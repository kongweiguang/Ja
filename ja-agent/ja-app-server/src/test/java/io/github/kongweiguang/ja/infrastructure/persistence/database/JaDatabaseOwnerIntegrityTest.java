// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通过生产 SQLite datasource 验证 Tool、Plan Run、Turn claim 的归属组合约束。 */
final class JaDatabaseOwnerIntegrityTest {
    private static final String PLAN_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    @TempDir Path temp;

    /** Tool 的 thread_id 与 turn_id 必须指向同一个会话，不能拼接两个独立存在的父记录。 */
    @Test
    void rejectsToolCallWhoseThreadDiffersFromTurnOwner() throws Exception {
        Path databasePath = initializedDatabase("tool-owner");
        try (JaDatabase database = openDatabase(databasePath);
             Connection connection = database.dataSource().getConnection();
             Statement statement = connection.createStatement()) {
            assertForeignKeysEnabled(statement);
            seedWorkspace(statement);
            insertThread(statement, "thread_a", "DEFAULT");
            insertThread(statement, "thread_b", "DEFAULT");
            insertTurn(statement, "turn_b", "thread_b");

            assertEquals(1, number(statement,
                    "SELECT COUNT(*) FROM ja_conversation_thread WHERE thread_id='thread_a'"));
            assertEquals(1, number(statement,
                    "SELECT COUNT(*) FROM ja_conversation_turn WHERE turn_id='turn_b'"));
            assertThrows(SQLException.class, () -> insertToolCall(statement, "call_bad", "thread_a", "turn_b"));
            assertEquals(0, number(statement, "SELECT COUNT(*) FROM ja_tool_call WHERE call_id='call_bad'"));
        }
    }

    /** 有效 run、plan、revision 和同 owner Turn 仍须由本 run 先 claim 才能创建 attempt。 */
    @Test
    void rejectsPlanToolAttemptWithoutClaimForItsRun() throws Exception {
        Path databasePath = initializedDatabase("missing-claim");
        try (JaDatabase database = openDatabase(databasePath);
             Connection connection = database.dataSource().getConnection();
             Statement statement = connection.createStatement()) {
            assertForeignKeysEnabled(statement);
            seedWorkspace(statement);
            insertThread(statement, "thread_a", "PLAN");
            insertTurn(statement, "turn_a", "thread_a");
            seedPlanRun(statement, "thread_a", "plan_a", "revision_a", "run_a");
            insertToolCall(statement, "call_a", "thread_a", "turn_a");

            assertEquals(1, number(statement,
                    "SELECT COUNT(*) FROM ja_plan WHERE plan_id='plan_a'"));
            assertEquals(1, number(statement,
                    "SELECT COUNT(*) FROM ja_plan_revision WHERE plan_revision_id='revision_a'"));
            assertEquals(1, number(statement,
                    "SELECT COUNT(*) FROM ja_plan_execution WHERE run_id='run_a'"));
            assertEquals(0, number(statement,
                    "SELECT COUNT(*) FROM ja_plan_turn_claim WHERE run_id='run_a' AND turn_id='turn_a'"));
            assertThrows(SQLException.class, () -> insertPlanToolAttempt(
                    statement, "attempt_missing_claim", "plan_a", "run_a", "revision_a", "turn_a", "call_a"));
            assertEquals(0, number(statement,
                    "SELECT COUNT(*) FROM ja_plan_tool_attempt WHERE tool_attempt_id='attempt_missing_claim'"));
        }
    }

    /** 先建 thread_b 的真实 Turn，再种入错配 claim，使拒绝落在 ToolAttempt owner guard；这不是合法 Plan 准入。 */
    @Test
    void rejectsPlanToolAttemptClaimedByAnotherThread() throws Exception {
        Path databasePath = initializedDatabase("cross-thread-claim");
        try (JaDatabase database = openDatabase(databasePath);
             Connection connection = database.dataSource().getConnection();
             Statement statement = connection.createStatement()) {
            assertForeignKeysEnabled(statement);
            seedWorkspace(statement);
            insertThread(statement, "thread_a", "PLAN");
            insertThread(statement, "thread_b", "DEFAULT");
            seedPlanRun(statement, "thread_a", "plan_a", "revision_a", "run_a");
            insertTurn(statement, "turn_b", "thread_b");
            insertPlanTurnClaim(statement, "run_a", "turn_b");
            insertToolCall(statement, "call_b", "thread_b", "turn_b");

            assertEquals(1, number(statement, """
                    SELECT COUNT(*) FROM ja_plan_turn_claim
                    WHERE run_id='run_a' AND turn_id='turn_b' AND state='CLAIMED'
                    """));
            assertThrows(SQLException.class, () -> insertPlanToolAttempt(
                    statement, "attempt_cross_thread", "plan_a", "run_a", "revision_a", "turn_b", "call_b"));
            assertEquals(0, number(statement,
                    "SELECT COUNT(*) FROM ja_plan_tool_attempt WHERE tool_attempt_id='attempt_cross_thread'"));
        }
    }

