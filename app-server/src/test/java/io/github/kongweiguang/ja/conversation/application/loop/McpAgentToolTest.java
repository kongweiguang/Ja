// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kongweiguang.ja.conversation.adapter.out.tools.NetworkntToolArgumentValidation;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.JsonValueCodec;
import io.github.kongweiguang.ja.conversation.port.out.McpGateway;
import io.github.kongweiguang.ja.conversation.port.out.ToolArgumentValidator;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonNull;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.foundation.json.JsonValue;
import io.github.kongweiguang.ja.support.TestJsonValueCodec;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/** 使用确定性本地路由夹具验证真实固定 MCP 网关 Adapter。 */
final class McpAgentToolTest {
    private static final String CATALOG_REVISION = "catalog_fixture";
    private static final JsonValueCodec CODEC = new TestJsonValueCodec();
    private static final ToolArgumentValidator VALIDATOR = new NetworkntToolArgumentValidation(CODEC);

    /** 本地状态、重名搜索、限定搜索和描述共用冻结目录，不触发远端 IO。 */
    @Test
    void statusSearchAndDescribeKeepDuplicateTargetsDistinct() throws Exception {
        Fixture fixture = fixture(List.of(
                new Remote("mcp_alpha", "read_alpha_shared", "shared", "First schema"),
                new Remote("mcp_beta", "read_beta_shared", "shared", "Second schema"),
                new Remote("mcp_page", "read_page_00", "page_00", "Paged tool 0"),
                new Remote("mcp_page", "read_page_01", "page_01", "Paged tool 1"),
                new Remote("mcp_page", "read_page_02", "page_02", "Paged tool 2"),
                new Remote("mcp_page", "read_page_03", "page_03", "Paged tool 3"),
                new Remote("mcp_page", "read_page_04", "page_04", "Paged tool 4"),
                new Remote("mcp_page", "read_page_05", "page_05", "Paged tool 5"),
                new Remote("mcp_page", "read_page_06", "page_06", "Paged tool 6"),
                new Remote("mcp_page", "read_page_07", "page_07", "Paged tool 7"),
                new Remote("mcp_page", "read_page_08", "page_08", "Paged tool 8"),
                new Remote("mcp_page", "read_page_09", "page_09", "Paged tool 9"),
                new Remote("mcp_page", "read_page_10", "page_10", "Paged tool 10"),
                new Remote("mcp_page", "read_page_11", "page_11", "Paged tool 11")),
                invocation -> CompletableFuture.completedFuture(result(false, "unused")));

        JsonObject status = resultContent(fixture.tool().execute(invocation("call_status", args("status", null,
                null, null, null, null)), context(), CancellationToken.none()).toCompletableFuture().join());
        JsonArray servers = (JsonArray) status.get("servers");
        assertEquals(0, fixture.gateway().invocations.size());
        JsonObject emptyServer = servers.values().stream().map(JsonObject.class::cast)
                .filter(server -> "mcp_empty".equals(text(server, "serverId"))).findFirst().orElseThrow();
        assertEquals(0, ((io.github.kongweiguang.ja.foundation.json.JsonNumber)
                emptyServer.get("toolCount")).value().intValue());

        JsonObject search = resultContent(fixture.tool().execute(invocation("call_search", args("search", null,
                null, "shared", 0, null)), context(), CancellationToken.none()).toCompletableFuture().join());
        JsonArray matches = (JsonArray) search.get("tools");
        assertEquals(2, matches.values().size());
        assertEquals(List.of("mcp_alpha", "mcp_beta"), matches.values().stream()
                .map(value -> (JsonObject) value).map(value -> text(value, "serverId")).toList());

        JsonObject filteredSearch = resultContent(fixture.tool().execute(invocation("call_filtered_search",
                args("search", "mcp_beta", null, "shared", 0, null)), context(), CancellationToken.none())
                .toCompletableFuture().join());
        assertEquals(List.of("mcp_beta"), ((JsonArray) filteredSearch.get("tools")).values().stream()
                .map(value -> (JsonObject) value).map(value -> text(value, "serverId")).toList());

        JsonObject described = resultContent(fixture.tool().execute(invocation("call_describe", args("describe",
                "mcp_beta", "shared", null, null, null)), context(), CancellationToken.none())
                .toCompletableFuture().join());
        assertEquals("Second schema", text(described, "description"));
        assertEquals(0, fixture.gateway().invocations.size());

        JsonObject firstPage = resultContent(fixture.tool().execute(invocation("call_page_1", args("search", null,
                null, "page_", 0, null)), context(), CancellationToken.none()).toCompletableFuture().join());
        JsonObject secondPage = resultContent(fixture.tool().execute(invocation("call_page_2", args("search", null,
                null, "page_", 10, null)), context(), CancellationToken.none()).toCompletableFuture().join());
        assertEquals(10, ((JsonArray) firstPage.get("tools")).values().size());
        assertEquals(10, ((io.github.kongweiguang.ja.foundation.json.JsonNumber)
                firstPage.get("nextOffset")).value().intValue());
        assertEquals(2, ((JsonArray) secondPage.get("tools")).values().size());
    }

