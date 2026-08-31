// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useEffect, useMemo, useRef, useState, type ReactElement, type RefObject } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { ArrowDown, Paperclip } from "lucide-react";
import { cn } from "@/shared/ui/primitives/cn";
import type { UserApprovalDecision } from "../approval/ApprovalCard";
import type {
  ApprovalDecision,
  TimelineApproval as ApprovalSummary,
  TimelineTurn,
} from "../../domain/timelineTypes";
import { itemRevision, type TimelineItemAdapter } from "../../domain/timelineTypes";
import { MarkdownMessage } from "./MarkdownMessage";
import { WorkProcess } from "./WorkProcess";
import { TurnChangesCard } from "./TurnChangesCard";
import { CopyTextButton } from "@/shared/ui/CopyTextButton";
import "./timeline.css";

export interface ChatTimelineProps {
  /** Item 必须直接来自规范化 Reducer 投影，视图不创建第二份业务状态。 */
  items: readonly TimelineItemAdapter[];
  /** Turn 只用于紧凑的当前工作状态，绝不能充当第二个 Store。 */
  turns?: readonly TimelineTurn[];
  /** Approval 按 Turn 与 callId 关联 Prepared Tool，不能使用已删除的 Wire itemId。 */
  approvals?: readonly ApprovalSummary[];
  approvalDecisions?: Readonly<Record<string, ApprovalDecision | undefined>>;
  approvalClosedAt?: Readonly<Record<string, string | undefined>>;
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
  onReadTurnDiff?: (input: {
    threadId: string;
    turnId: string;
    artifactId: string;
  }) => Promise<string>;
  className?: string;
  emptyText?: string;
}

type TimelineRow = {
  kind: "turn";
  key: string;
  turnId: string;
  turn?: TimelineTurn;
  user?: TimelineItemAdapter;
  attachments: TimelineItemAdapter[];
  work: TimelineItemAdapter[];
  final?: TimelineItemAdapter;
  approvals: ApprovalSummary[];
};

type MutableTurnGroup = {
  turnId: string;
  turn?: TimelineTurn;
  user: TimelineItemAdapter[];
  attachments: TimelineItemAdapter[];
  work: TimelineItemAdapter[];
  final: TimelineItemAdapter[];
  approvals: ApprovalSummary[];
};

/**
 * 将规范化投影转换为每个 Turn 一行的虚拟列表。User Fragment、Work Event 与 Agent Fragment
 * 独立收集，公开回复 delta 与最终消息共用 Agent Fragment，确保终态切换不创建第二个气泡。
 */
function buildRows(
  items: readonly TimelineItemAdapter[],
  turns: readonly TimelineTurn[],
  approvals: readonly ApprovalSummary[],
): TimelineRow[] {
  const turnById = new Map(turns.map((turn) => [turn.turnId, turn]));
  const groups = new Map<string, MutableTurnGroup>();
  /** 行顺序绑定第一个持久 Event，而不是依赖 Object/Map 排序。 */
  const groupFor = (turnId: string) => {
    const existing = groups.get(turnId);
    if (existing !== undefined) {
      return existing;
    }
    const created: MutableTurnGroup = {
      turnId,
      turn: turnById.get(turnId),
      user: [],
      attachments: [],
      work: [],
      final: [],
      approvals: [],
    };
    groups.set(turnId, created);
    return created;
  };
  for (const item of items) {
    const group = groupFor(item.turnId);
    if (item.kind === "user_message") {
      group.user.push(item);
    } else if (item.kind === "agent_message") {
      group.final.push(item);
    } else if (item.metadata?.attachmentId !== undefined) {
      group.attachments.push(item);
    } else {
      group.work.push(item);
    }
  }
  for (const approval of approvals) {
    const group = groupFor(approval.turnId);
    if (!group.approvals.some((candidate) => candidate.approvalId === approval.approvalId)) {
      group.approvals.push(approval);
    }
  }
  return [...groups.values()].map((group) => ({
    kind: "turn" as const,
    key: group.turnId,
    turnId: group.turnId,
    turn: group.turn,
    user: mergeMessages(group.user, "user_message"),
    attachments: group.attachments,
    work: group.work,
    final: mergeMessages(group.final, "agent_message", true),
    approvals: group.approvals,
  }));
}

