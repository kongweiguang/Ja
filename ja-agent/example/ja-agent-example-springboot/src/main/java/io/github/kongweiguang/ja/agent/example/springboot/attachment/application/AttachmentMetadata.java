// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.attachment.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 附件公开元数据不携带文件内容；哈希用于确认上传重试仍对应同一不可变文件。
 *
 * @param id 附件的稳定存储标识
 * @param threadId 附件所属的 Thread 标识
 * @param fileName 上传时冻结的展示文件名
 * @param mediaType 服务端识别并允许的媒体类型
 * @param sizeBytes 不可变文件内容的字节数
 * @param sha256 原始文件内容的 SHA-256 指纹
 * @param createdAt 附件草稿创建时间
 * @param referenced 是否已被持久化输入引用
 */
public record AttachmentMetadata(String id,
                                 String threadId,
                                 String fileName,
                                 String mediaType,
                                 long sizeBytes,
                                 String sha256,
                                 Instant createdAt,
                                 boolean referenced) {
    private static final Set<String> MEDIA_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf");

    /**
     * 限制投影字段至服务端已验证格式与稳定标识，避免持久化异常成为公开元数据。
     */
    public AttachmentMetadata {
        if (id == null || !id.matches("att_[A-Za-z0-9_.:-]{1,124}")
                || threadId == null || !threadId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
                || fileName == null || fileName.isBlank() || fileName.length() > 512
                || fileName.chars().anyMatch(Character::isISOControl)
                || !MEDIA_TYPES.contains(Objects.requireNonNull(mediaType, "mediaType"))
                || sizeBytes < 1 || sizeBytes > 50_000_000L
                || sha256 == null || !sha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("invalid attachment metadata");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
