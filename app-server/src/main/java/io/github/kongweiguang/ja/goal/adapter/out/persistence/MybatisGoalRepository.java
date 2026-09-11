// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.domain.GoalModels.AcceptanceCriterion;
import io.github.kongweiguang.ja.goal.domain.GoalModels.CriterionEvaluation;
import io.github.kongweiguang.ja.goal.domain.GoalStateMachine;
import io.github.kongweiguang.ja.goal.domain.PlanPolicy;
import io.github.kongweiguang.ja.goal.domain.GoalModels.Evidence;
import io.github.kongweiguang.ja.goal.domain.GoalModels.EvidenceSource;
import io.github.kongweiguang.ja.goal.domain.GoalModels.EvaluationVerdict;
import io.github.kongweiguang.ja.goal.domain.GoalModels.Goal;
import io.github.kongweiguang.ja.goal.domain.GoalModels.GoalDefinition;
import io.github.kongweiguang.ja.goal.domain.GoalModels.GoalEvaluation;
import io.github.kongweiguang.ja.goal.domain.GoalModels.GoalPhase;
import io.github.kongweiguang.ja.goal.domain.GoalModels.GoalPlanLink;
import io.github.kongweiguang.ja.goal.domain.GoalModels.GoalSnapshot;
import io.github.kongweiguang.ja.goal.domain.GoalModels.GoalStatus;
import io.github.kongweiguang.ja.goal.domain.GoalModels.OwnerKind;
import io.github.kongweiguang.ja.goal.domain.GoalModels.Plan;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanApproval;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanDefinition;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanDraft;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanRevision;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanSnapshot;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanStatus;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanStep;
import io.github.kongweiguang.ja.goal.domain.GoalModels.StepExecution;
import io.github.kongweiguang.ja.goal.domain.GoalModels.StepStatus;
import io.github.kongweiguang.ja.goal.domain.GoalModels.TerminalActivity;
import io.github.kongweiguang.ja.goal.domain.GoalModels.ToolAttempt;
import io.github.kongweiguang.ja.goal.domain.GoalModels.ToolAttemptState;
import io.github.kongweiguang.ja.goal.port.out.GoalRepository;
import io.github.kongweiguang.ja.goal.port.out.GoalRepositoryException;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionStatus;
import org.apache.ibatis.session.SqlSessionFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** V1 Goal/Plan SQLite adapter；所有公开 mutation 由同一 MyBatis session transaction 包围。 */
public final class MybatisGoalRepository implements GoalRepository {
    private final GoalUnitOfWork transactions;
    private final ObjectMapper json;

    /** 生产构造复用 Solon 管理的 MyBatis transaction。 */
    public MybatisGoalRepository(SqlSessionFactory sessions, ObjectMapper json) {
        this.transactions = new GoalUnitOfWork(sessions);
        this.json = Objects.requireNonNull(json, "json");
    }

    /** 测试构造注入真实 SQLite commit/rollback owner。 */
    public MybatisGoalRepository(SqlSessionFactory sessions, ObjectMapper json, GoalUnitOfWork.SessionOwner owner) {
        this.transactions = new GoalUnitOfWork(sessions, owner);
        this.json = Objects.requireNonNull(json, "json");
    }

    /** Goal 创建原子冻结 definition、criteria、Goal-only run 与首事件。 */
    @Override public Goal create(CreateGoal command) {
        return transactions.required(c -> {
            Goal replay = queryOne(c, "SELECT * FROM goals WHERE owner_thread_id=? AND create_idempotency_key=?", this::mapGoal, command.ownerThreadId(), command.idempotencyKey());
            if (replay != null) return replay;
            Owner owner = queryOne(c, "SELECT t.revision,l.task_kind,l.lifecycle FROM threads t LEFT JOIN thread_lineage l ON l.child_thread_id=t.thread_id WHERE t.thread_id=? AND t.deleted_at IS NULL",
                    r -> new Owner(r.getLong(1), r.getString(2), r.getString(3)), command.ownerThreadId());
            if (owner == null) throw error(GoalRepositoryException.Code.GOAL_NOT_FOUND, "Goal owner is unavailable");
            if (owner.revision != command.expectedThreadRevision()) throw conflict();
            boolean validOwner = command.ownerKind() == OwnerKind.ROOT_THREAD && owner.taskKind == null
                    || command.ownerKind() == OwnerKind.INDEPENDENT_TASK && "SIDE_TASK".equals(owner.taskKind) && "INDEPENDENT".equals(owner.lifecycle);
            if (!validOwner) throw invalid("Goal owner type is invalid");
            requireOwnerAdmissionOpen(c, command.ownerThreadId());
            requireNoStandalonePlanRun(c, command.ownerThreadId());
            String at = at(command.at());
            one(update(c, "INSERT INTO goals(goal_id,owner_thread_id,owner_kind,objective,goal_definition_revision,create_idempotency_key,status,phase,revision,active_run_id,created_at,updated_at) VALUES(?,?,?,?,1,?,'ACTIVE','WORKING',0,?,?,?)",
                    command.goalId(), command.ownerThreadId(), command.ownerKind().name(), command.objective(), command.idempotencyKey(), command.runId(), at, at));
            one(update(c, "INSERT INTO goal_definition_revisions(goal_id,revision_number,objective,created_at) VALUES(?,1,?,?)", command.goalId(), command.objective(), at));
            for (int i=0;i<command.acceptanceCriteria().size();i++) {
                AcceptanceCriterion item=command.acceptanceCriteria().get(i);
                one(update(c,"INSERT INTO goal_acceptance_criteria(goal_id,goal_definition_revision,criterion_id,ordinal,description,required) VALUES(?,1,?,?,?,?)",command.goalId(),item.criterionId(),i,item.description(),item.required()?1:0));
            }
            insertRun(c,command.runId(),command.goalId(),null,1L,null,null,command.processGeneration(),at);
            goalEvent(c,command.goalId(),0,"CHANGED","created",command.goalId()+":created",command.idempotencyKey(),at);
            return requireGoal(c,command.goalId());
        });
    }
    /** Plan 创建不依赖活动 Goal。 */
    @Override public Plan createPlan(CreatePlan command) {
        return transactions.required(c -> {
            Plan replay=queryOne(c,"SELECT * FROM plans WHERE owner_thread_id=? AND create_idempotency_key=?",this::mapPlan,command.ownerThreadId(),command.idempotencyKey());
            if(replay!=null)return replay;
            Long revision=queryOne(c,"SELECT revision FROM threads WHERE thread_id=? AND deleted_at IS NULL",r->r.getLong(1),command.ownerThreadId());
            if(revision==null)throw error(GoalRepositoryException.Code.GOAL_NOT_FOUND,"Plan owner is unavailable");
            if(revision!=command.expectedThreadRevision())throw conflict();
            // 当前草稿必须经修订继续使用；提前返回领域冲突，不能把唯一约束错误伪装成存储不可用。
            if (scalar(c, "SELECT COUNT(*) FROM plans WHERE owner_thread_id=? AND status NOT IN ('COMPLETED','STOPPED')",
                    command.ownerThreadId()) != 0) throw invalid("Thread already has an unfinished Plan");
            requireOwnerAdmissionOpen(c, command.ownerThreadId());
            String at=at(command.at());
            one(update(c,"INSERT INTO plans(plan_id,owner_thread_id,objective,create_idempotency_key,status,revision,created_at,updated_at) VALUES(?,?,?,?,'DRAFT',0,?,?)",command.planId(),command.ownerThreadId(),command.objective(),command.idempotencyKey(),at,at));
            planEvent(c,command.planId(),0,"created","evt_"+command.planId(),command.idempotencyKey(),at);
            return requirePlan(c,command.planId());
        });
    }
    /** 按 identity 读取 Goal。 */
    @Override public Optional<Goal> findGoal(String goalId) { return transactions.required(c->Optional.ofNullable(queryOne(c,"SELECT * FROM goals WHERE goal_id=?",this::mapGoal,goalId))); }
    /** 精确读取 Goal definition revision。 */
    @Override public GoalDefinition readGoalDefinition(String goalId, long revision) { return transactions.required(c->definition(c,goalId,revision)); }
    /** 单事务组装 Goal snapshot。 */
    @Override public GoalSnapshot readSnapshot(String goalId) { return transactions.required(c->snapshot(c,requireGoal(c,goalId))); }
    /** 单事务组装独立 Plan snapshot。 */
    @Override public PlanSnapshot readPlanSnapshot(String planId) { return transactions.required(c->planSnapshot(c,requirePlan(c,planId))); }
    /** owner 与最新计划在同一读事务内解析，不能从 UI 缓存或 Goal link 推断当前计划。 */
    @Override public Optional<PlanSnapshot> findCurrentPlan(String ownerThreadId) {
        return transactions.required(c -> {
            Plan plan = queryOne(c, "SELECT * FROM plans WHERE owner_thread_id=? ORDER BY updated_at DESC,plan_id DESC LIMIT 1",
                    this::mapPlan, ownerThreadId);
            return plan == null ? Optional.empty() : Optional.of(planSnapshot(c, plan));
        });
    }
    /** Plan 观察只查询当前步骤摘要和最新事件，隐藏 Workbench 不会因此加载正文或 evidence。 */
    @Override public GoalRepository.PlanObservation readPlanObservation(String planId) {
        return transactions.required(c -> {
            Plan plan = requirePlan(c, planId);
            PlanEvent event = queryOne(c,
                    "SELECT event_sequence,event_id,plan_id,plan_revision,activity,created_at "
                            + "FROM plan_events WHERE plan_id=? ORDER BY event_sequence DESC LIMIT 1",
                    this::mapPlanEvent, planId);
            List<StepProgressRow> rows = plan.activePlanRevisionId() == null || plan.activeRunId() == null
                    ? List.of()
                    : queryList(c,
                    "SELECT s.step_id,s.title,s.required,COALESCE(x.status,'PENDING') AS status "
                            + "FROM plan_steps s LEFT JOIN plan_step_executions x "
                            + "ON x.run_id=? AND x.step_id=s.step_id "
                            + "WHERE s.plan_revision_id=? ORDER BY s.ordinal",
                    r -> new StepProgressRow(r.getString("step_id"), r.getString("title"),
                            r.getInt("required") == 1, r.getString("status")),
                    plan.activeRunId(), plan.activePlanRevisionId());
            return new GoalRepository.PlanObservation(plan, event, progress(rows));
        });
    }
    /** 草稿缺失不回退。 */
    @Override public Optional<PlanDraft> findDraft(String planId) { return transactions.required(c->Optional.ofNullable(queryOne(c,"SELECT * FROM plan_drafts WHERE plan_id=?",this::mapDraft,planId))); }
    /** Plan/draft 双 CAS 保存。 */
    @Override public Plan saveDraft(SaveDraft command) {
        return planMutation(command.planId(),command.expectedPlanRevision(),command.idempotencyKey(),
                Set.of("draft_saved"), c->{
            Plan before=requirePlan(c,command.planId());
            if (before.status() == PlanStatus.EXECUTING || before.status() == PlanStatus.VERIFYING) {
                throw invalid("Plan must be paused before editing");
            }
            if (before.activeRunId() != null && before.status() == PlanStatus.PAUSED) {
                long unsettled = scalar(c, "SELECT COUNT(*) FROM turns t JOIN turn_internal_context x ON x.turn_id=t.turn_id "
                        + "WHERE json_extract(x.context_json,'$.runId')=? AND t.state IN ('QUEUED','RUNNING','WAITING_APPROVAL','SUSPENDED')",
                        before.activeRunId());
                if (unsettled > 0) throw invalid("Plan pause is not settled for editing");
                stopRun(c, before.activeRunId(), command.at());
                update(c, "UPDATE plan_turn_claims SET state='SETTLED' WHERE run_id=? AND state='CLAIMED'", before.activeRunId());
            }
            PlanDraft current=queryOne(c,"SELECT * FROM plan_drafts WHERE plan_id=?",this::mapDraft,command.planId());
            if((current==null?0:current.draftRevision())!=command.expectedDraftRevision())throw conflict();
            String draftId=current==null?"plandraft_"+command.planId():current.planDraftId();
            one(update(c,"INSERT INTO plan_drafts(plan_draft_id,plan_id,draft_revision,definition_json,based_on_plan_revision_id,updated_at) VALUES(?,?,1,?,?,?) ON CONFLICT(plan_id) DO UPDATE SET draft_revision=plan_drafts.draft_revision+1,definition_json=excluded.definition_json,based_on_plan_revision_id=excluded.based_on_plan_revision_id,updated_at=excluded.updated_at",draftId,command.planId(),command.definitionJson(),command.basedOnPlanRevisionId(),at(command.at())));
            planCas(c,before,PlanStatus.DRAFT,null,null,command.at());
            planEvent(c,command.planId(),before.revision()+1,"draft_saved",command.eventId(),command.idempotencyKey(),at(command.at()));
            return requirePlan(c,command.planId());
        });
    }
    /**
     * 丢弃换版草稿时恢复草稿基于的已批准 revision；没有有效批准基线才回到普通 DRAFT。
     * 这样“取消编辑”不会销毁原执行资格，同时仍不让未批准的新 revision 被执行。
     */
    @Override public Plan discardDraft(DiscardDraft command) {
        return planMutation(command.planId(), command.expectedPlanRevision(), command.idempotencyKey(),
                Set.of("draft_discarded"), c -> {
            Plan before = requirePlan(c, command.planId());
            PlanDraft draft = queryOne(c, "SELECT * FROM plan_drafts WHERE plan_id=?", this::mapDraft,
                    command.planId());
            String approvedBaseline = draft == null || draft.basePlanRevisionId() == null ? null
                    : queryOne(c, "SELECT plan_revision_id FROM plan_approvals WHERE plan_id=? "
                                    + "AND plan_revision_id=? AND decision='APPROVED' ORDER BY created_at DESC LIMIT 1",
                            r -> r.getString(1), command.planId(), draft.basePlanRevisionId());
            update(c, "DELETE FROM plan_drafts WHERE plan_id=?", command.planId());
            planCas(c, before, approvedBaseline == null ? PlanStatus.DRAFT : PlanStatus.APPROVED,
                    approvedBaseline, null, command.at());
            planEvent(c, command.planId(), before.revision() + 1, "draft_discarded", command.eventId(),
                    command.idempotencyKey(), at(command.at()));
            return requirePlan(c, command.planId());
        });
    }
    /** 冻结 canonical Plan revision。 */
    @Override public PlanRevision propose(ProposePlan command) {
        PlanPolicy.validate(command.revision().definition());
        return transactions.required(c->{
            String replay=queryOne(c,"SELECT activity FROM plan_events WHERE plan_id=? AND idempotency_key=?",r->r.getString(1),command.planId(),command.idempotencyKey());
            if(replay!=null&&replay.startsWith("plan_proposed:"))return requireRevision(c,command.planId(),replay.substring(14));
            Plan before=requirePlanRevision(c,command.planId(),command.expectedPlanRevision());
            if(before.status()!=PlanStatus.DRAFT)throw invalid("Plan is not accepting proposals");
            long pendingRequired = scalar(c,
                    "SELECT COUNT(*) FROM interaction_requests i "
                            + "WHERE i.thread_id=? AND i.status='PENDING' "
                            + "AND EXISTS (SELECT 1 FROM json_each(i.questions_json) q "
                            + "WHERE json_extract(q.value,'$.required')=1)", before.ownerThreadId());
            if (pendingRequired != 0) {
                throw invalid("Plan cannot be finalized while a required question is pending");
            }
            int next=Math.toIntExact(scalar(c,"SELECT COALESCE(MAX(revision_number),0)+1 FROM plan_revisions WHERE plan_id=?",command.planId()));
            PlanRevision value=command.revision();
            one(update(c,"INSERT INTO plan_revisions(plan_revision_id,plan_id,revision_number,definition_json,plan_hash,created_by,created_at) VALUES(?,?,?,?,?,?,?)",value.planRevisionId(),command.planId(),next,value.canonicalJson(),value.planHash(),value.createdBy(),at(value.createdAt())));
            for(int i=0;i<value.definition().steps().size();i++){PlanStep step=value.definition().steps().get(i);one(update(c,"INSERT INTO plan_steps(plan_revision_id,step_id,ordinal,title,description,required,dependency_ids_json) VALUES(?,?,?,?,?,?,?)",value.planRevisionId(),step.stepId(),i,step.title(),step.description(),step.required()?1:0,json.writeValueAsString(step.dependsOn())));}
            for(int i=0;i<value.definition().acceptanceCriteria().size();i++){AcceptanceCriterion item=value.definition().acceptanceCriteria().get(i);one(update(c,"INSERT INTO acceptance_criteria(plan_revision_id,criterion_id,ordinal,description,required) VALUES(?,?,?,?,?)",value.planRevisionId(),item.criterionId(),i,item.description(),item.required()?1:0));}
            update(c,"DELETE FROM plan_drafts WHERE plan_id=?",command.planId());
            planCas(c,before,PlanStatus.AWAITING_APPROVAL,null,null,command.at());
            planEvent(c,command.planId(),before.revision()+1,"plan_proposed:"+value.planRevisionId(),command.eventId(),command.idempotencyKey(),at(command.at()));
            return new PlanRevision(value.planRevisionId(),command.planId(),next,value.definition(),value.canonicalJson(),value.planHash(),value.createdBy(),value.createdAt());
        });
    }
    /** 批准只记录 USER_UI decision。 */
    @Override public Plan approve(ApprovePlan command) { return planMutation(command.planId(),command.expectedPlanRevision(),command.idempotencyKey(),Set.of("plan_approved"),c->{Plan before=requirePlan(c,command.planId());if(before.status()!=PlanStatus.AWAITING_APPROVAL)throw invalid("Plan is not awaiting approval");PlanRevision rev=requireLatest(c,command.planId(),command.planRevisionId(),command.planHash());one(update(c,"INSERT INTO plan_approvals(approval_id,plan_id,plan_revision_id,plan_hash,actor,decision,created_at) VALUES(?,?,?,?,'USER_UI','APPROVED',?)",command.approvalId(),command.planId(),rev.planRevisionId(),rev.planHash(),at(command.at())));planCas(c,before,PlanStatus.APPROVED,rev.planRevisionId(),null,command.at());planEvent(c,command.planId(),before.revision()+1,"plan_approved",command.eventId(),command.idempotencyKey(),at(command.at()));return requirePlan(c,command.planId());}); }
    /**
     * 执行是唯一的用户授权边界：AWAITING_APPROVAL 在此事务内同时写入审计、Run 和冻结预算，
     * 防止前端先批准再执行造成双击、丢响应或版本竞态；APPROVED 仅用于恢复既有数据库事实。
     */
    @Override public Plan executePlan(ExecutePlan command) { return planMutation(command.planId(),command.expectedPlanRevision(),command.idempotencyKey(),Set.of("plan_execution_started"),c->{Plan before=requirePlan(c,command.planId());requireOwnerAdmissionOpen(c,before.ownerThreadId());if(before.status()!=PlanStatus.AWAITING_APPROVAL&&before.status()!=PlanStatus.APPROVED)throw invalid("Plan is not ready to execute");requireNoActiveGoalForPlan(c, command.planId());requireNoActiveStandaloneRun(c, command.planId());PlanRevision rev=before.status()==PlanStatus.AWAITING_APPROVAL?requireLatest(c,command.planId(),command.planRevisionId(),command.planHash()):requireApproved(c,command.planId(),command.planRevisionId(),command.planHash());if(before.status()==PlanStatus.AWAITING_APPROVAL)one(update(c,"INSERT INTO plan_approvals(approval_id,plan_id,plan_revision_id,plan_hash,actor,decision,created_at) VALUES(?,?,?,?,'USER_UI','APPROVED',?)",command.approvalId(),command.planId(),rev.planRevisionId(),rev.planHash(),at(command.at())));insertRun(c,command.runId(),null,command.planId(),null,rev.planRevisionId(),rev.planHash(),command.processGeneration(),at(command.at()));freezePlanBudget(c,command);insertSteps(c,command.runId(),rev,command.at());planCas(c,before,PlanStatus.EXECUTING,rev.planRevisionId(),command.runId(),command.at());planEvent(c,command.planId(),before.revision()+1,"plan_execution_started",command.eventId(),command.idempotencyKey(),at(command.at()));return requirePlan(c,command.planId());}); }
    /** 未完成 Turn 进入可恢复暂停；原 Run、冻结预算和 revision 保留，继续动作不会重置累计用量。 */
    @Override public Plan settlePlanExecution(SettlePlanExecution command) { return planMutation(command.planId(),command.expectedPlanRevision(),command.idempotencyKey(),Set.of("plan_execution_incomplete"),c->{Plan before=requirePlan(c,command.planId());if((before.status()!=PlanStatus.EXECUTING&&before.status()!=PlanStatus.VERIFYING)||!Objects.equals(before.activeRunId(),command.runId()))return before;settleOrAbandonPlanClaims(c,command.runId());transitionRun(c,command.runId(),"PAUSED",command.at());planCas(c,before,PlanStatus.PAUSED,before.activePlanRevisionId(),before.activeRunId(),command.at());planEvent(c,command.planId(),before.revision()+1,"plan_execution_incomplete",command.eventId(),command.idempotencyKey(),at(command.at()));return requirePlan(c,command.planId());}); }
    /** 原子领取一个隐藏 Turn；预算耗尽时在同一事务暂停 Plan，不能靠重启清零计数。 */
    @Override public Optional<PlanTurnClaim> claimPlanTurn(ClaimPlanTurn command) {
        return transactions.required(c -> {
            Plan before = requirePlan(c, command.planId());
            requireOwnerAdmissionOpen(c, before.ownerThreadId());
            if (before.revision() != command.expectedPlanRevision()
                    || !Objects.equals(before.activeRunId(), command.runId())
                    || !Objects.equals(before.activePlanRevisionId(), command.planRevisionId())) throw conflict();
            ClaimRow existing = queryOne(c, "SELECT ordinal,state FROM plan_turn_claims WHERE run_id=? AND turn_id=?",
                    r -> new ClaimRow(r.getInt(1), r.getString(2)), command.runId(), command.turnId());
            if (existing != null && "CLAIMED".equals(existing.state())) {
                RunBudget existingBudget = queryOne(c,
                        "SELECT status,plan_id,plan_revision_id,turn_budget,turns_used,pause_requested,"
                                + "max_model_rounds,max_tool_calls,wall_budget_millis,"
                                + "used_model_rounds,used_tool_calls,used_active_millis "
                                + "FROM execution_runs WHERE run_id=?", this::mapRunBudget, command.runId());
                if (existingBudget == null) return Optional.empty();
                return Optional.of(new PlanTurnClaim(command.planId(), command.runId(),
                        command.planRevisionId(), command.turnId(), existing.ordinal(),
                        Math.max(0, existingBudget.turnBudget() - existingBudget.turnsUsed()),
                        remaining(existingBudget.maxModelRounds(), existingBudget.usedModelRounds()),
                        remaining(existingBudget.maxToolCalls(), existingBudget.usedToolCalls()),
                        remaining(existingBudget.wallBudgetMillis(), existingBudget.usedActiveMillis())));
            }
            // SETTLED/ABANDONED claim 已经有终态事实，不能让重试用同一 turnId 重新执行。
            if (existing != null) return Optional.empty();
            Integer activeClaim = queryOne(c, "SELECT 1 FROM plan_turn_claims WHERE run_id=? AND state='CLAIMED' LIMIT 1",
                    r -> r.getInt(1), command.runId());
            if (activeClaim != null) return Optional.empty();
            RunBudget budget = queryOne(c,
                    "SELECT status,plan_id,plan_revision_id,turn_budget,turns_used,pause_requested,"
                            + "max_model_rounds,max_tool_calls,wall_budget_millis,"
                            + "used_model_rounds,used_tool_calls,used_active_millis "
                            + "FROM execution_runs WHERE run_id=?", this::mapRunBudget, command.runId());
            if (budget == null || !Objects.equals(budget.planId(), command.planId())
                    || !Objects.equals(budget.planRevision(), command.planRevisionId())
                    || !"RUNNING".equals(budget.status()) || budget.pauseRequested()) return Optional.empty();
            if (budget.turnsUsed() >= budget.turnBudget() || exhausted(budget)) {
                if (before.status() == PlanStatus.EXECUTING) {
                    transitionRun(c, command.runId(), "PAUSED", command.at());
                    planCas(c, before, PlanStatus.PAUSED, before.activePlanRevisionId(), before.activeRunId(), command.at());
                    planEvent(c, command.planId(), before.revision() + 1, "plan_budget_exhausted",
                            command.eventId(), command.idempotencyKey(), at(command.at()));
                }
                return Optional.empty();
            }
            int ordinal = budget.turnsUsed() + 1;
            one(update(c, "UPDATE execution_runs SET turns_used=turns_used+1,updated_at=? "
                    + "WHERE run_id=? AND status='RUNNING' AND pause_requested=0 AND turns_used=?",
                    at(command.at()), command.runId(), budget.turnsUsed()));
            one(update(c, "INSERT INTO plan_turn_claims(run_id,turn_id,ordinal,claimed_at) VALUES(?,?,?,?)",
                    command.runId(), command.turnId(), ordinal, at(command.at())));
            return Optional.of(new PlanTurnClaim(command.planId(), command.runId(), command.planRevisionId(),
                    command.turnId(), ordinal, budget.turnBudget() - ordinal,
                    remaining(budget.maxModelRounds(), budget.usedModelRounds()),
                    remaining(budget.maxToolCalls(), budget.usedToolCalls()),
                    remaining(budget.wallBudgetMillis(), budget.usedActiveMillis())));
        });
    }

