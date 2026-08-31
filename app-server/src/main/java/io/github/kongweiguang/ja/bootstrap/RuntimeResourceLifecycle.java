// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.foundation.concurrent.ShutdownDeadline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一拥有 Solon 创建的运行时资源，并严格按构造逆序释放。
 */
final class RuntimeResourceLifecycle implements AutoCloseable {
    private final Deque<AutoCloseable> resources = new ArrayDeque<>();
    private final Set<AutoCloseable> identities = Collections.newSetFromMap(new IdentityHashMap<>());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<Void>> closeCompletion = new AtomicReference<>();
    private final AtomicReference<ShutdownDeadline.ForcedTerminationException> forcedTermination =
            new AtomicReference<>();
    private AutoCloseable shutdownFence;
    private AutoCloseable activeClose;

    /**
     * 已构造资源必须立即登记，确保后续 Bean 构造失败时，先创建的数据库、Executor、
     * Provider 传输或队列仍处于统一关闭边界内。
     */
    synchronized <T extends AutoCloseable> T own(T resource) {
        T value = Objects.requireNonNull(resource, "resource");
        if (closed.get()) {
            closeLateResource(value);
            throw new IllegalStateException("runtime lifecycle is already closed");
        }
        if (!identities.add(value)) {
            throw new IllegalStateException("runtime resource is already owned");
        }
        resources.addLast(value);
        return value;
    }

    /**
     * 单独登记依赖关闭栅栏，使其始终先于队列、Loop、取消、持久化和数据库 Owner 执行，
     * 后续 Bean 的注册顺序不能改变这一约束。
     */
    synchronized <T extends AutoCloseable> T ownShutdownFence(T resource) {
        if (shutdownFence != null) {
            throw new IllegalStateException("runtime shutdown fence is already owned");
        }
        T value = own(resource);
        shutdownFence = value;
        return value;
    }

    /**
     * 启动一次默认预算关闭，后续调用方必须等待同一个完成结果。
     */
    @Override
    public void close() {
        close(ShutdownDeadline.start());
    }

