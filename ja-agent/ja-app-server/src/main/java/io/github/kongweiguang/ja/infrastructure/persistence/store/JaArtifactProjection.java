// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.tool.ToolState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValues;
import io.github.kongweiguang.ja.conversation.domain.model.TurnChangeSet;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TurnChangeSetCodec;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.nullable;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.requireOne;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.sha256;

/** 产物投影验证严格扩展事实及终态快照，正文和父级引用在同一 Store 事务内写入。 */
final class JaArtifactProjection {
    private final ObjectMapper mapper;
    private final TurnChangeSetCodec changeSets;

    /** 所有 codec 共用组合根配置；此协作者只接收连接，不持有数据库生命周期。 */
    JaArtifactProjection(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.changeSets = new TurnChangeSetCodec(mapper);
    }

    /**
     * 工具产物只能来自严格版本化的 Ja 扩展事实，字节长度和摘要都在事务边界重新验证。
     */
    static ToolArtifactSnapshot decodeToolArtifact(JsonObject payload) throws SQLException {
        if (!payload.members().keySet().equals(Set.of("callId", "artifactId", "content", "sha256", "byteLength"))
            || !(payload.get("callId") instanceof JsonText(String value4))
            || !(payload.get("artifactId") instanceof JsonText(String value3))
            || !(payload.get("content") instanceof JsonText(String value2))
            || !(payload.get("sha256") instanceof JsonText(String value1))
            || !(payload.get("byteLength") instanceof JsonNumber(java.math.BigDecimal value))) {
            throw new SQLException("Ja tool artifact payload is malformed");
        }
        byte[] bytes = value2.getBytes(StandardCharsets.UTF_8);
        long declaredLength;
        try {
            declaredLength = value.longValueExact();
        } catch (ArithmeticException failure) {
            throw new SQLException("Ja tool artifact byte length is invalid", failure);
        }
        if (bytes.length > 16 * 1024 * 1024 || declaredLength != bytes.length
            || !value1.matches("[0-9a-f]{64}")
            || !value1.equals(sha256(bytes))) {
            throw new SQLException("Ja tool artifact content digest or size is invalid");
        }
        return new ToolArtifactSnapshot(value4, value3, value2);
    }

    /**
     * 按 UTF-8 字节验证 diff artifact；SQLite character_length 另按 Java 字符数计算展示值。
     */
    static ChangeSetArtifactSnapshot decodeChangeSetArtifact(JsonObject payload) throws SQLException {
        if (!payload.members().keySet().equals(Set.of("artifactId", "content", "sha256", "byteLength"))
            || !(payload.get("artifactId") instanceof JsonText(String value3))
            || !(payload.get("content") instanceof JsonText(String value2))
            || !(payload.get("sha256") instanceof JsonText(String value1))
            || !(payload.get("byteLength") instanceof JsonNumber(java.math.BigDecimal value))) {
            throw new SQLException("Ja change-set artifact payload is malformed");
        }
        byte[] bytes = value2.getBytes(StandardCharsets.UTF_8);
        long declaredLength;
        try {
            declaredLength = value.longValueExact();
        } catch (ArithmeticException failure) {
            throw new SQLException("Ja change-set artifact byte length is invalid", failure);
        }
        if (declaredLength != bytes.length || bytes.length > 2 * 1024 * 1024
            || !value1.matches("[0-9a-f]{64}") || !value1.equals(sha256(bytes))) {
            throw new SQLException("Ja change-set artifact size or digest is invalid");
        }
        return new ChangeSetArtifactSnapshot(value3, value2, value1, declaredLength);
    }

