// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.interaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.application.interaction.InteractionToolFactory;
import io.github.kongweiguang.ja.conversation.application.interaction.InteractionService;
import io.github.kongweiguang.ja.agent.application.concurrent.CancellationSource;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.conversation.application.gateway.JaToolCatalogIdentity;
import io.github.kongweiguang.ja.conversation.application.gateway.JaToolRequestContext;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeRequest;
import io.github.kongweiguang.ja.conversation.infrastructure.tools.JaAgentToolAdapter;
import io.github.kongweiguang.ja.conversation.infrastructure.tools.JaToolBindingCodec;
import io.github.kongweiguang.ja.conversation.application.loop.WorkspaceWriteLeaseCoordinator;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.conversation.application.gateway.InteractionRepository;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionRequest;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionResumeState;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionSnapshot;
import io.github.kongweiguang.ja.conversation.application.gateway.TaskCapabilityCeilingPort;
import io.github.kongweiguang.ja.infrastructure.persistence.store.ToolPresentationCodec;
import io.github.kongweiguang.ja.conversation.application.gateway.ConversationWorkspaceWriteClaimPort;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证公共提问能力以 Turn 来源为硬边界，不把用户普通模式偏好误用于 Plan-owned 执行。 */
final class InteractionToolFactoryTest {
    /** Plan-owned 执行即使普通澄清设置关闭，也必须保留 request_user_input。 */
    @Test
    void planExecutionOverridesDisabledPolicy() {
        InteractionToolFactory factory = new InteractionToolFactory(service());

        assertEquals(1, factory.toolsFor(request(CollaborationMode.DEFAULT, TurnOrigin.PLAN_EXECUTION,
                false, Optional.empty())).visibleNames().size());
    }

    /** 普通模式仍服从关闭设置，防止 Plan 特例扩大到所有来源。 */
    @Test
    void ordinaryTurnRespectsDisabledPolicy() {
        InteractionToolFactory factory = new InteractionToolFactory(service());

        assertEquals(0, factory.toolsFor(request(CollaborationMode.DEFAULT, TurnOrigin.USER,
                false, Optional.empty())).visibleNames().size());
    }

    /** 只读规划与子代理边界分别验证：规划可提问，子代理不能直接抢占用户卡片。 */
    @Test
    void planningAllowsQuestionsButSubagentDoesNot() {
        InteractionToolFactory factory = new InteractionToolFactory(service());

        assertEquals(1, factory.toolsFor(request(CollaborationMode.PLAN, TurnOrigin.USER,
                false, Optional.empty())).visibleNames().size());
        assertEquals(0, factory.toolsFor(request(CollaborationMode.PLAN, TurnOrigin.CHILD_TASK,
                false, Optional.of(TaskCapabilityCeilingPort.Kind.SUBAGENT))).visibleNames().size());
    }

    /** 隐藏提问入口仍声明完整安全定义，避免角色显隐掩盖 Schema 或审批语义变化。 */
    @Test
    void keepsQuestionDefinitionWhenVisibilityChanges() {
        InteractionToolFactory factory = new InteractionToolFactory(service());
        JaTool.Selection hidden = factory.toolsFor(request(CollaborationMode.DEFAULT, TurnOrigin.USER,
                false, Optional.empty()));
        JaTool.Selection visible = factory.toolsFor(request(CollaborationMode.PLAN, TurnOrigin.USER,
                false, Optional.empty()));

        assertEquals(0, hidden.visibleNames().size());
        assertEquals(1, visible.visibleNames().size());
        assertEquals(List.of("request_user_input"), hidden.definitions().stream()
                .map(value -> value.spec().name()).toList());
        assertEquals(List.of("request_user_input"), visible.definitions().stream()
                .map(value -> value.spec().name()).toList());
    }

    /** 可见能力只补充结构化问答与审批边界，不复制核心自主推进规则。 */
    @Test
    void visibleQuestionToolTreatsClarificationAsLastResort() {
        InteractionToolFactory factory = new InteractionToolFactory(service());

        String prompt = factory.toolsFor(request(CollaborationMode.DEFAULT, TurnOrigin.USER,
                true, Optional.empty())).promptFragment();

        assertTrue(prompt.contains("essential clarification"));
        assertTrue(prompt.contains("request_user_input"));
        assertTrue(prompt.contains("wait for the structured response"));
        assertTrue(prompt.contains("Do not use clarification to duplicate tool approval"));
        assertTrue(prompt.contains("minimum unanswered questions together"));
    }

