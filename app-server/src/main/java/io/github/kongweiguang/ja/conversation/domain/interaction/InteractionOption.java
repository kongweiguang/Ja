// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.domain.interaction;

import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.util.Objects;

/** 单个稳定选项；optionId 是答案身份，label/description 仅用于展示。 */
public record InteractionOption(String optionId, String label, String description, boolean recommended) {
    /** 选项身份不能依赖可变显示文本，避免本地化或重绘后答案失配。 */
    public InteractionOption {
        optionId = ContractChecks.identifier(optionId, "optionId");
        if (!optionId.startsWith("option_")) throw new IllegalArgumentException("invalid optionId");
        label = text(label, "label", 512);
        description = text(description, "description", 2_000);
    }

    /** 展示字段保持有界且可直接渲染，身份校验由 optionId 规则单独负责。 */
    private static String text(String value, String field, int max) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > max || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }
}
