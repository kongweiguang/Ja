// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 持久布局只记录终端休眠意图，刻意排除原生 session、输出、环境变量和 scrollback；
 * 重启后仅恢复 shape/profile/cwd，并在标签首次激活时创建全新的 PTY。
 */
const TERMINAL_LAYOUT_VERSION = 1 as const;
export const MAX_TERMINAL_PANES = 8;
export const MAX_PANES_PER_TERMINAL_TAB = 4;
/**
 * 原始输入检查预算略宽于八窗格输出合同，使恶意拓扑在递归投影与确定性 ID 分配前
 * 就受到固定上限约束；投影仅用于精确校验，不构成旧数据迁移。
 */
export const MAX_TERMINAL_LAYOUT_TABS = 8;
const MAX_TERMINAL_LAYOUT_NODES = 64;
export const MAX_TERMINAL_LAYOUT_DEPTH = 8;
export const MAX_TERMINAL_LAYOUT_INSPECTION_STEPS =
  1 + MAX_TERMINAL_LAYOUT_TABS + MAX_TERMINAL_LAYOUT_NODES;
export const MAX_TERMINAL_RELATIVE_CWD_LENGTH = 4_096;
export const MIN_SPLIT_RATIO = 0.2;
export const MAX_SPLIT_RATIO = 0.8;

const TERMINAL_LAYOUT_KEYS = ["version", "workspaceId", "tabs", "activeTabId"] as const;
const TERMINAL_TAB_KEYS = ["tabId", "title", "profile", "root", "activePaneId"] as const;
const TERMINAL_TAB_WITH_CWD_KEYS = [...TERMINAL_TAB_KEYS, "relativeCwd"] as const;
const TERMINAL_PANE_KEYS = ["kind", "paneId"] as const;
const TERMINAL_SPLIT_KEYS = ["kind", "splitId", "orientation", "ratio", "first", "second"] as const;

export const TERMINAL_PROFILES = ["default", "power_shell", "cmd", "bash", "zsh", "fish"] as const;
export type TerminalProfile = (typeof TERMINAL_PROFILES)[number];
export type TerminalSplitOrientation = "horizontal" | "vertical";

export interface TerminalTabCreateOptions {
  title?: string;
  profile?: TerminalProfile;
  relativeCwd?: string;
}

export interface TerminalPaneLayout {
  kind: "pane";
  paneId: string;
}

export interface TerminalSplitLayout {
  kind: "split";
  splitId: string;
  orientation: TerminalSplitOrientation;
  ratio: number;
  first: TerminalLayoutNode;
  second: TerminalLayoutNode;
}

export type TerminalLayoutNode = TerminalPaneLayout | TerminalSplitLayout;

export interface TerminalTabLayout {
  tabId: string;
  title: string;
  profile: TerminalProfile;
  relativeCwd?: string;
  root: TerminalLayoutNode;
  activePaneId: string;
}

export interface TerminalLayoutV1 {
  version: typeof TERMINAL_LAYOUT_VERSION;
  workspaceId: string;
  tabs: TerminalTabLayout[];
  activeTabId: string | null;
}

export interface TerminalPaneLocation {
  tabId: string;
  paneId: string;
  pane: TerminalPaneLayout;
}

type TerminalLocalIdPrefix = "pane" | "split" | "tab";

interface TerminalLayoutInspectionBudget {
  remaining: number;
}

/** 消耗一次结构检查预算；所有持久化输入遍历共享同一硬上限，避免未来扩容重新引入同步冻结。 */
function consumeTerminalLayoutInspection(budget: TerminalLayoutInspectionBudget): boolean {
  if (budget.remaining <= 0) return false;
  budget.remaining -= 1;
  return true;
}

/** 只接受 dormant schema 的精确自有字段集合，旧 runtime/secret 与未知字段都触发一次安全回写。 */
function hasExactTerminalKeys(
  value: Record<string, unknown>,
  expected: readonly string[],
): boolean {
  const keys = Object.keys(value);
  return keys.length === expected.length && keys.every((key) => expected.includes(key));
}

