// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.transport.rpc.runtime;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.kongweiguang.ja.transport.rpc.handler.HandshakeContractTestAccess;
import io.github.kongweiguang.ja.transport.rpc.handler.HealthShutdownHandler;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.transport.rpc.RpcServicesFactory;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcCodec;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings;
import io.github.kongweiguang.ja.transport.rpc.support.TestConfigurationPorts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationScope;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationUseCase;
import io.github.kongweiguang.ja.agent.application.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.agent.application.concurrent.ShutdownDeadline;
import io.github.kongweiguang.ja.agent.application.concurrent.BoundedVirtualExecutor;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.foundation.runtime.ProductVersion;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.error.OutputSchemaException;
import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import io.github.kongweiguang.ja.conversation.application.api.ContextCompactionUseCase;
import io.github.kongweiguang.ja.conversation.application.api.InteractionUseCase;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.conversation.application.api.NativeExecutionContext;
import io.github.kongweiguang.ja.foundation.lifecycle.NativeClientOwner;
import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.application.api.PlanEvent;
import io.github.kongweiguang.ja.plan.application.api.PlanEventSink;
import io.github.kongweiguang.ja.plan.application.api.PlanUseCase;
import io.github.kongweiguang.ja.collaboration.application.api.TaskUseCase;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通过真实且有界的 RpcServer 传输验证严格握手、配置脱敏与关闭边界。 */
final class RpcServerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-25T12:00:00Z"), ZoneOffset.UTC);

    /** EOF 必须取消独立压缩源，再等待其完成并关闭应用 owner。 */
    @Test
    void eofCancelsActiveCompactionBeforeWaitingForRequests() throws Exception {
        assertCompactionCancelledOnStop(false);
    }

    /** 显式整体关闭与 EOF 共用取消边界，不能把活动压缩误作不可静默请求。 */
    @Test
    void shutdownCancelsActiveCompactionBeforeWaitingForRequests() throws Exception {
        assertCompactionCancelledOnStop(true);
    }

    /** 受控压缩只等待自己的令牌，证明传输关闭没有依赖 Session 的另一个取消源。 */
    private static void assertCompactionCancelledOnStop(boolean explicitShutdown) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicReference<String> reason = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean();
        ContextCompactionUseCase compactions = (command, events, token) -> {
            entered.countDown();
            try (CancellationToken.Registration registration = token.onCancellation(cancelled::countDown)) {
                if (!cancelled.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("compaction was not cancelled");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("compaction wait interrupted", interrupted);
            }
            reason.set(token.reason().orElse(null));
            return new ContextCompactionUseCase.Result(ContextCompactionUseCase.Outcome.UNCHANGED,
                    null, null, 1, 0, 0);
        };
        TurnUseCase turns = (TurnUseCase) Proxy.newProxyInstance(RpcServerTest.class.getClassLoader(),
                new Class<?>[]{TurnUseCase.class}, (proxy, method, arguments) -> {
                    if ("stopAccepting".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        TaskUseCase tasks = (TaskUseCase) Proxy.newProxyInstance(RpcServerTest.class.getClassLoader(),
                new Class<?>[]{TaskUseCase.class}, (proxy, method, arguments) -> {
                    return switch (method.getName()) {
                        case "subscribe" -> (AutoCloseable) () -> { };
                        case "listRootActivities" -> List.of();
                        case "closeTemporarySideChats" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        RpcServiceBindings base = RpcTestBindings.create(null, null, null, null, null,
                () -> closed.set(true));
        RpcServiceBindings bindings = new RpcServiceBindings(base.workspaces(), base.workspacePathSearch(),
                base.threads(), base.threadMcp(), turns, compactions, base.approvals(), base.catalog(),
                base.attachments(), base.attachmentPreviews(), tasks, base.plans(),
                base.interactions(),
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedProcesses(),
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedReviews(), base.lifecycle());
        try (PipedInputStream input = new PipedInputStream(64 * 1024);
             PipedOutputStream sender = new PipedOutputStream(input)) {
            RpcServer server = new RpcServer(input, new ByteArrayOutputStream(), testConfiguration(), CLOCK,
                    ignored -> bindings, TestConfigurationPorts.unavailable());
            AtomicInteger exit = new AtomicInteger(-1);
            Thread reader = Thread.ofVirtual().start(() -> exit.set(server.run()));
            try {
                sender.write((mapper.writeValueAsString(initialize(mapper)) + "\n"
                        + "{\"jsonrpc\":\"2.0\",\"method\":\"runtime/initialized\",\"params\":{\"readyToken\":\"0123456789abcdef0123456789abcdef\"}}\n"
                        + "{\"jsonrpc\":\"2.0\",\"id\":\"c:compact\",\"method\":\"thread/compact\",\"params\":{\"threadId\":\"thr_stop\",\"expectedThreadRevision\":1}}\n")
                        .getBytes(StandardCharsets.UTF_8));
                sender.flush();
                assertTrue(entered.await(5, TimeUnit.SECONDS), "compaction did not start");
                if (explicitShutdown) {
                    sender.write("{\"jsonrpc\":\"2.0\",\"id\":\"c:stop\",\"method\":\"runtime/shutdown\",\"params\":{\"force\":true}}\n"
                            .getBytes(StandardCharsets.UTF_8));
                    sender.flush();
                } else {
                    sender.close();
                }
                reader.join(5_000);
                assertFalse(reader.isAlive(), "runtime did not finish after cancelling compaction");
                assertEquals(0, exit.get());
                assertEquals("runtime_closed", reason.get());
                assertTrue(closed.get(), "application owner was not closed");
            } finally {
                sender.close();
                reader.join(5_000);
            }
        }
    }

    /**
     * 存储阻塞时允许无根 Native owner 先完成本地关闭回执，显式恢复后仍复用原 RPC 服务绑定。
     * @author kongweiguang
     */
    @Test
    void blockedStorageCanRetryWithoutReplacingTheRpcSession() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicBoolean retried = new AtomicBoolean();
        RpcServiceBindings bindings = new EmptyServices(new AtomicBoolean()).bindings();
        io.github.kongweiguang.ja.transport.rpc.RpcServicesFactory factory =
                new io.github.kongweiguang.ja.transport.rpc.RpcServicesFactory() {
            /** 只在显式重试前返回环境性存储错误，以覆盖传输层真实准入栅栏。 */
            @Override public RpcServiceBindings open(ObjectMapper ignored) {
                if (!retried.get()) {
                    throw new StorageException(StorageException.Code.IO, "opaque");
                }
                return bindings;
            }
            /** 就绪状态变化时保持恢复实例标识不变，验证客户端仍观察同一故障实例。 */
            @Override public RecoveryStatus storageRecovery() {
                return new RecoveryStatus("11111111-1111-1111-1111-111111111111", retried.get() ? "ready" : "blocked",
                        "DISK_FULL", true, false, false);
            }
            /** 仅收到显式 retry 操作后解除 fixture 故障，避免自动恢复造成假阳性。 */
            @Override public void retryStorage() { retried.set(true); }
        };
        Process nativeHost = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", System.getProperty("java.class.path"),
                io.github.kongweiguang.ja.collaboration.application.service.NativeHostProcessFixture.class.getName())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try (StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), mapper, 4 * 1024 * 1024);
             RpcSession session = new RpcSession(testConfiguration(), mapper, CLOCK, writer, factory,
                     TestConfigurationPorts.unavailable())) {
            session.initialize();
            session.ready("0123456789abcdef0123456789abcdef");
            assertFalse(session.servicesAvailable());
            session.requireConnected();
            session.refreshPreparedWorkspaces();

            NativeExecutionContext context = NativeExecutionContext.shared();
            NativeClientOwner owner = context.registerNativeClient(nativeHost.pid(),
                    "native_" + java.util.UUID.randomUUID().toString().replace("-", ""));
            String contextId = context.register(Map.of(), null, Optional.of(owner));
            String requestId = "storage_blocked_client_close";
            context.bindRpcRequest(requestId, contextId);
            try (NativeExecutionContext.Scope ignored = context.enterRpcRequest(requestId)) {
                ObjectNode closedClient = new HealthShutdownHandler(session).handle(new RpcCommand(
                        RpcMethod.RUNTIME_CLIENT_CLOSE, mapper.createObjectNode().put("timeoutMs", 1_000)))
                        .toCompletableFuture().join();
                assertTrue(closedClient.path("accepted").booleanValue());
                assertTrue(context.nativeClientClosed(owner));
            } finally {
                context.unbindRpcRequest(requestId);
                context.release(contextId);
            }

            io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException blocked =
                    assertThrows(io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException.class,
                    session::threads);
            assertEquals("STORAGE_UNAVAILABLE", blocked.errorCode());
            String instance = session.serverInstanceId();
            session.retryStorage();
            assertTrue(session.servicesAvailable());
            assertSame(bindings.threads(), session.threads());
            assertEquals(instance, session.serverInstanceId());
        } finally {
            nativeHost.destroyForcibly();
            assertTrue(nativeHost.waitFor(2, TimeUnit.SECONDS), "Native host process fixture failed to exit");
        }
    }

    /** ready flush 阻塞时，有限关闭期限先失败且不启动 one-shot runtime close。 */
    @Test
    void closeDeadlineWaitsForReadyFlushBeforeCapturingRuntimeOwners() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        BlockingReadyOutputStream output = new BlockingReadyOutputStream();
        AtomicInteger runtimeCloses = new AtomicInteger();
        RpcServiceBindings bindings = RpcTestBindings.create(null, null, null, null, null,
                runtimeCloses::incrementAndGet);
        AtomicReference<Throwable> readyFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread closer = null;
        try (StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024);
             RpcSession session = new RpcSession(testConfiguration(), mapper, CLOCK, writer,
                     ignored -> bindings, TestConfigurationPorts.unavailable())) {
            session.initialize();
            Thread ready = Thread.ofVirtual().start(() -> {
                try {
                    session.ready("0123456789abcdef0123456789abcdef");
                } catch (Throwable failure) {
                    readyFailure.set(failure);
                }
            });
            try {
                assertTrue(output.readyFlushEntered.await(2, TimeUnit.SECONDS), "ready flush did not start");
                closer = Thread.ofVirtual().start(() -> {
                    try {
                        session.close(ShutdownDeadline.start(Duration.ofMillis(100)));
                    } catch (Throwable failure) {
                        closeFailure.set(failure);
                    }
                });
                closer.join(1_000);
                assertFalse(closer.isAlive(), "close must honor its deadline while ready flush is blocked");
                assertTrue(closeFailure.get() instanceof ShutdownDeadline.ForcedTerminationException);
                assertEquals("rpc session publications timed out", closeFailure.get().getMessage());
                assertEquals(0, runtimeCloses.get(), "the coordinator must not capture owners before ready exits");
            } finally {
                output.releaseReadyFlush();
                ready.join(2_000);
                if (closer != null) {
                    closer.join(2_000);
                }
            }
            assertFalse(ready.isAlive(), "ready must finish after the controlled flush is released");
            assertTrue(readyFailure.get() == null, "ready must complete after the controlled flush is released");

            session.close(ShutdownDeadline.start(Duration.ofSeconds(2)));
            assertEquals(1, runtimeCloses.get(), "the later close must capture and close the runtime owner once");
        }
    }

    /** read 阻塞时 shutdown 有界退出；读异常仍为主因，失败清理的句柄由后续 close 重试。 */
    @Test
    void closeDeadlineWaitsForInteractionReadAndRetainsFailedCleanupOwner() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        AtomicInteger subscribeCalls = new AtomicInteger();
        AtomicInteger subscriptionCloseAttempts = new AtomicInteger();
        AtomicInteger runtimeCloses = new AtomicInteger();
        IllegalStateException readFailure = new IllegalStateException("interaction read failed");
        IllegalStateException cleanupFailure = new IllegalStateException("interaction unsubscribe failed");
        InteractionUseCase interactions = (InteractionUseCase) Proxy.newProxyInstance(
                RpcServerTest.class.getClassLoader(), new Class<?>[]{InteractionUseCase.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "subscribe" -> {
                        subscribeCalls.incrementAndGet();
                        yield (AutoCloseable) () -> {
                            if (subscriptionCloseAttempts.incrementAndGet() == 1) {
                                throw cleanupFailure;
                            }
                        };
                    }
                    case "read" -> {
                        readEntered.countDown();
                        awaitLatch(releaseRead);
                        throw readFailure;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        RpcServiceBindings base = RpcTestBindings.create(null, null, null, null, null,
                runtimeCloses::incrementAndGet);
        RpcServiceBindings bindings = new RpcServiceBindings(base.workspaces(), base.workspacePathSearch(),
                base.threads(), base.threadMcp(), base.turns(), base.compactions(), base.approvals(),
                base.catalog(), base.attachments(), base.attachmentPreviews(), base.tasks(), base.plans(),
                interactions,
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedProcesses(),
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedReviews(), base.lifecycle());
        AtomicReference<Throwable> observationFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread closer = null;
        try (StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), mapper, 4 * 1024 * 1024);
             RpcSession session = new RpcSession(testConfiguration(), mapper, CLOCK, writer,
                     ignored -> bindings, TestConfigurationPorts.unavailable())) {
            session.initialize();
            session.ready("0123456789abcdef0123456789abcdef");
            Thread observer = Thread.ofVirtual().start(() -> {
                try {
                    session.observeInteraction("thr_wait");
                } catch (Throwable failure) {
                    observationFailure.set(failure);
                }
            });
            try {
                assertTrue(readEntered.await(2, TimeUnit.SECONDS), "interaction read did not start");
                closer = Thread.ofVirtual().start(() -> {
                    try {
                        session.close(ShutdownDeadline.start(Duration.ofMillis(100)));
                    } catch (Throwable failure) {
                        closeFailure.set(failure);
                    }
                });
                closer.join(1_000);
                assertFalse(closer.isAlive(), "close must honor its deadline while interaction read is blocked");
                assertTrue(closeFailure.get() instanceof ShutdownDeadline.ForcedTerminationException);
                assertEquals("rpc session publications timed out", closeFailure.get().getMessage());
                assertEquals(0, subscriptionCloseAttempts.get(),
                        "the coordinator must wait for acquisition before closing its owner");
                assertEquals(0, runtimeCloses.get(), "the coordinator must not capture the runtime owner early");
            } finally {
                releaseRead.countDown();
                observer.join(2_000);
                if (closer != null) {
                    closer.join(2_000);
                }
            }
            assertFalse(observer.isAlive(), "interaction acquisition must finish after read is released");
            assertSame(readFailure, observationFailure.get());
            assertEquals(1, readFailure.getSuppressed().length);
            assertEquals("interaction observation could not close", readFailure.getSuppressed()[0].getMessage());
            assertSame(cleanupFailure, readFailure.getSuppressed()[0].getCause());
            assertEquals(1, subscriptionCloseAttempts.get());

            session.close(ShutdownDeadline.start(Duration.ofSeconds(2)));
            assertEquals(2, subscriptionCloseAttempts.get(), "close must retry the retained interaction owner");
            assertEquals(1, runtimeCloses.get());
            io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException closed = assertThrows(
                    io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException.class,
                    () -> session.observeInteraction("thr_after_close"));
            assertEquals("SHUTTING_DOWN", closed.errorCode());
            assertEquals(1, subscribeCalls.get(), "a closed session must not publish a new subscription");
        }
    }

    /** 首份 Task 订阅失败后可安全重试，新旧生命周期 owner 各自只关闭一次。 */
    @Test
    void retriesAfterFailedTaskSubscriptionAndClosesEachRuntimeOnce() {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger taskSubscribeCalls = new AtomicInteger();
        AtomicInteger taskSubscriptionCloses = new AtomicInteger();
        AtomicInteger planSubscribeCalls = new AtomicInteger();
        AtomicInteger planSubscriptionCloses = new AtomicInteger();
        AtomicInteger runtimeCloses = new AtomicInteger();
        AtomicInteger openedRuntimes = new AtomicInteger();
        AtomicInteger retries = new AtomicInteger();
        IllegalStateException subscriptionFailure = new IllegalStateException("task subscription failed");
        TaskUseCase tasks = (TaskUseCase) Proxy.newProxyInstance(RpcServerTest.class.getClassLoader(),
                new Class<?>[]{TaskUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "subscribe" -> {
                        if (taskSubscribeCalls.incrementAndGet() == 1) {
                            throw subscriptionFailure;
                        }
                        yield (AutoCloseable) taskSubscriptionCloses::incrementAndGet;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        PlanUseCase plans = (PlanUseCase) Proxy.newProxyInstance(RpcServerTest.class.getClassLoader(),
                new Class<?>[]{PlanUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "subscribePlan" -> {
                        planSubscribeCalls.incrementAndGet();
                        yield (AutoCloseable) planSubscriptionCloses::incrementAndGet;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        RpcServicesFactory factory = blockedStorageFactory(
                ignored -> subscriptionBindings(tasks, plans, runtimeCloses::incrementAndGet),
                openedRuntimes, retries);
        try (StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), mapper, 4 * 1024 * 1024);
             RpcSession session = new RpcSession(testConfiguration(), mapper, CLOCK, writer, factory,
                     TestConfigurationPorts.unavailable(), 72)) {
            session.initialize();
            session.ready("0123456789abcdef0123456789abcdef");

            assertSame(subscriptionFailure, assertThrows(IllegalStateException.class, session::retryStorage));
            assertFalse(session.servicesAvailable());
            assertEquals(1, runtimeCloses.get(), "failed startup lifecycle must be closed before retry");

            session.retryStorage();
            assertTrue(session.servicesAvailable());
            session.close(ShutdownDeadline.start(Duration.ofSeconds(2)));

            assertEquals(2, taskSubscribeCalls.get());
            assertEquals(1, taskSubscriptionCloses.get());
            assertEquals(1, planSubscribeCalls.get());
            assertEquals(1, planSubscriptionCloses.get());
            assertEquals(2, runtimeCloses.get(), "each opened runtime lifecycle must close once");
            assertEquals(2, openedRuntimes.get());
            assertEquals(2, retries.get());
        }
    }

    /** 第二份订阅失败时先撤销已取得的 Task 订阅，再关闭 owner，并允许重新装配。 */
    @Test
    void closesTaskSubscriptionWhenPlanSubscriptionFails() {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger taskSubscriptionCloses = new AtomicInteger();
        AtomicInteger planSubscribeCalls = new AtomicInteger();
        AtomicInteger planSubscriptionCloses = new AtomicInteger();
        AtomicInteger runtimeCloses = new AtomicInteger();
        AtomicInteger openedRuntimes = new AtomicInteger();
        AtomicInteger retries = new AtomicInteger();
        IllegalStateException subscriptionFailure = new IllegalStateException("plan subscription failed");
        TaskUseCase tasks = (TaskUseCase) Proxy.newProxyInstance(RpcServerTest.class.getClassLoader(),
                new Class<?>[]{TaskUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "subscribe" -> (AutoCloseable) taskSubscriptionCloses::incrementAndGet;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        PlanUseCase plans = (PlanUseCase) Proxy.newProxyInstance(RpcServerTest.class.getClassLoader(),
                new Class<?>[]{PlanUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "subscribePlan" -> {
                        if (planSubscribeCalls.incrementAndGet() == 1) {
                            throw subscriptionFailure;
                        }
                        yield (AutoCloseable) planSubscriptionCloses::incrementAndGet;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        RpcServicesFactory factory = blockedStorageFactory(
                ignored -> subscriptionBindings(tasks, plans, runtimeCloses::incrementAndGet),
                openedRuntimes, retries);
        try (StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), mapper, 4 * 1024 * 1024);
             RpcSession session = new RpcSession(testConfiguration(), mapper, CLOCK, writer, factory,
                     TestConfigurationPorts.unavailable(), 73)) {
            session.initialize();
            session.ready("0123456789abcdef0123456789abcdef");

            assertSame(subscriptionFailure, assertThrows(IllegalStateException.class, session::retryStorage));
            assertEquals(1, taskSubscriptionCloses.get());
            assertEquals(1, runtimeCloses.get());
            assertFalse(session.servicesAvailable());

            session.retryStorage();
            assertTrue(session.servicesAvailable());
            session.close(ShutdownDeadline.start(Duration.ofSeconds(2)));

            assertEquals(2, taskSubscriptionCloses.get());
            assertEquals(2, planSubscribeCalls.get());
            assertEquals(1, planSubscriptionCloses.get());
            assertEquals(2, runtimeCloses.get());
            assertEquals(2, openedRuntimes.get());
            assertEquals(2, retries.get());
        }
    }

    /** 清理失败保留其 owner、报告原启动原因，并阻止新运行时覆盖未关闭资源。 */
    @Test
    void retainsFailedStartupOwnersAndRejectsRetryWithCleanupDiagnostics() {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger taskCloseAttempts = new AtomicInteger();
        AtomicInteger lifecycleCloseAttempts = new AtomicInteger();
        AtomicInteger openedRuntimes = new AtomicInteger();
        AtomicInteger retries = new AtomicInteger();
        IllegalStateException startupFailure = new IllegalStateException("plan subscription failed");
        IllegalStateException taskCloseFailure = new IllegalStateException("task unsubscribe failed");
        IllegalStateException lifecycleCloseFailure = new IllegalStateException("runtime close failed");
        TaskUseCase tasks = (TaskUseCase) Proxy.newProxyInstance(RpcServerTest.class.getClassLoader(),
                new Class<?>[]{TaskUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "subscribe" -> (AutoCloseable) () -> {
                        if (taskCloseAttempts.incrementAndGet() == 1) {
                            throw taskCloseFailure;
                        }
                    };
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        PlanUseCase plans = (PlanUseCase) Proxy.newProxyInstance(RpcServerTest.class.getClassLoader(),
                new Class<?>[]{PlanUseCase.class}, (proxy, method, arguments) -> {
                    if ("subscribePlan".equals(method.getName())) {
                        throw startupFailure;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        RpcServicesFactory factory = blockedStorageFactory(ignored -> subscriptionBindings(tasks, plans, () -> {
            if (lifecycleCloseAttempts.incrementAndGet() == 1) {
                throw lifecycleCloseFailure;
            }
        }), openedRuntimes, retries);
        try (StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), mapper, 4 * 1024 * 1024);
             RpcSession session = new RpcSession(testConfiguration(), mapper, CLOCK, writer, factory,
                     TestConfigurationPorts.unavailable(), 74)) {
            session.initialize();
            session.ready("0123456789abcdef0123456789abcdef");

            IllegalStateException actual = assertThrows(IllegalStateException.class, session::retryStorage);
            assertSame(startupFailure, actual);
            assertEquals(2, actual.getSuppressed().length);
            assertEquals("task event subscription could not close", actual.getSuppressed()[0].getMessage());
            assertSame(taskCloseFailure, actual.getSuppressed()[0].getCause());
            assertSame(lifecycleCloseFailure, actual.getSuppressed()[1]);
            assertFalse(session.servicesAvailable());

            io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException retryFailure = assertThrows(
                    io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException.class, session::retryStorage);
            assertEquals("SHUTTING_DOWN", retryFailure.errorCode());
            assertEquals(1, retries.get(), "retained startup owners must block factory retry");

            session.close(ShutdownDeadline.start(Duration.ofSeconds(2)));
            assertEquals(2, taskCloseAttempts.get(), "final close must retry the retained subscription owner");
            assertEquals(2, lifecycleCloseAttempts.get(), "final close must retry the retained lifecycle owner");
        }
    }

    /** 关闭等待启动耗尽期限时不启动 one-shot 清理；启动回滚失败的迟到 owner 由后续关闭重试。 */
    @Test
    void closeDuringSubscriptionPreventsRuntimePublicationAndDoubleClose() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CountDownLatch taskSubscribeEntered = new CountDownLatch(1);
        CountDownLatch returnTaskSubscription = new CountDownLatch(1);
        AtomicInteger taskSubscriptionCloseAttempts = new AtomicInteger();
        AtomicInteger planSubscriptionCloses = new AtomicInteger();
        AtomicInteger runtimeCloses = new AtomicInteger();
        AtomicInteger openedRuntimes = new AtomicInteger();
        AtomicInteger retries = new AtomicInteger();
        IllegalStateException taskCloseFailure = new IllegalStateException("task unsubscribe failed");
        TaskUseCase tasks = (TaskUseCase) Proxy.newProxyInstance(RpcServerTest.class.getClassLoader(),
                new Class<?>[]{TaskUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "subscribe" -> {
                        taskSubscribeEntered.countDown();
                        awaitLatch(returnTaskSubscription);
                        yield (AutoCloseable) () -> {
                            if (taskSubscriptionCloseAttempts.incrementAndGet() == 1) {
                                throw taskCloseFailure;
                            }
                        };
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        PlanUseCase plans = (PlanUseCase) Proxy.newProxyInstance(RpcServerTest.class.getClassLoader(),
                new Class<?>[]{PlanUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "subscribePlan" -> (AutoCloseable) planSubscriptionCloses::incrementAndGet;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        RpcServicesFactory factory = blockedStorageFactory(
                ignored -> subscriptionBindings(tasks, plans, runtimeCloses::incrementAndGet),
                openedRuntimes, retries);
        Thread startup = null;
        try (StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), mapper, 4 * 1024 * 1024);
             RpcSession session = new RpcSession(testConfiguration(), mapper, CLOCK, writer, factory,
                     TestConfigurationPorts.unavailable(), 75)) {
            session.initialize();
            session.ready("0123456789abcdef0123456789abcdef");
            AtomicReference<Throwable> startupFailure = new AtomicReference<>();
            startup = Thread.ofVirtual().start(() -> {
                try {
                    session.retryStorage();
                } catch (Throwable failure) {
                    startupFailure.set(failure);
                }
            });
            try {
                assertTrue(taskSubscribeEntered.await(2, TimeUnit.SECONDS), "Task subscription did not start");

                ShutdownDeadline.ForcedTerminationException closeFailure = assertThrows(
                        ShutdownDeadline.ForcedTerminationException.class,
                        () -> session.close(ShutdownDeadline.start(Duration.ofMillis(100))));
                assertEquals("rpc session runtime startup timed out", closeFailure.getMessage());
                assertEquals(0, taskSubscriptionCloseAttempts.get(),
                        "the close coordinator must not snapshot owners before startup settles");
            } finally {
                returnTaskSubscription.countDown();
            }
            startup.join(2_000);

            assertFalse(startup.isAlive(), "startup must finish after its acquired subscription is released");
            assertTrue(startupFailure.get() instanceof io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException);
            io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException startupError =
                    (io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException) startupFailure.get();
            assertEquals("SHUTTING_DOWN", startupError.errorCode());
            assertEquals(1, startupError.getSuppressed().length);
            assertEquals("task event subscription could not close", startupError.getSuppressed()[0].getMessage());
            assertSame(taskCloseFailure, startupError.getSuppressed()[0].getCause());
            assertFalse(session.servicesAvailable());
            assertEquals(1, taskSubscriptionCloseAttempts.get());
            assertEquals(1, planSubscriptionCloses.get());
            assertEquals(1, runtimeCloses.get());
            assertEquals(1, openedRuntimes.get());
            assertEquals(1, retries.get());

            session.close(ShutdownDeadline.start(Duration.ofSeconds(2)));
            assertEquals(2, taskSubscriptionCloseAttempts.get(),
                    "final close must retry the retained subscription owner");
            assertEquals(1, runtimeCloses.get(), "startup rollback already closed the lifecycle owner");
        } finally {
            returnTaskSubscription.countDown();
            if (startup != null) {
                startup.join(2_000);
            }
        }
    }

    /** 构造先处于存储阻塞、收到 retry 后再逐次打开服务图的工厂。 */
    private static RpcServicesFactory blockedStorageFactory(IntFunction<RpcServiceBindings> openAfterRetry,
                                                            AtomicInteger openedRuntimes,
                                                            AtomicInteger retries) {
        AtomicBoolean storageReady = new AtomicBoolean();
        return new RpcServicesFactory() {
            /** 存储尚未显式恢复时返回受控故障，每次恢复尝试提供独立生命周期 owner。 */
            @Override
            public RpcServiceBindings open(ObjectMapper ignored) {
                if (!storageReady.get()) {
                    throw new StorageException(StorageException.Code.IO, "opaque");
                }
                return openAfterRetry.apply(openedRuntimes.incrementAndGet());
            }

            /** 恢复状态只暴露固定分类，供 initialize 正常建立降级连接。 */
            @Override
            public RecoveryStatus storageRecovery() {
                return new RecoveryStatus("11111111-1111-1111-1111-111111111111",
                        storageReady.get() ? "ready" : "blocked", "DISK_FULL", true, false, false);
            }

            /** 只有客户端明确 retry 后才能打开服务图。 */
            @Override
            public void retryStorage() {
                retries.incrementAndGet();
                storageReady.set(true);
            }
        };
    }

    /** 用指定 Task/Plan 订阅 owner 和生命周期 owner 组装其余拒绝式 RPC 端口。 */
    private static RpcServiceBindings subscriptionBindings(TaskUseCase tasks, PlanUseCase plans,
                                                           Runnable closeLifecycle) {
        RpcServiceBindings defaults = RpcTestBindings.create(null, null, null, null, null, null, null);
        DeadlineCloseable lifecycle = new DeadlineCloseable() {
            /** 所有生产关闭路径传递的期限都落到同一测试计数器。 */
            @Override
            public void closeAt(long shutdownDeadlineNanos) {
                closeLifecycle.run();
            }

            /** 保持生命周期 owner 的 AutoCloseable 契约明确。 */
            @Override
            public void close() {
                closeLifecycle.run();
            }
        };
        return new RpcServiceBindings(defaults.workspaces(), defaults.workspacePathSearch(), defaults.threads(),
                defaults.threadMcp(), defaults.turns(), defaults.compactions(), defaults.approvals(),
                defaults.catalog(), defaults.attachments(), defaults.attachmentPreviews(), tasks, plans,
                defaults.interactions(),
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedProcesses(),
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedReviews(), lifecycle);
    }

    /** 提供 JA-RPC v2 Sidecar 边界要求的四个明确根目录，避免测试依赖用户目录。 */
    private static SidecarConfiguration testConfiguration() {
        java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "ja-rpc-server-test")
                .toAbsolutePath().normalize();
        return new SidecarConfiguration(root.resolve("home"), root.resolve("data"),
                root.resolve("run"), root.resolve("logs"));
    }

    /** 通过生产构造器观察配置中的 host generation，确保默认入口不再硬编码事件代际。 */
    @Test
    void productionConstructorPublishesConfiguredRuntimeGeneration() {
        SidecarConfiguration configuration = new SidecarConfiguration(
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "ja-rpc-server-test", "home"),
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "ja-rpc-server-test", "data"),
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "ja-rpc-server-test", "run"),
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "ja-rpc-server-test", "logs"),
                7L);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RpcServer server = new RpcServer(new ByteArrayInputStream(new byte[0]), output, configuration,
                ignored -> new EmptyServices(new AtomicBoolean()).bindings(),
                TestConfigurationPorts.unavailable());
        try {
            java.lang.reflect.Field session = RpcServer.class.getDeclaredField("session");
            session.setAccessible(true);
            RpcSession value = (RpcSession) session.get(server);
            assertEquals(7L, value.runtimeGeneration());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("runtime generation must be inspectable in the server session", failure);
        } finally {
            server.close(ShutdownDeadline.start());
        }
    }

    /** SessionBinding 构造失败或缺少释放句柄时，Server 不得泄漏已启动的 Writer owner。 */
    @Test
    void bindingConstructionFailureStopsWriterOwner() {
        RuntimeException bindingFailure = new IllegalStateException("synthetic binding failure");
        assertSame(bindingFailure, assertThrows(RuntimeException.class,
                () -> new RpcServer(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(),
                        testConfiguration(), CLOCK,
                        ignored -> new EmptyServices(new AtomicBoolean()).bindings(),
                        TestConfigurationPorts.unavailable(), session -> {
                            throw bindingFailure;
                        })));
        assertFalse(hasLiveWriterOwner());

        assertThrows(IllegalArgumentException.class,
                () -> new RpcServer(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(),
                        testConfiguration(), CLOCK,
                        ignored -> new EmptyServices(new AtomicBoolean()).bindings(),
                        TestConfigurationPorts.unavailable(), session -> null));
        assertFalse(hasLiveWriterOwner());
    }

    /** 仅检查本类独占创建的固定名称平台线程，确保失败构造已完成同步清理。 */
    private static boolean hasLiveWriterOwner() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && "ja-rpc-writer".equals(thread.getName()));
    }

    /** 验证 initialize、ready 与 EOF 关闭只写出公开帧，任何凭据均不会回显。 */
    @Test
    void handshakePublishesIdentityAndRedactsConfig() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode initialize = initialize(mapper);
        String input = mapper.writeValueAsString(initialize) + """
            
            {"jsonrpc":"2.0","method":"runtime/initialized","params":\
            {"readyToken":"0123456789abcdef0123456789abcdef"}}
            """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicBoolean closed = new AtomicBoolean();
        RpcServer server = new RpcServer(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                output, testConfiguration(),
                CLOCK, ignored -> new EmptyServices(closed).bindings(),
                TestConfigurationPorts.unavailable());
        java.lang.reflect.Field requests = RpcServer.class.getDeclaredField("requests");
        requests.setAccessible(true);
        BoundedVirtualExecutor executor = (BoundedVirtualExecutor) requests.get(server);
        assertTrue(executor.submit(() -> Thread.currentThread().isVirtual()).get(1, TimeUnit.SECONDS));
        assertEquals(0, server.run());
        String wire = output.toString(StandardCharsets.UTF_8);
        assertTrue(wire.contains("\"protocolMajor\":2"));
        assertTrue(wire.contains("\"engine\":\"ja-kernel\""));
        assertTrue(wire.contains("\"engineVersion\":\"" + ProductVersion.current() + "\""));
        assertTrue(wire.contains("\"status\":\"ready\""));
        assertTrue(wire.contains("\"status\":\"stopped\""));
        assertTrue(wire.contains("\"features\":[\"task_threads\",\"plan\",\"interaction\"]"));
        assertFalse(wire.contains("provider-secret"));
        assertTrue(closed.get());
    }

    /** 后台通知 flush 失败必须关闭闲置 ingress、退出读取循环，并恰好释放一次连接 Owner。 */
    @Test
    void asynchronousProjectionFailureClosesIdleIngressAndRuntimeOnce() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode initialized = mapper.createObjectNode().put("jsonrpc", "2.0")
                .put("method", "runtime/initialized");
        initialized.set("params", mapper.createObjectNode()
                .put("readyToken", "0123456789abcdef0123456789abcdef"));
        CloseAwareBlockingInputStream input = new CloseAwareBlockingInputStream(
                (mapper.writeValueAsString(initialize(mapper)) + "\n"
                        + mapper.writeValueAsString(initialized) + "\n").getBytes(StandardCharsets.UTF_8));
        FailingOutputStream output = new FailingOutputStream();
        BlockingServices services = new BlockingServices(false);
        AtomicReference<RpcSession> connectedSession = new AtomicReference<>();
        AtomicInteger bindingCloseCount = new AtomicInteger();
        RpcServer server = new RpcServer(input, output, testConfiguration(), CLOCK,
                ignored -> services.bindings(), TestConfigurationPorts.unavailable(), session -> {
                    connectedSession.set(session);
                    return bindingCloseCount::incrementAndGet;
                });
        AtomicInteger exit = new AtomicInteger(-1);
        Thread reader = Thread.ofVirtual().start(() -> exit.set(server.run()));
        try {
            assertTrue(output.readyFrameFlushed.await(2, TimeUnit.SECONDS), "runtime did not become ready");
            assertTrue(input.readBlocked.await(2, TimeUnit.SECONDS), "ingress did not become idle");
            output.failWrites.set(true);
            connectedSession.get().publishObservedConfigurationChange("user", null, "cfg_changed");

            reader.join(2_000);
            assertFalse(reader.isAlive(), "projection failure must release the blocked ingress read");
            assertEquals(1, exit.get(), "failed output projection must produce a non-zero run result");
            assertEquals(1, input.closeCount.get(), "failed ingress is closed once");
            assertEquals(1, services.closeCount.get(), "runtime Owner is closed once");
            assertEquals(1, bindingCloseCount.get(), "connection binding is released once");
        } finally {
            input.release();
            reader.join(2_000);
        }
    }

    /**
     * 外部文件变更仅在握手完成后进入当前连接，并在关闭前注销绑定，避免后台 Watcher 写入旧 stdout。
     */
    @Test
    void externalConfigurationBindingPublishesAfterReadyAndClosesBeforeWriter() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<RpcSession> capturedSession = new AtomicReference<>();
        AtomicBoolean bindingClosed = new AtomicBoolean();
        RpcServer server = new RpcServer(new ByteArrayInputStream(new byte[0]), output,
                testConfiguration(), CLOCK, ignored -> new EmptyServices(new AtomicBoolean()).bindings(),
                TestConfigurationPorts.unavailable(), session -> {
                    capturedSession.set(session);
                    return () -> bindingClosed.set(true);
                });
        try {
            RpcSession session = capturedSession.get();
            assertTrue(session != null, "session binding must receive the constructed connection");
            session.initialize();
            session.ready("0123456789abcdef0123456789abcdef");
            session.publishObservedConfigurationChange("user", null, "cfg_external");
        } finally {
            server.close(ShutdownDeadline.start());
        }
        String wire = output.toString(StandardCharsets.UTF_8);
        assertTrue(wire.contains("\"method\":\"configuration/changed\""));
        assertTrue(wire.contains("\"scope\":\"user\""));
        assertTrue(wire.contains("\"version\":\"cfg_external\""));
        assertFalse(wire.contains("\"path\""));
        assertTrue(bindingClosed.get());
    }

    /**
     * Plan 创建事件先于连接观察时不得广播；读取权威 snapshot 后只向观察该 Plan 的连接推送变化，
     * 关闭观察后继续静默，避免隐藏 Workbench 消费无界状态流。
     */
    @Test
    void publishesPlanChangesOnlyWhileObserved() {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<PlanEventSink> subscribed = new AtomicReference<>();
        PlanModels.PlanSnapshot snapshot = planDiscoverySnapshot();
        PlanUseCase planOwner = (PlanUseCase) Proxy.newProxyInstance(
                RpcServerTest.class.getClassLoader(), new Class<?>[]{PlanUseCase.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "subscribePlan" -> {
                        subscribed.set((PlanEventSink) arguments[0]);
                        yield (AutoCloseable) () -> subscribed.set(null);
                    }
                    case "readPlan" -> snapshot;
                    default -> throw new UnsupportedOperationException("unexpected Plan call: " + method.getName());
                });
        RpcServiceBindings base = RpcTestBindings.create(null, null, null, null, null, () -> { });
        RpcServiceBindings bindings = new RpcServiceBindings(base.workspaces(), base.workspacePathSearch(),
                base.threads(), base.threadMcp(), base.turns(), base.compactions(), base.approvals(), base.catalog(),
                base.attachments(), base.attachmentPreviews(), base.tasks(), planOwner,
                base.interactions(),
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedProcesses(),
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedReviews(), base.lifecycle());

        try (StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024);
             RpcSession session = new RpcSession(testConfiguration(), mapper, CLOCK, writer,
                     ignored -> bindings, TestConfigurationPorts.unavailable(), 73)) {
            session.initialize();
            session.ready("0123456789abcdef0123456789abcdef");
            PlanEventSink sink = subscribed.get();
            sink.publish(planEvent(snapshot.plan(), 1, "created")).toCompletableFuture().join();
            assertFalse(output.toString(StandardCharsets.UTF_8).contains("plan/changed"));

            ObjectNode observation = session.observePlan("thr_discovery", "plan_discovery");
            assertEquals("plan_discovery", observation.path("plan").path("planId").asText());
            sink.publish(planEvent(snapshot.plan(), 2, "plan_draft_saved")).toCompletableFuture().join();
            String whileObserved = output.toString(StandardCharsets.UTF_8);
            assertTrue(whileObserved.contains("\"method\":\"plan/changed\""));
            assertTrue(whileObserved.contains("\"planId\":\"plan_discovery\""));

            session.unobservePlan(observation.path("observationId").asText());
            sink.publish(planEvent(snapshot.plan(), 3, "plan_step_updated")).toCompletableFuture().join();
            assertEquals(whileObserved, output.toString(StandardCharsets.UTF_8));
        }
    }

    /**
     * 连接关闭必须保留最早的取消失败，并按实际清理顺序挂载 observation、订阅和生命周期失败；
     * 后续资源仍全部尝试关闭，避免一个诊断异常截断真实资源回收。
     */
    @Test
    void closePreservesFirstFailureAndSuppressesLaterCleanupFailures() {
        ObjectMapper mapper = new ObjectMapper();
        List<String> order = new java.util.ArrayList<>();
        IllegalStateException cancellationFailure = new IllegalStateException("cancellation failed");
        IllegalStateException observationFailure = new IllegalStateException("observation failed");
        IllegalStateException subscriptionFailure = new IllegalStateException("subscription failed");
        IllegalStateException lifecycleFailure = new IllegalStateException("lifecycle failed");
        TaskUseCase taskOwner = (TaskUseCase) Proxy.newProxyInstance(RpcServerTest.class.getClassLoader(),
                new Class<?>[]{TaskUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "subscribe" -> (AutoCloseable) () -> {
                        order.add("subscription");
                        throw subscriptionFailure;
                    };
                    case "observe" -> new TaskUseCase.Observation("observe_close", "thr_task_close", 1);
                    case "unobserve" -> {
                        order.add("unobserve");
                        throw observationFailure;
                    }
                    default -> throw new AssertionError("unexpected TaskUseCase call: " + method.getName());
                });
        DeadlineCloseable lifecycle = new DeadlineCloseable() {
            /** 关闭组合根时记录顺序并制造最终失败。 */
            @Override public void closeAt(long shutdownDeadlineNanos) {
                order.add("lifecycle");
                throw lifecycleFailure;
            }

            /** 测试只允许生产路径传递绝对 Deadline。 */
            @Override public void close() {
                throw new AssertionError("deadline-aware close is required");
            }
        };
        RpcServiceBindings base = RpcTestBindings.create(null, null, null, null, null, () -> { });
        RpcServiceBindings bindings = new RpcServiceBindings(base.workspaces(), base.workspacePathSearch(),
                base.threads(), base.threadMcp(), base.turns(), base.compactions(), base.approvals(), base.catalog(),
                base.attachments(), base.attachmentPreviews(), taskOwner, base.plans(), base.interactions(),
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedProcesses(),
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedReviews(), lifecycle);
        try (StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), mapper, 4 * 1024 * 1024)) {
            RpcSession session = new RpcSession(testConfiguration(), mapper, CLOCK, writer,
                    ignored -> bindings, TestConfigurationPorts.unavailable(), 71);
            session.initialize();
            session.ready("0123456789abcdef0123456789abcdef");
            session.observeTask("thr_task_close", 1);
            session.cancellationToken().onCancellation(() -> {
                order.add("cancellation");
                throw cancellationFailure;
            });

            RuntimeException actual = assertThrows(RuntimeException.class,
                    () -> session.close(ShutdownDeadline.start(Duration.ofSeconds(2))));

            assertSame(cancellationFailure, actual);
            assertEquals(List.of("cancellation", "unobserve", "subscription", "lifecycle"), order);
            assertEquals(3, actual.getSuppressed().length);
            assertSame(observationFailure, actual.getSuppressed()[0]);
            assertSame(subscriptionFailure, actual.getSuppressed()[1].getCause());
            assertSame(lifecycleFailure, actual.getSuppressed()[2]);
        }
    }

    /** 验证任意入站响应信封都会在进入应用状态前被拒绝。 */
    @Test
    void rejectsInboundResponseEnvelopes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String id : List.of("c:client-response", "x:foreign-response")) {
            String frame = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", id)
                    .set("result", mapper.createObjectNode().put("accepted", true))
                    .toString();
            AtomicBoolean opened = new AtomicBoolean();
            RpcServer server = new RpcServer(new ByteArrayInputStream(
                    (frame + "\n").getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream(),
                    testConfiguration(), CLOCK, ignored -> {
                        opened.set(true);
                        return new EmptyServices(new AtomicBoolean()).bindings();
                    }, TestConfigurationPorts.unavailable());
            assertEquals(1, server.run(), "response must fail closed: " + id);
            assertFalse(opened.get(), "response must not open application services: " + id);
        }
    }

    /**
     * 入站与关闭异常都继续失败关闭；日志只含阶段和 FailureDiagnostics 摘要，不回显环境、凭据或请求正文。
     *
     * @author kongweiguang
     */
    @Test
    void runLogsSafeFailureStageAndPreservesNonZeroExit() {
        String secret = "api_key=rpc-test-secret environment=rpc-test-env requestBody=rpc-test-body";
        Logger logger = (Logger) LoggerFactory.getLogger(RpcServer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ByteArrayOutputStream ingressOutput = new ByteArrayOutputStream();
        ByteArrayOutputStream closeOutput = new ByteArrayOutputStream();
        try {
            InputStream failingInput = new InputStream() {
                /** 在实际读取边界抛出带敏感哨兵的异常，验证 run 只输出安全摘要。
                 *
                 * @author kongweiguang
                 */
                @Override
                public int read() throws IOException {
                    throw new IOException(secret);
                }
            };
            RpcServer ingressServer = new RpcServer(failingInput, ingressOutput,
                    testConfiguration(), CLOCK,
                    ignored -> new EmptyServices(new AtomicBoolean()).bindings(),
                    TestConfigurationPorts.unavailable());
            assertEquals(1, ingressServer.run(), "ingress failure must retain the existing non-zero exit");

            IOException cleanupFailure = new IOException(secret);
            RpcServer closeServer = new RpcServer(new ByteArrayInputStream(new byte[0]), closeOutput,
                    testConfiguration(), CLOCK,
                    ignored -> new EmptyServices(new AtomicBoolean()).bindings(),
                    TestConfigurationPorts.unavailable(), ignored -> () -> { throw cleanupFailure; });
            assertEquals(1, closeServer.run(), "close failure must retain the existing non-zero exit");

            List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertTrue(messages.stream().anyMatch(message -> message.contains("stage=ingress")
                    && message.contains("type=java.io.IOException")
                    && message.contains("causeType=java.io.IOException")));
            assertTrue(messages.stream().anyMatch(message -> message.contains("stage=close")
                    && message.contains("type=java.lang.IllegalStateException")
                    && message.contains("causeType=java.io.IOException")));
            String rendered = String.join("\n", messages);
            assertFalse(rendered.contains("rpc-test-secret"));
            assertFalse(rendered.contains("rpc-test-env"));
            assertFalse(rendered.contains("rpc-test-body"));
            String ingressWire = ingressOutput.toString(StandardCharsets.UTF_8);
            String closeWire = closeOutput.toString(StandardCharsets.UTF_8);
            List<JaRpcCodec.Frame> ingressFrames = decodeJaRpcStdout(ingressWire);
            List<JaRpcCodec.Frame> closeFrames = decodeJaRpcStdout(closeWire);
            assertTrue(ingressFrames.stream().allMatch(JaRpcCodec.Notification.class::isInstance));
            assertTrue(closeFrames.stream().allMatch(JaRpcCodec.Notification.class::isInstance));
            for (String wire : List.of(ingressWire, closeWire)) {
                assertFalse(wire.contains("Ja RPC server failed"));
                assertFalse(wire.contains("stage="));
                assertFalse(wire.contains("java.io.IOException"));
                assertFalse(wire.contains("\"message\""));
                assertFalse(wire.contains("api_key=rpc-test-secret"));
                assertFalse(wire.contains("environment=rpc-test-env"));
                assertFalse(wire.contains("requestBody=rpc-test-body"));
            }
            assertTrue(appender.list.stream().allMatch(event -> event.getThrowableProxy() == null),
                    "raw exception chains must not be attached to the log event");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * 验证忽略中断的 Handler 会先触发进程边界强制终止，而不会继续关闭 Session 或 Runtime。
     * 输入闸门保持读取线程存活，使测试覆盖生产中的同一关闭所有者，避免 EOF 触发第二次关闭造成竞态。
     */
    @Test
    void unquiescedHandlerForcesTerminationWithoutRuntimeCloseOrStopped() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode initialize = initialize(mapper);
        String input = mapper.writeValueAsString(initialize) + """
            
            {"jsonrpc":"2.0","method":"runtime/initialized","params":\
            {"readyToken":"0123456789abcdef0123456789abcdef"}}
            """;
        ObjectNode list = mapper.createObjectNode().put("jsonrpc", "2.0")
                .put("id", "c:blocking-list").put("method", "workspace/list");
        list.set("params", mapper.createObjectNode());
        BlockingInputStream inputStream = new BlockingInputStream(
                (input + mapper.writeValueAsString(list) + "\n").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BlockingServices services = new BlockingServices();
        RpcServer server = new RpcServer(inputStream, output,
                testConfiguration(), CLOCK,
                ignored -> services.bindings(),
                TestConfigurationPorts.unavailable());
        Thread reader = Thread.ofVirtual().start(server::run);
        try {
            assertTrue(services.handlerEntered.await(2, TimeUnit.SECONDS), "handler did not start");
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> server.close(ShutdownDeadline.start(Duration.ofMillis(150))));
            assertTrue(failure instanceof ShutdownDeadline.ForcedTerminationException);
            assertEquals(0, services.closeCount.get(), "forced ingress must retain runtime ownership");
            assertFalse(output.toString(StandardCharsets.UTF_8).contains("\"status\":\"stopped\""));
        } finally {
            services.release();
            inputStream.release();
            reader.join(2_000);
        }
        assertFalse(reader.isAlive(), "reader must return after forced handoff");
    }

    /** 验证重复关闭会复用首次强制终止结果，而不会重新启动一段关闭预算。 */
    @Test
    void repeatedCloseReplaysForcedResultWithinOneCompletion() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode initialize = initialize(mapper);
        ObjectNode list = mapper.createObjectNode().put("jsonrpc", "2.0")
                .put("id", "c:repeat-close").put("method", "workspace/list");
        list.set("params", mapper.createObjectNode());
        BlockingInputStream inputStream = new BlockingInputStream(
                (mapper.writeValueAsString(initialize) + """
                    
                    {"jsonrpc":"2.0","method":"runtime/initialized","params":\
                    {"readyToken":"0123456789abcdef0123456789abcdef"}}
                    """
                        + mapper.writeValueAsString(list) + "\n").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BlockingServices services = new BlockingServices();
        RpcServer server = new RpcServer(inputStream, output,
                testConfiguration(), CLOCK,
                ignored -> services.bindings(),
                TestConfigurationPorts.unavailable());
        Thread reader = Thread.ofVirtual().start(server::run);
        try {
            assertTrue(services.handlerEntered.await(2, TimeUnit.SECONDS), "handler did not start");
            RuntimeException first = assertThrows(RuntimeException.class,
                    () -> server.close(ShutdownDeadline.start(Duration.ofMillis(150))));
            long started = System.nanoTime();
            RuntimeException repeated = assertThrows(RuntimeException.class,
                    () -> server.close(ShutdownDeadline.start(Duration.ofSeconds(2))));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(first instanceof ShutdownDeadline.ForcedTerminationException);
            assertSame(first, repeated);
            assertTrue(elapsedMillis < 500, "repeated close waited for a new shutdown budget");
            assertEquals(0, services.closeCount.get(), "forced close must retain runtime ownership");
        } finally {
            services.release();
            inputStream.release();
            reader.join(2_000);
        }
        assertFalse(reader.isAlive(), "reader must return after forced handoff");
    }

    /**
     * 验证关闭请求必须等待 Codec 已返回的帧越过分发闸门后才能停止执行器，
     * 防止该帧在正常关闭完成后继续访问已经释放的 Runtime 对象图。
     */
    @Test
    void readDispatchGatePreventsLateHandlerAndResourceUse() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode initialize = initialize(mapper);
        String input = mapper.writeValueAsString(initialize) + """
            
            {"jsonrpc":"2.0","method":"runtime/initialized","params":\
            {"readyToken":"0123456789abcdef0123456789abcdef"}}
            """;
        ObjectNode list = mapper.createObjectNode().put("jsonrpc", "2.0")
                .put("id", "c:gate-list").put("method", "workspace/list");
        list.set("params", mapper.createObjectNode());
        ByteArrayInputStream inputStream = new ByteArrayInputStream(
                (input + mapper.writeValueAsString(list) + "\n").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BlockingServices services = new BlockingServices(false);
        CountDownLatch frameRead = new CountDownLatch(1);
        CountDownLatch allowDispatch = new CountDownLatch(1);
        AtomicInteger frameCount = new AtomicInteger();
        RpcServer server = new RpcServer(inputStream, output,
                testConfiguration(), CLOCK,
                ignored -> services.bindings(),
                TestConfigurationPorts.unavailable(), () -> {
                    if (frameCount.incrementAndGet() == 3) {
                        frameRead.countDown();
                        awaitLatch(allowDispatch);
                    }
                });
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        CountDownLatch closeCall = new CountDownLatch(1);
        CountDownLatch closeDone = new CountDownLatch(1);
        Thread reader = Thread.ofVirtual().start(server::run);
        Thread closer = null;
        try {
            assertTrue(frameRead.await(1, TimeUnit.SECONDS),
                    () -> "frame did not reach read boundary count=" + frameCount.get()
                            + " output=" + output.toString(StandardCharsets.UTF_8));
            closer = Thread.ofVirtual().start(() -> {
                closeCall.countDown();
                try {
                    server.close(ShutdownDeadline.start(Duration.ofSeconds(2)));
                } catch (Throwable failure) {
                    closeFailure.set(failure);
                } finally {
                    closeDone.countDown();
                }
            });
            assertTrue(closeCall.await(1, TimeUnit.SECONDS));
            assertFalse(services.handlerEntered.await(100, TimeUnit.MILLISECONDS));
            assertEquals(0, services.closeCount.get());
            allowDispatch.countDown();
            assertTrue(services.handlerEntered.await(1, TimeUnit.SECONDS),
                    "frame was not dispatched after the gate opened");
            assertTrue(closeDone.await(2, TimeUnit.SECONDS));
            assertTrue(closeFailure.get() == null, () -> "unexpected close failure: " + closeFailure.get());
            assertEquals(1, services.closeCount.get());
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"status\":\"stopped\""));
        } finally {
            allowDispatch.countDown();
            reader.join(2_000);
            if (closer != null) {
                closer.join(2_000);
            }
        }
        assertFalse(reader.isAlive(), "reader must finish after the gated dispatch");
        if (closer != null) {
            assertFalse(closer.isAlive(), "close owner must finish after the gated dispatch");
        }
    }

    /** 验证限制参数不匹配时会在持有敏感信息的应用工厂打开前失败。 */
    @Test
    void rejectsLimitMismatchBeforeFactoryOpen() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode initialize = initialize(mapper);
        ((ObjectNode) ((ObjectNode) initialize.get("params")).get("limits"))
                .put("maxFrameBytes", 4 * 1024 * 1024 + 1);
        AtomicInteger opened = new AtomicInteger();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RpcServer server = new RpcServer(new ByteArrayInputStream(
                (mapper.writeValueAsString(initialize) + "\n").getBytes(StandardCharsets.UTF_8)), output,
                testConfiguration(), CLOCK,
                ignored -> {
                    opened.incrementAndGet();
                    return new EmptyServices(new AtomicBoolean()).bindings();
                }, TestConfigurationPorts.unavailable());
        assertEquals(0, server.run());
        assertEquals(0, opened.get());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"errorCode\":\"INVALID_PARAMS\""));
    }

    /** 不支持的 JA-RPC 版本不得触发应用对象图创建，也不做隐式协议升级。 */
    @Test
    void rejectsLegacyProtocolBeforeFactoryOpen() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode initialize = initialize(mapper);
        ObjectNode params = (ObjectNode) initialize.path("params");
        params.put("protocolMajor", 2).put("protocolMinor", 1);
        AtomicInteger opened = new AtomicInteger();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RpcServer server = new RpcServer(new ByteArrayInputStream(
                (mapper.writeValueAsString(initialize) + "\n").getBytes(StandardCharsets.UTF_8)), output,
                testConfiguration(), CLOCK,
                ignored -> {
                    opened.incrementAndGet();
                    return new EmptyServices(new AtomicBoolean()).bindings();
                }, TestConfigurationPorts.unavailable());

        assertEquals(0, server.run());
        assertEquals(0, opened.get());
        assertTrue(output.toString(StandardCharsets.UTF_8)
                .contains("\"errorCode\":\"PROTOCOL_VERSION_UNSUPPORTED\""));
    }

    /** 验证配置损坏只会降低健康状态，同时保留 initialize 与 Settings 边界的可用性。 */
    @Test
    void exposesRedactedConfigurationDegradedHealth() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode initialize = initialize(mapper);
        ObjectNode health = mapper.createObjectNode().put("jsonrpc", "2.0")
                .put("id", "c:health").put("method", "runtime/health");
        health.set("params", mapper.createObjectNode());
        String input = mapper.writeValueAsString(initialize) + """
            
            {"jsonrpc":"2.0","method":"runtime/initialized","params":{"readyToken":"0123456789abcdef0123456789abcdef"}}
            """
                + mapper.writeValueAsString(health) + "\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RpcServer server = new RpcServer(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output,
                testConfiguration(), CLOCK,
                ignored ->
                        new EmptyServices(new AtomicBoolean()).configurationBindings(),
                new DegradedConfiguration());
        assertEquals(0, server.run());
        String wire = output.toString(StandardCharsets.UTF_8);
        assertTrue(wire.contains("\"name\":\"configuration\",\"status\":\"degraded\""));
        assertTrue(wire.contains("CORRUPT_CONFIG"));
        assertFalse(wire.contains("C:/private/secret.toml"));
    }

    /** 验证真实 stdio 帧保持冻结的配置和凭据结果结构，并对 Secret 完成脱敏。 */
    @Test
    void servesStrictConfigurationAndCredentialFrames() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode initialize = initialize(mapper);
        String ready = "{\"jsonrpc\":\"2.0\",\"method\":\"runtime/initialized\",\"params\":{\"readyToken\":\"0123456789abcdef0123456789abcdef\"}}";
        ObjectNode read = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", "c:config-read")
                .put("method", "configuration/read");
        read.set("params", mapper.createObjectNode());
        ObjectNode write = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", "c:config-write")
                .put("method", "configuration/patch");
        write.set("params", mapper.createObjectNode().put("scope", "user")
                .put("expectedVersion", "cfg_missing")
                .set("patch", mapper.createObjectNode().put("unexpected", true)));
        ObjectNode credential = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", "c:credential")
                .put("method", "credential/set");
        credential.set("params", mapper.createObjectNode().put("credentialId", "cred_test")
                .put("secret", "wire-secret-must-not-echo").put("expectedVersion", "cfg_missing"));
        String input = mapper.writeValueAsString(initialize) + "\n" + ready + "\n"
                + mapper.writeValueAsString(read) + "\n" + mapper.writeValueAsString(write) + "\n"
                + mapper.writeValueAsString(credential) + "\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RpcServer server = new RpcServer(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output,
                testConfiguration(), CLOCK,
                ignored ->
                        new EmptyServices(new AtomicBoolean()).configurationBindings(),
                new WireConfiguration());
        assertEquals(0, server.run());
        String wire = output.toString(StandardCharsets.UTF_8);
        assertTrue(wire.contains("\"credentialId\":\"cred_test\""));
        assertTrue(wire.contains("\"method\":\"configuration/changed\""));
        assertFalse(wire.contains("wire-secret-must-not-echo"));

        List<ObjectNode> frames = wire.lines().map(line -> {
            try {
                return (ObjectNode) mapper.readTree(line);
            } catch (Exception failure) {
                throw new AssertionError("runtime emitted invalid JSON", failure);
            }
        }).toList();
        ObjectNode configResult = responseResult(frames, "c:config-read");
        assertExactFields(configResult, "workspaceId", "effective", "user", "project", "credentials",
                "cas", "diagnostics", "issues", "trusted", "sources");
        assertTrue(configResult.get("workspaceId").isNull());
        ObjectNode cas = (ObjectNode) configResult.path("cas");
        assertExactFields(cas, "userVersion", "projectVersion", "credentialVersion");
        assertEquals("cfg_missing", cas.path("userVersion").textValue());
        assertEquals("cfg_missing", cas.path("projectVersion").textValue());
        assertEquals("cfg_missing", cas.path("credentialVersion").textValue());
        ObjectNode credentialResult = responseResult(frames, "c:credential");
        assertExactFields(credentialResult, "accepted", "credentialId", "configured", "version");
        assertFalse(credentialResult.has("secret"));
        frames.stream().filter(frame -> "configuration/changed".equals(frame.path("method").textValue()))
                .forEach(frame -> assertFalse(frame.path("params").has("path")));
    }

    /** 验证存储 CAS 与 I/O 失败映射到稳定错误目录，且不泄露持久化实现细节。 */
    @Test
    void mapsPersistenceFailuresToStableCatalog() {
        assertEquals("CONFLICT", RpcServer.mapPersistenceFailure(StorageException.Code.CAS_CONFLICT).errorCode());
        assertEquals("STORAGE_UNAVAILABLE",
                RpcServer.mapPersistenceFailure(StorageException.Code.IO).errorCode());
    }

    /** 在途 ID 冲突必须终止违规输入，保留原请求唯一响应，避免客户端收到同 ID 双结果。 */
    @Test
    void duplicateInFlightIdDoesNotWriteASecondResponse() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode ready = mapper.createObjectNode().put("jsonrpc", "2.0")
                .put("method", "runtime/initialized");
        ready.set("params", mapper.createObjectNode()
                .put("readyToken", "0123456789abcdef0123456789abcdef"));
        ObjectNode list = mapper.createObjectNode().put("jsonrpc", "2.0")
                .put("id", "c:duplicate-list").put("method", "workspace/list");
        list.set("params", mapper.createObjectNode());
        String input = mapper.writeValueAsString(initialize(mapper)) + "\n"
                + mapper.writeValueAsString(ready) + "\n"
                + mapper.writeValueAsString(list) + "\n"
                + mapper.writeValueAsString(list) + "\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BlockingServices services = new BlockingServices();
        RpcServer server = new RpcServer(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                output, testConfiguration(), CLOCK, ignored -> services.bindings(),
                TestConfigurationPorts.unavailable());
        AtomicInteger exit = new AtomicInteger(-1);
        Thread reader = Thread.ofVirtual().start(() -> exit.set(server.run()));
        try {
            assertTrue(services.handlerEntered.await(2, TimeUnit.SECONDS), "original request did not start");
        } finally {
            services.release();
            reader.join(2_000);
        }
        assertFalse(reader.isAlive(), "protocol failure must finish after the admitted request");
        assertEquals(1, exit.get());
        List<ObjectNode> responses = output.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank()).map(line -> {
                    try {
                        return (ObjectNode) mapper.readTree(line);
                    } catch (IOException failure) {
                        throw new AssertionError("invalid server response", failure);
                    }
                }).filter(frame -> "c:duplicate-list".equals(frame.path("id").textValue())).toList();
        assertEquals(1, responses.size());
        assertTrue(responses.getFirst().has("result"));
    }

    /** Core CAS 可在宿主版本预检后发生；解包异步失败仍需返回冲突且不泄漏 Store 的原始诊断。 */
    @Test
    void mapsCoreStoreConflictWithoutLeakingDetails() throws Exception {
        java.lang.reflect.Method mapping = RpcServer.class.getDeclaredMethod("mapFailure", Throwable.class);
        mapping.setAccessible(true);
        io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.StoreConflictException conflict =
                new io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.StoreConflictException(
                "private SQL path or credential must not escape");
        io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException mapped =
                (io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException) mapping.invoke(null,
                new java.util.concurrent.CompletionException(conflict));
        assertEquals("CONFLICT", mapped.errorCode());
        assertFalse(mapped.getMessage().contains("private SQL"));
    }

    /** 准入错误经异步传播仍保留可操作分类，用户可换模型或修 Schema，而不是猜测通用参数错误。 */
    @Test
    void mapsOutputSchemaAdmissionFailuresToExplicitCatalog() throws Exception {
        java.lang.reflect.Method mapping = RpcServer.class.getDeclaredMethod("mapFailure", Throwable.class);
        mapping.setAccessible(true);
        for (OutputSchemaException failure : List.of(OutputSchemaException.invalid(),
                OutputSchemaException.unsupported())) {
            io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException mapped =
                    (io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException) mapping.invoke(null,
                    new java.util.concurrent.CompletionException(failure));
            assertEquals(failure.code(), mapped.errorCode());
            assertEquals(failure.getMessage(), mapped.getMessage());
        }
    }

    /** 没有本产品代码位置时明确标记未知，不能回退到会泄露消息或路径的完整 Throwable 文本。 */
    @Test
    void unexpectedFailureWithoutJaFrameRemainsUnknown() throws Exception {
        RuntimeException failure = new RuntimeException("C:/private/api_key=private-secret");
        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("java.util.concurrent.CompletableFuture", "join", "auth.json", 42)
        });
        assertEquals("unknown", io.github.kongweiguang.ja.foundation.error.FailureDiagnostics
                .summarize(failure).origin());
        java.lang.reflect.Method mapping = RpcServer.class.getDeclaredMethod("mapFailure", Throwable.class);
        mapping.setAccessible(true);
        io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException mapped =
                (io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException) mapping.invoke(null, failure);
        assertEquals("INTERNAL_ERROR", mapped.errorCode());
        assertEquals("runtime request failed", mapped.getMessage());
        assertFalse(mapped.getMessage().contains("private"));
    }

    /** 异步包装的间接 cause 环必须及时形成脱敏响应，不能无限解包而使请求永久挂起。 */
    @Test
    void mapsAnIndirectAsyncCauseCycleWithoutBlocking() throws Exception {
        AtomicReference<Throwable> firstCause = new AtomicReference<>();
        AtomicReference<Throwable> secondCause = new AtomicReference<>();
        AtomicInteger causeReads = new AtomicInteger();
        Throwable first = new java.util.concurrent.CompletionException("private payload") {
            /** 有界读数让旧的无限解包快速失败，而不留下永久占用测试线程的死循环。 */
            @Override
            public synchronized Throwable getCause() {
                assertTrue(causeReads.incrementAndGet() <= 12, "cause traversal did not stop");
                return firstCause.get();
            }
        };
        Throwable second = new java.util.concurrent.ExecutionException("private payload") {
            /** 两种真实异步包装组成间接环，覆盖传输解包而不伪造公共错误类别。 */
            @Override
            public synchronized Throwable getCause() {
                assertTrue(causeReads.incrementAndGet() <= 12, "cause traversal did not stop");
                return secondCause.get();
            }
        };
        firstCause.set(second);
        secondCause.set(first);
        java.lang.reflect.Method mapping = RpcServer.class.getDeclaredMethod("mapFailure", Throwable.class);
        mapping.setAccessible(true);
        io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException mapped =
                (io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException) mapping.invoke(null, first);
        assertEquals("INTERNAL_ERROR", mapped.errorCode());
        assertEquals("runtime request failed", mapped.getMessage());
        assertFalse(mapped.getMessage().contains("private"));
    }

    /** 使用生产能力和限制构造当前 JA-RPC 2.0 initialize 请求，避免测试复制其余合同常量。 */
    private static ObjectNode initialize(ObjectMapper mapper) {
        ObjectNode params = mapper.createObjectNode().put("protocolMajor", 2).put("protocolMinor", 0)
                .put("clientVersion", "0.2.0");
        params.set("capabilities", HandshakeContractTestAccess.capabilities(mapper));
        params.set("limits", HandshakeContractTestAccess.limits(mapper));
        ObjectNode request = mapper.createObjectNode().put("jsonrpc", "2.0")
                .put("id", "c:init").put("method", "runtime/initialize");
        request.set("params", params);
        return request;
    }

    /**
     * 用生产严格 Codec 校验关闭仍可产生的每个 stdout 通知帧；无通知时允许零帧输出，避免依赖异步关闭投影的时序。
     *
     * @param wire 服务端 stdout JSONL
     * @return 已通过严格信封解码的 v2 帧
     * @author kongweiguang
     */
    private static List<JaRpcCodec.Frame> decodeJaRpcStdout(String wire) {
        if (wire.isEmpty()) {
            return List.of();
        }
        assertTrue(wire.endsWith("\n"), "each stdout frame must retain its JSONL terminator");
        String[] lines = wire.split("\\R", -1);
        assertEquals("", lines[lines.length - 1], "stdout must end at a frame boundary");
        JaRpcCodec codec = new JaRpcCodec();
        List<JaRpcCodec.Frame> frames = new ArrayList<>();
        for (int index = 0; index < lines.length - 1; index++) {
            assertFalse(lines[index].isBlank(), "stdout must not contain blank protocol frames");
            frames.add(codec.decode(lines[index].getBytes(StandardCharsets.UTF_8)));
        }
        return List.copyOf(frames);
    }

    /** 创建最小独立 Plan snapshot，确保 transport fixture 不依赖聚合服务或持久层。 */
    private static PlanModels.PlanSnapshot planDiscoverySnapshot() {
        Instant now = CLOCK.instant();
        PlanModels.Plan plan = new PlanModels.Plan("plan_discovery", "thr_discovery",
                "验证 Plan 观察", PlanModels.PlanStatus.DRAFT, 1, null, null, now, now);
        return new PlanModels.PlanSnapshot(plan, null, null, null, List.of(), 1);
    }

    /** 只发布已提交的 Plan 事件投影，验证 observation 控制连接级可见性。 */
    private static PlanEvent planEvent(PlanModels.Plan plan, long sequence, String kind) {
        PlanModels.PublicEvent activity = new PlanModels.PublicEvent(
                sequence, plan.revision(), kind, "Plan 已更新", CLOCK.instant());
        PlanModels.PlanProgress progress = new PlanModels.PlanProgress(null, null, 0, 0);
        return new PlanEvent(plan, sequence, activity, progress);
    }

    /** 按 ID 读取唯一响应结果，避免断言把输入请求误认为标准输出帧。 */
    private static ObjectNode responseResult(List<ObjectNode> frames, String id) {
        ObjectNode response = frames.stream()
                .filter(frame -> id.equals(frame.path("id").textValue()))
                .findFirst().orElseThrow(() -> new AssertionError("response is missing: " + id));
        assertTrue(response.has("result"));
        return (ObjectNode) response.get("result");
    }

    /** 校验封闭的结果字段集合，同时允许 JSON 对象字段顺序变化。 */
    private static void assertExactFields(ObjectNode value, String... fields) {
        Set<String> expected = Set.of(fields);
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        assertEquals(expected, actual);
    }

    /** 保持测试专用分发屏障确定，并阻止中断绕过该同步点。 */
    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test dispatch barrier interrupted", interrupted);
        }
    }

    /**
     * 阻塞一个真实 RPC Handler，仅暴露证明强制入站分支不会进入 Session 或 Runtime 清理
     * 所需的关闭计数器，避免测试夹具扩散无关能力。
     */
    private static final class BlockingServices {
        private final CountDownLatch handlerEntered = new CountDownLatch(1);
        private final CountDownLatch releaseHandler = new CountDownLatch(1);
        private final AtomicInteger closeCount = new AtomicInteger();
        private final boolean holdHandler;
        private final WorkspaceUseCase history = new WorkspaceUseCase() {
            /** 阻塞夹具不开放工作区注册能力。 */
            @Override
            public Workspace openWorkspace(OpenWorkspace request) {
                throw new UnsupportedOperationException();
            }

            /** 阻塞夹具不创建会话目录。 */
            @Override
            public Workspace createSessionWorkspace(String threadId) {
                throw new UnsupportedOperationException();
            }

            /** 阻塞夹具不执行创建补偿。 */
            @Override
            public void discardUnlinkedSessionWorkspace(String workspaceId, long expectedRevision) {
                throw new UnsupportedOperationException();
            }

            /** 阻塞夹具不重开登记目录。 */
            @Override
            public Workspace openRegisteredWorkspace(String workspaceId) {
                throw new UnsupportedOperationException();
            }

            /** 列表调用作为唯一阻塞入口，用于观测 Handler 已经进入执行阶段。 */
            @Override
            public CursorPage<Workspace> listWorkspaces(String cursor, int limit, Workspace.Kind kind) {
                handlerEntered.countDown();
                if (holdHandler) {
                    awaitReleaseIgnoringInterrupt();
                }
                return new CursorPage<>(List.of(), null);
            }

            /** 阻塞夹具不提供可读取的工作区投影。 */
            @Override
            public Optional<Workspace> readWorkspace(String workspaceId) {
                throw new UnsupportedOperationException();
            }

            /** 关闭竞态夹具不对 Workspace 根或名称产生持久化副作用。 */
            @Override
            public Workspace updateWorkspace(UpdateWorkspace command) {
                throw new UnsupportedOperationException();
            }

            /** 此夹具只覆盖 Workspace Handler 关闭，不参与 Turn/Plan 准入。 */
            @Override
            public io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceAdmissionPort.Lease
                    acquireAdmission(String workspaceId) {
                return io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceAdmissionPort.noOpLease();
            }

            /** 阻塞夹具不提供已经打开的目录能力。 */
            @Override
            public Workspace requireOpenWorkspace(String workspaceId) {
                throw new UnsupportedOperationException();
            }

            /** 阻塞夹具不允许修改工作区信任状态。 */
            @Override
            public Workspace setWorkspaceTrust(String workspaceId, Workspace.Trust trust) {
                throw new UnsupportedOperationException();
            }

            /** 阻塞夹具不允许注销工作区，防止测试产生持久化副作用。 */
            @Override
            public void unregisterWorkspace(String workspaceId, long expectedRevision) {
                throw new UnsupportedOperationException();
            }

            /** 阻塞夹具不执行配置预热。 */
            @Override
            public void refreshPreparedWorkspaces() {
                throw new UnsupportedOperationException();
            }


        };

        /** 创建默认忽略中断的阻塞夹具，以覆盖强制关闭分支。 */
        private BlockingServices() {
            this(true);
        }

        /** 控制 Workspace Handler 在准入后是否保持在途，使正常关闭与强制关闭可分别验证。 */
        private BlockingServices(boolean holdHandler) {
            this.holdHandler = holdHandler;
        }

        /** 在 shutdownNow 后仍保持测试 Handler 在途，确保生产屏障接受真实并发压力。 */
        private void awaitReleaseIgnoringInterrupt() {
            boolean released = false;
            while (!released) {
                try {
                    released = releaseHandler.await(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // 该夹具刻意模拟无法遵守中断协议的资源所有者。
                }
            }
        }

        /** 仅在强制关闭断言完成后释放阻塞 Handler，避免测试提前解除关键竞态。 */
        private void release() {
            releaseHandler.countDown();
        }

        /** 组合阻塞 Workspace 端口与关闭计数器，其他能力保持显式拒绝。 */
        private RpcServiceBindings bindings() {
            return RpcTestBindings.create(history, null, null, null, null,
                    closeCount::incrementAndGet);
        }
    }

    /**
     * 在完整请求后保持读取线程，使测试能在阻塞 Handler 仍处于准入状态时调用同一关闭所有者；
     * 该输入流只服务测试同步，不改变生产 stdin 唤醒实现。
     */
    private static final class BlockingInputStream extends InputStream {
        private final byte[] bytes;
        private final CountDownLatch release = new CountDownLatch(1);
        private int offset;

        /** 仅保留私有且不可变的请求前缀，不额外缓存第二条协议通道。 */
        private BlockingInputStream(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        /** 请求字节耗尽后等待显式释放，以稳定复现关闭与读取线程并发。 */
        @Override
        public int read() throws IOException {
            if (offset < bytes.length) {
                return bytes[offset++] & 0xff;
            }
            try {
                release.await();
                return -1;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("test input interrupted", interrupted);
            }
        }

        /** 在强制关闭分支返回后解除读取线程阻塞。 */
        private void release() {
            release.countDown();
        }
    }

    /** 只在写入 runtime/status-changed ready 后确认就绪，之后模拟等待下一条客户端帧。 */
    private static final class FailingOutputStream extends OutputStream {
        private final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private final CountDownLatch readyFrameFlushed = new CountDownLatch(1);
        private final AtomicBoolean failWrites = new AtomicBoolean();

        /** 向测试缓冲区追加单字节，并在断开后模拟 stdout 写失败。 */
        @Override
        public synchronized void write(int value) throws IOException {
            if (failWrites.get()) {
                throw new IOException("synthetic stdout failure");
            }
            written.write(value);
        }

        /** 向测试缓冲区追加完整帧片段，并在断开后模拟 stdout 写失败。 */
        @Override
        public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
            if (failWrites.get()) {
                throw new IOException("synthetic stdout failure");
            }
            written.write(bytes, offset, length);
        }

        /** 只有 ready 帧成功 flush 后才释放测试，确保故障来自后续异步通知。 */
        @Override
        public synchronized void flush() throws IOException {
            if (failWrites.get()) {
                throw new IOException("synthetic stdout failure");
            }
            if (written.toString(StandardCharsets.UTF_8).contains("\"status\":\"ready\"")) {
                readyFrameFlushed.countDown();
            }
        }
    }

    /** 阻塞唯一 ready 帧的 flush，以真实 StdioWriter 验证 Session 关闭等待边界。 */
    private static final class BlockingReadyOutputStream extends OutputStream {
        private final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private final CountDownLatch readyFlushEntered = new CountDownLatch(1);
        private final CountDownLatch releaseReadyFlush = new CountDownLatch(1);
        private final AtomicBoolean blockReadyFlush = new AtomicBoolean(true);

        /** 将 Writer 提交的片段保留到本地协议缓冲区。 */
        @Override
        public synchronized void write(int value) {
            written.write(value);
        }

        /** 将完整协议帧追加到本地缓冲区，保持测试只观察真实 Writer 输出。 */
        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            written.write(bytes, offset, length);
        }

        /** 仅阻塞第一次 ready flush；初始化与后续 stopped 通知都正常完成。 */
        @Override
        public void flush() throws IOException {
            String wire;
            synchronized (this) {
                wire = written.toString(StandardCharsets.UTF_8);
            }
            if (wire.contains("\"status\":\"ready\"") && blockReadyFlush.compareAndSet(true, false)) {
                readyFlushEntered.countDown();
                try {
                    if (!releaseReadyFlush.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("ready flush release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("ready flush interrupted", interrupted);
                }
            }
        }

        /** 解除受控 flush，确保 deadline 回归失败时也能退出所有测试线程。 */
        private void releaseReadyFlush() {
            releaseReadyFlush.countDown();
        }
    }

    /** close 代表此 Server 独占的读取端失效，并确定性唤醒唯一 ingress reader。 */
    private static final class CloseAwareBlockingInputStream extends InputStream {
        private final byte[] bytes;
        private final CountDownLatch readBlocked = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicInteger closeCount = new AtomicInteger();
        private int offset;

        /** 复制唯一握手前缀，避免测试输入在构造后被外部修改。 */
        private CloseAwareBlockingInputStream(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        /** 输入耗尽后等待关闭信号，复现真实 transport 的 idle read。 */
        @Override
        public int read() throws IOException {
            if (offset < bytes.length) {
                return bytes[offset++] & 0xff;
            }
            readBlocked.countDown();
            try {
                released.await();
                return -1;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("test input interrupted", interrupted);
            }
        }

        /** 仅模拟拥有该输入端的 RpcServer 关闭连接。 */
        @Override
        public void close() {
            closeCount.incrementAndGet();
            released.countDown();
        }

        /** 测试清理兜底，确保断言失败时 reader 不会残留。 */
        private void release() {
            released.countDown();
        }
    }

    /** 用最小应用对象图验证传输生命周期，不伪造任何业务响应。 */
    private static final class EmptyServices {
        private final AtomicBoolean closed;

        /** 仅保留 EOF 生命周期验收所需的关闭探针。 */
        private EmptyServices(AtomicBoolean closed) { this.closed = closed; }

        /** 只组合关闭探针，所有业务端口保持显式拒绝。 */
        private RpcServiceBindings bindings() {
            return RpcTestBindings.create(null, null, null, null, null,
                    () -> closed.set(true));
        }

        /**
         * 配置写入测试只开放提交后的工作区预热能力；其它工作区操作仍显式失败，避免空夹具
         * 掩盖 Handler 的意外跨域调用。
         */
        private RpcServiceBindings configurationBindings() {
            WorkspaceUseCase workspaces = (WorkspaceUseCase) Proxy.newProxyInstance(
                    RpcServerTest.class.getClassLoader(), new Class<?>[]{WorkspaceUseCase.class},
                    (proxy, method, arguments) -> {
                        if ("refreshPreparedWorkspaces".equals(method.getName())) {
                            return null;
                        }
                        throw new AssertionError("unexpected workspace call: " + method.getName());
                    });
            return RpcTestBindings.create(workspaces, null, null, null, null,
                    () -> closed.set(true));
        }

    }

    /** 以脱敏健康状态模拟配置文件损坏，同时保持 initialize 可用。 */
    private static final class DegradedConfiguration extends TestConfigurationPorts {
        /** 返回固定的脱敏降级诊断，验证健康帧不会泄露配置路径。 */
        @Override public ConfigurationUseCase.HealthResult health() {
            return new ConfigurationUseCase.HealthResult(
                    ConfigurationUseCase.HealthStatus.DEGRADED, List.of("CORRUPT_CONFIG"));
        }
    }

    /** 最小配置用例夹具只返回纯 JDK 结果，由 Handler 负责构造冻结 Wire。 */
    private static final class WireConfiguration extends TestConfigurationPorts {
        /** 返回固定补丁回执，使 Wire 断言不依赖文件系统写入。 */
        @Override public ConfigurationUseCase.MutationResult patch(
                ConfigurationScope scope, Path root, ConfigurationUseCase.Document patch, String version) {
            return new ConfigurationUseCase.MutationResult(scope, "cfg_user_2");
        }
        /** 配置替换复用补丁回执，确保测试聚焦公共 Wire 结构。 */
        @Override public ConfigurationUseCase.MutationResult replace(
                ConfigurationScope scope, Path root, ConfigurationUseCase.Document document, String version) {
            return patch(scope, root, document, version);
        }
        /** 配置重置复用补丁回执，避免维护另一套测试响应形状。 */
        @Override public ConfigurationUseCase.MutationResult reset(
                ConfigurationScope scope, Path root, String version) {
            return new ConfigurationUseCase.MutationResult(scope, "cfg_user_2");
        }
        /** 返回不含 Secret 的固定凭据设置回执，用于验证响应脱敏。 */
        @Override public ConfigurationUseCase.CredentialResult setCredential(
                String credentialId, String secret, String version) {
            return new ConfigurationUseCase.CredentialResult(credentialId, true, "auth_2");
        }
        /** 返回固定凭据删除回执，验证 configured 状态和版本字段。 */
        @Override public ConfigurationUseCase.CredentialResult deleteCredential(
                String credentialId, String version) {
            return new ConfigurationUseCase.CredentialResult(credentialId, false, "auth_3");
        }
        /** 返回稳定健康状态，避免 Wire 合同测试被无关诊断干扰。 */
        @Override public ConfigurationUseCase.HealthResult health() {
            return new ConfigurationUseCase.HealthResult(
                    ConfigurationUseCase.HealthStatus.HEALTHY, List.of());
        }
    }
}
