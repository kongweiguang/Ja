// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionStatus;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestionType;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionWait;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.test.JsonValueTestSupport;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationUseCase;
import io.github.kongweiguang.ja.conversation.infrastructure.provider.support.ModelAdapterTestSupport;
import io.github.kongweiguang.ja.conversation.application.service.JaTurnRequestMapper;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnEvent;
import io.github.kongweiguang.ja.conversation.domain.model.TurnResult;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.application.api.ApprovalUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.api.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.conversation.application.gateway.AgentExecutionEvents;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通过真实 Solon、Resolver、Core 与 SQLite 验证权限、审批等待及工具失败纠正闭环。 */
final class JaApprovalExecutionIntegrationTest {
    private static final String PROVIDER_ID = "provider_approval_fixture";
    private static final String MODEL_ID = "model_approval_fixture";
    private static final String CREDENTIAL_ID = "cred_approval_fixture";
    private static final String READ_SENTINEL = "FULL_ACCESS_READ_SENTINEL_4821";
    private static final int CHILD_TIMEOUT_SECONDS = 90;

    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    Path temporary;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> diagnosticStages = new CopyOnWriteArrayList<>();
    private final Map<String, AgentIdentity> diagnosticIdentities = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, List<TurnEvent>> diagnosticEvents = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String stage = "not-started";
    private volatile AgentStore diagnosticStore;
    private volatile int providerCalls;