/**
 * 从当前布局分配首个空闲且确定的 UI ID；调用方持有集合，避免依赖重启后丢失的
 * 模块历史，并在返回前立即登记，保证一次 mutation 不会重复分配。
 */
function createLocalId(prefix: TerminalLocalIdPrefix, usedIds: Set<string>): string {
  let suffix = 1;
  let candidate = `${prefix}-${suffix.toString(36)}`;
  while (usedIds.has(candidate)) {
    suffix += 1;
    candidate = `${prefix}-${suffix.toString(36)}`;
  }
  usedIds.add(candidate);
  return candidate;
}

/**
 * 投影阶段仅保留一次有效持久标识，并为缺失或重复值生成确定性候选；最终恢复仍需
 * 通过精确等价校验，因此该内部修复不会形成兼容或迁移路径。
 */
function sanitizeLocalId(
  value: unknown,
  prefix: TerminalLocalIdPrefix,
  usedIds: Set<string>,
): string {
  if (typeof value === "string" && value.length > 0 && value.length <= 128 && !usedIds.has(value)) {
    usedIds.add(value);
    return value;
  }
  return createLocalId(prefix, usedIds);
}

/** 收集树内全部标识，防止后续新增或分屏 mutation 复用已恢复 ID。 */
function collectNodeIds(node: TerminalLayoutNode, usedIds: Set<string>): void {
  if (node.kind === "pane") {
    usedIds.add(node.paneId);
    return;
  }
  usedIds.add(node.splitId);
  collectNodeIds(node.first, usedIds);
  collectNodeIds(node.second, usedIds);
}

/** 只从持久布局意图建立分配作用域，绝不读取原生 PTY 运行态。 */
function collectLayoutIds(layout: TerminalLayoutV1): Set<string> {
  const usedIds = new Set<string>();
  for (const tab of layout.tabs) {
    usedIds.add(tab.tabId);
    collectNodeIds(tab.root, usedIds);
  }
  return usedIds;
}

/** 将分屏比例限定在合同范围，避免手工编辑或指针噪声破坏布局可用性。 */
export function clampSplitRatio(ratio: number): number {
  if (!Number.isFinite(ratio)) return 0.5;
  return Math.min(MAX_SPLIT_RATIO, Math.max(MIN_SPLIT_RATIO, ratio));
}

/**
 * 只规范化工作区相对 cwd；盘符相对路径、绝对路径、遍历和控制字符一律拒绝，
 * 使前端意图永远不能扩大 Rust 维护的工作区边界。
 */
export function normalizeRelativeCwd(value: unknown): string | undefined {
  if (typeof value !== "string" || value.length > MAX_TERMINAL_RELATIVE_CWD_LENGTH)
    return undefined;
  const normalized = value.trim();
  if (normalized.length === 0 || /^[A-Za-z]:/u.test(normalized) || /^[\\/]/u.test(normalized))
    return undefined;
  if (containsControlCharacters(normalized)) return undefined;
  if (normalized.split(/[\\/]+/u).some((segment) => segment === "..")) return undefined;
  return normalized;
}

/** 空字段表示工作区根目录；其它 cwd 在持久化前必须通过完整安全校验。 */
export function isValidTerminalRelativeCwd(value: string): boolean {
  if (value.length > MAX_TERMINAL_RELATIVE_CWD_LENGTH) return false;
  return value.trim().length === 0 || normalizeRelativeCwd(value) !== undefined;
}

/** 显式检查控制字符，避免在正则中嵌入不可见字面量并保持审计可读性。 */
function containsControlCharacters(value: string): boolean {
  for (const character of value) {
    const code = character.charCodeAt(0);
    if (code <= 0x1f || code === 0x7f) return true;
  }
  return false;
}

