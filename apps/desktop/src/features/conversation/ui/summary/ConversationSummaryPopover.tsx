// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  ArrowRight,
  Bot,
  CircleDot,
  FileDiff,
  FolderOpen,
  ListChecks,
  RefreshCw,
} from "lucide-react";
import {
  useEffect,
  useRef,
  useState,
  type MutableRefObject,
  type ReactElement,
  type ReactNode,
} from "react";
import {
  Button,
  IconButton,
  Popover,
  PopoverArrow,
  PopoverContent,
  PopoverTrigger,
} from "@/shared/ui/primitives";
import { CopyTextButton } from "@/shared/ui/CopyTextButton";
import type { ConversationActivityCounts } from "../../domain/conversationActivity";
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
  activity: ConversationActivityCounts;
  turnCount: number;
  durationMs?: number;
}

export type ConversationMcpSource = "active" | "last_observed" | "unchecked" | "stale";
export type ConversationMcpServerState =
  | "available"
  | "unavailable"
  | "disabled"
  | "not_discovered"
  | "not_exposed"
  | "stale";

/** UI 只需要脱敏后的 Thread MCP 投影，配置凭据与连接细节留在 native/runtime owner。 */
export interface ConversationMcpStatusSnapshot {
  threadId: string;
  source: ConversationMcpSource;
  catalogRevision?: string;
  observedAt?: string;
  notices: readonly ("configuration_changed" | "project_untrusted" | "project_config_error")[];
  servers: readonly ConversationMcpServerStatus[];
}

/** 名称、来源和状态均由会话配置 Owner 给出，前端不猜测项目归属。 */
export interface ConversationMcpServerStatus {
  serverId: string;
  name: string;
  scope: "global" | "project";
  state: ConversationMcpServerState;
}

/** 会话概览只读目录，连接检查由设置页独立承担。 */
export interface ConversationMcpReader {
  read(threadId: string): Promise<ConversationMcpStatusSnapshot>;
}

export interface ConversationSummaryPopoverProps {
  summary: ConversationSummary;
  threadId?: string;
  threadTitle?: string;
  createdAt?: string;
  rootPath?: string;
  onCopyText?: (text: string) => Promise<void>;
  mcpReader?: ConversationMcpReader;
  onOpenMcpSettings?: () => void;
}

type McpPopoverState =
  | { threadId: string; kind: "loading" }
  | { threadId: string; kind: "error"; message: string }
  | {
      threadId: string;
      kind: "ready";
      snapshot: ConversationMcpStatusSnapshot;
    };

/** 在每行直说当前会话的可用性，不展示探测预览或内部代际术语。 */
function mcpServerStateLabel(
  state: ConversationMcpServerState,
  source: ConversationMcpSource,
): string {
  switch (state) {
    case "available":
      if (source === "active") return "本轮可用";
      if (source === "last_observed") return "上次可用";
      return "未检查";
    case "unavailable":
      return "不可用";
    case "disabled":
      return "已停用";
    case "not_discovered":
      return "未检查";
    case "not_exposed":
      return "当前模式不可用";
    case "stale":
      return "未检查";
  }
}

/** 只把后端闭集提示翻译为下一步可理解的说明，不展示原始配置诊断。 */
function mcpNoticeLabel(
  notice: ConversationMcpStatusSnapshot["notices"][number],
  source: ConversationMcpSource,
): string {
  switch (notice) {
    case "configuration_changed":
      return source === "active" ? "配置已更新，下轮生效" : "配置已更新，下次使用时检查";
    case "project_untrusted":
      return "项目未信任，项目 MCP 未加载";
    case "project_config_error":
      return "部分项目 MCP 配置有误，请到管理页查看";
  }
}

