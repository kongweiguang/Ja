// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.domain.error.OutputSchemaException;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.api.TurnEventSink;
import io.github.kongweiguang.ja.agent.application.concurrent.CancellationSource;
import io.github.kongweiguang.ja.agent.application.context.summary.ModelSummaryGenerator;
import io.github.kongweiguang.ja.agent.application.engine.AgentRequestBinding;
import io.github.kongweiguang.ja.agent.application.engine.AgentRequestBindingCodec;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.ToolArgumentValidator;
import io.github.kongweiguang.ja.agent.application.gateway.ModelSelection;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentRecordIds;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.context.CheckpointUsage;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.infrastructure.json.JsonValueCodecFactory;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ControlPayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ExtensionFactRecordCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.JacksonExecutionEventCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.MiddlewareStateRecordCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.TurnExecutionStateCodec;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 核验首次 Engine Skill 恢复后，ModelStep 仍把选中的 checkpoint 摘要与活动 Skill 冻结进唯一请求。 */
final class AgentModelStepSkillRestoreTest {
    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");
    private static final AgentIdentity IDENTITY = new AgentIdentity(
            "tenant_skill", "thread_skill", "turn_skill", "user_skill");

    /** 初次恢复由 Engine 边界夹具模拟；ModelStep 不得再次恢复，并须派发携带同一摘要与 Skill 的冻结请求。 */
    @Test
    void preservesRestoredSkillsAndCheckpointSummaryForFirstModelDispatch() {
        Fixture fixture = fixture();
        String initialSummary = "";

        fixture.prompt().restoreActiveSkills(initialSummary, fixture.activeSkills());
        fixture.modelStep().advance();

        ModelPort.ModelRequest estimated = fixture.estimatedRequest().get();
        assertNotNull(estimated);
        assertEquals(1, fixture.restoreCalls().get());
        assertEquals(1, fixture.dispatchCalls().get());
        assertTrue(fixture.preparedSummaries().contains(fixture.checkpointSummary().toPromptText()));
        assertTrue(estimated.prompt().systemPrompt().contains("skill-marker"));
        assertTrue(estimated.prompt().systemPrompt().contains(fixture.checkpointSummary().toPromptText()));
        assertTrue(fixture.dispatchedRequest().get().prompt().systemPrompt().contains("skill-marker"));
        assertEquals(estimated, fixture.dispatchedRequest().get());
        assertEquals(TurnState.FAILED, fixture.terminalState().get());
    }

