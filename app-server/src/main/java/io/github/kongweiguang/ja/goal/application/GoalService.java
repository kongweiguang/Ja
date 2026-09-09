// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.goal.domain.CanonicalPlanJson;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.domain.GoalModels.Goal;
import io.github.kongweiguang.ja.goal.domain.GoalModels.GoalPhase;
import io.github.kongweiguang.ja.goal.domain.GoalModels.GoalStatus;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanRevision;
import io.github.kongweiguang.ja.goal.port.in.GoalEvent;
import io.github.kongweiguang.ja.goal.port.in.GoalEventSink;
import io.github.kongweiguang.ja.goal.port.in.GoalUseCase;
import io.github.kongweiguang.ja.goal.port.in.PlanExecutionEventSink;
import io.github.kongweiguang.ja.goal.port.out.GoalRepository;
import io.github.kongweiguang.ja.goal.port.out.GoalRepositoryException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Optional;
import java.util.function.Supplier;

/** Goal application service 只分配 identity、canonical hash 并调用事务端口。 */
public final class GoalService implements GoalUseCase {
    private final GoalRepository goals;
    private final CanonicalPlanJson canonical;
    private final ObjectMapper json;
    private final Clock clock;
    private final long processGeneration;
    private final GoalEventRegistry events;
    private final GoalContinuationGate continuations;
    private final PlanExecutionCoordinator planExecutions;

    /** 所有 identity 仅由服务端生成，调用方只携带幂等 key。 */
    public GoalService(GoalRepository goals, ObjectMapper json, Clock clock) {
        this(goals, json, clock, 1, new GoalEventRegistry(), new GoalContinuationGate(), null);
    }

    /** process generation 由启动 ledger 注入，所有 evaluator intent 共享同一恢复代际。 */
    public GoalService(GoalRepository goals, ObjectMapper json, Clock clock, long processGeneration) {
        this(goals, json, clock, processGeneration, new GoalEventRegistry(), new GoalContinuationGate(), null);
    }

    /** 生产组合注入共享事件注册表和 continuation gate，使 RPC、调度与控制使用同一生命周期边界。 */
    public GoalService(GoalRepository goals, ObjectMapper json, Clock clock, long processGeneration,
                       GoalEventRegistry events, GoalContinuationGate continuations) {
        this(goals, json, clock, processGeneration, events, continuations, null);
    }

    /** 生产组合显式注入 Plan execution coordinator，测试可只验证无 execute 的核心用例。 */
    public GoalService(GoalRepository goals, ObjectMapper json, Clock clock, long processGeneration,
                       GoalEventRegistry events, GoalContinuationGate continuations,
                       PlanExecutionCoordinator planExecutions) {
        this.goals = Objects.requireNonNull(goals, "goals");
        this.json = Objects.requireNonNull(json, "json");
        this.canonical = new CanonicalPlanJson(json);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
        this.continuations = Objects.requireNonNull(continuations, "continuations");
        this.planExecutions = planExecutions;
        if (processGeneration < 1) throw new IllegalArgumentException("invalid process generation");
        this.processGeneration = processGeneration;
    }

    /** 订阅同时服务一个 RPC 连接和内部 continuation；每个句柄只移除自己的出口。 */
    @Override
    public AutoCloseable subscribe(GoalEventSink sink) {
        return events.subscribe(sink);
    }

    /** 读取由 repository 单事务组装的完整投影，application 不缓存派生状态。 */
    @Override
    public GoalModels.GoalSnapshot read(String goalId) {
        return goals.readSnapshot(goalId);
    }

    /** Plan 是独立 aggregate，读取不会隐式要求或更新 Goal link。 */
    @Override
    public GoalModels.PlanSnapshot readPlan(String planId) {
        return goals.readPlanSnapshot(planId);
    }

