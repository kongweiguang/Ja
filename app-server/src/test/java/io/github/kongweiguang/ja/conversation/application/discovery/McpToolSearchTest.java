// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.support.TestJsonValueCodec;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** MCP Tool 搜索的行为测试，重点锁定确定性排序、分页、参数防御和摘要边界。 */
final class McpToolSearchTest {
    private final TestJsonValueCodec codec = new TestJsonValueCodec();

    /** 精确本地名在同一关键词候选中排在描述命中之前。 */
    @Test
    void exactLocalNameRanksBeforeOtherKeywordMatches() {
        McpToolSearch search = search(tool("read_file", "readFile", "files", "Read a file"),
                tool("read_directory", "readDirectory", "files", "Read a file from a directory"));

        JsonObject result = result(search, args("read_file", 0));

        assertEquals("read_file", firstName(result));
        assertEquals(2, number(result, "totalMatches"));
    }

    /** 验证中文字符、大小写和 camel/snake 形式共享同一搜索语义。 */
    @Test
    void matchesChineseCaseInsensitiveAndCamelSnakeTerms() {
        McpToolSearch search = search(tool("getWeather", "天气查询", "weather", "获取天气预报"));

        assertEquals("getWeather", firstName(result(search, args("天气", 0))));
        assertEquals("getWeather", firstName(result(search, args("GET_WEATHER", 0))));
        assertEquals("getWeather", firstName(result(search, args("GETWEATHER", 0))));
    }

    /** 验证空查询按固定五项分页且遍历不会遗漏或重复。 */
    @Test
    void emptyQueryPagesWithoutOmissions() {
        List<AgentTool> tools = List.of(tool("a", "a", "svc", "A"), tool("b", "b", "svc", "B"),
                tool("c", "c", "svc", "C"), tool("d", "d", "svc", "D"), tool("e", "e", "svc", "E"),
                tool("f", "f", "svc", "F"), tool("g", "g", "svc", "G"));
        McpToolSearch search = search(tools.toArray(AgentTool[]::new));
        List<String> names = new ArrayList<>();
        int offset = 0;
        Integer next;
        do {
            JsonObject result = result(search, args("", offset));
            JsonArray page = assertInstanceOf(JsonArray.class, result.get("tools"));
            page.values().forEach(value -> names.add(((JsonText) ((JsonObject) value).get("name")).value()));
            next = result.get("nextOffset") instanceof io.github.kongweiguang.ja.foundation.json.JsonNumber n
                    ? n.value().intValueExact() : null;
            if (next != null) offset = next;
        } while (next != null);

        assertEquals(List.of("a", "b", "c", "d", "e", "f", "g"), names);
    }

    /** 无结果仍返回可指导模型下一步操作的恢复提示。 */
    @Test
    void noResultsExplainRecovery() {
        JsonObject result = result(search(tool("read", "read", "files", "Read a file")), args("missing", 0));

        assertEquals(0, number(result, "totalMatches"));
        assertTrue(((JsonText) result.get("message")).value().contains("different keywords"));
    }

    /** 合法但过期的分页游标提示回到第一页，不伪装成整个目录为空。 */
    @Test
    void pastEndOffsetExplainsPaginationRecovery() {
        JsonObject result = result(search(tool("read", "read", "files", "Read a file")), args("", 5));

        assertEquals(1, number(result, "totalMatches"));
        assertEquals(0, ((JsonArray) result.get("tools")).values().size());
        assertTrue(((JsonText) result.get("message")).value().contains("offset 0"));
    }

    /** 非法分页参数不抛出到上层，而是返回可纠正的稳定错误。 */
    @Test
    void invalidArgumentsReturnFailureForModelCorrection() {
        McpToolSearch search = search(tool("read", "read", "files", "Read"));

        AgentTool.ToolResult result = execute(search, JsonObjects.builder().putText("query", "x")
                .putText("offset", "bad").build());

        assertEquals(ToolOutcome.FAILED, result.outcome());
        assertEquals("TOOL_ARGUMENTS_INVALID", result.errorCode());
    }

    /** 取消在搜索开始边界被识别并映射为 CANCELLED。 */
    @Test
    void cancellationReturnsCancelledResult() {
        McpToolSearch search = search(tool("read", "read", "files", "Read"));

        AgentTool.ToolResult result = search.execute(invocation(args("", 0)), context(), cancelled())
                .toCompletableFuture().join();

        assertEquals(ToolOutcome.CANCELLED, result.outcome());
        assertEquals("TOOL_CANCELLED", result.errorCode());
    }

    /** 摘要正文保持有界且不包含远端 Schema。 */
    @Test
    void outputIsBoundedAndContainsNoInputSchema() {
        String description = "x".repeat(2_000);
        McpToolSearch search = search(tool("long", "remoteLong", "svc", description));

        AgentTool.ToolResult result = execute(search, args("", 0));

        assertTrue(result.content().length() < 1_000);
        assertFalse(result.content().contains("inputSchema"));
        assertTrue(result.content().contains("schemaHash"));
        assertTrue(result.content().contains("routeHash"));
    }

