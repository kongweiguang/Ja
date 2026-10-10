// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;
import io.github.kongweiguang.ja.agent.bootstrap.AgentEngineBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.conversation.domain.model.ClientOperationReceipt;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader;
import io.github.kongweiguang.ja.agent.application.gateway.ModelEventSink;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.model.SummaryModel;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ControlPayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.conversation.application.admission.JaAdmissionParticipants;
import io.github.kongweiguang.ja.conversation.application.service.JaExecutionContexts;
import io.github.kongweiguang.ja.conversation.application.service.TurnService;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnChangeSet;
import io.github.kongweiguang.ja.conversation.domain.model.TurnEvent;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.application.api.InternalTurnStartRequest;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.application.gateway.AgentExecutionEvents;
import io.github.kongweiguang.ja.conversation.application.gateway.JaOperationReceiptRepository;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeResolver;
import io.github.kongweiguang.ja.conversation.application.gateway.WorkspaceBindingResolver;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TurnChangeSetCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaAdmissionParticipantFactory;
import io.github.kongweiguang.ja.infrastructure.persistence.support.AgentStoreProjectionFixture;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import io.github.kongweiguang.ja.support.FixedAgentPromptSession;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import io.github.kongweiguang.ja.conversation.application.gateway.ConversationAttachmentPort;
import io.github.kongweiguang.ja.conversation.application.title.AutomaticThreadTitleScheduler;
import io.github.kongweiguang.ja.conversation.application.gateway.ConversationWorkspaceReferencePort;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 直接覆盖 Ja 服务层 continue receipt 的 SQLite 重试与冲突边界。 */
final class TurnContinueIdempotencyTest extends PersistenceTestSupport {
    private static final String THREAD_ID = "thr_continue_receipt";
    private static final String WORKSPACE_ID = "ws_continue_receipt";
    private static final String SOURCE_TURN_ID = "turn_failed_source";
    private static final String SOURCE_MESSAGE_ID = "item_failed_source";
    private static final String CONTINUATION_TURN_ID = "turn_continue_first";
    private static final String OPERATION_ID = "op_00000000000000000000000000000031";
    private static final String FINGERPRINT = "a".repeat(64);
    private static final Instant STARTED_AT = Instant.parse("2026-09-28T12:00:00Z");
    private static final ThreadPreferences PREFERENCES = new ThreadPreferences("provider_test", "model_test",
            "medium", AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT,
            ThreadPreferences.TitleSource.PLACEHOLDER);

    /** 持久准入后保持首个 Provider 请求未完成，验证重复 client operation 只回读原 Turn。 */
    @Test
    void reusesPersistedContinueReceiptAndRejectsChangedFingerprint() throws Exception {
        try (TestDatabase database = database("turn-continue-idempotency");
             AgentStore store = database.jaAgentStore()) {
            prepareFailedSource(database, store);
            long threadRevision = database.conversationProjections().readThread(THREAD_ID).orElseThrow().revision();

            BlockingModel model = new BlockingModel();
            AgentEngine engine = new AgentEngineBuilder().store(store).runtimeResolver(runtimeResolver(model))
                    .extensions(registry -> registry.registerMiddleware(emptyChangeSet(database.mapper())))
                    .build();
            try (engine) {
                JaOperationReceiptRepository receipts = new MybatisJaOperationReceiptRepository(
                        database.sessions(), readOnlyTestTransactions());
                TurnService service = service(engine, store, database, receipts);
                TurnUseCase.Accepted first = null;
                try {
                    InternalTurnStartRequest firstRequest = continuationRequest(CONTINUATION_TURN_ID,
                            threadRevision);
                    first = service.continueQuestion(firstRequest,
                            ConversationTurnEventSink.noop(), OPERATION_ID, FINGERPRINT);
                    assertTrue(model.started.await(5, TimeUnit.SECONDS), "continue did not reach the local model");

                    ClientOperationReceipt receipt = receipts.readClientOperation(OPERATION_ID).orElseThrow();
                    assertEquals(OPERATION_ID, receipt.operationKey());
                    assertTrue(receipt.matches("turn/continue", FINGERPRINT));
                    assertEquals(THREAD_ID, receipt.threadId());
                    assertEquals(CONTINUATION_TURN_ID, receipt.turnId());

                    InternalTurnStartRequest retryRequest = continuationRequest("turn_continue_retry",
                            threadRevision);
                    TurnUseCase.Accepted retry = service.continueQuestion(retryRequest,
                            ConversationTurnEventSink.noop(), OPERATION_ID, FINGERPRINT);

                    assertEquals(first.threadId(), retry.threadId());
                    assertEquals(first.turnId(), retry.turnId());
                    assertEquals(first.threadRevision(), retry.threadRevision());
                    assertEquals(1, model.calls.get(), "a receipt retry must not invoke a second model execution");
                    assertFalse(store.loadExecution(executionKey("turn_continue_retry")).isPresent(),
                            "a retry must not admit the newly generated Turn identity");
                    assertEquals(0, userMessageRecords(store, first.turnId()),
                            "a hidden continuation must not create a visible USER message");
                    assertEquals(2, database.conversationProjections().readThread(THREAD_ID).orElseThrow()
                            .turns().size(), "the retry must not append another Turn");

                    StorageException conflict = assertThrows(StorageException.class,
                            () -> service.continueQuestion(continuationRequest("turn_continue_conflict",
                                            threadRevision),
                                    ConversationTurnEventSink.noop(), OPERATION_ID, "b".repeat(64)));
                    assertEquals(StorageException.Code.CAS_CONFLICT, conflict.code());
                    assertEquals(1, model.calls.get(), "a conflicting retry must not enter the model");
                    assertFalse(store.loadExecution(executionKey("turn_continue_conflict")).isPresent());
                } finally {
                    model.complete();
                }

                assertNotNull(first);
                io.github.kongweiguang.ja.conversation.domain.model.TurnResult completed = first.completion()
                        .toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
                assertEquals(TurnState.COMPLETED, completed.state());
                assertNotNull(receipts.readClientOperation(OPERATION_ID).orElse(null));
            }
        }
    }