    /** 父进程只负责隔离 Solon/MyBatis 的进程级静态状态，并在失败时保留脱敏水位文件。 */
    @Test
    void permissionModesResolvePersistentApprovalAndReplayCommittedEvents() throws Exception {
        Path output = temporary.resolve("approval-loop-child.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "--enable-native-access=ALL-UNNAMED", "-Dja.log-dir=" + temporary.resolve("logs"),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                JaApprovalExecutionIntegrationTest.class.getName(), temporary.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            boolean exited = child.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!exited) {
                child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
            assertTrue(exited, () -> diagnosticText(temporary.resolve("approval-diagnostics.txt")));
            assertEquals(0, child.exitValue(), () -> diagnosticText(temporary.resolve("approval-diagnostics.txt")));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    /** 子 JVM 执行真实装配，避免测试依赖 Solon 和 MyBatis 的同进程数据源缓存清理。 */
    public static void main(String[] arguments) throws Throwable {
        if (arguments.length != 1 || !"25".equals(System.getProperty("java.specification.version"))) {
            throw new IllegalStateException("isolated approval test requires one profile path and JDK 25");
        }
        JaApprovalExecutionIntegrationTest fixture = new JaApprovalExecutionIntegrationTest();
        fixture.temporary = Path.of(arguments[0]);
        try {
            fixture.exercisePermissionAndApproval();
        } catch (Throwable failure) {
            fixture.writeFailureDiagnostics(failure);
            throw failure;
        }
    }

    /** 新 Thread 必须先以占位来源创建，再用 revision CAS 取得人工标题所有权，避免自动标题请求抢占顺序响应。 */
    private void exercisePermissionAndApproval() throws Exception {
        Path home = Files.createDirectories(temporary.resolve("home"));
        Map<String, String> previous = new LinkedHashMap<>();
        AtomicInteger calls = new AtomicInteger();
        List<String> providerRequests = new CopyOnWriteArrayList<>();
        stage("starting-loopback");
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback((call, exchange) -> {
            calls.set(call);
            providerCalls = call;
            providerRequests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (call == 1) {
                ModelAdapterTestSupport.sse(exchange,
                        toolCallResponse("resp_full_read_tool", "call_full_read", "read",
                                "{\"path\":\"source.txt\"}"), 31);
            } else if (call == 2) {
                ModelAdapterTestSupport.sse(exchange,
                        finalTextResponse("resp_full_read_final", "FULL_ACCESS_READ_COMPLETE"), 29);
            } else if (call == 3) {
                ModelAdapterTestSupport.sse(exchange,
                        toolCallResponse("resp_approval_allow_tool", "call_approval_allow", "write",
                                "{\"path\":\"allowed.txt\",\"content\":\"APPROVED_WRITE_4821\"}"), 31);
            } else if (call == 4) {
                ModelAdapterTestSupport.sse(exchange,
                        finalTextResponse("resp_approval_allow_final", "APPROVAL_ALLOW_COMPLETE"), 29);
            } else if (call == 5) {
                ModelAdapterTestSupport.sse(exchange,
                        toolCallResponse("resp_approval_deny_tool", "call_approval_deny", "exec_command",
                                "{\"cmd\":\"Write-Output 'Bearer approval-secret-4821'\"}"), 31);
            } else if (call == 6) {
                ModelAdapterTestSupport.sse(exchange,
                        finalTextResponse("resp_approval_deny_final", "APPROVAL_DENY_COMPLETE"), 29);
            } else if (call == 7) {
                ModelAdapterTestSupport.sse(exchange,
                        toolCallResponse("resp_unknown_tool", "call_unknown_e2e_tool", "unknown_e2e_tool", "{}"), 31);
            } else if (call == 8) {
                ModelAdapterTestSupport.sse(exchange,
                        toolCallResponse("resp_unknown_tool_recovery", "call_unknown_e2e_recovery", "read",
                                "{\"path\":\"source.txt\"}"), 31);
            } else if (call == 9) {
                ModelAdapterTestSupport.sse(exchange,
                        finalTextResponse("resp_unknown_tool_final", "UNKNOWN_TOOL_RECOVERED"), 29);
            } else {
                ModelAdapterTestSupport.status(exchange, 500);
            }
        })) {
            Files.writeString(home.resolve("config.toml"), configuration(AccessMode.APPROVAL_REQUIRED,
                    server.baseUri()));
            setRuntimeProperties(home, previous);
            stage("starting-solo");
            Solon.start(App.class, new String[0], started -> started.enableHttp(false));

            ConfigurationUseCase configuration = Solon.context().getBean(ConfigurationUseCase.class);
            assertEquals(ConfigurationUseCase.LayerStatus.VALID, configuration.read(null).user().status());
            configuration.setCredential(CREDENTIAL_ID, "isolated-test-key", configuration.read(null).credentialVersion());
            WorkspaceUseCase workspaces = Solon.context().getBean(WorkspaceUseCase.class);
            ThreadUseCase threads = Solon.context().getBean(ThreadUseCase.class);
            TurnUseCase turns = Solon.context().getBean(TurnUseCase.class);
            AgentExecutionEvents events = Solon.context().getBean(AgentExecutionEvents.class);
            diagnosticStore = (AgentStore) Solon.context().getBean("jaAgentStore");
            stage("solon-ready");

            String threadId = "thr_approval_full_access";
            String turnId = "turn_approval_full_access";
            io.github.kongweiguang.ja.workspace.domain.model.Workspace workspace =
                    workspaces.createSessionWorkspace(threadId);
            Files.writeString(workspace.root().resolve("source.txt"), READ_SENTINEL);
            ThreadPreferences preferences = new ThreadPreferences(PROVIDER_ID, MODEL_ID, null,
                    AccessMode.FULL_ACCESS, CollaborationMode.DEFAULT,
                    ThreadPreferences.TitleSource.PLACEHOLDER);
            ThreadSummary thread = threads.createThread(new ThreadSummary.Creation(
                    threadId, workspace.workspaceId(), "Full access approval fixture", preferences, Instant.now()));
            thread = threads.renameThread(threadId, thread.title(), thread.revision());
            TurnStartRequest request = new TurnStartRequest(threadId, turnId, workspace.workspaceId(), workspace.root(),
                    new UserContent(List.of(new TextContent("Read source.txt and report its contents."))),
                    PROVIDER_ID, MODEL_ID, null, AccessMode.FULL_ACCESS, CollaborationMode.DEFAULT,
                    thread.revision(), 0, Instant.now(), Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), false);
            List<TurnEvent> liveEvents = new CopyOnWriteArrayList<>();
            AgentIdentity identity = JaTurnRequestMapper.identity(threadId, turnId);
            diagnosticIdentities.put("full_access", identity);
            diagnosticEvents.put("full_access", liveEvents);
            stage("full-access-turn-admitted");
            TurnUseCase.Accepted accepted = turns.start(request, sink(liveEvents));
            stage("full-access-turn-running");
            TurnResult result;
            try {
                result = accepted.completion().toCompletableFuture().get(25, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException failure) {
                recordExecutionDiagnostics("full_access_timeout", identity, liveEvents);
                throw failure;
            } catch (java.util.concurrent.ExecutionException failure) {
                recordExecutionDiagnostics("full_access_failed", identity, liveEvents);
                throw failure;
            }
            providerCalls = calls.get();
            stage("full-access-terminal-projected");
            assertEquals(TurnState.COMPLETED, result.state());
            assertEquals("FULL_ACCESS_READ_COMPLETE", result.terminal().finalMessage().text());
            assertEquals(2, calls.get(), "the read result must return to the Provider for a second request");
            assertTrue(providerRequests.get(1).contains(READ_SENTINEL),
                    "the real read result must be present in the continued Provider request");
            assertTrue(liveEvents.stream().noneMatch(TurnEvent.ApprovalRequested.class::isInstance),
                    "FULL_ACCESS must not create a persisted approval wait for a built-in read");
            assertEquals(1, liveEvents.stream().filter(TurnEvent.ToolStarted.class::isInstance).count(),
                    "the actual Tool dispatch must reach the event sink");
            List<TurnEvent.ToolBatchCommitted> toolCompletions = liveEvents.stream()
                    .filter(TurnEvent.ToolBatchCommitted.class::isInstance)
                    .map(TurnEvent.ToolBatchCommitted.class::cast).toList();
            assertEquals(1, toolCompletions.size(), "the committed Tool result must reach the event sink");
            assertEquals(ToolOutcome.SUCCEEDED, toolCompletions.getFirst().results().getFirst().outcome());
            assertEquals("call_full_read", toolCompletions.getFirst().results().getFirst().callId());
            assertEquals(1, liveEvents.stream().filter(TurnEvent.Terminal.class::isInstance).count(),
                    "the exact terminal projection must reach the same event sink");
            assertTrue(turns.awaitQuiescence(Duration.ZERO), "terminal delivery releases the local Turn owner");

            verifyDurableReplay(events, identity, threadId, liveEvents);
            recordWatermarks("full_access_verified", identity, liveEvents);
            assertEquals(2, server.calls());

            exerciseApprovalDecision(threads, turns, workspaces, events, calls, providerRequests,
                    ApprovalDecision.APPROVE, "allow", "APPROVED_WRITE_4821", "APPROVAL_ALLOW_COMPLETE");
            exerciseApprovalDecision(threads, turns, workspaces, events, calls, providerRequests,
                    ApprovalDecision.DENY, "deny", "DENIED_WRITE_4821", "APPROVAL_DENY_COMPLETE");
            exerciseUnknownToolCorrection(threads, turns, workspaces, events, calls, providerRequests);
            assertEquals(9, server.calls(), "each continuation must reach its expected Provider request");
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

    /** 以同一 execution 的第二个订阅者从 durable cursor 零重放，检查断线后的 Tool 与终态闭环。 */
    private void verifyDurableReplay(AgentExecutionEvents events, AgentIdentity identity, String threadId,
                                     List<TurnEvent> liveEvents) throws Exception {
        List<TurnEvent> replayed = new CopyOnWriteArrayList<>();
        try (AutoCloseable subscription = events.attach(identity, sink(replayed))) {
            awaitEvent(replayed, TurnEvent.Terminal.class::isInstance, "durable terminal replay");
        }
        assertEquals(liveEvents.stream().filter(TurnEvent.ToolStarted.class::isInstance).count(),
                replayed.stream().filter(TurnEvent.ToolStarted.class::isInstance).count());
        assertEquals(liveEvents.stream().filter(TurnEvent.ToolBatchCommitted.class::isInstance).count(),
                replayed.stream().filter(TurnEvent.ToolBatchCommitted.class::isInstance).count());
        assertEquals(liveEvents.stream().filter(TurnEvent.ApprovalRequested.class::isInstance).count(),
                replayed.stream().filter(TurnEvent.ApprovalRequested.class::isInstance).count());
        assertEquals(liveEvents.stream().filter(TurnEvent.ApprovalResolved.class::isInstance).count(),
                replayed.stream().filter(TurnEvent.ApprovalResolved.class::isInstance).count());
        assertEquals(liveEvents.stream().filter(TurnEvent.Terminal.class::isInstance).count(),
                replayed.stream().filter(TurnEvent.Terminal.class::isInstance).count());
        ThreadSnapshot snapshot = Solon.context().getBean(ThreadUseCase.class)
                .readThread(threadId, null, 64).orElseThrow();
        assertTrue(snapshot.items().stream().anyMatch(ThreadSnapshot.ToolItem.class::isInstance),
                "the SQLite history projection must retain the committed Tool item");
    }

    /** 真实 Engine/SQLite 路径先把未知工具失败交回模型，再执行纠正后的已注册工具并完成 Turn。 */
    private void exerciseUnknownToolCorrection(ThreadUseCase threads, TurnUseCase turns,
            WorkspaceUseCase workspaces, AgentExecutionEvents events, AtomicInteger calls,
            List<String> providerRequests) throws Exception {
        String threadId = "thr_unknown_tool_recovery";
        String turnId = "turn_unknown_tool_recovery";
        String sentinel = "UNKNOWN_TOOL_RECOVERY_SENTINEL";
        io.github.kongweiguang.ja.workspace.domain.model.Workspace workspace =
                workspaces.createSessionWorkspace(threadId);
        Files.writeString(workspace.root().resolve("source.txt"), sentinel, StandardCharsets.UTF_8);
        ThreadPreferences preferences = new ThreadPreferences(PROVIDER_ID, MODEL_ID, null,
                AccessMode.FULL_ACCESS, CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.PLACEHOLDER);
        ThreadSummary thread = threads.createThread(new ThreadSummary.Creation(
                threadId, workspace.workspaceId(), "Unknown Tool recovery", preferences, Instant.now()));
        thread = threads.renameThread(threadId, thread.title(), thread.revision());
        TurnStartRequest request = new TurnStartRequest(threadId, turnId, workspace.workspaceId(), workspace.root(),
                new UserContent(List.of(new TextContent(
                        "Recover from the unavailable Tool, inspect source.txt, and report it."))),
                PROVIDER_ID, MODEL_ID, null, AccessMode.FULL_ACCESS, CollaborationMode.DEFAULT,
                thread.revision(), 0, Instant.now(), Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), false);
        List<TurnEvent> liveEvents = new CopyOnWriteArrayList<>();
        AgentIdentity identity = JaTurnRequestMapper.identity(threadId, turnId);
        diagnosticIdentities.put("unknown_tool_recovery", identity);
        diagnosticEvents.put("unknown_tool_recovery", liveEvents);
        int firstProviderCall = calls.get() + 1;
        TurnUseCase.Accepted accepted = turns.start(request, sink(liveEvents));
        stage("unknown-tool-recovery-turn-running");
        TurnResult result;
        try {
            result = accepted.completion().toCompletableFuture().get(25, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException failure) {
            recordExecutionDiagnostics("unknown_tool_recovery_timeout", identity, liveEvents);
            throw failure;
        } catch (java.util.concurrent.ExecutionException failure) {
            recordExecutionDiagnostics("unknown_tool_recovery_failed", identity, liveEvents);
            throw failure;
        }

        providerCalls = calls.get();
        assertEquals(TurnState.COMPLETED, result.state());
        assertEquals("UNKNOWN_TOOL_RECOVERED", result.terminal().finalMessage().text());
        assertEquals(firstProviderCall + 2, calls.get(),
                "failure correction, legal Tool result, and final answer each need a Provider call");
        String correctedRequest = providerRequests.get(firstProviderCall);
        assertTrue(correctedRequest.contains("call_unknown_e2e_tool"));
        assertTrue(correctedRequest.contains("This Tool is not available"),
                "the failed unknown Tool result must reach the next Provider request");
        String finalRequest = providerRequests.get(firstProviderCall + 1);
        assertTrue(finalRequest.contains(sentinel), "the registered Tool result must reach the final Provider request");

        List<TurnEvent.ToolBatchCommitted> batches = liveEvents.stream()
                .filter(TurnEvent.ToolBatchCommitted.class::isInstance)
                .map(TurnEvent.ToolBatchCommitted.class::cast).toList();
        assertEquals(2, batches.size());
        assertEquals("call_unknown_e2e_tool", batches.getFirst().results().getFirst().callId());
        assertEquals(ToolOutcome.FAILED, batches.getFirst().results().getFirst().outcome());
        assertEquals("TOOL_NOT_AVAILABLE", batches.getFirst().results().getFirst().errorCode());
        assertEquals("call_unknown_e2e_recovery", batches.getLast().results().getFirst().callId());
        assertEquals(ToolOutcome.SUCCEEDED, batches.getLast().results().getFirst().outcome());
        assertEquals(1, liveEvents.stream().filter(TurnEvent.ToolStarted.class::isInstance).count(),
                "the unknown Tool is never dispatched and the corrected Tool starts once");
        assertEquals(1, liveEvents.stream().filter(TurnEvent.Terminal.class::isInstance).count());
        assertTrue(turns.awaitQuiescence(Duration.ZERO));

        AgentStore.ExecutionKey key = executionKey(identity);
        AgentStore.ExecutionSnapshot execution = diagnosticStore.loadExecution(key).orElseThrow();
        List<AgentStore.StoredRecord> records = diagnosticStore.readRecords(AgentStore.RecordQuery.withDefaultMaxBytes(
                key, 0, execution.lastRecordSequence(), 2_048)).records();
        assertEquals(1, records.stream().filter(record -> record.recordType().equals("agent.tool.result")
                && JsonValueTestSupport.containsText(record.payload(), "call_unknown_e2e_tool")).count());
        assertEquals(1, records.stream().filter(record -> record.recordType().equals("agent.tool.message")
                && JsonValueTestSupport.containsText(record.payload(), "call_unknown_e2e_tool")).count());
        verifyDurableReplay(events, identity, threadId, liveEvents);
        recordWatermarks("unknown_tool_recovery_verified", identity, liveEvents);
    }

    /** 验证文件写入审批与命令脱敏审批均沿冻结展示投影；拒绝场景不能泄露原始 Bearer 文本。 */
    private void exerciseApprovalDecision(ThreadUseCase threads, TurnUseCase turns, WorkspaceUseCase workspaces,
            AgentExecutionEvents events, AtomicInteger calls, List<String> providerRequests,
            ApprovalDecision decision, String scenario, String content, String finalText) throws Exception {
        String threadId = "thr_approval_" + scenario;
        String turnId = "turn_approval_" + scenario;
        String targetPath = "allow".equals(scenario) ? "allowed.txt" : "denied.txt";
        io.github.kongweiguang.ja.workspace.domain.model.Workspace workspace =
                workspaces.createSessionWorkspace(threadId);
        ThreadPreferences preferences = new ThreadPreferences(PROVIDER_ID, MODEL_ID, null,
                AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT,
                ThreadPreferences.TitleSource.PLACEHOLDER);
        ThreadSummary thread = threads.createThread(new ThreadSummary.Creation(
                threadId, workspace.workspaceId(), "Approval " + scenario, preferences, Instant.now()));
        thread = threads.renameThread(threadId, thread.title(), thread.revision());
        TurnStartRequest request = new TurnStartRequest(threadId, turnId, workspace.workspaceId(), workspace.root(),
                new UserContent(List.of(new TextContent("Write the requested file."))),
                PROVIDER_ID, MODEL_ID, null, AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT,
                thread.revision(), 0, Instant.now(), Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), false);
        List<TurnEvent> liveEvents = new CopyOnWriteArrayList<>();
        AgentIdentity identity = JaTurnRequestMapper.identity(threadId, turnId);
        diagnosticIdentities.put(scenario, identity);
        diagnosticEvents.put(scenario, liveEvents);
        ConversationTurnEventSink eventSink = sink(liveEvents);
        TurnUseCase.Accepted accepted = turns.start(request, eventSink);
        stage("approval-" + scenario + "-turn-running");

        try {
            awaitEvent(liveEvents, TurnEvent.ApprovalRequested.class::isInstance, scenario + " approval request");
            TurnEvent.ApprovalRequested approval = liveEvents.stream()
                    .filter(TurnEvent.ApprovalRequested.class::isInstance)
                    .map(TurnEvent.ApprovalRequested.class::cast).findFirst().orElseThrow();
            assertEquals("allow".equals(scenario) ? "write" : "exec_command", approval.toolName());
            assertEquals("call_approval_" + scenario, approval.callId());
            assertEquals("allow".equals(scenario) ? "WRITE" : "SHELL", approval.presentation().kind().name());
            assertEquals("PENDING", approval.presentation().status().name());
            if ("allow".equals(scenario)) {
                assertEquals(targetPath, approval.presentation().relativePaths().getFirst());
            } else {
                assertEquals("Write-Output 'Bearer [REDACTED]'", approval.presentation().command());
                assertEquals(approval.presentation().command(), approval.presentation().inputPreview());
                assertFalse(approval.toString().contains("approval-secret-4821"),
                        "the frozen ApprovalRequested event must not expose raw Provider arguments");
            }
            assertEquals("请确认是否允许执行此次工具调用。", approval.reason());
            assertTrue(approval.expiresAt().isAfter(Instant.now()));
            AgentExecutionStatus status = Solon.context().getBean(AgentEngine.class).query(identity).orElseThrow();
            assertEquals("WAITING_APPROVAL", status.state(), "the Engine must persist an approval wait state");
            InteractionWait wait = persistedApprovalWait(identity, approval.approvalId());
            assertEquals(InteractionStatus.PENDING, wait.status());
            assertEquals(approval.callId(), wait.toolCallId());
            assertEquals(1, wait.questions().size());
            io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestion question =
                    wait.questions().getFirst();
            assertEquals("question_approval", question.questionId());
            assertEquals(InteractionQuestionType.SINGLE, question.type());
            assertTrue(question.required());
            assertFalse(question.allowFreeText());
            assertEquals(Set.of("option_approve", "option_deny"), Set.copyOf(question.options().stream()
                    .map(option -> option.optionId()).toList()));
            assertEquals(Set.of("kind", "expiresAt"), wait.attributes().members().keySet());
            assertEquals("approval", ((JsonText) wait.attributes().get("kind")).value());
            Instant storedExpiry = Instant.parse(((JsonText) wait.attributes().get("expiresAt")).value());
            assertEquals(approval.expiresAt(), storedExpiry);
            assertTrue(storedExpiry.isAfter(Instant.now()));
            assertTrue(Duration.between(Instant.now(), storedExpiry).compareTo(Duration.ofMinutes(5)) <= 0,
                    "approval_required uses the fixed five-minute validity policy");
            assertFalse(accepted.completion().toCompletableFuture().isDone(),
                    "approval wait must not complete the original Turn before a user decision");
            assertFalse(Files.exists(workspace.root().resolve(targetPath)),
                    "a write Tool must not execute before approval");
            int approvalCall = "allow".equals(scenario) ? 3 : 5;
            assertEquals(approvalCall, calls.get(),
                    "the Provider must remain suspended at the approval request");

            long before = calls.get();
            long approvalRevision = approval.context().threadRevision();
            String operationId = "op_" + (decision == ApprovalDecision.APPROVE ? "1" : "2").repeat(32);
            String fingerprint = approvalFingerprint(threadId, turnId, approval.approvalId(), decision,
                    approvalRevision);
            ApprovalUseCase approvalUseCase = Solon.context().getBean(ApprovalUseCase.class);
            ApprovalUseCase.Resolution resolution = approvalUseCase.resolve(threadId, turnId, approval.approvalId(),
                    approvalRevision, decision, operationId, fingerprint, eventSink)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(resolution.threadRevision() >= approvalRevision,
                    "the approval receipt must return its committed Thread revision");
            TurnResult result;
            try {
                result = accepted.completion().toCompletableFuture().get(25, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException failure) {
                recordExecutionDiagnostics("approval_" + scenario + "_timeout", identity, liveEvents);
                throw failure;
            } catch (java.util.concurrent.ExecutionException failure) {
                recordExecutionDiagnostics("approval_" + scenario + "_failed", identity, liveEvents);
                throw failure;
            }
            providerCalls = calls.get();
            assertEquals(TurnState.COMPLETED, result.state());
            assertEquals(finalText, result.terminal().finalMessage().text());
            assertEquals(before + 1, calls.get(), "the same Turn resumes with exactly one final Provider call");
            String continuationRequest = providerRequests.get((int) before);
            assertTrue(continuationRequest.contains("\"type\":\"function_call_output\""),
                    "approval resolution must feed the actual Tool result into the continued Provider request");
            assertEquals(1, liveEvents.stream().filter(TurnEvent.ApprovalRequested.class::isInstance).count());
            List<TurnEvent.ApprovalResolved> resolutions = liveEvents.stream()
                    .filter(TurnEvent.ApprovalResolved.class::isInstance)
                    .map(TurnEvent.ApprovalResolved.class::cast).toList();
            assertEquals(1, resolutions.size());
            assertEquals(approval.approvalId(), resolutions.getFirst().approvalId());
            assertEquals(decision, resolutions.getFirst().decision());
            List<TurnEvent.ToolBatchCommitted> committed = liveEvents.stream()
                    .filter(TurnEvent.ToolBatchCommitted.class::isInstance)
                    .map(TurnEvent.ToolBatchCommitted.class::cast).toList();
            assertEquals(1, committed.size());
            TurnEvent.ToolBatchResult toolResult = committed.getFirst().results().getFirst();
            assertEquals("call_approval_" + scenario, toolResult.callId());
            if (decision == ApprovalDecision.APPROVE) {
                assertEquals(ToolOutcome.SUCCEEDED, toolResult.outcome());
                assertTrue(Files.exists(workspace.root().resolve(targetPath)));
                assertEquals(content, Files.readString(workspace.root().resolve(targetPath), StandardCharsets.UTF_8));
                assertEquals(1, liveEvents.stream().filter(TurnEvent.ToolStarted.class::isInstance).count());
                assertTrue(continuationRequest.contains("call_approval_allow"));
            } else {
                assertEquals(ToolOutcome.FAILED, toolResult.outcome());
                assertEquals("TOOL_APPROVAL_DENIED", toolResult.errorCode());
                assertFalse(Files.exists(workspace.root().resolve(targetPath)),
                        "a denied write must never cross the Tool execution boundary");
                assertTrue(continuationRequest.contains("The user did not approve this Tool call."),
                        "the denial result must return to the Provider for correction/continuation");
            }
            assertEquals(1, liveEvents.stream().filter(TurnEvent.Terminal.class::isInstance).count());
            assertTrue(turns.awaitQuiescence(Duration.ZERO));
            verifyDurableReplay(events, identity, threadId, liveEvents);
            recordWatermarks("approval_" + scenario + "_verified", identity, liveEvents);
        } catch (Exception | Error failure) {
            recordExecutionDiagnostics("approval_" + scenario + "_assertion_failed", identity, liveEvents);
            throw failure;
        }
    }

    /** 从当前执行的固定记录水位恢复等待本体，拒绝只靠内存事件冒充已持久化审批。 */
    private InteractionWait persistedApprovalWait(AgentIdentity identity, String approvalId) {
        AgentStore.ExecutionKey key = executionKey(identity);
        AgentStore.ExecutionSnapshot snapshot = diagnosticStore.loadExecution(key).orElseThrow();
        ExecutionControlCodec codec = Solon.context().getBean("jaExecutionControlCodec");
        InteractionWait found = null;
        long after = 0;
        while (after < snapshot.lastRecordSequence()) {
            AgentStore.RecordPage page = diagnosticStore.readRecords(AgentStore.RecordQuery.withDefaultMaxBytes(
                    key, after, snapshot.lastRecordSequence(), 256));
            for (AgentStore.StoredRecord record : page.records()) {
                if ("agent.interaction.wait".equals(record.recordType())) {
                    InteractionWait candidate = codec.decodeWait(record);
                    if (candidate.interactionId().equals(approvalId)) {
                        found = candidate;
                    }
                }
            }
            if (!page.hasMore()) {
                break;
            }
            if (page.nextSequence() <= after) {
                throw new IllegalStateException("approval wait page did not advance");
            }
            after = page.nextSequence();
        }
        return java.util.Objects.requireNonNull(found, "persisted approval wait");
    }

    /** 为同一裁决生成稳定、无正文的 SHA-256 指纹，满足客户端幂等回执的完整性约束。 */
    private static String approvalFingerprint(String threadId, String turnId, String approvalId,
            ApprovalDecision decision, long revision) {
        String request = String.join("\u0000", "approval/respond", threadId, turnId, approvalId,
                decision.name(), Long.toString(revision));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(request.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    /** 把会话和凭据路径固定在独立子进程的临时目录，并在父 JVM 退出前恢复原属性。 */
    private static void setRuntimeProperties(Path home, Map<String, String> previous) throws IOException {
        Map<String, Path> paths = Map.of("ja.home-dir", home, "ja.data-dir", home.resolve("data"),
                "ja.run-dir", home.resolve("run"), "ja.log-dir", home.resolve("logs"));
        for (Map.Entry<String, Path> entry : paths.entrySet()) {
            previous.put(entry.getKey(), System.getProperty(entry.getKey()));
            System.setProperty(entry.getKey(), Files.createDirectories(entry.getValue()).toString());
        }
    }

    /** 构建使用真实 provider adapter 的 Ja event sink，不在测试中替换循环、Router 或权限策略。 */
    private static ConversationTurnEventSink sink(List<TurnEvent> events) {
        return ConversationTurnEventSink.turnEventsOnly(event -> {
            events.add(event);
            return CompletableFuture.completedFuture(null);
        });
    }

    /** 生成 Provider 原生 function-call SSE，保证模型请求经 Ja OpenAI Responses adapter 正常归约。 */
    private String toolCallResponse(String responseId, String callId, String toolName, String arguments)
            throws JsonProcessingException {
        String itemId = "item_" + callId.substring("call_".length());
        ObjectNode item = mapper.createObjectNode().put("id", itemId).put("type", "function_call")
                .put("call_id", callId).put("name", toolName).put("arguments", arguments);
        ArrayNode output = mapper.createArrayNode().add(item);
        ObjectNode created = eventPayload("response.created", 0)
                .set("response", readTree(ModelAdapterTestSupport.openAiResponse(responseId, "in_progress")));
        ObjectNode addedItem = mapper.createObjectNode().put("id", itemId).put("type", "function_call")
                .put("call_id", callId).put("name", toolName).put("arguments", "");
        ObjectNode added = eventPayload("response.output_item.added", 1).put("output_index", 0)
                .set("item", addedItem);
        ObjectNode delta = eventPayload("response.function_call_arguments.delta", 2)
                .put("item_id", itemId).put("output_index", 0).put("delta", arguments);
        ObjectNode doneArguments = eventPayload("response.function_call_arguments.done", 3)
                .put("item_id", itemId).put("output_index", 0).put("arguments", arguments);
        ObjectNode doneItem = eventPayload("response.output_item.done", 4).put("output_index", 0)
                .set("item", item);
        ObjectNode completed = eventPayload("response.completed", 5).set("response",
                readTree(ModelAdapterTestSupport.openAiResponse(responseId, "completed",
                        new ModelUsage(15, 4, 19), mapper.writeValueAsString(output))));
        return event("response.created", created) + event("response.output_item.added", added)
                + event("response.function_call_arguments.delta", delta)
                + event("response.function_call_arguments.done", doneArguments)
                + event("response.output_item.done", doneItem) + event("response.completed", completed);
    }

    /** 生成一轮最终文本回复，供首个真实 Tool 结果之后的 Provider continuation 收口。 */
    private String finalTextResponse(String responseId, String text) throws JsonProcessingException {
        ObjectNode content = mapper.createObjectNode().put("type", "output_text").put("text", text);
        content.set("annotations", mapper.createArrayNode());
        content.set("logprobs", mapper.createArrayNode());
        ObjectNode item = mapper.createObjectNode().put("id", "message_1").put("type", "message")
                .put("role", "assistant").put("status", "completed");
        item.set("content", mapper.createArrayNode().add(content));
        ObjectNode created = eventPayload("response.created", 0)
                .set("response", readTree(ModelAdapterTestSupport.openAiResponse(responseId, "in_progress")));
        ObjectNode delta = eventPayload("response.output_text.delta", 1).put("output_index", 0)
                .put("content_index", 0).put("item_id", "message_1").put("delta", text);
        ObjectNode done = eventPayload("response.output_text.done", 2).put("output_index", 0)
                .put("content_index", 0).put("item_id", "message_1").put("text", text);
        ObjectNode doneItem = eventPayload("response.output_item.done", 3).put("output_index", 0)
                .set("item", item);
        ObjectNode completed = eventPayload("response.completed", 4).set("response",
                readTree(ModelAdapterTestSupport.openAiResponse(responseId, "completed",
                        new ModelUsage(20, 5, 25), mapper.writeValueAsString(mapper.createArrayNode().add(item)))));
        return event("response.created", created) + event("response.output_text.delta", delta)
                + event("response.output_text.done", done) + event("response.output_item.done", doneItem)
                + event("response.completed", completed);
    }

    /** 构造带严格序号的 Provider event；每次 fixture 都与正式 JSON/SSE 解析器走同一字节协议。 */
    private ObjectNode eventPayload(String type, int sequence) {
        return mapper.createObjectNode().put("type", type).put("sequence_number", sequence);
    }

    /** 序列化一个真实 SSE event，保留 Provider 行边界和 data JSON 的正确转义。 */
    private String event(String type, ObjectNode payload) throws JsonProcessingException {
        return "event: " + type + "\ndata: " + mapper.writeValueAsString(payload) + "\n\n";
    }

    /** 由共享响应构造器读取合法 JSON 树，使 function-call 完成体与流式事件保持一致。 */
    private JsonNode readTree(String json) throws JsonProcessingException {
        return mapper.readTree(json);
    }

    /** 等待真实异步事件投影到达；超时由持久水位诊断解释，不以轮询间隔掩盖缺失提交。 */
    private static void awaitEvent(List<TurnEvent> events, Predicate<TurnEvent> predicate, String label)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (events.stream().anyMatch(predicate)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(label + " was not projected");
    }

    /** 从用户可见 Thread/Turn ID 恢复租户复合键，查询边界与生产事件桥使用同一身份映射。 */
    private static AgentStore.ExecutionKey executionKey(AgentIdentity identity) {
        return new AgentStore.ExecutionKey(new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), "user_local"), identity.turnId());
    }

    /** 记录阶段、execution/event/record 水位与事件种类计数，不写正文、路径、凭据或异常消息。 */
    private void recordWatermarks(String name, AgentIdentity identity, List<TurnEvent> events) {
        diagnosticStages.add(name + ":events=" + eventCounts(events));
        AgentStore.ExecutionKey key = executionKey(identity);
        diagnosticStore.loadExecution(key).ifPresent(execution -> {
            AgentStore.ConversationSnapshot conversation =
                    diagnosticStore.loadConversation(key.conversation()).orElseThrow();
            diagnosticStages.add(name + ":state=" + execution.state() + ":mutation="
                    + execution.mutationVersion() + ":lastRecord=" + execution.lastRecordSequence()
                    + ":eventSequence=" + conversation.eventSequence() + ":recordSequence="
                    + conversation.recordSequence());
        });
    }

    /** 仅输出数量与稳定事件身份水位，避免诊断文件包含审批提示、工具参数或用户正文。 */
    private static String eventCounts(List<TurnEvent> events) {
        String approvalFrames = String.join("|", events.stream()
                .filter(TurnEvent.ApprovalRequested.class::isInstance)
                .map(TurnEvent.ApprovalRequested.class::cast)
                .map(event -> event.context().eventId() + "@" + event.context().threadRevision()
                        + "/" + event.context().turnMutationVersion() + "#" + event.approvalId())
                .toList());
        return "total=" + events.size()
                + ",toolStarted=" + events.stream().filter(TurnEvent.ToolStarted.class::isInstance).count()
                + ",toolCommitted=" + events.stream().filter(TurnEvent.ToolBatchCommitted.class::isInstance).count()
                + ",approvalRequested=" + events.stream().filter(TurnEvent.ApprovalRequested.class::isInstance).count()
                + ",approvalResolved=" + events.stream().filter(TurnEvent.ApprovalResolved.class::isInstance).count()
                + ",terminal=" + events.stream().filter(TurnEvent.Terminal.class::isInstance).count()
                + ",approvalFrames=" + approvalFrames
                + ",kinds=" + String.join("|", events.stream().map(event -> event.getClass().getSimpleName())
                        .distinct().sorted().toList());
    }

    /** 在 Solon 关闭数据库连接前快照执行水位；诊断读取失败不能遮盖原执行异常。 */
    private void recordExecutionDiagnostics(String name, AgentIdentity identity, List<TurnEvent> events) {
        try {
            recordWatermarks(name, identity, events);
        } catch (RuntimeException failure) {
            diagnosticStages.add(name + ":storeReadFailure=" + failure.getClass().getSimpleName());
        }
    }

    /** 使用当前单机场景真实配置和本地 Provider 地址，不读取用户配置目录或网络凭据。 */
    private static String configuration(AccessMode defaultMode, URI endpoint) {
        return """
                schema_version = 2
                config_revision = 1
                default_access_mode = "%s"
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
                name = "Approval loop fixture"
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
                name = "Approval loop fixture"
                model = "test-model"
                reasoning_level_map = {}
                default_reasoning_level = { __ja_null = true }
                [providers.models.capabilities]
                context_window_tokens = 128000
                max_output_tokens = 8192
                """.formatted(defaultMode.name().toLowerCase(Locale.ROOT), PROVIDER_ID, MODEL_ID,
                PROVIDER_ID, endpoint, CREDENTIAL_ID, MODEL_ID);
    }

    /** 每个失败阶段都保留脱敏 execution 水位与堆栈位置，避免根因调查依赖被清理的测试目录。 */
    private void writeFailureDiagnostics(Throwable failure) {
        List<String> lines = new ArrayList<>();
        lines.add("stage=" + stage);
        lines.add("providerCalls=" + providerCalls);
        lines.addAll(diagnosticStages);
        for (Map.Entry<String, AgentIdentity> entry : diagnosticIdentities.entrySet()) {
            lines.add("turn=" + entry.getKey() + ":events=" + eventCounts(
                    diagnosticEvents.getOrDefault(entry.getKey(), List.of())));
            if (diagnosticStore != null) {
                try {
                    AgentStore.ExecutionKey key = executionKey(entry.getValue());
                    diagnosticStore.loadExecution(key).ifPresent(execution -> {
                        AgentStore.ConversationSnapshot conversation =
                                diagnosticStore.loadConversation(key.conversation()).orElseThrow();
                        lines.add("turn=" + entry.getKey() + ":state=" + execution.state() + ":mutation="
                                + execution.mutationVersion() + ":lastRecord=" + execution.lastRecordSequence()
                                + ":eventSequence=" + conversation.eventSequence() + ":recordSequence="
                                + conversation.recordSequence());
                    });
                } catch (RuntimeException diagnosticFailure) {
                    lines.add("turn=" + entry.getKey() + ":storeReadFailure="
                            + diagnosticFailure.getClass().getSimpleName());
                }
            }
        }
        Throwable cause = failure;
        while (cause != null) {
            lines.add("failureType=" + cause.getClass().getName());
            for (int index = 0; index < Math.min(8, cause.getStackTrace().length); index++) {
                StackTraceElement frame = cause.getStackTrace()[index];
                lines.add("frame=" + frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber());
            }
            cause = cause.getCause();
        }
        try {
            Files.write(temporary.resolve("approval-diagnostics.txt"), lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 原始故障仍由子进程 exit code 暴露；诊断文件失败不替换权威测试错误。
        }
    }

    /** 父进程失败消息只读取专门脱敏的状态文件，不把 Solon 日志或请求正文带入测试输出。 */
    private static String diagnosticText(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8)
                    : "child diagnostic file was not written";
        } catch (IOException failure) {
            return "child diagnostic read failed: " + failure.getClass().getSimpleName();
        }
    }

    /** 持续记录阶段名，超时或强制终止后仍能区分启动、准入、执行和 durable replay。 */
    private void stage(String value) {
        stage = value;
        try {
            List<String> lines = new ArrayList<>();
            lines.add("stage=" + stage);
            lines.add("providerCalls=" + providerCalls);
            lines.addAll(diagnosticStages);
            Files.write(temporary.resolve("approval-diagnostics.txt"), lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 阶段记录仅用于故障诊断，不影响测试中真实 Engine/Store 的执行与结算。
        }
    }
}
