// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  useCallback,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
  type MouseEvent,
  type ReactElement,
  type ReactNode,
} from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import {
  ArrowDown,
  Copy,
  GitFork,
  LoaderCircle,
  Paperclip,
  Pencil,
  TriangleAlert,
} from "lucide-react";
import { cn } from "@/shared/ui/primitives/cn";
import { MenuItem, MenuSeparator, PointerContextMenu } from "@/shared/ui/primitives";
import { observeElementOffsetWhileActive } from "@/shared/ui/observeElementOffsetWhileActive";
import type { UserApprovalDecision } from "../approval/ApprovalCard";
import type {
  ApprovalDecision,
  TimelineApproval as ApprovalSummary,
  TimelineTurn,
} from "../../domain/timelineTypes";
import { itemRevision, type TimelineItemAdapter } from "../../domain/timelineTypes";
import type { AttachmentSummary } from "../../domain/timelineContracts";
import { MarkdownMessage, type MarkdownFileTarget } from "./MarkdownMessage";
import { WorkProcess, type WorkProcessDisplayMode } from "./WorkProcess";
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
import {
  shouldAdjustTimelineScrollPosition,
  TimelineScrollCache,
  useTimelineScroll,
  type TimelineScrollKey,
} from "./timelineScroll";
import type { TimelineDisclosureCache } from "./timelineDisclosure";
import type { ConversationProcessBinding } from "../../application/processSessionTypes";
import { useFullPublicText } from "./useFullPublicText";
import { exportPublicText } from "./exportPublicText";
import "./timeline.css";

/** 实时终态最多 4,096 UTF-16 单元；代理对边界可少一位，之后按身份读取全文。 */
const LONG_ANSWER_PREVIEW_THRESHOLD = 4_095;

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
  /** 当前 Thread 的瞬态 viewport identity；缺失时保留 fixture/嵌入调用方的默认尾部语义。 */
  threadId?: string;
  /** 旧页只在当前可见 Thread 中按需读取，按钮不触发隐藏会话的扫描。 */
  hasOlderHistory?: boolean;
  loadingOlderHistory?: boolean;
  olderHistoryError?: string;
  onLoadOlderHistory?: () => Promise<void>;
  /** 由稳定的 ConversationWorkspace owner 提供，使 Timeline 短暂卸载时仍能恢复滚动锚点。 */
  scrollCache?: TimelineScrollCache;
  /** Thread 作用域的用户折叠选择；虚拟卸载与历史回读不得重置用户主动展开的内容。 */
  disclosureCache?: TimelineDisclosureCache;
  /** Item 必须直接来自规范化 Reducer 投影，视图不创建第二份业务状态。 */
  items: readonly TimelineItemAdapter[];
  /** Turn 只用于紧凑的当前工作状态，绝不能充当第二个 Store。 */
  turns?: readonly TimelineTurn[];
  /** 逐条回复用最后一个持久 FINAL_ANSWER identity 分叉，不能用合并消息的首项 ID。 */
  onForkReply?: (anchor: { threadId: string; itemId: string }) => Promise<ForkReplyOutcome>;
  /** 按 Thread/Item identity 持有操作状态，虚拟行卸载后仍能恢复原操作反馈。 */
  forkingReplies?: readonly { threadId: string; itemId: string }[];
  /** 已有创建回执但尚未成功打开；再次点击只重开该目标，不会再次创建。 */
  forkCreatedReplies?: readonly { threadId: string; itemId: string }[];
  forkOpenFailures?: readonly { threadId: string; itemId: string }[];
  /** RPC 明确冲突与结果待核实必须跨虚拟行卸载保留各自的安全重试语义。 */
  forkConflictReplies?: readonly { threadId: string; itemId: string }[];
  forkUnconfirmedReplies?: readonly { threadId: string; itemId: string }[];
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
  onOpenLink?: (url: string, source?: HTMLElement) => void | Promise<void>;
  onOpenFile?: (
    target: MarkdownFileTarget,
    source: HTMLElement,
    mode?: "explorer" | "browser",
  ) => void | Promise<void>;
  onCopyText?: (text: string) => Promise<void>;
  /** 仅在用户查看或复制时按稳定 Thread/Item identity 读取已提交正文。 */
  onReadMessageContent?: (threadId: string, itemId: string) => Promise<string>;
  /** 终态答复可跨未加载的旧页；生产 Controller 应以最终消息身份查齐整段。 */
  onReadAnswerContent?: (
    finalMessageId: string,
    minimumRevision?: number,
    turnId?: string,
  ) => Promise<string>;
  onReadToolArtifact?: (input: {
    threadId: string;
    turnId: string;
    callId: string;
    artifactId: string;
  }) => Promise<string>;
  onResolveToolRecovery?: (input: {
    threadId: string;
    turnId: string;
    callId: string;
    expectedThreadRevision: number;
    expectedRecoveryRevision: number;
    decision: "retry" | "skip";
    operationKey: string;
  }) => Promise<unknown>;
  processSession?: ConversationProcessBinding;
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
  /** 仅当前路径最后一个失败且没有成功答复的问题接收编辑意图。 */
  editableSourceMessageId?: string;
  onEditQuestion?: (item: TimelineItemAdapter) => void;
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
  forkAnchor?: { threadId: string; itemId: string };
  approvals: ApprovalSummary[];
};

type MessageMenuRole = "user" | "thread-message" | "final" | "response" | "failure";

interface MessageContextMenuSession {
  readonly key: number;
  readonly x: number;
  readonly y: number;
  readonly itemId: string;
  readonly role: MessageMenuRole;
  readonly opener: HTMLElement;
}

/** 只使用 DOM 角色将菜单事件路由回当前的类型化 Timeline 投影。 */
function isMessageMenuRole(role: string | undefined): role is MessageMenuRole {
  return (
    role === "user" ||
    role === "thread-message" ||
    role === "final" ||
    role === "response" ||
    role === "failure"
  );
}

/** 菜单目标保持在实际问题或答复上，避免选区、Markdown 目标和嵌套控件被误劫持。 */
function shouldPreserveNativeMessageContextMenu(target: EventTarget | null): boolean {
  if (window.getSelection()?.isCollapsed === false) return true;
  if (!(target instanceof Element)) return false;
  return (
    target.closest(
      "a, [data-file-reference], button, input, textarea, select, [contenteditable='true']",
    ) !== null
  );
}

/** 只依据当前规范化 Timeline 投影暴露复制与编辑，菜单存活期间目标失效时不会执行旧对象。 */
function messageMenuActions(
  item: TimelineItemAdapter | undefined,
  row: TimelineRow | undefined,
  role: MessageMenuRole,
  editableSourceMessageId: string | undefined,
  onCopyText: ChatTimelineProps["onCopyText"],
  onEditQuestion: ChatTimelineProps["onEditQuestion"],
): { copyText?: string; canEdit: boolean } | undefined {
  if (item === undefined) return undefined;
  const failedReplyIsHidden =
    item.metadata?.failureReply === true &&
    (row?.turn?.status === "failed" || item.status === "failed");
  const copyText =
    onCopyText !== undefined &&
    !failedReplyIsHidden &&
    item.text?.trim() &&
    !(item.kind === "agent_message" && item.text.length >= LONG_ANSWER_PREVIEW_THRESHOLD)
      ? item.text
      : undefined;
  const canEdit =
    role === "user" &&
    row !== undefined &&
    editableSourceMessageId !== undefined &&
    onEditQuestion !== undefined &&
    row.user?.itemId === editableSourceMessageId &&
    (row.turn?.status === "failed" || row.turn?.status === "cancelled") &&
    (row.final === undefined || row.final.metadata?.failureReply === true);
  return copyText === undefined && !canEdit ? undefined : { copyText, canEdit };
}

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
  | "waiting_input"
  | "waiting"
  | "suspended"
  | "streaming"
  | "completed"
  | "failed"
  | "cancelled";

