// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** MCP 配置边界必须把无效地址归一为可诊断的输入错误。 */
class AgentPropertiesTest {
    /** 相对地址和凭据 URL 都必须按同一配置错误拒绝，避免 NPE 或错误泄漏到后续连接。 */
    @Test
    void rejectsRelativeAndCredentialMcpUrls() {
        for (String url : List.of("/mcp", "relative/mcp", "https://user:secret@example.com/mcp")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new AgentProperties.McpServer("demo", url, Map.of()));

            assertEquals("invalid MCP URL", failure.getMessage());
        }
    }

    /** MCP 服务允许标准 HTTP 与 HTTPS 源站，传输实现仍统一为 Streamable HTTP。 */
    @Test
    void acceptsHttpAndHttpsMcpUrls() {
        for (String url : List.of("http://127.0.0.1:8091/mcp", "https://example.com/mcp")) {
            assertDoesNotThrow(() -> new AgentProperties.McpServer("demo", url, Map.of()));
        }
    }
}
