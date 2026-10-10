// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationUseCase;
import io.github.kongweiguang.ja.conversation.infrastructure.provider.support.ModelAdapterTestSupport;
import io.github.kongweiguang.ja.conversation.application.service.JaTurnRequestMapper;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.collaboration.application.api.TaskUseCase;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用真实 Solon、SQLite 与 AgentEngine 验证显式 Child follow-up 接纳并消费待投递消息。 */
final class TaskMailboxChildFollowUpIntegrationTest {
    private static final String PROVIDER_ID = "provider_child_mailbox_fixture";
    private static final String MODEL_ID = "model_child_mailbox_fixture";
    private static final String CREDENTIAL_ID = "cred_child_mailbox_fixture";
    private static final String MAILBOX_TEXT = "The parent requests a concise status update.";
    private static final String FOLLOW_UP_TEXT = "Please apply the pending message.";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    Path temporary;

    /** 子 JVM 隔离全局 owner；所有等待退出分支都清理进程，再断言生产组合根的结果。 */
    @Test
    void explicitChildFollowUpConsumesPendingMailboxBeforeProviderCall() throws Exception {
        Path output = temporary.resolve("child-mailbox-loop.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "--enable-native-access=ALL-UNNAMED", "-Dja.log-dir=" + temporary.resolve("logs"),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                TaskMailboxChildFollowUpIntegrationTest.class.getName(), temporary.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        assertEquals(0, ChildProcessTestSupport.awaitExit(child, 90), () -> failureOutput(output));
    }

    /** 子进程加载正式 Solon composition，Provider 仅由本机 loopback fixture 响应。 */
    public static void main(String[] arguments) throws Throwable {
        if (arguments.length != 1 || !"25".equals(System.getProperty("java.specification.version"))) {
            throw new IllegalStateException("isolated child mailbox test requires one profile path and JDK 25");
        }
        new TaskMailboxChildFollowUpIntegrationTest().exercise(Path.of(arguments[0]));
    }

    /** 先持久化纯投递消息，再由用户显式 follow-up 准入；没有空闲会话自动唤醒。 */
    private void exercise(Path root) throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Map<String, String> previous = new LinkedHashMap<>();
        AtomicInteger calls = new AtomicInteger();
        List<String> providerRequests = new CopyOnWriteArrayList<>();
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback((call, exchange) -> {
            calls.set(call);
            providerRequests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ModelAdapterTestSupport.sse(exchange, finalTextResponse("resp_child_mailbox_" + call,
                    "Mailbox consumed on explicit follow-up."), 37);
        })) {
            Files.writeString(home.resolve("config.toml"), configuration(server.baseUri()));
            setRuntimeProperties(home, previous);
            Solon.start(App.class, new String[0], started -> started.enableHttp(false));

            ConfigurationUseCase configuration = Solon.context().getBean(ConfigurationUseCase.class);
            configuration.setCredential(CREDENTIAL_ID, "isolated-test-key", configuration.read(null).credentialVersion());
            WorkspaceUseCase workspaces = Solon.context().getBean(WorkspaceUseCase.class);
            ThreadUseCase threads = Solon.context().getBean(ThreadUseCase.class);
            TaskUseCase tasks = Solon.context().getBean(TaskUseCase.class);
            AgentEngine engine = Solon.context().getBean(AgentEngine.class);

            String parentThreadId = "thr_child_mailbox_parent";
            io.github.kongweiguang.ja.workspace.domain.model.Workspace workspace =
                    workspaces.createSessionWorkspace(parentThreadId);
            ThreadPreferences preferences = new ThreadPreferences(PROVIDER_ID, MODEL_ID, null, AccessMode.FULL_ACCESS,
                    CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.PLACEHOLDER);
            ThreadSummary parent = threads.createThread(new ThreadSummary.Creation(parentThreadId,
                    workspace.workspaceId(), "Mailbox parent", preferences, Instant.now()));
            String childThreadId = tasks.createSideTask(new TaskUseCase.CreateCommand(parentThreadId, null,
                    parent.revision(), "Mailbox recipient", null)).lineage().taskThreadId();

            TaskUseCase.MessageReceipt sent = tasks.sendMessage(new TaskUseCase.MessageCommand(parentThreadId,
                    childThreadId, new UserContent(List.of(new TextContent(MAILBOX_TEXT))),
                    "child-mailbox-parent-message", null));
            TaskModels.MailboxMessage pending = tasks.read(childThreadId, 0, 0, 16).mailbox().stream()
                    .filter(message -> message.messageId().equals(sent.messageId())).findFirst().orElseThrow();
            assertEquals(TaskModels.MailboxState.PENDING, pending.state());
            assertEquals(0, calls.get(), "Mailbox-only delivery must not wake the idle target");

            TaskModels.Summary beforeFollowUp = tasks.read(childThreadId, 0, 0, 16).task();
            TaskUseCase.FollowUpResult followUp = tasks.followUp(new TaskUseCase.FollowUpCommand(
                    new TaskUseCase.MessageCommand(childThreadId, childThreadId,
                            new UserContent(List.of(new TextContent(FOLLOW_UP_TEXT))),
                            "child-mailbox-follow-up", null), beforeFollowUp.projection().revision()));
            AgentIdentity identity = JaTurnRequestMapper.identity(childThreadId, followUp.turnId());
            awaitCompleted(tasks, engine, childThreadId, identity);

            assertEquals(1, calls.get(), "Explicit follow-up must issue exactly one Provider request");
            String firstRequest = providerRequests.getFirst();
            assertTrue(firstRequest.contains("external_thread_message"));
            assertTrue(firstRequest.contains(parentThreadId));
            assertTrue(firstRequest.contains(MAILBOX_TEXT));
            assertTrue(firstRequest.contains(FOLLOW_UP_TEXT));
            TaskModels.MailboxMessage consumed = tasks.read(childThreadId, 0, 0, 16).mailbox().stream()
                    .filter(message -> message.messageId().equals(sent.messageId())).findFirst().orElseThrow();
            assertEquals(TaskModels.MailboxState.CONSUMED, consumed.state());
            assertEquals(followUp.turnId(), consumed.boundTurnId());
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

    /** 等 Core terminal 与 Task 投影共同完成，避免把准入回执误作业务结束。 */
    private static void awaitCompleted(TaskUseCase tasks, AgentEngine engine, String threadId,
                                       AgentIdentity identity) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime() < deadline) {
            boolean coreCompleted = engine.query(identity)
                    .map(status -> TurnState.COMPLETED.name().equals(status.state())).orElse(false);
            boolean taskCompleted = tasks.read(threadId, 0, 0, 16).task().projection().state()
                    == TaskModels.State.COMPLETED;
            if (coreCompleted && taskCompleted) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Child Core Turn and Task projection did not reach COMPLETED");
    }

