// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useRef, useState } from "react";
import {
  workspaceFromGeneral,
  workspaceFromHistory,
  type WorkspaceProjection,
} from "../domain/workspace";
import type { GeneralWorkspaceRecord, WorkspaceHistoryPort, WorkspaceRuntimeState } from "./ports";

/** 目录选择端口由 App composition 注入，application 不直接创建 Tauri adapter。 */
export interface WorkspacePickerPort {
  pick(): Promise<string | null>;
}

/** workspace 切换前取得的释放函数必须幂等，供成功、失败和过期 intent 统一清理。 */
type WorkspaceChangeRelease = () => void;

/**
 * 在替换 native workspace capability 前冻结特权资源；拒绝代表旧范围未安全关闭，
 * controller 必须中止切换而不能暴露半完成的新投影。
 */
type BeforeWorkspaceChange = (
  previousWorkspaceId: string,
  nextWorkspaceId?: string,
) => Promise<WorkspaceChangeRelease | void>;

interface WorkspaceControllerOptions {
  history: WorkspaceHistoryPort;
  picker: WorkspacePickerPort;
  generalWorkspace: () => Promise<GeneralWorkspaceRecord>;
  runtimeState: WorkspaceRuntimeState | undefined;
  configurationReady: boolean;
  beforeWorkspaceChange: BeforeWorkspaceChange;
  onWorkspaceCommitted: (workspace: WorkspaceProjection | undefined) => void;
}

/** workspace controller 的公开契约只包含 workspace 事实和 workspace 用例。 */
export interface WorkspaceController {
  workspace: WorkspaceProjection | undefined;
  projects: WorkspaceProjection[];
  catalogLoading: boolean;
  catalogError: string | undefined;
  busy: boolean;
  error: string | undefined;
  revision: number;
  choose(): Promise<void>;
  selectGeneral(): Promise<void>;
  select(workspaceId: string): Promise<void>;
  retryCatalog(): Promise<void>;
  activateForConversation(workspaceId: string): Promise<WorkspaceProjection | undefined>;
}

interface WorkspacePreparation {
  release?: WorkspaceChangeRelease;
}

/**
 * 独占 workspace 目录、活动身份和 native capability 切换；会话和设置只把当前身份作为输入，
 * 不在本 controller 保存 Thread 或配置文档，从而避免一次切换产生多个事实 owner。
 */
