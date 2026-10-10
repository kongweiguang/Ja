// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.domain.model;

import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Collaboration 侧的 Turn admission 快照；出站 ACL 将其转换成 conversation 的存储输入。
 *
 * @param threadId               Thread 标识
 * @param turnId                 Turn 标识
 * @param messageId              USER 消息标识
 * @param userMessage            已提交的 USER 消息
 * @param attachmentIds          附件标识列表
 * @param expectedThreadRevision 调用方预期的 Thread 版本
 * @param requestedAt            接纳请求时间
 * @param initialExecution       初始执行状态
 */
public record TurnAdmission(String threadId,
                            String turnId,
                            String messageId,
                            ModelMessage userMessage,
                            List<String> attachmentIds,
                            long expectedThreadRevision,
                            Instant requestedAt,
                            TurnExecutionState initialExecution) {
    /**
     * admission 只接受已提交的 USER 输入和显式初始执行状态，杜绝伪造 assistant 事实。
     */
    public TurnAdmission {
        threadId = identifier(threadId, "thr_", "threadId");
        turnId = identifier(turnId, "turn_", "turnId");
        messageId = identifier(messageId, "item_", "messageId");
        Objects.requireNonNull(userMessage, "userMessage");
        if (userMessage.role() != ModelRole.USER) {
            throw new IllegalArgumentException("admission requires USER message");
        }
        attachmentIds = List.copyOf(Objects.requireNonNull(attachmentIds, "attachmentIds"));
        if (attachmentIds.size() > 10 || attachmentIds.stream().distinct().count() != attachmentIds.size()) {
            throw new IllegalArgumentException("admission attachments exceed bounds or repeat");
        }
        attachmentIds.forEach(value -> identifier(value, "att_", "attachmentId"));
        if (expectedThreadRevision < 0) {
            throw new IllegalArgumentException("invalid expectedThreadRevision");
        }
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(initialExecution, "initialExecution");
    }

    /**
     * ID 语法在领域边界统一，适配器不能用宽松字符串代替稳定关联键。
     */
    private static String identifier(String value, String prefix, String field) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
                || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }
}