    /** 首次 Plan Turn 原子冻结 RuntimeLease limits；turn_budget 是唯一 anti-loop 预算列。 */
    @Override public void initializePlanBudget(InitializePlanBudget command) {
        if (command.maxModelRounds() < 1 || command.maxToolCalls() < 0
                || command.wallBudgetMillis() <= 0 || command.antiLoopTurnBudget() < 1) {
            throw invalid("Plan execution budget is invalid");
        }
        transactions.required(c -> {
            int changed = update(c, "UPDATE execution_runs SET max_model_rounds=?,max_tool_calls=?,"
                            + "wall_budget_millis=?,turn_budget=?,updated_at=? "
                    + "WHERE run_id=? AND plan_id=? AND max_model_rounds IS NULL",
                    command.maxModelRounds(), command.maxToolCalls(), command.wallBudgetMillis(),
                    command.antiLoopTurnBudget(), at(command.at()), command.runId(), command.planId());
            if (changed == 1) return null;
            Integer rounds = queryOne(c, "SELECT max_model_rounds FROM execution_runs WHERE run_id=? AND plan_id=?",
                    r -> r.getInt(1), command.runId(), command.planId());
            if (rounds == null) throw invalid("Plan execution run is unavailable");
            Integer tools = queryOne(c, "SELECT max_tool_calls FROM execution_runs WHERE run_id=?",
                    r -> r.getInt(1), command.runId());
            Long wall = queryOne(c, "SELECT wall_budget_millis FROM execution_runs WHERE run_id=?",
                    r -> r.getLong(1), command.runId());
            Integer antiLoop = queryOne(c, "SELECT turn_budget FROM execution_runs WHERE run_id=?",
                    r -> r.getInt(1), command.runId());
            if (tools == null || wall == null || antiLoop == null
                    || rounds != command.maxModelRounds() || tools != command.maxToolCalls()
                    || wall != command.wallBudgetMillis() || antiLoop != command.antiLoopTurnBudget()) {
                throw invalid("Plan execution budget conflicts with frozen limits");
            }
            return null;
        });
    }

    /** execute 事务内冻结服务端解析的预算，防止首个 Turn 前配置刷新改变 Run 上限。 */
    private void freezePlanBudget(Connection c, ExecutePlan command) throws SQLException {
        one(update(c, "UPDATE execution_runs SET max_model_rounds=?,max_tool_calls=?,"
                        + "wall_budget_millis=?,turn_budget=?,updated_at=? WHERE run_id=? AND plan_id=?",
                command.maxModelRounds(), command.maxToolCalls(), command.wallBudgetMillis(),
                command.turnBudget(), at(command.at()), command.runId(), command.planId()));
    }

    /** 只读返回冻结 Run 的剩余模型、Tool 和墙钟预算，缺失冻结值时 fail closed。 */
    @Override public Optional<PlanRunBudget> readPlanRunBudget(String planId, String runId) {
        return transactions.required(c -> {
            RunBudget budget = queryOne(c,
                    "SELECT status,plan_id,plan_revision_id,turn_budget,turns_used,pause_requested,"
                            + "max_model_rounds,max_tool_calls,wall_budget_millis,"
                            + "used_model_rounds,used_tool_calls,used_active_millis "
                            + "FROM execution_runs WHERE run_id=? AND plan_id=?", this::mapRunBudget, runId, planId);
            if (budget == null || budget.maxModelRounds() == null || budget.maxToolCalls() == null
                    || budget.wallBudgetMillis() == null) return Optional.empty();
            return Optional.of(new PlanRunBudget(planId, runId,
                    remaining(budget.maxModelRounds(), budget.usedModelRounds()),
                    remaining(budget.maxToolCalls(), budget.usedToolCalls()),
                    remaining(budget.wallBudgetMillis(), budget.usedActiveMillis())));
        });
    }

    /**
     * Turn 终态只结算一次：plan_events 的唯一幂等键先挡住重试，再从 usage/tools 账本读取真实计数，
     * 通过 CAS 累加 Run 预算；超额时一并写 pause fence 和 Plan PAUSED，避免进程内计数被重启绕过。
     */
    @Override public void settlePlanTurn(SettlePlanTurn command) {
        transactions.required(c -> {
            if (queryOne(c, "SELECT 1 FROM plan_events WHERE plan_id=? AND idempotency_key=?",
                    r -> r.getInt(1), command.planId(), command.idempotencyKey()) != null) return null;
            Plan before = requirePlan(c, command.planId());
            RunBudget budget = queryOne(c,
                    "SELECT status,plan_id,plan_revision_id,turn_budget,turns_used,pause_requested,"
                            + "max_model_rounds,max_tool_calls,wall_budget_millis,"
                            + "used_model_rounds,used_tool_calls,used_active_millis "
                            + "FROM execution_runs WHERE run_id=?", this::mapRunBudget, command.runId());
            if (budget == null || !Objects.equals(budget.planId(), command.planId())
                    || !Objects.equals(budget.planRevision(), before.activePlanRevisionId())) return null;
            Integer claimed = queryOne(c, "SELECT 1 FROM plan_turn_claims WHERE run_id=? AND turn_id=? AND state='CLAIMED'",
                    r -> r.getInt(1), command.runId(), command.turnId());
            if (claimed == null) return null;
            int modelRounds = Math.toIntExact(scalar(c,
                    "SELECT COUNT(DISTINCT model_round) FROM usage WHERE turn_id=? AND purpose='ASSISTANT'",
                    command.turnId()));
            int toolCalls = Math.toIntExact(scalar(c,
                    "SELECT COUNT(*) FROM tools WHERE turn_id=?", command.turnId()));
            int nextRounds = Math.addExact(budget.usedModelRounds(), modelRounds);
            int nextTools = Math.addExact(budget.usedToolCalls(), toolCalls);
            long nextWall = Math.addExact(budget.usedActiveMillis(), command.activeMillis());
            one(update(c, "UPDATE execution_runs SET used_model_rounds=?,used_tool_calls=?,"
                            + "used_active_millis=?,updated_at=? WHERE run_id=? AND status IN ('RUNNING','PAUSED')",
                    nextRounds, nextTools, nextWall, at(command.at()), command.runId()));
            // Claim 与 Turn usage 在同一事务释放；挂起不会调用该入口，因而保留同一 turnId 继续。
            one(update(c, "UPDATE plan_turn_claims SET state='SETTLED' WHERE run_id=? AND turn_id=? AND state='CLAIMED'",
                    command.runId(), command.turnId()));
            boolean exhausted = budget.maxModelRounds() != null && nextRounds >= budget.maxModelRounds()
                    || budget.maxToolCalls() != null && budget.maxToolCalls() > 0
                    && nextTools >= budget.maxToolCalls()
                    || budget.wallBudgetMillis() != null && nextWall >= budget.wallBudgetMillis()
                    || budget.turnsUsed() >= budget.turnBudget();
            if (exhausted && before.status() == PlanStatus.EXECUTING) {
                transitionRun(c, command.runId(), "PAUSED", command.at());
                Plan current = requirePlan(c, command.planId());
                if (current.status() == PlanStatus.EXECUTING
                        && Objects.equals(current.activeRunId(), command.runId())) {
                    planCas(c, current, PlanStatus.PAUSED, current.activePlanRevisionId(), current.activeRunId(), command.at());
                    planEvent(c, command.planId(), current.revision() + 1, "plan_budget_exhausted",
                            command.eventId(), command.idempotencyKey(), at(command.at()));
                    return null;
                }
            }
            planEvent(c, command.planId(), before.revision(), "plan_turn_settled",
                    command.eventId(), command.idempotencyKey(), at(command.at()));
            return null;
        });
    }