    /** Extension 只取得当前 Goal 的稳定 mutation/run identity，Plan link 由同一快照冻结。 */
    @Override
    public Optional<GoalTurnContext> currentGoalContext(String ownerThreadId) {
        return goals.findActiveGoalByOwner(ownerThreadId).map(goal -> {
            GoalModels.GoalPlanLink link = goals.readSnapshot(goal.goalId()).planLink();
            return new GoalTurnContext(goal.goalId(), goal.revision(), goal.activeRunId(),
                    goal.status(), goal.phase(),
                    link == null ? null : link.planId(), link == null ? null : link.planRevisionId());
        });
    }

    /**
     * admission 后必须沿用不可变 Turn binding，即使 Goal mutation 已进入等待或终态；这允许 AgentLoop
     * 完成最后一轮无 Tool 文本，同时防止旧 Turn 跟随 owner 后来创建的新 Goal。
     */
    @Override
    public Optional<GoalTurnContext> goalContinuationContext(String ownerThreadId, String turnId) {
        GoalModels.requireId(turnId, "turnId");
        Optional<GoalRepository.InternalTurnBinding> persisted = goals.findInternalTurnBinding(turnId)
                .filter(binding -> "GOAL_CONTINUATION".equals(binding.origin()));
        if (persisted.isEmpty()) return currentGoalContext(ownerThreadId);
        GoalRepository.InternalTurnBinding binding = persisted.orElseThrow();
        GoalModels.GoalSnapshot snapshot = goals.readSnapshot(binding.goalId());
        GoalModels.Goal goal = snapshot.goal();
        GoalModels.GoalPlanLink link = snapshot.planLink();
        boolean linkMatches = link == null
                ? binding.planId() == null && binding.planRevisionId() == null
                : link.planId().equals(binding.planId())
                        && link.planRevisionId().equals(binding.planRevisionId());
        if (!goal.ownerThreadId().equals(ownerThreadId)
                || !Objects.equals(goal.activeRunId(), binding.runId())
                || goal.goalDefinitionRevision() != binding.goalDefinitionRevision()
                || !linkMatches) return Optional.empty();
        return Optional.of(new GoalTurnContext(goal.goalId(), goal.revision(), goal.activeRunId(),
                goal.status(), goal.phase(), binding.planId(), binding.planRevisionId()));
    }

    /** Plan mode 动态上下文只投影 owner 唯一非终态 Plan 的 CAS 与执行 identity。 */
    @Override
    public Optional<PlanTurnContext> currentPlanContext(String ownerThreadId) {
        return goals.findActivePlanByOwner(ownerThreadId).map(plan -> new PlanTurnContext(
                plan.planId(), plan.revision(), plan.status(),
                plan.activePlanRevisionId(), plan.activeRunId()));
    }

    /**
     * runtime resolution 发生在 Turn admission 之前，此时尚无持久 internal context；V1 的 owner
     * 非终态唯一索引与 EXECUTING 查询共同提供唯一候选，admission 后 Tool ledger 再按 turnId 复核。
     */
    @Override
    public Optional<PlanTurnContext> planExecutionContext(String ownerThreadId, String turnId) {
        GoalModels.requireId(turnId, "turnId");
        Optional<GoalRepository.InternalTurnBinding> persisted = goals.findInternalTurnBinding(turnId)
                .filter(binding -> "PLAN_EXECUTION".equals(binding.origin()));
        if (persisted.isPresent()) {
            GoalRepository.InternalTurnBinding binding = persisted.orElseThrow();
            GoalModels.PlanSnapshot snapshot = goals.readPlanSnapshot(binding.planId());
            GoalModels.Plan current = snapshot.plan();
            GoalModels.PlanRevision revision = snapshot.currentRevision();
            if (!current.ownerThreadId().equals(ownerThreadId)
                    || (current.status() != GoalModels.PlanStatus.EXECUTING
                            && current.status() != GoalModels.PlanStatus.COMPLETED)
                    || !Objects.equals(current.activeRunId(), binding.runId())
                    || !Objects.equals(current.activePlanRevisionId(), binding.planRevisionId())
                    || revision == null || !revision.planHash().equals(binding.planHash())) {
                return Optional.empty();
            }
            return Optional.of(new PlanTurnContext(current.planId(), current.revision(), current.status(),
                    current.activePlanRevisionId(), current.activeRunId()));
        }
        return goals.findExecutingPlanByOwner(ownerThreadId).flatMap(plan -> {
            GoalModels.PlanSnapshot snapshot = goals.readPlanSnapshot(plan.planId());
            GoalModels.Plan current = snapshot.plan();
            GoalModels.PlanRevision revision = snapshot.currentRevision();
            if (current.status() != GoalModels.PlanStatus.EXECUTING
                    || current.activeRunId() == null || current.activePlanRevisionId() == null
                    || revision == null
                    || !revision.planRevisionId().equals(current.activePlanRevisionId())) {
                return Optional.empty();
            }
            return Optional.of(new PlanTurnContext(current.planId(), current.revision(), current.status(),
                    current.activePlanRevisionId(), current.activeRunId()));
        });
    }

