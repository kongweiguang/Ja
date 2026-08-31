// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.platform.windows;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 独占一个已接纳进程句柄及其三个父端管道句柄。
 */
final class WindowsNativeProcess extends Process implements AutoCloseable {
    private static final long PIPE_SETTLE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long PIPE_POLL_INTERVAL_MILLIS = 10;
    private final WindowsProcessNativeApi api;
    private final MemorySegment processHandle;
    private final long processId;
    private final HandleInputStream stdout;
    private final HandleInputStream stderr;
    private final HandleOutputStream stdin;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean apiClosed = new AtomicBoolean();
    private volatile Integer exitCode;

    /**
     * 挂起进程完成 Job 接纳后绑定进程和父端管道句柄，所有权此后只属于本对象。
     */
    WindowsNativeProcess(
            WindowsProcessNativeApi api,
            MemorySegment processHandle,
            long processId,
            MemorySegment parentInput,
            MemorySegment parentOutput,
            MemorySegment parentError) {
        this.api = Objects.requireNonNull(api, "api");
        this.processHandle = Objects.requireNonNull(processHandle, "processHandle");
        this.processId = processId;
        this.stdin = new HandleOutputStream(api, parentInput);
        this.stdout = new HandleInputStream(api, parentOutput);
        this.stderr = new HandleInputStream(api, parentError);
    }

    /**
     * 仅返回进程标识用于诊断，不允许 PID 成为清理授权依据。
     */
    @Override
    public long pid() {
        return processId;
    }

    /**
     * 暴露父端 stdin 写流；关闭该流只向子进程发送 EOF，不转移进程句柄所有权。
     */
    @Override
    public OutputStream getOutputStream() {
        return stdin;
    }

    /**
     * 暴露 stdout 读流；底层句柄仍由进程对象独占，调用方只能关闭自己的流视图。
     */
    @Override
    public InputStream getInputStream() {
        return stdout;
    }

    /**
     * 暴露 stderr 独立读流，避免关闭 stdout 时连带释放错误输出句柄。
     */
    @Override
    public InputStream getErrorStream() {
        return stderr;
    }

    /**
     * 等待精确本地进程句柄，不查询全局进程表。
     */
    @Override
    public int waitFor() throws InterruptedException {
        while (!waitFor(1, TimeUnit.DAYS)) {
            // 单次等待限制为一天，使方法保持可中断，同时维持 Process 的无限等待语义。
        }
        return exitValue();
    }

