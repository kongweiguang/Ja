// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.conversation.domain.model.ThreadProjection;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOwnerProjection;

import java.util.Optional;

/**
 * 读取 Ja 配置与 Turn 状态的组合投影；执行事实写入和恢复权威仍归 AgentStore。
 */
public interface ConversationProjectionRepository {
    /**
     * 读取同一一致性快照中的 Thread 配置和 Turn 状态；模型历史与客户端时间线仍由各自所有者提供。
     *
     * @param threadId 稳定的 Ja Thread 身份
     * @return Thread 存在时返回的只读投影
     */
    Optional<ThreadProjection> readThread(String threadId);

    /**
     * 通过唯一 Turn ID 定位其 Ja 所有者与同快照 CAS 版本，不枚举所有会话。
     */
    Optional<TurnOwnerProjection> readTurn(String turnId);
}
