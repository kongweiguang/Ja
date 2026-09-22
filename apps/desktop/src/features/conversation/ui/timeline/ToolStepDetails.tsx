// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  Check,
  ChevronDown,
  ChevronUp,
  CircleAlert,
  CircleCheck,
  CircleX,
  File,
  Folder,
  ListTree,
  LoaderCircle,
  Minus,
  RotateCcw,
  Search,
  SkipForward,
} from "lucide-react";
import { useMemo, useState, type ReactElement } from "react";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/shared/ui/primitives/Collapsible";
import type {
  ToolInteractionAnswer,
  ToolPresentation,
  WorkStepAdapter,
} from "../../domain/timelineTypes";
import {
  toolResultContent,
  toolResultView,
  type ToolResultRow,
  type ToolResultView,
} from "./toolResultView";
import type { TimelineDisclosureCache } from "./timelineDisclosure";

export interface ToolStepDetailsProps {
  step: WorkStepAdapter;
  disclosureCache?: TimelineDisclosureCache;
  disclosureKey?: string;
  disclosureThreadId?: string;
  onReadArtifact?: (input: {
    threadId: string;
    turnId: string;
    callId: string;
    artifactId: string;
  }) => Promise<string>;
  /** 当前 Turn revision 来自权威 Turn 投影；缺失时宁可不显示操作，也不能猜测 CAS。 */
  recoveryThreadRevision?: number;
  onResolveRecovery?: (input: {
    threadId: string;
    turnId: string;
    callId: string;
    expectedThreadRevision: number;
    expectedRecoveryRevision: number;
    decision: "retry" | "skip";
    idempotencyKey: string;
  }) => Promise<unknown>;
}

const PREVIEW_LINES = 10;

/**
 * 同一 call/revision/选择必须在重绘和虚拟卸载后保持同一个幂等身份；散列只缩短 UI 传输键，
 * 真正的 Tool 归属仍由 Java 以 callId 和双 revision 校验，不能作为授权或事实来源。
 */
function recoveryIdempotencyKey(
  turnId: string,
  callId: string,
  recoveryRevision: number,
  decision: "retry" | "skip",
): string {
  let hash = 0x811c9dc5;
  for (const character of `${turnId}:${callId}:${recoveryRevision}:${decision}`) {
    hash ^= character.codePointAt(0) ?? 0;
    hash = Math.imul(hash, 0x01000193);
  }
  return `recovery-${(hash >>> 0).toString(16).padStart(8, "0")}`;
}

/** Tool 状态只用于可访问名称和异常详情，不为正常步骤增加一列视觉噪声。 */
function presentationStatusLabel(status: ToolPresentation["status"]): string {
  switch (status) {
    case "pending":
      return "等待执行";
    case "running":
      return "进行中";
    case "waiting_approval":
      return "等待确认";
    case "success":
      return "完成";
    case "error":
      return "失败";
    case "cancelled":
      return "已取消";
  }
}

/** 按真实 Tool 名称优先选择可辨识动作，未知 MCP 才回退到通用标签。 */
function presentationActionLabel(
  presentation: ToolPresentation,
  toolName: string | undefined,
): string {
  switch (toolName) {
    case "request_user_input":
      return "询问用户";
    case "grep":
      return "搜索内容";
    case "find":
      return "查找文件";
    case "ls":
      return "列出目录";
    case "read_attachment":
      return "读取附件";
    default:
      break;
  }
  switch (presentation.kind) {
    case "shell":
      return "执行命令";
    case "read":
      return "读取";
    case "edit":
      return "编辑";
    case "write":
      return "写入";
    case "mcp":
      return "调用工具";
    case "context":
      return "上下文自动压缩";
  }
}

/** 只接受 Reducer 从协议外层保留的真实 Tool 名称，缺失时不拿展示标题冒充身份。 */
function presentationToolName(step: WorkStepAdapter): string | undefined {
  const toolName = step.metadata?.toolName?.trim();
  return toolName === undefined || toolName === "" ? undefined : toolName;
}

