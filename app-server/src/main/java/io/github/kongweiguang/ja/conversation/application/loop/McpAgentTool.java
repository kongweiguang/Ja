// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.JsonValueCodec;
import io.github.kongweiguang.ja.conversation.port.out.McpGateway;
import io.github.kongweiguang.ja.conversation.port.out.ToolArgumentValidator;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonNull;
import io.github.kongweiguang.ja.foundation.json.JsonNumber;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.foundation.json.JsonValue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 一个固定 Provider Tool 负责本地目录发现和精确 MCP 路由，避免远端名称进入供应商 Schema。 */
public final class McpAgentTool implements AgentTool {
    public static final String NAME = "mcp";
    private static final int PAGE_SIZE = 10;
    private static final int MAX_OFFSET = 100_000;
    private static final int MAX_ARGUMENT_JSON = 1_048_576;
    private static final ToolSpec SPEC = new ToolSpec(NAME,
            "Inspect and use the MCP tools available to this request. Use status, search, describe, then call. "
                    + "Search may filter by serverId. Call requires the exact serverId and toolName returned "
                    + "by search.", inputSchema());

    private final McpGateway gateway;
    private final McpGateway.McpSnapshot snapshot;
    private final Map<String, McpGateway.RouteIdentity> routes;
    private final List<McpGateway.McpServerStatus> servers;
    private final JsonValueCodec codec;
    private final ToolArgumentValidator argumentValidator;
    private final ToolBindingDescriptor gatewayBinding;

