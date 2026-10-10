// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.platform.windows;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 以单一所有者执行挂起 CreateProcess、分配 Job、恢复线程的原子接纳序列。
 */
final class WindowsProcessAdmission {
    private final WindowsProcessLaunchPolicy.LaunchSpec spec;
    private final WindowsJobObject job;
    private final WindowsProcessNativeApi api;
    private final PipeFactory pipeFactory;
    private final HandleCloser handleCloser;
    private final List<MemorySegment> childHandles = new ArrayList<>(3);
    private MemorySegment parentInput;
    private MemorySegment parentOutput;
    private MemorySegment parentError;

    /**
     * 捕获已经校验的输入和唯一原生 API owner；失败注入只替换管道创建与句柄关闭边界。
     */
    private WindowsProcessAdmission(
            WindowsProcessLaunchPolicy.LaunchSpec spec,
            WindowsJobObject job,
            WindowsProcessNativeApi api,
            PipeFactory pipeFactory,
            HandleCloser handleCloser) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.job = Objects.requireNonNull(job, "job");
        this.api = Objects.requireNonNull(api, "api");
        this.pipeFactory = Objects.requireNonNull(pipeFactory, "pipeFactory");
        this.handleCloser = Objects.requireNonNull(handleCloser, "handleCloser");
    }

    /**
     * 创建管道并将挂起子进程接纳到 Job，只有所有权建立后才恢复执行。
     */
    static Process launch(
            WindowsProcessLaunchPolicy.LaunchSpec spec,
            WindowsJobObject job) throws IOException {
        WindowsProcessNativeApi api = new WindowsProcessNativeApi();
        return launch(spec, job, api, api::createPipe, api::closeHandle);
    }

    /**
     * 允许包内故障测试只替换管道创建和句柄回收，生产入口仍共享同一个原生 API owner。
     */
    static Process launch(
            WindowsProcessLaunchPolicy.LaunchSpec spec,
            WindowsJobObject job,
            WindowsProcessNativeApi api,
            PipeFactory pipeFactory,
            HandleCloser handleCloser) throws IOException {
        return new WindowsProcessAdmission(spec, job, api, pipeFactory, handleCloser).launchOwned();
    }

    /**
     * 在完整 WindowsNativeProcess 返回前独占全部临时句柄，任一失败分支都在此收敛清理；
     * 接纳后的对象初始化异常也必须终止根进程并关闭创建句柄。
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    private Process launchOwned() throws IOException {
        try {
            WindowsProcessNativeApi.Pipe stdin = pipeFactory.create();
            parentInput = stdin.write();
            childHandles.add(stdin.read());

            WindowsProcessNativeApi.Pipe stdout = pipeFactory.create();
            parentOutput = stdout.read();
            childHandles.add(stdout.write());

            WindowsProcessNativeApi.Pipe stderr = pipeFactory.create();
            parentError = stderr.read();
            childHandles.add(stderr.write());
            api.disableInheritance(parentInput);
            api.disableInheritance(parentOutput);
            api.disableInheritance(parentError);
            WindowsProcessNativeApi.ProcessCreation creation = api.createSuspended(
                    spec, stdin.read(), stdout.write(), stderr.write());
            try {
                closeChildHandles();
            } catch (WindowsProcessNativeApi.WindowsFailure failure) {
                closeCreationQuietly(creation, failure);
                throw failure;
            }
            try {
                job.assignHandle(creation.processHandle());
                api.resumeThread(creation.threadHandle());
                api.closeHandle(creation.threadHandle());
                WindowsNativeProcess process = new WindowsNativeProcess(
                        api, creation.processHandle(), creation.processId(),
                        parentInput, parentOutput, parentError, creation.pseudoConsole());
                parentInput = null;
                parentOutput = null;
                parentError = null;
                return process;
            } catch (WindowsProcessNativeApi.WindowsFailure | IOException | RuntimeException failure) {
                closeCreationQuietly(creation, failure);
                throw failure;
            }
        } catch (WindowsProcessNativeApi.WindowsFailure failure) {
            closeChildHandlesQuietly();
            closeParentHandlesQuietly();
            api.close();
            throw failure.asIoException("windows_process_launch_failed");
        } catch (IOException failure) {
            closeChildHandlesQuietly();
            closeParentHandlesQuietly();
            api.close();
            throw failure;
        } catch (RuntimeException failure) {
            closeChildHandlesQuietly();
            closeParentHandlesQuietly();
            api.close();
            throw new IOException("windows_process_launch_failed", failure);
        }
    }

    /**
     * CreateProcess 返回后立即关闭父进程中的可继承子端，防止 EOF 被额外引用延迟；
     * 每个句柄关闭成功即移出 owner 列表，后续失败清理只重试仍由本对象持有的句柄。
     */
    private void closeChildHandles() throws WindowsProcessNativeApi.WindowsFailure {
        closeTrackedHandles(childHandles, handleCloser);
    }

    /**
     * 关闭成功后立刻放弃对应所有权；失败句柄保留供清理重试，避免复用已关闭的本地句柄值。
     */
    static void closeTrackedHandles(List<MemorySegment> handles, HandleCloser closer)
            throws WindowsProcessNativeApi.WindowsFailure {
        Objects.requireNonNull(handles, "handles");
        Objects.requireNonNull(closer, "closer");
        while (!handles.isEmpty()) {
            MemorySegment handle = handles.getFirst();
            closer.close(handle);
            handles.removeFirst();
        }
    }

    /**
     * 释放部分接纳的根进程及两个创建句柄，并把次生异常挂到主失败而不覆盖根因。
     */
    private void closeCreationQuietly(
            WindowsProcessNativeApi.ProcessCreation creation,
            Exception failure) {
        try {
            api.terminate(creation.processHandle());
        } catch (WindowsProcessNativeApi.WindowsFailure cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        api.closeQuietly(creation.threadHandle());
        api.closeQuietly(creation.processHandle());
        api.closePseudoConsoleQuietly(creation.pseudoConsole());
    }

    /**
     * 接纳或本地初始化失败后尽力释放所有子端句柄。
     */
    private void closeChildHandlesQuietly() {
        for (MemorySegment handle : childHandles) {
            closeQuietly(handle);
        }
        childHandles.clear();
    }

    /**
     * 无法返回进程对象时释放父端管道，避免失败路径保留输入输出资源。
     */
    private void closeParentHandlesQuietly() {
        closeQuietly(parentInput);
        closeQuietly(parentOutput);
        closeQuietly(parentError);
        parentInput = null;
        parentOutput = null;
        parentError = null;
    }

    /**
     * 部分建管道失败时跳过尚未取得的端点，同时让每个已登记句柄继续经过唯一关闭器。
     */
    private void closeQuietly(MemorySegment handle) {
        if (handle != null && !WindowsKernel32Library.isNullHandle(handle)) {
            try {
                handleCloser.close(handle);
            } catch (WindowsProcessNativeApi.WindowsFailure ignored) {
                // 保留启动失败作为主结果；当前句柄已走过唯一关闭路径。
            }
        }
    }

    /**
     * 只抽象可失败的 CreatePipe 调用，使逐阶段分配失败可在不启动子进程时验证。
     */
    @FunctionalInterface
    interface PipeFactory {
        /**
         * 创建一对尚未转移给子进程的管道端点。
         */
        WindowsProcessNativeApi.Pipe create() throws WindowsProcessNativeApi.WindowsFailure;
    }

    /**
     * 允许关闭失败留在 owner 列表中供后续尽力重试，同时保留原生关闭的受检失败。
     */
    @FunctionalInterface
    interface HandleCloser {
        /**
         * 仅在底层确认关闭成功后正常返回。
         */
        void close(MemorySegment handle) throws WindowsProcessNativeApi.WindowsFailure;
    }
}
