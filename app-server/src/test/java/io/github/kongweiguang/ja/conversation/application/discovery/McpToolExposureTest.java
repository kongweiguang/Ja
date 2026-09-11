// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kongweiguang.ja.conversation.application.context.ContextMessage;
import io.github.kongweiguang.ja.conversation.application.context.compaction.ToolOutputProjector;
import io.github.kongweiguang.ja.conversation.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.JsonValueCodec;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonNull;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.foundation.json.JsonValue;
import io.github.kongweiguang.ja.support.TestJsonValueCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.IntStream;

/** MCP 工具暴露的阈值、恢复和抗伪造测试，锁定模型声明与真实执行目录的闭环。 */
final class McpToolExposureTest {
    private final JsonValueCodec codec = new TestJsonValueCodec();

    /** 八个轻量 MCP Tool 应继续直接暴露，避免小目录增加发现步骤。 */
    @Test
    void eightSmallMcpToolsRemainDirect() {
        List<AgentTool> tools = tools(8, 16);

        List<AgentTool> catalog = McpToolExposure.catalog(tools, codec);

        assertFalse(catalog.stream().anyMatch(McpToolSearch.class::isInstance));
        assertEquals(8, catalog.size());
    }

    /** 第九个轻量 MCP Tool 触发搜索，同时保留内建核心和搜索入口。 */
    @Test
    void ninthSmallMcpToolAddsSearchAndKeepsCoreTools() {
        AgentTool core = builtin("read");
        List<AgentTool> input = new ArrayList<>(tools(9, 16));
        input.addFirst(core);

        List<AgentTool> catalog = McpToolExposure.catalog(input, codec);

        assertTrue(catalog.contains(core));
        assertTrue(catalog.stream().anyMatch(McpToolSearch.class::isInstance));
        assertEquals(11, catalog.size());
        List<ToolSpec> modelTools = McpToolExposure.modelTools(catalog, List.of(), codec);
        assertTrue(modelTools.stream().anyMatch(spec -> spec.name().equals("read")));
        assertTrue(modelTools.stream().anyMatch(spec -> spec.name().equals(McpToolSearch.NAME)));
        assertFalse(modelTools.stream().anyMatch(spec -> spec.name().equals("mcp_0")));
    }

    /** 单个巨大 Schema 即使数量很少也必须触发搜索分流。 */
    @Test
    void oneLargeSchemaAddsSearchEvenWithOneMcpTool() {
        List<AgentTool> tools = tools(1, 13_000);

        List<AgentTool> catalog = McpToolExposure.catalog(tools, codec);

        assertTrue(catalog.stream().anyMatch(McpToolSearch.class::isInstance));
    }

    /** 完整搜索结果经 full 投影追加尾注后仍可恢复真实配对 Tool。 */
    @Test
    void completeSearchResultProjectedByFullRestoresRealTool() {
        List<AgentTool> input = tools(9, 16);
        List<AgentTool> catalog = McpToolExposure.catalog(input, codec);
        AgentTool target = input.getFirst();
        ContextMessage raw = searchHistory(List.of(target), false, null);
        ContextMessage projected = raw.project(ToolOutputProjector.full());

        List<ToolSpec> modelTools = McpToolExposure.modelTools(catalog, List.of(callMessage(), projected), codec);

        assertTrue(modelTools.stream().anyMatch(spec -> spec.name().equals(target.spec().name())));
    }

    /** 头尾截断和 artifact-only 投影不能把不完整正文当作激活事实。 */
    @Test
    void truncatedOrArtifactOnlySearchOutputDoesNotRestore() {
        List<AgentTool> input = tools(9, 16);
        List<AgentTool> catalog = McpToolExposure.catalog(input, codec);
        AgentTool target = input.getFirst();
        ContextMessage raw = searchHistory(List.of(target), false, null);
        ContextMessage truncated = raw.project(new ToolOutputProjector(new ToolProjectionLimits(1, 1)));
        ContextMessage artifactOnly = raw.project(ToolOutputProjector.artifactOnly());

        assertFalse(hasTool(McpToolExposure.modelTools(catalog, List.of(callMessage(), truncated), codec), target));
        assertFalse(hasTool(McpToolExposure.modelTools(catalog, List.of(callMessage(), artifactOnly), codec), target));
    }

