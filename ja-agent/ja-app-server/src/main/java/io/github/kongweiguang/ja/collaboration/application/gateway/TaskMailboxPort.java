// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.application.gateway;

import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;

import java.util.List;
import java.util.Optional;

/**
 * 只读 Mailbox 查询端口；绑定与消费只能由 AgentStore 事务参与者完成。
 */
public interface TaskMailboxPort {
    /**
     * Core 队列和数据库邮箱共用同一有限批次上限；未读完的消息留在 PENDING。
     */
    int MAX_QUEUE_BATCH = 8;

    /**
     * 按发送方命名空间回读幂等事实，确保重试不会为同一消息生成新的投递身份。
     */
    Optional<TaskModels.MailboxMessage> findByIdempotency(String senderThreadId, String operationKey);

    /**
     * 读取目标 Thread 最早的有限待投递批次；未返回的消息继续保持 PENDING，不能在查询时 claim。
     */
    List<TaskModels.MailboxMessage> listPending(String targetThreadId, int limit);
}
