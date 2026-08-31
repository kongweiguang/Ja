// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.util.Objects;

/**
 * 只存在于冻结 Provider 请求中的原生附件载荷；数据库仍只持久化 {@link AttachmentContent} identity。
 */
public record NativeAttachmentContent(String attachmentId, Kind kind, String displayName,
                                      String mediaType, long sizeBytes, String base64Data)
        implements ModelContent {
    /**
     * 载荷由受管附件读取端口生成，因此这里校验闭集、大小与 Base64 形状，避免任意字符串绕过 Codec 门。
     */
    public NativeAttachmentContent {
        attachmentId = ContractChecks.identifier(attachmentId, "attachmentId");
        if (!attachmentId.startsWith("att_")) throw new IllegalArgumentException("invalid attachmentId");
        Objects.requireNonNull(kind, "kind");
        displayName = ContractChecks.text(displayName, "displayName", 512, false);
        mediaType = ContractChecks.text(mediaType, "mediaType", 128, false);
        if (sizeBytes < 1 || sizeBytes > 50L * 1024 * 1024) {
            throw new IllegalArgumentException("invalid native attachment size");
        }
        validateBase64(base64Data, sizeBytes);
    }

    /**
     * 只允许 Provider 原生协议已实现的两类载荷；文本与其它二进制必须继续使用受控 Tool。
     */
    public enum Kind {
        /** 图片载荷只在模型模态与 Provider Codec 双门通过后进入请求。 */
        IMAGE,
        /** PDF 载荷保持独立类型，避免被图片或通用二进制路径误编码。 */
        PDF
    }

    /**
     * 使用长度、字母表和尾部填充验证而不再次解码大载荷，避免构造阶段产生同尺寸临时副本。
     */
    private static void validateBase64(String value, long sizeBytes) {
        Objects.requireNonNull(value, "base64Data");
        long expectedLength = 4L * ((sizeBytes + 2L) / 3L);
        if (value.length() != expectedLength) throw new IllegalArgumentException("invalid base64 length");
        int padding = sizeBytes % 3 == 0 ? 0 : (int) (3 - sizeBytes % 3);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean data = character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '+' || character == '/';
            boolean tailPadding = character == '=' && index >= value.length() - padding;
            if (!data && !tailPadding) throw new IllegalArgumentException("invalid base64 data");
        }
        for (int index = value.length() - padding; index < value.length(); index++) {
            if (value.charAt(index) != '=') throw new IllegalArgumentException("invalid base64 padding");
        }
    }
}
