// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context.compaction;

import io.github.kongweiguang.ja.conversation.application.context.ContextException;
import io.github.kongweiguang.ja.conversation.application.context.ContextMessage;
import io.github.kongweiguang.ja.conversation.application.context.ContextPolicy;
import io.github.kongweiguang.ja.conversation.application.context.ContextTokenMeter;
import io.github.kongweiguang.ja.conversation.application.context.ModelContinuation;
import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointStore;
import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointUsage;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryDocument;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryGenerator;
import io.github.kongweiguang.ja.conversation.domain.ContextBudget;
import io.github.kongweiguang.ja.conversation.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationSource;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 上下文压缩恢复回归集，锁定检查点 CAS、摘要增量、溢出重投影与 durable receipt。 */
final class ContextCompactorTest {
    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");
    private static final long INITIAL_REVISION = 7;
    private static final CheckpointUsage USAGE = new CheckpointUsage(120, 30, 150, 10, 0);
    private static final ContextTokenMeter TEST_METER = ContextCompactorTest::measureFixtureTokens;

    /** 压缩测试以消息和摘要声明的权重计量，避免把生产 Provider tokenizer 复制进 fixture。 */
    private static ContextTokenMeter.Measurement measureFixtureTokens(
            List<ContextMessage> messages, SummaryDocument summary,
            Optional<ModelContinuation> continuation, boolean localCompaction) {
        long tokens = summary.allFacts().stream()
                .mapToLong(fact -> Math.max(1L, (fact.text().codePointCount(0, fact.text().length()) + 3L) / 4L))
                .sum();
        for (ContextMessage message : messages) {
            tokens = Math.addExact(tokens, message.estimatedTokens());
        }
        if (continuation.isPresent()) {
            tokens = Math.addExact(tokens,
                    Math.max(1L, (continuation.orElseThrow().opaqueState().length() + 3L) / 4L));
        }
        return new ContextTokenMeter.Measurement(Math.max(1L, tokens), "0".repeat(64));
    }

    /** 锁定压缩持久化 usage 并清除旧 continuation，避免恢复后续接失效响应。 */
    @Test
    void compactionPersistsUsageAndResetsContinuation() {
        MemoryCheckpointStore store = new MemoryCheckpointStore("thread-ctx", INITIAL_REVISION);
        List<ContextMessage> source = evictingHistory();
        SummaryDocument delta = summary("ship the feature", "implemented compaction");
        ContextCompactionService service = service(store, request -> result(delta, USAGE));

        ContextCompactionService.CompactionResult compacted = service.compact(request(store, source, true,
                Optional.of(new ModelContinuation("openai_responses", "resp_private"))));

        CheckpointStore.ContextCheckpoint checkpoint = compacted.checkpoint().orElseThrow();
        assertTrue(compacted.compacted());
        assertTrue(compacted.prompt().localCompaction());
        assertTrue(compacted.prompt().continuation().isEmpty());
        assertEquals("ship the feature", compacted.prompt().summary().goals().getFirst().text());
        assertEquals(INITIAL_REVISION, checkpoint.sourceRevision());
        assertEquals(USAGE, checkpoint.usage());
        assertEquals("checkpoint_test_1", checkpoint.checkpointId());
        assertEquals(1, store.appended().size());
        assertEquals("old context", text(source.getFirst()));
    }

    /** 锁定显式检查点 ID 使用持久命名空间，避免进程重启后标识来源改变。 */
    @Test
    void explicitCheckpointIdSourceUsesDurableNamespace() {
        MemoryCheckpointStore store = new MemoryCheckpointStore("thread-ctx", INITIAL_REVISION);
        ContextCompactionService service = new ContextCompactionService(
                store, new ContextPolicy(), request -> result(summary("goal", "progress"), USAGE),
                Clock.fixed(NOW, ZoneOffset.UTC), ContextCompactionService::newCheckpointId);

        CheckpointStore.ContextCheckpoint checkpoint = service.compact(
                request(store, evictingHistory(), true, Optional.empty())).checkpoint().orElseThrow();

        assertTrue(checkpoint.checkpointId().startsWith("checkpoint_"));
        assertTrue(checkpoint.checkpointId().matches("checkpoint_[A-Za-z0-9][A-Za-z0-9._-]*"));
    }

