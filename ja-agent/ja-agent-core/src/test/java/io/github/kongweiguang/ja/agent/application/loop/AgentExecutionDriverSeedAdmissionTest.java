// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.domain.conversation.ContextInstruction;
import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.ModelEventSink;
import io.github.kongweiguang.ja.agent.application.gateway.ToolArgumentValidator;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ExtensionFactRecordCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.InitialContextSeedCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.MiddlewareStateRecordCodec;
import io.github.kongweiguang.ja.agent.application.engine.AgentInitialQueuedInputsCodec;
import io.github.kongweiguang.ja.agent.infrastructure.json.JsonValueCodecFactory;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ControlPayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.JacksonExecutionEventCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.TurnExecutionStateCodec;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;

/** 核验冻结父上下文与用户消息在同一准入事实集内提交，并且元数据修订不等同于已有对话。 */
final class AgentExecutionDriverSeedAdmissionTest {
    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");
    private static final AgentStore.ConversationKey CHILD = new AgentStore.ConversationKey(
            "tenant_seed", "thread_child", "user_seed");

    /** 元数据已修订但仍无执行或记录时允许导入；Seed、首条用户消息和绑定由同一 Store admission 承载。 */
    @Test
    void admitsSeedWithUserAgainstMetadataOnlyRevisionAsOneAtomicRequest() {
        Fixture fixture = fixture(new AgentStore.ConversationSnapshot(CHILD, 7, 0, 0, NOW, NOW, 0));
        AgentExecutionRequest request = request("turn_child_one", "operation_seed_one", seed());

        AgentStore.Admission admission = fixture.driver.prepareAdmission(request, runtime(), binding(request.identity()), 7);

        assertEquals(7, admission.expectedThreadRevision());
        assertEquals(List.of(InitialContextSeedCodec.RECORD_TYPE, "agent.user.message",
                        AgentInitialQueuedInputsCodec.RECORD_TYPE, "agent.request.binding"),
                admission.records().stream().map(AgentStore.RecordWrite::recordType).toList());
        assertEquals("agent.execution.admitted", admission.events().getFirst().eventType());
        assertEquals(seed(), fixture.records.decodeInitialContextSeed(stored(admission.records().getFirst())));

        AgentStore.AdmissionReceipt receipt = fixture.persistence.admit(admission,
                io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant.NONE);

        assertEquals(1, fixture.admissionCalls.get());
        assertEquals(admission, fixture.admitted.get());
        assertEquals(8, receipt.threadRevision());
    }

    /** 已产生 execution/record 的 conversation 不得接收第二份背景 Seed，即使调用方使用新 execution ID。 */
    @Test
    void rejectsSecondSeedAfterFirstAdmissionCreatedExecutionFacts() {
        Fixture fixture = fixture(new AgentStore.ConversationSnapshot(CHILD, 7, 0, 0, NOW, NOW, 0));
        AgentExecutionRequest first = request("turn_child_one", "operation_seed_one", seed());
        AgentStore.Admission admission = fixture.driver.prepareAdmission(first, runtime(), binding(first.identity()), 7);
        fixture.persistence.admit(admission,
                io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant.NONE);

        AgentExecutionRequest second = request("turn_child_two", "operation_seed_two", seed());

        assertThrows(AgentStore.StoreConflictException.class,
                () -> fixture.driver.prepareAdmission(second, runtime(), binding(second.identity()), 8));
        assertEquals(1, fixture.admissionCalls.get());
    }

    /** Seed 的来源租户必须与新 conversation 相同，不能借同一进程内的跨租户对象绕开隔离。 */
    @Test
    void rejectsSeedFromAnotherTenantBeforeAdmission() {
        InitialContextSeed foreign = new InitialContextSeed(
                new InitialContextSeed.SourceIdentity("tenant_other", "thread_parent", "turn_parent"),
                1, List.of(), Optional.of(summary(1)));
        assertThrows(IllegalArgumentException.class,
                () -> request("turn_child_one", "operation_seed_one", foreign));
    }