    /** 同一 Plan owner Thread 的真实 Turn 被本 run claim 后，可提交合法的未启动 Tool attempt。 */
    @Test
    void acceptsPlanToolAttemptForClaimedOwnerTurn() throws Exception {
        Path databasePath = initializedDatabase("valid-claim");
        try (JaDatabase database = openDatabase(databasePath);
             Connection connection = database.dataSource().getConnection();
             Statement statement = connection.createStatement()) {
            assertForeignKeysEnabled(statement);
            seedWorkspace(statement);
            insertThread(statement, "thread_a", "PLAN");
            seedPlanRun(statement, "thread_a", "plan_a", "revision_a", "run_a");
            insertPlanTurnClaim(statement, "run_a", "turn_a");
            insertTurn(statement, "turn_a", "thread_a");
            insertToolCall(statement, "call_a", "thread_a", "turn_a");

            insertPlanToolAttempt(statement, "attempt_valid", "plan_a", "run_a", "revision_a", "turn_a", "call_a");
            assertEquals(1, number(statement,
                    "SELECT COUNT(*) FROM ja_plan_tool_attempt "
                            + "WHERE tool_attempt_id='attempt_valid' AND state='PREPARED'"));
        }
    }

    /** claim 先于 Turn admission 提交是合法生命周期，不应因 Turn 外键提前锁死准入顺序。 */
    @Test
    void allowsPlanTurnClaimBeforeTurnAdmission() throws Exception {
        Path databasePath = initializedDatabase("claim-before-turn");
        try (JaDatabase database = openDatabase(databasePath);
             Connection connection = database.dataSource().getConnection();
             Statement statement = connection.createStatement()) {
            assertForeignKeysEnabled(statement);
            seedWorkspace(statement);
            insertThread(statement, "thread_a", "PLAN");
            seedPlanRun(statement, "thread_a", "plan_a", "revision_a", "run_a");
            insertPlanTurnClaim(statement, "run_a", "turn_pending");

            assertEquals(0, number(statement,
                    "SELECT COUNT(*) FROM ja_conversation_turn WHERE turn_id='turn_pending'"));
            assertEquals(1, number(statement,
                    "SELECT COUNT(*) FROM ja_plan_turn_claim WHERE run_id='run_a' AND turn_id='turn_pending'"));

            insertTurn(statement, "turn_pending", "thread_a");
            assertEquals(1, number(statement,
                    "SELECT COUNT(*) FROM ja_conversation_turn WHERE turn_id='turn_pending' AND thread_id='thread_a'"));
        }
    }

    /** 每个测试使用私有目录，避免跨用例共享 SQLite 文件或持久化恢复状态。 */
    private Path initializedDatabase(String name) throws Exception {
        Path databasePath = temp.resolve(name).resolve("ja.db");
        Files.createDirectories(databasePath.getParent());
        try (JaDatabase ignored = openDatabase(databasePath)) {
            // 初始化必须经过正式 V1 路径，测试不手工创建 schema。
        }
        return databasePath;
    }

    /** 让 fixture 与产品使用相同的正式数据库配置。 */
    private static JaDatabase openDatabase(Path databasePath) {
        return JaDatabase.open(DatabaseConfig.of(databasePath, databasePath.getParent()));
    }

    /** 外键是 Tool Thread/Turn 组合拒绝的关键；测试必须确认使用了产品 datasource 配置。 */
    private static void assertForeignKeysEnabled(Statement statement) throws Exception {
        assertEquals(1, number(statement, "PRAGMA foreign_keys"));
    }

