// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.infrastructure.filesystem;

import io.github.kongweiguang.ja.workspace.domain.model.WorkspaceDirectory;
import io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceDirectoryPort;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * 基于 Java NIO 验证目录真实性，并把独立会话工作区限制在 Ja Home 内。
 */
public final class NioWorkspaceDirectoryAdapter implements WorkspaceDirectoryPort {
    private static final String SESSION_DIRECTORY_NAME = "workspaces";
    private final Path homeDirectory;
    private final Path sessionDirectory;

    /**
     * session 物理边界只归 Ja Home；构造阶段仅冻结该路径，避免提前创建目录或引入无关存储根。
     */
    public NioWorkspaceDirectoryAdapter(Path homeDirectory) {
        this.homeDirectory = Objects.requireNonNull(homeDirectory, "homeDirectory")
                .toAbsolutePath().normalize();
        this.sessionDirectory = this.homeDirectory.resolve(SESSION_DIRECTORY_NAME).normalize();
        if (!this.sessionDirectory.startsWith(this.homeDirectory)) {
            throw new IllegalArgumentException("session workspace escapes Ja home");
        }
    }

    /**
     * 创建或重验 Ja Home 的真实目录身份，不跟随 Home 内的链接或 reparse point。
     */
    private static Path ensureDirectory(Path path) throws IOException {
        Files.createDirectories(path);
        return canonicalDirectory(path);
    }

    /**
     * 根目录没有父级时按身份冲突处理，避免文件系统边界校验因 null 而短路。
     */
    private static Path requireParent(Path path, String failureMessage) {
        Path parent = path.getParent();
        if (parent == null) {
            throw confinement(failureMessage);
        }
        return parent;
    }

    /**
     * 文件系统根没有名称时拒绝登记，避免把根路径误认为有效 Thread 或共享工作区。
     */
    private static String requireFileName(Path path, String failureMessage) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            throw confinement(failureMessage);
        }
        return fileName.toString();
    }

    /**
     * sessions 根必须是 Ja Home 的直接子目录；并发首建时重验胜出的目录而不是误报创建失败。
     */
    private static Path ensureChildDirectory(Path physicalParent, Path child) throws IOException {
        if (Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
            Path physicalChild = canonicalDirectory(child);
            requireDirectChild(physicalParent, physicalChild);
            return physicalChild;
        }
        try {
            Files.createDirectory(child);
        } catch (java.nio.file.FileAlreadyExistsException concurrentCreate) {
            // 同时创建不同 SESSION 的 RPC 可能竞争唯一 workspaces 根；下方真实性检查仍拒绝链接/文件。
            if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
                throw concurrentCreate;
            }
        }
        Path physicalChild = canonicalDirectory(child);
        requireDirectChild(physicalParent, physicalChild);
        return physicalChild;
    }

    /**
     * 根路径身份既要匹配真实父目录，也要与登记的单段子目录保持一致。
     */
    private static void requireDirectChild(Path parent, Path child) {
        if (!parent.equals(child.getParent())) {
            throw confinement("workspace directory escaped its owner");
        }
    }

    /**
     * 新 thread 目录必须为空；directory stream 不递归物化用户文件。
     */
    private static boolean isEmpty(Path directory) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            return !entries.iterator().hasNext();
        }
    }

    /**
     * 文件系统目录名只接受服务端生成的 Thread 标识。
     */
    private static String requireThreadId(String value) {
        if (value == null || !value.matches("thr_[A-Za-z0-9_-]{1,96}")) {
            throw new IllegalArgumentException("invalid session thread identity");
        }
        return value;
    }

    /**
     * 验证目标是普通物理目录且跟随链接前后身份一致；词法路径可使用 Windows
     * 8.3、大小写或命名空间别名，因此不能与 real path 做字符串相等比较。
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
        return lexical.toRealPath();
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
     * NIO 的 same-file 判断消除大小写与文件系统别名差异；旧根已删除时回退到平台 Path 身份比较。
     */
    @Override
    public boolean samePhysicalDirectory(Path first, Path second) {
        Path left = Objects.requireNonNull(first, "first").toAbsolutePath().normalize();
        Path right = Objects.requireNonNull(second, "second").toAbsolutePath().normalize();
        try {
            return Files.isSameFile(left, right);
        } catch (IOException unavailable) {
            return left.equals(right);
        }
    }

    /**
     * 用 Thread ID 固定唯一子目录；领域拒绝保持原身份，底层故障保留原因，重试只复用空目录。
     */
    @Override
    public WorkspaceDirectory createSessionDirectory(String threadId) {
        String safeThreadId = requireThreadId(threadId);
        try {
            Path physicalHome = ensureDirectory(homeDirectory);
            Path physicalSessions = ensureChildDirectory(physicalHome, sessionDirectory);
            Path lexicalTarget = physicalSessions.resolve(safeThreadId);
            Path target;
            if (Files.exists(lexicalTarget, LinkOption.NOFOLLOW_LINKS)) {
                target = canonicalDirectory(lexicalTarget);
            } else {
                Files.createDirectory(lexicalTarget);
                target = canonicalDirectory(lexicalTarget);
            }
            requireDirectChild(physicalSessions, target);
            if (!isEmpty(target)) {
                throw confinement("new session workspace is not empty");
            }
            return new WorkspaceDirectory(target, WorkspaceDirectory.Kind.SESSION);
        } catch (WorkspaceFailure failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new WorkspaceFailure(WorkspaceFailure.Code.DIRECTORY_UNAVAILABLE,
                    "session workspace is unavailable", failure);
        }
    }

    /**
     * 重开保留用户文件，但根仍须属于原 Thread；已分类的身份拒绝不得重包装为目录不可用。
     */
    @Override
    public WorkspaceDirectory verifySessionDirectory(String threadId, Path registeredRoot) {
        String safeThreadId = requireThreadId(threadId);
        Path requested = Objects.requireNonNull(registeredRoot, "registeredRoot").toAbsolutePath().normalize();
        try {
            Path physicalHome = canonicalDirectory(homeDirectory);
            Path physicalSessions = canonicalDirectory(physicalHome.resolve(SESSION_DIRECTORY_NAME));
            if (!physicalHome.equals(requireParent(physicalSessions, "session workspace escaped Ja home"))) {
                throw confinement("session workspace escaped Ja home");
            }
            Path root = canonicalDirectory(requested);
            requireDirectChild(physicalSessions, root);
            if (!safeThreadId.equals(requireFileName(root, "session workspace identity changed"))) {
                throw confinement("session workspace identity changed");
            }
            return new WorkspaceDirectory(root, WorkspaceDirectory.Kind.SESSION);
        } catch (WorkspaceFailure failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new WorkspaceFailure(WorkspaceFailure.Code.DIRECTORY_UNAVAILABLE,
                    "session workspace is unavailable", failure);
        }
    }

}
