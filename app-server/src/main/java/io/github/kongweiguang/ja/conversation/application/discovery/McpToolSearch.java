// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.discovery;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.JsonValueCodec;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonNumber;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.foundation.json.JsonValue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 在当前已授权的 MCP Tool 快照中提供有界本地搜索；完整定义由下一轮目录重新暴露，
 * 这样搜索结果既能发现能力，又不会把远端 Schema 复制进模型上下文。
 */
public final class McpToolSearch implements AgentTool {
    public static final String NAME = "tool_search";

    private static final int MAX_QUERY_LENGTH = 256;
    private static final int MAX_OFFSET = 100_000;
    /** 搜索摘要页大小由暴露恢复策略共享，防止恢复逻辑与实际返回页数漂移。 */
    public static final int PAGE_SIZE = 5;
    private static final int MAX_DESCRIPTION_LENGTH = 256;
    private static final ToolSpec BASE_SPEC = new ToolSpec(NAME,
            "Search currently authorized MCP tools. Empty query browses pages. "
                    + "Only recent tool definitions are retained; if a target definition is not visible "
                    + "or context was compacted, search again. Call tool_search when a needed MCP definition "
                    + "is absent; matching definitions become available on the next model request.", inputSchema());

    private final List<SearchEntry> entries;
    private final ToolSpec spec;
    private final JsonValueCodec codec;

    /**
     * 冻结调用方传入的授权快照并提前建立搜索索引，避免执行阶段读取会变化的 MCP 目录或重复解析 Schema。
     */
    public McpToolSearch(List<AgentTool> mcpTools, JsonValueCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
        List<AgentTool> tools = List.copyOf(Objects.requireNonNull(mcpTools, "mcpTools"));
        Set<String> names = new HashSet<>();
        List<SearchEntry> prepared = new ArrayList<>(tools.size());
        for (AgentTool tool : tools) {
            Objects.requireNonNull(tool, "mcp tool");
            AgentTool.ToolBindingDescriptor binding = Objects.requireNonNull(tool.bindingDescriptor(),
                    "MCP binding descriptor");
            if (binding.routeKind() != AgentTool.RouteKind.MCP) {
                throw new IllegalArgumentException("tool_search accepts MCP tools only");
            }
            if (!binding.localName().equals(tool.spec().name()) || !names.add(binding.localName())) {
                throw new IllegalArgumentException("MCP tool binding names must be unique and stable");
            }
            prepared.add(new SearchEntry(tool, binding, searchableTokens(tool, binding)));
        }
        prepared.sort(SearchEntry.ORDER);
        this.entries = List.copyOf(prepared);
        this.spec = withServiceHints(entries);
    }

    /**
     * 返回固定输入契约；目录内容只能影响描述提示，不能影响参数 Schema，保证恢复绑定身份稳定。
     */
    @Override
    public ToolSpec spec() {
        return spec;
    }

    /** 搜索仅访问已经冻结的内存目录，不读取 Workspace，也不产生外部副作用。 */
    @Override
    public ToolSideEffect sideEffect() {
        return ToolSideEffect.READ_ONLY;
    }

    /** 搜索不写 Workspace，显式声明 NONE 使内核无需为其降级工作区完整性。 */
    @Override
    public WorkspaceMutationMode workspaceMutationMode() {
        return WorkspaceMutationMode.NONE;
    }

    /**
     * 工具发现只读取已冻结的本地 MCP 目录；它不调用远端 MCP，因此不应把权限审批卡在发现步骤前。
     * 真实 MCP Tool 仍使用默认 USER_REQUIRED，不能通过名称或返回内容获得豁免。
     */
    @Override
    public ApprovalRequirement approvalRequirement() {
        return ApprovalRequirement.TRUSTED_INTERNAL;
    }

