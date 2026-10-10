// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.infrastructure.persistence;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFact;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.json.JsonNull;
import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.JdbcTransactionResources;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionEventCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.application.gateway.JaExtensionFactApplier;
import io.github.kongweiguang.ja.plan.application.service.PlanToolCommandCodec;
import io.github.kongweiguang.ja.plan.infrastructure.persistence.serialization.CanonicalPlanJson;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.AgentStoreConnectionScope;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 将已结算的 Plan Tool 命令在 Store 事务内应用，阻止业务行与真实 Tool 结果分离。
 */
public final class PlanExtensionFactApplier implements JaExtensionFactApplier {
    private static final Set<String> ACTOR_FIELDS = Set.of("tenantId", "threadId", "turnId", "userId");
    private static final Set<String> BINDING_FIELDS = Set.of("threadId", "turnId", "origin", "accessMode",
            "collaborationMode", "configGeneration", "workspaceId", "planId", "planRevision", "planRunId");
    private static final Set<String> PAYLOAD_FIELDS = Set.of("actor", "binding", "callId", "callKey", "toolName",
            "arguments", "acceptedAt");

    private final PlanRepository plans;
    private final PlanToolCommandCodec commands;
    private final CanonicalPlanJson canonical;
    private final ExecutionRecordCodec records;
    private final ExecutionEventCodec events;
    private final long processGeneration;

