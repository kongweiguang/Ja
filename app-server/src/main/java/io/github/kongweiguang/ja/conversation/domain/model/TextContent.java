// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

/**
 * 模型消息中的非空文本内容块。
 */
public record TextContent(String text) implements UserContentBlock {
    /**
     * 在进入历史和 Provider 请求前限制文本体积并拒绝 NUL。
     */
    public TextContent {
        text = ContractChecks.text(text, "text", 4_000_000, false);
    }
}
