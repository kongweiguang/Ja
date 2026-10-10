// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import io.github.kongweiguang.ja.catalog.domain.model.McpServerDescriptor;
import io.github.kongweiguang.ja.catalog.domain.model.McpToolDescriptor;
import io.github.kongweiguang.ja.catalog.domain.model.SkillDescriptor;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcCodec;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcResults;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.IntFunction;

/**
 * 严格映射只含描述信息的 Skill 页面与有界 MCP 健康、Tool 目录。
 */
public final class SettingsCatalogHandler implements RpcHandler {
    private static final int RESPONSE_FRAME_BUDGET = JaRpcCodec.DEFAULT_MAX_FRAME_BYTES - 64 * 1024;
    private final RpcSession session;

    /**
     * 只绑定连接会话，不保留原始配置 JSON、配置代际或凭据查询状态。
     */
    public SettingsCatalogHandler(RpcSession session) {
        this.session = session;
    }

    /**
     * 保留配置错误的固定 RPC 映射，同时将其余 Provider 失败收敛为模型不可用，避免同步异常和
     * CompletionException 包装在模型验证、目录读取两条 Wire 路径中产生不同错误类别。
     */
    private static JaRpcException modelUnavailable(Throwable failure, String message) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException
                          && failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof io.github.kongweiguang.ja.configuration.domain.model.ConfigurationError error) {
            throw error;
        }
        return JaRpcException.of(
                io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog.MODEL_UNAVAILABLE, message);
    }

    /**
     * 只暴露冻结的查询与探测方法，不恢复 Java 侧 Profile/MCP 旧 CRUD 入口。
     */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.SKILL_LIST, RpcMethod.MCP_LIST, RpcMethod.MCP_TEST, RpcMethod.MODEL_TEST,
                RpcMethod.MODEL_DISCOVER, RpcMethod.MCP_LIST_TOOLS);
    }

    /**
     * ready 校验后分派严格命令；只有包含 MCP IO 的探测保留异步生命周期。
     */
    @Override
    public CompletionStage<ObjectNode> handle(RpcCommand command) {
        session.requireReady();
        return switch (command.method()) {
            case SKILL_LIST -> CompletableFuture.completedFuture(skills(command.params()));
            case MCP_LIST -> CompletableFuture.completedFuture(mcp(command.params()));
            case MCP_TEST -> test(command.params());
            case MODEL_TEST -> testModel(command.params());
            case MODEL_DISCOVER -> discoverModels(command.params());
            case MCP_LIST_TOOLS -> CompletableFuture.completedFuture(tools(command.params()));
            default -> throw JaRpcException.methodNotFound();
        };
    }

    /**
     * 映射统一的 items 页面，禁止 Skill 正文、来源路径或配置实现类型进入 Wire。
     */
    private ObjectNode skills(ObjectNode params) {
        RpcParams.requireOnly(params, "workspaceId", "cursor", "limit");
        String workspaceId = RpcParams.optionalText(params, "workspaceId", 100);
        if (workspaceId != null) {
            workspaceId = RpcParams.identifier(workspaceId, "ws_", 100);
        }
        String requestedWorkspaceId = workspaceId;
        String cursor = RpcParams.optionalText(params, "cursor", 512);
        return withinFrame(RpcParams.pageLimit(params), limit -> {
            CursorPage<SkillDescriptor> page = session.catalog().listSkills(requestedWorkspaceId, cursor, limit);
            ObjectNode result = session.mapper().createObjectNode();
            ArrayNode values = result.putArray("items");
            page.items().forEach(value -> values.addObject().put("skillId", value.skillId())
                    .put("name", value.name()).put("scope", value.scope()).put("enabled", value.enabled())
                    .put("status", value.status()).put("description", value.description()));
            RpcResults.cursor(result, page.nextCursor());
            return result;
        });
    }

    /**
     * 映射统一的 items 页面，并禁止 endpoint、参数、环境、Header、认证或 Secret 字段。
     */
    private ObjectNode mcp(ObjectNode params) {
        RpcParams.requireOnly(params, "workspaceId", "cursor", "limit");
        String workspaceId = RpcParams.optionalText(params, "workspaceId", 100);
        if (workspaceId != null) {
            workspaceId = RpcParams.identifier(workspaceId, "ws_", 100);
        }
        String requestedWorkspaceId = workspaceId;
        String cursor = RpcParams.optionalText(params, "cursor", 512);
        return withinFrame(RpcParams.pageLimit(params), limit -> {
            CursorPage<McpServerDescriptor> page = session.catalog().listMcp(requestedWorkspaceId, cursor, limit);
            ObjectNode result = session.mapper().createObjectNode();
            ArrayNode values = result.putArray("items");
            page.items().forEach(value -> values.add(mcpView(value)));
            RpcResults.cursor(result, page.nextCursor());
            return result;
        });
    }

    /**
     * 在同一租约内先确认 MCP 身份再执行有界探测，结果只包含健康与 Tool 数量。
     */
    private CompletionStage<ObjectNode> test(ObjectNode params) {
        RpcParams.requireOnly(params, "workspaceId", "mcpId");
        String workspaceId = RpcParams.optionalText(params, "workspaceId", 100);
        if (workspaceId != null) {
            workspaceId = RpcParams.identifier(workspaceId, "ws_", 100);
        }
        String mcpId = RpcParams.identifier(params, "mcpId", "mcp_", 100);
        return session.catalog().testMcp(workspaceId, mcpId).thenApply(this::mcpView);
    }

    /**
     * 模型验证结果只返回脱敏模型名和耗时；Provider 失败统一收敛为稳定可重试错误。
     */
    private CompletionStage<ObjectNode> testModel(ObjectNode params) {
        RpcParams.requireExact(params, "providerId", "modelId");
        String providerId = RpcParams.identifier(params, "providerId", "provider_", 108);
        String modelId = RpcParams.identifier(params, "modelId", "model_", 108);
        return session.catalog().testModel(providerId, modelId, session.cancellationToken())
                .handle((result, failure) -> {
                    if (failure != null) {
                        throw modelUnavailable(failure, "model is unavailable");
                    }
                    return session.mapper().createObjectNode()
                            .put("responseModel", result.responseModel())
                            .put("latencyMs", result.latencyMs());
                });
    }

    /**
     * 只接受已保存 Provider 身份，并将上游目录压缩成模型字符串与截断事实；HTTP 失败沿用模型
     * 可用性错误，不公开 URL、Header、正文或凭据。
     */
    private CompletionStage<ObjectNode> discoverModels(ObjectNode params) {
        RpcParams.requireExact(params, "providerId");
        String providerId = RpcParams.identifier(params, "providerId", "provider_", 108);
        return session.catalog().discoverModels(providerId, session.cancellationToken())
                .handle((result, failure) -> {
                    if (failure != null) {
                        throw modelUnavailable(failure, "model discovery is unavailable");
                    }
                    ObjectNode response = session.mapper().createObjectNode();
                    ArrayNode items = response.putArray("items");
                    result.items().forEach(items::add);
                    response.put("truncated", result.truncated());
                    return response;
                });
    }

    /**
     * 从显式 MCP 身份读取一页有界 Tool Schema；响应只保留统一列表字段，不回显请求身份。
     */
    private ObjectNode tools(ObjectNode params) {
        RpcParams.requireOnly(params, "workspaceId", "mcpId", "cursor", "limit");
        String workspaceId = RpcParams.optionalText(params, "workspaceId", 100);
        if (workspaceId != null) {
            workspaceId = RpcParams.identifier(workspaceId, "ws_", 100);
        }
        String requestedWorkspaceId = workspaceId;
        String mcpId = RpcParams.identifier(params, "mcpId", "mcp_", 100);
        String cursor = RpcParams.optionalText(params, "cursor", 512);
        return withinFrame(RpcParams.pageLimit(params), limit -> {
            CursorPage<McpToolDescriptor> page = session.catalog().readMcpTools(
                    requestedWorkspaceId, mcpId, cursor, limit);
            ObjectNode result = session.mapper().createObjectNode();
            ArrayNode values = result.putArray("items");
            page.items().forEach(value -> values.addObject().put("name", value.name())
                    .put("description", value.description()).set("inputSchema", schema(value.inputSchemaJson())));
            RpcResults.cursor(result, page.nextCursor());
            return result;
        });
    }

    /** 重读同一游标并递减查询页大小，保持真实下一页边界；完整 catalog item 无法放入单帧时明确失败。 */
    private ObjectNode withinFrame(int requestedLimit, IntFunction<ObjectNode> readPage) {
        for (int limit = requestedLimit; ; limit = Math.max(1, limit / 2)) {
            ObjectNode result = readPage.apply(limit);
            if (serializedBytes(result) <= RESPONSE_FRAME_BUDGET) {
                return result;
            }
            if (limit == 1) {
                throw JaRpcException.of(JaErrorCatalog.FRAME_TOO_LARGE,
                        "one catalog item exceeds the RPC frame");
            }
        }
    }

    /** 使用与 StdioWriter 相同的 JSON 编码估算响应体，并预留 envelope 与通知元数据空间。 */
    private int serializedBytes(ObjectNode result) {
        try {
            return session.mapper().writeValueAsBytes(result).length;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("catalog response could not be serialized", failure);
        }
    }

    /**
     * 复用唯一脱敏 MCP 健康投影，避免 list 与 test 返回字段漂移。
     */
    private ObjectNode mcpView(McpServerDescriptor value) {
        return session.mapper().createObjectNode().put("mcpId", value.mcpId())
                .put("name", value.name()).put("scope", value.scope()).put("transport", value.transport())
                .put("status", value.status()).put("toolCount", value.toolCount());
    }

    /**
     * 在 Wire 边界解析规范 Schema 文本，避免 Jackson 类型进入 catalog domain。
     */
    private com.fasterxml.jackson.databind.JsonNode schema(String value) {
        try {
            return session.mapper().readTree(value);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("MCP tool schema is invalid", failure);
        }
    }
}
