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
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.JdbcTransactionResources;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionEventCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.application.gateway.JaExtensionFactApplier;
import io.github.kongweiguang.ja.plan.application.service.PlanDigest;
import io.github.kongweiguang.ja.plan.application.service.PlanToolAttemptIdentity;
import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.AgentStoreConnectionScope;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 在 Core 的 prepared、started、result 三个提交中同步推进 Plan Tool attempt。
 */
public final class PlanToolAttemptFactApplier implements JaExtensionFactApplier {
    private static final Set<String> FIELDS = Set.of("tenantId", "threadId", "turnId", "userId",
            "origin", "planRevisionId", "runId", "callId", "callKey",
            "toolName", "ordinal", "attemptId", "processGeneration", "requestDigest", "stage",
            "resultOutcome", "resultErrorCode", "resultDigest");

    private final PlanRepository plans;
    private final ExecutionRecordCodec records;
    private final ExecutionEventCodec events;

    /**
     * 只依赖事务内仓储与 Core codec，禁止从持久 applier 反向取得 Engine/PlanService。
     */
    public PlanToolAttemptFactApplier(PlanRepository plans, ExecutionRecordCodec records,
                                      ExecutionEventCodec events) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.records = Objects.requireNonNull(records, "records");
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * 返回的领域行必须与本次 durable attempt identity 和期望 stage 完全一致。
     */
    private static void verifyAttemptRow(PlanModels.ToolAttempt attempt, JsonObject payload,
                                         ToolCall call, PlanModels.ToolAttemptState expected)
            throws SQLException {
        if (!attempt.toolAttemptId().equals(text(payload, "attemptId"))
            || !attempt.turnId().equals(text(payload, "turnId"))
            || !attempt.callId().equals(call.callId()) || !attempt.runId().equals(text(payload, "runId"))
            || !Objects.equals(attempt.planRevisionId(), nullableText(payload, "planRevisionId"))
            || !attempt.requestDigest().equals(text(payload, "requestDigest"))
            || attempt.processGeneration() != number(payload, "processGeneration")
            || attempt.state() != expected) {
            throw new SQLException("Plan attempt row does not match the active Core call");
        }
    }

    /**
     * 全量 actor 必须属于当前租户、用户、Thread 和 Turn，不能从其它展示字段补齐。
     */
    private static AgentIdentity actor(JsonObject payload) throws SQLException {
        try {
            return new AgentIdentity(text(payload, "tenantId"), text(payload, "threadId"),
                    text(payload, "turnId"), text(payload, "userId"));
        } catch (RuntimeException invalid) {
            throw new SQLException("Plan attempt actor is invalid", invalid);
        }
    }

    /**
     * Actor 必须与当前 Store Turn 一致，且 callKey 由原生 call id 唯一推导。
     */
    private static void verifyIdentity(AgentStoreTransactionContext tx, AgentIdentity actor,
                                       JsonObject payload) throws SQLException {
        if (!actor.tenantId().equals(tx.execution().conversation().tenantId())
            || !actor.threadId().equals(tx.execution().conversation().threadId())
            || !actor.turnId().equals(tx.execution().turnId())
            || !actor.toolCallKey(text(payload, "callId")).equals(text(payload, "callKey"))) {
            throw new SQLException("Plan attempt identity is outside the active execution");
        }
        TurnOrigin origin = origin(payload);
        if (origin != TurnOrigin.PLAN_EXECUTION) {
            throw new SQLException("ordinary Tool call cannot enter Plan attempt ledger");
        }
        if (!PlanToolAttemptIdentity.id(actor, text(payload, "callKey")).equals(text(payload, "attemptId"))) {
            throw new SQLException("Plan attempt id is not derived from its actor");
        }
    }

    /**
     * Turn origin 是授权数据而不是展示标签，未知值一律拒绝。
     */
    private static TurnOrigin origin(JsonObject payload) throws SQLException {
        try {
            return TurnOrigin.valueOf(text(payload, "origin"));
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("Plan attempt origin is invalid", invalid);
        }
    }

