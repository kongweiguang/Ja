// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceWriteClaimPort;

/**
 * Conversation 消费方持有的写租约端口；基础 Workspace claim 合同只通过该窄入口进入 Loop。
 */
public interface ConversationWorkspaceWriteClaimPort extends WorkspaceWriteClaimPort {
}
