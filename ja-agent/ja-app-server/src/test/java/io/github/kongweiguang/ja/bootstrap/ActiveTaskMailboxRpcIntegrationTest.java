// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.collaboration.application.service.NativeHostProcessFixture;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationUseCase;
import io.github.kongweiguang.ja.conversation.infrastructure.provider.support.ModelAdapterTestSupport;
import io.github.kongweiguang.ja.conversation.application.service.JaTurnRequestMapper;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.collaboration.application.api.TaskUseCase;
import io.github.kongweiguang.ja.transport.rpc.handler.HandshakeHandler;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.Solon;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用真实 WebSocket JA-RPC、Solon、SQLite 与 AgentEngine 固定活动 Task 邮箱的单次消费边界。 */
final class ActiveTaskMailboxRpcIntegrationTest {
    private static final String PROVIDER_ID = "provider_active_mailbox_fixture";
    private static final String MODEL_ID = "model_active_mailbox_fixture";
    private static final String CREDENTIAL_ID = "cred_active_mailbox_fixture";
    private static final String SOURCE_TEXT = "ACTIVE_MAILBOX_SOURCE_SENTINEL_3157";
    private static final String MAILBOX_TEXT = "ACTIVE_MAILBOX_RPC_MESSAGE_7294";
    private static final String INITIAL_TEXT = "Read source.txt and report its contents.";
    private static final String FINAL_TEXT = "ACTIVE_MAILBOX_CONTINUATION_COMPLETE";
    private static final int CHILD_TIMEOUT_SECONDS = 90;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    Path temporary;

    private volatile String phase = "not-started";
    private volatile String parentThreadId;
    private volatile String taskThreadId;
    private volatile String activeTurnId;
    private volatile String mailboxMessageId;
    private volatile AgentIdentity activeIdentity;
    private volatile AgentEngine diagnosticEngine;
    private volatile TaskUseCase diagnosticTasks;

