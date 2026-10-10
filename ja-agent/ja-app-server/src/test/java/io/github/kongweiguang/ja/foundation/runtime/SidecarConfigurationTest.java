// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 启动参数是宿主与 Java 进程的传输契约；测试拒绝旧 TCP 参数，避免隐式回退到已移除的监听。
 */
final class SidecarConfigurationTest {
    /**
     * Host 与 stdio transport 已迁移到 WebSocket；拒绝 TCP 可防止旧 sidecar 参数静默恢复不兼容启动路径。
     */
    @Test
    void acceptsOnlyWebSocketTransport() {
        assertDoesNotThrow(() -> SidecarConfiguration.fromArgs(argumentsFor("--ja-transport=websocket")));
        assertThrows(IllegalArgumentException.class,
                () -> SidecarConfiguration.fromArgs(argumentsFor("--ja-transport=tcp")));
    }

    /** FeatHTTP 原生 host/port 只在 WebSocket 模式生效，非法或重复参数必须在监听前失败。 */
    @Test
    void validatesWebSocketListenAddress() {
        SidecarConfiguration configured = SidecarConfiguration.fromArgs(argumentsFor(
                "--ja-transport=websocket", "--server.host=0.0.0.0", "--server.port=39000"));
        assertEquals("0.0.0.0", configured.listenHost());
        assertEquals(39_000, configured.listenPort());

        SidecarConfiguration defaults = SidecarConfiguration.fromArgs(argumentsFor("--ja-transport=websocket"));
        assertEquals("127.0.0.1", defaults.listenHost());
        assertEquals(0, defaults.listenPort());
        assertEquals("127.0.0.1", SidecarConfiguration.fromArgs(argumentsFor(
                "--ja-transport=websocket", "--server.host=localhost")).listenHost());

        for (String invalid : new String[] {"--server.host=example.com", "--server.host=::1",
                "--server.port=-1", "--server.port=65536", "--server.port=abc"}) {
            assertThrows(IllegalArgumentException.class, () -> SidecarConfiguration.fromArgs(argumentsFor(
                    "--ja-transport=websocket", invalid)), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> SidecarConfiguration.fromArgs(argumentsFor(
                "--server.port=39000")));
        assertThrows(IllegalArgumentException.class, () -> SidecarConfiguration.fromArgs(argumentsFor(
                "--ja-transport=websocket", "--server.port=39000", "--server.port=39001")));
    }

    /**
     * 通过绝对临时目录满足 Host 参数契约，不依赖宿主机实际创建目录或读取外部配置。
     */
    private static String[] argumentsFor(String... options) {
        Path root = Path.of(System.getProperty("java.io.tmpdir"));
        String[] arguments = {
                directoryArgument("home", root.resolve("ja-test-home")),
                directoryArgument("data", root.resolve("ja-test-data")),
                directoryArgument("run", root.resolve("ja-test-run")),
                directoryArgument("log", root.resolve("ja-test-log")),
                "--ja-runtime-generation=1"
        };
        String[] combined = Arrays.copyOf(arguments, arguments.length + options.length);
        System.arraycopy(options, 0, combined, arguments.length, options.length);
        return combined;
    }

    /**
     * 路径以 Base64URL 形式输入，避免 Windows 分隔符和空格影响参数边界测试。
     */
    private static String directoryArgument(String name, Path directory) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(directory.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
        return "--" + name + "-dir-base64=" + encoded;
    }
}
