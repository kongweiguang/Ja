// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.platform.windows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 协调启动策略校验与 Windows 挂起进程接纳边界，不直接持有本地资源。
 */
public final class WindowsProcessLauncher {
    /**
     * 静态入口不允许实例化，避免产生无状态对象。
     */
    private WindowsProcessLauncher() {
    }

    /**
     * 在分配本地资源前冻结调用方输入，且仅在 Job 建立所有权后返回；保持协调器精简可防止
     * 策略代码意外获取本地资源。
     */
    public static Process launch(
            List<String> command,
            Path workingDirectory,
            Map<String, String> environment,
            WindowsJobObject job) throws IOException {
        if (!WindowsJobObject.isSupported()) {
            throw new IOException("windows_process_unsupported_platform");
        }
        Objects.requireNonNull(job, "job");
        WindowsProcessLaunchPolicy.LaunchSpec spec =
                WindowsProcessLaunchPolicy.validate(command, workingDirectory, environment);
        return WindowsProcessAdmission.launch(spec, job);
    }

    /**
     * 只释放由本适配器创建的进程句柄；拒绝外部 Process 实现，防止引入第二套清理所有权模型。
     */
    @SuppressWarnings("PMD.CloseResource")
    public static void close(Process process) throws IOException {
        Objects.requireNonNull(process, "process");
        if (!(process instanceof WindowsNativeProcess nativeProcess)) {
            throw new IOException("windows_process_owner_invalid");
        }
        nativeProcess.close();
    }
}
