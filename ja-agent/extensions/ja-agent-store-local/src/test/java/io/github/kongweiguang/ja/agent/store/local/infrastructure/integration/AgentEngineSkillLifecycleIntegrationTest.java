// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.store.local.infrastructure.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentAnswer;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionOutcome;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentRequest;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware.ExtensionDecision;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware.ToolContext;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware.ToolDecision;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntimeResolver;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool.ApprovalRequirement;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool.ExecutionContext;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool.ExecutionResult;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool.Invocation;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool.ToolResult;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort.FinishReason;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort.InputTokenEstimate;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort.ModelOutcome;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort.ModelRequest;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPortResolver;
import io.github.kongweiguang.ja.agent.application.gateway.ModelEventSink;
import io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRuntimeRequest;
import io.github.kongweiguang.ja.agent.application.gateway.model.SummaryModel;
import io.github.kongweiguang.ja.agent.application.gateway.skill.SkillSource;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.bootstrap.AgentEngineBuilder;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.skill.SkillDescriptor;
import io.github.kongweiguang.ja.agent.domain.skill.SkillPage;
import io.github.kongweiguang.ja.agent.domain.skill.SkillReadRequest;
import io.github.kongweiguang.ja.agent.domain.skill.SkillRequestContext;
import io.github.kongweiguang.ja.agent.domain.skill.SkillResourceContent;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.TurnExecutionStateCodec;
import io.github.kongweiguang.ja.agent.store.local.infrastructure.SQLiteAgentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用真实 SQLite 与公开 Engine 锁定 Skill 首次准入和非终态恢复的生命周期边界。 */
final class AgentEngineSkillLifecycleIntegrationTest {
    private static final String SKILL_ID = "business.docs:guide";
    private static final String SKILL_BODY = "SKILL_LIFECYCLE_MARKER";
    private static final String PROMPT_FINGERPRINT = "a".repeat(64);

    @TempDir
    Path directory;