    /** 事件页由 Repository 在一个读事务内冻结内容与水位，Service 只处理 opaque cursor。 */
    @Override
    public GoalModels.Page<GoalModels.PublicEvent> readEvents(String goalId, String cursor, int limit) {
        long afterSequence = numericCursor(cursor, "evt~");
        GoalRepository.ReadPage<GoalRepository.Event> page = goals.listEvents(
                goalId, afterSequence, pageFetch(limit));
        List<GoalRepository.Event> rows = page.items();
        boolean more = rows.size() > limit;
        List<GoalModels.PublicEvent> items = rows.stream().limit(limit).map(this::publicEvent).toList();
        String next = more && !items.isEmpty() ? "evt~" + items.getLast().eventSequence() : null;
        return new GoalModels.Page<>(goalId, page.goalRevision(), page.eventSequence(), items, next);
    }

    /** 终态时间线直接使用仓储已绑定的不可逆事实，不缓存或重建 Goal 状态。 */
    @Override
    public List<GoalModels.TerminalActivity> listTerminalActivities(String ownerThreadId, int limit) {
        return goals.listTerminalActivities(ownerThreadId, limit);
    }

    /** revision 页使用不可变 revisionNumber 游标，尾部追加不会重排已返回条目。 */
    @Override
    public GoalModels.Page<PlanRevision> listRevisions(String planId, String cursor, int limit) {
        long afterRevisionNumber = numericCursor(cursor, "rev~");
        GoalRepository.ReadPage<PlanRevision> page = goals.listPlanRevisions(
                planId, afterRevisionNumber, pageFetch(limit));
        List<PlanRevision> rows = page.items();
        boolean more = rows.size() > limit;
        List<PlanRevision> items = rows.stream().limit(limit).toList();
        String next = more && !items.isEmpty() ? "rev~" + items.getLast().revisionNumber() : null;
        return new GoalModels.Page<>(planId, page.goalRevision(), page.eventSequence(), items, next);
    }

    /** evidence 页使用 createdAt/identity keyset，迟到的写入不会移动已发出的游标边界。 */
    @Override
    public GoalModels.Page<GoalModels.Evidence> listEvidence(
            String goalId, long goalDefinitionRevision, String planRevisionId, String cursor, int limit) {
        EvidenceCursor after = evidenceCursor(cursor);
        GoalRepository.ReadPage<GoalModels.Evidence> page = goals.listEvidencePage(
                goalId, goalDefinitionRevision, planRevisionId,
                after == null ? null : after.createdAt(), after == null ? null : after.evidenceId(),
                pageFetch(limit));
        List<GoalModels.Evidence> rows = page.items();
        boolean more = rows.size() > limit;
        List<GoalModels.Evidence> items = rows.stream().limit(limit).toList();
        String next = more && !items.isEmpty() ? evidenceCursor(items.getLast()) : null;
        return new GoalModels.Page<>(goalId, page.goalRevision(), page.eventSequence(), items, next);
    }