    /**
     * 只依赖仓储与纯 codec，使事实落库不再反向构造 PlanService、TurnService 或 AgentEngine。
     */
    public PlanExtensionFactApplier(PlanRepository plans, PlanToolCommandCodec commands,
                                    CanonicalPlanJson canonical, ExecutionRecordCodec records,
                                    ExecutionEventCodec events, long processGeneration) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.canonical = Objects.requireNonNull(canonical, "canonical");
        this.records = Objects.requireNonNull(records, "records");
        this.events = Objects.requireNonNull(events, "events");
        if (processGeneration < 1) {
            throw new IllegalArgumentException("invalid process generation");
        }
        this.processGeneration = processGeneration;
    }

    /**
     * 请求身份、调用键和冻结应用 binding 必须全部属于本次 Store execution。
     */
    private static void verifyIdentity(AgentStoreTransactionContext tx, AgentIdentity actor, JsonObject binding,
                                       String callId, String callKey) throws SQLException {
        exact(binding, BINDING_FIELDS);
        if (!actor.tenantId().equals(tx.execution().conversation().tenantId())
            || !actor.threadId().equals(tx.execution().conversation().threadId())
            || !actor.turnId().equals(tx.execution().turnId())
            || !tx.execution().turnId().equals(text(binding, "turnId"))
            || !tx.execution().conversation().threadId().equals(text(binding, "threadId"))) {
            throw new SQLException("Plan Tool actor is outside the active execution");
        }
        if (!actor.toolCallKey(callId).equals(callKey)) {
            throw new SQLException("Plan Tool call key does not match its actor");
        }
        try {
            TurnOrigin.valueOf(text(binding, "origin"));
            io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode.valueOf(text(binding, "accessMode"));
            io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode.valueOf(
                    text(binding, "collaborationMode"));
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("Plan Tool request binding is invalid", invalid);
        }
    }

    /**
     * 阻止事实类型越权选择 aggregate，并将模型参数 CAS 与 prepare 阶段冻结身份逐项对照。
     */
    private static void verifyAuthority(JsonObject binding, PlanToolCommandCodec.Command command)
            throws SQLException {
        TurnOrigin origin = TurnOrigin.valueOf(text(binding, "origin"));
        String mode = text(binding, "collaborationMode");
        JsonObject arguments = command.arguments();
        switch (command.toolName()) {
            case "plan_propose", "plan_draft_update" -> {
                requirePlanningOrigin(origin, mode);
                requireBindingText(binding, "planId", command.aggregateId());
                requireBindingNumber(binding, "planRevision", number(arguments, "expectedPlanRevision"));
            }
            case "plan_step_update" -> {
                if (origin != TurnOrigin.PLAN_EXECUTION)
                    {
                        throw new SQLException("Plan step update is not authorized");
                    }
                requireBindingText(binding, "planId", command.aggregateId());
                requireBindingNumber(binding, "planRevision", number(arguments, "expectedPlanRevision"));
                requireBindingText(binding, "planRunId", text(arguments, "runId"));
            }
            default -> throw new SQLException("unsupported Plan Tool command");
        }
    }

    /**
     * 草稿和提案只在冻结 Plan 模式的普通或子任务 Turn 生效。
     */
    private static void requirePlanningOrigin(TurnOrigin origin, String mode) throws SQLException {
        if (!"PLAN".equals(mode) || !Set.of(TurnOrigin.USER, TurnOrigin.USER_CONTINUATION,
                TurnOrigin.CHILD_TASK).contains(origin)) {
            throw new SQLException("Plan draft mutation is not authorized");
        }
    }

    /**
     * actor envelope 拒绝字段缺失或额外载荷，防止未来版本被静默误读。
     */
    private static AgentIdentity actor(JsonObject value) throws SQLException {
        exact(value, ACTOR_FIELDS);
        try {
            return new AgentIdentity(text(value, "tenantId"), text(value, "threadId"),
                    text(value, "turnId"), text(value, "userId"));
        } catch (RuntimeException invalid) {
            throw new SQLException("Plan actor identity is invalid", invalid);
        }
    }

    /**
     * JSON 字段集合必须与固定 schema 完全相等，避免 unknown-field fallback。
     */
    private static void exact(JsonObject value, Set<String> names) throws SQLException {
        if (!value.members().keySet().equals(names)) {
            throw new SQLException("Plan fact schema is invalid");
        }
    }

    /**
     * 每层嵌套身份都显式校验 JSON object，避免错误类型被默认转换掩盖。
     */
    private static JsonObject object(JsonObject value, String field) throws SQLException {
        if (value.get(field) instanceof JsonObject nested) {
            return nested;
        }
        throw new SQLException("Plan fact object is invalid");
    }

    /**
     * 身份文本不做类型 coercion 或空值补全。
     */
    private static String text(JsonObject value, String field) throws SQLException {
        if (value.get(field) instanceof JsonText(String value1) && !value1.isBlank()) {
            return value1;
        }
        throw new SQLException("Plan fact text is invalid");
    }

    /**
     * null 和 revision 数值语义不混用。
     */
    private static long number(JsonObject value, String field) throws SQLException {
        if (value.get(field) instanceof JsonNumber(java.math.BigDecimal value1)) {
            try {
                long result = value1.longValueExact();
                if (result >= 0) {
                    return result;
                }
            } catch (ArithmeticException ignored) {
                // 小数或溢出值统一在下方按非法 revision 拒绝。
            }
        }
        throw new SQLException("Plan fact number is invalid");
    }

    /**
     * 受理时刻必须使用精确 ISO 时间，不能由数据库默认值补造。
     */
    private static Instant instant(JsonObject value, String field) throws SQLException {
        try {
            return Instant.parse(text(value, field));
        } catch (RuntimeException invalid) {
            throw new SQLException("Plan fact time is invalid", invalid);
        }
    }

    /**
     * 比较冻结的非空绑定字段，不把缺失字段与 JSON null 混为一谈。
     */
    private static void requireBindingText(JsonObject binding, String field, String expected) throws SQLException {
        if (!Objects.equals(text(binding, field), expected)) {
            throw new SQLException("Plan CAS binding changed");
        }
    }

    /**
     * revision 可空字段只有在其 JSON 表示明确时才参与比较。
     */
    private static void requireBindingNumber(JsonObject binding, String field, long expected) throws SQLException {
        if (!Objects.equals(nullableNumber(binding, field), expected))
            {
                throw new SQLException("Plan CAS revision changed");
            }
    }

    /**
     * 读取可空 revision 时不把 null 强制转换为零。
     */
    private static Long nullableNumber(JsonObject binding, String field) throws SQLException {
        JsonValue item = binding.get(field);
        if (item == JsonNull.INSTANCE) {
            return null;
        }
        if (item instanceof JsonNumber(java.math.BigDecimal value)) {
            try {
                return value.longValueExact();
            } catch (ArithmeticException invalid) {
                throw new SQLException("Plan binding revision is invalid", invalid);
            }
        }
        throw new SQLException("Plan binding revision is invalid");
    }

    /**
     * 唯一承接 Plan 命令事实的 namespace。
     */
    @Override
    public String namespace() {
        return "ja.plan";
    }

    /**
     * 仅由成功工具结果产生的命令可修改聚合。
     */
    @Override
    public String type() {
        return "tool-command";
    }

    /**
     * payload 结构固定为 schema 1，升级必须显式迁移与注册新 applier。
     */
    @Override
    public int schemaVersion() {
        return 1;
    }

    /**
     * 在当前 Core COMMIT 中核验真实调用结果后，使用同一连接运行带 CAS 的领域命令。
     */
    @Override
    public void apply(AgentStoreTransactionContext transaction, JsonObject payload) throws SQLException {
        AgentStoreTransactionContext tx = Objects.requireNonNull(transaction, "transaction");
        if (tx.kind() != AgentStore.TransactionKind.COMMIT
            || AgentStoreConnectionScope.current().orElse(null) != JdbcTransactionResources.connection(tx)) {
            throw new SQLException("Plan Tool command requires the active AgentStore result transaction");
        }
        exact(payload, PAYLOAD_FIELDS);
        AgentIdentity actor = actor(object(payload, "actor"));
        JsonObject binding = object(payload, "binding");
        String callId = text(payload, "callId");
        String callKey = text(payload, "callKey");
        String toolName = text(payload, "toolName");
        JsonObject arguments = object(payload, "arguments");
        Instant acceptedAt = instant(payload, "acceptedAt");
        verifyIdentity(tx, actor, binding, callId, callKey);
        verifySuccessfulToolResult(tx, callId);
        PlanToolCommandCodec.Command command;
        try {
            command = commands.decode(toolName, arguments, acceptedAt);
            verifyAuthority(binding, command);
        } catch (RuntimeException invalid) {
            throw new SQLException("Plan Tool command binding is invalid", invalid);
        }
        command.apply(plans, canonical, processGeneration, callKey);
    }

    /**
     * 只有当本次 Store 提交同时保存 SUCCEEDED 结果和对应完成事件时，才应用业务命令。
     */
    private void verifySuccessfulToolResult(AgentStoreTransactionContext tx, String callId) throws SQLException {
        ExecutionFacts.ToolResult result = null;
        String resultRecordId = null;
        long ordinal = 0;
        for (AgentStore.RecordWrite record : tx.records()) {
            if (!"agent.tool.result".equals(record.recordType())) {
                continue;
            }
            ExecutionFact decoded = decode(record, ++ordinal);
            if (decoded instanceof ExecutionFacts.ToolResult candidate && candidate.callId().equals(callId)) {
                if (result != null) {
                    throw new SQLException("Plan Tool result is duplicated in the commit");
                }
                result = candidate;
                resultRecordId = record.recordId();
            }
        }
        if (result == null || result.state() != io.github.kongweiguang.ja.agent.domain.tool.ToolState.SUCCEEDED) {
            throw new SQLException("Plan Tool command has no successful result in the same commit");
        }
        boolean completed = false;
        long eventSequence = 0;
        for (AgentStore.EventWrite event : tx.events()) {
            if (!"agent.tool.completed".equals(event.eventType())) {
                continue;
            }
            ExecutionEvent decoded = decode(event, tx, ++eventSequence);
            if (decoded instanceof ExecutionEvent.ToolCompleted value && value.callId().equals(callId)
                && value.state() == io.github.kongweiguang.ja.agent.domain.tool.ToolState.SUCCEEDED
                && value.resultRecordId().equals(resultRecordId)) {
                if (completed) {
                    throw new SQLException("Plan Tool completion event is duplicated");
                }
                completed = true;
            }
        }
        if (!completed) {
            throw new SQLException("Plan Tool result has no matching durable completion event");
        }
    }

    /**
     * Core codec 校验事实时使用记录自身的持久时间；局部序号只满足 codec 的正序约束。
     */
    private ExecutionFact decode(AgentStore.RecordWrite record, long ordinal) throws SQLException {
        try {
            return records.decode(new AgentStore.StoredRecord(record.recordId(), record.recordType(),
                    record.payloadSchemaVersion(), ordinal, record.payload(), record.occurredAt()));
        } catch (RuntimeException invalid) {
            throw new SQLException("Plan Tool execution fact is invalid", invalid);
        }
    }

    /**
     * 将写入事件封装为已编号值供统一事件 codec 严格解析。
     */
    private ExecutionEvent decode(AgentStore.EventWrite event, AgentStoreTransactionContext tx, long ordinal)
            throws SQLException {
        return PlanExecutionEventDecoder.decode(events, event, tx, ordinal,
                "Plan Tool completion event is invalid");
    }

}