/** 创建一个仅含休眠意图的单窗格布局；profile 由调用方在平台探测后选择。 */
export function createDefaultTerminalLayout(
  workspaceId: string,
  profile: TerminalProfile = "default",
): TerminalLayoutV1 {
  const usedIds = new Set<string>();
  const paneId = createLocalId("pane", usedIds);
  const tabId = createLocalId("tab", usedIds);
  return {
    version: TERMINAL_LAYOUT_VERSION,
    workspaceId,
    tabs: [
      { tabId, title: "终端 1", profile, root: { kind: "pane", paneId }, activePaneId: paneId },
    ],
    activeTabId: tabId,
  };
}

/** 持久化标签意图前只接受当前关闭的 Shell profile 联合。 */
function isProfile(value: unknown): value is TerminalProfile {
  return typeof value === "string" && TERMINAL_PROFILES.includes(value as TerminalProfile);
}

/** 以 `default` 优先、闭集声明顺序次之选择回退，避免 Rust 返回顺序影响持久化修复。 */
function terminalProfileFallback(
  supportedProfiles: readonly TerminalProfile[],
): TerminalProfile | undefined {
  if (supportedProfiles.includes("default")) return "default";
  return TERMINAL_PROFILES.find((profile) => supportedProfiles.includes(profile));
}

/** 同时校验全局枚举和本次 Rust 探测集合，防止测试或陈旧状态绕过平台闭集。 */
function isSupportedTerminalProfile(
  value: unknown,
  supportedProfiles: readonly TerminalProfile[],
): value is TerminalProfile {
  return isProfile(value) && supportedProfiles.includes(value);
}

/** 投影阶段校验分屏方向；非法值只生成候选，最终精确校验仍会整体拒绝输入。 */
function isOrientation(value: unknown): value is TerminalSplitOrientation {
  return value === "horizontal" || value === "vertical";
}

/**
 * 在递归修复和 ID 分配前执行 O(1) Tab 上限早退，并用显式操作预算限制后续
 * 拓扑检查；损坏、循环或深斜树只能回退为默认 dormant 布局，不能阻塞渲染线程。
 */
function isTerminalLayoutStructureWithinBudget(tabs: readonly unknown[]): boolean {
  if (tabs.length > MAX_TERMINAL_LAYOUT_TABS) return false;
  const budget: TerminalLayoutInspectionBudget = {
    remaining: MAX_TERMINAL_LAYOUT_INSPECTION_STEPS,
  };
  if (!consumeTerminalLayoutInspection(budget)) return false;
  let totalNodes = 0;
  for (const rawTab of tabs) {
    if (!consumeTerminalLayoutInspection(budget)) return false;
    if (rawTab === null || typeof rawTab !== "object") continue;
    const root = (rawTab as Record<string, unknown>)["root"];
    if (root === null || typeof root !== "object") continue;
    const pending: Array<{ value: unknown; depth: number }> = [{ value: root, depth: 1 }];
    const visited = new Set<object>();
    while (pending.length > 0) {
      const current = pending.pop();
      if (current === undefined || current.value === null || typeof current.value !== "object")
        continue;
      if (!consumeTerminalLayoutInspection(budget)) return false;
      if (current.depth > MAX_TERMINAL_LAYOUT_DEPTH || visited.has(current.value)) return false;
      visited.add(current.value);
      totalNodes += 1;
      if (totalNodes > MAX_TERMINAL_LAYOUT_NODES) return false;
      const node = current.value as Record<string, unknown>;
      if (node["kind"] === "split") {
        pending.push(
          { value: node["second"], depth: current.depth + 1 },
          { value: node["first"], depth: current.depth + 1 },
        );
      }
    }
  }
  return true;
}

