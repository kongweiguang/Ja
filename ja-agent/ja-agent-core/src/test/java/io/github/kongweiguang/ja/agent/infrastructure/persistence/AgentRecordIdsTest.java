// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.persistence;




import io.github.kongweiguang.ja.agent.application.gateway.store.AgentRecordIds;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationKey;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ExecutionKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定持久记录 ID 的跨租户隔离、重试稳定性和数据库字段长度边界。 */
class AgentRecordIdsTest {
    /** 同一个事实跨迁移与重放应得到相同主键，其他身份维度变化必须产生不同主键。 */
    @Test
    void derivesDeterministicTenantScopedIdentifiers() {
        ExecutionKey key = execution("tenant-a", "thread-a", "turn-a");
        String id = AgentRecordIds.derive(key, "ToolResult", "call-a");

        assertEquals(id, AgentRecordIds.derive(key, "ToolResult", "call-a"));
        assertNotEquals(id, AgentRecordIds.derive(execution("tenant-b", "thread-a", "turn-a"),
                "ToolResult", "call-a"));
        assertNotEquals(id, AgentRecordIds.derive(execution("tenant-a", "thread-b", "turn-a"),
                "ToolResult", "call-a"));
        assertNotEquals(id, AgentRecordIds.derive(execution("tenant-a", "thread-a", "turn-b"),
                "ToolResult", "call-a"));
        assertNotEquals(id, AgentRecordIds.derive(key, "ToolMessage", "call-a"));
        assertNotEquals(id, AgentRecordIds.derive(key, "ToolResult", "call-b"));
    }

    /** 固定短 ID 满足 Store identifier 限制，并允许带修订号的稳定复合键。 */
    @Test
    void keepsDerivedIdsBoundedAndRejectsAmbiguousKeys() {
        ExecutionKey key = execution("tenant", "thread", "turn");
        String id = AgentRecordIds.derive(key, "RecoveryDecision", "c".repeat(256) + ":17");

        assertTrue(id.matches("rec_[0-9a-f]{64}"));
        assertEquals(68, id.length());
        assertThrows(IllegalArgumentException.class, () -> AgentRecordIds.derive(key, "RecoveryDecision", "\0"));
    }

    /** 每个测试都通过真实 Store 值对象校验 tenant、thread 与 turn 身份，不用裸字符串模拟键结构。 */
    private static ExecutionKey execution(String tenantId, String threadId, String turnId) {
        return new ExecutionKey(new ConversationKey(tenantId, threadId, "user"), turnId);
    }
}


