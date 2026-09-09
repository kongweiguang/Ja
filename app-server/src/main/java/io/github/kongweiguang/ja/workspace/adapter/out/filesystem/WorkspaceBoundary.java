// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.adapter.out.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 在单个物理工作区内解析模型路径，但不声称提供操作系统级沙箱。
 */
public final class WorkspaceBoundary {
    private final Path root;
    private final Path physicalRoot;

    /**
     * 同时固定现有词法根与物理根，以拒绝 junction、reparse point 和 symlink 逃逸；
     * 路径能力不得在 Workspace 被外部删除后静默重建一个不同物理身份的空目录。
     */
    public WorkspaceBoundary(Path root) {
        Objects.requireNonNull(root, "root");
        try {
            this.root = root.toAbsolutePath().normalize();
            if (!Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("workspace_root_unavailable");
            }
            rejectLinkOrReparse(this.root);
            this.physicalRoot = this.root.toRealPath();
        } catch (IOException failure) {
            throw new IllegalArgumentException("workspace_root_invalid", failure);
        }
    }

    /**
     * 返回作为进程工作目录使用的规范物理根。
     */
    public Path root() {
        return physicalRoot;
    }

    /**
     * 解析一个已存在路径，并确认最终物理目标仍在工作区内。
     */
    public Path existing(String input) throws IOException {
        Path candidate = lexical(input);
        rejectLinksAndReparse(candidate);
        Path physical = candidate.toRealPath();
        requireContained(physical);
        return physical;
    }

