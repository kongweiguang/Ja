// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.application.api.PlanUseCase;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepositoryException;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.PlanWireMapper;
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

/**
 * Plan 的严格 JA-RPC v2 入站适配器；状态、批准与调度权威仍只存在于 Java 用例。
 */
public final class PlanHandler implements RpcHandler {
    private static final int DEFAULT_PAGE_LIMIT = 50;
    private final RpcSession session;
    private final PlanWireMapper wire;

    /**
     * Handler 只持有连接会话和纯投影器，不缓存 Plan 或 revision。
     */
    public PlanHandler(RpcSession session) {
        this.session = Objects.requireNonNull(session, "session");
        this.wire = new PlanWireMapper(session.mapper());
    }

    /**
     * 文本数组逐项校验，不允许 null、对象或空文本经 Jackson coercion 进入领域。
     */
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

    /**
     * 标识数组拒绝重复 identity，依赖存在性和环由 PlanPolicy 统一验证。
     */
    private static List<String> identifiers(ObjectNode object, String field, String prefix, int maximum) {
        List<String> values = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode item : array(object, field, 0, maximum)) {
            if (!item.isTextual()) {
                throw JaRpcException.invalidParams();
            }
            String value = identifier(item.textValue(), prefix);
            if (!unique.add(value)) {
                throw JaRpcException.invalidParams();
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    /**
     * 数组形状和数量在构造领域对象前失败关闭。
     */
    private static ArrayNode array(ObjectNode object, String field, int minimum, int maximum) {
        JsonNode value = object.get(field);
        if (!(value instanceof ArrayNode array) || array.size() < minimum || array.size() > maximum) {
            throw JaRpcException.invalidParams();
        }
        return array;
    }

    /**
     * 布尔字段不接受 0/1 或字符串 coercion。
     */
    private static boolean bool(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isBoolean()) {
            throw JaRpcException.invalidParams();
        }
        return value.booleanValue();
    }

    /**
     * Plan 可接受空验收列表，而 Plan 必须至少包含一项；两者共享严格 criterion 形状。
     */
    private static List<PlanModels.AcceptanceCriterion> criteria(JsonNode node, int minimum) {
        if (!(node instanceof ArrayNode array) || array.size() < minimum || array.size() > 256) {
            throw JaRpcException.invalidParams();
        }
        List<PlanModels.AcceptanceCriterion> result = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (JsonNode item : array) {
            if (!(item instanceof ObjectNode criterion)) {
                throw JaRpcException.invalidParams();
            }
            RpcParams.requireExact(criterion, "criterionId", "description", "required");
            String criterionId = RpcParams.identifier(criterion, "criterionId", "criterion_", 128);
            if (!identities.add(criterionId)) {
                throw JaRpcException.invalidParams();
            }
            result.add(new PlanModels.AcceptanceCriterion(criterionId,
                    RpcParams.text(criterion, "description", 2_000, false), bool(criterion, "required")));
        }
        return List.copyOf(result);
    }


    /**
     * 计划变更只接受自身公开 identity 与 CAS 字段，避免关联身份混入独立聚合。
     */
    private static void planMutation(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "planId", "expectedPlanRevision", "operationKey");
    }


    /**
     * Plan identity 使用独立前缀，不能把 Plan ID 当作 Plan owner。
     */
    private static String planId(ObjectNode params) {
        return RpcParams.identifier(params, "planId", "plan_", 128);
    }