    /**
     * 嵌套对象不允许非对象或默认空值进入身份边界。
     */
    private static void exact(JsonObject value, Set<String> fields) throws SQLException {
        if (!value.members().keySet().equals(fields)) {
            throw new SQLException("Plan attempt fact schema is invalid");
        }
    }

    /**
     * PREPARED 与 STARTED payload 不得携带伪造的 Tool 终态或结果摘要。
     */
    private static void requireNoResult(JsonObject payload) throws SQLException {
        if (nullableText(payload, "resultOutcome") != null
            || nullableText(payload, "resultErrorCode") != null
            || nullableText(payload, "resultDigest") != null) {
            throw new SQLException("Plan attempt has a premature result");
        }
    }

    /**
     * 文本字段拒绝 JSON 类型转换和空白 identity。
     */
    private static String text(JsonObject value, String field) throws SQLException {
        if (value.get(field) instanceof JsonText(String value1) && !value1.isBlank()) {
            return value1;
        }
        throw new SQLException("Plan attempt fact text is invalid");
    }

    /**
     * 可空字段必须显式使用 JSON null，保留关联缺失的确定语义。
     */
    private static String nullableText(JsonObject value, String field) throws SQLException {
        JsonValue item = value.get(field);
        if (item == JsonNull.INSTANCE) {
            return null;
        }
        if (item instanceof JsonText(String value1)) {
            return value1;
        }
        throw new SQLException("Plan attempt nullable text is invalid");
    }

    /**
     * 整数 identity 不接收浮点转换、负数或超长输入。
     */
    private static long number(JsonObject value, String field) throws SQLException {
        if (value.get(field) instanceof JsonNumber(java.math.BigDecimal value1)) {
            try {
                long result = value1.longValueExact();
                if (result >= 0) {
                    return result;
                }
            } catch (ArithmeticException ignored) {
                // 非整数及溢出值统一拒绝。
            }
        }
        throw new SQLException("Plan attempt fact number is invalid");
    }

    /**
     * 内部 actor 只在校验确定后派生 attempt，不从请求文本提取租户或执行身份。
     */
    private static AgentIdentity actor(AgentStoreTransactionContext tx, JsonObject payload) throws SQLException {
        AgentIdentity actor = actor(payload);
        if (!actor.tenantId().equals(tx.execution().conversation().tenantId())
            || !actor.threadId().equals(tx.execution().conversation().threadId())
            || !actor.turnId().equals(tx.execution().turnId())) {
            throw new SQLException("Plan attempt actor does not match the Store execution");
        }
        return actor;
    }

    /**
     * 本地调用快照仅用于跨 codec 校验，不会持有 JDBC 资源或可变 Provider 对象。
     */
    private static ToolCall call(JsonObject payload) throws SQLException {
        String callId = text(payload, "callId");
        String toolName = text(payload, "toolName");
        long ordinal = number(payload, "ordinal");
        if (ordinal > Integer.MAX_VALUE) {
            throw new SQLException("Plan attempt Tool ordinal is invalid");
        }
        return new ToolCall(null, callId, toolName, Math.toIntExact(ordinal), text(payload, "requestDigest"));
    }

    /**
     * Plan attempt facts 使用独立 namespace，避免和用户可调用的业务 command 混淆。
     */
    @Override
    public String namespace() {
        return "ja.plan";
    }

    /**
     * 单一 type 通过 stage 显式区分生命周期边界。
     */
    @Override
    public String type() {
        return "tool-attempt";
    }

    /**
     * 结构变化必须新增 schema version，不能把未知 payload 静默解释成旧状态。
     */
    @Override
    public int schemaVersion() {
        return 1;
    }

    /**
     * 同步校验 Core 持久事实及已冻结身份后，才在相同 JDBC connection 推进聚合账本。
     */
    @Override
    public void apply(AgentStoreTransactionContext transaction, JsonObject payload) throws SQLException {
        AgentStoreTransactionContext tx = Objects.requireNonNull(transaction, "transaction");
        if (tx.kind() != AgentStore.TransactionKind.COMMIT
            || AgentStoreConnectionScope.current().orElse(null) != JdbcTransactionResources.connection(tx)) {
            throw new SQLException("Plan attempt requires the active AgentStore commit connection");
        }
        exact(payload, FIELDS);
        AgentIdentity actor = actor(payload);
        verifyIdentity(tx, actor, payload);
        String stage = text(payload, "stage");
        switch (stage) {
            case "PREPARED" -> {
                requireNoResult(payload);
                prepare(tx, payload);
            }
            case "STARTED" -> {
                requireNoResult(payload);
                start(tx, payload);
            }
            case "SETTLED" -> settle(tx, payload);
            default -> throw new SQLException("Plan attempt stage is invalid");
        }
    }

