// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentCompactionReceipt;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentCompactionRequest;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentEventPage;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentEventWatch;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionHandle;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionOutcome;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest.HistorySelection;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.foundation.runtime.AppServerIdentity;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionEventCodec;
import io.github.kongweiguang.ja.conversation.application.service.JaExecutionContexts;
import io.github.kongweiguang.ja.conversation.application.service.JaTurnRequestMapper;
import io.github.kongweiguang.ja.conversation.domain.model.ContextCompactionEvent;
import io.github.kongweiguang.ja.conversation.domain.model.ContextCompactionEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadProjection;
import io.github.kongweiguang.ja.conversation.domain.model.TurnProjection;
import io.github.kongweiguang.ja.conversation.application.api.ContextCompactionUseCase;
import io.github.kongweiguang.ja.conversation.application.gateway.ConversationProjectionRepository;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 以 Ja 只读 Thread 投影冻结压缩范围，并把实际执行、事务与恢复交给 AgentEngine。
 */
public final class ManualContextCompactionService implements ContextCompactionUseCase {
    private static final int EVENT_PAGE_SIZE = 256;
    private static final Duration WATCH_INTERVAL = Duration.ofMillis(250);

    private final ConversationProjectionRepository conversations;
    private final AgentStore store;
    private final ExecutionEventCodec eventCodec;
    private final AgentEngine engine;
    private final JaExecutionContexts contexts;
    private final Clock clock;

    /**
     * 复用 Ja 的原生上下文 owner；压缩仍由 Engine 写入，环境只在本次调用期间留在内存。
     */
    public ManualContextCompactionService(ConversationProjectionRepository conversations,
                                          AgentStore store, ExecutionEventCodec eventCodec,
                                          AgentEngine engine, JaExecutionContexts contexts, Clock clock) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.store = Objects.requireNonNull(store, "store");
        this.eventCodec = Objects.requireNonNull(eventCodec, "eventCodec");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 把中断交回准入执行的协调循环，使它先提交 Engine 取消并读取权威终态。
     */
    private static void awaitNext(AgentEventWatch watch, long cursor) throws InterruptedException {
        watch.awaitNext(cursor, WATCH_INTERVAL);
    }

    /**
     * 只接受同一次提交的结果事件与 typed receipt 完全一致，避免读取“最新 checkpoint”补造回执。
     */
    private static boolean matchesReceipt(AgentCompactionReceipt receipt,
                                          ExecutionEvent.ContextCompacted persisted,
                                          ExecutionEvent.CompactionOutcome expectedOutcome) {
        return persisted != null
               && persisted.outcome() == expectedOutcome
               && persisted.compactionId().equals(receipt.compactionId())
               && persisted.sourceRevision() == receipt.sourceRevision()
               && persisted.threadRevision() == receipt.threadRevision()
               && persisted.inputTokensBefore() == receipt.inputTokensBefore()
               && persisted.inputTokensAfter() == receipt.inputTokensAfter()
               && receipt.checkpointId().equals(java.util.Optional.ofNullable(persisted.checkpointId()));
    }

    /**
     * 按持久 reasonCode 穷举公开错误，不解析 Provider 异常文本或猜测压缩阶段。
     */
    private static Code errorCode(String reasonCode) {
        return switch (Objects.requireNonNullElse(reasonCode, "")) {
            case "CAS_CONFLICT", "CONFLICT" -> Code.CONFLICT;
            case "SUMMARY_FAILURE" -> Code.SUMMARY_FAILURE;
            case "CONTEXT_LIMIT" -> Code.CONTEXT_LIMIT;
            case "CANCELLED" -> Code.CANCELLED;
            case "THREAD_BUSY" -> Code.THREAD_BUSY;
            default -> Code.INVALID_STATE;
        };
    }

    /**
     * 等待观察者的异步背压；失败由本次 CompactionEvents 转为断开观察，不改变 Engine 执行。
     */
    private static void publish(ContextCompactionEventSink sink, ContextCompactionEvent event) {
        sink.publish(event).toCompletableFuture().join();
    }

    /**
     * 创建不暴露底层数据、异常信息或路径的稳定业务失败。
     */
    private static Failure failure(Code code) {
        return new Failure(code);
    }

    /**
     * 业务错误码保持脱敏闭集，同时保留只在应用进程内可见的诊断 cause。
     */
    private static Failure failure(Code code, Throwable cause) {
        return new Failure(code, cause);
    }

