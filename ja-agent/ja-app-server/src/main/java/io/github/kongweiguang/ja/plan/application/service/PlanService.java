// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.service;

import io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot;
import io.github.kongweiguang.ja.conversation.application.api.NativeExecutionContext;
import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanRevision;
import io.github.kongweiguang.ja.plan.application.api.PlanUseCase;
import io.github.kongweiguang.ja.plan.application.api.PlanEventSink;
import io.github.kongweiguang.ja.plan.application.api.PlanExecutionEventSink;
import io.github.kongweiguang.ja.plan.application.gateway.PlanCanonicalizer;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepository;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepositoryException;
import io.github.kongweiguang.ja.plan.application.gateway.PlanWorkspacePort;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceAdmissionPort;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Plan application service 只分配 identity、canonical hash 并调用事务端口。
 */
public final class PlanService implements PlanUseCase {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(PlanService.class);
    private final PlanRepository plans;
    private final PlanCanonicalizer canonical;
    private final Clock clock;
    private final PlanExecutionCoordinator planExecutions;
    private final PlanEventRegistry planEvents;
    private final PlanCommittedPublisher committedPublisher;
    private final PlanWorkspacePort workspaces;

    /**
     * canonicalizer 由组合根注入，应用层不持有 JSON mapper；构造阶段不访问存储或重复持有可变依赖。
     */
    public PlanService(PlanRepository plans, PlanCanonicalizer canonical, Clock clock, long processGeneration,
                       PlanExecutionCoordinator planExecutions, PlanEventRegistry planEvents,
                       PlanCommittedPublisher committedPublisher) {
        this(plans, canonical, clock, processGeneration, planExecutions, planEvents, committedPublisher, null);
    }

