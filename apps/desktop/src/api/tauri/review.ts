// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { z } from "zod";
import {
  defaultNativeBridge,
  normalizeRuntimeError,
  RuntimeHostError,
  type RuntimeNativeBridge,
} from "./runtime";
import {
  parseMethodParams,
  parseMethodResult,
  type MethodParams,
  type MethodResult,
} from "@/api/protocol/methods";
import { WorkspaceNonEmptyRelativePathSchema } from "./workspace";

const WorkspaceIdSchema = z
  .string()
  .regex(/^ws_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(99);
const StableTokenSchema = z
  .string()
  .min(1)
  .max(256)
  .refine(
    (value) =>
      !Array.from(value).some((character) => {
        const code = character.codePointAt(0) ?? 0;
        return code <= 0x1f || code === 0x7f;
      }),
    "token contains control characters",
  );
const RevisionSchema = StableTokenSchema;
const OperationIdSchema = StableTokenSchema.max(128);

const ReviewCaptureInputSchema = z
  .object({
    workspaceId: WorkspaceIdSchema,
    source: z.discriminatedUnion("kind", [
      z.object({ kind: z.literal("uncommitted") }).strict(),
      z.object({ kind: z.literal("unstaged") }).strict(),
      z.object({ kind: z.literal("staged") }).strict(),
      z.object({ kind: z.literal("branch"), refId: StableTokenSchema }).strict(),
      z.object({ kind: z.literal("commit"), commitId: StableTokenSchema }).strict(),
    ]),
    expectedSnapshotRevision: RevisionSchema,
    operationId: OperationIdSchema,
  })
  .strict();
export type ReviewCaptureInput = z.infer<typeof ReviewCaptureInputSchema>;
const FrozenReviewSourceSchema = z.discriminatedUnion("kind", [
  z.object({ kind: z.literal("uncommitted") }).strict(),
  z.object({ kind: z.literal("unstaged") }).strict(),
  z.object({ kind: z.literal("staged") }).strict(),
  z.object({ kind: z.literal("base"), commitId: z.string().regex(/^[0-9a-f]{40,64}$/) }).strict(),
  z.object({ kind: z.literal("commit"), commitId: z.string().regex(/^[0-9a-f]{40,64}$/) }).strict(),
]);
const ReviewCaptureCoverageSchema = z
  .object({
    complete: z.boolean(),
    files: z.number().int().min(0).max(2_000),
    bytes: z
      .number()
      .int()
      .min(0)
      .max(64 * 1024 * 1024),
    maxFiles: z.literal(2_000),
    maxBytes: z.literal(64 * 1024 * 1024),
    partialReasons: z
      .array(
        z.enum([
          "binary_file",
          "non_utf8",
          "file_limit",
          "byte_limit",
          "content_unavailable",
          "patch_unavailable",
        ]),
      )
      .max(6)
      .refine((values) => new Set(values).size === values.length),
  })
  .strict()
  .superRefine((coverage, context) => {
    if (coverage.complete !== (coverage.partialReasons.length === 0))
      context.addIssue({
        code: "custom",
        path: ["complete"],
        message: "inconsistent review coverage",
      });
  });
const ReviewCaptureResultSchema = z
  .object({
    workspaceId: WorkspaceIdSchema,
    source: FrozenReviewSourceSchema,
    snapshotId: z.string().regex(/^rev_[0-9a-f]{24}$/),
    snapshotHash: z.string().regex(/^[0-9a-f]{64}$/),
    revision: RevisionSchema,
    attachmentId: z.string().regex(/^att_[A-Za-z0-9_.:-]{1,124}$/),
    coverage: ReviewCaptureCoverageSchema,
  })
  .strict();
export type ReviewCaptureResult = z.infer<typeof ReviewCaptureResultSchema>;

const ReviewSourceSchema = z.discriminatedUnion("kind", [
  z.object({ kind: z.literal("uncommitted") }).strict(),
  z.object({ kind: z.literal("unstaged") }).strict(),
  z.object({ kind: z.literal("staged") }).strict(),
  z.object({ kind: z.literal("branch"), refId: StableTokenSchema }).strict(),
  z.object({ kind: z.literal("commit"), commitId: StableTokenSchema }).strict(),
]);

const ReviewCatalogInputSchema = z
  .object({
    workspaceId: WorkspaceIdSchema,
    maxCommits: z.number().int().min(1).max(100).optional(),
  })
  .strict();

const ReviewSnapshotInputSchema = z
  .object({
    workspaceId: WorkspaceIdSchema,
    source: ReviewSourceSchema,
  })
  .strict();

const ReviewFileDiffInputSchema = z
  .object({
    workspaceId: WorkspaceIdSchema,
    source: ReviewSourceSchema,
    revision: RevisionSchema,
    fileId: StableTokenSchema,
  })
  .strict();

const ReviewActionSchema = z.enum(["stage", "unstage", "revert"]);
const ReviewTargetSchema = z.discriminatedUnion("kind", [
  z.object({ kind: z.literal("all") }).strict(),
  z.object({ kind: z.literal("file"), fileId: StableTokenSchema }).strict(),
  z
    .object({ kind: z.literal("hunk"), fileId: StableTokenSchema, hunkId: StableTokenSchema })
    .strict(),
]);

const ReviewApplyInputSchema = z
  .object({
    workspaceId: WorkspaceIdSchema,
    source: ReviewSourceSchema,
    revision: RevisionSchema,
    action: ReviewActionSchema,
    target: ReviewTargetSchema,
    operationId: OperationIdSchema,
  })
  .strict();

const ReviewCancelInputSchema = z
  .object({
    workspaceId: WorkspaceIdSchema,
    operationId: OperationIdSchema,
  })
  .strict();

const ReviewRefSchema = z
  .object({
    refId: StableTokenSchema,
    label: z.string().min(1).max(256),
    kind: z.enum(["base", "local", "remote"]),
  })
  .strict();

const ReviewCommitSchema = z
  .object({
    commitId: StableTokenSchema,
    subject: z.string().max(1024),
    author: z.string().max(256),
    authoredAt: z.string().max(128),
  })
  .strict();

const ReviewCatalogSchema = z
  .object({
    workspaceId: WorkspaceIdSchema,
    repositoryName: z.string().min(1).max(256),
    currentBranch: z.string().max(256).nullable(),
    headCommitId: StableTokenSchema.nullable(),
    baseRefs: z.array(ReviewRefSchema).max(64),
    commits: z.array(ReviewCommitSchema).max(100),
  })
  .strict();

const ReviewFileStatusSchema = z.enum([
  "added",
  "modified",
  "deleted",
  "renamed",
  "copied",
  "conflicted",
  "untracked",
]);
const ReviewFileLayerSchema = z.enum(["staged", "unstaged", "untracked", "comparison"]);
const ReviewHunkSchema = z
  .object({
    hunkId: StableTokenSchema,
    header: z.string().max(1024),
    oldStart: z.number().int().nonnegative(),
    oldLines: z.number().int().nonnegative(),
    newStart: z.number().int().nonnegative(),
    newLines: z.number().int().nonnegative(),
  })
  .strict();

const ReviewFileSchema = z
  .object({
    fileId: StableTokenSchema,
    layer: ReviewFileLayerSchema,
    path: WorkspaceNonEmptyRelativePathSchema,
    oldPath: WorkspaceNonEmptyRelativePathSchema.nullable(),
    status: ReviewFileStatusSchema,
    additions: z.number().int().nonnegative().nullable(),
    deletions: z.number().int().nonnegative().nullable(),
    binary: z.boolean(),
    truncated: z.boolean(),
    hunks: z.array(ReviewHunkSchema).max(10_000),
  })
  .strict();

const ReviewStatsSchema = z
  .object({
    files: z.number().int().nonnegative(),
    additions: z.number().int().nonnegative(),
    deletions: z.number().int().nonnegative(),
    binaryFiles: z.number().int().nonnegative(),
    truncated: z.boolean(),
  })
  .strict();

const ReviewCapabilitiesSchema = z
  .object({
    stage: z.boolean(),
    unstage: z.boolean(),
    revert: z.boolean(),
  })
  .strict();

const ReviewSnapshotSchema = z
  .object({
    workspaceId: WorkspaceIdSchema,
    source: ReviewSourceSchema,
    revision: RevisionSchema,
    files: z.array(ReviewFileSchema).max(100_000),
    stats: ReviewStatsSchema,
    capabilities: ReviewCapabilitiesSchema,
  })
  .strict();

const ReviewDiffLineSchema = z
  .object({
    kind: z.enum(["context", "addition", "deletion"]),
    oldLine: z.number().int().nonnegative().nullable(),
    newLine: z.number().int().nonnegative().nullable(),
    text: z.string().max(64 * 1024),
  })
  .strict();

const ReviewFileDiffSchema = z
  .object({
    workspaceId: WorkspaceIdSchema,
    source: ReviewSourceSchema,
    revision: RevisionSchema,
    fileId: StableTokenSchema,
    layer: ReviewFileLayerSchema,
    path: WorkspaceNonEmptyRelativePathSchema,
    oldPath: WorkspaceNonEmptyRelativePathSchema.nullable(),
    status: ReviewFileStatusSchema,
    binary: z.boolean(),
    truncated: z.boolean(),
    original: z
      .string()
      .max(2 * 1024 * 1024)
      .nullable(),
    modified: z
      .string()
      .max(2 * 1024 * 1024)
      .nullable(),
    unified: z
      .string()
      .max(4 * 1024 * 1024)
      .nullable(),
    hunks: z.array(ReviewHunkSchema).max(10_000),
    lines: z.array(ReviewDiffLineSchema).max(100_000),
  })
  .strict();

const ReviewApplyResultSchema = z
  .object({
    workspaceId: WorkspaceIdSchema,
    operationId: OperationIdSchema,
    applied: z.literal(true),
    snapshot: ReviewSnapshotSchema,
  })
  .strict();

const ReviewCancelResultSchema = z
  .object({
    workspaceId: WorkspaceIdSchema,
    operationId: OperationIdSchema,
    cancelled: z.boolean(),
  })
  .strict();

const ReviewInvalidatedEventSchema = z
  .object({
    workspaceId: WorkspaceIdSchema,
    generation: z.number().int().positive(),
    reason: z.enum(["mutation", "external", "turn_completed", "repository_changed"]),
  })
  .strict();

export type ReviewCatalogInput = z.infer<typeof ReviewCatalogInputSchema>;
export type ReviewSnapshotInput = z.infer<typeof ReviewSnapshotInputSchema>;
export type ReviewFileDiffInput = z.infer<typeof ReviewFileDiffInputSchema>;
export type ReviewApplyInput = z.infer<typeof ReviewApplyInputSchema>;
export type ReviewCancelInput = z.infer<typeof ReviewCancelInputSchema>;
export type ReviewCatalog = z.infer<typeof ReviewCatalogSchema>;
export type ReviewSnapshot = z.infer<typeof ReviewSnapshotSchema>;
export type ReviewFileDiff = z.infer<typeof ReviewFileDiffSchema>;
export type ReviewApplyResult = z.infer<typeof ReviewApplyResultSchema>;
export type ReviewCancelResult = z.infer<typeof ReviewCancelResultSchema>;
export type ReviewInvalidatedEvent = z.infer<typeof ReviewInvalidatedEventSchema>;
export type ReviewUnsubscribe = () => void | Promise<void>;
export type ReviewNativeBridge = Pick<RuntimeNativeBridge, "invoke" | "listen">;

/** renderer 只能请求五项固定 Review 能力，不能选择任意 Git 操作。 */
export const JA_REVIEW_COMMANDS = {
  catalog: "ja_review_catalog",
  snapshot: "ja_review_snapshot",
  captureSnapshot: "ja_review_capture_snapshot",
  fileDiff: "ja_review_file_diff",
  apply: "ja_review_apply",
  cancel: "ja_review_cancel",
} as const;

/** invalidation 只携带元数据提示；每个消费者都必须重新读取权威 snapshot。 */
export const JA_REVIEW_EVENTS = {
  invalidated: "review/invalidated",
} as const;

/** 将 Tauri wire 错误转换为 Review feature 码和安全文案，不让原生诊断进入 UI。 */
export function normalizeReviewError(error: unknown): RuntimeHostError {
  if (error instanceof RuntimeHostError) {
    const safe = REVIEW_ERRORS[error.code];
    if (safe !== undefined) {
      return new RuntimeHostError(safe.featureCode ?? error.code, safe.message, safe.retryable);
    }
  }
  const candidate =
    error !== null && typeof error === "object" ? (error as Record<string, unknown>) : undefined;
  const code = typeof candidate?.["code"] === "string" ? candidate["code"] : undefined;
  const safe = code === undefined ? undefined : REVIEW_ERRORS[code];
  if (safe !== undefined) {
    return new RuntimeHostError(safe.featureCode ?? code!, safe.message, safe.retryable);
  }
  return normalizeRuntimeError(error);
}

const REVIEW_ERRORS: Readonly<
  Record<
    string,
    { readonly featureCode?: string; readonly message: string; readonly retryable: boolean }
  >
> = {
  REVIEW_STALE: { message: "工作区已经变化，请重新读取 Review。", retryable: true },
  REVIEW_CONFLICT: { message: "当前文件存在重叠修改，无法安全应用。", retryable: false },
  REVIEW_READ_ONLY: { message: "此 Review 来源为只读。", retryable: false },
  REVIEW_UNAVAILABLE: { message: "当前轮次没有可精确归因的变更。", retryable: false },
  REVIEW_LIMIT: { message: "Review 内容超过安全上限。", retryable: false },
  NOT_GIT_REPOSITORY: { message: "当前目录不是 Git 工作区，审查不可用。", retryable: false },
  INVALID_INPUT: { message: "Review 请求参数无效。", retryable: false },
  WORKSPACE_ESCAPE: { message: "请求路径不在工作区内。", retryable: false },
  HOST_BUSY: { message: "工作区正被其他操作占用，请稍后重试。", retryable: true },
  CANCELLED: { message: "Review 操作已取消。", retryable: true },
  PARSE_ERROR: {
    featureCode: "PARSE",
    message: "Git 输出解析失败",
    retryable: false,
  },
};

/** 将畸形 renderer 输入收敛为稳定边界错误，不回显输入细节。 */
function parseReviewInput<T>(schema: z.ZodType<T>, input: unknown): T {
  try {
    return schema.parse(input);
  } catch {
    throw new RuntimeHostError("INVALID_INPUT", "Review 请求参数无效。", false);
  }
}

/** 调用封闭原生 command，并在 React 看到结果前校验完整 DTO。 */
async function invokeReview<T>(
  bridge: ReviewNativeBridge,
  command: string,
  input: unknown,
  inputSchema: z.ZodType<unknown>,
  resultSchema: z.ZodType<T>,
): Promise<T> {
  const parsedInput = parseReviewInput(inputSchema, input);
  try {
    const result = await bridge.invoke<unknown>(command, { input: parsedInput });
    return resultSchema.parse(result);
  } catch (error) {
    if (error instanceof z.ZodError) {
      throw new RuntimeHostError("RUNTIME_UNAVAILABLE", "运行时返回的数据无效。", true);
    }
    throw normalizeReviewError(error);
  }
}

export interface ReviewAdapter {
  catalog(input: ReviewCatalogInput): Promise<ReviewCatalog>;
  snapshot(input: ReviewSnapshotInput): Promise<ReviewSnapshot>;
  captureSnapshot(input: ReviewCaptureInput): Promise<ReviewCaptureResult>;
  fileDiff(input: ReviewFileDiffInput): Promise<ReviewFileDiff>;
  apply(input: ReviewApplyInput): Promise<ReviewApplyResult>;
  cancel(input: ReviewCancelInput): Promise<ReviewCancelResult>;
  startAiReview(input: MethodParams<"review/start">): Promise<MethodResult<"review/start">>;
  readAiReview(input: MethodParams<"review/read">): Promise<MethodResult<"review/read">>;
  subscribeInvalidated(
    listener: (event: ReviewInvalidatedEvent) => void,
  ): Promise<ReviewUnsubscribe>;
}

/** typed Tauri adapter 只做映射；Git 路径、patch 文本和 revision 校验仍由原生层拥有。 */
export class TauriReviewAdapter implements ReviewAdapter {
  constructor(private readonly bridge: ReviewNativeBridge = defaultNativeBridge) {}

  /** 读取填充紧凑 source selector 所需的 repository 元数据。 */
  async catalog(input: ReviewCatalogInput): Promise<ReviewCatalog> {
    return invokeReview(
      this.bridge,
      JA_REVIEW_COMMANDS.catalog,
      input,
      ReviewCatalogInputSchema,
      ReviewCatalogSchema,
    );
  }

  /** 为选定 Review source 读取有界权威 snapshot。 */
  async snapshot(input: ReviewSnapshotInput): Promise<ReviewSnapshot> {
    return invokeReview(
      this.bridge,
      JA_REVIEW_COMMANDS.snapshot,
      input,
      ReviewSnapshotInputSchema,
      ReviewSnapshotSchema,
    );
  }

  /**
   * 输入 revision 是原生采集的 source CAS 围栏，返回 revision 标识冻结工件；二者语义不同，只校验工作区与来源。
   */
  async captureSnapshot(input: ReviewCaptureInput): Promise<ReviewCaptureResult> {
    const result = await invokeReview(
      this.bridge,
      JA_REVIEW_COMMANDS.captureSnapshot,
      input,
      ReviewCaptureInputSchema,
      ReviewCaptureResultSchema,
    );
    if (result.workspaceId !== input.workspaceId) {
      throw new RuntimeHostError("RUNTIME_UNAVAILABLE", "运行时返回的审查快照身份无效。", true);
    }
    const sourceMatches =
      input.source.kind === "branch"
        ? result.source.kind === "base"
        : input.source.kind === "commit"
          ? result.source.kind === "commit"
          : result.source.kind === input.source.kind;
    if (!sourceMatches) {
      throw new RuntimeHostError("RUNTIME_UNAVAILABLE", "运行时返回的审查快照来源无效。", true);
    }
    return result;
  }

  /** 只在已知 snapshot revision 后加载单个文件的有界 old/new 内容。 */
  async fileDiff(input: ReviewFileDiffInput): Promise<ReviewFileDiff> {
    return invokeReview(
      this.bridge,
      JA_REVIEW_COMMANDS.fileDiff,
      input,
      ReviewFileDiffInputSchema,
      ReviewFileDiffSchema,
    );
  }

  /** 应用原生层持有的 all/file/hunk 操作，并只接受其返回的新快照。 */
  async apply(input: ReviewApplyInput): Promise<ReviewApplyResult> {
    return invokeReview(
      this.bridge,
      JA_REVIEW_COMMANDS.apply,
      input,
      ReviewApplyInputSchema,
      ReviewApplyResultSchema,
    );
  }

  /** 按不透明 operation identity 取消 pre-commit 原生操作。 */
  async cancel(input: ReviewCancelInput): Promise<ReviewCancelResult> {
    return invokeReview(
      this.bridge,
      JA_REVIEW_COMMANDS.cancel,
      input,
      ReviewCancelInputSchema,
      ReviewCancelResultSchema,
    );
  }

  /** 只订阅固定元数据 invalidation 事件，不接收 diff 正文。 */
  async subscribeInvalidated(
    listener: (event: ReviewInvalidatedEvent) => void,
  ): Promise<ReviewUnsubscribe> {
    try {
      return await this.bridge.listen<unknown>(JA_REVIEW_EVENTS.invalidated, (payload) => {
        const parsed = ReviewInvalidatedEventSchema.safeParse(payload);
        if (parsed.success) listener(parsed.data);
      });
    } catch (error) {
      throw normalizeReviewError(error);
    }
  }

  /** 使用固定 Tauri command 与 JA-RPC validator，避免 AI 审查成为 renderer 可调用的通用 RPC 通道。 */
  async startAiReview(input: MethodParams<"review/start">): Promise<MethodResult<"review/start">> {
    return invokeAiReview(this.bridge, "review/start", "ja_ai_review_start", input);
  }

  /** 历史报告只按冻结的 Thread/Turn 身份读取，不能改绑到当前工作区快照。 */
  async readAiReview(input: MethodParams<"review/read">): Promise<MethodResult<"review/read">> {
    return invokeAiReview(this.bridge, "review/read", "ja_ai_review_read", input);
  }
}

/** AI Review 只经固定 command 和协议校验调用，renderer 不获得可任意选择方法的 RPC 通道。 */
async function invokeAiReview<M extends "review/start" | "review/read">(
  bridge: ReviewNativeBridge,
  method: M,
  command: string,
  input: MethodParams<M>,
): Promise<MethodResult<M>> {
  const parsedInput = parseMethodParams(method, input);
  try {
    const result = await bridge.invoke<unknown>(command, { input: parsedInput });
    return parseMethodResult(method, result);
  } catch (error) {
    if (error instanceof z.ZodError)
      throw new RuntimeHostError("RUNTIME_UNAVAILABLE", "运行时返回的审查结果无效。", true);
    throw normalizeReviewError(error);
  }
}

/** 创建生产 adapter，同时让测试停留在可注入窄 bridge。 */
export function createReviewAdapter(): ReviewAdapter {
  return new TauriReviewAdapter();
}
