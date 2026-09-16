// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  ChevronDown,
  ChevronUp,
  CircleAlert,
  CircleCheck,
  CircleX,
  File,
  Folder,
  ListTree,
  LoaderCircle,
  Search,
} from "lucide-react";
import { useMemo, useState, type ReactElement } from "react";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/shared/ui/primitives/Collapsible";
import type { ToolPresentation, WorkStepAdapter } from "../../domain/timelineTypes";
import {
  toolResultContent,
  toolResultView,
  type ToolResultRow,
  type ToolResultView,
} from "./toolResultView";

export interface ToolStepDetailsProps {
  step: WorkStepAdapter;
  onReadArtifact?: (input: {
    threadId: string;
    turnId: string;
    callId: string;
    artifactId: string;
  }) => Promise<string>;
}

const PREVIEW_LINES = 10;

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

/**
 * Tool 行自身就是唯一 Disclosure：常规步骤保持紧凑，失败步骤自动展开，
 * 且用户的手动选择只在同一状态内有效；动作、真实 Tool 名称与首个目标留在同一行，
 * 已脱敏结果留在展开区，避免用展示标题或原始参数猜测身份。
 */
export function ToolStepDetails({
  step,
  onReadArtifact,
}: ToolStepDetailsProps): ReactElement | null {
  const presentation = step.metadata?.presentation;
  const callId = step.metadata?.callId;
  const [manualDisclosure, setManualDisclosure] = useState<{
    status: ToolPresentation["status"];
    open: boolean;
  }>();
  const [outputExpanded, setOutputExpanded] = useState(false);
  const [loadedOutput, setLoadedOutput] = useState<string>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const toolName = presentationToolName(step);
  const rawOutput =
    presentation === undefined ? undefined : (loadedOutput ?? presentationOutput(presentation));
  const output =
    presentation === undefined ? undefined : toolResultContent(toolName, presentation, rawOutput);
  const lines = useMemo(() => output?.split(/\r?\n/u) ?? [], [output]);

  if (presentation === undefined) return null;
  const canLoad =
    presentation.artifactId !== undefined && callId !== undefined && onReadArtifact !== undefined;
  const locallyExpandable = lines.length > PREVIEW_LINES;
  const shownOutput = outputExpanded ? output : lines.slice(0, PREVIEW_LINES).join("\n");
  const hidesSuccessOutput =
    presentation.status === "success" &&
    presentation.summary !== undefined &&
    (toolName === "edit" || toolName === "write");
  const outputView = hidesSuccessOutput
    ? undefined
    : toolResultView(toolName, presentation, shownOutput);
  const showsInput = presentation.kind === "mcp" && presentation.inputPreview?.trim() !== "";
  const detailsOpen =
    manualDisclosure?.status === presentation.status
      ? manualDisclosure.open
      : presentation.status === "error";
  const actionLabel = presentationActionLabel(presentation, toolName);
  const target = presentationTarget(presentation, toolName);
  const accessibleSummary = [
    actionLabel,
    toolName,
    target,
    presentationStatusLabel(presentation.status),
  ]
    .filter((value): value is string => value !== undefined)
    .join("，");

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

  return (
    <div
      className="ja-tool-details"
      data-status={presentation.status}
      data-tool-kind={presentation.kind}
      data-tool-name={toolName}
    >
      <Collapsible
        open={detailsOpen}
        onOpenChange={(open) => setManualDisclosure({ status: presentation.status, open })}
      >
        <CollapsibleTrigger className="ja-tool-details__trigger" aria-label={accessibleSummary}>
          <span className="ja-tool-details__identity">
            <strong className="ja-tool-details__label">{actionLabel}</strong>
          </span>
          {target ? (
            <code className="ja-tool-details__target" title={target}>
              {target}
            </code>
          ) : null}
          <span className="ja-tool-details__summary">
            <ChevronDown aria-hidden="true" />
          </span>
        </CollapsibleTrigger>
        <CollapsibleContent className="ja-tool-details__content">
          {presentation.summary === undefined ? null : (
            <div className="ja-tool-details__overview" data-status={presentation.status}>
              <span aria-hidden="true" className="ja-tool-details__overview-icon">
                <ToolOverviewIcon status={presentation.status} />
              </span>
              <span>{presentation.summary}</span>
            </div>
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
