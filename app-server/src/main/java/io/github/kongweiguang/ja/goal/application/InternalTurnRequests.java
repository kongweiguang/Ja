// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.conversation.port.in.InternalTurnStartRequest;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.workspace.domain.Workspace;

import java.time.Duration;
import java.time.Instant;

/** Goal 与 standalone Plan 共享的 hidden Turn 请求冻结规则。 */
final class InternalTurnRequests {
    private static final Duration DEADLINE = Duration.ofHours(24);

    /**
     * 只从同一次读取的 Thread/Workspace 快照构造请求，并显式保留 origin；调用方仍负责在 admission
     * 前校验各自的 run/revision/lease，避免本 helper 成为第二个状态 owner。
     */
    static InternalTurnStartRequest create(ConversationRepository.ThreadSnapshot thread, Workspace workspace,
                                           String turnId, Instant requestedAt, TurnOrigin origin) {
        return new InternalTurnStartRequest(thread.threadId(), turnId, workspace.workspaceId(), workspace.root(),
                thread.preferences().providerId(), thread.preferences().modelId(),
                thread.preferences().reasoningLevel(), thread.preferences().accessMode(),
                thread.preferences().collaborationMode(), DEADLINE, thread.revision(), 0, requestedAt, origin);
    }

    /** 纯构造规则不进入依赖注入容器。 */
    private InternalTurnRequests() { }
}
