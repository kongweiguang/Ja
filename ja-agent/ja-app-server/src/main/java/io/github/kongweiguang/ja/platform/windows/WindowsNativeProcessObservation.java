// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.platform.windows;

import io.github.kongweiguang.ja.foundation.lifecycle.NativeProcessObservation;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.time.Instant;
import java.util.Objects;

/**
 * 以仅查询和等待权限持有一个精确进程句柄；生命周期观察不依赖 JDK 对跨完整性级别进程的枚举权限。
 */
public final class WindowsNativeProcessObservation implements NativeProcessObservation {
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    private static final int SYNCHRONIZE = 0x00100000;
    private static final int REQUIRED_ACCESS = PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE;
    private static final int WAIT_OBJECT_0 = 0;
    private static final int WAIT_TIMEOUT = 258;
    private static final int WAIT_FAILED = 0xffff_ffff;
    private static final long WINDOWS_TO_UNIX_EPOCH_TICKS = 116_444_736_000_000_000L;
    private static final long FILETIME_TICKS_PER_SECOND = 10_000_000L;
    private static final int MAX_WAIT_MILLIS = 1_000;

    private final ProcessApi api;
    private final Object processHandle;
    private final Instant creationTime;
    private final Object lifecycleLock = new Object();
    private boolean handleClosed;
    private boolean closed;

    /**
     * 句柄和 Kernel32 符号会话由同一个实例独占，关闭等待采用短超时以便后台停机能有界回收资源。
     * @author kongweiguang
     */
    private WindowsNativeProcessObservation(ProcessApi api, Object processHandle, Instant creationTime) {
        this.api = Objects.requireNonNull(api, "api");
        this.processHandle = Objects.requireNonNull(processHandle, "processHandle");
        this.creationTime = Objects.requireNonNull(creationTime, "creationTime");
    }

    /**
     * 使用 Windows 最小查询权限打开指定 PID，并在返回观察器前确认该句柄仍对应存活进程。
     * @author kongweiguang
     */
    public static WindowsNativeProcessObservation open(long processId) {
        if (processId < 1 || processId > 0xffff_ffffL) {
            throw new IllegalArgumentException("native client process id is outside the Windows PID range");
        }
        return open(processId, new Kernel32ProcessApi());
    }

    /**
     * 测试入口复用真实注册与等待流程，以假 API 检查权限掩码、状态转换及精确一次的句柄释放。
     * @author kongweiguang
     */
    static WindowsNativeProcessObservation open(long processId, ProcessApi api) {
        Objects.requireNonNull(api, "api");
        if (processId < 1 || processId > 0xffff_ffffL) {
            IllegalArgumentException invalidId = new IllegalArgumentException(
                    "native client process id is outside the Windows PID range");
            closeApiAfterOpenFailure(api, null, invalidId);
            throw invalidId;
        }
        Object handle = null;
        try {
            handle = api.openProcess(processId, REQUIRED_ACCESS);
            if (handle == null) {
                throw new IllegalStateException("windows_native_process_open_failed");
            }
            Instant creationTime = Objects.requireNonNull(api.creationTime(handle), "creationTime");
            int state = api.waitForSingleObject(handle, 0);
            if (state == WAIT_OBJECT_0) {
                throw new IllegalStateException("native client process has already exited");
            }
            if (state != WAIT_TIMEOUT) {
                throw new IllegalStateException("windows_native_process_wait_failed");
            }
            return new WindowsNativeProcessObservation(api, handle, creationTime);
        } catch (RuntimeException | Error failure) {
            closeApiAfterOpenFailure(api, handle, failure);
            if (failure instanceof Error) {
                throw failure;
            }
            throw new IllegalArgumentException("native client process is unavailable", failure);
        }
    }

    /**
     * 返回由 GetProcessTimes 读取的真实创建时刻，用于区分系统复用后的相同 PID。
     * @author kongweiguang
     */
    public Instant creationTime() {
        return creationTime;
    }

