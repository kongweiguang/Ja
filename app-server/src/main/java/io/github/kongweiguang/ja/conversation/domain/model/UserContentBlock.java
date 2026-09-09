// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

/**
 * 用户消息可持久化、可排队且可跨 JA-RPC 投影的唯一内容块闭集。
 *
 * <p>Skill 引用表达本条消息的执行上下文，Workspace 引用只表达路径线索；两者都不等价于
 * 已读取正文，避免 Renderer 选择一个 Chip 就隐式扩大 IO 或模型上下文。</p>
 */
public sealed interface UserContentBlock extends ModelContent permits AttachmentContent,
        SkillReferenceContent, TextContent, WorkspaceReferenceContent {
}
