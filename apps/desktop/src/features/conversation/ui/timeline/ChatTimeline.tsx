// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactElement,
  type ReactNode,
  type RefObject,
} from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { ArrowDown, CircleAlert, Paperclip, RotateCcw } from "lucide-react";
import { cn } from "@/shared/ui/primitives/cn";
import { Button } from "@/shared/ui/primitives/Button";
import type { UserApprovalDecision } from "../approval/ApprovalCard";
import type {
  ApprovalDecision,
  TimelineApproval as ApprovalSummary,
  TimelineTurn,
} from "../../domain/timelineTypes";
import { itemRevision, type TimelineItemAdapter } from "../../domain/timelineTypes";
import type { AttachmentSummary } from "../../domain/timelineContracts";
import { MarkdownMessage } from "./MarkdownMessage";
import { WorkProcess } from "./WorkProcess";
import { TurnChangesCard } from "./TurnChangesCard";
import { CopyTextButton } from "@/shared/ui/CopyTextButton";
import { ComposerContextChips } from "../composer/ComposerContextChips";
import {
  resolveSkillReferenceMetadata,
  type ConversationContextReference,
  type ConversationSkillMetadata,
} from "../../domain/userContent";
import { projectTimelineChronology } from "../../domain/timelineChronology";
import {
  HistoryAttachmentThumbnail,
  type HistoryAttachmentAuthorization,
  type HistoryAttachmentThumbnailPort,
} from "./HistoryAttachmentThumbnail";
import "./timeline.css";

export interface ChatTimelineExternalRow {
  /** 持久 identity 用于去重和虚拟行 key，禁止使用 Renderer 数组下标。 */
  readonly rowId: string;
  /** 服务端提交时间决定与 Conversation Row 的交错位置。 */
  readonly occurredAt: string;
  /** 内容变化修订只驱动滚动测量，不参与时间排序。 */
  readonly revision: string;
  readonly content: ReactNode;
}

export interface ChatTimelineProps {
  /** Item 必须直接来自规范化 Reducer 投影，视图不创建第二份业务状态。 */
  items: readonly TimelineItemAdapter[];
  /** Turn 只用于紧凑的当前工作状态，绝不能充当第二个 Store。 */
  turns?: readonly TimelineTurn[];
  /** Skill 展示元数据来自当前 catalog；历史 wire 始终只保存稳定 skillId。 */
  skills?: readonly ConversationSkillMetadata[];
  /** Approval 按 Turn 与 callId 关联 Prepared Tool，不能使用已删除的 Wire itemId。 */
  approvals?: readonly ApprovalSummary[];
  approvalDecisions?: Readonly<Record<string, ApprovalDecision | undefined>>;
  approvalClosedAt?: Readonly<Record<string, string | undefined>>;
  /** 点击发送即形成记录；ACK 后由真实 Turn 替换，失败则保留消息及就地错误。 */
  localSubmissions?: readonly {
    submissionId: string;
    threadId: string;
    text: string;
    contextReferences: readonly ConversationContextReference[];
    attachments: readonly (AttachmentSummary & { thumbnailUrl?: string })[];
    submittedAt: string;
    status: "pending" | "failed";
    error?: string;
  }[];
  onApprovalDecision?: (
    approval: ApprovalSummary,
    decision: UserApprovalDecision,
  ) => void | Promise<void>;
  onOpenLink?: (url: string) => void | Promise<void>;
  onCopyText?: (text: string) => Promise<void>;
  onReadToolArtifact?: (input: {
    threadId: string;
    turnId: string;
    callId: string;
    artifactId: string;
  }) => Promise<string>;
  /**
   * 只准备一次新的人工发送：调用方负责把原始用户文本放回 Composer，禁止在此回调中自动提交
   * 或重放旧 Turn 的 Tool，避免失败后的外部副作用被静默执行两次。
   */
  onPrepareRetry?: (turnId: string, text: string) => void;
  /** 每个终态 Turn 的冻结修改由 Workbench 承载；可选路径只定位该 artifact 内的真实文件。 */
  onReviewTurn?: (
    turn: TimelineTurn,
    changeSet: NonNullable<TimelineTurn["changeSet"]>,
    requestedPath?: string,
  ) => void;
  onOpenAttachmentPreview?: (
    attachment: {
      attachmentId: string;
      displayName: string;
      mediaKind: "image" | "text";
      threadId: string;
      authorization: HistoryAttachmentAuthorization;
    },
    source: HTMLButtonElement,
  ) => void;
  /** 历史缩略图仅通过受管 Preview session 读取，不接触路径或任意 URL。 */
  attachmentThumbnailPort?: HistoryAttachmentThumbnailPort;
  /** Task 等低频持久事实进入同一虚拟 Timeline，但不写入 Conversation reducer。 */
  externalRows?: readonly ChatTimelineExternalRow[];
  className?: string;
  emptyText?: string;
}

type TimelineRow = {
  kind: "turn";
  key: string;
  turnId: string;
  turn?: TimelineTurn;
  submissionStatus?: "pending" | "failed";
  submissionError?: string;
  attachmentAuthorization?: HistoryAttachmentAuthorization;
  attachmentThumbnailUrls?: Readonly<Record<string, string | undefined>>;
  user?: TimelineItemAdapter;
  threadMessages: TimelineItemAdapter[];
  work: TimelineItemAdapter[];
  final?: TimelineItemAdapter;
  approvals: ApprovalSummary[];
};

type OrderedTimelineRow =
  | {
      readonly kind: "conversation";
      readonly row: TimelineRow;
      readonly externalRows: readonly ChatTimelineExternalRow[];
    }
  | { readonly kind: "external"; readonly row: ChatTimelineExternalRow };

