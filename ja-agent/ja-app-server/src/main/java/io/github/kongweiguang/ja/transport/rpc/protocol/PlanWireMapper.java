// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.application.api.PlanEvent;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 独立 Plan 领域投影到 JA-RPC v2 的唯一映射器。
 */
public final class PlanWireMapper {
    private final ObjectMapper mapper;

    /**
     * 复用连接级 ObjectMapper，避免反射序列化把内部字段意外暴露到协议。
     */
    public PlanWireMapper(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * 数组逐项写入，避免领域集合反射序列化造成字段漂移。
     */
    private static void texts(ArrayNode target, List<String> values) {
        values.forEach(target::add);
    }

    /**
     * required-nullable 文本字段始终出现在响应中。
     */
    private static void nullable(ObjectNode target, String field, String value) {
        if (value == null) {
            target.putNull(field);
        }
        else {
            target.put(field, value);
        }
    }

    /**
     * 可选时间统一输出 ISO-8601 或显式 null。
     */
    private static void instant(ObjectNode target, String field, Instant value) {
        if (value == null) {
            target.putNull(field);
        }
        else {
            target.put(field, value.toString());
        }
    }

    /**
     * 分页游标始终显式输出 null 或 opaque 值。
     */
    private static void cursor(ObjectNode target, String value) {
        if (value == null) {
            target.putNull("nextCursor");
        }
        else {
            target.put("nextCursor", value);
        }
    }

    /**
     * 领域 enum 统一映射为 JA-RPC 小写词汇。
     */
    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }


    /**
     * Plan 查询与 mutation 返回独立 CAS 投影。
     */
    public ObjectNode planSnapshot(PlanModels.PlanSnapshot snapshot) {
        ObjectNode result = mapper.createObjectNode();
        result.set("plan", plan(snapshot.plan()));
        if (snapshot.draft() == null) {
            result.putNull("draft");
        }
        else {
            result.set("draft", draft(snapshot.draft()));
        }
        if (snapshot.currentRevision() == null) {
            result.putNull("currentRevision");
        }
        else {
            result.set("currentRevision", revision(snapshot.currentRevision()));
        }
        if (snapshot.approval() == null) {
            result.putNull("approval");
        }
        else {
            result.set("approval", approval(snapshot.approval()));
        }
        ArrayNode executions = result.putArray("stepExecutions");
        snapshot.stepExecutions().forEach(value -> executions.add(stepExecution(value)));
        return result.put("eventSequence", snapshot.eventSequence());
    }

    /**
     * 执行进度仅发送 Plan 行，不把完整版本和证据广播给隐藏工作面。
     */
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


    /**
     * Plan 页使用自身聚合 revision。
     */
    public ObjectNode planEvents(PlanModels.Page<PlanModels.PublicEvent> page) {
        ObjectNode result = mapper.createObjectNode().put("planId", page.aggregateId())
                .put("planRevision", page.aggregateRevision()).put("eventSequence", page.eventSequence());
        ArrayNode items = result.putArray("items");
        appendEvents(items, page.items());
        cursor(result, page.nextCursor());
        return result;
    }

    /**
     * 相同计划可有多个历史 Run，证据页必须保持调用方选择的冻结身份。
     */
    public ObjectNode planEvidence(PlanModels.Page<PlanModels.Evidence> page, String planRevisionId, String runId) {
        ObjectNode result = mapper.createObjectNode().put("planId", page.aggregateId())
                .put("planRevision", page.aggregateRevision()).put("eventSequence", page.eventSequence())
                .put("planRevisionId", planRevisionId).put("runId", runId);
        ArrayNode items = result.putArray("items");
        page.items().forEach(value -> items.add(evidence(value)));
        cursor(result, page.nextCursor());
        return result;
    }

