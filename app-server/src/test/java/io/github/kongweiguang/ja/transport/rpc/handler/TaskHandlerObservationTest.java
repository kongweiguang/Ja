// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.attachment.port.in.AttachmentPreviewUseCase;
import io.github.kongweiguang.ja.attachment.port.in.AttachmentUseCase;
import io.github.kongweiguang.ja.catalog.port.in.CatalogUseCase;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationUseCase;
import io.github.kongweiguang.ja.conversation.port.in.ApprovalUseCase;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionUseCase;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.task.port.in.TaskUseCase;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
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
import java.util.Set;

import static io.github.kongweiguang.ja.transport.rpc.runtime.RpcRuntimeTestAccess.markReady;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    /** 用真实 Handler 与 RpcSession 生命周期驱动观察命令，其余应用端口显式拒绝调用。 */
    private static final class Harness implements AutoCloseable {
        private final RecordingTasks tasks = new RecordingTasks();
        private final StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), MAPPER, 4 * 1024 * 1024);
        private final RpcSession session;
        private final TaskHandler handler;

        /** 初始化一个 ready 连接，并让 Task 事件订阅返回可关闭但无副作用的句柄。 */
        private Harness() {
            RpcServiceBindings bindings = new RpcServiceBindings(
                    unsupported(WorkspaceUseCase.class), unsupported(WorkspacePathSearchUseCase.class),
                    unsupported(ThreadUseCase.class), unsupported(TurnUseCase.class),
                    unsupported(ContextCompactionUseCase.class), unsupported(ApprovalUseCase.class),
                    unsupported(CatalogUseCase.class), unsupported(AttachmentUseCase.class),
                    unsupported(AttachmentPreviewUseCase.class), tasks.proxy(),
                    io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.passiveGoals(),
                    io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.passiveInteractions(),
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
        private int sequence;

        /** 返回严格受限代理，任何意外方法都会立即暴露测试越界。 */
        private TaskUseCase proxy() {
            return (TaskUseCase) Proxy.newProxyInstance(TaskUseCase.class.getClassLoader(),
                    new Class<?>[]{TaskUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                        case "subscribe" -> (AutoCloseable) () -> { };
                        case "observe" -> new TaskUseCase.Observation(
                                "observe_test_" + ++sequence, (String) arguments[0], (long) arguments[1]);
                        case "unobserve" -> { released.add((String) arguments[0]); yield null; }
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
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
