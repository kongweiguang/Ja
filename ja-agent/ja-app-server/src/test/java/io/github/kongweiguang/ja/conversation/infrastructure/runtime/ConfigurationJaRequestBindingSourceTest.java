// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.runtime;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.conversation.application.admission.JaAdmissionParticipants;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadProjection;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.application.gateway.ConversationProjectionRepository;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOwnerProjection;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRuntimeRequest;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionParticipantResolver;
import io.github.kongweiguang.ja.conversation.application.gateway.JaAdmissionTransactionParticipant;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定 Child admission 未使用客户端幂等键时的 nullable fingerprint 合同。 */
final class ConfigurationJaRequestBindingSourceTest {
    /** 自动标题在快照读取后推进 revision 时，必须在资源解析和事务前暴露可重读的 CAS 类型。 */
    @Test
    void classifiesPreAdmissionRevisionRaceAsCasConflict() {
        ThreadPreferences preferences = new ThreadPreferences("provider_test", "model_test", null,
                AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.AUTO);
        ConversationProjectionRepository projections = new ConversationProjectionRepository() {
            /** 模拟自动标题已经提交的新水位，不伪造已接纳的执行。 */
            @Override public Optional<ThreadProjection> readThread(String threadId) {
                return Optional.of(new ThreadProjection(threadId, "ws_test", preferences, 22, List.of()));
            }
            /** 准入竞争检测应在 Turn 查询之前结束。 */
            @Override public Optional<TurnOwnerProjection> readTurn(String turnId) {
                throw new AssertionError("Turn lookup is not expected before admission");
            }
        };
        ConfigurationJaRequestBindingSource source = new ConfigurationJaRequestBindingSource(projections,
                workspaceId -> { throw new AssertionError("stale admission must not resolve workspace"); },
                identity -> { throw new AssertionError("stale admission must not resolve native context"); },
                new JaAdmissionParticipants(),
                facts -> { throw new AssertionError("stale admission must not create transaction participant"); },
                facts -> Optional.empty());
        AgentRuntimeRequest request = new AgentRuntimeRequest(AgentRuntimeRequest.Kind.USER_INPUT,
                new AgentIdentity("ja.local", "thr_test", "turn_test", "user_local"), Optional.empty(),
                Map.of("ja.workspaceId", "ws_test", "ja.providerId", "provider_test",
                        "ja.modelId", "model_test", "ja.accessMode", "APPROVAL_REQUIRED",
                        "ja.collaborationMode", "DEFAULT", "ja.origin", "USER",
                        "ja.method", "turn/start", "ja.acceptedAt", "2026-09-27T00:00:00Z",
                        "ja.expectedThreadRevision", "21"), CancellationToken.none());

        StorageException conflict = assertThrows(StorageException.class,
                () -> source.bind(request, Optional.empty()));
        assertEquals(StorageException.Code.CAS_CONFLICT, conflict.code());
    }

    /** 可选幂等身份允许同时缺失；比较两份冻结事实不能因 nullable fingerprint 触发 NPE。 */
    @Test
    void comparesAdmissionFactsWithoutClientOperationIdentity() {
        AgentIdentity identity = new AgentIdentity("ja.local", "thr_child", "turn_child", "user_local");
        AdmissionFacts expected = childAdmission(identity);
        AdmissionFacts actual = childAdmission(identity);

        assertTrue(ConfigurationJaRequestBindingSource.sameRequestFacts(expected, actual));
    }

    /** Child followup 的真实来源消息由 Core 准入事务分配，不能要求 Ja 请求伪造历史 item ID。 */
    @Test
    void allowsTaskAdmissionWithoutJaSourceMessageIdentity() {
        ConfigurationJaRequestBindingSource.requireSourceMessage(
                TurnOrigin.CHILD_TASK, "task/followup", null);
    }

    /** 真实用户续写和 reask 仍必须引用明确历史项，防止弱化用户输入的历史边界。 */
    @Test
    void continuesToRequireSourceMessageForUserContinuationAndReask() {
        assertThrows(IllegalArgumentException.class, () ->
                ConfigurationJaRequestBindingSource.requireSourceMessage(
                        TurnOrigin.USER_CONTINUATION, "turn/continue", null));
        assertThrows(IllegalArgumentException.class, () ->
                ConfigurationJaRequestBindingSource.requireSourceMessage(
                        TurnOrigin.USER, "turn/reask", null));
    }

