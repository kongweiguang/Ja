// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.gateway.ToolArgumentValidator;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ControlPayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ExtensionFactRecordCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.JacksonExecutionEventCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.MiddlewareStateRecordCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.TurnExecutionStateCodec;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionEventCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 固定新基线只按命令恢复队列，并拒绝精确退役的快照类型而保留扩展记录边界。 */
final class AgentLoopInputQueueRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
    private static final AgentIdentity IDENTITY = new AgentIdentity("tenant_test", "thread_test", "turn_test", "user_test");

    /** 旧快照不得被公开队列读取路径静默解释为可恢复状态。
     * @author kongweiguang
     */
    @Test
    void rejectsRetiredSnapshotDuringInputQueueInspection() {
        Fixture fixture = fixture(List.of(retiredSnapshot()));
        try (StandardAgentLoopExecutionService service = service(fixture)) {
            assertThrows(AgentStore.StoreIntegrityException.class, () -> service.inspectInputQueue(IDENTITY));
        }
    }

    /** 状态视图是恢复的另一入口，必须对相同退役类型施加同一失败关闭边界。
     * @author kongweiguang
     */
    @Test
    void rejectsRetiredSnapshotDuringExecutionStateRestore() {
        Fixture fixture = fixture(List.of(retiredSnapshot()));

        assertThrows(AgentStore.StoreIntegrityException.class, () -> stateView(fixture));
    }

    /** 新恢复基线由命令顺序和 revision 决定，未知自定义 Store 记录不参与队列归约。
     * @author kongweiguang
     */
    @Test
    void restoresOrderedCommandsAndLeavesCustomRecordsExtensible() {
        ControlPayloadCodec controls = new ControlPayloadCodec(new ObjectMapper());
        AgentInputQueue.Command enqueue = new AgentInputQueue.Command("command_enqueue",
                AgentInputQueue.Action.ENQUEUE, "input_1", AgentInputQueue.Kind.FOLLOW_UP,
                0, 1, "message_1", null, NOW, Map.of());
        AgentInputQueue.Command prioritize = new AgentInputQueue.Command("command_prioritize",
                AgentInputQueue.Action.PRIORITIZE, "input_1", null, 1, 2,
                null, null, NOW.plusSeconds(1), Map.of());
        List<AgentStore.StoredRecord> records = List.of(
                stored(controls.encodeInputQueueCommand(enqueue.commandId(), enqueue, NOW), 1),
                new AgentStore.StoredRecord("extension_vendor", "vendor.custom.fact", 1, 2,
                        JsonObject.empty(), NOW),
                stored(controls.encodeInputQueueCommand(prioritize.commandId(), prioritize, NOW.plusSeconds(1)), 3));
        Fixture fixture = fixture(records);

        try (StandardAgentLoopExecutionService service = service(fixture)) {
            AgentInputQueue inspected = service.inspectInputQueue(IDENTITY);
            AgentInputQueue restored = stateView(fixture).queue();

            assertEquals(2, inspected.revision());
            assertEquals(AgentInputQueue.Kind.STEERING, inspected.entries().getFirst().kind());
            assertEquals(2, inspected.entries().getFirst().inputRevision());
            assertEquals(inspected, restored);
        }
    }

    /** 将退役身份与 JSON payload 解耦，保证准入边界不解析也不泄漏旧正文。
     * @author kongweiguang
     */
    private static AgentStore.StoredRecord retiredSnapshot() {
        return new AgentStore.StoredRecord("record_retired_snapshot", "agent.input.queue.snapshot",
                1, 1, JsonObject.empty(), NOW);
    }

    /** 为 loop 与恢复视图提供同一冻结水位，测试不通过临时补读推进游标。
     * @author kongweiguang
     */
    private static Fixture fixture(List<AgentStore.StoredRecord> records) {
        AgentStore.ConversationKey conversationKey = new AgentStore.ConversationKey(
                IDENTITY.tenantId(), IDENTITY.threadId(), IDENTITY.userId());
        AgentStore.ExecutionKey key = new AgentStore.ExecutionKey(conversationKey, IDENTITY.turnId());
        long watermark = records.stream().mapToLong(AgentStore.StoredRecord::sequence).max().orElse(0);
        AgentStore.ConversationSnapshot conversation = new AgentStore.ConversationSnapshot(
                conversationKey, 1, 0, 1, NOW, NOW, watermark);
        AgentStore.ExecutionSnapshot execution = new AgentStore.ExecutionSnapshot(
                key, 1, TurnState.RUNNING.name(), 1, 1, JsonObject.empty(), watermark, NOW, NOW);
        List<AgentStore.ConversationRecord> conversationRecords = records.stream()
                .map(record -> new AgentStore.ConversationRecord(conversationKey, key, record.recordId(),
                        record.recordType(), record.payloadSchemaVersion(), record.sequence(),
                        record.payload(), record.occurredAt()))
                .toList();
        AgentStore store = recordStore(execution, records);
        ObjectMapper mapper = new ObjectMapper();
        JsonValueCodec json = new JacksonJsonValueCodec(mapper);
        ExecutionRecordCodec executionRecords = new StorePayloadCodec(mapper);
        AgentLoopPersistence persistence = new AgentLoopPersistence(store, Clock.fixed(NOW, ZoneOffset.UTC));
        AgentContextMapper contextMapper = new AgentContextMapper(json, executionRecords);
        ExecutionControlCodec controls = new ControlPayloadCodec(mapper);
        AgentExecutionDriver.Services services = new AgentExecutionDriver.Services(store, persistence,
                executionRecords, new TurnExecutionStateCodec(mapper), new JacksonExecutionEventCodec(mapper),
                contextMapper, (ToolArgumentValidator) (schema, arguments) -> Optional.empty(), controls,
                json, new ExtensionFactRecordCodec(), new MiddlewareStateRecordCodec(json),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30));
        AgentLoopPersistence.Restored restored = new AgentLoopPersistence.Restored(
                conversation, execution, List.of(execution), conversationRecords);
        return new Fixture(store, restored, services, executionRecords,
                new JacksonExecutionEventCodec(mapper), controls);
    }

    /** 用只读 Store 替身固定 execution 与记录水位，任何恢复外的读写都立即暴露。
     * @author kongweiguang
     */
    private static AgentStore recordStore(AgentStore.ExecutionSnapshot execution,
                                          List<AgentStore.StoredRecord> records) {
        return (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "loadExecution" -> Optional.of(execution);
                    case "readRecords" -> {
                        AgentStore.RecordQuery query = (AgentStore.RecordQuery) arguments[0];
                        List<AgentStore.StoredRecord> page = records.stream()
                                .filter(record -> record.sequence() > query.afterSequence()
                                        && record.sequence() <= query.throughSequence())
                                .toList();
                        long next = page.isEmpty() ? query.afterSequence() : page.getLast().sequence();
                        yield new AgentStore.RecordPage(page, next, false);
                    }
                    case "close" -> null;
                    case "toString" -> "FixedQueueRecoveryStore";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new AssertionError("unexpected Store call: " + method.getName());
                });
    }

    /** 以真实服务视图调用 reducer，确认测试未绕过 queue restore 的业务入口。
     * @author kongweiguang
     */
    private static StandardAgentLoopExecutionService service(Fixture fixture) {
        return new StandardAgentLoopExecutionService(fixture.store(),
                new AgentExecutionDriver(fixture.store(), fixture.services().persistence(), fixture.records(),
                        fixture.services().cursors(), fixture.events(), fixture.services().mapper(),
                        fixture.services().toolArgumentValidator(), fixture.controls(), fixture.services().json(),
                        fixture.services().extensionFacts(), fixture.services().middlewareStates(),
                        fixture.services().clock(), fixture.services().leaseDuration()),
                fixture.services().persistence(), fixture.records(), fixture.events(), fixture.controls(),
                Duration.ofSeconds(30), fixture.services().clock());
    }

    /** 对固定 Store recovery material 构建正式的执行状态视图。
     * @author kongweiguang
     */
    private static ExecutionStateView stateView(Fixture fixture) {
        return new ExecutionStateView(fixture.restored(), fixture.services(), List.of());
    }

    /** 按 Core Store 的记录封套保存测试命令，不构造伪造 sequence。
     * @author kongweiguang
     */
    private static AgentStore.StoredRecord stored(AgentStore.RecordWrite record, long sequence) {
        return new AgentStore.StoredRecord(record.recordId(), record.recordType(), record.payloadSchemaVersion(),
                sequence, record.payload(), record.occurredAt());
    }

    /** 单一 fixture 保持所有 reducer 读取同一 Store snapshot 与 codec 实例。
     * @param store 队列恢复测试共用的 Store
     * @param restored loop 与状态视图共用的恢复快照
     * @param services loop 使用的持久化服务及 codec 视图
     * @param records 执行记录编解码器
     * @param events 执行事件编解码器
     * @param controls 控制事实及输入队列命令编解码器
     * @author kongweiguang
     */
    private record Fixture(AgentStore store,
                           AgentLoopPersistence.Restored restored,
                           AgentExecutionDriver.Services services,
                           ExecutionRecordCodec records,
                           ExecutionEventCodec events,
                           ExecutionControlCodec controls) {
    }
}
