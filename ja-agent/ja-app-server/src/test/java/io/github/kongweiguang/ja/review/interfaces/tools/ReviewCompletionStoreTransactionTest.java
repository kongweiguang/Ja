// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.review.interfaces.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
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
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonNull;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
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
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.MiddlewareStateRecordCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisHistoryService;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionPayloadCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaAgentStore;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaConversationParticipant;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaTransactionParticipant;
import io.github.kongweiguang.ja.infrastructure.persistence.store.ToolPresentationCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.support.AgentStoreProjectionFixture;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实 SQLite Store 验证 Review artifact、ToolResult 与完成回执的原子提交边界。 */
final class ReviewCompletionStoreTransactionTest extends PersistenceTestSupport {
    private static final Instant ACCEPTED_AT = Instant.parse("2026-09-28T00:00:00Z");
    private static final String WORKSPACE_ID = "ws_review_atomic";
    private static final String THREAD_ID = "thr_review_atomic";
    private static final String TURN_ID = "turn_review_atomic";
    private static final String ATTACHMENT_ID = "att_review_atomic";
    private static final String SNAPSHOT_ID = "rev_" + "a".repeat(24);
    private static final String SNAPSHOT_HASH = "b".repeat(64);
    private static final String SNAPSHOT_REVISION = "d".repeat(64);
    private static final String PATH = "src/Main.java";
    private static final String INCUMBENT_CALL_ID = "call_incumbent";
    private static final String REVIEW_CALL_ID = "call_review_report";
    private static final String COLLIDING_ARTIFACT_ID = "artifact_review_report";
    private static final String REVIEW_TOOL = "review_report_submit";