    /** 调用前校验冻结的远端 Schema，并绑定精确服务与工具。 */
    @Test
    void callUsesExactServerAndRemoteSchema() throws Exception {
        Fixture fixture = fixture(List.of(
                new Remote("mcp_alpha", "read_alpha_shared", "shared", "First schema"),
                new Remote("mcp_beta", "read_beta_shared", "shared", "Second schema")),
                invocation -> CompletableFuture.completedFuture(result(false, "read result")));
        AgentTool.Invocation call = invocation("call_exact", args("call", "mcp_beta", "shared", null, null,
                "{\"value\":\"payload\"}"));

        assertTrue(fixture.tool().validationFailure(call).isEmpty());
        AgentTool.ToolBindingDescriptor binding = fixture.tool().bindingDescriptor(call);
        assertEquals("mcp_beta", binding.serverId());
        assertEquals("shared", binding.remoteName());
        assertEquals("a".repeat(64), binding.schemaHash());
        CancellationToken cancellation = CancellationToken.none();
        AgentTool.ToolResult result = fixture.tool().execute(call, context(), cancellation)
                .toCompletableFuture().join();

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertEquals(1, fixture.gateway().invocations.size());
        assertEquals("read_beta_shared", fixture.gateway().invocations.getFirst().localToolName());
        assertEquals("payload", text(fixture.gateway().invocations.getFirst().arguments(), "value"));
        assertSame(cancellation, fixture.gateway().lastCancellationToken.get());
    }

    /** Schema 与 JSON 格式错误在远端调用前返回，且不泄漏输入值。 */
    @Test
    void invalidRemoteArgumentsReturnModelCorrectableResultBeforeInvoke() {
        Fixture fixture = fixture(List.of(new Remote("mcp_alpha", "read_shared", "shared", "Read value")),
                invocation -> CompletableFuture.completedFuture(result(false, "unused")));
        AgentTool.Invocation invalidSchema = invocation("call_bad_schema", args("call", "mcp_alpha", "shared",
                null, null, "{\"extra\":\"private-value\"}"));
        AgentTool.Invocation invalidJson = invocation("call_bad_json", args("call", "mcp_alpha", "shared",
                null, null, "[]"));

        assertEquals("MCP_ARGUMENTS_INVALID", fixture.tool().validationFailure(invalidSchema)
                .orElseThrow().code());
        AgentTool.ToolResult schemaResult = fixture.tool().execute(invalidSchema, context(), CancellationToken.none())
                .toCompletableFuture().join();
        AgentTool.ToolResult jsonResult = fixture.tool().execute(invalidJson, context(), CancellationToken.none())
                .toCompletableFuture().join();

        assertEquals("MCP_ARGUMENTS_INVALID", schemaResult.errorCode());
        assertEquals("MCP_ARGUMENTS_INVALID", jsonResult.errorCode());
        assertFalse(schemaResult.content().contains("private-value"));
        assertEquals(0, fixture.gateway().invocations.size());
    }

