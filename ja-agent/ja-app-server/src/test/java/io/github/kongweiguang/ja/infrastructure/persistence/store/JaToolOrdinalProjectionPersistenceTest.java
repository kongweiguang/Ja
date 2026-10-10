// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.store;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.AssistantMessage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ModelRequestIdentity;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolMessage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolPrepared;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolResult;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.Usage;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.model.ModelContent;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.infrastructure.persistence.support.AgentStoreProjectionFixture;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 以真实 SQLite 验证不同 Tool batch 的局部序号投影为同一 Turn 的连续历史顺序。 */
final class JaToolOrdinalProjectionPersistenceTest extends PersistenceTestSupport {
    private static final Instant ACCEPTED_AT = Instant.parse("2026-09-28T00:00:00Z");
    private static final String THREAD_ID = "thr_tool_ordinal";
    private static final String TURN_ID = "turn_tool_ordinal";

    /** 第二批故意逆序提交两个局部 ordinal，验证既有水位续接和 batch 内排序均由投影事务保证。 */
    @Test
    void assignsTurnWideOrdinalsAcrossBatchesAndSettlesEachResultOnce() throws Exception {
        try (TestDatabase database = database("tool-ordinal-projection");
             AgentStoreProjectionFixture fixture = admit(database);
             AgentStore store = database.agentStore()) {
            ModelRequestIdentity profile = profile();
            commitPendingUsage(fixture, profile, "modelreq_batch_one", 0, 1, ACCEPTED_AT.plusSeconds(1));
            List<PreparedCall> firstBatch = List.of(new PreparedCall("call_batch_one", 0));
            TurnExecutionState.Tools firstCursor = commitPreparedBatch(fixture, database, profile,
                    "batch_one", 1, 1, firstBatch, ACCEPTED_AT.plusSeconds(2));
            settleFailedBatch(fixture, database, firstCursor, firstBatch,
                    ACCEPTED_AT.plusSeconds(3));

            commitPendingUsage(fixture, profile, "modelreq_batch_two", 1, 2, ACCEPTED_AT.plusSeconds(4));
            List<PreparedCall> secondBatch = List.of(
                    new PreparedCall("call_batch_two_ordinal_one", 1),
                    new PreparedCall("call_batch_two_ordinal_zero", 0));
            TurnExecutionState.Tools secondCursor = commitPreparedBatch(fixture, database, profile,
                    "batch_two", 2, 2, secondBatch, ACCEPTED_AT.plusSeconds(5));
            settleFailedBatch(fixture, database, secondCursor, secondBatch,
                    ACCEPTED_AT.plusSeconds(6));

            commitPendingUsage(fixture, profile, "modelreq_final", 2, 3, ACCEPTED_AT.plusSeconds(7));
            commitCompleted(fixture, profile, ACCEPTED_AT.plusSeconds(8));

            List<ProjectedTool> tools = readTools(database, TURN_ID);
            assertEquals(List.of(
                    new ProjectedTool("call_batch_one", 0, ToolState.FAILED.name()),
                    new ProjectedTool("call_batch_two_ordinal_zero", 1, ToolState.FAILED.name()),
                    new ProjectedTool("call_batch_two_ordinal_one", 2, ToolState.FAILED.name())), tools);
            assertEquals(3, countToolResults(database, TURN_ID));
            for (PreparedCall call : concat(firstBatch, secondBatch)) {
                assertEquals(1, countToolResult(database, fixture, call.callId()));
            }
            assertEquals(TurnState.COMPLETED.name(), store.loadExecution(fixture.execution()).orElseThrow().state());
        }
    }