/** 在固定操作预算内核对一个原始节点是否已经是可直接持久化的 dormant 白名单结构。 */
function isDormantTerminalNodeEquivalent(
  value: unknown,
  expected: TerminalLayoutNode,
  budget: TerminalLayoutInspectionBudget,
): boolean {
  const pending: Array<{ value: unknown; expected: TerminalLayoutNode }> = [{ value, expected }];
  while (pending.length > 0) {
    const current = pending.pop();
    if (current === undefined || !consumeTerminalLayoutInspection(budget)) return false;
    if (current.value === null || typeof current.value !== "object" || Array.isArray(current.value))
      return false;
    const candidate = current.value as Record<string, unknown>;
    if (current.expected.kind === "pane") {
      if (!hasExactTerminalKeys(candidate, TERMINAL_PANE_KEYS)) return false;
      if (candidate["kind"] !== "pane" || candidate["paneId"] !== current.expected.paneId)
        return false;
      continue;
    }
    if (!hasExactTerminalKeys(candidate, TERMINAL_SPLIT_KEYS)) return false;
    if (
      candidate["kind"] !== "split" ||
      candidate["splitId"] !== current.expected.splitId ||
      candidate["orientation"] !== current.expected.orientation ||
      candidate["ratio"] !== current.expected.ratio
    )
      return false;
    pending.push(
      { value: candidate["second"], expected: current.expected.second },
      { value: candidate["first"], expected: current.expected.first },
    );
  }
  return true;
}

/**
 * 判断恢复源是否已与清洗后的 dormant 布局完全等价；结果为 false 时 hook 会
 * 主动覆盖旧持久化值，从存储中移除 session、环境变量、命令和 scrollback。
 */
function isDormantTerminalLayoutEquivalent(value: unknown, expected: TerminalLayoutV1): boolean {
  try {
    const budget: TerminalLayoutInspectionBudget = {
      remaining: MAX_TERMINAL_LAYOUT_INSPECTION_STEPS,
    };
    if (!consumeTerminalLayoutInspection(budget)) return false;
    if (value === null || typeof value !== "object" || Array.isArray(value)) return false;
    const candidate = value as Record<string, unknown>;
    if (!hasExactTerminalKeys(candidate, TERMINAL_LAYOUT_KEYS)) return false;
    if (
      candidate["version"] !== expected.version ||
      candidate["workspaceId"] !== expected.workspaceId ||
      candidate["activeTabId"] !== expected.activeTabId ||
      !Array.isArray(candidate["tabs"]) ||
      candidate["tabs"].length !== expected.tabs.length
    )
      return false;

    for (let index = 0; index < expected.tabs.length; index += 1) {
      if (!consumeTerminalLayoutInspection(budget)) return false;
      const rawTab = candidate["tabs"][index];
      const expectedTab = expected.tabs[index];
      if (
        expectedTab === undefined ||
        rawTab === null ||
        typeof rawTab !== "object" ||
        Array.isArray(rawTab)
      )
        return false;
      const tab = rawTab as Record<string, unknown>;
      const expectedKeys =
        expectedTab.relativeCwd === undefined ? TERMINAL_TAB_KEYS : TERMINAL_TAB_WITH_CWD_KEYS;
      if (!hasExactTerminalKeys(tab, expectedKeys)) return false;
      if (
        tab["tabId"] !== expectedTab.tabId ||
        tab["title"] !== expectedTab.title ||
        tab["profile"] !== expectedTab.profile ||
        tab["activePaneId"] !== expectedTab.activePaneId ||
        tab["relativeCwd"] !== expectedTab.relativeCwd ||
        !isDormantTerminalNodeEquivalent(tab["root"], expectedTab.root, budget)
      )
        return false;
    }
    return true;
  } catch {
    return false;
  }
}

/** 按视觉顺序遍历分屏树，该顺序也是确定性 ID 投影顺序。 */
export function flattenPanes(node: TerminalLayoutNode): TerminalPaneLayout[] {
  if (node.kind === "pane") return [node];
  return [...flattenPanes(node.first), ...flattenPanes(node.second)];
}

/** 只统计布局叶子，不读取 runtime 或 session 状态。 */
export function countPanes(node: TerminalLayoutNode): number {
  return node.kind === "pane" ? 1 : countPanes(node.first) + countPanes(node.second);
}