    /**
     * 先映射预先取消，再校验 Thread revision 与活动 Turn；取消监听先于准入注册，阻止漏掉早期取消。
     * Store 事件负责实时进度，typed receipt 负责精确终态；两者缺少一致证据时拒绝返回成功。
     * 共享后台的原生上下文须先于准入绑定，且覆盖 Engine 权威终态；断流或读页故障不能释放执行 owner。
     * 线程中断先提交 Engine 取消并等待持久终态，再释放事件监视与上下文租约后恢复中断标记。
     */
    @Override
    @SuppressWarnings("PMD.UnusedLocalVariable") // 原生上下文租约由 try-with-resources 释放，执行期间无须再次访问。
    public Result compact(Command command, ContextCompactionEventSink sink, CancellationToken cancellation) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(cancellation, "cancellation");
        try {
            cancellation.throwIfCancellationRequested();
        } catch (CancellationException cancelled) {
            throw failure(Code.CANCELLED, cancelled);
        }
        ThreadProjection thread = conversations.readThread(command.threadId())
                .orElseThrow(() -> failure(Code.THREAD_NOT_FOUND));
        if (thread.revision() != command.expectedThreadRevision()) {
            throw failure(Code.CONFLICT);
        }
        if (thread.turns().stream().anyMatch(turn -> !turn.state().terminal())) {
            throw failure(Code.THREAD_BUSY);
        }

        List<String> currentPath = thread.turns().stream().filter(TurnProjection::currentPath)
                .map(TurnProjection::turnId).toList();
        if (currentPath.isEmpty()) {
            return new Result(Outcome.UNCHANGED, null, null, thread.revision(), 0, 0);
        }

        AgentStore.ConversationKey conversationKey = AppServerIdentity.local().conversation(thread.threadId());
        AgentStore.ConversationSnapshot stored = store.loadConversation(conversationKey)
                .orElseThrow(() -> failure(Code.INVALID_STATE));
        if (stored.revision() != thread.revision()) {
            throw failure(Code.CONFLICT);
        }

