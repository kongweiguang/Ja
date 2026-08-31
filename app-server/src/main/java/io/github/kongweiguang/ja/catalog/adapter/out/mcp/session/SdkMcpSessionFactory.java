// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpDeadline;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpJsonValues;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpLimits;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpServerDefinition;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.transport.JaBoundedHttpTransport;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.transport.JaBoundedStdioTransport;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonValue;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 显式接线 Jackson 2 的 MCP Java SDK 适配器，不依赖 Runtime ServiceLoader 默认实现。
 */
public final class SdkMcpSessionFactory implements McpSessionFactory {
    private final ObjectMapper objectMapper;
    private final McpJsonMapper jsonMapper;
    private final McpLimits limits;

    /**
     * 构建唯一生产 Session Factory，并显式绑定 SDK Mapper 以缩小 Native Image 反射面。
     */
    public SdkMcpSessionFactory(ObjectMapper objectMapper, McpLimits limits) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.jsonMapper = new JacksonMcpJsonMapper(this.objectMapper);
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * 只使用 Java 配置代际解析出的私有定义，并禁用不完整的 SDK Schema 缓存。
     */
    @Override
    public McpSession open(McpServerDefinition definition, McpDeadline deadline) {
        Objects.requireNonNull(deadline, "deadline");
        McpClientTransport transport = definition.transport() == McpServerDefinition.Transport.STDIO
                ? new JaBoundedStdioTransport(
                definition.command(),
                definition.workingDirectory(),
                definition.environment(),
                definition.protocolVersions(),
                jsonMapper,
                limits,
                deadline)
                : new JaBoundedHttpTransport(
                definition.endpoint(), definition.headers(), definition.protocolVersions(), jsonMapper,
                limits, deadline);
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(deadline.remaining(limits.requestTimeout(), "mcp_request_deadline_elapsed"))
                .initializationTimeout(deadline.remaining(
                        limits.startupTimeout(), "mcp_initialize_deadline_elapsed"))
                .clientInfo(McpSchema.Implementation.builder("ja-kernel", "1").build())
                .enableCallToolSchemaCaching(false)
                .build();
        return new SdkSession(client, objectMapper);
    }

    /**
     * 将 SDK 与 Reactor 类型封闭在适配器内，对 Kernel 只暴露有界中性记录。
     */
    private static final class SdkSession implements McpSession {
        private final McpSyncClient client;
        private final ObjectMapper objectMapper;

        /**
         * 每个服务独占一个 SDK Client，使 Gateway 取消能够关闭整个 Session。
         */
        private SdkSession(McpSyncClient client, ObjectMapper objectMapper) {
            this.client = client;
            this.objectMapper = objectMapper;
        }

        /**
         * 每个新 Session 只执行一次强制 MCP 生命周期握手。
         */
        @Override
        public void initialize() {
            client.initialize();
        }

        /**
         * 首页面也使用 Cursor 重载，禁止 SDK 自动分页绕过聚合资源上限。
         */
        @Override
        public ToolPage listTools(String cursor) {
            McpSchema.ListToolsResult page = client.listTools(cursor);
            List<RemoteTool> tools = page.tools().stream()
                    .map(tool -> new RemoteTool(
                            tool.name(),
                            tool.description() == null || tool.description().isBlank()
                                    ? "MCP tool " + tool.name()
                                    : tool.description(),
                            McpJsonValues.objectFromSdk(objectMapper, tool.inputSchema())))
                    .toList();
            return new ToolPage(tools, page.nextCursor());
        }

        /**
         * 将所有 Content 变体转换为 JSON，防止二进制或 Provider 类型绕过结果预算。
         */
        @Override
        public RemoteResult call(String toolName, JsonObject arguments) {
            Map<String, Object> sdkArguments = McpJsonValues.toSdkArguments(objectMapper, arguments);
            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(toolName, sdkArguments, null));
            String content;
            try {
                content = objectMapper.writeValueAsString(result.content());
            } catch (Exception failure) {
                throw new IllegalStateException("mcp_result_serialization_failed", failure);
            }
            Optional<JsonValue> structured = result.structuredContent() == null
                    ? Optional.empty()
                    : Optional.of(McpJsonValues.fromSdk(objectMapper, result.structuredContent()));
            return new RemoteResult(Boolean.TRUE.equals(result.isError()), content, structured);
        }

        /**
         * 使用 SDK 有界优雅关闭，且不向调用方暴露易漂移的布尔诊断值。
         */
        @Override
        public void close() {
            client.closeGracefully();
        }
    }
}
