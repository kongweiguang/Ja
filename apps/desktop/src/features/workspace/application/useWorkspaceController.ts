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
  pick(initialDirectory?: string): Promise<string | null>;
}

/** workspace 切换前取得的释放函数必须幂等，供成功、失败和过期 intent 统一清理。 */
type WorkspaceChangeRelease = () => void;

/** 文件工作面先暂存关闭意图；只有 Java 确认目录更新后 commit 才丢弃旧绑定。 */
export interface WorkspaceRebindLease {
  commit(): void;
  release(): void;
}

/** 项目更新区分可安全重新编辑的 CAS 冲突和必须先回读的传输不确定态。 @author kongweiguang */
export class WorkspaceProjectUpdateError extends Error {
  readonly reason: "revision_conflict" | "result_unknown";
  readonly currentWorkspace?: WorkspaceProjection;

  /** 仅用成功的权威读回填 currentWorkspace；不把请求草稿伪装成服务端事实。 @author kongweiguang */
  constructor(
    reason: "revision_conflict" | "result_unknown",
    message: string,
    currentWorkspace?: WorkspaceProjection,
  ) {
    super(message);
    this.name = "WorkspaceProjectUpdateError";
    this.reason = reason;
    this.currentWorkspace = currentWorkspace;
  }
}

/**
 * 项目与会话目录之间的切换需要回收 workspace 级资源；两个 session 之间只切 active id，
 * 否则会误关后台 Thread 自己拥有的终端与预览。
 */
type BeforeWorkspaceChange = (
  previousWorkspaceId: string,
  nextWorkspaceId?: string,
) => Promise<WorkspaceChangeRelease | void>;

/** 将新式提交/释放 lease 与旧切换释放函数收敛成一种回收结果，避免误把路径写入提前提交。 */
async function acquireRebindLease(
  workspaceId: string,
  beforeWorkspaceRebind: WorkspaceControllerOptions["beforeWorkspaceRebind"],
  beforeWorkspaceChange: BeforeWorkspaceChange,
): Promise<WorkspaceRebindLease | undefined> {
  if (beforeWorkspaceRebind !== undefined) return beforeWorkspaceRebind(workspaceId);
  const release = await beforeWorkspaceChange(workspaceId, workspaceId);
  return release === undefined ? undefined : { commit: () => undefined, release };
}

interface WorkspaceControllerOptions {
  history: WorkspaceHistoryPort;
  picker: WorkspacePickerPort;
  activateWorkspace: (workspaceId: string) => Promise<WorkspaceActivationRecord>;
  /** 比较路径别名时只解析目录元数据；RPC 始终接收用户原始路径以保留服务端校验。 */
  resolveProjectDirectory?: (cwd: string) => Promise<string>;
  runtimeState: WorkspaceRuntimeState | undefined;
  configurationReady: boolean;
  beforeWorkspaceChange: BeforeWorkspaceChange;
  /** 修改已绑定项目目录前释放该 Workspace 下所有会话资源；同一身份也必须经过此门禁。 */
  beforeWorkspaceRebind?: (workspaceId: string) => Promise<WorkspaceRebindLease | undefined>;
  onWorkspaceCommitted: (workspace: WorkspaceProjection | undefined) => void;
}

export type WorkspaceDirectoryState = "unknown" | "checking" | "available" | "unavailable";

/** 进程重启会重置 generation，因此目录绑定身份必须同时包含 Java serverInstanceId。 */
function readyRuntimeKey(runtimeState: WorkspaceRuntimeState | undefined): string | undefined {
  if (
    runtimeState === undefined ||
    (runtimeState.status !== "ready" && runtimeState.status !== "busy") ||
    !Number.isSafeInteger(runtimeState.generation) ||
    runtimeState.generation < 1 ||
    typeof runtimeState.serverInstanceId !== "string" ||
    runtimeState.serverInstanceId.length === 0
  )
    return undefined;
  return JSON.stringify([runtimeState.serverInstanceId, runtimeState.generation]);
}

/** Windows cwd 比较时折叠扩展长度前缀和分隔符；物理解析仍由注入的原生端口完成。 */
function windowsPathKey(value: string): string {
  let normalized = value.trim().replaceAll("/", "\\");
  if (/^\\\\\?\\UNC\\/i.test(normalized)) normalized = `\\\\${normalized.slice(8)}`;
  else if (/^\\\\\?\\[a-z]:\\/i.test(normalized)) normalized = normalized.slice(4);
  return normalized.replace(/\\+$/, "").toLowerCase();
}

/** 失败闩锁只绑定项目目录和 Runtime 身份；名称/revision 变化不应重试一个仍失效的目录。 @author kongweiguang */
function projectBindingFailureKey(project: WorkspaceProjection, runtimeKey: string): string {
  return JSON.stringify([runtimeKey, project.workspaceId, windowsPathKey(project.rootPath)]);
}

/** workspace controller 的公开契约只包含 workspace 事实和 workspace 用例。 */
export interface WorkspaceController {
  workspace: WorkspaceProjection | undefined;
  /** 仅在当前 workspace 已由当前 ready Runtime identity 激活后为 true。 */
  bindingReady: boolean;
  projects: WorkspaceProjection[];
  catalogLoading: boolean;
  catalogError: string | undefined;
  busy: boolean;
  error: string | undefined;
  directoryState: WorkspaceDirectoryState;
  revision: number;
  choose(): Promise<void>;
  pickDirectory(initialDirectory?: string): Promise<string | null>;
  selectNoProject(): Promise<void>;
  select(workspaceId: string): Promise<void>;
  readProject(workspaceId: string): Promise<WorkspaceProjection>;
  updateProject(input: {
    workspaceId: string;
    expectedRevision: number;
    displayName?: string;
    cwd?: string;
  }): Promise<WorkspaceProjection>;
  recheckProjectBinding(workspaceId: string): Promise<WorkspaceProjection>;
  retryBinding(): Promise<void>;
  refreshProjectMetadata(workspaceId: string, revision: number): Promise<void>;
  setProjectTrust(
    workspaceId: string,
    trust: "trusted" | "untrusted",
  ): Promise<WorkspaceProjection>;
  retryCatalog(): Promise<void>;
  activateForConversation(workspaceId: string): Promise<WorkspaceProjection | undefined>;
}

interface WorkspacePreparation {
  release?: WorkspaceChangeRelease;
}

/**
 * 独占项目选择、active Host activation 与设置 scope 切换；无项目入口只清空当前选择，
 * 新建会话后再由 Java-issued id 激活专属目录，不为 blank state 绑定共享 cwd。
 * 项目 trust 是独立 mutation，必须回读服务端投影且不推进切换 revision 或资源 fence。
 */
