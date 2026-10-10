// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.model.ClientOperationReceipt;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.review.application.api.ReviewUseCase;
import io.github.kongweiguang.ja.review.application.api.ReviewFailure;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.OperationFingerprint;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 将只读快照审查映射到 Review 用例，并保持通知、Turn admission 与持久回执使用同一身份。
 */
public final class ReviewHandler implements RpcHandler {
    private final RpcSession session;

    /**
     * Handler 只绑定连接会话；审查输入、运行状态和报告均由应用用例及 Turn owner 持有。
     */
    public ReviewHandler(RpcSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /**
     * 暴露固定 Review RPC 子集，避免本 Handler 代替 Thread 或 Turn 接口扩展控制权限。
     */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.REVIEW_START, RpcMethod.REVIEW_READ);
    }

    /**
     * 执行 Review 命令并只把应用层的稳定失败类别映射为 JA-RPC 错误目录；原始 cause 仅保留在进程内诊断。
     */
    @Override
    public CompletionStage<ObjectNode> handle(RpcCommand command) {
        session.requireReady();
        try {
            return switch (command.method()) {
                case REVIEW_START -> CompletableFuture.completedFuture(start(command.params()));
                case REVIEW_READ -> CompletableFuture.completedFuture(read(command.params()));
                default -> throw JaRpcException.methodNotFound();
            };
        } catch (ReviewFailure failure) {
            throw map(failure);
        }
    }

    /**
     * 先重放 turn/start 持久回执，再登记通知身份并准入普通 Turn；revision 不会在重试中被静默刷新。
     */
    private ObjectNode start(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "expectedThreadRevision", "operationKey",
                "snapshotAttachmentId");
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 100);
        String snapshotAttachmentId = RpcParams.identifier(params, "snapshotAttachmentId", "att_", 100);
        long expectedRevision = RpcParams.revision(params, "expectedThreadRevision");
        String operationKey = operationKey(params);
        String fingerprint = OperationFingerprint.sha256(session.mapper(), params);
        ObjectNode committed = replay(threadId, operationKey, fingerprint);
        if (committed != null) {
            return committed;
        }

        ThreadSnapshot snapshot = session.threads().readThread(threadId, null, 1)
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.THREAD_NOT_FOUND, "thread is unavailable"));
        ThreadSummary thread = snapshot.thread();
        if (thread.revision() != expectedRevision || thread.status() != ThreadSummary.Status.ACTIVE) {
            throw JaRpcException.of(JaErrorCatalog.CONFLICT, "thread revision or state changed");
        }
        String turnId = "turn_" + UUID.randomUUID().toString().replace("-", "");
        session.registerTurnNotificationContext(turnId, thread.workspaceId(), threadId, expectedRevision);
        try {
            ReviewUseCase.Accepted accepted = session.reviews().start(
                    new ReviewUseCase.StartRequest(threadId, turnId, expectedRevision, snapshotAttachmentId),
                    session.eventSink(), operationKey, fingerprint);
            accepted.completion().whenComplete((ignored, failure) -> session.abandonTurnNotification(turnId));
            return startResult(accepted.threadId(), accepted.turnId(), accepted.threadRevision(), accepted.queued());
        } catch (RuntimeException failure) {
            session.abandonTurnNotification(turnId);
            throw failure;
        }
    }

    /**
     * 已提交重试必须同时匹配规范请求指纹和所属 Thread，才返回原始 Turn。
     */
    private ObjectNode replay(String threadId, String operationKey, String fingerprint) {
        Optional<ClientOperationReceipt> receipt = session.turns().readClientOperation(operationKey);
        if (receipt.isEmpty()) {
            return null;
        }
        ClientOperationReceipt committed = receipt.orElseThrow();
        if (!committed.matches("turn/start", fingerprint) || !committed.threadId().equals(threadId)) {
            throw JaRpcException.of(JaErrorCatalog.CONFLICT, "client operation identity changed");
        }
        return startResult(committed.threadId(), committed.turnId(), committed.threadRevision(), committed.queued());
    }

    /**
     * 显式投影 Review 状态与可选冻结报告，未完成时返回 null 而不伪造空报告。
     */
    private ObjectNode read(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "turnId");
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 100);
        String turnId = RpcParams.identifier(params, "turnId", "turn_", 100);
        ReviewUseCase.ReadResult value = session.reviews().read(new ReviewUseCase.ReadRequest(threadId, turnId));
        ObjectNode result = session.mapper().createObjectNode().put("threadId", value.threadId())
                .put("turnId", value.turnId()).put("status", value.status());
        if (value.report() == null) {
            result.putNull("report");
        } else {
            result.set("report", session.mapper().valueToTree(value.report()));
        }
        return result;
    }

    /**
     * 将共享的 turn/start 回执收窄为 Review 受理合同，不把受理状态当作完成。
     */
    private ObjectNode startResult(String threadId, String turnId, long revision, boolean queued) {
        return session.mapper().createObjectNode().put("threadId", threadId).put("turnId", turnId)
                .put("threadRevision", revision).put("queued", queued);
    }

    /**
     * 将稳定失败分类映射为脱敏 RPC 错误并保留进程内 cause，报告读取失败不返回部分或过期数据。
     */
    private static JaRpcException map(ReviewFailure failure) {
        JaErrorCatalog error = switch (failure.failure()) {
            case THREAD_NOT_FOUND -> JaErrorCatalog.THREAD_NOT_FOUND;
            case TURN_NOT_FOUND -> JaErrorCatalog.TURN_NOT_FOUND;
            case TURN_UNAVAILABLE -> JaErrorCatalog.INVALID_STATE;
            case WORKSPACE_UNAVAILABLE -> JaErrorCatalog.WORKSPACE_NOT_FOUND;
            case CONFLICT -> JaErrorCatalog.CONFLICT;
            case REPORT_UNAVAILABLE -> JaErrorCatalog.STORAGE_UNAVAILABLE;
        };
        return JaRpcException.of(error, "review operation failed", failure);
    }

    /**
     * 只接受固定格式的 turn/start 操作身份，使 Review 别名复用相同的幂等边界。
     */
    private static String operationKey(ObjectNode params) {
        String value = RpcParams.text(params, "operationKey", 35, false);
        if (!value.matches("op_[0-9a-f]{32}")) {
            throw JaRpcException.invalidParams();
        }
        return value;
    }
}
