// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { z } from "zod";
import packageJson from "../../../../../package.json";
import {
  fingerprintReadyToken,
  forEachReadyTokenCandidate,
  READY_TOKEN_PATTERN,
} from "./readyToken";
import { ConfigDocumentSchema } from "./configDocument";
import {
  CollaborationModeSchema,
  GoalIdSchema,
  GoalActivityParamsSchema,
  GoalChangedParamsSchema,
  GoalParamsSchemaByMethod,
  GoalResultSchemaByMethod,
  PlanChangedParamsSchema,
} from "./goal";
import {
  InteractionChangedParamsSchema,
  InteractionParamsSchemaByMethod,
  InteractionResultSchemaByMethod,
} from "./interaction";

/** 首个版本刻意只接受一个精确 wire revision，禁止隐式兼容分支。 */
/** Ja v1 的配置归 app-server 所有；客户端只交换意图和投影。 */
export const JA_PROTOCOL_MAJOR = 1 as const;
export const JA_PROTOCOL_MINOR = 0 as const;

const MAX_SAFE_INTEGER = Number.MAX_SAFE_INTEGER;
/** 不依赖控制字符正则就拒绝 framing control，避免不可见规则被 lint 绕过。 */
const noControlCharacters = (value: string): boolean =>
  [...value].every((character) => {
    const code = character.codePointAt(0) ?? 0;
    return code !== 0 && code !== 10 && code !== 13;
  });

/** Task 名称和幂等键会进入树路径、日志与唯一性比较，因此拒绝完整 C0/C1 控制字符集。 */
const noIsoControlCharacters = (value: string): boolean =>
  [...value].every((character) => {
    const code = character.codePointAt(0) ?? 0;
    return !((code >= 0 && code <= 31) || (code >= 127 && code <= 159));
  });
const noNulCharacters = (value: string): boolean => !value.includes("\u0000");
const noAsciiControlCharacters = (value: string): boolean =>
  [...value].every((character) => {
    const code = character.codePointAt(0) ?? 0;
    return code > 0x1f && code !== 0x7f;
  });

/** 在 Java、Rust 与 TypeScript 间保持不透明 id 稳定，同时拒绝主机路径。 */
function prefixedId(prefix: string, maximum: number): z.ZodString {
  return z
    .string()
    .regex(new RegExp(`^${prefix}[A-Za-z0-9][A-Za-z0-9._-]{0,95}$`))
    .max(maximum);
}

