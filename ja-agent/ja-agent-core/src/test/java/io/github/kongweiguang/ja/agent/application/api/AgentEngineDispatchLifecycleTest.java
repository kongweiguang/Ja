// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.api;

import io.github.kongweiguang.ja.agent.application.service.AgentEngineService;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionHandle;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionOutcome;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionStatus;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentRequest;
import io.github.kongweiguang.ja.agent.application.cancellation.CancellationCoordinator;
import io.github.kongweiguang.ja.agent.application.cancellation.DefaultCancellationCoordinator;
import io.github.kongweiguang.ja.agent.application.engine.AgentEventWatchRegistry;
import io.github.kongweiguang.ja.agent.application.engine.AgentInitialQueuedInputsCodec;
import io.github.kongweiguang.ja.agent.application.engine.AgentRequestBindingCodec;
import io.github.kongweiguang.ja.agent.application.engine.AgentStreamRegistry;
import io.github.kongweiguang.ja.agent.application.engine.ExecutionScheduler;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.application.loop.AgentLoopExecutionService;
import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRuntimeRequest;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.infrastructure.json.JsonValueCodecFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定 facade 向 Core 移交运行时租约的同步边界，防止 close 与慢启动交错导致双重所有权。 */
final class AgentEngineDispatchLifecycleTest {
    /** close 等待所有权移交与清理；终态已经落库而句柄未结束时，不能把取消状态冲突误判为释放失败。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closeWaitsForExecutionDispatchOwnershipHandoff(boolean terminalBeforeCleanup) throws Exception {
        CountDownLatch executeEntered = new CountDownLatch(1);
        CountDownLatch returnFromExecute = new CountDownLatch(1);
        CountDownLatch cancelEntered = new CountDownLatch(1);
        CompletableFuture<AgentExecutionOutcome> execution = new CompletableFuture<>();
        AtomicInteger runtimeCloses = new AtomicInteger();
        AgentRequestRuntime[] suppliedRuntime = new AgentRequestRuntime[1];
        AgentStore store = (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "readReceipt" -> Optional.empty();
                    case "toString" -> "test-store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AgentLoopExecutionService service = (AgentLoopExecutionService) Proxy.newProxyInstance(
                AgentLoopExecutionService.class.getClassLoader(), new Class<?>[]{AgentLoopExecutionService.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "admit" -> new AgentStore.AdmissionReceipt(1, 1, 0, 0, emptyJson(), true);
                    case "execute" -> {
                        AgentRequestRuntime runtime = (AgentRequestRuntime) arguments[2];
                        suppliedRuntime[0] = runtime;
                        executeEntered.countDown();
                        if (!returnFromExecute.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test did not release execute");
                        }
                        execution.whenComplete((result, failure) -> runtime.close());
                        yield execution;
                    }
                    case "cancel" -> {
                        cancelEntered.countDown();
                        if (terminalBeforeCleanup) {
                            throw new IllegalArgumentException("control request cannot target a terminal execution");
                        }
                        yield CompletableFuture.completedFuture(new AgentStore.CommitReceipt(2, 2, 0, 0,
                                emptyJson(), true));
                    }
                    case "query" -> Optional.of(new io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionStatus(
                            (AgentIdentity) arguments[0], "COMPLETED", 1, 1, java.time.Instant.now()));
                    case "close" -> null;
                    case "toString" -> "test-loop-service";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ExtensionRegistry.Snapshot extensions = new ExtensionRegistry().freeze();
        ModelPort.ModelConfiguration configuration = modelConfiguration();
        io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntimeResolver resolver =
                (request, base, recovered) -> runtime(request, configuration, runtimeCloses);
        AgentEngine engine = new AgentEngineService(store, service, resolver, extensions,
                new AgentRequestBindingCodec(), new AgentInitialQueuedInputsCodec(),
                new AgentEventWatchRegistry(store, 8),
                new AgentStreamRegistry(8), new DefaultCancellationCoordinator(),
                new ExecutionScheduler(1, 4, 4), null);

        AgentExecutionHandle handle = engine.submit(AgentRequest.text(
                new AgentIdentity("tenant_test", "thread_test", "turn_test", "user_test"),
                "hello", "op_test"));
        assertTrue(executeEntered.await(5, TimeUnit.SECONDS));
        CompletableFuture<Void> exposedCopy = engine.closeAsync().toCompletableFuture();
        assertTrue(exposedCopy.complete(null));
        CompletableFuture<Void> closing = engine.closeAsync().toCompletableFuture();
        assertFalse(closing.isDone());
        Thread.sleep(50);
        assertFalse(closing.isDone());
        assertEquals(0, runtimeCloses.get());

        returnFromExecute.countDown();
        assertTrue(cancelEntered.await(5, TimeUnit.SECONDS));
        assertFalse(closing.isDone());
        assertEquals(0, runtimeCloses.get());
        execution.complete(new AgentExecutionOutcome.Completed(
                new TurnResult(TurnState.COMPLETED, "done", null, null)));
        closing.get(5, TimeUnit.SECONDS);
        assertEquals(1, runtimeCloses.get());
        AgentExecutionOutcome outcome = handle.completion().toCompletableFuture().join();
        assertTrue(outcome instanceof AgentExecutionOutcome.Completed);
        assertEquals(TurnState.COMPLETED,
                ((AgentExecutionOutcome.Completed) outcome).result().state());
    }

    /** 持久暂停回执只记录请求；本地 owner 仍须释放执行权并提交挂起结果后才能确认暂停完成。 */
    @Test
    void suspendWaitsForLocalExecutionOwnerToSettle() throws Exception {
        AgentIdentity identity = new AgentIdentity("tenant_test", "thread_pause", "turn_pause", "user_test");
        CountDownLatch executeEntered = new CountDownLatch(1);
        CountDownLatch suspendCommitted = new CountDownLatch(1);
        CountDownLatch localCancellationRequested = new CountDownLatch(1);
        CompletableFuture<AgentExecutionOutcome> execution = new CompletableFuture<>();
        AtomicInteger runtimeCloses = new AtomicInteger();
        AgentStore store = (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "readReceipt" -> Optional.empty();
                    case "toString" -> "pause-test-store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AgentLoopExecutionService service = (AgentLoopExecutionService) Proxy.newProxyInstance(
                AgentLoopExecutionService.class.getClassLoader(), new Class<?>[]{AgentLoopExecutionService.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "admit" -> new AgentStore.AdmissionReceipt(1, 1, 0, 0, emptyJson(), true);
                    case "execute" -> {
                        AgentRequestRuntime runtime = (AgentRequestRuntime) arguments[2];
                        execution.whenComplete((outcome, failure) -> runtime.close());
                        executeEntered.countDown();
                        yield execution;
                    }
                    case "suspend" -> {
                        suspendCommitted.countDown();
                        yield CompletableFuture.completedFuture(new AgentStore.CommitReceipt(
                                2, 2, 0, 0, emptyJson(), true));
                    }
                    case "query" -> Optional.of(new AgentExecutionStatus(
                            identity, "RUNNING", 2, 2, java.time.Instant.now()));
                    case "close" -> null;
                    case "toString" -> "test-loop-service";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ModelPort.ModelConfiguration configuration = modelConfiguration();
        DefaultCancellationCoordinator cancellationDelegate = new DefaultCancellationCoordinator();
        CancellationCoordinator observedCancellations = (CancellationCoordinator) Proxy.newProxyInstance(
                CancellationCoordinator.class.getClassLoader(), new Class<?>[]{CancellationCoordinator.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "open" -> cancellationDelegate.open((String) arguments[0], (String) arguments[1]);
                    case "cancel" -> {
                        localCancellationRequested.countDown();
                        yield cancellationDelegate.cancel((String) arguments[0], (String) arguments[1],
                                (String) arguments[2]);
                    }
                    case "find" -> cancellationDelegate.find((String) arguments[0], (String) arguments[1]);
                    case "complete" -> {
                        cancellationDelegate.complete((String) arguments[0], (String) arguments[1]);
                        yield null;
                    }
                    case "close" -> {
                        cancellationDelegate.close();
                        yield null;
                    }
                    case "toString" -> "observed-cancellation-coordinator";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AgentEngine engine = new AgentEngineService(store, service,
                (request, base, recovered) -> runtime(request, configuration, runtimeCloses),
                new ExtensionRegistry().freeze(), new AgentRequestBindingCodec(),
                new AgentInitialQueuedInputsCodec(), new AgentEventWatchRegistry(store, 8),
                new AgentStreamRegistry(8), observedCancellations, new ExecutionScheduler(1, 4, 4), null);

        try {
            AgentExecutionHandle handle = engine.submit(AgentRequest.text(identity, "hello", "op_start"));
            assertTrue(executeEntered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> pause = engine.suspend(identity, "op_pause", "plan_pause").toCompletableFuture();
            assertTrue(suspendCommitted.await(5, TimeUnit.SECONDS));
            assertTrue(localCancellationRequested.await(5, TimeUnit.SECONDS));
            assertFalse(pause.isDone());

            AgentExecutionOutcome.Suspended settled = new AgentExecutionOutcome.Suspended(
                    new AgentExecutionStatus(identity, "SUSPENDED", 3, 3, java.time.Instant.now()), Optional.empty());
            execution.complete(settled);
            pause.get(5, TimeUnit.SECONDS);
            assertSame(settled, handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(1, runtimeCloses.get());
        } finally {
            execution.complete(new AgentExecutionOutcome.Suspended(
                    new AgentExecutionStatus(identity, "SUSPENDED", 3, 3, java.time.Instant.now()), Optional.empty()));
            engine.closeAsync().handle((ignored, failure) -> null).toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    /** 没有本地 owner 时，仍处于 RUNNING 的持久状态不能被控制请求回执伪装成已暂停。 */
    @Test
    void suspendWithoutLocalOwnerRejectsRunningStoreState() throws Exception {
        AgentIdentity identity = new AgentIdentity("tenant_test", "thread_unowned", "turn_unowned", "user_test");
        AtomicInteger suspendCalls = new AtomicInteger();
        AgentStore store = (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "readReceipt" -> Optional.empty();
                    case "toString" -> "unowned-pause-test-store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AgentLoopExecutionService service = (AgentLoopExecutionService) Proxy.newProxyInstance(
                AgentLoopExecutionService.class.getClassLoader(), new Class<?>[]{AgentLoopExecutionService.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "suspend" -> {
                        suspendCalls.incrementAndGet();
                        yield CompletableFuture.completedFuture(new AgentStore.CommitReceipt(
                                2, 2, 0, 0, emptyJson(), true));
                    }
                    case "query" -> Optional.of(new AgentExecutionStatus(
                            identity, "RUNNING", 2, 2, java.time.Instant.now()));
                    case "close" -> null;
                    case "toString" -> "test-loop-service";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ModelPort.ModelConfiguration configuration = modelConfiguration();
        AgentEngine engine = new AgentEngineService(store, service,
                (request, base, recovered) -> runtime(request, configuration, new AtomicInteger()),
                new ExtensionRegistry().freeze(), new AgentRequestBindingCodec(),
                new AgentInitialQueuedInputsCodec(), new AgentEventWatchRegistry(store, 8),
                new AgentStreamRegistry(8), new DefaultCancellationCoordinator(), new ExecutionScheduler(1, 4, 4), null);

        try (engine) {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> engine.suspend(identity, "op_pause", "plan_pause")
                            .toCompletableFuture().get(5, TimeUnit.SECONDS));
            IllegalStateException rejection = assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals("suspend request has no local owner to settle the running execution",
                    rejection.getMessage());
            assertEquals(1, suspendCalls.get());
        }
    }

    /** 多个 shutdown 回调并发失败时，最终异常必须保留每个执行的诊断。 */
    @Test
    void retainsConcurrentShutdownFailures() throws Exception {
        int executionCount = 32;
        CountDownLatch executionsStarted = new CountDownLatch(executionCount);
        CountDownLatch cancellationCalls = new CountDownLatch(executionCount);
        ConcurrentHashMap<String, CompletableFuture<AgentExecutionOutcome>> executions = new ConcurrentHashMap<>();
        AtomicInteger runtimeCloses = new AtomicInteger();
        AgentStore store = (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "readReceipt" -> Optional.empty();
                    case "toString" -> "test-store";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AgentLoopExecutionService service = (AgentLoopExecutionService) Proxy.newProxyInstance(
                AgentLoopExecutionService.class.getClassLoader(), new Class<?>[]{AgentLoopExecutionService.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "admit" -> new AgentStore.AdmissionReceipt(1, 1, 0, 0, emptyJson(), true);
                    case "execute" -> {
                        AgentExecutionRequest request = (AgentExecutionRequest) arguments[0];
                        AgentRequestRuntime runtime = (AgentRequestRuntime) arguments[2];
                        CompletableFuture<AgentExecutionOutcome> execution = new CompletableFuture<>();
                        executions.put(request.identity().turnId(), execution);
                        execution.whenComplete((outcome, failure) -> runtime.close());
                        executionsStarted.countDown();
                        yield execution;
                    }
                    case "cancel" -> {
                        AgentIdentity identity = (AgentIdentity) arguments[0];
                        CompletableFuture<AgentStore.CommitReceipt> cancellation = new CompletableFuture<>();
                        cancellationCalls.countDown();
                        Thread.ofVirtual().start(() -> {
                            try {
                                if (!cancellationCalls.await(5, TimeUnit.SECONDS)) {
                                    cancellation.completeExceptionally(
                                            new IllegalStateException("shutdown cancellation barrier timed out"));
                                    return;
                                }
                                cancellation.completeExceptionally(new IllegalStateException(
                                        "shutdown_cancel_failed_" + identity.turnId()));
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                cancellation.completeExceptionally(interrupted);
                            }
                        });
                        yield cancellation;
                    }
                    case "close" -> {
                        executions.values().forEach(execution -> execution.complete(
                                new AgentExecutionOutcome.Completed(new TurnResult(TurnState.COMPLETED,
                                        "done", null, null))));
                        yield null;
                    }
                    case "toString" -> "test-loop-service";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ModelPort.ModelConfiguration configuration = modelConfiguration();
        io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntimeResolver resolver =
                (request, base, recovered) -> runtime(request, configuration, runtimeCloses);
        AgentEngine engine = new AgentEngineService(store, service, resolver, new ExtensionRegistry().freeze(),
                new AgentRequestBindingCodec(), new AgentInitialQueuedInputsCodec(),
                new AgentEventWatchRegistry(store, executionCount), new AgentStreamRegistry(executionCount),
                new DefaultCancellationCoordinator(),
                new ExecutionScheduler(executionCount, executionCount, 1), null);

        for (int index = 0; index < executionCount; index++) {
            AgentIdentity identity = new AgentIdentity("tenant_test", "thread_" + index,
                    "turn_" + index, "user_test");
            engine.submit(AgentRequest.text(identity, "hello", "operation_" + index));
        }
        assertTrue(executionsStarted.await(5, TimeUnit.SECONDS));

        ExecutionException shutdownFailure = assertThrows(ExecutionException.class,
                () -> engine.closeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS));
        IllegalStateException aggregate = assertInstanceOf(IllegalStateException.class, shutdownFailure.getCause());
        assertEquals(executionCount, aggregate.getSuppressed().length);
        assertEquals(executionCount, java.util.Arrays.stream(aggregate.getSuppressed())
                .map(Throwable::getMessage)
                .filter(message -> message != null && message.startsWith("shutdown_cancel_failed_"))
                .distinct().count());
        assertEquals(executionCount, runtimeCloses.get());
    }

    /** 一个 owner 失败后仍关闭后续资源，并将 RuntimeException 与 Error 原样保留在关闭诊断中。 */
    @Test
    void continuesClosingOwnersAndRetainsEveryFailure() {
        IllegalStateException firstFailure = new IllegalStateException("scheduler close failed");
        AssertionError laterFailure = new AssertionError("watch close failed");
        AtomicInteger laterOwnersClosed = new AtomicInteger();
        java.util.List<Throwable> failures = new ArrayList<>();

        AgentEngineService.closeOwners(failures,
                () -> { throw firstFailure; },
                laterOwnersClosed::incrementAndGet,
                () -> { throw laterFailure; },
                laterOwnersClosed::incrementAndGet);
        IllegalStateException diagnostic = AgentEngineService.shutdownFailure(failures);

        assertEquals("AgentEngine shutdown did not complete cleanly", diagnostic.getMessage());
        assertEquals(2, laterOwnersClosed.get());
        assertEquals(2, diagnostic.getSuppressed().length);
        assertSame(firstFailure, diagnostic.getSuppressed()[0]);
        assertSame(laterFailure, diagnostic.getSuppressed()[1]);
    }

    /** 准入失败与关闭 Error 相撞时保留原因，并释放句柄和同会话槽位供后续请求使用。 */
    @Test
    void admissionFailureRetainsReleaseErrorAndAllowsNextSubmission() throws Exception {
        IllegalStateException admissionFailure = new IllegalStateException("admission failed");
        AssertionError releaseFailure = new AssertionError("runtime close failed");
        AtomicInteger admissions = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AgentLoopExecutionService service = (AgentLoopExecutionService) Proxy.newProxyInstance(
                AgentLoopExecutionService.class.getClassLoader(), new Class<?>[]{AgentLoopExecutionService.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "admit" -> {
                        if (admissions.incrementAndGet() == 1) {
                            throw admissionFailure;
                        }
                        yield new AgentStore.AdmissionReceipt(1, 1, 0, 0, emptyJson(), true);
                    }
                    case "execute" -> {
                        ((AgentRequestRuntime) arguments[2]).close();
                        yield CompletableFuture.completedFuture(new AgentExecutionOutcome.Completed(
                                new TurnResult(TurnState.COMPLETED, "done", null, null)));
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        try (AgentEngine engine = failureEngine(service, () -> {
            if (closes.incrementAndGet() == 1) {
                throw releaseFailure;
            }
        })) {
            AgentRequest request = AgentRequest.text(
                    new AgentIdentity("tenant_test", "thread_test", "turn_test", "user_test"), "hello", "op_test");
            assertSame(admissionFailure, assertThrows(IllegalStateException.class, () -> engine.submit(request)));
            assertSame(releaseFailure, admissionFailure.getSuppressed()[0]);
            AgentExecutionOutcome outcome = engine.submit(request).completion().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertInstanceOf(AgentExecutionOutcome.Completed.class, outcome);
            assertEquals(2, admissions.get());
            assertEquals(2, closes.get());
        }
    }

    /** 派发清理抛 Error 仍完成句柄和调度；关闭期间还必须将清理失败送入最终关闭诊断。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dispatchFailureRetainsReleaseErrorAndSettlesCleanup(boolean shutdownDuringCleanup) throws Exception {
        IllegalStateException dispatchFailure = new IllegalStateException("dispatch failed");
        AssertionError releaseFailure = new AssertionError("runtime close failed");
        CountDownLatch cancellationEntered = new CountDownLatch(1);
        CompletableFuture<AgentStore.CommitReceipt> cancellation = new CompletableFuture<>();
        AtomicInteger dispatches = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AgentLoopExecutionService service = (AgentLoopExecutionService) Proxy.newProxyInstance(
                AgentLoopExecutionService.class.getClassLoader(), new Class<?>[]{AgentLoopExecutionService.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "admit" -> new AgentStore.AdmissionReceipt(1, 1, 0, 0, emptyJson(), true);
                    case "execute" -> {
                        if (dispatches.incrementAndGet() == 1) {
                            throw dispatchFailure;
                        }
                        ((AgentRequestRuntime) arguments[2]).close();
                        yield CompletableFuture.completedFuture(new AgentExecutionOutcome.Completed(
                                new TurnResult(TurnState.COMPLETED, "done", null, null)));
                    }
                    case "cancel" -> {
                        cancellationEntered.countDown();
                        yield cancellation;
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AgentEngine engine = failureEngine(service, () -> {
            if (closes.incrementAndGet() == 1) {
                throw releaseFailure;
            }
        });
        try {
            AgentExecutionHandle first = engine.submit(AgentRequest.text(
                    new AgentIdentity("tenant_test", "thread_test", "turn_first", "user_test"), "hello", "op_first"));
            assertTrue(cancellationEntered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> closing = shutdownDuringCleanup ? engine.closeAsync().toCompletableFuture() : null;
            AgentExecutionHandle second = shutdownDuringCleanup ? null : engine.submit(AgentRequest.text(
                    new AgentIdentity("tenant_test", "thread_test", "turn_second", "user_test"), "hello", "op_second"));
            cancellation.complete(new AgentStore.CommitReceipt(2, 2, 0, 0, emptyJson(), true));
            ExecutionException failed = assertThrows(ExecutionException.class,
                    () -> first.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertSame(dispatchFailure, failed.getCause());
            assertSame(releaseFailure, dispatchFailure.getSuppressed()[0]);
            if (shutdownDuringCleanup) {
                ExecutionException shutdown = assertThrows(ExecutionException.class,
                        () -> closing.get(5, TimeUnit.SECONDS));
                assertSame(dispatchFailure, shutdown.getCause().getSuppressed()[0]);
                assertEquals(1, closes.get());
            } else {
                assertInstanceOf(AgentExecutionOutcome.Completed.class,
                        second.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
                engine.closeAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(2, closes.get());
            }
        } finally {
            cancellation.complete(new AgentStore.CommitReceipt(2, 2, 0, 0, emptyJson(), true));
            engine.closeAsync().handle((ignored, failure) -> null).toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    /** 使用真实调度器和取消 owner，替身只隔离存储与业务循环，关闭错误由解析器释放动作注入。 */
    private static AgentEngine failureEngine(AgentLoopExecutionService service, AutoCloseable release) {
        AgentStore store = (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "readReceipt" -> Optional.empty();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new AgentEngineService(store, service,
                (request, base, recovered) -> runtime(request, modelConfiguration(), release),
                new ExtensionRegistry().freeze(), new AgentRequestBindingCodec(),
                new AgentInitialQueuedInputsCodec(), new AgentEventWatchRegistry(store, 8),
                new AgentStreamRegistry(8), new DefaultCancellationCoordinator(), new ExecutionScheduler(1, 4, 4), null);
    }

    /** 构造不可变空回执，保持代理返回值符合 Store 的类型化 JSON 边界。 */
    private static io.github.kongweiguang.ja.agent.domain.json.JsonObject emptyJson() {
        return JsonValueCodecFactory.standard().decodeObject("{}");
    }

    /** 为 facade 移交测试提供无网络模型与能记录释放次数的请求运行时。 */
    private static AgentRequestRuntime runtime(AgentRuntimeRequest request,
            ModelPort.ModelConfiguration configuration, AtomicInteger runtimeCloses) {
        return runtime(request, configuration, runtimeCloses::incrementAndGet);
    }

    /** 将释放动作作为独立故障入口，避免用反射或公开测试 API 改变生产生命周期。 */
    private static AgentRequestRuntime runtime(AgentRuntimeRequest request,
            ModelPort.ModelConfiguration configuration, AutoCloseable release) {
        ModelPort model = (ModelPort) Proxy.newProxyInstance(ModelPort.class.getClassLoader(),
                new Class<?>[]{ModelPort.class}, (proxy, method, arguments) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
        AgentPromptSession prompt = (AgentPromptSession) Proxy.newProxyInstance(
                AgentPromptSession.class.getClassLoader(), new Class<?>[]{AgentPromptSession.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("replaceActiveSkills")) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        ExtensionRegistry.Snapshot empty = new ExtensionRegistry().freeze();
        String bindingFingerprint = "0".repeat(64);
        return new AgentRequestRuntime(configuration, model, binding -> {
            throw new AssertionError("summary model is not used by dispatch tests");
        }, empty, prompt, bindingFingerprint,
                io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant.NONE, mutation -> {
                    throw new AssertionError("managed attachment is not used by dispatch tests");
                }, new ToolProjectionLimits(12_000, 2_000), Map.of(), release);
    }

    /** 构造仅供本地服务替身使用的明确模型选择，测试不会发起 Provider 请求。 */
    private static ModelPort.ModelConfiguration modelConfiguration() {
        return new ModelPort.ModelConfiguration("provider_test", "model_test", "cfg_test",
                ModelPort.Api.OPENAI_RESPONSES, "upstream_test", URI.create("https://example.invalid"), "secret_test",
                Duration.ofSeconds(1), Duration.ofSeconds(1), java.util.Set.of(ModelPort.InputModality.TEXT),
                new ModelPort.GenerationOptions(null, null, 16, null));
    }
}