    /** create 的幂等事实由 owner/key 回读，随机 ID 不影响公开重试。 */
    @Override
    public Goal create(Create command) {
        String goalId = id("goal_");
        String runId = id("run_");
        Goal result = goals.create(new GoalRepository.CreateGoal(goalId, command.ownerThreadId(),
                command.independentTask() ? GoalModels.OwnerKind.INDEPENDENT_TASK : GoalModels.OwnerKind.ROOT_THREAD,
                command.objective(), command.acceptanceCriteria(), runId, processGeneration,
                command.expectedThreadRevision(), command.idempotencyKey(), at(command.at())));
        publishLatest(result.goalId());
        return result;
    }

    /** Plan create 分配独立 identity，不写 Goal event 或依赖当前活动 Goal。 */
    @Override
    public GoalModels.Plan createPlan(CreatePlan command) {
        return goals.createPlan(new GoalRepository.CreatePlan(id("plan_"), command.ownerThreadId(),
                command.objective(), command.expectedThreadRevision(), command.idempotencyKey(), at(command.at())));
    }

    /** 草稿 JSON 使用和 revision 相同字段名，但不产生 hash 权威性。 */
    @Override
    public GoalModels.Plan saveDraft(SaveDraft command) {
        String definitionJson = canonical.encode(command.definition()).json();
        return goals.saveDraft(new GoalRepository.SaveDraft(
                command.planId(), command.expectedPlanRevision(),
                command.expectedDraftRevision(), definitionJson, command.basedOnPlanRevisionId(), id("evt_"),
                command.idempotencyKey(), at(command.at())));
    }

    /** 草稿丢弃保留 Goal CAS 与幂等事件，重复动作回读当前状态。 */
    @Override
    public GoalModels.Plan discardDraft(DiscardDraft command) {
        return goals.discardDraft(new GoalRepository.DiscardDraft(command.planId(), command.expectedPlanRevision(),
                id("evt_"), command.idempotencyKey(), at(command.at())));
    }

    /** UI propose 只冻结当前已保存草稿，不能从请求携带另一份未保存定义。 */
    @Override
    public PlanRevision proposeDraft(ProposeDraft command) {
        GoalModels.PlanDraft draft = goals.findDraft(command.planId()).orElseThrow(() ->
                new GoalRepositoryException(GoalRepositoryException.Code.PLAN_INVALID,
                        "Plan draft is unavailable"));
        return propose(new Propose(command.planId(), command.expectedPlanRevision(),
                draft.definition(), command.userAuthored(), command.idempotencyKey(), at(command.at())));
    }

    /** revision number 从仓储 writer transaction 分配，service 提供候选 1 供仓储替换校验。 */
    @Override
    public PlanRevision propose(Propose command) {
        CanonicalPlanJson.Encoded encoded = canonical.encode(command.definition());
        PlanRevision revision = new PlanRevision(id("planrev_"), command.planId(), 1, command.definition(),
                encoded.json(), encoded.sha256(), command.userAuthored() ? "USER_UI" : "AGENT", at(command.at()));
        return goals.propose(new GoalRepository.ProposePlan(command.planId(), command.expectedPlanRevision(),
                revision, id("evt_"), command.idempotencyKey(), at(command.at())));
    }

    /** 批准 action 分配 approval/run identity，仓储再次核对 hash。 */
    @Override
    public GoalModels.Plan approve(Approve command) {
        return goals.approve(new GoalRepository.ApprovePlan(command.planId(), command.expectedPlanRevision(),
                command.planRevisionId(), command.planHash(), id("appr_"), id("evt_"),
                command.idempotencyKey(), at(command.at())));
    }

