// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.engine;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.support.JsonTestValues;
import io.github.kongweiguang.ja.agent.application.gateway.AgentEvent;
import io.github.kongweiguang.ja.agent.application.gateway.AgentEventListener;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证观察器各自有界串行、不会阻塞 Loop，并能显式报告持久事件重读区间。 */
final class AgentEventListenerDispatcherTest {
    /** 一个阻塞观察器不能阻塞另一个 listener 或事件提交调用线程。 */
    @Test
    void isolatesBlockedListenerAndKeepsEachListenerOrdered() throws Exception {
        AgentEventListenerDispatcher dispatcher = new AgentEventListenerDispatcher(4);
        AgentIdentity identity = identity("turn_blocked");
        CountDownLatch blockedEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocked = new CountDownLatch(1);
        CountDownLatch fastDelivered = new CountDownLatch(2);
        AtomicInteger slowOrder = new AtomicInteger();
        AgentEventListener slow = listener("slow", event -> {
            assertEquals(event.persistedEvent().sequence(), slowOrder.incrementAndGet());
            if (event.persistedEvent().sequence() == 1) {
                blockedEntered.countDown();
                try {
                    releaseBlocked.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("listener test worker was interrupted", interrupted);
                }
            }
        });
        AgentEventListener fast = listener("fast", event -> fastDelivered.countDown());
        try {
            dispatcher.dispatch(List.of(slow, fast), event(identity, 1));
            assertTrue(blockedEntered.await(1, TimeUnit.SECONDS));
            long started = System.nanoTime();
            dispatcher.dispatch(List.of(slow, fast), event(identity, 2));
            assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(100));
            assertTrue(fastDelivered.await(1, TimeUnit.SECONDS));
            releaseBlocked.countDown();
            awaitCount(slowOrder, 2);
        } finally {
            releaseBlocked.countDown();
            dispatcher.finishExecution(identity);
            dispatcher.close();
        }
        assertEquals(2, slowOrder.get());
    }

    /** 有界队列溢出会通知精确的重读水位，并在补读成功前抑制后续增量。 */
    @Test
    void overflowRequestsStoreReplayThroughTheDroppedWatermark() throws Exception {
        AgentEventListenerDispatcher dispatcher = new AgentEventListenerDispatcher(1);
        AgentIdentity identity = identity("turn_overflow");
        CountDownLatch blockedEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocked = new CountDownLatch(1);
        CountDownLatch replayRequested = new CountDownLatch(1);
        AtomicReference<AgentEventListener.ReplayRequired> replay = new AtomicReference<>();
        AtomicInteger delivered = new AtomicInteger();
        AgentEventListener listener = new AgentEventListener() {
            /** 固定监听器身份，使待测队列溢出只归属于这一订阅。 */
            @Override
            public String id() {
                return "overflow-test";
            }

            /** 首个事件阻塞到测试释放，确定性制造容量为一的队列缺口。 */
            @Override
            public void onEvent(AgentEvent event) {
                delivered.incrementAndGet();
                if (event.persistedEvent().sequence() == 1) {
                    blockedEntered.countDown();
                    try {
                        releaseBlocked.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            /** 保存重读通知，供测试验证其上界覆盖所有被丢弃的增量。 */
            @Override
            public void onReplayRequired(ReplayRequired required) {
                replay.set(required);
                replayRequested.countDown();
            }
        };
        try {
            dispatcher.dispatch(List.of(listener), event(identity, 1));
            assertTrue(blockedEntered.await(1, TimeUnit.SECONDS));
            dispatcher.dispatch(List.of(listener), event(identity, 2));
            dispatcher.dispatch(List.of(listener), event(identity, 3));
            releaseBlocked.countDown();
            assertTrue(replayRequested.await(1, TimeUnit.SECONDS));
            AgentEventListener.ReplayRequired required = replay.get();
            assertEquals(identity, required.identity());
            assertEquals(0, required.afterSequence());
            assertEquals(3, required.throughSequence());
            assertEquals("queue_full", required.reasonCode());
            assertEquals(1, delivered.get());
        } finally {
            releaseBlocked.countDown();
            dispatcher.finishExecution(identity);
            dispatcher.close();
        }
    }

    /** 观察回调抛错不会穿透提交调用；重同步原因使用稳定码，不携带异常正文。 */
    @Test
    void callbackFailureRequiresExplicitReplayHandling() throws Exception {
        AgentEventListenerDispatcher dispatcher = new AgentEventListenerDispatcher(4);
        AgentIdentity identity = identity("turn_failure");
        CountDownLatch replayRequested = new CountDownLatch(1);
        AtomicInteger callbackCount = new AtomicInteger();
        AtomicReference<AgentEventListener.ReplayRequired> replay = new AtomicReference<>();
        AgentEventListener listener = new AgentEventListener() {
            /** 提供独立观察者身份，避免失败场景与其它 dispatcher 队列串线。 */
            @Override
            public String id() {
                return "failure-test";
            }

            /** 首次回调故意失败，验证失败后 dispatcher 要求显式补读。 */
            @Override
            public void onEvent(AgentEvent event) {
                if (callbackCount.incrementAndGet() == 1) {
                    throw new IllegalStateException("observer failed");
                }
            }

            /** 记录缺口水位，确保观察异常不会被伪装成已成功投递。 */
            @Override
            public void onReplayRequired(ReplayRequired required) {
                replay.set(required);
                replayRequested.countDown();
            }
        };
        try {
            dispatcher.dispatch(List.of(listener), event(identity, 7));
            dispatcher.dispatch(List.of(listener), event(identity, 8));
            assertTrue(replayRequested.await(1, TimeUnit.SECONDS));
            assertEquals(0, replay.get().afterSequence());
            assertEquals(7, replay.get().throughSequence());
            assertEquals("listener_failed", replay.get().reasonCode());
            awaitCount(callbackCount, 2);
            assertEquals(2, callbackCount.get());
        } finally {
            dispatcher.finishExecution(identity);
            dispatcher.close();
        }
    }

    /** listener 消息可能回显事件载荷，因此两类回调日志只保留阶段类别与 JVM 异常类身份。 */
    @Test
    void listenerFailuresLogOnlySafeExceptionIdentity() throws Exception {
        String eventSecret = "event-payload-secret-928";
        String replaySecret = "replay-payload-secret-928";
        ByteArrayOutputStream logOutput = new ByteArrayOutputStream();
        StreamHandler handler = new StreamHandler(logOutput, new SimpleFormatter());
        Logger logger = Logger.getLogger(AgentEventListenerDispatcher.class.getName());
        Level previousLevel = logger.getLevel();
        boolean previousParentHandlers = logger.getUseParentHandlers();
        AgentEventListenerDispatcher dispatcher = new AgentEventListenerDispatcher(4);
        AgentIdentity identity = identity("turn_log_redaction");
        CountDownLatch replayAttempted = new CountDownLatch(1);
        AgentEventListener listener = new AgentEventListener() {
            /** 提供固定测试身份，避免日志断言与监听器动态标签有关。 */
            @Override
            public String id() {
                return "log-redaction-test";
            }

            /** 用载荷哨兵作为异常正文，确保 dispatcher 不会把扩展消息直接写入日志。 */
            @Override
            public void onEvent(AgentEvent event) {
                throw new IllegalStateException(eventSecret);
            }

            /** 用第二个哨兵覆盖重放回调失败路径，并让测试等待异步回调完成。 */
            @Override
            public void onReplayRequired(ReplayRequired required) {
                replayAttempted.countDown();
                throw new IllegalArgumentException(replaySecret);
            }
        };
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        try {
            try {
                dispatcher.dispatch(List.of(listener), event(identity, 1));
                assertTrue(replayAttempted.await(1, TimeUnit.SECONDS));
            } finally {
                try {
                    dispatcher.finishExecution(identity);
                } finally {
                    dispatcher.close();
                }
            }
            handler.flush();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousParentHandlers);
            handler.close();
        }

        String logs = logOutput.toString(StandardCharsets.UTF_8);
        assertFalse(logs.contains(eventSecret));
        assertFalse(logs.contains(replaySecret));
        assertTrue(logs.contains("listener callback failed"));
        assertTrue(logs.contains("listener replay handler failed"));
        assertTrue(logs.contains(IllegalStateException.class.getName()));
        assertTrue(logs.contains(IllegalArgumentException.class.getName()));
    }

    /** 在有限期限内等候异步通知数量，避免测试依赖 Thread.sleep 调度猜测。 */
    private static void awaitCount(AtomicInteger count, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (count.get() < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    /** 创建只读 listener，测试无需依赖扩展注册器或具体业务投影。 */
    private static AgentEventListener listener(String id, java.util.function.Consumer<AgentEvent> consumer) {
        return new AgentEventListener() {
            /** 使用调用方给定身份，保持每个测试订阅的派发队列稳定。 */
            @Override
            public String id() {
                return id;
            }

            /** 将事件交给断言回调，避免测试绕开 dispatcher 直接调用监听逻辑。 */
            @Override
            public void onEvent(AgentEvent event) {
                consumer.accept(event);
            }
        };
    }

    /** 为重放测试使用完整调用身份，避免跨租户事件混淆。 */
    private static AgentIdentity identity(String execution) {
        return new AgentIdentity("tenant_test", "thread_test", execution, "user_test");
    }

    /** 构造具备有效会话水位的持久事件，不依赖数据库序号分配器。 */
    private static AgentEvent event(AgentIdentity identity, long sequence) {
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(
                identity.tenantId(), identity.threadId(), identity.userId());
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(conversation, identity.turnId());
        AgentStore.PersistedEvent persisted = new AgentStore.PersistedEvent(conversation, execution,
                "event_" + sequence, "state_changed", 1, sequence, sequence, sequence, sequence,
                JsonTestValues.object("{}"),
                Instant.EPOCH.plusSeconds(sequence));
        return new AgentEvent(identity, persisted);
    }
}