        String operationKey = "manual-compaction-" + UUID.randomUUID().toString().replace("-", "");
        AgentCompactionRequest request = JaTurnRequestMapper.compaction(thread.threadId(), thread.workspaceId(),
                thread.preferences(), operationKey,
                HistorySelection.explicit(currentPath, command.expectedThreadRevision()), thread.revision(),
                clock.instant());
        AgentIdentity identity = request.identity();
        AtomicBoolean cancellationSubmitted = new AtomicBoolean();
        AtomicReference<String> selectedCancellationReason = new AtomicReference<>();
        boolean interrupted = false;
        try {
            try (JaExecutionContexts.Binding contextBinding = contexts.bind(identity, null);
                 AgentEventWatch watch = engine.watchEvents(identity, stored.eventSequence())) {
                AdmissionGate admission = new AdmissionGate();
                try (CancellationToken.Registration cancellationRegistration = cancellation.onCancellation(() ->
                        admission.cancel(() -> submitCancellation(identity, operationKey, cancellation,
                                selectedCancellationReason, cancellationSubmitted, "cancelled")))) {
                    if (!admission.begin()) {
                        throw failure(Code.CANCELLED);
                    }
                    AgentExecutionHandle handle;
                    try {
                        handle = engine.compact(request);
                    } catch (RuntimeException | Error rejected) {
                        admission.reject();
                        throw rejected;
                    }
                    Runnable deferredCancellation = admission.accepted();
                    if (deferredCancellation != null) {
                        deferredCancellation.run();
                    }
                    EventCursor cursor = new EventCursor(stored.eventSequence());
                    CompactionEvents events = new CompactionEvents(thread, sink, command.expectedThreadRevision());
                    CompletableFuture<AgentExecutionOutcome> completion = handle.completion().toCompletableFuture();
                    RuntimeException observationFailure = null;
                    while (!completion.isDone()) {
                        boolean advanced = false;
                        if (observationFailure == null) {
                            try {
                                advanced = drain(identity, cursor, events);
                            } catch (RuntimeException failedObservation) {
                                observationFailure = failedObservation;
                            }
                        }
                        if (observationFailure != null) {
                            try {
                                completion.get();
                            } catch (InterruptedException interruption) {
                                interrupted = true;
                                submitCancellation(identity, operationKey, cancellation, selectedCancellationReason,
                                        cancellationSubmitted, "thread interrupted");
                            } catch (ExecutionException completed) {
                                // Future 已结束，退出等待后由 join() 结合持久失败事件统一映射同一异常。
                                break;
                            }
                            continue;
                        }
                        if (!advanced) {
                            try {
                                awaitNext(watch, cursor.sequence);
                            } catch (InterruptedException interruption) {
                                interrupted = true;
                                submitCancellation(identity, operationKey, cancellation, selectedCancellationReason,
                                        cancellationSubmitted, "thread interrupted");
                            } catch (RuntimeException failedObservation) {
                                observationFailure = failedObservation;
                            }
                        }
                    }
                    if (observationFailure == null) {
                        try {
                            drain(identity, cursor, events);
                        } catch (RuntimeException failedObservation) {
                            observationFailure = failedObservation;
                        }
                    }
                    AgentExecutionOutcome outcome;
                    try {
                        outcome = completion.join();
                    } catch (CompletionException failed) {
                        throw completionFailure(failed, events.failed);
                    }
                    if (observationFailure != null) {
                        if (events.failed != null) {
                            throw failure(errorCode(events.failed.reasonCode()), observationFailure);
                        }
                        if (outcome instanceof AgentExecutionOutcome.Completed(
                                io.github.kongweiguang.ja.agent.application.api.TurnResult result
                        )) {
                            if (result.state() == TurnState.CANCELLED) {
                                throw failure(Code.CANCELLED, observationFailure);
                            }
                            if (result.state() == TurnState.FAILED) {
                                throw failure(errorCode(result.errorCode()), observationFailure);
                            }
                        }
                        throw failure(Code.INVALID_STATE, observationFailure);
                    }
                    return settle(outcome, events);
                }
            }
        } catch (ContextCompactionUseCase.Failure known) {
            throw known;
        } catch (AgentStore.StoreConflictException conflict) {
            throw failure(Code.CONFLICT, conflict);
        } catch (StorageException storageFailure) {
            throw failure(storageFailure.code() == StorageException.Code.CAS_CONFLICT
                    ? Code.CONFLICT : Code.INVALID_STATE, storageFailure);
        } catch (CancellationException cancelled) {
            throw failure(Code.CANCELLED, cancelled);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw failure(Code.INVALID_STATE, invalid);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 首个取消原因成为同一幂等操作的持久原因，竞速的令牌取消与线程中断不会互相冲突。
     */
    private void submitCancellation(AgentIdentity identity, String operationKey, CancellationToken cancellation,
                                    AtomicReference<String> selectedReason, AtomicBoolean submitted,
                                    String fallbackReason) {
        String requestedReason = cancellation.reason().orElse(fallbackReason);
        String reason = selectedReason.updateAndGet(current -> current == null ? requestedReason : current);
        if (!submitted.compareAndSet(false, true)) {
            return;
        }
        try {
            engine.cancel(identity, "cancel-" + operationKey, reason).exceptionally(failure -> null);
        } catch (RuntimeException ignored) {
            // Engine 可能在此竞态中先关闭；持久化的执行结果仍是权威状态。
        }
    }

    /**
     * 取消回调先于 Engine 调用注册；准入调用中的取消暂存到 handle 返回，避免取消未受理的操作。
     */
    private static final class AdmissionGate {
        private Stage stage = Stage.READY;
        private Runnable pendingCancellation;

        /**
         * 将同步准入调用设为取消闸门的线性化点；更早的取消直接拒绝准入。
         */
        private synchronized boolean begin() {
            if (stage != Stage.READY) {
                return false;
            }
            stage = Stage.SUBMITTING;
            return true;
        }

        /**
         * 接受注册回调中的取消；只在 Engine 已返回 handle 后才可能提交持久取消。
         */
        private void cancel(Runnable cancellation) {
            boolean submit;
            synchronized (this) {
                if (stage == Stage.READY) {
                    stage = Stage.REJECTED;
                    return;
                }
                if (stage == Stage.SUBMITTING) {
                    if (pendingCancellation == null) {
                        pendingCancellation = cancellation;
                    }
                    return;
                }
                submit = stage == Stage.ADMITTED;
            }
            if (submit) {
                cancellation.run();
            }
        }

        /**
         * 把 handle 作为准入凭证；闸门内暂存的取消在本方法返回后执行。
         */
        private synchronized Runnable accepted() {
            if (stage != Stage.SUBMITTING) {
                throw new IllegalStateException("compaction admission gate is not submitting");
            }
            stage = Stage.ADMITTED;
            Runnable cancellation = pendingCancellation;
            pendingCancellation = null;
            return cancellation;
        }

        /**
         * Engine 准入失败时丢弃暂存取消，防止控制请求指向不存在的执行。
         */
        private synchronized void reject() {
            stage = Stage.REJECTED;
            pendingCancellation = null;
        }

        /** Engine 同步准入期间暂存取消，并区分已受理与已拒绝的不可逆阶段。 */
        private enum Stage {
            /** 取消监听已注册，但尚未开始同步 Engine 准入。 */
            READY,
            /** Engine 正在同步准入，取消请求暂存至其返回结果。 */
            SUBMITTING,
            /** Engine 已返回执行句柄，暂存的取消可以提交。 */
            ADMITTED,
            /** Engine 准入失败，暂存的取消不得提交。 */
            REJECTED
        }
    }

    /**
     * 只沿会话持久游标读有界页，先验证分页有进展再投影事件，避免损坏页产生部分通知。
     * 游标可以越过同会话其他执行，但事件正文不能从其他 execution 泄漏进当前压缩通知。
     */
    private boolean drain(AgentIdentity identity, EventCursor cursor, CompactionEvents projected) {
        long initial = cursor.sequence;
        do {
            AgentEventPage page = engine.readEvents(identity, cursor.sequence, EVENT_PAGE_SIZE);
            if (page.nextSequence() < cursor.sequence
                || page.hasMore() && page.nextSequence() <= cursor.sequence) {
                throw failure(Code.INVALID_STATE);
            }
            for (AgentStore.PersistedEvent persisted : page.events()) {
                if (!identity.turnId().equals(persisted.execution().turnId())) {
                    continue;
                }
                ExecutionEvent event = eventCodec.decode(persisted);
                if (event instanceof ExecutionEvent.ContextCompactionStarted started) {
                    projected.started(persisted, started);
                } else if (event instanceof ExecutionEvent.ContextCompacted compacted) {
                    projected.compacted(persisted, compacted);
                } else if (event instanceof ExecutionEvent.ContextCompactionFailed failed) {
                    projected.failed(persisted, failed);
                }
            }
            cursor.sequence = page.nextSequence();
            if (!page.hasMore()) {
                break;
            }
        } while (true);
        return cursor.sequence != initial;
    }

    /**
     * 准入 CAS 是来源下界；Engine 内部提交推进 revision，终态仍须逐字段匹配本次持久事件。
     */
    private Result settle(AgentExecutionOutcome outcome, CompactionEvents events) {
        if (events.failed != null) {
            throw failure(errorCode(events.failed.reasonCode()));
        }
        if (!(outcome instanceof AgentExecutionOutcome.Compacted(AgentCompactionReceipt receipt))) {
            if (outcome instanceof AgentExecutionOutcome.Completed(
                    io.github.kongweiguang.ja.agent.application.api.TurnResult result
            )) {
                TurnState state = result.state();
                if (state == TurnState.CANCELLED) {
                    throw failure(Code.CANCELLED);
                }
                if (state == TurnState.FAILED) {
                    throw failure(errorCode(result.errorCode()));
                }
            }
            throw failure(Code.INVALID_STATE);
        }
        if (receipt.sourceRevision() < events.expectedRevision) {
            throw failure(Code.CONFLICT);
        }
        ExecutionEvent.ContextCompacted persisted = events.compacted;
        if (receipt.outcome() == AgentCompactionReceipt.Outcome.UNCHANGED) {
            if (events.started != null || !matchesReceipt(receipt, persisted,
                    ExecutionEvent.CompactionOutcome.UNCHANGED)) {
                throw failure(Code.INVALID_STATE);
            }
            return new Result(Outcome.UNCHANGED, null, null, receipt.threadRevision(),
                    receipt.inputTokensBefore(), receipt.inputTokensAfter());
        }
        if (events.started == null || !matchesReceipt(receipt, persisted,
                ExecutionEvent.CompactionOutcome.COMPACTED)
            || !events.started.compactionId().equals(receipt.compactionId())
            || events.started.sourceRevision() != receipt.sourceRevision()
            || events.started.inputTokensBefore() != receipt.inputTokensBefore()) {
            throw failure(Code.INVALID_STATE);
        }
        ContextCompactionEvent.Context context = events.context(events.compactedPersisted,
                persisted.compactionId(), persisted.sourceRevision(), persisted.inputTokensBefore(),
                persisted.inputTokensAfter(), persisted.threadRevision());
        events.publish(new ContextCompactionEvent.Compacted(context, receipt.checkpointId().orElseThrow()));
        return new Result(Outcome.COMPACTED, receipt.compactionId(), receipt.checkpointId().orElseThrow(),
                receipt.threadRevision(), receipt.inputTokensBefore(), receipt.inputTokensAfter());
    }

    /**
     * 发布 Core 已提交的失败水位并映射固定公开错误类别；未知 reason 显式归为 INVALID_STATE。
     */
    private RuntimeException completionFailure(CompletionException failure, ExecutionEvent.ContextCompactionFailed failed) {
        if (failed != null) {
            throw failure(errorCode(failed.reasonCode()), failure);
        }
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause instanceof CancellationException) {
            throw failure(Code.CANCELLED, failure);
        }
        if (cause instanceof AgentStore.StoreConflictException) {
            throw failure(Code.CONFLICT, failure);
        }
        if (cause instanceof StorageException storageFailure
            && storageFailure.code() == StorageException.Code.CAS_CONFLICT) {
            throw failure(Code.CONFLICT, failure);
        }
        return failure(Code.INVALID_STATE, failure);
    }

