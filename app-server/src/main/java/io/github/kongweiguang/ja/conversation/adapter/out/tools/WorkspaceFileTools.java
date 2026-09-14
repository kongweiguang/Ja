// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjectBuilder;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.workspace.adapter.out.filesystem.WorkspaceBoundary;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Workspace 只读发现 Tool 的共同实现；所有候选路径先经过 WorkspaceBoundary 再进入后续 IO。
 */
final class WorkspaceFileTools {
    static final int MAX_SCAN_ENTRIES = 20_000;
    static final int MAX_SCAN_DEPTH = 32;
    static final int MAX_FILES = 10_000;
    static final long MAX_BYTES = 16L * 1024 * 1024;
    static final int MAX_OUTPUT_CHARACTERS = 64_000;
    static final int MAX_RESULT_BODY_CHARACTERS = MAX_OUTPUT_CHARACTERS - 512;
    static final int MAX_QUERY_LENGTH = 512;
    static final int MAX_PATTERN_LENGTH = 256;
    static final int MAX_PATH_LENGTH = 8_192;
    static final int MAX_SNIPPET_CHARACTERS = 400;
    static final int DEFAULT_MAX_RESULTS = 50;
    static final int MAX_RESULTS = 200;
    static final int DEFAULT_MAX_FIND_RESULTS = 200;
    static final int MAX_FIND_RESULTS = 2_000;
    static final int DEFAULT_MAX_ENTRIES = 200;
    static final int MAX_ENTRIES = 1_000;

    /** 静态 Tool 工厂不持有 Workspace 状态，避免把一次调用的预算泄漏到下一次 Turn。 */
    private WorkspaceFileTools() {
    }

    /** 创建字面量内容搜索 Tool；Workspace 根由调用方在工厂装配时固定。 */
    static AgentTool grep(Path workspaceRoot) {
        return new GrepTool(workspaceRoot);
    }

    /** 创建文件名 Glob 搜索 Tool；它只返回路径摘要，不物化文件正文。 */
    static AgentTool find(Path workspaceRoot) {
        return new FindTool(workspaceRoot);
    }

    /** 创建单层目录枚举 Tool；递归发现能力必须继续使用 find。 */
    static AgentTool ls(Path workspaceRoot) {
        return new LsTool(workspaceRoot);
    }

