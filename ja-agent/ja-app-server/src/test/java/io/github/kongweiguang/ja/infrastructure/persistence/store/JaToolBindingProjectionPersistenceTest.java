// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.store;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ModelRequestIdentity;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.agent.domain.tool.ToolState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.infrastructure.persistence.support.AgentStoreProjectionFixture;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 以真实 SQLite schema 验证未派发结果的绑定查询及线程归属边界。 */
final class JaToolBindingProjectionPersistenceTest extends PersistenceTestSupport {
    private static final Instant ACCEPTED_AT = Instant.parse("2026-09-27T00:00:00Z");
    private static final String CALL_ID = "call_unprepared_projection";

    /** 未准备调用的失败结果仍可结算，参与者不能查询 tool_bindings 中不存在的 thread_id 列。 */
    @Test
    void settlesUnpreparedFailureAgainstRealSQLiteSchema() throws Exception {
        try (TestDatabase database = database("unprepared-tool-binding");
             AgentStoreProjectionFixture fixture = admit(database, "thr_unprepared_binding", "turn_unprepared_binding")) {
            ModelRequestIdentity profile = profile();
            String modelRequestId = "modelreq_unprepared_binding";
            fixture.commit(TurnState.RUNNING, readyCursor(0, 1), List.of(new ExecutionFacts.Usage(modelRequestId,
                            null, 1, 1, ExecutionFacts.UsagePurpose.ASSISTANT, ExecutionFacts.UsageCertainty.UNKNOWN,
                            profile, null)), List.of(), ACCEPTED_AT.plusSeconds(1));

            String assistantId = "message_unprepared_assistant";
            String assistantRecordId = fixture.recordId("agent.assistant", assistantId);
            String usageRecordId = fixture.recordId("agent.usage", modelRequestId);
            ModelMessage assistant = new ModelMessage(ModelRole.ASSISTANT,
                    List.of(new ToolCallContent(CALL_ID, "read", io.github.kongweiguang.ja.agent.domain.json.JsonObject.empty())));
            List<AgentStore.RecordWrite> modelRecords = List.of(
                    fixture.record("agent.assistant", assistantId,
                            new ExecutionFacts.AssistantMessage(assistantId, assistant, "", null, 1),
                            ACCEPTED_AT.plusSeconds(2)),
                    fixture.record("agent.usage", modelRequestId,
                            new ExecutionFacts.Usage(modelRequestId, new ModelUsage(40, 8, 48), 1, 1,
                                    ExecutionFacts.UsagePurpose.ASSISTANT, ExecutionFacts.UsageCertainty.KNOWN,
                                    profile, null), ACCEPTED_AT.plusSeconds(2)));
            TurnExecutionState.Common toolsCommon = common(1, 2);
            TurnExecutionState.Tools toolsCursor = new TurnExecutionState.Tools(toolsCommon,
                    "batch_unprepared_binding", assistantId, 0, 0, 0);
            fixture.commitRecords(TurnState.RUNNING, toolsCursor, modelRecords,
                    List.of(new ExecutionEvent.ModelCompleted(assistantRecordId, 1, List.of(CALL_ID), usageRecordId)),
                    ACCEPTED_AT.plusSeconds(2));

            String failure = "Tool execution was stopped before dispatch.";
            String toolMessageId = "message_unprepared_result";
            String resultRecordId = fixture.recordId("agent.tool.result", CALL_ID);
            String toolMessageRecordId = fixture.recordId("agent.tool.message", toolMessageId);
            List<AgentStore.RecordWrite> resultRecords = List.of(
                    fixture.record("agent.tool.result", CALL_ID,
                            new ExecutionFacts.ToolResult(CALL_ID, ToolState.FAILED, failure, true, failure),
                            ACCEPTED_AT.plusSeconds(3)),
                    fixture.record("agent.tool.message", toolMessageId,
                            new ExecutionFacts.ToolMessage(toolMessageId, new ModelMessage(ModelRole.TOOL,
                                    List.of(new ToolResultContent(CALL_ID, failure, true)))),
                            ACCEPTED_AT.plusSeconds(3)));
            fixture.commitRecords(TurnState.RUNNING,
                    new TurnExecutionState.Ready(toolsCommon, TurnExecutionState.Next.ASSISTANT, null),
                    resultRecords, List.of(new ExecutionEvent.ToolCompleted(CALL_ID, ToolState.FAILED,
                            resultRecordId, toolMessageRecordId, "TOOL_NOT_DISPATCHED")), ACCEPTED_AT.plusSeconds(3));

            AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(
                    new AgentStore.ConversationKey("ja.local", "thr_unprepared_binding", "user_local"), "turn_unprepared_binding");
            assertTrue(database.agentStore().readRecord(execution, resultRecordId).isPresent());
        }
    }

