// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { TerminalSessionInfo, TerminalWorkspaceAdapter } from "@/features/workbench/terminal";
import type { JaWorkbenchAdapters } from "../useJaWorkbench";

interface OwnedTerminalSession {
  readonly workspaceId: string;
  readonly session: TerminalSessionInfo;
}

interface PendingTerminalOpen {
  readonly workspaceId: string;
  readonly completion: Promise<TerminalSessionInfo>;
}

/** 使用原生签发的 session identity 建立进程期 key，禁止用 workspace 或 Thread 推导 PTY 身份。 */
function terminalSessionKey(session: TerminalSessionInfo): string {
  return `${session.sessionId}:${session.generation}`;
}

/** 为越过当前会话边界的调用提供稳定失败，避免误操作另一个会话拥有的原生 PTY。 */
function terminalOwnershipError(): Error {
  return new Error("terminal session is not owned by this conversation");
}

/** 关闭栅栏期间拒绝新建 PTY；调用方可在本轮关闭结算后重新触发打开。 */
function terminalClosingError(): Error {
  return new Error("terminal workspace scope is closing");
}

class ThreadTerminalAdapter implements TerminalWorkspaceAdapter {
  private readonly ownedSessions = new Map<string, OwnedTerminalSession>();
  private readonly pendingOpens = new Set<PendingTerminalOpen>();
  private readonly closingSessions = new Map<string, Promise<void>>();
  private readonly closingWorkspaces = new Set<string>();
  private readonly workspaceClosures = new Map<string, Promise<void>>();

  /** 只保存共享原生端口；所有会话所有权均来自此实例亲自完成的 open。 */
  constructor(private readonly sharedAdapter: TerminalWorkspaceAdapter) {}

  /** Profile 是只读平台能力，不携带 session，因此可以直接复用共享原生端口。 */
  profiles(): ReturnType<TerminalWorkspaceAdapter["profiles"]> {
    return this.sharedAdapter.profiles();
  }

  /**
   * 完整透传调用方提供的原生 open 参数，并在原生 ACK 后登记 session；若 closeAll 已在
   * 等待该 open，则先关闭迟到 session 再拒绝返回，避免 renderer 接管已退出作用域的 PTY。
   */
  async open(
    input: Parameters<TerminalWorkspaceAdapter["open"]>[0],
  ): ReturnType<TerminalWorkspaceAdapter["open"]> {
    if (this.closingWorkspaces.has(input.workspaceId)) throw terminalClosingError();
    const completion = this.sharedAdapter.open(input);
    const pending: PendingTerminalOpen = { workspaceId: input.workspaceId, completion };
    this.pendingOpens.add(pending);
    try {
      const session = await completion;
      this.ownedSessions.set(terminalSessionKey(session), {
        workspaceId: input.workspaceId,
        session,
      });
      if (this.closingWorkspaces.has(input.workspaceId)) {
        await this.closeOwnedSession(session);
        throw terminalClosingError();
      }
      return session;
    } finally {
      this.pendingOpens.delete(pending);
    }
  }

  /** 原生拖放只准投递到此实例已经登记的 session。 */
  dropNativePaths(
    session: TerminalSessionInfo,
    dropToken: string,
  ): ReturnType<TerminalWorkspaceAdapter["dropNativePaths"]> {
    this.requireOwnedSession(session);
    return this.sharedAdapter.dropNativePaths(session, dropToken);
  }

  /** 输入写入沿用原生 generation，并在调用原生端口前核对会话所有权。 */
  input(
    session: TerminalSessionInfo,
    data: Parameters<TerminalWorkspaceAdapter["input"]>[1],
  ): ReturnType<TerminalWorkspaceAdapter["input"]> {
    this.requireOwnedSession(session);
    return this.sharedAdapter.input(session, data);
  }

  /** 尺寸更新只透传真实 session 与原生 DTO，不创建 Thread 或 workspace 替代身份。 */
  resize(
    session: TerminalSessionInfo,
    size: Parameters<TerminalWorkspaceAdapter["resize"]>[1],
  ): ReturnType<TerminalWorkspaceAdapter["resize"]> {
    this.requireOwnedSession(session);
    return this.sharedAdapter.resize(session, size);
  }

  /** 轮询前核对 session owner，防止隐藏会话读取另一个会话的 PTY 输出。 */
  poll(
    session: TerminalSessionInfo,
    timeoutMs?: number,
  ): ReturnType<TerminalWorkspaceAdapter["poll"]> {
    this.requireOwnedSession(session);
    return this.sharedAdapter.poll(session, timeoutMs);
  }

