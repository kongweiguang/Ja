// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.conversation.domain.model.TurnChangeSet;

/**
 * Ja 终态变更投影的窄序列化端口，由唯一持久 codec 实现。
 */
@FunctionalInterface
public interface TurnChangeSetSerialization {
    /**
     * 序列化 Core 可持久化的终态投影值，避免消费方依赖数据库 codec 所在层。
     */
    JsonObject writePayload(TurnChangeSet value);
}
