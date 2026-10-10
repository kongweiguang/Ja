// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.context;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 在新 conversation 首次准入时冻结的中立背景上下文，不代表 child 已执行过这些历史。
 * sourceOrdinal 是父历史的消息水位；seed 消息保留父 ordinal，后续 child 消息从该水位之后继续，
 * 摘要事实保留独立来源的原始 ordinal，不把摘要来源水位误作历史截断边界。
 *
 * @param sourceIdentity 来源身份
 * @param sourceOrdinal 来源消息的顺序位置
 * @param messages 消息列表
 * @param summary 摘要（可选）
 */
public record InitialContextSeed(SourceIdentity sourceIdentity,
                                 long sourceOrdinal,
                                 List<ContextMessage> messages,
                                 Optional<SummaryDocument> summary) {
    /**
     * 冻结父消息的原始序号和顺序，拒绝越过来源水位、无法配对的工具调用。
     */
    public InitialContextSeed {
        Objects.requireNonNull(sourceIdentity, "sourceIdentity");
        if (sourceOrdinal < 0) {
            throw new IllegalArgumentException("sourceOrdinal must not be negative");
        }
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        summary = Objects.requireNonNull(summary, "summary");
        if (messages.isEmpty() && summary.isEmpty()) {
            throw new IllegalArgumentException("empty initial context seed");
        }
        summary.ifPresent(value -> {
            if (value.hasNoFacts()) {
                throw new IllegalArgumentException("initial seed summary has no facts");
            }
        });
        long previousOrdinal = 0;
        HashSet<String> messageIds = new HashSet<>();
        HashSet<String> closedTurns = new HashSet<>();
        HashSet<String> pendingCalls = new HashSet<>();
        String previousTurn = null;
        for (ContextMessage message : messages) {
            Objects.requireNonNull(message, "message");
            if (message.ordinal() <= previousOrdinal || message.ordinal() > sourceOrdinal
                || !messageIds.add(message.messageId())) {
                throw new IllegalArgumentException("initial seed messages are not in source order");
            }
            previousOrdinal = message.ordinal();
            if (previousTurn != null && !previousTurn.equals(message.turnId())) {
                closedTurns.add(previousTurn);
            }
            if (closedTurns.contains(message.turnId())) {
                throw new IllegalArgumentException("initial seed messages split a source turn");
            }
            previousTurn = message.turnId();
            if (message.role() == ContextMessage.Role.SYSTEM) {
                throw new IllegalArgumentException("initial seed cannot contain a system prompt");
            }
            for (String callId : message.toolCallIds()) {
                if (message.role() != ContextMessage.Role.ASSISTANT) {
                    throw new IllegalArgumentException("initial seed Tool call is not assistant-authored");
                }
                if (!pendingCalls.add(callId)) {
                    throw new IllegalArgumentException("duplicate initial seed Tool call");
                }
            }
            for (String callId : message.toolResultIds()) {
                if (message.role() != ContextMessage.Role.TOOL) {
                    throw new IllegalArgumentException("initial seed Tool result is not tool-authored");
                }
                if (!pendingCalls.remove(callId))
                    {
                        throw new IllegalArgumentException("unpaired initial seed Tool result");
                    }
            }
        }
        if (!pendingCalls.isEmpty()) {
            throw new IllegalArgumentException("initial seed ends with an unpaired Tool call");
        }
    }

    /**
     * 来源三元组可跨模块传递，同时让准入层能拒绝跨租户复制。
     *
     * @param tenantId 租户标识，用于隔离不同租户的数据与执行
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     */
    public record SourceIdentity(String tenantId,
                                 String threadId,
                                 String turnId) {
        /**
         * 仅接受稳定来源标识；来源身份不引入 Ja task 或 workspace 类型。
         */
        public SourceIdentity {
            tenantId = ContractChecks.identifier(tenantId, "source tenantId");
            threadId = ContractChecks.identifier(threadId, "source threadId");
            turnId = ContractChecks.identifier(turnId, "source turnId");
        }
    }
}
