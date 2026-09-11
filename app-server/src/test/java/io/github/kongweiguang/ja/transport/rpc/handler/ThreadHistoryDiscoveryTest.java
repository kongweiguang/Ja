// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.catalog.port.in.CatalogUseCase;
import io.github.kongweiguang.ja.attachment.port.in.AttachmentPreviewUseCase;
import io.github.kongweiguang.ja.attachment.port.in.AttachmentUseCase;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationUseCase;
import io.github.kongweiguang.ja.conversation.port.in.ApprovalUseCase;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionUseCase;
import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.goal.port.in.GoalUseCase;
import io.github.kongweiguang.ja.task.port.in.TaskUseCase;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;
import io.github.kongweiguang.ja.transport.rpc.runtime.StdioWriter;
import io.github.kongweiguang.ja.transport.rpc.support.TestConfigurationPorts;
import io.github.kongweiguang.ja.workspace.port.in.WorkspacePathSearchUseCase;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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

        /** 初始化连接级 Task/Goal/Interaction 订阅，使测试边界与生产 Handler 相同。 */
        private Harness() {
            RpcServiceBindings bindings = new RpcServiceBindings(
                    unsupported(WorkspaceUseCase.class), unsupported(WorkspacePathSearchUseCase.class), threads.proxy(),
                    unsupported(TurnUseCase.class), unsupported(ContextCompactionUseCase.class),
                    unsupported(ApprovalUseCase.class), unsupported(CatalogUseCase.class),
                    unsupported(AttachmentUseCase.class), unsupported(AttachmentPreviewUseCase.class),
                    passive(TaskUseCase.class), passive(GoalUseCase.class),
                    io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.passiveInteractions(),
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

        /** 返回记录器代理，保留 ThreadUseCase 的所有未声明入口为显式失败。 */
        private ThreadUseCase proxy() {
            return (ThreadUseCase) Proxy.newProxyInstance(ThreadUseCase.class.getClassLoader(),
                    new Class<?>[]{ThreadUseCase.class}, (proxy, method, arguments) -> {
                        if ("discoverThreads".equals(method.getName())) {
                            discoveryQuery = (ThreadDiscovery.Query) arguments[0];
                            return new CursorPage<>(List.of(new ThreadDiscovery("thr_discovered", "review target",
                                    ThreadDiscovery.Kind.SUBAGENT, "ws_project", ThreadDiscovery.Status.COMPLETED)),
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
        @Override public ThreadSummary updatePreferences(String threadId, io.github.kongweiguang.ja.conversation.domain.ThreadPreferences value, long revision) { throw unsupported(); }
        /** 此 fixture 不允许发现隐式触发自动命名。 */
        @Override public boolean writeAutomaticTitle(String threadId, String title, long revision) { throw unsupported(); }
        /** 归档不是发现操作的副作用，误调用立即失败。 */
        @Override public ThreadSummary archiveThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 发现入口不得取得删除会话的控制权。 */
        @Override public void deleteThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 摘要查询不得进一步物化执行轮次详情。 */
        @Override public Optional<io.github.kongweiguang.ja.conversation.domain.TurnSummary> findTurn(String turnId) { throw unsupported(); }

        /** 构造普通导航使用的完整 Thread metadata，确保 Handler 未误用 discovery 投影。 */
        private static ThreadSummary thread() {
            return new ThreadSummary("thr_main", "ws_project", "Main thread", preferences(),
                    ThreadSummary.Status.ACTIVE, false, TurnState.COMPLETED, true, null, 1, NOW, NOW);
        }
    }

    /** 固定 Workspace page 请求参数，断言旧导航仍保持原始三元调用。 */
    private record ListRequest(String workspaceId, String cursor, int limit) { }

    /** 只开放连接订阅和关闭，测试不持有真实 Task/Goal 生命周期。 */
    private static <T> T passive(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, arguments) -> {
            if ((type == TaskUseCase.class && "subscribe".equals(method.getName()))
                    || (type == GoalUseCase.class && ("subscribe".equals(method.getName())
                    || "subscribePlan".equals(method.getName())))) {
                return (AutoCloseable) () -> { };
            }
            if ("close".equals(method.getName())) return null;
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
