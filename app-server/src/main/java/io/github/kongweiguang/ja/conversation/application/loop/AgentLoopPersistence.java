// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointStore;
import io.github.kongweiguang.ja.conversation.application.observation.ExecutionObservers;
import io.github.kongweiguang.ja.conversation.domain.InputQueue;
import io.github.kongweiguang.ja.conversation.domain.model.ModelContent;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.ProviderRequestUsage;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionRequest;
import io.github.kongweiguang.ja.conversation.application.interaction.InteractionSuspendedException;
import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import io.github.kongweiguang.ja.conversation.port.in.TurnEventSink;
import io.github.kongweiguang.ja.conversation.port.in.TurnResult;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.port.out.ExecutionObserver;
import io.github.kongweiguang.ja.conversation.port.out.TaskMailboxPort;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;

/**
 * 统一 Agent Loop 的 CAS 提交、修订号推进与事件发布顺序，确保只发布已经持久化的事实。
 */
final class AgentLoopPersistence {
    private final ConversationRepository store;
    private final Clock clock;
    private final ExecutionObservers observers;
    private final TaskMailboxInbox taskMailboxInbox;
    private final BiConsumer<InteractionRequest, Long> interactionPublisher;

    /**
     * 固定 Repository 与时钟，使一次 Turn 的提交语义和事件时间源保持一致。
     */
    AgentLoopPersistence(ConversationRepository store, Clock clock, ExecutionObservers observers,
                         TaskMailboxInbox taskMailboxInbox) {
        this(store, clock, observers, taskMailboxInbox, (request, sequence) -> { });
    }

