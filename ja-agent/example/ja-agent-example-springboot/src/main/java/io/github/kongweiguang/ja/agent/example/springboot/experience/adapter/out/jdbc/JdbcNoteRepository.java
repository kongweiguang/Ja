// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.experience.adapter.out.jdbc;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.example.springboot.experience.domain.Note;
import io.github.kongweiguang.ja.agent.example.springboot.experience.port.out.NoteRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** PostgreSQL 笔记回执；保留可代理类型以启用 Spring Repository 异常翻译，写入使用 Core Store 借出的事务连接。 */
@Repository
public class JdbcNoteRepository implements NoteRepository {
    private static final String NOTE_TABLE = "ja_agent_demo_note";
    private static final String SOURCE_TABLE = "ja_agent_demo_note_source";
    private final JdbcTemplate jdbc;

    /**
     * 保留宿主连接池供只读管理查询；Core 提交路径单独传入其当前连接。
     */
    public JdbcNoteRepository(DataSource dataSource) {
        jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    /**
     * 建立缺失的示例基线表并严格检查已有列；旧演示库不自动 ALTER 或清空。
     */
    @PostConstruct
    void initializeSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ja_agent_demo_note (
                    call_key text NOT NULL,
                    tenant_id text NOT NULL,
                    user_id text NOT NULL,
                    thread_id text NOT NULL,
                    turn_id text NOT NULL,
                    title text NOT NULL,
                    body text NOT NULL,
                    argument_fingerprint text NOT NULL,
                    created_at_ms bigint NOT NULL
                        DEFAULT (floor(extract(epoch FROM clock_timestamp()) * 1000))::bigint,
                    CONSTRAINT pk_ja_agent_demo_note PRIMARY KEY (call_key)
                )
                """);
        requireColumns(NOTE_TABLE, Map.of(
                "call_key", "text", "tenant_id", "text", "user_id", "text", "thread_id", "text",
                "turn_id", "text", "title", "text", "body", "text", "argument_fingerprint", "text",
                "created_at_ms", "bigint"));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ja_agent_demo_note_source (
                    call_key text NOT NULL,
                    attachment_id text NOT NULL,
                    source_ordinal integer NOT NULL,
                    file_name text NOT NULL,
                    media_type text NOT NULL,
                    size_bytes bigint NOT NULL,
                    sha256 text NOT NULL,
                    CONSTRAINT pk_ja_agent_demo_note_source PRIMARY KEY (call_key, attachment_id),
                    CONSTRAINT uq_ja_agent_demo_note_source_order UNIQUE (call_key, source_ordinal),
                    CONSTRAINT fk_ja_agent_demo_note_source_note FOREIGN KEY (call_key)
                        REFERENCES ja_agent_demo_note (call_key) ON DELETE CASCADE
                )
                """);
        requireColumns(SOURCE_TABLE, Map.of(
                "call_key", "text", "attachment_id", "text", "source_ordinal", "integer",
                "file_name", "text", "media_type", "text", "size_bytes", "bigint", "sha256", "text"));
    }

    /**
     * 拒绝兼容不了当前所有权与来源契约的已有数据库，避免把 CREATE IF NOT EXISTS 当成迁移。
     */
    private void requireColumns(String table, Map<String, String> expected) {
        Map<String, String> actual = new LinkedHashMap<>();
        jdbc.query("SELECT column_name, data_type FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ?",
                (RowCallbackHandler) row -> actual.put(row.getString("column_name"),
                        row.getString("data_type")), table);
        if (!actual.equals(expected)) {
            throw new IllegalStateException("database schema mismatch for " + table
                    + "; use a fresh Spring example database");
        }
    }

    /**
     * 将一份规范化 note.write 写入已开启的 Core 事务；重复 callKey 仅复用完全相同的持久回执。
     */
    @Override
    public Note save(Connection connection, AgentIdentity identity, String callKey, String title, String body,
                     List<String> sourceAttachmentIds, String argumentFingerprint) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(identity, "identity");
        List<String> sources = List.copyOf(sourceAttachmentIds);
        int inserted;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ja_agent_demo_note(call_key, tenant_id, user_id, thread_id, turn_id,
                    title, body, argument_fingerprint)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (call_key) DO NOTHING
                """)) {
            statement.setString(1, callKey);
            statement.setString(2, identity.tenantId());
            statement.setString(3, identity.userId());
            statement.setString(4, identity.threadId());
            statement.setString(5, identity.turnId());
            statement.setString(6, title);
            statement.setString(7, body);
            statement.setString(8, argumentFingerprint);
            inserted = statement.executeUpdate();
        }
        if (inserted == 0) {
            return verifyExisting(connection, identity, callKey, title, body, sources, argumentFingerprint);
        }
        for (int index = 0; index < sources.size(); index++) {
            AttachmentRow source = requireAttachment(connection, identity, sources.get(index));
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO ja_agent_demo_note_source(call_key, attachment_id, source_ordinal,
                        file_name, media_type, size_bytes, sha256)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, callKey);
                statement.setString(2, source.attachmentId());
                statement.setInt(3, index);
                statement.setString(4, source.fileName());
                statement.setString(5, source.mediaType());
                statement.setLong(6, source.sizeBytes());
                statement.setString(7, source.sha256());
                statement.executeUpdate();
            }
        }
        return readByCallKey(connection, callKey).orElseThrow(() -> new SQLException("note receipt was not written"));
    }

    /**
     * 来源只允许引用已由附件 admission 标记、且 owner 和 Thread 完全一致的不可变内容。
     */
    private AttachmentRow requireAttachment(Connection connection, AgentIdentity identity, String attachmentId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT attachment_id, file_name, media_type, size_bytes, sha256
                FROM ja_agent_demo_attachment
                WHERE tenant_id = ? AND user_id = ? AND thread_id = ? AND attachment_id = ?
                    AND referenced_at_ms IS NOT NULL
                FOR KEY SHARE
                """)) {
            statement.setString(1, identity.tenantId());
            statement.setString(2, identity.userId());
            statement.setString(3, identity.threadId());
            statement.setString(4, attachmentId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("note source attachment is not owned by the execution");
                }
                return new AttachmentRow(rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getLong(4), rows.getString(5));
            }
        }
    }

    /**
     * callKey 冲突时锁定旧回执并逐字段比较，防止重试复用另一笔 note 或来源集合。
     */
    private Note verifyExisting(Connection connection, AgentIdentity identity, String callKey, String title,
                                String body, List<String> sources, String fingerprint) throws SQLException {
        Note existing = readByCallKey(connection, callKey)
                .orElseThrow(() -> new SQLException("note call key conflict disappeared"));
        if (!existing.matches(identity.tenantId(), identity.userId(), identity.threadId(), identity.turnId(),
                title, body, sources, fingerprint)) {
            throw new SQLException("note call key conflicts with another committed write");
        }
        return existing;
    }

    /**
     * 未知执行恢复只读取现存回执；查询连接不会触发重写或引用状态变化。
     */
    @Override
    public Optional<Note> findForRecovery(AgentIdentity identity, String callKey) {
        Objects.requireNonNull(identity, "identity");
        return jdbc.query("""
                        SELECT n.call_key, n.tenant_id, n.user_id, n.thread_id, n.turn_id, n.title, n.body,
                            n.argument_fingerprint, n.created_at_ms,
                            COALESCE(array_agg(s.attachment_id ORDER BY s.source_ordinal)
                                FILTER (WHERE s.attachment_id IS NOT NULL), ARRAY[]::text[]) AS source_ids
                        FROM ja_agent_demo_note n
                        LEFT JOIN ja_agent_demo_note_source s ON s.call_key = n.call_key
                        WHERE n.tenant_id = ? AND n.user_id = ? AND n.thread_id = ? AND n.turn_id = ?
                            AND n.call_key = ?
                        GROUP BY n.call_key, n.tenant_id, n.user_id, n.thread_id, n.turn_id, n.title, n.body,
                            n.argument_fingerprint, n.created_at_ms
                        """,
                rows -> rows.next() ? Optional.of(mapNote(rows)) : Optional.empty(), identity.tenantId(),
                identity.userId(), identity.threadId(), identity.turnId(), callKey);
    }

    /**
     * 仅读取当前冻结 Turn 的持久回执，不设置人为数量上限，以恢复所有已提交标题事实。
     */
    @Override
    public List<Note> listForTurn(AgentIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return jdbc.query("""
                        SELECT n.call_key, n.tenant_id, n.user_id, n.thread_id, n.turn_id, n.title, n.body,
                            n.argument_fingerprint, n.created_at_ms,
                            COALESCE(array_agg(s.attachment_id ORDER BY s.source_ordinal)
                                FILTER (WHERE s.attachment_id IS NOT NULL), ARRAY[]::text[]) AS source_ids
                        FROM ja_agent_demo_note n
                        LEFT JOIN ja_agent_demo_note_source s ON s.call_key = n.call_key
                        WHERE n.tenant_id = ? AND n.user_id = ? AND n.thread_id = ? AND n.turn_id = ?
                        GROUP BY n.call_key, n.tenant_id, n.user_id, n.thread_id, n.turn_id, n.title, n.body,
                            n.argument_fingerprint, n.created_at_ms
                        ORDER BY n.created_at_ms, n.call_key
                        """,
                (row, ignored) -> mapNote(row), identity.tenantId(), identity.userId(), identity.threadId(),
                identity.turnId());
    }

    /**
     * 管理页读取限制在服务端当前 owner 与单个 Thread，资料来源按原始顺序附在回执上。
     */
    @Override
    public List<Note> list(String tenantId, String userId, String threadId) {
        return jdbc.query("""
                        SELECT n.call_key, n.tenant_id, n.user_id, n.thread_id, n.turn_id, n.title, n.body,
                            n.argument_fingerprint, n.created_at_ms,
                            COALESCE(array_agg(s.attachment_id ORDER BY s.source_ordinal)
                                FILTER (WHERE s.attachment_id IS NOT NULL), ARRAY[]::text[]) AS source_ids
                        FROM ja_agent_demo_note n
                        LEFT JOIN ja_agent_demo_note_source s ON s.call_key = n.call_key
                        WHERE n.tenant_id = ? AND n.user_id = ? AND n.thread_id = ?
                        GROUP BY n.call_key, n.tenant_id, n.user_id, n.thread_id, n.turn_id, n.title, n.body,
                            n.argument_fingerprint, n.created_at_ms
                        ORDER BY n.created_at_ms DESC, n.call_key DESC LIMIT 100
                        """,
                (row, ignored) -> mapNote(row), tenantId, userId, threadId);
    }

    /**
     * 将事务内回读映射为完整回执，同时装入按保存顺序排列的资料来源快照。
     */
    private Optional<Note> readByCallKey(Connection connection, String callKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT call_key, tenant_id, user_id, thread_id, turn_id, title, body,
                    argument_fingerprint, created_at_ms
                FROM ja_agent_demo_note WHERE call_key = ? FOR UPDATE
                """)) {
            statement.setString(1, callKey);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                List<String> sources = new ArrayList<>();
                try (PreparedStatement sourceStatement = connection.prepareStatement("""
                        SELECT attachment_id FROM ja_agent_demo_note_source
                        WHERE call_key = ? ORDER BY source_ordinal
                        """)) {
                    sourceStatement.setString(1, callKey);
                    try (ResultSet sourceRows = sourceStatement.executeQuery()) {
                        while (sourceRows.next()) {
                            sources.add(sourceRows.getString(1));
                        }
                    }
                }
                return Optional.of(new Note(rows.getString("call_key"), rows.getString("tenant_id"),
                        rows.getString("user_id"), rows.getString("thread_id"), rows.getString("turn_id"),
                        rows.getString("title"), rows.getString("body"), sources,
                        rows.getString("argument_fingerprint"), Instant.ofEpochMilli(rows.getLong("created_at_ms"))));
            }
        }
    }

    /**
     * 读取 JDBC `text[]` 并复制到不可变领域值，避免 ResultSet 生命周期泄漏。
     */
    private Note mapNote(ResultSet row) throws SQLException {
        List<String> sources = new ArrayList<>();
        java.sql.Array array = row.getArray("source_ids");
        if (array != null) {
            Object value = array.getArray();
            if (value instanceof Object[] items) {
                for (Object item : items) {
                    sources.add(Objects.toString(item));
                }
            }
            array.free();
        }
        return new Note(row.getString("call_key"), row.getString("tenant_id"), row.getString("user_id"),
                row.getString("thread_id"), row.getString("turn_id"), row.getString("title"),
                row.getString("body"), sources, row.getString("argument_fingerprint"),
                Instant.ofEpochMilli(row.getLong("created_at_ms")));
    }

    /**
     * 固定 JDBC 读回来源字段，避免 participant 需要把数据库行句柄传出当前调用。
     *
     * @param attachmentId 被笔记引用的附件标识
     * @param fileName 上传时冻结的附件展示名
     * @param mediaType 服务端校验的媒体类型
     * @param sizeBytes 附件原始内容大小
     * @param sha256 附件内容指纹
     */
    private record AttachmentRow(String attachmentId,
                                 String fileName,
                                 String mediaType,
                                 long sizeBytes,
                                 String sha256) {
    }
}
