// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { z } from "zod";
import {
  defaultNativeBridge,
  normalizeRuntimeError,
  RuntimeHostError,
  type RuntimeNativeBridge,
} from "./runtime";

const WorkspaceIdSchema = z
  .string()
  .regex(/^ws_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(99);
const ThreadIdSchema = z
  .string()
  .regex(/^thr_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(100);
const TurnIdSchema = z
  .string()
  .regex(/^turn_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(101);
const CallIdSchema = z
  .string()
  .regex(/^call_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(101);
const ArtifactIdSchema = z
  .string()
  .regex(/^artifact_[A-Za-z0-9][A-Za-z0-9._-]{0,118}$/)
  .max(128);
/** 与 JA-RPC relativePath 约束对齐，防止 renderer 借冻结读取探测 artifact 外路径。 */
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
  .regex(/^(?!\/)(?![A-Za-z]:)(?!.*\\)(?!.*(?:^|\/)\.\.(?:\/|$)).+$/u)
  .refine(excludesIsoControls);
const PageOffsetSchema = z.number().int().min(0).max(Number.MAX_SAFE_INTEGER);
const ToolArtifactReadInputSchema = z
  .object({
    workspaceId: WorkspaceIdSchema,
    threadId: ThreadIdSchema,
    turnId: TurnIdSchema,
    callId: CallIdSchema,
    artifactId: ArtifactIdSchema,
    offsetCharacters: PageOffsetSchema,
    limitCharacters: z.number().int().min(1).max(65_536),
  })
  .strict();
const TurnDiffReadInputSchema = z
  .object({
    workspaceId: WorkspaceIdSchema,
    threadId: ThreadIdSchema,
    turnId: TurnIdSchema,
    artifactId: ArtifactIdSchema,
    filePath: RelativePathSchema,
  })
  .strict();
const ToolArtifactPageSchema = z
  .object({
    artifactId: ArtifactIdSchema,
    offsetCharacters: PageOffsetSchema,
    nextOffsetCharacters: PageOffsetSchema.nullable(),
    totalCharacters: PageOffsetSchema,
    truncated: z.boolean(),
    content: z
      .string()
      .max(65_536)
      .refine((value) => !value.includes("\0")),
  })
  .strict();
const TurnDiffSchema = z
  .object({
    artifactId: ArtifactIdSchema,
    filePath: RelativePathSchema,
    byteLength: z.number().int().min(0).max(2_097_152),
    sha256: z.string().regex(/^[a-f0-9]{64}$/),
    content: z
      .string()
      .max(2_097_152)
      .refine((value) => !value.includes("\0")),
  })
  .strict();

export type ToolArtifactReadInput = z.infer<typeof ToolArtifactReadInputSchema>;
export type TurnDiffReadInput = z.infer<typeof TurnDiffReadInputSchema>;
export type ToolArtifactPage = z.infer<typeof ToolArtifactPageSchema>;
export type TurnDiff = z.infer<typeof TurnDiffSchema>;

export interface TurnArtifactAdapter {
  readToolPage(input: ToolArtifactReadInput): Promise<ToolArtifactPage>;
  readTurnDiff(input: TurnDiffReadInput): Promise<TurnDiff>;
}

const COMMANDS = {
  tool: "ja_tool_artifact_read",
  turnDiff: "ja_turn_change_set_read",
} as const;

/** 固定 command 调用在 WebView 边界完成双向 Schema 校验，不开放通用 RPC tunnel。 */
async function readPage<I, O>(
  bridge: Pick<RuntimeNativeBridge, "invoke">,
  command: string,
  input: I,
  inputSchema: z.ZodType<I>,
  outputSchema: z.ZodType<O>,
): Promise<O> {
  let parsed: I;
  try {
    parsed = inputSchema.parse(input);
  } catch {
    throw new RuntimeHostError("INVALID_INPUT", "读取的内容标识无效。", false);
  }
  try {
    const value = await bridge.invoke<unknown>(command, { input: parsed });
    return outputSchema.parse(value);
  } catch (error) {
    if (error instanceof z.ZodError) {
      throw new RuntimeHostError("RUNTIME_UNAVAILABLE", "运行时返回的内容页无效。", true);
    }
    throw normalizeRuntimeError(error);
  }
}

/** 冻结 Diff 必须精确回显身份和 UTF-8 总长度，防止错误响应进入历史审阅。 */
function validateTurnDiff(input: TurnDiffReadInput, result: TurnDiff): TurnDiff {
  const contentBytes = new TextEncoder().encode(result.content).byteLength;
  if (
    result.artifactId !== input.artifactId ||
    result.filePath !== input.filePath ||
    contentBytes !== result.byteLength
  )
    throw new RuntimeHostError("RUNTIME_UNAVAILABLE", "运行时返回的修改内容无效。", true);
  return result;
}

/** Tauri adapter 只读取 Java 已脱敏并持久化的冻结 artifact。 */
export class TauriTurnArtifactAdapter implements TurnArtifactAdapter {
  constructor(private readonly bridge: Pick<RuntimeNativeBridge, "invoke"> = defaultNativeBridge) {}

  /** Tool 输出按 Unicode character offset 分页，避免切断代理对用户可见的字符。 */
  readToolPage(input: ToolArtifactReadInput): Promise<ToolArtifactPage> {
    return readPage(
      this.bridge,
      COMMANDS.tool,
      input,
      ToolArtifactReadInputSchema,
      ToolArtifactPageSchema,
    );
  }

  /** Turn diff 一次读取一个有界文件，读取的是终态冻结 artifact 而非当前工作树。 */
  async readTurnDiff(input: TurnDiffReadInput): Promise<TurnDiff> {
    const result = await readPage(
      this.bridge,
      COMMANDS.turnDiff,
      input,
      TurnDiffReadInputSchema,
      TurnDiffSchema,
    );
    return validateTurnDiff(input, result);
  }
}

/** 默认生产实例只封装固定 Tauri commands，便于组合层注入测试替身。 */
export function createTurnArtifactAdapter(): TurnArtifactAdapter {
  return new TauriTurnArtifactAdapter();
}
