// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { z } from "zod";
import {
  CursorSchema,
  ThreadIdSchema,
  ThreadReadResultSchema,
  MessageContentReadResultSchema,
  ThreadObservationParamsSchema,
  ThreadObservationResultSchema,
  ThreadUsageSummarySchema,
  ThreadMcpStatusResultSchema,
  ParamsSchemaByMethod,
  ThreadSchema,
  WorkspaceIdSchema,
  WorkspaceKindSchema,
  WorkspaceSchema,
  ProviderIdSchema,
  ModelIdSchema,
  ReasoningLevelSchema,
  AccessModeSchema,
  ThreadDiscoveryItemSchema,
  ThreadDiscoveryParamsSchema,
  ThreadDiscoveryResultSchema,
  type Thread,
  type ThreadReadResult,
  type ThreadUsageSummary,
} from "../protocol/protocol";
import { CollaborationModeSchema } from "../protocol/goal";
import {
  defaultNativeBridge,
  normalizeRuntimeError,
  RuntimeHostError,
  type RuntimeNativeBridge,
} from "./runtime";

/** 固定 Tauri command 映射冻结的 history 能力，不向 React 暴露通用 RPC tunnel。 */
export const JA_HISTORY_COMMANDS = {
  // 此 command 与现有文件 open-with command `ja_workspace_open` 语义不同，不能复用名称。
  workspaceOpen: "ja_runtime_workspace_open",
  workspaceList: "ja_workspace_list",
  threadCreate: "ja_thread_create",
  threadDiscover: "ja_thread_discover",
  threadList: "ja_thread_list",
  threadSearch: "ja_thread_search",
  threadRead: "ja_thread_read",
  messageContentRead: "ja_thread_message_content_read",
  threadObserve: "ja_thread_observe",
  threadUnobserve: "ja_thread_unobserve",
  threadUsageRead: "ja_thread_usage_read",
  threadMcpRead: "ja_thread_mcp_read",
  threadRename: "ja_thread_rename",
  threadPin: "ja_thread_pin",
  threadSeen: "ja_thread_seen",
  threadPreferencesUpdate: "ja_thread_preferences_update",
  threadArchive: "ja_thread_archive",
  threadRestore: "ja_thread_restore",
  threadDelete: "ja_thread_delete",
  threadCompact: "ja_thread_compact",
  threadCompactCancel: "ja_thread_compact_cancel",
} as const;

const PageInputSchema = z
  .object({
    cursor: CursorSchema.optional(),
    limit: z.number().int().min(1).max(200).optional(),
  })
  .strict();
const WorkspaceListInputSchema = PageInputSchema.extend({
  kind: WorkspaceKindSchema.optional(),
}).strict();
const WorkspaceOpenInputSchema = z
  .object({
    cwd: z
      .string()
      .min(1)
      .max(4_096)
      .refine((value) => !value.includes("\u0000"), "cwd contains NUL"),
    displayName: z.string().min(1).max(1_024).optional(),
  })
  .strict();
const ThreadCreateInputSchema = z
  .object({
    cwd: z.string().min(1).max(4_096).optional(),
    title: z.string().min(1).max(512),
    providerId: ProviderIdSchema,
    modelId: ModelIdSchema,
    reasoningLevel: ReasoningLevelSchema.nullable(),
    accessMode: AccessModeSchema,
    collaborationMode: CollaborationModeSchema,
  })
  .strict();
const ThreadListInputSchema = z.union([
  PageInputSchema.extend({ workspaceId: WorkspaceIdSchema }).strict(),
  PageInputSchema.extend({ workspaceKind: z.literal("session") }).strict(),
]);
/** 全局会话发现保持独立输入形状；scope 是与普通 Workspace 列表互斥的语义判别字段。 */
const ThreadDiscoverInputSchema = ThreadDiscoveryParamsSchema;
const ThreadSearchInputSchema = z.union([
  PageInputSchema.extend({ workspaceId: WorkspaceIdSchema, query: z.string().max(256) }).strict(),
  PageInputSchema.extend({
    workspaceKind: z.literal("session"),
    query: z.string().max(256),
  }).strict(),
]);
const ThreadReadInputSchema = z
  .object({
    threadId: ThreadIdSchema,
    cursor: CursorSchema.optional(),
    limit: z.number().int().min(1).max(200).optional(),
    tail: z.literal(true).optional(),
  })
  .strict();
