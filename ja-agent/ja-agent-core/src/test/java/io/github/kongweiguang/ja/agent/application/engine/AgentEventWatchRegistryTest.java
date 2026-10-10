// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.engine;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentEventPage;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentEventWatch;
import io.github.kongweiguang.ja.agent.application.gateway.AgentEvent;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.support.JsonTestValues;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证水位竞态、有界唤醒语义及租户和执行事件过滤。 */
final class AgentEventWatchRegistryTest {
    private static final AgentStore.ConversationKey CONVERSATION =
            new AgentStore.ConversationKey("tenant_a", "thread_a", "user_a");

    /** 登记监听后重新读取 Store 水位，覆盖订阅期间并发提交的竞态。 */
    @Test
    void watchStartsAtTheStoreWatermarkAndAdvancesFromCommittedNotifications() throws Exception {
        AgentStore store = store(2, List.of());
        try (AgentEventWatchRegistry registry = new AgentEventWatchRegistry(store, 4)) {
            AgentIdentity identity = identity("turn_a");
            AgentEventWatch watch = registry.watch(identity, 1);
            assertEquals(OptionalLong.of(2), watch.awaitNext(1, Duration.ZERO));

            registry.onEvent(event(identity, 3));
            assertEquals(OptionalLong.of(3), watch.awaitNext(2, Duration.ofSeconds(1)));
            assertFalse(watch.isClosed());
            watch.close();
            assertTrue(watch.isClosed());
        }
    }

    /** 监听通知不得跨租户传播，也不得伪造水位。 */
    @Test
    void ignoresNotificationsFromAnotherTenantAndWakesOnConversationCommit() throws Exception {
        AgentStore store = store(1, List.of());
        try (AgentEventWatchRegistry registry = new AgentEventWatchRegistry(store, 4)) {
            AgentIdentity identity = identity("turn_a");
            AgentEventWatch watch = registry.watch(identity, 1);
            registry.onEvent(event(new AgentIdentity("tenant_b", "thread_a", "turn_b", "user_a"), 2));
            assertTrue(watch.awaitNext(1, Duration.ofMillis(1)).isEmpty());

            AgentIdentity sameConversation = new AgentIdentity(
                    identity.tenantId(), identity.threadId(), "turn_b", "user_b");
            registry.onEvent(event(sameConversation, 4));
            assertEquals(OptionalLong.of(4), watch.awaitNext(1, Duration.ofSeconds(1)));
        }
    }

    /** 关闭 Watch 是明确生命周期信号，不能变成让等待线程悬挂的超时。 */
    @Test
    void closingWatchCancelsBlockedRead() {
        AgentEventWatchRegistry registry = new AgentEventWatchRegistry(store(0, List.of()), 4);
        AgentEventWatch watch = registry.watch(identity("turn_a"), 0);
        watch.close();
        assertThrows(CancellationException.class, () -> watch.awaitNext(0, Duration.ZERO));
        registry.close();
    }

    /** 达到订阅容量时立即拒绝新 Watch，释放旧句柄后可重新订阅。 */
    @Test
    void boundsActiveWatchesAndReusesReleasedCapacity() {
        AgentEventWatchRegistry registry = new AgentEventWatchRegistry(store(0, List.of()), 1);
        AgentIdentity firstIdentity = identity("turn_a");
        AgentEventWatch first = registry.watch(firstIdentity, 0);

        assertThrows(IllegalStateException.class, () -> registry.watch(identity("turn_b"), 0));
        first.close();
        assertFalse(registry.watch(identity("turn_b"), 0).isClosed());
        registry.close();
    }

    /**
     * 零游标允许在首次准入前订阅；提交通知唤醒后关闭句柄须归还唯一容量。
     *
     * @author kongweiguang
     */
    @Test
    void registersZeroCursorBeforeConversationAdmissionAndReleasesCapacityOnClose() throws Exception {
        AgentIdentity identity = identity("turn_a");
        AgentEventWatchRegistry registry = new AgentEventWatchRegistry(store(Optional.empty(), List.of()), 1);
        AgentEventWatch watch = registry.watch(identity, 0);

        assertTrue(watch.awaitNext(0, Duration.ZERO).isEmpty());
        registry.onEvent(event(identity, 1));
        assertEquals(OptionalLong.of(1), watch.awaitNext(0, Duration.ofSeconds(1)));
        watch.close();
        assertThrows(CancellationException.class, () -> watch.awaitNext(1, Duration.ZERO));
        assertFalse(registry.watch(identity("turn_b"), 0).isClosed());
        registry.close();
    }

