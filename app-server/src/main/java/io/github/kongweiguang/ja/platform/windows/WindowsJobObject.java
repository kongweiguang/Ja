// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.platform.windows;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Locale;
import java.util.Objects;

/**
 * 独占一个 Windows Job Object，作为根进程及其全部后代的清理边界。
 *
 * <p>句柄在接纳进程前设置 {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}。本类型不暴露其它进程树
 * 清理方式，因为只有关闭已配置的 Job 才能证明后代归属。普通 Java 进程启动后再调用
 * {@link #assign(Process)} 仍存在创建到分配的竞争窗口，生产启动路径必须使用挂起创建和
 * 句柄接纳接口 {@link #assignHandle(MemorySegment)}。</p>
 */
public final class WindowsJobObject implements AutoCloseable {
    /**
     * Win32 表示句柄无效的稳定错误码。
     */
    private static final int ERROR_INVALID_HANDLE = 6;
    /**
     * 本地错误缺失时使用的参数契约错误码。
     */
    private static final int ERROR_INVALID_PARAMETER = 87;
    /**
     * FFM 调用前失败且没有 Win32 错误时的占位码。
     */
    private static final int ERROR_NONE = 0;
    /**
     * Windows 32 位进程标识的无符号上界。
     */
    private static final long MAX_WINDOWS_PID = 0xffff_ffffL;

    private final WindowsJobObjectNativeApi nativeApi;
    private final Object lifecycleLock = new Object();
    private MemorySegment jobHandle;
    private boolean assigned;
    private boolean terminated;

    /**
     * 绑定已配置的 Job 句柄和唯一 FFM 所有者；构造后句柄不得转移到其它生命周期对象。
     */
    private WindowsJobObject(WindowsJobObjectNativeApi nativeApi, MemorySegment jobHandle) {
        this.nativeApi = Objects.requireNonNull(nativeApi, "nativeApi");
        this.jobHandle = Objects.requireNonNull(jobHandle, "jobHandle");
    }

    /**
     * 判断当前宿主是否允许访问 Windows Job Object；该检查无副作用，避免非 Windows 组合根
     * 在类初始化或 Native Image 分析阶段加载 Kernel32。
     */
    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 在运行期创建并配置 Job Object；静态初始化不查找本地库也不创建句柄，以保持 GraalVM
     * 构建期分析无平台副作用。
     */
    public static WindowsJobObject create() throws WindowsJobObjectException {
        requireWindows();
        WindowsJobObjectNativeApi api = null;
        try {
            api = new WindowsJobObjectNativeApi();
            MemorySegment job = api.createConfiguredJob();
            return new WindowsJobObject(api, job);
        } catch (WindowsJobObjectException failure) {
            api.closeQuietly();
            throw failure;
        } catch (Throwable failure) {
            if (api != null) {
                api.closeQuietly();
            }
            throw WindowsJobObjectException.invocation("create", failure);
        }
    }

    /**
     * 将一个存活根进程分配给 Job。临时进程句柄只在本方法内打开并关闭；拒绝第二个根进程，
     * 防止调用方意外合并互不相关的任务树。
     */
    public void assign(Process process) throws WindowsJobObjectException {
        Objects.requireNonNull(process, "process");
        synchronized (lifecycleLock) {
            try {
                ensureOpen("assign");
                if (assigned) {
                    throw WindowsJobObjectException.contract("assign_already_assigned");
                }
                if (!process.isAlive()) {
                    throw WindowsJobObjectException.contract("assign_process_not_alive");
                }
                long pid = process.pid();
                if (pid <= 0 || pid > MAX_WINDOWS_PID) {
                    throw WindowsJobObjectException.contract("assign_process_id_invalid");
                }

                MemorySegment rootHandle = nativeApi.openProcess((int) pid);
                WindowsJobObjectException failure = null;
                try {
                    nativeApi.assign(jobHandle, rootHandle);
                    assigned = true;
                } catch (WindowsJobObjectException nativeFailure) {
                    failure = nativeFailure;
                } finally {
                    try {
                        nativeApi.closeHandle(rootHandle);
                    } catch (WindowsJobObjectException closeFailure) {
                        if (failure == null) {
                            failure = closeFailure;
                        } else {
                            failure.addSuppressed(closeFailure);
                        }
                    }
                }
                if (failure != null) {
                    throw failure;
                }
            } catch (WindowsJobObjectException failure) {
                // 分配失败后立即关闭未绑定 Job，避免调用方把失败对象误用于另一棵进程树。
                closeAfterAssignFailure(failure);
                throw failure;
            }
        }
    }

    /**
     * 接纳一个挂起创建的进程句柄而不按 PID 重新打开。启动器在本调用成功前不会恢复线程，
     * 从而消除普通 {@link Process} 适配器无法规避的创建到分配竞争窗口。
     */
    void assignHandle(MemorySegment processHandle) throws WindowsJobObjectException {
        Objects.requireNonNull(processHandle, "processHandle");
        synchronized (lifecycleLock) {
            try {
                ensureOpen("assign");
                if (assigned) {
                    throw WindowsJobObjectException.contract("assign_already_assigned");
                }
                if (WindowsKernel32Library.isNullHandle(processHandle)) {
                    throw WindowsJobObjectException.contract("assign_process_handle_invalid");
                }
                nativeApi.assign(jobHandle, processHandle);
                assigned = true;
            } catch (WindowsJobObjectException failure) {
                closeAfterAssignFailure(failure);
                throw failure;
            }
        }
    }

