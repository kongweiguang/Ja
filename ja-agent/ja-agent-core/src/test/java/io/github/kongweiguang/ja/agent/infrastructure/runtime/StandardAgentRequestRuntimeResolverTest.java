// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.runtime;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.skill.SkillDescriptor;
import io.github.kongweiguang.ja.agent.domain.skill.SkillPage;
import io.github.kongweiguang.ja.agent.domain.skill.SkillReadRequest;
import io.github.kongweiguang.ja.agent.domain.skill.SkillRequestContext;
import io.github.kongweiguang.ja.agent.domain.skill.SkillResourceContent;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRuntimeRequest;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.ModelEventSink;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader;
import io.github.kongweiguang.ja.agent.application.gateway.model.SummaryModel;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.skill.SkillSource;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry.Snapshot;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort.ModelConfiguration;
import io.github.kongweiguang.ja.agent.application.gateway.model.SummaryModel.TurnBinding;

/**
 * 覆盖标准 resolver 的显式模型路由、身份隔离、能力指纹和请求资源所有权。
 */
final class StandardAgentRequestRuntimeResolverTest {
    private static final AgentIdentity IDENTITY =
            new AgentIdentity("tenant-a", "thread-a", "turn-a", "user-a");
    private static final ContextBudget BUDGET = new ContextBudget(32_768, 4_096, true);
    private static final ToolProjectionLimits OUTPUT_LIMITS = new ToolProjectionLimits(8_192, 8_192);
    private static final ManagedAttachmentReader ATTACHMENTS = request -> {
        throw new AssertionError("attachments are outside resolver tests");
    };
    private static final SummaryModel.Factory SUMMARY_MODELS = binding -> {
        throw new AssertionError("summary model is not used by resolver tests");
    };

    /** 未配置宿主 Persona 时，标准 Core Runtime 必须发送空 system prompt，而不是内置行为规则。 */
    @Test
    void missingHostPromptDoesNotInjectCorePersona() {
        TrackingModel model = new TrackingModel();
        StandardAgentRequestRuntimeResolver resolver = resolver(model);
        ExtensionRegistry.Snapshot extensions = new ExtensionRegistry().freeze();
        try (AgentRequestRuntime runtime = resolver.resolve(
                new AgentRuntimeRequest(AgentRuntimeRequest.Kind.USER_INPUT, IDENTITY,
                        Optional.empty(), Map.of()), extensions, Optional.empty())) {
            assertTrue(runtime.promptSession().prepare("", List.of()).snapshot().systemPrompt().isBlank());
        }
    }

    /**
     * 精确注册的 Provider 路由必须优先；未触发的显式解析器与测试附件读取器不得转移所有权。
     */
    @Test
    void exactProviderAndExplicitBudgetAreBoundWithoutTakingModelOwnership() {
        TrackingModel registered = new TrackingModel();
        TrackingModel fallback = new TrackingModel();
        AtomicInteger fallbackResolutions = new AtomicInteger();
        ModelPort.ModelConfiguration defaultModel = configuration("registered", "model-default", "cfg_default", "secret-a");
        ModelPort.ModelConfiguration override = configuration("registered", "model-override", "cfg_override", "secret-b");
        ExtensionRegistry.Snapshot extensions = new ExtensionRegistry()
                .registerModelProvider("registered", registered)
                .registerTool(new TestTool(toolSpec("lookup", "Find a record")))
                .freeze();
        StandardAgentRequestRuntimeResolver resolver = new StandardAgentRequestRuntimeResolver(
                defaultModel, configuration -> {
                    fallbackResolutions.incrementAndGet();
                    return fallback;
                }, BUDGET, SUMMARY_MODELS, ATTACHMENTS, OUTPUT_LIMITS);

        try (AgentRequestRuntime runtime = resolver.resolve(
                new AgentRuntimeRequest(AgentRuntimeRequest.Kind.USER_INPUT, IDENTITY,
                        Optional.of(override), Map.of()), extensions, Optional.empty())) {
            assertSame(registered, runtime.model());
            assertEquals(override, runtime.modelConfiguration());
            assertEquals(0, fallbackResolutions.get());
            assertEquals(64, runtime.bindingFingerprint().length());
            assertSame(io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant.NONE,
                    runtime.transactionParticipant());
            assertSame(ATTACHMENTS, runtime.attachments());
            assertSame(OUTPUT_LIMITS, runtime.outputLimits());
            assertEquals(Map.of(), runtime.modelIdentityAttributes());
            AgentPromptSession.PreparedPrompt prepared = runtime.promptSession().prepare(
                    "", extensions.tools().stream().map(AgentTool::spec).toList());
            assertSame(BUDGET, prepared.budget());
            assertTrue(prepared.snapshot().systemPrompt().isBlank());
            runtime.close();
            runtime.close();
            assertThrows(IllegalStateException.class, runtime.promptSession()::currentRevision);
        }
        assertEquals(0, registered.closeCount.get());
        assertEquals(0, fallback.closeCount.get());
    }

