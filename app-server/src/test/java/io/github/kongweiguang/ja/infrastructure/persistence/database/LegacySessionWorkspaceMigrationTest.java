// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import io.github.kongweiguang.ja.attachment.adapter.out.persistence.MybatisAttachmentRepository;
import io.github.kongweiguang.ja.attachment.adapter.out.storage.ManagedAttachmentStore;
import io.github.kongweiguang.ja.attachment.application.AttachmentService;
import io.github.kongweiguang.ja.attachment.port.in.AttachmentPreviewUseCase;
import io.github.kongweiguang.ja.attachment.port.in.AttachmentUseCase;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SchemaMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import io.github.kongweiguang.ja.workspace.domain.WorkspacePolicy;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.mapping.Environment;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证旧 V4/V6 数据副本升级到 V7 时先备份，再原子迁移线程树与附件归属。 */
final class LegacySessionWorkspaceMigrationTest {
    private static final String TIME = "2026-09-23T00:00:00Z";
    private static final byte[] BLOB_CONTENT = "migrated attachment bytes\r\nremain readable".getBytes(
            java.nio.charset.StandardCharsets.UTF_8);
    private static final String BLOB_SHA = sha256Unchecked(BLOB_CONTENT);
    private static final MybatisUnitOfWork.SessionOwner TEST_SQLITE_TRANSACTIONS =
            new MybatisUnitOfWork.SessionOwner() {
                /** 迁移验收不启动 Solon；用真实 SQLite session 明确提交读取事务。 */
                @Override
                public <T> T execute(SqlSessionFactory sessions, MybatisUnitOfWork.Work<T> work) {
                    try (org.apache.ibatis.session.SqlSession session = sessions.openSession()) {
                        try {
                            T result = work.apply(PersistenceMappers.open(session));
                            session.commit();
                            return result;
                        } catch (Throwable failure) {
                            session.rollback();
                            if (failure instanceof StorageException persistence) throw persistence;
                            if (failure instanceof RuntimeException runtime) throw runtime;
                            if (failure instanceof Error error) throw error;
                            throw new StorageException(StorageException.Code.TRANSACTION,
                                    "migration test transaction failed", failure);
                        }
                    }
                }
            };

    @TempDir Path temporary;

