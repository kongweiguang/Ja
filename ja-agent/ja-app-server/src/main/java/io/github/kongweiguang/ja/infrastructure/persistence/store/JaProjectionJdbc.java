// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;

/** 参与器内部共享精确记录读取、序号与 JDBC 校验；不创建连接或持有提交和关闭权。 */
final class JaProjectionJdbc {
    /** 仅作为内部无状态 JDBC 辅助类，禁止产生第二事务 owner。 */
    private JaProjectionJdbc() {
    }

    /**
     * 固定长度 SHA-256 由 JDK 提供，摘要比较发生在写库前并不触发任何外部副作用。
     */
    static String sha256(byte[] bytes) throws SQLException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new SQLException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 把 RecordWrite 转为共享 decoder 需要的临时有序记录；序号只用于满足 codec 契约，不写入 Store。
     */
    static AgentStore.StoredRecord stored(AgentStore.RecordWrite value, long sequence) {
        return new AgentStore.StoredRecord(value.recordId(), value.recordType(), value.payloadSchemaVersion(),
                sequence, value.payload(), value.occurredAt());
    }

    /**
     * admission 正文必须来自当前事务，避免把历史 queued record 引用混入新 Mailbox 绑定。
     */
    static AgentStore.StoredRecord transactionRecord(AgentStoreTransactionContext transaction,
                                                             String recordId) throws SQLException {
        AgentStore.StoredRecord match = null;
        for (int index = 0; index < transaction.records().size(); index++) {
            AgentStore.RecordWrite record = transaction.records().get(index);
            if (!record.recordId().equals(recordId)) {
                continue;
            }
            if (match != null) {
                throw new SQLException("Ja transaction repeats a queued message record");
            }
            match = stored(record, index + 1L);
        }
        if (match == null) {
            throw new SQLException("Ja mailbox ENQUEUE body is not in the current transaction");
        }
        return match;
    }

    /**
     * Timeline 顺序号覆盖同一 Core message 派生的多个可见 item，不从 Message ordinal 推导。
     */
    static long nextTimelineSequence(Connection connection, String threadId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(sequence),0)+1 FROM ja_conversation_timeline_entry WHERE thread_id=?")) {
            statement.setString(1, threadId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Ja timeline sequence query returned no row");
                }
                return rows.getLong(1);
            }
        }
    }

    /**
     * message 行生成的内部关联 ID 只用于不同 Timeline 类别互不冲突，不代替模型 message identity。
     */
    static String derivedItemId(String identity, String suffix) {
        return identity + "_" + suffix;
    }

    /**
     * 内容寻址只用于 Ja presentation 主键，不参与权限核验或用户授权。
     */
    static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 只保留显式空值，不经 JDBC 字符串重载推测 null 的类型。
     */
    static void nullable(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.VARCHAR);
        }
        else {
            statement.setString(index, value);
        }
    }

    /**
     * 数字 null 与零用量含义不同，因此对可空计数统一显式设置 BIGINT NULL。
     */
    static void nullable(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        }
        else {
            statement.setLong(index, value);
        }
    }

    /**
     * 仅当稳定工具身份唯一命中时判断投影是否存在，重复身份必须中止同连接事务。
     */
    static boolean exists(Connection connection, String sql, String... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return false;
                }
                if (rows.next()) {
                    throw new SQLException("Ja tool projection identity is ambiguous");
                }
                return true;
            }
        }
    }

    /**
     * JDBC wrapper 未影响精确一行表示持久身份丢失或 CAS 失败，不能继续提交局部投影。
     */
    static void requireOne(int changed, String message) throws SQLException {
        if (changed != 1) {
            throw new SQLException(message);
        }
    }

    /**
     * 按完整 execution 身份读取唯一 Store 行；调用方保留各自的缺失与歧义错误语义。
     */
    static AgentStore.StoredRecord readStoredRecord(Connection connection,
                                                     AgentStoreTransactionContext transaction, String recordId,
                                                     String missingMessage, String duplicateMessage) throws SQLException {
        String sql = """
            SELECT record_type,payload_schema_version,record_sequence,payload_json,occurred_at_ms \
            FROM agent_record WHERE tenant_id=? AND thread_id=? AND turn_id=? \
            AND record_id=?\
            """;
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, transaction.execution().conversation().tenantId());
            query.setString(2, transaction.execution().conversation().threadId());
            query.setString(3, transaction.execution().turnId());
            query.setString(4, recordId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException(missingMessage);
                }
                AgentStore.StoredRecord record = new AgentStore.StoredRecord(recordId, rows.getString(1),
                        rows.getInt(2), rows.getLong(3), new JacksonJsonValueCodec().decodeObject(rows.getString(4)), Instant.ofEpochMilli(rows.getLong(5)));
                if (rows.next()) {
                    throw new SQLException(duplicateMessage);
                }
                return record;
            }
        }
    }

    /**
     * 领域集合编码失败时中止核心事务，不能把界面请求退化成不完整 JSON。
     */
    static String encode(ObjectMapper mapper, Object value) throws SQLException {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new SQLException("cannot encode Ja interaction projection", failure);
        }
    }
}
