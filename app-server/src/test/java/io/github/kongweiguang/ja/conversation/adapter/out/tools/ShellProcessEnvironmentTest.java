// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 锁定 Shell 对父进程环境的完整继承与原生进程边界校验。 */
final class ShellProcessEnvironmentTest {
    @TempDir Path temp;

    /**
     * 变量名、凭据标识、代理配置和相对 PATH 都必须逐项保留；Shell 边界不能替用户猜测哪些变量安全。
     */
    @Test
    void capturesEveryParentEnvironmentEntryWithoutRewriting() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("PATH", ".;C:\\custom-bin;C:\\custom-bin");
        source.put("ProgramFiles(x86)", "C:\\Program Files (x86)");
        source.put("HTTPS_PROXY", "http://proxy.example.test:8080");
        source.put("GH_CONFIG_DIR", "C:\\Users\\test\\AppData\\Roaming\\GitHub CLI");
        source.put("JA_TEST_MARKER", "visible-to-shell");

        Map<String, String> captured = ShellProcessEnvironment.capture(
                ShellProfile.OperatingSystem.WINDOWS, source);

        assertEquals(source, captured);
    }

    /** 只有 NUL 和 Windows 环境块无法表示的变量名被拒绝，普通括号和控制字符值仍可传递。 */
    @Test
    void rejectsOnlyUnrepresentableEnvironmentEntries() {
        Map<String, String> newlineValue = Map.of("PROFILE_MARKER", "line-one\nline-two");
        assertEquals(newlineValue, ShellProcessEnvironment.validate(
                ShellProfile.OperatingSystem.WINDOWS, newlineValue));
        assertEquals(Map.of("=C:", "C:\\workspace"), ShellProcessEnvironment.validate(
                ShellProfile.OperatingSystem.WINDOWS, Map.of("=C:", "C:\\workspace")));
        assertThrows(IllegalArgumentException.class, () -> ShellProcessEnvironment.validate(
                ShellProfile.OperatingSystem.LINUX, Map.of("BAD=NAME", "value")));
        assertThrows(IllegalArgumentException.class, () -> ShellProcessEnvironment.validate(
                ShellProfile.OperatingSystem.WINDOWS, Map.of("BAD\0NAME", "value")));
        assertThrows(IllegalArgumentException.class, () -> ShellProcessEnvironment.validate(
                ShellProfile.OperatingSystem.WINDOWS, Map.of("BAD", "value\0")));
    }

    /** ShellProfile 也必须保留任意正常宿主变量，防止未来 composition 再引入秘密关键词白名单。 */
    @Test
    void profilePreservesArbitraryEnvironment() {
        Map<String, String> environment = Map.of(
                "PATHEXT", ".EXE;.CMD",
                "AUTHORIZATION", "test-marker",
                "ProgramFiles(x86)", temp.toString());

        ShellProfile profile = new ShellProfile(
                ShellProfile.OperatingSystem.WINDOWS, ShellProfile.Dialect.POWERSHELL,
                temp.resolve("pwsh.exe"), java.util.List.of("-NonInteractive", "-Command"), "windows",
                environment);

        assertEquals(environment, profile.environment());
    }
}
