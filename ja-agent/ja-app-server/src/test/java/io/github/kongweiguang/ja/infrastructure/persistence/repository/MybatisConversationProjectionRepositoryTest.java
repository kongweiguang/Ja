// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadProjection;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.infrastructure.persistence.support.AgentStoreProjectionFixture;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Ja Thread/Turn 轻量读取来自 Store admission 的同事务持久投影。 */
final class MybatisConversationProjectionRepositoryTest extends PersistenceTestSupport {

    /** 同一真实 admission 提交 Thread revision、Turn 初态与 USER 事实，不拼接旧的应用级仓储写 API。 */
    @Test
    void readsThreadPreferencesAndTurnStateFromOneSnapshot() throws Exception {
        String threadId = "thr_projection";
        String workspaceId = "ws_projection";
        String turnId = "turn_projection";
        ThreadPreferences preferences = preferences();
        try (TestDatabase database = database("conversation-projection")) {
            database.history().register(new Workspace.Registration(workspaceId, temp.toAbsolutePath(), "workspace", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, START));
            database.history().createThread(new ThreadSummary.Creation(
                    threadId, workspaceId, "thread", preferences, START));
            AgentStore store = database.jaAgentStore();

            AgentIdentity identity = new AgentIdentity("ja.local", threadId, turnId, "user_local");
            AgentExecutionRequest request = new AgentExecutionRequest(identity,
                    AgentExecutionRequest.AdmissionKind.USER_INPUT,
                    Optional.of(new UserContent(List.of(new TextContent("run")))), "item_projection",
                    Optional.empty(), new AgentStore.OperationIdentity("operation_projection", "turn/start", "a".repeat(64), new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}")), Map.of(),
                    AgentExecutionRequest.HistorySelection.currentConversation(), START, List.of(),
                    Optional.empty(), Optional.empty());
            AdmissionFacts jaFacts = new AdmissionFacts(identity, workspaceId, TurnOrigin.USER, "turn/start",
                    START, null, null, null, null, null, null, null, 0, Optional.empty());

            try (AgentStoreProjectionFixture ignored = AgentStoreProjectionFixture.open(store, database.mapper(),
                    request, jaFacts, preferences, 0)) {
                ThreadProjection projection = database.conversationProjections().readThread(threadId).orElseThrow();

                assertEquals(threadId, projection.threadId());
                assertEquals(workspaceId, projection.workspaceId());
                assertEquals(1, projection.revision());
                assertEquals(preferences.providerId(), projection.preferences().providerId());
                assertEquals(preferences.modelId(), projection.preferences().modelId());
                assertEquals(1, projection.turns().size());
                assertEquals(turnId, projection.turns().getFirst().turnId());
                assertEquals(TurnState.QUEUED, projection.turns().getFirst().state());
                assertEquals(1, projection.turns().getFirst().mutationVersion());
                assertTrue(projection.turns().getFirst().currentPath());
                assertEquals("item_projection", projection.turns().getFirst().sourceMessageId());
            }
        }
    }

    /** 样本偏好显式冻结权限与协作模式，避免投影断言由生产默认值偶然成立。 */
    private static ThreadPreferences preferences() {
        return new ThreadPreferences("provider_projection", "model_projection", "medium",
                AccessMode.APPROVAL_REQUIRED,
                io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode.DEFAULT,
                ThreadPreferences.TitleSource.PLACEHOLDER);
    }
}