    /**
     * 交互或人工暂停结束一个活动片段时只结算墙钟；模型和 Tool 用量留到 Turn 最终终态统一结算，
     * 避免同一 Turn 在多次恢复间重复累计。
     */
    @Override public void settlePlanTurnActivity(SettlePlanTurnActivity command) {
        transactions.required(c -> {
            if (queryOne(c, "SELECT 1 FROM plan_events WHERE plan_id=? AND idempotency_key=?",
                    r -> r.getInt(1), command.planId(), command.idempotencyKey()) != null) return null;
            Plan before = requirePlan(c, command.planId());
            RunBudget budget = queryOne(c,
                    "SELECT status,plan_id,plan_revision_id,turn_budget,turns_used,pause_requested,"
                            + "max_model_rounds,max_tool_calls,wall_budget_millis,"
                            + "used_model_rounds,used_tool_calls,used_active_millis "
                            + "FROM execution_runs WHERE run_id=?", this::mapRunBudget, command.runId());
            if (budget == null || !Objects.equals(budget.planId(), command.planId())
                    || !Objects.equals(budget.planRevision(), before.activePlanRevisionId())) return null;
            Integer claimed = queryOne(c, "SELECT 1 FROM plan_turn_claims WHERE run_id=? AND turn_id=?",
                    r -> r.getInt(1), command.runId(), command.turnId());
            if (claimed == null) return null;
            long nextWall = Math.addExact(budget.usedActiveMillis(), command.activeMillis());
            one(update(c, "UPDATE execution_runs SET used_active_millis=?,updated_at=? "
                            + "WHERE run_id=? AND status IN ('RUNNING','PAUSED')",
                    nextWall, at(command.at()), command.runId()));
            planEvent(c, command.planId(), before.revision(), "plan_turn_activity_settled",
                    command.eventId(), command.idempotencyKey(), at(command.at()));
            return null;
        });
    }

    /** 先写持久 pause fence，控制 adapter 随后才可取消正在运行的真实 Turn。 */
    @Override public Plan requestPlanPause(RequestPlanPause command) {
        return transactions.required(c -> {
            Plan before = requirePlan(c, command.planId());
            if (before.revision() != command.expectedPlanRevision()
                    || !Objects.equals(before.activeRunId(), command.runId())) throw conflict();
            if (before.status() == PlanStatus.EXECUTING || before.status() == PlanStatus.VERIFYING) {
                one(update(c, "UPDATE execution_runs SET pause_requested=1,updated_at=? "
                                + "WHERE run_id=? AND plan_id=? AND status IN ('RUNNING','VERIFYING','PAUSED')",
                        at(command.at()), command.runId(), command.planId()));
            }
            return before;
        });
    }
    /** reject 回到 DRAFT。 */
    @Override public Plan reject(RejectPlan command) { return planMutation(command.planId(),command.expectedPlanRevision(),command.idempotencyKey(),Set.of("plan_rejected"),c->{Plan before=requirePlan(c,command.planId());if(before.status()!=PlanStatus.AWAITING_APPROVAL)throw invalid("Plan is not awaiting rejection");PlanRevision rev=requireLatest(c,command.planId(),command.planRevisionId(),command.planHash());one(update(c,"INSERT INTO plan_approvals(approval_id,plan_id,plan_revision_id,plan_hash,actor,decision,created_at) VALUES(?,?,?,?,'USER_UI','REJECTED',?)",command.approvalId(),command.planId(),rev.planRevisionId(),rev.planHash(),at(command.at())));planCas(c,before,PlanStatus.DRAFT,null,null,command.at());planEvent(c,command.planId(),before.revision()+1,"plan_rejected",command.eventId(),command.idempotencyKey(),at(command.at()));return requirePlan(c,command.planId());}); }
    /** attach 创建 Goal-owned linked run。 */
    @Override public Goal attachPlan(AttachPlan command) { return goalMutation(command.goalId(),command.expectedGoalRevision(),command.idempotencyKey(),Set.of("plan_attached"),c->{Goal before=requireGoal(c,command.goalId());requireOwnerAdmissionOpen(c,before.ownerThreadId());requireRunReplacementSafe(c,before);Plan plan=requirePlan(c,command.planId());if(!plan.ownerThreadId().equals(before.ownerThreadId()))throw invalid("Goal and Plan owner differ");PlanRevision revision=requireAttachable(c,plan,command.planRevisionId(),command.planHash());int collision=Math.toIntExact(scalar(c,"SELECT COUNT(*) FROM goal_acceptance_criteria g JOIN acceptance_criteria p ON p.criterion_id=g.criterion_id WHERE g.goal_id=? AND g.goal_definition_revision=? AND p.plan_revision_id=?",before.goalId(),before.goalDefinitionRevision(),revision.planRevisionId()));if(collision!=0)throw error(GoalRepositoryException.Code.PLAN_INVALID,"Goal and Plan criterion identities overlap");stopRun(c,before.activeRunId(),command.at());update(c,"UPDATE goal_plan_links SET detached_at=? WHERE goal_id=? AND detached_at IS NULL",at(command.at()),command.goalId());long link=scalar(c,"SELECT COALESCE(MAX(link_revision),0)+1 FROM goal_plan_links WHERE goal_id=?",command.goalId());one(update(c,"INSERT INTO goal_plan_links(goal_id,link_revision,plan_id,plan_revision_id,plan_hash,attached_at) VALUES(?,?,?,?,?,?)",command.goalId(),link,command.planId(),revision.planRevisionId(),revision.planHash(),at(command.at())));insertRun(c,command.runId(),command.goalId(),command.planId(),before.goalDefinitionRevision(),revision.planRevisionId(),revision.planHash(),command.processGeneration(),runStatus(before),at(command.at()));insertSteps(c,command.runId(),revision,command.at());goalCas(c,before,before.status(),before.phase(),command.runId(),before.recoveryRequired(),before.turnsWithoutProgress(),before.repeatedFailureCount(),before.lastFailureSignature(),command.at());goalEvent(c,command.goalId(),before.revision()+1,"CHANGED","plan_attached",command.eventId(),command.idempotencyKey(),at(command.at()));return requireGoal(c,command.goalId());}); }
    /** detach 切回新 Goal-only run。 */
    @Override public Goal detachPlan(DetachPlan command) { return goalMutation(command.goalId(),command.expectedGoalRevision(),command.idempotencyKey(),Set.of("plan_detached"),c->{Goal before=requireGoal(c,command.goalId());requireOwnerAdmissionOpen(c,before.ownerThreadId());requireRunReplacementSafe(c,before);GoalPlanLink link=activeLink(c,command.goalId());if(link==null)throw invalid("Goal has no Plan link");stopRun(c,before.activeRunId(),command.at());one(update(c,"UPDATE goal_plan_links SET detached_at=? WHERE goal_id=? AND link_revision=? AND detached_at IS NULL",at(command.at()),command.goalId(),link.linkRevision()));insertRun(c,command.replacementRunId(),command.goalId(),null,before.goalDefinitionRevision(),null,null,command.processGeneration(),runStatus(before),at(command.at()));goalCas(c,before,before.status(),before.phase(),command.replacementRunId(),before.recoveryRequired(),before.turnsWithoutProgress(),before.repeatedFailureCount(),before.lastFailureSignature(),command.at());goalEvent(c,command.goalId(),before.revision()+1,"CHANGED","plan_detached",command.eventId(),command.idempotencyKey(),at(command.at()));return requireGoal(c,command.goalId());}); }
    /** Goal 状态转换受完成门保护。 */
    @Override public Goal transition(Transition command) { String activity=command.phase().name().toLowerCase(Locale.ROOT);return goalMutation(command.goalId(),command.expectedGoalRevision(),command.idempotencyKey(),Set.of(activity),c->{Goal before=requireGoal(c,command.goalId());if(command.status()==GoalStatus.ACTIVE)requireOwnerAdmissionOpen(c,before.ownerThreadId());if(!GoalStateMachine.mayTransition(before.status(),command.status()))throw invalid("Goal transition is invalid");GoalStateMachine.requireCombination(command.status(),command.phase());if(command.status()==GoalStatus.ACHIEVED)requireCompletion(c,before);if(before.recoveryRequired()&&command.status()==GoalStatus.ACTIVE)throw error(GoalRepositoryException.Code.GOAL_RECOVERY_REQUIRED,"Goal recovery is required");if(before.status()!=command.status())transitionRun(c,before.activeRunId(),switch(command.status()){case ACTIVE->"RUNNING";case PAUSED->"PAUSED";case ACHIEVED->"COMPLETED";case STOPPED->"STOPPED";},command.at());goalCas(c,before,command.status(),command.phase(),before.activeRunId(),command.recoveryRequired(),before.turnsWithoutProgress(),before.repeatedFailureCount(),before.lastFailureSignature(),command.at());goalEvent(c,command.goalId(),before.revision()+1,"CHANGED",activity,command.eventId(),command.idempotencyKey(),at(command.at()));return requireGoal(c,command.goalId());}); }
    /** linked Goal 步骤在同一事务更新状态、挂接本 Run 证据并累计同签名失败，第三次失败必须暂停。 */
    @Override public Goal updateStep(UpdateStep command) { return goalMutation(command.goalId(),command.expectedGoalRevision(),command.idempotencyKey(),Set.of("step_updated"),c->{Goal before=requireGoal(c,command.goalId());GoalPlanLink link=activeLink(c,command.goalId());if(link==null||!before.activeRunId().equals(command.runId()))throw stale();stepCas(c,command.runId(),command.stepId(),command.expectedStatus(),command.status(),command.failureSignature(),command.at());appendClaims(c,command.evidenceClaims(),command.runId(),command.goalId(),link.planId(),before.goalDefinitionRevision(),link.planRevisionId(),command.stepId(),command.at());boolean failed=command.status()==StepStatus.FAILED;boolean retrying=command.status()==StepStatus.READY||command.status()==StepStatus.RUNNING;boolean same=failed&&Objects.equals(command.failureSignature(),before.lastFailureSignature());int failures=failed?(same?Math.min(3,before.repeatedFailureCount()+1):1):(retrying?before.repeatedFailureCount():0);String signature=failed?command.failureSignature():(retrying?before.lastFailureSignature():null);boolean pause=failed&&failures>=3;goalCas(c,before,pause?GoalStatus.PAUSED:GoalStatus.ACTIVE,pause?GoalPhase.NEEDS_ATTENTION:GoalPhase.WORKING,before.activeRunId(),false,0,failures,signature,command.at());goalEvent(c,command.goalId(),before.revision()+1,"CHANGED","step_updated",command.eventId(),command.idempotencyKey(),at(command.at()));return requireGoal(c,command.goalId());}); }
    /** Plan step 只写入步骤与证据；当前 Tool 尚未完成时不能提前进入 VERIFYING。 */
    @Override public Plan updatePlanStep(UpdatePlanStep command) { return planMutation(command.planId(),command.expectedPlanRevision(),command.idempotencyKey(),Set.of("step_updated"),c->{Plan before=requirePlan(c,command.planId());if(before.status()!=PlanStatus.EXECUTING||!Objects.equals(before.activeRunId(),command.runId()))throw invalid("Plan run is not active");stepCas(c,command.runId(),command.stepId(),command.expectedStatus(),command.status(),command.failureSignature(),command.at());appendClaims(c,command.evidenceClaims(),command.runId(),null,command.planId(),null,before.activePlanRevisionId(),command.stepId(),command.at());planCas(c,before,PlanStatus.EXECUTING,before.activePlanRevisionId(),before.activeRunId(),command.at());planEvent(c,command.planId(),before.revision()+1,"step_updated",command.eventId(),command.idempotencyKey(),at(command.at()));return requirePlan(c,command.planId());}); }

    /** Turn 与 Tool 均已结算后才允许验收；CAS 失败或仍有 blocker 时保持原状态。 */
    @Override public Plan beginPlanVerification(BeginPlanVerification command) { return planMutation(command.planId(),command.expectedPlanRevision(),command.idempotencyKey(),Set.of("plan_verifying"),c->{Plan before=requirePlan(c,command.planId());if(before.status()!=PlanStatus.EXECUTING||!Objects.equals(before.activeRunId(),command.runId()))return before;if(planBlockers(c,command.runId(),before.activePlanRevisionId())!=0)return before;transitionRun(c,command.runId(),"VERIFYING",command.at());planCas(c,before,PlanStatus.VERIFYING,before.activePlanRevisionId(),before.activeRunId(),command.at());planEvent(c,command.planId(),before.revision()+1,"plan_verifying",command.eventId(),command.idempotencyKey(),at(command.at()));return requirePlan(c,command.planId());}); }

    /** 验收结果只在 VERIFYING 状态结算；NOT_MET 回到 RUNNING，INCONCLUSIVE 进入可恢复暂停。 */
    @Override public Plan completePlanVerification(CompletePlanVerification command) { return planMutation(command.planId(),command.expectedPlanRevision(),command.idempotencyKey(),Set.of("plan_completed","plan_verification_failed","plan_verification_inconclusive"),c->{Plan before=requirePlan(c,command.planId());if(before.status()!=PlanStatus.VERIFYING||!Objects.equals(before.activeRunId(),command.runId()))throw invalid("Plan verification is stale");if(command.verdict()==GoalModels.EvaluationVerdict.MET&&planBlockers(c,command.runId(),before.activePlanRevisionId())!=0)throw error(GoalRepositoryException.Code.GOAL_EVIDENCE_INCOMPLETE,"Plan completion evidence is incomplete");PlanStatus status=switch(command.verdict()){case MET->PlanStatus.COMPLETED;case NOT_MET->PlanStatus.EXECUTING;case INCONCLUSIVE->PlanStatus.PAUSED;};String runStatus=switch(command.verdict()){case MET->"COMPLETED";case NOT_MET->"RUNNING";case INCONCLUSIVE->"PAUSED";};transitionRun(c,command.runId(),runStatus,command.at());if(command.verdict()==GoalModels.EvaluationVerdict.NOT_MET)one(update(c,"UPDATE execution_runs SET pause_requested=0,updated_at=? WHERE run_id=?",at(command.at()),command.runId()));planCas(c,before,status,before.activePlanRevisionId(),before.activeRunId(),command.at());String activity=switch(command.verdict()){case MET->"plan_completed";case NOT_MET->"plan_verification_failed";case INCONCLUSIVE->"plan_verification_inconclusive";};planEvent(c,command.planId(),before.revision()+1,activity,command.eventId(),command.idempotencyKey(),at(command.at()));return requirePlan(c,command.planId());}); }

    /** pause 只冻结新 Tool admission，保留 run identity 供 Resume 使用。 */
    @Override public Plan pausePlan(PausePlan command) { return planMutation(command.planId(),command.expectedPlanRevision(),command.idempotencyKey(),Set.of("plan_paused"),c->{Plan before=requirePlan(c,command.planId());if((before.status()!=PlanStatus.EXECUTING&&before.status()!=PlanStatus.VERIFYING)||!Objects.equals(before.activeRunId(),command.runId()))throw invalid("Plan is not pausable");transitionRun(c,command.runId(),"PAUSED",command.at());planCas(c,before,PlanStatus.PAUSED,before.activePlanRevisionId(),before.activeRunId(),command.at());planEvent(c,command.planId(),before.revision()+1,"plan_paused",command.eventId(),command.idempotencyKey(),at(command.at()));return requirePlan(c,command.planId());}); }

    /** resume 只恢复已暂停的同一个 run，避免并发点击生成第二个执行 identity。 */
    @Override public Plan resumePlan(ResumePlan command) { return planMutation(command.planId(),command.expectedPlanRevision(),command.idempotencyKey(),Set.of("plan_resumed"),c->{Plan before=requirePlan(c,command.planId());requireOwnerAdmissionOpen(c,before.ownerThreadId());if(before.status()!=PlanStatus.PAUSED||!Objects.equals(before.activeRunId(),command.runId()))throw invalid("Plan is not resumable");transitionRun(c,command.runId(),"RUNNING",command.at());one(update(c,"UPDATE execution_runs SET pause_requested=0,updated_at=? WHERE run_id=?",at(command.at()),command.runId()));planCas(c,before,PlanStatus.EXECUTING,before.activePlanRevisionId(),before.activeRunId(),command.at());planEvent(c,command.planId(),before.revision()+1,"plan_resumed",command.eventId(),command.idempotencyKey(),at(command.at()));return requirePlan(c,command.planId());}); }

