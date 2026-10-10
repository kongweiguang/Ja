// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.infrastructure.persistence;

import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.model.ModelContent;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.domain.tool.ToolExecutionBinding;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.domain.tool.ToolState;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.JacksonExecutionEventCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.TurnExecutionStateCodec;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;
import io.github.kongweiguang.ja.conversation.infrastructure.tools.JaToolBindingCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaAgentStore;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaConversationParticipant;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaProjectionPayloadCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaTransactionParticipant;
import io.github.kongweiguang.ja.infrastructure.persistence.store.ToolPresentationCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.support.AgentStoreProjectionFixture;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepository;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepositoryException;
import io.github.kongweiguang.ja.plan.application.service.PlanToolExecutionLedger;
import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.infrastructure.persistence.PlanToolAttemptFactApplier;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 以真实 Core Store 提交证明挂起游标中确定未派发的 Plan Tool 可以继续同一 Run。 */
final class PlanPreparedAttemptResumeIntegrationTest extends PersistenceTestSupport {
    private static final Instant ACCEPTED_AT = Instant.parse("2026-10-07T00:00:00Z");
    private static final String TOOL_NAME = "fixture_tool";
    private static final ThreadPreferences PREFERENCES = new ThreadPreferences("provider_test", "model_test",
            "medium", AccessMode.FULL_ACCESS, CollaborationMode.PLAN,
            ThreadPreferences.TitleSource.PLACEHOLDER);

    /**
     * 真实 admission、Core typed cursor/Prepared 与 Ja attempt 在 SQLite 同事务持久化后，验证 Plan resume 准入边界。
     * 本回归不派发 Tool：没有 ToolStarted/Result 才能证明该调用尚未越过副作用边界；路由绑定由生产 codec 生成。
     *
     * @author kongweiguang
     */
    @Test
    void resumesSameSuspendedTurnWhenCurrentCursorProvesPreparedToolWasNeverStarted() throws Exception {
        verifyPreparedResume(ProofMutation.NONE);
    }

    /**
     * 缺失 Core binding 虽仍是合法可纠正 Tool 事实，却不能证明 Plan 副作用调用可安全重建。
     *
     * @author kongweiguang
     */
    @Test
    void rejectsSuspendedPreparedAttemptWithoutCoreBinding() throws Exception {
        verifyPreparedResume(ProofMutation.MISSING_TOOL_BINDING);
    }

    /** 完整 Store 事实存在时，cursor 不能跳过当前 ordinal 并把旧 Prepared 当作可恢复调用。 */
    @Test
    void rejectsPreparedAttemptWhenCursorAlreadyAdvanced() throws Exception {
        verifyPreparedResume(ProofMutation.ADVANCED_CURSOR);
    }

    /** Core assistant 当前调用 identity 必须与已冻结的 Plan attempt 同一 call。 */
    @Test
    void rejectsPreparedAttemptWhenAssistantCallIdentityDiffers() throws Exception {
        verifyPreparedResume(ProofMutation.ASSISTANT_CALL_MISMATCH);
    }

    /** 内部 Turn 的不可变 Plan hash 损坏时，不能借另一份一致上下文证明恢复授权。 */
    @Test
    void rejectsPreparedAttemptWhenInternalPlanHashDiffers() throws Exception {
        verifyPreparedResume(ProofMutation.INTERNAL_PLAN_HASH_MISMATCH);
    }

    /** 重复身份键不是可选择的旧新值，必须在严格 JSON decode 处拒绝。 */
    @Test
    void rejectsPreparedAttemptWhenInternalBindingRepeatsHashKey() throws Exception {
        verifyPreparedResume(ProofMutation.DUPLICATE_INTERNAL_HASH_KEY);
    }

    /** 即使 Turn 已暂停，存在 Core ToolStarted 与 Plan STARTED 事实仍表示外部结果未知。 */
    @Test
    void rejectsPreparedAttemptAfterDurableToolStarted() throws Exception {
        verifyPreparedResume(ProofMutation.TOOL_STARTED);
    }

    /** Plan ledger 的冻结参数摘要与真实 Core Prepared 不同，不能靠 call identity 相同恢复。 */
    @Test
    void rejectsPreparedAttemptWhenFrozenRequestDigestDiffers() throws Exception {
        verifyPreparedResume(ProofMutation.REQUEST_DIGEST_MISMATCH);
    }

