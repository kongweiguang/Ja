// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.api.engine;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证压缩内部身份使用共享摘要原语且仍隔离业务操作。 */
final class AgentCompactionRequestTest {
    /** 相同会话和操作必须重建相同内部 Turn，不同操作或租户不能复用恢复身份。 */
    @Test
    void derivesStableCompactionIdentity() {
        AgentIdentity source = new AgentIdentity("tenant_a", "thread_a", "turn_a", "user_a");

        AgentIdentity first = AgentCompactionRequest.compactionIdentity(source, "compact_1");
        AgentIdentity repeat = AgentCompactionRequest.compactionIdentity(source, "compact_1");
        AgentIdentity changedOperation = AgentCompactionRequest.compactionIdentity(source, "compact_2");
        AgentIdentity changedTenant = AgentCompactionRequest.compactionIdentity(
                new AgentIdentity("tenant_b", "thread_a", "turn_a", "user_a"), "compact_1");

        assertEquals(first, repeat);
        assertNotEquals(first.turnId(), changedOperation.turnId());
        assertNotEquals(first.turnId(), changedTenant.turnId());
        assertTrue(first.turnId().matches("compaction_[0-9a-f]{64}"));
    }
}