    /** deadline 刷新不影响答案恢复与 Tool 执行；真实 Turn 漂移仍拒绝恢复。
     *
     * @author kongweiguang
     */
    @Test
    void requestUserInputReconcilesOnlyMatchingPersistedAnswer() {
        InteractionRequest answered = new InteractionRequest("interaction_persisted", "thr_interaction",
                "turn_interaction", "call_interaction", null, null, "interaction_interaction",
                List.of(new io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestion(
                        "question_scope", "Which scope should be used?",
                        io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestionType.TEXT,
                        List.of(), true, true)), InteractionStatus.ANSWERED,
                List.of(new InteractionAnswer("question_scope", List.of(), "the broad project scope", false)),
                1, Instant.parse("2026-09-10T12:00:00Z"), Instant.parse("2026-09-10T12:01:00Z"));
        InteractionToolFactory factory = new InteractionToolFactory(
                new InteractionService(repositoryWithAnswer(answered)));
        JaToolRequestContext frozenRequest = request(
                CollaborationMode.DEFAULT, TurnOrigin.USER, true, Optional.empty());
        JaTool.Selection prepared = factory.toolsFor(frozenRequest);
        JaToolCatalogIdentity catalog = new JaToolCatalogIdentity(
                "a".repeat(64), "mcp_test", java.util.Set.of());
        JaTool tool = prepared.definitions().getFirst().materialize(catalog);
        AgentTool.Invocation invocation = questionInvocation("call_interaction", "Which scope should be used?");
        AgentIdentity identity = new AgentIdentity("tenant_interaction", "thr_interaction",
                "turn_interaction", "user_interaction");
        JaTool.ExecutionContext context = new JaTool.ExecutionContext(identity, invocation.callId(),
                identity.toolCallKey(invocation.callId()), "thr_interaction", "turn_interaction",
                frozenRequest.turn().workspaceRoot(), frozenRequest.preferences().accessMode(),
                frozenRequest.configGeneration(), frozenRequest.deadline().plusSeconds(30),
                frozenRequest.turn().workspaceId(),
                null, null, frozenRequest.turn().origin());

        JaTool.RecoveryResolution.Applied recovered = assertInstanceOf(JaTool.RecoveryResolution.Applied.class,
                tool.reconcile(invocation, context, Optional.empty(), CancellationToken.none())
                        .toCompletableFuture().join());
        JaTool.RecoveryResolution.Applied recoveredAgain = assertInstanceOf(JaTool.RecoveryResolution.Applied.class,
                tool.reconcile(invocation, context, Optional.empty(), CancellationToken.none())
                        .toCompletableFuture().join());
        assertEquals(recovered.result(), recoveredAgain.result());
        assertEquals(recovered.verificationFingerprint(), recoveredAgain.verificationFingerprint());
        assertTrue(recovered.verificationFingerprint().matches("[0-9a-f]{64}"));
        assertEquals(recovered.result(), tool.execute(invocation, context, CancellationToken.none())
                .toCompletableFuture().join());

        AgentIdentity otherTurnIdentity = new AgentIdentity("tenant_interaction", "thr_interaction",
                "turn_other", "user_interaction");
        JaTool.ExecutionContext otherTurnContext = new JaTool.ExecutionContext(otherTurnIdentity,
                invocation.callId(), otherTurnIdentity.toolCallKey(invocation.callId()), "thr_interaction",
                "turn_other", context.workspaceRoot(), context.accessMode(), context.configGeneration(),
                context.deadline(), context.workspaceId(), null, null, context.origin());
        assertInstanceOf(JaTool.RecoveryResolution.Unknown.class,
                tool.reconcile(invocation, otherTurnContext, Optional.empty(), CancellationToken.none())
                        .toCompletableFuture().join());

        AgentTool.Invocation wrongCall = questionInvocation("call_other", "Which scope should be used?");
        assertInstanceOf(JaTool.RecoveryResolution.Unknown.class,
                tool.reconcile(wrongCall, context, Optional.empty(), CancellationToken.none())
                        .toCompletableFuture().join());
        AgentTool.Invocation changedQuestion = questionInvocation(
                invocation.callId(), "Which different scope should be used?");
        assertInstanceOf(JaTool.RecoveryResolution.Unknown.class,
                tool.reconcile(changedQuestion, context, Optional.empty(), CancellationToken.none())
                        .toCompletableFuture().join());

        CancellationSource cancellation = new CancellationSource();
        cancellation.cancel("recovery_cancelled");
        assertThrows(CancellationException.class, () -> tool.reconcile(invocation, context,
                Optional.empty(), cancellation));

        ObjectMapper mapper = new ObjectMapper();
        ConversationWorkspaceWriteClaimPort noWriteClaims = (ConversationWorkspaceWriteClaimPort) Proxy.newProxyInstance(
                ConversationWorkspaceWriteClaimPort.class.getClassLoader(), new Class<?>[] { ConversationWorkspaceWriteClaimPort.class },
                (proxy, method, arguments) -> {
                    throw new AssertionError("interaction tools do not claim workspace write leases");
                });
        try (WorkspaceWriteLeaseCoordinator writeLeases = new WorkspaceWriteLeaseCoordinator(
                noWriteClaims, Clock.systemUTC(), 1)) {
            AgentTool adapter = new JaAgentToolAdapter(tool, new JaAgentToolAdapter.Dependencies(
                    (activeInvocation, coreContext) -> context,
                    frozenRequest.preferences().accessMode(), new JaToolBindingCodec(mapper), writeLeases,
                    new ToolPresentationCodec(mapper), List.of(), List.of(), frozenRequest.turn().origin(),
                    frozenRequest.preferences().collaborationMode()));
            AgentTool.ExecutionContext coreContext = new AgentTool.ExecutionContext(
                    identity, identity.toolCallKey(invocation.callId()), Map.of());
            try (AgentTool.ExecutionResult result = adapter.execute(invocation, coreContext,
                    CancellationToken.none()).toCompletableFuture().join()) {
                assertEquals(io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome.SUCCEEDED,
                        result.result().outcome());
                assertTrue(result.result().content().contains("the broad project scope"));
                JsonObject structured = assertInstanceOf(JsonObject.class,
                        result.result().structuredContent().orElseThrow());
                JsonArray answers = assertInstanceOf(JsonArray.class, structured.get("answers"));
                JsonObject answer = assertInstanceOf(JsonObject.class, answers.values().getFirst());
                assertEquals("question_scope", ((io.github.kongweiguang.ja.agent.domain.json.JsonText)
                        answer.get("questionId")).value());
                assertEquals("the broad project scope", ((io.github.kongweiguang.ja.agent.domain.json.JsonText)
                        answer.get("freeText")).value());
            }
        }
    }