    /**
     * 按调用方给定的有界超时等待精确进程句柄。
     */
    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        if (closed.get()) {
            return exitCode != null;
        }
        long millis = unit.toMillis(timeout);
        int result = api.waitFor(processHandle,
                millis >= Integer.MAX_VALUE ? -1 : (int) Math.max(0, millis));
        if (result == WindowsProcessNativeApi.WAIT_OBJECT_0) {
            rememberExitCode();
            return true;
        }
        if (result == WindowsProcessNativeApi.WAIT_TIMEOUT) {
            return false;
        }
        throw new IllegalStateException("windows_process_wait_failed");
    }

    /**
     * 读取缓存退出状态；精确进程句柄仍存活时按 Process 契约失败。
     */
    @Override
    public int exitValue() {
        if (isAlive()) {
            throw new IllegalThreadStateException("process has not exited");
        }
        Integer known = exitCode;
        if (known != null) {
            return known;
        }
        rememberExitCode();
        return exitCode;
    }

    /**
     * 请求终止精确根进程；整棵进程树的清理授权仍只属于 Job close。
     */
    @Override
    public void destroy() {
        if (!closed.get() && isAlive()) {
            api.terminateQuietly(processHandle);
        }
    }

    /**
     * 直接升级到 TerminateProcess，不引入基于 PID 的备用清理。
     */
    @Override
    public Process destroyForcibly() {
        destroy();
        return this;
    }

    /**
     * 明确声明适配器不提供普通终止协议，避免调用方误判可优雅退出。
     */
    @Override
    public boolean supportsNormalTermination() {
        return false;
    }

    /**
     * 先并行发出三个父端管道的取消请求，再等待同步 IO 登记归还，最后才释放共享 FFM Arena；
     * 该顺序避免串行等待阻止另一个 reader 收到取消，也禁止 downcall 尚持有 session 时关闭 Arena。
     */
    @Override
    public void close() throws IOException {
        if (apiClosed.get()) {
            return;
        }
        if (closed.compareAndSet(false, true)) {
            stdin.requestClose();
            stdout.requestClose();
            stderr.requestClose();
            api.closeQuietly(processHandle);
        }
        long deadlineNanos = System.nanoTime() + PIPE_SETTLE_TIMEOUT_NANOS;
        if (!stdin.awaitSettled(deadlineNanos)
                || !stdout.awaitSettled(deadlineNanos)
                || !stderr.awaitSettled(deadlineNanos)) {
            throw new IOException("windows_pipe_io_settle_timeout");
        }
        if (apiClosed.compareAndSet(false, true)) {
            api.close();
        }
    }

    /**
     * 只捕获一次退出码，使后续句柄关闭不会破坏 Process 查询语义。
     */
    private void rememberExitCode() {
        if (exitCode == null) {
            exitCode = api.exitCode(processHandle);
        }
    }

    /**
     * 以零超时检查精确进程句柄，不枚举任何后代进程。
     */
    @Override
    public boolean isAlive() {
        if (exitCode != null || closed.get()) {
            return false;
        }
        int result = api.waitFor(processHandle, 0);
        if (result == WindowsProcessNativeApi.WAIT_OBJECT_0) {
            rememberExitCode();
            return false;
        }
        return result == WindowsProcessNativeApi.WAIT_TIMEOUT;
    }

    /**
     * 以有界缓冲读取本地管道，不使用 FileDescriptor 反射。
     */
    private static final class HandleInputStream extends InputStream {
        private final SynchronousPipeHandle pipe;

        /**
         * 绑定一个父端读句柄并取得独占生命周期所有权。
         */
        private HandleInputStream(WindowsProcessNativeApi api, MemorySegment handle) {
            pipe = new SynchronousPipeHandle(api, handle);
        }

        /**
         * 单字节读取复用批量读取的同一 FFM ReadFile 路径。
         */
        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(one[0]);
        }

        /**
         * 通过 PeekNamedPipe 只读取当前已存在的字节；空管道采用短轮询，使 Native Image 的 FFM
         * downcall 永不永久阻塞，同时把已关闭或断开的管道解释为 EOF。
         */
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            if (pipe.isClosed()) {
                return -1;
            }
            while (true) {
                try (PendingPipeIo ignored = pipe.begin("windows_pipe_concurrent_read")) {
                    if (ignored == null || pipe.isClosed()) {
                        return -1;
                    }
                    long available = Integer.toUnsignedLong(pipe.availableBytes());
                    if (available > 0) {
                        return pipe.read(bytes, offset, (int) Math.min(length, available));
                    }
                } catch (WindowsProcessNativeApi.WindowsFailure failure) {
                    if (failure.error() == WindowsProcessNativeApi.ERROR_BROKEN_PIPE
                        || failure.error() == WindowsProcessNativeApi.ERROR_NO_DATA
                        || failure.error() == WindowsProcessNativeApi.ERROR_INVALID_HANDLE
                        || (pipe.isClosed()
                            && failure.error() == WindowsProcessNativeApi.ERROR_OPERATION_ABORTED)) {
                        return -1;
                    }
                    throw failure.asIoException("windows_pipe_read_failed");
                }
                if (pipe.isClosed()) {
                    return -1;
                }
                awaitNextPoll();
            }
        }

        /**
         * 以可中断短等待限制空管道轮询频率；中断恢复线程状态并映射为受检 IO 失败，供统一清理归约。
         */
        private static void awaitNextPoll() throws InterruptedIOException {
            try {
                TimeUnit.MILLISECONDS.sleep(PIPE_POLL_INTERVAL_MILLIS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                InterruptedIOException interrupted =
                        new InterruptedIOException("windows_pipe_poll_interrupted");
                interrupted.initCause(failure);
                throw interrupted;
            }
        }

        /**
         * 先取消精确读句柄上的 pending I/O 再关闭，不影响同级 stdout 或 stderr 管道。
         */
        @Override
        public void close() throws IOException {
            pipe.close();
        }

        /**
         * 只发出取消和句柄关闭，不在此处等待 reader；进程 owner 需要先同时取消 stdout 与 stderr。
         */
        private void requestClose() {
            pipe.requestClose();
        }

        /**
         * 在进程 owner 的共享绝对 Deadline 内等待本流登记归还，避免每个流各自扩张关闭预算。
         */
        private boolean awaitSettled(long deadlineNanos) throws IOException {
            return pipe.awaitSettled(deadlineNanos);
        }
    }

    /**
     * 通过父端本地管道写入命令输入。
     */
    private static final class HandleOutputStream extends OutputStream {
        private final SynchronousPipeHandle pipe;

        /**
         * 绑定一个父端写句柄并取得独占生命周期所有权。
         */
        private HandleOutputStream(WindowsProcessNativeApi api, MemorySegment handle) {
            pipe = new SynchronousPipeHandle(api, handle);
        }

        /**
         * 单字节写入复用有界批量写入路径。
         */
        @Override
        public void write(int value) throws IOException {
            write(new byte[]{(byte) value}, 0, 1);
        }

        /**
         * 写入请求切片，子管道断开后不进行可能重复副作用的重试。
         */
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (pipe.isClosed()) {
                throw new IOException("windows_pipe_closed");
            }
            try (PendingPipeIo ignored = pipe.begin("windows_pipe_concurrent_write")) {
                if (ignored == null || pipe.isClosed()) {
                    throw new IOException("windows_pipe_closed");
                }
                int written = pipe.write(bytes, offset, length);
                if (written != length) {
                    throw new IOException("windows_pipe_partial_write");
                }
            } catch (WindowsProcessNativeApi.WindowsFailure failure) {
                throw failure.asIoException("windows_pipe_write_failed");
            }
        }

        /**
         * 关闭精确输入句柄，使子进程能够观察到 EOF。
         */
        @Override
        public void close() throws IOException {
            pipe.close();
        }

        /**
         * 只发出 stdin 取消和句柄关闭，使三个管道在统一等待前都进入不可逆关闭状态。
         */
        private void requestClose() {
            pipe.requestClose();
        }

        /**
         * 复用进程 owner 的共享绝对 Deadline，禁止单个阻塞 writer 独占额外关闭时间。
         */
        private boolean awaitSettled(long deadlineNanos) throws IOException {
            return pipe.awaitSettled(deadlineNanos);
        }
    }

    /**
     * 统一持有一个父端管道、关闭位与唯一 pending 同步 I/O，避免读写流复制并发取消协议。
     */
    private static final class SynchronousPipeHandle implements AutoCloseable {
        private final WindowsProcessNativeApi api;
        private final MemorySegment pipeHandle;
        private final AtomicBoolean closed = new AtomicBoolean();
        private PendingPipeIo pending;

        /**
         * 取得精确管道句柄所有权，任何线程登记只借用该 owner 而不转移关闭权。
         */
        private SynchronousPipeHandle(WindowsProcessNativeApi api, MemorySegment pipeHandle) {
            this.api = Objects.requireNonNull(api, "api");
            this.pipeHandle = Objects.requireNonNull(pipeHandle, "pipeHandle");
        }

        /**
         * 登记该精确管道上的唯一同步 I/O；关闭已开始时返回 null，让读端映射 EOF、写端映射关闭失败。
         * 串行约束让 CancelIoEx(NULL) 与关闭位之间不存在新请求竞态。
         */
        private synchronized PendingPipeIo begin(String concurrentCode) throws IOException {
            if (closed.get()) {
                return null;
            }
            if (pending != null) {
                throw new IOException(concurrentCode);
            }
            PendingPipeIo operation = new PendingPipeIo(this);
            pending = operation;
            return operation;
        }

        /**
         * 从精确父端管道执行同步读取，取消错误仍由输入流按关闭状态归约为 EOF。
         */
        private int read(byte[] bytes, int offset, int length)
                throws WindowsProcessNativeApi.WindowsFailure {
            return api.read(pipeHandle, bytes, offset, length);
        }

        /**
         * 查询当前可安全同步读取的字节数，调用不消费管道内容也不等待生产者。
         */
        private int availableBytes() throws WindowsProcessNativeApi.WindowsFailure {
            return api.availableBytes(pipeHandle);
        }

        /**
         * 从精确父端管道执行同步写入，不在平台边界重试可能重复的输入字节。
         */
        private int write(byte[] bytes, int offset, int length)
                throws WindowsProcessNativeApi.WindowsFailure {
            return api.write(pipeHandle, bytes, offset, length);
        }

        /**
         * 报告管道 owner 是否已开始不可逆关闭，供读写路径在进入本地调用前二次检查竞态。
         */
        private boolean isClosed() {
            return closed.get();
        }

        /**
         * 只移除本次线程登记，迟到 finally 不得清除后续操作的取消目标。
         */
        private synchronized void release(PendingPipeIo operation) {
            if (pending == operation) {
                pending = null;
                notifyAll();
            }
        }

        /**
         * 先对精确管道发出 CancelIoEx，再关闭句柄；pending 登记仍由 I/O 线程在 finally 中释放，
         * 关闭线程只等待静默而不伪造操作完成。
         */
        @Override
        @SuppressWarnings("PMD.CloseResource")
        public void close() throws IOException {
            requestClose();
            if (!awaitSettled(System.nanoTime() + PIPE_SETTLE_TIMEOUT_NANOS)) {
                throw new IOException("windows_pipe_io_settle_timeout");
            }
        }

        /**
         * 在 owner 监视器内冻结关闭状态，再在锁外按“取消、关闭”顺序调用 Win32；begin 受同一
         * 监视器保护，因此关闭位发布后不会出现新的同步 I/O。
         */
        private void requestClose() {
            synchronized (this) {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
            }
            api.cancelIoExQuietly(pipeHandle);
            api.closeQuietly(pipeHandle);
        }

        /**
         * 等待 I/O 线程归还 pending 登记；CancelIoEx 只发出取消请求，必须由这个屏障确认同步
         * ReadFile/WriteFile 已真正离开。中断会转换为受检 IO 失败并恢复中断位。
         */
        private synchronized boolean awaitSettled(long deadlineNanos) throws IOException {
            while (pending != null) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException interrupted =
                            new InterruptedIOException("windows_pipe_io_settle_interrupted");
                    interrupted.initCause(failure);
                    throw interrupted;
                }
            }
            return true;
        }
    }

    /**
     * 独占一次精确管道同步 I/O 的登记；对象不持有线程或额外本地句柄，唯一职责是配对归还屏障。
     */
    private static final class PendingPipeIo implements AutoCloseable {
        private final SynchronousPipeHandle owner;
        private final AtomicBoolean closed = new AtomicBoolean();

        /**
         * 绑定所属精确管道，取消权仍只属于管道 owner。
         */
        private PendingPipeIo(SynchronousPipeHandle owner) {
            this.owner = Objects.requireNonNull(owner, "owner");
        }

        /**
         * 只归还一次登记并唤醒关闭屏障；本对象不拥有本地句柄，因此不得执行次生 Win32 调用。
         */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release(this);
            }
        }
    }
}