    /**
     * 正负路径共用真实 Store 准入与同事务事实，每个负例只注入一个可审计的持久证明缺口。
     *
     * @param mutation 唯一变化的恢复证明边界
     * @author kongweiguang
     */
    private void verifyPreparedResume(ProofMutation mutation) throws Exception {
        String fixtureName = "plan-prepared-resume-" + mutation.name().toLowerCase(java.util.Locale.ROOT);
        try (TestDatabase database = database(fixtureName)) {
            PlanExecutionAdmissionPersistenceTest.AdmissionCase admission =
                    PlanExecutionAdmissionPersistenceTest.prepare(database, "prepared_resume", temp);
            AgentStore rawStore = database.agentStore();
            StorePayloadCodec records = new StorePayloadCodec(database.mapper());
            JacksonExecutionEventCodec events = new JacksonExecutionEventCodec(database.mapper());
            PlanToolAttemptFactApplier attemptApplier = new PlanToolAttemptFactApplier(
                    admission.plans(), records, events);
            AgentStore store = new JaAgentStore(rawStore, new JaTransactionParticipant(database.mapper(),
                    List.of(attemptApplier), database.taskRepository(), database.attachments()),
                    new JaConversationParticipant());

            try (AgentStoreProjectionFixture fixture = AgentStoreProjectionFixture.open(store, database.mapper(),
                    admission.request(), admission.facts(), PREFERENCES, 0, admission.participant())) {
                String callId = "call_prepared_resume";
                String laterCallId = "call_later_unprepared";
                String batchId = "batch_prepared_resume";
                String assistantId = "item_assistant_prepared_resume";
                String requestId = "modelreq_prepared_resume";
                Instant preparedAt = ACCEPTED_AT.plusSeconds(10);
                String assistantCallId = mutation == ProofMutation.ASSISTANT_CALL_MISMATCH
                        ? "call_different_from_attempt" : callId;
                JsonObject modelArguments = JsonObjects.builder().putText("input", "model arguments").build();
                JsonObject arguments = JsonObjects.builder().putText("input", "middleware-normalized arguments").build();
                JsonObject laterArguments = JsonObjects.builder().putText("input", "later call").build();
                AgentTool.Invocation invocation = new AgentTool.Invocation(callId, TOOL_NAME, arguments, 0);
                AgentIdentity identity = admission.facts().identity();
                JaTool.ExecutionContext context = new JaTool.ExecutionContext(identity, callId,
                        identity.toolCallKey(callId), identity.threadId(), identity.turnId(), temp.toAbsolutePath(),
                        AccessMode.FULL_ACCESS, "cfg_prepared_resume", preparedAt.plusSeconds(60),
                        admission.facts().workspaceId(), admission.revision().planRevisionId(),
                        admission.plan().activeRunId(), TurnOrigin.PLAN_EXECUTION);
                var preparedAttempt = new PlanToolExecutionLedger(admission.plans(), 1)
                        .prepared(invocation, context).getFirst();

                ModelMessage assistant = new ModelMessage(ModelRole.ASSISTANT, List.<ModelContent>of(
                        new ToolCallContent(assistantCallId, TOOL_NAME, modelArguments),
                        new ToolCallContent(laterCallId, TOOL_NAME, laterArguments)));
                ExecutionFacts.AssistantMessage assistantFact = new ExecutionFacts.AssistantMessage(
                        assistantId, assistant, "", null, 1);
                ExecutionFacts.ModelRequestIdentity profile = profile();
                ExecutionFacts.Usage usage = new ExecutionFacts.Usage(requestId,
                        new ModelUsage(8, 2, 10), 1, 1, ExecutionFacts.UsagePurpose.ASSISTANT,
                        ExecutionFacts.UsageCertainty.KNOWN, profile, null);
                TurnExecutionState.Tools cursor = new TurnExecutionState.Tools(
                        new TurnExecutionState.Common(1, 0, 2, "prompt_prepared_resume", List.of()),
                        batchId, assistantId, 0, 1,
                        mutation == ProofMutation.ADVANCED_CURSOR ? 1 : 0);
                ToolExecutionBinding binding = mutation == ProofMutation.MISSING_TOOL_BINDING
                        ? null : preparedBinding(database);
                List<AgentStore.RecordWrite> writes = new ArrayList<>(List.of(
                        fixture.record("agent.assistant", assistantId, assistantFact, preparedAt),
                        fixture.record("agent.usage", requestId, usage, preparedAt),
                        fixture.record("agent.tool.prepared", callId,
                                new ExecutionFacts.ToolPrepared(batchId, callId, TOOL_NAME, arguments, 0,
                                        ToolSideEffect.EXTERNAL, binding, false), preparedAt),
                        preparedPresentation(database, callId, preparedAt)));
                writes.add(fixture.extensionFact("agent.extension.fact", "plan-attempt:" + callId,
                        preparedAttempt.namespace(), preparedAttempt.type(), preparedAttempt.schemaVersion(),
                        preparedAttempt.payload(), preparedAt));
                fixture.commitRecords(TurnState.SUSPENDED, cursor, writes,
                        List.of(new ExecutionEvent.ModelCompleted(fixture.recordId("agent.assistant", assistantId),
                                1, List.of(assistantCallId, laterCallId), fixture.recordId("agent.usage", requestId))),
                        preparedAt);

                if (mutation == ProofMutation.TOOL_STARTED) {
                    commitStartedAttempt(database, fixture, admission, identity, callId, batchId, arguments, cursor,
                            temp.toAbsolutePath(), preparedAt.plusSeconds(1));
                }

                AgentStore.ExecutionSnapshot suspended = rawStore.loadExecution(fixture.execution()).orElseThrow();
                assertEquals(TurnState.SUSPENDED.name(), suspended.state());
                assertEquals(cursor, new TurnExecutionStateCodec(database.mapper()).read(
                        suspended.cursorSchemaVersion(), suspended.cursor()));
                AgentStore.RecordPage persistedRecords = rawStore.readRecords(AgentStore.RecordQuery
                        .withDefaultMaxBytes(fixture.execution(), 0, suspended.lastRecordSequence(), 100));
                assertFalse(persistedRecords.hasMore());
                assertEquals(mutation == ProofMutation.TOOL_STARTED,
                        persistedRecords.records().stream().anyMatch(record ->
                                record.recordType().equals("agent.tool.started")));
                assertFalse(persistedRecords.records().stream().anyMatch(record ->
                        record.recordType().equals("agent.tool.result")));
                ExecutionFacts.ToolPrepared persistedPrepared = persistedRecords.records().stream()
                        .filter(record -> record.recordType().equals("agent.tool.prepared"))
                        .map(records::decode).map(ExecutionFacts.ToolPrepared.class::cast)
                        .filter(value -> value.callId().equals(callId)).findFirst().orElseThrow();
                assertEquals(binding, persistedPrepared.binding());
                AgentStore.ConversationSnapshot conversation = fixture.conversationSnapshot();
                AgentStore.EventPage persistedEvents = rawStore.readEvents(AgentStore.EventQuery
                        .withDefaultMaxBytes(fixture.execution().conversation(), 0,
                                conversation.eventSequence(), 100));
                assertFalse(persistedEvents.hasMore());
                assertEquals(mutation == ProofMutation.TOOL_STARTED,
                        persistedEvents.events().stream().anyMatch(event ->
                                event.eventType().equals("agent.tool.started")));
                assertFalse(persistedEvents.events().stream().anyMatch(event ->
                        event.eventType().equals("agent.tool.completed")));
                List<PlanModels.ToolAttempt> attempts = admission.plans().findToolAttemptsByTurnIdCallId(
                        admission.turnId(), callId);
                assertEquals(1, attempts.size());
                PlanModels.ToolAttemptState expectedAttemptState = mutation == ProofMutation.TOOL_STARTED
                        ? PlanModels.ToolAttemptState.STARTED : PlanModels.ToolAttemptState.PREPARED;
                assertEquals(expectedAttemptState, attempts.getFirst().state());
                assertEquals(admission.plan().activeRunId(), attempts.getFirst().runId());
                assertEquals(admission.turnId(), attempts.getFirst().turnId());

                PlanModels.Plan paused = admission.plans().pausePlan(new PlanRepository.PausePlan(
                        admission.plan().planId(), admission.plans().readPlanSnapshot(
                                admission.plan().planId()).plan().revision(), admission.plan().activeRunId(),
                        "event_pause_prepared_resume", "pause:prepared_resume",
                        preparedAt.plusSeconds(mutation == ProofMutation.TOOL_STARTED ? 2 : 1)));
                if (mutation == ProofMutation.INTERNAL_PLAN_HASH_MISMATCH
                        || mutation == ProofMutation.DUPLICATE_INTERNAL_HASH_KEY) {
                    corruptInternalPlanHash(database, admission.turnId(),
                            mutation == ProofMutation.DUPLICATE_INTERNAL_HASH_KEY);
                }
                if (mutation == ProofMutation.REQUEST_DIGEST_MISMATCH) {
                    corruptRequestDigest(database, admission.turnId(), callId);
                }
                PlanRepository.ResumePlan command = new PlanRepository.ResumePlan(
                        paused.planId(), paused.revision(), paused.activeRunId(), "event_resume_prepared_resume",
                        "resume:prepared_resume", preparedAt.plusSeconds(mutation == ProofMutation.TOOL_STARTED ? 3 : 2));
                int eventCountBeforeResume = planEventCount(database, paused.planId());
                String runStatusBeforeResume = runStatus(database, paused.activeRunId());
                List<PlanModels.ToolAttempt> attemptsBeforeResume = admission.plans()
                        .findToolAttemptsByTurnIdCallId(admission.turnId(), callId);
                if (mutation == ProofMutation.NONE) {
                    PlanRepository.ResumeResult resumed = admission.plans().resumePlan(command);
                    assertTrue(resumed.applied());
                    assertEquals(PlanModels.PlanStatus.EXECUTING, resumed.plan().status());
                    assertEquals(paused.activeRunId(), resumed.plan().activeRunId());
                    assertEquals("RUNNING", runStatus(database, paused.activeRunId()));
                    assertEquals(eventCountBeforeResume + 1, planEventCount(database, paused.planId()));
                } else {
                    var failure = assertThrows(PlanRepositoryException.class,
                            () -> admission.plans().resumePlan(command));
                    assertEquals(PlanRepositoryException.Code.PLAN_INVALID_STATE, failure.code());
                    assertEquals(PlanModels.PlanStatus.PAUSED,
                            admission.plans().readPlanSnapshot(paused.planId()).plan().status());
                    assertEquals(paused, admission.plans().readPlanSnapshot(paused.planId()).plan());
                }
                if (mutation != ProofMutation.NONE) {
                    assertEquals(runStatusBeforeResume, runStatus(database, paused.activeRunId()));
                    assertEquals(eventCountBeforeResume, planEventCount(database, paused.planId()));
                }
                assertEquals(attemptsBeforeResume, admission.plans()
                        .findToolAttemptsByTurnIdCallId(admission.turnId(), callId));
                assertEquals(expectedAttemptState,
                        admission.plans().findToolAttemptsByTurnIdCallId(admission.turnId(), callId)
                                .getFirst().state());
            }
        }
    }

