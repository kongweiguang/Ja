// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionDraft;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionEvent;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionRequest;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionSnapshot;

import java.util.List;
import java.util.Optional;

/**
 * 已提交 Interaction 的查询与草稿端口；答案和关闭统一经 Core 等待事务结算。
 */
public interface InteractionRepository extends AutoCloseable {
    /**
     * 按 Thread 读取唯一活动或指定历史请求，防止跨 Thread 枚举 interactionId。
     */
    Optional<InteractionSnapshot> read(String threadId, String interactionId);

    /**
     * 读取 Thread 当前活动请求；数据库唯一约束保证同一时刻最多一个。
     */
    Optional<InteractionRequest> findActive(String threadId);

    /**
     * 保存尚未定稿的用户草稿，草稿不改变模型请求的答案状态。
     */
    InteractionDraft saveDraft(InteractionDraft draft, long expectedDraftRevision, String operationKey);

    /**
     * 返回观察游标之后的持久事件；序号由数据库分配且严格递增。
     */
    List<InteractionEvent> events(String threadId, long afterSequence);

    /**
     * 关闭资源仅由拥有数据库连接的 adapter 实现。
     */
    @Override
    default void close() {
    }
}
