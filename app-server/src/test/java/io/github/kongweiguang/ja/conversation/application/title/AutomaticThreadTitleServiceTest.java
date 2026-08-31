// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.title;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.ThreadTitlePolicy;
import io.github.kongweiguang.ja.conversation.domain.TurnRuntimeSnapshot;
import io.github.kongweiguang.ja.conversation.domain.TurnSummary;
import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.port.in.ThreadMetadataEvent;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.port.out.AutomaticTitleUsagePort;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.foundation.concurrent.BoundedVirtualExecutor;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 验证自动标题的冻结 Provider、持久幂等、人工优先、失败保留与清理边界。 */
final class AutomaticThreadTitleServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ModelUsage USAGE = new ModelUsage(80, 5, 85);

    /** 成功调用必须使用冻结模型、空 Tool 与低预算，并发布可确定刷新的元数据事件。 */
    @Test
    void successfulTitleUsesFrozenModelAndPublishesMetadata() throws Exception {
        FakeThreads threads = new FakeThreads();
        threads.preferences = preferences("provider_current", "model_current", ThreadPreferences.TitleSource.PLACEHOLDER);
        FakeUsage usage = new FakeUsage(false);
        AtomicReference<ModelPort.ModelRequest> observed = new AtomicReference<>();
        ModelPort model = (request, sink, cancellation) -> {
            observed.set(request);
            sink.onEvent(new ModelPort.TextDelta("\"标题：生产级 UI 重构\"")).toCompletableFuture().join();
            sink.onEvent(new ModelPort.UsageEvent(USAGE)).toCompletableFuture().join();
            return CompletableFuture.completedFuture(
                    new ModelPort.ModelOutcome(ModelPort.FinishReason.STOP, null, USAGE));
        };
        List<ThreadMetadataEvent> events = new ArrayList<>();
        try (AutomaticThreadTitleService service = service(model, threads, usage, Duration.ofSeconds(1))) {
            service.schedule(request(), event -> {
                events.add(event);
                return CompletableFuture.completedFuture(null);
            }).toCompletableFuture().get(2, TimeUnit.SECONDS);
        }

        ModelPort.ModelRequest sent = observed.get();
        assertNotNull(sent);
        assertEquals("provider_frozen", sent.configuration().providerId());
        assertEquals("model_frozen", sent.configuration().modelId());
        assertEquals("upstream-frozen", sent.configuration().model());
        assertEquals(64, sent.configuration().generation().maxOutputTokens());
        assertNull(sent.configuration().generation().temperature());
        assertNull(sent.configuration().generation().topP());
        assertNull(sent.configuration().generation().reasoningLevel());
        assertTrue(sent.tools().isEmpty());
        assertEquals(ModelPort.RetryPolicy.SINGLE_ATTEMPT, sent.retryPolicy());
        assertEquals("生产级 UI 重构", threads.summary.title());
        assertEquals(ThreadPreferences.TitleSource.AUTO, threads.preferences.titleSource());
        assertEquals(1, events.size());
        assertEquals(USAGE, usage.outcomes.getFirst().usage());
    }

    /** Provider 失败只记录失败事实，必须保留 admission 已提交的 PLACEHOLDER 短标题。 */
    @Test
    void providerFailureUsesLocalFallback() throws Exception {
        FakeThreads threads = new FakeThreads();
        FakeUsage usage = new FakeUsage(false);
        ModelPort failed = (request, sink, cancellation) ->
                CompletableFuture.failedFuture(new IllegalStateException("provider unavailable"));
        try (AutomaticThreadTitleService service = service(failed, threads, usage, Duration.ofSeconds(1))) {
            service.schedule(request("  修复\n附件\u0000 导入  "), ignored -> completed())
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
        }

        assertEquals("首问短标题", threads.summary.title());
        assertEquals(ThreadPreferences.TitleSource.PLACEHOLDER, threads.preferences.titleSource());
        assertEquals(AutomaticTitleUsagePort.Result.FAILED, usage.outcomes.getFirst().result());
        assertEquals("PROVIDER_FAILED", usage.outcomes.getFirst().failureCode());
        assertNull(usage.outcomes.getFirst().usage());
    }

    /** attachment-only 的短标题已由 admission 持久化，Provider 失败不得用助手回复覆盖它。 */
    @Test
    void attachmentOnlyProviderFailureUsesAssistantReplyFallback() throws Exception {
        FakeThreads threads = new FakeThreads();
        FakeUsage usage = new FakeUsage(false);
        ModelPort failed = (request, sink, cancellation) ->
                CompletableFuture.failedFuture(new IllegalStateException("provider unavailable"));
        try (AutomaticThreadTitleService service = service(failed, threads, usage, Duration.ofSeconds(1))) {
            service.schedule(request("", "  已完成\n附件图像分析  "), ignored -> completed())
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
        }

        assertEquals("首问短标题", threads.summary.title());
        assertEquals(ThreadPreferences.TitleSource.PLACEHOLDER, threads.preferences.titleSource());
        assertEquals(AutomaticTitleUsagePort.Result.FAILED, usage.outcomes.getFirst().result());
        assertEquals("PROVIDER_FAILED", usage.outcomes.getFirst().failureCode());
    }

    /** 达到输出上限表示标题可能被截断，必须记录失败并保留 admission 已提交的短标题。 */
    @Test
    void maxOutputFinishDoesNotCommitPartialTitle() throws Exception {
        FakeThreads threads = new FakeThreads();
        FakeUsage usage = new FakeUsage(false);
        ModelPort truncated = (request, sink, cancellation) -> {
            sink.onEvent(new ModelPort.TextDelta("可能被截断的标题")).toCompletableFuture().join();
            sink.onEvent(new ModelPort.UsageEvent(USAGE)).toCompletableFuture().join();
            return CompletableFuture.completedFuture(new ModelPort.ModelOutcome(
                    ModelPort.FinishReason.MAX_OUTPUT_TOKENS, null, USAGE));
        };
        try (AutomaticThreadTitleService service = service(
                truncated, threads, usage, Duration.ofSeconds(1))) {
            service.schedule(request("验证截断结果"), ignored -> completed())
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
        }

        assertEquals("首问短标题", threads.summary.title());
        assertEquals(ThreadPreferences.TitleSource.PLACEHOLDER, threads.preferences.titleSource());
        assertEquals(AutomaticTitleUsagePort.Result.FAILED, usage.outcomes.getFirst().result());
        assertEquals("MODEL_OUTPUT_INCOMPLETE", usage.outcomes.getFirst().failureCode());
        assertEquals(USAGE, usage.outcomes.getFirst().usage());
    }

    /** 自然结束但正文清理后为空仍是非法结果，不能把 PLACEHOLDER 所有权升级为 AUTO。 */
    @Test
    void emptyModelOutputKeepsProvisionalTitle() throws Exception {
        FakeThreads threads = new FakeThreads();
        FakeUsage usage = new FakeUsage(false);
        ModelPort empty = (request, sink, cancellation) -> {
            sink.onEvent(new ModelPort.UsageEvent(USAGE)).toCompletableFuture().join();
            return CompletableFuture.completedFuture(
                    new ModelPort.ModelOutcome(ModelPort.FinishReason.STOP, null, USAGE));
        };
        try (AutomaticThreadTitleService service = service(
                empty, threads, usage, Duration.ofSeconds(1))) {
            service.schedule(request("验证空标题"), ignored -> completed())
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
        }

        assertEquals("首问短标题", threads.summary.title());
        assertEquals(ThreadPreferences.TitleSource.PLACEHOLDER, threads.preferences.titleSource());
        assertEquals("EMPTY_TITLE", usage.outcomes.getFirst().failureCode());
    }

    /** 人工改名发生在模型调用期间时，迟到自动结果必须在 revision/titleSource 双门处安静退出。 */
    @Test
    void manualTitleAlwaysWinsLateModelResult() throws Exception {
        FakeThreads threads = new FakeThreads();
        FakeUsage usage = new FakeUsage(false);
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        ModelPort delayed = (request, sink, cancellation) -> {
            started.countDown();
            return release.thenCompose(ignored -> {
                sink.onEvent(new ModelPort.TextDelta("迟到的自动标题")).toCompletableFuture().join();
                return CompletableFuture.completedFuture(
                        new ModelPort.ModelOutcome(ModelPort.FinishReason.STOP, null, USAGE));
            });
        };
        List<ThreadMetadataEvent> events = new ArrayList<>();
        try (AutomaticThreadTitleService service = service(delayed, threads, usage, Duration.ofSeconds(1))) {
            CompletionStage<Void> completion = service.schedule(request(), event -> {
                events.add(event);
                return completed();
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            threads.renameThread("thr_test", "人工标题", threads.summary.revision());
            release.complete(null);
            completion.toCompletableFuture().get(2, TimeUnit.SECONDS);
        }

        assertEquals("人工标题", threads.summary.title());
        assertEquals(ThreadPreferences.TitleSource.MANUAL, threads.preferences.titleSource());
        assertTrue(events.isEmpty());
    }

    /** 低优先级 worker 启动前即使第二轮已准入，首轮身份仍匹配时也必须完成一次自动标题。 */
    @Test
    void laterTurnAdmissionBeforeWorkerDoesNotStarveFirstAutomaticTitle() throws Exception {
        FakeThreads threads = new FakeThreads();
        threads.advanceRevisionForLaterTurn();
        FakeUsage usage = new FakeUsage(false);
        AtomicInteger calls = new AtomicInteger();
        try (AutomaticThreadTitleService service = service(
                successfulModel(calls, "排队后的首轮标题"), threads, usage, Duration.ofSeconds(1))) {
            service.schedule(request(), ignored -> completed())
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
        }

        assertEquals(1, calls.get());
        assertEquals("排队后的首轮标题", threads.summary.title());
        assertEquals(ThreadPreferences.TitleSource.AUTO, threads.preferences.titleSource());
        assertEquals(7, threads.summary.revision());
    }

    /** 模型调用期间后续 Turn 继续推进 revision、不取得标题所有权时，首次标题仍须一次落地。 */
    @Test
    void laterTurnRevisionDoesNotStarveFirstAutomaticTitle() throws Exception {
        FakeThreads threads = new FakeThreads();
        FakeUsage usage = new FakeUsage(false);
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        ModelPort delayed = (request, sink, cancellation) -> {
            started.countDown();
            return release.thenCompose(ignored -> {
                sink.onEvent(new ModelPort.TextDelta("首次回复标题")).toCompletableFuture().join();
                return CompletableFuture.completedFuture(
                        new ModelPort.ModelOutcome(ModelPort.FinishReason.STOP, null, USAGE));
            });
        };
        try (AutomaticThreadTitleService service = service(delayed, threads, usage, Duration.ofSeconds(1))) {
            CompletionStage<Void> completion = service.schedule(request(), ignored -> completed());
            assertTrue(started.await(1, TimeUnit.SECONDS));
            threads.advanceRevisionForLaterTurn();
            threads.advanceRevisionForLaterTurn();
            release.complete(null);
            completion.toCompletableFuture().get(2, TimeUnit.SECONDS);
        }

        assertEquals("首次回复标题", threads.summary.title());
        assertEquals(ThreadPreferences.TitleSource.AUTO, threads.preferences.titleSource());
        assertEquals(8, threads.summary.revision());
    }

    /** titleSource 与 durable claim 共同保证同进程重复完成和服务重启都不会产生第二次模型调用。 */
    @Test
    void repeatedCompletionAndRestartAreIdempotent() throws Exception {
        FakeThreads threads = new FakeThreads();
        FakeUsage usage = new FakeUsage(false);
        AtomicInteger calls = new AtomicInteger();
        ModelPort model = successfulModel(calls, "一次标题");
        try (AutomaticThreadTitleService first = service(model, threads, usage, Duration.ofSeconds(1))) {
            first.schedule(request(), ignored -> completed()).toCompletableFuture().get(2, TimeUnit.SECONDS);
            first.schedule(request(), ignored -> completed()).toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
        try (AutomaticThreadTitleService restarted = service(model, threads, usage, Duration.ofSeconds(1))) {
            restarted.schedule(request(), ignored -> completed()).toCompletableFuture().get(2, TimeUnit.SECONDS);
        }

        assertEquals(1, calls.get());
        assertEquals(1, usage.claims.get());
        assertEquals("一次标题", threads.summary.title());
    }

    /** 重启发现未完成持久 claim 时保留短标题，不重放可能已经计费的外部请求。 */
    @Test
    void incompleteDurableClaimRecoversWithoutProviderReplay() throws Exception {
        FakeThreads threads = new FakeThreads();
        FakeUsage usage = new FakeUsage(true);
        AtomicInteger calls = new AtomicInteger();
        try (AutomaticThreadTitleService service = service(
                successfulModel(calls, "不应调用"), threads, usage, Duration.ofSeconds(1))) {
            service.schedule(request("恢复未完成标题"), ignored -> completed())
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
        }

        assertEquals(0, calls.get());
        assertEquals("首问短标题", threads.summary.title());
        assertTrue(usage.outcomes.isEmpty());
    }

    /** 超时记录明确失败事实并保留短标题，关闭不依赖 Provider Future 主动完成。 */
    @Test
    void timeoutFallsBackAndDoesNotLeaveBackgroundTask() throws Exception {
        FakeThreads threads = new FakeThreads();
        FakeUsage usage = new FakeUsage(false);
        ModelPort never = (request, sink, cancellation) -> new CompletableFuture<>();
        try (AutomaticThreadTitleService service = service(never, threads, usage, Duration.ofMillis(20))) {
            service.schedule(request("模型超时回退"), ignored -> completed())
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
        }

        assertEquals("首问短标题", threads.summary.title());
        assertEquals("PROVIDER_TIMEOUT", usage.outcomes.getFirst().failureCode());
    }

    /** 超长正文、换行、双向格式字符和 Markdown 包装都不会污染最终短标题。 */
    @Test
    void titleCleaningRemovesControlsAndTruncatesByCodePoint() {
        String source = "# \"标题：" + "界面".repeat(40) + "\u202E\n说明\"";
        String cleaned = ThreadTitlePolicy.modelTitle(source);
        assertFalse(cleaned.contains("\u202E"));
        assertFalse(cleaned.contains("\n"));
        assertTrue(cleaned.codePointCount(0, cleaned.length()) <= 48);
        assertTrue(cleaned.endsWith("…"));
        assertEquals("", ThreadTitlePolicy.modelTitle("\u0000\u202E"));
    }

    /** 创建使用独立双门执行器的生产同路径服务，测试结束会验证真实关闭。 */
    private static AutomaticThreadTitleService service(ModelPort model, FakeThreads threads,
                                                       FakeUsage usage, Duration timeout) {
        return new AutomaticThreadTitleService(model, threads, usage, CLOCK, timeout,
                new BoundedVirtualExecutor("title-test-", 2, 8));
    }

    /** 构造成功 Provider，使幂等测试只累计真实 start 次数。 */
    private static ModelPort successfulModel(AtomicInteger calls, String title) {
        return (request, sink, cancellation) -> {
            calls.incrementAndGet();
            sink.onEvent(new ModelPort.TextDelta(title)).toCompletableFuture().join();
            return CompletableFuture.completedFuture(
                    new ModelPort.ModelOutcome(ModelPort.FinishReason.STOP, null, USAGE));
        };
    }

    /** 默认请求绑定冻结 Provider/Model，Thread 当前偏好可在测试中独立变化。 */
    private static AutomaticThreadTitleScheduler.Request request() {
        return request("设计 Ja Agent 产品级界面");
    }

    /** 构造包含指定首问与成功回复的冻结标题请求。 */
    private static AutomaticThreadTitleScheduler.Request request(String userText) {
        return request(userText, "已经完成实现");
    }

    /** 首问允许为空以覆盖 attachment-only Turn，成功回复仍须保持真实内容。 */
    private static AutomaticThreadTitleScheduler.Request request(String userText, String assistantReply) {
        TurnRuntimeSnapshot runtime = runtime();
        return new AutomaticThreadTitleScheduler.Request(
                "thr_test", "turn_first", 5, userText, assistantReply, runtime, configuration());
    }

    /** 创建不含端点与凭据的持久运行快照。 */
    private static TurnRuntimeSnapshot runtime() {
        return new TurnRuntimeSnapshot("provider_frozen", "model_frozen", "openai", "openai_responses",
                "upstream-frozen", "high", AccessMode.APPROVAL_REQUIRED, "cfg_frozen");
    }

    /** 创建仍携带测试凭据的进程内冻结配置，断言服务不会改写身份或上游模型。 */
    private static ModelPort.ModelConfiguration configuration() {
        return new ModelPort.ModelConfiguration(
                "provider_frozen", "model_frozen", "cfg_frozen", ModelPort.Provider.OPENAI,
                ModelPort.Api.OPENAI_RESPONSES, "upstream-frozen", URI.create("http://127.0.0.1:60842"),
                "test-secret", Duration.ofSeconds(2), Duration.ofSeconds(2),
                java.util.Set.of(ModelPort.InputModality.TEXT),
                new ModelPort.GenerationOptions(0.8, 0.9, 2_000, "high"));
    }

    /** 创建完整 Thread 偏好，标题来源可由竞争场景单独指定。 */
    private static ThreadPreferences preferences(String providerId, String modelId,
                                                 ThreadPreferences.TitleSource source) {
        return new ThreadPreferences(providerId, modelId, "medium", AccessMode.APPROVAL_REQUIRED, source);
    }

    /** 为不施加背压的测试事件提供已完成阶段。 */
    private static CompletionStage<Void> completed() {
        return CompletableFuture.completedFuture(null);
    }

    /** 内存实现只模拟 Thread 标题 revision CAS 与人工优先级，不复制数据库查询逻辑。 */
    private static final class FakeThreads implements ThreadUseCase {
        private ThreadPreferences preferences = preferences(
                "provider_current", "model_current", ThreadPreferences.TitleSource.PLACEHOLDER);
        private ThreadSummary summary = summary("首问短标题", preferences, 5);
        private boolean laterTurnPresent;

        /** 测试不创建额外 Thread。 */
        @Override public ThreadSummary createThread(ThreadSummary.Creation request) { throw unsupported(); }
        /** 测试不分页列出 Thread。 */
        @Override public CursorPage<ThreadSummary> listThreads(String workspaceId, String cursor, int limit) {
            throw unsupported();
        }
        /** 测试不执行标题搜索。 */
        @Override public CursorPage<ThreadSummary> searchThreads(
                String workspaceId, String query, String cursor, int limit) { throw unsupported(); }

        /** 返回当前标题状态、首轮及可选后续轮，模拟不受 item page limit 影响的完整 Turn 快照。 */
        @Override
        public synchronized Optional<ThreadSnapshot> readThread(String threadId, String cursor, int limit) {
            if (!summary.threadId().equals(threadId)) return Optional.empty();
            ThreadSnapshot.Turn first = new ThreadSnapshot.Turn(
                    "turn_first", "completed", runtime(), NOW.minusSeconds(2), NOW, NOW, null, null);
            List<ThreadSnapshot.Turn> turns = new ArrayList<>();
            turns.add(first);
            if (laterTurnPresent) {
                turns.add(new ThreadSnapshot.Turn(
                        "turn_later", "completed", runtime(), NOW.plusSeconds(1), NOW.plusSeconds(2),
                        NOW.plusSeconds(2), null, null));
            }
            return Optional.of(new ThreadSnapshot(summary, turns, List.of(), null, null));
        }

        /** 人工标题推进 revision 并永久切换 manual 所有权。 */
        @Override
        public synchronized ThreadSummary renameThread(String threadId, String title, long expectedRevision) {
            if (summary.revision() != expectedRevision) throw new IllegalStateException("conflict");
            preferences = preferences.withTitleSource(ThreadPreferences.TitleSource.MANUAL);
            summary = summary(title, preferences, expectedRevision + 1);
            return summary;
        }

        /** 测试不修改模型偏好。 */
        @Override public ThreadSummary updatePreferences(
                String threadId, ThreadPreferences value, long expectedThreadRevision) { throw unsupported(); }

        /** 自动标题接受后续 Turn 推进后的 revision，但仍以 placeholder 作为唯一所有权门。 */
        @Override
        public synchronized boolean writeAutomaticTitle(String threadId, String title, long expectedRevision) {
            if (summary.revision() < expectedRevision
                || preferences.titleSource() != ThreadPreferences.TitleSource.PLACEHOLDER) return false;
            preferences = preferences.withTitleSource(ThreadPreferences.TitleSource.AUTO);
            summary = summary(title, preferences, summary.revision() + 1);
            return true;
        }

        /** 模拟第二轮准入及其后续状态推进；完整快照从此包含该轮，但标题归属仍是 placeholder。 */
        private synchronized void advanceRevisionForLaterTurn() {
            laterTurnPresent = true;
            summary = summary(summary.title(), preferences, summary.revision() + 1);
        }

        /** 测试不归档 Thread。 */
        @Override public void archiveThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 测试不删除 Thread。 */
        @Override public void deleteThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 测试不按全局身份查找 Turn。 */
        @Override public Optional<TurnSummary> findTurn(String turnId) { throw unsupported(); }

        /** 创建保持固定 Workspace 和时间的 Thread 投影，只改变标题、偏好与 revision。 */
        private static ThreadSummary summary(String title, ThreadPreferences value, long revision) {
            return new ThreadSummary("thr_test", "ws_test", title, value,
                    ThreadSummary.Status.ACTIVE, revision, NOW.minusSeconds(60), NOW);
        }
    }

    /** 记录 claim 和 outcome，模拟 Thread 唯一约束而不冒充 SQLite 原子实现。 */
    private static final class FakeUsage implements AutomaticTitleUsagePort {
        private final AtomicInteger claims = new AtomicInteger();
        private final List<ModelOutcome> outcomes = new ArrayList<>();
        private boolean exists;

        /** 允许场景预置崩溃前已声明但未完成的持久事实。 */
        private FakeUsage(boolean exists) {
            this.exists = exists;
        }

        /** 首次 claim 取得调用权，后续调用观察 durable existing。 */
        @Override
        public synchronized ClaimResult claim(GenerationClaim claim) {
            claims.incrementAndGet();
            if (exists) return ClaimResult.ALREADY_EXISTS;
            exists = true;
            return ClaimResult.ACQUIRED;
        }

        /** 保存一次模型终局，测试据此区分成功 Usage 与失败空值。 */
        @Override
        public synchronized void recordModelOutcome(ModelOutcome outcome) {
            outcomes.add(outcome);
        }
    }

    /** 所有未声明测试能力都显式失败，避免空操作掩盖调用越界。 */
    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("test capability is not configured");
    }
}
