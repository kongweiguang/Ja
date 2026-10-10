// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.attachment.application.api.AttachmentPreviewUseCase;
import io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase;
import io.github.kongweiguang.ja.catalog.application.api.CatalogUseCase;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ApprovalUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ContextCompactionUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.agent.application.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.collaboration.application.api.TaskUseCase;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;
import io.github.kongweiguang.ja.transport.rpc.runtime.StdioWriter;
import io.github.kongweiguang.ja.transport.rpc.support.TestConfigurationPorts;
import io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings;
import io.github.kongweiguang.ja.workspace.application.api.WorkspacePathSearchUseCase;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.github.kongweiguang.ja.transport.rpc.runtime.RpcRuntimeTestAccess.markReady;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Task observation 的连接所有权、显式释放与断连清理。 */
final class TaskHandlerObservationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-03T00:00:00Z"), ZoneOffset.UTC);

    /** 显式 unobserve 从连接集合移除句柄，后续 session close 不得重复释放。 */
    @Test
    void explicitUnobserveRemovesSessionOwnership() {
        Harness harness = new Harness();
        String observationId;
        try {
            observationId = harness.observe();

            harness.unobserve(observationId);
        } finally {
            harness.close();
        }
        assertEquals(List.of(observationId), harness.tasks.released);
    }

    /** owner 意外复用句柄时只拒绝第二次登记，不撤销第一次连接所有权或制造重复释放。 */
    @Test
    void duplicateOwnerObservationIdDoesNotRevokeExistingSessionObservation() {
        String observationId;
        Harness harness = new Harness(true);
        try {
            observationId = harness.observe();
            JaRpcException failure = assertThrows(JaRpcException.class, harness::observe);

            assertEquals("TASK_OBSERVATION_INVALID", failure.errorCode());
            assertEquals(List.of(), harness.tasks.released);
            harness.unobserve(observationId);
        } finally {
            harness.close();
        }

        assertEquals(List.of(observationId), harness.tasks.released);
    }

    /** close 先移除句柄时，observe 仍须失败，且不得将已释放句柄返回给客户端。 */
    @Test
    void rejectsObservationWhenCloseReleasesBeforeRegistrationReturns() throws Exception {
        Harness harness = new Harness();
        BlockingObservationSet observations = new BlockingObservationSet();
        replaceObservationOwnershipSet(harness.session, observations);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> observe = executor.submit(harness::observe);
            assertTrue(observations.added.await(5, TimeUnit.SECONDS));

            Future<?> close = executor.submit(harness::close);
            close.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("observe_test_1"), harness.tasks.released);

            observations.allowAddReturn.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> observe.get(5, TimeUnit.SECONDS));
            JaRpcException rpcFailure = (JaRpcException) failure.getCause();
            assertEquals("SHUTTING_DOWN", rpcFailure.errorCode());
        } finally {
            observations.allowAddReturn.countDown();
            executor.shutdownNow();
            harness.close();
        }

        assertEquals(List.of("observe_test_1"), harness.tasks.released);
    }

    /** 客户端断开必须在共享 Task runtime 关闭前释放所有仍由本连接持有的句柄。 */
    @Test
    void sessionCloseReleasesOutstandingObservations() {
        Harness harness = new Harness();
        String first = harness.observe();
        String second = harness.observe();

        harness.close();

        assertEquals(Set.of(first, second), Set.copyOf(harness.tasks.released));
        assertEquals(2, harness.tasks.released.size());
    }

    /** 未由当前连接创建的句柄必须失败关闭，避免跨连接释放别人的高频订阅。 */
    @Test
    void rejectsObservationOwnedByAnotherSession() {
        try (Harness harness = new Harness()) {
            JaRpcException failure = assertThrows(JaRpcException.class,
                    () -> harness.unobserve("observe_foreign"));

            assertEquals("TASK_OBSERVATION_INVALID", failure.errorCode());
            assertEquals(List.of(), harness.tasks.released);
        }
    }

    /** task/read 只把字段缺失解释为首屏，显式 null 必须在调用 TaskUseCase 前严格拒绝。 */
    @Test
    void rejectsExplicitNullTaskReadCursor() {
        try (Harness harness = new Harness()) {
            RpcCommand command = new RpcCommand(RpcMethod.TASK_READ, MAPPER.createObjectNode()
                    .put("taskThreadId", "thr_child").putNull("cursor").put("limit", 20));

            JaRpcException failure = assertThrows(JaRpcException.class,
                    () -> harness.handler.handle(command).toCompletableFuture().join());

            assertEquals("INVALID_PARAMS", failure.errorCode());
        }
    }

    /** close 的 RPC 结果必须直接投影已提交的 lineage 集合，不能缩成旧布尔 ACK。 */
    @Test
    void taskCloseProjectsCommittedLineageReceipt() {
        try (Harness harness = new Harness()) {
            RpcCommand command = new RpcCommand(RpcMethod.TASK_CLOSE, MAPPER.createObjectNode()
                    .put("taskThreadId", "thr_side_root"));

            ObjectNode result = harness.handler.handle(command).toCompletableFuture().join();

            assertEquals(1, result.size());
            assertEquals("[\"thr_side_root\",\"thr_subagent\"]",
                    result.path("closedThreadIds").toString());
            assertEquals("thr_side_root", harness.tasks.closedRootThreadId);
        }
    }

    /** 用真实 Handler 与 RpcSession 生命周期驱动观察命令，其余应用端口显式拒绝调用。 */
    private static final class Harness implements AutoCloseable {
        private final RecordingTasks tasks;
        private final StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), MAPPER, 4 * 1024 * 1024);
        private final RpcSession session;
        private final TaskHandler handler;

        /** 默认使用每次 observe 都返回新 ID 的 owner。 */
        private Harness() {
            this(false);
        }

        /** 仅在请求时模拟 owner 错误复用 ID，覆盖连接侧失败关闭与已有句柄保护。 */
        private Harness(boolean duplicateObservationIds) {
            tasks = new RecordingTasks(duplicateObservationIds);
            RpcServiceBindings bindings = new RpcServiceBindings(
                    unsupported(WorkspaceUseCase.class), unsupported(WorkspacePathSearchUseCase.class),
                    unsupported(ThreadUseCase.class), RpcTestBindings.unsupportedThreadMcp(),
                    unsupported(TurnUseCase.class),
                    unsupported(ContextCompactionUseCase.class), unsupported(ApprovalUseCase.class),
                    unsupported(CatalogUseCase.class), unsupported(AttachmentUseCase.class),
                    unsupported(AttachmentPreviewUseCase.class), tasks.proxy(),
                    io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.passivePlans(),
                    io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.passiveInteractions(),
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedProcesses(),
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedReviews(),
                    new NoopLifecycle());
            Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-task-observation-test").toAbsolutePath();
            SidecarConfiguration sidecar = new SidecarConfiguration(root.resolve("home"), root.resolve("data"),
                    root.resolve("run"), root.resolve("logs"));
            ConfigurationUseCase configuration = TestConfigurationPorts.unavailable();
            session = new RpcSession(sidecar, MAPPER, CLOCK, writer, ignored -> bindings, configuration);
            session.initialize();
            markReady(session, "0123456789abcdef0123456789abcdef");
            handler = new TaskHandler(session);
        }

        /** 发起 observe 并返回服务端 opaque handle。 */
        private String observe() {
            RpcCommand command = new RpcCommand(RpcMethod.TASK_OBSERVE, MAPPER.createObjectNode()
                    .put("taskThreadId", "thr_child").put("expectedTaskRevision", 0));
            return handler.handle(command).toCompletableFuture().join().path("observationId").asText();
        }

        /** 通过真实 Handler 显式释放连接拥有的句柄。 */
        private void unobserve(String observationId) {
            RpcCommand command = new RpcCommand(RpcMethod.TASK_UNOBSERVE, MAPPER.createObjectNode()
                    .put("observationId", observationId));
            handler.handle(command).toCompletableFuture().join();
        }

        /** 按生产顺序关闭连接与 stdout owner；RpcSession 自身保证重复关闭幂等。 */
        @Override public void close() {
            try { session.close(); } finally { writer.close(); }
        }
    }

    /** 用动态代理只实现 Task observe/unobserve/subscribe，避免测试复制完整应用端口。 */
    private static final class RecordingTasks {
        private final List<String> released = new ArrayList<>();
        private final Set<String> active = new HashSet<>();
        private final boolean duplicateObservationIds;
        private int sequence;
        private String closedRootThreadId;

        /** 可切换为确定的 owner ID 冲突场景，并保持释放必须命中当前活跃句柄。 */
        private RecordingTasks(boolean duplicateObservationIds) {
            this.duplicateObservationIds = duplicateObservationIds;
        }

        /** 返回严格受限代理，任何意外方法都会立即暴露测试越界。 */
        private TaskUseCase proxy() {
            return (TaskUseCase) Proxy.newProxyInstance(TaskUseCase.class.getClassLoader(),
                    new Class<?>[]{TaskUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                        case "subscribe" -> (AutoCloseable) () -> { };
                        case "observe" -> {
                            String observationId = duplicateObservationIds
                                    ? "observe_test_duplicate" : "observe_test_" + ++sequence;
                            TaskUseCase.Observation observation = new TaskUseCase.Observation(
                                    observationId, (String) arguments[0], (long) arguments[1]);
                            active.add(observationId);
                            yield observation;
                        }
                        case "unobserve" -> {
                            String observationId = (String) arguments[0];
                            if (!active.remove(observationId)) {
                                throw new IllegalStateException("observation is not active");
                            }
                            released.add(observationId);
                            yield null;
                        }
                        case "closeSideChat" -> {
                            closedRootThreadId = (String) arguments[0];
                            yield new TaskModels.SideChatCloseReceipt(closedRootThreadId, "thr_top_root",
                                    List.of(closedRootThreadId, "thr_subagent"));
                        }
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    /** 在 owner handle 已登记后暂停 add，稳定复现 close 先取得移除权的交错。 */
    private static final class BlockingObservationSet extends AbstractSet<String> {
        private final Set<String> values = ConcurrentHashMap.newKeySet();
        private final CountDownLatch added = new CountDownLatch(1);
        private final CountDownLatch allowAddReturn = new CountDownLatch(1);

        /** 委托并发存储后等待测试线程完成 close 快照与释放。 */
        @Override public boolean add(String value) {
            boolean addedValue = values.add(value);
            if (addedValue) {
                added.countDown();
                awaitAddRelease();
            }
            return addedValue;
        }

        /** 透传 close 取得的移除操作，保持与生产 set 相同的 compare/remove 语义。 */
        @Override public boolean remove(Object value) {
            return values.remove(value);
        }

        /** close 使用快照遍历当前已登记的句柄。 */
        @Override public Iterator<String> iterator() {
            return values.iterator();
        }

        /** 供 AbstractSet 提供一致的集合大小。 */
        @Override public int size() {
            return values.size();
        }

        /** 限时等待避免回归测试在断言失败时挂住 Maven Reactor。 */
        private void awaitAddRelease() {
            try {
                if (!allowAddReturn.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("observation registration was not released");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("observation registration was interrupted", failure);
            }
        }
    }

    /** 仅为测试安装可控并发集合，不扩展生产 RpcSession 的白盒访问面。 */
    private static void replaceObservationOwnershipSet(RpcSession session, Set<String> observations) {
        try {
            Field field = RpcSession.class.getDeclaredField("taskObservationIds");
            field.setAccessible(true);
            field.set(session, observations);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("could not install observation race barrier", failure);
        }
    }

    /** 测试组合没有外部资源，连接关闭仍可走完整 Deadline 生命周期。 */
    private static final class NoopLifecycle implements DeadlineCloseable {
        /** 无资源可关闭。 */
        @Override public void closeAt(long shutdownDeadlineNanos) { }
        /** 无资源可关闭。 */
        @Override public void close() { }
    }

    /** 非目标端口一旦被调用就失败，防止 Handler 绕过 TaskUseCase。 */
    @SuppressWarnings("unchecked")
    private static <T> T unsupported(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> { throw new UnsupportedOperationException(method.getName()); });
    }
}