    /**
     * 以已存在 artifact 触发真实 SQLite 主键冲突，并确认 Core 记录、artifact 投影和 receipt 一并回滚。
     */
    @Test
    void reportArtifactToolResultAndReceiptRollbackTogetherOnStoreFailure() throws Exception {
        try (TestDatabase database = database("review-report-atomicity");
             AgentStore store = reviewStore(database);
             AgentStoreProjectionFixture fixture = admit(database, store)) {
            ObjectMapper mapper = database.mapper();
            JsonValueCodec codec = new JacksonJsonValueCodec(mapper);
            ReviewCompletionMiddleware middleware = new ReviewCompletionMiddleware(
                    new io.github.kongweiguang.ja.conversation.domain.model.ReviewContext(ATTACHMENT_ID), codec);
            MiddlewareStateRecordCodec stateRecords = new MiddlewareStateRecordCodec(codec);
            ExtensionRegistry.MiddlewareRegistration registration = new ExtensionRegistry.MiddlewareRegistration(
                    middleware.id(), middleware.stateSchemaVersion(), middleware);
            ModelRequestIdentity profile = profile();

            commitPendingUsage(fixture, profile, "modelreq_incumbent", 0, 1, ACCEPTED_AT.plusSeconds(1));
            AgentMiddleware.ExtensionStateView reviewState = persistReviewEvidence(
                    database, fixture, middleware, stateRecords, registration, codec);
            ToolCall incumbent = new ToolCall(INCUMBENT_CALL_ID, "fixture_tool", 0);
            TurnExecutionState.Tools incumbentCursor = commitPreparedBatch(fixture, database, profile,
                    "batch_incumbent", 1, 1, List.of(incumbent), ACCEPTED_AT.plusSeconds(4));
            startTool(fixture, incumbentCursor, incumbent, ACCEPTED_AT.plusSeconds(5));
            commitArtifactResult(database, fixture, incumbentCursor, incumbent, COLLIDING_ARTIFACT_ID,
                    "incumbent artifact", ACCEPTED_AT.plusSeconds(6));

            commitPendingUsage(fixture, profile, "modelreq_review", 1, 2, ACCEPTED_AT.plusSeconds(7));
            ToolCall review = new ToolCall(REVIEW_CALL_ID, REVIEW_TOOL, 0);
            TurnExecutionState.Tools reviewCursor = commitPreparedBatch(fixture, database, profile,
                    "batch_review", 2, 2, List.of(review), ACCEPTED_AT.plusSeconds(8));
            startTool(fixture, reviewCursor, review, ACCEPTED_AT.plusSeconds(9));

            String report = codec.encode(reportArtifact());
            AgentMiddleware.ExtensionDecision<AgentMiddleware.ToolResultProjection> projected =
                    middleware.projectToolResult(result(middleware, reviewState, REVIEW_CALL_ID, REVIEW_TOOL,
                            ToolOutcome.SUCCEEDED, "Review report submitted.", Optional.of(report)));
            AgentMiddleware.ExtensionStateView candidateState = apply(reviewState, projected.statePatch());
            AgentStore.RecordWrite resultRecord = fixture.record("agent.tool.result", REVIEW_CALL_ID,
                    new ToolResult(REVIEW_CALL_ID, ToolState.SUCCEEDED, "Review report submitted.", false, report),
                    ACCEPTED_AT.plusSeconds(10));
            String messageId = "item_review_report_result";
            AgentStore.RecordWrite messageRecord = fixture.record("agent.tool.message", messageId,
                    new ToolMessage(messageId, new ModelMessage(ModelRole.TOOL,
                            List.of(new ToolResultContent(REVIEW_CALL_ID, "Review report submitted.", false)))),
                    ACCEPTED_AT.plusSeconds(10));
            AgentStore.RecordWrite receiptRecord = stateRecords.encodePatch(
                    fixture.recordId(MiddlewareStateRecordCodec.RECORD_TYPE, "state:review:report"),
                    registration, reviewState, projected.statePatch(), ACCEPTED_AT.plusSeconds(10)).orElseThrow();
            AgentStore.RecordWrite artifactRecord = toolArtifactFact(fixture, REVIEW_CALL_ID,
                    COLLIDING_ARTIFACT_ID, report, ACCEPTED_AT.plusSeconds(10));
            AgentStore.RecordWrite presentation = presentation(database, review, 1, ToolState.SUCCEEDED,
                    ToolPresentation.Status.SUCCESS, COLLIDING_ARTIFACT_ID, ACCEPTED_AT.plusSeconds(10));
            List<AgentStore.RecordWrite> writes = List.of(resultRecord, messageRecord, receiptRecord,
                    artifactRecord, presentation);
            List<ExecutionEvent> events = List.of(new ExecutionEvent.ToolCompleted(REVIEW_CALL_ID,
                    ToolState.SUCCEEDED, resultRecord.recordId(), messageRecord.recordId(), null));
            AgentStore.ExecutionSnapshot before = store.loadExecution(fixture.execution()).orElseThrow();

            AgentStore.StoreParticipantException failure = assertThrows(AgentStore.StoreParticipantException.class,
                    () -> fixture.commitRecords(TurnState.RUNNING,
                            new TurnExecutionState.Ready(reviewCursor.common(), TurnExecutionState.Next.ASSISTANT, null),
                            writes, events, ACCEPTED_AT.plusSeconds(10)));

            assertTrue(failure.getCause() instanceof java.sql.SQLException);
            AgentStore.ExecutionSnapshot after = store.loadExecution(fixture.execution()).orElseThrow();
            assertEquals(before.state(), after.state());
            assertEquals(before.mutationVersion(), after.mutationVersion());
            assertTrue(store.readRecord(fixture.execution(), resultRecord.recordId()).isEmpty());
            assertTrue(store.readRecord(fixture.execution(), messageRecord.recordId()).isEmpty());
            assertTrue(store.readRecord(fixture.execution(), receiptRecord.recordId()).isEmpty());
            assertTrue(store.readRecord(fixture.execution(), artifactRecord.recordId()).isEmpty());

            MybatisHistoryService history = database.history();
            assertEquals("incumbent artifact", history.readToolArtifact(THREAD_ID, TURN_ID, INCUMBENT_CALL_ID,
                    COLLIDING_ARTIFACT_ID, 0, 100).orElseThrow().content());
            assertTrue(history.readToolArtifact(THREAD_ID, TURN_ID, REVIEW_CALL_ID,
                    COLLIDING_ARTIFACT_ID, 0, 100).isEmpty());

            AgentMiddleware.ExtensionStateView persistedState = readReviewEvidence(
                    store, fixture, stateRecords, registration);
            assertEquals(reviewState.value(), persistedState.value());
            assertInstanceOf(AgentMiddleware.Continue.class, completion(middleware, persistedState));
            assertFalse(candidateState.value().equals(persistedState.value()));
        }
    }

