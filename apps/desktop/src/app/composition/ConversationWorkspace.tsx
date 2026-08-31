// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { FolderOpen } from "lucide-react";
import { useEffect, useMemo, type ReactElement } from "react";
import { useShallow } from "zustand/react/shallow";
import {
  ChatTimeline,
  Composer,
  ComposerContext,
  ConversationSummaryPopover,
  ReplyFileOpenMenu,
  ThreadOperationsMenu,
  selectApprovalClosedAt,
  selectApprovalDecisions,
  selectApprovals,
  selectItemsForThread,
  resolveContextUsage,
  useConversationInteractionController,
  useReplyFileOpen,
  useTimelineStore,
  type ConversationController,
  type ConversationAttachmentPort,
  type ConversationArtifactPort,
  type ConversationSummary,
  type TimelineTurn as Turn,
} from "@/features/conversation";
import type { SettingsController } from "@/features/settings";
import type { WorkspaceController } from "@/features/workspace";
import { RightPanelIcon } from "@/shared/ui/RightPanelIcon";
import type { JaWorkbenchAdapters } from "../useJaWorkbench";
import { useRuntimeState, useRuntimeTurns } from "../RuntimeProvider";
import { RecoveryPanel } from "./RecoveryPanel";
import { IconButton } from "@/shared/ui/primitives";
import { modelSelectionId } from "@/shared/settings/types";

export interface ConversationWorkspaceProps {
  readonly workspace: WorkspaceController;
  readonly conversation: ConversationController;
  readonly settings: SettingsController;
  readonly inspectorOpen: boolean;
  readonly onToggleInspector: () => void;
  readonly summary: ConversationSummary;
  readonly workspaceAdapter: JaWorkbenchAdapters["workspace"];
  readonly gitBranch?: string;
  readonly onOpenLink: (url: string) => Promise<void>;
  readonly onCopyText: (text: string) => Promise<void>;
  readonly onConversationFocusAvailabilityChange: (available: boolean) => void;
  readonly attachmentPort?: ConversationAttachmentPort;
  readonly artifactPort?: ConversationArtifactPort;
}

/**
 * 对话工作区只负责组合 Conversation、Workspace、Settings 与 Runtime 窄端口，并把 application
 * view model 绑定到 UI；Draft、Turn 并发、pending、审批和模型切换均不在此复制 owner。
 */