    /**
     * standalone execute 是独立 mutation；发起连接的窄事件出口随 run 显式下传，避免 Plan
     * 错误依赖 Goal observe 或把内部 Turn 广播到其它连接。
     */
    @Override
    public GoalModels.Plan executePlan(ExecutePlan command, PlanExecutionEventSink events) {
        Objects.requireNonNull(events, "events");
        GoalModels.Plan result = goals.executePlan(new GoalRepository.ExecutePlan(command.planId(), command.expectedPlanRevision(),
                command.planRevisionId(), command.planHash(), id("run_"), command.processGeneration(), id("evt_"),
                command.idempotencyKey(), at(command.at())));
        if (planExecutions == null) throw new IllegalStateException("Plan execution runtime is unavailable");
        planExecutions.start(result, events);
        return result;
    }

    /** 拒绝当前最新提案；精确 revision/hash 从同一权威读模型取得并由 mutation CAS 再校验。 */
    @Override
    public GoalModels.Plan reject(Reject command) {
        GoalModels.PlanSnapshot snapshot = goals.readPlanSnapshot(command.planId());
        PlanRevision revision = snapshot.currentRevision();
        if (revision == null) throw new IllegalArgumentException("Plan revision is unavailable");
        return goals.reject(new GoalRepository.RejectPlan(command.planId(), command.expectedPlanRevision(),
                revision.planRevisionId(), revision.planHash(), id("appr_"), command.reason(), id("evt_"),
                command.idempotencyKey(), at(command.at())));
    }

    /** attach 由仓储原子校验 owner、批准事实与精确 hash，并切换 Goal run。 */
    @Override
    public Goal attachPlan(AttachPlan command) {
        return continuations.serialized(command.goalId(), () -> {
            requireSettledForCurrentRevision(command.goalId(), command.expectedGoalRevision());
            return mutateAndPublish(command.goalId(), () -> goals.attachPlan(new GoalRepository.AttachPlan(
                    command.goalId(), command.expectedGoalRevision(), command.planId(), command.planRevisionId(),
                    command.planHash(), id("run_"), processGeneration, id("evt_"), command.idempotencyKey(),
                    at(command.at()))));
        });
    }

    /** detach 分配全新 Goal-only run；独立 Plan 的 run 生命周期不受影响。 */
    @Override
    public Goal detachPlan(DetachPlan command) {
        return continuations.serialized(command.goalId(), () -> {
            requireSettledForCurrentRevision(command.goalId(), command.expectedGoalRevision());
            return mutateAndPublish(command.goalId(), () -> goals.detachPlan(new GoalRepository.DetachPlan(
                    command.goalId(), command.expectedGoalRevision(), id("run_"),
                    processGeneration, id("evt_"), command.idempotencyKey(), at(command.at()))));
        });
    }

    /** 控制动作映射到固定状态组合，不接受 handler 自选 phase。 */
    @Override
    public Goal control(Control command) {
        GoalStatus status = switch (command.action()) {
            case PAUSE -> GoalStatus.PAUSED;
            case RESUME -> GoalStatus.ACTIVE;
            case STOP -> GoalStatus.STOPPED;
        };
        GoalPhase phase = switch (command.action()) {
            case PAUSE -> GoalPhase.PAUSED;
            case RESUME -> GoalPhase.WORKING;
            case STOP -> GoalPhase.STOPPED;
        };
        Supplier<Goal> transition = () -> mutateAndPublish(command.goalId(), () -> goals.transition(
                new GoalRepository.Transition(command.goalId(), command.expectedGoalRevision(),
                        status, phase, false, id("evt_"), command.idempotencyKey(), at(command.at()))));
        return continuations.serialized(command.goalId(), () -> {
            if (command.action() == Action.RESUME) {
                continuations.requireSettled(command.goalId());
                return transition.get();
            }
            Goal committed = transition.get();
            continuations.cancelActive(command.goalId());
            return committed;
        });
    }

