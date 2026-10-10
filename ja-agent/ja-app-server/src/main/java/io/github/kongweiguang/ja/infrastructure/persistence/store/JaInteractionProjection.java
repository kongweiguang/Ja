// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFact;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.Approval;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswerSubmission;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionWait;
import io.github.kongweiguang.ja.agent.domain.json.JsonNull;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ControlPayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.JacksonExecutionEventCodec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.encode;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.nullable;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.requireOne;

/** 等待投影维护交互、审批与答案回执；授权仍由 Core typed wait 决定，失败回滚整个执行提交。 */
final class JaInteractionProjection {
    private final ObjectMapper mapper;
    private final ControlPayloadCodec controls;
    private final JsonValueCodec jsonValues;
    private final JacksonExecutionEventCodec executionEvents;

    /** 所有 codec 共用组合根配置；此协作者只接收连接，不持有数据库生命周期。 */
    JaInteractionProjection(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.controls = new ControlPayloadCodec(mapper);
        this.jsonValues = new JacksonJsonValueCodec(mapper);
        this.executionEvents = new JacksonExecutionEventCodec(mapper);
    }

    /**
     * Approval 行是 Ja UI 投影；准许或拒绝值仅从已提交的核心审批事实复制。
     */
    static void applyApprovals(Connection connection, AgentStoreTransactionContext transaction,
                                       List<ExecutionFact> values) throws SQLException {
        for (ExecutionFact fact : values) {
            if (!(fact instanceof Approval(
                    String approvalId, String callId,
                    io.github.kongweiguang.ja.agent.domain.approval.ApprovalDecision decision1, Instant expiresAt
            ))) {
                continue;
            }
            String threadId = transaction.execution().conversation().threadId();
            String turnId = transaction.execution().turnId();
            String decision = decision1 == null ? null : decision1.name();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT decision FROM ja_approval WHERE approval_id=? AND thread_id=? AND turn_id=? AND call_id=?")) {
                query.setString(1, approvalId);
                query.setString(2, threadId);
                query.setString(3, turnId);
                query.setString(4, callId);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        try (PreparedStatement insert = connection.prepareStatement(
                                """
                                    INSERT INTO ja_approval(approval_id,thread_id,turn_id,call_id,decision,requested_at_ms,\
                                    resolved_at_ms,expires_at_ms) \
                                    VALUES(?,?,?,?,?,?,?,?)\
                                    """)) {
                            insert.setString(1, approvalId);
                            insert.setString(2, threadId);
                            insert.setString(3, turnId);
                            insert.setString(4, callId);
                            insert.setString(5, decision);
                            insert.setLong(6, transaction.occurredAt().toEpochMilli());
                            if (decision == null) {
                                insert.setNull(7, java.sql.Types.VARCHAR);
                            }
                            else {
                                insert.setLong(7, transaction.occurredAt().toEpochMilli());
                            }
                            insert.setLong(8, expiresAt.toEpochMilli());
                            requireOne(insert.executeUpdate(), "Ja approval insert failed");
                        }
                    } else if (decision != null && rows.getString(1) == null) {
                        try (PreparedStatement update = connection.prepareStatement(
                                """
                                    UPDATE ja_approval SET decision=?,resolved_at_ms=? WHERE approval_id=? AND thread_id=? \
                                    AND turn_id=? AND call_id=? AND decision IS NULL\
                                    """)) {
                            update.setString(1, decision);
                            update.setLong(2, transaction.occurredAt().toEpochMilli());
                            update.setString(3, approvalId);
                            update.setString(4, threadId);
                            update.setString(5, turnId);
                            update.setString(6, callId);
                            requireOne(update.executeUpdate(), "Ja approval decision CAS failed");
                        }
                    } else if (decision != null && !decision.equals(rows.getString(1))) {
                        throw new SQLException("Ja approval projection conflicts with committed decision");
                    }
                }
            }
        }
    }

    /**
     * 审批结果和回执必须参加同一 Store 事务；回执中的 threadRevision 指向本次成功提交后的修订。
     */
    private static void insertApprovalReceipt(Connection connection, AgentStoreTransactionContext transaction,
                                              InteractionWait wait, InteractionAnswerSubmission submission,
                                              String decision) throws SQLException {
        String methodDecision = "APPROVE".equals(decision) ? "approve" : "deny";
        String operationKey = submission.hostMetadata().get("ja.operationKey");
        String requestFingerprint = submission.hostMetadata().get("ja.requestFingerprint");
        if (operationKey == null || requestFingerprint == null) {
            throw new SQLException("Ja approval external receipt identity is missing");
        }
        String sql = """
            INSERT INTO ja_client_operation(operation_key,method,request_fingerprint,thread_id,turn_id,\
            thread_revision,is_queued,approval_id,decision,created_at_ms) VALUES(?,'approval/respond',?,?,?,?,0,?,?,?)\
            """;
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, operationKey);
            insert.setString(2, requestFingerprint);
            insert.setString(3, transaction.execution().conversation().threadId());
            insert.setString(4, transaction.execution().turnId());
            insert.setLong(5, transaction.committedConversationRevision());
            insert.setString(6, wait.interactionId());
            insert.setString(7, methodDecision);
            insert.setLong(8, transaction.occurredAt().toEpochMilli());
            requireOne(insert.executeUpdate(), "Ja approval operation receipt insert failed");
        }
    }

    /**
     * 回答成功后清理最后一次草稿，避免同一 Interaction 同时显示未提交答案。
     */
    private static void deleteInteractionDraft(Connection connection, InteractionWait wait, String threadId)
            throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM ja_interaction_draft WHERE interaction_id=? AND thread_id=?")) {
            delete.setString(1, wait.interactionId());
            delete.setString(2, threadId);
            if (delete.executeUpdate() > 1) {
                throw new SQLException("Ja interaction draft identity is not unique");
            }
        }
    }

    /**
     * typed input enqueue 回执与通用队列命令同事务落库，其他控制命令不伪装成新输入。
     */
    static void applyInputOperationReceipts(Connection connection,
                                                    AgentStoreTransactionContext transaction,
                                                    List<AgentInputQueue.Command> commands) throws SQLException {
        for (AgentInputQueue.Command command : commands) {
            if (command.action() != AgentInputQueue.Action.ENQUEUE) {
                continue;
            }
            String operationKey = command.hostMetadata().get("ja.operationKey");
            String fingerprint = command.hostMetadata().get("ja.requestFingerprint");
            if (operationKey == null && fingerprint == null) {
                continue;
            }
            if (operationKey == null || !operationKey.matches("op_[0-9a-f]{32}")
                || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
                throw new SQLException("Ja input operation receipt metadata is incomplete");
            }
            boolean hasMessage = transaction.records().stream().anyMatch(record ->
                    "agent.input.message".equals(record.recordType())
                    && command.messageRecordId().equals(record.recordId()));
            if (!hasMessage) {
                throw new SQLException("Ja input queue command has no linked message record");
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    """
                        INSERT INTO ja_input_operation_receipt(operation_key,request_fingerprint,thread_id,turn_id,\
                        input_id,input_kind,created_at_ms) VALUES(?,?,?,?,?,?,?)\
                        """)) {
                insert.setString(1, operationKey);
                insert.setString(2, fingerprint);
                insert.setString(3, transaction.execution().conversation().threadId());
                insert.setString(4, transaction.execution().turnId());
                insert.setString(5, command.inputId());
                insert.setString(6, command.kind().name());
                insert.setLong(7, command.occurredAt().toEpochMilli());
                requireOne(insert.executeUpdate(), "Ja input operation receipt insert failed");
            }
        }
    }

    /**
     * approval 身份查询同时校验租户投影归属，防止相同 interactionId 跨 Turn 结算。
     */
    private static ApprovalProjection readApproval(Connection connection, String approvalId,
                                                   String threadId, String turnId, String callId)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                """
                    SELECT decision,expires_at_ms FROM ja_approval WHERE approval_id=? AND thread_id=? \
                    AND turn_id=? AND call_id=?\
                    """)) {
            query.setString(1, approvalId);
            query.setString(2, threadId);
            query.setString(3, turnId);
            query.setString(4, callId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                ApprovalProjection value = new ApprovalProjection(rows.getString(1), rows.getLong(2));
                if (rows.next()) {
                    throw new SQLException("Ja approval identity is not unique");
                }
                return value;
            }
        }
    }

    /**
     * 在 interaction 属性中只读取稳定文本字段，不将类型转换失败解释成普通等待。
     */
    private static String attributeText(JsonObject attributes, String key) throws SQLException {
        if (!(attributes.get(key) instanceof JsonText(String value)) || value.isBlank()) {
            throw new SQLException("Ja approval wait attribute is missing: " + key);
        }
        return value;
    }

    /**
     * 审批请求必须明确标记 kind，未声明类型的等待保持普通交互语义。
     */
    private static boolean isApprovalWait(InteractionWait wait) throws SQLException {
        JsonValue kind = wait.attributes().get("kind");
        if (kind == null) {
            return false;
        }
        if (!(kind instanceof JsonText(String value))) {
            throw new SQLException("Ja wait kind attribute is invalid");
        }
        return "approval".equals(value);
    }

    /**
     * 控制元数据中的版本必须是非负十进制整数，拒绝溢出和部分解析。
     */
    private static long nonNegativeLong(String value, String field) throws SQLException {
        if (value == null) {
            throw new SQLException("Ja " + field + " is invalid");
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new NumberFormatException("negative value");
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new SQLException("Ja " + field + " is invalid", invalid);
        }
    }

    /**
     * 在写入 interaction event 前查询完整 owner 和 revision，避免遗漏 Plan 归属。
     */
    private static PersistedWait readWait(Connection connection, String interactionId, String threadId, String turnId)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                """
                    SELECT tool_call_id,plan_revision_id,run_id,operation_key,questions_json,answers_json,\
                    status,revision,created_at_ms,updated_at_ms FROM ja_interaction_request \
                    WHERE interaction_id=? AND thread_id=? AND turn_id=?\
                    """)) {
            query.setString(1, interactionId);
            query.setString(2, threadId);
            query.setString(3, turnId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                PersistedWait value = new PersistedWait(rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7),
                        rows.getLong(8), rows.getLong(9), rows.getLong(10));
                if (rows.next()) {
                    throw new SQLException("Ja interaction identity is not unique");
                }
                return value;
            }
        }
    }

    /**
     * 只允许名称空间中明确声明的Ja所有者字段，缺失关系与显式null保持区分。
     */
    private static WaitOwner waitOwner(JsonObject attributes) throws SQLException {
        if (!attributes.containsKey("ja.owner")) {
            return new WaitOwner(null, null);
        }
        if (!(attributes.get("ja.owner") instanceof JsonObject owner)
            || !owner.members().keySet().equals(java.util.Set.of("planRevisionId", "runId"))) {
            throw new SQLException("Ja interaction owner payload is incomplete");
        }
        return new WaitOwner(ownerText(owner, "planRevisionId"), ownerText(owner, "runId"));
    }

    /**
     * 将宿主关联字段限制为稳定文本或显式 JSON null，拒绝任意 JSON 变相充当业务身份。
     */
    private static String ownerText(JsonObject owner, String field) throws SQLException {
        JsonValue value = owner.get(field);
        if (value == JsonNull.INSTANCE) {
            return null;
        }
        if (value instanceof JsonText(String value1) && !value1.isBlank()) {
            return value1;
        }
        throw new SQLException("Ja interaction owner value is invalid");
    }

    /**
     * 事件序号由 Ja 数据库分配，仅在核心 wait 状态已成功插入或推进后追加。
     */
    private static void insertWaitEvent(Connection connection, String threadId, InteractionWait wait, String event)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ja_interaction_event(thread_id,interaction_id,interaction_revision,kind,occurred_at_ms) VALUES(?,?,?,?,?)")) {
            statement.setString(1, threadId);
            statement.setString(2, wait.interactionId());
            statement.setLong(3, wait.interactionRevision());
            statement.setString(4, event);
            statement.setLong(5, wait.updatedAt().toEpochMilli());
            requireOne(statement.executeUpdate(), "Ja interaction event insert failed");
        }
    }

    /**
     * Core wait 是状态权威；普通交互和审批分流到各自投影，答案回执与草稿清理仍在同一连接提交。
     */
    void applyWaits(Connection connection, AgentStoreTransactionContext transaction,
                            List<AgentStore.StoredRecord> records,
                            List<InteractionAnswerSubmission> submissions) throws SQLException {
        Map<String, InteractionAnswerSubmission> answers = new HashMap<>();
        for (InteractionAnswerSubmission submission : submissions) {
            if (answers.putIfAbsent(submission.interactionId(), submission) != null) {
                throw new SQLException("Ja transaction contains duplicate answers for one wait");
            }
        }
        Set<String> projectedWaits = new HashSet<>();
        for (AgentStore.StoredRecord record : records) {
            if (ExecutionControlCodec.TOOL_RECOVERY_RECORD_TYPE.equals(record.recordType())) {
                continue;
            }
            if (!ExecutionControlCodec.WAIT_RECORD_TYPE.equals(record.recordType())) {
                continue;
            }
            InteractionWait wait;
            try {
                wait = controls.decodeWait(record);
            } catch (RuntimeException invalid) {
                throw new SQLException("Ja wait record is invalid", invalid);
            }
            if (!projectedWaits.add(wait.interactionId())) {
                throw new SQLException("Ja transaction contains duplicate wait records");
            }
            InteractionAnswerSubmission submission = answers.remove(wait.interactionId());
            if (isApprovalWait(wait)) {
                applyApprovalWait(connection, transaction, wait, submission);
            } else {
                applyInteractionWait(connection, transaction, wait, submission);
            }
        }
        if (!answers.isEmpty()) {
            throw new SQLException("Ja answer submission has no matching wait record");
        }
    }

    /**
     * 普通澄清只进入 Interaction 表，审批等待留在 approvals，防止同一问题出现两张卡片。
     */
    private void applyInteractionWait(Connection connection, AgentStoreTransactionContext transaction,
                                      InteractionWait wait, InteractionAnswerSubmission submission)
            throws SQLException {
        String threadId = transaction.execution().conversation().threadId();
        String turnId = transaction.execution().turnId();
        String questions = encode(mapper, wait.questions());
        String answers = encode(mapper, wait.answers());
        WaitOwner owner = waitOwner(wait.attributes());
        PersistedWait previous = readWait(connection, wait.interactionId(), threadId, turnId);
        if (wait.status() == InteractionStatus.ANSWERED) {
            verifyAnswer(transaction, wait, submission, false);
        } else if (submission != null) {
            throw new SQLException("Ja interaction answer does not settle an ANSWERED wait");
        }
        String event;
        if (previous == null) {
            if (wait.interactionRevision() != 0 || wait.status() != InteractionStatus.PENDING) {
                throw new SQLException("Ja interaction has no durable initial wait");
            }
            insertWait(connection, wait, threadId, turnId, owner, questions, answers);
            event = "CREATED";
        } else {
            verifyWaitIdentity(previous, wait, owner, questions);
            if (wait.interactionRevision() == previous.revision()) {
                if (!previous.status().equals(wait.status().name()) || !sameJson(previous.answersJson(), answers)
                    || previous.updatedAt() != wait.updatedAt().toEpochMilli()) {
                    throw new SQLException("Ja interaction facts conflict at the same revision");
                }
                return;
            }
            if (wait.interactionRevision() != previous.revision() + 1 || !"PENDING".equals(previous.status())) {
                throw new SQLException("Ja interaction revision is not a valid next transition");
            }
            try (PreparedStatement update = connection.prepareStatement(
                    """
                        UPDATE ja_interaction_request SET answers_json=?,status=?,revision=?,updated_at_ms=? \
                        WHERE interaction_id=? AND thread_id=? AND turn_id=? AND revision=? AND status='PENDING'\
                        """)) {
                update.setString(1, answers);
                update.setString(2, wait.status().name());
                update.setLong(3, wait.interactionRevision());
                update.setLong(4, wait.updatedAt().toEpochMilli());
                update.setString(5, wait.interactionId());
                update.setString(6, threadId);
                update.setString(7, turnId);
                update.setLong(8, previous.revision());
                requireOne(update.executeUpdate(), "Ja interaction revision CAS failed");
            }
            event = switch (wait.status()) {
                case ANSWERED -> "ANSWERED";
                case CANCELLED -> "CANCELLED";
                case SUPERSEDED -> "SUPERSEDED";
                case EXPIRED -> "EXPIRED";
                case PENDING -> throw new SQLException("Ja wait cannot advance while remaining pending");
            };
        }
        insertWaitEvent(connection, threadId, wait, event);
        if (wait.status() != InteractionStatus.PENDING) {
            deleteInteractionDraft(connection, wait, threadId);
        }
    }

    /**
     * 审批 wait 的核心状态和 Ja 授权卡片同事务结算；身份来自 wait 字段，属性只保留策略期限。
     */
    private void applyApprovalWait(Connection connection, AgentStoreTransactionContext transaction,
                                   InteractionWait wait, InteractionAnswerSubmission submission)
            throws SQLException {
        if (wait.toolCallId() == null) {
            throw new SQLException("Ja approval wait identity is incomplete");
        }
        // approvalId/callId 与 Core interactionId/toolCallId 相同，禁止在 opaque 属性中重复保存可分叉身份。
        String expiresAt = attributeText(wait.attributes(), "expiresAt");
        String threadId = transaction.execution().conversation().threadId();
        String turnId = transaction.execution().turnId();
        String decision = null;
        if (wait.status() == InteractionStatus.ANSWERED) {
            decision = verifyAnswer(transaction, wait, submission, true);
        } else if (submission != null) {
            throw new SQLException("Ja approval answer does not settle an ANSWERED wait");
        }
        ApprovalProjection approval = readApproval(connection, wait.interactionId(), threadId, turnId, wait.toolCallId());
        if (approval == null || approval.expiresAt() != Instant.parse(expiresAt).toEpochMilli()) {
            throw new SQLException("Ja approval projection differs from its durable wait");
        }
        if (wait.status() == InteractionStatus.PENDING) {
            if (approval.decision() != null) {
                throw new SQLException("pending Ja approval is already resolved");
            }
            return;
        }
        if (wait.status() == InteractionStatus.ANSWERED) {
            if (!Objects.equals(approval.decision(), decision)) {
                throw new SQLException("Ja approval decision differs from its answer");
            }
            insertApprovalReceipt(connection, transaction, wait, submission, decision);
            return;
        }
        if (approval.decision() == null) {
            String closeDecision = switch (wait.status()) {
                case CANCELLED -> "DENY";
                case EXPIRED -> "EXPIRED";
                case ANSWERED, PENDING, SUPERSEDED -> throw new SQLException("unsupported Ja approval close state");
            };
            try (PreparedStatement update = connection.prepareStatement(
                    """
                        UPDATE ja_approval SET decision=?,resolved_at_ms=? WHERE approval_id=? AND thread_id=? \
                        AND turn_id=? AND call_id=? AND decision IS NULL\
                        """)) {
                update.setString(1, closeDecision);
                update.setLong(2, wait.updatedAt().toEpochMilli());
                update.setString(3, wait.interactionId());
                update.setString(4, threadId);
                update.setString(5, turnId);
                update.setString(6, wait.toolCallId());
                requireOne(update.executeUpdate(), "Ja approval cancellation CAS failed");
            }
        } else {
            String expectedClosedDecision = switch (wait.status()) {
                case CANCELLED -> "DENY";
                case EXPIRED -> "EXPIRED";
                case ANSWERED -> throw new SQLException("answered approval was not handled");
                case PENDING, SUPERSEDED -> throw new SQLException("unsupported Ja approval close state");
            };
            if (!expectedClosedDecision.equals(approval.decision())) {
                throw new SQLException("closed Ja approval has a different resolution");
            }
        }
    }

    /**
     * 校验 typed answer 与 wait/CAS；Ja 外部回执身份和 Core scoped operation 身份各守自己的命名空间。
     */
    private String verifyAnswer(AgentStoreTransactionContext transaction, InteractionWait wait,
                                InteractionAnswerSubmission submission, boolean approval) throws SQLException {
        if (submission == null || wait.status() != InteractionStatus.ANSWERED
            || submission.expectedInteractionRevision() + 1 != wait.interactionRevision()
            || !submission.interactionId().equals(wait.interactionId()) || !submission.answers().equals(wait.answers())
            || !submission.occurredAt().equals(wait.updatedAt())
            || !submission.occurredAt().equals(transaction.occurredAt())
            || transaction.kind() != AgentStore.TransactionKind.CONTROL
            || transaction.fencingToken() != 0 || !"SUSPENDED".equals(transaction.state())
            || transaction.committedMutationVersion() != transaction.expectedMutationVersion() + 1
            || transaction.committedConversationRevision() != transaction.expectedThreadRevision() + 1
            || !Objects.equals(transaction.interactionId(), wait.interactionId())
            || !Objects.equals(transaction.answerId(), submission.answerId())
            || transaction.operation().isEmpty()
            || !"agent.wait.answer".equals(transaction.operation().orElseThrow().operationType())
            || !transaction.operation().orElseThrow().operationKey().equals(submission.operationKey())) {
            throw new SQLException("Ja answer does not match its control transaction");
        }
        verifyAnswerControlFacts(transaction, submission);
        Map<String, String> metadata = submission.hostMetadata();
        if (approval) {
            if (!metadata.keySet().equals(Set.of("ja.operationKey", "ja.requestFingerprint",
                    "ja.expectedThreadRevision", "ja.approvalId", "ja.decision"))) {
                throw new SQLException("Ja approval receipt metadata is incomplete");
            }
            String operationKey = metadata.get("ja.operationKey");
            String fingerprint = metadata.get("ja.requestFingerprint");
            String approvalId = metadata.get("ja.approvalId");
            String wireDecision = metadata.get("ja.decision");
            long expectedThreadRevision = nonNegativeLong(metadata.get("ja.expectedThreadRevision"),
                    "approval expected thread revision");
            if (operationKey == null || !operationKey.matches("op_[0-9a-f]{32}")
                || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")
                || !wait.interactionId().equals(approvalId)
                || expectedThreadRevision != transaction.expectedThreadRevision()
                || transaction.committedConversationRevision() != expectedThreadRevision + 1
                || !Set.of("approve", "deny").contains(wireDecision)
                || submission.answers().size() != 1) {
                throw new SQLException("Ja approval answer receipt identity is invalid");
            }
            InteractionAnswer answer = submission.answers().getFirst();
            String expectedOption = "approve".equals(wireDecision) ? "option_approve" : "option_deny";
            if (!"question_approval".equals(answer.questionId()) || answer.skipped()
                || answer.freeText() != null || !answer.optionIds().equals(List.of(expectedOption))) {
                throw new SQLException("Ja approval answer does not match its receipt decision");
            }
            return "approve".equals(wireDecision) ? "APPROVE" : "DENY";
        }
        String externalOperationKey = metadata.get("ja.operationKey");
        if (!metadata.keySet().equals(Set.of("ja.operationKey", "ja.expectedInteractionRevision"))
            || externalOperationKey == null
            || !externalOperationKey.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
            || nonNegativeLong(metadata.get("ja.expectedInteractionRevision"), "interaction expected wait revision")
               != submission.expectedInteractionRevision()) {
            throw new SQLException("Ja interaction answer metadata is invalid");
        }
        return null;
    }

    /**
     * controlCommit 不携带执行租约，故上下文 kind 固定为 CONTROL；只有唯一答案记录和 WAIT_RESOLVED
     * 事件同时引用相同 answerId 与 operation，才可作为等待结算，不能接受泛化 CONTROL 事务。
     */
    private void verifyAnswerControlFacts(AgentStoreTransactionContext transaction,
                                          InteractionAnswerSubmission submission) throws SQLException {
        int answerRecords = 0;
        for (AgentStore.RecordWrite record : transaction.records()) {
            if (!ExecutionControlCodec.ANSWER_SUBMISSION_RECORD_TYPE.equals(record.recordType())) {
                continue;
            }
            InteractionAnswerSubmission decoded;
            try {
                decoded = controls.decodeAnswerSubmission(record);
            } catch (RuntimeException invalid) {
                throw new SQLException("Ja answer record is invalid", invalid);
            }
            if (!record.recordId().equals(decoded.answerId()) || !submission.equals(decoded)) {
                throw new SQLException("Ja answer record identity differs from the submitted answer");
            }
            answerRecords++;
        }
        if (answerRecords != 1) {
            throw new SQLException("Ja answer control requires one typed answer record");
        }

        AgentStore.OperationIdentity operation = transaction.operation().orElseThrow();
        int waitEvents = 0;
        for (AgentStore.EventWrite write : transaction.events()) {
            ExecutionEvent event;
            try {
                event = executionEvents.decode(write.eventType(), write.payloadSchemaVersion(), jsonValues.encode(write.payload()));
            } catch (RuntimeException invalid) {
                throw new SQLException("Ja answer control event is invalid", invalid);
            }
            if (!(event instanceof ExecutionEvent.ControlRecorded(
                    ExecutionEvent.ControlAction action, String controlRecordId, String operationKey, String reasonCode
            ))) {
                continue;
            }
            if (action != ExecutionEvent.ControlAction.WAIT_RESOLVED
                || !submission.answerId().equals(controlRecordId)
                || !submission.operationKey().equals(operationKey)
                || reasonCode != null) {
                throw new SQLException("Ja answer control event does not identify this answer");
            }
            waitEvents++;
        }
        if (waitEvents != 1 || !operation.operationKey().equals(submission.operationKey())) {
            throw new SQLException("Ja answer control requires one matching wait resolution event");
        }
    }

    /**
     * 插入首版问答状态时从通用 opaque namespace 保留可选的 Ja Plan 关联。
     */
    private void insertWait(Connection connection, InteractionWait wait, String threadId, String turnId,
                            WaitOwner owner, String questions, String answers) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                """
                    INSERT INTO ja_interaction_request(interaction_id,thread_id,turn_id,tool_call_id,plan_revision_id,\
                    run_id,\
                    operation_key,questions_json,answers_json,status,revision,created_at_ms,updated_at_ms) \
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)\
                    """)) {
            insert.setString(1, wait.interactionId());
            insert.setString(2, threadId);
            insert.setString(3, turnId);
            nullable(insert, 4, wait.toolCallId());
            nullable(insert, 5, owner.planRevisionId());
            nullable(insert, 6, owner.runId());
            insert.setString(7, wait.operationKey());
            insert.setString(8, questions);
            insert.setString(9, answers);
            insert.setString(10, wait.status().name());
            insert.setLong(11, wait.interactionRevision());
            insert.setLong(12, wait.createdAt().toEpochMilli());
            insert.setLong(13, wait.updatedAt().toEpochMilli());
            requireOne(insert.executeUpdate(), "Ja interaction insert failed");
        }
    }

    /**
     * 拒绝 wait 记录改变既有租户内身份、题目或宿主所有者关联。
     */
    private void verifyWaitIdentity(PersistedWait previous, InteractionWait wait,
                                    WaitOwner owner, String questions) throws SQLException {
        if (!Objects.equals(previous.toolCallId(), wait.toolCallId())
            || !Objects.equals(previous.planRevisionId(), owner.planRevisionId())
            || !Objects.equals(previous.runId(), owner.runId())
            || !previous.operationKey().equals(wait.operationKey())
            || !sameJson(previous.questionsJson(), questions)
            || previous.createdAt() != wait.createdAt().toEpochMilli()) {
            throw new SQLException("Ja interaction owner or immutable identity changed");
        }
    }

    /**
     * 同 revision 回放通过树结构比较，允许 JSON 空白差异但不允许字段或值变化。
     */
    private boolean sameJson(String left, String right) throws SQLException {
        try {
            return mapper.readTree(left).equals(mapper.readTree(right));
        } catch (JsonProcessingException failure) {
            throw new SQLException("Ja interaction JSON is invalid", failure);
        }
    }

    /**
     * Approval 表只是 UI 投影；取消状态以 Core wait 为权威，不会伪造 ApprovalDecision 事实。
     *
     * @param decision 决策
     * @param expiresAt 过期时间
     */
    private record ApprovalProjection(String decision,
                                      long expiresAt) {
    }

    /**
     * 问答 owner 是 Ja projection 元数据，不影响 Core wait 的执行授权或恢复资格。
     *
     * @param planRevisionId Plan 修订版本标识
     * @param runId 运行标识
     */
    private record WaitOwner(String planRevisionId,
                             String runId) {
    }

    /**
     * 精确读取已提交展示行，以便 participant 对照通用 wait 的 CAS 版本和不可变身份。
     *
     * @param toolCallId 工具调用标识
     * @param planRevisionId Plan 修订版本标识
     * @param runId 运行标识
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     * @param questionsJson 问题列表 JSON 表示
     * @param answersJson 答案集合 JSON 表示
     * @param status 当前状态分类
     * @param revision 当前修订版本
     * @param createdAt 创建时间
     * @param updatedAt 最后更新时间
     */
    private record PersistedWait(String toolCallId,
                                 String planRevisionId,
                                 String runId,
                                 String operationKey,
                                 String questionsJson,
                                 String answersJson,
                                 String status,
                                 long revision,
                                 long createdAt,
                                 long updatedAt) {
    }
}
