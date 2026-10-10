// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcCodec;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.transport.rpc.RpcServicesFactory;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;
import io.github.kongweiguang.ja.transport.rpc.runtime.StdioWriter;
import io.github.kongweiguang.ja.transport.rpc.support.TestConfigurationPorts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.kongweiguang.ja.transport.rpc.runtime.RpcRuntimeTestAccess.markReady;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.catalog.domain.model.McpServerDescriptor;
import io.github.kongweiguang.ja.catalog.domain.model.McpToolDescriptor;
import io.github.kongweiguang.ja.catalog.domain.model.SkillDescriptor;
import io.github.kongweiguang.ja.catalog.application.api.CatalogUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ApprovalUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.agent.application.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 验证 Settings catalog 入站边界只消费 catalog 用例，并保持冻结 JA-RPC v2 Wire 结构。 */
final class SettingsCatalogHandlerTest {
    private static final String MCP_ID = "mcp_fixture";

    /**
     * 使用真实会话准入与 Handler 分派验证公开方法，并由 try-with-resources 保证先关闭 Session、再关闭
     * Writer；这样断言失败仍保留原异常，且不会遗漏 CatalogUseCase 与跨域端口隔离边界。
    */
    @Test
    void mapsFrozenCatalogWithoutConfigurationPort() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-settings-catalog-test").toAbsolutePath();
        SidecarConfiguration process = new SidecarConfiguration(
                root.resolve("home"), root.resolve("data"), root.resolve("run"), root.resolve("logs"));