/** 摘要只取首个权威目标；grep/find 优先显示 query/pattern，完整输入和多路径信息留在展开内容中。 */
function presentationTarget(
  presentation: ToolPresentation,
  toolName: string | undefined,
): string | undefined {
  const inputTarget = presentation.inputPreview?.trim().split(/\r?\n/u)[0];
  if (toolName === "grep" || toolName === "find") {
    return (
      inputTarget ||
      presentation.relativePaths.find((path) => path.trim() !== "")?.trim() ||
      presentation.command?.trim()
    );
  }
  return (
    presentation.command?.trim() ||
    presentation.relativePaths.find((path) => path.trim() !== "")?.trim() ||
    inputTarget
  );
}

/** 只接受 JA-RPC 明确投影的结构化问答，避免从历史摘要猜测题目、答案或跳过状态。 */
function interactionAnswers(
  presentation: ToolPresentation,
  toolName: string | undefined,
): ToolInteractionAnswer[] {
  if (toolName !== "request_user_input") return [];
  return presentation.interactionAnswers ?? [];
}

/** 问答 Tool 折叠态只表达规模，避免再次把完整问题与答案挤成不可读的一行。 */
function interactionSummary(
  presentation: ToolPresentation,
  toolName: string | undefined,
  answers: readonly ToolInteractionAnswer[],
): string | undefined {
  if (toolName !== "request_user_input") return undefined;
  return answers.length > 0
    ? `已回答 ${answers.length} 个问题`
    : presentation.summary?.trim() || undefined;
}

/** Shell 将 stdout/stderr 保持为不同事实，普通 Tool 只展示 Java 提供的安全 outputPreview。 */
function presentationOutput(presentation: ToolPresentation): string | undefined {
  if (presentation.kind !== "shell") return presentation.outputPreview;
  const sections = [
    presentation.stdout?.trim() ? `stdout\n${presentation.stdout}` : undefined,
    presentation.stderr?.trim() ? `stderr\n${presentation.stderr}` : undefined,
  ].filter((value): value is string => value !== undefined);
  return sections.length === 0 ? presentation.outputPreview : sections.join("\n\n");
}

/**
 * 结果图标只表达 Java 已确认的条目类型，不把静态装饰或伪交互加到高密度的工具结果列表中。
 */
function ToolResultIcon({ kind }: { kind: ToolResultRow["kind"] }): ReactElement {
  switch (kind) {
    case "directory":
      return <Folder aria-hidden="true" />;
    case "file":
      return <File aria-hidden="true" />;
    case "match":
      return <Search aria-hidden="true" />;
    case "context":
      return <ListTree aria-hidden="true" />;
  }
}

/**
 * 摘要图标必须反映 Tool 的当前可恢复状态：仅真实执行中的步骤显示动态提示，终态不使用会造成误导的成功图标。
 */
function ToolOverviewIcon({ status }: { status: ToolPresentation["status"] }): ReactElement {
  switch (status) {
    case "success":
      return <CircleCheck />;
    case "error":
    case "waiting_approval":
      return <CircleAlert />;
    case "cancelled":
      return <CircleX />;
    case "pending":
    case "running":
      return <LoaderCircle className="ja-tool-details__overview-spinner" />;
  }
}

/**
 * 结构化行仅改善扫描效率，行本身没有接通文件预览能力，因此保持为不可点击的真实结果文本。
 */
function ToolResultOutput({
  view,
  status,
}: {
  view: ToolResultView;
  status: ToolPresentation["status"];
}): ReactElement {
  if (view.kind === "raw" || view.kind === "read") {
    return (
      <pre className="ja-tool-details__output" data-status={status}>
        {view.content}
      </pre>
    );
  }
  return (
    <ul
      className="ja-tool-result-list"
      aria-label={view.kind === "matches" ? "搜索结果" : "文件结果"}
    >
      {view.rows.map((row) => (
        <li key={`${row.kind}:${row.path}:${row.line ?? ""}`} data-kind={row.kind}>
          <ToolResultIcon kind={row.kind} />
          <code title={row.path}>{row.path}</code>
          {row.line === undefined ? null : <span>{row.line}</span>}
          {row.detail === undefined || row.detail === "" ? null : (
            <code className="ja-tool-result-list__detail" title={row.detail}>
              {row.detail}
            </code>
          )}
        </li>
      ))}
    </ul>
  );
}