    /** stop 终结 run 但保留 active revision，便于审计未知副作用而不允许继续写入。 */
    @Override public Plan stopPlan(StopPlan command) { return planMutation(command.planId(),command.expectedPlanRevision(),command.idempotencyKey(),Set.of("plan_stopped"),c->{Plan before=requirePlan(c,command.planId());if((before.status()!=PlanStatus.EXECUTING&&before.status()!=PlanStatus.VERIFYING&&before.status()!=PlanStatus.PAUSED)||!Objects.equals(before.activeRunId(),command.runId()))throw invalid("Plan is not stoppable");transitionRun(c,command.runId(),"STOPPED",command.at());planCas(c,before,PlanStatus.STOPPED,before.activePlanRevisionId(),before.activeRunId(),command.at());planEvent(c,command.planId(),before.revision()+1,"plan_stopped",command.eventId(),command.idempotencyKey(),at(command.at()));return requirePlan(c,command.planId());}); }
    /** 外部 evidence 精确绑定当前 Goal definition/run。 */
    @Override public Evidence appendEvidence(AppendEvidence command) { return goalMutation(command.goalId(),command.expectedGoalRevision(),command.idempotencyKey(),Set.of("evidence_added"),c->{Evidence replay=queryOne(c,"SELECT * FROM acceptance_evidence WHERE evidence_id=?",this::mapEvidence,command.evidence().evidenceId());if(replay==null)throw invalid("Goal idempotency key was reused");return replay;},c->{Goal before=requireGoal(c,command.goalId());Evidence value=command.evidence();GoalPlanLink link=activeLink(c,command.goalId());if(!Objects.equals(value.goalId(),before.goalId())||!Objects.equals(value.goalDefinitionRevision(),before.goalDefinitionRevision())||!Objects.equals(value.runId(),before.activeRunId())||!Objects.equals(value.planRevisionId(),link==null?null:link.planRevisionId()))throw stale();insertEvidence(c,value);goalCas(c,before,before.status(),before.phase(),before.activeRunId(),before.recoveryRequired(),0,before.repeatedFailureCount(),before.lastFailureSignature(),command.at());goalEvent(c,command.goalId(),before.revision()+1,"ACTIVITY","evidence_added",command.eventId(),command.idempotencyKey(),at(command.at()));return value;}); }
    /** evaluator intent 先持久化。 */
    @Override public Goal requestEvaluation(RequestEvaluation command) { return goalMutation(command.goalId(),command.expectedGoalRevision(),command.idempotencyKey(),Set.of("evaluation_requested"),c->{Goal before=requireGoal(c,command.goalId());requireOwnerAdmissionOpen(c,before.ownerThreadId());GoalPlanLink link=activeLink(c,command.goalId());if(before.status()!=GoalStatus.ACTIVE||!Objects.equals(before.activeRunId(),command.runId())||!Objects.equals(command.planRevisionId(),link==null?null:link.planRevisionId()))throw stale();appendClaims(c,command.evidenceClaims(),command.runId(),command.goalId(),link==null?null:link.planId(),before.goalDefinitionRevision(),command.planRevisionId(),null,command.at());Model model=queryOne(c,"SELECT provider_id,model_id FROM threads WHERE thread_id=?",r->new Model(r.getString(1),r.getString(2)),before.ownerThreadId());one(update(c,"INSERT INTO goal_evaluations(evaluation_id,goal_id,goal_definition_revision,run_id,plan_revision_id,status,process_generation,model_id,provider_id,requested_at) VALUES(?,?,?,?,?,'REQUESTED',?,?,?,?)",command.evaluationId(),command.goalId(),before.goalDefinitionRevision(),command.runId(),command.planRevisionId(),command.processGeneration(),model.modelId,model.providerId,at(command.at())));goalCas(c,before,GoalStatus.ACTIVE,GoalPhase.VERIFYING,before.activeRunId(),false,before.turnsWithoutProgress(),before.repeatedFailureCount(),before.lastFailureSignature(),command.at());goalEvent(c,command.goalId(),before.revision()+1,"ACTIVITY","evaluation_requested",command.eventId(),command.idempotencyKey(),at(command.at()));return requireGoal(c,command.goalId());}); }
    /** evaluator 终态与 Goal phase 原子结算；调用失败或结论不确定时暂停等待处理，不能伪装成未满足。 */
    @Override public Goal completeEvaluation(CompleteEvaluation command) { return goalMutation(command.goalId(),command.expectedGoalRevision(),command.idempotencyKey(),Set.of("evaluation_completed"),c->{Goal before=requireGoal(c,command.goalId());Eval intent=queryOne(c,"SELECT * FROM goal_evaluations WHERE evaluation_id=?",this::mapEval,command.evaluationId());if(intent==null||!intent.goalId.equals(command.goalId())||!intent.runId.equals(before.activeRunId()))throw stale();String state=command.errorCode()==null?"COMPLETED":"FAILED";one(update(c,"UPDATE goal_evaluations SET status=?,verdict=?,criteria_json=?,summary=?,error_code=?,completed_at=? WHERE evaluation_id=? AND status IN ('REQUESTED','RUNNING')",state,command.verdict()==null?null:command.verdict().name(),command.criteriaJson(),command.summary(),command.errorCode(),at(command.at()),command.evaluationId()));boolean attention=command.errorCode()!=null||command.verdict()==EvaluationVerdict.INCONCLUSIVE;goalCas(c,before,attention?GoalStatus.PAUSED:GoalStatus.ACTIVE,attention?GoalPhase.NEEDS_ATTENTION:GoalPhase.WORKING,before.activeRunId(),false,before.turnsWithoutProgress(),before.repeatedFailureCount(),before.lastFailureSignature(),command.at());goalEvent(c,command.goalId(),before.revision()+1,"ACTIVITY","evaluation_completed",command.eventId(),command.idempotencyKey(),at(command.at()));return requireGoal(c,command.goalId());}); }
    /** dispatcher 单飞领取 evaluator intent。 */
    @Override public Optional<EvaluationIntent> claimRequestedEvaluation(String goalId, String runId) { return transactions.required(c->{Eval row=queryOne(c,"SELECT * FROM goal_evaluations WHERE goal_id=? AND run_id=? AND status='REQUESTED' ORDER BY requested_at LIMIT 1",this::mapEval,goalId,runId);if(row==null||update(c,"UPDATE goal_evaluations SET status='RUNNING' WHERE evaluation_id=? AND status='REQUESTED'",row.evaluationId)!=1)return Optional.empty();return Optional.of(intent(row));}); }
    /** Tool 执行前写 identity。 */
    @Override public ToolAttempt prepareToolAttempt(PrepareToolAttempt command) { return transactions.required(c->{ToolAttempt v=command.attempt();Run owner=queryOne(c,"SELECT goal_id,plan_id,goal_definition_revision,plan_revision_id FROM execution_runs WHERE run_id=?",this::mapRun,v.runId());if(owner==null||!Objects.equals(owner.goalId,v.goalId())||!Objects.equals(owner.planId,v.planId())||!Objects.equals(owner.definition,v.goalDefinitionRevision())||!Objects.equals(owner.planRevision,v.planRevisionId()))throw stale();one(update(c,"INSERT INTO goal_tool_attempts(tool_attempt_id,goal_id,plan_id,goal_definition_revision,run_id,plan_revision_id,step_id,attempt,turn_id,call_id,process_generation,side_effect,state,request_digest,result_digest,prepared_at,started_at,completed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",v.toolAttemptId(),v.goalId(),v.planId(),v.goalDefinitionRevision(),v.runId(),v.planRevisionId(),v.stepId(),v.attempt(),v.turnId(),v.callId(),v.processGeneration(),v.sideEffect()?1:0,v.state().name(),v.requestDigest(),v.resultDigest(),at(v.preparedAt()),v.startedAt()==null?null:at(v.startedAt()),v.completedAt()==null?null:at(v.completedAt())));return v;}); }
    /** PREPARED -> STARTED 是副作用 fence。 */
    @Override public ToolAttempt startToolAttempt(String toolAttemptId, Instant at) { return transactions.required(c->{one(update(c,"UPDATE goal_tool_attempts SET state='STARTED',started_at=? WHERE tool_attempt_id=? AND state='PREPARED'",at(at),toolAttemptId));return requireTool(c,toolAttemptId);}); }
    /**
     * Tool terminal、evidence 与可能的 Goal 完成必须位于同一事务：evaluator 可能先于当前 Tool 返回
     * MET，也可能后返回；任一后到者都复用同一完成门，避免崩溃窗口留下永久 WORKING Goal。
     * 相同终态回执按 attempt identity 幂等重放，不重复 evidence、revision 或事件。
     */
    @Override public ToolAttempt settleToolAttempt(SettleToolAttempt command) {
        return transactions.required(c -> {
            ToolAttempt before = requireTool(c, command.toolAttemptId());
            if (before.state() == ToolAttemptState.SUCCEEDED || before.state() == ToolAttemptState.FAILED) {
                if (before.state() != command.state()
                        || !Objects.equals(before.resultDigest(), command.resultDigest())) {
                    throw invalid("Tool settlement conflicts with terminal attempt");
                }
                return before;
            }
            if (before.state() != ToolAttemptState.STARTED) {
                throw invalid("Tool attempt is not started");
            }
            one(update(c, "UPDATE goal_tool_attempts SET state=?,result_digest=?,completed_at=? "
                            + "WHERE tool_attempt_id=? AND state='STARTED'",
                    command.state().name(), command.resultDigest(), at(command.at()), command.toolAttemptId()));
            if (command.evidence() != null) insertEvidence(c, command.evidence());
            else if (command.state() == ToolAttemptState.SUCCEEDED) {
                insertEvidence(c, new Evidence("evidence_" + before.toolAttemptId(), before.goalId(),
                        before.planId(), before.goalDefinitionRevision(), before.runId(), before.planRevisionId(),
                        null, before.stepId(), EvidenceSource.TOOL_RESULT, before.callId(),
                        "真实 Tool 调用已成功结算", command.resultDigest(), command.at(), command.at()));
            }
            completeGoalAfterToolSettlement(c, before, command.at());
            return requireTool(c, command.toolAttemptId());
        });
    }
    /** 有界读取旧 generation attempts。 */
    @Override public List<ToolAttempt> listUnsettledToolAttempts(long generation, int limit) { return transactions.required(c->queryList(c,"SELECT * FROM goal_tool_attempts WHERE process_generation<>? AND state IN ('PREPARED','STARTED') ORDER BY prepared_at,tool_attempt_id LIMIT ?",this::mapTool,generation,limit)); }
    /** 有界读取旧 generation evaluator intents。 */
    @Override public List<EvaluationIntent> listUnsettledEvaluations(long generation, int limit) { return transactions.required(c->queryList(c,"SELECT * FROM goal_evaluations WHERE process_generation<>? AND status IN ('REQUESTED','RUNNING') ORDER BY requested_at,evaluation_id LIMIT ?",this::mapEval,generation,limit).stream().map(this::intent).toList()); }
    /** 旧 evaluator 失败关闭。 */
    @Override public Optional<Goal> recoverEvaluation(EvaluationIntent intent, long generation, Instant at) { return transactions.required(c->{if(update(c,"UPDATE goal_evaluations SET status='FAILED',error_code='EVALUATOR_RESULT_UNKNOWN',completed_at=? WHERE evaluation_id=? AND process_generation<>? AND status IN ('REQUESTED','RUNNING')",at(at),intent.evaluationId(),generation)!=1)return Optional.empty();Goal goal=requireGoal(c,intent.goalId());if(goal.status()==GoalStatus.ACTIVE&&Objects.equals(goal.activeRunId(),intent.runId()))goalCas(c,goal,GoalStatus.PAUSED,GoalPhase.NEEDS_ATTENTION,goal.activeRunId(),true,goal.turnsWithoutProgress(),goal.repeatedFailureCount(),goal.lastFailureSignature(),at);return Optional.of(requireGoal(c,intent.goalId()));}); }
    /**
     * 进程退出后的安全 run 保留原 revision、run 和累计预算并进入 PAUSED；未知副作用进入
     * STOPPED recovery blocker，防止用户在无法证明外部结果时盲目重放。
     */
    @Override public Plan recoverPlanExecution(RecoverPlanExecution command) {
        return planMutation(command.planId(), command.expectedPlanRevision(), command.idempotencyKey(),
                Set.of("plan_recovery_required", "plan_execution_recovered"), c -> {
                    Plan before = requirePlan(c, command.planId());
                    if ((before.status() != PlanStatus.EXECUTING && before.status() != PlanStatus.VERIFYING)
                            || !Objects.equals(before.activeRunId(), command.runId())) return before;
                    String runStatus = command.unsafe() ? "STOPPED" : "PAUSED";
                    transitionRun(c, command.runId(), runStatus, command.at());
                    PlanStatus status = command.unsafe() ? PlanStatus.STOPPED : PlanStatus.PAUSED;
                    planCas(c, before, status, before.activePlanRevisionId(), before.activeRunId(), command.at());
                    planEvent(c, command.planId(), before.revision() + 1,
                            command.unsafe() ? "plan_recovery_required" : "plan_execution_recovered",
                            command.eventId(), command.idempotencyKey(), at(command.at()));
                    return requirePlan(c, command.planId());
                });
    }
    /** 旧 generation 的执行 run 只暴露恢复所需 identity，未知判断在同一 SQLite 查询完成。 */
    @Override public List<PlanExecutionRecovery> listUnsettledPlanExecutions(long generation, int limit) {
        if (generation < 1 || limit < 1) throw new IllegalArgumentException("invalid recovery page");
        return transactions.required(c -> queryList(c,
                "SELECT p.plan_id,p.revision,p.active_run_id,p.active_plan_revision_id,"
                        + "CASE WHEN EXISTS (SELECT 1 FROM goal_tool_attempts a "
                        + "WHERE a.run_id=r.run_id AND (a.state='UNKNOWN' "
                        + "OR (a.state='STARTED' AND a.side_effect=1))) "
                        + "OR EXISTS (SELECT 1 FROM plan_evaluation_requests e "
                        + "WHERE e.run_id=r.run_id AND e.outcome IN ('RUNNING','UNKNOWN')) "
                        + "THEN 1 ELSE 0 END AS unsafe "
                        + "FROM plans p JOIN execution_runs r ON r.run_id=p.active_run_id "
                        + "WHERE r.plan_id=p.plan_id AND r.process_generation<>? "
                        + "AND p.status IN ('EXECUTING','VERIFYING') "
                        + "ORDER BY p.updated_at,p.plan_id LIMIT ?",
                r -> new PlanExecutionRecovery(r.getString("plan_id"), r.getLong("revision"),
                        r.getString("active_run_id"), r.getString("active_plan_revision_id"),
                        r.getInt("unsafe") == 1), generation, limit));
    }
    /** 没有对应 Turn 的 claim 是尚未越过 admission 边界的 intent，终态化后才能创建新 claim。 */
    @Override public void settleOrphanedPlanTurnClaims(String runId, Instant at) {
        transactions.required(c -> {
            update(c, "UPDATE plan_turn_claims SET state='SETTLED' "
                            + "WHERE run_id=? AND state='CLAIMED' "
                            + "AND NOT EXISTS (SELECT 1 FROM turns t WHERE t.turn_id=plan_turn_claims.turn_id)",
                    runId);
            return null;
        });
    }
    /** owner 唯一非终态 Goal。 */
    @Override public Optional<Goal> findActiveGoalByOwner(String ownerThreadId) { return transactions.required(c->Optional.ofNullable(queryOne(c,"SELECT * FROM goals WHERE owner_thread_id=? AND status IN ('ACTIVE','PAUSED')",this::mapGoal,ownerThreadId))); }
    /** 只按 owner 查询正在执行的独立 Plan，避免 continuation 错把任意最新 Plan 当作活动 Run。 */
    @Override public Optional<Plan> findExecutingPlanByOwner(String ownerThreadId) { return transactions.required(c->Optional.ofNullable(queryOne(c,"SELECT * FROM plans WHERE owner_thread_id=? AND status='EXECUTING' ORDER BY updated_at DESC LIMIT 1",this::mapPlan,ownerThreadId))); }
    /** 内部 Turn binding 从不可变 context 读取；Goal continuation 的旧/已释放 fencing lease 直接失效。 */
    @Override public Optional<InternalTurnBinding> findInternalTurnBinding(String turnId) {
        return transactions.required(c -> {
            InternalContext row = queryOne(c,
                    "SELECT origin,context_json FROM turn_internal_context WHERE turn_id=?",
                    r -> new InternalContext(r.getString(1), r.getString(2)), turnId);
            if (row == null) return Optional.empty();
            try {
                com.fasterxml.jackson.databind.JsonNode root = json.readTree(row.contextJson());
                String origin = row.origin();
                if (root == null || !root.isObject() || !origin.equals(root.path("kind").textValue())) {
                    throw invalid("Persisted internal Turn binding is invalid");
                }
                String goalId = text(root, "goalId");
                String planId = text(root, "planId");
                String runId = text(root, "runId");
                Long definition = number(root, "goalDefinitionRevision");
                String planRevisionId = text(root, "planRevisionId");
                String planHash = text(root, "planHash");
                Long fencingToken = number(root, "fencingToken");
                InternalTurnBinding binding = new InternalTurnBinding(turnId, origin, goalId, planId, runId,
                        definition, planRevisionId, planHash, fencingToken);
                if ("GOAL_CONTINUATION".equals(origin)) {
                    Long currentFence = queryOne(c,
                            "SELECT fencing_token FROM goal_continuation_leases "
                                    + "WHERE goal_id=? AND state='HELD' ORDER BY fencing_token DESC LIMIT 1",
                            r -> r.getLong(1), goalId);
                    if (currentFence == null) return Optional.empty();
                    // Immutable context 保留创建时 fence；恢复后返回当前唯一 HELD fence，旧 owner 仍无法续约。
                    binding = new InternalTurnBinding(binding.turnId(), binding.origin(), binding.goalId(),
                            binding.planId(), binding.runId(), binding.goalDefinitionRevision(),
                            binding.planRevisionId(), binding.planHash(), currentFence);
                }
                return Optional.of(binding);
            } catch (GoalRepositoryException failure) {
                throw failure;
            } catch (JsonProcessingException | IllegalArgumentException failure) {
                throw invalid("Persisted internal Turn binding is invalid");
            }
        });
    }