    /** 同 call 结果消息和错误分类必须唯一匹配，错误事务整体回滚，不留下工具或结果投影。 */
    @Test
    void rejectsMismatchedDuplicateAndUnclassifiedMissingPresentations() throws Exception {
        for (String scenario : List.of("mismatched-message", "duplicate-message",
                                       "missing-presentation-other-failure")) {
            boolean duplicateWithConflict = scenario.equals("duplicate-message");
            boolean missingPresentation = scenario.equals("missing-presentation-other-failure");
            String errorCode = missingPresentation ? "TOOL_EXECUTION_FAILED" : "TOOL_NOT_AVAILABLE";
            try (TestDatabase database = database("unknown-tool-" + scenario);
                 AgentStoreProjectionFixture fixture = admit(database);
                 AgentStore store = database.agentStore()) {
                ModelRequestIdentity profile = profile();
                String batchId = "batch_" + scenario;
                String callId = "call_" + scenario;
                Instant modelAt = ACCEPTED_AT.plusSeconds(1);
                String requestId = "modelreq_" + scenario;
                commitPendingUsage(fixture, profile, requestId, 0, 1, modelAt);
                TurnExecutionState.Tools cursor = commitModelProposal(fixture, database, profile,
                        batchId, callId, requestId, JsonObject.empty(), modelAt.plusSeconds(1));

                AgentStore.StoreParticipantException failure = assertThrows(
                        AgentStore.StoreParticipantException.class,
                        () -> commitInvalidPreparedFailure(fixture, database, cursor, callId,
                                duplicateWithConflict, !missingPresentation, errorCode, modelAt.plusSeconds(2)));
                assertEquals("Ja transaction participant failed", failure.getMessage());
                assertTrue(failure.getCause() instanceof SQLException);
                assertEquals("Ja prepared tool presentation is missing or inconsistent",
                        failure.getCause().getMessage());
                assertTrue(readTools(database, TURN_ID).isEmpty());
                assertEquals(0, countToolResults(database, TURN_ID));
                assertEquals(0, countToolMessages(database, TURN_ID));
                assertEquals(TurnState.RUNNING.name(), store.loadExecution(fixture.execution())
                        .orElseThrow().state());
            }
        }
    }

    /**
     * 已验证的 TOOL_NOT_AVAILABLE 失败要把待校验占位替换成安全原因，而非显示为仍待校验。
     *
     * @author kongweiguang
     */
    @Test
    void projectsSafeReasonForUnavailableToolWithoutArgumentPreview() throws Exception {
        String callId = "call_unavailable_projection";
        String secretArgument = "synthetic-unvalidated-argument";
        JsonObject arguments = JsonObjects.builder().putText("payload", secretArgument).build();
        try (TestDatabase database = database("unavailable-tool-safe-presentation");
             AgentStoreProjectionFixture fixture = admit(database);
             AgentStore store = database.agentStore()) {
            ModelRequestIdentity profile = profile();
            String batchId = "batch_unavailable_projection";
            String requestId = "modelreq_unavailable_projection";
            Instant modelAt = ACCEPTED_AT.plusSeconds(1);
            commitPendingUsage(fixture, profile, requestId, 0, 1, modelAt);
            TurnExecutionState.Tools cursor = commitModelProposal(fixture, database, profile,
                    batchId, callId, requestId, arguments, modelAt.plusSeconds(1));

            commitUnavailableToolFailure(fixture, cursor, callId, arguments, modelAt.plusSeconds(2));

            String presentationJson = readToolPresentationJson(database, callId);
            ToolPresentation presentation = new ToolPresentationCodec(database.mapper()).read(presentationJson);
            assertEquals(ToolPresentation.Kind.MCP, presentation.kind());
            assertEquals("fixture_tool", presentation.title());
            assertEquals(ToolPresentation.Status.ERROR, presentation.status());
            assertNull(presentation.inputPreview());
            assertEquals("工具当前不可用，本次调用未执行。", presentation.outputPreview());
            assertFalse(presentationJson.contains(secretArgument));
            assertEquals(List.of(new ProjectedTool(callId, 0, ToolState.FAILED.name())), readTools(database, TURN_ID));
            assertEquals(TurnState.RUNNING.name(), store.loadExecution(fixture.execution()).orElseThrow().state());
        }
    }

