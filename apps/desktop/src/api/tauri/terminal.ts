// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { z } from "zod";
import { defaultNativeBridge } from "./runtime";

/**
 * command 列表保持封闭，Terminal 调用方不能把 adapter 扩张为通用进程或 executable RPC surface。
 */
export const JA_TERMINAL_COMMANDS = {
  profiles: "ja_terminal_profiles",
  open: "ja_terminal_open",
  dropNativePaths: "ja_terminal_drop",
  input: "ja_terminal_input",
  resize: "ja_terminal_resize",
  poll: "ja_terminal_poll",
  scrollback: "ja_terminal_scrollback",
  close: "ja_terminal_close",
  closeAll: "ja_terminal_close_all",
} as const;

/** 原生 drag/drop 是固定事件，不公开任意事件订阅。 */
export const JA_TERMINAL_EVENTS = {
  nativeDrop: "ja://workspace-native-drop",
} as const;

type TerminalCommand = (typeof JA_TERMINAL_COMMANDS)[keyof typeof JA_TERMINAL_COMMANDS];

const MAX_PATH_BYTES = 4_096;
const MAX_INPUT_BYTES = 64 * 1024;
const MAX_SCROLLBACK_BYTES = 4 * 1024 * 1024;
const MAX_WORKSPACE_ID_BYTES = 128;

const SessionIdSchema = z.string().uuid();
const GenerationSchema = z.number().int().min(1).max(Number.MAX_SAFE_INTEGER);
const BytesSchema = z.array(z.number().int().min(0).max(255)).max(MAX_SCROLLBACK_BYTES);
const SHELL_PROFILES = ["default", "power_shell", "cmd", "bash", "zsh", "fish"] as const;
const ShellProfileSchema = z.enum(SHELL_PROFILES);
const TerminalProfilesSchema = z
  .array(ShellProfileSchema)
  .max(SHELL_PROFILES.length)
  .superRefine((profiles, context) => {
    if (new Set(profiles).size !== profiles.length) {
      context.addIssue({ code: "custom", message: "terminal profiles must be unique" });
    }
  });
const WorkspaceIdSchema = z
  .string()
  .min(1)
  .max(MAX_WORKSPACE_ID_BYTES)
  .regex(/^ws_[A-Za-z0-9_-]+$/);

/** cwd 是 workspace-relative 展示值；绝对或原生路径永不穿过此 adapter。 */
const RelativeCwdSchema = z
  .string()
  .min(1)
  .max(MAX_PATH_BYTES)
  .refine((value) => {
    if (/^(?:[A-Za-z]:[\\/]|[\\/]{1,2})/.test(value)) return false;
    return !value.split(/[\\/]+/u).some((segment) => segment === "..");
  }, "terminal cwd must be relative to the selected workspace");

/** Terminal size 字段由 Rust 保持 snake_case，因为嵌套 DTO 与 PTY 代码共享。 */
const TerminalSizeSchema = z
  .object({
    rows: z.number().int().min(1).max(4_096),
    cols: z.number().int().min(1).max(4_096),
    pixel_width: z.number().int().min(0).max(4_096).default(0),
    pixel_height: z.number().int().min(0).max(4_096).default(0),
  })
  .strict();

export const TerminalOpenInputSchema = z
  .object({
    workspaceId: WorkspaceIdSchema,
    profile: ShellProfileSchema.default("default"),
    relativeCwd: RelativeCwdSchema.optional(),
    size: TerminalSizeSchema.default({ rows: 24, cols: 80, pixel_width: 0, pixel_height: 0 }),
  })
  .strict();

const TerminalCloseAllInputSchema = z.object({ workspaceId: WorkspaceIdSchema }).strict();

const TerminalSessionInfoSchema = z
  .object({
    sessionId: SessionIdSchema,
    generation: GenerationSchema,
  })
  .strict();

const TerminalIdentitySchema = z
  .object({
    sessionId: SessionIdSchema,
    generation: GenerationSchema,
  })
  .strict();

const TerminalDropInputSchema = z
  .object({
    ...TerminalIdentitySchema.shape,
    dropToken: z.string().uuid(),
  })
  .strict();

const TerminalNativeDropEventSchema = z
  .object({
    dropToken: z.string().uuid(),
    x: z.number().finite().min(-1e9).max(1e9),
    y: z.number().finite().min(-1e9).max(1e9),
  })
  .strict();

