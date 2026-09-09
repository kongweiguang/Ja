// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.application.service.TurnService;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.conversation.port.in.InternalTurnStartRequest;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanSnapshot;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanStatus;
import io.github.kongweiguang.ja.goal.port.in.PlanExecutionEventSink;
import io.github.kongweiguang.ja.goal.port.out.GoalRepository;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** 将一次 standalone Plan execution 接入唯一 TurnService，执行上下文不写 USER message 历史。 */
public final class PlanExecutionTurnAdapter implements PlanExecutionCoordinator.PlanExecutionTurnPort {
    private final TurnService turns;
    private final ConversationRepository conversations;
    private final WorkspaceUseCase workspaces;
    private final GoalRepository plans;
    private final ObjectMapper json;
    private final Clock clock;

    /** adapter 复用现有 Thread、Workspace 与连接路由 owner，不保存第二份 Plan 状态。 */
    public PlanExecutionTurnAdapter(TurnService turns, ConversationRepository conversations,
                                    WorkspaceUseCase workspaces, GoalRepository plans,
                                    ObjectMapper json, Clock clock) {
        this.turns = Objects.requireNonNull(turns, "turns");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** admission 前重读 Plan/Thread；过期 revision/run 的迟到启动直接失败关闭。 */
    @Override public CompletionStage<Void> start(PlanExecutionCoordinator.ExecutionRequest request,
                                                 PlanExecutionEventSink events) {
        PlanSnapshot plan = plans.readPlanSnapshot(request.planId());
        if (plan.plan().status() != PlanStatus.EXECUTING
                || !Objects.equals(plan.plan().activePlanRevisionId(), request.planRevisionId())
                || !Objects.equals(plan.plan().activeRunId(), request.runId())) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        ConversationRepository.ThreadSnapshot thread = conversations.readThread(request.ownerThreadId())
                .orElseThrow(() -> new IllegalStateException("Plan owner Thread is unavailable"));
        Workspace workspace = workspaces.requireOpenWorkspace(thread.workspaceId());
        InternalTurnStartRequest command = InternalTurnRequests.create(
                thread, workspace, request.turnId(), clock.instant(), TurnOrigin.PLAN_EXECUTION);
        boolean registered = events.registerTurn(request.turnId(), workspace.workspaceId(),
                thread.threadId(), thread.revision());
        try {
            var accepted = turns.startContinuation(command, hiddenContext(plan),
                    registered ? events::publish : io.github.kongweiguang.ja.conversation.port.in.TurnEventSink.noop());
            return accepted.completion().whenComplete((ignored, failure) -> {
                if (registered) events.abandonTurn(request.turnId());
            }).thenApply(ignored -> null);
        } catch (RuntimeException failure) {
            if (registered) events.abandonTurn(request.turnId());
            // start 的异步契约必须把同步 admission 异常也归一为失败 stage，调用方才能统一结算 Plan run。
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
    }

    /** 隐藏上下文仅携带冻结 Plan 与 Run identity，不复制对话历史，避免旧消息改变已批准执行语义。 */
    private String hiddenContext(PlanSnapshot snapshot) {
        try {
            ObjectNode root = json.createObjectNode();
            root.put("kind", "PLAN_EXECUTION");
            root.put("planId", snapshot.plan().planId());
            root.put("planRevisionId", snapshot.plan().activePlanRevisionId());
            root.put("planHash", snapshot.currentRevision().planHash());
            root.put("runId", snapshot.plan().activeRunId());
            root.set("plan", json.readTree(snapshot.currentRevision().canonicalJson()));
            return json.writeValueAsString(root);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Plan execution context cannot be encoded", failure);
        }
    }
}
