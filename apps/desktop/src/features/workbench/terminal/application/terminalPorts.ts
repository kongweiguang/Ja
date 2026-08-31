// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { TerminalProfile } from "../domain";

/** 原生终端尺寸保留 Rust DTO 字段名，避免 application 层偷偷引入第二套 IPC 映射。 */
export interface TerminalSize {
  rows: number;
  cols: number;
  pixel_width: number;
  pixel_height: number;
}

/** 不透明会话代次是所有写操作的并发栅栏，前端不能自行生成或修复。 */
export interface TerminalSessionInfo {
  sessionId: string;
  generation: number;
}

/** 终端输出保持原始字节，避免在 application 层提前破坏 UTF-8 或 ANSI 状态。 */
export interface TerminalOutputChunk {
  sequence: number | string;
  data: Uint8Array | readonly number[];
}

/** 打开请求只表达工作区内意图；可执行文件、绝对路径和环境变量不属于前端端口。 */
export interface TerminalOpenInput {
  workspaceId: string;
  profile: TerminalProfile;
  relativeCwd?: string;
  size: TerminalSize;
}

/** 原生事件在 adapter 边界完成校验，controller 只按会话代次和事件类型编排。 */
export type TerminalEvent = {
  session_id: string;
  generation: number;
  sequence: number;
  kind:
    | { type: "output"; data: Uint8Array }
    | { type: "resized"; size: TerminalSize }
    | { type: "exited"; code: number; signal: string | null }
    | {
        type: "closed";
        reason: "user" | "shutdown" | "timeout" | "queue_overflow" | "process_exited" | "fault";
      }
    | { type: "error"; code: number }
    | { type: "output_dropped"; bytes: number };
};

/** 拖放端口只暴露一次性 token 与坐标，绝不让原生绝对路径进入 React。 */
export interface TerminalNativeDropEvent {
  dropToken: string;
  x: number;
  y: number;
}

/**
 * application 只依赖这组终端能力端口，具体 Tauri adapter 由 app composition 注入。
 * 端口刻意保持窄接口，防止 controller 获得通用 invoke、进程或文件系统能力。
 */
export interface TerminalWorkspaceAdapter {
  profiles(): Promise<readonly TerminalProfile[]>;
  open(input: TerminalOpenInput): Promise<TerminalSessionInfo>;
  dropNativePaths(session: TerminalSessionInfo, dropToken: string): Promise<void>;
  input(session: TerminalSessionInfo, data: Uint8Array | readonly number[]): Promise<void>;
  resize(session: TerminalSessionInfo, size: TerminalSize): Promise<void>;
  poll(session: TerminalSessionInfo, timeoutMs?: number): Promise<TerminalEvent | null>;
  scrollback(session: TerminalSessionInfo): Promise<Uint8Array>;
  close(session: TerminalSessionInfo): Promise<void>;
  closeAll(workspaceId: string): Promise<void>;
  subscribeNativeDrop(
    listener: (event: TerminalNativeDropEvent) => void,
  ): Promise<() => void | Promise<void>>;
}
