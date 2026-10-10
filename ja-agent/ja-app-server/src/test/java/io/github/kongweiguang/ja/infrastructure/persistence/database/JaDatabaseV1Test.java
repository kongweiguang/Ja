// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import io.github.kongweiguang.ja.agent.domain.error.StorageException;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证唯一 V1 基线、fail-closed 恢复和数据库 owner 约束，不测试历史 schema 升级。 */
final class JaDatabaseV1Test {
    @TempDir Path temp;

    /** 空库建立含工具媒体 owner 的唯一 V1 与 Core schema；重开必须复用完整表集合。 */
    @Test
    void initializesCurrentV1BaselineAndReopensWithoutMigration() throws Exception {
        Path databasePath = temp.resolve("fresh").resolve("ja.db");
        try (JaDatabase ignored = openForTest(databasePath)) {
            // close 与生产一致，测试验证 checkpoint 完成后 lease 才释放。
        }

        assertCurrentSchema(databasePath);
        try (JaDatabase reopened = openForTest(databasePath)) {
            assertNull(reopened.recovery());
        }
        assertCurrentSchema(databasePath);
    }

    /** 未版本化的旧布局不能被识别成新库；阻断前后数据库字节必须完全相同。 */
    @Test
    void rejectsUnversionedPriorLayoutWithoutChangingOriginalBytes() throws Exception {
        Path databasePath = temp.resolve("old-layout").resolve("ja.db");
        Files.createDirectories(databasePath.getParent());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ja_conversation_thread(thread_id TEXT PRIMARY KEY, title TEXT)");
            statement.execute("INSERT INTO ja_conversation_thread VALUES('old-thread','keep')");
        }

