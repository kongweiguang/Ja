// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.api;

import io.github.kongweiguang.ja.agent.application.api.engine.AgentAdmissionReceipt;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionHandle;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionOutcome;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentRequest;
import io.github.kongweiguang.ja.agent.application.cancellation.DefaultCancellationCoordinator;
import io.github.kongweiguang.ja.agent.application.engine.AgentEventWatchRegistry;
import io.github.kongweiguang.ja.agent.application.engine.AgentInitialQueuedInputsCodec;
import io.github.kongweiguang.ja.agent.application.engine.AgentRequestBindingCodec;
import io.github.kongweiguang.ja.agent.application.engine.AgentStreamRegistry;
import io.github.kongweiguang.ja.agent.application.engine.ExecutionScheduler;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntimeResolver;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRuntimeRequest;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.domain.error.OutputSchemaException;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.application.loop.AgentLoopExecutionService;
import io.github.kongweiguang.ja.agent.application.service.AgentEngineService;
import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.infrastructure.json.JsonValueCodecFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 校验公开回执始终来自 durable admission，且 observer 是实际调度的同步边界。 */
final class AgentAdmissionReceiptTest {
    private static final AgentIdentity IDENTITY = new AgentIdentity(
            "tenant_test", "thread_test", "turn_test", "user_test");
    private static final Instant ACCEPTED_AT = Instant.parse("2026-10-02T00:00:00Z");
    private static final AgentAdmissionReceipt DURABLE_RECEIPT = new AgentAdmissionReceipt(41, 9, 0, 0);

    /** 未声明能力时不落库、不调度，并释放准入所有权，使用户修正请求后可以继续同一身份。 */
    @Test
    void rejectsUnsupportedOutputSchemaBeforeDurableAdmission() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AgentRequest constrained = fixture.request.withOutputSchema(
                    io.github.kongweiguang.ja.agent.domain.json.JsonObjects.builder()
                            .putText("type", "object").build());
            OutputSchemaException failure = assertThrows(OutputSchemaException.class,
                    () -> fixture.engine.submit(constrained));
            assertEquals("OUTPUT_SCHEMA_UNSUPPORTED", failure.code());
            assertEquals(0, fixture.admitCalls.get());
            assertEquals(0, fixture.executeCalls.get());
            assertNull(fixture.operationReceipt.get());