export function useWorkspaceController({
  history,
  picker,
  activateWorkspace,
  resolveProjectDirectory,
  runtimeState,
  configurationReady,
  beforeWorkspaceChange,
  beforeWorkspaceRebind,
  onWorkspaceCommitted,
}: WorkspaceControllerOptions): WorkspaceController {
  const [workspace, setWorkspace] = useState<WorkspaceProjection>();
  const [projects, setProjects] = useState<WorkspaceProjection[]>([]);
  const [catalogLoading, setCatalogLoading] = useState(false);
  const [catalogError, setCatalogError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [directoryState, setDirectoryState] = useState<WorkspaceDirectoryState>("unknown");
  const [revision, setRevision] = useState(0);
  const [boundRuntimeKey, setBoundRuntimeKey] = useState<string>();
  const runtimeKey = readyRuntimeKey(runtimeState);
  const runtimeKeyRef = useRef(runtimeKey);
  runtimeKeyRef.current = runtimeKey;
  const bindingReady =
    workspace !== undefined && runtimeKey !== undefined && boundRuntimeKey === runtimeKey;
  const workspaceRef = useRef<WorkspaceProjection | undefined>(undefined);
  const retainedSessionWorkspaceRef = useRef<WorkspaceProjection | undefined>(undefined);
  const intentRef = useRef(0);
  const observedRuntimeKeyRef = useRef<string | undefined>(undefined);
  const failedProjectBindingRef = useRef(new Map<string, string>());
  const pendingConversationActivationRef = useRef<
    { request: number; runtimeKey: string | undefined } | undefined
  >(undefined);
  const catalogRequestRef = useRef(0);
  const trustMutationRef = useRef(new Map<string, symbol>());
  const projectMutationRef = useRef(new Map<string, symbol>());
  const uncertainProjectUpdatesRef = useRef(new Set<string>());
  const projectsNeedingBindingRef = useRef(new Set<string>());
  const metadataEventRef = useRef(new Map<string, number>());
  const deferredMetadataRevisionRef = useRef(new Map<string, number>());
  const metadataRefreshRef = useRef<
    ((workspaceId: string, revision: number) => Promise<void>) | undefined
  >(undefined);
  const mountedRef = useRef(false);

  /** 自动恢复在相同 Runtime/root 失败后保持静止；只有显式重试或身份变化才重新探测。 @author kongweiguang */
  const hasFailedProjectBinding = useCallback(
    (project: WorkspaceProjection, expectedRuntimeKey: string | undefined): boolean =>
      expectedRuntimeKey !== undefined &&
      failedProjectBindingRef.current.get(project.workspaceId) ===
        projectBindingFailureKey(project, expectedRuntimeKey),
    [],
  );

  /** 记住最后一次失败绑定，避免会话切换在缺失目录上形成 open/read 风暴。 @author kongweiguang */
  const rememberFailedProjectBinding = useCallback(
    (project: WorkspaceProjection, expectedRuntimeKey: string | undefined): void => {
      if (expectedRuntimeKey === undefined) return;
      failedProjectBindingRef.current.set(
        project.workspaceId,
        projectBindingFailureKey(project, expectedRuntimeKey),
      );
    },
    [],
  );

  /** 成功签发当前项目句柄后清除旧失败结果，使后续 Runtime 身份仍可独立恢复。 @author kongweiguang */
  const clearFailedProjectBinding = useCallback((workspaceId: string): void => {
    failedProjectBindingRef.current.delete(workspaceId);
  }, []);

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

  /** 逐页读取 App Server 的项目投影，让信任结果不依赖 command 的回显或本地推测。 */
  const readProjectProjection = useCallback(
    async (workspaceId: string): Promise<WorkspaceProjection> => {
      if (history.workspaceRead !== undefined) {
        const record = await history.workspaceRead({ workspaceId });
        if (record.workspaceId !== workspaceId || record.kind !== "project")
          throw new Error("project workspace identity mismatch");
        return workspaceFromHistory(record);
      }
      let cursor: string | undefined;
      const visitedCursors = new Set<string>();
      do {
        const listed = await history.workspaceList({ kind: "project", limit: 200, cursor });
        const record = listed.items.find((item) => item.workspaceId === workspaceId);
        if (record !== undefined) return workspaceFromHistory(record);
        const nextCursor = listed.nextCursor ?? undefined;
        if (nextCursor !== undefined && visitedCursors.has(nextCursor))
          throw new Error("workspace/list repeated a cursor");
        if (nextCursor !== undefined) visitedCursors.add(nextCursor);
        cursor = nextCursor;
      } while (cursor !== undefined);
      throw new Error("project workspace unavailable");
    },
    [history],
  );

  /** 只读取持久项目元数据，不激活或签发原生 Workspace 句柄，供失效目录仍可编辑使用。 */
  const readProject = useCallback(
    async (workspaceId: string): Promise<WorkspaceProjection> => readProjectProjection(workspaceId),
    [readProjectProjection],
  );

  /** 原生文件选择器只返回目录意图；取消时不影响调用方持有的编辑草稿。 */
  const pickDirectory = useCallback(
    (initialDirectory?: string): Promise<string | null> => picker.pick(initialDirectory),
    [picker],
  );

  /**
   * CAS 更新单个项目；只有 cwd 变化才冻结该项目资源并重新绑定，失败回读权威结果避免盲目重放。
   * 新 root 绑定失败后保留失败闩锁，避免后续会话激活把一次修复失败变成自动重试风暴。
   * @author kongweiguang
   */
  const updateProject = useCallback(
    async (input: {
      workspaceId: string;
      expectedRevision: number;
      displayName?: string;
      cwd?: string;
    }): Promise<WorkspaceProjection> => {
      if (history.workspaceUpdate === undefined) throw new Error("当前运行时不支持编辑项目。");
      if (uncertainProjectUpdatesRef.current.has(input.workspaceId))
        throw new WorkspaceProjectUpdateError(
          "result_unknown",
          "项目目录更新结果尚未核实，请先重新检查项目资料。",
        );
      if (projectMutationRef.current.has(input.workspaceId))
        throw new Error("项目正在保存，请稍后重试。");

      const mutationId = Symbol(input.workspaceId);
      projectMutationRef.current.set(input.workspaceId, mutationId);
      let rebindLease: WorkspaceRebindLease | undefined;
      let directoryChanged = false;
      let controlsBusyState = false;
      try {
        const initial =
          projects.find((project) => project.workspaceId === input.workspaceId) ??
          (workspaceRef.current?.workspaceId === input.workspaceId
            ? workspaceRef.current
            : await readProjectProjection(input.workspaceId));
        const requestedCwd = input.cwd ?? initial.rootPath;
        const requestedName = input.displayName ?? initial.displayName;
        const initialRawDirectoryKey = windowsPathKey(initial.rootPath);
        const requestedRawDirectoryKey = windowsPathKey(requestedCwd);
        let initialDirectoryKey = windowsPathKey(initial.rootPath);
        let requestedDirectoryKey = windowsPathKey(requestedCwd);
        directoryChanged = requestedDirectoryKey !== initialDirectoryKey;
        if (directoryChanged && resolveProjectDirectory !== undefined) {
          // 先确认目标是可解析目录并比较物理别名，再冻结资源；原始 cwd 仍交给 Java 做权威校验。
          requestedDirectoryKey = windowsPathKey(await resolveProjectDirectory(requestedCwd));
          try {
            initialDirectoryKey = windowsPathKey(await resolveProjectDirectory(initial.rootPath));
            directoryChanged = requestedDirectoryKey !== initialDirectoryKey;
          } catch {
            // 旧目录可能已删除；新目录已确认可用时允许修复已失效的项目绑定。
            directoryChanged = true;
          }
        }
        const isSelected = workspaceRef.current?.workspaceId === input.workspaceId;
        const expectedRuntimeKey = runtimeKeyRef.current;
        if (directoryChanged) {
          setBusy(true);
          controlsBusyState = true;
          rebindLease = await acquireRebindLease(
            input.workspaceId,
            beforeWorkspaceRebind,
            beforeWorkspaceChange,
          );
        }

        let updated: WorkspaceProjection;
        try {
          updated = workspaceFromHistory(
            await history.workspaceUpdate({
              workspaceId: input.workspaceId,
              expectedRevision: input.expectedRevision,
              ...(input.displayName === undefined ? {} : { displayName: requestedName }),
              ...(!directoryChanged ? {} : { cwd: requestedCwd }),
            }),
          );
        } catch (cause: unknown) {
          const actual = await readProjectProjection(input.workspaceId).catch(() => undefined);
          if (actual === undefined) {
            // 更新和权威回读都失败时禁止盲目重提，包括只改名称的请求。
            uncertainProjectUpdatesRef.current.add(input.workspaceId);
            projectsNeedingBindingRef.current.add(input.workspaceId);
            if (directoryChanged) {
              // root 结果未知时提交用户已确认的文件处置，避免旧 root 草稿或终端继续作为有效资源恢复。
              rememberFailedProjectBinding(initial, expectedRuntimeKey);
              rebindLease?.commit();
            }
            if (workspaceRef.current?.workspaceId === input.workspaceId) {
              // 立即封住新的本机 IO；保留 Thread 资源的保存/关闭决定留给用户显式复核。
              setBoundRuntimeKey(undefined);
              setDirectoryState("unavailable");
              setError("项目更新结果暂时无法确认，请重新检查项目资料后再继续。 ");
            }
            throw new WorkspaceProjectUpdateError(
              "result_unknown",
              "项目更新结果暂时无法确认，请重新检查项目资料。",
            );
          }
          const actualRevision = actual.revision;
          let actualRootChanged =
            windowsPathKey(actual.rootPath) !== windowsPathKey(initial.rootPath);
          if (actualRootChanged && resolveProjectDirectory !== undefined) {
            try {
              const [initialPhysicalRoot, actualPhysicalRoot] = await Promise.all([
                resolveProjectDirectory(initial.rootPath),
                resolveProjectDirectory(actual.rootPath),
              ]);
              actualRootChanged =
                windowsPathKey(initialPhysicalRoot) !== windowsPathKey(actualPhysicalRoot);
            } catch {
              // 旧 root 或新 root 缺失时无法证明它们是同一物理目录，必须保持关闭状态。
            }
          }
          if (typeof actualRevision === "number" && actualRevision > input.expectedRevision) {
            let actualDirectoryKey = windowsPathKey(actual.rootPath);
            let actualDirectoryResolved = false;
            if (directoryChanged && resolveProjectDirectory !== undefined) {
              try {
                actualDirectoryKey = windowsPathKey(await resolveProjectDirectory(actual.rootPath));
                actualDirectoryResolved = true;
              } catch {
                // 资料已落库后目录可能立即不可访问；保留 DB 路径作确定性回读，不重复提交。
                actualDirectoryKey = windowsPathKey(actual.rootPath);
              }
            }
            const rootMatchesRequest = directoryChanged
              ? actualDirectoryResolved
                ? actualDirectoryKey === requestedDirectoryKey
                : actualDirectoryKey === requestedDirectoryKey ||
                  actualDirectoryKey === requestedRawDirectoryKey
              : windowsPathKey(actual.rootPath) === initialRawDirectoryKey;
            // 写入回包可能丢失；名称和目标目录吻合才确认提交，未改目录时只依赖数据库事实。
            if (actual.displayName === requestedName && rootMatchesRequest) {
              updated = actual;
            } else {
              if (actualRootChanged) {
                // A competing CAS may already have revoked the native old-root handle; never leave the previous UI binding ready.
                uncertainProjectUpdatesRef.current.add(input.workspaceId);
                projectsNeedingBindingRef.current.add(input.workspaceId);
                rememberFailedProjectBinding(actual, expectedRuntimeKey);
                if (rebindLease === undefined) {
                  try {
                    rebindLease = await acquireRebindLease(
                      input.workspaceId,
                      beforeWorkspaceRebind,
                      beforeWorkspaceChange,
                    );
                  } catch {
                    // Keep the project fenced; explicit recheck retries resource cleanup before opening the authoritative root.
                  }
                }
                rebindLease?.commit();
                setProjects((current) => {
                  const index = current.findIndex(
                    (project) => project.workspaceId === input.workspaceId,
                  );
                  if (index < 0) return [actual, ...current];
                  const next = [...current];
                  next[index] = actual;
                  return next;
                });
                if (workspaceRef.current?.workspaceId === input.workspaceId) {
                  workspaceRef.current = actual;
                  setWorkspace(actual);
                  setBoundRuntimeKey(undefined);
                  setDirectoryState("unavailable");
                  setError("项目目录已由其他修改更新，请先重新检查并恢复绑定。 ");
                }
              }
              throw new WorkspaceProjectUpdateError(
                "revision_conflict",
                "项目资料已被其他修改，请先重新检查最新资料，核对后保存。",
                actual,
              );
            }
          } else if (actualRevision !== input.expectedRevision) {
            throw new WorkspaceProjectUpdateError(
              "revision_conflict",
              "项目资料已被其他修改，请先重新检查最新资料，核对后保存。",
              actual,
            );
          } else if (
            directoryChanged &&
            cause !== null &&
            typeof cause === "object" &&
            "code" in cause &&
            cause.code === "CONFLICT"
          )
            throw new Error("该目录已被另一个项目占用，请选择其他目录。");
          else throw cause;
        }
        if (updated.workspaceId !== input.workspaceId || updated.kind !== "project")
          throw new Error("项目更新返回了不匹配的身份。");
        metadataEventRef.current.set(
          input.workspaceId,
          Math.max(metadataEventRef.current.get(input.workspaceId) ?? -1, updated.revision ?? -1),
        );
        const persistedDirectoryChanged =
          windowsPathKey(updated.rootPath) !== windowsPathKey(initial.rootPath);
        if (directoryChanged && persistedDirectoryChanged) rebindLease?.commit();
        directoryChanged = persistedDirectoryChanged;

        const replaceProject =
          (projection: WorkspaceProjection) =>
          (current: WorkspaceProjection[]): WorkspaceProjection[] => {
            const index = current.findIndex(
              (project) => project.workspaceId === projection.workspaceId,
            );
            if (index < 0) return [projection, ...current];
            const next = [...current];
            next[index] = projection;
            return next;
          };
        setProjects(replaceProject(updated));

        if (isSelected && workspaceRef.current?.workspaceId === input.workspaceId) {
          workspaceRef.current = updated;
          setWorkspace(updated);
          if (directoryChanged) {
            // A new root is not a usable runtime binding until workspaceOpen confirms it; publishing earlier can cache old-root settings under the new root key.
            setBoundRuntimeKey(undefined);
            setDirectoryState("checking");
          } else onWorkspaceCommitted(updated);
        }

        if (directoryChanged && !isSelected) {
          // A successful root update installed the candidate capability even though this project is not active in the renderer.
          uncertainProjectUpdatesRef.current.delete(input.workspaceId);
          projectsNeedingBindingRef.current.delete(input.workspaceId);
        }
        if (!directoryChanged || !isSelected) return updated;

        projectsNeedingBindingRef.current.add(input.workspaceId);

        try {
          const activation =
            history.workspaceOpen === undefined
              ? workspaceFromActivation(await activateWorkspace(input.workspaceId))
              : workspaceFromHistory(
                  await history.workspaceOpen({ workspaceId: input.workspaceId }),
                );
          if (
            activation.workspaceId !== input.workspaceId ||
            activation.kind !== "project" ||
            windowsPathKey(activation.rootPath) !== windowsPathKey(updated.rootPath)
          )
            throw new Error("新项目目录绑定身份不匹配。");
          uncertainProjectUpdatesRef.current.delete(input.workspaceId);
          projectsNeedingBindingRef.current.delete(input.workspaceId);
          if (
            projectMutationRef.current.get(input.workspaceId) !== mutationId ||
            runtimeKeyRef.current !== expectedRuntimeKey ||
            workspaceRef.current?.workspaceId !== input.workspaceId
          )
            return updated;
          const rebound: WorkspaceProjection = {
            ...activation,
            revision: activation.revision ?? updated.revision,
          };
          clearFailedProjectBinding(input.workspaceId);
          workspaceRef.current = rebound;
          setWorkspace(rebound);
          setProjects(replaceProject(rebound));
          setBoundRuntimeKey(expectedRuntimeKey);
          setDirectoryState("available");
          setError(undefined);
          onWorkspaceCommitted(rebound);
        } catch {
          if (
            projectMutationRef.current.get(input.workspaceId) === mutationId &&
            runtimeKeyRef.current === expectedRuntimeKey &&
            workspaceRef.current?.workspaceId === input.workspaceId
          ) {
            rememberFailedProjectBinding(updated, expectedRuntimeKey);
            setBoundRuntimeKey(undefined);
            setDirectoryState("unavailable");
            setError("项目目录已保存，但工作区未能重新绑定。可稍后重试。");
          }
        }
        return updated;
      } finally {
        rebindLease?.release();
        if (controlsBusyState && mountedRef.current) setBusy(false);
        if (projectMutationRef.current.get(input.workspaceId) === mutationId)
          projectMutationRef.current.delete(input.workspaceId);
        const deferredRevision = deferredMetadataRevisionRef.current.get(input.workspaceId);
        if (deferredRevision !== undefined) {
          deferredMetadataRevisionRef.current.delete(input.workspaceId);
          if ((metadataEventRef.current.get(input.workspaceId) ?? -1) < deferredRevision)
            queueMicrotask(() => {
              void metadataRefreshRef.current?.(input.workspaceId, deferredRevision);
            });
        }
      }
    },
    [
      activateWorkspace,
      beforeWorkspaceChange,
      beforeWorkspaceRebind,
      clearFailedProjectBinding,
      history,
      onWorkspaceCommitted,
      projects,
      readProjectProjection,
      rememberFailedProjectBinding,
      resolveProjectDirectory,
    ],
  );

  /** 显式回读权威项目；同 root 且绑定可用时只刷新 metadata，未知或换 root 时先关闭该项目全部 Thread 资源。 @author kongweiguang */
  const recheckProjectBinding = useCallback(
    async (workspaceId: string): Promise<WorkspaceProjection> => {
      if (projectMutationRef.current.has(workspaceId))
        throw new Error("项目正在保存，请稍后重试。");
      const mutationId = Symbol(`workspace-recheck:${workspaceId}`);
      projectMutationRef.current.set(workspaceId, mutationId);
      const expectedRuntimeKey = runtimeKeyRef.current;
      /** 复核以项目 mutation 和 Runtime 为边界；同项目 Thread 导航不能留下永久 checking。 @author kongweiguang */
      const ownsRecheck = (): boolean =>
        mountedRef.current &&
        projectMutationRef.current.get(workspaceId) === mutationId &&
        runtimeKeyRef.current === expectedRuntimeKey;
      const selectedAtStart = workspaceRef.current?.workspaceId === workspaceId;
      const previous =
        projects.find((project) => project.workspaceId === workspaceId) ??
        (selectedAtStart ? workspaceRef.current : undefined);
      const uncertain = uncertainProjectUpdatesRef.current.has(workspaceId);
      const needsBinding = projectsNeedingBindingRef.current.has(workspaceId);
      let currentProjection: WorkspaceProjection | undefined;
      let sameRoot = previous === undefined;
      let rebindLease: WorkspaceRebindLease | undefined;
      // 当前项目的复核从入口同步进入 busy，避免调用方在首个 authority read 尚未完成时误判操作已结束。
      let controlsBusyState = selectedAtStart;
      if (controlsBusyState) setBusy(true);

      try {
        currentProjection = await readProjectProjection(workspaceId);
        if (!ownsRecheck()) return currentProjection;
        if (previous !== undefined) {
          sameRoot =
            windowsPathKey(previous.rootPath) === windowsPathKey(currentProjection.rootPath);
          if (!sameRoot && resolveProjectDirectory !== undefined) {
            try {
              const [oldRoot, newRoot] = await Promise.all([
                resolveProjectDirectory(previous.rootPath),
                resolveProjectDirectory(currentProjection.rootPath),
              ]);
              sameRoot = windowsPathKey(oldRoot) === windowsPathKey(newRoot);
            } catch {
              sameRoot = false;
            }
          }
        }

        const needsResourceCleanup = uncertain || needsBinding || !sameRoot;
        const selectedNow = workspaceRef.current?.workspaceId === workspaceId;
        if (!selectedNow) {
          if (needsResourceCleanup) {
            controlsBusyState = true;
            setBusy(true);
            rebindLease = await acquireRebindLease(
              workspaceId,
              beforeWorkspaceRebind,
              beforeWorkspaceChange,
            );
            if (!ownsRecheck()) return currentProjection;
            // 权威 root 已变化或更新结果未知时，不得把旧草稿当作任一 root 下仍可继续的资源。
            if (needsResourceCleanup) rebindLease?.commit();
          }
          setProjects((current) => {
            const index = current.findIndex((project) => project.workspaceId === workspaceId);
            if (index < 0) return [currentProjection!, ...current];
            const next = [...current];
            next[index] = currentProjection!;
            return next;
          });
          if (needsResourceCleanup) {
            uncertainProjectUpdatesRef.current.delete(workspaceId);
            projectsNeedingBindingRef.current.add(workspaceId);
            clearFailedProjectBinding(workspaceId);
          }
          return currentProjection;
        }

        if (!needsResourceCleanup && bindingReady) {
          // 只改名称或 revision 时保留同 root 的有效句柄和运行中的 Thread 资源。
          workspaceRef.current = currentProjection;
          setWorkspace(currentProjection);
          setProjects((current) => {
            const index = current.findIndex((project) => project.workspaceId === workspaceId);
            if (index < 0) return [currentProjection!, ...current];
            const next = [...current];
            next[index] = currentProjection!;
            return next;
          });
          onWorkspaceCommitted(currentProjection);
          return currentProjection;
        }

        if (expectedRuntimeKey === undefined)
          throw new Error("当前运行时尚未就绪，请稍后重新检查项目目录。");
        controlsBusyState = true;
        setBusy(true);
        setBoundRuntimeKey(undefined);
        setDirectoryState("checking");
        setError(undefined);
        workspaceRef.current = currentProjection;
        setWorkspace(currentProjection);
        setProjects((current) => {
          const index = current.findIndex((project) => project.workspaceId === workspaceId);
          if (index < 0) return [currentProjection!, ...current];
          const next = [...current];
          next[index] = currentProjection!;
          return next;
        });

        if (needsResourceCleanup) {
          rebindLease = await acquireRebindLease(
            workspaceId,
            beforeWorkspaceRebind,
            beforeWorkspaceChange,
          );
          if (!ownsRecheck() || workspaceRef.current?.workspaceId !== workspaceId)
            return currentProjection;
          // 只有 authority root 不明或确实变化时才排空 Thread 资源；普通失效目录只尝试 ID-open。
          rebindLease?.commit();
          uncertainProjectUpdatesRef.current.delete(workspaceId);
          projectsNeedingBindingRef.current.add(workspaceId);
        }
        if (!ownsRecheck() || workspaceRef.current?.workspaceId !== workspaceId)
          return currentProjection;

        const activation =
          history.workspaceOpen === undefined
            ? workspaceFromActivation(await activateWorkspace(workspaceId))
            : workspaceFromHistory(await history.workspaceOpen({ workspaceId }));
        if (activation.workspaceId !== workspaceId || activation.kind !== "project")
          throw new Error("项目目录重新绑定身份不匹配。");
        uncertainProjectUpdatesRef.current.delete(workspaceId);
        projectsNeedingBindingRef.current.delete(workspaceId);
        clearFailedProjectBinding(workspaceId);
        const rebound: WorkspaceProjection = {
          ...activation,
          revision: activation.revision ?? currentProjection.revision,
        };
        if (ownsRecheck() && workspaceRef.current?.workspaceId === workspaceId) {
          workspaceRef.current = rebound;
          setWorkspace(rebound);
          setProjects((current) => {
            const index = current.findIndex((project) => project.workspaceId === workspaceId);
            if (index < 0) return [rebound, ...current];
            const next = [...current];
            next[index] = rebound;
            return next;
          });
          setBoundRuntimeKey(expectedRuntimeKey);
          setDirectoryState("available");
          setError(undefined);
          onWorkspaceCommitted(rebound);
        }
        return rebound;
      } catch (cause: unknown) {
        if (ownsRecheck() && workspaceRef.current?.workspaceId === workspaceId) {
          if (currentProjection !== undefined)
            rememberFailedProjectBinding(currentProjection, expectedRuntimeKey);
          setBoundRuntimeKey(undefined);
          setDirectoryState("unavailable");
          setError("项目目录尚未重新绑定，请检查目录并重试。");
        }
        throw cause;
      } finally {
        rebindLease?.release();
        if (controlsBusyState && mountedRef.current) setBusy(false);
        if (projectMutationRef.current.get(workspaceId) === mutationId)
          projectMutationRef.current.delete(workspaceId);
        const deferredRevision = deferredMetadataRevisionRef.current.get(workspaceId);
        if (deferredRevision !== undefined) {
          deferredMetadataRevisionRef.current.delete(workspaceId);
          if ((metadataEventRef.current.get(workspaceId) ?? -1) < deferredRevision)
            queueMicrotask(() => {
              void metadataRefreshRef.current?.(workspaceId, deferredRevision);
            });
        }
      }
    },
    [
      activateWorkspace,
      beforeWorkspaceChange,
      beforeWorkspaceRebind,
      bindingReady,
      clearFailedProjectBinding,
      history,
      onWorkspaceCommitted,
      projects,
      readProjectProjection,
      rememberFailedProjectBinding,
      resolveProjectDirectory,
    ],
  );

  /** 元数据事件只作失效提示，回读 Workspace 投影后才推进本地 revision 与目录绑定；绑定失败同 root 暂停自动恢复。 @author kongweiguang */
  const refreshProjectMetadata = useCallback(
    async (workspaceId: string, eventRevision: number): Promise<void> => {
      if (!Number.isSafeInteger(eventRevision) || eventRevision < 0) return;
      if ((metadataEventRef.current.get(workspaceId) ?? -1) >= eventRevision) return;
      if (projectMutationRef.current.has(workspaceId)) {
        deferredMetadataRevisionRef.current.set(
          workspaceId,
          Math.max(deferredMetadataRevisionRef.current.get(workspaceId) ?? -1, eventRevision),
        );
        const latest = await readProjectProjection(workspaceId).catch(() => undefined);
        if (
          latest !== undefined &&
          workspaceRef.current?.workspaceId === workspaceId &&
          windowsPathKey(workspaceRef.current.rootPath) !== windowsPathKey(latest.rootPath)
        ) {
          // Server 已提交新 root 时立即撤销旧目录能力；本地 mutation 结束后再统一处理资源关闭和重绑。
          setBoundRuntimeKey(undefined);
          setDirectoryState("unavailable");
          workspaceRef.current = latest;
          setWorkspace(latest);
          setProjects((current) => {
            const index = current.findIndex((project) => project.workspaceId === workspaceId);
            if (index < 0) return [latest, ...current];
            const next = [...current];
            next[index] = latest;
            return next;
          });
        }
        return;
      }
      metadataEventRef.current.set(workspaceId, eventRevision);
      const requestRevision = eventRevision;
      let rebindLease: WorkspaceRebindLease | undefined;
      const previous =
        projects.find((project) => project.workspaceId === workspaceId) ??
        (workspaceRef.current?.workspaceId === workspaceId ? workspaceRef.current : undefined);
      const selectedAtStart = workspaceRef.current?.workspaceId === workspaceId;
      const expectedRuntimeKey = runtimeKeyRef.current;
      let controlsBusyState = false;
      let directoryChanged = false;

      try {
        const current = await readProjectProjection(workspaceId);
        if (
          metadataEventRef.current.get(workspaceId) !== requestRevision ||
          (current.revision ?? -1) < eventRevision ||
          (previous?.revision ?? -1) >= (current.revision ?? -1)
        )
          return;
        directoryChanged =
          previous !== undefined &&
          windowsPathKey(previous.rootPath) !== windowsPathKey(current.rootPath);
        if (directoryChanged) {
          if (selectedAtStart && workspaceRef.current?.workspaceId === workspaceId) {
            // 服务端已提交新 root，先撤销旧目录能力，再统一处理资源关闭和重新绑定。
            setBoundRuntimeKey(undefined);
            setDirectoryState("unavailable");
            workspaceRef.current = current;
            setWorkspace(current);
            setProjects((items) => {
              const index = items.findIndex((item) => item.workspaceId === workspaceId);
              if (index < 0) return [current, ...items];
              const next = [...items];
              next[index] = current;
              return next;
            });
          }
          setBusy(true);
          controlsBusyState = true;
          rebindLease = await acquireRebindLease(
            workspaceId,
            beforeWorkspaceRebind,
            beforeWorkspaceChange,
          );
          rebindLease?.commit();
        }
        const replace = (items: WorkspaceProjection[]): WorkspaceProjection[] => {
          const index = items.findIndex((item) => item.workspaceId === workspaceId);
          if (index < 0) return [current, ...items];
          const next = [...items];
          next[index] = current;
          return next;
        };
        setProjects(replace);
        if (!selectedAtStart || workspaceRef.current?.workspaceId !== workspaceId) return;
        workspaceRef.current = current;
        setWorkspace(current);
        if (!directoryChanged) {
          onWorkspaceCommitted(current);
          return;
        }

        setBoundRuntimeKey(undefined);
        setDirectoryState("checking");
        try {
          const activation =
            history.workspaceOpen === undefined
              ? workspaceFromActivation(await activateWorkspace(workspaceId))
              : workspaceFromHistory(await history.workspaceOpen({ workspaceId }));
          if (
            activation.workspaceId !== workspaceId ||
            activation.kind !== "project" ||
            windowsPathKey(activation.rootPath) !== windowsPathKey(current.rootPath)
          )
            throw new Error("workspace metadata activation mismatch");
          if (
            metadataEventRef.current.get(workspaceId) !== requestRevision ||
            runtimeKeyRef.current !== expectedRuntimeKey ||
            workspaceRef.current?.workspaceId !== workspaceId
          )
            return;
          const rebound: WorkspaceProjection = {
            ...current,
            rootPath: activation.rootPath,
            trust: activation.trust,
          };
          workspaceRef.current = rebound;
          clearFailedProjectBinding(workspaceId);
          setWorkspace(rebound);
          setProjects((items) => {
            const index = items.findIndex((item) => item.workspaceId === workspaceId);
            if (index < 0) return [rebound, ...items];
            const next = [...items];
            next[index] = rebound;
            return next;
          });
          setBoundRuntimeKey(expectedRuntimeKey);
          setDirectoryState("available");
          setError(undefined);
          onWorkspaceCommitted(rebound);
        } catch {
          if (
            metadataEventRef.current.get(workspaceId) === requestRevision &&
            runtimeKeyRef.current === expectedRuntimeKey &&
            workspaceRef.current?.workspaceId === workspaceId
          ) {
            rememberFailedProjectBinding(current, expectedRuntimeKey);
            setDirectoryState("unavailable");
            setError("项目目录不可用。可编辑项目目录后重新绑定，或重试检查。");
          }
        }
      } catch {
        if (metadataEventRef.current.get(workspaceId) === requestRevision) {
          const activeProject = workspaceRef.current;
          if (directoryChanged && selectedAtStart && activeProject?.workspaceId === workspaceId) {
            rememberFailedProjectBinding(activeProject, expectedRuntimeKey);
            setBoundRuntimeKey(undefined);
            setDirectoryState("unavailable");
            setError("项目目录已更新，但旧目录资源尚未释放。处理未保存内容后可重新检查绑定。");
          } else setError("项目资料暂时无法读取，请重试。");
        }
      } finally {
        rebindLease?.release();
        if (controlsBusyState && mountedRef.current) setBusy(false);
      }
    },
    [
      activateWorkspace,
      beforeWorkspaceChange,
      beforeWorkspaceRebind,
      clearFailedProjectBinding,
      history,
      onWorkspaceCommitted,
      projects,
      readProjectProjection,
      rememberFailedProjectBinding,
    ],
  );
  metadataRefreshRef.current = refreshProjectMetadata;

  /** 改写同一项目的目录投影而不推进 workspace revision、资源 fence 或对话 scope。 */
  const commitProjectTrustProjection = useCallback((project: WorkspaceProjection): void => {
    setProjects((current) => {
      const index = current.findIndex((candidate) => candidate.workspaceId === project.workspaceId);
      if (index < 0) return [project, ...current];
      const next = [...current];
      next[index] = project;
      return next;
    });
    if (workspaceRef.current?.workspaceId === project.workspaceId) {
      workspaceRef.current = project;
      setWorkspace(project);
    }
  }, []);

  /** 信任写入可能在传输失败前已落库，因此总是按固定项目 ID 回读实际状态再决定结果。 */
  const setProjectTrust = useCallback(
    async (workspaceId: string, trust: "trusted" | "untrusted"): Promise<WorkspaceProjection> => {
      const mutationId = Symbol(workspaceId);
      trustMutationRef.current.set(workspaceId, mutationId);
      // 使已经开始的目录刷新失效，避免旧信任值在回读之后覆盖本次权威投影。
      catalogRequestRef.current += 1;
      try {
        let accepted = false;
        let mutationFailure: unknown;
        try {
          const result = await history.workspaceSetTrust({ workspaceId, trust });
          accepted = result.accepted === true;
          if (!accepted) mutationFailure = new Error("workspace trust not accepted");
        } catch (error: unknown) {
          mutationFailure = error;
        }

        let project: WorkspaceProjection;
        try {
          project = await readProjectProjection(workspaceId);
        } catch (readFailure: unknown) {
          if (!accepted && mutationFailure !== undefined) throw mutationFailure;
          throw readFailure;
        }
        if (mountedRef.current && trustMutationRef.current.get(workspaceId) === mutationId)
          commitProjectTrustProjection(project);
        if (!accepted) throw mutationFailure ?? new Error("workspace trust not accepted");
        if (project.trust !== trust) throw new Error("workspace trust projection mismatch");
        return project;
      } finally {
        catalogRequestRef.current += 1;
        if (mountedRef.current) setCatalogLoading(false);
        if (trustMutationRef.current.get(workspaceId) === mutationId)
          trustMutationRef.current.delete(workspaceId);
      }
    },
    [commitProjectTrustProjection, history, readProjectProjection],
  );

  /**
   * Session→session 与 session→blank 都不申请资源 fence；跨项目 fence 只作用于当前 Runtime
   * 已确认绑定的 native resources，不能尝试 flush 已因 Runtime 重启而失效的旧 binding。
   */
  const prepareChange = useCallback(
    async (
      nextKind: WorkspaceKind | undefined,
      nextWorkspaceId?: string,
    ): Promise<WorkspacePreparation> => {
      const previous = workspaceRef.current ?? retainedSessionWorkspaceRef.current;
      if (previous === undefined) return {};
      if (runtimeKeyRef.current === undefined || boundRuntimeKey !== runtimeKeyRef.current)
        return {};
      if (previous.kind === "session" && (nextKind === "session" || nextKind === undefined))
        return {};
      if (workspaceRef.current === undefined && nextKind !== "project") return {};
      const release =
        (await beforeWorkspaceChange(previous.workspaceId, nextWorkspaceId)) ?? undefined;
      return { release };
    },
    [beforeWorkspaceChange, boundRuntimeKey],
  );

  /**
   * 提交已由 native 校验的 workspace projection；同一 session family 内不释放资源，
   * 项目边界则先完成 fence，并只向 Settings 投影 project scope。成功绑定同时清理同项目失败闩锁。
   * @author kongweiguang
   */
  const commitWorkspace = useCallback(
    async (
      selected: WorkspaceProjection,
      intent: number,
      preparation?: WorkspacePreparation,
      expectedRuntimeKey = runtimeKeyRef.current,
      nativeBindingReady = true,
    ): Promise<WorkspaceProjection | undefined> => {
      let release = preparation?.release;
      let replacementStarted = false;
      setBusy(true);
      setError(undefined);
      try {
        if (
          workspaceRef.current !== undefined &&
          preparation === undefined &&
          expectedRuntimeKey !== undefined &&
          boundRuntimeKey === expectedRuntimeKey
        ) {
          const previous = workspaceRef.current;
          if (!(previous.kind === "session" && selected.kind === "session")) {
            release =
              (await beforeWorkspaceChange(previous.workspaceId, selected.workspaceId)) ??
              undefined;
            if (!isCurrentIntent(intent) || runtimeKeyRef.current !== expectedRuntimeKey)
              return undefined;
          }
        } else if (
          workspaceRef.current === undefined &&
          preparation === undefined &&
          expectedRuntimeKey !== undefined &&
          boundRuntimeKey === expectedRuntimeKey
        ) {
          const retained = retainedSessionWorkspaceRef.current;
          if (retained !== undefined && selected.kind === "project") {
            release =
              (await beforeWorkspaceChange(retained.workspaceId, selected.workspaceId)) ??
              undefined;
            if (!isCurrentIntent(intent) || runtimeKeyRef.current !== expectedRuntimeKey)
              return undefined;
          }
        }
        replacementStarted = true;
        workspaceRef.current = undefined;
        setWorkspace(undefined);
        workspaceRef.current = selected;
        setBoundRuntimeKey(nativeBindingReady ? expectedRuntimeKey : undefined);
        if (selected.kind === "project" && nativeBindingReady)
          clearFailedProjectBinding(selected.workspaceId);
        if (selected.kind === "session") retainedSessionWorkspaceRef.current = selected;
        else if (selected.kind === "project") retainedSessionWorkspaceRef.current = undefined;
        setDirectoryState(
          selected.kind === "project"
            ? nativeBindingReady
              ? "available"
              : "unavailable"
            : "unknown",
        );
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
    [
      beforeWorkspaceChange,
      boundRuntimeKey,
      clearFailedProjectBinding,
      isCurrentIntent,
      onWorkspaceCommitted,
      refreshCatalog,
    ],
  );

  /** 项目目录使用权威 cwd 重开；失效绑定在同一目录/Runtime 中复用数据库投影，成功回包须过 fence 才能清失败闩锁。 @author kongweiguang */
  const openPersisted = useCallback(
    async (
      selected: WorkspaceProjection,
      intent: number,
      expectedRuntimeKey = runtimeKeyRef.current,
    ): Promise<WorkspaceProjection | undefined> => {
      if (selected.kind === "project") {
        const persisted = await readProjectProjection(selected.workspaceId);
        if (!isCurrentIntent(intent) || runtimeKeyRef.current !== expectedRuntimeKey)
          return undefined;
        const preparation = await prepareChange(selected.kind, selected.workspaceId);
        let transferred = false;
        let rebindLease: WorkspaceRebindLease | undefined;
        try {
          if (!isCurrentIntent(intent) || runtimeKeyRef.current !== expectedRuntimeKey)
            return undefined;
          const uncertain = uncertainProjectUpdatesRef.current.has(selected.workspaceId);
          const requiresBindingRecovery =
            uncertain || projectsNeedingBindingRef.current.has(selected.workspaceId);
          if (requiresBindingRecovery) {
            rebindLease = await acquireRebindLease(
              selected.workspaceId,
              beforeWorkspaceRebind,
              beforeWorkspaceChange,
            );
            if (!isCurrentIntent(intent) || runtimeKeyRef.current !== expectedRuntimeKey)
              return undefined;
            // An explicit project selection may clear an unknown native fence only after all retained Threads discard old-root resources.
            rebindLease?.commit();
            uncertainProjectUpdatesRef.current.delete(selected.workspaceId);
            projectsNeedingBindingRef.current.add(selected.workspaceId);
          }
          let activation: WorkspaceProjection | undefined;
          const bindingAlreadyFailed =
            !requiresBindingRecovery && hasFailedProjectBinding(persisted, expectedRuntimeKey);
          if (!bindingAlreadyFailed) {
            try {
              activation =
                history.workspaceOpen === undefined
                  ? workspaceFromActivation(await activateWorkspace(selected.workspaceId))
                  : workspaceFromHistory(
                      await history.workspaceOpen({ workspaceId: selected.workspaceId }),
                    );
            } catch {
              activation = undefined;
            }
          }
          if (!isCurrentIntent(intent) || runtimeKeyRef.current !== expectedRuntimeKey)
            return undefined;
          if (
            activation !== undefined &&
            (activation.workspaceId !== selected.workspaceId || activation.kind !== "project")
          )
            throw new Error("workspace activation identity changed");
          transferred = true;
          if (activation !== undefined && requiresBindingRecovery) {
            uncertainProjectUpdatesRef.current.delete(selected.workspaceId);
            projectsNeedingBindingRef.current.delete(selected.workspaceId);
          }
          const committed = await commitWorkspace(
            activation ?? persisted,
            intent,
            preparation,
            expectedRuntimeKey,
            activation !== undefined,
          );
          if (activation === undefined && !bindingAlreadyFailed && committed !== undefined)
            rememberFailedProjectBinding(persisted, expectedRuntimeKey);
          if (activation === undefined && committed !== undefined)
            setError("项目目录不可用。可编辑项目目录后重新绑定，或重试检查。");
          return committed;
        } finally {
          rebindLease?.release();
          if (!transferred) preparation.release?.();
        }
      }

      const activation = await activateWorkspace(selected.workspaceId);
      if (
        !isCurrentIntent(intent) ||
        runtimeKeyRef.current !== expectedRuntimeKey ||
        activation.workspaceId !== selected.workspaceId
      )
        return undefined;
      return commitWorkspace(
        workspaceFromActivation(activation),
        intent,
        undefined,
        expectedRuntimeKey,
      );
    },
    [
      activateWorkspace,
      beforeWorkspaceChange,
      beforeWorkspaceRebind,
      commitWorkspace,
      hasFailedProjectBinding,
      history,
      isCurrentIntent,
      prepareChange,
      readProjectProjection,
      rememberFailedProjectBinding,
    ],
  );

  /** 按 Java project ID 恢复目录；同一失败绑定只读历史投影，不因 Thread 激活反复探测目录。 @author kongweiguang */
  const restoreCurrentBinding = useCallback(
    async (
      expectedRuntimeKey: string,
      intent: number,
    ): Promise<WorkspaceProjection | undefined> => {
      const selected = workspaceRef.current;
      if (selected === undefined) return undefined;
      if (
        selected.kind === "project" &&
        (hasFailedProjectBinding(selected, expectedRuntimeKey) ||
          projectsNeedingBindingRef.current.has(selected.workspaceId))
      )
        return selected;
      setBusy(true);
      setError(undefined);
      try {
        let restored: WorkspaceProjection;
        let activationFailed = false;
        if (selected.kind === "project") {
          restored = await readProjectProjection(selected.workspaceId);
          try {
            const activation =
              history.workspaceOpen === undefined
                ? workspaceFromActivation(await activateWorkspace(selected.workspaceId))
                : workspaceFromHistory(
                    await history.workspaceOpen({ workspaceId: selected.workspaceId }),
                  );
            if (activation.workspaceId !== selected.workspaceId || activation.kind !== "project")
              throw new Error("workspace activation identity changed");
            restored = activation;
          } catch {
            activationFailed = true;
          }
        } else {
          restored = workspaceFromActivation(await activateWorkspace(selected.workspaceId));
        }
        if (restored.workspaceId !== selected.workspaceId || restored.kind !== selected.kind)
          throw new Error("workspace activation identity changed");
        if (
          !isCurrentIntent(intent) ||
          runtimeKeyRef.current !== expectedRuntimeKey ||
          workspaceRef.current?.workspaceId !== selected.workspaceId
        )
          return undefined;

        if (restored.kind === "project") {
          if (activationFailed) rememberFailedProjectBinding(restored, expectedRuntimeKey);
          else clearFailedProjectBinding(restored.workspaceId);
        }
        workspaceRef.current = restored;
        setWorkspace(restored);
        setBoundRuntimeKey(activationFailed ? undefined : expectedRuntimeKey);
        setDirectoryState(
          restored.kind === "project"
            ? activationFailed
              ? "unavailable"
              : "available"
            : "unknown",
        );
        setError(
          activationFailed ? "项目目录不可用。可编辑项目目录后重新绑定，或重试检查。" : undefined,
        );
        setCatalogError(undefined);
        if (restored.kind === "project") {
          setProjects((current) => {
            const index = current.findIndex(
              (candidate) => candidate.workspaceId === restored.workspaceId,
            );
            if (index < 0) return [restored, ...current];
            const next = [...current];
            next[index] = restored;
            return next;
          });
          if (!activationFailed) onWorkspaceCommitted(restored);
        }
        // Project identity suffices for thread/read; the separate binding flag keeps native use closed.
        return restored;
      } catch {
        if (
          isCurrentIntent(intent) &&
          runtimeKeyRef.current === expectedRuntimeKey &&
          workspaceRef.current?.workspaceId === selected.workspaceId
        ) {
          if (selected.kind === "project")
            rememberFailedProjectBinding(selected, expectedRuntimeKey);
          const message = "当前工作目录尚未恢复，请重试。";
          setError(message);
          if (selected.kind !== "project") setCatalogError(message);
          else setDirectoryState("unavailable");
        }
        return undefined;
      } finally {
        if (isCurrentIntent(intent)) setBusy(false);
      }
    },
    [
      activateWorkspace,
      clearFailedProjectBinding,
      hasFailedProjectBinding,
      history,
      isCurrentIntent,
      onWorkspaceCommitted,
      readProjectProjection,
      rememberFailedProjectBinding,
    ],
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
      const expectedRuntimeKey = runtimeKeyRef.current;
      const opened = await history.workspaceOpen({ cwd: rootPath });
      if (!isCurrentIntent(intent) || runtimeKeyRef.current !== expectedRuntimeKey) return;
      transferred = true;
      await commitWorkspace(workspaceFromHistory(opened), intent, preparation, expectedRuntimeKey);
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

  /** 项目选择按登记 ID 与 Runtime 请求栅栏准入；Settings 读取失败不能阻断历史和项目切换。 */
  const select = useCallback(
    async (workspaceId: string): Promise<void> => {
      if (workspaceRef.current?.workspaceId === workspaceId) {
        if (bindingReady) return;
        const currentRuntimeKey = runtimeKeyRef.current;
        if (currentRuntimeKey === undefined) return;
        const intent = beginIntent();
        await restoreCurrentBinding(currentRuntimeKey, intent);
        return;
      }
      const selected = projects.find((candidate) => candidate.workspaceId === workspaceId);
      if (selected === undefined) return;
      const intent = beginIntent();
      const expectedRuntimeKey = runtimeKeyRef.current;
      setBusy(true);
      setError(undefined);
      try {
        const opened = await openPersisted(selected, intent, expectedRuntimeKey);
        if (
          opened === undefined &&
          isCurrentIntent(intent) &&
          runtimeKeyRef.current === expectedRuntimeKey
        )
          setError("项目未能打开，请检查目录和运行时状态后重试。 ");
      } catch {
        if (isCurrentIntent(intent) && runtimeKeyRef.current === expectedRuntimeKey)
          setError("项目未能打开，请检查目录和运行时状态后重试。 ");
      } finally {
        if (isCurrentIntent(intent)) setBusy(false);
      }
    },
    [bindingReady, beginIntent, isCurrentIntent, openPersisted, projects, restoreCurrentBinding],
  );

  /**
   * 无项目入口只展示 session 聚合分类，不建立或激活共享 workspace；它必须可在项目配置
   * 读取失败时退出项目 scope，资源清理仍按当前有效 Runtime binding 决定是否执行。
   */
  const selectNoProject = useCallback(async (): Promise<void> => {
    if (workspaceRef.current === undefined) return;
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
      setDirectoryState("unknown");
      if (previous.kind === "project") retainedSessionWorkspaceRef.current = undefined;
      onWorkspaceCommitted(undefined);
      setRevision((current) => current + 1);
    } catch {
      if (isCurrentIntent(intent)) setError("无法切换到无项目对话，请重试。 ");
    } finally {
      preparation?.release?.();
      if (isCurrentIntent(intent)) setBusy(false);
    }
  }, [beginIntent, isCurrentIntent, onWorkspaceCommitted, prepareChange]);

  /** Thread 激活绑定请求起点 Runtime；失败闩锁前置返回，避免无效激活递增 intent 并取消目录刷新。 @author kongweiguang */
  const activateForConversation = useCallback(
    async (workspaceId: string): Promise<WorkspaceProjection | undefined> => {
      const currentWorkspace = workspaceRef.current;
      if (currentWorkspace?.workspaceId === workspaceId && bindingReady) return currentWorkspace;
      const expectedRuntimeKey = runtimeKeyRef.current;
      if (
        currentWorkspace?.workspaceId === workspaceId &&
        currentWorkspace.kind === "project" &&
        (projectsNeedingBindingRef.current.has(currentWorkspace.workspaceId) ||
          (hasFailedProjectBinding(currentWorkspace, expectedRuntimeKey) &&
            !uncertainProjectUpdatesRef.current.has(currentWorkspace.workspaceId)))
      )
        return currentWorkspace;
      const intent = beginIntent();
      const activationIntent = { request: intent, runtimeKey: expectedRuntimeKey };
      pendingConversationActivationRef.current = activationIntent;
      try {
        if (currentWorkspace?.workspaceId === workspaceId) {
          if (expectedRuntimeKey === undefined) return undefined;
          return await restoreCurrentBinding(expectedRuntimeKey, intent);
        }
        let target = projects.find((candidate) => candidate.workspaceId === workspaceId);
        if (target === undefined) {
          let cursor: string | undefined;
          const visitedCursors = new Set<string>();
          do {
            const listed = await history.workspaceList({ kind: "project", limit: 200, cursor });
            if (!isCurrentIntent(intent) || runtimeKeyRef.current !== expectedRuntimeKey)
              return undefined;
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
        if (target !== undefined) return await openPersisted(target, intent, expectedRuntimeKey);
        const activation = await activateWorkspace(workspaceId);
        if (
          !isCurrentIntent(intent) ||
          runtimeKeyRef.current !== expectedRuntimeKey ||
          activation.workspaceId !== workspaceId
        )
          return undefined;
        return await commitWorkspace(
          workspaceFromActivation(activation),
          intent,
          undefined,
          expectedRuntimeKey,
        );
      } catch {
        if (isCurrentIntent(intent)) setError("会话所属工作目录暂时无法打开。 ");
        return undefined;
      } finally {
        if (pendingConversationActivationRef.current === activationIntent)
          pendingConversationActivationRef.current = undefined;
      }
    },
    [
      activateWorkspace,
      bindingReady,
      beginIntent,
      commitWorkspace,
      history,
      hasFailedProjectBinding,
      isCurrentIntent,
      openPersisted,
      projects,
      restoreCurrentBinding,
    ],
  );

  /** 组件卸载时推进 intent，使所有晚到 native 结果失效且不再写 React 状态。 */
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      observedRuntimeKeyRef.current = undefined;
      intentRef.current += 1;
      catalogRequestRef.current += 1;
    };
  }, []);

  /** Runtime identity 变化推进旧请求 fence；同 identity 的配置准入只补读取，不夺走进行中的 Thread 激活。 @author kongweiguang */
  useEffect(() => {
    if (!configurationReady) return;
    if (runtimeKey === undefined) {
      observedRuntimeKeyRef.current = undefined;
      return;
    }
    if (observedRuntimeKeyRef.current === runtimeKey) return;
    observedRuntimeKeyRef.current = runtimeKey;
    const pendingActivation = pendingConversationActivationRef.current;
    const preserveActivation =
      pendingActivation !== undefined &&
      pendingActivation.request === intentRef.current &&
      pendingActivation.runtimeKey === runtimeKey;
    const intent = preserveActivation ? pendingActivation.request : beginIntent();
    void refreshCatalog(intent, true);
    const currentWorkspace = workspaceRef.current;
    const bindingAlreadyReady = currentWorkspace !== undefined && boundRuntimeKey === runtimeKey;
    if (currentWorkspace !== undefined && !bindingAlreadyReady && !preserveActivation)
      void restoreCurrentBinding(runtimeKey, intent);
  }, [
    beginIntent,
    boundRuntimeKey,
    configurationReady,
    refreshCatalog,
    restoreCurrentBinding,
    runtimeKey,
  ]);

  /** 项目列表重试复用原入口；绑定失效时先恢复当前 Java identity，再刷新目录投影。 */
  const retryCatalog = useCallback(async (): Promise<void> => {
    const currentRuntimeKey = runtimeKeyRef.current;
    if (
      workspaceRef.current !== undefined &&
      currentRuntimeKey !== undefined &&
      boundRuntimeKey !== currentRuntimeKey
    ) {
      const intent = beginIntent();
      const restored = await restoreCurrentBinding(currentRuntimeKey, intent);
      if (restored === undefined || !isCurrentIntent(intent)) return;
      await refreshCatalog(intent, false);
      return;
    }
    await refreshCatalog(intentRef.current, false);
  }, [beginIntent, boundRuntimeKey, isCurrentIntent, refreshCatalog, restoreCurrentBinding]);

  /** 侧栏重试是状态型入口；显式复核会投影失败状态，本入口吞掉已展示的拒绝以免产生未处理异常。 @author kongweiguang */
  const retryBinding = useCallback(async (): Promise<void> => {
    const selected = workspaceRef.current;
    const currentRuntimeKey = runtimeKeyRef.current;
    if (selected?.kind !== "project" || currentRuntimeKey === undefined) return;
    await recheckProjectBinding(selected.workspaceId).catch(() => undefined);
  }, [recheckProjectBinding]);

  return {
    workspace,
    bindingReady,
    projects,
    catalogLoading,
    catalogError,
    busy,
    error,
    directoryState,
    revision,
    choose,
    pickDirectory,
    selectNoProject,
    select,
    readProject,
    updateProject,
    recheckProjectBinding,
    retryBinding,
    refreshProjectMetadata,
    setProjectTrust,
    retryCatalog,
    activateForConversation,
  };
}
