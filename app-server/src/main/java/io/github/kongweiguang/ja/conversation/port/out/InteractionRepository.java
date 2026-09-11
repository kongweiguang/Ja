// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionDraft;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionEvent;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionRequest;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionSnapshot;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Interaction 的 SQLite 权威端口；所有写操作都要求 revision 与幂等键。 */
public interface InteractionRepository extends AutoCloseable {
    /** 按 Thread 读取唯一活动或指定历史请求，防止跨 Thread 枚举 requestId。 */
    Optional<InteractionSnapshot> read(String threadId, String requestId);

    /** 读取 Thread 当前活动请求；数据库唯一约束保证同一时刻最多一个。 */
    Optional<InteractionRequest> findActive(String threadId);

    /** 取消或替代只允许从 PENDING 转换，迟到响应不会唤醒 Turn。 */
    InteractionRequest close(String threadId, String requestId, long expectedRevision,
                             InteractionStatus status, String idempotencyKey, Instant occurredAt);

    /** 保存尚未定稿的用户草稿，草稿不改变模型请求的答案状态。 */
    InteractionDraft saveDraft(InteractionDraft draft, long expectedRevision, String idempotencyKey);

    /** 返回观察游标之后的持久事件；序号由数据库分配且严格递增。 */
    List<InteractionEvent> events(String threadId, long afterSequence);

    /** 关闭资源仅由拥有数据库连接的 adapter 实现。 */
    @Override
    default void close() { }
}