    /** 普通模型与摘要能力必须取自同一次显式注册，不能在压缩时绕过业务供应商。 */
    @Test
    void registeredProviderUsesItsOwnSummaryFactory() {
        TrackingModel model = new TrackingModel();
        AtomicInteger bindings = new AtomicInteger();
        SummaryModel.Factory summaries = binding -> {
            bindings.incrementAndGet();
            throw new IllegalStateException("registered-summary-invoked");
        };
        ModelConfiguration configuration = configuration("registered", "model", "cfg_summary", "secret");
        Snapshot extensions = new ExtensionRegistry().registerModelProvider("registered", model, summaries).freeze();
        StandardAgentRequestRuntimeResolver resolver = new StandardAgentRequestRuntimeResolver(configuration,
                ignored -> { throw new AssertionError("registered model must not use built-in routing"); },
                BUDGET, SUMMARY_MODELS, ATTACHMENTS, OUTPUT_LIMITS);
        try (AgentRequestRuntime runtime = resolver.resolve(new AgentRuntimeRequest(AgentRuntimeRequest.Kind.USER_INPUT,
                IDENTITY, Optional.empty(), Map.of()), extensions, Optional.empty())) {
            assertSame(summaries, runtime.summaryModels());
            TurnBinding binding = new SummaryModel.TurnBinding(IDENTITY.threadId(), configuration,
                    Instant.now().plusSeconds(10), io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken.none());
            assertEquals("registered-summary-invoked", assertThrows(IllegalStateException.class,
                    () -> runtime.summaryModels().bind(binding)).getMessage());
            assertEquals(1, bindings.get());
        }
    }

    /** 缺失摘要能力是可定位的不支持错误，不得静默换成内置 HTTP 供应商。 */
    @Test
    void registeredProviderWithoutSummaryDoesNotUseBuiltInSummary() {
        TrackingModel model = new TrackingModel();
        ModelConfiguration configuration = configuration("registered", "model", "cfg_summary", "secret");
        Snapshot extensions = new ExtensionRegistry().registerModelProvider("registered", model).freeze();
        StandardAgentRequestRuntimeResolver resolver = new StandardAgentRequestRuntimeResolver(configuration, ignored -> model,
                BUDGET, SUMMARY_MODELS, ATTACHMENTS, OUTPUT_LIMITS);
        try (AgentRequestRuntime runtime = resolver.resolve(new AgentRuntimeRequest(AgentRuntimeRequest.Kind.USER_INPUT,
                IDENTITY, Optional.empty(), Map.of()), extensions, Optional.empty())) {
            TurnBinding binding = new SummaryModel.TurnBinding(IDENTITY.threadId(), configuration,
                    Instant.now().plusSeconds(10), io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken.none());
            assertEquals("registered_model_summary_not_supported:registered", assertThrows(IllegalStateException.class,
                    () -> runtime.summaryModels().bind(binding)).getMessage());
        }
    }

