// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import java.util.Objects;

/**
 * 绑定到受限 Turn 的冻结快照附件身份；该事实属于 App Server 的 Turn 准入，不依赖 Review 实现包。
 *
 * @param snapshotAttachmentId 绑定审查 Turn 的受管快照附件身份
 */
public record ReviewContext(String snapshotAttachmentId) {
    /**
     * 只接受受管附件身份，避免准入事实携带物理路径或任意外部引用。
     */
    public ReviewContext {
        snapshotAttachmentId = identifier(snapshotAttachmentId, "att_", "snapshotAttachmentId");
    }

    /**
     * 在准入前复用 Java 附件标识的稳定语法，错误不回显原始输入。
     */
    private static String identifier(String value, String prefix, String field) {
        Objects.requireNonNull(value, field);
        if (!value.startsWith(prefix) || value.length() > 128
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }
}