type ConversationRenderBlock =
  | { readonly kind: "user"; readonly key: string }
  | {
      readonly kind: "thread_message";
      readonly key: string;
      readonly item: TimelineItemAdapter;
    }
  | { readonly kind: "work"; readonly key: string }
  | { readonly kind: "assistant"; readonly key: string }
  | { readonly kind: "changes"; readonly key: string }
  | { readonly kind: "external"; readonly key: string; readonly row: ChatTimelineExternalRow };

type MutableTurnGroup = {
  turnId: string;
  turn?: TimelineTurn;
  submissionStatus?: "pending" | "failed";
  submissionError?: string;
  attachmentAuthorization?: HistoryAttachmentAuthorization;
  attachmentThumbnailUrls?: Readonly<Record<string, string | undefined>>;
  key: string;
  user?: TimelineItemAdapter;
  threadMessages: TimelineItemAdapter[];
  work: TimelineItemAdapter[];
  final: TimelineItemAdapter[];
  approvals: ApprovalSummary[];
};

type AssistantResponseState =
  | "working"
  | "waiting"
  | "suspended"
  | "streaming"
  | "completed"
  | "failed"
  | "cancelled";

interface TurnFailurePresentation {
  summary: string;
  recovery: string;
}

/**
 * 稳定错误码只解释已确认的运行失败，不把格式问题归因于安全威胁或用户配置；
 * 也不承诺自动重试已经可能产生 Tool 副作用的 Turn。
 */
function turnFailurePresentation(
  error: TimelineTurn["error"] | undefined,
): TurnFailurePresentation {
  switch (error?.code) {
    case "BUDGET_EXCEEDED":
      return {
        summary: "本轮在达到执行上限前没有完成回复。",
        recovery: "请缩小任务范围或补充更明确的限制，然后重新编辑并发送。",
      };
    case "MODEL_UNAVAILABLE":
      return {
        summary: "模型服务暂时不可用，这次请求已经停止。",
        recovery: "请稍后重新编辑并发送上一条消息。",
      };
    case "SUMMARY_FAILURE":
      return {
        summary: "上下文摘要生成失败，本轮回复已经停止。",
        recovery: "请缩短当前对话后重新编辑并发送；若仍然失败，请新建会话。",
      };
    case "MODEL_PROTOCOL_ERROR":
      return {
        summary: "模型响应格式有误或不完整，本轮未能完成。",
        recovery: "请重新编辑并发送；若持续失败，请查看运行日志中的具体原因。",
      };
    case "REQUEST_DEADLINE_EXCEEDED":
      return {
        summary: "本次执行超过了允许的最长时间。",
        recovery: "请缩小任务范围或稍后重新编辑并发送。",
      };
    case "APPROVAL_EXPIRED":
      return {
        summary: "等待确认已超过有效期，本次执行没有继续。",
        recovery: "请重新编辑并发送上一条消息，再及时处理新的确认请求。",
      };
    case "CONFLICT":
    case "INVALID_STATE":
      return {
        summary: "会话状态发生冲突，Ja 已停止当前回复以保护已保存的内容。",
        recovery: "请重新打开此会话，确认内容同步后再重新编辑并发送。",
      };
    default:
      return error?.retryable
        ? {
            summary: "临时故障导致这次回复中止。",
            recovery: "请稍后重新编辑并发送上一条消息。",
          }
        : {
            summary: "Ja 无法安全完成这次回复，当前执行已经结束。",
            recovery: "请根据错误代码检查运行时或模型配置，再重新编辑并发送。",
          };
  }
}

/** 错误码只承担支持诊断，不作为主文案；异常值保持有界，避免损坏失败卡布局。 */
function visibleTurnErrorCode(error: TimelineTurn["error"] | undefined): string {
  const code = error?.code.trim();
  return code ? code.slice(0, 128) : "UNKNOWN_FAILURE";
}

/**
 * 将规范化投影按 USER Message 切为 exchange；同一 Turn 消费下一条队列输入时立即开始新行，
 * 后续工作与 Final 归入新 exchange，避免把多次用户意图压进同一气泡。
 */
