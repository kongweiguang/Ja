// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.resource.LoadableResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** 验证全新建库与真实历史数据都只能通过单向 V2/V3/V4/V5/V6 迁移到达最终结构。 */
final class FreshBaselineMigrationTest {
    @TempDir Path temp;

    /** 全新数据库顺序执行 V1 至 V6，并建立最终 runtime、安全时间线与七档思考结构。 */
    @Test
    void createsFinalSelectorsFromFreshBaseline() throws Exception {
        Path database = temp.resolve("fresh.sqlite3");
        SQLiteDataSource source = source(database);
        flyway(source).migrate();

        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            assertFinalColumns(connection, "threads", Set.of(
                    "provider_id", "model_id", "reasoning_level", "access_mode", "title_source"));
            assertFinalColumns(connection, "turns", Set.of(
                    "provider_id", "model_id", "provider", "api", "upstream_model",
                    "reasoning_level", "access_mode", "config_generation"));
            assertNullableColumn(connection, "threads", "reasoning_level");
            assertNullableColumn(connection, "turns", "reasoning_level");
            assertFalse(columnNames(connection, "threads").contains("profile_id"));
            Set<String> turnColumns = columnNames(connection, "turns");
            assertFalse(turnColumns.contains("profile_id"));
            assertFalse(turnColumns.contains("config_revision"));
            assertTrue(tableNames(connection).containsAll(Set.of(
                    "attachment_blobs", "attachments", "turn_attachments", "thread_title_generations")));
            try (java.sql.Statement statement = connection.createStatement();
                 java.sql.ResultSet rows = statement.executeQuery(
                         "SELECT version FROM flyway_schema_history ORDER BY installed_rank")) {
                assertTrue(rows.next());
                assertTrue("1".equals(rows.getString(1)));
                assertTrue(rows.next());
                assertTrue("2".equals(rows.getString(1)));
                assertTrue(rows.next());
                assertTrue("3".equals(rows.getString(1)));
                assertTrue(rows.next());
                assertTrue("4".equals(rows.getString(1)));
                assertTrue(rows.next());
                assertTrue("5".equals(rows.getString(1)));
                assertTrue(rows.next());
                assertTrue("6".equals(rows.getString(1)));
                assertFalse(rows.next());
            }
        }
    }

    /** V6 保留旧三档历史值，并允许持久化全部逻辑档位但继续拒绝协议外值。 */
    @Test
    void migratesAndEnforcesAllReasoningLevels() throws Exception {
        Path database = temp.resolve("reasoning-levels.sqlite3");
        SQLiteDataSource source = source(database);
        flywayV5(source).migrate();
        String now = Instant.parse("2026-08-30T00:00:00Z").toString();
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO workspaces VALUES "
                    + "('ws_reasoning','C:/reasoning','Reasoning','TRUSTED',0,'" + now + "','" + now + "')");
            statement.executeUpdate("INSERT INTO threads(thread_id,workspace_id,title,revision,created_at,updated_at,"
                    + "provider_id,model_id,reasoning_effort,access_mode,title_source) VALUES "
                    + "('thr_reasoning','ws_reasoning','Reasoning',0,'" + now + "','" + now
                    + "','provider_reasoning','model_reasoning','high','APPROVAL_REQUIRED','USER')");
            statement.executeUpdate("INSERT INTO turns(turn_id,thread_id,state,config_generation,mutation_version,"
                    + "requested_at,updated_at,provider_id,model_id,provider,api,upstream_model,reasoning_effort,access_mode) "
                    + "VALUES ('turn_reasoning','thr_reasoning','QUEUED','cfg_reasoning',0,'" + now + "','" + now
                    + "','provider_reasoning','model_reasoning','openai','openai_responses','gpt-reasoning','high',"
                    + "'APPROVAL_REQUIRED')");
        }

        flyway(source).migrate();
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             java.sql.Statement statement = connection.createStatement()) {
            assertEquals("high", textScalar(statement,
                    "SELECT reasoning_level FROM threads WHERE thread_id='thr_reasoning'"));
            assertEquals("high", textScalar(statement,
                    "SELECT reasoning_level FROM turns WHERE turn_id='turn_reasoning'"));
            for (String level : java.util.List.of("off", "minimal", "low", "medium", "high", "xhigh", "max")) {
                statement.executeUpdate("UPDATE threads SET reasoning_level='" + level
                        + "' WHERE thread_id='thr_reasoning'");
                statement.executeUpdate("UPDATE turns SET reasoning_level='" + level
                        + "' WHERE turn_id='turn_reasoning'");
            }
            assertThrows(java.sql.SQLException.class,
                    () -> statement.executeUpdate("UPDATE threads SET reasoning_level='extreme'"));
            assertThrows(java.sql.SQLException.class,
                    () -> statement.executeUpdate("UPDATE turns SET reasoning_level='extreme'"));
            assertFalse(columnNames(connection, "threads").contains("reasoning_effort"));
            assertFalse(columnNames(connection, "turns").contains("reasoning_effort"));
        }
    }

    /** 真实 V1 历史行迁移后保持 null 事实，而新写入必须提供完整快照。 */
    @Test
    void migratesV1RowsWithoutInventingRuntimeFacts() throws Exception {
        Path database = temp.resolve("legacy.sqlite3");
        SQLiteDataSource source = source(database);
        flywayV1(source).migrate();
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             java.sql.Statement statement = connection.createStatement()) {
            String now = Instant.parse("2026-08-30T00:00:00Z").toString();
            statement.executeUpdate("INSERT INTO workspaces VALUES "
                    + "('ws_legacy','C:/legacy','Legacy','TRUSTED',0,'" + now + "','" + now + "')");
            statement.executeUpdate("INSERT INTO threads "
                    + "(thread_id,workspace_id,title,profile_id,revision,created_at,updated_at) VALUES "
                    + "('thr_legacy','ws_legacy','Legacy','profile_legacy',0,'" + now + "','" + now + "')");
            statement.executeUpdate("INSERT INTO turns "
                    + "(turn_id,thread_id,state,profile_id,config_generation,mutation_version,requested_at,updated_at) "
                    + "VALUES ('turn_legacy','thr_legacy','RUNNING','profile_legacy','cfg_legacy',0,'"
                    + now + "','" + now + "')");
        }

        flyway(source).migrate();
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            assertFinalColumns(connection, "threads", Set.of("provider_id", "model_id", "access_mode", "title_source"));
            assertFinalColumns(connection, "turns", Set.of("provider_id", "model_id", "provider", "api",
                    "upstream_model", "access_mode"));
            try (java.sql.ResultSet row = connection.createStatement().executeQuery(
                    "SELECT provider_id,model_id,access_mode,title_source FROM threads WHERE thread_id='thr_legacy'")) {
                assertTrue(row.next());
                assertTrue(row.getString("provider_id") == null);
                assertTrue(row.getString("model_id") == null);
                assertTrue(row.getString("access_mode") == null);
                assertTrue(row.getString("title_source") == null);
            }
            assertThrows(java.sql.SQLException.class, () -> connection.createStatement().executeUpdate(
                    "INSERT INTO threads (thread_id,workspace_id,title,revision,created_at,updated_at) VALUES "
                            + "('thr_invalid','ws_legacy','Invalid',0,'2026-08-30T00:00:00Z','2026-08-30T00:00:00Z')"));
        }
    }

    /** 含旧 Chat Completions 快照的 V2 数据必须让 V3 整体回滚，不可静默改写历史。 */
    @Test
    void rejectsRetiredChatCompletionRowsWithoutLeavingPartialV3() throws Exception {
        Path database = temp.resolve("retired-api.sqlite3");
        SQLiteDataSource source = source(database);
        flywayV2(source).migrate();
        String now = Instant.parse("2026-08-30T00:00:00Z").toString();
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO workspaces VALUES "
                    + "('ws_retired','C:/retired','Retired','TRUSTED',0,'" + now + "','" + now + "')");
            statement.executeUpdate("INSERT INTO threads(thread_id,workspace_id,title,revision,created_at,updated_at,"
                    + "provider_id,model_id,reasoning_effort,access_mode,title_source) VALUES "
                    + "('thr_retired','ws_retired','Retired',0,'" + now + "','" + now
                    + "','provider_retired','model_retired',NULL,'APPROVAL_REQUIRED','USER')");
            statement.executeUpdate("INSERT INTO turns(turn_id,thread_id,state,config_generation,mutation_version,"
                    + "requested_at,updated_at,provider_id,model_id,provider,api,upstream_model,reasoning_effort,access_mode) "
                    + "VALUES ('turn_retired','thr_retired','RUNNING','cfg_retired',0,'" + now + "','" + now
                    + "','provider_retired','model_retired','openai','openai_chat_completions','legacy-model',NULL,"
                    + "'APPROVAL_REQUIRED')");
        }

        assertThrows(FlywayException.class, () -> flyway(source).migrate());
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            assertTrue(columnNames(connection, "turns").contains("api"));
            assertFalse(columnNames(connection, "turns").contains("api_v3"));
            assertFalse(tableNames(connection).contains("attachment_blobs"));
            try (java.sql.ResultSet row = connection.createStatement().executeQuery(
                    "SELECT api FROM turns WHERE turn_id='turn_retired'")) {
                assertTrue(row.next());
                assertEquals("openai_chat_completions", row.getString(1));
            }
        }
    }

    /**
     * 已发布 V4 的校验和不可改写；V5 只降级迁移时猜测的 final，保留 V4 上线后由 terminal
     * 事务写入的 final，同时收敛公开 unknown 并删除无法安全重投影的旧结果正文。
     */
    @Test
    void migratesLegacyTimelineWithoutInventingFinalOrPublicUnknown() throws Exception {
        Path database = temp.resolve("legacy-agent-timeline.sqlite3");
        SQLiteDataSource source = source(database);
        flywayV3(source).migrate();
        String now = Instant.parse("2026-08-30T00:00:00Z").toString();
        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO workspaces VALUES "
                    + "('ws_agent','C:/agent','Agent','TRUSTED',0,'" + now + "','" + now + "')");
            statement.executeUpdate("INSERT INTO threads(thread_id,workspace_id,title,revision,created_at,updated_at,"
                    + "provider_id,model_id,reasoning_effort,access_mode,title_source) VALUES "
                    + "('thr_agent','ws_agent','Agent',0,'" + now + "','" + now
                    + "','provider_agent','model_agent',NULL,'FULL_ACCESS','USER')");
            statement.executeUpdate("INSERT INTO turns(turn_id,thread_id,state,config_generation,mutation_version,"
                    + "requested_at,updated_at,completed_at,terminal_summary,provider_id,model_id,provider,api,"
                    + "upstream_model,reasoning_effort,access_mode) VALUES "
                    + "('turn_agent','thr_agent','COMPLETED','cfg_agent',0,'" + now + "','" + now + "','" + now
                    + "','legacy done','provider_agent','model_agent','openai','openai_responses','gpt-agent',NULL,"
                    + "'FULL_ACCESS')");
            statement.executeUpdate("INSERT INTO messages VALUES "
                    + "('item_user','thr_agent','turn_agent',1,'USER','[{\"kind\":\"text\",\"text\":\"run\"}]','"
                    + now + "')");
            statement.executeUpdate("INSERT INTO messages VALUES "
                    + "('item_progress','thr_agent','turn_agent',2,'ASSISTANT',"
                    + "'[{\"kind\":\"text\",\"text\":\"legacy assistant\"}]','" + now + "')");
            statement.executeUpdate("INSERT INTO messages VALUES "
                    + "('item_tool','thr_agent','turn_agent',3,'TOOL',"
                    + "'[{\"kind\":\"tool_result\",\"callId\":\"call_agent\","
                    + "\"content\":\"legacy-raw-secret\",\"error\":true}]','" + now + "')");
            statement.executeUpdate("INSERT INTO usage VALUES "
                    + "('usage_agent','thr_agent','turn_agent',3,1,1,2,'" + now + "')");
            statement.executeUpdate("INSERT INTO tools(call_id,thread_id,turn_id,ordinal,tool_name,side_effect,"
                    + "arguments_json,state,result_content,result_error,revision,created_at,updated_at) VALUES "
                    + "('call_agent','thr_agent','turn_agent',0,'shell','EXTERNAL','{\"command\":\"secret\"}',"
                    + "'UNKNOWN','legacy-raw-secret',1,2,'" + now + "','" + now + "')");
        }

        flywayV4(source).migrate();

        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             java.sql.Statement statement = connection.createStatement()) {
            assertEquals(1457177006, scalar(statement,
                    "SELECT checksum FROM flyway_schema_history WHERE version='4' AND success=1"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM timeline_messages WHERE item_id='item_progress' "
                            + "AND message_kind='FINAL_ANSWER'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM tools WHERE call_id='call_agent' "
                            + "AND json_extract(presentation_json,'$.status')='unknown'"));
            try (java.sql.ResultSet row = statement.executeQuery(
                    "SELECT json_extract(blocks_json,'$[0].content') FROM messages WHERE message_id='item_tool'")) {
                assertTrue(row.next());
                assertEquals("legacy-raw-secret", row.getString(1));
            }

            String terminalAt = Instant.parse("2099-01-01T00:00:00Z").toString();
            statement.executeUpdate("INSERT INTO messages VALUES "
                    + "('item_terminal','thr_agent','turn_agent',4,'ASSISTANT',"
                    + "'[{\"kind\":\"text\",\"text\":\"verified terminal\"}]','" + terminalAt + "')");
            statement.executeUpdate("INSERT INTO timeline_messages VALUES "
                    + "('item_terminal','thr_agent','turn_agent','FINAL_ANSWER','verified terminal',NULL,'"
                    + terminalAt + "')");
        }

        flyway(source).migrate();

        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             java.sql.Statement statement = connection.createStatement()) {
            try (java.sql.ResultSet row = statement.executeQuery(
                    "SELECT message_kind,model_round FROM timeline_messages WHERE item_id='item_progress'")) {
                assertTrue(row.next());
                assertEquals("ASSISTANT_PROGRESS", row.getString("message_kind"));
                assertEquals(3, row.getInt("model_round"));
            }
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM timeline_messages WHERE message_kind='FINAL_ANSWER' "
                            + "AND item_id='item_terminal'"));
            try (java.sql.ResultSet row = statement.executeQuery(
                    "SELECT state,json_extract(presentation_json,'$.status') FROM tools WHERE call_id='call_agent'")) {
                assertTrue(row.next());
                assertEquals("UNKNOWN", row.getString(1));
                assertEquals("error", row.getString(2));
            }
            try (java.sql.ResultSet row = statement.executeQuery(
                    "SELECT json_extract(blocks_json,'$[0].content') FROM messages WHERE message_id='item_tool'")) {
                assertTrue(row.next());
                assertEquals("[historical tool output unavailable]", row.getString(1));
            }
            assertFalse(columnNames(connection, "tools").contains("arguments_json"));
            assertFalse(columnNames(connection, "tools").contains("result_content"));
        }
    }

    /** 迁移完成后重复启动不重放 DDL，Flyway history 仍保持唯一 V1 至 V6 记录。 */
    @Test
    void repeatedStartupDoesNotReplayV2() throws Exception {
        Path database = temp.resolve("repeat.sqlite3");
        SQLiteDataSource source = source(database);
        flyway(source).migrate();
        flyway(source).migrate();

        try (java.sql.Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             java.sql.ResultSet rows = connection.createStatement().executeQuery(
                     "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1")) {
            assertTrue(rows.next());
            assertEquals(6, rows.getInt(1));
        }
    }

    /** 使用生产固定资源集，确保测试不会意外发现旧 migration。 */
    private static Flyway flyway(SQLiteDataSource source) {
        return Flyway.configure().dataSource(source).locations(new String[0])
                .resourceProvider(JaFlywayResources.provider()).baselineOnMigrate(false).load();
    }

    /** 用生产资源的真实 V1 创建历史 fixture，避免测试手写 schema 与发布基线漂移。 */
    private static Flyway flywayV1(SQLiteDataSource source) {
        return flywayThrough(source, "V1__kernel.sql");
    }

    /** 创建可写入旧 API fixture 的正式 V2 数据库，不手写任一历史 schema。 */
    private static Flyway flywayV2(SQLiteDataSource source) {
        return flywayThrough(source, "V1__kernel.sql", "V2__thread_runtime_preferences.sql");
    }

    /** 创建真实 V3 旧库，专用于 V4 安全时间线一次性迁移验证。 */
    private static Flyway flywayV3(SQLiteDataSource source) {
        return flywayThrough(source, "V1__kernel.sql", "V2__thread_runtime_preferences.sql",
                "V3__managed_attachments_and_title_usage.sql");
    }

    /** 创建已发布 V4 边界，用真实 checksum 锁定不可变历史并验证 V5 的选择性修复。 */
    private static Flyway flywayV4(SQLiteDataSource source) {
        return flywayThrough(source, "V1__kernel.sql", "V2__thread_runtime_preferences.sql",
                "V3__managed_attachments_and_title_usage.sql", "V4__safe_agent_timeline.sql");
    }

    /** 创建已发布 V5 边界，用于证明 V6 只迁移思考列和值域。 */
    private static Flyway flywayV5(SQLiteDataSource source) {
        return flywayThrough(source, "V1__kernel.sql", "V2__thread_runtime_preferences.sql",
                "V3__managed_attachments_and_title_usage.sql", "V4__safe_agent_timeline.sql",
                "V5__close_agent_timeline_states.sql");
    }

    /** 只向 Flyway 暴露指定正式资源，保证迁移失败测试与真实发布 SQL 同源。 */
    private static Flyway flywayThrough(SQLiteDataSource source, String... filenames) {
        ResourceProvider all = JaFlywayResources.provider();
        Set<String> allowed = Set.of(filenames);
        ResourceProvider selected = new ResourceProvider() {
            /** 精确传递已选资源，任何其他名称仍按不存在处理。 */
            @Override public LoadableResource getResource(String name) {
                LoadableResource resource = all.getResource(name);
                return resource != null && allowed.contains(resource.getFilename()) ? resource : null;
            }

            /** Flyway scanner 只可看到已选正式 migration，并保持生产版本顺序。 */
            @Override public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
                return all.getResources(prefix, suffixes).stream()
                        .filter(resource -> allowed.contains(resource.getFilename())).toList();
            }
        };
        return Flyway.configure().dataSource(source).locations(new String[0])
                .resourceProvider(selected).baselineOnMigrate(false).load();
    }

    /** 创建与聚焦 Flyway 测试一致的单文件 SQLite datasource。 */
    private static SQLiteDataSource source(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + path);
        return source;
    }

    /** 校验最终必需列，同时不让 migration 断言依赖列顺序。 */
    private static void assertFinalColumns(java.sql.Connection connection, String table,
                                           Set<String> required) throws Exception {
        assertTrue(columnNames(connection, table).containsAll(required));
    }

    /** 保持通用作用域 selector 可表达，不引入旧 sentinel 值。 */
    private static void assertNullableColumn(java.sql.Connection connection, String table,
                                             String column) throws Exception {
        try (java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                if (column.equals(rows.getString("name"))) {
                    assertFalse(rows.getBoolean("notnull"));
                    return;
                }
            }
        }
        throw new AssertionError("missing column " + table + "." + column);
    }

    /** 读取 SQLite 权威列清单，不依赖 Mapper metadata。 */
    private static Set<String> columnNames(java.sql.Connection connection, String table) throws Exception {
        Set<String> columns = new HashSet<>();
        try (java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                columns.add(rows.getString("name"));
            }
        }
        return columns;
    }

    /** 读取 SQLite schema 表，验证失败 migration 没有留下半张附件表。 */
    private static Set<String> tableNames(java.sql.Connection connection) throws Exception {
        Set<String> tables = new HashSet<>();
        try (java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rows = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table'")) {
            while (rows.next()) tables.add(rows.getString(1));
        }
        return tables;
    }

    /** 聚焦断言只读取单个整数，避免测试复制 JDBC 游标样板。 */
    private static int scalar(java.sql.Statement statement, String query) throws Exception {
        try (java.sql.ResultSet row = statement.executeQuery(query)) {
            if (!row.next()) throw new AssertionError("missing scalar row");
            return row.getInt(1);
        }
    }

    /** 聚焦迁移断言只读取单个文本值，避免通过 Mapper 掩盖物理列名或复制状态。 */
    private static String textScalar(java.sql.Statement statement, String query) throws Exception {
        try (java.sql.ResultSet row = statement.executeQuery(query)) {
            if (!row.next()) throw new AssertionError("missing scalar row");
            return row.getString(1);
        }
    }
}