    /** 远端错误与结果不确定的超时都保留原始调用身份。 */
    @Test
    void remoteFailureAndTimeoutBecomeSafeToolResults() {
        AtomicReference<McpGateway.McpInvocation> observed = new AtomicReference<>();
        Fixture failed = fixture(List.of(new Remote("mcp_alpha", "read_shared", "shared", "Read value")),
                invocation -> {
                    observed.set(invocation);
                    return CompletableFuture.completedFuture(new McpGateway.McpResult(true, "remote error",
                            Optional.empty(), ToolOutcome.FAILED));
                });
        AgentTool.Invocation call = invocation("call_failed", args("call", "mcp_alpha", "shared",
                null, null, "{\"value\":\"payload\"}"));
        AgentTool.ToolResult error = failed.tool().execute(call, context(), CancellationToken.none())
                .toCompletableFuture().join();
        assertEquals("call_failed", observed.get().callId());
        assertEquals("MCP_TOOL_FAILED", error.errorCode());
        assertEquals("remote error", error.content());

        Fixture timedOut = fixture(List.of(new Remote("mcp_alpha", "read_shared", "shared", "Read value")),
                invocation -> CompletableFuture.failedFuture(new TimeoutException("private endpoint details")));
        AgentTool.ToolResult uncertain = timedOut.tool().execute(call, context(), CancellationToken.none())
                .toCompletableFuture().join();
        assertEquals("TOOL_EXECUTION_UNCONFIRMED", uncertain.errorCode());
        assertFalse(uncertain.content().contains("private endpoint details"));
        assertEquals(1, timedOut.gateway().invocations.size());
    }

    /** 使用受控本地路由图和 Schema 校验器构造真实网关实例。 */
    private static Fixture fixture(List<Remote> remotes,
                                   java.util.function.Function<McpGateway.McpInvocation,
                                           CompletionStage<McpGateway.McpResult>> response) {
        List<McpGateway.McpTool> tools = new ArrayList<>();
        Map<String, McpGateway.RouteIdentity> routes = new java.util.LinkedHashMap<>();
        for (Remote remote : remotes) {
            ToolSpec spec = new ToolSpec(remote.localName(), remote.description(), remoteSchema());
            tools.add(new McpGateway.McpTool(remote.serverId(), remote.remoteName(), spec));
            routes.put(spec.name(), new McpGateway.RouteIdentity(spec.name(), remote.serverId(), remote.remoteName(),
                    "definition_" + remote.serverId(), "a".repeat(64), "b".repeat(64), CATALOG_REVISION));
        }
        McpGateway.McpSnapshot snapshot = new McpGateway.McpSnapshot(CATALOG_REVISION, tools, Instant.EPOCH);
        RecordingGateway gateway = new RecordingGateway(snapshot, response);
        return new Fixture(gateway, (McpAgentTool) McpAgentTool.adapt(gateway, snapshot, routes, CODEC, VALIDATOR)
                .getFirst());
    }

    /** 使用封闭对象 Schema，覆盖生产校验器的额外字段规则。 */
    private static JsonObject remoteSchema() {
        return JsonObjects.builder().putText("type", "object")
                .put("properties", JsonObjects.builder().put("value", JsonObjects.builder()
                        .putText("type", "string").build()).build())
                .put("required", new JsonArray(List.of(new JsonText("value"))))
                .putBoolean("additionalProperties", false).build();
    }

    /** 构造稳定合成结果，不依赖传输 SDK 的内部值。 */
    private static McpGateway.McpResult result(boolean error, String content) {
        return new McpGateway.McpResult(error, content, Optional.empty(),
                error ? ToolOutcome.FAILED : ToolOutcome.SUCCEEDED);
    }

