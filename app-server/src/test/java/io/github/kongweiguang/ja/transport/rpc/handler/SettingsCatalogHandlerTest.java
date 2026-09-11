// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.transport.rpc.RpcServicesFactory;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;
import io.github.kongweiguang.ja.transport.rpc.runtime.StdioWriter;
import io.github.kongweiguang.ja.transport.rpc.support.TestConfigurationPorts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static io.github.kongweiguang.ja.transport.rpc.runtime.RpcRuntimeTestAccess.markReady;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.catalog.domain.McpServerDescriptor;
import io.github.kongweiguang.ja.catalog.domain.McpToolDescriptor;
import io.github.kongweiguang.ja.catalog.domain.SkillDescriptor;
import io.github.kongweiguang.ja.catalog.port.in.CatalogUseCase;
import io.github.kongweiguang.ja.conversation.port.in.ApprovalUseCase;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 验证 Settings catalog 入站边界只消费 catalog 用例，并保持冻结 JA-RPC v1 Wire 结构。 */
final class SettingsCatalogHandlerTest {
    private static final String MCP_ID = "mcp_fixture";

    /**
     * 使用真实会话准入与 Handler 分派验证五个公开方法，避免直接调用私有映射方法而遗漏
     * ready、CatalogUseCase 以及跨域端口隔离边界。
    */
    @Test
    void mapsFrozenCatalogWithoutConfigurationPort() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-settings-catalog-test").toAbsolutePath();
        SidecarConfiguration process = new SidecarConfiguration(
                root.resolve("home"), root.resolve("data"), root.resolve("run"), root.resolve("logs"));

        try (StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), mapper, 16_384)) {
            RecordingCatalog catalog = new RecordingCatalog();
            RpcServicesFactory services = ignoredMapper ->
                    bindings(catalog);
            RpcSession session = new RpcSession(process, mapper, Clock.fixed(
                    Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC), writer, services,
                    TestConfigurationPorts.unavailable(), 42);
            session.initialize();
            markReady(session, "0".repeat(32));
            SettingsCatalogHandler handler = new SettingsCatalogHandler(session);

            assertEquals(Set.of(RpcMethod.SKILL_LIST, RpcMethod.MCP_LIST,
                    RpcMethod.MCP_TEST, RpcMethod.MODEL_TEST, RpcMethod.MCP_LIST_TOOLS), handler.methods());

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
            assertEquals(Set.of("mcpId", "name", "transport", "status", "toolCount"),
                    fields(tested));
            assertEquals("available", tested.path("status").asText());

            ObjectNode modelTest = invoke(handler, RpcMethod.MODEL_TEST,
                    mapper.createObjectNode().put("providerId", "provider_fixture")
                            .put("modelId", "model_fixture"));
            assertEquals(Set.of("responseModel", "latencyMs"), fields(modelTest));
            assertEquals("fixture-model", modelTest.path("responseModel").asText());

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
            assertEquals(5, catalog.calls.get());
        }
    }

    /** 同步取得 Handler 结果，使测试失败位置保留在公开命令边界。 */
    private static ObjectNode invoke(SettingsCatalogHandler handler, RpcMethod method, ObjectNode params) {
        return handler.handle(new RpcCommand(method, params)).toCompletableFuture().join();
    }

    /** 收集严格对象字段，避免只断言部分内容而放过旧字段或敏感扩展。 */
    private static Set<String> fields(ObjectNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    /** 组合唯一被允许调用的 Catalog 端口，其余业务端口保持显式拒绝。 */
    private static RpcServiceBindings bindings(CatalogUseCase catalog) {
        return new RpcServiceBindings(
                unsupported(WorkspaceUseCase.class),
                unsupported(io.github.kongweiguang.ja.workspace.port.in.WorkspacePathSearchUseCase.class),
                unsupported(ThreadUseCase.class),
                unsupported(TurnUseCase.class),
                (command, events, cancellation) -> { throw new UnsupportedOperationException("context compaction is unavailable"); },
                unsupported(ApprovalUseCase.class), catalog,
                unsupported(io.github.kongweiguang.ja.attachment.port.in.AttachmentUseCase.class),
                unsupported(io.github.kongweiguang.ja.attachment.port.in.AttachmentPreviewUseCase.class),
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.passiveTasks(),
                    io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.passiveGoals(),
                    io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.passiveInteractions(),
                unsupported(DeadlineCloseable.class));
    }

    /** 用拒绝式动态代理隔离非目标端口，意外跨域调用会直接暴露为测试失败。 */
    private static <T> T unsupported(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(SettingsCatalogHandlerTest.class.getClassLoader(),
                new Class<?>[]{type}, (proxy, method, arguments) -> {
                    throw new AssertionError("unexpected port call: " + method.getName());
                }));
    }

    /** 记录 Handler 是否只调用 CatalogUseCase，而不触达 configuration 端口。 */
    private static final class RecordingCatalog implements CatalogUseCase {
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
        public CursorPage<McpServerDescriptor> listMcp(String cursor, int limit) {
            admit();
            assertEquals(1, limit);
            return new CursorPage<>(List.of(new McpServerDescriptor(
                    MCP_ID, "Fixture", "streamable_http", "configured", 1)), null);
        }

        /** 返回已完成的有界探测，Handler 只映射结果而不管理下游租约。 */
        @Override
        public CompletionStage<McpServerDescriptor> testMcp(String mcpId) {
            admit();
            assertEquals(MCP_ID, mcpId);
            return CompletableFuture.completedFuture(new McpServerDescriptor(
                    MCP_ID, "Fixture", "streamable_http", "available", 1));
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

        /** 返回规范 JSON Schema 文本，Jackson 解析只允许发生在 Wire 边界。 */
        @Override
        public CursorPage<McpToolDescriptor> readMcpTools(
                String mcpId, String cursor, int limit) {
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
}