/** 统一统计工作区叶子，使领域模型与可见控件执行同一原生 PTY 预算。 */
export function countTerminalPanes(layout: TerminalLayoutV1): number {
  return layout.tabs.reduce((sum, tab) => sum + countPanes(tab.root), 0);
}

/** 阻止新标签创建第九个原生 PTY，同时允许空布局重新建立首个标签。 */
export function canAddTerminalTab(layout: TerminalLayoutV1): boolean {
  return countTerminalPanes(layout) < MAX_TERMINAL_PANES;
}

/** 分屏准入同时遵守单标签四窗格与工作区八窗格上限。 */
export function canSplitTerminalPane(
  layout: TerminalLayoutV1,
  tabId: string,
  paneId: string,
): boolean {
  const target = layout.tabs.find((tab) => tab.tabId === tabId);
  return (
    target !== undefined &&
    flattenPanes(target.root).some((pane) => pane.paneId === paneId) &&
    countPanes(target.root) < MAX_PANES_PER_TERMINAL_TAB &&
    countTerminalPanes(layout) < MAX_TERMINAL_PANES
  );
}

/** 在单标签和全局预算内递归生成节点候选，结果仍需通过精确 schema 等价校验。 */
function sanitizeNode(
  value: unknown,
  usedIds: Set<string>,
  seenPaneIds: Set<string>,
  remaining: { count: number },
): TerminalLayoutNode | undefined {
  if (remaining.count <= 0 || value === null || typeof value !== "object") return undefined;
  const candidate = value as Record<string, unknown>;
  if (candidate["kind"] === "pane") {
    const paneId = sanitizeLocalId(candidate["paneId"], "pane", usedIds);
    seenPaneIds.add(paneId);
    remaining.count -= 1;
    return { kind: "pane", paneId };
  }
  if (candidate["kind"] !== "split") return undefined;
  const first = sanitizeNode(candidate["first"], usedIds, seenPaneIds, remaining);
  const second = sanitizeNode(candidate["second"], usedIds, seenPaneIds, remaining);
  if (first === undefined) return second;
  if (second === undefined) return first;
  return {
    kind: "split",
    splitId: sanitizeLocalId(candidate["splitId"], "split", usedIds),
    orientation: isOrientation(candidate["orientation"]) ? candidate["orientation"] : "horizontal",
    ratio: clampSplitRatio(typeof candidate["ratio"] === "number" ? candidate["ratio"] : 0.5),
    first,
    second,
  };
}

/**
 * 将已通过外层版本检查的输入投影为 v1 布局候选；该函数只用于精确等价校验，
 * 不能把修复结果直接当作迁移数据写回，否则旧 runtime 字段会被误解释为当前状态。
 */
