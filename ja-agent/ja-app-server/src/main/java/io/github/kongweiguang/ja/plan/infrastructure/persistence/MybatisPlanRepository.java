// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFact;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValues;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.JacksonExecutionEventCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.TurnExecutionStateCodec;
import io.github.kongweiguang.ja.plan.infrastructure.persistence.serialization.CanonicalPlanJson;
import io.github.kongweiguang.ja.plan.application.gateway.PlanCanonicalizer;
import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PlanRecords;
import io.github.kongweiguang.ja.plan.domain.PlanModels.AcceptanceCriterion;
import io.github.kongweiguang.ja.plan.domain.PlanModels.Evidence;
import io.github.kongweiguang.ja.plan.domain.PlanModels.EvidenceSource;
import io.github.kongweiguang.ja.plan.domain.PlanModels.Plan;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanApproval;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanDefinition;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanDraft;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanRevision;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanSnapshot;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanStatus;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanStep;
import io.github.kongweiguang.ja.plan.domain.PlanModels.StepExecution;
import io.github.kongweiguang.ja.plan.domain.PlanModels.StepStatus;
import io.github.kongweiguang.ja.plan.domain.PlanModels.ToolAttempt;
import io.github.kongweiguang.ja.plan.domain.PlanModels.ToolAttemptState;
import io.github.kongweiguang.ja.plan.domain.PlanPolicy;
import io.github.kongweiguang.ja.plan.application.service.PlanDigest;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepository;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepositoryException;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.session.SqlSessionFactory;

import java.sql.SQLDataException;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * V1 Plan SQLite adapter；所有公开 mutation 由同一 MyBatis session transaction 包围。
 */
public final class MybatisPlanRepository implements PlanRepository {
    private final PlanUnitOfWork transactions;
    private final ObjectMapper json;
    private final CanonicalPlanJson canonicalPlanJson;

    /**
     * 生产构造复用 MyBatis 事务与同一 JSON 配置，确保读取的 revision 可按写入规则复算。
     */
    public MybatisPlanRepository(SqlSessionFactory sessions, ObjectMapper json) {
        this.transactions = new PlanUnitOfWork(sessions);
        this.json = Objects.requireNonNull(json, "json");
        this.canonicalPlanJson = new CanonicalPlanJson(this.json);
    }

    /**
     * 测试构造注入真实 SQLite commit/rollback owner，并复用生产 revision 编码规则。
     */
    MybatisPlanRepository(SqlSessionFactory sessions, ObjectMapper json, PlanUnitOfWork.SessionOwner owner) {
        this.transactions = new PlanUnitOfWork(sessions, owner);
        this.json = Objects.requireNonNull(json, "json");
        this.canonicalPlanJson = new CanonicalPlanJson(this.json);
    }

    /**
     * Run 状态转换在同一事务写入终态时间，恢复逻辑不会观察到终态与完成时间分离。
     */
    private static void transitionRun(PlanUnitOfWork.Mappers c, String run, String status, Instant at) throws SQLException {
        if (run == null) {
            return;
        }
        one(c.plan().updateExecutionRunsSettingStatusByRunId(status, status, at(at), at(at), run));
    }

    /**
     * 只有尚未创建持久 Turn 的准入意图可以放弃；有 Turn 的账本只能由正式 settlement 收口。
     */
    private static void abandonUnadmittedPlanTurnClaims(PlanUnitOfWork.Mappers c, String runId)
            throws SQLException {
        c.plan().abandonUnadmittedPlanTurnClaims(runId);
    }

    /**
     * 先封存无 Turn 的意图，再拒绝绕过 Turn usage settlement 关闭 Run 的调用。
     */
    private static void requireSettledPlanTurnClaims(PlanUnitOfWork.Mappers c, String runId)
            throws SQLException {
        abandonUnadmittedPlanTurnClaims(c, runId);
        if (c.plan().findPlanTurnClaimsByRunIdState(runId) != null) {
            throw invalid("Plan run has unsettled Turn claims");
        }
    }

    /**
     * 终态 Turn 若仍有 CLAIMED claim，说明其 usage settlement 未提交，不得用 Run 状态掩盖该阻塞。
     */
    private static boolean hasTerminalUnsettledPlanTurnClaim(PlanUnitOfWork.Mappers c, String runId)
            throws SQLException {
        return c.plan().findTerminalUnsettledPlanTurnClaimByRunId(runId) != null;
    }

    /**
     * 替换 Run 前先停止旧 identity，确保旧 continuation 或 Tool 回执无法继续写入当前聚合。
     */
    private static void stopRun(PlanUnitOfWork.Mappers c, String run, Instant at) throws SQLException {
        if (run != null) {
            transitionRun(c, run, "STOPPED", at);
        }
    }

    /**
     * 稳定步骤 ID 只能经过 PlanPolicy 允许的状态边迁移，CAS 失败按并发冲突处理而非覆盖。
     */
    private static void stepCas(PlanUnitOfWork.Mappers c, String run, String step, StepStatus expected, StepStatus target, String failure, Instant at) throws SQLException {
        if (!PlanPolicy.mayTransition(expected, target)) {
            throw invalid("Plan step transition is invalid");
        }
        String completed = switch (target) {
            case SUCCEEDED, FAILED, SKIPPED -> at(at);
            default -> null;
        };
        one(c.plan().updatePlanStepExecutionsSettingStatusByRunId(target.name(), target.name(), failure, target.name(), at(at), completed, at(at), run, step, expected.name()));
        if (target == StepStatus.SUCCEEDED || target == StepStatus.SKIPPED)
            {
                c.plan().activateReadyPlanSteps(at(at), run);
            }
    }

    /**
     * Plan blocker 统计同时覆盖必要步骤与必要验收，任一缺失都禁止把独立 Plan 标记完成。
     */
    private static int planBlockers(PlanUnitOfWork.Mappers c, String run, String revision) throws SQLException {
        return Math.toIntExact(scalar(c.plan().findPlanStepsByPlanRevisionIdRequiredRunId(run, revision, revision, run, run, run)));
    }


    /**
     * 证据写入交由 V1 外键与 trigger 再校验 owner 组合，应用检查不能替代数据库完整性约束。
     */
    private static void insertEvidence(PlanUnitOfWork.Mappers c, Evidence e) throws SQLException {
        one(c.plan().insertIfAbsentAcceptanceEvidenceWithEvidenceId(e.evidenceId(), e.planId(), e.runId(), e.planRevisionId(), e.criterionId(), e.stepId(), e.sourceType().name(), e.sourceId(), e.summary(), e.digest(), at(e.observedAt()), at(e.createdAt())));
    }

    /**
     * 步骤观察行仅保留 UI 所需摘要，不把 definition_json 带出 persistence 查询。
     */
    private static PlanModels.PlanProgress progress(List<StepProgressRow> rows) {
        int total = Math.toIntExact(rows.stream().filter(StepProgressRow::required).count());
        int completed = Math.toIntExact(rows.stream()
                .filter(row -> row.required() && "SUCCEEDED".equals(row.status())).count());
        StepProgressRow active = rows.stream()
                .filter(row -> "RUNNING".equals(row.status()) || "READY".equals(row.status()))
                .findFirst().orElse(null);
        return new PlanModels.PlanProgress(active == null ? null : active.stepId(),
                active == null ? null : active.title(), completed, total);
    }

    /**
     * 新执行默认进入 RUNNING，状态变更由 Plan CAS 单独控制。
     */
    private static void insertRun(PlanUnitOfWork.Mappers c, String runId, String planId, String revision, String hash, long generation, String at) throws SQLException {
        one(c.plan().insertExecutionRunsWithRunId(runId, planId, revision, hash, "RUNNING", generation, at, at, at));
    }



    /**
     * evidence criterion 必须来自精确 Plan revision，不能接受模型猜测 ID。
     */
    private static void requireCriterion(PlanUnitOfWork.Mappers c, String revision, String criterion) throws SQLException {
        if (scalar(c.plan().findAcceptanceCriteriaByPlanRevisionIdCriterionId(revision, criterion)) != 1)
            {
                throw invalid("Evidence criterion is invalid");
            }
    }

    /**
     * Plan run 初始化稳定 step ids。
     */
    private static void insertSteps(PlanUnitOfWork.Mappers c, String runId, PlanRevision revision, Instant at) throws SQLException {
        for (PlanStep step : revision.definition().steps())
            {
                one(c.plan().insertPlanStepExecutionsWithRunId(runId, revision.planRevisionId(), step.stepId(), step.dependsOn().isEmpty() ? "READY" : "PENDING", at(at)));
            }
    }

    /**
     * Plan CAS 与 Plan CAS 正交。
     */
    private static void planCas(PlanUnitOfWork.Mappers c, Plan before, PlanStatus status, String revision, String run, Instant at) throws SQLException {
        one(c.plan().updatePlansSettingStatusByPlanId(status.name(), revision, run, at(at), before.planId(), before.revision()));
    }


    /**
     * attach/execute 的同一 Plan 不得同时保留另一个独立 run，PAUSED 也属于可恢复活动事实。
     */
    private static void requireNoActiveStandaloneRun(PlanUnitOfWork.Mappers c, String planId) throws SQLException {
        Integer activeRun = c.plan().findExecutionRunsByPlanIdStatus(planId);
        if (activeRun != null) {
            throw invalid("Plan already has an active standalone run");
        }
    }

    /**
     * Plan 准入必须复用临时侧聊的关闭闸门，不能依赖界面是否禁用控件；
     * 递归校验同时阻断关闭侧聊内的子任务，主会话和其他工作区不受影响。
     */
    private static void requireOwnerAdmissionOpen(PlanUnitOfWork.Mappers c, String threadId) throws SQLException {
        PlanRecords.TemporarySideChatRow closing = c.plan().findTemporarySideChatsByState(threadId);
        String closingOwner = closing == null ? null : closing.threadId();
        if (closingOwner != null) {
            throw invalid("side chat is closing");
        }
    }


    /**
     * Plan 事件使用独立 sequence namespace，不能与 Plan 事件游标混排或共享水位。
     */
    private static void planEvent(PlanUnitOfWork.Mappers c, String plan, long revision, String activity, String event, String key, String at) throws SQLException {
        one(c.plan().insertPlanEventsWithEventId(event, plan, revision, activity, key, at));
    }

    /**
     * 单行映射保留查询缺失语义，并只接受 Mapper 为该 SQL 声明的结果类型。
     */
    private static <S, T> T queryOne(S row, Rows<S, T> mapper) throws SQLException {
        return row == null ? null : mapper.map(row);
    }

