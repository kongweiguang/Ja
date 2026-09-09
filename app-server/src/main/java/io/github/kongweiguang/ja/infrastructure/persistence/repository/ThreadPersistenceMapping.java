// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;

/** Thread 的领域定义到持久化写模型的唯一映射边界。 */
public final class ThreadPersistenceMapping {
    /** 映射边界不持有状态，禁止实例化可避免 repository 误把它当成可替换的运行时依赖。 */
    private ThreadPersistenceMapping() {
    }

    /**
     * 普通会话与 Child Task 必须写入完全相同的偏好、标题来源和时间字段；集中映射可防止
     * 两个 repository 在 schema 演进时产生静默漂移，而实际 INSERT 仍留在各自事务中。
     */
    public static PersistenceRecords.ThreadInsert toInsert(ConversationRepository.ThreadDefinition thread) {
        return new PersistenceRecords.ThreadInsert(thread.threadId(), thread.workspaceId(), thread.title(),
                thread.preferences().providerId(), thread.preferences().modelId(),
                thread.preferences().reasoningLevel(), thread.preferences().accessMode().name(),
                thread.preferences().collaborationMode().name(),
                thread.preferences().titleSource().name(), thread.createdAt().toString());
    }
}
