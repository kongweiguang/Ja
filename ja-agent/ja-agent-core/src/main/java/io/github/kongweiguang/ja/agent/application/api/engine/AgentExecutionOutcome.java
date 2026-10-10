// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.api.engine;

import io.github.kongweiguang.ja.agent.application.api.TurnResult;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;

import java.util.Objects;
import java.util.Optional;

/**
 * 一次已派发执行的结果；持久等待与终态完成采用不同类型表达。
 */
public sealed interface AgentExecutionOutcome permits AgentExecutionOutcome.Completed,
        AgentExecutionOutcome.Suspended, AgentExecutionOutcome.Compacted {
    /**
     * 唯一终态结果，等待用户操作不能伪装成完成。
     *
     * @param result 处理结果
     */
    record Completed(TurnResult result) implements AgentExecutionOutcome {
        /**
         * 仅接受 TurnState 明确定义的终态结果。
         */
        public Completed {
            Objects.requireNonNull(result, "result");
            if (!result.state().terminal()) {
                throw new IllegalArgumentException("execution result is not terminal");
            }
        }
    }

    /**
     * 已持久化挂起事实；调用方可释放当前 owner，之后由显式 resume 开始下一次派发。
     *
     * @param status 当前状态分类
     * @param interactionId 交互标识（可选）
     */
    record Suspended(AgentExecutionStatus status,
                     Optional<String> interactionId) implements AgentExecutionOutcome {
        /**
         * 等待身份可选，因为计划暂停等普通 SUSPENDED 状态没有交互 wait。
         */
        public Suspended {
            Objects.requireNonNull(status, "status");
            interactionId = Objects.requireNonNull(interactionId, "interactionId");
            TurnState state;
            try {
                state = TurnState.valueOf(status.state());
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("unknown suspended execution state", failure);
            }
            if (state != TurnState.SUSPENDED && state != TurnState.WAITING_INPUT
                && state != TurnState.WAITING_APPROVAL) {
                throw new IllegalArgumentException("outcome state is not suspended or waiting");
            }
            boolean waiting = state == TurnState.WAITING_INPUT || state == TurnState.WAITING_APPROVAL;
            if (waiting != interactionId.isPresent()) {
                throw new IllegalArgumentException("wait identity must match interaction state");
            }
            interactionId.ifPresent(value -> {
                if (value.isBlank()) {
                    throw new IllegalArgumentException("interactionId cannot be blank");
                }
            });
        }
    }

    /**
     * 手动压缩返回与 Checkpoint 同事务提交的测量和 revision，不混进普通 Turn 终态 DTO。
     *
     * @param receipt 回执
     */
    record Compacted(AgentCompactionReceipt receipt) implements AgentExecutionOutcome {
        /**
         * 压缩回执由核心执行 Session 从实际提交结果构造，Engine 不推断或补查 latest。
         */
        public Compacted {
            Objects.requireNonNull(receipt, "receipt");
        }
    }
}
