// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ModelRequestIdentity;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.Usage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.UsageCertainty;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.UsagePurpose;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 直接通过 Ja Store 参与者验证 SQLite 用量追加规则与请求结算的原子性。 */
final class MybatisUsageProjectionPersistenceTest extends PersistenceTestSupport {
    private static final Instant REQUESTED_AT = Instant.parse("2026-09-27T00:00:00Z");

    /** 成功响应可首次提交 KNOWN，投影必须在同一事务建立 UNKNOWN 基线后立即结算。 */
    @Test
    void acceptsKnownUsageOnFirstCommitAndSettlesAnExistingUnknownRow() throws Exception {
        try (TestDatabase database = database("known-first-usage")) {
            AgentStoreProjectionFixture fixture = admit(database, "thr_usage_atomic", "turn_usage_atomic");
            try (fixture) {
                ModelRequestIdentity identity = profile();
                fixture.commit(TurnState.RUNNING, cursor(), List.of(new Usage("modelreq_known_first",
                                new ModelUsage(42, 8, 50), 1, 1, UsagePurpose.ASSISTANT,
                                UsageCertainty.KNOWN, identity, null)), List.of(), REQUESTED_AT.plusSeconds(1));
                assertUsage(database, "modelreq_known_first", "KNOWN", 42L, 8L, null);

                fixture.commit(TurnState.RUNNING, cursor(), List.of(new Usage("modelreq_unknown_then_known",
                                null, 2, 2, UsagePurpose.ASSISTANT, UsageCertainty.UNKNOWN, identity, 34_000L)),
                        List.of(), REQUESTED_AT.plusSeconds(2));
                UsageRow unknown = usage(database, "modelreq_unknown_then_known");
                assertEquals("UNKNOWN", unknown.certainty());
                assertNull(unknown.inputTokens());
                assertNull(unknown.outputTokens());
                assertEquals(34_000L, unknown.estimatedContextTokens());

                fixture.commit(TurnState.RUNNING, cursor(), List.of(new Usage("modelreq_unknown_then_known",
                                new ModelUsage(100, 12, 112), 2, 2, UsagePurpose.ASSISTANT,
                                UsageCertainty.KNOWN, identity, null)), List.of(), REQUESTED_AT.plusSeconds(3));
                UsageRow settled = usage(database, "modelreq_unknown_then_known");
                assertEquals(unknown.usageId(), settled.usageId());
                assertEquals("KNOWN", settled.certainty());
                assertEquals(100L, settled.inputTokens());
                assertEquals(12L, settled.outputTokens());
                assertNull(settled.estimatedContextTokens());
            }
        }
    }

    /** 后续扩展 applier 失败时，Store 与 usage 的 UNKNOWN/KNOWN 两步都必须整体回滚。 */
    @Test
    void rollsBackKnownFirstInsertWhenLaterParticipantWorkFails() throws Exception {
        try (TestDatabase database = database("known-first-usage-rollback")) {
            AgentStoreProjectionFixture fixture = admit(database, "thr_usage_rollback", "turn_usage_rollback");
            try (fixture) {
                Usage known = new Usage("modelreq_rollback", new ModelUsage(20, 4, 24), 1, 1,
                        UsagePurpose.ASSISTANT, UsageCertainty.KNOWN, profile(), null);
                AgentStore.RecordWrite fact = fixture.record(
                        "agent.usage", "usage-known-first", known, REQUESTED_AT.plusSeconds(1));
                AgentStore.RecordWrite unsupported = fixture.extensionFact(
                        "agent.extension.fact", "unsupported", "test", "missing", 1,
                        JsonObjects.builder().build(), REQUESTED_AT.plusSeconds(1));

                assertThrows(AgentStore.StoreParticipantException.class, () -> fixture.commitRecords(
                        TurnState.RUNNING, cursor(), List.of(fact, unsupported), List.of(), REQUESTED_AT.plusSeconds(1)));

                assertFalse(usageExists(database, "modelreq_rollback"));
            }
        }
    }

    /** 缓存读写已含在 OpenAI 输入时，完整样本分母直接取 input；与排缓存协议混合汇总也不能重复计算。 */
    @Test
    void completeCacheSamplesUseInclusiveInputAndPreserveMissingWriteCoverage() throws Exception {
        try (TestDatabase database = database("cache-complete-inclusive-write")) {
            AgentStoreProjectionFixture fixture = admit(database, "thr_cache_complete", "turn_cache_complete");
            try (fixture) {
                List<ModelUsage> usages = List.of(
                        new ModelUsage(24_000, 449, 24_449, 22_080L, 1_000L,
                                ModelUsage.InputAccounting.INPUT_INCLUDES_CACHE),
                        new ModelUsage(24_000, 449, 24_449, 22_080L, null,
                                ModelUsage.InputAccounting.INPUT_INCLUDES_CACHE),
                        new ModelUsage(1_000, 449, 2_149, 500L, 200L,
                                ModelUsage.InputAccounting.INPUT_EXCLUDES_CACHE));
                for (int index = 0; index < usages.size(); index++) {
                    int ordinal = index + 1;
                    fixture.commit(TurnState.RUNNING, cursor(), List.of(new Usage("modelreq_cache_" + ordinal,
                                    usages.get(index), ordinal, ordinal, UsagePurpose.ASSISTANT,
                                    UsageCertainty.KNOWN, profile(), null)), List.of(), REQUESTED_AT.plusSeconds(ordinal));
                }
                var summary = database.history().readThreadUsageSummary("thr_cache_complete").orElseThrow();
                assertEquals(3, summary.measuredRequestCount());
                assertEquals(3_840, summary.newInputTokens());
                assertEquals(51_047, summary.totalTokens());
                assertEquals(1_347, summary.outputTokens());
                assertEquals(1_200, summary.cacheWriteTokens());
                assertEquals(2, summary.cacheWriteRequestCount());
                assertEquals(3, summary.cacheCompleteRequestCount());
                assertEquals(49_700, summary.cacheCompleteInputTokens());
                assertEquals(44_660, summary.cacheCompleteReadTokens());
                assertEquals(summary.totalTokens() - summary.outputTokens(), summary.cacheCompleteInputTokens());
            }
        }
    }