    /**
     * 在单个 SQLite 读事务内严格确认 Goal continuation 的原 Turn、Interaction 和副作用边界；
     * 恢复 hook 只有拿到该候选后才能申请新 fence，避免从 owner 最新状态猜测旧执行位置。
     */
    @Override
    public Optional<ContinuationRecoveryCandidate> findContinuationRecovery(String goalId, String runId) {
        GoalModels.requireId(goalId, "goalId");
        GoalModels.requireId(runId, "runId");
        return transactions.required(c -> {
            Goal goal = requireGoal(c, goalId);
            if (goal.status() == GoalStatus.ACHIEVED || goal.status() == GoalStatus.STOPPED
                    || !runId.equals(goal.activeRunId())) return Optional.empty();
            ContinuationRecoveryRow row = queryOne(c,
                    "SELECT t.turn_id,t.thread_id,h.revision,t.mutation_version,i.request_id,i.status,c.origin,c.context_json "
                            + "FROM turns t JOIN threads h ON h.thread_id=t.thread_id JOIN interaction_requests i ON i.turn_id=t.turn_id "
                            + "JOIN turn_internal_context c ON c.turn_id=t.turn_id "
                            + "WHERE c.origin='GOAL_CONTINUATION' AND t.state='SUSPENDED' "
                            + "AND t.cancel_requested_at IS NULL AND i.goal_id=? AND i.run_id=? "
                            + "AND i.status IN ('PENDING','ANSWERED') "
                            + "AND NOT EXISTS (SELECT 1 FROM goal_tool_attempts a WHERE a.run_id=? "
                            + "AND (a.state='UNKNOWN' OR (a.state='STARTED' AND a.side_effect=1))) "
                            + "AND NOT EXISTS (SELECT 1 FROM goal_evaluations e WHERE e.run_id=? "
                            + "AND e.status IN ('REQUESTED','RUNNING')) "
                            + "ORDER BY t.updated_at DESC,t.turn_id DESC LIMIT 1",
                    r -> new ContinuationRecoveryRow(r.getString("turn_id"), r.getString("thread_id"),
                            r.getLong("revision"), r.getLong("mutation_version"), r.getString("request_id"),
                            r.getString("status"), r.getString("origin"), r.getString("context_json")),
                    goalId, runId, runId, runId);
            if (row == null) return Optional.empty();
            try {
                com.fasterxml.jackson.databind.JsonNode root = json.readTree(row.contextJson());
                if (root == null || !root.isObject() || !row.origin().equals(root.path("kind").textValue())) {
                    throw invalid("Persisted continuation context is invalid");
                }
                InternalTurnBinding binding = new InternalTurnBinding(row.turnId(), row.origin(),
                        text(root, "goalId"), text(root, "planId"), text(root, "runId"),
                        number(root, "goalDefinitionRevision"), text(root, "planRevisionId"),
                        text(root, "planHash"), number(root, "fencingToken"));
                if (!goalId.equals(binding.goalId()) || !runId.equals(binding.runId())
                        || !row.threadId().equals(goal.ownerThreadId())
                        || binding.goalDefinitionRevision() == null
                        || binding.goalDefinitionRevision() != goal.goalDefinitionRevision()) {
                    throw invalid("Persisted continuation identity is stale");
                }
                return Optional.of(new ContinuationRecoveryCandidate(goalId, row.threadId(), row.turnId(), runId,
                        row.threadRevision(), row.turnMutationVersion(), row.requestId(),
                        InteractionStatus.valueOf(row.status()), binding));
            } catch (GoalRepositoryException failure) {
                throw failure;
            } catch (JsonProcessingException | IllegalArgumentException failure) {
                throw invalid("Persisted continuation recovery candidate is invalid");
            }
        });
    }
    /** 仅从持久 PLAN_EXECUTION context 定位活动 Turn，支持重启后的 pause/stop fencing。 */
    @Override public Optional<InternalTurnBinding> findPlanTurnBinding(String planId, String runId) {
        String turnId = transactions.required(c -> {
            String candidateTurnId = queryOne(c,
                    "SELECT c.turn_id FROM turn_internal_context c JOIN turns t ON t.turn_id=c.turn_id "
                            + "WHERE c.origin='PLAN_EXECUTION' "
                            + "AND json_extract(c.context_json,'$.planId')=? "
                            + "AND json_extract(c.context_json,'$.runId')=? "
                            + "AND t.state IN ('QUEUED','RUNNING','WAITING_APPROVAL','SUSPENDED') "
                            + "ORDER BY t.updated_at DESC,t.turn_id DESC LIMIT 1",
                    r -> r.getString(1), planId, runId);
            return candidateTurnId;
        });
        if (turnId == null) return Optional.empty();
        // 重新通过统一解析器校验 context 闭集，避免索引查询旁路 hash/revision 约束。
        return findInternalTurnBinding(turnId);
    }
    /** 唯一 partial index 已保证 owner 至多一个非终态 Plan。 */
    @Override public Optional<Plan> findActivePlanByOwner(String ownerThreadId) { return transactions.required(c->Optional.ofNullable(queryOne(c,"SELECT * FROM plans WHERE owner_thread_id=? AND status IN ('DRAFT','AWAITING_APPROVAL','APPROVED','EXECUTING','VERIFYING','PAUSED')",this::mapPlan,ownerThreadId))); }
    /** Goal 终态时间线。 */
    @Override public List<TerminalActivity> listTerminalActivities(String ownerThreadId, int limit) { return transactions.required(c->queryList(c,"SELECT g.*,e.event_sequence,e.created_at event_at FROM goals g JOIN goal_events e ON e.event_sequence=(SELECT MAX(x.event_sequence) FROM goal_events x WHERE x.goal_id=g.goal_id AND lower(json_extract(x.payload_json,'$.activity'))=lower(g.status)) WHERE g.owner_thread_id=? AND g.status IN ('ACHIEVED','STOPPED') ORDER BY e.event_sequence DESC LIMIT ?",r->new TerminalActivity(r.getString("goal_id"),r.getString("owner_thread_id"),r.getString("objective"),GoalStatus.valueOf(r.getString("status")),r.getLong("revision"),r.getLong("event_sequence"),Instant.parse(r.getString("event_at"))),ownerThreadId,limit)); }
    /** Goal event keyset 页。 */
    @Override public ReadPage<Event> listEvents(String goalId, long afterSequence, int limit) { return transactions.required(c->{Goal goal=requireGoal(c,goalId);List<Event> items=queryList(c,"SELECT * FROM goal_events WHERE goal_id=? AND event_sequence>? ORDER BY event_sequence LIMIT ?",this::mapEvent,goalId,afterSequence,limit);return new ReadPage<>(goal.revision(),scalar(c,"SELECT COALESCE(MAX(event_sequence),0) FROM goal_events WHERE goal_id=?",goalId),items);}); }
    /** Plan event 页使用自己的 sequence，不能让 Goal 查询水位掩盖执行状态变化。 */
    @Override public ReadPage<PlanEvent> listPlanEvents(String planId, long afterSequence, int limit) { return transactions.required(c->{Plan plan=requirePlan(c,planId);List<PlanEvent> items=queryList(c,"SELECT event_sequence,event_id,plan_id,plan_revision,activity,created_at FROM plan_events WHERE plan_id=? AND event_sequence>? ORDER BY event_sequence LIMIT ?",this::mapPlanEvent,planId,afterSequence,limit);return new ReadPage<>(plan.revision(),scalar(c,"SELECT COALESCE(MAX(event_sequence),0) FROM plan_events WHERE plan_id=?",planId),items);}); }
    /** Plan revision keyset 页。 */
    @Override public ReadPage<PlanRevision> listPlanRevisions(String planId, long afterRevisionNumber, int limit) { return transactions.required(c->{Plan plan=requirePlan(c,planId);List<PlanRevision> items=queryList(c,"SELECT * FROM plan_revisions WHERE plan_id=? AND revision_number>? ORDER BY revision_number LIMIT ?",this::mapRevision,planId,afterRevisionNumber,limit);return new ReadPage<>(plan.revision(),scalar(c,"SELECT COALESCE(MAX(event_sequence),0) FROM plan_events WHERE plan_id=?",planId),items);}); }
    /** 当前 Goal run evidence。 */
    @Override public List<Evidence> listEvidence(String goalId, String runId, int limit) { return transactions.required(c->queryList(c,"SELECT * FROM acceptance_evidence WHERE goal_id=? AND run_id=? ORDER BY created_at,evidence_id LIMIT ?",this::mapEvidence,goalId,runId,limit)); }
    /** 独立 Plan evaluator 只消费当前 run/revision，禁止把旧版本 evidence 带入完成门。 */
    @Override public List<Evidence> listPlanEvidence(String planId, String runId, String planRevisionId, int limit) { return transactions.required(c->queryList(c,"SELECT * FROM acceptance_evidence WHERE plan_id=? AND run_id=? AND plan_revision_id=? ORDER BY created_at,evidence_id LIMIT ?",this::mapEvidence,planId,runId,planRevisionId,limit)); }
    /** Plan evidence keyset 页在同一事务返回 Plan revision/event 水位，供断线后精确对账。 */
    @Override public ReadPage<Evidence> listPlanEvidencePage(String planId, String runId, String planRevisionId,
                                                               String afterCreatedAt, String afterEvidenceId, int limit) { return transactions.required(c->{Plan plan=requirePlan(c,planId);String sql="SELECT * FROM acceptance_evidence WHERE plan_id=? AND run_id=? AND plan_revision_id=?"+(afterCreatedAt==null?"":" AND (created_at>? OR (created_at=? AND evidence_id>?))")+" ORDER BY created_at,evidence_id LIMIT ?";List<Evidence> items=afterCreatedAt==null?queryList(c,sql,this::mapEvidence,planId,runId,planRevisionId,limit):queryList(c,sql,this::mapEvidence,planId,runId,planRevisionId,afterCreatedAt,afterCreatedAt,afterEvidenceId,limit);return new ReadPage<>(plan.revision(),scalar(c,"SELECT COALESCE(MAX(event_sequence),0) FROM plan_events WHERE plan_id=?",planId),items);}); }
    /** definition/link 精确 evidence 页。 */
    @Override public ReadPage<Evidence> listEvidencePage(String goalId, long definitionRevision,String planRevisionId,String afterCreatedAt,String afterEvidenceId,int limit) { return transactions.required(c->{Goal goal=requireGoal(c,goalId);String sql="SELECT * FROM acceptance_evidence WHERE goal_id=? AND goal_definition_revision=? AND plan_revision_id IS ?"+(afterCreatedAt==null?"":" AND (created_at>? OR (created_at=? AND evidence_id>?))")+" ORDER BY created_at,evidence_id LIMIT ?";List<Evidence> items=afterCreatedAt==null?queryList(c,sql,this::mapEvidence,goalId,definitionRevision,planRevisionId,limit):queryList(c,sql,this::mapEvidence,goalId,definitionRevision,planRevisionId,afterCreatedAt,afterCreatedAt,afterEvidenceId,limit);return new ReadPage<>(goal.revision(),scalar(c,"SELECT COALESCE(MAX(event_sequence),0) FROM goal_events WHERE goal_id=?",goalId),items);}); }
    /** SQLite partial unique index 保证 lease 单飞。 */
    @Override public Optional<ContinuationLease> tryAcquireLease(AcquireLease command) { return transactions.required(c->{Goal goal=requireGoal(c,command.goalId());requireOwnerAdmissionOpen(c,goal.ownerThreadId());if(goal.status()!=GoalStatus.ACTIVE||goal.recoveryRequired()||!canAcquireContinuation(c,goal))return Optional.empty();long token=scalar(c,"SELECT COALESCE(MAX(fencing_token),0)+1 FROM goal_continuation_leases WHERE goal_id=?",command.goalId());try{one(update(c,"INSERT INTO goal_continuation_leases(lease_id,goal_id,process_generation,fencing_token,state,acquired_at,heartbeat_at) VALUES(?,?,?,?,'HELD',?,?)",command.leaseId(),command.goalId(),command.processGeneration(),token,at(command.at()),at(command.at())));}catch(SQLException failure){if(failure.getMessage()!=null&&failure.getMessage().contains("UNIQUE"))return Optional.empty();throw failure;}return Optional.of(requireLease(c,command.leaseId()));}); }
    /** 等待问题只允许为原挂起 Turn 恢复租约，不使普通调度器越过回答边界。 */
    private static boolean canAcquireContinuation(Connection c, Goal goal) throws SQLException {
        if (goal.phase() == GoalPhase.WORKING) return true;
        return goal.phase() == GoalPhase.WAITING_INPUT && scalar(c,
                "SELECT COUNT(*) FROM interaction_requests i JOIN turns t ON t.turn_id=i.turn_id "
                        + "WHERE i.goal_id=? AND i.run_id=? AND i.status IN ('PENDING','ANSWERED') "
                        + "AND t.state='SUSPENDED' AND t.cancel_requested_at IS NULL",
                goal.goalId(), goal.activeRunId()) > 0;
    }