    /** 建立真实失败来源消息，continue 的业务来源校验仍会读取 Core USER record。 */
    private void prepareFailedSource(TestDatabase database, AgentStore store) {
        database.history().register(new Workspace.Registration(WORKSPACE_ID, temp.toAbsolutePath(), "workspace", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, STARTED_AT));
        database.history().createThread(new ThreadSummary.Creation(THREAD_ID, WORKSPACE_ID,
                "continue receipt", PREFERENCES, STARTED_AT));

        AgentIdentity identity = identity(SOURCE_TURN_ID);
        AgentExecutionRequest request = new AgentExecutionRequest(identity,
                AgentExecutionRequest.AdmissionKind.USER_INPUT,
                Optional.of(new UserContent(List.of(new TextContent("source request")))), SOURCE_MESSAGE_ID,
                Optional.empty(), new AgentStore.OperationIdentity("operation_failed_source", "turn/start", FINGERPRINT, new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}")), Map.of(), AgentExecutionRequest.HistorySelection.currentConversation(),
                STARTED_AT, List.of(), Optional.empty(), Optional.empty());
        AdmissionFacts facts = new AdmissionFacts(identity, WORKSPACE_ID, TurnOrigin.USER, "turn/start",
                STARTED_AT, SOURCE_MESSAGE_ID, null, null, null, null, null, null, 0, Optional.empty());
        try (AgentStoreProjectionFixture fixture = AgentStoreProjectionFixture.open(store, database.mapper(),
                request, facts, PREFERENCES, 0)) {
            fixture.commitRecords(TurnState.FAILED, completionCursor(), List.of(),
                    List.of(new ExecutionEvent.Terminal(TurnState.FAILED, "model unavailable",
                            "MODEL_UNAVAILABLE", "Provider unavailable", null, null)), STARTED_AT.plusSeconds(1));
        }
    }

    /** 构造服务用例只会提交的 continuation request，并让不同重试身份显式可见。 */
    private InternalTurnStartRequest continuationRequest(String turnId, long threadRevision) {
        return new InternalTurnStartRequest(THREAD_ID, turnId, WORKSPACE_ID, temp.toAbsolutePath(),
                "provider_test", "model_test", "medium", AccessMode.APPROVAL_REQUIRED,
                CollaborationMode.DEFAULT, threadRevision, 0, STARTED_AT.plusSeconds(3),
                TurnOrigin.USER_CONTINUATION, null, null);
    }

    /** 只装配 retry 路径会读取的真实 DB ports，所有非目标能力遇到调用都让测试失败。 */
    private TurnService service(AgentEngine engine, AgentStore store, TestDatabase database,
                                JaOperationReceiptRepository receipts) {
        ObjectMapper mapper = database.mapper();
        return new TurnService(engine, store, new StorePayloadCodec(mapper), new ControlPayloadCodec(mapper),
                receipts, database.conversationProjections(), new JaExecutionContexts(),
                new JaAdmissionParticipants(), thread -> Optional.empty(), (thread, limit) -> List.of(),
                executionEvents(), unusedPort(ConversationAttachmentPort.class),
                unusedPort(ConversationWorkspaceReferencePort.class), unusedPort(WorkspaceBindingResolver.class),
                unusedPort(TurnRuntimeResolver.class), AutomaticThreadTitleScheduler.disabled(),
                java.time.Clock.fixed(STARTED_AT, java.time.ZoneOffset.UTC));
    }

