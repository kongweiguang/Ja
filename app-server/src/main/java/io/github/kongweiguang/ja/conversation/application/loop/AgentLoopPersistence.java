// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointStore;
import io.github.kongweiguang.ja.conversation.application.middleware.MiddlewareChain;
import io.github.kongweiguang.ja.conversation.domain.model.ModelContent;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import io.github.kongweiguang.ja.conversation.port.in.TurnEventSink;
import io.github.kongweiguang.ja.conversation.port.in.TurnResult;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * 统一 Agent Loop 的 CAS 提交、修订号推进与事件发布顺序，确保只发布已经持久化的事实。
 */
final class AgentLoopPersistence {
    private final ConversationRepository store;
    private final Clock clock;
    private final MiddlewareChain middleware;

    /**
     * 固定 Repository 与时钟，使一次 Turn 的提交语义和事件时间源保持一致。
     */
    AgentLoopPersistence(ConversationRepository store, Clock clock, MiddlewareChain middleware) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.middleware = Objects.requireNonNull(middleware, "middleware");
    }

    /**
     * 先以当前 mutation version 提交事实，再更新本地权威修订号并发布绑定新修订号的事件。
     */
    void emit(
            TurnExecutionPlan request,
            AgentLoop.RuntimeState state,
            TurnEvent event,
            List<ConversationRepository.Fact> facts,
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
                                clock.instant()));
        requireAdvanced(receipt, state.threadRevision, state.turnMutationVersion);
        state.threadRevision = receipt.threadRevision();
        state.turnMutationVersion = receipt.turnMutationVersion();
        if (event != null) publishCommitted(sink, rebind(event, receipt.threadRevision()));
    }

    /**
     * 吸收取消 Owner 的权威 CAS Token 后，仅通过专用端口提交已发生副作用的最终 Tool batch。
     */
    void emitCancellationToolBatch(
            TurnExecutionPlan request,
            AgentLoop.RuntimeState state,
            TurnEvent event,
            List<ConversationRepository.Fact> facts,
            TurnEventSink sink) {
        facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
        refreshExternalAuthority(request, state);
        ConversationRepository.CommitReceipt receipt = store.commitCancellationToolBatch(
                new ConversationRepository.CancellationToolBatchCommit(
                        new ConversationRepository.CommitRequest(
                                request.threadId(), request.turnId(), state.state, facts,
                                state.turnMutationVersion, clock.instant())));
        requireAdvanced(receipt, state.threadRevision, state.turnMutationVersion);
        state.threadRevision = receipt.threadRevision();
        state.turnMutationVersion = receipt.turnMutationVersion();
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
                                clock.instant()));
        requireAdvanced(receipt, state.threadRevision, state.turnMutationVersion);
        state.state = target;
        state.threadRevision = receipt.threadRevision();
        state.turnMutationVersion = receipt.turnMutationVersion();
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
                                clock.instant()));
        requireAdvanced(receipt, state.threadRevision, state.turnMutationVersion);
        state.state = target;
        state.threadRevision = receipt.threadRevision();
        state.turnMutationVersion = receipt.turnMutationVersion();
        publishCommitted(sink, rebind(draft, receipt.threadRevision()));
    }

    /**
     * 通过终态协调器竞争唯一提交权，并显式区分 Usage 的终态投影与首次持久化责任。
     */
    TurnResult terminal(
            TurnExecutionPlan request,
            TurnEventSink sink,
            AgentLoop.RuntimeState state,
            TerminalCoordinator terminalCoordinator,
            TurnState target,
            String summary,
            ModelMessage finalMessage,
            ModelUsage usage,
            int modelRound,
            boolean persistUsage,
            String errorCode,
            String errorMessage) {
        String text = summary == null ? "" : summary;
        String messageId = finalMessage == null ? null : "item_" + UUID.randomUUID();
        List<ConversationRepository.Fact> facts = usage == null || !persistUsage
                ? List.of()
                : List.of(new ConversationRepository.UsageFact(usage, modelRound));
        long priorRevision = state.threadRevision;
        TerminalCoordinator.Finish finish =
                terminalCoordinator.finish(
                        () -> {
                            /*
                             * 取消声明由另一 Owner 提交，并刻意保持 Turn 非终态；在终态边界只刷新一次 CAS Token，
                             * 使首次取消终态提交使用权威 mutation version 获胜。这是类型化刷新，不是提交失败后的盲目重试。
                            */
                            refreshExternalAuthority(request, state);
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
                                            facts,
                                            state.turnMutationVersion,
                                            clock.instant()));
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
                                        usage == null ? null : new TurnEvent.TerminalUsage(usage, modelRound)),
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
        middleware.onEventCommitted(terminal);
        return new TurnResult(terminal.state(), terminal.summary(), terminal);
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

    /** 先通知只读提交后观察器，再发布到客户端；观察器异常由 MiddlewareChain 隔离。 */
    private void publishCommitted(TurnEventSink sink, TurnEvent event) {
        middleware.onEventCommitted(event);
        await(sink.publish(event));
    }
}