    /** 在真实 SQLite 中建立 Thread、准入记录和租约，后续写入全部经过 Ja 事务参与者。 */
    private AgentStoreProjectionFixture admit(TestDatabase database) {
        String workspaceId = "ws_tool_ordinal";
        ThreadPreferences preferences = new ThreadPreferences("provider_test", "model_test", "medium",
                AccessMode.FULL_ACCESS, CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.PLACEHOLDER);
        database.history().register(new Workspace.Registration(workspaceId, temp.toAbsolutePath(), "workspace", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, ACCEPTED_AT));
        database.history().createThread(new ThreadSummary.Creation(THREAD_ID, workspaceId,
                "Tool ordinal projection", preferences, ACCEPTED_AT));
        AgentIdentity identity = new AgentIdentity("ja.local", THREAD_ID, TURN_ID, "user_local");
        AgentExecutionRequest request = new AgentExecutionRequest(identity,
                AgentExecutionRequest.AdmissionKind.USER_INPUT,
                Optional.of(new UserContent(List.of(new TextContent("run tools")))), "item_tool_ordinal_user",
                Optional.empty(), new AgentStore.OperationIdentity("operation_tool_ordinal", "turn/start", "a".repeat(64), new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}")), Map.of(), AgentExecutionRequest.HistorySelection.currentConversation(),
                ACCEPTED_AT, List.of(), Optional.empty(), Optional.empty());
        AdmissionFacts facts = new AdmissionFacts(identity, workspaceId, TurnOrigin.USER, "turn/start", ACCEPTED_AT,
                null, null, null, null, null, null, null, 0, Optional.empty());
        return AgentStoreProjectionFixture.open(database.jaAgentStore(), database.mapper(), request, facts,
                preferences, 0);
    }

    /** 先持久 UNKNOWN usage 与当前 READY 水位，保持 Provider 请求记录和后续已知结算的先后关系。 */
    private static void commitPendingUsage(AgentStoreProjectionFixture fixture, ModelRequestIdentity profile,
                                           String requestId, int currentRound, int requestOrdinal, Instant at) {
        Usage pending = new Usage(requestId, null, currentRound + 1, requestOrdinal,
                ExecutionFacts.UsagePurpose.ASSISTANT, ExecutionFacts.UsageCertainty.UNKNOWN, profile, null);
        TurnExecutionState.Common common = common(currentRound, requestOrdinal);
        fixture.commitRecords(TurnState.RUNNING,
                new TurnExecutionState.Ready(common, TurnExecutionState.Next.ASSISTANT, null),
                List.of(fixture.record("agent.usage", requestId + "_pending", pending, at)), List.of(), at);
    }

    /**
     * 将真实模型提案先提交成持久 preview，供同提交 Prepared 失败校验其 callId、名称和 ordinal。
     *
     * @author kongweiguang
     */
    private static TurnExecutionState.Tools commitModelProposal(AgentStoreProjectionFixture fixture,
            TestDatabase database, ModelRequestIdentity profile, String batchId, String callId,
            String requestId, JsonObject arguments, Instant at) {
        String assistantId = "item_assistant_" + batchId;
        ModelMessage assistant = new ModelMessage(ModelRole.ASSISTANT, List.of(
                new ToolCallContent(callId, "fixture_tool", arguments)));
        AssistantMessage message = new AssistantMessage(assistantId, assistant, "", null, 1);
        Usage usage = new Usage(requestId, new ModelUsage(8, 2, 10), 1, 1,
                ExecutionFacts.UsagePurpose.ASSISTANT, ExecutionFacts.UsageCertainty.KNOWN, profile, null);
        String assistantRecordId = fixture.recordId("agent.assistant", assistantId);
        String usageRecordId = fixture.recordId("agent.usage", requestId);
        TurnExecutionState.Tools cursor = new TurnExecutionState.Tools(common(1, 2), batchId,
                assistantId, 0, 0, 0);
        fixture.commitRecords(TurnState.RUNNING, cursor, List.of(
                        fixture.record("agent.assistant", assistantId, message, at),
                        fixture.record("agent.usage", requestId, usage, at)),
                List.of(new ExecutionEvent.ModelCompleted(assistantRecordId, 1, List.of(callId), usageRecordId)), at);
        return cursor;
    }

