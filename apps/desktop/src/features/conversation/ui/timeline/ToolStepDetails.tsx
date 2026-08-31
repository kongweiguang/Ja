// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { ChevronDown, ChevronUp, LoaderCircle } from "lucide-react";
import { useMemo, useState, type ReactElement } from "react";
import type { ToolPresentation, WorkStepAdapter } from "../../domain/timelineTypes";

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

/** Tool 状态只翻译 App Server 已投影的闭集，不从退出码或 Item 状态猜测。 */
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

/** Shell 将 stdout/stderr 保持为不同事实，普通 Tool 只展示 Java 提供的安全 outputPreview。 */
function presentationOutput(presentation: ToolPresentation): string | undefined {
  if (presentation.kind !== "shell") return presentation.outputPreview;
  const sections = [
    presentation.stdout?.trim() ? `stdout\n${presentation.stdout}` : undefined,
    presentation.stderr?.trim() ? `stderr\n${presentation.stderr}` : undefined,
  ].filter((value): value is string => value !== undefined);
  return sections.length === 0 ? presentation.outputPreview : sections.join("\n\n");
}

/** Tool 详情只投影安全 presentation，并把长输出限制为十行预览。 */
export function ToolStepDetails({
  step,
  onReadArtifact,
}: ToolStepDetailsProps): ReactElement | null {
  const presentation = step.metadata?.presentation;
  const callId = step.metadata?.callId;
  const [expanded, setExpanded] = useState(false);
  const [loadedOutput, setLoadedOutput] = useState<string>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const output =
    loadedOutput ?? (presentation === undefined ? undefined : presentationOutput(presentation));
  const lines = useMemo(() => output?.split(/\r?\n/u) ?? [], [output]);

  if (presentation === undefined) return null;
  const canLoad =
    presentation.artifactId !== undefined && callId !== undefined && onReadArtifact !== undefined;
  const locallyExpandable = lines.length > PREVIEW_LINES;
  const shownOutput = expanded ? output : lines.slice(0, PREVIEW_LINES).join("\n");

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
      setExpanded(true);
    } catch {
      setError("无法读取完整输出，请稍后重试。");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="ja-tool-details" data-tool-kind={presentation.kind}>
      {presentation.command?.trim() ? (
        <div className="ja-tool-details__command">
          <span>命令</span>
          <code>{presentation.command}</code>
        </div>
      ) : presentation.inputPreview?.trim() ? (
        <pre className="ja-tool-details__input">{presentation.inputPreview}</pre>
      ) : null}
      {presentation.relativeCwd ? (
        <span className="ja-tool-details__cwd">工作目录：{presentation.relativeCwd}</span>
      ) : null}
      {shownOutput?.trim() ? (
        <pre className="ja-tool-details__output" data-status={presentation.status}>
          {shownOutput}
        </pre>
      ) : null}
      <div className="ja-tool-details__facts">
        <span>状态：{presentationStatusLabel(presentation.status)}</span>
        {presentation.exitCode === undefined ? null : <span>退出码 {presentation.exitCode}</span>}
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
          onClick={() => setExpanded((value) => !value)}
        >
          {expanded ? <ChevronUp aria-hidden="true" /> : <ChevronDown aria-hidden="true" />}
          {expanded ? "收起输出" : `展开全部 ${lines.length} 行`}
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
    </div>
  );
}
