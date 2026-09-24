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
    private static final String SESSION_DIRECTORY_NAME = "workspaces";
    private final Path dataDirectory;
    private final Path generalDirectory;
    private final Path homeDirectory;
    private final Path sessionDirectory;

    /**
     * 只冻结配置路径而不触发 IO，避免 composition 阶段提前创建数据目录。
     */
    public NioWorkspaceDirectoryAdapter(Path dataDirectory, Path homeDirectory) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath().normalize();
        this.generalDirectory = this.dataDirectory.resolve(GENERAL_DIRECTORY_NAME).normalize();
        this.homeDirectory = Objects.requireNonNull(homeDirectory, "homeDirectory")
                .toAbsolutePath().normalize();
        this.sessionDirectory = this.homeDirectory.resolve(SESSION_DIRECTORY_NAME).normalize();
        if (!this.generalDirectory.startsWith(this.dataDirectory)) {
            throw new IllegalArgumentException("general workspace escapes data directory");
        }
        if (!this.sessionDirectory.startsWith(this.homeDirectory)) {
            throw new IllegalArgumentException("session workspace escapes Ja home");
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
     * 创建会话工作区时用 Thread ID 固定唯一子目录；失败重试仅复用空目录，避免把残留用户文件接入新会话。
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
            if (!isEmpty(target)) throw confinement("new session workspace is not empty");
            return new WorkspaceDirectory(target, WorkspaceDirectory.Kind.SESSION);
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof WorkspaceFailure workspaceFailure) throw workspaceFailure;
            throw new WorkspaceFailure(WorkspaceFailure.Code.DIRECTORY_UNAVAILABLE,
                    "session workspace is unavailable", failure);
        }
    }

    /** 已登记 session 重开可以包含用户文件，但根必须仍是其 Thread ID 的直接子目录。 */
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
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof WorkspaceFailure workspaceFailure) throw workspaceFailure;
            throw new WorkspaceFailure(WorkspaceFailure.Code.DIRECTORY_UNAVAILABLE,
                    "session workspace is unavailable", failure);
        }
    }

    /** 旧共享根只允许按迁移前固定 data/general-workspace 身份显式重开。 */
    @Override
    public WorkspaceDirectory verifyLegacySharedDirectory(Path registeredRoot) {
        Path requested = Objects.requireNonNull(registeredRoot, "registeredRoot").toAbsolutePath().normalize();
        try {
            Path physicalData = canonicalDirectory(dataDirectory);
            Path root = canonicalDirectory(requested);
            if (!physicalData.equals(requireParent(root, "legacy workspace escaped data directory"))) {
                throw confinement("legacy workspace escaped data directory");
            }
            if (!requireFileName(root, "legacy workspace identity changed").equals(GENERAL_DIRECTORY_NAME)) {
                throw confinement("legacy workspace identity changed");
            }
            return new WorkspaceDirectory(root, WorkspaceDirectory.Kind.LEGACY_SHARED);
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof WorkspaceFailure workspaceFailure) throw workspaceFailure;
            throw new WorkspaceFailure(WorkspaceFailure.Code.DIRECTORY_UNAVAILABLE,
                    "legacy workspace is unavailable", failure);
        }
    }

    /**
     * 原始配置路径无需 IO 即可识别；已存在目录再比较物理根，兼容 Windows 8.3 拼写。
     * 不创建目录，且物理比较沿用链接拒绝策略，防止外部链接冒充通用工作区。
     */
    @Override
    public boolean isLegacySharedDirectory(Path root) {
        Path normalized = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        try {
            Path canonicalData = canonicalDirectory(dataDirectory);
            Path expected = canonicalDirectory(generalDirectory);
            Path candidate = canonicalDirectory(normalized);
            return candidate.equals(expected) && canonicalData.equals(candidate.getParent());
        } catch (IOException | WorkspaceFailure unavailable) {
            return false;
        }
    }

    /** 创建或重验 Ja Home 的真实目录身份，不跟随 Home 内的链接或 reparse point。 */
    private static Path ensureDirectory(Path path) throws IOException {
        Files.createDirectories(path);
        return canonicalDirectory(path);
    }

    /** 根目录没有父级时按身份冲突处理，避免文件系统边界校验因 null 而短路。 */
    private static Path requireParent(Path path, String failureMessage) {
        Path parent = path.getParent();
        if (parent == null) throw confinement(failureMessage);
        return parent;
    }

    /** 文件系统根没有名称时拒绝登记，避免把根路径误认为有效 Thread 或共享工作区。 */
    private static String requireFileName(Path path, String failureMessage) {
        Path fileName = path.getFileName();
        if (fileName == null) throw confinement(failureMessage);
        return fileName.toString();
    }

    /** sessions 根必须是 Ja Home 的直接子目录；并发首建时重验胜出的目录而不是误报创建失败。 */
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
            if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) throw concurrentCreate;
        }
        Path physicalChild = canonicalDirectory(child);
        requireDirectChild(physicalParent, physicalChild);
        return physicalChild;
    }

    /** 根路径身份既要匹配真实父目录，也要与登记的单段子目录保持一致。 */
    private static void requireDirectChild(Path parent, Path child) {
        if (!parent.equals(child.getParent())) throw confinement("workspace directory escaped its owner");
    }

    /** 新 thread 目录必须为空；directory stream 不递归物化用户文件。 */
    private static boolean isEmpty(Path directory) throws IOException {
        try (var entries = Files.newDirectoryStream(directory)) {
            return !entries.iterator().hasNext();
        }
    }

    /** 文件系统目录名只接受服务端生成的 Thread 标识。 */
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
}