/**
 * 附件属于用户提交内容而不是 Agent 工作过程，因此历史视图始终展示紧凑文件行；只使用
 * App Server 已持久化的脱敏名称、媒体摘要和稳定 identity，不暴露本地路径或内容。
 */
function AttachmentHistory({
  items,
}: {
  items: readonly TimelineItemAdapter[];
}): ReactElement | null {
  if (items.length === 0) return null;
  return (
    <ul className="ja-chat-attachments" aria-label="附件">
      {items.map((item) => (
        <li
          className="ja-chat-attachment"
          key={item.itemId}
          data-attachment-id={item.metadata?.attachmentId}
        >
          <Paperclip aria-hidden="true" />
          <span className="ja-chat-attachment__name">{item.title ?? "附件"}</span>
          {item.summary?.trim() ? (
            <span className="ja-chat-attachment__summary">{item.summary}</span>
          ) : null}
        </li>
      ))}
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
 * 渲染单个 Role Block；消息操作位于正文之后的同级操作层，让复制在视觉上落到对应消息下方，
 * 同时不进入用户气泡或 Assistant 阅读内容。Role 继续只靠布局和材质区分，不重复展示身份标签。
 */
function MessageRow({
  item,
  role,
  onOpenLink,
  onCopyText,
}: {
  item: TimelineItemAdapter;
  role: "user" | "final";
  onOpenLink?: (url: string) => void | Promise<void>;
  onCopyText?: (text: string) => Promise<void>;
}): ReactElement {
  const isUserMessage = role === "user";
  const statusText =
    item.status === "in_progress"
      ? "正在回复"
      : item.status === "failed"
        ? "失败"
        : item.status === "cancelled"
          ? "已取消"
          : item.status === "completed"
            ? "完成"
            : "";
  return (
    <article
      aria-label={isUserMessage ? "用户问题" : "最终答复"}
      className={cn(
        "ja-chat-message",
        `ja-chat-message-${item.kind}`,
        `ja-chat-message-${item.status}`,
        isUserMessage ? "ja-chat-message-user" : "ja-chat-message-final",
      )}
      data-item-id={item.itemId}
      data-role={role}
      aria-busy={item.status === "in_progress" || undefined}
    >
      <div className="ja-chat-message__body">
        {item.status !== "completed" && statusText ? (
          <div
            className="ja-chat-message__meta"
            role={item.status === "in_progress" ? "status" : undefined}
            aria-live={item.status === "in_progress" ? "polite" : undefined}
          >
            <span>{statusText}</span>
          </div>
        ) : null}
        {item.text?.trim() ? (
          <MarkdownMessage content={item.text} onOpenLink={onOpenLink} onCopyText={onCopyText} />
        ) : (
          <span className="ja-chat-message__placeholder">正在准备回复…</span>
        )}
      </div>
      {item.text?.trim() && onCopyText !== undefined ? (
        <div
          className="ja-chat-message__actions"
          role="group"
          aria-label={isUserMessage ? "用户消息操作" : "回复操作"}
        >
          <CopyTextButton
            text={item.text}
            label={isUserMessage ? "复制消息" : "复制回复"}
            onCopyText={onCopyText}
          />
        </div>
      ) : null}
    </article>
  );
}

/**
 * 仅当用户已经跟随底部时让 Viewport 靠近最新 Stream Item；手动查看历史时绝不跳动。
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
    element.addEventListener("scroll", onScroll, { passive: true });
    onScroll();
    return () => element.removeEventListener("scroll", onScroll);
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
  turns = [],
  approvals = [],
  approvalDecisions = {},
  approvalClosedAt = {},
  onApprovalDecision,
  onOpenLink,
  onCopyText,
  onReadToolArtifact,
  onReadTurnDiff,
  className,
  emptyText = "发送一条消息后，工作过程会显示在这里。",
}: ChatTimelineProps): ReactElement {
  const rows = useMemo(() => buildRows(items, turns, approvals), [items, turns, approvals]);
  const scrollRef = useRef<HTMLDivElement>(null);
  const [followingLatest, setFollowingLatest] = useState(true);
  const revision = useMemo(
    () =>
      rows
        .map((row) =>
          [
            row.key,
            row.turn?.status ?? "",
            row.turn?.startedAt ?? "",
            row.turn?.completedAt ?? "",
            row.turn?.changeSet == null ? "" : JSON.stringify(row.turn.changeSet.stats),
            itemRevision(row.user),
            row.work.map(itemRevision).join(","),
            itemRevision(row.final),
            row.approvals.map((approval) => approval.approvalId).join(","),
          ].join("|"),
        )
        .join("||"),
    [rows],
  );
  // TanStack Virtual 暴露命令式实例；对其 Memoize 会让 React Compiler 保留陈旧 Scroll Function，
  // 因此该显式 Escape Hatch 只留在 Virtualization 边界内。
  // eslint-disable-next-line react-hooks/incompatible-library
  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => 280,
    overscan: 6,
    getItemKey: (index) => rows[index]?.key ?? index,
  });
  const scrollToLatest = useMemo(
    () => () => {
      if (rows.length > 0) {
        virtualizer.scrollToIndex(rows.length - 1, { align: "end" });
      }
    },
    [rows.length, virtualizer],
  );
  useFollowLatest(scrollRef, rows.length, revision, scrollToLatest, setFollowingLatest);
  const virtualRows = virtualizer.getVirtualItems();
  const visibleRows =
    virtualRows.length > 0
      ? virtualRows
      : rows.slice(0, 24).map((_, index) => ({
          index,
          key: rows[index]?.key ?? index,
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
      {rows.length === 0 ? <p className="ja-chat-timeline__empty">{emptyText}</p> : null}
      <div className="ja-chat-timeline__scroll" ref={scrollRef}>
        <div
          className="ja-chat-timeline__spacer ja-conversation-content-rail"
          style={{ height: virtualizer.getTotalSize() }}
        >
          {visibleRows.map((virtualRow) => {
            const row = rows[virtualRow.index];
            if (row === undefined) {
              return null;
            }
            const changeSet = row.turn?.changeSet;
            const visibleChangeSet =
              changeSet?.state === "available" && changeSet.stats.files > 0 ? changeSet : undefined;
            return (
              <div
                className="ja-chat-timeline__row"
                key={virtualRow.key}
                data-index={virtualRow.index}
                data-turn-id={row.turnId}
                ref={virtualizer.measureElement}
                style={{ transform: `translateY(${virtualRow.start}px)` }}
              >
                {row.user ? (
                  <MessageRow
                    item={row.user}
                    role="user"
                    onOpenLink={onOpenLink}
                    onCopyText={onCopyText}
                  />
                ) : null}
                <AttachmentHistory items={row.attachments} />
                <WorkProcess
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
                {row.final ? (
                  <MessageRow
                    item={row.final}
                    role="final"
                    onOpenLink={onOpenLink}
                    onCopyText={onCopyText}
                  />
                ) : null}
                {row.turn && row.turn.error ? (
                  <p className="ja-chat-timeline__turn-error" role="alert">
                    错误：{row.turn.error.code}
                    {row.turn.error.retryable ? "（可重试）" : ""}
                  </p>
                ) : null}
                {row.turn === undefined || visibleChangeSet === undefined ? null : (
                  <TurnChangesCard
                    turn={row.turn}
                    changeSet={visibleChangeSet}
                    onReadDiff={onReadTurnDiff}
                  />
                )}
              </div>
            );
          })}
        </div>
        {rows.length > 0 && !followingLatest ? (
          <button
            type="button"
            className="ja-chat-timeline__jump-latest"
            onClick={handleScrollToLatest}
          >
            <ArrowDown aria-hidden="true" />
            <span>回到最新</span>
          </button>
        ) : null}
      </div>
    </section>
  );
}