    /** SQLite 唯一约束在 Core 记录已进入事务后失败，使测试覆盖 Store 自身的全量回滚。 */
    private static AgentStore reviewStore(TestDatabase database) {
        AgentStore raw = database.agentStore();
        AgentStoreTransactionParticipant participant = new JaTransactionParticipant(database.mapper(), List.of(),
                (transaction, terminal, factsByRecordId) -> { }, database.attachments());
        return new JaAgentStore(raw, participant, new JaConversationParticipant());
    }

    /** 建立同一 SQLite 文件上的 Thread 与真实 AgentStore 准入，后续记录沿用相同租约提交。 */
    private AgentStoreProjectionFixture admit(TestDatabase database, AgentStore store) {
        ThreadPreferences preferences = new ThreadPreferences("provider_test", "model_test", "medium",
                AccessMode.FULL_ACCESS, CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.PLACEHOLDER);
        database.history().register(new Workspace.Registration(WORKSPACE_ID, temp.toAbsolutePath(), "workspace",
                Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, ACCEPTED_AT));
        database.history().createThread(new ThreadSummary.Creation(THREAD_ID, WORKSPACE_ID,
                "Review report transaction", preferences, ACCEPTED_AT));
        AgentIdentity identity = identity();
        AgentExecutionRequest request = new AgentExecutionRequest(identity,
                AgentExecutionRequest.AdmissionKind.USER_INPUT,
                Optional.of(new UserContent(List.of(new TextContent("review frozen snapshot")))),
                "item_review_atomic_user", Optional.empty(), new AgentStore.OperationIdentity(
                        "operation_review_atomic", "turn/start", "a".repeat(64),
                        new JacksonJsonValueCodec(database.mapper()).decodeObject("{}")),
                Map.of(), AgentExecutionRequest.HistorySelection.currentConversation(), ACCEPTED_AT,
                List.of(), Optional.empty(), Optional.empty());
        AdmissionFacts facts = new AdmissionFacts(identity, WORKSPACE_ID, TurnOrigin.USER, "turn/start", ACCEPTED_AT,
                null, null, null, null, null, null, null, 0, Optional.empty());
        return AgentStoreProjectionFixture.open(store, database.mapper(), request, facts, preferences, 0);
    }

    /** 经实际 Review 中间件投影并分两笔 Store 提交清单与读取证据，作为报告准入的持久前置状态。 */
    private static AgentMiddleware.ExtensionStateView persistReviewEvidence(
            TestDatabase database, AgentStoreProjectionFixture fixture, ReviewCompletionMiddleware middleware,
            MiddlewareStateRecordCodec stateRecords, ExtensionRegistry.MiddlewareRegistration registration,
            JsonValueCodec codec) {
        TurnExecutionState.Ready cursor = ready(0, 1);
        AgentMiddleware.ExtensionStateView state = AgentMiddleware.ExtensionStateView.empty(middleware.id(), 1);
        AgentMiddleware.ExtensionDecision<AgentMiddleware.ToolResultProjection> listed =
                middleware.projectToolResult(result(middleware, state, "call_snapshot_list", "review_snapshot_list",
                        ToolOutcome.SUCCEEDED, codec.encode(snapshotList()), Optional.empty()));
        state = commitMiddlewarePatch(database, fixture, stateRecords, registration, state, listed.statePatch(),
                cursor, "state:review:list", ACCEPTED_AT.plusSeconds(2));

        AgentMiddleware.ExtensionDecision<AgentMiddleware.ToolResultProjection> read =
                middleware.projectToolResult(result(middleware, state, "call_snapshot_read", "review_snapshot_read",
                        ToolOutcome.SUCCEEDED, codec.encode(readResult()), Optional.empty()));
        return commitMiddlewarePatch(database, fixture, stateRecords, registration, state, read.statePatch(),
                cursor, "state:review:read", ACCEPTED_AT.plusSeconds(3));
    }