const TerminalEventKindSchema = z.discriminatedUnion("type", [
  z
    .object({
      type: z.literal("output"),
      data: BytesSchema.transform((value) => Uint8Array.from(value)),
    })
    .strict(),
  z.object({ type: z.literal("resized"), size: TerminalSizeSchema }).strict(),
  z
    .object({
      type: z.literal("exited"),
      code: z.number().int().min(0).max(0xffff_ffff),
      signal: z.string().max(64).nullable(),
    })
    .strict(),
  z
    .object({
      type: z.literal("closed"),
      reason: z.enum(["user", "shutdown", "timeout", "queue_overflow", "process_exited", "fault"]),
    })
    .strict(),
  z.object({ type: z.literal("error"), code: z.number().int().min(0).max(0xffff) }).strict(),
  z
    .object({
      type: z.literal("output_dropped"),
      bytes: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
    })
    .strict(),
]);

const TerminalEventSchema = z
  .object({
    session_id: SessionIdSchema,
    generation: GenerationSchema,
    sequence: GenerationSchema,
    kind: TerminalEventKindSchema,
  })
  .strict();

export type TerminalSize = z.input<typeof TerminalSizeSchema>;
export type TerminalSessionInfo = z.infer<typeof TerminalSessionInfoSchema>;
export type TerminalEvent = z.infer<typeof TerminalEventSchema>;
export type ShellProfile = z.infer<typeof ShellProfileSchema>;
export type TerminalIdentity = z.infer<typeof TerminalIdentitySchema>;
export type TerminalNativeDropEvent = z.infer<typeof TerminalNativeDropEventSchema>;
export type TerminalUnsubscribe = () => void | Promise<void>;

/** 窄 bridge 可在测试中注入，但仍只准入已知 Terminal command。 */
export interface TerminalNativeBridge {
  invoke(command: TerminalCommand, args?: Record<string, unknown>): Promise<unknown>;
  listen?(event: string, handler: (payload: unknown) => void): Promise<TerminalUnsubscribe>;
}

export type TerminalAdapterErrorCode = "invalid_input" | "invalid_response" | "command_failed";

/** 错误只包含静态文本，原生路径、argv 与环境值不能到达 React。 */
export class TerminalAdapterError extends Error {
  /** 错误实例只携带恢复所需分类，PTY 路径、argv 与原生诊断均在边界外丢弃。 */
  constructor(readonly code: TerminalAdapterErrorCode) {
    super(
      code === "invalid_input"
        ? "终端请求参数无效"
        : code === "invalid_response"
          ? "终端返回数据无效"
          : "终端操作失败",
    );
    this.name = "TerminalAdapterError";
  }
}

/** 将 UI byte view 转换为 Rust `Vec<u8>` 形状，同时保留每个字节并强制 PTY 预算。 */
function encodeBytes(data: Uint8Array | readonly number[]): number[] {
  const values = Array.from(data);
  if (
    values.length > MAX_INPUT_BYTES ||
    values.some((value) => !Number.isInteger(value) || value < 0 || value > 255)
  ) {
    throw new TerminalAdapterError("invalid_input");
  }
  return values;
}

/** 将原生数组转换回 Uint8Array，使 xterm 接收原始字节而非已解码文本。 */
function parseBytes(value: unknown, code: "invalid_response" = "invalid_response"): Uint8Array {
  try {
    return Uint8Array.from(BytesSchema.parse(value));
  } catch {
    throw new TerminalAdapterError(code);
  }
}

/** 解析用户输入时不保留可能包含 workspace 或 Secret 值的 Zod path。 */
function parseInput<T>(schema: z.ZodType<T>, value: unknown): T {
  try {
    return schema.parse(value);
  } catch {
    throw new TerminalAdapterError("invalid_input");
  }
}

/** 在 IPC 边缘解析单个 command 结果，畸形原生数据不能到达 store 或 xterm。 */
function parseResult<T>(schema: z.ZodType<T>, value: unknown): T {
  try {
    return schema.parse(value);
  } catch {
    throw new TerminalAdapterError("invalid_response");
  }
}

/** 从 command 故障移除平台异常文本，同时保留稳定重试边界。 */
function commandFailed(error: unknown): TerminalAdapterError {
  if (error instanceof TerminalAdapterError) return error;
  void error;
  return new TerminalAdapterError("command_failed");
}

/** 通过 Tauri bridge 发送固定 Terminal command，绝不暴露 raw invoke rejection 文本。 */
async function invokeTerminal(
  bridge: TerminalNativeBridge,
  command: TerminalCommand,
  args: Record<string, unknown>,
): Promise<unknown> {
  try {
    return await bridge.invoke(command, args);
  } catch (error) {
    throw commandFailed(error);
  }
}

/**
 * typed PTY adapter 只映射 DTO；shell 选择、cwd containment、进程树清理、
 * 字节预算与 session generation 均由 Rust 拥有。
 */
export class TauriTerminalAdapter {
  constructor(private readonly bridge: TerminalNativeBridge = defaultNativeBridge) {}