    /** 锁定来源 revision 变化会拒绝追加，防止摘要写入过期历史。 */
    @Test
    void sourceRevisionChangeRejectsCheckpointAppend() {
        MemoryCheckpointStore store = new MemoryCheckpointStore("thread-ctx", INITIAL_REVISION);
        AtomicBoolean sourceAdvanced = new AtomicBoolean();
        ContextCompactionService service = service(store, request -> {
            if (sourceAdvanced.compareAndSet(false, true)) {
                store.advanceSourceRevision(INITIAL_REVISION + 1);
            }
            return result(summary("winner", "race"), USAGE);
        });

        ContextException failure = assertThrows(ContextException.class,
                () -> service.compact(request(INITIAL_REVISION, evictingHistory(), true, Optional.empty())));

        assertEquals(ContextException.Code.CAS_CONFLICT, failure.code());
        assertEquals(INITIAL_REVISION + 1, store.read("thread-ctx").threadRevision());
        assertTrue(store.appended().isEmpty());
    }

    /** 锁定过期请求在生成摘要前失败，避免对注定 CAS 失败的数据调用模型。 */
    @Test
    void staleRequestRevisionFailsBeforeSummaryGeneration() {
        MemoryCheckpointStore store = new MemoryCheckpointStore("thread-ctx", INITIAL_REVISION + 1);
        AtomicBoolean invoked = new AtomicBoolean();
        ContextCompactionService service = service(store, request -> {
            invoked.set(true);
            return result(summary("stale", "invalid"), USAGE);
        });

        ContextException failure = assertThrows(ContextException.class,
                () -> service.compact(request(INITIAL_REVISION, evictingHistory(), true, Optional.empty())));

        assertEquals(ContextException.Code.CAS_CONFLICT, failure.code());
        assertFalse(invoked.get());
        assertTrue(store.appended().isEmpty());
    }

    /** 锁定跨 revision 追加检查点并累积摘要事实，避免增量压缩覆盖先前内容。 */
    @Test
    void checkpointsAppendAndSummariesAccumulateAcrossRevisions() {
        MemoryCheckpointStore store = new MemoryCheckpointStore("thread-ctx", INITIAL_REVISION);
        List<SummaryGenerator.SummaryRequest> requests = new ArrayList<>();
        int[] round = {0};
        ContextCompactionService service = service(store, request -> {
            requests.add(request);
            round[0]++;
            List<SummaryDocument.Fact> progress = new ArrayList<>(request.previousSummary()
                    .orElseGet(SummaryDocument::empty).currentProgress());
            progress.add(new SummaryDocument.Fact("progress-" + round[0], round[0]));
            SummaryDocument cumulative = new SummaryDocument(
                    List.of(new SummaryDocument.Fact("goal-" + round[0], round[0])), List.of(), List.of(),
                    progress, List.of(), List.of(), List.of(),
                    List.of(new SummaryDocument.Fact("critical", round[0])), List.of(), List.of(), List.of());
            return result(cumulative,
                    new CheckpointUsage(round[0] * 10L, round[0], round[0] * 11L, 0, 0));
        });

        ContextCompactionService.CompactionResult first = service.compact(
                request(store, evictingHistory(), true, Optional.empty()));
        store.advanceSourceRevision(INITIAL_REVISION + 1);
        List<ContextMessage> secondHistory = List.of(
                ContextMessage.text("msg-1", "turn-1", 1, ContextMessage.Role.USER,
                        "old context", 20_000),
                ContextMessage.text("msg-2", "turn-2", 2, ContextMessage.Role.USER,
                        "middle context", 20_000),
                ContextMessage.text("msg-3", "turn-3", 3, ContextMessage.Role.USER,
                        "latest", 2));
        ContextCompactionService.CompactionResult second = service.compact(
                request(store, secondHistory, true, Optional.empty()));

        assertEquals(2, store.appended().size());
        assertEquals(INITIAL_REVISION, store.appended().getFirst().sourceRevision());
        assertEquals(INITIAL_REVISION + 1, store.appended().getLast().sourceRevision());
        assertEquals("checkpoint_test_1", first.checkpoint().orElseThrow().checkpointId());
        assertEquals("checkpoint_test_2", second.checkpoint().orElseThrow().checkpointId());
        assertTrue(requests.getFirst().previousSummary().isEmpty());
        assertEquals("goal-1", requests.getLast().previousSummary().orElseThrow()
                .goals().getFirst().text());
        assertEquals(List.of("progress-1", "progress-2"), second.prompt().summary().currentProgress().stream()
                .map(SummaryDocument.Fact::text).toList());
        assertEquals(List.of(1L), requests.getFirst().evictedMessages().stream()
                .map(ContextMessage::ordinal).toList());
        assertEquals(List.of(2L), requests.getLast().evictedMessages().stream()
                .map(ContextMessage::ordinal).toList());
    }

