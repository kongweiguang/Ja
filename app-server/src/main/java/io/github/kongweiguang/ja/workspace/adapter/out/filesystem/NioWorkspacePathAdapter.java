// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.adapter.out.filesystem;

import io.github.kongweiguang.ja.workspace.domain.WorkspaceEntryKind;
import io.github.kongweiguang.ja.workspace.domain.WorkspacePathFailure;
import io.github.kongweiguang.ja.workspace.port.out.WorkspacePathPort;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 使用 Java NIO 在已绑定 Workspace 内搜索路径并重新验证引用，任何操作都不读取文件正文。
 */
public final class NioWorkspacePathAdapter implements WorkspacePathPort {
    static final int MAXIMUM_ENTRIES = 100_000;
    static final int MAXIMUM_DEPTH = 64;
    static final Duration DEADLINE = Duration.ofSeconds(2);
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", ".hg", ".svn", ".cache", ".next", ".nuxt", ".skills-cache",
            "node_modules", "target", "coverage", "dist", "build", ".codex-target", ".tmp");
    private static final Comparator<Candidate> BEST_FIRST = Comparator
            .comparingInt(Candidate::rank)
            .thenComparingInt(Candidate::depth)
            .thenComparing(Candidate::foldedPath)
            .thenComparing(Candidate::relativePath);
    private final LongSupplier nanoTime;

    /** 生产构造器使用单调时钟，使墙钟调整不能延长固定扫描预算。 */
    public NioWorkspacePathAdapter() {
        this(System::nanoTime);
    }

    /** 包级时钟 seam 只用于确定性 deadline 测试，不允许改变生产预算。 */
    NioWorkspacePathAdapter(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * 按扫描数、深度、返回数与 2 秒 deadline 限界；仅读取目录项和 metadata。
     */
    @Override
    public SearchOutcome search(Path workspaceRoot, String query, int limit) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(query, "query");
        if (query.length() > 1_024 || query.indexOf('\0') >= 0 || limit < 1 || limit > 50) {
            throw new IllegalArgumentException("invalid workspace path search request");
        }
        try {
            WorkspaceBoundary boundary = boundary(workspaceRoot);
            return scan(boundary, normalizeQuery(query), limit);
        } catch (WorkspacePathFailure failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw new WorkspacePathFailure(WorkspacePathFailure.Code.INVALID_PATH,
                    "workspace path search request is invalid", failure);
        } catch (SecurityException failure) {
            throw confinement(failure);
        } catch (IOException failure) {
            throw mapIo(failure);
        }
    }

    /**
     * 每次校验都从已打开根重新解析目标，避免排队期间删除、替换或类型变化被忽略。
     */
    @Override
    public ValidatedPath validate(Path workspaceRoot, String relativePath, WorkspaceEntryKind kind) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(kind, "kind");
        try {
            WorkspaceBoundary boundary = boundary(workspaceRoot);
            Path admitted = boundary.existing(relativePath);
            WorkspaceEntryKind actual = kind(admitted);
            if (actual != kind) {
                throw new WorkspacePathFailure(WorkspacePathFailure.Code.TYPE_MISMATCH,
                        "workspace reference type changed");
            }
            return new ValidatedPath(boundary.relative(admitted), actual);
        } catch (WorkspacePathFailure failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw new WorkspacePathFailure(WorkspacePathFailure.Code.INVALID_PATH,
                    "workspace reference path is invalid", failure);
        } catch (SecurityException failure) {
            throw confinement(failure);
        } catch (IOException failure) {
            throw mapIo(failure);
        }
    }

    /**
     * 已打开根若被删除必须失败，不能沿用 WorkspaceBoundary 构造失败的参数错误外观。
     */
    private static WorkspaceBoundary boundary(Path workspaceRoot) {
        if (!Files.isDirectory(workspaceRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspacePathFailure(WorkspacePathFailure.Code.PATH_UNAVAILABLE,
                    "workspace root is unavailable");
        }
        try {
            return new WorkspaceBoundary(workspaceRoot);
        } catch (IllegalArgumentException failure) {
            throw new WorkspacePathFailure(WorkspacePathFailure.Code.CONFINEMENT,
                    "workspace root violates containment", failure);
        }
    }

    /**
     * 广度扫描保证浅路径尽早参与候选，同时 top-k 堆让内存不随命中数增长。
     */
    private SearchOutcome scan(WorkspaceBoundary boundary, String query, int limit) throws IOException {
        long startedAt = nanoTime.getAsLong();
        ArrayDeque<DirectoryNode> pending = new ArrayDeque<>();
        pending.add(new DirectoryNode(boundary.root(), "", 0));
        PriorityQueue<Candidate> best = new PriorityQueue<>(limit, BEST_FIRST.reversed());
        ScanState state = new ScanState();
        while (!pending.isEmpty() && !state.truncated()) {
            if (expired(startedAt)) {
                state.truncate();
                break;
            }
            DirectoryNode directory = pending.removeFirst();
            scanDirectory(boundary, directory, query, limit, best, pending, state, startedAt);
        }
        List<PathEntry> entries = best.stream().sorted(BEST_FIRST)
                .map(candidate -> new PathEntry(candidate.relativePath(), candidate.kind()))
                .toList();
        return new SearchOutcome(entries, state.truncated() || state.matches() > limit,
                state.scannedEntries());
    }

    /**
     * 单目录枚举在每个条目前检查 deadline 和全局条目预算，达到边界后不再继续 IO。
     */
    private void scanDirectory(
            WorkspaceBoundary boundary,
            DirectoryNode directory,
            String query,
            int limit,
            PriorityQueue<Candidate> best,
            ArrayDeque<DirectoryNode> pending,
            ScanState state,
            long startedAt) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory.path())) {
            for (Path child : stream) {
                if (expired(startedAt) || state.scannedEntries() >= MAXIMUM_ENTRIES) {
                    state.truncate();
                    return;
                }
                state.incrementScannedEntries();
                inspectChild(boundary, directory, child, query, limit, best, pending, state);
            }
        }
        boundary.revalidateExisting(directory.path());
    }

    /**
     * 忽略目录在进入递归队列前筛除；其余节点必须完成 NOFOLLOW 与物理包含准入。
     */
    private static void inspectChild(
            WorkspaceBoundary boundary,
            DirectoryNode parent,
            Path child,
            String query,
            int limit,
            PriorityQueue<Candidate> best,
            ArrayDeque<DirectoryNode> pending,
            ScanState state) throws IOException {
        BasicFileAttributes lexical = Files.readAttributes(
                child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        String name = fileName(child);
        if (lexical.isDirectory() && ignoredDirectory(name)) {
            return;
        }
        String relative = join(parent.relativePath(), name);
        Path admitted = boundary.existing(relative);
        WorkspaceEntryKind kind = kind(admitted);
        int childDepth = parent.depth() + 1;
        if (matches(relative, query)) {
            state.incrementMatches();
            retain(best, new Candidate(relative, kind, rank(relative, query), childDepth), limit);
        }
        if (kind == WorkspaceEntryKind.DIRECTORY) {
            if (childDepth >= MAXIMUM_DEPTH) {
                state.truncate();
            } else {
                pending.addLast(new DirectoryNode(admitted, relative, childDepth));
            }
        }
    }

    /** 只保留全局最优的有限候选，结果不依赖文件系统枚举顺序。 */
    private static void retain(PriorityQueue<Candidate> best, Candidate candidate, int limit) {
        if (best.size() < limit) {
            best.add(candidate);
            return;
        }
        Candidate worst = best.peek();
        if (worst != null && BEST_FIRST.compare(candidate, worst) < 0) {
            best.remove();
            best.add(candidate);
        }
    }

    /** 空查询展示浅层字典序建议；非空查询支持路径前缀、段前缀和稳定子序列模糊匹配。 */
    private static boolean matches(String relativePath, String query) {
        if (query.isEmpty()) {
            return true;
        }
        String folded = relativePath.toLowerCase(Locale.ROOT);
        if (folded.startsWith(query)) {
            return true;
        }
        for (String segment : folded.split("/")) {
            if (segment.startsWith(query)) {
                return true;
            }
        }
        return subsequence(folded, query);
    }

    /** 排名严格对应完整路径前缀、路径段前缀、模糊匹配三个产品层级。 */
    private static int rank(String relativePath, String query) {
        if (query.isEmpty()) {
            return 0;
        }
        String folded = relativePath.toLowerCase(Locale.ROOT);
        if (folded.startsWith(query)) {
            return 0;
        }
        for (String segment : folded.split("/")) {
            if (segment.startsWith(query)) {
                return 1;
            }
        }
        return 2;
    }

    /** 子序列匹配避免引入新的模糊搜索依赖，且 CPU 工作严格受条目和查询长度上限约束。 */
    private static boolean subsequence(String candidate, String query) {
        int queryIndex = 0;
        for (int index = 0; index < candidate.length() && queryIndex < query.length(); index++) {
            if (candidate.charAt(index) == query.charAt(queryIndex)) {
                queryIndex++;
            }
        }
        return queryIndex == query.length();
    }

    /** Windows 产品语义固定不区分大小写，并统一斜杠以匹配公开相对路径。 */
    private static String normalizeQuery(String query) {
        return query.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    /** 与现有 Workspace 正文搜索共享可再生目录边界，避免隐藏面板触发重型扫描。 */
    static boolean ignoredDirectory(String name) {
        String folded = name.toLowerCase(Locale.ROOT);
        return IGNORED_DIRECTORIES.contains(folded)
                || folded.startsWith("target-")
                || folded.startsWith(".tmp-");
    }

    /** 公开合同只接受普通文件和目录，设备、socket 或未知 special entry 全部失败关闭。 */
    private static WorkspaceEntryKind kind(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isRegularFile()) {
            return WorkspaceEntryKind.FILE;
        }
        if (attributes.isDirectory()) {
            return WorkspaceEntryKind.DIRECTORY;
        }
        throw new WorkspacePathFailure(WorkspacePathFailure.Code.CONFINEMENT,
                "workspace special entry is forbidden");
    }

    /** 使用 parent 已准入的相对路径构建公开路径，不接触系统分隔符。 */
    private static String join(String parent, String name) {
        return parent.isEmpty() ? name : parent + "/" + name;
    }

    /** 目录项必须有具名末段，文件系统根不可能作为搜索结果返回。 */
    private static String fileName(Path path) {
        Path name = path.getFileName();
        if (name == null) {
            throw new WorkspacePathFailure(WorkspacePathFailure.Code.INVALID_PATH,
                    "workspace entry name is unavailable");
        }
        return name.toString();
    }

    /** 单调时间差避免 nanoTime 环绕或墙钟调整破坏 deadline。 */
    private boolean expired(long startedAt) {
        return nanoTime.getAsLong() - startedAt >= DEADLINE.toNanos();
    }

    /** 链接、reparse 与 containment 异常统一为不重试的安全拒绝。 */
    private static WorkspacePathFailure confinement(RuntimeException cause) {
        return new WorkspacePathFailure(WorkspacePathFailure.Code.CONFINEMENT,
                "workspace path violates containment", cause);
    }

    /** 缺失目标与其它 IO 使用不同稳定类别，便于排队引用进入可恢复状态。 */
    private static WorkspacePathFailure mapIo(IOException cause) {
        WorkspacePathFailure.Code code = cause instanceof NoSuchFileException
                ? WorkspacePathFailure.Code.PATH_UNAVAILABLE
                : WorkspacePathFailure.Code.IO_UNAVAILABLE;
        return new WorkspacePathFailure(code, "workspace path is unavailable", cause);
    }

    /** BFS 节点保留已经物理准入的目录和标准公开相对路径。 */
    private record DirectoryNode(Path path, String relativePath, int depth) {
    }

    /** 排名候选预计算折叠路径，避免 PriorityQueue 比较重复分配。 */
    private record Candidate(String relativePath, WorkspaceEntryKind kind, int rank,
                             int depth, String foldedPath) {
        /** 从标准相对路径构造稳定 Windows 排序键。 */
        private Candidate(String relativePath, WorkspaceEntryKind kind, int rank, int depth) {
            this(relativePath, kind, rank, depth, relativePath.toLowerCase(Locale.ROOT));
        }
    }

    /** 扫描状态集中维护硬预算与真实匹配数，避免多个循环各自推断截断。 */
    private static final class ScanState {
        private int scannedEntries;
        private int matches;
        private boolean truncated;

        /** 返回当前已读取 metadata 的目录项数量。 */
        private int scannedEntries() {
            return scannedEntries;
        }

        /** 每发现一个目录项立即计数，异常也不会重复消耗预算。 */
        private void incrementScannedEntries() {
            scannedEntries++;
        }

        /** 返回所有符合查询的数量，用于判断 top-k 是否省略了命中。 */
        private int matches() {
            return matches;
        }

        /** 匹配计数最多等于扫描上限，不存在整数溢出风险。 */
        private void incrementMatches() {
            matches++;
        }

        /** 标记 deadline、深度或条目预算导致结果非穷尽。 */
        private void truncate() {
            truncated = true;
        }

        /** 返回结果是否因任一预算被提前截断。 */
        private boolean truncated() {
            return truncated;
        }
    }
}
