// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.interaction;

import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestion;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestionType;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionEvent;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionRequest;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionResumeState;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionSnapshot;
import io.github.kongweiguang.ja.conversation.application.api.InteractionUseCase;
import io.github.kongweiguang.ja.conversation.application.gateway.InteractionRepository;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通知仅作为快照失效信号，慢消费者不得占住业务线程或积累无界缓存。 */
class InteractionObservationTest {
    /** 阻塞第一条通知期间提交大量事件，只保留最后水位，释放后客户端仍能回读最新快照。 */
    @Test void coalescesSlowObserverWithoutBlockingPublisher() throws Exception {
        AtomicInteger latestSequence = new AtomicInteger();
        InteractionRequest request = new InteractionRequest("interaction_test", "thr_test", "turn_test",
                "call_test", null, null, "op_test",
                List.of(new InteractionQuestion("question_test", "请输入", InteractionQuestionType.TEXT,
                        List.of(), true, true)), InteractionStatus.PENDING, List.of(), 0,
                Instant.parse("2026-09-26T00:00:00Z"), Instant.parse("2026-09-26T00:00:00Z"));
        InteractionService service = new InteractionService(repository(request, latestSequence));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Long> latest = new CompletableFuture<>();
        AtomicInteger deliveries = new AtomicInteger();
        try (AutoCloseable subscription = service.subscribe("thr_test", event -> {
            deliveries.incrementAndGet();
            if (event.eventSequence() == 1) {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            } else {
                latest.complete(event.eventSequence());
            }
        })) {
            latestSequence.set(1);
            service.publishLatest("thr_test");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            CompletableFuture<Void> submitted = CompletableFuture.runAsync(() -> {
                for (int sequence = 2; sequence <= 1_000; sequence++) {
                    latestSequence.set(sequence);
                    service.publishLatest("thr_test");
                }
            });
            submitted.get(2, TimeUnit.SECONDS);
            release.countDown();
            assertEquals(1_000L, latest.get(2, TimeUnit.SECONDS));
            assertEquals(2, deliveries.get());
        } finally {
            release.countDown();
        }
    }

    /** 缺失或错属 Interaction 用稳定 NOT_FOUND 结束，不得尝试写入草稿。 */
    @Test void missingInteractionDoesNotAttemptDraftWrite() {
        AtomicInteger writeCalls = new AtomicInteger();
        InteractionRepository repository = (InteractionRepository) Proxy.newProxyInstance(
                InteractionRepository.class.getClassLoader(), new Class<?>[] {InteractionRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("read")) {
                        return Optional.empty();
                    }
                    if (method.getName().equals("saveDraft")) {
                        writeCalls.incrementAndGet();
                        throw new AssertionError("missing Interaction must not reach draft persistence");
                    }
                    throw new AssertionError("unexpected repository method: " + method.getName());
                });
        InteractionService service = new InteractionService(repository);

        InteractionUseCase.InteractionException failure = assertThrows(
                InteractionUseCase.InteractionException.class,
                () -> service.saveDraft("thr_missing", "interaction_missing", List.of(), 0, false, 0,
                        "op_missing", Instant.parse("2026-09-26T00:00:00Z")));

        assertEquals(InteractionUseCase.Failure.NOT_FOUND, failure.code());
        assertEquals(0, writeCalls.get());
    }

    /** 发布端只从 Repository 的持久快照和事件游标读取通知，不模拟旧的直接创建路径。 */
    private static InteractionRepository repository(InteractionRequest request, AtomicInteger latestSequence) {
        return (InteractionRepository) Proxy.newProxyInstance(InteractionRepository.class.getClassLoader(),
                new Class<?>[] {InteractionRepository.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("read")) {
                        long sequence = latestSequence.get();
                        return Optional.of(new InteractionSnapshot("thr_test", sequence, Optional.of(request),
                                Optional.empty(), InteractionResumeState.WAITING_FOR_ANSWER));
                    }
                    if (method.getName().equals("events")) {
                        long sequence = latestSequence.get();
                        long after = (long) arguments[1];
                        return sequence > after ? List.of(new InteractionEvent("thr_test", request.interactionId(),
                                request.interactionRevision(), sequence, InteractionEvent.Kind.CREATED,
                                request.createdAt()))
                                : List.of();
                    }
                    throw new AssertionError("unexpected repository method: " + method.getName());
                });
    }
}
