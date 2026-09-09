// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.port.out.TaskMailboxPort;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** AgentLoop 在模型与 Tool 安全点 claim Task Mailbox 的 late-bound 窄桥。 */
final class TaskMailboxInbox {
    private static final int CLAIM_LIMIT = 256;
    private final AtomicReference<TaskMailboxPort> repository = new AtomicReference<>();

    /**
     * 组合根只允许绑定一个 Task Repository owner；同实例重入用于测试/Native 初始化幂等，
     * 禁止运行中替换后让一个 Turn 的 claim 与 consume 落到不同数据库。
     */
    void bind(TaskMailboxPort value) {
        Objects.requireNonNull(value, "value");
        TaskMailboxPort current = repository.get();
        if (current == value) return;
        if (!repository.compareAndSet(null, value)) {
            throw new IllegalStateException("task mailbox inbox is already bound");
        }
    }

    /**
     * 未绑定只允许既有纯 Loop 测试保持无 Task 基础设施；生产组合根启动前会显式绑定，
     * 已绑定后每次安全点都由 SQLite 重放同 Turn 的 BOUND 批次或 claim 新 PENDING 消息。
     */
    TaskMailboxPort.ClaimBatch claim(String threadId, String turnId, Instant occurredAt) {
        TaskMailboxPort owner = repository.get();
        return owner == null
                ? new TaskMailboxPort.ClaimBatch(List.of(), 0)
                : owner.claimPendingMessages(threadId, turnId, CLAIM_LIMIT, occurredAt);
    }
}
