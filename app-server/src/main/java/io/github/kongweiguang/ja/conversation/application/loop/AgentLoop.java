// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.application.approval.ApprovalBroker;
import io.github.kongweiguang.ja.conversation.application.cancellation.CancellationCoordinator;
import io.github.kongweiguang.ja.conversation.application.context.ContextOrchestratorFactory;
import io.github.kongweiguang.ja.conversation.application.observation.ExecutionObservers;
import io.github.kongweiguang.ja.conversation.application.policy.ToolPolicyChain;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.conversation.port.in.TurnEventSink;
import io.github.kongweiguang.ja.conversation.port.in.TurnResult;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.port.out.ExecutionObserver;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.conversation.port.out.TaskMailboxPort;
import io.github.kongweiguang.ja.conversation.port.out.ToolPolicy;
import io.github.kongweiguang.ja.conversation.port.out.WorkspaceWriteClaimPort;
import io.github.kongweiguang.ja.conversation.port.out.JsonValueCodec;
import io.github.kongweiguang.ja.conversation.port.out.ToolArgumentValidator;
import io.github.kongweiguang.ja.conversation.port.out.GoalToolExecutionPort;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseable;

import java.io.Serial;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 最小 pi 风格循环：流式接收模型输出、执行 Tool、按序回注结果并继续模型轮次。
 */
public final class AgentLoop implements DeadlineCloseable {
    private static final long CLOSE_GRACE_SECONDS = 2;
    private final AgentToolRunner toolRunner;
    private final DeltaTimerScheduler deltaTimers;
    private final AgentTurnExecution turnExecution;
    private final TaskMailboxInbox taskMailboxInbox;
    private final Object runLifecycle = new Object();
    private final Set<ActiveRun> activeRuns = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>> closeCompletion =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * 组合窄 Provider、策略、审批、存储和生命周期 owner，并使用 UTC 生产时钟。
     */
    public AgentLoop(
            ModelPort model,
            ApprovalBroker approvalBroker,
            ConversationRepository store,
            ContextOrchestratorFactory contextFactory,
            JsonValueCodec argumentsCodec,
            ToolArgumentValidator argumentValidator,
            List<? extends ToolPolicy> policies,
            List<? extends ExecutionObserver> observers) {
        this(model, approvalBroker, store, contextFactory,
                argumentsCodec, argumentValidator, policies, observers, Clock.systemUTC());
    }

    /**
     * 组合根在发布 Loop 前绑定 SQLite write claims；保留既有构造签名供纯 Loop 测试使用无副作用边界。
     */
    public void bindWorkspaceWriteClaims(WorkspaceWriteClaimPort claims, long processGeneration) {
        toolRunner.bindWriteLeases(new WorkspaceWriteLeaseCoordinator(
                Objects.requireNonNull(claims, "claims"), Clock.systemUTC(), processGeneration));
    }

    /** Goal Tool ledger 与既有 Runner 共用真实调用顺序，不建立第二套 Tool executor。 */
    public void bindGoalToolExecution(GoalToolExecutionPort goalTools) {
        toolRunner.bindGoalTools(Objects.requireNonNull(goalTools, "goalTools"));
    }

    /**
     * 绑定 Task Mailbox 的唯一 SQLite owner；Tool Gateway 与 Loop 共享 Repository 事实，
     * 但消息只在运行 Turn 的安全点 claim，空闲 Task 不会被此绑定主动唤醒。
     */
    public void bindTaskMailbox(TaskMailboxPort tasks) {
        taskMailboxInbox.bind(Objects.requireNonNull(tasks, "tasks"));
    }