    /**
     * 解析写入目标，并检查每个已存在祖先不存在链接或 junction 逃逸。
     */
    public Path target(String input) throws IOException {
        Path candidate = lexical(input);
        rejectLinksAndReparse(candidate);
        Path cursor = candidate;
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            cursor = cursor.getParent();
        }
        if (cursor == null) {
            throw new IOException("workspace_ancestor_missing");
        }
        rejectLinksAndReparse(cursor);
        requireContained(cursor.toRealPath());
        return candidate;
    }

    /**
     * 在提交前重新验证父目录，以缩小文件系统 TOCTOU 竞争窗口。
     */
    public void revalidateParent(Path target) throws IOException {
        Path lexicalTarget = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        if (!lexicalTarget.startsWith(root)) {
            throw new SecurityException("workspace_escape");
        }
        rejectLinksAndReparse(lexicalTarget);
        Path parent = Objects.requireNonNull(lexicalTarget.getParent(), "target parent").toRealPath();
        requireContained(parent);
    }

    /**
     * 在创建临时文件前冻结父目录的 NOFOLLOW 物理路径与文件系统 identity；该 opaque guard
     * 只用于同一次 mutation，不向调用方暴露可被序列化的路径事实。
     */
    public MutationGuard mutationGuard(Path target) throws IOException {
        Path lexicalTarget = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        revalidateParent(lexicalTarget);
        Path parent = Objects.requireNonNull(lexicalTarget.getParent(), "target parent");
        BasicFileAttributes attributes = Files.readAttributes(
                parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Object fileKey = attributes.fileKey();
        if (!attributes.isDirectory()) {
            throw new IOException("workspace_parent_identity_unavailable");
        }
        return new MutationGuard(parent, parent.toRealPath(LinkOption.NOFOLLOW_LINKS), fileKey);
    }

    /**
     * 在临时文件创建、写入和 move 的每个边界复核同一父目录；Windows NIO 不提供可靠目录句柄，
     * provider 也可能不提供 fileKey，因而 NOFOLLOW 真实路径与可用 fileKey 只能缩小竞态窗口，
     * 不能宣称消除恶意 TOCTOU。
     */
    public void revalidateMutationGuard(MutationGuard guard) throws IOException {
        MutationGuard expected = Objects.requireNonNull(guard, "guard");
        rejectLinksAndReparse(expected.parent);
        BasicFileAttributes attributes = Files.readAttributes(
                expected.parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Path noFollow = expected.parent.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()
                || expected.fileKey != null && !expected.fileKey.equals(attributes.fileKey())
                || !expected.noFollowRealPath.equals(noFollow)) {
            throw new SecurityException("workspace_parent_identity_changed");
        }
        requireContained(expected.parent.toRealPath());
    }

    /**
     * 把受约束物理路径转换为稳定的斜杠分隔模型输出。
     */
    public String relative(Path path) throws IOException {
        Path lexicalPath = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        rejectLinksAndReparse(lexicalPath);
        Path physical = Files.exists(lexicalPath, LinkOption.NOFOLLOW_LINKS)
                ? lexicalPath.toRealPath() : lexicalPath;
        requireContained(physical);
        return physicalRoot.relativize(physical).toString().replace('\\', '/');
    }

    /**
     * 逐个准入遍历项，并在遍历过程中执行硬上限，防止目录列表或搜索退化为无界扫描；
     * 遇到任一链接或 reparse point 时整个操作失败，避免返回部分可信结果。
     */
    public WalkResult walkExisting(Path start, int maximumDepth, int maximumEntries) throws IOException {
        if (maximumDepth < 0 || maximumDepth > 64 || maximumEntries < 1 || maximumEntries > 20_000) {
            throw new IllegalArgumentException("workspace_walk_limit_invalid");
        }
        Path admitted = admitExisting(start);
        List<Path> entries = new ArrayList<>(Math.min(maximumEntries, 1_024));
        boolean truncated = walkDirectory(admitted, 0, maximumDepth, maximumEntries, entries);
        return new WalkResult(entries, truncated);
    }

    /**
     * 再次准入调用方持有的已存在路径，以便在 IO 前发现替换竞争。
     */
    public Path revalidateExisting(Path path) throws IOException {
        return admitExisting(path);
    }

    /**
     * 每层最多枚举剩余预算加一个哨兵，达到上限后不再递归。
     */
    private boolean walkDirectory(
            Path directory,
            int depth,
            int maximumDepth,
            int maximumEntries,
            List<Path> entries) throws IOException {
        if (depth >= maximumDepth || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        int remaining = maximumEntries - entries.size();
        List<Path> children = new ArrayList<>(Math.min(remaining + 1, 1_024));
        boolean siblingTruncated = false;
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                if (children.size() > remaining) {
                    siblingTruncated = true;
                    break;
                }
                children.add(child);
            }
        }
        children.sort(Comparator.comparing(WorkspaceBoundary::fileName));
        for (Path child : children) {
            if (entries.size() == maximumEntries) {
                return true;
            }
            Path admitted = admitExisting(child);
            entries.add(admitted);
            if (Files.isDirectory(admitted, LinkOption.NOFOLLOW_LINKS)
                && walkDirectory(admitted, depth + 1, maximumDepth, maximumEntries, entries)) {
                return true;
            }
        }
        return siblingTruncated;
    }

    /**
     * 目录枚举结果必须是具名子项；显式拒绝文件系统根，避免排序阶段出现空文件名。
     */
    private static String fileName(Path path) {
        Path name = Objects.requireNonNull(path, "path").getFileName();
        if (name == null) {
            throw new IllegalArgumentException("workspace entry name is unavailable");
        }
        return name.toString();
    }

    /**
     * 遍历节点复用相同的 NOFOLLOW 身份与物理包含检查。
     */
    private Path admitExisting(Path path) throws IOException {
        Path lexicalPath = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (!lexicalPath.startsWith(root) && !lexicalPath.startsWith(physicalRoot)) {
            throw new SecurityException("workspace_escape");
        }
        rejectLinksAndReparse(lexicalPath);
        Path physical = lexicalPath.toRealPath();
        requireContained(physical);
        return physical;
    }

    /**
     * 在访问文件系统前拒绝绝对路径、遍历、NUL 与词法逃逸输入。
     */
    private Path lexical(String input) {
        if (input == null || input.isBlank() || input.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("workspace_path_required");
        }
        String normalizedInput = input.replace('\\', '/');
        Path supplied = Path.of(normalizedInput);
        if (supplied.isAbsolute() || normalizedInput.matches("^[A-Za-z]:.*")) {
            throw new SecurityException("workspace_absolute_path_forbidden");
        }
        Path candidate = root.resolve(supplied).normalize();
        if (!candidate.startsWith(root)) {
            throw new SecurityException("workspace_traversal_forbidden");
        }
        return candidate;
    }

    /**
     * 在每个已存在路径分量上拒绝 symlink 与 Windows reparse alias。
     */
    private void rejectLinksAndReparse(Path candidate) throws IOException {
        Path cursor = candidate;
        while (cursor != null && cursor.startsWith(root)) {
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                rejectLinkOrReparse(cursor);
            }
            if (cursor.equals(root)) {
                return;
            }
            cursor = cursor.getParent();
        }
    }

    /**
     * 比较 NOFOLLOW 与跟随后的身份，使 junction 不能成为可写别名。
     */
    private static void rejectLinkOrReparse(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new SecurityException("workspace_link_forbidden");
        }
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Path noFollow = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path followed = path.toRealPath();
        if (attributes.isOther() || !noFollow.equals(followed)) {
            throw new SecurityException("workspace_reparse_forbidden");
        }
    }

    /**
     * 集中物理包含检查，使读取与提交采用完全相同的语义。
     */
    private void requireContained(Path path) {
        if (!path.startsWith(physicalRoot)) {
            throw new SecurityException("workspace_physical_escape");
        }
    }

    /**
     * 父目录 mutation 身份刻意不提供访问器或默认 record 输出，避免诊断与 Tool 结果泄漏物理路径。
     */
    public static final class MutationGuard {
        private final Path parent;
        private final Path noFollowRealPath;
        private final Object fileKey;

        /** 只有 WorkspaceBoundary 能创建已完成 containment 检查的 guard。 */
        private MutationGuard(Path parent, Path noFollowRealPath, Object fileKey) {
            this.parent = parent;
            this.noFollowRealPath = noFollowRealPath;
            this.fileKey = fileKey;
        }

        /** 默认字符串只报告 opaque 类型，禁止把父目录和平台 fileKey 写入日志。 */
        @Override
        public String toString() {
            return "MutationGuard[opaque]";
        }
    }

    /**
     * 有界遍历的不可变结果，所有路径都至少完成过一次物理准入。
     */
    public record WalkResult(List<Path> entries, boolean truncated) {
        /**
         * 复制集合，防止调用方把未检查路径注入后续读取循环。
         */
        public WalkResult {
            entries = List.copyOf(entries);
        }
    }
}