    /** 两个旧升级起点都必须保留预升级 WAL 快照，并在数据事务修复后可幂等续迁。 */
    @ParameterizedTest(name = "V{0} legacy copy migration")
    @ValueSource(strings = {"4", "6"})
    void migratesIsolatedLegacyCopyAtomicallyAndIdempotently(String sourceVersion) throws Exception {
        Path data = temporary.resolve("ja-data");
        Path home = temporary.resolve("ja-home");
        Path databasePath = data.resolve("ja.db");
        Path legacyRoot = data.resolve("general-workspace");
        Path preservedFile = legacyRoot.resolve("nested").resolve("old.txt");
        byte[] originalBytes = "旧共享目录内容\r\nkeep byte for byte".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.createDirectories(preservedFile.getParent());
        Files.write(preservedFile, originalBytes);
        String originalHash = sha256(originalBytes);
        Files.createDirectories(data);
        Path blobRoot = data.resolve("attachments/blobs");
        Files.createDirectories(blobRoot);
        Files.write(blobRoot.resolve(BLOB_SHA), BLOB_CONTENT);

        SQLiteDataSource source = dataSource(databasePath);
        try (Connection anchor = source.getConnection()) {
            try (Statement pragma = anchor.createStatement()) {
                pragma.execute("PRAGMA journal_mode=WAL");
                pragma.execute("PRAGMA wal_autocheckpoint=0");
                pragma.execute("PRAGMA foreign_keys=ON");
            }
            migrateLegacySchema(source, sourceVersion);
            String legacyWorkspaceId = new WorkspacePolicy().workspaceId(legacyRoot.toRealPath());
            seedLegacyRows(anchor, legacyWorkspaceId, legacyRoot.toRealPath());
            assertTrue(Files.exists(databasePath.resolveSibling(databasePath.getFileName().toString() + "-wal")),
                    "fixture keeps committed V4 rows in WAL while backup is taken");

            StorageExceptionCarrier failure = assertMigrationFailure(databasePath, home);
            assertNotNull(failure.cause());
            assertFailedDataTransactionWasRolledBack(source, legacyWorkspaceId);
            assertEquals(1, backupSizes(databasePath, sourceVersion, 4, 4, legacyWorkspaceId).size(),
                    "failed V7 data migration must retain its snapshot");

            try (Connection repair = source.getConnection(); Statement statement = repair.createStatement()) {
                statement.executeUpdate("DELETE FROM pending_input_attachments WHERE attachment_id='att_conflict'");
            }

            try (JaDatabase database = openDatabase(databasePath, home)) {
                String sessionA = workspaceId(database, "thr_main_a");
                String sessionB = workspaceId(database, "thr_main_b");
                assertEquals(new WorkspacePolicy().workspaceId(
                        home.resolve("workspaces/thr_main_a").toRealPath()), sessionA);
                assertEquals(new WorkspacePolicy().workspaceId(
                        home.resolve("workspaces/thr_main_b").toRealPath()), sessionB);
                assertFalse(sessionA.equals(sessionB));
                assertSessionTree(database, "thr_main_a", "thr_side_a", sessionA);
                assertSessionTree(database, "thr_main_b", "thr_side_b", sessionB);
                assertEquals("LEGACY_SHARED", scalar(database,
                        "SELECT kind FROM workspaces WHERE workspace_id='" + legacyWorkspaceId + "'"));
                assertEquals(legacyWorkspaceId, scalar(database,
                        "SELECT legacy_shared_workspace_id FROM workspaces WHERE workspace_id='" + sessionA + "'"));

                MybatisAttachmentRepository attachments = new MybatisAttachmentRepository(
                        factory(database), TEST_SQLITE_TRANSACTIONS);
                assertEquals(sessionA, attachments.findThread("att_message", "thr_main_a").orElseThrow()
                        .workspaceId());
                assertEquals(sessionB, attachments.findThread("att_pending", "thr_main_b").orElseThrow()
                        .workspaceId());
                assertEquals(sessionA, attachments.findThread("att_conflict", "thr_main_a").orElseThrow()
                        .workspaceId());
                assertMigratedAttachmentsRemainReadable(attachments, data,
                        List.of(new AttachmentOwner("att_message", "thr_main_a"),
                                new AttachmentOwner("att_pending", "thr_main_b")));
                assertEquals(legacyWorkspaceId, scalar(database,
                        "SELECT workspace_id FROM attachments WHERE attachment_id='att_unowned'"));

                assertEquals(sessionA, scalar(database,
                        "SELECT workspace_id FROM turn_change_sets WHERE turn_id='turn_main_a'"));
                assertEquals(sessionB, scalar(database,
                        "SELECT workspace_id FROM turn_change_sets WHERE turn_id='turn_side_b'"));
                assertEquals(sessionA, scalar(database,
                        "SELECT workspace_id FROM workspace_write_claims WHERE claim_id='claim_main_a'"));
                assertEquals(sessionB, scalar(database,
                        "SELECT workspace_id FROM workspace_write_claims WHERE claim_id='claim_side_b'"));
                assertEquals(0, scalarLong(database,
                        "SELECT COUNT(*) FROM workspace_write_claims c JOIN threads t USING(thread_id) "
                                + "WHERE c.workspace_id<>t.workspace_id"));
                assertEquals(0, scalarLong(database,
                        "SELECT COUNT(*) FROM turn_change_sets c JOIN threads t USING(thread_id) "
                                + "WHERE c.workspace_id<>t.workspace_id"));
                assertTrue(Files.isDirectory(home.resolve("workspaces/thr_main_a")));
                assertTrue(Files.isDirectory(home.resolve("workspaces/thr_main_b")));
                assertEquals(0, childCount(home.resolve("workspaces/thr_main_a")));
                assertEquals(0, childCount(home.resolve("workspaces/thr_main_b")));
                assertEquals(1L, scalarLong(database,
                        "SELECT revision FROM workspaces WHERE workspace_id='" + legacyWorkspaceId + "'"));
                assertEquals("7", scalar(database,
                        "SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1"));
            }

            try (JaDatabase ignored = openDatabase(databasePath, home)) {
                // 同一 SESSION 事实重开不重复创建 workspace，也不再次推进旧共享根 revision。
            }
            assertEquals(1, backupSizes(databasePath, sourceVersion, 4, 4, legacyWorkspaceId).size(),
                    "V7-complete restart must not create another snapshot");
            assertEquals(1L, scalarLong(source,
                    "SELECT revision FROM workspaces WHERE workspace_id='" + legacyWorkspaceId + "'"));
            assertEquals(originalHash, sha256(Files.readAllBytes(preservedFile)));
            assertEquals(originalBytes.length, Files.size(preservedFile));
            assertTrue(Files.exists(preservedFile), "migration must leave old shared files in place");
        }
    }