    /** 锁定已有检查点直接应用且不重复追加，确保恢复路径幂等。 */
    @Test
    void existingCheckpointIsAppliedWithoutDuplicateAppend() {
        MemoryCheckpointStore store = new MemoryCheckpointStore("thread-ctx", INITIAL_REVISION);
        ContextCompactionService service = service(store,
                request -> result(summary("durable", "first"), USAGE));
        CheckpointStore.ContextCheckpoint first = service.compact(
                request(store, evictingHistory(), true, Optional.empty())).checkpoint().orElseThrow();
        store.advanceSourceRevision(INITIAL_REVISION + 1);
        List<ContextMessage> source = List.of(
                ContextMessage.text("msg-1", "turn-1", 1, ContextMessage.Role.USER,
                        "old context", 20_000),
                ContextMessage.text("msg-2", "turn-2", 2, ContextMessage.Role.USER,
                        "latest", 2),
                ContextMessage.text("msg-3", "turn-3", 3, ContextMessage.Role.USER,
                        "new", 2));

        ContextCompactionService.CompactionResult reused = service.compact(request(store, source, false,
                Optional.of(new ModelContinuation("openai_responses", "must-clear"))));

        assertTrue(reused.compacted());
        assertEquals(first, reused.checkpoint().orElseThrow());
        assertEquals("durable", reused.prompt().summary().goals().getFirst().text());
        assertTrue(reused.prompt().continuation().isEmpty());
        assertEquals(List.of(2L, 3L), reused.prompt().messages().stream().map(ContextMessage::ordinal).toList());
        assertEquals(1, store.appended().size());
    }

    /** 锁定同一来源 revision 复用唯一检查点，防止重试制造重复摘要。 */
    @Test
    void sameSourceRevisionReusesUniqueCheckpoint() {
        MemoryCheckpointStore store = new MemoryCheckpointStore("thread-ctx", INITIAL_REVISION);
        int[] generations = {0};
        ContextCompactionService service = service(store, request -> {
            generations[0]++;
            return result(summary("durable", "once"), USAGE);
        });
        List<ContextMessage> source = evictingHistory();
        CheckpointStore.ContextCheckpoint first = service.compact(
                request(store, source, true, Optional.empty())).checkpoint().orElseThrow();

        ContextCompactionService.CompactionResult repeated = service.compact(
                request(store, source, true, Optional.empty()));

        assertEquals(first, repeated.checkpoint().orElseThrow());
        assertEquals(1, generations[0]);
        assertEquals(1, store.appended().size());
        assertTrue(repeated.prompt().continuation().isEmpty());
    }

    /** 锁定 durable receipt 先于 Provider 失败可见，确保已提交压缩不会丢失。 */
    @Test
    void committedReceiptPrecedesProviderFailureAndRemainsDurable() {
        ReceiptCheckpointStore store = new ReceiptCheckpointStore("thread-ctx", INITIAL_REVISION);
        ContextCompactionService service = service(store,
                request -> result(summary("durable", "before failure"), USAGE));
        List<String> order = new ArrayList<>();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new OverflowRecovery(service).execute(receiptRequest(store), receipt -> {
                    order.add("receipt");
                    assertEquals(INITIAL_REVISION + 1, receipt.threadRevision());
                }, prompt -> {
                    order.add("provider");
                    throw new IllegalStateException("provider failed");
                }));