    /** 将中间件 patch 编码为 Core typed state record，并仅在真实 Store 提交成功后推进本地测试快照。 */
    private static AgentMiddleware.ExtensionStateView commitMiddlewarePatch(
            TestDatabase database, AgentStoreProjectionFixture fixture, MiddlewareStateRecordCodec stateRecords,
            ExtensionRegistry.MiddlewareRegistration registration, AgentMiddleware.ExtensionStateView current,
            AgentMiddleware.ExtensionStatePatch patch, TurnExecutionState.Ready cursor,
            String stableKey, Instant at) {
        AgentStore.RecordWrite record = stateRecords.encodePatch(
                fixture.recordId(MiddlewareStateRecordCodec.RECORD_TYPE, stableKey), registration,
                current, patch, at).orElseThrow();
        fixture.commitRecords(TurnState.RUNNING, cursor, List.of(record), List.of(), at);
        return apply(current, patch);
    }

    /** 从实际 StoredRecord 顺序恢复 Review 状态，确认失败提交留下的仍是没有 receipt 的前一版快照。 */
    private static AgentMiddleware.ExtensionStateView readReviewEvidence(
            AgentStore store, AgentStoreProjectionFixture fixture, MiddlewareStateRecordCodec stateRecords,
            ExtensionRegistry.MiddlewareRegistration registration) {
        AgentMiddleware.ExtensionStateView state = AgentMiddleware.ExtensionStateView.empty(
                registration.namespace(), registration.stateSchemaVersion());
        for (String key : List.of("state:review:list", "state:review:read")) {
            AgentStore.StoredRecord record = store.readRecord(fixture.execution(),
                    fixture.recordId(MiddlewareStateRecordCodec.RECORD_TYPE, key)).orElseThrow();
            state = stateRecords.decodeNext(record, registration, state);
        }
        return state;
    }

    /** 应用 hook 产生的候选 patch，模拟 Core 状态 reducer 的 revision 推进规则。 */
    private static AgentMiddleware.ExtensionStateView apply(AgentMiddleware.ExtensionStateView state,
                                                            AgentMiddleware.ExtensionStatePatch patch) {
        JsonObject value = patch.apply(state);
        long revision = patch.isEmpty() ? state.revision() : Math.addExact(state.revision(), 1);
        return new AgentMiddleware.ExtensionStateView(state.namespace(), revision, state.schemaVersion(), value);
    }

    /** 构造冻结快照清单，保证报告回执所用身份来自 Review 准入附件。 */
    private static JsonObject snapshotList() {
        JsonObject coverage = JsonObjects.builder().putBoolean("complete", true).putNumber("files", 1)
                .putNumber("bytes", 3).putNumber("maxFiles", 2_000).putNumber("maxBytes", 64 * 1024 * 1024)
                .put("partialReasons", new JsonArray(List.of())).build();
        JsonObject file = JsonObjects.builder().putText("path", PATH).putText("layer", "unstaged")
                .put("oldPath", JsonNull.INSTANCE).putText("status", "modified").putBoolean("binary", false)
                .putBoolean("hasBase", false).putBoolean("hasCurrent", true).putBoolean("hasPatch", true).build();
        return JsonObjects.builder().putText("snapshotId", SNAPSHOT_ID).putText("snapshotHash", SNAPSHOT_HASH)
                .putText("revision", SNAPSHOT_REVISION).put("coverage", coverage)
                .put("files", array(file)).build();
    }

