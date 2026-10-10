// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.service;


import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentAdmissionReceipt;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentAnswer;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentCompactionRequest;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentEventPage;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentEventWatch;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionHandle;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionOutcome;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionStatus;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInput;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputMutationReceipt;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInternalRequest;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentRequest;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentStreamSubscription;
import io.github.kongweiguang.ja.agent.application.cancellation.CancellationCoordinator;
import io.github.kongweiguang.ja.agent.application.engine.AgentEventListenerDispatcher;
import io.github.kongweiguang.ja.agent.application.engine.AgentEventReader;
import io.github.kongweiguang.ja.agent.application.engine.AgentEventWatchRegistry;
import io.github.kongweiguang.ja.agent.application.engine.AgentInitialQueuedInputsCodec;
import io.github.kongweiguang.ja.agent.application.engine.AgentRequestBinding;
import io.github.kongweiguang.ja.agent.application.engine.AgentRequestBindingCodec;
import io.github.kongweiguang.ja.agent.application.engine.AgentRequestFingerprint;
import io.github.kongweiguang.ja.agent.shared.Hashing;
import io.github.kongweiguang.ja.agent.application.engine.AgentStreamRegistry;
import io.github.kongweiguang.ja.agent.application.engine.ExecutionScheduler;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.application.loop.AgentInputMutation;
import io.github.kongweiguang.ja.agent.application.loop.AgentLoopExecutionService;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.error.OutputSchemaException;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.tool.RecoveryCommand;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.AgentEvent;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntimeResolver;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRuntimeRequest;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.ModelSelection;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 单个持久化 Agent 执行循环的公开门面；事实由 Store 持有，Engine 管理调度与订阅。
 */
public final class AgentEngineService implements AgentEngine {
    private static final System.Logger LOGGER = System.getLogger(AgentEngineService.class.getName());

    private final AgentStore store;
    private final AgentLoopExecutionService service;
    private final AgentRequestRuntimeResolver runtimeResolver;
    private final ExtensionRegistry.Snapshot baseExtensions;
    private final AgentRequestBindingCodec bindingCodec;
    private final AgentInitialQueuedInputsCodec initialQueuedInputsCodec;
    private final AgentEventWatchRegistry eventWatches;
    private final AgentEventReader eventReader;
    private final AgentEventListenerDispatcher listenerDispatcher = new AgentEventListenerDispatcher(256);
    private final AgentStreamRegistry streamRegistry;
    private final CancellationCoordinator cancellations;
    private final ExecutionScheduler scheduler;
    private final AutoCloseable ownedModelResources;
    private final Object handlesLock = new Object();
    private final Map<AgentIdentity, ExecutionRecord> localHandles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AgentIdentity, CancellationCoordinator.CancellationScope> activeScopes
        = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closeStarted = new AtomicBoolean();
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();

    /**
     * 仅持有装配时自建的运行资源；持久化适配器和业务能力均由宿主管理。
     */
    public AgentEngineService(AgentStore store, AgentLoopExecutionService service, AgentRequestRuntimeResolver runtimeResolver,
                ExtensionRegistry.Snapshot baseExtensions, AgentRequestBindingCodec bindingCodec,
                AgentInitialQueuedInputsCodec initialQueuedInputsCodec,
                AgentEventWatchRegistry eventWatches,
                AgentStreamRegistry streamRegistry,
                CancellationCoordinator cancellations, ExecutionScheduler scheduler,
                AutoCloseable ownedModelResources) {
        this.store = Objects.requireNonNull(store, "store");
        this.service = Objects.requireNonNull(service, "service");
        this.runtimeResolver = Objects.requireNonNull(runtimeResolver, "runtimeResolver");
        this.baseExtensions = Objects.requireNonNull(baseExtensions, "baseExtensions");
        this.bindingCodec = Objects.requireNonNull(bindingCodec, "bindingCodec");
        this.initialQueuedInputsCodec = Objects.requireNonNull(initialQueuedInputsCodec, "initialQueuedInputsCodec");
        this.eventWatches = Objects.requireNonNull(eventWatches, "eventWatches");
        this.eventReader = new AgentEventReader(store);
        this.streamRegistry = Objects.requireNonNull(streamRegistry, "streamRegistry");
        this.cancellations = Objects.requireNonNull(cancellations, "cancellations");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownedModelResources = ownedModelResources;
    }

    /**
     * 返回显式装配入口；库不会扫描框架配置或进程级设置。
     */
    /**
     * 将公开闭集变更转换成 reducer 命令，队列修订仍由 Store 当前水位计算。
     */
    private static AgentInputMutation toInputMutation(AgentInputCommand command) {
        if (command instanceof AgentInputCommand.Enqueue enqueue) {
            return new AgentInputMutation(AgentInputQueue.Action.ENQUEUE, enqueue.inputId(), 0,
                AgentInputQueue.Kind.valueOf(enqueue.kind().name()), Optional.of(enqueue.content()), null,
                enqueue.hostMetadata());
        }
        if (command instanceof AgentInputCommand.Update update) {
            return new AgentInputMutation(AgentInputQueue.Action.UPDATE, update.inputId(),
                update.expectedInputRevision(), null, Optional.of(update.content()), null, update.hostMetadata());
        }
        if (command instanceof AgentInputCommand.Delete delete) {
            return new AgentInputMutation(AgentInputQueue.Action.DELETE, delete.inputId(),
                delete.expectedInputRevision(), null, Optional.empty(), null, delete.hostMetadata());
        }
        if (command instanceof AgentInputCommand.Prioritize prioritize) {
            return new AgentInputMutation(AgentInputQueue.Action.PRIORITIZE, prioritize.inputId(),
                prioritize.expectedInputRevision(), null, Optional.empty(), null, prioritize.hostMetadata());
        }
        throw new IllegalArgumentException("unsupported input queue command");
    }

    /**
     * 等待多个取消或执行阶段全部收敛，保留首要失败并继续清理其余资源。
     */
    private static void awaitAll(List<CompletableFuture<?>> stages, List<Throwable> failures) {
        try {
            CompletableFuture.allOf(stages.toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException failure) {
            failures.add(unwrap(failure));
        }
    }

    /**
     * Engine 接管传入 owner 的关闭职责；逐项释放可在单项失败后继续关闭并保留每个原始异常。
     */
    @SuppressWarnings("PMD.CloseResource") // 由 Engine 集中拥有并逐项关闭这些资源，自动作用域会改变失败汇总边界。
    public static void closeOwners(List<Throwable> failures, AutoCloseable... owners) {
        for (AutoCloseable owner : owners) {
            try {
                owner.close();
            } catch (Exception | Error failure) {
                failures.add(failure);
            }
        }
    }

    /**
     * 将各关闭阶段的原始异常放入统一诊断，保留异常类型及其堆栈。
     */
    public static IllegalStateException shutdownFailure(List<Throwable> failures) {
        IllegalStateException aggregate = new IllegalStateException("AgentEngine shutdown did not complete cleanly");
        failures.forEach(aggregate::addSuppressed);
        return aggregate;
    }

    /**
     * 由租户和 Thread 构造共享调度键，不把任意业务标识直接暴露给执行器。
     */
    private static ExecutionScheduler.ThreadKey threadKey(AgentIdentity identity) {
        return new ExecutionScheduler.ThreadKey(identity.tenantId(), identity.threadId());
    }

    /**
     * 从原操作派生可重试的内部取消身份，不保存原请求正文。
     */
    private static AgentStore.OperationIdentity cancellationForAbandon(
        AgentIdentity identity, AgentStore.OperationIdentity admittedOperation) {
        String key = "engine_abandon_" + admittedOperation.operationKey().substring(3, 35);
        return AgentRequestFingerprint.prepareCancellation(identity, key,
            "agent engine could not dispatch the admitted execution");
    }

    /**
     * 在放入协调器键前散列调用方控制的租户和 Thread 值，并以独立前缀避免冒充真实 threadId。
     */
    private static String cancellationThreadKey(AgentIdentity identity) {
        return "thread_scope_" + Hashing.sha256HexPrefix(
                identity.tenantId() + "\u0000" + identity.threadId(), 20);
    }

    /**
     * 关闭单次操作租约；解析器的 Error 也作为清理诊断保留，不能覆盖操作的主要异常。
     */
    private static void closeRuntime(AgentRequestRuntime runtime, Throwable primary) {
        try {
            runtime.close();
        } catch (RuntimeException | Error closeFailure) {
            if (primary != closeFailure) {
                primary.addSuppressed(closeFailure);
            }
        }
    }

    /**
     * 去除异步阶段包装，但保留模型、工具或持久化操作的原始异常。
     */
    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 使用无同步观察器的便捷入口，保持准入与调度只有一条实现路径。
     */
    public AgentExecutionHandle submit(AgentRequest request) {
        return submit(request, ignored -> { });
    }

    /**
     * 用户正文和首轮队列原子准入；同步观察原始水位后才调度，租约在 Loop 接受前由 Engine 持有。
     * 终答能力在持久准入前以领域错误拒绝，宿主可区分能力缺失且不会留下被受理的失败任务。
     */
    @SuppressWarnings("PMD.CloseResource")
    public AgentExecutionHandle submit(AgentRequest request, Consumer<AgentAdmissionReceipt> beforeSchedule) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(beforeSchedule, "beforeSchedule");
        ensureOpen();
        String requestFingerprint = AgentRequestFingerprint.requestFingerprint(request);
        HandleRegistration registration = registerHandle(request.identity(), requestFingerprint, Mode.SUBMIT);
        ExecutionRecord record = registration.record();
        if (!registration.fresh()) {
            deliverExistingAdmissionReceipt(record, beforeSchedule);
            return record.handle;
        }
        try {
            synchronized (record.setupLock) {
                ensureOpen();
                record.reserve(scheduler.reserve(threadKey(request.identity())));
                String scopedOperation = AgentRequestFingerprint.submissionOperationKey(
                    request.identity(), request.operationKey());
                if (resumeExistingOperation(record, request.identity(), scopedOperation,
                    receipt -> AgentRequestFingerprint.requireSameSubmittedRequest(receipt, request),
                    beforeSchedule)) {
                    return record.handle;
                }

                record.scope = openScope(record);
                AgentRequestRuntime runtime = resolveRuntime(AgentRuntimeRequest.Kind.USER_INPUT,
                    request.identity(), request.modelOverride(), request.contextAttributes(),
                    Optional.empty(), record.scope);
                request.outputSchema().ifPresent(schema -> {
                    if (!runtime.model().supportsOutputSchema(runtime.modelConfiguration())) {
                        throw OutputSchemaException.unsupported();
                    }
                });
                record.runtime = runtime;
                AgentRequestFingerprint.PreparedRequest prepared = AgentRequestFingerprint.prepare(request,
                    runtime.modelConfiguration(), runtime.bindingFingerprint());
                AgentExecutionRequest executionRequest = new AgentExecutionRequest(request.identity(),
                    AgentExecutionRequest.AdmissionKind.USER_INPUT, Optional.of(request.input()),
                    prepared.inputMessageId(), Optional.empty(), prepared.operation(), request.contextAttributes(),
                    request.history(), request.acceptedAt(), request.initialQueuedInputs(),
                    request.outputSchema(), request.initialContextSeed());
                admitAndSchedule(record, executionRequest, runtime, scopedOperation,
                    saved -> AgentRequestFingerprint.requireSameSubmittedRequest(saved, request), beforeSchedule);
                return record.handle;
            }
        } catch (RuntimeException | Error failure) {
            abortSetup(record, failure);
            throw failure;
        }
    }

