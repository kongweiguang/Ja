// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useRef, useState, type ReactElement } from "react";
import { toast } from "sonner";
import type { FilesWorkspaceCloseLease, FilesWorkspaceLifecycle } from "@/features/workbench/files";
import type { PreviewWorkspaceLifecycle } from "../useJaWorkbench";
import { createThreadWorkbenchAdapters } from "../application/threadWorkbenchAdapters";
import type { TerminalWorkspaceLifecycle } from "../application/workbenchLifecyclePorts";
import { WorkbenchHost, type WorkbenchHostProps } from "./WorkbenchHost";

export interface ThreadWorkbenchSessionsProps extends WorkbenchHostProps {
  readonly scopeKey: string;
  /** 只向当前缓存会话暴露侧聊 slash launcher；隐藏会话不得覆盖当前入口。 */
  readonly onRegisterSideChatLauncher?: WorkbenchHostProps["onRegisterSideChatLauncher"];
  /** Files 已由当前会话完成 flush 后，只通知壳层恢复焦点，不得再次触发 workspace 级关闭。 */
  readonly onFilesCapabilityClosed?: (workspaceId: string) => void;
}

interface ThreadLifecycleRegistry {
  readonly filesLifecycle: FilesWorkspaceLifecycle;
  readonly terminalLifecycle: TerminalWorkspaceLifecycle;
  readonly previewLifecycle: PreviewWorkspaceLifecycle;
  readonly registerFiles: (
    scopeKey: string,
    lifecycle: FilesWorkspaceLifecycle | undefined,
  ) => void;
  readonly registerTerminal: (
    scopeKey: string,
    lifecycle: TerminalWorkspaceLifecycle | undefined,
  ) => void;
  readonly registerPreview: (
    scopeKey: string,
    lifecycle: PreviewWorkspaceLifecycle | undefined,
  ) => void;
  readonly currentFiles: (scopeKey: string) => FilesWorkspaceLifecycle | undefined;
  readonly activate: () => () => void;
}

interface ThreadLifecycleSnapshot {
  readonly files: ReadonlyMap<string, FilesWorkspaceLifecycle>;
  readonly terminal: ReadonlyMap<string, TerminalWorkspaceLifecycle>;
  readonly preview: ReadonlyMap<string, PreviewWorkspaceLifecycle>;
}

interface ThreadSessionHostProps {
  readonly scopeKey: string;
  readonly current: boolean;
  readonly input?: ThreadSessionInput;
  readonly registry: ThreadLifecycleRegistry;
}

interface ThreadSessionInput {
  readonly hostProps: WorkbenchHostProps;
  readonly onFilesCapabilityClosed?: (workspaceId: string) => void;
  readonly onRegisterSideChatLauncher?: WorkbenchHostProps["onRegisterSideChatLauncher"];
}

/** 卸载已无法回滚 UI，只发布稳定脱敏反馈；底层 rejection 不得进入用户消息或日志。 */
function reportRetainedCleanupFailure(): void {
  toast.error("部分文件或工作区资源未能完成清理，请重新打开项目确认。", {
    id: "ja-retained-workbench-cleanup-failed",
  });
}

/** 释放全部已取得的 Files lease；单个 release 异常不能阻止其它会话解除编辑冻结。 */
function releaseFilesLeases(leases: readonly FilesWorkspaceCloseLease[]): void {
  let firstError: unknown;
  for (const lease of leases) {
    try {
      lease.release();
    } catch (error: unknown) {
      firstError ??= error;
    }
  }
  if (firstError !== undefined) throw firstError;
}

/** 聚合当前项目所有会话的 Files fence；任一 flush 失败时立即释放其它已取得的 lease。 */
async function flushAllFiles(
  workspaceId: string,
  lifecycles: ReadonlyMap<string, FilesWorkspaceLifecycle>,
): Promise<FilesWorkspaceCloseLease> {
  const results = await Promise.allSettled(
    [...lifecycles.values()]
      .filter((lifecycle) => lifecycle.workspaceId === workspaceId)
      .map((lifecycle) => lifecycle.flushForWorkspaceChange()),
  );
  const leases = results.flatMap((result) => (result.status === "fulfilled" ? [result.value] : []));
  const failure = results.find((result) => result.status === "rejected");
  if (failure?.status === "rejected") {
    releaseFilesLeases(leases);
    throw failure.reason;
  }

  let released = false;
  return {
    release: (): void => {
      if (released) return;
      released = true;
      releaseFilesLeases(leases);
    },
  };
}

