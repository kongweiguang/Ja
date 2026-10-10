// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.context.checkpoint;
import io.github.kongweiguang.ja.agent.domain.context.ToolProjection;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationRecordQuery;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.StoredRecord;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore.ContextCheckpoint;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore.ProjectionRequest;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore.ProjectionSnapshot;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.support.JsonTestValues;

/** 检查点适配器只暂存事实，分页与冻结选择规则独立于 SQLite/PG 编解码契约验证。 */
class StoreBackedCheckpointStoreTest {
    private static final Instant NOW = Instant.parse("2026-09-26T00:00:00Z");
    private static final AgentStore.ConversationKey KEY = new AgentStore.ConversationKey("tenant", "thread", "user");
    private static final AgentStore.ExecutionKey EXECUTION = new AgentStore.ExecutionKey(KEY, "turn");
    private static final CheckpointStore.ProjectionBinding BINDING =
            new CheckpointStore.ProjectionBinding("a".repeat(64), "b".repeat(64));

    /** 每页固定同一水位，即使数据库还有新记录也不能混入本次上下文恢复。 */
    @Test void readsFixedWatermarkAcrossPages() {
        AtomicInteger pages = new AtomicInteger();
        ProjectionSnapshot projection = projection(Map.of());
        AgentStore store = proxy(AgentStore.class, (method, arguments) -> {
            assertEquals("readConversationRecords", method);
            ConversationRecordQuery query = (AgentStore.ConversationRecordQuery) arguments[0];
            assertEquals(2, query.throughSequence());
            int page = pages.incrementAndGet();
            assertEquals(page - 1, query.afterSequence());
            return new AgentStore.ConversationRecordPage(List.of(record(page,
                    page == 1 ? "agent.context.projection" : "agent.user.message")), page, page == 1);
        });
        StoreBackedCheckpointStore.View view = new StoreBackedCheckpointStore(store, codec(projection)).read(snapshot(2));
        assertEquals(2, pages.get());
        assertEquals(projection, view.projection().orElseThrow());
        assertTrue(view.checkpoint().isEmpty());
    }

    /** 旧模型请求即使晚于新摘要恢复，也必须按冻结 ID 取回原摘要，而不是 latest。 */
    @Test void readsReferencedHistoricalCheckpointWithinFixedWatermark() {
        ContextCheckpoint checkpointA = checkpoint("checkpoint_a", 4, 2, "摘要 A");
        ContextCheckpoint checkpointB = checkpoint("checkpoint_b", 6, 4, "摘要 B");
        AgentStore store = proxy(AgentStore.class, (method, arguments) -> {
            assertEquals("readConversationRecords", method);
            ConversationRecordQuery query = (AgentStore.ConversationRecordQuery) arguments[0];
            assertEquals(2, query.throughSequence());
            return new AgentStore.ConversationRecordPage(List.of(
                    recordWithId("record_a", 1, "agent.context.checkpoint"),
                    recordWithId("record_b", 2, "agent.context.checkpoint")), 2, false);
        });
        StoreBackedCheckpointStore adapter = new StoreBackedCheckpointStore(store, checkpointCodec(Map.of(
                "record_a", checkpointA, "record_b", checkpointB)));

        assertEquals(checkpointA, adapter.readCheckpoint(snapshot(2), "checkpoint_a"));
        assertEquals(checkpointB, adapter.read(snapshot(2)).checkpoint().orElseThrow());
    }

    /** 被模型请求引用的摘要若在恢复水位中消失，必须明确报存储损坏，不能改读更新摘要。 */
    @Test void rejectsMissingReferencedCheckpointAtRestoredWatermark() {
        ContextCheckpoint checkpointB = checkpoint("checkpoint_b", 6, 4, "摘要 B");
        AgentStore store = proxy(AgentStore.class, (method, arguments) ->
                new AgentStore.ConversationRecordPage(List.of(
                        recordWithId("record_b", 1, "agent.context.checkpoint")), 1, false));
        StoreBackedCheckpointStore adapter = new StoreBackedCheckpointStore(store, checkpointCodec(Map.of("record_b", checkpointB)));

        assertThrows(AgentStore.StoreIntegrityException.class,
                () -> adapter.readCheckpoint(snapshot(1), "checkpoint_a"));
    }