    /**
     * 恢复先保留旧正文指纹，下一次准备模型请求再记录新读到的正文。
     */
    @Test
    void restoredSkillKeepsHistoricalFingerprintUntilNewPromptUsesCurrentBody() {
        MutableSkillSource source = new MutableSkillSource("user", "Original guidance.");
        TrackingModel model = new TrackingModel();
        ExtensionRegistry.Snapshot extensions = new ExtensionRegistry().registerSkillSource(source).freeze();
        StandardAgentRequestRuntimeResolver resolver = resolver(model);
        TurnExecutionStateHolder saved = new TurnExecutionStateHolder();

        try (AgentRequestRuntime first = resolver.resolve(
                new AgentRuntimeRequest(AgentRuntimeRequest.Kind.USER_INPUT, IDENTITY,
                        Optional.empty(), Map.of()), extensions, Optional.empty())) {
            AgentPromptSession session = first.promptSession();
            session.validateSkillReferences(java.util.List.of("user:guide"));
            AgentPromptSession.SkillReplacement replacement =
                    session.prepareSkillReplacement(java.util.List.of("user:guide"));
            saved.references = replacement.activeSkillReferences();
            replacement.commit();
            assertEquals(IDENTITY.tenantId(), source.lastTenant.get());
        }

        String historicalFingerprint = saved.references.getFirst().contentFingerprint().orElseThrow();
        source.content.set("Updated guidance for the next request.");
        try (AgentRequestRuntime resumed = resolver.resolve(
                new AgentRuntimeRequest(AgentRuntimeRequest.Kind.USER_INPUT, IDENTITY,
                        Optional.empty(), Map.of()), extensions,
                Optional.of(executionSnapshot(IDENTITY)))) {
            AgentPromptSession session = resumed.promptSession();
            session.restoreActiveSkills("Previous conversation summary.", saved.references);
            assertEquals(historicalFingerprint,
                    session.activeSkillReferences().getFirst().contentFingerprint().orElseThrow());

            source.updateDescriptor("Updated guide version", 2);
            source.content.set("Version two guidance for the next request.");
            AgentPromptSession.PreparedPrompt prepared = session.prepare(
                    "Previous conversation summary.", java.util.List.of());
            String currentFingerprint = session.activeSkillReferences().getFirst().contentFingerprint().orElseThrow();
            assertNotEquals(historicalFingerprint, currentFingerprint);
            assertEquals(2, source.lastReadVersion.get());
            assertTrue(prepared.snapshot().systemPrompt().contains("Version two guidance for the next request."));
            assertFalse(prepared.snapshot().systemPrompt().contains("Updated guidance for the next request."));
            assertFalse(prepared.snapshot().systemPrompt().contains("Original guidance."));
            assertTrue(prepared.snapshot().systemPrompt().contains("Previous conversation summary."));
        }
    }

    /**
     * 已注册来源的发现失败必须中止请求解析，不能退回空目录或本地路径。
     */
    @Test
    void registeredSkillSourceFailureIsSurfaced() {
        SkillSource broken = new SkillSource() {
            /** 返回注册时使用的稳定来源标识。 */
            @Override
            public String id() {
                return "user";
            }

            /** 固定唯一来源的优先级，避免测试受到其它能力顺序影响。 */
            @Override
            public int priority() {
                return 1;
            }

            /** 模拟来源目录故障，并要求 resolver 将失败原样暴露给提交方。 */
            @Override
            public SkillPage discover(SkillRequestContext context, String cursor, int pageSize) {
                throw new IllegalStateException("catalog unavailable");
            }

            /** 发现失败时不应继续读取，此分支用于捕获静默退化为空目录的错误行为。 */
            @Override
            public SkillResourceContent read(
                    SkillRequestContext context, SkillDescriptor descriptor, SkillReadRequest request) {
                throw new AssertionError("discovery failure must stop before reading");
            }
        };
        ExtensionRegistry.Snapshot extensions = new ExtensionRegistry().registerSkillSource(broken).freeze();

        assertThrows(IllegalStateException.class, () -> resolver(new TrackingModel()).resolve(
                new AgentRuntimeRequest(AgentRuntimeRequest.Kind.USER_INPUT, IDENTITY,
                        Optional.empty(), Map.of()), extensions, Optional.empty()));
    }

    /**
     * 恢复调用不得借同名会话或执行身份跨租户读取别人的游标。
     */
    @Test
    void recoveredTurnMustMatchTenantThreadAndTurnIdentity() {
        AgentIdentity other = new AgentIdentity("tenant-b", IDENTITY.threadId(),
                IDENTITY.turnId(), IDENTITY.userId());

        assertThrows(IllegalArgumentException.class, () -> resolver(new TrackingModel()).resolve(
                new AgentRuntimeRequest(AgentRuntimeRequest.Kind.USER_INPUT, other,
                        Optional.empty(), Map.of()),
                new ExtensionRegistry().freeze(), Optional.of(executionSnapshot(IDENTITY))));
    }

