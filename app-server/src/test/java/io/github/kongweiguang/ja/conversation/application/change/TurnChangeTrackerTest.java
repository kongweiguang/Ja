// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.change;

import io.github.kongweiguang.ja.conversation.domain.TurnChangeSet;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证运行期只累计修改证据、终态一次性冻结 Diff 的产品边界。 */
final class TurnChangeTrackerTest {
    @TempDir
    Path temporary;

    /** Fresh 与恢复 Turn 只在终态暴露结果；恢复边界必须永久标记 partial。 */
    @Test
    void freezesFreshAndResumedEmptyStates() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("states"));
        TurnChangeTracker.Frozen fresh = TurnChangeTracker.fresh(workspace).freeze();
        TurnChangeTracker.Frozen resumed = TurnChangeTracker.resumed(workspace).freeze();
        assertEquals(TurnChangeSet.State.COMPLETE, fresh.changeSet().state());
        assertTrue(fresh.changeSet().files().isEmpty());
        assertNull(fresh.unifiedDiff());
        assertEquals(TurnChangeSet.State.PARTIAL, resumed.changeSet().state());
        assertTrue(resumed.changeSet().incompleteReasons()
                .contains(TurnChangeSet.IncompleteReason.RECOVERY_BOUNDARY));
    }

    /** 同文件多次修改合并为首次 preimage 到最终 postimage，恢复原样后不保留文件或 artifact。 */
    @Test
    void mergesRepeatedEditsAndDropsNetZero() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("merge"));
        Path target = workspace.resolve("a.txt");
        TurnChangeTracker changed = TurnChangeTracker.fresh(workspace);
        changed.apply(receipt(target, true, "zero\n", true, "one\n"));
        changed.apply(receipt(target, true, "one\n", true, "two\n"));
        TurnChangeTracker.Frozen frozen = changed.freeze();
        assertEquals(1, frozen.changeSet().files().size());
        assertTrue(frozen.unifiedDiff().contains("-zero"));
        assertTrue(frozen.unifiedDiff().contains("+two"));
        assertFalse(frozen.unifiedDiff().contains("one"));

        TurnChangeTracker reverted = TurnChangeTracker.fresh(workspace);
        reverted.apply(receipt(target, true, "zero\n", true, "one\n"));
        reverted.apply(receipt(target, true, "one\n", true, "zero\n"));
        assertTrue(reverted.freeze().changeSet().files().isEmpty());
    }

    /** 链断裂不吸收无法证明的后像，但保留此前确认修改并清楚标记 partial。 */
    @Test
    void keepsConfirmedChangesWhenMutationChainBreaks() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("chain"));
        Path target = workspace.resolve("a.txt");
        TurnChangeTracker tracker = TurnChangeTracker.fresh(workspace);
        tracker.apply(receipt(target, false, "", true, "confirmed\n"));
        tracker.apply(receipt(target, true, "other\n", true, "unconfirmed\n"));
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
        TurnChangeTracker tracker = TurnChangeTracker.fresh(workspace);
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

    /** 工作区外路径和属性读取失败都只能降低完整性，不把敏感正文写进 artifact。 */
    @Test
    void rejectsOutsideAndUnverifiablePaths() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("paths"));
        TurnChangeTracker outside = TurnChangeTracker.fresh(workspace);
        outside.apply(receipt(temporary.resolve("outside.txt"), false, "", true, "secret"));
        TurnChangeTracker.Frozen outsideFrozen = outside.freeze();
        assertTrue(outsideFrozen.changeSet().files().isEmpty());
        assertTrue(outsideFrozen.changeSet().incompleteReasons()
                .contains(TurnChangeSet.IncompleteReason.OUTSIDE_WORKSPACE));

        Path existing = Files.writeString(workspace.resolve("existing.txt"), "old");
        TurnChangeTracker unreadable = new TurnChangeTracker(workspace, ignored -> {
            throw new IOException("fixture");
        });
        unreadable.apply(receipt(existing, true, "old", true, "secret"));
        TurnChangeTracker.Frozen unreadableFrozen = unreadable.freeze();
        assertTrue(unreadableFrozen.changeSet().files().isEmpty());
        assertTrue(unreadableFrozen.changeSet().incompleteReasons()
                .contains(TurnChangeSet.IncompleteReason.CAPTURE_FAILED));
    }

    /** 新增、删除、CRLF 和末尾换行变化必须保留为可重放的标准 unified diff。 */
    @Test
    void preservesLifecycleAndExactLineEndings() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("line-endings"));
        TurnChangeTracker tracker = TurnChangeTracker.fresh(workspace);
        tracker.apply(receipt(workspace.resolve("added.txt"), false, "", true, "added"));
        tracker.apply(receipt(workspace.resolve("deleted.txt"), true, "deleted", false, ""));
        tracker.apply(receipt(workspace.resolve("crlf.txt"), true, "old\r\n", true, "new\r\n"));
        tracker.apply(receipt(workspace.resolve("newline.txt"), true, "same", true, "same\n"));
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
        TurnChangeTracker tracker = TurnChangeTracker.fresh(workspace);
        for (int index = 0; index <= TurnChangeTracker.MAX_FILES; index++) {
            tracker.apply(receipt(workspace.resolve("f-" + index + ".txt"), false, "", true, "x"));
        }
        TurnChangeTracker.Frozen frozen = tracker.freeze();
        assertEquals(TurnChangeTracker.MAX_FILES, frozen.changeSet().files().size());
        assertTrue(frozen.changeSet().incompleteReasons()
                .contains(TurnChangeSet.IncompleteReason.LIMIT_EXCEEDED));
    }

    /** freeze 同步生成 artifact 身份、聚合摘要和 UTF-8 长度，并关闭后续修改入口。 */
    @Test
    void freezesArtifactIntegrityExactlyOnce() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("freeze"));
        TurnChangeTracker tracker = TurnChangeTracker.fresh(workspace);
        tracker.apply(receipt(workspace.resolve("a.txt"), false, "", true, "内容😀\n"));
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

    /** 测试收据统一走生产完整性工厂，避免夹具绕过 UTF-8 长度与 SHA-256 自校验。 */
    private static AgentTool.MutationReceipt receipt(Path path, boolean beforeExists, String before,
                                                     boolean afterExists, String after) {
        return AgentTool.MutationReceipt.of(path, beforeExists, before, afterExists, after);
    }
}
