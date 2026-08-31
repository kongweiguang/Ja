// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.TurnSummary;
import io.github.kongweiguang.ja.conversation.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.conversation.port.in.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.workspace.domain.Workspace;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 处理有界 Turn 接纳、取消与普通审批响应，不承载运行时资源解析。
 */
public final class TurnApprovalHandler implements RpcHandler {
    private final RpcSession session;

    /**
     * 绑定当前 RPC 会话；所需用例通过会话的明确端口取得，避免依赖组合图。
     */
    public TurnApprovalHandler(RpcSession session) {
        this.session = session;
    }

    /**
     * 仅暴露启动、取消和审批响应，审批请求只能由服务端通知发起。
     */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.TURN_START, RpcMethod.TURN_CANCEL, RpcMethod.TURN_STEER,
                RpcMethod.TURN_FOLLOW_UP, RpcMethod.APPROVAL_RESPOND);
    }

    /**
     * 分派异步取消与审批，避免阻塞持有 stdin 读取权的线程。
     */
    @Override
    public CompletionStage<ObjectNode> handle(RpcCommand command) {
        session.requireReady();
        return switch (command.method()) {
            case TURN_START -> CompletableFuture.completedFuture(start(command.params()));
            case TURN_CANCEL -> cancel(command.params());
            case TURN_STEER -> CompletableFuture.completedFuture(queue(command.params(), true));
            case TURN_FOLLOW_UP -> CompletableFuture.completedFuture(queue(command.params(), false));
            case APPROVAL_RESPOND -> respond(command.params());
            default -> throw JaRpcException.methodNotFound();
        };
    }

    /**
     * 只映射 Wire 输入为 transport-free 启动意图；运行时冻结和资源释放由 TurnService 负责。
     */
    private ObjectNode start(ObjectNode params) {
        RpcParams.requireOnly(params, "threadId", "content", "deadlineMs");
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 100);
        Duration deadline = Duration.ofHours(24);
        if (params.has("deadlineMs")) {
            long deadlineMillis = RpcParams.wholeNumber(params, "deadlineMs");
            if (deadlineMillis < 1_000 || deadlineMillis > 86_400_000) {
                throw JaRpcException.invalidParams();
            }
            deadline = Duration.ofMillis(deadlineMillis);
        }
        return startCurrent(threadId, content(params.get("content")), deadline);
    }

    /**
     * turn/start 没有客户端 revision，因此每次尝试都从权威 Thread 冻结下一轮偏好；自动标题、
     * 人工标题或偏好更新若恰在读后提交，只对无副作用的 admission CAS 冲突重读一次，既避免
     * 自动标题让下一轮随机失败，也不对 Provider、Tool 或已接纳 Turn 做不安全重放。
     */
    private ObjectNode startCurrent(String threadId, TurnContent content, Duration deadline) {
        StorageException firstConflict = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            ThreadSnapshot snapshot = session.threads()
                    .readThread(threadId, null, 1)
                    .orElseThrow(() -> JaRpcException.of(
                            JaErrorCatalog.THREAD_NOT_FOUND, "thread is unavailable"));
            var preferences = snapshot.thread().preferences();
            Workspace workspace = session.workspaces().requireOpenWorkspace(snapshot.thread().workspaceId());
            String turnId = "turn_" + UUID.randomUUID().toString().replace("-", "");
            try {
            session.registerTurnNotificationContext(turnId, workspace.workspaceId(), threadId,
                    snapshot.thread().revision());
            TurnStartRequest request = new TurnStartRequest(threadId, turnId, workspace.workspaceId(),
                    workspace.root(), content.text(), content.attachmentIds(),
                    preferences.providerId(), preferences.modelId(),
                    preferences.reasoningLevel(), preferences.accessMode(), deadline,
                    snapshot.thread().revision(), 0, session.clock().instant());
            TurnUseCase.Accepted accepted = session.turns().start(request, session.eventSink());
            bindNotificationCleanup(turnId, accepted.completion());
            return session.mapper().createObjectNode()
                    .put("accepted", true)
                    .put("queued", accepted.queued())
                    .put("turnId", accepted.turnId())
                    .put("threadRevision", accepted.threadRevision());
            } catch (StorageException conflict) {
                session.abandonTurnNotification(turnId);
                if (conflict.code() != StorageException.Code.CAS_CONFLICT || attempt > 0) throw conflict;
                firstConflict = conflict;
            } catch (RuntimeException failure) {
                session.abandonTurnNotification(turnId);
                throw failure;
            }
        }
        throw Objects.requireNonNull(firstConflict, "firstConflict");
    }

    /**
     * 未安全启动的执行不会产生 terminal 事件，因此异常完成必须显式清理通知关联；
     * RuntimeLease 的释放始终留在 TurnService。
     */
    void bindNotificationCleanup(String turnId, CompletionStage<?> completion) {
        Objects.requireNonNull(completion, "completion").whenComplete((ignored, failure) -> {
            if (failure != null) session.abandonTurnNotification(turnId);
        });
    }

    /**
     * 使用全局唯一 turnId 定位 Turn，并把取消请求直接委托给应用端口。
     */
    private CompletionStage<ObjectNode> cancel(ObjectNode params) {
        RpcParams.requireExact(params, "turnId", "expectedThreadRevision");
        String turnId = RpcParams.identifier(params, "turnId", "turn_", 108);
        long expected = RpcParams.revision(params, "expectedThreadRevision");
        TurnUseCase.CancelResult cancelled = session.turns().cancel(turnId, expected);
        return CompletableFuture.completedFuture(session.mapper().createObjectNode()
                .put("accepted", cancelled.accepted()).put("turnId", turnId)
                .put("status", cancelled.status().name().toLowerCase(Locale.ROOT))
                .put("threadRevision", cancelled.threadRevision()));
    }

    /** 追加单条持久输入并立即返回排队身份；消费时机由 Agent Loop 决定。 */
    private ObjectNode queue(ObjectNode params, boolean steering) {
        RpcParams.requireExact(params, "turnId", "text");
        String turnId = RpcParams.identifier(params, "turnId", "turn_", 108);
        String text = RpcParams.text(params, "text", 4_000_000, false);
        TurnUseCase.QueuedInput queued = steering
                ? session.turns().steer(turnId, text)
                : session.turns().followUp(turnId, text);
        return session.mapper().createObjectNode().put("accepted", true)
                .put("inputId", queued.inputId()).put("turnId", queued.turnId())
                .put("kind", queued.kind()).put("status", "queued");
    }

    /**
     * 只解决一次普通审批请求，并仅等待已提交审批事件的写出确认。
     */
    private CompletionStage<ObjectNode> respond(ObjectNode params) {
        RpcParams.requireExact(params, "approvalId", "turnId", "decision", "expectedThreadRevision");
        String approvalId = RpcParams.identifier(params, "approvalId", "appr_", 108);
        String turnId = RpcParams.identifier(params, "turnId", "turn_", 108);
        long expected = RpcParams.revision(params, "expectedThreadRevision");
        /*
         * 在 begin() 改变响应门闩前解析封闭的决策词汇，使畸形输入不改变状态；
         * 关联被占用后的所有失败则统一由 catch 释放门闩。
         */
        ApprovalDecision decision = decision(RpcParams.text(params, "decision", 32, false));
        ApprovalCompletions.Pending pending = session.approvals().begin(approvalId, turnId);
        try {
            TurnSummary turn = session.threads().findTurn(turnId)
                    .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.TURN_NOT_FOUND,
                            "turn is unavailable"));
            if (!turn.threadId().equals(pending.threadId()) || turn.threadRevision() != expected) {
                throw JaRpcException.of(JaErrorCatalog.CONFLICT, "thread revision changed");
            }
            if (!session.approvalUseCase().resolve(approvalId, decision, session.clock().instant())) {
                throw JaRpcException.of(JaErrorCatalog.APPROVAL_ALREADY_RESOLVED,
                        "approval could not be resolved");
            }
            return pending.completion().thenApply(resolution -> session.mapper().createObjectNode()
                    .put("accepted", true).put("approvalId", approvalId).put("turnId", turnId)
                    .put("decision", decisionWire(decision)).put("threadRevision", resolution.threadRevision()));
        } catch (RuntimeException failure) {
            session.approvals().rejected(pending);
            throw failure;
        }
    }

    /**
     * 解析严格判别联合：文本按出现顺序拼接，附件仅保留 opaque ID 且每轮最多十个。
     */
    private static TurnContent content(JsonNode value) {
        if (!(value instanceof ArrayNode array) || array.isEmpty() || array.size() > 64) {
            throw JaRpcException.invalidParams();
        }
        StringBuilder text = new StringBuilder();
        java.util.List<String> attachmentIds = new java.util.ArrayList<>();
        java.util.Set<String> uniqueAttachmentIds = new java.util.HashSet<>();
        for (JsonNode item : array) {
            if (!(item instanceof ObjectNode object)) throw JaRpcException.invalidParams();
            String type = RpcParams.text(object, "type", 16, false);
            if ("text".equals(type)) {
                RpcParams.requireExact(object, "type", "text");
                String part = RpcParams.text(object, "text", 4_000_000, false);
                if (!text.isEmpty()) text.append('\n');
                if ((long) text.length() + part.length() > 4_000_000L) {
                    throw JaRpcException.invalidParams();
                }
                text.append(part);
            } else if ("attachment".equals(type)) {
                RpcParams.requireExact(object, "type", "attachmentId");
                String attachmentId = RpcParams.identifier(object, "attachmentId", "att_", 128);
                if (attachmentIds.size() >= 10 || !uniqueAttachmentIds.add(attachmentId)) {
                    throw JaRpcException.invalidParams();
                }
                attachmentIds.add(attachmentId);
            } else {
                throw JaRpcException.invalidParams();
            }
        }
        if (text.isEmpty() && attachmentIds.isEmpty()) throw JaRpcException.invalidParams();
        return new TurnContent(text.toString(), java.util.List.copyOf(attachmentIds));
    }

    /** Handler 内部结构只承接已验证的聚合结果，不进入领域或 Wire DTO。 */
    private record TurnContent(String text, java.util.List<String> attachmentIds) {
        /** 防止后续重构绕过 parser 构造可变附件集合。 */
        private TurnContent {
            text = Objects.requireNonNull(text, "text");
            attachmentIds = java.util.List.copyOf(attachmentIds);
        }
    }

    /**
     * 将三种普通审批决策映射为 Kernel Broker 词汇。
     */
    private static ApprovalDecision decision(String value) {
        return switch (value) {
            case "approve" -> ApprovalDecision.APPROVE;
            case "deny" -> ApprovalDecision.DENY;
            default -> throw JaRpcException.invalidParams();
        };
    }

    /**
     * 返回公开契约规定的准确 snake_case 审批词汇。
     */
    private static String decisionWire(ApprovalDecision value) {
        return switch (value) {
            case APPROVE -> "approve";
            case DENY -> "deny";
        };
    }
}
