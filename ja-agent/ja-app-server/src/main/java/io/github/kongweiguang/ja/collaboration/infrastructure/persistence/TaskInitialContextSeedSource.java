// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.infrastructure.persistence;

import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.conversation.application.gateway.InitialContextSeedSource;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepository;

import java.util.Objects;
import java.util.Optional;

/**
 * 将 Task 创建时持久冻结的首轮上下文接到对话准入，不在入站时重新读取父 Thread。
 */
public final class TaskInitialContextSeedSource implements InitialContextSeedSource {
    private final TaskRepository tasks;

    /**
     * 只依赖 Task 快照读取端口，确保首轮读取不会绕过创建时冻结边界。
     */
    public TaskInitialContextSeedSource(TaskRepository tasks) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    /**
     * 无 Task、非首轮或空父上下文都明确返回 empty，由 Core 不写入伪造 seed 记录。
     */
    @Override
    public Optional<InitialContextSeed> findForFirstTurn(String threadId) {
        return tasks.findInitialContextSeedForFirstTurn(Objects.requireNonNull(threadId, "threadId"));
    }
}
