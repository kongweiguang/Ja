// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  Bot,
  CircleDot,
  Clock3,
  FileDiff,
  FolderOpen,
  GitBranch,
  ListChecks,
  MessageSquare,
  Workflow,
} from "lucide-react";
import type { ReactElement, ReactNode } from "react";
import {
  IconButton,
  Popover,
  PopoverArrow,
  PopoverContent,
  PopoverTrigger,
} from "@/shared/ui/primitives";
import "./ConversationSummaryPopover.css";

/** Conversation Header 只接收展示摘要，不依赖 Workbench 内部类型。 */
export interface ConversationSummary {
  status?: string;
  changedFiles?: number;
  additions?: number;
  deletions?: number;
  scope?: string;
  gitBranch?: string;
  model?: string;
  runtime?: string;
  turnCount: number;
  stepCount: number;
  durationMs?: number;
}

export interface ConversationSummaryPopoverProps {
  summary: ConversationSummary;
}

/**
 * 只格式化 Runtime 提供的 elapsed time，避免 UI 用墙钟时间为 Live 或未完成 Turn 推断时长。
 */
function formatDuration(durationMs: number | undefined): string | undefined {
  if (durationMs === undefined || !Number.isSafeInteger(durationMs) || durationMs < 0)
    return undefined;
  if (durationMs < 1_000) return `${durationMs} ms`;
  const seconds = Math.floor(durationMs / 1_000);
  if (seconds < 60) return `${seconds} 秒`;
  const minutes = Math.floor(seconds / 60);
  const remainder = seconds % 60;
  if (minutes < 60) return remainder === 0 ? `${minutes} 分钟` : `${minutes} 分 ${remainder} 秒`;
  return `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`;
}

/**
 * 紧凑环境行保持相同结构，同时允许各值保留自己的语义颜色和截断标题。
 */
function SummaryRow({
  icon,
  label,
  value,
}: {
  icon: ReactNode;
  label: string;
  value: ReactNode;
}): ReactElement {
  return (
    <div className="ja-conversation-summary-row">
      <span className="ja-conversation-summary-row-icon" aria-hidden="true">
        {icon}
      </span>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

/**
 * 将只读环境摘要锚定到 Conversation Header。Portal 可避开裁剪，末端对齐让卡片向中间列展开，
 * 不占用或改变 Workbench Tab。
 */
export function ConversationSummaryPopover({
  summary,
}: ConversationSummaryPopoverProps): ReactElement {
  const duration = formatDuration(summary.durationMs);
  const hasDiffStat =
    summary.changedFiles !== undefined ||
    summary.additions !== undefined ||
    summary.deletions !== undefined;
  return (
    <Popover>
      <PopoverTrigger asChild>
        <IconButton className="ja-inline-icon-button" label="打开对话摘要">
          <ListChecks aria-hidden="true" />
        </IconButton>
      </PopoverTrigger>
      <PopoverContent
        className="ja-conversation-summary-popover"
        align="end"
        side="bottom"
        sideOffset={8}
        collisionPadding={12}
        aria-labelledby="ja-conversation-summary-title"
      >
        <header className="ja-conversation-summary-header">
          <div>
            <p>当前对话</p>
            <h2 id="ja-conversation-summary-title">环境信息</h2>
          </div>
          <span>{summary.status ?? "等待下一步"}</span>
        </header>

        <dl className="ja-conversation-summary-list">
          {hasDiffStat ? (
            <SummaryRow
              icon={<FileDiff />}
              label="变更"
              value={
                <span className="ja-conversation-summary-diff" aria-label="变更统计">
                  {summary.changedFiles === undefined ? null : (
                    <span>{summary.changedFiles} 个文件</span>
                  )}
                  {summary.additions === undefined ? null : (
                    <span className="is-added">+{summary.additions}</span>
                  )}
                  {summary.deletions === undefined ? null : (
                    <span className="is-removed">-{summary.deletions}</span>
                  )}
                </span>
              }
            />
          ) : null}
          <SummaryRow
            icon={<FolderOpen />}
            label="范围"
            value={<span title={summary.scope}>{summary.scope ?? "当前对话"}</span>}
          />
          {summary.gitBranch === undefined ? null : (
            <SummaryRow
              icon={<GitBranch />}
              label="分支"
              value={<span title={summary.gitBranch}>{summary.gitBranch}</span>}
            />
          )}
          {summary.model === undefined ? null : (
            <SummaryRow
              icon={<Bot />}
              label="模型"
              value={<span title={summary.model}>{summary.model}</span>}
            />
          )}
          {summary.runtime === undefined ? null : (
            <SummaryRow icon={<CircleDot />} label="运行时" value={summary.runtime} />
          )}
        </dl>

        <section
          className="ja-conversation-summary-section"
          aria-labelledby="ja-conversation-summary-progress"
        >
          <h3 id="ja-conversation-summary-progress">处理摘要</h3>
          <dl className="ja-conversation-summary-list">
            <SummaryRow icon={<MessageSquare />} label="对话轮次" value={summary.turnCount} />
            <SummaryRow icon={<Workflow />} label="处理步骤" value={summary.stepCount} />
            {duration === undefined ? null : (
              <SummaryRow icon={<Clock3 />} label="处理时间" value={duration} />
            )}
          </dl>
        </section>
        <PopoverArrow className="ja-conversation-summary-arrow" aria-hidden="true" />
      </PopoverContent>
    </Popover>
  );
}
