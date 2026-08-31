// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.runtime;

import io.github.kongweiguang.ja.catalog.adapter.out.mcp.session.McpSessionFactory;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpCloseResult;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpDeadline;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpLimits;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpServerDefinition;
import io.github.kongweiguang.ja.foundation.concurrent.BoundedVirtualExecutor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 协调准入、Worker 所有权、Session 清理与共享 Runtime 关闭结果。
 */
final class McpRuntimeLifecycle {
    private final Map<String, McpServerState> servers;
    private final ExecutorService executor;
    private final ExecutorService cleanupExecutor;
    private final McpDeadline deadline;
    private final McpLimits limits;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<McpCloseResult> closeCompletion = new CompletableFuture<>();

    /**
     * 打开传输前创建独立的调用/清理双门虚拟执行器，阻塞调用不能占用清理容量或平台线程。
     */
    McpRuntimeLifecycle(
            List<McpServerDefinition> definitions,
            McpLimits limits,
            McpSessionFactory sessionFactory,
            McpDeadline deadline) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(sessionFactory, "sessionFactory");
        Map<String, McpServerDefinition> uniqueDefinitions = new LinkedHashMap<>();
        definitions.stream()
                .sorted(Comparator.comparing(McpServerDefinition::id))
                .forEach(definition -> {
                    if (uniqueDefinitions.putIfAbsent(definition.id(), definition) != null) {
                        throw new IllegalArgumentException("mcp_server_id_conflict");
                    }
                });
        int parallelism = Math.max(1, Math.min(8, definitions.size()));
        this.executor = new BoundedVirtualExecutor("ja-mcp-call-", parallelism, 64);
        this.cleanupExecutor = new BoundedVirtualExecutor(
                "ja-mcp-cleanup-", parallelism, Math.max(64, definitions.size()));
        Map<String, McpServerState> holders = new LinkedHashMap<>();
        uniqueDefinitions.values().forEach(definition -> holders.put(definition.id(), new McpServerState(
                definition, sessionFactory, deadline, cleanupExecutor, closed)));
        this.servers = Map.copyOf(holders);
    }

    /**
     * 返回供 Gateway 目录及路由校验使用的不可变服务 Map。
     */
    Map<String, McpServerState> servers() {
        return servers;
    }

    /**
     * 返回此 Runtime 代际独占的调用 Executor。
     */
    ExecutorService executor() {
        return executor;
    }

    /**
     * shutdown 开始后禁止发现、初始化与调用准入。
     */
    void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("mcp_runtime_closed");
        }
    }

    /**
     * 在公平锁下运行一个服务操作，并消耗调用方剩余预算。
     */
    <T> T executeServerWithin(
            McpServerState holder,
            long deadlineNanos,
            String timeoutCode,
            java.util.concurrent.Callable<T> operation) {
        long remaining = remainingNanos(deadlineNanos);
        if (remaining <= 0) {
            throw new IllegalStateException(timeoutCode);
        }
        Future<T> future = executor.submit(() -> {
            holder.lockInterruptibly();
            try {
                return operation.call();
            } finally {
                holder.unlock();
            }
        });
        remaining = remainingNanos(deadlineNanos);
        if (remaining <= 0) {
            future.cancel(true);
            IllegalStateException failure = new IllegalStateException(timeoutCode);
            closeSessionAfterFailure(holder, failure, deadlineNanos);
            throw failure;
        }
        try {
            T result = future.get(remaining, TimeUnit.NANOSECONDS);
            if (remainingNanos(deadlineNanos) <= 0) {
                throw new TimeoutException(timeoutCode);
            }
            return result;
        } catch (TimeoutException timeout) {
            future.cancel(true);
            IllegalStateException failure = new IllegalStateException(timeoutCode);
            closeSessionAfterFailure(holder, failure, deadlineNanos);
            throw failure;
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            IllegalStateException failure = new IllegalStateException("mcp_operation_interrupted");
            closeSessionAfterFailure(holder, failure, deadlineNanos);
            throw failure;
        } catch (ExecutionException execution) {
            Throwable cause = unwrap(execution);
            if (cause instanceof Error fatal) {
                throw fatal;
            }
            RuntimeException failure = cause instanceof RuntimeException runtimeFailure
                    ? runtimeFailure
                    : new IllegalStateException("mcp_operation_failed");
            closeSessionAfterFailure(holder, failure, deadlineNanos);
            throw failure;
        }
    }

    /**
     * 先使失败服务失效，再仅使用操作剩余聚合预算完成清理。
     */
    private void closeSessionAfterFailure(
            McpServerState holder, RuntimeException primary, long deadlineNanos) {
        McpCloseResult result = awaitSessionClose(holder.beginCloseSession(deadlineNanos), deadlineNanos);
        appendCloseFailures(primary, result);
    }

    /**
     * 等待已有清理结果前先分离全部已初始化服务。
     */
    void closeAllSessionsAfterFailure(RuntimeException primary, long deadlineNanos) {
        List<CompletableFuture<McpCloseResult>> closures = servers.values().stream()
                .map(holder -> holder.beginCloseSession(deadlineNanos))
                .toList();
        for (CompletableFuture<McpCloseResult> closure : closures) {
            appendCloseFailures(primary, awaitSessionClose(closure, deadlineNanos));
        }
    }

    /**
     * 只复制稳定清理代码，不保留传输异常或敏感消息。
     */
    static void appendCloseFailures(RuntimeException primary, McpCloseResult closeResult) {
        for (String code : closeResult.failures()) {
            primary.addSuppressed(new IllegalStateException(code));
        }
    }

    /**
     * 只在调用方剩余预算内等待；共享 Completion 保留迟到清理状态。
     */
    McpCloseResult awaitSessionClose(
            CompletableFuture<McpCloseResult> completion, long deadlineNanos) {
        long remaining = remainingNanos(deadlineNanos);
        if (remaining <= 0) {
            return McpCloseResult.failure("mcp_session_close_timeout");
        }
        try {
            return completion.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            return McpCloseResult.failure("mcp_session_close_timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return McpCloseResult.failure("mcp_runtime_close_interrupted");
        } catch (ExecutionException impossible) {
            return McpCloseResult.failure("mcp_session_close_failed");
        }
    }

    /**
     * 发布一个共享关闭结果，使并发生命周期调用方重放相同结果。
     */
    void close() {
        if (closed.compareAndSet(false, true)) {
            McpCloseResult result;
            try {
                result = performClose();
            } catch (RuntimeException unexpected) {
                result = McpCloseResult.failure("mcp_runtime_close_failed");
            }
            closeCompletion.complete(result);
        }
        McpCloseResult result = awaitCloseCompletion(closeCompletion);
        if (result.failed()) {
            throw result.asRuntimeFailure();
        }
    }

    /**
     * 协调一次有界 Runtime 关闭，并将残留 Worker 记录为失败关闭证据。
     */
    private McpCloseResult performClose() {
        long deadlineNanos = deadline.phaseDeadline(limits.closeTimeout());
        List<CompletableFuture<McpCloseResult>> sessionClosures = servers.values().stream()
                .map(holder -> holder.beginCloseSession(deadlineNanos))
                .toList();
        cleanupExecutor.shutdown();
        executor.shutdownNow();
        List<String> failures = new ArrayList<>();
        for (CompletableFuture<McpCloseResult> sessionClosure : sessionClosures) {
            failures.addAll(awaitSessionClose(sessionClosure, deadlineNanos).failures());
        }
        awaitExecutor(executor, deadlineNanos, "mcp_executor_close_timeout", failures);
        awaitExecutor(cleanupExecutor, deadlineNanos, "mcp_cleanup_executor_close_timeout", failures);
        if (!cleanupExecutor.isTerminated()) {
            cleanupExecutor.shutdownNow();
        }
        return new McpCloseResult(failures);
    }

    /**
     * 跨越中断继续等待，确保共享关闭屏障恰好完成一次。
     */
    private void awaitExecutor(
            ExecutorService ownedExecutor, long deadlineNanos, String timeoutCode, List<String> failures) {
        boolean interrupted = false;
        try {
            while (!ownedExecutor.isTerminated()) {
                long remaining = remainingNanos(deadlineNanos);
                if (remaining <= 0) {
                    failures.add(timeoutCode);
                    return;
                }
                try {
                    if (!ownedExecutor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                        failures.add(timeoutCode);
                        return;
                    }
                } catch (InterruptedException interruption) {
                    interrupted = true;
                    if (!failures.contains("mcp_runtime_close_interrupted")) {
                        failures.add("mcp_runtime_close_interrupted");
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 即使等待方被中断，也让并发关闭调用方共享同一结果。
     */
    private static McpCloseResult awaitCloseCompletion(CompletableFuture<McpCloseResult> completion) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return completion.get();
                } catch (InterruptedException interruption) {
                    interrupted = true;
                } catch (ExecutionException impossible) {
                    return McpCloseResult.failure("mcp_runtime_close_failed");
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 从创建 Deadline 的同一单调时钟计算剩余聚合预算。
     */
    private long remainingNanos(long deadlineNanos) {
        return deadline.remainingNanos(deadlineNanos);
    }

    /**
     * 解包异步异常，同时保留本地失败分类边界。
     */
    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
               && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