    /** heartbeat 只命中 fencing owner。 */
    @Override public Optional<ContinuationLease> heartbeatLease(String goalId, String leaseId, long token, Instant at) { return transactions.required(c->update(c,"UPDATE goal_continuation_leases SET heartbeat_at=? WHERE goal_id=? AND lease_id=? AND fencing_token=? AND state='HELD'",at(at),goalId,leaseId,token)==1?Optional.of(requireLease(c,leaseId)):Optional.empty()); }
    /** release/abandon 持久终态。 */
    @Override public Optional<ContinuationLease> releaseLease(String goalId, String leaseId, long token, boolean abandoned, Instant at) { return transactions.required(c->update(c,"UPDATE goal_continuation_leases SET state=?,heartbeat_at=?,released_at=? WHERE goal_id=? AND lease_id=? AND fencing_token=? AND state='HELD'",abandoned?"ABANDONED":"RELEASED",at(at),at(at),goalId,leaseId,token)==1?Optional.of(requireLease(c,leaseId)):Optional.empty()); }
    /** 有界读取旧 generation lease。 */
    @Override public List<ContinuationLease> listHeldLeases(long generation, int limit) { return transactions.required(c->queryList(c,"SELECT * FROM goal_continuation_leases WHERE process_generation<>? AND state='HELD' ORDER BY acquired_at,lease_id LIMIT ?",this::mapLease,generation,limit)); }
    /** 第三次无进展进入 attention。 */
    @Override public Goal recordContinuationNoProgress(String goalId,long expected,String eventId,String key,Instant at) { return goalMutation(goalId,expected,key,Set.of("continuation_no_progress"),c->{Goal before=requireGoal(c,goalId);int count=Math.min(3,before.turnsWithoutProgress()+1);boolean pause=count>=3;goalCas(c,before,pause?GoalStatus.PAUSED:GoalStatus.ACTIVE,pause?GoalPhase.NEEDS_ATTENTION:GoalPhase.WORKING,before.activeRunId(),false,count,before.repeatedFailureCount(),before.lastFailureSignature(),at);goalEvent(c,goalId,before.revision()+1,"ACTIVITY","continuation_no_progress",eventId,key,at(at));return requireGoal(c,goalId);}); }
    /** Tool approval phase 不消耗 Goal mutation revision。 */
    @Override public Goal projectContinuationPhase(ProjectContinuationPhase command) { return transactions.required(c->{String expectedActivity=command.phase()==GoalPhase.WAITING_APPROVAL?"tool_approval_requested":"tool_approval_resolved";String replay=queryOne(c,"SELECT json_extract(payload_json,'$.activity') FROM goal_events WHERE goal_id=? AND idempotency_key=?",r->r.getString(1),command.goalId(),command.idempotencyKey());if(replay!=null){if(!expectedActivity.equals(replay))throw invalid("Goal idempotency key was reused");return requireGoal(c,command.goalId());}int changed=update(c,"UPDATE goals SET phase=?,updated_at=? WHERE goal_id=? AND revision=? AND status='ACTIVE' AND phase=?",command.phase().name(),at(command.at()),command.goalId(),command.expectedGoalRevision(),command.expectedPhase().name());if(changed!=1)throw invalid("Goal continuation phase is stale");goalEvent(c,command.goalId(),command.expectedGoalRevision(),"ACTIVITY",expectedActivity,command.eventId(),command.idempotencyKey(),at(command.at()));return requireGoal(c,command.goalId());}); }

    /** Plan 幂等重放必须属于同一操作类型，禁止一个 key 跨批准、执行或恢复边界复用。 */
    private <T> T planMutation(String planId, long expected, String key, Set<String> replayActivities,
                               SqlWork<T> work) {
        return transactions.required(c -> {
            String activity = queryOne(c,
                    "SELECT activity FROM plan_events WHERE plan_id=? AND idempotency_key=?",
                    r -> r.getString(1), planId, key);
            if (activity != null) {
                if (!replayActivities.contains(activity)) throw invalid("Plan idempotency key was reused");
                @SuppressWarnings("unchecked") T replay = (T) requirePlan(c, planId);
                return replay;
            }
            requirePlanRevision(c, planId, expected);
            return work.run(c);
        });
    }
    /** Goal mutation 重放必须属于同一 activity，避免 pause key 被复用于 stop、input 或 evidence。 */
    private Goal goalMutation(String goalId, long expected, String key, Set<String> replayActivities,
                              SqlWork<Goal> work) {
        return goalMutation(goalId, expected, key, replayActivities, c -> requireGoal(c, goalId), work);
    }

