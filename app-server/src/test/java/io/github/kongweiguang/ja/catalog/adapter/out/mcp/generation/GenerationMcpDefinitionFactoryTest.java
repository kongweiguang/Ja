// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.generation;

import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpServerDefinition;
import io.github.kongweiguang.ja.catalog.port.out.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationGenerationSnapshot;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 stdio 配置覆盖和类型化凭据只在短生命周期 definition 中合并。 */
final class GenerationMcpDefinitionFactoryTest {
    /** 配置环境与租约凭据共同进入 stdio definition，Secret 正文不会出现在默认诊断字符串中。 */
    @Test
    void createsStdioDefinitionWithConfiguredEnvironmentAndResolvedCredential(@TempDir Path workingDirectory) {
        String secret = "credential-value-fixture";
        ConfigurationGenerationSnapshot.McpServer server = stdioServer(
                Map.of("MCP_SETTING", "configured-value"),
                new ConfigurationGenerationSnapshot.Auth(
                        ConfigurationGenerationSnapshot.AuthKind.ENV, "MCP_ACCESS_TOKEN", "credential_fixture"));

        McpServerDefinition definition = GenerationMcpDefinitionFactory.create(
                server, workingDirectory, lease(secret));

        assertEquals("configured-value", definition.environment().get("MCP_SETTING"));
        assertEquals(secret, definition.environment().get("MCP_ACCESS_TOKEN"));
        assertFalse(definition.toString().contains(secret));
        assertFalse(definition.toString().contains("MCP_ACCESS_TOKEN"));
    }

    /** 显式环境与类型化凭据目标发生 Windows 大小写冲突时失败关闭。 */
    @Test
    void rejectsCaseInsensitiveCredentialEnvironmentCollision(@TempDir Path workingDirectory) {
        ConfigurationGenerationSnapshot.McpServer server = stdioServer(
                Map.of("mcp_setting", "manual-value"),
                new ConfigurationGenerationSnapshot.Auth(
                        ConfigurationGenerationSnapshot.AuthKind.ENV, "MCP_SETTING", "credential_fixture"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> GenerationMcpDefinitionFactory.create(server, workingDirectory, lease("secret")));

        assertEquals("mcp_environment_auth_conflict", failure.getMessage());
    }

    /** 构造只包含 stdio 所需字段的不可变 MCP 配置快照。 */
    private static ConfigurationGenerationSnapshot.McpServer stdioServer(
            Map<String, String> environment, ConfigurationGenerationSnapshot.Auth auth) {
        return new ConfigurationGenerationSnapshot.McpServer(
                "stdio-fixture", "Fixture", ConfigurationGenerationSnapshot.Scope.GLOBAL, ConfigurationGenerationSnapshot.Transport.STDIO,
                "node", List.of("fixture.js"), environment, Map.of(), auth, true);
    }

    /** 租约只开放一个期望的凭据，阻止测试通过其它代际查询逃逸。 */
    private static ConfigurationGenerationPort.Lease lease(String expectedSecret) {
        return new ConfigurationGenerationPort.Lease() {
            /** 测试不依赖配置代际身份。 */
            @Override public String generationId() { return "generation_fixture"; }
            /** Definition 构造不得读取整份配置投影。 */
            @Override public ConfigurationGenerationSnapshot snapshot() {
                throw new AssertionError("unexpected snapshot access");
            }
            /** 只解析 fixture 指定的凭据引用。 */
            @Override public String secretFor(String credentialId) {
                assertEquals("credential_fixture", credentialId);
                return expectedSecret;
            }
            /** 无外部租约资源需要释放。 */
            @Override public void close() { }
        };
    }
}
