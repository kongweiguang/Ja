// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { z } from "zod";

const MAX_SAFE_INTEGER = Number.MAX_SAFE_INTEGER;
const MAX_PROCESS_OUTPUT_BYTES = 65_536;
const MAX_PROCESS_INPUT_BYTES = 1_048_576;
const MAX_PROCESS_PAGE_SIZE = 200;
const WorkspaceIdSchema = z
  .string()
  .regex(/^ws_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(99);
const ThreadIdSchema = z
  .string()
  .regex(/^thr_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(100);
const OperationKeySchema = z
  .string()
  .regex(/^op_[0-9a-f]{32}$/)
  .max(35);
const ProcessGenerationSchema = z.number().int().min(1).max(MAX_SAFE_INTEGER);
const ProcessSequenceSchema = z.number().int().min(0).max(MAX_SAFE_INTEGER);
const ProcessSessionIdSchema = z
  .string()
  .regex(/^proc_[0-9a-f]{32}$/)
  .max(37);
const ProcessSessionCursorSchema = z
  .string()
  .regex(/^[A-Za-z0-9._~-]{1,256}$/)
  .max(256);
const ProcessStateSchema = z.enum(["running", "stopping", "exited", "interrupted"]);

/** Match Java's UTF-8 byte budgets; JavaScript string length counts UTF-16 units instead. */
function isWithinUtf8Budget(value: string, maxBytes: number): boolean {
  return new TextEncoder().encode(value).byteLength <= maxBytes;
}

const ProcessListIdentityFields = {
  workspaceId: WorkspaceIdSchema,
  threadId: ThreadIdSchema,
};
const ProcessIdentityFields = {
  ...ProcessListIdentityFields,
  generation: ProcessGenerationSchema,
};

/** 每个进程条目携带完整拥有者身份，避免列表项被其他会话复用。 */
export const ProcessSessionSchema = z
  .object({
    ...ProcessIdentityFields,
    sessionId: ProcessSessionIdSchema,
    startedTurnId: z
      .string()
      .regex(/^turn_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
      .max(101),
    startedCallId: z
      .string()
      .regex(/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/)
      .max(128),
    createdAt: z.string().datetime({ offset: true }).max(64),
    state: ProcessStateSchema,
    outputSequence: ProcessSequenceSchema,
    outputTruncated: z.boolean(),
    exitCode: z.number().int().min(-2_147_483_648).max(2_147_483_647).nullable(),
  })
  .strict();

const ProcessSessionSnapshotSchema = ProcessSessionSchema.extend({
  output: z
    .string()
    .max(MAX_PROCESS_OUTPUT_BYTES)
    .refine((value) => isWithinUtf8Budget(value, MAX_PROCESS_OUTPUT_BYTES)),
  nextSequence: ProcessSequenceSchema,
});

/** 进程查询绑定当前 Workspace、Thread 与 Java runtime generation。 */
export const ProcessSessionListParamsSchema = z
  .object({
    ...ProcessListIdentityFields,
    limit: z.number().int().min(1).max(MAX_PROCESS_PAGE_SIZE),
    cursor: ProcessSessionCursorSchema.nullable(),
    startedCallId: z
      .string()
      .regex(/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/)
      .max(128)
      .optional(),
  })
  .strict();
export const ProcessSessionListResultSchema = z
  .object({
    ...ProcessIdentityFields,
    sessions: z.array(ProcessSessionSchema).max(MAX_PROCESS_PAGE_SIZE),
    nextCursor: ProcessSessionCursorSchema.nullable(),
  })
  .strict();

/** 分页游标按 UTF-16 code unit 计数，与 Java 维护的文本缓冲索引一致。 */
export const ProcessSessionReadParamsSchema = z
  .object({
    ...ProcessIdentityFields,
    sessionId: ProcessSessionIdSchema,
    afterSequence: ProcessSequenceSchema,
    maxBytes: z.number().int().min(1).max(MAX_PROCESS_OUTPUT_BYTES),
  })
  .strict();
export const ProcessSessionReadResultSchema = ProcessSessionSnapshotSchema;

/** 写入幂等键属于进程会话，不复用 Turn 或跨会话的执行身份。 */
export const ProcessSessionWriteParamsSchema = z
  .object({
    ...ProcessIdentityFields,
    sessionId: ProcessSessionIdSchema,
    afterSequence: ProcessSequenceSchema,
    input: z
      .string()
      .max(MAX_PROCESS_INPUT_BYTES)
      .refine((value) => isWithinUtf8Budget(value, MAX_PROCESS_INPUT_BYTES)),
    closeInput: z.boolean(),
    operationKey: OperationKeySchema,
  })
  .strict();
export const ProcessSessionWriteResultSchema = ProcessSessionSnapshotSchema;

/** 停止请求只引用会话身份；结果允许 stopping，随后由 read 确认真实退出码。 */
export const ProcessSessionStopParamsSchema = z
  .object({
    ...ProcessIdentityFields,
    sessionId: ProcessSessionIdSchema,
  })
  .strict();
export const ProcessSessionStopResultSchema = ProcessSessionSnapshotSchema.extend({
  state: z.enum(["stopping", "exited"]),
  output: z.literal(""),
  outputTruncated: z.literal(false),
});