    /** 首次模型派发必须沿用已激活所选 Skill 的准入 runtime，并把稳定引用与指纹写入 SQLite 游标。 */
    @Test
    void submitsSelectedSkillThroughTheAdmissionRuntime() throws Exception {
        AtomicInteger dispatches = new AtomicInteger();
        RuntimeObservations observations = new RuntimeObservations();
        SkillModel model = new SkillModel(dispatches, false);
        SQLiteAgentStore store = sqlite("admission.sqlite");

        try (AgentEngine engine = engine(store, model, new CountingSkillSource(), observations, false)) {
            AgentRequest request = request("skill-admission");
            AgentExecutionOutcome.Completed completed = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    engine.submit(request).completion().toCompletableFuture().get(20, java.util.concurrent.TimeUnit.SECONDS));

            assertEquals(TurnState.COMPLETED, completed.result().state());
            assertEquals(1, dispatches.get());
            assertEquals(1, observations.freshResolutions.get());
            assertEquals(0, observations.recoveredResolutions.get());
            assertEquals(0, observations.restoreCalls.get(), "admission must not rebuild its own PromptSession from the cursor");
            assertEquals(List.of(SKILL_ID), activeSkillIds(store, request.identity()));
            assertTrue(activeSkills(store, request.identity()).getFirst().contentFingerprint().isPresent());
        }
    }

    /** 审批等待是可恢复的非终态；新 Engine 必须仅按持久游标恢复新 PromptSession 一次后完成闭环。 */
    @Test
    void restoresSelectedSkillOnceWhenResumingAnApprovalWait() throws Exception {
        AtomicInteger dispatches = new AtomicInteger();
        AtomicInteger toolExecutions = new AtomicInteger();
        RuntimeObservations observations = new RuntimeObservations();
        SkillModel model = new SkillModel(dispatches, true);
        AgentIdentity identity = identity("skill-recovery");
        SQLiteAgentStore firstStore = sqlite("recovery.sqlite");
        AgentEngine first = engine(firstStore, model, new CountingSkillSource(), observations, true, toolExecutions);
        String interactionId;
        try {
            AgentExecutionOutcome.Suspended waiting = assertInstanceOf(AgentExecutionOutcome.Suspended.class,
                    first.submit(request(identity)).completion().toCompletableFuture()
                            .get(20, java.util.concurrent.TimeUnit.SECONDS));
            interactionId = waiting.interactionId().orElseThrow();
            assertEquals("WAITING_APPROVAL", first.query(identity).orElseThrow().state());
            assertEquals(List.of(SKILL_ID), activeSkillIds(firstStore, identity));
            assertEquals(1, dispatches.get());
            assertEquals(1, observations.freshResolutions.get());
            assertEquals(0, observations.restoreCalls.get());
        } finally {
            first.close();
        }

        SQLiteAgentStore reopenedStore = sqlite("recovery.sqlite");
        assertEquals(List.of(SKILL_ID), activeSkillIds(reopenedStore, identity),
                "the resumed runtime must consume the persisted cursor, not an in-memory copy");
        AgentEngine recovered = engine(reopenedStore, model, new CountingSkillSource(), observations, true, toolExecutions);
        try {
            recovered.answer(identity, new AgentAnswer(interactionId, "approve-operation", 0,
                    List.of(new InteractionAnswer("question_approval", List.of("option_approve"), null, false)), Map.of()))
                    .toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
            AgentExecutionOutcome.Completed completed = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    recovered.resume(identity).completion().toCompletableFuture()
                            .get(20, java.util.concurrent.TimeUnit.SECONDS));

            assertEquals(TurnState.COMPLETED, completed.result().state());
            assertEquals(2, dispatches.get(), "one initial dispatch and one post-tool dispatch must reach the model");
            assertEquals(1, toolExecutions.get());
            assertEquals(1, observations.freshResolutions.get());
            assertEquals(1, observations.recoveredResolutions.get());
            assertEquals(1, observations.restoreCalls.get());
            assertEquals(List.of(SKILL_ID), observations.restoredReferences.get().stream()
                    .map(TurnExecutionState.ActiveSkill::skillId).toList());
            assertEquals(2, observations.promptSessions.size());
            assertNotSame(observations.promptSessions.get(0), observations.promptSessions.get(1));
        } finally {
            recovered.close();
        }
    }

    /** 每项场景使用独立临时文件；重新创建 DataSource 后的读取证明恢复事实已提交到 SQLite。 */
    private SQLiteAgentStore sqlite(String fileName) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + directory.resolve(fileName));
        SQLiteAgentStore.bootstrap(dataSource);
        return new SQLiteAgentStore(dataSource);
    }

    /** 用真实 Core、SQLite 和标准 Prompt 路径执行；Provider 与 SkillSource 是确定性外部边界夹具。 */
    private AgentEngine engine(SQLiteAgentStore store, SkillModel model, SkillSource source,
                               RuntimeObservations observations, boolean approval) {
        return engine(store, model, source, observations, approval, new AtomicInteger());
    }

    /** 恢复场景另行计数工具副作用，避免用模型最终结果代替工具闭环证据。 */
    private AgentEngine engine(SQLiteAgentStore store, SkillModel model, SkillSource source,
                               RuntimeObservations observations, boolean approval, AtomicInteger toolExecutions) {
        ModelPortResolver ports = ignored -> model;
        SummaryModel.Factory summaries = ignored -> {
            throw new AssertionError("summary model must not be bound in this scenario");
        };
        ManagedAttachmentReader attachments = ignored -> {
            throw new AssertionError("attachments are not part of this scenario");
        };
        AgentRequestRuntimeResolver standard = new io.github.kongweiguang.ja.agent.infrastructure.runtime.StandardAgentRequestRuntimeResolver(
                modelConfiguration(), ports, new ContextBudget(32_768, 4_096, false), summaries, attachments,
                new ToolProjectionLimits(12_000, 2_000));
        return new AgentEngineBuilder().store(store)
                .runtimeResolver(observations.wrap(standard))
                .extensions(registry -> {
                    registry.registerTool(new LookupTool(toolExecutions));
                    registry.registerSkillSource(source);
                    if (approval) {
                        registry.registerMiddleware(new ApprovalWait());
                    }
                }).build();
    }

    /** 模型和 Store binding 使用同一显式配置，避免恢复路径依赖环境变量或上游网络。 */
    private static ModelPort.ModelConfiguration modelConfiguration() {
        return new ModelPort.ModelConfiguration("test-provider", "test-model", "cfg_test",
                ModelPort.Api.OPENAI_RESPONSES, "test-model", URI.create("https://example.invalid/v1"),
                "test-key", Duration.ofSeconds(2), Duration.ofSeconds(10),
                Set.of(ModelPort.InputModality.TEXT), ModelPort.GenerationOptions.defaults());
    }

    /** 请求显式携带 Skill 引用，使 Engine 必须在准入前读取并冻结实际正文。 */
    private static AgentRequest request(String turnId) {
        return request(identity(turnId));
    }

    /** 两条路径复用同一业务请求内容，但各自使用独立执行身份与 SQLite 文件。 */
    private static AgentRequest request(AgentIdentity identity) {
        return new AgentRequest(identity,
                new io.github.kongweiguang.ja.agent.domain.UserContent(
                        List.of(new SkillReferenceContent(SKILL_ID), new TextContent("请按指导处理"))),
                "operation-" + identity.turnId(), Optional.empty(), Map.of());
    }

    /** 从数据库快照解码游标，断言的是写入 Store 的事实而非 PromptSession 内存状态。 */
    private static List<TurnExecutionState.ActiveSkill> activeSkills(SQLiteAgentStore store, AgentIdentity identity) {
        AgentStore.ExecutionKey key = new AgentStore.ExecutionKey(
                new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), identity.userId()), identity.turnId());
        AgentStore.ExecutionSnapshot snapshot = store.loadExecution(key).orElseThrow();
        TurnExecutionState state = new TurnExecutionStateCodec(new ObjectMapper())
                .read(snapshot.cursorSchemaVersion(), snapshot.cursor());
        return state.common().activeSkills();
    }

    /** 为断言保留最小身份投影，避免重复维护测试特有的 Cursor codec。 */
    private static List<String> activeSkillIds(SQLiteAgentStore store, AgentIdentity identity) {
        return activeSkills(store, identity).stream().map(TurnExecutionState.ActiveSkill::skillId).toList();
    }

    /** 使用固定合法身份，明确 SQLite 复合键中的租户、Thread、Turn 和用户归属。 */
    private static AgentIdentity identity(String turnId) {
        return new AgentIdentity("tenant", "thread", turnId, "user");
    }

    /** 共享计数器验证恢复解析拿到新 PromptSession，并且 STORE 来源只调用一次游标恢复。 */
    private static final class RuntimeObservations {
        private final AtomicInteger freshResolutions = new AtomicInteger();
        private final AtomicInteger recoveredResolutions = new AtomicInteger();
        private final AtomicInteger restoreCalls = new AtomicInteger();
        private final AtomicReference<List<TurnExecutionState.ActiveSkill>> restoredReferences = new AtomicReference<>();
        private final java.util.concurrent.CopyOnWriteArrayList<AgentPromptSession> promptSessions =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        /** 将观测限制在 Runtime Resolver 和公开 PromptSession 边界，不插手 Core 执行逻辑。 */
        private AgentRequestRuntimeResolver wrap(AgentRequestRuntimeResolver delegate) {
            return (AgentRuntimeRequest request, ExtensionRegistry.Snapshot extensions,
                    Optional<AgentStore.ExecutionSnapshot> recovered) -> {
                if (recovered.isPresent()) {
                    recoveredResolutions.incrementAndGet();
                } else {
                    freshResolutions.incrementAndGet();
                }
                AgentRequestRuntime runtime = delegate.resolve(request, extensions, recovered);
                AgentPromptSession prompt = (AgentPromptSession) Proxy.newProxyInstance(
                        AgentPromptSession.class.getClassLoader(), new Class<?>[]{AgentPromptSession.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("restoreActiveSkills")) {
                                restoreCalls.incrementAndGet();
                                @SuppressWarnings("unchecked")
                                List<TurnExecutionState.ActiveSkill> references =
                                        (List<TurnExecutionState.ActiveSkill>) arguments[1];
                                restoredReferences.set(List.copyOf(references));
                            }
                            try {
                                return method.invoke(runtime.promptSession(), arguments);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
                promptSessions.add(prompt);
                return new AgentRequestRuntime(runtime.modelConfiguration(), runtime.model(), runtime.summaryModels(),
                        runtime.extensions(), prompt, runtime.bindingFingerprint(), runtime.transactionParticipant(),
                        runtime.attachments(), runtime.outputLimits(), runtime.modelIdentityAttributes(), runtime::close);
            };
        }
    }

    /** 来源只返回一个租户无关稳定资源，数据库身份仍由 SkillRequestContext 单独传递。 */
    private static final class CountingSkillSource implements SkillSource {
        /** 来源名属于持久引用，跨运行时保持不变。 */
        @Override
        public String id() {
            return "business.docs";
        }

        /** 测试没有同名来源竞争，使用稳定的显式优先级。 */
        @Override
        public int priority() {
            return 1;
        }

        /** 单条目录足以覆盖 Skill 的发现到按需读取路径。 */
        @Override
        public SkillPage discover(SkillRequestContext context, String cursor, int pageSize) {
            return new SkillPage(List.of(new SkillDescriptor("guide", "业务处理指导", id(), 1)), null);
        }

        /** 返回可观测正文，避免测试只通过 Skill ID 却没有验证 Prompt 实际内容。 */
        @Override
        public SkillResourceContent read(SkillRequestContext context, SkillDescriptor descriptor,
                                         SkillReadRequest request) {
            assertEquals("tenant", context.tenantId());
            return new SkillResourceContent(SKILL_BODY, false);
        }
    }

    /** 首次自然完成或先触发审批工具，再在恢复后完成的确定性 Provider。 */
    private static final class SkillModel implements ModelPort {
        private final AtomicInteger dispatches;
        private final boolean requestApproval;

        /** 两个分支只改变是否产生一个真实非终态等待，模型信封校验保持相同。 */
        private SkillModel(AtomicInteger dispatches, boolean requestApproval) {
            this.dispatches = dispatches;
            this.requestApproval = requestApproval;
        }

        /** 固定估算使测试不引入摘要、tokenizer 或供应商网络行为。 */
        @Override
        public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellationToken) {
            return new InputTokenEstimate(100, 100, PROMPT_FINGERPRINT);
        }

        /** 每次真实 Provider 派发必须含激活正文，首轮工具调用仅等待用户审批，不绕过执行循环。 */
        @Override
        public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink eventSink,
                                                   CancellationToken cancellationToken) {
            int dispatch = dispatches.incrementAndGet();
            assertTrue(request.prompt().systemPrompt().contains(SKILL_BODY));
            if (requestApproval && dispatch == 1) {
                eventSink.onEvent(new ModelPort.ToolCallReady("call-skill", "lookup", JsonObject.empty(), 0))
                        .toCompletableFuture().join();
                return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.TOOL_CALLS, null,
                        new ModelUsage(100, 10, 110)));
            }
            eventSink.onEvent(new ModelPort.TextDelta("已按指导完成")).toCompletableFuture().join();
            return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.STOP, null,
                    new ModelUsage(100, 10, 110)));
        }
    }

    /** 产生可核验的等待并使用稳定策略身份，使回答可在全新 Engine 上结算。 */
    private static final class ApprovalWait implements AgentMiddleware {
        /** 重启后同一命名空间允许 Core 读取真实等待策略状态。 */
        @Override
        public String id() {
            return "skill-lifecycle-approval";
        }

        /** 审批在工具派发前写成持久等待事实。 */
        @Override
        public ExtensionDecision<ToolDecision> beforeTool(ToolContext context) {
            AgentMiddleware.WaitRequest wait = new AgentMiddleware.WaitRequest("approval", "允许查询？",
                    JsonObjects.builder().putText("expiresAt", java.time.Instant.now().plus(Duration.ofMinutes(10)).toString())
                            .build());
            return AgentMiddleware.ExtensionDecision.unchanged(ToolDecision.waitFor(wait), context.extensionState());
        }
    }

    /** 最小真实 Tool 只在持久审批回答后执行一次，以证明恢复完整走到副作用与后续模型请求。 */
    private static final class LookupTool implements AgentTool {
        private final AtomicInteger executions;

        /** 执行计数用于确认批准的工具结果没有遗漏或重复派发。 */
        private LookupTool(AtomicInteger executions) {
            this.executions = executions;
        }

        /** 空对象参数仍走生产 Schema 校验。 */
        @Override
        public ToolSpec spec() {
            return new ToolSpec("lookup", "查询测试业务记录", JsonObjects.builder().putText("type", "object")
                    .putBoolean("additionalProperties", false).build());
        }

        /** 该操作真实执行但不访问外部系统，恢复失败不会留下不可逆测试副作用。 */
        @Override
        public ToolSideEffect sideEffect() {
            return ToolSideEffect.READ_ONLY;
        }

        /** 审批由本测试明确注册的持久中间件负责，工具默认策略不额外引入第二个审批分支。 */
        @Override
        public ApprovalRequirement approvalRequirement() {
            return ApprovalRequirement.NOT_REQUIRED;
        }

        /** 完成已审批调用后返回可供后续模型使用的真实工具结果。 */
        @Override
        public CompletionStage<ExecutionResult> execute(Invocation invocation, ExecutionContext context,
                                                         CancellationToken cancellationToken) {
            executions.incrementAndGet();
            return CompletableFuture.completedFuture(ExecutionResult.of(ToolResult.success("业务结果")));
        }
    }
}
