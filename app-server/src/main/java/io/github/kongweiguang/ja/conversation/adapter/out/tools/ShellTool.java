// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.foundation.json.JsonValue;
import io.github.kongweiguang.ja.platform.windows.WindowsJobObject;
import io.github.kongweiguang.ja.platform.windows.WindowsProcessLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 在冻结的原生 Shell Profile 中执行原始 command，并负责有界输出与进程树清理。 */
final class ShellTool extends ToolSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShellTool.class);
    static final int DEFAULT_TIMEOUT_MILLIS = 120_000;
    static final int MIN_TIMEOUT_MILLIS = 100;
    static final int MAX_TIMEOUT_MILLIS = 600_000;
    private static final int MAX_OUTPUT_BYTES = 256_000;
    private static final long POLL_MILLIS = 40;
    private static final long PROCESS_EXIT_GRACE_MILLIS = 3_000;
    private static final long READER_EXIT_GRACE_MILLIS = 3_000;
    private final ShellProfile profile;

    /** Tool 描述和执行器共同引用同一 Profile，并公开唯一的有界超时输入，禁止隐式无限执行。 */
    ShellTool(ShellProfile profile) {
        super(new io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec(
                "shell", Objects.requireNonNull(profile, "profile").toolDescription(),
                objectSchema(Map.of(
                                "command", property("string", "Command in the declared shell dialect."),
                                "timeout_ms", timeoutProperty()),
                        List.of("command"))));
        this.profile = profile;
    }

    /**
     * 先建立平台进程树所有权并立即关闭 stdin，再按 Tool 与 Turn 较早 Deadline 执行；所有返回路径
     * 都经过同一清理屏障，因此 Agent Loop 不会观察到仍持有管道或子进程的已完成结果。
     */
    @Override
    @SuppressWarnings("PMD.CloseResource")
    ToolResult executeChecked(Invocation invocation, ExecutionContext context, CancellationToken token) {
        String command = string(invocation, "command", 256_000);
        int requestedTimeout = integer(invocation, "timeout_ms", DEFAULT_TIMEOUT_MILLIS,
                MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS);
        long remainingTurnMillis = Math.max(0, Duration.between(Instant.now(), context.deadline()).toMillis());
        long executionMillis = Math.min(requestedTimeout, remainingTurnMillis);
        if (executionMillis == 0) {
            return result(ToolOutcome.CANCELLED, "", false, "shell_timeout");
        }

        ShellProcessOwner owner;
        try {
            owner = ShellProcessOwner.launch(profile, command, context.workspaceRoot());
        } catch (IOException failure) {
            return launchFailure(failure);
        }

        ExecutorService readers = null;
        CancellationToken.Registration registration = CancellationToken.Registration.noop();
        ToolResult result;
        boolean interrupted = false;
        IOException boundaryCleanupFailure = null;
        try {
            registration = token.onCancellation(owner::requestTermination);
            if (token.isCancellationRequested()) {
                result = result(ToolOutcome.CANCELLED, "", false, "shell_cancelled");
            } else {
                try {
                    owner.closeInput();
                    readers = newReaders();
                    result = executeOwned(owner, readers, executionMillis, token);
                } catch (IOException inputFailure) {
                    result = token.isCancellationRequested()
                            ? result(ToolOutcome.CANCELLED, "", false, "shell_cancelled")
                            : diagnosedFailure("shell_stdin_close_failed", inputFailure);
                } catch (InterruptedException executionInterrupted) {
                    interrupted = true;
                    owner.requestTermination();
                    result = result(ToolOutcome.CANCELLED, "", false, "shell_cancelled");
                }
            }
        } catch (RuntimeException executionFailure) {
            result = diagnosedFailure("shell_execution_failed", executionFailure);
        } finally {
            try {
                registration.close();
            } catch (RuntimeException registrationFailure) {
                boundaryCleanupFailure = new IOException("shell_cancellation_registration_close_failed",
                        registrationFailure);
            }
            boundaryCleanupFailure = merge(boundaryCleanupFailure, owner.close());
            boundaryCleanupFailure = merge(boundaryCleanupFailure, closeReaders(readers));
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (boundaryCleanupFailure != null) {
            return diagnosedFailure("shell_cleanup_failed", boundaryCleanupFailure,
                    result.content(), result.structuredContent());
        }
        return result;
    }

    /**
     * 在 stdin 已收到 EOF 后并行排空双流；根进程结束时也先关闭进程树 owner，避免后台后代继续
     * 持有继承管道而让 reader 永久等待。
     */
    private static ToolResult executeOwned(
            ShellProcessOwner owner,
            ExecutorService readers,
            long executionMillis,
            CancellationToken token) throws InterruptedException {
        AtomicInteger remaining = new AtomicInteger(MAX_OUTPUT_BYTES);
        AtomicBoolean truncated = new AtomicBoolean();
        Future<byte[]> stdout = readers.submit(() -> drain(owner.process().getInputStream(), remaining, truncated));
        Future<byte[]> stderr = readers.submit(() -> drain(owner.process().getErrorStream(), remaining, truncated));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(executionMillis);
        boolean timedOut = false;
        while (owner.process().isAlive() && !token.isCancellationRequested()) {
            long nanos = deadline - System.nanoTime();
            if (nanos <= 0) {
                timedOut = true;
                break;
            }
            owner.process().waitFor(Math.min(POLL_MILLIS,
                    Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos))), TimeUnit.MILLISECONDS);
        }

        if (owner.process().isAlive()) {
            owner.requestTermination();
        } else {
            owner.releaseAfterRootExit();
        }
        boolean rootExited = owner.process().waitFor(PROCESS_EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        if (!rootExited) {
            owner.requestTermination();
            return diagnosedFailure("shell_cleanup_failed",
                    new IOException("shell_process_exit_cleanup_timeout"));
        }

        byte[] out;
        byte[] err;
        try {
            out = await(stdout);
            err = await(stderr);
        } catch (IOException | ExecutionException readerFailure) {
            stdout.cancel(true);
            stderr.cancel(true);
            if (token.isCancellationRequested()) {
                return result(ToolOutcome.CANCELLED, "", truncated.get(), "shell_cancelled");
            }
            if (timedOut) {
                return result(ToolOutcome.CANCELLED, "", truncated.get(), "shell_timeout");
            }
            return diagnosedFailure("shell_output_failed", readerFailure, "",
                    Optional.of(JsonObjects.builder().putBoolean("truncated", truncated.get()).build()));
        }

        String content = format(out, err);
        if (token.isCancellationRequested()) {
            return result(ToolOutcome.CANCELLED, content, truncated.get(), "shell_cancelled");
        }
        if (timedOut) {
            return result(ToolOutcome.CANCELLED, content, truncated.get(), "shell_timeout");
        }
        int exitCode = owner.process().exitValue();
        return new ToolResult(exitCode == 0 ? ToolOutcome.SUCCEEDED : ToolOutcome.FAILED,
                content, Optional.of(JsonObjects.builder().putNumber("exit_code", exitCode)
                        .putBoolean("truncated", truncated.get()).build()),
                exitCode == 0 ? null : "shell_exit_nonzero");
    }

    /** 创建每次调用独占的虚拟 reader；它只读取两个有界管道，不进入共享全局线程池。 */
    private static ExecutorService newReaders() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("ja-shell-output-", 0).factory());
    }

    /** 持续排空管道并共享总字节上限；try-with-resources 保证 reader 自身失败时也释放流视图。 */
    private static byte[] drain(InputStream input, AtomicInteger remaining, AtomicBoolean truncated)
            throws IOException {
        try (input; ByteArrayOutputStream retained = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                int keep = reserve(remaining, read);
                if (keep > 0) retained.write(buffer, 0, keep);
                if (keep < read) truncated.set(true);
            }
            return retained.toByteArray();
        }
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

    /** 在进程树清理后有界取得 reader 结果，超时只取消本 reader，不扩大 Tool Deadline。 */
    private static byte[] await(Future<byte[]> result)
            throws InterruptedException, ExecutionException, IOException {
        try {
            return result.get(READER_EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            result.cancel(true);
            throw new IOException("shell_output_cleanup_failed", timeout);
        }
    }

    /**
     * 进程 owner 已先关闭管道，这里再中断 reader 并有界等待；Windows CancelIoEx 因而会唤醒
     * 已进入本地读取的线程，不能仅依赖 Future.cancel。
     */
    private static IOException closeReaders(ExecutorService readers) {
        if (readers == null) {
            return null;
        }
        readers.shutdownNow();
        try {
            if (!readers.awaitTermination(READER_EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                return new IOException("shell_reader_cleanup_timeout");
            }
            return null;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new IOException("shell_reader_cleanup_interrupted", failure);
        }
    }

    /** 构造 timeout 元数据；默认值与硬边界同时进入模型可见 Schema，执行端仍会二次校验。 */
    private static JsonObject timeoutProperty() {
        return JsonObjects.builder()
                .putText("type", "integer")
                .putText("description", "Maximum execution time in milliseconds; default 120000.")
                .putNumber("minimum", MIN_TIMEOUT_MILLIS)
                .putNumber("maximum", MAX_TIMEOUT_MILLIS)
                .putNumber("default", DEFAULT_TIMEOUT_MILLIS)
                .build();
    }

    /** 为没有退出码的取消/超时/输出失败统一附带截断事实，避免错误路径另造不兼容元数据。 */
    private static ToolResult result(ToolOutcome outcome, String content, boolean truncated, String errorCode) {
        if (outcome != ToolOutcome.SUCCEEDED && content.isEmpty()) {
            content = "[stderr]\nShell stopped: " + errorCode + ".";
        }
        return new ToolResult(outcome, content,
                Optional.of(JsonObjects.builder().putBoolean("truncated", truncated).build()), errorCode);
    }

    /**
     * 将启动策略、Job 接纳和 CreateProcess 的已审计错误归入稳定阶段码；Win32 数值与异常类型
     * 只进入脱敏日志，模型不会再把所有早期失败误判为 Shell 不存在并盲目重试。
     */
    private static ToolResult launchFailure(IOException failure) {
        String message = failure.getMessage();
        String cause = failure.getCause() == null ? null : failure.getCause().getMessage();
        String code;
        if (failure instanceof WindowsJobObject.WindowsJobObjectException) {
            code = "shell_job_unavailable";
        } else if ("windows_process_cwd_invalid".equals(message)) {
            code = "shell_working_directory_invalid";
        } else if ("windows_process_environment_invalid".equals(message)) {
            code = "shell_environment_invalid";
        } else if ("executable_not_found".equals(cause)) {
            code = "shell_executable_unavailable";
        } else if ("assign".equals(cause)) {
            code = "shell_process_admission_failed";
        } else if ("resume_thread".equals(cause)) {
            code = "shell_process_resume_failed";
        } else if ("create_process".equals(cause)) {
            code = "shell_process_create_failed";
        } else if (cause != null && (cause.contains("pipe") || cause.contains("handle_information"))) {
            code = "shell_pipe_unavailable";
        } else {
            code = "shell_launch_failed";
        }
        return diagnosedFailure(code, failure);
    }

    /** 为无子进程输出的基础设施失败提供非空模型反馈，同时保持 stdout/stderr 元数据语义。 */
    private static ToolResult diagnosedFailure(String code, Exception failure) {
        return diagnosedFailure(code, failure, "", Optional.empty());
    }

    /**
     * 保留已取得的子进程输出，并追加独立诊断段；日志不记录 command、cwd、环境、异常消息或路径。
     */
    private static ToolResult diagnosedFailure(
            String code, Exception failure, String content, Optional<JsonValue> structuredContent) {
        String diagnosticId = "diag_" + UUID.randomUUID().toString().replace("-", "");
        Throwable cause = failure.getCause();
        LOGGER.warn("Shell boundary failure diagnosticId={} code={} nativeError={} type={} causeType={}",
                diagnosticId, code, nativeError(failure), failure.getClass().getName(),
                cause == null ? "none" : cause.getClass().getName());
        String diagnostic = "Shell failed: " + code + ". Diagnostic ID: " + diagnosticId + ".";
        String combined = appendDiagnostic(content, diagnostic);
        return new ToolResult(ToolOutcome.FAILED, combined, structuredContent, code);
    }

    /**
     * 把基础设施诊断放入 stderr 分区且不改写既有子进程字节；显式补 stdout 标签是为了让展示层
     * 仍能准确拆分“子进程真实输出”和“Ja 边界失败”。
     */
    private static String appendDiagnostic(String content, String diagnostic) {
        if (content.isEmpty()) {
            return "[stderr]\n" + diagnostic;
        }
        String separator = content.endsWith("\n") ? "" : "\n";
        if (content.startsWith("[stderr]\n") || content.contains("\n[stderr]\n")) {
            return content + separator + diagnostic;
        }
        if (content.startsWith("[stdout]\n")) {
            return content + separator + "[stderr]\n" + diagnostic;
        }
        return "[stdout]\n" + content + separator + "[stderr]\n" + diagnostic;
    }

    /**
     * 只从平台适配器生成的闭集消息提取 Win32 数值码；任意其它异常返回零，禁止把自由文本写日志。
     */
    private static int nativeError(Exception failure) {
        if (failure instanceof WindowsJobObject.WindowsJobObjectException jobFailure) {
            return jobFailure.nativeError();
        }
        String message = failure.getMessage();
        String prefix = "windows_process_launch_failed_";
        if (message == null || !message.startsWith(prefix)) {
            return 0;
        }
        try {
            return Integer.parseUnsignedInt(message.substring(prefix.length()));
        } catch (NumberFormatException invalid) {
            return 0;
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

    /** 聚合清理失败而不记录其消息；首个失败决定行为码，后续失败只保留在本地 cause 链。 */
    private static IOException merge(IOException first, IOException next) {
        if (first == null) return next;
        if (next != null) first.addSuppressed(next);
        return first;
    }

    /**
     * 统一封装根进程、管道和平台进程树 owner。Windows 只允许 Job Object 终止完整后代；
     * 非 Windows 沿用 ProcessHandle 清理语义，不把弱平台方案混入 Windows 生产边界。
     */
    private static final class ShellProcessOwner {
        private final Process process;
        private final WindowsJobObject windowsJob;
        private boolean treeReleased;
        private IOException lifecycleFailure;

        /** 绑定已启动根进程与可选 Windows Job；二者在本对象外不得独立关闭。 */
        private ShellProcessOwner(Process process, WindowsJobObject windowsJob) {
            this.process = Objects.requireNonNull(process, "process");
            this.windowsJob = windowsJob;
        }

        /**
         * Windows 使用挂起 CreateProcess→Job 接纳→Resume 的原子边界；创建失败会先关闭 Job。
         * 其它平台保持已有 ProcessBuilder 启动语义，但仍由同一调用级 owner 回收资源。
         */
        private static ShellProcessOwner launch(ShellProfile profile, String command, java.nio.file.Path cwd)
                throws IOException {
            List<String> commandLine = profile.commandLine(command);
            if (profile.os() == ShellProfile.OperatingSystem.WINDOWS) {
                WindowsJobObject job = WindowsJobObject.create();
                try {
                    Process admitted = WindowsProcessLauncher.launch(commandLine, cwd, profile.environment(), job);
                    return new ShellProcessOwner(admitted, job);
                } catch (IOException | RuntimeException failure) {
                    try {
                        job.close();
                    } catch (IOException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                    if (failure instanceof IOException ioFailure) throw ioFailure;
                    throw failure;
                }
            }
            ProcessBuilder builder = new ProcessBuilder(commandLine).directory(cwd.toFile());
            builder.environment().clear();
            builder.environment().putAll(profile.environment());
            return new ShellProcessOwner(builder.start(), null);
        }

        /** 返回唯一根进程视图；调用方不得据此复制进程树清理权。 */
        private Process process() {
            return process;
        }

        /** Shell Tool 没有交互输入能力，因此启动后立刻关闭父端写管道以发送确定性 EOF。 */
        private void closeInput() throws IOException {
            process.getOutputStream().close();
        }

        /** 取消回调不能抛出并阻断取消协调器；失败只进入最终 cleanup 屏障统一结算。 */
        private void requestTermination() {
            terminateTree();
        }

        /** 根进程正常退出也关闭 owner；Windows kill-on-close 会清理仍继承管道的后台后代。 */
        private synchronized void releaseAfterRootExit() {
            if (treeReleased) return;
            try {
                if (windowsJob != null) windowsJob.closeAfterRootExit();
                else terminatePortableDescendants(process);
                treeReleased = true;
            } catch (IOException failure) {
                lifecycleFailure = merge(lifecycleFailure, failure);
                // close() 会在返回 ToolResult 前重试并把失败收敛为 shell_cleanup_failed。
            }
        }

        /**
         * 超时、取消和失败统一取得进程树关闭权；Windows 禁止退化为 descendants 枚举，
         * Job close 成功即证明整棵树不再脱离调用生命周期。
         */
        private synchronized IOException terminateTree() {
            if (treeReleased) return null;
            try {
                if (windowsJob != null) windowsJob.terminate();
                else terminatePortableTree(process);
                treeReleased = true;
                return null;
            } catch (IOException failure) {
                lifecycleFailure = merge(lifecycleFailure, failure);
                return failure;
            } catch (RuntimeException failure) {
                IOException cleanupFailure = new IOException("shell_tree_cleanup_failed", failure);
                lifecycleFailure = merge(lifecycleFailure, cleanupFailure);
                return cleanupFailure;
            }
        }

        /**
         * 最终屏障先确保进程树已关闭，再释放根进程和三条管道；任何失败都返回稳定本地事实，
         * 不包含命令、路径、环境或 PID。
         */
        private IOException close() {
            terminateTree();
            IOException failure = lifecycleFailure;
            try {
                if (windowsJob != null) WindowsProcessLauncher.close(process);
                else closePortableProcess(process);
            } catch (IOException closeFailure) {
                failure = merge(failure, closeFailure);
            } catch (RuntimeException closeFailure) {
                failure = merge(failure, new IOException("shell_process_close_failed", closeFailure));
            }
            try {
                if (windowsJob != null && !windowsJob.isClosed()) {
                    windowsJob.close();
                }
            } catch (IOException closeFailure) {
                failure = merge(failure, closeFailure);
            } catch (RuntimeException closeFailure) {
                failure = merge(failure, new IOException("shell_job_close_failed", closeFailure));
            }
            return failure;
        }

        /** 非 Windows 沿用已有可见后代清理，并对同一快照执行有界强制终止。 */
        private static void terminatePortableTree(Process root) {
            List<ProcessHandle> descendants = new ArrayList<>(root.descendants().toList());
            descendants.forEach(ProcessHandle::destroy);
            root.destroy();
            awaitPortableExit(root, descendants);
        }

        /** 根已退出时只回收仍存活的可见后代，防止它们继续持有 stdout/stderr 写端。 */
        private static void terminatePortableDescendants(Process root) {
            List<ProcessHandle> descendants = new ArrayList<>(root.descendants().toList());
            descendants.forEach(ProcessHandle::destroy);
            awaitPortableExit(root, descendants);
        }

        /** 对普通平台根和后代预留短优雅窗口，随后强制终止同一已冻结集合。 */
        private static void awaitPortableExit(Process root, List<ProcessHandle> descendants) {
            try {
                root.waitFor(300, TimeUnit.MILLISECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
            descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            if (root.isAlive()) root.destroyForcibly();
        }

        /** 非 Windows Process 没有统一 close，显式关闭三条流并保留第一个受检失败。 */
        @SuppressWarnings("PMD.CloseResource")
        private static void closePortableProcess(Process process) throws IOException {
            IOException failure = null;
            for (AutoCloseable stream : List.of(process.getOutputStream(), process.getInputStream(),
                    process.getErrorStream())) {
                try {
                    stream.close();
                } catch (Exception closeFailure) {
                    IOException ioFailure = closeFailure instanceof IOException value
                            ? value : new IOException("shell_stream_close_failed", closeFailure);
                    failure = merge(failure, ioFailure);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
