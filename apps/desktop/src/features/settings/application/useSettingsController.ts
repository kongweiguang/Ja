// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useMemo, useRef } from "react";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import type {
  AppearanceSettings,
  LoadedSettings,
  McpServerProjection,
  McpServerSave,
  McpToolProjection,
  AccessMode,
  DefaultModelSelection,
  ProviderModelSave,
  ProviderSave,
  SettingsDocument,
  SettingsConfigurationChange,
  SettingsMcpServer,
  SettingsSnapshot,
  SubagentSettings,
  SkillProjection,
} from "../domain/types";
import {
  type McpListResult,
  type McpToolsResult,
  type SettingsAdapter,
  type SettingsAppearancePort,
  type SettingsPorts,
  type SettingsRuntimePort,
  type SettingsRuntimeState,
  type SkillListResult,
} from "./ports";

interface BootProjection {
  status:
    | "idle"
    | "connecting"
    | "ready"
    | "busy"
    | "stopped"
    | "recovery_required"
    | "failed"
    | "degraded";
  message?: string;
}

interface SettingsControllerOptions {
  adapter: SettingsAdapter;
  appearancePort: SettingsAppearancePort;
  workspaceScope: { workspaceId: string; kind: "general" | "project" } | undefined;
  runtimeState: SettingsRuntimeState | undefined;
  boot: BootProjection;
  configurationChange: SettingsConfigurationChange | undefined;
  runtimePort: SettingsRuntimePort;
  /** 编辑面可延迟加载，避免隐藏设置页扫描另一个项目的目录。 */
  active?: boolean;
  catalogSection?: "skills" | "mcp" | "all" | "none";
}

/** Settings controller 只暴露 v1 Provider 聚合，模型选择始终归属已保存 Provider。 */
export interface SettingsController {
  loaded: LoadedSettings | undefined;
  /** 当前 Workspace 的 effective 配置，只供对话、Composer 与 Turn 准入消费。 */
  snapshot: SettingsSnapshot;
  /** 设置页唯一可编辑投影，始终来自用户级配置文档。 */
  globalSnapshot: SettingsSnapshot;
  /** Skills 单独提供全局与当前项目投影，避免其它设置页误把项目 effective 值当成用户写入基线。 */
  skillSettings: {
    global: SkillProjection[];
    project?: SkillProjection[];
    projectAvailable: boolean;
  };
  mcpSettings: {
    global: McpServerProjection[];
    project?: McpServerProjection[];
    projectAvailable: boolean;
    projectWorkspaceId?: string;
  };
  loading: boolean;
  synchronizing: boolean;
  scopeReady: boolean;
  /** 当前 effective 配置所属项目，用于 Workspace 切换准入和竞态隔离。 */
  scopeWorkspaceId: string | undefined;
  error: string | undefined;
  ports: SettingsPorts;
  /** 由 App Server 备份当前原文并恢复最近完整用户配置后再触发权威重读。 */
  restoreLastKnownGood(): Promise<void>;
  reload(): Promise<void>;
}

/** 将健康与停用事实分开收敛，避免把已发现但损坏的 Skill 显示成可用。 */
function skillStatus(
  status: "healthy" | "invalid" | "unavailable",
  enabled: boolean,
): SkillProjection["status"] {
  return status === "healthy" ? (enabled ? "ready" : "disabled") : "error";
}

/** 仅保存 Skill 摘要，禁止把正文或执行实现复制到 React 状态。 */
function projectSkills(result: SkillListResult): SkillProjection[] {
  return result.items.map((skill) => ({
    id: skill.skillId,
    name: skill.name,
    source: skill.scope,
    description: skill.description ?? "",
    enabled: skill.enabled,
    status: skillStatus(skill.status, skill.enabled),
  }));
}

/**
 * 把发现元数据与来源限定停用记录重新组合；缺失记录只供设置页移除。
 */
function skillSettingsProjection(
  discovered: SkillProjection[],
  disabledReferences: readonly string[],
  include: (skill: SkillProjection) => boolean,
): SkillProjection[] {
  const disabled = new Set(disabledReferences);
  const visible = discovered.filter(include).map((skill) => ({
    ...skill,
    enabled: !disabled.has(skill.id),
    status: disabled.has(skill.id) ? ("disabled" as const) : skill.status,
  }));
  const known = new Set(discovered.map((skill) => skill.id));
  for (const reference of disabledReferences) {
    if (known.has(reference)) continue;
    const delimiter = reference.indexOf(":");
    const source = reference.slice(0, delimiter);
    const name = reference.slice(delimiter + 1);
    if ((source !== "user" && source !== "ja" && source !== "project") || name.length === 0)
      continue;
    visible.push({
      id: reference,
      name,
      source,
      description: "",
      enabled: false,
      missing: true,
      status: "error",
      error: "文件已移除",
    });
  }
  return visible.sort((left, right) => left.name.localeCompare(right.name, "zh-Hans-CN"));
}

/** 映射脱敏 MCP 摘要；configured 只补展示字段，不把 enabled 冒充连接成功。 */
function projectMcpServers(
  result: McpListResult,
  configured: SettingsMcpServer[] = [],
): McpServerProjection[] {
  const configuredById = new Map(configured.map((item) => [item.mcpRevision, item]));
  return result.items.map((server) => {
    const saved = configuredById.get(server.mcpId);
    return {
      id: server.mcpId,
      mcpRevision: server.mcpId,
      name: server.name,
      transport: server.transport,
      endpoint: saved?.endpoint ?? "未配置",
      protocolVersion: saved?.protocolVersion ?? "2025-03-26",
      args: [...(saved?.args ?? [])],
      env: { ...(saved?.env ?? {}) },
      headers: { ...(saved?.headers ?? {}) },
      auth: saved?.auth ?? { kind: "none" },
      enabled: saved?.enabled ?? server.status !== "disabled",
      status:
        server.status === "healthy" || server.status === "available"
          ? ("connected" as const)
          : server.status === "disabled"
            ? ("disabled" as const)
            : server.status === "unavailable"
              ? ("error" as const)
              : ("unknown" as const),
      tools: [],
    };
  });
}

/** Java 的 available/healthy 都表示 probe 成功；configured 只表示已配置，不能提升为 connected。 */
function mcpProbeHealthy(status: "healthy" | "available" | "degraded" | "unavailable"): boolean {
  return status === "healthy" || status === "available";
}

/** MCP Tool 只投影名称和交互策略，避免在 UI store 长期保存任意输入 Schema。 */
function projectMcpTools(result: McpToolsResult): McpToolProjection[] {
  return result.items.map((tool) => ({ name: tool.name, policy: "ask" as const }));
}

/** 设置页健康投影按配置来源合成；测试结果不得反向写入用户或项目定义。 */
function mcpSettingsProjection(
  configured: SettingsMcpServer[],
  observed: McpServerProjection[],
): McpServerProjection[] {
  return configured.map((server) => {
    const match = observed.find((item) => item.id === server.mcpRevision);
    const enabledObservation = server.enabled ? match : undefined;
    return {
      id: server.mcpRevision,
      ...server,
      args: [...server.args],
      env: { ...server.env },
      headers: { ...server.headers },
      auth: { ...server.auth },
      status: enabledObservation?.status ?? (server.enabled ? "unknown" : "disabled"),
      tools: enabledObservation?.tools ?? [],
      ...(enabledObservation?.lastError === undefined
        ? {}
        : { lastError: enabledObservation.lastError }),
    };
  });
}

/**
 * 把严格设置文档与 runtime 观测合并为 UI 投影；配置事实和健康事实保持分离，
 * enabled 的 MCP 在没有探测证据时只能显示 unknown。
 */