    /**
     * artifact 长度按 Unicode code point 计，必须与历史分页游标语义一致并校验完成展示引用。
     */
    void applyToolArtifacts(Connection connection, AgentStoreTransactionContext transaction,
                                    List<ToolArtifactSnapshot> artifacts,
                                    Map<String, JaProjectionPayloadCodec.ToolPresentationSnapshot> presentations)
            throws SQLException {
        Set<String> calls = new HashSet<>();
        for (ToolArtifactSnapshot artifact : artifacts) {
            if (!calls.add(artifact.callId())) {
                throw new SQLException("duplicate Ja tool artifact call");
            }
            JaProjectionPayloadCodec.ToolPresentationSnapshot presentation = presentations.get(artifact.callId());
            if (presentation == null) {
                throw new SQLException("Ja tool artifact presentation is missing");
            }
            String artifactRef;
            try {
                JsonNode json = mapper.readTree(presentation.presentationJson());
                artifactRef = json.path("artifactId").isTextual() ? json.path("artifactId").textValue() : null;
            } catch (JsonProcessingException invalid) {
                throw new SQLException("Ja tool artifact presentation is malformed", invalid);
            }
            if (!artifact.artifactId().equals(artifactRef)
                || presentation.state() == ToolState.PREPARED || presentation.state() == ToolState.RUNNING) {
                throw new SQLException("Ja tool artifact is not referenced by its completed presentation");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                        INSERT INTO ja_tool_artifact(artifact_id,thread_id,turn_id,call_id,content,character_length,\
                        created_at_ms) \
                        VALUES(?,?,?,?,?,?,?)\
                        """)) {
                statement.setString(1, artifact.artifactId());
                statement.setString(2, transaction.execution().conversation().threadId());
                statement.setString(3, transaction.execution().turnId());
                statement.setString(4, artifact.callId());
                statement.setString(5, artifact.content());
                statement.setInt(6, artifact.content().codePointCount(0, artifact.content().length()));
                statement.setLong(7, transaction.occurredAt().toEpochMilli());
                requireOne(statement.executeUpdate(), "Ja tool artifact insert failed");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ja_tool_call SET artifact_id=? WHERE thread_id=? AND turn_id=? AND call_id=?")) {
                statement.setString(1, artifact.artifactId());
                statement.setString(2, transaction.execution().conversation().threadId());
                statement.setString(3, transaction.execution().turnId());
                statement.setString(4, artifact.callId());
                requireOne(statement.executeUpdate(), "Ja tool artifact has no matching tool row");
            }
        }
    }

    /**
     * 成功终态必须冻结唯一快照，非终态不得预写；失败或取消可保留确实已生成的快照。
     */
    void applyChangeSet(Connection connection, AgentStoreTransactionContext transaction,
                                ExecutionEvent.Terminal terminalEvent, TurnChangeSet changeSet,
                                ChangeSetArtifactSnapshot artifact) throws SQLException {
        if (terminalEvent == null && (changeSet != null || artifact != null)) {
            throw new SQLException("Ja change set is only valid in a terminal transaction");
        }
        if (changeSet == null) {
            if (artifact != null) {
                throw new SQLException("Ja change-set artifact has no parent fact");
            }
            if (terminalEvent != null && terminalEvent.state() == TurnState.COMPLETED) {
                throw new SQLException("completed Ja turn has no frozen change set");
            }
            return;
        }
        if (changeSet.artifactId() == null ? artifact != null
                : artifact == null || !changeSet.artifactId().equals(artifact.artifactId())) {
            throw new SQLException("Ja change-set artifact identity is incomplete");
        }
        String threadId = transaction.execution().conversation().threadId();
        String turnId = transaction.execution().turnId();
        String workspaceId;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT workspace_id FROM ja_conversation_thread WHERE thread_id=?")) {
            query.setString(1, threadId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Ja change set has no owning Thread");
                }
                workspaceId = rows.getString(1);
                if (workspaceId == null || workspaceId.isBlank() || rows.next()) {
                    throw new SQLException("Ja change set Thread workspace is invalid");
                }
            }
        }
        String payload = changeSets.write(changeSet);
        try (PreparedStatement insert = connection.prepareStatement(
                """
                    INSERT INTO ja_turn_change_set(turn_id,thread_id,workspace_id,change_set_json,artifact_id,\
                    committed_at_ms) \
                    VALUES(?,?,?,?,?,?)\
                    """)) {
            insert.setString(1, turnId);
            insert.setString(2, threadId);
            insert.setString(3, workspaceId);
            insert.setString(4, payload);
            nullable(insert, 5, changeSet.artifactId());
            insert.setLong(6, transaction.occurredAt().toEpochMilli());
            requireOne(insert.executeUpdate(), "Ja change set was not persisted");
        }
        if (artifact == null) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(
                """
                    INSERT INTO ja_change_set_artifact(artifact_id,thread_id,turn_id,content,sha256,byte_length,\
                    created_at_ms) \
                    VALUES(?,?,?,?,?,?,?)\
                    """)) {
            insert.setString(1, artifact.artifactId());
            insert.setString(2, threadId);
            insert.setString(3, turnId);
            insert.setString(4, artifact.content());
            insert.setString(5, artifact.sha256());
            insert.setLong(6, artifact.byteLength());
            insert.setLong(7, transaction.occurredAt().toEpochMilli());
            requireOne(insert.executeUpdate(), "Ja change-set artifact was not persisted");
        }
    }

    /**
     * TurnChangeSet 的扩展事实必须与现有 app codec 完全同构，不增加第二套字段解释。
     */
    TurnChangeSet decodeChangeSet(JsonObject payload) throws SQLException {
        try {
            return changeSets.read(mapper.writeValueAsString(JacksonJsonValues.toNode(mapper, payload)));
        } catch (JsonProcessingException | RuntimeException failure) {
            throw new SQLException("Ja change-set payload is invalid", failure);
        }
    }

    /**
     * 验证过字节长度和摘要的展示产物；正文只在同事务内进入 Ja 的 artifact 表。
     *
     * @param callId 模型生成的工具调用标识
     * @param artifactId 产物标识
     * @param content 内容
     */
    record ToolArtifactSnapshot(String callId,
                                String artifactId,
                                String content) {
    }

    /**
     * 终态 diff 快照固定身份与 UTF-8 哈希，供事务提交前后二次校验。
     *
     * @param artifactId 产物标识
     * @param content 内容
     * @param sha256 内容的 SHA-256 摘要
     * @param byteLength 数据长度，单位为字节
     */
    record ChangeSetArtifactSnapshot(String artifactId,
                                     String content,
                                     String sha256,
                                     long byteLength) {
    }
}