    /**
     * Core completion 回调只关闭仍处于 PREPARED 的 Plan intent；真实 ToolStarted 已由 SETTLED fact 流程负责，
     * 因此只有明确失败/取消结果且当前提交没有 Started 记录时才写未启动终态。
     */
    @Override
    public void applyToolCompletion(AgentStoreTransactionContext transaction,
                                    ExecutionEvent.ToolCompleted completed,
                                    ExecutionFacts.ToolResult result) throws SQLException {
        AgentStoreTransactionContext tx = Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(completed, "completed");
        Objects.requireNonNull(result, "result");
        if (tx.kind() != AgentStore.TransactionKind.COMMIT
            || AgentStoreConnectionScope.current().orElse(null) != JdbcTransactionResources.connection(tx)) {
            throw new SQLException("Plan Tool completion requires the active AgentStore commit connection");
        }
        if (!completed.callId().equals(result.callId()) || completed.state() != result.state()) {
            throw new SQLException("Plan Tool completion differs from its terminal result");
        }
        List<PlanModels.ToolAttempt> attempts =
                plans.findToolAttemptsByTurnIdCallId(tx.execution().turnId(), completed.callId());
        List<PlanModels.ToolAttempt> prepared = attempts.stream()
                .filter(attempt -> attempt.state() == PlanModels.ToolAttemptState.PREPARED)
                .toList();
        if (prepared.isEmpty()) {
            return;
        }
        if (prepared.size() != 1) {
            throw new SQLException("Plan Tool call has multiple PREPARED attempts");
        }
        if (hasStartedForCall(tx, completed.callId())) {
            throw new SQLException("PREPARED Plan Tool call also has a durable ToolStarted fact");
        }
        PlanModels.ToolAttempt attempt = prepared.getFirst();
        PlanModels.ToolAttemptState state = switch (result.state()) {
            case FAILED -> PlanModels.ToolAttemptState.FAILED_BEFORE_START;
            case CANCELLED -> PlanModels.ToolAttemptState.CANCELLED_BEFORE_START;
            case SUCCEEDED -> throw new SQLException("Plan Tool cannot succeed before ToolStarted");
            case PREPARED, RUNNING -> throw new SQLException("Plan Tool result is not terminal");
        };
        String outcome = result.state().name();
        String digest = PlanDigest.sha256(outcome + '\n' + Objects.toString(completed.errorCode(), "")
                                          + '\n' + result.content());
        PlanModels.ToolAttempt settled = plans.settleUnstartedToolAttempt(new PlanRepository.SettleToolAttempt(
                attempt.toolAttemptId(), state, digest, null, tx.occurredAt()));
        if (settled.state() != state || settled.startedAt() != null
            || !Objects.equals(settled.resultDigest(), digest)) {
            throw new SQLException("Plan Tool no-start settlement changed its execution boundary");
        }
    }