    /**
     * 通过同一精确句柄的零超时等待判断进程状态，不重新按 PID 打开或查询进程。
     * @author kongweiguang
     */
    public boolean isAlive() {
        synchronized (lifecycleLock) {
            return !closed && !awaitExit(0);
        }
    }

    /**
     * 有界等待指定进程句柄；限制单次阻塞时长，避免共享模式关闭被无限等待拖住。
     * @author kongweiguang
     */
    public boolean awaitExit(int timeoutMillis) {
        if (timeoutMillis < 0 || timeoutMillis > MAX_WAIT_MILLIS) {
            throw new IllegalArgumentException("native process wait must remain bounded");
        }
        synchronized (lifecycleLock) {
            if (closed) {
                return false;
            }
            int state = api.waitForSingleObject(processHandle, timeoutMillis);
            if (state == WAIT_OBJECT_0) {
                return true;
            }
            if (state == WAIT_TIMEOUT) {
                return false;
            }
            throw new IllegalStateException("windows_native_process_wait_failed");
        }
    }

    /**
     * 等待中只允许短暂持有关闭锁；句柄关闭成功后再释放承载其 downcall 的 Kernel32 符号会话。
     * @author kongweiguang
     */
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            if (!handleClosed) {
                api.closeHandle(processHandle);
                handleClosed = true;
            }
            api.close();
            closed = true;
        }
    }

    /**
     * 打开失败时关闭已取得的进程句柄和符号会话，同时保留最初失败作为诊断根因。
     * @author kongweiguang
     */
    private static void closeApiAfterOpenFailure(ProcessApi api, Object handle, Throwable failure) {
        if (handle != null) {
            try {
                api.closeHandle(handle);
            } catch (RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        try {
            api.close();
        } catch (RuntimeException | Error closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /**
     * FILETIME 以 100 纳秒为单位；保留完整原生精度，避免毫秒时间戳削弱 PID 代际比较。
     * @author kongweiguang
     */
    private static Instant fileTimeToInstant(long ticks) {
        long unixTicks = ticks - WINDOWS_TO_UNIX_EPOCH_TICKS;
        long seconds = Math.floorDiv(unixTicks, FILETIME_TICKS_PER_SECOND);
        long nanos = Math.floorMod(unixTicks, FILETIME_TICKS_PER_SECOND) * 100L;
        return Instant.ofEpochSecond(seconds, nanos);
    }

    /**
     * 为观察器提供可替换的最小系统边界，避免测试通过放宽真实 Windows 权限来制造结果。
     * @author kongweiguang
     */
    interface ProcessApi extends AutoCloseable {
        /** 使用明确的访问掩码打开指定进程，并将本地句柄作为不透明对象返回。 */
        Object openProcess(long processId, int desiredAccess);

        /** 从精确句柄读取真实创建时刻。 */
        Instant creationTime(Object handle);

        /** 对精确句柄执行有界 WaitForSingleObject。 */
        int waitForSingleObject(Object handle, int timeoutMillis);

        /** 关闭由此 API 独占的进程句柄。 */
        void closeHandle(Object handle);

        /** 释放符号查找会话。 */
        @Override
        void close();
    }

    /**
     * 每个被观察进程独占一个 Kernel32 符号会话，避免共享模式关闭时误关其他进程的 downcall。
     * @author kongweiguang
     */
    private static final class Kernel32ProcessApi implements ProcessApi {
        private final WindowsKernel32Library library;
        private final MethodHandle openProcess;
        private final MethodHandle getProcessTimes;
        private final MethodHandle waitForSingleObject;
        private final MethodHandle closeHandle;

        /**
         * API 实例在整个观察句柄生命周期内持有符号 Arena；部分初始化失败时关闭该实例已创建的 Arena。
         * @author kongweiguang
         */
        private Kernel32ProcessApi() {
            library = new WindowsKernel32Library(
                    "windows_native_process_symbols_unavailable");
            try {
                openProcess = library.downcall("OpenProcess", FunctionDescriptor.of(
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                getProcessTimes = library.downcall("GetProcessTimes", FunctionDescriptor.of(
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                waitForSingleObject = library.downcall("WaitForSingleObject", FunctionDescriptor.of(
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                closeHandle = library.downcall("CloseHandle", FunctionDescriptor.of(
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            } catch (RuntimeException | Error failure) {
                library.closeQuietly();
                throw failure;
            }
        }

        /**
         * 以最小必要权限打开句柄：不请求 PROCESS_QUERY_INFORMATION、终止权或句柄继承。
         * @author kongweiguang
         */
        @Override
        public Object openProcess(long processId, int desiredAccess) {
            WindowsKernel32Library.NativeCall result = invoke(
                    openProcess, "open_process", desiredAccess, 0, (int) processId);
            MemorySegment handle = (MemorySegment) result.value();
            if (WindowsKernel32Library.isNullHandle(handle)) {
                throw new IllegalStateException("windows_native_process_open_failed_"
                        + Integer.toUnsignedString(result.error()));
            }
            return handle;
        }

        /**
         * 用四个 FILETIME 输出槽读取创建时刻，其他三个时间槽仅为满足系统 ABI 而分配。
         * @author kongweiguang
         */
        @Override
        public Instant creationTime(Object process) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment creation = arena.allocate(8, 4);
                MemorySegment exit = arena.allocate(8, 4);
                MemorySegment kernel = arena.allocate(8, 4);
                MemorySegment user = arena.allocate(8, 4);
                WindowsKernel32Library.NativeCall result = invoke(
                        getProcessTimes, "get_process_times", process, creation, exit, kernel, user);
                if ((int) result.value() == 0) {
                    throw new IllegalStateException("windows_native_process_times_failed_"
                            + Integer.toUnsignedString(result.error()));
                }
                long low = Integer.toUnsignedLong(creation.get(ValueLayout.JAVA_INT, 0));
                long high = Integer.toUnsignedLong(creation.get(ValueLayout.JAVA_INT, 4));
                return fileTimeToInstant((high << 32) | low);
            }
        }

        /**
         * 只把 WAIT_TIMEOUT 和 WAIT_OBJECT_0 交给状态机，其余 Win32 结果都保留为观察故障。
         * @author kongweiguang
         */
        @Override
        public int waitForSingleObject(Object process, int timeoutMillis) {
            WindowsKernel32Library.NativeCall result = invoke(
                    waitForSingleObject, "wait_for_single_object", process, timeoutMillis);
            int state = (int) result.value();
            if (state == WAIT_FAILED) {
                throw new IllegalStateException("windows_native_process_wait_failed_"
                        + Integer.toUnsignedString(result.error()));
            }
            return state;
        }

        /**
         * 只关闭由观察器创建的精确句柄；关闭失败保留给 owner 重试，不伪装为资源已释放。
         * @author kongweiguang
         */
        @Override
        public void closeHandle(Object process) {
            WindowsKernel32Library.NativeCall result = invoke(closeHandle, "close_handle", process);
            if ((int) result.value() == 0) {
                throw new IllegalStateException("windows_native_process_close_failed_"
                        + Integer.toUnsignedString(result.error()));
            }
        }

        /**
         * 句柄关闭后释放固定 Kernel32 查找会话；库自身对重复 close 保持幂等。
         * @author kongweiguang
         */
        @Override
        public void close() {
            library.close();
        }

        /**
         * 在 FFM 临时状态释放前冻结结果与错误码，避免把本地内存段泄漏到观察器。
         * @author kongweiguang
         */
        private WindowsKernel32Library.NativeCall invoke(MethodHandle function, String operation,
                                                          Object... arguments) {
            try {
                return library.invoke(function, operation, arguments);
            } catch (WindowsKernel32Library.InvocationFailure failure) {
                throw new IllegalStateException("windows_native_process_call_failed_" + operation, failure);
            }
        }
    }
}
