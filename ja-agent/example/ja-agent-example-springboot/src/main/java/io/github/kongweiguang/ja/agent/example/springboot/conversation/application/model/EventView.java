// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model;

import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;

import java.time.Instant;
import java.util.Objects;

/**
 * 保留 Core 版本化事件载荷，事件信封只暴露 Thread/Turn 身份。
 *
 * @param threadId 所属对话的稳定标识
 * @param turnId 本次可恢复执行的稳定标识
 * @param eventId 事件标识
 * @param eventType 事件类型
 * @param payloadSchemaVersion 负载数据结构版本
 * @param sequence 当前记录的顺序号
 * @param threadRevision 对话修订版本
 * @param turnMutationVersion 当前执行的状态变更版本
 * @param lastRecordSequence 最近记录的顺序号
 * @param payloadJson 负载 JSON 表示
 * @param occurredAt 事件发生时间
 */
public record EventView(String threadId,
                        String turnId,
                        String eventId,
                        String eventType,
                        int payloadSchemaVersion,
                        long sequence,
                        long threadRevision,
                        long turnMutationVersion,
                        long lastRecordSequence,
                        String payloadJson,
                        Instant occurredAt) {
    /**
     * HTTP 和 SSE 共用同一投影；由组合根注入 Gateway，避免应用投影绑定 Core 序列化实现。
     */
    public static EventView from(AgentStore.PersistedEvent event, JsonValueCodec payloadCodec) {
        JsonValueCodec codec = Objects.requireNonNull(payloadCodec, "payloadCodec");
        return new EventView(event.conversation().threadId(), event.execution().turnId(), event.eventId(),
                event.eventType(), event.payloadSchemaVersion(), event.sequence(), event.threadRevision(),
                event.mutationVersion(), event.lastRecordSequence(), codec.encode(event.payload()),
                event.occurredAt());
    }
}