    /** 绑定行不存 thread_id；经 tools 的真实 owner 关联过滤后，错误 Thread 不得命中同名调用。 */
    @Test
    void bindingLookupRequiresOwningThread() throws Exception {
        try (TestDatabase database = database("tool-binding-thread-owner");
             AgentStoreProjectionFixture fixture = admit(database, "thr_binding_owner", "turn_binding_owner");
             SqlSession session = database.sessions().openSession()) {
            String callId = "call_owner_check";
            insertToolAndBinding(session, "thr_binding_owner", "turn_binding_owner", callId);
            Connection connection = session.getConnection();
            AgentStore.ExecutionKey owner = new AgentStore.ExecutionKey(
                    new AgentStore.ConversationKey("ja.local", "thr_binding_owner", "user_local"), "turn_binding_owner");
            AgentStore.ExecutionKey wrongThread = new AgentStore.ExecutionKey(
                    new AgentStore.ConversationKey("ja.local", "thr_another_owner", "user_local"), "turn_binding_owner");

            assertTrue(hasToolBinding(connection, owner, callId));
            assertFalse(hasToolBinding(connection, wrongThread, callId));
        }
    }

    /** 创建由完整 Ja Store 准入持有的真实线程与执行，避免仅凭手写外键行测试查询。 */
    private AgentStoreProjectionFixture admit(TestDatabase database, String threadId, String turnId) {
        String workspaceId = "ws_" + threadId.substring("thr_".length());
        ThreadPreferences preferences = new ThreadPreferences("provider_test", "model_test", "medium",
                AccessMode.FULL_ACCESS, CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.PLACEHOLDER);
        database.history().register(new Workspace.Registration(workspaceId, temp.toAbsolutePath(), "workspace", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, ACCEPTED_AT));
        database.history().createThread(new ThreadSummary.Creation(threadId, workspaceId,
                "Tool binding projection", preferences, ACCEPTED_AT));
        AgentIdentity identity = new AgentIdentity("ja.local", threadId, turnId, "user_local");
        AgentExecutionRequest request = new AgentExecutionRequest(identity,
                AgentExecutionRequest.AdmissionKind.USER_INPUT,
                Optional.of(new UserContent(List.of(new TextContent("inspect")))), "item_" + turnId,
                Optional.empty(), new AgentStore.OperationIdentity("operation_" + turnId, "turn/start", "a".repeat(64), new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}")), Map.of(),
                AgentExecutionRequest.HistorySelection.currentConversation(), ACCEPTED_AT, List.of(),
                Optional.empty(), Optional.empty());
        AdmissionFacts facts = new AdmissionFacts(identity, workspaceId, TurnOrigin.USER, "turn/start", ACCEPTED_AT,
                null, null, null, null, null, null, null, 0, Optional.empty());
        return AgentStoreProjectionFixture.open(database.jaAgentStore(), database.mapper(), request, facts,
                preferences, 0);
    }