    /** 父测试只管独立 JVM 生命周期；失败 profile 留存以便核对真实 SQLite 与安全水位。 */
    @Test
    void activeRpcMessageBindsConsumesOnceAndEntersNextProviderRequest() throws Exception {
        Path output = temporary.resolve("active-mailbox-child.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "--enable-native-access=ALL-UNNAMED", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                ActiveTaskMailboxRpcIntegrationTest.class.getName(), temporary.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            boolean exited = child.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!exited) {
                child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
            assertTrue(exited, () -> failureOutput(output));
            assertEquals(0, child.exitValue(), () -> failureOutput(output));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    /** 子 JVM 必须使用 JDK 25，避免 Solon/MyBatis 的全局状态跨测试复用。 */
    public static void main(String[] arguments) throws Throwable {
        if (arguments.length != 1 || !"25".equals(System.getProperty("java.specification.version"))) {
            throw new IllegalStateException("isolated active mailbox test requires one profile path and JDK 25");
        }
        ActiveTaskMailboxRpcIntegrationTest fixture = new ActiveTaskMailboxRpcIntegrationTest();
        fixture.temporary = Path.of(arguments[0]);
        fixture.exercise(fixture.temporary);
    }

    /**
     * 只在首个模型请求已到达 Provider 后投递；Native owner 使用独立宿主进程身份，不能冒用 App Server PID。
     * @author kongweiguang
     */
    private void exercise(Path root) throws Throwable {
        Path home = Files.createDirectories(root.resolve("home"));
        Path data = Files.createDirectories(root.resolve("data"));
        Path run = Files.createDirectories(root.resolve("run"));
        Path logs = Files.createDirectories(root.resolve("logs"));
        CountDownLatch firstRequestEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstResponse = new CountDownLatch(1);
        CountDownLatch continuationEntered = new CountDownLatch(1);
        List<String> providerRequests = new CopyOnWriteArrayList<>();
        CompletableFuture<Integer> runtimeExit = new CompletableFuture<>();
        AtomicInteger requestCount = new AtomicInteger();
        RpcClient rpc = null;
        boolean gracefulShutdown = false;
        TaskUseCase tasks = null;
        AgentEngine engine = null;
        AgentIdentity identity = null;
        try (NativeHostProcessFixture.Host nativeHost = NativeHostProcessFixture.start();
             ModelAdapterTestSupport.Loopback provider = new ModelAdapterTestSupport.Loopback((call, exchange) -> {
            requestCount.set(call);
            providerRequests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (call == 1) {
                firstRequestEntered.countDown();
                awaitFirstResponseRelease(releaseFirstResponse);
                ModelAdapterTestSupport.sse(exchange,
                        toolCallResponse("resp_active_mailbox_tool", "call_active_mailbox_read", "read",
                                "{\"path\":\"source.txt\"}"), 31);
            } else if (call == 2) {
                continuationEntered.countDown();
                ModelAdapterTestSupport.sse(exchange,
                        finalTextResponse("resp_active_mailbox_final", FINAL_TEXT), 29);
            } else {
                ModelAdapterTestSupport.status(exchange, 500);
            }
        })) {
            Files.writeString(home.resolve("config.toml"), configuration(provider.baseUri()));
            String[] appArguments = applicationArguments(home, data, run, logs);
            phase = "starting-solon-runtime";
            Thread.ofVirtual().name("ja-active-mailbox-sidecar").start(() -> {
                try {
                    runtimeExit.complete(new StdioApplication().run(appArguments));
                } catch (Throwable failure) {
                    runtimeExit.completeExceptionally(failure);
                }
            });

            phase = "waiting-for-tcp-endpoint";
            ObjectNode endpoint = awaitEndpoint(run.resolve("app-server.endpoint.json"), runtimeExit);
            rpc = connect(endpoint, nativeHost);
            ConfigurationUseCase configuration = Solon.context().getBean(ConfigurationUseCase.class);
            configuration.setCredential(CREDENTIAL_ID, "isolated-test-key",
                    configuration.read(null).credentialVersion());
            WorkspaceUseCase workspaces = Solon.context().getBean(WorkspaceUseCase.class);
            ThreadUseCase threads = Solon.context().getBean(ThreadUseCase.class);
            tasks = Solon.context().getBean(TaskUseCase.class);
            engine = Solon.context().getBean(AgentEngine.class);
            diagnosticTasks = tasks;
            diagnosticEngine = engine;

            phase = "creating-sender-and-task";
            parentThreadId = "thr_active_mailbox_sender";
            io.github.kongweiguang.ja.workspace.domain.model.Workspace workspace =
                    workspaces.createSessionWorkspace(parentThreadId);
            ThreadPreferences preferences = new ThreadPreferences(PROVIDER_ID, MODEL_ID, null,
                    AccessMode.FULL_ACCESS, CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.PLACEHOLDER);
            ThreadSummary parent = threads.createThread(new ThreadSummary.Creation(
                    parentThreadId, workspace.workspaceId(), "Mailbox sender placeholder", preferences, Instant.now()));
            parent = threads.renameThread(parentThreadId, "Active mailbox sender", parent.revision());
            Files.writeString(workspace.root().resolve("source.txt"), SOURCE_TEXT);
            TaskModels.Summary created = tasks.createSideTask(new TaskUseCase.CreateCommand(
                    parentThreadId, null, parent.revision(), "Active mailbox recipient", null));
            taskThreadId = created.lineage().taskThreadId();

            phase = "starting-task-follow-up-over-rpc";
            TaskModels.Detail taskBefore = tasks.read(taskThreadId, 0, 0, 16);
            ObjectNode followUp = MAPPER.createObjectNode()
                    .put("senderThreadId", taskThreadId)
                    .put("targetThreadId", taskThreadId)
                    .put("operationKey", "op_00000000000000000000000000000001")
                    .put("expectedTaskRevision", taskBefore.task().projection().revision());
            followUp.set("content", userText(INITIAL_TEXT));
            ObjectNode followUpResponse = rpc.request("c:active_follow_up", "task/followup", followUp);
            assertTrue(followUpResponse.path("result").path("accepted").asBoolean(),
                    "the actual task follow-up RPC must admit the active turn");
            activeTurnId = followUpResponse.path("result").path("turnId").asText();
            assertTrue(activeTurnId.startsWith("turn_"));
            identity = JaTurnRequestMapper.identity(taskThreadId, activeTurnId);
            activeIdentity = identity;

            phase = "waiting-for-first-provider-request";
            assertTrue(firstRequestEntered.await(6, TimeUnit.SECONDS),
                    "the task turn must reach its first held model request");
            assertEquals(1, requestCount.get());
            assertTrue(engine.query(identity)
                    .map(status -> TurnState.RUNNING.name().equals(status.state())).orElse(false),
                    "the mailbox target must be an active execution while the Provider request is held");
            String firstBody = providerRequests.getFirst();
            assertFalse(firstBody.contains(MAILBOX_TEXT), "the held request predates the new mailbox message");

            phase = "sending-active-mailbox-rpc";
            ObjectNode sendMessage = MAPPER.createObjectNode()
                    .put("senderThreadId", parentThreadId)
                    .put("targetThreadId", taskThreadId)
                    .put("operationKey", "op_00000000000000000000000000000002");
            sendMessage.set("content", userText(MAILBOX_TEXT));
            ObjectNode messageResponse = rpc.request("c:active_message_send", "thread/message/send", sendMessage);
            ObjectNode messageResult = messageResponse.path("result").isObject()
                    ? (ObjectNode) messageResponse.path("result") : MAPPER.createObjectNode();
            assertTrue(messageResult.path("accepted").asBoolean(),
                    "the real thread/message/send RPC must acknowledge the queued message");
            mailboxMessageId = messageResult.path("messageId").asText();
            assertTrue(mailboxMessageId.startsWith("msg_"));
            assertTrue(messageResult.path("mailboxSequence").asLong() > 0);

            TaskModels.MailboxMessage bound = mailbox(tasks, taskThreadId, mailboxMessageId);
            assertEquals(TaskModels.MailboxState.BOUND, bound.state(),
                    "the active message must bind before the held Provider request is released");
            assertEquals(activeTurnId, bound.boundTurnId());
            AgentInputQueue heldQueue = engine.inspectInputQueue(identity);
            assertEquals(1, heldQueue.entries().size(), "the bound input must remain pending at the live safe point");
            assertEquals(AgentInputQueue.Kind.MESSAGE, heldQueue.entries().getFirst().kind());

            phase = "releasing-first-provider-response";
            releaseFirstResponse.countDown();
            assertTrue(continuationEntered.await(8, TimeUnit.SECONDS),
                    "the Tool result must lead to the next Provider request");
            assertEquals(2, requestCount.get());
            String continuationBody = providerRequests.get(1);
            assertTrue(continuationBody.contains("external_thread_message"),
                    "the next model input must preserve the Task mailbox envelope");
            assertEquals(1, occurrences(continuationBody, MAILBOX_TEXT),
                    "the mailbox content must enter the next model request exactly once");
            assertTrue(continuationBody.contains("function_call_output"),
                    "the continuation must also retain the completed native Tool result");

            phase = "waiting-for-core-and-task-completion";
            awaitCompleted(tasks, engine, taskThreadId, identity);
            assertEquals(2, requestCount.get(), "the Provider must not receive a duplicate mailbox continuation");
            TaskModels.MailboxMessage consumed = mailbox(tasks, taskThreadId, mailboxMessageId);
            assertEquals(TaskModels.MailboxState.CONSUMED, consumed.state());
            assertEquals(activeTurnId, consumed.boundTurnId());
            assertNotNull(consumed.consumedAt());
            assertTrue(engine.inspectInputQueue(identity).entries().isEmpty(),
                    "the consumed Core input must no longer remain queued");

            ThreadSnapshot history = threads.readThread(taskThreadId, null, 64).orElseThrow();
            List<ThreadSnapshot.ThreadMessageItem> messages = history.items().stream()
                    .filter(ThreadSnapshot.ThreadMessageItem.class::isInstance)
                    .map(ThreadSnapshot.ThreadMessageItem.class::cast).toList();
            assertEquals(1, messages.size(), "one consumed mailbox row must create one visible thread message");
            assertEquals(parentThreadId, messages.getFirst().sourceThreadId());
            assertEquals("Active mailbox sender", messages.getFirst().sourceTitle());
            assertEquals(MAILBOX_TEXT, messages.getFirst().content());
            List<ThreadSnapshot.ToolItem> tools = history.items().stream()
                    .filter(ThreadSnapshot.ToolItem.class::isInstance)
                    .map(ThreadSnapshot.ToolItem.class::cast).toList();
            assertEquals(1, tools.size(), "the native read call must settle exactly once");
            assertEquals("read", tools.getFirst().toolName());
            List<ThreadSnapshot.TextItem> finalAnswers = history.items().stream()
                    .filter(ThreadSnapshot.TextItem.class::isInstance)
                    .map(ThreadSnapshot.TextItem.class::cast)
                    .filter(item -> item.kind() == ThreadSnapshot.TextKind.FINAL_ANSWER).toList();
            assertEquals(1, finalAnswers.size());
            assertEquals(FINAL_TEXT, finalAnswers.getFirst().text());

            phase = "graceful-runtime-shutdown";
            ObjectNode shutdown = awaitGracefulShutdown(rpc);
            assertTrue(shutdown.path("result").path("accepted").asBoolean());
            gracefulShutdown = true;
            assertEquals(0, runtimeExit.get(15, TimeUnit.SECONDS));
            phase = "completed";
        } catch (Throwable failure) {
            writeFailureDiagnostics(root, failure, tasks, engine, identity, requestCount.get(), providerRequests);
            throw failure;
        } finally {
            releaseFirstResponse.countDown();
            if (rpc != null) {
                if (!gracefulShutdown) {
                    rpc.forceShutdownForIsolatedFailure();
                }
                rpc.close();
            }
        }
    }

    /** 持久终态先于执行 owner 释放；仅等待明确的 pending-work 拒绝，避免把正常收尾竞态当作邮件失败。 */
    private static ObjectNode awaitGracefulShutdown(RpcClient rpc) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
        int attempt = 0;
        while (System.nanoTime() < deadline) {
            ObjectNode response = rpc.request("c:active_shutdown_" + attempt++, "runtime/shutdown",
                    MAPPER.createObjectNode());
            if (response.path("result").path("accepted").asBoolean()) {
                return response;
            }
            assertEquals(-32034, response.path("error").path("code").asInt(),
                    "only pending-work rejection may precede graceful shutdown");
            assertEquals("runtime has pending work", response.path("error").path("message").asText());
            Thread.sleep(10);
        }
        throw new AssertionError("completed execution owner did not release before graceful shutdown");
    }

    /** 阻塞 Provider 工作线程到测试确认 RPC 已提交绑定，且超时短于模型 HTTP 请求预算。 */
    private static void awaitFirstResponseRelease(CountDownLatch release) throws IOException {
        try {
            if (!release.await(7, TimeUnit.SECONDS)) {
                throw new IOException("active mailbox release was not signalled");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("active mailbox provider wait was interrupted", interrupted);
        }
    }

    /** 正式就绪包含 Solon 冷启动与 17 项 SQLite 迁移，独立于后续 Provider 等待；为全量 Reactor 负载预留 30 秒，并在 runtimeExit 时立即失败。 */
    private static ObjectNode awaitEndpoint(Path path, CompletableFuture<Integer> runtimeExit) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(path)) {
                return (ObjectNode) MAPPER.readTree(Files.readAllBytes(path));
            }
            if (runtimeExit.isDone()) {
                throw new IllegalStateException("isolated sidecar exited before WebSocket readiness");
            }
            Thread.sleep(10);
        }
        throw new AssertionError("isolated WebSocket endpoint was not published");
    }

    /**
     * 走完整握手并登记独立 Native Host 身份，避免 self-process watcher 退化成虚假的生命周期测试。
     * @author kongweiguang
     */
    private static RpcClient connect(ObjectNode endpoint, NativeHostProcessFixture.Host nativeHost) throws Exception {
        RpcClient client = new RpcClient();
        client.open(endpoint.path("port").intValue());
        client.write(MAPPER.createObjectNode().put("token", endpoint.path("token").asText()));
        ObjectNode initialize = MAPPER.createObjectNode().put("protocolMajor", 2).put("protocolMinor", 0)
                .put("clientVersion", "active-mailbox-integration-test");
        initialize.set("capabilities", HandshakeHandler.capabilities(MAPPER));
        initialize.set("limits", HandshakeHandler.limits(MAPPER));
        ObjectNode initialized = client.request("c:active_initialize", "runtime/initialize", initialize);
        assertEquals(endpoint.path("serverInstanceId").asText(),
                initialized.path("result").path("serverInstanceId").asText());
        ObjectNode ready = MAPPER.createObjectNode().put("jsonrpc", "2.0").put("method", "runtime/initialized");
        ready.set("params", MAPPER.createObjectNode().put("readyToken", "0123456789abcdef0123456789abcdef"));
        client.write(ready);
        ObjectNode status = client.readFrame();
        assertEquals("runtime/status-changed", status.path("method").asText());
        assertEquals("ready", status.path("params").path("status").asText());
        ObjectNode context = MAPPER.createObjectNode();
        context.putObject("environment");
        context.putNull("shell");
        context.put("clientProcessId", nativeHost.processId());
        context.put("clientLifetimeId", nativeHost.lifetimeId());
        ObjectNode registered = client.request("c:active_context", "runtime/context/register", context);
        assertTrue(registered.path("result").path("contextId").asText().matches("ctx_[0-9a-f]{32}"));
        return client;
    }

    /** 将标准文本块编码为 Task RPC 的既有 UserContent wire 形状。 */
    private static ArrayNode userText(String text) {
        return MAPPER.createArrayNode().add(MAPPER.createObjectNode().put("type", "text").put("text", text));
    }

    /** 从持久 Task 详情回读单个 Mailbox，测试不以 RPC ACK 替代 BOUND/CONSUMED 状态。 */
    private static TaskModels.MailboxMessage mailbox(TaskUseCase tasks, String threadId, String messageId) {
        return tasks.read(threadId, 0, 0, 32).mailbox().stream()
                .filter(message -> message.messageId().equals(messageId)).findFirst().orElseThrow();
    }

    /** 同时等 Core execution 与 Task terminal 投影，避免把 Provider response 当作事务完成回执。 */
    private static void awaitCompleted(TaskUseCase tasks, AgentEngine engine, String threadId,
                                       AgentIdentity identity) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime() < deadline) {
            boolean coreCompleted = engine.query(identity)
                    .map(status -> TurnState.COMPLETED.name().equals(status.state())).orElse(false);
            boolean taskCompleted = tasks.read(threadId, 0, 0, 32).task().projection().state()
                    == TaskModels.State.COMPLETED;
            if (coreCompleted && taskCompleted) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("active mailbox Core Turn and Task did not reach COMPLETED");
    }

    /** 仅配置本机 Responses Provider 与短请求预算，测试不访问外部网络或真实凭据。 */
    private static String configuration(java.net.URI endpoint) {
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
                name = "Active mailbox loop fixture"
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
                name = "Active mailbox loop fixture"
                model = "test-model"
                reasoning_level_map = {}
                default_reasoning_level = { __ja_null = true }
                [providers.models.capabilities]
                context_window_tokens = 128000
                max_output_tokens = 8192
                """.formatted(PROVIDER_ID, MODEL_ID, PROVIDER_ID, endpoint, CREDENTIAL_ID, MODEL_ID);
    }

    /** 构造 Provider 原生 function-call SSE，使 Tool 与后续模型请求经过正式 codec 和 Driver。 */
    private static String toolCallResponse(String responseId, String callId, String toolName, String arguments)
            throws JsonProcessingException {
        String itemId = "item_active_mailbox_read";
        ObjectNode item = MAPPER.createObjectNode().put("id", itemId).put("type", "function_call")
                .put("call_id", callId).put("name", toolName).put("arguments", arguments);
        ArrayNode output = MAPPER.createArrayNode().add(item);
        ObjectNode created = eventPayload("response.created", 0)
                .set("response", MAPPER.readTree(ModelAdapterTestSupport.openAiResponse(responseId, "in_progress")));
        ObjectNode addedItem = MAPPER.createObjectNode().put("id", itemId).put("type", "function_call")
                .put("call_id", callId).put("name", toolName).put("arguments", "");
        ObjectNode added = eventPayload("response.output_item.added", 1).put("output_index", 0).set("item", addedItem);
        ObjectNode delta = eventPayload("response.function_call_arguments.delta", 2)
                .put("item_id", itemId).put("output_index", 0).put("delta", arguments);
        ObjectNode doneArguments = eventPayload("response.function_call_arguments.done", 3)
                .put("item_id", itemId).put("output_index", 0).put("arguments", arguments);
        ObjectNode doneItem = eventPayload("response.output_item.done", 4).put("output_index", 0).set("item", item);
        ObjectNode completed = eventPayload("response.completed", 5).set("response",
                MAPPER.readTree(ModelAdapterTestSupport.openAiResponse(responseId, "completed",
                        new ModelUsage(15, 4, 19), MAPPER.writeValueAsString(output))));
        return event("response.created", created) + event("response.output_item.added", added)
                + event("response.function_call_arguments.delta", delta)
                + event("response.function_call_arguments.done", doneArguments)
                + event("response.output_item.done", doneItem) + event("response.completed", completed);
    }

    /** 构造标准文本完成 SSE，保证活动队列消费后存在真实 Provider continuation。 */
    private static String finalTextResponse(String responseId, String text) throws JsonProcessingException {
        ObjectNode content = MAPPER.createObjectNode().put("type", "output_text").put("text", text);
        content.set("annotations", MAPPER.createArrayNode());
        content.set("logprobs", MAPPER.createArrayNode());
        ObjectNode item = MAPPER.createObjectNode().put("id", "message_active_mailbox_final")
                .put("type", "message").put("role", "assistant").put("status", "completed");
        item.set("content", MAPPER.createArrayNode().add(content));
        ArrayNode output = MAPPER.createArrayNode().add(item);
        ObjectNode created = eventPayload("response.created", 0)
                .set("response", MAPPER.readTree(ModelAdapterTestSupport.openAiResponse(responseId, "in_progress")));
        ObjectNode delta = eventPayload("response.output_text.delta", 1).put("output_index", 0)
                .put("content_index", 0).put("item_id", "message_active_mailbox_final").put("delta", text);
        ObjectNode done = eventPayload("response.output_text.done", 2).put("output_index", 0)
                .put("content_index", 0).put("item_id", "message_active_mailbox_final").put("text", text);
        ObjectNode doneItem = eventPayload("response.output_item.done", 3).put("output_index", 0).set("item", item);
        ObjectNode completed = eventPayload("response.completed", 4).set("response",
                MAPPER.readTree(ModelAdapterTestSupport.openAiResponse(responseId, "completed",
                        new ModelUsage(20, 5, 25), MAPPER.writeValueAsString(output))));
        return event("response.created", created) + event("response.output_text.delta", delta)
                + event("response.output_text.done", done) + event("response.output_item.done", doneItem)
                + event("response.completed", completed);
    }

    /** 固定严格递增的 Responses event 序号，以覆盖真实 Provider SSE 归约器。 */
    private static ObjectNode eventPayload(String type, int sequence) {
        return MAPPER.createObjectNode().put("type", type).put("sequence_number", sequence);
    }

    /** 将一项 Responses event 编码为合法 SSE frame，保留 JSON 转义和帧边界。 */
    private static String event(String type, ObjectNode payload) throws JsonProcessingException {
        return "event: " + type + "\ndata: " + MAPPER.writeValueAsString(payload) + "\n\n";
    }

    /** 统计指定标记在模型请求体中的真实出现次数，不把用户文本复制到失败诊断。 */
    private static int occurrences(String value, String marker) {
        int count = 0;
        for (int from = 0; (from = value.indexOf(marker, from)) >= 0; from += marker.length()) {
            count++;
        }
        return count;
    }

    /** 把四个 Ja 目录以唯一受支持的 Base64URL 参数传给子进程，避免编码边界依赖空格转义。 */
    private static String[] applicationArguments(Path home, Path data, Path run, Path logs) {
        return new String[]{"--home-dir-base64=" + encodePath(home), "--data-dir-base64=" + encodePath(data),
                "--run-dir-base64=" + encodePath(run), "--log-dir-base64=" + encodePath(logs),
                "--ja-runtime-generation=1", "--ja-transport=websocket"};
    }

    /** 使用无 padding Base64URL 编码绝对路径，匹配正式 sidecar 参数解析契约。 */
    private static String encodePath(Path path) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(path.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 仅记录执行阶段、状态闭集、队列数量和标记计数，失败时 profile 可继续用于 SQLite 核验。 */
    private void writeFailureDiagnostics(Path root, Throwable failure, TaskUseCase tasks, AgentEngine engine,
                                         AgentIdentity identity, int calls, List<String> requests) {
        StringBuilder text = new StringBuilder("phase=").append(phase)
                .append("\nfailureType=").append(failure.getClass().getName())
                .append("\nproviderCalls=").append(calls)
                .append("\nproviderBodiesWithMailboxMarker=")
                .append(requests.stream().filter(body -> body.contains(MAILBOX_TEXT)).count())
                .append("\nparentThreadId=").append(parentThreadId)
                .append("\ntaskThreadId=").append(taskThreadId)
                .append("\nturnId=").append(activeTurnId)
                .append("\nmessageId=").append(mailboxMessageId);
        if (tasks != null && taskThreadId != null) {
            try {
                TaskModels.Detail detail = tasks.read(taskThreadId, 0, 0, 32);
                text.append("\ntaskState=").append(detail.task().projection().state())
                        .append("\nmailbox=").append(detail.mailbox().stream()
                                .map(message -> message.state() + ":" + message.boundTurnId())
                                .toList());
            } catch (RuntimeException unavailable) {
                text.append("\ntaskRead=unavailable");
            }
        }
        if (engine != null && identity != null) {
            try {
                text.append("\ncoreState=").append(engine.query(identity)
                        .map(status -> status.state()).orElse("missing"))
                        .append("\ncoreQueue=").append(engine.inspectInputQueue(identity).entries().stream()
                                .map(entry -> entry.kind() + ":" + entry.status()).toList());
            } catch (RuntimeException unavailable) {
                text.append("\ncoreRead=unavailable");
            }
        }
        try {
            Files.writeString(root.resolve("active-mailbox-diagnostics.txt"), text.toString());
        } catch (IOException ignored) {
            // 子 JVM 的原始失败仍由父进程日志保留；诊断文件失败不覆盖根因。
        }
    }

    /** 返回有界子进程输出尾部；临时 profile 只在失败时由 JUnit 保留。 */
    private static String failureOutput(Path output) {
        try {
            String content = Files.readString(output, StandardCharsets.UTF_8);
            return content.length() <= 6_000 ? content : content.substring(content.length() - 6_000);
        } catch (IOException failure) {
            return "isolated active mailbox child failed; output unavailable";
        }
    }

    /** 同一测试 client 顺序消费 WebSocket 消息，响应等待跳过异步通知。 */
    private static final class RpcClient implements AutoCloseable, WebSocket.Listener {
        private static final String CLOSED = "\u0000";
        private final LinkedBlockingQueue<String> incoming = new LinkedBlockingQueue<>();
        private final StringBuilder fragments = new StringBuilder();
        private WebSocket socket;

        /** 只连隔离 sidecar 的固定路由，测试路径与正式 Rust Host 一致。 */
        private void open(int port) throws Exception {
            socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ja-rpc"), this)
                    .get(5, TimeUnit.SECONDS);
        }

        /** 显式申请下一条消息，正常测试客户端持续排空服务端出站帧。 */
        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }

        /** 仅完整 WebSocket 文本可作为一帧 JA-RPC，分片不会提前唤醒请求等待者。 */
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            synchronized (fragments) {
                fragments.append(data);
                if (last) {
                    incoming.offer(fragments.toString());
                    fragments.setLength(0);
                }
            }
            webSocket.request(1);
            return null;
        }

        /** 连接关闭作为 EOF 交给顺序读者，不伪装成业务响应。 */
        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            incoming.offer(CLOSED);
            return null;
        }

        /** 异步传输错误与关闭使用相同的连接终结语义。 */
        @Override public void onError(WebSocket webSocket, Throwable error) { incoming.offer(CLOSED); }

        /** 发送单个真实 JSON-RPC 请求并按 client id 等待其响应。 */
        private ObjectNode request(String id, String method, ObjectNode params) throws Exception {
            ObjectNode frame = MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
            frame.set("params", params);
            write(frame);
            for (int index = 0; index < 256; index++) {
                ObjectNode next = readFrame();
                if (id.equals(next.path("id").asText())) {
                    return next;
                }
            }
            throw new AssertionError("RPC response was not delivered to the isolated client");
        }

        /** token 与 JA-RPC 各占一个文本消息，不再包含 JSONL 的 LF 分隔符。 */
        private void write(ObjectNode frame) throws IOException {
            String text = MAPPER.writeValueAsString(frame);
            try {
                socket.sendText(text, true).get(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("isolated WebSocket send interrupted", interrupted);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
                throw new IOException("isolated WebSocket send failed", failure);
            }
        }

        /** 读取一个有界文本消息，拒绝 EOF 和非对象输出，避免把协议噪声当成有效事件。 */
        private ObjectNode readFrame() throws IOException {
            String text;
            try {
                text = incoming.poll(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("isolated WebSocket receive interrupted", interrupted);
            }
            if (text == null) {
                throw new IOException("isolated WebSocket receive timed out");
            }
            if (CLOSED.equals(text)) {
                throw new IOException("sidecar closed the isolated RPC connection");
            }
            JsonNode frame = MAPPER.readTree(text);
            if (!(frame instanceof ObjectNode object)) {
                throw new IOException("sidecar emitted a non-object frame");
            }
            return object;
        }

        /** 失败清理仅作用于本测试子 JVM，允许终止尚未收口的隔离执行以保留失败现场。 */
        private void forceShutdownForIsolatedFailure() {
            try {
                request("c:active_failure_cleanup", "runtime/shutdown",
                        MAPPER.createObjectNode().put("force", true));
            } catch (Exception ignored) {
                // 子进程父测试仍拥有有界 destroyForcibly 兜底，不覆盖原始断言失败。
            }
        }

        /** 只撤销当前 WebSocket，会话关闭不转移应用 Runtime 所有权。 */
        @Override
        public void close() {
            if (socket != null) { socket.abort(); }
        }
    }
}