  /** 查询 Rust 实际探测到的闭集，拒绝对象、重复项和任何未知 profile。 */
  async profiles(): Promise<readonly ShellProfile[]> {
    const result = await invokeTerminal(this.bridge, JA_TERMINAL_COMMANDS.profiles, {});
    return parseResult(TerminalProfilesSchema, result);
  }

  /** 使用 allow-list Profile 与 workspace-relative cwd 打开 PTY。 */
  async open(
    value: Partial<z.input<typeof TerminalOpenInputSchema>> = {},
  ): Promise<TerminalSessionInfo> {
    const input = parseInput(TerminalOpenInputSchema, value);
    const result = await invokeTerminal(this.bridge, JA_TERMINAL_COMMANDS.open, { input });
    return parseResult(TerminalSessionInfoSchema, result);
  }

  /** 按 Rust 队列相同 chunk 预算写入原始输入字节。 */
  async input(session: TerminalIdentity, data: Uint8Array | readonly number[]): Promise<void> {
    const identity = parseInput(TerminalIdentitySchema, session);
    const input = { ...identity, data: encodeBytes(data) };
    await invokeTerminal(this.bridge, JA_TERMINAL_COMMANDS.input, { input });
  }

  /** 单个原生 drop token 只能消费到一个活动 PTY generation。 */
  async dropNativePaths(session: TerminalIdentity, dropToken: string): Promise<void> {
    const input = parseInput(TerminalDropInputSchema, { ...session, dropToken });
    await invokeTerminal(this.bridge, JA_TERMINAL_COMMANDS.dropNativePaths, { input });
  }

  /** 调整活动 PTY 大小时保留用于阻止陈旧写入的 generation token。 */
  async resize(session: TerminalIdentity, size: TerminalSize): Promise<void> {
    const identity = parseInput(TerminalIdentitySchema, session);
    const input = { ...identity, size: parseInput(TerminalSizeSchema, size) };
    await invokeTerminal(this.bridge, JA_TERMINAL_COMMANDS.resize, { input });
  }

  /** 轮询单个有界事件；UI 可重复调用而无需创建原生 stream。 */
  async poll(session: TerminalIdentity, timeoutMs = 0): Promise<TerminalEvent | null> {
    const identity = parseInput(TerminalIdentitySchema, session);
    const input = parseInput(
      z
        .object({ ...TerminalIdentitySchema.shape, timeoutMs: z.number().int().min(0).max(5_000) })
        .strict(),
      { ...identity, timeoutMs },
    );
    const result = await invokeTerminal(this.bridge, JA_TERMINAL_COMMANDS.poll, { input });
    if (result === null) return null;
    return parseResult(TerminalEventSchema, result);
  }

  /** 使用与 poll 相同的 session identity 读取有界原始 scrollback。 */
  async scrollback(session: TerminalIdentity): Promise<Uint8Array> {
    const identity = parseInput(TerminalIdentitySchema, session);
    const result = await invokeTerminal(this.bridge, JA_TERMINAL_COMMANDS.scrollback, {
      input: identity,
    });
    return parseBytes(result);
  }

  /** 精确关闭一个 session；不接受任意进程或 executable 标识。 */
  async close(session: TerminalIdentity): Promise<void> {
    const input = parseInput(TerminalIdentitySchema, session);
    await invokeTerminal(this.bridge, JA_TERMINAL_COMMANDS.close, { input });
  }

  /** 替换已选 workspace 前关闭全部原生 PTY，避免跨工作区资源残留。 */
  async closeAll(workspaceId: string): Promise<void> {
    const input = parseInput(TerminalCloseAllInputSchema, { workspaceId });
    await invokeTerminal(this.bridge, JA_TERMINAL_COMMANDS.closeAll, { input });
  }

  /** 只订阅已移除路径的 token/point 事件，并丢弃畸形 payload。 */
  async subscribeNativeDrop(
    listener: (event: TerminalNativeDropEvent) => void,
  ): Promise<TerminalUnsubscribe> {
    if (this.bridge.listen === undefined) {
      throw new TerminalAdapterError("command_failed");
    }
    try {
      return await this.bridge.listen(JA_TERMINAL_EVENTS.nativeDrop, (payload) => {
        try {
          listener(parseTerminalNativeDropEvent(payload));
        } catch {
          // 携带路径或形状畸形的事件必须在 Terminal UI 看到前丢弃。
        }
      });
    } catch (error) {
      throw commandFailed(error);
    }
  }
}

/** 解析唯一 native-drop 事件形状，不保留被拒绝的字段或值。 */
export function parseTerminalNativeDropEvent(payload: unknown): TerminalNativeDropEvent {
  try {
    return TerminalNativeDropEventSchema.parse(payload);
  } catch {
    throw new TerminalAdapterError("invalid_input");
  }
}
