// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.infrastructure.acl;

import io.github.kongweiguang.ja.conversation.application.gateway.ConversationProjectionRepository;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadProjection;
import io.github.kongweiguang.ja.conversation.domain.model.TurnProjection;
import io.github.kongweiguang.ja.plan.application.gateway.PlanConversationPort;
import java.util.Objects;

/**
 * 将 Conversation 投影和 Workspace owner 转换为 Plan 自有绑定值，避免 Plan 应用服务持有外域实体。
 */
public final class PlanConversationAcl implements PlanConversationPort {
    private final ConversationProjectionRepository conversations;

    /** 组合根注入对话投影，Workspace 绑定由独立 PlanWorkspaceAcl 负责。 */
    public PlanConversationAcl(ConversationProjectionRepository conversations) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
    }

    /** 只映射隐藏 Turn 所需字段，不把 ThreadProjection 继续向 Plan 内部传播。 */
    @Override
    public ThreadBinding readThread(String threadId) {
        ThreadProjection thread = conversations.readThread(Objects.requireNonNull(threadId, "threadId"))
                .orElseThrow(() -> new IllegalStateException("Plan owner Thread is unavailable"));
        return new ThreadBinding(thread.threadId(), thread.workspaceId(), thread.revision(),
                thread.preferences().providerId(), thread.preferences().modelId(),
                thread.preferences().reasoningLevel(), thread.preferences().accessMode().name(),
                thread.preferences().collaborationMode().name());
    }

    /** 只返回精确 Turn 的终态信息，缺失 Turn 由调用方按幂等已终态处理。 */
    @Override
    public TurnBinding readTurn(String threadId, String turnId) {
        ThreadProjection thread = conversations.readThread(Objects.requireNonNull(threadId, "threadId"))
                .orElseThrow(() -> new IllegalStateException("Plan owner Thread is unavailable"));
        TurnProjection turn = thread.turns().stream()
                .filter(candidate -> candidate.turnId().equals(Objects.requireNonNull(turnId, "turnId")))
                .findFirst().orElse(null);
        return turn == null ? null : new TurnBinding(turn.turnId(), turn.state());
    }
}
