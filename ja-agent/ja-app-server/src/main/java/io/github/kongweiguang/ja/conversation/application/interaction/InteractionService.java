// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.interaction;

import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentAnswer;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.StoreConflictException;
import io.github.kongweiguang.ja.conversation.application.service.JaTurnRequestMapper;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionDraft;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionEvent;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionRequest;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionSnapshot;
import io.github.kongweiguang.ja.conversation.application.api.InteractionUseCase;
import io.github.kongweiguang.ja.conversation.application.api.NativeExecutionContext;
import io.github.kongweiguang.ja.conversation.application.gateway.InteractionRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Interaction 应用服务；把输入校验、唯一活动请求和 Repository CAS 集中在一个边界。
 */
public final class InteractionService implements InteractionUseCase,
        io.github.kongweiguang.ja.agent.application.gateway.AgentEventListener {
    private static final System.Logger LOGGER = System.getLogger(InteractionService.class.getName());
    private final InteractionRepository repository;
    private final AtomicReference<AgentEngine> engine = new AtomicReference<>();
    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final AtomicReference<ResumeScheduler> resumeScheduler = new AtomicReference<>();

    /**
     * 时间与执行控制归唯一 Engine；本服务只借用投影 Repository，不持有第二份时钟或连接。
     */
    public InteractionService(InteractionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * 配置解析器会引用本服务，后置绑定唯一 Engine 打破构造环，但运行时不允许缺少持久执行者。
     */
    public void bindEngine(AgentEngine value) {
        if (!engine.compareAndSet(null, Objects.requireNonNull(value, "engine"))) {
            throw new IllegalStateException("interaction engine already bound");
        }
    }

    /**
     * 缺少组合根接线时立即失败，不退回旧 Repository 独立写入路径。
     */
    private AgentEngine engine() {
        AgentEngine value = engine.get();
        if (value == null) {
            throw new IllegalStateException("interaction engine is not bound");
        }
        return value;
    }

    /**
     * 由组合根在 TurnService 创建后绑定，打破构造环；未绑定时不得受理需要继续执行的回答。
     */
    public void bindResumeScheduler(ResumeScheduler scheduler) {
        Objects.requireNonNull(scheduler, "scheduler");
        if (!resumeScheduler.compareAndSet(null, scheduler)) {
            throw new IllegalStateException("interaction resume scheduler is already bound");
        }
    }

    /**
     * 队列与 Interaction 在 Conversation 事务中一并变化后，只发布最新已提交事件。
     */
    public void publishLatest(String threadId) {
        long sequence = repository.read(threadId, null).map(InteractionSnapshot::eventSequence).orElse(0L);
        if (sequence > 0) {
            publish(repository.events(threadId, sequence - 1));
        }
    }

    /**
     * 稳定监听身份让重复组合根接线在注册阶段失败。
     */
    @Override
    public String id() {
        return "ja.interaction.events";
    }

    /**
     * 只读取事务已生成的 Ja 通知；合并通知不改变持久事件及其重连游标。
     */
    @Override
    public void onEvent(io.github.kongweiguang.ja.agent.application.gateway.AgentEvent event) {
        String type = event.persistedEvent().eventType();
        if ("agent.wait.requested".equals(type) || "agent.wait.resolved".equals(type)) {
            publishLatest(event.identity().threadId());
        }
    }

    /**
     * 读取权威请求、草稿和恢复状态，禁止从连接本地缓存拼接快照。
     */
    @Override
    public Optional<InteractionSnapshot> read(String threadId, String interactionId) {
        return repository.read(threadId, interactionId);
    }

    /**
     * 按 Thread 事件序列读取增量，缺口由调用方重新读取完整快照。
     */
    @Override
    public List<InteractionEvent> observe(String threadId, long afterSequence) {
        if (threadId == null || afterSequence < 0)
            {
                throw new IllegalArgumentException("invalid interaction observation");
            }
        return repository.events(threadId, afterSequence);
    }

    /**
     * 注册连接级观察者并把关闭句柄交给调用方；加入集合失败时立即停止已启动的虚拟线程。
     * RPC 会话或 Plan continuation 在断连、终态或 admission 失败时释放句柄，挂起期间保留以支持恢复。
     */
    @SuppressWarnings("PMD.CloseResource")
    @Override
    public AutoCloseable subscribe(String threadId, Consumer<InteractionEvent> observer) {
        Subscription subscription = new Subscription(threadId, observer);
        try {
            subscriptions.add(subscription);
            return () -> {
                subscriptions.remove(subscription);
                subscription.close();
            };
        } catch (RuntimeException | Error failure) {
            subscription.close();
            throw failure;
        }
    }

    /**
     * 先确认 Interaction 仍属于指定 Thread，再以 draftRevision CAS 保存；缺失 owner 必须稳定映射为 NOT_FOUND。
     */
    @Override
    public InteractionDraft saveDraft(String threadId, String interactionId, List<InteractionAnswer> answers,
                                      int page, boolean collapsed, long expectedDraftRevision,
                                      String operationKey, Instant occurredAt) {
        InteractionSnapshot current = repository.read(threadId, interactionId)
                .orElseThrow(() -> InteractionUseCase.InteractionException.of(InteractionUseCase.Failure.NOT_FOUND));
        long previousSequence = current.eventSequence();
        InteractionDraft draft = new InteractionDraft(threadId, interactionId, answers, page, collapsed,
                operationKey, expectedDraftRevision, Objects.requireNonNull(occurredAt, "occurredAt"));
        InteractionDraft saved = repository.saveDraft(draft, expectedDraftRevision, operationKey);
        publish(repository.events(threadId, previousSequence));
        return saved;
    }

    /**
     * 默认使用无连接 sink 提交答案，仍由同一恢复调度器处理 Turn 唤醒。
     */
    @Override
    public InteractionRequest respond(String threadId, String interactionId, long expectedInteractionRevision,
                                      List<InteractionAnswer> answers, String operationKey, Instant occurredAt) {
        return respond(threadId, interactionId, expectedInteractionRevision, answers, operationKey, occurredAt,
                ConversationTurnEventSink.noop());
    }

    /**
     * 初次回答由聚合校验完整性；已回答重试仍交 Core 持久回执核对键与请求指纹，避免把创建键误作答案幂等键。
     * Store 冲突映射为领域闭集错误并保留 cause；Transport 只公开稳定分类，不泄漏底层诊断。
     */
    @Override
    public InteractionRequest respond(String threadId, String interactionId, long expectedInteractionRevision,
                                      List<InteractionAnswer> answers, String operationKey, Instant occurredAt,
                                      ConversationTurnEventSink resumeSink) {
        ResumeScheduler scheduler = Objects.requireNonNull(resumeScheduler.get(), "interaction resume scheduler not bound");
        Objects.requireNonNull(resumeSink, "resumeSink");
        NativeExecutionContext nativeContext = NativeExecutionContext.shared();
        NativeExecutionSnapshot nativeSnapshot = nativeContext.current().orElse(null);
        if (nativeContext.sharedMode() && nativeSnapshot == null) {
            throw new IllegalStateException("authenticated native context required to resume interaction");
        }
        long previousSequence = repository.read(threadId, interactionId).map(InteractionSnapshot::eventSequence).orElse(0L);
        InteractionRequest current = repository.read(threadId, interactionId)
                .flatMap(InteractionSnapshot::request)
                .orElseThrow(() -> InteractionUseCase.InteractionException.of(InteractionUseCase.Failure.NOT_FOUND));
        if (current.status() != InteractionStatus.PENDING && current.status() != InteractionStatus.ANSWERED) {
            throw InteractionUseCase.InteractionException.of(InteractionUseCase.Failure.INVALID_STATE);
        }
        List<InteractionAnswer> submittedAnswers = List.copyOf(Objects.requireNonNull(answers, "answers"));
        InteractionRequest expectedAnswer = current.status() == InteractionStatus.PENDING
                ? current.answer(submittedAnswers, Objects.requireNonNull(occurredAt, "occurredAt")) : current;
        if (current.status() == InteractionStatus.PENDING
            && current.interactionRevision() != expectedInteractionRevision) {
            throw InteractionUseCase.InteractionException.of(InteractionUseCase.Failure.INVALID_STATE);
        }
        AgentIdentity identity = JaTurnRequestMapper.identity(threadId, current.turnId());
        try {
            engine().answer(identity, new AgentAnswer(interactionId, operationKey, expectedInteractionRevision,
                            submittedAnswers, Map.of("ja.operationKey", operationKey,
                                    "ja.expectedInteractionRevision", Long.toString(expectedInteractionRevision))))
                    .toCompletableFuture().join();
        } catch (CompletionException failure) {
            Optional<StoreConflictException> conflict = storeConflictCause(failure);
            if (current.status() != InteractionStatus.ANSWERED || conflict.isEmpty()) {
                throw failure;
            }
            InteractionUseCase.Failure code = expectedInteractionRevision != current.interactionRevision() - 1
                    || current.answers().equals(submittedAnswers)
                    ? InteractionUseCase.Failure.REVISION_CONFLICT
                    : InteractionUseCase.Failure.INVALID_STATE;
            throw InteractionUseCase.InteractionException.of(code, conflict.orElseThrow());
        }
        InteractionRequest answered = repository.read(threadId, interactionId).flatMap(InteractionSnapshot::request)
                .orElseThrow(() -> new IllegalStateException("committed interaction projection missing"));
        if (answered.status() != InteractionStatus.ANSWERED || !answered.answers().equals(expectedAnswer.answers())) {
            throw new IllegalStateException("committed interaction projection differs from answer");
        }
        publish(repository.events(threadId, previousSequence));
        scheduler.schedule(answered.threadId(), answered.turnId(),
                engine().query(identity).orElseThrow().threadRevision(), resumeSink, nativeSnapshot);
        return answered;
    }

    /** 异步控制阶段可能包装存储冲突；提取强类型 cause 而不依赖诊断文本。 */
    private static Optional<StoreConflictException> storeConflictCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof StoreConflictException conflict) {
                return Optional.of(conflict);
            }
            if (cause.getCause() == cause) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * 取消或替代待回答请求，并使迟到答案无法越过状态 CAS。
     */
    @Override
    public InteractionRequest cancel(String threadId, String interactionId, long expectedInteractionRevision,
                                     InteractionStatus status, String operationKey, Instant occurredAt) {
        if (status != InteractionStatus.CANCELLED && status != InteractionStatus.SUPERSEDED) {
            throw InteractionUseCase.InteractionException.of(InteractionUseCase.Failure.INVALID_STATE);
        }
        long previousSequence = repository.read(threadId, interactionId).map(InteractionSnapshot::eventSequence).orElse(0L);
        Objects.requireNonNull(occurredAt, "occurredAt");
        InteractionRequest current = repository.read(threadId, interactionId).flatMap(InteractionSnapshot::request)
                .orElseThrow(() -> InteractionUseCase.InteractionException.of(InteractionUseCase.Failure.NOT_FOUND));
        engine().closeWait(JaTurnRequestMapper.identity(threadId, current.turnId()), interactionId,
                expectedInteractionRevision,
                status, operationKey, Map.of("ja.operationKey", operationKey,
                        "ja.expectedInteractionRevision", Long.toString(expectedInteractionRevision)))
                .toCompletableFuture().join();
        InteractionRequest closed = repository.read(threadId, interactionId).flatMap(InteractionSnapshot::request)
                .orElseThrow(() -> new IllegalStateException("committed interaction projection missing"));
        publish(repository.events(threadId, previousSequence));
        return closed;
    }

    /**
     * 事件仅在 Repository 提交后分发；集合中的订阅由注册者持有，发布只借用并唤醒它们，不能在此关闭。
     */
    @SuppressWarnings("PMD.CloseResource")
    private void publish(List<InteractionEvent> events) {
        for (InteractionEvent event : events) {
            for (Subscription subscription : subscriptions) {
                if (subscription.threadId.equals(event.threadId())) {
                    subscription.offer(event);
                }
            }
        }
    }

    /**
     * 回答提交后的非阻塞恢复意图；实现必须自行等待旧 Turn owner 释放并执行幂等 CAS。
     */
    @FunctionalInterface
    public interface ResumeScheduler {
        /**
         * 提交后的恢复意图；实现负责等待旧 owner 释放并执行恢复 CAS。
         */
        void schedule(String threadId, String turnId, long threadRevision,
                      ConversationTurnEventSink resumeSink,
                      io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot nativeContext);
    }

    /**
     * 连接级订阅身份；线程过滤在发布时完成，避免跨 Thread 串事件。
     */
    private static final class Subscription implements AutoCloseable {
        private final String threadId;
        private final Consumer<InteractionEvent> observer;
        private final java.util.concurrent.Semaphore wake = new java.util.concurrent.Semaphore(0);
        private final AtomicReference<InteractionEvent> pending = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Thread worker;
        private long lastSequence;

        /**
         * 通知仅使客户端快照失效，因此每个慢订阅只保留最新一条，不阻塞业务提交线程。
         */
        private Subscription(String threadId, Consumer<InteractionEvent> observer) {
            this.threadId = Objects.requireNonNull(threadId, "threadId");
            this.observer = Objects.requireNonNull(observer, "observer");
            worker = Thread.ofVirtual().name("ja-interaction-events").start(this::dispatch);
        }

        /**
         * 重复或乱序通知可丢弃，完整历史仍由持久 observe 接口按游标读取。
         */
        private synchronized void offer(InteractionEvent event) {
            if (closed.get() || event.eventSequence() <= lastSequence) {
                return;
            }
            lastSequence = event.eventSequence();
            if (pending.getAndSet(event) == null) {
                wake.release();
            }
        }

        /**
         * 只读监听异常独立报告，不能伪装成已提交回答失败或吞掉诊断。
         */
        private void dispatch() {
            try {
                while (!closed.get()) {
                    wake.acquire();
                    InteractionEvent event = pending.getAndSet(null);
                    if (event != null && !closed.get()) {
                        try {
                            observer.accept(event);
                        } catch (RuntimeException failure) {
                            LOGGER.log(System.Logger.Level.WARNING, "Interaction subscriber failed", failure);
                        }
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * 关闭连接只释放观察线程，不取消对应 Agent 或修改等待状态。
         */
        @Override
        public void close() {
            closed.set(true);
            pending.set(null);
            worker.interrupt();
        }
    }

}
