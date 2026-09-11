// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SchemaMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.resource.LoadableResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Ja 0.1.0 只接受事务化 V1/V2/V3 以及严格的 Flyway/SQLite 启动准入。 */
final class JaDatabaseV1Test {
    private static final Set<String> DOMAIN_TABLES = Set.of(
            "acceptance_criteria", "acceptance_evidence", "approvals", "attachment_blobs", "attachments",
            "change_set_artifacts", "context_checkpoints", "execution_runs", "goal_acceptance_criteria",
            "goal_continuation_leases", "goal_definition_revisions", "goal_evaluations", "goal_events",
            "goal_plan_links", "goal_tool_attempts", "goals", "message_attachments",
            "messages", "pending_input_attachments", "pending_inputs", "plan_approvals", "plan_drafts",
            "plan_events", "plan_revisions", "plan_step_executions", "plan_steps", "plans",
            "task_activities", "task_context_seeds", "task_mailbox", "task_process_generation",
            "task_projections", "thread_instruction_scopes", "thread_lineage", "thread_title_generations",
            "threads", "temporary_side_chats", "timeline_messages", "tool_artifacts", "tool_bindings", "tools", "turn_change_sets",
            "thread_subagent_policies", "turn_execution", "turn_internal_context", "turns", "usage",
            "workspace_write_claims", "interaction_requests", "interaction_drafts", "interaction_events",
            "plan_turn_claims", "plan_evaluation_requests",
            "workspaces");

    @TempDir Path temp;

    /** 空库一次创建完整领域结构；再次启动只能验证同一 V3，不会产生第二条 history。 */
    @Test
    void initializesCompleteV2AndReopensWithoutMigration() throws Exception {
        Path databasePath = temp.resolve("fresh").resolve("ja.db");
        try (JaDatabase ignored = openForTest(databasePath)) {
            // 首次 close 同样走生产 WAL checkpoint，确保 lease 在完整生命周期后释放。
        }

        assertCurrentV2(databasePath);
        try (JaDatabase ignored = openForTest(databasePath)) {
            // 当前 V2 只做 checksum 和完整性验证。
        }
        assertCurrentV2(databasePath);
    }

    /** 未带 Flyway history 的非空 schema 明确拒绝，原表保留且失败后 lease 可重新获取。 */
    @Test
    void rejectsUnknownSchemaWithoutBaselineOrRepair() throws Exception {
        Path databasePath = temp.resolve("unknown").resolve("ja.db");
        Files.createDirectories(databasePath.getParent());
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE unknown_state(value TEXT NOT NULL)");
            statement.execute("INSERT INTO unknown_state(value) VALUES('preserve')");
        }

        StorageException failure = assertThrows(StorageException.class,
                () -> JaDatabase.open(DatabaseConfig.of(databasePath)));