    /**
     * 在单一内存边界完成校验、匹配和分页；取消或参数错误返回可供模型纠正的 ToolResult，
     * 不把异常抛出到 ToolRunner 外层，避免一次发现请求破坏整个 Tool continuation。
     */
    @Override
    public CompletionStage<ToolResult> execute(Invocation invocation, ExecutionContext context,
                                               CancellationToken cancellationToken) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        try {
            cancellationToken.throwIfCancellationRequested();
            if (!NAME.equals(invocation.toolName())) {
                return completedFailure("TOOL_ARGUMENTS_INVALID",
                        "Tool name does not match the current search binding.");
            }
            SearchArguments arguments = parseArguments(invocation.arguments());
            List<SearchEntry> matches = find(arguments.query(), cancellationToken);
            int start = Math.min(arguments.offset(), matches.size());
            int end = Math.min(matches.size(), start + PAGE_SIZE);
            List<JsonValue> resultTools = new ArrayList<>(end - start);
            for (int index = start; index < end; index++) {
                cancellationToken.throwIfCancellationRequested();
                resultTools.add(toResult(matches.get(index)));
            }
            Integer nextOffset = end < matches.size() ? end : null;
            var resultBuilder = JsonObjects.builder()
                    .put("tools", new JsonArray(resultTools))
                    .putNumber("totalMatches", matches.size())
                    .put("nextOffset", nextOffset == null
                            ? io.github.kongweiguang.ja.foundation.json.JsonNull.INSTANCE
                            : new JsonNumber(nextOffset));
            if (matches.isEmpty()) {
                resultBuilder.putText("message",
                        "No MCP tools matched this query. Try different keywords or use an empty query to browse pages.");
            } else if (start >= matches.size()) {
                resultBuilder.putText("message",
                        "This search offset is past the end of the current results. The directory may have changed; try offset 0.");
            }
            JsonObject result = resultBuilder.build();
            return CompletableFuture.completedFuture(new ToolResult(ToolOutcome.SUCCEEDED,
                    codec.encode(result), java.util.Optional.of(result), null));
        } catch (CancellationException cancelled) {
            return completedCancelled();
        } catch (IllegalArgumentException invalid) {
            return completedFailure("TOOL_ARGUMENTS_INVALID", invalid.getMessage());
        }
    }

    /**
     * 用大小写不敏感的本地字段完成确定性排序；精确本地名始终优先，其余命中要求所有关键词出现，
     * 从而不会用模糊推荐伪造不存在的命中。
     */
    private List<SearchEntry> find(String query, CancellationToken cancellationToken) {
        if (query.isBlank()) return entries;
        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) return List.of();
        String normalizedQuery = normalize(query);
        List<ScoredEntry> scored = new ArrayList<>();
        for (SearchEntry entry : entries) {
            cancellationToken.throwIfCancellationRequested();
            if (exactIdentity(entry, normalizedQuery) || entry.tokens().containsAll(queryTokens)) {
                int score = score(entry, normalizedQuery);
                scored.add(new ScoredEntry(entry, score));
            }
        }
        scored.sort(Comparator.comparingInt(ScoredEntry::score).reversed()
                .thenComparing(ScoredEntry::entry, SearchEntry.ORDER));
        return scored.stream().map(ScoredEntry::entry).toList();
    }

    /** 先用去分隔符的稳定身份召回 camelCase 工具，再回退到分词 AND，避免直观名称搜索漏召回。 */
    private static boolean exactIdentity(SearchEntry entry, String normalizedQuery) {
        return normalize(entry.binding().localName()).equals(normalizedQuery)
                || normalize(entry.binding().remoteName()).equals(normalizedQuery)
                || normalize(entry.binding().serverId()).equals(normalizedQuery);
    }

    /** 精确本地工具名、远端名和服务 ID 依次提高相关性，普通关键词仍保持可预测顺序。 */
    private static int score(SearchEntry entry, String normalizedQuery) {
        String local = normalize(entry.binding().localName());
        String remote = normalize(entry.binding().remoteName());
        String server = normalize(entry.binding().serverId());
        if (local.equals(normalizedQuery)) return 4;
        if (remote.equals(normalizedQuery) || server.equals(normalizedQuery)) return 3;
        if (local.startsWith(normalizedQuery)) return 2;
        return 1;
    }

    /** 将匹配项投影成小型身份摘要，明确禁止把真实远端 inputSchema 写入返回正文。 */
    private static JsonObject toResult(SearchEntry entry) {
        return JsonObjects.builder()
                .putText("name", entry.binding().localName())
                .putText("description", boundedDescription(entry.tool().spec().description()))
                .putText("schemaHash", entry.binding().schemaHash())
                .putText("routeHash", entry.binding().routeHash())
                .build();
    }

    /** 将描述压缩到固定上限并折叠控制空白，防止目录描述反向制造无界 ToolResult。 */
    private static String boundedDescription(String description) {
        String compact = description.replaceAll("\\s+", " ").trim();
        if (compact.codePointCount(0, compact.length()) <= MAX_DESCRIPTION_LENGTH) return compact;
        int end = compact.offsetByCodePoints(0, MAX_DESCRIPTION_LENGTH - 1);
        return compact.substring(0, end) + "...";
    }

    /** 提取查询与分页参数并拒绝未知字段，避免模型误把下一轮参数拼进当前发现请求。 */
    private static SearchArguments parseArguments(JsonObject arguments) {
        Objects.requireNonNull(arguments, "arguments");
        if (arguments.members().keySet().stream().anyMatch(key -> !key.equals("query") && !key.equals("offset"))) {
            throw new IllegalArgumentException("Tool search accepts only query and offset.");
        }
        JsonValue queryValue = arguments.get("query");
        if (!(queryValue instanceof JsonText query)) {
            throw new IllegalArgumentException("query is required and must be a string.");
        }
        if (query.value().codePointCount(0, query.value().length()) > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("query is too long; use at most 256 characters.");
        }
        int offset = 0;
        JsonValue offsetValue = arguments.get("offset");
        if (offsetValue != null) {
            if (!(offsetValue instanceof JsonNumber number)) {
                throw new IllegalArgumentException("offset must be a non-negative integer.");
            }
            try {
                BigInteger integer = number.value().toBigIntegerExact();
                if (integer.signum() < 0 || integer.compareTo(BigInteger.valueOf(MAX_OFFSET)) > 0) {
                    throw new IllegalArgumentException("offset must be between 0 and 100000.");
                }
                offset = integer.intValueExact();
            } catch (ArithmeticException invalid) {
                throw new IllegalArgumentException("offset must be a non-negative integer.");
            }
        }
        return new SearchArguments(query.value(), offset);
    }

    /** 在索引建立时把名称、远端名称、服务 ID 和描述统一拆词，兼顾 camel/snake 与中文字符。 */
    private static Set<String> searchableTokens(AgentTool tool, AgentTool.ToolBindingDescriptor binding) {
        List<String> values = List.of(binding.localName(), binding.remoteName(), binding.serverId(),
                tool.spec().description());
        Set<String> tokens = new HashSet<>();
        values.forEach(value -> tokens.addAll(tokenize(value)));
        return Set.copyOf(tokens);
    }

    /** 以 Unicode 字母数字为边界，保留中文单字 token，并在大小写跃迁处支持 camelCase 查询。 */
    private static List<String> tokenize(String value) {
        List<String> tokens = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        int previous = -1;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (!Character.isLetterOrDigit(codePoint)) {
                addWord(tokens, word);
                previous = -1;
                continue;
            }
            boolean cjk = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
            boolean camelBoundary = previous >= 0 && Character.isUpperCase(codePoint)
                    && Character.isLowerCase(previous);
            if (cjk || camelBoundary) addWord(tokens, word);
            if (cjk) tokens.add(new String(Character.toChars(codePoint)));
            else word.appendCodePoint(Character.toLowerCase(codePoint));
            previous = codePoint;
        }
        addWord(tokens, word);
        return List.copyOf(tokens);
    }

    /** 结束一个英文/数字 token，避免空分隔符进入匹配集合。 */
    private static void addWord(List<String> tokens, StringBuilder word) {
        if (word.length() > 0) {
            tokens.add(word.toString());
            word.setLength(0);
        }
    }

    /** 统一精确比较所需的大小写与分隔符形式；token 匹配仍负责 camel/snake 语义。 */
    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** 在工具 Schema 固定的前提下构建可选的服务发现提示，内容始终保持有界。 */
    private static ToolSpec withServiceHints(List<SearchEntry> entries) {
        List<String> servers = entries.stream().map(entry -> entry.binding().serverId()).distinct().sorted().toList();
        String hint = servers.isEmpty() ? " No MCP services are currently available."
                : " Current MCP services: " + boundedServiceList(servers) + ".";
        return new ToolSpec(NAME, BASE_SPEC.description() + hint, BASE_SPEC.inputSchema());
    }

    /** 只展示少量服务 ID 作为发现线索，避免服务目录变成另一份无界上下文。 */
    private static String boundedServiceList(List<String> servers) {
        StringBuilder result = new StringBuilder();
        for (String server : servers) {
            if (result.length() > 0) result.append(", ");
            if (result.length() + server.length() > 384) {
                result.append("…");
                break;
            }
            result.append(server);
        }
        return result.toString();
    }

    /** 固定 Schema 的集中定义，参数变化必须显式改变源代码而不能随目录内容漂移。 */
    private static JsonObject inputSchema() {
        JsonObject querySchema = JsonObjects.builder().putText("type", "string")
                .putText("description", "Use short keywords, a tool name, or a service ID. "
                        + "Multiple words use AND matching; this is not semantic search. "
                        + "Use an empty string to browse.")
                .putNumber("maxLength", MAX_QUERY_LENGTH).build();
        JsonObject offsetSchema = JsonObjects.builder().putText("type", "integer")
                .putText("description", "Use nextOffset from the previous result to continue browsing.")
                .putNumber("minimum", 0).putNumber("maximum", MAX_OFFSET).build();
        JsonObject properties = JsonObjects.builder().put("query", querySchema)
                .put("offset", offsetSchema).build();
        return JsonObjects.builder().putText("type", "object").put("properties", properties)
                .put("required", new JsonArray(List.of(new JsonText("query"))))
                .putBoolean("additionalProperties", false).build();
    }

    /** 用统一错误码结束失败分支，保持 ToolRunner 能够让模型自行纠正。 */
    private static CompletionStage<ToolResult> completedFailure(String code, String content) {
        return CompletableFuture.completedFuture(new ToolResult(ToolOutcome.FAILED,
                content == null || content.isBlank() ? "Tool search arguments are invalid." : content,
                java.util.Optional.empty(), code));
    }

    /** 取消不是参数错误，单独保留 CANCELLED 结果使上层调度器停止重试并结束当前调用。 */
    private static CompletionStage<ToolResult> completedCancelled() {
        return CompletableFuture.completedFuture(new ToolResult(ToolOutcome.CANCELLED,
                "Tool search was cancelled.", java.util.Optional.empty(), "TOOL_CANCELLED"));
    }

    /** 已完成类型检查的搜索输入，防止分页执行阶段重新解释弱类型 JSON。 */
    private record SearchArguments(String query, int offset) {
    }

    /** 将匹配分数与冻结目录项绑定，保持排序逻辑纯函数且可重复。 */
    private record ScoredEntry(SearchEntry entry, int score) {
    }

    /** 预解析的 MCP 目录项，只保留搜索字段和真实绑定摘要，不缓存远端 Schema 副本。 */
    private record SearchEntry(AgentTool tool, AgentTool.ToolBindingDescriptor binding, Set<String> tokens) {
        private static final Comparator<SearchEntry> ORDER = Comparator
                .comparing((SearchEntry entry) -> entry.binding().localName(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing((SearchEntry entry) -> entry.binding().serverId(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing((SearchEntry entry) -> entry.binding().remoteName(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing((SearchEntry entry) -> entry.binding().routeHash());
    }
}
