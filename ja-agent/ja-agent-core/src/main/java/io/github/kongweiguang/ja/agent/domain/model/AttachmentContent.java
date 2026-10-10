// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.model;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

/**
 * 用户消息中的受管附件事实；只保存 opaque identity，不保存路径、摘要或伪造的文本提示。
 *
 * @param attachmentId 附件标识
 */
public record AttachmentContent(String attachmentId) implements UserContentBlock {
    /**
     * 附件以类型和宿主授权区分资源，不把 Ja 的命名前缀施加给其他业务接入方。
     */
    public AttachmentContent {
        attachmentId = ContractChecks.identifier(attachmentId, "attachmentId");
    }
}