    /** 建立最小合法 Workspace，使后续 owner 失败只能来自待验证的身份组合。 */
    private static void seedWorkspace(Statement statement) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO ja_workspace(
                    workspace_id,root_path,kind,display_name,trust,revision,created_at_ms,updated_at_ms
                ) VALUES('workspace_a','/test/workspace','PROJECT','Workspace','TRUSTED',0,1,1)
                """);
    }

    /** 固定 owner、workspace 和协作模式，避免以缺失的对话父记录触发约束失败。 */
    private static void insertThread(Statement statement, String threadId, String mode) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO ja_conversation_thread(
                    thread_id,owner_user_id,workspace_id,title,created_at_ms,updated_at_ms,
                    provider_id,model_id,access_mode,collaboration_mode,title_source
                ) VALUES('%s','user_local','workspace_a','Thread',1,1,
                    'provider_test','model_test','FULL_ACCESS','%s','MANUAL')
                """.formatted(threadId, mode));
    }

    /** 创建处于活动态的真实 Turn，满足后续 Tool call 与 attempt 的独立外键。 */
    private static void insertTurn(Statement statement, String turnId, String threadId) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO ja_conversation_turn(turn_id,thread_id,state,requested_at_ms,updated_at_ms)
                VALUES('%s','%s','RUNNING',1,1)
                """.formatted(turnId, threadId));
    }

    /** 建立同一 Plan 的冻结 revision 与活动 run，供 owner trigger 和复合外键使用。 */
    private static void seedPlanRun(
            Statement statement, String ownerThreadId, String planId, String revisionId, String runId)
            throws SQLException {
        statement.executeUpdate("""
                INSERT INTO ja_plan(
                    plan_id,owner_thread_id,objective,create_operation_key,status,revision,created_at_ms,updated_at_ms
                ) VALUES('%s','%s','Objective','create_%s','DRAFT',0,1,1)
                """.formatted(planId, ownerThreadId, planId));
        statement.executeUpdate("""
                INSERT INTO ja_plan_revision(
                    plan_revision_id,plan_id,revision_number,definition_json,plan_hash,created_by,created_at_ms
                ) VALUES('%s','%s',1,'{}','%s','USER_UI',1)
                """.formatted(revisionId, planId, PLAN_HASH));
        statement.executeUpdate("""
                INSERT INTO ja_plan_execution(
                    run_id,plan_id,plan_revision_id,plan_hash,status,process_generation,created_at_ms,updated_at_ms
                ) VALUES('%s','%s','%s','%s','RUNNING',1,1,1)
                """.formatted(runId, planId, revisionId, PLAN_HASH));
        statement.executeUpdate("""
                UPDATE ja_plan SET status='EXECUTING',revision=1,
                    active_plan_revision_id='%s',active_run_id='%s',updated_at_ms=1
                WHERE plan_id='%s'
                """.formatted(revisionId, runId, planId));
    }

    /** 写入已持久化的 CLAIMED 事实；schema 有意不要求对应 Turn 已 admission。 */
    private static void insertPlanTurnClaim(Statement statement, String runId, String turnId) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO ja_plan_turn_claim(run_id,turn_id,ordinal,claimed_at_ms,state)
                VALUES('%s','%s',1,1,'CLAIMED')
                """.formatted(runId, turnId));
    }

    /** 插入满足 Turn 复合 owner 键的 Tool call，隔离 attempt 所有者断言。 */
    private static void insertToolCall(Statement statement, String callId, String threadId, String turnId)
            throws SQLException {
        statement.executeUpdate("""
                INSERT INTO ja_tool_call(
                    call_id,thread_id,turn_id,ordinal,tool_name,side_effect,presentation_json,
                    state,created_at_ms,updated_at_ms
                ) VALUES('%s','%s','%s',0,'test','READ_ONLY','{}','PREPARED',1,1)
                """.formatted(callId, threadId, turnId));
    }

    /** 用完整且有效的父身份尝试写入，失败必须来自 Run/Turn/Thread 归属关系。 */
    private static void insertPlanToolAttempt(
            Statement statement, String attemptId, String planId, String runId,
            String revisionId, String turnId, String callId) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO ja_plan_tool_attempt(
                    tool_attempt_id,plan_id,run_id,plan_revision_id,attempt,turn_id,call_id,
                    process_generation,side_effect,state,request_digest,prepared_at_ms
                ) VALUES('%s','%s','%s','%s',1,'%s','%s',1,0,'PREPARED',
                    '0000000000000000000000000000000000000000000000000000000000000000',1)
                """.formatted(attemptId, planId, runId, revisionId, turnId, callId));
    }

    /** 查询单值 SQL 并要求恰好一行，避免 fixture 断言静默忽略重复记录。 */
    private static int number(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            int value = rows.getInt(1);
            assertFalse(rows.next());
            return value;
        }
    }
}
