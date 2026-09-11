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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.TurnSummary;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
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
 * 处理有界 Turn 接纳、恢复、取消与普通审批响应，不承载运行时资源解析。
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
     * 仅暴露启动、恢复、取消和审批响应，审批请求只能由服务端通知发起。
     */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.TURN_START, RpcMethod.TURN_RESUME, RpcMethod.TURN_CANCEL,
                RpcMethod.TURN_INPUT_ENQUEUE, RpcMethod.TURN_INPUT_PRIORITIZE,
                RpcMethod.TURN_INPUT_UPDATE, RpcMethod.TURN_INPUT_DELETE, RpcMethod.APPROVAL_RESPOND);
    }

    /**
     * 分派异步取消与审批，避免阻塞持有 stdin 读取权的线程。
     */
    @Override
    public CompletionStage<ObjectNode> handle(RpcCommand command) {
        session.requireReady();
        return switch (command.method()) {
            case TURN_START -> CompletableFuture.completedFuture(start(command.params()));
            case TURN_RESUME -> CompletableFuture.completedFuture(resume(command.params()));
            case TURN_CANCEL -> cancel(command.params());
            case TURN_INPUT_ENQUEUE -> CompletableFuture.completedFuture(enqueueInput(command.params()));
            case TURN_INPUT_PRIORITIZE -> CompletableFuture.completedFuture(prioritizeInput(command.params()));
            case TURN_INPUT_UPDATE -> CompletableFuture.completedFuture(updateInput(command.params()));
            case TURN_INPUT_DELETE -> CompletableFuture.completedFuture(deleteInput(command.params()));
            case APPROVAL_RESPOND -> respond(command.params());
            default -> throw JaRpcException.methodNotFound();
        };
    }

    /**
     * 从权威 Turn/Thread 投影建立通知上下文后恢复原 Turn；恢复失败时立即撤销临时关联，
     * 防止不存在、顺序冲突或指纹不匹配的请求污染当前 RPC 代际。
     */
    private ObjectNode resume(ObjectNode params) {
        RpcParams.requireExact(params, "turnId", "expectedThreadRevision");
        String turnId = RpcParams.identifier(params, "turnId", "turn_", 108);
        long expected = RpcParams.revision(params, "expectedThreadRevision");
        TurnSummary turn = session.threads().findTurn(turnId)
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.TURN_NOT_RESUMABLE,
                        "turn is not resumable"));
        ThreadSnapshot snapshot = session.threads().readThread(turn.threadId(), null, 1)
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.TURN_NOT_RESUMABLE,
                        "turn is not resumable"));
        if (turn.threadRevision() != expected || snapshot.thread().revision() != expected) {
            throw JaRpcException.of(JaErrorCatalog.TURN_RESUME_ORDER_CONFLICT,
                    "turn resume order changed");
        }
        Workspace workspace = session.workspaces().requireOpenWorkspace(snapshot.thread().workspaceId());
        session.registerTurnNotificationContext(turnId, workspace.workspaceId(), turn.threadId(), expected);
        try {
            TurnUseCase.Accepted accepted = session.turns().resume(turnId, expected, session.eventSink());
            bindNotificationCleanup(turnId, accepted.completion());
            return session.mapper().createObjectNode()
                    .put("accepted", true)
                    .put("queued", accepted.queued())
                    .put("turnId", accepted.turnId())
                    .put("threadRevision", accepted.threadRevision());
        } catch (RuntimeException failure) {
            session.abandonTurnNotification(turnId);
            throw failure;
        }
    }

    /**
     * 只映射 Wire 输入为 transport-free 启动意图；请求级运行时和资源释放由 TurnService 负责。
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
        try {
            return startCurrent(threadId, content(params.get("content")), deadline);
        } catch (TurnUseCase.ContentValidationException failure) {
            throw contentFailure(failure.failure());
        }
    }

    /**
     * turn/start 没有客户端 revision，因此每次尝试都从权威 Thread 冻结下一轮偏好；自动标题、
     * 人工标题或偏好更新若恰在读后提交，只对无副作用的 admission CAS 冲突重读一次，既避免
     * 自动标题让下一轮随机失败，也不对 Provider、Tool 或已接纳 Turn 做不安全重放。
     */
    private ObjectNode startCurrent(String threadId, UserContent content, Duration deadline) {
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
                    workspace.root(), content,
                    preferences.providerId(), preferences.modelId(),
                    preferences.reasoningLevel(), preferences.accessMode(), preferences.collaborationMode(), deadline,
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
        TurnUseCase.CancelResult cancelled = cancelCurrent(turnId, expected);
        return CompletableFuture.completedFuture(session.mapper().createObjectNode()
                .put("accepted", cancelled.accepted()).put("turnId", turnId)
                .put("status", cancelled.status().name().toLowerCase(Locale.ROOT))
                .put("threadRevision", cancelled.threadRevision()));
    }

    /**
     * approval 回执后 Operation 会立即持久化一次内部游标，因此只吸收同一非终态 Turn 恰好一版且
     * 尚未登记 cancel intent 的竞态；这是单次内部 cursor race，不放宽通用 cancellation CAS。
     */
    private TurnUseCase.CancelResult cancelCurrent(String turnId, long expectedThreadRevision) {
        try {
            return session.turns().cancel(turnId, expectedThreadRevision);
        } catch (TurnUseCase.TurnCancellationException conflict) {
            if (conflict.failure() != TurnUseCase.CancelFailure.CONFLICT
                    || expectedThreadRevision == Long.MAX_VALUE) {
                throw conflict;
            }
            TurnSummary current = session.threads().findTurn(turnId).orElse(null);
            if (current == null || !current.turnId().equals(turnId) || current.cancellationRequested()
                    || TurnState.valueOf(current.status().toUpperCase(Locale.ROOT)).terminal()
                    || current.threadRevision() != expectedThreadRevision + 1) {
                throw conflict;
            }
            return session.turns().cancel(turnId, current.threadRevision());
        }
    }

    /** 默认入队为普通 FOLLOW_UP；返回全量投影让 ACK 与事件任意先后都可收敛。 */
    private ObjectNode enqueueInput(ObjectNode params) {
        RpcParams.requireExact(params, "turnId", "content");
        String turnId = RpcParams.identifier(params, "turnId", "turn_", 108);
        return inputResult(() -> session.turns().enqueueInput(turnId, content(params.get("content")), session.eventSink()));
    }

    /** “调整方向”按条目 revision 提升，重复点击已提升条目保持幂等。 */
    private ObjectNode prioritizeInput(ObjectNode params) {
        RpcParams.requireExact(params, "turnId", "inputId", "expectedInputRevision");
        String turnId = RpcParams.identifier(params, "turnId", "turn_", 108);
        String inputId = RpcParams.identifier(params, "inputId", "input_", 128);
        return inputResult(() -> session.turns().prioritizeInput(turnId, inputId,
                inputRevision(params)));
    }

    /** 编辑正文保留原 identity、创建时间和处理顺序。 */
    private ObjectNode updateInput(ObjectNode params) {
        RpcParams.requireExact(params, "turnId", "inputId", "expectedInputRevision", "content");
        String turnId = RpcParams.identifier(params, "turnId", "turn_", 108);
        String inputId = RpcParams.identifier(params, "inputId", "input_", 128);
        return inputResult(() -> session.turns().updateInput(turnId, inputId,
                inputRevision(params), content(params.get("content"))));
    }

    /** 删除无需确认，但仍通过 item revision 拒绝消费竞态。 */
    private ObjectNode deleteInput(ObjectNode params) {
        RpcParams.requireExact(params, "turnId", "inputId", "expectedInputRevision");
        String turnId = RpcParams.identifier(params, "turnId", "turn_", 108);
        String inputId = RpcParams.identifier(params, "inputId", "input_", 128);
        return inputResult(() -> session.turns().deleteInput(turnId, inputId,
                inputRevision(params)));
    }

    /** 应用异常按稳定目录映射，响应只包含公共 mutation 结果。 */
    private ObjectNode inputResult(java.util.function.Supplier<TurnUseCase.InputMutation> operation) {
        try {
            TurnUseCase.InputMutation result = operation.get();
            ObjectNode response = session.mapper().createObjectNode().put("accepted", result.accepted())
                    .put("inputId", result.inputId());
            response.set("inputQueue", RpcResults.inputQueue(session.mapper(), result.inputQueue()));
            return response;
        } catch (TurnUseCase.InputMutationException failure) {
            throw switch (failure.failure()) {
                case TURN_NOT_FOUND -> JaRpcException.of(JaErrorCatalog.TURN_NOT_FOUND,
                        "turn is unavailable");
                case QUEUE_FULL, CONTENT_TOO_LARGE -> JaRpcException.of(JaErrorCatalog.CONTENT_TOO_LARGE,
                        "user content is too large");
                case INPUT_NOT_FOUND -> JaRpcException.of(JaErrorCatalog.QUEUED_INPUT_NOT_FOUND,
                        "queued input is unavailable");
                case CONFLICT -> JaRpcException.of(JaErrorCatalog.CONFLICT,
                        "queued input revision changed");
                case WORKSPACE_REFERENCE_INVALID -> JaRpcException.of(
                        JaErrorCatalog.WORKSPACE_REFERENCE_INVALID, "workspace reference is invalid");
                case SKILL_UNAVAILABLE -> JaRpcException.of(
                        JaErrorCatalog.SKILL_UNAVAILABLE, "skill is unavailable");
                case SKILL_LOAD_FAILED -> JaRpcException.of(
                        JaErrorCatalog.SKILL_LOAD_FAILED, "skill could not be loaded");
            };
        } catch (TurnUseCase.ContentValidationException failure) {
            throw contentFailure(failure.failure());
        }
    }

    /** 首轮和队列共享稳定内容错误目录，客户端据此保留草稿与 Chip。 */
    private static JaRpcException contentFailure(TurnUseCase.ContentFailure failure) {
        return switch (failure) {
            case WORKSPACE_REFERENCE_INVALID -> JaRpcException.of(
                    JaErrorCatalog.WORKSPACE_REFERENCE_INVALID, "workspace reference is invalid");
            case SKILL_UNAVAILABLE -> JaRpcException.of(
                    JaErrorCatalog.SKILL_UNAVAILABLE, "skill is unavailable");
            case SKILL_LOAD_FAILED -> JaRpcException.of(
                    JaErrorCatalog.SKILL_LOAD_FAILED, "skill could not be loaded");
            case CONTENT_TOO_LARGE -> JaRpcException.of(
                    JaErrorCatalog.CONTENT_TOO_LARGE, "user content is too large");
        };
    }

    /** input revision 从一开始就是正整数，零值不能冒充未初始化 CAS。 */
    private static long inputRevision(ObjectNode params) {
        long revision = RpcParams.revision(params, "expectedInputRevision");
        if (revision < 1) throw JaRpcException.invalidParams();
        return revision;
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

    /** 首轮、队列与 Task 共用唯一结构化内容解析器，避免局部长度或判别闭集再次漂移。 */
    private static UserContent content(JsonNode value) {
        return RpcUserContent.parse(value);
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
