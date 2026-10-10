// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.attachment.adapter.out.jdbc;

import io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentException;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentMetadata;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.port.out.AttachmentRepository;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActor;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * PostgreSQL bytea 附件存储；Core participant 借用其原 Store Connection 完成引用 fencing。
 */
@Repository
public class JdbcAttachmentStore implements AttachmentRepository {
    private static final String TABLE = "ja_agent_demo_attachment";
    private static final String METADATA_COLUMNS = "attachment_id, thread_id, file_name, media_type, size_bytes, "
            + "sha256, created_at_ms, referenced_at_ms, state";
    private static final RowMapper<AttachmentMetadata> METADATA_MAPPER = JdbcAttachmentStore::metadata;
    private static final RowMapper<StoredAttachment> STORED_MAPPER = JdbcAttachmentStore::stored;
    private static final int REFERENCE_QUERY_BATCH_SIZE = 2_560;
    private static final int MAX_DISCARD_BATCH = 10;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock = Clock.systemUTC();
    private final long draftRetentionMillis;

    /**
     * 绑定 Spring DataSource 与可配置草稿保留期；附件内容始终保存在该 PostgreSQL 数据源中。
     */
    public JdbcAttachmentStore(DataSource dataSource,
                               @Value("${ja.example.attachments.draft-retention-ms:86400000}") long draftRetentionMillis) {
        if (draftRetentionMillis < 1) {
            throw new IllegalArgumentException("attachment draft retention must be positive");
        }
        jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.draftRetentionMillis = draftRetentionMillis;
    }

