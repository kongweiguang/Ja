// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定 Java 握手对 Plan/Goal 的严格能力声明，防止三端合同静默降级。 */
final class HandshakeCapabilitiesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 直接断言生产能力投影的有序完整值，因为 initialize 要求客户端逐项完全匹配而非集合包含。
     */
    @Test
    void advertisesPlanGoalMethodsEventsModesAndFeatureInFrozenOrder() {
        ObjectNode capabilities = HandshakeHandler.capabilities(MAPPER);
        List<String> methods = MAPPER.convertValue(capabilities.path("methods"),
                MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));

        assertEquals(MAPPER.valueToTree(HandshakeHandler.METHODS), capabilities.path("methods"));
        assertEquals(MAPPER.valueToTree(HandshakeHandler.EVENTS), capabilities.path("events"));
        assertEquals(MAPPER.valueToTree(List.of("approval_required", "full_access")),
                capabilities.path("accessModes"));
        assertEquals(MAPPER.valueToTree(List.of("default", "plan")), capabilities.path("collaborationModes"));
        assertEquals(MAPPER.valueToTree(List.of("task_threads_v1", "plan_goal_v1", "interaction_v1")),
                capabilities.path("features"));
        assertTrue(methods.contains("goal/read"));
        assertTrue(methods.contains("plan/draft/discard"));
        assertTrue(methods.contains("plan/reject"));
        assertTrue(methods.contains("plan/execute"));
        List<String> events = MAPPER.convertValue(capabilities.path("events"),
                MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
        assertTrue(events.contains("goal/changed"));
        assertTrue(events.contains("interaction/changed"));
        assertTrue(events.contains("plan/changed"));
    }
}