    /** Retry 先提交 UNKNOWN 调用所占的 ordinal；暂停取消只能打断退避，不能再次派发同一副作用请求。 */
    @Test
    void cancellationDuringProviderRetryBackoffDoesNotRedispatchAndKeepsAdvancedCursor() throws Exception {
        CancellationSource cancellation = new CancellationSource();
        CountDownLatch retryBackoffEntered = new CountDownLatch(1);
        CancellationToken observedCancellation = new CancellationToken() {
                    /** 取消状态由真实 source 持有，包装器只增加退避注册的确定性观察点。 */
                    @Override
                    public boolean isCancellationRequested() {
                        return cancellation.isCancellationRequested();
                    }

                    /** 返回 source 的脱敏取消原因，不在测试包装层另建状态。 */
                    @Override
                    public Optional<String> reason() {
                        return cancellation.reason();
                    }

                    /** 在退避等待注册回调后通知测试线程，再委托原子注册以覆盖并发取消。 */
                    @Override
                    public CancellationToken.Registration onCancellation(Runnable callback) {
                        retryBackoffEntered.countDown();
                        return cancellation.onCancellation(callback);
                    }
                };
        AtomicReference<TurnExecutionState> committedCursor = new AtomicReference<>();
        Fixture fixture = fixture(Optional.empty(), false, false, false, observedCancellation,
                committedCursor, true, Duration.ofSeconds(30));
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                fixture.modelStep().advance();
            } catch (RuntimeException cancelled) {
                failure.set(cancelled);
            }
        });

        try {
            assertTrue(retryBackoffEntered.await(5, TimeUnit.SECONDS));
        } finally {
            cancellation.cancel("plan_pause");
            worker.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertFalse(worker.isAlive());
        assertInstanceOf(CancellationException.class, failure.get());
        assertEquals(1, fixture.dispatchCalls().get());
        TurnExecutionState.Ready ready = assertInstanceOf(TurnExecutionState.Ready.class, committedCursor.get());
        assertEquals(2, ready.common().nextProviderOrdinal());
        assertNull(fixture.terminalState().get());
    }

    /** 结构化输出能力在 Provider start 前核对，避免向不支持的模型发出请求。 */
    @Test
    void rejectsUnsupportedOutputSchemaBeforeProviderDispatch() {
        Fixture fixture = fixture(Optional.of(outputSchema("object")), false, false);

        OutputSchemaException failure = assertThrows(
                OutputSchemaException.class, () -> fixture.modelStep().advance());

        assertEquals("OUTPUT_SCHEMA_UNSUPPORTED", failure.code());
        assertEquals(0, fixture.dispatchCalls().get());
    }

    /** 根类型排除 object 时在 Provider 派发前返回稳定 Schema 错误。 */
    @Test
    void rejectsNonObjectOutputSchemaBeforeProviderDispatch() {
        Fixture fixture = fixture(Optional.of(outputSchema("array")), true, false);

        OutputSchemaException failure = assertThrows(
                OutputSchemaException.class, () -> fixture.modelStep().advance());

        assertEquals("OUTPUT_SCHEMA_INVALID", failure.code());
        assertEquals(0, fixture.dispatchCalls().get());
    }

    /** 无法编译的原 Schema 在模型派发前失败，避免 Provider 接收含糊的格式约束。 */
    @Test
    void rejectsMalformedOutputSchemaBeforeProviderDispatch() {
        Fixture fixture = fixture(Optional.of(outputSchema("object")), true, false, true);

        OutputSchemaException failure = assertThrows(
                OutputSchemaException.class, () -> fixture.modelStep().advance());

        assertEquals("OUTPUT_SCHEMA_INVALID", failure.code());
        assertEquals(0, fixture.dispatchCalls().get());
    }

    /** Provider 声明不等于验证成功；不符合原 Schema 的 JSON 终答必须失败收口。 */
    @Test
    void terminatesInvalidFinalAnswerWithStableCode() {
        Fixture fixture = fixture(Optional.of(outputSchema("object")), true, true);

        fixture.modelStep().advance();

        assertEquals(1, fixture.dispatchCalls().get());
        assertEquals(TurnState.FAILED, fixture.terminalState().get());
        assertEquals("OUTPUT_SCHEMA_VALIDATION_FAILED", fixture.terminalCode().get());
        assertTrue(fixture.terminalMessage().get().contains("Final response did not satisfy"));
    }

    /** 组合真实 Context、Mapper 与 ModelStep；Prompt 代理仅验证冻结结果，提交通知沿接口默认语义保持无副作用。 */
    private static Fixture fixture() {
        return fixture(Optional.empty(), false, false, false);
    }

    /** 固定 object 型 JSON Schema，避免测试依赖序列化文本文法。 */
    private static JsonObject outputSchema(String rootType) {
        return new JsonObject(Map.of("type", new JsonText(rootType)));
    }

    /** 组合真实 Context、Mapper 与 ModelStep，并只替换 Schema 准入及 Provider 终答条件。 */
    private static Fixture fixture(Optional<JsonObject> outputSchema, boolean supportsOutputSchema,
                                  boolean invalidFinalAnswer) {
        return fixture(outputSchema, supportsOutputSchema, invalidFinalAnswer, false);
    }

    /**
     * 在相同执行装配中注入可控的 Schema 编译失败；流序号单调递增，避免协议错误掩盖终答校验。
     */
    private static Fixture fixture(Optional<JsonObject> outputSchema, boolean supportsOutputSchema,
                                  boolean invalidFinalAnswer, boolean malformedSchema) {
        return fixture(outputSchema, supportsOutputSchema, invalidFinalAnswer, malformedSchema,
                CancellationToken.none(), new AtomicReference<>(), false, Duration.ZERO);
    }

    /** 将取消令牌、游标提交观察和可控 retry-after 注入同一 fixture，精确隔离退避竞态。 */
    private static Fixture fixture(Optional<JsonObject> outputSchema, boolean supportsOutputSchema,
                                  boolean invalidFinalAnswer, boolean malformedSchema,
                                  CancellationToken cancellation,
                                  AtomicReference<TurnExecutionState> committedCursor,
                                  boolean retryableProviderFailure, Duration retryAfterHint) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AgentStore store = (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("toString")) {
                        return "skill-restore-test-store";
                    }
                    throw new AssertionError("unexpected Store call: " + method.getName());
                });
        ObjectMapper objectMapper = new ObjectMapper();
        StorePayloadCodec records = new StorePayloadCodec(objectMapper);
        JsonValueCodec json = JsonValueCodecFactory.standard();
        AgentContextMapper mapper = new AgentContextMapper(json, records);
        String bindingFingerprint = "b".repeat(64);
        ModelPort.ModelConfiguration configuration = new ModelPort.ModelConfiguration(
                "provider_skill", "model_skill", "cfg_generation_skill", ModelPort.Api.OPENAI_RESPONSES,
                "upstream_skill", URI.create("https://model.example/v1"), "test-key",
                Duration.ofSeconds(5), Duration.ofSeconds(30), Set.of(ModelPort.InputModality.TEXT),
                ModelPort.GenerationOptions.defaults());
        AtomicReference<ModelPort.ModelRequest> estimatedRequest = new AtomicReference<>();
        AtomicReference<ModelPort.ModelRequest> dispatchedRequest = new AtomicReference<>();
        AtomicInteger dispatchCalls = new AtomicInteger();
        AtomicInteger restoreCalls = new AtomicInteger();
        AtomicReference<TurnState> terminalState = new AtomicReference<>();
        AtomicReference<String> terminalCode = new AtomicReference<>();
        AtomicReference<String> terminalMessage = new AtomicReference<>();
        List<String> preparedSummaries = new ArrayList<>();
        List<TurnExecutionState.ActiveSkill> activeSkills = List.of(new TurnExecutionState.ActiveSkill(
                "skill.restore-test:restore-test", Optional.of("sha256:" + "a".repeat(64))));
        AgentPromptSession prompt = (AgentPromptSession) Proxy.newProxyInstance(
                AgentPromptSession.class.getClassLoader(), new Class<?>[]{AgentPromptSession.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "activeSkillReferences" -> activeSkills;
                    case "currentRevision" -> "prompt_initial";
                    case "restoreActiveSkills" -> {
                        if (restoreCalls.incrementAndGet() != 1) {
                            throw new IllegalStateException("Prompt Skills were restored more than once");
                        }
                        yield null;
                    }
                    case "requestCommitted" -> null;
                    case "prepare" -> {
                        String summary = (String) arguments[0];
                        preparedSummaries.add(summary);
                        String system = "skill-marker\n" + summary;
                        yield new AgentPromptSession.PreparedPrompt(
                                new io.github.kongweiguang.ja.agent.domain.prompt.AgentPromptSnapshot(
                                        system, "prompt_skill_restore", system.length()),
                                ContextBudget.capabilities(32_768, 2_048, true));
                    }
                    case "toString" -> "strict-skill-prompt-session";
                    default -> throw new AssertionError("unexpected Prompt call: " + method.getName());
                });
        ModelPort model = new ModelPort() {
            /** 测量真实 mapper 生成的 envelope，并将同一对象留给派发断言。 */
            @Override
            public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellationToken) {
                estimatedRequest.set(request);
                return new InputTokenEstimate(12, 16, "c".repeat(64));
            }

            /** 测试以冻结模型能力声明验证请求前的结构化输出门禁。 */
            @Override
            public boolean supportsOutputSchema(ModelConfiguration ignored) {
                return supportsOutputSchema;
            }

            /** 捕获冻结请求；除既有 Schema 场景外，按 fixture 选择瞬时故障或确定性失败。 */
            @Override
            public CompletableFuture<ModelOutcome> start(
                    ModelRequest request, io.github.kongweiguang.ja.agent.application.gateway.ModelEventSink sink,
                    CancellationToken cancellationToken) {
                dispatchedRequest.set(request);
                dispatchCalls.incrementAndGet();
                if (invalidFinalAnswer) {
                    sink.onEvent(new ModelPort.TextDelta("{}")).toCompletableFuture().join();
                    return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.STOP, null, null));
                }
                if (retryableProviderFailure) {
                    return CompletableFuture.failedFuture(new ModelPort.ModelUnavailableException(
                            "test transient failure", null) {
                        /** 固定退避窗口，让回归测试在 Provider 重派发前取消。 */
                        @Override
                        public Optional<Duration> retryAfterHint() {
                            return Optional.of(retryAfterHint);
                        }
                    });
                }
                return CompletableFuture.failedFuture(new IllegalStateException("sensitive-provider-failure"));
            }
        };
        AgentRequestRuntime runtime = new AgentRequestRuntime(configuration, model,
                ignored -> { throw new AssertionError("summary model must not be invoked"); },
                new ExtensionRegistry().freeze(), prompt, bindingFingerprint,
                AgentStoreTransactionParticipant.NONE,
                (ManagedAttachmentReader) request -> { throw new AssertionError("attachments are not in the fixture"); },
                new ToolProjectionLimits(64, 64), Map.of(), () -> { });

        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(
                new AgentStore.ConversationKey(IDENTITY.tenantId(), IDENTITY.threadId(), IDENTITY.userId()),
                IDENTITY.turnId());
        AgentStore.ConversationSnapshot conversation = new AgentStore.ConversationSnapshot(
                execution.conversation(), 3, 0, 1, NOW, NOW, 3);
        AgentStore.ExecutionSnapshot executionSnapshot = new AgentStore.ExecutionSnapshot(
                execution, 1, TurnState.RUNNING.name(), 0, TurnExecutionState.SCHEMA_VERSION,
                JsonObject.empty(), 3, NOW, NOW);
        SummaryDocument checkpointSummary = new SummaryDocument(
                List.of(new SummaryDocument.Fact("checkpoint-summary-marker", 1)), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        CheckpointStore.ContextCheckpoint checkpoint = new CheckpointStore.ContextCheckpoint(
                "checkpoint_skill", IDENTITY.threadId(), 1, 2, conversation.revision(), Optional.empty(),
                checkpointSummary, 12, "d".repeat(64), "test-strategy", CheckpointUsage.none(), NOW);
        AgentExecutionRequest.HistorySelection historySelection =
                AgentExecutionRequest.HistorySelection.currentConversation();
        AgentRequestBinding binding = new AgentRequestBinding(IDENTITY,
                AgentExecutionRequest.AdmissionKind.USER_INPUT, Map.of(), historySelection, NOW,
                ModelSelection.from(configuration), bindingFingerprint, "initial_inputs_skill", Optional.empty());
        AgentStore.RecordWrite checkpointRecord = records.encodeCheckpoint(
                AgentRecordIds.derive(execution, ExecutionRecordCodec.CONTEXT_CHECKPOINT_RECORD_TYPE,
                        checkpoint.checkpointId()), checkpoint, NOW);
        AgentStore.RecordWrite bindingRecord = new AgentRequestBindingCodec().encode(binding);
        AgentStore.RecordWrite userRecord = records.encodeUser(
                AgentRecordIds.derive(execution, "agent.user.message", "message_skill"), "message_skill",
                new io.github.kongweiguang.ja.agent.domain.UserContent(List.of(new TextContent("first request"))), NOW);
        List<AgentStore.ConversationRecord> historyRecords = List.of(
                conversationRecord(execution, checkpointRecord, 1),
                conversationRecord(execution, bindingRecord, 2),
                conversationRecord(execution, userRecord, 3));
        AgentLoopPersistence.Restored restored = new AgentLoopPersistence.Restored(
                conversation, executionSnapshot, List.of(executionSnapshot), historyRecords);
        TurnExecutionState.Ready cursor = new TurnExecutionState.Ready(
                new TurnExecutionState.Common(0, 0, 1, "prompt_initial", activeSkills),
                TurnExecutionState.Next.ASSISTANT, null);
        AgentExecutionDriver.Services services = new AgentExecutionDriver.Services(store,
                new AgentLoopPersistence(store, clock), records, new TurnExecutionStateCodec(objectMapper),
                new JacksonExecutionEventCodec(objectMapper), mapper,
                (ToolArgumentValidator) (schema, arguments) -> {
                    if (malformedSchema) {
                        throw new IllegalArgumentException("schema fixture rejected");
                    }
                    return invalidFinalAnswer
                            ? Optional.of("the declared schema constraint is not satisfied.") : Optional.empty();
                },
                new ControlPayloadCodec(objectMapper), json, new ExtensionFactRecordCodec(),
                new MiddlewareStateRecordCodec(json), clock, Duration.ofSeconds(30));
        CheckpointStore checkpoints = (CheckpointStore) Proxy.newProxyInstance(
                CheckpointStore.class.getClassLoader(), new Class<?>[]{CheckpointStore.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "read" -> new CheckpointStore.Snapshot(IDENTITY.threadId(), conversation.revision(),
                            Optional.of(checkpoint));
                    case "prepareProjection" -> {
                        CheckpointStore.ProjectionRequest request = (CheckpointStore.ProjectionRequest) arguments[0];
                        yield new CheckpointStore.ProjectionSnapshot("projection_skill", request.threadId(), 1,
                                request.sourceRevision(), CheckpointStore.ProjectionReason.INITIAL,
                                request.binding(), Map.of());
                    }
                    case "persistProjection" -> ((CheckpointStore.ProjectionSnapshot) arguments[0])
                            .withSelections((Map<String, io.github.kongweiguang.ja.agent.domain.context.ToolProjection>) arguments[1]);
                    case "toString" -> "skill-restore-test-checkpoints";
                    default -> throw new AssertionError("unexpected CheckpointStore call: " + method.getName());
                });
        ExecutionStateView view = new ExecutionStateView(restored, services, runtime.extensions().middleware());
        ModelSummaryGenerator.SummaryOperation summaryOperation =
                (ModelSummaryGenerator.SummaryOperation) Proxy.newProxyInstance(
                        ModelSummaryGenerator.SummaryOperation.class.getClassLoader(),
                        new Class<?>[]{ModelSummaryGenerator.SummaryOperation.class},
                        (proxy, method, arguments) -> {
                            throw new AssertionError("summary operation must not be invoked: " + method.getName());
                        });
        AtomicInteger commitRevision = new AtomicInteger(3);
        AtomicInteger commitMutation = new AtomicInteger();
        LongAdder ids = new LongAdder();
        AgentModelStep.Host host = (AgentModelStep.Host) Proxy.newProxyInstance(
                AgentModelStep.Host.class.getClassLoader(), new Class<?>[]{AgentModelStep.Host.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "identity" -> IDENTITY;
                    case "services" -> services;
                    case "cursor" -> cursor;
                    case "restored" -> restored;
                    case "commit" -> {
                        committedCursor.set((TurnExecutionState) arguments[1]);
                        yield new AgentStore.CommitReceipt(commitRevision.incrementAndGet(),
                                commitMutation.incrementAndGet(), 0, 0, JsonObject.empty(), true);
                    }
                    case "assertDispatchAuthority", "appendMiddleware", "beginCompaction" -> null;
                    case "runtime" -> runtime;
                    case "view" -> view;
                    case "cancellation" -> cancellation;
                    case "stream" -> TurnEventSink.noop();
                    case "streamSequence" -> (java.util.function.LongSupplier) () -> {
                        ids.increment();
                        return ids.longValue();
                    };
                    case "checkpoints" -> checkpoints;
                    case "summaryOperation" -> summaryOperation;
                    case "historySelection" -> historySelection;
                    case "outputSchema" -> outputSchema;
                    case "fact" -> records.encode("fact_skill", (io.github.kongweiguang.ja.agent.domain.execution.ExecutionFact) arguments[0], NOW);
                    case "id" -> arguments[0] + "_" + arguments[1] + "_test";
                    case "hash" -> io.github.kongweiguang.ja.agent.shared.Hashing.sha256Hex((String) arguments[0]);
                    case "terminate" -> {
                        terminalState.set((TurnState) arguments[0]);
                        terminalCode.set((String) arguments[1]);
                        terminalMessage.set((String) arguments[2]);
                        yield null;
                    }
                    case "toString" -> "skill-restore-test-host";
                    default -> throw new AssertionError("unexpected ModelStep host call: " + method.getName());
                });

        return new Fixture(new AgentModelStep(host), prompt, activeSkills, checkpointSummary,
                restoreCalls, dispatchCalls, estimatedRequest, dispatchedRequest, preparedSummaries,
                terminalState, terminalCode, terminalMessage);
    }

    /** 将编解码写入放回其真实执行与 Thread 身份，保留历史排序及 decoder 所需的版本水位。 */
    private static AgentStore.ConversationRecord conversationRecord(
            AgentStore.ExecutionKey execution, AgentStore.RecordWrite write, long sequence) {
        return new AgentStore.ConversationRecord(execution.conversation(), execution, write.recordId(),
                write.recordType(), write.payloadSchemaVersion(), sequence, write.payload(), write.occurredAt());
    }

    /**
     * 汇总首轮派发回归所需的组件及请求、恢复与终态观察值。
     * @param modelStep 负责推进首轮模型请求的 ModelStep
     * @param prompt 接收恢复结果并准备提示词的 Prompt 会话
     * @param activeSkills 首轮模型请求必须携带的活动 Skill 引用
     * @param checkpointSummary 首轮提示词需保留的检查点摘要
     * @param restoreCalls Prompt 会话恢复方法的调用计数
     * @param dispatchCalls 模型请求开始派发的调用计数
     * @param estimatedRequest 输入 Token 估算阶段观察到的模型请求
     * @param dispatchedRequest 实际派发到 Provider 的模型请求
     * @param preparedSummaries Prompt 准备阶段接收的摘要文本
     * @param terminalState 派发后记录的会话终态
     * @param terminalCode Session 收到的终态错误码
     * @param terminalMessage Session 收到的脱敏终态说明
     */
    private record Fixture(AgentModelStep modelStep,
                           AgentPromptSession prompt,
                           List<TurnExecutionState.ActiveSkill> activeSkills,
                           SummaryDocument checkpointSummary,
                           AtomicInteger restoreCalls,
                           AtomicInteger dispatchCalls,
                           AtomicReference<ModelPort.ModelRequest> estimatedRequest,
                           AtomicReference<ModelPort.ModelRequest> dispatchedRequest,
                           List<String> preparedSummaries,
                           AtomicReference<TurnState> terminalState,
                           AtomicReference<String> terminalCode,
                           AtomicReference<String> terminalMessage) {
    }
}