    /**
     * Plan revision 页使用 Plan 自己的 revision 水位。
     */
    public ObjectNode revisions(PlanModels.Page<PlanModels.PlanRevision> page, PlanModels.Plan plan) {
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







    /**
     * Plan 本体只持有 Thread owner 和自身 CAS/run。
     */
    private ObjectNode plan(PlanModels.Plan plan) {
        ObjectNode owner = mapper.createObjectNode().put("kind", "thread")
                .put("threadId", plan.ownerThreadId());
        ObjectNode result = mapper.createObjectNode().put("planId", plan.planId()).set("owner", owner);
        result.put("objective", plan.objective()).put("status", lower(plan.status()))
                .put("revision", plan.revision());
        nullable(result, "activePlanRevisionId", plan.activePlanRevisionId());
        nullable(result, "activeRunId", plan.activeRunId());
        return result.put("createdAt", plan.createdAt().toString()).put("updatedAt", plan.updatedAt().toString());
    }

    /**
     * Draft 展开结构化定义与 draft CAS，不输出内部 canonical JSON。
     */
    private ObjectNode draft(PlanModels.PlanDraft draft) {
        ObjectNode result = definition(draft.definition()).put("planDraftId", draft.planDraftId())
                .put("planId", draft.planId()).put("draftRevision", draft.draftRevision());
        nullable(result, "basePlanRevisionId", draft.basePlanRevisionId());
        return result.put("updatedAt", draft.updatedAt().toString());
    }

    /**
     * Revision 展开冻结定义、作者与 hash，canonical JSON 不重复传输。
     */
    private ObjectNode revision(PlanModels.PlanRevision revision) {
        ObjectNode result = definition(revision.definition()).put("planRevisionId", revision.planRevisionId())
                .put("planId", revision.planId()).put("revisionNumber", revision.revisionNumber())
                .put("planHash", revision.planHash())
                .put("createdBy", revision.createdBy().toLowerCase(Locale.ROOT));
        return result.put("createdAt", revision.createdAt().toString());
    }

    /**
     * 结构化定义保持数组顺序，canonical hash 仍只由 Java 领域服务生成。
     */
    private ObjectNode definition(PlanModels.PlanDefinition definition) {
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

    /**
     * Acceptance criterion 使用稳定 identity 供 evidence 与 evaluator 逐项引用。
     */
    private ObjectNode criterion(PlanModels.AcceptanceCriterion value) {
        return mapper.createObjectNode().put("criterionId", value.criterionId())
                .put("description", value.description()).put("required", value.required());
    }

    /**
     * 批准投影只包含精确 revision/hash 和用户批准时刻。
     */
    private ObjectNode approval(PlanModels.PlanApproval approval) {
        return mapper.createObjectNode().put("approvalId", approval.approvalId())
                .put("planId", approval.planId()).put("planRevisionId", approval.planRevisionId())
                .put("planHash", approval.planHash()).put("approvedAt", approval.approvedAt().toString());
    }

    /**
     * Step execution 保留稳定 stepId 与 attempt，不泄漏执行器私有 identity。
     */
    private ObjectNode stepExecution(PlanModels.StepExecution value) {
        ObjectNode result = mapper.createObjectNode().put("stepId", value.stepId()).put("runId", value.runId())
                .put("status", lower(value.status())).put("attempt", value.attempt());
        nullable(result, "failureSignature", value.failureSignature());
        nullable(result, "summary", value.summary());
        instant(result, "startedAt", value.startedAt());
        instant(result, "completedAt", value.completedAt());
        return result;
    }




    /**
     * 事件页只公开稳定序号与摘要，避免持久 payload 泄漏到协议。
     */
    private void appendEvents(ArrayNode target, List<PlanModels.PublicEvent> events) {
        events.forEach(event -> target.add(mapper.createObjectNode()
                .put("eventSequence", event.eventSequence()).put("kind", event.kind())
                .put("summary", event.summary()).put("occurredAt", event.occurredAt().toString())));
    }

    /**
     * Plan evidence 只暴露冻结版本和真实来源，避免内部持久字段进入协议。
     */
    private ObjectNode evidence(PlanModels.Evidence value) {
        ObjectNode result = mapper.createObjectNode().put("evidenceId", value.evidenceId())
                .put("planId", value.planId()).put("runId", value.runId())
                .put("planRevisionId", value.planRevisionId());
        nullable(result, "criterionId", value.criterionId());
        nullable(result, "stepId", value.stepId());
        return result.put("sourceType", lower(value.sourceType())).put("sourceId", value.sourceId())
                .put("summary", value.summary()).put("digest", value.digest())
                .put("observedAt", value.observedAt().toString()).put("createdAt", value.createdAt().toString());
    }
}
