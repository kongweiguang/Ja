// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.gateway.store;


import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;

import java.time.Instant;

/**
 * 应用用例依赖版本化事件协议，不反向依赖 Jackson 或某个数据库实现。
 */
public interface ExecutionEventCodec {
    /**
     * 将领域事件变成同事务写入的显式协议，提交顺序仍由 Store 分配。
     */
    AgentStore.EventWrite encode(String eventId, ExecutionEvent event, Instant occurredAt);

    /**
     * 按实际存储版本解析不可变事件，未知结构必须明确报错而非跳过。
     */
    ExecutionEvent decode(AgentStore.PersistedEvent event);
}

