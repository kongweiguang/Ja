// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.infrastructure.acl;

import io.github.kongweiguang.ja.collaboration.application.gateway.TaskMailboxQueueCommandMapper;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.conversation.application.gateway.PendingQueueInputSource;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskMailboxPort;

import java.util.List;
import java.util.Objects;

/**
 * 为普通 USER admission 读取有限待投递 Mailbox，不执行 claim 或提前改变任何持久状态。
 */
public final class TaskMailboxQueueInputSource implements PendingQueueInputSource {
    private final TaskMailboxPort mailboxes;
    private final JsonValueCodec json;

    /**
     * 只依赖只读 Mailbox 端口，使 admission 事务仍是唯一绑定与恢复边界。
     */
    public TaskMailboxQueueInputSource(TaskMailboxPort mailboxes, JsonValueCodec json) {
        this.mailboxes = Objects.requireNonNull(mailboxes, "mailboxes");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 按请求容量读取最早 PENDING 消息；剩余消息保持原状态，待下次 USER admission 接纳。
     */
    @Override
    public List<AgentInputCommand.Enqueue> loadPendingInputs(String threadId, int limit) {
        if (threadId == null || !threadId.startsWith("thr_") || limit < 1) {
            throw new IllegalArgumentException("invalid pending mailbox query");
        }
        int boundedLimit = Math.min(limit, TaskMailboxPort.MAX_QUEUE_BATCH);
        List<TaskModels.MailboxMessage> pending = List.copyOf(
                Objects.requireNonNull(mailboxes.listPending(threadId, boundedLimit), "pending mailbox rows"));
        if (pending.size() > boundedLimit) {
            throw new IllegalStateException("Mailbox source exceeded the requested admission limit");
        }
        return pending.stream().map(message -> {
            if (!threadId.equals(message.targetThreadId())) {
                throw new IllegalStateException("Mailbox source returned a message for another Thread");
            }
            return TaskMailboxQueueCommandMapper.toQueueCommand(message, json);
        }).toList();
    }
}


