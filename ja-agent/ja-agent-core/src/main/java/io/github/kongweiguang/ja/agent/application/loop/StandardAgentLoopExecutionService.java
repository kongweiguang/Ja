// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.TurnEventSink;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionOutcome;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionStatus;
import io.github.kongweiguang.ja.agent.application.cancellation.CancellationCoordinator;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.execution.ControlRequest;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.execution.SuspendConsumption;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswerSubmission;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionOption;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestion;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestionType;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionWait;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.tool.RecoveryCommand;
import io.github.kongweiguang.ja.agent.domain.tool.ToolRecoveryDecision;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.AgentEvent;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentRecordIds;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionEventCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.agent.shared.Hashing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;








import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 将 API 请求、Store 控制事务和唯一 AgentExecutionDriver 串成可恢复生命周期。
 */
public final class StandardAgentLoopExecutionService implements AgentLoopExecutionService {
    private static final int PAGE_SIZE = 256;
    private static final String WAIT_RECORD_TYPE = ExecutionControlCodec.WAIT_RECORD_TYPE;
    private static final String INPUT_COMMAND_TYPE = ExecutionControlCodec.INPUT_QUEUE_COMMAND_RECORD_TYPE;
    private static final String TOOL_RECOVERY_DECISION_TYPE = ExecutionControlCodec.TOOL_RECOVERY_RECORD_TYPE;
    private static final String CANCEL_REQUEST_TYPE = ExecutionControlCodec.CANCEL_REQUEST_RECORD_TYPE;
    private static final String SUSPEND_REQUEST_TYPE = ExecutionControlCodec.SUSPEND_REQUEST_RECORD_TYPE;
    private static final String SUSPEND_CONSUMED_TYPE = ExecutionControlCodec.SUSPEND_CONSUMED_RECORD_TYPE;

