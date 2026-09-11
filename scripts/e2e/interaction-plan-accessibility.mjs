// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Interaction/Plan 的真实 WebView2 辅助技术证据。
 *
 * 这里直接读取 Chromium Accessibility domain 的完整 AX tree，再把结果裁剪为
 * 可审计的布尔值和长度摘要。它不读取 DOM、不改变焦点、不发送键盘事件，也不
 * 试图把“屏幕阅读器已经朗读”写成证据；Narrator 仍须由人工或专门的系统测试验收。
 */

const CONTROL_ROLES = new Set(["radio", "checkbox"]);
const GROUP_ROLES = new Set(["group", "radiogroup"]);
const NON_QUESTION_ANCESTOR_ROLES = new Set(["rootwebarea", "window", "application"]);

/** 把 CDP AXValue 的递归 value 包装解包为有限标量。 */
function scalarValue(value, depth = 0) {
  if (depth > 4 || value === null || value === undefined) return undefined;
  if (typeof value !== "object") return value;
  if (Object.prototype.hasOwnProperty.call(value, "value")) {
    return scalarValue(value.value, depth + 1);
  }
  return undefined;
}

/** 返回 AX node 的规范化角色；未知或空角色视为缺失。 */
function nodeRole(node) {
  const role = scalarValue(node?.role);
  return typeof role === "string" && role.trim() !== "" ? role.trim().toLowerCase() : undefined;
}

/** 返回 AX node 的 accessible name；名称正文不会进入最终证据。 */
function accessibleName(node) {
  const name = scalarValue(node?.name);
  return typeof name === "string" ? name.trim() : "";
}

/** 读取 AX node 的 description；该字段承载 aria-describedby 的必填说明。 */
function accessibleDescription(node) {
  const description = scalarValue(node?.description);
  return typeof description === "string" ? description.trim() : "";
}

/** 将名称投影为不包含题目或选项正文的存在性摘要。 */
function nameEvidence(name) {
  return { present: name.length > 0, length: name.length };
}

/** 读取 AX node 的属性值，同时兼容 Chromium 的 properties 和直接字段形状。 */
function propertyValue(node, propertyName) {
  if (node !== null && typeof node === "object" && node[propertyName] !== undefined) {
    return scalarValue(node[propertyName]);
  }
  const property = Array.isArray(node?.properties)
    ? node.properties.find((candidate) => candidate?.name === propertyName)
    : undefined;
  if (property !== undefined) return scalarValue(property.value);
  const attributes = node?.attributes;
  if (attributes !== null && typeof attributes === "object") {
    if (!Array.isArray(attributes) && attributes[propertyName] !== undefined) {
      return scalarValue(attributes[propertyName]);
    }
    if (Array.isArray(attributes)) {
      for (let index = 0; index < attributes.length; index += 2) {
        if (attributes[index] === propertyName) return scalarValue(attributes[index + 1]) ?? true;
      }
    }
  }
  return undefined;
}

/** 将 AX 布尔/三态属性规范化，避免把任意字符串误认成已选中或必答。 */
function booleanOrMixed(value) {
  if (value === true || value === false || value === "mixed") return value;
  if (typeof value !== "string") return undefined;
  const normalized = value.trim().toLowerCase();
  if (normalized === "true") return true;
  if (normalized === "false") return false;
  if (normalized === "mixed") return "mixed";
  return undefined;
}

/** 将可访问说明中的明确必填/可选文案转换为题目级语义，未知文案保持未确定。 */
function requiredTextState(value) {
  if (typeof value !== "string") return undefined;
  const normalized = value.trim().toLowerCase();
  if (
    normalized === "必填" ||
    normalized === "required" ||
    normalized === "required field" ||
    normalized === "必填字段"
  )
    return true;
  if (
    normalized === "可选" ||
    normalized === "optional" ||
    normalized === "not required" ||
    normalized === "non-required" ||
    normalized === "非必填"
  )
    return false;
  return undefined;
}

/** 读取 question/group 的 required 语义；未暴露语义时返回 undefined 而非猜测默认值。 */
function requiredState(node) {
  const value = propertyValue(node, "required");
  if (value !== undefined) return booleanOrMixed(value);
  const ariaValue = propertyValue(node, "aria-required");
  return ariaValue === undefined ? undefined : booleanOrMixed(ariaValue);
}

/** 读取 radio/checkbox 的 checked 语义；AX 树缺字段时必须失败。 */
function checkedState(node) {
  return booleanOrMixed(propertyValue(node, "checked"));
}

/** 读取并校验稳定 AX node ID，供树内关系解析使用，不写入输出证据。 */
function nodeId(node) {
  if (typeof node?.nodeId === "string" && node.nodeId !== "") return node.nodeId;
  if (Number.isSafeInteger(node?.nodeId)) return String(node.nodeId);
  throw new InteractionPlanAccessibilityError("AX_NODE_ID_MISSING", "AX node 缺少稳定 nodeId");
}