    /** 构造工具真实返回的完整 patch 读取范围，使报告前置覆盖无需依赖模型自报内容。 */
    private static JsonObject readResult() {
        return JsonObjects.builder().putText("snapshotId", SNAPSHOT_ID).putText("snapshotHash", SNAPSHOT_HASH)
                .putText("path", PATH).putText("layer", "unstaged").putText("side", "patch")
                .put("requiredSides", texts("patch")).putNumber("offsetCharacters", 0)
                .putNumber("totalCharacters", 3).putText("content", "abc")
                .put("nextOffsetCharacters", JsonNull.INSTANCE).build();
    }

    /** 报告 artifact 字段与 Review schema 对齐，且 coverage 和 evidence 均对应已读取快照。 */
    private static JsonObject reportArtifact() {
        JsonObject coverage = JsonObjects.builder().putBoolean("complete", true).putNumber("files", 1)
                .putNumber("bytes", 3).putNumber("maxFiles", 2_000).putNumber("maxBytes", 64 * 1024 * 1024)
                .put("partialReasons", new JsonArray(List.of())).put("filesReviewed", array(filePair()))
                .putBoolean("reviewComplete", true).build();
        return JsonObjects.builder().putText("reportId", "report_" + "c".repeat(24))
                .putText("snapshotId", SNAPSHOT_ID).putText("snapshotHash", SNAPSHOT_HASH)
                .putText("revision", SNAPSHOT_REVISION).putText("summary", "Review summary")
                .put("coverage", coverage).put("findings", new JsonArray(List.of())).build();
    }

    /** 构造一项明确绑定冻结路径与变更层的已审查文件。 */
    private static JsonObject filePair() {
        return JsonObjects.builder().putText("path", PATH).putText("layer", "unstaged").build();
    }

    /** 使用 JSON value 类型系统构造数组，避免测试夹具依赖 Jackson 的弱类型转换。 */
    private static JsonArray array(JsonObject... values) {
        return new JsonArray(java.util.Arrays.stream(values).map(JsonValue.class::cast).toList());
    }

    /** 将来源侧名称编码为严格 JSON 文本数组。 */
    private static JsonArray texts(String... values) {
        return new JsonArray(java.util.Arrays.stream(values).map(JsonText::new)
                .map(JsonValue.class::cast).toList());
    }

    /** 将真实投影 hook 的 ToolResultContext 固定为同一执行身份和当前已提交 middleware state。 */
    private static AgentMiddleware.ToolResultContext result(ReviewCompletionMiddleware middleware,
            AgentMiddleware.ExtensionStateView state, String callId, String toolName, ToolOutcome outcome,
            String content, Optional<String> artifact) {
        AgentTool.Invocation invocation = new AgentTool.Invocation(callId, toolName, JsonObject.empty(), 0);
        return new AgentMiddleware.ToolResultContext(identity(), invocation, outcome, true, content,
                Optional.empty(), artifact, List.of(), state);
    }

    /** 以真实 Tool Schema 无关的稳定模型身份满足 Usage typed fact 约束。 */
    private static ModelRequestIdentity profile() {
        JsonObject attributes = JsonObjects.builder().putText("accessMode", AccessMode.FULL_ACCESS.name())
                .putText("collaborationMode", CollaborationMode.DEFAULT.name())
                .putText("configGeneration", "cfg_review_atomic")
                .putText("promptRevision", "prompt_review_atomic")
                .putText("toolCatalogRevision", "b".repeat(64)).putText("bindingFingerprint", "c".repeat(64))
                .build();
        return new ModelRequestIdentity("provider_test", "model_test", "openai_responses", "test-model",
                "medium", "medium", 100_000, 8_192, attributes);
    }

    /** 以稳定身份建立只有显式访问权限的测试 workspace 和 Thread。 */
    private static AgentIdentity identity() {
        return new AgentIdentity("ja.local", THREAD_ID, TURN_ID, "user_local");
    }

