// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { Bot, ChevronDown, CircleAlert, LoaderCircle, Play, X } from "lucide-react";
import { useCallback, useMemo, useState, type ReactElement } from "react";
import {
  ChatTimeline,
  Composer,
  type ComposerSubmit,
  type ComposerWorkspaceSearchResult,
  type ConversationAttachment,
  type ConversationAttachmentPort,
  type ConversationContextReference,
  type TimelineApproval,
  type TimelineItemAdapter,
  type TimelineTurn,
} from "@/features/conversation";
import { Button, ErrorState } from "@/shared/ui/primitives";
import type { WorkbenchTaskTab } from "@/features/workbench";
import type { TaskController } from "../application/useTaskController";
import { readyTaskAttachments, useTaskComposerDraft } from "../application/useTaskComposerDraft";
import type { TaskContentBlock, TaskSummary } from "../domain/taskModel";
import { propagatingTaskDescendantCount, taskStateLabel } from "../domain/taskModel";
import "./tasks.css";

interface TaskTimelineProjection {
  readonly items: TimelineItemAdapter[];
  readonly turns: TimelineTurn[];
  readonly approvals: TimelineApproval[];
  readonly approvalDecisions: Readonly<Record<string, "approve" | "deny" | undefined>>;
}

export interface TaskComposerSkillSuggestion {
  readonly skillId: string;
  readonly name: string;
  readonly description: string;
  readonly scope: "builtin" | "user" | "ja" | "project";
}

export interface TaskComposerEnvironment {
  readonly workspaceId: string;
  readonly runtimeGeneration?: number;
  readonly skills?: readonly TaskComposerSkillSuggestion[];
  readonly attachmentPort?: ConversationAttachmentPort;
  readonly onSearchWorkspacePaths?: (query: string) => Promise<ComposerWorkspaceSearchResult>;
  readonly onOpenAttachmentPreview?: (
    attachment: ConversationAttachment,
    source: HTMLButtonElement,
  ) => void;
  readonly onAttachmentRemoved?: (attachmentId: string) => void;
  readonly onAttachmentsBound?: (threadId: string, attachmentIds: readonly string[]) => void;
}

export interface TaskTranscriptActions {
  readonly onOpenLink?: (url: string) => void | Promise<void>;
  readonly onCopyText?: (text: string) => Promise<void>;
  readonly onReadToolArtifact?: (input: {
    threadId: string;
    turnId: string;
    callId: string;
    artifactId: string;
  }) => Promise<string>;
  readonly onOpenAttachmentPreview?: (
    attachment: {
      attachmentId: string;
      displayName: string;
      mediaKind: "image" | "text";
      threadId: string;
    },
    source: HTMLButtonElement,
  ) => void;
}

/** Snapshot 已过严格 Schema；此处只读取明确字符串字段，不为未知历史格式建立兼容通道。 */
function stringField(item: Record<string, unknown>, field: string): string | undefined {
  const value = item[field];
  return typeof value === "string" ? value : undefined;
}

/** Tool 状态映射到现有 Timeline 闭集，保留失败、取消与审批等待的可视语义。 */
function toolItemStatus(presentation: Record<string, unknown>): TimelineItemAdapter["status"] {
  switch (presentation["status"]) {
    case "pending":
      return "started";
    case "running":
    case "waiting_approval":
      return "in_progress";
    case "success":
      return "completed";
    case "error":
      return "failed";
    case "cancelled":
      return "cancelled";
    default:
      return "started";
  }
}

/** User content 只转换协议闭集中的正文与引用，Skill 展示元数据仍由当前 catalog 补齐。 */
function userItem(
  item: Record<string, unknown>,
  threadId: string,
  itemId: string,
  turnId: string,
): TimelineItemAdapter {
  const content = Array.isArray(item["content"]) ? (item["content"] as TaskContentBlock[]) : [];
  const text = content
    .filter((block): block is Extract<TaskContentBlock, { type: "text" }> => block.type === "text")
    .map((block) => block.text)
    .join("\n\n");
  const contextReferences = content.filter(
    (block): block is ConversationContextReference =>
      block.type === "workspace_reference" || block.type === "skill_reference",
  );
  const attachments = Array.isArray(item["attachments"])
    ? (item["attachments"] as NonNullable<TimelineItemAdapter["attachments"]>)
    : [];
  return {
    itemId,
    threadId,
    turnId,
    kind: "user_message",
    status: "completed",
    text,
    contextReferences,
    attachments,
    createdAt: stringField(item, "createdAt"),
  };
}

