// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentRecordIds;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.context.ContextException;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;
import io.github.kongweiguang.ja.agent.application.context.summary.ModelSummaryGenerator;
import io.github.kongweiguang.ja.agent.domain.context.CheckpointUsage;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFact;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ModelRequestIdentity;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.ToolArgumentValidator;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionCursorCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionEventCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ExtensionFactRecordCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.MiddlewareStateRecordCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 覆盖自动摘要从准入到结算、故障恢复及最终 checkpoint 的持久闭环。 */
final class AgentSummaryOperationTest {
    private static final String PLAN = "a".repeat(64);
    private static final String PROMPT = "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-09-26T00:00:00Z");
    private static final String EMPTY_SUMMARY_JSON = new StorePayloadCodec(new ObjectMapper())
            .encodeSummaryDocument(SummaryDocument.empty());

    /** 首次调用创建持久摘要进度，重建操作后只从游标恢复且拒绝计划漂移。 */
    @Test
    void startsAndRestoresOnlyThePersistedPlan() {
        TestHost host = new TestHost(ready(TurnExecutionState.Next.ASSISTANT, null));
        AgentSummaryOperation operation = new AgentSummaryOperation(host);

        ModelSummaryGenerator.SummaryOperation.Progress first = operation.start(PLAN, SummaryDocument.empty());
        assertEquals(ModelSummaryGenerator.SummaryOperation.Stage.CANDIDATE, first.stage());
        assertEquals(TurnExecutionState.Next.SUMMARY, assertInstanceOf(TurnExecutionState.Ready.class,
                host.cursor()).next());
        assertEquals(1, host.commitCount);

        ModelSummaryGenerator.SummaryOperation.Progress restored =
                new AgentSummaryOperation(host).start(PLAN, SummaryDocument.empty());
        assertEquals(first, restored);
        assertEquals(1, host.commitCount);
        ContextException mismatch = assertThrows(ContextException.class,
                () -> new AgentSummaryOperation(host).start("c".repeat(64), SummaryDocument.empty()));
        assertEquals(ContextException.Code.SUMMARY_FAILURE, mismatch.code());
    }

    /** Provider intent 必须先落盘，结算同时保存缓存用量和推进后的 SUMMARY 游标。 */
    @Test
    void commitsProviderIntentAndKnownUsageWithSummaryProgress() {
        TestHost host = new TestHost(ready(TurnExecutionState.Next.ASSISTANT, null));
        AgentSummaryOperation operation = new AgentSummaryOperation(host);
        operation.start(PLAN, SummaryDocument.empty());

        operation.begin(PROMPT, Optional.of(profile()));
        TurnExecutionState.ProviderPending pending = assertInstanceOf(TurnExecutionState.ProviderPending.class,
                host.cursor());
        assertEquals(TurnExecutionState.ProviderPurpose.SUMMARY, pending.purpose());
        assertTrue(host.dispatchAuthorityChecks > 0);
        assertTrue(host.lastWrites.isEmpty(), "intent commit must not invent a usage outcome");

        CheckpointUsage usage = new CheckpointUsage(12, 4, 18, 3, 2);
        ModelSummaryGenerator.SummaryOperation.Progress accepted = ModelSummaryGenerator.SummaryOperation.Progress.candidate(
                SummaryDocument.empty(), usage, 1, 7, PLAN);
        operation.settle(usage, accepted);

        TurnExecutionState.Ready ready = assertInstanceOf(TurnExecutionState.Ready.class, host.cursor());
        assertEquals(TurnExecutionState.Next.SUMMARY, ready.next());
        assertEquals(2, ready.common().nextProviderOrdinal());
        assertEquals(accepted, new AgentSummaryOperation(host).start(PLAN, SummaryDocument.empty()));
        ExecutionFacts.Usage fact = assertInstanceOf(ExecutionFacts.Usage.class, host.lastFacts.getLast());
        assertEquals(ExecutionFacts.UsagePurpose.SUMMARY, fact.purpose());
        assertEquals(ExecutionFacts.UsageCertainty.KNOWN, fact.certainty());
        assertEquals(3L, fact.usage().cacheReadTokens());
        assertEquals(2L, fact.usage().cacheWriteTokens());
        assertEquals(ModelUsage.InputAccounting.UNKNOWN, fact.usage().inputAccounting());
        assertEquals(AgentRecordIds.derive(host.execution, "agent.usage", pending.modelRequestId()),
                host.lastWrites.getFirst().recordId());
    }

    /** 可恢复故障记录 UNKNOWN 用量事实，回到已保存摘要阶段并占用原请求 ordinal。 */
    @Test
    void retryCommitsUnknownUsageAndReleasesPendingCursor() {
        TestHost host = new TestHost(ready(TurnExecutionState.Next.ASSISTANT, null));
        AgentSummaryOperation operation = new AgentSummaryOperation(host);
        operation.start(PLAN, SummaryDocument.empty());
        operation.begin(PROMPT, Optional.of(profile()));
        String modelRequestId = assertInstanceOf(TurnExecutionState.ProviderPending.class, host.cursor()).modelRequestId();

        operation.retry();

        TurnExecutionState.Ready ready = assertInstanceOf(TurnExecutionState.Ready.class, host.cursor());
        assertEquals(TurnExecutionState.Next.SUMMARY, ready.next());
        assertEquals(2, ready.common().nextProviderOrdinal());
        ExecutionFacts.Usage usage = assertInstanceOf(ExecutionFacts.Usage.class, host.lastFacts.getLast());
        assertEquals(modelRequestId, usage.modelRequestId());
        assertEquals(ExecutionFacts.UsageCertainty.UNKNOWN, usage.certainty());
        assertNull(usage.usage());
    }

    /** FAILED 子阶段原样恢复并阻止生成另一候选，完成 checkpoint 只接受候选态。 */
    @Test
    void restoresFailedProgressAndRejectsPrematureCheckpoint() {
        TurnExecutionState.SummaryProgress failed = new TurnExecutionState.SummaryProgress(
                EMPTY_SUMMARY_JSON, 0, 0, PLAN,
                TurnExecutionState.SummaryStage.FAILED, 1, 1, List.of("INVALID_SUMMARY"), PROMPT,
                new TurnExecutionState.KnownUsage(0, 0, 0, 0, 0));
        TestHost host = new TestHost(ready(TurnExecutionState.Next.SUMMARY, failed));
        ModelSummaryGenerator.SummaryOperation.Progress restored =
                new AgentSummaryOperation(host).start(PLAN, SummaryDocument.empty());

        assertEquals("FAILED", restored.stage().name());
        assertThrows(ContextException.class, () -> new AgentSummaryOperation(host).checkpointTurnOperation());
    }

    /** Checkpoint 使用最近确认的会话 revision，并保持业务提供的执行身份原样。 */
    @Test
    void checkpointUsesLatestRevisionAndNeutralTurnIdentity() {
        TestHost host = new TestHost(ready(TurnExecutionState.Next.ASSISTANT, null));
        AgentSummaryOperation operation = new AgentSummaryOperation(host);
        operation.start(PLAN, SummaryDocument.empty());
        long confirmedRevision = host.restored().conversation().revision();

        assertEquals(confirmedRevision, operation.checkpointSourceRevision(0));
        CheckpointStore.TurnOperation checkpoint = operation.checkpointTurnOperation().orElseThrow();
        assertEquals("turn_test", checkpoint.turnId());
        assertEquals(host.restored().execution().mutationVersion(), checkpoint.expectedTurnMutationVersion());
        assertEquals(TurnExecutionState.Next.ASSISTANT, checkpoint.completedExecution().next());
        assertNull(checkpoint.completedExecution().summary());
    }

    /** 无摘要历史但工具投影减少时仍以原 Ready 游标保护同一 checkpoint 事务。 */
    @Test
    void checkpointPreservesAssistantCursorWhenNoSummaryWasStarted() {
        TurnExecutionState.Ready current = ready(TurnExecutionState.Next.ASSISTANT, null);
        TestHost host = new TestHost(current);

        CheckpointStore.TurnOperation checkpoint = new AgentSummaryOperation(host)
                .checkpointTurnOperation().orElseThrow();

        assertEquals("turn_test", checkpoint.turnId());
        assertEquals(current, checkpoint.completedExecution());
        assertEquals(0, host.commitCount);
    }

    /** 手动压缩可从 COMPACTION 游标开始持久摘要计划并在 checkpoint 后回到 Assistant 工作游标。 */
    @Test
    void startsAndCompletesManualCompactionCursorAroundPersistedSummary() {
        TestHost host = new TestHost(ready(TurnExecutionState.Next.COMPACTION, null));
        AgentSummaryOperation operation = new AgentSummaryOperation(host);

        ModelSummaryGenerator.SummaryOperation.Progress initial = operation.start(PLAN, SummaryDocument.empty());
        assertEquals(ModelSummaryGenerator.SummaryOperation.Stage.CANDIDATE, initial.stage());
        assertEquals(TurnExecutionState.Next.SUMMARY, assertInstanceOf(TurnExecutionState.Ready.class,
                host.cursor()).next());
        operation.begin(PROMPT, Optional.of(profile()));
        CheckpointUsage usage = new CheckpointUsage(12, 4, 18, 3, 2);
        ModelSummaryGenerator.SummaryOperation.Progress accepted =
                ModelSummaryGenerator.SummaryOperation.Progress.candidate(
                        SummaryDocument.empty(), usage, 1, 7, PLAN);
        operation.settle(usage, accepted);

        TurnExecutionState.Ready completed = operation.checkpointTurnOperation().orElseThrow().completedExecution();
        assertEquals(TurnExecutionState.Next.ASSISTANT, completed.next());
        assertNull(completed.summary());
    }

    /** 没有摘要输入但压缩投影确实缩小时也结算 COMPACTION，不留下不可恢复的手动游标。 */
    @Test
    void completesManualCompactionCursorWhenNoSummaryWasNeeded() {
        TurnExecutionState.Ready current = ready(TurnExecutionState.Next.COMPACTION, null);
        TestHost host = new TestHost(current);

        CheckpointStore.TurnOperation checkpoint = new AgentSummaryOperation(host)
                .checkpointTurnOperation().orElseThrow();

        assertEquals(current.common(), checkpoint.completedExecution().common());
        assertEquals(TurnExecutionState.Next.ASSISTANT, checkpoint.completedExecution().next());
        assertNull(checkpoint.completedExecution().summary());
        assertEquals(0, host.commitCount);
    }

    /** 构造满足持久进度闭集的最小 READY 游标。 */
    private static TurnExecutionState.Ready ready(TurnExecutionState.Next next,
                                                  TurnExecutionState.SummaryProgress summary) {
        return new TurnExecutionState.Ready(new TurnExecutionState.Common(0, 0, 1, null, List.of()), next, summary);
    }

    /** 返回不包含凭据或端点的固定模型请求审计身份。 */
    private static ModelRequestIdentity profile() {
        return new ModelRequestIdentity("provider", "model", "openai_responses", "upstream-model",
                null, null, 8_192, 512, new JsonObject(Map.of()));
    }

    /** 创建只记录执行事实写入的 codec 代理，同时委托摘要读写给真实持久化实现。 */
    private static AgentExecutionDriver.Services services(AgentStore store, List<ExecutionFact> facts) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ExecutionRecordCodec delegate = new StorePayloadCodec(new ObjectMapper());
        ExecutionRecordCodec records = proxy(ExecutionRecordCodec.class, (proxy, method, arguments) -> {
            if (method.getName().equals("encode")) {
                String recordId = (String) arguments[0];
                ExecutionFact fact = (ExecutionFact) arguments[1];
                Instant occurredAt = (Instant) arguments[2];
                facts.add(fact);
                return new AgentStore.RecordWrite(recordId, "agent.usage", 1,
                        io.github.kongweiguang.ja.agent.domain.json.JsonObject.empty(), occurredAt);
            }
            try {
                return method.invoke(delegate, arguments);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
        JsonValueCodec json = proxy(JsonValueCodec.class, unsupportedCalls());
        AgentContextMapper mapper = new AgentContextMapper(json, records);
        return new AgentExecutionDriver.Services(store, new AgentLoopPersistence(store, clock), records,
                proxy(ExecutionCursorCodec.class, unsupportedCalls()),
                proxy(ExecutionEventCodec.class, unsupportedCalls()), mapper,
                proxy(ToolArgumentValidator.class, unsupportedCalls()),
                proxy(ExecutionControlCodec.class, unsupportedCalls()), json,
                new ExtensionFactRecordCodec(), new MiddlewareStateRecordCodec(json),
                clock, Duration.ofSeconds(30));
    }

    /** 为仅做非空装配的扩展接口提供 fail-fast 双替身，意外调用不伪装有效结果。 */
    private static InvocationHandler unsupportedCalls() {
        return (proxy, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class && method.getName().equals("toString")) {
                return "unexpected test dependency call";
            }
            throw new UnsupportedOperationException(method.getName());
        };
    }

    /** 创建类型安全的 JDK 动态代理，避免测试为无关 codec 实现重复样板代码。 */
    private static <T> T proxy(Class<T> contract, InvocationHandler handler) {
        Object proxy = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, handler);
        return contract.cast(proxy);
    }

    /** 仅记录提交后快照，模拟执行宿主按每次 commit 刷新游标和 CAS 水位。 */
    private static final class TestHost implements AgentSummaryOperation.Host {
        private final AgentIdentity identity = new AgentIdentity(
                "tenant_test", "thread_test", "turn_test", "user_test");
        private final AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(
                new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), identity.userId()), identity.turnId());
        private final List<ExecutionFact> lastFacts = new ArrayList<>();
        private final AgentExecutionDriver.Services services = AgentSummaryOperationTest.services(
                unusedStore(), lastFacts);
        private TurnExecutionState cursor;
        private AgentLoopPersistence.Restored restored;
        private List<AgentStore.RecordWrite> lastWrites = List.of();
        private int commitCount;
        private int dispatchAuthorityChecks;

        /** 使用固定状态和 revision 建立与真实 Store 快照形状一致的执行宿主。 */
        private TestHost(TurnExecutionState initialCursor) {
            cursor = initialCursor;
            AgentStore.ConversationSnapshot conversation = new AgentStore.ConversationSnapshot(
                    execution.conversation(), 1, 0, 1, NOW, NOW, 0);
            AgentStore.ExecutionSnapshot snapshot = new AgentStore.ExecutionSnapshot(
                    execution, 1, TurnState.RUNNING.name(), 0, TurnExecutionState.SCHEMA_VERSION,
                     io.github.kongweiguang.ja.agent.domain.json.JsonObject.empty(), 0, NOW, NOW);
            restored = new AgentLoopPersistence.Restored(conversation, snapshot, List.of(snapshot), List.of());
        }

        /** 将游标与写入一同推进，供测试断言调用者总是持久化后再观察状态。 */
        @Override
        public AgentStore.CommitReceipt commit(TurnState target, TurnExecutionState next,
                                               List<AgentStore.RecordWrite> records,
                                               List<ExecutionEvent> events) {
            assertEquals(TurnState.RUNNING, target);
            cursor = next;
            lastWrites = List.copyOf(records);
            commitCount++;
            long revision = restored.conversation().revision() + 1;
            long mutation = restored.execution().mutationVersion() + 1;
            long recordSequence = restored.conversation().recordSequence() + records.size();
            AgentStore.ConversationSnapshot conversation = new AgentStore.ConversationSnapshot(
                    execution.conversation(), revision, restored.conversation().eventSequence(),
                    restored.conversation().latestExecutionSequence(), NOW, NOW, recordSequence);
            AgentStore.ExecutionSnapshot snapshot = new AgentStore.ExecutionSnapshot(
                    execution, restored.execution().admissionSequence(), target.name(), mutation,
                    TurnExecutionState.SCHEMA_VERSION, io.github.kongweiguang.ja.agent.domain.json.JsonObject.empty(),
                    recordSequence, NOW, NOW);
            restored = new AgentLoopPersistence.Restored(conversation, snapshot, List.of(snapshot), List.of());
            return new AgentStore.CommitReceipt(revision, mutation, 0, 0,
                    io.github.kongweiguang.ja.agent.domain.json.JsonObject.empty(), true);
        }

        /** 统计 Provider 派发前的权限检查，防止测试替身绕过执行权门禁。 */
        @Override public void assertDispatchAuthority() { dispatchAuthorityChecks++; }

        /** 返回固定的中立执行身份。 */
        @Override public AgentIdentity identity() { return identity; }

        /** 返回不可变 codec 与存储视图。 */
        @Override public AgentExecutionDriver.Services services() { return services; }

        /** 返回最近提交的执行游标。 */
        @Override public TurnExecutionState cursor() { return cursor; }

        /** 返回最近确认的数据库水位。 */
        @Override public AgentLoopPersistence.Restored restored() { return restored; }

        /** 返回不被本测试调用的 Store 代理。 */
        private static AgentStore unusedStore() { return proxy(AgentStore.class, unsupportedCalls()); }
    }
}