    /**
     * 指纹覆盖模型代际和工具描述，但不把 API Key 本身纳入可持久摘要。
     */
    @Test
    void bindingFingerprintTracksPublicModelAndToolIdentityButExcludesApiKey() {
        TrackingModel model = new TrackingModel();
        ModelPort.ModelConfiguration base = configuration("provider", "model-a", "cfg_same", "secret-one");
        ModelPort.ModelConfiguration rotatedCredential = configuration(
                "provider", "model-a", "cfg_same", "secret-two");
        ModelPort.ModelConfiguration changedGeneration = configuration(
                "provider", "model-a", "cfg_new", "secret-two");

        String first = fingerprint(model, base, "Search entries");
        assertEquals(first, fingerprint(model, rotatedCredential, "Search entries"));
        assertNotEquals(first, fingerprint(model, changedGeneration, "Search entries"));
        assertNotEquals(first, fingerprint(model, base, "Search archived entries"));
    }

    /**
     * 目录元数据更新应改变下一次 Prompt 修订，但不能把尚未冻结的执行身份变成永久恢复冲突。
     */
    @Test
    void changingSkillCatalogRefreshesPromptWithoutChangingStableRuntimeBinding() {
        MutableSkillSource source = new MutableSkillSource("user", "Original guidance.");
        ExtensionRegistry.Snapshot extensions = new ExtensionRegistry().registerSkillSource(source).freeze();
        StandardAgentRequestRuntimeResolver resolver = resolver(new TrackingModel());

        try (AgentRequestRuntime runtime = resolver.resolve(
                new AgentRuntimeRequest(AgentRuntimeRequest.Kind.USER_INPUT, IDENTITY,
                        Optional.empty(), Map.of()), extensions, Optional.empty())) {
            String binding = runtime.bindingFingerprint();
            String firstPromptRevision =
                    runtime.promptSession().prepare("", java.util.List.of()).snapshot().revision();

            source.updateDescriptor("Updated metadata for current Skill", 2);
            String currentPromptRevision =
                    runtime.promptSession().prepare("", java.util.List.of()).snapshot().revision();
            assertEquals(binding, runtime.bindingFingerprint());
            assertNotEquals(firstPromptRevision, currentPromptRevision);
        }
    }

    /**
     * 创建使用同一租户身份的 resolver，并为测试显式绑定不读取附件的宿主端口。
     */
    private static StandardAgentRequestRuntimeResolver resolver(TrackingModel model) {
        return new StandardAgentRequestRuntimeResolver(
                configuration("provider", "model-a", "cfg_current", "secret"),
                ignored -> model,
                BUDGET,
                SUMMARY_MODELS,
                ATTACHMENTS,
                OUTPUT_LIMITS);
    }

    /**
     * 通过完整六项装配计算真实绑定摘要，避免测试复制规范化规则或依赖隐式默认能力。
     */
    private static String fingerprint(TrackingModel model, ModelPort.ModelConfiguration configuration,
                                      String description) {
        ExtensionRegistry.Snapshot extensions = new ExtensionRegistry()
                .registerTool(new TestTool(toolSpec("search", description)))
                .freeze();
        try (AgentRequestRuntime runtime = new StandardAgentRequestRuntimeResolver(
                configuration, ignored -> model, BUDGET, SUMMARY_MODELS, ATTACHMENTS, OUTPUT_LIMITS).resolve(
                        new AgentRuntimeRequest(AgentRuntimeRequest.Kind.USER_INPUT, IDENTITY,
                                Optional.empty(), Map.of()),
                        extensions, Optional.empty())) {
            return runtime.bindingFingerprint();
        }
    }

    /**
     * 使用测试配置构造器固定模型 API、凭据通道与超时边界。
     */
    private static ModelPort.ModelConfiguration configuration(
            String providerId, String modelId, String generation, String apiKey) {
        return new ModelPort.ModelConfiguration(
                providerId, modelId, generation, ModelPort.Api.OPENAI_RESPONSES, "test-model",
                URI.create("https://model.example/v1"), apiKey, Duration.ofSeconds(5), Duration.ofSeconds(30),
                Set.of(ModelPort.InputModality.TEXT), ModelPort.GenerationOptions.defaults());
    }

    /**
     * 构造只有一个稳定字符串约束的工具 Schema，便于比较目录身份变化。
     */
    private static ToolSpec toolSpec(String name, String description) {
        return new ToolSpec(name, description, new JsonObject(Map.of("type", new JsonText("object"))));
    }