/**
 * 关闭所有会话的 PTY；部分失败时恢复全部会话 controller，避免已经关闭的 Host 永久停在
 * workspace-change 状态，成功后的恢复则由父协调器在事务最终结算时统一调用。
 */
async function closeAllTerminals(
  workspaceId: string,
  lifecycles: ReadonlyMap<string, TerminalWorkspaceLifecycle>,
): Promise<void> {
  const targets = [...lifecycles.values()].filter(
    (lifecycle) => lifecycle.workspaceId === workspaceId,
  );
  const results = await Promise.allSettled(
    targets.map((lifecycle) => lifecycle.closeForWorkspaceChange()),
  );
  const failure = results.find((result) => result.status === "rejected");
  if (failure?.status !== "rejected") return;
  for (const lifecycle of targets) lifecycle.resumeAfterWorkspaceChange();
  throw failure.reason;
}

/** Preview 原生子窗口均需得到关闭 ACK；聚合后仍保留首个失败供 workspace 事务回滚。 */
async function closeAllPreviews(
  workspaceId: string,
  lifecycles: ReadonlyMap<string, PreviewWorkspaceLifecycle>,
): Promise<void> {
  const results = await Promise.allSettled(
    [...lifecycles.values()]
      .filter((lifecycle) => lifecycle.workspaceId === workspaceId)
      .map((lifecycle) => lifecycle.closeForWorkspaceChange()),
  );
  const failure = results.find((result) => result.status === "rejected");
  if (failure?.status === "rejected") throw failure.reason;
}

/**
 * React 已决定卸载后无法以 flush 失败阻止视图销毁，因此仍按 Files、Terminal、Preview 顺序
 * 尽最大努力完成全部回收；成功取得的 Files lease 覆盖原生清理事务并在最终阶段释放。
 */
async function cleanupRetainedResources(
  workspaceId: string,
  snapshot: ThreadLifecycleSnapshot,
): Promise<void> {
  const failures: unknown[] = [];
  let filesLease: FilesWorkspaceCloseLease | undefined;
  try {
    filesLease = await flushAllFiles(workspaceId, snapshot.files);
  } catch (error: unknown) {
    failures.push(error);
  }
  try {
    await closeAllTerminals(workspaceId, snapshot.terminal);
  } catch (error: unknown) {
    failures.push(error);
  }
  try {
    await closeAllPreviews(workspaceId, snapshot.preview);
  } catch (error: unknown) {
    failures.push(error);
  }
  try {
    filesLease?.release();
  } catch (error: unknown) {
    failures.push(error);
  }
  if (failures.length === 1) throw failures[0];
  if (failures.length > 1)
    throw new AggregateError(failures, "retained workbench resource cleanup failed");
}

/**
 * 可变的各会话端口封装在事件型 registry 闭包内，React 只持有稳定窄方法与聚合 lifecycle，
 * 避免渲染期读取 Map 或因注册动作触发无意义重渲染。
 */