/**
 * Child thread/read 投影成现有 ChatTimeline 输入，复用安全 Markdown、ToolPresentation、Approval
 * 和 Turn error renderer；原始隐藏推理与未知 item kind 没有兜底展示路径。
 */
function projectTaskTranscript(controller: TaskController): TaskTimelineProjection {
  const snapshot = controller.transcript;
  if (snapshot === undefined) return { items: [], turns: [], approvals: [], approvalDecisions: {} };
  const items: TimelineItemAdapter[] = [];
  const approvals: TimelineApproval[] = [];
  const approvalDecisions: Record<string, "approve" | "deny" | undefined> = {};
  for (const [index, item] of snapshot.items.entries()) {
    const kind = stringField(item, "kind");
    const itemId = stringField(item, "itemId") ?? `task-item-${index}`;
    const turnId = stringField(item, "turnId");
    if (turnId === undefined) continue;
    if (kind === "user_input") {
      items.push(userItem(item, snapshot.threadId, itemId, turnId));
      continue;
    }
    if (kind === "assistant_progress" || kind === "reasoning_summary") {
      items.push({
        itemId,
        threadId: snapshot.threadId,
        turnId,
        kind: "commentary",
        status: "completed",
        title: kind === "reasoning_summary" ? "思考摘要" : "进度",
        text: stringField(item, "text"),
        summary: stringField(item, "text"),
        createdAt: stringField(item, "createdAt"),
      });
      continue;
    }
    if (kind === "final_answer") {
      items.push({
        itemId,
        threadId: snapshot.threadId,
        turnId,
        kind: "agent_message",
        status: "completed",
        text: stringField(item, "text"),
        final: true,
        createdAt: stringField(item, "createdAt"),
      });
      continue;
    }
    if (kind === "tool_call") {
      const presentation = item["presentation"] as Record<string, unknown>;
      items.push({
        itemId,
        threadId: snapshot.threadId,
        turnId,
        kind: "tool_call",
        status: toolItemStatus(presentation),
        title: stringField(presentation, "title"),
        metadata: {
          callId: stringField(item, "callId"),
          toolName: stringField(item, "toolName"),
          presentation: presentation as unknown as NonNullable<
            NonNullable<TimelineItemAdapter["metadata"]>["presentation"]
          >,
        },
        createdAt: stringField(item, "createdAt"),
      });
      continue;
    }
    if (kind === "approval") {
      const approvalId = stringField(item, "approvalId");
      const callId = stringField(item, "callId");
      const toolName = stringField(item, "toolName");
      const reason = stringField(item, "reason");
      const expiresAt = stringField(item, "expiresAt");
      if (
        approvalId === undefined ||
        callId === undefined ||
        toolName === undefined ||
        reason === undefined ||
        expiresAt === undefined
      )
        continue;
      approvals.push({
        approvalId,
        threadId: snapshot.threadId,
        turnId,
        threadRevision: snapshot.revision,
        callId,
        toolName,
        reason,
        expiresAt,
      });
      const decision = item["decision"];
      if (decision === "approve" || decision === "deny") approvalDecisions[approvalId] = decision;
    }
  }
  return {
    items,
    approvals,
    approvalDecisions,
    turns: snapshot.turns.map((turn) => ({
      turnId: turn.turnId,
      threadId: snapshot.threadId,
      status: turn.status as TimelineTurn["status"],
      threadRevision: snapshot.revision,
      startedAt: turn.requestedAt,
      completedAt: turn.completedAt ?? undefined,
      error: turn.errorCode === null ? undefined : { code: turn.errorCode, retryable: false },
    })),
  };
}

/** Task Transcript 只做严格投影，具体文本、Tool 与 Approval 展示完全复用主 Timeline。 */
function TaskTranscript({
  controller,
  actions,
}: {
  controller: TaskController;
  actions?: TaskTranscriptActions;
}): ReactElement {
  const projection = useMemo(() => projectTaskTranscript(controller), [controller]);
  return (
    <ChatTimeline
      className="ja-task-chat-timeline"
      items={projection.items}
      turns={projection.turns}
      approvals={projection.approvals}
      approvalDecisions={projection.approvalDecisions}
      onApprovalDecision={(approval, decision) =>
        controller.approvalRespond(
          approval.approvalId,
          approval.turnId,
          approval.threadRevision,
          decision,
        )
      }
      onOpenLink={actions?.onOpenLink}
      onCopyText={actions?.onCopyText}
      onReadToolArtifact={actions?.onReadToolArtifact}
      onOpenAttachmentPreview={actions?.onOpenAttachmentPreview}
      emptyText="尚无回复"
    />
  );
}