    /** 为事务回归建立具备真实 Ja 外键和 Store 准入记录的会话。 */
    private AgentStoreProjectionFixture admit(TestDatabase database, String threadId, String turnId) {
        String workspaceId = "ws_" + threadId.substring("thr_".length());
        ThreadPreferences preferences = new ThreadPreferences("provider_test", "model_test", "medium",
                AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.PLACEHOLDER);
        database.history().register(new Workspace.Registration(workspaceId, temp.toAbsolutePath(), "workspace", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, REQUESTED_AT));
        database.history().createThread(new ThreadSummary.Creation(threadId, workspaceId, "usage projection test",
                preferences, REQUESTED_AT));
        AgentIdentity identity = new AgentIdentity("ja.local", threadId, turnId, "user_local");
        AgentExecutionRequest request = new AgentExecutionRequest(identity,
                AgentExecutionRequest.AdmissionKind.USER_INPUT, Optional.of(new UserContent(List.of(
                        new TextContent("run")))), "item_user_" + threadId.substring("thr_".length()), Optional.empty(),
                new AgentStore.OperationIdentity("operation_" + turnId, "turn/start", "a".repeat(64), new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}")),
                Map.of(), AgentExecutionRequest.HistorySelection.currentConversation(), REQUESTED_AT, List.of(),
                Optional.empty(), Optional.empty());
        AdmissionFacts facts = new AdmissionFacts(identity, workspaceId, TurnOrigin.USER, "turn/start", REQUESTED_AT,
                null, null, null, null, null, null, null, 0, Optional.empty());
        return AgentStoreProjectionFixture.open(database.jaAgentStore(), database.mapper(), request, facts,
                preferences, 0);
    }

    /** 保持最小合法 READY 游标，让回归只验证事实投影和事务约束。 */
    private static TurnExecutionState.Ready cursor() {
        return new TurnExecutionState.Ready(new TurnExecutionState.Common(0, 0, 1, null, List.of()),
                TurnExecutionState.Next.ASSISTANT, null);
    }

    /** 用量需要完整且非敏感的冻结 profile，不能借用当前线程设置补齐审计字段。 */
    private static ModelRequestIdentity profile() {
        JsonObject attributes = JsonObjects.builder().putText("accessMode", AccessMode.APPROVAL_REQUIRED.name())
                .putText("collaborationMode", CollaborationMode.DEFAULT.name())
                .putText("configGeneration", "cfg_usage_test").putText("promptRevision", "prompt_usage_test")
                .putText("toolCatalogRevision", "b".repeat(64)).putText("bindingFingerprint", "c".repeat(64))
                .build();
        return new ModelRequestIdentity("provider_test", "model_test", "openai_responses", "test-model",
                "medium", "medium", 100_000, 8_192, attributes);
    }

    /** 读取真实账本行并校验 certainty 与 token 空值语义。 */
    private static void assertUsage(TestDatabase database, String modelRequestId, String certainty,
                                    Long input, Long output, Long estimate) throws SQLException {
        UsageRow row = usage(database, modelRequestId);
        assertEquals(certainty, row.certainty());
        assertEquals(input, row.inputTokens());
        assertEquals(output, row.outputTokens());
        assertEquals(estimate, row.estimatedContextTokens());
    }

    /** 使用同一 SQLite 查询路径读取用量行，避免只验证内存中构造的事实。 */
    private static UsageRow usage(TestDatabase database, String modelRequestId) throws SQLException {
        try (SqlSession session = database.sessions().openSession();
             PreparedStatement query = session.getConnection().prepareStatement(
                     """
                         SELECT usage_id,certainty,input_tokens,output_tokens,estimated_context_tokens \
                         FROM ja_turn_usage WHERE model_request_id=?\
                         """)) {
            query.setString(1, modelRequestId);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next(), "expected persisted usage row");
                UsageRow value = new UsageRow(rows.getString(1), rows.getString(2), nullableLong(rows, 3),
                        nullableLong(rows, 4), nullableLong(rows, 5));
                assertFalse(rows.next(), "request identity must select one ja_turn_usage row");
                return value;
            }
        }
    }

    /** 读取 nullable SQLite INTEGER 时保留 SQL NULL，不让 JDBC 的默认零值掩盖 UNKNOWN。 */
    private static Long nullableLong(ResultSet rows, int index) throws SQLException {
        long value = rows.getLong(index);
        return rows.wasNull() ? null : value;
    }

    /** 检查失败提交后数据库内没有被回滚遗漏的半行。 */
    private static boolean usageExists(TestDatabase database, String modelRequestId) throws SQLException {
        try (SqlSession session = database.sessions().openSession();
             PreparedStatement query = session.getConnection().prepareStatement(
                     "SELECT 1 FROM ja_turn_usage WHERE model_request_id=?")) {
            query.setString(1, modelRequestId);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    /**
     * 测试读模型只复制必要账本字段，防止结果集资源逃逸关闭边界。
     *
     * @param usageId 用量标识
     * @param certainty 确定性
     * @param inputTokens 输入 Token 数
     * @param outputTokens 输出 Token 数
     * @param estimatedContextTokens 估算的上下文 Token 数
     */
    private record UsageRow(String usageId,
                            String certainty,
                            Long inputTokens,
                            Long outputTokens,
                            Long estimatedContextTokens) { }
}
