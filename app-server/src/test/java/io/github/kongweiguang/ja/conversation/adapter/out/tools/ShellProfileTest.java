// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定三平台环境块与 Tool 描述，避免模型混用 Shell 方言。 */
class ShellProfileTest {
    /** 显式构造平台矩阵，不依赖当前测试主机即可验证唯一方言契约。 */
    @Test
    void eachPlatformDeclaresOnlyItsNativeShell() {
        assertProfile(new ShellProfile(ShellProfile.OperatingSystem.WINDOWS, ShellProfile.Dialect.POWERSHELL,
                Path.of("C:/tools/pwsh.exe"), List.of("-NoProfile", "-NonInteractive", "-Command"), "windows",
                Map.of("PATHEXT", ".EXE;.CMD")),
                "os: windows", "shell: powershell", "Execute PowerShell 7 commands",
                List.of("windows_powershell", "zsh", "bash"));
        assertProfile(new ShellProfile(ShellProfile.OperatingSystem.WINDOWS,
                ShellProfile.Dialect.WINDOWS_POWERSHELL,
                Path.of("C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe"),
                List.of("-NoProfile", "-NonInteractive", "-Command"), "windows",
                Map.of("PATHEXT", ".EXE;.CMD")),
                "os: windows", "shell: windows_powershell", "Execute Windows PowerShell 5.1 commands",
                List.of("shell: powershell", "zsh", "bash"));
        assertProfile(new ShellProfile(ShellProfile.OperatingSystem.MACOS, ShellProfile.Dialect.ZSH,
                Path.of("/bin/zsh"), List.of("-lc"), "posix", Map.of()),
                "os: macos", "shell: zsh", "Execute zsh commands", List.of("powershell", "bash"));
        assertProfile(new ShellProfile(ShellProfile.OperatingSystem.LINUX, ShellProfile.Dialect.BASH,
                Path.of("/bin/bash"), List.of("-lc"), "posix", Map.of()),
                "os: linux", "shell: bash", "Execute Bash commands", List.of("powershell", "zsh"));
    }

    /** 同时验证环境块、动态描述和命令尾部，确保 Profile 不转换模型命令。 */
    private static void assertProfile(ShellProfile profile, String os, String shell, String description,
                                      List<String> forbidden) {
        String environment = profile.executionEnvironment(Path.of("workspace"));
        assertFalse(environment.endsWith("\n"));
        assertTrue(environment.contains(os));
        assertTrue(environment.contains(shell));
        for (String value : forbidden) assertFalse(environment.contains("shell: " + value));
        assertEquals(description, profile.toolDescription());
        assertEquals("echo native", profile.commandLine("echo native").getLast());
    }
}
