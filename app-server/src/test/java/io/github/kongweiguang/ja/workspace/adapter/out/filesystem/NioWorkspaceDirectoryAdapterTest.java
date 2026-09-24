// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.adapter.out.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.kongweiguang.ja.workspace.domain.WorkspaceDirectory;
import io.github.kongweiguang.ja.workspace.domain.WorkspaceFailure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** 验证 NIO 文件适配器的项目、独立会话目录真实性和链接拒绝语义。 */
final class NioWorkspaceDirectoryAdapterTest {
    @TempDir
    private Path temporaryDirectory;

    /** 普通现有目录返回规范物理根，且不会被误判为通用工作区。 */
    @Test
    void verifiesExistingProjectDirectory() throws IOException {
        Path data = Files.createDirectory(temporaryDirectory.resolve("data"));
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        Path home = Files.createDirectory(temporaryDirectory.resolve("home"));
        NioWorkspaceDirectoryAdapter adapter = new NioWorkspaceDirectoryAdapter(data, home);

        WorkspaceDirectory verified = adapter.verifyProjectDirectory(project);

        assertEquals(project.toRealPath(), verified.root());
        assertEquals(WorkspaceDirectory.Kind.PROJECT, verified.kind());
        assertFalse(adapter.isLegacySharedDirectory(verified.root()));
    }

    /** Windows 命名空间路径可能同时携带 8.3 别名；允许词法别名但仍返回唯一物理根。 */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void acceptsWindowsPathAliasForExistingProjectDirectory() throws IOException {
        Path data = Files.createDirectory(temporaryDirectory.resolve("alias-data"));
        Path project = Files.createDirectory(temporaryDirectory.resolve("alias-project"));
        Path home = Files.createDirectory(temporaryDirectory.resolve("alias-home"));
        Path namespaced = Path.of("\\\\?\\" + project.toAbsolutePath());
        NioWorkspaceDirectoryAdapter adapter = new NioWorkspaceDirectoryAdapter(data, home);

        WorkspaceDirectory verified = adapter.verifyProjectDirectory(namespaced);

        assertEquals(project.toRealPath(), verified.root());
    }

    /** 主 Thread ID 决定空会话目录，重复创建只接受仍为空的残留目录。 */
    @Test
    void createsDistinctEmptySessionDirectoriesInsideJaHome() throws IOException {
        Path data = Files.createDirectory(temporaryDirectory.resolve("data"));
        Path home = Files.createDirectory(temporaryDirectory.resolve("home"));
        NioWorkspaceDirectoryAdapter adapter = new NioWorkspaceDirectoryAdapter(data, home);

        WorkspaceDirectory first = adapter.createSessionDirectory("thr_first");
        WorkspaceDirectory second = adapter.createSessionDirectory("thr_second");

        assertEquals(home.resolve("workspaces/thr_first").toRealPath(), first.root());
        assertEquals(home.resolve("workspaces/thr_second").toRealPath(), second.root());
        assertEquals(WorkspaceDirectory.Kind.SESSION, first.kind());
        assertNotEquals(first.root(), second.root());
        assertEquals(first, adapter.verifySessionDirectory("thr_first", first.root()));
        assertFalse(Files.exists(data.resolve("general-workspace")));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.createSessionDirectory("thr_first/../other"));
    }

    /** symlink 能力不可用时跳过环境门禁；一旦创建成功就必须以约束错误拒绝。 */
    @Test
    void rejectsSymbolicLinkWorkspaceRoot() throws IOException {
        Path data = Files.createDirectory(temporaryDirectory.resolve("data"));
        Path home = Files.createDirectory(temporaryDirectory.resolve("home"));
        Path target = Files.createDirectory(temporaryDirectory.resolve("target"));
        Path link = temporaryDirectory.resolve("link");
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException unavailable) {
            assumeTrue(false, "当前文件系统不允许创建 symlink: " + unavailable.getClass().getSimpleName());
            return;
        }
        NioWorkspaceDirectoryAdapter adapter = new NioWorkspaceDirectoryAdapter(data, home);

        WorkspaceFailure failure = assertThrows(
                WorkspaceFailure.class, () -> adapter.verifyProjectDirectory(link));

        assertEquals(WorkspaceFailure.Code.DIRECTORY_CONFINEMENT, failure.code());
    }

    /** 非目录或缺失路径不能进入应用层目录状态。 */
    @Test
    void rejectsMissingDirectory() {
        NioWorkspaceDirectoryAdapter adapter = new NioWorkspaceDirectoryAdapter(
                temporaryDirectory.resolve("data"), temporaryDirectory.resolve("home"));

        WorkspaceFailure failure = assertThrows(WorkspaceFailure.class,
                () -> adapter.verifyProjectDirectory(temporaryDirectory.resolve("missing")));

        assertEquals(WorkspaceFailure.Code.DIRECTORY_UNAVAILABLE, failure.code());
    }
}