    /**
     * 保存会话全局游标，Engine 每页都保留跨 execution 的真实推进位置。
     */
    private static final class EventCursor {
        private long sequence;

        /**
         * 事件流从准入前固定的 Store 水位开始，不重新扫描旧历史。
         */
        private EventCursor(long sequence) {
            this.sequence = sequence;
        }
    }

    /**
     * 暂存有序事件，只在压缩终态与 Core typed receipt 一致后公布成功。
     */
    private final class CompactionEvents {
        private final ThreadProjection thread;
        private final ContextCompactionEventSink sink;
        private final long expectedRevision;
        private ExecutionEvent.ContextCompactionStarted started;
        private ExecutionEvent.ContextCompacted compacted;
        private ExecutionEvent.ContextCompactionFailed failed;
        private AgentStore.PersistedEvent compactedPersisted;
        private boolean observing = true;

        /**
         * 本次请求冻结 Thread 与 revision，后续事件不再读取会话投影来猜测配置。
         */
        private CompactionEvents(ThreadProjection thread, ContextCompactionEventSink sink,
                                 long expectedRevision) {
            this.thread = thread;
            this.sink = sink;
            this.expectedRevision = expectedRevision;
        }

        /**
         * 准入和启动已推进会话水位；接受本执行的实际来源，拒绝退回准入前的历史。
         */
        private void started(AgentStore.PersistedEvent persisted,
                             ExecutionEvent.ContextCompactionStarted event) {
            if (started != null || event.sourceRevision() < expectedRevision) {
                throw failure(Code.INVALID_STATE);
            }
            started = event;
            publish(new ContextCompactionEvent.Started(context(persisted, event.compactionId(),
                    event.sourceRevision(), event.inputTokensBefore(), null, persisted.threadRevision())));
        }

