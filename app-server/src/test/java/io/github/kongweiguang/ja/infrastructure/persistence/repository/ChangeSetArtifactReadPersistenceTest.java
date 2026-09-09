// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 SQLite 验证冻结 ChangeSet 每次完整读取、文件提取、权限与损坏数据关闭语义。 */
final class ChangeSetArtifactReadPersistenceTest extends PersistenceTestSupport {
    private static final String FIRST = "--- a/src/first.txt\n+++ b/src/first.txt\n"
            + "@@ -1,2 +1,2 @@\n--- decoy\n-old\n+new\n+tail\n";
    private static final String LAST = "--- /dev/null\n+++ b/src/末尾😀.txt\n"
            + "@@ -0,0 +1,1 @@\n+末尾😀\n\\ No newline at end of file\n";
    private static final String DELETED = "--- a/src/deleted.txt\n+++ /dev/null\n"
            + "@@ -1,1 +0,0 @@\n-gone\n";

    /** A→B→A 每次都重新读取完整 artifact，且响应只包含所选文件正文与文件级完整性。 */
    @Test
    void readsEverySelectionWithoutBodyOrIndexCache() throws Exception {
        try (TestDatabase database = database("change-set-file-read")) {
            QueryCounter queries = new QueryCounter();
            database.sessions().getConfiguration().addInterceptor(queries);
            insertArtifact(database, "turn_review", "artifact_review", FIRST + DELETED + LAST);
            MybatisConversationRepository store = database.agentStore();
            MybatisHistoryService history = database.history(store);

            ThreadUseCase.ChangeSetArtifactFile first = history.readChangeSetArtifact(
                    "thr_review", "turn_review", "artifact_review", "src/first.txt").orElseThrow();
            ThreadUseCase.ChangeSetArtifactFile deleted = history.readChangeSetArtifact(
                    "thr_review", "turn_review", "artifact_review", "src/deleted.txt").orElseThrow();
            ThreadUseCase.ChangeSetArtifactFile again = history.readChangeSetArtifact(
                    "thr_review", "turn_review", "artifact_review", "src/first.txt").orElseThrow();

            assertFile(first, "src/first.txt", FIRST);
            assertFile(deleted, "src/deleted.txt", DELETED);
            assertFile(again, "src/first.txt", FIRST);
            assertFalse(decode(first).contains("末尾😀.txt"));
            assertEquals(3, queries.fullBlobSelects.get());
            store.close();
        }
    }

    /** 大型冻结清单点击尾部文件时仍只返回该文件，并执行一次权威正文查询。 */
    @Test
    void readsTailFileFromHundredFileArtifact() throws Exception {
        try (TestDatabase database = database("change-set-hundred-files")) {
            QueryCounter queries = new QueryCounter();
            database.sessions().getConfiguration().addInterceptor(queries);
            StringBuilder aggregate = new StringBuilder();
            for (int index = 0; index < 100; index++) aggregate.append(modifiedFile(index));
            insertArtifact(database, "turn_review", "artifact_review", aggregate.toString());
            MybatisConversationRepository store = database.agentStore();
            MybatisHistoryService history = database.history(store);

            ThreadUseCase.ChangeSetArtifactFile file = history.readChangeSetArtifact(
                    "thr_review", "turn_review", "artifact_review", "src/file-099.txt").orElseThrow();

            assertFile(file, "src/file-099.txt", modifiedFile(99));
            assertFalse(decode(file).contains("src/file-000.txt"));
            assertEquals(1, queries.fullBlobSelects.get());
            store.close();
        }
    }

    /** 删除 artifact 或逻辑删除 Thread 后，下一次读取必须直接观察 SQLite 当前事实。 */
    @Test
    void deletionImmediatelyRevokesReads() throws Exception {
        try (TestDatabase database = database("change-set-delete")) {
            insertArtifact(database, "turn_review", "artifact_review", LAST);
            MybatisConversationRepository store = database.agentStore();
            MybatisHistoryService history = database.history(store);
            assertTrue(history.readChangeSetArtifact("thr_review", "turn_review", "artifact_review",
                    "src/末尾😀.txt").isPresent());
            try (var session = database.sessions().openSession();
                 Statement statement = session.getConnection().createStatement()) {
                statement.executeUpdate("UPDATE threads SET deleted_at='2026-09-08T01:00:00Z'"
                        + " WHERE thread_id='thr_review'");
                session.commit();
            }
            assertTrue(history.readChangeSetArtifact("thr_review", "turn_review", "artifact_review",
                    "src/末尾😀.txt").isEmpty());
            store.close();
        }
    }

    /** 聚合长度或摘要被篡改时整次读取失败，不能返回局部可解析正文。 */
    @Test
    void validatesPersistedAggregateOnEveryRead() throws Exception {
        try (TestDatabase database = database("change-set-digest")) {
            insertArtifact(database, "turn_review", "artifact_review", LAST);
            MybatisConversationRepository store = database.agentStore();
            MybatisHistoryService history = database.history(store);
            assertTrue(history.readChangeSetArtifact("thr_review", "turn_review", "artifact_review",
                    "src/末尾😀.txt").isPresent());
            try (var session = database.sessions().openSession();
                 Statement statement = session.getConnection().createStatement()) {
                statement.executeUpdate("UPDATE change_set_artifacts SET sha256='"
                        + "0".repeat(64) + "' WHERE artifact_id='artifact_review'");
                session.commit();
            }
            assertThrows(IllegalArgumentException.class, () -> history.readChangeSetArtifact(
                    "thr_review", "turn_review", "artifact_review", "src/末尾😀.txt"));
            store.close();
        }
    }