export function useWorkspaceController({
  history,
  picker,
  generalWorkspace,
  runtimeState,
  configurationReady,
  beforeWorkspaceChange,
  onWorkspaceCommitted,
}: WorkspaceControllerOptions): WorkspaceController {
  const [workspace, setWorkspace] = useState<WorkspaceProjection>();
  const [projects, setProjects] = useState<WorkspaceProjection[]>([]);
  const [catalogLoading, setCatalogLoading] = useState(false);
  const [catalogError, setCatalogError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [revision, setRevision] = useState(0);
  const workspaceRef = useRef<WorkspaceProjection | undefined>(undefined);
  const generalWorkspaceIdRef = useRef<string | undefined>(undefined);
  const intentRef = useRef(0);
  const catalogRequestRef = useRef(0);
  const mountedRef = useRef(false);
  const automaticGeneralKeyRef = useRef<string | undefined>(undefined);

  /**
   * 推进 workspace intent，同时取消旧目录请求；数值只用于 renderer 竞态栅栏，
   * 不冒充 Java workspace revision。
   */
  const beginIntent = useCallback((): number => {
    intentRef.current += 1;
    catalogRequestRef.current += 1;
    return intentRef.current;
  }, []);

  /** 仅允许仍挂载且属于最新用户意图的异步 continuation 写入 workspace 状态。 */
  const isCurrentIntent = useCallback(
    (intent: number): boolean => mountedRef.current && intentRef.current === intent,
    [],
  );

  /**
   * 刷新独立目录投影；活动 project 时禁止调用 generalWorkspace，因为该 native 命令会
   * 重绑 Host，目录读取不能暗中改变 workspace capability。
   */
  const refreshCatalog = useCallback(
    async (intent: number, retryOnce: boolean): Promise<void> => {
      const request = catalogRequestRef.current + 1;
      catalogRequestRef.current = request;
      setCatalogLoading(true);
      setCatalogError(undefined);
      const current = (): boolean =>
        isCurrentIntent(intent) && catalogRequestRef.current === request;
      try {
        for (let attempt = 0; attempt < (retryOnce ? 2 : 1); attempt += 1) {
          try {
            let generalId = generalWorkspaceIdRef.current;
            if (generalId === undefined) {
              if (workspaceRef.current?.kind === "project")
                throw new Error("general workspace identity unavailable");
              const general = await generalWorkspace();
              generalId = general.workspaceId;
              generalWorkspaceIdRef.current = generalId;
            }
            const listed = await history.workspaceList({ limit: 200 });
            if (!current()) return;
            setProjects(
              listed.items
                .filter((candidate) => candidate.workspaceId !== generalId)
                .map(workspaceFromHistory),
            );
            return;
          } catch {
            if (attempt + 1 >= (retryOnce ? 2 : 1)) break;
            await new Promise<void>((resolve) => globalThis.setTimeout(resolve, 250));
            if (!current()) return;
          }
        }
        if (current()) setCatalogError("项目列表暂时不可用，请重试。");
      } finally {
        if (current()) setCatalogLoading(false);
      }
    },
    [generalWorkspace, history, isCurrentIntent],
  );

  /**
   * 在 native 替换前获取资源 fence；返回 lease 而不在这里提前释放，确保所有失败路径
   * 都由同一 openWorkspace finally 收口。
   */
  const prepareChange = useCallback(
    async (nextWorkspaceId?: string): Promise<WorkspacePreparation> => {
      const previous = workspaceRef.current;
      if (previous === undefined) return {};
      const release =
        (await beforeWorkspaceChange(previous.workspaceId, nextWorkspaceId)) ?? undefined;
      return { release };
    },
    [beforeWorkspaceChange],
  );

  /**
   * 提交已经通过 native 校验的 workspace 投影；先关闭旧资源再替换投影，失败时 fail closed，
   * revision 只在 commit 后递增，让 conversation controller 用它建立切换 fence。
   */
  const commitWorkspace = useCallback(
    async (
      selected: WorkspaceProjection,
      intent: number,
      preparation?: WorkspacePreparation,
    ): Promise<WorkspaceProjection | undefined> => {
      let release = preparation?.release;
      let replacementStarted = false;
      setBusy(true);
      setError(undefined);
      try {
        if (workspaceRef.current !== undefined && preparation === undefined) {
          release =
            (await beforeWorkspaceChange(workspaceRef.current.workspaceId, selected.workspaceId)) ??
            undefined;
          if (!isCurrentIntent(intent)) return undefined;
        }
        replacementStarted = true;
        workspaceRef.current = undefined;
        setWorkspace(undefined);
        workspaceRef.current = selected;
        // 与活动 Workspace 在同一 React batch 发布 Settings scope，下一帧绝不能出现
        // “新 Workspace + 旧配置已就绪”的可交互组合。
        onWorkspaceCommitted(selected);
        setWorkspace(selected);
        setRevision((current) => current + 1);
        void refreshCatalog(intent, true);
        return selected;
      } catch {
        if (replacementStarted && isCurrentIntent(intent)) {
          workspaceRef.current = undefined;
          onWorkspaceCommitted(undefined);
          setWorkspace(undefined);
          setError(
            selected.kind === "general"
              ? "默认对话未能打开，请检查设置和运行时状态后重试。 "
              : "项目未能打开，请检查目录和运行时状态后重试。 ",
          );
        }
        return undefined;
      } finally {
        release?.();
        if (isCurrentIntent(intent)) setBusy(false);
      }
    },
    [beforeWorkspaceChange, isCurrentIntent, onWorkspaceCommitted, refreshCatalog],
  );

  /**
   * 通过服务端 workspace/open 重新建立持久项目的 Rust capability；目录元数据本身不具备
   * 文件访问权限，因此绝不能直接把 catalog 行设为活动 workspace。
   */
  const openPersisted = useCallback(
    async (
      selected: WorkspaceProjection,
      intent: number,
    ): Promise<WorkspaceProjection | undefined> => {
      if (history.workspaceOpen === undefined)
        throw new Error("workspace open capability unavailable");
      const preparation = await prepareChange(selected.workspaceId);
      let transferred = false;
      try {
        if (!isCurrentIntent(intent)) return undefined;
        const opened = await history.workspaceOpen({
          cwd: selected.rootPath,
          displayName: selected.displayName,
        });
        if (!isCurrentIntent(intent) || opened.workspaceId !== selected.workspaceId)
          return undefined;
        transferred = true;
        return await commitWorkspace(workspaceFromHistory(opened), intent, preparation);
      } finally {
        if (!transferred) preparation.release?.();
      }
    },
    [commitWorkspace, history, isCurrentIntent, prepareChange],
  );

  /** 目录选择取消时保留原 workspace；只有 server-issued identity 返回后才提交新投影。 */
  const choose = useCallback(async (): Promise<void> => {
    if (!configurationReady || history.workspaceOpen === undefined) return;
    const intent = beginIntent();
    setBusy(true);
    setError(undefined);
    let preparation: WorkspacePreparation | undefined;
    let transferred = false;
    try {
      const rootPath = await picker.pick();
      if (rootPath === null || !isCurrentIntent(intent)) return;
      if (rootPath.trim().length === 0) {
        setError("请选择一个项目目录。 ");
        return;
      }
      preparation = await prepareChange();
      if (!isCurrentIntent(intent)) return;
      const opened = await history.workspaceOpen({ cwd: rootPath });
      if (!isCurrentIntent(intent)) return;
      transferred = true;
      await commitWorkspace(workspaceFromHistory(opened), intent, preparation);
    } catch {
      if (isCurrentIntent(intent)) setError("项目未能打开，请检查目录和运行时状态后重试。 ");
    } finally {
      if (!transferred) preparation?.release?.();
      if (isCurrentIntent(intent)) setBusy(false);
    }
  }, [
    configurationReady,
    beginIntent,
    commitWorkspace,
    history,
    isCurrentIntent,
    picker,
    prepareChange,
  ]);

  /** 从当前 catalog 选择项目；同一 identity 为幂等 no-op，不重复关闭资源。 */
  const select = useCallback(
    async (workspaceId: string): Promise<void> => {
      if (!configurationReady || workspaceRef.current?.workspaceId === workspaceId) return;
      const selected = projects.find((candidate) => candidate.workspaceId === workspaceId);
      if (selected === undefined) return;
      const intent = beginIntent();
      setBusy(true);
      setError(undefined);
      try {
        const opened = await openPersisted(selected, intent);
        if (opened === undefined && isCurrentIntent(intent))
          setError("项目未能打开，请检查目录和运行时状态后重试。 ");
      } catch {
        if (isCurrentIntent(intent)) setError("项目未能打开，请检查目录和运行时状态后重试。 ");
      } finally {
        if (isCurrentIntent(intent)) setBusy(false);
      }
    },
    [configurationReady, beginIntent, isCurrentIntent, openPersisted, projects],
  );

  /**
   * 从项目返回受管 general workspace；资源 fence 必须先于 generalWorkspace 调用，因为后者
   * 会在 native Host 重绑 capability。当前已是 general 时保持幂等，不制造无意义 revision。
   */
  const selectGeneral = useCallback(async (): Promise<void> => {
    if (!configurationReady || workspaceRef.current?.kind === "general") return;
    const intent = beginIntent();
    setBusy(true);
    setError(undefined);
    let preparation: WorkspacePreparation | undefined;
    let transferred = false;
    try {
      preparation = await prepareChange();
      if (!isCurrentIntent(intent)) return;
      const general = await generalWorkspace();
      generalWorkspaceIdRef.current = general.workspaceId;
      if (!isCurrentIntent(intent)) return;
      transferred = true;
      const committed = await commitWorkspace(workspaceFromGeneral(general), intent, preparation);
      if (committed === undefined && isCurrentIntent(intent))
        setError("默认对话未能打开，请检查设置和运行时状态后重试。 ");
    } catch {
      if (isCurrentIntent(intent)) setError("默认对话未能打开，请检查设置和运行时状态后重试。 ");
    } finally {
      if (!transferred) preparation?.release?.();
      if (isCurrentIntent(intent)) setBusy(false);
    }
  }, [
    beginIntent,
    commitWorkspace,
    configurationReady,
    generalWorkspace,
    isCurrentIntent,
    prepareChange,
  ]);

  /**
   * 为跨项目会话激活所属 workspace；此方法只返回 workspace 结果，Thread 恢复仍由
   * conversation controller 完成，避免 workspace 成为第二个会话 owner。
   */
  const activateForConversation = useCallback(
    async (workspaceId: string): Promise<WorkspaceProjection | undefined> => {
      if (workspaceRef.current?.workspaceId === workspaceId) return workspaceRef.current;
      const intent = beginIntent();
      try {
        const general = await generalWorkspace();
        generalWorkspaceIdRef.current = general.workspaceId;
        if (!isCurrentIntent(intent)) return undefined;
        if (general.workspaceId === workspaceId)
          return await commitWorkspace(workspaceFromGeneral(general), intent);
        let target = projects.find((candidate) => candidate.workspaceId === workspaceId);
        if (target === undefined) {
          const listed = await history.workspaceList({ limit: 200 });
          target =
            listed.items.find((candidate) => candidate.workspaceId === workspaceId) === undefined
              ? undefined
              : workspaceFromHistory(
                  listed.items.find((candidate) => candidate.workspaceId === workspaceId)!,
                );
        }
        return target === undefined ? undefined : await openPersisted(target, intent);
      } catch {
        if (isCurrentIntent(intent)) setError("会话所属工作区暂时无法打开。 ");
        return undefined;
      }
    },
    [
      beginIntent,
      commitWorkspace,
      generalWorkspace,
      history,
      isCurrentIntent,
      openPersisted,
      projects,
    ],
  );

  /** 组件卸载时推进 intent，使所有晚到 native 结果失效且不再写 React 状态。 */
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      intentRef.current += 1;
      catalogRequestRef.current += 1;
    };
  }, []);

  /**
   * 首个有效 v4 配置自动打开 general workspace；key 只包含 runtime generation 与 ready，
   * StrictMode 或失败重渲染不会重复创建 durable Thread，runtime 重启后则允许重新绑定。
   */
  useEffect(() => {
    if (
      !configurationReady ||
      runtimeState === undefined ||
      !["ready", "busy"].includes(runtimeState.status)
    ) {
      automaticGeneralKeyRef.current = undefined;
      return;
    }
    if (workspaceRef.current !== undefined || busy) return;
    const key = `${runtimeState.generation}:ready`;
    if (automaticGeneralKeyRef.current === key) return;
    automaticGeneralKeyRef.current = key;
    const intent = beginIntent();
    void (async (): Promise<void> => {
      try {
        const general = await generalWorkspace();
        generalWorkspaceIdRef.current = general.workspaceId;
        if (isCurrentIntent(intent)) await commitWorkspace(workspaceFromGeneral(general), intent);
      } catch {
        if (isCurrentIntent(intent)) setError("默认对话未能打开，请检查设置和运行时状态后重试。 ");
      }
    })();
  }, [
    configurationReady,
    beginIntent,
    busy,
    commitWorkspace,
    generalWorkspace,
    isCurrentIntent,
    runtimeState,
  ]);

  /** 只重试目录查询，不重新绑定 workspace 或触发资源 fence。 */
  const retryCatalog = useCallback(async (): Promise<void> => {
    await refreshCatalog(intentRef.current, false);
  }, [refreshCatalog]);

  return {
    workspace,
    projects,
    catalogLoading,
    catalogError,
    busy,
    error,
    revision,
    choose,
    selectGeneral,
    select,
    retryCatalog,
    activateForConversation,
  };
}
