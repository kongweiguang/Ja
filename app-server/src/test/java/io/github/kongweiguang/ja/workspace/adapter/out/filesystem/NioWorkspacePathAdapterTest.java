// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.adapter.out.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.kongweiguang.ja.workspace.domain.WorkspaceEntryKind;
import io.github.kongweiguang.ja.workspace.domain.WorkspacePathFailure;
import io.github.kongweiguang.ja.workspace.port.out.WorkspacePathPort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证路径搜索的排序、预算、忽略规则及引用物理准入。 */
final class NioWorkspacePathAdapterTest {
    @TempDir
    private Path temporaryDirectory;

    /** Windows 搜索不区分大小写，并按完整前缀、段前缀、模糊、深度和字典序排序。 */
    @Test
    void searchesNamesAndRelativePathsInStableOrder() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Files.writeString(root.resolve("Composer.md"), "正文不参与路径搜索");
        Path source = Files.createDirectory(root.resolve("src"));
        Files.writeString(source.resolve("composer.tsx"), "另一份正文");
        Files.writeString(source.resolve("command.ts"), "无关正文");
        Path nested = Files.createDirectory(source.resolve("feature"));
        Files.writeString(nested.resolve("my_composer_test.ts"), "fixture");

        WorkspacePathPort.SearchOutcome result = new NioWorkspacePathAdapter()
                .search(root, "COMPOSER", 20);

        assertEquals(List.of(
                "Composer.md",
                "src/composer.tsx",
                "src/feature/my_composer_test.ts"),
                result.entries().stream().map(WorkspacePathPort.PathEntry::relativePath).toList());
        assertFalse(result.truncated());
        assertEquals(6, result.scannedEntries());
        assertTrue(new NioWorkspacePathAdapter()
                .search(root, "正文不参与路径搜索", 20).entries().isEmpty());
    }

    /** 忽略目录与正文搜索保持一致，生成物不会消耗其内部条目扫描预算。 */
    @Test
    void skipsSharedIgnoredDirectories() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Path git = Files.createDirectory(root.resolve(".git"));
        Files.writeString(git.resolve("Composer-secret"), "ignored");
        Path target = Files.createDirectory(root.resolve("target-debug"));
        Files.writeString(target.resolve("Composer.class"), "ignored");
        Files.writeString(root.resolve("Composer.java"), "visible");

        WorkspacePathPort.SearchOutcome result = new NioWorkspacePathAdapter()
                .search(root, "composer", 20);

        assertEquals(List.of("Composer.java"),
                result.entries().stream().map(WorkspacePathPort.PathEntry::relativePath).toList());
        assertEquals(3, result.scannedEntries());
    }

    /** 返回数量小于匹配数时必须诚实标记 truncated，且仍返回全局最优条目。 */
    @Test
    void limitsResultsWithoutLosingStableRanking() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Files.writeString(root.resolve("zeta.txt"), "z");
        Files.writeString(root.resolve("alpha.txt"), "a");
        Files.writeString(root.resolve("beta.txt"), "b");

        WorkspacePathPort.SearchOutcome result = new NioWorkspacePathAdapter()
                .search(root, "", 2);

        assertEquals(List.of("alpha.txt", "beta.txt"),
                result.entries().stream().map(WorkspacePathPort.PathEntry::relativePath).toList());
        assertTrue(result.truncated());
    }

    /** 单调时钟到达两秒预算后立即停止，不依赖真实 sleep 或机器性能。 */
    @Test
    void stopsAtFixedDeadline() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Files.writeString(root.resolve("file.txt"), "content");
        AtomicInteger ticks = new AtomicInteger();
        NioWorkspacePathAdapter adapter = new NioWorkspacePathAdapter(() ->
                ticks.getAndIncrement() == 0 ? 0 : NioWorkspacePathAdapter.DEADLINE.toNanos());

        WorkspacePathPort.SearchOutcome result = adapter.search(root, "", 20);

        assertTrue(result.truncated());
        assertTrue(result.entries().isEmpty());
        assertEquals(0, result.scannedEntries());
    }

    /** 引用校验返回标准斜杠相对路径，并拒绝声明类型与物理类型不一致。 */
    @Test
    void validatesReferenceKindAndNormalizedPath() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Path directory = Files.createDirectory(root.resolve("src"));
        Files.writeString(directory.resolve("main.java"), "class Main {}");
        NioWorkspacePathAdapter adapter = new NioWorkspacePathAdapter();

        WorkspacePathPort.ValidatedPath validated = adapter.validate(
                root, "src\\main.java", WorkspaceEntryKind.FILE);

        assertEquals("src/main.java", validated.relativePath());
        assertEquals(WorkspaceEntryKind.FILE, validated.kind());
        WorkspacePathFailure mismatch = assertThrows(WorkspacePathFailure.class,
                () -> adapter.validate(root, "src/main.java", WorkspaceEntryKind.DIRECTORY));
        assertEquals(WorkspacePathFailure.Code.TYPE_MISMATCH, mismatch.code());
    }

    /** 已打开根被删除后搜索必须失败且不得重新创建目录，避免 Workspace 物理身份漂移。 */
    @Test
    void doesNotRecreateDeletedWorkspaceRoot() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Files.delete(root);

        WorkspacePathFailure failure = assertThrows(WorkspacePathFailure.class,
                () -> new NioWorkspacePathAdapter().search(root, "", 20));

        assertEquals(WorkspacePathFailure.Code.PATH_UNAVAILABLE, failure.code());
        assertFalse(Files.exists(root));
    }

    /** 绝对路径与父级遍历在触碰目标 metadata 前即失败关闭。 */
    @Test
    void rejectsAbsoluteAndTraversalReferences() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        NioWorkspacePathAdapter adapter = new NioWorkspacePathAdapter();

        WorkspacePathFailure traversal = assertThrows(WorkspacePathFailure.class,
                () -> adapter.validate(root, "../outside.txt", WorkspaceEntryKind.FILE));
        WorkspacePathFailure absolute = assertThrows(WorkspacePathFailure.class,
                () -> adapter.validate(root, temporaryDirectory.resolve("outside.txt").toString(),
                        WorkspaceEntryKind.FILE));

        assertEquals(WorkspacePathFailure.Code.CONFINEMENT, traversal.code());
        assertEquals(WorkspacePathFailure.Code.CONFINEMENT, absolute.code());
    }

    /** symlink 能力可用时，搜索与直接引用都不得跟随其进入目标。 */
    @Test
    void rejectsSymbolicLinksDuringSearchAndValidation() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Path outside = Files.writeString(temporaryDirectory.resolve("outside.txt"), "outside");
        Path link = root.resolve("linked.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException unavailable) {
            assumeTrue(false, "当前文件系统不允许创建 symlink: "
                    + unavailable.getClass().getSimpleName());
            return;
        }
        NioWorkspacePathAdapter adapter = new NioWorkspacePathAdapter();

        WorkspacePathFailure searchFailure = assertThrows(WorkspacePathFailure.class,
                () -> adapter.search(root, "linked", 20));
        WorkspacePathFailure validationFailure = assertThrows(WorkspacePathFailure.class,
                () -> adapter.validate(root, "linked.txt", WorkspaceEntryKind.FILE));

        assertEquals(WorkspacePathFailure.Code.CONFINEMENT, searchFailure.code());
        assertEquals(WorkspacePathFailure.Code.CONFINEMENT, validationFailure.code());
    }
}
