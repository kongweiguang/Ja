// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference;

/**
 * 图片导入的窄出站端口；组合根把受管附件 owner 接到会话工具，Tool 不取得物理存储访问权。
 */
@FunctionalInterface
public interface ManagedToolMediaImporter {
    /**
     * 内容在调用返回前完成复制和类型校验，结果仍是不可访问的附件草稿，待 ToolResult 同事务绑定。
     */
    ToolMediaReference importImage(String workspaceId, String displayName, String mediaType, byte[] content);
}
