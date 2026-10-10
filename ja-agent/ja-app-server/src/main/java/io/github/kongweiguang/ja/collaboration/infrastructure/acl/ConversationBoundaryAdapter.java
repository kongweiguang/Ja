// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.infrastructure.acl;

import io.github.kongweiguang.ja.collaboration.application.gateway.ConversationBoundary;
import io.github.kongweiguang.ja.collaboration.application.gateway.ConversationAcl;
import io.github.kongweiguang.ja.collaboration.domain.model.CollaborationPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;

/** Conversation 值转换的基础设施适配器，统一复用 ACL 的显式枚举映射。 */
public final class ConversationBoundaryAdapter implements ConversationBoundary {
    /** 将 Collaboration 偏好交给集中 ACL 转换。 */
    @Override
    public ThreadPreferences toConversation(CollaborationPreferences preferences) {
        return ConversationAcl.toConversation(preferences);
    }

    /** 将 Conversation 偏好复制回 Collaboration 自有值。 */
    @Override
    public CollaborationPreferences fromConversation(ThreadPreferences preferences) {
        return ConversationAcl.fromConversation(preferences);
    }
}
