// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { invoke as tauriInvoke } from "@tauri-apps/api/core";
import { listen as tauriListen, type UnlistenFn } from "@tauri-apps/api/event";
import { z } from "zod";
import { normalizePreviewUrl } from "@/shared/validation/previewUrl";
import { invokeNativeCommand } from "./nativeInvoke";

/** 封闭 command/event surface，防止 Preview 页面选择任意 Tauri capability。 */
export const JA_PREVIEW_COMMANDS = {
  recoverPending: "ja_preview_recover_pending",
  open: "ja_preview_open",
  navigate: "ja_preview_navigate",
  layout: "ja_preview_layout",
  close: "ja_preview_close",
  events: "ja_preview_events",
  state: "ja_preview_state",
} as const;

export const JA_PREVIEW_EVENTS = {
  preview: "ja://preview",
} as const;

type PreviewCommand = (typeof JA_PREVIEW_COMMANDS)[keyof typeof JA_PREVIEW_COMMANDS];
type PreviewEventName = (typeof JA_PREVIEW_EVENTS)[keyof typeof JA_PREVIEW_EVENTS];

const PreviewIdSchema = z.string().uuid();
const GenerationSchema = z.number().int().min(0).max(Number.MAX_SAFE_INTEGER);
const SequenceSchema = z.number().int().min(0).max(Number.MAX_SAFE_INTEGER);
const RecoveryCountSchema = z.number().int().min(0).max(Number.MAX_SAFE_INTEGER);
const PreviewViewportSchema = z
  .object({
    x: z.number().finite().min(0).max(100_000),
    y: z.number().finite().min(0).max(100_000),
    width: z.number().finite().min(0).max(100_000),
    height: z.number().finite().min(0).max(100_000),
    visible: z.boolean(),
  })
  .strict();

/** 复用现有 URL 标准库边界，只有 HTTP(S) 能到达 Rust/WebView。 */
const PreviewUrlSchema = z
  .string()
  .min(1)
  .max(8_192)
  .refine(
    (value) => normalizePreviewUrl(value) !== undefined,
    "preview URL must use http or https",
  );

const PreviewWindowSchema = z
  .object({
    label: z.string().min(1).max(128),
    url: PreviewUrlSchema,
  })
  .strict();

const PreviewSessionSnapshotSchema = z
  .object({
    id: PreviewIdSchema,
    generation: GenerationSchema,
    status: z.enum(["open", "closed"]),
    load_status: z.enum(["loading", "finished", "failed"]),
    url: PreviewUrlSchema,
    title: z.string().max(1_024),
    window: PreviewWindowSchema,
    dropped_events: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
  })
  .strict();

const PreviewEventKindSchema = z.discriminatedUnion("type", [
  z.object({ type: z.literal("opened"), url: PreviewUrlSchema }).strict(),
  z
    .object({
      type: z.literal("navigation_committed"),
      source: z.enum(["user", "redirect"]),
      url: PreviewUrlSchema,
    })
    .strict(),
  z.object({ type: z.literal("title_changed"), title: z.string().max(1_024) }).strict(),
  z.object({ type: z.literal("load_failed"), message: z.string().max(4_096) }).strict(),
  z.object({ type: z.literal("load_finished"), url: PreviewUrlSchema }).strict(),
  z.object({ type: z.literal("closed") }).strict(),
]);

const PreviewEventSchemaRaw = z
  .object({
    session_id: PreviewIdSchema,
    generation: GenerationSchema,
    sequence: SequenceSchema,
    kind: PreviewEventKindSchema,
  })
  .strict();

/** 静态 fallback 避免原生 URL、路径或异常进入 Preview 错误状态。 */
function safePreviewMessage(message: string): string {
  return /(?:token|secret|password|api[_-]?key|authorization|credential|[A-Za-z]:[\\/]|\\\\|\b(?:https?|file|tauri):\/\/|(?:^|[\\/])(Users|home|private|var|tmp)(?:[\\/]|$))/i.test(
    message,
  )
    ? "预览加载失败"
    : message;
}

/** 解析 Preview 事件，并在交付 UI 前脱敏可疑原生诊断。 */
function parsePreviewEvent(value: unknown): PreviewEvent {
  return PreviewEventSchema.parse(value);
}

const PreviewOpenResultSchema = z
  .object({
    snapshot: PreviewSessionSnapshotSchema,
    window: PreviewWindowSchema,
  })
  .strict();