function buildRows(
  items: readonly TimelineItemAdapter[],
  turns: readonly TimelineTurn[],
  approvals: readonly ApprovalSummary[],
  localSubmissions: ChatTimelineProps["localSubmissions"],
): TimelineRow[] {
  const rows: MutableTurnGroup[] = [];
  const currentByTurn = new Map<string, MutableTurnGroup>();
  const exchangeByCall = new Map<string, MutableTurnGroup>();
  /** Tool callId 只在所属 Turn 内定位 exchange，避免异常重复值把审批带到另一条会话。 */
  const callKey = (turnId: string, callId: string): string => `${turnId}:${callId}`;
  /** 没有 USER Message 的运行态仍需要响应壳；真实 USER 到达后会成为新的 exchange。 */
  const currentFor = (turnId: string): MutableTurnGroup => {
    const existing = currentByTurn.get(turnId);
    if (existing !== undefined) return existing;
    const created: MutableTurnGroup = {
      key: `${turnId}:initial`,
      turnId,
      threadMessages: [],
      work: [],
      final: [],
      approvals: [],
    };
    rows.push(created);
    currentByTurn.set(turnId, created);
    return created;
  };
  for (const item of items) {
    if (item.kind === "user_message") {
      const exchange: MutableTurnGroup = {
        key: `${item.turnId}:${item.itemId}`,
        turnId: item.turnId,
        user: item,
        threadMessages: [],
        work: [],
        final: [],
        approvals: [],
      };
      rows.push(exchange);
      currentByTurn.set(item.turnId, exchange);
    } else if (item.kind === "thread_message") {
      currentFor(item.turnId).threadMessages.push(item);
    } else if (item.kind === "agent_message") {
      currentFor(item.turnId).final.push(item);
    } else {
      const group = currentFor(item.turnId);
      group.work.push(item);
      if (item.metadata?.callId !== undefined) {
        exchangeByCall.set(callKey(item.turnId, item.metadata.callId), group);
      }
    }
  }
  for (const approval of approvals) {
    const group =
      exchangeByCall.get(callKey(approval.turnId, approval.callId)) ?? currentFor(approval.turnId);
    if (!group.approvals.some((candidate) => candidate.approvalId === approval.approvalId)) {
      group.approvals.push(approval);
    }
  }
  for (const turn of turns) {
    currentFor(turn.turnId);
    const current = currentByTurn.get(turn.turnId);
    if (current !== undefined) current.turn = turn;
  }
  for (const submission of localSubmissions ?? []) {
    if (
      submission.text.trim() === "" &&
      submission.contextReferences.length === 0 &&
      (submission.attachments?.length ?? 0) === 0
    )
      continue;
    const localTurnId = `local:${submission.submissionId}`;
    rows.push({
      key: localTurnId,
      turnId: localTurnId,
      submissionStatus: submission.status,
      submissionError: submission.error,
      attachmentAuthorization: { kind: "draft" },
      attachmentThumbnailUrls: Object.fromEntries(
        submission.attachments.map((attachment) => [
          attachment.attachmentId,
          attachment.thumbnailUrl,
        ]),
      ),
      work: [],
      threadMessages: [],
      final: [],
      approvals: [],
      user: {
        itemId: `item:${submission.submissionId}`,
        threadId: submission.threadId,
        turnId: localTurnId,
        kind: "user_message",
        status: submission.status === "failed" ? "failed" : "in_progress",
        text: submission.text,
        contextReferences: [...submission.contextReferences],
        attachments: [...(submission.attachments ?? [])],
        createdAt: submission.submittedAt,
        metadata: { phase: `submission_${submission.status}` },
      },
    });
  }
  const projected = rows.map((group) => ({
    kind: "turn" as const,
    key: group.key,
    turnId: group.turnId,
    turn: group.turn,
    submissionStatus: group.submissionStatus,
    submissionError: group.submissionError,
    attachmentAuthorization: group.attachmentAuthorization,
    attachmentThumbnailUrls: group.attachmentThumbnailUrls,
    user: group.user,
    threadMessages: group.threadMessages,
    work: group.work,
    final: mergeMessages(group.final, "agent_message", true),
    approvals: group.approvals,
  }));
  /** 本地失败记录可能跨过后续成功 ACK；只在两行都有权威时间时排序，缺失时间继续保持事件顺序。 */
  return projected.sort((left, right) => {
    const leftTime =
      left.user?.createdAt ??
      left.threadMessages[0]?.createdAt ??
      left.work[0]?.createdAt ??
      left.final?.createdAt ??
      left.turn?.startedAt;
    const rightTime =
      right.user?.createdAt ??
      right.threadMessages[0]?.createdAt ??
      right.work[0]?.createdAt ??
      right.final?.createdAt ??
      right.turn?.startedAt;
    return leftTime === undefined || rightTime === undefined
      ? 0
      : leftTime.localeCompare(rightTime);
  });
}

/**
 * Conversation exchange 使用首个权威可见事实作为时间锚点；本地提交自带 submittedAt，
 * 因此流式文本更新只改变原行内容，不会因 delta 到达而在 Task Activity 之间来回移动。
 */
function rowOccurredAt(row: TimelineRow): string | undefined {
  return (
    row.user?.createdAt ?? row.work[0]?.createdAt ?? row.final?.createdAt ?? row.turn?.startedAt
  );
}

/** 外部事实与 Conversation exchange 只在视图投影层合流，禁止反向写入父 Thread Store。 */
function orderTimelineRows(
  rows: readonly TimelineRow[],
  externalRows: readonly ChatTimelineExternalRow[],
): readonly OrderedTimelineRow[] {
  const chronology = projectTimelineChronology<
    | { readonly kind: "conversation"; readonly row: TimelineRow }
    | { readonly kind: "external"; readonly row: ChatTimelineExternalRow }
  >([
    ...rows.map((row) => ({
      identity: `conversation:${row.key}`,
      occurredAt: rowOccurredAt(row),
      value: { kind: "conversation" as const, row },
    })),
    ...externalRows.map((row) => ({
      identity: `external:${row.rowId}`,
      occurredAt: row.occurredAt,
      value: { kind: "external" as const, row },
    })),
  ]).map((entry) => entry.value);
  const grouped: Array<
    | {
        readonly kind: "conversation";
        readonly row: TimelineRow;
        readonly externalRows: ChatTimelineExternalRow[];
      }
    | { readonly kind: "external"; readonly row: ChatTimelineExternalRow }
  > = [];
  let activeConversation:
    | {
        readonly kind: "conversation";
        readonly row: TimelineRow;
        readonly externalRows: ChatTimelineExternalRow[];
      }
    | undefined;
  for (const entry of chronology) {
    if (entry.kind === "conversation") {
      activeConversation = { ...entry, externalRows: [] };
      grouped.push(activeConversation);
      continue;
    }
    // 两条父 exchange 之间发生的 Task Activity 属于前一可视时间段；嵌入该虚拟行后，
    // 仍由下层 block chronology 与父 User/Work/Answer 逐项排序，而不是固定追加在 exchange 末尾。
    if (activeConversation !== undefined) activeConversation.externalRows.push(entry.row);
    else grouped.push(entry);
  }
  return grouped;
}