    /** 构造网关全部固定字段，使直接测试符合 Provider 契约形状。 */
    private static JsonObject args(String action, String serverId, String toolName,
                                   String query, Integer offset, String argumentsJson) {
        return JsonObjects.builder().putText("action", action)
                .put("serverId", nullable(serverId)).put("toolName", nullable(toolName))
                .put("query", nullable(query)).put("offset", offset == null ? JsonNull.INSTANCE
                        : new io.github.kongweiguang.ja.foundation.json.JsonNumber(offset))
                .put("argumentsJson", nullable(argumentsJson)).build();
    }

    /** 缺失辅助值写为 JSON null，不遗漏 Schema 要求的必填属性。 */
    private static JsonValue nullable(String value) {
        return value == null ? JsonNull.INSTANCE : new JsonText(value);
    }

    /** 为工具公开执行边界构造稳定 MCP 调用。 */
    private static AgentTool.Invocation invocation(String callId, JsonObject arguments) {
        return new AgentTool.Invocation(callId, "mcp", arguments, 0);
    }

    /** 提供无状态 Adapter 所需的最小合法上下文。 */
    private static AgentTool.ExecutionContext context() {
        return new AgentTool.ExecutionContext("thr_mcp_test", "turn_mcp_test", Path.of("C:/ja-mcp-test"),
                AccessMode.APPROVAL_REQUIRED, "cfg_mcp_test", Instant.now().plusSeconds(30), "ws_mcp_test");
    }

    /** 解码成功的结构化网关结果，供稳定字段断言。 */
    private static JsonObject resultContent(AgentTool.ToolResult result) throws Exception {
        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        return CODEC.decodeObject(result.content());
    }

    /** 读取 JSON 字符串字段，形状不符时由本地夹具断言失败。 */
    private static String text(JsonObject object, String key) {
        assertNotNull(object.get(key));
        return ((JsonText) object.get(key)).value();
    }

    /** 不可变本地路由描述，远端重名时仍保留各自的本地 Schema 名称。 */
    private record Remote(String serverId, String localName, String remoteName, String description) { }

    /** 将真实网关 Adapter 与记录型远端端点配对供单项测试使用。 */
    private record Fixture(RecordingGateway gateway, McpAgentTool tool) { }

    /** 记录精确路由并委派给本地确定性结果，避免测试触碰真实服务。 */
    private static final class RecordingGateway implements McpGateway {
        private final McpSnapshot snapshot;
        private final java.util.function.Function<McpInvocation, CompletionStage<McpResult>> response;
        private final List<McpInvocation> invocations = new ArrayList<>();
        private final AtomicReference<CancellationToken> lastCancellationToken = new AtomicReference<>();

        /** 在测试生命周期内固定目录和结果函数，避免断言受到刷新影响。 */
        private RecordingGateway(McpSnapshot snapshot,
                                 java.util.function.Function<McpInvocation, CompletionStage<McpResult>> response) {
            this.snapshot = snapshot;
            this.response = response;
        }

        /** 返回构建网关时使用的同一个不可变目录快照。 */
        @Override
        public McpSnapshot snapshot() {
            return snapshot;
        }

        /** 先记录选中的路由，再产生可控的本地结果。 */
        @Override
        public CompletionStage<McpResult> invoke(McpSnapshot requested,
                                                  McpInvocation invocation,
                                                  CancellationToken cancellationToken) {
            if (requested != snapshot) throw new AssertionError("gateway snapshot identity changed");
            invocations.add(invocation);
            lastCancellationToken.set(cancellationToken);
            return response.apply(invocation);
        }

        /** 将服务健康度和零工具服务分开表达，防止数量推断状态。 */
        @Override
        public List<McpServerStatus> serverStatuses() {
            return List.of(new McpServerStatus("mcp_alpha", "Alpha", "available", 1, null),
                    new McpServerStatus("mcp_beta", "Beta", "available", 1, null),
                    new McpServerStatus("mcp_empty", "Empty", "available", 0, null));
        }

        /** 夹具不打开网络句柄，关闭操作因此保持无副作用。 */
        @Override
        public void close() {
            // The direct adapter test owns no external resources.
        }
    }
}