    /** 生产组合根在事务提交后发布 Interaction CREATED；测试默认不依赖进程内订阅。 */
    AgentLoopPersistence(ConversationRepository store, Clock clock, ExecutionObservers observers,
                         TaskMailboxInbox taskMailboxInbox,
                         BiConsumer<InteractionRequest, Long> interactionPublisher) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.observers = Objects.requireNonNull(observers, "observers");
        this.taskMailboxInbox = Objects.requireNonNull(taskMailboxInbox, "taskMailboxInbox");
        this.interactionPublisher = Objects.requireNonNull(interactionPublisher, "interactionPublisher");
    }

    /**
     * 在运行 Turn 的安全点 claim Mailbox，再通过 Conversation 专用事务同时追加 USER messages、
     * 消费全部 BOUND 行并推进 execution/CAS；空批次不写数据库也不改变 continuation。事务回执后发布
     * 来源快照事件；FOLLOW_UP 若已由 admission 写入 USER_INPUT，只有实际
     * 新增的 THREAD_MESSAGE 才进入事件，避免同一消息在客户端出现两种语义。
     */
    boolean consumeTaskMailbox(TurnExecutionPlan request, AgentLoop.RuntimeState state, TurnEventSink sink) {
        Objects.requireNonNull(sink, "sink");
        Instant now = clock.instant();
        TaskMailboxPort.ClaimBatch claimed = taskMailboxInbox.claim(
                request.threadId(), request.turnId(), now);
        if (claimed.messages().isEmpty()) return false;
        ConversationRepository.TaskMailboxConsumption receipt = store.consumeTaskMailbox(
                new ConversationRepository.TaskMailboxCommit(request.threadId(), request.turnId(), state.state,
                        claimed.messages(), state.turnMutationVersion, now, state.execution));
        if (receipt.threadRevision() <= state.threadRevision
                || receipt.turnMutationVersion() != state.turnMutationVersion + 1) {
            throw new IllegalStateException("task mailbox consumption did not advance revisions");
        }
        state.threadRevision = receipt.threadRevision();
        state.turnMutationVersion = receipt.turnMutationVersion();
        state.execution = receipt.executionState();
        if (!receipt.messageItems().isEmpty()) {
            TurnEvent.Context context = new TurnEvent.Context("evt_" + UUID.randomUUID(),
                    request.threadId(), request.turnId(), receipt.threadRevision(), now);
            publishCommitted(sink, new TurnEvent.MessagesReceived(context, receipt.messageItems()));
        }
        return true;
    }

    /**
     * 先以当前 mutation version 提交事实，再更新本地权威修订号并发布绑定新修订号的事件。
     */
    void emit(
            TurnExecutionPlan request,
            AgentLoop.RuntimeState state,
            TurnEvent event,
            List<ConversationRepository.Fact> facts,
            TurnExecutionState execution,
            TurnEventSink sink) {
        facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
        ConversationRepository.CommitReceipt receipt =
                store.commit(
                        new ConversationRepository.CommitRequest(
                                request.threadId(),
                                request.turnId(),
                                state.state,
                                facts,
                                state.turnMutationVersion,
                                clock.instant(),
                                Objects.requireNonNull(execution, "execution")));
        requireAdvanced(receipt, state.threadRevision, state.turnMutationVersion);
        state.threadRevision = receipt.threadRevision();
        state.turnMutationVersion = receipt.turnMutationVersion();
        state.execution = execution;
        if (event != null) publishCommitted(sink, rebind(event, receipt.threadRevision()));
    }

    /**
     * 只有存储在同一事务内选中排队输入时才提交模型结算；本地游标与事件必须在事务回执后推进，
     * 从而保证 Assistant/Tool settlement 永远先于由该输入生成的 USER Message。
     */
    boolean emitWithNextInput(
            TurnExecutionPlan request,
            AgentLoop.RuntimeState state,
            TurnEvent event,
            List<ConversationRepository.Fact> facts,
            TurnExecutionState execution,
            ProviderRequestUsage settlementUsage,
            TurnEventSink sink) {
        facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
        Objects.requireNonNull(execution, "execution");
        PreparedInput prepared;
        try {
            prepared = prepareInput(request, execution, null).orElse(null);
        } catch (RejectedSelection rejected) {
            if (event != null) {
                throw new IllegalArgumentException("STOP queued input settlement must not publish model step");
            }
            commitAssistantSettlement(request, state, facts, execution);
            pauseRejectedInput(request, state, rejected, sink);
            throw new AssertionError("pauseRejectedInput must stop execution");
        }
        if (prepared == null) {
            java.util.Optional<ConversationRepository.InputConsumption> empty = store.commitWithNextInput(
                    new ConversationRepository.CommitRequest(request.threadId(), request.turnId(), state.state, facts,
                            state.turnMutationVersion, clock.instant(), execution), null);
            if (empty.isPresent()) throw new IllegalStateException("empty queue gate consumed an input");
            return false;
        }
        java.util.Optional<ConversationRepository.InputConsumption> consumption = store.commitWithNextInput(
                new ConversationRepository.CommitRequest(request.threadId(), request.turnId(), state.state, facts,
                        state.turnMutationVersion, clock.instant(), prepared.execution()), prepared.selection());
        if (consumption.isEmpty()) return false;
        ConversationRepository.InputConsumption receipt = consumption.orElseThrow();
        if (receipt.threadRevision() <= state.threadRevision
            || receipt.turnMutationVersion() != state.turnMutationVersion + 1) {
            throw new IllegalStateException("queued input commit receipt did not advance revisions");
        }
        prepared.boundary().commit();
        state.threadRevision = receipt.threadRevision();
        state.turnMutationVersion = receipt.turnMutationVersion();
        state.execution = prepared.execution();
        if (event != null) {
            throw new IllegalArgumentException("STOP queued input settlement must use input-consumed event");
        }
        publishCommitted(sink, inputConsumed(request, receipt, assistantSettlement(facts, settlementUsage)));
        return true;
    }

    /**
     * 队首不可用不改变此前 Provider STOP 已完成独立回复的事实；通过专用事务将 Assistant
     * 结算为 Final，同时保持问题输入及接收门原样，供后续标记、修复和显式 Resume。
     */
    private void commitAssistantSettlement(
            TurnExecutionPlan request,
            AgentLoop.RuntimeState state,
            List<ConversationRepository.Fact> facts,
            TurnExecutionState execution) {
        ConversationRepository.CommitReceipt receipt = store.commitAssistantSettlement(
                new ConversationRepository.CommitRequest(
                        request.threadId(), request.turnId(), state.state, facts,
                        state.turnMutationVersion, clock.instant(), execution));
        requireAdvanced(receipt, state.threadRevision, state.turnMutationVersion);
        state.threadRevision = receipt.threadRevision();
        state.turnMutationVersion = receipt.turnMutationVersion();
        state.execution = execution;
    }

    /** Tool 整批完成后的安全点只消费一条 Steering，并发布不含 Assistant 结算的原子迁移事件。 */
    boolean consumeInput(TurnExecutionPlan request, AgentLoop.RuntimeState state,
                         ConversationRepository.InputKind kind, TurnEventSink sink) {
        PreparedInput prepared;
        try {
            prepared = prepareInput(request, state.execution, kind).orElse(null);
        } catch (RejectedSelection rejected) {
            pauseRejectedInput(request, state, rejected, sink);
            throw new AssertionError("pauseRejectedInput must stop execution");
        }
        if (prepared == null) return false;
        java.util.Optional<ConversationRepository.InputConsumption> consumption = store.consumeInput(
                request.threadId(), request.turnId(), prepared.selection(), state.turnMutationVersion,
                clock.instant(), prepared.execution());
        if (consumption.isEmpty()) return false;
        ConversationRepository.InputConsumption receipt = consumption.orElseThrow();
        if (receipt.threadRevision() <= state.threadRevision
            || receipt.turnMutationVersion() != state.turnMutationVersion + 1) {
            throw new IllegalStateException("queued input consumption did not advance revisions");
        }
        prepared.boundary().commit();
        state.threadRevision = receipt.threadRevision();
        state.turnMutationVersion = receipt.turnMutationVersion();
        state.execution = prepared.execution();
        publishCommitted(sink, inputConsumed(request, receipt, null));
        return true;
    }

    /** Peek 后通过外层 owner 重验引用并实时加载 Skill，成功结果绑定精确消费 CAS 与新 Prompt 身份。 */
    private java.util.Optional<PreparedInput> prepareInput(TurnExecutionPlan request, TurnExecutionState execution,
                                                           ConversationRepository.InputKind kind) {
        java.util.Optional<io.github.kongweiguang.ja.conversation.domain.InputQueue.QueuedInput> candidate =
                store.peekInput(request.turnId(), kind);
        if (candidate.isEmpty()) return java.util.Optional.empty();
        var input = candidate.orElseThrow();
        ConversationRepository.InputSelection selection = ConversationRepository.InputSelection.from(input);
        if (!store.queuedAttachmentsAvailable(request.threadId(), input, clock.instant())) {
            throw new RejectedSelection(selection, new InputQueue.Issue(
                    "ATTACHMENT_UNAVAILABLE", "排队附件已不可用，请移除后再继续。", true));
        }
        try {
            verifyQueuedAttachmentBlobs(request, input);
        } catch (io.github.kongweiguang.ja.conversation.port.out.ManagedAttachmentReader.ReadFailure failure) {
            throw new RejectedSelection(selection, new InputQueue.Issue(
                    "ATTACHMENT_UNAVAILABLE", "排队附件已不可用，请移除后再继续。", true));
        }
        QueuedInputBoundary.Prepared boundary;
        try {
            // Workspace 文件系统与 SQLite 无法组成跨资源事务；把重验放在 CAS 前最后一步并用
            // inputId/revision 精确消费，既缩小删除/类型变化窗口，也保证并发编辑不会消费旧内容。
            boundary = request.queuedInputBoundary().prepare(input.content());
        } catch (QueuedInputBoundary.Rejected rejected) {
            throw new RejectedSelection(selection, rejected.issue());
        }
        return java.util.Optional.of(new PreparedInput(selection,
                withPromptMaterial(execution, boundary), boundary));
    }

    /**
     * SQLite 事务结束后逐项执行最小物理读取；文件系统不能加入消费事务，后续 selection CAS
     * 仍负责拒绝探测期间发生的编辑，且附件-only 输入也必须经过相同门禁。
     */
    private static void verifyQueuedAttachmentBlobs(
            TurnExecutionPlan request, InputQueue.QueuedInput input) {
        for (String attachmentId : input.content().attachmentIds()) {
            request.attachments().inspect(attachmentId, request.threadId());
        }
    }

    /**
     * 把失效队首保持在原位置并挂起 Turn；仅接受已绑定 selection 的内部异常，避免基类异常
     * 通过未经确认的强转越过队首 CAS 身份边界。
     */
    private void pauseRejectedInput(TurnExecutionPlan request, AgentLoop.RuntimeState state,
                                    RejectedSelection selected, TurnEventSink sink) {
        ConversationRepository.QueueMutation marked = store.markInputNeedsAttention(
                request.threadId(), request.turnId(), selected.selection(), selected.issue(), clock.instant());
        if (marked.changed()) {
            TurnEvent.Context context = new TurnEvent.Context("evt_" + UUID.randomUUID(), request.threadId(),
                    request.turnId(), marked.threadRevision(), clock.instant());
            publishCommitted(sink, new TurnEvent.InputQueueChanged(context, marked.inputQueue()));
        }
        transition(request, state, TurnState.SUSPENDED, sink);
        throw new AgentLoop.InputNeedsAttentionException(selected.issue().errorCode());
    }

    /** 用户消息切换后把候选 Prompt revision 与稳定 Skill ID 写回可恢复 READY 游标。 */
    private static TurnExecutionState withPromptMaterial(TurnExecutionState execution,
                                                         QueuedInputBoundary.Prepared boundary) {
        if (!(execution instanceof TurnExecutionState.Ready ready)) {
            throw new IllegalStateException("queued input can only be consumed from READY execution");
        }
        if (!boundary.changesPrompt()) return execution;
        TurnExecutionState.Common current = ready.common();
        TurnExecutionState.Common updated = new TurnExecutionState.Common(
                current.modelRound(), current.usedToolCalls(), current.nextProviderOrdinal(),
                current.promptCheckpointId(), boundary.activeSkills(), current.deadlineAt(), current.origin(),
                current.activeBudget());
        return new TurnExecutionState.Ready(updated, ready.next(), ready.summary());
    }

    /** 校验成功后等待事务消费的不可拆分门。 */
    private record PreparedInput(ConversationRepository.InputSelection selection,
                                 TurnExecutionState execution,
                                 QueuedInputBoundary.Prepared boundary) { }

    /** 将公开 issue 与产生它的精确队首绑定，避免异常跨并发编辑后误标其它内容。 */
    private static final class RejectedSelection extends QueuedInputBoundary.Rejected {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final transient ConversationRepository.InputSelection selection;

        /**
         * 保存仅供当前调用栈使用的 peek CAS 门；异常不会跨进程序列化，transient 防止把领域选择器
         * 误当作可持久异常载荷。
         */
        private RejectedSelection(ConversationRepository.InputSelection selection,
                                  io.github.kongweiguang.ja.conversation.domain.InputQueue.Issue issue) {
            super(issue);
            this.selection = Objects.requireNonNull(selection, "selection");
        }

        /** 返回与问题同一次读取获得的消费门。 */
        private ConversationRepository.InputSelection selection() {
            return selection;
        }
    }

    /** 从事务回执构造消费事件，确保队列行、Timeline item 与 revision 一次发布。 */
    private TurnEvent.InputConsumed inputConsumed(TurnExecutionPlan request,
                                                  ConversationRepository.InputConsumption receipt,
                                                  TurnEvent.AssistantSettlement assistant) {
        TurnEvent.Context context = new TurnEvent.Context("evt_" + UUID.randomUUID(), request.threadId(),
                request.turnId(), receipt.threadRevision(), receipt.occurredAt());
        TurnEvent.UserItem userItem = new TurnEvent.UserItem(receipt.userItemId(), receipt.occurredAt(),
                request.turnId(), receipt.input().content(), receipt.input().attachments());
        return new TurnEvent.InputConsumed(context, receipt.input(), userItem, receipt.inputQueue(), assistant);
    }

    /** STOP continuation 从已提交 facts 提取唯一 Assistant，并复用调用方绑定的请求级 Usage。 */
    private static TurnEvent.AssistantSettlement assistantSettlement(
            List<ConversationRepository.Fact> facts, ProviderRequestUsage usage) {
        ConversationRepository.AssistantFact assistant = facts.stream()
                .filter(ConversationRepository.AssistantFact.class::isInstance)
                .map(ConversationRepository.AssistantFact.class::cast).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("STOP continuation requires assistant fact"));
        return new TurnEvent.AssistantSettlement(assistant.messageId(), assistant.publicText(),
                assistant.modelRound(), Objects.requireNonNull(usage, "usage"), assistant.reasoningSummary());
    }

    /**
     * 吸收取消 Owner 的权威 CAS Token 后，仅通过专用端口提交已发生副作用的最终 Tool batch。
     */
    void emitCancellationToolBatch(
            TurnExecutionPlan request,
            AgentLoop.RuntimeState state,
            TurnEvent event,
            List<ConversationRepository.Fact> facts,
            TurnExecutionState execution,
            TurnEventSink sink) {
        facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
        refreshExternalAuthority(request, state);
        ConversationRepository.CommitReceipt receipt = store.commitCancellationToolBatch(
                new ConversationRepository.CancellationToolBatchCommit(
                        new ConversationRepository.CommitRequest(
                                request.threadId(), request.turnId(), state.state, facts,
                                state.turnMutationVersion, clock.instant(),
                                Objects.requireNonNull(execution, "execution"))));
        requireAdvanced(receipt, state.threadRevision, state.turnMutationVersion);
        state.threadRevision = receipt.threadRevision();
        state.turnMutationVersion = receipt.turnMutationVersion();
        state.execution = execution;
        publishCommitted(sink, rebind(event, receipt.threadRevision()));
    }

    /**
     * 仅发布本次新提交的压缩检查点，并校验收据属于当前 Thread 且推进了本地修订号。
     */
    void observeCommittedCheckpoint(
            TurnExecutionPlan request,
            AgentLoop.RuntimeState state,
            CheckpointStore.CommittedCheckpoint receipt) {
        Objects.requireNonNull(receipt, "receipt");
        if (!receipt.newlyCommitted()) return;
        if (!request.threadId().equals(receipt.checkpoint().threadId())) {
            throw new IllegalArgumentException("checkpoint receipt thread mismatch");
        }
        if (receipt.threadRevision() <= state.threadRevision) {
            throw new IllegalStateException("checkpoint receipt did not advance the local revision");
        }
        state.threadRevision = receipt.threadRevision();
        if (receipt.turnMutationVersion() != null) {
            if (receipt.turnMutationVersion() != state.turnMutationVersion + 1
                || !(state.execution instanceof TurnExecutionState.Ready ready)
                || ready.next() != TurnExecutionState.Next.SUMMARY) {
                throw new IllegalStateException("checkpoint receipt did not complete the Summary Operation");
            }
            state.turnMutationVersion = receipt.turnMutationVersion();
            state.execution = new TurnExecutionState.Ready(
                    state.execution.common(), TurnExecutionState.Next.ASSISTANT, null);
        }
    }

    /**
     * 原子提交状态迁移及其伴随事实；Reducer 校验通过且 CAS 成功后才改变内存状态和发事件。
     */
    void transitionWithFacts(
            TurnExecutionPlan request,
            AgentLoop.RuntimeState state,
            TurnState target,
            TurnEvent event,
            List<ConversationRepository.Fact> facts,
            TurnExecutionState execution,
            TurnEventSink sink) {
        TurnState prior = state.state;
        if (!prior.canTransitionTo(target)) {
            throw new AgentLoop.LoopFailure("INVALID_STATE", "illegal turn transition");
        }
        ConversationRepository.CommitReceipt receipt =
                store.commit(
                        new ConversationRepository.CommitRequest(
                                request.threadId(),
                                request.turnId(),
                                target,
                                List.copyOf(Objects.requireNonNull(facts, "facts")),
                                state.turnMutationVersion,
                                clock.instant(),
                                Objects.requireNonNull(execution, "execution")));
        requireAdvanced(receipt, state.threadRevision, state.turnMutationVersion);
        state.state = target;
        state.threadRevision = receipt.threadRevision();
        state.turnMutationVersion = receipt.turnMutationVersion();
        state.execution = execution;
        publishCommitted(sink, rebind(event, receipt.threadRevision()));
    }

    /**
     * 提交无附加事实的状态迁移，仍保持“持久化成功先于内存推进与事件可见”的顺序。
     */
    void transition(
            TurnExecutionPlan request,
            AgentLoop.RuntimeState state,
            TurnState target,
            TurnEventSink sink) {
        TurnState prior = state.state;
        if (!prior.canTransitionTo(target)) {
            throw new AgentLoop.LoopFailure("INVALID_STATE", "illegal turn transition");
        }
        TurnEvent.StateChanged draft =
                new TurnEvent.StateChanged(draftContext(request, state), prior, target);
        ConversationRepository.CommitReceipt receipt =
                store.commit(
                        new ConversationRepository.CommitRequest(
                                request.threadId(),
                                request.turnId(),
                                target,
                                List.of(),
                                state.turnMutationVersion,
                                clock.instant(),
                                state.execution));
        requireAdvanced(receipt, state.threadRevision, state.turnMutationVersion);
        state.state = target;
        state.threadRevision = receipt.threadRevision();
        state.turnMutationVersion = receipt.turnMutationVersion();
        publishCommitted(sink, rebind(draft, receipt.threadRevision()));
    }

    /**
     * request_user_input 的唯一暂停入口；Interaction、cursor 和 Turn 状态必须先由 Repository 原子提交，
     * 随后才更新内存与发布两个观察面，防止用户在半提交窗口回答。
     */
    void suspendForInteraction(TurnExecutionPlan request, AgentLoop.RuntimeState state,
                               InteractionSuspendedException suspended, TurnEventSink sink) {
        Objects.requireNonNull(suspended, "suspended");
        InteractionRequest interaction = suspended.request();
        if (!request.threadId().equals(interaction.threadId()) || !request.turnId().equals(interaction.turnId())) {
            throw new AgentLoop.LoopFailure("INVALID_STATE", "interaction identity does not match Turn");
        }
        TurnEvent.StateChanged draft = new TurnEvent.StateChanged(
                draftContext(request, state), state.state, TurnState.SUSPENDED);
        /* 将挂起瞬间的剩余活动预算写入同一事务；绝对 deadline 只服务当前运行，不能让用户等待消耗额度。 */
        Instant suspendedAt = clock.instant();
        Duration remaining = Duration.between(suspendedAt, state.execution.common().deadlineAt());
        TurnExecutionState pausedExecution = state.execution.withActiveBudget(
                remaining.isNegative() ? Duration.ZERO : remaining);
        ConversationRepository.InteractionSuspensionReceipt receipt = store.suspendForInteraction(
                new ConversationRepository.InteractionSuspensionRequest(
                        interaction, pausedExecution, state.turnMutationVersion, suspendedAt));
        requireAdvanced(new ConversationRepository.CommitReceipt(receipt.threadRevision(),
                receipt.turnMutationVersion()), state.threadRevision, state.turnMutationVersion);
        state.state = TurnState.SUSPENDED;
        state.threadRevision = receipt.threadRevision();
        state.turnMutationVersion = receipt.turnMutationVersion();
        interactionPublisher.accept(interaction, receipt.interactionEventSequence());
        publishCommitted(sink, rebind(draft, receipt.threadRevision()));
    }

    /**
     * Plan pause 的取消在 Loop 安全点收口；Repository 只保留已经写入的 execution cursor，
     * 因而正在 Provider/Tool 边界的未知副作用不会被伪装成成功，也不会被自动重做。
     */
    boolean suspendAfterPlanPause(TurnExecutionPlan request, AgentLoop.RuntimeState state,
                                  TurnEventSink sink) {
        ConversationRepository.TurnSnapshot current = store.findTurn(request.threadId(), request.turnId())
                .orElse(null);
        if (current == null || current.state().terminal()) return false;
        /* 取消 claim 只表示停止新调用；这里再把暂停瞬间的剩余活动预算写入 cursor，
         * 使用户等待和应用重启都不会消耗 Plan 的执行时长。 */
        Instant suspendedAt = clock.instant();
        Duration remaining = Duration.between(suspendedAt, state.execution.common().deadlineAt());
        TurnExecutionState pausedExecution = state.execution.withActiveBudget(
                remaining.isNegative() ? Duration.ZERO : remaining);
        if (!store.suspendCancelled(request.threadId(), request.turnId(), current.threadRevision(),
                current.turnMutationVersion(), pausedExecution, suspendedAt)) return false;
        state.execution = pausedExecution;
        TurnEvent.StateChanged suspended = new TurnEvent.StateChanged(
                draftContext(request, state), state.state, TurnState.SUSPENDED);
        state.state = TurnState.SUSPENDED;
        state.threadRevision = current.threadRevision() + 1;
        state.turnMutationVersion = current.turnMutationVersion() + 1;
        publishCommitted(sink, rebind(suspended, state.threadRevision));
        return true;
    }

    /**
     * 通过终态协调器竞争唯一提交权，并显式区分 Usage 的终态投影与首次持久化责任；公开 reasoning
     * 摘要作为独立事实写入，opaque 原生块仍只由成功模型消息提交。
     */
    TurnResult terminal(
            TurnExecutionPlan request,
            TurnEventSink sink,
            AgentLoop.RuntimeState state,
            TerminalCoordinator terminalCoordinator,
            TurnState target,
            String summary,
            String finalMessageId,
            ModelMessage finalMessage,
            String reasoningSummary,
            ModelUsage usage,
            int modelRound,
            int requestOrdinal,
            boolean persistUsage,
            ProviderRequestUsage committedUsage,
            String errorCode,
            String errorMessage) {
        String text = summary == null ? "" : summary;
        String messageId = finalMessage == null ? null : Objects.requireNonNull(finalMessageId, "finalMessageId");
        List<ConversationRepository.Fact> facts;
        TurnExecutionState.ProviderPending providerPending = state.execution
                instanceof TurnExecutionState.ProviderPending pending ? pending : null;
        ConversationRepository.UsagePurpose usagePurpose = providerPending != null
                && providerPending.purpose() == TurnExecutionState.ProviderPurpose.SUMMARY
                ? ConversationRepository.UsagePurpose.SUMMARY
                : ConversationRepository.UsagePurpose.ASSISTANT;
        if (!persistUsage) {
            facts = List.of();
        } else if (usage != null && providerPending != null) {
            facts = List.of(new ConversationRepository.UsageFact(providerPending.requestId(), usage,
                    modelRound, requestOrdinal, usagePurpose, ConversationRepository.UsageCertainty.KNOWN,
                    providerPending.profile()));
        } else {
            // UNKNOWN 已在 Provider dispatch 前落库；失败终态绝不能重复插入或把未知伪装成零。
            facts = List.of();
        }
        if (reasoningSummary != null && !reasoningSummary.isBlank()) {
            java.util.ArrayList<ConversationRepository.Fact> terminalFacts = new java.util.ArrayList<>(facts);
            /* 取消没有 final Assistant 时仍需独立 Timeline identity，不能伪造一个空的 final message。 */
            String summaryAnchor = messageId == null
                    ? "item_reasoning_" + UUID.randomUUID().toString().replace("-", "") : messageId;
            terminalFacts.add(new ConversationRepository.ReasoningSummaryFact(
                    summaryAnchor, reasoningSummary, modelRound));
            facts = List.copyOf(terminalFacts);
        }
        List<ConversationRepository.Fact> committedFacts = facts;
        long priorRevision = state.threadRevision;
        java.util.concurrent.atomic.AtomicReference<io.github.kongweiguang.ja.conversation.application.change
                .TurnChangeTracker.Frozen> frozenChange = new java.util.concurrent.atomic.AtomicReference<>();
        TerminalCoordinator.Finish finish =
                terminalCoordinator.finish(
                        () -> {
                            /*
                             * 取消声明由另一 Owner 提交，并刻意保持 Turn 非终态；在终态边界只刷新一次 CAS Token，
                             * 使首次取消终态提交使用权威 mutation version 获胜。这是类型化刷新，不是提交失败后的盲目重试。
                            */
                            refreshExternalAuthority(request, state);
                            var frozen = request.changeTracker().freeze();
                            frozenChange.set(frozen);
                            return store.commitTerminal(
                                    new ConversationRepository.TerminalCommit(
                                            request.threadId(),
                                            request.turnId(),
                                            target,
                                            text,
                                            errorCode,
                                            errorMessage,
                                            messageId,
                                            finalMessage,
                                            committedFacts,
                                            state.turnMutationVersion,
                                            clock.instant(), frozen.changeSet(),
                                            frozen.sha256(), frozen.byteLength(), frozen.unifiedDiff()));
                        },
                        receipt ->
                                new TurnEvent.Terminal(
                                        committedContext(request, receipt.threadRevision()),
                                        target,
                                        text,
                                        errorCode,
                                        errorMessage,
                                        finalMessage == null
                                                ? null
                                                : new TurnEvent.FinalMessage(messageId, visibleText(finalMessage)),
                                        providerPending == null ? committedUsage
                                                : requestUsage(providerPending, usage, modelRound, requestOrdinal),
                                        Objects.requireNonNull(frozenChange.get(), "frozen change set").changeSet()),
                        sink::publish);
        TurnEvent.Terminal terminal = finish.outcome().event();
        if (finish.committedByCaller()) {
            requireAdvanced(
                    finish.outcome().receipt(), priorRevision, state.turnMutationVersion);
        } else if (finish.outcome().receipt().threadRevision() < priorRevision) {
            throw new IllegalStateException("terminal receipt moved behind local revision");
        }
        state.state = terminal.state();
        state.threadRevision = finish.outcome().receipt().threadRevision();
        state.turnMutationVersion = finish.outcome().receipt().turnMutationVersion();
        observeCommitted(terminal);
        return new TurnResult(terminal.state(), terminal.summary(), terminal);
    }

    /** 终态公开与持久 Usage 使用同一 pending Profile；无计量时明确投影 UNKNOWN。 */
    private static ProviderRequestUsage requestUsage(TurnExecutionState.ProviderPending pending,
                                                      ModelUsage usage, int modelRound, int requestOrdinal) {
        ProviderRequestUsage.Purpose purpose = pending.purpose() == TurnExecutionState.ProviderPurpose.SUMMARY
                ? ProviderRequestUsage.Purpose.SUMMARY : ProviderRequestUsage.Purpose.ASSISTANT;
        return new ProviderRequestUsage(pending.requestId(), requestOrdinal, modelRound, purpose,
                usage == null ? ProviderRequestUsage.Certainty.UNKNOWN : ProviderRequestUsage.Certainty.KNOWN,
                pending.profile(), usage);
    }

    /**
     * 在 Tool batch 或唯一终态提交前吸收取消 Owner 的最新 CAS Token；这是读取已持久化 authority
     * 后的单次提交，不是捕获 CAS 冲突再重试。已出现终态时禁止覆盖既有结果。
     */
    void refreshExternalAuthority(TurnExecutionPlan request, AgentLoop.RuntimeState state) {
        ConversationRepository.TurnSnapshot current = store.findTurn(
                        request.threadId(), request.turnId())
                .orElseThrow(() -> new IllegalStateException("current Turn disappeared before authority refresh"));
        if (current.state().terminal()) {
            throw new IllegalStateException("terminal state was already committed by another owner");
        }
        state.threadRevision = Math.max(state.threadRevision, current.threadRevision());
        state.turnMutationVersion = current.turnMutationVersion();
        state.state = current.state();
    }

    /** 发布外部 owner 已提交的事实事件；调用方必须先刷新 revision，且本方法绝不重复写数据库。 */
    void publishExternalAuthorityEvent(TurnExecutionPlan request, AgentLoop.RuntimeState state,
                                       TurnEvent event, TurnEventSink sink) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(sink, "sink");
        publishCommitted(sink, rebind(event, state.threadRevision));
    }

    /**
     * 为尚未提交的事件生成临时上下文；真正发布前必须用提交收据重绑定修订号。
     */
    TurnEvent.Context draftContext(TurnExecutionPlan request, AgentLoop.RuntimeState state) {
        return new TurnEvent.Context(
                "evt_" + UUID.randomUUID(),
                request.threadId(),
                request.turnId(),
                state.threadRevision,
                clock.instant());
    }

    /**
     * 保留事件身份与时间，只用提交收据替换修订号，避免草稿版本对外可见。
     */
    private TurnEvent rebind(TurnEvent event, long revision) {
        if (event.context() == null) throw new IllegalArgumentException("durable event context is required");
        return event.withContext(
                new TurnEvent.Context(
                        event.context().eventId(),
                        event.context().threadId(),
                        event.context().turnId(),
                        revision,
                        event.context().occurredAt()));
    }

    /**
     * 直接为已提交终态构造上下文，使事件从创建起就携带权威修订号。
     */
    private TurnEvent.Context committedContext(TurnExecutionPlan request, long revision) {
        return new TurnEvent.Context(
                "evt_" + UUID.randomUUID(),
                request.threadId(),
                request.turnId(),
                revision,
                clock.instant());
    }

    /**
     * 强制一次提交同时推进 Thread revision 和恰好一个 Turn mutation version，拒绝异常收据。
     */
    private static void requireAdvanced(
            ConversationRepository.CommitReceipt receipt, long priorRevision, long priorTurnMutationVersion) {
        if (receipt == null || receipt.threadRevision() <= priorRevision
            || receipt.turnMutationVersion() != priorTurnMutationVersion + 1) {
            throw new IllegalStateException("store did not advance the Turn commit receipt");
        }
    }

    /**
     * 只拼接最终消息中的文本块，避免把 Tool 调用结构泄露为用户可见摘要。
     */
    private static String visibleText(ModelMessage message) {
        StringBuilder result = new StringBuilder();
        for (ModelContent content : message.content()) {
            if (content instanceof TextContent text) result.append(text.text());
        }
        return result.toString();
    }

    /**
     * 在同步 Loop 边界等待异步 Sink，并解包运行时异常以保留原始失败分类。
     */
    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw failure;
        }
    }

    /** 先通知只读提交后观察器，再发布到客户端；观察器异常由 ExecutionObservers 隔离。 */
    private void publishCommitted(TurnEventSink sink, TurnEvent event) {
        observeCommitted(event);
        await(sink.publish(event));
    }

    /** 已提交观察只投影安全身份、类型和 revision，不复制事件中的用户正文或 Tool 展示参数。 */
    private void observeCommitted(TurnEvent event) {
        TurnEvent.Context context = event.context();
        if (context == null) return;
        observers.observe(new ExecutionObserver.Committed(
                context.threadId(), context.turnId(), event.getClass().getSimpleName(),
                context.threadRevision()));
    }
}