        assertEquals(StorageException.Code.STORAGE_CONFLICT, failure.code());
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            assertEquals("preserve", text(statement, "SELECT value FROM unknown_state"));
            assertEquals(0, number(statement, "SELECT COUNT(*) FROM sqlite_schema "
                    + "WHERE type='table' AND name='flyway_schema_history'"));
        }
        assertLeaseReleased(databasePath);
    }

    /** 非 SQLite 内容失败关闭且字节不变，启动错误不能触发删库、quarantine 或 repair。 */
    @Test
    void rejectsCorruptDatabaseWithoutChangingBytes() throws Exception {
        Path databasePath = temp.resolve("corrupt").resolve("ja.db");
        Files.createDirectories(databasePath.getParent());
        byte[] corrupt = "not-a-sqlite-database".getBytes(StandardCharsets.UTF_8);
        Files.write(databasePath, corrupt);

        StorageException failure = assertThrows(StorageException.class,
                () -> JaDatabase.open(DatabaseConfig.of(databasePath)));

        assertEquals(StorageException.Code.STORAGE_CONFLICT, failure.code());
        assertArrayEquals(corrupt, Files.readAllBytes(databasePath));
        assertLeaseReleased(databasePath);
    }

    /** 默认会忽略的 future history 必须被显式拒绝，且 history 不得被修复或降级。 */
    @Test
    void rejectsFutureHistoryWithoutRepair() throws Exception {
        Path databasePath = initialized("future");
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE flyway_schema_history SET version='4',description='future' "
                    + "WHERE version='3'");
        }

        StorageException failure = assertThrows(StorageException.class,
                () -> JaDatabase.open(DatabaseConfig.of(databasePath)));

        assertEquals(StorageException.Code.STORAGE_CONFLICT, failure.code());
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            assertEquals("4", text(statement,
                    "SELECT version FROM flyway_schema_history WHERE version='4' AND success=1"));
        }
        assertLeaseReleased(databasePath);
    }

    /** V1 checksum 漂移只能由代码和明确数据决策解决，启动路径不得自动 repair history。 */
    @Test
    void rejectsChecksumDriftWithoutRepair() throws Exception {
        Path databasePath = initialized("checksum");
        int changedChecksum;
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='1'");
            changedChecksum = number(statement,
                    "SELECT checksum FROM flyway_schema_history WHERE version='1'");
        }

        StorageException failure = assertThrows(StorageException.class,
                () -> JaDatabase.open(DatabaseConfig.of(databasePath)));

        assertEquals(StorageException.Code.STORAGE_CONFLICT, failure.code());
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            assertEquals(changedChecksum, number(statement,
                    "SELECT checksum FROM flyway_schema_history WHERE version='1'"));
        }
        assertLeaseReleased(databasePath);
    }

    /** 同版本的第二条成功 history 也是未知数据库身份，不能因 current version 仍为 1 而放行。 */
    @Test
    void rejectsDuplicateSuccessfulV1HistoryWithoutRepair() throws Exception {
        Path databasePath = initialized("duplicate-history");
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO flyway_schema_history(
                        installed_rank,version,description,type,script,checksum,
                        installed_by,execution_time,success
                    )
                    SELECT (SELECT MAX(installed_rank) FROM flyway_schema_history)+1,
                        '1','duplicate V1','SQL','V1__duplicate.sql',checksum,
                        installed_by,0,1
                    FROM flyway_schema_history WHERE version='1' AND success=1
                    """);
        }

        StorageException failure = assertThrows(StorageException.class,
                () -> JaDatabase.open(DatabaseConfig.of(databasePath)));

        assertEquals(StorageException.Code.STORAGE_CONFLICT, failure.code());
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            assertEquals(4, number(statement, "SELECT COUNT(*) FROM flyway_schema_history"));
        }
        assertLeaseReleased(databasePath);
    }

    /** 未解决的失败 history 必须保留给诊断并拒绝启动，启动路径不能自动清除失败记录。 */
    @Test
    void rejectsFailedUnresolvedHistoryWithoutRepair() throws Exception {
        Path databasePath = initialized("failed-history");
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO flyway_schema_history(
                        installed_rank,version,description,type,script,checksum,
                        installed_by,execution_time,success
                    )
                    SELECT (SELECT MAX(installed_rank) FROM flyway_schema_history)+1,
                        '1.1','failed unresolved','SQL','V1_1__failed.sql',checksum+1,
                        installed_by,0,0
                    FROM flyway_schema_history WHERE version='1' AND success=1
                    """);
        }

        StorageException failure = assertThrows(StorageException.class,
                () -> JaDatabase.open(DatabaseConfig.of(databasePath)));

        assertEquals(StorageException.Code.STORAGE_CONFLICT, failure.code());
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            assertEquals(1, number(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='1.1' AND success=0"));
        }
        assertLeaseReleased(databasePath);
    }

    /** 可读取且 checksum 正确的数据库仍必须通过全库 FK 回读，违规行不会被启动路径修补。 */
    @Test
    void rejectsForeignKeyViolationWithoutChangingRows() throws Exception {
        Path databasePath = initialized("foreign-key");
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO threads(
                        thread_id,workspace_id,title,revision,created_at,updated_at,provider_id,model_id,
                        access_mode,collaboration_mode,title_source
                    ) VALUES(
                        'orphan','missing','orphan',0,'2026-09-07T00:00:00Z','2026-09-07T00:00:00Z',
                        'provider','model','APPROVAL_REQUIRED','DEFAULT','MANUAL'
                    )
                    """);
        }

        StorageException failure = assertThrows(StorageException.class,
                () -> JaDatabase.open(DatabaseConfig.of(databasePath)));

        assertEquals(StorageException.Code.STORAGE_CONFLICT, failure.code());
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            assertEquals(1, number(statement,
                    "SELECT COUNT(*) FROM threads WHERE thread_id='orphan'"));
        }
        assertLeaseReleased(databasePath);
    }

    /** 当前 schema 在绕过 Repository 时仍拒绝跨 Thread/Goal/Run 错绑和半写入诊断状态。 */
    @Test
    void rejectsInvalidOwnersAndPartialIssueTuplesAtDatabaseBoundary() throws Exception {
        Path databasePath = initialized("domain-constraints");
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            seedGoalOwners(connection);

            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO pending_inputs(
                        input_id,thread_id,turn_id,kind,content_json,state,validation_status,
                        input_revision,created_at,updated_at
                    ) VALUES('input_wrong_owner','thread_2','turn_1','FOLLOW_UP','[]','PENDING',
                        'PENDING',1,'2026-09-07T00:00:00Z','2026-09-07T00:00:00Z')
                    """));
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO pending_inputs(
                        input_id,thread_id,turn_id,kind,content_json,state,validation_status,
                        issue_error_code,input_revision,created_at,updated_at
                    ) VALUES('input_partial_issue','thread_1','turn_1','FOLLOW_UP','[]','PENDING',
                        'PENDING','CONTENT_TOO_LARGE',1,'2026-09-07T00:00:00Z','2026-09-07T00:00:00Z')
                    """));
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO interaction_requests(
                        request_id,thread_id,turn_id,tool_call_id,goal_id,run_id,idempotency_key,
                        questions_json,answers_json,status,revision,created_at,updated_at
                    ) VALUES('interaction_wrong_owner','thread_2','turn_1','call_1','goal_1','run_2','wrong-owner',
                        '[{"questionId":"question_one"}]','[]','PENDING',0,
                        '2026-09-07T00:00:00Z','2026-09-07T00:00:00Z')
                    """));

            insertToolAttemptFixture(statement);
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO goal_tool_attempts(
                        tool_attempt_id,goal_id,goal_definition_revision,run_id,attempt,turn_id,call_id,
                        process_generation,side_effect,state,request_digest,prepared_at
                    ) VALUES('attempt_wrong_owner','goal_1',1,'run_2',1,'turn_1','call_1',
                        1,0,'PREPARED','bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                        '2026-09-07T00:00:00Z')
                    """));
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                    "UPDATE goal_tool_attempts SET run_id='run_2' WHERE tool_attempt_id='attempt_valid'"));
        }
    }

    /** V1 中途失败必须回滚全部领域 DDL；同一文件随后可用正式 V3 完成初始化。 */
    @Test
    void rollsBackInterruptedV1AndAllowsRetry() throws Exception {
        Path databasePath = temp.resolve("retry").resolve("ja.db");
        Files.createDirectories(databasePath.getParent());
        SQLiteDataSource source = source(databasePath);
        String failingSql = migrationSql() + System.lineSeparator()
                + "CREATE TABLE interrupted_sentinel(value TEXT);"
                + System.lineSeparator() + "INSERT INTO missing_table(value) VALUES('fail');";
        Flyway failing = configured(source, new SingleMigrationProvider(failingSql));

        assertThrows(FlywayException.class, failing::migrate);
        try (java.sql.Connection connection = source.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            assertEquals(0, number(statement, "SELECT COUNT(*) FROM sqlite_schema WHERE type='table' "
                    + "AND name IN ('workspaces','interrupted_sentinel')"));
        }

        try (JaDatabase ignored = openForTest(databasePath)) {
            // 失败 migration 没有发布领域表或占住 lease，正式 V3 可以原位重试。
        }
        assertCurrentV2(databasePath);
    }

    /** 创建并完整关闭一个真实 V3，所有后续漂移测试都从同一生产路径出发。 */
    private Path initialized(String name) {
        Path databasePath = temp.resolve(name).resolve("ja.db");
        try (JaDatabase ignored = openForTest(databasePath)) {
            return databasePath;
        }
    }

    /** 生产 close 需要 named mapper factory；测试只注册 WAL checkpoint 所需的窄 mapper。 */
    private static JaDatabase openForTest(Path databasePath) {
        JaDatabase database = JaDatabase.open(DatabaseConfig.of(databasePath));
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration(
                new org.apache.ibatis.mapping.Environment("v1-test",
                        new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), database.dataSource()));
        configuration.addMapper(SchemaMapper.class);
        database.bindWalCheckpoint(new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration));
        return database;
    }

    /** 当前 schema 由 V1/V2/V3 成功 history、完整领域表集和格式约束共同定义。 */
    private static void assertCurrentV2(Path databasePath) throws Exception {
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            assertEquals(DOMAIN_TABLES, tableNames(statement));
            assertEquals(1, number(statement, "SELECT COUNT(*) FROM flyway_schema_history "
                    + "WHERE version='1' AND success=1"));
            assertEquals(1, number(statement, "SELECT COUNT(*) FROM flyway_schema_history "
                    + "WHERE version='2' AND success=1"));
            assertEquals(1, number(statement, "SELECT COUNT(*) FROM flyway_schema_history "
                    + "WHERE version='3' AND success=1"));
            assertEquals(3, number(statement, "SELECT COUNT(*) FROM flyway_schema_history"));
            assertEquals("ok", text(statement, "PRAGMA integrity_check"));
            assertFalse(statement.executeQuery("PRAGMA foreign_key_check").next());
            assertEquals(0, number(statement, "SELECT last_generation FROM task_process_generation "
                    + "WHERE singleton_id=1"));
            assertRequired(connection, "threads", "provider_id");
            assertRequired(connection, "threads", "model_id");
            assertRequired(connection, "threads", "access_mode");
            assertRequired(connection, "threads", "title_source");
            assertRequired(connection, "usage", "profile_json");
            assertRequired(connection, "interaction_requests", "thread_id");
            assertRequired(connection, "interaction_requests", "tool_call_id");
            assertFalse(columnNames(connection, "usage").contains("profile_origin"));
            Set<String> indexes = indexNames(statement);
            assertFalse(indexes.contains("idx_messages_thread_ordinal"));
            assertFalse(indexes.contains("idx_context_checkpoints_thread_source"));
            assertFalse(indexes.contains("idx_instruction_scopes_thread_directory"));
            String checkpoint = schemaSql(statement, "context_checkpoints");
            assertTrue(checkpoint.contains("strategy_version = 'ja-context-v1'"));
            String execution = schemaSql(statement, "turn_execution");
            assertTrue(execution.contains("schema_version=1"));
            assertTrue(execution.contains("'$.schemaVersion')=1"));
        }
    }

    /** 读取领域表名时排除 Flyway 与 SQLite 内部表，避免把框架实现混入业务结构断言。 */
    private static Set<String> tableNames(java.sql.Statement statement) throws Exception {
        Set<String> names = new HashSet<>();
        try (java.sql.ResultSet rows = statement.executeQuery("SELECT name FROM sqlite_schema "
                + "WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name<>'flyway_schema_history'")) {
            while (rows.next()) names.add(rows.getString(1));
        }
        return names;
    }

    /** 读取 SQLite 列元数据，拒绝以 Mapper 默认值代替数据库必填约束。 */
    private static void assertRequired(java.sql.Connection connection, String table, String column)
            throws Exception {
        try (java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                if (column.equals(rows.getString("name"))) {
                    assertTrue(rows.getBoolean("notnull"), table + "." + column + " must be required");
                    return;
                }
            }
        }
        throw new AssertionError("missing column " + table + "." + column);
    }

    /** 读取列集合只用于证明已删除的历史 Usage 维度没有留在当前 schema。 */
    private static Set<String> columnNames(java.sql.Connection connection, String table) throws Exception {
        Set<String> names = new HashSet<>();
        try (java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) names.add(rows.getString("name"));
        }
        return names;
    }

    /** 显式索引名只用于防止与 SQLite 自动唯一索引等价的历史结构重新进入当前 schema。 */
    private static Set<String> indexNames(java.sql.Statement statement) throws Exception {
        Set<String> names = new HashSet<>();
        try (java.sql.ResultSet rows = statement.executeQuery(
                "SELECT name FROM sqlite_schema WHERE type='index' AND sql IS NOT NULL")) {
            while (rows.next()) names.add(rows.getString(1));
        }
        return names;
    }

    /**
     * 创建两个独立 Goal owner 及各自 run，供约束测试区分合法 identity 与跨 aggregate 错绑。
     * 循环 FK 只在本地事务内延迟，提交后仍必须通过完整外键检查。
     */
    private static void seedGoalOwners(java.sql.Connection connection) throws Exception {
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            connection.setAutoCommit(false);
            statement.executeUpdate("""
                    INSERT INTO workspaces(workspace_id,root_path,display_name,trust,created_at,updated_at)
                    VALUES('workspace_1','C:/workspace','Workspace','TRUSTED',
                        '2026-09-07T00:00:00Z','2026-09-07T00:00:00Z')
                    """);
            for (int ordinal = 1; ordinal <= 2; ordinal++) {
                String suffix = Integer.toString(ordinal);
                statement.executeUpdate("""
                        INSERT INTO threads(
                            thread_id,workspace_id,title,created_at,updated_at,provider_id,model_id,
                            access_mode,collaboration_mode,title_source
                        ) VALUES('thread_%s','workspace_1','Thread %s','2026-09-07T00:00:00Z',
                            '2026-09-07T00:00:00Z','provider','model','APPROVAL_REQUIRED','DEFAULT','MANUAL')
                        """.formatted(suffix, suffix));
                statement.executeUpdate("""
                        INSERT INTO turns(turn_id,thread_id,state,requested_at,updated_at)
                        VALUES('turn_%s','thread_%s','RUNNING','2026-09-07T00:00:00Z',
                            '2026-09-07T00:00:00Z')
                        """.formatted(suffix, suffix));
                statement.executeUpdate("""
                        INSERT INTO goals(
                            goal_id,owner_thread_id,owner_kind,objective,goal_definition_revision,
                            create_idempotency_key,status,phase,revision,active_run_id,created_at,updated_at
                        ) VALUES('goal_%s','thread_%s','ROOT_THREAD','Goal %s',1,
                            'create_goal_%s','ACTIVE','WORKING',0,'run_%s',
                            '2026-09-07T00:00:00Z','2026-09-07T00:00:00Z')
                        """.formatted(suffix, suffix, suffix, suffix, suffix));
                statement.executeUpdate("""
                        INSERT INTO goal_definition_revisions(goal_id,revision_number,objective,created_at)
                        VALUES('goal_%s',1,'Goal %s','2026-09-07T00:00:00Z')
                        """.formatted(suffix, suffix));
                statement.executeUpdate("""
                        INSERT INTO execution_runs(
                            run_id,goal_id,goal_definition_revision,status,process_generation,
                            started_at,created_at,updated_at
                        ) VALUES('run_%s','goal_%s',1,'RUNNING',1,'2026-09-07T00:00:00Z',
                            '2026-09-07T00:00:00Z','2026-09-07T00:00:00Z')
                        """.formatted(suffix, suffix));
            }
            connection.commit();
            connection.setAutoCommit(true);
        }
    }

    /** 插入一条 owner 完整的 Tool attempt，后续测试只尝试篡改其冻结 identity。 */
    private static void insertToolAttemptFixture(java.sql.Statement statement) throws Exception {
        statement.executeUpdate("""
                INSERT INTO tools(
                    call_id,thread_id,turn_id,ordinal,tool_name,side_effect,presentation_json,
                    state,created_at,updated_at
                ) VALUES('call_1','thread_1','turn_1',0,'test','READ_ONLY','{}','PREPARED',
                    '2026-09-07T00:00:00Z','2026-09-07T00:00:00Z')
                """);
        statement.executeUpdate("""
                INSERT INTO goal_tool_attempts(
                    tool_attempt_id,goal_id,goal_definition_revision,run_id,attempt,turn_id,call_id,
                    process_generation,side_effect,state,request_digest,prepared_at
                ) VALUES('attempt_valid','goal_1',1,'run_1',1,'turn_1','call_1',
                    1,0,'PREPARED','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    '2026-09-07T00:00:00Z')
                """);
    }

    /** 读取 SQLite 保存的原始 DDL，验证格式身份由数据库约束而非 Java 常量单方面声明。 */
    private static String schemaSql(java.sql.Statement statement, String table) throws Exception {
        return text(statement, "SELECT sql FROM sqlite_schema WHERE type='table' AND name='" + table + "'");
    }

    /** 失败路径必须先释放同级 lock；该探针不会打开或修改 SQLite 文件。 */
    private static void assertLeaseReleased(Path databasePath) {
        try (DatabaseLease ignored = DatabaseLease.acquire(databasePath)) {
            assertNotNull(ignored);
        }
    }

    /** 测试专用 datasource 复用生产 durability/FK 语义，但不复制任何用户数据。 */
    private static SQLiteDataSource source(Path databasePath) {
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.enforceForeignKeys(true);
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqlite.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        SQLiteDataSource source = new SQLiteDataSource(sqlite);
        source.setUrl("jdbc:sqlite:" + databasePath);
        return source;
    }

    /** 测试失败脚本仍使用与生产相同的 Flyway 配置，差异仅限受控内存资源内容。 */
    private static Flyway configured(SQLiteDataSource source, ResourceProvider provider) {
        return Flyway.configure().dataSource(source).locations(new String[0]).resourceProvider(provider)
                .baselineOnMigrate(false).ignoreMigrationPatterns(new String[0])
                .validateMigrationNaming(true).load();
    }

    /** 读取正式 V1 并追加一个确定失败语句，避免测试复制整份 schema。 */
    private static String migrationSql() throws Exception {
        try (InputStream input = JaDatabaseV1Test.class.getClassLoader()
                .getResourceAsStream("db/migration/V1__kernel.sql")) {
            if (input == null) throw new AssertionError("V1 migration resource is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 单 migration provider 让失败注入仍经过真实 Flyway parser、transaction 和 history。 */
    private record SingleMigrationProvider(String sql) implements ResourceProvider {
        /** 只解析 V1 的 classpath/relative 名称，防止测试 provider 放宽生产发现语义。 */
        @Override
        public LoadableResource getResource(String name) {
            return name != null && name.replace('\\', '/').endsWith("V1__kernel.sql")
                    ? new StringMigration(sql) : null;
        }

        /** Flyway 扫描只能得到一条 V1，失败前后不存在隐藏修复 migration。 */
        @Override
        public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
            return List.of(new StringMigration(sql));
        }
    }

    /** 内存脚本保留稳定的 Flyway 资源身份，便于失败诊断但不写临时源文件。 */
    private static final class StringMigration extends LoadableResource {
        private final String sql;

        /** 冻结失败 SQL，Flyway 多次读取时返回相同 checksum 和内容。 */
        private StringMigration(String sql) {
            this.sql = sql;
        }

        /** 使用与正式资源相同的 V1 文件名。 */
        @Override public String getFilename() { return "V1__kernel.sql"; }

        /** classpath 身份避免测试依赖当前工作目录。 */
        @Override public String getAbsolutePath() { return "classpath:test/V1__kernel.sql"; }

        /** 内存资源没有磁盘路径，诊断沿用其稳定 classpath 身份。 */
        @Override public String getAbsolutePathOnDisk() { return getAbsolutePath(); }

        /** 返回 Flyway 可解析的相对文件名。 */
        @Override public String getRelativePath() { return "V1__kernel.sql"; }

        /** 每次读取都创建新 Reader，支持 Flyway checksum 与执行两个阶段。 */
        @Override public Reader read() { return new InputStreamReader(
                new java.io.ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8); }
    }

    /** 读取唯一文本标量，测试查询必须精确返回一行。 */
    private static String text(java.sql.Statement statement, String query) throws Exception {
        try (java.sql.ResultSet rows = statement.executeQuery(query)) {
            assertTrue(rows.next());
            String value = rows.getString(1);
            assertFalse(rows.next());
            return value;
        }
    }

    /** 读取唯一整数标量，避免 count/checksum 断言散落重复 JDBC 样板。 */
    private static int number(java.sql.Statement statement, String query) throws Exception {
        try (java.sql.ResultSet rows = statement.executeQuery(query)) {
            assertTrue(rows.next());
            int value = rows.getInt(1);
            assertFalse(rows.next());
            return value;
        }
    }
}