    /**
     * 未准入会话不能接受伪造的正游标，RuntimeException/Error 均清理注册槽并保持原样传播。
     *
     * @author kongweiguang
     */
    @Test
    void rejectsPositiveCursorForMissingConversationAndPropagatesStoreFailure() {
        AgentEventWatchRegistry missingConversation = new AgentEventWatchRegistry(
                store(Optional.empty(), List.of()), 1);
        assertThrows(IllegalArgumentException.class, () -> missingConversation.watch(identity("turn_a"), 1));
        assertFalse(missingConversation.watch(identity("turn_b"), 0).isClosed());
        missingConversation.close();

        RuntimeException storeFailure = new IllegalStateException("injected store read failure");
        AtomicReference<RuntimeException> pendingFailure = new AtomicReference<>(storeFailure);
        AgentStore failingStore = (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("loadConversation")) {
                        RuntimeException failure = pendingFailure.getAndSet(null);
                        if (failure != null) {
                            throw failure;
                        }
                        return Optional.empty();
                    }
                    if (method.getName().equals("close")) {
                        return null;
                    }
                    throw new AssertionError("unexpected AgentStore call: " + method.getName());
                });
        AgentEventWatchRegistry failingRegistry = new AgentEventWatchRegistry(failingStore, 1);
        assertSame(storeFailure, assertThrows(RuntimeException.class,
                () -> failingRegistry.watch(identity("turn_a"), 0)));
        assertFalse(failingRegistry.watch(identity("turn_b"), 0).isClosed());
        failingRegistry.close();

        Error storeError = new AssertionError("injected store error");
        AtomicReference<Error> pendingError = new AtomicReference<>(storeError);
        AgentStore errorStore = (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("loadConversation")) {
                        Error failure = pendingError.getAndSet(null);
                        if (failure != null) {
                            throw failure;
                        }
                        return Optional.empty();
                    }
                    if (method.getName().equals("close")) {
                        return null;
                    }
                    throw new AssertionError("unexpected AgentStore call: " + method.getName());
                });
        AgentEventWatchRegistry errorRegistry = new AgentEventWatchRegistry(errorStore, 1);
        assertSame(storeError, assertThrows(Error.class, () -> errorRegistry.watch(identity("turn_a"), 0)));
        assertFalse(errorRegistry.watch(identity("turn_b"), 0).isClosed());
        errorRegistry.close();
    }

    /** 按执行身份过滤事件，同时保留共享会话游标。 */
    @Test
    void readerFiltersOtherExecutionsWithoutReusingTheirCursorRange() {
        List<AgentStore.PersistedEvent> persisted = List.of(
                persisted(identity("turn_a"), 1), persisted(identity("turn_b"), 2));
        AgentEventReader reader = new AgentEventReader(store(2, persisted));

        AgentEventPage page = reader.read(identity("turn_b"), 0, 10);

        assertEquals(List.of(2L), page.events().stream().map(AgentStore.PersistedEvent::sequence).toList());
        assertEquals(2, page.nextSequence());
        assertFalse(page.hasMore());
    }

    /**
     * 只实现只读 Watch 合法调用的 Store 操作。
     *
     * @author kongweiguang
     */
    private static AgentStore store(long watermark, List<AgentStore.PersistedEvent> events) {
        return store(Optional.of(new AgentStore.ConversationSnapshot(
                CONVERSATION, 1, watermark, 1, Instant.EPOCH, Instant.EPOCH, 0)), events);
    }

    /**
     * 按需模拟尚未准入的会话，同时仍令所有非 Watch 查询越界失败。
     *
     * @author kongweiguang
     */
    private static AgentStore store(Optional<AgentStore.ConversationSnapshot> conversation,
                                    List<AgentStore.PersistedEvent> events) {
        return (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "loadConversation" -> conversation;
                    case "readEvents" -> new AgentStore.EventPage(events,
                            conversation.map(AgentStore.ConversationSnapshot::eventSequence).orElse(0L), false);
                    case "close" -> null;
                    case "toString" -> "ReadOnlyAgentStoreTestDouble";
                    default -> throw new AssertionError("unexpected AgentStore call: " + method.getName());
                });
    }

    /** 创建完整执行身份，不附加文件系统或 Provider 属性。 */
    private static AgentIdentity identity(String turnId) {
        return new AgentIdentity("tenant_a", "thread_a", turnId, "user_a");
    }

    /** 构造携带 Store 分配会话序号的已提交事件通知。 */
    private static AgentEvent event(AgentIdentity identity, long sequence) {
        return new AgentEvent(identity, persisted(identity, sequence));
    }

    /** 构造符合持久化契约的租户、会话和执行身份事件。 */
    private static AgentStore.PersistedEvent persisted(AgentIdentity identity, long sequence) {
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(
                identity.tenantId(), identity.threadId(), identity.userId());
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(conversation, identity.turnId());
        return new AgentStore.PersistedEvent(conversation, execution, "evt_" + sequence,
                "state_changed", 1, sequence, sequence, sequence, 0, JsonTestValues.object("{}"),
                Instant.EPOCH.plusSeconds(sequence));
    }
}
