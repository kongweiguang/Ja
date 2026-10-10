// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.codec;

import io.github.kongweiguang.ja.conversation.domain.model.ReviewContext;

import java.util.Map;
import java.util.Optional;

/**
 * 将持久化的 Turn 准入属性还原成受限上下文事实；缺失表示普通 Turn，不从正文推导。
 */
public final class ReviewContextCodec {
    public static final String SNAPSHOT_ATTACHMENT_ID = "ja.review.snapshotAttachmentId";

    /**
     * 无状态属性映射不需要实例，避免不同 Turn 的审查身份被共享对象缓存。
     */
    private ReviewContextCodec() {
    }

    /**
     * 仅从 Store 回读的保留准入键恢复附件身份，并通过领域类型再次校验。
     */
    public static Optional<ReviewContext> decode(Map<String, String> attributes) {
        String attachmentId = Map.copyOf(attributes).get(SNAPSHOT_ATTACHMENT_ID);
        return attachmentId == null ? Optional.empty() : Optional.of(new ReviewContext(attachmentId));
    }
}
