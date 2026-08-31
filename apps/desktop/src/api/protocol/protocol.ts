// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { z } from "zod";
import {
  fingerprintReadyToken,
  forEachReadyTokenCandidate,
  READY_TOKEN_PATTERN,
} from "./readyToken";
import { ConfigDocumentSchema } from "./configDocument";

/** 首个版本刻意只接受一个精确 wire revision，禁止隐式兼容分支。 */
/** Ja v2 的配置归 app-server 所有；客户端只交换意图和投影。 */
export const JA_PROTOCOL_MAJOR = 2 as const;
export const JA_PROTOCOL_MINOR = 0 as const;

const MAX_SAFE_INTEGER = Number.MAX_SAFE_INTEGER;
/** 不依赖控制字符正则就拒绝 framing control，避免不可见规则被 lint 绕过。 */
const noControlCharacters = (value: string): boolean =>
  [...value].every((character) => {
    const code = character.codePointAt(0) ?? 0;
    return code !== 0 && code !== 10 && code !== 13;
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
/** JA-RPC v2 只允许客户端发起请求；Java 不反向发起 Tool 请求。 */
const RpcRequestIdSchema = ClientRequestIdSchema;
export const WorkspaceIdSchema = prefixedId("ws_", 99);
export const ThreadIdSchema = prefixedId("thr_", 100);
const TurnIdSchema = prefixedId("turn_", 101);
const AttachmentIdSchema = prefixedId("att_", 128);
const ItemIdSchema = prefixedId("item_", 101);
const CallIdSchema = prefixedId("call_", 101);
const ApprovalIdSchema = prefixedId("appr_", 101);
export const ProviderIdSchema = prefixedId("provider_", 128);
export const ModelIdSchema = prefixedId("model_", 128);
export const ReasoningLevelSchema = z.enum(["low", "medium", "high"]);
export const AccessModeSchema = z.enum(["approval_required", "full_access"]);
const McpIdSchema = prefixedId("mcp_", 100);
const SkillIdSchema = prefixedId("skill_", 101);
const EventIdSchema = prefixedId("evt_", 100);
const CompactionIdSchema = prefixedId("cmp_", 100);
const CheckpointIdSchema = prefixedId("checkpoint_", 107);
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
  "workspace/set-trust",
  "workspace/unregister",
  "thread/create",
  "thread/list",
  "thread/search",
  "thread/read",
  "thread/rename",
  "thread/preferences/update",
  "thread/archive",
  "thread/delete",
  "thread/compact",
  "attachment/import",
  "attachment/discard",
  "turn/start",
  "turn/cancel",
  "turn/steer",
  "turn/follow-up",
  "turn/change-set/commit",
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
  "turn/state-changed",
  "assistant/model-step-committed",
  "assistant/text-delta",
  "assistant/reasoning-summary-delta",
  "tool/batch-committed",
  "approval/requested",
  "approval/resolved",
  "context/compaction-started",
  "context/compacted",
  "context/compaction-failed",
  "workspace/dirty",
  "turn/terminal",
  "thread/metadata-changed",
]);

/** capability 数组影响分派语义前先拒绝重复值，避免同一能力被重复解释。 */
function unique<T>(values: T[]): boolean {
  return new Set(values).size === values.length;
}

const TurnContentItemSchema = z.discriminatedUnion("type", [
  z
    .object({
      type: z.literal("text"),
      text: z.string().min(1).max(4_000_000).refine(noNulCharacters),
    })
    .strict(),
  z.object({ type: z.literal("attachment"), attachmentId: AttachmentIdSchema }).strict(),
]);

/** Turn content 允许纯附件，但每轮最多十个且 identity 不得重复绑定。 */
export const TurnContentSchema = z
  .array(TurnContentItemSchema)
  .min(1)
  .max(64)
  .superRefine((items, context) => {
    const attachments = items.filter((item) => item.type === "attachment");
    if (attachments.length > 10) {
      context.addIssue({ code: "custom", message: "turn attachment count exceeds limit" });
    }
    const identities = attachments.map((item) => item.attachmentId);
    if (new Set(identities).size !== identities.length) {
      context.addIssue({ code: "custom", message: "turn attachment identity is duplicated" });
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
    preferences: z
      .object({
        providerId: ProviderIdSchema,
        modelId: ModelIdSchema,
        reasoningLevel: ReasoningLevelSchema.nullable(),
        accessMode: AccessModeSchema,
        titleSource: z.enum(["placeholder", "auto", "manual"]),
      })
      .strict()
      .nullable(),
    title: SafeNameSchema,
    status: z.enum(["active", "archived", "deleted"]),
    revision: RevisionSchema,
    createdAt: TimestampSchema,
    updatedAt: TimestampSchema,
  })
  .strict();

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
    oldPath: RelativePathSchema.optional(),
    status: z.enum(["added", "modified", "deleted", "renamed"]),
    additions: RevisionSchema.optional(),
    deletions: RevisionSchema.optional(),
    binary: z.boolean(),
    truncated: z.boolean(),
  })
  .strict()
  .superRefine((value, context) => {
    if ((value.status === "renamed") !== (value.oldPath !== undefined)) {
      context.addIssue({
        code: "custom",
        path: ["oldPath"],
        message: "oldPath is required only for renamed files",
      });
    }
    if (value.binary && (value.additions !== undefined || value.deletions !== undefined)) {
      context.addIssue({
        code: "custom",
        path: ["binary"],
        message: "binary files cannot include line statistics",
      });
    }
  });