    /**
     * 使用无同步观察器的便捷入口，保持准入与调度只有一条实现路径。
     */
    public AgentExecutionHandle submitInternal(AgentInternalRequest request) {
        return submitInternal(request, ignored -> { });
    }

    /**
     * 隐藏指令及纯 MESSAGE 首批队列共用一次 Store 准入；观察原始水位后才转交运行时租约。
     */
    @SuppressWarnings("PMD.CloseResource")
    public AgentExecutionHandle submitInternal(AgentInternalRequest request,
                                               Consumer<AgentAdmissionReceipt> beforeSchedule) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(beforeSchedule, "beforeSchedule");
        ensureOpen();
        String requestFingerprint = AgentRequestFingerprint.internalRequestFingerprint(request);
        HandleRegistration registration = registerHandle(request.identity(), requestFingerprint, Mode.SUBMIT);
        ExecutionRecord record = registration.record();
        if (!registration.fresh()) {
            deliverExistingAdmissionReceipt(record, beforeSchedule);
            return record.handle;
        }
        try {
            synchronized (record.setupLock) {
                ensureOpen();
                record.reserve(scheduler.reserve(threadKey(request.identity())));
                String scopedOperation = AgentRequestFingerprint.internalSubmissionOperationKey(
                    request.identity(), request.operationKey());
                if (resumeExistingOperation(record, request.identity(), scopedOperation,
                    receipt -> AgentRequestFingerprint.requireSameInternalRequest(receipt, request),
                    beforeSchedule)) {
                    return record.handle;
                }

                record.scope = openScope(record);
                AgentRequestRuntime runtime = resolveRuntime(AgentRuntimeRequest.Kind.INTERNAL, request.identity(), request.modelOverride(),
                    request.contextAttributes(), Optional.empty(), record.scope);
                record.runtime = runtime;
                AgentRequestFingerprint.PreparedInternalRequest prepared = AgentRequestFingerprint.prepareInternal(
                    request, runtime.modelConfiguration(), runtime.bindingFingerprint());
                AgentExecutionRequest executionRequest = new AgentExecutionRequest(request.identity(),
                    AgentExecutionRequest.AdmissionKind.INTERNAL, Optional.empty(), null,
                    Optional.of(request.instruction()), prepared.operation(), request.contextAttributes(),
                    request.history(), request.acceptedAt(), request.initialQueuedInputs(),
                    Optional.empty(), request.initialContextSeed());
                admitAndSchedule(record, executionRequest, runtime, scopedOperation,
                    saved -> AgentRequestFingerprint.requireSameInternalRequest(saved, request), beforeSchedule);
                return record.handle;
            }
        } catch (RuntimeException | Error failure) {
            abortSetup(record, failure);
            throw failure;
        }
    }

    /**
     * 通过原始提交回执恢复已准入批次，避免重试时把后来待投递消息拼入旧 admission。
     */
    public Optional<List<AgentInputCommand.Enqueue>> readAdmittedInitialInputs(AgentRequest request) {
        AgentRequest value = Objects.requireNonNull(request, "request");
        return readAdmittedInitialInputs(value.identity(), "agent.submit",
            AgentRequestFingerprint.submissionOperationKey(value.identity(), value.operationKey()),
            AgentExecutionRequest.AdmissionKind.USER_INPUT);
    }

    /**
     * 只按内部提交身份读取其最初 MESSAGE 批次，不能回退到当前 PENDING 邮箱内容。
     */
    public Optional<List<AgentInputCommand.Enqueue>> readAdmittedInitialInputs(AgentInternalRequest request) {
        AgentInternalRequest value = Objects.requireNonNull(request, "request");
        return readAdmittedInitialInputs(value.identity(), "agent.internal",
            AgentRequestFingerprint.internalSubmissionOperationKey(value.identity(), value.operationKey()),
            AgentExecutionRequest.AdmissionKind.INTERNAL);
    }

    /**
     * Store receipt、请求绑定和稳定批次记录必须指向同一 operation 与 execution。
     */
    private Optional<List<AgentInputCommand.Enqueue>> readAdmittedInitialInputs(
        AgentIdentity identity, String operationType, String operationKey,
        AgentExecutionRequest.AdmissionKind expectedKind) {
        ensureOpen();
        AgentStore.OperationKey receiptKey = new AgentStore.OperationKey(
                identity.tenantId(), identity.userId(), operationKey);
        Optional<AgentStore.OperationReceipt> saved = store.readReceipt(receiptKey);
        if (saved.isEmpty()) {
            return Optional.empty();
        }
        AgentStore.OperationReceipt receipt = saved.orElseThrow();
        if (!receipt.operationKey().equals(operationKey) || !receipt.operationType().equals(operationType)
            || !receipt.conversation().tenantId().equals(identity.tenantId())
            || !receipt.conversation().threadId().equals(identity.threadId())
            || !receipt.turnId().equals(identity.turnId())) {
            throw new IllegalArgumentException("operation key belongs to another operation");
        }
        AgentStore.ExecutionSnapshot snapshot = loadExecution(identity);
        AgentRequestBinding binding = bindingCodec.read(store, snapshot, identity);
        if (binding.kind() != expectedKind) {
            throw new IllegalArgumentException("admission kind does not match request");
        }
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(receipt.conversation(), receipt.turnId());
        String expectedRecordId = AgentInitialQueuedInputsCodec.recordId(execution, operationKey);
        if (!expectedRecordId.equals(binding.initialQueuedInputsRecordId())) {
            throw new IllegalStateException("stored initial input batch reference is inconsistent");
        }
        AgentStore.StoredRecord bindingRecord = store.readRecord(execution, AgentRequestBindingCodec.recordId(execution))
            .orElseThrow(() -> new IllegalStateException("stored request binding is missing"));
        AgentStore.StoredRecord batchRecord = store.readRecord(execution, expectedRecordId)
            .orElseThrow(() -> new IllegalStateException("stored initial input batch is missing"));
        if (batchRecord.sequence() >= bindingRecord.sequence()
            || bindingRecord.sequence() > snapshot.lastRecordSequence()) {
            throw new IllegalStateException("stored initial input batch is outside its admission boundary");
        }
        return Optional.of(initialQueuedInputsCodec.decode(batchRecord, execution, operationKey));
    }

    /**
     * 使用无同步观察器的便捷入口，保持准入与调度只有一条实现路径。
     */
    public AgentExecutionHandle compact(AgentCompactionRequest request) {
        return compact(request, ignored -> { });
    }

    /**
     * 手动压缩以独立 execution 进入会话队列；观察持久水位后才启动调度。
     */
    @SuppressWarnings("PMD.CloseResource")
    public AgentExecutionHandle compact(AgentCompactionRequest request,
                                       Consumer<AgentAdmissionReceipt> beforeSchedule) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(beforeSchedule, "beforeSchedule");
        ensureOpen();
        String requestFingerprint = AgentRequestFingerprint.compactionRequestFingerprint(request);
        HandleRegistration registration = registerHandle(request.identity(), requestFingerprint, Mode.SUBMIT);
        ExecutionRecord record = registration.record();
        if (!registration.fresh()) {
            deliverExistingAdmissionReceipt(record, beforeSchedule);
            return record.handle;
        }
        try {
            synchronized (record.setupLock) {
                ensureOpen();
                record.reserve(scheduler.reserve(threadKey(request.identity())));
                String scopedOperation = AgentRequestFingerprint.compactionSubmissionOperationKey(
                    request.identity(), request.operationKey());
                if (resumeExistingOperation(record, request.identity(), scopedOperation,
                    receipt -> AgentRequestFingerprint.requireSameCompactionRequest(receipt, request),
                    beforeSchedule)) {
                    return record.handle;
                }

                record.scope = openScope(record);
                AgentRequestRuntime runtime = resolveRuntime(AgentRuntimeRequest.Kind.COMPACTION, request.identity(), request.modelOverride(),
                    request.contextAttributes(), Optional.empty(), record.scope);
                record.runtime = runtime;
                AgentRequestFingerprint.PreparedCompactionRequest prepared = AgentRequestFingerprint.prepareCompaction(
                    request, runtime.modelConfiguration(), runtime.bindingFingerprint());
                AgentExecutionRequest executionRequest = new AgentExecutionRequest(request.identity(),
                    AgentExecutionRequest.AdmissionKind.COMPACTION, Optional.empty(), null, Optional.empty(),
                    prepared.operation(), request.contextAttributes(), request.history(), request.acceptedAt(),
                    List.of(), Optional.empty(), Optional.empty());
                admitAndSchedule(record, executionRequest, runtime, scopedOperation,
                    saved -> AgentRequestFingerprint.requireSameCompactionRequest(saved, request), beforeSchedule);
                return record.handle;
            }
        } catch (RuntimeException | Error failure) {
            abortSetup(record, failure);
            throw failure;
        }
    }

    /**
     * 先于 submit/resume 注册瞬时流，确保首个文本片段不因调度竞争而丢失。
     */
    public AgentStreamSubscription subscribeStream(AgentIdentity identity, int capacity) {
        Objects.requireNonNull(identity, "identity");
        ensureOpen();
        return streamRegistry.subscribe(identity, capacity);
    }

    /**
     * 按租户身份恢复执行；重新解析的运行时由 Engine 持有至服务明确接受恢复阶段。
     */
    @SuppressWarnings("PMD.CloseResource")
    public AgentExecutionHandle resume(AgentIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        ensureOpen();
        HandleRegistration registration = registerHandle(identity, null, Mode.RESUME);
        ExecutionRecord record = registration.record();
        if (!registration.fresh()) {
            return record.handle;
        }
        try {
            synchronized (record.setupLock) {
                ensureOpen();
                record.reserve(scheduler.reserve(threadKey(identity)));
                AgentStore.ExecutionSnapshot snapshot = loadExecution(identity);
                record.scope = openScope(record);
                restoreForResume(record, identity, snapshot, false);
                scheduleResume(record);
                return record.handle;
            }
        } catch (RuntimeException | Error failure) {
            abortSetup(record, failure);
            throw failure;
        }
    }

    /**
     * 只读持久执行状态，所有查询键均从租户、会话和执行身份派生。
     */
    public Optional<AgentExecutionStatus> query(AgentIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        ensureOpen();
        return service.query(identity).map(status -> {
            if (!status.identity().equals(identity)) {
                throw new IllegalStateException("execution query returned a different identity");
            }
            return status;
        });
    }

    /**
     * 将便捷追加映射为一个稳定的 STEERING 队列命令，不建立第二种输入存储语义。
     */
    public CompletionStage<Void> appendInput(AgentIdentity identity, AgentInput input) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(input, "input");
        String inputId = AgentRequestFingerprint.queuedInputId(identity, input.operationKey());
        return mutateInput(identity, new AgentInputCommand.Enqueue(input.operationKey(), inputId,
            AgentInputCommand.Kind.STEERING, input.content(), Map.of())).thenApply(ignored -> null);
    }

    /**
     * 读取可从持久命令重放的通用输入队列，不通过 Ja 投影推测待处理内容。
     */
    public AgentInputQueue inspectInputQueue(AgentIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        ensureOpen();
        return service.inspectInputQueue(identity);
    }

    /**
     * 先持久化暂停请求，再中断本地活动 owner 并等待其回到可恢复边界；仅写入请求不能作为暂停成功回执。
     */
    public CompletionStage<Void> suspend(AgentIdentity identity, String operationKey, String reasonCode) {
        Objects.requireNonNull(identity, "identity");
        ensureOpen();
        AgentStore.OperationIdentity operation = AgentRequestFingerprint.prepareSuspend(
            identity, operationKey, reasonCode);
        return runControlOperation(identity, key -> service.suspend(key, operation, reasonCode),
            (key, receipt) -> {
                if (receipt.newlyCommitted()) {
                    publishControlReceipt(key, receipt);
                }
            }).thenCompose(ignored -> awaitSuspensionSettlement(identity));
    }

    /**
     * 本地 owner 必须先释放模型或工具调用并提交挂起结果；没有本地 owner 时只接受 Store 已安全停下的状态。
     */
    private CompletionStage<Void> awaitSuspensionSettlement(AgentIdentity identity) {
        ExecutionRecord record = localHandles.get(identity);
        if (record == null) {
            try {
                AgentExecutionStatus status = service.query(identity).orElseThrow(() ->
                        new IllegalStateException("execution status is unavailable after suspend request"));
                return settledState(status.state()) ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(new IllegalStateException(
                                "suspend request has no local owner to settle the running execution"));
            } catch (RuntimeException | Error failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
        if (record.completion.isDone()) {
            return requireSettledOutcome(record.completion);
        }
        if (record.scope == null || record.scopeThreadKey == null || record.scopeTurnKey == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "active execution is missing its cancellation scope"));
        }
        return cancellations.cancel(record.scopeThreadKey, record.scopeTurnKey, "plan_pause")
                .thenCompose(ignored -> requireSettledOutcome(record.completion));
    }

    /**
     * 仅挂起/等待或已提交终态可确认没有剩余 Provider 派发；未知状态继续 fail closed。
     */
    private static boolean settledState(String stateName) {
        TurnState state;
        try {
            state = TurnState.valueOf(stateName);
        } catch (IllegalArgumentException invalidState) {
            return false;
        }
        return state == TurnState.SUSPENDED || state == TurnState.WAITING_INPUT
                || state == TurnState.WAITING_APPROVAL || state.terminal();
    }

    /**
     * 只接受明确的挂起或终态结果，避免将取消清理完成误报为暂停完成。
     */
    private static CompletionStage<Void> requireSettledOutcome(
            CompletionStage<AgentExecutionOutcome> completion) {
        return completion.thenApply(outcome -> {
            if (outcome instanceof AgentExecutionOutcome.Suspended
                || outcome instanceof AgentExecutionOutcome.Completed) {
                return null;
            }
            throw new IllegalStateException("execution ended without a settled suspend boundary");
        });
    }

    /**
     * 只记录显式授权的工具结果裁决，实际 retry 或 skip 由唯一 Loop 在安全点执行。
     */
    public CompletionStage<Void> resolveToolRecovery(AgentIdentity identity, String operationKey,
                                                     String recoveryId, String callId, long expectedRecoveryRevision,
                                                     RecoveryCommand.Decision decision, String authorizationId) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(decision, "decision");
        AgentStore.OperationIdentity operation = AgentRequestFingerprint.prepareToolRecovery(identity, operationKey,
            recoveryId, callId, expectedRecoveryRevision, decision, authorizationId);
        return runControlOperation(identity, key -> service.resolveToolRecovery(key, operation,
                recoveryId, callId, expectedRecoveryRevision, decision, authorizationId),
            (key, receipt) -> {
                if (receipt.newlyCommitted()) {
                    publishControlReceipt(key, receipt);
                }
            }).thenApply(ignored -> null);
    }

    /**
     * 将宿主输入编辑命令送入唯一 reducer，并返回提交事实分配的队列版本。
     */
    public CompletionStage<AgentInputMutationReceipt> mutateInput(AgentIdentity identity,
                                                                  AgentInputCommand command) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(command, "command");
        ensureOpen();
        AgentRequestFingerprint.PreparedQueueMutation prepared =
            AgentRequestFingerprint.prepareQueueMutation(identity, command);
        AgentInputMutation mutation = toInputMutation(command);
        return runControlOperation(identity, key -> service.mutateInput(key, prepared.operation(), mutation),
            (key, result) -> {
                if (result.commit().newlyCommitted()) {
                    publishControlReceipt(key, result.commit());
                }
            }).thenApply(result -> new AgentInputMutationReceipt(result.inputId(), result.queueRevision()));
    }

    /**
     * 通过 Store 的等待事务原子结算已持久化的审批或澄清请求。
     */
    public CompletionStage<Void> answer(AgentIdentity identity, AgentAnswer answer) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(answer, "answer");
        ensureOpen();
        AgentStore.OperationIdentity operationIdentity = AgentRequestFingerprint.prepareAnswer(identity, answer);
        return runControlOperation(identity, key -> service.answer(key, operationIdentity,
                answer.interactionId(), answer.expectedInteractionRevision(), answer.answers(), answer.hostMetadata(),
                this::publishControlEvent),
            (key, receipt) -> {
                if (receipt.newlyCommitted()) {
                    publishControlReceipt(key, receipt);
                }
            }).thenApply(ignored -> null);
    }

    /**
     * 仅关闭指定版本的等待，不生成伪答案或自动恢复；状态只允许取消或替代。
     */
    public CompletionStage<Void> closeWait(AgentIdentity identity, String interactionId, long expectedInteractionRevision,
                                           InteractionStatus nextStatus, String operationKey, Map<String, String> hostMetadata) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(nextStatus, "nextStatus");
        Map<String, String> metadata = Map.copyOf(Objects.requireNonNull(hostMetadata, "hostMetadata"));
        AgentStore.OperationIdentity operation = AgentRequestFingerprint.prepareWaitClose(identity, operationKey,
            interactionId, expectedInteractionRevision, nextStatus, metadata);
        ensureOpen();
        return runControlOperation(identity, key -> service.closeWait(key, operation, interactionId,
            expectedInteractionRevision, nextStatus, metadata), (key, receipt) -> {
            if (receipt.newlyCommitted()) {
                publishControlReceipt(key, receipt);
            }
        })
            .thenApply(ignored -> null);
    }

    /**
     * 临时取消作用域在异步持久回执后关闭；已有执行作用域仍由对应执行记录持有。
     */
    @SuppressWarnings("PMD.CloseResource")
    public CompletionStage<Void> cancel(AgentIdentity identity, String operationKey, String reason) {
        Objects.requireNonNull(identity, "identity");
        ensureOpen();
        Objects.requireNonNull(reason, "reason");
        ExecutionRecord localRecord = localHandles.get(identity);
        CancellationCoordinator.CancellationScope scope = localRecord != null && localRecord.scope != null
            ? localRecord.scope : activeScopes.get(identity);
        boolean temporaryScope = scope == null;
        String threadKey = cancellationThreadKey(identity);
        String turnKey = "cancel_" + UUID.randomUUID().toString().replace("-", "");
        try {
            if (temporaryScope) {
                scope = cancellations.open(threadKey, turnKey);
            }
            AgentStore.OperationIdentity operation = AgentRequestFingerprint.prepareCancellation(
                identity, operationKey, reason);
            CancellationDispatch dispatch = cancelOnce(identity, localRecord, scope, operation, reason);
            CancellationCoordinator.CancellationScope selectedScope = scope;
            CompletableFuture<Void> result = new CompletableFuture<>();
            dispatch.stage().whenComplete((receipt, failure) -> {
                try {
                    if (failure != null) {
                        result.completeExceptionally(unwrap(failure));
                    } else {
                        result.complete(null);
                    }
                } catch (RuntimeException observerFailure) {
                    LOGGER.log(System.Logger.Level.WARNING, "Post-cancel event dispatch failed: {0}",
                        observerFailure.toString());
                    if (failure == null) {
                        result.complete(null);
                    }
                } finally {
                    if (temporaryScope) {
                        completeScope(identity, threadKey, turnKey, selectedScope);
                    }
                }
            });
            return result;
        } catch (RuntimeException | Error failure) {
            if (temporaryScope && scope != null) {
                completeScope(identity, threadKey, turnKey, scope);
            }
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * 分页读取有界持久事件；返回游标按会话推进，正文只包含当前执行的事件。
     */
    public AgentEventPage readEvents(AgentIdentity identity, long afterSequence, int limit) {
        Objects.requireNonNull(identity, "identity");
        ensureOpen();
        return eventReader.read(identity, afterSequence, limit);
    }

    /**
     * 以水位建立等待句柄；事件事实仍须从 Store 分页读取。
     */
    public AgentEventWatch watchEvents(AgentIdentity identity, long afterSequence) {
        Objects.requireNonNull(identity, "identity");
        ensureOpen();
        return eventWatches.watch(identity, afterSequence);
    }

    /**
     * 同步关闭 Engine，并等待所有仍被 Engine 或执行服务持有的资源退出。
     */
    @Override
    public void close() {
        awaitClosed();
    }

    /**
     * 开始一次可等待的异步关闭；并发关闭调用共享同一个完成阶段。
     */
    public CompletionStage<Void> closeAsync() {
        List<ExecutionRecord> records;
        synchronized (handlesLock) {
            if (!closeStarted.compareAndSet(false, true)) {
                return closeCompletion.minimalCompletionStage();
            }
            closed.set(true);
            records = List.copyOf(localHandles.values());
        }
        try {
            Thread.ofVirtual().name("ja-agent-engine-close").start(() -> finishClose(records));
        } catch (RuntimeException | Error dispatchFailure) {
            finishClose(records, dispatchFailure);
        }
        return closeCompletion.minimalCompletionStage();
    }

    /**
     * 让宿主可设定自己的等待窗口；超时不会中断仍负责释放资源的关闭线程。
     */
    @SuppressWarnings("PMD.PreserveStackTrace") // Completion/Execution 包装被拆开后，原 RuntimeException/Error 原样重抛。
    public void awaitClosed(java.time.Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout cannot be negative");
        }
        closeAsync();
        try {
            if (timeout.isZero()) {
                closeCompletion.get();
            } else {
                closeCompletion.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting AgentEngine shutdown", interrupted);
        } catch (TimeoutException timeoutFailure) {
            throw new IllegalStateException("AgentEngine shutdown is still in progress", timeoutFailure);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("AgentEngine shutdown failed", cause);
        }
    }

    /**
     * 无等待限时地等待关闭完成，供需要确认资源已释放的宿主生命周期钩子使用。
     */
    public void awaitClosed() {
        awaitClosed(java.time.Duration.ZERO);
    }

    /**
     * 关闭调度和订阅后收敛每个句柄，再按所有权边界释放自建资源。
     */
    private void finishClose(List<ExecutionRecord> records) {
        finishClose(records, null);
    }

    /**
     * 即使清理在快照后已完成，也等待其结算阶段并保留失败；终态不能替代资源关闭结果。
     */
    private void finishClose(List<ExecutionRecord> records, Throwable initialFailure) {
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        if (initialFailure != null) {
            failures.add(initialFailure);
        }
        List<CompletableFuture<?>> pendingCleanup = new ArrayList<>();
        List<CompletableFuture<?>> activeExecutions = new ArrayList<>();
        closeOwners(failures, scheduler, eventWatches, streamRegistry);
        for (ExecutionRecord record : records) {
            boolean cancelBeforeDispatch = false;
            boolean cancelAndAwait = false;
            synchronized (record.setupLock) {
                if (record.dispatchState == DispatchState.CLEANING || record.cleanupStarted.get()) {
                    pendingCleanup.add(record.cleanupCompletion.handle((ignored, failure) -> {
                        if (failure != null) {
                            failures.add(unwrap(failure));
                        }
                        return null;
                    }));
                }
                if (record.dispatchState == DispatchState.TRANSFERRED
                    || record.dispatchState == DispatchState.FINISHED) {
                    activeExecutions.add(record.completion.handle((ignored, failure) -> null));
                    cancelAndAwait = record.dispatchState == DispatchState.TRANSFERRED;
                } else if (record.dispatchState != DispatchState.CLEANING) {
                    record.dispatchState = DispatchState.CLEANING;
                    cancelBeforeDispatch = true;
                }
            }
            if (cancelAndAwait) {
                pendingCleanup.add(cancelForShutdown(record, failures));
            } else if (cancelBeforeDispatch) {
                CompletionStage<?> cleanup = record.admittedHere
                    ? abandonAdmittedBeforeExecute(record,
                    new RejectedExecutionException("agent engine closed before execution started"))
                    : cancelResumeBeforeDispatch(record,
                    new RejectedExecutionException("agent engine closed before recovery started"));
                pendingCleanup.add(cleanup.toCompletableFuture().handle((ignored, failure) -> {
                    if (failure != null) {
                        failures.add(unwrap(failure));
                    }
                    return null;
                }));
            }
        }
        awaitAll(pendingCleanup, failures);
        try {
            service.close();
        } catch (RuntimeException | Error failure) {
            failures.add(failure);
        }
        awaitAll(activeExecutions, failures);
        closeOwnedResources(failures);
        if (failures.isEmpty()) {
            closeCompletion.complete(null);
        } else {
            closeCompletion.completeExceptionally(shutdownFailure(failures));
        }
    }

    /**
     * 在指定安全点解析新的不可变运行时；单次租约的释放策略由解析器返回的运行时负责。
     */
    private AgentRequestRuntime resolveRuntime(AgentRuntimeRequest.Kind kind, AgentIdentity identity,
                                               Optional<io.github.kongweiguang.ja.agent.application.gateway.ModelPort.ModelConfiguration> modelOverride,
                                               Map<String, String> attributes, Optional<AgentStore.ExecutionSnapshot> recovered,
                                               CancellationToken cancellationToken) {
        return resolveRuntime(kind, identity, modelOverride, attributes, recovered, cancellationToken,
            Optional.empty());
    }

    /**
     * 把恢复选择交给宿主解析器并在边界再次核对，确保自定义 resolver 也不能悄然换模型。
     */
    private AgentRequestRuntime resolveRuntime(AgentRuntimeRequest.Kind kind, AgentIdentity identity,
                                               Optional<io.github.kongweiguang.ja.agent.application.gateway.ModelPort.ModelConfiguration> modelOverride,
                                               Map<String, String> attributes, Optional<AgentStore.ExecutionSnapshot> recovered,
                                               CancellationToken cancellationToken, Optional<ModelSelection> requiredSelection) {
        AgentRuntimeRequest request = new AgentRuntimeRequest(kind, identity, modelOverride, attributes,
            cancellationToken, requiredSelection);
        AgentRequestRuntime runtime = Objects.requireNonNull(runtimeResolver.resolve(request, baseExtensions, recovered),
            "resolved runtime");
        if (requiredSelection.isPresent() && !requiredSelection.orElseThrow().matches(runtime.modelConfiguration())) {
            IllegalStateException mismatch = new IllegalStateException("recovered_model_selection_mismatch");
            closeRuntime(runtime, mismatch);
            throw mismatch;
        }
        return runtime;
    }

    /**
     * 从 Store 绑定解析的租约交给句柄持有，直至接受恢复或清理路径显式关闭。
     */
    @SuppressWarnings("PMD.CloseResource")
    private void restoreForResume(ExecutionRecord record, AgentIdentity identity,
                                  AgentStore.ExecutionSnapshot snapshot, boolean cancelOnAbandon) {
        AgentRequestBinding binding = bindingCodec.read(store, snapshot, identity);
        AgentRequestRuntime runtime = resolveBinding(snapshot, record.scope, binding);
        configureResume(record, runtime, binding, cancelOnAbandon);
    }

    /**
     * 将一个已经从权威 Store 解码的绑定交给运行时扩展解析器，并再次验证模型选择。
     */
    private AgentRequestRuntime resolveBinding(AgentStore.ExecutionSnapshot snapshot,
                                               CancellationToken cancellationToken, AgentRequestBinding binding) {
        AgentRuntimeRequest request = binding.runtimeRequest(cancellationToken);
        AgentRequestRuntime runtime = Objects.requireNonNull(runtimeResolver.resolve(request, baseExtensions,
            Optional.of(snapshot)), "resolved runtime");
        if (!binding.modelSelection().matches(runtime.modelConfiguration())) {
            IllegalStateException mismatch = new IllegalStateException("recovered_model_selection_mismatch");
            closeRuntime(runtime, mismatch);
            throw mismatch;
        }
        return runtime;
    }

    /**
     * 使用完整租户、会话、执行三元组恢复，拒绝跨租户引用。
     */
    private AgentStore.ExecutionSnapshot loadExecution(AgentIdentity identity) {
        AgentStore.ExecutionKey key = new AgentStore.ExecutionKey(
            new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), identity.userId()), identity.turnId());
        return store.loadExecution(key).orElseThrow(() -> new IllegalArgumentException("execution does not exist"));
    }

    /**
     * 重复 Store 回执在模型解析前接回原执行，避免相同幂等请求重新准入。
     */
    private boolean resumeExistingOperation(ExecutionRecord record, AgentIdentity identity, String operationKey,
                                            Consumer<AgentStore.OperationReceipt> validate,
                                            Consumer<AgentAdmissionReceipt> beforeSchedule) {
        Optional<AgentStore.OperationReceipt> saved = store.readReceipt(
            new AgentStore.OperationKey(identity.tenantId(), identity.userId(), operationKey));
        if (saved.isEmpty()) {
            return false;
        }
        resumeFromOperationReceipt(record, identity, saved.orElseThrow(), validate, beforeSchedule);
        return true;
    }

    /**
     * 并发准入输给已提交操作时，要求 Store 回执存在后回接该执行。
     */
    private void resumeAdmittedOperation(ExecutionRecord record, AgentIdentity identity, String operationKey,
                                         Consumer<AgentStore.OperationReceipt> validate,
                                         Consumer<AgentAdmissionReceipt> beforeSchedule) {
        AgentStore.OperationReceipt saved = store.readReceipt(
            new AgentStore.OperationKey(identity.tenantId(), identity.userId(), operationKey)).orElseThrow(
            () -> new IllegalStateException("admission was not new but has no operation receipt"));
        resumeFromOperationReceipt(record, identity, saved, validate, beforeSchedule);
    }

    /**
     * 先验证原请求并释放本次租约；释放失败时停止恢复，避免遗留资源与新租约并存。
     */
    private void resumeFromOperationReceipt(ExecutionRecord record, AgentIdentity identity,
                                            AgentStore.OperationReceipt saved,
                                            Consumer<AgentStore.OperationReceipt> validate,
                                            Consumer<AgentAdmissionReceipt> beforeSchedule) {
        validate.accept(saved);
        completeAdmissionReceipt(record, new AgentAdmissionReceipt(saved.threadRevision(), saved.mutationVersion(),
            saved.firstEventSequence(), saved.lastEventSequence()), beforeSchedule);
        releaseRuntime(record, null);
        completeScope(record);
        AgentStore.ExecutionSnapshot snapshot = loadExecution(identity);
        record.scope = openScope(record);
        restoreForResume(record, identity, snapshot, false);
        scheduleResume(record);
    }

    /**
     * 三类新 execution 共用事务准入与回执重放边界，避免各入口漂移所有权顺序。
     */
    private void admitAndSchedule(ExecutionRecord record, AgentExecutionRequest request,
                                  AgentRequestRuntime runtime, String scopedOperation,
                                  Consumer<AgentStore.OperationReceipt> validateReplay,
                                  Consumer<AgentAdmissionReceipt> beforeSchedule) {
        record.executionRequest = Objects.requireNonNull(request, "request");
        record.operation.set(request.operation());
        AgentStore.RecordWrite binding = bindingCodec.encode(request, runtime);
        AgentStore.AdmissionReceipt receipt = service.admit(request, runtime, binding);
        record.storeAdmissionReceipt = receipt;
        if (!receipt.newlyAdmitted()) {
            resumeAdmittedOperation(record, request.identity(), scopedOperation, validateReplay, beforeSchedule);
            return;
        }
        record.admittedHere = true;
        completeAdmissionReceipt(record, new AgentAdmissionReceipt(receipt.threadRevision(), receipt.mutationVersion(),
            receipt.firstEventSequence(), receipt.lastEventSequence()), beforeSchedule);
        publishReceipt(request.identity(), receipt.firstEventSequence(), receipt.lastEventSequence(),
            runtime.extensions());
        scheduleExecute(record);
    }

    /**
     * 固定已提交的原始水位并在任何执行或恢复排队前交给同步观察者；观察器失败时不回滚准入事实。
     * 若观察器失败，外层清理会结束本地句柄并释放队列与租约；Store 准入仍已提交但尚未启动，可由同操作重放恢复。
     */
    private static void completeAdmissionReceipt(ExecutionRecord record, AgentAdmissionReceipt receipt,
                                                 Consumer<AgentAdmissionReceipt> beforeSchedule) {
        if (!record.admissionReceiptStage.complete(Optional.of(Objects.requireNonNull(receipt, "receipt")))) {
            throw new IllegalStateException("admission receipt was already completed");
        }
        beforeSchedule.accept(receipt);
    }

    /**
     * 同进程重复身份只复用已注册句柄；现有水位立即同步交付，等待中的水位由句柄阶段观察。
     */
    private static void deliverExistingAdmissionReceipt(ExecutionRecord record,
                                                        Consumer<AgentAdmissionReceipt> beforeSchedule) {
        Optional<AgentAdmissionReceipt> receipt;
        try {
            receipt = record.admissionReceiptStage.getNow(null);
        } catch (CompletionException ignored) {
            return;
        }
        if (receipt != null && receipt.isPresent()) {
            beforeSchedule.accept(receipt.orElseThrow());
        }
    }

    /**
     * 预留本地句柄身份，让同进程重试附着已有句柄而不是并发争抢准入。
     */
    private HandleRegistration registerHandle(AgentIdentity identity, String fingerprint, Mode mode) {
        synchronized (handlesLock) {
            ensureOpen();
            ExecutionRecord existing = localHandles.get(identity);
            if (existing != null) {
                if (mode == Mode.SUBMIT && existing.mode == Mode.SUBMIT) {
                    if (!Objects.equals(existing.requestFingerprint, fingerprint)) {
                        throw new IllegalArgumentException("operation key was already used for a different request");
                    }
                    return new HandleRegistration(existing, false);
                }
                if (mode == Mode.RESUME && existing.mode == Mode.RESUME) {
                    return new HandleRegistration(existing, false);
                }
                throw new IllegalStateException("execution already has a local handle");
            }
            ExecutionRecord created = new ExecutionRecord(identity, fingerprint, mode);
            localHandles.put(identity, created);
            return new HandleRegistration(created, true);
        }
    }

    /**
     * 首次标记属于单次注册结果，不能写在多个调用共享的执行句柄上。
     *
     * @param record 记录
     * @param fresh  是否为本次新建
     */
    private record HandleRegistration(ExecutionRecord record,
                                      boolean fresh) {
    }

    /**
     * 将恢复运行时和请求上下文绑定到新句柄，不重复创建准入事实。
     */
    private void configureResume(ExecutionRecord record, AgentRequestRuntime runtime,
                                 AgentRequestBinding binding, boolean cancelOnAbandon) {
        Objects.requireNonNull(record.scope, "resume cancellation scope must be open before runtime resolution");
        record.runtime = runtime;
        record.runtimeReleased.set(false);
        record.executionBinding = Objects.requireNonNull(binding, "binding").executionBinding();
        record.cancelOnAbandon = cancelOnAbandon;
    }

    /**
     * 只有 Store 提交准入后才入队；排队期间固定同一模型和 Prompt 租约。
     */
    private void scheduleExecute(ExecutionRecord record) {
        try {
            CompletionStage<Void> queued = record.reservation().start(() -> executeAccepted(record));
            queued.whenComplete((ignored, failure) -> {
                if (failure != null && !record.transferred.get()) {
                    abandonAdmittedBeforeExecute(record, unwrap(failure));
                }
            });
        } catch (RuntimeException | Error failure) {
            abandonAdmittedBeforeExecute(record, failure);
        }
    }

    /**
     * 调用唯一循环服务；有效阶段返回前租约仍归 Engine，之后由服务负责关闭。
     */
    @SuppressWarnings("PMD.CloseResource")
    private CompletionStage<?> executeAccepted(ExecutionRecord record) {
        AgentRequestRuntime runtime;
        CompletionStage<AgentExecutionOutcome> execution = null;
        Throwable dispatchFailure = null;
        try {
            synchronized (record.setupLock) {
                if (record.dispatchState == DispatchState.CLEANING
                    || record.dispatchState == DispatchState.FINISHED) {
                    return record.cleanupCompletion;
                }
                if (record.dispatchState != DispatchState.PREPARED) {
                    throw new IllegalStateException("execution dispatch already started");
                }
                record.dispatchState = DispatchState.STARTING;
                runtime = Objects.requireNonNull(record.runtime, "queued runtime");
                try {
                    execution = Objects.requireNonNull(service.execute(record.executionRequest,
                            record.storeAdmissionReceipt, runtime, record.scope,
                            event -> publishEvent(event, runtime.extensions()), streamRegistry.sink(record.identity)),
                        "execute stage");
                    record.dispatchState = DispatchState.TRANSFERRED;
                    record.transferred.set(true);
                } catch (RuntimeException | Error failure) {
                    record.dispatchState = DispatchState.CLEANING;
                    dispatchFailure = failure;
                }
            }
        } catch (RuntimeException | Error failure) {
            dispatchFailure = failure;
        }
        if (dispatchFailure != null) {
            return abandonAdmittedBeforeExecute(record, dispatchFailure);
        }
        CompletionStage<AgentExecutionOutcome> accepted = Objects.requireNonNull(execution, "accepted execution stage");
        accepted.whenComplete((result, failure) -> completeRecord(record, result, failure));
        return accepted;
    }

    /**
     * 调度恢复执行，不重放原用户输入或已发生的工具副作用。
     */
    private void scheduleResume(ExecutionRecord record) {
        try {
            CompletionStage<Void> queued = record.reservation().start(() -> resumeAccepted(record));
            queued.whenComplete((ignored, failure) -> {
                if (failure != null && !record.transferred.get()) {
                    failBeforeResume(record, unwrap(failure));
                }
            });
        } catch (RuntimeException | Error failure) {
            failBeforeResume(record, failure);
        }
    }

    /**
     * 仅在 Core 接受恢复阶段后转移租约，失败时由 Engine 恢复本地清理责任。
     */
    @SuppressWarnings("PMD.CloseResource")
    private CompletionStage<?> resumeAccepted(ExecutionRecord record) {
        AgentRequestRuntime runtime;
        CompletionStage<AgentExecutionOutcome> execution = null;
        Throwable dispatchFailure = null;
        try {
            synchronized (record.setupLock) {
                if (record.dispatchState == DispatchState.CLEANING
                    || record.dispatchState == DispatchState.FINISHED) {
                    return record.cleanupCompletion;
                }
                if (record.dispatchState != DispatchState.PREPARED) {
                    throw new IllegalStateException("recovery dispatch already started");
                }
                record.dispatchState = DispatchState.STARTING;
                runtime = Objects.requireNonNull(record.runtime, "recovery runtime");
                try {
                    execution = Objects.requireNonNull(service.resume(record.executionBinding, runtime, record.scope,
                            event -> publishEvent(event, runtime.extensions()), streamRegistry.sink(record.identity)),
                        "resume stage");
                    record.dispatchState = DispatchState.TRANSFERRED;
                    record.transferred.set(true);
                } catch (RuntimeException | Error failure) {
                    record.dispatchState = DispatchState.CLEANING;
                    dispatchFailure = failure;
                }
            }
        } catch (RuntimeException | Error failure) {
            dispatchFailure = failure;
        }
        if (dispatchFailure != null) {
            return failBeforeResume(record, dispatchFailure);
        }
        CompletionStage<AgentExecutionOutcome> accepted = Objects.requireNonNull(execution, "accepted recovery stage");
        accepted.whenComplete((result, failure) -> completeRecord(record, result, failure));
        return accepted;
    }

    /**
     * 失败派发路径读取记录借用的租约并负责关闭，不能因清理与关闭竞争而提前释放。
     */
    @SuppressWarnings("PMD.CloseResource")
    private CompletionStage<?> abandonAdmittedBeforeExecute(ExecutionRecord record, Throwable cause) {
        AgentRequestRuntime runtime;
        CancellationCoordinator.CancellationScope scope;
        synchronized (record.setupLock) {
            if (record.dispatchState == DispatchState.TRANSFERRED
                || record.dispatchState == DispatchState.FINISHED) {
                return record.completion;
            }
            if (!record.cleanupStarted.compareAndSet(false, true)) {
                return record.cleanupCompletion;
            }
            record.dispatchState = DispatchState.CLEANING;
            runtime = record.runtime;
            scope = record.scope;
        }
        if (!record.admittedHere || !record.cancelOnAbandon || runtime == null || scope == null) {
            releaseRuntime(record, cause);
            completeRecord(record, null, cause);
            record.cleanupCompletion.completeExceptionally(cause);
            return record.cleanupCompletion;
        }
        AgentStore.OperationIdentity admittedOperation = Objects.requireNonNull(record.operation.get(),
            "admitted operation must be prepared before dispatch cleanup");
        AgentStore.OperationIdentity operation = cancellationForAbandon(record.identity, admittedOperation);
        CompletionStage<AgentStore.CommitReceipt> persisted;
        try {
            CancellationDispatch dispatch = cancelOnce(record.identity, record, scope, operation,
                "agent engine could not dispatch the admitted execution");
            persisted = dispatch.stage();
        } catch (RuntimeException | Error cancellationFailure) {
            if (cause != cancellationFailure) {
                cause.addSuppressed(cancellationFailure);
            }
            releaseRuntime(record, cause);
            completeRecord(record, null, cause);
            record.cleanupCompletion.completeExceptionally(cause);
            return record.cleanupCompletion;
        }
        persisted.whenComplete((receipt, cancellationFailure) -> {
            if (cancellationFailure != null) {
                Throwable unwrapped = unwrap(cancellationFailure);
                if (cause != unwrapped) {
                    cause.addSuppressed(unwrapped);
                }
            }
            boolean runtimeReleased = releaseRuntime(record, cause);
            completeRecord(record, null, cause);
            if (cancellationFailure == null && runtimeReleased) {
                record.cleanupCompletion.complete(null);
            } else {
                record.cleanupCompletion.completeExceptionally(cause);
            }
        });
        return record.cleanupCompletion;
    }

    /**
     * Core 接受恢复前若恢复失败，保持原有可恢复执行不变。
     */
    private CompletionStage<?> failBeforeResume(ExecutionRecord record, Throwable failure) {
        synchronized (record.setupLock) {
            if (record.dispatchState == DispatchState.TRANSFERRED
                || record.dispatchState == DispatchState.FINISHED) {
                return record.completion;
            }
            if (!record.cleanupStarted.compareAndSet(false, true)) {
                return record.cleanupCompletion;
            }
            record.dispatchState = DispatchState.CLEANING;
        }
        releaseRuntime(record, failure);
        completeRecord(record, null, failure);
        record.cleanupCompletion.completeExceptionally(failure);
        return record.cleanupCompletion;
    }

    /**
     * 对同一执行只提交一份取消身份，防止关机与调用方取消互相覆盖回执。
     */
    private CancellationDispatch cancelOnce(AgentIdentity identity, ExecutionRecord record,
                                            CancellationCoordinator.CancellationScope scope, AgentStore.OperationIdentity operation,
                                            String reason) {
        if (record == null) {
            CompletionStage<AgentStore.CommitReceipt> stage = Objects.requireNonNull(
                service.cancel(identity, scope, operation, reason, this::publishControlEvent), "cancel stage");
            return new CancellationDispatch(stage);
        }
        synchronized (record.setupLock) {
            if (record.cancellationStage != null) {
                return new CancellationDispatch(record.cancellationStage);
            }
            CompletionStage<AgentStore.CommitReceipt> stage = Objects.requireNonNull(
                service.cancel(record.identity, scope, operation, reason, this::publishControlEvent), "cancel stage");
            record.cancellationStage = stage;
            return new CancellationDispatch(stage);
        }
    }

    /**
     * 关闭时借用执行记录的作用域持久化取消，作用域仍由完成记录的路径关闭。
     */
    @SuppressWarnings("PMD.CloseResource")
    private CompletableFuture<Void> cancelForShutdown(ExecutionRecord record, List<Throwable> failures) {
        CancellationCoordinator.CancellationScope scope = record.scope;
        if (record.completion.isDone()) {
            return CompletableFuture.completedFuture(null);
        }
        if (scope == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "active execution is missing its cancellation scope"));
        }
        String operationKey = AgentRequestFingerprint.systemOperationKey(record.identity, "agent.engine.close");
        String reason = "agent engine is closing";
        AgentStore.OperationIdentity operation = AgentRequestFingerprint.prepareCancellation(record.identity,
            operationKey, reason);
        try {
            CancellationDispatch dispatch = cancelOnce(record.identity, record, scope, operation, reason);
            CompletableFuture<Void> done = new CompletableFuture<>();
            dispatch.stage().whenComplete((receipt, failure) -> {
                if (failure != null) {
                    Throwable rootFailure = unwrap(failure);
                    if (!terminalShutdownRace(record, rootFailure)) {
                        failures.add(rootFailure);
                    }
                    done.complete(null);
                    return;
                }
                done.complete(null);
            });
            return done;
        } catch (RuntimeException | Error failure) {
            if (!terminalShutdownRace(record, failure)) {
                failures.add(failure);
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * 终态提交可早于句柄清理；只对取消状态冲突回读终态，真实存储与释放错误仍必须报告。
     */
    private boolean terminalShutdownRace(ExecutionRecord record, Throwable failure) {
        if (!(failure instanceof IllegalArgumentException || failure instanceof AgentStore.StoreConflictException)) {
            return false;
        }
        try {
            return service.query(record.identity).filter(status -> status.identity().equals(record.identity))
                .map(status -> java.util.Set.of("COMPLETED", "FAILED", "CANCELLED").contains(status.state()))
                .orElse(false);
        } catch (RuntimeException | Error queryFailure) {
            if (failure != queryFailure) {
                failure.addSuppressed(queryFailure);
            }
            return false;
        }
    }

    /**
     * 关闭前借用恢复句柄作用域写取消事实，随后由统一句柄收敛路径释放它。
     */
    @SuppressWarnings("PMD.CloseResource")
    private CompletionStage<?> cancelResumeBeforeDispatch(ExecutionRecord record, Throwable cause) {
        if (!record.cleanupStarted.compareAndSet(false, true)) {
            return record.cleanupCompletion;
        }
        CancellationCoordinator.CancellationScope scope = record.scope;
        if (scope == null) {
            releaseRuntime(record, cause);
            completeRecord(record, null, cause);
            record.cleanupCompletion.completeExceptionally(cause);
            return record.cleanupCompletion;
        }
        AgentStore.OperationIdentity operation = AgentRequestFingerprint.prepareCancellation(record.identity,
            AgentRequestFingerprint.systemOperationKey(record.identity, "agent.engine.close.resume"),
            "agent engine is closing before recovery dispatch");
        try {
            CancellationDispatch dispatch = cancelOnce(record.identity, record, scope, operation,
                "agent engine is closing before recovery dispatch");
            dispatch.stage().whenComplete((receipt, failure) -> {
                if (failure != null) {
                    Throwable unwrapped = unwrap(failure);
                    if (cause != unwrapped) {
                        cause.addSuppressed(unwrapped);
                    }
                }
                boolean runtimeReleased = releaseRuntime(record, cause);
                completeRecord(record, null, cause);
                if (failure == null && runtimeReleased) {
                    record.cleanupCompletion.complete(null);
                } else {
                    record.cleanupCompletion.completeExceptionally(cause);
                }
            });
        } catch (RuntimeException | Error cancellationFailure) {
            if (cause != cancellationFailure) {
                cause.addSuppressed(cancellationFailure);
            }
            releaseRuntime(record, cause);
            completeRecord(record, null, cause);
            record.cleanupCompletion.completeExceptionally(cause);
        }
        return record.cleanupCompletion;
    }

    /**
     * 只分发已经提交的事件，并隔离单个监听器异常。
     */
    private void publishEvent(AgentEvent event, ExtensionRegistry.Snapshot runtimeExtensions) {
        eventWatches.onEvent(event);
        listenerDispatcher.dispatch(runtimeExtensions.listeners(), event);
    }

    /**
     * 运行不依赖模型租约的短控制事务，提交后的观察失败不能反向改变 Store 回执。
     */
    private <T> CompletionStage<T> runControlOperation(AgentIdentity identity,
                                                       Function<AgentIdentity, CompletionStage<T>> operation,
                                                       BiConsumer<AgentIdentity, T> afterCommit) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(afterCommit, "afterCommit");
        try {
            ensureOpen();
            CompletionStage<T> stage = Objects.requireNonNull(operation.apply(identity), "control operation stage");
            return stage.thenApply(value -> {
                try {
                    afterCommit.accept(identity, value);
                } catch (RuntimeException observerFailure) {
                    LOGGER.log(System.Logger.Level.WARNING, "Post-control observer failed: {0}",
                        observerFailure.toString());
                }
                return value;
            });
        } catch (RuntimeException | Error failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * 控制事务仅在提交后按 receipt 水位回读事件，运行时结束后使用显式 base listener 快照。
     */
    private void publishControlReceipt(AgentIdentity identity, AgentStore.CommitReceipt receipt) {
        ExecutionRecord record = localHandles.get(identity);
        ExtensionRegistry.Snapshot listeners = record != null && record.runtime != null
            ? record.runtime.extensions() : baseExtensions;
        publishReceipt(identity, receipt.firstEventSequence(), receipt.lastEventSequence(), listeners);
    }

    /**
     * 转发 Service 刚提交的精确事件，活动 execution 使用其短租约内冻结的监听器视图。
     */
    private void publishControlEvent(AgentEvent event) {
        Objects.requireNonNull(event, "event");
        ExecutionRecord record = localHandles.get(event.identity());
        ExtensionRegistry.Snapshot listeners = record != null && record.runtime != null
            ? record.runtime.extensions() : baseExtensions;
        publishEvent(event, listeners);
    }

    /**
     * 只回读回执标明的已提交范围；内存唤醒丢失不会改变事件事实。
     */
    private void publishReceipt(AgentIdentity identity, long firstSequence, long lastSequence,
                                ExtensionRegistry.Snapshot runtimeExtensions) {
        if (firstSequence == 0 && lastSequence == 0) {
            return;
        }
        try {
            AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(
                    identity.tenantId(), identity.threadId(), identity.userId());
            long cursor = firstSequence - 1;
            while (cursor < lastSequence) {
                AgentStore.EventPage page = store.readEvents(AgentStore.EventQuery.withDefaultMaxBytes(
                        conversation, cursor, lastSequence, 10_000));
                if (page.events().isEmpty() || page.nextSequence() <= cursor) {
                    throw new IllegalStateException("store returned no progress for a committed event range");
                }
                for (AgentStore.PersistedEvent event : page.events()) {
                    if (event.sequence() != cursor + 1) {
                        throw new IllegalStateException("store event range is not contiguous");
                    }
                    publishEvent(new AgentEvent(identity, event), runtimeExtensions);
                    cursor = event.sequence();
                }
                if (page.nextSequence() != cursor) {
                    throw new IllegalStateException("store event cursor does not match the committed range");
                }
            }
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "Committed Agent events will be recovered from the store: {0}",
                failure.toString());
        }
    }

    /**
     * 新建作用域立即登记到活动索引和执行记录；任何冲突都会在本方法内关闭。
     */
    @SuppressWarnings("PMD.CloseResource")
    private CancellationCoordinator.CancellationScope openScope(ExecutionRecord record) {
        AgentIdentity identity = record.identity;
        String threadKey = cancellationThreadKey(identity);
        String turnKey = "run_" + UUID.randomUUID().toString().replace("-", "");
        CancellationCoordinator.CancellationScope scope = cancellations.open(threadKey, turnKey);
        CancellationCoordinator.CancellationScope existing = activeScopes.putIfAbsent(identity, scope);
        if (existing != null) {
            completeScope(identity, threadKey, turnKey, scope);
            throw new IllegalStateException("execution already has an active cancellation scope");
        }
        record.scopeThreadKey = threadKey;
        record.scopeTurnKey = turnKey;
        record.scopeClosed.set(false);
        return scope;
    }

    /**
     * 操作结算后关闭取消作用域，并同步移除身份路由。
     */
    private void completeScope(ExecutionRecord record) {
        if (record.scope == null || !record.scopeClosed.compareAndSet(false, true)) {
            return;
        }
        completeScope(record.identity, record.scopeThreadKey, record.scopeTurnKey, record.scope);
    }

    /**
     * 按原协调器键完成 Engine 所有的作用域，也包含临时远程取消作用域。
     */
    private void completeScope(AgentIdentity identity, String threadKey, String turnKey,
                               CancellationCoordinator.CancellationScope scope) {
        cancellations.complete(threadKey, turnKey);
        activeScopes.remove(identity, scope);
    }

    /**
     * 将持久版本映射为公开状态，再释放 Engine 所有的引用。
     */
    private void completeRecord(ExecutionRecord record, AgentExecutionOutcome result, Throwable failure) {
        if (!record.finalized.compareAndSet(false, true)) {
            return;
        }
        if (!record.admissionReceiptStage.isDone()) {
            Throwable receiptFailure = failure != null ? unwrap(failure)
                : new IllegalStateException("execution completed before admission receipt was established");
            record.admissionReceiptStage.completeExceptionally(receiptFailure);
        }
        listenerDispatcher.finishExecution(record.identity);
        synchronized (record.setupLock) {
            record.dispatchState = DispatchState.FINISHED;
        }
        completeScope(record);
        synchronized (handlesLock) {
            localHandles.remove(record.identity, record);
        }
        if (failure == null && result != null) {
            record.completion.complete(result);
        } else if (failure != null) {
            record.completion.completeExceptionally(unwrap(failure));
        } else {
            record.completion.completeExceptionally(new IllegalStateException("core loop returned no execution outcome"));
        }
    }

    /**
     * 仅释放 Engine 仍持有的租约；失败清理附加诊断并返回结算结果，正常换租约则直接报告失败。
     */
    @SuppressWarnings("PMD.CloseResource")
    private boolean releaseRuntime(ExecutionRecord record, Throwable primary) {
        AgentRequestRuntime runtime = record.runtime;
        if (runtime != null && !record.transferred.get() && record.runtimeReleased.compareAndSet(false, true)) {
            try {
                runtime.close();
            } catch (RuntimeException | Error failure) {
                if (primary == null) {
                    throw failure;
                }
                if (primary != failure) {
                    primary.addSuppressed(failure);
                }
                return false;
            }
        }
        return true;
    }

    /**
     * 装配失败仍结算句柄、队列和取消作用域；租约关闭错误只补充原始失败，不能中断收口。
     */
    private void abortSetup(ExecutionRecord record, Throwable failure) {
        record.releaseReservation();
        releaseRuntime(record, failure);
        completeScope(record);
        record.admissionReceiptStage.completeExceptionally(failure);
        record.completion.completeExceptionally(failure);
        synchronized (handlesLock) {
            localHandles.remove(record.identity, record);
        }
    }

    /**
     * 逐项关闭 Engine 创建的协调资源并汇总错误，注入资源和 Store 仍由宿主管理。
     */
    private void closeOwnedResources(List<Throwable> failures) {
        try {
            listenerDispatcher.close();
        } catch (RuntimeException | Error failure) {
            failures.add(failure);
        }
        try {
            cancellations.close();
        } catch (RuntimeException | Error failure) {
            failures.add(failure);
        }
        if (ownedModelResources != null) {
            try {
                ownedModelResources.close();
            } catch (Exception | Error failure) {
                failures.add(failure);
            }
        }
    }

    /**
     * 关闭后拒绝新调用，避免接收无法调度或观察的任务。
     */
    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("agent engine is closed");
        }
    }

    /**
     * Engine handle 的两类入口共用生命周期，但仅恢复入口可在无新准入时执行。
     */
    private enum Mode {
        /**
         * 创建或幂等复用一次提交请求。
         */
        SUBMIT,
        /**
         * 使用持久 request binding 恢复已存在的 execution。
         */
        RESUME
    }

    /**
     * 局部派发移交状态用于串行化 close、取消与 Core 接管之间的所有权竞争。
     */
    private enum DispatchState {
        /**
         * 已注册 handle，尚未冻结本次调度与运行时。
         */
        PREPARED,
        /**
         * 持有 setupLock 调用 Service，尚未确认其接受异步 stage。
         */
        STARTING,
        /**
         * 有效 stage 已返回，运行时资源由 Service 接管。
         */
        TRANSFERRED,
        /**
         * 尚未移交的请求正在持久化取消并释放本地资源。
         */
        CLEANING,
        /**
         * handle 已完成且 scope、队列 reservation 和本地索引已收敛。
         */
        FINISHED
    }

    /**
     * 将一次取消回执与创建者标记绑定，确保并发 close 不重复广播事件。
     *
     * @param stage 阶段
     */
    private record CancellationDispatch(CompletionStage<AgentStore.CommitReceipt> stage) {
    }

    /**
     * 将队列槽位、精确运行时租约和取消身份绑定到同一个执行生命周期对象。
     */
    private final class ExecutionRecord {
        private final AgentIdentity identity;
        private final String requestFingerprint;
        private final Mode mode;
        private final Object setupLock = new Object();
        private final CompletableFuture<AgentExecutionOutcome> completion = new CompletableFuture<>();
        private final AgentExecutionHandle handle;
        private final AtomicBoolean transferred = new AtomicBoolean();
        private final AtomicBoolean runtimeReleased = new AtomicBoolean();
        private final AtomicBoolean cleanupStarted = new AtomicBoolean();
        private final AtomicBoolean finalized = new AtomicBoolean();
        private final AtomicBoolean scopeClosed = new AtomicBoolean();
        private final CompletableFuture<Void> cleanupCompletion = new CompletableFuture<>();
        private final AtomicReference<AgentStore.OperationIdentity> operation = new AtomicReference<>();
        private final AtomicReference<ExecutionScheduler.Reservation> reservation = new AtomicReference<>();
        private volatile DispatchState dispatchState = DispatchState.PREPARED;
        private volatile AgentRequestRuntime runtime;
        private volatile AgentExecutionRequest executionRequest;
        private volatile io.github.kongweiguang.ja.agent.application.loop.AgentExecutionBinding executionBinding;
        private volatile AgentStore.AdmissionReceipt storeAdmissionReceipt;
        private final CompletableFuture<Optional<AgentAdmissionReceipt>> admissionReceiptStage = new CompletableFuture<>();
        private volatile CancellationCoordinator.CancellationScope scope;
        private volatile String scopeThreadKey;
        private volatile String scopeTurnKey;
        private volatile CompletionStage<AgentStore.CommitReceipt> cancellationStage;
        private volatile boolean admittedHere;
        private volatile boolean cancelOnAbandon = true;

        /**
         * 在进入本地调度器前固定身份与请求语义。
         */
        private ExecutionRecord(AgentIdentity identity, String requestFingerprint, Mode mode) {
            this.identity = identity;
            this.requestFingerprint = requestFingerprint;
            this.mode = mode;
            if (mode == Mode.RESUME) {
                admissionReceiptStage.complete(Optional.empty());
            }
            this.handle = new AgentExecutionHandle(identity, completion, admissionReceiptStage);
        }

        /**
         * 准入阶段只允许写入一次排队位置，确保生命周期清理不会释放其他任务的槽位。
         */
        private void reserve(ExecutionScheduler.Reservation value) {
            if (!reservation.compareAndSet(null, Objects.requireNonNull(value, "reservation"))) {
                throw new IllegalStateException("execution reservation already exists");
            }
        }

        /**
         * 派发只能读取已准入预留的队列位置，缺失表示 Engine 生命周期状态损坏。
         */
        private ExecutionScheduler.Reservation reservation() {
            return Objects.requireNonNull(reservation.get(), "execution reservation was not acquired");
        }

        /**
         * 清理未派发句柄时释放其队列槽位，重复清理保持幂等。
         */
        @SuppressWarnings("PMD.CloseResource")
        private void releaseReservation() {
            ExecutionScheduler.Reservation value = reservation.getAndSet(null);
            if (value != null) {
                value.close();
            }
        }
    }
}
