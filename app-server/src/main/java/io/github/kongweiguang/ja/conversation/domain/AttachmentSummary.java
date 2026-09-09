// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

import java.util.Objects;

/**
 * USER Message 与排队输入共享的安全附件摘要；状态、Workspace、hash 和物理路径不得进入公开投影。
 */
public record AttachmentSummary(String attachmentId, String displayName, long sizeBytes,
                                String mediaKind, String mediaType) {
    /** 摘要使用严格固定字段，损坏的持久化元数据必须整体失败而不能生成可预览的伪事实。 */
    public AttachmentSummary {
        requireIdentifier(attachmentId);
        if (displayName == null || displayName.isBlank() || displayName.length() > 512
            || displayName.chars().anyMatch(Character::isISOControl) || sizeBytes < 0
            || sizeBytes > 100L * 1024 * 1024
            || !Objects.requireNonNull(mediaKind, "mediaKind").matches("text|image|pdf|binary")
            || mediaType == null || mediaType.isBlank() || mediaType.length() > 128) {
            throw new IllegalArgumentException("invalid attachment summary");
        }
    }

    /** 附件摘要复用 JA opaque identity 规则，但异常不回显不可信原值。 */
    private static void requireIdentifier(String value) {
        if (value == null || !value.startsWith("att_") || value.length() > 128
            || !value.substring("att_".length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid attachmentId");
        }
    }
}
