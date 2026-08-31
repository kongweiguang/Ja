// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.adapter.out.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.kongweiguang.ja.workspace.domain.WorkspaceDirectory;
import io.github.kongweiguang.ja.workspace.domain.WorkspaceFailure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 NIO 文件适配器的目录真实性、通用目录和链接拒绝语义。 */
final class NioWorkspaceDirectoryAdapterTest {
    @TempDir
    private Path temporaryDirectory;

    /** 普通现有目录返回规范物理根，且不会被误判为通用工作区。 */
    @Test
    void verifiesExistingProjectDirectory() throws IOException {
        Path data = Files.createDirectory(temporaryDirectory.resolve("data"));
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        NioWorkspaceDirectoryAdapter adapter = new NioWorkspaceDirectoryAdapter(data);

        WorkspaceDirectory verified = adapter.verifyProjectDirectory(project);

        assertEquals(project.toRealPath(), verified.root());
        assertEquals(WorkspaceDirectory.Kind.PROJECT, verified.kind());
        assertFalse(adapter.isGeneralDirectory(verified.root()));
    }

    /** 通用目录只能由 Java 在固定 data/general-workspace 位置创建并标记。 */
    @Test
    void createsGeneralDirectoryInsideDataBoundary() throws IOException {
        Path data = temporaryDirectory.resolve("data");
        NioWorkspaceDirectoryAdapter adapter = new NioWorkspaceDirectoryAdapter(data);

        WorkspaceDirectory general = adapter.ensureGeneralDirectory();

        assertEquals(data.resolve("general-workspace").toRealPath(), general.root());
        assertEquals(WorkspaceDirectory.Kind.GENERAL, general.kind());
        assertTrue(adapter.isGeneralDirectory(general.root()));
    }

    /** symlink 能力不可用时跳过环境门禁；一旦创建成功就必须以约束错误拒绝。 */
    @Test
    void rejectsSymbolicLinkWorkspaceRoot() throws IOException {
        Path data = Files.createDirectory(temporaryDirectory.resolve("data"));
        Path target = Files.createDirectory(temporaryDirectory.resolve("target"));
        Path link = temporaryDirectory.resolve("link");
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException unavailable) {
            assumeTrue(false, "当前文件系统不允许创建 symlink: " + unavailable.getClass().getSimpleName());
            return;
        }
        NioWorkspaceDirectoryAdapter adapter = new NioWorkspaceDirectoryAdapter(data);

        WorkspaceFailure failure = assertThrows(
                WorkspaceFailure.class, () -> adapter.verifyProjectDirectory(link));

        assertEquals(WorkspaceFailure.Code.DIRECTORY_CONFINEMENT, failure.code());
    }

    /** 非目录或缺失路径不能进入应用层目录状态。 */
    @Test
    void rejectsMissingDirectory() {
        NioWorkspaceDirectoryAdapter adapter = new NioWorkspaceDirectoryAdapter(
                temporaryDirectory.resolve("data"));

        WorkspaceFailure failure = assertThrows(WorkspaceFailure.class,
                () -> adapter.verifyProjectDirectory(temporaryDirectory.resolve("missing")));

        assertEquals(WorkspaceFailure.Code.DIRECTORY_UNAVAILABLE, failure.code());
    }
}
