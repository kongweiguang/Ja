// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLDataException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Mapper 参数和行形状保持独立、具名且可准确表达 SQL null。 */
final class PersistenceRecordsTest {
    /** nullable 列由组件类型直接表达，不再依赖 Map 是否包含键。 */
    @Test
    void preservesNullableColumnsExplicitly() {
        PersistenceRecords.ThreadRow row = new PersistenceRecords.ThreadRow("thr", "user_local", "ws", "title", "project", "provider", "model", null, "approval_required", "plan", "placeholder", 0, "created", "updated", null, null, null, null, true, "turn_latest");

        assertEquals("project", row.workspaceKind());
        assertNull(row.reasoningLevel());
        assertNull(row.archivedAt());
        assertTrue(row.latestTurnSeen());
        assertEquals("plan", row.collaborationMode());
        assertEquals(0, row.revision());
        assertEquals("turn_latest", row.latestTurnId());
    }

    /** 相同字段值仍由业务语义不同的参数 record 隔离，防止 Mapper 误接线。 */
    @Test
    void keepsParameterRolesDistinct() {
        PersistenceRecords.ToolKey tool = new PersistenceRecords.ToolKey("turn", "call");
        PersistenceRecords.ApprovalKey approval = new PersistenceRecords.ApprovalKey("turn", "call");

        assertEquals("call", tool.callId());
        assertEquals("call", approval.approvalId());
    }

    /** 直接验证 SQLite INTEGER 包装类型及 SQL NULL，防止 JDBC 隐式数值读取掩盖存储契约差异。 */
    @Test
    void readsSqliteIntegerStorageAndNullWithoutCoercion() throws Exception {
        EpochMillisStringTypeHandler handler = new EpochMillisStringTypeHandler();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            try (ResultSet row = statement.executeQuery("SELECT 1 AS value")) {
                assertTrue(row.next());
                assertInstanceOf(Integer.class, row.getObject(1));
                assertEquals("1970-01-01T00:00:00.001Z", handler.getNullableResult(row, 1));
            }
            try (ResultSet row = statement.executeQuery("SELECT 2147483648 AS value")) {
                assertTrue(row.next());
                assertInstanceOf(Long.class, row.getObject(1));
                assertEquals(java.time.Instant.ofEpochMilli(2_147_483_648L).toString(),
                        handler.getNullableResult(row, 1));
            }
            try (ResultSet row = statement.executeQuery("SELECT NULL AS value")) {
                assertTrue(row.next());
                assertNull(handler.getNullableResult(row, 1));
            }
        }
    }

    /** TEXT 与 REAL 即使看似可转为毫秒也必须拒绝，避免损坏时间静默变成有效时刻。 */
    @Test
    void rejectsSqliteTextAndRealStorageForEpochMillis() throws Exception {
        EpochMillisStringTypeHandler handler = new EpochMillisStringTypeHandler();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            for (String sql : new String[]{"SELECT CAST('42' AS TEXT) AS value", "SELECT CAST(42 AS REAL) AS value"}) {
                try (ResultSet row = statement.executeQuery(sql)) {
                    assertTrue(row.next());
                    SQLDataException failure = assertThrows(SQLDataException.class,
                            () -> handler.getNullableResult(row, 1));
                    assertEquals("22007", failure.getSQLState());
                    assertEquals("persisted UTC epoch-millis value is not an integer", failure.getMessage());
                }
            }
        }
    }

    /** WAL 行按 PRAGMA 固定列构造，避免运行期再按字符串列名猜测。 */
    @Test
    void modelsWalCheckpointColumns() {
        PersistenceRecords.WalCheckpointRow row = new PersistenceRecords.WalCheckpointRow(0, 3, 3);

        assertEquals(0, row.busy());
        assertEquals(3, row.checkpointed());
    }

    /** JSON 布尔字段类型损坏时必须失败关闭，避免静默丢失二进制或截断事实。 */
    @Test
    void rejectsMalformedChangeSetFlags() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode fileFlag = changeSetPayload(mapper);
        ((ObjectNode) fileFlag.withArray("files").get(0)).put("binary", "false");
        assertInvalidChangeSet(mapper, fileFlag);

        ObjectNode fileTruncated = changeSetPayload(mapper);
        ((ObjectNode) fileTruncated.withArray("files").get(0)).put("truncated", "false");
        assertInvalidChangeSet(mapper, fileTruncated);

        ObjectNode statsTruncated = changeSetPayload(mapper);
        statsTruncated.with("stats").put("truncated", 0);
        assertInvalidChangeSet(mapper, statsTruncated);
    }

    /** 数值字段拒绝小数，避免 longValue 截断后让损坏的数量与文件汇总伪装一致。 */
    @Test
    void rejectsFractionalChangeSetCounts() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = changeSetPayload(mapper);
        root.with("stats").put("additions", 1.5);

        assertInvalidChangeSet(mapper, root);
    }

    /** 构造符合领域约束的最小事实，便于每个损坏用例只改变一个持久字段。 */
    private static ObjectNode changeSetPayload(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode().put("state", "complete");
        root.putArray("incompleteReasons");
        root.putArray("files").addObject()
                .put("path", "src/Main.java")
                .put("status", "modified")
                .put("additions", 1)
                .put("deletions", 0)
                .put("binary", false)
                .put("truncated", false);
        root.putObject("stats")
                .put("files", 1)
                .put("additions", 1)
                .put("deletions", 0)
                .put("binaryFiles", 0)
                .put("truncated", false);
        return root;
    }

    /** 解码损坏事实必须保留稳定 INVALID_STATE 分类，不允许 Jackson coercion 吞掉问题。 */
    private static void assertInvalidChangeSet(ObjectMapper mapper, ObjectNode payload) {
        TurnChangeSetCodec codec = new TurnChangeSetCodec(mapper);
        StorageException failure = assertThrows(StorageException.class, () -> codec.read(payload.toString()));
        assertEquals(StorageException.Code.INVALID_STATE, failure.code());
    }
}