        assertEquals("provider failed", failure.getMessage());
        assertEquals(List.of("receipt", "provider"), order);
        assertEquals(1, store.commitCount());
        assertEquals(INITIAL_REVISION + 1, store.threadRevision());
        assertTrue(store.latest().isPresent());
    }

    /** 锁定 Provider 取消前已发布提交回执，避免调用方误判压缩未持久化。 */
    @Test
    void committedReceiptPrecedesProviderCancellation() {
        ReceiptCheckpointStore store = new ReceiptCheckpointStore("thread-ctx", INITIAL_REVISION);
        ContextCompactionService service = service(store,
                request -> result(summary("durable", "before cancellation"), USAGE));
        List<CheckpointStore.CommittedCheckpoint> receipts = new ArrayList<>();

        assertThrows(CancellationException.class,
                () -> new OverflowRecovery(service).execute(receiptRequest(store), receipts::add,
                        prompt -> { throw new CancellationException("cancelled"); }));

        assertEquals(1, receipts.size());
        assertTrue(receipts.getFirst().newlyCommitted());
        assertEquals(INITIAL_REVISION + 1, receipts.getFirst().threadRevision());
        assertEquals(INITIAL_REVISION + 1, store.threadRevision());
        assertEquals(1, store.commitCount());
    }

    /** 锁定复用检查点不会推进 revision 或生成新回执，保持恢复操作只读。 */
    @Test
    void reusedCheckpointDoesNotAdvanceRevisionOrReceipt() {
        ReceiptCheckpointStore store = new ReceiptCheckpointStore("thread-ctx", INITIAL_REVISION);
        ContextCompactionService service = service(store,
                request -> result(summary("durable", "once"), USAGE));
        ContextCompactionService.CompactionResult first = service.compact(receiptRequest(store));
        long committedRevision = store.threadRevision();

        ContextCompactionService.CompactionResult reused = service.compact(receiptRequest(store));

        assertTrue(first.committedReceipt().orElseThrow().newlyCommitted());
        assertTrue(reused.committedReceipt().isEmpty());
        assertEquals(first.checkpoint().orElseThrow(), reused.checkpoint().orElseThrow());
        assertEquals(committedRevision, store.threadRevision());
        assertEquals(1, store.commitCount());
    }

    /** 锁定超大 Turn 的分片证据进入摘要，避免预算裁剪吞掉关键上下文。 */
    @Test
    void oversizedTurnCarriesSplitEvidenceIntoSummary() {
        MemoryCheckpointStore store = new MemoryCheckpointStore("thread-ctx", INITIAL_REVISION);
        AtomicReference<SummaryGenerator.SummaryRequest> observed = new AtomicReference<>();
        ContextCompactionService service = service(store, request -> {
            observed.set(request);
            return result(summary("split", "bounded"), USAGE);
        });
        String huge = "0123456789".repeat(20_000);
        List<ContextMessage> source = List.of(ContextMessage.text("msg-1", "turn-1", 1,
                ContextMessage.Role.USER, huge, 50_000));

        ContextCompactionService.CompactionResult compacted = service.compact(
                request(store, source, false, Optional.empty(), tinyBudget()));

        ContextPolicy.TurnSplit split = observed.get().splitTurn().orElseThrow();
        assertEquals("turn-1", split.turnId());
        assertEquals(1L, split.prefix().getLast().ordinal());
        assertEquals(1L, split.suffix().getFirst().ordinal());
        assertEquals(1L, compacted.checkpoint().orElseThrow().throughOrdinal());
        assertEquals(1L, compacted.checkpoint().orElseThrow().retainedFromOrdinal());
        assertTrue(compacted.checkpoint().orElseThrow().retainedSplit().isPresent());
    }

    /** 锁定分片后缀跨恢复保留并参与后续增量摘要，防止尾部证据断链。 */
    @Test
    void splitSuffixSurvivesRecoveryAndLaterJoinsIncrementalSummary() {
        MemoryCheckpointStore store = new MemoryCheckpointStore("thread-ctx", INITIAL_REVISION);
        List<SummaryGenerator.SummaryRequest> requests = new ArrayList<>();
        ContextCompactionService service = service(store, request -> {
            requests.add(request);
            return result(summary("round-" + requests.size(), "progress-" + requests.size()), USAGE);
        });
        String huge = "0123456789".repeat(20_000);
        List<ContextMessage> firstHistory = List.of(ContextMessage.text("split-source", "turn-1", 1,
                ContextMessage.Role.USER, huge, 50_000));

        CheckpointStore.ContextCheckpoint splitCheckpoint = service.compact(
                request(store, firstHistory, false, Optional.empty(), tinyBudget()))
                .checkpoint().orElseThrow();
        ContextPolicy.RetainedSplit retained = splitCheckpoint.retainedSplit().orElseThrow();
        store.advanceSourceRevision(INITIAL_REVISION + 1);
        List<ContextMessage> secondHistory = List.of(
                firstHistory.getFirst(),
                ContextMessage.text("message-2", "turn-2", 2, ContextMessage.Role.USER, "new", 2));

        ContextCompactionService.CompactionResult recovered = service.compact(
                request(store, secondHistory, false, Optional.empty()));

        assertEquals(1, requests.size());
        assertEquals(retained.retainedMessage(), recovered.prompt().messages().getFirst());
        assertEquals(2, recovered.prompt().messages().size());
        assertEquals(1, store.appended().size());

        store.advanceSourceRevision(INITIAL_REVISION + 2);
        String middle = "middle-".repeat(12_000);
        List<ContextMessage> thirdHistory = List.of(
                firstHistory.getFirst(),
                ContextMessage.text("message-2", "turn-2", 2, ContextMessage.Role.USER, middle, 20_000),
                ContextMessage.text("message-3", "turn-3", 3, ContextMessage.Role.USER, "latest", 2));
        CheckpointStore.ContextCheckpoint advanced = service.compact(
                request(store, thirdHistory, true, Optional.empty())).checkpoint().orElseThrow();

        assertEquals(2, requests.size());
        assertEquals(List.of(1L, 2L), requests.getLast().evictedMessages().stream()
                .map(ContextMessage::ordinal).toList());
        assertEquals(retained.retainedMessage().messageId(),
                requests.getLast().evictedMessages().getFirst().messageId());
        assertTrue(advanced.retainedSplit().isEmpty());
        assertEquals(2, store.appended().size());
    }

    /** 锁定摘要失败既不降级伪造结果也不写检查点，保持失败关闭语义。 */
    @Test
    void summaryFailureDoesNotFallBackOrWriteCheckpoint() {
        MemoryCheckpointStore store = new MemoryCheckpointStore("thread-ctx", INITIAL_REVISION);
        ContextCompactionService service = service(store, request -> {
            throw new IllegalStateException("model unavailable");
        });

        ContextException failure = assertThrows(ContextException.class,
                () -> service.compact(request(store, evictingHistory(), true, Optional.empty())));

        assertEquals(ContextException.Code.SUMMARY_FAILURE, failure.code());
        assertTrue(store.appended().isEmpty());
    }

    /** Summary 返回后观察到取消时必须停在持久边界之前，防止连接关闭产生迟到 Checkpoint。 */
    @Test
    void cancellationAfterSummaryPreventsCheckpointCommit() {
        MemoryCheckpointStore store = new MemoryCheckpointStore("thread-ctx", INITIAL_REVISION);
        CancellationSource cancellation = new CancellationSource();
        ContextCompactionService service = service(store, request -> {
            cancellation.cancel("connection_closed");
            return result(summary("cancelled", "must not persist"), USAGE);
        });
        ContextCompactionService.CompactionRequest request = new ContextCompactionService.CompactionRequest(
                "thread-ctx", store.threadRevision(), evictingHistory(), normalBudget(), true,
                Optional.empty(), new ToolProjectionLimits(64, 64), TEST_METER, cancellation);

        assertThrows(CancellationException.class, () -> service.compact(request));
        assertTrue(store.appended().isEmpty());
        assertEquals(INITIAL_REVISION, store.threadRevision());
    }

    /** 锁定结构化摘要无效时失败关闭，防止损坏文档污染持久历史。 */
    @Test
    void invalidStructuredSummaryFailsClosed() {
        MemoryCheckpointStore nullStore = new MemoryCheckpointStore("thread-ctx", INITIAL_REVISION);
        ContextException nullFailure = assertThrows(ContextException.class,
                () -> service(nullStore, request -> null).compact(
                        request(nullStore, evictingHistory(), true, Optional.empty())));
        MemoryCheckpointStore emptyStore = new MemoryCheckpointStore("thread-ctx", INITIAL_REVISION);
        ContextException emptyFailure = assertThrows(ContextException.class,
                () -> service(emptyStore, request -> result(SummaryDocument.empty(), USAGE)).compact(
                        request(emptyStore, evictingHistory(), true, Optional.empty())));

        assertEquals(ContextException.Code.SUMMARY_FAILURE, nullFailure.code());
        assertEquals(ContextException.Code.SUMMARY_FAILURE, emptyFailure.code());
        assertTrue(nullStore.appended().isEmpty());
        assertTrue(emptyStore.appended().isEmpty());
    }

    /** 锁定上下文溢出只以更小投影重试一次，避免无界 Provider 重放。 */
    @Test
    void overflowRecoveryRetriesExactlyOnceWithSmallerProjection() {
        MemoryCheckpointStore store = new MemoryCheckpointStore("thread-ctx", INITIAL_REVISION);
        ContextCompactionService service = service(store, request -> result(summary("unused", "unused"), USAGE));
        OverflowRecovery recovery = new OverflowRecovery(service);
        AtomicReference<ContextCompactionService.PromptContext> first = new AtomicReference<>();
        AtomicReference<ContextCompactionService.PromptContext> second = new AtomicReference<>();
        int[] attempts = {0};

        String answer = recovery.send(request(store, toolHistory(), false,
                Optional.of(new ModelContinuation("openai_responses", "response-1"))), prompt -> {
            attempts[0]++;
            if (attempts[0] == 1) {
                first.set(prompt);
                throw new ContextException(ContextException.Code.CONTEXT_LIMIT, "provider overflow");
            }
            second.set(prompt);
            return "ok";
        });

        assertEquals("ok", answer);
        assertEquals(2, attempts[0]);
        assertNotNull(first.get());
        assertNotNull(second.get());
        assertTrue(first.get().continuation().isPresent());
        assertTrue(second.get().continuation().isEmpty());
        assertTrue(second.get().localCompaction());
        assertTrue(projectedToolOutput(first.get()).length() > projectedToolOutput(second.get()).length());
        assertEquals(1, store.appended().size());
        assertEquals(CheckpointUsage.none(), store.appended().getFirst().usage());
    }

    /** 锁定已提交压缩后的溢出仅重投影，不再追加第二个检查点。 */
    @Test
    void overflowAfterCommittedCompactionReprojectsWithoutSecondAppend() {
        MemoryCheckpointStore store = new MemoryCheckpointStore("thread-ctx", INITIAL_REVISION);
        OverflowRecovery recovery = new OverflowRecovery(service(store,
                request -> result(summary("tool summary", "compacted"), USAGE)));
        AtomicReference<ContextCompactionService.PromptContext> first = new AtomicReference<>();
        AtomicReference<ContextCompactionService.PromptContext> second = new AtomicReference<>();
        List<ContextMessage> history = new ArrayList<>(evictingHistory());
        history.addAll(toolHistory().stream().map(message -> new ContextMessage(
                message.messageId() + "-later", "turn-3", message.ordinal() + 2,
                message.role(), message.blocks(), message.estimatedTokens())).toList());
        int[] attempts = {0};

        String result = recovery.send(request(store, history, true, Optional.empty()), prompt -> {
            attempts[0]++;
            if (attempts[0] == 1) {
                first.set(prompt);
                throw new ContextException(ContextException.Code.CONTEXT_LIMIT, "provider overflow");
            }
            second.set(prompt);
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(2, attempts[0]);
        assertEquals(1, store.appended().size());
        assertEquals(store.appended().getFirst(), store.read("thread-ctx").checkpoint().orElseThrow());
        assertTrue(projectedToolOutput(first.get()).length() > projectedToolOutput(second.get()).length());
        assertTrue(second.get().continuation().isEmpty());
    }

    /** 锁定溢出恢复绝不发起第三次请求，防止失败循环扩大费用与延迟。 */
    @Test
    void overflowRecoveryNeverAttemptsThirdSend() {
        MemoryCheckpointStore store = new MemoryCheckpointStore("thread-ctx", INITIAL_REVISION);
        OverflowRecovery recovery = new OverflowRecovery(service(store,
                request -> result(summary("unused", "unused"), USAGE)));
        int[] attempts = {0};
        List<ContextMessage> history = new ArrayList<>(evictingHistory());
        history.addAll(toolHistory().stream().map(message -> new ContextMessage(
                message.messageId() + "-later", "turn-3", message.ordinal() + 2,
                message.role(), message.blocks(), message.estimatedTokens())).toList());

        ContextException failure = assertThrows(ContextException.class,
                () -> recovery.send(request(store, history, false, Optional.empty()), prompt -> {
                    attempts[0]++;
                    throw new ContextException(ContextException.Code.CONTEXT_LIMIT, "provider overflow");
                }));

        assertEquals(ContextException.Code.CONTEXT_LIMIT, failure.code());
        assertEquals(2, attempts[0]);
        assertEquals(1, store.appended().size());
    }

    /** 组合真实策略与可替换存储、生成器，使压缩用例只伪造外部副作用。 */
    private static ContextCompactionService service(CheckpointStore store, SummaryGenerator generator) {
        AtomicLong sequence = new AtomicLong();
        return new ContextCompactionService(store, new ContextPolicy(), generator,
                Clock.fixed(NOW, ZoneOffset.UTC), () -> "checkpoint_test_" + sequence.incrementAndGet());
    }

    /** 从内存存储当前 revision 构造基础压缩请求，避免手写 CAS token 漂移。 */
    private static ContextCompactionService.CompactionRequest request(MemoryCheckpointStore store,
                                                                       List<ContextMessage> source,
                                                                       boolean force,
                                                                       Optional<ModelContinuation> continuation) {
        return request(store.threadRevision(), source, force, continuation, normalBudget());
    }

    /** 构造携带指定历史的压缩请求，用于隔离上下文裁剪与摘要行为。 */
    private static ContextCompactionService.CompactionRequest request(MemoryCheckpointStore store,
                                                                       List<ContextMessage> source,
                                                                       boolean force,
                                                                       Optional<ModelContinuation> continuation,
                                                                       ContextBudget budget) {
        return request(store.threadRevision(), source, force, continuation, budget);
    }

    /** 构造显式来源 revision 与历史的请求，用于验证过期写入拒绝。 */
    private static ContextCompactionService.CompactionRequest request(long sourceRevision,
                                                                       List<ContextMessage> source,
                                                                       boolean force,
                                                                       Optional<ModelContinuation> continuation) {
        return request(sourceRevision, source, force, continuation, normalBudget());
    }

    /** 构造带既有摘要的增量请求，锁定恢复后事实累积边界。 */
    private static ContextCompactionService.CompactionRequest request(long sourceRevision,
                                                                       List<ContextMessage> source,
                                                                       boolean force,
                                                                       Optional<ModelContinuation> continuation,
                                                                       ContextBudget budget) {
        return new ContextCompactionService.CompactionRequest("thread-ctx", sourceRevision, source, budget, force,
                continuation, new ToolProjectionLimits(64, 64), TEST_METER);
    }

    /** 从回执存储快照构造请求，用于验证 durable receipt 的发布顺序。 */
    private static ContextCompactionService.CompactionRequest receiptRequest(ReceiptCheckpointStore store) {
        return request(store.threadRevision(), evictingHistory(), true, Optional.empty());
    }

    /** 提供可容纳常规历史的预算基准，避免非溢出用例误入恢复分支。 */
    private static ContextBudget normalBudget() {
        return ContextBudget.capabilities(36_000, 2_000, true);
    }

    /** 提供必然触发压缩的小预算，用于确定性覆盖溢出恢复路径。 */
    private static ContextBudget tinyBudget() {
        return ContextBudget.capabilities(10_000, 500, true);
    }

    /** 构造需要淘汰旧消息的历史，锁定摘要输入与最近尾部的分界。 */
    private static List<ContextMessage> evictingHistory() {
        return List.of(
                ContextMessage.text("msg-1", "turn-1", 1, ContextMessage.Role.USER,
                        "old context", 20_000),
                ContextMessage.text("msg-2", "turn-2", 2, ContextMessage.Role.USER,
                        "latest", 2));
    }

    /** 构造完整 Tool 调用结果历史，确保压缩夹具保持消息配对。 */
    private static List<ContextMessage> toolHistory() {
        String output = "0123456789".repeat(200);
        return List.of(
                new ContextMessage("msg-call", "turn-1", 1, ContextMessage.Role.ASSISTANT,
                        List.of(new ContextMessage.ToolCallBlock("call-1", "read", "{}")), 1),
                new ContextMessage("msg-result", "turn-1", 2, ContextMessage.Role.TOOL,
                        List.of(new ContextMessage.ToolResultBlock("call-1", "read",
                                ContextMessage.ToolOutput.full(output, "artifact://tool/1", 0, null))), 500));
    }

    /** 从文本消息夹具提取正文，集中断言所需的类型校验。 */
    private static String text(ContextMessage message) {
        return ((ContextMessage.TextBlock) message.blocks().getFirst()).value();
    }

    /** 构造字段完整的摘要文档，仅让目标与进度成为用例变量。 */
    private static SummaryDocument summary(String goal, String progress) {
        return new SummaryDocument(List.of(new SummaryDocument.Fact(goal, 1)),
                List.of(new SummaryDocument.Fact("keep history", 1)), List.of(),
                List.of(new SummaryDocument.Fact(progress, 1)), List.of(), List.of(), List.of(),
                List.of(new SummaryDocument.Fact("critical", 1)), List.of(), List.of(), List.of());
    }

    /** 将摘要文档与 usage 组合为生成结果，保持 Provider 回执形状真实。 */
    private static SummaryGenerator.SummaryResult result(SummaryDocument document, CheckpointUsage usage) {
        return new SummaryGenerator.SummaryResult(document, usage);
    }

    /** 从投影上下文提取 Tool 输出，用于证明分片证据未在压缩中丢失。 */
    private static String projectedToolOutput(ContextCompactionService.PromptContext prompt) {
        return prompt.messages().stream()
                .flatMap(message -> message.blocks().stream())
                .filter(ContextMessage.ToolResultBlock.class::isInstance)
                .map(ContextMessage.ToolResultBlock.class::cast)
                .map(result -> result.output().content())
                .findFirst()
                .orElseThrow();
    }

    /** 模拟 revision CAS 与检查点追加的内存存储，用于隔离数据库之外的压缩规则。 */
    private static final class MemoryCheckpointStore implements CheckpointStore {
        private final String threadId;
        private final AtomicLong threadRevision;
        private final List<ContextCheckpoint> appended = new ArrayList<>();

        /** 固定 Thread 与初始 revision，使每个压缩用例拥有独立 CAS 时间线。 */
        private MemoryCheckpointStore(String threadId, long initialRevision) {
            this.threadId = threadId;
            this.threadRevision = new AtomicLong(initialRevision);
        }

        /** 仅允许读取夹具所属 Thread，避免测试掩盖跨 Thread 访问错误。 */
        @Override
        public synchronized Snapshot read(String requestedThreadId) {
            assertEquals(threadId, requestedThreadId);
            Optional<ContextCheckpoint> latest = appended.isEmpty()
                    ? Optional.empty() : Optional.of(appended.getLast());
            return new Snapshot(threadId, threadRevision.get(), latest);
        }

        /** 以同步 CAS 追加检查点，复现生产存储的原子提交边界。 */
        @Override
        public synchronized CommittedCheckpoint commit(CommitRequest request) {
            assertEquals(threadId, request.threadId());
            Optional<ContextCheckpoint> existing = appended.stream()
                    .filter(value -> value.sourceRevision() == request.expectedThreadRevision())
                    .findFirst();
            if (existing.isPresent()) {
                return CommittedCheckpoint.reused(existing.orElseThrow(), threadRevision.get());
            }
            if (threadRevision.get() != request.expectedThreadRevision()) {
                throw new CommitConflict("stale checkpoint source");
            }
            appended.add(request.checkpoint());
            long committedRevision = threadRevision.incrementAndGet();
            return CommittedCheckpoint.created(request.checkpoint(), committedRevision);
        }

        /** 推进来源 revision 以模拟并发写入，专门触发过期 CAS 分支。 */
        private void advanceSourceRevision(long revision) {
            threadRevision.set(revision);
        }

        /** 回读当前 Thread revision，供请求夹具携带真实 CAS token。 */
        private long threadRevision() {
            return threadRevision.get();
        }

        /** 返回不可变的追加快照，避免断言修改内存存储状态。 */
        private synchronized List<ContextCheckpoint> appended() {
            return List.copyOf(appended);
        }
    }

    /** 记录提交回执与调用次数的检查点假实现，用于验证失败后的 durable 顺序。 */
    private static final class ReceiptCheckpointStore implements CheckpointStore {
        private final String threadId;
        private long threadRevision;
        private final List<ContextCheckpoint> checkpoints = new ArrayList<>();
        private int commitCount;

        /** 固定 Thread 与起始 revision，使回执顺序断言不受共享状态影响。 */
        private ReceiptCheckpointStore(String threadId, long threadRevision) {
            this.threadId = threadId;
            this.threadRevision = threadRevision;
        }

        /** 返回当前检查点快照，保持恢复请求与已提交回执使用同一 revision。 */
        @Override
        public synchronized Snapshot read(String requestedThreadId) {
            assertEquals(threadId, requestedThreadId);
            Optional<ContextCheckpoint> latest = checkpoints.isEmpty()
                    ? Optional.empty() : Optional.of(checkpoints.getLast());
            return new Snapshot(threadId, threadRevision, latest);
        }

        /** 记录唯一提交及回执，用于证明 Provider 失败不会回滚持久事实。 */
        @Override
        public synchronized CommittedCheckpoint commit(CommitRequest request) {
            assertEquals(threadId, request.threadId());
            Optional<ContextCheckpoint> existing = checkpoints.stream()
                    .filter(value -> value.sourceRevision() == request.expectedThreadRevision())
                    .findFirst();
            if (existing.isPresent()) {
                return CommittedCheckpoint.reused(existing.orElseThrow(), threadRevision);
            }
            if (threadRevision != request.expectedThreadRevision()) {
                throw new CommitConflict("stale checkpoint source");
            }
            checkpoints.add(request.checkpoint());
            threadRevision++;
            commitCount++;
            return CommittedCheckpoint.created(request.checkpoint(), threadRevision);
        }

        /** 回读提交后的 revision，用于断言 durable 状态已前进。 */
        private synchronized long threadRevision() {
            return threadRevision;
        }

        /** 回读提交次数，证明恢复与重投影不会重复追加检查点。 */
        private synchronized int commitCount() {
            return commitCount;
        }

        /** 回读最近检查点，验证 Provider 失败或取消后已提交事实仍可恢复。 */
        private synchronized Optional<ContextCheckpoint> latest() {
            return checkpoints.isEmpty() ? Optional.empty() : Optional.of(checkpoints.getLast());
        }
    }
}