    /** 非 Goal 返回值提供精确 replay reader；找不到首次结果时按 key 碰撞失败关闭。 */
    private <T> T goalMutation(String goalId, long expected, String key, Set<String> replayActivities,
                               SqlWork<T> replayReader, SqlWork<T> work) {
        return transactions.required(c -> {
            String activity = queryOne(c,
                    "SELECT json_extract(payload_json,'$.activity') FROM goal_events "
                            + "WHERE goal_id=? AND idempotency_key=?",
                    r -> r.getString(1), goalId, key);
            if (activity != null) {
                if (!replayActivities.contains(activity)) throw invalid("Goal idempotency key was reused");
                return replayReader.run(c);
            }
            Goal value = requireGoal(c, goalId);
            if (value.revision() != expected) throw conflict();
            return work.run(c);
        });
    }
    /** Goal CAS 以旧 revision 为条件同时持久化状态、Run 与熔断计数，防止并发 mutation 拆分事实。 */
    private static void goalCas(Connection c,Goal before,GoalStatus status,GoalPhase phase,String run,boolean recovery,int noProgress,int failures,String signature,Instant at)throws SQLException{GoalStateMachine.requireCombination(status,phase);one(update(c,"UPDATE goals SET status=?,phase=?,revision=revision+1,active_run_id=?,progress_turns_without_change=?,repeated_failure_count=?,last_failure_signature=?,recovery_required=?,updated_at=? WHERE goal_id=? AND revision=? AND status NOT IN ('ACHIEVED','STOPPED')",status.name(),phase.name(),run,noProgress,failures,signature,recovery?1:0,at(at),before.goalId(),before.revision()));}
    /** 活动 Plan link 依赖部分唯一索引保持单值，读取层不对多行结果做任意取舍。 */
    private static GoalPlanLink activeLink(Connection c,String goalId)throws SQLException{return queryOne(c,"SELECT * FROM goal_plan_links WHERE goal_id=? AND detached_at IS NULL",r->new GoalPlanLink(r.getString("goal_id"),r.getString("plan_id"),r.getString("plan_revision_id"),r.getString("plan_hash"),r.getLong("link_revision"),Instant.parse(r.getString("attached_at"))),goalId);}
    /** Run 状态转换在同一事务写入终态时间，恢复逻辑不会观察到终态与完成时间分离。 */
    private static void transitionRun(Connection c,String run,String status,Instant at)throws SQLException{if(run==null)return;one(update(c,"UPDATE execution_runs SET status=?,completed_at=CASE WHEN ? IN ('COMPLETED','STOPPED') THEN ? ELSE NULL END,updated_at=? WHERE run_id=?",status,status,at(at),at(at),run));}
    /** 普通执行失败只暂停同一 Run；仅未创建 Turn 的 admission claim 可安全放弃，挂起 Turn 必须保留。 */
    private static void settleOrAbandonPlanClaims(Connection c, String runId) throws SQLException {
        update(c, "UPDATE plan_turn_claims SET state=CASE "
                        + "WHEN EXISTS(SELECT 1 FROM turns t WHERE t.turn_id=plan_turn_claims.turn_id "
                        + "AND t.state IN ('QUEUED','RUNNING','WAITING_APPROVAL','SUSPENDED')) THEN 'CLAIMED' "
                        + "WHEN EXISTS(SELECT 1 FROM turns t WHERE t.turn_id=plan_turn_claims.turn_id) THEN 'SETTLED' "
                        + "ELSE 'ABANDONED' END WHERE run_id=? AND state='CLAIMED'", runId);
    }
    /** 替换 Run 前先停止旧 identity，确保旧 continuation 或 Tool 回执无法继续写入当前聚合。 */
    private static void stopRun(Connection c,String run,Instant at)throws SQLException{if(run!=null)transitionRun(c,run,"STOPPED",at);}
    /** 稳定步骤 ID 只能经过 PlanPolicy 允许的状态边迁移，CAS 失败按并发冲突处理而非覆盖。 */
    private static void stepCas(Connection c,String run,String step,StepStatus expected,StepStatus target,String failure,Instant at)throws SQLException{if(!PlanPolicy.mayTransition(expected,target))throw invalid("Plan step transition is invalid");String completed=switch(target){case SUCCEEDED,FAILED,SKIPPED->at(at);default->null;};one(update(c,"UPDATE plan_step_executions SET status=?,attempt_count=attempt_count+CASE WHEN ?='RUNNING' THEN 1 ELSE 0 END,failure_signature=?,started_at=CASE WHEN ?='RUNNING' THEN ? ELSE started_at END,completed_at=?,updated_at=? WHERE run_id=? AND step_id=? AND status=?",target.name(),target.name(),failure,target.name(),at(at),completed,at(at),run,step,expected.name()));if(target==StepStatus.SUCCEEDED||target==StepStatus.SKIPPED)update(c,"UPDATE plan_step_executions SET status='READY',updated_at=? WHERE run_id=? AND status='PENDING' AND NOT EXISTS(SELECT 1 FROM json_each((SELECT dependency_ids_json FROM plan_steps s WHERE s.plan_revision_id=plan_step_executions.plan_revision_id AND s.step_id=plan_step_executions.step_id)) d JOIN plan_step_executions dep ON dep.run_id=plan_step_executions.run_id AND dep.step_id=d.value WHERE dep.status<>'SUCCEEDED')",at(at),run);}
    /** evidence claim 必须引用同一 Run 的真实成功 Tool，摘要可来自 Agent，但 digest 与来源由服务端取得。 */
    private void appendClaims(Connection c,List<ToolEvidenceClaim> claims,String run,String goal,String plan,Long definition,String revision,String step,Instant at)throws SQLException{for(ToolEvidenceClaim claim:claims){ToolAttempt source=queryOne(c,"SELECT * FROM goal_tool_attempts WHERE run_id=? AND call_id=? AND state='SUCCEEDED'",this::mapTool,run,claim.callId());if(source==null)throw invalid("Tool evidence source is invalid");requireCriterion(c,goal,definition,revision,claim.criterionId());insertEvidence(c,new Evidence(claim.evidenceId(),goal,plan,definition,run,revision,claim.criterionId(),step,EvidenceSource.TOOL_RESULT,claim.callId(),claim.summary(),source.resultDigest(),claim.observedAt(),at));}}
    /** Plan blocker 统计同时覆盖必要步骤与必要验收，任一缺失都禁止把独立 Plan 标记完成。 */
    private static int planBlockers(Connection c,String run,String revision)throws SQLException{return Math.toIntExact(scalar(c,"SELECT (SELECT COUNT(*) FROM plan_steps s LEFT JOIN plan_step_executions x ON x.run_id=? AND x.step_id=s.step_id WHERE s.plan_revision_id=? AND s.required=1 AND COALESCE(x.status,'')<>'SUCCEEDED')+(SELECT COUNT(*) FROM acceptance_criteria p WHERE p.plan_revision_id=? AND p.required=1 AND NOT EXISTS(SELECT 1 FROM acceptance_evidence e WHERE e.run_id=? AND e.plan_revision_id=p.plan_revision_id AND e.criterion_id=p.criterion_id))+(SELECT COUNT(*) FROM goal_tool_attempts t WHERE t.run_id=? AND t.state IN ('PREPARED','STARTED','UNKNOWN'))+(SELECT COUNT(*) FROM interaction_requests i WHERE i.run_id=? AND i.status='PENDING')",run,revision,revision,run,run,run));}
    /**
     * evaluator 已先提交 MET 时，最后一个 Goal Tool 的 terminal 写入负责原子完成；真正缺证据仍保持
     * ACTIVE/WORKING，只有完成门以外的持久化错误才回滚本次 Tool settlement。
     */
    private void completeGoalAfterToolSettlement(Connection c, ToolAttempt attempt, Instant at)
            throws SQLException {
        if (attempt.goalId() == null) return;
        Goal goal = requireGoal(c, attempt.goalId());
        if (goal.status() != GoalStatus.ACTIVE || goal.phase() != GoalPhase.WORKING
                || !Objects.equals(goal.activeRunId(), attempt.runId())) return;
        try {
            requireCompletion(c, goal);
        } catch (GoalRepositoryException failure) {
            if (failure.code() == GoalRepositoryException.Code.GOAL_EVIDENCE_INCOMPLETE) return;
            throw failure;
        }
        transitionRun(c, goal.activeRunId(), "COMPLETED", at);
        goalCas(c, goal, GoalStatus.ACHIEVED, GoalPhase.ACHIEVED, goal.activeRunId(), false,
                goal.turnsWithoutProgress(), goal.repeatedFailureCount(), goal.lastFailureSignature(), at);
        goalEvent(c, goal.goalId(), goal.revision() + 1, "CHANGED", "achieved",
                "evt_tool_settle_achieved_" + attempt.toolAttemptId(),
                "tool_settle_achieved:" + attempt.toolAttemptId(), at(at));
    }
    /** Goal 完成门要求当前 definition、可选 Plan、无未决工作及 MET evaluator 全部满足，拒绝模型自报完成。 */
    private static void requireCompletion(Connection c,Goal goal)throws SQLException{GoalPlanLink link=activeLink(c,goal.goalId());int blockers=Math.toIntExact(scalar(c,"SELECT (SELECT COUNT(*) FROM goal_acceptance_criteria g WHERE g.goal_id=? AND g.goal_definition_revision=? AND g.required=1 AND NOT EXISTS(SELECT 1 FROM acceptance_evidence e WHERE e.run_id=? AND e.goal_id=g.goal_id AND e.goal_definition_revision=g.goal_definition_revision AND e.criterion_id=g.criterion_id))+(SELECT COUNT(*) FROM interaction_requests WHERE goal_id=? AND status='PENDING')+(SELECT COUNT(*) FROM goal_tool_attempts WHERE goal_id=? AND run_id=? AND state IN ('PREPARED','STARTED','UNKNOWN'))",goal.goalId(),goal.goalDefinitionRevision(),goal.activeRunId(),goal.goalId(),goal.goalId(),goal.activeRunId()));if(link!=null)blockers+=planBlockers(c,goal.activeRunId(),link.planRevisionId());String verdict=queryOne(c,"SELECT verdict FROM goal_evaluations WHERE goal_id=? AND goal_definition_revision=? AND run_id=? AND status='COMPLETED' ORDER BY completed_at DESC LIMIT 1",r->r.getString(1),goal.goalId(),goal.goalDefinitionRevision(),goal.activeRunId());if(blockers!=0||!"MET".equals(verdict))throw error(GoalRepositoryException.Code.GOAL_EVIDENCE_INCOMPLETE,"Goal completion evidence is incomplete");}
    /** 证据写入交由 V1 外键与 trigger 再校验 owner 组合，应用检查不能替代数据库完整性约束。 */
    private static void insertEvidence(Connection c,Evidence e)throws SQLException{one(update(c,"INSERT OR IGNORE INTO acceptance_evidence(evidence_id,goal_id,plan_id,goal_definition_revision,run_id,plan_revision_id,criterion_id,step_id,source_type,source_id,summary,digest,observed_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",e.evidenceId(),e.goalId(),e.planId(),e.goalDefinitionRevision(),e.runId(),e.planRevisionId(),e.criterionId(),e.stepId(),e.sourceType().name(),e.sourceId(),e.summary(),e.digest(),at(e.observedAt()),at(e.createdAt())));}
    /** Tool 行映射保留 Goal-only 与 Plan-only 的 nullable owner，避免恢复阶段伪造不存在的关联。 */
    private ToolAttempt mapTool(ResultSet r)throws SQLException{long definition=r.getLong("goal_definition_revision");Long boxed=r.wasNull()?null:definition;return new ToolAttempt(r.getString("tool_attempt_id"),r.getString("goal_id"),r.getString("plan_id"),boxed,r.getString("run_id"),r.getString("plan_revision_id"),r.getString("step_id"),r.getInt("attempt"),r.getString("turn_id"),r.getString("call_id"),r.getLong("process_generation"),r.getInt("side_effect")==1,ToolAttemptState.valueOf(r.getString("state")),r.getString("request_digest"),r.getString("result_digest"),Instant.parse(r.getString("prepared_at")),parse(r.getString("started_at")),parse(r.getString("completed_at")));}
    /** evidence 行映射保留可空 owner identity，使查询结果忠实反映 Goal-only 或独立 Plan 来源。 */
    private Evidence mapEvidence(ResultSet r)throws SQLException{long definition=r.getLong("goal_definition_revision");Long boxed=r.wasNull()?null:definition;return new Evidence(r.getString("evidence_id"),r.getString("goal_id"),r.getString("plan_id"),boxed,r.getString("run_id"),r.getString("plan_revision_id"),r.getString("criterion_id"),r.getString("step_id"),EvidenceSource.valueOf(r.getString("source_type")),r.getString("source_id"),r.getString("summary"),r.getString("digest"),Instant.parse(r.getString("observed_at")),Instant.parse(r.getString("created_at")));}
    /** 事件映射保留 SQLite 单调 sequence，分页游标只能依据持久顺序而不能依据时间或 ID。 */
    private Event mapEvent(ResultSet r)throws SQLException{return new Event(r.getLong("event_sequence"),r.getString("event_id"),r.getString("goal_id"),r.getLong("goal_revision"),r.getString("kind"),r.getString("payload_json"),Instant.parse(r.getString("created_at")));}
    /** 提案版本后缀只供持久幂等回放使用，观察事件必须保持稳定词汇，避免提交后通知失败。 */
    private PlanEvent mapPlanEvent(ResultSet r) throws SQLException {
        String activity = r.getString("activity");
        if (activity.startsWith("plan_proposed:")) activity = "plan_proposed";
        return new PlanEvent(r.getLong("event_sequence"), r.getString("event_id"),
                r.getString("plan_id"), r.getLong("plan_revision"), activity,
                Instant.parse(r.getString("created_at")));
    }
    /** 步骤观察行仅保留 UI 所需摘要，不把 definition_json 带出 persistence 查询。 */
    private static GoalModels.PlanProgress progress(List<StepProgressRow> rows) {
        int total = Math.toIntExact(rows.stream().filter(StepProgressRow::required).count());
        int completed = Math.toIntExact(rows.stream()
                .filter(row -> row.required() && "SUCCEEDED".equals(row.status())).count());
        StepProgressRow active = rows.stream()
                .filter(row -> "RUNNING".equals(row.status()) || "READY".equals(row.status()))
                .findFirst().orElse(null);
        return new GoalModels.PlanProgress(active == null ? null : active.stepId(),
                active == null ? null : active.title(), completed, total);
    }
    /** lease 映射完整保留 fencing identity，续租与释放必须命中同一代际和 token。 */
    private ContinuationLease mapLease(ResultSet r)throws SQLException{return new ContinuationLease(r.getString("goal_id"),r.getString("lease_id"),r.getLong("process_generation"),r.getLong("fencing_token"),r.getString("state"),Instant.parse(r.getString("acquired_at")),Instant.parse(r.getString("heartbeat_at")),parse(r.getString("released_at")));}
    /** lease 缺失表示单飞竞争已失败，调用方必须停止启动而不能临时构造一个本地 lease。 */
    private ContinuationLease requireLease(Connection c,String id)throws SQLException{return queryOne(c,"SELECT * FROM goal_continuation_leases WHERE lease_id=?",this::mapLease,id);}
    /** Tool attempt 缺失表示持久执行链断裂，失败关闭可阻止无来源回执被挂接为验收证据。 */
    private ToolAttempt requireTool(Connection c,String id)throws SQLException{ToolAttempt value=queryOne(c,"SELECT * FROM goal_tool_attempts WHERE tool_attempt_id=?",this::mapTool,id);if(value==null)throw invalid("Tool attempt is unavailable");return value;}
    /** evaluator 行保留请求时冻结的模型与 Provider identity，恢复时不得静默切换计费或判断来源。 */
    private Eval mapEval(ResultSet r)throws SQLException{return new Eval(r.getString("evaluation_id"),r.getString("goal_id"),r.getLong("goal_definition_revision"),r.getString("run_id"),r.getString("plan_revision_id"),r.getLong("process_generation"),r.getString("model_id"),r.getString("provider_id"),r.getString("requested_at"));}
    /** 已完成 evaluator 投影严格解析结构化 criteria，损坏行不能以空结论进入 UI。 */
    private GoalEvaluation mapEvaluation(ResultSet r) throws SQLException {
        try {
            com.fasterxml.jackson.databind.JsonNode encoded = json.readTree(r.getString("criteria_json"));
            if (encoded == null || !encoded.isArray()) throw invalid("Persisted Goal evaluation is invalid");
            java.util.ArrayList<CriterionEvaluation> criteria = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode item : encoded) {
                if (!item.hasNonNull("criterionId") || !item.hasNonNull("verdict")
                        || !item.hasNonNull("reason") || item.get("reason").textValue().isBlank()) {
                    throw invalid("Persisted Goal evaluation is invalid");
                }
                criteria.add(new CriterionEvaluation(item.get("criterionId").textValue(),
                        EvaluationVerdict.valueOf(item.get("verdict").textValue()),
                        item.get("reason").textValue()));
            }
            String summary = r.getString("summary");
            if (summary == null || summary.isBlank()) throw invalid("Persisted Goal evaluation is invalid");
            return new GoalEvaluation(r.getString("evaluation_id"), r.getString("goal_id"),
                    r.getLong("goal_definition_revision"), r.getString("plan_revision_id"),
                    r.getString("run_id"), EvaluationVerdict.valueOf(r.getString("verdict")),
                    criteria, summary, Instant.parse(r.getString("completed_at")));
        } catch (GoalRepositoryException failure) {
            throw failure;
        } catch (JsonProcessingException | IllegalArgumentException | DateTimeException failure) {
            throw invalid("Persisted Goal evaluation is invalid");
        }
    }
    /** evaluator intent 在一处转换并严格解析时间，使正常 dispatch 与启动恢复使用同一冻结快照。 */
    private EvaluationIntent intent(Eval r){return new EvaluationIntent(r.evaluationId,r.goalId,r.definition,r.runId,r.planRevision,r.generation,r.modelId,r.providerId,Instant.parse(r.requestedAt));}
    /** pending input 内部投影不暴露响应正文，只保留结算 identity、状态与到期边界。 */
    /** Run owner 映射保留 SQL null，避免把 Goal-only 与 Plan-only 执行错误合并成同一种 owner。 */
    private Run mapRun(ResultSet r)throws SQLException{long definition=r.getLong("goal_definition_revision");Long boxed=r.wasNull()?null:definition;return new Run(r.getString("goal_id"),r.getString("plan_id"),boxed,r.getString("plan_revision_id"));}
    /** Goal row 映射后验证状态/phase 组合。 */
    private Goal mapGoal(ResultSet r)throws SQLException{Goal value=new Goal(r.getString("goal_id"),r.getString("owner_thread_id"),OwnerKind.valueOf(r.getString("owner_kind")),r.getString("objective"),r.getLong("goal_definition_revision"),GoalStatus.valueOf(r.getString("status")),GoalPhase.valueOf(r.getString("phase")),r.getLong("revision"),r.getString("active_run_id"),r.getInt("progress_turns_without_change"),r.getInt("repeated_failure_count"),r.getString("last_failure_signature"),r.getInt("recovery_required")==1,Instant.parse(r.getString("created_at")),Instant.parse(r.getString("updated_at")));GoalStateMachine.requireCombination(value.status(),value.phase());return value;}
    /** Plan row 映射保持 active identity 不变量。 */
    private Plan mapPlan(ResultSet r)throws SQLException{return new Plan(r.getString("plan_id"),r.getString("owner_thread_id"),r.getString("objective"),PlanStatus.valueOf(r.getString("status")),r.getLong("revision"),r.getString("active_plan_revision_id"),r.getString("active_run_id"),Instant.parse(r.getString("created_at")),Instant.parse(r.getString("updated_at")));}
    /** canonical JSON 是 Plan revision 权威源。 */
    private PlanRevision mapRevision(ResultSet r)throws SQLException{try{String raw=r.getString("definition_json");PlanDefinition definition=json.readValue(raw,PlanDefinition.class);PlanPolicy.validate(definition);return new PlanRevision(r.getString("plan_revision_id"),r.getString("plan_id"),r.getInt("revision_number"),definition,raw,r.getString("plan_hash"),r.getString("created_by"),Instant.parse(r.getString("created_at")));}catch(JsonProcessingException|IllegalArgumentException|DateTimeException failure){throw invalid("Persisted Plan revision is invalid");}}
    /** draft 允许尚未通过 DAG policy 的结构状态。 */
    private PlanDraft mapDraft(ResultSet r)throws SQLException{try{return new PlanDraft(r.getString("plan_draft_id"),r.getString("plan_id"),r.getLong("draft_revision"),json.readValue(r.getString("definition_json"),PlanDefinition.class),r.getString("based_on_plan_revision_id"),Instant.parse(r.getString("updated_at")));}catch(JsonProcessingException|IllegalArgumentException|DateTimeException failure){throw invalid("Persisted Plan draft is invalid");}}
    /** Goal definition criteria 可为空。 */
    private GoalDefinition definition(Connection c,String goalId,long revision)throws SQLException{Head head=queryOne(c,"SELECT objective,created_at FROM goal_definition_revisions WHERE goal_id=? AND revision_number=?",r->new Head(r.getString(1),r.getString(2)),goalId,revision);if(head==null)throw error(GoalRepositoryException.Code.GOAL_NOT_FOUND,"Goal definition is unavailable");List<AcceptanceCriterion> criteria=queryList(c,"SELECT criterion_id,description,required FROM goal_acceptance_criteria WHERE goal_id=? AND goal_definition_revision=? ORDER BY ordinal",r->new AcceptanceCriterion(r.getString(1),r.getString(2),r.getInt(3)==1),goalId,revision);return new GoalDefinition(goalId,revision,head.objective,criteria,Instant.parse(head.createdAt));}
    /** Goal snapshot 在一个读事务中投影 link、进度、输入和 evaluator，避免跨 revision 拼接。 */
    private GoalSnapshot snapshot(Connection c, Goal goal) throws SQLException {
        GoalPlanLink link = queryOne(c, "SELECT * FROM goal_plan_links WHERE goal_id=? AND detached_at IS NULL",
                r -> new GoalPlanLink(r.getString("goal_id"), r.getString("plan_id"),
                        r.getString("plan_revision_id"), r.getString("plan_hash"),
                        r.getLong("link_revision"), Instant.parse(r.getString("attached_at"))), goal.goalId());
        int total = link == null ? 0 : Math.toIntExact(scalar(c,
                "SELECT COUNT(*) FROM plan_steps WHERE plan_revision_id=? AND required=1",
                link.planRevisionId()));
        int completed = link == null ? 0 : Math.toIntExact(scalar(c,
                "SELECT COUNT(*) FROM plan_step_executions x JOIN plan_steps s "
                        + "ON s.plan_revision_id=x.plan_revision_id AND s.step_id=x.step_id "
                        + "WHERE x.run_id=? AND s.required=1 AND x.status='SUCCEEDED'",
                goal.activeRunId()));
        String current = link == null ? null : queryOne(c,
                "SELECT x.step_id FROM plan_step_executions x JOIN plan_steps s "
                        + "ON s.plan_revision_id=x.plan_revision_id AND s.step_id=x.step_id "
                        + "WHERE x.run_id=? AND x.status IN ('RUNNING','READY') "
                        + "ORDER BY CASE x.status WHEN 'RUNNING' THEN 0 ELSE 1 END,s.ordinal LIMIT 1",
                r -> r.getString(1), goal.activeRunId());
        GoalEvaluation evaluation = queryOne(c,
                "SELECT * FROM goal_evaluations WHERE goal_id=? AND goal_definition_revision=? AND run_id=? "
                        + "AND status='COMPLETED' ORDER BY completed_at DESC,evaluation_id DESC LIMIT 1",
                this::mapEvaluation, goal.goalId(), goal.goalDefinitionRevision(), goal.activeRunId());
        long seq = scalar(c, "SELECT COALESCE(MAX(event_sequence),0) FROM goal_events WHERE goal_id=?",
                goal.goalId());
        return new GoalSnapshot(goal, definition(c, goal.goalId(), goal.goalDefinitionRevision()), link,
                current, completed, total,
                goal.phase() == GoalPhase.NEEDS_ATTENTION ? "needs_attention" : null,
                goal.status() == GoalStatus.ACHIEVED ? goal.updatedAt() : null,
                goal.status() == GoalStatus.STOPPED ? goal.updatedAt() : null, evaluation, seq);
    }
    /**
     * Plan 进入批准或执行态后，currentRevision 必须跟随持久化 active identity，而不是跟随表中最新行；
     * 这样恢复窗口或后续版本已经追加时，UI 和执行器仍读取当初被批准的完整冻结定义。
     */
    private PlanSnapshot planSnapshot(Connection c, Plan plan) throws SQLException {
        PlanDraft draft = queryOne(c, "SELECT * FROM plan_drafts WHERE plan_id=?", this::mapDraft,
                plan.planId());
        PlanRevision revision = plan.activePlanRevisionId() == null
                ? queryOne(c, "SELECT * FROM plan_revisions WHERE plan_id=? ORDER BY revision_number DESC LIMIT 1",
                        this::mapRevision, plan.planId())
                : requireRevision(c, plan.planId(), plan.activePlanRevisionId());
        PlanApproval approval = revision == null ? null : queryOne(c,
                "SELECT * FROM plan_approvals WHERE plan_id=? AND plan_revision_id=? AND decision='APPROVED' "
                        + "ORDER BY created_at DESC LIMIT 1",
                r -> new PlanApproval(r.getString("approval_id"), r.getString("plan_id"),
                        r.getString("plan_revision_id"), r.getString("plan_hash"),
                        Instant.parse(r.getString("created_at"))),
                plan.planId(), revision.planRevisionId());
        List<StepExecution> steps = plan.activeRunId() == null ? List.of() : queryList(c,
                "SELECT x.* FROM plan_step_executions x JOIN plan_steps s "
                        + "ON s.plan_revision_id=x.plan_revision_id AND s.step_id=x.step_id "
                        + "WHERE x.run_id=? ORDER BY s.ordinal",
                r -> new StepExecution(r.getString("step_id"), r.getString("run_id"),
                        StepStatus.valueOf(r.getString("status")), r.getInt("attempt_count"),
                        r.getString("failure_signature"), null, parse(r.getString("started_at")),
                        parse(r.getString("completed_at"))),
                plan.activeRunId());
        long seq = scalar(c, "SELECT COALESCE(MAX(event_sequence),0) FROM plan_events WHERE plan_id=?",
                plan.planId());
        return new PlanSnapshot(plan, draft, revision, approval, steps, seq);
    }
    /** 新执行默认进入 RUNNING；暂停 Goal 的 link 切换必须显式走带状态重载。 */
    private static void insertRun(Connection c,String runId,String goalId,String planId,Long definition,String revision,String hash,long generation,String at)throws SQLException{insertRun(c,runId,goalId,planId,definition,revision,hash,generation,"RUNNING",at);}
    /** run 状态由聚合当前状态派生，不能因 link mutation 静默恢复暂停 Goal。 */
    private static void insertRun(Connection c,String runId,String goalId,String planId,Long definition,String revision,String hash,long generation,String status,String at)throws SQLException{one(update(c,"INSERT INTO execution_runs(run_id,goal_id,plan_id,goal_definition_revision,plan_revision_id,plan_hash,status,process_generation,started_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",runId,goalId,planId,definition,revision,hash,status,generation,at,at,at));}
    /** 只有显式 resume 才能把暂停 Goal 的 replacement run 改为 RUNNING。 */
    private static String runStatus(Goal goal){return goal.status()==GoalStatus.PAUSED?"PAUSED":"RUNNING";}
    /**
     * 切换 run 前在同一 writer transaction 检查所有已持久未决边界；任何未知 Tool/evaluator、
     * lease 或非终态 Turn 都必须先由既有恢复/取消流程结算，不能通过换 link 丢失所有权。
     */
    private static void requireRunReplacementSafe(Connection c,Goal goal)throws SQLException{
        long blockers=scalar(c,"SELECT (SELECT COUNT(*) FROM goal_evaluations WHERE run_id=? AND status IN ('REQUESTED','RUNNING'))+(SELECT COUNT(*) FROM goal_tool_attempts WHERE run_id=? AND state IN ('PREPARED','STARTED','UNKNOWN'))+(SELECT COUNT(*) FROM goal_continuation_leases WHERE goal_id=? AND state='HELD')+(SELECT COUNT(*) FROM turns WHERE thread_id=? AND state IN ('QUEUED','RUNNING','WAITING_APPROVAL','SUSPENDED'))",goal.activeRunId(),goal.activeRunId(),goal.goalId(),goal.ownerThreadId());
        if(blockers!=0)throw invalid("Goal run has unresolved work");
    }
    /** evidence criterion 必须来自当前 Goal definition 或精确 Plan revision，不能接受模型猜测 ID。 */
    private static void requireCriterion(Connection c,String goal,Long definition,String revision,String criterion)throws SQLException{
        long matches=0;
        if(goal!=null)matches+=scalar(c,"SELECT COUNT(*) FROM goal_acceptance_criteria WHERE goal_id=? AND goal_definition_revision=? AND criterion_id=?",goal,definition,criterion);
        if(revision!=null)matches+=scalar(c,"SELECT COUNT(*) FROM acceptance_criteria WHERE plan_revision_id=? AND criterion_id=?",revision,criterion);
        if(matches!=1)throw invalid("Evidence criterion is invalid");
    }
    /** Plan run 初始化稳定 step ids。 */
    private static void insertSteps(Connection c,String runId,PlanRevision revision,Instant at)throws SQLException{for(PlanStep step:revision.definition().steps())one(update(c,"INSERT INTO plan_step_executions(run_id,plan_revision_id,step_id,status,updated_at) VALUES(?,?,?,?,?)",runId,revision.planRevisionId(),step.stepId(),step.dependsOn().isEmpty()?"READY":"PENDING",at(at)));}
    /** Plan CAS 与 Goal CAS 正交。 */
    private static void planCas(Connection c,Plan before,PlanStatus status,String revision,String run,Instant at)throws SQLException{one(update(c,"UPDATE plans SET status=?,revision=revision+1,active_plan_revision_id=?,active_run_id=?,updated_at=? WHERE plan_id=? AND revision=?",status.name(),revision,run,at(at),before.planId(),before.revision()));}
    /** 独立 Plan 执行前拒绝同 owner 的活动 Goal；查询和后续 run insert 位于同一 writer transaction。 */
    private static void requireNoActiveGoalForPlan(Connection c, String planId) throws SQLException {
        String owner = queryOne(c, "SELECT p.owner_thread_id FROM plans p JOIN threads t "
                        + "ON t.thread_id=p.owner_thread_id WHERE p.plan_id=?", r -> r.getString(1), planId);
        if (owner == null) throw error(GoalRepositoryException.Code.GOAL_NOT_FOUND, "Plan owner is unavailable");
        Integer activeGoal = queryOne(c, "SELECT 1 FROM goals WHERE owner_thread_id=? "
                        + "AND status IN ('ACTIVE','PAUSED') LIMIT 1", r -> r.getInt(1), owner);
        if (activeGoal != null) throw invalid("Plan owner already has an active Goal");
    }

    /** Goal 创建与 Plan attach 前拒绝 owner 上仍可恢复的独立 Plan run，避免跨聚合并行写入。 */
    private static void requireNoStandalonePlanRun(Connection c, String ownerThreadId) throws SQLException {
        Integer activeRun = queryOne(c, "SELECT 1 FROM execution_runs r JOIN plans p ON p.plan_id=r.plan_id "
                        + "WHERE p.owner_thread_id=? AND r.goal_id IS NULL "
                        + "AND r.status IN ('PREPARED','RUNNING','VERIFYING','PAUSED') LIMIT 1",
                r -> r.getInt(1), ownerThreadId);
        if (activeRun != null) throw invalid("Owner already has an active standalone Plan run");
    }

    /** attach/execute 的同一 Plan 不得同时保留另一个独立 run，PAUSED 也属于可恢复活动事实。 */
    private static void requireNoActiveStandaloneRun(Connection c, String planId) throws SQLException {
        Integer activeRun = queryOne(c, "SELECT 1 FROM execution_runs WHERE plan_id=? AND goal_id IS NULL "
                        + "AND status IN ('PREPARED','RUNNING','VERIFYING','PAUSED') LIMIT 1",
                r -> r.getInt(1), planId);
        if (activeRun != null) throw invalid("Plan already has an active standalone run");
    }

    /**
     * Goal/Plan 准入必须复用临时侧聊的关闭闸门，不能依赖界面是否禁用控件；
     * 递归校验同时阻断关闭侧聊内的子任务，主会话和其他工作区不受影响。
     */
    private static void requireOwnerAdmissionOpen(Connection c, String threadId) throws SQLException {
        String closingOwner = queryOne(c,
                "WITH RECURSIVE ancestors(thread_id) AS ("
                        + "SELECT ? UNION ALL "
                        + "SELECT l.parent_thread_id FROM thread_lineage l "
                        + "JOIN ancestors a ON l.child_thread_id=a.thread_id) "
                        + "SELECT c.thread_id FROM temporary_side_chats c "
                        + "JOIN ancestors a ON a.thread_id=c.thread_id "
                        + "WHERE c.state='CLOSING' ORDER BY c.thread_id LIMIT 1",
                r -> r.getString(1), threadId);
        if (closingOwner != null) throw invalid("side chat is closing");
    }

    /** latest revision/hash 必须精确匹配。 */
    private PlanRevision requireLatest(Connection c,String planId,String revision,String hash)throws SQLException{PlanRevision value=queryOne(c,"SELECT * FROM plan_revisions WHERE plan_id=? ORDER BY revision_number DESC LIMIT 1",this::mapRevision,planId);if(value==null||!value.planRevisionId().equals(revision)||!value.planHash().equals(hash))throw stale();return value;}
    /** approval 是 execute/attach 的唯一资格事实。 */
    private PlanRevision requireApproved(Connection c,String planId,String revision,String hash)throws SQLException{PlanRevision value=requireRevision(c,planId,revision);Integer approved=queryOne(c,"SELECT 1 FROM plan_approvals WHERE plan_id=? AND plan_revision_id=? AND plan_hash=? AND decision='APPROVED'",r->r.getInt(1),planId,revision,hash);if(approved==null||!value.planHash().equals(hash))throw stale();return value;}
    /** 新版 attach 是独立的显式用户动作；只有历史 APPROVED 计划继续依赖旧审计资格。 */
    private PlanRevision requireAttachable(Connection c,Plan plan,String revision,String hash)throws SQLException{requireNoActiveStandaloneRun(c,plan.planId());if(plan.status()==PlanStatus.AWAITING_APPROVAL)return requireLatest(c,plan.planId(),revision,hash);return requireApproved(c,plan.planId(),revision,hash);}
    /** 精确 revision 查询绝不跟随 latest，批准、执行与恢复必须继续绑定原始 planRevisionId。 */
    private PlanRevision requireRevision(Connection c,String planId,String revision)throws SQLException{PlanRevision value=queryOne(c,"SELECT * FROM plan_revisions WHERE plan_id=? AND plan_revision_id=?",this::mapRevision,planId,revision);if(value==null)throw stale();return value;}
    /** Goal 必需读取统一返回稳定 GOAL_NOT_FOUND，避免 JDBC 空结果泄漏为不确定内部错误。 */
    private Goal requireGoal(Connection c,String id)throws SQLException{Goal value=queryOne(c,"SELECT * FROM goals WHERE goal_id=?",this::mapGoal,id);if(value==null)throw error(GoalRepositoryException.Code.GOAL_NOT_FOUND,"Goal is unavailable");return value;}
    /** Plan 缺失归类为 PLAN_INVALID 而不是 Goal 错误，保持 RPC 调用方可稳定区分聚合边界。 */
    private Plan requirePlan(Connection c,String id)throws SQLException{Plan value=queryOne(c,"SELECT * FROM plans WHERE plan_id=?",this::mapPlan,id);if(value==null)throw error(GoalRepositoryException.Code.PLAN_INVALID,"Plan is unavailable");return value;}
    /** Plan mutation 在进入写事务后重验 expected revision，防止基于过期 draft 或批准继续写入。 */
    private Plan requirePlanRevision(Connection c,String id,long revision)throws SQLException{Plan value=requirePlan(c,id);if(value.revision()!=revision)throw conflict();return value;}
    /** Goal 事件与聚合 revision 同事务追加，并由 SQLite 构造最小结构化 payload，禁止拼接任意 JSON。 */
    private static void goalEvent(Connection c,String goal,long revision,String kind,String activity,String event,String key,String at)throws SQLException{one(update(c,"INSERT INTO goal_events(event_id,goal_id,goal_revision,kind,payload_json,idempotency_key,created_at) VALUES(?,?,?,?,json_object('activity',?),?,?)",event,goal,revision,kind,activity,key,at));}
    /** Plan 事件使用独立 sequence namespace，不能与 Goal 事件游标混排或共享水位。 */
    private static void planEvent(Connection c,String plan,long revision,String activity,String event,String key,String at)throws SQLException{one(update(c,"INSERT INTO plan_events(event_id,plan_id,plan_revision,activity,idempotency_key,created_at) VALUES(?,?,?,?,?,?)",event,plan,revision,activity,key,at));}
    /** JDBC mutation 统一参数绑定以精确保留 null，并避免 identity 或摘要进入 SQL 字符串拼接。 */
    private static int update(Connection c,String sql,Object...values)throws SQLException{try(PreparedStatement s=c.prepareStatement(sql)){bind(s,values);return s.executeUpdate();}}
    /** 单行查询在返回领域值前关闭 ResultSet，mapper 不得把事务 cursor 生命周期泄漏到调用方。 */
    private static <T>T queryOne(Connection c,String sql,Rows<T> mapper,Object...values)throws SQLException{try(PreparedStatement s=c.prepareStatement(sql)){bind(s,values);try(ResultSet r=s.executeQuery()){return r.next()?mapper.map(r):null;}}}
    /** 多行查询在事务 cursor 内完成映射并冻结列表，调用方只能观察一致的已提交快照。 */
    private static <T>List<T> queryList(Connection c,String sql,Rows<T> mapper,Object...values)throws SQLException{try(PreparedStatement s=c.prepareStatement(sql)){bind(s,values);try(ResultSet r=s.executeQuery()){List<T> out=new java.util.ArrayList<>();while(r.next())out.add(mapper.map(r));return List.copyOf(out);}}}
    /** JDBC 原生绑定保持 nullable owner 与数值类型，禁止先转成字符串造成 SQLite 宽松比较。 */
    private static void bind(PreparedStatement s,Object[]values)throws SQLException{for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);}
    /** 标量读取复用单行关闭边界，并拒绝空结果，适用于计数、序列水位与 CAS 前置判断。 */
    private static long scalar(Connection c,String sql,Object...values)throws SQLException{return Objects.requireNonNull(queryOne(c,sql,r->r.getLong(1),values));}
    /** 预期单行 mutation 必须恰好命中一行，零行或多行都代表并发冲突或完整性破坏。 */
    private static void one(int count){if(count!=1)throw conflict();}
    /** 时间统一保存为非空 ISO Instant，跨进程恢复不依赖本地时区或数据库隐式格式。 */
    private static String at(Instant value){return Objects.requireNonNull(value).toString();}
    /** 可空终态时间只在数据库有值时解析，未结算状态不能被伪造为 epoch 等哨兵时间。 */
    private static Instant parse(String value){return value==null?null:Instant.parse(value);}
    /** Goal 与 Plan 的 revision CAS 统一映射为稳定冲突码，调用方据此重读而不是盲目重试。 */
    private static GoalRepositoryException conflict(){return error(GoalRepositoryException.Code.GOAL_REVISION_CONFLICT,"Aggregate revision is stale");}
    /** revision 或 hash 与批准事实不精确匹配时返回 stale，任何 AccessMode 都不能绕过该门。 */
    private static GoalRepositoryException stale(){return error(GoalRepositoryException.Code.PLAN_APPROVAL_STALE,"Plan approval is stale");}
    /** 生命周期组合或未决工作不满足时使用稳定无效状态错误，不把业务拒绝伪装成存储故障。 */
    private static GoalRepositoryException invalid(String message){return error(GoalRepositoryException.Code.GOAL_INVALID_STATE,message);}
    /** Repository 错误统一从稳定 code 构造，使 application 与 JA-RPC 不依赖 SQLite 异常文本。 */
    private static GoalRepositoryException error(GoalRepositoryException.Code code,String message){return new GoalRepositoryException(code,message);}

    /** context 可选文本只接受 JSON string，字段类型损坏时交由 binding 构造器失败关闭。 */
    private static String text(com.fasterxml.jackson.databind.JsonNode root, String name) {
        return root.hasNonNull(name) && root.get(name).isTextual() ? root.get(name).textValue() : null;
    }

    /** context 可选整数不接受浮点或字符串，fencing/revision 不做宽松转换。 */
    private static Long number(com.fasterxml.jackson.databind.JsonNode root, String name) {
        return root.hasNonNull(name) && root.get(name).isIntegralNumber() && root.get(name).canConvertToLong()
                ? root.get(name).longValue() : null;
    }

    /** 统一 JDBC work 允许 query/update helper 保留受检异常并交给 transaction owner 分类。 */
    @FunctionalInterface private interface SqlWork<T> {
        /** 在当前 writer transaction 的唯一 Connection 上执行，不得自行 commit 或 close。 */
        T run(Connection connection) throws SQLException, JsonProcessingException;
    }
    /** ResultSet mapper 只读取当前行，不持有 cursor 或连接生命周期。 */
    @FunctionalInterface private interface Rows<T> {
        /** 把当前 JDBC 行投影为不可变值，列损坏通过 SQLException 失败关闭。 */
        T map(ResultSet result) throws SQLException;
    }
    /** Goal owner 投影同时冻结 Thread revision 与 Task lineage。 */
    private record Owner(long revision,String taskKind,String lifecycle){}
    /** Goal definition head 与 criteria 分表读取，head 只保留冻结目标和时间。 */
    private record Head(String objective,String createdAt){}
    /** evaluator intent 必须沿用 owner Thread 当前显式模型身份。 */
    private record Model(String providerId,String modelId){}
    /** evaluator 持久行的最小内部投影，避免 application 层接触可变 JDBC cursor。 */
    private record Eval(String evaluationId,String goalId,long definition,String runId,String planRevision,
                        long generation,String modelId,String providerId,String requestedAt){}
    /** run owner tuple 用于恢复与 evidence 校验，不包含可变执行状态。 */
    private record Run(String goalId,String planId,Long definition,String planRevision){}
    /** Plan Turn claim 的状态与 ordinal 共同构成重试和恢复的稳定 admission identity。 */
    private record ClaimRow(int ordinal, String state) {}
    /** Plan 调度领取只读取 run budget 与 pause fence，不把执行计数混入 Plan 读模型。 */
    private record RunBudget(String status,String planId,String planRevision,int turnBudget,
                             int turnsUsed,boolean pauseRequested,Integer maxModelRounds,
                             Integer maxToolCalls,Long wallBudgetMillis,int usedModelRounds,
                             int usedToolCalls,long usedActiveMillis){}
    /** run budget 行严格读取列类型，损坏预算不能静默退化为默认值。 */
    private RunBudget mapRunBudget(ResultSet r) throws SQLException {
        return new RunBudget(r.getString("status"), r.getString("plan_id"),
                r.getString("plan_revision_id"), r.getInt("turn_budget"), r.getInt("turns_used"),
                r.getInt("pause_requested") == 1, nullableInt(r, "max_model_rounds"),
                nullableInt(r, "max_tool_calls"), nullableLong(r, "wall_budget_millis"),
                r.getInt("used_model_rounds"), r.getInt("used_tool_calls"),
                r.getLong("used_active_millis"));
    }
    /** 尚未冻结的预算列必须与合法零值区分，不能在查询时伪装为无限预算。 */
    private static Integer nullableInt(ResultSet r, String column) throws SQLException {
        int value = r.getInt(column);
        return r.wasNull() ? null : value;
    }
    /** 时间预算保留 null 状态，准入才能拒绝缺失的冻结执行约束。 */
    private static Long nullableLong(ResultSet r, String column) throws SQLException {
        long value = r.getLong(column);
        return r.wasNull() ? null : value;
    }
    /** 剩余额度使用饱和减法；缺失预算必须拒绝，不能为测试放宽生产执行上限。 */
    private static int remaining(Integer maximum, int used) {
        if (maximum == null) throw invalid("Plan execution budget is not frozen");
        return Math.max(0, maximum - used);
    }
    /** 迟到结算不能使时间预算下溢后变成正值，未冻结的 Run 也不得执行。 */
    private static long remaining(Long maximum, long used) {
        if (maximum == null) throw invalid("Plan execution budget is not frozen");
        return Math.max(0L, maximum - used);
    }
    /** 任一累计上限耗尽就停止准入，多个新 Turn 不能绕过同一 Run 预算。 */
    private static boolean exhausted(RunBudget budget) {
        return budget.maxModelRounds() != null && budget.usedModelRounds() >= budget.maxModelRounds()
                || budget.maxToolCalls() != null && budget.maxToolCalls() > 0
                && budget.usedToolCalls() >= budget.maxToolCalls()
                || budget.wallBudgetMillis() != null && budget.usedActiveMillis() >= budget.wallBudgetMillis();
    }
    /** internal Turn context 保留 origin 与原始 JSON，严格解析在事务内完成。 */
    private record InternalContext(String origin,String contextJson){}
    /** continuation 恢复查询的单事务投影，不把 Interaction 正文或可变 owner 状态带出。 */
    private record ContinuationRecoveryRow(String turnId, String threadId, long threadRevision,
                                           long turnMutationVersion, String requestId, String status,
                                           String origin, String contextJson) {}
    /** 只用于 Plan observe 的一行步骤摘要。 */
    private record StepProgressRow(String stepId, String title, boolean required, String status){}

}
