// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

/**
 * 发送给模型并持久化的消息角色闭集。
 */
public enum ModelRole {
    /**
     * 用户输入消息。
     */
    USER,
    /**
     * 模型回复或 Tool 调用消息。
     */
    ASSISTANT,
    /**
     * Tool 执行结果消息。
     */
    TOOL
}
