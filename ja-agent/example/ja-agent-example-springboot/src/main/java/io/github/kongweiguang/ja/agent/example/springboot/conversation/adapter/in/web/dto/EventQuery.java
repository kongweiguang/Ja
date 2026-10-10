// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web.dto;

import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.TurnCommand;

/**
 * Thread 水位用于重放，分页大小只影响一次读取。
 *
 * @param threadId 所属对话的稳定标识
 * @param turnId 本次可恢复执行的稳定标识
 * @param afterSequence 从该序号之后开始读取
 * @param limit 返回条目数上限
 */
public record EventQuery(String threadId,
                         String turnId,
                         long afterSequence,
                         Integer limit) {
    /**
     * 未传 limit 使用 256；拒绝负水位和越界分页，不能把错误请求静默修正。
     */
    public EventQuery {
        new TurnCommand(threadId, turnId);
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must be non-negative");
        }
        if (limit == null) {
            limit = 256;
        }
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("limit must be between 1 and 10000");
        }
    }
}