/** 关闭或切换 Thread 时同步使未完成请求失效，避免迟到状态覆盖新的会话视图。 */
function invalidateMcpPopoverRequest(requestEpochRef: MutableRefObject<number>): void {
  requestEpochRef.current += 1;
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

/** Thread 的服务端时间无效时直接省略，避免在会话身份区展示本地推测的创建时间。 */
function formatCreatedAt(value: string | undefined): string | undefined {
  if (value === undefined) return undefined;
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return undefined;
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(timestamp);
}

/** 长 Thread ID 首尾可辨即可；完整权威值仍保留在提示、读屏名称和复制动作中。 */
function compactThreadId(value: string): string {
  return value.length > 28 ? `${value.slice(0, 15)}…${value.slice(-8)}` : value;
}

/** 将 Windows 扩展路径转成用户可粘贴的路径；只处理展示与复制，不改写 Workspace 身份。 */
function displayWorkspacePath(value: string): string {
  if (value.startsWith("\\\\?\\UNC\\")) return `\\\\${value.slice(8)}`;
  return /^\\\\\?\\[A-Za-z]:\\/.test(value) ? value.slice(4) : value;
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

/** 路径与身份保留完整可选文本，复制按钮只接入现有桌面剪贴板端口。 */
function IdentityRow({
  label,
  value,
  displayValue = value,
  onCopyText,
}: {
  label: string;
  value: string;
  displayValue?: string;
  onCopyText?: (text: string) => Promise<void>;
}): ReactElement {
  return (
    <div
      className={`ja-conversation-summary-identity-row${onCopyText === undefined ? " is-simple" : ""}`}
    >
      <dt>{label}</dt>
      <dd title={value} aria-label={value}>
        {displayValue}
      </dd>
      {onCopyText === undefined ? null : (
        <CopyTextButton text={value} label={`复制${label}`} onCopyText={onCopyText} />
      )}
    </div>
  );
}

/**
 * 将环境摘要和按需读取的 Thread MCP 状态锚定到 Conversation Header；Portal 避开裁剪，
 * 末端对齐让卡片向中间列展开。MCP 展示态按 Thread 身份派生，避免 effect 镜像状态并阻止旧会话串显。
 */
export function ConversationSummaryPopover({
  summary,
  threadId = "",
  threadTitle,
  createdAt,
  rootPath,
  onCopyText,
  mcpReader,
  onOpenMcpSettings,
}: ConversationSummaryPopoverProps): ReactElement {
  const [open, setOpen] = useState(false);
  const [mcpState, setMcpState] = useState<McpPopoverState>();
  const [readSequence, setReadSequence] = useState(0);
  const requestEpochRef = useRef(0);
  const duration = formatDuration(summary.durationMs);
  const createdAtLabel = formatCreatedAt(createdAt);
  const hasDiffStat =
    summary.changedFiles !== undefined ||
    summary.additions !== undefined ||
    summary.deletions !== undefined;

  /** 概览打开后只读当前 Thread 的轻量状态；loading/error 由当前输入派生，effect 只负责 IO。 */
  useEffect(() => {
    if (!open || threadId === "" || mcpReader === undefined) return;
    const requestEpoch = ++requestEpochRef.current;
    void Promise.resolve()
      .then(() => mcpReader.read(threadId))
      .then(
        (snapshot) => {
          if (requestEpochRef.current !== requestEpoch) return;
          setMcpState(
            snapshot.threadId === threadId
              ? { threadId, kind: "ready", snapshot }
              : { threadId, kind: "error", message: "MCP 状态与当前会话不匹配，请重试。" },
          );
        },
        () => {
          if (requestEpochRef.current === requestEpoch)
            setMcpState({ threadId, kind: "error", message: "MCP 状态读取失败，请重试。" });
        },
      );
    return () => {
      if (requestEpochRef.current === requestEpoch) requestEpochRef.current += 1;
    };
  }, [open, mcpReader, readSequence, threadId]);

  /** 关闭时撤销在途读取，管理跳转复用 Popover 的焦点归还。 */
  const updatePopoverOpen = (nextOpen: boolean): void => {
    if (!nextOpen) {
      invalidateMcpPopoverRequest(requestEpochRef);
      setMcpState(undefined);
    }
    setOpen(nextOpen);
  };

  /** 打开 MCP Settings 时沿用 Radix 的关闭与焦点归还，再切换到正确分类。 */
  const openMcpSettings = (): void => {
    updatePopoverOpen(false);
    onOpenMcpSettings?.();
  };

  /** 用户点击重试时立即展示 loading；读取副作用仍留给受 epoch 约束的 effect。 */
  const retryMcpRead = (): void => {
    setMcpState({ threadId, kind: "loading" });
    setReadSequence((sequence) => sequence + 1);
  };

  const currentMcpState = mcpState?.threadId === threadId ? mcpState : undefined;
  const visibleMcpState: McpPopoverState | undefined =
    threadId === ""
      ? { threadId, kind: "error", message: "请先选择会话。" }
      : mcpReader === undefined
        ? { threadId, kind: "error", message: "当前运行时暂时无法读取 MCP 状态。" }
        : currentMcpState;
  return (
    <Popover open={open} onOpenChange={updatePopoverOpen}>
      <PopoverTrigger asChild>
        <IconButton className="ja-inline-icon-button" label="打开上下文信息">
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
        <div className="ja-conversation-summary-body">
          <header className="ja-conversation-summary-header">
            <div>
              <p>当前对话</p>
              <h2 id="ja-conversation-summary-title">会话概览</h2>
              {threadTitle === undefined ? null : (
                <span className="ja-conversation-summary-thread-title" title={threadTitle}>
                  {threadTitle}
                </span>
              )}
            </div>
            <span className="ja-conversation-summary-status">{summary.status ?? "等待下一步"}</span>
          </header>

          <section className="ja-conversation-summary-overview" aria-label="会话活动">
            <div className="ja-conversation-summary-stats">
              <div>
                <strong>{summary.activity.totalMessages}</strong>
                <span>文字消息</span>
              </div>
              <div>
                <strong>{summary.activity.toolCalls}</strong>
                <span>工具调用</span>
              </div>
              <div>
                <strong>{summary.turnCount}</strong>
                <span>对话轮次</span>
              </div>
              {duration === undefined ? null : (
                <div>
                  <strong>{duration}</strong>
                  <span>累计处理时间</span>
                </div>
              )}
            </div>
            <p className="ja-conversation-summary-breakdown">
              用户 {summary.activity.userMessages} · 助手 {summary.activity.assistantMessages}
              {summary.activity.collaborationMessages === 0
                ? null
                : ` · 协作 ${summary.activity.collaborationMessages}`}
            </p>
          </section>

          <section
            className="ja-conversation-summary-section"
            aria-labelledby="ja-conversation-summary-identity"
          >
            <h3 id="ja-conversation-summary-identity">会话与位置</h3>
            <dl className="ja-conversation-summary-identity-list">
              {threadId === "" ? null : (
                <IdentityRow
                  label="会话 ID"
                  value={threadId}
                  displayValue={compactThreadId(threadId)}
                  onCopyText={onCopyText}
                />
              )}
              {createdAtLabel === undefined ? null : (
                <div className="ja-conversation-summary-identity-row is-simple">
                  <dt>创建时间</dt>
                  <dd>{createdAtLabel}</dd>
                </div>
              )}
              {rootPath === undefined ? null : (
                <IdentityRow
                  label="工作目录"
                  value={displayWorkspacePath(rootPath)}
                  onCopyText={onCopyText}
                />
              )}
              {summary.gitBranch === undefined ? null : (
                <IdentityRow label="Git 分支" value={summary.gitBranch} onCopyText={onCopyText} />
              )}
            </dl>
          </section>

          <section
            className="ja-conversation-summary-section"
            aria-labelledby="ja-conversation-summary-context"
          >
            <h3 id="ja-conversation-summary-context">环境与处理</h3>
            <dl className="ja-conversation-summary-list">
              <SummaryRow
                icon={<FolderOpen />}
                label="范围"
                value={<span title={summary.scope}>{summary.scope ?? "当前对话"}</span>}
              />
              {summary.model === undefined ? null : (
                <SummaryRow
                  icon={<Bot />}
                  label="模型选择"
                  value={<span title={summary.model}>{summary.model}</span>}
                />
              )}
              {summary.runtime === undefined ? null : (
                <SummaryRow icon={<CircleDot />} label="运行时" value={summary.runtime} />
              )}
              {hasDiffStat ? (
                <SummaryRow
                  icon={<FileDiff />}
                  label="变更累计"
                  value={
                    <span className="ja-conversation-summary-diff" aria-label="变更统计">
                      {summary.changedFiles === undefined ? null : (
                        <span title="按已提交处理记录累计，重复文件可能多次计入">
                          {summary.changedFiles} 文件次
                        </span>
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
            </dl>
          </section>
        </div>

        <section
          className="ja-conversation-summary-section ja-conversation-summary-mcp"
          aria-labelledby="ja-conversation-summary-mcp-title"
        >
          <div className="ja-conversation-summary-section-heading">
            <h3 id="ja-conversation-summary-mcp-title">MCP</h3>
            {onOpenMcpSettings === undefined ? null : (
              <button
                className="ja-conversation-summary-mcp-manage"
                type="button"
                onClick={openMcpSettings}
              >
                管理 MCP <ArrowRight aria-hidden="true" />
              </button>
            )}
          </div>
          {visibleMcpState === undefined || visibleMcpState.kind === "loading" ? (
            <p className="ja-conversation-summary-mcp-message" role="status">
              正在读取…
            </p>
          ) : visibleMcpState.kind === "error" ? (
            <div className="ja-conversation-summary-mcp-error" role="status">
              <p>{visibleMcpState.message}</p>
              {mcpReader === undefined || threadId === "" ? null : (
                <Button type="button" variant="ghost" size="sm" onClick={retryMcpRead}>
                  <RefreshCw aria-hidden="true" />
                  重试
                </Button>
              )}
            </div>
          ) : (
            <>
              {visibleMcpState.snapshot.notices.map((notice) => (
                <p className="ja-conversation-summary-mcp-notice" key={notice} role="status">
                  {mcpNoticeLabel(notice, visibleMcpState.snapshot.source)}
                </p>
              ))}
              {visibleMcpState.snapshot.servers.length === 0 ? (
                <p className="ja-conversation-summary-mcp-message">
                  当前会话没有已配置的 MCP 服务。
                </p>
              ) : (
                <ul className="ja-conversation-summary-mcp-list" aria-label="当前会话的 MCP 服务">
                  {[...visibleMcpState.snapshot.servers]
                    .sort((left, right) =>
                      left.scope === right.scope
                        ? left.name.localeCompare(right.name, "zh-Hans-CN") ||
                          left.serverId.localeCompare(right.serverId)
                        : left.scope === "project"
                          ? -1
                          : 1,
                    )
                    .map((server) => (
                      <li
                        className={"ja-conversation-summary-mcp-server is-" + server.state}
                        key={server.serverId}
                      >
                        <span className="ja-conversation-summary-mcp-copy">
                          <strong title={server.name}>{server.name}</strong>
                          <small>{server.scope === "project" ? "当前项目" : "全局"}</small>
                        </span>
                        <span className="ja-conversation-summary-mcp-state">
                          <span
                            className="ja-conversation-summary-mcp-state-dot"
                            aria-hidden="true"
                          />
                          {mcpServerStateLabel(server.state, visibleMcpState.snapshot.source)}
                        </span>
                      </li>
                    ))}
                </ul>
              )}
            </>
          )}
        </section>
        <PopoverArrow className="ja-conversation-summary-arrow" aria-hidden="true" />
      </PopoverContent>
    </Popover>
  );
}