function toSettingsSnapshot(
  document: SettingsDocument,
  runtimeSkills: SkillProjection[],
  runtimeMcpServers: McpServerProjection[],
  appearance: AppearanceSettings,
): SettingsSnapshot {
  return {
    revision: document.revision,
    defaultSelection: document.defaultSelection === null ? null : { ...document.defaultSelection },
    subagents: {
      enabled: document.subagents.enabled,
      providerId: document.subagents.providerId,
      modelId: document.subagents.modelId,
      reasoningLevel: document.subagents.reasoningLevel,
    },
    providers: document.providers.map((provider) => ({
      ...provider,
      networkTimeouts: { ...provider.networkTimeouts },
      agentDefaults: {
        context: { ...provider.agentDefaults.context },
      },
      models: provider.models.map((model) => ({
        ...model,
        capabilities: { ...model.capabilities },
        reasoningLevelMap: { ...model.reasoningLevelMap },
      })),
    })),
    mcpServers: mcpSettingsProjection(document.mcpServers, runtimeMcpServers),
    skills: skillSettingsProjection(runtimeSkills, document.disabledSkills, () => true),
    defaultAccessMode: document.defaultAccessMode,
    clarificationEnabled: document.clarificationEnabled,
    appearance: {
      theme: appearance.theme,
      palette: appearance.palette,
      reducedMotion: appearance.reducedMotion,
      reducedTransparency: appearance.reducedTransparency,
      highContrast: appearance.highContrast,
    },
  };
}

/** 未完成首次读取时提供稳定空投影，且只携带本地外观偏好，不伪造配置来源。 */
function emptySettingsSnapshot(appearance: AppearanceSettings): SettingsSnapshot {
  return {
    revision: 0,
    defaultSelection: null,
    subagents: { enabled: true, providerId: null, modelId: null, reasoningLevel: null },
    providers: [],
    skills: [],
    mcpServers: [],
    defaultAccessMode: "full_access",
    clarificationEnabled: true,
    appearance,
  };
}

/** 只交换相邻项，稳定 ID 不随视觉排序改变且越界动作保持幂等。 */
function moveById<T>(
  items: T[],
  id: string,
  direction: -1 | 1,
  identify: (item: T) => string,
): T[] {
  const index = items.findIndex((item) => identify(item) === id);
  const target = index + direction;
  if (index < 0 || target < 0 || target >= items.length) return items;
  const next = [...items];
  [next[index], next[target]] = [next[target]!, next[index]!];
  return next;
}

/** 用稳定条目身份在三份快照间检测是否是同一设置目标被并发修改。 */
function rebaseEntries<T>(
  baseline: readonly T[],
  intended: readonly T[],
  latest: readonly T[],
  identify: (item: T) => string,
): T[] | undefined {
  const baselineById = new Map(baseline.map((item) => [identify(item), item]));
  const intendedById = new Map(intended.map((item) => [identify(item), item]));
  const latestById = new Map(latest.map((item) => [identify(item), item]));
  const allIds = new Set([...baselineById.keys(), ...intendedById.keys(), ...latestById.keys()]);
  const localChanges = new Set(
    [...allIds].filter(
      (id) => JSON.stringify(baselineById.get(id)) !== JSON.stringify(intendedById.get(id)),
    ),
  );
  const externalChanges = new Set(
    [...allIds].filter(
      (id) => JSON.stringify(baselineById.get(id)) !== JSON.stringify(latestById.get(id)),
    ),
  );
  if ([...localChanges].some((id) => externalChanges.has(id))) return undefined;

  const merged = latest.flatMap((item) => {
    const id = identify(item);
    if (!localChanges.has(id)) return [structuredClone(item)];
    const replacement = intendedById.get(id);
    return replacement === undefined ? [] : [structuredClone(replacement)];
  });
  for (const item of intended) {
    const id = identify(item);
    if (localChanges.has(id) && !latestById.has(id)) merged.push(structuredClone(item));
  }
  return merged;
}

/**
 * 仅在本地与外部改动指向不同根字段或不同 Provider/MCP/Skill 时自动 rebase 一次。相同目标
 * 保留调用方表单草稿并报告冲突，不能猜测哪个值应覆盖另一个窗口的最新值。
 */
function rebaseSettingsDocument(
  baseline: SettingsDocument,
  intended: SettingsDocument,
  latest: SettingsDocument,
): SettingsDocument | undefined {
  const rebased = structuredClone(latest);
  const rebaseScalar = <
    K extends keyof Pick<
      SettingsDocument,
      "defaultAccessMode" | "clarificationEnabled" | "defaultSelection" | "subagents"
    >,
  >(
    field: K,
  ): boolean => {
    if (JSON.stringify(baseline[field]) === JSON.stringify(intended[field])) return true;
    if (JSON.stringify(baseline[field]) !== JSON.stringify(latest[field])) return false;
    rebased[field] = structuredClone(intended[field]);
    return true;
  };
  if (
    !rebaseScalar("defaultAccessMode") ||
    !rebaseScalar("clarificationEnabled") ||
    !rebaseScalar("defaultSelection") ||
    !rebaseScalar("subagents")
  ) {
    return undefined;
  }
  const providers = rebaseEntries(
    baseline.providers,
    intended.providers,
    latest.providers,
    (provider) => provider.providerId,
  );
  const mcpServers = rebaseEntries(
    baseline.mcpServers,
    intended.mcpServers,
    latest.mcpServers,
    (server) => server.mcpRevision,
  );
  const disabledSkills = rebaseEntries(
    baseline.disabledSkills,
    intended.disabledSkills,
    latest.disabledSkills,
    (skill) => skill,
  );
  if (providers === undefined || mcpServers === undefined || disabledSkills === undefined)
    return undefined;
  rebased.providers = providers;
  rebased.mcpServers = mcpServers;
  rebased.disabledSkills = disabledSkills;
  return rebased;
}

/**
 * Settings snapshot 按 workspace、App Server 实例和 runtime generation 分代；workspace 切换
 * 让无法取消的旧 native 结果只能写回旧 key，编辑层切换不重新读取 effective 配置。
 */
function settingsSnapshotQueryKey(
  workspaceId: string | undefined,
  serverInstanceId: string | null | undefined,
  runtimeGeneration: number | undefined,
) {
  return [
    "settings",
    "snapshot",
    workspaceId ?? "general",
    serverInstanceId ?? "unavailable",
    runtimeGeneration ?? 0,
  ] as const;
}

/** Runtime 只读投影按 sidecar 实例与 generation 分区，重启后绝不复用旧进程结果。 */
function runtimeProjectionKey(
  kind: "storage" | "skills" | "mcp",
  serverInstanceId: string | null | undefined,
  runtimeGeneration: number | undefined,
  scopeKey = "global",
) {
  return [
    "settings",
    kind,
    serverInstanceId ?? "unavailable",
    runtimeGeneration ?? 0,
    scopeKey,
  ] as const;
}

/** 只读取可公开分支判断的稳定错误码，原生错误正文和敏感上下文不得进入 UI。 */
function settingsErrorCode(error: unknown): string | undefined {
  return error !== null && typeof error === "object"
    ? ((error as { code?: unknown }).code as string | undefined)
    : undefined;
}

/**
 * 独占设置文档、Provider、Skill/MCP 健康投影和 CAS 保存；当前 workspace 决定 effective
 * snapshot 与 Turn admission；全局写 userDocument，项目 MCP/Skill 分字段 CAS patch，
 * 由 App Server 在可信 Workspace 的 .ja/config.toml 上合并。
 * controller 不保存或切换 workspace，也不拥有会话状态。
 */