/** 问答结果按题目分组，多选答案保留独立行，跳过与未回答不会伪装成已选择项。 */
function InteractionAnswerList({
  answers,
}: {
  answers: readonly ToolInteractionAnswer[];
}): ReactElement {
  return (
    <ol className="ja-tool-interaction" aria-label="问答记录">
      {answers.map((answer, questionIndex) => {
        const values = answer.skipped
          ? ["已跳过"]
          : answer.answers.length > 0
            ? answer.answers
            : ["未回答"];
        return (
          <li key={`${questionIndex}:${answer.question}`}>
            <div className="ja-tool-interaction__question">
              <span>问题 {questionIndex + 1}</span>
              <p>{answer.question}</p>
            </div>
            <div className="ja-tool-interaction__answer">
              <span>你的回答</span>
              <ul>
                {values.map((value, answerIndex) => (
                  <li key={`${answerIndex}:${value}`} data-skipped={answer.skipped || undefined}>
                    {answer.skipped ? <Minus aria-hidden="true" /> : <Check aria-hidden="true" />}
                    <span>{value}</span>
                  </li>
                ))}
              </ul>
            </div>
          </li>
        );
      })}
    </ol>
  );
}

/**
 * Tool 行自身就是唯一 Disclosure：常规步骤保持紧凑，失败步骤自动展开，
 * 且用户的手动选择按 Thread/Turn/Item identity 跨状态和虚拟卸载保留；动作、真实 Tool 名称与首个目标留在同一行，
 * 已脱敏结果留在展开区，避免用展示标题或原始参数猜测身份。
 */