    /**
     * idempotency key 不能携带空白或无界文本。
     */
    private static String operationKey(ObjectNode params) {
        String value = RpcParams.text(params, "operationKey", 128, false);
        if (value.length() < 8 || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]+")) {
            throw JaRpcException.invalidParams();
        }
        return value;
    }

    /**
     * SHA-256 只接受小写十六进制，避免同一 hash 多种 wire 表示。
     */
    private static String digest(ObjectNode params, String field) {
        String value = RpcParams.text(params, field, 64, false);
        if (!value.matches("[0-9a-f]{64}")) {
            throw JaRpcException.invalidParams();
        }
        return value;
    }

    /**
     * 可选分页游标不能显式传 null。
     */
    private static String cursor(ObjectNode params) {
        if (params.has("cursor") && params.get("cursor").isNull()) {
            throw JaRpcException.invalidParams();
        }
        String value = RpcParams.optionalText(params, "cursor", 256);
        if (value != null && !value.matches("[A-Za-z0-9._~-]{1,256}")) {
            throw JaRpcException.invalidParams();
        }
        return value;
    }

    /**
     * 分页默认 50，公开上限 200；RpcParams 负责整数与安全范围校验。
     */
    private static int pageLimit(ObjectNode params) {
        if (!params.has("limit")) {
            return DEFAULT_PAGE_LIMIT;
        }
        return RpcParams.pageLimit(params);
    }

    /**
     * 对嵌套数组中的 identity 应用和顶层 RpcParams 相同的前缀词汇。
     */
    private static String identifier(String value, String prefix) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw JaRpcException.invalidParams();
        }
        return value;
    }

    /**
     * Plan 仓储闭集与冻结 JA-RPC 错误目录一一对应。
     */
    private static JaRpcException map(PlanRepositoryException failure) {
        JaErrorCatalog code = switch (failure.code()) {
            case PLAN_NOT_FOUND -> JaErrorCatalog.PLAN_NOT_FOUND;
            case PLAN_REVISION_CONFLICT -> JaErrorCatalog.PLAN_REVISION_CONFLICT;
            case PLAN_INVALID_STATE -> JaErrorCatalog.PLAN_INVALID_STATE;
            case PLAN_INVALID -> JaErrorCatalog.PLAN_INVALID;
            case PLAN_APPROVAL_STALE -> JaErrorCatalog.PLAN_APPROVAL_STALE;
            case PLAN_EVIDENCE_INCOMPLETE -> JaErrorCatalog.PLAN_EVIDENCE_INCOMPLETE;
            case PLAN_RECOVERY_REQUIRED -> JaErrorCatalog.PLAN_RECOVERY_REQUIRED;
        };
        return JaRpcException.of(code, "plan operation could not be completed");
    }

    /**
     * 返回 Plan 查询、独立 Plan 查询以及显式 link 命令的冻结闭集。
     */
    @Override
    public Set<RpcMethod> methods() {
        return Set.of(RpcMethod.PLAN_READ, RpcMethod.PLAN_REVISIONS_LIST,
                RpcMethod.PLAN_CURRENT_READ, RpcMethod.PLAN_EVENTS_READ, RpcMethod.PLAN_EVIDENCE_LIST,
                RpcMethod.PLAN_OBSERVE, RpcMethod.PLAN_UNOBSERVE,
                RpcMethod.PLAN_PAUSE, RpcMethod.PLAN_RESUME, RpcMethod.PLAN_STOP,
                RpcMethod.PLAN_CREATE, RpcMethod.PLAN_DRAFT_SAVE, RpcMethod.PLAN_DRAFT_DISCARD,
                RpcMethod.PLAN_PROPOSE, RpcMethod.PLAN_EXECUTE, RpcMethod.PLAN_REJECT);
    }

    /**
     * 所有 mutation 完成后重新读取事务一致投影，领域错误只按稳定枚举映射。
     */
    @Override
    public CompletionStage<ObjectNode> handle(RpcCommand command) {
        session.requireReady();
        try {
            ObjectNode result = switch (command.method()) {
                case PLAN_READ -> readPlan(command.params());
                case PLAN_CURRENT_READ -> currentPlan(command.params());
                case PLAN_EVENTS_READ -> planEvents(command.params());
                case PLAN_OBSERVE -> observePlan(command.params());
                case PLAN_UNOBSERVE -> unobservePlan(command.params());
                case PLAN_EVIDENCE_LIST -> planEvidence(command.params());
                case PLAN_PAUSE, PLAN_RESUME, PLAN_STOP -> controlPlan(command.params(), command.method());
                case PLAN_REVISIONS_LIST -> revisions(command.params());
                case PLAN_CREATE -> createPlan(command.params());
                case PLAN_DRAFT_SAVE -> saveDraft(command.params());
                case PLAN_DRAFT_DISCARD -> discardDraft(command.params());
                case PLAN_PROPOSE -> propose(command.params());
                case PLAN_EXECUTE -> execute(command.params());
                case PLAN_REJECT -> reject(command.params());
                default -> throw JaRpcException.methodNotFound();
            };
            return CompletableFuture.completedFuture(result);
        } catch (PlanRepositoryException failure) {
            throw map(failure);
        }
    }





    /**
     * plan/read 校验 thread owner，避免仅凭可猜测 planId 跨 Thread 读取独立聚合。
     */
    private ObjectNode readPlan(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "planId");
        return wire.planSnapshot(readOwnedPlan(params));
    }

    /**
     * 仅按已存在的 Thread 查找其最新计划，空结果不是协议错误。
     */
    private ObjectNode currentPlan(ObjectNode params) {
        RpcParams.requireExact(params, "threadId");
        String threadId = RpcParams.identifier(params, "threadId", "thr_", 128);
        if (session.threads().readThread(threadId, null, 1).isEmpty()) {
            throw JaRpcException.of(JaErrorCatalog.THREAD_NOT_FOUND, "会话不存在");
        }
        ObjectNode result = session.mapper().createObjectNode();
        result.set("current", session.plans().findCurrentPlan(threadId).map(wire::planSnapshot).orElse(null));
        return result;
    }

    /**
     * Plan 的历史只按 owner 与 opaque cursor 查询，不读取工作区文件。
     */
    private ObjectNode planEvents(ObjectNode params) {
        RpcParams.requireOnly(params, "threadId", "planId", "cursor", "limit");
        PlanModels.PlanSnapshot plan = readOwnedPlan(params);
        return wire.planEvents(session.plans().readPlanEvents(plan.plan().planId(), cursor(params), pageLimit(params)));
    }

    /**
     * Plan 观察身份归当前连接，先验证 Thread 归属后开放事件。
     */
    private ObjectNode observePlan(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "planId");
        return session.observePlan(RpcParams.identifier(params, "threadId", "thr_", 128), planId(params));
    }

    /**
     * 隐藏详情只撤销观察，不取消计划，也不借用 Plan 的关闭入口。
     */
    private ObjectNode unobservePlan(ObjectNode params) {
        RpcParams.requireExact(params, "observationId");
        session.unobservePlan(RpcParams.identifier(params, "observationId", "observe_", 128));
        return session.mapper().createObjectNode().put("accepted", true);
    }

    /**
     * 证据查询必须明确版本与 Run，后续执行不能改变旧结果入口。
     */
    private ObjectNode planEvidence(ObjectNode params) {
        RpcParams.requireOnly(params, "threadId", "planId", "planRevisionId", "runId", "cursor", "limit");
        PlanModels.PlanSnapshot plan = readOwnedPlan(params);
        String revisionId = RpcParams.identifier(params, "planRevisionId", "planrev_", 128);
        String runId = RpcParams.identifier(params, "runId", "run_", 128);
        return wire.planEvidence(session.plans().listPlanEvidence(plan.plan().planId(), revisionId, runId,
                cursor(params), pageLimit(params)), revisionId, runId);
    }

    /**
     * 独立 Plan 控制只作用于原 Run，不路由到 Plan continuation。
     */
    private ObjectNode controlPlan(ObjectNode params, RpcMethod action) {
        RpcParams.requireExact(params, "threadId", "planId", "expectedPlanRevision", "runId", "operationKey");
        PlanModels.PlanSnapshot plan = readOwnedPlan(params);
        PlanUseCase.PlanControl command = new PlanUseCase.PlanControl(plan.plan().planId(),
                RpcParams.revision(params, "expectedPlanRevision"),
                RpcParams.identifier(params, "runId", "run_", 128), operationKey(params), session.clock().instant());
        switch (action) {
            case PLAN_PAUSE -> session.plans().pausePlan(command);
            case PLAN_RESUME -> session.plans().resumePlan(command, session.planExecutionEvents());
            case PLAN_STOP -> session.plans().stopPlan(command);
            default -> throw JaRpcException.methodNotFound();
        }
        return wire.planSnapshot(session.plans().readPlan(plan.plan().planId()));
    }

    /**
     * PlanRevision 分页按服务端 revision keyset 前进，并沿用 plan owner 边界。
     */
    private ObjectNode revisions(ObjectNode params) {
        RpcParams.requireOnly(params, "threadId", "planId", "cursor", "limit");
        PlanModels.PlanSnapshot plan = readOwnedPlan(params);
        return wire.revisions(session.plans().listRevisions(
                plan.plan().planId(), cursor(params), pageLimit(params)), plan.plan());
    }



    /**
     * Plan create 绑定已存在的 owner Thread（包括独立侧边任务），并显式携带 Thread CAS。
     */
    private ObjectNode createPlan(ObjectNode params) {
        RpcParams.requireExact(params, "owner", "objective", "expectedThreadRevision", "operationKey");
        JsonNode ownerNode = params.get("owner");
        if (!(ownerNode instanceof ObjectNode owner)) {
            throw JaRpcException.invalidParams();
        }
        RpcParams.requireExact(owner, "kind", "threadId");
        if (!"thread".equals(RpcParams.text(owner, "kind", 32, false))) {
            throw JaRpcException.invalidParams();
        }
        String threadId = RpcParams.identifier(owner, "threadId", "thr_", 128);
        long expectedThreadRevision = RpcParams.revision(params, "expectedThreadRevision");
        PlanModels.Plan created = session.plans().createPlan(new PlanUseCase.CreatePlan(threadId,
                RpcParams.text(params, "objective", 32_768, false), expectedThreadRevision,
                operationKey(params), session.clock().instant()));
        return wire.planSnapshot(session.plans().readPlan(created.planId()));
    }


    /**
     * Wire 不暴露 draft CAS；Handler 从当前 Plan 快照取得 draft/base identity，服务端仍以
     * expectedPlanRevision + expectedDraftRevision 双门防止旧编辑覆盖新草稿。
     */
    private ObjectNode saveDraft(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "planId", "expectedPlanRevision", "operationKey", "draft");
        PlanModels.PlanSnapshot current = readOwnedPlan(params);
        PlanModels.PlanDraft draft = current.draft();
        long draftRevision = draft == null ? 0 : draft.draftRevision();
        String basedOn = draft == null
                ? current.currentRevision() == null ? null : current.currentRevision().planRevisionId()
                : draft.basePlanRevisionId();
        session.plans().saveDraft(new PlanUseCase.SaveDraft(current.plan().planId(),
                RpcParams.revision(params, "expectedPlanRevision"), draftRevision,
                definition(params.get("draft")), basedOn, operationKey(params), session.clock().instant()));
        return wire.planSnapshot(session.plans().readPlan(current.plan().planId()));
    }

    /**
     * 丢弃草稿可恢复之前批准版本，但仍必须通过当前 Plan CAS，避免旧编辑覆盖新 revision。
     */
    private ObjectNode discardDraft(ObjectNode params) {
        planMutation(params);
        PlanModels.PlanSnapshot current = readOwnedPlan(params);
        session.plans().discardDraft(new PlanUseCase.DiscardDraft(current.plan().planId(),
                RpcParams.revision(params, "expectedPlanRevision"), operationKey(params),
                session.clock().instant()));
        return wire.planSnapshot(session.plans().readPlan(current.plan().planId()));
    }

    /**
     * UI 只从持久 draft 提案，禁止在 approve 请求中夹带另一份计划定义。
     */
    private ObjectNode propose(ObjectNode params) {
        planMutation(params);
        PlanModels.PlanSnapshot current = readOwnedPlan(params);
        session.plans().proposeDraft(new PlanUseCase.ProposeDraft(current.plan().planId(),
                RpcParams.revision(params, "expectedPlanRevision"), true,
                operationKey(params), session.clock().instant()));
        return wire.planSnapshot(session.plans().readPlan(current.plan().planId()));
    }

    /**
     * execute 在单个服务端事务中批准精确版本并登记唯一 Run，前端无需串联两次调用。
     */
    private ObjectNode execute(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "planId", "expectedPlanRevision", "operationKey",
                "planRevisionId", "planHash");
        PlanModels.PlanSnapshot current = readOwnedPlan(params);
        session.plans().executePlan(new PlanUseCase.ExecutePlan(current.plan().planId(),
                RpcParams.revision(params, "expectedPlanRevision"),
                RpcParams.identifier(params, "planRevisionId", "planrev_", 128),
                digest(params, "planHash"), session.runtimeGeneration(), operationKey(params),
                session.clock().instant()), session.planExecutionEvents());
        return wire.planSnapshot(session.plans().readPlan(current.plan().planId()));
    }

    /**
     * Reject 只提交有界原因；当前 revision/hash 由 PlanService 在事务内锁定。
     */
    private ObjectNode reject(ObjectNode params) {
        RpcParams.requireExact(params, "threadId", "planId", "expectedPlanRevision", "operationKey", "reason");
        PlanModels.PlanSnapshot current = readOwnedPlan(params);
        session.plans().reject(new PlanUseCase.Reject(current.plan().planId(),
                RpcParams.revision(params, "expectedPlanRevision"),
                RpcParams.text(params, "reason", 32_768, false), operationKey(params),
                session.clock().instant()));
        return wire.planSnapshot(session.plans().readPlan(current.plan().planId()));
    }



    /**
     * 结构化计划使用字段闭集和有界数组，DAG 语义由 PlanPolicy 统一验证。
     */
    private PlanModels.PlanDefinition definition(JsonNode node) {
        if (!(node instanceof ObjectNode value)) {
            throw JaRpcException.invalidParams();
        }
        RpcParams.requireExact(value, "objective", "scope", "nonGoals", "constraints",
                "acceptanceCriteria", "steps", "dependencies", "risks", "verificationStrategy");
        List<PlanModels.AcceptanceCriterion> criteria = criteria(value.get("acceptanceCriteria"), 1);
        List<PlanModels.PlanStep> steps = new ArrayList<>();
        for (JsonNode item : array(value, "steps", 1, 256)) {
            if (!(item instanceof ObjectNode step)) {
                throw JaRpcException.invalidParams();
            }
            RpcParams.requireExact(step, "stepId", "title", "description", "required", "dependsOn");
            steps.add(new PlanModels.PlanStep(RpcParams.identifier(step, "stepId", "step_", 128),
                    RpcParams.text(step, "title", 1_024, false),
                    RpcParams.text(step, "description", 32_768, false), bool(step, "required"),
                    identifiers(step, "dependsOn", "step_", 128)));
        }
        return new PlanModels.PlanDefinition(RpcParams.text(value, "objective", 32_768, false),
                texts(value, "scope", 1, 128), texts(value, "nonGoals", 0, 128),
                texts(value, "constraints", 0, 128), texts(value, "dependencies", 0, 128),
                steps, criteria, texts(value, "risks", 0, 128),
                texts(value, "verificationStrategy", 1, 128));
    }

    /**
     * 每次 Plan 操作都核对 owner Thread；Handler 不缓存该关系。
     */
    private PlanModels.PlanSnapshot readOwnedPlan(ObjectNode params) {
        String ownerThreadId = RpcParams.identifier(params, "threadId", "thr_", 128);
        PlanModels.PlanSnapshot snapshot = session.plans().readPlan(planId(params));
        if (!ownerThreadId.equals(snapshot.plan().ownerThreadId())) {
            throw new PlanRepositoryException(PlanRepositoryException.Code.PLAN_NOT_FOUND,
                    "Plan is unavailable for Thread");
        }
        return snapshot;
    }
}
