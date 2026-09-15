// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 直接运行一次 fd/rg，并持续排空两个继承管道；调用方通过逐行消费者控制结果上限，
 * 避免把 native 搜索快照无界物化到 Java 内存。
 */
final class NativeSearchProcess {
    private static final int BUFFER_SIZE = 8_192;
    private static final long POLL_MILLIS = 25;
    private static final long CLEANUP_GRACE_MILLIS = 3_000;

    /**
     * 消费一个 UTF-8 native 输出记录；返回 false 表示调用方已达到结果或输出上限。
     */
    @FunctionalInterface
    interface LineConsumer {
        /**
         * 消费当前行；异常会终止 native 进程并在调用边界转换为脱敏 IO 失败。
         */
        boolean accept(String line) throws IOException;
    }

    /**
     * 只保留 find/grep 需要的生命周期事实，stderr 受限保存供进程内诊断而不回显给模型。
     */
    record Result(int exitCode, boolean outputTruncated, boolean stoppedByConsumer,
                  boolean cancelled, boolean deadlineExceeded, String stderr) {
    }

    /**
     * 启动可执行文件并交替处理取消、Turn Deadline、结果上限和两条输出管道；正常退出时
     * 读者等待到同一 Deadline，只有终止路径使用固定清理窗口，因此不会误杀合法慢读者。
     * reader/executor 必须和进程树及三个流共享 finally 清理屏障，PMD 无法识别该跨资源屏障，
     * 因此只在本方法局部抑制 CloseResource。
     */
    @SuppressWarnings("PMD.CloseResource")
    static Result run(Path executable, List<String> arguments, Path workingDirectory,
                      CancellationToken token, Instant deadline, int maxStdoutBytes,
                      int maxStderrBytes, LineConsumer consumer) throws IOException {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(consumer, "consumer");
        if (maxStdoutBytes < 1 || maxStderrBytes < 1) {
            throw new IllegalArgumentException("native search output limits are invalid");
        }
        token.throwIfCancellationRequested();
        if (!Instant.now().isBefore(deadline)) {
            return new Result(-1, false, false, false, true, "");
        }

        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(executable.toString());
        command.addAll(arguments);
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(false)
                .start();
        AtomicBoolean stopRequested = new AtomicBoolean();
        AtomicBoolean outputTruncated = new AtomicBoolean();
        AtomicBoolean stoppedByConsumer = new AtomicBoolean();
        AtomicReference<Exception> readerFailure = new AtomicReference<>();
        ExecutorService readers = null;
        CancellationToken.Registration registration = null;
        boolean completed = false;
        boolean deadlineExceeded = false;
        try {
            readers = Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("ja-search-output-", 0).factory());
            registration = token.onCancellation(() -> {
                stopRequested.set(true);
                terminate(process);
            });
            Future<?> stdout = readers.submit(() -> drainLines(process.getInputStream(), maxStdoutBytes,
                    outputTruncated, stopRequested, stoppedByConsumer, readerFailure, consumer, process));
            Future<String> stderr = readers.submit(() -> drainBytes(process.getErrorStream(), maxStderrBytes,
                    stopRequested, readerFailure, process));

            while (process.isAlive() || !stdout.isDone() || !stderr.isDone()) {
                if (token.isCancellationRequested()) {
                    stopRequested.set(true);
                    terminate(process);
                    break;
                }
                if (!Instant.now().isBefore(deadline)) {
                    deadlineExceeded = true;
                    stopRequested.set(true);
                    terminate(process);
                    break;
                }
                if (stopRequested.get()) {
                    terminate(process);
                    break;
                }
                if (process.isAlive()) {
                    process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS);
                } else {
                    // 子进程已退出但回调仍可能占用 reader；短暂让出线程，避免退出后的空转。
                    Thread.sleep(POLL_MILLIS);
                }
            }

            boolean cancelled = token.isCancellationRequested();
            boolean needsCleanup = cancelled || deadlineExceeded || stopRequested.get();
            if (needsCleanup) {
                terminate(process);
                reap(process);
            } else if (process.isAlive()) {
                reap(process);
            }