function projectTerminalLayoutV1(value: unknown, workspaceId: string): TerminalLayoutV1 {
  const fallbackProfile = "default";
  if (value === null || typeof value !== "object")
    return createDefaultTerminalLayout(workspaceId, fallbackProfile);
  const candidate = value as Record<string, unknown>;
  if (
    candidate["version"] !== TERMINAL_LAYOUT_VERSION ||
    candidate["workspaceId"] !== workspaceId ||
    !Array.isArray(candidate["tabs"])
  ) {
    return createDefaultTerminalLayout(workspaceId, fallbackProfile);
  }
  if (!isTerminalLayoutStructureWithinBudget(candidate["tabs"])) {
    return createDefaultTerminalLayout(workspaceId, fallbackProfile);
  }
  const usedIds = new Set<string>();
  const seenPaneIds = new Set<string>();
  const tabs: TerminalTabLayout[] = [];
  const tabsValue = candidate["tabs"] as unknown[];
  for (const rawTab of tabsValue) {
    if (seenPaneIds.size >= MAX_TERMINAL_PANES || rawTab === null || typeof rawTab !== "object")
      break;
    const tabValue = rawTab as Record<string, unknown>;
    const tabId = sanitizeLocalId(tabValue["tabId"], "tab", usedIds);
    const remaining = {
      count: Math.min(MAX_PANES_PER_TERMINAL_TAB, MAX_TERMINAL_PANES - seenPaneIds.size),
    };
    const root = sanitizeNode(tabValue["root"], usedIds, seenPaneIds, remaining);
    if (root === undefined) continue;
    const panes = flattenPanes(root);
    const activePaneId =
      typeof tabValue["activePaneId"] === "string" &&
      panes.some((pane) => pane.paneId === tabValue["activePaneId"])
        ? tabValue["activePaneId"]
        : panes[0]?.paneId;
    if (activePaneId === undefined) continue;
    const title =
      typeof tabValue["title"] === "string" && tabValue["title"].trim().length > 0
        ? tabValue["title"].slice(0, 80)
        : `终端 ${tabs.length + 1}`;
    const profile = isSupportedTerminalProfile(tabValue["profile"], TERMINAL_PROFILES)
      ? tabValue["profile"]
      : fallbackProfile;
    const relativeCwd = normalizeRelativeCwd(tabValue["relativeCwd"]);
    tabs.push({
      tabId,
      title,
      profile,
      ...(relativeCwd === undefined ? {} : { relativeCwd }),
      root,
      activePaneId,
    });
  }
  const activeTabId =
    typeof candidate["activeTabId"] === "string" &&
    tabs.some((tab) => tab.tabId === candidate["activeTabId"])
      ? candidate["activeTabId"]
      : (tabs[0]?.tabId ?? null);
  return { version: TERMINAL_LAYOUT_VERSION, workspaceId, tabs, activeTabId };
}

/**
 * 只恢复字段、结构和值都精确符合当前 v1 schema 的休眠布局；任何旧 runtime、
 * 未知字段或损坏值都直接回到默认布局，避免前端承担历史状态迁移和秘密清洗职责。
 * 平台 profile 变化属于当前运行能力收敛，在精确校验后单独修复，不放宽持久化 schema。
 */
export function parseTerminalLayout(
  value: unknown,
  workspaceId: string,
  supportedProfiles: readonly TerminalProfile[] = TERMINAL_PROFILES,
): TerminalLayoutV1 | undefined {
  const projected = projectTerminalLayoutV1(value, workspaceId);
  if (!isDormantTerminalLayoutEquivalent(value, projected)) return undefined;
  return repairTerminalLayoutProfiles(projected, supportedProfiles);
}

/**
 * Runtime 需要始终可用的布局，因此只在当前严格 parser 拒绝介质时创建全新默认值；
 * 拒绝结果不会写回存储，避免把读取恢复误当作历史迁移。
 */
export function sanitizeTerminalLayout(
  value: unknown,
  workspaceId: string,
  supportedProfiles: readonly TerminalProfile[] = TERMINAL_PROFILES,
): TerminalLayoutV1 {
  return (
    parseTerminalLayout(value, workspaceId, supportedProfiles) ??
    createDefaultTerminalLayout(
      workspaceId,
      terminalProfileFallback(supportedProfiles) ?? "default",
    )
  );
}

/** 已完成结构校验的布局只修复 unsupported profile，并在无需修复时保留对象身份。 */
export function repairTerminalLayoutProfiles(
  layout: TerminalLayoutV1,
  supportedProfiles: readonly TerminalProfile[],
): TerminalLayoutV1 {
  const fallbackProfile = terminalProfileFallback(supportedProfiles);
  if (fallbackProfile === undefined) return layout;
  let changed = false;
  const tabs = layout.tabs.map((tab) => {
    if (isSupportedTerminalProfile(tab.profile, supportedProfiles)) return tab;
    changed = true;
    return { ...tab, profile: fallbackProfile };
  });
  return changed ? { ...layout, tabs } : layout;
}

