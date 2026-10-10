// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.infrastructure.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定三平台环境块与命令入口，避免持续进程工具混用 Shell 方言。 */
class ShellProfileTest {
    /** 显式构造平台矩阵，不依赖当前测试主机即可验证唯一方言契约。 */
    @Test
    void eachPlatformDeclaresOnlyItsNativeShell() {
        assertProfile(new ShellProfile(ShellProfile.OperatingSystem.WINDOWS, ShellProfile.Dialect.POWERSHELL,
                Path.of("C:/tools/pwsh.exe"), List.of("-NonInteractive", "-Command"), "windows",
                Map.of("PATHEXT", ".EXE;.CMD")),
                "os: windows", "shell: powershell",
                List.of("windows_powershell", "zsh", "bash"));
        assertProfile(new ShellProfile(ShellProfile.OperatingSystem.WINDOWS,
                ShellProfile.Dialect.WINDOWS_POWERSHELL,
                Path.of("C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe"),
                List.of("-NonInteractive", "-Command"), "windows",
                Map.of("PATHEXT", ".EXE;.CMD")),
                "os: windows", "shell: windows_powershell",
                List.of("shell: powershell", "zsh", "bash"));
        assertProfile(new ShellProfile(ShellProfile.OperatingSystem.MACOS, ShellProfile.Dialect.ZSH,
                Path.of("/bin/zsh"), List.of("-lc"), "posix", Map.of()),
                "os: macos", "shell: zsh", List.of("powershell", "bash"));
        assertProfile(new ShellProfile(ShellProfile.OperatingSystem.LINUX, ShellProfile.Dialect.BASH,
                Path.of("/bin/bash"), List.of("-lc"), "posix", Map.of()),
                "os: linux", "shell: bash", List.of("powershell", "zsh"));
    }

    /** Windows 环境块必须直接给出 POSIX 替代写法，避免仅声明 shell 名称仍被弱模型忽略。 */
    @Test
    void windowsProfilesDeclareConcretePowerShellAlternatives() {
        ShellProfile profile = new ShellProfile(ShellProfile.OperatingSystem.WINDOWS,
                ShellProfile.Dialect.POWERSHELL, Path.of("C:/tools/pwsh.exe"),
                List.of("-NonInteractive", "-Command"), "windows",
                Map.of("PATHEXT", ".EXE;.CMD"));

        String environment = profile.executionEnvironment(Path.of("workspace"));

        assertTrue(environment.contains("This is PowerShell 7, not Bash"));
        assertTrue(environment.contains("Do not use POSIX-only commands such as head,"));
        assertTrue(environment.contains("grep, sed, or awk"));
        assertTrue(environment.contains("Select-Object -First/-Last"));
        assertTrue(environment.contains("rg.exe"));
    }

    /** Windows 实际探测要在根进程退出后关闭 Job，不能把空 Job 的冗余终止误报为 Shell 缺失。 */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsPreflightClosesJobAfterProbeExit() {
        ShellCapability capability = ShellCapability.detectAndPreflight();

        assertTrue(capability.profile().isPresent(), "the Windows system PowerShell should be available");
    }

    /** 环境方言与真实命令尾部必须一致；持续工具共享 Profile，不再维护旧 Shell Tool 描述。 */
    private static void assertProfile(ShellProfile profile, String os, String shell,
                                      List<String> forbidden) {
        String environment = profile.executionEnvironment(Path.of("workspace"));
        assertFalse(environment.endsWith("\n"));
        assertTrue(environment.contains(os));
        assertTrue(environment.contains(shell));
        for (String value : forbidden) {
            assertFalse(environment.contains("shell: " + value));
        }
        assertEquals("echo native", profile.commandLine("echo native").getLast());
    }
}