    /** 仅配置本地 Responses Provider，关闭 HTTP listener 并把所有数据放入隔离 profile。 */
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
                enabled = true
                provider_id = { __ja_null = true }
                model_id = { __ja_null = true }
                reasoning_level = { __ja_null = true }
                [[providers]]
                provider_id = "%s"
                name = "Child mailbox loop fixture"
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
                name = "Child mailbox loop fixture"
                model = "test-model"
                reasoning_level_map = {}
                default_reasoning_level = { __ja_null = true }
                [providers.models.capabilities]
                context_window_tokens = 128000
                max_output_tokens = 8192
                """.formatted(PROVIDER_ID, MODEL_ID, PROVIDER_ID, endpoint, CREDENTIAL_ID, MODEL_ID);
    }

    /** 子 JVM 的 Ja 路径属性逐项还原，避免污染调用测试宿主的用户目录。 */
    private static void setRuntimeProperties(Path home, Map<String, String> previous) throws IOException {
        Map<String, Path> paths = Map.of("ja.home-dir", home, "ja.data-dir", home.resolve("data"),
                "ja.run-dir", home.resolve("run"), "ja.log-dir", home.resolve("logs"));
        for (Map.Entry<String, Path> entry : paths.entrySet()) {
            previous.put(entry.getKey(), System.getProperty(entry.getKey()));
            System.setProperty(entry.getKey(), Files.createDirectories(entry.getValue()).toString());
        }
    }

    /** 构造完整 OpenAI Responses 事件序列，覆盖真实 SSE 解码和 Provider 历史映射。 */
    private static String finalTextResponse(String responseId, String text) throws JsonProcessingException {
        ObjectNode content = MAPPER.createObjectNode().put("type", "output_text").put("text", text);
        content.set("annotations", MAPPER.createArrayNode());
        content.set("logprobs", MAPPER.createArrayNode());
        ObjectNode item = MAPPER.createObjectNode().put("id", "message_child_mailbox_final")
                .put("type", "message").put("role", "assistant").put("status", "completed");
        item.set("content", MAPPER.createArrayNode().add(content));
        ArrayNode output = MAPPER.createArrayNode().add(item);
        ObjectNode created = eventPayload("response.created", 0)
                .set("response", MAPPER.readTree(ModelAdapterTestSupport.openAiResponse(responseId, "in_progress")));
        ObjectNode delta = eventPayload("response.output_text.delta", 1).put("output_index", 0)
                .put("content_index", 0).put("item_id", "message_child_mailbox_final").put("delta", text);
        ObjectNode done = eventPayload("response.output_text.done", 2).put("output_index", 0)
                .put("content_index", 0).put("item_id", "message_child_mailbox_final").put("text", text);
        ObjectNode doneItem = eventPayload("response.output_item.done", 3).put("output_index", 0).set("item", item);
        ObjectNode completed = eventPayload("response.completed", 4).set("response",
                MAPPER.readTree(ModelAdapterTestSupport.openAiResponse(responseId, "completed",
                        new ModelUsage(18, 6, 24), MAPPER.writeValueAsString(output))));
        return event("response.created", created) + event("response.output_text.delta", delta)
                + event("response.output_text.done", done) + event("response.output_item.done", doneItem)
                + event("response.completed", completed);
    }

    /** 固定事件序号以经过与实际 Provider 响应相同的严格 SSE 校验。 */
    private static ObjectNode eventPayload(String type, int sequence) {
        return MAPPER.createObjectNode().put("type", type).put("sequence_number", sequence);
    }

    /** 将事件序列化为独立标准 SSE frame。 */
    private static String event(String type, ObjectNode payload) throws JsonProcessingException {
        return "event: " + type + "\ndata: " + MAPPER.writeValueAsString(payload) + "\n\n";
    }

    /** 读取失败子 JVM 的有界输出尾部，保留完整断点而不把日志写进仓库。 */
    private static String failureOutput(Path output) {
        try {
            String text = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
            return text.length() <= 6000 ? text : text.substring(text.length() - 6000);
        } catch (IOException failure) {
            return "child mailbox child process failed; output unavailable";
        }
    }
}
