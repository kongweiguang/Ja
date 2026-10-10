// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.engine;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentEventPage;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;

import java.util.Objects;

/**
 * 从权威租户事件日志分页读取，且不暴露其他执行的事件正文。
 */
public final class AgentEventReader {
    private final AgentStore store;

    /**
     * 使用宿主注入的 Store 分页，不依赖进程内事件缓存。
     */
    public AgentEventReader(AgentStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * 将一个有界会话事件页筛选为指定执行的事件，并保留全局会话游标。
     */
    public AgentEventPage read(AgentIdentity identity, long afterSequence, int limit) {
        Objects.requireNonNull(identity, "identity");
        if (afterSequence < 0 || limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("invalid event page request");
        }
        AgentStore.ConversationKey key = new AgentStore.ConversationKey(
                identity.tenantId(), identity.threadId(), identity.userId());
        AgentStore.EventPage page = store.readEvents(
                AgentStore.EventQuery.latestWithDefaultMaxBytes(key, afterSequence, limit));
        if (page.nextSequence() < afterSequence) {
            throw new IllegalStateException("store event cursor moved backwards");
        }
        long previous = afterSequence;
        for (AgentStore.PersistedEvent event : page.events()) {
            if (!event.conversation().equals(key)) {
                throw new IllegalStateException("store returned an event outside the requested conversation");
            }
            if (event.sequence() != previous + 1) {
                throw new IllegalStateException("store event sequence is not contiguous");
            }
            previous = event.sequence();
        }
        if (previous != page.nextSequence()) {
            throw new IllegalStateException("store event cursor does not match its page");
        }
        return new AgentEventPage(page.events().stream()
                .filter(event -> event.execution().turnId().equals(identity.turnId())).toList(),
                page.nextSequence(), page.hasMore());
    }
}
