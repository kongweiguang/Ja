// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.infrastructure.runtime;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.application.gateway.AgentEvent;
import io.github.kongweiguang.ja.agent.application.gateway.AgentEventListener;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExtensionFactRecordCodec;
import io.github.kongweiguang.ja.plan.application.service.PlanCommittedPublisher;
import io.github.kongweiguang.ja.plan.application.service.PlanEventRegistry;
import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.application.api.PlanEvent;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定 Plan 通知监听器在并发 execution、Store 重放和大事务下的持久水位边界。
 */
final class PlanCommittedEventListenerTest {
    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");
    private static final AgentStore.ConversationKey CONVERSATION =
            new AgentStore.ConversationKey("ja.local", "thread-1", "user_local");
    private static final AgentIdentity EXECUTION_A = new AgentIdentity("ja.local", "thread-1", "turn-a", "user_local");
    private static final AgentIdentity EXECUTION_B = new AgentIdentity("ja.local", "thread-1", "turn-b", "user_local");
    private static final ExtensionFactRecordCodec FACTS =
            new io.github.kongweiguang.ja.agent.infrastructure.persistence.ExtensionFactRecordCodec();

    /**
     * 先处理执行 A 的后续水位，再重放执行 B 的早期提交；B 仍应投影且重复重放不得重复发布。
     */
    @Test
    void replaysInterleavedExecutionAndDeduplicatesRepeatedPlanWatermark() {
        Fixture fixture = new Fixture();
        fixture.latestPlanEvents.put("plan-a", new AtomicLong(1));
        fixture.latestPlanEvents.put("plan-b", new AtomicLong(1));
        fixture.events.add(event(EXECUTION_A, 1, 1));
        fixture.events.add(event(EXECUTION_B, 2, 2));
        fixture.events.add(event(EXECUTION_A, 3, 3));
        fixture.records.add(planCommand(EXECUTION_A, 1, "plan-a"));
        fixture.records.add(planCommand(EXECUTION_B, 2, "plan-b"));
        fixture.records.add(planCommand(EXECUTION_A, 3, "plan-a"));

        fixture.listener.onEvent(new AgentEvent(EXECUTION_A, fixture.events.getFirst()));
        fixture.latestPlanEvents.get("plan-a").set(2);
        fixture.listener.onEvent(new AgentEvent(EXECUTION_A, fixture.events.get(2)));
        fixture.listener.onReplayRequired(replay(EXECUTION_B, 0, 2));
        fixture.listener.onReplayRequired(replay(EXECUTION_B, 0, 2));

        assertEquals(List.of("plan-a:1", "plan-a:2", "plan-b:1"), fixture.published.stream()
                .map(value -> value.plan().planId() + ":" + value.eventSequence()).toList());
        assertEquals(List.of(0L, 0L), fixture.replayEventQueries.stream()
                .map(AgentStore.EventQuery::afterSequence).toList());
        assertEquals(2, fixture.replayEventQueries.size());
    }

    /**
     * 重放跨过 Store 单页上限时复用同一 throughSequence，并只把目标 execution 的事件交给 listener。
     */
    @Test
    void pagesReplayThroughFixedWatermarkAndFiltersOtherExecutions() {
        Fixture fixture = new Fixture();
        for (int sequence = 1; sequence < 257; sequence++) {
            fixture.events.add(event(EXECUTION_A, sequence, 0));
        }
        fixture.events.add(event(EXECUTION_B, 257, 0));

        fixture.listener.onReplayRequired(replay(EXECUTION_B, 0, 257));

        assertEquals(List.of(0L, 256L), fixture.replayEventQueries.stream()
                .map(AgentStore.EventQuery::afterSequence).toList());
        assertTrue(fixture.replayEventQueries.stream().allMatch(query -> query.throughSequence() == 257
                && query.limit() == 256));
        assertEquals(1, fixture.previousEventQueries.size());
        assertEquals(0, fixture.recordQueries.size());
    }

    /**
     * 大事务在固定记录水位内持续分页，避免旧的 10,000 条单页限制把已提交 Plan 事实卡死。
     */
    @Test
    void scansRecordWindowLargerThanSingleStorePage() {
        Fixture fixture = new Fixture();
        int recordCount = 10_001;
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(CONVERSATION, EXECUTION_A.turnId());
        fixture.events.add(event(EXECUTION_A, 1, recordCount));
        for (int sequence = 1; sequence <= recordCount; sequence++) {
            fixture.records.add(new AgentStore.ConversationRecord(CONVERSATION, execution,
                    "record-" + sequence, "agent.other.fact", 1, sequence, JsonObject.empty(), NOW));
        }

        fixture.listener.onEvent(new AgentEvent(EXECUTION_A, fixture.events.getFirst()));

        assertEquals(40, fixture.recordQueries.size());
        assertTrue(fixture.recordQueries.stream().allMatch(query -> query.throughSequence() == recordCount
                && query.limit() == 256));
        assertEquals(9_984, fixture.recordQueries.getLast().afterSequence());
    }