    /**
     * 用 Ja 的 Builtin descriptor 工厂与正式 codec 生成可被 Core 恢复器重新校验的绑定，不手拼路由别名。
     *
     * @param database 持久化当前 fixture 的 SQLite 映射配置
     * @return 与当前工具名、副作用和规范 Schema 一致的 Core 执行绑定
     * @author kongweiguang
     */
    private static ToolExecutionBinding preparedBinding(TestDatabase database) {
        ToolSpec spec = new ToolSpec(TOOL_NAME, "Deterministic Plan resume fixture",
                JsonObjects.builder().putText("type", "object").build());
        JaTool.ToolBindingDescriptor descriptor = JaTool.builtinBindingDescriptor(spec, ToolSideEffect.EXTERNAL,
                JaTool.WorkspaceMutationMode.UNOBSERVABLE);
        return new JaToolBindingCodec(database.mapper()).encode(TOOL_NAME, "b".repeat(64),
                AccessMode.FULL_ACCESS, ToolSideEffect.EXTERNAL, descriptor);
    }

    /** 按真实生命周期提交 Core ToolStarted 与 Ja STARTED，确保副作用结果不能被当作未派发重放。 */
    private static void commitStartedAttempt(TestDatabase database, AgentStoreProjectionFixture fixture,
                                             PlanExecutionAdmissionPersistenceTest.AdmissionCase admission,
                                             AgentIdentity identity, String callId, String batchId,
                                             JsonObject arguments, TurnExecutionState.Tools cursor,
                                             Path workspaceRoot, Instant at) {
        AgentTool.Invocation invocation = new AgentTool.Invocation(callId, TOOL_NAME, arguments, 0);
        JaTool.ExecutionContext context = new JaTool.ExecutionContext(identity, callId,
                identity.toolCallKey(callId), identity.threadId(), identity.turnId(), workspaceRoot,
                AccessMode.FULL_ACCESS, "cfg_prepared_resume", at.plusSeconds(60),
                admission.facts().workspaceId(), admission.revision().planRevisionId(),
                admission.plan().activeRunId(), TurnOrigin.PLAN_EXECUTION);
        var startedAttempt = new PlanToolExecutionLedger(admission.plans(), 1)
                .started(invocation, context).getFirst();
        fixture.commitRecords(TurnState.SUSPENDED, cursor, List.of(
                        fixture.record("agent.tool.started", callId,
                                new ExecutionFacts.ToolStarted(callId, 0), at),
                        presentation(database, callId, 1, ToolState.RUNNING, ToolPresentation.Status.RUNNING, at),
                        fixture.extensionFact("agent.extension.fact", "plan-attempt-started:" + callId,
                                startedAttempt.namespace(), startedAttempt.type(), startedAttempt.schemaVersion(),
                                startedAttempt.payload(), at)),
                List.of(new ExecutionEvent.ToolStarted(callId, batchId, TOOL_NAME, 0)), at);
    }

