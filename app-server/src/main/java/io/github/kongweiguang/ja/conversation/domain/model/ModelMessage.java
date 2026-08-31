// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.util.List;
import java.util.Objects;

/**
 * Provider 中立且保持内容块顺序的领域消息。
 */
public record ModelMessage(ModelRole role, List<ModelContent> content) {
    /**
     * 冻结内容块并拒绝空消息，避免 Provider Adapter 为缺失语义自行补值。
     */
    public ModelMessage {
        Objects.requireNonNull(role, "role");
        content = ContractChecks.immutableList(content, "content");
        if (content.isEmpty()) throw new IllegalArgumentException("message content must not be empty");
    }
}
