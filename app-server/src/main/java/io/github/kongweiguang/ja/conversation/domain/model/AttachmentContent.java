// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

/**
 * 用户消息中的受管附件事实；只保存 opaque identity，不保存路径、摘要或伪造的文本提示。
 */
public record AttachmentContent(String attachmentId) implements UserContentBlock {
    /** 复用稳定 identity 约束，并额外固定附件前缀以拒绝其它资源 ID。 */
    public AttachmentContent {
        attachmentId = ContractChecks.identifier(attachmentId, "attachmentId");
        if (!attachmentId.startsWith("att_")) throw new IllegalArgumentException("invalid attachmentId");
    }
}
