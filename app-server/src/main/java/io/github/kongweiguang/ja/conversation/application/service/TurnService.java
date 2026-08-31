// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.service;

import io.github.kongweiguang.ja.conversation.application.cancellation.CancellationCoordinator;
import io.github.kongweiguang.ja.conversation.application.loop.AgentLoop;
import io.github.kongweiguang.ja.conversation.application.loop.TerminalCoordinator;
import io.github.kongweiguang.ja.conversation.application.loop.TurnExecutionPlan;
import io.github.kongweiguang.ja.conversation.application.loop.TurnQueue;
import io.github.kongweiguang.ja.conversation.application.title.AutomaticThreadTitleScheduler;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelContent;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.domain.TurnRuntimeSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.port.in.ThreadMetadataEvent;
import io.github.kongweiguang.ja.conversation.port.in.TurnEventSink;
import io.github.kongweiguang.ja.conversation.port.in.TurnResult;
import io.github.kongweiguang.ja.conversation.port.in.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.port.out.RuntimeLease;
import io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeRequest;
import io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeResolver;
import io.github.kongweiguang.ja.foundation.concurrent.ShutdownDeadline;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turn 准入、FIFO 执行、快速取消、终态提交和关闭的应用层 owner。
 */
public final class TurnService implements TurnUseCase {
    private static final Logger LOGGER = LoggerFactory.getLogger(TurnService.class);
    private final ConversationRepository store;
    private final AgentLoop loop;
    private final TurnQueue queue;
    private final CancellationCoordinator cancellations;
    private final TurnRuntimeResolver runtimeResolver;
    private final Clock clock;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("ja-turn-deadline-", 0).factory());
    private final Map<Key, TurnOwnership> active = new ConcurrentHashMap<>();
    private final Map<Key, CompletableFuture<CancellationCoordinator.CancelOutcome>> cancellationBarriers
            = new ConcurrentHashMap<>();
    private final Object admissionLifecycle = new Object();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final TurnTerminalSettlement terminalSettlement;
    private final TurnCancellationLifecycle cancellationLifecycle;
    private final TurnShutdown shutdown;
    private final AutomaticThreadTitleScheduler automaticTitles;

    /**
     * 注入时钟以测试 Deadline 和准入失败，同时保持队列所有权不变。
     */
    public TurnService(ConversationRepository store, AgentLoop loop, TurnQueue queue,
                       CancellationCoordinator cancellations, TurnRuntimeResolver runtimeResolver,
                       Clock clock) {
        this(store, loop, queue, cancellations, runtimeResolver, clock,
                newTerminalExecutor(), AutomaticThreadTitleScheduler.disabled());
    }

    /**
     * 生产组合根显式注入真实标题调度器；既有测试构造器保持无后台副作用。
     */
    public TurnService(ConversationRepository store, AgentLoop loop, TurnQueue queue,
                       CancellationCoordinator cancellations, TurnRuntimeResolver runtimeResolver,
                       Clock clock, AutomaticThreadTitleScheduler automaticTitles) {
        this(store, loop, queue, cancellations, runtimeResolver, clock,
                newTerminalExecutor(), automaticTitles);
    }

    /**
     * 注入自有终态执行器，使拒绝提交和关闭竞态可确定性验证。
     */
    TurnService(ConversationRepository store, AgentLoop loop, TurnQueue queue,
                CancellationCoordinator cancellations, TurnRuntimeResolver runtimeResolver,
                Clock clock, ExecutorService terminalExecutor) {
        this(store, loop, queue, cancellations, runtimeResolver, clock,
                terminalExecutor, AutomaticThreadTitleScheduler.disabled());
    }

    /**
     * 同时注入终态 owner 与标题调度器，供应用测试证明标题失败不反向污染 Turn 完成。
     */
    TurnService(ConversationRepository store, AgentLoop loop, TurnQueue queue,
                CancellationCoordinator cancellations, TurnRuntimeResolver runtimeResolver,
                Clock clock, ExecutorService terminalExecutor,
                AutomaticThreadTitleScheduler automaticTitles) {
        this.store = Objects.requireNonNull(store, "store");
        this.loop = Objects.requireNonNull(loop, "loop");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.cancellations = Objects.requireNonNull(cancellations, "cancellations");
        this.runtimeResolver = Objects.requireNonNull(runtimeResolver, "runtimeResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.automaticTitles = Objects.requireNonNull(automaticTitles, "automaticTitles");
        ExecutorService requiredTerminalExecutor =
                Objects.requireNonNull(terminalExecutor, "terminalExecutor");
        this.terminalSettlement = new TurnTerminalSettlement(store, clock);
        TurnQueueCancellationSettlement queuedSettlement = new TurnQueueCancellationSettlement(
                requiredTerminalExecutor, cancellations, active, terminalSettlement);
        this.cancellationLifecycle = new TurnCancellationLifecycle(
                store, cancellations, clock, queue, active, cancellationBarriers, queuedSettlement);
        this.shutdown = new TurnShutdown(queue, deadlines, requiredTerminalExecutor, active,
                this::stopAccepting, cancellationLifecycle);
    }

    /**
     * 创建生产 Turn 收口唯一的串行终态 owner，避免并发写终态。
     */
    private static ExecutorService newTerminalExecutor() {
        return Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("ja-turn-terminal-", 0).factory());
    }

    /**
     * 解析并冻结运行时后完成准入；所有失败分支都在所有权转移前释放代际租约。
     */
    @SuppressWarnings("PMD.CloseResource")
    public TurnUseCase.Accepted start(TurnStartRequest request, TurnEventSink sink) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(sink, "sink");
        TurnRuntimeRequest runtimeRequest = new TurnRuntimeRequest(request.threadId(), request.workspaceRoot(),
                request.workspaceId(), request.providerId(), request.modelId(), request.reasoningLevel(),
                request.accessMode(), request.deadline(), request.requestedAt());
        RuntimeLease runtimeLease = Objects.requireNonNull(runtimeResolver.resolve(runtimeRequest), "runtimeLease");
        boolean ownershipTransferred = false;
        try {
            validateResolvedRuntime(request, runtimeLease);
            TurnExecutionPlan executionRequest = new TurnExecutionPlan(request.threadId(), request.turnId(),
                    request.workspaceRoot(), request.input(), request.attachmentIds(),
                    runtimeLease.model(), runtimeLease.accessMode(),
                    runtimeLease.limits(), request.requestedAt(), request.workspaceId(),
                    request.expectedThreadRevision(), request.initialTurnMutationVersion(),
                    runtimeLease.promptSession(), runtimeLease.attachments(), runtimeLease.tools(),
                    runtimeLease.generationId(), runtimeLease.toolSessions(), runtimeLease.outputLimits(),
                    runtimeLease.presentationSecrets());
            TurnExecutionPlan command = executionRequest;
            // 关闭路径采用同一准入锁，防止关闭快照之后再出现新的 active owner。
            synchronized (admissionLifecycle) {
                if (!accepting.get() || shutdown.isClosed()) {
                    throw rejected("SHUTTING_DOWN");
                }
                TurnQueue.Reservation reservation = queue.reserve(command.threadId(), command.turnId());
                CancellationCoordinator.CancellationScope cancellation;
                try {
                    cancellation = cancellations.open(command.threadId(), command.turnId());
                } catch (RuntimeException failure) {
                    reservation.fail(failure);
                    throw failure;
                }
                ConversationRepository.AdmissionReceipt receipt;
                try {
                    List<ModelContent> userContent = new java.util.ArrayList<>();
                    if (!command.userInput().isBlank()) userContent.add(new TextContent(command.userInput()));
                    command.attachmentIds().stream().map(AttachmentContent::new).forEach(userContent::add);
                    ModelMessage userMessage = new ModelMessage(ModelRole.USER, userContent);
                    receipt = store.admit(new ConversationRepository.TurnAdmission(command.threadId(), command.turnId(),
                            runtimeSnapshot(request, runtimeLease),
                            "item_" + UUID.randomUUID(), userMessage, command.attachmentIds(),
                            executionRequest.initialThreadRevision(),
                            command.requestedAt()));
                } catch (RuntimeException failure) {
                    reservation.fail(failure);
                    cancellation.close();
                    cancellations.complete(command.threadId(), command.turnId());
                    throw failure;
                }
                Key key = new Key(command.threadId(), command.turnId());
                // 准入时冻结唯一绝对 Deadline，后续所有路径都使用同一时刻。
                Instant deadlineAt = command.requestedAt().plus(command.limits().wallTimeout());
                TurnOwnership accepted = new TurnOwnership(executionRequest.withAdmissionReceipt(
                        receipt.threadRevision(), receipt.turnMutationVersion()), sink,
                        cancellation, new TerminalCoordinator(), runtimeLease,
                        new CompletableFuture<>(), receipt.createdProvisionalTitle(), deadlineAt);
                if (active.putIfAbsent(key, accepted) != null) {
                    reservation.fail(new IllegalArgumentException("turn identity is already active"));
                    cancellation.close();
                    cancellations.complete(command.threadId(), command.turnId());
                    throw new IllegalArgumentException("turn identity is already active");
                }
                long delayNanos = Math.max(0L, Duration.between(clock.instant(), accepted.deadlineAt).toNanos());
                try {
                    publishProvisionalTitle(command, receipt, sink);
                    accepted.deadline = deadlines.schedule(
                            () -> cancelFromRuntime(key, accepted, "turn deadline exceeded"),
                            delayNanos, TimeUnit.NANOSECONDS);
                    reservation.submit(() -> run(key, accepted));
                } catch (RuntimeException failure) {
                    active.remove(key, accepted);
                    if (accepted.deadline != null) accepted.deadline.cancel(false);
                    reservation.fail(failure);
                    cancellation.close();
                    cancellations.complete(command.threadId(), command.turnId());
                    terminalSettlement.commitUnexpectedTerminal(accepted, TurnState.FAILED,
                            "INTERNAL_ERROR", "turn could not be scheduled");
                    throw failure;
                }
                ownershipTransferred = true;
                return new TurnUseCase.Accepted(command.threadId(), command.turnId(), receipt.threadRevision(), true,
                        accepted.completion);
            }
        } finally {
            if (!ownershipTransferred) {
                runtimeLease.close();
            }
        }
    }

    /**
     * 将客户端 revision 交给取消生命周期执行持久 CAS，拒绝未确认的本地取消。
     */
    public TurnUseCase.CancelResult cancel(String turnId, long expectedThreadRevision) {
        TurnUseCase.CancelResult result = cancellationLifecycle.cancel(turnId, expectedThreadRevision);
        if (result.accepted()) store.cancelInputs(turnId, clock.instant());
        return result;
    }

    /** steering 使用与 Tool 边界消费相同的持久 FIFO，不中断当前模型或 Tool。 */
    @Override
    public TurnUseCase.QueuedInput steer(String turnId, String text) {
        return enqueueInput(turnId, text, ConversationRepository.InputKind.STEERING);
    }

    /** follow-up 只在 Turn 准备结束且没有 steering 时消费。 */
    @Override
    public TurnUseCase.QueuedInput followUp(String turnId, String text) {
        return enqueueInput(turnId, text, ConversationRepository.InputKind.FOLLOW_UP);
    }

    /** 解析活动 Turn 所有权并写入 SQLite；不存在或终态 Turn 直接拒绝而不建内存旁路队列。 */
    private TurnUseCase.QueuedInput enqueueInput(String turnId, String text,
                                                 ConversationRepository.InputKind kind) {
        Map.Entry<Key, TurnOwnership> owner = active.entrySet().stream()
                .filter(entry -> entry.getKey().turnId().equals(turnId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("turn is not active"));
        String inputId = "input_" + UUID.randomUUID();
        store.enqueueInput(new ConversationRepository.PendingInput(inputId,
                owner.getKey().threadId(), turnId, kind, text, clock.instant()));
        return new TurnUseCase.QueuedInput(inputId, turnId,
                kind == ConversationRepository.InputKind.STEERING ? "steering" : "follow_up");
    }

    /**
     * 在与准入共用的监视器内关闭服务和队列入口，确保关闭快照后不再新增 owner。
     */
    public void stopAccepting() {
        synchronized (admissionLifecycle) {
            if (accepting.compareAndSet(true, false)) queue.stopAccepting();
        }
    }

    /**
     * 等待全部 Thread Lane 退出，仅报告队列是否在给定预算内静默。
     */
    public boolean awaitQuiescence(Duration timeout) {
        return queue.awaitQuiescence(timeout);
    }

    /**
     * 以默认总预算委托关闭协调器收敛准入、活动 Turn 和自有执行器。
     */
    @Override
    public void close() {
        closeAt(ShutdownDeadline.start().deadlineNanos());
    }

    /**
     * 使用调用方单调截止线执行幂等关闭，并发调用共享首次关闭结果。
     */
    @Override
    public void closeAt(long shutdownDeadlineNanos) {
        RuntimeException failure = null;
        try {
            shutdown.closeAt(shutdownDeadlineNanos);
        } catch (RuntimeException shutdownFailure) {
            failure = shutdownFailure;
        }
        try {
            automaticTitles.closeAt(shutdownDeadlineNanos);
        } catch (RuntimeException titleFailure) {
            if (failure == null) failure = titleFailure;
            else failure.addSuppressed(titleFailure);
        }
        if (failure != null) throw failure;
    }

    /**
     * 执行已接纳 Turn，并在所有退出路径取消 Deadline、释放 Scope、索引和代际租约。
     */
    private void run(Key key, TurnOwnership turn) {
        CompletionStage<Void> titleCompletion = null;
        try {
            TurnResult result = await(loop.run(turn.request, turn.cancellation, turn.sink,
                    turn.terminalCoordinator));
            if (result.state() == TurnState.COMPLETED) {
                titleCompletion = scheduleAutomaticTitle(turn, result);
            }
            Throwable debt = turn.cancellationDebt.get();
            if (debt == null) turn.completion.complete(result);
            else turn.completion.completeExceptionally(debt);
        } catch (AgentLoop.UnsafeGenerationException unsafe) {
            // 草稿排序权威丢失后不允许提交终态；持久化 RUNNING 是刻意保留的恢复证据，
            // Completion 只负责释放 Turn 外层租约。
            turn.completion.completeExceptionally(unsafe);
        } catch (CancellationException cancelled) {
            cancellationLifecycle.awaitBarrier(key, turn);
            terminalSettlement.settleEmergency(turn, TurnState.CANCELLED, "CANCELLED",
                    "turn cancelled", turn.cancellationDebt.get());
        } catch (TerminalCoordinator.CommitFailure failure) {
            cancellationLifecycle.awaitBarrier(key, turn);
            terminalSettlement.settleEmergency(turn, TurnState.FAILED, "INTERNAL_ERROR",
                    "turn execution failed", null);
        } catch (TerminalCoordinator.ProjectionFailure failure) {
            // 持久终态回执已经存在；调用方观察一次异常完成，Transport 恢复则重新读取权威投影。
            turn.completion.completeExceptionally(failure);
        } catch (RuntimeException failure) {
            cancellationLifecycle.awaitBarrier(key, turn);
            terminalSettlement.settleEmergency(turn, TurnState.FAILED, "INTERNAL_ERROR",
                    "turn execution failed", null);
        } catch (Throwable failure) {
            // Provider 或 Tool 错误仍属于已接纳 Turn 的可观察完成结果；若任其逃逸，关闭流程会永久等待未完成 Future。
            cancellationLifecycle.awaitBarrier(key, turn);
            terminalSettlement.settleEmergency(turn, TurnState.FAILED, "INTERNAL_ERROR",
                    "turn execution failed", failure);
        } finally {
            if (turn.deadline != null) turn.deadline.cancel(false);
            turn.cancellation.close();
            cancellations.complete(key.threadId(), key.turnId());
            cancellationLifecycle.clearBarrier(key);
            active.remove(key, turn);
            if (titleCompletion == null) {
                closeRuntimeLease(turn.runtimeLease, key);
            } else {
                titleCompletion.whenComplete((ignored, failure) ->
                        closeRuntimeLease(turn.runtimeLease, key));
            }
        }
    }

    /**
     * 终态已经发布后仅非阻塞提交后台标题任务；调度故障不能反向改写成功 Turn。
     */
    private CompletionStage<Void> scheduleAutomaticTitle(TurnOwnership turn, TurnResult result) {
        if (!turn.provisionalTitleCreated) return null;
        try {
            TurnRuntimeSnapshot runtime = runtimeSnapshot(turn.runtimeLease);
            return automaticTitles.schedule(new AutomaticThreadTitleScheduler.Request(
                    turn.request.threadId(), turn.request.turnId(),
                    result.terminal().context().threadRevision(), turn.request.userInput(),
                    result.terminal().finalMessage().text(), runtime, turn.runtimeLease.model()), turn.sink);
        } catch (RuntimeException failure) {
            LOGGER.warn("Automatic title scheduling failed threadId={} turnId={} cause={}",
                    turn.request.threadId(), turn.request.turnId(), failure.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * admission 已提交后立即发布短标题且不阻塞 Agent；通知失败只意味着客户端按既有恢复机制重读，
     * 不能反向回滚已接纳 Turn 或延迟首个模型输出。
     */
    private static void publishProvisionalTitle(TurnExecutionPlan command,
                                                ConversationRepository.AdmissionReceipt receipt,
                                                TurnEventSink sink) {
        if (!receipt.createdProvisionalTitle()) return;
        try {
            CompletionStage<Void> publication = Objects.requireNonNull(sink.publish(new ThreadMetadataEvent(
                    command.threadId(), command.workspaceId(), receipt.threadRevision(),
                    receipt.provisionalTitle(), ThreadPreferences.TitleSource.PLACEHOLDER)),
                    "metadata publication");
            publication.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    LOGGER.warn("Provisional title publication failed threadId={} turnId={} cause={}",
                            command.threadId(), command.turnId(), failure.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException failure) {
            LOGGER.warn("Provisional title publication failed threadId={} turnId={} cause={}",
                    command.threadId(), command.turnId(), failure.getClass().getSimpleName());
        }
    }

    /**
     * 标题任务完成后释放冻结代际；日志只包含身份与异常类型，绝不输出配置或凭据。
     */
    private static void closeRuntimeLease(RuntimeLease lease, Key key) {
        try {
            lease.close();
        } catch (RuntimeException failure) {
            LOGGER.warn("Runtime lease release failed threadId={} turnId={} cause={}",
                    key.threadId(), key.turnId(), failure.getClass().getSimpleName());
        }
    }

    /**
     * 校验出站解析器只收紧 Deadline 与访问能力；模型身份由 Resolver 对 v3 选择器严格解析。
     */
    private static void validateResolvedRuntime(TurnStartRequest request, RuntimeLease runtime) {
        if (runtime.limits().wallTimeout().compareTo(request.deadline()) > 0
            || runtime.accessMode() != request.accessMode()) {
            throw new IllegalArgumentException("resolved runtime does not match turn start request");
        }
    }

    /** 从实际租约冻结持久运行快照；端点和凭据不进入该对象。 */
    private static TurnRuntimeSnapshot runtimeSnapshot(TurnStartRequest request, RuntimeLease lease) {
        Objects.requireNonNull(request, "request");
        return runtimeSnapshot(lease);
    }

    /** 后台标题与 admission 复用同一冻结快照构造，避免从可变 Thread 偏好重新解析模型。 */
    private static TurnRuntimeSnapshot runtimeSnapshot(RuntimeLease lease) {
        String provider = lease.model().provider().name().toLowerCase(java.util.Locale.ROOT);
        String api = lease.model().api().name().toLowerCase(java.util.Locale.ROOT);
        return new TurnRuntimeSnapshot(lease.model().providerId(), lease.model().modelId(), provider, api,
                lease.model().model(), lease.model().generation().reasoningLevel(), lease.accessMode(),
                lease.generationId());
    }

    /**
     * 将 Deadline 或关闭触发转换为 CAS 优先的取消流程，禁止直接发布未持久化 Token。
     */
    private void cancelFromRuntime(Key key, TurnOwnership turn, String reason) {
        cancellationLifecycle.requestCancellation(key, turn, reason);
    }

    /**
     * 用稳定错误码创建准入拒绝，避免关闭分支泄露内部状态。
     */
    private static RejectedExecutionException rejected(String code) {
        return new RejectedExecutionException(code);
    }

    /**
     * 同步取得 Agent Loop 结果，并解包运行时异常以进入对应终态分支。
     */
    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw failure;
        }
    }

    /**
     * 以 Thread 与 Turn 双维度索引活动所有权和取消屏障。
     */
    static record Key(String threadId, String turnId) {
        /**
         * 拒绝缺失的身份分量，防止活动表出现无法释放的匿名键。
         */
        Key {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(turnId, "turnId");
        }
    }
}