    /**
     * 严格时间处理器会在 Plan 行构造前拒绝坏存储；只识别明确的数据异常与 SQLState，
     * 对外保留固定脱敏分类，其他 SQL 故障继续交给事务桥处理，不依赖 MyBatis 包装文案或保留原始异常链。
     */
    @SuppressWarnings("PMD.PreserveStackTrace") // 原始查询异常可能带出持久值，使用固定诊断 cause。
    private static PlanRecords.PlanRow readPlanRow(Supplier<PlanRecords.PlanRow> query) {
        try {
            return query.get();
        } catch (PersistenceException failure) {
            for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLDataException data && "22007".equals(data.getSQLState())) {
                    throw new StorageException(StorageException.Code.INVALID_STATE,
                            "invalid persisted Plan timestamp",
                            new SQLDataException("persisted Plan timestamp is not a UTC epoch-millis integer", "22007"));
                }
            }
            throw failure;
        }
    }

    /**
     * 列表映射保留 SQL 顺序，避免把原始数据库容器带出 repository。
     */
    private static <S, T> List<T> queryList(List<S> rows, Rows<S, T> mapper) throws SQLException {
        java.util.ArrayList<T> result = new java.util.ArrayList<>(rows.size());
        for (S row : rows) {
            result.add(mapper.map(row));
        }
        return List.copyOf(result);
    }

    /**
     * 计数与水位以 Long 直接映射；缺失行仍表示持久查询不满足预期。
     */
    private static long scalar(Long value) {
        return Objects.requireNonNull(value, "scalar query returned no value");
    }

    /**
     * 预期单行 mutation 必须恰好命中一行，零行或多行都代表并发冲突或完整性破坏。
     */
    private static void one(int count) {
        if (count != 1) {
            throw conflict();
        }
    }

    /**
     * 时间统一保存为非空 ISO Instant，跨进程恢复不依赖本地时区或数据库隐式格式。
     */
    private static String at(Instant value) {
        return Objects.requireNonNull(value).toString();
    }

    /**
     * 损坏持久值可能由解析异常消息或原始 cause 回显；此处只保留异常类型并构造固定诊断 cause。
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    private static Instant persistedInstant(String value, String column) {
        if (value == null) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "invalid persisted Plan timestamp at " + column);
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeException failure) {
            String reason = failure.getClass().getSimpleName();
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "invalid persisted Plan timestamp at " + column,
                    new DateTimeException("stored value is not an ISO-8601 instant (" + reason + ")"));
        }
    }

    /**
     * nullable 列保留 SQL NULL 语义，其余值走与必填列相同的脱敏解析边界。
     */
    private static Instant optionalPersistedInstant(String value, String column) {
        return value == null ? null : persistedInstant(value, column);
    }

    /**
     * Plan revision CAS 冲突统一映射为稳定冲突码，调用方据此重读而不是盲目重试。
     */
    private static PlanRepositoryException conflict() {
        return error(PlanRepositoryException.Code.PLAN_REVISION_CONFLICT, "Aggregate revision is stale");
    }

    /**
     * revision 或 hash 与批准事实不精确匹配时返回 stale，任何 AccessMode 都不能绕过该门。
     */
    private static PlanRepositoryException stale() {
        return error(PlanRepositoryException.Code.PLAN_APPROVAL_STALE, "Plan approval is stale");
    }

    /**
     * 生命周期组合或未决工作不满足时使用稳定无效状态错误，不把业务拒绝伪装成存储故障。
     */
    private static PlanRepositoryException invalid(String message) {
        return error(PlanRepositoryException.Code.PLAN_INVALID_STATE, message);
    }

    /**
     * 持久值仍映射为固定失败文案，同时保留原始解析 cause 供进程内诊断。
     */
    private static PlanRepositoryException invalid(String message, Throwable cause) {
        return new PlanRepositoryException(PlanRepositoryException.Code.PLAN_INVALID_STATE, message, cause);
    }

    /**
     * Repository 错误统一从稳定 code 构造，使 application 与 JA-RPC 不依赖 SQLite 异常文本。
     */
    private static PlanRepositoryException error(PlanRepositoryException.Code code, String message) {
        return new PlanRepositoryException(code, message);
    }

    /**
     * context 可选文本只接受 JSON string，字段类型损坏时交由 binding 构造器失败关闭。
     */
    private static String text(com.fasterxml.jackson.databind.JsonNode root, String name) {
        return root.hasNonNull(name) && root.get(name).isTextual() ? root.get(name).textValue() : null;
    }

    /**
     * Plan 创建不依赖活动 Plan。
     */
    @Override
    public Plan createPlan(CreatePlan command) {
        return transactions.required(c -> {
            Plan replay = queryOne(readPlanRow(() -> c.plan().findPlansByOwnerThreadIdCreateOperationKey(command.ownerThreadId(), command.operationKey())), this::mapPlan);
            if (replay != null) {
                return replay;
            }
            Long revision = c.plan().findActiveThreadRevision(command.ownerThreadId());
            if (revision == null) {
                throw error(PlanRepositoryException.Code.PLAN_NOT_FOUND, "Plan owner is unavailable");
            }
            if (revision != command.expectedThreadRevision()) {
                throw conflict();
            }
            // 当前草稿必须经修订继续使用；提前返回领域冲突，不能把唯一约束错误伪装成存储不可用。
            if (scalar(c.plan().countOpenPlansByOwner(command.ownerThreadId())) != 0)
                {
                    throw invalid("Thread already has an unfinished Plan");
                }
            requireOwnerAdmissionOpen(c, command.ownerThreadId());
            String at = at(command.at());
            one(c.plan().insertPlansWithPlanId(command.planId(), command.ownerThreadId(), command.objective(), command.operationKey(), at, at));
            planEvent(c, command.planId(), 0, "created", "evt_" + command.planId(), command.operationKey(), at);
            return requirePlan(c, command.planId());
        });
    }




    /**
     * 单事务组装独立 Plan snapshot。
     */
    @Override
    public PlanSnapshot readPlanSnapshot(String planId) {
        return transactions.required(c -> planSnapshot(c, requirePlan(c, planId)));
    }

    /**
     * 按数据库中的 Thread owner 关系在同一读事务解析当前 Plan，不依赖调用方缓存旧引用。
     */
    @Override
    public Optional<PlanSnapshot> findCurrentPlan(String ownerThreadId) {
        return transactions.required(c -> {
            Plan plan = queryOne(readPlanRow(() -> c.plan().findPlansByOwnerThreadId(ownerThreadId)), this::mapPlan);
            return plan == null ? Optional.empty() : Optional.of(planSnapshot(c, plan));
        });
    }

    /**
     * Plan 观察只查询当前步骤摘要和最新事件，隐藏 Workbench 不会因此加载正文或 evidence。
     */
    @Override
    public PlanRepository.PlanObservation readPlanObservation(String planId) {
        return transactions.required(c -> {
            Plan plan = requirePlan(c, planId);
            PlanEvent event = queryOne(c.plan().findPlanEventsByPlanId(planId), this::mapPlanEvent);
            List<StepProgressRow> rows = plan.activePlanRevisionId() == null || plan.activeRunId() == null
                    ? List.of()
                    : queryList(c.plan().listPlanStepsByPlanRevisionId(plan.activeRunId(), plan.activePlanRevisionId()),
                    r -> new StepProgressRow(r.stepId(), r.title(), r.required() == 1, r.status()));
            return new PlanRepository.PlanObservation(plan, event, progress(rows));
        });
    }

    /**
     * 草稿缺失不回退。
     */
    @Override
    public Optional<PlanDraft> findDraft(String planId) {
        return transactions.required(c -> Optional.ofNullable(queryOne(c.plan().findPlanDraftsByPlanId(planId), this::mapDraft)));
    }

    /**
     * 保存前要求旧 Run 的 Turn ledger 均真实收口，防止 draft 替换掩盖失败的终态记账。
     */
    @Override
    public Plan saveDraft(SaveDraft command) {
        return planMutation(command.planId(), command.expectedPlanRevision(), command.operationKey(),
                Set.of("draft_saved"), c -> {
                    Plan before = requirePlan(c, command.planId());
                    if (before.status() == PlanStatus.EXECUTING || before.status() == PlanStatus.VERIFYING) {
                        throw invalid("Plan must be paused before editing");
                    }
                    if (before.activeRunId() != null && before.status() == PlanStatus.PAUSED) {
                        requireSettledPlanTurnClaims(c, before.activeRunId());
                        long unsettled = scalar(c.plan().findTurnsByState(before.activeRunId()));
                        if (unsettled > 0) {
                            throw invalid("Plan pause is not settled for editing");
                        }
                        stopRun(c, before.activeRunId(), command.at());
                    }
                    PlanDraft current = queryOne(c.plan().findPlanDraftsByPlanId(command.planId()), this::mapDraft);
                    if ((current == null ? 0 : current.draftRevision()) != command.expectedDraftRevision())
                        {
                            throw conflict();
                        }
                    String draftId = current == null ? "plandraft_" + command.planId() : current.planDraftId();
                    one(c.plan().insertPlanDraftsWithPlanDraftId(draftId, command.planId(), command.definitionJson(), command.basedOnPlanRevisionId(), at(command.at())));
                    planCas(c, before, PlanStatus.DRAFT, null, null, command.at());
                    planEvent(c, command.planId(), before.revision() + 1, "draft_saved", command.eventId(), command.operationKey(), at(command.at()));
                    return requirePlan(c, command.planId());
                });
    }

    /**
     * 只丢弃真实且处于可编辑状态的草稿；旧 Run 仍绑定时先在同事务核实 claim 和活动 Turn 已收口，
     * 再恢复草稿基于的批准 revision，避免空删除意外清除可恢复执行身份。
     */
    @Override
    public Plan discardDraft(DiscardDraft command) {
        return planMutation(command.planId(), command.expectedPlanRevision(), command.operationKey(),
                Set.of("draft_discarded"), c -> {
                    Plan before = requirePlan(c, command.planId());
                    if (before.status() != PlanStatus.DRAFT && before.status() != PlanStatus.PAUSED) {
                        throw invalid("Plan draft cannot be discarded in the current state");
                    }
                    PlanDraft draft = queryOne(c.plan().findPlanDraftsByPlanId(command.planId()), this::mapDraft);
                    if (draft == null) {
                        throw invalid("Plan draft does not exist");
                    }
                    if (before.activeRunId() != null) {
                        requireSettledPlanTurnClaims(c, before.activeRunId());
                        if (scalar(c.plan().findTurnsByState(before.activeRunId())) > 0) {
                            throw invalid("Plan pause is not settled for editing");
                        }
                        stopRun(c, before.activeRunId(), command.at());
                    }
                    String approvedBaseline = draft.basePlanRevisionId() == null ? null
                            : c.plan().findPlanApprovalsByPlanIdPlanRevisionIdDecision(command.planId(), draft.basePlanRevisionId());
                    one(c.plan().deletePlanDrafts(command.planId()));
                    planCas(c, before, approvedBaseline == null ? PlanStatus.DRAFT : PlanStatus.APPROVED,
                            approvedBaseline, null, command.at());
                    planEvent(c, command.planId(), before.revision() + 1, "draft_discarded", command.eventId(),
                            command.operationKey(), at(command.at()));
                    return requirePlan(c, command.planId());
                });
    }

    /**
     * 冻结 canonical Plan revision。
     */
    @Override
    public PlanRevision propose(ProposePlan command) {
        PlanPolicy.validate(command.revision().definition());
        return transactions.required(c -> {
            String replay = c.plan().findPlanEventsByPlanIdOperationKey(command.planId(), command.operationKey());
            if (replay != null && replay.startsWith("plan_proposed:"))
                {
                    return requireRevision(c, command.planId(), replay.substring(14));
                }
            Plan before = requirePlanRevision(c, command.planId(), command.expectedPlanRevision());
            if (before.status() != PlanStatus.DRAFT) {
                throw invalid("Plan is not accepting proposals");
            }
            long pendingRequired = scalar(c.plan().findInteractionRequestsByThreadIdStatus(before.ownerThreadId()));
            if (pendingRequired != 0) {
                throw invalid("Plan cannot be finalized while a required question is pending");
            }
            int next = Math.toIntExact(scalar(c.plan().nextPlanRevisionNumber(command.planId())));
            PlanRevision value = command.revision();
            one(c.plan().insertPlanRevisionsWithPlanRevisionId(value.planRevisionId(), command.planId(), next, value.canonicalJson(), value.planHash(), value.createdBy(), at(value.createdAt())));
            for (int i = 0; i < value.definition().steps().size(); i++) {
                PlanStep step = value.definition().steps().get(i);
                one(c.plan().insertPlanStepsWithPlanRevisionId(value.planRevisionId(), step.stepId(), i, step.title(), step.description(), step.required() ? 1 : 0, json.writeValueAsString(step.dependsOn())));
            }
            for (int i = 0; i < value.definition().acceptanceCriteria().size(); i++) {
                AcceptanceCriterion item = value.definition().acceptanceCriteria().get(i);
                one(c.plan().insertAcceptanceCriteriaWithPlanRevisionId(value.planRevisionId(), item.criterionId(), i, item.description(), item.required() ? 1 : 0));
            }
            c.plan().deletePlanDrafts(command.planId());
            planCas(c, before, PlanStatus.AWAITING_APPROVAL, null, null, command.at());
            planEvent(c, command.planId(), before.revision() + 1, "plan_proposed:" + value.planRevisionId(), command.eventId(), command.operationKey(), at(command.at()));
            return new PlanRevision(value.planRevisionId(), command.planId(), next, value.definition(), value.canonicalJson(), value.planHash(), value.createdBy(), value.createdAt());
        });
    }

    /**
     * 批准只记录 USER_UI decision。
     */
    @Override
    public Plan approve(ApprovePlan command) {
        return planMutation(command.planId(), command.expectedPlanRevision(), command.operationKey(), Set.of("plan_approved"), c -> {
            Plan before = requirePlan(c, command.planId());
            if (before.status() != PlanStatus.AWAITING_APPROVAL) {
                throw invalid("Plan is not awaiting approval");
            }
            PlanRevision rev = requireLatest(c, command.planId(), command.planRevisionId(), command.planHash());
            one(c.plan().insertPlanApprovalsWithApprovalId(command.approvalId(), command.planId(), rev.planRevisionId(), rev.planHash(), at(command.at())));
            planCas(c, before, PlanStatus.APPROVED, rev.planRevisionId(), null, command.at());
            planEvent(c, command.planId(), before.revision() + 1, "plan_approved", command.eventId(), command.operationKey(), at(command.at()));
            return requirePlan(c, command.planId());
        });
    }

    /**
     * 执行是唯一的用户授权边界：AWAITING_APPROVAL 在此事务内同时写入审计与 Run，
     * 防止前端先批准再执行造成双击、丢响应或版本竞态；APPROVED 仅用于恢复既有数据库事实。
     */
    @Override
    public Plan executePlan(ExecutePlan command) {
        return planMutation(command.planId(), command.expectedPlanRevision(), command.operationKey(), Set.of("plan_execution_started"), c -> {
            Plan before = requirePlan(c, command.planId());
            requireOwnerAdmissionOpen(c, before.ownerThreadId());
            if (before.status() != PlanStatus.AWAITING_APPROVAL && before.status() != PlanStatus.APPROVED)
                {
                    throw invalid("Plan is not ready to execute");
                }
            requireNoActiveStandaloneRun(c, command.planId());
            PlanRevision rev = before.status() == PlanStatus.AWAITING_APPROVAL ? requireLatest(c, command.planId(), command.planRevisionId(), command.planHash()) : requireApproved(c, command.planId(), command.planRevisionId(), command.planHash());
            if (before.status() == PlanStatus.AWAITING_APPROVAL)
                {
                    one(c.plan().insertPlanApprovalsWithApprovalId(command.approvalId(), command.planId(), rev.planRevisionId(), rev.planHash(), at(command.at())));
                }
            insertRun(c, command.runId(), command.planId(), rev.planRevisionId(), rev.planHash(), command.processGeneration(), at(command.at()));
            insertSteps(c, command.runId(), rev, command.at());
            planCas(c, before, PlanStatus.EXECUTING, rev.planRevisionId(), command.runId(), command.at());
            planEvent(c, command.planId(), before.revision() + 1, "plan_execution_started", command.eventId(), command.operationKey(), at(command.at()));
            return requirePlan(c, command.planId());
        });
    }

    /**
     * 普通执行失败持久为可恢复暂停并保留 Run/revision；未结算 terminal claim 留作恢复 blocker，
     * 已提交的 pause fence 则由显式 Pause/Stop 流程收口。
     */
    @Override
    public Plan settlePlanExecution(SettlePlanExecution command) {
        return planMutation(command.planId(), command.expectedPlanRevision(), command.operationKey(), Set.of("plan_execution_incomplete"), c -> {
            Plan before = requirePlan(c, command.planId());
            if ((before.status() != PlanStatus.EXECUTING && before.status() != PlanStatus.VERIFYING) || !Objects.equals(before.activeRunId(), command.runId()))
                {
                    return before;
                }
            RunState run = queryOne(c.plan().findExecutionRunsByRunId(command.runId()), this::mapRunState);
            if (run == null || !Objects.equals(run.planId(), command.planId())
                || !Objects.equals(run.planRevision(), before.activePlanRevisionId())) {
                throw invalid("Plan execution identity is invalid");
            }
            if (run.pauseRequested()) {
                return before;
            }
            abandonUnadmittedPlanTurnClaims(c, command.runId());
            transitionRun(c, command.runId(), "PAUSED", command.at());
            planCas(c, before, PlanStatus.PAUSED, before.activePlanRevisionId(), before.activeRunId(), command.at());
            planEvent(c, command.planId(), before.revision() + 1, "plan_execution_incomplete", command.eventId(), command.operationKey(), at(command.at()));
            return requirePlan(c, command.planId());
        });
    }

    /**
     * 原子校验 Run pause fence 并领取 Turn；同一 Turn 重试也必须重新通过准入边界。
     */
    @Override
    public Optional<PlanTurnClaim> claimPlanTurn(ClaimPlanTurn command) {
        return transactions.required(c -> {
            Plan before = requirePlan(c, command.planId());
            requireOwnerAdmissionOpen(c, before.ownerThreadId());
            if (before.revision() != command.expectedPlanRevision()
                || !Objects.equals(before.activeRunId(), command.runId())
                || !Objects.equals(before.activePlanRevisionId(), command.planRevisionId())) {
                    throw conflict();
                }
            if (before.status() != PlanStatus.EXECUTING) {
                return Optional.empty();
            }
            RunState run = queryOne(c.plan().findExecutionRunsByRunId(command.runId()), this::mapRunState);
            if (run == null || !Objects.equals(run.planId(), command.planId())
                || !Objects.equals(run.planRevision(), command.planRevisionId())
                || !"RUNNING".equals(run.status()) || run.pauseRequested()) {
                    return Optional.empty();
            }
            ClaimRow existing = queryOne(c.plan().findPlanTurnClaimsByRunIdTurnId(command.runId(), command.turnId()),
                    r -> new ClaimRow(r.ordinal(), r.state()));
            if (existing != null && "CLAIMED".equals(existing.state())) {
                return Optional.of(new PlanTurnClaim(command.planId(), command.runId(),
                        command.planRevisionId(), command.turnId(), existing.ordinal()));
            }
            // SETTLED/ABANDONED claim 已经有终态事实，不能让重试用同一 turnId 重新执行。
            if (existing != null) {
                return Optional.empty();
            }
            Integer activeClaim = c.plan().findPlanTurnClaimsByRunIdState(command.runId());
            if (activeClaim != null) {
                return Optional.empty();
            }
            int ordinal = Math.toIntExact(scalar(c.plan().findPlanTurnClaimsByRunId(command.runId())));
            one(c.plan().updateExecutionRunsSettingUpdatedAtByRunId(at(command.at()), command.runId()));
            one(c.plan().insertPlanTurnClaimsWithRunId(command.runId(), command.turnId(), ordinal, at(command.at())));
            return Optional.of(new PlanTurnClaim(command.planId(), command.runId(), command.planRevisionId(),
                    command.turnId(), ordinal));
        });
    }

    /**
     * 仅在同一事务读到持久终态 Turn 后释放 claim；Future 异常或未知 Turn 状态都保留阻塞事实。
     */
    @Override
    public void settlePlanTurn(SettlePlanTurn command) {
        transactions.required(c -> {
            PlanSettlementContext context = planSettlementContext(c, command.planId(), command.runId(),
                    command.operationKey());
            if (context == null) {
                return null;
            }
            Plan before = context.plan();
            RunState run = context.run();
            PlanRecords.PlanTurnClaimRow claim = c.plan().findPlanTurnClaimsByRunIdTurnId(
                    command.runId(), command.turnId());
            if (claim == null) {
                throw invalid("Plan Turn claim does not exist");
            }
            if ("SETTLED".equals(claim.state())) {
                return null;
            }
            if (!"CLAIMED".equals(claim.state())) {
                throw invalid("Plan Turn claim cannot be settled");
            }
            String turnState = c.plan().findConversationTurnStateByTurnId(command.turnId());
            if (turnState == null || !Set.of("COMPLETED", "FAILED", "CANCELLED").contains(turnState)) {
                throw invalid("Plan Turn is not persistently terminal");
            }
            int modelRounds = Math.toIntExact(scalar(c.plan().findUsageByTurnIdPurpose(command.turnId())));
            int toolCalls = Math.toIntExact(scalar(c.plan().findToolsByTurnId(command.turnId())));
            int nextRounds = Math.addExact(run.usedModelRounds(), modelRounds);
            int nextTools = Math.addExact(run.usedToolCalls(), toolCalls);
            long nextWall = Math.addExact(run.usedActiveMillis(), command.activeMillis());
            one(c.plan().updateExecutionRunsSettingUsedModelRoundsByRunId(nextRounds, nextTools, nextWall, at(command.at()), command.runId()));
            // Claim 与 Turn usage 在同一事务释放；挂起不会调用该入口，因而保留同一 turnId 继续。
            one(c.plan().settlePlanTurnClaim(command.runId(), command.turnId()));
            planEvent(c, command.planId(), before.revision(), "plan_turn_settled",
                    command.eventId(), command.operationKey(), at(command.at()));
            return null;
        });
    }

    /**
     * 交互或人工暂停结束一个活动片段时只结算墙钟；模型和 Tool 用量留到 Turn 最终终态统一结算，
     * 避免同一 Turn 在多次恢复间重复累计。
     */
    @Override
    public void settlePlanTurnActivity(SettlePlanTurnActivity command) {
        transactions.required(c -> {
            PlanSettlementContext context = planSettlementContext(c, command.planId(), command.runId(),
                    command.operationKey());
            if (context == null) {
                return null;
            }
            Plan before = context.plan();
            RunState run = context.run();
            Integer claimed = c.plan().findPlanTurnClaim(command.runId(), command.turnId());
            if (claimed == null) {
                return null;
            }
            long nextWall = Math.addExact(run.usedActiveMillis(), command.activeMillis());
            one(c.plan().updateExecutionRunsSettingUsedActiveMillisByRunId(nextWall, at(command.at()), command.runId()));
            planEvent(c, command.planId(), before.revision(), "plan_turn_activity_settled",
                    command.eventId(), command.operationKey(), at(command.at()));
            return null;
        });
    }

    /**
     * 先写持久 pause fence，控制 adapter 随后才可取消正在运行的真实 Turn。
     */
    @Override
    public Plan requestPlanPause(RequestPlanPause command) {
        return transactions.required(c -> {
            Plan before = requirePlan(c, command.planId());
            return applyPauseFence(c, command, before);
        });
    }

    /**
     * 用户 pause/stop 的幂等判定与持久 pause fence 同事务提交，避免重放或跨动作复用先控制真实 Turn。
     */
    @Override
    public PlanControlFenceResult requestPlanControlFence(RequestPlanPause command, PlanControlAction action) {
        Objects.requireNonNull(action, "action");
        return transactions.required(c -> {
            String activity = c.plan().findPlanEventsByPlanIdOperationKey(command.planId(), command.operationKey());
            if (activity != null) {
                if (!action.activity().equals(activity)) {
                    throw invalid("Plan idempotency key was reused");
                }
                return new PlanControlFenceResult(requirePlan(c, command.planId()), false);
            }

            Plan before = requirePlan(c, command.planId());
            Plan fenced = applyPauseFence(c, command, before);
            requireControlActionAllowed(fenced, action);
            return new PlanControlFenceResult(fenced, true);
        });
    }

    /**
     * 保持 owner-close 与用户控制共用相同 run/revision CAS 和 pause marker，且不复制事务写入规则。
     */
    private Plan applyPauseFence(PlanUnitOfWork.Mappers c, RequestPlanPause command, Plan before)
            throws SQLException {
        if (before.revision() != command.expectedPlanRevision()
            || !Objects.equals(before.activeRunId(), command.runId())) {
            throw conflict();
        }
        if (before.status() == PlanStatus.EXECUTING || before.status() == PlanStatus.VERIFYING) {
            one(c.plan().updateExecutionRunsSettingPauseRequestedByRunId(
                    at(command.at()), command.runId(), command.planId()));
        }
        return before;
    }

    /**
     * 先在写事务中拒绝不可能完成的动作；PAUSED 仍可 stop，但不可由新 pause key 重复暂停。
     */
    private static void requireControlActionAllowed(Plan plan, PlanControlAction action) {
        boolean pausable = plan.status() == PlanStatus.EXECUTING || plan.status() == PlanStatus.VERIFYING;
        boolean stoppable = pausable || plan.status() == PlanStatus.PAUSED;
        if (action == PlanControlAction.PAUSE && !pausable) {
            throw invalid("Plan is not pausable");
        }
        if (action == PlanControlAction.STOP && !stoppable) {
            throw invalid("Plan is not stoppable");
        }
    }

    /**
     * reject 回到 DRAFT。
     */
    @Override
    public Plan reject(RejectPlan command) {
        return planMutation(command.planId(), command.expectedPlanRevision(), command.operationKey(), Set.of("plan_rejected"), c -> {
            Plan before = requirePlan(c, command.planId());
            if (before.status() != PlanStatus.AWAITING_APPROVAL) {
                throw invalid("Plan is not awaiting rejection");
            }
            PlanRevision rev = requireLatest(c, command.planId(), command.planRevisionId(), command.planHash());
            one(c.plan().insertRejectedPlanApproval(command.approvalId(), command.planId(), rev.planRevisionId(), rev.planHash(), at(command.at())));
            planCas(c, before, PlanStatus.DRAFT, null, null, command.at());
            planEvent(c, command.planId(), before.revision() + 1, "plan_rejected", command.eventId(), command.operationKey(), at(command.at()));
            return requirePlan(c, command.planId());
        });
    }





    /**
     * Plan step 只写入步骤与证据；当前 Tool 尚未完成时不能提前进入 VERIFYING。
     */
    @Override
    public Plan updatePlanStep(UpdatePlanStep command) {
        return planMutation(command.planId(), command.expectedPlanRevision(), command.operationKey(), Set.of("step_updated"), c -> {
            Plan before = requirePlan(c, command.planId());
            if (before.status() != PlanStatus.EXECUTING || !Objects.equals(before.activeRunId(), command.runId()))
                {
                    throw invalid("Plan run is not active");
                }
            stepCas(c, command.runId(), command.stepId(), command.expectedStatus(), command.status(), command.failureSignature(), command.at());
            appendClaims(c, command.evidenceClaims(), command.runId(), command.planId(), before.activePlanRevisionId(), command.stepId(), command.at());
            planCas(c, before, PlanStatus.EXECUTING, before.activePlanRevisionId(), before.activeRunId(), command.at());
            planEvent(c, command.planId(), before.revision() + 1, "step_updated", command.eventId(), command.operationKey(), at(command.at()));
            return requirePlan(c, command.planId());
        });
    }

    /**
     * Turn 与 Tool 均已结算后才允许验收；CAS 失败或仍有 blocker 时保持原状态。
     */
    @Override
    public Plan beginPlanVerification(BeginPlanVerification command) {
        return planMutation(command.planId(), command.expectedPlanRevision(), command.operationKey(), Set.of("plan_verifying"), c -> {
            Plan before = requirePlan(c, command.planId());
            if (before.status() != PlanStatus.EXECUTING || !Objects.equals(before.activeRunId(), command.runId()))
                {
                    return before;
                }
            requireRevision(c, command.planId(), before.activePlanRevisionId());
            if (planBlockers(c, command.runId(), before.activePlanRevisionId()) != 0) {
                return before;
            }
            transitionRun(c, command.runId(), "VERIFYING", command.at());
            planCas(c, before, PlanStatus.VERIFYING, before.activePlanRevisionId(), before.activeRunId(), command.at());
            planEvent(c, command.planId(), before.revision() + 1, "plan_verifying", command.eventId(), command.operationKey(), at(command.at()));
            return requirePlan(c, command.planId());
        });
    }

    /**
     * 验收结算重验冻结 revision 和 Run；已提交 pause fence 优先于 MET/NOT_MET，避免越过用户暂停。
     */
    @Override
    public Plan completePlanVerification(CompletePlanVerification command) {
        return planMutation(command.planId(), command.expectedPlanRevision(), command.operationKey(), Set.of("plan_completed", "plan_verification_failed", "plan_verification_inconclusive"), c -> {
            Plan before = requirePlan(c, command.planId());
            if (before.status() != PlanStatus.VERIFYING || !Objects.equals(before.activeRunId(), command.runId()))
                {
                    throw invalid("Plan verification is stale");
                }
            RunState run = queryOne(c.plan().findExecutionRunsByRunId(command.runId()), this::mapRunState);
            if (run == null || !Objects.equals(run.planId(), command.planId())
                || !Objects.equals(run.planRevision(), before.activePlanRevisionId())
                || !"VERIFYING".equals(run.status())) {
                throw invalid("Plan verification is stale");
            }
            if (run.pauseRequested()) {
                return before;
            }
            requireRevision(c, command.planId(), before.activePlanRevisionId());
            if (command.verdict() == PlanModels.EvaluationVerdict.MET && planBlockers(c, command.runId(), before.activePlanRevisionId()) != 0)
                {
                    throw error(PlanRepositoryException.Code.PLAN_EVIDENCE_INCOMPLETE, "Plan completion evidence is incomplete");
                }
            PlanStatus status = switch (command.verdict()) {
                case MET -> PlanStatus.COMPLETED;
                case NOT_MET -> PlanStatus.EXECUTING;
                case INCONCLUSIVE -> PlanStatus.PAUSED;
            };
            String runStatus = switch (command.verdict()) {
                case MET -> "COMPLETED";
                case NOT_MET -> "RUNNING";
                case INCONCLUSIVE -> "PAUSED";
            };
            transitionRun(c, command.runId(), runStatus, command.at());
            if (command.verdict() == PlanModels.EvaluationVerdict.NOT_MET)
                {
                    one(c.plan().clearPlanRunPauseRequest(at(command.at()), command.runId()));
                }
            planCas(c, before, status, before.activePlanRevisionId(), before.activeRunId(), command.at());
            String activity = switch (command.verdict()) {
                case MET -> "plan_completed";
                case NOT_MET -> "plan_verification_failed";
                case INCONCLUSIVE -> "plan_verification_inconclusive";
            };
            planEvent(c, command.planId(), before.revision() + 1, activity, command.eventId(), command.operationKey(), at(command.at()));
            return requirePlan(c, command.planId());
        });
    }

    /**
     * pause 只冻结新 Tool admission，保留 run identity 供 Resume 使用。
     */
    @Override
    public Plan pausePlan(PausePlan command) {
        return planMutation(command.planId(), command.expectedPlanRevision(), command.operationKey(), Set.of("plan_paused"), c -> {
            Plan before = requirePlan(c, command.planId());
            if ((before.status() != PlanStatus.EXECUTING && before.status() != PlanStatus.VERIFYING) || !Objects.equals(before.activeRunId(), command.runId()))
                {
                    throw invalid("Plan is not pausable");
                }
            transitionRun(c, command.runId(), "PAUSED", command.at());
            planCas(c, before, PlanStatus.PAUSED, before.activePlanRevisionId(), before.activeRunId(), command.at());
            planEvent(c, command.planId(), before.revision() + 1, "plan_paused", command.eventId(), command.operationKey(), at(command.at()));
            return requirePlan(c, command.planId());
        });
    }

    /**
     * 仅按同一事务内的 attempt 持久事实判断未结算结果，供手动恢复和启动恢复共用。
     */
    private static boolean hasUnsettledPlanToolAttempt(PlanUnitOfWork.Mappers c, String runId) {
        return c.plan().findUnsettledPlanToolAttemptByRunId(runId) != null;
    }

    /**
     * 仅在当前挂起游标、Run claim、不可变 binding 与 Core/Plan PREPARED 事实完全配对时允许手动恢复；
     * 串行 Tool cursor 只允许 nextOrdinal 的唯一当前调用已准备，后续批次调用仍可未准备；该证明不用于启动恢复，
     * 避免把跨进程未知副作用解释成安全重放。
     *
     * @author kongweiguang
     */
    private boolean hasCurrentSuspendedPreparedToolProof(PlanUnitOfWork.Mappers c, Plan plan,
                                                         PlanRevision revision, String runId)
            throws SQLException {
        List<PlanRecords.PlanToolAttemptRow> unsettled = c.plan().listUnsettledPlanToolAttemptsByRunId(runId);
        if (unsettled == null || unsettled.size() != 1) {
            return false;
        }
        PlanRecords.PlanToolAttemptRow attempt = unsettled.getFirst();
        if (!"PREPARED".equals(attempt.state()) || attempt.startedAt() != null || attempt.completedAt() != null
                || attempt.resultDigest() != null || attempt.sideEffect() < 0 || attempt.sideEffect() > 1
                || !plan.planId().equals(attempt.planId()) || !runId.equals(attempt.runId())
                || !revision.planRevisionId().equals(attempt.planRevisionId()) || attempt.turnId() == null
                || attempt.callId() == null || attempt.processGeneration() < 1) {
            return false;
        }
        PlanRecords.ExecutionRunStateRow run = c.plan().findExecutionRunsByRunId(runId);
        PlanRecords.PlanTurnClaimRow claim = c.plan().findPlanTurnClaimsByRunIdTurnId(runId, attempt.turnId());
        if (run == null || !"PAUSED".equals(run.status()) || !plan.planId().equals(run.planId())
                || !revision.planRevisionId().equals(run.planRevisionId())
                || claim == null || !"CLAIMED".equals(claim.state())
                || !TurnState.SUSPENDED.name().equals(c.plan().findConversationTurnStateByTurnId(attempt.turnId()))) {
            return false;
        }
        InternalTurnBinding binding = findInternalTurnBinding(c, attempt.turnId()).orElse(null);
        if (binding == null || !TurnOrigin.PLAN_EXECUTION.name().equals(binding.origin())
                || !plan.planId().equals(binding.planId()) || !runId.equals(binding.runId())
                || !revision.planRevisionId().equals(binding.planRevisionId())
                || !revision.planHash().equals(binding.planHash())) {
            return false;
        }

        String snapshotJson = c.plan().findPlanResumeCoreExecutionSnapshot(plan.ownerThreadId(), attempt.turnId(), runId);
        if (snapshotJson == null) {
            return false;
        }
        ObjectNode snapshot = resumeEvidenceObject(snapshotJson);
        String userId = resumeEvidenceText(snapshot, "userId");
        long mutationVersion = resumeEvidenceLong(snapshot, "mutationVersion");
        long lastRecordSequence = resumeEvidenceLong(snapshot, "lastRecordSequence");
        int cursorSchemaVersion = resumeEvidenceInt(snapshot, "cursorSchemaVersion");
        String cursorJson = resumeEvidenceText(snapshot, "cursorJson");
        if (!plan.planId().equals(resumeEvidenceText(snapshot, "runPlanId"))
                || !revision.planRevisionId().equals(resumeEvidenceText(snapshot, "runRevisionId"))
                || !revision.planHash().equals(resumeEvidenceText(snapshot, "runHash"))
                || !TurnState.SUSPENDED.name().equals(resumeEvidenceText(snapshot, "appTurnState"))
                || !TurnState.SUSPENDED.name().equals(resumeEvidenceText(snapshot, "coreState"))
                || mutationVersion < 1 || lastRecordSequence < 1) {
            return false;
        }
        TurnExecutionState cursor;
        try {
            JsonNode cursorNode = json.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(cursorJson);
            JsonValue cursorValue = JacksonJsonValues.fromNode(cursorNode);
            if (!(cursorValue instanceof JsonObject cursorObject)) {
                return false;
            }
            cursor = new TurnExecutionStateCodec(json).read(cursorSchemaVersion, cursorObject);
        } catch (JsonProcessingException | RuntimeException failure) {
            throw invalid("Stored Core execution cursor is invalid", failure);
        }
        if (!(cursor instanceof TurnExecutionState.Tools tools)) {
            return false;
        }
        return matchesCurrentPreparedTool(c, plan, revision, runId, attempt, tools, userId,
                mutationVersion, lastRecordSequence);
    }

    /**
     * 只接受与 cursor ordinal 对应的唯一已完成模型响应和 ToolPrepared，并同时确认 Started/Result 证据缺失。
     *
     * @author kongweiguang
     */
    private boolean matchesCurrentPreparedTool(PlanUnitOfWork.Mappers c, Plan plan, PlanRevision revision,
                                               String runId, PlanRecords.PlanToolAttemptRow attempt,
                                               TurnExecutionState.Tools cursor, String userId,
                                               long mutationVersion, long lastRecordSequence)
            throws SQLException {
        List<String> recordRows = c.plan().listPlanResumeCoreToolRecords(plan.ownerThreadId(), attempt.turnId(),
                userId, cursor.assistantMessageId(), attempt.callId(), lastRecordSequence);
        if (recordRows == null || recordRows.isEmpty()) {
            return false;
        }
        StorePayloadCodec records = new StorePayloadCodec(json);
        ExecutionFacts.AssistantMessage assistant = null;
        String assistantRecordId = null;
        ExecutionFacts.ToolPrepared prepared = null;
        int assistantCount = 0;
        int preparedCount = 0;
        boolean started = false;
        boolean result = false;
        for (String rowJson : recordRows) {
            ObjectNode row = resumeEvidenceObject(rowJson);
            String recordType = resumeEvidenceText(row, "recordType");
            ExecutionFact fact;
            try {
                fact = records.decode(recordType, resumeEvidenceInt(row, "schemaVersion"),
                        resumeEvidenceText(row, "payloadJson"));
            } catch (RuntimeException failure) {
                throw invalid("Stored Core Tool resume facts are invalid", failure);
            }
            if (fact instanceof ExecutionFacts.AssistantMessage message) {
                if (message.messageId().equals(cursor.assistantMessageId())) {
                    assistant = message;
                    assistantRecordId = resumeEvidenceText(row, "recordId");
                    assistantCount++;
                }
            } else if (fact instanceof ExecutionFacts.ToolPrepared tool) {
                if (tool.callId().equals(attempt.callId())) {
                    prepared = tool;
                    preparedCount++;
                }
            } else if (fact instanceof ExecutionFacts.ToolStarted tool && tool.callId().equals(attempt.callId())) {
                started = true;
            } else if (fact instanceof ExecutionFacts.ToolResult tool && tool.callId().equals(attempt.callId())) {
                result = true;
            }
        }
        if (assistantCount != 1 || preparedCount != 1 || started || result || assistant == null
                || prepared == null || assistantRecordId == null) {
            return false;
        }
        List<ToolCallContent> calls = assistant.message().content().stream()
                .filter(ToolCallContent.class::isInstance).map(ToolCallContent.class::cast).toList();
        long cursorCallCount = (long) cursor.lastOrdinal() - cursor.firstOrdinal() + 1;
        int callIndex = cursor.nextOrdinal() - cursor.firstOrdinal();
        if (cursorCallCount != calls.size() || callIndex < 0 || callIndex >= calls.size()) {
            return false;
        }
        ToolCallContent current = calls.get(callIndex);
        boolean sideEffect = prepared.sideEffect() != ToolSideEffect.READ_ONLY;
        if (!current.callId().equals(attempt.callId()) || !current.callId().equals(prepared.callId())
                || !current.name().equals(prepared.toolName()) || prepared.binding() == null
                || !prepared.batchId().equals(cursor.batchId()) || prepared.ordinal() != cursor.nextOrdinal()
                || !runId.equals(attempt.runId()) || !plan.planId().equals(attempt.planId())
                || !revision.planRevisionId().equals(attempt.planRevisionId())
                || (attempt.sideEffect() == 1) != sideEffect
                || !PlanDigest.sha256(prepared.toolName() + '\n' + prepared.arguments()).equals(attempt.requestDigest())) {
            return false;
        }

        List<String> eventRows = c.plan().listPlanResumeCoreToolEvents(plan.ownerThreadId(), attempt.turnId(),
                userId, assistantRecordId, attempt.callId(), mutationVersion, lastRecordSequence);
        if (eventRows == null || eventRows.isEmpty()) {
            return false;
        }
        JacksonExecutionEventCodec events = new JacksonExecutionEventCodec(json);
        int matchingCompletionCount = 0;
        for (String rowJson : eventRows) {
            ObjectNode row = resumeEvidenceObject(rowJson);
            ExecutionEvent event;
            try {
                event = events.decode(resumeEvidenceText(row, "eventType"),
                        resumeEvidenceInt(row, "schemaVersion"), resumeEvidenceText(row, "payloadJson"));
            } catch (RuntimeException failure) {
                throw invalid("Stored Core Tool resume events are invalid", failure);
            }
            if (event instanceof ExecutionEvent.ModelCompleted completed
                    && completed.messageRecordId().equals(assistantRecordId)) {
                List<String> callIds = calls.stream().map(ToolCallContent::callId).toList();
                if (completed.modelRound() != assistant.modelRound() || !completed.toolCallIds().equals(callIds)) {
                    return false;
                }
                matchingCompletionCount++;
            } else if (event instanceof ExecutionEvent.ToolStarted tool
                    && tool.callId().equals(attempt.callId())) {
                return false;
            } else if (event instanceof ExecutionEvent.ToolCompleted tool
                    && tool.callId().equals(attempt.callId())) {
                return false;
            }
        }
        return matchingCompletionCount == 1;
    }

    /**
     * SQL JSON envelope 仅承载同事务已查询的闭集证据；损坏结构按稳定领域错误拒绝恢复。
     *
     * @author kongweiguang
     */
    private ObjectNode resumeEvidenceObject(String source) {
        try {
            JsonNode node = json.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(source);
            if (node instanceof ObjectNode object) {
                return object;
            }
            throw invalid("Stored Core resume evidence is invalid");
        } catch (JsonProcessingException failure) {
            throw invalid("Stored Core resume evidence is invalid", failure);
        }
    }

    /**
     * 恢复证据字符串字段拒绝缺失、null 与隐式类型转换，避免松散 JSON 改变副作用判断。
     *
     * @author kongweiguang
     */
    private static String resumeEvidenceText(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid("Stored Core resume evidence is invalid");
        }
        return value.textValue();
    }

    /**
     * 恢复版本和序号必须是无损整数，不能把文本或浮点数当作持久游标。
     *
     * @author kongweiguang
     */
    private static long resumeEvidenceLong(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid("Stored Core resume evidence is invalid");
        }
        return value.longValue();
    }

    /**
     * schema version 需要精确 int 值，溢出不得通过截断后选择另一种恢复路径。
     *
     * @author kongweiguang
     */
    private static int resumeEvidenceInt(ObjectNode node, String field) {
        long value = resumeEvidenceLong(node, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw invalid("Stored Core resume evidence is invalid");
        }
        return (int) value;
    }

    /**
     * 仅恢复同一获批 Run；保留挂起 claim，只阻止终态 Turn 尚未完成 usage 结算的 Run。
     */
    @Override
    public ResumeResult resumePlan(ResumePlan command) {
        return transactions.required(c -> {
            String activity = c.plan().findPlanEventsByPlanIdOperationKey(command.planId(), command.operationKey());
            if (activity != null) {
                if (!"plan_resumed".equals(activity)) {
                    throw invalid("Plan idempotency key was reused");
                }
                return new ResumeResult(requirePlan(c, command.planId()), false);
            }
            requirePlanRevision(c, command.planId(), command.expectedPlanRevision());
            Plan before = requirePlan(c, command.planId());
            requireOwnerAdmissionOpen(c, before.ownerThreadId());
            if (before.status() != PlanStatus.PAUSED || !Objects.equals(before.activeRunId(), command.runId()))
                {
                    throw invalid("Plan is not resumable");
                }
            PlanRevision revision = requireRevision(c, command.planId(), before.activePlanRevisionId());
            Integer approved = c.plan().findPlanApprovalsByPlanIdPlanRevisionIdPlanHash(
                    command.planId(), revision.planRevisionId(), revision.planHash());
            if (approved == null) {
                throw stale();
            }
            if (hasUnsettledPlanToolAttempt(c, command.runId())
                    && !hasCurrentSuspendedPreparedToolProof(c, before, revision, command.runId())) {
                throw invalid("Plan run has unresolved Tool attempts");
            }
            if (hasTerminalUnsettledPlanTurnClaim(c, command.runId())) {
                throw invalid("Plan Turn settlement is incomplete");
            }
            transitionRun(c, command.runId(), "RUNNING", command.at());
            one(c.plan().clearPlanRunPauseRequest(at(command.at()), command.runId()));
            planCas(c, before, PlanStatus.EXECUTING, before.activePlanRevisionId(), before.activeRunId(), command.at());
            planEvent(c, command.planId(), before.revision() + 1, "plan_resumed", command.eventId(), command.operationKey(), at(command.at()));
            return new ResumeResult(requirePlan(c, command.planId()), true);
        });
    }

    /**
     * 只在 Turn settlement 已提交或无 Turn intent 已放弃后终止 Run，保留 revision 供审计。
     */
    @Override
    public Plan stopPlan(StopPlan command) {
        return planMutation(command.planId(), command.expectedPlanRevision(), command.operationKey(), Set.of("plan_stopped"), c -> {
            Plan before = requirePlan(c, command.planId());
            if ((before.status() != PlanStatus.EXECUTING && before.status() != PlanStatus.VERIFYING && before.status() != PlanStatus.PAUSED) || !Objects.equals(before.activeRunId(), command.runId()))
                {
                    throw invalid("Plan is not stoppable");
                }
            requireSettledPlanTurnClaims(c, command.runId());
            transitionRun(c, command.runId(), "STOPPED", command.at());
            planCas(c, before, PlanStatus.STOPPED, before.activePlanRevisionId(), before.activeRunId(), command.at());
            planEvent(c, command.planId(), before.revision() + 1, "plan_stopped", command.eventId(), command.operationKey(), at(command.at()));
            return requirePlan(c, command.planId());
        });
    }





    /**
     * Tool 执行前写 identity。
     */
    @Override
    public ToolAttempt prepareToolAttempt(PrepareToolAttempt command) {
        return transactions.required(c -> {
            ToolAttempt v = command.attempt();
            Run owner = queryOne(c.plan().findPlanRunBinding(v.runId()), this::mapRun);
            if (owner == null || !Objects.equals(owner.planId, v.planId()) || !Objects.equals(owner.planRevision, v.planRevisionId()))
                {
                    throw stale();
                }
            one(c.plan().insertPlanToolAttemptsWithToolAttemptId(v.toolAttemptId(), v.planId(), v.runId(), v.planRevisionId(), v.stepId(), v.attempt(), v.turnId(), v.callId(), v.processGeneration(), v.sideEffect() ? 1 : 0, v.state().name(), v.requestDigest(), v.resultDigest(), at(v.preparedAt()), v.startedAt() == null ? null : at(v.startedAt()), v.completedAt() == null ? null : at(v.completedAt())));
            return v;
        });
    }

    /**
     * PREPARED -> STARTED 是副作用 fence。
     */
    @Override
    public ToolAttempt startToolAttempt(String toolAttemptId, Instant at) {
        return transactions.required(c -> {
            one(c.plan().updatePlanToolAttemptsSettingStateByToolAttemptId(at(at), toolAttemptId));
            return requireTool(c, toolAttemptId);
        });
    }

    /**
     * Tool terminal、evidence 与可能的 Plan 完成必须位于同一事务：evaluator 可能先于当前 Tool 返回
     * MET，也可能后返回；任一后到者都复用同一完成门，避免崩溃窗口留下永久 WORKING Plan。
     * 相同终态回执按 attempt identity 幂等重放，不重复 evidence、revision 或事件。
     */
    @Override
    public ToolAttempt settleToolAttempt(SettleToolAttempt command) {
        return transactions.required(c -> {
            ToolAttempt before = requireTool(c, command.toolAttemptId());
            if (before.state() == ToolAttemptState.SUCCEEDED || before.state() == ToolAttemptState.FAILED
                || before.state() == ToolAttemptState.CANCELLED) {
                if (before.state() != command.state()
                    || !Objects.equals(before.resultDigest(), command.resultDigest())) {
                    throw invalid("Tool settlement conflicts with terminal attempt");
                }
                return before;
            }
            if (before.state() != ToolAttemptState.STARTED) {
                throw invalid("Tool attempt is not started");
            }
            one(c.plan().settleStartedPlanToolAttempt(command.state().name(), command.resultDigest(), at(command.at()), command.toolAttemptId()));
            if (command.evidence() != null) {
                insertEvidence(c, command.evidence());
            }
            else if (command.state() == ToolAttemptState.SUCCEEDED) {
                insertEvidence(c, new Evidence("evidence_" + before.toolAttemptId(),
                        before.planId(), before.runId(), before.planRevisionId(),
                        null, before.stepId(), EvidenceSource.TOOL_RESULT, before.callId(),
                        "真实 Tool 调用已成功结算", command.resultDigest(), command.at(), command.at()));
            }
            return requireTool(c, command.toolAttemptId());
        });
    }

    /**
     * 仅Core已核验的未启动终态可结束PREPARED；该CAS不填started_at，也不产生成功证据。
     */
    @Override
    public ToolAttempt settleUnstartedToolAttempt(SettleToolAttempt command) {
        Objects.requireNonNull(command, "command");
        if (command.state() != ToolAttemptState.FAILED_BEFORE_START
            && command.state() != ToolAttemptState.CANCELLED_BEFORE_START) {
            throw invalid("Unstarted Tool settlement must be failed or cancelled");
        }
        if (command.evidence() != null || command.resultDigest() == null
            || !command.resultDigest().matches("[0-9a-f]{64}")) {
            throw invalid("Unstarted Tool settlement requires a real result digest without evidence");
        }
        return transactions.required(c -> {
            ToolAttempt before = requireTool(c, command.toolAttemptId());
            if (before.state() == command.state() && before.startedAt() == null
                && Objects.equals(before.resultDigest(), command.resultDigest())) {
                    return before;
                }
            if (before.state() != ToolAttemptState.PREPARED || before.startedAt() != null
                || before.completedAt() != null || before.resultDigest() != null) {
                throw invalid("Tool attempt cannot settle as unstarted");
            }
            one(c.plan().settlePreparedPlanToolAttempt(command.state().name(), command.resultDigest(),
                    at(command.at()), command.toolAttemptId()));
            ToolAttempt settled = requireTool(c, command.toolAttemptId());
            if (settled.state() != command.state() || settled.startedAt() != null
                || settled.completedAt() == null
                || !Objects.equals(settled.resultDigest(), command.resultDigest())) {
                throw invalid("Unstarted Tool settlement did not preserve its execution boundary");
            }
            return settled;
        });
    }

    /**
     * Tool call 可有多个按序重试 attempt，返回完整有序集合由调用方选择唯一活动行。
     */
    @Override
    public List<ToolAttempt> findToolAttemptsByTurnIdCallId(String turnId, String callId) {
        return transactions.required(c -> queryList(
                c.plan().listPlanToolAttemptsByTurnIdCallId(turnId, callId), this::mapTool));
    }

    /**
     * 只把旧 generation 已 STARTED 的 attempt 交给未知副作用恢复；PREPARED 留给 Core 精确完成回调结算。
     */
    @Override
    public List<ToolAttempt> listUnsettledToolAttempts(long generation, int limit) {
        return transactions.required(c -> queryList(
                c.plan().listStartedPlanToolAttemptsByProcessGeneration(generation, limit), this::mapTool));
    }



    /**
     * 恢复在同一事务重验 Tool blocker 与 admitted Turn claim；有真实 claim 时保留 PAUSED binding，
     * 让 terminal settlement 仍可到达，且不改写原 Tool/evaluator blocker。
     */
    @Override
    public Plan recoverPlanExecution(RecoverPlanExecution command) {
        return planMutation(command.planId(), command.expectedPlanRevision(), command.operationKey(),
                Set.of("plan_recovery_required", "plan_execution_recovered"), c -> {
                    Plan before = requirePlan(c, command.planId());
                    if ((before.status() != PlanStatus.EXECUTING && before.status() != PlanStatus.VERIFYING)
                        || !Objects.equals(before.activeRunId(), command.runId())) {
                            return before;
                        }
                    abandonUnadmittedPlanTurnClaims(c, command.runId());
                    boolean admittedClaim = c.plan().findPlanTurnClaimsByRunIdState(command.runId()) != null;
                    boolean unsafe = command.unsafe() || hasUnsettledPlanToolAttempt(c, command.runId());
                    boolean mustStop = unsafe && !admittedClaim;
                    String runStatus = mustStop ? "STOPPED" : "PAUSED";
                    transitionRun(c, command.runId(), runStatus, command.at());
                    PlanStatus status = mustStop ? PlanStatus.STOPPED : PlanStatus.PAUSED;
                    planCas(c, before, status, before.activePlanRevisionId(), before.activeRunId(), command.at());
                    planEvent(c, command.planId(), before.revision() + 1,
                            unsafe ? "plan_recovery_required" : "plan_execution_recovered",
                            command.eventId(), command.operationKey(), at(command.at()));
                    return requirePlan(c, command.planId());
                });
    }

    /**
     * 旧 generation 的执行 run 只暴露恢复所需 identity，未知判断在同一 SQLite 查询完成。
     */
    @Override
    public List<PlanExecutionRecovery> listUnsettledPlanExecutions(long generation, int limit) {
        if (generation < 1 || limit < 1) {
            throw new IllegalArgumentException("invalid recovery page");
        }
        return transactions.required(c -> queryList(c.plan().listPlanExecutionRecoveryCandidates(generation, limit),
                r -> new PlanExecutionRecovery(r.planId(), r.revision(), r.activeRunId(),
                        r.activePlanRevisionId(), r.unsafe() == 1)));
    }

    /**
     * 只放弃没有持久 Turn 的 admission intent；Turn 已存在时必须等待其真实结算。
     */
    @Override
    public void abandonUnadmittedPlanTurnClaims(String runId) {
        transactions.required(c -> {
            c.plan().abandonUnadmittedPlanTurnClaims(runId);
            return null;
        });
    }


    /**
     * 只按 owner 查询正在执行的独立 Plan，避免 continuation 错把任意最新 Plan 当作活动 Run。
     */
    @Override
    public Optional<Plan> findExecutingPlanByOwner(String ownerThreadId) {
        return transactions.required(c -> Optional.ofNullable(queryOne(c.plan().findPlansByOwnerThreadIdStatus(ownerThreadId), this::mapPlan)));
    }

    /**
     * 内部 Turn binding 从不可变 context 读取，Plan 运行身份必须完整一致。
     */
    @Override
    public Optional<InternalTurnBinding> findInternalTurnBinding(String turnId) {
        return transactions.required(c -> findInternalTurnBinding(c, turnId));
    }

    /**
     * 在调用方的读取事务中解析不可变 origin 与完整 Plan/hash identity，拒绝重复键并避免候选与 binding 跨快照。
     *
     * @author kongweiguang
     */
    private Optional<InternalTurnBinding> findInternalTurnBinding(PlanUnitOfWork.Mappers c, String turnId)
            throws SQLException {
        InternalContext row = queryOne(c.plan().findTurnInternalContextByTurnId(turnId),
                r -> new InternalContext(r.origin(), r.contextJson()));
        if (row == null) {
            return Optional.empty();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = json.reader()
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(row.contextJson());
            String origin = row.origin();
            if (root == null || !root.isObject() || !origin.equals(root.path("kind").textValue())) {
                throw invalid("Persisted internal Turn binding is invalid");
            }
            String planId = text(root, "planId");
            String runId = text(root, "runId");
            String planRevisionId = text(root, "planRevisionId");
            String planHash = text(root, "planHash");
            return Optional.of(new InternalTurnBinding(turnId, origin, planId, runId,
                    planRevisionId, planHash));
        } catch (PlanRepositoryException failure) {
            throw failure;
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw invalid("Persisted internal Turn binding is invalid", failure);
        }
    }


    /**
     * 在一个读取事务内定位可控 Turn；仅允许无 Turn 的临时 claim 缺少 binding，已有 Turn 缺失时失败关闭。
     */
    @Override
    public Optional<InternalTurnBinding> findPlanTurnBinding(String planId, String runId) {
        return transactions.required(c -> {
            if (c.plan().findClaimedPlanTurnMissingInternalContext(planId, runId) != null) {
                throw error(PlanRepositoryException.Code.PLAN_RECOVERY_REQUIRED,
                        "Claimed Plan Turn has no matching immutable binding; manual recovery is required");
            }
            PlanRecords.TurnIdRow candidate = c.plan().findTurnInternalContextByOriginState(planId, runId);
            if (candidate == null) {
                return Optional.empty();
            }
            return findInternalTurnBinding(c, candidate.turnId())
                    .filter(binding -> Objects.equals(planId, binding.planId())
                            && Objects.equals(runId, binding.runId()));
        });
    }

    /**
     * 唯一 partial index 已保证 owner 至多一个非终态 Plan。
     */
    @Override
    public Optional<Plan> findActivePlanByOwner(String ownerThreadId) {
        return transactions.required(c -> Optional.ofNullable(queryOne(c.plan().findNonTerminalPlanByOwner(ownerThreadId), this::mapPlan)));
    }



    /**
     * Plan event 页使用自己的 sequence，不能让 Plan 查询水位掩盖执行状态变化。
     */
    @Override
    public ReadPage<PlanEvent> listPlanEvents(String planId, long afterSequence, int limit) {
        return transactions.required(c -> {
            Plan plan = requirePlan(c, planId);
            List<PlanEvent> items = queryList(c.plan().listPlanEventsByPlanIdEventSequence(planId, afterSequence, limit), this::mapPlanEvent);
            return new ReadPage<>(plan.revision(), scalar(c.plan().latestPlanEventSequence(planId)), items);
        });
    }

    /**
     * Plan revision keyset 页。
     */
    @Override
    public ReadPage<PlanRevision> listPlanRevisions(String planId, long afterRevisionNumber, int limit) {
        return transactions.required(c -> {
            Plan plan = requirePlan(c, planId);
            List<PlanRevision> items = queryList(c.plan().listPlanRevisionsByPlanIdRevisionNumber(planId, afterRevisionNumber, limit), this::mapRevision);
            return new ReadPage<>(plan.revision(), scalar(c.plan().latestPlanEventSequence(planId)), items);
        });
    }
    /** pending input 内部投影不暴露响应正文，只保留结算 identity、状态与到期边界。 */


    /**
     * 独立 Plan evaluator 只消费当前 run/revision，禁止把旧版本 evidence 带入完成门。
     */
    @Override
    public List<Evidence> listPlanEvidence(String planId, String runId, String planRevisionId, int limit) {
        return transactions.required(c -> queryList(c.plan().listPlanEvidencePageStart(planId, runId, planRevisionId, limit), this::mapEvidence));
    }

    /**
     * 按创建时间和 ID 继续分页；读取事件水位与 Plan revision 必须共用一次事务快照，
     * 这样重连方才能判断页面与后续事件之间是否存在缺口。
     */
    @Override
    public ReadPage<Evidence> listPlanEvidencePage(String planId, String runId, String planRevisionId,
                                                   String afterCreatedAt, String afterEvidenceId, int limit) {
        return transactions.required(c -> {
            Plan plan = requirePlan(c, planId);
            List<Evidence> items = afterCreatedAt == null
                    ? queryList(c.plan().listPlanEvidencePageStart(planId, runId, planRevisionId, limit), this::mapEvidence)
                    : queryList(c.plan().listPlanEvidencePageAfter(planId, runId, planRevisionId,
                    afterCreatedAt, afterCreatedAt, afterEvidenceId, limit), this::mapEvidence);
            return new ReadPage<>(plan.revision(), scalar(c.plan().latestPlanEventSequence(planId)), items);
        });
    }








    /**
     * Plan 幂等重放必须属于同一操作类型，禁止一个 key 跨批准、执行或恢复边界复用。
     */
    private Plan planMutation(String planId, long expected, String key, Set<String> replayActivities,
                              RepositoryWork work) {
        return transactions.required(c -> {
            String activity = c.plan().findPlanEventsByPlanIdOperationKey(planId, key);
            if (activity != null) {
                if (!replayActivities.contains(activity)) {
                    throw invalid("Plan idempotency key was reused");
                }
                return requirePlan(c, planId);
            }
            requirePlanRevision(c, planId, expected);
            return work.run(c);
        });
    }



    /**
     * evidence claim 必须引用同一 Run 的真实成功 Tool，摘要可来自 Agent，但 digest 与来源由服务端取得。
     */
    private void appendClaims(PlanUnitOfWork.Mappers c, List<ToolEvidenceClaim> claims, String run, String plan, String revision, String step, Instant at) throws SQLException {
        for (ToolEvidenceClaim claim : claims) {
            ToolAttempt source = queryOne(c.plan().findPlanToolAttemptsByRunIdCallIdState(run, claim.callId()), this::mapTool);
            if (source == null) {
                throw invalid("Tool evidence source is invalid");
            }
            requireCriterion(c, revision, claim.criterionId());
            insertEvidence(c, new Evidence(claim.evidenceId(), plan, run, revision, claim.criterionId(), step, EvidenceSource.TOOL_RESULT, claim.callId(), claim.summary(), source.resultDigest(), claim.observedAt(), at));
        }
    }


    /**
     * Tool 行映射保留 nullable owner，避免恢复阶段伪造不存在的关联；持久时间损坏使用脱敏存储错误。
     */
    private ToolAttempt mapTool(PlanRecords.PlanToolAttemptRow r) {
        return new ToolAttempt(r.toolAttemptId(), r.planId(),
                r.runId(), r.planRevisionId(), r.stepId(), r.attempt(), r.turnId(), r.callId(),
                r.processGeneration(), r.sideEffect() == 1, storedEnum(ToolAttemptState.class, r.state()),
                r.requestDigest(), r.resultDigest(), persistedInstant(r.preparedAt(), "plan_tool_attempts.prepared_at"),
                optionalPersistedInstant(r.startedAt(), "plan_tool_attempts.started_at"),
                optionalPersistedInstant(r.completedAt(), "plan_tool_attempts.completed_at"));
    }

    /**
     * evidence 行映射保留可空 owner identity；必填时间损坏时不暴露存储原文。
     */
    private Evidence mapEvidence(PlanRecords.AcceptanceEvidenceRow r) {
        return new Evidence(r.evidenceId(), r.planId(),
                r.runId(), r.planRevisionId(), r.criterionId(), r.stepId(),
                storedEnum(EvidenceSource.class, r.sourceType()), r.sourceId(), r.summary(), r.digest(),
                persistedInstant(r.observedAt(), "acceptance_evidence.observed_at"),
                persistedInstant(r.createdAt(), "acceptance_evidence.created_at"));
    }


    /**
     * 提案版本后缀只供持久幂等回放使用，事件词汇保持稳定；持久时间按固定错误边界解码。
     */
    private PlanEvent mapPlanEvent(PlanRecords.PlanEventRow r) {
        String activity = r.activity();
        if (activity.startsWith("plan_proposed:")) {
            activity = "plan_proposed";
        }
        return new PlanEvent(r.eventSequence(), r.eventId(), r.planId(), r.planRevision(),
                activity, persistedInstant(r.createdAt(), "plan_events.created_at"));
    }



    /**
     * Tool attempt 缺失表示持久执行链断裂，失败关闭可阻止无来源回执被挂接为验收证据。
     */
    private ToolAttempt requireTool(PlanUnitOfWork.Mappers c, String id) throws SQLException {
        ToolAttempt value = queryOne(c.plan().findPlanToolAttemptsByToolAttemptId(id), this::mapTool);
        if (value == null) {
            throw invalid("Tool attempt is unavailable");
        }
        return value;
    }




    /**
     * Run owner 映射保留 SQL null，避免把 Plan-only 与 Plan-only 执行错误合并成同一种 owner。
     */
    private Run mapRun(PlanRecords.PlanRunBindingRow r) {
        return new Run(r.planId(), r.planRevisionId());
    }


    /**
     * Plan row 映射保持 active identity 不变量，必填时间列损坏时以稳定存储分类失败关闭。
     */
    private Plan mapPlan(PlanRecords.PlanRow r) {
        return new Plan(r.planId(), r.ownerThreadId(), r.objective(), storedEnum(PlanStatus.class, r.status()),
                r.revision(), r.activePlanRevisionId(), r.activeRunId(),
                persistedInstant(r.createdAt(), "plans.created_at"),
                persistedInstant(r.updatedAt(), "plans.updated_at"));
    }

    /**
     * 必需枚举列的 null 与未知值都属于损坏行；对外保持脱敏分类，并仅在本地 cause 中保留固定诊断原因。
     */
    private static <E extends Enum<E>> E storedEnum(Class<E> type, String value) {
        if (value == null) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "invalid persisted Plan state", new IllegalArgumentException("missing persisted Plan state"));
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException failure) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "invalid persisted Plan state", failure);
        }
    }

    /**
     * canonical JSON 是 Plan revision 权威源，created_at 也走统一的脱敏持久时间解码。
     */
    private PlanRevision mapRevision(PlanRecords.PlanRevisionRow r) {
        try {
            PlanDefinition definition = json.readerFor(PlanDefinition.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(r.definitionJson());
            PlanPolicy.validate(definition);
            PlanCanonicalizer.Encoded canonical = canonicalPlanJson.encode(definition);
            if (!canonical.json().equals(r.definitionJson()) || !canonical.sha256().equals(r.planHash())) {
                throw new IllegalArgumentException("Persisted Plan revision is not canonical");
            }
            return new PlanRevision(r.planRevisionId(), r.planId(), r.revisionNumber(), definition,
                    r.definitionJson(), r.planHash(), r.createdBy(),
                    persistedInstant(r.createdAt(), "plan_revisions.created_at"));
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw invalid("Persisted Plan revision is invalid", failure);
        }
    }

    /**
     * draft 允许尚未通过 DAG policy 的结构状态，更新时间损坏仍映射稳定存储错误。
     */
    private PlanDraft mapDraft(PlanRecords.PlanDraftRow r) {
        try {
            return new PlanDraft(r.planDraftId(), r.planId(), r.draftRevision(),
                    json.readerFor(PlanDefinition.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readValue(r.definitionJson()), r.basedOnPlanRevisionId(),
                    persistedInstant(r.updatedAt(), "plan_drafts.updated_at"));
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw invalid("Persisted Plan draft is invalid", failure);
        }
    }



    /**
     * Plan 进入批准或执行态后，currentRevision 必须跟随持久化 active identity，而不是跟随表中最新行；
     * 这样恢复窗口或后续版本已经追加时，UI 和执行器仍读取当初被批准的完整冻结定义，任一坏时间都停止投影。
     */
    private PlanSnapshot planSnapshot(PlanUnitOfWork.Mappers c, Plan plan) throws SQLException {
        PlanDraft draft = queryOne(c.plan().findPlanDraftsByPlanId(plan.planId()), this::mapDraft);
        PlanRevision revision = plan.activePlanRevisionId() == null
                ? queryOne(c.plan().findPlanRevisionsByPlanId(plan.planId()), this::mapRevision)
                : requireRevision(c, plan.planId(), plan.activePlanRevisionId());
        PlanApproval approval = revision == null ? null : queryOne(
                c.plan().findLatestApprovedPlanApproval(plan.planId(), revision.planRevisionId()),
                r -> new PlanApproval(r.approvalId(), r.planId(), r.planRevisionId(), r.planHash(),
                        persistedInstant(r.createdAt(), "plan_approvals.created_at")));
        List<StepExecution> steps = plan.activeRunId() == null ? List.of()
                : queryList(c.plan().listPlanStepExecutionsByRunId(plan.activeRunId()), r -> new StepExecution(
                r.stepId(), r.runId(), storedEnum(StepStatus.class, r.status()), r.attemptCount(),
                r.failureSignature(), null,
                optionalPersistedInstant(r.startedAt(), "plan_step_executions.started_at"),
                optionalPersistedInstant(r.completedAt(), "plan_step_executions.completed_at")));
        long seq = scalar(c.plan().latestPlanEventSequence(plan.planId()));
        return new PlanSnapshot(plan, draft, revision, approval, steps, seq);
    }

    /**
     * latest revision/hash 必须精确匹配。
     */
    private PlanRevision requireLatest(PlanUnitOfWork.Mappers c, String planId, String revision, String hash) throws SQLException {
        PlanRevision value = queryOne(c.plan().findPlanRevisionsByPlanId(planId), this::mapRevision);
        if (value == null || !value.planRevisionId().equals(revision) || !value.planHash().equals(hash)) {
            throw stale();
        }
        return value;
    }

    /**
     * approval 是 execute/attach 的唯一资格事实。
     */
    private PlanRevision requireApproved(PlanUnitOfWork.Mappers c, String planId, String revision, String hash) throws SQLException {
        PlanRevision value = requireRevision(c, planId, revision);
        Integer approved = c.plan().findPlanApprovalsByPlanIdPlanRevisionIdPlanHash(planId, revision, hash);
        if (approved == null || !value.planHash().equals(hash)) {
            throw stale();
        }
        return value;
    }


    /**
     * 精确 revision 查询绝不跟随 latest，批准、执行与恢复必须继续绑定原始 planRevisionId。
     */
    private PlanRevision requireRevision(PlanUnitOfWork.Mappers c, String planId, String revision) throws SQLException {
        PlanRevision value = queryOne(c.plan().findPlanRevisionsByPlanIdPlanRevisionId(planId, revision), this::mapRevision);
        if (value == null) {
            throw stale();
        }
        return value;
    }


    /**
     * Plan 缺失归类为 PLAN_INVALID 而不是 Plan 错误，保持 RPC 调用方可稳定区分聚合边界。
     */
    private Plan requirePlan(PlanUnitOfWork.Mappers c, String id) throws SQLException {
        Plan value = queryOne(readPlanRow(() -> c.plan().findPlanById(id)), this::mapPlan);
        if (value == null) {
            throw error(PlanRepositoryException.Code.PLAN_INVALID, "Plan is unavailable");
        }
        return value;
    }

    /**
     * Plan mutation 在进入写事务后重验 expected revision，防止基于过期 draft 或批准继续写入。
     */
    private Plan requirePlanRevision(PlanUnitOfWork.Mappers c, String id, long revision) throws SQLException {
        Plan value = requirePlan(c, id);
        if (value.revision() != revision) {
            throw conflict();
        }
        return value;
    }

    /**
     * 两类 Turn 结算必须读取同一 Plan/Run 快照；幂等键或 revision 不匹配时统一返回无副作用的空结果。
     */
    private PlanSettlementContext planSettlementContext(PlanUnitOfWork.Mappers c, String planId, String runId,
                                                        String operationKey) throws SQLException {
        if (c.plan().findPlanEventByOperationKey(planId, operationKey) != null) {
            return null;
        }
        Plan plan = requirePlan(c, planId);
        RunState run = queryOne(c.plan().findExecutionRunsByRunId(runId), this::mapRunState);
        if (run == null || !Objects.equals(run.planId(), planId)
            || !Objects.equals(run.planRevision(), plan.activePlanRevisionId())) {
                return null;
            }
        return new PlanSettlementContext(plan, run);
    }

    /**
     * Run 行只映射当前执行状态和统计，旧预算字段不得影响准入。
     */
    private RunState mapRunState(PlanRecords.ExecutionRunStateRow r) {
        return new RunState(r.status(), r.planId(), r.planRevisionId(), r.pauseRequested() == 1,
                Math.toIntExact(r.usedModelRounds()), Math.toIntExact(r.usedToolCalls()),
                r.usedActiveMillis());
    }

    /**
     * 只读取 MyBatis 已装载的当前行，不持有 session 或连接生命周期。
     */
    @FunctionalInterface
    private interface Rows<S, T> {
        /**
         * 把特定 SQL 的不可变行投影为领域值，不提供列名或位置反射读取。
         */
        T map(S result) throws SQLException;
    }

    /**
     * 同步 repository 事务步骤只能使用当前 SqlSession 创建的 Mapper。
     */
    @FunctionalInterface
    private interface RepositoryWork {
        /**
         * 所有工作在同一聚合事务或 Store 借用事务中执行，不得保存 Mapper 以供异步调用。
         */
        Plan run(PlanUnitOfWork.Mappers mappers) throws SQLException, JsonProcessingException;
    }

    /**
     * Plan owner 投影同时冻结 Thread revision 与 Task lineage。
     *
     * @param revision 当前修订版本
     * @param taskKind 任务类型
     * @param lifecycle 生命周期
     */
    private record Owner(long revision,
                         String taskKind,
                         String lifecycle) {
    }




    /**
     * run owner tuple 用于恢复与 evidence 校验，不包含可变执行状态。
     *
     * @param planId Plan 标识
     * @param planRevision Plan 修订版本
     */
    private record Run(String planId,
                       String planRevision) {
    }

    /**
     * Plan Turn claim 的状态与 ordinal 共同构成重试和恢复的稳定 admission identity。
     *
     * @param ordinal 当前项的顺序位置
     * @param state 当前状态
     */
    private record ClaimRow(int ordinal,
                            String state) {
    }

    /**
     * Plan 调度只读取 Run 状态、暂停 fence 与累计诊断，不解释旧预算列。
     *
     * @param status 当前状态分类
     * @param planId Plan 标识
     * @param planRevision Plan 修订版本
     * @param pauseRequested 是否已请求暂停
     * @param usedModelRounds 已发生的模型调用轮数
     * @param usedToolCalls 已发生的工具调用次数
     * @param usedActiveMillis 已消耗的活动时长，单位为毫秒
     */
    private record RunState(String status,
                            String planId,
                            String planRevision,
                            boolean pauseRequested,
                            int usedModelRounds,
                            int usedToolCalls,
                            long usedActiveMillis) {
    }

    /**
     * 结算上下文只在当前 JDBC 事务内有效，禁止跨事务保存可变 Run 状态。
     *
     * @param plan 执行计划
     * @param run 运行
     */
    private record PlanSettlementContext(Plan plan,
                                         RunState run) {
    }

    /**
     * internal Turn context 保留 origin 与原始 JSON，严格解析在事务内完成。
     *
     * @param origin 来源分类
     * @param contextJson 上下文 JSON 表示
     */
    private record InternalContext(String origin,
                                   String contextJson) {
    }


    /**
     * 只用于 Plan observe 的一行步骤摘要。
     *
     * @param stepId 步骤标识
     * @param title 显示标题
     * @param required 是否必需
     * @param status 当前状态分类
     */
    private record StepProgressRow(String stepId,
                                   String title,
                                   boolean required,
                                   String status) {
    }

}



