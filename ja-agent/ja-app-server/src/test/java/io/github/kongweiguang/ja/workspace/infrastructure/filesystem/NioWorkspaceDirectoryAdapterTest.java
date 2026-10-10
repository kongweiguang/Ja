// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.infrastructure.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.kongweiguang.ja.workspace.domain.model.WorkspaceDirectory;
import io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** 验证 NIO 文件适配器的项目、独立会话目录真实性和链接拒绝语义。 */
final class NioWorkspaceDirectoryAdapterTest {
    @TempDir
    private Path temporaryDirectory;

    /** 普通现有目录返回规范物理根并保留项目类型。 */
    @Test
    void verifiesExistingProjectDirectory() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path home = Files.createDirectory(temporaryDirectory.resolve("home"));
        NioWorkspaceDirectoryAdapter adapter = new NioWorkspaceDirectoryAdapter(home);

        WorkspaceDirectory verified = adapter.verifyProjectDirectory(project);

        assertEquals(project.toRealPath(), verified.root());
        assertEquals(WorkspaceDirectory.Kind.PROJECT, verified.kind());
    }

    /** Windows 命名空间路径可能同时携带 8.3 别名；允许词法别名但仍返回唯一物理根。 */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void acceptsWindowsPathAliasForExistingProjectDirectory() throws IOException {
        Path project = Files.createDirectory(temporaryDirectory.resolve("alias-project"));
        Path home = Files.createDirectory(temporaryDirectory.resolve("alias-home"));
        Path namespaced = Path.of("\\\\?\\" + project.toAbsolutePath());
        NioWorkspaceDirectoryAdapter adapter = new NioWorkspaceDirectoryAdapter(home);

        WorkspaceDirectory verified = adapter.verifyProjectDirectory(namespaced);

        assertEquals(project.toRealPath(), verified.root());
    }

    /** Session 目录以 Ja Home 为唯一物理边界；重复创建只接受仍为空的残留目录。 */
    @Test
    void createsDistinctEmptySessionDirectoriesInsideJaHome() throws IOException {
        Path home = Files.createDirectory(temporaryDirectory.resolve("home"));
        NioWorkspaceDirectoryAdapter adapter = new NioWorkspaceDirectoryAdapter(home);

        WorkspaceDirectory first = adapter.createSessionDirectory("thr_first");
        WorkspaceDirectory second = adapter.createSessionDirectory("thr_second");

        assertEquals(home.resolve("workspaces/thr_first").toRealPath(), first.root());
        assertEquals(home.resolve("workspaces/thr_second").toRealPath(), second.root());
        assertEquals(WorkspaceDirectory.Kind.SESSION, first.kind());
        assertNotEquals(first.root(), second.root());
        assertEquals(first, adapter.verifySessionDirectory("thr_first", first.root()));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.createSessionDirectory("thr_first/../other"));
    }

    /** symlink 能力不可用时跳过环境门禁；一旦创建成功就必须以约束错误拒绝。 */
    @Test
    void rejectsSymbolicLinkWorkspaceRoot() throws IOException {
        Path home = Files.createDirectory(temporaryDirectory.resolve("home"));
        Path target = Files.createDirectory(temporaryDirectory.resolve("target"));
        Path link = temporaryDirectory.resolve("link");
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException unavailable) {
            assumeTrue(false, "当前文件系统不允许创建 symlink: " + unavailable.getClass().getSimpleName());
            return;
        }
        NioWorkspaceDirectoryAdapter adapter = new NioWorkspaceDirectoryAdapter(home);

        WorkspaceFailure failure = assertThrows(
                WorkspaceFailure.class, () -> adapter.verifyProjectDirectory(link));

        assertEquals(WorkspaceFailure.Code.DIRECTORY_CONFINEMENT, failure.code());
    }

    /** Windows junction 有独立于 symlink 权限的创建路径；验证其外部目标被拒绝且 Home 内实体目录仍可重验。
     * finally 只删除隔离 tmp 下的 junction 入口，marker 证明 target 未被递归触碰；本测试不替代 symlink 用例。
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsDirectoryJunctionToOutsideTargetWhilePreservingHomeSession()
            throws IOException, InterruptedException {
        Path home = Files.createDirectory(temporaryDirectory.resolve("junction-home"));
        Path sessions = Files.createDirectory(home.resolve("workspaces"));
        Path target = Files.createDirectory(temporaryDirectory.resolve("outside-target"));
        Path targetMarker = Files.createFile(target.resolve("preserve.txt"));
        Path validRoot = Files.createDirectory(sessions.resolve("thr_inside"));
        Path junction = sessions.resolve("thr_outside");
        NioWorkspaceDirectoryAdapter adapter = new NioWorkspaceDirectoryAdapter(home);

        WorkspaceDirectory verified = adapter.verifySessionDirectory("thr_inside", validRoot);
        assertEquals(validRoot.toRealPath(), verified.root());
        assertEquals(WorkspaceDirectory.Kind.SESSION, verified.kind());
        assertFalse(target.toRealPath().startsWith(home.toRealPath()));

        try {
            createDirectoryJunction(junction, target);
            assertEquals(target.toRealPath(), junction.toRealPath());

            WorkspaceFailure failure = assertThrows(WorkspaceFailure.class,
                    () -> adapter.verifySessionDirectory("thr_outside", junction));

            assertEquals(WorkspaceFailure.Code.DIRECTORY_CONFINEMENT, failure.code());
        } finally {
            Files.deleteIfExists(junction); // Remove only this junction entry; never recurse into its target.
        }

        assertFalse(Files.exists(junction, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(targetMarker, LinkOption.NOFOLLOW_LINKS));
    }

    /** 使用真实目录 junction 验证 Windows 链接边界，失败时保留 cmd 输出便于诊断。 */
    private static void createDirectoryJunction(Path junction, Path target)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder("cmd.exe", "/d", "/u", "/c", "mklink", "/J",
                junction.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor();
            throw new AssertionError("mklink /J did not finish within 10 seconds");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_16LE).strip();
        assertEquals(0, process.exitValue(), "mklink /J failed: " + output);
    }

    /** 非目录或缺失路径不能进入应用层目录状态。 */
    @Test
    void rejectsMissingDirectory() {
        Path home = temporaryDirectory.resolve("home");
        NioWorkspaceDirectoryAdapter adapter = new NioWorkspaceDirectoryAdapter(home);

        WorkspaceFailure failure = assertThrows(WorkspaceFailure.class,
                () -> adapter.verifyProjectDirectory(temporaryDirectory.resolve("missing")));

        assertEquals(WorkspaceFailure.Code.DIRECTORY_UNAVAILABLE, failure.code());
    }
}