/** 以不可变方式把一个窗格叶子替换为分屏节点，避免污染调用方持有的快照。 */
function replaceNode(
  node: TerminalLayoutNode,
  targetPaneId: string,
  replacement: TerminalLayoutNode,
): TerminalLayoutNode {
  if (node.kind === "pane") return node.paneId === targetPaneId ? replacement : node;
  return {
    ...node,
    first: replaceNode(node.first, targetPaneId, replacement),
    second: replaceNode(node.second, targetPaneId, replacement),
  };
}

/** 删除一个叶子并折叠只剩单子节点的分屏，保持树结构最小化。 */
function collapseNode(node: TerminalLayoutNode, paneId: string): TerminalLayoutNode | undefined {
  if (node.kind === "pane") return node.paneId === paneId ? undefined : node;
  const first = collapseNode(node.first, paneId);
  const second = collapseNode(node.second, paneId);
  if (first === undefined) return second;
  if (second === undefined) return first;
  return { ...node, first, second };
}

/** 应用单标签更新，并在删除后保证 activeTabId 仍指向有效标签。 */
function updateTab(
  layout: TerminalLayoutV1,
  tabId: string,
  update: (tab: TerminalTabLayout) => TerminalTabLayout | undefined,
): TerminalLayoutV1 {
  const tabs: TerminalTabLayout[] = [];
  for (const tab of layout.tabs) {
    if (tab.tabId !== tabId) {
      tabs.push(tab);
      continue;
    }
    const updated = update(tab);
    if (updated !== undefined) tabs.push(updated);
  }
  const activeTabId = tabs.some((tab) => tab.tabId === layout.activeTabId)
    ? layout.activeTabId
    : (tabs[0]?.tabId ?? null);
  return { ...layout, tabs, activeTabId };
}

/** 新增一个 typed 休眠标签；显式 profile 必须属于当前 Rust 探测集合，不接受历史字符串重载。 */
export function addTerminalTab(
  layout: TerminalLayoutV1,
  options?: TerminalTabCreateOptions,
  supportedProfiles: readonly TerminalProfile[] = TERMINAL_PROFILES,
): TerminalLayoutV1 {
  if (!canAddTerminalTab(layout)) return layout;
  const creation = resolveTerminalTabCreateOptions(layout.tabs.length, options, supportedProfiles);
  if (creation === undefined) return layout;
  const usedIds = collectLayoutIds(layout);
  const paneId = createLocalId("pane", usedIds);
  const tabId = createLocalId("tab", usedIds);
  return {
    ...layout,
    tabs: [
      ...layout.tabs,
      { tabId, ...creation, root: { kind: "pane", paneId }, activePaneId: paneId },
    ],
    activeTabId: tabId,
  };
}

/** 在分配 ID 前把创建输入收敛为最小持久化意图，避免失败提交产生可观察副作用。 */
function resolveTerminalTabCreateOptions(
  tabCount: number,
  options: TerminalTabCreateOptions | undefined,
  supportedProfiles: readonly TerminalProfile[],
): Pick<TerminalTabLayout, "title" | "profile" | "relativeCwd"> | undefined {
  if (options !== undefined && (options === null || typeof options !== "object")) return undefined;
  const candidate: TerminalTabCreateOptions = options ?? {};
  const profile = candidate.profile ?? terminalProfileFallback(supportedProfiles);
  if (!isSupportedTerminalProfile(profile, supportedProfiles)) return undefined;
  if (
    candidate.relativeCwd !== undefined &&
    (typeof candidate.relativeCwd !== "string" ||
      !isValidTerminalRelativeCwd(candidate.relativeCwd))
  )
    return undefined;
  const title =
    typeof candidate.title === "string" && candidate.title.trim().length > 0
      ? candidate.title.trim().slice(0, 80)
      : `终端 ${tabCount + 1}`;
  const relativeCwd = normalizeRelativeCwd(candidate.relativeCwd);
  return { title, profile, ...(relativeCwd === undefined ? {} : { relativeCwd }) };
}