    /** Agent step update 仍需 expected status，不能仅凭文本宣告成功。 */
    @Override
    public Goal updateStep(StepUpdate command) {
        List<GoalRepository.ToolEvidenceClaim> claims = evidenceClaims(command.evidenceClaims(), command.at());
        return mutateAndPublish(command.goalId(), () -> goals.updateStep(new GoalRepository.UpdateStep(
                command.goalId(), command.expectedGoalRevision(),
                command.runId(), command.stepId(), command.expectedStatus(), command.status(),
                command.failureSignature(), claims, id("evt_"), command.idempotencyKey(),
                at(command.at()))));
    }

    /** standalone Plan step 只发布 Plan event，不触发 Goal observer。 */
    @Override
    public GoalModels.Plan updatePlanStep(PlanStepUpdate command) {
        List<GoalRepository.ToolEvidenceClaim> claims = evidenceClaims(command.evidenceClaims(), command.at());
        return goals.updatePlanStep(new GoalRepository.UpdatePlanStep(command.planId(),
                command.expectedPlanRevision(), command.runId(), command.stepId(), command.expectedStatus(),
                command.status(), command.failureSignature(), claims, id("evt_"), command.idempotencyKey(),
                at(command.at())));
    }

    /** 输入 request identity 由服务端分配，避免 Agent 复用别的 Goal 请求。 */
    @Override
    public Goal requestInput(InputRequest command) {
        return mutateAndPublish(command.goalId(), () -> goals.requestInput(new GoalRepository.RequestInput(
                command.goalId(), command.expectedGoalRevision(),
                id("goalinput_"), command.runId(), command.prompt(), command.expiresAt(), id("evt_"),
                command.idempotencyKey(), at(command.at()))));
    }

    /** evaluator intent 提交成功后才允许 coordinator 调用 Provider。 */
    @Override
    public Goal requestEvaluation(EvaluationRequest command) {
        List<GoalRepository.ToolEvidenceClaim> claims = evidenceClaims(command.evidenceClaims(), command.at());
        return mutateAndPublish(command.goalId(), () -> goals.requestEvaluation(new GoalRepository.RequestEvaluation(command.goalId(),
                command.expectedGoalRevision(), id("evaluation_"), command.runId(), command.planRevisionId(),
                processGeneration, claims, id("evt_"), command.idempotencyKey(), at(command.at()))));
    }

    /** 输入响应由 ObjectMapper 编码为 JSON string，避免手工转义改变持久语义。 */
    @Override
    public Goal respondInput(InputResponse command) {
        try {
            String responseJson = json.writeValueAsString(command.response());
            return mutateAndPublish(command.goalId(), () -> goals.respondInput(new GoalRepository.RespondInput(command.goalId(),
                    command.expectedGoalRevision(), command.inputRequestId(),
                    responseJson, id("evt_"), command.idempotencyKey(),
                    at(command.at()))));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("cannot encode Goal input response", failure);
        }
    }

    /** 所有 Agent evidence claim 在服务端分配 identity 并规范时间，三个 mutation 不得产生不同规则。 */
    private List<GoalRepository.ToolEvidenceClaim> evidenceClaims(
            List<GoalUseCase.ToolEvidenceClaim> claims, java.time.Instant observedAt) {
        return claims.stream().map(claim -> new GoalRepository.ToolEvidenceClaim(
                id("evidence_"), claim.criterionId(), claim.callId(), claim.summary(), at(observedAt))).toList();
    }

    /** mutation 先取得持久回执再发布；没有订阅者时不产生额外 Goal/Plan 查询。 */
    private <T> T mutateAndPublish(String goalId, Supplier<T> mutation) {
        T result = mutation.get();
        publishLatest(goalId);
        return result;
    }

