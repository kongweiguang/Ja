// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Mapper 参数和行形状保持独立、具名且可准确表达 SQL null。 */
final class PersistenceRecordsTest {
    /** nullable 列由组件类型直接表达，不再依赖 Map 是否包含键。 */
    @Test
    void preservesNullableColumnsExplicitly() {
        PersistenceRecords.ThreadRow row = new PersistenceRecords.ThreadRow(
                "thr", "ws", "title", "provider", "model", null, "approval_required",
                "plan", "placeholder", 0, "created", "updated", null, null, null, null, true, null);

        assertNull(row.reasoningLevel());
        assertNull(row.archivedAt());
        assertTrue(row.latestTurnSeen());
        assertEquals("plan", row.collaborationMode());
        assertEquals(0, row.revision());
    }

    /** 相同字段值仍由业务语义不同的参数 record 隔离，防止 Mapper 误接线。 */
    @Test
    void keepsParameterRolesDistinct() {
        PersistenceRecords.ToolKey tool = new PersistenceRecords.ToolKey("turn", "call");
        PersistenceRecords.ApprovalKey approval = new PersistenceRecords.ApprovalKey("turn", "call");

        assertEquals("call", tool.callId());
        assertEquals("call", approval.approvalId());
    }

    /** WAL 行按 PRAGMA 固定列构造，避免运行期再按字符串列名猜测。 */
    @Test
    void modelsWalCheckpointColumns() {
        PersistenceRecords.WalCheckpointRow row = new PersistenceRecords.WalCheckpointRow(0, 3, 3);

        assertEquals(0, row.busy());
        assertEquals(3, row.checkpointed());
    }
}