    /** 异址复制使旧 data 根与配置路径失配时，必须保留备份并停止启动，不能遗留 PROJECT 会话。 */
    @Test
    void refusesRelocatedLegacyGeneralRootBeforeStartup(@TempDir Path isolated) throws Exception {
        Path data = Files.createDirectories(isolated.resolve("copied-data"));
        Path home = Files.createDirectories(isolated.resolve("home"));
        Path originalGeneral = Files.createDirectories(isolated.resolve("original-data/general-workspace"));
        byte[] untouched = "keep the relocated legacy folder untouched".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        Path oldFile = originalGeneral.resolve("old.txt");
        Files.write(oldFile, untouched);
        Path databasePath = data.resolve("ja.db");
        SQLiteDataSource source = dataSource(databasePath);
        migrateLegacySchema(source, "6");
        String legacyWorkspaceId = insertPreV7Workspace(source, originalGeneral, "无项目");

        assertThrows(io.github.kongweiguang.ja.foundation.error.StorageException.class,
                () -> JaDatabase.open(DatabaseConfig.of(databasePath, home)));

        assertEquals("PROJECT", scalar(source,
                "SELECT kind FROM workspaces WHERE workspace_id='" + legacyWorkspaceId + "'"));
        assertEquals(1, backupSizes(databasePath, "6", 0, 0, null).size());
        assertArrayEquals(untouched, Files.readAllBytes(oldFile));
    }

    /** 普通项目目录仅因大小写相近或叶名像 general-workspace，不应被旧共享身份猜测。 */
    @Test
    void keepsCaseDistinctOrdinaryProjectWhenLegacyRootIsMissing(@TempDir Path isolated) throws Exception {
        Path data = Files.createDirectories(isolated.resolve("data"));
        Path home = Files.createDirectories(isolated.resolve("home"));
        Path project = Files.createDirectories(isolated.resolve("General-Workspace"));
        Path databasePath = data.resolve("ja.db");
        SQLiteDataSource source = dataSource(databasePath);
        migrateLegacySchema(source, "6");
        String projectWorkspaceId = insertPreV7Workspace(source, project, "General-Workspace");

        try (JaDatabase database = openDatabase(databasePath, home)) {
            assertEquals("PROJECT", scalar(database,
                    "SELECT kind FROM workspaces WHERE workspace_id='" + projectWorkspaceId + "'"));
            assertEquals(project.toRealPath().toString(), scalar(database,
                    "SELECT root_path FROM workspaces WHERE workspace_id='" + projectWorkspaceId + "'"));
            assertEquals(0, scalarLong(database, "SELECT COUNT(*) FROM workspaces WHERE kind='SESSION'"));
        }
        assertFalse(Files.exists(data.resolve("general-workspace")));
    }