    /**
     * 终止已分配进程树并关闭 Job 句柄。即使 TerminateJobObject 失败也尝试关闭，因为
     * KILL_ON_JOB_CLOSE 是失效关闭边界；CloseHandle 失败时仍保留句柄所有权供后续重试。
     */
    public void terminate() throws WindowsJobObjectException {
        synchronized (lifecycleLock) {
            shutdownLocked(true);
        }
    }

    /**
     * 执行与 {@link #terminate()} 相同的单调清理；重复关闭无操作，且仅在 Job 句柄释放成功后
     * 才结束本地库生命周期。
     */
    @Override
    public void close() throws WindowsJobObjectException {
        synchronized (lifecycleLock) {
            shutdownLocked(true);
        }
    }

    /**
     * 根进程已退出时直接关闭已配置的 Job 句柄。kill-on-close 仍会终止后代，同时跳过 Windows
     * 可能对空 Job 拒绝的冗余 TerminateJobObject，避免把成功的命令误报为清理失败。
     */
    public void closeAfterRootExit() throws WindowsJobObjectException {
        synchronized (lifecycleLock) {
            shutdownLocked(false);
        }
    }

    /**
     * 只暴露生命周期状态而不暴露本地句柄，防止其它适配器在本类型之外复制所有权。
     */
    public boolean isClosed() {
        synchronized (lifecycleLock) {
            return WindowsKernel32Library.isNullHandle(jobHandle);
        }
    }

    /**
     * 关闭 Job 句柄并保留首个本地失败；关闭失败时有意保留所有权，使调用方可以重试而不会遗失句柄。
     */
    private void shutdownLocked(boolean terminateTree) throws WindowsJobObjectException {
        if (WindowsKernel32Library.isNullHandle(jobHandle)) {
            return;
        }
        WindowsJobObjectException terminationFailure = null;
        if (terminateTree && !terminated) {
            try {
                nativeApi.terminateJob(jobHandle);
                terminated = true;
            } catch (WindowsJobObjectException failure) {
                terminationFailure = failure;
            }
        }

        try {
            nativeApi.closeHandle(jobHandle);
            jobHandle = MemorySegment.NULL;
            nativeApi.close();
        } catch (WindowsJobObjectException closeFailure) {
            if (terminationFailure != null) {
                terminationFailure.addSuppressed(closeFailure);
                throw terminationFailure;
            }
            throw closeFailure;
        }
        if (terminationFailure != null) {
            throw terminationFailure;
        }
    }

    /**
     * 分配拒绝后关闭 Job，并把可能的清理异常挂到原始失败上，确保接纳失败时关闭且不隐藏根因。
     */
    private void closeAfterAssignFailure(WindowsJobObjectException failure) {
        if (WindowsKernel32Library.isNullHandle(jobHandle)) {
            return;
        }
        try {
            shutdownLocked(true);
        } catch (WindowsJobObjectException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    /**
     * 在延迟 Kernel32 适配器初始化前拒绝不支持的平台；不使用额外命令或 Java 进程枚举替代，
     * 因为两者都不能证明进程树所有权。
     */
    private static void requireWindows() throws WindowsJobObjectException {
        if (!isSupported()) {
            throw WindowsJobObjectException.contract("unsupported_platform");
        }
    }

    /**
     * 将关闭或非法状态转换为稳定契约错误，不向 RPC 或日志调用方暴露 PID、路径或本地化系统文本。
     */
    private void ensureOpen(String operation) throws WindowsJobObjectException {
        if (WindowsKernel32Library.isNullHandle(jobHandle)) {
            throw WindowsJobObjectException.nativeFailure(operation, ERROR_INVALID_HANDLE);
        }
    }

    /**
     * 平台操作的稳定受检失败。消息只包含契约码以及 Win32 无符号错误码，不复制路径、PID、命令
     * 或本地化文本到公共错误面。
     */
    public static final class WindowsJobObjectException extends IOException {
        /**
         * 保持跨 JVM 版本稳定的异常序列标识；该异常不携带本地句柄。
         */
        private static final long serialVersionUID = 1L;
        private final String code;
        private final int nativeError;

        /**
         * 只保存白名单操作码和数值 Win32 错误，供 RPC 确定性映射；原始异常仅作为本地诊断原因。
         */
        private WindowsJobObjectException(String code, int nativeError, Throwable cause) {
            super(code, cause);
            this.code = code;
            this.nativeError = nativeError;
        }

        /**
         * 返回供运行时集成和测试消费的稳定脱敏错误码。
         */
        public String code() {
            return code;
        }

        /**
         * 返回原始 Win32 数值错误码；零表示失败发生在系统错误码可用之前。
         */
        public int nativeError() {
            return nativeError;
        }

        /**
         * 不访问平台即可创建契约失败，用于不支持宿主及非法生命周期或进程输入。
         */
        static WindowsJobObjectException contract(String code) {
            return new WindowsJobObjectException("windows_job_" + code, ERROR_INVALID_PARAMETER, null);
        }

        /**
         * 创建脱敏本地失败，数值错误码不受系统语言影响。
         */
        static WindowsJobObjectException nativeFailure(String operation, int error) {
            int stableError = error == 0 ? ERROR_INVALID_PARAMETER : error;
            return new WindowsJobObjectException("windows_job_" + operation + "_"
                                                 + Integer.toUnsignedString(stableError), stableError, null);
        }

        /**
         * 转换 FFM 或 linker 失败且不在公共消息中暴露本地路径、命令或符号，异常原因仅供本地诊断。
         */
        static WindowsJobObjectException invocation(String operation, Throwable failure) {
            return new WindowsJobObjectException("windows_job_" + operation + "_invoke_failed",
                    ERROR_NONE, failure);
        }
    }
}