/** 把错误压缩为可供验收报告使用的稳定 code，避免泄漏页面正文或运行环境。 */
export class InteractionPlanAccessibilityError extends Error {
  /** 创建不携带 AX 原文的辅助技术证据错误。 */
  constructor(code, message) {
    super(message);
    this.name = "InteractionPlanAccessibilityError";
    this.code = code;
  }
}

/** 将完整 AX node 数组建成节点、父节点和子节点索引。 */
function indexAXTree(nodes) {
  if (!Array.isArray(nodes) || nodes.length === 0) {
    throw new InteractionPlanAccessibilityError("AX_TREE_MISSING", "Accessibility AX tree 缺失");
  }
  const indexedNodes = [];
  const byId = new Map();
  for (const node of nodes) {
    if (node === null || typeof node !== "object" || Array.isArray(node)) {
      throw new InteractionPlanAccessibilityError(
        "AX_TREE_INVALID",
        "Accessibility AX tree 节点无效",
      );
    }
    const id = nodeId(node);
    if (byId.has(id)) {
      throw new InteractionPlanAccessibilityError(
        "AX_NODE_ID_DUPLICATE",
        "Accessibility AX tree nodeId 重复",
      );
    }
    const indexed = { id, node };
    indexedNodes.push(indexed);
    byId.set(id, indexed);
  }
  const parentById = new Map();
  const childrenById = new Map(indexedNodes.map(({ id }) => [id, []]));
  for (const indexed of indexedNodes) {
    const explicitParent = indexed.node.parentId;
    if (explicitParent !== undefined && explicitParent !== null) {
      const parentId = String(explicitParent);
      if (byId.has(parentId)) parentById.set(indexed.id, parentId);
    }
    if (Array.isArray(indexed.node.childIds)) {
      for (const child of indexed.node.childIds) {
        const childId = String(child);
        if (!byId.has(childId)) continue;
        const children = childrenById.get(indexed.id);
        if (!children.includes(childId)) children.push(childId);
        if (!parentById.has(childId)) parentById.set(childId, indexed.id);
      }
    }
  }
  return { indexedNodes, byId, parentById, childrenById };
}

/** 沿 parent 关系查找控件最近的 question group。 */
function nearestQuestionGroup(controlId, tree) {
  let currentId = tree.parentById.get(controlId);
  while (currentId !== undefined) {
    const candidate = tree.byId.get(currentId);
    if (GROUP_ROLES.has(nodeRole(candidate?.node))) return candidate;
    currentId = tree.parentById.get(currentId);
  }
  return undefined;
}

/** 找到 group 外层最近的非通用命名容器，作为问题 label 的 AX 证据。 */
function questionLabelNode(group, tree) {
  let currentId = tree.parentById.get(group.id);
  while (currentId !== undefined) {
    const candidate = tree.byId.get(currentId);
    const role = nodeRole(candidate?.node);
    if (
      candidate !== undefined &&
      accessibleName(candidate.node) !== "" &&
      !NON_QUESTION_ANCESTOR_ROLES.has(role)
    ) {
      return candidate;
    }
    currentId = tree.parentById.get(currentId);
  }
  return undefined;
}

/** 检查问题容器内由界面明确暴露的必填/可选标记，不把没有标记猜成 optional。 */
function hasRequiredMarker(label, tree) {
  for (const indexed of tree.indexedNodes) {
    let currentId = tree.parentById.get(indexed.id);
    while (currentId !== undefined) {
      if (currentId === label.id) {
        const marker = requiredTextState(accessibleName(indexed.node));
        if (marker !== undefined) return marker;
        break;
      }
      currentId = tree.parentById.get(currentId);
    }
  }
  return undefined;
}

/** 在 group 缺少 required 时，只从 group description 或问题容器的明确标记推导题目状态。 */
function inferRequiredState(group, label, tree) {
  const groupState = requiredState(group.node);
  if (groupState === true || groupState === false) return groupState;
  const descriptionState = requiredTextState(accessibleDescription(group.node));
  if (descriptionState === true || descriptionState === false) return descriptionState;
  const markerState = hasRequiredMarker(label, tree);
  if (markerState === true || markerState === false) return markerState;
  return undefined;
}

