// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定 Windows Shell 优先级与完全缺失时的非致命降级。 */
class ShellCapabilityTest {

    /** PATH 中可预检的 pwsh 必须优先于系统 Windows PowerShell。 */
    @Test
    void prefersPowerShellSeven() {
        Map<String, String> environment = Map.of(
                "PATH", Path.of("tools", "powershell-seven") + File.pathSeparator + Path.of("tools", "other"),
                "SystemRoot", Path.of("windows").toString());
        ShellCapability capability = ShellCapability.detectAndPreflight("Windows 11", environment::get,
                candidate -> candidate.executable().getFileName().toString().equalsIgnoreCase("pwsh.exe"));
        assertEquals(ShellProfile.Dialect.POWERSHELL, capability.profile().orElseThrow().dialect());
    }

    /** 所有 pwsh 候选失败后只回退一次 Windows PowerShell 5.1，不因缺少 PowerShell 7 终止服务。 */
    @Test
    void fallsBackToWindowsPowerShell() {
        Map<String, String> environment = Map.of(
                "PATH", Path.of("missing", "one") + File.pathSeparator + Path.of("missing", "two"),
                "SystemRoot", Path.of("windows").toString());
        ShellCapability capability = ShellCapability.detectAndPreflight("Windows 11", environment::get,
                candidate -> candidate.dialect() == ShellProfile.Dialect.WINDOWS_POWERSHELL);
        ShellProfile profile = capability.profile().orElseThrow();
        assertEquals(ShellProfile.Dialect.WINDOWS_POWERSHELL, profile.dialect());
        assertTrue(profile.executable().toString().contains("WindowsPowerShell"));
    }

    /** 两种 Windows Shell 都不可用时保持平台上下文，但不再暴露虚假的 Shell Profile。 */
    @Test
    void missingShellProducesExplicitUnavailableCapability() {
        Map<String, String> environment = Map.of("PATH", Path.of("missing").toString(),
                "SystemRoot", Path.of("windows").toString());
        ShellCapability capability = ShellCapability.detectAndPreflight("Windows 11", environment::get,
                candidate -> false);
        assertTrue(capability.profile().isEmpty());
        String executionEnvironment = capability.executionEnvironment(Path.of("workspace"));
        assertTrue(executionEnvironment.contains("os: windows"));
        assertTrue(executionEnvironment.contains("shell: unavailable"));
        assertFalse(executionEnvironment.endsWith("\n"));
    }
}
