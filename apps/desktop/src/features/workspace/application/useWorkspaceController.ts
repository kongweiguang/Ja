// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useRef, useState } from "react";
import {
  workspaceFromActivation,
  workspaceFromHistory,
  type WorkspaceKind,
  type WorkspaceProjection,
} from "../domain/workspace";
import type {
  WorkspaceActivationRecord,
  WorkspaceHistoryPort,
  WorkspaceRuntimeState,
} from "./ports";

/** 目录选择端口由 App composition 注入，application 不直接创建 Tauri adapter。 */
export interface WorkspacePickerPort {
  pick(): Promise<string | null>;
}

/** workspace 切换前取得的释放函数必须幂等，供成功、失败和过期 intent 统一清理。 */
type WorkspaceChangeRelease = () => void;

/**
 * 项目与会话目录之间的切换需要回收 workspace 级资源；两个 session 之间只切 active id，
 * 否则会误关后台 Thread 自己拥有的终端与预览。
 */
type BeforeWorkspaceChange = (
  previousWorkspaceId: string,
  nextWorkspaceId?: string,
) => Promise<WorkspaceChangeRelease | void>;

interface WorkspaceControllerOptions {
  history: WorkspaceHistoryPort;
  picker: WorkspacePickerPort;
  activateWorkspace: (workspaceId: string) => Promise<WorkspaceActivationRecord>;
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
  selectNoProject(): Promise<void>;
  select(workspaceId: string): Promise<void>;
  retryCatalog(): Promise<void>;
  activateForConversation(workspaceId: string): Promise<WorkspaceProjection | undefined>;
}

interface WorkspacePreparation {
  release?: WorkspaceChangeRelease;
}

/**
 * 独占项目选择、active Host activation 与设置 scope 切换；无项目入口只清空当前选择，
 * 新建会话后再由 Java-issued id 激活专属目录，不为 blank state 绑定共享 cwd。
 */