/**
 * Composer submit 按 Java UserContent 的 canonical 顺序冻结：引用、附件、唯一正文。该顺序
 * 让 task/create、task/followup 与普通 turn/start 共用同一严格校验，不携带展示元数据。
 */
function taskContent(request: ComposerSubmit): TaskContentBlock[] {
  return [
    ...(request.contextReferences ?? []).map(
      (reference): TaskContentBlock =>
        reference.type === "workspace_reference"
          ? reference
          : { type: "skill_reference", skillId: reference.skillId },
    ),
    ...(request.attachmentIds ?? []).map((attachmentId) => ({
      type: "attachment" as const,
      attachmentId,
    })),
    ...(request.text.trim() === "" ? [] : [{ type: "text" as const, text: request.text.trim() }]),
  ];
}

/**
 * Side Task 复用生产 Composer 的附件、Workspace 与 Skill 交互；Task controller 只替换最终
 * submit use case。名称由 Tab 唯一编辑；ACK 前保留全部草稿，ACK 后才清除并升级附件预览授权。
 */
function SideTaskComposer({
  task,
  draft,
  label,
  onCreated,
  controller,
  environment,
}: {
  task?: TaskSummary;
  draft: boolean;
  label: string;
  onCreated: (task: TaskSummary) => void;
  controller: TaskController;
  environment?: TaskComposerEnvironment;
}): ReactElement {
  const [sending, setSending] = useState(false);
  const [submitError, setSubmitError] = useState<string>();
  const composer = useTaskComposerDraft(
    environment?.attachmentPort,
    environment?.onAttachmentRemoved,
  );
  const composerThreadId = task?.taskThreadId ?? `draft:${environment?.workspaceId ?? "unknown"}`;
  /**
   * Workspace 搜索仍由根 Thread 完成服务端授权；通过后的有界结果重新绑定到当前 Child/Draft
   * Composer identity，才能沿用通用 Composer 的迟到响应隔离而不伪造新的读取权限。
   */
  const searchWorkspacePaths = useCallback(
    async (query: string): Promise<ComposerWorkspaceSearchResult> => {
      if (environment?.onSearchWorkspacePaths === undefined)
        throw new Error("task workspace search unavailable");
      const result = await environment.onSearchWorkspacePaths(query);
      return { ...result, threadId: composerThreadId };
    },
    [composerThreadId, environment],
  );
  const readyAttachments = readyTaskAttachments(composer.attachmentDraftItems);
  const active =
    task !== undefined && ["queued", "running", "waiting_approval"].includes(task.state);

  /** 同一个 submit handler 同时承载首次创建与后续排队，服务端 ACK 决定何时清空。 */
  const submit = async (request: ComposerSubmit): Promise<void> => {
    if (sending) return;
    const content = taskContent(request);
    if (content.length === 0) return;
    setSending(true);
    setSubmitError(undefined);
    try {
      let target = task;
      if (draft) {
        const normalized =
          (label === "新侧边任务" ? "" : label.trim()) ||
          request.text.trim().split(/\r?\n/u)[0]?.slice(0, 96);
        target = await controller.createSideTask({
          taskName: normalized || readyAttachments[0]?.fileName.slice(0, 96) || "侧边任务",
          content,
        });
        onCreated(target);
      } else if (task !== undefined) target = await controller.followup(task, content);
      const attachmentIds = request.attachmentIds ?? [];
      composer.clearSubmitted(attachmentIds);
      if (target !== undefined && attachmentIds.length > 0)
        environment?.onAttachmentsBound?.(target.taskThreadId, attachmentIds);
    } catch (failure) {
      setSubmitError(failure instanceof Error ? failure.message : "消息未发送，请重试。");
    } finally {
      setSending(false);
    }
  };

  return (
    <div
      className="ja-task-composer ja-conversation-content-rail"
      data-task-state={task?.state ?? "draft"}
    >
      <Composer
        className="ja-task-structured-composer"
        text={composer.text}
        onTextChange={composer.updateText}
        contextReferences={composer.contextReferences}
        onContextReferencesChange={composer.updateContextReferences}
        threadId={composerThreadId}
        workspaceId={environment?.workspaceId}
        runtimeGeneration={environment?.runtimeGeneration}
        skills={environment?.skills}
        onSearchWorkspacePaths={
          environment?.onSearchWorkspacePaths === undefined ? undefined : searchWorkspacePaths
        }
        attachments={readyAttachments}
        attachmentDraftItems={composer.attachmentDraftItems}
        activeTurn={active}
        sending={sending}
        importingAttachments={composer.importingAttachments}
        error={submitError ?? composer.error}
        onAddAttachments={
          environment?.attachmentPort === undefined ? undefined : composer.importAttachments
        }
        onRetryAttachment={
          environment?.attachmentPort === undefined ? undefined : composer.retryAttachment
        }
        onRemoveAttachment={
          environment?.attachmentPort === undefined ? undefined : composer.removeAttachment
        }
        onPasteAttachments={
          environment?.attachmentPort === undefined ? undefined : composer.importClipboard
        }
        onOpenAttachmentPreview={environment?.onOpenAttachmentPreview}
        onSend={submit}
        onEnqueue={submit}
      />
    </div>
  );
}