    /**
     * 只在全新 demo database 上建立附件新基线；已有表只做结构核验，不执行自动修复或迁移。
     */
    @PostConstruct
    public void initializeSchema() {
        Boolean exists = jdbc.queryForObject("SELECT to_regclass(current_schema() || '." + TABLE + "') IS NOT NULL",
                Boolean.class);
        if (!Boolean.TRUE.equals(exists)) {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS ja_agent_demo_attachment (
                        tenant_id text NOT NULL,
                        user_id text NOT NULL,
                        thread_id text NOT NULL,
                        attachment_id text NOT NULL,
                        operation_key text NOT NULL,
                        file_name text NOT NULL,
                        media_type text NOT NULL,
                        size_bytes bigint NOT NULL,
                        sha256 text NOT NULL,
                        created_at_ms bigint NOT NULL,
                        referenced_at_ms bigint,
                        state text NOT NULL,
                        content bytea,
                        CONSTRAINT ck_ja_agent_demo_attachment_size
                            CHECK (size_bytes > 0 AND size_bytes <= 50000000),
                        CONSTRAINT ck_ja_agent_demo_attachment_sha256
                            CHECK (sha256 ~ '^[a-f0-9]{64}$'),
                        CONSTRAINT ck_ja_agent_demo_attachment_state
                            CHECK (state IN ('ACTIVE','DISCARDED','EXPIRED')),
                        CONSTRAINT ck_ja_agent_demo_attachment_content_state
                            CHECK ((state='ACTIVE' AND content IS NOT NULL) OR (state<>'ACTIVE' AND content IS NULL)),
                        CONSTRAINT pk_ja_agent_demo_attachment PRIMARY KEY (tenant_id, thread_id, attachment_id),
                        CONSTRAINT uk_ja_agent_demo_attachment_operation
                            UNIQUE (tenant_id, user_id, thread_id, operation_key)
                    )
                    """);
            jdbc.execute("""
                    CREATE INDEX IF NOT EXISTS idx_ja_agent_demo_attachment_draft_expiry
                        ON ja_agent_demo_attachment (created_at_ms, attachment_id)
                        WHERE state='ACTIVE' AND referenced_at_ms IS NULL
                    """);
        }
        verifySchema();
    }

    /**
     * 上传事务锁定 operation key 对应行；相同不可变指纹回读原结果，变化内容返回冲突。
     */
    @Override
    public AttachmentMetadata upload(CurrentActor actor, String threadId, String operationKey,
                                     String fileName, String mediaType, String sha256, byte[] content) {
        return transactions.execute(status -> {
            requireWritableThread(actor, threadId);
            StoredAttachment prior = findByOperation(actor, threadId, operationKey, true);
            if (prior != null) {
                requireSameUpload(prior.metadata(), fileName, mediaType, content.length, sha256);
                requireActive(prior);
                return prior.metadata();
            }
            String id = "att_" + java.util.UUID.randomUUID().toString().replace("-", "");
            long createdAt = clock.millis();
            int inserted = jdbc.update("""
                    INSERT INTO ja_agent_demo_attachment
                        (tenant_id,user_id,thread_id,attachment_id,operation_key,file_name,media_type,size_bytes,
                         sha256,created_at_ms,state,content)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT (tenant_id,user_id,thread_id,operation_key) DO NOTHING
                    """, actor.tenantId(), actor.userId(), threadId, id, operationKey, fileName, mediaType,
                    content.length, sha256, createdAt, AttachmentState.ACTIVE.name(), content);
            if (inserted == 1) {
                return new AttachmentMetadata(id, threadId, fileName, mediaType, content.length,
                        sha256, Instant.ofEpochMilli(createdAt), false);
            }
            StoredAttachment raced = findByOperation(actor, threadId, operationKey, false);
            if (raced == null) {
                throw new IllegalStateException("attachment upload operation disappeared");
            }
            requireSameUpload(raced.metadata(), fileName, mediaType, content.length, sha256);
            requireActive(raced);
            return raced.metadata();
        });
    }

    /**
     * 只读取元数据列并要求每个请求 ID 属于同一 actor 与 Thread，不访问 bytea 正文；长列表按 SQL 批次读取，
     * 批次大小只限制单条查询，不限制历史中可引用的附件总数。
     */
    @Override
    public List<AttachmentMetadata> metadata(CurrentActor actor, String threadId, List<String> attachmentIds) {
        List<String> ids = sortedIds(attachmentIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<AttachmentMetadata> values = new ArrayList<>(ids.size());
        for (int start = 0; start < ids.size();) {
            int end = start + Math.min(REFERENCE_QUERY_BATCH_SIZE, ids.size() - start);
            List<String> batch = ids.subList(start, end);
            String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
            List<Object> arguments = new ArrayList<>(3 + batch.size());
            arguments.add(actor.tenantId());
            arguments.add(actor.userId());
            arguments.add(threadId);
            arguments.addAll(batch);
            values.addAll(jdbc.query("SELECT " + METADATA_COLUMNS + " FROM " + TABLE
                            + " WHERE tenant_id=? AND user_id=? AND thread_id=? AND state='ACTIVE'"
                            + " AND attachment_id IN (" + placeholders + ") ORDER BY attachment_id",
                    METADATA_MAPPER, arguments.toArray()));
            start = end;
        }
        if (values.size() != ids.size()) {
            throw new AttachmentException(AttachmentException.Reason.NOT_FOUND);
        }
        return List.copyOf(values);
    }

    /**
     * 查询单个 actor 所有 Thread 中的附件元数据；正文预览仍由独立内容查询加载。
     */
    @Override
    public AttachmentMetadata metadata(CurrentActor actor, String attachmentId) {
        return jdbc.query("SELECT " + METADATA_COLUMNS + " FROM " + TABLE
                        + " WHERE tenant_id=? AND user_id=? AND attachment_id=? AND state='ACTIVE'",
                METADATA_MAPPER, actor.tenantId(), actor.userId(), attachmentId).stream().findFirst()
                .orElseThrow(() -> new AttachmentException(AttachmentException.Reason.NOT_FOUND));
    }

    /**
     * actor 范围内读取单个文件供浏览器预览；只在该明确内容请求中物化 bytea。
     */
    @Override
    public AttachmentRepository.AttachmentContent content(CurrentActor actor, String threadId, String attachmentId) {
        List<AttachmentRepository.AttachmentContent> result = jdbc.query("SELECT " + METADATA_COLUMNS + ", content FROM " + TABLE
                        + " WHERE tenant_id=? AND user_id=? AND thread_id=? AND attachment_id=? AND state='ACTIVE'",
                (rows, rowNum) -> new AttachmentRepository.AttachmentContent(
                        METADATA_MAPPER.mapRow(rows, rowNum), rows.getBytes("content")),
                actor.tenantId(), actor.userId(), threadId, attachmentId);
        if (result.isEmpty()) {
            throw new AttachmentException(AttachmentException.Reason.NOT_FOUND);
        }
        return result.getFirst();
    }

    /**
     * 按稳定顺序锁定尚未进入持久输入的草稿；保留操作键 tombstone，避免重试产生新附件身份。
     */
    @Override
    public List<String> discard(CurrentActor actor, String threadId, List<String> attachmentIds) {
        List<String> ids = sortedIds(attachmentIds, MAX_DISCARD_BATCH);
        return transactions.execute(status -> {
            List<String> discarded = new ArrayList<>();
            for (String id : ids) {
                StoredAttachment current = jdbc.query("SELECT " + METADATA_COLUMNS + " FROM " + TABLE
                                + " WHERE tenant_id=? AND user_id=? AND thread_id=? AND attachment_id=? FOR UPDATE",
                        STORED_MAPPER, actor.tenantId(), actor.userId(), threadId, id)
                        .stream().findFirst().orElse(null);
                if (current == null) {
                    discarded.add(id);
                    continue;
                }
                if (current.state() != AttachmentState.ACTIVE) {
                    discarded.add(id);
                    continue;
                }
                if (current.metadata().referenced()) {
                    throw new AttachmentException(AttachmentException.Reason.CONFLICT);
                }
                jdbc.update("UPDATE " + TABLE + " SET state='DISCARDED', content=NULL"
                                + " WHERE tenant_id=? AND user_id=? AND thread_id=? AND attachment_id=?"
                                + " AND state='ACTIVE' AND referenced_at_ms IS NULL",
                        actor.tenantId(), actor.userId(), threadId, id);
                discarded.add(id);
            }
            return List.copyOf(discarded);
        });
    }

    /**
     * Store participant 在原 JDBC Connection 中原子记录输入引用，与消息事实共同提交或回滚。
     */
    @Override
    public void reference(Connection connection, AgentIdentity identity, Collection<String> attachmentIds)
            throws SQLException {
        List<String> ids = sortedIds(attachmentIds);
        for (String id : ids) {
            try (PreparedStatement query = connection.prepareStatement("SELECT attachment_id FROM " + TABLE
                    + " WHERE tenant_id=? AND user_id=? AND thread_id=? AND attachment_id=? AND state='ACTIVE' FOR UPDATE")) {
                query.setString(1, identity.tenantId());
                query.setString(2, identity.userId());
                query.setString(3, identity.threadId());
                query.setString(4, id);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw new SQLException("input attachment ownership is missing");
                    }
                }
            }
            try (PreparedStatement update = connection.prepareStatement("UPDATE " + TABLE
                    + " SET referenced_at_ms=COALESCE(referenced_at_ms,?)"
                    + " WHERE tenant_id=? AND user_id=? AND thread_id=? AND attachment_id=? AND state='ACTIVE'")) {
                update.setLong(1, clock.millis());
                update.setString(2, identity.tenantId());
                update.setString(3, identity.userId());
                update.setString(4, identity.threadId());
                update.setString(5, id);
                if (update.executeUpdate() != 1) {
                    throw new SQLException("input attachment reference failed");
                }
            }
        }
    }

    /**
     * Store participant 只读确认笔记来源均已被当前 Thread 的持久输入引用；较长来源列表拆成有界 SQL 查询，
     * 不限制同一执行可引用的历史资料总数。
     */
    @Override
    public List<AttachmentMetadata> requireOwned(Connection connection, AgentIdentity identity,
                                                  Collection<String> attachmentIds) throws SQLException {
        List<String> ids = sortedIds(attachmentIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<AttachmentMetadata> results = new ArrayList<>(ids.size());
        for (int start = 0; start < ids.size();) {
            int end = start + Math.min(REFERENCE_QUERY_BATCH_SIZE, ids.size() - start);
            List<String> batch = ids.subList(start, end);
            String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
            try (PreparedStatement query = connection.prepareStatement("SELECT " + METADATA_COLUMNS + " FROM " + TABLE
                    + " WHERE tenant_id=? AND user_id=? AND thread_id=? AND referenced_at_ms IS NOT NULL"
                    + " AND state='ACTIVE' AND attachment_id IN (" + placeholders + ") ORDER BY attachment_id")) {
                query.setString(1, identity.tenantId());
                query.setString(2, identity.userId());
                query.setString(3, identity.threadId());
                int index = 4;
                for (String id : batch) {
                    query.setString(index++, id);
                }
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        results.add(METADATA_MAPPER.mapRow(rows, results.size()));
                    }
                }
            }
            start = end;
        }
        if (results.size() != ids.size()) {
            throw new SQLException("note source attachment ownership is missing");
        }
        return List.copyOf(results);
    }

    /**
     * ManagedAttachmentReader 只按 Core 给出的 Thread 与附件 ID 取受引用文件的一个 64 KiB 窗口。
     */
    @Override
    public AttachmentRepository.ReadSlice read(ManagedAttachmentReader.ReadRequest request) {
        int offset = Math.toIntExact(request.offsetBytes());
        List<AttachmentRepository.ReadSlice> slices = jdbc.query("""
                SELECT a.attachment_id,a.thread_id,a.file_name,a.media_type,a.size_bytes,a.sha256,
                       a.created_at_ms,a.referenced_at_ms,a.state,
                       substring(a.content FROM ? FOR ?) AS content_slice
                FROM ja_agent_demo_attachment AS a
                JOIN ja_agent_demo_thread AS t
                  ON t.tenant_id=a.tenant_id AND t.user_id=a.user_id AND t.thread_id=a.thread_id
                WHERE a.thread_id=? AND a.attachment_id=? AND a.referenced_at_ms IS NOT NULL AND a.state='ACTIVE'
                ORDER BY a.tenant_id,a.user_id
                """, (rows, rowNum) -> new AttachmentRepository.ReadSlice(metadata(rows, rowNum), rows.getBytes("content_slice")),
                offset + 1, request.maxBytes(), request.threadId(), request.attachmentId());
        if (slices.size() != 1) {
            throw new AttachmentException(AttachmentException.Reason.NOT_FOUND);
        }
        return slices.getFirst();
    }

    /**
     * 每个周期清空超过配置期限且仍未绑定输入的草稿正文；状态 tombstone 保留操作键幂等性。
     */
    @Scheduled(fixedDelayString = "${ja.example.attachments.cleanup-interval-ms:3600000}")
    @Override
    public void deleteExpiredDrafts() {
        long expiry;
        try {
            expiry = Math.subtractExact(clock.millis(), draftRetentionMillis);
        } catch (ArithmeticException tooOld) {
            expiry = Long.MIN_VALUE;
        }
        jdbc.update("UPDATE " + TABLE + " SET state='EXPIRED', content=NULL"
                + " WHERE state='ACTIVE' AND referenced_at_ms IS NULL AND created_at_ms < ?", expiry);
    }

    /**
     * 通过 operation key 回读既有元数据，不加载文件正文。
     */
    private StoredAttachment findByOperation(CurrentActor actor, String threadId, String operationKey, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbc.query("SELECT " + METADATA_COLUMNS + " FROM " + TABLE
                        + " WHERE tenant_id=? AND user_id=? AND thread_id=? AND operation_key=?" + suffix,
                STORED_MAPPER, actor.tenantId(), actor.userId(), threadId, operationKey).stream().findFirst().orElse(null);
    }

    /**
     * 上传时再次校验 Thread owner 和可写状态，关闭 controller 检查与存储动作间的身份漂移。
     */
    private void requireWritableThread(CurrentActor actor, String threadId) {
        List<Boolean> archived = jdbc.query("SELECT is_archived FROM ja_agent_demo_thread"
                        + " WHERE tenant_id=? AND user_id=? AND thread_id=? FOR SHARE",
                (rows, row) -> rows.getBoolean(1), actor.tenantId(), actor.userId(), threadId);
        if (archived.isEmpty()) {
            throw new AttachmentException(AttachmentException.Reason.NOT_FOUND);
        }
        if (archived.getFirst()) {
            throw new AttachmentException(AttachmentException.Reason.CONFLICT);
        }
    }

    /**
     * 对 key 重试使用完整服务端文件指纹，避免同一操作键悄悄改绑另一份内容。
     */
    private static void requireSameUpload(AttachmentMetadata prior, String fileName, String mediaType,
                                         long sizeBytes, String sha256) {
        if (!prior.fileName().equals(fileName) || !prior.mediaType().equals(mediaType)
                || prior.sizeBytes() != sizeBytes || !prior.sha256().equals(sha256)) {
            throw new AttachmentException(AttachmentException.Reason.CONFLICT);
        }
    }

    /** 同一操作键命中已清理草稿时要求客户端换键重新上传，保持原 ID 与内容生命周期不可逆。 */
    private static void requireActive(StoredAttachment prior) {
        if (prior.state() != AttachmentState.ACTIVE) {
            throw new AttachmentException(AttachmentException.Reason.GONE);
        }
    }

    /** 验证附件 ID 并排序，所有多附件锁定与读取都采用同一稳定顺序。 */
    private static List<String> sortedIds(Collection<String> attachmentIds) {
        if (attachmentIds == null) {
            throw new AttachmentException(AttachmentException.Reason.INVALID);
        }
        TreeSet<String> ids = new TreeSet<>();
        for (String value : attachmentIds) {
            if (value == null || !value.matches("att_[A-Za-z0-9_.:-]{1,124}")) {
                throw new AttachmentException(AttachmentException.Reason.INVALID);
            }
            ids.add(value);
        }
        return List.copyOf(ids);
    }

    /** 在有明确单次操作上限的入口先拒绝超量列表，避免为无效请求建立排序快照。 */
    private static List<String> sortedIds(Collection<String> attachmentIds, int maximum) {
        if (attachmentIds == null) {
            throw new AttachmentException(AttachmentException.Reason.INVALID);
        }
        if (attachmentIds.size() > maximum) {
            throw new AttachmentException(AttachmentException.Reason.INVALID);
        }
        return sortedIds(attachmentIds);
    }

    /**
     * 将 JDBC 目录投影成不含 bytea 的 metadata record；rowNum 是 RowMapper 契约参数，此处按列名映射。
     */
    @SuppressWarnings("PMD.UnusedFormalParameter")
    private static AttachmentMetadata metadata(ResultSet rows, int rowNum) throws SQLException {
        rows.getLong("referenced_at_ms");
        boolean referenced = !rows.wasNull();
        return new AttachmentMetadata(rows.getString("attachment_id"), rows.getString("thread_id"),
                rows.getString("file_name"), rows.getString("media_type"), rows.getLong("size_bytes"),
                rows.getString("sha256"), Instant.ofEpochMilli(rows.getLong("created_at_ms")), referenced);
    }

    /** 操作键查询还需读取草稿生命周期，以区分有效重试和已清理内容。 */
    private static StoredAttachment stored(ResultSet rows, int rowNum) throws SQLException {
        return new StoredAttachment(metadata(rows, rowNum), AttachmentState.valueOf(rows.getString("state")));
    }

    /**
     * 对新基线数据库核对精确列集合与 PostgreSQL 类型，不自动添加缺失列或约束。
     */
    private void verifySchema() {
        Map<String, ColumnDefinition> actual = jdbc.query("""
                SELECT column_name,data_type,is_nullable FROM information_schema.columns
                WHERE table_schema=current_schema() AND table_name=?
                """, rows -> {
            java.util.LinkedHashMap<String, ColumnDefinition> columns = new java.util.LinkedHashMap<>();
            while (rows.next()) {
                columns.put(rows.getString("column_name"), new ColumnDefinition(rows.getString("data_type"),
                        "YES".equals(rows.getString("is_nullable"))));
            }
            return Map.copyOf(columns);
        }, TABLE);
        Map<String, ColumnDefinition> expected = Map.ofEntries(
                Map.entry("tenant_id", new ColumnDefinition("text", false)),
                Map.entry("user_id", new ColumnDefinition("text", false)),
                Map.entry("thread_id", new ColumnDefinition("text", false)),
                Map.entry("attachment_id", new ColumnDefinition("text", false)),
                Map.entry("operation_key", new ColumnDefinition("text", false)),
                Map.entry("file_name", new ColumnDefinition("text", false)),
                Map.entry("media_type", new ColumnDefinition("text", false)),
                Map.entry("size_bytes", new ColumnDefinition("bigint", false)),
                Map.entry("sha256", new ColumnDefinition("text", false)),
                Map.entry("created_at_ms", new ColumnDefinition("bigint", false)),
                Map.entry("referenced_at_ms", new ColumnDefinition("bigint", true)),
                Map.entry("state", new ColumnDefinition("text", false)),
                Map.entry("content", new ColumnDefinition("bytea", true)));
        if (!actual.equals(expected)) {
            throw new IllegalStateException("existing demo attachment schema is incompatible; use a fresh demo database");
        }
        Set<String> actualConstraints = Set.copyOf(jdbc.query(
                "SELECT conname FROM pg_constraint WHERE conrelid=to_regclass(current_schema() || '.' || ?)",
                (rows, rowNum) -> rows.getString(1), TABLE));
        Set<String> expectedConstraints = Set.of(
                "pk_ja_agent_demo_attachment", "uk_ja_agent_demo_attachment_operation",
                "ck_ja_agent_demo_attachment_size", "ck_ja_agent_demo_attachment_sha256",
                "ck_ja_agent_demo_attachment_state", "ck_ja_agent_demo_attachment_content_state");
        Integer draftIndex = jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname=current_schema()"
                + " AND tablename=? AND indexname='idx_ja_agent_demo_attachment_draft_expiry'", Integer.class, TABLE);
        if (!actualConstraints.equals(expectedConstraints) || !Integer.valueOf(1).equals(draftIndex)) {
            throw new IllegalStateException("existing demo attachment constraints are incompatible; use a fresh demo database");
        }
    }

    /** 轻量列定义还包括 nullability，确保新基线允许 tombstone 清空 bytea。
     *
     * @param dataType PostgreSQL 实际列类型名称
     * @param nullable 列是否允许保存 SQL NULL
     */
    private record ColumnDefinition(String dataType,
                                    boolean nullable) { }

    /** 数据库操作键命中结果将公开 metadata 与内部状态分开投影。
     *
     * @param metadata 可安全返回给调用方的附件元数据
     * @param state 用于幂等与清理决策的持久状态
     */
    private record StoredAttachment(AttachmentMetadata metadata,
                                    AttachmentState state) { }

    /** 清理状态只保留最小幂等审计字段，不再保留原文件 bytea。 */
    private enum AttachmentState {
        /** 附件可继续读取或引用。 */
        ACTIVE,
        /** 草稿已显式丢弃，内容不再保留。 */
        DISCARDED,
        /** 未引用草稿已超过清理期限。 */
        EXPIRED
    }

}
