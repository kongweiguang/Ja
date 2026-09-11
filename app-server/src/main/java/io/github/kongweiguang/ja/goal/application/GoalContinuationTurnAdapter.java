// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.application.service.TurnService;
import io.github.kongweiguang.ja.conversation.application.interaction.InteractionSuspendedException;
import io.github.kongweiguang.ja.conversation.application.interaction.InteractionService;
import io.github.kongweiguang.ja.conversation.application.loop.AgentLoop;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionEvent;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionSnapshot;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.conversation.port.in.InternalTurnStartRequest;
import io.github.kongweiguang.ja.conversation.port.in.TurnEventSink;
import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.port.out.GoalRepository;
import io.github.kongweiguang.ja.task.application.TaskCoordinator;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** 把 Goal continuation 适配到唯一 TurnService，隐藏上下文不进入 USER 消息历史。 */
public final class GoalContinuationTurnAdapter implements GoalContinuationCoordinator.ContinuationTurnPort {
    private final TurnService turns;
    private final ConversationRepository conversations;
    private final WorkspaceUseCase workspaces;
    private final GoalRepository goals;
    private final GoalService goalService;
    private final ObjectMapper json;
    private final Clock clock;
    private final GoalEventRegistry events;
    private final GoalContinuationGate gate;
    private final TaskCoordinator tasks;
    private final InteractionService interactions;

    /** adapter 只组合现有 owner，不保存第二份 Goal 或 Thread 状态。 */
    public GoalContinuationTurnAdapter(TurnService turns, ConversationRepository conversations,
                                       WorkspaceUseCase workspaces, GoalRepository goals,
                                        GoalService goalService,
                                        ObjectMapper json, Clock clock, GoalEventRegistry events,
                                        GoalContinuationGate gate, TaskCoordinator tasks,
                                        InteractionService interactions) {
        this.turns = Objects.requireNonNull(turns, "turns");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.goals = Objects.requireNonNull(goals, "goals");
        this.goalService = Objects.requireNonNull(goalService, "goalService");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
    }

    /** idle 判定委托 TurnService 的持久快照，避免 adapter 复制 FIFO 规则。 */
    @Override public boolean ownerIdle(String threadId) { return turns.ownerIdle(threadId); }

    /** 每次调度重新读取 Thread 偏好和 Workspace，CAS 竞争时不使用旧配置继续。 */
    @Override
    public CompletionStage<Void> start(GoalContinuationCoordinator.ContinuationRequest request) {
        return gate.serialized(request.goalId(), () -> startSerialized(request));
    }

    /** Interaction 恢复仍使用原 Goal lease 与 Turn identity，避免答案后另起一条竞争 continuation。 */
    @Override public void registerResumeContinuation(GoalContinuationCoordinator.ContinuationRequest request,
                                                      Consumer<CompletionStage<?>> continuation) {
        turns.registerResumeContinuation(request.turnId(), continuation);
    }

    /** admission 或终态收口时移除未消费的恢复回调，防止旧 Goal 引用跨 Run 存活。 */
    @Override public void clearResumeContinuation(GoalContinuationCoordinator.ContinuationRequest request) {
        turns.clearResumeContinuation(request.turnId());
    }

