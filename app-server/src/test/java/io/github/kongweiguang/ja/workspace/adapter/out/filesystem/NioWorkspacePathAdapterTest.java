// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.adapter.out.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.search.NativeSearchProcess;
import io.github.kongweiguang.ja.foundation.search.NativeSearchToolResolver;
import io.github.kongweiguang.ja.workspace.domain.WorkspaceEntryKind;
import io.github.kongweiguang.ja.workspace.domain.WorkspacePathFailure;
import io.github.kongweiguang.ja.workspace.port.out.WorkspacePathPort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 fd 参数、有限候选排序、取消/截断及路径引用的物理准入。 */
final class NioWorkspacePathAdapterTest {
    @TempDir
    private Path temporaryDirectory;

    /** fd 先过滤候选，再按既有排名规则稳定收口；正文从不进入路径搜索。 */
    @Test
    void searchesNamesAndRelativePathsInStableOrder() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Files.writeString(root.resolve("Composer.md"), "正文不参与路径搜索");
        Path source = Files.createDirectory(root.resolve("src"));
        Files.writeString(source.resolve("composer.tsx"), "另一份正文");
        Path nested = Files.createDirectory(source.resolve("feature"));
        Files.writeString(nested.resolve("my_composer_test.ts"), "fixture");
        AtomicReference<List<String>> arguments = new AtomicReference<>();
        NioWorkspacePathAdapter adapter = adapter(List.of(
                "src/feature/my_composer_test.ts", "src/composer.tsx", "Composer.md"),
                arguments, false);

        WorkspacePathPort.SearchOutcome result = adapter.search(root, "COMPOSER", 20,
                CancellationToken.none());