    /** 保存一次尚未收到供应商最终用量的请求事实，供后续模型完成事实准确结算。 */
    private static void commitPendingUsage(AgentStoreProjectionFixture fixture, ModelRequestIdentity profile,
            String requestId, int currentRound, int requestOrdinal, Instant at) {
        Usage pending = new Usage(requestId, null, currentRound + 1, requestOrdinal,
                ExecutionFacts.UsagePurpose.ASSISTANT, ExecutionFacts.UsageCertainty.UNKNOWN, profile, null);
        fixture.commitRecords(TurnState.RUNNING,
                ready(currentRound, requestOrdinal),
                List.of(fixture.record("agent.usage", requestId + "_pending", pending, at)), List.of(), at);
    }

    /** 将模型工具提案、Prepared 记录和待处理 presentation 一起提交，冻结合法 Tool 身份。 */
    private static TurnExecutionState.Tools commitPreparedBatch(AgentStoreProjectionFixture fixture,
            TestDatabase database, ModelRequestIdentity profile, String batchId, int modelRound,
            int requestOrdinal, List<ToolCall> calls, Instant at) {
        String assistantId = "item_assistant_" + batchId;
        String requestId = modelRound == 1 ? "modelreq_incumbent" : "modelreq_review";
        List<ModelContent> content = calls.stream()
                .sorted(java.util.Comparator.comparingInt(ToolCall::ordinal))
                .map(call -> (ModelContent) new ToolCallContent(call.callId(), call.toolName(), JsonObject.empty()))
                .toList();
        AssistantMessage assistant = new AssistantMessage(assistantId,
                new ModelMessage(ModelRole.ASSISTANT, content), "", null, modelRound);
        Usage usage = new Usage(requestId, new ModelUsage(8, 2, 10), modelRound, requestOrdinal,
                ExecutionFacts.UsagePurpose.ASSISTANT, ExecutionFacts.UsageCertainty.KNOWN, profile, null);
        List<AgentStore.RecordWrite> writes = new ArrayList<>(List.of(
                fixture.record("agent.assistant", assistantId, assistant, at),
                fixture.record("agent.usage", requestId, usage, at)));
        for (ToolCall call : calls) {
            ToolPrepared prepared = new ToolPrepared(batchId, call.callId(), call.toolName(), JsonObject.empty(),
                    call.ordinal(), ToolSideEffect.READ_ONLY, null, false);
            writes.add(fixture.record("agent.tool.prepared", call.callId(), prepared, at));
            writes.add(presentation(database, call, 0, ToolState.PREPARED, ToolPresentation.Status.PENDING,
                    null, at));
        }
        TurnExecutionState.Tools cursor = new TurnExecutionState.Tools(common(modelRound, requestOrdinal + 1),
                batchId, assistantId, 0, 0, 0);
        List<String> callIds = calls.stream().sorted(java.util.Comparator.comparingInt(ToolCall::ordinal))
                .map(ToolCall::callId).toList();
        fixture.commitRecords(TurnState.RUNNING, cursor, writes,
                List.of(new ExecutionEvent.ModelCompleted(fixture.recordId("agent.assistant", assistantId),
                        modelRound, callIds, fixture.recordId("agent.usage", requestId))), at);
        return cursor;
    }

    /** 先持久派发边界，确保下一个成功结果按 Engine 的 ToolStarted → ToolCompleted 顺序提交。 */
    private static void startTool(AgentStoreProjectionFixture fixture, TurnExecutionState.Tools cursor,
                                  ToolCall call, Instant at) {
        fixture.commitRecords(TurnState.RUNNING, cursor,
                List.of(fixture.record("agent.tool.started", "started:" + call.callId(),
                        new ExecutionFacts.ToolStarted(call.callId(), 0), at)),
                List.of(new ExecutionEvent.ToolStarted(call.callId(), cursor.batchId(), call.toolName(), 0)), at);
    }

