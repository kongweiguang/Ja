// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.UserInputHistory;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadUsageSummary;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.catalog.application.api.CatalogUseCase;
import io.github.kongweiguang.ja.attachment.application.api.AttachmentPreviewUseCase;
import io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ApprovalUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ContextCompactionUseCase;
import io.github.kongweiguang.ja.agent.application.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.plan.application.api.PlanUseCase;
import io.github.kongweiguang.ja.collaboration.application.api.TaskUseCase;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;
import io.github.kongweiguang.ja.transport.rpc.runtime.StdioWriter;
import io.github.kongweiguang.ja.transport.rpc.support.TestConfigurationPorts;
import io.github.kongweiguang.ja.workspace.application.api.WorkspacePathSearchUseCase;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.preferences;
import static io.github.kongweiguang.ja.transport.rpc.runtime.RpcRuntimeTestAccess.markReady;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 thread/list 的普通 Workspace 导航与 scope=all discovery 在同一 RPC lane 内严格分流。 */
final class ThreadHistoryDiscoveryTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** 带 scope 的请求只读取最小 discovery item，并把 opaque page 参数原样交给 Thread owner。 */
    @Test
    void discoversMinimalThreadsWithExplicitAllScope() {
        try (Harness harness = new Harness()) {
            ObjectNode result = harness.handle(MAPPER.createObjectNode()
                    .put("scope", "all")
                    .put("query", "review")
                    .put("cursor", "opaque_cursor")
                    .put("limit", 2)
                    .put("workspaceId", "ws_project"));

            assertEquals(new ThreadDiscovery.Query("all", "review", "opaque_cursor", 2, "ws_project"),
                    harness.threads.discoveryQuery);
            assertEquals(Set.of("items", "nextCursor"), fields(result));
            assertEquals("cursor_next", result.path("nextCursor").asText());
            assertEquals(1, result.path("items").size());
            ObjectNode item = (ObjectNode) result.path("items").get(0);
            assertEquals(Set.of("threadId", "title", "kind", "workspaceId", "status"), fields(item));
            assertEquals("thr_discovered", item.path("threadId").asText());
            assertEquals("review target", item.path("title").asText());
            assertEquals("subagent", item.path("kind").asText());
            assertEquals("ws_project", item.path("workspaceId").asText());
            assertEquals("completed", item.path("status").asText());
            assertTrue(harness.threads.listRequest == null);
        }
    }

    /** 重启后持久 RUNNING 没有本地 owner 时，发现投影必须提供可继续的 SUSPENDED 状态。 */
    @Test
    void projectsOwnerlessRunningDiscoveryAsSuspended() {
        try (Harness harness = new Harness()) {
            harness.threads.discoveredStatus = ThreadDiscovery.Status.RUNNING;

            ObjectNode result = harness.handle(MAPPER.createObjectNode().put("scope", "all").put("limit", 2));

            assertEquals("suspended", result.path("items").get(0).path("status").asText());
        }
    }

    /** 当前进程仍持有该会话 owner 时，发现投影保留真实运行态。 */
    @Test
    void retainsRunningDiscoveryWhileLocalOwnerExists() {
        try (Harness harness = new Harness()) {
            harness.threads.discoveredStatus = ThreadDiscovery.Status.RUNNING;
            harness.threads.localActiveTurnId = "turn_discovered";

            ObjectNode result = harness.handle(MAPPER.createObjectNode().put("scope", "all").put("limit", 2));

            assertEquals("running", result.path("items").get(0).path("status").asText());
        }
    }

    /** 列表按快照中的 latestTurnId 判定，不得用早先本地 owner 保活后来的孤儿 Turn。 */
    @Test
    void suspendsQueuedLatestTurnWhenOnlyEarlierTurnHasLocalOwner() {
        try (Harness harness = new Harness()) {
            harness.threads.discoveredStatus = ThreadDiscovery.Status.QUEUED;
            harness.threads.localActiveTurnId = "turn_a";
            harness.threads.latestTurnId = "turn_b";

            ObjectNode result = harness.handle(MAPPER.createObjectNode().put("scope", "all").put("limit", 2));

            assertEquals("suspended", result.path("items").get(0).path("status").asText());
        }
    }

    /** 最新 queued B 的本地 owner 应保留列表摘要，不受较早 A 的先行状态影响。 */
    @Test
    void retainsQueuedLatestTurnWhenItHasItsOwnLocalOwner() {
        try (Harness harness = new Harness()) {
            harness.threads.discoveredStatus = ThreadDiscovery.Status.QUEUED;
            harness.threads.localActiveTurnId = "turn_b";
            harness.threads.latestTurnId = "turn_b";

            ObjectNode result = harness.handle(MAPPER.createObjectNode().put("scope", "all").put("limit", 2));

            assertEquals("queued", result.path("items").get(0).path("status").asText());
        }
    }

    /** Workspace 列表按 ThreadSummary 自带的 latestTurnId 判定，不回读每个 Thread 的完整历史。 */
    @Test
    void projectsWorkspaceListLatestTurnUsingItsExactLocalOwner() {
        try (Harness harness = new Harness()) {
            harness.threads.latestTurnStatus = TurnState.QUEUED;
            harness.threads.latestTurnId = "turn_b";
            harness.threads.localActiveTurnId = "turn_a";

            ObjectNode result = harness.handle(MAPPER.createObjectNode().put("workspaceId", "ws_project").put("limit", 2));

            assertEquals("suspended", result.path("items").get(0).path("latestTurnStatus").asText());
        }
    }

    /** Workspace 列表保留由本地 handle 持有的最新 queued B，即使更早 A 也有本地 owner。 */
    @Test
    void keepsWorkspaceListLatestQueuedTurnWithExactLocalOwner() {
        try (Harness harness = new Harness()) {
            harness.threads.latestTurnStatus = TurnState.QUEUED;
            harness.threads.latestTurnId = "turn_b";
            harness.threads.localOwnerThreadId = "thr_main";
            harness.threads.localActiveTurnId = "turn_b";

            ObjectNode result = harness.handle(MAPPER.createObjectNode().put("workspaceId", "ws_project").put("limit", 2));

            assertEquals("queued", result.path("items").get(0).path("latestTurnStatus").asText());
        }
    }

    /** 无 scope 仍使用旧 Workspace page，避免普通导航意外切换为全局最小投影。 */
    @Test
    void keepsWorkspaceNavigationWhenScopeIsAbsent() {
        try (Harness harness = new Harness()) {
            ObjectNode result = harness.handle(MAPPER.createObjectNode()
                    .put("workspaceId", "ws_project")
                    .put("limit", 1));

            assertEquals(new ListRequest("ws_project", null, 1), harness.threads.listRequest);
            assertTrue(harness.threads.discoveryQuery == null);
            assertEquals(Set.of("items", "nextCursor"), fields(result));
            assertEquals("cursor_thread_next", result.path("nextCursor").asText());
            ObjectNode item = (ObjectNode) result.path("items").get(0);
            assertEquals("thr_main", item.path("threadId").asText());
            assertTrue(item.has("preferences"));
            assertTrue(item.has("revision"));
        }
    }

    /** 通过 ready RpcSession 构造真实 Handler，除 Thread history 两个方法外的端口均明确拒绝。 */
    private static final class Harness implements AutoCloseable {
        private final RecordingThreads threads = new RecordingThreads();
        private final StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), MAPPER, 4 * 1024 * 1024);
        private final RpcSession session;
        private final ThreadHistoryHandler handler;

        /** 初始化连接级 Task/Plan/Interaction 订阅，使测试边界与生产 Handler 相同。 */
        private Harness() {
            RpcServiceBindings bindings = new RpcServiceBindings(
                    unsupported(WorkspaceUseCase.class), unsupported(WorkspacePathSearchUseCase.class), threads.proxy(),
                    io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedThreadMcp(),
                    turnOwner(), unsupported(ContextCompactionUseCase.class),
                    unsupported(ApprovalUseCase.class), unsupported(CatalogUseCase.class),
                    unsupported(AttachmentUseCase.class), unsupported(AttachmentPreviewUseCase.class),
                    passive(TaskUseCase.class), passive(PlanUseCase.class),
                    io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.passiveInteractions(),
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedProcesses(),
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedReviews(),
                    new NoopLifecycle());
            Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-thread-discovery-test")
                    .toAbsolutePath();
            SidecarConfiguration sidecar = new SidecarConfiguration(root.resolve("home"), root.resolve("data"),
                    root.resolve("run"), root.resolve("logs"));
            ConfigurationUseCase configuration = TestConfigurationPorts.unavailable();
            session = new RpcSession(sidecar, MAPPER, CLOCK, writer, ignored -> bindings, configuration);
            session.initialize();
            markReady(session, "0123456789abcdef0123456789abcdef");
            handler = new ThreadHistoryHandler(session);
        }

        /** 直接驱动 Handler，调用方无需穿过 JSONL 服务器即可观察领域分流。 */
        private ObjectNode handle(ObjectNode params) {
            return handler.handle(new RpcCommand(RpcMethod.THREAD_LIST, params)).toCompletableFuture().join();
        }

        /** owner fixture 只保活精确配置的 Turn，避免把同 Thread 的早先执行误当作 latest owner。 */
        private TurnUseCase turnOwner() {
            return (TurnUseCase) Proxy.newProxyInstance(TurnUseCase.class.getClassLoader(),
                    new Class<?>[]{TurnUseCase.class}, (proxy, method, arguments) -> {
                        if ("locallyActiveTurnId".equals(method.getName())) {
                            return Optional.ofNullable(threads.localActiveTurnId);
                        }
                        if ("locallyOwnsTurn".equals(method.getName())) {
                            return Objects.equals(arguments[0], threads.localOwnerThreadId)
                                    && Objects.equals(arguments[1], threads.localActiveTurnId);
                        }
                        throw unsupported();
                    });
        }

        /** 关闭 Handler 与 Session，避免 discovery 测试遗留 deadline executor。 */
        @Override
        public void close() {
            try {
                handler.close();
            } finally {
                try {
                    session.close();
                } finally {
                    writer.close();
                }
            }
        }
    }

    /** 只提供 Thread history 两个查询；其它方法若被触达说明 Handler 产生了额外读取。 */
    private static final class RecordingThreads implements ThreadUseCase {
        private ThreadDiscovery.Query discoveryQuery;
        private ListRequest listRequest;
        private ThreadDiscovery.Status discoveredStatus = ThreadDiscovery.Status.COMPLETED;
        private TurnState latestTurnStatus = TurnState.COMPLETED;
        private String latestTurnId = "turn_discovered";
        private String localOwnerThreadId = "thr_discovered";
        private String localActiveTurnId;

        /** 冷扫描返回空终止页；显式 handler 查询才进入记录路径，避免混淆两种发现请求。 */
        private ThreadUseCase proxy() {
            return (ThreadUseCase) Proxy.newProxyInstance(ThreadUseCase.class.getClassLoader(),
                    new Class<?>[]{ThreadUseCase.class}, (proxy, method, arguments) -> {
                        if ("discoverThreads".equals(method.getName())) {
                            ThreadDiscovery.Query query = (ThreadDiscovery.Query) arguments[0];
                            if (query.query() == null && query.cursor() == null
                                    && query.workspaceId() == null && query.limit() == 200) {
                                return new CursorPage<>(List.of(), null);
                            }
                            discoveryQuery = query;
                            return new CursorPage<>(List.of(new ThreadDiscovery("thr_discovered", "review target",
                                    ThreadDiscovery.Kind.SUBAGENT, "ws_project", discoveredStatus, latestTurnId)),
                                    "cursor_next");
                        }
                        if ("listThreads".equals(method.getName())) {
                            listRequest = new ListRequest((String) arguments[0], (String) arguments[1],
                                    (int) arguments[2]);
                            return new CursorPage<>(List.of(thread()), "cursor_thread_next");
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }

        /** 该类仅通过代理接入 Handler，防止测试实现与接口新增方法发生隐式漂移。 */
        @Override public ThreadSummary createThread(ThreadSummary.Creation request) { throw unsupported(); }
        /** 导航读请求由捕获代理验证，不允许直接绕过记录路径。 */
        @Override public CursorPage<ThreadSummary> listThreads(String workspaceId, String cursor, int limit) { throw unsupported(); }
        /** 此 fixture 不执行搜索，意外调用必须立即暴露。 */
        @Override public CursorPage<ThreadSummary> searchThreads(String workspaceId, String query, String cursor, int limit) { throw unsupported(); }
        /** 发现只读摘要，正文请求表示 Handler 越过测试边界。 */
        @Override public Optional<ThreadSnapshot> readThread(String threadId, String cursor, int limit) { throw unsupported(); }
        /** 只读发现不能写入会话名称。 */
        @Override public ThreadSummary renameThread(String threadId, String title, long revision) { throw unsupported(); }
        /** 只读发现不能改变目标会话偏好。 */
        @Override public ThreadSummary updatePreferences(String threadId, io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences value, long revision) { throw unsupported(); }
        /** 此 fixture 不允许发现隐式触发自动命名。 */
        @Override public boolean writeAutomaticTitle(String threadId, String title, long revision) { throw unsupported(); }
        /** 发现入口不修改 Thread 置顶状态。 */
        @Override public ThreadSummary pinThread(String threadId, boolean pinned, long revision) { throw unsupported(); }
        /** 发现入口不确认 Thread 已读。 */
        @Override public ThreadSummary markThreadSeen(String threadId, long revision) { throw unsupported(); }
        /** 归档不是发现操作的副作用，误调用立即失败。 */
        @Override public ThreadSummary archiveThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 发现入口不恢复已归档 Thread。 */
        @Override public ThreadSummary restoreThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 发现入口不得取得删除会话的控制权。 */
        @Override public void deleteThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 摘要查询不得进一步物化执行轮次详情。 */
        @Override public Optional<io.github.kongweiguang.ja.conversation.domain.model.TurnSummary> findTurn(String turnId) { throw unsupported(); }
        /** Discovery 不读取冻结文件差异；调用表示 Handler 越过摘要查询边界。 */
        @Override public Optional<ChangeSetArtifactFile> readChangeSetArtifact(
                String threadId, String turnId, String artifactId, String filePath) { throw unsupported(); }
        /** Discovery 只返回摘要，不展开会话 item 正文。 */
        @Override public Optional<ItemContentPage> readItemContent(
                String threadId, String itemId, int offsetCharacters, int limitCharacters) { throw unsupported(); }
        /** Discovery 不读取历史 Tool 正文。 */
        @Override public Optional<TextArtifactPage> readToolArtifact(String threadId, String turnId, String callId,
                                                                     String artifactId, int offsetCharacters,
                                                                     int limitCharacters) { throw unsupported(); }
        /** 为列表投影提供同一快照中的最新执行身份，避免逐项读取完整 Thread 历史。 */
        private ThreadSummary thread() {
            return new ThreadSummary("thr_main", "ws_project", "Main thread", "project", preferences(),
                    ThreadSummary.Status.ACTIVE, false, latestTurnStatus, true, 1, NOW, NOW, latestTurnId);
        }
        /** 发现查询通过动态代理计数；直接调用夹具必须失败，防止跳过观测边界。 */
        @Override
        public CursorPage<ThreadDiscovery> discoverThreads(ThreadDiscovery.Query query) { throw unsupported(); }
        /** 发现查询不读取跨 Session Thread 列表。 */
        @Override
        public CursorPage<ThreadSummary> listSessionThreads(String cursor, int limit) { throw unsupported(); }
        /** 发现查询不搜索已提交用户输入。 */
        @Override
        public CursorPage<UserInputHistory> searchUserInputs(String query, String cursor, int limit) { throw unsupported(); }
        /** 发现查询不搜索 Session Thread 标题。 */
        @Override
        public CursorPage<ThreadSummary> searchSessionThreads(String query, String cursor, int limit) { throw unsupported(); }
        /** 发现查询不读取 Thread 最新页。 */
        @Override
        public Optional<ThreadSnapshot> readThreadLatest(String threadId, String beforeCursor, int limit) { throw unsupported(); }
        /** 发现查询不读取用量投影。 */
        @Override
        public Optional<ThreadUsageSummary> readThreadUsageSummary(String threadId) { throw unsupported(); }
    }

    /**
     * 固定 Workspace page 请求参数，断言旧导航仍保持原始三元调用。
     *
     * @param workspaceId 工作区稳定标识
     * @param cursor 游标
     * @param limit 返回条目数上限
     */
    private record ListRequest(String workspaceId,
                               String cursor,
                               int limit) { }

    /** 只开放连接订阅和关闭，测试不持有真实 Task/Plan 生命周期。 */
    private static <T> T passive(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, arguments) -> {
            if ((type == TaskUseCase.class && "subscribe".equals(method.getName()))
                    || (type == PlanUseCase.class && ("subscribe".equals(method.getName())
                    || "subscribePlan".equals(method.getName())))) {
                return (AutoCloseable) () -> { };
            }
            if ("close".equals(method.getName())) {
                return null;
            }
            throw unsupported();
        });
    }

    /** 通过运行时类型构造拒绝式端口，避免 transport 测试意外触碰文件、Provider 或配置。 */
    @SuppressWarnings("unchecked")
    private static <T> T unsupported(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> { throw unsupported(); });
    }

    /** 统一未声明能力失败类型，避免夹具静默返回伪造成功。 */
    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("not used by discovery test");
    }

    /** 返回对象字段闭集，测试不依赖 Jackson 字段顺序。 */
    private static Set<String> fields(ObjectNode value) {
        Set<String> result = new java.util.HashSet<>();
        value.fieldNames().forEachRemaining(result::add);
        return result;
    }

    /** 没有资源可关闭的最小 lifecycle owner。 */
    private static final class NoopLifecycle implements DeadlineCloseable {
        /** 生命周期无外部资源。 */
        @Override public void closeAt(long shutdownDeadlineNanos) { }
        /** 生命周期无外部资源。 */
        @Override public void close() { }
    }
}