    /**
     * 空页不能伪装成成功重放；线程中断须在读 Store 前退出并保留中断标记。
     */
    @Test
    void rejectsReplayWithoutCursorProgressAndHonorsInterruption() {
        Fixture stalled = new Fixture();
        stalled.eventPageOverride = query -> new AgentStore.EventPage(List.of(), query.afterSequence(), false);
        assertThrows(IllegalStateException.class,
                () -> stalled.listener.onReplayRequired(replay(EXECUTION_B, 0, 1)));

        Fixture stalledRecords = new Fixture();
        stalledRecords.events.add(event(EXECUTION_A, 1, 1));
        stalledRecords.recordPageOverride = query -> new AgentStore.ConversationRecordPage(
                List.of(), query.afterSequence(), false);
        assertThrows(IllegalStateException.class,
                () -> stalledRecords.listener.onEvent(new AgentEvent(EXECUTION_A, stalledRecords.events.getFirst())));

        Fixture interrupted = new Fixture();
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class,
                    () -> interrupted.listener.onReplayRequired(replay(EXECUTION_B, 0, 1)));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(interrupted.replayEventQueries.isEmpty());
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * 构造完整 listener 依赖并记录 Store 查询和实际通知，便于断言持久副作用而非只看返回值。
     */
    private static final class Fixture {
        private final List<AgentStore.PersistedEvent> events = new ArrayList<>();
        private final List<AgentStore.ConversationRecord> records = new ArrayList<>();
        private final List<AgentStore.EventQuery> replayEventQueries = new ArrayList<>();
        private final List<AgentStore.EventQuery> previousEventQueries = new ArrayList<>();
        private final List<AgentStore.ConversationRecordQuery> recordQueries = new ArrayList<>();
        private final Map<String, AtomicLong> latestPlanEvents = new java.util.concurrent.ConcurrentHashMap<>();
        private final List<PlanEvent> published = new ArrayList<>();
        private Function<AgentStore.EventQuery, AgentStore.EventPage> eventPageOverride;
        private Function<AgentStore.ConversationRecordQuery, AgentStore.ConversationRecordPage> recordPageOverride;
        private final AgentStore store = store(this);
        private final PlanRepository plans = repository(this);
        private final PlanEventRegistry registry = new PlanEventRegistry();
        private final PlanCommittedPublisher publisher = new PlanCommittedPublisher(plans, registry);
        private final PlanCommittedEventListener listener = new PlanCommittedEventListener(
                store, FACTS, plans, publisher);

        /**
         * 订阅 sink 只记录 publisher 的事件水位，仓储代理则按每个 Plan 的持久序号返回观察快照。
         */
        private Fixture() {
            registry.subscribe(value -> {
                published.add(value);
                return CompletableFuture.completedFuture(null);
            });
        }
    }

