// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.attachment.domain.AttachmentFailure;
import io.github.kongweiguang.ja.attachment.port.in.AttachmentPreviewUseCase;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 把 DRAFT/BOUND 授权映射为短期附件预览 session；物理路径、hash 与 blob identity 永不进入 transport。
 */
public final class AttachmentPreviewHandler implements RpcHandler {
    private final Runnable readiness;
    private final Consumer<String> workspaceAdmission;
    private final Supplier<AttachmentPreviewUseCase> previews;
    private final ObjectMapper mapper;

    /** 从连接 owner 提取最窄能力，保证 Handler 不持有 repository 或文件读取能力。 */
    public AttachmentPreviewHandler(RpcSession session) {
        RpcSession required = java.util.Objects.requireNonNull(session, "session");
        readiness = required::requireReady;
        workspaceAdmission = workspaceId -> required.workspaces().requireOpenWorkspace(workspaceId);
        previews = required::attachmentPreviews;
        mapper = required.mapper();
    }

    /** 只发布三条预览生命周期方法，不能借 session 扩张为任意附件读取接口。 */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.ATTACHMENT_PREVIEW_OPEN, RpcMethod.ATTACHMENT_PREVIEW_READ,
                RpcMethod.ATTACHMENT_PREVIEW_CLOSE);
    }

    /** 严格解析封闭字段并把领域失败统一收窄为脱敏 JA-RPC 错误。 */
    @Override
    public CompletionStage<ObjectNode> handle(RpcCommand command) {
        readiness.run();
        try {
            return CompletableFuture.completedFuture(switch (command.method()) {
                case ATTACHMENT_PREVIEW_OPEN -> open(command.params());
                case ATTACHMENT_PREVIEW_READ -> read(command.params());
                case ATTACHMENT_PREVIEW_CLOSE -> close(command.params());
                default -> throw JaRpcException.methodNotFound();
            });
        } catch (AttachmentFailure failure) {
            throw mapFailure(failure);
        } catch (IllegalArgumentException failure) {
            throw JaRpcException.invalidParams();
        }
    }

    /** DRAFT 先验证当前 Workspace；Thread 分支覆盖该会话的排队和已绑定消息附件。 */
    private ObjectNode open(ObjectNode params) {
        RpcParams.requireExact(params, "attachmentId", "authorization");
        String attachmentId = RpcParams.identifier(params, "attachmentId", "att_", 128);
        ObjectNode authorization = RpcParams.object(params, "authorization");
        String kind = RpcParams.text(authorization, "kind", 16, false);
        AttachmentPreviewUseCase.Authorization access;
        if ("draft".equals(kind)) {
            RpcParams.requireExact(authorization, "kind", "workspaceId");
            String workspaceId = RpcParams.identifier(authorization, "workspaceId", "ws_", 100);
            workspaceAdmission.accept(workspaceId);
            access = new AttachmentPreviewUseCase.DraftAuthorization(workspaceId);
        } else if ("thread".equals(kind)) {
            RpcParams.requireExact(authorization, "kind", "threadId");
            access = new AttachmentPreviewUseCase.ThreadAuthorization(
                    RpcParams.identifier(authorization, "threadId", "thr_", 100));
        } else {
            throw JaRpcException.invalidParams();
        }
        AttachmentPreviewUseCase.PreviewDescriptor descriptor = previews.get().openPreview(
                new AttachmentPreviewUseCase.PreviewOpenRequest(attachmentId, access));
        return mapper.createObjectNode().put("previewSessionId", descriptor.previewSessionId())
                .put("attachmentId", descriptor.attachmentId()).put("displayName", descriptor.displayName())
                .put("sizeBytes", descriptor.sizeBytes())
                .put("mediaKind", descriptor.mediaKind().name().toLowerCase(Locale.ROOT))
                .put("mediaType", descriptor.mediaType())
                .put("previewKind", descriptor.previewKind().name().toLowerCase(Locale.ROOT));
    }

    /** 每次读取都重新验证 session 与窗口，offset 只能单调推进且单段最多 64 KiB。 */
    private ObjectNode read(ObjectNode params) {
        RpcParams.requireExact(params, "previewSessionId", "offsetBytes", "limitBytes");
        String sessionId = previewSessionId(params);
        long offsetBytes = RpcParams.wholeNumber(params, "offsetBytes");
        long limit = RpcParams.wholeNumber(params, "limitBytes");
        if (limit < 4 || limit > 64 * 1024) throw JaRpcException.invalidParams();
        AttachmentPreviewUseCase.PreviewReadResult result = previews.get().readPreview(
                new AttachmentPreviewUseCase.PreviewReadRequest(sessionId, offsetBytes, (int) limit));
        return mapper.createObjectNode().put("previewSessionId", result.previewSessionId())
                .put("offsetBytes", result.offsetBytes()).put("nextOffsetBytes", result.nextOffsetBytes())
                .put("contentBase64", result.contentBase64()).put("eof", result.eof())
                .put("truncated", result.truncated());
    }

    /** close 对已关闭 identity 保持幂等，响应只确认本次命令完成，不泄露历史 session 状态。 */
    private ObjectNode close(ObjectNode params) {
        RpcParams.requireExact(params, "previewSessionId");
        String sessionId = previewSessionId(params);
        previews.get().closePreview(sessionId);
        return mapper.createObjectNode().put("previewSessionId", sessionId).put("closed", true);
    }

    /** preview identity 使用独立前缀和固定随机长度，拒绝把 attachment/thread identity 混作 session。 */
    private static String previewSessionId(ObjectNode params) {
        String value = RpcParams.text(params, "previewSessionId", 36, false);
        if (!value.matches("apv_[0-9a-f]{32}")) throw JaRpcException.invalidParams();
        return value;
    }

    /** 失败投影沿用附件错误闭集，不回显底层状态、文件内容或 session 存在性。 */
    private static JaRpcException mapFailure(AttachmentFailure failure) {
        JaErrorCatalog error = switch (failure.code()) {
            case INVALID_REQUEST -> JaErrorCatalog.INVALID_PARAMS;
            case NOT_FOUND -> JaErrorCatalog.ATTACHMENT_NOT_FOUND;
            case CONFLICT -> JaErrorCatalog.ATTACHMENT_CONFLICT;
            case TOO_LARGE -> JaErrorCatalog.ATTACHMENT_LIMIT_EXCEEDED;
            case CONTENT_UNAVAILABLE, CONTENT_CHANGED, CONTENT_CORRUPT, IO ->
                    JaErrorCatalog.ATTACHMENT_UNAVAILABLE;
        };
        return JaRpcException.of(error, "attachment preview operation failed");
    }
}