  /** Scrollback 与实时轮询使用同一所有权规则，避免历史字节跨会话泄露。 */
  scrollback(session: TerminalSessionInfo): ReturnType<TerminalWorkspaceAdapter["scrollback"]> {
    this.requireOwnedSession(session);
    return this.sharedAdapter.scrollback(session);
  }

  /** 单 session 关闭成功后才释放本地 owner；失败继续保留，以便用户重试。 */
  close(session: TerminalSessionInfo): ReturnType<TerminalWorkspaceAdapter["close"]> {
    this.requireOwnedSession(session);
    return this.closeOwnedSession(session);
  }

  /**
   * 关闭当前实例在指定真实 workspace 内打开的全部 session；绝不调用共享 adapter.closeAll，
   * 因为原生 workspace 级关闭会误伤同项目下其它会话的 PTY。
   */
  async closeAll(workspaceId: string): ReturnType<TerminalWorkspaceAdapter["closeAll"]> {
    const existing = this.workspaceClosures.get(workspaceId);
    if (existing !== undefined) return existing;
    this.closingWorkspaces.add(workspaceId);
    const operation = this.closeWorkspaceSessions(workspaceId);
    this.workspaceClosures.set(workspaceId, operation);
    try {
      await operation;
    } finally {
      if (this.workspaceClosures.get(workspaceId) === operation)
        this.workspaceClosures.delete(workspaceId);
      this.closingWorkspaces.delete(workspaceId);
    }
  }

  /** Native drop 事件本身不含 session；具体命中与写入仍在调用 dropNativePaths 时做 owner 校验。 */
  subscribeNativeDrop(
    listener: Parameters<TerminalWorkspaceAdapter["subscribeNativeDrop"]>[0],
  ): ReturnType<TerminalWorkspaceAdapter["subscribeNativeDrop"]> {
    return this.sharedAdapter.subscribeNativeDrop(listener);
  }

  /** 返回已登记 owner；未知或已成功关闭的 session 必须在触达原生端口前失败。 */
  private requireOwnedSession(session: TerminalSessionInfo): OwnedTerminalSession {
    const owned = this.ownedSessions.get(terminalSessionKey(session));
    if (owned === undefined) throw terminalOwnershipError();
    return owned;
  }

  /**
   * 同一 session 的并发 close 共享一次原生调用；只有 ACK 后才删除 owner，finally 清除中的
   * promise 使失败 close 可以由后续显式操作再次执行。
   */
  private closeOwnedSession(session: TerminalSessionInfo): Promise<void> {
    const key = terminalSessionKey(session);
    this.requireOwnedSession(session);
    const existing = this.closingSessions.get(key);
    if (existing !== undefined) return existing;
    const operation = this.sharedAdapter.close(session).then(() => {
      this.ownedSessions.delete(key);
    });
    this.closingSessions.set(key, operation);
    void operation.then(
      () => this.closingSessions.delete(key),
      () => this.closingSessions.delete(key),
    );
    return operation;
  }

  /**
   * closeAll 的 workspace 栅栏建立后不再有新的同范围 open；先等待已接纳 open 的原生结果，
   * 再并行关闭当前 owner。部分成功会被删除，首个失败原样抛出并保留未关闭 owner 供重试。
   */
  private async closeWorkspaceSessions(workspaceId: string): Promise<void> {
    const pending = [...this.pendingOpens]
      .filter((open) => open.workspaceId === workspaceId)
      .map((open) => open.completion);
    await Promise.allSettled(pending);
    const sessions = [...this.ownedSessions.values()]
      .filter((owned) => owned.workspaceId === workspaceId)
      .map((owned) => owned.session);
    const results = await Promise.allSettled(
      sessions.map((session) => this.closeOwnedSession(session)),
    );
    const failure = results.find(
      (result): result is PromiseRejectedResult => result.status === "rejected",
    );
    if (failure !== undefined) throw failure.reason;
  }
}

/**
 * 为一个会话创建独立 Terminal owner；调用方必须按会话 identity 缓存返回实例，不能在 render
 * 中重复创建，否则本地所有权表会丢失且无法可靠清理已打开 PTY。
 */
export function createThreadTerminalAdapter(
  sharedAdapter: TerminalWorkspaceAdapter,
): TerminalWorkspaceAdapter {
  return new ThreadTerminalAdapter(sharedAdapter);
}

/**
 * 为一个会话创建 Workbench adapter 集合；只包装有原生 PTY 所有权的 Terminal，Files、Review
 * 与 Preview 继续使用既有窄端口，其会话态分别由对应 controller/storage owner 管理。
 */
export function createThreadWorkbenchAdapters(
  sharedAdapters: JaWorkbenchAdapters,
): JaWorkbenchAdapters {
  return {
    ...sharedAdapters,
    terminal: createThreadTerminalAdapter(sharedAdapters.terminal),
  };
}