const TurnChangeStatsSchema = z
  .object({
    files: z.number().int().min(0).max(10_000),
    additions: RevisionSchema,
    deletions: RevisionSchema,
    binaryFiles: z.number().int().min(0).max(10_000),
    truncated: z.boolean(),
  })
  .strict();
const TurnChangeSetSchema = z
  .discriminatedUnion("state", [
    z
      .object({
        state: z.literal("available"),
        files: z.array(TurnChangeFileSchema).max(10_000),
        stats: TurnChangeStatsSchema,
        artifactId: ArtifactIdSchema.optional(),
      })
      .strict(),
    z
      .object({
        state: z.literal("unavailable"),
        reason: z.enum(["concurrent_turn", "not_git", "capture_failed", "diff_too_large"]),
        files: z.array(TurnChangeFileSchema).length(0),
        stats: TurnChangeStatsSchema,
      })
      .strict()
      .superRefine((value, context) => {
        if (
          value.stats.files !== 0 ||
          value.stats.additions !== 0 ||
          value.stats.deletions !== 0 ||
          value.stats.binaryFiles !== 0 ||
          value.stats.truncated
        ) {
          context.addIssue({
            code: "custom",
            path: ["stats"],
            message: "unavailable change sets cannot report fabricated statistics",
          });
        }
      }),
  ])
  .superRefine((value, context) => {
    if (value.stats.files !== value.files.length) {
      context.addIssue({
        code: "custom",
        path: ["stats", "files"],
        message: "change set file count must match the frozen file list",
      });
    }
  });

/** Turn admission 的非敏感冻结事实用于解释历史，不允许端点、凭据或旧 Profile 进入 WebView。 */
const TurnRuntimeSnapshotSchema = z
  .object({
    providerId: ProviderIdSchema,
    modelId: ModelIdSchema,
    provider: z.enum(["openai", "anthropic"]),
    api: z.enum(["openai_responses", "anthropic_messages"]),
    upstreamModel: SafeNameSchema,
    reasoningLevel: ReasoningLevelSchema.nullable(),
    accessMode: AccessModeSchema,
    configGeneration: z
      .string()
      .regex(/^cfg_[A-Za-z0-9_-]+$/)
      .max(128),
  })
  .strict();

