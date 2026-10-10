// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export type ConversationProcessState = "running" | "stopping" | "exited" | "interrupted";

/** UI 只消费可见会话身份和状态；进程输出始终按游标从 App Server 读取。 */
export interface ConversationProcessSession {
  readonly workspaceId: string;
  readonly threadId: string;
  readonly generation: number;
  readonly sessionId: string;
  readonly startedTurnId: string;
  readonly startedCallId: string;
  readonly state: ConversationProcessState;
  readonly outputSequence: number;
  readonly outputTruncated: boolean;
  readonly exitCode: number | null;
}

export interface ConversationProcessSnapshot extends ConversationProcessSession {
  readonly output: string;
  readonly nextSequence: number;
}

export interface ConversationProcessScope {
  readonly workspaceId: string;
  readonly threadId: string;
}

/** read/write/stop 的 generation 是 process registry fence；会话快照自身仍返回原始进程代际。 */
export interface ConversationProcessGenerationScope extends ConversationProcessScope {
  readonly generation: number;
}

/** 当前窗口只绑定一个 workspace/runtime owner；generation 仅用于 UI 丢弃旧生命周期回调。 */
export interface ConversationProcessBinding {
  readonly workspaceId: string;
  readonly generation: number;
  readonly port: ConversationProcessPort;
}

/** Composition 适配器必须在每次 RPC 前再次校验 scope 和当前 runtime generation。 */
export interface ConversationProcessPort {
  list(
    input: ConversationProcessScope & {
      readonly limit: number;
      readonly cursor: null;
      readonly startedCallId: string;
    },
  ): Promise<{
    readonly generation: number;
    readonly sessions: readonly ConversationProcessSession[];
  }>;
  read(
    input: ConversationProcessGenerationScope & {
      readonly sessionId: string;
      readonly afterSequence: number;
      readonly maxBytes: number;
    },
  ): Promise<ConversationProcessSnapshot>;
  write(
    input: ConversationProcessGenerationScope & {
      readonly sessionId: string;
      readonly afterSequence: number;
      readonly input: string;
      readonly closeInput: boolean;
      readonly operationKey: string;
    },
  ): Promise<ConversationProcessSnapshot>;
  stop(
    input: ConversationProcessGenerationScope & {
      readonly sessionId: string;
    },
  ): Promise<ConversationProcessSnapshot>;
}
