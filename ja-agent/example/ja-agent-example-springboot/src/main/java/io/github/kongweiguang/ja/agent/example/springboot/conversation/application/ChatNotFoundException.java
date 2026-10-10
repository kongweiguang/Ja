// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.application;

/**
 * 服务层的不存在语义独立于 HTTP 状态码。
 */
public final class ChatNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * 消息只描述资源类型，不携带正文、SQL 或配置值。
     */
    public ChatNotFoundException(String message) {
        super(message);
    }
}