    /** 引用即便命中同一存储的记录，只要摘要归属其他 Thread 就必须失败关闭。 */
    @Test void rejectsReferencedCheckpointFromAnotherConversation() {
        ContextCheckpoint foreign = new CheckpointStore.ContextCheckpoint("checkpoint_foreign", "thread-other", 2, 3, 6,
                Optional.empty(), io.github.kongweiguang.ja.agent.domain.context.SummaryDocument.empty(),
                100, "c".repeat(64), "strategy_test",
                io.github.kongweiguang.ja.agent.domain.context.CheckpointUsage.none(), NOW);
        AgentStore store = proxy(AgentStore.class, (method, arguments) ->
                new AgentStore.ConversationRecordPage(List.of(
                        recordWithId("record_foreign", 1, "agent.context.checkpoint")), 1, false));
        StoreBackedCheckpointStore adapter = new StoreBackedCheckpointStore(store, checkpointCodec(Map.of("record_foreign", foreign)));

        assertThrows(AgentStore.StoreIntegrityException.class,
                () -> adapter.readCheckpoint(snapshot(1), "checkpoint_foreign"));
    }

    /** 不推进的空页或缺失已承诺水位必须失败，不能悄悄视为无压缩历史。 */
    @Test void rejectsIncompleteRecordWindow() {
        AgentStore store = proxy(AgentStore.class, (method, arguments) ->
                new AgentStore.ConversationRecordPage(List.of(), 0, false));
        assertThrows(AgentStore.StoreIntegrityException.class,
                () -> new StoreBackedCheckpointStore(store, codec(projection(Map.of()))).read(snapshot(1)));
    }

    /** 多次规划得到相同身份且不会自己写 Store；未绑定模型不能落入内存测试默认路径。 */
    @Test void stagesStableProjectionWithoutIndependentCommit() {
        StoreBackedCheckpointStore adapter = new StoreBackedCheckpointStore(noStoreCalls(), codec(projection(Map.of())));
        StoreBackedCheckpointStore.View view = new StoreBackedCheckpointStore.View(snapshot(0), Optional.empty(), Optional.empty());
        ProjectionRequest request = new CheckpointStore.ProjectionRequest("thread", 7, BINDING, NOW);
        ProjectionSnapshot first = adapter.prepareProjection(view, request, Optional.empty());
        assertEquals(first, adapter.prepareProjection(view, request, Optional.empty()));
        StoreBackedCheckpointStore.StagedProjection one = adapter.stageProjection(EXECUTION, view, first, Map.of(), NOW);
        StoreBackedCheckpointStore.StagedProjection two = adapter.stageProjection(EXECUTION, view, first, Map.of(), NOW);
        assertEquals(one.records(), two.records());
        assertEquals(1, one.records().size());
        assertThrows(IllegalArgumentException.class, () -> adapter.prepareProjection(view,
                new CheckpointStore.ProjectionRequest("thread", 7, CheckpointStore.ProjectionBinding.unbound(), NOW),
                Optional.empty()));
    }

    /** 已发给模型的选择不得被删除或改写；相同阶段的无变化重试无需另写事实。 */
    @Test void preservesFrozenSelectionsAndTenantBoundary() {
        ToolProjection choice = ToolProjection.values()[0];
        ProjectionSnapshot projection = projection(Map.of("call_one", choice));
        StoreBackedCheckpointStore.View view = new StoreBackedCheckpointStore.View(snapshot(1), Optional.empty(), Optional.of(projection));
        StoreBackedCheckpointStore adapter = new StoreBackedCheckpointStore(noStoreCalls(), codec(projection));
        assertTrue(adapter.stageProjection(EXECUTION, view, projection, projection.selections(), NOW).records().isEmpty());
        assertThrows(AgentStore.StoreIntegrityException.class,
                () -> adapter.stageProjection(EXECUTION, view, projection, Map.of(), NOW));
        assertThrows(IllegalArgumentException.class, () -> adapter.stageProjection(
                new AgentStore.ExecutionKey(new AgentStore.ConversationKey("other", "thread", "user"), "turn"),
                view, projection, projection.selections(), NOW));
    }

