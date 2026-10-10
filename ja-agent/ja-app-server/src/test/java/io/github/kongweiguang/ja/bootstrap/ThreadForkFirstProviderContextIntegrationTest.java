// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.api.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.conversation.application.service.JaTurnRequestMapper;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.infrastructure.provider.support.ModelAdapterTestSupport;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用真实 Solon、SQLite、Thread fork 和本地 Provider 验证冻结上下文在首轮与重启后的闭环。 */
final class ThreadForkFirstProviderContextIntegrationTest {
    private static final String PROVIDER_ID = "provider_thread_fork_context_fixture";
    private static final String MODEL_ID = "model_thread_fork_context_fixture";
    private static final String CREDENTIAL_ID = "cred_thread_fork_context_fixture";
    private static final String SOURCE_THREAD_ID = "thr_thread_fork_context_source";
    private static final String SOURCE_FIRST_TURN_ID = "turn_thread_fork_context_first";
    private static final String SOURCE_SELECTED_TURN_ID = "turn_thread_fork_context_selected";
    private static final String SOURCE_FUTURE_TURN_ID = "turn_thread_fork_context_future";
    private static final String SOURCE_PREFIX_MARKER = "FORK_PREFIX_ALPHA_71";
    private static final String SOURCE_SELECTED_MARKER = "FORK_SELECTED_BETA_83";
    private static final String SOURCE_FUTURE_MARKER = "FORK_FUTURE_GAMMA_95";
    private static final String CHILD_FIRST_TURN_ID = "turn_thread_fork_context_child_first";
    private static final String CHILD_FOLLOW_UP_TURN_ID = "turn_thread_fork_context_child_followup";
    private static final String CHILD_FIRST_MARKER = "FORK_CHILD_FIRST_DELTA_27";
    private static final String CHILD_FOLLOW_UP_MARKER = "FORK_CHILD_FOLLOWUP_EPSILON_39";
    private static final String CHILD_THREAD_FILE = "forked-thread-id.txt";
    private static final String TOOL_SIDE_EFFECT_MARKER = "FORK_TOOL_SIDE_EFFECT_ONCE_56";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    Path temporary;

