// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

/**
 * 模型消息中允许持久化和跨 Provider 映射的内容块闭集。
 */
public sealed interface ModelContent permits NativeAttachmentContent, ReasoningContent, ToolCallContent,
        ToolResultContent, UserContentBlock {
}
