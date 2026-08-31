// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.attachment.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 附件在受管存储中的脱敏投影；物理路径和 ingress identity 永远不属于领域值。
 */
public record AttachmentMetadata(String attachmentId, String workspaceId, String displayName,
                                 long sizeBytes, String sha256, MediaKind mediaKind,
                                 String mediaType, Status status, Instant createdAt,
                                 Instant expiresAt, String boundTurnId) {
    /**
     * 集中约束可持久化和可返回 UI 的字段，避免 Adapter 以路径或自由状态字符串补足缺失事实。
     */
    public AttachmentMetadata {
        attachmentId = identifier(attachmentId, "att_", "attachmentId");
        workspaceId = identifier(workspaceId, "ws_", "workspaceId");
        if (displayName == null || displayName.isBlank() || displayName.length() > 512
            || displayName.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid displayName");
        }
        if (sizeBytes < 0 || sizeBytes > 100L * 1024 * 1024
            || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid attachment content identity");
        }
        Objects.requireNonNull(mediaKind, "mediaKind");
        if (mediaType == null || mediaType.isBlank() || mediaType.length() > 128
            || !mediaType.matches("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*")) {
            throw new IllegalArgumentException("invalid mediaType");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (boundTurnId != null) boundTurnId = identifier(boundTurnId, "turn_", "boundTurnId");
        if ((status == Status.BOUND) != (boundTurnId != null)) {
            throw new IllegalArgumentException("attachment binding state is inconsistent");
        }
    }

    /** 附件内容分类只表达安全读取策略，不暗示 Provider 已支持该媒体。 */
    public enum MediaKind {
        /** 已完成全文件严格 UTF-8 校验且不包含 NUL 的文本。 */
        TEXT,
        /** 只由受支持图片 magic bytes 识别的静态图片。 */
        IMAGE,
        /** 只由 PDF 文件签名识别的文档。 */
        PDF,
        /** 无法安全证明为已知文本或媒体的任意字节。 */
        BINARY
    }

    /** 草稿只能单向进入绑定、丢弃或过期终态。 */
    public enum Status {
        /** 尚未绑定 Turn、可被显式丢弃且最多保留 24 小时。 */
        DRAFT,
        /** 已由 Turn admission 原子绑定，不能再丢弃或过期。 */
        BOUND,
        /** 用户在绑定前主动丢弃的终态。 */
        DISCARDED,
        /** 后台回收超过 24 小时草稿得到的终态。 */
        EXPIRED
    }

    /** 复用严格协议 identity 规则，但不在异常中回显调用方值。 */
    private static String identifier(String value, String prefix, String field) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }
}