/** Virtualizer 与 fallback renderer 共用同一持久 key，避免测量状态因渲染路径切换而丢失。 */
function orderedRowKey(entry: OrderedTimelineRow | undefined, fallback: number): string | number {
  if (entry === undefined) return fallback;
  return entry.kind === "conversation"
    ? `conversation:${entry.row.key}`
    : `external:${entry.row.rowId}`;
}

/**
 * 同一 exchange 内的 User、Work、Answer、ChangeSet 与 Task Activity 继续按各自服务端时间排序。
 * WorkProcess 是产品上的一个可折叠 Timeline item，因此其内部 Tool 步骤保持原有 reducer 顺序。
 */
function orderConversationBlocks(
  row: TimelineRow,
  externalRows: readonly ChatTimelineExternalRow[],
  includeChanges: boolean,
): readonly ConversationRenderBlock[] {
  const assistantVisible =
    row.submissionStatus === "pending" || row.turn !== undefined || row.final !== undefined;
  const workTime = row.work[0]?.createdAt ?? row.turn?.startedAt ?? row.user?.createdAt;
  const assistantTime =
    row.final?.createdAt ?? row.turn?.completedAt ?? workTime ?? row.user?.createdAt;
  return projectTimelineChronology<ConversationRenderBlock>([
    ...(row.user === undefined
      ? []
      : [
          {
            identity: `conversation:${row.key}:0:user`,
            occurredAt: row.user.createdAt,
            value: { kind: "user" as const, key: `user:${row.key}` },
          },
        ]),
    ...row.threadMessages.map((item, index) => ({
      // 使用服务端批次顺序作为同一时间戳下的稳定次序；itemId 只负责 DOM identity。
      identity: `conversation:${row.key}:thread_message:${String(index).padStart(4, "0")}:${item.itemId}`,
      occurredAt: item.createdAt,
      value: {
        kind: "thread_message" as const,
        key: `thread-message:${item.itemId}`,
        item,
      },
    })),
    ...(row.work.length === 0 && row.approvals.length === 0
      ? []
      : [
          {
            identity: `conversation:${row.key}:1:work`,
            occurredAt: workTime,
            value: { kind: "work" as const, key: `work:${row.key}` },
          },
        ]),
    ...(assistantVisible
      ? [
          {
            identity: `conversation:${row.key}:2:assistant`,
            occurredAt: assistantTime,
            value: { kind: "assistant" as const, key: `assistant:${row.key}` },
          },
        ]
      : []),
    ...(includeChanges
      ? [
          {
            identity: `conversation:${row.key}:3:changes`,
            occurredAt: row.turn?.completedAt ?? assistantTime,
            value: { kind: "changes" as const, key: `changes:${row.key}` },
          },
        ]
      : []),
    ...externalRows.map((externalRow) => ({
      identity: `external:${externalRow.rowId}`,
      occurredAt: externalRow.occurredAt,
      value: {
        kind: "external" as const,
        key: `external:${externalRow.rowId}`,
        row: externalRow,
      },
    })),
  ]).map((entry) => entry.value);
}

/**
 * 附件属于用户提交内容而不是 Agent 工作过程，因此历史视图始终展示紧凑文件行；只使用
 * App Server 已持久化的脱敏名称、媒体摘要和稳定 identity，不暴露本地路径或内容。
 */
function AttachmentHistory({
  items,
  threadId,
  authorization,
  thumbnailUrls,
  thumbnailPort,
  onOpenPreview,
}: {
  items: readonly AttachmentSummary[];
  threadId: string;
  authorization: HistoryAttachmentAuthorization;
  thumbnailUrls?: Readonly<Record<string, string | undefined>>;
  thumbnailPort?: HistoryAttachmentThumbnailPort;
  onOpenPreview?: ChatTimelineProps["onOpenAttachmentPreview"];
}): ReactElement | null {
  if (items.length === 0) return null;
  return (
    <ul className="ja-chat-attachments" aria-label="附件">
      {items.map((item) => {
        const { attachmentId, mediaKind, displayName } = item;
        const previewable =
          onOpenPreview !== undefined && (mediaKind === "image" || mediaKind === "text");
        const size =
          item.sizeBytes < 1024
            ? `${item.sizeBytes} B`
            : item.sizeBytes < 1024 * 1024
              ? `${(item.sizeBytes / 1024).toFixed(1)} KB`
              : `${(item.sizeBytes / (1024 * 1024)).toFixed(1)} MB`;
        const kind =
          mediaKind === "pdf"
            ? "PDF"
            : mediaKind === "image"
              ? "图片"
              : mediaKind === "text"
                ? "文本"
                : "文件";
        const content = (
          <>
            {mediaKind === "image" ? (
              <HistoryAttachmentThumbnail
                attachmentId={attachmentId}
                displayName={displayName}
                authorization={authorization}
                directUrl={thumbnailUrls?.[attachmentId]}
                port={thumbnailPort}
              />
            ) : (
              <Paperclip aria-hidden="true" />
            )}
            <span className="ja-chat-attachment__name">{displayName}</span>
            <span className="ja-chat-attachment__summary">{`${kind} · ${size}`}</span>
          </>
        );
        return (
          <li className="ja-chat-attachment" key={attachmentId} data-attachment-id={attachmentId}>
            {previewable ? (
              <button
                type="button"
                className="ja-chat-attachment__content is-previewable"
                aria-label={`预览附件 ${displayName}`}
                onClick={(event) =>
                  onOpenPreview?.(
                    {
                      attachmentId,
                      displayName,
                      mediaKind,
                      threadId,
                      authorization,
                    },
                    event.currentTarget,
                  )
                }
              >
                {content}
              </button>
            ) : (
              <span className="ja-chat-attachment__content">{content}</span>
            )}
          </li>
        );
      })}
    </ul>
  );
}

