// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ClientOperationReceipt;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.TurnSummary;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.application.api.InternalTurnStartRequest;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.api.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.plan.application.api.PlanUseCase;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepositoryException;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanStatus;
import io.github.kongweiguang.ja.transport.rpc.protocol.OperationFingerprint;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcResults;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
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
     * 只有两个用户可选词能进入持久化；文件核实是服务端内部结论，客户端永远不能提交 verified。
     */
    private static TurnUseCase.ToolRecoveryDisposition toolRecoveryDecision(String value) {
        return switch (value) {
            case "retry" -> TurnUseCase.ToolRecoveryDisposition.RETRY;
            case "skip" -> TurnUseCase.ToolRecoveryDisposition.SKIP;
            default -> throw JaRpcException.invalidParams();
        };
    }

    /**
     * 首轮和队列共享稳定内容错误目录；cause 仅供进程内诊断，不参与 Wire 投影。
     */
    private static JaRpcException contentFailure(TurnUseCase.ContentFailure failure, Throwable cause) {
        return switch (failure) {
            case WORKSPACE_REFERENCE_INVALID -> JaRpcException.of(
                    JaErrorCatalog.WORKSPACE_REFERENCE_INVALID, "workspace reference is invalid", cause);
            case SKILL_UNAVAILABLE -> JaRpcException.of(
                    JaErrorCatalog.SKILL_UNAVAILABLE, "skill is unavailable", cause);
            case SKILL_LOAD_FAILED -> JaRpcException.of(
                    JaErrorCatalog.SKILL_LOAD_FAILED, "skill could not be loaded", cause);
            case CONTENT_TOO_LARGE -> JaRpcException.of(
                    JaErrorCatalog.CONTENT_TOO_LARGE, "user content is too large", cause);
        };
    }

    /**
     * input revision 从一开始就是正整数，零值不能冒充未初始化 CAS。
     */
    private static long inputRevision(ObjectNode params) {
        long revision = RpcParams.revision(params, "expectedInputRevision");
        if (revision < 1) {
            throw JaRpcException.invalidParams();
        }
        return revision;
    }

    /**
     * 同步准入与异步 CAS 失败共享映射，已提交业务错误不退化成不可辨识的内部错误。
     */
    private static RuntimeException approvalFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof io.github.kongweiguang.ja.conversation.application.api.ApprovalUseCase.ApprovalException approval) {
            io.github.kongweiguang.ja.conversation.application.api.ApprovalUseCase.Failure reason = approval.failure();
            JaErrorCatalog error = switch (reason) {
                case NOT_FOUND -> JaErrorCatalog.APPROVAL_NOT_FOUND;
                case EXPIRED -> JaErrorCatalog.APPROVAL_EXPIRED;
                case ALREADY_RESOLVED -> JaErrorCatalog.APPROVAL_ALREADY_RESOLVED;
                case CONFLICT -> JaErrorCatalog.CONFLICT;
                case UNAVAILABLE -> JaErrorCatalog.STORAGE_UNAVAILABLE;
            };
            String message = switch (reason) {
                case NOT_FOUND -> "approval is unavailable";
                case EXPIRED -> "approval request expired";
                case ALREADY_RESOLVED -> "approval was already resolved";
                case CONFLICT -> "approval state changed; refresh before responding";
                case UNAVAILABLE -> "approval state could not be loaded";
            };
            return JaRpcException.of(error, message);
        }
        if (cause instanceof io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.StoreConflictException) {
            return JaRpcException.of(JaErrorCatalog.CONFLICT, "approval revision changed");
        }
        return cause instanceof RuntimeException runtime ? runtime : new java.util.concurrent.CompletionException(cause);
    }

    /**
     * 四个有副作用入口共用唯一规范身份，拒绝任意字符串充当重试键。
     */
    private static String operationKey(ObjectNode params) {
        String value = RpcParams.text(params, "operationKey", 35, false);
        if (!value.matches("op_[0-9a-f]{32}")) {
            throw JaRpcException.invalidParams();
        }
        return value;
    }

    /**
     * Recovery 与其他 Turn 准入共用字段名，但其公开合同接受更短的可重试键；只允许 ASCII 标识字符。
     */
    private static String recoveryOperationKey(ObjectNode params) {
        String value = RpcParams.text(params, "operationKey", 128, false);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw JaRpcException.invalidParams();
        }
        return value;
    }

    /**
     * 首轮、队列与 Task 共用唯一结构化内容解析器，避免局部长度或判别闭集再次漂移。
     */
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

    /**
     * 仅暴露启动、恢复、取消和审批响应，审批请求只能由服务端通知发起。
     */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.OPERATION_READ, RpcMethod.TURN_START, RpcMethod.TURN_CONTINUE,
                RpcMethod.TURN_REASK,
                RpcMethod.TURN_RESUME, RpcMethod.TURN_RECOVERY_RESPOND, RpcMethod.TURN_CANCEL,
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
            case OPERATION_READ -> CompletableFuture.completedFuture(readOperation(command.params()));
            case TURN_START -> CompletableFuture.completedFuture(start(command.params()));
            case TURN_CONTINUE -> CompletableFuture.completedFuture(continueQuestion(command.params()));
            case TURN_REASK -> CompletableFuture.completedFuture(reask(command.params()));
            case TURN_RESUME -> CompletableFuture.completedFuture(resume(command.params()));
            case TURN_RECOVERY_RESPOND -> CompletableFuture.completedFuture(respondToolRecovery(command.params()));
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
     * 从权威 Turn/Thread 投影建立通知上下文后恢复原 Turn；Plan 恢复 ACK 使用实际 Thread revision，
     * 且 binding 异常保持为可操作的稳定 recovery 错误。
     * @author kongweiguang
     */
    private ObjectNode resume(ObjectNode params) {
        RpcParams.requireExact(params, "turnId", "expectedThreadRevision");
        String turnId = RpcParams.identifier(params, "turnId", "turn_", 101);
        long expected = RpcParams.revision(params, "expectedThreadRevision");
        TurnSummary turn = session.threads().findTurn(turnId)
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.TURN_NOT_RESUMABLE,
                        "turn is not resumable"));
        ThreadSnapshot snapshot = session.threads().readThread(turn.threadId(), null, 1)
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.TURN_NOT_RESUMABLE,
                        "turn is not resumable"));
        Optional<PlanUseCase.PlanTurnContext> planContext = planTurnControlContext(turn.threadId(), turnId);
        if (planContext.isPresent()) {
            PlanUseCase.PlanTurnContext plan = planContext.orElseThrow();
            TurnState planTurnState;
            try {
                planTurnState = TurnState.valueOf(turn.status().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException failure) {
                throw JaRpcException.of(JaErrorCatalog.PLAN_RECOVERY_REQUIRED,
                        "Plan Turn status requires recovery", failure);
            }
            if (planTurnState.terminal()) {
                throw JaRpcException.of(JaErrorCatalog.TURN_NOT_RESUMABLE, "turn is not resumable");
            }
            if (plan.status() == PlanStatus.COMPLETED || plan.status() == PlanStatus.STOPPED) {
                throw JaRpcException.of(JaErrorCatalog.TURN_NOT_RESUMABLE, "turn is not resumable");
            }
            if (turn.threadRevision() != expected || snapshot.thread().revision() != expected) {
                throw JaRpcException.of(JaErrorCatalog.TURN_RESUME_ORDER_CONFLICT,
                        "turn resume order changed");
            }
            Workspace workspace = requireOpenWorkspace(snapshot.thread().workspaceId());
            int planModelRound = snapshot.turns().stream()
                    .filter(value -> value.turnId().equals(turnId))
                    .mapToInt(ThreadSnapshot.Turn::modelRound)
                    .findFirst()
                    .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.TURN_NOT_RESUMABLE,
                            "turn is not resumable"));
            if (plan.status() == PlanStatus.PAUSED) {
                PlanUseCase.PlanControl control = new PlanUseCase.PlanControl(plan.planId(), plan.planRevision(),
                        plan.runId(), planTurnOperationKey("resume", turnId, plan.planRevision(), null),
                        session.clock().instant());
                session.plans().resumePlan(control, session.planExecutionEvents());
                return turnResumeResult(turnId, resumedPlanThreadRevision(turn.threadId(), turnId));
            }
            if (plan.status() == PlanStatus.EXECUTING || plan.status() == PlanStatus.VERIFYING) {
                session.restoreTurnNotificationContext(turnId, workspace.workspaceId(), turn.threadId(),
                        expected, turn.turnMutationVersion(), planModelRound);
                return turnResumeResult(turnId, expected);
            }
            throw JaRpcException.of(JaErrorCatalog.TURN_NOT_RESUMABLE, "turn is not resumable");
        }
        if (turn.threadRevision() != expected || snapshot.thread().revision() != expected) {
            throw JaRpcException.of(JaErrorCatalog.TURN_RESUME_ORDER_CONFLICT,
                    "turn resume order changed");
        }
        Workspace workspace = requireOpenWorkspace(snapshot.thread().workspaceId());
        int modelRound = snapshot.turns().stream()
                .filter(value -> value.turnId().equals(turnId))
                .mapToInt(ThreadSnapshot.Turn::modelRound)
                .findFirst()
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.TURN_NOT_RESUMABLE,
                        "turn is not resumable"));
        session.restoreTurnNotificationContext(turnId, workspace.workspaceId(), turn.threadId(), expected,
                turn.turnMutationVersion(), modelRound);
        try {
            TurnUseCase.Accepted accepted = session.turns().resume(turnId, expected,
                    taskAwareEventSink(turn.threadId()));
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

    /** 恢复入口和审批裁决共用 Task 投影装饰器；非 Task Thread 由装饰器原样透传，且不读取任务详情。
     * @author kongweiguang
     */
    private ConversationTurnEventSink taskAwareEventSink(String threadId) {
        return session.tasks().projectContinuationEvents(threadId, session.eventSink());
    }

    /**
     * 将原 Tool 详情里的用户选择作为一次有版本和幂等键保护的裁决提交。新裁决若已消除最后一项未知，
     * 立即复用既有 Resume 编排；仍有下一项未知时返回已提交事实，不把正常等待伪装为 RPC 失败。
     * @author kongweiguang
     */
    private ObjectNode respondToolRecovery(ObjectNode params) {
        RpcParams.requireExact(params, "turnId", "callId", "expectedThreadRevision",
                "expectedRecoveryRevision", "decision", "operationKey");
        String turnId = RpcParams.identifier(params, "turnId", "turn_", 101);
        String callId = RpcParams.toolCallId(params, "callId");
        long expectedThreadRevision = RpcParams.revision(params, "expectedThreadRevision");
        long expectedRecoveryRevision = RpcParams.revision(params, "expectedRecoveryRevision");
        if (expectedRecoveryRevision < 1) {
            throw JaRpcException.invalidParams();
        }
        TurnUseCase.ToolRecoveryDisposition decision = toolRecoveryDecision(
                RpcParams.text(params, "decision", 16, false));
        String operationKey = recoveryOperationKey(params);
        TurnUseCase.ToolRecoveryResponse response = session.turns().respondToolRecovery(
                new TurnUseCase.ToolRecoveryRequest(turnId, callId, expectedThreadRevision,
                        expectedRecoveryRevision, decision, operationKey));
        boolean resumed = false;
        if (response.changed()) {
            ObjectNode resumeParams = session.mapper().createObjectNode()
                    .put("turnId", response.turnId())
                    .put("expectedThreadRevision", response.threadRevision());
            try {
                resume(resumeParams);
                resumed = true;
            } catch (TurnUseCase.TurnResumeException pending) {
                if (pending.failure() != TurnUseCase.ResumeFailure.RECOVERY_REQUIRED) {
                    throw pending;
                }
            }
        }
        return session.mapper().createObjectNode().put("accepted", true)
                .put("turnId", response.turnId()).put("threadRevision", response.threadRevision())
                .put("decision", decision.name().toLowerCase(Locale.ROOT)).put("resumed", resumed);
    }

    /**
     * 只映射 Wire 输入为启动意图；内容失败的内部 cause 保留，公开响应仍只含固定目录信息。
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    private ObjectNode start(ObjectNode params) {
        RpcParams.requireOnly(params, "threadId", "content", "operationKey", "runtimeOverrides");
        RpcTurnRuntimeOverrides overrides = RpcTurnRuntimeOverrides.parse(params, session::jsonValueCodec);
        String operationKey = operationKey(params);
        String fingerprint = OperationFingerprint.sha256(session.mapper(), params);
        ObjectNode committed = replayIfCommitted("turn/start", operationKey, fingerprint);
        if (committed != null) {
            return committed;
        }
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 100);
        try {
            return startCurrent(threadId, content(params.get("content")),
                    operationKey, fingerprint, overrides);
        } catch (TurnUseCase.ContentValidationException failure) {
            throw contentFailure(failure.failure(), failure);
        }
    }

    /**
     * 继续不携带用户正文；Java 从最后失败问题读取完整原历史并关联隐藏 Turn。
     */
    private ObjectNode continueQuestion(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "expectedThreadRevision", "operationKey");
        String operationKey = operationKey(params);
        String fingerprint = OperationFingerprint.sha256(session.mapper(), params);
        ObjectNode committed = replayIfCommitted("turn/continue", operationKey, fingerprint);
        if (committed != null) {
            return committed;
        }
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 100);
        long expectedRevision = RpcParams.revision(params, "expectedThreadRevision");
        RecoveryContext recovery = prepareRecoveryContext(threadId, expectedRevision);
        Workspace workspace = recovery.workspace();
        ThreadPreferences preferences = recovery.preferences();
        String turnId = recovery.turnId();
        try {
            InternalTurnStartRequest request = new InternalTurnStartRequest(threadId, turnId,
                    workspace.workspaceId(), workspace.root(), preferences.providerId(), preferences.modelId(),
                    preferences.reasoningLevel(), preferences.accessMode(), preferences.collaborationMode(),
                    expectedRevision, 0, session.clock().instant(), TurnOrigin.USER_CONTINUATION,
                    null, null);
            TurnUseCase.Accepted accepted = session.turns().continueQuestion(request, session.eventSink(),
                    operationKey, fingerprint);
            bindNotificationCleanup(turnId, accepted.completion());
            return acceptedResult(accepted);
        } catch (RuntimeException failure) {
            session.abandonTurnNotification(turnId);
            throw failure;
        }
    }

    /**
     * 编辑只接受当前 revision 与最后源消息身份；内部失败 cause 保留，路径与正文不进入 Wire 诊断。
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    private ObjectNode reask(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "expectedThreadRevision", "sourceMessageId", "content",
                "operationKey");
        String operationKey = operationKey(params);
        String fingerprint = OperationFingerprint.sha256(session.mapper(), params);
        ObjectNode committed = replayIfCommitted("turn/reask", operationKey, fingerprint);
        if (committed != null) {
            return committed;
        }
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 100);
        long expectedRevision = RpcParams.revision(params, "expectedThreadRevision");
        String sourceMessageId = RpcParams.identifier(params, "sourceMessageId", "item_", 128);
        UserContent userContent = content(params.get("content"));
        RecoveryContext recovery = prepareRecoveryContext(threadId, expectedRevision);
        Workspace workspace = recovery.workspace();
        ThreadPreferences preferences = recovery.preferences();
        String turnId = recovery.turnId();
        try {
            TurnStartRequest request = new TurnStartRequest(threadId, turnId, workspace.workspaceId(),
                    workspace.root(), userContent, preferences.providerId(), preferences.modelId(),
                    preferences.reasoningLevel(), preferences.accessMode(), preferences.collaborationMode(),
                    expectedRevision, 0, session.clock().instant(), Optional.empty(),
                    Optional.empty(), Optional.empty(), false);
            TurnUseCase.Accepted accepted = session.turns().reask(request, sourceMessageId, session.eventSink(),
                    operationKey, fingerprint);
            bindNotificationCleanup(turnId, accepted.completion());
            return acceptedResult(accepted);
        } catch (TurnUseCase.ContentValidationException failure) {
            session.abandonTurnNotification(turnId);
            throw contentFailure(failure.failure(), failure);
        } catch (RuntimeException failure) {
            session.abandonTurnNotification(turnId);
            throw failure;
        }
    }

    /**
     * 为继续或重答登记同一套冻结 Thread/Workspace 身份，确保通知上下文先于异步 Turn 准入。
     */
    private RecoveryContext prepareRecoveryContext(String threadId, long expectedRevision) {
        ThreadSnapshot snapshot = requireThreadAtRevision(threadId, expectedRevision);
        Workspace workspace = requireOpenWorkspace(snapshot.thread().workspaceId());
        String turnId = "turn_" + UUID.randomUUID().toString().replace("-", "");
        session.registerTurnNotificationContext(turnId, workspace.workspaceId(), threadId, expectedRevision);
        return new RecoveryContext(turnId, workspace, snapshot.thread().preferences());
    }

    /**
     * recovery 操作使用客户端确认的 Thread revision，过期请求不得自动重读并改写另一段历史。
     */
    private ThreadSnapshot requireThreadAtRevision(String threadId, long expectedRevision) {
        ThreadSnapshot snapshot = session.threads().readThread(threadId, null, 1)
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.THREAD_NOT_FOUND, "thread is unavailable"));
        if (snapshot.thread().revision() != expectedRevision) {
            throw JaRpcException.of(JaErrorCatalog.CONFLICT, "thread revision changed");
        }
        return snapshot;
    }

    /**
     * 两个恢复方法沿用 turn/start 的同一 admission receipt 字段。
     */
    private ObjectNode acceptedResult(TurnUseCase.Accepted accepted) {
        return session.mapper().createObjectNode().put("accepted", true)
                .put("queued", accepted.queued()).put("turnId", accepted.turnId())
                .put("threadRevision", accepted.threadRevision());
    }

    /**
     * turn/start 没有客户端 revision，因此每次尝试都从权威 Thread 冻结下一轮偏好；自动标题、
     * 人工标题或偏好更新若恰在读后提交，只对 Storage 或 AgentStore 的 admission CAS 冲突重读一次，既避免
     * 自动标题让下一轮随机失败，也不对 Provider、Tool 或已接纳 Turn 做不安全重放。
     */
    private ObjectNode startCurrent(String threadId, UserContent content,
                                    String operationKey, String fingerprint, RpcTurnRuntimeOverrides overrides) {
        RuntimeException firstConflict = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            ThreadSnapshot snapshot = session.threads()
                    .readThread(threadId, null, 1)
                    .orElseThrow(() -> JaRpcException.of(
                            JaErrorCatalog.THREAD_NOT_FOUND, "thread is unavailable"));
            Workspace workspace = requireOpenWorkspace(snapshot.thread().workspaceId());
            ThreadPreferences preferences = overrides.select(snapshot.thread().preferences(),
                    workspace.root(), session.configurationUseCase());
            String turnId = "turn_" + UUID.randomUUID().toString().replace("-", "");
            try {
                session.registerTurnNotificationContext(turnId, workspace.workspaceId(), threadId,
                        snapshot.thread().revision());
                TurnStartRequest request = new TurnStartRequest(threadId, turnId, workspace.workspaceId(),
                        workspace.root(), content,
                        preferences.providerId(), preferences.modelId(),
                        preferences.reasoningLevel(), preferences.accessMode(), preferences.collaborationMode(),
                        snapshot.thread().revision(), 0, session.clock().instant(), Optional.empty(),
                        overrides.outputSchema(), Optional.empty(), overrides.overridesPreferences());
                TurnUseCase.Accepted accepted = session.turns().start(request, session.eventSink(),
                        operationKey, fingerprint);
                bindNotificationCleanup(turnId, accepted.completion());
                return session.mapper().createObjectNode()
                        .put("accepted", true)
                        .put("queued", accepted.queued())
                        .put("turnId", accepted.turnId())
                        .put("threadRevision", accepted.threadRevision());
            } catch (StorageException | AgentStore.StoreConflictException conflict) {
                session.abandonTurnNotification(turnId);
                if (attempt > 0 || conflict instanceof StorageException storageConflict
                                   && storageConflict.code() != StorageException.Code.CAS_CONFLICT) {
                    throw conflict;
                }
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
            if (failure != null) {
                session.abandonTurnNotification(turnId);
            }
        });
    }

    /**
     * 使用全局唯一 turnId 定位 Turn，并把取消请求直接委托给应用端口。
     */
    private CompletionStage<ObjectNode> cancel(ObjectNode params) {
        RpcParams.requireExact(params, "turnId");
        String turnId = RpcParams.identifier(params, "turnId", "turn_", 101);
        Optional<TurnSummary> turn = session.threads().findTurn(turnId);
        Optional<PlanUseCase.PlanTurnContext> planContext = turn.flatMap(value ->
                planTurnControlContext(value.threadId(), turnId));
        if (planContext.isPresent()) {
            return CompletableFuture.completedFuture(cancelPlanTurn(turnId, planContext.orElseThrow()));
        }
        TurnUseCase.CancelResult cancelled = session.turns().cancel(turnId);
        return CompletableFuture.completedFuture(session.mapper().createObjectNode()
                .put("accepted", cancelled.accepted()).put("turnId", turnId)
                .put("status", cancelled.status().name().toLowerCase(Locale.ROOT))
                .put("threadRevision", cancelled.threadRevision()));
    }

    /**
     * 只有不可逆的 Plan stop 负责结束内部 Turn；终态重放回读持久 Turn，缺失或未终态时失败关闭。
     */
    private ObjectNode cancelPlanTurn(String turnId, PlanUseCase.PlanTurnContext plan) {
        if (plan.status() == PlanStatus.EXECUTING || plan.status() == PlanStatus.VERIFYING
            || plan.status() == PlanStatus.PAUSED) {
            PlanUseCase.PlanControl control = new PlanUseCase.PlanControl(plan.planId(), plan.planRevision(),
                    plan.runId(), planTurnOperationKey("cancel", turnId, null, plan.runId()),
                    session.clock().instant());
            session.plans().stopPlan(control);
            return terminalPlanTurnCancelResult(turnId, true);
        }
        if (plan.status() == PlanStatus.STOPPED || plan.status() == PlanStatus.COMPLETED) {
            return terminalPlanTurnCancelResult(turnId, false);
        }
        throw new IllegalStateException("Persisted Plan Turn cannot be cancelled from its current state");
    }

    /**
     * 将持久 Plan binding 损坏映射为稳定恢复错误；其他存储错误保留原有分类。
     */
    private Optional<PlanUseCase.PlanTurnContext> planTurnControlContext(String ownerThreadId, String turnId) {
        try {
            return session.plans().planTurnControlContext(ownerThreadId, turnId);
        } catch (PlanRepositoryException failure) {
            if (failure.code() == PlanRepositoryException.Code.PLAN_RECOVERY_REQUIRED) {
                throw JaRpcException.of(JaErrorCatalog.PLAN_RECOVERY_REQUIRED,
                        "Plan Turn binding requires recovery", failure);
            }
            throw failure;
        }
    }

    /**
     * 恢复 ACK 的 revision 取自 Plan resume 已安排后的权威 Thread 投影，不按事件数量推导。
     */
    private long resumedPlanThreadRevision(String threadId, String turnId) {
        ThreadSnapshot snapshot = session.threads().readThread(threadId, null, 1)
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.PLAN_RECOVERY_REQUIRED,
                        "resumed Plan Thread is unavailable"));
        TurnSummary resumedTurn = session.threads().findTurn(turnId)
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.PLAN_RECOVERY_REQUIRED,
                        "resumed Plan Turn is unavailable"));
        if (!snapshot.thread().threadId().equals(threadId) || !resumedTurn.threadId().equals(threadId)) {
            throw JaRpcException.of(JaErrorCatalog.PLAN_RECOVERY_REQUIRED,
                    "resumed Plan Turn identity changed");
        }
        return snapshot.thread().revision();
    }

    /**
     * 终态回执从 Thread 持久投影构造，不能把 Plan STOPPED 误当成内部 Turn 已结算。
     */
    private ObjectNode terminalPlanTurnCancelResult(String turnId, boolean accepted) {
        TurnSummary turn = session.threads().findTurn(turnId)
                .orElseThrow(() -> new IllegalStateException("Stopped Plan Turn is unavailable"));
        TurnState state;
        try {
            state = TurnState.valueOf(turn.status().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("Stopped Plan Turn has an invalid status", failure);
        }
        if (!state.terminal()) {
            throw new IllegalStateException("Stopped Plan Turn has not reached a terminal state");
        }
        return session.mapper().createObjectNode().put("accepted", accepted).put("turnId", turnId)
                .put("status", state.name().toLowerCase(Locale.ROOT))
                .put("threadRevision", turn.threadRevision());
    }

    /**
     * Resume ACK 使用同一 RPC 结果字段集，不为 Plan execution 暴露另一种终态或 admission 形态。
     */
    private ObjectNode turnResumeResult(String turnId, long threadRevision) {
        return session.mapper().createObjectNode().put("accepted", true).put("queued", true)
                .put("turnId", turnId).put("threadRevision", threadRevision);
    }

    /**
     * 内部 Plan Turn 控制没有 Wire operationKey；从稳定动作身份生成键，丢失回执的同一重试可被仓储去重。
     */
    private String planTurnOperationKey(String action, String turnId, Long planRevision, String runId) {
        ObjectNode identity = session.mapper().createObjectNode().put("action", action).put("turnId", turnId);
        if (planRevision != null) {
            identity.put("planRevision", planRevision);
        }
        if (runId != null) {
            identity.put("runId", runId);
        }
        return "op_" + OperationFingerprint.sha256(session.mapper(), identity).substring(0, 32);
    }

    /**
     * 按明确的消费种类一次入队，ACK 与事件任意先后都从权威队列收敛。
     */
    private ObjectNode enqueueInput(ObjectNode params) {
        RpcParams.requireExact(params, "turnId", "content", "kind", "operationKey");
        String turnId = RpcParams.identifier(params, "turnId", "turn_", 101);
        String operationKey = operationKey(params);
        String fingerprint = OperationFingerprint.sha256(session.mapper(), params);
        Optional<TurnUseCase.InputOperationReceipt> previous = session.turns().readInputOperation(operationKey);
        if (previous.isPresent()) {
            TurnUseCase.InputOperationReceipt receipt = previous.orElseThrow();
            if (!receipt.fingerprint().equals(fingerprint)) {
                throw JaRpcException.of(JaErrorCatalog.CONFLICT, "client operation identity changed");
            }
            return inputReceiptResult(receipt);
        }
        String kind = RpcParams.text(params, "kind", 16, false);
        TurnUseCase.InputKind inputKind = switch (kind) {
            case "steering" -> TurnUseCase.InputKind.STEERING;
            case "follow_up" -> TurnUseCase.InputKind.FOLLOW_UP;
            default -> throw JaRpcException.invalidParams();
        };
        return inputResult(() -> session.turns().enqueueInput(turnId, content(params.get("content")),
                inputKind, session.eventSink(), operationKey, fingerprint));
    }

    /**
     * “调整方向”按条目 revision 提升，重复点击已提升条目保持幂等。
     */
    private ObjectNode prioritizeInput(ObjectNode params) {
        RpcParams.requireExact(params, "turnId", "inputId", "expectedInputRevision");
        String turnId = RpcParams.identifier(params, "turnId", "turn_", 101);
        String inputId = RpcParams.identifier(params, "inputId", "input_", 128);
        return inputResult(() -> session.turns().prioritizeInput(turnId, inputId,
                inputRevision(params)));
    }

    /**
     * 编辑正文保留原 identity、创建时间和处理顺序。
     */
    private ObjectNode updateInput(ObjectNode params) {
        RpcParams.requireExact(params, "turnId", "inputId", "expectedInputRevision", "content");
        String turnId = RpcParams.identifier(params, "turnId", "turn_", 101);
        String inputId = RpcParams.identifier(params, "inputId", "input_", 128);
        return inputResult(() -> session.turns().updateInput(turnId, inputId,
                inputRevision(params), content(params.get("content"))));
    }

    /**
     * 删除无需确认，但仍通过 item revision 拒绝消费竞态。
     */
    private ObjectNode deleteInput(ObjectNode params) {
        RpcParams.requireExact(params, "turnId", "inputId", "expectedInputRevision");
        String turnId = RpcParams.identifier(params, "turnId", "turn_", 101);
        String inputId = RpcParams.identifier(params, "inputId", "input_", 128);
        return inputResult(() -> session.turns().deleteInput(turnId, inputId,
                inputRevision(params)));
    }

    /**
     * 应用失败保留为进程内 cause；响应只包含稳定目录与公共 mutation 结果。
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
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
                        "turn is unavailable", failure);
                case QUEUE_FULL -> JaRpcException.of(JaErrorCatalog.TURN_INPUT_QUEUE_FULL,
                        "turn input queue is full", failure);
                case CONTENT_TOO_LARGE -> JaRpcException.of(JaErrorCatalog.CONTENT_TOO_LARGE,
                        "user content is too large", failure);
                case INPUT_NOT_FOUND -> JaRpcException.of(JaErrorCatalog.QUEUED_INPUT_NOT_FOUND,
                        "queued input is unavailable", failure);
                case CONFLICT -> JaRpcException.of(JaErrorCatalog.CONFLICT,
                        "queued input revision changed", failure);
                case WORKSPACE_REFERENCE_INVALID -> JaRpcException.of(
                        JaErrorCatalog.WORKSPACE_REFERENCE_INVALID, "workspace reference is invalid", failure);
                case SKILL_UNAVAILABLE -> JaRpcException.of(
                        JaErrorCatalog.SKILL_UNAVAILABLE, "skill is unavailable", failure);
                case SKILL_LOAD_FAILED -> JaRpcException.of(
                        JaErrorCatalog.SKILL_LOAD_FAILED, "skill could not be loaded", failure);
            };
        } catch (TurnUseCase.ContentValidationException failure) {
            throw contentFailure(failure.failure(), failure);
        }
    }

    /**
     * 审批依据持久等待与幂等回执结算，重连后不要求旧连接的内存 pending 仍然存在。
     */
    private CompletionStage<ObjectNode> respond(ObjectNode params) {
        RpcParams.requireExact(params, "approvalId", "turnId", "decision", "expectedThreadRevision",
                "operationKey");
        String operationKey = operationKey(params);
        String fingerprint = OperationFingerprint.sha256(session.mapper(), params);
        ObjectNode committed = replayIfCommitted("approval/respond", operationKey, fingerprint);
        String approvalId = RpcParams.identifier(params, "approvalId", "appr_", 108);
        String turnId = RpcParams.identifier(params, "turnId", "turn_", 101);
        long expected = RpcParams.revision(params, "expectedThreadRevision");
        ApprovalDecision decision = decision(RpcParams.text(params, "decision", 32, false));
        Optional<TurnSummary> savedTurn = session.threads().findTurn(turnId);
        if (committed != null && (savedTurn.isEmpty()
                                  || !"SUSPENDED".equalsIgnoreCase(savedTurn.orElseThrow().status()))) {
            return CompletableFuture.completedFuture(committed);
        }
        TurnSummary turn = savedTurn
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.TURN_NOT_FOUND, "turn is unavailable"));
        if (committed == null && turn.threadRevision() != expected) {
            throw JaRpcException.of(JaErrorCatalog.CONFLICT, "thread revision changed");
        }
        ThreadSnapshot snapshot = session.threads().readThread(turn.threadId(), null, 1)
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.THREAD_NOT_FOUND, "thread is unavailable"));
        session.restoreTurnNotificationContext(turnId, snapshot.thread().workspaceId(), turn.threadId(),
                snapshot.thread().revision(), turn.turnMutationVersion(), snapshot.turns().stream()
                        .filter(value -> value.turnId().equals(turnId)).mapToInt(ThreadSnapshot.Turn::modelRound)
                        .findFirst().orElseThrow(() -> JaRpcException.of(JaErrorCatalog.CONFLICT,
                                "approval turn snapshot unavailable")));
        try {
            return session.approvalUseCase().resolve(turn.threadId(), turnId, approvalId, expected, decision,
                    operationKey, fingerprint, taskAwareEventSink(turn.threadId())).handle((resolution, failure) -> {
                if (failure != null) {
                    throw approvalFailure(failure);
                }
                return session.mapper().createObjectNode().put("accepted", true).put("approvalId", approvalId)
                        .put("turnId", turnId).put("decision", decisionWire(decision))
                        .put("threadRevision", resolution.threadRevision());
            });
        } catch (RuntimeException failure) {
            throw approvalFailure(failure);
        }
    }

    /**
     * 断线后的查询只读持久业务同事务回执；unknown 不能证明未执行，客户端仍须保留
     * 原输入并让用户核实，不能自动生成第二次副作用。
     */
    private ObjectNode readOperation(ObjectNode params) {
        RpcParams.requireOnly(params, "operationKey", "sourceThreadId");
        if (!params.has("operationKey")) {
            throw JaRpcException.invalidParams();
        }
        String operationKey = operationKey(params);
        if (params.has("sourceThreadId")) {
            String sourceThreadId = RpcParams.identifier(params, "sourceThreadId", "thr_", 128);
            Optional<ThreadUseCase.ThreadForkResult> fork = session.threads()
                    .readThreadForkOperation(sourceThreadId, operationKey);
            if (fork.isPresent()) {
                ThreadUseCase.ThreadForkResult receipt = fork.orElseThrow();
                ObjectNode result = session.mapper().createObjectNode().put("status", "committed")
                        .put("method", "thread/fork").put("threadId", receipt.source().threadId());
                ObjectNode payload = result.putObject("result");
                payload.set("thread", RpcResults.thread(session.mapper(), receipt.thread()));
                payload.putObject("source").put("threadId", receipt.source().threadId())
                        .put("itemId", receipt.source().itemId())
                        .put("threadRevision", receipt.source().threadRevision());
                return result;
            }
        }
        ObjectNode ordinary = session.turns().readClientOperation(operationKey)
                .map(receipt -> {
                    ObjectNode result = session.mapper().createObjectNode().put("status", "committed")
                            .put("method", receipt.method()).put("threadId", receipt.threadId());
                    result.set("result", receiptResult(receipt));
                    return result;
                }).orElse(null);
        if (ordinary != null) {
            return ordinary;
        }
        return session.turns().readInputOperation(operationKey).map(receipt -> {
            ObjectNode result = session.mapper().createObjectNode().put("status", "committed")
                    .put("method", "turn/input/enqueue").put("threadId", receipt.threadId());
            result.set("result", inputReceiptResult(receipt));
            return result;
        }).orElseGet(() -> session.mapper().createObjectNode().put("status", "unknown"));
    }

    /**
     * 回放仅返回不可变输入身份和提交时的种类，队列可由后续权威 read 获取。
     */
    private ObjectNode inputReceiptResult(TurnUseCase.InputOperationReceipt receipt) {
        return session.mapper().createObjectNode().put("accepted", true)
                .put("turnId", receipt.turnId()).put("inputId", receipt.inputId())
                .put("kind", receipt.kind() == TurnUseCase.InputKind.STEERING ? "steering" : "follow_up");
    }

    /**
     * 同一 ID 的重发仅能复用原方法与规范化载荷，且必须早于任何 CAS、审批门闩或新 Turn ID。
     */
    private ObjectNode replayIfCommitted(String method, String operationKey, String fingerprint) {
        return session.turns().readClientOperation(operationKey).map(receipt -> {
            if (!receipt.matches(method, fingerprint)) {
                throw JaRpcException.of(JaErrorCatalog.CONFLICT, "client operation identity changed");
            }
            return receiptResult(receipt);
        }).orElse(null);
    }

    /**
     * 从持久回执重建原成功 DTO，不把指纹、数据库行或连接环境投影给客户端。
     */
    private ObjectNode receiptResult(ClientOperationReceipt receipt) {
        ObjectNode result = session.mapper().createObjectNode().put("accepted", true);
        if ("approval/respond".equals(receipt.method())) {
            return result.put("approvalId", receipt.approvalId()).put("turnId", receipt.turnId())
                    .put("decision", receipt.decision()).put("threadRevision", receipt.threadRevision());
        }
        return result.put("queued", receipt.queued()).put("turnId", receipt.turnId())
                .put("threadRevision", receipt.threadRevision());
    }

    /**
     * 冷重启后 Workspace 必须显式打开；沿用既有领域错误映射，让调用方能重开后重试，且不误报内部故障。
     */
    private Workspace requireOpenWorkspace(String workspaceId) {
        try {
            return session.workspaces().requireOpenWorkspace(workspaceId);
        } catch (WorkspaceFailure failure) {
            throw WorkspaceHandler.mapFailure(failure);
        }
    }

    /**
     * 冻结一次恢复准入所需的 Workspace 与模型偏好，避免两个入口自行组合上下文。
     *
     * @param turnId 本次可恢复执行的稳定标识
     * @param workspace 工作区
     * @param preferences 偏好配置
     */
    private record RecoveryContext(String turnId,
                                   Workspace workspace,
                                   ThreadPreferences preferences) {
    }
}