    /** 使用已有包级注入口读取真实回执，避免启动无关 Solon 服务或扩大生产测试 API。 */
    private static MybatisUnitOfWork.SessionOwner readOnlyTestTransactions() {
        return new MybatisUnitOfWork.SessionOwner() {
            /** 回执查询不提交写入；SqlSession 由本 owner 关闭，Store 事务仍由 Engine 管理。 */
            @Override
            public <T> T execute(SqlSessionFactory sessions, MybatisUnitOfWork.Work<T> work) {
                try (SqlSession session = sessions.openSession()) {
                    return work.apply(PersistenceMappers.open(session));
                } catch (RuntimeException | Error failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw new IllegalStateException("receipt read failed", failure);
                }
            }
        };
    }

    /** 此用例没有文件工具，明确提交空变更事实，让真实 Ja 终态约束与幂等断言共同生效。 */
    private static AgentMiddleware emptyChangeSet(ObjectMapper mapper) {
        JsonObject payload = new TurnChangeSetCodec(mapper).writePayload(TurnChangeSet.emptyComplete());
        return new AgentMiddleware() {
            /** 使用 Ja 已有事实命名空间，测试不引入新的业务协议或兼容载荷。 */
            @Override
            public String id() {
                return "ja.turn";
            }

            /** 只在成功候选被接受时冻结无文件变更事实，不提前写入业务终态。 */
            @Override
            public ExtensionDecision<CompletionDecision> beforeCompletion(CompletionContext context) {
                return new ExtensionDecision<>(CompletionDecision.complete(),
                        ExtensionStatePatch.unchanged(context.extensionState()),
                        List.of(new MiddlewareFact("change-set", 1, payload)));
            }
        };
    }

    /** 从真实 mapper 上下文重建本次 Ja admission facts，再由同事务参与者写 SQLite receipt。 */
    private static io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntimeResolver runtimeResolver(
            BlockingModel model) {
        ModelPort.ModelConfiguration configuration = new ModelPort.ModelConfiguration(
                "provider_test", "model_test", "cfg_continue_test", ModelPort.Api.OPENAI_RESPONSES,
                "model_test", URI.create("http://127.0.0.1:1"), "test-secret", Duration.ofSeconds(1),
                Duration.ofSeconds(1), Set.of(ModelPort.InputModality.TEXT), ModelPort.GenerationOptions.defaults());
        AgentPromptSession prompt = new FixedAgentPromptSession(ContextBudget.capabilities(100_000, 8_192, true));
        SummaryModel.Factory summaries = binding -> {
            throw new AssertionError("the receipt retry scenario does not require summary generation");
        };
        ManagedAttachmentReader attachments = request -> {
            throw new AssertionError("the receipt retry scenario has no attachments");
        };
        return (request, base, recovered) -> {
            Map<String, String> attributes = request.contextAttributes();
            AdmissionFacts facts = new AdmissionFacts(request.identity(), attributes.get("ja.workspaceId"),
                    TurnOrigin.valueOf(attributes.get("ja.origin")), attributes.get("ja.method"),
                    Instant.parse(attributes.get("ja.acceptedAt")), attributes.get("ja.sourceMessageId"),
                    null, null, null, null, attributes.get("ja.operationKey"),
                    attributes.get("ja.requestFingerprint"),
                    Long.parseLong(attributes.get("ja.expectedThreadRevision")), Optional.empty());
            AgentStoreTransactionParticipant admission = new JaAdmissionParticipantFactory().create(facts);
            AgentStoreTransactionParticipant admissionOnly = transaction -> {
                if (transaction.kind() == AgentStore.TransactionKind.ADMISSION) {
                    admission.apply(transaction);
                }
            };
            return new AgentRequestRuntime(configuration, model, summaries, base, prompt, "c".repeat(64),
                    admissionOnly, attachments,
                    new ToolProjectionLimits(64_000, 16_000),
                    Map.of("accessMode", AccessMode.APPROVAL_REQUIRED.name(),
                            "collaborationMode", CollaborationMode.DEFAULT.name(),
                            "configGeneration", configuration.configGeneration()), () -> { });
        };
    }