        /**
         * Compacted 暂存等 typed receipt 到达后再发布，不把存储定位符当业务 checkpointId。
         */
        private void compacted(AgentStore.PersistedEvent persisted,
                               ExecutionEvent.ContextCompacted event) {
            if (compacted != null) {
                throw failure(Code.INVALID_STATE);
            }
            compacted = event;
            compactedPersisted = persisted;
        }

        /**
         * Failed 必须沿用同一 Started 来源，不能用最初准入 CAS 覆盖 Engine 已冻结的水位。
         */
        private void failed(AgentStore.PersistedEvent persisted,
                            ExecutionEvent.ContextCompactionFailed event) {
            if (failed != null || started == null || compacted != null
                || !started.compactionId().equals(event.compactionId())
                || event.sourceRevision() < expectedRevision
                || started.sourceRevision() != event.sourceRevision()
                || started.inputTokensBefore() != event.inputTokensBefore()) {
                throw failure(Code.INVALID_STATE);
            }
            failed = event;
            publish(new ContextCompactionEvent.Failed(context(persisted, event.compactionId(),
                    event.sourceRevision(), event.inputTokensBefore(), null, persisted.threadRevision()),
                    ContextCompactionEvent.ErrorCode.valueOf(errorCode(event.reasonCode()).name())));
        }

        /**
         * 事件通知只服务于观察者；写入失败后停止投递，但 Engine 仍须完成并给出权威回执。
         */
        private void publish(ContextCompactionEvent event) {
            if (!observing) {
                return;
            }
            try {
                ManualContextCompactionService.publish(sink, event);
            } catch (RuntimeException disconnected) {
                observing = false;
            }
        }

        /**
         * 所有公开事件都继承受理时冻结的 Ja 归属及 Store event 的时间/修订水位。
         */
        private ContextCompactionEvent.Context context(AgentStore.PersistedEvent persisted, String compactionId,
                                                       long sourceRevision, Long before, Long after,
                                                       long revision) {
            return new ContextCompactionEvent.Context(persisted.eventId(), thread.workspaceId(), thread.threadId(),
                    null, revision, persisted.occurredAt(), compactionId,
                    ContextCompactionEvent.Trigger.MANUAL, sourceRevision, before, after,
                    ContextCompactionEvent.STRATEGY_VERSION, null);
        }
    }
}

