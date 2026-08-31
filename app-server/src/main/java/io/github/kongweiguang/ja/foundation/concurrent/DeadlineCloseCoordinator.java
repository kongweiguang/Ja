// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.concurrent;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 协调单个资源所有者的一次性关闭，使不同架构层共享关闭事实而不反向依赖具体适配器。
 * 该类型只决定关闭动作的唯一所有者，不决定资源顺序、失败聚合或强制终止策略。
 */
public final class DeadlineCloseCoordinator {
    private final AtomicReference<CompletableFuture<Void>> completion = new AtomicReference<>();

    /**
     * 创建尚未开始关闭的协调器，避免构造阶段隐式触发任何资源动作。
     */
    public DeadlineCloseCoordinator() {
        // 显式构造器标记生命周期起点，实际关闭动作仍由取得 CAS 所有权的调用方提供。
    }

    /**
     * 只让一个调用者执行关闭动作，其余调用者在自己的剩余预算内观察同一个完成结果。
     * 首个调用者提供的绝对期限同时传给动作，禁止并发关闭为资源释放刷新预算。
     */
    public void close(ShutdownDeadline deadline, String owner,
                      Consumer<ShutdownDeadline> closeAction) {
        ShutdownDeadline sharedDeadline = Objects.requireNonNull(deadline, "deadline");
        Consumer<ShutdownDeadline> action = Objects.requireNonNull(closeAction, "closeAction");
        CompletableFuture<Void> candidate = new CompletableFuture<>();
        /*
         * compareAndExchange 同时发布“关闭已开始”和唯一完成 Future。获胜者执行动作，
         * 失败者只等待已发布结果，避免重复释放资源或为同一次关闭刷新 Deadline。
         */
        CompletableFuture<Void> completionOwner = completion.compareAndExchange(null, candidate);
        CompletableFuture<Void> sharedCompletion = completionOwner == null ? candidate : completionOwner;
        if (completionOwner == null) {
            try {
                action.accept(sharedDeadline);
                sharedCompletion.complete(null);
            } catch (Throwable failure) {
                sharedCompletion.completeExceptionally(failure);
            }
        }
        sharedDeadline.await(sharedCompletion, owner);
    }

    /**
     * 返回关闭是否已被原子接纳，供所有者在关闭栅栏之后拒绝新的资源访问。
     */
    public boolean started() {
        return completion.get() != null;
    }
}
