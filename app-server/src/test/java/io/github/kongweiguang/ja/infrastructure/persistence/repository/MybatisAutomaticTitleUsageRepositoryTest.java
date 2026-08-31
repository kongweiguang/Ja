// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.port.out.AutomaticTitleUsagePort;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.preferences;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.runtime;
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
        }
    }

    /** 创建 claim 所需的真实 Workspace、Thread 与冻结 Turn 外键。 */
    private void prepareTurn(TestDatabase database) {
        MybatisConversationRepository conversations = database.agentStore();
        MybatisHistoryService history = database.history(conversations);
        history.register(new Workspace.Registration("ws_title", temp.resolve("title-project"),
                "Title", Workspace.Trust.TRUSTED, START));
        conversations.createThread(new ConversationRepository.ThreadDefinition(
                "thr_title", "ws_title", "Title",
                preferences("provider_title", "model_title"), START));
        conversations.admit(new ConversationRepository.TurnAdmission(
                "thr_title", "turn_title", runtime("provider_title", "model_title", "cfg_title"),
                "item_title", new ModelMessage(ModelRole.USER, List.of(new TextContent("hello"))),
                List.of(), 0, START));
    }

    /** 生成与冻结 Turn 完全一致的 durable claim。 */
    private static AutomaticTitleUsagePort.GenerationClaim claim(String generationId) {
        return new AutomaticTitleUsagePort.GenerationClaim(
                generationId, "thr_title", "turn_title", "provider_title", "model_title",
                "cfg_title", START.plusSeconds(1));
    }
}