/** 详情读取失败保留现有投影，并提供同一实例的权威重试入口。 */
function DetailReadAlert({
  message,
  onRetry,
}: {
  message: string;
  onRetry: () => void;
}): ReactElement {
  return (
    <div className="ja-task-read-alert" role="alert">
      <CircleAlert aria-hidden="true" />
      <span>{message}</span>
      <Button variant="ghost" size="sm" onClick={onRetry}>
        重试
      </Button>
    </div>
  );
}

/**
 * 侧边任务与 Subagent 共享详情壳和安全 Timeline；只有侧边任务拥有 Composer。关闭视图只
 * unobserve，取消和 suspended resume 都是独立、明确且可失败重试的用户动作。
 * 侧边任务的标题只在 Tab 呈现，正文不再叠加第二套会话抬头。
 */
export function TaskDetailPanel({
  tab,
  controller,
  onCreated,
  composerEnvironment,
  transcriptActions,
}: {
  tab: WorkbenchTaskTab;
  controller: TaskController;
  onCreated: (task: TaskSummary) => void;
  composerEnvironment?: TaskComposerEnvironment;
  transcriptActions?: TaskTranscriptActions;
}): ReactElement {
  const task =
    controller.detail?.task ??
    controller.tasks.find((item) => item.taskThreadId === tab.taskThreadId);
  const draft = tab.taskThreadId === undefined;
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [cancelError, setCancelError] = useState<string>();
  const [resumeBusy, setResumeBusy] = useState(false);
  const [resumeError, setResumeError] = useState<string>();
  const terminal =
    !draft && (task === undefined || ["completed", "failed", "cancelled"].includes(task.state));
  const propagatingDescendants =
    task === undefined ? 0 : propagatingTaskDescendantCount(controller.tasks, task);
  const contextText = useMemo(() => {
    const context = controller.detail?.contextSeed;
    if (context === undefined) return undefined;
    return context.inheritanceMode === "effective_context"
      ? `继承自主任务 revision ${context.parentRevision}`
      : `仅使用任务简报 · revision ${context.parentRevision}`;
  }, [controller.detail]);

  /** Resume 保持 single-flight；失败不改变权威 suspended 状态，按钮可直接重试。 */
  const resume = async (): Promise<void> => {
    if (task === undefined || resumeBusy) return;
    setResumeBusy(true);
    setResumeError(undefined);
    try {
      await controller.resume(task);
    } catch (failure) {
      setResumeError(
        failure instanceof Error ? failure.message : "任务恢复失败，请确认当前状态后重试。",
      );
    } finally {
      setResumeBusy(false);
    }
  };

  if (!draft && controller.detailError !== undefined && task === undefined)
    return (
      <ErrorState
        title="任务详情暂不可用"
        message={controller.detailError}
        onRetry={() => void controller.refreshDetail()}
      />
    );
  return (
    <section
      className="ja-task-detail"
      aria-label={draft ? "新建侧边任务" : (task?.taskName ?? tab.label)}
    >
      {tab.taskKind === "subagent" || (task !== undefined && !terminal) ? (
        <header className="ja-task-detail-header">
          {tab.taskKind === "subagent" ? (
            <div>
              <span>
                <Bot aria-hidden="true" />
                Subagent
              </span>
              <h2>{task?.taskName ?? tab.label}</h2>
              {task === undefined ? null : <small>{taskStateLabel(task.state)}</small>}
            </div>
          ) : null}
          {task?.state === "suspended" ? (
            <Button size="sm" disabled={resumeBusy} onClick={() => void resume()}>
              {resumeBusy ? (
                <LoaderCircle className="ja-task-spin" aria-hidden="true" />
              ) : (
                <Play aria-hidden="true" />
              )}
              继续运行
            </Button>
          ) : task === undefined || terminal ? null : confirmCancel ? (
            <div className="ja-task-cancel-confirm" role="group" aria-label="确认取消任务">
              <span>
                {task.lifecycle === "attached"
                  ? `将同时取消 ${propagatingDescendants} 个未结束后代`
                  : "仅取消此侧边任务"}
              </span>
              <Button
                variant="danger"
                size="sm"
                onClick={() => {
                  setCancelError(undefined);
                  void controller.cancel(task).catch((failure: unknown) => {
                    setCancelError(
                      failure instanceof Error ? failure.message : "取消请求未完成，请重试。",
                    );
                  });
                  setConfirmCancel(false);
                }}
              >
                确认取消
              </Button>
              <Button variant="ghost" size="sm" onClick={() => setConfirmCancel(false)}>
                返回
              </Button>
            </div>
          ) : (
            <Button variant="ghost" size="sm" onClick={() => setConfirmCancel(true)}>
              <X aria-hidden="true" />
              取消任务
            </Button>
          )}
        </header>
      ) : null}
      {cancelError === undefined ? null : (
        <DetailReadAlert message={cancelError} onRetry={() => setCancelError(undefined)} />
      )}
      {resumeError === undefined ? null : (
        <DetailReadAlert message={resumeError} onRetry={() => void resume()} />
      )}
      {controller.detailError === undefined || task === undefined ? null : (
        <DetailReadAlert
          message={controller.detailError}
          onRetry={() => void controller.refreshDetail()}
        />
      )}
      {controller.transcriptError === undefined ? null : (
        <DetailReadAlert
          message={controller.transcriptError}
          onRetry={() => void controller.refreshDetail()}
        />
      )}
      {contextText === undefined ? null : (
        <details className="ja-task-context">
          <summary>
            {contextText}
            <ChevronDown aria-hidden="true" />
          </summary>
          <p>{controller.detail?.contextSeed.inheritedContextSummary ?? "没有额外的继承摘要。"}</p>
          {(controller.detail?.contextSeed.inheritedContextPreview.length ?? 0) === 0 ? null : (
            <ol aria-label="创建时继承的上下文">
              {controller.detail?.contextSeed.inheritedContextPreview.map((item, index) => (
                <li key={`${item.role}-${index}`}>
                  {item.text === null ? null : <span>{item.text}</span>}
                  {item.attachmentIds.length === 0 ? null : (
                    <small>{item.attachmentIds.length} 个附件</small>
                  )}
                </li>
              ))}
            </ol>
          )}
          <code>SHA-256 {controller.detail?.contextSeed.fingerprint.slice(0, 12)}…</code>
        </details>
      )}
      {draft ? (
        <div className="ja-task-draft-spacer" aria-hidden="true" />
      ) : controller.detailLoading && controller.transcript === undefined ? (
        <div className="ja-task-loading" role="status">
          <LoaderCircle className="ja-task-spin" aria-hidden="true" />
          正在读取任务…
        </div>
      ) : (
        <TaskTranscript controller={controller} actions={transcriptActions} />
      )}
      {controller.progressSummary === undefined ? null : (
        <p className="ja-task-live-progress" aria-live="polite">
          {controller.progressSummary}
        </p>
      )}
      {tab.taskKind === "side_task" &&
      (draft ||
        (task !== undefined && task.state !== "cancelled" && task.state !== "suspended")) ? (
        <SideTaskComposer
          task={task}
          draft={draft}
          label={tab.label}
          onCreated={onCreated}
          controller={controller}
          environment={composerEnvironment}
        />
      ) : null}
      {task?.state === "cancelled" ? (
        <p className="ja-task-closed-note" role="status">
          此任务已取消，不能在界面中伪恢复。
        </p>
      ) : null}
    </section>
  );
}