    /** 首次 Provider 调用保持挂起，使 receipt 重试不受完成速度或网络行为影响。 */
    private static final class BlockingModel implements ModelPort {
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CompletableFuture<ModelOutcome> response = new CompletableFuture<>();

        /** 使用固定本地估算，避免 tokenizer 或网络服务成为本回归的额外依赖。 */
        @Override
        public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellation) {
            return new InputTokenEstimate(16, 16, "d".repeat(64));
        }

        /** 记录真实 Engine 模型调用并等待测试显式放行终态。 */
        @Override
        public java.util.concurrent.CompletionStage<ModelOutcome> start(
                ModelRequest request, ModelEventSink sink, CancellationToken cancellation) {
            cancellation.throwIfCancellationRequested();
            calls.incrementAndGet();
            sink.onEvent(new ModelPort.TextDelta("completed answer"));
            started.countDown();
            return response;
        }

        /** 释放模型响应，使服务可完成资源清理与终态回调。 */
        private void complete() {
            response.complete(new ModelOutcome(FinishReason.STOP, null, new ModelUsage(4, 1, 5)));
        }
    }

    /** 用稳定的最小 terminal projection 完成本地 Engine 回调，不伪造额外存储写入。 */
    private static AgentExecutionEvents executionEvents() {
        return new AgentExecutionEvents() {
            /** 本测试只检查持久 admission，不需要在 sink 上复制短时 SSE 事件。 */
            @Override
            public AutoCloseable attach(AgentIdentity identity, ConversationTurnEventSink sink) {
                return () -> { };
            }

            /** 将 Core 终态封装成满足 Ja 结果不变量的局部测试值。 */
            @Override
            public io.github.kongweiguang.ja.conversation.domain.model.TurnResult result(
                    AgentIdentity identity, io.github.kongweiguang.ja.agent.application.api.TurnResult completed) {
                String suffix = identity.turnId().substring("turn_".length());
                TurnEvent.Context context = new TurnEvent.Context("evt_" + suffix, identity.threadId(),
                        identity.turnId(), 0, 0, STARTED_AT.plusSeconds(5));
                TurnEvent.Terminal terminal = new TurnEvent.Terminal(context, completed.state(),
                        completed.summary(), null, null,
                        new TurnEvent.FinalMessage("item_final_" + suffix, "completed answer"), null,
                        TurnChangeSet.emptyComplete());
                return new io.github.kongweiguang.ja.conversation.domain.model.TurnResult(
                        completed.state(), completed.summary(), terminal);
            }

            /** 此用例保持响应未完成直到冲突断言结束，提前出现 terminal 表示执行路径异常。 */
            @Override
            public io.github.kongweiguang.ja.conversation.domain.model.TurnResult terminalResult(AgentIdentity identity) {
                throw new AssertionError("the held model response must not have a terminal projection yet");
            }
        };
    }

    /** 返回 fixture 身份键，以便断言重试没有创建第二条 Engine execution。 */
    private static AgentStore.ExecutionKey executionKey(String turnId) {
        return new AgentStore.ExecutionKey(new AgentStore.ConversationKey("ja.local", THREAD_ID, "user_local"), turnId);
    }

    /** 使用 Ja RPC 稳定身份构造 Store 与 projection 共用的完整执行键。 */
    private static AgentIdentity identity(String turnId) {
        return new AgentIdentity("ja.local", THREAD_ID, turnId, "user_local");
    }

    /** 固定 Store 水位统计一个 execution 中的直接 USER facts，确认 INTERNAL 指令不伪造消息。 */
    private static long userMessageRecords(AgentStore store, String turnId) {
        AgentStore.ExecutionKey execution = executionKey(turnId);
        AgentStore.ExecutionSnapshot snapshot = store.loadExecution(execution).orElseThrow();
        return store.readRecords(AgentStore.RecordQuery.withDefaultMaxBytes(execution, 0, snapshot.lastRecordSequence(), 1_000))
                .records().stream().filter(record -> record.recordType().equals("agent.user.message")).count();
    }

    /** 失败来源终态游标指向结算，不允许把失败 Turn 伪装成待执行模型位置。 */
    private static TurnExecutionState.Ready completionCursor() {
        return new TurnExecutionState.Ready(new TurnExecutionState.Common(0, 0, 1, null, List.of()),
                TurnExecutionState.Next.COMPLETION, null);
    }

    /** 非预期宿主能力只在错误路径被触发，测试必须清楚暴露依赖扩张。 */
    @SuppressWarnings("unchecked")
    private static <T> T unusedPort(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> {
                    throw new AssertionError("unexpected port call: " + type.getSimpleName() + "." + method.getName());
                });
    }
}
