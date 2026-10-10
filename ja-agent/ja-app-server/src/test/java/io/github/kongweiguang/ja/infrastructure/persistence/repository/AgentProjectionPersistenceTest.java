// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentAnswer;
import io.github.kongweiguang.ja.agent.application.engine.AgentRequestFingerprint;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.attachment.domain.model.AttachmentMetadata;
import io.github.kongweiguang.ja.conversation.domain.model.AttachmentSummary;
import io.github.kongweiguang.ja.conversation.domain.model.InputQueue;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionOption;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestion;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestionType;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionWait;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswerSubmission;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.AssistantMessage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.Approval;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ModelRequestIdentity;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolMessage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolPrepared;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolResult;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolStarted;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.Usage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.UsageCertainty;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.UsagePurpose;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.agent.domain.tool.ToolExecutionBinding;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ControlPayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.JacksonExecutionEventCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.PersistenceCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.agent.domain.context.CheckpointUsage;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExtensionFactRecordCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.MiddlewareStateRecordCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionEventCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.JaMessageIds;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadUsageSummary;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.model.TurnChangeSet;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.ProviderRequestUsage;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.model.ContextCompactionEvent;
import io.github.kongweiguang.ja.conversation.domain.model.TurnEvent;
import io.github.kongweiguang.ja.conversation.domain.model.TurnResult;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.application.gateway.ToolCallPreviewReadPort;
import io.github.kongweiguang.ja.conversation.application.presentation.ToolPresentationProjector;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TurnChangeSetCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaAgentEventBridge;
import io.github.kongweiguang.ja.infrastructure.persistence.store.ToolPresentationCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.support.AgentStoreProjectionFixture;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.transport.rpc.runtime.ActiveStreamRegistry;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 以真实 admission、typed Store 事实和 Ja 事务参与者验证历史、用量与身份投影。 */
final class AgentProjectionPersistenceTest extends PersistenceTestSupport {
    private static final Instant START = Instant.now().plusSeconds(60);

    /** UNKNOWN 估算先于首段正文提交时，历史轮次必须跟随执行游标，避免误拒重连基线。 */
    @Test
    void preservesLiveStreamBaselineWhenUnknownUsagePrecedesFirstDelta() throws Exception {
        try (TestDatabase database = database("agent-unknown-usage-stream")) {
            String threadId = "thr_unknown_stream";
            String turnId = "turn_unknown_stream";
            AgentStoreProjectionFixture fixture = admit(database, threadId, turnId);
            try (fixture) {
                ThreadSnapshot admitted = database.history().readThread(threadId, null, 20).orElseThrow();
                ThreadSnapshot.Turn initial = admitted.turns().getFirst();
                ActiveStreamRegistry streams = new ActiveStreamRegistry(1);
                streams.register(turnId, admitted.thread().workspaceId(), threadId, admitted.thread().revision(),
                        initial.mutationVersion(), initial.modelRound());

                fixture.commit(TurnState.RUNNING, cursor(), List.of(
                        usage("modelreq_unknown_stream", 1, 1, UsagePurpose.ASSISTANT,
                                UsageCertainty.UNKNOWN, profile("provider_test", "model_test", "cfg_test"),
                                null, 12_000L)), List.of(), START.plusSeconds(1));
                ThreadSnapshot running = database.history().readThread(threadId, null, 20).orElseThrow();
                ThreadSnapshot.Turn turn = running.turns().getFirst();
                assertEquals(0, turn.modelRound());
                assertEquals(ProviderRequestUsage.Certainty.UNKNOWN, running.contextUsage().request().certainty());

                streams.observeTurnCommit(threadId, turnId, running.thread().revision(),
                        turn.mutationVersion(), turn.modelRound(), null, START.plusSeconds(1));
                streams.observe(new TurnEvent.TextDelta(turnId, 1, "first text", "modelreq_unknown_stream"),
                        START.plusSeconds(2));
                ActiveStreamRegistry.Snapshot baseline = streams.snapshot(threadId, running.thread().revision(),
                        Set.of(turnId), Map.of(turnId, turn.mutationVersion()),
                        Map.of(turnId, turn.modelRound())).orElseThrow();
                assertEquals("first text", baseline.segments().getFirst().text());
            }
        }
    }

    /** 多 Provider 请求汇总时总量含缓存一次，新输入排除明确读写；测量时刻按数据库毫秒精度比较。 */
    @Test
    void readsThreadUsageSummaryAfterHistoryAdapterRecreation() throws Exception {
        try (TestDatabase database = database("agent-thread-usage-summary")) {
            AgentStoreProjectionFixture fixture = admit(database, "thr_usage", "turn_usage");
            try (fixture) {
                fixture.commit(TurnState.RUNNING, cursor(), List.of(
                        usage("modelreq_1", 1, 1, UsagePurpose.ASSISTANT, UsageCertainty.UNKNOWN,
                                profile("provider_test", "model_test", "cfg_test"), null, null),
                        usage("modelreq_2", 2, 2, UsagePurpose.ASSISTANT, UsageCertainty.UNKNOWN,
                                profile("provider_other", "model_other", "cfg_other"), null, null),
                        usage("modelreq_summary_3", 3, 3, UsagePurpose.SUMMARY, UsageCertainty.UNKNOWN,
                                profile("provider_test", "model_test", "cfg_test"), null, null)),
                        List.of(), START.plusSeconds(1));
                fixture.commit(TurnState.RUNNING, cursor(), List.of(
                        usage("modelreq_1", 1, 1, UsagePurpose.ASSISTANT, UsageCertainty.KNOWN,
                                profile("provider_test", "model_test", "cfg_test"),
                                new ModelUsage(100, 20, 170, 40L, 10L,
                                        ModelUsage.InputAccounting.INPUT_EXCLUDES_CACHE), null),
                        usage("modelreq_2", 2, 2, UsagePurpose.ASSISTANT,
                                profile("provider_other", "model_other", "cfg_other"),
                                new ModelUsage(180, 30, 210, 80L, null,
                                        ModelUsage.InputAccounting.INPUT_INCLUDES_CACHE)),
                        usage("modelreq_summary_3", 3, 3, UsagePurpose.SUMMARY,
                                profile("provider_test", "model_test", "cfg_test"),
                                new ModelUsage(90, 5, 95, 10L, 5L,
                                        ModelUsage.InputAccounting.INPUT_INCLUDES_CACHE)),
                        usage("modelreq_4", 4, 4, UsagePurpose.ASSISTANT, UsageCertainty.UNKNOWN,
                                profile("provider_test", "model_test", "cfg_test"), null, 34_000L)),
                        List.of(), START.plusSeconds(4));

                MybatisHistoryService history = database.history();
                assertUsageSummary(history.readThreadUsageSummary("thr_usage").orElseThrow());
                ThreadSnapshot.ContextUsage latest = history.readThread("thr_usage", null, 20)
                        .orElseThrow().contextUsage();
                assertEquals("modelreq_4", latest.request().modelRequestId());
                assertEquals(ProviderRequestUsage.Certainty.UNKNOWN, latest.request().certainty());
                assertEquals(34_000L, latest.estimatedContextTokens());
                assertEquals(Instant.ofEpochMilli(START.plusSeconds(4).toEpochMilli()),
                        latest.estimatedContextMeasuredAt());
                history.readThread("thr_usage", null, 1).orElseThrow();
                assertUsageSummary(database.history().readThreadUsageSummary("thr_usage").orElseThrow());
            }
        }
    }