    /** 同时冻结目录、真实路由与序列化器，确保模型批次不会观察后续发现或配置切换。 */
    private McpAgentTool(McpGateway gateway, McpGateway.McpSnapshot snapshot,
                         Map<String, McpGateway.RouteIdentity> routes,
                         List<McpGateway.McpServerStatus> servers, JsonValueCodec codec,
                         ToolArgumentValidator argumentValidator) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.routes = Map.copyOf(Objects.requireNonNull(routes, "routes"));
        this.servers = List.copyOf(Objects.requireNonNull(servers, "servers"));
        this.codec = Objects.requireNonNull(codec, "codec");
        this.argumentValidator = Objects.requireNonNull(argumentValidator, "argumentValidator");
        if (this.routes.size() != snapshot.tools().size()) {
            throw new IllegalArgumentException("MCP route identities do not match catalog snapshot");
        }
        this.gatewayBinding = gatewayBinding(snapshot);
    }

    /** 只要存在启用服务就暴露同一个入口，健康零工具服务仍可被状态查询准确呈现。 */
    public static List<AgentTool> adapt(McpGateway gateway, McpGateway.McpSnapshot snapshot,
                                        Map<String, McpGateway.RouteIdentity> routeIdentities,
                                        JsonValueCodec codec, ToolArgumentValidator argumentValidator) {
        Objects.requireNonNull(gateway, "gateway");
        Objects.requireNonNull(snapshot, "snapshot");
        List<McpGateway.McpServerStatus> servers = gateway.serverStatuses();
        if (servers.isEmpty() && snapshot.tools().isEmpty()) return List.of();
        return List.of(new McpAgentTool(gateway, snapshot, routeIdentities, servers, codec, argumentValidator));
    }

    /** 返回所有 Provider 共用的固定声明，不受远端目录大小影响。 */
    public static ToolSpec modelSpec() {
        return SPEC;
    }

    /** 为固定网关及当前目录修订构造不可变内部摘要绑定。 */
    public static ToolBindingDescriptor catalogBinding(McpGateway.McpSnapshot snapshot) {
        return gatewayBinding(Objects.requireNonNull(snapshot, "snapshot"));
    }

    /** Provider 只接收全局固定结构；服务目录和远端 Schema 不参与工具声明。 */
    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    /** 无参数或未知 action 都按外部副作用处理，只有完整校验后的本地动作能收窄权限。 */
    @Override
    public ToolSideEffect sideEffect(Invocation invocation) {
        return action(invocation).filter(value -> Set.of("status", "search", "describe").contains(value))
                .map(ignored -> ToolSideEffect.READ_ONLY).orElse(ToolSideEffect.EXTERNAL);
    }

    /** 本地目录动作不写 Workspace；远端调用可能改变外部状态，继续触发既有完整性降级。 */
    @Override
    public WorkspaceMutationMode workspaceMutationMode(Invocation invocation) {
        return sideEffect(invocation) == ToolSideEffect.READ_ONLY
                ? WorkspaceMutationMode.NONE : WorkspaceMutationMode.UNOBSERVABLE;
    }

    /** status/search/describe 完全留在冻结内存目录中，不需要外部动作审批。 */
    @Override
    public ApprovalRequirement approvalRequirement(Invocation invocation) {
        return sideEffect(invocation) == ToolSideEffect.READ_ONLY
                ? ApprovalRequirement.TRUSTED_INTERNAL : ApprovalRequirement.USER_REQUIRED;
    }

    /** call 在 PREPARED 事实写入前解析成精确远端绑定；其余动作绑定当前网关目录摘要。 */
    @Override
    public ToolBindingDescriptor bindingDescriptor(Invocation invocation) {
        if (action(invocation).filter("call"::equals).isPresent()) {
            Target target = target(invocation.arguments());
            if (target != null) {
                McpGateway.RouteIdentity route = resolve(target);
                if (route != null) {
                    return new ToolBindingDescriptor(RouteKind.MCP, NAME, route.serverId(), route.remoteName(),
                            route.schemaHash(), route.routeHash());
                }
            }
        }
        return gatewayBinding;
    }

    /** 在模型 Tool 批次准备前校验 action、目标、JSON 对象和冻结远端 Schema，不访问网络。 */
    @Override
    public Optional<InvocationValidationFailure> validationFailure(Invocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        Action action;
        try {
            action = parse(invocation.arguments());
        } catch (IllegalArgumentException invalid) {
            return Optional.of(new InvocationValidationFailure(
                    "TOOL_ARGUMENTS_INVALID", invalid.getMessage()));
        }
        if (!"call".equals(action.action())) return Optional.empty();
        Target target = new Target(action.serverId(), action.toolName());
        McpGateway.McpTool tool = resolveTool(target);
        if (tool == null) {
            return Optional.of(new InvocationValidationFailure(
                    "MCP_TOOL_NOT_FOUND", "The requested MCP tool is not in this request catalog."));
        }
        JsonObject remoteArguments;
        try {
            remoteArguments = codec.decodeObject(action.argumentsJson());
        } catch (IllegalArgumentException invalid) {
            return Optional.of(new InvocationValidationFailure(
                    "MCP_ARGUMENTS_INVALID", "argumentsJson must contain a valid JSON object."));
        }
        try {
            if (argumentValidator.invalidReason(tool.spec().inputSchema(), remoteArguments).isPresent()) {
                return Optional.of(new InvocationValidationFailure(
                        "MCP_ARGUMENTS_INVALID", "MCP call arguments do not match the selected tool schema."));
            }
        } catch (RuntimeException invalidSchema) {
            return Optional.of(new InvocationValidationFailure(
                    "MCP_TOOL_SCHEMA_UNAVAILABLE", "The selected MCP tool schema could not be validated."));
        }
        return Optional.empty();
    }

    /** 只有当前冻结目录中确实存在的本地读取动作可取得可信网关豁免。 */
    public boolean isTrustedLocalReadAction(Invocation invocation) {
        return validationFailure(invocation).isEmpty()
                && action(invocation).filter(Set.of("status", "search", "describe")::contains).isPresent();
    }

    /** dispatch 阶段只有同一快照中的精确服务和远端名能进入 MCP Gateway。 */
    @Override
    public CompletionStage<ToolResult> execute(Invocation invocation, ExecutionContext context,
                                               CancellationToken cancellationToken) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        if (!NAME.equals(invocation.toolName())) return failed("TOOL_BINDING_UNAVAILABLE",
                "MCP gateway binding is unavailable.");
        Optional<InvocationValidationFailure> validation = validationFailure(invocation);
        if (validation.isPresent()) {
            InvocationValidationFailure failure = validation.orElseThrow();
            return failed(failure.code(), failure.message());
        }
        Action arguments;
        try {
            arguments = parse(invocation.arguments());
        } catch (IllegalArgumentException invalid) {
            return failed("TOOL_ARGUMENTS_INVALID", invalid.getMessage());
        }
        return switch (arguments.action()) {
            case "status" -> success(status());
            case "search" -> search(arguments);
            case "describe" -> describe(arguments);
            case "call" -> call(invocation, arguments, cancellationToken);
            default -> failed("TOOL_ARGUMENTS_INVALID", "action must be status, search, describe, or call.");
        };
    }

    /** 返回冻结请求目录状态；此查询从不 initialize 或刷新 MCP 服务。 */
    private JsonObject status() {
        List<JsonValue> values = servers.stream().map(server -> {
            var builder = JsonObjects.builder().putText("serverId", server.serverId())
                    .putText("name", server.name()).putText("state", server.state());
            if (server.toolCount() != null) builder.putNumber("toolCount", server.toolCount());
            if (server.reasonCode() != null) builder.putText("reasonCode", server.reasonCode());
            return (JsonValue) builder.build();
        }).toList();
        return JsonObjects.builder().put("servers", new JsonArray(values))
                .putText("catalogRevision", snapshot.revision()).build();
    }

    /** 对冻结目录先按可选服务身份过滤再分页，让模型能缩小大目录且保留跨服务重名条目。 */
    private CompletionStage<ToolResult> search(Action arguments) {
        String query = arguments.query() == null ? "" : arguments.query().toLowerCase(Locale.ROOT);
        List<ToolEntry> matches = entries().stream()
                .filter(entry -> arguments.serverId() == null
                        || entry.serverId().equals(arguments.serverId()))
                .filter(entry -> query.isBlank() || entry.serverId().toLowerCase(Locale.ROOT).contains(query)
                        || entry.toolName().toLowerCase(Locale.ROOT).contains(query)
                        || entry.description().toLowerCase(Locale.ROOT).contains(query))
                .toList();
        int start = Math.min(arguments.offset() == null ? 0 : arguments.offset(), matches.size());
        int end = Math.min(matches.size(), start + PAGE_SIZE);
        List<JsonValue> items = new ArrayList<>(end - start);
        for (int index = start; index < end; index++) {
            ToolEntry entry = matches.get(index);
            items.add(JsonObjects.builder().putText("serverId", entry.serverId())
                    .putText("toolName", entry.toolName()).putText("description", entry.description()).build());
        }
        Integer nextOffset = end < matches.size() ? end : null;
        var result = JsonObjects.builder().put("tools", new JsonArray(items))
                .putNumber("totalMatches", matches.size());
        if (nextOffset != null) result.putNumber("nextOffset", nextOffset);
        return success(result.build());
    }

    /** 只在模型明确描述某工具时投影其冻结 Schema，避免把整个远端目录带入每次请求。 */
    private CompletionStage<ToolResult> describe(Action arguments) {
        Target target = new Target(arguments.serverId(), arguments.toolName());
        McpGateway.McpTool tool = resolveTool(target);
        if (tool == null) return failed("MCP_TOOL_NOT_FOUND", "The requested MCP tool is not in this request catalog.");
        return success(JsonObjects.builder().putText("serverId", target.serverId())
                .putText("toolName", target.toolName()).putText("description", tool.spec().description())
                .put("inputSchema", tool.spec().inputSchema()).build());
    }

    /** 解析 argumentsJson 为对象并调用绑定的路由；不确定远端失败明确提示检查真实状态后再重试。 */
    private CompletionStage<ToolResult> call(Invocation invocation, Action arguments,
                                              CancellationToken cancellationToken) {
        Target target = new Target(arguments.serverId(), arguments.toolName());
        McpGateway.McpTool tool = resolveTool(target);
        if (tool == null) return failed("MCP_TOOL_NOT_FOUND", "The requested MCP tool is not in this request catalog.");
        JsonObject remoteArguments;
        try {
            remoteArguments = codec.decodeObject(arguments.argumentsJson());
        } catch (IllegalArgumentException invalid) {
            return failed("MCP_ARGUMENTS_INVALID", "argumentsJson must contain a valid JSON object.");
        }
        try {
            return gateway.invoke(snapshot, new McpGateway.McpInvocation(invocation.callId(),
                            tool.spec().name(), remoteArguments, invocation.ordinal()), cancellationToken)
                    .handle((result, failure) -> {
                        if (failure != null) return new ToolResult(ToolOutcome.FAILED,
                                "MCP call did not return a confirmed result. Check the external state before retrying.",
                                Optional.empty(), "TOOL_EXECUTION_UNCONFIRMED");
                        return new ToolResult(result.outcome(), result.content(), result.structuredContent(),
                                result.error() ? "MCP_TOOL_FAILED" : null);
                    });
        } catch (RuntimeException failure) {
            return failed("MCP_TOOL_FAILED", "MCP call failed before the remote tool was invoked.");
        }
    }

    /** 按服务 ID 和原始远端名称精确匹配；不会解码模型提供的本地命名空间字符串。 */
    private McpGateway.McpTool resolveTool(Target target) {
        return snapshot.tools().stream().filter(tool -> {
            McpGateway.RouteIdentity route = routes.get(tool.spec().name());
            return route != null && route.serverId().equals(target.serverId())
                    && route.remoteName().equals(target.toolName());
        }).findFirst().orElse(null);
    }

    /** 返回目标工具的不可变路由身份，缺失时调用方只能产生模型可见错误。 */
    private McpGateway.RouteIdentity resolve(Target target) {
        McpGateway.McpTool tool = resolveTool(target);
        return tool == null ? null : routes.get(tool.spec().name());
    }

    /** 构建按 serverId 与远端名称稳定排序的可搜索目录。 */
    private List<ToolEntry> entries() {
        return snapshot.tools().stream().map(tool -> {
            McpGateway.RouteIdentity route = routes.get(tool.spec().name());
            if (route == null) throw new IllegalStateException("MCP route identity is missing");
            return new ToolEntry(route.serverId(), route.remoteName(), tool.spec().description());
        }).sorted(java.util.Comparator.comparing(ToolEntry::serverId)
                .thenComparing(ToolEntry::toolName)).toList();
    }

    /** 只接受固定参数集合，并在模型提交 PREPARED 事实前验证动作字段组合。 */
    private static Action parse(JsonObject arguments) {
        java.util.Set<String> allowed = java.util.Set.of(
                "action", "serverId", "toolName", "query", "offset", "argumentsJson");
        if (!arguments.members().containsKey("action")
                || !allowed.containsAll(arguments.members().keySet())) {
            throw new IllegalArgumentException("MCP action arguments do not match the fixed schema.");
        }
        String action = text(arguments, "action", false);
        String server = optionalText(arguments, "serverId", 128);
        String tool = optionalText(arguments, "toolName", 256);
        String query = optionalText(arguments, "query", 256);
        Integer offset = optionalOffset(arguments.get("offset"));
        String remoteArguments = optionalText(arguments, "argumentsJson", MAX_ARGUMENT_JSON);
        boolean valid = switch (action) {
            case "status" -> server == null && tool == null && query == null && offset == null
                    && remoteArguments == null;
            case "search" -> tool == null && remoteArguments == null;
            case "describe" -> server != null && tool != null && query == null && offset == null
                    && remoteArguments == null;
            case "call" -> server != null && tool != null && query == null && offset == null
                    && remoteArguments != null;
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException(
                "MCP action fields do not match status, search, describe, or call.");
        return new Action(action, server, tool, query, offset, remoteArguments);
    }

    /** 固定网关路由读取 action，参数损坏时保守落到外部动作分类。 */
    private static Optional<String> action(Invocation invocation) {
        if (invocation == null) return Optional.empty();
        return invocation.arguments().get("action") instanceof JsonText action
                ? Optional.of(action.value()) : Optional.empty();
    }

    /** 单次 call 的绑定仅在目标字段类型与标识界限有效时建立。 */
    private static Target target(JsonObject arguments) {
        try {
            String action = text(arguments, "action", false);
            if (!"call".equals(action)) return null;
            String server = optionalText(arguments, "serverId", 128);
            String tool = optionalText(arguments, "toolName", 256);
            return server == null || tool == null ? null : new Target(server, tool);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** 所有可空参数仍是 required JSON Schema 属性，保证 OpenAI strict mode 接受固定入口。 */
    private static JsonObject inputSchema() {
        JsonObject action = JsonObjects.builder().putText("type", "string")
                .put("enum", new JsonArray(List.of(new JsonText("status"), new JsonText("search"),
                        new JsonText("describe"), new JsonText("call"))))
                .build();
        JsonObject server = nullableText(128);
        JsonObject tool = nullableText(256);
        JsonObject query = nullableText(256);
        JsonObject offset = JsonObjects.builder().put("type", new JsonArray(List.of(
                        new JsonText("integer"), new JsonText("null"))))
                .putNumber("minimum", 0).putNumber("maximum", MAX_OFFSET).build();
        JsonObject arguments = nullableText(MAX_ARGUMENT_JSON);
        JsonObject properties = JsonObjects.builder().put("action", action).put("serverId", server)
                .put("toolName", tool).put("query", query).put("offset", offset)
                .put("argumentsJson", arguments).build();
        JsonArray required = new JsonArray(List.of("action", "serverId", "toolName", "query", "offset",
                "argumentsJson").stream().map(JsonText::new).map(JsonValue.class::cast).toList());
        return JsonObjects.builder().putText("type", "object").put("properties", properties)
                .put("required", required).putBoolean("additionalProperties", false).build();
    }

    /** 字符串属性同时允许 JSON null，以兼容 strict schema 的固定 required 集合。 */
    private static JsonObject nullableText(int maximum) {
        return JsonObjects.builder().put("type", new JsonArray(List.of(
                        new JsonText("string"), new JsonText("null"))))
                .putNumber("maxLength", maximum).build();
    }

    /** 解析必填 action 字符串，不回显模型提供的参数值。 */
    private static String text(JsonObject object, String key, boolean nullable) {
        JsonValue value = object.get(key);
        if (nullable && value == JsonNull.INSTANCE) return null;
        if (value instanceof JsonText text && !text.value().isBlank()) return text.value();
        throw new IllegalArgumentException("MCP field '" + key + "' must be a non-empty string.");
    }

    /** 有界读取可空字符串，未知 JSON 类型只返回安全字段名。 */
    private static String optionalText(JsonObject object, String key, int maximum) {
        JsonValue value = object.get(key);
        if (value == null || value == JsonNull.INSTANCE) return null;
        if (value instanceof JsonText text && !text.value().isBlank() && text.value().length() <= maximum) {
            return text.value();
        }
        throw new IllegalArgumentException("MCP field '" + key + "' must be null or a bounded non-empty string.");
    }

    /** 分页值必须是有界非负整数；fractional 与 overflow 均视为模型可纠正参数错误。 */
    private static Integer optionalOffset(JsonValue value) {
        if (value == null || value == JsonNull.INSTANCE) return null;
        if (!(value instanceof JsonNumber number)) throw new IllegalArgumentException("offset must be an integer.");
        try {
            BigInteger integer = number.value().toBigIntegerExact();
            if (integer.signum() < 0 || integer.compareTo(BigInteger.valueOf(MAX_OFFSET)) > 0) {
                throw new IllegalArgumentException("offset is outside the bounded search range.");
            }
            return integer.intValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("offset must be an integer.");
        }
    }

    /** 静态 descriptor 绑定固定模型 Schema 和整份发现目录修订，供只读动作恢复对账。 */
    private static ToolBindingDescriptor gatewayBinding(McpGateway.McpSnapshot snapshot) {
        String schemaHash = sha256(AgentTool.canonicalSchema(SPEC.inputSchema()));
        return new ToolBindingDescriptor(RouteKind.MCP, NAME, "mcp-gateway", NAME, schemaHash,
                sha256("mcp-gateway\0" + snapshot.revision() + "\0" + schemaHash));
    }

    /** 内存 JSON 值摘要使用固定 SHA-256，不把快照 Schema 或 Server 定义写入权限事实。 */
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** 统一构造安全 JSON ToolResult，让错误回到模型作为可纠正反馈。 */
    private CompletionStage<ToolResult> success(JsonObject content) {
        String encoded = codec.encode(content);
        return CompletableFuture.completedFuture(new ToolResult(ToolOutcome.SUCCEEDED, encoded,
                Optional.of(content), null));
    }

    /** 错误消息来自固定模板或受限字段诊断，不包含 MCP 参数正文。 */
    private static CompletionStage<ToolResult> failed(String code, String message) {
        return CompletableFuture.completedFuture(new ToolResult(ToolOutcome.FAILED,
                message == null || message.isBlank() ? "MCP action arguments are invalid." : message,
                Optional.empty(), code));
    }

    /** 解析完成的固定参数集，防止 dispatch 阶段重复解释弱类型 JSON。 */
    private record Action(String action, String serverId, String toolName, String query,
                          Integer offset, String argumentsJson) { }

    /** 以模型所见原始 MCP 身份定位一个唯一远端 Tool。 */
    private record Target(String serverId, String toolName) { }

    /** 搜索排序与渲染只依赖快照字段，重复名称因 serverId 不同而保留。 */
    private record ToolEntry(String serverId, String toolName, String description) { }
}