const ClientRequestIdSchema = z
  .string()
  .regex(/^c:[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(98);
/** JA-RPC v1 只允许客户端发起请求；Java 不反向发起 Tool 请求。 */
const RpcRequestIdSchema = ClientRequestIdSchema;
export const WorkspaceIdSchema = prefixedId("ws_", 99);
export const ThreadIdSchema = prefixedId("thr_", 100);
const TurnIdSchema = prefixedId("turn_", 101);
const AttachmentIdSchema = prefixedId("att_", 128);
const ItemIdSchema = prefixedId("item_", 101);
const CallIdSchema = prefixedId("call_", 101);
const ApprovalIdSchema = prefixedId("appr_", 101);
const ProviderRequestIdSchema = prefixedId("request_", 103);
export const ProviderIdSchema = prefixedId("provider_", 128);
export const ModelIdSchema = prefixedId("model_", 128);
export const ReasoningLevelSchema = z.enum([
  "off",
  "minimal",
  "low",
  "medium",
  "high",
  "xhigh",
  "max",
]);
export const AccessModeSchema = z.enum(["approval_required", "full_access"]);
const McpIdSchema = prefixedId("mcp_", 100);
const SkillIdSchema = prefixedId("skill_", 101);
const EventIdSchema = prefixedId("evt_", 100);
const CompactionIdSchema = prefixedId("cmp_", 100);
const CheckpointIdSchema = prefixedId("checkpoint_", 107);
const TaskSeedIdSchema = prefixedId("seed_", 100);
const TaskMessageIdSchema = prefixedId("msg_", 100);
const TaskObservationIdSchema = prefixedId("observe_", 103);
const TaskCursorSchema = z
  .string()
  .regex(/^task:[0-9]+:[0-9]+$/)
  .max(256);
const RuntimeFeaturesSchema = z.tuple([
  z.literal("task_threads_v1"),
  z.literal("plan_goal_v1"),
  z.literal("interaction_v1"),
]);
export const ServerInstanceIdSchema = prefixedId("srv_", 100);
export const RevisionSchema = z.number().int().min(0).max(MAX_SAFE_INTEGER);
export const CursorSchema = z
  .string()
  .regex(/^[A-Za-z0-9._~-]{1,256}$/)
  .max(256);
const TimestampSchema = z.string().datetime({ offset: true }).max(64);
export const ReadyTokenSchema = z.string().regex(READY_TOKEN_PATTERN);
const BoundedTextSchema = z.string().max(1_048_576).refine(noNulCharacters, "text contains NUL");
const PreviewTextSchema = z.string().max(32_768).refine(noNulCharacters, "preview contains NUL");
export const SafeNameSchema = z
  .string()
  .min(1)
  .max(512)
  .refine(noControlCharacters, "name contains a line/control delimiter");
const SafeIdentifierSchema = z
  .string()
  .regex(/^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$/)
  .max(256);
const TurnStateSchema = z.enum([
  "queued",
  "running",
  "waiting_approval",
  "suspended",
  "completed",
  "failed",
  "cancelled",
]);
const TerminalStateSchema = z.enum(["completed", "failed", "cancelled"]);
const ToolOutcomeSchema = z.enum(["succeeded", "failed", "cancelled"]);
const ApprovalDecisionSchema = z.enum(["approve", "deny"]);

const SecretValueSchema = z
  .string()
  .min(1)
  .max(8_192)
  .refine(noAsciiControlCharacters, "secret contains a control character");
const CredentialRefSchema = z
  .string()
  .regex(/^cred_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(100);
const JsonObjectSchema = z.record(z.string(), z.unknown());

export const ClientMethodSchema = z.enum([
  "runtime/initialize",
  "runtime/health",
  "runtime/shutdown",
  "workspace/open",
  "workspace/open-general",
  "workspace/list",
  "workspace/path/search",
  "workspace/set-trust",
  "workspace/unregister",
  "thread/create",
  "thread/list",
  "thread/search",
  "thread/read",
  "thread/rename",
  "thread/pin",
  "thread/seen",
  "thread/preferences/update",
  "thread/archive",
  "thread/restore",
  "thread/delete",
  "thread/compact",
  "interaction/read",
  "interaction/observe",
  "interaction/unobserve",
  "interaction/draft/save",
  "interaction/respond",
  "interaction/cancel",
  "goal/read",
  "goal/events/read",
  "goal/observe",
  "goal/unobserve",
  "plan/read",
  "plan/current/read",
  "plan/revisions/list",
  "goal/evidence/list",
  "goal/create",
  "goal/plan/attach",
  "goal/plan/detach",
  "goal/pause",
  "goal/resume",
  "goal/stop",
  "plan/create",
  "plan/draft/save",
  "plan/draft/discard",
  "plan/propose",
  "plan/execute",
  "plan/observe",
  "plan/unobserve",
  "plan/events/read",
  "plan/evidence/list",
  "plan/pause",
  "plan/resume",
  "plan/stop",
  "plan/reject",
  "task/create",
  "task/list",
  "task/read",
  "task/observe",
  "task/unobserve",
  "task/seen",
  "task/close",
  "thread/message/send",
  "task/followup",
  "task/cancel",
  "task/tree/delete",
  "attachment/import",
  "attachment/discard",
  "attachment/preview/open",
  "attachment/preview/read",
  "attachment/preview/close",
  "turn/start",
  "turn/resume",
  "turn/cancel",
  "turn/input/enqueue",
  "turn/input/prioritize",
  "turn/input/update",
  "turn/input/delete",
  "turn/change-set/read",
  "configuration/read",
  "configuration/patch",
  "configuration/replace",
  "configuration/reset",
  "credential/set",
  "credential/delete",
  "approval/respond",
  "skill/list",
  "mcp/list",
  "mcp/test",
  "model/test",
  "mcp/list-tools",
  "tool/artifact/read",
]);
const EventMethodSchema = z.enum([
  "runtime/status-changed",
  "configuration/changed",
  "turn/input-queue-changed",
  "turn/input-consumed",
  "turn/messages_received",
  "turn/state-changed",
  "assistant/model-step-committed",
  "assistant/text-delta",
  "assistant/reasoning-summary-delta",
  "tool/started",
  "tool/batch-committed",
  "approval/requested",
  "approval/resolved",
  "context/compaction-started",
  "context/compacted",
  "context/compaction-failed",
  "workspace/dirty",
  "turn/terminal",
  "thread/metadata-changed",
  "task/activity",
  "task/progress",
  "task/mailbox-changed",
  "goal/changed",
  "goal/activity",
  "interaction/changed",
  "plan/changed",
]);

/** capability 数组影响分派语义前先拒绝重复值，避免同一能力被重复解释。 */
function unique<T>(values: T[]): boolean {
  return new Set(values).size === values.length;
}

const WorkspaceReferencePathSchema = z
  .string()
  .min(1)
  .max(4_096)
  .regex(
    /^(?!\/)(?![A-Za-z]:)(?!.*\\)(?!.*(?:^|\/)\.\.(?:\/|$)).+$/u,
    "path must be a safe workspace-relative path",
  )
  .refine(noAsciiControlCharacters, "path must not contain control characters");

export const UserContentBlockSchema = z.discriminatedUnion("type", [
  z
    .object({
      type: z.literal("text"),
      text: z.string().min(1).max(4_000_000).refine(noNulCharacters),
    })
    .strict(),
  z.object({ type: z.literal("attachment"), attachmentId: AttachmentIdSchema }).strict(),
  z
    .object({
      type: z.literal("workspace_reference"),
      workspaceId: WorkspaceIdSchema,
      relativePath: WorkspaceReferencePathSchema,
      kind: z.enum(["file", "directory"]),
    })
    .strict(),
  z.object({ type: z.literal("skill_reference"), skillId: SkillIdSchema }).strict(),
]);

/** Turn content 固定为引用、附件、正文顺序；Skill 只增强消息，不能单独构成用户输入。 */
export const TurnContentSchema = z
  .array(UserContentBlockSchema)
  .min(1)
  .max(64)
  .superRefine((items, context) => {
    const attachments = items.filter((item) => item.type === "attachment");
    const texts = items.filter((item) => item.type === "text");
    const references = items.flatMap((item) => {
      if (item.type === "workspace_reference")
        return [`workspace:${item.workspaceId}:${item.relativePath}`];
      if (item.type === "skill_reference") return [`skill:${item.skillId}`];
      return [];
    });
    const workspaceIds = items.flatMap((item) =>
      item.type === "workspace_reference" ? [item.workspaceId] : [],
    );
    if (new Set(workspaceIds).size > 1) {
      context.addIssue({
        code: "custom",
        message: "turn content cannot reference multiple workspaces",
      });
    }
    const hasSendableContent = items.some(
      (item) =>
        item.type === "text" || item.type === "attachment" || item.type === "workspace_reference",
    );
    if (!hasSendableContent) {
      context.addIssue({ code: "custom", message: "skill reference cannot form input alone" });
    }
    let lastPhase = 0;
    for (const [index, item] of items.entries()) {
      const phase =
        item.type === "workspace_reference" || item.type === "skill_reference"
          ? 0
          : item.type === "attachment"
            ? 1
            : 2;
      if (phase < lastPhase) {
        context.addIssue({
          code: "custom",
          path: [index],
          message: "turn content must order references before attachments and text",
        });
      }
      lastPhase = Math.max(lastPhase, phase);
    }
    if (texts.length > 1) {
      context.addIssue({ code: "custom", message: "turn content must contain at most one text" });
    }
    if (attachments.length > 10) {
      context.addIssue({ code: "custom", message: "turn attachment count exceeds limit" });
    }
    const identities = attachments.map((item) => item.attachmentId);
    if (new Set(identities).size !== identities.length) {
      context.addIssue({ code: "custom", message: "turn attachment identity is duplicated" });
    }
    if (new Set(references).size !== references.length) {
      context.addIssue({ code: "custom", message: "turn reference identity is duplicated" });
    }
    const totalText = items.reduce(
      (total, item) => total + (item.type === "text" ? item.text.length : 0),
      0,
    );
    if (totalText > 4_000_000) {
      context.addIssue({ code: "custom", message: "turn text exceeds limit" });
    }
  });

const CapabilitiesSchema = z
  .object({
    methods: z.array(ClientMethodSchema).refine(unique, "methods must be unique"),
    events: z.array(EventMethodSchema).refine(unique, "events must be unique"),
    accessModes: z.array(AccessModeSchema).refine(unique, "accessModes must be unique"),
    collaborationModes: z
      .array(CollaborationModeSchema)
      .length(2)
      .refine(unique, "collaborationModes must be unique"),
    features: RuntimeFeaturesSchema,
  })
  .strict();

export const LimitsSchema = z
  .object({
    maxFrameBytes: z.number().int().min(1_024).max(4_194_304),
    maxInFlightRequests: z.number().int().min(1).max(64),
    maxInboundQueueFrames: z.number().int().min(1).max(256),
    maxControlOutboundQueueFrames: z.number().int().min(1).max(64),
    maxDataOutboundQueueFrames: z.number().int().min(1).max(1_024),
    maxConcurrentTurns: z.number().int().min(1).max(8),
    maxAdmittedTurns: z.number().int().min(1).max(64),
    maxThreadQueuedTurns: z.number().int().min(1).max(8),
    maxTurnQueuedInputs: z.literal(8),
    maxTurnQueuedInputBytes: z.literal(512 * 1024),
    maxSnapshotPageItems: z.literal(200),
    maxToolBatchConcurrency: z.number().int().min(1).max(8),
  })
  .strict();

export const WorkspaceSchema = z
  .object({
    workspaceId: WorkspaceIdSchema,
    root: z.string().min(1).max(4_096),
    displayName: SafeNameSchema,
    trust: z.enum(["untrusted", "trusted"]),
    revision: RevisionSchema,
  })
  .strict();

export const ThreadSchema = z
  .object({
    threadId: ThreadIdSchema,
    workspaceId: WorkspaceIdSchema,
    activeGoalId: GoalIdSchema.nullable(),
    preferences: z
      .object({
        providerId: ProviderIdSchema,
        modelId: ModelIdSchema,
        reasoningLevel: ReasoningLevelSchema.nullable(),
        accessMode: AccessModeSchema,
        collaborationMode: CollaborationModeSchema,
        titleSource: z.enum(["placeholder", "auto", "manual"]),
      })
      .strict()
      .nullable(),
    title: SafeNameSchema,
    status: z.enum(["active", "archived", "deleted"]),
    pinned: z.boolean(),
    latestTurnStatus: TurnStateSchema.nullable(),
    latestTurnSeen: z.boolean(),
    revision: RevisionSchema,
    createdAt: TimestampSchema,
    updatedAt: TimestampSchema,
  })
  .strict()
  // 无 Turn 时不存在可确认的结果；拒绝未读空会话可防止 UI 重启后制造蓝点。
  .superRefine((value, context) => {
    if (value.latestTurnStatus === null && !value.latestTurnSeen) {
      context.addIssue({
        code: "custom",
        path: ["latestTurnSeen"],
        message: "thread without a latest turn must be seen",
      });
    }
  });

const SnapshotItemBaseSchema = z.object({
  itemId: ItemIdSchema,
  createdAt: TimestampSchema,
  turnId: TurnIdSchema,
});

/** 与 Java Character.isISOControl 对齐，避免路径控制字符借历史快照进入 WebView。 */
function excludesIsoControls(value: string): boolean {
  return !Array.from(value).some((character) => {
    const codePoint = character.codePointAt(0) ?? 0;
    return codePoint <= 0x1f || (codePoint >= 0x7f && codePoint <= 0x9f);
  });
}

const RelativePathSchema = z
  .string()
  .min(1)
  .max(4_096)
  .regex(
    /^(?!\/)(?![A-Za-z]:)(?!.*\\)(?!.*(?:^|\/)\.\.(?:\/|$)).+$/u,
    "path must be a safe workspace-relative path",
  )
  .refine(excludesIsoControls, "path must not contain control characters");
const ArtifactIdSchema = prefixedId("artifact_", 110);

/** Java 已完成脱敏的唯一 Tool 展示合同；WebView 不接收 raw arguments 或 raw result。 */
const ToolPresentationSchema = z
  .object({
    kind: z.enum(["read", "edit", "write", "shell", "mcp"]),
    title: SafeNameSchema,
    status: z.enum(["pending", "running", "waiting_approval", "success", "error", "cancelled"]),
    inputPreview: PreviewTextSchema.optional(),
    outputPreview: PreviewTextSchema.optional(),
    relativePaths: z.array(RelativePathSchema).max(64).refine(unique, "paths must be unique"),
    command: PreviewTextSchema.optional(),
    relativeCwd: RelativePathSchema.optional(),
    stdout: PreviewTextSchema.optional(),
    stderr: PreviewTextSchema.optional(),
    exitCode: z.number().int().min(-2_147_483_648).max(2_147_483_647).optional(),
    durationMs: z.number().int().min(0).max(MAX_SAFE_INTEGER).optional(),
    truncated: z.boolean(),
    artifactId: ArtifactIdSchema.optional(),
  })
  .strict();

const TurnChangeFileSchema = z
  .object({
    path: RelativePathSchema,
    status: z.enum(["added", "modified", "deleted"]),
    additions: RevisionSchema,
    deletions: RevisionSchema,
    binary: z.boolean(),
    truncated: z.boolean(),
  })
  .strict();
const TurnChangeStatsSchema = z
  .object({
    files: z.number().int().min(0).max(256),
    additions: RevisionSchema,
    deletions: RevisionSchema,
    binaryFiles: z.number().int().min(0).max(256),
    truncated: z.boolean(),
  })
  .strict();
const TurnChangeIncompleteReasonSchema = z.enum([
  "unknown_mutator",
  "mutation_chain_broken",
  "outside_workspace",
  "limit_exceeded",
  "capture_failed",
  "commit_unconfirmed",
  "recovery_boundary",
]);
const TurnChangeSetSchema = z
  .object({
    state: z.enum(["complete", "partial"]),
    incompleteReasons: z
      .array(TurnChangeIncompleteReasonSchema)
      .max(7)
      .refine(unique, "incomplete reasons must be unique"),
    files: z.array(TurnChangeFileSchema).max(256),
    stats: TurnChangeStatsSchema,
    artifactId: ArtifactIdSchema.optional(),
  })
  .strict()
  .superRefine((value, context) => {
    if ((value.state === "complete") !== (value.incompleteReasons.length === 0)) {
      context.addIssue({
        code: "custom",
        path: ["incompleteReasons"],
        message: "complete change sets cannot carry incomplete reasons",
      });
    }
    if (value.stats.files !== value.files.length) {
      context.addIssue({
        code: "custom",
        path: ["stats", "files"],
        message: "change set file count must match the frozen file list",
      });
    }
  });

/** 每次 Provider 请求自己的可审计执行画像；它不承担 Turn 级冻结语义。 */
const ProviderRequestProfileSchema = z
  .object({
    providerId: ProviderIdSchema,
    modelId: ModelIdSchema,
    api: z.enum(["anthropic_messages", "openai_responses", "openai_chat_completions"]),
    upstreamModel: SafeNameSchema,
    requestedReasoning: z
      .enum(["off", "minimal", "low", "medium", "high", "xhigh", "max"])
      .nullable(),
    effectiveReasoning: z
      .enum(["off", "minimal", "low", "medium", "high", "xhigh", "max"])
      .nullable(),
    accessMode: AccessModeSchema,
    collaborationMode: CollaborationModeSchema,
    configGeneration: z
      .string()
      .regex(/^cfg_[A-Za-z0-9_-]+$/)
      .max(128),
    promptRevision: SafeIdentifierSchema,
    toolCatalogRevision: SafeIdentifierSchema,
    contextWindowTokens: z.number().int().min(1).max(MAX_SAFE_INTEGER),
    maxOutputTokens: z.number().int().min(1).max(MAX_SAFE_INTEGER),
  })
  .strict();

/** 平坦历史中的 Turn 元数据与 items 分离，避免分页条目被合并成一个 synthetic Turn。 */
const ThreadSnapshotTurnSchema = z
  .object({
    turnId: TurnIdSchema,
    status: TurnStateSchema,
    requestedAt: TimestampSchema,
    updatedAt: TimestampSchema,
    completedAt: TimestampSchema.nullable(),
    changeSet: TurnChangeSetSchema.nullable(),
    errorCode: z
      .string()
      .regex(/^[A-Z][A-Z0-9_]{1,63}$/)
      .nullable(),
  })
  .strict();

export const AttachmentSummarySchema = z
  .object({
    attachmentId: AttachmentIdSchema,
    displayName: SafeNameSchema,
    sizeBytes: z.number().int().min(0).max(104_857_600),
    mediaKind: z.enum(["text", "image", "pdf", "binary"]),
    mediaType: z.string().min(3).max(128),
  })
  .strict();

/** 摘要数组必须逐项对应 content 中的 attachment block，禁止错序授权预览。 */
function requireMatchingAttachmentSummaries(
  value: {
    content: z.infer<typeof TurnContentSchema>;
    attachments: z.infer<typeof AttachmentSummarySchema>[];
  },
  context: z.RefinementCtx,
): void {
  const ids = value.content
    .filter(
      (block): block is Extract<(typeof value.content)[number], { type: "attachment" }> =>
        block.type === "attachment",
    )
    .map((block) => block.attachmentId);
  if (
    ids.length !== value.attachments.length ||
    ids.some((id, index) => id !== value.attachments[index]?.attachmentId)
  ) {
    context.addIssue({
      code: "custom",
      path: ["attachments"],
      message: "attachment summaries must match content order",
    });
  }
}

/**
 * 用判别联合镜像 Java 拥有的 durable snapshot 词汇。
 * 每个 kind 都有不同的必填 payload；若使用可选兜底字段，会接受残缺记录，
 * 同时错误拒绝合法的 Tool 与 approval 数据。
 */
const ThreadItemSchema = z.discriminatedUnion("kind", [
  SnapshotItemBaseSchema.extend({
    kind: z.literal("user_input"),
    content: TurnContentSchema,
    attachments: z.array(AttachmentSummarySchema).max(10),
  })
    .strict()
    .superRefine(requireMatchingAttachmentSummaries),
  SnapshotItemBaseSchema.extend({
    kind: z.literal("thread_message"),
    sourceThreadId: ThreadIdSchema,
    sourceTitle: SafeNameSchema.refine(
      (value) => value.trim().length > 0,
      "source title must not be blank",
    ),
    content: BoundedTextSchema,
  }).strict(),
  SnapshotItemBaseSchema.extend({
    kind: z.literal("assistant_progress"),
    text: BoundedTextSchema,
    modelRound: z.number().int().min(1).max(128),
  }).strict(),
  SnapshotItemBaseSchema.extend({
    kind: z.literal("reasoning_summary"),
    text: BoundedTextSchema,
    modelRound: z.number().int().min(1).max(128),
  }).strict(),
  SnapshotItemBaseSchema.extend({
    kind: z.literal("final_answer"),
    text: BoundedTextSchema,
  }).strict(),
  SnapshotItemBaseSchema.extend({
    kind: z.literal("tool_call"),
    callId: CallIdSchema,
    toolName: SafeIdentifierSchema,
    ordinal: z.number().int().min(0).max(1_023),
    presentation: ToolPresentationSchema,
  }).strict(),
  SnapshotItemBaseSchema.extend({
    kind: z.literal("approval"),
    approvalId: ApprovalIdSchema,
    callId: CallIdSchema,
    toolName: SafeIdentifierSchema,
    reason: z.string().min(1).max(2_048),
    expiresAt: TimestampSchema,
    decision: ApprovalDecisionSchema.nullable(),
  }).strict(),
]);

/** 队列条目是 App Server 签发的持久事实；inputRevision 只保护单条编辑竞态。 */
export const QueuedInputSchema = z
  .object({
    inputId: prefixedId("input_", 128),
    turnId: TurnIdSchema,
    content: TurnContentSchema,
    attachments: z.array(AttachmentSummarySchema).max(10),
    kind: z.enum(["follow_up", "steering"]),
    status: z.enum(["pending", "needs_attention"]),
    issue: z
      .object({
        errorCode: z.enum([
          "WORKSPACE_REFERENCE_INVALID",
          "SKILL_UNAVAILABLE",
          "SKILL_LOAD_FAILED",
          "CONTENT_TOO_LARGE",
          "ATTACHMENT_UNAVAILABLE",
        ]),
        message: z.string().min(1).max(512).refine(noControlCharacters),
        retryable: z.boolean(),
      })
      .strict()
      .nullable(),
    inputRevision: RevisionSchema,
    createdAt: TimestampSchema,
  })
  .strict()
  .superRefine((value, context) => {
    requireMatchingAttachmentSummaries(value, context);
    if ((value.status === "needs_attention") !== (value.issue !== null)) {
      context.addIssue({
        code: "custom",
        path: ["issue"],
        message: "queued input attention state and issue must agree",
      });
    }
  });

/** 全量队列投影允许 revision 跳跃覆盖，但必须保持容量、字节预算与 identity 唯一。 */
export const InputQueueSchema = z
  .object({
    turnId: TurnIdSchema,
    revision: RevisionSchema,
    accepting: z.boolean(),
    items: z.array(QueuedInputSchema).max(8),
  })
  .strict()
  .superRefine((value, context) => {
    const inputIds = new Set<string>();
    let totalBytes = 0;
    value.items.forEach((item, index) => {
      totalBytes += new TextEncoder().encode(JSON.stringify(item.content)).byteLength;
      if (item.turnId !== value.turnId)
        context.addIssue({
          code: "custom",
          path: ["items", index, "turnId"],
          message: "queued input must belong to queue turn",
        });
      if (inputIds.has(item.inputId))
        context.addIssue({
          code: "custom",
          path: ["items", index, "inputId"],
          message: "queued input ids must be unique",
        });
      inputIds.add(item.inputId);
    });
    if (totalBytes > 512 * 1024)
      context.addIssue({
        code: "custom",
        path: ["items"],
        message: "input queue exceeds UTF-8 byte budget",
      });
  });

/**
 * 历史 Usage 只接受 Provider 已提交的精确计量；独立时间戳让恢复层能够与更晚的压缩事实择新。
 * BigInt 比较避免两个合法 safe integer 相加后跨过 JavaScript 精确整数边界。
 */
const RequestUsageBase = {
  requestId: ProviderRequestIdSchema,
  requestOrdinal: z.number().int().min(1).max(MAX_SAFE_INTEGER),
  modelRound: z.number().int().min(1).max(128),
  purpose: z.enum(["assistant", "summary"]),
  measuredAt: TimestampSchema,
} as const;

/**
 * 复用请求级 Usage 的严格判别联合，同时让事件外壳与 Thread 快照显式选择关联字段。
 * 这里不把 turnId 放宽为全局可选字段，避免事件负载偷偷形成第二套 Turn 身份来源。
 */
function createRequestUsageSchema<Association extends z.ZodRawShape>(association: Association) {
  const requestUsageBase = { ...RequestUsageBase, ...association };
  return z.discriminatedUnion("certainty", [
    z
      .object({
        ...requestUsageBase,
        profile: ProviderRequestProfileSchema,
        certainty: z.literal("known"),
        inputTokens: RevisionSchema,
        outputTokens: RevisionSchema,
        totalTokens: RevisionSchema,
      })
      .strict()
      .refine(
        (usage) => {
          const measured = usage as {
            inputTokens: number;
            outputTokens: number;
            totalTokens: number;
          };
          return (
            BigInt(measured.totalTokens) >=
            BigInt(measured.inputTokens) + BigInt(measured.outputTokens)
          );
        },
        { path: ["totalTokens"], message: "usage total must include input and output tokens" },
      ),
    z
      .object({
        ...requestUsageBase,
        profile: ProviderRequestProfileSchema,
        certainty: z.literal("unknown"),
        inputTokens: z.null(),
        outputTokens: z.null(),
        totalTokens: z.null(),
      })
      .strict(),
  ]);
}

/** 首版 Usage 始终携带完整请求画像，UNKNOWN 只表示 Provider 计量不可得。 */
const RequestUsageSchema = createRequestUsageSchema({});

/** Thread 快照必须补足事件外壳原本承载的 Turn 身份，供重启后按请求事实关联。 */
const ThreadRequestUsageSchema = createRequestUsageSchema({ turnId: TurnIdSchema });

const pageParams = {
  cursor: CursorSchema.optional(),
  limit: z.number().int().min(1).max(200).optional(),
};
const emptyParams = z.object({}).strict();
/** 通用 workspace 查询不接受客户端拥有的 cwd；其身份只能由 Java 提供。 */
const WorkspaceGeneralReadParamsSchema = emptyParams;
const workspaceMutationParams = z
  .object({ workspaceId: WorkspaceIdSchema, expectedRevision: RevisionSchema })
  .strict();
const threadMutationParams = z
  .object({ threadId: ThreadIdSchema, expectedThreadRevision: RevisionSchema })
  .strict();
const ThreadPinParamsSchema = threadMutationParams.extend({ pinned: z.boolean() }).strict();
/** 手动压缩刻意复用同一 CAS 字段形状，但保持独立方法以免扩展 archive/delete 语义。 */
const ThreadCompactParamsSchema = threadMutationParams;

const InitializeParamsSchema = z
  .object({
    protocolMajor: z.literal(JA_PROTOCOL_MAJOR),
    protocolMinor: z.literal(JA_PROTOCOL_MINOR),
    clientVersion: SafeNameSchema,
    capabilities: CapabilitiesSchema,
    limits: LimitsSchema,
  })
  .strict();
const CwdSchema = z.string().min(1).max(4_096).refine(noNulCharacters, "cwd contains NUL");
/**
 * 只接受 Java 拥有的 missing 标记或编码后的文件身份。
 * token 对调用方保持不透明，但 WebView 边界仍能校验形状，并拒绝 `v1`
 * 之类旧计数器，避免其被误认成合法 CAS generation。
 */
const ConfigVersionSchema = z
  .string()
  .regex(/^cfg_(?:missing|[A-Za-z0-9_-]+)$/)
  .max(256);
const ConfigScopeSchema = z.enum(["user", "project"]);
const ConfigProjectionSchema = z.record(z.string().max(256), z.unknown());
const ConfigReadLayerSchema = z
  .object({
    present: z.boolean(),
    trusted: z.boolean(),
    status: z.enum(["missing", "valid", "untrusted", "corrupt", "io_error"]),
    document: ConfigProjectionSchema.nullable(),
  })
  .strict();
/** 三个 CAS token 只存在于顶层快照，避免 layer 与 credential 各自形成旧版版本来源。 */
const ConfigCasSchema = z
  .object({
    userVersion: ConfigVersionSchema,
    projectVersion: ConfigVersionSchema,
    credentialVersion: ConfigVersionSchema,
  })
  .strict();
const CredentialProjectionSchema = z.record(
  CredentialRefSchema,
  z
    .object({
      configured: z.boolean(),
    })
    .strict(),
);
const ConfigReadResultSchema = z
  .object({
    workspaceId: WorkspaceIdSchema.nullable(),
    trusted: z.boolean(),
    effective: ConfigProjectionSchema,
    user: ConfigReadLayerSchema,
    project: ConfigReadLayerSchema,
    credentials: CredentialProjectionSchema,
    cas: ConfigCasSchema,
    diagnostics: z.array(z.string().regex(/^[A-Z][A-Z0-9_]{0,63}$/)).max(32),
  })
  .strict();
const ConfigWriteResultSchema = z
  .object({
    accepted: z.literal(true),
    scope: ConfigScopeSchema,
    version: ConfigVersionSchema,
  })
  .strict();
const CredentialSetResultSchema = z
  .object({
    accepted: z.literal(true),
    credentialId: CredentialRefSchema,
    configured: z.literal(true),
    version: ConfigVersionSchema,
  })
  .strict();
const CredentialDeleteResultSchema = z
  .object({
    accepted: z.literal(true),
    credentialId: CredentialRefSchema,
    configured: z.literal(false),
    version: ConfigVersionSchema,
  })
  .strict();
const WorkspaceOpenParamsSchema = z
  .object({ cwd: CwdSchema, displayName: SafeNameSchema.optional() })
  .strict();
const WorkspaceListParamsSchema = z.object(pageParams).strict();
const WorkspacePathSearchParamsSchema = z
  .object({
    threadId: ThreadIdSchema,
    workspaceId: WorkspaceIdSchema,
    query: z.string().max(1_024).refine(noAsciiControlCharacters),
    limit: z.number().int().min(1).max(50).optional(),
  })
  .strict();
const WorkspaceTrustParamsSchema = z
  .object({ workspaceId: WorkspaceIdSchema, trust: z.enum(["untrusted", "trusted"]) })
  .strict();
const WorkspaceUnregisterParamsSchema = workspaceMutationParams;
const ThreadCreateParamsSchema = z
  .object({
    cwd: CwdSchema.nullable().optional(),
    title: SafeNameSchema,
    providerId: ProviderIdSchema,
    modelId: ModelIdSchema,
    reasoningLevel: ReasoningLevelSchema.nullable(),
    accessMode: AccessModeSchema,
    collaborationMode: CollaborationModeSchema,
  })
  .strict();
const ThreadListWorkspaceParamsSchema = z
  .object({ workspaceId: WorkspaceIdSchema, ...pageParams })
  .strict();
/** 全局 Thread 发现与 Workspace 列表共用 `thread/list` wire lane，但以 scope 明确区分语义。 */
export const ThreadDiscoveryParamsSchema = z
  .object({
    scope: z.literal("all"),
    query: z.string().max(256).refine(noIsoControlCharacters).optional(),
    ...pageParams,
    workspaceId: WorkspaceIdSchema.optional(),
  })
  .strict();
const ThreadListParamsSchema = z.union([
  ThreadListWorkspaceParamsSchema,
  ThreadDiscoveryParamsSchema,
]);
const ThreadSearchParamsSchema = z
  .object({ workspaceId: WorkspaceIdSchema, query: z.string().max(256), ...pageParams })
  .strict();
const ThreadReadParamsSchema = z.object({ threadId: ThreadIdSchema, ...pageParams }).strict();
const ThreadRenameParamsSchema = z
  .object({
    threadId: ThreadIdSchema,
    title: SafeNameSchema,
    expectedThreadRevision: RevisionSchema,
  })
  .strict();
const ThreadPreferencesUpdateParamsSchema = z
  .object({
    threadId: ThreadIdSchema,
    providerId: ProviderIdSchema,
    modelId: ModelIdSchema,
    reasoningLevel: ReasoningLevelSchema.nullable(),
    accessMode: AccessModeSchema,
    collaborationMode: CollaborationModeSchema,
    expectedThreadRevision: RevisionSchema,
  })
  .strict();
const TurnStartParamsSchema = z
  .object({
    threadId: ThreadIdSchema,
    content: TurnContentSchema,
    deadlineMs: z.number().int().min(1_000).max(86_400_000).optional(),
  })
  .strict();
const AttachmentImportParamsSchema = z
  .object({
    ingressToken: z.string().regex(/^[0-9a-f]{32}$/),
    workspaceId: WorkspaceIdSchema,
    displayName: SafeNameSchema,
    sizeBytes: z.number().int().min(0).max(104_857_600),
    sha256: z.string().regex(/^[0-9a-f]{64}$/),
  })
  .strict();
const AttachmentDiscardParamsSchema = z.object({ attachmentId: AttachmentIdSchema }).strict();
const AttachmentPreviewSessionIdSchema = z.string().regex(/^apv_[A-Za-z0-9][A-Za-z0-9._-]{7,155}$/);
const AttachmentPreviewAuthorizationSchema = z.discriminatedUnion("kind", [
  z.object({ kind: z.literal("draft"), workspaceId: WorkspaceIdSchema }).strict(),
  z.object({ kind: z.literal("thread"), threadId: ThreadIdSchema }).strict(),
]);
const AttachmentPreviewOpenParamsSchema = z
  .object({
    attachmentId: AttachmentIdSchema,
    authorization: AttachmentPreviewAuthorizationSchema,
  })
  .strict();
const AttachmentPreviewReadParamsSchema = z
  .object({
    previewSessionId: AttachmentPreviewSessionIdSchema,
    offsetBytes: z.number().int().min(0).max(104_857_600),
    limitBytes: z.number().int().min(4).max(65_536),
  })
  .strict();
const AttachmentPreviewCloseParamsSchema = z
  .object({ previewSessionId: AttachmentPreviewSessionIdSchema })
  .strict();
const TurnCancelParamsSchema = z
  .object({
    turnId: TurnIdSchema,
    expectedThreadRevision: RevisionSchema,
  })
  .strict();
/** Resume 与 Cancel 共用 Turn identity/revision CAS，但保持独立方法以避免混淆授权语义。 */
const TurnResumeParamsSchema = TurnCancelParamsSchema;
const TurnInputEnqueueParamsSchema = z
  .object({ turnId: TurnIdSchema, content: TurnContentSchema })
  .strict();
const TurnInputMutationParamsSchema = z
  .object({
    turnId: TurnIdSchema,
    inputId: prefixedId("input_", 128),
    expectedInputRevision: RevisionSchema,
  })
  .strict();
const TurnInputUpdateParamsSchema = TurnInputMutationParamsSchema.extend({
  content: TurnContentSchema,
}).strict();
const TaskNameSchema = z.string().trim().min(1).max(96).refine(noIsoControlCharacters);
const TaskIdempotencyKeySchema = z.string().min(1).max(128).refine(noIsoControlCharacters);
/** 侧边任务可选完整执行偏好；缺省对象时由 App Server 冻结父 Thread 偏好。 */
const TaskCreatePreferencesSchema = z
  .object({
    providerId: ProviderIdSchema,
    modelId: ModelIdSchema,
    reasoningLevel: ReasoningLevelSchema.nullable(),
    accessMode: AccessModeSchema,
    collaborationMode: CollaborationModeSchema,
  })
  .strict();
const TaskCreateParamsSchema = z
  .object({
    parentThreadId: ThreadIdSchema,
    parentTurnId: TurnIdSchema.nullable(),
    expectedParentRevision: RevisionSchema,
    taskName: TaskNameSchema,
    preferences: TaskCreatePreferencesSchema.optional(),
  })
  .strict();
const TaskListParamsSchema = z.object({ rootThreadId: ThreadIdSchema }).strict();
const TaskReadParamsSchema = z
  .object({
    taskThreadId: ThreadIdSchema,
    cursor: TaskCursorSchema.optional(),
    limit: z.number().int().min(1).max(200).optional(),
  })
  .strict();
const TaskObserveParamsSchema = z
  .object({ taskThreadId: ThreadIdSchema, expectedTaskRevision: RevisionSchema })
  .strict();
const TaskUnobserveParamsSchema = z.object({ observationId: TaskObservationIdSchema }).strict();
const TaskSeenParamsSchema = z
  .object({
    taskThreadId: ThreadIdSchema,
    expectedTaskRevision: RevisionSchema,
    throughActivitySequence: z.number().int().min(1).max(MAX_SAFE_INTEGER),
  })
  .strict();
/** 侧聊关闭只携带临时 Thread identity；取消、观察释放与数据清理由服务端原子裁决。 */
const TaskCloseParamsSchema = z.object({ taskThreadId: ThreadIdSchema }).strict();
const TaskMessageParamsSchema = z
  .object({
    senderThreadId: ThreadIdSchema,
    targetThreadId: ThreadIdSchema,
    content: TurnContentSchema,
    idempotencyKey: TaskIdempotencyKeySchema,
  })
  .strict();
const TaskFollowupParamsSchema = TaskMessageParamsSchema.extend({
  expectedTaskRevision: RevisionSchema,
}).strict();
const TaskMutationParamsSchema = z
  .object({ taskThreadId: ThreadIdSchema, expectedTaskRevision: RevisionSchema })
  .strict();
const TaskTreeDeleteParamsSchema = TaskMutationParamsSchema.extend({
  confirmTaskThreadId: ThreadIdSchema,
})
  .strict()
  .superRefine((value, context) => {
    if (value.confirmTaskThreadId !== value.taskThreadId) {
      context.addIssue({
        code: "custom",
        path: ["confirmTaskThreadId"],
        message: "task tree deletion confirmation must repeat taskThreadId",
      });
    }
  });
const ApprovalRespondParamsSchema = z
  .object({
    approvalId: ApprovalIdSchema,
    turnId: TurnIdSchema,
    decision: ApprovalDecisionSchema,
    expectedThreadRevision: RevisionSchema,
  })
  .strict();
/** Skill 目录可绑定当前 Workspace；缺失时只读取内置与两级用户来源。 */
const SkillListParamsSchema = z
  .object({ workspaceId: WorkspaceIdSchema.optional(), ...pageParams })
  .strict();
const McpListParamsSchema = z.object(pageParams).strict();
const McpTestParamsSchema = z.object({ mcpId: McpIdSchema }).strict();
const ModelTestParamsSchema = z
  .object({ providerId: ProviderIdSchema, modelId: ModelIdSchema })
  .strict();
const McpToolsReadParamsSchema = z.object({ mcpId: McpIdSchema, ...pageParams }).strict();
const ToolArtifactReadParamsSchema = z
  .object({
    threadId: ThreadIdSchema,
    turnId: TurnIdSchema,
    callId: CallIdSchema,
    artifactId: ArtifactIdSchema,
    offsetCharacters: RevisionSchema,
    limitCharacters: z.number().int().min(1).max(65_536),
  })
  .strict();
const TurnChangeSetReadParamsSchema = z
  .object({
    threadId: ThreadIdSchema,
    turnId: TurnIdSchema,
    artifactId: ArtifactIdSchema,
    filePath: RelativePathSchema,
  })
  .strict();
const ConfigurationReadParamsSchema = z
  .object({ workspaceId: WorkspaceIdSchema.optional() })
  .strict();
const userConfigurationTarget = { scope: z.literal("user"), expectedVersion: ConfigVersionSchema };
const projectConfigurationTarget = {
  scope: z.literal("project"),
  workspaceId: WorkspaceIdSchema,
  expectedVersion: ConfigVersionSchema,
};
/** 通过作用域判别联合强制项目配置携带 workspaceId，并禁止用户配置夹带工作区能力。 */
const ConfigurationPatchParamsSchema = z.discriminatedUnion("scope", [
  z.object({ ...userConfigurationTarget, patch: ConfigProjectionSchema }).strict(),
  z.object({ ...projectConfigurationTarget, patch: ConfigProjectionSchema }).strict(),
]);
/** replace 只接受完整严格文档，不复用 patch 的部分文档语义。 */
const ConfigurationReplaceParamsSchema = z.discriminatedUnion("scope", [
  z.object({ ...userConfigurationTarget, document: ConfigDocumentSchema }).strict(),
  z.object({ ...projectConfigurationTarget, document: ConfigDocumentSchema }).strict(),
]);
/** reset 是独立命令，因此不存在旧 mode/document 组合分支。 */
const ConfigurationResetParamsSchema = z.discriminatedUnion("scope", [
  z.object(userConfigurationTarget).strict(),
  z.object(projectConfigurationTarget).strict(),
]);
const CredentialSetParamsSchema = z
  .object({
    credentialId: CredentialRefSchema,
    secret: SecretValueSchema,
    expectedVersion: ConfigVersionSchema,
  })
  .strict();
const CredentialDeleteParamsSchema = z
  .object({ credentialId: CredentialRefSchema, expectedVersion: ConfigVersionSchema })
  .strict();

export const ParamsSchemaByMethod = {
  "runtime/initialize": InitializeParamsSchema,
  "runtime/health": emptyParams,
  "runtime/shutdown": emptyParams,
  "workspace/open": WorkspaceOpenParamsSchema,
  "workspace/list": WorkspaceListParamsSchema,
  "workspace/path/search": WorkspacePathSearchParamsSchema,
  "workspace/open-general": WorkspaceGeneralReadParamsSchema,
  "workspace/set-trust": WorkspaceTrustParamsSchema,
  "workspace/unregister": WorkspaceUnregisterParamsSchema,
  "thread/create": ThreadCreateParamsSchema,
  "thread/list": ThreadListParamsSchema,
  "thread/search": ThreadSearchParamsSchema,
  "thread/read": ThreadReadParamsSchema,
  "thread/rename": ThreadRenameParamsSchema,
  "thread/pin": ThreadPinParamsSchema,
  "thread/seen": threadMutationParams,
  "thread/preferences/update": ThreadPreferencesUpdateParamsSchema,
  "thread/archive": threadMutationParams,
  "thread/restore": threadMutationParams,
  "thread/delete": threadMutationParams,
  "thread/compact": ThreadCompactParamsSchema,
  ...GoalParamsSchemaByMethod,
  ...InteractionParamsSchemaByMethod,
  "task/create": TaskCreateParamsSchema,
  "task/list": TaskListParamsSchema,
  "task/read": TaskReadParamsSchema,
  "task/observe": TaskObserveParamsSchema,
  "task/unobserve": TaskUnobserveParamsSchema,
  "task/seen": TaskSeenParamsSchema,
  "task/close": TaskCloseParamsSchema,
  "thread/message/send": TaskMessageParamsSchema,
  "task/followup": TaskFollowupParamsSchema,
  "task/cancel": TaskMutationParamsSchema,
  "task/tree/delete": TaskTreeDeleteParamsSchema,
  "attachment/import": AttachmentImportParamsSchema,
  "attachment/discard": AttachmentDiscardParamsSchema,
  "attachment/preview/open": AttachmentPreviewOpenParamsSchema,
  "attachment/preview/read": AttachmentPreviewReadParamsSchema,
  "attachment/preview/close": AttachmentPreviewCloseParamsSchema,
  "turn/start": TurnStartParamsSchema,
  "turn/resume": TurnResumeParamsSchema,
  "turn/cancel": TurnCancelParamsSchema,
  "turn/input/enqueue": TurnInputEnqueueParamsSchema,
  "turn/input/prioritize": TurnInputMutationParamsSchema,
  "turn/input/update": TurnInputUpdateParamsSchema,
  "turn/input/delete": TurnInputMutationParamsSchema,
  "turn/change-set/read": TurnChangeSetReadParamsSchema,
  "configuration/read": ConfigurationReadParamsSchema,
  "configuration/patch": ConfigurationPatchParamsSchema,
  "configuration/replace": ConfigurationReplaceParamsSchema,
  "configuration/reset": ConfigurationResetParamsSchema,
  "credential/set": CredentialSetParamsSchema,
  "credential/delete": CredentialDeleteParamsSchema,
  "approval/respond": ApprovalRespondParamsSchema,
  "skill/list": SkillListParamsSchema,
  "mcp/list": McpListParamsSchema,
  "mcp/test": McpTestParamsSchema,
  "model/test": ModelTestParamsSchema,
  "mcp/list-tools": McpToolsReadParamsSchema,
  "tool/artifact/read": ToolArtifactReadParamsSchema,
} satisfies Record<z.infer<typeof ClientMethodSchema>, z.ZodTypeAny>;

const initializeResultSchema = z
  .object({
    protocolMajor: z.literal(JA_PROTOCOL_MAJOR),
    protocolMinor: z.literal(JA_PROTOCOL_MINOR),
    serverInstanceId: ServerInstanceIdSchema,
    runtime: z
      .object({
        engine: z.literal("ja-kernel"),
        engineVersion: z.literal(packageJson.version),
      })
      .strict(),
    capabilities: CapabilitiesSchema,
    limits: LimitsSchema,
  })
  .strict();
const healthResultSchema = z
  .object({
    status: z.enum(["starting", "ready", "degraded", "shutting_down", "stopped", "crashed"]),
    components: z
      .array(
        z
          .object({
            name: SafeIdentifierSchema,
            status: z.enum(["healthy", "degraded", "unavailable", "stopped"]),
          })
          .strict(),
      )
      .max(64),
  })
  .strict();
const shutdownResultSchema = z
  .object({ accepted: z.literal(true), status: z.literal("shutting_down") })
  .strict();
const workspaceResultSchema = WorkspaceSchema;
const workspacePageResultSchema = z
  .object({ items: z.array(workspaceResultSchema).max(200), nextCursor: CursorSchema.nullable() })
  .strict();
const workspacePathSearchResultSchema = z
  .object({
    threadId: ThreadIdSchema,
    workspaceId: WorkspaceIdSchema,
    generation: z.number().int().min(1).max(MAX_SAFE_INTEGER),
    query: z.string().max(1_024).refine(noNulCharacters),
    items: z
      .array(
        z
          .object({
            relativePath: WorkspaceReferencePathSchema,
            kind: z.enum(["file", "directory"]),
          })
          .strict(),
      )
      .max(50),
    truncated: z.boolean(),
  })
  .strict();
const acceptedResultSchema = z.object({ accepted: z.literal(true) }).strict();
const threadResultSchema = ThreadSchema;
const threadPageResultSchema = z
  .object({ items: z.array(threadResultSchema).max(200), nextCursor: CursorSchema.nullable() })
  .strict();
export const ThreadDiscoveryItemSchema = z
  .object({
    threadId: ThreadIdSchema,
    title: SafeNameSchema,
    kind: z.enum(["main", "side_chat", "subagent"]),
    workspaceId: WorkspaceIdSchema,
    status: z.union([z.literal("idle"), TurnStateSchema]),
  })
  .strict();
export const ThreadDiscoveryResultSchema = z
  .object({
    items: z.array(ThreadDiscoveryItemSchema).max(200),
    nextCursor: CursorSchema.nullable(),
  })
  .strict();
const threadListResultSchema = z.union([threadPageResultSchema, ThreadDiscoveryResultSchema]);
const AttachmentResultSchema = z
  .object({
    attachmentId: AttachmentIdSchema,
    workspaceId: WorkspaceIdSchema,
    displayName: SafeNameSchema,
    sizeBytes: z.number().int().min(0).max(104_857_600),
    mediaKind: z.enum(["text", "image", "pdf", "binary"]),
    mediaType: z.string().min(3).max(128),
    state: z.enum(["draft", "bound", "discarded", "expired"]),
    createdAt: TimestampSchema,
    expiresAt: TimestampSchema,
    boundMessageId: ItemIdSchema.nullable(),
  })
  .strict()
  .superRefine((value, context) => {
    if ((value.state === "bound") !== (value.boundMessageId !== null)) {
      context.addIssue({
        code: "custom",
        path: ["boundMessageId"],
        message: "attachment binding state is inconsistent",
      });
    }
  });
const AttachmentPreviewOpenResultSchema = z
  .object({
    previewSessionId: AttachmentPreviewSessionIdSchema,
    attachmentId: AttachmentIdSchema,
    displayName: SafeNameSchema,
    sizeBytes: z.number().int().min(0).max(104_857_600),
    mediaKind: z.enum(["text", "image"]),
    mediaType: z.string().min(3).max(128),
    previewKind: z.enum(["text", "image"]),
  })
  .strict()
  .superRefine((value, context) => {
    if (value.mediaKind !== value.previewKind) {
      context.addIssue({ code: "custom", path: ["previewKind"], message: "preview kind mismatch" });
    }
  });
const AttachmentPreviewReadResultSchema = z
  .object({
    previewSessionId: AttachmentPreviewSessionIdSchema,
    offsetBytes: z.number().int().min(0).max(104_857_600),
    nextOffsetBytes: z.number().int().min(0).max(104_857_600),
    contentBase64: z
      .string()
      .max(87_384)
      .regex(/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/),
    eof: z.boolean(),
    truncated: z.boolean(),
  })
  .strict()
  .superRefine((value, context) => {
    if (value.nextOffsetBytes < value.offsetBytes) {
      context.addIssue({
        code: "custom",
        path: ["nextOffsetBytes"],
        message: "preview offset regressed",
      });
    }
  });
const AttachmentPreviewCloseResultSchema = z
  .object({ previewSessionId: AttachmentPreviewSessionIdSchema, closed: z.literal(true) })
  .strict();
const turnAcceptedResultSchema = z
  .object({
    accepted: z.literal(true),
    queued: z.boolean(),
    turnId: TurnIdSchema,
    threadRevision: RevisionSchema,
  })
  .strict();
/** Resume 必须重新入队且复用原 Turn；literal true 防止通用 start 结果放宽恢复语义。 */
const turnResumeResultSchema = z
  .object({
    accepted: z.literal(true),
    queued: z.literal(true),
    turnId: TurnIdSchema,
    threadRevision: RevisionSchema,
  })
  .strict();
const turnCancelResultSchema = z
  .object({
    accepted: z.literal(true),
    turnId: TurnIdSchema,
    status: TurnStateSchema,
    threadRevision: RevisionSchema,
  })
  .strict();
export const InputQueueMutationResultSchema = z
  .object({
    accepted: z.literal(true),
    inputId: prefixedId("input_", 128),
    inputQueue: InputQueueSchema,
  })
  .strict();
const approvalRespondResultSchema = z
  .object({
    accepted: z.literal(true),
    approvalId: ApprovalIdSchema,
    turnId: TurnIdSchema,
    decision: ApprovalDecisionSchema,
    threadRevision: RevisionSchema,
  })
  .strict();
const skillProjectionSchema = z
  .object({
    skillId: SkillIdSchema,
    name: SafeNameSchema,
    scope: z.enum(["builtin", "user", "ja", "project"]),
    enabled: z.boolean(),
    status: z.enum(["healthy", "invalid", "unavailable"]),
    description: BoundedTextSchema,
  })
  .strict();
const skillPageResultSchema = z
  .object({ items: z.array(skillProjectionSchema).max(200), nextCursor: CursorSchema.nullable() })
  .strict();
const mcpProjectionSchema = z
  .object({
    mcpId: McpIdSchema,
    name: SafeNameSchema,
    transport: z.enum(["stdio", "streamable_http"]),
    // Java catalog 用 configured 表示尚未探测，必须与 healthy 保持区分。
    status: z.enum(["healthy", "degraded", "unavailable", "disabled", "configured"]),
    toolCount: z.number().int().min(0).max(2_000),
  })
  .strict();
const mcpPageResultSchema = z
  .object({ items: z.array(mcpProjectionSchema).max(200), nextCursor: CursorSchema.nullable() })
  .strict();
const mcpTestResultSchema = z
  .object({
    mcpId: McpIdSchema,
    name: SafeNameSchema,
    transport: z.enum(["stdio", "streamable_http"]),
    // MCP probe 在 initialize/tools 成功后返回 available，界面再映射为 connected。
    status: z.enum(["healthy", "available", "degraded", "unavailable"]),
    toolCount: z.number().int().min(0).max(2_000),
  })
  .strict();
const modelTestResultSchema = z
  .object({
    responseModel: z.string().min(1).max(512).refine(noNulCharacters, "model contains NUL"),
    latencyMs: z.number().int().min(0).max(3_600_000),
  })
  .strict();
const mcpToolSchema = z
  .object({
    name: SafeIdentifierSchema,
    description: BoundedTextSchema,
    inputSchema: JsonObjectSchema.refine(
      (value) => Object.keys(value).length <= 128,
      "tool schema is too large",
    ),
  })
  .strict();
const mcpToolsResultSchema = z
  .object({ items: z.array(mcpToolSchema).max(200), nextCursor: CursorSchema.nullable() })
  .strict();
const toolArtifactReadResultSchema = z
  .object({
    artifactId: ArtifactIdSchema,
    offsetCharacters: RevisionSchema,
    nextOffsetCharacters: RevisionSchema.nullable(),
    totalCharacters: RevisionSchema,
    truncated: z.boolean(),
    content: BoundedTextSchema,
  })
  .strict();
const TURN_CHANGE_FILE_MAX_BYTES = 2_097_152;
const TURN_CHANGE_FILE_MAX_BASE64_CHARACTERS = 2_796_204;
const BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
const StandardBase64Schema = z
  .string()
  .max(TURN_CHANGE_FILE_MAX_BASE64_CHARACTERS)
  .refine(hasStandardBase64Shape, "content must be standard padded Base64")
  .refine(hasCanonicalBase64PaddingBits, "base64 padding bits must be canonical");

/** 线性扫描大正文，避免对 2 MiB 编码结果使用可能产生灾难性回溯的正则。 */
function hasStandardBase64Shape(contentBase64: string): boolean {
  if (contentBase64.length % 4 !== 0) return false;
  const padding = contentBase64.endsWith("==") ? 2 : contentBase64.endsWith("=") ? 1 : 0;
  const alphabetLength = contentBase64.length - padding;
  for (let index = 0; index < alphabetLength; index += 1) {
    const code = contentBase64.charCodeAt(index);
    const valid =
      (code >= 65 && code <= 90) ||
      (code >= 97 && code <= 122) ||
      (code >= 48 && code <= 57) ||
      code === 43 ||
      code === 47;
    if (!valid) return false;
  }
  return contentBase64.indexOf("=") === (padding === 0 ? -1 : alphabetLength);
}

/** 检查末组未使用 bit 必须为零，避免多个不同字符串表示同一份冻结正文。 */
function hasCanonicalBase64PaddingBits(contentBase64: string): boolean {
  if (contentBase64.endsWith("==")) {
    return (BASE64_ALPHABET.indexOf(contentBase64.at(-3) ?? "") & 0x0f) === 0;
  }
  if (contentBase64.endsWith("=")) {
    return (BASE64_ALPHABET.indexOf(contentBase64.at(-2) ?? "") & 0x03) === 0;
  }
  return true;
}

/** 只计算已通过 standard Base64 语法校验的载荷长度，避免在 wire parser 中重复解码大正文。 */
function standardBase64ByteLength(contentBase64: string): number {
  if (contentBase64.length === 0) return 0;
  const padding = contentBase64.endsWith("==") ? 2 : contentBase64.endsWith("=") ? 1 : 0;
  return (contentBase64.length / 4) * 3 - padding;
}

const turnChangeSetReadResultSchema = z
  .object({
    artifactId: ArtifactIdSchema,
    filePath: RelativePathSchema,
    byteLength: z.number().int().min(0).max(TURN_CHANGE_FILE_MAX_BYTES),
    sha256: z.string().regex(/^[0-9a-f]{64}$/),
    contentBase64: StandardBase64Schema,
  })
  .strict()
  .superRefine((value, context) => {
    if (standardBase64ByteLength(value.contentBase64) !== value.byteLength) {
      context.addIssue({
        code: "custom",
        path: ["contentBase64"],
        message: "change-set content byte length mismatch",
      });
    }
  });
const threadCompactResultSchema = z
  .object({
    outcome: z.enum(["compacted", "unchanged"]),
    compactionId: CompactionIdSchema.nullable(),
    checkpointId: CheckpointIdSchema.nullable(),
    threadRevision: RevisionSchema,
    inputTokensBefore: RevisionSchema,
    inputTokensAfter: RevisionSchema,
  })
  .strict()
  .superRefine((value, context) => {
    const compacted = value.outcome === "compacted";
    if (compacted !== (value.compactionId !== null && value.checkpointId !== null)) {
      context.addIssue({
        code: "custom",
        path: ["compactionId"],
        message: "compaction identities must match outcome",
      });
    }
    if (compacted && value.inputTokensAfter >= value.inputTokensBefore) {
      context.addIssue({
        code: "custom",
        path: ["inputTokensAfter"],
        message: "compacted context must reduce input tokens",
      });
    }
    if (!compacted && value.inputTokensAfter !== value.inputTokensBefore) {
      context.addIssue({
        code: "custom",
        path: ["inputTokensAfter"],
        message: "unchanged context must preserve input tokens",
      });
    }
  });

const TaskStateSchema = z.enum([
  "idle",
  "queued",
  "running",
  "waiting_approval",
  "suspended",
  "completed",
  "failed",
  "cancelled",
]);

export const TaskSummarySchema = z
  .object({
    taskThreadId: ThreadIdSchema,
    parentThreadId: ThreadIdSchema,
    rootThreadId: ThreadIdSchema,
    originTurnId: TurnIdSchema.nullable(),
    taskName: TaskNameSchema,
    depth: z.number().int().min(1).max(4),
    taskKind: z.enum(["side_task", "subagent"]),
    lifecycle: z.enum(["independent", "attached"]),
    state: TaskStateSchema,
    revision: RevisionSchema,
    latestActivitySequence: z.number().int().min(1).max(MAX_SAFE_INTEGER),
    unreadCount: z.number().int().min(0).max(MAX_SAFE_INTEGER),
    descendantCount: z.number().int().min(0).max(64),
    runningDescendantCount: z.number().int().min(0).max(64),
    needsAttentionCount: z.number().int().min(0).max(64),
    latestSafeSummary: PreviewTextSchema.nullable(),
    startedAt: TimestampSchema.nullable(),
    completedAt: TimestampSchema.nullable(),
    updatedAt: TimestampSchema,
  })
  .strict()
  .superRefine((value, context) => {
    const validLifecycle =
      (value.taskKind === "side_task" && value.lifecycle === "independent") ||
      (value.taskKind === "subagent" && value.lifecycle === "attached");
    if (!validLifecycle) {
      context.addIssue({
        code: "custom",
        path: ["lifecycle"],
        message: "task kind and lifecycle are inconsistent",
      });
    }
    if (value.taskKind === "subagent" && value.originTurnId === null) {
      context.addIssue({
        code: "custom",
        path: ["originTurnId"],
        message: "subagent must identify its origin turn",
      });
    }
  });
const TaskActivitySchema = z
  .object({
    activitySequence: z.number().int().min(1).max(MAX_SAFE_INTEGER),
    activityId: prefixedId("activity_", 105),
    rootThreadId: ThreadIdSchema,
    taskThreadId: ThreadIdSchema,
    actorThreadId: ThreadIdSchema,
    causalTurnId: TurnIdSchema.nullable(),
    kind: z.enum([
      "created",
      "dispatched",
      "message_sent",
      "follow_up_queued",
      "progress",
      "waiting_approval",
      "resumed",
      "completed",
      "failed",
      "cancelled",
      "suspended",
    ]),
    summary: z.object({ text: PreviewTextSchema }).strict(),
    createdAt: TimestampSchema,
  })
  .strict();
const TaskMailboxMessageSchema = z
  .object({
    mailboxSequence: z.number().int().min(1).max(MAX_SAFE_INTEGER),
    messageId: TaskMessageIdSchema,
    senderThreadId: ThreadIdSchema,
    targetThreadId: ThreadIdSchema,
    causalTurnId: TurnIdSchema.nullable(),
    kind: z.enum(["message", "follow_up", "final_answer"]),
    content: TurnContentSchema,
    state: z.enum(["pending", "bound", "consumed", "cancelled"]),
    boundTurnId: TurnIdSchema.nullable(),
    createdAt: TimestampSchema,
    updatedAt: TimestampSchema,
    consumedAt: TimestampSchema.nullable(),
  })
  .strict();
const TaskCreateResultSchema = z
  .object({ accepted: z.literal(true), task: TaskSummarySchema })
  .strict();
/** 列表必须组成一棵完整连根树；客户端拒绝断链、重复 identity 和伪造 depth。 */
function validateTaskTree(
  items: z.infer<typeof TaskSummarySchema>[],
  context: z.RefinementCtx,
): void {
  const byId = new Map(items.map((item) => [item.taskThreadId, item]));
  if (byId.size !== items.length) {
    context.addIssue({
      code: "custom",
      path: ["items"],
      message: "task identities must be unique",
    });
    return;
  }
  for (const [index, item] of items.entries()) {
    const path = ["items", index, "parentThreadId"];
    if (item.taskThreadId === item.rootThreadId || item.taskThreadId === item.parentThreadId) {
      context.addIssue({ code: "custom", path, message: "task lineage cannot self-reference" });
      continue;
    }
    if (item.depth === 1) {
      if (item.parentThreadId !== item.rootThreadId) {
        context.addIssue({
          code: "custom",
          path,
          message: "depth-one task must be rooted directly",
        });
      }
      continue;
    }
    const parent = byId.get(item.parentThreadId);
    if (!parent || parent.rootThreadId !== item.rootThreadId || parent.depth + 1 !== item.depth) {
      context.addIssue({
        code: "custom",
        path,
        message: "task parent is missing or has invalid depth",
      });
    }
  }
}

const TaskListResultSchema = z
  .object({ items: z.array(TaskSummarySchema).max(64) })
  .strict()
  .superRefine((value, context) => validateTaskTree(value.items, context));
const TaskContextPreviewItemSchema = z
  .object({
    role: z.enum(["user", "assistant"]),
    text: z
      .string()
      .refine(
        (value) => Array.from(value).length <= 512,
        "context preview text exceeds 512 code points",
      )
      .refine(noNulCharacters, "context preview contains NUL")
      .nullable(),
    attachmentIds: z.array(AttachmentIdSchema).max(10),
  })
  .strict()
  .superRefine((value, context) => {
    if (value.text === null && value.attachmentIds.length === 0) {
      context.addIssue({ code: "custom", path: [], message: "context preview item is empty" });
    }
  });
const TaskContextSeedSchema = z
  .object({
    contextSeedId: TaskSeedIdSchema,
    parentRevision: RevisionSchema,
    inheritanceMode: z.enum(["effective_context", "brief_only"]),
    taskBrief: TurnContentSchema.nullable(),
    inheritedContextSummary: PreviewTextSchema.nullable(),
    inheritedContextPreview: z.array(TaskContextPreviewItemSchema).max(24),
    fingerprint: z.string().regex(/^[0-9a-f]{64}$/),
    createdAt: TimestampSchema,
  })
  .strict()
  .superRefine((value, context) => {
    const inheritedCodePoints = value.inheritedContextPreview.reduce(
      (total, item) => total + (item.text === null ? 0 : Array.from(item.text).length),
      0,
    );
    if (inheritedCodePoints > 4_096) {
      context.addIssue({
        code: "custom",
        path: ["inheritedContextPreview"],
        message: "context preview exceeds total code-point budget",
      });
    }
    const validInheritance =
      (value.inheritanceMode === "brief_only" &&
        value.taskBrief !== null &&
        value.inheritedContextSummary === null &&
        value.inheritedContextPreview.length === 0) ||
      (value.inheritanceMode === "effective_context" &&
        value.taskBrief === null &&
        value.inheritedContextSummary !== null);
    if (!validInheritance) {
      context.addIssue({
        code: "custom",
        path: ["inheritanceMode"],
        message: "context seed inheritance projection is inconsistent",
      });
    }
  });
const TaskReadResultSchema = z
  .object({
    task: TaskSummarySchema,
    thread: ThreadSchema,
    contextSeed: TaskContextSeedSchema,
    activities: z.array(TaskActivitySchema).max(200),
    mailbox: z.array(TaskMailboxMessageSchema).max(200),
    nextCursor: TaskCursorSchema.nullable(),
  })
  .strict()
  .superRefine((value, context) => {
    if (value.thread.threadId !== value.task.taskThreadId) {
      context.addIssue({
        code: "custom",
        path: ["thread", "threadId"],
        message: "task thread metadata identity does not match task summary",
      });
    }
    for (let index = 0; index < value.activities.length; index += 1) {
      const activity = value.activities[index];
      const previous = value.activities[index - 1];
      if (activity === undefined) continue;
      if (
        activity.taskThreadId !== value.task.taskThreadId ||
        activity.activitySequence > value.task.latestActivitySequence ||
        (previous && activity.activitySequence <= previous.activitySequence)
      ) {
        context.addIssue({
          code: "custom",
          path: ["activities", index],
          message: "task activity page is inconsistent",
        });
      }
      if (
        activity.activitySequence === value.task.latestActivitySequence &&
        activity.summary.text !== value.task.latestSafeSummary
      ) {
        context.addIssue({
          code: "custom",
          path: ["activities", index, "summary"],
          message: "latest task activity summary is inconsistent",
        });
      }
    }
    for (let index = 1; index < value.mailbox.length; index += 1) {
      const current = value.mailbox[index];
      const previous = value.mailbox[index - 1];
      if (
        current !== undefined &&
        previous !== undefined &&
        current.mailboxSequence <= previous.mailboxSequence
      ) {
        context.addIssue({
          code: "custom",
          path: ["mailbox", index],
          message: "task mailbox sequence must be strictly increasing",
        });
      }
    }
  });
const TaskObserveResultSchema = z
  .object({
    observationId: TaskObservationIdSchema,
    taskThreadId: ThreadIdSchema,
    revision: RevisionSchema,
  })
  .strict();
const TaskMessageResultSchema = z
  .object({
    accepted: z.literal(true),
    messageId: TaskMessageIdSchema,
    mailboxSequence: z.number().int().min(1).max(MAX_SAFE_INTEGER),
  })
  .strict();
const TaskFollowupResultSchema = z
  .object({
    accepted: z.literal(true),
    messageId: TaskMessageIdSchema,
    turnId: TurnIdSchema,
    task: TaskSummarySchema,
  })
  .strict();
const TaskMutationResultSchema = z
  .object({ accepted: z.literal(true), task: TaskSummarySchema })
  .strict();
/** 关闭回执没有 revision，只有 true 才允许前端移除临时 Tab。 */
const TaskCloseResultSchema = z.object({ closed: z.literal(true) }).strict();
const TaskTreeDeleteResultSchema = z
  .object({ accepted: z.literal(true), deletedTaskCount: z.number().int().min(1).max(64) })
  .strict();

const ThreadTaskActivitySchema = z
  .object({ activity: TaskActivitySchema, task: TaskSummarySchema })
  .strict();

const ThreadGoalActivitySchema = z
  .object({
    goalId: GoalIdSchema,
    objective: z.string().min(1).max(32_768),
    status: z.enum(["achieved", "stopped"]),
    goalRevision: RevisionSchema.refine((value) => value > 0),
    eventSequence: RevisionSchema.refine((value) => value > 0),
    occurredAt: TimestampSchema,
  })
  .strict();

/**
 * Thread 快照只恢复当前根任务的有界低频 Task Activity；跨记录顺序和 identity 必须在客户端
 * 再次关联校验，因为 JSON Schema 无法表达这些不变量，Child Thread 的非空投影也会自然失败关闭。
 */
export const ThreadReadResultSchema = z
  .object({
    threadId: ThreadIdSchema,
    revision: RevisionSchema,
    turns: z.array(ThreadSnapshotTurnSchema).max(200),
    items: z.array(ThreadItemSchema).max(200),
    taskActivities: z.array(ThreadTaskActivitySchema).max(128),
    goalActivities: z.array(ThreadGoalActivitySchema).max(128),
    inputQueue: InputQueueSchema.nullable(),
    contextUsage: ThreadRequestUsageSchema.nullable(),
    nextCursor: CursorSchema.nullable(),
  })
  .strict()
  .superRefine((value, context) => {
    const activityIds = new Set<string>();
    for (let index = 0; index < value.taskActivities.length; index += 1) {
      const entry = value.taskActivities[index];
      const previous = value.taskActivities[index - 1];
      if (entry === undefined) continue;
      if (
        entry.activity.taskThreadId !== entry.task.taskThreadId ||
        entry.activity.rootThreadId !== entry.task.rootThreadId ||
        entry.task.taskKind !== "subagent" ||
        entry.task.parentThreadId !== value.threadId ||
        entry.activity.activitySequence > entry.task.latestActivitySequence ||
        (previous !== undefined &&
          entry.activity.activitySequence <= previous.activity.activitySequence) ||
        activityIds.has(entry.activity.activityId)
      ) {
        context.addIssue({
          code: "custom",
          path: ["taskActivities", index],
          message: "thread task activity projection is inconsistent",
        });
      }
      activityIds.add(entry.activity.activityId);
    }
    const goalIds = new Set<string>();
    for (let index = 0; index < value.goalActivities.length; index += 1) {
      const entry = value.goalActivities[index];
      const previous = value.goalActivities[index - 1];
      if (
        entry === undefined ||
        goalIds.has(entry.goalId) ||
        (previous !== undefined && entry.eventSequence <= previous.eventSequence)
      ) {
        context.addIssue({
          code: "custom",
          path: ["goalActivities", index],
          message: "thread goal activity projection is inconsistent",
        });
      }
      if (entry !== undefined) goalIds.add(entry.goalId);
    }
  });

export const ResultSchemaByMethod = {
  "runtime/initialize": initializeResultSchema,
  "runtime/health": healthResultSchema,
  "runtime/shutdown": shutdownResultSchema,
  "workspace/open": workspaceResultSchema,
  "workspace/list": workspacePageResultSchema,
  "workspace/path/search": workspacePathSearchResultSchema,
  "workspace/open-general": workspaceResultSchema,
  "workspace/set-trust": acceptedResultSchema,
  "workspace/unregister": acceptedResultSchema,
  "thread/create": threadResultSchema,
  "thread/list": threadListResultSchema,
  "thread/search": threadPageResultSchema,
  "thread/read": ThreadReadResultSchema,
  "thread/rename": threadResultSchema,
  "thread/pin": threadResultSchema,
  "thread/seen": threadResultSchema,
  "thread/preferences/update": threadResultSchema,
  "thread/archive": threadResultSchema,
  "thread/restore": threadResultSchema,
  "thread/delete": acceptedResultSchema,
  "thread/compact": threadCompactResultSchema,
  ...GoalResultSchemaByMethod,
  ...InteractionResultSchemaByMethod,
  "task/create": TaskCreateResultSchema,
  "task/list": TaskListResultSchema,
  "task/read": TaskReadResultSchema,
  "task/observe": TaskObserveResultSchema,
  "task/unobserve": acceptedResultSchema,
  "task/seen": TaskMutationResultSchema,
  "task/close": TaskCloseResultSchema,
  "thread/message/send": TaskMessageResultSchema,
  "task/followup": TaskFollowupResultSchema,
  "task/cancel": TaskMutationResultSchema,
  "task/tree/delete": TaskTreeDeleteResultSchema,
  "attachment/import": AttachmentResultSchema,
  "attachment/discard": AttachmentResultSchema,
  "attachment/preview/open": AttachmentPreviewOpenResultSchema,
  "attachment/preview/read": AttachmentPreviewReadResultSchema,
  "attachment/preview/close": AttachmentPreviewCloseResultSchema,
  "turn/start": turnAcceptedResultSchema,
  "turn/resume": turnResumeResultSchema,
  "turn/cancel": turnCancelResultSchema,
  "turn/input/enqueue": InputQueueMutationResultSchema,
  "turn/input/prioritize": InputQueueMutationResultSchema,
  "turn/input/update": InputQueueMutationResultSchema,
  "turn/input/delete": InputQueueMutationResultSchema,
  "turn/change-set/read": turnChangeSetReadResultSchema,
  "configuration/read": ConfigReadResultSchema,
  "configuration/patch": ConfigWriteResultSchema,
  "configuration/replace": ConfigWriteResultSchema,
  "configuration/reset": ConfigWriteResultSchema,
  "credential/set": CredentialSetResultSchema,
  "credential/delete": CredentialDeleteResultSchema,
  "approval/respond": approvalRespondResultSchema,
  "skill/list": skillPageResultSchema,
  "mcp/list": mcpPageResultSchema,
  "mcp/test": mcpTestResultSchema,
  "model/test": modelTestResultSchema,
  "mcp/list-tools": mcpToolsResultSchema,
  "tool/artifact/read": toolArtifactReadResultSchema,
} satisfies Record<z.infer<typeof ClientMethodSchema>, z.ZodTypeAny>;

const RpcErrorDataSchema = z
  .object({
    errorCode: z.string().regex(/^[A-Z][A-Z0-9_]{1,63}$/),
    category: z.enum([
      "protocol",
      "validation",
      "conflict",
      "not_found",
      "permission",
      "capacity",
      "unavailable",
      "timeout",
      "cancelled",
      "internal",
    ]),
    retryable: z.boolean(),
    errorId: z.string().regex(/^err_[0-9a-f]{32}$/),
    retryAfterMs: z.number().int().min(1).max(3_600_000).optional(),
  })
  .strict()
  .superRefine((value, context) => {
    if (value.retryAfterMs !== undefined && !value.retryable) {
      context.addIssue({
        code: "custom",
        path: ["retryAfterMs"],
        message: "retry delay requires retryable error",
      });
    }
  });
export const RpcErrorSchema = z
  .object({
    code: z.number().int().min(-32_768).max(-32_000),
    message: z.string().min(1).max(512),
    data: RpcErrorDataSchema,
  })
  .strict();

const ResponseEnvelopeSchema = z
  .object({
    jsonrpc: z.literal("2.0"),
    id: RpcRequestIdSchema,
    result: JsonObjectSchema.optional(),
    error: RpcErrorSchema.optional(),
  })
  .strict()
  .superRefine((value, context) => {
    if ((value.result === undefined) === (value.error === undefined)) {
      context.addIssue({
        code: "custom",
        message: "response must contain exactly one result or error",
      });
    }
  });

const RequestEnvelopeSchema = z
  .object({
    jsonrpc: z.literal("2.0"),
    id: ClientRequestIdSchema,
    method: ClientMethodSchema,
    params: JsonObjectSchema,
  })
  .strict();

const InitializedParamsSchema = z
  .object({ readyToken: z.string().regex(READY_TOKEN_PATTERN) })
  .strict();
const InitializedNotificationSchema = z
  .object({
    jsonrpc: z.literal("2.0"),
    method: z.literal("runtime/initialized"),
    params: InitializedParamsSchema,
  })
  .strict();

const semanticBaseSchema = z
  .object({
    serverInstanceId: ServerInstanceIdSchema,
    eventId: EventIdSchema,
    sequence: z.number().int().min(1).max(MAX_SAFE_INTEGER),
    generation: z.number().int().min(1).max(MAX_SAFE_INTEGER),
    workspaceId: WorkspaceIdSchema,
    threadId: ThreadIdSchema,
    turnId: TurnIdSchema,
    threadRevision: RevisionSchema,
    occurredAt: TimestampSchema,
  })
  .strict();
const contextCompactionBaseSchema = z
  .object({
    serverInstanceId: ServerInstanceIdSchema,
    eventId: EventIdSchema,
    sequence: z.number().int().min(1).max(MAX_SAFE_INTEGER),
    generation: z.number().int().min(1).max(MAX_SAFE_INTEGER),
    workspaceId: WorkspaceIdSchema,
    threadId: ThreadIdSchema,
    turnId: TurnIdSchema.nullable(),
    threadRevision: RevisionSchema,
    occurredAt: TimestampSchema,
    compactionId: CompactionIdSchema,
    trigger: z.enum(["automatic", "manual", "overflow_recovery"]),
    sourceRevision: RevisionSchema,
    strategyVersion: z.literal("ja-context-v1"),
  })
  .strict();
const legalTransitions: Readonly<
  Record<z.infer<typeof TurnStateSchema>, readonly z.infer<typeof TurnStateSchema>[]>
> = {
  queued: ["running", "suspended", "completed", "failed", "cancelled"],
  running: ["waiting_approval", "suspended", "completed", "failed", "cancelled"],
  waiting_approval: ["running", "suspended", "completed", "failed", "cancelled"],
  suspended: ["queued", "cancelled"],
  completed: [],
  failed: [],
  cancelled: [],
};

/** 强制公开七状态生命周期；Suspended 只能显式恢复或取消，不能被后台静默终结为失败。 */
function isLegalTurnTransition(
  from: z.infer<typeof TurnStateSchema>,
  to: z.infer<typeof TurnStateSchema>,
): boolean {
  return legalTransitions[from].includes(to);
}

const stateChangedParamsSchema = semanticBaseSchema
  .extend({
    from: TurnStateSchema,
    to: TurnStateSchema,
  })
  .strict()
  .refine((value) => isLegalTurnTransition(value.from, value.to), "illegal turn state transition");
const modelUsageSchema = RequestUsageSchema;

/** 队列变化独立于执行 CAS，只携带全量队列 revision，不能伪造 Thread revision 推进。 */
const inputQueueEventBaseSchema = z
  .object({
    serverInstanceId: ServerInstanceIdSchema,
    eventId: EventIdSchema,
    sequence: z.number().int().min(1).max(MAX_SAFE_INTEGER),
    occurredAt: TimestampSchema,
    generation: z.number().int().min(1).max(MAX_SAFE_INTEGER),
    workspaceId: WorkspaceIdSchema,
    threadId: ThreadIdSchema,
    turnId: TurnIdSchema,
  })
  .strict();
const inputQueueChangedParamsSchema = inputQueueEventBaseSchema
  .extend({ inputQueue: InputQueueSchema })
  .strict()
  .refine((value) => value.turnId === value.inputQueue.turnId, {
    path: ["inputQueue", "turnId"],
    message: "input queue event must reference the same turn",
  });
const consumedUserItemSchema = z
  .object({
    itemId: ItemIdSchema,
    createdAt: TimestampSchema,
    turnId: TurnIdSchema,
    kind: z.literal("user_input"),
    content: TurnContentSchema,
    attachments: z.array(AttachmentSummarySchema).max(10),
  })
  .strict()
  .superRefine(requireMatchingAttachmentSummaries);
const consumedAssistantSettlementSchema = z
  .object({
    messageId: ItemIdSchema,
    text: BoundedTextSchema,
    modelRound: z.number().int().min(1).max(128),
    usage: modelUsageSchema.optional(),
    reasoningSummary: BoundedTextSchema.optional(),
  })
  .strict()
  .refine((value) => value.usage === undefined || value.usage.modelRound === value.modelRound, {
    path: ["usage", "modelRound"],
    message: "assistant settlement usage must match its model round",
  });
const inputConsumedParamsSchema = semanticBaseSchema
  .extend({
    input: QueuedInputSchema,
    userItem: consumedUserItemSchema,
    inputQueue: InputQueueSchema,
    assistantSettlement: consumedAssistantSettlementSchema.optional(),
  })
  .strict()
  .superRefine((value, context) => {
    if (
      value.input.turnId !== value.turnId ||
      value.userItem.turnId !== value.turnId ||
      value.inputQueue.turnId !== value.turnId
    )
      context.addIssue({
        code: "custom",
        path: ["turnId"],
        message: "consumed input payload must reference the same turn",
      });
    if (JSON.stringify(value.input.content) !== JSON.stringify(value.userItem.content))
      context.addIssue({
        code: "custom",
        path: ["userItem", "content"],
        message: "consumed user item must preserve queued input content",
      });
    if (JSON.stringify(value.input.attachments) !== JSON.stringify(value.userItem.attachments))
      context.addIssue({
        code: "custom",
        path: ["userItem", "attachments"],
        message: "consumed user item must preserve queued attachment summaries",
      });
  });
/** Mailbox 消费一次提交一批跨会话消息；消息作为独立历史事实，不伪造用户输入或 Task Activity。 */
const messagesReceivedParamsSchema = semanticBaseSchema
  .extend({
    items: z
      .array(
        SnapshotItemBaseSchema.extend({
          kind: z.literal("thread_message"),
          sourceThreadId: ThreadIdSchema,
          sourceTitle: SafeNameSchema.refine(
            (value) => value.trim().length > 0,
            "source title must not be blank",
          ),
          content: BoundedTextSchema,
        }).strict(),
      )
      .min(1)
      .max(256),
  })
  .strict()
  .superRefine((value, context) => {
    const itemIds = new Set<string>();
    value.items.forEach((item, index) => {
      if (item.turnId !== value.turnId) {
        context.addIssue({
          code: "custom",
          path: ["items", index, "turnId"],
          message: "received message must reference the event turn",
        });
      }
      if (itemIds.has(item.itemId)) {
        context.addIssue({
          code: "custom",
          path: ["items", index, "itemId"],
          message: "received message item ids must be unique",
        });
      }
      itemIds.add(item.itemId);
    });
  });
const committedToolCallSchema = z
  .object({
    callId: CallIdSchema,
    toolName: SafeIdentifierSchema,
    presentation: ToolPresentationSchema,
    ordinal: z.number().int().min(0).max(1_023),
  })
  .strict();
/**
 * Tool ordinal 由 Java 在整个 Turn 内分配，因此后续 ModelStep 可以从非零值开始；
 * 此处只约束单个已提交批次保持原顺序连续，不能把 Provider 的轮次局部序号误当成 wire 事实。
 */
const modelStepCommittedParamsSchema = semanticBaseSchema
  .extend({
    messageId: ItemIdSchema,
    text: BoundedTextSchema,
    modelRound: z.number().int().min(1).max(128),
    usage: modelUsageSchema.optional(),
    reasoningSummary: BoundedTextSchema.optional(),
    toolCalls: z.array(committedToolCallSchema).min(1).max(128),
  })
  .strict()
  .superRefine((value, context) => {
    if (value.usage !== undefined && value.usage.modelRound !== value.modelRound)
      context.addIssue({
        code: "custom",
        path: ["usage", "modelRound"],
        message: "model step usage must match its model round",
      });
    const callIds = new Set<string>();
    const firstOrdinal = value.toolCalls[0]?.ordinal;
    value.toolCalls.forEach((call, index) => {
      if (firstOrdinal === undefined || call.ordinal !== firstOrdinal + index)
        context.addIssue({
          code: "custom",
          path: ["toolCalls", index, "ordinal"],
          message: "tool calls must preserve contiguous turn-global ordinals within the batch",
        });
      if (callIds.has(call.callId))
        context.addIssue({
          code: "custom",
          path: ["toolCalls", index, "callId"],
          message: "tool call ids must be unique",
        });
      callIds.add(call.callId);
    });
  });
const deltaParamsSchema = semanticBaseSchema
  .extend({
    streamSeq: z.number().int().min(1).max(MAX_SAFE_INTEGER),
    text: BoundedTextSchema,
  })
  .strict();
const committedToolResultSchema = z
  .object({
    callId: CallIdSchema,
    outcome: ToolOutcomeSchema,
    presentation: ToolPresentationSchema,
    ordinal: z.number().int().min(0).max(1_023),
    errorCode: SafeIdentifierSchema.optional(),
  })
  .strict();
/** started 只携带 Prepared Tool 的稳定双重关联，展示状态由既有安全 presentation 原位派生。 */
const toolStartedParamsSchema = semanticBaseSchema
  .extend({
    callId: CallIdSchema,
    ordinal: z.number().int().min(0).max(1_023),
  })
  .strict();
/** Tool 回执复用统一 Turn 事件元数据，确保重启隔离和跨事件排序一致。 */
const toolBatchCommittedParamsSchema = semanticBaseSchema
  .extend({
    results: z.array(committedToolResultSchema).min(1).max(128),
  })
  .strict()
  .superRefine((value, context) => {
    const callIds = new Set<string>();
    value.results.forEach((result, index) => {
      if (callIds.has(result.callId))
        context.addIssue({
          code: "custom",
          path: ["results", index, "callId"],
          message: "tool result call ids must be unique",
        });
      callIds.add(result.callId);
    });
  });
const approvalRequestedParamsSchema = semanticBaseSchema
  .extend({
    approvalId: ApprovalIdSchema,
    callId: CallIdSchema,
    toolName: SafeIdentifierSchema,
    reason: BoundedTextSchema,
    expiresAt: TimestampSchema,
    from: z.literal("running"),
    to: z.literal("waiting_approval"),
  })
  .strict();
const approvalResolvedParamsSchema = semanticBaseSchema
  .extend({
    approvalId: ApprovalIdSchema,
    decision: ApprovalDecisionSchema,
    from: z.literal("waiting_approval"),
    to: z.literal("running"),
  })
  .strict();
const contextCompactionStartedParamsSchema = contextCompactionBaseSchema
  .extend({
    inputTokensBefore: RevisionSchema,
    inputTokensAfter: z.null(),
  })
  .strict();
const contextCompactedParamsSchema = contextCompactionBaseSchema
  .extend({
    inputTokensBefore: RevisionSchema,
    inputTokensAfter: RevisionSchema,
    checkpointId: CheckpointIdSchema,
  })
  .strict()
  .refine((value) => value.inputTokensAfter < value.inputTokensBefore, {
    path: ["inputTokensAfter"],
    message: "compacted context must reduce input tokens",
  });
const contextCompactionFailedParamsSchema = contextCompactionBaseSchema
  .extend({
    inputTokensBefore: RevisionSchema.nullable(),
    inputTokensAfter: z.null(),
    errorCode: z.enum([
      "THREAD_NOT_FOUND",
      "CONFLICT",
      "THREAD_BUSY",
      "SUMMARY_FAILURE",
      "CONTEXT_LIMIT",
      "CANCELLED",
      "INVALID_STATE",
    ]),
  })
  .strict();
const terminalUsageSchema = modelUsageSchema;
const terminalMessageSchema = z
  .object({ messageId: ItemIdSchema, text: BoundedTextSchema })
  .strict();
const terminalParamsSchema = semanticBaseSchema
  .extend({
    state: TerminalStateSchema,
    summary: BoundedTextSchema,
    finalMessage: terminalMessageSchema.optional(),
    usage: terminalUsageSchema.optional(),
    errorCode: SafeIdentifierSchema.optional(),
    errorMessage: BoundedTextSchema.optional(),
    changeSet: TurnChangeSetSchema,
  })
  .strict()
  .superRefine((value, context) => {
    const hasErrorCode = value.errorCode !== undefined;
    const hasErrorMessage = value.errorMessage !== undefined;
    if (value.state !== "cancelled" && value.finalMessage === undefined) {
      context.addIssue({
        code: "custom",
        path: ["finalMessage"],
        message: "completed and failed terminal require finalMessage",
      });
    }
    if (value.state === "cancelled" && value.finalMessage !== undefined) {
      context.addIssue({
        code: "custom",
        path: ["finalMessage"],
        message: "cancelled terminal cannot carry finalMessage",
      });
    }
    if (value.state === "failed" && (!hasErrorCode || !hasErrorMessage)) {
      context.addIssue({
        code: "custom",
        path: ["errorCode"],
        message: "failed terminal requires an error pair",
      });
    }
    if (value.state !== "failed" && (hasErrorCode || hasErrorMessage)) {
      context.addIssue({
        code: "custom",
        path: ["errorCode"],
        message: "only failed terminal may carry errors",
      });
    }
  });
const runtimeStatusParamsSchema = z
  .object({
    serverInstanceId: ServerInstanceIdSchema,
    eventId: EventIdSchema,
    sequence: z.number().int().min(1).max(MAX_SAFE_INTEGER),
    occurredAt: TimestampSchema,
    status: z.enum(["starting", "ready", "shutting_down", "stopped", "failed"]),
    readyToken: z.string().regex(READY_TOKEN_PATTERN).optional(),
    reason: z
      .enum([
        "initialize",
        "user_requested",
        "host_shutdown",
        "shutdown_complete",
        "runtime_lifecycle",
      ])
      .optional(),
    generation: z.number().int().min(1).max(MAX_SAFE_INTEGER),
    features: RuntimeFeaturesSchema,
  })
  .strict()
  .superRefine((value, context) => {
    const valid =
      value.status === "starting"
        ? value.reason === "initialize" && value.readyToken === undefined
        : value.status === "ready"
          ? value.reason === undefined && value.readyToken !== undefined
          : value.status === "shutting_down"
            ? (value.reason === "user_requested" || value.reason === "host_shutdown") &&
              value.readyToken === undefined
            : value.status === "stopped"
              ? value.reason === "shutdown_complete" && value.readyToken === undefined
              : value.reason === "runtime_lifecycle" && value.readyToken === undefined;
    if (!valid) {
      context.addIssue({
        code: "custom",
        path: ["status"],
        message: "runtime lifecycle fields do not match status",
      });
    }
  });
const configChangedParamsSchema = z
  .object({
    serverInstanceId: ServerInstanceIdSchema,
    eventId: EventIdSchema,
    sequence: z.number().int().min(1).max(MAX_SAFE_INTEGER),
    occurredAt: TimestampSchema,
    generation: z.number().int().min(1).max(MAX_SAFE_INTEGER),
    scope: z.enum(["user", "project"]),
    /** 项目失效通知使用服务端工作区身份，用户作用域不绑定工作区。 */
    workspaceId: WorkspaceIdSchema.optional(),
    version: ConfigVersionSchema,
  })
  .strict()
  .superRefine((value, context) => {
    if (value.scope === "project" && value.workspaceId === undefined) {
      context.addIssue({
        code: "custom",
        path: ["workspaceId"],
        message: "project config changes require workspaceId",
      });
    }
    if (value.scope === "user" && value.workspaceId !== undefined) {
      context.addIssue({
        code: "custom",
        path: ["workspaceId"],
        message: "user config changes cannot carry workspaceId",
      });
    }
  });

/** admission 短标题与迟到的自动/人工标题共用元数据事件，UI 不依赖 terminal 时序或轮询。 */
const threadMetadataChangedParamsSchema = z
  .object({
    serverInstanceId: ServerInstanceIdSchema,
    eventId: EventIdSchema,
    sequence: z.number().int().min(1).max(MAX_SAFE_INTEGER),
    occurredAt: TimestampSchema,
    generation: z.number().int().min(1).max(MAX_SAFE_INTEGER),
    workspaceId: WorkspaceIdSchema,
    threadId: ThreadIdSchema,
    revision: RevisionSchema,
    title: SafeNameSchema,
    titleSource: z.enum(["placeholder", "auto", "manual"]),
  })
  .strict();

const taskEventBaseSchema = z
  .object({
    serverInstanceId: ServerInstanceIdSchema,
    eventId: EventIdSchema,
    sequence: z.number().int().min(1).max(MAX_SAFE_INTEGER),
    occurredAt: TimestampSchema,
    generation: z.number().int().min(1).max(MAX_SAFE_INTEGER),
    rootThreadId: ThreadIdSchema,
    taskThreadId: ThreadIdSchema,
    taskRevision: RevisionSchema,
  })
  .strict();
const taskActivityParamsSchema = taskEventBaseSchema
  .extend({ activity: TaskActivitySchema, task: TaskSummarySchema })
  .strict()
  .superRefine((value, context) => {
    if (
      value.activity.taskThreadId !== value.taskThreadId ||
      value.activity.rootThreadId !== value.task.rootThreadId ||
      value.task.taskThreadId !== value.taskThreadId ||
      value.task.rootThreadId !== value.rootThreadId ||
      value.task.revision !== value.taskRevision ||
      value.activity.activitySequence !== value.task.latestActivitySequence ||
      value.activity.summary.text !== value.task.latestSafeSummary
    ) {
      context.addIssue({
        code: "custom",
        path: [],
        message: "task activity event is inconsistent",
      });
    }
  });
const taskProgressParamsSchema = taskEventBaseSchema
  .extend({
    observationId: TaskObservationIdSchema,
    progressRevision: RevisionSchema,
    safeSummary: PreviewTextSchema,
  })
  .strict();
const taskMailboxChangedParamsSchema = taskEventBaseSchema
  .extend({
    mailboxSequence: z.number().int().min(1).max(MAX_SAFE_INTEGER),
    unreadCount: RevisionSchema,
  })
  .strict();

const notification = <M extends string, S extends z.ZodType<Record<string, unknown>>>(
  method: M,
  params: S,
) => z.object({ jsonrpc: z.literal("2.0"), method: z.literal(method), params }).strict();

/** @public Windows 真窗 smoke 在浏览器上下文中按 Vite 绝对路径动态导入此闭集。 */
export const JaEventSchema = z.discriminatedUnion("method", [
  notification("runtime/status-changed", runtimeStatusParamsSchema),
  notification("configuration/changed", configChangedParamsSchema),
  notification("turn/input-queue-changed", inputQueueChangedParamsSchema),
  notification("turn/input-consumed", inputConsumedParamsSchema),
  notification("turn/messages_received", messagesReceivedParamsSchema),
  notification("turn/state-changed", stateChangedParamsSchema),
  notification("assistant/model-step-committed", modelStepCommittedParamsSchema),
  notification("assistant/text-delta", deltaParamsSchema),
  notification("assistant/reasoning-summary-delta", deltaParamsSchema),
  notification("tool/started", toolStartedParamsSchema),
  notification("tool/batch-committed", toolBatchCommittedParamsSchema),
  notification("approval/requested", approvalRequestedParamsSchema),
  notification("approval/resolved", approvalResolvedParamsSchema),
  notification("context/compaction-started", contextCompactionStartedParamsSchema),
  notification("context/compacted", contextCompactedParamsSchema),
  notification("context/compaction-failed", contextCompactionFailedParamsSchema),
  notification("turn/terminal", terminalParamsSchema),
  notification("thread/metadata-changed", threadMetadataChangedParamsSchema),
  notification("task/activity", taskActivityParamsSchema),
  notification("task/progress", taskProgressParamsSchema),
  notification("task/mailbox-changed", taskMailboxChangedParamsSchema),
  notification("goal/changed", GoalChangedParamsSchema),
  notification("goal/activity", GoalActivityParamsSchema),
  notification("interaction/changed", InteractionChangedParamsSchema),
  notification("plan/changed", PlanChangedParamsSchema),
]);
export type Thread = z.infer<typeof ThreadSchema>;
export type UserContentBlock = z.infer<typeof UserContentBlockSchema>;
export type AttachmentSummary = z.infer<typeof AttachmentSummarySchema>;
export type QueuedInput = z.infer<typeof QueuedInputSchema>;
export type InputQueue = z.infer<typeof InputQueueSchema>;
export type InputQueueMutationResult = z.infer<typeof InputQueueMutationResultSchema>;
export type ThreadReadResult = z.infer<typeof ThreadReadResultSchema>;
export type TaskSummary = z.infer<typeof TaskSummarySchema>;
export type TaskActivity = z.infer<typeof TaskActivitySchema>;
export type TaskMailboxMessage = z.infer<typeof TaskMailboxMessageSchema>;
export type RequestEnvelope = z.infer<typeof RequestEnvelopeSchema>;
export type ResponseEnvelope = z.infer<typeof ResponseEnvelopeSchema>;
export type NotificationEnvelope =
  | z.infer<typeof InitializedNotificationSchema>
  | z.infer<typeof JaEventSchema>;
export type ReadyToken = z.infer<typeof InitializedParamsSchema>["readyToken"];
export type RpcError = z.infer<typeof RpcErrorSchema>;
export type JaEvent = z.infer<typeof JaEventSchema>;
export type InitializedNotification = z.infer<typeof InitializedNotificationSchema>;

interface PayloadSafetyOptions {
  allowConfigSecrets?: boolean;
  allowCredentialSecret?: boolean;
  allowAuthorizationPath?: readonly string[];
}

const SENSITIVE_KEYS = new Set([
  "apikey",
  "secretvalue",
  "credentialvalue",
  "password",
  "authorization",
  "accesstoken",
  "secret",
  "token",
  "credential",
  "cookie",
]);

/** Schema 投影前拒绝环、prototype pollution、超大树和 secret 形状输出。 */
export function assertSafePayload(value: unknown, options: PayloadSafetyOptions = {}): void {
  const seen = new WeakSet<object>();
  const budget = { nodes: 0 };
  const visit = (current: unknown, depth: number, path: readonly string[]): void => {
    if (typeof current === "string") {
      if (current.length > 4_194_304 || current.includes("\u0000"))
        throw new Error("payload string is invalid");
      return;
    }
    if (current === null || typeof current !== "object") return;
    if (depth > 64 || seen.has(current)) throw new Error("payload nesting or cycle is invalid");
    budget.nodes += 1;
    if (budget.nodes > 20_000) throw new Error("payload contains too many nodes");
    seen.add(current);
    for (const [key, child] of Object.entries(current)) {
      const normalized = key.replaceAll("_", "").replaceAll("-", "").toLowerCase();
      const childPath = [...path, key];
      const allowedAuthorization =
        normalized === "authorization" &&
        options.allowAuthorizationPath !== undefined &&
        childPath.length === options.allowAuthorizationPath.length &&
        childPath.every((part, index) => part === options.allowAuthorizationPath?.[index]);
      const allowedSecret =
        (options.allowConfigSecrets && (normalized === "apikey" || normalized === "secretvalue")) ||
        (options.allowCredentialSecret && normalized === "secret") ||
        allowedAuthorization;
      if (
        key === "__proto__" ||
        key === "constructor" ||
        key === "prototype" ||
        (SENSITIVE_KEYS.has(normalized) && !allowedSecret)
      ) {
        throw new Error("unsafe payload property");
      }
      visit(child, depth + 1, childPath);
    }
    seen.delete(current);
  };
  visit(value, 0, []);
}

interface ReadyTokenLeakOptions {
  allowChallengePath?: readonly ["params", "readyToken"];
  knownTokens?: readonly string[];
  knownTokenFingerprints?: readonly string[];
  isKnownReadyToken?: (value: string) => boolean;
  allowConfigSecrets?: boolean;
  allowCredentialSecret?: boolean;
  allowAuthorizationPath?: readonly string[];
}

/** 扫描宽松 payload 的叶节点，ready challenge 只能存在于唯一握手字段。 */
export function assertNoReadyTokenLeak(value: unknown, options: ReadyTokenLeakOptions = {}): void {
  assertSafePayload(value, {
    allowConfigSecrets: options.allowConfigSecrets,
    allowCredentialSecret: options.allowCredentialSecret,
    allowAuthorizationPath: options.allowAuthorizationPath,
  });
  const knownFingerprints = new Set(options.knownTokenFingerprints ?? []);
  for (const token of options.knownTokens ?? []) {
    if (READY_TOKEN_PATTERN.test(token)) knownFingerprints.add(fingerprintReadyToken(token));
  }
  const isKnown = (candidate: string): boolean =>
    knownFingerprints.has(fingerprintReadyToken(candidate)) ||
    options.isKnownReadyToken?.(candidate) === true;
  const allowedPath = (path: readonly string[]): boolean =>
    options.allowChallengePath !== undefined &&
    path.length === options.allowChallengePath.length &&
    path.every((part, index) => part === options.allowChallengePath?.[index]);
  const canMatchKnownToken = knownFingerprints.size > 0 || options.isKnownReadyToken !== undefined;
  const containsKnownToken = (text: string): boolean =>
    canMatchKnownToken && forEachReadyTokenCandidate(text, isKnown);
  const visit = (current: unknown, path: readonly string[]): void => {
    if (
      typeof current === "string" &&
      containsKnownToken(current) &&
      !(allowedPath(path) && isKnown(current) && current.length === 32)
    ) {
      throw new Error("readyToken leaked outside handshake field");
    }
    if (current === null || typeof current !== "object") return;
    for (const [key, child] of Object.entries(current)) {
      const childPath = [...path, key];
      const secretPath =
        (options.allowConfigSecrets && (key === "apiKey" || key === "secretValue")) ||
        (options.allowCredentialSecret && key === "secret");
      if (containsKnownToken(key) || (key === "readyToken" && !allowedPath(childPath)))
        throw new Error("readyToken key is not allowed");
      if (!secretPath) visit(child, childPath);
    }
  };
  visit(value, []);
}

/**
 * 校验仅客户端可用的请求 envelope；附件预览的 authorization 只是 tagged 资源归属而非凭据，
 * 因此只在该方法的精确字段路径放行，任何其它 authorization 键仍失败关闭。
 */
export function parseRequest(value: unknown): RequestEnvelope {
  const method =
    value !== null &&
    typeof value === "object" &&
    typeof (value as Record<string, unknown>)["method"] === "string"
      ? (value as Record<string, unknown>)["method"]
      : undefined;
  const allowCredentialSecret = method === "credential/set";
  const allowAuthorizationPath =
    method === "attachment/preview/open" ? (["params", "authorization"] as const) : undefined;
  assertNoReadyTokenLeak(value, { allowCredentialSecret, allowAuthorizationPath });
  const request = RequestEnvelopeSchema.parse(value);
  ParamsSchemaByMethod[request.method].parse(request.params);
  return request;
}

/** 按精确 challenge 形状校验出站 initialized 通知。 */
export function parseInitializedNotification(value: unknown): InitializedNotification {
  assertNoReadyTokenLeak(value, { allowChallengePath: ["params", "readyToken"] });
  return InitializedNotificationSchema.parse(value);
}

/** pending 关联或结果分派前先校验响应 envelope。 */
export function parseResponse(value: unknown): ResponseEnvelope {
  assertNoReadyTokenLeak(value);
  return ResponseEnvelopeSchema.parse(value);
}

/** 校验完整的 15 项语义事件闭集，并拒绝任何握手 secret。 */
export function parseEvent(
  value: unknown,
  validation: {
    expectedReadyToken?: string;
    isKnownReadyToken?: (value: string) => boolean;
  } = {},
): JaEvent {
  const isReady =
    value !== null &&
    typeof value === "object" &&
    (value as Record<string, unknown>)["method"] === "runtime/status-changed" &&
    (value as Record<string, unknown>)["params"] !== null &&
    typeof (value as Record<string, unknown>)["params"] === "object" &&
    ((value as Record<string, unknown>)["params"] as Record<string, unknown>)["status"] === "ready";
  assertNoReadyTokenLeak(value, {
    allowChallengePath: isReady ? ["params", "readyToken"] : undefined,
    knownTokens: validation.expectedReadyToken === undefined ? [] : [validation.expectedReadyToken],
    isKnownReadyToken: validation.isKnownReadyToken,
  });
  return JaEventSchema.parse(value);
}

/** initialized 属于控制面状态而非语义事件，因此必须单独路由。 */
export function parseNotification(
  value: unknown,
  validation: { expectedReadyToken?: string; isKnownReadyToken?: (value: string) => boolean } = {},
): NotificationEnvelope {
  const method =
    value !== null && typeof value === "object"
      ? (value as Record<string, unknown>)["method"]
      : undefined;
  if (method === "runtime/initialized") return parseInitializedNotification(value);
  return parseEvent(value, validation);
}