    private final AgentStore store;
    private final AgentExecutionDriver driver;
    private final AgentLoopPersistence persistence;
    private final ExecutionRecordCodec records;
    private final ExecutionControlCodec controls;
    private final ExecutionEventCodec events;
    private final Duration leaseDuration;
    private final Clock clock;
    private final ScheduledThreadPoolExecutor heartbeats;
    private final ApprovalExpiryScheduler approvalExpiries;
    private final ConcurrentHashMap<AgentIdentity, RunningExecution> running = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 取消和暂停控制遇到可重试的版本竞争时短暂让出线程；不把让步当作固定重试预算或成功依据。
     *
     * @author kongweiguang
     */
    private static void pauseBeforeControlRetry() {
        boolean interrupted = Thread.interrupted();
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Store 和 Driver 由装配层持有；租约心跳与独立审批计时 owner 由本服务创建并在关闭时销毁。
     */
    public StandardAgentLoopExecutionService(AgentStore store, AgentExecutionDriver driver,
                                             AgentLoopPersistence persistence, ExecutionRecordCodec records,
                                             ExecutionEventCodec events, ExecutionControlCodec controls, Duration leaseDuration, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.driver = Objects.requireNonNull(driver, "driver");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.records = Objects.requireNonNull(records, "records");
        this.events = Objects.requireNonNull(events, "events");
        this.controls = Objects.requireNonNull(controls, "controls");
        this.leaseDuration = positive(leaseDuration);
        this.clock = Objects.requireNonNull(clock, "clock");
        ThreadFactory threads = Thread.ofPlatform().daemon().name("ja-agent-lease-heartbeat-", 0).factory();
        heartbeats = new ScheduledThreadPoolExecutor(2, threads);
        heartbeats.setRemoveOnCancelPolicy(true);
        heartbeats.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        approvalExpiries = new ApprovalExpiryScheduler(clock, leaseDuration, 4096,
                StandardAgentLoopExecutionService::report);
    }

    /**
     * 将 Store 包装的领域容量异常还原为调用方可稳定识别的队列满类型。
     */
    private static RuntimeException queueFullCause(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AgentInputQueue.QueueFullException) {
                return new AgentInputQueue.QueueFullException();
            }
        }
        return failure;
    }

    /**
     * 校验会话、执行和租户身份以及记录水位相互一致，不从单边最新状态猜测执行位置。
     */
    private static void validateSnapshot(AgentIdentity identity, AgentStore.ConversationSnapshot conversation,
                                         AgentStore.ExecutionSnapshot execution) {
        if (!execution.key().equals(StandardAgentLoopExecutionService.execution(identity))
            || !conversation.key().equals(execution.key().conversation())
            || execution.lastRecordSequence() > conversation.recordSequence()) {
            throw corrupt("Store snapshot identity or record watermark is inconsistent");
        }
    }

    /**
     * Wait 结算只允许覆盖等待或挂起状态；Runner 正执行时由其安全点处理到期事实。
     */
    private static boolean waitingState(String state) {
        return TurnState.WAITING_APPROVAL.name().equals(state)
               || TurnState.WAITING_INPUT.name().equals(state)
               || TurnState.SUSPENDED.name().equals(state);
    }

    /**
     * 将真实等待状态迁移先于关闭事件提交；已挂起执行不产生没有变化的自环事件。
     */
    private static List<ExecutionEvent> waitSettlementEvents(ControlSnapshot current,
                                                             ExecutionEvent.ControlRecorded event) {
        List<ExecutionEvent> events = new ArrayList<>(2);
        TurnState prior = TurnState.valueOf(current.execution().state());
        if (prior != TurnState.SUSPENDED) {
            events.add(new ExecutionEvent.StateChanged(prior, TurnState.SUSPENDED));
        }
        events.add(event);
        return List.copyOf(events);
    }

    /**
     * 到期 operation 与 interactionRevision 确定绑定，跨重启可安全重放并恢复原提交回执。
     */
    private static AgentStore.OperationIdentity expiryOperation(AgentIdentity identity, InteractionWait wait) {
        return expiryOperation(identity, wait.interactionId(), wait.interactionRevision());
    }

    /**
     * 内部到期幂等键不使用时间，确保超时响应未知时不会制造第二份关闭事实。
     */
    private static AgentStore.OperationIdentity expiryOperation(AgentIdentity identity,
                                                                String interactionId, long interactionRevision) {
        String binding = interactionId + ":interaction-revision:" + interactionRevision;
        String key = AgentRecordIds.derive(execution(identity), "agent.wait.expiry.operation", binding);
        return internalOperation(key, "agent.wait.expire", identity, binding);
    }

    /**
     * 审批 wait 必须保持固定 question/option 闭集，答案不能把自由文本解释为许可。
     */
    private static boolean isApproval(InteractionWait wait) {
        return "approval".equals(textAttribute(wait.attributes(), "kind"));
    }

    /**
     * 沿用 pending wait 的 typed 身份绑定，再核验期限与选项闭集，拒绝迟到或无工具授权。
     */
    private static ApprovalDecision approvalDecision(InteractionWait wait, List<InteractionAnswer> answers,
                                                     Instant now) {
        if (wait.status() != InteractionStatus.PENDING || wait.toolCallId() == null) {
            throw new AgentStore.StoreConflictException("approval wait identity is invalid");
        }
        Instant expiresAt = approvalExpiry(wait);
        if (!expiresAt.isAfter(now)) {
            throw new AgentStore.StoreConflictException("approval wait has expired");
        }
        if (wait.questions().size() != 1) {
            throw new AgentStore.StoreConflictException("approval question set changed");
        }
        InteractionQuestion question = wait.questions().getFirst();
        Set<String> optionIds = question.options().stream().map(InteractionOption::optionId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!question.questionId().equals("question_approval") || question.type() != InteractionQuestionType.SINGLE
            || question.allowFreeText() || !optionIds.equals(Set.of("option_approve", "option_deny"))) {
            throw new AgentStore.StoreConflictException("approval question schema is invalid");
        }
        if (answers.size() != 1) {
            throw new AgentStore.StoreConflictException("approval answer count is invalid");
        }
        InteractionAnswer answer = answers.getFirst();
        if (!answer.questionId().equals("question_approval") || answer.skipped() || answer.freeText() != null
            || answer.optionIds().size() != 1) {
            throw new AgentStore.StoreConflictException("approval answer schema is invalid");
        }
        return switch (answer.optionIds().getFirst()) {
            case "option_approve" -> ApprovalDecision.APPROVE;
            case "option_deny" -> ApprovalDecision.DENY;
            default -> throw new AgentStore.StoreConflictException("approval option is invalid");
        };
    }

    /**
     * 审批有效期来自准入时冻结属性，缺失或损坏时失败关闭而不推断长期授权。
     */
    private static Instant approvalExpiry(InteractionWait wait) {
        try {
            return Instant.parse(textAttribute(wait.attributes(), "expiresAt"));
        } catch (RuntimeException failure) {
            throw new AgentStore.StoreIntegrityException("approval expiry is invalid", failure);
        }
    }

    /**
     * 只从固定 JsonText 字段读取审批元数据，拒绝数字、对象或隐式字符串化。
     */
    private static String textAttribute(JsonObject attributes, String name) {
        if (!(attributes.members().get(name) instanceof JsonText(String value1))) {
            throw new AgentStore.StoreIntegrityException("interaction attribute is missing", null);
        }
        return value1;
    }

    /**
     * 准入重复请求只复用同一 Store 回执，不重复读 Skill 或生成新的初始游标。
     */
    private static AgentStore.AdmissionReceipt admissionReplay(AgentStore.OperationReceipt saved,
                                                               AgentExecutionRequest request) {
        if (!saved.conversation().equals(conversation(request.identity()))
            || !saved.turnId().equals(request.identity().turnId())
            || !saved.fingerprint().equals(request.operation().fingerprint())
            || !saved.operationType().equals(request.operation().operationType())) {
            throw new AgentStore.StoreConflictException("admission operation key was reused with different request");
        }
        return new AgentStore.AdmissionReceipt(saved.threadRevision(), saved.mutationVersion(),
                saved.firstEventSequence(), saved.lastEventSequence(), saved.receipt(), false);
    }

    /**
     * 创建内部挂起消费幂等身份时只散列稳定控制字段，不把时间或正文加入回执。
     */
    private static AgentStore.OperationIdentity internalOperation(String key, String type,
                                                                  AgentIdentity identity, String binding) {
        String fingerprint = Hashing.sha256Hex(identity.tenantId() + "\u0000" + identity.threadId() + "\u0000"
                                    + identity.turnId() + "\u0000" + type + "\u0000" + binding);
        return new AgentStore.OperationIdentity(key, type, fingerprint,
                JsonObjects.builder().putNumber("schemaVersion", 1).build());
    }

    /**
     * 一次执行建立稳定的会话 key 与有界 owner 标识，不在日志或回执中保留凭据。
     */
    private static String ownerId() {
        return "owner_" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 规范构造 owner-scoped conversation key，避免同名 Thread 被另一用户占有或跨身份读取。
     */
    private static AgentStore.ConversationKey conversation(AgentIdentity identity) {
        return new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), identity.userId());
    }

    /**
     * 所有 Store 读写都用四元 Agent 身份映射到 tenant-scoped execution key。
     */
    private static AgentStore.ExecutionKey execution(AgentIdentity identity) {
        return new AgentStore.ExecutionKey(conversation(identity), identity.turnId());
    }

    /**
     * 以准入原 operation key 对账，租户外的同名回执不能被借用。
     */
    private static AgentStore.OperationKey operationKey(AgentExecutionRequest request) {
        return new AgentStore.OperationKey(request.identity().tenantId(), request.identity().userId(),
                request.operation().operationKey());
    }

    /**
     * 不接受零租期或负租期，租约用于执行权接管而非限制任务时长。
     */
    private static Duration positive(Duration value) {
        Objects.requireNonNull(value, "leaseDuration");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("lease duration must be positive");
        }
        return value;
    }

    /**
     * 冻结宿主元数据的值边界，确保事务参与者看到的同一请求不能被调用方并发改写。
     */
    private static Map<String, String> immutableMetadata(Map<String, String> metadata) {
        Objects.requireNonNull(metadata, "hostMetadata");
        Map<String, String> copy = new HashMap<>();
        metadata.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                throw new IllegalArgumentException("host metadata keys and values must be non-empty");
            }
            copy.put(key, value);
        });
        return Map.copyOf(copy);
    }

    /**
     * 避免服务层错误把记录损坏详情传播到未授权调用方。
     */
    private static AgentStore.StoreIntegrityException corrupt(String reason) {
        return new AgentStore.StoreIntegrityException(reason, null);
    }

    /**
     * 诊断 listener 失败只记录日志，不改变已提交的状态和回执。
     */
    private static void report(String message, Throwable failure) {
        System.getLogger(StandardAgentLoopExecutionService.class.getName()).log(
                System.Logger.Level.WARNING, message, failure);
    }

    /**
     * 去除 CompletionStage 包装以保留 Driver 的真实恢复或存储失败根因。
     */
    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 提前校验首个请求引用的 Skill，避免模型调用或用户消息准入后才发现 Skill 不可读取。
     */
    @Override
    public AgentStore.AdmissionReceipt admit(AgentExecutionRequest request, AgentRequestRuntime runtime,
                                             AgentStore.RecordWrite requestBinding) {
        requireOpen();
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(requestBinding, "requestBinding");
        Optional<AgentStore.OperationReceipt> previous = store.readReceipt(operationKey(request));
        if (previous.isPresent()) {
            return admissionReplay(previous.orElseThrow(), request);
        }
        request.input().ifPresent(input -> runtime.promptSession().replaceActiveSkills(input.skillIds()));
        AgentStore.ConversationKey conversation = conversation(request.identity());
        long expectedRevision = request.history().mode() == AgentExecutionRequest.HistorySelection.Mode.EXPLICIT
                ? Objects.requireNonNull(request.history().expectedThreadRevision())
                : store.loadConversation(conversation).map(AgentStore.ConversationSnapshot::revision).orElse(0L);
        AgentStore.Admission admission = driver.prepareAdmission(request, runtime, requestBinding, expectedRevision);
        return persistence.admit(admission, runtime.transactionParticipant());
    }

    /**
     * 取得单会话租约后交给同一 Driver；准入 receipt 本身不会创建第二个状态机。
     */
    @Override
    public CompletionStage<AgentExecutionOutcome> execute(AgentExecutionRequest request,
                                                          AgentStore.AdmissionReceipt admission, AgentRequestRuntime runtime,
                                                          CancellationCoordinator.CancellationScope cancellation, Consumer<AgentEvent> afterCommit,
                                                          TurnEventSink streamSink) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(afterCommit, "afterCommit");
        Objects.requireNonNull(streamSink, "streamSink");
        return startRun(request.identity(), runtime, cancellation, afterCommit, lease ->
                driver.execute(request, admission, runtime, lease, cancellation, afterCommit, streamSink));
    }

    /**
     * 恢复时消费固定水位内先前挂起意图，再由 Driver 从 Store 游标恢复工具、模型或等待阶段。
     */
    @Override
    public CompletionStage<AgentExecutionOutcome> resume(AgentExecutionBinding binding,
                                                         AgentRequestRuntime runtime, CancellationCoordinator.CancellationScope cancellation,
                                                         Consumer<AgentEvent> afterCommit, TurnEventSink streamSink) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(afterCommit, "afterCommit");
        Objects.requireNonNull(streamSink, "streamSink");
        return startRun(binding.identity(), runtime, cancellation, afterCommit, lease -> {
            settleExpiredApprovals(binding.identity(), afterCommit);
            consumePriorSuspensions(binding.identity(), lease, cancellation, afterCommit);
            return driver.resume(binding, runtime, lease, cancellation, afterCommit, streamSink);
        });
    }

    /**
     * 冷启动只重新登记固定 Wait revision 的截止时间；到期 CAS 经既有提交回调发布，绝不申请租约或调用 Driver。
     */
    @Override
    public void restoreApprovalExpiry(AgentIdentity identity, Consumer<AgentEvent> afterCommit) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(afterCommit, "afterCommit");
        if (closed.get()) {
            throw new IllegalStateException("agent loop service is closed");
        }
        settleExpiredApprovals(identity, afterCommit);
    }

    /**
     * 先提交取消事实，再通知当前进程令牌；跨节点执行者会在租约心跳时观察相同记录。
     */
    @Override
    public CompletionStage<AgentStore.CommitReceipt> cancel(AgentIdentity identity,
                                                            CancellationCoordinator.CancellationScope cancellation, AgentStore.OperationIdentity operation,
                                                            String reason, Consumer<AgentEvent> afterCommit) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(afterCommit, "afterCommit");
        AgentStore.CommitReceipt receipt = control(identity, operation, AgentStore.ControlKind.CANCEL_REQUEST,
                CANCEL_REQUEST_TYPE, new ControlRequest(operation.operationKey(), "CANCEL_REQUESTED", clock.instant()),
                ExecutionEvent.ControlAction.CANCEL_REQUESTED, "CANCEL_REQUESTED", Optional.empty());
        if (receipt.newlyCommitted()) {
            publishControl(identity, receipt, afterCommit);
        }
        cancellation.requestCancellation(reason);
        if (running.containsKey(identity)) {
            return CompletableFuture.completedFuture(receipt);
        }
        settleIdleCancellation(identity, cancellation, afterCommit);
        return CompletableFuture.completedFuture(receipt);
    }

    /**
     * 已挂起执行没有 Runner 接收取消信号时，取得同会话租约并由唯一 Driver 结算持久事实。
     */
    private void settleIdleCancellation(AgentIdentity identity,
                                        CancellationCoordinator.CancellationScope cancellation, Consumer<AgentEvent> afterCommit) {
        if (running.containsKey(identity)) {
            return;
        }
        AgentStore.Lease lease;
        try {
            lease = persistence.claim(conversation(identity), ownerId(), leaseDuration);
        } catch (AgentStore.StoreConflictException ownedElsewhere) {
            // 活跃的另一节点会在自己的租约心跳观察刚提交的 cancel-request；不越权改写它的执行游标。
            return;
        }
        LeaseGuard guard = new LeaseGuard(identity, lease, cancellation);
        try {
            guard.startDetached();
            driver.settleCancellation(identity, guard, cancellation, afterCommit);
        } finally {
            guard.stopDetached();
            persistence.release(guard.currentLease());
        }
    }

    /**
     * 按提交回执水位补读控制事实；观察错误只报告诊断，不能撤销数据库提交。
     */
    private void publishControl(AgentIdentity identity, AgentStore.CommitReceipt receipt,
                                Consumer<AgentEvent> afterCommit) {
        persistence.publishCommitted(identity, receipt, afterCommit,
                failure -> report("cancel event notification failed", failure));
    }

    /**
     * 将追加输入映射为同一通用 reducer 支持的 STEERING 命令，不另建输入事实格式。
     */
    @Override
    public CompletionStage<AgentStore.CommitReceipt> appendInput(AgentIdentity identity,
                                                                 AgentStore.OperationIdentity operation, String messageId, UserContent input) {
        Objects.requireNonNull(input, "input");
        AgentInputMutation mutation = new AgentInputMutation(AgentInputQueue.Action.ENQUEUE, messageId, 0,
                AgentInputQueue.Kind.STEERING, Optional.of(input), null, Map.of());
        return mutateInput(identity, operation, mutation).thenApply(AgentLoopExecutionService.InputMutationReceipt::commit);
    }

    /**
     * 将完整答案与更新后的 wait、审批 fact 和 SUSPENDED 状态在同一 control CAS 内保存。
     */
    @Override
    public CompletionStage<AgentStore.CommitReceipt> answer(AgentIdentity identity,
                                                            AgentStore.OperationIdentity operation, String interactionId, long expectedInteractionRevision,
                                                            List<InteractionAnswer> answers, Map<String, String> hostMetadata,
                                                            Consumer<AgentEvent> afterCommit) {
        requireOpen();
        Objects.requireNonNull(answers, "answers");
        Objects.requireNonNull(afterCommit, "afterCommit");
        Map<String, String> metadata = immutableMetadata(hostMetadata);
        Optional<AgentStore.CommitReceipt> replay = replayControl(identity, operation);
        if (replay.isPresent()) {
            return CompletableFuture.completedFuture(replay.orElseThrow());
        }
        ControlSnapshot current = controlSnapshot(identity);
        InteractionWait wait = pendingWait(current, interactionId, expectedInteractionRevision);
        Instant at = clock.instant();
        if (isApproval(wait) && !approvalExpiry(wait).isAfter(at)) {
            AgentStore.CommitReceipt expired = expireApprovalWait(current, identity, wait, at);
            if (expired.newlyCommitted()) {
                publishControl(identity, expired, afterCommit);
            }
            throw new AgentStore.StoreConflictException("approval wait has expired");
        }
        InteractionWait answered = wait.answer(answers, at);
        String answerId = AgentRecordIds.derive(execution(identity), ExecutionControlCodec.ANSWER_SUBMISSION_RECORD_TYPE,
                operation.operationKey());
        InteractionAnswerSubmission submission = new InteractionAnswerSubmission(answerId, interactionId,
                expectedInteractionRevision, operation.operationKey(), answers, metadata, at);
        List<AgentStore.RecordWrite> writes = new ArrayList<>();
        writes.add(controls.encodeAnswerSubmission(answerId, submission));
        String nextWaitRecordId = AgentRecordIds.derive(execution(identity), WAIT_RECORD_TYPE,
                interactionId + ":interaction-revision:" + answered.interactionRevision());
        AgentStore.RecordWrite waitWrite = controls.encodeWait(nextWaitRecordId, answered, at);
        writes.add(waitWrite);
        if (isApproval(wait)) {
            ApprovalDecision decision = approvalDecision(wait, answers, at);
            String approvalFactId = AgentRecordIds.derive(execution(identity), "agent.approval",
                    interactionId + ":answer:" + answerId);
            writes.add(records.encode(approvalFactId, new ExecutionFacts.Approval(interactionId,
                    Objects.requireNonNull(wait.toolCallId(), "approval callId"), decision,
                    approvalExpiry(wait)), at));
        }
        AgentStore.WaitControl waitControl = new AgentStore.WaitControl(interactionId, expectedInteractionRevision,
                Optional.of(answerId));
        ExecutionEvent.ControlRecorded event = new ExecutionEvent.ControlRecorded(
                ExecutionEvent.ControlAction.WAIT_RESOLVED, answerId, operation.operationKey(), null);
        AgentStore.CommitReceipt receipt = commitControlSnapshot(current, AgentStore.ControlKind.WAIT_SETTLEMENT,
                writes, waitSettlementEvents(current, event), operation, Optional.of(waitControl), at);
        return CompletableFuture.completedFuture(receipt);
    }

    /**
     * 关闭等待只写关闭后的 wait 状态，不伪造 answer record 或审批许可。
     */
    @Override
    public CompletionStage<AgentStore.CommitReceipt> closeWait(AgentIdentity identity,
                                                               AgentStore.OperationIdentity operation, String interactionId, long expectedInteractionRevision,
                                                               InteractionStatus status, Map<String, String> hostMetadata) {
        requireOpen();
        Objects.requireNonNull(hostMetadata, "hostMetadata");
        if (status != InteractionStatus.CANCELLED && status != InteractionStatus.SUPERSEDED) {
            throw new IllegalArgumentException("wait can only be cancelled or superseded");
        }
        Optional<AgentStore.CommitReceipt> replay = replayControl(identity, operation);
        if (replay.isPresent()) {
            return CompletableFuture.completedFuture(replay.orElseThrow());
        }
        ControlSnapshot current = controlSnapshot(identity);
        InteractionWait wait = pendingWait(current, interactionId, expectedInteractionRevision);
        Instant at = clock.instant();
        InteractionWait closedWait = wait.close(status, at);
        String recordId = AgentRecordIds.derive(execution(identity), WAIT_RECORD_TYPE,
                interactionId + ":interaction-revision:" + closedWait.interactionRevision());
        AgentStore.RecordWrite waitWrite = controls.encodeWait(recordId, closedWait, at);
        ExecutionEvent.ControlRecorded event = new ExecutionEvent.ControlRecorded(
                ExecutionEvent.ControlAction.WAIT_RESOLVED, recordId, operation.operationKey(), status.name());
        AgentStore.WaitControl waitControl = new AgentStore.WaitControl(interactionId, expectedInteractionRevision, Optional.empty());
        AgentStore.CommitReceipt receipt = commitControlSnapshot(current, AgentStore.ControlKind.WAIT_SETTLEMENT,
                List.of(waitWrite), waitSettlementEvents(current, event), operation, Optional.of(waitControl), at);
        return CompletableFuture.completedFuture(receipt);
    }

    /**
     * 查询同时回传会话 revision，让宿主以真实 Store 水位判断旧列表与历史路径。
     */
    @Override
    public Optional<AgentExecutionStatus> query(AgentIdentity identity) {
        requireOpen();
        AgentStore.ExecutionKey key = execution(identity);
        Optional<AgentStore.ExecutionSnapshot> execution = store.loadExecution(key);
        if (execution.isEmpty()) {
            return Optional.empty();
        }
        AgentStore.ConversationSnapshot conversation = store.loadConversation(key.conversation())
                .orElseThrow(() -> corrupt("execution conversation is missing"));
        AgentStore.ExecutionSnapshot snapshot = execution.orElseThrow();
        validateSnapshot(identity, conversation, snapshot);
        return Optional.of(new AgentExecutionStatus(identity, snapshot.state(), conversation.revision(),
                snapshot.mutationVersion(), snapshot.updatedAt()));
    }

    /**
     * 仅根据固定执行记录水位折叠输入队列，不从待消费正文推测已进入模型历史。
     */
    @Override
    public AgentInputQueue inspectInputQueue(AgentIdentity identity) {
        requireOpen();
        AgentStore.ExecutionSnapshot snapshot = loadExecution(identity);
        return foldQueue(snapshot);
    }

    /**
     * 以固定执行状态、水位和条目 revision 做 CAS；终态不接纳新输入，也不重读后改写冲突。
     */
    @Override
    public CompletionStage<InputMutationReceipt> mutateInput(AgentIdentity identity,
                                                             AgentStore.OperationIdentity operation, AgentInputMutation mutation) {
        requireOpen();
        Objects.requireNonNull(mutation, "mutation");
        Optional<AgentStore.CommitReceipt> replay = replayControl(identity, operation);
        if (replay.isPresent()) {
            return CompletableFuture.completedFuture(inputReplay(identity, operation,
                mutation, replay.orElseThrow()));
        }
        ControlSnapshot current = controlSnapshot(identity);
        if (mutation.action() == AgentInputQueue.Action.ENQUEUE
            && TurnState.valueOf(current.execution().state()).terminal()) {
            throw new AgentStore.StoreConflictException("cannot enqueue input for a terminal execution");
        }
        AgentInputQueue queue = foldQueue(current.execution());
        AgentInputQueue.Entry existing = queue.entries().stream()
                .filter(entry -> entry.inputId().equals(mutation.inputId())).findFirst().orElse(null);
        if (mutation.action() != AgentInputQueue.Action.ENQUEUE && existing == null) {
            throw new AgentStore.StoreConflictException("queued input does not exist");
        }
        if (existing != null && existing.inputRevision() != mutation.expectedInputRevision()) {
            throw new AgentStore.StoreConflictException("queued input revision changed");
        }
        if (mutation.action() == AgentInputQueue.Action.UPDATE && existing != null
            && existing.kind() == AgentInputQueue.Kind.MESSAGE
            && !mutation.body().orElseThrow().skillIds().isEmpty()) {
            throw new IllegalArgumentException("message input cannot change selected skills");
        }
        if (mutation.action() == AgentInputQueue.Action.ENQUEUE
            && queue.entries().size() >= AgentInputQueue.MAX_ENTRIES) {
            throw new AgentInputQueue.QueueFullException();
        }
        Instant at = clock.instant();
        String commandId = AgentRecordIds.derive(execution(identity), INPUT_COMMAND_TYPE, operation.operationKey());
        String bodyRecordId = mutation.body().isPresent()
                ? AgentRecordIds.derive(execution(identity), "agent.input.message", operation.operationKey()) : null;
        String messageId = mutation.body().isPresent()
                ? AgentRecordIds.derive(execution(identity), "agent.input.message-id", operation.operationKey()) : null;
        AgentInputQueue.Command command = new AgentInputQueue.Command(commandId, mutation.action(),
                mutation.inputId(), mutation.kind(), mutation.expectedInputRevision(),
                Math.addExact(queue.revision(), 1), bodyRecordId, mutation.issue(), at, mutation.hostMetadata());
        List<AgentStore.RecordWrite> writes = new ArrayList<>();
        mutation.body().ifPresent(body -> writes.add(records.encodeQueuedUser(bodyRecordId, messageId, body, at)));
        AgentStore.RecordWrite commandWrite = controls.encodeInputQueueCommand(commandId, command, at);
        writes.add(commandWrite);
        ExecutionEvent.ControlRecorded event = new ExecutionEvent.ControlRecorded(
                ExecutionEvent.ControlAction.INPUT_CHANGED, commandId, operation.operationKey(), null);
        AgentStore.CommitReceipt receipt;
        try {
            receipt = commitControlSnapshot(current, AgentStore.ControlKind.INPUT, writes,
                    List.of(event), operation, Optional.empty(), at);
        } catch (RuntimeException failure) {
            throw queueFullCause(failure);
        }
        return CompletableFuture.completedFuture(new InputMutationReceipt(receipt, mutation.inputId(), command.queueRevision()));
    }

    /**
     * 保存稳定暂停原因并等待唯一 Session 在安全点结束当前派发。
     */
    @Override
    public CompletionStage<AgentStore.CommitReceipt> suspend(AgentIdentity identity,
                                                             AgentStore.OperationIdentity operation, String reasonCode) {
        Instant at = clock.instant();
        String recordId = AgentRecordIds.derive(execution(identity), SUSPEND_REQUEST_TYPE,
                operation.operationKey());
        ControlRequest request = new ControlRequest(operation.operationKey(), reasonCode, at);
        AgentStore.RecordWrite write = controls.encodeSuspendRequest(recordId, request);
        ExecutionEvent.ControlRecorded event = new ExecutionEvent.ControlRecorded(
                ExecutionEvent.ControlAction.SUSPEND_REQUESTED, recordId, operation.operationKey(), reasonCode);
        return CompletableFuture.completedFuture(control(identity, operation, AgentStore.ControlKind.SUSPEND_REQUEST,
                SUSPEND_REQUEST_TYPE, write, event, Optional.empty()));
    }

    /**
     * 校验当前 pending recovery 的身份和 revision，再只追加宿主裁决命令供安全点消费。
     */
    @Override
    public CompletionStage<AgentStore.CommitReceipt> resolveToolRecovery(AgentIdentity identity,
                                                                         AgentStore.OperationIdentity operation, String recoveryId, String callId,
                                                                         long expectedRecoveryRevision, RecoveryCommand.Decision decision, String authorizationId) {
        requireOpen();
        Objects.requireNonNull(decision, "decision");
        Optional<AgentStore.CommitReceipt> replay = replayControl(identity, operation);
        if (replay.isPresent()) {
            return CompletableFuture.completedFuture(replay.orElseThrow());
        }
        ControlSnapshot current = controlSnapshot(identity);
        ToolRecoveryDecision pending = latestRecovery(current.execution(), recoveryId).orElseThrow(
                () -> new AgentStore.StoreConflictException("pending Tool recovery does not exist"));
        if (pending.status() != ToolRecoveryDecision.Status.PENDING || !pending.callId().equals(callId)
            || pending.revision() != expectedRecoveryRevision) {
            throw new AgentStore.StoreConflictException("pending Tool recovery changed");
        }
        Instant at = clock.instant();
        RecoveryCommand command = new RecoveryCommand(recoveryId, callId, expectedRecoveryRevision,
                decision, authorizationId, operation.operationKey(), at);
        String commandId = AgentRecordIds.derive(execution(identity),
                ExecutionControlCodec.RECOVERY_COMMAND_RECORD_TYPE, operation.operationKey());
        AgentStore.RecordWrite write = controls.encodeRecoveryCommand(commandId, command);
        ExecutionEvent.ControlRecorded event = new ExecutionEvent.ControlRecorded(
                ExecutionEvent.ControlAction.TOOL_RECOVERY_DECIDED, commandId, operation.operationKey(), decision.name());
        return CompletableFuture.completedFuture(commitControlSnapshot(current, AgentStore.ControlKind.TOOL_RECOVERY,
                List.of(write), List.of(event), operation, Optional.empty(), at));
    }

    /**
     * 停止准入并等待运行时和到期回调退出；Store 与模型供应商仍由 Builder 调用方持有。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<RunningExecution> active = List.copyOf(running.values());
        active.forEach(run -> run.cancellation.requestCancellation("engine closing"));
        CompletableFuture<?>[] completions = active.stream().map(run -> run.completion.handle((value, failure) -> null))
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(completions).join();
        } catch (CompletionException failure) {
            throw new IllegalStateException("Agent loop shutdown did not converge", unwrap(failure));
        } finally {
            heartbeats.shutdownNow();
            approvalExpiries.close();
        }
    }

    /**
     * 在任何执行资源移交前同步 claim、续租和读控制事实，失败时 Engine 仍负责 runtime。
     */
    private CompletionStage<AgentExecutionOutcome> startRun(AgentIdentity identity, AgentRequestRuntime runtime,
                                                            CancellationCoordinator.CancellationScope cancellation, Consumer<AgentEvent> afterCommit,
                                                            java.util.function.Function<LeaseGuard, CompletionStage<AgentExecutionOutcome>> dispatch) {
        requireOpen();
        RunningExecution run = new RunningExecution(identity, runtime, cancellation, afterCommit);
        if (running.putIfAbsent(identity, run) != null) {
            throw new IllegalStateException("execution is already active");
        }
        try {
            AgentStore.Lease lease = persistence.claim(conversation(identity), ownerId(), leaseDuration);
            run.guard = new LeaseGuard(identity, lease, cancellation);
            run.guard.start();
            CompletionStage<AgentExecutionOutcome> stage = Objects.requireNonNull(dispatch.apply(run.guard), "execution stage");
            run.accept(stage);
            return run.completion;
        } catch (RuntimeException | Error failure) {
            run.abortBeforeTransfer(failure);
            throw failure;
        }
    }

    /**
     * 将原先挂起请求按一次恢复的固定观察集消费，避免恢复后立刻再次挂起。
     */
    private void consumePriorSuspensions(AgentIdentity identity, ExecutionLease lease,
                                         CancellationToken cancellation, Consumer<AgentEvent> afterCommit) {
        AgentLoopPersistence.Restored initial = persistence.restore(identity, cancellation);
        if (!TurnState.SUSPENDED.name().equals(initial.execution().state())) {
            return;
        }
        List<Suspension> visible = pendingSuspensions(initial);
        String resumeKey = "resume_" + UUID.randomUUID().toString().replace("-", "");
        for (Suspension request : visible) {
            cancellation.throwIfCancellationRequested();
            while (true) {
                ControlSnapshot current = controlSnapshot(identity);
                if (!TurnState.SUSPENDED.name().equals(current.execution().state())) {
                    return;
                }
                String operationKey = AgentRecordIds.derive(execution(identity), "agent.suspend.consume.operation",
                        request.recordId() + ":" + resumeKey);
                String recordId = AgentRecordIds.derive(execution(identity), SUSPEND_CONSUMED_TYPE,
                        request.recordId() + ":" + resumeKey);
                Instant at = clock.instant();
                SuspendConsumption consumption = new SuspendConsumption(request.recordId(),
                        request.request().operationKey(), operationKey, at);
                AgentStore.RecordWrite record = controls.encodeSuspendConsumption(recordId, consumption);
                ExecutionEvent.ControlRecorded event = new ExecutionEvent.ControlRecorded(
                        ExecutionEvent.ControlAction.SUSPEND_CONSUMED, recordId, operationKey, null);
                AgentStore.OperationIdentity operation = internalOperation(operationKey,
                        "agent.execution.suspend.consume", identity, request.recordId() + ":" + resumeKey);
                try {
                    ControlSnapshot latest = current;
                    AgentStore.CommitReceipt receipt = commitControlSnapshot(latest,
                            AgentStore.ControlKind.SUSPEND_CONSUME, List.of(record), List.of(event), operation,
                            Optional.empty(), at);
                    if (receipt.newlyCommitted()) {
                        persistence.publishCommitted(identity, receipt, afterCommit,
                            failure -> report("suspend consumption event notification failed", failure));
                    }
                    break;
                } catch (AgentStore.StoreConflictException changed) {
                    AgentStore.ExecutionSnapshot now = loadExecution(identity);
                    if (!TurnState.SUSPENDED.name().equals(now.state())) {
                        return;
                    }
                    if (recordExists(identity, recordId)) {
                        break;
                    }
                    if (now.mutationVersion() == current.execution().mutationVersion()) {
                        throw changed;
                    }
                }
            }
        }
        lease.assertDispatchAuthority();
    }

    /**
     * 只把本次恢复开始前看见的挂起请求列入消费清单，新请求留给之后的安全点。
     */
    private List<Suspension> pendingSuspensions(AgentLoopPersistence.Restored snapshot) {
        Map<String, ControlRequest> requests = new HashMap<>();
        Set<String> consumed = new HashSet<>();
        forEachRecord(snapshot.execution(), record -> {
            if (record.recordType().equals(SUSPEND_REQUEST_TYPE)) {
                requests.put(record.recordId(), controls.decodeSuspendRequest(record));
            } else if (record.recordType().equals(SUSPEND_CONSUMED_TYPE)) {
                consumed.add(controls.decodeSuspendConsumption(record).suspendRequestRecordId());
            }
        });
        return requests.entrySet().stream().filter(entry -> !consumed.contains(entry.getKey()))
                .map(entry -> new Suspension(entry.getKey(), entry.getValue())).toList();
    }

    /**
     * 对单个租户执行身份执行无 cursor 的控制 CAS，并复用 operation receipt 恢复响应丢失。
     */
    private AgentStore.CommitReceipt control(AgentIdentity identity, AgentStore.OperationIdentity operation,
                                             AgentStore.ControlKind kind, String recordType, ControlRequest request,
                                             ExecutionEvent.ControlAction action, String reasonCode, Optional<AgentStore.WaitControl> wait) {
        Objects.requireNonNull(request, "request");
        String recordId = AgentRecordIds.derive(execution(identity), recordType, operation.operationKey());
        AgentStore.RecordWrite write = kind == AgentStore.ControlKind.CANCEL_REQUEST
                ? controls.encodeCancelRequest(recordId, request) : controls.encodeSuspendRequest(recordId, request);
        ExecutionEvent.ControlRecorded event = new ExecutionEvent.ControlRecorded(action, recordId,
                operation.operationKey(), reasonCode);
        return control(identity, operation, kind, recordType, write, event, wait);
    }

    /**
     * 读取原 operation receipt 后提交当前水位；响应未知时只查询回执，不重复控制副作用。
     */
    private AgentStore.CommitReceipt control(AgentIdentity identity, AgentStore.OperationIdentity operation,
                                             AgentStore.ControlKind kind, String requiredRecordType, AgentStore.RecordWrite write,
                                             ExecutionEvent.ControlRecorded event, Optional<AgentStore.WaitControl> wait) {
        requireOpen();
        Optional<AgentStore.CommitReceipt> replay = replayControl(identity, operation);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        if (!write.recordType().equals(requiredRecordType))
            {
                throw new IllegalArgumentException("control record type mismatch");
            }
        ControlSnapshot current = controlSnapshot(identity);
        return commitControlSnapshot(current, kind, List.of(write), List.of(event), operation, wait, clock.instant());
    }

    /**
     * 以固定会话与执行版本提交控制事实；取消和暂停仅在同一执行仍活动且权威版本前进时重读并重试。
     *
     * @author kongweiguang
     */
    private AgentStore.CommitReceipt commitControlSnapshot(ControlSnapshot current, AgentStore.ControlKind kind,
                                                           List<AgentStore.RecordWrite> writes, List<ExecutionEvent> controlEvents,
                                                           AgentStore.OperationIdentity operation, Optional<AgentStore.WaitControl> wait, Instant at) {
        AgentStore.ExecutionSnapshot execution = current.execution();
        List<AgentStore.EventWrite> eventWrites = new ArrayList<>(controlEvents.size());
        for (int index = 0; index < controlEvents.size(); index++) {
            String eventId = AgentRecordIds.derive(execution.key(), "agent.event",
                    operation.operationKey() + ":" + index);
            eventWrites.add(events.encode(eventId, controlEvents.get(index), at));
        }
        while (true) {
            execution = current.execution();
            AgentStore.ControlCommit request = new AgentStore.ControlCommit(execution.key(),
                    current.conversation().revision(), execution.state(), execution.mutationVersion(), kind,
                    writes, eventWrites, operation, wait, at);
            try {
                return store.controlCommit(request);
            } catch (AgentStore.StoreUnavailableException unavailable) {
                Optional<AgentStore.CommitReceipt> reconciled = replayControl(execution.key(), operation);
                if (reconciled.isPresent()) {
                    return reconciled.orElseThrow();
                }
                throw new AgentLoopPersistence.UncertainPersistenceException("control commit outcome is unknown",
                        operation.operationKey(), unavailable);
            } catch (AgentStore.StoreConflictException conflict) {
                if (kind != AgentStore.ControlKind.CANCEL_REQUEST
                    && kind != AgentStore.ControlKind.SUSPEND_REQUEST) {
                    throw conflict;
                }
                Optional<AgentStore.CommitReceipt> reconciled = replayControl(execution.key(), operation);
                if (reconciled.isPresent()) {
                    return reconciled.orElseThrow();
                }
                pauseBeforeControlRetry();
                ControlSnapshot latest = controlSnapshot(execution.key());
                boolean conversationAdvanced = latest.conversation().revision() > current.conversation().revision();
                boolean executionAdvanced = latest.execution().mutationVersion() > current.execution().mutationVersion();
                if ((!conversationAdvanced && !executionAdvanced)
                    || TurnState.valueOf(latest.execution().state()).terminal()) {
                    throw conflict;
                }
                current = latest;
            }
        }
    }

    /**
     * 按 operation key 核对 scope 与指纹，重复调用返回原水位且不再次验证过期答案。
     */
    private Optional<AgentStore.CommitReceipt> replayControl(AgentIdentity identity,
                                                             AgentStore.OperationIdentity operation) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(operation, "operation");
        return replayControl(execution(identity), operation);
    }

    /**
     * 只有同租户、同会话、同 execution、同 operation type 与 fingerprint 才可复用回执。
     */
    private Optional<AgentStore.CommitReceipt> replayControl(AgentStore.ExecutionKey execution,
                                                             AgentStore.OperationIdentity operation) {
        Optional<AgentStore.OperationReceipt> receipt = store.readReceipt(new AgentStore.OperationKey(
                execution.conversation().tenantId(), execution.conversation().userId(), operation.operationKey()));
        if (receipt.isEmpty()) {
            return Optional.empty();
        }
        AgentStore.OperationReceipt saved = receipt.orElseThrow();
        if (!saved.conversation().equals(execution.conversation())
            || !saved.turnId().equals(execution.turnId())
            || !saved.operationKey().equals(operation.operationKey())
            || !saved.fingerprint().equals(operation.fingerprint())
            || !saved.operationType().equals(operation.operationType())) {
            throw new AgentStore.StoreConflictException("operation key was reused with different control semantics");
        }
        return Optional.of(new AgentStore.CommitReceipt(saved.threadRevision(), saved.mutationVersion(),
                saved.firstEventSequence(), saved.lastEventSequence(), saved.receipt(), false));
    }

    /**
     * 捕获先执行后会话水位的双 CAS 快照，后续任何同 execution 并发事实都会使提交失败。
     */
    private ControlSnapshot controlSnapshot(AgentIdentity identity) {
        AgentStore.ExecutionKey key = execution(identity);
        AgentStore.ExecutionSnapshot execution = loadExecution(identity);
        AgentStore.ConversationSnapshot conversation = store.loadConversation(key.conversation())
                .orElseThrow(() -> corrupt("execution conversation is missing"));
        validateSnapshot(identity, conversation, execution);
        return new ControlSnapshot(conversation, execution);
    }

    /**
     * CAS 冲突后按已验证的持久执行键重读，避免用旧 revision 再次提交或越过会话边界。
     */
    private ControlSnapshot controlSnapshot(AgentStore.ExecutionKey key) {
        Objects.requireNonNull(key, "key");
        AgentStore.ExecutionSnapshot execution = store.loadExecution(key)
                .orElseThrow(() -> new IllegalArgumentException("execution does not exist"));
        AgentStore.ConversationSnapshot conversation = store.loadConversation(key.conversation())
                .orElseThrow(() -> corrupt("execution conversation is missing"));
        if (!execution.key().equals(key) || !conversation.key().equals(key.conversation())
            || execution.lastRecordSequence() > conversation.recordSequence()) {
            throw corrupt("Store snapshot identity or record watermark is inconsistent");
        }
        return new ControlSnapshot(conversation, execution);
    }

    /**
     * 统一 tenant-scoped 执行读取，缺失时不尝试以会话或旧投影猜恢复来源。
     */
    private AgentStore.ExecutionSnapshot loadExecution(AgentIdentity identity) {
        return store.loadExecution(execution(identity))
                .orElseThrow(() -> new IllegalArgumentException("execution does not exist"));
    }

    /**
     * 在一次执行的固定记录序号上按类型还原最新 wait，interactionRevision 跳跃或重复身份损坏即拒绝回答。
     */
    private Optional<InteractionWait> latestWait(AgentStore.ExecutionSnapshot snapshot, String interactionId) {
        return Optional.ofNullable(latestWaits(snapshot).get(interactionId));
    }

    /**
     * 回答与关闭共享同一水位上的 interactionRevision 校验，避免先读后以新版本掩盖竞态。
     */
    private InteractionWait pendingWait(ControlSnapshot snapshot, String interactionId,
                                        long expectedInteractionRevision) {
        InteractionWait wait = latestWait(snapshot.execution(), interactionId).orElseThrow(
                () -> new AgentStore.StoreConflictException("interaction wait does not exist"));
        if (wait.status() != InteractionStatus.PENDING
            || wait.interactionRevision() != expectedInteractionRevision) {
            throw new AgentStore.StoreConflictException("interaction wait changed");
        }
        return wait;
    }

    /**
     * 以一次 execution snapshot 的固定水位折叠所有 Wait，保证到期裁决不会混入并发新版本。
     */
    private Map<String, InteractionWait> latestWaits(AgentStore.ExecutionSnapshot snapshot) {
        Map<String, InteractionWait> waits = new HashMap<>();
        forEachRecord(snapshot, record -> {
            if (!record.recordType().equals(WAIT_RECORD_TYPE)) {
                return;
            }
            InteractionWait value = controls.decodeWait(record);
            InteractionWait previous = waits.get(value.interactionId());
            if (previous != null
                && value.interactionRevision() != Math.addExact(previous.interactionRevision(), 1)) {
                throw corrupt("wait revision history is not contiguous");
            }
            waits.put(value.interactionId(), value);
        });
        return Map.copyOf(waits);
    }

    /**
     * 在同一 WAIT_SETTLEMENT CAS 中写入 EXPIRED 与 SUSPENDED，不创建答案或审批决定。
     */
    private AgentStore.CommitReceipt expireApprovalWait(ControlSnapshot current, AgentIdentity identity,
                                                        InteractionWait wait, Instant at) {
        if (wait.status() != InteractionStatus.PENDING || !isApproval(wait)
            || approvalExpiry(wait).isAfter(at)) {
            throw new AgentStore.StoreConflictException("approval wait is not due for expiry");
        }
        String state = current.execution().state();
        if (!TurnState.WAITING_APPROVAL.name().equals(state)
            && !TurnState.WAITING_INPUT.name().equals(state)
            && !TurnState.SUSPENDED.name().equals(state)) {
            throw new AgentStore.StoreConflictException("execution is not waiting for approval settlement");
        }
        InteractionWait expired = wait.close(InteractionStatus.EXPIRED, at);
        String recordId = AgentRecordIds.derive(current.execution().key(), WAIT_RECORD_TYPE,
                wait.interactionId() + ":interaction-revision:" + expired.interactionRevision());
        AgentStore.RecordWrite write = controls.encodeWait(recordId, expired, at);
        AgentStore.OperationIdentity operation = expiryOperation(identity, wait);
        ExecutionEvent.ControlRecorded event = new ExecutionEvent.ControlRecorded(
                ExecutionEvent.ControlAction.WAIT_RESOLVED, recordId, operation.operationKey(),
                InteractionStatus.EXPIRED.name());
        AgentStore.WaitControl waitControl = new AgentStore.WaitControl(wait.interactionId(),
                wait.interactionRevision(), Optional.empty());
        return commitControlSnapshot(current, AgentStore.ControlKind.WAIT_SETTLEMENT, List.of(write),
                waitSettlementEvents(current, event),
                operation, Optional.of(waitControl), at);
    }

    /**
     * 以固定水位 CAS 关闭一个到期审批；CAS 冲突后重新读取，但不覆盖已经回答的版本。
     */
    private void expireApprovalWait(AgentIdentity identity, String interactionId,
                                    long expectedInteractionRevision,
                                    Consumer<AgentEvent> afterCommit) {
        AgentStore.OperationIdentity operation = expiryOperation(identity, interactionId, expectedInteractionRevision);
        while (!closed.get()) {
            Optional<AgentStore.CommitReceipt> replay = replayControl(identity, operation);
            if (replay.isPresent()) {
                return;
            }
            ControlSnapshot current = controlSnapshot(identity);
            if (!waitingState(current.execution().state())) {
                return;
            }
            Optional<InteractionWait> candidate = latestWait(current.execution(), interactionId);
            if (candidate.isEmpty()) {
                return;
            }
            InteractionWait wait = candidate.orElseThrow();
            if (wait.status() != InteractionStatus.PENDING
                || wait.interactionRevision() != expectedInteractionRevision
                || !isApproval(wait) || approvalExpiry(wait).isAfter(clock.instant())) {
                    return;
                }
            try {
                AgentStore.CommitReceipt receipt = expireApprovalWait(current, identity, wait, clock.instant());
                if (receipt.newlyCommitted()) {
                    publishControl(identity, receipt, afterCommit);
                }
                return;
            } catch (AgentStore.StoreConflictException changed) {
                if (replayControl(identity, operation).isPresent()) {
                    return;
                }
                // 等待期间，其他控制操作可能已推进 mutation 或 revision。
            }
        }
    }

    /**
     * 恢复前结算已到期的审批，并为此进程新发现的未来审批注册有界到期任务。
     */
    private void settleExpiredApprovals(AgentIdentity identity, Consumer<AgentEvent> afterCommit) {
        ControlSnapshot current = controlSnapshot(identity);
        if (!waitingState(current.execution().state())) {
            return;
        }
        Map<String, InteractionWait> waits = latestWaits(current.execution());
        Instant now = clock.instant();
        for (InteractionWait wait : waits.values()) {
            if (wait.status() != InteractionStatus.PENDING || !isApproval(wait)) {
                continue;
            }
            Instant expiresAt = approvalExpiry(wait);
            if (!expiresAt.isAfter(now)) {
                expireApprovalWait(identity, wait.interactionId(), wait.interactionRevision(), afterCommit);
            } else {
                scheduleWaitExpiry(identity, wait, expiresAt, afterCommit);
            }
        }
    }

    /**
     * Suspended 是持久等待的安全边界；此处发现并登记审批截止时间，恢复后仍由 Store 校验。
     */
    private void schedulePendingApprovalExpiries(AgentIdentity identity, Consumer<AgentEvent> afterCommit) {
        AgentStore.ExecutionSnapshot snapshot = store.loadExecution(execution(identity)).orElse(null);
        if (snapshot == null || !waitingState(snapshot.state())) {
            return;
        }
        Instant now = clock.instant();
        for (InteractionWait wait : latestWaits(snapshot).values()) {
            if (wait.status() != InteractionStatus.PENDING || !isApproval(wait)) {
                continue;
            }
            Instant expiresAt = approvalExpiry(wait);
            scheduleWaitExpiry(identity, wait, expiresAt.isAfter(now) ? expiresAt : now, afterCommit);
        }
    }

    /**
     * 服务只提供固定等待版本的 CAS 结算，登记、重试及线程关闭由审批计时 owner 统一负责。
     */
    private void scheduleWaitExpiry(AgentIdentity identity, InteractionWait wait, Instant expiresAt,
                                    Consumer<AgentEvent> afterCommit) {
        if (closed.get()) {
            return;
        }
        approvalExpiries.schedule(identity, wait.interactionId(), wait.interactionRevision(), expiresAt,
                () -> expireApprovalWait(identity, wait.interactionId(), wait.interactionRevision(), afterCommit));
    }

    /**
     * 逐页从空队列折叠当前命令；精确退役快照必须失败，不能静默恢复为空队列。
     * @author kongweiguang
     */
    private AgentInputQueue foldQueue(AgentStore.ExecutionSnapshot snapshot) {
        AgentInputQueue[] queue = {new AgentInputQueue(snapshot.key().turnId(), 0, List.of())};
        forEachRecord(snapshot, record -> {
            if (record.recordType().equals("agent.input.queue.snapshot")) {
                throw corrupt("input queue snapshot record is no longer supported");
            }
            if (record.recordType().equals(INPUT_COMMAND_TYPE)) {
                queue[0] = queue[0].apply(controls.decodeInputQueueCommand(record));
            }
        });
        return queue[0];
    }

    /**
     * 只接受状态仍为 PENDING 的恢复聚合，避免在工具结果已核实后追加迟到裁决。
     */
    private Optional<ToolRecoveryDecision> latestRecovery(AgentStore.ExecutionSnapshot snapshot, String recoveryId) {
        Map<String, ToolRecoveryDecision> decisions = new HashMap<>();
        forEachRecord(snapshot, record -> {
            if (!record.recordType().equals(TOOL_RECOVERY_DECISION_TYPE)) {
                return;
            }
            ToolRecoveryDecision value = controls.decodeToolRecoveryDecision(record);
            decisions.put(value.recoveryId(), value);
        });
        return Optional.ofNullable(decisions.get(recoveryId));
    }

    /**
     * 使用 execution snapshot 的固定记录水位分页，避免控制读取混入提交后的新版本。
     */
    private void forEachRecord(AgentStore.ExecutionSnapshot snapshot, Consumer<AgentStore.StoredRecord> consumer) {
        long after = 0;
        while (after < snapshot.lastRecordSequence()) {
            AgentStore.RecordPage page = store.readRecords(AgentStore.RecordQuery.withDefaultMaxBytes(
                    snapshot.key(), after, snapshot.lastRecordSequence(), PAGE_SIZE));
            long previous = after;
            for (AgentStore.StoredRecord record : page.records()) {
                if (record.sequence() <= after || record.sequence() > snapshot.lastRecordSequence()) {
                    throw corrupt("record page is outside its captured watermark");
                }
                consumer.accept(record);
                after = record.sequence();
            }
            if (page.nextSequence() != after) {
                throw corrupt("record page cursor does not match returned rows");
            }
            if (page.hasMore() && after <= previous) {
                throw corrupt("record page made no progress");
            }
            if (!page.hasMore()) {
                break;
            }
        }
    }

    /**
     * 读取队列操作重放的明确输入身份与 reducer 版本，不按队尾或内容猜测。
     */
    private InputMutationReceipt inputReplay(AgentIdentity identity, AgentStore.OperationIdentity operation,
                                             AgentInputMutation mutation, AgentStore.CommitReceipt receipt) {
        String commandId = AgentRecordIds.derive(execution(identity), INPUT_COMMAND_TYPE, operation.operationKey());
        AgentStore.StoredRecord row = store.readRecord(execution(identity), commandId).orElseThrow(
                () -> corrupt("input mutation receipt has no command record"));
        AgentInputQueue.Command command = controls.decodeInputQueueCommand(row);
        if (!command.inputId().equals(mutation.inputId()) || command.action() != mutation.action()) {
            throw new AgentStore.StoreConflictException("input mutation replay identity changed");
        }
        return new InputMutationReceipt(receipt, command.inputId(), command.queueRevision());
    }

    /**
     * 检查记录是否已提交，用于控制 CAS 冲突后避免重复写同一恢复消费事实。
     */
    private boolean recordExists(AgentIdentity identity, String recordId) {
        return store.readRecord(execution(identity), recordId).isPresent();
    }

    /**
     * 服务关闭后拒绝新执行，避免创建无法续租的孤儿 owner。
     */
    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Agent loop service is closed");
        }
    }

    /**
     * 控制事务读取到的会话与执行快照；下一次同执行提交由 Store CAS 决定是否仍有效。
     *
     * @param conversation 对话
     * @param execution 执行
     */
    private record ControlSnapshot(AgentStore.ConversationSnapshot conversation,
                                   AgentStore.ExecutionSnapshot execution) {
        /**
         * 两份快照必须属于同一 tenant conversation。
         */
        private ControlSnapshot {
            Objects.requireNonNull(conversation, "conversation");
            Objects.requireNonNull(execution, "execution");
            if (!conversation.key().equals(execution.key().conversation())) {
                throw new IllegalArgumentException("control snapshot belongs to another conversation");
            }
        }
    }

    /**
     * 恢复消费仅引用挂起记录身份和其解码后的稳定操作键。
     *
     * @param recordId 记录标识
     * @param request 请求
     */
    private record Suspension(String recordId,
                              ControlRequest request) {
        /**
         * 控制记录必须能产生有稳定身份的消费事实。
         */
        private Suspension {
            Objects.requireNonNull(recordId, "recordId");
            Objects.requireNonNull(request, "request");
        }
    }

    /**
     * 跨节点租约续期或控制读取失败时撤销派发资格，任务由新 owner 按 Store 游标恢复。
     */
    private static final class LeaseAuthorityLostException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        /**
         * 不携带会话正文，只保留租户执行身份与底层根因供受控诊断。
         */
        private LeaseAuthorityLostException(AgentIdentity identity, Throwable cause) {
            super("execution lease authority lost for " + identity.turnId(), cause);
        }
    }

    /**
     * 先注册活动 owner 再驱动异步状态机，运行阶段始终由服务负责租约和 runtime 的 exactly-once close。
     */
    private final class RunningExecution {
        private final AgentIdentity identity;
        private final AgentRequestRuntime runtime;
        private final CancellationCoordinator.CancellationScope cancellation;
        private final Consumer<AgentEvent> afterCommit;
        private final CompletableFuture<AgentExecutionOutcome> completion = new CompletableFuture<>();
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile LeaseGuard guard;
        private volatile ScheduledFuture<?> heartbeat;

        /**
         * 当前执行独占已解析 runtime；同步派发失败时 Engine 仍拥有关闭权。
         */
        private RunningExecution(AgentIdentity identity, AgentRequestRuntime runtime,
                                 CancellationCoordinator.CancellationScope cancellation, Consumer<AgentEvent> afterCommit) {
            this.identity = identity;
            this.runtime = runtime;
            this.cancellation = cancellation;
            this.afterCommit = afterCommit;
        }

        /**
         * 只在 Driver 接受有效 stage 后移交 runtime，完成、等待和异常均收敛到同一清理路径。
         */
        private void accept(CompletionStage<AgentExecutionOutcome> stage) {
            stage.whenComplete((outcome, failure) -> finish(outcome, failure));
        }

        /**
         * 释放自己 claim 的租约与运行时；清理失败不能被伪装为正常终态回执。
         */
        private void finish(AgentExecutionOutcome outcome, Throwable failure) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            Throwable resultFailure = failure == null ? null : unwrap(failure);
            try {
                if (guard != null && guard.currentLease() != null) {
                    persistence.release(guard.currentLease());
                }
            } catch (RuntimeException releaseFailure) {
                if (resultFailure == null) {
                    resultFailure = releaseFailure;
                }
                else if (resultFailure != releaseFailure) {
                    resultFailure.addSuppressed(releaseFailure);
                }
            }
            if (resultFailure == null && outcome instanceof AgentExecutionOutcome.Suspended && !closed.get()) {
                try {
                    schedulePendingApprovalExpiries(identity, afterCommit);
                } catch (RuntimeException schedulingFailure) {
                    report("could not register durable approval expiry; answer/resume will recheck its deadline",
                            schedulingFailure);
                }
            }
            try {
                runtime.close();
            } catch (RuntimeException closeFailure) {
                if (resultFailure == null) {
                    resultFailure = closeFailure;
                }
                else if (resultFailure != closeFailure) {
                    resultFailure.addSuppressed(closeFailure);
                }
            }
            running.remove(identity, this);
            if (resultFailure != null) {
                completion.completeExceptionally(resultFailure);
            }
            else if (outcome == null)
                {
                    completion.completeExceptionally(new IllegalStateException("Agent driver returned no outcome"));
                }
            else {
                completion.complete(outcome);
            }
        }

        /**
         * 同步准入或 Driver 调用失败时只释放 lease，不关闭仍由 Engine 持有的 runtime。
         */
        private void abortBeforeTransfer(Throwable failure) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            try {
                if (guard != null && guard.currentLease() != null) {
                    persistence.release(guard.currentLease());
                }
            } catch (RuntimeException releaseFailure) {
                if (failure != releaseFailure) {
                    failure.addSuppressed(releaseFailure);
                }
            }
            running.remove(identity, this);
            completion.completeExceptionally(failure);
        }
    }

    /**
     * 心跳续租同时增量观察远端取消记录，旧 fencing token 失效后立即禁止新的模型或工具派发。
     */
    private final class LeaseGuard implements ExecutionLease {
        private final AgentIdentity identity;
        private final CancellationCoordinator.CancellationScope cancellation;
        private final AtomicReference<AgentStore.Lease> lease;
        private long scannedRecordSequence;
        private volatile LeaseAuthorityLostException lost;
        private volatile ScheduledFuture<?> detachedHeartbeat;

        /**
         * 每次执行仅保留本机已确认的最新 lease 与控制扫描游标。
         */
        private LeaseGuard(AgentIdentity identity, AgentStore.Lease lease,
                           CancellationCoordinator.CancellationScope cancellation) {
            this.identity = identity;
            this.lease = new AtomicReference<>(lease);
            this.cancellation = cancellation;
        }

        /**
         * 派发前同步检查 fencing 并检查数据库控制事实，不能只依赖本 JVM cancel map。
         */
        private void start() {
            synchronized (this) {
                renewAndObserve();
            }
            RunningExecution owner = running.get(identity);
            if (owner == null) {
                throw new IllegalStateException("lease owner registration disappeared");
            }
            owner.heartbeat = scheduleRenewals();
        }

        /**
         * 对无 Runner 的控制结算独立续租，避免长历史回读期间租期过期。
         */
        private void startDetached() {
            synchronized (this) {
                renewAndObserve();
            }
            detachedHeartbeat = scheduleRenewals();
        }

        /**
         * 控制结算返回后停止本对象独占的心跳。
         */
        private void stopDetached() {
            ScheduledFuture<?> heartbeat = detachedHeartbeat;
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
        }

        /**
         * 续租与增量控制读取失败时立即撤销相应 Runner 或控制事务的提交权限。
         */
        private ScheduledFuture<?> scheduleRenewals() {
            long interval = Math.max(1, leaseDuration.toMillis() / 3);
            return heartbeats.scheduleAtFixedRate(() -> {
                try {
                    synchronized (LeaseGuard.this) {
                        renewAndObserve();
                    }
                } catch (RuntimeException failure) {
                    loseAuthority(failure);
                }
            }, interval, interval, TimeUnit.MILLISECONDS);
        }

        /**
         * 返回最新续租回执；已失效时向唯一 Driver 抛错并触发当前作用域取消。
         */
        @Override
        @SuppressWarnings("PMD.PreserveStackTrace") // lease 身份包装保留原 Store/租约故障作为 cause。
        public synchronized AgentStore.Lease assertDispatchAuthority() {
            if (lost != null) {
                throw lost;
            }
            try {
                renewAndObserve();
                return current();
            } catch (RuntimeException failure) {
                loseAuthority(failure);
                throw lost;
            }
        }

        /**
         * 提交总是读取最新 lease，避免把创建时 token 过期快照交给 Runner。
         */
        @Override
        public AgentStore.Lease current() {
            if (lost != null) {
                throw lost;
            }
            return lease.get();
        }

        /**
         * 关闭或失效路径只读内部当前 token，用于释放同一代租约。
         */
        private AgentStore.Lease currentLease() {
            return lease.get();
        }

        /**
         * 每次心跳先续租再读固定 execution watermark 的新增记录。
         */
        private void renewAndObserve() {
            lease.set(persistence.renew(lease.get(), leaseDuration));
            AgentStore.ExecutionSnapshot snapshot = loadExecution(identity);
            if (snapshot.lastRecordSequence() < scannedRecordSequence)
                {
                    throw corrupt("execution record watermark regressed");
                }
            if (snapshot.lastRecordSequence() == scannedRecordSequence) {
                return;
            }
            while (scannedRecordSequence < snapshot.lastRecordSequence()) {
                long previous = scannedRecordSequence;
                AgentStore.RecordPage page = store.readRecords(AgentStore.RecordQuery.withDefaultMaxBytes(
                        execution(identity), scannedRecordSequence, snapshot.lastRecordSequence(), PAGE_SIZE));
                for (AgentStore.StoredRecord record : page.records()) {
                    if (record.sequence() <= scannedRecordSequence
                        || record.sequence() > snapshot.lastRecordSequence()) {
                        throw corrupt("control record page is outside its captured watermark");
                    }
                    if (record.recordType().equals(CANCEL_REQUEST_TYPE)) {
                        ControlRequest request = controls.decodeCancelRequest(record);
                        cancellation.requestCancellation(request.reasonCode());
                    }
                    scannedRecordSequence = record.sequence();
                }
                if (page.nextSequence() != scannedRecordSequence || scannedRecordSequence <= previous) {
                    if (page.hasMore() || !page.records().isEmpty()) {
                        throw corrupt("control record page cursor did not advance");
                    }
                }
                // Record sequence 在整个对话内全局递增，因此该执行在水位前可能存在序列间隔。
                if (!page.hasMore()) {
                    scannedRecordSequence = snapshot.lastRecordSequence();
                }
            }
        }

        /**
         * 任意续租或增量读取错误都撤销本机派发许可，不把数据库不可用解释为仍持有执行权。
         */
        private void loseAuthority(RuntimeException failure) {
            if (lost == null) {
                lost = new LeaseAuthorityLostException(identity, failure);
            }
            cancellation.requestCancellation("execution authority lost");
        }
    }
}
