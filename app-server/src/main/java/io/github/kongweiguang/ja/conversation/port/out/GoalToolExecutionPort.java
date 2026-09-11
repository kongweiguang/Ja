// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.foundation.json.JsonObject;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** conversation 执行循环写入 Goal Tool ledger 的窄端口，避免 Loop 依赖 Goal 聚合或 SQLite。 */
public interface GoalToolExecutionPort {
    /** 已持久化 Tool call 绑定活动 Goal 当前步骤；没有活动 Goal 时返回空。 */
    Optional<Attempt> prepare(Prepare request);

    /** STARTED 回执提交后，调用方才可以越过真实 Tool 执行边界。 */
    void start(Attempt attempt, Instant at);

    /** 真实结果只以摘要和 digest 进入 Goal 聚合，不复制原始 Tool output。 */
    void settle(Attempt attempt, Settlement settlement);

    /**
     * 读取当前内部 Turn 的不可变 Plan/Goal 身份；Interaction 绑定需要它跨暂停、恢复和重启保持一致。
     * 普通 USER/CHILD_TASK Turn 返回空，不能因为同一 Thread 存在活动聚合而猜测身份。
     */
    default Optional<ExecutionIdentity> executionIdentity(String threadId, String turnId, TurnOrigin origin) {
        return Optional.empty();
    }

    /** 默认空实现保持非 Goal Turn 与聚焦 Loop 测试不产生持久副作用。 */
    static GoalToolExecutionPort disabled() {
        return new GoalToolExecutionPort() {
            /** 禁用边界永远不创建 attempt。 */
            @Override public Optional<Attempt> prepare(Prepare request) { return Optional.empty(); }
            /** 空 attempt 不可能由禁用边界产生。 */
            @Override public void start(Attempt attempt, Instant at) { }
            /** 空 attempt 不可能由禁用边界产生。 */
            @Override public void settle(Attempt attempt, Settlement settlement) { }
        };
    }

    /** prepare 输入只含当前调用已冻结的稳定事实。 */
    record Prepare(String threadId, String turnId, TurnOrigin origin, String callId, String toolName,
                   JsonObject arguments, ToolSideEffect sideEffect, Instant at) {
        /** 防止 adapter 从可变运行环境补推身份。 */
        public Prepare {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(arguments, "arguments");
            Objects.requireNonNull(sideEffect, "sideEffect");
            Objects.requireNonNull(at, "at");
        }
    }

    /** attempt 只向 Loop 暴露后续状态迁移需要的 opaque identity。 */
    record Attempt(String attemptId) {
        /** identity 必须来自 Goal adapter，Loop 不自行构造。 */
        public Attempt { Objects.requireNonNull(attemptId, "attemptId"); }
    }

    /** Interaction 与 Tool ledger 共用的内部执行身份；Plan 与 Goal 字段按 origin 互斥。 */
    record ExecutionIdentity(String planRevisionId, String runId, String goalId) {
        /** 至少绑定 Run，并拒绝把 Plan revision 与 Goal identity 交叉拼接。 */
        public ExecutionIdentity {
            if (runId == null || runId.isBlank()
                    || (goalId == null && planRevisionId == null)) {
                throw new IllegalArgumentException("invalid internal execution identity");
            }
        }
    }

    /** settlement 明确区分 outcome、错误码与经过投影的安全摘要。 */
    record Settlement(ToolOutcome outcome, String content, String errorCode, Instant at) {
        /** 原始内容仅用于本地 digest，adapter 不持久化正文。 */
        public Settlement {
            Objects.requireNonNull(outcome, "outcome");
            content = Objects.requireNonNullElse(content, "");
            Objects.requireNonNull(at, "at");
        }
    }
}
