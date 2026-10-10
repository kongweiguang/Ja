// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcCodec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定 Java 握手对运行时恢复与 Plan 的严格能力声明，防止三端合同静默降级。 */
final class HandshakeCapabilitiesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 直接断言生产能力投影的有序完整值，并固定存储恢复方法，避免客户端合同多于服务端能力。
     */
    @Test
    void advertisesPlanMethodsEventsModesAndFeatureInFrozenOrder() {
        ObjectNode capabilities = HandshakeHandler.capabilities(MAPPER);
        List<String> methods = MAPPER.convertValue(capabilities.path("methods"),
                MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));

        assertEquals(MAPPER.valueToTree(HandshakeHandler.METHODS), capabilities.path("methods"));
        assertEquals(MAPPER.valueToTree(HandshakeHandler.EVENTS), capabilities.path("events"));
        assertEquals(MAPPER.valueToTree(List.of("approval_required", "full_access")),
                capabilities.path("accessModes"));
        assertEquals(MAPPER.valueToTree(List.of("default", "plan")), capabilities.path("collaborationModes"));
        assertEquals(MAPPER.valueToTree(List.of("task_threads", "plan", "interaction")),
                capabilities.path("features"));
        assertFalse(methods.stream().anyMatch(method -> method.startsWith("goal/")));
        assertTrue(methods.contains("thread/mcp/read"));
        assertTrue(!methods.contains("thread/mcp/check"));
        assertTrue(methods.contains("runtime/storage/retry"));
        assertTrue(methods.contains("plan/draft/discard"));
        assertTrue(methods.contains("plan/reject"));
        assertTrue(methods.contains("plan/execute"));
        List<String> events = MAPPER.convertValue(capabilities.path("events"),
                MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
        assertEquals(List.of(
                "runtime/status-changed", "turn/state-changed", "turn/input-queue-changed", "turn/input-consumed",
                "turn/retry-started", "turn/messages_received", "assistant/model-step-committed",
                "assistant/context-estimated", "assistant/text-delta", "assistant/reasoning-summary-delta",
                "tool/started", "tool/batch-committed", "tool/recovery-changed", "approval/requested",
                "approval/resolved", "approval/closed", "context/compaction-started", "context/compacted",
                "context/compaction-failed", "workspace/dirty", "turn/terminal", "turn/resync-required",
                "thread/metadata-changed",
                "configuration/changed", "task/activity", "task/progress", "task/mailbox-changed",
                "task/closed",
                "interaction/changed", "plan/changed"), events);
        assertFalse(events.stream().anyMatch(event -> event.startsWith("goal/")));
        assertTrue(events.contains("interaction/changed"));
        assertTrue(events.contains("plan/changed"));
    }

    /** 握手宣告的恢复、审批关闭与重同步通知必须通过同一严格 JSON-RPC v2 decoder。 */
    @Test
    void acceptsAdvertisedRecoveryApprovalClosureAndResyncNotifications() throws Exception {
        JaRpcCodec codec = new JaRpcCodec();
        for (String method : List.of("tool/recovery-changed", "approval/closed", "turn/resync-required")) {
            ObjectNode frame = MAPPER.createObjectNode().put("jsonrpc", "2.0").put("method", method);
            frame.set("params", MAPPER.createObjectNode());

            JaRpcCodec.Frame decoded = codec.decode(MAPPER.writeValueAsBytes(frame));
            assertTrue(decoded instanceof JaRpcCodec.Notification);
            assertEquals(method, ((JaRpcCodec.Notification) decoded).method());
        }
    }
}