    /**
     * 创建与请求身份完全一致的可恢复执行快照。
     */
    private static AgentStore.ExecutionSnapshot executionSnapshot(AgentIdentity identity) {
        AgentStore.ConversationKey conversation =
                new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), identity.userId());
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(conversation, identity.turnId());
        return new AgentStore.ExecutionSnapshot(execution, 1, "RUNNING", 1, 1,
                io.github.kongweiguang.ja.agent.domain.json.JsonObject.empty(), 0, Instant.EPOCH, Instant.EPOCH);
    }

    /**
     * 只允许测试观察端口关闭次数；标准 resolver 不应把注入模型端口当作请求资源。
     */
    private static final class TrackingModel implements ModelPort, AutoCloseable {
        private final AtomicInteger closeCount = new AtomicInteger();

        /**
         * 防止测试误发真实 Provider 请求，保持断言只覆盖 runtime 绑定逻辑。
         */
        @Override
        public java.util.concurrent.CompletionStage<ModelOutcome> start(
                ModelRequest request, ModelEventSink eventSink,
                io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken cancellationToken) {
            return CompletableFuture.failedFuture(new AssertionError("model dispatch is outside this test"));
        }

        /** Runtime 解析测试不构造 Provider 信封；若意外触发估算，说明执行职责泄漏进了解析阶段。 */
        @Override
        public InputTokenEstimate estimateInputTokens(
                ModelRequest request, io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken cancellationToken) {
            throw new AssertionError("model token estimation is outside this test");
        }

        /**
         * 记录是否有租约越权关闭注入模型。
         */
        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }

    /**
     * 提供一个可变版本与正文的租户来源以模拟恢复期间 PG Skill 发布新版本。
     */
    private static final class MutableSkillSource implements SkillSource {
        private final String id;
        private final AtomicReference<String> content;
        private final AtomicReference<String> lastTenant = new AtomicReference<>();
        private final AtomicReference<SkillDescriptor> descriptor;
        private final AtomicInteger lastReadVersion = new AtomicInteger();

        /**
         * 保存测试所需的固定 Skill 身份与当前来源正文。
         */
        private MutableSkillSource(String id, String content) {
            this.id = id;
            this.content = new AtomicReference<>(content);
            this.descriptor = new AtomicReference<>(new SkillDescriptor("guide", "Guide for the test", id, 1));
        }

        /** 更新发现元数据以模拟 PG 目录新版本，不改变已注册来源的执行绑定身份。 */
        private void updateDescriptor(String description, long version) {
            descriptor.set(new SkillDescriptor("guide", description, id, version));
        }

        /**
         * 返回来源身份供 catalog 及持久引用共同绑定。
         */
        @Override
        public String id() {
            return id;
        }

        /**
         * 使用唯一来源时无需覆盖其他来源。
         */
        @Override
        public int priority() {
            return 1;
        }

        /**
         * 返回当前版本元数据，使重新准备请求能看见已发布的新版本。
         */
        @Override
        public SkillPage discover(SkillRequestContext context, String cursor, int pageSize) {
            lastTenant.set(context.tenantId());
            return new SkillPage(java.util.List.of(descriptor.get()), null);
        }

        /**
         * 在每次读取时返回最新正文，验证新模型请求会读取当前来源内容。
         */
        @Override
        public SkillResourceContent read(
                SkillRequestContext context, SkillDescriptor descriptor, SkillReadRequest request) {
            lastTenant.set(context.tenantId());
            lastReadVersion.set(Math.toIntExact(descriptor.version()));
            return new SkillResourceContent(content.get(), false);
        }
    }

    /**
     * 用最小 Tool 实现暴露可变描述，验证工具绑定确实参与 runtime 指纹。
     *
     * @param spec 配置规格
     */
    private record TestTool(ToolSpec spec) implements AgentTool {
        /**
         * 本测试只检查注册元数据，不允许意外跨越外部副作用边界。
         */
        @Override
        public java.util.concurrent.CompletionStage<AgentTool.ExecutionResult> execute(
                Invocation invocation, ExecutionContext context,
                io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken cancellationToken) {
            return CompletableFuture.completedFuture(
                    AgentTool.ExecutionResult.of(AgentTool.ToolResult.success("test tool result")));
        }
    }

    /**
     * 保留输入消费前持久化的 Skill 引用，模拟之后恢复的执行游标。
     */
    private static final class TurnExecutionStateHolder {
        private java.util.List<io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState.ActiveSkill> references =
                java.util.List.of();
    }
}
