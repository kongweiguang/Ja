// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.api.engine;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;

import java.util.List;
import java.util.Objects;

/**
 * 单个执行的有界持久事件页，游标在所属会话内递增。
 *
 * @param events 事件列表
 * @param nextSequence 下一序号
 * @param hasMore 是否还有后续分页数据
 */
public record AgentEventPage(List<AgentStore.PersistedEvent> events,
                             long nextSequence,
                             boolean hasMore) {
    /**
     * 校验事件排序，并保留跨过其他执行事件后的 Store 游标。
     */
    public AgentEventPage {
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        if (nextSequence < 0) {
            throw new IllegalArgumentException("nextSequence must be non-negative");
        }
        long previous = 0;
        for (AgentStore.PersistedEvent event : events) {
            Objects.requireNonNull(event, "event");
            if (event.sequence() <= previous || event.sequence() > nextSequence) {
                throw new IllegalArgumentException("event sequences must be strictly ordered before the cursor");
            }
            previous = event.sequence();
        }
    }
}