    /** 内部指令与 MESSAGE 同事务准入，并明确不携带输出 Schema 或父种子，隔离本用例的原子性边界。 */
    @Test
    void admitsInternalMessageBatchBesideInstructionInOneTransaction() {
        Fixture fixture = fixture(new AgentStore.ConversationSnapshot(CHILD, 7, 0, 0, NOW, NOW, 0));
        AgentIdentity identity = new AgentIdentity("tenant_seed", "thread_child", "turn_internal", "user_seed");
        List<AgentInputCommand.Enqueue> messages = List.of(
                queuedMessage("mailbox_one", "来源一"), queuedMessage("mailbox_two", "来源二"));
        AgentExecutionRequest request = new AgentExecutionRequest(identity,
                AgentExecutionRequest.AdmissionKind.INTERNAL, Optional.empty(), null,
                Optional.of(new ContextInstruction("instruction_task", "continue the task")),
                new AgentStore.OperationIdentity("operation_internal", "agent.internal", "d".repeat(64), json("{}")),
                Map.of(), AgentExecutionRequest.HistorySelection.currentConversation(), NOW,
                messages, Optional.empty(), Optional.empty());

        AgentStore.Admission admission = fixture.driver.prepareAdmission(request, runtime(), binding(identity), 7);

        assertEquals(List.of("agent.context.instruction", "agent.input.message", "agent.input.queue.command",
                        "agent.input.message", "agent.input.queue.command",
                        AgentInitialQueuedInputsCodec.RECORD_TYPE, "agent.request.binding"),
                admission.records().stream().map(AgentStore.RecordWrite::recordType).toList());
        AgentInitialQueuedInputsCodec initialInputs = new AgentInitialQueuedInputsCodec();
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(CHILD, identity.turnId());
        assertEquals(messages, initialInputs.decode(admission.records().get(admission.records().size() - 2),
                execution, "operation_internal"));
        assertEquals("agent.execution.admitted", admission.events().getFirst().eventType());
        fixture.persistence.admit(admission,
                io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant.NONE);

        assertEquals(1, fixture.admissionCalls.get());
        assertEquals(admission, fixture.admitted.get());
    }

    /** 构造只含事实的摘要 Seed，证明父消息水位与摘要 provenance 不必相同。 */
    private static InitialContextSeed seed() {
        return new InitialContextSeed(new InitialContextSeed.SourceIdentity(
                "tenant_seed", "thread_parent", "turn_parent"), 1_005,
                List.of(ContextMessage.text("parent_message", "turn_parent", 1_001,
                        ContextMessage.Role.USER, "frozen parent context", 5)), Optional.of(summary(1_000)));
    }