            AgentExecutionHandle corrected = fixture.engine.submit(fixture.request);
            assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    corrected.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(1, fixture.admitCalls.get());
            assertEquals(1, fixture.executeCalls.get());
        }
    }

    /**
     * 在 schedule 前固定 Store 水位；随后的本地修订变化不能覆盖返回的 admission 事实。
     */
    @Test
    void exposesOriginalReceiptBeforeDispatchAndIgnoresLaterRevision() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicLong currentRevision = new AtomicLong(41);
            AtomicInteger initialObserverCalls = new AtomicInteger();
            AtomicInteger reentrantObserverCalls = new AtomicInteger();
            AtomicReference<AgentExecutionHandle> reentrantHandle = new AtomicReference<>();

            AgentExecutionHandle handle = fixture.engine.submit(fixture.request, receipt -> {
                assertEquals(DURABLE_RECEIPT, receipt);
                assertEquals(0, fixture.executeCalls.get());
                initialObserverCalls.incrementAndGet();
                reentrantHandle.set(fixture.engine.submit(fixture.request, attachedReceipt -> {
                    assertEquals(DURABLE_RECEIPT, attachedReceipt);
                    assertEquals(0, fixture.executeCalls.get());
                    reentrantObserverCalls.incrementAndGet();
                }));
                currentRevision.incrementAndGet();
            });

            assertSame(handle, reentrantHandle.get());
            assertEquals(1, initialObserverCalls.get());
            assertEquals(1, reentrantObserverCalls.get());
            assertEquals(Optional.of(DURABLE_RECEIPT), handle.admissionReceipt().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS));
            assertEquals(42, currentRevision.get());
            assertEquals(41, handle.admissionReceipt().toCompletableFuture().join().orElseThrow().threadRevision());
            assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(1, fixture.executeCalls.get());
        }
    }

    /**
     * 已完成 receipt 的本地重入同步调用自己的 observer；其异常不能被误当成共享 stage 故障吞掉。
     */
    @Test
    void existingReceiptObserverFailurePropagatesAndPreventsDispatch() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CompletionException observerFailure = new CompletionException(
                    new IllegalStateException("existing observer failed"));
            AtomicReference<AgentExecutionHandle> sharedHandle = new AtomicReference<>();

            assertSame(observerFailure, assertThrows(CompletionException.class, () ->
                    fixture.engine.submit(fixture.request, receipt -> {
                        sharedHandle.set(fixture.engine.submit(fixture.request));
                        fixture.engine.submit(fixture.request, ignored -> {
                            throw observerFailure;
                        });
                    })));

            assertEquals(Optional.of(DURABLE_RECEIPT), sharedHandle.get().admissionReceipt().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS));
            ExecutionException completionFailure = assertThrows(ExecutionException.class,
                    () -> sharedHandle.get().completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertSame(observerFailure.getCause(), completionFailure.getCause());
            assertEquals(0, fixture.executeCalls.get());
        }
    }

    /**
     * 同 identity 调用在准入中途只能接入共享句柄；阶段最终完成且不能因第二次调用重复排队。
     */
    @Test
    void pendingLocalReentrySharesReceiptStageWithoutDuplicateDispatch() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.blockAdmission();
            CompletableFuture<AgentExecutionHandle> firstCall = invokeAsync(
                    () -> fixture.engine.submit(fixture.request));
            assertTrue(fixture.admissionEntered.await(5, TimeUnit.SECONDS));

            AtomicInteger observerCalls = new AtomicInteger();
            AgentExecutionHandle attached = fixture.engine.submit(fixture.request,
                    ignored -> observerCalls.incrementAndGet());
            assertFalse(attached.admissionReceipt().toCompletableFuture().isDone());
            assertEquals(0, observerCalls.get());

            fixture.releaseAdmission.countDown();
            assertSame(attached, firstCall.get(5, TimeUnit.SECONDS));
            assertEquals(Optional.of(DURABLE_RECEIPT), attached.admissionReceipt().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS));
            assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    attached.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(1, fixture.admitCalls.get());
            assertEquals(1, fixture.executeCalls.get());
            assertEquals(0, observerCalls.get());
        }
    }

    /**
     * 准入提交前的失败必须结束共享 receipt stage，避免附着句柄永久等待不存在的准入。
     */
    @Test
    void preAdmissionFailureCompletesSharedReceiptStageExceptionally() throws Exception {
        try (Fixture fixture = new Fixture()) {
            IllegalStateException admissionFailure = new IllegalStateException("admission failed");
            fixture.admissionFailure = admissionFailure;
            fixture.blockAdmission();
            CompletableFuture<AgentExecutionHandle> firstCall = invokeAsync(
                    () -> fixture.engine.submit(fixture.request));
            assertTrue(fixture.admissionEntered.await(5, TimeUnit.SECONDS));
            AgentExecutionHandle attached = fixture.engine.submit(fixture.request);

            fixture.releaseAdmission.countDown();
            ExecutionException callFailure = assertThrows(ExecutionException.class,
                    () -> firstCall.get(5, TimeUnit.SECONDS));
            assertSame(admissionFailure, callFailure.getCause());
            ExecutionException receiptFailure = assertThrows(ExecutionException.class,
                    () -> attached.admissionReceipt().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertSame(admissionFailure, receiptFailure.getCause());
            ExecutionException completionFailure = assertThrows(ExecutionException.class,
                    () -> attached.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertSame(admissionFailure, completionFailure.getCause());
            assertEquals(0, fixture.executeCalls.get());
        }
    }

    /**
     * Observer 失败发生在 durable commit 后：回执保持成功、执行不启动，同一 operation 可重放恢复。
     */
    @Test
    void postCommitObserverFailurePreservesReceiptAndDurableReplay() throws Exception {
        try (Fixture fixture = new Fixture()) {
            IllegalStateException observerFailure = new IllegalStateException("observer failed");
            fixture.blockAdmission();
            CompletableFuture<AgentExecutionHandle> firstCall = invokeAsync(
                    () -> fixture.engine.submit(fixture.request, ignored -> {
                        throw observerFailure;
                    }));
            assertTrue(fixture.admissionEntered.await(5, TimeUnit.SECONDS));
            AgentExecutionHandle committedHandle = fixture.engine.submit(fixture.request);

            fixture.releaseAdmission.countDown();
            ExecutionException callFailure = assertThrows(ExecutionException.class,
                    () -> firstCall.get(5, TimeUnit.SECONDS));
            assertSame(observerFailure, callFailure.getCause());
            assertEquals(Optional.of(DURABLE_RECEIPT), committedHandle.admissionReceipt().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS));
            ExecutionException completionFailure = assertThrows(ExecutionException.class,
                    () -> committedHandle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertSame(observerFailure, completionFailure.getCause());
            assertEquals(0, fixture.executeCalls.get());

            fixture.snapshot.set(fixture.snapshotWithMutationVersion(900));
            AgentExecutionHandle replay = fixture.engine.submit(fixture.request, receipt -> {
                assertEquals(DURABLE_RECEIPT, receipt);
                assertEquals(0, fixture.resumeCalls.get());
                assertEquals(0, fixture.executeCalls.get());
            });
            assertEquals(Optional.of(DURABLE_RECEIPT), replay.admissionReceipt().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS));
            assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    replay.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(1, fixture.resumeCalls.get());
            assertEquals(0, fixture.executeCalls.get());
        }
    }

    /**
     * 普通 resume 沿用既有 execution，不发生准入且必须以空回执明确表达该生命周期差异。
     */
    @Test
    void ordinaryResumeReportsNoAdmissionReceipt() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AgentExecutionHandle submitted = fixture.engine.submit(fixture.request);
            assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    submitted.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));

            AgentExecutionHandle resumed = fixture.engine.resume(IDENTITY);

            assertEquals(Optional.empty(), resumed.admissionReceipt().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS));
            assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    resumed.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(1, fixture.executeCalls.get());
            assertEquals(1, fixture.resumeCalls.get());
        }
    }

    /**
     * 将同步 Engine 调用放入虚拟线程，以便测试同一 identity 的并发附着不占住测试线程。
     */
    private static CompletableFuture<AgentExecutionHandle> invokeAsync(Supplier<AgentExecutionHandle> invocation) {
        CompletableFuture<AgentExecutionHandle> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                result.complete(invocation.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /**
     * 使用真实 Engine 调度与绑定编码，Store 和 Loop 仅保留回执、快照及 dispatch 计数。
     */
    private static final class Fixture implements AutoCloseable {
        private final AgentRequest request = AgentRequest.text(IDENTITY, "hello", "op_admission_receipt");
        private final AtomicInteger admitCalls = new AtomicInteger();
        private final AtomicInteger executeCalls = new AtomicInteger();
        private final AtomicInteger resumeCalls = new AtomicInteger();
        private final AtomicReference<AgentStore.OperationReceipt> operationReceipt = new AtomicReference<>();
        private final AtomicReference<AgentStore.StoredRecord> bindingRecord = new AtomicReference<>();
        private final AtomicReference<AgentStore.ExecutionSnapshot> snapshot = new AtomicReference<>();
        private final AgentStore store;
        private final AgentLoopExecutionService loop;
        private final AgentEngineService engine;
        private volatile CountDownLatch admissionEntered;
        private volatile CountDownLatch releaseAdmission;
        private volatile RuntimeException admissionFailure;

        /**
         * 固定 Store 回执与模型选择，使重放测试只由 durable admission 水位决定结果。
         */
        private Fixture() {
            store = (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                    new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> switch (method.getName()) {
                        case "readReceipt" -> Optional.ofNullable(operationReceipt.get());
                        case "loadExecution" -> Optional.ofNullable(snapshot.get());
                        case "readRecord" -> Optional.ofNullable(bindingRecord.get());
                        case "toString" -> "admission-receipt-test-store";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            loop = (AgentLoopExecutionService) Proxy.newProxyInstance(AgentLoopExecutionService.class.getClassLoader(),
                    new Class<?>[]{AgentLoopExecutionService.class}, (proxy, method, arguments) -> switch (method.getName()) {
                        case "admit" -> admit((AgentExecutionRequest) arguments[0],
                                (AgentRequestRuntime) arguments[1], (AgentStore.RecordWrite) arguments[2]);
                        case "execute" -> execute((AgentRequestRuntime) arguments[2]);
                        case "resume" -> resume((AgentRequestRuntime) arguments[1]);
                        case "close" -> null;
                        case "toString" -> "admission-receipt-test-loop";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            ModelPort.ModelConfiguration configuration = modelConfiguration();
            AgentRequestRuntimeResolver resolver = (runtimeRequest, extensions, recovered) -> runtime(
                    runtimeRequest, configuration);
            ExtensionRegistry.Snapshot extensions = new ExtensionRegistry().freeze();
            engine = new AgentEngineService(store, loop, resolver, extensions, new AgentRequestBindingCodec(),
                    new AgentInitialQueuedInputsCodec(), new AgentEventWatchRegistry(store, 8),
                    new AgentStreamRegistry(8), new DefaultCancellationCoordinator(),
                    new ExecutionScheduler(1, 4, 4), null);
        }

        /**
         * 延迟 admit 返回，暴露同进程重复请求和 before-commit 故障的共享 stage 边界。
         */
        private void blockAdmission() {
            admissionEntered = new CountDownLatch(1);
            releaseAdmission = new CountDownLatch(1);
        }

        /**
         * Store 写入新绑定与原始 operation receipt，返回本轮持久准入回执。
         */
        private AgentStore.AdmissionReceipt admit(AgentExecutionRequest execution,
                                                  AgentRequestRuntime runtime,
                                                  AgentStore.RecordWrite binding) throws InterruptedException {
            CountDownLatch entered = admissionEntered;
            CountDownLatch release = releaseAdmission;
            if (entered != null) {
                entered.countDown();
                if (release == null || !release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release admission");
                }
            }
            RuntimeException failure = admissionFailure;
            if (failure != null) {
                throw failure;
            }
            AgentStore.OperationIdentity operation = execution.operation();
            AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(
                    IDENTITY.tenantId(), IDENTITY.threadId(), IDENTITY.userId());
            operationReceipt.set(new AgentStore.OperationReceipt(conversation, IDENTITY.turnId(),
                    operation.operationKey(), operation.fingerprint(), operation.operationType(),
                    DURABLE_RECEIPT.threadRevision(), DURABLE_RECEIPT.mutationVersion(),
                    DURABLE_RECEIPT.firstEventSequence(), DURABLE_RECEIPT.lastEventSequence(),
                    operation.receipt(), ACCEPTED_AT));
            bindingRecord.set(new AgentStore.StoredRecord(binding.recordId(), binding.recordType(),
                    binding.payloadSchemaVersion(), 1, binding.payload(), binding.occurredAt()));
            snapshot.set(snapshotWithMutationVersion(DURABLE_RECEIPT.mutationVersion()));
            admitCalls.incrementAndGet();
            return new AgentStore.AdmissionReceipt(DURABLE_RECEIPT.threadRevision(),
                    DURABLE_RECEIPT.mutationVersion(), DURABLE_RECEIPT.firstEventSequence(),
                    DURABLE_RECEIPT.lastEventSequence(), emptyJson(), true);
        }

        /**
         * 计数并完成执行，证明 observer 返回前没有发生模型循环 dispatch。
         */
        private CompletableFuture<AgentExecutionOutcome> execute(AgentRequestRuntime runtime) {
            executeCalls.incrementAndGet();
            CompletableFuture<AgentExecutionOutcome> result = CompletableFuture.completedFuture(outcome());
            result.whenComplete((ignored, failure) -> runtime.close());
            return result;
        }

        /**
         * 计数恢复路径并接管 runtime 关闭职责，验证 replay 不会再次执行初始 admission。
         */
        private CompletableFuture<AgentExecutionOutcome> resume(AgentRequestRuntime runtime) {
            resumeCalls.incrementAndGet();
            CompletableFuture<AgentExecutionOutcome> result = CompletableFuture.completedFuture(outcome());
            result.whenComplete((ignored, failure) -> runtime.close());
            return result;
        }

        /**
         * 创建高于原准入版本的 snapshot，用来证明 API receipt 没有从当前状态倒推。
         */
        private AgentStore.ExecutionSnapshot snapshotWithMutationVersion(long mutationVersion) {
            AgentStore.ExecutionKey key = new AgentStore.ExecutionKey(
                    new AgentStore.ConversationKey(IDENTITY.tenantId(), IDENTITY.threadId(), IDENTITY.userId()),
                    IDENTITY.turnId());
            return new AgentStore.ExecutionSnapshot(key, 1, "RUNNING", mutationVersion, 1,
                    emptyJson(), 1, ACCEPTED_AT, ACCEPTED_AT);
        }

        /**
         * 等待测试内运行时全部结束后关闭 Engine，避免测试留下调度线程或租约。
         */
        @Override
        public void close() {
            if (releaseAdmission != null) {
                releaseAdmission.countDown();
            }
            engine.closeAsync().toCompletableFuture().join();
        }
    }

    /**
     * 生成没有网络副作用的终态结果，便于断言 Core 的 receipt 与 dispatch 边界。
     */
    private static AgentExecutionOutcome outcome() {
        return new AgentExecutionOutcome.Completed(new TurnResult(TurnState.COMPLETED, "done", null, null));
    }

    /**
     * 使用固定 Provider 身份，让持久绑定解码后能通过 Engine 的恢复模型选择校验。
     */
    private static ModelPort.ModelConfiguration modelConfiguration() {
        return new ModelPort.ModelConfiguration("provider_test", "model_test", "cfg_test",
                ModelPort.Api.OPENAI_RESPONSES, "upstream_test", URI.create("https://example.invalid"),
                "secret_test", Duration.ofSeconds(1), Duration.ofSeconds(1), Set.of(ModelPort.InputModality.TEXT),
                new ModelPort.GenerationOptions(null, null, 16, null));
    }

    /**
     * 构造无网络模型与无附件、终答 Schema 能力的 Engine runtime，测试只验证生命周期所有权。
     */
    private static AgentRequestRuntime runtime(AgentRuntimeRequest request,
                                               ModelPort.ModelConfiguration configuration) {
        ModelPort model = (ModelPort) Proxy.newProxyInstance(ModelPort.class.getClassLoader(),
                new Class<?>[]{ModelPort.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("supportsOutputSchema")) {
                        return false;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        AgentPromptSession prompt = (AgentPromptSession) Proxy.newProxyInstance(AgentPromptSession.class.getClassLoader(),
                new Class<?>[]{AgentPromptSession.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("replaceActiveSkills")) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return new AgentRequestRuntime(configuration, model, binding -> {
            throw new AssertionError("summary model is not used by receipt tests");
        }, new ExtensionRegistry().freeze(), prompt, "0".repeat(64),
                AgentStoreTransactionParticipant.NONE, mutation -> {
                    throw new AssertionError("attachments are not used by receipt tests");
                }, new ToolProjectionLimits(12_000, 2_000), Map.of(), () -> { });
    }

    /**
     * 使用标准 JSON codec 构造符合 Store 类型化边界的空 cursor 和 receipt。
     */
    private static JsonObject emptyJson() {
        return JsonValueCodecFactory.standard().decodeObject("{}");
    }
}
