// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcResults;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.catalog.domain.McpServerDescriptor;
import io.github.kongweiguang.ja.catalog.domain.McpToolDescriptor;
import io.github.kongweiguang.ja.catalog.domain.SkillDescriptor;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 严格映射只含描述信息的 Skill 页面与有界 MCP 健康、Tool 目录。
 */
public final class SettingsCatalogHandler implements RpcHandler {
    private final RpcSession session;

    /**
     * 只绑定连接会话，不保留原始配置 JSON、配置代际或凭据查询状态。
     */
    public SettingsCatalogHandler(RpcSession session) {
        this.session = session;
    }

    /**
     * 只暴露冻结的查询与探测方法，不恢复 Java 侧 Profile/MCP 旧 CRUD 入口。
     */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.SKILL_LIST, RpcMethod.MCP_LIST, RpcMethod.MCP_TEST, RpcMethod.MODEL_TEST,
                RpcMethod.MCP_LIST_TOOLS);
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
        if (workspaceId != null) workspaceId = RpcParams.identifier(workspaceId, "ws_", 100);
        CursorPage<SkillDescriptor> page = session.catalog().listSkills(
                workspaceId, RpcParams.optionalText(params, "cursor", 512), RpcParams.pageLimit(params));
        ObjectNode result = session.mapper().createObjectNode();
        ArrayNode values = result.putArray("items");
        page.items().forEach(value -> values.addObject().put("skillId", value.skillId())
                .put("name", value.name()).put("scope", value.scope()).put("enabled", value.enabled())
                .put("status", value.status()).put("description", value.description()));
        RpcResults.cursor(result, page.nextCursor());
        return result;
    }

    /**
     * 映射统一的 items 页面，并禁止 endpoint、参数、环境、Header、认证或 Secret 字段。
     */
    private ObjectNode mcp(ObjectNode params) {
        RpcParams.requireOnly(params, "cursor", "limit");
        CursorPage<McpServerDescriptor> page = session.catalog().listMcp(
                RpcParams.optionalText(params, "cursor", 512), RpcParams.pageLimit(params));
        ObjectNode result = session.mapper().createObjectNode();
        ArrayNode values = result.putArray("items");
        page.items().forEach(value -> values.add(mcpView(value)));
        RpcResults.cursor(result, page.nextCursor());
        return result;
    }

    /**
     * 在同一租约内先确认 MCP 身份再执行有界探测，结果只包含健康与 Tool 数量。
     */
    private CompletionStage<ObjectNode> test(ObjectNode params) {
        RpcParams.requireExact(params, "mcpId");
        String mcpId = RpcParams.identifier(params, "mcpId", "mcp_", 100);
        return session.catalog().testMcp(mcpId).thenApply(this::mcpView);
    }

    /** 模型验证结果只返回脱敏模型名和耗时；Provider 失败统一收敛为稳定可重试错误。 */
    private CompletionStage<ObjectNode> testModel(ObjectNode params) {
        RpcParams.requireExact(params, "providerId", "modelId");
        String providerId = RpcParams.identifier(params, "providerId", "provider_", 108);
        String modelId = RpcParams.identifier(params, "modelId", "model_", 108);
        return session.catalog().testModel(providerId, modelId, session.cancellationToken())
                .handle((result, failure) -> {
                    if (failure != null) {
                        Throwable cause = failure instanceof java.util.concurrent.CompletionException
                                && failure.getCause() != null ? failure.getCause() : failure;
                        if (cause instanceof io.github.kongweiguang.ja.configuration.domain.ConfigurationError error) {
                            throw error;
                        }
                        throw JaRpcException.of(
                                io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog.MODEL_UNAVAILABLE,
                                "model is unavailable");
                    }
                    return session.mapper().createObjectNode()
                            .put("responseModel", result.responseModel())
                            .put("latencyMs", result.latencyMs());
                });
    }

    /**
     * 从显式 MCP 身份读取一页有界 Tool Schema；响应只保留统一列表字段，不回显请求身份。
     */
    private ObjectNode tools(ObjectNode params) {
        RpcParams.requireOnly(params, "mcpId", "cursor", "limit");
        String mcpId = RpcParams.identifier(params, "mcpId", "mcp_", 100);
        CursorPage<McpToolDescriptor> page = session.catalog().readMcpTools(mcpId,
                RpcParams.optionalText(params, "cursor", 512), RpcParams.pageLimit(params));
        ObjectNode result = session.mapper().createObjectNode();
        ArrayNode values = result.putArray("items");
        page.items().forEach(value -> values.addObject().put("name", value.name())
                .put("description", value.description()).set("inputSchema", schema(value.inputSchemaJson())));
        RpcResults.cursor(result, page.nextCursor());
        return result;
    }

    /**
     * 复用唯一脱敏 MCP 健康投影，避免 list 与 test 返回字段漂移。
     */
    private ObjectNode mcpView(McpServerDescriptor value) {
        return session.mapper().createObjectNode().put("mcpId", value.mcpId())
                .put("name", value.name()).put("transport", value.transport())
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
