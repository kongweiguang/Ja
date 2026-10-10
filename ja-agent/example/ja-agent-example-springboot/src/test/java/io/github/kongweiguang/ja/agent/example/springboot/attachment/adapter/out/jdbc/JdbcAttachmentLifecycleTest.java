// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.attachment.adapter.out.jdbc;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentException;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentMetadata;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActor;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用隔离 PostgreSQL 验证附件草稿墓碑及同事务引用与回收之间的行锁边界。
 */
final class JdbcAttachmentLifecycleTest {
    private static final CurrentActor ACTOR = new CurrentActor("tenant_demo", "user_demo");
    private static final String THREAD_ID = "thread_attachment_lifecycle";
    private static final AgentIdentity IDENTITY = new AgentIdentity(
            ACTOR.tenantId(), THREAD_ID, "turn_attachment_lifecycle", ACTOR.userId());
    private static final String FILE_NAME = "evidence.pdf";
    private static final String MEDIA_TYPE = "application/pdf";
    private static final long DRAFT_RETENTION_MILLIS = 86_400_000L;
    private static final byte[] ORIGINAL_CONTENT = "immutable attachment bytes".getBytes(StandardCharsets.UTF_8);

    /** 过期草稿必须清空 bytea、保留操作键，并区分原内容重试与变更内容重试。 */
    @Test
    void expiresUnreferencedDraftAndRetainsOperationTombstone() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start()) {
            DataSource dataSource = postgres.getPostgresDatabase();
            JdbcAttachmentStore store = openStore(dataSource);
            AttachmentMetadata uploaded = uploadDraft(store, "expire_draft", ORIGINAL_CONTENT);
            setDraftCreatedAt(dataSource, uploaded.id(), 1L);

            store.deleteExpiredDrafts();

            RowState expired = rowState(dataSource, uploaded.id());
            assertEquals("EXPIRED", expired.state());
            assertFalse(expired.referenced());
            assertNull(expired.content());
            AttachmentException sameContent = assertThrows(AttachmentException.class,
                    () -> uploadDraft(store, "expire_draft", ORIGINAL_CONTENT));
            assertEquals(AttachmentException.Reason.GONE, sameContent.reason());
            assertEquals(410, sameContent.reason().status());
            byte[] changedContent = ORIGINAL_CONTENT.clone();
            changedContent[0] ^= 1;
            AttachmentException changedContentRetry = assertThrows(AttachmentException.class,
                    () -> uploadDraft(store, "expire_draft", changedContent));
            assertEquals(AttachmentException.Reason.CONFLICT, changedContentRetry.reason());
            assertEquals(409, changedContentRetry.reason().status());
        }
    }

    /** 引用与 discard 竞争时，提交引用的一方必须保住字节；discard 胜出时引用只能失败闭合。 */
    @Test
    void referenceRacingWithDiscardCannotLoseCommittedContent() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start()) {
            DataSource dataSource = postgres.getPostgresDatabase();
            JdbcAttachmentStore store = openStore(dataSource);
            AttachmentMetadata uploaded = uploadDraft(store, "discard_race", ORIGINAL_CONTENT);

            RaceResult race = raceReferenceWith(store, dataSource, uploaded.id(),
                    () -> store.discard(ACTOR, THREAD_ID, List.of(uploaded.id())));

            if (race.reference().committed()) {
                AttachmentException discardFailure = assertInstanceOf(
                        AttachmentException.class, race.mutationFailure());
                assertEquals(AttachmentException.Reason.CONFLICT, discardFailure.reason());
                assertActiveReferencedBytes(dataSource, uploaded.id(), ORIGINAL_CONTENT);
            } else {
                assertMissingReference(race.reference().failure());
                assertNull(race.mutationFailure());
                assertTombstone(dataSource, uploaded.id(), "DISCARDED");
            }
        }
    }

    /** 过期回收与同事务引用竞争时，已提交引用不得被清理语句清空。 */
    @Test
    void referenceRacingWithExpiryCleanupCannotLoseCommittedContent() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start()) {
            DataSource dataSource = postgres.getPostgresDatabase();
            JdbcAttachmentStore store = openStore(dataSource);
            AttachmentMetadata uploaded = uploadDraft(store, "cleanup_race", ORIGINAL_CONTENT);
            setDraftCreatedAt(dataSource, uploaded.id(), 1L);

            RaceResult race = raceReferenceWith(store, dataSource, uploaded.id(), store::deleteExpiredDrafts);

            assertNull(race.mutationFailure());
            if (race.reference().committed()) {
                assertActiveReferencedBytes(dataSource, uploaded.id(), ORIGINAL_CONTENT);
            } else {
                assertMissingReference(race.reference().failure());
                assertTombstone(dataSource, uploaded.id(), "EXPIRED");
            }
        }
    }

    /** 准备最小 demo Thread owner 行后显式初始化生产 JDBC Store，避免把附件逻辑替换为测试实现。 */
    private static JdbcAttachmentStore openStore(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE ja_agent_demo_thread (
                        tenant_id text NOT NULL,
                        user_id text NOT NULL,
                        thread_id text NOT NULL,
                        is_archived boolean NOT NULL,
                        PRIMARY KEY (tenant_id, user_id, thread_id)
                    )
                    """);
            statement.execute("INSERT INTO ja_agent_demo_thread VALUES ('tenant_demo','user_demo',"
                    + "'thread_attachment_lifecycle',false)");
        }
        JdbcAttachmentStore store = new JdbcAttachmentStore(dataSource, DRAFT_RETENTION_MILLIS);
        store.initializeSchema();
        return store;
    }

    /** 走真实 Store 上传事务并生成与文件字节一致的稳定指纹。 */
    private static AttachmentMetadata uploadDraft(JdbcAttachmentStore store, String operationKey, byte[] content) {
        return store.upload(ACTOR, THREAD_ID, operationKey, FILE_NAME, MEDIA_TYPE, sha256(content), content);
    }

    /** 只推进测试草稿的创建时间；过期策略与清理 SQL 仍由生产 Store 执行。 */
    private static void setDraftCreatedAt(DataSource dataSource, String attachmentId, long createdAtMillis)
            throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement update = connection.prepareStatement(
                "UPDATE ja_agent_demo_attachment SET created_at_ms=? WHERE tenant_id=? AND user_id=? "
                        + "AND thread_id=? AND attachment_id=?")) {
            update.setLong(1, createdAtMillis);
            update.setString(2, ACTOR.tenantId());
            update.setString(3, ACTOR.userId());
            update.setString(4, THREAD_ID);
            update.setString(5, attachmentId);
            assertEquals(1, update.executeUpdate());
        }
    }

    /** 回读持久状态与 bytea，直接验证清理/竞争没有只改变元数据而遗留或误删正文。 */
    private static RowState rowState(DataSource dataSource, String attachmentId) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(
                "SELECT state, content, referenced_at_ms FROM ja_agent_demo_attachment "
                        + "WHERE tenant_id=? AND user_id=? AND thread_id=? AND attachment_id=?")) {
            query.setString(1, ACTOR.tenantId());
            query.setString(2, ACTOR.userId());
            query.setString(3, THREAD_ID);
            query.setString(4, attachmentId);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next(), "attachment tombstone must remain addressable by its operation key");
                RowState state = new RowState(rows.getString("state"), rows.getBytes("content"),
                        rows.getObject("referenced_at_ms") != null);
                assertFalse(rows.next());
                return state;
            }
        }
    }

    /** 过期或丢弃只能形成无正文的未引用 tombstone。 */
    private static void assertTombstone(DataSource dataSource, String attachmentId, String expectedState)
            throws SQLException {
        RowState state = rowState(dataSource, attachmentId);
        assertEquals(expectedState, state.state());
        assertNull(state.content());
        assertFalse(state.referenced());
    }

    /** 成功引用必须保留 ACTIVE 状态、原始字节和持久引用时间。 */
    private static void assertActiveReferencedBytes(DataSource dataSource, String attachmentId, byte[] expected)
            throws SQLException {
        RowState state = rowState(dataSource, attachmentId);
        assertEquals("ACTIVE", state.state());
        assertTrue(state.referenced());
        assertArrayEquals(expected, state.content());
    }

    /** 竞争者先终结草稿时，只接受 Store 明确的缺失引用 SQL 失败。 */
    private static void assertMissingReference(Throwable failure) {
        SQLException missing = assertInstanceOf(SQLException.class, failure);
        assertEquals("input attachment ownership is missing", missing.getMessage());
    }

    /** 用双门闩同时放行两种真实数据库变更，返回两方结果供调用测试验证互斥状态。 */
    private static RaceResult raceReferenceWith(JdbcAttachmentStore store, DataSource dataSource,
                                                String attachmentId, Runnable concurrentMutation) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<ReferenceAttempt> reference = workers.submit(() -> referenceAfterStart(
                    store, dataSource, attachmentId, ready, start));
            Future<Throwable> mutation = workers.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    concurrentMutation.run();
                    return null;
                } catch (RuntimeException failure) {
                    return failure;
                }
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS), "both database operations must reach the race barrier");
            start.countDown();
            return new RaceResult(reference.get(15, TimeUnit.SECONDS), mutation.get(15, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    /** 在独立 Connection 中调用 Store participant API，并将引用与数据库提交置于同一事务。 */
    private static ReferenceAttempt referenceAfterStart(JdbcAttachmentStore store, DataSource dataSource,
                                                         String attachmentId, CountDownLatch ready,
                                                         CountDownLatch start) throws SQLException, InterruptedException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            ready.countDown();
            start.await();
            try {
                store.reference(connection, IDENTITY, List.of(attachmentId));
                connection.commit();
                return new ReferenceAttempt(true, null);
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                return new ReferenceAttempt(false, failure);
            }
        }
    }

    /** 计算生产上传路径使用的 SHA-256 指纹。 */
    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }

    /**
     * 保留每个竞争分支的 SQL 状态，便于只接受引用先提交或 tombstone 先提交两种结果。
     *
     * @param committed 引用事务是否先提交
     * @param failure 未提交时观察到的事务失败
     */
    private record ReferenceAttempt(boolean committed,
                                    Throwable failure) { }

    /**
     * 两个并发事务的结果快照。
     *
     * @param reference 附件引用事务的提交结果
     * @param mutationFailure 并发状态变更事务的失败
     */
    private record RaceResult(ReferenceAttempt reference,
                              Throwable mutationFailure) { }

    /**
     * 直接读取的 PostgreSQL 行状态，仅供断言比较，不映射成生产模型。
     *
     * @param state 存储层当前状态
     * @param content 数据库实际保存的附件字节
     * @param referenced 是否已建立持久输入引用
     */
    private record RowState(String state,
                            byte[] content,
                            boolean referenced) { }
}
