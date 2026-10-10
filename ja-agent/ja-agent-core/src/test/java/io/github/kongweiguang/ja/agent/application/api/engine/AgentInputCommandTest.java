// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.api.engine;

import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 固定输入队列 API 与持久化 reducer 共用的宿主元数据上限。 */
final class AgentInputCommandTest {
    /**
     * 在调用方创建命令时拒绝 reducer 无法持久化的批次，避免把可同步校验延迟成异步失败。
     */
    @Test
    void validatesMetadataAgainstReducerBoundBeforeDispatch() {
        Map<String, String> metadata = new LinkedHashMap<>();
        for (int index = 0; index < 32; index++) {
            metadata.put("key_" + index, "value_" + index);
        }

        AgentInputCommand.Enqueue accepted = new AgentInputCommand.Enqueue("operation_a", "input_a",
                AgentInputCommand.Kind.MESSAGE, new UserContent(List.of(new TextContent("queued message"))),
                metadata);
        AgentInputQueue.Command reducerCommand = new AgentInputQueue.Command("command_a",
                AgentInputQueue.Action.ENQUEUE, accepted.inputId(), AgentInputQueue.Kind.MESSAGE, 0, 1,
                "message_a", null, Instant.parse("2026-09-28T00:00:00Z"), accepted.hostMetadata());
        assertEquals(32, reducerCommand.hostMetadata().size());

        Map<String, String> oversized = new LinkedHashMap<>(metadata);
        oversized.put("key_32", "value_32");
        assertThrows(IllegalArgumentException.class, () -> new AgentInputCommand.Enqueue("operation_b",
                "input_b", AgentInputCommand.Kind.MESSAGE,
                new UserContent(List.of(new TextContent("queued message"))), oversized));
    }
}
