// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.tools;

import io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference;
import io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase;
import io.github.kongweiguang.ja.attachment.domain.model.AttachmentMetadata;
import io.github.kongweiguang.ja.conversation.application.gateway.ManagedToolMediaImporter;

import java.util.Objects;

/**
 * 将模型可见的图片引用绑定到附件模块所有的草稿存储，不把物理存储能力传给 Tool。
 */
public final class AttachmentManagedToolMediaImporter implements ManagedToolMediaImporter {
    private final AttachmentUseCase attachments;

    /**
     * 适配器只持有附件应用端口；Workspace 与 Thread 授权仍由 Tool 执行上下文及提交事务负责。
     */
    public AttachmentManagedToolMediaImporter(AttachmentUseCase attachments) {
        this.attachments = Objects.requireNonNull(attachments, "attachments");
    }

    /**
     * 通过附件 owner 校验并保存图片，随后只投影其受管身份供 ToolResult 事务绑定。
     */
    @Override
    public ToolMediaReference importImage(String workspaceId, String displayName,
                                          String mediaType, byte[] content) {
        byte[] snapshot = Objects.requireNonNull(content, "content").clone();
        AttachmentMetadata imported = Objects.requireNonNull(
                attachments.importMcpImage(workspaceId, displayName, mediaType, snapshot), "imported attachment");
        if (!workspaceId.equals(imported.workspaceId())
            || !displayName.equals(imported.displayName())
            || !mediaType.equals(imported.mediaType())
            || snapshot.length != imported.sizeBytes()
            || imported.mediaKind() != AttachmentMetadata.MediaKind.IMAGE
            || imported.status() != AttachmentMetadata.Status.DRAFT) {
            throw new IllegalStateException("managed image import returned inconsistent metadata");
        }
        return new ToolMediaReference(imported.attachmentId(), imported.mediaType(), imported.displayName(),
                imported.sizeBytes());
    }
}