    /** 新检查点只推进一次投影阶段，恢复同一检查点时不能反复丢弃已冻结的工具选择。 */
    @Test void advancesOnceForANewCheckpoint() {
        ContextCheckpoint checkpoint = new CheckpointStore.ContextCheckpoint("checkpoint_one", "thread", 2, 3, 6,
                Optional.empty(), io.github.kongweiguang.ja.agent.domain.context.SummaryDocument.empty(),
                100, "c".repeat(64), "strategy_test",
                io.github.kongweiguang.ja.agent.domain.context.CheckpointUsage.none(), NOW);
        StoreBackedCheckpointStore adapter = new StoreBackedCheckpointStore(noStoreCalls(), codec(projection(Map.of())));
        StoreBackedCheckpointStore.View before = new StoreBackedCheckpointStore.View(snapshot(1), Optional.of(checkpoint),
                Optional.of(projection(Map.of())));
        ProjectionRequest request = new CheckpointStore.ProjectionRequest("thread", 7, BINDING, NOW);
        ProjectionSnapshot advanced = adapter.prepareProjection(before, request, Optional.empty());
        assertEquals(2, advanced.stageNumber());
        assertEquals(CheckpointStore.ProjectionReason.CHECKPOINT, advanced.reason());
        StoreBackedCheckpointStore.View recovered = new StoreBackedCheckpointStore.View(snapshot(2), Optional.of(checkpoint), Optional.of(advanced));
        assertEquals(advanced, adapter.prepareProjection(recovered, request,
                Optional.of(CheckpointStore.ProjectionReason.CHECKPOINT)));
    }

    /** 提交者声称成功但未写事实时必须停止派发，不能把回调返回值当成恢复证据。 */
    @Test void refusesSuccessfulReceiptWithoutDurableProjection() {
        StoreBackedCheckpointStore adapter = new StoreBackedCheckpointStore(noStoreCalls(), codec(projection(Map.of())));
        CheckpointStore bound = adapter.bind(EXECUTION, new StoreBackedCheckpointStore.Committer() {
            /** 模拟未推进的实际 owner 水位，暴露假回执与持久事实不一致。 */
            @Override public AgentStore.ConversationSnapshot snapshot() { return StoreBackedCheckpointStoreTest.snapshot(0); }
            /** 故障注入只返回回执，不保存投影，适配器必须拒绝该结果。 */
            @Override public AgentStore.CommitReceipt commitProjection(long expected, List<AgentStore.RecordWrite> records) {
                return new AgentStore.CommitReceipt(8, 1, 0, 0, json("{}"), true);
            }
            /** 本用例没有摘要生成，意外进入该路径应立即失败。 */
            @Override public CheckpointStore.CommittedCheckpoint commitCheckpoint(CheckpointStore.CommitRequest request,
                    AgentStore.RecordWrite record) { throw new AssertionError("unexpected checkpoint commit"); }
        });
        ProjectionSnapshot prepared = bound.prepareProjection(new CheckpointStore.ProjectionRequest("thread", 7, BINDING, NOW));
        assertThrows(AgentStore.StoreIntegrityException.class, () -> bound.persistProjection(prepared, Map.of()));
    }

    /** 源版本固定为真实 CAS 值，记录水位与会话版本并不混为一个计数器。 */
    private static AgentStore.ConversationSnapshot snapshot(long watermark) {
        return new AgentStore.ConversationSnapshot(KEY, 7, 0, 1, NOW, NOW, watermark);
    }