    /**
     * gate 内重新读取 Goal 与 Thread 后才 admission；暂停/停止若已提交会在这里失败关闭，若稍后
     * 提交则必须等待本方法登记取消能力后再继续。
     */
    private CompletionStage<Void> startSerialized(GoalContinuationCoordinator.ContinuationRequest request) {
        GoalModels.GoalSnapshot goal = goals.readSnapshot(request.goalId());
        GoalModels.GoalPlanLink link = goal.planLink();
        if (goal.goal().status() != GoalModels.GoalStatus.ACTIVE
                || goal.goal().phase() != GoalModels.GoalPhase.WORKING
                || !Objects.equals(link == null ? null : link.planId(), request.planId())
                || !Objects.equals(link == null ? null : link.planRevisionId(), request.planRevisionId())
                || !Objects.equals(goal.goal().activeRunId(), request.runId())) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        ConversationRepository.ThreadSnapshot thread = conversations.readThread(request.ownerThreadId())
                .orElseThrow(() -> new IllegalStateException("Goal owner Thread is unavailable"));
        Workspace workspace = workspaces.requireOpenWorkspace(thread.workspaceId());
        String turnId = request.turnId();
        InternalTurnStartRequest command = InternalTurnRequests.create(
                thread, workspace, turnId, clock.instant(), TurnOrigin.GOAL_CONTINUATION);
        GoalEventRegistry.RoutedTurn route = events.registerTurn(request.goalId(), turnId,
                workspace.workspaceId(), thread.threadId(), thread.revision());
        AutoCloseable interactionWatch = watchInteraction(request);
        try (route) {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            registerResumeContinuation(request,
                    resumed -> observeResumed(request, route, completion, resumed, true, interactionWatch));
            TurnUseCase.Accepted accepted = turns.startContinuation(
                    command, hiddenContext(goal, request.fencingToken()),
                    tasks.projectContinuationEvents(thread.threadId(),
                            phaseAwareSink(request.goalId(), route.sink())));
            gate.activate(request.goalId(), turnId, request.fencingToken(),
                    () -> cancelCurrent(turnId, thread.threadId()));
            route.retain();
            observeResumed(request, route, completion, accepted.completion(), false, interactionWatch);
            return completion;
        } catch (RuntimeException failure) {
            clearResumeContinuation(request);
            closeInteractionWatch(interactionWatch, failure);
            try {
                route.abandon();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * 统一观察初次 admission 与后续恢复；挂起是可继续的中间态，只有真实终态才释放 Goal lease。
     */
    private void observeResumed(GoalContinuationCoordinator.ContinuationRequest request,
                                GoalEventRegistry.RoutedTurn route, CompletableFuture<Void> completion,
                                CompletionStage<?> observed, boolean registerNext,
                                AutoCloseable interactionWatch) {
        observed.whenComplete((ignored, failure) -> {
            if (isSuspended(failure) && registerNext) {
                registerResumeContinuation(request,
                        resumed -> observeResumed(request, route, completion, resumed, true, interactionWatch));
                return;
            }
            if (isSuspended(failure)) return;
            Throwable terminalFailure = failure;
            try {
                clearResumeContinuation(request);
                route.abandon();
                closeInteractionWatch(interactionWatch, terminalFailure);
            } catch (RuntimeException cleanupFailure) {
                if (terminalFailure == null) terminalFailure = cleanupFailure;
                else terminalFailure.addSuppressed(cleanupFailure);
            }
            if (terminalFailure != null) completion.completeExceptionally(terminalFailure);
            else completion.complete(null);
        });
    }

    /** 监听已提交 Interaction 事件，把 Goal phase 与问答恢复事实保持同一顺序。 */
    private AutoCloseable watchInteraction(GoalContinuationCoordinator.ContinuationRequest continuation) {
        return interactions.subscribe(continuation.ownerThreadId(), event -> {
            InteractionSnapshot snapshot = interactions.read(event.threadId(), event.requestId()).orElse(null);
            if (snapshot == null || snapshot.request().isEmpty()) return;
            io.github.kongweiguang.ja.conversation.domain.interaction.InteractionRequest interaction =
                    snapshot.request().orElseThrow();
            if (!continuation.turnId().equals(interaction.turnId())
                    || !continuation.goalId().equals(interaction.goalId())
                    || (continuation.runId() != null && !continuation.runId().equals(interaction.runId()))) return;
            if (event.kind() == InteractionEvent.Kind.CREATED) {
                goalService.projectContinuationPhase(continuation.goalId(), GoalModels.GoalPhase.WORKING,
                        GoalModels.GoalPhase.WAITING_INPUT, "interaction-created:" + event.requestId(),
                        event.occurredAt());
            } else if (event.kind() == InteractionEvent.Kind.ANSWERED || event.kind() == InteractionEvent.Kind.SUPERSEDED) {
                goalService.projectContinuationPhase(continuation.goalId(), GoalModels.GoalPhase.WAITING_INPUT,
                        GoalModels.GoalPhase.WORKING, "interaction-answered:" + event.requestId(),
                        event.occurredAt());
            }
        });
    }

    /** 关闭当前 continuation 的观察句柄；清理故障附着到主异常，不能覆盖状态结算。 */
    private static void closeInteractionWatch(AutoCloseable watch, Throwable primary) {
        try {
            watch.close();
        } catch (Exception cleanupFailure) {
            if (primary != null) primary.addSuppressed(cleanupFailure);
        }
    }

    /** 重启恢复回调复用 Goal 的阶段投影，不用过期事件订阅推断用户是否已回答。 */
    public void projectRecoveryPhase(GoalContinuationCoordinator.ContinuationRequest request, boolean waiting) {
        goalService.projectContinuationPhase(request.goalId(),
                waiting ? GoalModels.GoalPhase.WORKING : GoalModels.GoalPhase.WAITING_INPUT,
                waiting ? GoalModels.GoalPhase.WAITING_INPUT : GoalModels.GoalPhase.WORKING,
                "recovery-phase:" + request.turnId() + ":" + request.fencingToken() + ":" + waiting + ":" + clock.instant(),
                clock.instant());
    }

    /** 当前 Turn 仍保留持久 cursor 时，不能让 Goal coordinator 把等待态当成失败终态。 */
    private static boolean isSuspended(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof InteractionSuspendedException
                    || current instanceof AgentLoop.InputNeedsAttentionException
                    || current instanceof TurnService.PlanSuspendedException) return true;
            current = current.getCause();
        }
        return false;
    }

    /**
     * 只有已提交的 Turn 审批事件能改变 Goal phase；Interaction 由独立持久事件观察器投影，确保
     * 状态行不会在审批或提问卡片已经可操作时仍显示“执行中”。
     */
    private TurnEventSink phaseAwareSink(String goalId, TurnEventSink delegate) {
        return event -> {
            if (event instanceof io.github.kongweiguang.ja.conversation.port.in.TurnEvent.ApprovalRequested value) {
                goalService.projectContinuationPhase(goalId, GoalModels.GoalPhase.WORKING,
                        GoalModels.GoalPhase.WAITING_APPROVAL, value.context().eventId(),
                        value.context().occurredAt());
            } else if (event instanceof io.github.kongweiguang.ja.conversation.port.in.TurnEvent.ApprovalResolved value) {
                goalService.projectContinuationPhase(goalId, GoalModels.GoalPhase.WAITING_APPROVAL,
                        GoalModels.GoalPhase.WORKING, value.context().eventId(), value.context().occurredAt());
            }
            return delegate.publish(event);
        };
    }

    /** coordinator 确认 SQLite lease 已终态后再开放恢复，避免 Turn completion 与释放之间的窗口。 */
    @Override
    public void settled(GoalContinuationCoordinator.ContinuationRequest request) {
        gate.complete(request.goalId(), request.turnId(), request.fencingToken());
    }

    /**
     * 取消前每次读取最新 Thread revision；并发持久事件导致 CAS 冲突时允许一次重新对账，已终态
     * 或已离开活动表的 Turn 视为幂等完成。
     */
    private void cancelCurrent(String turnId, String threadId) {
        for (int attempt = 0; attempt < 2; attempt++) {
            ConversationRepository.ThreadSnapshot snapshot = conversations.readThread(threadId)
                    .orElseThrow(() -> new IllegalStateException("Goal owner Thread is unavailable"));
            boolean active = snapshot.turns().stream()
                    .anyMatch(turn -> turn.turnId().equals(turnId) && !turn.state().terminal());
            if (!active) return;
            try {
                turns.cancel(turnId, snapshot.revision());
                return;
            } catch (TurnUseCase.TurnCancellationException failure) {
                if (failure.failure() == TurnUseCase.CancelFailure.TURN_NOT_FOUND) return;
                if (failure.failure() != TurnUseCase.CancelFailure.CONFLICT || attempt > 0) throw failure;
            }
        }
    }

    /** 隐藏上下文始终包含 Goal definition；Plan-only 字段仅在显式 link 存在时加入。 */
    private String hiddenContext(GoalModels.GoalSnapshot snapshot, long fencingToken) {
        ObjectNode root = json.createObjectNode();
        root.put("kind", "GOAL_CONTINUATION");
        root.put("goalId", snapshot.goal().goalId());
        root.put("goalRevision", snapshot.goal().revision());
        root.put("goalDefinitionRevision", snapshot.goal().goalDefinitionRevision());
        root.put("runId", snapshot.goal().activeRunId());
        root.put("fencingToken", fencingToken);
        root.put("objective", snapshot.goal().objective());
        ArrayNode goalCriteria = root.putArray("goalAcceptanceCriteria");
        snapshot.definition().acceptanceCriteria().forEach(item -> {
            ObjectNode node = goalCriteria.addObject();
            node.put("criterionId", item.criterionId());
            node.put("description", item.description());
            node.put("required", item.required());
        });
        if (snapshot.planLink() != null) {
            GoalModels.PlanSnapshot plan = goals.readPlanSnapshot(snapshot.planLink().planId());
            if (plan.currentRevision() == null
                    || !plan.currentRevision().planRevisionId().equals(snapshot.planLink().planRevisionId())
                    || !plan.currentRevision().planHash().equals(snapshot.planLink().planHash())) {
                throw new IllegalStateException("Goal Plan link is stale");
            }
            root.put("planId", snapshot.planLink().planId());
            root.put("planRevisionId", snapshot.planLink().planRevisionId());
            root.put("planHash", snapshot.planLink().planHash());
            root.set("plan", parsePlan(plan.currentRevision().canonicalJson()));
        }
        ArrayNode evidence = root.putArray("evidence");
        for (GoalModels.Evidence item : goals.listEvidence(snapshot.goal().goalId(),
                snapshot.goal().activeRunId(), 512)) {
            ObjectNode node = evidence.addObject();
            if (item.criterionId() != null) node.put("criterionId", item.criterionId());
            if (item.stepId() != null) node.put("stepId", item.stepId());
            node.put("sourceType", item.sourceType().name());
            node.put("sourceId", item.sourceId());
            node.put("summary", item.summary());
            node.put("digest", item.digest());
        }
        try {
            return json.writeValueAsString(root);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Goal continuation context cannot be encoded", failure);
        }
    }

    /** canonical Plan 必须保持结构化，解析失败说明持久事实损坏并应失败关闭。 */
    private com.fasterxml.jackson.databind.JsonNode parsePlan(String canonicalJson) {
        try {
            return json.readTree(canonicalJson);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Goal Plan context is invalid", failure);
        }
    }

}