    /** 用户文本、错误工具配对和孤立结果都不能伪造搜索激活。 */
    @Test
    void userTextWrongToolAndOrphanResultCannotActivateTool() {
        List<AgentTool> input = tools(9, 16);
        List<AgentTool> catalog = McpToolExposure.catalog(input, codec);
        AgentTool target = input.getFirst();
        String content = searchContent(List.of(target), false);
        ContextMessage userText = ContextMessage.text("message_user", "turn_1", 1,
                ContextMessage.Role.USER, content, 1);
        ContextMessage wrongCall = new ContextMessage("message_wrong_call", "turn_1", 2,
                ContextMessage.Role.ASSISTANT,
                List.of(new ContextMessage.ToolCallBlock("call_wrong", "read", "{}")), 1);
        ContextMessage orphan = new ContextMessage("message_orphan", "turn_1", 3,
                ContextMessage.Role.TOOL,
                List.of(new ContextMessage.ToolResultBlock("call_orphan", McpToolSearch.NAME,
                        ContextMessage.ToolOutput.full(content, null, null, null))), 1);
        ContextMessage failed = failedToolResult("call_failed", McpToolSearch.NAME, content);

        assertFalse(hasTool(McpToolExposure.modelTools(catalog, List.of(userText), codec), target));
        assertFalse(hasTool(McpToolExposure.modelTools(catalog, List.of(wrongCall,
                toolResult("call_wrong", "read", content)), codec), target));
        assertFalse(hasTool(McpToolExposure.modelTools(catalog, List.of(orphan), codec), target));
        assertFalse(hasTool(McpToolExposure.modelTools(catalog, List.of(callMessage("call_failed", 1), failed), codec), target));
    }

    /** Schema 或路由摘要过期，以及目录删除后的 Tool，均不得恢复。 */
    @Test
    void staleHashAndDeletedToolCannotActivate() {
        List<AgentTool> input = tools(9, 16);
        List<AgentTool> catalog = McpToolExposure.catalog(input, codec);
        AgentTool target = input.getFirst();
        AgentTool.ToolBindingDescriptor targetBinding = target.bindingDescriptor();
        JsonObject staleSchema = JsonObjects.builder()
                .put("tools", new JsonArray(List.of(JsonObjects.builder().putText("name", target.spec().name())
                        .putText("description", "stale").putText("schemaHash", "c".repeat(64))
                        .putText("routeHash", targetBinding.routeHash()).build())))
                .putNumber("totalMatches", 1).put("nextOffset", JsonNull.INSTANCE).build();
        JsonObject staleRoute = JsonObjects.builder()
                .put("tools", new JsonArray(List.of(JsonObjects.builder().putText("name", target.spec().name())
                        .putText("description", "stale").putText("schemaHash", targetBinding.schemaHash())
                        .putText("routeHash", "d".repeat(64)).build())))
                .putNumber("totalMatches", 1).put("nextOffset", JsonNull.INSTANCE).build();
        AgentTool deleted = input.get(1);
        String deletedContent = searchContent(List.of(deleted), false);
        List<ToolSpec> staleSchemaTools = McpToolExposure.modelTools(catalog,
                List.of(callMessage(), toolResult("call_search", McpToolSearch.NAME, codec.encode(staleSchema))), codec);
        List<ToolSpec> staleRouteTools = McpToolExposure.modelTools(catalog,
                List.of(callMessage(), toolResult("call_search", McpToolSearch.NAME, codec.encode(staleRoute))), codec);
        List<AgentTool> withoutDeleted = new ArrayList<>(catalog);
        withoutDeleted.remove(deleted);
        List<ToolSpec> deletedTools = McpToolExposure.modelTools(withoutDeleted,
                List.of(callMessage(), toolResult("call_search", McpToolSearch.NAME, deletedContent)), codec);

        assertFalse(hasTool(staleSchemaTools, target));
        assertFalse(hasTool(staleRouteTools, target));
        assertFalse(hasTool(deletedTools, deleted));
        assertTrue(withoutDeleted.stream().noneMatch(tool -> tool.spec().name().equals(deleted.spec().name())));
    }