    /**
     * 当前 completion window 内任何 Started 事实都否定“未派发”，无论其重复情况如何。
     */
    private boolean hasStartedForCall(AgentStoreTransactionContext tx, String callId) throws SQLException {
        for (AgentStore.RecordWrite record : tx.records()) {
            if (!"agent.tool.started".equals(record.recordType())) {
                continue;
            }
            ExecutionFact fact = decode(record);
            if (fact instanceof ExecutionFacts.ToolStarted started && started.callId().equals(callId)) {
                return true;
            }
        }
        long ordinal = 0;
        for (AgentStore.EventWrite event : tx.events()) {
            if (!"agent.tool.started".equals(event.eventType())) {
                continue;
            }
            ExecutionEvent decoded = decode(event, tx, ++ordinal);
            if (decoded instanceof ExecutionEvent.ToolStarted started && started.callId().equals(callId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 准入写入与唯一 Core ToolPrepared 必须一同提交，故重启后可判断副作用是否真正派发。
     */
    private void prepare(AgentStoreTransactionContext tx, JsonObject payload) throws SQLException {
        ToolCall call = preparedCall(tx, payload);
        PlanModels.ToolAttempt attempt = resolveAttempt(tx, payload, call);
        plans.prepareToolAttempt(new PlanRepository.PrepareToolAttempt(attempt));
    }

    /**
     * STARTED 只能由 Core durable ToolStarted 事实与公开事件共同证明，然后 CAS PREPARED 行。
     */
    private void start(AgentStoreTransactionContext tx, JsonObject payload) throws SQLException {
        ToolCall call = verifyStarted(tx, payload);
        PlanModels.ToolAttempt started = plans.startToolAttempt(text(payload, "attemptId"), tx.occurredAt());
        verifyAttemptRow(started, payload, call, PlanModels.ToolAttemptState.STARTED);
    }

    /**
     * 结算必须匹配同事务真实结果和完成事件；失败事实不可被结果投影改写为成功。
     */
    private void settle(AgentStoreTransactionContext tx, JsonObject payload) throws SQLException {
        ToolCall call = call(payload);
        ExecutionFacts.ToolResult result = toolResult(tx, call.callId());
        ExecutionEvent.ToolCompleted completed = completedEvent(tx, call, result);
        String outcome = text(payload, "resultOutcome");
        String errorCode = nullableText(payload, "resultErrorCode");
        String resultDigest = text(payload, "resultDigest");
        String actualOutcome = switch (result.state()) {
            case SUCCEEDED -> "SUCCEEDED";
            case FAILED -> "FAILED";
            case CANCELLED -> "CANCELLED";
            case PREPARED, RUNNING -> throw new SQLException("Plan attempt result is not terminal");
        };
        String actualError = completed.errorCode();
        if (!actualOutcome.equals(outcome) || !Objects.equals(errorCode, actualError)
            || !PlanDigest.sha256(outcome + '\n' + Objects.toString(errorCode, "") + '\n' + result.content())
                .equals(resultDigest)) {
            throw new SQLException("Plan attempt result differs from the durable tool result");
        }
        PlanModels.ToolAttemptState state = switch (outcome) {
            case "SUCCEEDED" -> PlanModels.ToolAttemptState.SUCCEEDED;
            case "FAILED" -> PlanModels.ToolAttemptState.FAILED;
            case "CANCELLED" -> PlanModels.ToolAttemptState.CANCELLED;
            default -> throw new SQLException("Plan attempt outcome is invalid");
        };
        PlanModels.ToolAttempt settled = plans.settleToolAttempt(new PlanRepository.SettleToolAttempt(
                text(payload, "attemptId"), state, resultDigest, null, tx.occurredAt()));
        verifyAttemptRow(settled, payload, call, state);
    }

    /**
     * 确认 prepared intent 与 payload 指向同一个参数、工具名及批次位置。
     */
    private ToolCall preparedCall(AgentStoreTransactionContext tx, JsonObject payload) throws SQLException {
        String callId = text(payload, "callId");
        String toolName = text(payload, "toolName");
        long ordinal = number(payload, "ordinal");
        if (ordinal > 1_023) {
            throw new SQLException("Plan attempt Tool ordinal is invalid");
        }
        ExecutionFacts.ToolPrepared prepared = uniqueFact(tx, "agent.tool.prepared", ExecutionFacts.ToolPrepared.class,
                value -> value.callId().equals(callId));
        String digest = PlanDigest.sha256(prepared.toolName() + '\n' + prepared.arguments());
        if (!toolName.equals(prepared.toolName()) || ordinal != prepared.ordinal()
            || !digest.equals(text(payload, "requestDigest")) || number(payload, "processGeneration") < 1) {
            throw new SQLException("Plan attempt does not match its durable ToolPrepared fact");
        }
        return new ToolCall(prepared, callId, toolName, Math.toIntExact(ordinal), digest);
    }

    /**
     * 活动状态与不可变 Turn binding 再次校验，不能把内部调用转记到同 owner 的新 run。
     */
    private PlanModels.ToolAttempt resolveAttempt(AgentStoreTransactionContext tx, JsonObject payload,
                                                  ToolCall call) throws SQLException {
        TurnOrigin origin = origin(payload);
        String threadId = text(payload, "threadId");
        String turnId = text(payload, "turnId");
        String runId = text(payload, "runId");
        String planRevisionId = nullableText(payload, "planRevisionId");
        PlanRepository.InternalTurnBinding binding = plans.findInternalTurnBinding(turnId)
                .orElseThrow(() -> new SQLException("Plan attempt Turn binding is unavailable"));
        if (!binding.origin().equals(origin.name()) || !binding.runId().equals(runId)
            || !Objects.equals(binding.planRevisionId(), planRevisionId)) {
            throw new SQLException("Plan attempt Turn binding changed");
        }
        if (binding.planId() == null || planRevisionId == null) {
            throw new SQLException("Plan attempt binding is incomplete");
        }
        PlanModels.PlanSnapshot snapshot = plans.readPlanSnapshot(binding.planId());
        PlanModels.Plan plan = snapshot.plan();
        if (!plan.ownerThreadId().equals(threadId) || plan.status() != PlanModels.PlanStatus.EXECUTING
            || !Objects.equals(plan.activeRunId(), runId)
            || !Objects.equals(plan.activePlanRevisionId(), planRevisionId)
            || snapshot.currentRevision() == null
            || !snapshot.currentRevision().planRevisionId().equals(planRevisionId)
            || !snapshot.currentRevision().planHash().equals(binding.planHash())) {
            throw new SQLException("Plan attempt is outside the active Plan run");
        }
        PlanModels.StepExecution step = snapshot.stepExecutions().stream()
                .filter(item -> item.status() == PlanModels.StepStatus.RUNNING
                                || item.status() == PlanModels.StepStatus.READY)
                .findFirst().orElse(null);
        String stepId = step == null ? null : step.stepId();
        int attemptNumber = step == null ? 1 : Math.max(1, step.attempt());
        String planId = plan.planId();
        String expectedAttemptId = PlanToolAttemptIdentity.id(actor(tx, payload), text(payload, "callKey"));
        if (!expectedAttemptId.equals(text(payload, "attemptId"))) {
            throw new SQLException("Plan attempt id is not derived from this call identity");
        }
        boolean sideEffect = call.prepared().sideEffect() != ToolSideEffect.READ_ONLY;
        return new PlanModels.ToolAttempt(expectedAttemptId, planId, runId,
                planRevisionId, stepId, attemptNumber, turnId, call.callId(), number(payload, "processGeneration"),
                sideEffect, PlanModels.ToolAttemptState.PREPARED, call.digest(), null, tx.occurredAt(), null, null);
    }

    /**
     * STARTED 必须携带 matching Core fact 和事件，不以工具回调开始或内存状态代替持久边界。
     */
    private ToolCall verifyStarted(AgentStoreTransactionContext tx, JsonObject payload)
            throws SQLException {
        ToolCall call = call(payload);
        uniqueFact(tx, "agent.tool.started", ExecutionFacts.ToolStarted.class,
                value -> value.callId().equals(call.callId()));
        boolean matched = false;
        long ordinal = 0;
        for (AgentStore.EventWrite event : tx.events()) {
            if (!"agent.tool.started".equals(event.eventType())) {
                continue;
            }
            ExecutionEvent decoded = decode(event, tx, ++ordinal);
            if (decoded instanceof ExecutionEvent.ToolStarted started && started.callId().equals(call.callId())
                && started.toolName().equals(call.toolName())
                && started.ordinal() == call.ordinal()) {
                if (matched) {
                    throw new SQLException("Plan attempt ToolStarted event is duplicated");
                }
                matched = true;
            }
        }
        if (!matched) {
            throw new SQLException("Plan attempt has no matching durable ToolStarted event");
        }
        return call;
    }

    /**
     * 返回本次结算唯一匹配的真实结果记录。
     */
    private ExecutionFacts.ToolResult toolResult(AgentStoreTransactionContext tx, String callId)
            throws SQLException {
        return uniqueFact(tx, "agent.tool.result", ExecutionFacts.ToolResult.class,
                value -> value.callId().equals(callId));
    }

    /**
     * 完成事件必须引用本次真实结果记录，防止跨调用拼接成功状态。
     */
    private ExecutionEvent.ToolCompleted completedEvent(AgentStoreTransactionContext tx, ToolCall call,
                                                        ExecutionFacts.ToolResult result) throws SQLException {
        String resultRecordId = null;
        for (AgentStore.RecordWrite record : tx.records()) {
            if (!"agent.tool.result".equals(record.recordType())) {
                continue;
            }
            ExecutionFact decoded = decode(record);
            if (decoded instanceof ExecutionFacts.ToolResult candidate && candidate.callId().equals(call.callId())) {
                if (resultRecordId != null) {
                    throw new SQLException("Plan attempt result is duplicated");
                }
                resultRecordId = record.recordId();
            }
        }
        if (resultRecordId == null) {
            throw new SQLException("Plan attempt result record is missing");
        }
        ExecutionEvent.ToolCompleted matched = null;
        long ordinal = 0;
        for (AgentStore.EventWrite event : tx.events()) {
            if (!"agent.tool.completed".equals(event.eventType())) {
                continue;
            }
            ExecutionEvent decoded = decode(event, tx, ++ordinal);
            if (decoded instanceof ExecutionEvent.ToolCompleted completed && completed.callId().equals(call.callId())
                && completed.resultRecordId().equals(resultRecordId)
                && completed.state() == result.state()) {
                if (matched != null) {
                    throw new SQLException("Plan attempt completion event is duplicated");
                }
                matched = completed;
            }
        }
        if (matched == null) {
            throw new SQLException("Plan attempt has no matching durable completion event");
        }
        return matched;
    }

    /**
     * 所有步骤只接受单一匹配调用事实，解码异常让外层 Store 回滚本次提交。
     */
    private <T extends ExecutionFact> T uniqueFact(AgentStoreTransactionContext tx, String type,
                                                   Class<T> factType, java.util.function.Predicate<T> match)
            throws SQLException {
        T found = null;
        for (AgentStore.RecordWrite record : tx.records()) {
            if (!type.equals(record.recordType())) {
                continue;
            }
            ExecutionFact decoded = decode(record);
            if (!factType.isInstance(decoded) || !match.test(factType.cast(decoded))) {
                continue;
            }
            if (found != null) {
                throw new SQLException("Plan attempt Core fact is duplicated");
            }
            found = factType.cast(decoded);
        }
        if (found == null) {
            throw new SQLException("Plan attempt Core fact is missing");
        }
        return found;
    }

    /**
     * Store codec 使用持久记录的时间与 schema 校验 Core 事实，局部序号固定为事实的单记录边界。
     */
    private ExecutionFact decode(AgentStore.RecordWrite record) throws SQLException {
        try {
            return records.decode(new AgentStore.StoredRecord(record.recordId(), record.recordType(),
                    record.payloadSchemaVersion(), 1, record.payload(), record.occurredAt()));
        } catch (RuntimeException invalid) {
            throw new SQLException("Plan attempt Core fact is invalid", invalid);
        }
    }

    /**
     * event codec 严格验证核心 event payload 与当前执行、版本身份。
     */
    private ExecutionEvent decode(AgentStore.EventWrite event, AgentStoreTransactionContext tx, long ordinal)
            throws SQLException {
        return PlanExecutionEventDecoder.decode(events, event, tx, ordinal,
                "Plan attempt Core event is invalid");
    }

    /**
     * 当前事务内经 Core codec 校验的 Tool 调用快照，不持有可变运行对象。
     *
     * @param prepared 已准备
     * @param callId 模型生成的工具调用标识
     * @param toolName 工具名称
     * @param ordinal 当前项的顺序位置
     * @param digest 摘要
     */
    private record ToolCall(ExecutionFacts.ToolPrepared prepared,
                            String callId,
                            String toolName,
                            int ordinal,
                            String digest) {
    }
}



