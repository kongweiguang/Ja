// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.adapter.out.filesystem;

import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.search.NativeSearchProcess;
import io.github.kongweiguang.ja.foundation.search.NativeSearchToolResolver;
import io.github.kongweiguang.ja.workspace.domain.WorkspaceEntryKind;
import io.github.kongweiguang.ja.workspace.domain.WorkspacePathFailure;
import io.github.kongweiguang.ja.workspace.port.out.WorkspacePathPort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 使用 fd 搜索 Workspace 路径，只对有限候选复核物理边界；引用消费仍通过 NIO 重新校验。
 */
public final class NioWorkspacePathAdapter implements WorkspacePathPort {
    static final int MAXIMUM_CANDIDATES = 100;
    static final Duration DEADLINE = Duration.ofMillis(750);
    private static final int MAXIMUM_STDOUT_BYTES = 2 * 1024 * 1024;
    private static final int MAXIMUM_STDERR_BYTES = 64 * 1024;
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", ".hg", ".svn", ".cache", ".next", ".nuxt", ".skills-cache",
            "node_modules", "target", "coverage", "dist", "build", ".codex-target", ".tmp");
    private static final Comparator<Candidate> BEST_FIRST = Comparator
            .comparingInt(Candidate::rank)
            .thenComparingInt(Candidate::depth)
            .thenComparing(Candidate::foldedPath)
            .thenComparing(Candidate::relativePath);
    private final NativeSearchToolResolver searchTools;
    private final SearchProcessRunner processRunner;
    private volatile Path fdExecutable;

    /** 复用发行包旁的 fd 与既有管道清理器，缺工具时明确失败，不回退 Java 全树扫描。 */
    public NioWorkspacePathAdapter() {
        this(NativeSearchToolResolver.system(), NativeSearchProcess::run);
    }

    /** 注入 resolver 与 runner，使参数、预算和取消行为可在无真实子进程时确定性验证。 */
    NioWorkspacePathAdapter(NativeSearchToolResolver searchTools, SearchProcessRunner processRunner) {
        this.searchTools = Objects.requireNonNull(searchTools, "searchTools");
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner");
    }

    /**
     * fd 在原生层应用 query 与 ignore 规则并限制候选数量；Java 只复核候选，避免每次按键重扫全树。
     */
    @Override
    public SearchOutcome search(Path workspaceRoot, String query, int limit,
                                CancellationToken cancellationToken) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        if (query.length() > 1_024 || query.indexOf('\0') >= 0 || limit < 1 || limit > 50) {
            throw new IllegalArgumentException("invalid workspace path search request");
        }
        if (cancellationToken.isCancellationRequested()) {
            return new SearchOutcome(List.of(), true, 0);
        }
        try {
            WorkspaceBoundary boundary = boundary(workspaceRoot);
            return searchCandidates(boundary, normalizeQuery(query), limit, cancellationToken);
        } catch (WorkspacePathFailure failure) {
            throw failure;
        } catch (CancellationException cancelled) {
            return new SearchOutcome(List.of(), true, 0);
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
            return validate(boundary(workspaceRoot), relativePath, kind);
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

    /** 搜索有限候选集后才执行昂贵的物理包含检查，并保留确定性 top-k 排序。 */
    private SearchOutcome searchCandidates(WorkspaceBoundary boundary, String query, int limit,
                                           CancellationToken cancellationToken) throws IOException {
        int candidateLimit = Math.min(MAXIMUM_CANDIDATES, Math.max(limit + 1, limit * 2));
        Path fd = fdExecutable();
        List<String> arguments = fdArguments(boundary.root(), query, candidateLimit);
        List<Candidate> candidates = new ArrayList<>(candidateLimit);
        AtomicInteger outputLines = new AtomicInteger();
        AtomicBoolean invalidCandidate = new AtomicBoolean();
        Instant deadline = Instant.now().plus(DEADLINE);
        NativeSearchProcess.Result process = processRunner.run(
                fd, arguments, boundary.root(), cancellationToken, deadline,
                MAXIMUM_STDOUT_BYTES, MAXIMUM_STDERR_BYTES, searchTools.environment(), line -> {
                    if (cancellationToken.isCancellationRequested()) return false;
                    outputLines.incrementAndGet();
                    Candidate candidate = candidate(boundary, line, query);
                    if (candidate == null) invalidCandidate.set(true);
                    else candidates.add(candidate);
                    return true;
                });
        boundary.revalidateExisting(boundary.root());
        if (process.cancelled() || cancellationToken.isCancellationRequested()) {
            return new SearchOutcome(List.of(), true, outputLines.get());
        }
        if (!process.deadlineExceeded() && !process.outputTruncated()
                && process.exitCode() != 0 && process.exitCode() != 1) {
            throw new IOException("fd_workspace_path_search_failed");
        }
        List<PathEntry> entries = candidates.stream().sorted(BEST_FIRST)
                .limit(limit)
                .map(candidate -> new PathEntry(candidate.relativePath(), candidate.kind()))
                .toList();
        boolean truncated = process.outputTruncated() || process.stoppedByConsumer()
                || process.deadlineExceeded() || outputLines.get() >= candidateLimit
                || candidates.size() > limit || invalidCandidate.get();
        return new SearchOutcome(entries, truncated, outputLines.get());
    }

    /**
     * 解析 fd 的相对路径并复核物理 containment；不可信候选若是 symlink 或发生竞争，
     * 只丢弃该项并由调用方标记结果截断，直接引用校验仍会向用户返回安全拒绝。
     */
    private Candidate candidate(WorkspaceBoundary boundary, String output, String query) {
        if (output == null || output.isEmpty()) return null;
        String value = output.endsWith("\r") ? output.substring(0, output.length() - 1) : output;
        boolean directory = value.endsWith("/");
        if (directory) value = value.substring(0, value.length() - 1);
        while (value.startsWith("./")) value = value.substring(2);
        String relative = value;
        if (relative.isBlank() || relative.length() > 4_096 || relative.indexOf('\0') >= 0) return null;
        try {
            Path parsed = Path.of(relative);
            if (parsed.isAbsolute() || parsed.normalize().startsWith("..")) return null;
            WorkspaceEntryKind expected = directory ? WorkspaceEntryKind.DIRECTORY : WorkspaceEntryKind.FILE;
            ValidatedPath validated = validate(boundary, relative, expected);
            return new Candidate(validated.relativePath(), validated.kind(), rank(relative, query),
                    depth(relative));
        } catch (WorkspacePathFailure | InvalidPathException | IOException | SecurityException invalid) {
            return null;
        }
    }

    /** 构造不经 shell 的 fd 参数，保留隐藏文件、ignore 语义和既有生成目录排除规则。 */
    private static List<String> fdArguments(Path root, String query, int limit) {
        List<String> arguments = new ArrayList<>();
        arguments.add("--color=never");
        arguments.add("--hidden");
        arguments.add("--type");
        arguments.add("f");
        arguments.add("--type");
        arguments.add("d");
        arguments.add("--path-separator");
        arguments.add("/");
        arguments.add("--max-results");
        arguments.add(Integer.toString(limit));
        addExclusions(arguments);
        if (!insideGitRepository(root)) arguments.add("--no-require-git");
        if (!query.isEmpty()) {
            arguments.add("--regex");
            arguments.add("--ignore-case");
            arguments.add("--full-path");
        }
        arguments.add("--");
        if (!query.isEmpty()) arguments.add(subsequenceRegex(query));
        arguments.add(".");
        return List.copyOf(arguments);
    }

    /** 将已确认的默认排除名交给 fd glob 引擎，不实现第二套目录匹配规则。 */
    private static void addExclusions(List<String> arguments) {
        for (String name : IGNORED_DIRECTORIES) {
            addExclusion(arguments, name);
            addExclusion(arguments, "**/" + name);
            addExclusion(arguments, "**/" + name + "/**");
            addExclusion(arguments, name.toUpperCase(Locale.ROOT));
            addExclusion(arguments, "**/" + name.toUpperCase(Locale.ROOT));
            addExclusion(arguments, "**/" + name.toUpperCase(Locale.ROOT) + "/**");
        }
        addExclusion(arguments, "target-*");
        addExclusion(arguments, "**/target-*");
        addExclusion(arguments, "**/target-*/**");
        addExclusion(arguments, ".tmp-*");
        addExclusion(arguments, "**/.tmp-*");
        addExclusion(arguments, "**/.tmp-*/**");
    }

    /** 每条排除模式均作为独立 argv 传递，避免空格或元字符改变 shell 语义。 */
    private static void addExclusion(List<String> arguments, String pattern) {
        arguments.add("--exclude");
        arguments.add(pattern);
    }

    /** 和 Pi 及 find Tool 一致，只在搜索根及其祖先都没有 .git 时放宽 ignore 上下文。 */
    private static boolean insideGitRepository(Path root) {
        for (Path current = root; current != null; current = current.getParent()) {
            if (Files.exists(current.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) return true;
        }
        return false;
    }

    /** 与 Pi autocomplete 一样只在首次查询时解析 fd，后续按键复用同一已验证二进制。 */
    private Path fdExecutable() throws IOException {
        Path resolved = fdExecutable;
        if (resolved != null) return resolved;
        synchronized (this) {
            if (fdExecutable == null) fdExecutable = searchTools.resolve("fd");
            return fdExecutable;
        }
    }

    /** 将每个查询字符转为有序子序列表达式，避免正则元字符改变路径检索含义。 */
    private static String subsequenceRegex(String query) {
        StringBuilder expression = new StringBuilder(query.length() * 3);
        for (int offset = 0; offset < query.length();) {
            int codePoint = query.codePointAt(offset);
            if (offset > 0) expression.append(".*");
            if ("\\.^$|?*+()[]{}".indexOf(codePoint) >= 0) expression.append('\\');
            expression.appendCodePoint(codePoint);
            offset += Character.charCount(codePoint);
        }
        return expression.toString();
    }

    /** 按文件名语言归一化 Windows 分隔符，大小写判断交给 fd 的 Unicode matcher。 */
    private static String normalizeQuery(String query) {
        return query.replace('\\', '/');
    }

    /** 复用一次根准入结果，使最多 400 个候选无需重复解析 Workspace root。 */
    private static ValidatedPath validate(WorkspaceBoundary boundary, String relativePath,
                                          WorkspaceEntryKind expectedKind) throws IOException {
        Path admitted = boundary.existing(relativePath);
        WorkspaceEntryKind actual = kind(admitted);
        if (actual != expectedKind) {
            throw new WorkspacePathFailure(WorkspacePathFailure.Code.TYPE_MISMATCH,
                    "workspace reference type changed");
        }
        return new ValidatedPath(boundary.relative(admitted), actual);
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

    /** 文件系统安全入口只返回普通文件或目录；其它 special entry 继续失败关闭。 */
    private static WorkspaceEntryKind kind(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isRegularFile()) return WorkspaceEntryKind.FILE;
        if (attributes.isDirectory()) return WorkspaceEntryKind.DIRECTORY;
        throw new WorkspacePathFailure(WorkspacePathFailure.Code.CONFINEMENT,
                "workspace special entry is forbidden");
    }

    /** 路径匹配排名保持既有完整前缀、路径段前缀与模糊子序列的优先级。 */
    private static int rank(String relativePath, String query) {
        if (query.isEmpty()) return 0;
        String folded = relativePath.toLowerCase(Locale.ROOT);
        String foldedQuery = query.toLowerCase(Locale.ROOT);
        if (folded.startsWith(foldedQuery)) return 0;
        for (String segment : folded.split("/")) {
            if (segment.startsWith(foldedQuery)) return 1;
        }
        return 2;
    }

    /** 空查询与模糊查询先展示较浅路径，再以不区分大小写的字典序稳定收口。 */
    private static int depth(String relativePath) {
        int depth = 1;
        for (int index = 0; index < relativePath.length(); index++) {
            if (relativePath.charAt(index) == '/') depth++;
        }
        return depth;
    }

    /** 候选排序键只预计算一次，避免 PriorityQueue 比较重复分配折叠路径。 */
    private record Candidate(String relativePath, WorkspaceEntryKind kind, int rank,
                             int depth, String foldedPath) {
        /** 使用 Windows 产品语义构造大小写不敏感的确定性排序键。 */
        private Candidate(String relativePath, WorkspaceEntryKind kind, int rank, int depth) {
            this(relativePath, kind, rank, depth, relativePath.toLowerCase(Locale.ROOT));
        }
    }

    /** 同一 fd 调用的狭窄注入点，避免测试依赖机器安装的二进制或用户 PATH。 */
    @FunctionalInterface
    interface SearchProcessRunner {
        /** 使用结构化参数执行 bounded fd search，并把每个结果行交给候选复核器。 */
        NativeSearchProcess.Result run(Path executable, List<String> arguments, Path workingDirectory,
                                       CancellationToken token, Instant deadline, int maxStdoutBytes,
                                       int maxStderrBytes, Map<String, String> environment,
                                       NativeSearchProcess.LineConsumer consumer) throws IOException;
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
}
