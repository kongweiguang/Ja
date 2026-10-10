// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.service;

import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.conversation.application.gateway.JaToolFactContributor;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepository;

import java.util.List;
import java.util.Objects;

/**
 * 将 Plan 执行 Turn 的真实 Tool 生命周期变为同事务持久事实。
 */
public final class PlanToolExecutionLedger implements JaToolFactContributor {
    private static final String NAMESPACE = "ja.plan";
    private static final String TYPE = "tool-attempt";
    private static final int SCHEMA_VERSION = 1;

    private final PlanRepository plans;
    private final long processGeneration;

    /**
     * Ledger 只冻结调用意图与结果；Plan 行变更由 Ja Store applier 在其唯一事务内执行。
     */
    public PlanToolExecutionLedger(PlanRepository plans, long processGeneration) {
        this.plans = Objects.requireNonNull(plans, "plans");
        if (processGeneration < 1) {
            throw new IllegalArgumentException("invalid process generation");
        }
        this.processGeneration = processGeneration;
    }

    /**
     * 保留显式 JSON null，让严格 applier 区分关联缺失与空身份。
     */
    private static io.github.kongweiguang.ja.agent.domain.json.JsonValue nullable(String value) {
        return value == null ? io.github.kongweiguang.ja.agent.domain.json.JsonNull.INSTANCE
                : new io.github.kongweiguang.ja.agent.domain.json.JsonText(value);
    }

    /**
     * 所有 JaTool 共用这一贡献者，身份冲突在运行时组合阶段直接失败。
     */
    @Override
    public String id() {
        return "ja.plan.tool-attempt-ledger";
    }

    /**
     * 普通用户与子任务不写 Plan ledger；内部 origin 必须带完整冻结 run identity。
     */
    @Override
    public List<AgentTool.ExtensionFact> prepared(
            AgentTool.Invocation invocation, JaTool.ExecutionContext context) {
        if (!isInternal(context)) {
            return List.of();
        }
        return List.of(fact(invocation, context, "PREPARED", null, null));
    }

    /**
     * 开始阶段仅追加事实，不提前改变 Plan 数据库行或放行外部动作。
     */
    @Override
    public List<AgentTool.ExtensionFact> started(
            AgentTool.Invocation invocation, JaTool.ExecutionContext context) {
        if (!isInternal(context)) {
            return List.of();
        }
        return List.of(fact(invocation, context, "STARTED", null, null));
    }

    /**
     * 结算事实只绑定 JaTool 返回的真实终态；未知、取消前未派发不伪造已执行证据。
     */
    @Override
    public List<AgentTool.ExtensionFact> settled(
            AgentTool.Invocation invocation, JaTool.ExecutionContext context, JaTool.ToolResult result) {
        if (!isInternal(context)) {
            return List.of();
        }
        JaTool.ToolResult settled = Objects.requireNonNull(result, "result");
        String digest = PlanDigest.sha256(settled.outcome().name() + '\n'
                                          + Objects.toString(settled.errorCode(), "") + '\n' + settled.content());
        return List.of(fact(invocation, context, "SETTLED", settled, digest));
    }

    /**
     * 内部 origin 的不可变 Turn binding 是 ledger 唯一开关，owner 当前状态由事务 applier 再次核验。
     */
    private boolean isInternal(JaTool.ExecutionContext context) {
        JaTool.ExecutionContext value = Objects.requireNonNull(context, "context");
        if (value.origin() != TurnOrigin.PLAN_EXECUTION) {
            return false;
        }
        if (!value.identity().threadId().equals(value.threadId())
            || !value.identity().turnId().equals(value.turnId())
            || !value.identity().toolCallKey(value.callId()).equals(value.callKey())) {
            throw new IllegalStateException("Plan execution actor binding is invalid");
        }
        PlanRepository.InternalTurnBinding binding = plans.findInternalTurnBinding(value.turnId())
                .orElseThrow(() -> new IllegalStateException("Plan execution binding is unavailable"));
        if (!binding.origin().equals(value.origin().name()) || !binding.runId().equals(value.runId())
            || !Objects.equals(binding.planRevisionId(), value.planRevisionId())) {
            throw new IllegalStateException("Plan execution binding changed");
        }
        if (value.planRevisionId() == null || value.runId() == null) {
            throw new IllegalStateException("Plan execution binding is incomplete");
        }
        return true;
    }

    /**
     * 以 callKey 导出 attempt 身份，prepared、started 与 settled 重建后仍指向同一行。
     */
    private AgentTool.ExtensionFact fact(AgentTool.Invocation invocation, JaTool.ExecutionContext context,
                                         String stage, JaTool.ToolResult result, String resultDigest) {
        AgentTool.Invocation call = Objects.requireNonNull(invocation, "invocation");
        JaTool.ExecutionContext turn = Objects.requireNonNull(context, "context");
        if (!call.callId().equals(turn.callId()) || !turn.identity().toolCallKey(call.callId()).equals(turn.callKey())) {
            throw new IllegalArgumentException("Plan attempt call identity changed");
        }
        return new AgentTool.ExtensionFact(NAMESPACE, TYPE, SCHEMA_VERSION,
                JsonObjects.builder()
                        .putText("tenantId", turn.identity().tenantId())
                        .putText("threadId", turn.identity().threadId())
                        .putText("turnId", turn.identity().turnId())
                        .putText("userId", turn.identity().userId())
                        .putText("origin", turn.origin().name())
                        .put("planRevisionId", nullable(turn.planRevisionId()))
                        .put("runId", nullable(turn.runId()))
                        .putText("callId", call.callId())
                        .putText("callKey", turn.callKey())
                        .putText("toolName", call.toolName())
                        .putNumber("ordinal", call.ordinal())
                        .putText("attemptId", PlanToolAttemptIdentity.id(turn.identity(), turn.callKey()))
                        .putNumber("processGeneration", processGeneration)
                        .putText("requestDigest", PlanDigest.sha256(call.toolName() + '\n' + call.arguments()))
                        .putText("stage", stage)
                        .put("resultOutcome", nullable(result == null ? null : result.outcome().name()))
                        .put("resultErrorCode", nullable(result == null ? null : result.errorCode()))
                        .put("resultDigest", nullable(resultDigest))
                        .build());
    }
}

