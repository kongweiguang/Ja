// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Ja 历史消息与 Turn 展示投影的 admission 输入，不是第二份 AgentStore 执行状态。
 *
 * @param threadId 所属对话的稳定标识
 * @param turnId 本次可恢复执行的稳定标识
 * @param messageId 消息标识
 * @param userMessage 用户消息
 * @param attachmentIds 附件标识集合
 * @param expectedThreadRevision 预期对话修订版本
 * @param requestedAt 请求时间
 * @param initialExecution 初始执行
 */
public record JaTurnAdmission(String threadId,
                              String turnId,
                              String messageId,
                              ModelMessage userMessage,
                              List<String> attachmentIds,
                              long expectedThreadRevision,
                              Instant requestedAt,
                              TurnExecutionState initialExecution) {
    /**
     * 校验宿主投影只接收已由 AgentStore 接纳的 USER 输入与显式初始游标。
     */
    public JaTurnAdmission {
        threadId = prefixed(threadId, "thr_", "threadId");
        turnId = prefixed(turnId, "turn_", "turnId");
        messageId = prefixed(messageId, "item_", "messageId");
        Objects.requireNonNull(userMessage, "userMessage");
        if (userMessage.role() != ModelRole.USER) {
            throw new IllegalArgumentException("admission requires USER message");
        }
        attachmentIds = List.copyOf(Objects.requireNonNull(attachmentIds, "attachmentIds"));
        if (attachmentIds.size() > 10 || attachmentIds.stream().distinct().count() != attachmentIds.size()) {
            throw new IllegalArgumentException("admission attachments exceed bounds or repeat");
        }
        attachmentIds.forEach(value -> prefixed(value, "att_", "attachmentId"));
        if (expectedThreadRevision < 0) {
            throw new IllegalArgumentException("invalid expectedThreadRevision");
        }
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(initialExecution, "initialExecution");
    }

    /**
     * 限定当前 Ja 公开标识语法，持久边界不对关联键做宽松归一化。
     */
    private static String prefixed(String value, String prefix, String field) {
        String checked = ContractChecks.identifier(value, field);
        if (!checked.startsWith(prefix)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return checked;
    }
}
