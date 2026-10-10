// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.api.engine;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.TurnEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.kongweiguang.ja.agent.application.api.TurnEvent.ReasoningSummaryDelta;
import io.github.kongweiguang.ja.agent.application.api.TurnEvent.Reset;
import io.github.kongweiguang.ja.agent.application.api.TurnEvent.TextDelta;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** 在不运行模型的情况下验证临时订阅的内存、身份和断开边界，持久恢复另有真实存储测试。 */
class AgentStreamSubscriptionTest {
    private static final AgentIdentity ID = new AgentIdentity("tenant", "thread", "turn", "user");

    /** 允许在模型请求中途开始订阅，此后正文与摘要必须连续有序且不串请求。 */
    @Test
    void preservesOrderAndRequestIdentity() throws Exception {
        try (AgentStreamSubscription stream = new AgentStreamSubscription(ID, 3)) {
            TextDelta text = text("request", 20, "正文");
            ReasoningSummaryDelta reasoning = new TurnEvent.ReasoningSummaryDelta(context("request", 21), "摘要");
            Reset reset = new TurnEvent.Reset(context("request", 22), "TRANSIENT_FAILURE");
            stream.publish(text);
            stream.publish(reasoning);
            stream.publish(reset);
            assertEquals(text, stream.poll(Duration.ZERO).event().orElseThrow());
            assertEquals(reasoning, stream.poll(Duration.ZERO).event().orElseThrow());
            assertEquals(reset, stream.poll(Duration.ZERO).event().orElseThrow());
            assertTrue(stream.poll(Duration.ZERO).event().isEmpty());
            assertEquals(AgentStreamSubscription.State.OPEN, stream.state());
        }
    }

    /** 慢消费者不能让无限草稿占用内存，也不能继续拿缺片正文当成完整输出。 */
    @Test
    void overflowRequiresResynchronizationWithoutBlockingPublisher() throws Exception {
        try (AgentStreamSubscription stream = new AgentStreamSubscription(ID, 1)) {
            stream.publish(text("request_a", 1, "first"));
            stream.publish(text("request_b", 2, "second"));
            AgentStreamSubscription.PollResult result = stream.poll(Duration.ofSeconds(1));
            assertEquals(AgentStreamSubscription.State.RESYNC_REQUIRED, result.state());
            assertTrue(result.event().isEmpty());
        }
    }

    /** 同类别同请求的数千个微小 Unicode 片段节省队列槽位且保持字符与末序号完整。 */
    @Test
    void coalescesTinyUnicodeBurstWithoutLosingSequenceOrText() throws Exception {
        String[] fragments = {"🙂", "e\u0301", "中"};
        StringBuilder expected = new StringBuilder();
        try (AgentStreamSubscription stream = new AgentStreamSubscription(ID, 2)) {
            for (int sequence = 1; sequence <= 5_000; sequence++) {
                String fragment = fragments[(sequence - 1) % fragments.length];
                expected.append(fragment);
                stream.publish(text("request", sequence, fragment));
            }

            StringBuilder actual = new StringBuilder();
            int frames = 0;
            long lastSequence = 0;
            AgentStreamSubscription.PollResult result;
            while ((result = stream.poll(Duration.ZERO)).event().isPresent()) {
                TextDelta frame = (TextDelta) result.event().orElseThrow();
                assertTrue(frame.text().length() <= 8_192);
                actual.append(frame.text());
                lastSequence = frame.context().sequence();
                frames++;
            }
            assertEquals(expected.toString(), actual.toString());
            assertEquals(5_000, lastSequence);
            assertTrue(frames < 5_000);
            assertEquals(AgentStreamSubscription.State.OPEN, stream.state());
        }
    }

    /** 类型、模型请求和 Reset 都是合并边界，且合并上下文指向区间最后一片。 */
    @Test
    void keepsKindRequestAndResetBoundaries() throws Exception {
        try (AgentStreamSubscription stream = new AgentStreamSubscription(ID, 6)) {
            stream.publish(text("request_a", 1, "A"));
            stream.publish(text("request_a", 2, "B"));
            stream.publish(text("request_b", 3, "C"));
            stream.publish(new ReasoningSummaryDelta(context("request_b", 4), "R"));
            stream.publish(new ReasoningSummaryDelta(context("request_b", 5), "S"));
            stream.publish(text("request_b", 6, "D"));
            stream.publish(new Reset(context("request_b", 7), "TRANSIENT_FAILURE"));
            stream.publish(text("request_b", 8, "E"));

            TextDelta merged = (TextDelta) stream.poll(Duration.ZERO).event().orElseThrow();
            assertEquals("AB", merged.text());
            assertEquals(2, merged.context().sequence());
            assertEquals("C", ((TextDelta) stream.poll(Duration.ZERO).event().orElseThrow()).text());
            ReasoningSummaryDelta mergedReasoning = (ReasoningSummaryDelta) stream.poll(Duration.ZERO).event().orElseThrow();
            assertEquals("RS", mergedReasoning.text());
            assertEquals(5, mergedReasoning.context().sequence());
            assertEquals("D", ((TextDelta) stream.poll(Duration.ZERO).event().orElseThrow()).text());
            assertTrue(stream.poll(Duration.ZERO).event().orElseThrow() instanceof Reset);
            assertEquals("E", ((TextDelta) stream.poll(Duration.ZERO).event().orElseThrow()).text());
            assertEquals(AgentStreamSubscription.State.OPEN, stream.state());
        }
    }

