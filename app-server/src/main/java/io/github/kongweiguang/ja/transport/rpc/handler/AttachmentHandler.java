// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.attachment.domain.AttachmentFailure;
import io.github.kongweiguang.ja.attachment.domain.AttachmentMetadata;
import io.github.kongweiguang.ja.attachment.port.in.AttachmentUseCase;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcResults;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 把 Rust 私有 staging identity 收敛为 Java 受管附件，不允许物理路径进入 JA-RPC。
 */
public final class AttachmentHandler implements RpcHandler {
    private final Runnable readiness;
    private final Consumer<String> workspaceAdmission;
    private final Supplier<AttachmentUseCase> attachments;
    private final Supplier<java.time.Instant> now;
    private final Function<AttachmentMetadata, ObjectNode> projection;

    /**
     * 从连接 owner 提取附件处理所需的五个窄能力；Handler 不再保存可变 RpcSession 服务定位器，
     * 同时仍在每次请求时解析当前初始化代际的端口。
     */
    public AttachmentHandler(RpcSession session) {
        RpcSession required = java.util.Objects.requireNonNull(session, "session");
        readiness = required::requireReady;
        workspaceAdmission = workspaceId -> required.workspaces().requireOpenWorkspace(workspaceId);
        attachments = required::attachments;
        now = () -> required.clock().instant();
        projection = value -> RpcResults.attachment(required.mapper(), value);
    }

    /** 只公开导入和丢弃两个生命周期动作，读取能力只能由受控 Tool 使用。 */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.ATTACHMENT_IMPORT, RpcMethod.ATTACHMENT_DISCARD);
    }

    /** 严格解析封闭字段集，并把领域失败映射为不泄露路径或内容的稳定错误。 */
    @Override
    public CompletionStage<ObjectNode> handle(RpcCommand command) {
        readiness.run();
        try {
            return CompletableFuture.completedFuture(switch (command.method()) {
                case ATTACHMENT_IMPORT -> importDraft(command.params());
                case ATTACHMENT_DISCARD -> discard(command.params());
                default -> throw JaRpcException.methodNotFound();
            });
        } catch (AttachmentFailure failure) {
            throw mapFailure(failure);
        } catch (IllegalArgumentException failure) {
            throw JaRpcException.invalidParams();
        }
    }

    /** 导入只接受 Rust 生成的 opaque token 与内容事实，不接受路径、时间或状态覆盖。 */
    private ObjectNode importDraft(ObjectNode params) {
        RpcParams.requireExact(params, "ingressToken", "workspaceId", "displayName", "sizeBytes", "sha256");
        String workspaceId = RpcParams.identifier(params, "workspaceId", "ws_", 100);
        workspaceAdmission.accept(workspaceId);
        String ingressToken = RpcParams.text(params, "ingressToken", 32, false);
        String sha256 = RpcParams.text(params, "sha256", 64, false);
        if (!ingressToken.matches("[0-9a-f]{32}") || !sha256.matches("[0-9a-f]{64}")) {
            throw JaRpcException.invalidParams();
        }
        long sizeBytes = RpcParams.wholeNumber(params, "sizeBytes");
        AttachmentMetadata imported = attachments.get().importDraft(new AttachmentUseCase.ImportRequest(
                ingressToken, workspaceId, RpcParams.text(params, "displayName", 512, false),
                sizeBytes, sha256, now.get()));
        return projection.apply(imported);
    }

    /** 丢弃只按 opaque attachmentId 定位草稿，绑定和终态冲突由附件 owner 原子裁决。 */
    private ObjectNode discard(ObjectNode params) {
        RpcParams.requireExact(params, "attachmentId");
        AttachmentMetadata discarded = attachments.get().discard(
                RpcParams.identifier(params, "attachmentId", "att_", 128), now.get());
        return projection.apply(discarded);
    }

    /** 将附件领域闭集映射到固定 JA-RPC 目录，不公开底层异常或 staging 状态。 */
    private static JaRpcException mapFailure(AttachmentFailure failure) {
        JaErrorCatalog error = switch (failure.code()) {
            case INVALID_REQUEST -> JaErrorCatalog.INVALID_PARAMS;
            case TOO_LARGE -> JaErrorCatalog.ATTACHMENT_LIMIT_EXCEEDED;
            case NOT_FOUND -> JaErrorCatalog.ATTACHMENT_NOT_FOUND;
            case CONFLICT -> JaErrorCatalog.ATTACHMENT_CONFLICT;
            case CONTENT_UNAVAILABLE, CONTENT_CHANGED, CONTENT_CORRUPT, IO ->
                    JaErrorCatalog.ATTACHMENT_UNAVAILABLE;
        };
        return JaRpcException.of(error, "attachment operation failed");
    }
}