/**
 * 合并同一 Turn 的 Fragment，且不修改 Reducer 持有的 Item Object。空 Fragment 为状态展示保留，
 * 可见文本只拼接一次。
 */
function mergeMessages(
  items: readonly TimelineItemAdapter[],
  kind: TimelineItemAdapter["kind"],
  final = false,
): TimelineItemAdapter | undefined {
  const first = items[0];
  if (first === undefined) return undefined;
  const text = items
    .map((item) => item.text?.trim())
    .filter((value): value is string => value !== undefined && value !== "")
    .join("\n\n");
  const last = items[items.length - 1] ?? first;
  return {
    ...first,
    kind,
    status: last.status,
    text: text || undefined,
    final: final || first.final,
  };
}

/**
 * 活动圆点只表达仍在推进，不承载业务状态；发送、工作和真实流式回复复用同一节奏，避免同一页面
 * 出现多套 Spinner 语言。文本状态由相邻 live region 负责，无动画偏好下圆点保持静态可见。
 */
function ActivityDots(): ReactElement {
  return (
    <span className="ja-chat-activity-dots" aria-hidden="true">
      <span />
      <span />
      <span />
    </span>
  );
}

/**
 * 渲染单个 Role Block；消息操作位于正文之后的同级操作层，让复制在视觉上落到对应消息下方，
 * 同时不进入用户气泡或 Assistant 阅读内容。ACK 前只保留轻量入场标识，运行反馈统一交给工作响应壳，
 * 避免用户消息旁出现一套短暂且重复的发送状态。
 */
function UserMessage({
  item,
  skills,
  submissionError,
  attachmentAuthorization,
  attachmentThumbnailUrls,
  attachmentThumbnailPort,
  onOpenLink,
  onCopyText,
  onOpenAttachmentPreview,
}: {
  item: TimelineItemAdapter;
  skills: readonly ConversationSkillMetadata[];
  submissionError?: string;
  attachmentAuthorization?: HistoryAttachmentAuthorization;
  attachmentThumbnailUrls?: Readonly<Record<string, string | undefined>>;
  attachmentThumbnailPort?: HistoryAttachmentThumbnailPort;
  onOpenLink?: (url: string) => void | Promise<void>;
  onCopyText?: (text: string) => Promise<void>;
  onOpenAttachmentPreview?: ChatTimelineProps["onOpenAttachmentPreview"];
}): ReactElement {
  const isPending = item.metadata?.phase === "submission_pending";
  return (
    <article
      aria-label="用户问题"
      className={cn(
        "ja-chat-message",
        `ja-chat-message-${item.kind}`,
        `ja-chat-message-${item.status}`,
        "ja-chat-message-user",
        isPending && "ja-chat-message-new",
      )}
      data-item-id={item.itemId}
      data-role="user"
    >
      <div
        className={cn(
          "ja-chat-message__body",
          (item.attachments?.length ?? 0) > 0 && "ja-chat-message__body--with-attachments",
        )}
      >
        <ComposerContextChips
          references={resolveSkillReferenceMetadata(item.contextReferences ?? [], skills)}
          compact
          label="消息引用"
        />
        {item.text?.trim() ? (
          <MarkdownMessage content={item.text} onOpenLink={onOpenLink} onCopyText={onCopyText} />
        ) : null}
        <AttachmentHistory
          items={item.attachments ?? []}
          threadId={item.threadId}
          authorization={attachmentAuthorization ?? { kind: "thread", threadId: item.threadId }}
          thumbnailUrls={attachmentThumbnailUrls}
          thumbnailPort={attachmentThumbnailPort}
          onOpenPreview={onOpenAttachmentPreview}
        />
      </div>
      {submissionError === undefined ? null : (
        <p className="ja-chat-message__send-error" role="alert">
          {submissionError}
        </p>
      )}
      {item.text?.trim() && onCopyText !== undefined ? (
        <div className="ja-chat-message__actions" role="group" aria-label="用户消息操作">
          <CopyTextButton text={item.text} label="复制消息" onCopyText={onCopyText} />
        </div>
      ) : null}
    </article>
  );
}

/**
 * 渲染跨会话投递的纯文本事实；来源标题与 Thread ID 始终同时可见，且不使用用户气泡、Markdown
 * 或工作状态语义，避免消息在视觉和语义上冒充当前用户或当前 Agent 的发言。
 */
function ThreadMessage({
  item,
  onCopyText,
}: {
  item: TimelineItemAdapter;
  onCopyText?: (text: string) => Promise<void>;
}): ReactElement {
  const sourceTitle = item.sourceTitle ?? "未知会话";
  const sourceThreadId = item.sourceThreadId ?? "未知 Thread";
  const content = item.text ?? "";
  return (
    <article
      aria-label={`来自会话：${sourceTitle}`}
      className={cn("ja-chat-message", "ja-chat-message-thread_message")}
      data-item-id={item.itemId}
      data-role="thread-message"
      data-source-thread-id={sourceThreadId}
    >
      <div className="ja-thread-message__body">
        <header className="ja-thread-message__source">
          <span>来自会话</span>
          <strong title={sourceThreadId}>{sourceTitle}</strong>
        </header>
        <p className="ja-thread-message__content">{content}</p>
      </div>
      {onCopyText === undefined ? null : (
        <div className="ja-chat-message__actions" role="group" aria-label="会话消息操作">
          <CopyTextButton text={content} label="复制会话消息" onCopyText={onCopyText} />
        </div>
      )}
    </article>
  );
}

