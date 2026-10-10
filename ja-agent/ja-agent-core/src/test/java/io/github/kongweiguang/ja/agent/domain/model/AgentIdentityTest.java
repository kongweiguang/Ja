// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.model;

import org.junit.jupiter.api.Test;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定 Core 工具幂等键的稳定性与完整执行身份隔离。 */
final class AgentIdentityTest {
    /** 相同身份和原生调用 ID 必须可恢复到同一键，不同调用或身份维度不得碰撞。 */
    @Test
    void derivesStableKeysFromTenantThreadTurnUserIdentityAndNativeCallId() {
        AgentIdentity identity = new AgentIdentity("tenant_a", "thread_a", "turn_a", "user_a");
        String callKey = identity.toolCallKey("call_01JABC");

        assertEquals(callKey, identity.toolCallKey("call_01JABC"));
        assertEquals(69, callKey.length());
        assertTrue(callKey.matches("call_[0-9a-f]{64}"));
        assertNotEquals(callKey, identity.toolCallKey("toolu_01JABC"));
        assertNotEquals(callKey, new AgentIdentity("tenant_b", "thread_a", "turn_a", "user_a")
                .toolCallKey("call_01JABC"));
        assertNotEquals(callKey, new AgentIdentity("tenant_a", "thread_b", "turn_a", "user_a")
                .toolCallKey("call_01JABC"));
        assertNotEquals(callKey, new AgentIdentity("tenant_a", "thread_a", "turn_b", "user_a")
                .toolCallKey("call_01JABC"));
        assertNotEquals(callKey, new AgentIdentity("tenant_a", "thread_a", "turn_a", "user_b")
                .toolCallKey("call_01JABC"));
    }
}