    /** 插入符合主外键及整数毫秒约束的工具行，专门覆盖绑定表的 turn 级归属查询。 */
    private static void insertToolAndBinding(SqlSession session, String threadId, String turnId, String callId)
            throws Exception {
        long at = ACCEPTED_AT.toEpochMilli();
        try (PreparedStatement tool = session.getConnection().prepareStatement(
                """
                    INSERT INTO ja_tool_call(call_id,thread_id,turn_id,ordinal,tool_name,side_effect,presentation_json,\
                    artifact_id,state,revision,created_at_ms,updated_at_ms) VALUES(?,?,?,?,?,?,?,NULL,?,0,?,?)\
                    """)) {
            tool.setString(1, callId);
            tool.setString(2, threadId);
            tool.setString(3, turnId);
            tool.setInt(4, 0);
            tool.setString(5, "read");
            tool.setString(6, "READ_ONLY");
            tool.setString(7, "{\"status\":\"pending\"}");
            tool.setString(8, "PREPARED");
            tool.setLong(9, at);
            tool.setLong(10, at);
            tool.executeUpdate();
        }
        try (PreparedStatement binding = session.getConnection().prepareStatement(
                """
                    INSERT INTO ja_tool_binding(turn_id,batch_id,call_id,route_kind,local_name,server_id,remote_name,\
                    schema_hash,route_hash,catalog_revision,access_mode,created_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)\
                    """)) {
            binding.setString(1, turnId);
            binding.setString(2, "batch_owner_check");
            binding.setString(3, callId);
            binding.setString(4, "BUILTIN");
            binding.setString(5, "read");
            binding.setString(6, "builtin");
            binding.setString(7, "read");
            binding.setString(8, "1".repeat(64));
            binding.setString(9, "2".repeat(64));
            binding.setString(10, "3".repeat(64));
            binding.setString(11, "FULL_ACCESS");
            binding.setLong(12, at);
            binding.executeUpdate();
        }
    }

    /** 反射只覆盖私有 SQL seam，保持生产查询实现封闭，同时断言其真实 SQLite 归属过滤。 */
    private static boolean hasToolBinding(Connection connection, AgentStore.ExecutionKey execution, String callId)
            throws SQLException {
        try {
            Method method = JaToolProjection.class.getDeclaredMethod("hasToolBinding",
                    Connection.class, AgentStore.ExecutionKey.class, String.class);
            if (!method.trySetAccessible()) {
                throw new AssertionError("private binding query is inaccessible");
            }
            return (boolean) method.invoke(null, connection, execution, callId);
        } catch (NoSuchMethodException | IllegalAccessException failure) {
            throw new AssertionError("binding query contract changed", failure);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof SQLException sqlFailure) {
                throw sqlFailure;
            }
            if (failure.getCause() instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new AssertionError("binding query failed", failure.getCause());
        }
    }

    /** 用 UNKNOWN/KNOWN 两条事实表达真实模型请求边界，避免已知用量伪造为首次状态。 */
    private static ModelRequestIdentity profile() {
        JsonObject attributes = JsonObjects.builder().putText("accessMode", AccessMode.FULL_ACCESS.name())
                .putText("collaborationMode", CollaborationMode.DEFAULT.name())
                .putText("configGeneration", "cfg_unprepared_test").putText("promptRevision", "prompt_unprepared_test")
                .putText("toolCatalogRevision", "b".repeat(64)).putText("bindingFingerprint", "c".repeat(64))
                .build();
        return new ModelRequestIdentity("provider_test", "model_test", "openai_responses", "test-model",
                "medium", "medium", 100_000, 8_192, attributes);
    }

    /** 测试执行事实使用同一模型轮次和下一个 Provider ordinal。 */
    private static TurnExecutionState.Common common(int modelRound, int nextProviderOrdinal) {
        return new TurnExecutionState.Common(modelRound, 0, nextProviderOrdinal,
                "prompt_unprepared_test", List.of());
    }

    /** 为准入后的请求游标保留 READY 状态，确保测试只触及 Tool 持久投影。 */
    private static TurnExecutionState.Ready readyCursor(int modelRound, int nextProviderOrdinal) {
        return new TurnExecutionState.Ready(common(modelRound, nextProviderOrdinal),
                TurnExecutionState.Next.ASSISTANT, null);
    }
}
