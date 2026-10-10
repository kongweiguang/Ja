// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.conversation.domain.model.AttachmentSummary;

import java.util.List;

/**
 * Conversation 消费方定义的附件摘要端口；附件 owner 只返回当前 Thread 可见的脱敏结果。
 */
@FunctionalInterface
public interface ConversationAttachmentPort {
    /**
     * 读取队列消息所需的受授权摘要，不把附件实体、路径或 blob 能力带入 Conversation。
     *
     * @param threadId 当前会话
     * @param attachmentIds 队列中冻结的附件顺序
     * @return 与授权结果顺序一致的摘要
     */
    List<AttachmentSummary> readAuthorizedSummaries(String threadId, List<String> attachmentIds);
}
