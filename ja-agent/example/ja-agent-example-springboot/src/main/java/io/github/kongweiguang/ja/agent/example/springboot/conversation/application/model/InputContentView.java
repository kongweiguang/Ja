// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model;

import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentMetadata;

import java.util.List;

/**
 * 已持久化输入的读取视图，附带元数据但不物化附件正文。
 *
 * @param text 可选文本正文
 * @param skillIds 关联 Skill 标识
 * @param attachmentIds 关联附件标识
 * @param attachments 可预览附件元数据
 */
public record InputContentView(String text,
                               List<String> skillIds,
                               List<String> attachmentIds,
                               List<AttachmentMetadata> attachments) {
    /** 同批读取到的元数据与持久 ID 分离，调用方仍可发现数据不一致。 */
    public InputContentView {
        text = text == null ? "" : text;
        skillIds = List.copyOf(skillIds);
        attachmentIds = List.copyOf(attachmentIds);
        attachments = List.copyOf(attachments);
    }
}