    /**
     * 持久化真实未执行失败事实；只缺展示回执以覆盖 TOOL_NOT_AVAILABLE 的服务端兜底投影。
     *
     * @author kongweiguang
     */
    private static void commitUnavailableToolFailure(AgentStoreProjectionFixture fixture,
            TurnExecutionState.Tools cursor, String callId, JsonObject arguments, Instant at) {
        String error = "The Tool is unavailable.";
        String messageId = "item_result_" + callId;
        String resultRecordId = fixture.recordId("agent.tool.result", callId);
        String messageRecordId = fixture.recordId("agent.tool.message", messageId);
        List<AgentStore.RecordWrite> writes = List.of(
                fixture.record("agent.tool.prepared", callId,
                        new ToolPrepared(cursor.batchId(), callId, "fixture_tool", arguments, 0,
                                ToolSideEffect.READ_ONLY, null, false), at),
                fixture.record("agent.tool.result", callId, new ToolResult(callId, ToolState.FAILED,
                        error, true, error), at),
                fixture.record("agent.tool.message", messageId,
                        new ToolMessage(messageId, new ModelMessage(ModelRole.TOOL,
                                List.of(new ToolResultContent(callId, error, true)))), at));
        TurnExecutionState.Common common = cursor.common();
        TurnExecutionState.Common settled = new TurnExecutionState.Common(common.modelRound(), 1,
                common.nextProviderOrdinal(), common.promptRevision(), common.activeSkills());
        fixture.commitRecords(TurnState.RUNNING,
                new TurnExecutionState.Ready(settled, TurnExecutionState.Next.ASSISTANT, null), writes,
                List.of(new ExecutionEvent.ToolCompleted(callId, ToolState.FAILED,
                        resultRecordId, messageRecordId, "TOOL_NOT_AVAILABLE")), at);
    }

    /** 制造消息冲突或未分类缺失展示事实，验证 Ja participant 仍拒绝并原子回滚。 */
    private static void commitInvalidPreparedFailure(AgentStoreProjectionFixture fixture,
            TestDatabase database, TurnExecutionState.Tools cursor, String callId,
            boolean duplicateWithConflict, boolean includePresentation, String errorCode, Instant at) {
        String error = "The Tool is unavailable.";
        String messageId = "item_result_" + callId;
        String resultRecordId = fixture.recordId("agent.tool.result", callId);
        String messageRecordId = fixture.recordId("agent.tool.message", messageId);
        ToolResult result = new ToolResult(callId, ToolState.FAILED, error, true, error);
        List<ModelContent> messageContents = duplicateWithConflict
                ? List.of(new ToolResultContent(callId, error, true),
                        new ToolResultContent(callId, "Conflicting duplicate content.", true))
                : includePresentation ? List.of(new ToolResultContent(callId, "Mismatched result content.", true))
                : List.of(new ToolResultContent(callId, error, true));
        List<AgentStore.RecordWrite> writes = new ArrayList<>(List.of(
                fixture.record("agent.tool.prepared", callId,
                        new ToolPrepared(cursor.batchId(), callId, "fixture_tool", JsonObject.empty(),
                                0, ToolSideEffect.READ_ONLY, null, false), at),
                fixture.record("agent.tool.result", callId, result, at),
                fixture.record("agent.tool.message", messageId,
                        new ToolMessage(messageId, new ModelMessage(ModelRole.TOOL, messageContents)), at)));
        if (includePresentation) {
            writes.add(presentation(database, callId, 1, ToolState.FAILED, ToolPresentation.Status.ERROR, at));
        }
        TurnExecutionState.Common settled = new TurnExecutionState.Common(cursor.common().modelRound(), 1,
                cursor.common().nextProviderOrdinal(), cursor.common().promptRevision(),
                cursor.common().activeSkills());
        fixture.commitRecords(TurnState.RUNNING,
                new TurnExecutionState.Ready(settled, TurnExecutionState.Next.ASSISTANT, null), writes,
                List.of(new ExecutionEvent.ToolCompleted(callId, ToolState.FAILED,
                        resultRecordId, messageRecordId, errorCode)), at);
    }