    /**
     * 只在隔离库中损坏冻结 binding 的 hash；finally 先恢复 immutable trigger 再提交，故障路径也不放宽约束。
     *
     * @author kongweiguang
     */
    private static void corruptInternalPlanHash(TestDatabase database, String turnId, boolean duplicateKey)
            throws Exception {
        try (SqlSession session = database.sessions().openSession()) {
            String contextJson;
            try (PreparedStatement query = session.getConnection().prepareStatement(
                    "SELECT context_json FROM ja_turn_internal_context WHERE turn_id=?")) {
                query.setString(1, turnId);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw new AssertionError("missing immutable Turn binding");
                    }
                    contextJson = rows.getString(1);
                }
            }
            ObjectNode corrupted = (ObjectNode) database.mapper().readTree(contextJson);
            corrupted.put("planHash", "e".repeat(64));
            String corruptedJson = database.mapper().writeValueAsString(corrupted);
            if (duplicateKey) {
                String uniqueHash = "\"planHash\":\"" + corrupted.path("planHash").textValue() + "\"";
                String repeatedHash = uniqueHash + ",\"planHash\":\"" + "f".repeat(64) + "\"";
                corruptedJson = corruptedJson.replace(uniqueHash, repeatedHash);
                if (!corruptedJson.contains(repeatedHash)) {
                    throw new AssertionError("failed to inject duplicate immutable hash key");
                }
            }
            boolean triggerDropped = false;
            try {
                try (Statement statement = session.getConnection().createStatement()) {
                    statement.execute("DROP TRIGGER turn_internal_context_immutable");
                    triggerDropped = true;
                }
                try (PreparedStatement update = session.getConnection().prepareStatement(
                        "UPDATE ja_turn_internal_context SET context_json=? WHERE turn_id=?")) {
                    update.setString(1, corruptedJson);
                    update.setString(2, turnId);
                    assertEquals(1, update.executeUpdate());
                }
            } finally {
                if (triggerDropped) {
                    try (Statement restore = session.getConnection().createStatement()) {
                        restore.execute("CREATE TRIGGER turn_internal_context_immutable BEFORE UPDATE ON "
                                + "ja_turn_internal_context BEGIN "
                                + "SELECT RAISE(ABORT,'Turn internal context is immutable'); END");
                    }
                }
            }
            session.commit();
        }
    }

    /**
     * 只在隔离库中注入错误 attempt digest；finally 恢复 identity trigger 后才提交，且不触碰外键约束。
     *
     * @author kongweiguang
     */
    private static void corruptRequestDigest(TestDatabase database, String turnId, String callId) throws Exception {
        try (SqlSession session = database.sessions().openSession()) {
            boolean triggerDropped = false;
            try {
                try (Statement statement = session.getConnection().createStatement()) {
                    statement.execute("DROP TRIGGER plan_tool_attempts_identity_update");
                    triggerDropped = true;
                }
                try (PreparedStatement update = session.getConnection().prepareStatement(
                        "UPDATE ja_plan_tool_attempt SET request_digest=? WHERE turn_id=? AND call_id=?")) {
                    update.setString(1, "f".repeat(64));
                    update.setString(2, turnId);
                    update.setString(3, callId);
                    assertEquals(1, update.executeUpdate());
                }
            } finally {
                if (triggerDropped) {
                    try (Statement restore = session.getConnection().createStatement()) {
                        restore.execute("CREATE TRIGGER plan_tool_attempts_identity_update BEFORE UPDATE OF "
                                + "tool_attempt_id,plan_id,run_id,plan_revision_id,step_id,attempt,turn_id,call_id,"
                                + "process_generation,side_effect,request_digest,prepared_at_ms "
                                + "ON ja_plan_tool_attempt "
                                + "BEGIN SELECT RAISE(ABORT,'Plan Tool attempt identity is immutable'); END");
                    }
                }
            }
            session.commit();
        }
    }

    /** 回读 durable Run 状态，确保被拒绝的 Resume 没有推进执行生命周期。 */
    private static String runStatus(TestDatabase database, String runId) throws Exception {
        try (SqlSession session = database.sessions().openSession();
             PreparedStatement query = session.getConnection().prepareStatement(
                     "SELECT status FROM ja_plan_execution WHERE run_id=?")) {
            query.setString(1, runId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new AssertionError("missing Plan execution run");
                }
                return rows.getString(1);
            }
        }
    }

    /** 回读 Plan event 数，确保失败的 Resume 事务没有留下部分提交的 lifecycle event。 */
    private static int planEventCount(TestDatabase database, String planId) throws Exception {
        try (SqlSession session = database.sessions().openSession();
             PreparedStatement query = session.getConnection().prepareStatement(
                     "SELECT COUNT(*) FROM ja_plan_event WHERE plan_id=?")) {
            query.setString(1, planId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new AssertionError("missing Plan event count");
                }
                return rows.getInt(1);
            }
        }
    }

    /**
     * 为真实 ModelCompleted 事实构造无凭据 Profile，测试不触发 Provider 请求。
     *
     * @return 可以编码为确定用量事实的固定模型 Profile
     * @author kongweiguang
     */
    private static ExecutionFacts.ModelRequestIdentity profile() {
        JsonObject attributes = JsonObjects.builder().putText("accessMode", AccessMode.FULL_ACCESS.name())
                .putText("collaborationMode", CollaborationMode.PLAN.name())
                .putText("configGeneration", "cfg_prepared_resume")
                .putText("promptRevision", "prompt_prepared_resume")
                .putText("toolCatalogRevision", "b".repeat(64))
                .putText("bindingFingerprint", "c".repeat(64)).build();
        return new ExecutionFacts.ModelRequestIdentity("provider_test", "model_test", "openai_responses",
                "test-model", "medium", "medium", 100_000, 8_192, attributes);
    }

    /**
     * 按生产版本化 presentation codec 写入未授权 PENDING 展示，不为测试绕过 Ja Tool 投影校验。
     *
     * @param database 同一测试 SQLite 的 JSON 映射配置
     * @param callId 已提交 ToolPrepared 的 call 身份
     * @param at 同一 Store commit 的业务时间
     * @return 与 PREPARED fact 一致的 Ja 展示记录
     * @author kongweiguang
     */
    private static AgentStore.RecordWrite preparedPresentation(TestDatabase database, String callId, Instant at) {
        return presentation(database, callId, 0, ToolState.PREPARED, ToolPresentation.Status.PENDING, at);
    }

    /** 通过正式 presentation codec 写入与 Core lifecycle 对齐的状态，保留 Ja 投影的迁移约束。 */
    private static AgentStore.RecordWrite presentation(TestDatabase database, String callId, long revision,
                                                       ToolState state, ToolPresentation.Status status, Instant at) {
        ToolPresentation value = new ToolPresentation(ToolPresentation.Kind.MCP, TOOL_NAME,
                status, "{}", "", null, List.of(), null, null, null,
                null, null, null, false, null);
        return new JaProjectionPayloadCodec(database.mapper()).encodeToolPresentation(callId, revision,
                state,
                new ToolPresentationCodec(database.mapper()).write(value), at);
    }

    /**
     * 按单一维度破坏可信 Prepared proof，使每个恢复拒绝用例都保留其余真实持久化证据。
     *
     * @author kongweiguang
     */
    private enum ProofMutation {
        NONE,
        MISSING_TOOL_BINDING,
        ADVANCED_CURSOR,
        ASSISTANT_CALL_MISMATCH,
        INTERNAL_PLAN_HASH_MISMATCH,
        DUPLICATE_INTERNAL_HASH_KEY,
        TOOL_STARTED,
        REQUEST_DIGEST_MISMATCH
    }
}