export function useWorkspaceController({
  history,
  picker,
  activateWorkspace,
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
  const retainedSessionWorkspaceRef = useRef<WorkspaceProjection | undefined>(undefined);
  const intentRef = useRef(0);
  const catalogRequestRef = useRef(0);
  const mountedRef = useRef(false);

  /** 推进 workspace intent，同时取消旧目录请求；数值只用于 renderer 竞态栅栏。 */
  const beginIntent = useCallback((): number => {
    intentRef.current += 1;
    catalogRequestRef.current += 1;
    return intentRef.current;
  }, []);

  /** 只允许仍挂载且属于最新用户意图的异步 continuation 写入 workspace 状态。 */
  const isCurrentIntent = useCallback(
    (intent: number): boolean => mountedRef.current && intentRef.current === intent,
    [],
  );

  /** 只把明确标记为 project 的 Java rows 放进项目区，session 根目录由 Thread kind 聚合。 */
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
            const items = [];
            let cursor: string | undefined;
            const visitedCursors = new Set<string>();
            do {
              const listed = await history.workspaceList({ kind: "project", limit: 200, cursor });
              if (!current()) return;
              items.push(...listed.items);
              const nextCursor = listed.nextCursor ?? undefined;
              if (nextCursor !== undefined && visitedCursors.has(nextCursor))
                throw new Error("workspace/list repeated a cursor");
              if (nextCursor !== undefined) visitedCursors.add(nextCursor);
              cursor = nextCursor;
            } while (cursor !== undefined);
            if (!current()) return;
            setProjects(items.map(workspaceFromHistory));
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
    [history, isCurrentIntent],
  );

  /**
   * Session→session 与 session→blank 都不申请资源 fence，保证隐藏会话的 PTY 继续存活；
   * 离开无项目分类进入项目时，仍以当前或保留的 session identity 汇总清理此前资源。
   */
  const prepareChange = useCallback(
    async (
      nextKind: WorkspaceKind | undefined,
      nextWorkspaceId?: string,
    ): Promise<WorkspacePreparation> => {
      const previous = workspaceRef.current ?? retainedSessionWorkspaceRef.current;
      if (previous === undefined) return {};
      if (previous.kind === "session" && (nextKind === "session" || nextKind === undefined))
        return {};
      if (workspaceRef.current === undefined && nextKind !== "project") return {};
      const release =
        (await beforeWorkspaceChange(previous.workspaceId, nextWorkspaceId)) ?? undefined;
      return { release };
    },
    [beforeWorkspaceChange],
  );

  /**
   * 提交已由 native 校验的 workspace projection；同一 session family 内不释放资源，
   * 项目边界则先完成 fence，并只向 Settings 投影 project scope。
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
          const previous = workspaceRef.current;
          if (!(previous.kind === "session" && selected.kind === "session")) {
            release =
              (await beforeWorkspaceChange(previous.workspaceId, selected.workspaceId)) ??
              undefined;
            if (!isCurrentIntent(intent)) return undefined;
          }
        } else if (workspaceRef.current === undefined && preparation === undefined) {
          const retained = retainedSessionWorkspaceRef.current;
          if (retained !== undefined && selected.kind === "project") {
            release =
              (await beforeWorkspaceChange(retained.workspaceId, selected.workspaceId)) ??
              undefined;
            if (!isCurrentIntent(intent)) return undefined;
          }
        }
        replacementStarted = true;
        workspaceRef.current = undefined;
        setWorkspace(undefined);
        workspaceRef.current = selected;
        if (selected.kind === "session") retainedSessionWorkspaceRef.current = selected;
        else if (selected.kind === "project") retainedSessionWorkspaceRef.current = undefined;
        onWorkspaceCommitted(selected.kind === "project" ? selected : undefined);
        setWorkspace(selected);
        if (selected.kind === "project") {
          setProjects((current) => {
            const index = current.findIndex(
              (candidate) => candidate.workspaceId === selected.workspaceId,
            );
            if (index < 0) return [selected, ...current];
            const next = [...current];
            next[index] = selected;
            return next;
          });
        }
        setRevision((current) => current + 1);
        void refreshCatalog(intent, true);
        return selected;
      } catch {
        if (replacementStarted && isCurrentIntent(intent)) {
          workspaceRef.current = undefined;
          onWorkspaceCommitted(undefined);
          setWorkspace(undefined);
          setError("工作目录未能激活，请检查运行时状态后重试。 ");
        }
        return undefined;
      } finally {
        release?.();
        if (isCurrentIntent(intent)) setBusy(false);
      }
    },
    [beforeWorkspaceChange, isCurrentIntent, onWorkspaceCommitted, refreshCatalog],
  );

  /** 项目目录需要通过 cwd 创建/重开；持久 session 必须走 ID-only native activation。 */
  const openPersisted = useCallback(
    async (
      selected: WorkspaceProjection,
      intent: number,
    ): Promise<WorkspaceProjection | undefined> => {
      if (selected.kind === "project") {
        if (history.workspaceOpen === undefined)
          throw new Error("project workspace open capability unavailable");
        const preparation = await prepareChange(selected.kind, selected.workspaceId);
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
      }

      const activation = await activateWorkspace(selected.workspaceId);
      if (!isCurrentIntent(intent) || activation.workspaceId !== selected.workspaceId)
        return undefined;
      return commitWorkspace(workspaceFromActivation(activation), intent);
    },
    [activateWorkspace, commitWorkspace, history, isCurrentIntent, prepareChange],
  );

  /** 目录选择取消时保留原范围；Java 返回项目 identity 后才允许切换并提交。 */
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
      preparation = await prepareChange("project");
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

  /** 从服务端项目目录选择项目；同一 identity 为幂等 no-op。 */
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
   * 无项目入口只展示 session 聚合分类，不建立或激活共享 workspace；离开项目时仍先完成
   * 项目/保留会话资源的安全清理，已选 session 之间则保持各自 Host 与 PTY。
   */
  const selectNoProject = useCallback(async (): Promise<void> => {
    if (!configurationReady || workspaceRef.current === undefined) return;
    const intent = beginIntent();
    const previous = workspaceRef.current;
    setBusy(true);
    setError(undefined);
    let preparation: WorkspacePreparation | undefined;
    try {
      if (previous.kind === "project") preparation = await prepareChange(undefined);
      if (!isCurrentIntent(intent)) return;
      workspaceRef.current = undefined;
      setWorkspace(undefined);
      if (previous.kind === "project") retainedSessionWorkspaceRef.current = undefined;
      onWorkspaceCommitted(undefined);
      setRevision((current) => current + 1);
    } catch {
      if (isCurrentIntent(intent)) setError("无法切换到无项目对话，请重试。 ");
    } finally {
      preparation?.release?.();
      if (isCurrentIntent(intent)) setBusy(false);
    }
  }, [beginIntent, configurationReady, isCurrentIntent, onWorkspaceCommitted, prepareChange]);

  /**
   * Thread navigation activates the Java-owned SESSION by id or reopens a known project by cwd;
   * LEGACY_SHARED remains outside the default list and is opened only from its explicit folder action.
   */
  const activateForConversation = useCallback(
    async (workspaceId: string): Promise<WorkspaceProjection | undefined> => {
      if (workspaceRef.current?.workspaceId === workspaceId) return workspaceRef.current;
      const intent = beginIntent();
      try {
        let target = projects.find((candidate) => candidate.workspaceId === workspaceId);
        if (target === undefined) {
          let cursor: string | undefined;
          const visitedCursors = new Set<string>();
          do {
            const listed = await history.workspaceList({ kind: "project", limit: 200, cursor });
            if (!isCurrentIntent(intent)) return undefined;
            const record = listed.items.find((candidate) => candidate.workspaceId === workspaceId);
            if (record !== undefined) {
              target = workspaceFromHistory(record);
              break;
            }
            const nextCursor = listed.nextCursor ?? undefined;
            if (nextCursor !== undefined && visitedCursors.has(nextCursor))
              throw new Error("workspace/list repeated a cursor");
            if (nextCursor !== undefined) visitedCursors.add(nextCursor);
            cursor = nextCursor;
          } while (cursor !== undefined);
        }
        if (target !== undefined) return await openPersisted(target, intent);
        const activation = await activateWorkspace(workspaceId);
        if (!isCurrentIntent(intent) || activation.workspaceId !== workspaceId) return undefined;
        return await commitWorkspace(workspaceFromActivation(activation), intent);
      } catch {
        if (isCurrentIntent(intent)) setError("会话所属工作目录暂时无法打开。 ");
        return undefined;
      }
    },
    [
      activateWorkspace,
      beginIntent,
      commitWorkspace,
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

  /** 首次 Runtime ready 只刷新项目 catalog；空白无项目状态不绑定任何 workspace root。 */
  useEffect(() => {
    if (
      !configurationReady ||
      runtimeState === undefined ||
      !["ready", "busy"].includes(runtimeState.status)
    )
      return;
    void refreshCatalog(intentRef.current, true);
  }, [configurationReady, refreshCatalog, runtimeState]);

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
    selectNoProject,
    select,
    retryCatalog,
    activateForConversation,
  };
}