    /** 提交 incumbent 的真实 result、presentation 与正文 artifact，后续同 ID 写入触发 SQLite 唯一约束。 */
    private static void commitArtifactResult(TestDatabase database, AgentStoreProjectionFixture fixture,
            TurnExecutionState.Tools cursor, ToolCall call, String artifactId, String content, Instant at)
            throws Exception {
        AgentStore.RecordWrite result = fixture.record("agent.tool.result", call.callId(),
                new ToolResult(call.callId(), ToolState.SUCCEEDED, content, false, content), at);
        String messageId = "item_result_" + call.callId();
        AgentStore.RecordWrite message = fixture.record("agent.tool.message", messageId,
                new ToolMessage(messageId, new ModelMessage(ModelRole.TOOL,
                        List.of(new ToolResultContent(call.callId(), content, false)))), at);
        fixture.commitRecords(TurnState.RUNNING,
                ready(cursor.common().modelRound(), cursor.common().nextProviderOrdinal()),
                List.of(result, message, presentation(database, call, 1, ToolState.SUCCEEDED,
                                ToolPresentation.Status.SUCCESS, artifactId, at),
                        toolArtifactFact(fixture, call.callId(), artifactId, content, at)),
                List.of(new ExecutionEvent.ToolCompleted(call.callId(), ToolState.SUCCEEDED,
                        result.recordId(), message.recordId(), null)), at);
    }

    /** 使用生产 Ja projection codec 编码展示身份，测试不直接写入任何业务展示表。 */
    private static AgentStore.RecordWrite presentation(TestDatabase database, ToolCall call, long revision,
            ToolState state, ToolPresentation.Status status, String artifactId, Instant at) {
        ToolPresentation value = new ToolPresentation(ToolPresentation.Kind.MCP, call.toolName(), status,
                "{}", status == ToolPresentation.Status.SUCCESS ? "completed" : "", null,
                List.of(), List.of(), null, null, null, null, null, null, false, artifactId);
        return new JaProjectionPayloadCodec(database.mapper()).encodeToolPresentation(call.callId(), revision, state,
                new ToolPresentationCodec(database.mapper()).write(value), at);
    }

    /** Tool artifact 使用生产 namespace 和 SHA-256/UTF-8 字节长度协议进入同一 Store 事务。 */
    private static AgentStore.RecordWrite toolArtifactFact(AgentStoreProjectionFixture fixture, String callId,
            String artifactId, String content, Instant at) throws NoSuchAlgorithmException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        JsonObject payload = JsonObjects.builder().putText("callId", callId).putText("artifactId", artifactId)
                .putText("content", content).putText("sha256", digest).putNumber("byteLength", bytes.length).build();
        return fixture.extensionFact("agent.extension.fact", "artifact:" + callId,
                "ja.tool", "artifact-content", 1, payload, at);
    }

    /** 构造可恢复的 Ready cursor，游标位置与同轮已提交模型调用 ordinal 保持一致。 */
    private static TurnExecutionState.Ready ready(int modelRound, int nextProviderOrdinal) {
        return new TurnExecutionState.Ready(common(modelRound, nextProviderOrdinal),
                TurnExecutionState.Next.ASSISTANT, null);
    }

    /** 显式冻结回合数、执行修订和 Provider 请求序号，避免混用展示序号。 */
    private static TurnExecutionState.Common common(int modelRound, int nextProviderOrdinal) {
        return new TurnExecutionState.Common(modelRound, 0, nextProviderOrdinal,
                "prompt_review_atomic", List.of());
    }

    /** 检查持久证据状态仍要求模型补交报告，而不是把 Store 失败当作审查成功。 */
    private static AgentMiddleware.CompletionDecision completion(ReviewCompletionMiddleware middleware,
            AgentMiddleware.ExtensionStateView state) {
        return middleware.beforeCompletion(new AgentMiddleware.CompletionContext(identity(), "Review summary",
                false, false, state)).decision();
    }

    /**
     * 将测试调用固定为一个模型工具身份及其批次内顺序。
     *
     * @param callId 工具调用身份
     * @param toolName 工具名称
     * @param ordinal 本批次内的顺序号
     */
    private record ToolCall(String callId,
                            String toolName,
                            int ordinal) {
    }
}
