// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.service;

import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest.HistorySelection;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentCompactionRequest;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInternalRequest;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentRequest;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.application.api.InternalTurnStartRequest;
import io.github.kongweiguang.ja.conversation.application.api.TurnStartRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Ja 请求映射把业务身份留在受控属性中，宿主路径与环境不会进入 Core 请求。 */
final class JaTurnRequestMapperTest {
    private static final Instant ACCEPTED_AT = Instant.parse("2026-09-26T00:00:00Z");

    /** 用户请求从 ja.operationKey 读取幂等身份并保留历史 CAS，同时只把工作区 opaque ID 交给 Core。 */
    @Test
    void mapsUserRequestWithoutPersistingHostPathOrEnvironment() {
        TurnStartRequest request = userRequest("turn_mapper");
        AgentRequest mapped = JaTurnRequestMapper.user(request, request.content(), TurnOrigin.USER,
                HistorySelection.explicit(List.of("turn_prior"), 9),
                Map.of("ja.operationKey", "op_mapper", "ja.requestFingerprint", "f".repeat(64)));

        assertEquals(JaTurnRequestMapper.identity("thr_mapper", "turn_mapper"), mapped.identity());
        assertEquals("op_mapper", mapped.operationKey());
        assertEquals(List.of("turn_prior"), mapped.history().visibleTurnIds());
        assertEquals(9, mapped.history().expectedThreadRevision());
        assertEquals("ws_mapper", mapped.contextAttributes().get("ja.workspaceId"));
        assertEquals("provider_test", mapped.contextAttributes().get("ja.providerId"));
        assertFalse(mapped.contextAttributes().containsValue(request.workspaceRoot().toString()));
        assertFalse(mapped.contextAttributes().keySet().stream()
                .anyMatch(key -> key.toLowerCase(java.util.Locale.ROOT).contains("path")
                        || key.toLowerCase(java.util.Locale.ROOT).contains("environment")));
    }

    /** 宿主扩展不能覆盖固定的 Ja 身份字段，否则准入参与者与 Resolver 会读取不同来源。 */
    @Test
    void rejectsReservedAttributeOverride() {
        TurnStartRequest request = userRequest("turn_mapper");
        assertThrows(IllegalArgumentException.class, () -> JaTurnRequestMapper.user(request,
                request.content(), TurnOrigin.USER, HistorySelection.currentConversation(),
                Map.of("ja.workspaceId", "ws_other")));
    }

    /** 只把创建时冻结的上下文 seed 交给子任务首轮，普通用户请求不能携带跨会话背景。 */
    @Test
    void mapsFrozenSeedOnlyForChildTask() {
        InitialContextSeed seed = new InitialContextSeed(
                new InitialContextSeed.SourceIdentity("ja.local", "thr_parent", "turn_parent"), 1,
                List.of(ContextMessage.text("msg_parent", "turn_parent", 1, ContextMessage.Role.USER,
                        "frozen parent context", 4)), Optional.empty());
        TurnStartRequest base = userRequest("turn_mapper");
        TurnStartRequest request = new TurnStartRequest(base.threadId(), base.turnId(), base.workspaceId(),
                base.workspaceRoot(), base.content(), base.providerId(), base.modelId(), base.reasoningLevel(),
                base.accessMode(), base.collaborationMode(), base.expectedThreadRevision(),
                base.initialTurnMutationVersion(), base.requestedAt(), Optional.of(seed), Optional.empty(),
                Optional.empty(), false);

        AgentRequest child = JaTurnRequestMapper.user(request, request.content(), TurnOrigin.CHILD_TASK,
                HistorySelection.currentConversation(), Map.of());

        assertEquals(Optional.of(seed), child.initialContextSeed());
        assertThrows(IllegalArgumentException.class, () -> JaTurnRequestMapper.user(request,
                request.content(), TurnOrigin.USER, HistorySelection.currentConversation(), Map.of()));
    }

    /** 内部续跑指令 ID 随 Turn 稳定，重试不会追加另一条指令或伪造用户消息。 */
    @Test
    void mapsInternalInstructionToStableCoreIdentity() {
        InternalTurnStartRequest request = new InternalTurnStartRequest(
                "thr_mapper", "turn_mapper", "ws_mapper", Path.of("C:\\ja-workspace"),
                "provider_test", "model_test", null, AccessMode.FULL_ACCESS, CollaborationMode.DEFAULT,
                9, 1, ACCEPTED_AT, TurnOrigin.USER_CONTINUATION, null, null);
        AgentInternalRequest first = JaTurnRequestMapper.internal(request, "continue", HistorySelection.currentConversation(),
                Map.of("ja.sourceMessageId", "item_source"));
        AgentInternalRequest retried = JaTurnRequestMapper.internal(request, "continue", HistorySelection.currentConversation(),
                Map.of("ja.sourceMessageId", "item_source"));

        assertEquals(first.identity(), retried.identity());
        assertEquals(first.instruction().messageId(), retried.instruction().messageId());
        assertEquals("continue", first.instruction().text());
        assertEquals("item_source", first.contextAttributes().get("ja.sourceMessageId"));
    }

    /** 手动压缩使用独立可重放身份和精确历史 CAS，不生成 Turn 或携带 Workspace 路径。 */
    @Test
    void mapsManualCompactionToDedicatedTurnIdentity() {
        ThreadPreferences preferences = new ThreadPreferences("provider_test", "model_test", "medium",
                AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT,
                ThreadPreferences.TitleSource.PLACEHOLDER);
        HistorySelection history = HistorySelection.explicit(List.of("turn_old", "turn_current"), 12);

        AgentCompactionRequest first = JaTurnRequestMapper.compaction("thr_mapper", "ws_mapper", preferences,
                "operation_compact", history, 12, ACCEPTED_AT);
        AgentCompactionRequest retried = JaTurnRequestMapper.compaction("thr_mapper", "ws_mapper", preferences,
                "operation_compact", history, 12, ACCEPTED_AT);

        assertEquals(first.identity(), retried.identity());
        assertEquals("thr_mapper", first.identity().threadId());
        assertEquals("user_local", first.identity().userId());
        assertTrue(first.identity().turnId().matches("compaction_[0-9a-f]{64}"));
        assertEquals("operation_compact", first.operationKey());
        assertEquals(history, first.history());
        assertEquals("thread/compact", first.contextAttributes().get("ja.method"));
        assertEquals("12", first.contextAttributes().get("ja.expectedThreadRevision"));
        assertEquals(ACCEPTED_AT, first.acceptedAt());
        assertFalse(first.contextAttributes().keySet().stream()
                .anyMatch(key -> key.toLowerCase(java.util.Locale.ROOT).contains("path")
                        || key.toLowerCase(java.util.Locale.ROOT).contains("environment")));
    }

    /** 固定统一样例使映射断言聚焦身份、路径隔离和属性冲突而不依赖运行环境。 */
    private static TurnStartRequest userRequest(String turnId) {
        return new TurnStartRequest("thr_mapper", turnId, "ws_mapper", Path.of("C:\\ja-workspace"),
                new UserContent(List.of(new TextContent("hello"))), "provider_test", "model_test", null,
                AccessMode.FULL_ACCESS, CollaborationMode.DEFAULT, 9, 0, ACCEPTED_AT, Optional.empty(),
                Optional.empty(), Optional.empty(), false);
    }
}