/** 平坦历史中的 Turn 元数据与 items 分离，避免分页条目被合并成一个 synthetic Turn。 */
const ThreadSnapshotTurnSchema = z
  .object({
    turnId: TurnIdSchema,
    status: TurnStateSchema,
    runtime: TurnRuntimeSnapshotSchema.nullable(),
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

/**
 * 用判别联合镜像 Java 拥有的 durable snapshot 词汇。
 * 每个 kind 都有不同的必填 payload；若使用可选兜底字段，会接受残缺记录，
 * 同时错误拒绝合法的 Tool 与 approval 数据。
 */
const ThreadItemSchema = z.discriminatedUnion("kind", [
  SnapshotItemBaseSchema.extend({
    kind: z.literal("user_input"),
    text: BoundedTextSchema,
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
  SnapshotItemBaseSchema.extend({
    kind: z.literal("attachment"),
    attachmentId: AttachmentIdSchema,
    displayName: SafeNameSchema,
    sizeBytes: z.number().int().min(0).max(104_857_600),
    mediaKind: z.enum(["text", "image", "pdf", "binary"]),
    mediaType: z.string().min(3).max(128),
    state: z.enum(["draft", "bound", "discarded", "expired"]),
  }).strict(),
]);

/**
 * 历史 Usage 只接受 Provider 已提交的精确计量；独立时间戳让恢复层能够与更晚的压缩事实择新。
 * BigInt 比较避免两个合法 safe integer 相加后跨过 JavaScript 精确整数边界。
 */
const ThreadContextUsageSchema = z
  .object({
    turnId: TurnIdSchema,
    modelRound: z.number().int().min(1).max(128),
    inputTokens: RevisionSchema,
    outputTokens: RevisionSchema,
    totalTokens: RevisionSchema,
    measuredAt: TimestampSchema,
  })
  .strict()
  .refine(
    (usage) => BigInt(usage.totalTokens) >= BigInt(usage.inputTokens) + BigInt(usage.outputTokens),
    "context usage total must include input and output tokens",
  );

/**
 * 快照 Usage 必须引用同一快照内的真实 Turn；客户端不得把另一条会话或已分页丢失的身份拼进指标。
 */
export const ThreadReadResultSchema = z
  .object({
    threadId: ThreadIdSchema,
    revision: RevisionSchema,
    turns: z.array(ThreadSnapshotTurnSchema).max(200),
    items: z.array(ThreadItemSchema).max(200),
    contextUsage: ThreadContextUsageSchema.nullable(),
    nextCursor: CursorSchema.nullable(),
  })
  .strict()
  .superRefine((value, context) => {
    if (
      value.contextUsage !== null &&
      !value.turns.some((turn) => turn.turnId === value.contextUsage?.turnId)
    ) {
      context.addIssue({
        code: "custom",
        path: ["contextUsage", "turnId"],
        message: "context usage must reference a snapshot turn",
      });
    }
  });

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
  })
  .strict();
const ThreadListParamsSchema = z.object({ workspaceId: WorkspaceIdSchema, ...pageParams }).strict();
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
const TurnCancelParamsSchema = z
  .object({
    turnId: TurnIdSchema,
    expectedThreadRevision: RevisionSchema,
  })
  .strict();
const TurnQueuedInputParamsSchema = z
  .object({
    turnId: TurnIdSchema,
    text: z.string().min(1).max(4_000_000).refine(noNulCharacters, "text contains NUL"),
  })
  .strict();
const ApprovalRespondParamsSchema = z
  .object({
    approvalId: ApprovalIdSchema,
    turnId: TurnIdSchema,
    decision: ApprovalDecisionSchema,
    expectedThreadRevision: RevisionSchema,
  })
  .strict();
const SkillListParamsSchema = z.object(pageParams).strict();
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
const TurnChangeArtifactSchema = z
  .object({
    sha256: z.string().regex(/^[0-9a-f]{64}$/),
    byteLength: z.number().int().min(0).max(2_097_152),
    unifiedDiff: z.string().max(2_097_152).refine(noNulCharacters, "diff contains NUL"),
  })
  .strict();
const EmptyTurnChangeStatsSchema = z
  .object({
    files: z.literal(0),
    additions: z.literal(0),
    deletions: z.literal(0),
    binaryFiles: z.literal(0),
    truncated: z.literal(false),
  })
  .strict();
/** Native-only commit 仍在公共 v2 Schema 中严格建模，WebView 不会直接调用该方法。 */
const TurnChangeSetCommitParamsSchema = z
  .discriminatedUnion("state", [
    z
      .object({
        threadId: ThreadIdSchema,
        turnId: TurnIdSchema,
        workspaceId: WorkspaceIdSchema,
        state: z.literal("available"),
        files: z.array(TurnChangeFileSchema).max(10_000),
        stats: TurnChangeStatsSchema,
        artifact: TurnChangeArtifactSchema.optional(),
      })
      .strict(),
    z
      .object({
        threadId: ThreadIdSchema,
        turnId: TurnIdSchema,
        workspaceId: WorkspaceIdSchema,
        state: z.literal("unavailable"),
        reason: z.enum(["concurrent_turn", "not_git", "capture_failed", "diff_too_large"]),
        files: z.array(TurnChangeFileSchema).length(0),
        stats: EmptyTurnChangeStatsSchema,
      })
      .strict(),
  ])
  .superRefine((value, context) => {
    if (value.stats.files !== value.files.length) {
      context.addIssue({
        code: "custom",
        path: ["stats", "files"],
        message: "change set commit count must match the frozen file list",
      });
    }
  });
const TurnChangeSetReadParamsSchema = z
  .object({
    threadId: ThreadIdSchema,
    turnId: TurnIdSchema,
    artifactId: ArtifactIdSchema,
    offsetBytes: z.number().int().min(0).max(2_097_152),
    limitBytes: z.number().int().min(1).max(65_536),
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
  "workspace/open-general": WorkspaceGeneralReadParamsSchema,
  "workspace/set-trust": WorkspaceTrustParamsSchema,
  "workspace/unregister": WorkspaceUnregisterParamsSchema,
  "thread/create": ThreadCreateParamsSchema,
  "thread/list": ThreadListParamsSchema,
  "thread/search": ThreadSearchParamsSchema,
  "thread/read": ThreadReadParamsSchema,
  "thread/rename": ThreadRenameParamsSchema,
  "thread/preferences/update": ThreadPreferencesUpdateParamsSchema,
  "thread/archive": threadMutationParams,
  "thread/delete": threadMutationParams,
  "thread/compact": ThreadCompactParamsSchema,
  "attachment/import": AttachmentImportParamsSchema,
  "attachment/discard": AttachmentDiscardParamsSchema,
  "turn/start": TurnStartParamsSchema,
  "turn/cancel": TurnCancelParamsSchema,
  "turn/steer": TurnQueuedInputParamsSchema,
  "turn/follow-up": TurnQueuedInputParamsSchema,
  "turn/change-set/commit": TurnChangeSetCommitParamsSchema,
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
        engineVersion: SafeNameSchema,
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
const acceptedResultSchema = z.object({ accepted: z.literal(true) }).strict();
const threadResultSchema = ThreadSchema;
const threadPageResultSchema = z
  .object({ items: z.array(threadResultSchema).max(200), nextCursor: CursorSchema.nullable() })
  .strict();
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
    boundTurnId: TurnIdSchema.nullable(),
  })
  .strict()
  .superRefine((value, context) => {
    if ((value.state === "bound") !== (value.boundTurnId !== null)) {
      context.addIssue({
        code: "custom",
        path: ["boundTurnId"],
        message: "attachment binding state is inconsistent",
      });
    }
  });
const turnAcceptedResultSchema = z
  .object({
    accepted: z.literal(true),
    queued: z.boolean(),
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
const turnQueuedInputResultSchema = z
  .object({
    accepted: z.literal(true),
    inputId: prefixedId("input_", 128),
    turnId: TurnIdSchema,
    kind: z.enum(["steering", "follow_up"]),
    status: z.literal("queued"),
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
    scope: z.enum(["builtin", "user", "workspace"]),
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
    status: z.enum(["healthy", "degraded", "unavailable", "disabled"]),
    toolCount: z.number().int().min(0).max(2_000),
  })
  .strict();
const mcpPageResultSchema = z
  .object({ items: z.array(mcpProjectionSchema).max(200), nextCursor: CursorSchema.nullable() })
  .strict();
const mcpTestResultSchema = z
  .object({
    mcpId: McpIdSchema,
    status: z.enum(["healthy", "degraded", "unavailable"]),
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
const turnChangeSetCommitResultSchema = z
  .object({ accepted: z.literal(true), changeSet: TurnChangeSetSchema })
  .strict();
const turnChangeSetReadResultSchema = z
  .object({
    artifactId: ArtifactIdSchema,
    offsetBytes: z.number().int().min(0).max(2_097_152),
    nextOffsetBytes: z.number().int().min(0).max(2_097_152).nullable(),
    byteLength: z.number().int().min(0).max(2_097_152),
    truncated: z.boolean(),
    content: z.string().max(65_536).refine(noNulCharacters, "diff page contains NUL"),
  })
  .strict();
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

export const ResultSchemaByMethod = {
  "runtime/initialize": initializeResultSchema,
  "runtime/health": healthResultSchema,
  "runtime/shutdown": shutdownResultSchema,
  "workspace/open": workspaceResultSchema,
  "workspace/list": workspacePageResultSchema,
  "workspace/open-general": workspaceResultSchema,
  "workspace/set-trust": acceptedResultSchema,
  "workspace/unregister": acceptedResultSchema,
  "thread/create": threadResultSchema,
  "thread/list": threadPageResultSchema,
  "thread/search": threadPageResultSchema,
  "thread/read": ThreadReadResultSchema,
  "thread/rename": threadResultSchema,
  "thread/preferences/update": threadResultSchema,
  "thread/archive": acceptedResultSchema,
  "thread/delete": acceptedResultSchema,
  "thread/compact": threadCompactResultSchema,
  "attachment/import": AttachmentResultSchema,
  "attachment/discard": AttachmentResultSchema,
  "turn/start": turnAcceptedResultSchema,
  "turn/cancel": turnCancelResultSchema,
  "turn/steer": turnQueuedInputResultSchema,
  "turn/follow-up": turnQueuedInputResultSchema,
  "turn/change-set/commit": turnChangeSetCommitResultSchema,
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
    strategyVersion: z.literal("ja-context-v3"),
  })
  .strict();
const legalTransitions: Readonly<
  Record<z.infer<typeof TurnStateSchema>, readonly z.infer<typeof TurnStateSchema>[]>
> = {
  queued: ["running", "completed", "failed", "cancelled"],
  running: ["waiting_approval", "completed", "failed", "cancelled"],
  waiting_approval: ["running", "completed", "failed", "cancelled"],
  completed: [],
  failed: [],
  cancelled: [],
};

/** 强制公开六状态生命周期，内部 Agent phase 不得穿过 RPC 边界。 */
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
const modelUsageSchema = z
  .object({
    inputTokens: z.number().int().min(0).max(MAX_SAFE_INTEGER),
    outputTokens: z.number().int().min(0).max(MAX_SAFE_INTEGER),
    totalTokens: z.number().int().min(0).max(MAX_SAFE_INTEGER),
  })
  .strict()
  .refine(
    (usage) => usage.totalTokens >= usage.inputTokens + usage.outputTokens,
    "usage total must include input and output tokens",
  );
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
      "TOKEN_COUNT_UNAVAILABLE",
      "SUMMARY_FAILURE",
      "CONTEXT_LIMIT",
      "CANCELLED",
      "INVALID_STATE",
    ]),
  })
  .strict();
const terminalUsageSchema = modelUsageSchema
  .extend({
    modelRound: z.number().int().min(1).max(128),
  })
  .strict();
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
  })
  .strict()
  .superRefine((value, context) => {
    const hasErrorCode = value.errorCode !== undefined;
    const hasErrorMessage = value.errorMessage !== undefined;
    if (value.state === "completed" && value.finalMessage === undefined) {
      context.addIssue({
        code: "custom",
        path: ["finalMessage"],
        message: "completed terminal requires finalMessage",
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

const notification = <M extends string, S extends z.ZodType<Record<string, unknown>>>(
  method: M,
  params: S,
) => z.object({ jsonrpc: z.literal("2.0"), method: z.literal(method), params }).strict();

/** @public Windows 真窗 smoke 在浏览器上下文中按 Vite 绝对路径动态导入此闭集。 */
export const JaEventSchema = z.discriminatedUnion("method", [
  notification("runtime/status-changed", runtimeStatusParamsSchema),
  notification("configuration/changed", configChangedParamsSchema),
  notification("turn/state-changed", stateChangedParamsSchema),
  notification("assistant/model-step-committed", modelStepCommittedParamsSchema),
  notification("assistant/text-delta", deltaParamsSchema),
  notification("assistant/reasoning-summary-delta", deltaParamsSchema),
  notification("tool/batch-committed", toolBatchCommittedParamsSchema),
  notification("approval/requested", approvalRequestedParamsSchema),
  notification("approval/resolved", approvalResolvedParamsSchema),
  notification("context/compaction-started", contextCompactionStartedParamsSchema),
  notification("context/compacted", contextCompactedParamsSchema),
  notification("context/compaction-failed", contextCompactionFailedParamsSchema),
  notification("turn/terminal", terminalParamsSchema),
  notification("thread/metadata-changed", threadMetadataChangedParamsSchema),
]);
export type Thread = z.infer<typeof ThreadSchema>;
export type ThreadReadResult = z.infer<typeof ThreadReadResultSchema>;
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
      const allowedSecret =
        (options.allowConfigSecrets && (normalized === "apikey" || normalized === "secretvalue")) ||
        (options.allowCredentialSecret && normalized === "secret");
      if (
        key === "__proto__" ||
        key === "constructor" ||
        key === "prototype" ||
        (SENSITIVE_KEYS.has(normalized) && !allowedSecret)
      ) {
        throw new Error("unsafe payload property");
      }
      visit(child, depth + 1, [...path, key]);
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
}

/** 扫描宽松 payload 的叶节点，ready challenge 只能存在于唯一握手字段。 */
export function assertNoReadyTokenLeak(value: unknown, options: ReadyTokenLeakOptions = {}): void {
  assertSafePayload(value, {
    allowConfigSecrets: options.allowConfigSecrets,
    allowCredentialSecret: options.allowCredentialSecret,
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
  const containsKnownToken = (text: string): boolean => forEachReadyTokenCandidate(text, isKnown);
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

/** 校验仅客户端可用的请求 envelope；方法参数由 method registry 单独校验。 */
export function parseRequest(value: unknown): RequestEnvelope {
  const method =
    value !== null &&
    typeof value === "object" &&
    typeof (value as Record<string, unknown>)["method"] === "string"
      ? (value as Record<string, unknown>)["method"]
      : undefined;
  const allowCredentialSecret = method === "credential/set";
  assertNoReadyTokenLeak(value, { allowCredentialSecret });
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