        byte[] original = Files.readAllBytes(databasePath);
        assertBlocked(databasePath);
        assertArrayEquals(original, Files.readAllBytes(databasePath));
    }

    /** 未知表同样阻断首次初始化，不能在原文件旁创建可误认的空基线。 */
    @Test
    void rejectsUnknownSchemaWithoutChangingOriginalBytes() throws Exception {
        Path databasePath = temp.resolve("unknown").resolve("ja.db");
        Files.createDirectories(databasePath.getParent());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE unknown_state(value TEXT NOT NULL)");
            statement.execute("INSERT INTO unknown_state(value) VALUES('preserve')");
        }

        byte[] original = Files.readAllBytes(databasePath);
        assertBlocked(databasePath);
        assertArrayEquals(original, Files.readAllBytes(databasePath));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            assertEquals("preserve", text(statement, "SELECT value FROM unknown_state"));
            assertEquals(0, number(statement,
                    "SELECT COUNT(*) FROM sqlite_schema WHERE type='table' AND name='flyway_schema_history'"));
        }
    }

    /** 非 SQLite 输入保留逐字节原样，恢复器只记录阻断状态。 */
    @Test
    void rejectsCorruptDatabaseWithoutChangingBytes() throws Exception {
        Path databasePath = temp.resolve("corrupt").resolve("ja.db");
        Files.createDirectories(databasePath.getParent());
        byte[] corrupt = "not-a-sqlite-database".getBytes(StandardCharsets.UTF_8);
        Files.write(databasePath, corrupt);

        assertBlocked(databasePath);
        assertArrayEquals(corrupt, Files.readAllBytes(databasePath));
    }

    /** 未知的较新 migration history 不能触发降级、repair 或源文件替换。 */
    @Test
    void rejectsFutureHistoryWithoutChangingOriginalBytes() throws Exception {
        Path databasePath = initialized("future");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE flyway_schema_history SET version='99' WHERE version='1'");
        }

        byte[] original = Files.readAllBytes(databasePath);
        assertBlocked(databasePath);
        assertArrayEquals(original, Files.readAllBytes(databasePath));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            assertEquals("99", text(statement,
                    "SELECT version FROM flyway_schema_history WHERE version='99' AND success=1"));
        }
    }

    /** V1 checksum 漂移失败关闭，保留唯一原始 schema 供用户恢复。 */
    @Test
    void rejectsChecksumDriftWithoutChangingOriginalBytes() throws Exception {
        Path databasePath = initialized("checksum");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='1'");
        }

        byte[] original = Files.readAllBytes(databasePath);
        assertBlocked(databasePath);
        assertArrayEquals(original, Files.readAllBytes(databasePath));
    }

    /** 两条成功的 V1 history 不会被 Flyway 自动 repair 成看似可信的 schema。 */
    @Test
    void rejectsDuplicateSuccessfulV1HistoryWithoutRepair() throws Exception {
        Path databasePath = initialized("duplicate-history");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
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

        byte[] original = Files.readAllBytes(databasePath);
        assertBlocked(databasePath);
        assertArrayEquals(original, Files.readAllBytes(databasePath));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            assertEquals(2, number(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND success=1"));
        }
    }

    /** 未解决的失败 history 不会被清除，也不能让准入检查误报成功。 */
    @Test
    void rejectsFailedUnresolvedHistoryWithoutChangingOriginalBytes() throws Exception {
        Path databasePath = initialized("failed-history");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
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

        byte[] original = Files.readAllBytes(databasePath);
        assertBlocked(databasePath);
        assertArrayEquals(original, Files.readAllBytes(databasePath));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            assertEquals(1, number(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='1.1' AND success=0"));
        }
    }

    /** 正式 datasource 启用外键；非法 owner 写入失败且不会改变现有 thread 行。 */
    @Test
    void currentDatabaseRejectsInvalidWorkspaceOwnerWrites() throws Exception {
        Path databasePath = initialized("foreign-key");
        try (JaDatabase database = openForTest(databasePath);
             Connection connection = database.dataSource().getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO ja_conversation_thread(
                        thread_id,owner_user_id,workspace_id,title,created_at_ms,updated_at_ms,provider_id,model_id,
                        access_mode,collaboration_mode,title_source
                    ) VALUES('orphan','user_local','missing','orphan',0,0,'provider','model',
                        'APPROVAL_REQUIRED','DEFAULT','MANUAL')
                    """));
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            assertEquals(0, number(statement,
                    "SELECT COUNT(*) FROM ja_conversation_thread WHERE thread_id='orphan'"));
        }
    }

    /** 数据库拒绝跨 Thread/Plan/Run 错绑，Interaction 必须引用同一 turn 的 owner。 */
    @Test
    void rejectsInvalidOwnersAndPartialIssueTuplesAtDatabaseBoundary() throws Exception {
        Path databasePath = initialized("domain-constraints");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            seedPlanOwners(connection);
            insertToolCallFixture(statement);

            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO ja_turn_input(
                        input_id,thread_id,turn_id,kind,content_json,state,validation_status,
                        input_revision,created_at_ms,updated_at_ms
                    ) VALUES('input_wrong_owner','thread_2','turn_1','FOLLOW_UP','[]','PENDING',
                        'PENDING',1,0,0)
                    """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO ja_turn_input(
                        input_id,thread_id,turn_id,kind,content_json,state,validation_status,
                        issue_error_code,input_revision,created_at_ms,updated_at_ms
                    ) VALUES('input_partial_issue','thread_1','turn_1','FOLLOW_UP','[]','PENDING',
                        'PENDING','CONTENT_TOO_LARGE',1,0,0)
                    """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO ja_interaction_request(
                        interaction_id,thread_id,turn_id,tool_call_id,plan_revision_id,run_id,operation_key,
                        questions_json,answers_json,status,revision,created_at_ms,updated_at_ms
                    ) VALUES('interaction_wrong_owner','thread_2','turn_1','call_1','plan_revision_2','run_2',
                        'wrong-owner','[{"questionId":"question_one"}]','[]','PENDING',0,0,0)
                    """));

            insertToolAttemptFixture(statement);
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO ja_plan_tool_attempt(
                        tool_attempt_id,plan_id,run_id,plan_revision_id,attempt,turn_id,call_id,
                        process_generation,side_effect,state,request_digest,prepared_at_ms
                    ) VALUES('attempt_wrong_owner','plan_1','run_2','plan_revision_1',1,'turn_1','call_1',
                        1,0,'PREPARED','bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',0)
                    """));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE ja_plan_tool_attempt SET run_id='run_2' WHERE tool_attempt_id='attempt_valid'"));
        }
    }

    /** 注入失败后由正式 Flyway 在同一文件执行 V1，再验证 Ja 准入；准入本身不修复无恢复授权的库。 */
    @Test
    void rollsBackInterruptedV1AndAllowsRetry() throws Exception {
        Path databasePath = temp.resolve("retry").resolve("ja.db");
        Files.createDirectories(databasePath.getParent());
        SQLiteDataSource source = source(databasePath);
        String failingSql = migrationSql() + System.lineSeparator()
                + "CREATE TABLE interrupted_sentinel(value TEXT);" + System.lineSeparator()
                + "INSERT INTO missing_table(value) VALUES('fail');";
        Flyway failing = configured(source, new SingleMigrationProvider(failingSql));

        assertThrows(FlywayException.class, failing::migrate);
        try (Connection connection = source.getConnection(); java.sql.Statement statement = connection.createStatement()) {
            assertEquals(0, number(statement, """
                    SELECT COUNT(*) FROM sqlite_schema WHERE type='table'
                        AND name IN ('ja_workspace','interrupted_sentinel')
                    """));
        }

        JaDatabase.flyway(source).migrate();
        try (JaDatabase ignored = openForTest(databasePath)) {
            // 只有正式 migration 建立完整且校验通过的基线后，JaDatabase 才能准入并释放 lease。
        }
        assertCurrentSchema(databasePath);
    }

    /** Plan Tool 尝试可记录未派发失败；数据库不能伪造工具已经启动的事实。 */
    @Test
    void planToolAttemptsEnforceUnstartedTerminalBoundary() throws Exception {
        Path databasePath = initialized("plan-tool-attempt-boundary");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            seedPlanOwners(connection);
            insertToolAttemptFixture(statement);
            statement.executeUpdate("""
                    UPDATE ja_plan_tool_attempt SET state='FAILED_BEFORE_START',
                        result_digest='cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                        completed_at_ms=2 WHERE tool_attempt_id='attempt_valid'
                    """);
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    UPDATE ja_plan_tool_attempt SET started_at_ms=1
                    WHERE tool_attempt_id='attempt_valid'
                    """));
            assertEquals(1, number(statement, """
                    SELECT COUNT(*) FROM sqlite_schema WHERE type='trigger'
                        AND name='plan_tool_attempts_run_owner_insert'
                    """));
            assertEquals(1, number(statement, """
                    SELECT COUNT(*) FROM sqlite_schema WHERE type='trigger'
                        AND name='plan_tool_attempts_identity_update'
                    """));
        }
    }

    /** 只在测试库验证精确 V1 表集、唯一 V1、Core schema marker 与关键跨层约束。 */
    private static void assertCurrentSchema(Path databasePath) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             java.sql.Statement statement = connection.createStatement()) {
            assertEquals(JaDatabase.expectedTableNames(), tableNames(statement));
            assertEquals(1, number(statement, "SELECT COUNT(*) FROM flyway_schema_history"));
            assertEquals(1, number(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND success=1"));
            assertEquals(1, number(statement,
                    "SELECT schema_version FROM agent_schema_version WHERE singleton_id=1"));
            assertTrue(schemaSql(statement, "ja_conversation_turn").contains("'WAITING_INPUT'"));
            assertTrue(indexNames(statement).containsAll(Set.of("idx_ja_conversation_timeline_input_history",
                    "ix_ja_process_session_thread_started")));
            assertEquals("ok", text(statement, "PRAGMA integrity_check"));
            assertFalse(statement.executeQuery("PRAGMA foreign_key_check").next());
        }
    }

    /** 读取所有非 SQLite 内部表名，把 Flyway history 也纳入精确 V1 基线比较。 */
    private static Set<String> tableNames(java.sql.Statement statement) throws Exception {
        Set<String> names = new HashSet<>();
        try (java.sql.ResultSet rows = statement.executeQuery("""
                SELECT name FROM sqlite_schema WHERE type='table' AND name NOT LIKE 'sqlite_%'
                """)) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return names;
    }

    /** 从 schema catalog 读取 DDL，确保关键状态属于数据库约束而非 Java 常量。 */
    private static String schemaSql(java.sql.Statement statement, String table) throws Exception {
        return text(statement, "SELECT sql FROM sqlite_schema WHERE type='table' AND name='" + table + "'");
    }

    /** 仅统计显式索引，避免 SQLite 自动唯一索引掩盖关键查询路径缺失。 */
    private static Set<String> indexNames(java.sql.Statement statement) throws Exception {
        Set<String> names = new HashSet<>();
        try (java.sql.ResultSet rows = statement.executeQuery(
                "SELECT name FROM sqlite_schema WHERE type='index' AND sql IS NOT NULL")) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return names;
    }

    /** 创建两个独立 Plan owner 与冻结 revision/run，供约束测试识别跨 aggregate 错绑。 */
    private static void seedPlanOwners(Connection connection) throws Exception {
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            connection.setAutoCommit(false);
            statement.executeUpdate("""
                    INSERT INTO ja_workspace(workspace_id,root_path,kind,display_name,trust,created_at_ms,updated_at_ms)
                    VALUES('workspace_1','C:/workspace','PROJECT','Workspace','TRUSTED',0,0)
                    """);
            for (int ordinal = 1; ordinal <= 2; ordinal++) {
                String suffix = Integer.toString(ordinal);
                statement.executeUpdate("""
                        INSERT INTO ja_conversation_thread(
                            thread_id,owner_user_id,workspace_id,title,created_at_ms,updated_at_ms,
                            provider_id,model_id,access_mode,collaboration_mode,title_source
                        ) VALUES('thread_%s','user_local','workspace_1','Thread %s',0,0,
                            'provider','model','APPROVAL_REQUIRED','DEFAULT','MANUAL')
                        """.formatted(suffix, suffix));
                statement.executeUpdate("""
                        INSERT INTO ja_conversation_turn(turn_id,thread_id,state,requested_at_ms,updated_at_ms)
                        VALUES('turn_%s','thread_%s','RUNNING',0,0)
                        """.formatted(suffix, suffix));
                statement.executeUpdate("""
                        INSERT INTO ja_plan(
                            plan_id,owner_thread_id,objective,create_operation_key,status,revision,
                            created_at_ms,updated_at_ms
                        ) VALUES('plan_%s','thread_%s','Plan %s','create_plan_%s','DRAFT',0,0,0)
                        """.formatted(suffix, suffix, suffix, suffix));
                statement.executeUpdate("""
                        INSERT INTO ja_plan_revision(
                            plan_revision_id,plan_id,revision_number,definition_json,plan_hash,created_by,created_at_ms
                        ) VALUES('plan_revision_%s','plan_%s',1,'{}',
                            'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','AGENT',0)
                        """.formatted(suffix, suffix));
                statement.executeUpdate("""
                        INSERT INTO ja_plan_execution(
                            run_id,plan_id,plan_revision_id,plan_hash,status,process_generation,
                            started_at_ms,created_at_ms,updated_at_ms
                        ) VALUES('run_%s','plan_%s','plan_revision_%s',
                            'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','RUNNING',1,0,0,0)
                        """.formatted(suffix, suffix, suffix));
                statement.executeUpdate("""
                        UPDATE ja_plan SET status='EXECUTING',revision=1,
                            active_plan_revision_id='plan_revision_%s',active_run_id='run_%s',updated_at_ms=0
                        WHERE plan_id='plan_%s'
                        """.formatted(suffix, suffix, suffix));
            }
            connection.commit();
            connection.setAutoCommit(true);
        }
    }

    /** 插入合法 Tool Call；有些约束场景先建立它以证明失败来自 owner 身份。 */
    private static void insertToolCallFixture(java.sql.Statement statement) throws Exception {
        statement.executeUpdate("""
                INSERT OR IGNORE INTO ja_tool_call(
                    call_id,thread_id,turn_id,ordinal,tool_name,side_effect,presentation_json,
                    state,created_at_ms,updated_at_ms
                ) VALUES('call_1','thread_1','turn_1',0,'test','READ_ONLY','{}','PREPARED',0,0)
                """);
    }

    /** 先持久化对应 Run/Turn 的 CLAIMED owner，再写合法 Call 与未启动 attempt 供负向断言破坏。 */
    private static void insertToolAttemptFixture(java.sql.Statement statement) throws Exception {
        statement.executeUpdate("""
                INSERT INTO ja_plan_turn_claim(run_id,turn_id,ordinal,claimed_at_ms,state)
                VALUES('run_1','turn_1',1,1,'CLAIMED')
                """);
        insertToolCallFixture(statement);
        statement.executeUpdate("""
                INSERT INTO ja_plan_tool_attempt(
                    tool_attempt_id,plan_id,run_id,plan_revision_id,attempt,turn_id,call_id,
                    process_generation,side_effect,state,request_digest,prepared_at_ms
                ) VALUES('attempt_valid','plan_1','run_1','plan_revision_1',1,'turn_1','call_1',
                    1,0,'PREPARED','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',0)
                """);
    }

    /** 测试库 close 前绑定 checkpoint mapper，保持与生产关闭路径一致。 */
    private static JaDatabase openForTest(Path databasePath) {
        JaDatabase database = JaDatabase.open(DatabaseConfig.of(databasePath, databasePath.getParent()));
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration(
                new org.apache.ibatis.mapping.Environment("v1-test",
                        new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), database.dataSource()));
        configuration.addMapper(SchemaMapper.class);
        database.bindWalCheckpoint(new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration));
        return database;
    }

    /** 用于不需要绑定关闭依赖的恢复测试，数据库只会在 JUnit 临时目录内创建。 */
    private Path initialized(String name) {
        Path databasePath = temp.resolve(name).resolve("ja.db");
        try (JaDatabase ignored = openForTest(databasePath)) {
            // fixture 初始化必须走与业务启动相同的唯一 V1 准入路径。
        }
        return databasePath;
    }

    /** 任何失败打开都必须释放数据库 lease，避免阻断后续恢复或测试。 */
    private static void assertLeaseReleased(Path databasePath) {
        try (DatabaseLease ignored = DatabaseLease.acquire(databasePath)) {
            assertNotNull(ignored);
        }
    }

    /** 验证拒绝时保留源库原始字节，且不发布替代数据源或报告 ready。 */
    private static StorageRecovery.Result assertBlocked(Path databasePath) throws Exception {
        byte[] originalBytes = Files.readAllBytes(databasePath);
        DatabaseConfig config = DatabaseConfig.of(databasePath, databasePath.getParent());
        StorageException failure = assertThrows(StorageException.class, () -> JaDatabase.open(config));
        assertEquals(StorageException.Code.STORAGE_CONFLICT, failure.code());
        StorageRecovery.Result result = StorageRecovery.latest(config);
        assertNotNull(result);
        assertEquals("blocked", result.phase());
        assertFalse(result.rebuilt());
        assertFalse(result.backupAvailable());
        assertArrayEquals(originalBytes, Files.readAllBytes(databasePath));
        assertLeaseReleased(databasePath);
        return result;
    }

    /** 测试 datasource 复用生产外键与同步持久性设置，避免 SQL 语义分叉。 */
    private static SQLiteDataSource source(Path databasePath) {
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.enforceForeignKeys(true);
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqlite.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        SQLiteDataSource source = new SQLiteDataSource(sqlite);
        source.setUrl("jdbc:sqlite:" + databasePath);
        return source;
    }

    /** 失败注入只替换受控 V1 SQL 内容，其余 Flyway 配置与生产保持一致。 */
    private static Flyway configured(SQLiteDataSource source, ResourceProvider provider) {
        return Flyway.configure().dataSource(source).locations(new String[0]).resourceProvider(provider)
                .baselineOnMigrate(false).ignoreMigrationPatterns(new String[0])
                .validateMigrationNaming(true).load();
    }

    /** 读取唯一正式 migration 并追加确定失败语句，不复制可能变化的完整 DDL。 */
    private static String migrationSql() throws Exception {
        try (InputStream input = JaDatabaseV1Test.class.getClassLoader()
                .getResourceAsStream("db/migration/V1__kernel.sql")) {
            if (input == null) {
                throw new AssertionError("V1 migration resource is missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 单 migration provider 限定失败注入的资源身份，避免夹具放宽生产扫描边界。
     * @param sql 供隔离 migration provider 读取的 V1 内容
     */
    private record SingleMigrationProvider(String sql) implements ResourceProvider {
        /** 只解析 V1 classpath/relative 名称，防止 fixture 放宽生产资源扫描范围。 */
        @Override
        public LoadableResource getResource(String name) {
            return name != null && name.replace('\\', '/').endsWith("V1__kernel.sql")
                    ? new StringMigration(sql) : null;
        }

        /** 每次扫描只返回同一份 V1 内容，Flyway checksum 与执行读取保持一致。 */
        @Override
        public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
            return List.of(new StringMigration(sql));
        }
    }

    /** 内存 migration 提供稳定 V1 名称，不创建或修改工作区中的 SQL 文件。 */
    private static final class StringMigration extends LoadableResource {
        private final String sql;

        /** 冻结失败脚本，使 Flyway 两阶段读取得到相同内容。 */
        private StringMigration(String sql) {
            this.sql = sql;
        }

        /** 保持正式 migration 的文件名，history 断言仍针对唯一 V1。 */
        @Override public String getFilename() { return "V1__kernel.sql"; }

        /** 内存资源沿用稳定 classpath 身份，诊断不依赖当前目录。 */
        @Override public String getAbsolutePath() { return "classpath:test/V1__kernel.sql"; }

        /** 内存资源没有磁盘路径，使用相同的逻辑资源身份。 */
        @Override public String getAbsolutePathOnDisk() { return getAbsolutePath(); }

        /** Flyway parser 只需要唯一相对文件名。 */
        @Override public String getRelativePath() { return "V1__kernel.sql"; }

        /** 每次读取创建新 Reader，支持 checksum 与执行阶段独立消费。 */
        @Override public Reader read() {
            return new InputStreamReader(new java.io.ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8);
        }
    }

    /** 读取恰好一条文本结果，避免约束断言忽略额外行。 */
    private static String text(java.sql.Statement statement, String query) throws Exception {
        try (java.sql.ResultSet rows = statement.executeQuery(query)) {
            assertTrue(rows.next());
            String value = rows.getString(1);
            assertFalse(rows.next());
            return value;
        }
    }

    /** 读取恰好一条数值结果，共用 history、table 与 index 计数断言。 */
    private static int number(java.sql.Statement statement, String query) throws Exception {
        try (java.sql.ResultSet rows = statement.executeQuery(query)) {
            assertTrue(rows.next());
            int value = rows.getInt(1);
            assertFalse(rows.next());
            return value;
        }
    }
}