type ForkReplyOutcome = "opened" | "deferred" | "open_failed";

/**
 * Fork 与复制共用同一圆形操作位；控制器持有请求和可恢复错误语义，局部状态只补充不可恢复的泛化反馈。
 * @author kongweiguang
 */
function ForkReplyButton({
  anchor,
  pending = false,
  created = false,
  openFailed = false,
  conflict = false,
  unconfirmed = false,
  onForkReply,
}: {
  anchor: { threadId: string; itemId: string };
  pending?: boolean;
  created?: boolean;
  openFailed?: boolean;
  conflict?: boolean;
  unconfirmed?: boolean;
  onForkReply: NonNullable<ChatTimelineProps["onForkReply"]>;
}): ReactElement {
  const [failed, setFailed] = useState(false);
  const accessibleLabel = openFailed
    ? "已创建，打开失败，重试打开会话"
    : unconfirmed
      ? "分叉结果待核实；重试将继续使用此回复"
      : conflict
        ? "会话已更新；请重试此回复"
        : created
          ? "新会话已创建，打开会话"
          : failed
            ? "分叉失败，重试"
            : "从此回复新建会话";
  const title = pending
    ? "正在创建会话"
    : openFailed
      ? "已创建，打开失败；点击重试打开"
      : unconfirmed
        ? "分叉结果待核实；点击后继续使用此回复和原操作身份重试"
        : conflict
          ? "会话已更新；刷新后点击重试此回复"
          : created
            ? "新会话已创建；点击打开"
            : failed
              ? "分叉失败，点击重试"
              : "从此回复新建会话";

  /** 把创建错误与打开错误分别呈现；open_failed 后下一次操作只重开原回执。 */
  const fork = async (): Promise<void> => {
    if (pending) return;
    setFailed(false);
    try {
      const outcome = await onForkReply(anchor);
      setFailed(outcome === "open_failed");
    } catch {
      setFailed(true);
    }
  };

  return (
    <button
      type="button"
      className={`ja-copy-text-button${pending ? " is-pending" : ""}`}
      aria-label={accessibleLabel}
      aria-busy={pending || undefined}
      data-testid="fork-reply"
      data-source-item-id={anchor.itemId}
      data-fork-state={
        pending
          ? "pending"
          : openFailed
            ? "open-failed"
            : unconfirmed
              ? "unconfirmed"
              : conflict
                ? "conflict"
                : created
                  ? "created"
                  : failed
                    ? "failed"
                    : "ready"
      }
      title={title}
      disabled={pending}
      onClick={(event) => {
        event.stopPropagation();
        void fork();
      }}
    >
      {pending ? (
        <LoaderCircle aria-hidden="true" />
      ) : failed || conflict || unconfirmed ? (
        <TriangleAlert aria-hidden="true" />
      ) : (
        <GitFork aria-hidden="true" />
      )}
    </button>
  );
}

/**
 * 稳定错误码只提供一条可读原因；恢复动作已由 Composer 的新轮次续答承载，不能在 Timeline
 * 重复展示技术诊断、操作建议或可能重放 Tool 的伪操作。
 */
function turnFailurePresentation(error: TimelineTurn["error"] | undefined): string {
  if (error?.message?.trim()) return error.message;
  switch (error?.code) {
    case "BUDGET_EXCEEDED":
      return "已达到本轮资源上限。";
    case "MODEL_UNAVAILABLE":
      return "模型服务暂时不可用。";
    case "MODEL_UPSTREAM_REJECTED":
      return "请求被上游拒绝。";
    case "MODEL_STREAM_INVALID":
      return "模型响应流损坏或不完整。";
    case "MODEL_IDLE_TIMEOUT":
      return "等待模型响应超时。";
    case "SUMMARY_FAILURE":
      return "对话摘要生成失败。";
    case "MODEL_PROTOCOL_ERROR":
      return "模型响应格式有误或不完整。";
    case "REQUEST_DEADLINE_EXCEEDED":
      return "本次执行超时。";
    case "APPROVAL_EXPIRED":
      return "工具授权已失效。";
    case "CONFLICT":
    case "INVALID_STATE":
      return "会话状态已变化。";
    default:
      return error?.retryable ? "本次回复暂时中断。" : "本次回复未能完成。";
  }
}

/**
 * 历史 read 会同时返回本轮最后的 assistant_progress 与冻结 final_answer；两者正文相同且最终
 * 答复已有独立阅读位置时，保留前者会让重载后的工作过程重复最终正文。
 *
 * 这里只剔除同一 exchange 内、文本完全相同的持久 progress，不影响 Tool 前的过程叙事或 reasoning，
 * 也不触碰仍在流式中的 Draft。
 */
function isPersistedFinalProgressDuplicate(
  item: TimelineItemAdapter,
  finalTexts: ReadonlySet<string>,
): boolean {
  const text = item.text?.trim();
  return (
    item.kind === "commentary" &&
    item.status !== "in_progress" &&
    item.metadata?.phase === "assistant_progress" &&
    text !== undefined &&
    finalTexts.has(text)
  );
}

/** 重试是当前工作状态的修饰信息，不作为单独过程步骤占一行。 */
function isAssistantRetryStatus(item: TimelineItemAdapter): boolean {
  return item.kind === "commentary" && item.metadata?.phase === "assistant_retry";
}

/** 只拼接最近一次 Tool 之后已提交的纯文本段；完整消息按需读取，避免长回复常驻 Timeline。 */
async function readCompleteAnswer(
  final: TimelineItemAdapter,
  work: readonly TimelineItemAdapter[],
  readItem: (threadId: string, itemId: string) => Promise<string>,
): Promise<string> {
  let lastToolIndex = -1;
  for (let index = work.length - 1; index >= 0; index -= 1) {
    if (work[index]?.kind === "tool_call") {
      lastToolIndex = index;
      break;
    }
  }
  const progress = work
    .slice(lastToolIndex + 1)
    .filter((item) => item.kind === "commentary" && item.metadata?.phase === "assistant_progress");
  const parts: string[] = [];
  for (const item of progress) parts.push(await readItem(item.threadId, item.itemId));
  const fullFinal = await readItem(final.threadId, final.itemId);
  const preceding = parts.join("");
  if (preceding === "" || fullFinal.startsWith(preceding)) return fullFinal;
  if (preceding.endsWith(fullFinal)) return preceding;
  return preceding + fullFinal;
}

