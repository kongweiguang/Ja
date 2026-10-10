// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.api;

import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.util.Objects;

/**
 * 终态提交后的执行结果；界面投影和事件重放由宿主读取持久事件完成。
 *
 * @param state 当前状态
 * @param summary 摘要
 * @param errorCode 错误代码
 * @param errorMessage 错误消息
 */
public record TurnResult(TurnState state,
                         String summary,
                         String errorCode,
                         String errorMessage) {
    /**
     * 等待与挂起不伪装完成；错误保留明确分类，不携带异常对象或运行资源。
     */
    public TurnResult {
        Objects.requireNonNull(state, "state");
        if (!state.terminal()) {
            throw new IllegalArgumentException("execution is not terminal");
        }
        summary = Objects.requireNonNullElse(summary, "");
        if (state == TurnState.COMPLETED && (errorCode != null || errorMessage != null)) {
            throw new IllegalArgumentException("completed execution cannot carry an error");
        }
        if (errorCode != null) {
            errorCode = ContractChecks.identifier(errorCode, "errorCode");
        }
        if (errorMessage != null) {
            errorMessage = ContractChecks.text(errorMessage, "errorMessage", 16_384, true);
        }
    }
}