    /** 构造器拒绝把内建 Tool 混入 MCP 搜索目录。 */
    @Test
    void rejectsBuiltinBindingAtConstruction() {
        AgentTool builtin = new AgentTool() {
            /** 返回最小内建 fixture 定义。 */
            @Override public ToolSpec spec() { return new ToolSpec("read", "Read", objectSchema()); }
            /** 返回已完成结果，避免构造无效的匿名 Tool。 */
            @Override public CompletionStage<ToolResult> execute(Invocation i, ExecutionContext c,
                                                                  CancellationToken t) {
                return CompletableFuture.completedFuture(ToolResult.success("fixture"));
            }
        };
        assertThrows(IllegalArgumentException.class, () -> new McpToolSearch(List.of(builtin), codec));
    }

    /** 创建使用测试 Codec 的搜索器。 */
    private McpToolSearch search(AgentTool... tools) {
        return new McpToolSearch(List.of(tools), codec);
    }

    /** 执行一次搜索并等待其同步结果。 */
    private AgentTool.ToolResult execute(McpToolSearch search, JsonObject arguments) {
        return search.execute(invocation(arguments), context(), CancellationToken.none())
                .toCompletableFuture().join();
    }

    /** 执行并解码成功结果，统一收紧各测试的断言入口。 */
    private JsonObject result(McpToolSearch search, JsonObject arguments) {
        AgentTool.ToolResult result = execute(search, arguments);
        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        return (JsonObject) codec.decode(result.content());
    }

    /** 提取结果第一页的第一个 Tool 名称。 */
    private static String firstName(JsonObject result) {
        JsonArray tools = (JsonArray) result.get("tools");
        return ((JsonText) ((JsonObject) tools.values().getFirst()).get("name")).value();
    }

    /** 读取结果中的整数元数据。 */
    private static int number(JsonObject result, String key) {
        return ((io.github.kongweiguang.ja.foundation.json.JsonNumber) result.get(key)).value().intValueExact();
    }

    /** 构造合法的搜索参数对象。 */
    private static JsonObject args(String query, int offset) {
        return JsonObjects.builder().putText("query", query).putNumber("offset", offset).build();
    }

    /** 构造最小合法对象 Schema。 */
    private static JsonObject objectSchema() {
        return JsonObjects.builder().putText("type", "object").build();
    }

    /** 构造带真实 MCP 绑定摘要的只读测试 Tool。 */
    private static AgentTool tool(String name, String remote, String server, String description) {
        ToolSpec spec = new ToolSpec(name, description, objectSchema());
        AgentTool.ToolBindingDescriptor binding = new AgentTool.ToolBindingDescriptor(
                AgentTool.RouteKind.MCP, name, server, remote,
                "a".repeat(64), "b".repeat(64));
        return new AgentTool() {
            /** 返回测试 MCP Tool 的固定定义。 */
            @Override public ToolSpec spec() { return spec; }
            /** 返回真实 MCP 绑定摘要，供搜索结果直接投影。 */
            @Override public AgentTool.ToolBindingDescriptor bindingDescriptor() { return binding; }
            /** 返回已完成结果，测试只关心发现不执行远端调用。 */
            @Override public ToolSideEffect sideEffect() { return ToolSideEffect.READ_ONLY; }
            /** 显式声明 fixture 不写 Workspace。 */
            @Override public WorkspaceMutationMode workspaceMutationMode() { return WorkspaceMutationMode.NONE; }
            /** 返回已完成结果，避免匿名 Tool 留下不完整的 null 执行契约。 */
            @Override public CompletionStage<ToolResult> execute(Invocation i, ExecutionContext c,
                                                                  CancellationToken t) {
                return CompletableFuture.completedFuture(ToolResult.success("fixture"));
            }
        };
    }

    /** 构造 tool_search 调用身份。 */
    private static AgentTool.Invocation invocation(JsonObject arguments) {
        return new AgentTool.Invocation("call_search", McpToolSearch.NAME, arguments, 0);
    }

    /** 提供满足 AgentTool 契约的最小执行上下文。 */
    private static AgentTool.ExecutionContext context() {
        return new AgentTool.ExecutionContext("thread_1", "turn_1", Path.of("C:/workspace").toAbsolutePath(),
                AccessMode.FULL_ACCESS, "cfg_1", Instant.now().plusSeconds(30), "ws_1");
    }

    /** 提供在首次检查即取消的令牌。 */
    private static CancellationToken cancelled() {
        return new CancellationToken() {
            /** 令牌始终报告已取消，覆盖搜索的首个取消边界。 */
            @Override public boolean isCancellationRequested() { return true; }
            /** 返回脱敏的固定测试原因。 */
            @Override public Optional<String> reason() { return Optional.of("test"); }
            /** 取消已发生，不保留测试回调。 */
            @Override public Registration onCancellation(Runnable callback) { return Registration.noop(); }
        };
    }
}
