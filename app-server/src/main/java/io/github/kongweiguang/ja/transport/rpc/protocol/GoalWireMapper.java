// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.port.in.PlanEvent;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Goal 与独立 Plan 领域投影到 JA-RPC v1 的唯一映射器。 */
public final class GoalWireMapper {
    private final ObjectMapper mapper;

    /** 复用连接级 ObjectMapper，避免反射序列化把内部字段意外暴露到协议。 */
    public GoalWireMapper(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Goal 读写只返回 Goal 聚合；Plan 只能通过显式 link identity 关联。 */
    public ObjectNode snapshot(GoalModels.GoalSnapshot snapshot) {
        ObjectNode result = mapper.createObjectNode();
        result.set("goal", goal(snapshot));
        return result.put("eventSequence", snapshot.eventSequence());
    }

    /** Plan 查询与 mutation 返回独立 CAS 投影，不携带或推断 Goal identity。 */
    public ObjectNode planSnapshot(GoalModels.PlanSnapshot snapshot) {
        ObjectNode result = mapper.createObjectNode();
        result.set("plan", plan(snapshot.plan()));
        if (snapshot.draft() == null) result.putNull("draft");
        else result.set("draft", draft(snapshot.draft()));
        if (snapshot.currentRevision() == null) result.putNull("currentRevision");
        else result.set("currentRevision", revision(snapshot.currentRevision()));
        if (snapshot.approval() == null) result.putNull("approval");
        else result.set("approval", approval(snapshot.approval()));
        ArrayNode executions = result.putArray("stepExecutions");
        snapshot.stepExecutions().forEach(value -> executions.add(stepExecution(value)));
        return result.put("eventSequence", snapshot.eventSequence());
    }

    /** 执行进度仅发送 Plan 行，不把完整版本和证据广播给隐藏工作面。 */
    public ObjectNode planChanged(PlanEvent event) {
        ObjectNode result = mapper.createObjectNode().put("ownerThreadId", event.plan().ownerThreadId())
                .put("planId", event.plan().planId()).put("planRevision", event.plan().revision())
                .put("eventSequence", event.eventSequence());
        result.set("plan", plan(event.plan()));
        result.set("progress", mapper.createObjectNode()
                .put("currentStepId", event.progress().currentStepId())
                .put("currentStepTitle", event.progress().currentStepTitle())
                .put("completedRequiredSteps", event.progress().completedRequiredSteps())
                .put("totalRequiredSteps", event.progress().totalRequiredSteps()));
        return result;
    }

    /** Goal 事件页只公开持久 sequence，cursor 保持 opaque。 */
    public ObjectNode events(GoalModels.Page<GoalModels.PublicEvent> page) {
        ObjectNode result = goalPageBase(page);
        ArrayNode items = result.putArray("items");
        page.items().forEach(event -> items.add(mapper.createObjectNode()
                .put("eventSequence", event.eventSequence()).put("kind", event.kind())
                .put("summary", event.summary()).put("occurredAt", event.occurredAt().toString())));
        cursor(result, page.nextCursor());
        return result;
    }

    /** Plan 页使用独立聚合 revision，避免客户端误用 Goal CAS。 */
    public ObjectNode planEvents(GoalModels.Page<GoalModels.PublicEvent> page) {
        ObjectNode result = mapper.createObjectNode().put("planId", page.aggregateId())
                .put("planRevision", page.aggregateRevision()).put("eventSequence", page.eventSequence());
        ArrayNode items = result.putArray("items");
        page.items().forEach(event -> items.add(mapper.createObjectNode()
                .put("eventSequence", event.eventSequence()).put("kind", event.kind())
                .put("summary", event.summary()).put("occurredAt", event.occurredAt().toString())));
        cursor(result, page.nextCursor());
        return result;
    }

    /** 相同计划可有多个历史 Run，证据页必须保持调用方选择的冻结身份。 */
    public ObjectNode planEvidence(GoalModels.Page<GoalModels.Evidence> page, String planRevisionId, String runId) {
        ObjectNode result = mapper.createObjectNode().put("planId", page.aggregateId())
                .put("planRevision", page.aggregateRevision()).put("eventSequence", page.eventSequence())
                .put("planRevisionId", planRevisionId).put("runId", runId);
        ArrayNode items = result.putArray("items");
        page.items().forEach(value -> items.add(evidence(value)));
        cursor(result, page.nextCursor());
        return result;
    }

    /** Plan revision 页使用 Plan 自己的 revision 水位，不能复用 Goal CAS 字段名。 */
    public ObjectNode revisions(GoalModels.Page<GoalModels.PlanRevision> page, GoalModels.Plan plan) {
        ObjectNode result = mapper.createObjectNode().put("planId", page.aggregateId())
                .put("planRevision", page.aggregateRevision()).put("eventSequence", page.eventSequence());
        if (!plan.planId().equals(page.aggregateId()) || plan.revision() != page.aggregateRevision()) {
            throw new IllegalArgumentException("Plan revision page does not match Plan snapshot");
        }
        ArrayNode items = result.putArray("items");
        page.items().forEach(value -> items.add(revision(value)));
        cursor(result, page.nextCursor());
        return result;
    }

    /** Evidence 页固定 Goal definition，nullable Plan revision 只表示可选过滤条件。 */
    public ObjectNode evidence(GoalModels.Page<GoalModels.Evidence> page,
                               long goalDefinitionRevision, String planRevisionId) {
        ObjectNode result = goalPageBase(page).put("goalDefinitionRevision", goalDefinitionRevision);
        nullable(result, "planRevisionId", planRevisionId);
        ArrayNode items = result.putArray("items");
        page.items().forEach(value -> items.add(evidence(value)));
        cursor(result, page.nextCursor());
        return result;
    }

    /** goal/changed 与 goal/read 共享同一 Goal 形状，事件公共元数据由 RpcSession 添加。 */
    public ObjectNode changed(GoalModels.GoalSnapshot snapshot) {
        ObjectNode result = mapper.createObjectNode().put("goalId", snapshot.goal().goalId())
                .put("goalRevision", snapshot.goal().revision())
                .put("eventSequence", snapshot.eventSequence());
        result.set("goal", goal(snapshot));
        return result;
    }

    /** activity 仅投影状态行所需的小型事实，不把持久 payload 直接透传到 WebView。 */
    public ObjectNode activity(GoalModels.GoalSnapshot snapshot, GoalModels.PublicEvent event) {
        String kind = switch (event.kind()) {
            case "step_changed" -> "step";
            case "evaluation_started", "evaluation_completed" -> "evaluation";
            case "recovery_required" -> "recovery";
            default -> "run";
        };
        ObjectNode activity = mapper.createObjectNode().put("kind", kind)
                .put("status", lower(snapshot.goal().phase())).put("summary", event.summary());
        if ("step".equals(kind) && snapshot.currentStepId() != null) {
            activity.put("stepId", snapshot.currentStepId());
        } else activity.putNull("stepId");
        ObjectNode result = mapper.createObjectNode().put("goalId", snapshot.goal().goalId())
                .put("goalRevision", snapshot.goal().revision())
                .put("eventSequence", event.eventSequence());
        return result.set("activity", activity);
    }

    /** Conversation 时间线只承载不可逆 Goal 终态摘要。 */
    public ObjectNode terminalActivity(GoalModels.TerminalActivity activity) {
        return mapper.createObjectNode().put("goalId", activity.goalId())
                .put("objective", activity.objective()).put("status", lower(activity.status()))
                .put("goalRevision", activity.goalRevision()).put("eventSequence", activity.eventSequence())
                .put("occurredAt", activity.occurredAt().toString());
    }

    /** Goal 顶层包含冻结 definition、可选 Plan link 和独立 evaluator 结论。 */
    private ObjectNode goal(GoalModels.GoalSnapshot snapshot) {
        GoalModels.Goal goal = snapshot.goal();
        GoalModels.GoalDefinition definition = snapshot.definition();
        ObjectNode owner = mapper.createObjectNode();
        if (goal.ownerKind() == GoalModels.OwnerKind.ROOT_THREAD) {
            owner.put("kind", "thread").put("threadId", goal.ownerThreadId());
        } else owner.put("kind", "independent_task").put("taskThreadId", goal.ownerThreadId());
        ObjectNode result = mapper.createObjectNode().put("goalId", goal.goalId()).set("owner", owner);
        result.put("objective", goal.objective()).put("goalDefinitionRevision", goal.goalDefinitionRevision());
        ArrayNode criteria = result.putArray("acceptanceCriteria");
        definition.acceptanceCriteria().forEach(value -> criteria.add(criterion(value)));
        result.put("status", lower(goal.status())).put("phase", lower(goal.phase()))
                .put("revision", goal.revision());
        if (snapshot.planLink() == null) result.putNull("planLink");
        else result.set("planLink", link(snapshot.planLink()));
        nullable(result, "currentRunId", goal.activeRunId());
        nullable(result, "currentStepId", snapshot.currentStepId());
        result.put("completedRequiredSteps", snapshot.completedRequiredSteps())
                .put("totalRequiredSteps", snapshot.totalRequiredSteps());
        nullable(result, "attentionReason", snapshot.attentionReason());
        if (snapshot.latestEvaluation() == null) result.putNull("latestEvaluation");
        else result.set("latestEvaluation", evaluation(snapshot.latestEvaluation()));
        result.put("createdAt", goal.createdAt().toString()).put("updatedAt", goal.updatedAt().toString());
        instant(result, "achievedAt", snapshot.achievedAt());
        instant(result, "stoppedAt", snapshot.stoppedAt());
        return result;
    }

    /** Goal link 冻结精确批准版本；linkRevision 仅供显示与审计。 */
    private ObjectNode link(GoalModels.GoalPlanLink link) {
        return mapper.createObjectNode().put("planId", link.planId())
                .put("planRevisionId", link.planRevisionId()).put("planHash", link.planHash())
                .put("linkRevision", link.linkRevision()).put("attachedAt", link.attachedAt().toString());
    }

    /** Plan 本体只持有 Thread owner 和自身 CAS/run，不携带 Goal 字段。 */
    private ObjectNode plan(GoalModels.Plan plan) {
        ObjectNode owner = mapper.createObjectNode().put("kind", "thread")
                .put("threadId", plan.ownerThreadId());
        ObjectNode result = mapper.createObjectNode().put("planId", plan.planId()).set("owner", owner);
        result.put("objective", plan.objective()).put("status", lower(plan.status()))
                .put("revision", plan.revision());
        nullable(result, "activePlanRevisionId", plan.activePlanRevisionId());
        nullable(result, "activeRunId", plan.activeRunId());
        return result.put("createdAt", plan.createdAt().toString()).put("updatedAt", plan.updatedAt().toString());
    }

    /** Draft 展开结构化定义与 draft CAS，不输出内部 canonical JSON。 */
    private ObjectNode draft(GoalModels.PlanDraft draft) {
        ObjectNode result = definition(draft.definition()).put("planDraftId", draft.planDraftId())
                .put("planId", draft.planId()).put("draftRevision", draft.draftRevision());
        nullable(result, "basePlanRevisionId", draft.basePlanRevisionId());
        return result.put("updatedAt", draft.updatedAt().toString());
    }

    /** Revision 展开冻结定义、作者与 hash，canonical JSON 不重复传输。 */
    private ObjectNode revision(GoalModels.PlanRevision revision) {
        ObjectNode result = definition(revision.definition()).put("planRevisionId", revision.planRevisionId())
                .put("planId", revision.planId()).put("revisionNumber", revision.revisionNumber())
                .put("planHash", revision.planHash())
                .put("createdBy", revision.createdBy().toLowerCase(Locale.ROOT));
        return result.put("createdAt", revision.createdAt().toString());
    }

    /** 结构化定义保持数组顺序，canonical hash 仍只由 Java 领域服务生成。 */
    private ObjectNode definition(GoalModels.PlanDefinition definition) {
        ObjectNode result = mapper.createObjectNode().put("objective", definition.objective());
        texts(result.putArray("scope"), definition.scope());
        texts(result.putArray("nonGoals"), definition.nonGoals());
        texts(result.putArray("constraints"), definition.constraints());
        ArrayNode criteria = result.putArray("acceptanceCriteria");
        definition.acceptanceCriteria().forEach(value -> criteria.add(criterion(value)));
        ArrayNode steps = result.putArray("steps");
        definition.steps().forEach(value -> {
            ObjectNode step = mapper.createObjectNode().put("stepId", value.stepId())
                    .put("title", value.title()).put("description", value.description())
                    .put("required", value.required());
            texts(step.putArray("dependsOn"), value.dependsOn());
            steps.add(step);
        });
        texts(result.putArray("dependencies"), definition.dependencies());
        texts(result.putArray("risks"), definition.risks());
        texts(result.putArray("verificationStrategy"), definition.verificationStrategy());
        return result;
    }

    /** Acceptance criterion 使用稳定 identity 供 evidence 与 evaluator 逐项引用。 */
    private ObjectNode criterion(GoalModels.AcceptanceCriterion value) {
        return mapper.createObjectNode().put("criterionId", value.criterionId())
                .put("description", value.description()).put("required", value.required());
    }

    /** 批准投影只包含精确 revision/hash 和用户批准时刻。 */
    private ObjectNode approval(GoalModels.PlanApproval approval) {
        return mapper.createObjectNode().put("approvalId", approval.approvalId())
                .put("planId", approval.planId()).put("planRevisionId", approval.planRevisionId())
                .put("planHash", approval.planHash()).put("approvedAt", approval.approvedAt().toString());
    }

    /** Step execution 保留稳定 stepId 与 attempt，不泄漏执行器私有 identity。 */
    private ObjectNode stepExecution(GoalModels.StepExecution value) {
        ObjectNode result = mapper.createObjectNode().put("stepId", value.stepId()).put("runId", value.runId())
                .put("status", lower(value.status())).put("attempt", value.attempt());
        nullable(result, "failureSignature", value.failureSignature());
        nullable(result, "summary", value.summary());
        instant(result, "startedAt", value.startedAt());
        instant(result, "completedAt", value.completedAt());
        return result;
    }

    /** evaluator 输出精确绑定 Goal definition，Plan revision 在 Goal-only 运行中可为 null。 */
    private ObjectNode evaluation(GoalModels.GoalEvaluation value) {
        ObjectNode result = mapper.createObjectNode().put("evaluationId", value.evaluationId())
                .put("goalId", value.goalId()).put("goalDefinitionRevision", value.goalDefinitionRevision());
        nullable(result, "planRevisionId", value.planRevisionId());
        result.put("runId", value.runId()).put("verdict", lower(value.verdict()));
        ArrayNode criteria = result.putArray("criteria");
        value.criteria().forEach(item -> criteria.add(mapper.createObjectNode()
                .put("criterionId", item.criterionId()).put("verdict", lower(item.verdict()))
                .put("reason", item.reason())));
        return result.put("summary", value.summary()).put("completedAt", value.completedAt().toString());
    }

    /** Evidence 显式投影 nullable owner/revision，不能把 Plan-only 证据伪装成 Goal 证据。 */
    private ObjectNode evidence(GoalModels.Evidence value) {
        ObjectNode result = mapper.createObjectNode().put("evidenceId", value.evidenceId());
        nullable(result, "goalId", value.goalId());
        nullable(result, "planId", value.planId());
        nullableLong(result, "goalDefinitionRevision", value.goalDefinitionRevision());
        result.put("runId", value.runId());
        nullable(result, "planRevisionId", value.planRevisionId());
        nullable(result, "criterionId", value.criterionId());
        nullable(result, "stepId", value.stepId());
        return result.put("sourceType", lower(value.sourceType())).put("sourceId", value.sourceId())
                .put("summary", value.summary()).put("digest", value.digest())
                .put("observedAt", value.observedAt().toString()).put("createdAt", value.createdAt().toString());
    }

    /** Goal 分页字段只用于 Goal-owned 查询。 */
    private ObjectNode goalPageBase(GoalModels.Page<?> page) {
        return mapper.createObjectNode().put("goalId", page.aggregateId())
                .put("goalRevision", page.aggregateRevision()).put("eventSequence", page.eventSequence());
    }

    /** 数组逐项写入，避免领域集合反射序列化造成字段漂移。 */
    private static void texts(ArrayNode target, List<String> values) {
        values.forEach(target::add);
    }

    /** required-nullable 文本字段始终出现在响应中。 */
    private static void nullable(ObjectNode target, String field, String value) {
        if (value == null) target.putNull(field); else target.put(field, value);
    }

    /** nullable revision 单独处理，避免 Long 自动拆箱导致空指针。 */
    private static void nullableLong(ObjectNode target, String field, Long value) {
        if (value == null) target.putNull(field); else target.put(field, value);
    }

    /** 可选时间统一输出 ISO-8601 或显式 null。 */
    private static void instant(ObjectNode target, String field, Instant value) {
        if (value == null) target.putNull(field); else target.put(field, value.toString());
    }

    /** 分页游标始终显式输出 null 或 opaque 值。 */
    private static void cursor(ObjectNode target, String value) {
        if (value == null) target.putNull("nextCursor"); else target.put("nextCursor", value);
    }

    /** 领域 enum 统一映射为 JA-RPC 小写词汇。 */
    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
