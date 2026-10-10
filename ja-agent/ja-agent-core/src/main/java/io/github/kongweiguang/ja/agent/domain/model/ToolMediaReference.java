// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.model;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

/**
 * 持久 Tool 结果引用受管图片；只保存授权句柄和展示元数据，不复制二进制正文。
 *
 * @param attachmentId 受管附件授权句柄
 * @param mediaType 经验证的图片 MIME 类型
 * @param displayName 用于展示的媒体名称
 * @param sizeBytes 图片正文的字节数
 */
public record ToolMediaReference(String attachmentId,
                                 String mediaType,
                                 String displayName,
                                 long sizeBytes) {
    /**
     * 限定为 Provider 能力可回传的图片引用，避免历史事实夹带不受支持的媒体或无界载荷。
     */
    public ToolMediaReference {
        attachmentId = ContractChecks.identifier(attachmentId, "attachmentId");
        mediaType = ContractChecks.text(mediaType, "mediaType", 128, false);
        displayName = ContractChecks.text(displayName, "displayName", 512, false);
        if (!mediaType.matches("image/(png|jpeg|gif|webp)")
            || sizeBytes < 1 || sizeBytes > 50L * 1024 * 1024) {
            throw new IllegalArgumentException("invalid tool image reference");
        }
    }
}