    /**
     * 生产组合根提供 Workspace gate，测试窄构造保留无 Workspace 的隔离语义；root 更新与 Plan 准入需共享同一锁。
     *
     * @author kongweiguang
     */
    public PlanService(PlanRepository plans, PlanCanonicalizer canonical, Clock clock, long processGeneration,
                       PlanExecutionCoordinator planExecutions, PlanEventRegistry planEvents,
                       PlanCommittedPublisher committedPublisher, PlanWorkspacePort workspaces) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.canonical = Objects.requireNonNull(canonical, "canonical");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.planExecutions = planExecutions;
        this.planEvents = Objects.requireNonNull(planEvents, "planEvents");
        this.committedPublisher = Objects.requireNonNull(committedPublisher, "committedPublisher");
        this.workspaces = workspaces;
        if (processGeneration < 1) {
            throw new IllegalArgumentException("invalid process generation");
        }
    }


    /**
     * Plan 只在持有活动 run 时需要真实执行协调器参与关闭。
     */
    private static boolean isStoppablePlan(PlanModels.Plan plan) {
        return plan != null && plan.activeRunId() != null
               && (plan.status() == PlanModels.PlanStatus.EXECUTING
                   || plan.status() == PlanModels.PlanStatus.VERIFYING
                   || plan.status() == PlanModels.PlanStatus.PAUSED);
    }

    /**
     * 同步用例保持既有 RPC 契约；异步 Turn 控制异常统一还原为领域失败。
     * RuntimeException 保留原实例，checked cause 作为 IllegalStateException 的 cause 传播。
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    private static void awaitPlanControl(java.util.concurrent.CompletionStage<Void> control) {
        try {
            control.toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Plan Turn control failed", cause);
        }
    }

    /**
     * 共享后台的 Run 创建和恢复只接受发起连接的私有快照；stdio 入口继续使用其
     * 进程启动环境，任何后续异步阶段都不能重新读取请求线程。
     */
    private static NativeExecutionSnapshot currentExecutionContext() {
        NativeExecutionContext bridge = NativeExecutionContext.shared();
        NativeExecutionSnapshot snapshot = bridge.current().orElse(null);
        if (bridge.sharedMode() && snapshot == null) {
            throw new IllegalStateException("native execution context is required for run admission");
        }
        return snapshot;
    }

    /**
     * 执行协调器是持久 run 的唯一 owner；缺失时必须在 Plan 状态提交前拒绝。
     */
    private PlanExecutionCoordinator requirePlanExecutions() {
        if (planExecutions == null) {
            throw new IllegalStateException("Plan execution runtime is unavailable");
        }
        return planExecutions;
    }


    /**
     * Plan 暂停或终态必须清理完整环境，Resume 会由新连接重新绑定同一 Run。
     */
    private static void releasePlanContextIfInactive(PlanModels.Plan plan) {
        if (plan.activeRunId() != null && plan.status() != PlanModels.PlanStatus.EXECUTING
            && plan.status() != PlanModels.PlanStatus.VERIFYING) {
            NativeExecutionContext.shared().releasePlanRun(plan.planId(), plan.activeRunId());
        }
    }

    /**
     * transport 页最大 200，内部只多取一项判断 nextCursor。
     */
    private static int pageFetch(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("invalid Plan page size");
        }
        return limit + 1;
    }

    /**
     * 数字游标带类型前缀，禁止 event cursor 被误用于 revision 查询。
     */
    private static long numericCursor(String cursor, String prefix) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        if (!cursor.startsWith(prefix) || cursor.length() > 64) {
            throw new IllegalArgumentException("invalid Plan cursor");
        }
        try {
            long value = Long.parseLong(cursor.substring(prefix.length()));
            if (value < 0) {
                throw new IllegalArgumentException("invalid Plan cursor");
            }
            return value;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("invalid Plan cursor", failure);
        }
    }

    /**
     * evidence cursor 是 URL-safe Base64 的 ISO instant 与稳定 identity，不暴露数据库 offset。
     */
    private static EvidenceCursor evidenceCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        if (cursor.length() > 512) {
            throw new IllegalArgumentException("invalid evidence cursor");
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = decoded.indexOf('\n');
            if (separator <= 0 || separator != decoded.lastIndexOf('\n')) {
                throw new IllegalArgumentException("invalid evidence cursor");
            }
            String createdAt = decoded.substring(0, separator);
            String evidenceId = PlanModels.requireId(decoded.substring(separator + 1), "evidence cursor id");
            java.time.Instant.parse(createdAt);
            return new EvidenceCursor(createdAt, evidenceId);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("invalid evidence cursor", failure);
        }
    }

    /**
     * 下一 evidence cursor 精确落在最后一个已返回事实，不从客户端可见序号推导。
     */
    private static String evidenceCursor(PlanModels.Evidence evidence) {
        String value = evidence.createdAt() + "\n" + evidence.evidenceId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }


    /**
     * Plan event 已由 SQLite 限制 activity 闭集，映射后仍不把内部 payload 暴露给 UI。
     */
    static PlanModels.PublicEvent projectPlanPublicEvent(PlanRepository.PlanEvent value) {
        return new PlanModels.PublicEvent(value.sequence(), value.planRevision(),
                publicKind(value.activity()), publicSummary(value.activity()), value.createdAt());
    }

    /** 仅两种事务结算事实属于内部游标；其余未知活动必须继续经过公开闭集校验。 */
    private static boolean isInternalPlanEvent(String activity) {
        return switch (activity) {
            case "plan_turn_settled", "plan_turn_activity_settled" -> true;
            default -> false;
        };
    }

    /**
     * 仅映射当前 Plan 的冻结事件词汇；未知活动直接失败，避免业务残留扩散到 Wire。
     */
    private static String publicKind(String activity) {
        return switch (activity.toLowerCase(java.util.Locale.ROOT)) {
            case "created" -> "created";
            case "draft_saved" -> "plan_draft_saved";
            case "draft_discarded" -> "plan_draft_saved";
            case "plan_proposed" -> "plan_proposed";
            case "plan_execution_started" -> "plan_execution_started";
            case "plan_verifying" -> "plan_verifying";
            case "plan_completed" -> "plan_completed";
            case "plan_verification_failed" -> "plan_verification_failed";
            case "plan_verification_inconclusive" -> "plan_verification_inconclusive";
            case "plan_paused" -> "plan_paused";
            case "plan_resumed" -> "plan_resumed";
            case "plan_stopped" -> "plan_stopped";
            case "plan_execution_incomplete" -> "plan_execution_incomplete";
            case "plan_recovery_required" -> "plan_recovery_required";
            case "plan_execution_recovered" -> "plan_execution_recovered";
            case "plan_approved" -> "plan_approved";
            case "plan_rejected" -> "plan_rejected";
            case "step_updated" -> "step_changed";
            case "evidence_added" -> "evidence_added";
            case "continuation_no_progress" -> "continuation_no_progress";
            case "tool_approval_requested" -> "tool_approval_requested";
            case "tool_approval_resolved" -> "tool_approval_resolved";
            case "evaluation_requested" -> "evaluation_started";
            case "evaluation_completed" -> "evaluation_completed";
            case "paused" -> "paused";
            case "working" -> "resumed";
            case "stopped" -> "stopped";
            case "achieved" -> "achieved";
            case "needs_attention" -> "recovery_required";
            default -> throw new IllegalStateException("unknown Plan event activity");
        };
    }

    /**
     * 摘要从当前事件词汇派生为稳定产品文案，不回显 payload 或异常消息。
     */
    private static String publicSummary(String activity) {
        return switch (publicKind(activity)) {
            case "created" -> "Plan 已创建";
            case "plan_draft_saved" -> "计划草稿已更新";
            case "plan_proposed" -> "计划等待批准";
            case "plan_approved" -> "计划已批准";
            case "plan_rejected" -> "计划已退回";
            case "plan_execution_started" -> "计划执行已开始";
            case "plan_verifying" -> "计划正在独立验收";
            case "plan_completed" -> "计划已完成";
            case "plan_verification_failed" -> "计划验收未通过";
            case "plan_verification_inconclusive" -> "计划验收证据不足，已暂停";
            case "plan_paused" -> "计划已暂停";
            case "plan_resumed" -> "计划已恢复";
            case "plan_stopped" -> "计划已停止";
            case "plan_execution_incomplete" -> "计划执行未完成";
            case "plan_recovery_required" -> "计划需要人工恢复";
            case "plan_execution_recovered" -> "计划已恢复为可执行状态";
            case "step_changed" -> "计划步骤已更新";
            case "evidence_added" -> "验收证据已记录";
            case "continuation_no_progress" -> "本轮未取得新进展";
            case "tool_approval_requested" -> "Plan 正在等待工具批准";
            case "tool_approval_resolved" -> "工具审批已处理";
            case "evaluation_started" -> "独立验收已开始";
            case "evaluation_completed" -> "独立验收已完成";
            case "paused" -> "Plan 已暂停";
            case "resumed" -> "Plan 已恢复";
            case "stopped" -> "Plan 已停止";
            case "achieved" -> "Plan 已达成";
            case "recovery_required" -> "Plan 需要人工处理";
            default -> throw new IllegalStateException("unknown Plan event activity");
        };
    }

    /**
     * UUID 只作为 opaque identity，业务排序完全依赖 SQLite sequence。
     */
    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }


    /**
     * Plan 状态观察使用独立 registry；关闭一个连接只撤销该连接的订阅。
     */
    @Override
    public AutoCloseable subscribePlan(PlanEventSink sink) {
        return planEvents.subscribe(sink);
    }


    /**
     * 读取 Plan snapshot 不改变其状态、revision 或执行 identity。
     */
    @Override
    public PlanModels.PlanSnapshot readPlan(String planId) {
        return plans.readPlanSnapshot(planId);
    }

    /**
     * 初次显示 Thread 时的一次性查询，后续更新使用轻量 Plan 观察。
     */
    @Override
    public Optional<PlanModels.PlanSnapshot> findCurrentPlan(String ownerThreadId) {
        return plans.findCurrentPlan(ownerThreadId);
    }



    /**
     * Plan mode 动态上下文只投影 owner 唯一非终态 Plan 的 CAS 与执行 identity。
     */
    @Override
    public Optional<PlanTurnContext> currentPlanContext(String ownerThreadId) {
        return plans.findActivePlanByOwner(ownerThreadId).map(plan -> new PlanTurnContext(
                plan.planId(), plan.revision(), plan.status(),
                plan.activePlanRevisionId(), plan.activeRunId()));
    }

    /**
     * runtime resolution 发生在 Turn admission 之前，此时尚无持久 internal context；V1 的 owner
     * 非终态唯一索引与 EXECUTING 查询共同提供唯一候选，admission 后 Tool ledger 再按 turnId 复核。
     */
    @Override
    public Optional<PlanTurnContext> planExecutionContext(String ownerThreadId, String turnId) {
        PlanModels.requireId(turnId, "turnId");
        Optional<PlanRepository.InternalTurnBinding> persisted = plans.findInternalTurnBinding(turnId)
                .filter(binding -> "PLAN_EXECUTION".equals(binding.origin()));
        if (persisted.isPresent()) {
            PlanRepository.InternalTurnBinding binding = persisted.orElseThrow();
            PlanModels.PlanSnapshot snapshot = plans.readPlanSnapshot(binding.planId());
            PlanModels.Plan current = snapshot.plan();
            PlanModels.PlanRevision revision = snapshot.currentRevision();
            if (!current.ownerThreadId().equals(ownerThreadId)
                || (current.status() != PlanModels.PlanStatus.EXECUTING
                    && current.status() != PlanModels.PlanStatus.COMPLETED)
                || !Objects.equals(current.activeRunId(), binding.runId())
                || !Objects.equals(current.activePlanRevisionId(), binding.planRevisionId())
                || revision == null || !revision.planHash().equals(binding.planHash())) {
                return Optional.empty();
            }
            return Optional.of(new PlanTurnContext(current.planId(), current.revision(), current.status(),
                    current.activePlanRevisionId(), current.activeRunId()));
        }
        return plans.findExecutingPlanByOwner(ownerThreadId).flatMap(plan -> {
            PlanModels.PlanSnapshot snapshot = plans.readPlanSnapshot(plan.planId());
            PlanModels.Plan current = snapshot.plan();
            PlanModels.PlanRevision revision = snapshot.currentRevision();
            if (current.status() != PlanModels.PlanStatus.EXECUTING
                || current.activeRunId() == null || current.activePlanRevisionId() == null
                || revision == null
                || !revision.planRevisionId().equals(current.activePlanRevisionId())) {
                return Optional.empty();
            }
            return Optional.of(new PlanTurnContext(current.planId(), current.revision(), current.status(),
                    current.activePlanRevisionId(), current.activeRunId()));
        });
    }

    /**
     * Turn 控制只从持久不可变 binding 恢复 Plan/run identity；发现缺失 Plan、损坏、过期或跨 owner 绑定时
     * 使用可恢复错误关闭，避免错误回落到普通 Turn。
     */
    @Override
    public Optional<PlanTurnContext> planTurnControlContext(String ownerThreadId, String turnId) {
        PlanModels.requireId(ownerThreadId, "ownerThreadId");
        PlanModels.requireId(turnId, "turnId");
        Optional<PlanRepository.InternalTurnBinding> persisted;
        try {
            persisted = plans.findInternalTurnBinding(turnId);
        } catch (PlanRepositoryException failure) {
            throw new PlanRepositoryException(PlanRepositoryException.Code.PLAN_RECOVERY_REQUIRED,
                    "Persisted Plan Turn control binding requires recovery", failure);
        }
        if (persisted.isEmpty()) {
            return Optional.empty();
        }
        PlanRepository.InternalTurnBinding binding = persisted.orElseThrow();
        PlanModels.PlanSnapshot snapshot;
        try {
            snapshot = plans.readPlanSnapshot(binding.planId());
        } catch (PlanRepositoryException failure) {
            throw new PlanRepositoryException(PlanRepositoryException.Code.PLAN_RECOVERY_REQUIRED,
                    "Persisted Plan Turn control binding requires recovery", failure);
        }
        PlanModels.Plan current = snapshot.plan();
        PlanRevision revision = snapshot.currentRevision();
        if (!binding.turnId().equals(turnId)
            || !current.planId().equals(binding.planId())
            || !current.ownerThreadId().equals(ownerThreadId)
            || !Objects.equals(current.activeRunId(), binding.runId())
            || !Objects.equals(current.activePlanRevisionId(), binding.planRevisionId())
            || revision == null
            || !revision.planRevisionId().equals(binding.planRevisionId())
            || !revision.planHash().equals(binding.planHash())) {
            throw new PlanRepositoryException(PlanRepositoryException.Code.PLAN_RECOVERY_REQUIRED,
                    "Persisted Plan Turn control binding requires recovery");
        }
        return Optional.of(new PlanTurnContext(current.planId(), current.revision(), current.status(),
                current.activePlanRevisionId(), current.activeRunId()));
    }


    /** Plan 观察按原始序号推进游标，内部结算事实不可公开也不能让分页停滞。 */
    @Override
    public PlanModels.Page<PlanModels.PublicEvent> readPlanEvents(String planId, String cursor, int limit) {
        long afterSequence = numericCursor(cursor, "pev~");
        PlanRepository.ReadPage<PlanRepository.PlanEvent> page = plans.listPlanEvents(
                planId, afterSequence, pageFetch(limit));
        List<PlanModels.PublicEvent> items = new ArrayList<>(Math.min(limit, page.items().size()));
        long scannedSequence = afterSequence;
        boolean hasUnreturnedPublicEvent = false;
        for (PlanRepository.PlanEvent event : page.items()) {
            if (isInternalPlanEvent(event.activity())) {
                scannedSequence = event.sequence();
                continue;
            }
            if (items.size() == limit) {
                hasUnreturnedPublicEvent = true;
                break;
            }
            items.add(projectPlanPublicEvent(event));
            scannedSequence = event.sequence();
        }
        boolean more = hasUnreturnedPublicEvent || scannedSequence < page.eventSequence();
        String next = more && scannedSequence > afterSequence ? "pev~" + scannedSequence : null;
        return new PlanModels.Page<>(planId, page.planRevision(), page.eventSequence(), items, next);
    }

    /**
     * Plan evidence 查询按冻结 revision/run 过滤，旧版本证据不可进入当前 UI 完成门。
     */
    @Override
    public PlanModels.Page<PlanModels.Evidence> listPlanEvidence(String planId, String planRevisionId,
                                                                 String runId, String cursor, int limit) {
        PlanModels.PlanSnapshot snapshot = plans.readPlanSnapshot(planId);
        if (snapshot.currentRevision() == null
            || !snapshot.currentRevision().planRevisionId().equals(planRevisionId)) {
            throw new PlanRepositoryException(PlanRepositoryException.Code.PLAN_APPROVAL_STALE,
                    "Plan revision is stale");
        }
        EvidenceCursor after = evidenceCursor(cursor);
        PlanRepository.ReadPage<PlanModels.Evidence> page = plans.listPlanEvidencePage(planId, runId,
                planRevisionId, after == null ? null : after.createdAt(), after == null ? null : after.evidenceId(),
                pageFetch(limit));
        List<PlanModels.Evidence> all = page.items();
        boolean more = all.size() > limit;
        List<PlanModels.Evidence> items = all.stream().limit(limit).toList();
        String next = more && !items.isEmpty() ? evidenceCursor(items.getLast()) : null;
        return new PlanModels.Page<>(planId, page.planRevision(), page.eventSequence(), items, next);
    }


    /**
     * revision 页使用不可变 revisionNumber 游标，尾部追加不会重排已返回条目。
     */
    @Override
    public PlanModels.Page<PlanRevision> listRevisions(String planId, String cursor, int limit) {
        long afterRevisionNumber = numericCursor(cursor, "rev~");
        PlanRepository.ReadPage<PlanRevision> page = plans.listPlanRevisions(
                planId, afterRevisionNumber, pageFetch(limit));
        List<PlanRevision> rows = page.items();
        boolean more = rows.size() > limit;
        List<PlanRevision> items = rows.stream().limit(limit).toList();
        String next = more && !items.isEmpty() ? "rev~" + items.getLast().revisionNumber() : null;
        return new PlanModels.Page<>(planId, page.planRevision(), page.eventSequence(), items, next);
    }



    /**
     * Plan create 分配独立 identity，不写 Plan event 或依赖当前活动 Plan。
     */
    @Override
    public PlanModels.Plan createPlan(CreatePlan command) {
        PlanModels.Plan result = plans.createPlan(new PlanRepository.CreatePlan(id("plan_"), command.ownerThreadId(),
                command.objective(), command.expectedThreadRevision(), command.operationKey(), at(command.at())));
        publishPlanCommitted(result.planId());
        return result;
    }

    /**
     * 草稿 JSON 使用和 revision 相同字段名，但不产生 hash 权威性。
     */
    @Override
    public PlanModels.Plan saveDraft(SaveDraft command) {
        preparePlanEdit(command.planId(), command.expectedPlanRevision());
        String definitionJson = canonical.encode(command.definition()).json();
        PlanModels.Plan result = plans.saveDraft(new PlanRepository.SaveDraft(
                command.planId(), command.expectedPlanRevision(),
                command.expectedDraftRevision(), definitionJson, command.basedOnPlanRevisionId(), id("evt_"),
                command.operationKey(), at(command.at())));
        publishPlanCommitted(result.planId());
        return result;
    }

    /**
     * 草稿丢弃保留 Plan CAS 与幂等事件，重复动作回读当前状态。
     */
    @Override
    public PlanModels.Plan discardDraft(DiscardDraft command) {
        PlanModels.Plan result = plans.discardDraft(new PlanRepository.DiscardDraft(command.planId(), command.expectedPlanRevision(),
                id("evt_"), command.operationKey(), at(command.at())));
        publishPlanCommitted(result.planId());
        return result;
    }

    /**
     * UI propose 只冻结当前已保存草稿，不能从请求携带另一份未保存定义。
     */
    @Override
    public PlanRevision proposeDraft(ProposeDraft command) {
        PlanModels.PlanDraft draft = plans.findDraft(command.planId()).orElseThrow(() ->
                new PlanRepositoryException(PlanRepositoryException.Code.PLAN_INVALID,
                        "Plan draft is unavailable"));
        return propose(new Propose(command.planId(), command.expectedPlanRevision(),
                draft.definition(), command.userAuthored(), command.operationKey(), at(command.at())));
    }

    /**
     * revision number 从仓储 writer transaction 分配，service 提供候选 1 供仓储替换校验。
     */
    @Override
    public PlanRevision propose(Propose command) {
        return withWorkspaceAdmission(command.planId(), () -> proposeAdmitted(command));
    }

    /** 在共享 Workspace 门禁内冻结 Plan revision，阻止 root CAS 与活动计划准入交错。 */
    private PlanRevision proposeAdmitted(Propose command) {
        preparePlanEdit(command.planId(), command.expectedPlanRevision());
        PlanCanonicalizer.Encoded encoded = canonical.encode(command.definition());
        PlanRevision revision = new PlanRevision(id("planrev_"), command.planId(), 1, command.definition(),
                encoded.json(), encoded.sha256(), command.userAuthored() ? "USER_UI" : "AGENT", at(command.at()));
        PlanRevision result = plans.propose(new PlanRepository.ProposePlan(command.planId(), command.expectedPlanRevision(),
                revision, id("evt_"), command.operationKey(), at(command.at())));
        publishPlanCommitted(result.planId());
        return result;
    }

    /**
     * 修改冻结方案前收口原暂停 Turn，防止换版清空 Plan 指针却遗留可执行的旧 Run。
     */
    private void preparePlanEdit(String planId, long expectedRevision) {
        PlanModels.Plan current = plans.readPlanSnapshot(planId).plan();
        if (current.revision() != expectedRevision) {
            return;
        }
        if (current.status() == PlanModels.PlanStatus.EXECUTING || current.status() == PlanModels.PlanStatus.VERIFYING) {
            throw new PlanRepositoryException(PlanRepositoryException.Code.PLAN_INVALID_STATE, "Pause the Plan before editing");
        }
        if (current.status() == PlanModels.PlanStatus.PAUSED && current.activeRunId() != null) {
            if (planExecutions == null) {
                throw new IllegalStateException("Plan execution runtime is unavailable");
            }
            awaitPlanControl(planExecutions.stop(current));
        }
    }

    /**
     * 批准 action 分配 approval/run identity，仓储再次核对 hash。
     */
    @Override
    public PlanModels.Plan approve(Approve command) {
        return withWorkspaceAdmission(command.planId(), () -> approveAdmitted(command));
    }

    /** 仅在 Workspace read lease 持有期间提交待执行批准。 */
    private PlanModels.Plan approveAdmitted(Approve command) {
        PlanModels.Plan result = plans.approve(new PlanRepository.ApprovePlan(command.planId(), command.expectedPlanRevision(),
                command.planRevisionId(), command.planHash(), id("appr_"), id("evt_"),
                command.operationKey(), at(command.at())));
        publishPlanCommitted(result.planId());
        return result;
    }

    /**
     * standalone execute 是独立 mutation；仓储按 operation key 重放时返回当前权威聚合，只有
     * 本次随机 runId 仍是返回快照的活动 Run 才由此请求负责启动，避免重复 admit 或启动暂停/终态旧 Run。
     * 发起连接的窄事件出口随新 Run 显式下传，不依赖 Plan observe 或向其它连接广播内部 Turn。
     */
    @Override
    public PlanModels.Plan executePlan(ExecutePlan command, PlanExecutionEventSink events) {
        return withWorkspaceAdmission(command.planId(), () -> executePlanAdmitted(command, events));
    }

    /** 持有准入 lease 直到执行状态持久化并启动协调器，避免 root 更新越过计划启动窗口。 */
    private PlanModels.Plan executePlanAdmitted(ExecutePlan command, PlanExecutionEventSink events) {
        PlanExecutionCoordinator executions = requirePlanExecutions();
        PlanExecutionEventSink sink = Objects.requireNonNull(events, "events");
        currentExecutionContext();
        String runId = id("run_");
        PlanModels.Plan result = plans.executePlan(new PlanRepository.ExecutePlan(command.planId(), command.expectedPlanRevision(),
                command.planRevisionId(), command.planHash(), id("appr_"), runId, command.processGeneration(), id("evt_"),
                command.operationKey(), at(command.at())));
        publishPlanCommitted(result.planId());
        if (Objects.equals(result.activeRunId(), runId)) {
            executions.start(result, sink);
        }
        return result;
    }

    /**
     * 只有本次 fence 被接受并完成 Turn 安全结算后才刷新聚合 revision；最终仓储 CAS 仍校验原 Run 与可暂停状态。
     * 同键重放或初始身份不匹配继续使用调用方 revision，不以刷新读绕过控制准入。
     *
     * @author kongweiguang
     */
    @Override
    public PlanModels.Plan pausePlan(PlanControl command) {
        long expectedRevision = quiescePlanTurn(command, PlanRepository.PlanControlAction.PAUSE)
                .map(PlanModels.Plan::revision).orElse(command.expectedPlanRevision());
        PlanModels.Plan result = plans.pausePlan(new PlanRepository.PausePlan(command.planId(), expectedRevision,
                command.runId(), id("evt_"), command.operationKey(), at(command.at())));
        releasePlanContextIfInactive(result);
        publishPlanCommitted(result.planId());
        return result;
    }

    /**
     * Plan resume 由仓储 CAS 恢复原 run，避免重复点击创建新的 execution_runs 行。
     */
    @Override
    public PlanModels.Plan resumePlan(PlanControl command) {
        return resumePlan(command, PlanExecutionEventSink.noop());
    }

    /**
     * 恢复入口保留发起连接的事件 sink，重启后恢复的 Plan Turn 仍能实时投影到当前窗口。
     */
    @Override
    public PlanModels.Plan resumePlan(PlanControl command, PlanExecutionEventSink events) {
        return withWorkspaceAdmission(command.planId(), () -> resumePlanAdmitted(command, events));
    }

    /** Resume 的状态 CAS 与 coordinator admission 同属 Workspace gate 临界区。 */
    private PlanModels.Plan resumePlanAdmitted(PlanControl command, PlanExecutionEventSink events) {
        PlanExecutionCoordinator executions = requirePlanExecutions();
        PlanExecutionEventSink sink = Objects.requireNonNull(events, "events");
        currentExecutionContext();
        PlanRepository.ResumeResult mutation = plans.resumePlan(new PlanRepository.ResumePlan(command.planId(), command.expectedPlanRevision(),
                command.runId(), id("evt_"), command.operationKey(), at(command.at())));
        PlanModels.Plan result = mutation.plan();
        publishPlanCommitted(result.planId());
        if (mutation.applied()) {
            // 只有事务首次提交恢复时才 admission；持久同键重放只返回权威快照。
            executions.resume(result, sink).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    // coordinator 已按同一 run fencing 结算失败；这里不能阻塞或再次写 Plan 状态。
                    LOGGER.warn("Plan resume admission failed type={}", failure.getClass().getSimpleName());
                }
            });
        }
        return result;
    }

    /**
     * 从 Plan 的不可变 owner Thread 解析 Workspace gate；非生产窄测试可使用旧构造而不装配 ACL。
     */
    private <T> T withWorkspaceAdmission(String planId, Supplier<T> operation) {
        if (workspaces == null) {
            return operation.get();
        }
        String ownerThreadId = plans.readPlanSnapshot(planId).plan().ownerThreadId();
        try (WorkspaceAdmissionPort.Lease ignored = workspaces.acquireAdmissionForThread(ownerThreadId)) {
            return operation.get();
        }
    }

    /**
     * Stop 在真实 Turn 与 ledger 安全收口后才使用最新聚合 revision，仓储仍对原 Run 和终态边界做 CAS。
     * 未被接受的控制不读取新版本，避免陈旧调用借刷新读跨过最初身份校验。
     *
     * @author kongweiguang
     */
    @Override
    public PlanModels.Plan stopPlan(PlanControl command) {
        long expectedRevision = quiescePlanTurn(command, PlanRepository.PlanControlAction.STOP)
                .map(PlanModels.Plan::revision).orElse(command.expectedPlanRevision());
        PlanModels.Plan result = plans.stopPlan(new PlanRepository.StopPlan(command.planId(), expectedRevision,
                command.runId(), id("evt_"), command.operationKey(), at(command.at())));
        releasePlanContextIfInactive(result);
        publishPlanCommitted(result.planId());
        return result;
    }

    /**
     * 侧聊关闭时统一停止所属活动 Run，并把关闭方的单调 deadline 传到真实 Turn 控制；
     * 协调器缺失时只允许无活动执行的 Plan，避免仅改投影伪造停止。
     */
    public void stopOwners(Set<String> ownerThreadIds, long deadlineNanos) {
        Objects.requireNonNull(ownerThreadIds, "ownerThreadIds");
        for (String ownerThreadId : ownerThreadIds) {
            if (ownerThreadId == null || ownerThreadId.isBlank()) {
                throw new IllegalArgumentException("invalid Plan owner identity");
            }
        }
        Set<String> owners = Set.copyOf(ownerThreadIds);
        if (planExecutions == null) {
            for (String ownerThreadId : owners) {
                PlanModels.Plan plan = plans.findActivePlanByOwner(ownerThreadId).orElse(null);
                if (isStoppablePlan(plan)) {
                    throw new IllegalStateException("Plan execution runtime is unavailable");
                }
            }
        } else {
            planExecutions.stopOwners(owners, deadlineNanos);
        }
    }


    /**
     * 先以调用方 revision/run 接受控制 fence，再等待同一真实 Turn 安全结算；仅成功等待后读取最新快照供最终 CAS。
     * 未匹配、幂等重放和失败的控制均不刷新 revision，防止把其他 Run、终态或未完成结算吞成当前操作成功。
     *
     * @author kongweiguang
     */
    private Optional<PlanModels.Plan> quiescePlanTurn(PlanControl command,
            PlanRepository.PlanControlAction action) {
        if (planExecutions == null) {
            throw new IllegalStateException("Plan execution runtime is unavailable");
        }
        PlanModels.Plan current = plans.readPlanSnapshot(command.planId()).plan();
        if (current.revision() != command.expectedPlanRevision()
            || !Objects.equals(current.activeRunId(), command.runId())) {
                return Optional.empty();
        }
        PlanRepository.PlanControlFenceResult fence = plans.requestPlanControlFence(
                new PlanRepository.RequestPlanPause(command.planId(),
                command.expectedPlanRevision(), command.runId(), id("evt_"),
                command.operationKey(), at(command.at())), action);
        if (!fence.shouldControl()) {
            return Optional.empty();
        }
        if (action == PlanRepository.PlanControlAction.STOP) {
            awaitPlanControl(planExecutions.stop(fence.plan()));
        }
        else {
            awaitPlanControl(planExecutions.pause(fence.plan()));
        }
        return Optional.of(plans.readPlanSnapshot(command.planId()).plan());
    }

    /**
     * 拒绝当前最新提案；精确 revision/hash 从同一权威读模型取得并由 mutation CAS 再校验。
     */
    @Override
    public PlanModels.Plan reject(Reject command) {
        PlanModels.PlanSnapshot snapshot = plans.readPlanSnapshot(command.planId());
        PlanRevision revision = snapshot.currentRevision();
        if (revision == null) {
            throw new IllegalArgumentException("Plan revision is unavailable");
        }
        PlanModels.Plan result = plans.reject(new PlanRepository.RejectPlan(command.planId(), command.expectedPlanRevision(),
                revision.planRevisionId(), revision.planHash(), id("appr_"), command.reason(), id("evt_"),
                command.operationKey(), at(command.at())));
        publishPlanCommitted(result.planId());
        return result;
    }





    /**
     * standalone Plan step 只发布 Plan event，不触发 Plan observer。
     */
    @Override
    public PlanModels.Plan updatePlanStep(PlanStepUpdate command) {
        List<PlanRepository.ToolEvidenceClaim> claims = evidenceClaims(command.evidenceClaims(), command.at());
        PlanModels.Plan result = plans.updatePlanStep(new PlanRepository.UpdatePlanStep(command.planId(),
                command.expectedPlanRevision(), command.runId(), command.stepId(), command.expectedStatus(),
                command.status(), command.failureSignature(), claims, id("evt_"), command.operationKey(),
                at(command.at())));
        publishPlanCommitted(result.planId());
        return result;
    }


    /**
     * 所有 Agent evidence claim 在服务端分配 identity 并规范时间，三个 mutation 不得产生不同规则。
     */
    private List<PlanRepository.ToolEvidenceClaim> evidenceClaims(
            List<PlanUseCase.ToolEvidenceClaim> claims, java.time.Instant observedAt) {
        return claims.stream().map(claim -> new PlanRepository.ToolEvidenceClaim(
                id("evidence_"), claim.criterionId(), claim.callId(), claim.summary(), at(observedAt))).toList();
    }






    /**
     * Plan mutation 提交后只读取最新一条 event 和轻量 Plan row，观察不会物化 revision/evidence。
     */
    private void publishPlanCommitted(String planId) {
        committedPublisher.publishPlanCommitted(planId);
    }

    /**
     * evaluator 事务提交后由 coordinator 调用，确保 UI 只观察到已落库的 Plan 终态。
     */
    public void publishPlanCommittedAfterExecution(String planId) {
        releasePlanContextIfInactive(plans.readPlanSnapshot(planId).plan());
        publishPlanCommitted(planId);
    }


    /**
     * 缺省时间使用 composition Clock，禁止直接读取系统墙钟。
     */
    private java.time.Instant at(java.time.Instant requested) {
        return requested == null ? clock.instant() : requested;
    }

    /**
     * 解码后的 keyset 游标只在 application service 内部存在。
     *
     * @param createdAt 创建时间
     * @param evidenceId 证据标识
     */
    private record EvidenceCursor(String createdAt,
                                  String evidenceId) {
    }
}