export function useSettingsController({
  adapter,
  appearancePort,
  workspaceScope,
  runtimeState,
  boot,
  configurationChange,
  runtimePort,
  active = true,
  catalogSection = "all",
}: SettingsControllerOptions): SettingsController {
  const queryClient = useQueryClient();
  // Workspace owner 位于 composition；每次 render 直接派生 query key，避免 ref 更新不触发 render
  // 而让 controller 继续观察旧项目配置。设置页的编辑层不能改变会话消费的 effective 配置。
  const projectWorkspaceId =
    workspaceScope?.kind === "project" ? workspaceScope.workspaceId : undefined;
  const queryWorkspaceId = projectWorkspaceId;
  const {
    themeMode,
    palette,
    reducedMotion,
    reducedTransparency,
    highContrast,
    setThemeMode,
    setPalette,
    setHighContrast,
    setReduceMotion,
    setReducedTransparency,
  } = appearancePort;
  const runtimeReady =
    active && runtimeState !== undefined && ["ready", "busy"].includes(runtimeState.status);
  // Query 与失效 effect 只依赖投影中的分代标量；调用方重建等值对象时不能形成 fetch 循环。
  const runtimeServerInstanceId = runtimeState?.serverInstanceId;
  const runtimeGeneration = runtimeState?.generation;
  const configurationVersion = configurationChange?.version;
  const configurationScope = configurationChange?.scope;
  const configurationWorkspaceId = configurationChange?.workspaceId;
  const configurationScopeKey = queryWorkspaceId ?? "general";
  const handledConfigurationVersionsRef = useRef<Map<string, string>>(new Map());
  const credentialMutationTailRef = useRef<Promise<void>>(Promise.resolve());
  /** MCP 配置保存会推进观测序号；晚到的旧 probe 不得重新写入新定义的健康状态。 */
  const mcpObservationEpochRef = useRef<Map<string, number>>(new Map());
  // User 变更影响全部有效配置，Project 变更只允许推进匹配 workspace 的 key；其它项目的
  // timeline 事件不能让当前项目产生一次无意义的 configuration/read。
  const configurationMatchesScope =
    configurationScope === "user" ||
    (configurationScope === "project" && configurationWorkspaceId === queryWorkspaceId);
  const snapshotKey = useMemo(
    () => settingsSnapshotQueryKey(queryWorkspaceId, runtimeServerInstanceId, runtimeGeneration),
    [queryWorkspaceId, runtimeGeneration, runtimeServerInstanceId],
  );
  const skillWorkspaceId = workspaceScope?.kind === "project" ? queryWorkspaceId : undefined;
  const skillsKey = useMemo(
    () =>
      runtimeProjectionKey(
        "skills",
        runtimeServerInstanceId,
        runtimeGeneration,
        skillWorkspaceId ?? "global",
      ),
    [runtimeGeneration, runtimeServerInstanceId, skillWorkspaceId],
  );
  const globalSkillsKey = useMemo(
    () => runtimeProjectionKey("skills", runtimeServerInstanceId, runtimeGeneration, "global"),
    [runtimeGeneration, runtimeServerInstanceId],
  );
  const mcpKey = useMemo(
    () =>
      runtimeProjectionKey(
        "mcp",
        runtimeServerInstanceId,
        runtimeGeneration,
        queryWorkspaceId ?? "global",
      ),
    [queryWorkspaceId, runtimeGeneration, runtimeServerInstanceId],
  );
  const globalMcpKey = useMemo(
    () => runtimeProjectionKey("mcp", runtimeServerInstanceId, runtimeGeneration, "global"),
    [runtimeGeneration, runtimeServerInstanceId],
  );

  /** 权威设置文档只由 Query cache 持有；workspace key 阻止旧 native promise 覆盖新范围。 */
  const settingsQuery = useQuery({
    queryKey: snapshotKey,
    queryFn: () =>
      adapter.snapshot(
        queryWorkspaceId === undefined ? undefined : { workspaceId: queryWorkspaceId },
      ),
    enabled: runtimeReady,
    placeholderData: keepPreviousData,
  });

  /**
   * 每个 runtime/scope/version 事件只失效一次当前 Query。事件可能落在首个读取开始之后、
   * 返回之前；此时不能提前标记为已处理，否则旧读取会永久遮蔽外部修改。待 Query 结束后
   * effect 会以同一事件再运行一次并只补发一次权威重读；其它项目事件仍完全不触碰当前 cache。
   */
  useEffect(() => {
    if (!configurationMatchesScope || configurationVersion === undefined) return;
    const eventKey = `${runtimeServerInstanceId ?? "unavailable"}:${runtimeGeneration ?? 0}:${configurationScopeKey}`;
    if (handledConfigurationVersionsRef.current.get(eventKey) === configurationVersion) return;
    if (settingsQuery.isPending || settingsQuery.isPlaceholderData) return;
    handledConfigurationVersionsRef.current.set(eventKey, configurationVersion);
    void queryClient.invalidateQueries({ queryKey: snapshotKey, exact: true });
  }, [
    configurationMatchesScope,
    configurationScopeKey,
    configurationVersion,
    queryClient,
    runtimeGeneration,
    runtimeServerInstanceId,
    settingsQuery.isPending,
    settingsQuery.isPlaceholderData,
    snapshotKey,
  ]);

  /** Skill catalog 按 runtime generation 读取一次，显式 mutation 后才重新获取。 */
  const skillsQuery = useQuery({
    queryKey: skillsKey,
    queryFn: async () =>
      projectSkills(
        await runtimePort.listSkills(
          skillWorkspaceId === undefined ? undefined : { workspaceId: skillWorkspaceId },
        ),
      ),
    enabled: runtimeReady && (catalogSection === "all" || catalogSection === "skills"),
    // 仅可见 Skills 管理页低频重发现，安装新文件后无需切换工作区或修改配置。
    refetchInterval: runtimeReady && catalogSection === "skills" ? 15_000 : false,
  });
  /** 全局列表永远不携带 workspace，保证项目同名覆盖不会污染全局授权编辑面。 */
  const globalSkillsQuery = useQuery({
    queryKey: globalSkillsKey,
    queryFn: async () => projectSkills(await runtimePort.listSkills()),
    enabled: runtimeReady && (catalogSection === "all" || catalogSection === "skills"),
    refetchInterval: runtimeReady && catalogSection === "skills" ? 15_000 : false,
  });

  /** MCP catalog 与流式 timeline 分离；仅保存有界、脱敏的一次性查询投影。 */
  const mcpQuery = useQuery({
    queryKey: mcpKey,
    queryFn: async () =>
      projectMcpServers(
        await runtimePort.listMcpServers(
          queryWorkspaceId === undefined ? undefined : { workspaceId: queryWorkspaceId },
        ),
      ),
    enabled: runtimeReady && (catalogSection === "all" || catalogSection === "mcp"),
  });
  const globalMcpQuery = useQuery({
    queryKey: globalMcpKey,
    queryFn: async () => projectMcpServers(await runtimePort.listMcpServers()),
    enabled: runtimeReady && (catalogSection === "all" || catalogSection === "mcp"),
  });
  const loaded = settingsQuery.data;
  const runtimeSkills = useMemo(() => skillsQuery.data ?? [], [skillsQuery.data]);
  const globalRuntimeSkills = useMemo(() => globalSkillsQuery.data ?? [], [globalSkillsQuery.data]);
  const runtimeMcpServers = useMemo(() => mcpQuery.data ?? [], [mcpQuery.data]);
  const globalRuntimeMcpServers = useMemo(() => globalMcpQuery.data ?? [], [globalMcpQuery.data]);

  /** 从 Query cache 读取当前 workspace 的最新 CAS 快照，避免另建可变版本 owner。 */
  const currentLoaded = useCallback(
    (): LoadedSettings | undefined => queryClient.getQueryData<LoadedSettings>(snapshotKey),
    [queryClient, snapshotKey],
  );

  /** 所有本地提交都写回同一 Query entry；这里不维护平行 React state 或 adapter CAS cache。 */
  const updateLoadedQuery = useCallback(
    (update: (current: LoadedSettings) => LoadedSettings): void => {
      queryClient.setQueryData<LoadedSettings>(snapshotKey, (current) =>
        current === undefined ? current : update(current),
      );
    },
    [queryClient, snapshotKey],
  );

  /**
   * 读取当前 workspace 的有效设置；fetchQuery 强制本次 native read，query key 负责
   * 隔离无法取消的旧 invoke，Secret 仍只存在于 native credential owner。
   */
  const reload = useCallback(async (): Promise<void> => {
    await queryClient.fetchQuery({
      queryKey: snapshotKey,
      queryFn: () =>
        adapter.snapshot(
          queryWorkspaceId === undefined ? undefined : { workspaceId: queryWorkspaceId },
        ),
      staleTime: 0,
    });
  }, [adapter, queryClient, queryWorkspaceId, snapshotKey]);

  /**
   * 并行刷新设置页真实消费的 Skill/MCP 投影；任一查询失败时 fail closed 为 empty，
   * 不阻断模型配置页面，也不把配置布尔值当作健康证据。
   */
  const refreshRuntimeSettings = useCallback(async (): Promise<void> => {
    await Promise.all([
      queryClient.fetchQuery({
        queryKey: skillsKey,
        queryFn: async () =>
          projectSkills(
            await runtimePort.listSkills(
              skillWorkspaceId === undefined ? undefined : { workspaceId: skillWorkspaceId },
            ),
          ),
        staleTime: 0,
      }),
      queryClient.fetchQuery({
        queryKey: globalSkillsKey,
        queryFn: async () => projectSkills(await runtimePort.listSkills()),
        staleTime: 0,
      }),
      queryClient.fetchQuery({
        queryKey: mcpKey,
        queryFn: async () =>
          projectMcpServers(
            await runtimePort.listMcpServers(
              queryWorkspaceId === undefined ? undefined : { workspaceId: queryWorkspaceId },
            ),
          ),
        staleTime: 0,
      }),
      ...(queryWorkspaceId === undefined
        ? []
        : [
            queryClient.fetchQuery({
              queryKey: globalMcpKey,
              queryFn: async () => projectMcpServers(await runtimePort.listMcpServers()),
              staleTime: 0,
            }),
          ]),
    ]);
  }, [
    globalMcpKey,
    globalSkillsKey,
    mcpKey,
    queryClient,
    queryWorkspaceId,
    runtimePort,
    skillWorkspaceId,
    skillsKey,
  ]);

  /**
   * 用户配置写入始终以读取到的 userDocument 为基线。不同条目发生的并发写入自动重放一次；同一
   * 目标冲突则重读权威值并抛回错误，让现有表单保留草稿而不是用整份快照覆盖对方的修改。
   */
  const saveDocument = useCallback(
    async (userDocument: SettingsDocument): Promise<void> => {
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      let version: string;
      let documentToSave = userDocument;
      try {
        version = await adapter.save(documentToSave, current.cas.userVersion, current.userDocument);
      } catch (error) {
        if (settingsErrorCode(error) !== "revision_conflict") throw error;
        await reload();
        const latest = currentLoaded();
        const rebased =
          latest === undefined
            ? undefined
            : rebaseSettingsDocument(current.userDocument, userDocument, latest.userDocument);
        if (latest === undefined || rebased === undefined) throw error;
        documentToSave = rebased;
        version = await adapter.save(documentToSave, latest.cas.userVersion, latest.userDocument);
      }
      updateLoadedQuery((snapshot) => ({
        ...snapshot,
        userDocument: documentToSave,
        cas: { ...snapshot.cas, userVersion: version },
      }));
      await reload();
    },
    [adapter, currentLoaded, reload, updateLoadedQuery],
  );

  /**
   * 恢复仅在用户明确选择时发生，成功后丢弃页面旧投影并重新读取，避免把恢复前的草稿或 CAS
   * 版本重新写回。冲突仍保留当前界面，用户可以看到外部修改后的最新状态再决定下一步。
   */
  const restoreLastKnownGood = useCallback(async (): Promise<void> => {
    const current = currentLoaded();
    if (current === undefined) throw new Error("settings unavailable");
    try {
      await adapter.restoreLastKnownGood(current.cas.userVersion);
    } catch (error) {
      if (settingsErrorCode(error) === "revision_conflict") await reload();
      throw error;
    }
    await reload();
    await refreshRuntimeSettings();
  }, [adapter, currentLoaded, refreshRuntimeSettings, reload]);

  /**
   * 整组模型与连接共用 user CAS；能力编辑同步收敛根思考档位，删除默认模型仍须走显式替代流程。
   * 项目 effective 值不得进入全局表单，供应商改名或新增模型也不得改变当前模型选择。
   */
  const saveProvider = useCallback(
    async (provider: ProviderSave): Promise<void> => {
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      const existing = current.userDocument.providers.find(
        (item) => item.providerId === provider.providerId,
      );
      const merged = { ...provider, credentialConfigured: existing?.credentialConfigured ?? false };
      const firstModel = merged.models[0];
      if (firstModel === undefined) throw new Error("provider requires model");
      const selected = current.userDocument.defaultSelection;
      const selectedModel = merged.models.find((model) => model.modelId === selected?.modelId);
      if (selected?.providerId === provider.providerId && selectedModel === undefined)
        throw new Error("replacement model is required");
      const subagentModel = merged.models.find(
        (model) => model.modelId === current.userDocument.subagents.modelId,
      );
      if (
        current.userDocument.subagents.providerId === provider.providerId &&
        current.userDocument.subagents.modelId !== null &&
        subagentModel === undefined
      )
        throw new Error("subagent model replacement is required");
      const subagents =
        current.userDocument.subagents.providerId === provider.providerId &&
        current.userDocument.subagents.modelId === subagentModel?.modelId &&
        current.userDocument.subagents.reasoningLevel !== null &&
        subagentModel.reasoningLevelMap[current.userDocument.subagents.reasoningLevel] === undefined
          ? { ...current.userDocument.subagents, reasoningLevel: null }
          : current.userDocument.subagents;
      const defaultSelection =
        selected === null
          ? {
              providerId: provider.providerId,
              modelId: firstModel.modelId,
              reasoningLevel: firstModel.defaultReasoningLevel,
            }
          : selected.providerId === provider.providerId &&
              selectedModel !== undefined &&
              selected.reasoningLevel !== null &&
              selectedModel.reasoningLevelMap[selected.reasoningLevel] === undefined
            ? { ...selected, reasoningLevel: selectedModel.defaultReasoningLevel }
            : selected;
      await saveDocument({
        ...current.userDocument,
        revision: current.userDocument.revision + 1,
        defaultSelection,
        subagents,
        providers:
          existing === undefined
            ? [...current.userDocument.providers, merged]
            : current.userDocument.providers.map((item) =>
                item.providerId === provider.providerId ? merged : item,
              ),
      });
    },
    [currentLoaded, saveDocument],
  );

  /** 删除默认 Provider 时只接受用户显式选择的替代项，避免按数组顺序静默改变默认模型。 */
  const deleteProvider = useCallback(
    async (providerId: string, replacement: DefaultModelSelection | null): Promise<void> => {
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      const providers = current.userDocument.providers.filter(
        (provider) => provider.providerId !== providerId,
      );
      if (providers.length === current.userDocument.providers.length)
        throw new Error("provider unavailable");
      const deletesDefault = current.userDocument.defaultSelection?.providerId === providerId;
      const deletesSubagent = current.userDocument.subagents.providerId === providerId;
      const replacementModel =
        replacement === null
          ? undefined
          : providers
              .find((provider) => provider.providerId === replacement.providerId)
              ?.models.find((model) => model.modelId === replacement.modelId);
      const replacementReasoningLevel = replacement?.reasoningLevel ?? null;
      if (deletesDefault && providers.length > 0 && replacementModel === undefined)
        throw new Error("replacement model is required");
      if (deletesSubagent) throw new Error("subagent model replacement is required");
      if (
        replacementModel !== undefined &&
        replacementReasoningLevel !== null &&
        replacementModel.reasoningLevelMap[replacementReasoningLevel] === undefined
      )
        throw new Error("replacement reasoning level is unavailable");
      const defaultSelection = deletesDefault ? replacement : current.userDocument.defaultSelection;
      await saveDocument({
        ...current.userDocument,
        revision: current.userDocument.revision + 1,
        providers,
        defaultSelection,
      });
    },
    [currentLoaded, saveDocument],
  );

  /** Provider 排序只改变展示与分组顺序，不改变稳定 ID 或默认选择。 */
  const moveProvider = useCallback(
    async (providerId: string, direction: -1 | 1): Promise<void> => {
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      const providers = moveById(
        current.userDocument.providers,
        providerId,
        direction,
        (item) => item.providerId,
      );
      if (providers === current.userDocument.providers) return;
      await saveDocument({
        ...current.userDocument,
        revision: current.userDocument.revision + 1,
        providers,
      });
    },
    [currentLoaded, saveDocument],
  );

  /** 单模型保存只更新用户级模型能力，并同步收敛根默认思考档位。 */
  const saveModel = useCallback(
    async (providerId: string, model: ProviderModelSave): Promise<void> => {
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      const provider = current.userDocument.providers.find(
        (candidate) => candidate.providerId === providerId,
      );
      if (provider === undefined) throw new Error("provider unavailable");
      const userModel = provider.models.find((candidate) => candidate.modelId === model.modelId);
      const savedModel = model;
      const exists = userModel !== undefined;
      const models = exists
        ? provider.models.map((candidate) =>
            candidate.modelId === model.modelId ? savedModel : candidate,
          )
        : [...provider.models, savedModel];
      const selected = current.userDocument.defaultSelection;
      const defaultSelection =
        selected === null
          ? {
              providerId,
              modelId: savedModel.modelId,
              reasoningLevel: savedModel.defaultReasoningLevel,
            }
          : selected.providerId === providerId &&
              selected.modelId === savedModel.modelId &&
              selected.reasoningLevel !== null &&
              savedModel.reasoningLevelMap[selected.reasoningLevel] === undefined
            ? { ...selected, reasoningLevel: savedModel.defaultReasoningLevel }
            : selected;
      const subagents =
        current.userDocument.subagents.providerId === providerId &&
        current.userDocument.subagents.modelId === savedModel.modelId &&
        current.userDocument.subagents.reasoningLevel !== null &&
        savedModel.reasoningLevelMap[current.userDocument.subagents.reasoningLevel] === undefined
          ? { ...current.userDocument.subagents, reasoningLevel: null }
          : current.userDocument.subagents;
      await saveDocument({
        ...current.userDocument,
        revision: current.userDocument.revision + 1,
        defaultSelection,
        subagents,
        providers: current.userDocument.providers.map((candidate) =>
          candidate.providerId === providerId ? { ...candidate, models } : candidate,
        ),
      });
    },
    [currentLoaded, saveDocument],
  );

  /** 删除默认模型时只接受用户显式选择的替代项，且替代项必须仍存在于删除后的目录。 */
  const deleteModel = useCallback(
    async (
      providerId: string,
      modelId: string,
      replacement: DefaultModelSelection | null,
    ): Promise<void> => {
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      const provider = current.userDocument.providers.find(
        (candidate) => candidate.providerId === providerId,
      );
      if (provider === undefined || provider.models.length <= 1)
        throw new Error("provider requires model");
      const models = provider.models.filter((candidate) => candidate.modelId !== modelId);
      if (models.length === provider.models.length) throw new Error("model unavailable");
      const deletesDefault =
        current.userDocument.defaultSelection?.providerId === providerId &&
        current.userDocument.defaultSelection.modelId === modelId;
      const deletesSubagent =
        current.userDocument.subagents.providerId === providerId &&
        current.userDocument.subagents.modelId === modelId;
      const remainingProviders = current.userDocument.providers.map((candidate) =>
        candidate.providerId === providerId ? { ...candidate, models } : candidate,
      );
      const replacementModel =
        replacement === null
          ? undefined
          : remainingProviders
              .find((candidate) => candidate.providerId === replacement.providerId)
              ?.models.find((candidate) => candidate.modelId === replacement.modelId);
      const replacementReasoningLevel = replacement?.reasoningLevel ?? null;
      if (deletesDefault && replacementModel === undefined)
        throw new Error("replacement model is required");
      if (deletesSubagent) throw new Error("subagent model replacement is required");
      if (
        replacementModel !== undefined &&
        replacementReasoningLevel !== null &&
        replacementModel.reasoningLevelMap[replacementReasoningLevel] === undefined
      )
        throw new Error("replacement reasoning level is unavailable");
      const defaultSelection = deletesDefault ? replacement : current.userDocument.defaultSelection;
      await saveDocument({
        ...current.userDocument,
        revision: current.userDocument.revision + 1,
        defaultSelection,
        providers: remainingProviders,
      });
    },
    [currentLoaded, saveDocument],
  );

  /** 模型验证只允许全局已保存身份，测试请求正文和回答始终由 App Server 隔离。 */
  const testModel = useCallback(
    async (providerId: string, modelId: string) => {
      const current = currentLoaded();
      const model = current?.userDocument.providers
        .find((provider) => provider.providerId === providerId)
        ?.models.find((candidate) => candidate.modelId === modelId);
      if (model === undefined) throw new Error("model unavailable");
      return runtimePort.testModel(providerId, modelId);
    },
    [currentLoaded, runtimePort],
  );

  /**
   * 目录读取只接受当前用户文档中已保存的 Provider 身份，防止界面把未保存的 Base URL、协议或
   * API Key 传入运行时；返回结果由编辑 sheet 明确决定是否合并和保存。
   */
  const discoverModels = useCallback(
    async (providerId: string) => {
      const current = currentLoaded();
      const provider = current?.userDocument.providers.find(
        (candidate) => candidate.providerId === providerId,
      );
      if (provider === undefined) throw new Error("provider unavailable");
      return runtimePort.discoverModels(providerId);
    },
    [currentLoaded, runtimePort],
  );

  /** 模型排序只交换同一 Provider 内相邻项，不能跨 Provider 移动身份。 */
  const moveModel = useCallback(
    async (providerId: string, modelId: string, direction: -1 | 1): Promise<void> => {
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      const provider = current.userDocument.providers.find(
        (candidate) => candidate.providerId === providerId,
      );
      if (provider === undefined) throw new Error("provider unavailable");
      const models = moveById(provider.models, modelId, direction, (item) => item.modelId);
      if (models === provider.models) return;
      await saveDocument({
        ...current.userDocument,
        revision: current.userDocument.revision + 1,
        providers: current.userDocument.providers.map((candidate) =>
          candidate.providerId === providerId ? { ...candidate, models } : candidate,
        ),
      });
    },
    [currentLoaded, saveDocument],
  );

  /** 默认选择必须命中真实模型，思考档位由模型能力闭集裁决。 */
  const saveDefaultSelection = useCallback(
    async (selection: DefaultModelSelection): Promise<void> => {
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      const model = current.userDocument.providers
        .find((provider) => provider.providerId === selection.providerId)
        ?.models.find((candidate) => candidate.modelId === selection.modelId);
      if (
        model === undefined ||
        (selection.reasoningLevel !== null &&
          model.reasoningLevelMap[selection.reasoningLevel] === undefined)
      ) {
        throw new Error("default selection unavailable");
      }
      await saveDocument({
        ...current.userDocument,
        revision: current.userDocument.revision + 1,
        defaultSelection: selection,
      });
    },
    [currentLoaded, saveDocument],
  );

  /** 子智能体引用必须同时命中已保存目录，防止删除模型后静默改派到其它上游。 */
  const saveSubagentSettings = useCallback(
    async (settings: SubagentSettings): Promise<void> => {
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      if ((settings.providerId === null) !== (settings.modelId === null))
        throw new Error("subagent selection incomplete");
      if (settings.providerId === null && settings.reasoningLevel !== null)
        throw new Error("subagent reasoning level requires model");
      if (settings.providerId !== null && settings.modelId !== null) {
        const model = current.userDocument.providers
          .find((provider) => provider.providerId === settings.providerId)
          ?.models.find((candidate) => candidate.modelId === settings.modelId);
        if (model === undefined) throw new Error("subagent model unavailable");
        if (
          settings.reasoningLevel !== null &&
          model.reasoningLevelMap[settings.reasoningLevel] === undefined
        )
          throw new Error("subagent reasoning level unavailable");
      }
      await saveDocument({
        ...current.userDocument,
        revision: current.userDocument.revision + 1,
        subagents: { ...settings },
      });
    },
    [currentLoaded, saveDocument],
  );

  /** 持久化根默认访问模式；Thread 的请求级实际值仍由 App Server 在安全点解析。 */
  const saveAccessMode = useCallback(
    async (mode: AccessMode): Promise<void> => {
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      await saveDocument({
        ...current.userDocument,
        revision: current.userDocument.revision + 1,
        defaultAccessMode: mode,
      });
    },
    [currentLoaded, saveDocument],
  );

  /**
   * 仅更新用户层澄清开关并复用完整文档 CAS；项目 effective 快照不能成为写入基线。
   */
  const saveClarificationEnabled = useCallback(
    async (enabled: boolean): Promise<void> => {
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      await saveDocument({
        ...current.userDocument,
        revision: current.userDocument.revision + 1,
        clarificationEnabled: enabled,
      });
    },
    [currentLoaded, saveDocument],
  );

  /**
   * 外观全部写入唯一 UI preference owner；JA-RPC 配置不拥有 theme/palette/accessibility，
   * 因而这里不能伪造 Config replace，否则权威重读会把短暂主题切换覆盖回 system。
   */
  const saveAppearance = useCallback(
    async (appearance: AppearanceSettings, changed: keyof AppearanceSettings): Promise<void> => {
      if (changed === "theme") setThemeMode(appearance.theme);
      if (changed === "palette") setPalette(appearance.palette);
      if (changed === "highContrast") setHighContrast(appearance.highContrast);
      if (changed === "reducedMotion") setReduceMotion(appearance.reducedMotion);
      if (changed === "reducedTransparency") setReducedTransparency(appearance.reducedTransparency);
    },
    [setHighContrast, setPalette, setReduceMotion, setReducedTransparency, setThemeMode],
  );

  /**
   * 保存 MCP 定义时保留高级非敏感 map；全局用用户 CAS，项目只 patch MCP 字段；成功后失效当前
   * generation 的观测 cache，因为 endpoint、认证引用和 enabled 都可能已改变，而旧观测
   * 不能代表新定义。序号同时为在途 probe 提供晚到结果屏障，不改变后端 Turn 的 lease 语义。
   */
  const saveMcp = useCallback(
    async (server: McpServerSave, scope: "user" | "project"): Promise<void> => {
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      const project = scope === "project";
      const source = project ? current.projectMcpServers : current.userDocument.mcpServers;
      if (source === undefined || (project && queryWorkspaceId === undefined))
        throw new Error("project settings unavailable");
      const existing = source.find((item) => item.mcpRevision === server.mcpRevision);
      const merged: SettingsMcpServer = {
        ...(existing ?? { protocolVersion: "2025-06-18" as const }),
        ...server,
      };
      const mcpServers =
        existing === undefined
          ? [...source, merged]
          : source.map((item) => (item.mcpRevision === server.mcpRevision ? merged : item));
      if (project) {
        try {
          await adapter.saveProjectMcpServers(
            mcpServers,
            queryWorkspaceId!,
            current.cas.projectVersion,
          );
        } catch (error) {
          if (settingsErrorCode(error) === "revision_conflict") await reload();
          throw error;
        }
        await reload();
      } else {
        await saveDocument({
          ...current.userDocument,
          revision: current.userDocument.revision + 1,
          mcpServers,
        });
      }
      mcpObservationEpochRef.current.set(
        `${scope}:${server.mcpRevision}`,
        (mcpObservationEpochRef.current.get(`${scope}:${server.mcpRevision}`) ?? 0) + 1,
      );
      await queryClient.invalidateQueries({ queryKey: mcpKey, exact: true });
      if (!project && queryWorkspaceId !== undefined)
        await queryClient.invalidateQueries({ queryKey: globalMcpKey, exact: true });
    },
    [
      adapter,
      currentLoaded,
      globalMcpKey,
      mcpKey,
      queryClient,
      queryWorkspaceId,
      reload,
      saveDocument,
    ],
  );

  /**
   * 运行一次真实 MCP probe 并只更新对应 server 的健康投影；只有健康 probe 才读取工具目录。
   * 每次探测推进观测序号，使并发旧结果和配置保存后的晚到结果都不能覆盖最新状态；
   * probe/目录 RPC 失败也先落成可见错误，避免卡片保留“未检查”或旧工具。
   */
  const testMcp = useCallback(
    async (
      mcpRevision: string,
      scope: "user" | "project",
    ): Promise<"unknown" | "connected" | "disabled" | "testing" | "error"> => {
      const current = currentLoaded();
      const server = (
        scope === "project" ? current?.projectMcpServers : current?.userDocument.mcpServers
      )?.find((item) => item.mcpRevision === mcpRevision);
      if (server === undefined || !server.enabled) return "disabled";
      const workspaceId = scope === "project" ? queryWorkspaceId : undefined;
      if (scope === "project" && workspaceId === undefined)
        throw new Error("project settings unavailable");
      const observationKey = `${scope}:${mcpRevision}`;
      const targetKey = scope === "project" ? mcpKey : globalMcpKey;
      const observationEpoch = (mcpObservationEpochRef.current.get(observationKey) ?? 0) + 1;
      mcpObservationEpochRef.current.set(observationKey, observationEpoch);

      /** 同一处提交 pending/终态，并再次校验序号，避免取消旧 Query 后被并发保存越过。 */
      const commitObservation = async (
        status: McpServerProjection["status"],
        tools: McpToolProjection[],
        lastError?: string,
      ): Promise<boolean> => {
        if ((mcpObservationEpochRef.current.get(observationKey) ?? 0) !== observationEpoch)
          return false;
        await queryClient.cancelQueries({ queryKey: targetKey, exact: true });
        if ((mcpObservationEpochRef.current.get(observationKey) ?? 0) !== observationEpoch)
          return false;
        queryClient.setQueryData<McpServerProjection[]>(targetKey, (items = []) => {
          return items.some((item) => item.id === mcpRevision)
            ? items.map((item) => {
                if (item.id !== mcpRevision) return item;
                const observed = { ...item, status, tools };
                if (lastError === undefined) delete observed.lastError;
                else observed.lastError = lastError;
                return observed;
              })
            : [
                ...items,
                {
                  ...server,
                  id: server.mcpRevision,
                  status,
                  tools,
                  ...(lastError === undefined ? {} : { lastError }),
                },
              ];
        });
        return true;
      };

      /** 晚到操作向 UI 返回当前投影，不能把旧 probe 结果重新说成当前状态。 */
      const latestStatus = (): McpServerProjection["status"] =>
        queryClient
          .getQueryData<McpServerProjection[]>(targetKey)
          ?.find((item) => item.id === mcpRevision)?.status ?? "unknown";

      if (!(await commitObservation("testing", []))) return latestStatus();

      let result: Awaited<ReturnType<SettingsRuntimePort["testMcp"]>>;
      try {
        result = await runtimePort.testMcp(
          mcpRevision,
          workspaceId === undefined ? undefined : { workspaceId },
        );
      } catch {
        return (await commitObservation(
          "error",
          [],
          "MCP 检查请求未完成；请检查 Ja 本地运行时后重试。",
        ))
          ? "error"
          : latestStatus();
      }

      if ((mcpObservationEpochRef.current.get(observationKey) ?? 0) !== observationEpoch)
        return latestStatus();
      if (!mcpProbeHealthy(result.status)) {
        return (await commitObservation("error", [], "MCP 服务不可用；请检查服务状态和连接配置。"))
          ? "error"
          : latestStatus();
      }

      let tools: McpToolProjection[];
      try {
        tools = projectMcpTools(
          await runtimePort.listMcpTools(
            mcpRevision,
            workspaceId === undefined ? undefined : { workspaceId },
          ),
        );
      } catch {
        return (await commitObservation(
          "error",
          [],
          "MCP 工具目录读取失败；请重试或检查 MCP 服务兼容性。",
        ))
          ? "error"
          : latestStatus();
      }

      if ((mcpObservationEpochRef.current.get(observationKey) ?? 0) !== observationEpoch)
        return latestStatus();
      return (await commitObservation("connected", tools)) ? "connected" : latestStatus();
    },
    [currentLoaded, globalMcpKey, mcpKey, queryClient, queryWorkspaceId, runtimePort],
  );

  /** 删除 MCP 只移除所选来源的目录定义；Credential 引用保持独立，避免隐式 Secret 级联。 */
  const deleteMcp = useCallback(
    async (mcpRevision: string, scope: "user" | "project"): Promise<void> => {
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      const source =
        scope === "project" ? current.projectMcpServers : current.userDocument.mcpServers;
      if (source === undefined) throw new Error("project settings unavailable");
      const mcpServers = source.filter((server) => server.mcpRevision !== mcpRevision);
      if (mcpServers.length === source.length) throw new Error("MCP server unavailable");
      if (scope === "project") {
        if (queryWorkspaceId === undefined) throw new Error("project settings unavailable");
        try {
          await adapter.saveProjectMcpServers(
            mcpServers,
            queryWorkspaceId,
            current.cas.projectVersion,
          );
        } catch (error) {
          if (settingsErrorCode(error) === "revision_conflict") await reload();
          throw error;
        }
        await reload();
      } else {
        await saveDocument({
          ...current.userDocument,
          revision: current.userDocument.revision + 1,
          mcpServers,
        });
      }
      await refreshRuntimeSettings();
    },
    [adapter, currentLoaded, queryWorkspaceId, refreshRuntimeSettings, reload, saveDocument],
  );

  /**
   * Skill 开关只提交来源限定停用引用；全局与项目各自写入所属来源名单。
   * 保存失败或 CAS 冲突会保留当前画面并权威重读，禁止乐观状态漂移到其它 workspace。
   */
  const toggleSkill = useCallback(
    async (skillReference: string, enabled: boolean, scope: "user" | "project"): Promise<void> => {
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      if (scope === "user") {
        if (!skillReference.startsWith("user:") && !skillReference.startsWith("ja:")) {
          throw new Error("global skill unavailable");
        }
        const disabledSkills = enabled
          ? current.userDocument.disabledSkills.filter((skill) => skill !== skillReference)
          : Array.from(new Set([...current.userDocument.disabledSkills, skillReference]));
        await saveDocument({
          ...current.userDocument,
          revision: current.userDocument.revision + 1,
          disabledSkills,
        });
      } else {
        const workspaceId = queryWorkspaceId;
        const project = current.projectSkillDocument;
        if (workspaceId === undefined || project === undefined) {
          throw new Error("project settings unavailable");
        }
        if (!skillReference.startsWith("project:")) throw new Error("project skill unavailable");
        const next = {
          ...project,
          revision: project.revision + 1,
          disabledSkills: enabled
            ? project.disabledSkills.filter((skill) => skill !== skillReference)
            : Array.from(new Set([...project.disabledSkills, skillReference])),
        };
        try {
          await adapter.saveProjectSkills(next, workspaceId, current.cas.projectVersion);
        } catch (error) {
          if (settingsErrorCode(error) === "revision_conflict") await reload();
          throw error;
        }
        await reload();
      }
      await refreshRuntimeSettings();
    },
    [adapter, currentLoaded, queryWorkspaceId, refreshRuntimeSettings, reload, saveDocument],
  );

  /**
   * Credential CAS 冲突只允许权威重读后重试一次；成功也必须重读脱敏投影，
   * 避免将 command 回执或本地推测冒充 credentialConfigured 事实。
   */
  const mutateCredential = useCallback(
    async (mutation: (expectedVersion: string) => Promise<string>): Promise<void> => {
      const operation = credentialMutationTailRef.current.then(async () => {
        const current = currentLoaded();
        if (current === undefined) throw new Error("settings unavailable");
        try {
          await mutation(current.cas.credentialVersion);
        } catch (error) {
          if (settingsErrorCode(error) !== "revision_conflict") throw error;
          await reload();
          const refreshed = currentLoaded();
          if (refreshed === undefined) throw error;
          await mutation(refreshed.cas.credentialVersion);
        }
        await reload();
      });
      credentialMutationTailRef.current = operation.catch(() => undefined);
      await operation;
    },
    [currentLoaded, reload],
  );

  /** Secret 只穿过 native credential port，CAS 恢复期间也不写入 React state 或设置文档。 */
  const setCredential = useCallback(
    async (credentialId: string, secret: string): Promise<void> => {
      await mutateCredential((expectedVersion) =>
        adapter.setCredential(credentialId, secret, expectedVersion),
      );
    },
    [adapter, mutateCredential],
  );

  /**
   * 首次创建跨配置文档和凭据库分两次提交：先让 Provider 成为可见、可恢复事实，再写 Secret。
   * 第二步失败时保留 Provider，调用方可用同一稳定 ID 重试，避免产生不可见的孤立凭据。
   */
  const createProvider = useCallback(
    async (provider: ProviderSave, secret: string): Promise<void> => {
      await saveProvider(provider);
      try {
        await setCredential(provider.credentialId, secret);
      } catch (cause) {
        throw Object.assign(new Error("provider credential save failed"), {
          code: "provider_saved_credential_failed",
          cause,
        });
      }
    },
    [saveProvider, setCredential],
  );

  /** 删除 native Secret 复用同一 CAS 恢复语义，并保留 Provider/MCP 中的不透明引用。 */
  const deleteCredential = useCallback(
    async (credentialId: string): Promise<void> => {
      await mutateCredential((expectedVersion) =>
        adapter.deleteCredential(credentialId, expectedVersion),
      );
    },
    [adapter, mutateCredential],
  );

  /** 回显只允许经过 adapter 的 Provider 专用方法，避免控制器从设置快照或 MCP 身份推导 Secret。 */
  const revealProviderCredential = useCallback(
    async (providerId: string): Promise<string | null> =>
      adapter.revealProviderCredential(providerId),
    [adapter],
  );

  /** 合并后的只读 snapshot 不缓存 Secret，也不把 runtime 健康状态写回设置文档。 */
  const snapshot = useMemo<SettingsSnapshot>(
    () =>
      loaded === undefined
        ? emptySettingsSnapshot({
            theme: themeMode,
            palette,
            reducedMotion,
            reducedTransparency,
            highContrast,
          })
        : toSettingsSnapshot(loaded.document, runtimeSkills, runtimeMcpServers, {
            theme: themeMode,
            palette,
            reducedMotion,
            reducedTransparency,
            highContrast,
          }),
    [
      highContrast,
      loaded,
      palette,
      reducedMotion,
      reducedTransparency,
      runtimeMcpServers,
      runtimeSkills,
      themeMode,
    ],
  );

  /** 全局设置投影复用同一脱敏映射，但配置值严格取自 userDocument。 */
  const globalSnapshot = useMemo<SettingsSnapshot>(
    () =>
      loaded === undefined
        ? emptySettingsSnapshot({
            theme: themeMode,
            palette,
            reducedMotion,
            reducedTransparency,
            highContrast,
          })
        : toSettingsSnapshot(loaded.userDocument, globalRuntimeSkills, globalRuntimeMcpServers, {
            theme: themeMode,
            palette,
            reducedMotion,
            reducedTransparency,
            highContrast,
          }),
    [
      highContrast,
      loaded,
      palette,
      reducedMotion,
      reducedTransparency,
      globalRuntimeMcpServers,
      globalRuntimeSkills,
      themeMode,
    ],
  );

  /**
   * Skills 的全局/项目投影各自以所属停用名单为真相；项目组只展示项目来源。
   */
  const skillSettings = useMemo<SettingsController["skillSettings"]>(() => {
    if (loaded === undefined) return { global: [], projectAvailable: false };
    const globalDisabled = loaded.userDocument.disabledSkills;
    const global = skillSettingsProjection(
      globalRuntimeSkills,
      globalDisabled,
      (skill) => skill.source === "user" || skill.source === "ja",
    );
    const projectDocument = loaded.projectSkillDocument;
    const projectAvailable =
      queryWorkspaceId !== undefined &&
      projectDocument !== undefined &&
      !settingsQuery.isPlaceholderData;
    if (!projectAvailable || projectDocument === undefined)
      return { global, projectAvailable: false };
    const project = skillSettingsProjection(
      runtimeSkills,
      projectDocument.disabledSkills,
      (skill) => skill.source === "project",
    );
    return { global, project, projectAvailable: true };
  }, [
    globalRuntimeSkills,
    loaded,
    queryWorkspaceId,
    runtimeSkills,
    settingsQuery.isPlaceholderData,
  ]);

  /** 全局与项目 MCP 分别以各自配置文档为编辑事实，健康度只叠加同作用域测试结果。 */
  const mcpSettings = useMemo<SettingsController["mcpSettings"]>(() => {
    if (loaded === undefined) return { global: [], projectAvailable: false };
    const global = mcpSettingsProjection(loaded.userDocument.mcpServers, globalRuntimeMcpServers);
    const projectAvailable =
      queryWorkspaceId !== undefined &&
      loaded.projectMcpServers !== undefined &&
      !settingsQuery.isPlaceholderData;
    if (!projectAvailable || loaded.projectMcpServers === undefined) {
      return { global, projectAvailable: false };
    }
    return {
      global,
      project: mcpSettingsProjection(loaded.projectMcpServers, runtimeMcpServers),
      projectAvailable: true,
      projectWorkspaceId: queryWorkspaceId,
    };
  }, [
    globalRuntimeMcpServers,
    loaded,
    queryWorkspaceId,
    runtimeMcpServers,
    settingsQuery.isPlaceholderData,
  ]);

  /** 以稳定 action 集合作为 UI 边界，避免视图接触 adapter 或 CAS 文档。 */
  const ports = useMemo<SettingsPorts>(
    () => ({
      onCreateProvider: createProvider,
      onSaveProvider: saveProvider,
      onDeleteProvider: deleteProvider,
      onMoveProvider: moveProvider,
      onSaveModel: saveModel,
      onTestModel: testModel,
      onDiscoverModels: discoverModels,
      onDeleteModel: deleteModel,
      onMoveModel: moveModel,
      onDefaultSelectionChange: saveDefaultSelection,
      onSubagentSettingsChange: saveSubagentSettings,
      onReplaceCredential: setCredential,
      onClearCredential: deleteCredential,
      onRevealProviderCredential: revealProviderCredential,
      onSaveMcp: saveMcp,
      onDeleteMcp: deleteMcp,
      onTestMcp: testMcp,
      onToggleSkill: toggleSkill,
      onAccessModeChange: saveAccessMode,
      onClarificationEnabledChange: saveClarificationEnabled,
      onAppearanceChange: saveAppearance,
    }),
    [
      createProvider,
      deleteModel,
      deleteMcp,
      deleteProvider,
      deleteCredential,
      discoverModels,
      revealProviderCredential,
      moveModel,
      moveProvider,
      saveAppearance,
      saveAccessMode,
      saveClarificationEnabled,
      saveDefaultSelection,
      saveSubagentSettings,
      saveMcp,
      saveModel,
      saveProvider,
      setCredential,
      testMcp,
      testModel,
      toggleSkill,
    ],
  );

  // 明确失败或恢复状态必须先展示可操作错误，不能被“正在读取设置”永久遮住。
  const bootBlocksSettings =
    boot.status === "failed" || boot.status === "degraded" || boot.status === "recovery_required";
  const loading =
    !bootBlocksSettings && (!runtimeReady || (settingsQuery.isPending && loaded === undefined));
  // 后台重读保留当前权威投影；刷新不应把整张设置页锁住，mutation 自身仍各自管理 pending。
  const synchronizing = runtimeReady && settingsQuery.isFetching;
  const scopeReady = runtimeReady && settingsQuery.isSuccess && !settingsQuery.isPlaceholderData;
  const error =
    boot.status === "failed" || boot.status === "degraded"
      ? `本地运行时启动失败：${boot.message}`
      : boot.status === "recovery_required"
        ? "本地运行时需要先完成恢复。"
        : settingsQuery.isError
          ? "设置暂时无法读取，请稍后重试。"
          : undefined;
  return {
    loaded,
    snapshot,
    globalSnapshot,
    skillSettings,
    mcpSettings,
    loading,
    synchronizing,
    scopeReady,
    scopeWorkspaceId: queryWorkspaceId,
    error,
    ports,
    restoreLastKnownGood,
    reload,
  };
}
