// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.runtime;

import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.InputQueue;
import io.github.kongweiguang.ja.conversation.domain.model.ProviderRequestProfile;
import io.github.kongweiguang.ja.conversation.domain.model.ProviderRequestUsage;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.domain.model.ContextCompactionEvent;
import io.github.kongweiguang.ja.conversation.domain.model.TurnEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证活动公开流基线的 revision、顺序、预算与生命周期边界。 */
final class ActiveStreamRegistryTest {
    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");
    private static final String MODEL_REQUEST_ID = "mreq_stream";
    private static final String NEXT_MODEL_REQUEST_ID = "mreq_next";

    /** 同 kind delta 只在相邻 seq 合并，并保留首段实际发生时间及 metadata 推进后的 revision。 */
    @Test
    void mergesAdjacentDeltasAndFollowsMetadataRevision() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry(7);
        register(registry, "turn_stream", "thr_stream", 7, 0, 0);
        registry.observe(state(7, TurnState.QUEUED, TurnState.RUNNING), T0);
        registry.observe(new TurnEvent.TextDelta("turn_stream", 1, "你", MODEL_REQUEST_ID), T0.plusSeconds(1));
        registry.observe(new TurnEvent.TextDelta("turn_stream", 2, "好", MODEL_REQUEST_ID), T0.plusSeconds(2));
        registry.observe(new TurnEvent.ReasoningSummaryDelta("turn_stream", 3, "summary", MODEL_REQUEST_ID),
                T0.plusSeconds(3));

        ActiveStreamRegistry.Snapshot before = registry.snapshot("thr_stream", 7,
                Set.of("turn_stream"), Map.of("turn_stream", 0L), Map.of("turn_stream", 0)).orElseThrow();
        assertEquals(3, before.streamSeq());
        assertEquals(2, before.segments().size());
        assertEquals("你好", before.segments().getFirst().text());
        assertEquals(T0.plusSeconds(1), before.segments().getFirst().occurredAt());
        assertEquals("reasoningSummary", before.segments().getLast().kind().wireName());