    /** 新 USER 与 Plan admission 共用业务 resolver，恢复执行不重新建立业务 Turn。 */
    @Test
    void resolvesBusinessParticipantForNewAdmissionButNotRecovery() {
        AgentIdentity user = new AgentIdentity("ja.local", "thr_user", "turn_user", "user_local");
        AgentIdentity plan = new AgentIdentity("ja.local", "thr_plan", "turn_plan", "user_local");
        AtomicInteger calls = new AtomicInteger();
        JaAdmissionTransactionParticipant participant = new JaAdmissionTransactionParticipant() {
            /** 测试参与者只用于验证 resolver 路由，不写准入前事实。 */
            @Override
            public void beforeProjection(AgentStoreTransactionContext transaction) { }

            /** 测试参与者只用于验证 resolver 路由，不写准入后事实。 */
            @Override
            public void afterProjection(AgentStoreTransactionContext transaction) { }
        };
        AdmissionParticipantResolver resolver = facts -> {
            calls.incrementAndGet();
            return Optional.of(participant);
        };

        assertSame(participant, ConfigurationJaRequestBindingSource.resolveAdmissionParticipant(
                userAdmission(user), resolver, true).orElseThrow());
        assertSame(participant, ConfigurationJaRequestBindingSource.resolveAdmissionParticipant(
                planAdmission(plan), resolver, true).orElseThrow());
        assertTrue(ConfigurationJaRequestBindingSource.resolveAdmissionParticipant(
                userAdmission(user), resolver, false).isEmpty());
        assertEquals(2, calls.get());
    }

    /** 只有 Core COMPACTION 才可承载 compaction identity，普通 Turn 仍必须使用 turn identity。 */
    @Test
    void validatesCompactionKindAndIdentityAsAnIndependentExecution() {
        AgentIdentity compaction = new AgentIdentity("ja.local", "thr_compact", "compaction_" + "a".repeat(64), "user_local");
        AgentIdentity turn = new AgentIdentity("ja.local", "thr_compact", "turn_regular", "user_local");

        ConfigurationJaRequestBindingSource.validateJaIdentity(compaction, true);
        ConfigurationJaRequestBindingSource.validateJaIdentity(turn, false);
        ConfigurationJaRequestBindingSource.validateRequestKind(AgentRuntimeRequest.Kind.COMPACTION,
                "thread/compact");
        ConfigurationJaRequestBindingSource.validateRequestKind(AgentRuntimeRequest.Kind.USER_INPUT,
                "turn/start");

        assertThrows(IllegalArgumentException.class,
                () -> ConfigurationJaRequestBindingSource.validateJaIdentity(compaction, false));
        assertThrows(IllegalArgumentException.class,
                () -> ConfigurationJaRequestBindingSource.validateJaIdentity(turn, true));
        assertThrows(IllegalArgumentException.class,
                () -> ConfigurationJaRequestBindingSource.validateRequestKind(AgentRuntimeRequest.Kind.INTERNAL,
                        "thread/compact"));
        assertThrows(IllegalArgumentException.class,
                () -> ConfigurationJaRequestBindingSource.validateRequestKind(AgentRuntimeRequest.Kind.COMPACTION,
                        "turn/start"));
    }

    /** 构造同一份合法 Child 准入事实，确保测试只变化 nullable fingerprint 这一原有边界。 */
    private static AdmissionFacts childAdmission(AgentIdentity identity) {
        return new AdmissionFacts(identity, "ws_child", TurnOrigin.CHILD_TASK, "task/create",
                Instant.parse("2026-09-26T00:00:00Z"), "item_source", "turn_parent", "turn_root",
                null, null, null, null, 0, Optional.empty());
    }

    /** 构造最小普通 USER admission 事实，避免测试通过 Task 私有 DTO 推断绑定行为。 */
    private static AdmissionFacts userAdmission(AgentIdentity identity) {
        return new AdmissionFacts(identity, "ws_user", TurnOrigin.USER, "turn/start",
                Instant.parse("2026-09-26T00:00:00Z"), null, null, null,
                null, null, null, null, 0, Optional.empty());
    }

    /** Plan 准入携带已批准 revision 与 Run 身份，不伪造 Task 父级关系。 */
    private static AdmissionFacts planAdmission(AgentIdentity identity) {
        return new AdmissionFacts(identity, "ws_plan", TurnOrigin.PLAN_EXECUTION, "plan/execute",
                Instant.parse("2026-09-26T00:00:00Z"), null, null, null,
                "plan_revision", "run_plan", null, null, 2, Optional.empty());
    }
}
