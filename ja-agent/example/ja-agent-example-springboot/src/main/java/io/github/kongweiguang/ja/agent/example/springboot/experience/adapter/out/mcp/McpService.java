// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.experience.adapter.out.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.example.springboot.bootstrap.AgentProperties;
import io.github.kongweiguang.ja.agent.example.springboot.experience.application.gateway.McpDirectory;
import io.github.kongweiguang.ja.agent.example.springboot.experience.domain.JsonSchemaSnapshot;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValues;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.http.HttpRequest;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 每次调用独占 SDK 会话，取消和关闭不影响其它 Thread；探测按用户打开面板后才发生。
 */
public final class McpService implements McpDirectory {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, AgentProperties.McpServer> servers;
    private final Set<McpSyncClient> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /**
     * 冻结服务定义并拒绝重复名字，HTTP 服务由独立 Python 进程提供。
     */
    public McpService(List<AgentProperties.McpServer> definitions) {
        LinkedHashMap<String, AgentProperties.McpServer> values = new LinkedHashMap<String, AgentProperties.McpServer>();
        for (AgentProperties.McpServer server : definitions) {
            if (values.putIfAbsent(server.id(), server) != null)
                {
                    throw new IllegalArgumentException("duplicate MCP server");
                }
        }
        servers = Map.copyOf(values);
    }

    /**
     * 目录读取有页数、数量和大小上限，失败展示稳定错误而不返回服务端认证信息。
     */
    public List<McpDirectory.ServerView> catalog() {
        List<String> names = servers.keySet().stream().sorted().toList();
        return names.stream().map(name -> {
            try (McpService.Session session = open(name)) {
                return new McpDirectory.ServerView(name, "http", "AVAILABLE", tools(session));
            } catch (RuntimeException failure) {
                return new McpDirectory.ServerView(name, "unknown", "UNAVAILABLE", List.of());
            }
        }).toList();
    }

    /**
     * 实际 MCP 调用必须先确认发现目录，取消关闭本次会话并保留外部副作用未知语义。
     */
    @SuppressWarnings("PMD.UnusedLocalVariable") // registration 在离开调用作用域时自动注销取消回调。
    public String call(String server, String tool, JsonObject arguments, CancellationToken cancellation) {
        cancellation.throwIfCancellationRequested();
        try (McpService.Session session = open(server); CancellationToken.Registration registration = cancellation.onCancellation(session::close)) {
            if (tools(session).stream().noneMatch(value -> value.name().equals(tool)))
                {
                    throw new IllegalArgumentException("MCP tool not found");
                }
            Map<String, Object> values = mapper.convertValue(JacksonJsonValues.toNode(mapper, arguments), new TypeReference<Map<String, Object>>() {
            });
            McpSchema.CallToolResult result = session.client.callTool(new McpSchema.CallToolRequest(tool, values, null));
            cancellation.throwIfCancellationRequested();
            String text = result.content().stream().map(value -> value instanceof McpSchema.TextContent content ? content.text() : "Non-text MCP content").collect(java.util.stream.Collectors.joining("\n"));
            if (text.length() > 65_536) {
                throw new IllegalStateException("MCP result too large");
            }
            if (Boolean.TRUE.equals(result.isError())) {
                throw new IllegalArgumentException(text);
            }
            return text;
        }
    }

    /**
     * 保留 SDK 原生 Schema Map，页数、数量和大小上限仍在发现时检查。
     */
    private List<McpDirectory.ToolView> tools(Session session) {
        ArrayList<McpDirectory.ToolView> result = new ArrayList<McpDirectory.ToolView>();
        String cursor = null;
        for (int page = 0; page < 8; page++) {
            McpSchema.ListToolsResult current = session.client.listTools(cursor);
            for (McpSchema.Tool tool : current.tools()) {
                if (encodedSize(tool.inputSchema()) > 32_768 || result.size() >= 128)
                    {
                        throw new IllegalStateException("MCP catalog limit");
                    }
                result.add(new McpDirectory.ToolView(tool.name(), tool.description() == null ? "" : tool.description(),
                        JsonSchemaSnapshot.copyOf(tool.inputSchema())));
            }
            String next = current.nextCursor();
            if (next == null) {
                return List.copyOf(result);
            }
            if (next.equals(cursor)) {
                throw new IllegalStateException("MCP cursor did not advance");
            }
            cursor = next;
        }
        throw new IllegalStateException("MCP page limit");
    }

    /** 按实际 JSON 字节数约束目录，而不通过 Bean introspection 重建 MCP 的严格 Schema Map。 */
    private int encodedSize(Map<String, Object> value) {
        try {
            return mapper.writeValueAsBytes(value).length;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("MCP schema is not serializable", failure);
        }
    }

    /**
     * 使用标准 HTTP SDK 传输与显式 Jackson 2，服务器地址在启动时从数据库冻结。
     */
    private synchronized Session open(String name) {
        if (closed) {
            throw new IllegalStateException("MCP service closed");
        }
        JacksonMcpJsonMapper json = new JacksonMcpJsonMapper(mapper);
        AgentProperties.McpServer definition = servers.get(name);
        if (definition == null) {
            throw new IllegalArgumentException("unknown MCP server");
        }
        URI uri = URI.create(definition.url());
        HttpRequest.Builder request = HttpRequest.newBuilder();
        definition.headers().forEach(request::header);
        McpClientTransport transport = HttpClientStreamableHttpTransport.builder(uri.getScheme() + "://" + uri.getRawAuthority())
                .endpoint(uri.getRawPath()).requestBuilder(request).jsonMapper(json).maxResponseSize(1024 * 1024)
                .connectTimeout(Duration.ofSeconds(10)).build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).initializationTimeout(Duration.ofSeconds(15))
                .clientInfo(new McpSchema.Implementation("ja-spring-demo", "1")).enableCallToolSchemaCaching(false).build();
        active.add(client);
        try {
            client.initialize();
            return new Session(client);
        } catch (RuntimeException failure) {
            active.remove(client);
            client.closeGracefully();
            throw new IllegalStateException("MCP initialization failed", failure);
        }
    }

    /**
     * Spring 停止时释放所有本次会话，不保留本宿主的 SDK 连接。
     */
    @Override
    public synchronized void close() {
        closed = true;
        active.forEach(McpSyncClient::closeGracefully);
        active.clear();
    }

    /**
     * 会话在正常、错误、取消路径都及时移出生命周期集合。
     */
    private final class Session implements AutoCloseable {
        private final McpSyncClient client;

        /**
         * 每次调用拥有唯一 SDK Client，避免跨 Turn 取消。
         */
        private Session(McpSyncClient client) {
            this.client = client;
        }

        /**
         * SDK 关闭幂等，移出集合后由当前调用完成释放。
         */
        @Override
        public void close() {
            active.remove(client);
            client.closeGracefully();
        }
    }
}