/** 累计账本只按 Thread 身份读取；React 不提交范围、过滤器或计价参数。 */
const ThreadUsageReadInputSchema = z.object({ threadId: ThreadIdSchema }).strict();
const ThreadMcpReadInputSchema = ParamsSchemaByMethod["thread/mcp/read"];
const ThreadMutationInputSchema = z
  .object({
    threadId: ThreadIdSchema,
    expectedThreadRevision: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
  })
  .strict();
const ThreadPinInputSchema = ThreadMutationInputSchema.extend({ pinned: z.boolean() }).strict();
const ThreadRenameInputSchema = z
  .object({
    threadId: ThreadIdSchema,
    title: z.string().min(1).max(512),
    expectedThreadRevision: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
  })
  .strict();
const ThreadPreferencesUpdateInputSchema = z
  .object({
    threadId: ThreadIdSchema,
    providerId: ProviderIdSchema,
    modelId: ModelIdSchema,
    reasoningLevel: ReasoningLevelSchema.nullable(),
    accessMode: AccessModeSchema,
    collaborationMode: CollaborationModeSchema,
    expectedThreadRevision: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
  })
  .strict();
const AcceptedResultSchema = z.object({ accepted: z.literal(true) }).strict();
const ThreadCompactCancelResultSchema = z.object({ accepted: z.boolean() }).strict();
const ThreadCompactResultSchema = z
  .object({
    outcome: z.enum(["compacted", "unchanged"]),
    compactionId: z
      .string()
      .regex(/^cmp_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
      .max(100)
      .nullable(),
    checkpointId: z
      .string()
      .regex(/^checkpoint_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
      .max(107)
      .nullable(),
    threadRevision: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
    inputTokensBefore: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
    inputTokensAfter: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
  })
  .strict()
  .superRefine((value, context) => {
    const compacted = value.outcome === "compacted";
    if (compacted !== (value.compactionId !== null && value.checkpointId !== null)) {
      context.addIssue({ code: "custom", path: ["compactionId"], message: "invalid identities" });
    }
    if (
      compacted
        ? value.inputTokensAfter >= value.inputTokensBefore
        : value.inputTokensAfter !== value.inputTokensBefore
    ) {
      context.addIssue({
        code: "custom",
        path: ["inputTokensAfter"],
        message: "invalid token result",
      });
    }
  });
const WorkspaceListResultSchema = z
  .object({ items: z.array(WorkspaceSchema).max(200), nextCursor: CursorSchema.nullable() })
  .strict();
const ThreadListResultSchema = z
  .object({ items: z.array(ThreadSchema).max(200), nextCursor: CursorSchema.nullable() })
  .strict();

export type HistoryWorkspace = z.infer<typeof WorkspaceSchema>;
export type HistoryWorkspaceOpenInput = z.infer<typeof WorkspaceOpenInputSchema>;
export type HistoryThread = Thread;
export type HistoryWorkspaceListInput = z.infer<typeof WorkspaceListInputSchema>;
export type HistoryThreadCreateInput = z.infer<typeof ThreadCreateInputSchema>;
export type HistoryThreadListInput = z.infer<typeof ThreadListInputSchema>;
export type HistoryThreadDiscoverInput = z.infer<typeof ThreadDiscoverInputSchema>;
export type HistoryThreadSearchInput = z.infer<typeof ThreadSearchInputSchema>;
export type HistoryThreadReadInput = z.infer<typeof ThreadReadInputSchema>;
export type HistoryMessageContentReadInput = z.infer<
  (typeof ParamsSchemaByMethod)["thread/message-content/read"]
>;
export type HistoryMessageContentReadResult = z.infer<typeof MessageContentReadResultSchema>;
export type HistoryThreadObservationInput = z.infer<typeof ThreadObservationParamsSchema>;
export type HistoryThreadObservationResult = z.infer<typeof ThreadObservationResultSchema>;
export type HistoryThreadUsageReadInput = z.infer<typeof ThreadUsageReadInputSchema>;
export type HistoryThreadMcpReadInput = z.infer<typeof ThreadMcpReadInputSchema>;
export type HistoryThreadRenameInput = z.infer<typeof ThreadRenameInputSchema>;
export type HistoryThreadPreferencesUpdateInput = z.infer<
  typeof ThreadPreferencesUpdateInputSchema
>;
export type HistoryThreadMutationInput = z.infer<typeof ThreadMutationInputSchema>;
export type HistoryThreadPinInput = z.infer<typeof ThreadPinInputSchema>;
export type HistoryThreadCompactResult = z.infer<typeof ThreadCompactResultSchema>;
export interface HistoryThreadListResult {
  items: HistoryThread[];
  nextCursor?: string | null;
}
export type HistoryThreadDiscoveryItem = z.infer<typeof ThreadDiscoveryItemSchema>;
export interface HistoryThreadDiscoverResult {
  items: HistoryThreadDiscoveryItem[];
  nextCursor?: string | null;
}
export type HistoryThreadReadResult = ThreadReadResult;
export type HistoryThreadUsageSummary = ThreadUsageSummary;
export type HistoryThreadMcpStatusResult = z.infer<typeof ThreadMcpStatusResultSchema>;

export interface HistoryWorkspaceListResult {
  items: HistoryWorkspace[];
  nextCursor?: string | null;
}

/** History adapter 只需要 invoke；frame listener 仍由 RuntimeHost 独占。 */
export type HistoryNativeBridge = Pick<RuntimeNativeBridge, "invoke">;

export interface HistoryAdapter {
  /** 注入式测试 bridge 可省略此能力；生产 adapter 必须始终实现。 */
  workspaceOpen?: (input: HistoryWorkspaceOpenInput) => Promise<HistoryWorkspace>;
  workspaceList(input?: HistoryWorkspaceListInput): Promise<HistoryWorkspaceListResult>;
  threadCreate(input: HistoryThreadCreateInput): Promise<HistoryThread>;
  /** 全局发现是只读目录能力，保持可选以兼容不需要会话发现的注入式测试 adapter。 */
  threadDiscover?: (input: HistoryThreadDiscoverInput) => Promise<HistoryThreadDiscoverResult>;
  threadList(input: HistoryThreadListInput): Promise<HistoryThreadListResult>;
  threadSearch(input: HistoryThreadSearchInput): Promise<HistoryThreadListResult>;
  threadRead(input: HistoryThreadReadInput): Promise<HistoryThreadReadResult>;
  /** 只读能力；注入式测试 adapter 可省略，生产始终由 Java 权威消息分页提供。 */
  messageContentRead?: (
    input: HistoryMessageContentReadInput,
  ) => Promise<HistoryMessageContentReadResult>;
  threadObserve(input: HistoryThreadObservationInput): Promise<HistoryThreadObservationResult>;
  threadUnobserve(input: HistoryThreadObservationInput): Promise<HistoryThreadObservationResult>;
  /** 用量在旧注入式测试 adapter 中可缺席；生产 adapter 固定提供此只读能力。 */
  threadUsageRead?: (input: HistoryThreadUsageReadInput) => Promise<HistoryThreadUsageSummary>;
  /** MCP status is queried only while the header popover is open. */
  threadMcpRead?: (input: HistoryThreadMcpReadInput) => Promise<HistoryThreadMcpStatusResult>;
  threadRename(input: HistoryThreadRenameInput): Promise<HistoryThread>;
  threadPreferencesUpdate(input: HistoryThreadPreferencesUpdateInput): Promise<HistoryThread>;
  threadPin(input: HistoryThreadPinInput): Promise<HistoryThread>;
  threadSeen(input: HistoryThreadMutationInput): Promise<HistoryThread>;
  threadArchive(input: HistoryThreadMutationInput): Promise<HistoryThread>;
  threadRestore(input: HistoryThreadMutationInput): Promise<HistoryThread>;
  threadDelete?: (input: HistoryThreadMutationInput) => Promise<void>;
  threadCompact(input: HistoryThreadMutationInput): Promise<HistoryThreadCompactResult>;
  threadCompactCancel(input: { threadId: string }): Promise<{ accepted: boolean }>;
}

/** 拒绝畸形输入，且不得通过 Zod 诊断回显标识或路径片段。 */
function parseInput<T>(schema: z.ZodType<T>, input: unknown): T {
  try {
    return schema.parse(input);
  } catch {
    throw new RuntimeHostError("INVALID_INPUT", "请求参数无效", false);
  }
}

/** 只调用 allow-list command，并在 React 看到结果前完成整体校验。 */
async function invokeHistory<I, O>(
  bridge: HistoryNativeBridge,
  command: string,
  input: unknown,
  inputSchema: z.ZodType<I>,
  resultSchema: z.ZodType<O>,
): Promise<O> {
  const parsedInput = parseInput(inputSchema, input);
  try {
    const result = await bridge.invoke<unknown>(command, { input: parsedInput });
    return resultSchema.parse(result);
  } catch (error) {
    if (error instanceof RuntimeHostError) throw normalizeRuntimeError(error);
    if (error instanceof z.ZodError)
      throw new RuntimeHostError("RUNTIME_UNAVAILABLE", "运行时暂不可用", true);
    throw normalizeRuntimeError(error);
  }
}

/** typed history adapter 使用严格 cursor 与 revision 边界，禁止隐式默认身份。 */
export class TauriHistoryAdapter implements HistoryAdapter {
  constructor(private readonly bridge: HistoryNativeBridge = defaultNativeBridge) {}

  /** 请求 app-server 规范化 cwd 并分配 durable workspace identity，renderer 不自行生成。 */
  async workspaceOpen(input: HistoryWorkspaceOpenInput): Promise<HistoryWorkspace> {
    const parsedInput = parseInput(WorkspaceOpenInputSchema, input);
    // 原生目录选择器是显式信任动作；该策略不能下放给任意 renderer 输入，
    // 同时仍需满足 Rust 的封闭 workspace admission DTO。
    return invokeHistory(
      this.bridge,
      JA_HISTORY_COMMANDS.workspaceOpen,
      { ...parsedInput, trust: "trusted" },
      WorkspaceOpenInputSchema.extend({ trust: z.literal("trusted") }),
      WorkspaceSchema,
    );
  }

  /** 按服务端 kind 过滤后分页列目录，避免 session 根目录挤占项目目录首屏。 */
  async workspaceList(input: HistoryWorkspaceListInput = {}): Promise<HistoryWorkspaceListResult> {
    return invokeHistory(
      this.bridge,
      JA_HISTORY_COMMANDS.workspaceList,
      input,
      WorkspaceListInputSchema,
      WorkspaceListResultSchema,
    );
  }

  /** 从 cwd 与显式 Provider/Model 选择创建 durable thread；服务端冻结并持久化偏好。 */
  async threadCreate(input: HistoryThreadCreateInput): Promise<HistoryThread> {
    return invokeHistory(
      this.bridge,
      JA_HISTORY_COMMANDS.threadCreate,
      input,
      ThreadCreateInputSchema,
      ThreadSchema,
    );
  }

  /** 只读取跨 Workspace 的最小会话目录，不触发目标 Thread、正文读取或状态同步。 */
  threadDiscover(input: HistoryThreadDiscoverInput): Promise<HistoryThreadDiscoverResult> {
    return invokeHistory(
      this.bridge,
      JA_HISTORY_COMMANDS.threadDiscover,
      input,
      ThreadDiscoverInputSchema,
      ThreadDiscoveryResultSchema,
    );
  }

  /** 为 Rust 显式 runtime binding 加载有界且由服务端排序的 thread page。 */
  threadList(input: HistoryThreadListInput): Promise<HistoryThreadListResult> {
    return invokeHistory(
      this.bridge,
      JA_HISTORY_COMMANDS.threadList,
      input,
      ThreadListInputSchema,
      ThreadListResultSchema,
    );
  }

  /** 以当前 Workspace 和标题片段调用服务端搜索；空 query 保留最近会话语义。 */
  threadSearch(input: HistoryThreadSearchInput): Promise<HistoryThreadListResult> {
    return invokeHistory(
      this.bridge,
      JA_HISTORY_COMMANDS.threadSearch,
      input,
      ThreadSearchInputSchema,
      ThreadListResultSchema,
    );
  }

  /** 读取一页完整结果；cursor 非 null 时调用方必须视为 resync 尚未完成。 */
  threadRead(input: HistoryThreadReadInput): Promise<HistoryThreadReadResult> {
    return invokeHistory(
      this.bridge,
      JA_HISTORY_COMMANDS.threadRead,
      input,
      ThreadReadInputSchema,
      ThreadReadResultSchema,
    );
  }

  /** Unicode 字符分页只接受已提交消息身份；完整正文不进入 thread/read 大帧。 */
  messageContentRead(
    input: HistoryMessageContentReadInput,
  ): Promise<HistoryMessageContentReadResult> {
    return invokeHistory(
      this.bridge,
      JA_HISTORY_COMMANDS.messageContentRead,
      input,
      ParamsSchemaByMethod["thread/message-content/read"],
      MessageContentReadResultSchema,
    );
  }

  /** 先订阅后读取基线，ACK 身份必须与请求一致才能把 Thread 标为实时可见。 */
  async threadObserve(
    input: HistoryThreadObservationInput,
  ): Promise<HistoryThreadObservationResult> {
    const result = await invokeHistory(
      this.bridge,
      JA_HISTORY_COMMANDS.threadObserve,
      input,
      ThreadObservationParamsSchema,
      ThreadObservationResultSchema,
    );
    if (result.threadId !== input.threadId)
      throw new RuntimeHostError("RUNTIME_UNAVAILABLE", "运行时暂不可用", true);
    return result;
  }

  /** 隐藏会话只释放这条连接的订阅；后台回合与持久会话由 Java 继续持有。 */
  async threadUnobserve(
    input: HistoryThreadObservationInput,
  ): Promise<HistoryThreadObservationResult> {
    const result = await invokeHistory(
      this.bridge,
      JA_HISTORY_COMMANDS.threadUnobserve,
      input,
      ThreadObservationParamsSchema,
      ThreadObservationResultSchema,
    );
    if (result.threadId !== input.threadId)
      throw new RuntimeHostError("RUNTIME_UNAVAILABLE", "运行时暂不可用", true);
    return result;
  }

  /** 读取完整账本时不加载 Timeline 页；原生边界会拒绝畸形或错配响应。 */
  threadUsageRead(input: HistoryThreadUsageReadInput): Promise<HistoryThreadUsageSummary> {
    return invokeHistory(
      this.bridge,
      JA_HISTORY_COMMANDS.threadUsageRead,
      input,
      ThreadUsageReadInputSchema,
      ThreadUsageSummarySchema,
    );
  }

  /** Read the real thread-scoped MCP projection; the renderer cannot enumerate configuration secrets. */
  threadMcpRead(input: HistoryThreadMcpReadInput): Promise<HistoryThreadMcpStatusResult> {
    return invokeHistory(
      this.bridge,
      JA_HISTORY_COMMANDS.threadMcpRead,
      input,
      ThreadMcpReadInputSchema,
      ThreadMcpStatusResultSchema,
    );
  }

  /** 用 Thread revision CAS 设置人工标题，并返回服务端提交后的完整 Thread。 */
  threadRename(input: HistoryThreadRenameInput): Promise<HistoryThread> {
    return invokeHistory(
      this.bridge,
      JA_HISTORY_COMMANDS.threadRename,
      input,
      ThreadRenameInputSchema,
      ThreadSchema,
    );
  }

  /** 整体更新运行偏好；不打断在途请求，并由同一 Turn 的下一 Provider 请求读取。 */
  threadPreferencesUpdate(input: HistoryThreadPreferencesUpdateInput): Promise<HistoryThread> {
    return invokeHistory(
      this.bridge,
      JA_HISTORY_COMMANDS.threadPreferencesUpdate,
      input,
      ThreadPreferencesUpdateInputSchema,
      ThreadSchema,
    );
  }

  /** 以显式目标状态提交置顶 CAS；完整返回值是 UI 重排和后续 CAS 的唯一依据。 */
  threadPin(input: HistoryThreadPinInput): Promise<HistoryThread> {
    return invokeHistory(
      this.bridge,
      JA_HISTORY_COMMANDS.threadPin,
      input,
      ThreadPinInputSchema,
      ThreadSchema,
    );
  }

  /** 只确认服务端当前 latest Turn 已实际呈现；完整投影防止 Renderer 本地伪造已读边界。 */
  threadSeen(input: HistoryThreadMutationInput): Promise<HistoryThread> {
    return invokeHistory(
      this.bridge,
      JA_HISTORY_COMMANDS.threadSeen,
      input,
      ThreadMutationInputSchema,
      ThreadSchema,
    );
  }

  /** 只归档一个精确 thread revision，不提供 active-thread fallback。 */
  threadArchive(input: HistoryThreadMutationInput): Promise<HistoryThread> {
    return invokeHistory(
      this.bridge,
      JA_HISTORY_COMMANDS.threadArchive,
      input,
      ThreadMutationInputSchema,
      ThreadSchema,
    );
  }

  /** 恢复归档会话并接收服务端强制未置顶的完整投影。 */
  threadRestore(input: HistoryThreadMutationInput): Promise<HistoryThread> {
    return invokeHistory(
      this.bridge,
      JA_HISTORY_COMMANDS.threadRestore,
      input,
      ThreadMutationInputSchema,
      ThreadSchema,
    );
  }

  /** 只删除一个精确 thread revision；调用方必须先读取该 revision。 */
  async threadDelete(input: HistoryThreadMutationInput): Promise<void> {
    await invokeHistory(
      this.bridge,
      JA_HISTORY_COMMANDS.threadDelete,
      input,
      ThreadMutationInputSchema,
      AcceptedResultSchema,
    );
  }

  /** 只转发精确 Thread revision 并校验完整结果；压缩策略和 Provider 配置继续由 Java 独占。 */
  threadCompact(input: HistoryThreadMutationInput): Promise<HistoryThreadCompactResult> {
    return invokeHistory(
      this.bridge,
      JA_HISTORY_COMMANDS.threadCompact,
      input,
      ThreadMutationInputSchema,
      ThreadCompactResultSchema,
    );
  }

  /** 停止意图只向当前连接发出，压缩本身的最终结果仍由原 Promise 和事件确认。 */
  threadCompactCancel(input: { threadId: string }): Promise<{ accepted: boolean }> {
    return invokeHistory(
      this.bridge,
      JA_HISTORY_COMMANDS.threadCompactCancel,
      input,
      z.object({ threadId: ThreadIdSchema }).strict(),
      ThreadCompactCancelResultSchema,
    );
  }
}

/** 使用 Tauri 固定 invoke bridge 创建生产 adapter，不泄漏通用调用能力。 */
export function createHistoryAdapter(): HistoryAdapter {
  return new TauriHistoryAdapter();
}
