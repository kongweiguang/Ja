// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.infrastructure.acl;

import io.github.kongweiguang.ja.conversation.application.api.InternalTurnStartRequest;
import io.github.kongweiguang.ja.conversation.application.api.NativeExecutionContext;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.TurnResult;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.plan.application.gateway.PlanTurnPort;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * 将唯一 Conversation TurnService 压缩为 Plan 所需的生命周期端口，不在 Plan 中保存第二执行状态。
 */
public final class PlanTurnAcl implements PlanTurnPort {
    private final TurnUseCase turns;

    /** 组合根注入唯一 TurnService owner。 */
    public PlanTurnAcl(TurnUseCase turns) {
        this.turns = Objects.requireNonNull(turns, "turns");
    }

    /** 将权威 TurnResult 原样交给 Plan adapter，由其分别处理执行推进和停止确认。 */
    @Override
    public void registerResumeContinuation(String turnId, Consumer<CompletionStage<TurnResult>> continuation) {
        Objects.requireNonNull(continuation, "continuation");
        turns.registerResumeContinuation(turnId, continuation);
    }

    /** 清理 Conversation 中尚未消费的 continuation。 */
    @Override
    public void clearResumeContinuation(String turnId) {
        turns.clearResumeContinuation(turnId);
    }

    /** 通过 Conversation 的唯一隐藏 Turn 准入入口开始执行。 */
    @Override
    public PlanTurnPort.Accepted startContinuation(PlanTurnPort.StartRequest request, String instruction,
                                                  ConversationTurnEventSink sink,
                                                  NativeExecutionSnapshot nativeContext) {
        InternalTurnStartRequest command = new InternalTurnStartRequest(request.threadId(), request.turnId(),
                request.workspaceId(), request.workspaceRoot(), request.providerId(), request.modelId(),
                request.reasoningLevel(), AccessMode.valueOf(request.accessMode()),
                CollaborationMode.valueOf(request.collaborationMode()), request.expectedThreadRevision(), 0,
                request.requestedAt(), TurnOrigin.PLAN_EXECUTION, request.planRevisionId(), request.runId());
        TurnUseCase.Accepted accepted = turns.startContinuation(command, instruction, sink, nativeContext);
        return new PlanTurnPort.Accepted(accepted.completion());
    }

    /** 暂停操作保持由 Conversation owner 持有。 */
    @Override
    public CompletionStage<Void> suspendPlanRun(String turnId) {
        return turns.suspendPlanRun(turnId);
    }

    /** 恢复操作保持由 Conversation owner 持有。 */
    @Override
    public PlanTurnPort.Accepted resume(String turnId, long expectedThreadRevision,
                                       ConversationTurnEventSink sink,
                                       NativeExecutionSnapshot nativeContext) {
        TurnUseCase.Accepted accepted = turns.resume(turnId, expectedThreadRevision, sink, nativeContext);
        return new PlanTurnPort.Accepted(accepted.completion());
    }

    /** 取消操作保持由 Conversation owner 持有，并传递其 terminal completion。 */
    @Override
    public CompletionStage<TurnResult> cancel(String turnId) {
        return turns.cancel(turnId).completion();
    }

    /** 只返回精确 Plan/run 绑定的原生快照，避免恢复路径读取当前线程环境。 */
    @Override
    public NativeExecutionSnapshot runContext(String planId, String runId) {
        return NativeExecutionContext.shared().findPlanRun(planId, runId).orElse(null);
    }
}