    /**
     * 注入时钟以确定性测试 Deadline，同时保持生产资源所有权不变。
     */
    public AgentLoop(
            ModelPort model,
            ApprovalBroker approvalBroker,
            ConversationRepository store,
            ContextOrchestratorFactory contextFactory,
            JsonValueCodec argumentsCodec,
            ToolArgumentValidator argumentValidator,
            List<? extends ToolPolicy> policies,
            List<? extends ExecutionObserver> observers,
            Clock clock) {
        ModelPort requiredModel = Objects.requireNonNull(model, "model");
        ConversationRepository requiredStore = Objects.requireNonNull(store, "store");
        approvalBroker.bindDecisionStore(requiredStore::resolveApproval);
        Clock requiredClock = Objects.requireNonNull(clock, "clock");
        ContextOrchestratorFactory requiredContextFactory =
                Objects.requireNonNull(contextFactory, "contextFactory");
        AgentContextMapper contextMapper =
                new AgentContextMapper(Objects.requireNonNull(argumentsCodec, "argumentsCodec"));
        ToolPolicyChain policyChain = new ToolPolicyChain(policies);
        ExecutionObservers executionObservers = new ExecutionObservers(observers);
        this.taskMailboxInbox = new TaskMailboxInbox();
        AgentLoopPersistence persistence = new AgentLoopPersistence(
                requiredStore, requiredClock, executionObservers, taskMailboxInbox);
        this.deltaTimers = new DeltaTimerScheduler();
        this.toolRunner =
                new AgentToolRunner(Objects.requireNonNull(approvalBroker, "approvalBroker"),
                        requiredClock, policyChain, executionObservers,
                        Objects.requireNonNull(argumentValidator, "argumentValidator"));
        this.turnExecution =
                new AgentTurnExecution(
                        requiredModel,
                        requiredStore,
                        requiredClock,
                        requiredContextFactory,
                        contextMapper,
                        persistence,
                        toolRunner,
                        deltaTimers,
                        closed::get,
                        executionObservers);
    }