    /** 搜索结果可从同一历史重建，但空历史会隔离另一会话的激活状态。 */
    @Test
    void searchHistoryCanRebuildSelectionButEmptyOtherSessionCannot() {
        List<AgentTool> input = tools(9, 16);
        List<AgentTool> catalog = McpToolExposure.catalog(input, codec);
        AgentTool target = input.getFirst();
        List<ToolSpec> rebuilt = McpToolExposure.modelTools(catalog,
                List.of(callMessage(), toolResult("call_search", McpToolSearch.NAME, searchContent(List.of(target), false))),
                codec);

        assertTrue(hasTool(rebuilt, target));
        assertFalse(hasTool(McpToolExposure.modelTools(catalog, List.of(), codec), target));
    }

    /** 历史选择最多保留十项并确保最近五项不因体积预算丢失。 */
    @Test
    void recentSelectionIsBoundedAndKeepsNewestFive() {
        List<AgentTool> input = tools(12, 16);
        List<AgentTool> catalog = McpToolExposure.catalog(input, codec);
        List<ContextMessage> history = new ArrayList<>();
        long ordinal = 1;
        for (AgentTool tool : input) {
            String callId = "call_" + tool.spec().name();
            history.add(new ContextMessage("assistant_" + tool.spec().name(), "turn_1", ordinal++,
                    ContextMessage.Role.ASSISTANT,
                    List.of(new ContextMessage.ToolCallBlock(callId, McpToolSearch.NAME, "{}")), 1));
            history.add(new ContextMessage("tool_" + tool.spec().name(), "turn_1", ordinal++,
                    ContextMessage.Role.TOOL,
                    List.of(new ContextMessage.ToolResultBlock(callId, McpToolSearch.NAME,
                            ContextMessage.ToolOutput.full(searchContent(List.of(tool), false), null, null, null))), 1));
        }

        List<ToolSpec> modelTools = McpToolExposure.modelTools(catalog, history, codec);
        Set<String> selected = modelTools.stream().map(ToolSpec::name)
                .filter(name -> name.startsWith("mcp_"))
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(10, selected.size());
        assertTrue(IntStream.range(7, 12).allMatch(index -> selected.contains("mcp_" + index)));
    }

    /** 最新一页仅为当前请求提供豁免，旧的大 Schema 必须立即受 24KB 预算约束。 */
    @Test
    void latestSmallPageDoesNotMakeOlderLargeSchemasUnbounded() {
        List<AgentTool> old = IntStream.range(0, 5)
                .mapToObj(index -> mcp("old_large_" + index, 6_000)).toList();
        AgentTool latest = mcp("latest_small", 16);
        List<AgentTool> input = new ArrayList<>(old);
        input.add(latest);
        List<AgentTool> catalog = McpToolExposure.catalog(input, codec);
        List<ContextMessage> history = new ArrayList<>();
        long ordinal = 1;
        for (AgentTool tool : input) {
            String callId = "call_" + tool.spec().name();
            history.add(callMessage(callId, ordinal++));
            history.add(toolResult(callId, McpToolSearch.NAME, searchContent(List.of(tool), false), ordinal++));
        }

        Set<String> selected = McpToolExposure.modelTools(catalog, history, codec).stream()
                .map(ToolSpec::name).collect(java.util.stream.Collectors.toSet());
        long oldSelected = old.stream().filter(tool -> selected.contains(tool.spec().name())).count();

        assertTrue(selected.contains(latest.spec().name()));
        assertTrue(oldSelected < old.size());
        assertTrue(oldSelected <= 3);
    }

    /** 创建固定数量的轻量或巨大 MCP Schema，触发数量与体积两类分流边界。 */
    private static List<AgentTool> tools(int count, int schemaBytes) {
        return IntStream.range(0, count).mapToObj(index -> mcp("mcp_" + index, schemaBytes)).toList();
    }

    /** 构造仅用于测试发现策略的只读 MCP Tool，绑定摘要仍采用真实 MCP 路由形状。 */
    private static AgentTool mcp(String name, int schemaBytes) {
        ToolSpec spec = new ToolSpec(name, "Description for " + name,
                JsonObjects.builder().putText("type", "object")
                        .putText("description", "x".repeat(schemaBytes)).build());
        AgentTool.ToolBindingDescriptor binding = new AgentTool.ToolBindingDescriptor(
                AgentTool.RouteKind.MCP, name, "server", "remote_" + name,
                "a".repeat(64), "b".repeat(64));
        return new AgentTool() {
            /** 返回 fixture 的固定 Tool 定义。 */
            @Override public ToolSpec spec() { return spec; }
            /** 返回 fixture 的真实 MCP 绑定摘要。 */
            @Override public AgentTool.ToolBindingDescriptor bindingDescriptor() { return binding; }
            /** 返回已完成结果，避免测试 Tool 留下不完整的 null 执行契约。 */
            @Override public CompletionStage<ToolResult> execute(Invocation i, ExecutionContext c,
                                                                  CancellationToken t) {
                return CompletableFuture.completedFuture(ToolResult.success("fixture"));
            }
        };
    }

