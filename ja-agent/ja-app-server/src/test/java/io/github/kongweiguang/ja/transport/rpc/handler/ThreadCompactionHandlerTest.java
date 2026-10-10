// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.catalog.application.api.CatalogUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ApprovalUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ContextCompactionUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.agent.application.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;
import io.github.kongweiguang.ja.transport.rpc.runtime.StdioWriter;
import io.github.kongweiguang.ja.transport.rpc.support.TestConfigurationPorts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.github.kongweiguang.ja.transport.rpc.runtime.RpcRuntimeTestAccess.markReady;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定 thread/compact 的严格 DTO、nullable 结果和稳定错误映射。 */
final class ThreadCompactionHandlerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-29T00:00:00Z"), ZoneOffset.UTC);

    /** changed 结果必须保留 CAS revision、官方 Token 和两个真实身份。 */
    @Test
    void mapsCompactedResult() {
        AtomicReference<ContextCompactionUseCase.Command> observed = new AtomicReference<>();
        ContextCompactionUseCase useCase = (command, events, cancellation) -> {
            observed.set(command);
            return new ContextCompactionUseCase.Result(ContextCompactionUseCase.Outcome.COMPACTED,
                    "cmp_test", "checkpoint_test", 8, 1_000, 400);
        };
        try (Harness harness = new Harness(useCase)) {
            ObjectNode result = harness.handler.handle(command(7)).toCompletableFuture().join();
            assertEquals("thr_test", observed.get().threadId());
            assertEquals(7, observed.get().expectedThreadRevision());
            assertEquals("compacted", result.path("outcome").textValue());
            assertEquals("cmp_test", result.path("compactionId").textValue());
            assertEquals("checkpoint_test", result.path("checkpointId").textValue());
            assertEquals(8, result.path("threadRevision").longValue());
            assertEquals(1_000, result.path("inputTokensBefore").longValue());
            assertEquals(400, result.path("inputTokensAfter").longValue());
        }
    }

    /** unchanged 必须显式返回 JSON null，不能把条件字段变成 optional。 */
    @Test
    void mapsUnchangedWithExplicitNullIdentities() {
        ContextCompactionUseCase useCase = (command, events, cancellation) ->
                new ContextCompactionUseCase.Result(ContextCompactionUseCase.Outcome.UNCHANGED,
                        null, null, 7, 400, 400);
        try (Harness harness = new Harness(useCase)) {
            ObjectNode result = harness.handler.handle(command(7)).toCompletableFuture().join();
            assertNull(result.get("compactionId").textValue());
            assertNull(result.get("checkpointId").textValue());
        }
    }

    /** 应用失败闭集必须逐项映射为同名 JA-RPC errorCode。 */
    @Test
    void mapsStableFailureCatalog() {
        for (ContextCompactionUseCase.Code code : ContextCompactionUseCase.Code.values()) {
            ContextCompactionUseCase useCase = (command, events, cancellation) -> { throw new ContextCompactionUseCase.Failure(code); };
            try (Harness harness = new Harness(useCase)) {
                JaRpcException failure = assertThrows(JaRpcException.class,
                        () -> harness.handler.handle(command(7)));
                assertEquals(code.name(), failure.errorCode());
            }
        }
    }

    /** 未知字段必须在调用应用用例前被 strict params 拒绝。 */
    @Test
    void rejectsUnknownParams() {
        ContextCompactionUseCase useCase = (command, events, cancellation) -> { throw new AssertionError("must not execute"); };
        try (Harness harness = new Harness(useCase)) {
            ObjectNode params = command(7).params().put("legacy", true);
            JaRpcException failure = assertThrows(JaRpcException.class,
                    () -> harness.handler.handle(new RpcCommand(RpcMethod.THREAD_COMPACT, params)));
            assertEquals("INVALID_PARAMS", failure.errorCode());
        }
    }

    /** 取消只触达本连接的活动摘要；ACK 不代替原请求的 CANCELLED 终态。 */
    @Test
    void cancelsActiveCompactionWithoutClosingSession() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        ContextCompactionUseCase useCase = (command, events, cancellation) -> {
            try (io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken.Registration ignored =
                    cancellation.onCancellation(stopped::countDown)) {
                started.countDown();
                if (!stopped.await(3, TimeUnit.SECONDS)) {
                    throw new AssertionError("cancel did not reach compaction");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            throw new ContextCompactionUseCase.Failure(ContextCompactionUseCase.Code.CANCELLED);
        };
        try (Harness harness = new Harness(useCase)) {
            CompletableFuture<Void> running = CompletableFuture.runAsync(() ->
                    assertEquals("CANCELLED", assertThrows(JaRpcException.class,
                            () -> harness.handler.handle(command(7))).errorCode()));
            assertEquals(true, started.await(3, TimeUnit.SECONDS));
            RpcCommand cancel = new RpcCommand(RpcMethod.THREAD_COMPACT_CANCEL,
                    MAPPER.createObjectNode().put("threadId", "thr_test"));
            assertEquals(true, harness.handler.handle(cancel).toCompletableFuture().join().path("accepted").booleanValue());
            running.get(3, TimeUnit.SECONDS);
            assertEquals(false, harness.handler.handle(cancel).toCompletableFuture().join().path("accepted").booleanValue());
        }
    }

    /** 连接关闭只结束事件观察；只有显式 compact/cancel 才能取消已受理执行。 */
    @Test
    void closingSessionDoesNotCancelAcceptedCompaction() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Boolean> cancelled = new AtomicReference<>(false);
        ContextCompactionUseCase useCase = (command, events, cancellation) -> {
            try (io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken.Registration ignored =
                    cancellation.onCancellation(() -> cancelled.set(true))) {
                started.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) {
                    throw new AssertionError("test did not release accepted compaction");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            return new ContextCompactionUseCase.Result(ContextCompactionUseCase.Outcome.UNCHANGED,
                    null, null, 7, 400, 400);
        };
        Harness harness = new Harness(useCase);
        CompletableFuture<ObjectNode> running = CompletableFuture.supplyAsync(() ->
                harness.handler.handle(command(7)).toCompletableFuture().join());
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS));
            harness.session.close();
            assertFalse(cancelled.get());
            assertFalse(running.isDone());
            release.countDown();
            assertEquals("unchanged", running.get(3, TimeUnit.SECONDS).path("outcome").textValue());
        } finally {
            release.countDown();
            try {
                running.handle((value, failure) -> null).get(3, TimeUnit.SECONDS);
            } finally {
                harness.close();
            }
        }
    }

    /** 整体运行时关闭封闭新准入，并将取消传给已登记的 Engine 压缩。 */
    @Test
    void runtimeShutdownCancelsActiveCompactionAndRejectsQueuedAdmission() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        ContextCompactionUseCase useCase = (command, events, cancellation) -> {
            invocations.incrementAndGet();
            try (io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken.Registration ignored =
                    cancellation.onCancellation(stopped::countDown)) {
                started.countDown();
                if (!stopped.await(3, TimeUnit.SECONDS)) {
                    throw new AssertionError("shutdown cancellation did not reach compaction");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            throw new ContextCompactionUseCase.Failure(ContextCompactionUseCase.Code.CANCELLED);
        };
        try (Harness harness = new Harness(useCase)) {
            CompletableFuture<Void> running = CompletableFuture.runAsync(() ->
                    assertEquals("CANCELLED", assertThrows(JaRpcException.class,
                            () -> harness.handler.handle(command(7))).errorCode()));
            assertTrue(started.await(3, TimeUnit.SECONDS));

            harness.handler.cancelForShutdown();

            running.get(3, TimeUnit.SECONDS);
            JaRpcException rejected = assertThrows(JaRpcException.class,
                    () -> harness.handler.handle(command(8)));
            assertEquals("SHUTTING_DOWN", rejected.errorCode());
            assertEquals(1, invocations.get());
        }
    }

    /** 关闭取消会遍历全部活动 source，并汇总每个 source 的回调故障。 */
    @Test
    void runtimeShutdownAttemptsAllCancellationCallbacksAndAggregatesFailures() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch stopped = new CountDownLatch(2);
        ContextCompactionUseCase useCase = (command, events, cancellation) -> {
            try (io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken.Registration ignored =
                    cancellation.onCancellation(() -> {
                        stopped.countDown();
                        throw new IllegalStateException("cancel callback failed");
                    })) {
                started.countDown();
                if (!stopped.await(3, TimeUnit.SECONDS)) {
                    throw new AssertionError("shutdown must attempt every active source");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            throw new ContextCompactionUseCase.Failure(ContextCompactionUseCase.Code.CANCELLED);
        };
        try (Harness harness = new Harness(useCase)) {
            CompletableFuture<Void> first = runCancelled(harness, command("thr_first", 7));
            CompletableFuture<Void> second = runCancelled(harness, command("thr_second", 9));
            assertTrue(started.await(3, TimeUnit.SECONDS));

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    harness.handler::cancelForShutdown);

            assertEquals(1, failure.getSuppressed().length);
            assertEquals(0, stopped.getCount());
            first.get(3, TimeUnit.SECONDS);
            second.get(3, TimeUnit.SECONDS);
        }
    }

    /** 将执行线程中的预期 CANCELLED RPC 错误转换为可等待的测试终结状态。 */
    private static CompletableFuture<Void> runCancelled(Harness harness, RpcCommand command) {
        return CompletableFuture.runAsync(() ->
                assertEquals("CANCELLED", assertThrows(JaRpcException.class,
                        () -> harness.handler.handle(command)).errorCode()));
    }

    /** 构造精确 thread/compact 命令，避免每个断言重复 DTO 拼装。 */
    private static RpcCommand command(long revision) {
        return command("thr_test", revision);
    }

    /** 为关闭聚合测试构造独立 Thread 身份。 */
    private static RpcCommand command(String threadId, long revision) {
        return new RpcCommand(RpcMethod.THREAD_COMPACT, MAPPER.createObjectNode()
                .put("threadId", threadId).put("expectedThreadRevision", revision));
    }

    /** 使用真实 RpcSession ready 状态机，只把非目标端口替换为显式拒绝代理。 */
    private static final class Harness implements AutoCloseable {
        private final StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), MAPPER, 4 * 1024 * 1024);
        private final RpcSession session;
        private final ThreadCompactionHandler handler;

        /** 注入唯一允许执行的压缩端口，测试不创建存储、Provider 或配置文件。 */
        private Harness(ContextCompactionUseCase compactions) {
            RpcServiceBindings bindings = new RpcServiceBindings(unsupported(io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase.class),
                    unsupported(io.github.kongweiguang.ja.workspace.application.api.WorkspacePathSearchUseCase.class),
                    unsupported(ThreadUseCase.class), io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedThreadMcp(),
                    unsupported(TurnUseCase.class), compactions,
                    unsupported(ApprovalUseCase.class), unsupported(CatalogUseCase.class),
                    unsupported(io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase.class),
                    unsupported(io.github.kongweiguang.ja.attachment.application.api.AttachmentPreviewUseCase.class),
                    io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.passiveTasks(),
                    io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.passivePlans(),
                    io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.passiveInteractions(),
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedProcesses(),
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedReviews(),
                    new DeadlineCloseable() {
                        /** 测试组合没有外部资源，deadline close 保持无副作用。 */
                        @Override public void closeAt(long shutdownDeadlineNanos) { }
                        /** 测试组合没有外部资源，普通 close 保持无副作用。 */
                        @Override public void close() { }
                    });
            Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-thread-compaction-handler-test").toAbsolutePath();
            SidecarConfiguration sidecar = new SidecarConfiguration(root.resolve("home"), root.resolve("data"),
                    root.resolve("run"), root.resolve("logs"));
            session = new RpcSession(sidecar, MAPPER, CLOCK, writer, ignored -> bindings,
                    TestConfigurationPorts.unavailable());
            session.initialize();
            markReady(session, "0123456789abcdef0123456789abcdef");
            handler = new ThreadCompactionHandler(session);
        }

        /** 按生产顺序关闭 session 与 writer，避免 stdout owner 线程泄漏。 */
        @Override
        public void close() {
            try { session.close(); } finally { writer.close(); }
        }
    }

    /** 非目标端口一旦被调用就失败，防止 Handler 越过压缩用例边界。 */
    @SuppressWarnings("unchecked")
    private static <T> T unsupported(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
    }
}
