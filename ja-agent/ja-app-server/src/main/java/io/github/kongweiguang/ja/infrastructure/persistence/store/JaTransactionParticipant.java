// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.engine.AgentInitialQueuedInputsCodec;
import io.github.kongweiguang.ja.agent.application.engine.AgentRequestBinding;
import io.github.kongweiguang.ja.agent.application.engine.AgentRequestBindingCodec;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentRecordIds;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExtensionFactRecordCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.MiddlewareStateRecordCodec;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.execution.ControlRequest;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFact;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolResult;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswerSubmission;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValues;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ControlPayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.JacksonExecutionEventCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskTerminalProjection;
import io.github.kongweiguang.ja.conversation.application.change.WorkspaceMutationFactCodec;
import io.github.kongweiguang.ja.conversation.application.gateway.JaExtensionFactApplier;
import io.github.kongweiguang.ja.conversation.domain.model.TurnChangeSet;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaArtifactProjection.ChangeSetArtifactSnapshot;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaArtifactProjection.ToolArtifactSnapshot;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaMailboxProjection.MailboxQueueProjection;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaMailboxProjection.QueueCommandFact;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.AgentStoreConnectionScope;
import io.github.kongweiguang.ja.attachment.application.gateway.AttachmentRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.JdbcTransactionResources;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.nullable;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.requireOne;
import static io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionJdbc.stored;

/**
 * Ja 投影唯一协调器先校验提交事实，再按依赖顺序调用内部投影；各协作者共用 Store 借出的连接。
 * <p>这里不提交、回滚或关闭连接，任一协作者失败由 Store 回滚 Core 与全部产品事实。</p>
 */
public final class JaTransactionParticipant implements AgentStoreTransactionParticipant {
    private static final String TENANT_ID = "ja.local";
    private final ObjectMapper mapper;
    private final StorePayloadCodec facts;
    private final ControlPayloadCodec controls;
    private final JsonValueCodec jsonValues;
    private final ExtensionFactRecordCodec extensionFactRecords;
    private final MiddlewareStateRecordCodec middlewareStates;
    private final AgentInitialQueuedInputsCodec initialQueuedInputs;
    private final JaProjectionPayloadCodec projections;
    private final JacksonExecutionEventCodec executionEvents;
    private final JaInputProjection inputs;
    private final TaskTerminalProjection taskTerminalProjection;
    private final Map<ExtensionFactKey, JaExtensionFactApplier> extensionAppliers;
    private final JaMessageProjection messageProjection;
    private final JaMailboxProjection mailboxProjection;
    private final JaInteractionProjection interactionProjection;
    private final JaToolProjection toolProjection;
    private final JaArtifactProjection artifactProjection;

