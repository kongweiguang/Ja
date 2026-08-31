// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
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
  ProviderProjection,
  ProviderSave,
  SettingsDocument,
  SettingsConfigurationChange,
  SettingsMcpServer,
  SettingsSnapshot,
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
}

/** Settings controller 只暴露 v4 Provider 聚合，不保留 Profile 激活兼容入口。 */
export interface SettingsController {
  loaded: LoadedSettings | undefined;
  snapshot: SettingsSnapshot;
  loading: boolean;
  synchronizing: boolean;
  scopeReady: boolean;
  scopeWorkspaceId: string | undefined;
  scope: "global" | "project";
  projectAvailable: boolean;
  error: string | undefined;
  ports: SettingsPorts;
  reload(): Promise<void>;
  setScope(scope: "global" | "project"): void;
}

/** 将 Ja Kernel 的 Skill 健康状态收敛为设置 UI 的有限枚举。 */
function skillStatus(status: "healthy" | "invalid" | "unavailable"): SkillProjection["status"] {
  return status === "healthy" ? "ready" : "error";
}

/** 仅保存 Skill 摘要，禁止把正文或执行实现复制到 React 状态。 */
function projectSkills(result: SkillListResult): SkillProjection[] {
  return result.items.map((skill) => ({
    id: skill.skillId,
    name: skill.name,
    source:
      skill.scope === "builtin" ? "builtin" : skill.scope === "workspace" ? "workspace" : "user",
    description: skill.description ?? "",
    enabled: skill.enabled,
    status: skillStatus(skill.status),
    ...(skill.status === "healthy" ? { lastGood: "刚刚" } : {}),
  }));
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
        server.status === "healthy"
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

/** MCP Tool 只投影名称和交互策略，避免在 UI store 长期保存任意输入 Schema。 */
function projectMcpTools(result: McpToolsResult): McpToolProjection[] {
  return result.items.map((tool) => ({ name: tool.name, policy: "ask" as const }));
}

/**
 * 把严格设置文档与 runtime 观测合并为 UI 投影；配置事实和健康事实保持分离，
 * enabled 的 MCP 在没有探测证据时只能显示 unknown。
 */
function toSettingsSnapshot(
  document: SettingsDocument,
  userDocument: SettingsDocument,
  projectOverrides: LoadedSettings["projectOverrides"],
  runtimeSkills: SkillProjection[],
  runtimeMcpServers: McpServerProjection[],
  appearance: Pick<AppearanceSettings, "reducedMotion" | "highContrast">,
): SettingsSnapshot {
  return {
    revision: document.revision,
    defaultSelection: document.defaultSelection === null ? null : { ...document.defaultSelection },
    providers: document.providers.map((provider) => ({
      ...provider,
      networkTimeouts: { ...provider.networkTimeouts },
      agentDefaults: {
        context: { ...provider.agentDefaults.context },
        turnLimits: { ...provider.agentDefaults.turnLimits },
      },
      models: provider.models.map((model) => ({
        ...model,
        capabilities: { ...model.capabilities },
        reasoningLevelMap: { ...model.reasoningLevelMap },
      })),
    })),
    mcpServers: document.mcpServers.map((server) => {
      const observed = runtimeMcpServers.find((item) => item.id === server.mcpRevision);
      return {
        id: server.mcpRevision,
        mcpRevision: server.mcpRevision,
        name: server.name,
        transport: server.transport,
        endpoint: server.endpoint,
        protocolVersion: server.protocolVersion,
        args: [...server.args],
        env: { ...server.env },
        headers: { ...server.headers },
        auth: { ...server.auth },
        enabled: server.enabled,
        status: observed?.status ?? (server.enabled ? ("unknown" as const) : ("disabled" as const)),
        tools: observed?.tools ?? [],
        ...(observed?.lastError === undefined ? {} : { lastError: observed.lastError }),
        globallyEnabled:
          userDocument.mcpServers.find((item) => item.mcpRevision === server.mcpRevision)
            ?.enabled ?? false,
        projectOverridden: projectOverrides.disabledMcpIds.includes(server.mcpRevision),
      };
    }),
    skills: runtimeSkills.map((skill) => ({
      ...skill,
      globallyEnabled:
        userDocument.skills?.find((item) => item.skillId === skill.id)?.enabled ?? false,
      projectOverridden: projectOverrides.disabledSkillIds.includes(skill.id),
    })),
    defaultAccessMode: document.defaultAccessMode,
    globalAccessMode: userDocument.defaultAccessMode,
    projectOverrides,
    appearance: {
      theme: document.theme,
      palette: "xcode",
      reducedMotion: appearance.reducedMotion,
      highContrast: appearance.highContrast,
    },
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

/**
 * Overlay 表单会提交完整 effective 对象；只有值相对 effective 真正变化时才覆盖 user 值，
 * 从而避免未编辑的项目收紧项被反向扁平写入用户层。
 */
function userValueAfterEffectiveEdit<T>(userValue: T, effectiveValue: T, submittedValue: T): T {
  return JSON.stringify(submittedValue) === JSON.stringify(effectiveValue)
    ? structuredClone(userValue)
    : structuredClone(submittedValue);
}

/** Provider 保存逐叶合并 user/effective，模型目录由独立模型动作管理。 */
function mergeUserProviderEdit(
  user: ProviderProjection,
  effective: ProviderProjection,
  submitted: ProviderSave,
): ProviderProjection {
  return {
    providerId: user.providerId,
    name: userValueAfterEffectiveEdit(user.name, effective.name, submitted.name),
    provider: userValueAfterEffectiveEdit(user.provider, effective.provider, submitted.provider),
    api: userValueAfterEffectiveEdit(user.api, effective.api, submitted.api),
    baseUrl: userValueAfterEffectiveEdit(user.baseUrl, effective.baseUrl, submitted.baseUrl),
    credentialId: userValueAfterEffectiveEdit(
      user.credentialId,
      effective.credentialId,
      submitted.credentialId,
    ),
    credentialConfigured: effective.credentialConfigured,
    networkTimeouts: {
      connectTimeoutMs: userValueAfterEffectiveEdit(
        user.networkTimeouts.connectTimeoutMs,
        effective.networkTimeouts.connectTimeoutMs,
        submitted.networkTimeouts.connectTimeoutMs,
      ),
      requestTimeoutMs: userValueAfterEffectiveEdit(
        user.networkTimeouts.requestTimeoutMs,
        effective.networkTimeouts.requestTimeoutMs,
        submitted.networkTimeouts.requestTimeoutMs,
      ),
    },
    agentDefaults: {
      context: {
        autoCompact: userValueAfterEffectiveEdit(
          user.agentDefaults.context.autoCompact,
          effective.agentDefaults.context.autoCompact,
          submitted.agentDefaults.context.autoCompact,
        ),
      },
      turnLimits: {
        maxModelRounds: userValueAfterEffectiveEdit(
          user.agentDefaults.turnLimits.maxModelRounds,
          effective.agentDefaults.turnLimits.maxModelRounds,
          submitted.agentDefaults.turnLimits.maxModelRounds,
        ),
        maxToolCalls: userValueAfterEffectiveEdit(
          user.agentDefaults.turnLimits.maxToolCalls,
          effective.agentDefaults.turnLimits.maxToolCalls,
          submitted.agentDefaults.turnLimits.maxToolCalls,
        ),
        wallTimeoutMs: userValueAfterEffectiveEdit(
          user.agentDefaults.turnLimits.wallTimeoutMs,
          effective.agentDefaults.turnLimits.wallTimeoutMs,
          submitted.agentDefaults.turnLimits.wallTimeoutMs,
        ),
      },
    },
    models: structuredClone(user.models),
  };
}

/** 模型能力逐字段回写 user 层，未改动的 project 收紧值保持只读。 */
function mergeUserModelEdit(
  user: ProviderModelSave,
  effective: ProviderModelSave,
  submitted: ProviderModelSave,
): ProviderModelSave {
  return {
    modelId: user.modelId,
    name: userValueAfterEffectiveEdit(user.name, effective.name, submitted.name),
    model: userValueAfterEffectiveEdit(user.model, effective.model, submitted.model),
    capabilities: {
      contextWindowTokens: userValueAfterEffectiveEdit(
        user.capabilities.contextWindowTokens,
        effective.capabilities.contextWindowTokens,
        submitted.capabilities.contextWindowTokens,
      ),
      maxOutputTokens: userValueAfterEffectiveEdit(
        user.capabilities.maxOutputTokens,
        effective.capabilities.maxOutputTokens,
        submitted.capabilities.maxOutputTokens,
      ),
    },
    reasoningLevelMap: userValueAfterEffectiveEdit(
      user.reasoningLevelMap,
      effective.reasoningLevelMap,
      submitted.reasoningLevelMap,
    ),
    defaultReasoningLevel: userValueAfterEffectiveEdit(
      user.defaultReasoningLevel,
      effective.defaultReasoningLevel,
      submitted.defaultReasoningLevel,
    ),
  };
}

/**
 * Settings snapshot 按 workspace、App Server 实例和 runtime generation 分代；scope 切换
 * 让无法取消的旧 native 结果只能写回旧 key，配置事件则精确失效当前 key。
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
) {
  return ["settings", kind, serverInstanceId ?? "unavailable", runtimeGeneration ?? 0] as const;
}

/**
 * 独占设置文档、Provider、Skill/MCP 健康投影和 CAS 保存；workspace identity 仅作为
 * snapshot 查询参数，controller 不保存或切换 workspace，也不拥有会话状态。
 */
export function useSettingsController({
  adapter,
  appearancePort,
  workspaceScope,
  runtimeState,
  boot,
  configurationChange,
  runtimePort,
}: SettingsControllerOptions): SettingsController {
  const queryClient = useQueryClient();
  const [scope, setScopeState] = useState<"global" | "project">("global");
  // Workspace owner 位于 composition；每次 render 直接派生 query key，避免 ref 更新不触发 render
  // 而让 controller 继续观察旧项目配置。
  const projectWorkspaceId =
    workspaceScope?.kind === "project" ? workspaceScope.workspaceId : undefined;
  const queryWorkspaceId = scope === "project" ? projectWorkspaceId : undefined;
  const setScope = useCallback(
    (next: "global" | "project"): void => {
      setScopeState(next === "project" && projectWorkspaceId !== undefined ? "project" : "global");
    },
    [projectWorkspaceId],
  );

  /** 项目解绑后立即回到全局，禁止保留指向旧 workspace 的可写作用域。 */
  useEffect(() => {
    if (projectWorkspaceId === undefined) setScopeState("global");
  }, [projectWorkspaceId]);
  const { reducedMotion, highContrast, setThemeMode, setHighContrast, setReduceMotion } =
    appearancePort;
  const runtimeReady =
    runtimeState !== undefined && ["ready", "busy"].includes(runtimeState.status);
  // Query 与失效 effect 只依赖投影中的分代标量；调用方重建等值对象时不能形成 fetch 循环。
  const runtimeServerInstanceId = runtimeState?.serverInstanceId;
  const runtimeGeneration = runtimeState?.generation;
  const configurationVersion = configurationChange?.version;
  const configurationScope = configurationChange?.scope;
  const configurationWorkspaceId = configurationChange?.workspaceId;
  const configurationScopeKey = queryWorkspaceId ?? "general";
  const handledConfigurationVersionsRef = useRef<Map<string, string>>(new Map());
  // User 变更影响全部有效配置，Project 变更只允许推进匹配 workspace 的 key；其它项目的
  // timeline 事件不能让当前项目产生一次无意义的 configuration/read。
  const configurationMatchesScope =
    configurationScope === "user" ||
    (configurationScope === "project" && configurationWorkspaceId === queryWorkspaceId);
  const snapshotKey = useMemo(
    () => settingsSnapshotQueryKey(queryWorkspaceId, runtimeServerInstanceId, runtimeGeneration),
    [queryWorkspaceId, runtimeGeneration, runtimeServerInstanceId],
  );
  const skillsKey = useMemo(
    () => runtimeProjectionKey("skills", runtimeServerInstanceId, runtimeGeneration),
    [runtimeGeneration, runtimeServerInstanceId],
  );
  const mcpKey = useMemo(
    () => runtimeProjectionKey("mcp", runtimeServerInstanceId, runtimeGeneration),
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
   * 每个 runtime/scope/version 事件只失效一次当前 Query。新 key 正在首次读取时，该读取
   * 已覆盖事件后的权威状态，无需再排第二次；其它项目事件则完全不触碰当前 cache。
   */
  useEffect(() => {
    if (!configurationMatchesScope || configurationVersion === undefined) return;
    const eventKey = `${runtimeServerInstanceId ?? "unavailable"}:${runtimeGeneration ?? 0}:${configurationScopeKey}`;
    if (handledConfigurationVersionsRef.current.get(eventKey) === configurationVersion) return;
    handledConfigurationVersionsRef.current.set(eventKey, configurationVersion);
    if (settingsQuery.isPending || settingsQuery.isPlaceholderData) return;
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
    queryFn: async () => projectSkills(await runtimePort.listSkills()),
    enabled: runtimeReady,
  });

  /** MCP catalog 与流式 timeline 分离；仅保存有界、脱敏的一次性查询投影。 */
  const mcpQuery = useQuery({
    queryKey: mcpKey,
    queryFn: async () => projectMcpServers(await runtimePort.listMcpServers()),
    enabled: runtimeReady,
  });
  const loaded = settingsQuery.data;
  const runtimeSkills = useMemo(() => skillsQuery.data ?? [], [skillsQuery.data]);
  const runtimeMcpServers = useMemo(() => mcpQuery.data ?? [], [mcpQuery.data]);

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
        queryFn: async () => projectSkills(await runtimePort.listSkills()),
        staleTime: 0,
      }),
      queryClient.fetchQuery({
        queryKey: mcpKey,
        queryFn: async () => projectMcpServers(await runtimePort.listMcpServers()),
        staleTime: 0,
      }),
    ]);
  }, [mcpKey, queryClient, runtimePort, skillsKey]);

  /** user CAS replace 只接受已加载 user 文档，effective/project 投影永不成为写入基线。 */
  const saveDocument = useCallback(
    async (userDocument: SettingsDocument): Promise<void> => {
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      const version = await adapter.save(userDocument, current.cas.userVersion);
      updateLoadedQuery((snapshot) => ({
        ...snapshot,
        userDocument,
        cas: { ...snapshot.cas, userVersion: version },
      }));
      await reload();
    },
    [adapter, currentLoaded, reload, updateLoadedQuery],
  );

  /**
   * 项目设置只提交稀疏 Merge Patch；CAS 冲突或校验失败后立即重读权威快照，直接控件不保留
   * 可能已过期的乐观值。
   */
  const saveProjectPatch = useCallback(
    async (patch: Record<string, unknown>): Promise<void> => {
      const current = currentLoaded();
      if (current === undefined || queryWorkspaceId === undefined)
        throw new Error("project settings unavailable");
      try {
        await adapter.patch({
          scope: "project",
          workspaceId: queryWorkspaceId,
          expectedVersion: current.cas.projectVersion,
          patch,
        });
      } catch (error) {
        await reload();
        throw error;
      }
      await reload();
    },
    [adapter, currentLoaded, queryWorkspaceId, reload],
  );

  /** 删除整个项目层必须走 App Server reset，不用空对象猜测 Merge Patch 语义。 */
  const resetProject = useCallback(async (): Promise<void> => {
    const current = currentLoaded();
    if (current === undefined || queryWorkspaceId === undefined)
      throw new Error("project settings unavailable");
    try {
      await adapter.reset({
        scope: "project",
        workspaceId: queryWorkspaceId,
        expectedVersion: current.cas.projectVersion,
      });
    } finally {
      await reload();
    }
  }, [adapter, currentLoaded, queryWorkspaceId, reload]);

  /** 保存 Provider 时只把相对 effective 的真实编辑应用到 user 文档。 */
  const saveProvider = useCallback(
    async (provider: ProviderSave): Promise<void> => {
      if (scope === "project") throw new Error("project provider is read only");
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      const effective = current.document.providers.find(
        (item) => item.providerId === provider.providerId,
      );
      const existing = current.userDocument.providers.find(
        (item) => item.providerId === provider.providerId,
      );
      if (effective !== undefined && existing === undefined)
        throw new Error("project provider is read only");
      const merged: ProviderProjection =
        existing === undefined || effective === undefined
          ? { ...provider, credentialConfigured: false }
          : mergeUserProviderEdit(existing, effective, provider);
      const firstModel = merged.models[0];
      if (firstModel === undefined) throw new Error("provider requires model");
      await saveDocument({
        ...current.userDocument,
        revision: current.userDocument.revision + 1,
        defaultSelection: current.userDocument.defaultSelection ?? {
          providerId: provider.providerId,
          modelId: firstModel.modelId,
          reasoningLevel: firstModel.defaultReasoningLevel,
        },
        providers:
          existing === undefined
            ? [...current.userDocument.providers, merged]
            : current.userDocument.providers.map((item) =>
                item.providerId === provider.providerId ? merged : item,
              ),
      });
    },
    [currentLoaded, saveDocument, scope],
  );

  /** 删除默认 Provider 时只接受用户显式选择的替代项，避免按数组顺序静默改变默认模型。 */
  const deleteProvider = useCallback(
    async (providerId: string, replacement: DefaultModelSelection | null): Promise<void> => {
      if (scope === "project") throw new Error("project provider is read only");
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      const providers = current.userDocument.providers.filter(
        (provider) => provider.providerId !== providerId,
      );
      if (providers.length === current.userDocument.providers.length)
        throw new Error("provider unavailable");
      const deletesDefault = current.userDocument.defaultSelection?.providerId === providerId;
      const replacementModel =
        replacement === null
          ? undefined
          : providers
              .find((provider) => provider.providerId === replacement.providerId)
              ?.models.find((model) => model.modelId === replacement.modelId);
      const replacementReasoningLevel = replacement?.reasoningLevel ?? null;
      if (deletesDefault && providers.length > 0 && replacementModel === undefined)
        throw new Error("replacement model is required");
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
    [currentLoaded, saveDocument, scope],
  );

  /** Provider 排序只改变展示与分组顺序，不改变稳定 ID 或默认选择。 */
  const moveProvider = useCallback(
    async (providerId: string, direction: -1 | 1): Promise<void> => {
      if (scope === "project") throw new Error("project provider is read only");
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
    [currentLoaded, saveDocument, scope],
  );

  /** 单模型保存按 effective 差异更新 user 能力，并同步收敛根默认思考档位。 */
  const saveModel = useCallback(
    async (providerId: string, model: ProviderModelSave): Promise<void> => {
      if (scope === "project") throw new Error("project model catalog is read only");
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      const effectiveProvider = current.document.providers.find(
        (candidate) => candidate.providerId === providerId,
      );
      const provider = current.userDocument.providers.find(
        (candidate) => candidate.providerId === providerId,
      );
      if (provider === undefined || effectiveProvider === undefined)
        throw new Error("provider unavailable");
      const userModel = provider.models.find((candidate) => candidate.modelId === model.modelId);
      const effectiveModel = effectiveProvider.models.find(
        (candidate) => candidate.modelId === model.modelId,
      );
      if (effectiveModel !== undefined && userModel === undefined)
        throw new Error("project model is read only");
      const savedModel =
        userModel === undefined || effectiveModel === undefined
          ? model
          : mergeUserModelEdit(userModel, effectiveModel, model);
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
      await saveDocument({
        ...current.userDocument,
        revision: current.userDocument.revision + 1,
        defaultSelection,
        providers: current.userDocument.providers.map((candidate) =>
          candidate.providerId === providerId ? { ...candidate, models } : candidate,
        ),
      });
    },
    [currentLoaded, saveDocument, scope],
  );

  /** 删除默认模型时只接受用户显式选择的替代项，且替代项必须仍存在于删除后的目录。 */
  const deleteModel = useCallback(
    async (
      providerId: string,
      modelId: string,
      replacement: DefaultModelSelection | null,
    ): Promise<void> => {
      if (scope === "project") throw new Error("project model catalog is read only");
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
    [currentLoaded, saveDocument, scope],
  );

  /** 模型验证只允许全局已保存身份，测试请求正文和回答始终由 App Server 隔离。 */
  const testModel = useCallback(
    async (providerId: string, modelId: string) => {
      if (scope === "project") throw new Error("project model test is unavailable");
      const current = currentLoaded();
      const model = current?.userDocument.providers
        .find((provider) => provider.providerId === providerId)
        ?.models.find((candidate) => candidate.modelId === modelId);
      if (model === undefined) throw new Error("model unavailable");
      return runtimePort.testModel(providerId, modelId);
    },
    [currentLoaded, runtimePort, scope],
  );

  /** 模型排序只交换同一 Provider 内相邻项，不能跨 Provider 移动身份。 */
  const moveModel = useCallback(
    async (providerId: string, modelId: string, direction: -1 | 1): Promise<void> => {
      if (scope === "project") throw new Error("project model catalog is read only");
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
    [currentLoaded, saveDocument, scope],
  );

  /** 默认选择必须命中真实模型，思考档位由模型能力闭集裁决。 */
  const saveDefaultSelection = useCallback(
    async (selection: DefaultModelSelection): Promise<void> => {
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      const model = current.document.providers
        .find((provider) => provider.providerId === selection.providerId)
        ?.models.find((candidate) => candidate.modelId === selection.modelId);
      if (
        model === undefined ||
        (selection.reasoningLevel !== null &&
          model.reasoningLevelMap[selection.reasoningLevel] === undefined)
      ) {
        throw new Error("default selection unavailable");
      }
      if (scope === "project") {
        await saveProjectPatch({
          default_provider_id: selection.providerId,
          default_model_id: selection.modelId,
          default_reasoning_level: selection.reasoningLevel,
        });
        return;
      }
      await saveDocument({
        ...current.userDocument,
        revision: current.userDocument.revision + 1,
        defaultSelection: selection,
      });
    },
    [currentLoaded, saveDocument, saveProjectPatch, scope],
  );

  /** 恢复模型继承必须删除三个关联叶子，避免留下半覆盖的 Provider/Model/Reasoning 组合。 */
  const restoreDefaultSelection = useCallback(async (): Promise<void> => {
    if (scope !== "project") return;
    await saveProjectPatch({
      default_provider_id: null,
      default_model_id: null,
      default_reasoning_level: null,
    });
  }, [saveProjectPatch, scope]);

  /** 持久化根默认访问模式；每个 Turn 的实际快照由 App Server 冻结。 */
  const saveAccessMode = useCallback(
    async (mode: AccessMode): Promise<void> => {
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      if (scope === "project") {
        if (mode === "full_access" && current.userDocument.defaultAccessMode !== "full_access")
          throw new Error("project access cannot exceed global access");
        await saveProjectPatch({
          default_access_mode:
            mode === current.userDocument.defaultAccessMode ? null : "approval_required",
        });
        return;
      }
      await saveDocument({
        ...current.userDocument,
        revision: current.userDocument.revision + 1,
        defaultAccessMode: mode,
      });
    },
    [currentLoaded, saveDocument, saveProjectPatch, scope],
  );

  /**
   * 外观中的 WebView 偏好写入唯一 UI preferences store；theme 同步到当前脱敏文档投影，
   * 不新增第二套持久 store，也不触碰 credential。
   */
  const saveAppearance = useCallback(
    async (appearance: AppearanceSettings): Promise<void> => {
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      setThemeMode(appearance.theme);
      setHighContrast(appearance.highContrast);
      setReduceMotion(appearance.reducedMotion);
      updateLoadedQuery((snapshot) => ({
        ...snapshot,
        document: { ...snapshot.document, theme: appearance.theme },
      }));
    },
    [currentLoaded, setHighContrast, setReduceMotion, setThemeMode, updateLoadedQuery],
  );

  /** 保存 MCP 定义时保留高级非敏感 map，并通过同一 CAS replace 路径提交。 */
  const saveMcp = useCallback(
    async (server: McpServerSave): Promise<void> => {
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      if (scope === "project") {
        const global = current.userDocument.mcpServers.find(
          (item) => item.mcpRevision === server.mcpRevision,
        );
        if (global === undefined || !global.enabled)
          throw new Error("project MCP server is unavailable");
        const disabled = current.userDocument.mcpServers
          .filter(
            (item) =>
              item.enabled &&
              current.document.mcpServers.find(
                (effective) => effective.mcpRevision === item.mcpRevision,
              )?.enabled === false,
          )
          .map((item) => item.mcpRevision);
        const next = new Set(disabled);
        if (server.enabled) next.delete(server.mcpRevision);
        else next.add(server.mcpRevision);
        await saveProjectPatch({
          mcp_servers: [...next].map((mcpId) => ({ mcp_id: mcpId, enabled: false })),
        });
        return;
      }
      const existing = current.userDocument.mcpServers.find(
        (item) => item.mcpRevision === server.mcpRevision,
      );
      const merged: SettingsMcpServer = {
        ...(existing ?? { protocolVersion: "2025-06-18" as const }),
        ...server,
      };
      await saveDocument({
        ...current.userDocument,
        revision: current.userDocument.revision + 1,
        mcpServers:
          existing === undefined
            ? [...current.userDocument.mcpServers, merged]
            : current.userDocument.mcpServers.map((item) =>
                item.mcpRevision === server.mcpRevision ? merged : item,
              ),
      });
    },
    [currentLoaded, saveDocument, saveProjectPatch, scope],
  );

  /** 运行一次真实 MCP probe 并只更新对应 server 的健康投影。 */
  const testMcp = useCallback(
    async (
      mcpRevision: string,
    ): Promise<"unknown" | "connected" | "disabled" | "testing" | "error"> => {
      const server = currentLoaded()?.document.mcpServers.find(
        (item) => item.mcpRevision === mcpRevision,
      );
      if (server === undefined || !server.enabled) return "disabled";
      const result = await runtimePort.testMcp(mcpRevision);
      const tools = projectMcpTools(await runtimePort.listMcpTools(mcpRevision));
      queryClient.setQueryData<McpServerProjection[]>(mcpKey, (items = []) =>
        items.map((item) =>
          item.id === mcpRevision
            ? {
                ...item,
                status: result.status === "healthy" ? "connected" : "error",
                tools,
                lastError: result.status === "healthy" ? undefined : "MCP Server 不可用。",
              }
            : item,
        ),
      );
      return result.status === "healthy" ? "connected" : "error";
    },
    [currentLoaded, mcpKey, queryClient, runtimePort],
  );

  /** 删除 MCP 只移除全局目录定义；Credential 引用保持独立，避免配置删除产生隐式 Secret 级联。 */
  const deleteMcp = useCallback(
    async (mcpRevision: string): Promise<void> => {
      if (scope === "project") throw new Error("project MCP catalog is read only");
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      const mcpServers = current.userDocument.mcpServers.filter(
        (server) => server.mcpRevision !== mcpRevision,
      );
      if (mcpServers.length === current.userDocument.mcpServers.length)
        throw new Error("MCP server unavailable");
      await saveDocument({
        ...current.userDocument,
        revision: current.userDocument.revision + 1,
        mcpServers,
      });
    },
    [currentLoaded, saveDocument, scope],
  );

  /** 通过现有 CAS/reconfigure 路径禁用 MCP，不在 UI 伪造关闭状态。 */
  const closeMcp = useCallback(
    async (mcpRevision: string): Promise<void> => {
      const server = currentLoaded()?.document.mcpServers.find(
        (item) => item.mcpRevision === mcpRevision,
      );
      if (server === undefined) throw new Error("MCP server unavailable");
      await saveMcp({
        mcpRevision: server.mcpRevision,
        name: server.name,
        transport: server.transport,
        endpoint: server.endpoint,
        args: [...server.args],
        env: { ...server.env },
        headers: { ...server.headers },
        auth: { ...server.auth },
        enabled: false,
      });
    },
    [currentLoaded, saveMcp],
  );

  /** Skill 开关只写根级 catalog 的唯一 enabled 事实，不再借用默认 Provider 作为隐含作用对象。 */
  const toggleSkill = useCallback(
    async (skillRevision: string, enabled: boolean): Promise<void> => {
      const current = currentLoaded();
      const observed = runtimeSkills.find((skill) => skill.id === skillRevision);
      const configured = current?.userDocument.skills?.find(
        (skill) => skill.skillId === skillRevision,
      );
      if (current === undefined || observed === undefined || configured === undefined)
        throw new Error("skill unavailable");
      if (scope === "project") {
        if (!configured.enabled)
          throw new Error("project Skill cannot enable a global disabled item");
        const disabled = (current.userDocument.skills ?? [])
          .filter(
            (skill) =>
              skill.enabled &&
              current.document.skills?.find((effective) => effective.skillId === skill.skillId)
                ?.enabled === false,
          )
          .map((skill) => skill.skillId);
        const next = new Set(disabled);
        if (enabled) next.delete(skillRevision);
        else next.add(skillRevision);
        await saveProjectPatch({
          skills: [...next].map((skillId) => ({ skill_id: skillId, enabled: false })),
        });
        await refreshRuntimeSettings();
        return;
      }
      await saveDocument({
        ...current.userDocument,
        revision: current.userDocument.revision + 1,
        skills: current.userDocument.skills?.map((skill) =>
          skill.skillId === skillRevision ? { ...skill, enabled } : skill,
        ),
      });
      await refreshRuntimeSettings();
    },
    [currentLoaded, refreshRuntimeSettings, runtimeSkills, saveDocument, saveProjectPatch, scope],
  );

  /** Secret 只穿过 native credential port，不写入 React state 或设置文档。 */
  const setCredential = useCallback(
    async (credentialId: string, secret: string): Promise<void> => {
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      const version = await adapter.setCredential(
        credentialId,
        secret,
        current.cas.credentialVersion,
      );
      updateLoadedQuery((snapshot) => ({
        ...snapshot,
        cas: { ...snapshot.cas, credentialVersion: version },
      }));
      await reload();
    },
    [adapter, currentLoaded, reload, updateLoadedQuery],
  );

  /** 删除 native Secret 时保留 Provider/MCP 中的不透明 credential 引用。 */
  const deleteCredential = useCallback(
    async (credentialId: string): Promise<void> => {
      const current = currentLoaded();
      if (current === undefined) throw new Error("settings unavailable");
      const version = await adapter.deleteCredential(credentialId, current.cas.credentialVersion);
      updateLoadedQuery((snapshot) => ({
        ...snapshot,
        cas: { ...snapshot.cas, credentialVersion: version },
      }));
      await reload();
    },
    [adapter, currentLoaded, reload, updateLoadedQuery],
  );

  /** Query 返回新设置后同步唯一外观 owner；不从 renderer 反向生成配置版本。 */
  useEffect(() => {
    if (loaded !== undefined) setThemeMode(loaded.document.theme);
  }, [loaded, setThemeMode]);

  /** 合并后的只读 snapshot 不缓存 Secret，也不把 runtime 健康状态写回设置文档。 */
  const snapshot = useMemo<SettingsSnapshot>(
    () =>
      loaded === undefined
        ? {
            revision: 0,
            defaultSelection: null,
            providers: [],
            skills: [],
            mcpServers: [],
            defaultAccessMode: "full_access",
            globalAccessMode: "full_access",
            projectOverrides: {
              defaultSelection: false,
              accessMode: false,
              disabledSkillIds: [],
              disabledMcpIds: [],
            },
            appearance: { theme: "system", palette: "xcode", reducedMotion, highContrast },
          }
        : toSettingsSnapshot(
            loaded.document,
            loaded.userDocument,
            loaded.projectOverrides,
            runtimeSkills,
            runtimeMcpServers,
            {
              reducedMotion,
              highContrast,
            },
          ),
    [highContrast, loaded, reducedMotion, runtimeMcpServers, runtimeSkills],
  );

  /** 以稳定 action 集合作为 UI 边界，避免视图接触 adapter 或 CAS 文档。 */
  const ports = useMemo<SettingsPorts>(
    () => ({
      onSaveProvider: saveProvider,
      onDeleteProvider: deleteProvider,
      onMoveProvider: moveProvider,
      onSaveModel: saveModel,
      onTestModel: testModel,
      onDeleteModel: deleteModel,
      onMoveModel: moveModel,
      onDefaultSelectionChange: saveDefaultSelection,
      onRestoreDefaultSelection: restoreDefaultSelection,
      onReplaceCredential: setCredential,
      onClearCredential: deleteCredential,
      onSaveMcp: saveMcp,
      onDeleteMcp: deleteMcp,
      onTestMcp: testMcp,
      onCloseMcp: closeMcp,
      onToggleSkill: toggleSkill,
      onAccessModeChange: saveAccessMode,
      onAppearanceChange: saveAppearance,
      onResetProject: resetProject,
    }),
    [
      closeMcp,
      deleteModel,
      deleteMcp,
      deleteProvider,
      deleteCredential,
      moveModel,
      moveProvider,
      resetProject,
      saveAppearance,
      saveAccessMode,
      saveDefaultSelection,
      restoreDefaultSelection,
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
  // 后台重读保留上一份只读投影，但在当前 key 获得权威结果前关闭写入与 Turn admission；
  // 这让切换过程保持可见，同时绝不拿旧项目 Provider 配置启动新项目 Turn。
  const synchronizing = runtimeReady && settingsQuery.isFetching;
  const scopeReady =
    runtimeReady &&
    settingsQuery.isSuccess &&
    !settingsQuery.isPlaceholderData &&
    !settingsQuery.isFetching;
  const error =
    boot.status === "failed" || boot.status === "degraded"
      ? `本地运行时启动失败：${boot.message}`
      : boot.status === "recovery_required"
        ? "本地运行时需要先完成恢复。"
        : settingsQuery.isError
          ? "设置配置无效或无法读取，需要先完成恢复；当前已禁止保存。"
          : undefined;
  return {
    loaded,
    snapshot,
    loading,
    synchronizing,
    scopeReady,
    scopeWorkspaceId: queryWorkspaceId,
    scope,
    projectAvailable: projectWorkspaceId !== undefined,
    error,
    ports,
    reload,
    setScope,
  };
}
