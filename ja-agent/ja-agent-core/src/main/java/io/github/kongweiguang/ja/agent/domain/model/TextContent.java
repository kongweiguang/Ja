// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.model;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

/**
 * 模型消息中的非空文本内容块。
 *
 * @param text 文本内容
 */
public record TextContent(String text) implements UserContentBlock {
    /**
     * 拒绝协议无法保存的控制标记；单条回复长度由分页持久化承接。
     */
    public TextContent {
        text = ContractChecks.text(text, "text", Integer.MAX_VALUE, false);
    }
}
