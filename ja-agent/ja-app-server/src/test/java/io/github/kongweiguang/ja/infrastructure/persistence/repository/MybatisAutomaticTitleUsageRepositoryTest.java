// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.application.gateway.AutomaticTitleUsagePort;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.persistence.support.AgentStoreProjectionFixture;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.preferences;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 真实 SQLite 验证自动标题调用资格和 outcome 事实不会混入普通 Turn usage。 */
final class MybatisAutomaticTitleUsageRepositoryTest extends PersistenceTestSupport {
    /** 同 Thread 跨 adapter/restart 只能取得一次调用资格，已完成事实允许安全重放。 */
    @Test
    void keepsClaimAtMostOnceAndOutcomeIdempotentAcrossAdapters() throws Exception {
        try (TestDatabase database = database("automatic-title-usage")) {
            prepareTurn(database);
            MybatisAutomaticTitleUsageRepository first = database.automaticTitleUsage();
            MybatisAutomaticTitleUsageRepository restarted = database.automaticTitleUsage();
            AutomaticTitleUsagePort.GenerationClaim claim = claim("titlegen_one");

            assertEquals(AutomaticTitleUsagePort.ClaimResult.ACQUIRED, first.claim(claim));
            assertEquals(AutomaticTitleUsagePort.ClaimResult.ALREADY_EXISTS, restarted.claim(
                    new AutomaticTitleUsagePort.GenerationClaim(
                            "titlegen_retry", "thr_title", "turn_title", "provider_title",
                            "model_title", "cfg_title", START.plusSeconds(1))));

            AutomaticTitleUsagePort.ModelOutcome succeeded = new AutomaticTitleUsagePort.ModelOutcome(
                    "titlegen_one", AutomaticTitleUsagePort.Result.SUCCEEDED,
                    new ModelUsage(8, 3, 11), null, START.plusSeconds(2));
            first.recordModelOutcome(succeeded);
            restarted.recordModelOutcome(new AutomaticTitleUsagePort.ModelOutcome(
                    "titlegen_one", AutomaticTitleUsagePort.Result.SUCCEEDED,
                    new ModelUsage(8, 3, 11), null, START.plusSeconds(9)));
            assertEquals(0, database.history().readThreadUsageSummary("thr_title").orElseThrow().requestCount());
        }
    }

    /** 已提交终态不能被另一结果或用量覆盖，首个审计事实始终保留。 */
    @Test
    void rejectsConflictingTerminalOutcome() throws Exception {
        try (TestDatabase database = database("automatic-title-conflict")) {
            prepareTurn(database);
            MybatisAutomaticTitleUsageRepository repository = database.automaticTitleUsage();
            assertEquals(AutomaticTitleUsagePort.ClaimResult.ACQUIRED,
                    repository.claim(claim("titlegen_conflict")));
            repository.recordModelOutcome(new AutomaticTitleUsagePort.ModelOutcome(
                    "titlegen_conflict", AutomaticTitleUsagePort.Result.FAILED,
                    null, "PROVIDER_TIMEOUT", START.plusSeconds(2)));

            StorageException conflict = assertThrows(StorageException.class,
                    () -> repository.recordModelOutcome(new AutomaticTitleUsagePort.ModelOutcome(
                            "titlegen_conflict", AutomaticTitleUsagePort.Result.SUCCEEDED,
                            new ModelUsage(1, 1, 2), null, START.plusSeconds(3))));
            assertEquals(StorageException.Code.CAS_CONFLICT, conflict.code());
            assertEquals(0, database.history().readThreadUsageSummary("thr_title").orElseThrow().requestCount());
        }
    }

    /** 复用历史服务创建的会话锚点，再通过 Store 准入建立 Turn 与用户消息外键。 */
    private void prepareTurn(TestDatabase database) {
        MybatisHistoryService history = database.history();
        history.register(new Workspace.Registration("ws_title", temp.resolve("title-project"), "Title", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, START));
        ThreadPreferences threadPreferences = preferences("provider_title", "model_title");
        history.createThread(new ThreadSummary.Creation("thr_title", "ws_title", "Title", threadPreferences, START));
        AgentStore store = database.jaAgentStore();

        AgentIdentity identity = new AgentIdentity("ja.local", "thr_title", "turn_title", "user_local");
        AgentExecutionRequest request = new AgentExecutionRequest(identity,
                AgentExecutionRequest.AdmissionKind.USER_INPUT,
                Optional.of(new UserContent(List.of(new TextContent("hello")))), "item_turn_title_user",
                Optional.empty(), new AgentStore.OperationIdentity("operation_turn_title", "turn/start", "a".repeat(64), new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}")), Map.of(),
                AgentExecutionRequest.HistorySelection.currentConversation(), START, List.of(),
                Optional.empty(), Optional.empty());
        AdmissionFacts facts = new AdmissionFacts(identity, "ws_title", TurnOrigin.USER, "turn/start", START,
                null, null, null, null, null, null, null, 0, Optional.empty());
        try (AgentStoreProjectionFixture ignored = AgentStoreProjectionFixture.open(store, database.mapper(),
                request, facts, threadPreferences, 0)) {
            assertEquals(1, history.readThread("thr_title", null, 10).orElseThrow().items().size());
        }
    }

    /** 生成与冻结 Turn 完全一致的 durable claim。 */
    private static AutomaticTitleUsagePort.GenerationClaim claim(String generationId) {
        return new AutomaticTitleUsagePort.GenerationClaim(
                generationId, "thr_title", "turn_title", "provider_title", "model_title",
                "cfg_title", START.plusSeconds(1));
    }
}