const PreviewRecoveryReportSchema = z
  .object({
    observed: RecoveryCountSchema,
    recovered: RecoveryCountSchema,
    failed: RecoveryCountSchema,
    pending: RecoveryCountSchema,
  })
  .strict();

const PreviewEventSchema = PreviewEventSchemaRaw.transform((value) =>
  value.kind.type === "load_failed"
    ? { ...value, kind: { ...value.kind, message: safePreviewMessage(value.kind.message) } }
    : value,
);

export type PreviewSessionSnapshot = z.infer<typeof PreviewSessionSnapshotSchema>;
export type PreviewOpenResult = z.infer<typeof PreviewOpenResultSchema>;
export type PreviewRecoveryReport = z.infer<typeof PreviewRecoveryReportSchema>;
export type PreviewEvent = z.infer<typeof PreviewEventSchema>;
export type PreviewViewport = z.infer<typeof PreviewViewportSchema>;
export type PreviewEventListener = (event: PreviewEvent) => void;
export type PreviewUnsubscribe = () => void | Promise<void>;

const PreviewIdentitySchema = z
  .object({
    sessionId: PreviewIdSchema,
    generation: GenerationSchema,
  })
  .strict();

/** bridge 只公开固定 Preview command/event 名称，并允许 Vitest 替换窄边界。 */
export interface PreviewNativeBridge {
  invoke(command: PreviewCommand, args?: Record<string, unknown>): Promise<unknown>;
  listen(event: PreviewEventName, handler: (payload: unknown) => void): Promise<UnlistenFn>;
}

const defaultNativeBridge: PreviewNativeBridge = {
  invoke: (command, args) =>
    invokeNativeCommand(command, args, () => tauriInvoke<unknown>(command, args)),
  listen: async (event, handler) => {
    const unlisten = await tauriListen<unknown>(event, (eventPayload) =>
      handler(eventPayload.payload),
    );
    return unlisten;
  },
};

export type PreviewAdapterErrorCode = "invalid_input" | "invalid_response" | "command_failed";

/** Preview 错误保持稳定且已脱敏，失败的 WebView command 不能回显 URL 或路径数据。 */
export class PreviewAdapterError extends Error {
  /** 只按稳定错误码选择静态文案，禁止把 native exception 作为构造参数透传。 */
  constructor(readonly code: PreviewAdapterErrorCode) {
    super(
      code === "invalid_input"
        ? "预览请求参数无效"
        : code === "invalid_response"
          ? "预览返回数据无效"
          : "预览操作失败",
    );
    this.name = "PreviewAdapterError";
  }
}

/** 解析调用方值时刻意丢弃 Zod 携带输入值的 issue path。 */
function parseInput<T>(schema: z.ZodType<T>, value: unknown): T {
  try {
    return schema.parse(value);
  } catch {
    throw new PreviewAdapterError("invalid_input");
  }
}

/** 在边界解析原生 DTO，畸形 snapshot 不得成为 UI 状态。 */
function parseResult<T>(schema: z.ZodType<T>, value: unknown): T {
  try {
    return schema.parse(value);
  } catch {
    throw new PreviewAdapterError("invalid_response");
  }
}

/** 将原生 rejection 转换为静态文本，避免 WebView 诊断进入 React 状态。 */
function commandFailed(error: unknown): PreviewAdapterError {
  if (error instanceof PreviewAdapterError) return error;
  void error;
  return new PreviewAdapterError("command_failed");
}

/** 调用固定 Preview command，不暴露通用 method/path bridge。 */
async function invokePreview(
  bridge: PreviewNativeBridge,
  command: PreviewCommand,
  args: Record<string, unknown>,
): Promise<unknown> {
  try {
    return await bridge.invoke(command, args);
  } catch (error) {
    throw commandFailed(error);
  }
}

/**
 * typed Preview adapter 只负责 DTO 映射；URL 策略、generation 校验、隔离 WebView
 * 生命周期与事件队列上限仍以 Rust 为权威。
 */
export class TauriPreviewAdapter {
  constructor(private readonly bridge: PreviewNativeBridge = defaultNativeBridge) {}

