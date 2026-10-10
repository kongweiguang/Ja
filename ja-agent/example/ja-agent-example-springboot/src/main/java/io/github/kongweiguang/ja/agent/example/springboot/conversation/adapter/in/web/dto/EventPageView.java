// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web.dto;

import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.EventView;

import io.github.kongweiguang.ja.agent.application.api.engine.AgentEventPage;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;

import java.util.List;

/**
 * 分页响应保留权威水位，不能由事件数量推算下一页。
 *
 * @param events 事件列表
 * @param nextSequence 下一序号
 * @param hasMore 是否还有后续分页数据
 */
public record EventPageView(List<EventView> events,
                            long nextSequence,
                            boolean hasMore) {
    /**
     * 响应集合不可变，异步发送期间不允许修改。
     */
    public EventPageView {
        events = List.copyOf(events);
    }

    /**
     * 事件复用 HTTP/SSE 共用的 JSON Gateway，空页也保留 Store 给出的权威水位。
     */
    public static EventPageView from(AgentEventPage page, JsonValueCodec payloadCodec) {
        return new EventPageView(page.events().stream()
                .map(event -> EventView.from(event, payloadCodec)).toList(), page.nextSequence(), page.hasMore());
    }
}
