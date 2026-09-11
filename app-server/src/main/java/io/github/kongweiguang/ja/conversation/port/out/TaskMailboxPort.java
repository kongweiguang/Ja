// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.UserContent;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Conversation Loop 消费 Task Mailbox 的出站端口；Task 实现不能反向成为 Loop 的领域依赖。 */
public interface TaskMailboxPort {
    /**
     * 安全点把有界 PENDING 消息绑定到具体 Turn；崩溃重入返回同一 BOUND 批次，真正消费仍由
     * ConversationRepository 与 USER facts 在单事务内完成。
     */
    ClaimBatch claimPendingMessages(String targetThreadId, String turnId, int limit, Instant occurredAt);

    /** Mailbox 类型保留 FOLLOW_UP 首消息复用语义，避免 Conversation 通过 Task 类型做分支。 */
    enum MessageKind {
        /** 普通异步消息在安全点新增一条 USER fact。 */
        MESSAGE,
        /** Follow-up 的首条 USER fact 已在 admission 事务写入，消费时只复核并复用。 */
        FOLLOW_UP,
        /** Child 最终答复作为父 Thread 的普通后续 USER fact 消费。 */
        FINAL_ANSWER
    }

    /** 已绑定消息冻结数据库逐字段复核所需事实，不携带 Task 投影或 UI 状态。 */
    record ClaimedMessage(long sequence, String messageId, String rootThreadId,
                          String senderThreadId, String senderTitle, String targetThreadId,
                          String causalTurnId, MessageKind kind, UserContent content,
                          String idempotencyKey, String boundTurnId) {
        /** BOUND 快照必须拥有正序号、具体 Turn 与不可变结构化内容。 */
        public ClaimedMessage {
            if (sequence < 1) throw new IllegalArgumentException("invalid mailbox sequence");
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(rootThreadId, "rootThreadId");
            Objects.requireNonNull(senderThreadId, "senderThreadId");
            Objects.requireNonNull(senderTitle, "senderTitle");
            Objects.requireNonNull(targetThreadId, "targetThreadId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(boundTurnId, "boundTurnId");
        }
    }

    /** 同一安全点取得的消息批次保持 SQLite FIFO 顺序与唯一 Turn owner。 */
    record ClaimBatch(List<ClaimedMessage> messages, long throughSequence) {
        /** 空批次没有游标；非空批次必须严格递增并由同一 Turn 持有。 */
        public ClaimBatch {
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            if (messages.isEmpty()) {
                if (throughSequence != 0) throw new IllegalArgumentException("empty mailbox batch has a boundary");
            } else {
                String owner = messages.getFirst().boundTurnId();
                long previous = 0;
                for (ClaimedMessage message : messages) {
                    if (!owner.equals(message.boundTurnId()) || message.sequence() <= previous) {
                        throw new IllegalArgumentException("invalid mailbox claim batch");
                    }
                    previous = message.sequence();
                }
                if (throughSequence != previous) {
                    throw new IllegalArgumentException("mailbox boundary does not match claimed messages");
                }
            }
        }
    }
}