/**
 * 将规范化投影按 USER Message 切为 exchange；同一 Turn 消费下一条队列输入时立即开始新行，
 * 后续工作与最终答复归入新 exchange，避免把多次用户意图压进同一气泡。
 *
 * Reasoning、assistant Draft 与已经提交的 Tool 模型步正文都归入工作过程；只有 terminal
 * 产生的 agent_message 能进入最终答复，避免运行中的模型正文提前越出 WorkProcess。只有无 USER 的
 * 跨 Turn continuation 才通过 sourceMessageId 并回原问题；普通 Turn 也有来源 ID，不能据此合并队列输入。
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
  /**
   * 同一 Turn 的用户输入序号是跨 thread/read 稳定的；不能把服务端 itemId 当作 exchange
   * identity，因为 turn/start 的本地 item 会在持久快照中被随机 item_* 替换，从而重建响应壳。
   */
  const userExchangeOrdinals = new Map<string, number>();
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
      const exchangeOrdinal = userExchangeOrdinals.get(item.turnId) ?? 0;
      userExchangeOrdinals.set(item.turnId, exchangeOrdinal + 1);
      const exchange: MutableTurnGroup = {
        key: `${item.turnId}:exchange:${exchangeOrdinal}`,
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
  for (const group of rows) {
    const finalTexts = new Set(
      group.final.flatMap((item) =>
        item.kind === "agent_message" && item.final === true && item.text?.trim()
          ? [item.text.trim()]
          : [],
      ),
    );
    if (finalTexts.size > 0) {
      group.work = group.work.filter(
        (item) => !isPersistedFinalProgressDuplicate(item, finalTexts),
      );
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
  // 来源 ID 同时用于普通 Turn；仅无独立 USER 的跨 Turn continuation 归回原问题。
  for (const continuation of turns) {
    if (continuation.sourceMessageId == null || userExchangeOrdinals.has(continuation.turnId))
      continue;
    const source = rows.find(
      (candidate) => candidate.user?.itemId === continuation.sourceMessageId,
    );
    const continuationGroup = currentByTurn.get(continuation.turnId);
    if (
      source === undefined ||
      continuationGroup === undefined ||
      source.turnId === continuation.turnId ||
      source === continuationGroup
    )
      continue;
    source.threadMessages.push(...continuationGroup.threadMessages);
    source.work.push(...continuationGroup.work);
    source.final.push(...continuationGroup.final);
    source.approvals.push(...continuationGroup.approvals);
    if (
      source.turn === undefined ||
      (continuation.threadRevision ?? -1) >= (source.turn.threadRevision ?? -1)
    )
      source.turn = continuation;
    // 当前路径只呈现最新尝试的失败收口；较早的固定失败正文仍留在持久审计中。
    source.final = source.final.filter(
      (item) => item.metadata?.failureReply !== true || item.turnId === source.turn?.turnId,
    );
    rows.splice(rows.indexOf(continuationGroup), 1);
    currentByTurn.set(continuation.turnId, source);
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
        // 尚未被服务端确认的本地提交属于当前会话草稿，不是继承历史。
        origin: "native",
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
    // 仅 terminal agent_message 拥有最终答复语义；运行中的公开正文始终由 WorkProcess 承载。
    final: mergeMessages(group.final, "agent_message", true),
    forkAnchor:
      group.final.at(-1)?.kind === "agent_message" &&
      group.final.at(-1)?.final === true &&
      group.final.at(-1)?.status === "completed" &&
      group.final.at(-1)?.metadata?.failureReply !== true
        ? { threadId: group.final.at(-1)!.threadId, itemId: group.final.at(-1)!.itemId }
        : undefined,
    approvals: group.approvals,
  }));
  /**
   * 本地失败记录可能跨过后续成功 ACK；只按可解析时间重排且保留同毫秒的权威输入顺序，
   * 避免格式不同但同一毫秒的时间字符串把 snapshot 因果顺序打乱。
   * @author kongweiguang
   */
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
    if (leftTime === undefined || rightTime === undefined) return 0;
    const leftMillis = Date.parse(leftTime);
    const rightMillis = Date.parse(rightTime);
    return Number.isFinite(leftMillis) && Number.isFinite(rightMillis)
      ? leftMillis - rightMillis
      : 0;
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

/**
 * 外部事实与 Conversation exchange 只在视图投影层合流，禁止反向写入父 Thread Store；
 * Conversation 数组顺序来自权威 item 投影，只为同毫秒排序提供因果序，row.key 仍是稳定身份。
 * @author kongweiguang
 */
function orderTimelineRows(
  rows: readonly TimelineRow[],
  externalRows: readonly ChatTimelineExternalRow[],
): readonly OrderedTimelineRow[] {
  const chronology = projectTimelineChronology<
    | { readonly kind: "conversation"; readonly row: TimelineRow }
    | { readonly kind: "external"; readonly row: ChatTimelineExternalRow }
  >([
    ...rows.map((row, causalOrder) => ({
      identity: `conversation:${row.key}`,
      occurredAt: rowOccurredAt(row),
      causalOrder,
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
  const turnNeedsStatus = row.turn !== undefined && row.turn.status !== "completed";
  const assistantVisible =
    row.submissionStatus === "pending" || row.final !== undefined || turnNeedsStatus;
  const workSteps = row.work.filter((item) => !isAssistantRetryStatus(item));
  const workTime = workSteps[0]?.createdAt ?? row.turn?.startedAt ?? row.user?.createdAt;
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
    ...(workSteps.length === 0 && row.approvals.length === 0
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
          <li
            className={cn(
              "ja-chat-attachment",
              mediaKind === "image" && "ja-chat-attachment--image",
            )}
            key={attachmentId}
            data-attachment-id={attachmentId}
          >
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
    origin: last.origin,
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
 * 避免用户消息旁出现一套短暂且重复的发送状态。编辑动作只由当前可重问问题的来源 ID 驱动，
 * 这样较早的失败提问不会绕过服务端当前路径资格；可复制消息才接收右键与键盘上下文菜单。
 */
function UserMessage({
  item,
  skills,
  submissionError,
  attachmentAuthorization,
  attachmentThumbnailUrls,
  attachmentThumbnailPort,
  onOpenLink,
  onOpenFile,
  onCopyText,
  onReadMessageContent,
  onOpenAttachmentPreview,
  canEdit,
  onEditQuestion,
  onContextMenu,
  onContextMenuKeyDown,
}: {
  item: TimelineItemAdapter;
  skills: readonly ConversationSkillMetadata[];
  submissionError?: string;
  attachmentAuthorization?: HistoryAttachmentAuthorization;
  attachmentThumbnailUrls?: Readonly<Record<string, string | undefined>>;
  attachmentThumbnailPort?: HistoryAttachmentThumbnailPort;
  onOpenLink?: (url: string, source?: HTMLElement) => void | Promise<void>;
  onOpenFile?: (target: MarkdownFileTarget, source: HTMLElement) => void | Promise<void>;
  onCopyText?: (text: string) => Promise<void>;
  onReadMessageContent?: (threadId: string, itemId: string) => Promise<string>;
  onOpenAttachmentPreview?: ChatTimelineProps["onOpenAttachmentPreview"];
  canEdit: boolean;
  onEditQuestion?: ChatTimelineProps["onEditQuestion"];
  onContextMenu?: (event: MouseEvent<HTMLElement>) => void;
  onContextMenuKeyDown?: (event: KeyboardEvent<HTMLElement>) => void;
}): ReactElement {
  const isPending = item.metadata?.phase === "submission_pending";
  const contextReferences = resolveSkillReferenceMetadata(item.contextReferences ?? [], skills);
  const isTruncated = item.bodyTruncated === true;
  const {
    text: fullText,
    loading: fullTextLoading,
    error: fullTextError,
    load: loadFullText,
  } = useFullPublicText(
    item,
    onReadMessageContent === undefined
      ? undefined
      : () => onReadMessageContent(item.threadId, item.itemId),
  );
  const [showFullQuestion, setShowFullQuestion] = useState(false);
  const messageText = showFullQuestion && fullText !== undefined ? fullText : (item.text ?? "");
  const hasMessageBody = contextReferences.length > 0 || messageText.trim() !== "" || isTruncated;
  const hasContextActions =
    !isTruncated && ((messageText.trim() !== "" && onCopyText !== undefined) || canEdit);

  /** 展开前才分页读取正文；复制读取过全文时，下一次展开直接复用 TimelineStore。 */
  const revealFullQuestion = async (): Promise<void> => {
    if (fullText !== undefined) {
      setShowFullQuestion((visible) => !visible);
      return;
    }
    try {
      await loadFullText();
      setShowFullQuestion(true);
    } catch {
      // 错误状态由 TimelineStore 投影，按钮会变为显式重试入口。
    }
  };

  /** 编辑被截断的用户输入前先补齐原文，避免用预览内容替换持久问题。 */
  const editQuestion = async (): Promise<void> => {
    if (onEditQuestion === undefined) return;
    try {
      const text = isTruncated ? await loadFullText() : (fullText ?? item.text ?? "");
      onEditQuestion({
        ...item,
        text,
        bodyTruncated: false,
        bodyTotalCharacters: Array.from(text).length,
        bodyHydration: {
          status: "complete",
          text,
          offsetCharacters: Array.from(text).length,
          totalCharacters: Array.from(text).length,
        },
      });
    } catch {
      // 编辑入口不丢失；失败正文留在当前位置，用户可先重试全文读取。
    }
  };
  return (
    <article
      aria-label="用户问题"
      className={cn(
        "ja-chat-message",
        `ja-chat-message-${item.kind}`,
        `ja-chat-message-${item.status}`,
        "ja-chat-message-user",
        !hasMessageBody && "ja-chat-message-user--attachments-only",
        isPending && "ja-chat-message-new",
      )}
      data-item-id={item.itemId}
      data-role="user"
      tabIndex={hasContextActions ? -1 : undefined}
      aria-keyshortcuts={hasContextActions ? "ContextMenu Shift+F10" : undefined}
      onContextMenu={hasContextActions ? onContextMenu : undefined}
      onKeyDown={hasContextActions ? onContextMenuKeyDown : undefined}
    >
      <AttachmentHistory
        items={item.attachments ?? []}
        threadId={item.threadId}
        authorization={attachmentAuthorization ?? { kind: "thread", threadId: item.threadId }}
        thumbnailUrls={attachmentThumbnailUrls}
        thumbnailPort={attachmentThumbnailPort}
        onOpenPreview={onOpenAttachmentPreview}
      />
      {hasMessageBody ? (
        <div className="ja-chat-message__body">
          <ComposerContextChips references={contextReferences} compact label="消息引用" />
          {messageText !== "" ? (
            <MarkdownMessage
              content={messageText}
              onOpenLink={onOpenLink}
              onOpenFile={onOpenFile}
              onCopyText={onCopyText}
            />
          ) : null}
          {isTruncated && onReadMessageContent !== undefined ? (
            <div className="ja-public-text-actions">
              <button
                type="button"
                className="ja-chat-response__full-action"
                aria-label={fullTextLoading ? "正在读取完整问题" : undefined}
                disabled={fullTextLoading}
                onClick={() => void revealFullQuestion()}
              >
                {fullTextLoading
                  ? "正在读取全文…"
                  : fullTextError
                    ? "重试读取全文"
                    : showFullQuestion && fullText !== undefined
                      ? "收起完整问题"
                      : "查看完整问题"}
              </button>
              {fullTextError ? (
                <p className="ja-chat-response__full-error" role="alert">
                  完整问题暂时无法读取。
                </p>
              ) : null}
            </div>
          ) : null}
        </div>
      ) : null}
      {submissionError === undefined ? null : (
        <p className="ja-chat-message__send-error" role="alert">
          {submissionError}
        </p>
      )}
      {((item.text?.trim() || (isTruncated && onReadMessageContent !== undefined)) &&
        onCopyText !== undefined) ||
      canEdit ? (
        <div className="ja-chat-message__actions" role="group" aria-label="用户消息操作">
          {(item.text?.trim() || (isTruncated && onReadMessageContent !== undefined)) &&
          onCopyText !== undefined ? (
            <CopyTextButton
              text={isTruncated ? () => loadFullText() : (fullText ?? item.text ?? "")}
              label="复制消息"
              onCopyText={onCopyText}
            />
          ) : null}
          {canEdit && onEditQuestion !== undefined ? (
            <button
              type="button"
              className="ja-copy-text-button"
              aria-label="编辑问题"
              title="编辑问题"
              onClick={(event) => {
                event.stopPropagation();
                void editQuestion();
              }}
            >
              <Pencil aria-hidden="true" />
            </button>
          ) : null}
        </div>
      ) : null}
    </article>
  );
}

/**
 * 渲染跨会话投递的纯文本事实；来源标题与 Thread ID 始终同时可见，且不使用用户气泡、Markdown
 * 或工作状态语义，避免消息在视觉和语义上冒充当前用户或当前 Agent 的发言；右键复制沿用其规范化正文。
 */
function ThreadMessage({
  item,
  onCopyText,
  onReadMessageContent,
  onContextMenu,
  onContextMenuKeyDown,
}: {
  item: TimelineItemAdapter;
  onCopyText?: (text: string) => Promise<void>;
  onReadMessageContent?: (threadId: string, itemId: string) => Promise<string>;
  onContextMenu?: (event: MouseEvent<HTMLElement>) => void;
  onContextMenuKeyDown?: (event: KeyboardEvent<HTMLElement>) => void;
}): ReactElement {
  const sourceTitle = item.sourceTitle ?? "未知会话";
  const sourceThreadId = item.sourceThreadId ?? "未知 Thread";
  const isTruncated = item.bodyTruncated === true;
  const {
    text: fullText,
    loading: fullTextLoading,
    error: fullTextError,
    load: loadFullText,
  } = useFullPublicText(
    item,
    onReadMessageContent === undefined
      ? undefined
      : () => onReadMessageContent(item.threadId, item.itemId),
  );
  const [showFullMessage, setShowFullMessage] = useState(false);
  const content = showFullMessage && fullText !== undefined ? fullText : (item.text ?? "");

  /** 跨会话正文使用其当前 Thread/Item 身份分页；来源 Thread 只作为展示元数据。 */
  const revealFullMessage = async (): Promise<void> => {
    if (fullText !== undefined) {
      setShowFullMessage((visible) => !visible);
      return;
    }
    try {
      await loadFullText();
      setShowFullMessage(true);
    } catch {
      // 错误状态由 TimelineStore 投影，按钮会变为显式重试入口。
    }
  };

  const hasContextMenu = !isTruncated && content.trim() !== "" && onCopyText !== undefined;
  return (
    <article
      aria-label={`来自会话：${sourceTitle}`}
      className={cn("ja-chat-message", "ja-chat-message-thread_message")}
      data-item-id={item.itemId}
      data-role="thread-message"
      data-source-thread-id={sourceThreadId}
      tabIndex={hasContextMenu ? -1 : undefined}
      aria-keyshortcuts={hasContextMenu ? "ContextMenu Shift+F10" : undefined}
      onContextMenu={hasContextMenu ? onContextMenu : undefined}
      onKeyDown={hasContextMenu ? onContextMenuKeyDown : undefined}
    >
      <div className="ja-thread-message__body">
        <header className="ja-thread-message__source">
          <span>来自会话</span>
          <strong title={sourceThreadId}>{sourceTitle}</strong>
        </header>
        <p className="ja-thread-message__content">{content}</p>
        {isTruncated && onReadMessageContent !== undefined ? (
          <div className="ja-public-text-actions">
            <button
              type="button"
              className="ja-chat-response__full-action"
              disabled={fullTextLoading}
              onClick={() => void revealFullMessage()}
            >
              {fullTextLoading
                ? "正在读取全文…"
                : fullTextError
                  ? "重试读取全文"
                  : showFullMessage && fullText !== undefined
                    ? "收起完整消息"
                    : "查看完整消息"}
            </button>
            {fullTextError ? (
              <p className="ja-chat-response__full-error" role="alert">
                完整消息暂时无法读取。
              </p>
            ) : null}
          </div>
        ) : null}
      </div>
      {onCopyText === undefined ? null : (
        <div className="ja-chat-message__actions" role="group" aria-label="会话消息操作">
          <CopyTextButton
            text={isTruncated ? () => loadFullText() : (fullText ?? content)}
            label="复制会话消息"
            onCopyText={onCopyText}
          />
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
  // Inherited turns are frozen display history; the source may still be running after the selected answer.
  if (item?.origin === "inherited" || turn?.origin === "inherited") {
    if (item?.status === "failed") return "failed";
    if (item?.status === "cancelled") return "cancelled";
    return "completed";
  }
  if (turn?.status === "waiting_input") return "waiting_input";
  if (turn?.status === "waiting_approval") return "waiting";
  if (turn?.status === "suspended") return "suspended";
  if (turn?.status === "failed" || item?.status === "failed") return "failed";
  if (turn?.status === "cancelled" || item?.status === "cancelled") return "cancelled";
  // Turn 仍在运行时只表达 working/streaming；终态语义必须来自权威 Turn，而不是 Item 的局部状态。
  if (turn?.status === "running" || turn?.status === "queued")
    return item?.text?.trim() ? "streaming" : "working";
  if (turn?.status === "completed" || (turn === undefined && item?.status === "completed"))
    return "completed";
  if (item?.text?.trim()) return "streaming";
  return "working";
}

/**
 * 仅当服务端已确认本轮 completed 且最终答复已到位，才自动收起过程；失败和取消也使用相同归档
 * 入口但默认展开，保留已生成正文与诊断。局部 Tool 完成、短暂断流、缺失 Turn 或只有已完成的过程片段
 * 都保持直出，避免用户在唯一可读正文尚未落点前看到空折叠栏。
 */
function workProcessDisplayMode(row: TimelineRow): WorkProcessDisplayMode | undefined {
  if (row.turn?.origin === "inherited") return "archived";
  const finalReady = row.final?.final === true && row.final.text?.trim() !== "";
  if (row.turn?.status === "completed" && finalReady) return "archived";
  if (row.turn?.status === "failed" || row.turn?.status === "cancelled") return "archived";
  if (
    row.turn === undefined ||
    row.turn.status === "queued" ||
    row.turn.status === "running" ||
    row.turn.status === "waiting_input" ||
    row.turn.status === "waiting_approval" ||
    row.turn.status === "suspended" ||
    row.turn.status === "completed"
  ) {
    return "inline";
  }
  return undefined;
}

/**
 * 从 Turn 接纳到终态始终复用同一个 Article；阶段提示与 Markdown 分层，使屏幕阅读器只播报阶段变化。
 * 失败时隐藏持久化的系统收口正文，只留下由稳定错误码派生的一条原因；真实已生成内容仍照常保留。
 * 临时重试次数并入正在工作状态，减少一条独立过程行，同时保持原有工作动效与停止入口。
 * 正文可复制时暴露同一上下文菜单；只把文章当前呈现的最终文本用于复制。
 */
function AssistantResponse({
  turn,
  item,
  retryStatus,
  onOpenLink,
  onOpenFile,
  onCopyText,
  forkAnchor,
  forkPending = false,
  forkCreated = false,
  forkOpenFailed = false,
  forkConflict = false,
  forkUnconfirmed = false,
  onForkReply,
  onReadFullText,
  onContextMenu,
  onContextMenuKeyDown,
}: {
  turn?: TimelineTurn;
  item?: TimelineItemAdapter;
  retryStatus?: string;
  onOpenLink?: (url: string, source?: HTMLElement) => void | Promise<void>;
  onOpenFile?: (target: MarkdownFileTarget, source: HTMLElement) => void | Promise<void>;
  onCopyText?: (text: string) => Promise<void>;
  forkAnchor?: { threadId: string; itemId: string };
  forkPending?: boolean;
  forkCreated?: boolean;
  forkOpenFailed?: boolean;
  forkConflict?: boolean;
  forkUnconfirmed?: boolean;
  onForkReply?: ChatTimelineProps["onForkReply"];
  onReadFullText?: () => Promise<string>;
  onContextMenu?: (event: MouseEvent<HTMLElement>) => void;
  onContextMenuKeyDown?: (event: KeyboardEvent<HTMLElement>) => void;
}): ReactElement {
  const state = assistantResponseState(turn, item);
  const isActive =
    state === "working" ||
    state === "waiting" ||
    state === "waiting_input" ||
    state === "streaming";
  const isFailed = state === "failed";
  const isCancelled = state === "cancelled";
  const workingStatus = retryStatus === undefined ? "正在工作" : `正在工作 · ${retryStatus}`;
  const statusText =
    state === "working"
      ? workingStatus
      : state === "waiting"
        ? "等待你的确认"
        : state === "waiting_input"
          ? "等待你的输入"
          : state === "suspended"
            ? "已暂停"
            : state === "streaming"
              ? workingStatus
              : state === "cancelled"
                ? "已取消"
                : undefined;
  const isFailureReply = isFailed && item?.metadata?.failureReply === true;
  const text = isFailureReply || !item?.text?.trim() ? undefined : item.text;
  const {
    text: fullText,
    loading: fullTextLoading,
    error: fullTextError,
    load: loadFullText,
  } = useFullPublicText(item, onReadFullText);
  const [exportFeedback, setExportFeedback] = useState<{
    itemId: string;
    phase: "saving" | "saved" | "unsupported" | "error";
  }>();
  const exportPhase =
    exportFeedback !== undefined && exportFeedback.itemId === item?.itemId
      ? exportFeedback.phase
      : undefined;
  /** 导出和复制共用同一按需全文读取，浏览器保存面板取消时不显示失败。 */
  const exportFullText = useCallback(async (): Promise<void> => {
    const itemId = item?.itemId;
    if (itemId === undefined) return;
    setExportFeedback({ itemId, phase: "saving" });
    try {
      const outcome = await exportPublicText(await loadFullText(), "Ja-回复.txt");
      setExportFeedback(
        outcome === "cancelled"
          ? undefined
          : { itemId, phase: outcome === "saved" ? "saved" : "unsupported" },
      );
    } catch {
      setExportFeedback({ itemId, phase: "error" });
    }
  }, [item?.itemId, loadFullText]);
  const longAnswer =
    state === "completed" &&
    text !== undefined &&
    (item?.bodyTruncated === true || text.length >= LONG_ANSWER_PREVIEW_THRESHOLD);
  const visibleText = fullText ?? text;
  const plainLongText = visibleText !== undefined && visibleText.length >= 65_535;
  const failure = isFailed ? turnFailurePresentation(turn?.error) : undefined;
  const isFinalAnswer = state === "completed" && text !== undefined;
  const canForkReply = forkAnchor !== undefined && onForkReply !== undefined;
  const articleLabel = isFinalAnswer
    ? "最终答复"
    : isFailed
      ? "失败说明"
      : isCancelled
        ? "已取消的回复"
        : "回复状态";

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
      data-fork-source-item-id={canForkReply ? forkAnchor.itemId : undefined}
      tabIndex={visibleText !== undefined && onCopyText !== undefined ? -1 : undefined}
      aria-keyshortcuts={
        visibleText !== undefined && onCopyText !== undefined && !longAnswer
          ? "ContextMenu Shift+F10"
          : undefined
      }
      onContextMenu={visibleText !== undefined && !longAnswer ? onContextMenu : undefined}
      onKeyDown={visibleText !== undefined && !longAnswer ? onContextMenuKeyDown : undefined}
    >
      <div className="ja-chat-message__body">
        {visibleText === undefined ? null : (
          <div className="ja-chat-response__content" aria-live="off">
            {plainLongText ? (
              <pre className="ja-chat-response__long-text">{visibleText}</pre>
            ) : (
              <MarkdownMessage
                content={visibleText}
                onOpenLink={onOpenLink}
                onOpenFile={onOpenFile}
                onCopyText={onCopyText}
              />
            )}
          </div>
        )}
        {longAnswer && onReadFullText !== undefined ? (
          <div className="ja-public-text-actions">
            {fullText === undefined ? (
              <button
                type="button"
                className="ja-chat-response__full-action"
                disabled={fullTextLoading}
                onClick={() => void loadFullText().catch(() => {})}
              >
                {fullTextLoading
                  ? "正在读取全文…"
                  : fullTextError
                    ? "重试读取全文"
                    : "查看完整回复"}
              </button>
            ) : null}
            <button
              type="button"
              className="ja-chat-response__full-action"
              disabled={exportPhase === "saving"}
              onClick={() => void exportFullText()}
            >
              {exportPhase === "saving" ? "正在导出…" : "导出全文"}
            </button>
          </div>
        ) : null}
        {exportPhase === "saved" ? (
          <p className="ja-chat-response__full-note" role="status">
            已导出全文。
          </p>
        ) : null}
        {exportPhase === "unsupported" ? (
          <p className="ja-chat-response__full-error" role="alert">
            当前窗口不支持直接导出，可复制全文。
          </p>
        ) : null}
        {exportPhase === "error" ? (
          <p className="ja-chat-response__full-error" role="alert">
            导出未完成，请重试。
          </p>
        ) : null}
        {fullTextError ? (
          <p className="ja-chat-response__full-error" role="alert">
            完整回复暂时无法读取。
          </p>
        ) : null}
        {failure === undefined ? null : (
          <p className="ja-chat-failure" role="alert">
            {failure}
          </p>
        )}
        {failure !== undefined || statusText === undefined ? null : (
          <div
            className="ja-chat-response__status"
            aria-live="polite"
            aria-atomic="true"
            data-retry-status={retryStatus === undefined ? undefined : "true"}
          >
            <span>{statusText}</span>
            {state === "working" || state === "streaming" ? <ActivityDots /> : null}
          </div>
        )}
      </div>
      {(visibleText !== undefined && onCopyText !== undefined) || canForkReply ? (
        <div
          className="ja-chat-message__actions"
          role="group"
          aria-label={isFailed ? "未完成回复操作" : "回复操作"}
        >
          {visibleText !== undefined && onCopyText !== undefined ? (
            <CopyTextButton
              text={visibleText}
              label={isFailed ? "复制未完成内容" : "复制回复"}
              onCopyText={
                longAnswer && onReadFullText !== undefined
                  ? async () => onCopyText(await loadFullText())
                  : onCopyText
              }
            />
          ) : null}
          {canForkReply ? (
            <ForkReplyButton
              anchor={forkAnchor}
              pending={forkPending}
              created={forkCreated}
              openFailed={forkOpenFailed}
              conflict={forkConflict}
              unconfirmed={forkUnconfirmed}
              onForkReply={onForkReply}
            />
          ) : null}
        </div>
      ) : null}
    </article>
  );
}

/**
 * 对长对话做 Virtualization，同时保留 Turn 级分组、本地 Stream 更新以及桌面端共用的
 * Disclosure/Approval 组件。顶部旧页读取入口在请求期间以原位圆圈表达状态，避免加载文字插入时间线造成跳动；
 * 隐藏 continuation 先归并到源问题，过程时长合计各次尝试而不计等待继续的间隔。
 * 修改卡共享 Thread/Turn 展开缓存，使独立虚拟行的卸载不改变用户阅读选择。
 */
export function ChatTimeline({
  threadId,
  hasOlderHistory = false,
  loadingOlderHistory = false,
  olderHistoryError,
  onLoadOlderHistory,
  scrollCache,
  disclosureCache,
  items,
  skills = [],
  turns = [],
  onForkReply,
  forkingReplies = [],
  forkCreatedReplies = [],
  forkOpenFailures = [],
  forkConflictReplies = [],
  forkUnconfirmedReplies = [],
  approvals = [],
  approvalDecisions = {},
  approvalClosedAt = {},
  localSubmissions = [],
  onApprovalDecision,
  onOpenLink,
  onOpenFile,
  onCopyText,
  onReadMessageContent,
  onReadAnswerContent,
  onReadToolArtifact,
  onResolveToolRecovery,
  processSession,
  onReviewTurn,
  onOpenAttachmentPreview,
  editableSourceMessageId,
  onEditQuestion,
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
  const [messageContextMenu, setMessageContextMenu] = useState<MessageContextMenuSession>();
  const messageContextMenuKey = useRef(0);
  /** 重新从当前投影解析菜单目标，避免虚拟化或异步更新后对旧 Item 执行操作。 */
  const resolveMessageContextTarget = useCallback(
    (itemId: string, role: MessageMenuRole) => {
      const item = items.find((candidate) => candidate.itemId === itemId);
      if (item === undefined) return undefined;
      const row = rows.find(
        (candidate) =>
          candidate.user?.itemId === itemId ||
          candidate.threadMessages.some((message) => message.itemId === itemId) ||
          candidate.final?.itemId === itemId,
      );
      const actions = messageMenuActions(
        item,
        row,
        role,
        editableSourceMessageId,
        onCopyText,
        item.origin === "native" ? onEditQuestion : undefined,
      );
      return actions === undefined ? undefined : { item, actions };
    },
    [editableSourceMessageId, items, onCopyText, onEditQuestion, rows],
  );
  /** 坐标与稳定 Item ID 共同构成一次菜单会话，重复右击会重建 Radix 定位锚点。 */
  const openMessageContextMenu = useCallback(
    (
      messageElement: HTMLElement,
      x: number,
      y: number,
      focusTarget: HTMLElement = messageElement,
    ): boolean => {
      const itemId = messageElement.getAttribute("data-item-id") ?? undefined;
      const role = messageElement.getAttribute("data-role") ?? undefined;
      if (itemId === undefined || !isMessageMenuRole(role)) return false;
      if (resolveMessageContextTarget(itemId, role) === undefined) return false;
      messageContextMenuKey.current += 1;
      setMessageContextMenu({
        key: messageContextMenuKey.current,
        x,
        y,
        itemId,
        role,
        opener: focusTarget,
      });
      return true;
    },
    [resolveMessageContextTarget],
  );
  /** 选区、Markdown 目标及已有按钮保留浏览器/WebView 的原始目标语义。 */
  const handleMessageContextMenu = useCallback(
    (event: MouseEvent<HTMLElement>): void => {
      if (shouldPreserveNativeMessageContextMenu(event.target)) return;
      if (openMessageContextMenu(event.currentTarget, event.clientX, event.clientY)) {
        event.preventDefault();
        event.stopPropagation();
      }
    },
    [openMessageContextMenu],
  );
  /** ContextMenu 与 Shift+F10 以当前消息边缘定位，避免键盘用户依赖鼠标坐标。 */
  const handleMessageContextMenuKeyDown = useCallback(
    (event: KeyboardEvent<HTMLElement>): void => {
      if (event.key !== "ContextMenu" && !(event.key === "F10" && event.shiftKey)) return;
      const bounds = event.currentTarget.getBoundingClientRect();
      const focusTarget =
        event.target instanceof HTMLElement && event.currentTarget.contains(event.target)
          ? event.target
          : event.currentTarget;
      if (openMessageContextMenu(event.currentTarget, bounds.left, bounds.bottom, focusTarget)) {
        event.preventDefault();
        event.stopPropagation();
      }
    },
    [openMessageContextMenu],
  );
  const scrollRef = useRef<HTMLDivElement>(null);
  /** 设计原因：旧页插入到视口上方时补偿高度差，保持用户正在阅读的行和输入焦点。 */
  const loadOlderHistory = useCallback(async (): Promise<void> => {
    if (onLoadOlderHistory === undefined || loadingOlderHistory) return;
    const scroller = scrollRef.current;
    const beforeHeight = scroller?.scrollHeight ?? 0;
    const beforeTop = scroller?.scrollTop ?? 0;
    await onLoadOlderHistory();
    requestAnimationFrame(() => {
      const current = scrollRef.current;
      if (current !== null) current.scrollTop = beforeTop + current.scrollHeight - beforeHeight;
    });
  }, [loadingOlderHistory, onLoadOlderHistory]);
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
    // TanStack 默认 debounce 不能取消；dispose 后忽略迟到的滚动 offset 通知。
    observeElementOffset: observeElementOffsetWhileActive,
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
      if (orderedRows.length === 0) return;
      /**
       * 文本 delta 会让 revision 高频变化；已经贴住尾部时不重复调用 Virtualizer 的命令式
       * scrollToIndex，避免 WebView2 在每个片段上重新测量并产生可见布局抖动。内容高度真正增长
       * 后 distance 会再次为正，仍会在下一帧追到最新位置；首次布局尚未测量时保留原调用。
       */
      const element = scrollRef.current;
      if (
        element !== null &&
        element.clientHeight > 0 &&
        element.scrollHeight > element.clientHeight + 1 &&
        element.scrollHeight - element.scrollTop - element.clientHeight <= 1
      )
        return;
      virtualizer.scrollToIndex(orderedRows.length - 1, { align: "end" });
    },
    [orderedRows.length, virtualizer],
  );
  const indexForKey = useCallback(
    (key: TimelineScrollKey): number =>
      orderedRows.findIndex((entry, index) => orderedRowKey(entry, index) === key),
    [orderedRows],
  );
  const timelineScroll = useTimelineScroll({
    cache: scrollCache,
    threadId,
    rowCount: orderedRows.length,
    revision,
    scrollRef,
    virtualizer,
    indexForKey,
    scrollToLatest,
  });
  const followingLatest = timelineScroll.followingLatest;
  const isFollowingLatest = timelineScroll.isFollowingLatest;
  /**
   * 测量回调可能在 Wheel 事件与 React re-render 之间执行；即时读取 Hook ref，确保上滚阅读期间
   * 连完全位于 viewport 上方的行也不触发自动 scrollTop 补偿。
   */
  const shouldAdjustForTimelineReading = useCallback(
    (
      item: Parameters<typeof shouldAdjustTimelineScrollPosition>[0],
      delta: number,
      instance: Parameters<typeof shouldAdjustTimelineScrollPosition>[2],
    ) => shouldAdjustTimelineScrollPosition(item, delta, instance, isFollowingLatest()),
    [isFollowingLatest],
  );
  useLayoutEffect(() => {
    virtualizer.shouldAdjustScrollPositionOnItemSizeChange = shouldAdjustForTimelineReading;
    return () => {
      if (
        virtualizer.shouldAdjustScrollPositionOnItemSizeChange === shouldAdjustForTimelineReading
      ) {
        virtualizer.shouldAdjustScrollPositionOnItemSizeChange = undefined;
      }
    };
  }, [shouldAdjustForTimelineReading, virtualizer]);
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
  /** 虚拟行离开 overscan 后立即丢弃菜单，避免焦点恢复或操作落到已卸载的消息节点。 */
  useLayoutEffect(() => {
    if (messageContextMenu === undefined || messageContextMenu.opener.isConnected) return;
    setMessageContextMenu((current) =>
      current?.key === messageContextMenu.key ? undefined : current,
    );
  }, [messageContextMenu, visibleRows]);
  const activeMessageMenuTarget =
    messageContextMenu === undefined
      ? undefined
      : resolveMessageContextTarget(messageContextMenu.itemId, messageContextMenu.role);

  /** 将用户带回 Live Tail，但不修改规范化 Timeline 投影。 */
  const handleScrollToLatest = (): void => {
    timelineScroll.scrollToLatest();
  };

  return (
    <section
      className={cn("ja-chat-timeline", className)}
      aria-label="对话时间线"
      data-thread-id={threadId}
    >
      {(hasOlderHistory || olderHistoryError !== undefined) && (
        <div
          className="ja-conversation-content-rail"
          style={{ textAlign: "center", padding: "0.35rem 0" }}
        >
          {hasOlderHistory && onLoadOlderHistory !== undefined && (
            <button
              className="ja-button ja-button-sm ja-button-ghost"
              type="button"
              aria-label={loadingOlderHistory ? "正在加载…" : "加载更早的记录"}
              aria-busy={loadingOlderHistory || undefined}
              disabled={loadingOlderHistory}
              onClick={() => void loadOlderHistory()}
            >
              {loadingOlderHistory ? (
                <LoaderCircle
                  className="ja-chat-timeline__history-spinner"
                  aria-hidden="true"
                  focusable="false"
                />
              ) : (
                "加载更早的记录"
              )}
            </button>
          )}
          {olderHistoryError !== undefined && <span role="status">{olderHistoryError}</span>}
        </div>
      )}
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
            const workDisplayMode = workProcessDisplayMode(row);
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
                data-response-turn-id={row.turn?.turnId}
                data-source-message-id={row.user?.itemId ?? row.turn?.sourceMessageId ?? undefined}
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
                          onOpenFile={onOpenFile}
                          onCopyText={onCopyText}
                          onReadMessageContent={onReadMessageContent}
                          onOpenAttachmentPreview={onOpenAttachmentPreview}
                          canEdit={
                            messageMenuActions(
                              row.user,
                              row,
                              "user",
                              editableSourceMessageId,
                              onCopyText,
                              row.user.origin === "native" ? onEditQuestion : undefined,
                            )?.canEdit ?? false
                          }
                          onEditQuestion={onEditQuestion}
                          onContextMenu={handleMessageContextMenu}
                          onContextMenuKeyDown={handleMessageContextMenuKeyDown}
                        />
                      );
                    case "thread_message":
                      return (
                        <ThreadMessage
                          key={block.key}
                          item={block.item}
                          onCopyText={onCopyText}
                          onReadMessageContent={onReadMessageContent}
                          onContextMenu={handleMessageContextMenu}
                          onContextMenuKeyDown={handleMessageContextMenuKeyDown}
                        />
                      );
                    case "work":
                      return (
                        <WorkProcess
                          key={block.key}
                          disclosureCache={disclosureCache}
                          disclosureKey={row.key}
                          disclosureThreadId={
                            threadId ??
                            row.turn?.threadId ??
                            row.user?.threadId ??
                            row.work[0]?.threadId
                          }
                          steps={row.work.filter((item) => !isAssistantRetryStatus(item))}
                          turn={
                            row.turn?.origin === "inherited"
                              ? { ...row.turn, status: "completed" }
                              : row.turn
                          }
                          displayMode={workDisplayMode}
                          relatedTurns={
                            row.turn === undefined
                              ? undefined
                              : turns.filter(
                                  (candidate) =>
                                    candidate.turnId === row.turnId ||
                                    candidate.turnId === row.turn?.turnId ||
                                    (row.user !== undefined &&
                                      candidate.sourceMessageId === row.user.itemId),
                                )
                          }
                          autoCollapse={followingLatest}
                          approvals={row.approvals.filter((approval) =>
                            turns.some(
                              (candidate) =>
                                candidate.turnId === approval.turnId &&
                                candidate.origin === "native",
                            ),
                          )}
                          approvalDecisions={approvalDecisions}
                          approvalClosedAt={approvalClosedAt}
                          onApprovalDecision={onApprovalDecision}
                          onOpenLink={onOpenLink}
                          onOpenFile={onOpenFile}
                          onCopyText={onCopyText}
                          onReadMessageContent={onReadMessageContent}
                          onReadToolArtifact={onReadToolArtifact}
                          onResolveToolRecovery={onResolveToolRecovery}
                          onOpenAttachmentPreview={onOpenAttachmentPreview}
                          processSession={processSession}
                        />
                      );
                    case "assistant":
                      return (
                        <AssistantResponse
                          key={block.key}
                          turn={row.turn}
                          item={row.final}
                          retryStatus={row.work.find(isAssistantRetryStatus)?.text}
                          onOpenLink={onOpenLink}
                          onOpenFile={onOpenFile}
                          onCopyText={onCopyText}
                          forkAnchor={row.forkAnchor}
                          forkPending={forkingReplies.some(
                            (identity) =>
                              identity.threadId === row.forkAnchor?.threadId &&
                              identity.itemId === row.forkAnchor?.itemId,
                          )}
                          forkCreated={forkCreatedReplies.some(
                            (identity) =>
                              identity.threadId === row.forkAnchor?.threadId &&
                              identity.itemId === row.forkAnchor?.itemId,
                          )}
                          forkOpenFailed={forkOpenFailures.some(
                            (identity) =>
                              identity.threadId === row.forkAnchor?.threadId &&
                              identity.itemId === row.forkAnchor?.itemId,
                          )}
                          forkConflict={forkConflictReplies.some(
                            (identity) =>
                              identity.threadId === row.forkAnchor?.threadId &&
                              identity.itemId === row.forkAnchor?.itemId,
                          )}
                          forkUnconfirmed={forkUnconfirmedReplies.some(
                            (identity) =>
                              identity.threadId === row.forkAnchor?.threadId &&
                              identity.itemId === row.forkAnchor?.itemId,
                          )}
                          onForkReply={onForkReply}
                          onReadFullText={
                            row.final === undefined
                              ? undefined
                              : onReadAnswerContent !== undefined
                                ? () =>
                                    onReadAnswerContent(
                                      row.final!.itemId,
                                      row.turn?.threadRevision,
                                      row.final!.turnId,
                                    )
                                : onReadMessageContent === undefined
                                  ? undefined
                                  : () =>
                                      readCompleteAnswer(
                                        row.final!,
                                        row.work,
                                        (targetThreadId, itemId) =>
                                          onReadMessageContent(targetThreadId, itemId),
                                      )
                          }
                          onContextMenu={handleMessageContextMenu}
                          onContextMenuKeyDown={handleMessageContextMenuKeyDown}
                        />
                      );
                    case "changes":
                      return row.turn === undefined ||
                        row.turn.origin !== "native" ||
                        visibleChangeSet === undefined ? null : (
                        <TurnChangesCard
                          key={block.key}
                          turn={row.turn}
                          changeSet={visibleChangeSet}
                          disclosureCache={disclosureCache}
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
      {messageContextMenu === undefined || activeMessageMenuTarget === undefined ? null : (
        <PointerContextMenu
          key={messageContextMenu.key}
          x={messageContextMenu.x}
          y={messageContextMenu.y}
          label="消息操作"
          onOpenChange={(open) => {
            if (!open) {
              setMessageContextMenu((current) =>
                current?.key === messageContextMenu.key ? undefined : current,
              );
            }
          }}
          onRestoreFocus={() => {
            if (messageContextMenu.opener.isConnected) messageContextMenu.opener.focus();
          }}
          className="ja-chat-message-context-menu"
        >
          {activeMessageMenuTarget.actions.copyText === undefined ? null : (
            <MenuItem
              onSelect={() => {
                const current = resolveMessageContextTarget(
                  messageContextMenu.itemId,
                  messageContextMenu.role,
                );
                const copyText = current?.actions.copyText;
                if (copyText === undefined || onCopyText === undefined) return;
                void Promise.resolve()
                  .then(() => onCopyText(copyText))
                  .catch(() => {});
              }}
            >
              <Copy aria-hidden="true" />
              <span>复制正文</span>
            </MenuItem>
          )}
          {activeMessageMenuTarget.actions.copyText !== undefined &&
          activeMessageMenuTarget.actions.canEdit ? (
            <MenuSeparator />
          ) : null}
          {!activeMessageMenuTarget.actions.canEdit || onEditQuestion === undefined ? null : (
            <MenuItem
              onSelect={() => {
                const current = resolveMessageContextTarget(
                  messageContextMenu.itemId,
                  messageContextMenu.role,
                );
                if (current?.actions.canEdit) onEditQuestion(current.item);
              }}
            >
              <Pencil aria-hidden="true" />
              <span>编辑问题</span>
            </MenuItem>
          )}
        </PointerContextMenu>
      )}
    </section>
  );
}