    /** 缺失、父目录和非规范路径都只能作为 artifact 内键失败，绝不转成工作区文件访问。 */
    @Test
    void rejectsMissingAndMaliciousFileKeys() throws Exception {
        try (TestDatabase database = database("change-set-path-identity")) {
            insertArtifact(database, "turn_review", "artifact_review", LAST);
            MybatisConversationRepository store = database.agentStore();
            MybatisHistoryService history = database.history(store);
            assertTrue(history.readChangeSetArtifact("thr_review", "turn_review", "artifact_review",
                    "src/missing.txt").isEmpty());
            assertThrows(IllegalArgumentException.class, () -> history.readChangeSetArtifact(
                    "thr_review", "turn_review", "artifact_review", "../outside.txt"));
            assertThrows(IllegalArgumentException.class, () -> history.readChangeSetArtifact(
                    "thr_review", "turn_review", "artifact_review", "C:\\outside.txt"));
            store.close();
        }
    }

    /** 重复文件 header 或未满足 hunk 行数的 artifact 均整体失败，不能返回部分索引。 */
    @Test
    void malformedOrDuplicateSectionsFailClosed() throws Exception {
        try (TestDatabase database = database("change-set-malformed")) {
            insertArtifact(database, "turn_duplicate", "artifact_duplicate", LAST + LAST);
            insertArtifact(database, "turn_incomplete", "artifact_incomplete",
                    "--- a/a.txt\n+++ b/a.txt\n@@ -1,1 +1,1 @@\n-old\n");
            MybatisConversationRepository store = database.agentStore();
            MybatisHistoryService history = database.history(store);
            assertThrows(IllegalArgumentException.class, () -> history.readChangeSetArtifact(
                    "thr_review", "turn_duplicate", "artifact_duplicate", "src/末尾😀.txt"));
            assertThrows(IllegalArgumentException.class, () -> history.readChangeSetArtifact(
                    "thr_review", "turn_incomplete", "artifact_incomplete", "a.txt"));
            store.close();
        }
    }

    /** 核对响应身份、UTF-8 长度、文件级 SHA-256 与标准 Base64 正文。 */
    private static void assertFile(ThreadUseCase.ChangeSetArtifactFile file, String path, String expected)
            throws Exception {
        byte[] bytes = expected.getBytes(StandardCharsets.UTF_8);
        assertEquals(path, file.filePath());
        assertEquals(bytes.length, file.byteLength());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), file.sha256());
        assertEquals(expected, decode(file));
    }

    /** 标准 Base64 解码只用于测试投影，不接受 MIME 或 URL-safe 变体。 */
    private static String decode(ThreadUseCase.ChangeSetArtifactFile file) {
        return new String(Base64.getDecoder().decode(file.contentBase64()), StandardCharsets.UTF_8);
    }

    /** 生成稳定的单 hunk 文件，供大型聚合测试精确比较尾部文件。 */
    private static String modifiedFile(int index) {
        String path = "src/file-%03d.txt".formatted(index);
        return "--- a/" + path + "\n+++ b/" + path + "\n@@ -1,1 +1,1 @@\n-old-" + index
                + "\n+new-" + index + "\n";
    }

    /** 只在临时 SQLite 建立最小合法 owner 链，正文使用参数绑定保留 Unicode 与换行。 */
    private void insertArtifact(TestDatabase database, String turnId, String artifactId, String diff)
            throws Exception {
        byte[] bytes = diff.getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        try (var session = database.sessions().openSession()) {
            try (Statement statement = session.getConnection().createStatement()) {
                statement.executeUpdate("INSERT OR IGNORE INTO workspaces(workspace_id,root_path,display_name,"
                        + "trust,created_at,updated_at) VALUES('ws_review','C:/fixture','fixture','TRUSTED',"
                        + "'2026-09-08T00:00:00Z','2026-09-08T00:00:00Z')");
                statement.executeUpdate("INSERT OR IGNORE INTO threads(thread_id,workspace_id,title,provider_id,"
                        + "model_id,access_mode,collaboration_mode,title_source,created_at,updated_at) VALUES("
                        + "'thr_review','ws_review','review','provider','model','FULL_ACCESS','DEFAULT','MANUAL',"
                        + "'2026-09-08T00:00:00Z','2026-09-08T00:00:00Z')");
            }
            try (PreparedStatement turn = session.getConnection().prepareStatement(
                    "INSERT INTO turns(turn_id,thread_id,state,requested_at,updated_at,completed_at,terminal_summary)"
                            + " VALUES(?,'thr_review','COMPLETED','2026-09-08T00:00:00Z',"
                            + "'2026-09-08T00:00:01Z','2026-09-08T00:00:01Z','done')")) {
                turn.setString(1, turnId);
                turn.executeUpdate();
            }
            try (PreparedStatement artifact = session.getConnection().prepareStatement(
                    "INSERT INTO change_set_artifacts(artifact_id,thread_id,turn_id,content,sha256,byte_length,"
                            + "created_at) VALUES(?,'thr_review',?,?,?,?, '2026-09-08T00:00:01Z')")) {
                artifact.setString(1, artifactId);
                artifact.setString(2, turnId);
                artifact.setString(3, diff);
                artifact.setString(4, sha256);
                artifact.setLong(5, bytes.length);
                artifact.executeUpdate();
            }
            session.commit();
        }
    }

    /** 精确统计本能力完整正文查询，证明每个 A→B→A 选择都访问 SQLite。 */
    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    private static final class QueryCounter implements Interceptor {
        private final AtomicInteger fullBlobSelects = new AtomicInteger();

        /** 查询执行前按 statement ID 计数，不改变参数、结果或事务。 */
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            String statement = ((MappedStatement) invocation.getArgs()[0]).getId();
            if (statement.endsWith(".selectChangeSetArtifact")) fullBlobSelects.incrementAndGet();
            return invocation.proceed();
        }
    }
}