    /** 仅为发现 Tool 解析受 WorkspaceBoundary 保护的目录，不接受绝对路径或链接别名。 */
    private static Path directory(WorkspaceBoundary boundary, String rawPath) throws IOException {
        Path path = boundary.existing(rawPath == null || rawPath.isBlank() ? "." : rawPath);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw ToolSupport.failure(ToolSupport.Failure.PATH_NOT_DIRECTORY);
        }
        return path;
    }

    /** 把模型提供的 Glob 编译为当前 Workspace 文件系统匹配器，并将非法模式映射到具体字段。 */
    private static PathMatcher matcher(Path workspaceRoot, String field, String pattern) {
        try {
            return workspaceRoot.getFileSystem().getPathMatcher("glob:" + pattern);
        } catch (IllegalArgumentException invalidPattern) {
            throw ToolSupport.argument(field, "must be a valid glob pattern");
        }
    }

    /** 逐项执行取消、Deadline 和目录项上限检查，未通过时只返回截断事实。 */
    private static boolean allowNext(ScanBudget budget) {
        budget.token().throwIfCancellationRequested();
        if (!Instant.now().isBefore(budget.deadline())) {
            budget.mark("deadline");
            return false;
        }
        return true;
    }

    /** 在有限深度和项数内遍历目录，同时对每个子项重新执行物理 containment 检查。 */
    private static Walk collect(WorkspaceBoundary boundary, Path directory, ScanBudget budget)
            throws IOException {
        List<Path> entries = new ArrayList<>();
        boolean truncated = collectDirectory(boundary, directory, 0, budget, entries);
        entries.sort(Comparator.comparing(WorkspaceFileTools::stablePath));
        return new Walk(entries, truncated || budget.termination() != null);
    }

    /** 递归实现只使用 bounded DirectoryStream，避免先构造无界全树快照再进行过滤。 */
    private static boolean collectDirectory(WorkspaceBoundary boundary, Path directory, int depth,
                                             ScanBudget budget, List<Path> entries) throws IOException {
        if (!allowNext(budget)) return true;
        if (depth >= MAX_SCAN_DEPTH) {
            budget.mark("depth_limit");
            return true;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                if (!allowNext(budget)) return true;
                if (!budget.reserveEntry()) return true;
                Path admitted;
                try {
                    admitted = boundary.revalidateExisting(child);
                } catch (IOException | SecurityException skippedEntry) {
                    // 扫描中的无关链接或瞬时消失项不应阻断同一 Workspace 的其它结果。
                    budget.markSkipped();
                    continue;
                }
                entries.add(admitted);
                if (Files.isDirectory(admitted, LinkOption.NOFOLLOW_LINKS)
                        && collectDirectory(boundary, admitted, depth + 1, budget, entries)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 返回不受本地路径分隔符影响的排序键，确保同一 Workspace 的结果稳定。 */
    private static String stablePath(Path path) {
        return path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    /** 限制单条结果和总输出，截断时不让 ToolResult 超过模型上下文预算。 */
    private static boolean appendLine(StringBuilder output, String line) {
        int separator = output.length() == 0 ? 0 : 1;
        int available = MAX_RESULT_BODY_CHARACTERS - output.length() - separator;
        if (available <= 0) return false;
        if (line.length() <= available) {
            if (separator != 0) output.append('\n');
            output.append(line);
            return true;
        }
        if (separator != 0) output.append('\n');
        output.append(line, 0, safePrefixLength(line, available));
        return false;
    }

    /** 按 Unicode code point 截取输出前缀，避免预算边界切断代理项形成无效文本。 */
    private static int safePrefixLength(String value, int maximumChars) {
        int end = Math.min(value.length(), maximumChars);
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return end;
    }

    /** 生成发现 Tool 统一使用的有界统计元数据，不把物理路径或文件正文带入结构化结果。 */
    private static JsonObject metadata(ScanBudget budget, int resultCount, boolean truncated) {
        JsonObjectBuilder builder = JsonObjects.builder()
                .putNumber("resultCount", resultCount)
                .putNumber("scannedEntries", budget.scannedEntries())
                .putNumber("skippedEntries", budget.skippedEntries())
                .putNumber("scannedFiles", budget.scannedFiles())
                .putNumber("scannedBytes", budget.scannedBytes())
                .putBoolean("truncated", truncated);
        if (budget.termination() != null) builder.putText("termination", budget.termination());
        return builder.build();
    }

    /** 从相对路径和文件名两种常用写法匹配 find 的 Glob，避免模型必须猜平台分隔符。 */
    private static boolean matches(PathMatcher matcher, Path root, Path path,
                                   WorkspaceBoundary boundary) throws IOException {
        String relative = boundary.relative(path);
        return matcher.matches(path.getFileName()) || matcher.matches(root.getFileSystem().getPath(relative));
    }

    /** 搜索 Tool 的稳定有界扫描状态；取消由 token 抛出，Deadline 与资源上限转为截断原因。 */
    private static final class ScanBudget {
        private final CancellationToken token;
        private final Instant deadline;
        private final int maximumEntries;
        private final int maximumFiles;
        private final long maximumBytes;
        private int scannedEntries;
        private int scannedFiles;
        private long scannedBytes;
        private int skippedEntries;
        private String termination;

        /** 冻结一次调用的资源上限，防止递归过程动态扩大预算。 */
        private ScanBudget(CancellationToken token, Instant deadline, int maximumEntries,
                           int maximumFiles, long maximumBytes) {
            this.token = Objects.requireNonNull(token, "token");
            this.deadline = Objects.requireNonNull(deadline, "deadline");
            this.maximumEntries = maximumEntries;
            this.maximumFiles = maximumFiles;
            this.maximumBytes = maximumBytes;
        }

        /** 返回取消令牌供每个目录项和正文 chunk 复用。 */
        private CancellationToken token() {
            return token;
        }

        /** 返回冻结 Deadline，避免调用方传入可变时钟或延长当前操作。 */
        private Instant deadline() {
            return deadline;
        }

        /** 预留一个目录项位置；超过硬上限后不再读取后续项。 */
        private boolean reserveEntry() {
            if (scannedEntries >= maximumEntries) {
                mark("entry_limit");
                return false;
            }
            scannedEntries++;
            return true;
        }

        /** 记录候选普通文件的大小并返回不可扩大的正文读取预约。 */
        private FileReservation accountFile(Path path) throws IOException {
            if (scannedFiles >= maximumFiles) {
                mark("file_limit");
                return null;
            }
            long size = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).size();
            if (size > maximumBytes || scannedBytes > maximumBytes - size) {
                mark("byte_limit");
                return null;
            }
            scannedFiles++;
            scannedBytes += size;
            return new FileReservation(size);
        }

        /** 记录不读取正文的文件发现，保持 find/ls 的 scannedBytes 为零并仍限制文件计数。 */
        private boolean accountMetadataFile() {
            if (scannedFiles >= maximumFiles) {
                mark("file_limit");
                return false;
            }
            scannedFiles++;
            return true;
        }

        /** 记录被安全跳过的链接或竞态项，帮助模型区分空结果和不完整发现。 */
        private void markSkipped() {
            skippedEntries++;
        }

        /** 记录正文无法安全读取，并将扫描标记为不完整而非“无匹配”。 */
        private void markSkipped(String reason) {
            skippedEntries++;
            mark(reason);
        }

        /** 保存第一个终止原因，避免后续条件覆盖最先触发的资源边界。 */
        private void mark(String reason) {
            if (termination == null) termination = reason;
        }

        /** 返回已枚举目录项数，用于结构化预算诊断。 */
        private int scannedEntries() {
            return scannedEntries;
        }

        /** 返回已纳入正文扫描的文件数。 */
        private int scannedFiles() {
            return scannedFiles;
        }

        /** 返回遍历中被跳过的链接/竞态项数量，不暴露具体路径。 */
        private int skippedEntries() {
            return skippedEntries;
        }

        /** 返回已纳入正文扫描的字节数。 */
        private long scannedBytes() {
            return scannedBytes;
        }

        /** 返回首个有界终止原因；null 表示扫描自然完成。 */
        private String termination() {
            return termination;
        }
    }

    /** 冻结 stat 时的文件大小，防止正文读取在同一次扫描中取得更大预算。 */
    private record FileReservation(long maxBytes) {
    }

    /** 递归收集的路径快照仅在固定上限内存在，供各 Tool 做不同的只读投影。 */
    private record Walk(List<Path> entries, boolean truncated) {
        /** 冻结遍历结果，防止投影阶段修改已完成物理准入的路径集合。 */
        private Walk {
            entries = List.copyOf(entries);
        }
    }

    /** grep 只做字面量匹配；正则表达式需求应由 shell 或外部 MCP 明确承担。 */
    private static final class GrepTool extends ToolSupport {
        private final Path workspaceRoot;

        /** 冻结 grep 的字段长度和结果上限，使 Provider Schema 与执行端共享同一边界。 */
        private GrepTool(Path workspaceRoot) {
            super(new ToolSpec("grep", "Search literal text in bounded UTF-8 workspace files",
                    objectSchema(Map.of(
                            "query", requiredStringProperty(
                                    "Non-empty literal text; use find for file-name lookup.", MAX_QUERY_LENGTH),
                            "filePattern", optionalStringProperty(
                                    "Optional glob matched against file names; defaults to *.", MAX_PATTERN_LENGTH),
                            "path", optionalStringProperty(
                                    "Optional workspace-relative directory; defaults to .", MAX_PATH_LENGTH),
                            "maxResults", integerProperty(
                                    "Maximum matching lines; defaults to 50.", 1, MAX_RESULTS)),
                            List.of("query"))));
            this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        }

        /** grep 不写工作区或外部系统。 */
        @Override
        public ToolSideEffect sideEffect() {
            return ToolSideEffect.READ_ONLY;
        }

        /** grep 只产生模型可见文本，不产生 Workspace ChangeSet。 */
        @Override
        public WorkspaceMutationMode workspaceMutationMode() {
            return WorkspaceMutationMode.NONE;
        }

        /** 在物理工作区内按稳定顺序执行字面量搜索，并报告每个硬预算的截断原因。 */
        @Override
        ToolResult executeChecked(Invocation invocation, ExecutionContext context, CancellationToken token)
                throws IOException {
            String query = requiredString(invocation, "query", MAX_QUERY_LENGTH,
                    "must be a non-empty literal query; use find for file-name lookup");
            String rawPattern = optionalString(invocation, "filePattern", MAX_PATTERN_LENGTH);
            String filePattern = rawPattern == null || rawPattern.isBlank() ? "*" : rawPattern;
            String rawPath = optionalString(invocation, "path", MAX_PATH_LENGTH);
            int maxResults = integer(invocation, "maxResults", DEFAULT_MAX_RESULTS, 1, MAX_RESULTS);
            WorkspaceBoundary boundary = new WorkspaceBoundary(workspaceRoot);
            Path start = directory(boundary, rawPath);
            PathMatcher matcher = matcher(workspaceRoot, "filePattern", filePattern);
            ScanBudget budget = new ScanBudget(token, context.deadline(), MAX_SCAN_ENTRIES,
                    MAX_FILES, MAX_BYTES);
            if (!allowNext(budget)) return successful(budget, 0, true, "");
            Walk walked = collect(boundary, start, budget);
            StringBuilder output = new StringBuilder();
            int resultCount = 0;
            boolean truncated = walked.truncated();
            boolean projectionStopped = false;
            for (Path path : walked.entries()) {
                if (!allowNext(budget)) {
                    truncated = true;
                    projectionStopped = true;
                    break;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || !matcher.matches(path.getFileName())) continue;
                FileReservation reservation = budget.accountFile(path);
                if (reservation == null) {
                    truncated = true;
                    if ("file_limit".equals(budget.termination())
                            && budget.scannedFiles() >= MAX_FILES) break;
                    continue;
                }
                if (reservation.maxBytes() == 0) continue;
                boundary.revalidateExisting(path);
                String content;
                try {
                    content = ToolSupport.readUtf8File(path, token, context.deadline(), reservation.maxBytes(),
                            (int) (MAX_BYTES / 4));
                } catch (ToolSupport.DeadlineExceededException deadline) {
                    budget.mark("deadline");
                    truncated = true;
                    projectionStopped = true;
                    break;
                } catch (ToolSupport.ToolFailure ignoredUnreadable) {
                    budget.markSkipped("unreadable_file");
                    truncated = true;
                    continue;
                }
                String[] lines = content.split("\\R", -1);
                for (int index = 0; index < lines.length; index++) {
                    if (!allowNext(budget)) {
                        truncated = true;
                        projectionStopped = true;
                        break;
                    }
                    int match = lines[index].indexOf(query);
                    if (match < 0) continue;
                    String snippet = boundedSnippet(lines[index], match, query.length());
                    String line = boundary.relative(path) + ":" + (index + 1) + ": " + snippet;
                    if (!appendLine(output, line)) {
                        budget.mark("output_limit");
                        truncated = true;
                        projectionStopped = true;
                        break;
                    }
                    resultCount++;
                    if (resultCount >= maxResults) {
                        budget.mark("result_limit");
                        truncated = true;
                        projectionStopped = true;
                        break;
                    }
                }
                if (projectionStopped) break;
            }
            return successful(budget, resultCount, truncated, output.toString());
        }

        /** 按匹配点保留有限上下文，避免长单行占满结果预算。 */
        private static String boundedSnippet(String line, int match, int queryLength) {
            if (line.length() <= MAX_SNIPPET_CHARACTERS) return line;
            int start = Math.max(0, match - 160);
            int end = Math.min(line.length(), match + queryLength + 160);
            end = Math.min(end, start + MAX_SNIPPET_CHARACTERS);
            if (start > 0 && start < line.length() && Character.isLowSurrogate(line.charAt(start))
                    && Character.isHighSurrogate(line.charAt(start - 1))) start++;
            if (end > 0 && end < line.length() && Character.isHighSurrogate(line.charAt(end - 1))
                    && Character.isLowSurrogate(line.charAt(end))) end--;
            return line.substring(start, end);
        }
    }

    /** find 只返回正则无关的文件名/相对路径命中，不读取正文，适合定位待 read 的文件。 */
    private static final class FindTool extends ToolSupport {
        private final Path workspaceRoot;

        /** 冻结 find 的 Glob、目录、文件数和输出上限。 */
        private FindTool(Path workspaceRoot) {
            super(new ToolSpec("find", "Find workspace files by a bounded name glob without reading contents",
                    objectSchema(Map.of(
                            "pattern", requiredStringProperty(
                                    "Non-empty glob matched against file names or relative paths.", MAX_PATTERN_LENGTH),
                            "path", optionalStringProperty(
                                    "Optional workspace-relative directory; defaults to .", MAX_PATH_LENGTH),
                            "maxResults", integerProperty(
                                    "Maximum matching files; defaults to 200.", 1, MAX_FIND_RESULTS)),
                            List.of("pattern"))));
            this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        }

        /** find 不改变工作区或外部系统。 */
        @Override
        public ToolSideEffect sideEffect() {
            return ToolSideEffect.READ_ONLY;
        }

        /** find 只投影路径事实，不产生 Workspace ChangeSet。 */
        @Override
        public WorkspaceMutationMode workspaceMutationMode() {
            return WorkspaceMutationMode.NONE;
        }

        /** 在固定遍历预算内按 Glob 返回文件路径，绝不读取文件正文。 */
        @Override
        ToolResult executeChecked(Invocation invocation, ExecutionContext context, CancellationToken token)
                throws IOException {
            String pattern = requiredString(invocation, "pattern", MAX_PATTERN_LENGTH,
                    "must be a non-empty glob pattern");
            String rawPath = optionalString(invocation, "path", MAX_PATH_LENGTH);
            int maxResults = integer(invocation, "maxResults", DEFAULT_MAX_FIND_RESULTS, 1, MAX_FIND_RESULTS);
            WorkspaceBoundary boundary = new WorkspaceBoundary(workspaceRoot);
            Path start = directory(boundary, rawPath);
            PathMatcher matcher = matcher(workspaceRoot, "pattern", pattern);
            ScanBudget budget = new ScanBudget(token, context.deadline(), MAX_SCAN_ENTRIES,
                    MAX_FILES, MAX_BYTES);
            if (!allowNext(budget)) return successful(budget, 0, true, "");
            Walk walked = collect(boundary, start, budget);
            StringBuilder output = new StringBuilder();
            int resultCount = 0;
            boolean truncated = walked.truncated();
            for (Path path : walked.entries()) {
                if (!allowNext(budget)) {
                    truncated = true;
                    break;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!budget.accountMetadataFile()) {
                    truncated = true;
                    if ("file_limit".equals(budget.termination())
                            && budget.scannedFiles() >= MAX_FILES) break;
                    continue;
                }
                if (!matches(matcher, workspaceRoot, path, boundary)) continue;
                String line = boundary.relative(path);
                if (!appendLine(output, line)) {
                    budget.mark("output_limit");
                    truncated = true;
                    break;
                }
                resultCount++;
                if (resultCount >= maxResults) {
                    budget.mark("result_limit");
                    truncated = true;
                    break;
                }
            }
            return successful(budget, resultCount, truncated, output.toString());
        }
    }

    /** ls 只枚举指定目录的一层，并返回名称/类型，不递归也不读取文件正文。 */
    private static final class LsTool extends ToolSupport {
        private final Path workspaceRoot;

        /** 冻结 ls 的单层、目录项、输出与路径长度上限。 */
        private LsTool(Path workspaceRoot) {
            super(new ToolSpec("ls", "List one bounded workspace directory level",
                    objectSchema(Map.of(
                            "path", optionalStringProperty(
                                    "Optional workspace-relative directory; defaults to .", MAX_PATH_LENGTH),
                            "maxEntries", integerProperty(
                                    "Maximum entries; defaults to 200.", 1, MAX_ENTRIES)),
                            List.of())));
            this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        }

        /** ls 不改变工作区或外部系统。 */
        @Override
        public ToolSideEffect sideEffect() {
            return ToolSideEffect.READ_ONLY;
        }

        /** ls 只投影目录项，不产生 Workspace ChangeSet。 */
        @Override
        public WorkspaceMutationMode workspaceMutationMode() {
            return WorkspaceMutationMode.NONE;
        }

        /** 只读取一层目录并在 maxEntries+1 项处停止，避免全目录列表无界进入内存。 */
        @Override
        ToolResult executeChecked(Invocation invocation, ExecutionContext context, CancellationToken token)
                throws IOException {
            String rawPath = optionalString(invocation, "path", MAX_PATH_LENGTH);
            int maxEntries = integer(invocation, "maxEntries", DEFAULT_MAX_ENTRIES, 1, MAX_ENTRIES);
            WorkspaceBoundary boundary = new WorkspaceBoundary(workspaceRoot);
            Path start = directory(boundary, rawPath);
            ScanBudget budget = new ScanBudget(token, context.deadline(), maxEntries + 1,
                    MAX_FILES, MAX_BYTES);
            if (!allowNext(budget)) return successful(budget, 0, true, "");
            List<Path> entries = new ArrayList<>();
            boolean truncated = false;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(start)) {
                for (Path child : stream) {
                    if (!allowNext(budget)) {
                        truncated = true;
                        break;
                    }
                    if (!budget.reserveEntry()) {
                        truncated = true;
                        break;
                    }
                    Path admitted;
                    try {
                        admitted = boundary.revalidateExisting(child);
                    } catch (IOException | SecurityException skippedEntry) {
                        // ls 只跳过无关链接/竞态项，显式 path 的链接仍在 directory() 处失败关闭。
                        budget.markSkipped();
                        continue;
                    }
                    if (Files.isRegularFile(admitted, LinkOption.NOFOLLOW_LINKS)
                            && !budget.accountMetadataFile()) {
                        truncated = true;
                        break;
                    }
                    entries.add(admitted);
                    if (entries.size() > maxEntries) {
                        budget.mark("entry_limit");
                        truncated = true;
                        break;
                    }
                }
            }
            entries.sort(Comparator.comparing(WorkspaceFileTools::stablePath));
            if (entries.size() > maxEntries) entries = new ArrayList<>(entries.subList(0, maxEntries));
            StringBuilder output = new StringBuilder();
            int resultCount = 0;
            for (Path path : entries) {
                String type = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? "directory" : "file";
                if (!appendLine(output, boundary.relative(path) + "\t" + type)) {
                    budget.mark("output_limit");
                    truncated = true;
                    break;
                }
                resultCount++;
            }
            return successful(budget, resultCount, truncated || budget.termination() != null,
                    output.toString());
        }
    }

    /** 创建成功的纯文本结果并保留完整扫描统计，供模型区分空目录与预算截断。 */
    private static AgentTool.ToolResult successful(ScanBudget budget, int resultCount, boolean truncated,
                                                   String content) {
        boolean partial = truncated || budget.skippedEntries() > 0;
        String summary = "[workspace discovery] results=" + resultCount
                + ", scannedEntries=" + budget.scannedEntries()
                + ", skippedEntries=" + budget.skippedEntries()
                + ", scannedFiles=" + budget.scannedFiles()
                + ", scannedBytes=" + budget.scannedBytes()
                + ", truncated=" + partial
                + (budget.termination() == null ? "" : ", termination=" + budget.termination())
                + (partial
                ? ". Results are partial; some entries were skipped or the scan reached a limit."
                : resultCount == 0 ? ". No matching entries were found."
                : ". Results are complete.");
        String visibleContent = content.isBlank() ? summary : content + "\n\n" + summary;
        return new AgentTool.ToolResult(ToolOutcome.SUCCEEDED, visibleContent,
                Optional.of(metadata(budget, resultCount, partial)), null);
    }
}