    /**
     * 当前 revision 的换 Run 操作必须等待 continuation 收口；陈旧命令则交给事务仓储先判定幂等重放或
     * revision conflict，避免进程内 gate 把稳定 CAS 错误遮蔽，也避免成功请求的重试被误判为冲突。
     * 仓储仍在同一事务执行最终 revision CAS，因此这里的只读判断不承担并发正确性。
     */
    private void requireSettledForCurrentRevision(String goalId, long expectedGoalRevision) {
        Optional<Goal> current = goals.findGoal(goalId);
        if (current.isPresent() && current.orElseThrow().revision() == expectedGoalRevision) {
            continuations.requireSettled(goalId);
        }
    }

    /** 从最新持久 sequence 读取刚提交事件并携带同事务快照，连接背压由 sink 控制。 */
    private void publishLatest(String goalId) {
        if (events.isEmpty()) return;
        GoalModels.GoalSnapshot snapshot = goals.readSnapshot(goalId);
        if (snapshot.eventSequence() < 1) return;
        GoalRepository.ReadPage<GoalRepository.Event> page = goals.listEvents(
                goalId, snapshot.eventSequence() - 1, 1);
        if (page.items().isEmpty()) return;
        GoalEvent event = new GoalEvent(snapshot, publicEvent(page.items().getFirst()));
        events.publish(event);
    }

    /** 内部 evaluator/恢复在持久 mutation 成功后复用同一公开事件映射与订阅背压。 */
    public void publishCommitted(String goalId) { publishLatest(goalId); }

    /**
     * continuation Turn 的审批事件使用事件 identity 做幂等 CAS；用户暂停或停止先提交时不重试，
     * 防止迟到的 Turn 事件覆盖用户控制结果。
     */
    void projectContinuationPhase(String goalId, GoalPhase expectedPhase, GoalPhase phase,
                                  String eventIdentity, java.time.Instant occurredAt) {
        Goal current = goals.findGoal(goalId).orElseThrow(() -> new GoalRepositoryException(
                GoalRepositoryException.Code.GOAL_NOT_FOUND, "Goal is unavailable"));
        if (current.status() != GoalStatus.ACTIVE || current.phase() != expectedPhase) return;
        mutateAndPublish(goalId, () -> goals.projectContinuationPhase(
                new GoalRepository.ProjectContinuationPhase(goalId, current.revision(), expectedPhase, phase,
                        id("evt_"), "continuation-phase:" + eventIdentity, at(occurredAt))));
    }

    /** transport 页最大 200，内部只多取一项判断 nextCursor。 */
    private static int pageFetch(int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("invalid Goal page size");
        return limit + 1;
    }

    /** 数字游标带类型前缀，禁止 event cursor 被误用于 revision 查询。 */
    private static long numericCursor(String cursor, String prefix) {
        if (cursor == null || cursor.isBlank()) return 0;
        if (!cursor.startsWith(prefix) || cursor.length() > 64) {
            throw new IllegalArgumentException("invalid Goal cursor");
        }
        try {
            long value = Long.parseLong(cursor.substring(prefix.length()));
            if (value < 0) throw new IllegalArgumentException("invalid Goal cursor");
            return value;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("invalid Goal cursor", failure);
        }
    }