    /**
     * Task 终态和附件仓储必须显式注入；前者维持 Task receipt 原子性，后者让 ToolResult 图片 owner 加入当前 Store 事务。
     */
    public JaTransactionParticipant(ObjectMapper mapper, Collection<? extends JaExtensionFactApplier> appliers,
                                    TaskTerminalProjection taskTerminalProjection,
                                    AttachmentRepository attachments) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(appliers, "appliers");
        this.taskTerminalProjection = Objects.requireNonNull(taskTerminalProjection, "taskTerminalProjection");
        facts = new StorePayloadCodec(mapper);
        controls = new ControlPayloadCodec(mapper);
        jsonValues = new JacksonJsonValueCodec(mapper);
        extensionFactRecords = new io.github.kongweiguang.ja.agent.infrastructure.persistence.ExtensionFactRecordCodec();
        middlewareStates = new io.github.kongweiguang.ja.agent.infrastructure.persistence.MiddlewareStateRecordCodec(jsonValues);
        initialQueuedInputs = new AgentInitialQueuedInputsCodec();
        projections = new JaProjectionPayloadCodec(mapper);
        executionEvents = new JacksonExecutionEventCodec(mapper);
        inputs = new JaInputProjection(mapper);
        messageProjection = new JaMessageProjection(mapper);
        mailboxProjection = new JaMailboxProjection(mapper);
        interactionProjection = new JaInteractionProjection(mapper);
        toolProjection = new JaToolProjection(mapper, Objects.requireNonNull(attachments, "attachments"));
        artifactProjection = new JaArtifactProjection(mapper);
        Map<ExtensionFactKey, JaExtensionFactApplier> registered = new HashMap<>();
        for (JaExtensionFactApplier applier : appliers) {
            Objects.requireNonNull(applier, "applier");
            ExtensionFactKey key = new ExtensionFactKey(applier.namespace(), applier.type(), applier.schemaVersion());
            if (key.schemaVersion() <= 0 || registered.putIfAbsent(key, applier) != null) {
                throw new IllegalArgumentException("duplicate or invalid Ja extension fact registration");
            }
        }
        extensionAppliers = Map.copyOf(registered);
    }

    /**
     * 将已验证的取消意图写入当前 Turn，并以原 conversation 与 mutation CAS 水位绑定传播欠账。
     */
    private static void applyCancelRequest(Connection connection, AgentStoreTransactionContext transaction,
                                           CancelRequestFact request) throws SQLException {
        if (request == null) {
            return;
        }
        String sql = """
            UPDATE ja_conversation_turn SET cancel_requested_at_ms=?,cancel_reason=?,cancel_expected_thread_revision=?,\
            cancel_thread_revision=?,cancel_turn_mutation_version=? WHERE turn_id=? AND thread_id=? \
            AND mutation_version=? AND cancel_requested_at_ms IS NULL \
            AND state NOT IN ('COMPLETED','FAILED','CANCELLED') \
            AND EXISTS (SELECT 1 FROM ja_conversation_thread t WHERE t.thread_id=ja_conversation_turn.thread_id AND t.revision=?)\
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, request.request().occurredAt().toEpochMilli());
            statement.setString(2, request.request().reasonCode());
            statement.setLong(3, transaction.expectedThreadRevision());
            statement.setLong(4, transaction.committedConversationRevision());
            statement.setLong(5, transaction.committedMutationVersion());
            statement.setString(6, transaction.execution().turnId());
            statement.setString(7, transaction.execution().conversation().threadId());
            statement.setLong(8, transaction.expectedMutationVersion());
            statement.setLong(9, transaction.expectedThreadRevision());
            requireOne(statement.executeUpdate(), "Ja cancellation request projection CAS failed");
        }
    }

    /**
     * 从唯一 typed binding 判定独立压缩，不能按 ID、无用户消息或缺少 Ja 行猜测请求类型。
     */
    public static boolean isCompactionAdmission(AgentStoreTransactionContext transaction) throws SQLException {
        AgentStoreTransactionContext tx = Objects.requireNonNull(transaction, "transaction");
        return tx.kind() == AgentStore.TransactionKind.ADMISSION
               && requestBinding(tx).kind() == AgentExecutionRequest.AdmissionKind.COMPACTION;
    }

    /**
     * 每个 COMPACTION 提交都凭 Store 中的冻结 binding 跳过 Ja 投影，不能从 turnId 猜请求种类。
     */
    private static boolean isCompactionExecution(AgentStoreTransactionContext transaction) throws SQLException {
        AgentStoreTransactionContext tx = Objects.requireNonNull(transaction, "transaction");
        AgentRequestBinding binding = tx.kind() == AgentStore.TransactionKind.ADMISSION
                ? requestBinding(tx) : requestBinding(JdbcTransactionResources.connection(tx), tx.execution());
        AgentStore.ExecutionKey key = tx.execution();
        if (!binding.identity().tenantId().equals(key.conversation().tenantId())
            || !binding.identity().threadId().equals(key.conversation().threadId())
            || !binding.identity().turnId().equals(key.turnId())) {
            throw new SQLException("Ja persisted request binding does not match its store execution");
        }
        return binding.kind() == AgentExecutionRequest.AdmissionKind.COMPACTION;
    }

    /**
     * 后续事务只按稳定 execution key 读取唯一绑定；缺失绑定代表损坏，不猜测执行种类或启用旧投影。
     */
    private static AgentRequestBinding requestBinding(Connection connection, AgentStore.ExecutionKey execution)
            throws SQLException {
        String sql = """
            SELECT record_id,record_type,payload_schema_version,record_sequence,payload_json,occurred_at_ms \
            FROM agent_record WHERE tenant_id=? AND thread_id=? AND turn_id=? \
            AND record_type=? ORDER BY record_sequence\
            """;
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, execution.conversation().tenantId());
            query.setString(2, execution.conversation().threadId());
            query.setString(3, execution.turnId());
            query.setString(4, AgentRequestBindingCodec.RECORD_TYPE);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Ja execution has no persisted request binding");
                }
                AgentStore.StoredRecord record = new AgentStore.StoredRecord(rows.getString(1), rows.getString(2),
                        rows.getInt(3), rows.getLong(4), new JacksonJsonValueCodec().decodeObject(rows.getString(5)), Instant.ofEpochMilli(rows.getLong(6)));
                if (rows.next()) {
                    throw new SQLException("Ja execution has multiple persisted request bindings");
                }
                try {
                    return new AgentRequestBindingCodec().decode(record);
                } catch (RuntimeException invalid) {
                    throw new SQLException("Ja persisted request binding is invalid", invalid);
                }
            }
        }
    }

    /**
     * 同时核对 request binding 与事务执行键，避免错误绑定被用作跳过宿主投影的通道。
     */
    private static AgentRequestBinding requestBinding(AgentStoreTransactionContext transaction) throws SQLException {
        AgentRequestBinding binding = requestBinding(transaction.records());
        AgentStore.ExecutionKey execution = transaction.execution();
        if (!binding.identity().tenantId().equals(execution.conversation().tenantId())
            || !binding.identity().threadId().equals(execution.conversation().threadId())
            || !binding.identity().turnId().equals(execution.turnId())) {
            throw new SQLException("Ja admission binding does not match its store execution");
        }
        return binding;
    }

    /**
     * 每个 Ja admission 必须包含唯一完整请求绑定；缺失或重复会在投影前失败关闭。
     */
    private static AgentRequestBinding requestBinding(List<AgentStore.RecordWrite> records) throws SQLException {
        AgentRequestBindingCodec codec = new AgentRequestBindingCodec();
        AgentRequestBinding decoded = null;
        long sequence = 0;
        for (AgentStore.RecordWrite record : records) {
            sequence++;
            if (!AgentRequestBindingCodec.RECORD_TYPE.equals(record.recordType())) {
                continue;
            }
            if (decoded != null) {
                throw new SQLException("Ja admission has multiple request bindings");
            }
            try {
                decoded = codec.decode(new AgentStore.StoredRecord(record.recordId(), record.recordType(),
                        record.payloadSchemaVersion(), sequence, record.payload(), record.occurredAt()));
            } catch (RuntimeException invalid) {
                throw new SQLException("Ja admission request binding is invalid", invalid);
            }
        }
        if (decoded == null) {
            throw new SQLException("Ja admission has no request binding");
        }
        return decoded;
    }

    /**
     * 复用 Workspace 纯 codec 校验已知收据闭集，避免参与者复制 payload 规则或吞掉未知版本。
     */
    private static void validateWorkspaceMutationFact(DecodedExtensionFact fact) throws SQLException {
        try {
            WorkspaceMutationFactCodec.DecodedFact decoded = WorkspaceMutationFactCodec.decode(
                    fact.namespace(), fact.type(), fact.schemaVersion(), fact.payload());
            if (!(decoded instanceof WorkspaceMutationFactCodec.MutationReceipt)
                && !(decoded instanceof WorkspaceMutationFactCodec.ObservationFailure)) {
                throw new SQLException("Ja workspace mutation fact has an unsupported decoded type");
            }
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("Ja workspace mutation fact is invalid", invalid);
        }
    }

    /**
     * 同一 callId 的 presentation 只允许单调 revision；同 revision 不同载荷代表冲突。
     */
    private static void putPresentation(Map<String, JaProjectionPayloadCodec.ToolPresentationSnapshot> values,
                                        JaProjectionPayloadCodec.ToolPresentationSnapshot snapshot)
            throws SQLException {
        JaProjectionPayloadCodec.ToolPresentationSnapshot prior = values.get(snapshot.callId());
        if (prior == null || snapshot.revision() > prior.revision()) {
            values.put(snapshot.callId(), snapshot);
        }
        else if (snapshot.revision() == prior.revision() && !snapshot.equals(prior)) {
            throw new SQLException("Ja tool presentation revision conflicts");
        }
    }

    /**
     * 先验证业务 Thread 与 Turn 已由初始化或 request participant 建立，后续 Ja 投影不能创建第二身份。
     */
    private static void verifyOwner(Connection connection, String threadId, String turnId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM ja_conversation_turn WHERE turn_id=? AND thread_id=?")) {
            statement.setString(1, turnId);
            statement.setString(2, threadId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Ja Turn row is missing for AgentStore execution");
                }
            }
        }
    }

    /**
     * Core Terminal event 是 Ja Turn 终态字段的唯一来源，避免同一提交复制第二份可分叉摘要。
     */
    private static void mirrorTurn(Connection connection, AgentStoreTransactionContext transaction,
                                   ExecutionEvent.Terminal terminal) throws SQLException {
        TurnState state;
        try {
            state = TurnState.valueOf(transaction.state());
        } catch (IllegalArgumentException failure) {
            throw new SQLException("AgentStore turn state cannot be projected to Ja", failure);
        }
        if (state.terminal() == (terminal == null)) {
            throw new SQLException("Core terminal event does not match AgentStore state");
        }
        if (terminal != null && terminal.state() != state) {
            throw new SQLException("Core terminal event state differs from committed execution state");
        }
        long expected = transaction.kind() == AgentStore.TransactionKind.ADMISSION ? 0
                : transaction.expectedMutationVersion();
        String sql = """
            UPDATE ja_conversation_turn SET state=?,mutation_version=?,updated_at_ms=?,completed_at_ms=?,terminal_summary=?,\
            error_code=?,error_message=? WHERE turn_id=? AND thread_id=? AND mutation_version=?\
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, state.name());
            statement.setLong(2, transaction.committedMutationVersion());
            statement.setLong(3, transaction.occurredAt().toEpochMilli());
            nullable(statement, 4, state.terminal() ? transaction.occurredAt().toEpochMilli() : null);
            nullable(statement, 5, terminal == null ? null : terminal.summary());
            nullable(statement, 6, terminal == null ? null : terminal.errorCode());
            nullable(statement, 7, terminal == null ? null : terminal.errorMessage());
            statement.setString(8, transaction.execution().turnId());
            statement.setString(9, transaction.execution().conversation().threadId());
            statement.setLong(10, expected);
            requireOne(statement.executeUpdate(), "Ja Turn projection version CAS failed");
        }
    }

    /**
     * 将通用 conversation revision 镜像到 Thread，以同一个旧值 CAS 防止 Ja metadata 与 loop 状态分叉。
     */
    private static void mirrorThread(Connection connection, AgentStoreTransactionContext transaction) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ja_conversation_thread SET revision=?,updated_at_ms=? WHERE thread_id=? AND revision=?")) {
            statement.setLong(1, transaction.committedConversationRevision());
            statement.setLong(2, transaction.occurredAt().toEpochMilli());
            statement.setString(3, transaction.execution().conversation().threadId());
            statement.setLong(4, transaction.expectedThreadRevision());
            requireOne(statement.executeUpdate(), "Ja Thread projection revision CAS failed");
        }
    }

    /**
     * Core 回调保持中立；SQL 与呈现 JSON 序列化的受检故障在此包装，其余失败原样交由 Store 回滚。
     */
    @Override
    public void apply(AgentStoreTransactionContext transaction) {
        try {
            applyProjection(transaction);
        } catch (SQLException | JsonProcessingException failure) {
            throw new AgentStore.StoreParticipantException("Ja transaction participant failed", failure);
        }
    }

    /**
     * 先按类型解码同一提交内的核心记录并拒绝精确退役队列快照，再镜像 Ja Turn 事实；只借用事务连接。
     * 独立压缩不改变 Ja Turn 投影；只声明实际的 SQL 和 JSON 序列化故障，保留精确根因。
     * @author kongweiguang
     */
    @SuppressWarnings("PMD.CloseResource") // Store 负责事务连接生命周期；此处关闭会提前破坏原子提交。
    private void applyProjection(AgentStoreTransactionContext transaction) throws SQLException, JsonProcessingException {
        Objects.requireNonNull(transaction, "transaction");
        AgentStore.ExecutionKey key = transaction.execution();
        if (!TENANT_ID.equals(key.conversation().tenantId())) {
            throw new SQLException("Ja participant received a foreign tenant");
        }
        validateInitialQueuedInputs(transaction);
        if (isCompactionExecution(transaction)) {
            mirrorThread(JdbcTransactionResources.connection(transaction), transaction);
            return;
        }
        Connection connection = JdbcTransactionResources.connection(transaction);
        List<AgentStore.RecordWrite> records = transaction.records();
        List<ExecutionFact> executionFacts = new ArrayList<>();
        List<AgentStore.StoredRecord> waitRecords = new ArrayList<>();
        CancelRequestFact cancelRequest = null;
        List<InteractionAnswerSubmission> answerSubmissions = new ArrayList<>();
        List<QueueCommandFact> inputCommands = new ArrayList<>();
        List<DecodedExtensionFact> extensionFacts = new ArrayList<>();
        Map<String, ExecutionFact> executionFactsByRecordId = new HashMap<>();
        List<ToolArtifactSnapshot> toolArtifacts = new ArrayList<>();
        TurnChangeSet changeSet = null;
        ChangeSetArtifactSnapshot changeSetArtifact = null;
        Map<String, JaProjectionPayloadCodec.ToolPresentationSnapshot> presentationsByCall = new HashMap<>();
        for (int index = 0; index < records.size(); index++) {
            AgentStore.RecordWrite record = records.get(index);
            AgentStore.StoredRecord stored = stored(record, index + 1L);
            if (record.recordType().startsWith("agent.")) {
                if (record.recordType().equals(ExecutionControlCodec.WAIT_RECORD_TYPE)) {
                    waitRecords.add(stored);
                } else if (record.recordType().equals(ExecutionControlCodec.TOOL_RECOVERY_RECORD_TYPE)) {
                    // 关联的工具事实应用后，通用恢复状态会在下方同步更新。
                    waitRecords.add(stored);
                } else if (ExecutionControlCodec.ANSWER_SUBMISSION_RECORD_TYPE.equals(record.recordType())) {
                    try {
                        answerSubmissions.add(controls.decodeAnswerSubmission(record));
                    } catch (RuntimeException invalid) {
                        throw new SQLException("Ja interaction answer submission is invalid", invalid);
                    }
                } else if (ExecutionControlCodec.INPUT_QUEUE_COMMAND_RECORD_TYPE.equals(record.recordType())) {
                    try {
                        AgentInputQueue.Command command = controls.decodeInputQueueCommand(record);
                        if (!record.recordId().equals(command.commandId())) {
                            throw new SQLException("Ja input queue command record identity differs from its payload");
                        }
                        inputCommands.add(new QueueCommandFact(command));
                    } catch (RuntimeException invalid) {
                        throw new SQLException("Ja input queue command is invalid", invalid);
                    }
                } else if ("agent.input.queue.snapshot".equals(record.recordType())) {
                    throw new SQLException("input queue snapshot record is no longer supported");
                } else if (ExecutionControlCodec.CANCEL_REQUEST_RECORD_TYPE.equals(record.recordType())) {
                    if (cancelRequest != null) {
                        throw new SQLException("Ja transaction has multiple cancellation requests");
                    }
                    try {
                        ControlRequest request = controls.decodeCancelRequest(stored);
                        String expectedRecordId = AgentRecordIds.derive(transaction.execution(),
                                ExecutionControlCodec.CANCEL_REQUEST_RECORD_TYPE, request.operationKey());
                        if (!record.recordId().equals(expectedRecordId)) {
                            throw new SQLException("Ja cancellation request identity differs from its operation key");
                        }
                        cancelRequest = new CancelRequestFact(record.recordId(), request);
                    } catch (RuntimeException invalid) {
                        throw new SQLException("Ja cancellation request is invalid", invalid);
                    }
                } else if (ExecutionControlCodec.SUSPEND_REQUEST_RECORD_TYPE.equals(record.recordType())) {
                    controls.decodeSuspendRequest(stored);
                } else if (ExecutionControlCodec.SUSPEND_CONSUMED_RECORD_TYPE.equals(record.recordType())) {
                    controls.decodeSuspendConsumption(stored);
                } else if (ExecutionControlCodec.RECOVERY_COMMAND_RECORD_TYPE.equals(record.recordType())) {
                    controls.decodeRecoveryCommand(record);
                } else if (ExecutionControlCodec.RECOVERY_COMMAND_CONSUMPTION_RECORD_TYPE.equals(record.recordType())) {
                    controls.decodeRecoveryCommandConsumption(record);
                } else if (AgentInitialQueuedInputsCodec.RECORD_TYPE.equals(record.recordType())) {
                    // 已在 admission 边界按 Core typed codec 和 binding 引用校验，不属于 ExecutionFact。
                } else if (MiddlewareStateRecordCodec.RECORD_TYPE.equals(record.recordType())) {
                    try {
                        middlewareStates.decode(record);
                    } catch (RuntimeException invalid) {
                        throw new SQLException("Ja transaction received an invalid Core middleware state record", invalid);
                    }
                    // Core middleware 状态由 AgentStore 保存并在恢复时按注册项归约；Ja 不复制第二份状态。
                } else if (AgentRequestBindingCodec.RECORD_TYPE.equals(record.recordType())) {
                    if (transaction.kind() != AgentStore.TransactionKind.ADMISSION) {
                        throw new SQLException("Ja request binding appeared outside admission");
                    }
                    // validateInitialQueuedInputs 已按同一 execution、operation 和 initial batch 引用解码。
                } else if ("agent.user.message".equals(record.recordType())
                           || "agent.input.message".equals(record.recordType())) {
                    // 用户内容有专用解码器，不得按 ExecutionFact 解释。
                } else if ("agent.extension.fact".equals(record.recordType())) {
                    DecodedExtensionFact extension = decodeExtensionFact(stored);
                    if ("ja.tool".equals(extension.namespace())
                        && "presentation".equals(extension.type())
                        && extension.schemaVersion() == 1) {
                        String payload = mapper.writeValueAsString(
                                JacksonJsonValues.toNode(mapper, extension.payload()));
                        JaProjectionPayloadCodec.ToolPresentationSnapshot snapshot =
                                projections.decodeToolPresentation(JaProjectionPayloadCodec.TOOL_PRESENTATION_TYPE,
                                        JaProjectionPayloadCodec.SCHEMA_VERSION, payload);
                        putPresentation(presentationsByCall, snapshot);
                    } else if ("ja.tool".equals(extension.namespace())
                               && "artifact-content".equals(extension.type())
                               && extension.schemaVersion() == 1) {
                        toolArtifacts.add(JaArtifactProjection.decodeToolArtifact(extension.payload()));
                    } else if ("ja.turn".equals(extension.namespace())
                               && "change-set".equals(extension.type()) && extension.schemaVersion() == 1) {
                        if (changeSet != null) {
                            throw new SQLException("multiple Ja change sets in one commit");
                        }
                        changeSet = artifactProjection.decodeChangeSet(extension.payload());
                    } else if ("ja.turn".equals(extension.namespace())
                               && "change-set-artifact".equals(extension.type()) && extension.schemaVersion() == 1) {
                        if (changeSetArtifact != null) {
                            throw new SQLException("multiple Ja change-set artifacts in one commit");
                        }
                        changeSetArtifact = JaArtifactProjection.decodeChangeSetArtifact(extension.payload());
                    } else if ("ja.workspace".equals(extension.namespace())) {
                        validateWorkspaceMutationFact(extension);
                        // Workspace 收据事实由 ChangeSet 中间件消费，其类型化状态补丁与本结果一并提交；不再为这些事实单独写入 Ja SQL 行。
                    } else {
                        extensionFacts.add(extension);
                    }
                } else if (ExecutionRecordCodec.INITIAL_CONTEXT_SEED_RECORD_TYPE.equals(record.recordType())
                           || ExecutionRecordCodec.CONTEXT_INSTRUCTION_RECORD_TYPE.equals(record.recordType())
                           || ExecutionRecordCodec.CONTEXT_CHECKPOINT_RECORD_TYPE.equals(record.recordType())
                           || ExecutionRecordCodec.CONTEXT_PROJECTION_RECORD_TYPE.equals(record.recordType())
                           || ExecutionRecordCodec.CONTEXT_COMPACTION_RECORD_TYPE.equals(record.recordType())) {
                    facts.validateContextRecord(stored);
                } else if (ExecutionControlCodec.SUSPENSION_RECORD_TYPE.equals(record.recordType())) {
                    controls.decodeSuspension(stored);
                } else if (ExecutionRecordCodec.MODEL_CAPABILITIES_RECORD_TYPE.equals(record.recordType())) {
                    facts.decodeModelCapabilities(stored, transaction.execution());
                } else {
                    ExecutionFact fact = facts.decode(record.recordType(), record.payloadSchemaVersion(),
                            jsonValues.encode(record.payload()));
                    executionFacts.add(fact);
                    if (executionFactsByRecordId.putIfAbsent(record.recordId(), fact) != null) {
                        throw new SQLException("Ja transaction repeats an execution fact record identity");
                    }
                }
            } else if (JaProjectionPayloadCodec.TOOL_PRESENTATION_TYPE.equals(record.recordType())) {
                JaProjectionPayloadCodec.ToolPresentationSnapshot snapshot = projections.decodeToolPresentation(record);
                putPresentation(presentationsByCall, snapshot);
            } else if ("ja.turn.terminal".equals(record.recordType())) {
                throw new SQLException("Ja terminal record is retired; use the Core Terminal event");
            } else {
                throw new SQLException("Ja participant received an unsupported record type");
            }
        }

        validateCancelRequest(transaction, cancelRequest);
        ExecutionEvent.Terminal terminalEvent = terminalEvent(transaction);
        verifyOwner(connection, key.conversation().threadId(), key.turnId());
        List<MailboxQueueProjection> mailboxQueue = mailboxProjection.prepareMailboxQueue(connection, transaction, inputCommands);
        Set<String> mailboxMessageIdsConsumed = mailboxQueue.stream()
                .filter(value -> value.command().action() == AgentInputQueue.Action.CONSUME)
                .map(value -> value.source().queuedMessage().messageId()).collect(java.util.stream.Collectors.toSet());
        Set<String> settledAssistantRecordIds = transaction.events().stream()
                .filter(event -> "agent.assistant.settled".equals(event.eventType()))
                .map(event -> executionEvents.decode(event.eventType(), event.payloadSchemaVersion(),
                        jsonValues.encode(event.payload())))
                .map(ExecutionEvent.AssistantSettled.class::cast)
                .map(ExecutionEvent.AssistantSettled::messageRecordId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        messageProjection.applyMessages(connection, transaction, executionFactsByRecordId, mailboxMessageIdsConsumed,
                terminalEvent, settledAssistantRecordIds);
        messageProjection.finalizeTerminalAssistant(connection, transaction, terminalEvent, executionFactsByRecordId);
        applyCancelRequest(connection, transaction, cancelRequest);
        mailboxProjection.applyMailboxQueue(connection, transaction, mailboxQueue);
        inputs.apply(transaction);
        toolProjection.applyModelCallPreviews(connection, transaction, executionFactsByRecordId);
        toolProjection.applyTools(connection, transaction, executionFacts, executionFactsByRecordId, presentationsByCall);
        artifactProjection.applyToolArtifacts(connection, transaction, toolArtifacts, presentationsByCall);
        artifactProjection.applyChangeSet(connection, transaction, terminalEvent, changeSet, changeSetArtifact);
        toolProjection.applyRecoveries(connection, transaction, executionFacts, waitRecords);
        JaInteractionProjection.applyApprovals(connection, transaction, executionFacts);
        messageProjection.applyUsage(connection, transaction, executionFacts);
        interactionProjection.applyWaits(connection, transaction, waitRecords, answerSubmissions);
        JaInteractionProjection.applyInputOperationReceipts(connection, transaction,
                inputCommands.stream().map(QueueCommandFact::command).toList());
        applyExtensionFacts(transaction, extensionFacts);
        applyToolCompletions(transaction, executionFactsByRecordId);
        toolProjection.validatePresentationStates(connection, transaction, presentationsByCall);
        mailboxProjection.releaseUnconsumedMailboxMessages(connection, transaction);
        mirrorTurn(connection, transaction, terminalEvent);
        if (terminalEvent != null) {
            Map<String, ExecutionFact> committedFacts = Map.copyOf(executionFactsByRecordId);
            AgentStoreConnectionScope.call(connection, () -> {
                taskTerminalProjection.settleTerminal(transaction, terminalEvent, committedFacts);
                return null;
            });
        }
        mirrorThread(connection, transaction);
    }

    /**
     * 每次准入都要与冻结 binding 一起保存唯一初始队列批次；这类 Core 请求元数据不能误解为执行事实。
     */
    private void validateInitialQueuedInputs(AgentStoreTransactionContext transaction) throws SQLException {
        AgentStore.RecordWrite batch = null;
        for (AgentStore.RecordWrite record : transaction.records()) {
            if (!AgentInitialQueuedInputsCodec.RECORD_TYPE.equals(record.recordType())) {
                continue;
            }
            if (transaction.kind() != AgentStore.TransactionKind.ADMISSION || batch != null) {
                throw new SQLException("Ja transaction has an unexpected or duplicate initial input batch");
            }
            batch = record;
        }
        if (transaction.kind() != AgentStore.TransactionKind.ADMISSION) {
            return;
        }
        if (batch == null) {
            throw new SQLException("Ja admission has no initial input batch");
        }

        AgentStore.OperationIdentity operation = transaction.operation().orElse(null);
        if (operation == null) {
            throw new SQLException("Ja admission has no operation identity");
        }
        AgentRequestBinding binding = requestBinding(transaction);
        String expectedRecordId = AgentInitialQueuedInputsCodec.recordId(
                transaction.execution(), operation.operationKey());
        if (!expectedRecordId.equals(batch.recordId())
            || !batch.recordId().equals(binding.initialQueuedInputsRecordId())) {
            throw new SQLException("Ja initial input batch does not match its operation or request binding");
        }
        try {
            initialQueuedInputs.decode(batch, transaction.execution(), operation.operationKey());
        } catch (RuntimeException invalid) {
            throw new SQLException("Ja initial input batch is invalid", invalid);
        }
    }

    /**
     * 取消请求必须与同事务 Core 事件、操作回执身份及执行身份一一对应，禁止按 Ja 状态猜测意图。
     */
    private void validateCancelRequest(AgentStoreTransactionContext transaction, CancelRequestFact request)
            throws SQLException {
        ExecutionEvent.ControlRecorded event = null;
        for (AgentStore.EventWrite written : transaction.events()) {
            if (!"agent.execution.control".equals(written.eventType())) {
                continue;
            }
            ExecutionEvent decoded;
            try {
                decoded = executionEvents.decode(written.eventType(), written.payloadSchemaVersion(),
                        jsonValues.encode(written.payload()));
            } catch (RuntimeException invalid) {
                throw new SQLException("Ja control event is invalid", invalid);
            }
            if (!(decoded instanceof ExecutionEvent.ControlRecorded control)) {
                throw new SQLException("Ja control event has an unsupported payload");
            }
            boolean cancellation = control.action() == ExecutionEvent.ControlAction.CANCEL_REQUESTED;
            if (!cancellation && (request == null || !request.recordId().equals(control.controlRecordId()))) {
                continue;
            }
            if (!cancellation || event != null) {
                throw new SQLException("Ja cancellation request event is duplicated or mismatched");
            }
            event = control;
            if (!written.occurredAt().equals(transaction.occurredAt())) {
                throw new SQLException("Ja cancellation event timestamp differs from its Store transaction");
            }
        }
        if (request == null) {
            if (event != null) {
                throw new SQLException("Ja cancellation event has no typed request record");
            }
            return;
        }
        if (transaction.kind() != AgentStore.TransactionKind.CONTROL || event == null
            || transaction.operation().isEmpty()
            || !transaction.operation().orElseThrow().operationKey().equals(request.request().operationKey())
            || !event.controlRecordId().equals(request.recordId())
            || !event.operationKey().equals(request.request().operationKey())
            || !event.reasonCode().equals(request.request().reasonCode())) {
            throw new SQLException("Ja cancellation request does not match its Core control transaction");
        }
    }

    /**
     * 终态 Task 只消费本次提交唯一的 typed Terminal 事件，不能从 Ja state 列猜终止原因或答复正文。
     */
    private ExecutionEvent.Terminal terminalEvent(AgentStoreTransactionContext transaction) throws SQLException {
        ExecutionEvent.Terminal found = null;
        for (AgentStore.EventWrite event : transaction.events()) {
            if (!"agent.execution.terminal".equals(event.eventType())) {
                continue;
            }
            ExecutionEvent decoded;
            try {
                decoded = executionEvents.decode(event.eventType(), event.payloadSchemaVersion(), jsonValues.encode(event.payload()));
            } catch (RuntimeException invalid) {
                throw new SQLException("Ja terminal event is invalid", invalid);
            }
            if (!(decoded instanceof ExecutionEvent.Terminal candidate) || found != null) {
                throw new SQLException("Ja transaction has multiple or invalid terminal events");
            }
            found = candidate;
        }
        boolean terminalState;
        try {
            terminalState = TurnState.valueOf(transaction.state()).terminal();
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("Ja transaction state is invalid", invalid);
        }
        if (terminalState == (found == null)
            || (found != null && !found.state().name().equals(transaction.state()))) {
            throw new SQLException("Ja terminal event does not match the committed execution state");
        }
        return found;
    }

    /**
     * 仅分派精确 schema 的 namespace envelope；applier 的 SQL 故障继续交由唯一事务控制者回滚。
     */
    private void applyExtensionFacts(AgentStoreTransactionContext transaction,
                                     List<DecodedExtensionFact> extensionFacts) throws SQLException {
        for (DecodedExtensionFact fact : extensionFacts) {
            ExtensionFactKey key = new ExtensionFactKey(fact.namespace(), fact.type(), fact.schemaVersion());
            JaExtensionFactApplier applier = extensionAppliers.get(key);
            if (applier == null) {
                throw new SQLException("Ja extension fact has no registered applier");
            }
            AgentStoreConnectionScope.call(JdbcTransactionResources.connection(transaction), () -> {
                applier.apply(transaction, fact.payload());
                return null;
            });
        }
    }

    /**
     * 只把精确匹配终态通知事务参与者；SQL 故障返回外层，覆盖未派发拒绝而不伪造业务回调。
     */
    private void applyToolCompletions(AgentStoreTransactionContext transaction,
                                      Map<String, ExecutionFact> executionFactsByRecordId) throws SQLException {
        Set<String> calls = new HashSet<>();
        for (AgentStore.EventWrite write : transaction.events()) {
            if (!"agent.tool.completed".equals(write.eventType())) {
                continue;
            }
            ExecutionEvent decoded;
            try {
                decoded = executionEvents.decode(write.eventType(), write.payloadSchemaVersion(), jsonValues.encode(write.payload()));
            } catch (RuntimeException invalid) {
                throw new SQLException("Ja tool completion event is invalid", invalid);
            }
            if (!(decoded instanceof ExecutionEvent.ToolCompleted completed)
                || !calls.add(completed.callId())
                || !(executionFactsByRecordId.get(completed.resultRecordId()) instanceof ToolResult result)
                || !completed.callId().equals(result.callId()) || completed.state() != result.state()) {
                throw new SQLException("Ja tool completion does not match its unique terminal result");
            }
            AgentStoreConnectionScope.call(JdbcTransactionResources.connection(transaction), () -> {
                for (JaExtensionFactApplier applier : extensionAppliers.values()) {
                    applier.applyToolCompletion(transaction, completed, result);
                }
                return null;
            });
        }
    }

    /**
     * 使用 Core 唯一 envelope codec 解码，避免 Ja 与通用 Store 对扩展外壳产生双重协议。
     */
    private DecodedExtensionFact decodeExtensionFact(AgentStore.StoredRecord record) throws SQLException {
        try {
            AgentTool.ExtensionFact decoded = extensionFactRecords.decode(record);
            return new DecodedExtensionFact(decoded.namespace(), decoded.type(), decoded.schemaVersion(),
                    decoded.payload());
        } catch (IllegalArgumentException failure) {
            throw new SQLException("Ja extension fact cannot be decoded", failure);
        }
    }

    /**
     * Extension envelope 校验完成后的中立载荷；只在当前 Store 事务栈上短暂存在。
     *
     * @param namespace 命名空间
     * @param type 类型标识
     * @param schemaVersion 数据结构版本
     * @param payload 负载
     */
    private record DecodedExtensionFact(String namespace,
                                        String type,
                                        int schemaVersion,
                                        JsonObject payload) {
    }

    /**
     * Extension applier 注册键显式包含 payload schema，避免向后猜测旧版结构。
     *
     * @param namespace 命名空间
     * @param type 类型标识
     * @param schemaVersion 数据结构版本
     */
    private record ExtensionFactKey(String namespace,
                                    String type,
                                    int schemaVersion) {
        /**
         * 注册键约束被集中在值对象边界，避免无效 handler 进入请求处理路径。
         */
        private ExtensionFactKey {
            if (namespace == null || namespace.isBlank() || type == null || type.isBlank()) {
                throw new IllegalArgumentException("invalid Ja extension fact registration");
            }
        }
    }

    /**
     * 保留取消记录身份与中立请求，供事件闭集校验和同事务 CAS 投影共用。
     *
     * @param recordId 记录标识
     * @param request 请求
     */
    private record CancelRequestFact(String recordId,
                                     ControlRequest request) {
    }
}