    /** 以已发布 V6 schema 的字段插入 workspace，避免测试绕过 V7 的默认 kind。 */
    private static String insertPreV7Workspace(SQLiteDataSource source, Path root, String displayName)
            throws Exception {
        String workspaceId = new WorkspacePolicy().workspaceId(root.toRealPath());
        try (Connection connection = source.getConnection(); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO workspaces(workspace_id,root_path,display_name,trust,created_at,updated_at) "
                        + "VALUES(?,?,?,'TRUSTED',?,?)")) {
            insert.setString(1, workspaceId);
            insert.setString(2, root.toRealPath().toString());
            insert.setString(3, displayName);
            insert.setString(4, TIME);
            insert.setString(5, TIME);
            insert.executeUpdate();
        }
        return workspaceId;
    }

    /** 用指定已发布 schema 版本构造隔离副本，验证 V7 从早期与最新安装都可前滚。 */
    private static void migrateLegacySchema(SQLiteDataSource source, String version) {
        Flyway.configure().dataSource(source).locations(new String[0])
                .resourceProvider(JaFlywayResources.provider()).baselineOnMigrate(false)
                .target(MigrationVersion.fromVersion(version)).load().migrate();
    }

    /** 通过旧 schema 写出双主、双侧聊、消息/队列附件与 thread-owned write history。 */
    private static void seedLegacyRows(Connection connection, String workspaceId, Path legacyRoot)
            throws Exception {
        try (PreparedStatement workspace = connection.prepareStatement(
                "INSERT INTO workspaces(workspace_id,root_path,display_name,trust,created_at,updated_at) "
                        + "VALUES(?,?,?,'TRUSTED',?,?)")) {
            workspace.setString(1, workspaceId);
            workspace.setString(2, legacyRoot.toString());
            workspace.setString(3, "无项目");
            workspace.setString(4, TIME);
            workspace.setString(5, TIME);
            workspace.executeUpdate();
        }
        for (String thread : List.of("thr_main_a", "thr_side_a", "thr_main_b", "thr_side_b")) {
            insertThread(connection, thread, workspaceId);
            insertTurn(connection, thread.replace("thr_", "turn_"), thread);
            insertChangeSet(connection, thread.replace("thr_", "turn_"), thread, workspaceId);
            insertClaim(connection, "claim_" + thread.substring("thr_".length()), thread,
                    thread.replace("thr_", "turn_"), workspaceId,
                    List.of("thr_main_a", "thr_side_a", "thr_main_b", "thr_side_b").indexOf(thread) + 1);
        }
        insertLineage(connection, "thr_side_a", "thr_main_a", "ctx_a");
        insertLineage(connection, "thr_side_b", "thr_main_b", "ctx_b");
        insertBlobAndAttachment(connection, "att_message", workspaceId, "BOUND");
        insertBlobAndAttachment(connection, "att_pending", workspaceId, "DRAFT");
        insertBlobAndAttachment(connection, "att_conflict", workspaceId, "BOUND");
        insertBlobAndAttachment(connection, "att_unowned", workspaceId, "DRAFT");
        insertMessage(connection, "item_a", "thr_main_a", "turn_main_a", 1);
        insertMessage(connection, "item_a_conflict", "thr_main_a", "turn_main_a", 2);
        insertMessageAttachment(connection, "item_a", "att_message", 0);
        insertMessageAttachment(connection, "item_a_conflict", "att_conflict", 0);
        insertPendingInput(connection, "input_b", "thr_main_b", "turn_main_b");
        insertPendingAttachment(connection, "input_b", "att_pending", 0);
        insertPendingAttachment(connection, "input_b", "att_conflict", 1);
    }

    /** 保存满足首版 schema 约束的 Thread 摘要行，不通过当前生产 API 绕过旧版本边界。 */
    private static void insertThread(Connection connection, String threadId, String workspaceId) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO threads(thread_id,workspace_id,title,created_at,updated_at,provider_id,model_id,"
                        + "access_mode,collaboration_mode,title_source) VALUES(?,?,?,?,?,'provider_test',"
                        + "'model_test','FULL_ACCESS','DEFAULT','PLACEHOLDER')")) {
            insert.setString(1, threadId);
            insert.setString(2, workspaceId);
            insert.setString(3, threadId);
            insert.setString(4, TIME);
            insert.setString(5, TIME);
            insert.executeUpdate();
        }
    }

    /** 每个旧 Thread 都带一条有效终态 Turn，作为 change set、claim 和附件外键所有者。 */
    private static void insertTurn(Connection connection, String turnId, String threadId) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO turns(turn_id,thread_id,state,requested_at,updated_at,completed_at,terminal_summary) "
                        + "VALUES(?,?,'COMPLETED',?,?,?,'complete')")) {
            insert.setString(1, turnId);
            insert.setString(2, threadId);
            insert.setString(3, TIME);
            insert.setString(4, TIME);
            insert.setString(5, TIME);
            insert.executeUpdate();
        }
    }

    /** 冻结 change set 按各自 Thread 写入旧共享 Workspace，供迁移后逐行校验。 */
    private static void insertChangeSet(Connection connection, String turnId, String threadId,
                                        String workspaceId) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO turn_change_sets(turn_id,thread_id,workspace_id,change_set_json,committed_at) "
                        + "VALUES(?,?,?,'{}',?)")) {
            insert.setString(1, turnId);
            insert.setString(2, threadId);
            insert.setString(3, workspaceId);
            insert.setString(4, TIME);
            insert.executeUpdate();
        }
    }

    /** 终态 claims 也必须跟随 thread，且旧共享 fence token 在原 workspace 内全局唯一。 */
    private static void insertClaim(Connection connection, String claimId, String threadId, String turnId,
                                    String workspaceId, int token) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO workspace_write_claims(claim_id,workspace_id,thread_id,turn_id,process_generation,"
                        + "fencing_token,state,requested_at,released_at) VALUES(?,?,?,?,1,?,'RELEASED',?,?)")) {
            insert.setString(1, claimId);
            insert.setString(2, workspaceId);
            insert.setString(3, threadId);
            insert.setString(4, turnId);
            insert.setInt(5, token);
            insert.setString(6, TIME);
            insert.setString(7, TIME);
            insert.executeUpdate();
        }
    }

    /** lineage 与其不可变 seed 一起构造，测试真正覆盖主会话到侧任务的 workspace 重属。 */
    private static void insertLineage(Connection connection, String child, String root, String contextSeed)
            throws Exception {
        try (PreparedStatement seed = connection.prepareStatement(
                "INSERT INTO task_context_seeds(context_seed_id,parent_thread_id,parent_revision,inheritance_mode,"
                        + "references_json,permission_ceiling_json,fingerprint,created_at) "
                        + "VALUES(?,?,0,'BRIEF_ONLY','[]','{}',?,?)")) {
            seed.setString(1, contextSeed);
            seed.setString(2, root);
            seed.setString(3, "c".repeat(64));
            seed.setString(4, TIME);
            seed.executeUpdate();
        }
        try (PreparedStatement lineage = connection.prepareStatement(
                "INSERT INTO thread_lineage(child_thread_id,parent_thread_id,root_thread_id,task_name,depth,"
                        + "task_kind,lifecycle,context_seed_id,created_at) "
                        + "VALUES(?,?,?,? ,1,'SIDE_TASK','INDEPENDENT',?,?)")) {
            lineage.setString(1, child);
            lineage.setString(2, root);
            lineage.setString(3, root);
            lineage.setString(4, child);
            lineage.setString(5, contextSeed);
            lineage.setString(6, TIME);
            lineage.executeUpdate();
        }
    }

    /** 写入真实内容寻址 blob 与附件元数据，使迁移后可以走生产 Thread 授权读路径。 */
    private static void insertBlobAndAttachment(Connection connection, String attachmentId,
                                                String workspaceId, String status) throws Exception {
        try (PreparedStatement blob = connection.prepareStatement(
                "INSERT OR IGNORE INTO attachment_blobs(sha256,size_bytes,media_kind,media_type,created_at) "
                        + "VALUES(?,?,'TEXT','text/plain',?)")) {
            blob.setString(1, BLOB_SHA);
            blob.setLong(2, BLOB_CONTENT.length);
            blob.setString(3, TIME);
            blob.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO attachments(attachment_id,workspace_id,blob_sha256,content_sha256,display_name,"
                        + "size_bytes,media_kind,media_type,status,created_at,expires_at,bound_at) "
                        + "VALUES(?,?,?,?,?,?,'TEXT','text/plain',?,?,?,?)")) {
            insert.setString(1, attachmentId);
            insert.setString(2, workspaceId);
            insert.setString(3, BLOB_SHA);
            insert.setString(4, BLOB_SHA);
            insert.setString(5, attachmentId + ".txt");
            insert.setLong(6, BLOB_CONTENT.length);
            insert.setString(7, status);
            insert.setString(8, TIME);
            insert.setString(9, "2027-09-23T00:00:00Z");
            insert.setString(10, "BOUND".equals(status) ? TIME : null);
            insert.executeUpdate();
        }
    }

    /** 通过生产 repo、blob store、AttachmentService 验证迁移后的消息和队列附件均可授权读回。 */
    private static void assertMigratedAttachmentsRemainReadable(MybatisAttachmentRepository repository,
                                                                 Path dataDirectory,
                                                                 List<AttachmentOwner> owners) {
        Path runDirectory = dataDirectory.resolve("run");
        try {
            Files.createDirectories(runDirectory);
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
        ManagedAttachmentStore blobs = new ManagedAttachmentStore(
                runDirectory, dataDirectory);
        AttachmentService service = new AttachmentService(repository, blobs,
                Clock.fixed(Instant.parse(TIME), ZoneOffset.UTC),
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(), false);
        try (service) {
            for (AttachmentOwner owner : owners) {
                AttachmentUseCase.ReadResult read = service.read(new AttachmentUseCase.ReadRequest(
                        owner.attachmentId(), owner.threadId(), 0, 64 * 1024));
                assertEquals(new String(BLOB_CONTENT, java.nio.charset.StandardCharsets.UTF_8), read.content());
                assertTrue(read.endOfFile());

                var descriptor = service.openPreview(new AttachmentPreviewUseCase.PreviewOpenRequest(
                        owner.attachmentId(), new AttachmentPreviewUseCase.ThreadAuthorization(owner.threadId())));
                var preview = service.readPreview(new AttachmentPreviewUseCase.PreviewReadRequest(
                        descriptor.previewSessionId(), 0, 64 * 1024));
                assertArrayEquals(BLOB_CONTENT, Base64.getDecoder().decode(preview.contentBase64()));
                assertTrue(preview.eof());
                service.closePreview(descriptor.previewSessionId());
            }
        }
    }

    /** 消息绑定提供附件可读的所有权路径。 */
    private static void insertMessage(Connection connection, String messageId, String threadId,
                                      String turnId, int ordinal) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO messages(message_id,thread_id,turn_id,ordinal,role,blocks_json,created_at) "
                        + "VALUES(?,?,?,?,'USER','[]',?)")) {
            insert.setString(1, messageId);
            insert.setString(2, threadId);
            insert.setString(3, turnId);
            insert.setInt(4, ordinal);
            insert.setString(5, TIME);
            insert.executeUpdate();
        }
    }

    /** 关联用户消息附件，验证迁移后 Thread read/preview 仍按同 Workspace 授权。 */
    private static void insertMessageAttachment(Connection connection, String messageId, String attachmentId,
                                                int ordinal) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO message_attachments(message_id,attachment_id,ordinal,created_at) VALUES(?,?,?,?)")) {
            insert.setString(1, messageId);
            insert.setString(2, attachmentId);
            insert.setInt(3, ordinal);
            insert.setString(4, TIME);
            insert.executeUpdate();
        }
    }

    /** 待处理输入路径使用 pending attachment 表，验证附件授权不只覆盖 message owner。 */
    private static void insertPendingInput(Connection connection, String inputId, String threadId,
                                           String turnId) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO pending_inputs(input_id,thread_id,turn_id,kind,content_json,state,created_at,updated_at) "
                        + "VALUES(?,?,?,'FOLLOW_UP','[]','PENDING',?,?)")) {
            insert.setString(1, inputId);
            insert.setString(2, threadId);
            insert.setString(3, turnId);
            insert.setString(4, TIME);
            insert.setString(5, TIME);
            insert.executeUpdate();
        }
    }

    /** pending attachment 的 ordinal 与唯一身份由旧 schema 同样约束。 */
    private static void insertPendingAttachment(Connection connection, String inputId,
                                                String attachmentId, int ordinal) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO pending_input_attachments(input_id,attachment_id,ordinal,created_at) VALUES(?,?,?,?)")) {
            insert.setString(1, inputId);
            insert.setString(2, attachmentId);
            insert.setInt(3, ordinal);
            insert.setString(4, TIME);
            insert.executeUpdate();
        }
    }

    /** 打开必须失败且保留 V7 之前的备份；该异常表示启动被故障注入阻断。 */
    private static StorageExceptionCarrier assertMigrationFailure(Path databasePath, Path home) {
        var failure = assertThrows(io.github.kongweiguang.ja.foundation.error.StorageException.class,
                () -> JaDatabase.open(DatabaseConfig.of(databasePath, home)));
        return new StorageExceptionCarrier(failure);
    }

    /** 回读证明数据事务因所有权歧义整体回滚，Flyway schema 版本可独立保持在 V7。 */
    private static void assertFailedDataTransactionWasRolledBack(SQLiteDataSource source, String legacyWorkspaceId)
            throws Exception {
        assertEquals("7", scalar(source,
                "SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1"));
        assertEquals("PROJECT", scalar(source,
                "SELECT kind FROM workspaces WHERE workspace_id='" + legacyWorkspaceId + "'"));
        assertEquals(0, scalarLong(source, "SELECT COUNT(*) FROM workspaces WHERE kind='SESSION'"));
        assertEquals(4, scalarLong(source,
                "SELECT COUNT(*) FROM threads WHERE workspace_id='" + legacyWorkspaceId + "'"));
        assertEquals(4, scalarLong(source,
                "SELECT COUNT(*) FROM turn_change_sets WHERE workspace_id='" + legacyWorkspaceId + "'"));
        assertEquals(4, scalarLong(source,
                "SELECT COUNT(*) FROM workspace_write_claims WHERE workspace_id='" + legacyWorkspaceId + "'"));
    }

    /** 通过 lineage root 证明所有侧任务跟随自己的主会话，而两棵树使用不同 Session workspace。 */
    private static void assertSessionTree(JaDatabase database, String rootThread, String childThread,
                                          String expectedWorkspace) throws Exception {
        assertEquals(expectedWorkspace, workspaceId(database, rootThread));
        assertEquals(expectedWorkspace, workspaceId(database, childThread));
        assertEquals("SESSION", scalar(database,
                "SELECT kind FROM workspaces WHERE workspace_id='" + expectedWorkspace + "'"));
        assertEquals(rootThread, scalar(database,
                "SELECT root_thread_id FROM thread_lineage WHERE child_thread_id='" + childThread + "'"));
    }

    /**
     * 生产升级在 lease 内调用 online backup；除完整性与版本外，还回读 WAL 中已提交的 Thread、
     * Attachment 和工作区归属，防止只验证出一个结构完整但丢失业务行的空快照。
     */
    private static List<Long> backupSizes(Path databasePath, String expectedVersion, long expectedThreads,
                                          long expectedAttachments, String legacyWorkspaceId) throws Exception {
        try (var files = Files.list(databasePath.getParent())) {
            List<Path> backups = files.filter(path -> path.getFileName().toString().contains(".pre-v7-"))
                    .toList();
            for (Path backup : backups) {
                SQLiteDataSource snapshot = dataSource(backup);
                assertEquals("ok", scalar(snapshot, "PRAGMA integrity_check"));
                assertEquals(expectedVersion, scalar(snapshot,
                        "SELECT version FROM flyway_schema_history WHERE success=1 "
                                + "ORDER BY installed_rank DESC LIMIT 1"));
                assertEquals(expectedThreads, scalarLong(snapshot, "SELECT COUNT(*) FROM threads"));
                assertEquals(expectedAttachments, scalarLong(snapshot, "SELECT COUNT(*) FROM attachments"));
                if (legacyWorkspaceId != null) {
                    assertEquals("无项目", scalar(snapshot,
                            "SELECT display_name FROM workspaces WHERE workspace_id='" + legacyWorkspaceId + "'"));
                    assertEquals(1, scalarLong(snapshot,
                            "SELECT COUNT(*) FROM threads WHERE thread_id='thr_main_a' AND workspace_id='"
                                    + legacyWorkspaceId + "'"));
                    assertEquals(BLOB_SHA, scalar(snapshot,
                            "SELECT content_sha256 FROM attachments WHERE attachment_id='att_message'"));
                }
            }
            return backups.stream().map(path -> {
                try { return Files.size(path); }
                catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }).toList();
        }
    }

    /** 与生产 datasource 采用相同 WAL 与 FK 约束，确保旧副本和新启动使用同一 SQLite 语义。 */
    private static SQLiteDataSource dataSource(Path path) {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + path.toAbsolutePath().normalize());
        return source;
    }

    /** 打开 JaDatabase 并绑定生产 checkpoint mapper，使测试 close 执行真实 WAL lease 释放路径。 */
    private static JaDatabase openDatabase(Path databasePath, Path home) {
        JaDatabase database = JaDatabase.open(DatabaseConfig.of(databasePath, home));
        database.bindWalCheckpoint(factory(database));
        return database;
    }

    /** 构造消费生产 XML 的 MyBatis factory，用于附件 owner readback 和 WAL checkpoint。 */
    private static SqlSessionFactory factory(JaDatabase database) {
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration(
                new Environment("session-workspace-migration-test", new JdbcTransactionFactory(),
                        database.dataSource()));
        configuration.addMapper(SchemaMapper.class);
        configuration.addMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.AttachmentMapper.class);
        configuration.addMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.HistoryMapper.class);
        configuration.addMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.AgentMapper.class);
        configuration.addMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.CheckpointMapper.class);
        configuration.addMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.ProjectionMapper.class);
        configuration.addMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.RecoveryMapper.class);
        configuration.addMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.InstructionScopeMapper.class);
        configuration.addMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.TaskMapper.class);
        configuration.addMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.SubagentPolicyMapper.class);
        configuration.addMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.InteractionMapper.class);
        configuration.addMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.PlanEvaluationMapper.class);
        configuration.addMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.ThreadDiscoveryMapper.class);
        configuration.addMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.SideChatMapper.class);
        configuration.addMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.SideChatPurgeMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    /** 查询一个标量并关闭每次临时连接，避免测试结果依赖连接级事务状态。 */
    private static String scalar(SQLiteDataSource source, String sql) throws Exception {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), "scalar query returned no row: " + sql);
            return result.getString(1);
        }
    }

    /** 用 JaDatabase 暴露的同一个 SQLite source 做标量回读。 */
    private static String scalar(JaDatabase database, String sql) throws Exception {
        return scalar(database.dataSource(), sql);
    }

    /** 查询计数类 SQL 并避免隐式把 NULL 当作零。 */
    private static long scalarLong(SQLiteDataSource source, String sql) throws Exception {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), "count query returned no row: " + sql);
            return result.getLong(1);
        }
    }

    /** 用 JaDatabase 暴露的同一个 SQLite source 做 count 回读。 */
    private static long scalarLong(JaDatabase database, String sql) throws Exception {
        return scalarLong(database.dataSource(), sql);
    }

    /** 从主 Thread 找出 Java 持久化的 Session workspace identity。 */
    private static String workspaceId(JaDatabase database, String threadId) throws Exception {
        return scalar(database, "SELECT workspace_id FROM threads WHERE thread_id='" + threadId + "'");
    }

    /** 只检查新目录是否为空，不递归读取内容。 */
    private static long childCount(Path directory) throws Exception {
        try (var entries = Files.list(directory)) { return entries.count(); }
    }

    /** 文件 SHA-256 只用于验证旧共享目录内容未因归属迁移而变化。 */
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** 测试 fixture 在类初始化时锁定已知明文的真实 content-address identity。 */
    private static String sha256Unchecked(byte[] bytes) {
        try {
            return sha256(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /** 测试用附件与 Thread 归属事实，避免只验证元数据而漏掉真实读取授权。 */
    private record AttachmentOwner(String attachmentId, String threadId) { }

    /** 包装测试失败原因，便于跨事务故障注入保留原始异常类型。 */
    private record StorageExceptionCarrier(Throwable cause) { }
}
