// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.runtime;

import io.github.kongweiguang.ja.catalog.adapter.out.mcp.session.McpSession;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.session.McpSessionFactory;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpCloseResult;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpDeadline;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpServerDefinition;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 拥有一个配置服务 Session、串行调用锁及分离清理。
 */
final class McpServerState {
    private final McpServerDefinition definition;
    private final McpSessionFactory sessionFactory;
    private final McpDeadline deadline;
    private final ExecutorService cleanupExecutor;
    private final AtomicBoolean runtimeClosed;
    private final ReentrantLock callLock = new ReentrantLock(true);
    private McpSession session;
    private CompletableFuture<McpCloseResult> sessionCloseCompletion =
            CompletableFuture.completedFuture(McpCloseResult.success());

    /**
     * 在真正需要服务 Session 前只保留不含 Secret 的定义。
     */
    McpServerState(
            McpServerDefinition definition,
            McpSessionFactory sessionFactory,
            McpDeadline deadline,
            ExecutorService cleanupExecutor,
            AtomicBoolean runtimeClosed) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.cleanupExecutor = Objects.requireNonNull(cleanupExecutor, "cleanupExecutor");
        this.runtimeClosed = Objects.requireNonNull(runtimeClosed, "runtimeClosed");
    }

    /**
     * 返回用于目录 Tool 与调用路由的不可变服务身份。
     */
    McpServerDefinition definition() {
        return definition;
    }

    /**
     * 获取逐服务公平锁，同时允许调用方 Deadline 取消等待。
     */
    void lockInterruptibly() throws InterruptedException {
        callLock.lockInterruptibly();
    }

    /**
     * 释放发现、初始化或调用操作获取的锁。
     */
    void unlock() {
        callLock.unlock();
    }

    /**
     * 初始化前发布正在打开的 Session，使取消能够分离并中止进行中的 IO；
     * Runtime 关闭标记防止迟到的 open 逃逸所有权边界。
     */
    McpSession session() {
        requireOpen();
        McpSession current;
        synchronized (this) {
            current = session;
        }
        if (current != null) {
            return current;
        }
        McpSession opened = sessionFactory.open(definition, deadline);
        synchronized (this) {
            if (runtimeClosed.get()) {
                current = null;
            } else {
                session = opened;
                sessionCloseCompletion = CompletableFuture.completedFuture(McpCloseResult.success());
                current = opened;
            }
        }
        if (current == null) {
            try {
                opened.close();
            } catch (RuntimeException closeFailure) {
                throw new IllegalStateException("mcp_session_close_failed");
            }
            throw new IllegalStateException("mcp_runtime_closed");
        }
        try {
            opened.initialize();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("mcp_initialize_failed", failure);
        }
        synchronized (this) {
            if (session != opened || runtimeClosed.get()) {
                throw new IllegalStateException("mcp_session_invalidated");
            }
        }
        return opened;
    }

    /**
     * 原子分离当前传输，并返回共享关闭 Completion。
     */
    @SuppressWarnings("PMD.CloseResource")
    CompletableFuture<McpCloseResult> beginCloseSession(long deadlineNanos) {
        McpSession current;
        CompletableFuture<McpCloseResult> completion;
        synchronized (this) {
            if (session == null) {
                return sessionCloseCompletion;
            }
            current = session;
            session = null;
            completion = new CompletableFuture<>();
            sessionCloseCompletion = completion;
        }
        scheduleSessionClose(current, completion, deadlineNanos);
        return completion;
    }

    /**
     * 调度一次所有权明确的关闭，并与调用使用的同一绝对 Deadline 竞争。
     */
    private void scheduleSessionClose(
            McpSession session, CompletableFuture<McpCloseResult> completion, long deadlineNanos) {
        long remaining = deadline.remainingNanos(deadlineNanos);
        if (remaining <= 0) {
            completion.complete(McpCloseResult.failure("mcp_session_close_timeout"));
        } else {
            CompletableFuture.delayedExecutor(remaining, TimeUnit.NANOSECONDS).execute(
                    () -> completion.complete(McpCloseResult.failure("mcp_session_close_timeout")));
        }
        try {
            cleanupExecutor.execute(() -> {
                try {
                    session.close();
                    completion.complete(McpCloseResult.success());
                } catch (RuntimeException closeFailure) {
                    completion.complete(McpCloseResult.failure("mcp_session_close_failed"));
                }
            });
        } catch (RuntimeException rejected) {
            completion.complete(McpCloseResult.failure("mcp_cleanup_executor_unavailable"));
        }
    }

    /**
     * Runtime shutdown 开始后拒绝打开新 Session。
     */
    private void requireOpen() {
        if (runtimeClosed.get()) {
            throw new IllegalStateException("mcp_runtime_closed");
        }
    }
}