  /** 只恢复 Rust 已追踪且打开失败未返回 renderer session identity 的子 WebView。 */
  async recoverPending(): Promise<PreviewRecoveryReport> {
    const result = await invokePreview(this.bridge, JA_PREVIEW_COMMANDS.recoverPending, {});
    return parseResult(PreviewRecoveryReportSchema, result);
  }

  /** 打开 HTTP(S) Preview 并返回权威 session identity。 */
  async open(url: string, viewport: PreviewViewport): Promise<PreviewOpenResult> {
    const normalized = normalizePreviewUrl(url);
    if (normalized === undefined) throw new PreviewAdapterError("invalid_input");
    const parsedViewport = parseInput(PreviewViewportSchema, viewport);
    if (!parsedViewport.visible || parsedViewport.width < 1 || parsedViewport.height < 1)
      throw new PreviewAdapterError("invalid_input");
    const result = await invokePreview(this.bridge, JA_PREVIEW_COMMANDS.open, {
      input: { url: normalized, viewport: parsedViewport },
    });
    return parseResult(PreviewOpenResultSchema, result);
  }

  /** 携带显式 generation 导航单个 session，用于拒绝陈旧 callback。 */
  async navigate(
    sessionId: string,
    generation: number,
    url: string,
    source: "user" | "redirect" = "user",
  ): Promise<PreviewSessionSnapshot> {
    const identity = parseInput(PreviewIdentitySchema, { sessionId, generation });
    const normalized = normalizePreviewUrl(url);
    if (normalized === undefined) throw new PreviewAdapterError("invalid_input");
    const input = { ...identity, source, url: normalized };
    const result = await invokePreview(this.bridge, JA_PREVIEW_COMMANDS.navigate, { input });
    return parseResult(PreviewSessionSnapshotSchema, result);
  }

  /** 使用测量后的主窗口矩形定位或隐藏子 WebView，避免 UI 自行猜测原生坐标。 */
  async layout(sessionId: string, viewport: PreviewViewport): Promise<PreviewSessionSnapshot> {
    const input = parseInput(
      z.object({ sessionId: PreviewIdSchema, viewport: PreviewViewportSchema }).strict(),
      { sessionId, viewport },
    );
    const result = await invokePreview(this.bridge, JA_PREVIEW_COMMANDS.layout, { input });
    return parseResult(PreviewSessionSnapshotSchema, result);
  }

  /** 关闭与单个不透明 Preview session 对应的原生 WebView。 */
  async close(sessionId: string): Promise<PreviewSessionSnapshot> {
    const input = parseInput(z.object({ sessionId: PreviewIdSchema }).strict(), { sessionId });
    const result = await invokePreview(this.bridge, JA_PREVIEW_COMMANDS.close, { input });
    return parseResult(PreviewSessionSnapshotSchema, result);
  }

  /** 为重连或 reload 排空有界事件批次，不创建第二套自定义 stream。 */
  async events(sessionId: string, maxEvents = 128): Promise<PreviewEvent[]> {
    const input = parseInput(
      z
        .object({ sessionId: PreviewIdSchema, maxEvents: z.number().int().min(1).max(512) })
        .strict(),
      { sessionId, maxEvents },
    );
    const result = await invokePreview(this.bridge, JA_PREVIEW_COMMANDS.events, { input });
    try {
      return z.array(PreviewEventSchema).max(512).parse(result).map(parsePreviewEvent);
    } catch (error) {
      if (error instanceof PreviewAdapterError) throw error;
      throw new PreviewAdapterError("invalid_response");
    }
  }

  /** reload 或晚到事件后读取当前权威状态，避免依赖本地推断。 */
  async state(sessionId: string): Promise<PreviewSessionSnapshot> {
    const input = parseInput(z.object({ sessionId: PreviewIdSchema }).strict(), { sessionId });
    const result = await invokePreview(this.bridge, JA_PREVIEW_COMMANDS.state, { input });
    return parseResult(PreviewSessionSnapshotSchema, result);
  }

  /** 订阅固定 Preview 事件，并丢弃畸形原生 payload。 */
  async subscribe(listener: PreviewEventListener): Promise<PreviewUnsubscribe> {
    try {
      return await this.bridge.listen(JA_PREVIEW_EVENTS.preview, (payload) => {
        try {
          listener(parsePreviewEvent(payload));
        } catch {
          // 畸形原生事件无法安全关联到 session，必须在 adapter 边界丢弃。
        }
      });
    } catch (error) {
      throw commandFailed(error);
    }
  }
}
