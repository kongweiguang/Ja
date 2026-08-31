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
    private final WindowsProcessNativeApi api = new WindowsProcessNativeApi();
    private final List<MemorySegment> childHandles = new ArrayList<>();
    private MemorySegment parentInput;
    private MemorySegment parentOutput;
    private MemorySegment parentError;

    /**
     * 捕获已经校验的输入和调用方 Job，此阶段不创建进程或管道句柄。
     */
    private WindowsProcessAdmission(
            WindowsProcessLaunchPolicy.LaunchSpec spec,
            WindowsJobObject job) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.job = Objects.requireNonNull(job, "job");
    }

    /**
     * 创建管道并将挂起子进程接纳到 Job，只有所有权建立后才恢复执行。
     */
    static Process launch(
            WindowsProcessLaunchPolicy.LaunchSpec spec,
            WindowsJobObject job) throws IOException {
        return new WindowsProcessAdmission(spec, job).launchOwned();
    }

    /**
     * 在完整 WindowsNativeProcess 返回前独占全部临时句柄，任一失败分支都在此收敛清理。
     */
    private Process launchOwned() throws IOException {
        try {
            WindowsProcessNativeApi.Pipe stdin = api.createPipe();
            WindowsProcessNativeApi.Pipe stdout = api.createPipe();
            WindowsProcessNativeApi.Pipe stderr = api.createPipe();
            parentInput = stdin.write();
            parentOutput = stdout.read();
            parentError = stderr.read();
            childHandles.add(stdin.read());
            childHandles.add(stdout.write());
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
                        parentInput, parentOutput, parentError);
                parentInput = null;
                parentOutput = null;
                parentError = null;
                return process;
            } catch (WindowsProcessNativeApi.WindowsFailure | IOException failure) {
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
     * CreateProcess 返回后立即关闭父进程中的可继承子端，防止 EOF 被额外引用延迟。
     */
    private void closeChildHandles() throws WindowsProcessNativeApi.WindowsFailure {
        for (MemorySegment handle : childHandles) {
            api.closeHandle(handle);
        }
        childHandles.clear();
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
    }

    /**
     * 接纳或本地初始化失败后尽力释放所有子端句柄。
     */
    private void closeChildHandlesQuietly() {
        for (MemorySegment handle : childHandles) {
            api.closeQuietly(handle);
        }
        childHandles.clear();
    }

    /**
     * 无法返回进程对象时释放父端管道，避免失败路径保留输入输出资源。
     */
    private void closeParentHandlesQuietly() {
        api.closeQuietly(parentInput);
        api.closeQuietly(parentOutput);
        api.closeQuietly(parentError);
        parentInput = null;
        parentOutput = null;
        parentError = null;
    }
}