    /**
     * 在同一个绝对 Deadline 下执行栅栏与依赖逆序关闭；资源只有关闭成功后才移除所有权记录，
     * 防止未完成栅栏让后续强制终止路径误判资源已经安全释放。
     */
    void close(ShutdownDeadline deadline) {
        Objects.requireNonNull(deadline, "deadline");
        CompletableFuture<Void> completion;
        boolean owner = false;
        synchronized (this) {
            completion = closeCompletion.get();
            if (completion == null) {
                completion = new CompletableFuture<>();
                closeCompletion.set(completion);
                closed.set(true);
                owner = true;
            }
        }
        if (owner) {
            try {
                closeOwnedResources(deadline);
                synchronized (this) {
                    ShutdownDeadline.ForcedTerminationException forced = forcedTermination.get();
                    if (forced != null) throw forced;
                    completion.complete(null);
                }
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
        }
        awaitCompletion(completion, deadline, "runtime resource lifecycle");
    }

    /**
     * 在不启动任何依赖关闭的前提下记录入站无法证明静默；后续 Solon 销毁回调只能观察同一个
     * 失败结果，不能重新分配预算并在请求或 Turn Worker 仍运行时关闭持久化。
     */
    void requireForcedTermination(Throwable cause) {
        synchronized (this) {
            CompletableFuture<Void> completion = closeCompletion.get();
            if (completion == null) {
                completion = new CompletableFuture<>();
                closeCompletion.set(completion);
                closed.set(true);
            }
            if (completion.isDone()) return;
            ShutdownDeadline.ForcedTerminationException forced = forcedTermination.updateAndGet(
                    prior -> prior == null
                            ? forcedFailure("forced termination: runtime ingress did not quiesce", cause)
                            : prior);
            completion.completeExceptionally(forced);
        }
    }

    /**
     * 先执行依赖栅栏，再严格按逆序关闭其余 Owner。
     */
    @SuppressWarnings("PMD.CloseResource")
    private void closeOwnedResources(ShutdownDeadline deadline) {
        List<AutoCloseable> snapshot;
        AutoCloseable fence;
        synchronized (this) {
            snapshot = new ArrayList<>(resources);
            fence = shutdownFence;
        }
        if (fence != null) {
            beginClose(fence);
            try {
                closeWithinDeadline(fence, deadline, "runtime dependency fence");
                removeClosed(fence);
            } catch (RuntimeException failure) {
                throw forcedFailure("forced termination: runtime dependency fence failed", failure);
            } finally {
                endClose(fence);
            }
            throwIfForced();
        }
        RuntimeException failure = null;
        for (int index = snapshot.size() - 1; index >= 0; index--) {
            AutoCloseable resource = snapshot.get(index);
            if (resource == fence) continue;
            beginClose(resource);
            try {
                closeWithinDeadline(resource, deadline, "runtime resource");
                removeClosed(resource);
            } catch (RuntimeException closeFailure) {
                if (closeFailure instanceof ShutdownDeadline.ForcedTerminationException
                    || deadline.expired()) {
                    ShutdownDeadline.ForcedTerminationException forced =
                            closeFailure instanceof ShutdownDeadline.ForcedTerminationException value
                                    ? value
                                    : forcedFailure("forced termination: runtime close deadline expired",
                                    closeFailure);
                    if (failure != null) forced.addSuppressed(failure);
                    throw forced;
                }
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            } finally {
                endClose(resource);
            }
            throwIfForced();
        }
        throwIfForced();
        if (failure != null) throw failure;
    }

    /**
     * 标记强制升级前已经准入的唯一关闭操作，避免并发路径跨越资源边界。
     */
    private synchronized void beginClose(AutoCloseable resource) {
        ShutdownDeadline.ForcedTerminationException forced = forcedTermination.get();
        if (forced != null) throw forced;
        activeClose = resource;
    }

    /**
     * 清除已准入关闭标记，但不改变已经作出的强制升级决定。
     */
    private synchronized void endClose(AutoCloseable resource) {
        if (activeClose == resource) activeClose = null;
    }

    /**
     * 生命周期被强制升级后，在下一个资源边界立即停止逆序清理。
     */
    private void throwIfForced() {
        ShutdownDeadline.ForcedTerminationException forced = forcedTermination.get();
        if (forced != null) throw forced;
    }

    /**
     * 向支持 Deadline 的资源传递同一绝对边界；普通 AutoCloseable 隔离在虚拟线程中，
     * 即使忽略中断也不能延长外层进程预算。
     */
    private static void closeWithinDeadline(AutoCloseable resource, ShutdownDeadline deadline, String owner) {
        if (deadline.expired()) {
            throw forcedFailure("forced termination: " + owner + " deadline expired", null);
        }
        CompletableFuture<Void> close = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().name("ja-shutdown-close-").start(() -> {
            try {
                if (resource instanceof DeadlineCloseable aware) {
                    aware.closeAt(deadline.deadlineNanos());
                } else {
                    resource.close();
                }
                close.complete(null);
            } catch (Throwable failure) {
                close.completeExceptionally(failure);
            }
        });
        try {
            deadline.await(close, owner);
        } catch (RuntimeException failure) {
            worker.interrupt();
            throw failure;
        }
    }

    /**
     * 只移除已经成功关闭的 Owner；栅栏失败时保留全部依赖资源的所有权证据。
     */
    private synchronized void removeClosed(AutoCloseable resource) {
        resources.remove(resource);
        identities.remove(resource);
        if (resource == shutdownFence) shutdownFence = null;
    }

    /**
     * 向并发调用方重放同一个关闭结果，不允许启动第二轮清理。
     */
    private static void awaitCompletion(CompletableFuture<Void> completion, ShutdownDeadline deadline, String owner) {
        try {
            deadline.await(completion, owner);
        } catch (RuntimeException failure) {
            throw failure;
        }
    }

    /**
     * 复用进程级标记，使每一层关闭都以一致类型报告强制终止。
     */
    private static ShutdownDeadline.ForcedTerminationException forcedFailure(String message, Throwable cause) {
        return ShutdownDeadline.forced(message, cause);
    }

    /**
     * 直接释放关闭后迟到的资源，不把它准入已经排空的所有权栈。
     */
    private static void closeLateResource(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception closeFailure) {
            throw closeFailure instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException("late runtime resource close failed", closeFailure);
        }
    }
}