function createThreadLifecycleRegistry(workspaceId: string): ThreadLifecycleRegistry {
  const files = new Map<string, FilesWorkspaceLifecycle>();
  const terminal = new Map<string, TerminalWorkspaceLifecycle>();
  const preview = new Map<string, PreviewWorkspaceLifecycle>();
  let mountRevision = 0;

  /** undefined 是对应 Host 的卸载信号，只删除该 scope。 */
  const registerFiles = (
    scopeKey: string,
    lifecycle: FilesWorkspaceLifecycle | undefined,
  ): void => {
    if (lifecycle === undefined) files.delete(scopeKey);
    else files.set(scopeKey, lifecycle);
  };
  /** Terminal 注册保留所有已访问会话，不以最后注册者覆盖 workspace 生命周期。 */
  const registerTerminal = (
    scopeKey: string,
    lifecycle: TerminalWorkspaceLifecycle | undefined,
  ): void => {
    if (lifecycle === undefined) terminal.delete(scopeKey);
    else terminal.set(scopeKey, lifecycle);
  };
  /** Preview 注册保留所有 native 子窗口 owner，项目切换时必须逐一关闭。 */
  const registerPreview = (
    scopeKey: string,
    lifecycle: PreviewWorkspaceLifecycle | undefined,
  ): void => {
    if (lifecycle === undefined) preview.delete(scopeKey);
    else preview.set(scopeKey, lifecycle);
  };

  /**
   * cleanup 立即快照端口，确保随后子 Host 的注销不会丢失 owner；实际关闭延迟一个 microtask，
   * 同 registry 的 StrictMode 重挂或 callback 换代会先递增 revision 并取消这次模拟卸载。
   */
  const activate = (): (() => void) => {
    const revision = ++mountRevision;
    let released = false;
    return (): void => {
      if (released) return;
      released = true;
      const snapshot: ThreadLifecycleSnapshot = {
        files: new Map(files),
        terminal: new Map(terminal),
        preview: new Map(preview),
      };
      queueMicrotask(() => {
        if (mountRevision !== revision) return;
        // React cleanup 不能等待 Promise；以稳定反馈消费 rejection，同时不泄漏原生诊断。
        void cleanupRetainedResources(workspaceId, snapshot).catch(reportRetainedCleanupFailure);
      });
    };
  };

  return {
    filesLifecycle: {
      workspaceId,
      flushForWorkspaceChange: () => flushAllFiles(workspaceId, files),
    },
    terminalLifecycle: {
      workspaceId,
      closeForWorkspaceChange: () => closeAllTerminals(workspaceId, terminal),
      resumeAfterWorkspaceChange: () => {
        for (const lifecycle of terminal.values())
          if (lifecycle.workspaceId === workspaceId) lifecycle.resumeAfterWorkspaceChange();
      },
    },
    previewLifecycle: {
      workspaceId,
      closeForWorkspaceChange: () => closeAllPreviews(workspaceId, preview),
    },
    registerFiles,
    registerTerminal,
    registerPreview,
    currentFiles: (scopeKey: string) => files.get(scopeKey),
    activate,
  };
}

/**
 * 每个缓存 Host 独占稳定 adapter 与 lifecycle 注册回调；后台 Host 继续保留 React/controller
 * 状态，但 active=false 且 wrapper hidden，不能获得焦点或启动重型可见性 IO。
 */