    /** 测试使用持久阶段字段而非 ephemeral 工厂，阶段不能依赖随机 UUID 恢复。 */
    private static CheckpointStore.ProjectionSnapshot projection(Map<String, ToolProjection> selections) {
        return new CheckpointStore.ProjectionSnapshot("projection_one", "thread", 1, 5,
                CheckpointStore.ProjectionReason.INITIAL, BINDING, selections);
    }

    /** 泛型记录只承载顺序与来源，本测试不复制已有 JSON 编解码器的专属用例。 */
    private static AgentStore.ConversationRecord record(long sequence, String type) {
        return new AgentStore.ConversationRecord(KEY, EXECUTION, "record_" + sequence,
                type, 1, sequence, json("{}"), NOW);
    }

    /** 为多事实快照指定稳定行 ID，使精确引用测试不会依赖页内位置猜身份。 */
    private static AgentStore.ConversationRecord recordWithId(String id, long sequence, String type) {
        return new AgentStore.ConversationRecord(KEY, EXECUTION, id, type, 1,
                sequence, json("{}"), NOW);
    }

    /** 用不同源版本和摘要正文构造真实可比较的历史检查点对象。 */
    private static CheckpointStore.ContextCheckpoint checkpoint(String id, long sourceRevision,
            long throughOrdinal, String text) {
        SummaryDocument summary = new io.github.kongweiguang.ja.agent.domain.context.SummaryDocument(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new io.github.kongweiguang.ja.agent.domain.context.SummaryDocument.Fact(text, 1)),
                List.of(), List.of(), List.of());
        return new CheckpointStore.ContextCheckpoint(id, "thread", throughOrdinal, throughOrdinal,
                sourceRevision, Optional.empty(), summary, 100, "c".repeat(64), "strategy_test",
                io.github.kongweiguang.ja.agent.domain.context.CheckpointUsage.none(), NOW);
    }

    /** 仅按存储行 ID 解码测试 checkpoint，其他调用仍立即失败以守住 codec 边界。 */
    private static ExecutionRecordCodec checkpointCodec(Map<String, CheckpointStore.ContextCheckpoint> checkpoints) {
        return proxy(ExecutionRecordCodec.class, (method, arguments) -> switch (method) {
            case "decodeCheckpoint" -> {
                StoredRecord stored = (AgentStore.StoredRecord) arguments[0];
                ContextCheckpoint checkpoint = checkpoints.get(stored.recordId());
                if (checkpoint == null) {
                    throw new AssertionError("unexpected checkpoint record: " + stored.recordId());
                }
                yield checkpoint;
            }
            default -> throw new AssertionError("unexpected codec call: " + method);
        });
    }

    /** 仅允许两种投影编解码操作；任何额外持久化行为都应使测试立即失败。 */
    private static ExecutionRecordCodec codec(CheckpointStore.ProjectionSnapshot projection) {
        return proxy(ExecutionRecordCodec.class, (method, arguments) -> switch (method) {
            case "decodeProjection" -> projection;
            case "encodeProjection" -> new AgentStore.RecordWrite((String) arguments[0],
                    "agent.context.projection", 1, json("{}"), (Instant) arguments[2]);
            default -> throw new AssertionError("unexpected codec call: " + method);
        });
    }

    /** 规划和暂存没有事务写权，用拒绝所有调用的 Store 防止实现偷偷独立提交。 */
    private static AgentStore noStoreCalls() {
        return proxy(AgentStore.class, (method, arguments) -> { throw new AssertionError("unexpected store call: " + method); });
    }

    /** 构造测试所需的不可变 JSON 对象，匹配 Core 持久化值类型。 */
    private static JsonObject json(String source) {
        return JsonTestValues.object(source);
    }

    /** 小型严格代理保留端口边界，未声明操作不能退回无声默认值。 */
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Call call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (instance, method, arguments) -> call.invoke(method.getName(), arguments));
    }

    /** 只为当前测试代理提供明确的方法调度，不扩展生产公开 API。 */
    @FunctionalInterface private interface Call {
        /** 参数与真实接口保持一致，禁止测试夹具吞掉意外调用。 */
        Object invoke(String method, Object[] arguments);
    }
}
