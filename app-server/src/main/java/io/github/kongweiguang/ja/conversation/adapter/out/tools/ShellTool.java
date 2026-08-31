// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 在冻结的原生 Shell Profile 中执行原始 command，并负责有界输出与进程树清理。 */
final class ShellTool extends ToolSupport {
    private static final int MAX_OUTPUT_BYTES = 256_000;
    private static final long POLL_MILLIS = 40;
    private final ShellProfile profile;

    /** Tool 描述和执行器共同引用同一 Profile，禁止模型契约与实际 Shell 漂移。 */
    ShellTool(ShellProfile profile) {
        super(new io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec(
                "shell", Objects.requireNonNull(profile, "profile").toolDescription(),
                objectSchema(Map.of("command", property("string", "Command in the declared shell dialect.")),
                        java.util.List.of("command"))));
        this.profile = profile;
    }

    /**
     * 只启动 Profile 指定的唯一 Shell；失败原样保留 stdout/stderr、exit code，不做翻译或重试。
     */
    @Override
    @SuppressWarnings("PMD.CloseResource")
    ToolResult executeChecked(Invocation invocation, ExecutionContext context, CancellationToken token)
            throws Exception {
        String command = string(invocation, "command", 256_000);
        long remainingMillis = Math.max(0, Duration.between(Instant.now(), context.deadline()).toMillis());
        if (remainingMillis == 0) {
            return new ToolResult(ToolOutcome.CANCELLED, "", Optional.empty(), "shell_timeout");
        }
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(profile.commandLine(command))
                    .directory(context.workspaceRoot().toFile());
            builder.environment().clear();
            builder.environment().putAll(profile.environment());
            process = builder.start();
        } catch (IOException failure) {
            return new ToolResult(ToolOutcome.FAILED, "", Optional.empty(), "shell_not_available");
        }
        AtomicInteger remaining = new AtomicInteger(MAX_OUTPUT_BYTES);
        AtomicBoolean truncated = new AtomicBoolean();
        ExecutorService readers = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("ja-shell-output-", 0).factory());
        Future<byte[]> stdout = readers.submit(() -> drain(process.getInputStream(), remaining, truncated));
        Future<byte[]> stderr = readers.submit(() -> drain(process.getErrorStream(), remaining, truncated));
        CancellationToken.Registration registration = token.onCancellation(() -> terminateTree(process));
        boolean timedOut = false;
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remainingMillis);
            while (process.isAlive() && !token.isCancellationRequested()) {
                long nanos = deadline - System.nanoTime();
                if (nanos <= 0) {
                    timedOut = true;
                    break;
                }
                process.waitFor(Math.min(POLL_MILLIS, TimeUnit.NANOSECONDS.toMillis(nanos) + 1),
                        TimeUnit.MILLISECONDS);
            }
            if (process.isAlive()) terminateTree(process);
            process.waitFor(3, TimeUnit.SECONDS);
            byte[] out = await(stdout);
            byte[] err = await(stderr);
            String content = format(out, err);
            if (token.isCancellationRequested()) {
                return new ToolResult(ToolOutcome.CANCELLED, content,
                        Optional.of(JsonObjects.builder().putBoolean("truncated", truncated.get()).build()),
                        "shell_cancelled");
            }
            if (timedOut) {
                return new ToolResult(ToolOutcome.CANCELLED, content,
                        Optional.of(JsonObjects.builder().putBoolean("truncated", truncated.get()).build()),
                        "shell_timeout");
            }
            int exitCode = process.exitValue();
            return new ToolResult(exitCode == 0 ? ToolOutcome.SUCCEEDED : ToolOutcome.FAILED,
                    content, Optional.of(JsonObjects.builder().putNumber("exit_code", exitCode)
                            .putBoolean("truncated", truncated.get()).build()),
                    exitCode == 0 ? null : "shell_exit_nonzero");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            terminateTree(process);
            throw new CancellationException("shell_interrupted");
        } finally {
            registration.close();
            if (process.isAlive()) terminateTree(process);
            readers.shutdownNow();
        }
    }

    /** 持续排空管道并共享总字节上限，防止子进程因输出背压而死锁。 */
    private static byte[] drain(InputStream input, AtomicInteger remaining, AtomicBoolean truncated)
            throws IOException {
        ByteArrayOutputStream retained = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            int keep = reserve(remaining, read);
            if (keep > 0) retained.write(buffer, 0, keep);
            if (keep < read) truncated.set(true);
        }
        return retained.toByteArray();
    }

    /** 通过 CAS 分配 stdout/stderr 的共享容量，使计数不会在并行读取时下溢。 */
    private static int reserve(AtomicInteger remaining, int requested) {
        while (true) {
            int available = remaining.get();
            if (available <= 0) return 0;
            int granted = Math.min(available, requested);
            if (remaining.compareAndSet(available, available - granted)) return granted;
        }
    }

    /**
     * 先终止当前可见后代再终止根进程；这是进程清理而非权限 Sandbox，不限制命令可访问范围。
     */
    private static void terminateTree(Process process) {
        process.descendants().forEach(handle -> {
            if (handle.isAlive()) handle.destroy();
        });
        process.destroy();
        try {
            if (!process.waitFor(300, TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(handle -> {
                    if (handle.isAlive()) handle.destroyForcibly();
                });
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /** 在有界清理后取得 reader 结果，并把异常归约到 Tool 执行边界。 */
    private static byte[] await(Future<byte[]> result)
            throws InterruptedException, ExecutionException, IOException {
        try {
            return result.get(3, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            result.cancel(true);
            throw new IOException("shell_output_cleanup_failed", timeout);
        }
    }

    /** 分区保留两个流，便于模型准确区分普通输出与真实错误。 */
    private static String format(byte[] stdout, byte[] stderr) {
        String out = new String(stdout, StandardCharsets.UTF_8);
        String err = new String(stderr, StandardCharsets.UTF_8);
        if (err.isEmpty()) return out;
        if (out.isEmpty()) return "[stderr]\n" + err;
        return "[stdout]\n" + out + "\n[stderr]\n" + err;
    }
}
