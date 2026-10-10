// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.attachment.application;

import java.util.List;

/**
 * 模型声明与实际 Codec 原生附件闭集的交集，供上传、提交校验和能力页面共用。
 *
 * @param declaredFormats 模型声明允许输入的 MIME 类型
 * @param effectiveFormats 声明与 Provider Codec 同时支持的 MIME 类型
 * @param maxSingleBytes 单个附件可进入模型请求的最大字节数
 * @param maxTotalBytes 同一模型请求中附件合计的最大字节数
 * @param maxAttachments 单次用户输入可引用的附件数量上限
 */
public record AttachmentCapabilities(List<String> declaredFormats,
                                     List<String> effectiveFormats,
                                     long maxSingleBytes,
                                     long maxTotalBytes,
                                     int maxAttachments) {
    private static final int CORE_MAX_ATTACHMENTS = 10;

    /**
     * 冻结有序格式列表和 Core 上限，使所有 HTTP 路径使用同一份能力快照。
     */
    public AttachmentCapabilities {
        declaredFormats = List.copyOf(declaredFormats);
        effectiveFormats = List.copyOf(effectiveFormats);
        if (!declaredFormats.containsAll(effectiveFormats) || maxSingleBytes < 0 || maxTotalBytes < 0
                || maxAttachments < 1 || maxAttachments > CORE_MAX_ATTACHMENTS
                || effectiveFormats.isEmpty() != (maxSingleBytes == 0 || maxTotalBytes == 0)) {
            throw new IllegalArgumentException("invalid attachment capabilities");
        }
    }

    /**
     * 校验文件 MIME 和长度均落在配置与 Codec 双门的交集中。
     */
    public void requireSupported(String mediaType, long sizeBytes) {
        if (!effectiveFormats.contains(mediaType)) {
            throw new AttachmentException(AttachmentException.Reason.UNSUPPORTED);
        }
        if (sizeBytes < 1 || sizeBytes > maxSingleBytes) {
            throw new AttachmentException(AttachmentException.Reason.TOO_LARGE);
        }
    }
}