    /** 用单条父事实避免空摘要触发 seed 的 fail-closed 校验。 */
    private static SummaryDocument summary(long sourceOrdinal) {
        return new SummaryDocument(List.of(new SummaryDocument.Fact("parent fact", sourceOrdinal)), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** 固定 MESSAGE 来源与正文，用于核对队列快照不会被降级成普通用户手输文本。 */
    private static AgentInputCommand.Enqueue queuedMessage(String id, String text) {
        return new AgentInputCommand.Enqueue("operation_" + id, "input_" + id, AgentInputCommand.Kind.MESSAGE,
                new UserContent(List.of(new TextContent(text))),
                Map.of("ja.mailbox.messageId", id, "ja.mailbox.senderThreadId", "thread_parent"));
    }

    /** 以显式父来源构造 Loop 请求，输出 Schema 留空，确保父种子不误绑定到相邻可选参数。 */
    private static AgentExecutionRequest request(String turnId, String operationKey, InitialContextSeed seed) {
        AgentIdentity identity = new AgentIdentity("tenant_seed", "thread_child", turnId, "user_seed");
        return new AgentExecutionRequest(identity, AgentExecutionRequest.AdmissionKind.USER_INPUT,
                Optional.of(new UserContent(List.of(new TextContent("start child")))), "child_user_message",
                Optional.empty(), new AgentStore.OperationIdentity(operationKey, "agent.submit", "a".repeat(64), json("{}")),
                Map.of(), AgentExecutionRequest.HistorySelection.currentConversation(), NOW, List.of(), Optional.empty(), Optional.of(seed));
    }

    /** 使用内存水位替身只隔离 CAS 分支；原子写集合仍由真实 AgentStore contract 验证。 */
    private static Fixture fixture(AgentStore.ConversationSnapshot initialSnapshot) {
        AtomicReference<AgentStore.ConversationSnapshot> snapshot = new AtomicReference<>(initialSnapshot);
        AtomicReference<AgentStore.Admission> admitted = new AtomicReference<>();
        AtomicInteger admissionCalls = new AtomicInteger();
        AgentStore store = (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "loadConversation" -> Optional.ofNullable(snapshot.get());
                    case "admit" -> {
                        AgentStore.Admission admission = (AgentStore.Admission) arguments[0];
                        admitted.set(admission);
                        admissionCalls.incrementAndGet();
                        long nextRevision = admission.expectedThreadRevision() + 1;
                        snapshot.set(new AgentStore.ConversationSnapshot(admission.execution().conversation(),
                                nextRevision, admission.events().size(), 1, NOW, NOW, admission.records().size()));
                        yield new AgentStore.AdmissionReceipt(nextRevision, 1, 0,
                                admission.events().size() - 1L, json("{}"), true);
                    }
                    case "toString" -> "seed-admission-store";
                    default -> throw new AssertionError("unexpected Store call: " + method.getName());
                });
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AgentLoopPersistence persistence = new AgentLoopPersistence(store, clock);
        ObjectMapper mapper = new ObjectMapper();
        StorePayloadCodec records = new StorePayloadCodec(mapper);
        JsonValueCodec json = JsonValueCodecFactory.standard();
        AgentExecutionDriver driver = new AgentExecutionDriver(store, persistence, records, new TurnExecutionStateCodec(mapper),
                new JacksonExecutionEventCodec(mapper), new AgentContextMapper(json, records),
                (ToolArgumentValidator) (schema, arguments) -> Optional.empty(), new ControlPayloadCodec(mapper),
                json, new ExtensionFactRecordCodec(), new MiddlewareStateRecordCodec(json), clock,
                Duration.ofSeconds(30));
        return new Fixture(driver, persistence, records, admitted, admissionCalls);
    }

    /** 准入测试不调用模型或附件，任何越界访问都让测试立即失败。 */
    private static AgentRequestRuntime runtime() {
        AgentPromptSession prompt = (AgentPromptSession) Proxy.newProxyInstance(
                AgentPromptSession.class.getClassLoader(), new Class<?>[]{AgentPromptSession.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "currentRevision" -> "b".repeat(64);
                    case "activeSkillReferences" -> List.of();
                    case "toString" -> "seed-prompt-session";
                    default -> throw new AssertionError("unexpected Prompt call: " + method.getName());
                });
        ModelPort model = new ModelPort() {
            /** 准入阶段不派发模型；出现调用即表示测试跨越了准入边界。 */
            @Override
            public CompletableFuture<ModelOutcome> start(
                    ModelRequest request, ModelEventSink sink, CancellationToken cancellation) {
                return CompletableFuture.failedFuture(
                        new AssertionError("admission must not dispatch a model request"));
            }

            /** 执行未受理前不能估算 Provider 请求，防止准入失败时提前运行模型侧工作。 */
            @Override
            public ModelPort.InputTokenEstimate estimateInputTokens(
                    ModelRequest request, CancellationToken cancellation) {
                throw new AssertionError("admission must not estimate a model request");
            }
        };
        ManagedAttachmentReader attachments = request -> {
            throw new AssertionError("admission must not read attachments");
        };
        return new AgentRequestRuntime(modelConfiguration(), model, binding -> {
            throw new AssertionError("admission must not create a summary model");
        }, new ExtensionRegistry().freeze(), prompt, "c".repeat(64),
                io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant.NONE,
                attachments, new ToolProjectionLimits(64, 16), Map.of(), () -> { });
    }

    /** 测试配置只满足 RequestRuntime 的显式结构，不会建立网络客户端或访问凭据。 */
    private static ModelPort.ModelConfiguration modelConfiguration() {
        return new ModelPort.ModelConfiguration("provider_seed", "model_seed", "cfg_generation_seed",
                ModelPort.Api.OPENAI_RESPONSES, "model-test", URI.create("https://model.example/v1"), "secret_seed",
                Duration.ofSeconds(1), Duration.ofMinutes(1), Set.of(ModelPort.InputModality.TEXT),
                new ModelPort.GenerationOptions(null, null, 32, null));
    }

    /** 用稳定绑定记录完成 Core admission 的必需 schema 约束。 */
    private static AgentStore.RecordWrite binding(AgentIdentity identity) {
        return new AgentStore.RecordWrite("binding_" + identity.turnId(), "agent.request.binding", 1,
                json("{}"), NOW);
    }

    /** 将待写入 Seed 视作固定版本记录解码，验证准入封装的不是临时文本副本。 */
    private static AgentStore.StoredRecord stored(AgentStore.RecordWrite write) {
        return new AgentStore.StoredRecord(write.recordId(), write.recordType(), write.payloadSchemaVersion(),
                1, write.payload(), write.occurredAt());
    }

    /** 构造Core存储类型使用的不可变JSON，避免测试替身重新引入字符串负载契约。 */
    private static JsonObject json(String source) {
        return JsonValueCodecFactory.standard().decodeObject(source);
    }

    /**
     * 保存 Store 写入捕获点和共用 codec，使断言针对同一次 admission 的完整内容。
     *
     * @param driver 执行驱动
     * @param persistence 持久化边界
     * @param records 记录列表
     * @param admitted 已准入的执行
     * @param admissionCalls 准入调用次数
     */
    private record Fixture(AgentExecutionDriver driver,
                           AgentLoopPersistence persistence,
                           ExecutionRecordCodec records,
                           AtomicReference<AgentStore.Admission> admitted,
                           AtomicInteger admissionCalls) { }

}