        RecordingCatalog catalog = new RecordingCatalog();
        RpcServicesFactory services = ignoredMapper -> bindings(catalog);
        try (StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), mapper, 16_384);
             RpcSession session = new RpcSession(process, mapper, Clock.fixed(
                     Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC), writer, services,
                     TestConfigurationPorts.unavailable(), 42)) {
            session.initialize();
            markReady(session, "0".repeat(32));
            SettingsCatalogHandler handler = new SettingsCatalogHandler(session);

            assertEquals(Set.of(RpcMethod.SKILL_LIST, RpcMethod.MCP_LIST,
                    RpcMethod.MCP_TEST, RpcMethod.MODEL_TEST, RpcMethod.MODEL_DISCOVER,
                    RpcMethod.MCP_LIST_TOOLS), handler.methods());

            ObjectNode skillPage = invoke(handler, RpcMethod.SKILL_LIST,
                    mapper.createObjectNode().put("workspaceId", "ws_fixture").put("limit", 1));
            assertEquals(Set.of("items", "nextCursor"), fields(skillPage));
            assertEquals(Set.of("skillId", "name", "scope", "enabled", "status", "description"),
                    fields((ObjectNode) skillPage.path("items").path(0)));
            assertEquals("skill_fixture", skillPage.path("items").path(0).path("skillId").asText());
            assertEquals("skill_next", skillPage.path("nextCursor").asText());
            assertEquals("ws_fixture", catalog.skillWorkspaceId);

            ObjectNode mcpPage = invoke(handler, RpcMethod.MCP_LIST,
                    mapper.createObjectNode().put("limit", 1));
            assertEquals(Set.of("items", "nextCursor"), fields(mcpPage));
            assertEquals(MCP_ID, mcpPage.path("items").path(0).path("mcpId").asText());

            ObjectNode tested = invoke(handler, RpcMethod.MCP_TEST,
                    mapper.createObjectNode().put("mcpId", MCP_ID));
            assertEquals(Set.of("mcpId", "name", "scope", "transport", "status", "toolCount"),
                    fields(tested));
            assertEquals("available", tested.path("status").asText());

            ObjectNode modelTest = invoke(handler, RpcMethod.MODEL_TEST,
                    mapper.createObjectNode().put("providerId", "provider_fixture")
                            .put("modelId", "model_fixture"));
            assertEquals(Set.of("responseModel", "latencyMs"), fields(modelTest));
            assertEquals("fixture-model", modelTest.path("responseModel").asText());

            ObjectNode discovered = invoke(handler, RpcMethod.MODEL_DISCOVER,
                    mapper.createObjectNode().put("providerId", "provider_fixture"));
            assertEquals(Set.of("items", "truncated"), fields(discovered));
            assertEquals("fixture-model", discovered.path("items").path(0).asText());
            assertEquals(false, discovered.path("truncated").asBoolean());

            ObjectNode toolPage = invoke(handler, RpcMethod.MCP_LIST_TOOLS,
                    mapper.createObjectNode().put("mcpId", MCP_ID).put("limit", 1));
            assertEquals(Set.of("items", "nextCursor"), fields(toolPage));
            assertEquals("object", toolPage.path("items").path(0).path("inputSchema")
                    .path("type").asText());

            /* 额外字段必须在获取配置租约前失败，避免非法请求触发配置或 MCP 资源副作用。 */
            assertThrows(JaRpcException.class, () -> invoke(handler, RpcMethod.SKILL_LIST,
                    mapper.createObjectNode().put("limit", 1).put("unexpected", true)));
            assertThrows(JaRpcException.class, () -> invoke(handler, RpcMethod.SKILL_LIST,
                    mapper.createObjectNode().put("workspaceId", "../workspace")));
            assertThrows(JaRpcException.class, () -> invoke(handler, RpcMethod.MCP_LIST,
                    mapper.createObjectNode().put("limit", 1).put("unexpected", true)));
            assertThrows(JaRpcException.class, () -> invoke(handler, RpcMethod.MCP_LIST_TOOLS,
                    mapper.createObjectNode().put("mcpId", MCP_ID).put("limit", 1)
                            .put("unexpected", true)));
            assertThrows(JaRpcException.class, () -> invoke(handler, RpcMethod.MODEL_TEST,
                    mapper.createObjectNode().put("providerId", "provider_fixture")
                            .put("modelId", "../secret")));
            assertThrows(JaRpcException.class, () -> invoke(handler, RpcMethod.MODEL_DISCOVER,
                    mapper.createObjectNode().put("providerId", "../secret")));
            assertEquals(6, catalog.calls.get());
        }
    }

    /** MCP tool schema 只能整项分页；缩小后沿服务端返回游标继续且最终写成合法单帧。 */
    @Test
    void shrinksMcpToolPageToFitStdioFrame() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-settings-frame-test").toAbsolutePath();
        SidecarConfiguration process = new SidecarConfiguration(
                root.resolve("home"), root.resolve("data"), root.resolve("run"), root.resolve("logs"));
        String description = "汉".repeat(400_000);
        List<McpToolDescriptor> tools = List.of(
                new McpToolDescriptor("tool_one", "One", schema(description)),
                new McpToolDescriptor("tool_two", "Two", schema(description)),
                new McpToolDescriptor("tool_three", "Three", schema(description)),
                new McpToolDescriptor("tool_four", "Four", schema(description)));
        ToolPageCatalog catalog = new ToolPageCatalog(tools);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RpcServicesFactory services = ignoredMapper -> bindings(catalog);
        try (StdioWriter writer = new StdioWriter(output, mapper, JaRpcCodec.DEFAULT_MAX_FRAME_BYTES);
             RpcSession session = new RpcSession(process, mapper, Clock.fixed(
                     Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC), writer, services,
                     TestConfigurationPorts.unavailable(), 42)) {
            session.initialize();
            markReady(session, "0".repeat(32));
            ObjectNode response = invoke(new SettingsCatalogHandler(session), RpcMethod.MCP_LIST_TOOLS,
                    mapper.createObjectNode().put("mcpId", MCP_ID).put("limit", 4));

            assertEquals(List.of(4, 2), catalog.requestedLimits);
            assertEquals(2, response.path("items").size());
            assertEquals("2", response.path("nextCursor").asText());
            writer.response("c:catalog_page", response).join();
            assertTrue(output.size() <= JaRpcCodec.DEFAULT_MAX_FRAME_BYTES);
        }
    }

    /** 单条 Schema 无法装入响应预算时必须返回稳定容量错误，不能截断其 JSON 结构。 */
    @Test
    void rejectsMcpToolItemThatExceedsStdioFrame() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-settings-item-frame-test").toAbsolutePath();
        SidecarConfiguration process = new SidecarConfiguration(
                root.resolve("home"), root.resolve("data"), root.resolve("run"), root.resolve("logs"));
        ToolPageCatalog catalog = new ToolPageCatalog(List.of(new McpToolDescriptor(
                "tool_huge", "Huge", schema("汉".repeat(1_500_000)))));
        RpcServicesFactory services = ignoredMapper -> bindings(catalog);
        try (StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), mapper,
                    JaRpcCodec.DEFAULT_MAX_FRAME_BYTES);
             RpcSession session = new RpcSession(process, mapper, Clock.fixed(
                     Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC), writer, services,
                     TestConfigurationPorts.unavailable(), 42)) {
            session.initialize();
            markReady(session, "0".repeat(32));
            JaRpcException failure = assertThrows(JaRpcException.class,
                    () -> invoke(new SettingsCatalogHandler(session), RpcMethod.MCP_LIST_TOOLS,
                            mapper.createObjectNode().put("mcpId", MCP_ID).put("limit", 1)));
            assertEquals("FRAME_TOO_LARGE", failure.errorCode());
            assertEquals(List.of(1), catalog.requestedLimits);
        }
    }

    /** 同步取得 Handler 结果，使测试失败位置保留在公开命令边界。 */
    private static ObjectNode invoke(SettingsCatalogHandler handler, RpcMethod method, ObjectNode params) {
        return handler.handle(new RpcCommand(method, params)).toCompletableFuture().join();
    }

    /** 保持 Schema 为单个合法 JSON object，巨大内容只占 description 叶节点。 */
    private static String schema(String description) {
        return "{\"type\":\"object\",\"description\":\"" + description + "\"}";
    }

    /** 收集严格对象字段，避免只断言部分内容而放过旧字段或敏感扩展。 */
    private static Set<String> fields(ObjectNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    /** Catalog 是唯一业务端口；仅允许 session teardown 调用 DeadlineCloseable.closeAt。 */
    private static RpcServiceBindings bindings(CatalogUseCase catalog) {
        return new RpcServiceBindings(
                unsupported(WorkspaceUseCase.class),
                unsupported(io.github.kongweiguang.ja.workspace.application.api.WorkspacePathSearchUseCase.class),
                unsupported(ThreadUseCase.class),
                unsupported(io.github.kongweiguang.ja.catalog.application.api.ThreadMcpUseCase.class),
                unsupported(TurnUseCase.class),
                (command, events, cancellation) -> { throw new UnsupportedOperationException("context compaction is unavailable"); },
                unsupported(ApprovalUseCase.class), catalog,
                unsupported(io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase.class),
                unsupported(io.github.kongweiguang.ja.attachment.application.api.AttachmentPreviewUseCase.class),
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.passiveTasks(),
                    io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.passivePlans(),
                    io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.passiveInteractions(),
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedProcesses(),
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedReviews(),
                lifecycleOnlyClose());
    }

    /** 用拒绝式动态代理隔离非目标端口，意外跨域调用会直接暴露为测试失败。 */
    private static <T> T unsupported(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(SettingsCatalogHandlerTest.class.getClassLoader(),
                new Class<?>[]{type}, (proxy, method, arguments) -> {
                    throw new AssertionError("unexpected port call: " + method.getName());
                }));
    }

    /** 允许 session 释放其关闭期限资源；任何业务访问仍立即失败，避免 fixture 掩盖依赖。 */
    private static DeadlineCloseable lifecycleOnlyClose() {
        return (DeadlineCloseable) Proxy.newProxyInstance(SettingsCatalogHandlerTest.class.getClassLoader(),
                new Class<?>[]{DeadlineCloseable.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("closeAt")) {
                        return null;
                    }
                    throw new AssertionError("unexpected lifecycle call: " + method.getName());
                });
    }

    /** 记录 Handler 是否只调用 CatalogUseCase，而不触达 configuration 端口。 */
    private static class RecordingCatalog implements CatalogUseCase {
        private final AtomicInteger calls = new AtomicInteger();
        private String skillWorkspaceId;

        /** 返回一页脱敏 Skill 描述，并记录唯一应用入口调用。 */
        @Override
        public CursorPage<SkillDescriptor> listSkills(String workspaceId, String cursor, int limit) {
            admit();
            skillWorkspaceId = workspaceId;
            assertEquals(1, limit);
            return new CursorPage<>(List.of(new SkillDescriptor(
                    "skill_fixture", "Fixture", "builtin", true, "healthy", "安全描述")),
                    "skill_next");
        }

        /** 返回一页脱敏 MCP 描述，并记录唯一应用入口调用。 */
        @Override
        public CursorPage<McpServerDescriptor> listMcp(String workspaceId, String cursor, int limit) {
            admit();
            assertEquals(1, limit);
            return new CursorPage<>(List.of(new McpServerDescriptor(
                    MCP_ID, "Fixture", "global", "streamable_http", "configured", 1)), null);
        }

        /** 返回已完成的有界探测，Handler 只映射结果而不管理下游租约。 */
        @Override
        public CompletionStage<McpServerDescriptor> testMcp(String workspaceId, String mcpId) {
            admit();
            assertEquals(MCP_ID, mcpId);
            return CompletableFuture.completedFuture(new McpServerDescriptor(
                    MCP_ID, "Fixture", "global", "streamable_http", "available", 1));
        }

        /** 返回不含回答的模型验证摘要，并确认请求只携带保存身份与连接取消令牌。 */
        @Override
        public CompletionStage<ModelTestResult> testModel(
                String providerId, String modelId, CancellationToken cancellationToken) {
            admit();
            assertEquals("provider_fixture", providerId);
            assertEquals("model_fixture", modelId);
            return CompletableFuture.completedFuture(new ModelTestResult("fixture-model", 17));
        }

        /** 返回单页上游目录投影，确认 Handler 只透传 Provider 身份及连接取消令牌。 */
        @Override
        public CompletionStage<ModelDiscoveryResult> discoverModels(
                String providerId, CancellationToken cancellationToken) {
            admit();
            assertEquals("provider_fixture", providerId);
            return CompletableFuture.completedFuture(new ModelDiscoveryResult(List.of("fixture-model"), false));
        }

        /** 返回规范 JSON Schema 文本，Jackson 解析只允许发生在 Wire 边界。 */
        @Override
        public CursorPage<McpToolDescriptor> readMcpTools(
                String workspaceId, String mcpId, String cursor, int limit) {
            admit();
            assertEquals(MCP_ID, mcpId);
            assertEquals(1, limit);
            return new CursorPage<>(List.of(new McpToolDescriptor(
                    "mcp_fixture__tool", "安全 Tool", "{\"type\":\"object\"}")), null);
        }

        /** 记录一次合法 catalog 调用，意外的 configuration 调用由拒绝代理直接失败。 */
        private void admit() {
            calls.incrementAndGet();
        }
    }

    /** 按请求 limit 与游标返回 Tool 子页，并记录 handler 为满足 frame budget 重读的边界。 */
    private static final class ToolPageCatalog extends RecordingCatalog {
        private final List<McpToolDescriptor> tools;
        private final List<Integer> requestedLimits = new ArrayList<>();

        /** 固定可重复读取的 Tool 目录页，避免用例依赖真实 MCP 服务或网络。 */
        private ToolPageCatalog(List<McpToolDescriptor> tools) {
            this.tools = List.copyOf(tools);
        }

        /** 返回 cursor 后的同一目录切片，游标只由实际返回的末项推进。 */
        @Override
        public CursorPage<McpToolDescriptor> readMcpTools(
                String workspaceId, String mcpId, String cursor, int limit) {
            assertEquals(MCP_ID, mcpId);
            requestedLimits.add(limit);
            int start = cursor == null ? 0 : Integer.parseInt(cursor);
            int end = Math.min(start + limit, tools.size());
            String nextCursor = end < tools.size() ? Integer.toString(end) : null;
            return new CursorPage<>(tools.subList(start, end), nextCursor);
        }
    }
}