/**
 * 只从 Turn 与公开 Agent Fragment 派生展示阶段，避免把 UI 动效状态写回 Store；公开文本一到达就
 * 直接进入 streaming，不设置计时器或缓冲队列。
 */
function assistantResponseState(
  turn: TimelineTurn | undefined,
  item: TimelineItemAdapter | undefined,
): AssistantResponseState {
  if (turn?.status === "waiting_approval") return "waiting";
  if (turn?.status === "suspended") return "suspended";
  if (turn?.status === "failed" || item?.status === "failed") return "failed";
  if (turn?.status === "cancelled" || item?.status === "cancelled") return "cancelled";
  if (turn?.status === "completed" || item?.status === "completed") return "completed";
  if (item?.text?.trim()) return "streaming";
  return "working";
}

/**
 * 从 Turn 接纳到终态始终复用同一个 Article；阶段提示与 Markdown 分层，使屏幕阅读器只播报阶段变化，
 * 而真实 delta 直接更新正文且不触发逐片段播报。Failed Turn 改用独立失败语义，并只允许把原问题
 * 恢复为草稿，避免未完成内容或可能已经发生的 Tool 副作用被当作成功结果重放。
 */
function AssistantResponse({
  turn,
  item,
  submittedText,
  onPrepareRetry,
  onOpenLink,
  onCopyText,
}: {
  turn?: TimelineTurn;
  item?: TimelineItemAdapter;
  submittedText?: string;
  onPrepareRetry?: ChatTimelineProps["onPrepareRetry"];
  onOpenLink?: (url: string) => void | Promise<void>;
  onCopyText?: (text: string) => Promise<void>;
}): ReactElement {
  const state = assistantResponseState(turn, item);
  const isActive = state === "working" || state === "waiting" || state === "streaming";
  const isFailed = state === "failed";
  const isCancelled = state === "cancelled";
  const statusText =
    state === "working"
      ? "正在工作"
      : state === "waiting"
        ? "等待你的确认"
        : state === "suspended"
          ? "已暂停"
          : state === "streaming"
            ? "正在回复"
            : state === "cancelled"
              ? "已取消"
              : undefined;
  const text = item?.text?.trim() ? item.text : undefined;
  const failure = isFailed ? turnFailurePresentation(turn?.error) : undefined;
  const errorCode = isFailed ? visibleTurnErrorCode(turn?.error) : undefined;
  const retryText = submittedText?.trim() ? submittedText : undefined;
  const retryTurnId = turn?.status === "failed" ? turn.turnId : undefined;
  const isFailureReply = isFailed && item?.metadata?.failureReply === true;
  // 失败卡只负责恢复草稿；构造回调时冻结 Turn 与原文身份，绝不从点击时的活动会话反查或自动提交。
  const prepareRetry =
    retryTurnId !== undefined && retryText !== undefined && onPrepareRetry !== undefined
      ? () => onPrepareRetry(retryTurnId, retryText)
      : undefined;
  const isFinalAnswer = state === "completed" && text !== undefined;
  const articleLabel = isFailed ? "失败说明" : isCancelled ? "已取消的回复" : "最终答复";

  return (
    <article
      aria-label={articleLabel}
      aria-busy={isActive || undefined}
      className={cn(
        "ja-chat-message",
        "ja-chat-message-agent_message",
        "ja-chat-message-final",
        isActive && "ja-chat-message-draft",
        (state === "failed" || state === "cancelled") && `ja-chat-message-${state}`,
      )}
      data-item-id={item?.itemId ?? `response:${turn?.turnId ?? "unknown"}`}
      data-response-state={state}
      data-role={isFinalAnswer ? "final" : isFailed ? "failure" : "response"}
    >
      <div className="ja-chat-message__body">
        {text === undefined ? null : (
          <div className="ja-chat-response__content" aria-live="off">
            {isFailed && !isFailureReply ? (
              <p className="ja-chat-failure__partial-note">
                以下内容在失败前生成，可能不完整，不能视为最终答复。
              </p>
            ) : null}
            <MarkdownMessage content={text} onOpenLink={onOpenLink} onCopyText={onCopyText} />
          </div>
        )}
        {failure === undefined ? null : (
          <section
            className="ja-chat-failure"
            role="alert"
            aria-labelledby={`failure-title-${turn?.turnId ?? item?.turnId ?? "unknown"}`}
          >
            <div className="ja-chat-failure__heading">
              <CircleAlert aria-hidden="true" />
              <strong id={`failure-title-${turn?.turnId ?? item?.turnId ?? "unknown"}`}>
                任务未完成
              </strong>
            </div>
            <p className="ja-chat-failure__completion">
              {text === undefined
                ? "本轮没有生成最终答复。"
                : isFailureReply
                  ? "本轮失败原因已保存。"
                  : "上方内容是未完成的回复，请在重试前核对。"}
            </p>
            <p className="ja-chat-failure__summary">{failure.summary}</p>
            <p className="ja-chat-failure__recovery">{failure.recovery}</p>
            <p className="ja-chat-failure__code">
              <span>错误代码</span>
              <code>{errorCode}</code>
            </p>
            {prepareRetry ? (
              <div className="ja-chat-failure__actions">
                <Button type="button" variant="secondary" size="sm" onClick={prepareRetry}>
                  <RotateCcw aria-hidden="true" />
                  重新编辑
                </Button>
                <span>只恢复原问题，不会自动发送或重放已执行的工具。</span>
              </div>
            ) : null}
          </section>
        )}
        {failure !== undefined || statusText === undefined ? null : (
          <div className="ja-chat-response__status" aria-live="polite" aria-atomic="true">
            <span>{statusText}</span>
            {state === "working" || state === "streaming" ? <ActivityDots /> : null}
          </div>
        )}
      </div>
      {text !== undefined && onCopyText !== undefined ? (
        <div
          className="ja-chat-message__actions"
          role="group"
          aria-label={isFailed ? "未完成回复操作" : "回复操作"}
        >
          <CopyTextButton
            text={text}
            label={isFailed ? "复制未完成内容" : "复制回复"}
            onCopyText={onCopyText}
          />
        </div>
      ) : null}
    </article>
  );
}