/** 删除一个外层标签；关闭最后一个标签后保留合法的空布局。 */
export function removeTerminalTab(layout: TerminalLayoutV1, tabId: string): TerminalLayoutV1 {
  return updateTab(layout, tabId, () => undefined);
}

/** 只切换活动外层标签，不触碰任何休眠窗格运行态。 */
export function setActiveTerminalTab(layout: TerminalLayoutV1, tabId: string): TerminalLayoutV1 {
  return layout.tabs.some((tab) => tab.tabId === tabId)
    ? { ...layout, activeTabId: tabId }
    : layout;
}

/** 在指定标签内更新活动窗格，使分屏或缩放后的焦点身份保持稳定。 */
export function setActiveTerminalPane(
  layout: TerminalLayoutV1,
  tabId: string,
  paneId: string,
): TerminalLayoutV1 {
  return updateTab(layout, tabId, (tab) =>
    flattenPanes(tab.root).some((pane) => pane.paneId === paneId)
      ? { ...tab, activePaneId: paneId }
      : tab,
  );
}

/** 在单标签和全局预算允许时分割窗格，越界操作保持无副作用。 */
export function splitTerminalPane(
  layout: TerminalLayoutV1,
  tabId: string,
  paneId: string,
  orientation: TerminalSplitOrientation,
): TerminalLayoutV1 {
  if (!canSplitTerminalPane(layout, tabId, paneId)) return layout;
  const usedIds = collectLayoutIds(layout);
  const newPaneId = createLocalId("pane", usedIds);
  const replacement: TerminalSplitLayout = {
    kind: "split",
    splitId: createLocalId("split", usedIds),
    orientation,
    ratio: 0.5,
    first: { kind: "pane", paneId },
    second: { kind: "pane", paneId: newPaneId },
  };
  return updateTab(layout, tabId, (tab) => ({
    ...tab,
    root: replaceNode(tab.root, paneId, replacement),
    activePaneId: newPaneId,
  }));
}

/** 删除窗格并折叠父分屏；标签失去最后一个窗格时同步删除标签。 */
export function removeTerminalPane(
  layout: TerminalLayoutV1,
  tabId: string,
  paneId: string,
): TerminalLayoutV1 {
  return updateTab(layout, tabId, (tab) => {
    const root = collapseNode(tab.root, paneId);
    if (root === undefined) return undefined;
    const remaining = flattenPanes(root);
    const activePaneId = remaining.some((pane) => pane.paneId === tab.activePaneId)
      ? tab.activePaneId
      : remaining[0]?.paneId;
    return activePaneId === undefined ? undefined : { ...tab, root, activePaneId };
  });
}

/** 使用与拖拽手柄一致的 0.2 至 0.8 边界更新分屏比例。 */
export function setTerminalSplitRatio(
  layout: TerminalLayoutV1,
  tabId: string,
  splitId: string,
  ratio: number,
): TerminalLayoutV1 {
  return updateTab(layout, tabId, (tab) => ({
    ...tab,
    root: updateSplitRatio(tab.root, splitId, ratio),
  }));
}

/** 遍历单标签树，只更新目标 splitId 的有界比例。 */
function updateSplitRatio(
  node: TerminalLayoutNode,
  splitId: string,
  ratio: number,
): TerminalLayoutNode {
  if (node.kind === "pane") return node;
  return {
    ...node,
    ratio: node.splitId === splitId ? clampSplitRatio(ratio) : node.ratio,
    first: updateSplitRatio(node.first, splitId, ratio),
    second: updateSplitRatio(node.second, splitId, ratio),
  };
}

/** 为渲染动作定位窗格，同时不向组件暴露树遍历细节。 */
export function findTerminalPane(
  layout: TerminalLayoutV1,
  paneId: string,
): TerminalPaneLocation | undefined {
  for (const tab of layout.tabs) {
    const pane = flattenPanes(tab.root).find((candidate) => candidate.paneId === paneId);
    if (pane !== undefined) return { tabId: tab.tabId, paneId, pane };
  }
  return undefined;
}