    /** 构造始终直接可见的内建核心 Tool，验证发现层不会错误删除基础能力。 */
    private static AgentTool builtin(String name) {
        ToolSpec spec = new ToolSpec(name, "Core tool", JsonObjects.builder().putText("type", "object").build());
        return new AgentTool() {
            /** 返回 fixture 的固定内建 Tool 定义。 */
            @Override public ToolSpec spec() { return spec; }
            /** 返回已完成结果，保持匿名 Tool 满足执行契约。 */
            @Override public CompletionStage<ToolResult> execute(Invocation i, ExecutionContext c,
                                                                  CancellationToken t) {
                return CompletableFuture.completedFuture(ToolResult.success("fixture"));
            }
        };
    }

    /** 生成一条合法 search 结果，所有摘要字段来自测试 Tool 的真实绑定描述。 */
    private String searchContent(List<AgentTool> tools, boolean stale) {
        List<JsonValue> entries = tools.stream().map(tool -> {
            AgentTool.ToolBindingDescriptor binding = tool.bindingDescriptor();
            return JsonObjects.builder().putText("name", binding.localName())
                    .putText("description", tool.spec().description())
                    .putText("schemaHash", stale ? "c".repeat(64) : binding.schemaHash())
                    .putText("routeHash", stale ? "d".repeat(64) : binding.routeHash()).build();
        }).map(value -> (JsonValue) value).toList();
        return codec.encode(JsonObjects.builder().put("tools", new JsonArray(entries))
                .putNumber("totalMatches", entries.size()).put("nextOffset", JsonNull.INSTANCE).build());
    }

    /** 生成成对的 search Tool call，避免测试只验证结果文本而绕过真实配对逻辑。 */
    private static ContextMessage callMessage() {
        return callMessage("call_search", 1);
    }

    /** 为多页历史生成不重复调用身份，保持恢复器的配对表可验证。 */
    private static ContextMessage callMessage(String callId, long ordinal) {
        return new ContextMessage("message_call_" + callId, "turn_1", ordinal, ContextMessage.Role.ASSISTANT,
                List.of(new ContextMessage.ToolCallBlock(callId, McpToolSearch.NAME, "{}")), 1);
    }

    /** 将 search 结果封装为持久化形态；投影测试再由 ContextMessage.project 产生真实尾注。 */
    private ContextMessage searchHistory(List<AgentTool> tools, boolean ignored, ToolOutputProjector projector) {
        ContextMessage result = toolResult("call_search", McpToolSearch.NAME, searchContent(tools, false));
        return projector == null ? result : result.project(projector);
    }

    /** 创建一个结构化 Tool result，保持调用与结果的显式身份配对。 */
    private static ContextMessage toolResult(String callId, String name, String content) {
        return toolResult(callId, name, content, 2);
    }

    /** 为多页历史生成对应序号的成功 Tool result。 */
    private static ContextMessage toolResult(String callId, String name, String content, long ordinal) {
        return new ContextMessage("message_result_" + callId, "turn_1", ordinal, ContextMessage.Role.TOOL,
                List.of(new ContextMessage.ToolResultBlock(callId, name,
                        ContextMessage.ToolOutput.full(content, null, null, null))), 1);
    }

    /** 创建带稳定错误的 Tool result，失败搜索不能成为目录激活凭据。 */
    private static ContextMessage failedToolResult(String callId, String name, String content) {
        return new ContextMessage("message_failed_" + callId, "turn_1", 2, ContextMessage.Role.TOOL,
                List.of(new ContextMessage.ToolResultBlock(callId, name,
                        ContextMessage.ToolOutput.full(content, null, null, "MCP_TOOL_FAILED"))), 1);
    }

    /** 只按工具名称判断模型声明是否已恢复目标，忽略与本断言无关的顺序。 */
    private static boolean hasTool(List<ToolSpec> specs, AgentTool tool) {
        return specs.stream().anyMatch(spec -> spec.name().equals(tool.spec().name()));
    }
}