/**
 * 仅当用户已经跟随底部时让 Viewport 靠近最新 Stream Item。向上的 Wheel 意图必须先于
 * scrollTop 变化解除跟随，否则仍落在底部阈值内的第一格滚动会被下一帧 Stream 更新抢回。
 */
function useFollowLatest(
  scrollRef: RefObject<HTMLDivElement | null>,
  rowCount: number,
  revision: string,
  scrollToLatest: () => void,
  onFollowingChange: (following: boolean) => void,
): void {
  const followingRef = useRef(true);
  const lastScrollHeightRef = useRef(0);

  useEffect(() => {
    const element = scrollRef.current;
    if (element === null) {
      return undefined;
    }
    const onScroll = (): void => {
      const following = element.scrollHeight - element.scrollTop - element.clientHeight <= 64;
      followingRef.current = following;
      onFollowingChange(following);
    };
    /** 用户向上查看历史时立即让出 Viewport 所有权，不等待浏览器完成首个滚动步进。 */
    const onWheel = (event: WheelEvent): void => {
      if (event.deltaY >= 0 || !followingRef.current) {
        return;
      }
      followingRef.current = false;
      onFollowingChange(false);
    };
    element.addEventListener("scroll", onScroll, { passive: true });
    element.addEventListener("wheel", onWheel, { passive: true });
    onScroll();
    return () => {
      element.removeEventListener("scroll", onScroll);
      element.removeEventListener("wheel", onWheel);
    };
  }, [onFollowingChange, scrollRef]);

  useEffect(() => {
    if (rowCount === 0 || !followingRef.current) {
      return;
    }
    const element = scrollRef.current;
    if (element === null) {
      return;
    }
    const oldScrollHeight = lastScrollHeightRef.current;
    lastScrollHeightRef.current = element.scrollHeight;
    // 使用 requestAnimationFrame 等 Stream Text 稳定后再测量 Virtualizer，避免中间 DOM 高度引起滚动跳变。
    const frame =
      typeof window.requestAnimationFrame === "function"
        ? window.requestAnimationFrame(scrollToLatest)
        : window.setTimeout(scrollToLatest, 0);
    if (oldScrollHeight === 0) {
      followingRef.current = true;
    }
    return () => {
      if (typeof window.cancelAnimationFrame === "function" && typeof frame === "number") {
        window.cancelAnimationFrame(frame);
      } else {
        window.clearTimeout(frame);
      }
    };
  }, [revision, rowCount, scrollRef, scrollToLatest]);
}

/**
 * 对长对话做 Virtualization，同时保留 Turn 级分组、本地 Stream 更新以及桌面端共用的
 * Disclosure/Approval 组件。
 */
