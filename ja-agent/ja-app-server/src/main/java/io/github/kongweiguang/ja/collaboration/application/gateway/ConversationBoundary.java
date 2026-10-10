// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.application.gateway;

import io.github.kongweiguang.ja.collaboration.domain.model.CollaborationPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;

/** Collaboration 使用的 Conversation 值边界，隔离外域枚举和模型生命周期。 */
public interface ConversationBoundary {
    /** 将 Collaboration 偏好转换为 Conversation admission 使用的值对象。 */
    ThreadPreferences toConversation(CollaborationPreferences preferences);

    /** 将 Conversation 偏好复制为 Collaboration 自有值对象。 */
    CollaborationPreferences fromConversation(ThreadPreferences preferences);
}