    /** 将一轮 Provider 输出与多个 Prepared 事实共事务提交，调用顺序故意允许不同于 ordinal 顺序。 */
    private static TurnExecutionState.Tools commitPreparedBatch(AgentStoreProjectionFixture fixture,
            TestDatabase database, ModelRequestIdentity profile, String batchId, int modelRound,
            int requestOrdinal, List<PreparedCall> calls, Instant at) {
        String assistantId = "item_assistant_" + batchId;
        String requestId = "modelreq_" + batchId;
        List<PreparedCall> orderedCalls = calls.stream().sorted(Comparator.comparingInt(PreparedCall::ordinal))
                .toList();
        List<ModelContent> toolCalls = new ArrayList<>();
        orderedCalls.forEach(call -> toolCalls.add(
                new ToolCallContent(call.callId(), "fixture_tool", JsonObject.empty())));
        ModelMessage assistant = new ModelMessage(ModelRole.ASSISTANT, toolCalls);
        AssistantMessage message = new AssistantMessage(assistantId, assistant, "", null, modelRound);
        Usage usage = new Usage(requestId, new ModelUsage(8, 2, 10), modelRound, requestOrdinal,
                ExecutionFacts.UsagePurpose.ASSISTANT, ExecutionFacts.UsageCertainty.KNOWN, profile, null);
        List<AgentStore.RecordWrite> writes = new ArrayList<>(List.of(
                fixture.record("agent.assistant", assistantId, message, at),
                fixture.record("agent.usage", requestId, usage, at)));
        for (PreparedCall call : calls) {
            ToolPrepared prepared = new ToolPrepared(batchId, call.callId(), "fixture_tool", JsonObject.empty(),
                    call.ordinal(), ToolSideEffect.READ_ONLY, null, false);
            writes.add(fixture.record("agent.tool.prepared", call.callId(), prepared, at));
            writes.add(presentation(database, call.callId(), 0, ToolState.PREPARED,
                    ToolPresentation.Status.PENDING, at));
        }
        TurnExecutionState.Common common = common(modelRound, requestOrdinal + 1);
        TurnExecutionState.Tools cursor = new TurnExecutionState.Tools(common, batchId, assistantId, 0, 0, 0);
        List<String> callIds = orderedCalls.stream().map(PreparedCall::callId).toList();
        fixture.commitRecords(TurnState.RUNNING, cursor, writes,
                List.of(new ExecutionEvent.ModelCompleted(fixture.recordId("agent.assistant", assistantId),
                        modelRound, callIds, fixture.recordId("agent.usage", requestId))), at);
        return cursor;
    }

    /** 以明确未派发失败结算每个 Prepared 项，保持测试不触发外部副作用并验证结果投影闭环。 */
    private static void settleFailedBatch(AgentStoreProjectionFixture fixture, TestDatabase database,
                                          TurnExecutionState.Tools cursor, List<PreparedCall> calls, Instant at) {
        List<PreparedCall> orderedCalls = calls.stream().sorted(Comparator.comparingInt(PreparedCall::ordinal))
                .toList();
        List<AgentStore.RecordWrite> writes = new ArrayList<>();
        List<ExecutionEvent> events = new ArrayList<>();
        for (PreparedCall call : orderedCalls) {
            String error = "Tool was not dispatched in the persistence fixture.";
            String messageId = "item_result_" + call.callId();
            String resultId = fixture.recordId("agent.tool.result", call.callId());
            String messageRecordId = fixture.recordId("agent.tool.message", messageId);
            writes.add(fixture.record("agent.tool.result", call.callId(),
                    new ToolResult(call.callId(), ToolState.FAILED, error, true, error), at));
            writes.add(fixture.record("agent.tool.message", messageId,
                    new ToolMessage(messageId, new ModelMessage(ModelRole.TOOL,
                            List.of(new ToolResultContent(call.callId(), error, true)))), at));
            writes.add(presentation(database, call.callId(), 1, ToolState.FAILED,
                    ToolPresentation.Status.ERROR, at));
            events.add(new ExecutionEvent.ToolCompleted(call.callId(), ToolState.FAILED,
                    resultId, messageRecordId, "TOOL_NOT_DISPATCHED"));
        }
        fixture.commitRecords(TurnState.RUNNING,
                new TurnExecutionState.Ready(cursor.common(), TurnExecutionState.Next.ASSISTANT, null),
                writes, events, at);
    }

