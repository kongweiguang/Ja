// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { invoke as tauriInvoke } from "@tauri-apps/api/core";
import { listen as tauriListen, type UnlistenFn } from "@tauri-apps/api/event";
import { z } from "zod";
import { normalizePreviewUrl, normalizePreviewWebUrl } from "@/shared/validation/previewUrl";
import { invokeNativeCommand } from "./nativeInvoke";

/** 封闭 command/event surface，防止 Preview 页面选择任意 Tauri capability。 */
export const JA_PREVIEW_COMMANDS = {
  recoverPending: "ja_preview_recover_pending",
  open: "ja_preview_open",
  openBlank: "ja_preview_open_blank",
  resolveFile: "ja_preview_resolve_file",
  revealFile: "ja_preview_reveal_file",
  openFile: "ja_preview_open_file",
  navigate: "ja_preview_navigate",
  navigateFile: "ja_preview_navigate_file",
  goBack: "ja_preview_go_back",
  goForward: "ja_preview_go_forward",
  reload: "ja_preview_reload",
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

/** native page snapshot 允许 open_file 的规范 file URI，但拒绝裸路径和任意协议。 */
const PreviewPageUrlSchema = z
  .string()
  .min(1)
  .max(8_192)
  .refine(
    (value) =>
      value === "about:blank" ||
      (/^(?:https?|file):/iu.test(value) && normalizePreviewUrl(value) !== undefined),
    "preview page URL must use http, https, file, or about:blank",
  );

const PreviewWindowSchema = z
  .object({
    label: z.string().min(1).max(128),
    url: PreviewPageUrlSchema,
  })
  .strict();

const PreviewSessionSnapshotSchema = z
  .object({
    id: PreviewIdSchema,
    generation: GenerationSchema,
    status: z.enum(["open", "closed"]),
    load_status: z.enum(["loading", "finished", "failed"]),
    url: PreviewPageUrlSchema,
    title: z.string().max(1_024),
    can_go_back: z.boolean(),
    can_go_forward: z.boolean(),
    window: PreviewWindowSchema,
    dropped_events: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
  })
  .strict();

const PreviewEventKindSchema = z.discriminatedUnion("type", [
  z.object({ type: z.literal("opened"), url: PreviewPageUrlSchema }).strict(),
  z
    .object({
      type: z.literal("navigation_committed"),
      source: z.enum(["user", "redirect"]),
      url: PreviewPageUrlSchema,
    })
    .strict(),
  z.object({ type: z.literal("title_changed"), title: z.string().max(1_024) }).strict(),
  z.object({ type: z.literal("load_failed"), message: z.string().max(4_096) }).strict(),
  z.object({ type: z.literal("load_finished"), url: PreviewPageUrlSchema }).strict(),
  z
    .object({
      type: z.literal("history_changed"),
      can_go_back: z.boolean(),
      can_go_forward: z.boolean(),
    })
    .strict(),
  z.object({ type: z.literal("action_blocked"), action: z.enum(["popup", "download"]) }).strict(),
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
const PreviewFileResolutionSchema = z
  .object({
    canonicalPath: z.string().min(1).max(4_096),
    displayName: z.string().min(1).max(1_024),
    workspaceId: z.string().min(1).max(128).nullable(),
    workspaceRelativePath: z.string().min(1).max(4_096).nullable(),
    withinWorkspace: z.boolean(),
    kind: z.enum(["text", "browser", "unsupported"]),
    mimeType: z.string().min(1).max(256).nullable(),
    fileUrl: PreviewPageUrlSchema,
    content: z.string().max(1_048_576).nullable(),
    truncated: z.boolean(),
    line: z.number().int().min(1).max(Number.MAX_SAFE_INTEGER).nullable(),
    column: z.number().int().min(1).max(Number.MAX_SAFE_INTEGER).nullable(),
    readOnly: z.boolean(),
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
export type PreviewFileResolution = z.infer<typeof PreviewFileResolutionSchema>;
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

export type PreviewAdapterErrorCode =
  | "invalid_input"
  | "invalid_response"
  | "command_failed"
  | "file_target_invalid"
  | "file_not_found"
  | "file_unreadable"
  | "file_is_directory"
  | "workspace_required"
  | "workspace_unavailable"
  | "file_unsupported"
  | "file_reveal_failed";

/** Preview 错误保持稳定且已脱敏，失败的 WebView command 不能回显 URL 或路径数据。 */
export class PreviewAdapterError extends Error {
  /** 只按稳定错误码选择静态文案，禁止把 native exception 作为构造参数透传。 */
  constructor(readonly code: PreviewAdapterErrorCode) {
    super(
      code === "invalid_input"
        ? "预览请求参数无效"
        : code === "invalid_response"
          ? "预览返回数据无效"
          : code === "file_target_invalid"
            ? "文件路径无效。"
            : code === "file_not_found"
              ? "文件不存在或已被移动。"
              : code === "file_unreadable"
                ? "当前没有读取此文件的权限。"
                : code === "file_is_directory"
                  ? "这是一个文件夹，无法在浏览器中打开。"
                  : code === "workspace_required"
                    ? "相对路径需要先打开工作区。"
                    : code === "workspace_unavailable"
                      ? "当前工作区已关闭，请重新打开后重试。"
                      : code === "file_unsupported"
                        ? "此文件类型暂不支持在浏览器中打开。"
                        : code === "file_reveal_failed"
                          ? "无法启动文件资源管理器，请稍后重试。"
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
  if (typeof error === "object" && error !== null && "code" in error) {
    const code = (error as { code?: unknown }).code;
    const mapped: Readonly<Record<string, PreviewAdapterErrorCode>> = {
      FileTargetInvalid: "file_target_invalid",
      FileNotFound: "file_not_found",
      FileUnreadable: "file_unreadable",
      FileIsDirectory: "file_is_directory",
      WorkspaceRequired: "workspace_required",
      WorkspaceUnavailable: "workspace_unavailable",
      LocalFileUnsupported: "file_unsupported",
      FileRevealFailed: "file_reveal_failed",
    };
    if (typeof code === "string" && mapped[code] !== undefined)
      return new PreviewAdapterError(mapped[code]);
  }
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
    const normalized = normalizePreviewWebUrl(url);
    if (normalized === undefined) throw new PreviewAdapterError("invalid_input");
    const parsedViewport = parseInput(PreviewViewportSchema, viewport);
    if (parsedViewport.width < 1 || parsedViewport.height < 1)
      throw new PreviewAdapterError("invalid_input");
    const result = await invokePreview(this.bridge, JA_PREVIEW_COMMANDS.open, {
      input: { url: normalized, viewport: parsedViewport },
    });
    return parseResult(PreviewOpenResultSchema, result);
  }

  /** 新建 about:blank session，使 tab identity 仍由 Rust 签发且没有 Ja capability。 */
  async openBlank(viewport: PreviewViewport): Promise<PreviewOpenResult> {
    const parsedViewport = parseInput(PreviewViewportSchema, viewport);
    if (parsedViewport.width < 1 || parsedViewport.height < 1)
      throw new PreviewAdapterError("invalid_input");
    const result = await invokePreview(this.bridge, JA_PREVIEW_COMMANDS.openBlank, {
      input: { viewport: parsedViewport },
    });
    return parseResult(PreviewOpenResultSchema, result);
  }

  /** 显式点击文件目标后才请求 Rust 解析；renderer 不自行 stat、读取或授权本机路径。 */
  async resolveFile(
    target: string,
    workspaceId?: string,
    line?: number,
    column?: number,
  ): Promise<PreviewFileResolution> {
    const input = parseInput(
      z
        .object({
          target: z.string().trim().min(1).max(4_096),
          workspaceId: z.string().min(1).max(128).optional(),
          line: z.number().int().min(1).max(Number.MAX_SAFE_INTEGER).optional(),
          column: z.number().int().min(1).max(Number.MAX_SAFE_INTEGER).optional(),
        })
        .strict(),
      { target, workspaceId, line, column },
    );
    const result = await invokePreview(this.bridge, JA_PREVIEW_COMMANDS.resolveFile, { input });
    return parseResult(PreviewFileResolutionSchema, result);
  }

  /** 仅在显式 Ctrl+点击后请求原生定位，路径不会成为 renderer 可选择的进程参数。 */
  async revealFile(target: string, workspaceId?: string): Promise<void> {
    const input = parseInput(
      z
        .object({
          target: z.string().trim().min(1).max(4_096),
          workspaceId: z.string().min(1).max(128).optional(),
        })
        .strict(),
      { target, workspaceId },
    );
    await invokePreview(this.bridge, JA_PREVIEW_COMMANDS.revealFile, { input });
  }

  /** 再次将文件路径交给 Rust 解析并在隔离子 WebView 中打开，不采用 renderer 的 file_url。 */
  async openFile(
    target: string,
    workspaceId: string | undefined,
    viewport: PreviewViewport,
  ): Promise<PreviewOpenResult> {
    const input = parseInput(
      z
        .object({
          target: z.string().trim().min(1).max(4_096),
          workspaceId: z.string().min(1).max(128).optional(),
          viewport: PreviewViewportSchema,
        })
        .strict(),
      { target, workspaceId, viewport },
    );
    if (input.viewport.width < 1 || input.viewport.height < 1)
      throw new PreviewAdapterError("invalid_input");
    const result = await invokePreview(this.bridge, JA_PREVIEW_COMMANDS.openFile, { input });
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
    const normalized = normalizePreviewWebUrl(url);
    if (normalized === undefined) throw new PreviewAdapterError("invalid_input");
    const input = { ...identity, source, url: normalized };
    const result = await invokePreview(this.bridge, JA_PREVIEW_COMMANDS.navigate, { input });
    return parseResult(PreviewSessionSnapshotSchema, result);
  }

  /** 在现有 tab 中显式打开本机文件；Rust 会重新解析路径并执行 workspace/权限校验。 */
  async navigateFile(
    sessionId: string,
    generation: number,
    target: string,
    workspaceId?: string,
  ): Promise<PreviewSessionSnapshot> {
    const input = parseInput(
      z
        .object({
          sessionId: PreviewIdSchema,
          generation: GenerationSchema,
          target: z.string().trim().min(1).max(4_096),
          workspaceId: z.string().min(1).max(128).optional(),
        })
        .strict(),
      { sessionId, generation, target, workspaceId },
    );
    const result = await invokePreview(this.bridge, JA_PREVIEW_COMMANDS.navigateFile, { input });
    return parseResult(PreviewSessionSnapshotSchema, result);
  }

  /** 原生 WebView 决定真实回退能力；React 不维护与地址栏不同步的历史栈。 */
  async goBack(sessionId: string, generation: number): Promise<PreviewSessionSnapshot> {
    return this.navigateHistory(JA_PREVIEW_COMMANDS.goBack, sessionId, generation);
  }

  /** 前进能力由对应原生 WebView 的历史栈提供。 */
  async goForward(sessionId: string, generation: number): Promise<PreviewSessionSnapshot> {
    return this.navigateHistory(JA_PREVIEW_COMMANDS.goForward, sessionId, generation);
  }

  /** reload 只刷新当前页面，不制造一条 React 侧历史记录。 */
  async reload(sessionId: string, generation: number): Promise<PreviewSessionSnapshot> {
    return this.navigateHistory(JA_PREVIEW_COMMANDS.reload, sessionId, generation);
  }

  /** 将三个原生历史动作收敛到一份固定 identity DTO 与 snapshot 校验。 */
  private async navigateHistory(
    command:
      | typeof JA_PREVIEW_COMMANDS.goBack
      | typeof JA_PREVIEW_COMMANDS.goForward
      | typeof JA_PREVIEW_COMMANDS.reload,
    sessionId: string,
    generation: number,
  ): Promise<PreviewSessionSnapshot> {
    const identity = parseInput(PreviewIdentitySchema, { sessionId, generation });
    const result = await invokePreview(this.bridge, command, { input: identity });
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
