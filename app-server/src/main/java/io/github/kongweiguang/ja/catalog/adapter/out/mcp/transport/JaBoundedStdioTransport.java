// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.transport;

import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpDeadline;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpLimits;
import io.github.kongweiguang.ja.platform.windows.WindowsJobObject;
import io.github.kongweiguang.ja.platform.windows.WindowsProcessLauncher;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Ja 独占的 stdio 传输，对分帧、队列、环境、Deadline 和 Windows 进程树清理施加边界。
 * 本实现有意不继承 SDK stdio 传输，因为后者会保留无界 Reactor Sink 和父进程环境变量。
 */
public final class JaBoundedStdioTransport extends BoundedMcpTransport {
    private final List<String> command;
    private final java.nio.file.Path workingDirectory;
    private final Map<String, String> environment;
    private final McpLimits limits;
    private final McpDeadline deadline;
    private final ArrayBlockingQueue<McpSchema.JSONRPCMessage> outbound;
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicInteger stderrBytes = new AtomicInteger();
    private final ExecutorService ioExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("ja-mcp-stdio-", 0).factory());
    private volatile Process process;
    private volatile WindowsJobObject job;

    /**
     * 复制所有启动输入，防止调用方在构造后修改子进程边界。
     */
    public JaBoundedStdioTransport(
            List<String> command,
            java.nio.file.Path workingDirectory,
            Map<String, String> environment,
            List<String> protocolVersions,
            McpJsonMapper jsonMapper,
            McpLimits limits,
            McpDeadline deadline) {
        super(jsonMapper, protocolVersions);
        this.command = List.copyOf(command);
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        this.environment = Map.copyOf(environment);
        this.limits = Objects.requireNonNull(limits, "limits");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.outbound = new ArrayBlockingQueue<>(limits.outboundQueueCapacity());
    }

    /**
     * 通过挂起式 Native Job 准入启动子进程，再启动有界流泵，关闭启动与归属竞态。
     */
    @Override
    public Mono<Void> connect(
            Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        Objects.requireNonNull(handler, "handler");
        return Mono.fromRunnable(() -> {
            synchronized (lifecycleLock) {
                if (closing.get() || process != null) {
                    throw new IllegalStateException("mcp_stdio_already_started");
                }
                WindowsJobObject admittedJob = null;
                try {
                    admittedJob = WindowsJobObject.create();
                    Process admittedProcess = WindowsProcessLauncher.launch(
                            command, workingDirectory, environment, admittedJob);
                    job = admittedJob;
                    process = admittedProcess;
                } catch (IOException failure) {
                    if (admittedJob != null) {
                        try {
                            admittedJob.close();
                        } catch (WindowsJobObject.WindowsJobObjectException cleanupFailure) {
                            failure.addSuppressed(cleanupFailure);
                        }
                    }
                    throw new IllegalStateException("mcp_stdio_start_failed", failure);
                }
                ioExecutor.execute(() -> readStdout(handler));
                ioExecutor.execute(this::writeStdin);
                ioExecutor.execute(this::drainStderr);
                ioExecutor.execute(this::watchExit);
            }
        });
    }

    /**
     * 同步拒绝过载，禁止保留无界出站积压。
     */
    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        Objects.requireNonNull(message, "message");
        return Mono.fromRunnable(() -> {
            if (closing.get() || !outbound.offer(message)) {
                throw new IllegalStateException("mcp_stdio_outbound_queue_full");
            }
        });
    }

    /**
     * 每次读取一行有界 UTF-8 JSON-RPC，并直接交给 SDK Session。
     */
    private void readStdout(
            Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        try (InputStream input = process.getInputStream()) {
            byte[] line;
            while (!closing.get() && (line = readBoundedLine(input, limits.maxMessageBytes())) != null) {
                McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(
                        jsonMapper, new String(line, StandardCharsets.UTF_8));
                handler.apply(Mono.just(message)).block(
                        deadline.remaining(limits.requestTimeout(), "mcp_stdio_deadline_elapsed"));
            }
        } catch (IOException | RuntimeException failure) {
            if (!closing.get()) {
                failTransport(failure);
            }
        }
    }

    /**
     * 通过单写入器串行化，防止调用线程间的换行帧交错。
     */
    private void writeStdin() {
        try (OutputStream output = process.getOutputStream()) {
            while (!closing.get()) {
                McpSchema.JSONRPCMessage message = outbound.poll(100, TimeUnit.MILLISECONDS);
                if (message == null) {
                    continue;
                }
                byte[] encoded = jsonMapper.writeValueAsBytes(message);
                if (encoded.length > limits.maxMessageBytes() || containsLineBreak(encoded)) {
                    throw new IOException("mcp_stdio_outbound_message_invalid");
                }
                output.write(encoded);
                output.write('\n');
                output.flush();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException failure) {
            if (!closing.get()) {
                failTransport(failure);
            }
        }
    }

    /**
     * 排空 stderr 但不保留内容，并终止超过诊断预算的对端。
     */
    private void drainStderr() {
        try (InputStream error = process.getErrorStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while (!closing.get() && (count = error.read(buffer)) >= 0) {
                if (stderrBytes.addAndGet(count) > limits.maxStderrBytes()) {
                    throw new IOException("mcp_stdio_stderr_limit");
                }
            }
        } catch (IOException failure) {
            if (!closing.get()) {
                failTransport(failure);
            }
        }
    }

    /**
     * 将意外子进程退出转换为传输失败，但不暴露命令或 stderr 文本。
     */
    private void watchExit() {
        try {
            int exit = process.waitFor();
            if (!closing.get() && exit != 0) {
                failTransport(new IOException("mcp_stdio_server_exited"));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            // 主动关闭会并发释放 Windows 进程句柄；此时 watcher 的读取失败属于已完成清理，不能泄漏到未捕获异常处理器。
            if (!closing.get()) {
                failTransport(failure);
            }
        }
    }

    /**
     * 每次分帧或 IO 失败只通知 SDK 一次，并立即开始进程清理。
     */
    private void failTransport(Throwable failure) {
        try {
            reportTransportFailure(failure);
        } finally {
            closeTransport();
        }
    }

    /**
     * 幂等终止 Job Object，并在公共模板下于关闭预算内等待精确根进程退出。
     */
    @Override
    @SuppressWarnings("PMD.CloseResource")
    protected void closeTransport() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        Process current;
        WindowsJobObject currentJob;
        synchronized (lifecycleLock) {
            current = process;
            currentJob = job;
        }
        RuntimeException cleanupFailure = null;
        long closeDeadline = deadline.phaseDeadline(limits.closeTimeout());
        if (currentJob != null) {
            try {
                if (current == null || current.isAlive()) {
                    currentJob.terminate();
                } else {
                    currentJob.closeAfterRootExit();
                }
            } catch (WindowsJobObject.WindowsJobObjectException failure) {
                cleanupFailure = new IllegalStateException("mcp_stdio_job_cleanup_failed", failure);
            }
        }
        if (current != null) {
            try {
                current.getOutputStream().close();
            } catch (IOException ignored) {
                // stdin 关闭仅尽力执行，进程树终止仍是权威清理边界。
            }
            waitForExit(current, closeDeadline);
            if (current.isAlive()) {
                current.destroyForcibly();
                waitForExit(current, closeDeadline);
            }
            closeStreams(current);
        }
        if (currentJob != null && !currentJob.isClosed()) {
            try {
                currentJob.close();
                cleanupFailure = null;
            } catch (WindowsJobObject.WindowsJobObjectException failure) {
                if (cleanupFailure == null) {
                    cleanupFailure = new IllegalStateException("mcp_stdio_job_cleanup_failed", failure);
                } else {
                    cleanupFailure.addSuppressed(failure);
                }
            }
        }
        ioExecutor.shutdownNow();
        awaitIoTermination(closeDeadline);
        if (current != null && current.isAlive() || currentJob != null && !currentJob.isClosed()) {
            IllegalStateException orphan = new IllegalStateException("mcp_stdio_orphan_cleanup_failed");
            if (cleanupFailure != null) {
                orphan.addSuppressed(cleanupFailure);
            }
            throw orphan;
        }
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }

    /**
     * 将父管道和进程句柄释放委派给 Native Launcher 所有者。
     */
    private static void closeStreams(Process process) {
        try {
            WindowsProcessLauncher.close(process);
        } catch (IOException ignored) {
            // Job 终止仍是权威清理边界，句柄释放仅尽力执行。
        }
    }

    /**
     * 即使 Windows 子进程忽略正常终止，也不等待超过清理预算。
     */
    private void waitForExit(Process process, long closeDeadline) {
        try {
            process.waitFor(deadline.remainingNanos(closeDeadline), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 外部调用方只在关闭预算内等待；IO Worker 不得等待自身退出。
     */
    private void awaitIoTermination(long closeDeadline) {
        if (Thread.currentThread().getName().startsWith("ja-mcp-stdio-")) {
            return;
        }
        try {
            ioExecutor.awaitTermination(deadline.remainingNanos(closeDeadline), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 解码前按字节限制单个 stdout 帧，避免超大 UTF-8 分配。
     */
    private static byte[] readBoundedLine(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(maximum, 8192));
        int next;
        while ((next = input.read()) >= 0) {
            if (next == '\n') {
                return buffer.toByteArray();
            }
            if (next == '\r') {
                continue;
            }
            if (buffer.size() >= maximum) {
                throw new IOException("mcp_stdio_message_limit");
            }
            buffer.write(next);
        }
        return buffer.size() == 0 ? null : buffer.toByteArray();
    }

    /**
     * 确保编码后的 JSON-RPC 对象严格占用一个 stdio 帧。
     */
    private static boolean containsLineBreak(byte[] encoded) {
        for (byte value : encoded) {
            if (value == '\r' || value == '\n') {
                return true;
            }
        }
        return false;
    }
}
