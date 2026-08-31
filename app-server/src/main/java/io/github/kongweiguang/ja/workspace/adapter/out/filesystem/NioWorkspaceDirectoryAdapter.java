// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.adapter.out.filesystem;

import io.github.kongweiguang.ja.workspace.domain.WorkspaceDirectory;
import io.github.kongweiguang.ja.workspace.domain.WorkspaceFailure;
import io.github.kongweiguang.ja.workspace.port.out.WorkspaceDirectoryPort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * 基于 Java NIO 验证目录真实性，并把通用工作区限制在固定数据目录内。
 */
public final class NioWorkspaceDirectoryAdapter implements WorkspaceDirectoryPort {
    private static final String GENERAL_DIRECTORY_NAME = "general-workspace";
    private final Path dataDirectory;
    private final Path generalDirectory;

    /**
     * 只冻结配置路径而不触发 IO，避免 composition 阶段提前创建数据目录。
     */
    public NioWorkspaceDirectoryAdapter(Path dataDirectory) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath().normalize();
        this.generalDirectory = this.dataDirectory.resolve(GENERAL_DIRECTORY_NAME).normalize();
        if (!this.generalDirectory.startsWith(this.dataDirectory)) {
            throw new IllegalArgumentException("general workspace escapes data directory");
        }
    }

    /**
     * 使用 NOFOLLOW 与真实路径双重检查，拒绝 symlink、junction 和 reparse alias。
     */
    @Override
    public WorkspaceDirectory verifyProjectDirectory(Path requestedRoot) {
        Path root = Objects.requireNonNull(requestedRoot, "requestedRoot")
                .toAbsolutePath().normalize();
        try {
            return new WorkspaceDirectory(canonicalDirectory(root), WorkspaceDirectory.Kind.PROJECT);
        } catch (WorkspaceFailure failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new WorkspaceFailure(WorkspaceFailure.Code.DIRECTORY_UNAVAILABLE,
                    "workspace directory is unavailable", failure);
        }
    }

    /**
     * 先固定数据目录物理身份，再创建子目录，阻止数据目录链接把通用工作区重定向。
     */
    @Override
    public WorkspaceDirectory ensureGeneralDirectory() {
        try {
            Files.createDirectories(dataDirectory);
            Path physicalData = canonicalDirectory(dataDirectory);
            if (!physicalData.equals(dataDirectory)) {
                throw confinement("data directory identity changed");
            }
            Files.createDirectories(generalDirectory);
            Path physicalGeneral = canonicalDirectory(generalDirectory);
            if (!physicalData.equals(physicalGeneral.getParent())) {
                throw confinement("general workspace escaped data directory");
            }
            return new WorkspaceDirectory(physicalGeneral, WorkspaceDirectory.Kind.GENERAL);
        } catch (IOException | RuntimeException failure) {
            throw new WorkspaceFailure(WorkspaceFailure.Code.GENERAL_WORKSPACE_UNAVAILABLE,
                    "general workspace is unavailable", failure);
        }
    }

    /**
     * 只做绝对规范路径比较，不检查存在性，也不会创建通用目录。
     */
    @Override
    public boolean isGeneralDirectory(Path root) {
        return Objects.requireNonNull(root, "root").toAbsolutePath().normalize()
                .equals(generalDirectory);
    }

    /**
     * 验证目标是普通物理目录且跟随链接前后身份一致。
     */
    private static Path canonicalDirectory(Path lexical) throws IOException {
        if (Files.isSymbolicLink(lexical)) {
            throw confinement("workspace root is linked");
        }
        if (!Files.isDirectory(lexical, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceFailure(WorkspaceFailure.Code.DIRECTORY_UNAVAILABLE,
                    "workspace directory is unavailable");
        }
        rejectLinkOrReparse(lexical);
        Path physical = lexical.toRealPath();
        if (!physical.equals(lexical)) {
            throw confinement("workspace root is linked");
        }
        return physical;
    }

    /**
     * 比较 NOFOLLOW 与跟随后的物理身份，覆盖 Windows junction/reparse point。
     */
    private static void rejectLinkOrReparse(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw confinement("workspace root is linked");
        }
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Path noFollow = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path followed = path.toRealPath();
        if (attributes.isOther() || !noFollow.equals(followed)) {
            throw confinement("workspace root is a reparse alias");
        }
    }

    /**
     * 文件系统约束失败使用稳定分类，具体路径只留在本地异常调用栈。
     */
    private static WorkspaceFailure confinement(String message) {
        return new WorkspaceFailure(WorkspaceFailure.Code.DIRECTORY_CONFINEMENT, message);
    }
}