export function ChatTimeline({
  items,
  skills = [],
  turns = [],
  approvals = [],
  approvalDecisions = {},
  approvalClosedAt = {},
  localSubmissions = [],
  onApprovalDecision,
  onOpenLink,
  onCopyText,
  onReadToolArtifact,
  onPrepareRetry,
  onReviewTurn,
  onOpenAttachmentPreview,
  attachmentThumbnailPort,
  externalRows = [],
  className,
  emptyText = "发送一条消息后，工作过程会显示在这里。",
}: ChatTimelineProps): ReactElement {
  const rows = useMemo(
    () => buildRows(items, turns, approvals, localSubmissions),
    [approvals, items, localSubmissions, turns],
  );
  const orderedRows = useMemo(() => orderTimelineRows(rows, externalRows), [externalRows, rows]);
  const scrollRef = useRef<HTMLDivElement>(null);
  const [followingLatest, setFollowingLatest] = useState(true);
  const revision = useMemo(
    () =>
      orderedRows
        .map((entry) => {
          if (entry.kind === "external") {
            return `external:${entry.row.rowId}:${entry.row.revision}`;
          }
          const row = entry.row;
          return [
            row.key,
            row.turn?.status ?? "",
            row.turn?.startedAt ?? "",
            row.turn?.completedAt ?? "",
            row.turn?.changeSet == null ? "" : JSON.stringify(row.turn.changeSet.stats),
            itemRevision(row.user),
            row.user?.attachments
              ?.map(
                (attachment) =>
                  `${attachment.attachmentId}:${attachment.displayName}:${attachment.sizeBytes}:${attachment.mediaKind}:${attachment.mediaType}`,
              )
              .join(",") ?? "",
            row.work.map(itemRevision).join(","),
            itemRevision(row.final),
            row.approvals.map((approval) => approval.approvalId).join(","),
            entry.externalRows
              .map((externalRow) => `${externalRow.rowId}:${externalRow.revision}`)
              .join(","),
          ].join("|");
        })
        .join("||"),
    [orderedRows],
  );
  // TanStack Virtual 暴露命令式实例；对其 Memoize 会让 React Compiler 保留陈旧 Scroll Function，
  // 因此该显式 Escape Hatch 只留在 Virtualization 边界内。
  // eslint-disable-next-line react-hooks/incompatible-library
  const virtualizer = useVirtualizer({
    // React 19 的测量 ref 可能在 commit 生命周期内同步校正；避免 adapter 在该阶段调用 flushSync。
    useFlushSync: false,
    count: orderedRows.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => 280,
    overscan: 6,
    getItemKey: (index) => {
      return orderedRowKey(orderedRows[index], index);
    },
  });
  const scrollToLatest = useMemo(
    () => () => {
      if (orderedRows.length > 0) {
        virtualizer.scrollToIndex(orderedRows.length - 1, { align: "end" });
      }
    },
    [orderedRows.length, virtualizer],
  );
  useFollowLatest(scrollRef, orderedRows.length, revision, scrollToLatest, setFollowingLatest);
  const virtualRows = virtualizer.getVirtualItems();
  const visibleRows =
    virtualRows.length > 0
      ? virtualRows
      : orderedRows.slice(0, 24).map((_, index) => ({
          index,
          key: orderedRowKey(orderedRows[index], index),
          start: index * 280,
          size: 280,
          end: (index + 1) * 280,
          lane: 0,
        }));

  /** 将用户带回 Live Tail，但不修改规范化 Timeline 投影。 */
  const handleScrollToLatest = (): void => {
    setFollowingLatest(true);
    scrollToLatest();
  };

  return (
    <section className={cn("ja-chat-timeline", className)} aria-label="对话时间线">
      {orderedRows.length === 0 ? <p className="ja-chat-timeline__empty">{emptyText}</p> : null}
      <div className="ja-chat-timeline__scroll" ref={scrollRef}>
        <div
          className="ja-chat-timeline__spacer ja-conversation-content-rail"
          style={{ height: virtualizer.getTotalSize() }}
        >
          {visibleRows.map((virtualRow) => {
            const entry = orderedRows[virtualRow.index];
            if (entry === undefined) {
              return null;
            }
            if (entry.kind === "external") {
              return (
                <div
                  className="ja-chat-timeline__row ja-chat-timeline__external-row"
                  key={virtualRow.key}
                  data-index={virtualRow.index}
                  data-external-row-id={entry.row.rowId}
                  ref={virtualizer.measureElement}
                  style={{ transform: `translateY(${virtualRow.start}px)` }}
                >
                  {entry.row.content}
                </div>
              );
            }
            const row = entry.row;
            const changeSet = row.turn?.changeSet;
            const visibleChangeSet =
              row.turn !== undefined &&
              ["completed", "failed", "cancelled"].includes(row.turn.status) &&
              changeSet != null &&
              changeSet.stats.files > 0
                ? changeSet
                : undefined;
            const blocks = orderConversationBlocks(
              row,
              entry.externalRows,
              visibleChangeSet !== undefined,
            );
            return (
              <div
                className="ja-chat-timeline__row"
                key={virtualRow.key}
                data-index={virtualRow.index}
                data-turn-id={row.turnId}
                ref={virtualizer.measureElement}
                style={{ transform: `translateY(${virtualRow.start}px)` }}
              >
                {blocks.map((block) => {
                  switch (block.kind) {
                    case "user":
                      return row.user === undefined ? null : (
                        <UserMessage
                          key={block.key}
                          item={row.user}
                          skills={skills}
                          submissionError={row.submissionError}
                          attachmentAuthorization={row.attachmentAuthorization}
                          attachmentThumbnailUrls={row.attachmentThumbnailUrls}
                          attachmentThumbnailPort={attachmentThumbnailPort}
                          onOpenLink={onOpenLink}
                          onCopyText={onCopyText}
                          onOpenAttachmentPreview={onOpenAttachmentPreview}
                        />
                      );
                    case "thread_message":
                      return (
                        <ThreadMessage key={block.key} item={block.item} onCopyText={onCopyText} />
                      );
                    case "work":
                      return (
                        <WorkProcess
                          key={block.key}
                          steps={row.work}
                          turn={row.turn}
                          approvals={row.approvals}
                          approvalDecisions={approvalDecisions}
                          approvalClosedAt={approvalClosedAt}
                          onApprovalDecision={onApprovalDecision}
                          onOpenLink={onOpenLink}
                          onCopyText={onCopyText}
                          onReadToolArtifact={onReadToolArtifact}
                        />
                      );
                    case "assistant":
                      return (
                        <AssistantResponse
                          key={block.key}
                          turn={row.turn}
                          item={row.final}
                          submittedText={row.user?.text}
                          onPrepareRetry={onPrepareRetry}
                          onOpenLink={onOpenLink}
                          onCopyText={onCopyText}
                        />
                      );
                    case "changes":
                      return row.turn === undefined || visibleChangeSet === undefined ? null : (
                        <TurnChangesCard
                          key={block.key}
                          turn={row.turn}
                          changeSet={visibleChangeSet}
                          onReview={
                            visibleChangeSet.artifactId === undefined || onReviewTurn === undefined
                              ? undefined
                              : (requestedPath) =>
                                  onReviewTurn(row.turn!, visibleChangeSet, requestedPath)
                          }
                        />
                      );
                    case "external":
                      return (
                        <div
                          className="ja-chat-timeline__embedded-external"
                          key={block.key}
                          data-external-row-id={block.row.rowId}
                        >
                          {block.row.content}
                        </div>
                      );
                  }
                })}
              </div>
            );
          })}
        </div>
      </div>
      {orderedRows.length > 0 && !followingLatest ? (
        <button
          type="button"
          className="ja-chat-timeline__jump-latest"
          onClick={handleScrollToLatest}
        >
          <ArrowDown aria-hidden="true" />
          <span>回到最新</span>
        </button>
      ) : null}
    </section>
  );
}