        assertEquals(List.of(
                "Composer.md",
                "src/composer.tsx",
                "src/feature/my_composer_test.ts"),
                result.entries().stream().map(WorkspacePathPort.PathEntry::relativePath).toList());
        assertFalse(result.truncated());
        assertEquals(3, result.candidateCount());
        assertTrue(arguments.get().contains("--hidden"));
        assertTrue(arguments.get().contains("--regex"));
        assertTrue(arguments.get().contains("--ignore-case"));
        assertTrue(arguments.get().contains("--full-path"));
        assertTrue(arguments.get().contains("--no-require-git"));
        assertTrue(arguments.get().contains("**/node_modules/**"));
        assertEquals("40", arguments.get().get(arguments.get().indexOf("--max-results") + 1));
    }

    /** 有界 fd 候选集仍按查询优先级裁剪，并诚实标记省略的候选。 */
    @Test
    void limitsResultsWithoutLosingStableRanking() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Files.writeString(root.resolve("zeta.txt"), "z");
        Files.writeString(root.resolve("alpha.txt"), "a");
        Files.writeString(root.resolve("beta.txt"), "b");
        NioWorkspacePathAdapter adapter = adapter(
                List.of("zeta.txt", "alpha.txt", "beta.txt"), new AtomicReference<>(), false);

        WorkspacePathPort.SearchOutcome result = adapter.search(root, "", 2,
                CancellationToken.none());

        assertEquals(List.of("alpha.txt", "beta.txt"),
                result.entries().stream().map(WorkspacePathPort.PathEntry::relativePath).toList());
        assertTrue(result.truncated());
    }

    /** fd deadline耗尽时返回已收集候选并保留截断事实，不阻塞 UI 等待全树扫描完成。 */
    @Test
    void reportsDeadlineAsTruncated() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        NioWorkspacePathAdapter adapter = adapter(List.of(), new AtomicReference<>(), true);

        WorkspacePathPort.SearchOutcome result = adapter.search(root, "missing", 20,
                CancellationToken.none());

        assertTrue(result.truncated());
        assertTrue(result.entries().isEmpty());
    }

    /** 在仓库内沿用 Git 自身的 ignore 作用域，不把祖先仓库外的规则混入搜索。 */
    @Test
    void keepsGitIgnoreScopedToRepository() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Files.createDirectory(root.resolve(".git"));
        AtomicReference<List<String>> arguments = new AtomicReference<>();
        NioWorkspacePathAdapter adapter = adapter(List.of(), arguments, false);

        adapter.search(root, "missing", 20, CancellationToken.none());

        assertFalse(arguments.get().contains("--no-require-git"));
    }

    /** 查询已被后续输入取消时不再解析工具或启动新的 native process。 */
    @Test
    void skipsProcessWhenCancellationAlreadyPublished() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        AtomicReference<List<String>> arguments = new AtomicReference<>();
        NioWorkspacePathAdapter adapter = adapter(List.of("file.txt"), arguments, false);

        WorkspacePathPort.SearchOutcome result = adapter.search(root, "file", 20, cancelledToken());

        assertTrue(result.truncated());
        assertTrue(result.entries().isEmpty());
        assertNull(arguments.get());
    }

    /** 已打开根被删除后搜索必须失败且不得重新创建目录，避免 Workspace 物理身份漂移。 */
    @Test
    void doesNotRecreateDeletedWorkspaceRoot() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Files.delete(root);
        NioWorkspacePathAdapter adapter = adapter(List.of(), new AtomicReference<>(), false);

        WorkspacePathFailure failure = assertThrows(WorkspacePathFailure.class,
                () -> adapter.search(root, "", 20, CancellationToken.none()));

        assertEquals(WorkspacePathFailure.Code.PATH_UNAVAILABLE, failure.code());
        assertFalse(Files.exists(root));
    }

    /** 引用消费继续重新检查类型与标准斜杠路径，不依赖搜索候选的旧 metadata。 */
    @Test
    void validatesReferenceKindAndNormalizedPath() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        Path directory = Files.createDirectory(root.resolve("src"));
        Files.writeString(directory.resolve("main.java"), "class Main {}");
        NioWorkspacePathAdapter adapter = adapter(List.of(), new AtomicReference<>(), false);

        WorkspacePathPort.ValidatedPath validated = adapter.validate(
                root, "src\\main.java", WorkspaceEntryKind.FILE);

        assertEquals("src/main.java", validated.relativePath());
        assertEquals(WorkspaceEntryKind.FILE, validated.kind());
        WorkspacePathFailure mismatch = assertThrows(WorkspacePathFailure.class,
                () -> adapter.validate(root, "src/main.java", WorkspaceEntryKind.DIRECTORY));
        assertEquals(WorkspacePathFailure.Code.TYPE_MISMATCH, mismatch.code());
    }

    /** 绝对路径与父级遍历在触碰目标 metadata 前即失败关闭。 */
    @Test
    void rejectsAbsoluteAndTraversalReferences() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        NioWorkspacePathAdapter adapter = adapter(List.of(), new AtomicReference<>(), false);

        WorkspacePathFailure traversal = assertThrows(WorkspacePathFailure.class,
                () -> adapter.validate(root, "../outside.txt", WorkspaceEntryKind.FILE));
        WorkspacePathFailure absolute = assertThrows(WorkspacePathFailure.class,
                () -> adapter.validate(root, temporaryDirectory.resolve("outside.txt").toString(),
                        WorkspaceEntryKind.FILE));

        assertEquals(WorkspacePathFailure.Code.CONFINEMENT, traversal.code());
        assertEquals(WorkspacePathFailure.Code.CONFINEMENT, absolute.code());
    }

    /** fd 候选仍须经过物理 containment；symlink 或逃逸项不会进入可选择列表。 */
    @Test
    void rejectsSymbolicLinksFromCandidateResults() throws IOException {
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
        NioWorkspacePathAdapter adapter = adapter(
                List.of("linked.txt"), new AtomicReference<>(), false);

        WorkspacePathPort.SearchOutcome result = adapter.search(root, "linked", 20,
                CancellationToken.none());

        assertTrue(result.entries().isEmpty());
        assertTrue(result.truncated());
        WorkspacePathFailure validationFailure = assertThrows(WorkspacePathFailure.class,
                () -> adapter.validate(root, "linked.txt", WorkspaceEntryKind.FILE));
        assertEquals(WorkspacePathFailure.Code.CONFINEMENT, validationFailure.code());
    }

    /** 用固定 fd 路径与可控输出替代宿主安装，测试 adapter 而不扫描测试机环境。 */
    private NioWorkspacePathAdapter adapter(List<String> output,
                                            AtomicReference<List<String>> capturedArguments,
                                            boolean deadlineExceeded) throws IOException {
        Path fakeFd = Files.writeString(temporaryDirectory.resolve("fd.exe"), "test fixture");
        NativeSearchToolResolver resolver = new NativeSearchToolResolver(fakeFd, null);
        NioWorkspacePathAdapter.SearchProcessRunner runner = (
                executable, arguments, workingDirectory, token, deadline, maxStdoutBytes,
                maxStderrBytes, environment, consumer) -> {
            capturedArguments.set(arguments);
            boolean stopped = false;
            for (String line : output) {
                if (!consumer.accept(line)) {
                    stopped = true;
                    break;
                }
            }
            return new NativeSearchProcess.Result(
                    deadlineExceeded ? -1 : 0, false, stopped, token.isCancellationRequested(),
                    deadlineExceeded, "");
        };
        return new NioWorkspacePathAdapter(resolver, runner);
    }

    /** 构造无共享状态的已取消令牌，确保过期查询不会启动 native 工具。 */
    private static CancellationToken cancelledToken() {
        return new CancellationToken() {
            /** 固定报告取消，测试不依赖时间竞争。 */
            @Override
            public boolean isCancellationRequested() {
                return true;
            }

            /** 提供脱敏固定原因，满足统一 token 契约。 */
            @Override
            public Optional<String> reason() {
                return Optional.of("superseded");
            }

            /** 令牌已取消，回调立即执行且不会保留引用。 */
            @Override
            public Registration onCancellation(Runnable callback) {
                callback.run();
                return Registration.noop();
            }
        };
    }
}