export function ConversationWorkspace({
  workspace,
  conversation,
  settings,
  inspectorOpen,
  onToggleInspector,
  summary,
  workspaceAdapter,
  gitBranch,
  onOpenLink,
  onCopyText,
  onConversationFocusAvailabilityChange,
  attachmentPort,
  artifactPort,
}: ConversationWorkspaceProps): ReactElement {
  const { boot, turnAdmissionReady } = useRuntimeState();
  const turnPort = useRuntimeTurns();
  const threadId = conversation.currentThreadId ?? "";
  const items = useTimelineStore(
    useShallow((state) => (threadId === "" ? [] : selectItemsForThread(threadId)(state))),
  );
  const turns = useTimelineStore(
    useShallow((state) =>
      threadId === ""
        ? []
        : Object.values(state.turns).filter((turn) => turn.threadId === threadId),
    ),
  );
  const contextFacts = useTimelineStore(
    useShallow((state) => {
      if (threadId === "") return { usage: undefined, runtime: undefined, compaction: undefined };
      const usage = state.contextUsageByThread[threadId];
      return {
        usage,
        runtime: usage === undefined ? undefined : state.turns[usage.turnId]?.runtime,
        compaction: state.contextCompactionByThread[threadId],
      };
    }),
  );
  const approvals = useTimelineStore(
    useShallow((state) =>
      threadId === ""
        ? []
        : selectApprovals(state).filter((approval) => approval.threadId === threadId),
    ),
  );
  const approvalDecisions = useTimelineStore(useShallow((state) => selectApprovalDecisions(state)));
  const approvalClosedAt = useTimelineStore(useShallow((state) => selectApprovalClosedAt(state)));
  const currentThread = conversation.threads.find(
    (candidate) => candidate.threadId === conversation.currentThreadId,
  );
  const isProjectScope = workspace.workspace?.kind === "project";
  const replyFileOpen = useReplyFileOpen(
    isProjectScope ? workspace.workspace?.workspaceId : undefined,
    items,
    workspaceAdapter,
  );
  /** 模型目录只投影 Thread 偏好需要的稳定身份和能力，不复制 Provider 连接配置。 */
  const models = useMemo(
    () =>
      settings.snapshot.providers.flatMap((provider) =>
        provider.models.map((model) => ({
          value: modelSelectionId(provider.providerId, model.modelId),
          providerId: provider.providerId,
          providerLabel: provider.name,
          modelId: model.modelId,
          modelIdentifier: model.model,
          modelLabel: model.name,
          contextWindowTokens: model.capabilities.contextWindowTokens,
          reasoningLevelMap: model.reasoningLevelMap,
          defaultReasoningLevel: model.defaultReasoningLevel,
        })),
      ),
    [settings.snapshot.providers],
  );
  const selectedContextModel = models.find(
    (model) =>
      model.providerId === currentThread?.preferences?.providerId &&
      model.modelId === currentThread?.preferences?.modelId,
  );
  const contextUsage = resolveContextUsage({
    usage: contextFacts.usage,
    runtime: contextFacts.runtime,
    compaction: contextFacts.compaction,
    providerId: currentThread?.preferences?.providerId,
    modelId: currentThread?.preferences?.modelId,
    contextWindowTokens: selectedContextModel?.contextWindowTokens,
  });
  // Workspace commit 与配置 Query key 切换可能跨一个 React effect；只有目标 scope 已取得
  // 权威快照才开放 Composer，防止旧项目 Provider 配置进入新项目 Turn。
  const settingsScopeReady =
    settings.scopeReady &&
    settings.scopeWorkspaceId ===
      (workspace.workspace?.kind === "project" ? workspace.workspace.workspaceId : undefined);
  const ready =
    settingsScopeReady && turnAdmissionReady && (boot.status === "ready" || boot.status === "busy");
  const interaction = useConversationInteractionController({
    threadId: conversation.currentThreadId,
    workspaceId: workspace.workspace?.workspaceId,
    preferences: currentThread?.preferences ?? undefined,
    models,
    ready,
    blocked: workspace.busy || conversation.busy,
    turnPort,
    preferencesPort: { updatePreferences: conversation.updatePreferences },
    attachmentPort,
  });
  const hasConversationContent = items.length > 0 || turns.length > 0 || approvals.length > 0;
  const scopeName = isProjectScope
    ? (workspace.workspace?.displayName ?? "未命名项目")
    : "无项目对话";
  const threadTitle =
    conversation.threads.find((thread) => thread.threadId === threadId)?.title.trim() || scopeName;
  const scopeError =
    workspace.error === undefined
      ? undefined
      : boot.status === "failed"
        ? `${boot.message}。请在设置中检查模型凭据和本地运行时后重试。`
        : workspace.error;

  /** Composer 可用性只作为壳层快捷键 capability 发布，卸载时立即撤销。 */
  useEffect(() => {
    onConversationFocusAvailabilityChange(!interaction.disabled);
    return () => onConversationFocusAvailabilityChange(false);
  }, [interaction.disabled, onConversationFocusAvailabilityChange]);

  // 空 Thread 与有内容 Thread 共用同一受控 Composer，视觉 dock 迁移不会丢失 Thread draft。
  const composer = (
    <Composer
      key={threadId}
      text={interaction.draft}
      onTextChange={interaction.updateDraft}
      preferences={interaction.preferences}
      models={interaction.models}
      attachments={interaction.attachments}
      activeTurn={interaction.activeTurn}
      disabled={interaction.disabled}
      sending={interaction.sending}
      preferenceBusy={interaction.preferenceBusy}
      importingAttachments={interaction.importingAttachments}
      cancelling={interaction.cancelling}
      error={interaction.error}
      queueStatus={interaction.queueStatus}
      contextUsage={contextUsage}
      onModelChange={(value) => void interaction.changeModel(value)}
      onReasoningChange={(value) => void interaction.changeReasoning(value)}
      onAccessModeChange={(value) => void interaction.changeAccessMode(value)}
      onRestoreDefaults={
        settings.snapshot.defaultSelection === null
          ? undefined
          : () =>
              void interaction.resetPreferences(
                settings.snapshot.defaultSelection!,
                settings.snapshot.defaultAccessMode,
              )
      }
      onAddAttachments={attachmentPort === undefined ? undefined : interaction.importAttachments}
      onRemoveAttachment={attachmentPort === undefined ? undefined : interaction.removeAttachment}
      onSend={interaction.send}
      onQueue={interaction.queue}
      onCancel={interaction.cancel}
    />
  );
  const composerDock = (
    <div className="ja-conversation-composer-dock ja-conversation-content-rail">
      <ComposerContext
        workspaceLabel={scopeName}
        gitBranch={isProjectScope ? gitBranch : undefined}
      />
      {composer}
    </div>
  );

  return (
    <section className="ja-conversation" aria-label="coding 对话">
      <h1 className="ja-visually-hidden">Ja 对话</h1>
      <header className="ja-conversation-header" aria-label="对话操作">
        <div className="ja-conversation-heading" title={threadTitle}>
          <FolderOpen aria-hidden="true" />
          <strong>{threadTitle}</strong>
        </div>
        <div className="ja-conversation-header-actions">
          <ReplyFileOpenMenu {...replyFileOpen} />
          <ConversationSummaryPopover summary={summary} />
          <ThreadOperationsMenu
            showCompactAction={conversation.canCompact}
            compaction={conversation.compaction}
            onCompact={conversation.compact}
            onDismissFeedback={conversation.dismissCompactionFeedback}
          />
          {inspectorOpen ? null : (
            <IconButton
              className="ja-inline-icon-button ja-conversation-drawer-toggle"
              label="显示工作区面板"
              onClick={onToggleInspector}
            >
              <RightPanelIcon />
            </IconButton>
          )}
        </div>
      </header>
      {boot.status === "recovery_required" ? <RecoveryPanel /> : null}
      {scopeError === undefined ? null : (
        <p className="ja-inline-error" role="alert">
          {scopeError}
        </p>
      )}
      {conversation.error === undefined ? null : (
        <p className="ja-inline-error" role="alert">
          {conversation.error}
        </p>
      )}
      {!hasConversationContent ? (
        <section className="ja-conversation-empty" aria-labelledby="ja-conversation-empty-title">
          <div className="ja-conversation-empty-greeting">
            <img
              className="ja-conversation-empty-icon"
              src="/favicon.png"
              alt=""
              aria-hidden="true"
            />
            <h2 id="ja-conversation-empty-title">
              {isProjectScope ? (
                <>
                  你想在 <span>{scopeName}</span> 中构建什么？
                </>
              ) : (
                "你想让 Ja 帮你完成什么？"
              )}
            </h2>
          </div>
          {composerDock}
        </section>
      ) : (
        <ChatTimeline
          items={items}
          turns={turns as Turn[]}
          approvals={approvals}
          approvalDecisions={approvalDecisions}
          approvalClosedAt={approvalClosedAt}
          onApprovalDecision={(approval, decision) => void interaction.approve(approval, decision)}
          onOpenLink={onOpenLink}
          onCopyText={onCopyText}
          onReadToolArtifact={
            artifactPort === undefined || workspace.workspace === undefined
              ? undefined
              : ({ threadId, turnId, callId, artifactId }) =>
                  artifactPort.readToolArtifact({
                    workspaceId: workspace.workspace!.workspaceId,
                    threadId,
                    turnId,
                    callId,
                    artifactId,
                  })
          }
          onReadTurnDiff={
            artifactPort === undefined || workspace.workspace === undefined
              ? undefined
              : ({ threadId, turnId, artifactId }) =>
                  artifactPort.readTurnDiff({
                    workspaceId: workspace.workspace!.workspaceId,
                    threadId,
                    turnId,
                    artifactId,
                  })
          }
        />
      )}
      {interaction.sending ? (
        <span className="ja-visually-hidden" role="status" aria-live="polite">
          正在提交当前请求…
        </span>
      ) : null}
      {hasConversationContent ? composerDock : null}
    </section>
  );
}
