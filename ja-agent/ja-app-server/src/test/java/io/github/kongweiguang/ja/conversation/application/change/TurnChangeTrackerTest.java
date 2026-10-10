// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.change;

import io.github.kongweiguang.ja.conversation.domain.model.TurnChangeSet;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.foundation.filesystem.Utf8Fingerprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证运行期只累计修改证据、终态一次性冻结 Diff 的产品边界。 */
final class TurnChangeTrackerTest {
    private static final String WORKSPACE_ID = "ws_fixture";

    @TempDir
    Path temporary;

    /** 空收据状态表示有事实支持的无变更；进程重启本身不再被猜成恢复风险。 */
    @Test
    void freezesKnownEmptyStateAndRestoresOnlyPersistedIncompleteReasons() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("states"));
        TurnChangeTracker.Frozen fresh = TurnChangeTracker.fresh(workspace, WORKSPACE_ID).freeze();
        assertEquals(TurnChangeSet.State.COMPLETE, fresh.changeSet().state());
        assertTrue(fresh.changeSet().files().isEmpty());
        assertNull(fresh.unifiedDiff());

        TurnChangeTracker pending = TurnChangeTracker.fresh(workspace, WORKSPACE_ID);
        pending.markIncomplete(TurnChangeSet.IncompleteReason.RECOVERY_BOUNDARY);
        TurnChangeTracker restored = TurnChangeTracker.restore(workspace, WORKSPACE_ID, pending.durableSnapshot());
        TurnChangeTracker.Frozen recovered = restored.freeze();
        assertEquals(TurnChangeSet.State.PARTIAL, recovered.changeSet().state());
        assertTrue(recovered.changeSet().incompleteReasons()
                .contains(TurnChangeSet.IncompleteReason.RECOVERY_BOUNDARY));
    }

    /** 同文件多次修改合并为首次 preimage 到最终 postimage，恢复原样后不保留文件或 artifact。 */
    @Test
    void mergesRepeatedEditsAndDropsNetZero() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("merge"));
        Path target = workspace.resolve("a.txt");
        TurnChangeTracker changed = TurnChangeTracker.fresh(workspace, WORKSPACE_ID);
        applyReceipt(changed, receipt(workspace, target, true, "zero\n", true, "one\n"));
        applyReceipt(changed, receipt(workspace, target, true, "one\n", true, "two\n"));
        TurnChangeTracker.Frozen frozen = changed.freeze();
        assertEquals(1, frozen.changeSet().files().size());
        assertTrue(frozen.unifiedDiff().contains("-zero"));
        assertTrue(frozen.unifiedDiff().contains("+two"));
        assertFalse(frozen.unifiedDiff().contains("one"));

        TurnChangeTracker reverted = TurnChangeTracker.fresh(workspace, WORKSPACE_ID);
        applyReceipt(reverted, receipt(workspace, target, true, "zero\n", true, "one\n"));
        applyReceipt(reverted, receipt(workspace, target, true, "one\n", true, "zero\n"));
        assertTrue(reverted.freeze().changeSet().files().isEmpty());
    }

    /** 两次结果提交之间经持久快照恢复时，继续沿首次 preimage 合并而不读取当前文件内容。 */
    @Test
    void restoresBoundedStateAndContinuesTheReceiptChain() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("durable-state"));
        Path target = workspace.resolve("a.txt");
        TurnChangeTracker first = TurnChangeTracker.fresh(workspace, WORKSPACE_ID);
        applyReceipt(first, receipt(workspace, target, false, "", true, "one\n"));

        TurnChangeTracker restored = TurnChangeTracker.restore(workspace, WORKSPACE_ID, first.durableSnapshot());
        applyReceipt(restored, receipt(workspace, target, true, "one\n", true, "two\n"));

        TurnChangeTracker.Frozen frozen = restored.freeze();
        assertEquals(1, frozen.changeSet().files().size());
        assertTrue(frozen.unifiedDiff().contains("+two"));
        assertFalse(frozen.unifiedDiff().contains("one"));
        assertThrows(IllegalArgumentException.class,
                () -> TurnChangeTracker.restore(workspace, "ws_other", first.durableSnapshot()));
    }

    /** 链断裂不吸收无法证明的后像，但保留此前确认修改并清楚标记 partial。 */
    @Test
    void keepsConfirmedChangesWhenMutationChainBreaks() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("chain"));
        Path target = workspace.resolve("a.txt");
        TurnChangeTracker tracker = TurnChangeTracker.fresh(workspace, WORKSPACE_ID);
        applyReceipt(tracker, receipt(workspace, target, false, "", true, "confirmed\n"));
        applyReceipt(tracker, receipt(workspace, target, true, "other\n", true, "unconfirmed\n"));
        TurnChangeTracker.Frozen frozen = tracker.freeze();
        assertEquals(TurnChangeSet.State.PARTIAL, frozen.changeSet().state());
        assertTrue(frozen.changeSet().incompleteReasons()
                .contains(TurnChangeSet.IncompleteReason.MUTATION_CHAIN_BROKEN));
        assertTrue(frozen.unifiedDiff().contains("confirmed"));
        assertFalse(frozen.unifiedDiff().contains("unconfirmed"));
    }

    /** Shell/MCP 未知写入与提交未确认只追加原因，不伪造文件记录或正文。 */
    @Test
    void marksUnknownAndUnconfirmedWritesWithoutInventingFiles() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("unknown"));
        TurnChangeTracker tracker = TurnChangeTracker.fresh(workspace, WORKSPACE_ID);
        tracker.markIncomplete(TurnChangeSet.IncompleteReason.UNKNOWN_MUTATOR);
        tracker.markCommitUnconfirmed();
        TurnChangeTracker.Frozen frozen = tracker.freeze();
        assertTrue(frozen.changeSet().files().isEmpty());
        assertNull(frozen.unifiedDiff());
        assertTrue(frozen.changeSet().incompleteReasons()
                .contains(TurnChangeSet.IncompleteReason.UNKNOWN_MUTATOR));
        assertTrue(frozen.changeSet().incompleteReasons()
                .contains(TurnChangeSet.IncompleteReason.COMMIT_UNCONFIRMED));
    }

    /** 收据声明工作区外路径时拒绝它；无 confinement 证明时还必须执行可失败的物理路径核验。 */
    @Test
    void rejectsOutsideAndUnverifiablePaths() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("paths"));
        TurnChangeTracker outside = TurnChangeTracker.fresh(workspace, WORKSPACE_ID);
        applyReceipt(outside, receipt(workspace, temporary.resolve("outside.txt"), false, "", true, "secret"));
        TurnChangeTracker.Frozen outsideFrozen = outside.freeze();
        assertTrue(outsideFrozen.changeSet().files().isEmpty());
        assertTrue(outsideFrozen.changeSet().incompleteReasons()
                .contains(TurnChangeSet.IncompleteReason.CAPTURE_FAILED));

        Path existing = Files.writeString(workspace.resolve("existing.txt"), "old");
        TurnChangeTracker unreadable = new TurnChangeTracker(workspace, WORKSPACE_ID, ignored -> {
            throw new IOException("fixture");
        });
        applyReceipt(unreadable, receipt(workspace, existing, true, "old", true, "secret", false));
        TurnChangeTracker.Frozen unreadableFrozen = unreadable.freeze();
        assertTrue(unreadableFrozen.changeSet().files().isEmpty());
        assertTrue(unreadableFrozen.changeSet().incompleteReasons()
                .contains(TurnChangeSet.IncompleteReason.CAPTURE_FAILED));
    }

    /** 新增、删除、CRLF 和末尾换行变化必须保留为可重放的标准 unified diff。 */
    @Test
    void preservesLifecycleAndExactLineEndings() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("line-endings"));
        TurnChangeTracker tracker = TurnChangeTracker.fresh(workspace, WORKSPACE_ID);
        applyReceipt(tracker, receipt(workspace, workspace.resolve("added.txt"), false, "", true, "added"));
        applyReceipt(tracker, receipt(workspace, workspace.resolve("deleted.txt"), true, "deleted", false, ""));
        applyReceipt(tracker, receipt(workspace, workspace.resolve("crlf.txt"), true, "old\r\n", true, "new\r\n"));
        applyReceipt(tracker, receipt(workspace, workspace.resolve("newline.txt"), true, "same", true, "same\n"));
        String diff = tracker.freeze().unifiedDiff();
        assertNotNull(diff);
        assertTrue(diff.contains("--- /dev/null\n+++ b/added.txt\n"));
        assertTrue(diff.contains("--- a/deleted.txt\n+++ /dev/null\n"));
        assertTrue(diff.contains("-old\r\n+new\r\n"));
        assertTrue(diff.contains("\\ No newline at end of file\n"));
    }

    /** 超过文件预算时保留已确认集合并标记 partial，不扩张到整仓扫描或无界正文。 */
    @Test
    void boundsTrackedFiles() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("budget"));
        TurnChangeTracker tracker = TurnChangeTracker.fresh(workspace, WORKSPACE_ID);
        for (int index = 0; index <= TurnChangeTracker.MAX_FILES; index++) {
            applyReceipt(tracker, receipt(workspace, workspace.resolve("f-" + index + ".txt"), false, "", true, "x"));
        }
        TurnChangeTracker.Frozen frozen = tracker.freeze();
        assertEquals(TurnChangeTracker.MAX_FILES, frozen.changeSet().files().size());
        assertTrue(frozen.changeSet().incompleteReasons()
                .contains(TurnChangeSet.IncompleteReason.LIMIT_EXCEEDED));
    }

    /** 单文件 Diff 正文超过协议上限时不得丢弃 artifact 后仍报告 complete。 */
    @Test
    void marksSingleFileDiffOverLimitAsPartialWithoutArtifact() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("diff-budget"));
        TurnChangeTracker tracker = TurnChangeTracker.fresh(workspace, WORKSPACE_ID);
        String oversized = "x".repeat(TurnChangeTracker.MAX_DIFF_BYTES + 1);
        applyReceipt(tracker, receipt(workspace, workspace.resolve("large.txt"), false, "", true, oversized));

        TurnChangeTracker.Frozen frozen = tracker.freeze();

        assertEquals(TurnChangeSet.State.PARTIAL, frozen.changeSet().state());
        assertTrue(frozen.changeSet().incompleteReasons().contains(TurnChangeSet.IncompleteReason.LIMIT_EXCEEDED));
        assertTrue(frozen.changeSet().stats().truncated());
        assertTrue(frozen.changeSet().files().getFirst().truncated());
        assertNull(frozen.unifiedDiff());
        assertNull(frozen.changeSet().artifactId());
    }

    /** freeze 同步生成 artifact 身份、聚合摘要和 UTF-8 长度，并关闭后续修改入口。 */
    @Test
    void freezesArtifactIntegrityExactlyOnce() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("freeze"));
        TurnChangeTracker tracker = TurnChangeTracker.fresh(workspace, WORKSPACE_ID);
        applyReceipt(tracker, receipt(workspace, workspace.resolve("a.txt"), false, "", true, "内容😀\n"));
        TurnChangeTracker.Frozen frozen = tracker.freeze();
        assertNotNull(frozen.unifiedDiff());
        assertEquals((long) frozen.unifiedDiff().getBytes(StandardCharsets.UTF_8).length,
                frozen.byteLength());
        assertTrue(frozen.sha256().matches("[0-9a-f]{64}"));
        assertTrue(frozen.changeSet().artifactId().startsWith("artifact_"));
        assertThrows(IllegalStateException.class, tracker::freeze);
        assertThrows(IllegalStateException.class,
                () -> tracker.markIncomplete(TurnChangeSet.IncompleteReason.CAPTURE_FAILED));
    }

    /** 内部快照构造失败不得先消耗冻结权；清除损坏恢复态后可继续收据并成功结算。 */
    @Test
    void failedSnapshotDoesNotFreezeTracker() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("snapshot-retry"));
        TurnChangeTracker tracker = TurnChangeTracker.fresh(workspace, WORKSPACE_ID);
        injectInvalidTrackedPath(tracker);

        assertThrows(IllegalArgumentException.class, tracker::freeze);

        clearTrackedFiles(tracker);
        applyReceipt(tracker, receipt(workspace, workspace.resolve("recovered.txt"), false, "", true, "saved"));
        TurnChangeTracker.Frozen recovered = tracker.freeze();
        assertEquals(TurnChangeSet.State.COMPLETE, recovered.changeSet().state());
        assertEquals("recovered.txt", recovered.changeSet().files().getFirst().path());
    }

    /** Myers 计算越过 100ms 后必须中断 worker 并完成既有整文件摘要，不能把超时只当作调用方超时。 */
    @Test
    void interruptsExpensiveDiffAfterDeadline() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("diff-timeout"));
        String before = lines("before", 9_000);
        String after = lines("after", 9_000);
        TurnChangeTracker tracker = TurnChangeTracker.fresh(workspace, WORKSPACE_ID);
        applyReceipt(tracker, receipt(workspace, workspace.resolve("large.txt"), true, before, true, after));
        ThreadPoolExecutor executor = diffExecutor();
        assertTrue(awaitIdle(executor));

        long startedAt = System.nanoTime();
        TurnChangeTracker.Frozen frozen = tracker.freeze();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertTrue(elapsedMillis >= 90, "the fixture must cross the configured diff deadline");
        assertNotNull(frozen.unifiedDiff());
        assertTrue(frozen.unifiedDiff().contains("-before-0\n"));
        assertTrue(frozen.unifiedDiff().contains("+after-0\n"));
        assertTrue(awaitIdle(executor), "the cancelled diff must release its worker and queue slot");
    }

    /** 两个 worker 被占用时，过期的排队 Future 必须被取消并移出队列，不留住大输入。 */
    @Test
    void removesTimedOutQueuedDiffFromExecutorQueue() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("diff-queued-timeout"));
        ThreadPoolExecutor executor = diffExecutor();
        CountDownLatch workersStarted = new CountDownLatch(2);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        try {
            blockDiffWorkers(executor, workersStarted, releaseWorkers);
            assertTrue(workersStarted.await(2, TimeUnit.SECONDS));
            TurnChangeTracker tracker = TurnChangeTracker.fresh(workspace, WORKSPACE_ID);
            applyReceipt(tracker, receipt(workspace, workspace.resolve("queued.txt"), true,
                    "before\n", true, "after\n"));

            TurnChangeTracker.Frozen frozen = tracker.freeze();

            assertNotNull(frozen.unifiedDiff());
            assertTrue(executor.getQueue().isEmpty(), "timed-out queued Future must not occupy the bounded queue");
        } finally {
            releaseWorkers.countDown();
        }
        assertTrue(awaitIdle(executor));
    }

    /** 饱和时保留同一整文件 Diff 结果，且拒绝的新任务不能越过 8 项队列上限。 */
    @Test
    void preservesWholeFileDiffWhenExecutorQueueIsSaturated() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("diff-saturated"));
        ThreadPoolExecutor executor = diffExecutor();
        CountDownLatch workersStarted = new CountDownLatch(2);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        try {
            blockDiffWorkers(executor, workersStarted, releaseWorkers);
            assertTrue(workersStarted.await(2, TimeUnit.SECONDS));
            ArrayBlockingQueue<Runnable> queue = (ArrayBlockingQueue<Runnable>) executor.getQueue();
            while (queue.remainingCapacity() > 0) {
                executor.execute(() -> { });
            }
            assertEquals(8, queue.size());

            TurnChangeTracker tracker = TurnChangeTracker.fresh(workspace, WORKSPACE_ID);
            applyReceipt(tracker, receipt(workspace, workspace.resolve("saturated.txt"), true,
                    "before\n", true, "after\n"));
            TurnChangeTracker.Frozen frozen = tracker.freeze();

            assertTrue(frozen.unifiedDiff().contains("-before\n+after\n"));
            assertEquals(8, queue.size(), "rejected diff work must not exceed the configured queue capacity");
        } finally {
            releaseWorkers.countDown();
        }
        assertTrue(awaitIdle(executor));
    }

    /** 生成受 WorkspaceBoundary 证明的持久收据，测试在线与恢复共用的 JSON 解码路径。 */
    private static io.github.kongweiguang.ja.agent.domain.json.JsonObject receipt(
            Path workspaceRoot, Path path, boolean beforeExists, String before,
            boolean afterExists, String after) {
        return receipt(workspaceRoot, path, beforeExists, before, afterExists, after, true);
    }

    /** 生成 confinement 状态明确的收据，以便单独验证缺少物理授权证明时的路径检查。 */
    private static io.github.kongweiguang.ja.agent.domain.json.JsonObject receipt(
            Path workspaceRoot, Path path, boolean beforeExists, String before,
            boolean afterExists, String after, boolean confined) {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path absolute = path.toAbsolutePath().normalize();
        String relative = root.relativize(absolute).toString().replace('\\', '/');
        Utf8Fingerprint beforeFingerprint = Utf8Fingerprint.of(before);
        Utf8Fingerprint afterFingerprint = Utf8Fingerprint.of(after);
        return JsonObjects.builder().putText("workspaceId", WORKSPACE_ID).putText("relativePath", relative)
                .putBoolean("beforeExists", beforeExists).putBoolean("afterExists", afterExists)
                .putText("beforeText", before).putText("afterText", after)
                .putNumber("beforeBytes", beforeFingerprint.byteLength())
                .putNumber("afterBytes", afterFingerprint.byteLength())
                .putText("beforeSha256", beforeFingerprint.sha256()).putText("afterSha256", afterFingerprint.sha256())
                .putBoolean("confined", confined).build();
    }

    /** 通过共享严格解码入口应用已提交收据，避免测试继续依赖旧的 payload-only API。 */
    private static void applyReceipt(TurnChangeTracker tracker,
            io.github.kongweiguang.ja.agent.domain.json.JsonObject payload) {
        tracker.applyCommittedWorkspaceFact(WorkspaceMutationFactCodec.NAMESPACE,
                WorkspaceMutationFactCodec.RECEIPT_TYPE, WorkspaceMutationFactCodec.SCHEMA_VERSION, payload);
    }

    /** 反射只用于在测试中占用真实私有执行器，不向生产类增加可替换的测试接口。 */
    private static ThreadPoolExecutor diffExecutor() throws ReflectiveOperationException {
        Field field = TurnChangeTracker.class.getDeclaredField("DIFF_EXECUTOR");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(null);
    }

    /** 同步占用两个真实 diff worker，令队列超时和饱和分支可重复触发。 */
    private static void blockDiffWorkers(ThreadPoolExecutor executor, CountDownLatch started,
                                         CountDownLatch release) {
        for (int index = 0; index < 2; index++) {
            executor.execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

    /** 等待静态执行器空闲，避免测试间遗留工作使饱和断言依赖执行顺序。 */
    private static boolean awaitIdle(ThreadPoolExecutor executor) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (executor.getActiveCount() == 0 && executor.getQueue().isEmpty()) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        return executor.getActiveCount() == 0 && executor.getQueue().isEmpty();
    }

    /** 通过私有状态构造损坏的持久路径，验证终态构造失败不会留下半完成冻结。 */
    @SuppressWarnings("unchecked")
    private static void injectInvalidTrackedPath(TurnChangeTracker tracker) throws ReflectiveOperationException {
        Field trackedField = TurnChangeTracker.class.getDeclaredField("tracked");
        trackedField.setAccessible(true);
        Map<String, Object> tracked = (Map<String, Object>) trackedField.get(tracker);
        Class<?> trackedFile = Class.forName(TurnChangeTracker.class.getName() + "$TrackedFile");
        Constructor<?> constructor = trackedFile.getDeclaredConstructor(String.class, boolean.class, String.class,
                String.class, boolean.class, String.class, String.class);
        constructor.setAccessible(true);
        String before = "";
        String after = "saved";
        String path = "bad" + Character.toString(0x7f) + ".txt";
        tracked.put(path, constructor.newInstance(path, false, before, Utf8Fingerprint.of(before).sha256(),
                true, after, Utf8Fingerprint.of(after).sha256()));
    }

    /** 仅清理由测试注入的损坏态，不扩大生产 API 来服务白盒恢复测试。 */
    @SuppressWarnings("unchecked")
    private static void clearTrackedFiles(TurnChangeTracker tracker) throws ReflectiveOperationException {
        Field trackedField = TurnChangeTracker.class.getDeclaredField("tracked");
        trackedField.setAccessible(true);
        ((Map<String, Object>) trackedField.get(tracker)).clear();
    }

    /** 构造两组无公共行的有界输入，确保 Myers 搜索跨过超时后可验证中断。 */
    private static String lines(String prefix, int count) {
        StringBuilder content = new StringBuilder(count * 16);
        for (int index = 0; index < count; index++) {
            content.append(prefix).append('-').append(index).append('\n');
        }
        return content.toString();
    }
}
