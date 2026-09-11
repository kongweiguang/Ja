// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.interaction;

import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionQuestion;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionRequest;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionDraft;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionEvent;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionSnapshot;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.conversation.port.in.InteractionUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnEventSink;
import io.github.kongweiguang.ja.conversation.port.out.InteractionRepository;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Interaction 应用服务；把输入校验、唯一活动请求和 Repository CAS 集中在一个边界。 */
public final class InteractionService implements InteractionUseCase {
    private final InteractionRepository repository;
    private final ConversationRepository conversations;
    private final Clock clock;
    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final AtomicReference<ResumeScheduler> resumeScheduler =
            new AtomicReference<>((threadId, turnId, threadRevision, resumeSink) -> { });
    private final AtomicBoolean resumeSchedulerBound = new AtomicBoolean();

    /** 生产组合根注入 SQLite Repository 与可测试时钟，避免服务自行持有连接或等待线程。 */
    public InteractionService(InteractionRepository repository, ConversationRepository conversations, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 由组合根在 TurnService 创建后绑定，打破 Interaction 与执行器的循环依赖；绑定前回答仍可落库，
     * 但不会伪造本地恢复。
     */
    public void bindResumeScheduler(ResumeScheduler scheduler) {
        Objects.requireNonNull(scheduler, "scheduler");
        if (!resumeSchedulerBound.compareAndSet(false, true)) {
            throw new IllegalStateException("interaction resume scheduler is already bound");
        }
        resumeScheduler.set(scheduler);
    }

    /** 只构造请求快照，不写库；Loop 会把它与 Tool cursor 和 SUSPENDED 状态合并提交。 */
    public InteractionRequest prepare(CreateRequest request) {
        Objects.requireNonNull(request, "request");
        if (repository.findActive(request.threadId()).isPresent()) {
            throw InteractionUseCase.InteractionException.of(InteractionUseCase.Failure.INVALID_STATE);
        }
        Instant now = clock.instant();
        String idempotency = request.idempotencyKey() == null
                ? "interaction-request-" + UUID.randomUUID() : request.idempotencyKey();
        return new InteractionRequest(request.requestId(), request.threadId(), request.turnId(),
                request.toolCallId(), request.planRevisionId(), request.runId(), request.goalId(), idempotency, request.questions(), InteractionStatus.PENDING,
                List.of(), 0, now, now);
    }

    /** 原子挂起提交成功后发布 CREATED，订阅者不会观察到未落库的 Interaction。 */
    public void publishCreated(InteractionRequest request, long eventSequence) {
        Objects.requireNonNull(request, "request");
        if (eventSequence < 1) throw new IllegalArgumentException("invalid interaction event sequence");
        publish(List.of(new InteractionEvent(request.threadId(), request.requestId(), request.revision(),
                eventSequence, InteractionEvent.Kind.CREATED, request.createdAt())));
    }

    /** 队列与 Interaction 在 Conversation 事务中一并变化后，只发布最新已提交事件。 */
    public void publishLatest(String threadId) {
        long sequence = repository.read(threadId, null).map(InteractionSnapshot::eventSequence).orElse(0L);
        if (sequence > 0) publish(repository.events(threadId, sequence - 1));
    }

    /** 读取权威请求、草稿和恢复状态，禁止从连接本地缓存拼接快照。 */
    @Override
    public Optional<InteractionSnapshot> read(String threadId, String requestId) {
        return repository.read(threadId, requestId);
    }

    /** 按 Thread 事件序列读取增量，缺口由调用方重新读取完整快照。 */
    @Override
    public List<InteractionEvent> observe(String threadId, long afterSequence) {
        if (threadId == null || afterSequence < 0) throw new IllegalArgumentException("invalid interaction observation");
        return repository.events(threadId, afterSequence);
    }

    /** 注册轻量观察者；订阅只传递已提交事件，不承担状态持久化责任。 */
    @Override
    public AutoCloseable subscribe(String threadId, Consumer<InteractionEvent> observer) {
        Subscription subscription = new Subscription(threadId, observer);
        subscriptions.add(subscription);
        return () -> subscriptions.remove(subscription);
    }

    /** 以 draft revision CAS 保存可恢复输入，失败时保留客户端本地内容。 */
    @Override
    public InteractionDraft saveDraft(String threadId, String requestId, List<InteractionAnswer> answers,
                                      int page, boolean collapsed, long expectedRevision,
                                      String idempotencyKey, Instant occurredAt) {
        long previousSequence = repository.read(threadId, requestId).map(InteractionSnapshot::eventSequence).orElse(0L);
        InteractionDraft draft = new InteractionDraft(threadId, requestId, answers, page, collapsed,
                idempotencyKey, expectedRevision, Objects.requireNonNull(occurredAt, "occurredAt"));
        InteractionDraft saved = repository.saveDraft(draft, expectedRevision, idempotencyKey);
        publish(repository.events(threadId, previousSequence));
        return saved;
    }

    /** 默认使用无连接 sink 提交答案，仍由同一恢复调度器处理 Turn 唤醒。 */
    @Override
    public InteractionRequest respond(String threadId, String requestId, long expectedRevision,
                                      List<InteractionAnswer> answers, String idempotencyKey, Instant occurredAt) {
        return respond(threadId, requestId, expectedRevision, answers, idempotencyKey, occurredAt,
                TurnEventSink.noop());
    }

    /** 在同一事务确认答案后调度原 Turn，幂等重试不得重复唤醒 Provider。 */
    @Override
    public InteractionRequest respond(String threadId, String requestId, long expectedRevision,
                                      List<InteractionAnswer> answers, String idempotencyKey, Instant occurredAt,
                                      TurnEventSink resumeSink) {
        long previousSequence = repository.read(threadId, requestId).map(InteractionSnapshot::eventSequence).orElse(0L);
        InteractionRequest current = repository.read(threadId, requestId)
                .flatMap(InteractionSnapshot::request)
                .orElseThrow(() -> InteractionUseCase.InteractionException.of(InteractionUseCase.Failure.NOT_FOUND));
        if (current.status() != InteractionStatus.PENDING) {
            if (current.status() == InteractionStatus.ANSWERED && current.idempotencyKey().equals(idempotencyKey)
                    && current.answers().equals(answers)) return current;
            throw InteractionUseCase.InteractionException.of(InteractionUseCase.Failure.INVALID_STATE);
        }
        InteractionRequest proposed = current.answer(answers, Objects.requireNonNull(occurredAt, "occurredAt"));
        ConversationRepository.InteractionAnswerReceipt receipt = conversations.respondInteraction(
                proposed, expectedRevision, idempotencyKey, occurredAt);
        InteractionRequest answered = receipt.request();
        publish(repository.events(threadId, previousSequence));
        if (receipt.newlySettled()) {
            resumeScheduler.get().schedule(answered.threadId(), answered.turnId(),
                    receipt.turnReceipt().threadRevision(), Objects.requireNonNull(resumeSink, "resumeSink"));
        }
        return answered;
    }

    /** 取消或替代待回答请求，并使迟到答案无法越过状态 CAS。 */
    @Override
    public InteractionRequest cancel(String threadId, String requestId, long expectedRevision,
                                     InteractionStatus status, String idempotencyKey, Instant occurredAt) {
        if (status != InteractionStatus.CANCELLED && status != InteractionStatus.SUPERSEDED) {
            throw InteractionUseCase.InteractionException.of(InteractionUseCase.Failure.INVALID_STATE);
        }
        long previousSequence = repository.read(threadId, requestId).map(InteractionSnapshot::eventSequence).orElse(0L);
        InteractionRequest closed = repository.close(threadId, requestId, expectedRevision, status, idempotencyKey,
                Objects.requireNonNull(occurredAt, "occurredAt"));
        publish(repository.events(threadId, previousSequence));
        return closed;
    }

    /** 事件仅在 Repository 提交后分发；订阅者故障不能回滚已经提交的 Interaction 事实。 */
    private void publish(List<InteractionEvent> events) {
        for (InteractionEvent event : events) {
            for (Subscription subscription : subscriptions) {
                if (subscription.threadId.equals(event.threadId())) {
                    try { subscription.observer.accept(event); } catch (RuntimeException ignored) { }
                }
            }
        }
    }

    /** 连接级订阅身份；线程过滤在发布时完成，避免跨 Thread 串事件。 */
    private record Subscription(String threadId, Consumer<InteractionEvent> observer) {
        /** 订阅必须绑定稳定 Thread 和非空回调，关闭操作才能精确移除。 */
        private Subscription {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(observer, "observer");
        }
    }

    /** 回答提交后的非阻塞恢复意图；实现必须自行等待旧 Turn owner 释放并执行幂等 CAS。 */
    @FunctionalInterface
    public interface ResumeScheduler {
        /** 提交后的恢复意图；实现负责等待旧 owner 释放并执行恢复 CAS。 */
        void schedule(String threadId, String turnId, long threadRevision, TurnEventSink resumeSink);
    }

    /** Tool 端创建请求所需的最小稳定身份和题目快照。 */
    public record CreateRequest(String requestId, String threadId, String turnId, String toolCallId,
                                String planRevisionId, String runId, String goalId,
                                String idempotencyKey, List<InteractionQuestion> questions) {
        /** Tool 传入的创建身份必须可在持久化与恢复链路中稳定关联。 */
        public CreateRequest {
            if (requestId == null || !requestId.startsWith("interaction_") || threadId == null
                    || turnId == null || toolCallId == null || questions == null) {
                throw new IllegalArgumentException("invalid interaction create request");
            }
            questions = List.copyOf(questions);
        }
    }

}