    /** 复用一题文本调用，按参数变化单独验证恢复绑定而不复制运行时装配夹具。 */
    private static AgentTool.Invocation questionInvocation(String callId, String prompt) {
        JsonObject question = JsonObjects.builder().putText("questionId", "question_scope")
                .putText("prompt", prompt).putText("type", "text")
                .put("options", new JsonArray(List.of()))
                .putBoolean("required", true).putBoolean("allowFreeText", true).build();
        return new AgentTool.Invocation(callId, "request_user_input",
                JsonObjects.builder().put("questions", new JsonArray(List.of(question))).build(), 0);
    }

    /** 构造真实服务对象但不提供可执行的外部端口，prepare 阶段不得触发任何 Repository 调用。 */
    private static InteractionService service() {
        return new InteractionService(unused(InteractionRepository.class));
    }

    /** 只提供已提交答案快照，其它写操作 fail-fast，确保恢复不依赖进程内待答问题状态。 */
    private static InteractionRepository repositoryWithAnswer(InteractionRequest request) {
        return (InteractionRepository) Proxy.newProxyInstance(
                InteractionRepository.class.getClassLoader(), new Class<?>[] { InteractionRepository.class },
                (proxy, method, arguments) -> {
                    if (method.getName().equals("read")) {
                        return Optional.of(new InteractionSnapshot(request.threadId(), 4, Optional.of(request),
                                Optional.empty(), InteractionResumeState.RESUMING));
                    }
                    throw new AssertionError("unexpected repository call: " + method.getName());
                });
    }

    /** 用 fail-fast 动态代理隔离目录准入测试与 SQLite 及 Conversation 事务。 */
    @SuppressWarnings("unchecked")
    private static <T> T unused(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
                (proxy, method, arguments) -> {
                    throw new AssertionError("unexpected repository call: " + method.getName());
                });
    }

    /** 按 Turn 来源补齐必需的 Plan revision 与 run 标识，避免测试上下文缺少所属执行身份。 */
    private static JaToolRequestContext request(CollaborationMode mode, TurnOrigin origin,
                                                    boolean clarification,
                                                    Optional<TaskCapabilityCeilingPort.Kind> taskKind) {
        Instant requestedAt = Instant.parse("2026-09-10T12:00:00Z");
        ThreadPreferences preferences = new ThreadPreferences("provider_interaction", "model_interaction", "medium",
                AccessMode.FULL_ACCESS, mode, ThreadPreferences.TitleSource.MANUAL);
        String planRevisionId = origin == TurnOrigin.PLAN_EXECUTION ? "planrev_interaction" : null;
        String runId = origin == TurnOrigin.PLAN_EXECUTION ? "run_interaction" : null;
        TurnRuntimeRequest turn = new TurnRuntimeRequest("thr_interaction", "turn_interaction",
                Path.of(".").toAbsolutePath(), "ws_interaction", preferences.providerId(), preferences.modelId(),
                preferences.reasoningLevel(), preferences.accessMode(), mode, origin,
                planRevisionId, runId, requestedAt, null, java.util.Optional.empty());
        return new JaToolRequestContext(turn, preferences, "cfg_" + "a".repeat(64), clarification,
                requestedAt, taskKind);
    }
}