function ThreadSessionHost({
  scopeKey,
  current,
  input,
  registry,
}: ThreadSessionHostProps): ReactElement {
  const [retained, setRetained] = useState<{
    readonly source: ThreadSessionInput;
    readonly cached: ThreadSessionInput;
  }>(() => {
    if (input === undefined) throw new Error("thread workbench session requires initial props");
    return {
      source: input,
      cached: input,
    };
  });
  if (input !== undefined && retained.source !== input) {
    setRetained({
      source: input,
      cached: input,
    });
  }
  const effectiveInput = input ?? retained.cached;
  const { hostProps, onFilesCapabilityClosed, onRegisterSideChatLauncher } = effectiveInput;
  const [adapters] = useState(() => createThreadWorkbenchAdapters(hostProps.adapters));
  const closeFilesTaskRef = useRef<Promise<void> | undefined>(undefined);

  /** 子 Host 卸载时的 undefined 只清理自身注册项，不覆盖其它会话已注册的端口。 */
  const registerFiles = useCallback(
    (lifecycle: FilesWorkspaceLifecycle | undefined): void => {
      registry.registerFiles(scopeKey, lifecycle);
    },
    [registry, scopeKey],
  );

  /** Terminal lifecycle 按 Thread 保存，workspace 聚合端口只在切项目或退出时遍历它们。 */
  const registerTerminal = useCallback(
    (lifecycle: TerminalWorkspaceLifecycle | undefined): void => {
      registry.registerTerminal(scopeKey, lifecycle);
    },
    [registry, scopeKey],
  );

  /** Preview lifecycle 按 Thread 保存，禁止后注册的当前会话覆盖后台会话的 native close port。 */
  const registerPreview = useCallback(
    (lifecycle: PreviewWorkspaceLifecycle | undefined): void => {
      registry.registerPreview(scopeKey, lifecycle);
    },
    [registry, scopeKey],
  );

  /**
   * Files Tab 关闭只 flush 本 Host，并在 ACK 后立即释放短 lease；不调用父级 workspace close，
   * 否则同项目下其它会话会被错误冻结和刷盘。
   */
  const closeFilesCapability = useCallback(
    (workspaceId: string): Promise<void> => {
      const pending = closeFilesTaskRef.current;
      if (pending !== undefined) return pending;
      const lifecycle = registry.currentFiles(scopeKey);
      if (lifecycle?.workspaceId !== workspaceId) return Promise.resolve();
      const task = (async (): Promise<void> => {
        const lease = await lifecycle.flushForWorkspaceChange();
        lease.release();
        onFilesCapabilityClosed?.(workspaceId);
      })().finally(() => {
        if (closeFilesTaskRef.current === task) closeFilesTaskRef.current = undefined;
      });
      closeFilesTaskRef.current = task;
      return task;
    },
    [onFilesCapabilityClosed, registry, scopeKey],
  );

  return (
    <div
      className="ja-thread-workbench-session"
      data-thread-workbench-scope={scopeKey}
      hidden={!current}
      aria-hidden={current ? undefined : true}
      inert={current ? undefined : true}
      style={current ? { display: "contents" } : undefined}
    >
      <WorkbenchHost
        {...hostProps}
        adapters={adapters}
        active={current && hostProps.active}
        onRegisterFilesLifecycle={registerFiles}
        onRegisterTerminalLifecycle={registerTerminal}
        onRegisterPreviewLifecycle={registerPreview}
        onCloseFilesCapability={closeFilesCapability}
        onRegisterSideChatLauncher={current ? onRegisterSideChatLauncher : undefined}
      />
    </div>
  );
}

/**
 * 在同一个 workspace 宿主内保留所有已访问 Thread 的 WorkbenchHost；当前会话直接消费
 * 最新 props，后台会话只消费最后一次已提交快照，从而不把 Task、Review 或附件 target 串线。
 */
export function ThreadWorkbenchSessions({
  scopeKey,
  onRegisterSideChatLauncher,
  onFilesCapabilityClosed,
  onRegisterFilesLifecycle,
  onRegisterTerminalLifecycle,
  onRegisterPreviewLifecycle,
  ...hostProps
}: ThreadWorkbenchSessionsProps): ReactElement | null {
  const validScope = scopeKey.length > 0 && hostProps.rootThreadId !== undefined;
  const [scopeKeys, setScopeKeys] = useState<readonly string[]>(() =>
    validScope ? [scopeKey] : [],
  );
  const workspaceId = hostProps.workspace.workspaceId;
  const [registry] = useState(() => createThreadLifecycleRegistry(workspaceId));
  if (validScope && !scopeKeys.includes(scopeKey)) setScopeKeys([...scopeKeys, scopeKey]);

  useEffect(() => {
    const deactivate = registry.activate();
    onRegisterFilesLifecycle(registry.filesLifecycle);
    onRegisterTerminalLifecycle(registry.terminalLifecycle);
    onRegisterPreviewLifecycle(registry.previewLifecycle);
    return () => {
      deactivate();
      onRegisterFilesLifecycle(undefined);
      onRegisterTerminalLifecycle(undefined);
      onRegisterPreviewLifecycle(undefined);
    };
  }, [onRegisterFilesLifecycle, onRegisterPreviewLifecycle, onRegisterTerminalLifecycle, registry]);

  return (
    <>
      {scopeKeys.map((retainedScope) => {
        const current = validScope && retainedScope === scopeKey;
        return (
          <ThreadSessionHost
            key={retainedScope}
            scopeKey={retainedScope}
            current={current}
            input={
              current
                ? {
                    hostProps: {
                      ...hostProps,
                      onRegisterFilesLifecycle,
                      onRegisterTerminalLifecycle,
                      onRegisterPreviewLifecycle,
                    },
                    onFilesCapabilityClosed,
                    onRegisterSideChatLauncher,
                  }
                : undefined
            }
            registry={registry}
          />
        );
      })}
    </>
  );
}