/** 将当前问题的 AX tree 裁剪为角色、状态和名称长度摘要，不保留任何正文。 */
export function parseInteractionPlanAccessibilityTree(
  nodes,
  { groupIndex = 0, expectedRequired } = {},
) {
  if (!Number.isSafeInteger(groupIndex) || groupIndex < 0) {
    throw new TypeError("groupIndex 必须是非负整数");
  }
  if (expectedRequired !== undefined && typeof expectedRequired !== "boolean") {
    throw new TypeError("expectedRequired 必须是布尔值");
  }
  const tree = indexAXTree(nodes);
  const controls = tree.indexedNodes.filter(({ node }) => CONTROL_ROLES.has(nodeRole(node)));
  if (controls.length === 0) {
    throw new InteractionPlanAccessibilityError(
      "AX_CONTROL_ROLE_MISSING",
      "AX tree 缺少 radio 或 checkbox 控件",
    );
  }
  const groupedControls = new Map();
  for (const control of controls) {
    const group = nearestQuestionGroup(control.id, tree);
    if (group === undefined) {
      throw new InteractionPlanAccessibilityError(
        "AX_QUESTION_GROUP_MISSING",
        "控件缺少 question group",
      );
    }
    const current = groupedControls.get(group.id) ?? [];
    current.push(control);
    groupedControls.set(group.id, current);
  }
  const groups = [...groupedControls.entries()]
    .map(([id, groupControls]) => ({ group: tree.byId.get(id), controls: groupControls }))
    .sort(
      (left, right) =>
        tree.indexedNodes.indexOf(left.group) - tree.indexedNodes.indexOf(right.group),
    );
  const selected = groups[groupIndex];
  if (selected === undefined || selected.group === undefined) {
    throw new InteractionPlanAccessibilityError(
      "AX_QUESTION_GROUP_MISSING",
      "当前问题没有可验证的 question group",
    );
  }
  const group = selected.group;
  const groupRole = nodeRole(group.node);
  const groupName = accessibleName(group.node);
  if (!GROUP_ROLES.has(groupRole) || groupName === "") {
    throw new InteractionPlanAccessibilityError(
      "AX_QUESTION_GROUP_LABEL_MISSING",
      "question group 缺少可访问名称",
    );
  }
  const label = questionLabelNode(group, tree);
  if (label === undefined) {
    throw new InteractionPlanAccessibilityError(
      "AX_QUESTION_LABEL_MISSING",
      "问题容器缺少可访问名称",
    );
  }
  const required = inferRequiredState(group, label, tree);
  if (required === undefined) {
    throw new InteractionPlanAccessibilityError(
      "AX_REQUIRED_MISSING",
      "问题缺少明确 required 语义",
    );
  }
  if (expectedRequired !== undefined && required !== expectedRequired) {
    throw new InteractionPlanAccessibilityError(
      "AX_REQUIRED_CONFLICT",
      "问题 required 语义与期望不一致",
    );
  }
  const evidenceControls = selected.controls.map((control) => {
    const role = nodeRole(control.node);
    const name = accessibleName(control.node);
    if (!CONTROL_ROLES.has(role)) {
      throw new InteractionPlanAccessibilityError(
        "AX_CONTROL_ROLE_MISSING",
        "控件 role 不是 radio 或 checkbox",
      );
    }
    if (name === "") {
      throw new InteractionPlanAccessibilityError(
        "AX_CONTROL_NAME_MISSING",
        "radio 或 checkbox 缺少可访问名称",
      );
    }
    const checked = checkedState(control.node);
    if (checked === undefined) {
      throw new InteractionPlanAccessibilityError(
        "AX_CHECKED_MISSING",
        "radio 或 checkbox 缺少 checked 状态",
      );
    }
    const directRequired = requiredState(control.node);
    return {
      role,
      accessibleName: nameEvidence(name),
      checked,
      required: directRequired === true || directRequired === false ? directRequired : required,
    };
  });
  const roleCounts = { radio: 0, checkbox: 0 };
  for (const control of evidenceControls) roleCounts[control.role] += 1;
  return {
    version: 1,
    surface: "webview2_cdp",
    source: "Accessibility.getFullAXTree",
    axNodeCount: tree.indexedNodes.length,
    question: {
      label: nameEvidence(accessibleName(label.node)),
      groupLabel: nameEvidence(groupName),
      groupRole,
      required,
    },
    controls: evidenceControls,
    controlCount: evidenceControls.length,
    roleCounts,
  };
}

/** 从真实 WebView2 CDP 读取 AX tree，并保证 session 在只读采集后释放。 */
export async function captureInteractionPlanAccessibility(page, options = {}) {
  if (page === null || typeof page !== "object") {
    throw new TypeError("captureInteractionPlanAccessibility 需要 WebView2 page");
  }
  const suppliedSession = options.cdpSession;
  let client = suppliedSession;
  let ownsClient = false;
  if (client === undefined) {
    if (typeof page.context !== "function") {
      throw new InteractionPlanAccessibilityError(
        "AX_CDP_UNAVAILABLE",
        "WebView2 page 缺少 CDP context",
      );
    }
    try {
      client = await page.context().newCDPSession(page);
      ownsClient = true;
    } catch {
      throw new InteractionPlanAccessibilityError(
        "AX_CDP_UNAVAILABLE",
        "无法建立 WebView2 CDP session",
      );
    }
  }
  if (client === null || typeof client.send !== "function") {
    throw new TypeError("cdpSession 必须提供 send 方法");
  }
  let response;
  try {
    response = await client.send("Accessibility.getFullAXTree");
  } catch {
    throw new InteractionPlanAccessibilityError(
      "AX_TREE_UNAVAILABLE",
      "无法读取 WebView2 Accessibility AX tree",
    );
  } finally {
    if (ownsClient && typeof client.detach === "function")
      await client.detach().catch(() => undefined);
  }
  return parseInteractionPlanAccessibilityTree(response?.nodes, options);
}
