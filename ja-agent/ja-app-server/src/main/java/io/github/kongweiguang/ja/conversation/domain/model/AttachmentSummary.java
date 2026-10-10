// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import java.util.Objects;

/**
 * USER 消息与排队输入共享的安全会话快照，不携带附件存储、Workspace 或授权状态。
 *
 * @param attachmentId 附件标识
 * @param displayName 面向用户的显示名称
 * @param sizeBytes 内容大小，单位为字节
 * @param mediaKind 媒体分类
 * @param mediaType 内容的 MIME 类型
 */
public record AttachmentSummary(String attachmentId,
                                String displayName,
                                long sizeBytes,
                                String mediaKind,
                                String mediaType) {
    /**
     * 严格固定摘要字段，损坏的历史数据必须整体失败，不能合成可预览的伪事实。
     */
    public AttachmentSummary {
        attachmentId = io.github.kongweiguang.ja.agent.domain.validation.ContractChecks.identifier(
                attachmentId, "attachmentId");
        if (displayName == null || displayName.isBlank() || displayName.length() > 512
            || displayName.chars().anyMatch(Character::isISOControl) || sizeBytes < 0
            || sizeBytes > 100L * 1024 * 1024
            || !Objects.requireNonNull(mediaKind, "mediaKind").matches("text|image|pdf|binary")
            || mediaType == null || mediaType.isBlank() || mediaType.length() > 128
            || !mediaType.matches("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*")) {
            throw new IllegalArgumentException("invalid attachment summary");
        }
    }
}
