// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcResults;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.domain.WorkspaceFailure;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 严格映射工作区 Wire DTO，并把全部业务决策交给唯一 WorkspaceUseCase。
 */
public final class WorkspaceHandler implements RpcHandler {
    private final RpcSession session;

    /**
     * 只绑定连接会话，不保留目录、信任或注册状态。
     */
    public WorkspaceHandler(RpcSession session) {
        this.session = session;
    }

    /**
     * 返回冻结的五个工作区方法，不暴露旧 activation alias。
     */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.WORKSPACE_OPEN, RpcMethod.WORKSPACE_OPEN_GENERAL,
                RpcMethod.WORKSPACE_LIST, RpcMethod.WORKSPACE_SET_TRUST,
                RpcMethod.WORKSPACE_UNREGISTER);
    }

    /**
     * ready 校验后分派明确命令，并把领域失败映射为脱敏 RPC 错误。
     */
    @Override
    public CompletionStage<ObjectNode> handle(RpcCommand command) {
        session.requireReady();
        try {
            return CompletableFuture.completedFuture(switch (command.method()) {
                case WORKSPACE_OPEN -> open(command.params());
                case WORKSPACE_OPEN_GENERAL -> general(command.params());
                case WORKSPACE_LIST -> list(command.params());
                case WORKSPACE_SET_TRUST -> trust(command.params());
                case WORKSPACE_UNREGISTER -> unregister(command.params());
                default -> throw JaRpcException.methodNotFound();
            });
        } catch (WorkspaceFailure failure) {
            throw mapFailure(failure);
        }
    }

    /**
     * 通用工作区不接受客户端路径或身份，Java 用例直接返回唯一权威投影。
     */
    private ObjectNode general(ObjectNode params) {
        RpcParams.requireExact(params);
        return RpcResults.workspace(session.mapper(),
                session.workspaces().openGeneralWorkspace());
    }

    /**
     * 只解析严格 Wire 字段，目录真实性、stable ID、容量与预热均由应用用例负责。
     */
    private ObjectNode open(ObjectNode params) {
        RpcParams.requireOnly(params, "cwd", "displayName");
        Path root = path(RpcParams.text(params, "cwd", 4_096, false));
        String displayName = RpcParams.optionalText(params, "displayName", 1_024);
        Workspace value = session.workspaces().openWorkspace(
                new WorkspaceUseCase.OpenWorkspace(root, displayName));
        return RpcResults.workspace(session.mapper(), value);
    }

    /**
     * 返回统一 items/nextCursor 页面，不保留 workspace 专用列表字段。
     */
    private ObjectNode list(ObjectNode params) {
        RpcParams.requireOnly(params, "cursor", "limit");
        CursorPage<Workspace> page = session.workspaces()
                .listWorkspaces(RpcParams.optionalText(params, "cursor", 512), RpcParams.pageLimit(params));
        ObjectNode result = session.mapper().createObjectNode();
        ArrayNode values = result.putArray("items");
        page.items().forEach(value -> values.add(RpcResults.workspace(session.mapper(), value)));
        RpcResults.cursor(result, page.nextCursor());
        return result;
    }

    /**
     * 将 Wire 信任值映射到领域闭集，持久化和配置同步顺序由应用用例保证。
     */
    private ObjectNode trust(ObjectNode params) {
        RpcParams.requireExact(params, "workspaceId", "trust");
        String workspaceId = RpcParams.identifier(params, "workspaceId", "ws_", 100);
        String trust = RpcParams.text(params, "trust", 32, false);
        session.workspaces().setWorkspaceTrust(workspaceId, trust(trust));
        return session.mapper().createObjectNode().put("accepted", true);
    }

    /**
     * 只传递 revision CAS，应用用例负责元数据与进程目录绑定的原子顺序。
     */
    private ObjectNode unregister(ObjectNode params) {
        RpcParams.requireExact(params, "workspaceId", "expectedRevision");
        String workspaceId = RpcParams.identifier(params, "workspaceId", "ws_", 100);
        session.workspaces().unregisterWorkspace(workspaceId,
                RpcParams.revision(params, "expectedRevision"));
        return session.mapper().createObjectNode().put("accepted", true);
    }

    /**
     * 将 Wire 信任值映射到领域闭集，任何未知值都按参数错误拒绝。
     */
    private static Workspace.Trust trust(String value) {
        return switch (value) {
            case "trusted" -> Workspace.Trust.TRUSTED;
            case "untrusted" -> Workspace.Trust.UNTRUSTED;
            default -> throw JaRpcException.invalidParams();
        };
    }

    /**
     * 将有界 cwd 转为 Path；平台语法错误仍属于严格参数错误而不是服务端故障。
     */
    private static Path path(String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException invalid) {
            throw JaRpcException.invalidParams();
        }
    }

    /**
     * 统一把 workspace 失败闭集映射到冻结 JA-RPC catalog，且不复制底层消息或路径。
     */
    static JaRpcException mapFailure(WorkspaceFailure failure) {
        JaErrorCatalog error = switch (failure.code()) {
            case DIRECTORY_UNAVAILABLE, WORKSPACE_NOT_OPEN -> JaErrorCatalog.WORKSPACE_NOT_FOUND;
            case DIRECTORY_CONFINEMENT, IDENTITY_CONFLICT -> JaErrorCatalog.WORKSPACE_CONFINEMENT;
            case GENERAL_WORKSPACE_UNAVAILABLE -> JaErrorCatalog.STORAGE_UNAVAILABLE;
            case CAPACITY_EXHAUSTED -> JaErrorCatalog.QUEUE_FULL;
            case TRUST_CONFLICT -> JaErrorCatalog.WORKSPACE_TRUST_REQUIRED;
        };
        return JaRpcException.of(error, "workspace operation failed");
    }
}