    /** Checkpoint 使本地估算失效而不改写已确认账本；adapter 重建后下一真实请求提供新计量。 */
    @Test
    void checkpointInvalidatesUsageAcrossHistoryAdapterRecreationWithoutRewritingLedger() throws Exception {
        try (TestDatabase database = database("agent-checkpoint-usage")) {
            AgentStoreProjectionFixture fixture = admit(database, "thr_checkpoint", "turn_checkpoint");
            try (fixture) {
                ModelRequestIdentity profile = profile("provider_test", "model_test", "cfg_test");
                fixture.commit(TurnState.RUNNING, cursor(), List.of(
                        usage("modelreq_checkpoint", 1, 1, UsagePurpose.ASSISTANT, UsageCertainty.UNKNOWN,
                                profile, null, 41_000L)), List.of(), START.plusSeconds(1));
                fixture.commit(TurnState.RUNNING, cursor(), List.of(
                        usage("modelreq_checkpoint", 1, 1, UsagePurpose.ASSISTANT, UsageCertainty.KNOWN,
                                profile, new ModelUsage(42_000, 8, 42_008), null)), List.of(), START.plusSeconds(2));
                assertEquals(ProviderRequestUsage.Certainty.KNOWN, database.history()
                        .readThread("thr_checkpoint", null, 100).orElseThrow().contextUsage().request().certainty());

                long revision = fixture.conversationSnapshot().revision();
                CheckpointStore.ContextCheckpoint checkpoint = new CheckpointStore.ContextCheckpoint(
                        "checkpoint_usage", "thr_checkpoint", 1, 2, revision, Optional.empty(),
                        SummaryDocument.empty(), 100, "0".repeat(64), ContextCompactionEvent.STRATEGY_VERSION,
                        CheckpointUsage.none(), START.plusSeconds(3));
                fixture.commitCheckpoint(cursor(), checkpoint, 42_000, START.plusSeconds(3));

                ThreadSnapshot.ContextUsage invalidated = database.history()
                        .readThread("thr_checkpoint", null, 100).orElseThrow().contextUsage();
                assertEquals(ProviderRequestUsage.Certainty.UNKNOWN, invalidated.request().certainty());
                assertNull(invalidated.request().usage());
                assertEquals("modelreq_checkpoint", invalidated.request().modelRequestId());
                assertEquals(100L, invalidated.estimatedContextTokens());
                assertEquals(START.plusSeconds(3), invalidated.estimatedContextMeasuredAt());
                try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession();
                     Statement sql = session.getConnection().createStatement();
                     java.sql.ResultSet rows = sql.executeQuery("""
                         SELECT certainty,input_tokens,estimated_context_tokens \
                         FROM ja_turn_usage WHERE model_request_id='modelreq_checkpoint'\
                         """)) {
                    assertTrue(rows.next());
                    assertEquals("KNOWN", rows.getString(1));
                    assertEquals(42_000, rows.getLong(2));
                    assertNull(rows.getObject(3));
                }

                fixture.commit(TurnState.RUNNING, cursor(), List.of(
                        usage("modelreq_after_checkpoint", 2, 2, UsagePurpose.ASSISTANT,
                                UsageCertainty.UNKNOWN, profile("provider_test", "model_test", "cfg_test"),
                                null, 100L)), List.of(), START.plusSeconds(4));
                fixture.commit(TurnState.RUNNING, cursor(), List.of(
                        usage("modelreq_after_checkpoint", 2, 2, UsagePurpose.ASSISTANT,
                                profile("provider_test", "model_test", "cfg_test"),
                                new ModelUsage(100, 8, 108))), List.of(), START.plusSeconds(5));
                ThreadSnapshot.ContextUsage fresh = database.history()
                        .readThread("thr_checkpoint", null, 100).orElseThrow().contextUsage();
                assertEquals(ProviderRequestUsage.Certainty.KNOWN, fresh.request().certainty());
                assertEquals(100, fresh.request().usage().inputTokens());
                assertNull(fresh.estimatedContextTokens());
            }
        }
    }

    /** 历史快照从真实进程工具投影重建 RUNNING 命令，不能丢失会话入口或停留在 PREPARED。 */
    @Test
    void restoresRunningPresentationAfterStartedCommit() throws Exception {
        try (TestDatabase database = database("agent-projection-running")) {
            AgentStoreProjectionFixture fixture = admit(database, "thr_running", "turn_running");
            try (fixture) {
                String callId = prepareToolCall(fixture, "call_running", START.plusSeconds(1));
                fixture.commitRecords(TurnState.RUNNING, cursor(), List.of(
                        fixture.record("agent.tool.started", "started-running",
                                new ToolStarted(callId, 0), START.plusSeconds(3))),
                        List.of(new ExecutionEvent.ToolStarted(callId, "batch_" + callId, "exec_command", 0)),
                        START.plusSeconds(3));

                ThreadSnapshot snapshot = database.history().readThread("thr_running", null, 100).orElseThrow();
                ThreadSnapshot.ToolItem tool = snapshot.items().stream()
                        .filter(ThreadSnapshot.ToolItem.class::isInstance)
                        .map(ThreadSnapshot.ToolItem.class::cast).findFirst().orElseThrow();
                assertEquals(callId, tool.callId());
                assertEquals("exec_command", tool.toolName());
                assertEquals(ToolPresentation.Kind.SHELL, tool.presentation().kind());
                assertEquals("执行命令", tool.presentation().title());
                assertEquals("echo ok", tool.presentation().command());
                assertEquals(ToolPresentation.Status.RUNNING, tool.presentation().status());
            }
        }
    }

    /** approval wait 与其 Core middleware snapshot 必须同事务落库，未知快照版本整笔回滚。 */
    @Test
    void commitsApprovalWaitWithTypedMiddlewareStateAtomically() throws Exception {
        try (TestDatabase database = database("agent-approval-middleware-state")) {
            AgentStoreProjectionFixture fixture = admit(database, "thr_middleware_wait", "turn_middleware_wait");
            try (fixture) {
                String callId = "call_middleware_wait";
                String approvalId = "approval_middleware_wait";
                Instant expiresAt = START.plusSeconds(300);
                prepareToolCall(fixture, callId, START.plusSeconds(1), approvalId, expiresAt);

                Instant waitAt = START.plusSeconds(5);
                InteractionWait wait = approvalWait(approvalId, callId, expiresAt, waitAt);
                AgentStore.RecordWrite waitRecord = fixture.controls().encodeWait(
                        fixture.recordId(ExecutionControlCodec.WAIT_RECORD_TYPE, "wait:" + approvalId), wait, waitAt);
                AgentStore.RecordWrite stateRecord = approvalMiddlewareState(fixture, waitAt, wait.interactionId());
                AgentStore.RecordWrite unknownStateVersion = new AgentStore.RecordWrite(stateRecord.recordId(),
                        stateRecord.recordType(), MiddlewareStateRecordCodec.ENVELOPE_VERSION + 1,
                        stateRecord.payload(), stateRecord.occurredAt());

                AgentStore.StoreParticipantException rejected = assertThrows(
                        AgentStore.StoreParticipantException.class,
                        () -> fixture.commitRecords(TurnState.WAITING_APPROVAL, cursor(),
                                List.of(waitRecord, unknownStateVersion),
                                List.of(new ExecutionEvent.WaitRequested(wait.interactionId(), "approval",
                                        waitRecord.recordId())), waitAt));
                assertTrue(rejected.getMessage().contains("participant"));
                assertRecordAbsent(database, waitRecord.recordId());
                assertRecordAbsent(database, stateRecord.recordId());
                assertPendingApproval(database, approvalId, "thr_middleware_wait", "turn_middleware_wait",
                        callId, expiresAt);
                assertEquals(0, interactionRequestCount(database, approvalId));

                fixture.commitRecords(TurnState.WAITING_APPROVAL, cursor(), List.of(waitRecord, stateRecord),
                        List.of(new ExecutionEvent.WaitRequested(wait.interactionId(), "approval", waitRecord.recordId())),
                        waitAt);

                assertRecordPresent(database, waitRecord.recordId(), ExecutionControlCodec.WAIT_RECORD_TYPE,
                        ExecutionControlCodec.WAIT_SCHEMA_VERSION);
                assertRecordPresent(database, stateRecord.recordId(), MiddlewareStateRecordCodec.RECORD_TYPE,
                        MiddlewareStateRecordCodec.ENVELOPE_VERSION);
                assertPendingApproval(database, approvalId, "thr_middleware_wait", "turn_middleware_wait",
                        callId, expiresAt);
                assertEquals(0, interactionRequestCount(database, approvalId));
            }
        }
    }

    /**
     * ThreadRead 按同一审批身份和 WAITING_APPROVAL owner 派生等待态，不改写 Tool 的准备边界。
     * @author kongweiguang
     */
    @Test
    void snapshotsPendingApprovalAsWaitingWithoutChangingStoredToolPresentation() throws Exception {
        try (TestDatabase database = database("agent-approval-snapshot-state")) {
            String threadId = "thr_approval_snapshot";
            String turnId = "turn_approval_snapshot";
            String callId = "call_approval_snapshot";
            String approvalId = "approval_snapshot";
            Instant expiresAt = START.plusSeconds(300);
            AgentStoreProjectionFixture fixture = admit(database, threadId, turnId);
            try (fixture) {
                prepareToolCall(fixture, callId, START.plusSeconds(1), approvalId, expiresAt);
                InteractionWait wait = approvalWait(approvalId, callId, expiresAt, START.plusSeconds(5));
                AgentStore.RecordWrite waitRecord = fixture.controls().encodeWait(
                        fixture.recordId(ExecutionControlCodec.WAIT_RECORD_TYPE, "wait:" + approvalId),
                        wait, wait.createdAt());
                AgentStore.RecordWrite stateRecord = approvalMiddlewareState(
                        fixture, wait.createdAt(), wait.interactionId());
                fixture.commitRecords(TurnState.WAITING_APPROVAL, cursor(), List.of(waitRecord, stateRecord),
                        List.of(new ExecutionEvent.WaitRequested(wait.interactionId(), "approval", waitRecord.recordId())),
                        wait.createdAt());

                assertEquals(ToolPresentation.Status.WAITING_APPROVAL,
                        snapshotToolStatus(database, threadId, callId));
                assertEquals(ToolPresentation.Status.WAITING_APPROVAL,
                        database.history().readThreadLatest(threadId, null, 20).orElseThrow().items().stream()
                                .filter(ThreadSnapshot.ToolItem.class::isInstance)
                                .map(ThreadSnapshot.ToolItem.class::cast)
                                .filter(item -> callId.equals(item.callId()))
                                .map(item -> item.presentation().status())
                                .findFirst().orElseThrow());
                assertPendingApproval(database, approvalId, threadId, turnId, callId, expiresAt);

                try (SqlSession session = database.sessions().openSession();
                     PreparedStatement query = session.getConnection().prepareStatement(
                             "SELECT state,presentation_json FROM ja_tool_call WHERE thread_id=? AND turn_id=? AND call_id=?")) {
                    query.setString(1, threadId);
                    query.setString(2, turnId);
                    query.setString(3, callId);
                    try (ResultSet rows = query.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals("PREPARED", rows.getString(1));
                        assertEquals("pending", new ObjectMapper().readTree(rows.getString(2)).path("status").asText());
                        assertFalse(rows.next());
                    }
                }

                String unrelatedCallId = "call_approval_snapshot_unrelated";
                String unrelatedPresentation = new ToolPresentationCodec(new ObjectMapper())
                        .write(presentation(ToolPresentation.Status.PENDING, null, "echo ok"));
                try (SqlSession session = database.sessions().openSession();
                     PreparedStatement insert = session.getConnection().prepareStatement("""
                             INSERT INTO ja_tool_call(call_id,thread_id,turn_id,ordinal,tool_name,side_effect,
                             presentation_json,state,revision,created_at_ms,updated_at_ms)
                             VALUES(?,?,?,1,'exec_command','EXTERNAL',?,'PREPARED',0,?,?)
                             """)) {
                    insert.setString(1, unrelatedCallId);
                    insert.setString(2, threadId);
                    insert.setString(3, turnId);
                    insert.setString(4, unrelatedPresentation);
                    insert.setLong(5, START.plusSeconds(2).toEpochMilli());
                    insert.setLong(6, START.plusSeconds(2).toEpochMilli());
                    assertEquals(1, insert.executeUpdate());
                    session.commit();
                }
                assertEquals(ToolPresentation.Status.PENDING,
                        snapshotToolStatus(database, threadId, unrelatedCallId));

                try (SqlSession session = database.sessions().openSession();
                     PreparedStatement update = session.getConnection().prepareStatement(
                             "UPDATE ja_approval SET decision='APPROVE',resolved_at_ms=? WHERE approval_id=?")) {
                    update.setLong(1, START.plusSeconds(6).toEpochMilli());
                    update.setString(2, approvalId);
                    assertEquals(1, update.executeUpdate());
                    session.commit();
                }
                assertEquals(ToolPresentation.Status.PENDING, snapshotToolStatus(database, threadId, callId));

                try (SqlSession session = database.sessions().openSession();
                     PreparedStatement resetApproval = session.getConnection().prepareStatement(
                             "UPDATE ja_approval SET decision=NULL,resolved_at_ms=NULL WHERE approval_id=?");
                     PreparedStatement finishTurn = session.getConnection().prepareStatement(
                             "UPDATE ja_conversation_turn SET state='COMPLETED',completed_at_ms=?,terminal_summary=? "
                                     + "WHERE thread_id=? AND turn_id=?")) {
                    resetApproval.setString(1, approvalId);
                    assertEquals(1, resetApproval.executeUpdate());
                    finishTurn.setLong(1, START.plusSeconds(7).toEpochMilli());
                    finishTurn.setString(2, "completed");
                    finishTurn.setString(3, threadId);
                    finishTurn.setString(4, turnId);
                    assertEquals(1, finishTurn.executeUpdate());
                    session.commit();
                }
                assertEquals(ToolPresentation.Status.PENDING, snapshotToolStatus(database, threadId, callId));

                try (SqlSession session = database.sessions().openSession();
                     PreparedStatement restoreWait = session.getConnection().prepareStatement(
                             "UPDATE ja_conversation_turn SET state='WAITING_APPROVAL',completed_at_ms=NULL,terminal_summary=NULL "
                                     + "WHERE thread_id=? AND turn_id=?");
                     PreparedStatement startTool = session.getConnection().prepareStatement(
                             "UPDATE ja_tool_call SET state='RUNNING',presentation_json=?,revision=revision+1 "
                                     + "WHERE thread_id=? AND turn_id=? AND call_id=?")) {
                    restoreWait.setString(1, threadId);
                    restoreWait.setString(2, turnId);
                    assertEquals(1, restoreWait.executeUpdate());
                    startTool.setString(1, new ToolPresentationCodec(new ObjectMapper())
                            .write(presentation(ToolPresentation.Status.RUNNING, null, "echo ok")));
                    startTool.setString(2, threadId);
                    startTool.setString(3, turnId);
                    startTool.setString(4, callId);
                    assertEquals(1, startTool.executeUpdate());
                    session.commit();
                }
                assertEquals(ToolPresentation.Status.RUNNING, snapshotToolStatus(database, threadId, callId));

                try (SqlSession session = database.sessions().openSession();
                     PreparedStatement restorePreparedState = session.getConnection().prepareStatement(
                             "UPDATE ja_tool_call SET state='PREPARED',revision=revision+1 "
                                     + "WHERE thread_id=? AND turn_id=? AND call_id=?")) {
                    restorePreparedState.setString(1, threadId);
                    restorePreparedState.setString(2, turnId);
                    restorePreparedState.setString(3, callId);
                    assertEquals(1, restorePreparedState.executeUpdate());
                    session.commit();
                }
                assertEquals(ToolPresentation.Status.RUNNING, snapshotToolStatus(database, threadId, callId));
            }
        }
    }

    /** 无租约 controlCommit 的回答仍须经精确 typed wait、answer 与 resolution event 原子结算。 */
    @Test
    void settlesApprovalAnswerFromTypedControlTransaction() throws Exception {
        try (TestDatabase database = database("agent-approval-answer-control")) {
            AgentStoreProjectionFixture fixture = admit(database, "thr_approval_answer", "turn_approval_answer");
            try (fixture) {
                String callId = "call_approval_answer";
                String approvalId = "approval_answer_control";
                Instant expiresAt = START.plusSeconds(300);
                prepareToolCall(fixture, callId, START.plusSeconds(1), approvalId, expiresAt);

                Instant waitAt = START.plusSeconds(5);
                InteractionWait pending = approvalWait(approvalId, callId, expiresAt, waitAt);
                AgentStore.RecordWrite waitRecord = fixture.controls().encodeWait(
                        fixture.recordId(ExecutionControlCodec.WAIT_RECORD_TYPE, "wait:" + approvalId), pending, waitAt);
                AgentStore.RecordWrite stateRecord = approvalMiddlewareState(fixture, waitAt, pending.interactionId());
                fixture.commitRecords(TurnState.WAITING_APPROVAL, cursor(), List.of(waitRecord, stateRecord),
                        List.of(new ExecutionEvent.WaitRequested(pending.interactionId(), "approval", waitRecord.recordId())),
                        waitAt);

                Instant answeredAt = waitAt.plusSeconds(2);
                String clientOperationId = "op_0123456789abcdef0123456789abcdef";
                long expectedThreadRevision = fixture.conversationSnapshot().revision();
                String requestFingerprint = "d".repeat(64);
                InteractionAnswer answer = new InteractionAnswer("question_approval",
                        List.of("option_approve"), null, false);
                Map<String, String> hostMetadata = Map.of(
                        "ja.operationKey", clientOperationId,
                        "ja.requestFingerprint", requestFingerprint,
                        "ja.expectedThreadRevision", Long.toString(expectedThreadRevision),
                        "ja.approvalId", approvalId,
                        "ja.decision", "approve");
                AgentAnswer request = new AgentAnswer(approvalId, clientOperationId,
                        pending.interactionRevision(),
                        List.of(answer), hostMetadata);
                AgentStore.OperationIdentity operation = AgentRequestFingerprint.prepareAnswer(
                        identity("thr_approval_answer", "turn_approval_answer"), request);
                assertNotEquals(clientOperationId, operation.operationKey());
                assertNotEquals(requestFingerprint, operation.fingerprint());
                String answerId = fixture.recordId(ExecutionControlCodec.ANSWER_SUBMISSION_RECORD_TYPE,
                        operation.operationKey());
                InteractionAnswerSubmission submission = new InteractionAnswerSubmission(answerId,
                        pending.interactionId(), pending.interactionRevision(), operation.operationKey(), List.of(answer),
                        hostMetadata, answeredAt);
                InteractionWait answered = pending.answer(List.of(answer), answeredAt);
                AgentStore.RecordWrite answerRecord = fixture.controls().encodeAnswerSubmission(answerId, submission);
                AgentStore.RecordWrite answeredWaitRecord = fixture.controls().encodeWait(
                        fixture.recordId(ExecutionControlCodec.WAIT_RECORD_TYPE, "answered:" + approvalId),
                        answered, answeredAt);
                AgentStore.RecordWrite approvalDecision = fixture.record("agent.approval", "decision:" + approvalId,
                        new Approval(approvalId, callId, ApprovalDecision.APPROVE, expiresAt), answeredAt);
                AgentStore.RecordWrite settledState = approvalAnswerMiddlewareState(
                        fixture, answeredAt, pending.interactionId());

                AgentStore store = database.jaAgentStore();
                AgentStore.ConversationSnapshot conversation = store.loadConversation(fixture.execution().conversation())
                        .orElseThrow();
                AgentStore.ExecutionSnapshot execution = store.loadExecution(fixture.execution()).orElseThrow();
                AgentStore.WaitControl waitControl = new AgentStore.WaitControl(
                        pending.interactionId(), pending.interactionRevision(), Optional.of(answerId));
                AgentStore.CommitReceipt receipt = store.controlCommit(new AgentStore.ControlCommit(
                        fixture.execution(), conversation.revision(), execution.state(), execution.mutationVersion(),
                        AgentStore.ControlKind.WAIT_SETTLEMENT,
                        List.of(answerRecord, answeredWaitRecord, approvalDecision, settledState),
                        List.of(fixture.event("answer-state", new ExecutionEvent.StateChanged(
                                TurnState.WAITING_APPROVAL, TurnState.SUSPENDED), answeredAt),
                                fixture.event("answer-resolved", new ExecutionEvent.ControlRecorded(
                                        ExecutionEvent.ControlAction.WAIT_RESOLVED, answerId,
                                        operation.operationKey(), null),
                                        answeredAt)),
                        operation, Optional.of(waitControl), answeredAt));

                assertTrue(receipt.newlyCommitted());
                assertAnsweredApproval(database, approvalId, "thr_approval_answer", "turn_approval_answer",
                        callId, clientOperationId, requestFingerprint, answeredAt);
                assertEquals(0, interactionRequestCount(database, approvalId));
            }
        }
    }

    /** 基于持久事实重建历史快照，并核对 Tool、usage 与净 diff 的终态 Bridge 投影。 */
    @Test
    void restoresSafeTimelineAndIsolatedArtifactsFromPersistedStoreFacts() throws Exception {
        try (TestDatabase database = database("agent-projection-persisted-facts")) {
            AgentStoreProjectionFixture fixture = admit(database, "thr_artifacts", "turn_artifacts");
            try (fixture) {
                String callId = "call_artifact";
                prepareToolCall(fixture, callId, START.plusSeconds(1));
                fixture.commitRecords(TurnState.RUNNING, cursor(), List.of(
                        fixture.record("agent.tool.started", "started-artifact",
                                new ToolStarted(callId, 0), START.plusSeconds(4))),
                        List.of(new ExecutionEvent.ToolStarted(callId, "batch_artifact", "exec_command", 0)),
                        START.plusSeconds(4));

                String toolContent = "甲😀乙\nstdout";
                String toolArtifactId = "artifact_tool_agent";
                ToolPresentation completedPresentation = presentation(ToolPresentation.Status.SUCCESS,
                        toolArtifactId, toolContent);
                AgentStore.RecordWrite resultWrite = fixture.record("agent.tool.result", "result-artifact",
                        new ToolResult(callId, ToolState.SUCCEEDED, toolContent, false, toolContent),
                        START.plusSeconds(5));
                AgentStore.RecordWrite messageWrite = fixture.record("agent.tool.message", "message-artifact",
                        new ToolMessage("item_tool_message", new ModelMessage(ModelRole.TOOL,
                                List.of(new ToolResultContent(callId, toolContent, false)))), START.plusSeconds(5));
                AgentStore.RecordWrite usageWrite = fixture.record("agent.usage", "usage-artifact",
                        usage("modelreq_artifact", 2, 2, UsagePurpose.ASSISTANT, UsageCertainty.UNKNOWN,
                                profile("provider_test", "model_test", "cfg_test"), null, 42_000L), START.plusSeconds(5));
                AgentStore.RecordWrite completed = presentationFact(fixture, callId, 1, ToolState.SUCCEEDED,
                        completedPresentation, START.plusSeconds(5));
                AgentStore.RecordWrite artifact = toolArtifactFact(
                        fixture, callId, toolArtifactId, toolContent, START.plusSeconds(5));
                fixture.commitRecords(TurnState.RUNNING, cursor(),
                        List.of(resultWrite, messageWrite, usageWrite, completed, artifact),
                        List.of(new ExecutionEvent.ToolCompleted(callId, ToolState.SUCCEEDED,
                                resultWrite.recordId(), messageWrite.recordId(), null)), START.plusSeconds(5));

                String diff = "--- a/甲.txt\n+++ b/甲.txt\n@@ -1,1 +1,1 @@\n-甲\n+乙😀\n";
                TurnChangeSet changeSet = changeSet("artifact_change_agent");
                AgentStore.RecordWrite finalAssistant = fixture.record("agent.assistant", "assistant-final",
                        new AssistantMessage("item_final", new ModelMessage(ModelRole.ASSISTANT,
                                List.of(new TextContent("done"))), "done", null, 3), START.plusSeconds(6));
                AgentStore.RecordWrite finalUsage = fixture.record("agent.usage", "usage-final",
                        usage("modelreq_final", 3, 3, UsagePurpose.ASSISTANT,
                                UsageCertainty.UNKNOWN, profile("provider_test", "model_test", "cfg_test"),
                                null, 42_000L),
                        START.plusSeconds(6));
                fixture.commitRecords(TurnState.COMPLETED, completionCursor(), List.of(finalAssistant, finalUsage,
                                changeSetFact(database.mapper(), fixture, changeSet,
                                        START.plusSeconds(6)),
                                changeSetArtifactFact(fixture, changeSet.artifactId(), diff, START.plusSeconds(6))),
                        List.of(new ExecutionEvent.Terminal(TurnState.COMPLETED, "done", null, null,
                                finalAssistant.recordId(), finalUsage.recordId())), START.plusSeconds(6));

                MybatisHistoryService history = database.history();
                ThreadSnapshot snapshot = history.readThread("thr_artifacts", null, 100).orElseThrow();
                assertEquals(changeSet, snapshot.turns().getFirst().changeSet());
                assertEquals(changeSet, terminalResult(database, fixture,
                        identity("thr_artifacts", "turn_artifacts")).terminal().changeSet());
                assertEquals("modelreq_final", snapshot.contextUsage().request().modelRequestId());
                assertEquals(ProviderRequestUsage.Certainty.UNKNOWN, snapshot.contextUsage().request().certainty());
                assertNull(snapshot.contextUsage().request().usage());
                ThreadSnapshot.ToolItem tool = snapshot.items().stream()
                        .filter(ThreadSnapshot.ToolItem.class::isInstance)
                        .map(ThreadSnapshot.ToolItem.class::cast).findFirst().orElseThrow();
                assertEquals(ToolPresentation.Status.SUCCESS, tool.presentation().status());
                assertEquals("命令已完成", tool.presentation().summary());

                ThreadUseCase.TextArtifactPage first = history.readToolArtifact(
                        "thr_artifacts", "turn_artifacts", callId, toolArtifactId, 0, 2).orElseThrow();
                ThreadUseCase.TextArtifactPage second = history.readToolArtifact(
                        "thr_artifacts", "turn_artifacts", callId, toolArtifactId,
                        first.nextOffsetCharacters(), 64).orElseThrow();
                assertEquals(toolContent, first.content() + second.content());
                assertTrue(history.readToolArtifact("thr_elsewhere", "turn_artifacts", callId,
                        toolArtifactId, 0, 64).isEmpty());
                assertTrue(history.readToolArtifact("thr_artifacts", "turn_elsewhere", callId,
                        toolArtifactId, 0, 64).isEmpty());
                assertTrue(history.readToolArtifact("thr_artifacts", "turn_artifacts", "call_elsewhere",
                        toolArtifactId, 0, 64).isEmpty());

                ThreadUseCase.ChangeSetArtifactFile restored = history.readChangeSetArtifact(
                        "thr_artifacts", "turn_artifacts", changeSet.artifactId(), "甲.txt").orElseThrow();
                assertEquals(diff, new String(Base64.getDecoder().decode(restored.contentBase64()),
                        StandardCharsets.UTF_8));
                assertEquals(diff.getBytes(StandardCharsets.UTF_8).length, restored.byteLength());
                assertFalse(history.readChangeSetArtifact(
                        "thr_artifacts", "turn_elsewhere", changeSet.artifactId(), "甲.txt").isPresent());
            }
        }
    }

    /** 失效附件只以 identity 和明确修复状态呈现，UPDATE 命令清除问题后才能回到待消费队列。 */
    @Test
    void restoresAndRepairsUnavailableQueuedAttachment() throws Exception {
        try (TestDatabase database = database("agent-projection-unavailable-attachment")) {
            AgentStoreProjectionFixture fixture = admit(database, "thr_queue", "turn_queue");
            try (fixture) {
                StorePayloadCodec facts = new StorePayloadCodec(database.mapper());
                ControlPayloadCodec controls = new ControlPayloadCodec(database.mapper());
                Instant enqueueAt = START.plusSeconds(1);
                UserContent original = new UserContent(List.of(new TextContent("queued text")));
                AgentStore.RecordWrite queuedBody = facts.encodeQueuedUser(
                        "record_queued_original", "item_queued_original", original, enqueueAt);
                AgentInputQueue.Command enqueue = new AgentInputQueue.Command("command_enqueue_broken",
                        AgentInputQueue.Action.ENQUEUE, "input_broken", AgentInputQueue.Kind.FOLLOW_UP,
                        0, 1, queuedBody.recordId(), null, enqueueAt, Map.of());
                AgentStore.RecordWrite enqueueCommand = controls.encodeInputQueueCommand(
                        enqueue.commandId(), enqueue, enqueueAt);
                fixture.commitRecords(TurnState.RUNNING, cursor(), List.of(queuedBody, enqueueCommand),
                        List.of(), enqueueAt);

                UserContent broken = new UserContent(List.of(new AttachmentContent("att_missing"),
                        new TextContent("queued text")));
                String brokenJson = new PersistenceCodec(database.mapper()).writeUserContent(broken);
                try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession();
                     java.sql.PreparedStatement update = session.getConnection().prepareStatement("""
                         UPDATE ja_turn_input SET content_json=? \
                         ,validation_status='NEEDS_ATTENTION',issue_error_code='ATTACHMENT_UNAVAILABLE',\
                         issue_message='附件不可用，请移除后继续。',is_issue_retryable=0 WHERE input_id='input_broken'\
                         """)) {
                    update.setString(1, brokenJson);
                    assertEquals(1, update.executeUpdate());
                    session.commit();
                }

                ThreadSnapshot brokenSnapshot = database.history().readThread("thr_queue", null, 100).orElseThrow();
                AgentInputQueueSnapshotAssertions.assertNeedsAttention(brokenSnapshot);

                Instant updateAt = START.plusSeconds(2);
                UserContent repaired = new UserContent(List.of(new TextContent("已移除失效附件")));
                AgentStore.RecordWrite repairedBody = facts.encodeQueuedUser(
                        "record_queued_repaired", "item_queued_repaired", repaired, updateAt);
                AgentInputQueue.Command updateCommand = new AgentInputQueue.Command("command_update_broken",
                        AgentInputQueue.Action.UPDATE, "input_broken", null, 1, 2,
                        repairedBody.recordId(), null, updateAt, Map.of());
                fixture.commitRecords(TurnState.RUNNING, cursor(), List.of(repairedBody,
                                controls.encodeInputQueueCommand(updateCommand.commandId(), updateCommand, updateAt)),
                        List.of(), updateAt);

                InputQueue.QueuedInput fixed = database.history().readThread("thr_queue", null, 100)
                        .orElseThrow().inputQueue().items().getFirst();
                assertEquals(InputQueue.Status.PENDING, fixed.status());
                assertNull(fixed.issue());
                assertTrue(fixed.attachments().isEmpty());
                assertTrue(fixed.content().attachmentIds().isEmpty());
                assertEquals(2, fixed.inputRevision());
            }
        }
    }

    /** 持久 terminal 的可选 message ref 缺失时不能借同文案的旧 assistant 代替失败结果。 */
    @Test
    void failedTerminalWithoutAssistantDoesNotBorrowPriorMessage() throws Exception {
        try (TestDatabase database = database("agent-terminal-without-message")) {
            AgentStoreProjectionFixture fixture = admit(database, "thr_failed_identity", "turn_failed_identity");
            AgentIdentity identity = identity("thr_failed_identity", "turn_failed_identity");
            try (fixture) {
                AgentStore.RecordWrite prior = fixture.record("agent.assistant", "prior-assistant",
                        new AssistantMessage("item_prior_answer", new ModelMessage(ModelRole.ASSISTANT,
                                List.of(new TextContent("automatic failure"))), "automatic failure", null, 1),
                        START.plusSeconds(1));
                fixture.commitRecords(TurnState.RUNNING, cursor(), List.of(prior), List.of(), START.plusSeconds(1));
                fixture.commitRecords(TurnState.FAILED, completionCursor(), List.of(),
                        List.of(new ExecutionEvent.Terminal(TurnState.FAILED, "automatic failure",
                                "MODEL_UNAVAILABLE", "Provider unavailable", null, null)), START.plusSeconds(2));

                TurnEvent.Terminal terminal = terminalResult(database, fixture, identity).terminal();
                assertNull(terminal.finalMessage());
                assertNull(terminal.changeSet());
                assertEquals("MODEL_UNAVAILABLE", terminal.errorCode());
                assertTrue(database.history().readThread("thr_failed_identity", null, 100).orElseThrow()
                        .items().stream().filter(ThreadSnapshot.TextItem.class::isInstance)
                        .map(ThreadSnapshot.TextItem.class::cast)
                        .noneMatch(item -> item.kind() == ThreadSnapshot.TextKind.FINAL_ANSWER));
            }
        }
    }

    /** 失败 ChangeSet 只要在终态水位前真实提交，就必须原样投影而不能因失败被清空。 */
    @Test
    void failedTerminalPreservesCommittedChangeSet() throws Exception {
        try (TestDatabase database = database("agent-failed-terminal-change-set")) {
            AgentStoreProjectionFixture fixture = admit(database, "thr_failed_change_set", "turn_failed_change_set");
            AgentIdentity identity = identity("thr_failed_change_set", "turn_failed_change_set");
            try (fixture) {
                TurnChangeSet committed = new TurnChangeSet(TurnChangeSet.State.PARTIAL,
                        Set.of(TurnChangeSet.IncompleteReason.CAPTURE_FAILED), List.of(),
                        new TurnChangeSet.Stats(0, 0, 0, 0, false), null);
                fixture.commitRecords(TurnState.FAILED, completionCursor(), List.of(
                                changeSetFact(database.mapper(), fixture, committed, START.plusSeconds(1))),
                        List.of(new ExecutionEvent.Terminal(TurnState.FAILED, "Provider failed",
                                "MODEL_UNAVAILABLE", "Provider unavailable", null, null)), START.plusSeconds(1));

                TurnEvent.Terminal terminal = terminalResult(database, fixture, identity).terminal();

                assertEquals(committed, terminal.changeSet());
                assertNull(terminal.finalMessage());
            }
        }
    }

    /** 相同可见文案不决定最终消息；Bridge 必须按 terminal event 指向的 record 精确恢复 ID。 */
    @Test
    void terminalMessageUsesPersistedRecordIdentityInsteadOfMatchingText() throws Exception {
        try (TestDatabase database = database("agent-terminal-exact-identity")) {
            AgentStoreProjectionFixture fixture = admit(database, "thr_terminal_identity", "turn_terminal_identity");
            AgentIdentity identity = identity("thr_terminal_identity", "turn_terminal_identity");
            try (fixture) {
                AgentStore.RecordWrite prior = fixture.record("agent.assistant", "prior-same-text",
                        new AssistantMessage("item_prior_same_text", new ModelMessage(ModelRole.ASSISTANT,
                                List.of(new TextContent("same close text"))), "same close text", null, 1),
                        START.plusSeconds(1));
                fixture.commitRecords(TurnState.RUNNING, cursor(), List.of(prior), List.of(), START.plusSeconds(1));
                AgentStore.RecordWrite finalMessage = fixture.record("agent.assistant", "terminal-message",
                        new AssistantMessage("item_terminal_persisted", new ModelMessage(ModelRole.ASSISTANT,
                                List.of(new TextContent("same close text"))), "same close text", null, 2),
                        START.plusSeconds(2));
                AgentStore.RecordWrite usage = fixture.record("agent.usage", "terminal-usage",
                        usage("modelreq_terminal", 2, 2, UsagePurpose.ASSISTANT, UsageCertainty.UNKNOWN,
                                profile("provider_test", "model_test", "cfg_test"), null, null), START.plusSeconds(2));
                fixture.commitRecords(TurnState.RUNNING, cursor(), List.of(finalMessage, usage),
                        List.of(new ExecutionEvent.ModelCompleted(finalMessage.recordId(), 2, List.of(),
                                usage.recordId())), START.plusSeconds(2));
                fixture.commitRecords(TurnState.COMPLETED, completionCursor(), List.of(
                                changeSetFact(database.mapper(), fixture, TurnChangeSet.emptyComplete(),
                                        START.plusSeconds(3))),
                        List.of(new ExecutionEvent.Terminal(TurnState.COMPLETED, "done", null, null,
                                finalMessage.recordId(), usage.recordId())), START.plusSeconds(3));

                TurnEvent.FinalMessage projected = terminalResult(database, fixture, identity)
                        .terminal().finalMessage();
                assertEquals("item_terminal_persisted", projected.messageId());
                assertEquals("same close text", projected.text());
                assertEquals(JaMessageIds.toItemId("item_terminal_persisted"), projected.messageId());
                assertTimelineMessage(database, identity, prior, "item_prior_same_text",
                        "ASSISTANT_PROGRESS", "same close text", 1);
                assertTimelineMessage(database, identity, finalMessage, "item_terminal_persisted",
                        "FINAL_ANSWER", "same close text", null);
                List<ThreadSnapshot.TextItem> assistantTexts = database.history()
                        .readThread("thr_terminal_identity", null, 100).orElseThrow().items().stream()
                        .filter(ThreadSnapshot.TextItem.class::isInstance)
                        .map(ThreadSnapshot.TextItem.class::cast)
                        .filter(item -> item.kind() == ThreadSnapshot.TextKind.ASSISTANT_PROGRESS
                                || item.kind() == ThreadSnapshot.TextKind.FINAL_ANSWER).toList();
                assertEquals(List.of("same close text"), assistantTexts.stream()
                        .filter(item -> item.kind() == ThreadSnapshot.TextKind.FINAL_ANSWER)
                        .map(ThreadSnapshot.TextItem::text).toList());
            }
        }
    }

    /** 成功终态遗漏冻结快照或在活动提交中携带快照都必须让 Store 与 Ja SQL 一起回滚。 */
    @Test
    void enforcesChangeSetTerminalBoundaryAndRollsBackStoreAndJaRows() throws Exception {
        try (TestDatabase database = database("agent-change-set-terminal-boundary");
             AgentStore store = database.agentStore()) {
            AgentStoreProjectionFixture fixture = admit(database, "thr_change_set_boundary", "turn_change_set_boundary");
            try (fixture) {
                Instant at = START.plusSeconds(1);
                fixture.commitRecords(TurnState.RUNNING, cursor(), List.of(), List.of(), at);
                AgentStore.ExecutionSnapshot before = store.loadExecution(fixture.execution()).orElseThrow();

                Instant missingAt = at.plusSeconds(1);
                AgentStore.RecordWrite missingAssistant = fixture.record("agent.assistant", "missing-change-set",
                        new AssistantMessage("item_missing_change_set",
                                new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent("done"))),
                                "done", null, 1), missingAt);
                AgentStore.StoreParticipantException missing = assertThrows(
                        AgentStore.StoreParticipantException.class,
                        () -> fixture.commitRecords(TurnState.COMPLETED, completionCursor(),
                                List.of(missingAssistant),
                                List.of(new ExecutionEvent.Terminal(TurnState.COMPLETED, "done", null, null,
                                        missingAssistant.recordId(), null)), missingAt));
                assertInstanceOf(SQLException.class, missing.getCause());
                assertStoreSnapshotUnchanged(store, fixture, before);
                assertEquals(0, changeSetCount(database, fixture.execution().turnId()));

                Instant nonterminalAt = missingAt.plusSeconds(1);
                AgentStore.StoreParticipantException nonterminal = assertThrows(
                        AgentStore.StoreParticipantException.class,
                        () -> fixture.commitRecords(TurnState.RUNNING, cursor(),
                                List.of(fixture.knownEmptyChangeSet(nonterminalAt)), List.of(), nonterminalAt));
                assertInstanceOf(SQLException.class, nonterminal.getCause());
                assertStoreSnapshotUnchanged(store, fixture, before);
                assertEquals(0, changeSetCount(database, fixture.execution().turnId()));

                Instant completedAt = nonterminalAt.plusSeconds(1);
                AgentStore.RecordWrite finalAssistant = fixture.record("agent.assistant", "valid-change-set",
                        new AssistantMessage("item_valid_change_set",
                                new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent("done"))),
                                "done", null, 1), completedAt);
                fixture.commitRecords(TurnState.COMPLETED, completionCursor(),
                        List.of(finalAssistant, fixture.knownEmptyChangeSet(completedAt)),
                        List.of(new ExecutionEvent.Terminal(TurnState.COMPLETED, "done", null, null,
                                finalAssistant.recordId(), null)), completedAt);

                assertEquals(TurnState.COMPLETED.name(),
                        store.loadExecution(fixture.execution()).orElseThrow().state());
                assertEquals(1, changeSetCount(database, fixture.execution().turnId()));
                assertEquals(TurnChangeSet.emptyComplete(), database.history()
                        .readThread("thr_change_set_boundary", null, 20).orElseThrow()
                        .turns().getFirst().changeSet());
            }
        }
    }

    /** 对比 Store 状态和记录水位，证明拒绝没有留下通用记录、事件或版本推进。 */
    private static void assertStoreSnapshotUnchanged(AgentStore store, AgentStoreProjectionFixture fixture,
                                                      AgentStore.ExecutionSnapshot before) {
        AgentStore.ExecutionSnapshot after = store.loadExecution(fixture.execution()).orElseThrow();
        assertEquals(before.state(), after.state());
        assertEquals(before.mutationVersion(), after.mutationVersion());
        assertEquals(before.lastRecordSequence(), after.lastRecordSequence());
    }

    /** 直接核对 Ja SQL 投影，确保失败提交没有留下快照行。 */
    private static long changeSetCount(TestDatabase database, String turnId) throws SQLException {
        try (SqlSession session = database.sessions().openSession();
             PreparedStatement query = session.getConnection().prepareStatement(
                     "SELECT COUNT(*) FROM ja_turn_change_set WHERE turn_id=?")) {
            query.setString(1, turnId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("change-set count query returned no row");
                }
                long count = rows.getLong(1);
                if (rows.next()) {
                    throw new SQLException("change-set count query returned multiple rows");
                }
                return count;
            }
        }
    }

    /** 初始化真实 Workspace/Thread、通用会话和 Ja admission，并把后续写入交给共享 fixture。 */
    private AgentStoreProjectionFixture admit(TestDatabase database, String threadId, String turnId) {
        String workspaceId = "ws_" + threadId.substring("thr_".length());
        ThreadPreferences preferences = preferences();
        database.history().register(new Workspace.Registration(workspaceId, temp.toAbsolutePath(), "workspace", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, START));
        database.history().createThread(new ThreadSummary.Creation(threadId, workspaceId,
                "projection test", preferences, START));
        AgentStore store = database.jaAgentStore();
        AgentIdentity identity = identity(threadId, turnId);
        AgentExecutionRequest request = new AgentExecutionRequest(identity,
                AgentExecutionRequest.AdmissionKind.USER_INPUT,
                Optional.of(new UserContent(List.of(new TextContent("run")))), "item_user_" + threadId.substring(4),
                Optional.empty(), new AgentStore.OperationIdentity("operation_" + turnId, "turn/start", "a".repeat(64), new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}")), Map.of(),
                AgentExecutionRequest.HistorySelection.currentConversation(), START, List.of(),
                Optional.empty(), Optional.empty());
        AdmissionFacts facts = new AdmissionFacts(identity, workspaceId, TurnOrigin.USER, "turn/start",
                START, null, null, null, null, null, null, null, 0, Optional.empty());
        return AgentStoreProjectionFixture.open(store, database.mapper(), request, facts, preferences, 0);
    }

    /** 测试身份使用稳定 Ja tenant/app 并与会话主键完全一致，不对通用执行 ID 再映射。 */
    private static AgentIdentity identity(String threadId, String turnId) {
        return new AgentIdentity("ja.local", threadId, turnId, "user_local");
    }

    /** 中间提交沿用清晰 READY cursor；测试只改变持久 facts，不伪造另一份执行队列。 */
    private static TurnExecutionState.Ready cursor() {
        return new TurnExecutionState.Ready(new TurnExecutionState.Common(0, 0, 1, null, List.of()),
                TurnExecutionState.Next.ASSISTANT, null);
    }

    /** 终态 cursor 指向完成结算，避免把 terminal record 与仍待模型调用的恢复位置混淆。 */
    private static TurnExecutionState.Ready completionCursor() {
        return new TurnExecutionState.Ready(new TurnExecutionState.Common(0, 0, 1, null, List.of()),
                TurnExecutionState.Next.COMPLETION, null);
    }

    /** Provider 用量必须带齐请求级 Ja 属性，终态与历史不能从当前配置回退补值。 */
    private static ModelRequestIdentity profile(String providerId, String modelId, String generation) {
        JsonObject attributes = JsonObjects.builder().putText("accessMode", AccessMode.APPROVAL_REQUIRED.name())
                .putText("collaborationMode", CollaborationMode.DEFAULT.name())
                .putText("configGeneration", generation).putText("promptRevision", "prompt_test")
                .putText("toolCatalogRevision", "b".repeat(64)).putText("bindingFingerprint", "c".repeat(64))
                .build();
        return new ModelRequestIdentity(providerId, modelId, "openai_responses", "test-model",
                "medium", "medium", 100_000, 8_192, attributes);
    }

    /** 在严格 usage value 中明示 purpose、certainty 和估算，未知账单不转换成零计量。 */
    private static Usage usage(String modelRequestId, int round, int ordinal, UsagePurpose purpose,
            UsageCertainty certainty, ModelRequestIdentity profile, ModelUsage modelUsage, Long estimate) {
        return new Usage(modelRequestId, modelUsage, round, ordinal, purpose, certainty, profile, estimate);
    }

    /** 便于一般确定结算构造 KNOWN 请求，避免遗漏必需 profile 字段。 */
    private static Usage usage(String modelRequestId, int round, int ordinal, UsagePurpose purpose,
            ModelRequestIdentity profile, ModelUsage modelUsage) {
        return usage(modelRequestId, round, ordinal, purpose, UsageCertainty.KNOWN, profile, modelUsage, null);
    }

    /** ModelCompleted 的调用目录必须和原生 Assistant tool call 顺序同事务一致。 */
    private String prepareToolCall(AgentStoreProjectionFixture fixture, String callId, Instant at) {
        return prepareToolCall(fixture, callId, at, null, null);
    }

    /** 当前进程调用用真实投影器组装准备展示，并与可选审批同事务提交，避免手写 SHELL 掩盖分类遗漏。 */
    private String prepareToolCall(AgentStoreProjectionFixture fixture, String callId, Instant at,
                                   String approvalId, Instant expiresAt) {
        if ((approvalId == null) != (expiresAt == null)) {
            throw new IllegalArgumentException("approval identity and expiry must be supplied together");
        }
        ToolCallContent call = new ToolCallContent(callId, "exec_command",
                JsonObjects.builder().putText("cmd", "echo ok").build());
        AgentStore.RecordWrite pendingUsage = fixture.record("agent.usage", "model-tool-usage-pending-" + callId,
                usage("modelreq_model_" + callId, 1, 1, UsagePurpose.ASSISTANT, UsageCertainty.UNKNOWN,
                        profile("provider_test", "model_test", "cfg_test"), null, null), at);
        fixture.commitRecords(TurnState.RUNNING, cursor(), List.of(pendingUsage), List.of(), at);
        Instant completedAt = at.plusSeconds(1);
        AgentStore.RecordWrite assistant = fixture.record("agent.assistant", "model-tool-call-" + callId,
                new AssistantMessage("item_model_" + callId, new ModelMessage(ModelRole.ASSISTANT,
                        List.of(new TextContent("working"), call)), "working", null, 1), completedAt);
        AgentStore.RecordWrite usage = fixture.record("agent.usage", "model-tool-usage-" + callId,
                usage("modelreq_model_" + callId, 1, 1, UsagePurpose.ASSISTANT,
                        profile("provider_test", "model_test", "cfg_test"), new ModelUsage(42, 8, 50)), completedAt);
        fixture.commitRecords(TurnState.RUNNING, cursor(), List.of(assistant, usage),
                List.of(new ExecutionEvent.ModelCompleted(assistant.recordId(), 1, List.of(callId),
                        usage.recordId())), completedAt);
        ToolPresentation preview = ToolPresentationProjector.prepared(
                new AgentTool.Invocation(callId, call.name(), call.arguments(), 0), Path.of("."), List.of());
        AgentStore.RecordWrite prepared = fixture.record("agent.tool.prepared", "prepared-" + callId,
                new ToolPrepared("batch_" + callId, callId, "exec_command", call.arguments(), 0,
                        ToolSideEffect.EXTERNAL, binding("exec_command"), approvalId != null), at.plusSeconds(2));
        AgentStore.RecordWrite presentation = presentationFact(fixture, callId, 0, ToolState.PREPARED,
                preview, at.plusSeconds(2));
        List<AgentStore.RecordWrite> preparedRecords = new ArrayList<>(List.of(prepared, presentation));
        if (approvalId != null) {
            preparedRecords.add(fixture.record("agent.approval", "approval:" + approvalId,
                    new Approval(approvalId, callId, null, expiresAt), at.plusSeconds(2)));
        }
        fixture.commitRecords(TurnState.RUNNING, cursor(), preparedRecords, List.of(), at.plusSeconds(2));
        return callId;
    }

    /** 构造真实 approval wait 协议值，授权身份取 interactionId/toolCallId 而非重复 opaque 属性。 */
    private static InteractionWait approvalWait(String approvalId, String callId, Instant expiresAt, Instant createdAt) {
        JsonObject attributes = JsonObjects.builder().putText("kind", "approval")
                .putText("expiresAt", expiresAt.toString()).build();
        InteractionQuestion question = new InteractionQuestion("question_approval",
                "请确认是否允许执行此次工具调用。", InteractionQuestionType.SINGLE,
                List.of(new InteractionOption("option_approve", "允许", "允许此次工具调用", true),
                        new InteractionOption("option_deny", "拒绝", "拒绝此次工具调用", false)), true, false);
        return new InteractionWait(approvalId, callId, "operation_" + approvalId, attributes,
                List.of(question), List.of(), InteractionStatus.PENDING, 0, createdAt, createdAt);
    }

    /** 使用 Core codec 生成其状态事实，participant 无需或不应伪造中间件注册上下文。 */
    private static AgentStore.RecordWrite approvalMiddlewareState(AgentStoreProjectionFixture fixture,
                                                                  Instant occurredAt, String interactionId) {
        String namespace = "ja.tool-approval";
        AgentMiddleware middleware = new AgentMiddleware() {
            /** 返回为本测试固定的审批中间件身份。 */
            @Override
            public String id() {
                return namespace;
            }
        };
        ExtensionRegistry.MiddlewareRegistration registration =
                new ExtensionRegistry.MiddlewareRegistration(namespace, 1, middleware);
        AgentMiddleware.ExtensionStateView initial = AgentMiddleware.ExtensionStateView.empty(namespace, 1);
        AgentMiddleware.ExtensionStatePatch patch = new AgentMiddleware.ExtensionStatePatch(0,
                Map.of("interactionId", new JsonText(interactionId)), Set.of());
        return new io.github.kongweiguang.ja.agent.infrastructure.persistence.MiddlewareStateRecordCodec(new JacksonJsonValueCodec(new ObjectMapper()))
                .encodePatch(fixture.recordId(MiddlewareStateRecordCodec.RECORD_TYPE, "state:" + interactionId),
                        registration, initial, patch, occurredAt).orElseThrow();
    }

    /** 以审批等待当前修订构造同一 namespace 的关闭 patch，测试 CONTROL 不复制 Core 状态。 */
    private static AgentStore.RecordWrite approvalAnswerMiddlewareState(AgentStoreProjectionFixture fixture,
            Instant occurredAt, String interactionId) {
        String namespace = "ja.tool-approval";
        AgentMiddleware middleware = new AgentMiddleware() {
            /** 返回本测试冻结的审批中间件身份。 */
            @Override
            public String id() {
                return namespace;
            }
        };
        ExtensionRegistry.MiddlewareRegistration registration =
                new ExtensionRegistry.MiddlewareRegistration(namespace, 1, middleware);
        AgentMiddleware.ExtensionStateView current = new AgentMiddleware.ExtensionStateView(namespace, 1, 1,
                JsonObjects.builder().putText("interactionId", interactionId).build());
        AgentMiddleware.ExtensionStatePatch patch = new AgentMiddleware.ExtensionStatePatch(
                current.revision(), Map.of(), Set.of("interactionId"));
        return new io.github.kongweiguang.ja.agent.infrastructure.persistence.MiddlewareStateRecordCodec(new JacksonJsonValueCodec(new ObjectMapper()))
                .encodePatch(fixture.recordId(MiddlewareStateRecordCodec.RECORD_TYPE,
                                "state-settled:" + interactionId), registration, current, patch, occurredAt)
                .orElseThrow();
    }

    /** 验证失败记录未越过 AgentStore 事务边界，避免只检查 Ja 展示表的表面回滚。 */
    private static void assertRecordAbsent(TestDatabase database, String recordId) throws SQLException {
        assertEquals(0, recordCount(database, recordId));
    }

    /** 检查 Store 保留原始 typed record 与精确 envelope 版本。 */
    private static void assertRecordPresent(TestDatabase database, String recordId,
                                            String recordType, int schemaVersion) throws SQLException {
        try (SqlSession session = database.sessions().openSession();
             PreparedStatement query = session.getConnection().prepareStatement(
                     "SELECT record_type,payload_schema_version FROM agent_record WHERE record_id=?")) {
            query.setString(1, recordId);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(recordType, rows.getString(1));
                assertEquals(schemaVersion, rows.getInt(2));
                assertFalse(rows.next());
            }
        }
    }

    /** 以稳定主键读取 Store record 数量，确认参与者拒绝时其前序插入也已回滚。 */
    private static long recordCount(TestDatabase database, String recordId) throws SQLException {
        try (SqlSession session = database.sessions().openSession();
             PreparedStatement query = session.getConnection().prepareStatement(
                     "SELECT COUNT(*) FROM agent_record WHERE record_id=?")) {
            query.setString(1, recordId);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    /** wait 仅生成 approvals 卡片；有效期按 ja_approval 的 epoch 毫秒精度核对 Core wait。 */
    private static void assertPendingApproval(TestDatabase database, String approvalId,
            String threadId, String turnId, String callId, Instant expiresAt) throws SQLException {
        try (SqlSession session = database.sessions().openSession();
             PreparedStatement query = session.getConnection().prepareStatement(
                     "SELECT thread_id,turn_id,call_id,decision,expires_at_ms FROM ja_approval WHERE approval_id=?")) {
            query.setString(1, approvalId);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(threadId, rows.getString(1));
                assertEquals(turnId, rows.getString(2));
                assertEquals(callId, rows.getString(3));
                assertNull(rows.getString(4));
                assertEquals(expiresAt.toEpochMilli(), rows.getLong(5));
                assertFalse(rows.wasNull());
                assertFalse(rows.next());
            }
        }
    }

    /**
     * 从公开 ThreadRead 快照按 Tool call 身份读取状态，避免测试绕开对外快照投影。
     * @author kongweiguang
     */
    private static ToolPresentation.Status snapshotToolStatus(TestDatabase database, String threadId, String callId) {
        return database.history().readThread(threadId, null, 20).orElseThrow().items().stream()
                .filter(ThreadSnapshot.ToolItem.class::isInstance)
                .map(ThreadSnapshot.ToolItem.class::cast)
                .filter(item -> callId.equals(item.callId()))
                .map(item -> item.presentation().status())
                .findFirst().orElseThrow();
    }

    /** 等待状态、授权决议和调用方幂等回执必须在同一次 CONTROL 提交后完全对齐。 */
    private static void assertAnsweredApproval(TestDatabase database, String approvalId,
            String threadId, String turnId, String callId, String clientOperationId,
            String requestFingerprint, Instant answeredAt)
            throws SQLException {
        try (SqlSession session = database.sessions().openSession();
             PreparedStatement query = session.getConnection().prepareStatement(
                     """
                         SELECT decision,resolved_at_ms FROM ja_approval WHERE approval_id=? AND thread_id=? \
                         AND turn_id=? AND call_id=?\
                         """)) {
            query.setString(1, approvalId);
            query.setString(2, threadId);
            query.setString(3, turnId);
            query.setString(4, callId);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("APPROVE", rows.getString(1));
                assertEquals(answeredAt.toEpochMilli(), rows.getLong(2));
                assertFalse(rows.wasNull());
                assertFalse(rows.next());
            }
        }
        try (SqlSession session = database.sessions().openSession();
             PreparedStatement query = session.getConnection().prepareStatement(
                     """
                         SELECT method,request_fingerprint,thread_id,turn_id,approval_id,decision,is_queued \
                         FROM ja_client_operation \
                         WHERE operation_key=?\
                         """)) {
            query.setString(1, clientOperationId);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("approval/respond", rows.getString(1));
                assertEquals(requestFingerprint, rows.getString(2));
                assertEquals(threadId, rows.getString(3));
                assertEquals(turnId, rows.getString(4));
                assertEquals(approvalId, rows.getString(5));
                assertEquals("approve", rows.getString(6));
                assertEquals(0, rows.getInt(7));
                assertFalse(rows.next());
            }
        }
    }

    /** 可见正文仍以 Core Terminal 结果为准；此处核对公开 item 到同 Thread/Turn 和 Core record 的完整 owner。 */
    private static void assertTimelineMessage(TestDatabase database, AgentIdentity identity,
            AgentStore.RecordWrite coreRecord, String messageId, String expectedKind,
            String expectedText, Integer expectedModelRound) throws SQLException {
        String itemId = JaMessageIds.toItemId(messageId);
        try (SqlSession session = database.sessions().openSession();
             PreparedStatement query = session.getConnection().prepareStatement(
                     """
                         SELECT timeline.kind,timeline.preview_text,timeline.model_round,
                         message.core_tenant_id,message.core_thread_id,message.core_turn_id,
                         message.core_record_id,core.user_id,core.record_type \
                         FROM ja_conversation_timeline_entry timeline \
                         JOIN ja_conversation_message message ON message.message_id=timeline.message_id \
                         AND message.thread_id=timeline.thread_id AND message.turn_id=timeline.turn_id \
                         JOIN agent_record core ON core.tenant_id=message.core_tenant_id \
                         AND core.thread_id=message.core_thread_id AND core.turn_id=message.core_turn_id \
                         AND core.record_id=message.core_record_id \
                         WHERE timeline.item_id=? AND timeline.thread_id=? AND timeline.turn_id=? \
                         AND message.message_id=? AND message.core_tenant_id=? AND message.core_thread_id=? \
                         AND message.core_turn_id=? AND message.core_record_id=? AND core.user_id=?\
                         """)) {
            query.setString(1, itemId);
            query.setString(2, identity.threadId());
            query.setString(3, identity.turnId());
            query.setString(4, itemId);
            query.setString(5, identity.tenantId());
            query.setString(6, identity.threadId());
            query.setString(7, identity.turnId());
            query.setString(8, coreRecord.recordId());
            query.setString(9, identity.userId());
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(expectedKind, rows.getString(1));
                assertEquals(expectedText, rows.getString(2));
                int modelRound = rows.getInt(3);
                assertEquals(expectedModelRound, rows.wasNull() ? null : modelRound);
                assertEquals(identity.tenantId(), rows.getString(4));
                assertEquals(identity.threadId(), rows.getString(5));
                assertEquals(identity.turnId(), rows.getString(6));
                assertEquals(coreRecord.recordId(), rows.getString(7));
                assertEquals(identity.userId(), rows.getString(8));
                assertEquals("agent.assistant", rows.getString(9));
                assertFalse(rows.next());
            }
        }
    }

    /** Approval wait 不得被重复建成普通 InteractionRequest 卡片。 */
    /** 按稳定 interactionId 计数，避免把待回答交互误称为传输请求。 */
    private static long interactionRequestCount(TestDatabase database, String interactionId) throws SQLException {
        try (SqlSession session = database.sessions().openSession();
             PreparedStatement query = session.getConnection().prepareStatement(
                     "SELECT COUNT(*) FROM ja_interaction_request WHERE interaction_id=?")) {
            query.setString(1, interactionId);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    /** Ja 工具绑定包含可解释路由和稳定指纹，不允许投影测试绕过授权身份字段。 */
    private static ToolExecutionBinding binding(String toolName) {
        return new ToolExecutionBinding(toolName, "1".repeat(64), "2".repeat(64), "3".repeat(64),
                JsonObjects.builder().putText("routeKind", "BUILTIN").putText("localName", toolName)
                        .putText("serverId", "local_server").putText("remoteName", toolName)
                        .putText("schemaHash", "1".repeat(64)).putText("routeHash", "4".repeat(64))
                        .putText("accessMode", AccessMode.APPROVAL_REQUIRED.name()).build());
    }

    /** 把脱敏 presentation 包成 Ja extension fact，由与工具事实相同事务的参与者校验状态。 */
    private static AgentStore.RecordWrite presentationFact(AgentStoreProjectionFixture fixture, String callId,
            long revision, ToolState state, ToolPresentation presentation, Instant at) {
        JsonObject payload = JsonObjects.builder().putText("callId", callId).putNumber("revision", revision)
                .putText("state", state.name()).putText("presentationJson",
                        new ToolPresentationCodec(new ObjectMapper()).write(presentation)).build();
        return fixture.extensionFact("agent.extension.fact", "presentation:" + callId + ':' + revision,
                "ja.tool", "presentation", 1, payload, at);
    }

    /** 工具正文只进独立 artifact extension fact，调用身份和 UTF-8 摘要在提交边界验证。 */
    private static AgentStore.RecordWrite toolArtifactFact(AgentStoreProjectionFixture fixture, String callId,
            String artifactId, String content, Instant at) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        JsonObject payload = JsonObjects.builder().putText("callId", callId).putText("artifactId", artifactId)
                .putText("content", content).putText("sha256", sha256(bytes))
                .putNumber("byteLength", bytes.length).build();
        return fixture.extensionFact("agent.extension.fact", "artifact:" + callId,
                "ja.tool", "artifact-content", 1, payload, at);
    }

    /** 终态 tool 展示由模型可见状态生成，artifact 引用不携带工具原始输出。 */
    private static ToolPresentation presentation(ToolPresentation.Status status, String artifactId, String preview) {
        boolean completed = status == ToolPresentation.Status.SUCCESS;
        return new ToolPresentation(ToolPresentation.Kind.SHELL, "shell", status, preview,
                completed ? "ok" : null, completed ? "命令已完成" : null, List.of(),
                completed ? List.of("甲.txt") : List.of(), "echo ok", ".",
                completed ? "ok" : null, null, completed ? 0 : null,
                completed ? 10L : null, false, artifactId);
    }

    /** ChangeSet artifact 与净文件清单保持同一文件和行统计。 */
    private static TurnChangeSet changeSet(String artifactId) {
        return new TurnChangeSet(TurnChangeSet.State.COMPLETE, Set.of(),
                List.of(new TurnChangeSet.FileChange("甲.txt", TurnChangeSet.FileStatus.MODIFIED,
                        1, 1, false, false)), new TurnChangeSet.Stats(1, 1, 1, 0, false), artifactId);
    }

    /** 将变更清单按 Ja 已有 codec 字段写入稳定 execution extension key。 */
    private static AgentStore.RecordWrite changeSetFact(ObjectMapper mapper, AgentStoreProjectionFixture fixture,
            TurnChangeSet changeSet, Instant at) {
        JsonObject payload = new TurnChangeSetCodec(mapper).writePayload(changeSet);
        return fixture.extensionFact("agent.extension.fact", "ja.turn:change-set:" + fixture.execution().turnId(),
                "ja.turn", "change-set", 1, payload, at);
    }

    /** diff 正文按 UTF-8 摘要及字节数单独提交，避免把大内容复制进 ChangeSet JSON。 */
    private static AgentStore.RecordWrite changeSetArtifactFact(AgentStoreProjectionFixture fixture,
            String artifactId, String content, Instant at) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        JsonObject payload = JsonObjects.builder().putText("artifactId", artifactId).putText("content", content)
                .putText("sha256", sha256(bytes)).putNumber("byteLength", bytes.length).build();
        return fixture.extensionFact("agent.extension.fact", "ja.turn:change-set-artifact:" + artifactId,
                "ja.turn", "change-set-artifact", 1, payload, at);
    }

    /** Ja bridge 从 Core Terminal event 的固定 record watermark 解码事实，不读取已删除的 Ja 终态副本。 */
    private TurnResult terminalResult(TestDatabase database, AgentStoreProjectionFixture fixture,
                                      AgentIdentity identity) {
        ObjectMapper mapper = database.mapper();
        AgentStore store = database.jaAgentStore();
        ExecutionRecordCodec records = new StorePayloadCodec(mapper);
        ExecutionEventCodec events = new JacksonExecutionEventCodec(mapper);
        ExecutionControlCodec controls = new ControlPayloadCodec(mapper);
        ExtensionFactRecordCodec extensions =
                new io.github.kongweiguang.ja.agent.infrastructure.persistence.ExtensionFactRecordCodec();
        ToolCallPreviewReadPort previews = (execution, messageId, eventId, sequence) -> List.of();
        JaAgentEventBridge bridge = new JaAgentEventBridge(store, events, records, controls, extensions,
                new ToolPresentationCodec(mapper), new TurnChangeSetCodec(mapper), previews, noAttachments());
        return bridge.terminalResult(identity);
    }

    /** 非附件终态测试明示无法读取任何附件，避免用空实现掩盖未配置读取行为。 */
    private static AttachmentUseCase noAttachments() {
        return new AttachmentUseCase() {
            /** 投影测试不导入文件；若误触发附件写入应立即失败。 */
            @Override public io.github.kongweiguang.ja.attachment.domain.model.AttachmentMetadata importDraft(
                    ImportRequest request) { throw new AssertionError("unexpected attachment import"); }
            /** 投影测试不拥有受信图片导入能力，误调用须保持失败关闭。 */
            @Override public io.github.kongweiguang.ja.attachment.domain.model.AttachmentMetadata importMcpImage(
                    String workspaceId, String displayName, String mediaType, byte[] content) {
                throw new AssertionError("unexpected MCP image import");
            }
            /** 投影测试不删除文件；若误触发外部操作应立即失败。 */
            @Override public io.github.kongweiguang.ja.attachment.domain.model.AttachmentMetadata discard(
                    String attachmentId, Instant discardedAt) { throw new AssertionError("unexpected attachment discard"); }
            /** 投影测试没有附件读取授权，任何正文读取都必须显式暴露为意外调用。 */
            @Override public ReadResult read(ReadRequest request) { throw new AssertionError("unexpected attachment read"); }
            /** 无附件场景只允许返回空授权元数据，不凭缺失授权生成展示替代品。 */
            @Override public List<AttachmentMetadata> readAuthorizedMetadata(
                    String threadId, List<String> attachmentIds) {
                if (!attachmentIds.isEmpty()) {
                    throw new AssertionError("unexpected attachment summary lookup");
                }
                return List.of();
            }
            /** 测试不拥有附件存储的回收周期。 */
            @Override public void collectGarbage() { }
        };
    }

    /** 冻结非敏感 Provider 与权限修订；凭据只用于调用，不进入持久记录。 */
    private static ThreadPreferences preferences() {
        return new ThreadPreferences("provider_test", "model_test", "medium", AccessMode.APPROVAL_REQUIRED,
                CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.PLACEHOLDER);
    }

    /** 直接核对完整输入、缓存子项与输出闭合，UNKNOWN 请求只扩大覆盖分母，不制造零计量。 */
    private static void assertUsageSummary(ThreadUsageSummary summary) {
        assertEquals(4, summary.requestCount());
        assertEquals(3, summary.measuredRequestCount());
        assertEquals(3, summary.newInputRequestCount());
        assertEquals(275, summary.newInputTokens());
        assertEquals(3, summary.outputRequestCount());
        assertEquals(55, summary.outputTokens());
        assertEquals(3, summary.totalRequestCount());
        assertEquals(475, summary.totalTokens());
        assertEquals(3, summary.cacheReadRequestCount());
        assertEquals(130, summary.cacheReadTokens());
        assertEquals(2, summary.cacheWriteRequestCount());
        assertEquals(15, summary.cacheWriteTokens());
        assertEquals(3, summary.cacheCompleteRequestCount());
        assertEquals(420, summary.cacheCompleteInputTokens());
        assertEquals(130, summary.cacheCompleteReadTokens());
        assertEquals(summary.totalTokens() - summary.outputTokens(), summary.cacheCompleteInputTokens());
    }

    /** 按 UTF-8 字节计算摘要，避免多字节文本与 Java 字符数混淆。 */
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** 队列投影错误码和附件 placeholder 必须来自失效引用，不读取不存在的 attachment row。 */
    private static final class AgentInputQueueSnapshotAssertions {
        /** 保留旧修复体验断言，同时依赖新的 AgentStore 队列来源。 */
        private static void assertNeedsAttention(ThreadSnapshot snapshot) {
            InputQueue.QueuedInput broken = snapshot.inputQueue().items().getFirst();
            assertEquals(InputQueue.Status.NEEDS_ATTENTION, broken.status());
            assertEquals("ATTACHMENT_UNAVAILABLE", broken.issue().errorCode());
            assertEquals(List.of(new AttachmentSummary("att_missing", "附件不可用", 0,
                    "binary", "application/octet-stream")), broken.attachments());
            assertEquals(List.of("att_missing"), broken.content().attachmentIds());
        }
    }
}


