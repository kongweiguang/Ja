// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.platform.windows;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * 只封装 Windows Job Object 所需的固定 Kernel32 操作，不持有业务生命周期状态。
 */
final class WindowsJobObjectNativeApi implements AutoCloseable {
    /**
     * SetInformationJobObject 的扩展限制信息类别。
     */
    private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;
    /**
     * 关闭 Job 句柄时强制终止全部成员进程的限制标志。
     */
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x0000_2000;
    /**
     * 64 位 Windows 中 JOBOBJECT_EXTENDED_LIMIT_INFORMATION 的结构大小。
     */
    private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_BYTES = 144;
    /**
     * 基础限制结构中 LimitFlags 字段的 64 位布局偏移。
     */
    private static final int BASIC_LIMIT_INFORMATION_LIMIT_FLAGS_OFFSET = 16;
    /**
     * AssignProcessToJobObject 所需的进程配额设置权限。
     */
    private static final int PROCESS_SET_QUOTA = 0x0100;
    /**
     * Job 接管和失效关闭所需的进程终止权限。
     */
    private static final int PROCESS_TERMINATE = 0x0001;

    private final WindowsKernel32Library library;
    private final MethodHandle createJobObject;
    private final MethodHandle setInformationJobObject;
    private final MethodHandle openProcess;
    private final MethodHandle assignProcessToJobObject;
    private final MethodHandle terminateJobObject;
    private final MethodHandle closeHandle;

    /**
     * 延迟创建共享 FFM 会话并解析 Job Object 固定符号，部分解析失败时不会遗留 library Arena。
     */
    WindowsJobObjectNativeApi() {
        library = new WindowsKernel32Library("windows_job_native_symbols_unavailable");
        try {
            createJobObject = library.downcall("CreateJobObjectW",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));
            setInformationJobObject = library.downcall("SetInformationJobObject",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            openProcess = library.downcall("OpenProcess",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            assignProcessToJobObject = library.downcall("AssignProcessToJobObject",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));
            terminateJobObject = library.downcall("TerminateJobObject",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT));
            closeHandle = library.downcall("CloseHandle",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        } catch (Throwable failure) {
            library.closeQuietly();
            throw new IllegalStateException("windows_job_native_symbols_unavailable", failure);
        }
    }

    /**
     * 创建 Job 后先设置 KILL_ON_JOB_CLOSE；配置失败会立即关闭句柄，禁止半初始化对象逃逸。
     */
    MemorySegment createConfiguredJob() throws WindowsJobObject.WindowsJobObjectException {
        WindowsKernel32Library.NativeCall create = invoke(
                createJobObject, "create", MemorySegment.NULL, MemorySegment.NULL);
        MemorySegment job = (MemorySegment) create.value();
        if (WindowsKernel32Library.isNullHandle(job)) {
            throw WindowsJobObject.WindowsJobObjectException.nativeFailure("create", create.error());
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment limits = arena.allocate(JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_BYTES, 8);
            limits.set(ValueLayout.JAVA_INT, BASIC_LIMIT_INFORMATION_LIMIT_FLAGS_OFFSET,
                    JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE);
            WindowsKernel32Library.NativeCall configure = invoke(
                    setInformationJobObject, "configure", job,
                    JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, limits,
                    JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_BYTES);
            if ((int) configure.value() == 0) {
                throw WindowsJobObject.WindowsJobObjectException.nativeFailure(
                        "configure", configure.error());
            }
            return job;
        } catch (WindowsJobObject.WindowsJobObjectException failure) {
            closeFailedConfiguration(job, failure);
            throw failure;
        } catch (Throwable failure) {
            WindowsJobObject.WindowsJobObjectException invocation =
                    WindowsJobObject.WindowsJobObjectException.invocation("configure", failure);
            closeFailedConfiguration(job, invocation);
            throw invocation;
        }
    }

    /**
     * 只以分配所需最小权限打开根进程句柄，临时句柄由调用方在同一方法链关闭。
     */
    MemorySegment openProcess(int pid) throws WindowsJobObject.WindowsJobObjectException {
        WindowsKernel32Library.NativeCall open = invoke(
                openProcess, "open_process", PROCESS_TERMINATE | PROCESS_SET_QUOTA, 0, pid);
        MemorySegment handle = (MemorySegment) open.value();
        if (WindowsKernel32Library.isNullHandle(handle)) {
            throw WindowsJobObject.WindowsJobObjectException.nativeFailure("open_process", open.error());
        }
        return handle;
    }

    /**
     * 将一个已验证的根进程句柄关联到目标 Job，后续子进程由 Windows 继承归属。
     */
    void assign(MemorySegment job, MemorySegment process)
            throws WindowsJobObject.WindowsJobObjectException {
        WindowsKernel32Library.NativeCall assign = invoke(
                assignProcessToJobObject, "assign", job, process);
        if ((int) assign.value() == 0) {
            throw WindowsJobObject.WindowsJobObjectException.nativeFailure("assign", assign.error());
        }
    }

    /**
     * 请求终止 Job 内全部进程；即使失败，上层仍会尝试关闭 KILL_ON_JOB_CLOSE 句柄。
     */
    void terminateJob(MemorySegment job) throws WindowsJobObject.WindowsJobObjectException {
        WindowsKernel32Library.NativeCall terminate = invoke(
                terminateJobObject, "terminate", job, 1);
        if ((int) terminate.value() == 0) {
            throw WindowsJobObject.WindowsJobObjectException.nativeFailure(
                    "terminate", terminate.error());
        }
    }

    /**
     * 关闭一个 Win32 句柄；失败时仍由上层保留所有权，以便后续重试。
     */
    void closeHandle(MemorySegment handle) throws WindowsJobObject.WindowsJobObjectException {
        WindowsKernel32Library.NativeCall close = invoke(closeHandle, "close", handle);
        if ((int) close.value() == 0) {
            throw WindowsJobObject.WindowsJobObjectException.nativeFailure("close", close.error());
        }
    }

    /**
     * 释放配置失败的 Job 句柄并把次生关闭异常挂到主失败上，避免丢失首要错误。
     */
    private void closeFailedConfiguration(
            MemorySegment job,
            WindowsJobObject.WindowsJobObjectException failure) {
        try {
            closeHandle(job);
        } catch (WindowsJobObject.WindowsJobObjectException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /**
     * 把通用 FFM 调用异常转换成 Job Object 的稳定错误契约，不泄漏符号或本地参数。
     */
    private WindowsKernel32Library.NativeCall invoke(
            MethodHandle function,
            String operation,
            Object... arguments) throws WindowsJobObject.WindowsJobObjectException {
        try {
            return library.invoke(function, operation, arguments);
        } catch (WindowsKernel32Library.InvocationFailure failure) {
            throw WindowsJobObject.WindowsJobObjectException.invocation(
                    failure.operation(), failure);
        }
    }

    /**
     * Job 句柄成功释放后再关闭符号 Arena，避免仍在使用的 MethodHandle 失效。
     */
    @Override
    public void close() {
        library.close();
    }

    /**
     * 构造或创建失败时执行尽力释放，保留原始失败作为对外证据。
     */
    void closeQuietly() {
        library.closeQuietly();
    }
}