        registry.observeThreadRevision("ws_registry", "thr_stream", 8);
        ActiveStreamRegistry.Snapshot after = registry.snapshot("thr_stream", 8,
                Set.of("turn_stream"), Map.of("turn_stream", 0L), Map.of("turn_stream", 0)).orElseThrow();
        assertEquals(before.segments(), after.segments());
    }

    /** 相邻请求的同类草稿不得合并；请求身份只控制内部分段，公开 streamSeq 仍连续。 */
    @Test
    void keepsAdjacentModelRequestsInSeparateSegments() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry(7);
        register(registry, "turn_requests", "thr_requests", 1, 0, 0);
        registry.observe(new TurnEvent.TextDelta("turn_requests", 1, "first", MODEL_REQUEST_ID), T0);
        registry.observe(new TurnEvent.TextDelta("turn_requests", 2, " part", MODEL_REQUEST_ID), T0);
        registry.observe(new TurnEvent.TextDelta("turn_requests", 3, "second", NEXT_MODEL_REQUEST_ID), T0);
        registry.observe(new TurnEvent.TextDelta("turn_requests", 4, " part", NEXT_MODEL_REQUEST_ID), T0);

        ActiveStreamRegistry.Snapshot snapshot = registry.snapshot("thr_requests", 1,
                Set.of("turn_requests"), Map.of("turn_requests", 0L), Map.of("turn_requests", 0))
                .orElseThrow();
        assertEquals(4, snapshot.streamSeq());
        assertEquals(2, snapshot.segments().size());
        assertEquals("first part", snapshot.segments().getFirst().text());
        assertEquals(2, snapshot.segments().getFirst().streamSeq());
        assertEquals("second part", snapshot.segments().getLast().text());
        assertEquals(3, snapshot.segments().getLast().segmentStartSeq());
    }

    /** 快照必须在异步 Wire 映射期间与后续 delta 隔离，不能暴露 registry 的可变缓冲。 */
    @Test
    void keepsSnapshotUnchangedAfterLaterDelta() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry(7);
        register(registry, "turn_snapshot", "thr_snapshot", 1, 0, 0);
        registry.observe(new TurnEvent.TextDelta("turn_snapshot", 1, "first", MODEL_REQUEST_ID), T0);
        ActiveStreamRegistry.Snapshot earlier = registry.snapshot("thr_snapshot", 1,
                Set.of("turn_snapshot"), Map.of("turn_snapshot", 0L), Map.of("turn_snapshot", 0))
                .orElseThrow();

        registry.observe(new TurnEvent.TextDelta("turn_snapshot", 2, " second", MODEL_REQUEST_ID), T0);

        assertEquals(1, earlier.streamSeq());
        assertEquals("first", earlier.segments().getFirst().text());
        assertEquals("first second", registry.snapshot("thr_snapshot", 1,
                Set.of("turn_snapshot"), Map.of("turn_snapshot", 0L), Map.of("turn_snapshot", 0))
                .orElseThrow().segments().getFirst().text());
        assertThrows(UnsupportedOperationException.class,
                () -> earlier.segments().add(earlier.segments().getFirst()));
    }

    /** 接缝处的代理对会增加两字节；超出 64 KiB 时另起 segment，避免构造超限快照。 */
    @Test
    void accountsForSurrogatePairAcrossDeltaAtSegmentByteLimit() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry(7);
        register(registry, "turn_surrogate", "thr_surrogate", 1, 0, 0);
        String highSurrogate = String.valueOf((char) 0xD83D);
        String lowSurrogate = String.valueOf((char) 0xDE00);
        String firstPart = "a".repeat(ActiveStreamRegistry.MAX_SEGMENT_BYTES - 2) + highSurrogate;

        registry.observe(new TurnEvent.TextDelta("turn_surrogate", 1, firstPart, MODEL_REQUEST_ID), T0);
        registry.observe(new TurnEvent.TextDelta("turn_surrogate", 2, lowSurrogate, MODEL_REQUEST_ID), T0);

        ActiveStreamRegistry.Snapshot snapshot = registry.snapshot("thr_surrogate", 1,
                Set.of("turn_surrogate"), Map.of("turn_surrogate", 0L), Map.of("turn_surrogate", 0))
                .orElseThrow();
        assertEquals(2, snapshot.segments().size());
        assertEquals(firstPart, snapshot.segments().getFirst().text());
        assertEquals(lowSurrogate, snapshot.segments().getLast().text());
        assertEquals(1, snapshot.segments().getFirst().streamSeq());
        assertEquals(2, snapshot.segments().getLast().streamSeq());
    }

    /** 新 Turn 在首个 delta 前也需要 seq=0 空基线；首段缺口则绝不发布残缺正文。 */
    @Test
    void supportsEmptyBaselineAndRejectsFirstSequenceGap() {
        ActiveStreamRegistry empty = new ActiveStreamRegistry(8);
        register(empty, "turn_empty", "thr_empty", 1, 0, 0);
        ActiveStreamRegistry.Snapshot initial = empty.snapshot("thr_empty", 1,
                Set.of("turn_empty"), Map.of("turn_empty", 0L), Map.of("turn_empty", 0)).orElseThrow();
        assertEquals(0, initial.streamSeq());
        assertTrue(initial.segments().isEmpty());

        ActiveStreamRegistry gap = new ActiveStreamRegistry(8);
        register(gap, "turn_gap", "thr_gap", 1, 0, 0);
        gap.observe(new TurnEvent.TextDelta("turn_gap", 2, "late", MODEL_REQUEST_ID), T0);
        assertTrue(gap.snapshot("thr_gap", 1, Set.of("turn_gap"), Map.of("turn_gap", 0L),
                Map.of("turn_gap", 0)).isEmpty());
    }

    /** Thread revision 可被内部事务合法跳号；公开基线按持久水位前进，迟到旧事件不得回收新状态。 */
    @Test
    void acceptsDurableRevisionJumpAndIgnoresLateEvent() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry(12);
        register(registry, "turn_revision_gap", "thr_revision_gap", 10, 1, 0);
        registry.observe(new TurnEvent.StateChanged(new TurnEvent.Context(
                "evt_gap_start", "thr_revision_gap", "turn_revision_gap", 10, 1, T0),
                TurnState.QUEUED, TurnState.RUNNING), T0);
        registry.observe(new TurnEvent.TextDelta("turn_revision_gap", 1, "draft", MODEL_REQUEST_ID), T0);
        registry.observeThreadRevision("ws_registry", "thr_revision_gap", 13);
        assertEquals(1, registry.snapshot("thr_revision_gap", 13, Set.of("turn_revision_gap"),
                Map.of("turn_revision_gap", 1L), Map.of("turn_revision_gap", 0)).orElseThrow().streamSeq());

        registry.observe(new TurnEvent.Terminal(new TurnEvent.Context(
                "evt_gap_11", "thr_revision_gap", "turn_revision_gap", 11, 2, T0),
                TurnState.CANCELLED, "late", null, null, null, null, null), T0);
        assertFalse(registry.hasActive("thr_revision_gap"));
    }

    /**
     * metadata 可以先提交更高 Thread revision，但不能把尚未送达的 ModelStep 当成已消费；迟到
     * ModelStep 仍按 Turn mutation 水位清理草稿，之后才重新允许同一持久快照发布基线。
     */
    @Test
    void metadataOvertakesModelStepUntilDurableFenceArrives() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry(14);
        register(registry, "turn_model_fence", "thr_model_fence", 10, 1, 0);
        registry.observe(new TurnEvent.TextDelta("turn_model_fence", 1, "draft", MODEL_REQUEST_ID), T0);
        registry.observeThreadRevision("ws_registry", "thr_model_fence", 12);

        assertTrue(registry.snapshot("thr_model_fence", 12, Set.of("turn_model_fence"),
                Map.of("turn_model_fence", 2L), Map.of("turn_model_fence", 1)).isEmpty());

        registry.observe(modelStep("turn_model_fence", "thr_model_fence", 11, 2), T0.plusSeconds(1));

        ActiveStreamRegistry.Snapshot recovered = registry.snapshot("thr_model_fence", 12,
                Set.of("turn_model_fence"), Map.of("turn_model_fence", 2L), Map.of("turn_model_fence", 1))
                .orElseThrow();
        assertEquals(1, recovered.streamSeq());
        assertTrue(recovered.segments().isEmpty());
    }

    /**
     * 内部 dispatch 提交可以先把 execution modelRound 推到下一轮，但不能伪造 ModelStep 已清理；
     * 迟到语义事件必须仍能清理旧 draft，同轮重复事件也不能清掉随后产生的新 draft。
     */
    @Test
    void internalCommitDoesNotOvertakeLateModelStepOrClearNextDraft() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry(16);
        register(registry, "turn_commit_boundary", "thr_commit_boundary", 10, 1, 0);
        registry.observe(new TurnEvent.TextDelta("turn_commit_boundary", 1, "第一段", MODEL_REQUEST_ID), T0);

        registry.observeTurnCommit("thr_commit_boundary", "turn_commit_boundary", 12, 3, 1,
                null, T0.plusSeconds(1));
        assertTrue(registry.snapshot("thr_commit_boundary", 12, Set.of("turn_commit_boundary"),
                Map.of("turn_commit_boundary", 3L), Map.of("turn_commit_boundary", 1)).isEmpty());

        registry.observe(modelStep("turn_commit_boundary", "thr_commit_boundary", 11, 2),
                T0.plusSeconds(2));
        ActiveStreamRegistry.Snapshot recovered = registry.snapshot("thr_commit_boundary", 12,
                Set.of("turn_commit_boundary"), Map.of("turn_commit_boundary", 3L),
                Map.of("turn_commit_boundary", 1)).orElseThrow();
        assertTrue(recovered.segments().isEmpty());

        registry.observe(new TurnEvent.TextDelta("turn_commit_boundary", 2, "第二段", NEXT_MODEL_REQUEST_ID),
                T0.plusSeconds(3));
        registry.observe(modelStep("turn_commit_boundary", "thr_commit_boundary", 12, 3),
                T0.plusSeconds(4));
        ActiveStreamRegistry.Snapshot afterDuplicate = registry.snapshot("thr_commit_boundary", 12,
                Set.of("turn_commit_boundary"), Map.of("turn_commit_boundary", 3L),
                Map.of("turn_commit_boundary", 1)).orElseThrow();
        assertEquals("第二段", afterDuplicate.segments().getFirst().text());
    }

    /**
     * 重试只清除失败请求的临时草稿；Turn 的 streamSeq 保持单调，重复通知不得清掉下一次请求已接纳的正文。
     */
    @Test
    void clearsRetryDraftWithoutResettingSequenceOrClearingNextAttempt() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry(18);
        register(registry, "turn_retry", "thr_retry", 1, 1, 0);
        registry.observe(new TurnEvent.TextDelta("turn_retry", 1, "failed partial", MODEL_REQUEST_ID), T0);
        registry.observeTurnCommit("thr_retry", "turn_retry", 2, 2, 0, null, T0.plusSeconds(1));
        TurnEvent.RetryStarted retry = new TurnEvent.RetryStarted(
                new TurnEvent.Context("evt_retry", "thr_retry", "turn_retry", 2, 2, T0.plusSeconds(1)),
                2);

        registry.observe(retry, T0.plusSeconds(1));
        ActiveStreamRegistry.Snapshot cleared = registry.snapshot("thr_retry", 2,
                Set.of("turn_retry"), Map.of("turn_retry", 2L), Map.of("turn_retry", 0)).orElseThrow();
        assertEquals(1, cleared.streamSeq());
        assertTrue(cleared.segments().isEmpty());

        registry.observe(new TurnEvent.TextDelta("turn_retry", 2, "next attempt", NEXT_MODEL_REQUEST_ID),
                T0.plusSeconds(2));
        registry.observe(retry, T0.plusSeconds(3));
        ActiveStreamRegistry.Snapshot nextAttempt = registry.snapshot("thr_retry", 2,
                Set.of("turn_retry"), Map.of("turn_retry", 2L), Map.of("turn_retry", 0)).orElseThrow();
        assertEquals(2, nextAttempt.streamSeq());
        assertEquals("next attempt", nextAttempt.segments().getFirst().text());
    }

    /** STOP 结算旧 Assistant 并消费队列输入时，新的 execution modelRound 必须绑定到清稿 fence。 */
    @Test
    void assistantSettlementAdvancesClearedRoundBeforeNextDelta() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry(17);
        register(registry, "turn_settlement", "thr_settlement", 1, 1, 0);
        registry.observe(new TurnEvent.TextDelta("turn_settlement", 1, "旧回复", MODEL_REQUEST_ID), T0);

        UserContent content = new UserContent(List.of(new TextContent("继续")));
        InputQueue.QueuedInput input = new InputQueue.QueuedInput(
                "input_settlement", "turn_settlement", content, InputQueue.Kind.FOLLOW_UP,
                List.of(), InputQueue.Status.PENDING, null, 1, T0);
        TurnEvent.InputConsumed event = new TurnEvent.InputConsumed(
                new TurnEvent.Context("evt_settlement", "thr_settlement", "turn_settlement", 2, 2, T0),
                input, new TurnEvent.UserItem("item_settlement", T0, "turn_settlement", content, List.of()),
                new InputQueue("turn_settlement", 2, true, List.of()),
                new TurnEvent.AssistantSettlement("item_old_assistant", "旧回复", 1, usage(), null));

        registry.observeTurnCommit("thr_settlement", "turn_settlement", 2, 2, 1,
                event, T0.plusSeconds(1));
        registry.observe(new TurnEvent.TextDelta("turn_settlement", 2, "新回复", NEXT_MODEL_REQUEST_ID),
                T0.plusSeconds(2));
        TurnEvent.InputConsumed lateDuplicate = new TurnEvent.InputConsumed(
                new TurnEvent.Context("evt_settlement_late", "thr_settlement", "turn_settlement", 2, 2, T0),
                input, new TurnEvent.UserItem("item_settlement_late", T0, "turn_settlement", content, List.of()),
                new InputQueue("turn_settlement", 2, true, List.of()),
                new TurnEvent.AssistantSettlement("item_old_assistant", "旧回复", 1, usage(), null));
        registry.observe(lateDuplicate, T0.plusSeconds(3));

        ActiveStreamRegistry.Snapshot snapshot = registry.snapshot("thr_settlement", 2,
                Set.of("turn_settlement"), Map.of("turn_settlement", 2L),
                Map.of("turn_settlement", 1)).orElseThrow();
        assertEquals(2, snapshot.streamSeq());
        assertEquals("新回复", snapshot.segments().getFirst().text());
    }

    /** 迟到 terminal 不能因 metadata 的较高 revision 被忽略，否则终态 Turn 会永久泄漏 registry。 */
    @Test
    void metadataOvertakesTerminalStillAbandonsByTurnIdentity() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry(15);
        register(registry, "turn_terminal_fence", "thr_terminal_fence", 10, 1, 0);
        registry.observe(new TurnEvent.TextDelta("turn_terminal_fence", 1, "draft", MODEL_REQUEST_ID), T0);
        registry.observeThreadRevision("ws_registry", "thr_terminal_fence", 12);

        registry.observe(new TurnEvent.Terminal(new TurnEvent.Context(
                "evt_terminal_fence", "thr_terminal_fence", "turn_terminal_fence", 11, 2, T0),
                TurnState.CANCELLED, "late", null, null, null, null, null), T0.plusSeconds(1));

        assertFalse(registry.hasActive("thr_terminal_fence"));
    }

    /** queued Turn 与 running Turn 同时存在时，带公开流的运行 Turn 不能被空 queued 游标遮蔽。 */
    @Test
    void choosesRunningTurnOverQueuedTurn() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry(9);
        register(registry, "ws_queue", "turn_running", "thr_queue", 10, 0, 0);
        register(registry, "ws_queue", "turn_queued", "thr_queue", 10, 0, 0);
        registry.observe(state("turn_running", 10, TurnState.QUEUED, TurnState.RUNNING), T0);
        registry.observe(new TurnEvent.TextDelta("turn_running", 1, "running", MODEL_REQUEST_ID), T0);
        registry.observeCompaction(new ContextCompactionEvent.Context(
                "evt_compaction", "ws_queue", "thr_queue", "turn_running", 11, T0,
                "cmp_queue", ContextCompactionEvent.Trigger.AUTOMATIC, 10, 100L, 50L,
                ContextCompactionEvent.STRATEGY_VERSION, 0L));
        /* 压缩推进整个 Thread 水位；已登记的 queued 与 running 都必须能在新 revision 读取。 */

        Optional<ActiveStreamRegistry.Snapshot> result = registry.snapshot("thr_queue", 11,
                Set.of("turn_queued", "turn_running"),
                Map.of("turn_queued", 0L, "turn_running", 0L), Map.of("turn_queued", 0, "turn_running", 0));
        assertEquals("turn_running", result.orElseThrow().turnId());
        assertEquals(1, result.orElseThrow().streamSeq());

        registry.observe(new TurnEvent.TextDelta("turn_running", 2, "继续", MODEL_REQUEST_ID), T0.plusSeconds(1));
        assertEquals(2, registry.snapshot("thr_queue", 11, Set.of("turn_queued", "turn_running"),
                Map.of("turn_queued", 0L, "turn_running", 0L),
                Map.of("turn_queued", 0, "turn_running", 0))
                .orElseThrow().streamSeq());
    }

    /** 单个过长 delta 不切成伪造 seq；模型步骤提交后仍保留游标但可返回空 segment。 */
    @Test
    void invalidatesOversizedDeltaAndClearsCommittedDraft() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry(10);
        register(registry, "turn_budget", "thr_budget", 2, 0, 0);
        registry.observe(new TurnEvent.TextDelta("turn_budget", 1,
                "中".repeat(ActiveStreamRegistry.MAX_SEGMENT_BYTES), MODEL_REQUEST_ID), T0);
        assertTrue(registry.snapshot("thr_budget", 2, Set.of("turn_budget"),
                Map.of("turn_budget", 0L), Map.of("turn_budget", 0)).isEmpty());

        ActiveStreamRegistry committed = new ActiveStreamRegistry(10);
        register(committed, "turn_commit", "thr_commit", 2, 0, 0);
        committed.observe(new TurnEvent.TextDelta("turn_commit", 1, "draft", MODEL_REQUEST_ID), T0);
        committed.observe(modelStep(3), T0.plusSeconds(1));
        ActiveStreamRegistry.Snapshot baseline = committed.snapshot("thr_commit", 3, Set.of("turn_commit"),
                Map.of("turn_commit", 1L), Map.of("turn_commit", 1))
                .orElseThrow();
        assertEquals(1, baseline.streamSeq());
        assertTrue(baseline.segments().isEmpty());
    }

    /** 终态和连接清理都释放活动流，避免后续 read 继续拿到已经结束的 Spinner 基线。 */
    @Test
    void clearsOnTerminalAndExplicitClose() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry(11);
        register(registry, "turn_terminal", "thr_terminal", 5, 0, 0);
        registry.observe(new TurnEvent.TextDelta("turn_terminal", 1, "draft", MODEL_REQUEST_ID), T0);
        registry.observe(new TurnEvent.Terminal(context(6), TurnState.CANCELLED,
                "", null, null, null, null, null), T0.plusSeconds(1));
        assertFalse(registry.hasActive("thr_terminal"));

        register(registry, "turn_close", "thr_terminal", 6, 0, 0);
        registry.clear();
        assertFalse(registry.hasActive("thr_terminal"));
    }

    /** Thread 删除必须一次释放其所有 Turn，不能把 Thread ID 误当作某一个 Turn ID。 */
    @Test
    void abandonsAllTurnsForThread() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry(13);
        register(registry, "turn_delete_running", "thr_delete", 4, 0, 0);
        register(registry, "turn_delete_queued", "thr_delete", 4, 0, 0);
        registry.observe(new TurnEvent.TextDelta("turn_delete_running", 1, "draft", MODEL_REQUEST_ID), T0);

        registry.abandonThread("thr_delete");

        assertFalse(registry.hasActive("thr_delete"));
        assertTrue(registry.snapshot("thr_delete", 4, Set.of("turn_delete_running", "turn_delete_queued"),
                Map.of("turn_delete_running", 0L, "turn_delete_queued", 0L),
                Map.of("turn_delete_running", 0, "turn_delete_queued", 0)).isEmpty());
    }

    /** 同一 Workspace/Thread 的活动 Turn 共用水位，其他 Workspace/Thread 与较旧观察保持原值。 */
    @Test
    void scopesThreadRevisionAndPreservesMaximum() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry(19);
        register(registry, "ws_first", "turn_first", "thr_shared", 5, 0, 0);
        register(registry, "ws_first", "turn_sibling", "thr_shared", 5, 0, 0);
        register(registry, "ws_second", "turn_second", "thr_shared", 7, 0, 0);
        register(registry, "ws_first", "turn_other", "thr_other", 2, 0, 0);

        registry.observeThreadRevision("ws_first", "thr_shared", 6);
        registry.observeThreadRevision("ws_first", "thr_shared", 4);

        assertTrue(registry.snapshot("thr_shared", 6, Set.of("turn_first"),
                Map.of("turn_first", 0L), Map.of("turn_first", 0)).isPresent());
        assertTrue(registry.snapshot("thr_shared", 6, Set.of("turn_sibling"),
                Map.of("turn_sibling", 0L), Map.of("turn_sibling", 0)).isPresent());
        assertTrue(registry.snapshot("thr_shared", 7, Set.of("turn_second"),
                Map.of("turn_second", 0L), Map.of("turn_second", 0)).isPresent());
        assertTrue(registry.snapshot("thr_other", 2, Set.of("turn_other"),
                Map.of("turn_other", 0L), Map.of("turn_other", 0)).isPresent());
    }

    /** 仅隔离测试显式选择 Workspace，其他活动流回归共用固定工作区。 */
    private static void register(ActiveStreamRegistry registry, String workspaceId, String turnId,
                                 String threadId, long revision, long mutationVersion, int modelRound) {
        registry.register(turnId, workspaceId, threadId, revision, mutationVersion, modelRound);
    }

    /** 为不涉及工作区隔离的用例登记固定工作区，生产 API 仍要求显式身份。 */
    private static void register(ActiveStreamRegistry registry, String turnId, String threadId, long revision,
                                 long mutationVersion, int modelRound) {
        register(registry, "ws_registry", turnId, threadId, revision, mutationVersion, modelRound);
    }

    /** 构造一次合法非终态迁移，确保 registry 同步其运行生命周期用于候选选择。 */
    private static TurnEvent.StateChanged state(long revision, TurnState from, TurnState to) {
        return state("turn_stream", revision, from, to);
    }

    /** 允许测试为不同 Turn 构造相同的状态迁移形状。 */
    private static TurnEvent.StateChanged state(String turnId, long revision, TurnState from, TurnState to) {
        return new TurnEvent.StateChanged(new TurnEvent.Context(
                "evt_" + turnId, "thr_" + (turnId.contains("running") ? "queue" : "stream"), turnId,
                revision, 0, T0), from, to);
    }

    /** 终态上下文使用公开基线的同一 Thread/Turn 身份。 */
    private static TurnEvent.Context context(long revision) {
        return new TurnEvent.Context("evt_terminal", "thr_terminal", "turn_terminal", revision, 1, T0);
    }

    /** 模型步骤提交代表持久正文已落库，测试只关心它清空公开草稿而不回退 streamSeq。 */
    private static TurnEvent.ModelStepCommitted modelStep(long revision) {
        return new TurnEvent.ModelStepCommitted(contextForCommit(revision), "item_commit", "draft", null, 1,
                usage(), List.of(new TurnEvent.ToolCall("call_commit", "read_file", presentation(), 0)));
    }

    /** 为模型提交测试绑定独立 Turn 身份，避免影响终态清理用例。 */
    private static TurnEvent.Context contextForCommit(long revision) {
        return new TurnEvent.Context("evt_commit", "thr_commit", "turn_commit", revision, 1, T0);
    }

    /** 为 metadata 交错回归构造指定 Turn mutation 水位的 ModelStep。 */
    private static TurnEvent.ModelStepCommitted modelStep(String turnId, String threadId,
                                                          long revision, long mutationVersion) {
        TurnEvent.Context context = new TurnEvent.Context("evt_" + turnId + "_" + mutationVersion,
                threadId, turnId, revision, mutationVersion, T0);
        return new TurnEvent.ModelStepCommitted(context, "item_fence", "draft", null, 1,
                usage(), List.of(new TurnEvent.ToolCall("call_fence", "read_file", presentation(), 0)));
    }

    /** 事件构造需要完整请求级 Profile，防止测试回退到已废弃的裸 token DTO。 */
    private static ProviderRequestUsage usage() {
        ProviderRequestProfile profile = new ProviderRequestProfile(
                "provider_test", "model_test", "openai_responses", "gpt-test", "medium", "medium",
                AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT, "cfg_test", "prompt_test",
                "0".repeat(64), 100_000, 8_192);
        return new ProviderRequestUsage("modelreq_commit", 1, 1, ProviderRequestUsage.Purpose.ASSISTANT,
                ProviderRequestUsage.Certainty.KNOWN, profile, new ModelUsage(1, 1, 2));
    }

    /** Tool 调用只用于满足 ModelStepCommitted 的结构约束，不参与基线正文。 */
    private static ToolPresentation presentation() {
        return new ToolPresentation(ToolPresentation.Kind.READ, "read", ToolPresentation.Status.PENDING,
                "a.txt", "ok", "读取完成", List.of("a.txt"), null, ".", null, null, null, 1L, false, null);
    }
}
