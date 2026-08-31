// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定 Shell 子进程的环境白名单、PATH 边界和 Windows 扩展解析。 */
final class ShellProcessEnvironmentTest {
    @TempDir Path temp;

    /**
     * `env_clear` 输入缺 PATHEXT 时仍要构造 `.exe/.cmd` 解析能力，同时 secret 与相对 PATH
     * 项不得进入冻结 Profile。
     */
    @Test
    void constructsBoundedWindowsEnvironmentWithoutSecrets() {
        Path bin = temp.resolve("node-bin").toAbsolutePath();
        Map<String, String> source = new HashMap<>();
        source.put("PATH", bin + File.pathSeparator + "." + File.pathSeparator + bin);
        source.put("SystemRoot", temp.resolve("Windows").toAbsolutePath().toString());
        source.put("ComSpec", temp.resolve("Windows/System32/cmd.exe").toAbsolutePath().toString());
        source.put("TEMP", temp.toAbsolutePath().toString());
        source.put("OPENAI_API_KEY", "must-not-cross-shell-boundary");

        Map<String, String> captured = ShellProcessEnvironment.capture(
                ShellProfile.OperatingSystem.WINDOWS, source::get);

        assertTrue(captured.get("PATH").contains(bin.toString()));
        assertFalse(captured.get("PATH").contains(File.pathSeparator + "."));
        assertTrue(captured.get("PATHEXT").contains(".EXE"));
        assertTrue(captured.get("PATHEXT").contains(".CMD"));
        assertFalse(captured.containsKey("OPENAI_API_KEY"));
        assertFalse(captured.toString().contains("must-not-cross-shell-boundary"));
    }

    /** Profile 拒绝测试或未来 composition 注入白名单外变量，不能只依赖生产 capture。 */
    @Test
    void profileRejectsNonAllowlistedEnvironment() {
        assertThrows(IllegalArgumentException.class, () -> new ShellProfile(
                ShellProfile.OperatingSystem.WINDOWS, ShellProfile.Dialect.POWERSHELL,
                temp.resolve("pwsh.exe"), java.util.List.of("-Command"), "windows",
                Map.of("PATHEXT", ".EXE;.CMD", "AUTHORIZATION", "secret")));
    }
}