    /**
     * 注册同步调用者及其终态 owner，使关闭只取消本次作用域且不会重建终态协调器。
     */
    public CompletionStage<TurnResult> run(
            TurnExecutionPlan request, CancellationToken cancellation, TurnEventSink sink,
            TerminalCoordinator terminalCoordinator, TurnExecutionState initialExecution) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(terminalCoordinator, "terminalCoordinator");
        Objects.requireNonNull(initialExecution, "initialExecution");
        ActiveRun active;
        synchronized (runLifecycle) {
            if (closed.get())
                return CompletableFuture.failedFuture(new IllegalStateException("agent loop is closed"));
            active = new ActiveRun(cancellation, Thread.currentThread());
            activeRuns.add(active);
        }
        try {
            return CompletableFuture.completedFuture(
                    execute(request, cancellation, sink, terminalCoordinator, initialExecution));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        } finally {
            activeRuns.remove(active);
            active.finish();
        }
    }

    /**
     * 使用默认宽限期关闭 Loop，给 Provider 与 Tool 的取消传播留出有界收口时间。
     */
    @Override
    public void close() {
        closeAt(deadlineAfter(TimeUnit.SECONDS.toNanos(CLOSE_GRACE_SECONDS * 2)));
    }

    /**
     * 竞争唯一关闭 owner，其余调用者复用同一完成信号并共同受绝对 Deadline 约束。
     */
    @Override
    public void closeAt(long shutdownDeadlineNanos) {
        CompletableFuture<Void> completion = closeCompletion.get();
        boolean owner = false;
        if (completion == null) {
            CompletableFuture<Void> candidate = new CompletableFuture<>();
            if (closeCompletion.compareAndSet(null, candidate)) {
                completion = candidate;
                closed.set(true);
                owner = true;
            } else {
                completion = closeCompletion.get();
            }
        }
        if (owner) {
            try {
                closeOwned(shutdownDeadlineNanos);
                completion.complete(null);
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
        }
        awaitClose(completion, shutdownDeadlineNanos);
    }

    /**
     * 先取消运行中 Turn，再关闭 Tool 执行器并二次等待，确保资源错误不会掩盖未终止任务。
     */
    private void closeOwned(long shutdownDeadlineNanos) {
        List.copyOf(activeRuns).forEach(ActiveRun::cancel);
        RuntimeException closeFailure = null;
        try {
            toolRunner.closeAt(shutdownDeadlineNanos);
        } catch (RuntimeException failure) {
            closeFailure = failure;
        } finally {
            awaitRuns(shutdownDeadlineNanos);
            activeRuns.forEach(ActiveRun::cancel);
            awaitRuns(shutdownDeadlineNanos);
            try {
                deltaTimers.closeAt(shutdownDeadlineNanos);
            } catch (RuntimeException failure) {
                if (closeFailure == null) closeFailure = failure;
                else closeFailure.addSuppressed(failure);
            }
        }
        if (!activeRuns.isEmpty()) {
            IllegalStateException runFailure =
                    new IllegalStateException("Agent model runs did not terminate");
            if (closeFailure != null) {
                runFailure.addSuppressed(closeFailure);
            }
            throw runFailure;
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    /**
     * 执行模型轮次；只有 STOP 轮次可以提供最终 assistant 文本。
     */
    private TurnResult execute(TurnExecutionPlan request, CancellationToken cancellation,
                               TurnEventSink sink, TerminalCoordinator terminalCoordinator,
                               TurnExecutionState initialExecution) {
        return turnExecution.execute(request, cancellation, sink, terminalCoordinator, initialExecution);
    }

    /**
     * 在绝对 Deadline 内等待活动集合清空，短暂 park 避免关闭线程忙等占满 CPU。
     */
    private void awaitRuns(long shutdownDeadlineNanos) {
        while (!activeRuns.isEmpty()) {
            long remaining = shutdownDeadlineNanos - System.nanoTime();
            if (remaining <= 0) return;
            java.util.concurrent.locks.LockSupport.parkNanos(
                    Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(5)));
        }
    }

    /**
     * 等待唯一关闭结果并保留中断位，同时把超时和内部失败转成稳定的生命周期异常。
     */
    private static void awaitClose(CompletableFuture<Void> completion, long shutdownDeadlineNanos) {
        long remaining = shutdownDeadlineNanos - System.nanoTime();
        if (remaining <= 0 && !completion.isDone()) {
            throw new IllegalStateException("agent loop close deadline expired");
        }
        try {
            completion.get(Math.max(1, remaining), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while closing agent loop", interrupted);
        } catch (TimeoutException timeout) {
            throw new IllegalStateException("agent loop close deadline expired", timeout);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("agent loop close failed", cause);
        }
    }

    /**
     * 以单调时钟计算绝对 Deadline，并在纳秒加法溢出时饱和到最大值。
     */
    private static long deadlineAfter(long budgetNanos) {
        long now = System.nanoTime();
        return now >= Long.MAX_VALUE - budgetNanos ? Long.MAX_VALUE : now + budgetNanos;
    }

    /**
     * 记录一个同步 Turn 的取消令牌与所属线程；普通 Turn 取消只走协作式清理，避免中断破坏终态排空。
     */
    private final class ActiveRun {
        private final CancellationToken cancellation;
        private final Thread owner;
        private final Object interruptLifecycle = new Object();
        private boolean interruptInjected;
        private boolean finished;

        /**
         * 绑定调用线程但不为普通取消注册中断；Provider、Tool 和草稿批次都通过同一 Token 自行收口。
         */
        private ActiveRun(CancellationToken cancellation, Thread owner) {
            this.cancellation = cancellation;
            this.owner = owner;
        }

        /**
         * Loop 关闭先请求协作式取消，再以受控中断打破不遵守 Token 的阻塞调用；中断只在活动窗口内注入。
         */
        @SuppressWarnings("PMD.CloseResource")
        private void cancel() {
            if (cancellation instanceof CancellationCoordinator.CancellationScope scope) {
                scope.requestCancellation("agent loop closing");
            }
            synchronized (interruptLifecycle) {
                if (!finished && Thread.currentThread() != owner) {
                    interruptInjected = true;
                    owner.interrupt();
                }
            }
        }

        /**
         * 在 owner 退出任务前消费仅由 Loop 关闭注入的中断，防止复用线程把关闭信号带给后续任务。
         */
        private void finish() {
            synchronized (interruptLifecycle) {
                finished = true;
                if (interruptInjected && Thread.currentThread() == owner) {
                    Thread.interrupted();
                }
            }
        }
    }

    /**
     * 保存单个 Turn 的权威运行状态、修订号与序号分配器，供 Reducer 按序推进持久事实。
     */
    static final class RuntimeState {
        TurnState state = TurnState.QUEUED;
        long threadRevision;
        long turnMutationVersion;
        int toolCalls;
        int nextToolOrdinal;
        long nextStreamSequence = 1;
        TurnExecutionState execution;

        /**
         * 从已提交快照的修订号初始化状态，后续只能随成功持久化的迁移单调前进。
         */
        RuntimeState(long threadRevision, long turnMutationVersion, TurnExecutionState execution) {
            this.threadRevision = threadRevision;
            this.turnMutationVersion = turnMutationVersion;
            this.execution = Objects.requireNonNull(execution, "execution");
            this.toolCalls = execution.common().usedToolCalls();
            this.nextToolOrdinal = execution instanceof TurnExecutionState.Tools tools
                    ? tools.lastOrdinal() + 1 : execution.common().usedToolCalls();
        }

        /**
         * 为草稿流分配从一开始的单调序号，使异步批次仍可由调用方确定性重排。
         */
        long allocateStreamSequence() {
            return nextStreamSequence++;
        }

        /**
         * 连续预留 Tool 序号区间，并在越过 Turn 上限前原子拒绝整个批次。
         */
        int allocateToolOrdinals(int count) {
            if (count < 0 || nextToolOrdinal + count > 1_024) {
                throw new LoopFailure("BUDGET_EXCEEDED", "Tool ordinal limit reached");
            }
            int base = nextToolOrdinal;
            nextToolOrdinal += count;
            return base;
        }
    }

    /**
     * 携带稳定应用错误码的 Loop 失败，供上层在不解析异常文本的情况下映射 RPC 错误。
     */
    public static final class LoopFailure extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        private final String code;

        /**
         * 将错误码与安全消息成对固定，避免后续异常包装丢失机器可读分类。
         */
        public LoopFailure(String code, String message) {
            super(message);
            this.code = code;
        }

        /**
         * 返回跨应用边界使用的稳定错误码。
         */
        public String code() {
            return code;
        }
    }

    /** 排队输入需要用户修复时，Turn 已持久化为 SUSPENDED，服务层不得再提交失败终态。 */
    public static final class InputNeedsAttentionException extends IllegalStateException {
        @Serial
        private static final long serialVersionUID = 1L;
        private final String errorCode;

        /** 只携带稳定错误码，具体修复信息由权威 InputQueue 投影提供。 */
        InputNeedsAttentionException(String errorCode) {
            super("queued input needs attention");
            this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        }

        /** 返回队列问题闭集中的稳定错误码。 */
        public String errorCode() {
            return errorCode;
        }
    }

    /**
     * 表示草稿流无法在终态提交前安全排空，终态协调器必须拒绝继续发布成功结果。
     */
    public static final class UnsafeGenerationException extends IllegalStateException {
        @Serial
        private static final long serialVersionUID = 1L;
        private final Code code;

        /**
         * 把内部排空失败映射为公开分类，同时保留原始原因用于受控诊断。
         */
        UnsafeGenerationException(AgentRound.DeltaDrainException.Code code, Throwable cause) {
            super("agent output generation is unsafe", cause);
            this.code = switch (Objects.requireNonNull(code, "code")) {
                case TIMEOUT -> Code.DELTA_DRAIN_TIMEOUT;
                case INTERRUPTED -> Code.DELTA_DRAIN_INTERRUPTED;
                case SINK_FAILURE -> Code.DELTA_SINK_FAILURE;
            };
        }

        /**
         * 返回调用方可据以判断超时、中断或 Sink 故障的稳定分类。
         */
        public Code code() {
            return code;
        }

        /**
         * 草稿增量停止发布时可安全跨越应用边界的失败分类。
         */
        public enum Code {
            /**
             * 终态前等待草稿队列排空超过固定期限。
             */
            DELTA_DRAIN_TIMEOUT,
            /**
             * 等待草稿队列时线程被中断并恢复中断位。
             */
            DELTA_DRAIN_INTERRUPTED,
            /**
             * 草稿 Sink 拒绝或无法保持既定顺序。
             */
            DELTA_SINK_FAILURE
        }
    }
}