    /** 恰好 8192 个 UTF-16 code unit 可合并，原有 65536 单片独立保留且不截断。 */
    @Test
    void respectsCoalescedAndStandaloneFrameBounds() throws Exception {
        String large = "x".repeat(65_536);
        try (AgentStreamSubscription stream = new AgentStreamSubscription(ID, 4)) {
            stream.publish(text("request", 1, "a".repeat(8_192)));
            stream.publish(text("request", 2, "b"));
            stream.publish(text("request", 3, large));

            TextDelta bounded = (TextDelta) stream.poll(Duration.ZERO).event().orElseThrow();
            assertEquals(8_192, bounded.text().length());
            TextDelta next = (TextDelta) stream.poll(Duration.ZERO).event().orElseThrow();
            assertEquals("b", next.text());
            TextDelta standalone = (TextDelta) stream.poll(Duration.ZERO).event().orElseThrow();
            assertEquals(large, standalone.text());
            assertEquals(3, standalone.context().sequence());
        }
    }

    /** 拒绝乱序和重复；租约接管后的合法序号区间跳跃不冒充缓冲溢出。 */
    @Test
    void rejectsNonIncreasingSequence() {
        for (long sequence : new long[]{1, 2}) {
            try (AgentStreamSubscription stream = new AgentStreamSubscription(ID, 4)) {
                stream.publish(delta(2));
                stream.publish(delta(sequence));
                assertEquals(AgentStreamSubscription.State.RESYNC_REQUIRED, stream.state());
            }
        }
        try (AgentStreamSubscription stream = new AgentStreamSubscription(ID, 4)) {
            stream.publish(delta(2));
            stream.publish(delta((1L << 32) + 1));
            assertEquals(AgentStreamSubscription.State.OPEN, stream.state());
        }
    }

    /** 订阅关闭只唤醒消费者，不需要取消令牌或任何任务控制权。 */
    @Test
    void closeWakesWaitingConsumer() throws Exception {
        try (AgentStreamSubscription stream = new AgentStreamSubscription(ID, 1);
             ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch entered = new CountDownLatch(1);
            AtomicReference<Thread> pollingThread = new AtomicReference<>();
            Future<AgentStreamSubscription.PollResult> pending = workers.submit(() -> {
                pollingThread.set(Thread.currentThread());
                entered.countDown();
                return stream.poll(Duration.ofMinutes(1));
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (pollingThread.get().getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(Thread.State.TIMED_WAITING, pollingThread.get().getState());
            stream.close();
            assertEquals(AgentStreamSubscription.State.CLOSED, pending.get(2, TimeUnit.SECONDS).state());
            stream.close();
            stream.publish(delta(1));
            assertTrue(stream.poll(Duration.ZERO).event().isEmpty());
        }
    }

    /** 全身份匹配保护租户和调用者隔离，不能只检查 turnId。 */
    @Test
    void rejectsCrossTenantEvent() {
        try (AgentStreamSubscription stream = new AgentStreamSubscription(ID, 2)) {
            AgentIdentity other = new AgentIdentity("other", ID.threadId(), ID.turnId(), ID.userId());
            TextDelta event = new TurnEvent.TextDelta(new TurnEvent.Context(other, "request", 1, Instant.EPOCH), "x");
            assertThrows(IllegalArgumentException.class, () -> stream.publish(event));
            assertEquals(AgentStreamSubscription.State.OPEN, stream.state());
        }
    }

    /** 容量、等待时长和片段大小在入口拒绝，不依靠后台线程默默截断内容。 */
    @Test
    void validatesBounds() {
        assertThrows(IllegalArgumentException.class, () -> new AgentStreamSubscription(ID, 257));
        assertThrows(IllegalArgumentException.class, () -> new TurnEvent.TextDelta(context("request", 1), "x".repeat(65_537)));
        try (AgentStreamSubscription stream = new AgentStreamSubscription(ID, 1)) {
            assertThrows(IllegalArgumentException.class, () -> stream.poll(Duration.ofMinutes(2)));
        }
    }

    /** 固定时钟和请求身份，让测试只关注队列与执行流的关联约束。 */
    private static TurnEvent.Context context(String modelRequestId, long sequence) {
        return new TurnEvent.Context(ID, modelRequestId, sequence, Instant.EPOCH);
    }

    /** 构造真实公开事件而非队列内部对象，覆盖 API 校验到订阅的完整边界。 */
    private static TurnEvent.TextDelta delta(long sequence) {
        return new TurnEvent.TextDelta(context("request", sequence), "正文");
    }

    /** 为边界用例保留独立请求身份和源序号，避免测试辅助方法掩盖真实合并键。 */
    private static TurnEvent.TextDelta text(String request, long sequence, String body) {
        return new TurnEvent.TextDelta(context(request, sequence), body);
    }
}