    /** evidence cursor 是 URL-safe Base64 的 ISO instant 与稳定 identity，不暴露数据库 offset。 */
    private static EvidenceCursor evidenceCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        if (cursor.length() > 512) throw new IllegalArgumentException("invalid evidence cursor");
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = decoded.indexOf('\n');
            if (separator <= 0 || separator != decoded.lastIndexOf('\n')) {
                throw new IllegalArgumentException("invalid evidence cursor");
            }
            String createdAt = decoded.substring(0, separator);
            String evidenceId = GoalModels.requireId(decoded.substring(separator + 1), "evidence cursor id");
            java.time.Instant.parse(createdAt);
            return new EvidenceCursor(createdAt, evidenceId);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("invalid evidence cursor", failure);
        }
    }

    /** 下一 evidence cursor 精确落在最后一个已返回事实，不从客户端可见序号推导。 */
    private static String evidenceCursor(GoalModels.Evidence evidence) {
        String value = evidence.createdAt() + "\n" + evidence.evidenceId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** 事件 payload 只接受 repository 自己写入的 activity 闭集，不把任意 JSON 透传到 UI。 */
    private GoalModels.PublicEvent publicEvent(GoalRepository.Event value) {
        try {
            String activity = json.readTree(value.payloadJson()).required("activity").textValue();
            return new GoalModels.PublicEvent(value.sequence(), value.goalRevision(), publicKind(activity),
                    publicSummary(activity), value.createdAt());
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("persisted Goal event is invalid", failure);
        }
    }

    /** 映射冻结事件词汇，内部细节不会成为可扩展的 Wire 字符串。 */
    private static String publicKind(String activity) {
        return switch (activity.toLowerCase(java.util.Locale.ROOT)) {
            case "created" -> "created";
            case "draft_saved" -> "plan_draft_saved";
            case "draft_discarded" -> "plan_draft_saved";
            case "plan_proposed" -> "plan_proposed";
            case "plan_approved" -> "plan_approved";
            case "plan_rejected" -> "plan_rejected";
            case "plan_attached" -> "plan_attached";
            case "plan_detached" -> "plan_detached";
            case "step_updated" -> "step_changed";
            case "evidence_added" -> "evidence_added";
            case "continuation_no_progress" -> "continuation_no_progress";
            case "tool_approval_requested" -> "tool_approval_requested";
            case "tool_approval_resolved" -> "tool_approval_resolved";
            case "input_requested" -> "input_requested";
            case "input_responded" -> "input_received";
            case "evaluation_requested" -> "evaluation_started";
            case "evaluation_completed" -> "evaluation_completed";
            case "paused" -> "paused";
            case "working" -> "resumed";
            case "stopped" -> "stopped";
            case "achieved" -> "achieved";
            case "needs_attention" -> "recovery_required";
            default -> throw new IllegalStateException("unknown Goal event activity");
        };
    }

    /** 用户可见事件摘要是稳定产品文案，不回显 payload 或异常消息。 */
    private static String publicSummary(String activity) {
        return switch (publicKind(activity)) {
            case "created" -> "Goal 已创建";
            case "plan_draft_saved" -> "计划草稿已更新";
            case "plan_proposed" -> "计划等待批准";
            case "plan_approved" -> "计划已批准";
            case "plan_rejected" -> "计划已退回";
            case "plan_attached" -> "计划已关联到 Goal";
            case "plan_detached" -> "计划已从 Goal 解除关联";
            case "step_changed" -> "计划步骤已更新";
            case "evidence_added" -> "验收证据已记录";
            case "continuation_no_progress" -> "本轮未取得新进展";
            case "tool_approval_requested" -> "Goal 正在等待工具批准";
            case "tool_approval_resolved" -> "工具审批已处理";
            case "input_requested" -> "Goal 正在等待输入";
            case "input_received" -> "输入已提交";
            case "evaluation_started" -> "独立验收已开始";
            case "evaluation_completed" -> "独立验收已完成";
            case "paused" -> "Goal 已暂停";
            case "resumed" -> "Goal 已恢复";
            case "stopped" -> "Goal 已停止";
            case "achieved" -> "Goal 已达成";
            case "recovery_required" -> "Goal 需要人工处理";
            default -> throw new IllegalStateException("unknown Goal event activity");
        };
    }

    /** 缺省时间使用 composition Clock，禁止直接读取系统墙钟。 */
    private java.time.Instant at(java.time.Instant requested) {
        return requested == null ? clock.instant() : requested;
    }

    /** UUID 只作为 opaque identity，业务排序完全依赖 SQLite sequence。 */
    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    /** 解码后的 keyset 游标只在 application service 内部存在。 */
    private record EvidenceCursor(String createdAt, String evidenceId) { }
}
