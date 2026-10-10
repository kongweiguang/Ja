// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentAnswer;
import io.github.kongweiguang.ja.agent.application.engine.AgentRequestFingerprint;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.StoreConflictException;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationUseCase;
import io.github.kongweiguang.ja.conversation.infrastructure.provider.support.ModelAdapterTestSupport;
import io.github.kongweiguang.ja.conversation.application.service.JaTurnRequestMapper;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnEvent;
import io.github.kongweiguang.ja.conversation.domain.model.TurnResult;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionRequest;
import io.github.kongweiguang.ja.conversation.application.api.InteractionUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.api.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.Solon;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用真实 Solon JVM 与共享 SQLite 覆盖澄清回答、取消及进程重启恢复。 */
final class JaClarificationAnswerIntegrationTest {
    private static final String PROVIDER_ID = "provider_clarification_fixture";
    private static final String MODEL_ID = "model_clarification_fixture";
    private static final String CREDENTIAL_ID = "cred_clarification_fixture";
    private static final String THREAD_ID = "thr_clarification_scope";
    private static final String TURN_ID = "turn_clarification_scope";
    private static final String ANSWER_TEXT = "cerulean";
    private static final String FINAL_TEXT = "Clarification applied.";

    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    Path temporary;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 三个独立 JVM 共用持久数据目录和 Loopback Provider，验证重启后答复及后续恢复。 */
    @Test
    void clarificationAnswerIsIdempotentAndResumesAfterProcessRestart() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> providerRequests = new CopyOnWriteArrayList<>();
        JaClarificationAnswerIntegrationTest providerFixture = new JaClarificationAnswerIntegrationTest();
        try (ModelAdapterTestSupport.Loopback provider = new ModelAdapterTestSupport.Loopback((sequence, exchange) -> {
            int call = calls.incrementAndGet();
            providerRequests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (call == 1) {
                ModelAdapterTestSupport.sse(exchange, providerFixture.toolCallResponse("resp_clarification_question",
                        "call_clarification", "request_user_input",
                        """
                            {"questions":[{"questionId":"question_color","prompt":"Which color?",\
                            "type":"text","options":[],"required":true,"allowFreeText":true}]}\
                            """), 31);
            } else if (call == 2) {
                ModelAdapterTestSupport.sse(exchange,
                        providerFixture.finalTextResponse("resp_clarification_final", FINAL_TEXT), 29);
            } else {
                ModelAdapterTestSupport.status(exchange, 500);
            }
        })) {
            Path home = Files.createDirectories(temporary.resolve("home"));
            Files.writeString(home.resolve("config.toml"), configuration(provider.baseUri()));
            runChild(temporary, "pending");
            assertEquals(1, calls.get(), "the first process must stop with one persisted clarification wait");

            runChild(temporary, "answer");
            assertEquals(1, calls.get(), "committing an answer must not resume the Provider before explicit recovery");

            runChild(temporary, "resume");
            assertEquals(2, calls.get(), "the resumed Turn must issue exactly one additional Provider request");
            assertEquals(1, occurrences(providerRequests.get(1), ANSWER_TEXT),
                    "the restored Provider request must contain the committed answer exactly once");
        }
    }

    /** 新进程取消重启前创建的等待，确认 SQLite 只追加关闭事实且不创建答案或模型副作用。 */
    @Test
    void cancellationAfterProcessRestartPersistsOneCloseWithoutAnswerOrProviderReplay() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        JaClarificationAnswerIntegrationTest providerFixture = new JaClarificationAnswerIntegrationTest();
        try (ModelAdapterTestSupport.Loopback provider = new ModelAdapterTestSupport.Loopback((sequence, exchange) -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                ModelAdapterTestSupport.sse(exchange, providerFixture.toolCallResponse("resp_clarification_cancel",
                        "call_clarification_cancel", "request_user_input",
                        """
                            {"questions":[{"questionId":"question_color","prompt":"Which color?",\
                            "type":"text","options":[],"required":true,"allowFreeText":true}]}\
                            """), 31);
            } else {
                ModelAdapterTestSupport.status(exchange, 500);
            }
        })) {
            Path home = Files.createDirectories(temporary.resolve("home"));
            Files.writeString(home.resolve("config.toml"), configuration(provider.baseUri()));
            runChild(temporary, "pending");
            assertEquals(1, calls.get(), "the pending wait must be the only Provider request");

            runChild(temporary, "cancel");
            assertEquals(1, calls.get(), "cancelling a recovered wait must not invoke the Provider");
        }
    }

    /** 每个阶段由新的 Solon 进程执行；阶段选择显式限定写入和恢复顺序。 */
    public static void main(String[] arguments) throws Throwable {
        if (arguments.length != 2 || !"25".equals(System.getProperty("java.specification.version"))) {
            throw new IllegalStateException("isolated clarification test requires a profile, phase, and JDK 25");
        }
        JaClarificationAnswerIntegrationTest fixture = new JaClarificationAnswerIntegrationTest();
        Path root = Path.of(arguments[0]);
        fixture.temporary = root;
        switch (arguments[1]) {
            case "pending" -> fixture.pendingAndExit(root);
            case "answer" -> fixture.answerAfterRestartAndExit(root);
            case "resume" -> fixture.resumeAfterRestart(root);
            case "cancel" -> fixture.cancelAfterRestart(root);
            default -> throw new IllegalArgumentException("unknown clarification test phase");
        }
    }

    /** 创建持久等待后结束进程；错误问题与错误执行身份保持零写入。 */
    private void pendingAndExit(Path root) throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Map<String, String> previous = new LinkedHashMap<>();
        try {
            setRuntimeProperties(home, previous);
            Solon.start(App.class, new String[0], started -> started.enableHttp(false));

            ConfigurationUseCase configuration = Solon.context().getBean(ConfigurationUseCase.class);
            configuration.setCredential(CREDENTIAL_ID, "isolated-test-key", configuration.read(null).credentialVersion());
            WorkspaceUseCase workspaces = Solon.context().getBean(WorkspaceUseCase.class);
            ThreadUseCase threads = Solon.context().getBean(ThreadUseCase.class);
            TurnUseCase turns = Solon.context().getBean(TurnUseCase.class);
            InteractionUseCase interactions = Solon.context().getBean(InteractionUseCase.class);
            AgentEngine engine = Solon.context().getBean(AgentEngine.class);
            AgentStore store = Solon.context().getBean(AgentStore.class);

            io.github.kongweiguang.ja.workspace.domain.model.Workspace workspace =
                    workspaces.createSessionWorkspace(THREAD_ID);
            ThreadPreferences preferences = new ThreadPreferences(PROVIDER_ID, MODEL_ID, null,
                    io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode.FULL_ACCESS,
                    CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.PLACEHOLDER);
            ThreadSummary thread = threads.createThread(new ThreadSummary.Creation(
                    THREAD_ID, workspace.workspaceId(), "Clarification scope fixture", preferences, Instant.now()));
            TurnStartRequest request = new TurnStartRequest(THREAD_ID, TURN_ID, workspace.workspaceId(), workspace.root(),
                    new UserContent(List.of(new TextContent("Choose a color for the report."))), PROVIDER_ID,
                    MODEL_ID, null, io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode.FULL_ACCESS,
                    CollaborationMode.DEFAULT, thread.revision(), 0, Instant.now(), Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), false);
            List<TurnEvent> events = new CopyOnWriteArrayList<>();
            AgentIdentity identity = JaTurnRequestMapper.identity(THREAD_ID, TURN_ID);
            turns.start(request, sink(events));
            InteractionRequest pending = awaitPending(interactions, engine, identity, THREAD_ID);
            assertEquals("question_color", pending.questions().getFirst().questionId());
            assertEquals(0, answerRecordCount(store, identity), "the initial wait must have no answer record");

            AgentAnswer wrongQuestion = answer(pending.interactionId(), "answer-wrong-question",
                    pending.interactionRevision(),
                    List.of(new InteractionAnswer("question_missing", List.of(), ANSWER_TEXT, false)));
            assertThrows(Exception.class, () -> engine.answer(identity, wrongQuestion).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS));
            AgentAnswer scopedAnswer = answer(pending.interactionId(), "answer-wrong-turn",
                    pending.interactionRevision(),
                    List.of(new InteractionAnswer("question_color", List.of(), ANSWER_TEXT, false)));
            AgentIdentity wrongIdentity = new AgentIdentity(identity.tenantId(), identity.threadId(),
                    "turn_wrong_identity", identity.userId());
            assertThrows(Exception.class, () -> engine.answer(wrongIdentity, scopedAnswer).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS));
            InteractionRequest stillPending = readRequest(interactions, THREAD_ID);
            assertEquals(InteractionStatus.PENDING, stillPending.status());
            assertTrue(stillPending.answers().isEmpty());
            assertEquals("WAITING_INPUT", engine.query(identity).orElseThrow().state());
            assertEquals(0, answerRecordCount(store, identity),
                    "mismatched question and Turn identities must not persist an answer");

            assertEquals(0, answerRecordCount(store, identity),
                    "the process must stop with the clarification still pending and no answer record");
        } finally {
            if (Solon.context() != null) {
                Solon.stopBlock(false, 0);
            }
            previous.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                }
                else {
                    System.setProperty(key, value);
                }
            });
        }
    }

    /** 新 JVM 以 Interaction 的持久修订提交答案；幂等重试只保留一条答案记录。 */
    private void answerAfterRestartAndExit(Path root) throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Map<String, String> previous = new LinkedHashMap<>();
        try {
            setRuntimeProperties(home, previous);
            Solon.start(App.class, new String[0], started -> started.enableHttp(false));

            InteractionUseCase interactions = Solon.context().getBean(InteractionUseCase.class);
            AgentEngine engine = Solon.context().getBean(AgentEngine.class);
            AgentStore store = Solon.context().getBean(AgentStore.class);
            AgentIdentity identity = JaTurnRequestMapper.identity(THREAD_ID, TURN_ID);
            InteractionRequest pending = readRequest(interactions, THREAD_ID);
            assertEquals(InteractionStatus.PENDING, pending.status());
            assertEquals("question_color", pending.questions().getFirst().questionId());
            assertEquals("WAITING_INPUT", engine.query(identity).orElseThrow().state());
            assertEquals(0, answerRecordCount(store, identity));

            AgentAnswer correctAnswer = answer(pending.interactionId(), "answer-operation-clarification",
                    pending.interactionRevision(),
                    List.of(new InteractionAnswer("question_color", List.of(), ANSWER_TEXT, false)));
            engine.answer(identity, correctAnswer).toCompletableFuture().get(10, TimeUnit.SECONDS);
            engine.answer(identity, correctAnswer).toCompletableFuture().get(10, TimeUnit.SECONDS);

            InteractionRequest answered = readRequest(interactions, THREAD_ID);
            assertEquals(InteractionStatus.ANSWERED, answered.status());
            assertEquals(1, answered.answers().size());
            assertEquals(ANSWER_TEXT, answered.answers().getFirst().freeText());
            assertEquals("SUSPENDED", engine.query(identity).orElseThrow().state(),
                    "answering persists the wait settlement but does not implicitly resume execution");
            assertEquals(1, answerRecordCount(store, identity), "an idempotent replay must retain one answer record");
        } finally {
            if (Solon.context() != null) {
                Solon.stopBlock(false, 0);
            }
            previous.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                }
                else {
                    System.setProperty(key, value);
                }
            });
        }
    }

    /** 新 JVM 通过生产取消服务关闭待回答请求，并核实真实 SQLite 的单次状态迁移。 */
    private void cancelAfterRestart(Path root) throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Map<String, String> previous = new LinkedHashMap<>();
        try {
            setRuntimeProperties(home, previous);
            Solon.start(App.class, new String[0], started -> started.enableHttp(false));

            InteractionUseCase interactions = Solon.context().getBean(InteractionUseCase.class);
            AgentEngine engine = Solon.context().getBean(AgentEngine.class);
            AgentStore store = Solon.context().getBean(AgentStore.class);
            AgentIdentity identity = JaTurnRequestMapper.identity(THREAD_ID, TURN_ID);
            InteractionRequest pending = readRequest(interactions, THREAD_ID);
            assertEquals(InteractionStatus.PENDING, pending.status());
            assertEquals("WAITING_INPUT", engine.query(identity).orElseThrow().state());
            int initialWaitRecords = waitRecordCount(store, identity);
            assertEquals(1, initialWaitRecords);

            InteractionRequest cancelled = interactions.cancel(THREAD_ID, pending.interactionId(),
                    pending.interactionRevision(), InteractionStatus.CANCELLED, "cancel-after-restart", Instant.now());

            InteractionRequest persisted = readRequest(interactions, THREAD_ID);
            assertEquals(InteractionStatus.CANCELLED, cancelled.status());
            assertEquals(pending.interactionRevision() + 1, persisted.interactionRevision());
            assertEquals(InteractionStatus.CANCELLED, persisted.status());
            assertTrue(persisted.answers().isEmpty());
            assertEquals(initialWaitRecords + 1, waitRecordCount(store, identity),
                    "cancellation must append one durable wait fact");
            assertEquals(0, answerRecordCount(store, identity), "cancellation must not create an answer submission");
        } finally {
            if (Solon.context() != null) {
                Solon.stopBlock(false, 0);
            }
            previous.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                }
                else {
                    System.setProperty(key, value);
                }
            });
        }
    }

    /** 新 JVM 恢复并完成 Turn 后，经生产 UseCase 核对回答回执、冲突分类及无副作用重试。 */
    private void resumeAfterRestart(Path root) throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Map<String, String> previous = new LinkedHashMap<>();
        try {
            setRuntimeProperties(home, previous);
            Solon.start(App.class, new String[0], started -> started.enableHttp(false));

            ThreadUseCase threads = Solon.context().getBean(ThreadUseCase.class);
            TurnUseCase turns = Solon.context().getBean(TurnUseCase.class);
            InteractionUseCase interactions = Solon.context().getBean(InteractionUseCase.class);
            AgentEngine engine = Solon.context().getBean(AgentEngine.class);
            AgentStore store = Solon.context().getBean(AgentStore.class);
            AgentIdentity identity = JaTurnRequestMapper.identity(THREAD_ID, TURN_ID);
            InteractionRequest answered = readRequest(interactions, THREAD_ID);
            assertEquals(InteractionStatus.ANSWERED, answered.status());
            assertEquals(1, answered.answers().size());
            assertEquals(1, answerRecordCount(store, identity));
            long answeredRevision = answered.interactionRevision();
            long submittedRevision = answeredRevision - 1;
            List<InteractionAnswer> committedAnswers = answered.answers();
            AgentAnswer committedAnswer = answer(answered.interactionId(), "answer-operation-clarification",
                    submittedRevision, committedAnswers);
            AgentStore.OperationKey receiptKey = operationReceiptKey(identity, committedAnswer);
            AgentStore.OperationReceipt committedReceipt = store.readReceipt(receiptKey).orElseThrow();

            ThreadSummary thread = threads.readThread(THREAD_ID, null, 32).orElseThrow().thread();
            List<TurnEvent> events = new CopyOnWriteArrayList<>();
            TurnUseCase.Accepted resumed = turns.resume(TURN_ID, thread.revision(), sink(events));
            TurnResult result = resumed.completion().toCompletableFuture().get(25, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED, result.state());
            assertEquals(FINAL_TEXT, result.terminal().finalMessage().text());
            assertEquals(1, events.stream().filter(TurnEvent.Terminal.class::isInstance).count());
            assertEquals("COMPLETED", engine.query(identity).orElseThrow().state());
            assertEquals(1, answerRecordCount(store, identity), "resume must not duplicate the committed answer");
            assertTrue(turns.awaitQuiescence(java.time.Duration.ZERO));

            InteractionRequest replayed = interactions.respond(THREAD_ID, answered.interactionId(), submittedRevision,
                    committedAnswers, committedAnswer.operationKey(), Instant.now());
            assertEquals(InteractionStatus.ANSWERED, replayed.status());
            assertEquals(committedAnswers, replayed.answers());
            assertEquals(committedReceipt, store.readReceipt(receiptKey).orElseThrow(),
                    "an exact replay must return the same durable Core receipt");

            List<InteractionAnswer> changedAnswers = List.of(new InteractionAnswer("question_color", List.of(),
                    "different color", false));
            InteractionUseCase.InteractionException changedAnswer = assertThrows(
                    InteractionUseCase.InteractionException.class,
                    () -> interactions.respond(THREAD_ID, answered.interactionId(), submittedRevision,
                            changedAnswers, committedAnswer.operationKey(), Instant.now()));
            assertEquals(InteractionUseCase.Failure.INVALID_STATE, changedAnswer.code());
            assertInstanceOf(StoreConflictException.class, changedAnswer.getCause());

            String staleOperationKey = "answer-operation-stale";
            AgentAnswer staleAnswer = answer(answered.interactionId(), staleOperationKey,
                    submittedRevision, committedAnswers);
            AgentStore.OperationKey staleReceiptKey = operationReceiptKey(identity, staleAnswer);
            InteractionUseCase.InteractionException staleRevision = assertThrows(
                    InteractionUseCase.InteractionException.class,
                    () -> interactions.respond(THREAD_ID, answered.interactionId(), submittedRevision,
                            committedAnswers, staleOperationKey, Instant.now()));
            assertEquals(InteractionUseCase.Failure.REVISION_CONFLICT, staleRevision.code());
            assertInstanceOf(StoreConflictException.class, staleRevision.getCause());
            assertTrue(store.readReceipt(staleReceiptKey).isEmpty(),
                    "a stale revision must not create a receipt for a new operation key");
            assertEquals(committedReceipt, store.readReceipt(receiptKey).orElseThrow());
            assertEquals(1, answerRecordCount(store, identity),
                    "replays and rejected answers must not append another answer fact");
            assertEquals("COMPLETED", engine.query(identity).orElseThrow().state());
        } finally {
            if (Solon.context() != null) {
                Solon.stopBlock(false, 0);
            }
            previous.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                }
                else {
                    System.setProperty(key, value);
                }
            });
        }
    }

    /** 子进程等待与清理使用同一 owner；中断仍传播，阶段日志保留供失败诊断。 */
    private static void runChild(Path root, String phase) throws Exception {
        Path output = root.resolve("clarification-" + phase + ".log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "--enable-native-access=ALL-UNNAMED", "-Dja.log-dir=" + root.resolve("logs"),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                JaClarificationAnswerIntegrationTest.class.getName(), root.toString(), phase)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        assertEquals(0, ChildProcessTestSupport.awaitExit(child, 90), () -> failureOutput(output));
    }

    /** 将 Interaction projection 作为答案业务状态读取，不以 Core 回执代替宿主可见结果。 */
    private static InteractionRequest readRequest(InteractionUseCase interactions, String threadId) {
        return interactions.read(threadId, null).flatMap(snapshot -> snapshot.request()).orElseThrow();
    }

    /** 复用生产交互服务的回执元数据约束，使失败用例能到达目标的作用域或题目校验。 */
    private static AgentAnswer answer(String interactionId, String operationKey, long expectedRevision,
                                      List<InteractionAnswer> answers) {
        return new AgentAnswer(interactionId, operationKey, expectedRevision, answers,
                Map.of("ja.operationKey", operationKey,
                        "ja.expectedInteractionRevision", Long.toString(expectedRevision)));
    }

    /** 将调用方答案键换算为 Core 的租户级 receipt key，以读回比较真实持久回执。 */
    private static AgentStore.OperationKey operationReceiptKey(AgentIdentity identity, AgentAnswer answer) {
        String scopedOperationKey = AgentRequestFingerprint.prepareAnswer(identity, answer).operationKey();
        return new AgentStore.OperationKey(identity.tenantId(), "user_local", scopedOperationKey);
    }

    /** 读取执行水位内的 Core wait 事实，验证重启后取消只追加一次状态修订。 */
    private static int waitRecordCount(AgentStore store, AgentIdentity identity) {
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(
                new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), "user_local"), identity.turnId());
        AgentStore.ExecutionSnapshot snapshot = store.loadExecution(execution).orElseThrow();
        AgentStore.RecordPage page = store.readRecords(AgentStore.RecordQuery.withDefaultMaxBytes(execution, 0,
                snapshot.lastRecordSequence(), 1_000));
        if (page.hasMore()) {
            throw new AssertionError("clarification records exceeded the bounded test page");
        }
        return (int) page.records().stream()
                .filter(record -> ExecutionControlCodec.WAIT_RECORD_TYPE.equals(record.recordType())).count();
    }

    /** 按执行局部水位读取 Core facts，确认幂等回答只落下一条 answer submission。 */
    private static int answerRecordCount(AgentStore store, AgentIdentity identity) {
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(
                new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), "user_local"), identity.turnId());
        AgentStore.ExecutionSnapshot snapshot = store.loadExecution(execution).orElseThrow();
        AgentStore.RecordPage page = store.readRecords(AgentStore.RecordQuery.withDefaultMaxBytes(execution, 0,
                snapshot.lastRecordSequence(), 1_000));
        if (page.hasMore()) {
            throw new AssertionError("clarification records exceeded the bounded test page");
        }
        return (int) page.records().stream()
                .filter(record -> ExecutionControlCodec.ANSWER_SUBMISSION_RECORD_TYPE.equals(record.recordType())).count();
    }

    /** 统计 Provider 请求中的答案片段，避免仅凭响应终态推断答案只消费了一次。 */
    private static int occurrences(String value, String marker) {
        int count = 0;
        for (int from = 0; (from = value.indexOf(marker, from)) >= 0; from += marker.length()) {
            count++;
        }
        return count;
    }

    /** 等待实际 Engine 与 Interaction SQLite 投影都进入澄清挂起，防止只测内存 Tool 回调。 */
    private static InteractionRequest awaitPending(InteractionUseCase interactions, AgentEngine engine,
                                                   AgentIdentity identity, String threadId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Optional<InteractionRequest> request = interactions.read(threadId, null)
                    .flatMap(snapshot -> snapshot.request());
            String state = engine.query(identity).map(status -> status.state()).orElse("");
            if ("WAITING_INPUT".equals(state) && request.isPresent()
                    && request.orElseThrow().status() == InteractionStatus.PENDING) {
                return request.orElseThrow();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Core execution and SQLite Interaction projection did not reach WAITING_INPUT");
    }

    /** 配置本地 Responses Provider 与默认开启的澄清能力，不触碰用户目录或远程服务。 */
    private static String configuration(URI endpoint) {
        return """
                schema_version = 2
                config_revision = 1
                default_access_mode = "full_access"
                default_provider_id = "%s"
                default_model_id = "%s"
                default_reasoning_level = { __ja_null = true }
                mcp_servers = []
                disabled_skills = []
                [subagents]
                is_enabled = true
                provider_id = { __ja_null = true }
                model_id = { __ja_null = true }
                reasoning_level = { __ja_null = true }
                [[providers]]
                provider_id = "%s"
                name = "Clarification loop fixture"
                api = "openai_responses"
                base_url = "%s"
                credential_id = "%s"
                [providers.network_timeouts]
                connect_timeout_ms = 1000
                request_timeout_ms = 10000
                [providers.agent_defaults.context]
                auto_compact = true
                [[providers.models]]
                model_id = "%s"
                name = "Clarification loop fixture"
                model = "test-model"
                reasoning_level_map = {}
                default_reasoning_level = { __ja_null = true }
                [providers.models.capabilities]
                context_window_tokens = 128000
                max_output_tokens = 8192
                """.formatted(PROVIDER_ID, MODEL_ID, PROVIDER_ID, endpoint, CREDENTIAL_ID, MODEL_ID);
    }

    /** 子测试 JVM 运行时隔离配置与数据库路径，结束后逐项还原父进程属性。 */
    private static void setRuntimeProperties(Path home, Map<String, String> previous) throws IOException {
        Map<String, Path> paths = Map.of("ja.home-dir", home, "ja.data-dir", home.resolve("data"),
                "ja.run-dir", home.resolve("run"), "ja.log-dir", home.resolve("logs"));
        for (Map.Entry<String, Path> entry : paths.entrySet()) {
            previous.put(entry.getKey(), System.getProperty(entry.getKey()));
            System.setProperty(entry.getKey(), Files.createDirectories(entry.getValue()).toString());
        }
    }

    /** 事件 sink 仅收集真实 Solon 投影，不替换 Engine 或持久化路径。 */
    private static ConversationTurnEventSink sink(List<TurnEvent> events) {
        return ConversationTurnEventSink.turnEventsOnly(event -> {
            events.add(event);
            return CompletableFuture.completedFuture(null);
        });
    }

    /** 生成 Provider 原生 function-call SSE，令真实 adapter 派发澄清 Tool。 */
    private String toolCallResponse(String responseId, String callId, String toolName, String arguments)
            throws JsonProcessingException {
        String itemId = "item_clarification";
        ObjectNode item = mapper.createObjectNode().put("id", itemId).put("type", "function_call")
                .put("call_id", callId).put("name", toolName).put("arguments", arguments);
        ArrayNode output = mapper.createArrayNode().add(item);
        ObjectNode created = eventPayload("response.created", 0)
                .set("response", mapper.readTree(ModelAdapterTestSupport.openAiResponse(responseId, "in_progress")));
        ObjectNode addedItem = mapper.createObjectNode().put("id", itemId).put("type", "function_call")
                .put("call_id", callId).put("name", toolName).put("arguments", "");
        ObjectNode added = eventPayload("response.output_item.added", 1).put("output_index", 0)
                .set("item", addedItem);
        ObjectNode delta = eventPayload("response.function_call_arguments.delta", 2)
                .put("item_id", itemId).put("output_index", 0).put("delta", arguments);
        ObjectNode doneArguments = eventPayload("response.function_call_arguments.done", 3)
                .put("item_id", itemId).put("output_index", 0).put("arguments", arguments);
        ObjectNode doneItem = eventPayload("response.output_item.done", 4).put("output_index", 0).set("item", item);
        ObjectNode completed = eventPayload("response.completed", 5).set("response",
                mapper.readTree(ModelAdapterTestSupport.openAiResponse(responseId, "completed",
                        new ModelUsage(15, 4, 19), mapper.writeValueAsString(output))));
        return event("response.created", created) + event("response.output_item.added", added)
                + event("response.function_call_arguments.delta", delta)
                + event("response.function_call_arguments.done", doneArguments)
                + event("response.output_item.done", doneItem) + event("response.completed", completed);
    }

    /** 生成最终文本 SSE，验证澄清答案确实返回 Provider 并完成同一 Turn。 */
    private String finalTextResponse(String responseId, String text) throws JsonProcessingException {
        ObjectNode content = mapper.createObjectNode().put("type", "output_text").put("text", text);
        content.set("annotations", mapper.createArrayNode());
        content.set("logprobs", mapper.createArrayNode());
        ObjectNode item = mapper.createObjectNode().put("id", "message_clarification_final")
                .put("type", "message").put("role", "assistant").put("status", "completed");
        item.set("content", mapper.createArrayNode().add(content));
        ObjectNode created = eventPayload("response.created", 0)
                .set("response", mapper.readTree(ModelAdapterTestSupport.openAiResponse(responseId, "in_progress")));
        ObjectNode delta = eventPayload("response.output_text.delta", 1).put("output_index", 0)
                .put("content_index", 0).put("item_id", "message_clarification_final").put("delta", text);
        ObjectNode done = eventPayload("response.output_text.done", 2).put("output_index", 0)
                .put("content_index", 0).put("item_id", "message_clarification_final").put("text", text);
        ObjectNode doneItem = eventPayload("response.output_item.done", 3).put("output_index", 0).set("item", item);
        ObjectNode completed = eventPayload("response.completed", 4).set("response",
                mapper.readTree(ModelAdapterTestSupport.openAiResponse(responseId, "completed",
                        new ModelUsage(20, 5, 25), mapper.writeValueAsString(mapper.createArrayNode().add(item)))));
        return event("response.created", created) + event("response.output_text.delta", delta)
                + event("response.output_text.done", done) + event("response.output_item.done", doneItem)
                + event("response.completed", completed);
    }

    /** 固定 Provider 事件序号，确保 fixture 经过与真实 SSE 相同的协议校验。 */
    private ObjectNode eventPayload(String type, int sequence) {
        return mapper.createObjectNode().put("type", type).put("sequence_number", sequence);
    }

    /** 将 Provider event 编成单个标准 SSE frame。 */
    private String event(String type, ObjectNode payload) throws JsonProcessingException {
        return "event: " + type + "\ndata: " + mapper.writeValueAsString(payload) + "\n\n";
    }

    /** 读取失败子进程的有限诊断尾部，便于定位真实集成断点。 */
    private static String failureOutput(Path output) {
        try {
            String text = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
            return text.length() <= 6000 ? text : text.substring(text.length() - 6000);
        } catch (IOException failure) {
            return "clarification child process failed; output unavailable";
        }
    }
}