export function ToolStepDetails({
  step,
  disclosureCache,
  disclosureKey,
  disclosureThreadId,
  onReadArtifact,
  recoveryThreadRevision,
  onResolveRecovery,
}: ToolStepDetailsProps): ReactElement | null {
  const presentation = step.metadata?.presentation;
  const callId = step.metadata?.callId;
  const [manualOpen, setManualOpen] = useState<boolean>();
  const [outputExpanded, setOutputExpanded] = useState(false);
  const [loadedOutput, setLoadedOutput] = useState<string>();
  const [loading, setLoading] = useState(false);
  const [resolvingRecovery, setResolvingRecovery] = useState(false);
  const [error, setError] = useState<string>();
  const toolName = presentationToolName(step);
  const isInteractionTool = toolName === "request_user_input";
  const isContextCompaction = presentation?.kind === "context";
  const answeredInteractions =
    presentation === undefined ? [] : interactionAnswers(presentation, toolName);
  const rawOutput =
    presentation === undefined ? undefined : (loadedOutput ?? presentationOutput(presentation));
  const output =
    presentation === undefined ? undefined : toolResultContent(toolName, presentation, rawOutput);
  const lines = useMemo(() => output?.split(/\r?\n/u) ?? [], [output]);

  if (presentation === undefined) return null;
  const canLoad =
    presentation.artifactId !== undefined && callId !== undefined && onReadArtifact !== undefined;
  const canResolveRecovery =
    presentation.recovery !== undefined &&
    callId !== undefined &&
    recoveryThreadRevision !== undefined &&
    onResolveRecovery !== undefined;
  const locallyExpandable = lines.length > PREVIEW_LINES;
  const shownOutput = outputExpanded ? output : lines.slice(0, PREVIEW_LINES).join("\n");
  const hidesSuccessOutput =
    isInteractionTool ||
    (presentation.status === "success" &&
      presentation.summary !== undefined &&
      (toolName === "edit" || toolName === "write"));
  const outputView = hidesSuccessOutput
    ? undefined
    : toolResultView(toolName, presentation, shownOutput);
  const showsInput =
    !isInteractionTool && presentation.kind === "mcp" && presentation.inputPreview?.trim() !== "";
  const cachedOpen =
    disclosureCache !== undefined && disclosureThreadId !== undefined && disclosureKey !== undefined
      ? disclosureCache.get(disclosureThreadId, "tool", disclosureKey)
      : undefined;
  const detailsOpen =
    cachedOpen ??
    manualOpen ??
    (presentation.status === "error" ||
      presentation.recovery !== undefined ||
      (isInteractionTool && presentation.status === "success" && answeredInteractions.length > 0));
  const actionLabel = presentationActionLabel(presentation, toolName);
  const target = presentationTarget(presentation, toolName);
  const interactionResult = interactionSummary(presentation, toolName, answeredInteractions);
  const accessibleSummary = [
    actionLabel,
    toolName,
    target,
    interactionResult,
    presentationStatusLabel(presentation.status),
  ]
    .filter((value): value is string => value !== undefined)
    .join("，");

  /** Tool identity 稳定时保存用户选择；状态变化只影响无人工选择时的失败自动展开策略。 */
  const updateDetailsOpen = (open: boolean): void => {
    if (
      disclosureCache !== undefined &&
      disclosureThreadId !== undefined &&
      disclosureKey !== undefined
    ) {
      disclosureCache.set(disclosureThreadId, "tool", disclosureKey, open);
    }
    setManualOpen(open);
  };

  /** 完整输出必须通过 thread/turn/call/artifact 四重身份读取，失败不回显原生诊断。 */
  const loadFullOutput = async (): Promise<void> => {
    if (
      !canLoad ||
      presentation.artifactId === undefined ||
      callId === undefined ||
      onReadArtifact === undefined
    )
      return;
    setLoading(true);
    setError(undefined);
    try {
      setLoadedOutput(
        await onReadArtifact({
          threadId: step.threadId,
          turnId: step.turnId,
          callId,
          artifactId: presentation.artifactId,
        }),
      );
      setOutputExpanded(true);
    } catch {
      setError("无法读取完整输出，请稍后重试。");
    } finally {
      setLoading(false);
    }
  };

  /**
   * 点击动作冻结当前详情的全部 CAS 身份；切换 Thread、收到新快照或重复点击后，Runtime 和 Java
   * 分别以 generation、串行 key、revision 与幂等键失败关闭，UI 绝不乐观伪造处理完成。
   */
  const resolveRecovery = async (decision: "retry" | "skip"): Promise<void> => {
    if (
      !canResolveRecovery ||
      presentation.recovery === undefined ||
      callId === undefined ||
      recoveryThreadRevision === undefined ||
      onResolveRecovery === undefined ||
      resolvingRecovery
    )
      return;
    setResolvingRecovery(true);
    setError(undefined);
    try {
      await onResolveRecovery({
        threadId: step.threadId,
        turnId: step.turnId,
        callId,
        expectedThreadRevision: recoveryThreadRevision,
        expectedRecoveryRevision: presentation.recovery.revision,
        decision,
        idempotencyKey: recoveryIdempotencyKey(
          step.turnId,
          callId,
          presentation.recovery.revision,
          decision,
        ),
      });
    } catch {
      setError("无法提交这一步的处理，请刷新会话状态后重试。");
    } finally {
      setResolvingRecovery(false);
    }
  };

  return (
    <div
      className="ja-tool-details"
      data-status={presentation.status}
      data-tool-kind={presentation.kind}
      data-tool-name={toolName}
    >
      <Collapsible open={detailsOpen} onOpenChange={updateDetailsOpen}>
        <CollapsibleTrigger className="ja-tool-details__trigger" aria-label={accessibleSummary}>
          <span className="ja-tool-details__identity">
            <strong className="ja-tool-details__label">{actionLabel}</strong>
          </span>
          {target ? (
            <code className="ja-tool-details__target" title={target}>
              {target}
            </code>
          ) : null}
          {interactionResult ? (
            <span className="ja-tool-details__interaction-result" title={interactionResult}>
              {interactionResult}
            </span>
          ) : null}
          <span className="ja-tool-details__summary">
            <ChevronDown aria-hidden="true" />
          </span>
        </CollapsibleTrigger>
        <CollapsibleContent className="ja-tool-details__content">
          {presentation.summary === undefined || isInteractionTool ? null : (
            <div className="ja-tool-details__overview" data-status={presentation.status}>
              <span aria-hidden="true" className="ja-tool-details__overview-icon">
                <ToolOverviewIcon status={presentation.status} />
              </span>
              <span>{presentation.summary}</span>
            </div>
          )}
          {answeredInteractions.length === 0 ? null : (
            <InteractionAnswerList answers={answeredInteractions} />
          )}
          {showsInput ? (
            <pre className="ja-tool-details__input">{presentation.inputPreview}</pre>
          ) : null}
          {presentation.relativeCwd ? (
            <span className="ja-tool-details__cwd">工作目录：{presentation.relativeCwd}</span>
          ) : null}
          {outputView === undefined ? null : (
            <ToolResultOutput view={outputView} status={presentation.status} />
          )}
          {canResolveRecovery ? (
            <div className="ja-tool-details__recovery" role="group" aria-label="恢复此工具步骤">
              <p>这条命令已启动，但未保存结果。再次执行可能重复操作。</p>
              <div className="ja-tool-details__recovery-actions">
                <button
                  type="button"
                  className="ja-tool-details__recovery-action"
                  disabled={resolvingRecovery}
                  onClick={() => void resolveRecovery("retry")}
                >
                  {resolvingRecovery ? (
                    <LoaderCircle aria-hidden="true" />
                  ) : (
                    <RotateCcw aria-hidden="true" />
                  )}
                  重新执行
                </button>
                <button
                  type="button"
                  className="ja-tool-details__recovery-action"
                  disabled={resolvingRecovery}
                  onClick={() => void resolveRecovery("skip")}
                >
                  <SkipForward aria-hidden="true" />
                  跳过这一步
                </button>
              </div>
            </div>
          ) : null}
          {isInteractionTool || isContextCompaction ? null : (
            <div className="ja-tool-details__facts">
              <span>状态：{presentationStatusLabel(presentation.status)}</span>
              {presentation.exitCode === undefined ? null : (
                <span>退出码 {presentation.exitCode}</span>
              )}
              {presentation.durationMs === undefined ? null : (
                <span>
                  {presentation.durationMs < 1_000
                    ? `${presentation.durationMs} ms`
                    : `${(presentation.durationMs / 1_000).toFixed(1)} s`}
                </span>
              )}
              {presentation.truncated ? <span>预览已截断</span> : null}
            </div>
          )}
          {locallyExpandable ? (
            <button
              type="button"
              className="ja-tool-details__expand"
              onClick={() => setOutputExpanded((value) => !value)}
            >
              {outputExpanded ? (
                <ChevronUp aria-hidden="true" />
              ) : (
                <ChevronDown aria-hidden="true" />
              )}
              {outputExpanded ? "收起输出" : `展开全部 ${lines.length} 行`}
            </button>
          ) : null}
          {presentation.truncated && canLoad && loadedOutput === undefined ? (
            <button
              type="button"
              className="ja-tool-details__expand"
              disabled={loading}
              onClick={() => void loadFullOutput()}
            >
              {loading ? <LoaderCircle aria-hidden="true" /> : <ChevronDown aria-hidden="true" />}
              {loading ? "正在读取…" : "加载完整输出"}
            </button>
          ) : null}
          {error === undefined ? null : <p role="alert">{error}</p>}
        </CollapsibleContent>
      </Collapsible>
    </div>
  );
}
