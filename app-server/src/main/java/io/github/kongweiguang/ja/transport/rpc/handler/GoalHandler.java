// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.port.in.GoalUseCase;
import io.github.kongweiguang.ja.goal.port.out.GoalRepositoryException;
import io.github.kongweiguang.ja.transport.rpc.protocol.GoalWireMapper;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Goal/Plan 的严格 JA-RPC v1 入站适配器；状态、批准与调度权威仍只存在于 Java 用例。 */
public final class GoalHandler implements RpcHandler {
    private static final int DEFAULT_PAGE_LIMIT = 50;
    private final RpcSession session;
    private final GoalWireMapper wire;

    /** Handler 只持有连接会话和纯投影器，不缓存 Goal 或 revision。 */
    public GoalHandler(RpcSession session) {
        this.session = Objects.requireNonNull(session, "session");
        this.wire = new GoalWireMapper(session.mapper());
    }

    /** 返回 Goal 查询、独立 Plan 查询以及显式 link 命令的冻结闭集。 */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.GOAL_READ, RpcMethod.GOAL_EVENTS_READ, RpcMethod.GOAL_OBSERVE,
                RpcMethod.GOAL_UNOBSERVE, RpcMethod.PLAN_READ, RpcMethod.PLAN_REVISIONS_LIST,
                RpcMethod.GOAL_EVIDENCE_LIST, RpcMethod.GOAL_CREATE, RpcMethod.GOAL_PAUSE,
                RpcMethod.GOAL_RESUME, RpcMethod.GOAL_STOP, RpcMethod.GOAL_INPUT_RESPOND,
                RpcMethod.PLAN_CREATE, RpcMethod.PLAN_DRAFT_SAVE, RpcMethod.PLAN_DRAFT_DISCARD,
                RpcMethod.PLAN_PROPOSE, RpcMethod.PLAN_APPROVE, RpcMethod.PLAN_EXECUTE, RpcMethod.PLAN_REJECT,
                RpcMethod.GOAL_PLAN_ATTACH, RpcMethod.GOAL_PLAN_DETACH);
    }

    /** 所有 mutation 完成后重新读取事务一致投影，领域错误只按稳定枚举映射。 */
    @Override
    public CompletionStage<ObjectNode> handle(RpcCommand command) {
        session.requireReady();
        try {
            ObjectNode result = switch (command.method()) {
                case GOAL_READ -> read(command.params());
                case GOAL_EVENTS_READ -> events(command.params());
                case GOAL_OBSERVE -> observe(command.params());
                case GOAL_UNOBSERVE -> unobserve(command.params());
                case PLAN_READ -> readPlan(command.params());
                case PLAN_REVISIONS_LIST -> revisions(command.params());
                case GOAL_EVIDENCE_LIST -> evidence(command.params());
                case GOAL_CREATE -> create(command.params());
                case PLAN_CREATE -> createPlan(command.params());
                case GOAL_PAUSE -> control(command.params(), GoalUseCase.Action.PAUSE);
                case GOAL_RESUME -> control(command.params(), GoalUseCase.Action.RESUME);
                case GOAL_STOP -> control(command.params(), GoalUseCase.Action.STOP);
                case GOAL_INPUT_RESPOND -> respondInput(command.params());
                case PLAN_DRAFT_SAVE -> saveDraft(command.params());
                case PLAN_DRAFT_DISCARD -> discardDraft(command.params());
                case PLAN_PROPOSE -> propose(command.params());
                case PLAN_APPROVE -> approve(command.params());
                case PLAN_EXECUTE -> execute(command.params());
                case PLAN_REJECT -> reject(command.params());
                case GOAL_PLAN_ATTACH -> attachPlan(command.params());
                case GOAL_PLAN_DETACH -> detachPlan(command.params());
                default -> throw JaRpcException.methodNotFound();
            };
            return CompletableFuture.completedFuture(result);
        } catch (GoalRepositoryException failure) {
            throw map(failure);
        }
    }

    /** goal/read 只接受稳定 Goal identity。 */
    private ObjectNode read(ObjectNode params) {
        RpcParams.requireExact(params, "goalId");
        return wire.snapshot(session.goals().read(goalId(params)));
    }

    /** 事件分页游标保持 opaque，Handler 不解析 SQLite keyset。 */
    private ObjectNode events(ObjectNode params) {
        RpcParams.requireOnly(params, "goalId", "cursor", "limit");
        return wire.events(session.goals().readEvents(goalId(params), cursor(params), pageLimit(params)));
    }

    /** observe 建立连接级过滤句柄并原子返回当前完整快照。 */
    private ObjectNode observe(ObjectNode params) {
        RpcParams.requireExact(params, "goalId");
        RpcSession.GoalObservation observation = session.observeGoal(goalId(params));
        ObjectNode result = wire.snapshot(observation.snapshot());
        result.put("observationId", observation.observationId());
        return result;
    }

    /** unobserve 只释放当前连接拥有的投影句柄，不暂停 Goal。 */
    private ObjectNode unobserve(ObjectNode params) {
        RpcParams.requireExact(params, "observationId");
        session.unobserveGoal(RpcParams.identifier(params, "observationId", "observe_", 128));
        return session.mapper().createObjectNode().put("accepted", true);
    }

    /** plan/read 校验 thread owner，避免仅凭可猜测 planId 跨 Thread 读取独立聚合。 */
    private ObjectNode readPlan(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "planId");
        return wire.planSnapshot(readOwnedPlan(params));
    }

    /** PlanRevision 分页按服务端 revision keyset 前进，并沿用 plan owner 边界。 */
    private ObjectNode revisions(ObjectNode params) {
        RpcParams.requireOnly(params, "threadId", "planId", "cursor", "limit");
        GoalModels.PlanSnapshot plan = readOwnedPlan(params);
        return wire.revisions(session.goals().listRevisions(
                plan.plan().planId(), cursor(params), pageLimit(params)), plan.plan());
    }

    /** Evidence 必须绑定 Goal definition，可选 Plan revision 只进一步收窄而不替代定义版本。 */
    private ObjectNode evidence(ObjectNode params) {
        RpcParams.requireOnly(params, "goalId", "goalDefinitionRevision", "planRevisionId", "cursor", "limit");
        long definitionRevision = RpcParams.revision(params, "goalDefinitionRevision");
        if (definitionRevision < 1) throw JaRpcException.invalidParams();
        String planRevisionId = optionalPlanRevisionId(params);
        return wire.evidence(session.goals().listEvidence(
                goalId(params), definitionRevision, planRevisionId, cursor(params), pageLimit(params)),
                definitionRevision, planRevisionId);
    }

    /**
     * create 的 expectedGoalRevision 固定为 0；owner Thread revision 在同一请求内读取并交给
     * Goal owner 校验，避免让 WebView 再维护一份 Thread CAS 字段。
     */
    private ObjectNode create(ObjectNode params) {
        RpcParams.requireExact(params, "owner", "objective", "acceptanceCriteria",
                "expectedGoalRevision", "idempotencyKey");
        if (RpcParams.revision(params, "expectedGoalRevision") != 0) throw JaRpcException.invalidParams();
        JsonNode ownerNode = params.get("owner");
        if (!(ownerNode instanceof ObjectNode owner)) throw JaRpcException.invalidParams();
        String kind = RpcParams.text(owner, "kind", 32, false);
        String ownerThreadId;
        boolean independent;
        if ("thread".equals(kind)) {
            RpcParams.requireExact(owner, "kind", "threadId");
            ownerThreadId = RpcParams.identifier(owner, "threadId", "thr_", 128);
            independent = false;
        } else if ("independent_task".equals(kind)) {
            RpcParams.requireExact(owner, "kind", "taskThreadId");
            ownerThreadId = RpcParams.identifier(owner, "taskThreadId", "thr_", 128);
            independent = true;
        } else throw JaRpcException.invalidParams();
        ThreadSnapshot ownerSnapshot = session.threads().readThread(ownerThreadId, null, 1)
                .orElseThrow(() -> new GoalRepositoryException(
                        GoalRepositoryException.Code.GOAL_NOT_FOUND, "Goal owner is unavailable"));
        GoalModels.Goal created = session.goals().create(new GoalUseCase.Create(ownerThreadId, independent,
                RpcParams.text(params, "objective", 32_768, false),
                criteria(params.get("acceptanceCriteria"), 0), ownerSnapshot.thread().revision(),
                idempotencyKey(params), session.clock().instant()));
        return wire.snapshot(session.goals().read(created.goalId()));
    }

    /** Plan create 只允许普通 Thread owner，并显式携带 Thread CAS，避免隐式挂接 Goal。 */
    private ObjectNode createPlan(ObjectNode params) {
        RpcParams.requireExact(params, "owner", "objective", "expectedThreadRevision", "idempotencyKey");
        JsonNode ownerNode = params.get("owner");
        if (!(ownerNode instanceof ObjectNode owner)) throw JaRpcException.invalidParams();
        RpcParams.requireExact(owner, "kind", "threadId");
        if (!"thread".equals(RpcParams.text(owner, "kind", 32, false))) {
            throw JaRpcException.invalidParams();
        }
        String threadId = RpcParams.identifier(owner, "threadId", "thr_", 128);
        long expectedThreadRevision = RpcParams.revision(params, "expectedThreadRevision");
        GoalModels.Plan created = session.goals().createPlan(new GoalUseCase.CreatePlan(threadId,
                RpcParams.text(params, "objective", 32_768, false), expectedThreadRevision,
                idempotencyKey(params), session.clock().instant()));
        return wire.planSnapshot(session.goals().readPlan(created.planId()));
    }

    /** 暂停、恢复和停止共享精确 Goal CAS 与幂等键。 */
    private ObjectNode control(ObjectNode params, GoalUseCase.Action action) {
        mutation(params);
        String goalId = goalId(params);
        session.goals().control(new GoalUseCase.Control(goalId,
                RpcParams.revision(params, "expectedGoalRevision"), action,
                idempotencyKey(params), session.clock().instant()));
        return wire.snapshot(session.goals().read(goalId));
    }

    /** 用户输入响应只提交可见文本，Handler 不制造 USER timeline message。 */
    private ObjectNode respondInput(ObjectNode params) {
        RpcParams.requireExact(params, "goalId", "expectedGoalRevision", "idempotencyKey",
                "inputRequestId", "response");
        String goalId = goalId(params);
        session.goals().respondInput(new GoalUseCase.InputResponse(goalId,
                RpcParams.revision(params, "expectedGoalRevision"),
                RpcParams.identifier(params, "inputRequestId", "goalinput_", 128),
                RpcParams.text(params, "response", 32_768, false), idempotencyKey(params),
                session.clock().instant()));
        return wire.snapshot(session.goals().read(goalId));
    }

    /**
     * Wire 不暴露 draft CAS；Handler 从当前 Plan 快照取得 draft/base identity，服务端仍以
     * expectedPlanRevision + expectedDraftRevision 双门防止旧编辑覆盖新草稿。
     */
    private ObjectNode saveDraft(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "planId", "expectedPlanRevision", "idempotencyKey", "draft");
        GoalModels.PlanSnapshot current = readOwnedPlan(params);
        GoalModels.PlanDraft draft = current.draft();
        long draftRevision = draft == null ? 0 : draft.draftRevision();
        String basedOn = draft == null
                ? current.currentRevision() == null ? null : current.currentRevision().planRevisionId()
                : draft.basePlanRevisionId();
        session.goals().saveDraft(new GoalUseCase.SaveDraft(current.plan().planId(),
                RpcParams.revision(params, "expectedPlanRevision"), draftRevision,
                definition(params.get("draft")), basedOn, idempotencyKey(params), session.clock().instant()));
        return wire.planSnapshot(session.goals().readPlan(current.plan().planId()));
    }

    /** 丢弃草稿可恢复之前批准版本，但仍必须通过当前 Plan CAS，避免旧编辑覆盖新 revision。 */
    private ObjectNode discardDraft(ObjectNode params) {
        planMutation(params);
        GoalModels.PlanSnapshot current = readOwnedPlan(params);
        session.goals().discardDraft(new GoalUseCase.DiscardDraft(current.plan().planId(),
                RpcParams.revision(params, "expectedPlanRevision"), idempotencyKey(params),
                session.clock().instant()));
        return wire.planSnapshot(session.goals().readPlan(current.plan().planId()));
    }

    /** UI 只从持久 draft 提案，禁止在 approve 请求中夹带另一份计划定义。 */
    private ObjectNode propose(ObjectNode params) {
        planMutation(params);
        GoalModels.PlanSnapshot current = readOwnedPlan(params);
        session.goals().proposeDraft(new GoalUseCase.ProposeDraft(current.plan().planId(),
                RpcParams.revision(params, "expectedPlanRevision"), true,
                idempotencyKey(params), session.clock().instant()));
        return wire.planSnapshot(session.goals().readPlan(current.plan().planId()));
    }

    /** 批准仅绑定精确 revision/hash；执行必须通过独立 plan/execute 显式启动。 */
    private ObjectNode approve(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "planId", "expectedPlanRevision", "idempotencyKey",
                "planRevisionId", "planHash");
        GoalModels.PlanSnapshot current = readOwnedPlan(params);
        session.goals().approve(new GoalUseCase.Approve(current.plan().planId(),
                RpcParams.revision(params, "expectedPlanRevision"),
                RpcParams.identifier(params, "planRevisionId", "planrev_", 128),
                digest(params, "planHash"), idempotencyKey(params),
                session.clock().instant()));
        return wire.planSnapshot(session.goals().readPlan(current.plan().planId()));
    }

    /** execute 只消费当前批准版本，并使用 App Server process generation 创建 standalone run。 */
    private ObjectNode execute(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "planId", "expectedPlanRevision", "idempotencyKey",
                "planRevisionId", "planHash");
        GoalModels.PlanSnapshot current = readOwnedPlan(params);
        session.goals().executePlan(new GoalUseCase.ExecutePlan(current.plan().planId(),
                RpcParams.revision(params, "expectedPlanRevision"),
                RpcParams.identifier(params, "planRevisionId", "planrev_", 128),
                digest(params, "planHash"), session.runtimeGeneration(), idempotencyKey(params),
                session.clock().instant()), session.planExecutionEvents());
        return wire.planSnapshot(session.goals().readPlan(current.plan().planId()));
    }

    /** Reject 只提交有界原因；当前 revision/hash 由 GoalService 在事务内锁定。 */
    private ObjectNode reject(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "planId", "expectedPlanRevision", "idempotencyKey", "reason");
        GoalModels.PlanSnapshot current = readOwnedPlan(params);
        session.goals().reject(new GoalUseCase.Reject(current.plan().planId(),
                RpcParams.revision(params, "expectedPlanRevision"),
                RpcParams.text(params, "reason", 32_768, false), idempotencyKey(params),
                session.clock().instant()));
        return wire.planSnapshot(session.goals().readPlan(current.plan().planId()));
    }

    /** attach 只接受当前批准 revision/hash，Goal 不会跟随 Plan 后续版本漂移。 */
    private ObjectNode attachPlan(ObjectNode params) {
        RpcParams.requireExact(params, "goalId", "expectedGoalRevision", "idempotencyKey",
                "planId", "planRevisionId", "planHash");
        String goalId = goalId(params);
        session.goals().attachPlan(new GoalUseCase.AttachPlan(goalId,
                RpcParams.revision(params, "expectedGoalRevision"), planId(params),
                RpcParams.identifier(params, "planRevisionId", "planrev_", 128),
                digest(params, "planHash"), idempotencyKey(params), session.clock().instant()));
        return wire.snapshot(session.goals().read(goalId));
    }

    /** detach 的 linkRevision 由同一 Goal CAS 快照取得，不扩展公开并发协议。 */
    private ObjectNode detachPlan(ObjectNode params) {
        mutation(params);
        String goalId = goalId(params);
        GoalModels.GoalSnapshot current = session.goals().read(goalId);
        if (current.planLink() == null) {
            throw new GoalRepositoryException(GoalRepositoryException.Code.GOAL_INVALID_STATE,
                    "Goal has no attached Plan");
        }
        session.goals().detachPlan(new GoalUseCase.DetachPlan(goalId,
                RpcParams.revision(params, "expectedGoalRevision"),
                idempotencyKey(params), session.clock().instant()));
        return wire.snapshot(session.goals().read(goalId));
    }

    /** 结构化计划使用字段闭集和有界数组，DAG 语义继续由 GoalPolicy 统一验证。 */
    private GoalModels.PlanDefinition definition(JsonNode node) {
        if (!(node instanceof ObjectNode value)) throw JaRpcException.invalidParams();
        RpcParams.requireExact(value, "objective", "scope", "nonGoals", "constraints",
                "acceptanceCriteria", "steps", "dependencies", "risks", "verificationStrategy");
        List<GoalModels.AcceptanceCriterion> criteria = criteria(value.get("acceptanceCriteria"), 1);
        List<GoalModels.PlanStep> steps = new ArrayList<>();
        for (JsonNode item : array(value, "steps", 1, 256)) {
            if (!(item instanceof ObjectNode step)) throw JaRpcException.invalidParams();
            RpcParams.requireExact(step, "stepId", "title", "description", "required", "dependsOn");
            steps.add(new GoalModels.PlanStep(RpcParams.identifier(step, "stepId", "step_", 128),
                    RpcParams.text(step, "title", 1_024, false),
                    RpcParams.text(step, "description", 32_768, false), bool(step, "required"),
                    identifiers(step, "dependsOn", "step_", 128)));
        }
        return new GoalModels.PlanDefinition(RpcParams.text(value, "objective", 32_768, false),
                texts(value, "scope", 1, 128), texts(value, "nonGoals", 0, 128),
                texts(value, "constraints", 0, 128), texts(value, "dependencies", 0, 128),
                steps, criteria, texts(value, "risks", 0, 128),
                texts(value, "verificationStrategy", 1, 128));
    }

    /** 文本数组逐项校验，不允许 null、对象或空文本经 Jackson coercion 进入领域。 */
    private static List<String> texts(ObjectNode object, String field, int minimum, int maximum) {
        List<String> result = new ArrayList<>();
        for (JsonNode item : array(object, field, minimum, maximum)) {
            if (!item.isTextual() || item.textValue().isBlank() || item.textValue().length() > 2_000) {
                throw JaRpcException.invalidParams();
            }
            result.add(item.textValue());
        }
        return List.copyOf(result);
    }

    /** 标识数组同时拒绝重复 identity，具体依赖存在性和环由 GoalPolicy 验证。 */
    private static List<String> identifiers(ObjectNode object, String field, String prefix, int maximum) {
        List<String> values = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode item : array(object, field, 0, maximum)) {
            if (!item.isTextual()) throw JaRpcException.invalidParams();
            String value = identifier(item.textValue(), prefix);
            if (!unique.add(value)) throw JaRpcException.invalidParams();
            values.add(value);
        }
        return List.copyOf(values);
    }

    /** 数组形状和数量在构造领域对象前失败关闭。 */
    private static ArrayNode array(ObjectNode object, String field, int minimum, int maximum) {
        JsonNode value = object.get(field);
        if (!(value instanceof ArrayNode array) || array.size() < minimum || array.size() > maximum) {
            throw JaRpcException.invalidParams();
        }
        return array;
    }

    /** 布尔字段不接受 0/1 或字符串 coercion。 */
    private static boolean bool(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isBoolean()) throw JaRpcException.invalidParams();
        return value.booleanValue();
    }

    /** Goal 可接受空验收列表，而 Plan 必须至少包含一项；两者共享严格 criterion 形状。 */
    private static List<GoalModels.AcceptanceCriterion> criteria(JsonNode node, int minimum) {
        if (!(node instanceof ArrayNode array) || array.size() < minimum || array.size() > 256) {
            throw JaRpcException.invalidParams();
        }
        List<GoalModels.AcceptanceCriterion> result = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (JsonNode item : array) {
            if (!(item instanceof ObjectNode criterion)) throw JaRpcException.invalidParams();
            RpcParams.requireExact(criterion, "criterionId", "description", "required");
            String criterionId = RpcParams.identifier(criterion, "criterionId", "criterion_", 128);
            if (!identities.add(criterionId)) throw JaRpcException.invalidParams();
            result.add(new GoalModels.AcceptanceCriterion(criterionId,
                    RpcParams.text(criterion, "description", 2_000, false), bool(criterion, "required")));
        }
        return List.copyOf(result);
    }

    /** 通用 mutation 的字段闭集保持一致。 */
    private static void mutation(ObjectNode params) {
        RpcParams.requireExact(params, "goalId", "expectedGoalRevision", "idempotencyKey");
    }

    /** Plan mutation 的公开 CAS 与 Goal 完全分离，禁止继续接受旧 goalId 字段。 */
    private static void planMutation(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "planId", "expectedPlanRevision", "idempotencyKey");
    }

    /** 每次 Plan 操作都核对 owner Thread；Handler 不缓存该关系。 */
    private GoalModels.PlanSnapshot readOwnedPlan(ObjectNode params) {
        String ownerThreadId = RpcParams.identifier(params, "threadId", "thr_", 128);
        GoalModels.PlanSnapshot snapshot = session.goals().readPlan(planId(params));
        if (!ownerThreadId.equals(snapshot.plan().ownerThreadId())) {
            throw new GoalRepositoryException(GoalRepositoryException.Code.GOAL_NOT_FOUND,
                    "Plan is unavailable for Thread");
        }
        return snapshot;
    }

    /** Goal identity 使用公开前缀和统一长度上限。 */
    private static String goalId(ObjectNode params) {
        return RpcParams.identifier(params, "goalId", "goal_", 128);
    }

    /** Plan identity 使用独立前缀，不能把 Goal ID 当作 Plan owner。 */
    private static String planId(ObjectNode params) {
        return RpcParams.identifier(params, "planId", "plan_", 128);
    }

    /** Evidence 的 Plan filter 可省略但不能显式传 null，保持 optional 与 nullable 语义分离。 */
    private static String optionalPlanRevisionId(ObjectNode params) {
        if (!params.has("planRevisionId")) return null;
        if (params.get("planRevisionId").isNull()) throw JaRpcException.invalidParams();
        return RpcParams.identifier(params, "planRevisionId", "planrev_", 128);
    }

    /** idempotency key 不能携带空白或无界文本。 */
    private static String idempotencyKey(ObjectNode params) {
        String value = RpcParams.text(params, "idempotencyKey", 128, false);
        if (value.length() < 8 || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]+")) {
            throw JaRpcException.invalidParams();
        }
        return value;
    }

    /** SHA-256 只接受小写十六进制，避免同一 hash 多种 wire 表示。 */
    private static String digest(ObjectNode params, String field) {
        String value = RpcParams.text(params, field, 64, false);
        if (!value.matches("[0-9a-f]{64}")) throw JaRpcException.invalidParams();
        return value;
    }

    /** 可选分页游标不能显式传 null。 */
    private static String cursor(ObjectNode params) {
        if (params.has("cursor") && params.get("cursor").isNull()) throw JaRpcException.invalidParams();
        String value = RpcParams.optionalText(params, "cursor", 256);
        if (value != null && !value.matches("[A-Za-z0-9._~-]{1,256}")) throw JaRpcException.invalidParams();
        return value;
    }

    /** 分页默认 50，公开上限 200；RpcParams 负责整数与安全范围校验。 */
    private static int pageLimit(ObjectNode params) {
        if (!params.has("limit")) return DEFAULT_PAGE_LIMIT;
        return RpcParams.pageLimit(params);
    }

    /** 对嵌套数组中的 identity 应用和顶层 RpcParams 相同的前缀词汇。 */
    private static String identifier(String value, String prefix) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
                || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw JaRpcException.invalidParams();
        }
        return value;
    }

    /** Goal 仓储闭集与冻结 JA-RPC 错误目录一一对应。 */
    private static JaRpcException map(GoalRepositoryException failure) {
        JaErrorCatalog code = switch (failure.code()) {
            case GOAL_NOT_FOUND -> JaErrorCatalog.GOAL_NOT_FOUND;
            case GOAL_REVISION_CONFLICT -> JaErrorCatalog.GOAL_REVISION_CONFLICT;
            case GOAL_INVALID_STATE -> JaErrorCatalog.GOAL_INVALID_STATE;
            case PLAN_INVALID -> JaErrorCatalog.PLAN_INVALID;
            case PLAN_APPROVAL_STALE -> JaErrorCatalog.PLAN_APPROVAL_STALE;
            case GOAL_EVIDENCE_INCOMPLETE -> JaErrorCatalog.GOAL_EVIDENCE_INCOMPLETE;
            case GOAL_RECOVERY_REQUIRED -> JaErrorCatalog.GOAL_RECOVERY_REQUIRED;
            case GOAL_INPUT_EXPIRED -> JaErrorCatalog.GOAL_INPUT_EXPIRED;
        };
        return JaRpcException.of(code, "goal operation could not be completed");
    }
}
