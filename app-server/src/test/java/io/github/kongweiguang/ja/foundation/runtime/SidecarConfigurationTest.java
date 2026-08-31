// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 sidecar 启动参数只接受当前严格绝对目录契约。 */
final class SidecarConfigurationTest {
    /** Base64URL 解码必须保持严格 UTF-8，防止替换字符把 Host 目录静默改向。 */
    @Test
    void rejectsMalformedUtf8Directory() {
        String malformed = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[]{(byte) 0xC3, 0x28});
        assertThrows(IllegalArgumentException.class,
                () -> SidecarConfiguration.fromArgs(new String[]{"--home-dir-base64=" + malformed}));
    }

    /** 相对路径依赖进程 cwd 且不可复现，因此即使编码合法也不能成为 sidecar 目录。 */
    @Test
    void rejectsRelativeDirectory() {
        assertThrows(IllegalArgumentException.class,
                () -> SidecarConfiguration.fromArgs(new String[]{"--home-dir-base64="
                        + encode(Path.of("relative", "data"))}));
    }

    /** 重复参数会制造值覆盖顺序差异，因此首次出现后必须拒绝同一 owner 的再次声明。 */
    @Test
    void rejectsDuplicateArguments() {
        String home = encode(absolute("home"));
        assertThrows(IllegalArgumentException.class, () -> SidecarConfiguration.fromArgs(
                new String[]{"--home-dir-base64=" + home, "--home-dir-base64=" + home}));
    }

    /** 未声明参数没有 owner，必须直接失败而不能被静默忽略。 */
    @Test
    void rejectsUnknownArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> SidecarConfiguration.fromArgs(new String[]{"--unsupported-option=value"}));
    }

    /** Host 必须一次性提供全部目录，禁止回退到 cwd、环境变量或临时目录。 */
    @Test
    void rejectsMissingDirectories() {
        assertThrows(IllegalArgumentException.class, () -> SidecarConfiguration.fromArgs(new String[0]));
    }

    /** 合法路径必须按绝对规范化结果保存，使不同调用方观察到同一目录身份。 */
    @Test
    void normalizesAllDecodedAbsoluteDirectories() {
        Path home = absolute("home").resolve("..").resolve("home");
        Path data = absolute("data");
        Path run = absolute("run");
        Path logs = absolute("logs");
        SidecarConfiguration configuration = SidecarConfiguration.fromArgs(new String[]{
                "--home-dir-base64=" + encode(home), "--data-dir-base64=" + encode(data),
                "--run-dir-base64=" + encode(run), "--log-dir-base64=" + encode(logs)});
        assertEquals(home.normalize(), configuration.homeDirectory());
        assertEquals(data.normalize(), configuration.dataDirectory());
        assertEquals(run.normalize(), configuration.runDirectory());
        assertEquals(logs.normalize(), configuration.logDirectory());
    }

    /** 为每个案例生成不会触碰文件系统的绝对目录身份。 */
    private static Path absolute(String leaf) {
        return Path.of(System.getProperty("java.io.tmpdir"), "ja-sidecar-configuration", leaf)
                .toAbsolutePath();
    }

    /** 按生产 Host 使用的无填充 Base64URL 编码路径。 */
    private static String encode(Path path) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(path.toString().getBytes(StandardCharsets.UTF_8));
    }
}