    /**
     * Store 代理仅实现监听器读取的两个端口，未使用的方法立即失败，防止测试依赖意外扩大。
     */
    private static AgentStore store(Fixture fixture) {
        return (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "readEvents" -> readEvents(fixture, (AgentStore.EventQuery) arguments[0]);
                    case "readConversationRecords" -> readRecords(fixture,
                            (AgentStore.ConversationRecordQuery) arguments[0]);
                    case "toString" -> "PlanListenerTestStore";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /**
     * 按固定上界返回连续事件页；limit=1 用于 listener 精确读取前一提交水位。
     */
    private static AgentStore.EventPage readEvents(Fixture fixture, AgentStore.EventQuery query) {
        boolean replayRead = query.limit() == 256;
        (replayRead ? fixture.replayEventQueries : fixture.previousEventQueries).add(query);
        if (fixture.eventPageOverride != null && replayRead) {
            return fixture.eventPageOverride.apply(query);
        }
        List<AgentStore.PersistedEvent> matching = fixture.events.stream()
                .filter(event -> event.sequence() > query.afterSequence()
                        && event.sequence() <= query.throughSequence())
                .toList();
        int returned = Math.min(query.limit(), matching.size());
        List<AgentStore.PersistedEvent> page = matching.subList(0, returned);
        long next = page.isEmpty() ? query.afterSequence() : page.getLast().sequence();
        return new AgentStore.EventPage(page, next, matching.size() > query.limit());
    }

    /**
     * 返回 record sequence 有序的固定水位页，模拟 SQLite/PostgreSQL 的 limit-plus-one 语义。
     */
    private static AgentStore.ConversationRecordPage readRecords(
            Fixture fixture, AgentStore.ConversationRecordQuery query) {
        fixture.recordQueries.add(query);
        if (fixture.recordPageOverride != null) {
            return fixture.recordPageOverride.apply(query);
        }
        List<AgentStore.ConversationRecord> matching = fixture.records.stream()
                .filter(record -> record.sequence() > query.afterSequence()
                        && record.sequence() <= query.throughSequence())
                .toList();
        int returned = Math.min(query.limit(), matching.size());
        List<AgentStore.ConversationRecord> page = matching.subList(0, returned);
        long next = page.isEmpty() ? query.afterSequence() : page.getLast().sequence();
        return new AgentStore.ConversationRecordPage(page, next, matching.size() > query.limit());
    }

    /**
     * 仓储只返回被测试更新的持久事件序号，确保去重断言来自 publisher 的真实实现。
     */
    private static PlanRepository repository(Fixture fixture) {
        return (PlanRepository) Proxy.newProxyInstance(PlanRepository.class.getClassLoader(),
                new Class<?>[]{PlanRepository.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "readPlanObservation" -> observation((String) arguments[0],
                            fixture.latestPlanEvents.get((String) arguments[0]).get());
                    case "toString" -> "PlanListenerTestRepository";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /**
     * 使用最小合法 Plan snapshot 和 append-only 事件，覆盖 publisher 的实际序号去重路径。
     */
    private static PlanRepository.PlanObservation observation(String planId, long eventSequence) {
        PlanModels.Plan plan = new PlanModels.Plan(planId, CONVERSATION.threadId(), "test plan",
                PlanModels.PlanStatus.DRAFT, 1, null, null, NOW, NOW);
        PlanRepository.PlanEvent latest = new PlanRepository.PlanEvent(eventSequence,
                "event-" + planId + "-" + eventSequence, planId, 1, "created", NOW);
        return new PlanRepository.PlanObservation(plan, latest, new PlanModels.PlanProgress(null, null, 0, 0));
    }

    /**
     * 生成包含 execution actor 和 Plan binding 的已编码 command fact，模拟真实事务记录格式。
     */
    private static AgentStore.ConversationRecord planCommand(AgentIdentity identity, long sequence, String planId) {
        JsonObject actor = new JsonObject(Map.of(
                "tenantId", new JsonText(identity.tenantId()),
                "threadId", new JsonText(identity.threadId()),
                "turnId", new JsonText(identity.turnId()),
                "userId", new JsonText(identity.userId())));
        JsonObject binding = new JsonObject(Map.of("planId", new JsonText(planId)));
        AgentTool.ExtensionFact fact = new AgentTool.ExtensionFact("ja.plan", "tool-command", 1,
                new JsonObject(Map.of("actor", actor, "binding", binding)));
        AgentStore.RecordWrite write = FACTS.encode("record-" + sequence, fact, NOW);
        return new AgentStore.ConversationRecord(CONVERSATION,
                new AgentStore.ExecutionKey(CONVERSATION, identity.turnId()),
                write.recordId(), write.recordType(), write.payloadSchemaVersion(), sequence,
                write.payload(), write.occurredAt());
    }

    /**
     * 事件事实的 execution 与 sequence 独立构造，保留会话级事件序号交错语义。
     */
    private static AgentStore.PersistedEvent event(AgentIdentity identity, long sequence, long recordSequence) {
        return new AgentStore.PersistedEvent(CONVERSATION,
                new AgentStore.ExecutionKey(CONVERSATION, identity.turnId()),
                "event-" + sequence, "agent.turn", 1, sequence, 1, sequence,
                recordSequence, JsonObject.empty(), NOW);
    }

    /**
     * 回放区间左端独占、右端包含，和 dispatcher 传入的 persisted sequence 约定一致。
     */
    private static AgentEventListener.ReplayRequired replay(
            AgentIdentity identity, long afterSequence, long throughSequence) {
        return new AgentEventListener.ReplayRequired(identity, afterSequence, throughSequence, "queue_full");
    }
}