    /**
     * 按进程边界重启真实 Store；真实宿主工具副作用只执行一次，后续 Provider 请求只消费冻结的工具事实。
     * @author kongweiguang
     */
    @Test
    void frozenPrefixReachesFirstProviderAfterRestartAndIsNotReplayed() throws Exception {
        List<String> providerRequests = new CopyOnWriteArrayList<>();
        AtomicBoolean emittedHistoricalTool = new AtomicBoolean();
        Path toolEffectFile = temporary.resolve("historical-tool-side-effect.txt");
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback((call, exchange) -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            providerRequests.add(request);
            String response = request.contains(SOURCE_PREFIX_MARKER)
                    && !request.contains("function_call_output")
                    && emittedHistoricalTool.compareAndSet(false, true)
                    ? toolCallResponse("resp_thread_fork_context_" + call, toolEffectFile)
                    : finalTextResponse("resp_thread_fork_context_" + call, "FORK_ASSISTANT_REPLY_ZETA_48");
            ModelAdapterTestSupport.sse(exchange, response, 37);
        })) {
            runChildPhase("prepare", server.baseUri());
            assertEquals(List.of(TOOL_SIDE_EFFECT_MARKER), Files.readAllLines(toolEffectFile,
                    StandardCharsets.UTF_8), "the source Tool effect must execute once before its answer settles");
            assertTrue(providerRequest(providerRequests, SOURCE_PREFIX_MARKER).contains(SOURCE_PREFIX_MARKER));
            assertTrue(providerRequest(providerRequests, SOURCE_SELECTED_MARKER).contains(SOURCE_SELECTED_MARKER));

            runChildPhase("first", server.baseUri());
            String firstChildRequest = providerRequest(providerRequests, CHILD_FIRST_MARKER);
            assertTrue(firstChildRequest.contains("\"type\":\"function_call\""),
                    "the child Provider receives historical Tool intent as context");
            assertTrue(firstChildRequest.contains("\"type\":\"function_call_output\""),
                    "the child Provider receives the historical Tool result as context");
            assertOccursOnce(firstChildRequest, SOURCE_PREFIX_MARKER);
            assertOccursOnce(firstChildRequest, SOURCE_SELECTED_MARKER);
            assertFalse(firstChildRequest.contains(SOURCE_FUTURE_MARKER),
                    "forking at the selected follow-up must exclude later source Turns");
            assertEquals(List.of(TOOL_SIDE_EFFECT_MARKER), Files.readAllLines(toolEffectFile,
                    StandardCharsets.UTF_8), "loading inherited Tool history must not execute its side effect again");

            runChildPhase("followup", server.baseUri());
            String resumedFollowUpRequest = providerRequest(providerRequests, CHILD_FOLLOW_UP_MARKER);
            assertOccursOnce(resumedFollowUpRequest, SOURCE_PREFIX_MARKER);
            assertOccursOnce(resumedFollowUpRequest, SOURCE_SELECTED_MARKER);
            assertOccursOnce(resumedFollowUpRequest, CHILD_FIRST_MARKER);
            assertFalse(resumedFollowUpRequest.contains(SOURCE_FUTURE_MARKER));
            assertEquals(List.of(TOOL_SIDE_EFFECT_MARKER), Files.readAllLines(toolEffectFile,
                    StandardCharsets.UTF_8), "restarting the child must not replay a source Tool effect");
        }
    }

    /** 分阶段运行真实组合根；子 JVM 隔离 Solon owner，并显式使用当前 JDK 的平台可执行文件。 */
    private void runChildPhase(String phase, URI endpoint) throws Exception {
        Path output = temporary.resolve("fork-context-" + phase + ".log");
        Process child = new ProcessBuilder(javaExecutable().toString(),
                "--enable-native-access=ALL-UNNAMED", "-Dja.log-dir=" + temporary.resolve("logs-" + phase),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                ThreadForkFirstProviderContextIntegrationTest.class.getName(), temporary.toString(), phase,
                endpoint.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        assertEquals(0, ChildProcessTestSupport.awaitExit(child, 90), () -> failureOutput(output));
    }

    /** 使用当前 JDK 的 bin 目录而不依赖 PATH；仅按宿主平台选择 JVM 文件名。 */
    private static Path javaExecutable() {
        String binary = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", binary);
    }

    /** 由 Surefire 子 JVM 执行准备、首轮或重启后 follow-up，始终使用同一隔离数据库。 */
    public static void main(String[] arguments) throws Throwable {
        if (arguments.length != 3 || !"25".equals(System.getProperty("java.specification.version"))) {
            throw new IllegalStateException("isolated Thread fork test requires a profile, phase, and JDK 25");
        }
        new ThreadForkFirstProviderContextIntegrationTest().exercise(Path.of(arguments[0]), arguments[1],
                URI.create(arguments[2]));
    }

    /** 准备真实 source history，或在同一 forked profile 的新进程中消费/续跑 child seed。 */
    private void exercise(Path root, String phase, URI endpoint) throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Map<String, String> previous = new LinkedHashMap<>();
        try {
            Files.writeString(home.resolve("config.toml"), configuration(endpoint));
            setRuntimeProperties(home, previous);
            Solon.start(App.class, new String[0], started -> started.enableHttp(false));

            ConfigurationUseCase configuration = Solon.context().getBean(ConfigurationUseCase.class);
            configuration.setCredential(CREDENTIAL_ID, "isolated-thread-fork-key",
                    configuration.read(null).credentialVersion());
            WorkspaceUseCase workspaces = Solon.context().getBean(WorkspaceUseCase.class);
            ThreadUseCase threads = Solon.context().getBean(ThreadUseCase.class);
            TurnUseCase turns = Solon.context().getBean(TurnUseCase.class);
            AgentEngine engine = Solon.context().getBean(AgentEngine.class);

            switch (phase) {
                case "prepare" -> prepareSourceAndFork(root, workspaces, threads, turns, engine);
                case "first" -> startChildTurn(root, workspaces, threads, turns, engine,
                        CHILD_FIRST_TURN_ID, CHILD_FIRST_MARKER);
                case "followup" -> startChildTurn(root, workspaces, threads, turns, engine,
                        CHILD_FOLLOW_UP_TURN_ID, CHILD_FOLLOW_UP_MARKER);
                default -> throw new IllegalArgumentException("unknown Thread fork test phase");
            }
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

    /** 以真实来源回复 Item 冻结 prefix；选中的 follow-up 位于未来 source Turn 之前。 */
    private static void prepareSourceAndFork(Path root, WorkspaceUseCase workspaces, ThreadUseCase threads,
                                             TurnUseCase turns, AgentEngine engine) throws Exception {
        Workspace workspace = workspaces.createSessionWorkspace(SOURCE_THREAD_ID);
        ThreadPreferences preferences = preferences();
        ThreadSummary source = threads.createThread(new ThreadSummary.Creation(SOURCE_THREAD_ID,
                workspace.workspaceId(), "Thread fork context source", preferences, Instant.now()));
        source = threads.renameThread(SOURCE_THREAD_ID, source.title(), source.revision());
        startAndAwait(threads, turns, engine, workspace, source, SOURCE_FIRST_TURN_ID, SOURCE_PREFIX_MARKER);
        startAndAwait(threads, turns, engine, workspace,
                currentThread(threads, SOURCE_THREAD_ID), SOURCE_SELECTED_TURN_ID, SOURCE_SELECTED_MARKER);
        String selectedReplyItemId = finalReplyItemId(threads, SOURCE_THREAD_ID, SOURCE_SELECTED_TURN_ID);
        startAndAwait(threads, turns, engine, workspace,
                currentThread(threads, SOURCE_THREAD_ID), SOURCE_FUTURE_TURN_ID, SOURCE_FUTURE_MARKER);

        ThreadSummary latestSource = currentThread(threads, SOURCE_THREAD_ID);
        ThreadUseCase.ThreadForkResult fork = threads.forkThread(new ThreadUseCase.ThreadForkRequest(
                SOURCE_THREAD_ID, selectedReplyItemId, latestSource.revision(),
                "op_0123456789abcdef0123456789abcdef"));
        ThreadSummary child = threads.renameThread(fork.thread().threadId(), fork.thread().title(),
                fork.thread().revision());
        Files.writeString(root.resolve(CHILD_THREAD_FILE), child.threadId(), StandardCharsets.UTF_8);
    }

    /**
     * Resolves the last visible settled reply for the selected source Turn from persisted history; a Turn ID cannot
     * substitute for the item boundary because one execution Turn may publish several independently forkable replies.
     */
    private static String finalReplyItemId(ThreadUseCase threads, String threadId, String turnId) {
        return threads.readThread(threadId, null, 256).orElseThrow().items().stream()
                .map(item -> item instanceof ThreadSnapshot.OriginItem inherited ? inherited.item() : item)
                .filter(ThreadSnapshot.TextItem.class::isInstance)
                .map(ThreadSnapshot.TextItem.class::cast)
                .filter(item -> item.kind() == ThreadSnapshot.TextKind.FINAL_ANSWER)
                .filter(item -> turnId.equals(item.turnId()))
                .map(ThreadSnapshot.TextItem::itemId)
                .reduce((left, right) -> right)
                .orElseThrow(() -> new IllegalStateException("selected Turn has no settled final answer"));
    }

    /** 首轮或跨进程 follow-up 都经过普通 USER 准入，不向 Turn 人工注入 seed。 */
    private static void startChildTurn(Path root, WorkspaceUseCase workspaces, ThreadUseCase threads,
                                       TurnUseCase turns, AgentEngine engine,
                                       String turnId, String inputMarker) throws Exception {
        String childThreadId = Files.readString(root.resolve(CHILD_THREAD_FILE), StandardCharsets.UTF_8).trim();
        ThreadSummary child = currentThread(threads, childThreadId);
        Workspace workspace = workspaces.openRegisteredWorkspace(child.workspaceId());
        startAndAwait(threads, turns, engine, workspace, child, turnId, inputMarker);
    }

    /** 只在真实 terminal 完成后推进下一个源 Turn，保证 fork 冻结边界可由持久历史判定。 */
    private static void startAndAwait(ThreadUseCase threads, TurnUseCase turns, AgentEngine engine,
                                      Workspace workspace, ThreadSummary thread,
                                      String turnId, String inputMarker) throws Exception {
        TurnStartRequest request = new TurnStartRequest(thread.threadId(), turnId, workspace.workspaceId(),
                workspace.root(), new UserContent(List.of(new TextContent(inputMarker))), PROVIDER_ID, MODEL_ID,
                null, AccessMode.FULL_ACCESS, CollaborationMode.DEFAULT, thread.revision(), 0, Instant.now(),
                Optional.empty(), Optional.empty(), Optional.empty(), false);
        TurnUseCase.Accepted accepted = turns.start(request, ConversationTurnEventSink.turnEventsOnly(
                event -> java.util.concurrent.CompletableFuture.completedFuture(null)));
        accepted.completion().toCompletableFuture().get(40, TimeUnit.SECONDS);
        AgentIdentity identity = JaTurnRequestMapper.identity(thread.threadId(), turnId);
        assertEquals(TurnState.COMPLETED.name(), engine.query(identity).orElseThrow().state());
        assertTrue(currentThread(threads, thread.threadId()).revision() > thread.revision());
    }

    /** 从权威完整 Thread 历史读取最新 revision，不让测试通过手工猜测版本。 */
    private static ThreadSummary currentThread(ThreadUseCase threads, String threadId) {
        return threads.readThread(threadId, null, 256).orElseThrow().thread();
    }

    /** 同一组 Provider 默认值让来源、fork 与恢复后的 child 走生产配置解析。 */
    private static ThreadPreferences preferences() {
        return new ThreadPreferences(PROVIDER_ID, MODEL_ID, null, AccessMode.FULL_ACCESS,
                CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.PLACEHOLDER);
    }

    /** 只接受命中指定用户输入的 captured Provider body，避免异步自动标题请求影响断言。 */
    private static String providerRequest(List<String> requests, String marker) {
        return requests.stream().filter(body -> body.contains(marker)).findFirst()
                .orElseThrow(() -> new AssertionError("Provider request did not contain the expected Turn marker"));
    }

    /** 冻结来源标记必须各映射一次，避免既遗漏 prefix 又重复播种。 */
    private static void assertOccursOnce(String providerRequest, String marker) {
        assertEquals(1, occurrences(providerRequest, marker), "frozen context marker must appear once: " + marker);
    }

    /** 只统计指定不透明测试标记，不解析或重写实际 Provider 请求。 */
    private static int occurrences(String value, String marker) {
        int count = 0;
        for (int offset = 0; (offset = value.indexOf(marker, offset)) >= 0; offset += marker.length()) {
            count++;
        }
        return count;
    }

    /** 仅指向 loopback Responses Provider；测试不会访问外网或真实凭据。 */
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
                name = "Thread fork context fixture"
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
                name = "Thread fork context fixture"
                model = "test-model"
                reasoning_level_map = {}
                default_reasoning_level = { __ja_null = true }
                [providers.models.capabilities]
                context_window_tokens = 128000
                max_output_tokens = 8192
                """.formatted(PROVIDER_ID, MODEL_ID, PROVIDER_ID, endpoint, CREDENTIAL_ID, MODEL_ID);
    }

    /** 子 JVM 的 Ja 目录逐项固定到本测试 profile，重启验证不触碰其他用户数据。 */
    private static void setRuntimeProperties(Path home, Map<String, String> previous) throws IOException {
        Map<String, Path> paths = Map.of("ja.home-dir", home, "ja.data-dir", home.resolve("data"),
                "ja.run-dir", home.resolve("run"), "ja.log-dir", home.resolve("logs"));
        for (Map.Entry<String, Path> entry : paths.entrySet()) {
            previous.put(entry.getKey(), System.getProperty(entry.getKey()));
            System.setProperty(entry.getKey(), Files.createDirectories(entry.getValue()).toString());
        }
    }

    /** 生成完整 Responses SSE 文本事件，确保真实 Provider Adapter 经过严格解码。 */
    private static String finalTextResponse(String responseId, String text) throws JsonProcessingException {
        ObjectNode content = MAPPER.createObjectNode().put("type", "output_text").put("text", text);
        content.set("annotations", MAPPER.createArrayNode());
        content.set("logprobs", MAPPER.createArrayNode());
        ObjectNode item = MAPPER.createObjectNode().put("id", "message_thread_fork_final")
                .put("type", "message").put("role", "assistant").put("status", "completed");
        item.set("content", MAPPER.createArrayNode().add(content));
        ArrayNode output = MAPPER.createArrayNode().add(item);
        ObjectNode created = eventPayload("response.created", 0)
                .set("response", MAPPER.readTree(ModelAdapterTestSupport.openAiResponse(responseId, "in_progress")));
        ObjectNode delta = eventPayload("response.output_text.delta", 1).put("output_index", 0)
                .put("content_index", 0).put("item_id", "message_thread_fork_final").put("delta", text);
        ObjectNode done = eventPayload("response.output_text.done", 2).put("output_index", 0)
                .put("content_index", 0).put("item_id", "message_thread_fork_final").put("text", text);
        ObjectNode doneItem = eventPayload("response.output_item.done", 3).put("output_index", 0).set("item", item);
        ObjectNode completed = eventPayload("response.completed", 4).set("response",
                MAPPER.readTree(ModelAdapterTestSupport.openAiResponse(responseId, "completed",
                        new ModelUsage(18, 6, 24), MAPPER.writeValueAsString(output))));
        return event("response.created", created) + event("response.output_text.delta", delta)
                + event("response.output_text.done", done) + event("response.output_item.done", doneItem)
                + event("response.completed", completed);
    }

    /** 返回宿主 Shell 的追加调用；文件每多一行就代表了一次真实派发，而不是模型上下文中的历史引用。 @author kongweiguang */
    private static String toolCallResponse(String responseId, Path effectFile) throws JsonProcessingException {
        String itemId = "item_thread_fork_historical_tool";
        String callId = "call_thread_fork_historical_tool";
        String command = "Add-Content -LiteralPath '" + effectFile + "' -Value '"
                + TOOL_SIDE_EFFECT_MARKER + "'";
        ObjectNode arguments = MAPPER.createObjectNode().put("cmd", command).put("yield_time_ms", 10_000);
        ObjectNode item = MAPPER.createObjectNode().put("id", itemId).put("type", "function_call")
                .put("call_id", callId).put("name", "exec_command")
                .put("arguments", MAPPER.writeValueAsString(arguments));
        ArrayNode output = MAPPER.createArrayNode().add(item);
        ObjectNode created = eventPayload("response.created", 0)
                .set("response", MAPPER.readTree(ModelAdapterTestSupport.openAiResponse(responseId, "in_progress")));
        ObjectNode addedItem = MAPPER.createObjectNode().put("id", itemId).put("type", "function_call")
                .put("call_id", callId).put("name", "exec_command").put("arguments", "");
        ObjectNode added = eventPayload("response.output_item.added", 1).put("output_index", 0).set("item", addedItem);
        ObjectNode delta = eventPayload("response.function_call_arguments.delta", 2)
                .put("item_id", itemId).put("output_index", 0)
                .put("delta", MAPPER.writeValueAsString(arguments));
        ObjectNode doneArguments = eventPayload("response.function_call_arguments.done", 3)
                .put("item_id", itemId).put("output_index", 0)
                .put("arguments", MAPPER.writeValueAsString(arguments));
        ObjectNode doneItem = eventPayload("response.output_item.done", 4).put("output_index", 0).set("item", item);
        ObjectNode completed = eventPayload("response.completed", 5).set("response",
                MAPPER.readTree(ModelAdapterTestSupport.openAiResponse(responseId, "completed",
                        new ModelUsage(15, 4, 19), MAPPER.writeValueAsString(output))));
        return event("response.created", created) + event("response.output_item.added", added)
                + event("response.function_call_arguments.delta", delta)
                + event("response.function_call_arguments.done", doneArguments)
                + event("response.output_item.done", doneItem) + event("response.completed", completed);
    }

    /** 为严格 Responses fixture 固定真实事件序号。 */
    private static ObjectNode eventPayload(String type, int sequence) {
        return MAPPER.createObjectNode().put("type", type).put("sequence_number", sequence);
    }

    /** 将 Provider 事件编码成 SSE frame，不依赖模型端兼容格式。 */
    private static String event(String type, ObjectNode payload) throws JsonProcessingException {
        return "event: " + type + "\ndata: " + MAPPER.writeValueAsString(payload) + "\n\n";
    }

    /** 返回失败子 JVM 有界日志尾部，便于定位测试组合失败而不写仓库日志。 */
    private static String failureOutput(Path output) {
        try {
            String text = Files.readString(output, StandardCharsets.UTF_8);
            return text.length() <= 6000 ? text : text.substring(text.length() - 6000);
        }
        catch (IOException failure) {
            return "Thread fork child process failed; output unavailable";
        }
    }
}
