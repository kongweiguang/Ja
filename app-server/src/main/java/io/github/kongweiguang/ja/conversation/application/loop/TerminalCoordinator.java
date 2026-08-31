// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * AgentLoop 与应用紧急收口 owner 共享的 Turn 局部单终态状态机。
 */
public final class TerminalCoordinator {
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.OPEN);
    private final CompletableFuture<Outcome> outcome = new CompletableFuture<>();

    /**
     * 仅允许一个调用方提交并发布；其余调用方等待同一完整结果，不得提前越过终态通知屏障。
     */
    public Finish finish(
            Supplier<ConversationRepository.CommitReceipt> commit,
            Function<ConversationRepository.CommitReceipt, TurnEvent.Terminal> eventFactory,
            Function<TurnEvent.Terminal, CompletionStage<Void>> publisher) {
        Objects.requireNonNull(commit, "commit");
        Objects.requireNonNull(eventFactory, "eventFactory");
        Objects.requireNonNull(publisher, "publisher");
        if (!phase.compareAndSet(Phase.OPEN, Phase.COMMITTING)) {
            return new Finish(await(outcome), false);
        }
        ConversationRepository.CommitReceipt receipt;
        try {
            /*
             * 数据库提交失败会留下非终态恢复证据，但当前执行的终态所有权已经耗尽。状态只能进入
             * COMMIT_FAILED，禁止同一进程根据异常重开盲写；启动恢复负责处理剩余 RUNNING 记录。
            */
            receipt = Objects.requireNonNull(commit.get(), "commit receipt");
        } catch (CommitFailure failure) {
            /*
             * 失败事务没有持久回执，但对当前内存执行仍是最终所有权决定。所有观察者共享同一异常结果，
             * 不会再发起第二次终态写入。
             */
            phase.set(Phase.COMMIT_FAILED);
            outcome.completeExceptionally(failure);
            throw failure;
        } catch (Throwable failure) {
            CommitFailure classified = new CommitFailure(failure);
            phase.set(Phase.COMMIT_FAILED);
            outcome.completeExceptionally(classified);
            throw classified;
        }
        try {
            TurnEvent.Terminal event = Objects.requireNonNull(eventFactory.apply(receipt), "terminal event");
            phase.set(Phase.PUBLISHING);
            await(Objects.requireNonNull(publisher.apply(event), "terminal publication"));
            Outcome completed = new Outcome(receipt, event);
            phase.set(Phase.PUBLISHED);
            outcome.complete(completed);
            return new Finish(completed, true);
        } catch (Throwable failure) {
            // 数据库回执已经权威可见；事件构造或发布失败只能关闭协调器，不能重新提交终态。
            ProjectionFailure projectionFailure = failure instanceof ProjectionFailure existing
                    ? existing : new ProjectionFailure(failure);
            phase.set(Phase.PROJECTION_FAILED);
            outcome.completeExceptionally(projectionFailure);
            throw projectionFailure;
        }
    }

    /**
     * 终态提交状态只允许单向推进，避免异常分支把已消费的提交权重新开放。
     */
    private enum Phase {
        /**
         * 尚无调用方取得终态提交权。
         */
        OPEN,

        /**
         * 唯一 owner 正在执行持久化提交。
         */
        COMMITTING,

        /**
         * 持久回执已产生，唯一 owner 正在等待终态事件发布完成。
         */
        PUBLISHING,

        /**
         * 持久回执与终态事件发布均已完成。
         */
        PUBLISHED,

        /**
         * 持久回执产生前失败，等待启动恢复处理非终态记录。
         */
        COMMIT_FAILED,

        /**
         * 持久回执已存在但事件投影失败，调用方必须重读权威状态。
         */
        PROJECTION_FAILED
    }

    /**
     * 标识持久回执产生前的数据库失败，使 AgentLoop 的通用异常分支不会递归执行第二次盲写。
     */
    public static final class CommitFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        /**
         * 保留存储原因供内部诊断，同时向调用链暴露稳定的终态边界分类。
         */
        public CommitFailure(Throwable cause) {
            super("terminal commit failed before durable terminal state", cause);
        }
    }

    /**
     * 标识提交后的投影失败，并保持持久终态门永久关闭。
     */
    public static final class ProjectionFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        /**
         * 保留内部投影原因，并向调用方明确此边界禁止重试提交。
         */
        public ProjectionFailure(Throwable cause) {
            super("terminal projection failed after durable commit", cause);
        }
    }

    /**
     * 返回唯一已完成终态，不额外读取或伪造存储状态。
     */
    public Outcome awaitOutcome() {
        return await(outcome);
    }

    /**
     * 等待单赋值结果，并保留原始 RuntimeException 分类。
     */
    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw failure;
        }
    }

    /**
     * 同步跨越异步发布屏障；发布失败由外层统一分类为不可重试的投影失败。
     */
    private static <T> T await(CompletionStage<T> stage) {
        return await(stage.toCompletableFuture());
    }

    /**
     * 将唯一提交回执与由其派生的精确终态投影绑定。
     */
    public record Outcome(ConversationRepository.CommitReceipt receipt, TurnEvent.Terminal event) {
        /**
         * 在 Accepted Turn 完成前拒绝回执与事件 revision 不一致。
         */
        public Outcome {
            Objects.requireNonNull(receipt, "receipt");
            Objects.requireNonNull(event, "event");
            if (receipt.threadRevision() != event.context().threadRevision()) {
                throw new IllegalArgumentException("terminal receipt and event revision differ");
            }
        }
    }

    /**
     * 表示当前调用方执行了存储提交，还是仅观察既有结果。
     */
    public record Finish(Outcome outcome, boolean committedByCaller) {
        /**
         * 获胜者和观察者都必须取得完整终态结果。
         */
        public Finish {
            Objects.requireNonNull(outcome, "outcome");
        }
    }
}
