// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.collaboration.application.api.TaskEvent;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 Task 关闭通知只携带已提交回执，并通过存活的 owner root 路由，避免客户端推断物理清理范围。
 */
final class TaskEventWireMapperTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 删除事件用存活 owner root 路由，并原样映射与 task/close ACK 相同的 receipt 集合。 */
    @Test
    void closedEventUsesCommittedReceiptAndSurvivingRootRoute() {
        TaskModels.SideChatCloseReceipt receipt = new TaskModels.SideChatCloseReceipt(
                "thr_side_root", "thr_top_root", List.of("thr_subagent", "thr_side_root"));
        TaskEventWireMapper.WireEvent event = new TaskEventWireMapper(MAPPER)
                .map(new TaskEvent.Closed(receipt));
        ObjectNode params = event.params();

        assertEquals("task/closed", event.method());
        assertEquals(3, params.size());
        assertEquals("thr_top_root", params.path("rootThreadId").asText());
        assertEquals("thr_side_root", params.path("taskThreadId").asText());
        assertEquals("[\"thr_side_root\",\"thr_subagent\"]",
                params.path("closedThreadIds").toString());
    }
}