    /** 终态提交保留最终回答、已知 usage 和“无工作区修改”的明确快照事实。 */
    private static void commitCompleted(AgentStoreProjectionFixture fixture, ModelRequestIdentity profile,
                                        Instant at) {
        String assistantId = "item_final_tool_ordinal";
        String requestId = "modelreq_final";
        AssistantMessage message = new AssistantMessage(assistantId,
                new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent("done"))), "done", null, 3);
        Usage usage = new Usage(requestId, new ModelUsage(6, 2, 8), 3, 3,
                ExecutionFacts.UsagePurpose.ASSISTANT, ExecutionFacts.UsageCertainty.KNOWN, profile, null);
        List<AgentStore.RecordWrite> writes = List.of(
                fixture.record("agent.assistant", assistantId, message, at),
                fixture.record("agent.usage", requestId, usage, at),
                fixture.knownEmptyChangeSet(at));
        fixture.commitRecords(TurnState.COMPLETED,
                new TurnExecutionState.Ready(common(3, 4), TurnExecutionState.Next.ASSISTANT, null), writes,
                List.of(new ExecutionEvent.Terminal(TurnState.COMPLETED, "done", null, null,
                        fixture.recordId("agent.assistant", assistantId), fixture.recordId("agent.usage", requestId))),
                at);
    }

    /** 读取工具展示行的真实持久顺序，序号和状态都以 SQLite 行为准。 */
    private static List<ProjectedTool> readTools(TestDatabase database, String turnId) throws SQLException {
        try (SqlSession session = database.sessions().openSession();
             PreparedStatement query = session.getConnection().prepareStatement(
                     "SELECT call_id,ordinal,state FROM ja_tool_call WHERE turn_id=? ORDER BY ordinal")) {
            query.setString(1, turnId);
            try (ResultSet rows = query.executeQuery()) {
                List<ProjectedTool> tools = new ArrayList<>();
                while (rows.next()) {
                    tools.add(new ProjectedTool(rows.getString(1), rows.getInt(2), rows.getString(3)));
                }
                return List.copyOf(tools);
            }
        }
    }

    /**
     * 回读事务生成的 JSON，断言持久 UI 投影本身不含 Core 的参数正文。
     *
     * @author kongweiguang
     */
    private static String readToolPresentationJson(TestDatabase database, String callId) throws SQLException {
        try (SqlSession session = database.sessions().openSession();
             PreparedStatement query = session.getConnection().prepareStatement(
                     "SELECT presentation_json FROM ja_tool_call WHERE thread_id=? AND turn_id=? AND call_id=?")) {
            query.setString(1, THREAD_ID);
            query.setString(2, TURN_ID);
            query.setString(3, callId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Tool presentation query returned no row");
                }
                String presentationJson = rows.getString(1);
                if (rows.next()) {
                    throw new SQLException("Tool presentation query returned multiple rows");
                }
                return presentationJson;
            }
        }
    }

    /** 按完整执行键统计所有 Tool result 记录，确保没有丢失或重复结算。 */
    private static long countToolResults(TestDatabase database, String turnId) throws SQLException {
        try (SqlSession session = database.sessions().openSession();
             PreparedStatement query = session.getConnection().prepareStatement(
                     "SELECT COUNT(*) FROM agent_record WHERE tenant_id=? AND thread_id=? AND turn_id=? "
                             + "AND record_type='agent.tool.result'")) {
            query.setString(1, "ja.local");
            query.setString(2, THREAD_ID);
            query.setString(3, turnId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Tool result count query returned no row");
                }
                return rows.getLong(1);
            }
        }
    }

    /** 验证失败提交回滚后，模型可见 ToolMessage 也没有越过事务边界。 */
    private static long countToolMessages(TestDatabase database, String turnId) throws SQLException {
        try (SqlSession session = database.sessions().openSession();
             PreparedStatement query = session.getConnection().prepareStatement(
                     "SELECT COUNT(*) FROM agent_record WHERE tenant_id=? AND thread_id=? AND turn_id=? "
                             + "AND record_type='agent.tool.message'")) {
            query.setString(1, "ja.local");
            query.setString(2, THREAD_ID);
            query.setString(3, turnId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Tool message count query returned no row");
                }
                return rows.getLong(1);
            }
        }
    }

    /** 对每个稳定 callId 单独回读结果记录，避免总数掩盖同一结果重复或缺失。 */
    private static long countToolResult(TestDatabase database, AgentStoreProjectionFixture fixture,
                                        String callId) throws SQLException {
        try (SqlSession session = database.sessions().openSession();
             PreparedStatement query = session.getConnection().prepareStatement(
                     "SELECT COUNT(*) FROM agent_record WHERE tenant_id=? AND thread_id=? AND turn_id=? "
                             + "AND record_type='agent.tool.result' AND record_id=?")) {
            query.setString(1, "ja.local");
            query.setString(2, THREAD_ID);
            query.setString(3, TURN_ID);
            query.setString(4, fixture.recordId("agent.tool.result", callId));
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Tool result identity query returned no row");
                }
                return rows.getLong(1);
            }
        }
    }

    /** Tool presentation 使用生产版本化 Codec 写入，同 Prepared/Result 共享 Store 事务。 */
    private static AgentStore.RecordWrite presentation(TestDatabase database, String callId, long revision,
                                                       ToolState state, ToolPresentation.Status display,
                                                       Instant at) {
        ToolPresentation value = new ToolPresentation(ToolPresentation.Kind.MCP, "fixture_tool", display,
                "{}", "", null, List.of(), null, null, null, null, null, null, false, null);
        return new JaProjectionPayloadCodec(database.mapper()).encodeToolPresentation(callId, revision, state,
                new ToolPresentationCodec(database.mapper()).write(value), at);
    }

    /** 使用稳定 Provider 身份满足 Usage 事实约束，不发起真实模型请求。 */
    private static ModelRequestIdentity profile() {
        JsonObject attributes = JsonObjects.builder().putText("accessMode", AccessMode.FULL_ACCESS.name())
                .putText("collaborationMode", CollaborationMode.DEFAULT.name())
                .putText("configGeneration", "cfg_tool_ordinal_test")
                .putText("promptRevision", "prompt_projection_test")
                .putText("toolCatalogRevision", "b".repeat(64)).putText("bindingFingerprint", "c".repeat(64))
                .build();
        return new ModelRequestIdentity("provider_test", "model_test", "openai_responses", "test-model",
                "medium", "medium", 100_000, 8_192, attributes);
    }

    /** 保持 Store 游标和 Provider ordinal 显式递增，避免测试把展示序号误作 Core 序号。 */
    private static TurnExecutionState.Common common(int modelRound, int nextProviderOrdinal) {
        return new TurnExecutionState.Common(modelRound, 0, nextProviderOrdinal,
                "prompt_projection_test", List.of());
    }

    /** 多批测试只拼接 call 身份用于逐条结果断言，不参与生产投影排序。 */
    private static List<PreparedCall> concat(List<PreparedCall> first, List<PreparedCall> second) {
        List<PreparedCall> calls = new ArrayList<>(first);
        calls.addAll(second);
        return List.copyOf(calls);
    }

    /**
     * 保留 Core 返回的 batch-local 工具调用顺序，不与 Ja Turn 展示序号混为一谈。
     * @param callId Provider 提供并由执行事实持久化的调用身份
     * @param ordinal 本 batch 内的零起始顺序
     */
    private record PreparedCall(String callId,
                                int ordinal) {
    }

    /**
     * 仅保存 SQLite 中可观察的工具身份、Turn 展示序号和最终状态。
     * @param callId 已投影的工具调用身份
     * @param ordinal SQLite 内持久化的 Turn 全局展示序号
     * @param state Ja 展示行最终状态
     */
    private record ProjectedTool(String callId,
                                 int ordinal,
                                 String state) {
    }
}