            long readerWaitMillis = needsCleanup ? CLEANUP_GRACE_MILLIS : remainingMillis(deadline);
            long readerDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readerWaitMillis);
            String error = awaitValue(stderr, remainingMillis(readerDeadline),
                    "native_search_stderr_cleanup_timeout");
            awaitValue(stdout, remainingMillis(readerDeadline), "native_search_output_cleanup_timeout");
            Exception failure = readerFailure.get();
            if (failure != null) throw new IOException("native_search_output_failed", failure);
            completed = true;
            return new Result(process.exitValue(), outputTruncated.get(), stoppedByConsumer.get(),
                    cancelled, deadlineExceeded, error);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("native_search_interrupted", interrupted);
        } finally {
            try {
                if (registration != null) registration.close();
            } finally {
                if (!completed || process.isAlive()) {
                    terminate(process);
                    reapQuietly(process);
                }
                IOException streamCleanup = closeProcessStreams(process);
                IOException readerCleanup = readers == null ? null : closeReaders(readers);
                if (completed) {
                    if (streamCleanup != null && readerCleanup != null) {
                        streamCleanup.addSuppressed(readerCleanup);
                    }
                    if (streamCleanup != null) throw streamCleanup;
                    if (readerCleanup != null) throw readerCleanup;
                }
            }
        }
    }

    /**
     * 按字节计数并组装一行，newline 也计入 stdout 预算，超限立即终止而不继续缓存长行。
     */
    private static void drainLines(InputStream input, int maximumBytes, AtomicBoolean truncated,
                                   AtomicBoolean stopRequested, AtomicBoolean stoppedByConsumer,
                                   AtomicReference<Exception> readerFailure, LineConsumer consumer,
                                   Process process) {
        int remaining = maximumBytes;
        ByteArrayOutputStream line = new ByteArrayOutputStream(Math.min(BUFFER_SIZE, maximumBytes));
        byte[] buffer = new byte[BUFFER_SIZE];
        try (input) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                for (int index = 0; index < read; index++) {
                    int value = buffer[index] & 0xff;
                    if (remaining == 0) {
                        truncated.set(true);
                        stopRequested.set(true);
                        terminate(process);
                        return;
                    }
                    remaining--;
                    if (value == '\n') {
                        if (!consumeLine(line, consumer, stoppedByConsumer, stopRequested,
                                readerFailure, process)) return;
                        line.reset();
                    } else {
                        line.write(value);
                    }
                }
            }
            if (line.size() != 0) consumeLine(line, consumer, stoppedByConsumer, stopRequested,
                    readerFailure, process);
        } catch (IOException failure) {
            if (!stopRequested.get()) {
                readerFailure.compareAndSet(null, failure);
                stopRequested.set(true);
                terminate(process);
            }
        }
    }

    /**
     * 持续排空 stderr；超过诊断保留量后继续丢弃，避免 native 进程因错误管道满而卡住。
     */
    private static String drainBytes(InputStream input, int maximumBytes, AtomicBoolean stopRequested,
                                     AtomicReference<Exception> readerFailure, Process process) {
        try (input; ByteArrayOutputStream retained = new ByteArrayOutputStream(Math.min(BUFFER_SIZE,
                maximumBytes))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int remaining = maximumBytes;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                int keep = Math.min(remaining, read);
                if (keep > 0) retained.write(buffer, 0, keep);
                remaining -= keep;
            }
            return retained.toString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            if (!stopRequested.get()) {
                readerFailure.compareAndSet(null, failure);
                stopRequested.set(true);
                terminate(process);
            }
            return "";
        }
    }

    /**
     * 把完整 native 行交给调用方；回调失败或主动停止都通过同一 owner 终止进程。
     */
    private static boolean consumeLine(ByteArrayOutputStream bytes, LineConsumer consumer,
                                       AtomicBoolean stoppedByConsumer, AtomicBoolean stopRequested,
                                       AtomicReference<Exception> readerFailure, Process process) {
        String line = bytes.toString(StandardCharsets.UTF_8);
        try {
            if (consumer.accept(line)) return true;
            stoppedByConsumer.set(true);
        } catch (IOException failure) {
            readerFailure.compareAndSet(null, failure);
        }
        stopRequested.set(true);
        terminate(process);
        return false;
    }

    /**
     * 先优雅等待根进程，超出清理窗口才强制终止，窗口只用于释放已请求终止的进程。
     */
    private static void reap(Process process) throws InterruptedException, IOException {
        if (process.waitFor(CLEANUP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) return;
        forciblyTerminate(process);
        if (!process.waitFor(CLEANUP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
            throw new IOException("native_search_process_cleanup_timeout");
        }
    }

    /** 线程中断时尽力清理子进程，但保留调用线程的中断状态。 */
    private static void reapQuietly(Process process) {
        try {
            if (process.isAlive()) forciblyTerminate(process);
            process.waitFor(CLEANUP_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** 终止后代后再终止根进程，缩短 Windows 上继承管道的关闭窗口。 */
    private static void terminate(Process process) {
        ProcessHandle root = process.toHandle();
        root.descendants().toList().forEach(ProcessHandle::destroy);
        process.destroy();
    }

    /** 只在优雅清理失败后强制终止进程树，不把它当成普通搜索超时机制。 */
    private static void forciblyTerminate(Process process) {
        ProcessHandle root = process.toHandle();
        root.descendants().toList().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /** 正常退出时把 reader 等待限制绑定到当前 Turn Deadline，而不是另造 Tool 超时。 */
    private static long remainingMillis(Instant deadline) {
        long remaining = Duration.between(Instant.now(), deadline).toMillis();
        return Math.max(1, remaining);
    }

    /** 等待读者共享一个绝对清理窗口，避免先完成的管道把后一个等待推过 Deadline。 */
    private static long remainingMillis(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    /** 泛型单一等待入口，避免 Future<T> 与 Future<?> 因类型擦除发生重载冲突。 */
    private static <T> T awaitValue(Future<T> future, long timeoutMillis, String timeoutCode)
            throws InterruptedException, IOException {
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("native_search_output_failed", cause);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new IOException(timeoutCode, timeout);
        }
    }

    /** 关闭三个进程流并保留首个关闭错误，避免异常路径把本地句柄留给下一次调用。 */
    private static IOException closeProcessStreams(Process process) {
        IOException first = null;
        try {
            process.getInputStream().close();
        } catch (IOException failure) {
            first = failure;
        }
        try {
            process.getErrorStream().close();
        } catch (IOException failure) {
            if (first == null) first = failure;
            else first.addSuppressed(failure);
        }
        try {
            process.getOutputStream().close();
        } catch (IOException failure) {
            if (first == null) first = failure;
            else first.addSuppressed(failure);
        }
        return first;
    }

    /** reader 已完成时无额外等待；未完成则中断并在清理窗口内确认释放。 */
    private static IOException closeReaders(ExecutorService readers) {
        readers.shutdownNow();
        try {
            return readers.awaitTermination(CLEANUP_GRACE_MILLIS, TimeUnit.MILLISECONDS)
                    ? null : new IOException("native_search_reader_cleanup_timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new IOException("native_search_reader_cleanup_interrupted", interrupted);
        }
    }

}
