// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证生产 Logback 资源能够持久化诊断信息，同时不污染承载 JSONL 协议的标准输出。 */
final class LogbackFilePersistenceTest {
    /**
     * 在隔离上下文加载打包后的配置，使断言覆盖真实 Appender、路径替换和过滤行为，
     * 而不是仅匹配 XML 文本。
     */
    @Test
    void writesInfoAndErrorLogsUnderFixedJaHome(@TempDir Path userHome)
            throws Exception {
        String previousUserHome = System.getProperty("user.home");
        String previousLogDirectory = System.getProperty("ja.log-dir");
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        Path logDirectory = userHome.resolve(".ja/logs/java");
        try {
            System.setProperty("user.home", userHome.toString());
            System.clearProperty("ja.log-dir");
            URL configuration = LogbackFilePersistenceTest.class.getResource("/logback.xml");
            assertTrue(configuration != null, "logback.xml must be packaged as a runtime resource");

            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(configuration);

            ch.qos.logback.classic.Logger logger =
                    context.getLogger("io.github.kongweiguang.ja.logging.persistence");
            logger.debug("debug-marker-must-not-be-persisted");
            logger.info("info-marker-must-be-persisted");
            logger.error("error-marker-must-be-persisted");
        } finally {
            context.stop();
            restoreProperty("user.home", previousUserHome);
            restoreProperty("ja.log-dir", previousLogDirectory);
        }

        String appServerLog = Files.readString(logDirectory.resolve("app-server.log"));
        String errorLog = Files.readString(logDirectory.resolve("app-server-error.log"));
        assertTrue(appServerLog.contains("info-marker-must-be-persisted"));
        assertTrue(appServerLog.contains("error-marker-must-be-persisted"));
        assertFalse(appServerLog.contains("debug-marker-must-not-be-persisted"));
        assertTrue(errorLog.contains("error-marker-must-be-persisted"));
        assertFalse(errorLog.contains("info-marker-must-be-persisted"));
    }

    /** 验证包含 Unicode 的 Windows 路径使用与 Rust Host 相同的严格 ASCII 安全参数。 */
    @Test
    void decodesExplicitLogDirectoryArgument(@TempDir Path temporaryDirectory) {
        Path logDirectory = temporaryDirectory.resolve("日志/java").toAbsolutePath().normalize();
        Path home = temporaryDirectory.resolve("home").toAbsolutePath().normalize();
        Path data = temporaryDirectory.resolve("data").toAbsolutePath().normalize();
        Path run = temporaryDirectory.resolve("run").toAbsolutePath().normalize();
        SidecarConfiguration configuration = SidecarConfiguration.fromArgs(
                new String[]{argument("home", home), argument("data", data), argument("run", run),
                        argument("log", logDirectory), "--ja-runtime-generation=1"});
        assertEquals(logDirectory, configuration.logDirectory());
    }

    /** 使用与 Rust Host 一致的无填充 Base64URL 格式构造一个目录参数。 */
    private static String argument(String name, Path path) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(path.toString().getBytes(StandardCharsets.UTF_8));
        return "--" + name + "-dir-base64=" + encoded;
    }

    /** 恢复进程级测试状态，确保后续 Solon 测试仍使用各自独立的存储边界。 */
    private static void restoreProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
    }
}
