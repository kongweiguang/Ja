// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.acl;

import io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase;
import io.github.kongweiguang.ja.attachment.domain.model.AttachmentMetadata;
import io.github.kongweiguang.ja.conversation.application.gateway.ConversationAttachmentPort;
import io.github.kongweiguang.ja.conversation.domain.model.AttachmentSummary;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 将 Attachment owner 的 metadata-only 查询压缩为 Conversation 自有摘要，隔离附件实体与生命周期。
 */
public final class ConversationAttachmentAcl implements ConversationAttachmentPort {
    private final AttachmentUseCase attachments;

    /**
     * ACL 持有唯一附件入站端口，避免 Conversation 直接依赖附件应用服务。
     */
    public ConversationAttachmentAcl(AttachmentUseCase attachments) {
        this.attachments = Objects.requireNonNull(attachments, "attachments");
    }

    /**
     * 只映射受授权 metadata；不复制附件路径、blob 句柄或附件 owner 的异常模型。
     */
    @Override
    public List<AttachmentSummary> readAuthorizedSummaries(String threadId, List<String> attachmentIds) {
        return attachments.readAuthorizedMetadata(threadId, attachmentIds).stream()
                .map(ConversationAttachmentAcl::summary)
                .toList();
    }

    /**
     * 以 Conversation 的稳定摘要形状承接附件领域的媒体枚举。
     */
    private static AttachmentSummary summary(AttachmentMetadata metadata) {
        return new AttachmentSummary(metadata.attachmentId(), metadata.displayName(), metadata.sizeBytes(),
                metadata.mediaKind().name().toLowerCase(Locale.ROOT), metadata.mediaType());
    }
}
