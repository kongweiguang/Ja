// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonObjectBuilder;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.workspace.adapter.out.filesystem.WorkspaceBoundary;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Workspace 只读发现 Tool 的共同实现；find/grep 使用成熟 native 搜索器，候选路径仍经
 * WorkspaceBoundary 复核后才进入模型可见结果或 Java 文件读取。
 */
final class WorkspaceFileTools {
    static final int MAX_OUTPUT_CHARACTERS = 64_000;
    static final int MAX_RESULT_BODY_CHARACTERS = MAX_OUTPUT_CHARACTERS - 512;
    static final int MAX_QUERY_LENGTH = 4_096;
    static final int MAX_PATTERN_LENGTH = 4_096;
    static final int MAX_PATH_LENGTH = 8_192;
    static final int MAX_SNIPPET_CHARACTERS = 500;
    static final int DEFAULT_RESULT_LIMIT = 100;
    static final int DEFAULT_MAX_ENTRIES = 200;
    static final int MAX_ENTRIES = 1_000;
    private static final int MAX_NATIVE_STDOUT_BYTES = 512 * 1024;
    private static final int MAX_NATIVE_STDERR_BYTES = 32 * 1024;
    private static final ObjectMapper SEARCH_JSON = new ObjectMapper();

    /** 静态 Tool 工厂不持有 Workspace 状态，避免把一次调用的预算泄漏到下一次 Turn。 */
    private WorkspaceFileTools() {
    }

    /** 测试可注入 native executable，生产路径仍只使用 packaged resource 或宿主 PATH。 */
    static AgentTool grep(Path workspaceRoot, NativeSearchToolResolver resolver) {
        return new GrepTool(workspaceRoot, resolver);
    }

    /** 测试可注入 native executable，避免通过改写 PATH 伪造缺失工具或取消行为。 */
    static AgentTool find(Path workspaceRoot, NativeSearchToolResolver resolver) {
        return new FindTool(workspaceRoot, resolver);
    }

    /** 创建单层目录枚举 Tool；递归发现能力继续交给 fd find。 */
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

    /** 逐项执行取消和 Deadline 检查；ls 的单层 DirectoryStream 仍使用同一 Turn 预算。 */
    private static boolean allowNext(ScanBudget budget) {
        budget.token().throwIfCancellationRequested();
        if (!Instant.now().isBefore(budget.deadline())) {
            budget.mark("deadline");
            return false;
        }
        return true;
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

    /** 将 native 输出路径解析到已准入搜索目录；非法路径只形成候选跳过事实。 */
    private static Path nativePath(Path searchRoot, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return null;
        try {
            Path parsed = Path.of(rawPath);
            return (parsed.isAbsolute() ? parsed : searchRoot.resolve(parsed)).normalize();
        } catch (InvalidPathException invalidPath) {
            return null;
        }
    }

    /** 删除 native 输出行尾，保留文件名内部的空白字符。 */
    private static String lineText(String value) {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == '\r' || value.charAt(end - 1) == '\n')) end--;
        return value.substring(0, end);
    }

    /** 判断指定目录及其祖先是否位于 Git 仓库，复用 fd 的 Git ignore 语义。 */
    private static boolean insideGitRepository(Path directory) {
        for (Path current = directory; current != null; current = current.getParent()) {
            if (Files.exists(current.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) return true;
        }
        return false;
    }

    /** 将 pi 的路径 Glob 参数转换为 fd 在 Windows 上的 full-path 匹配形式。 */
    private static List<String> findArguments(String pattern, Path searchRoot, int maxResults) {
        String normalized = pattern.replace('\\', '/');
        List<String> arguments = new ArrayList<>();
        arguments.add("--glob");
        arguments.add("--color=never");
        arguments.add("--hidden");
        arguments.add("--no-follow");
        if (!insideGitRepository(searchRoot)) arguments.add("--no-require-git");
        arguments.add("--max-results");
        arguments.add(Integer.toString(maxResults));
        if (normalized.contains("/")) {
            arguments.add("--full-path");
            if (!normalized.startsWith("/") && !normalized.startsWith("**/") && !normalized.equals("**")) {
                normalized = "**/" + normalized;
            }
            if (isWindows()) normalized = normalized.replace("/", "[/\\\\]");
        }
        arguments.add("--");
        arguments.add(normalized);
        arguments.add(searchRoot.toString());
        return arguments;
    }

    /** 明确报告 fd/rg 缺失；不会退回 Java 递归扫描，避免把安装问题伪装成卡顿。 */
    private static AgentTool.ToolResult unavailable(String toolName) {
        String content = "Tool failed: search_tool_unavailable. The native " + toolName
                + " executable is not installed; install the packaged search tools or make "
                + toolName + " available on PATH.";
        return new AgentTool.ToolResult(ToolOutcome.FAILED, content, Optional.empty(),
                "search_tool_unavailable");
    }

    /** 只识别 fd/rg 的固定 glob 诊断，把 native 语法错误映射为可纠正字段错误而不泄露 stderr。 */
    private static boolean isNativeGlobError(String stderr) {
        String normalized = stderr == null ? "" : stderr.toLowerCase(Locale.ROOT);
        return normalized.contains("error parsing glob")
                || normalized.contains("invalid glob")
                || normalized.contains("regex parse error");
    }

    /** 将 rg 的正则解析失败映射回 query 字段，使模型可修正模式而不是盲目重试整个搜索。 */
    private static boolean isNativeRegexError(String stderr) {
        String normalized = stderr == null ? "" : stderr.toLowerCase(Locale.ROOT);
        return normalized.contains("regex parse error") || normalized.contains("error parsing regex");
    }

    /** native 搜索结果只报告工具能证明的结果、输出上限和候选复核，不伪造目录项扫描统计。 */
    private static AgentTool.ToolResult nativeSuccess(String searchTool, int resultCount,
                                                      boolean truncated, String termination,
                                                      int skippedCandidates, String content) {
        JsonObjectBuilder metadata = JsonObjects.builder()
                .putText("searchTool", searchTool)
                .putNumber("resultCount", resultCount)
                .putNumber("skippedCandidates", skippedCandidates)
                .putBoolean("truncated", truncated);
        if (termination != null) metadata.putText("termination", termination);
        String nextStep = "result_limit".equals(termination)
                ? " Use limit=" + Math.min(Integer.MAX_VALUE, Math.max(2L, (long) resultCount * 2L))
                        + " for more results, or refine the pattern."
                : "";
        String summary = "[workspace discovery] results=" + resultCount
                + ", searchTool=" + searchTool
                + ", skippedCandidates=" + skippedCandidates
                + ", truncated=" + truncated
                + (termination == null ? "" : ", termination=" + termination)
                + (truncated || skippedCandidates > 0
                ? ". Results are partial; native search or candidate validation stopped early." + nextStep
                : resultCount == 0 ? ". No matching entries were found."
                : ". Results are complete. Directory-entry scan counts are not reported for native search.");
        String visibleContent = content.isBlank() ? summary : content + "\n\n" + summary;
        return new AgentTool.ToolResult(ToolOutcome.SUCCEEDED, visibleContent,
                Optional.of(metadata.build()), null);
    }

    /** 将 rg 的 JSONL match record 投影成稳定字段，拒绝把 native JSON 正文直接交给模型。 */
    private static JsonNode parseSearchRecord(String line) throws IOException {
        try {
            JsonNode parsed = SEARCH_JSON.readTree(line);
            if (parsed == null || parsed.isNull()) throw new IOException("empty native search record");
            return parsed;
        } catch (IOException invalidJson) {
            throw new IOException("native_search_output_record_invalid", invalidJson);
        }
    }

    /** rg JSON 的路径字段允许绝对或相对值，但必须存在且由候选边界重新准入。 */
    private static String jsonPath(JsonNode match) {
        JsonNode path = match.path("data").path("path").path("text");
        return path.isTextual() ? path.textValue() : null;
    }

    /** rg JSON 的 line number 缺失或非法时跳过非 match 诊断，match 则由调用方判定为失败。 */
    private static int jsonLineNumber(JsonNode match) {
        JsonNode line = match.path("data").path("line_number");
        return line.canConvertToInt() ? line.intValue() : -1;
    }

    /** rg 的 lines.text 带换行，输出前先去除 native 行结束符。 */
    private static String jsonLineText(JsonNode match) {
        JsonNode lines = match.path("data").path("lines").path("text");
        return lines.isTextual() ? lineText(lines.textValue()) : "";
    }

    /** 按匹配点保留有限上下文，避免 rg 返回的长单行占满结果预算。 */
    private static String boundedSnippet(String line, int match, int queryLength) {
        if (line.length() <= MAX_SNIPPET_CHARACTERS) return line;
        int safeMatch = Math.max(0, match);
        int start = Math.max(0, safeMatch - 160);
        int end = Math.min(line.length(), safeMatch + queryLength + 160);
        end = Math.min(end, start + MAX_SNIPPET_CHARACTERS);
        if (start > 0 && start < line.length() && Character.isLowSurrogate(line.charAt(start))
                && Character.isHighSurrogate(line.charAt(start - 1))) start++;
        if (end > 0 && end < line.length() && Character.isHighSurrogate(line.charAt(end - 1))
                && Character.isLowSurrogate(line.charAt(end))) end--;
        return line.substring(start, end);
    }

    /**
     * literal 且忽略大小写时仅为截取结果定位匹配点；真正的匹配语义仍完全由 rg 执行，避免复制搜索器。
     */
    private static int literalMatchOffset(String text, String pattern, boolean ignoreCase) {
        if (!ignoreCase) return text.indexOf(pattern);
        for (int index = 0, last = text.length() - pattern.length(); index <= last; index++) {
            if (text.regionMatches(true, index, pattern, 0, pattern.length())) return index;
        }
        return -1;
    }

    /** 统一判断当前进程是否在 Windows 上运行 native .exe。 */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** rg 采用 Pi 的正则默认值与少量可选开关，所有候选仍经过 WorkspaceBoundary 复核。 */
    private static final class GrepTool extends ToolSupport {
        private final Path workspaceRoot;
        private final NativeSearchToolResolver resolver;

        /** 冻结 grep 的字段长度、输出边界和 executable resolver，使每次调用互不污染。 */
        private GrepTool(Path workspaceRoot, NativeSearchToolResolver resolver) {
            super(new ToolSpec("grep", "Search a regular expression or literal text with ripgrep while respecting repository ignores",
                    objectSchema(Map.of(
                            "pattern", requiredStringProperty(
                                    "Non-empty search pattern. Regular expressions are the default; set literal for exact text. Use find for file-name lookup.",
                                    MAX_QUERY_LENGTH),
                            "glob", optionalStringProperty(
                                    "Optional glob matched by ripgrep; defaults to all searchable files.", MAX_PATTERN_LENGTH),
                            "path", optionalStringProperty(
                                    "Optional workspace-relative directory; defaults to .", MAX_PATH_LENGTH),
                            "literal", booleanProperty(
                                    "Treat pattern as literal text instead of a regular expression; defaults to false."),
                            "ignoreCase", booleanProperty("Case-insensitive matching; defaults to false."),
                            "context", integerProperty(
                                    "Lines before and after each match; defaults to 0.", 0, Integer.MAX_VALUE),
                            "limit", integerProperty(
                                    "Maximum matching lines; defaults to 100.", 1, Integer.MAX_VALUE)),
                            List.of("pattern"))));
            this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
            this.resolver = Objects.requireNonNull(resolver, "resolver");
        }

        /** grep 不写工作区或外部系统。 */
        @Override
        public ToolSideEffect sideEffect() {
            return ToolSideEffect.READ_ONLY;
        }

        /** grep 不产生 Workspace ChangeSet。 */
        @Override
        public WorkspaceMutationMode workspaceMutationMode() {
            return WorkspaceMutationMode.NONE;
        }

        /**
         * 通过 rg JSONL 流式处理匹配和上下文；rg 负责 ignore、隐藏项和 no-follow，Java 只复核候选与输出预算。
         */
        @Override
        ToolResult executeChecked(Invocation invocation, ExecutionContext context, CancellationToken token)
                throws IOException {
            String pattern = requiredString(invocation, "pattern", MAX_QUERY_LENGTH,
                    "must be a non-empty search pattern; use find for file-name lookup");
            String glob = optionalString(invocation, "glob", MAX_PATTERN_LENGTH);
            boolean literal = booleanValue(invocation, "literal", false);
            boolean ignoreCase = booleanValue(invocation, "ignoreCase", false);
            int contextLines = integer(invocation, "context", 0, 0, Integer.MAX_VALUE);
            int maxResults = integer(invocation, "limit", DEFAULT_RESULT_LIMIT, 1, Integer.MAX_VALUE);
            WorkspaceBoundary boundary = new WorkspaceBoundary(workspaceRoot);
            Path start = directory(boundary, optionalString(invocation, "path", MAX_PATH_LENGTH));
            Path executable;
            try {
                executable = resolver.resolve("rg");
            } catch (NativeSearchToolResolver.SearchToolUnavailableException missing) {
                return unavailable(missing.toolName());
            }
            List<String> arguments = new ArrayList<>();
            arguments.add("--no-config");
            arguments.add("--json");
            arguments.add("--line-number");
            arguments.add("--color=never");
            arguments.add("--hidden");
            arguments.add("--no-follow");
            if (literal) arguments.add("--fixed-strings");
            if (ignoreCase) arguments.add("--ignore-case");
            if (contextLines > 0) {
                arguments.add("--context");
                arguments.add(Integer.toString(contextLines));
            }
            if (glob != null && !glob.isBlank()) {
                arguments.add("--glob");
                arguments.add(glob.replace('\\', '/'));
            }
            arguments.add("--");
            arguments.add(pattern);
            arguments.add(start.toString());

            StringBuilder output = new StringBuilder();
            AtomicInteger resultCount = new AtomicInteger();
            AtomicInteger skippedCandidates = new AtomicInteger();
            AtomicBoolean resultLimit = new AtomicBoolean();
            AtomicBoolean outputLimit = new AtomicBoolean();
            AtomicBoolean nonTextRecord = new AtomicBoolean();
            NativeSearchProcess.Result result = NativeSearchProcess.run(executable, arguments, start,
                    token, context.deadline(), MAX_NATIVE_STDOUT_BYTES, MAX_NATIVE_STDERR_BYTES, line -> {
                        JsonNode record = parseSearchRecord(line);
                        String type = record.path("type").asText();
                        boolean matchRecord = "match".equals(type);
                        if (!matchRecord && !"context".equals(type)) return true;
                        String rawPath = jsonPath(record);
                        int lineNumber = jsonLineNumber(record);
                        if (rawPath == null || lineNumber < 1) {
                            throw new IOException("native_search_match_record_invalid");
                        }
                        Path candidate = nativePath(start, rawPath);
                        if (candidate == null) {
                            skippedCandidates.incrementAndGet();
                            return true;
                        }
                        Path admitted;
                        try {
                            admitted = boundary.revalidateExisting(candidate);
                            if (!Files.isRegularFile(admitted, LinkOption.NOFOLLOW_LINKS)) {
                                skippedCandidates.incrementAndGet();
                                return true;
                            }
                        } catch (IOException | SecurityException skipped) {
                            skippedCandidates.incrementAndGet();
                            return true;
                        }
                        JsonNode lineNode = record.path("data").path("lines");
                        if (!lineNode.path("text").isTextual()) {
                            nonTextRecord.set(true);
                            skippedCandidates.incrementAndGet();
                            return true;
                        }
                        String text = jsonLineText(record);
                        int match = literal ? literalMatchOffset(text, pattern, ignoreCase) : 0;
                        String rendered = boundary.relative(admitted) + (matchRecord ? ":" : "-") + lineNumber
                                + (matchRecord ? ": " : "- ")
                                + boundedSnippet(text, match, literal ? pattern.length() : 0);
                        if (!appendLine(output, rendered)) {
                            outputLimit.set(true);
                            return false;
                        }
                        if (!matchRecord) return true;
                        int count = resultCount.incrementAndGet();
                        if (count >= maxResults) {
                            resultLimit.set(true);
                            return false;
                        }
                        return true;
                    });
            if (result.cancelled()) throw new CancellationException();
            if (result.deadlineExceeded()) {
                return nativeSuccess("rg", resultCount.get(), true, "deadline",
                        skippedCandidates.get(), output.toString());
            }
            if (result.outputTruncated() || outputLimit.get()) {
                return nativeSuccess("rg", resultCount.get(), true, "output_limit",
                        skippedCandidates.get(), output.toString());
            }
            if (nonTextRecord.get()) {
                return nativeSuccess("rg", resultCount.get(), true, "non_text_output",
                        skippedCandidates.get(), output.toString());
            }
            if (resultLimit.get() || result.stoppedByConsumer()) {
                return nativeSuccess("rg", resultCount.get(), true, "result_limit",
                        skippedCandidates.get(), output.toString());
            }
            if (result.exitCode() != 0 && result.exitCode() != 1) {
                if (glob != null && isNativeGlobError(result.stderr())) {
                    throw ToolSupport.argument("glob", "must be a valid ripgrep glob pattern");
                }
                if (!literal && isNativeRegexError(result.stderr())) {
                    throw ToolSupport.argument("pattern", "must be a valid ripgrep regular expression");
                }
                throw new IOException("native_search_exit_nonzero");
            }
            return nativeSuccess("rg", resultCount.get(), skippedCandidates.get() > 0,
                    skippedCandidates.get() > 0 ? "candidate_validation" : null,
                    skippedCandidates.get(), output.toString());
        }
    }

    /** find 通过 fd 返回文件和目录路径，不读取正文并完整复用 fd 的 ignore 规则。 */
    private static final class FindTool extends ToolSupport {
        private final Path workspaceRoot;
        private final NativeSearchToolResolver resolver;

        /** 冻结 find 的 Glob、路径、结果上限和 executable resolver。 */
        private FindTool(Path workspaceRoot, NativeSearchToolResolver resolver) {
            super(new ToolSpec("find", "Find workspace files and directories by glob with fd",
                    objectSchema(Map.of(
                            "pattern", requiredStringProperty(
                                    "Non-empty glob matched against file names or relative paths.", MAX_PATTERN_LENGTH),
                            "path", optionalStringProperty(
                                    "Optional workspace-relative directory; defaults to .", MAX_PATH_LENGTH),
                            "limit", integerProperty(
                                    "Maximum matching files or directories; defaults to 100.", 1, Integer.MAX_VALUE)),
                            List.of("pattern"))));
            this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
            this.resolver = Objects.requireNonNull(resolver, "resolver");
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

        /** fd 负责 glob、hidden、gitignore 和结果上限；Java 只复核路径 containment。 */
        @Override
        ToolResult executeChecked(Invocation invocation, ExecutionContext context, CancellationToken token)
                throws IOException {
            String pattern = requiredString(invocation, "pattern", MAX_PATTERN_LENGTH,
                    "must be a non-empty glob pattern");
            int maxResults = integer(invocation, "limit", DEFAULT_RESULT_LIMIT, 1, Integer.MAX_VALUE);
            WorkspaceBoundary boundary = new WorkspaceBoundary(workspaceRoot);
            Path start = directory(boundary, optionalString(invocation, "path", MAX_PATH_LENGTH));
            Path executable;
            try {
                executable = resolver.resolve("fd");
            } catch (NativeSearchToolResolver.SearchToolUnavailableException missing) {
                return unavailable(missing.toolName());
            }
            StringBuilder output = new StringBuilder();
            AtomicInteger resultCount = new AtomicInteger();
            AtomicInteger skippedCandidates = new AtomicInteger();
            AtomicBoolean resultLimit = new AtomicBoolean();
            AtomicBoolean outputLimit = new AtomicBoolean();
            NativeSearchProcess.Result result = NativeSearchProcess.run(executable,
                    findArguments(pattern, start, maxResults), start, token, context.deadline(),
                    MAX_NATIVE_STDOUT_BYTES, MAX_NATIVE_STDERR_BYTES, rawLine -> {
                        String nativeLine = lineText(rawLine);
                        Path candidate = nativePath(start, nativeLine);
                        if (candidate == null) {
                            skippedCandidates.incrementAndGet();
                            return true;
                        }
                        Path admitted;
                        try {
                            admitted = boundary.revalidateExisting(candidate);
                        } catch (IOException | SecurityException skipped) {
                            skippedCandidates.incrementAndGet();
                            return true;
                        }
                        String relative = boundary.relative(admitted);
                        if (Files.isDirectory(admitted, LinkOption.NOFOLLOW_LINKS)) relative += "/";
                        if (!appendLine(output, relative)) {
                            outputLimit.set(true);
                            return false;
                        }
                        int count = resultCount.incrementAndGet();
                        if (count >= maxResults) {
                            resultLimit.set(true);
                            return false;
                        }
                        return true;
                    });
            if (result.cancelled()) throw new CancellationException();
            if (result.deadlineExceeded()) {
                return nativeSuccess("fd", resultCount.get(), true, "deadline",
                        skippedCandidates.get(), output.toString());
            }
            if (result.outputTruncated() || outputLimit.get()) {
                return nativeSuccess("fd", resultCount.get(), true, "output_limit",
                        skippedCandidates.get(), output.toString());
            }
            if (resultLimit.get() || result.stoppedByConsumer()) {
                return nativeSuccess("fd", resultCount.get(), true, "result_limit",
                        skippedCandidates.get(), output.toString());
            }
            if (result.exitCode() != 0) {
                if (isNativeGlobError(result.stderr())) {
                    throw ToolSupport.argument("pattern", "must be a valid fd glob pattern");
                }
                throw new IOException("native_search_exit_nonzero");
            }
            return nativeSuccess("fd", resultCount.get(), skippedCandidates.get() > 0,
                    skippedCandidates.get() > 0 ? "candidate_validation" : null,
                    skippedCandidates.get(), output.toString());
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

        /** 只读取一层目录并在 maxEntries+1 项处停止，避免目录列表无界进入内存。 */
        @Override
        ToolResult executeChecked(Invocation invocation, ExecutionContext context, CancellationToken token)
                throws IOException {
            int maxEntries = integer(invocation, "maxEntries", DEFAULT_MAX_ENTRIES, 1, MAX_ENTRIES);
            WorkspaceBoundary boundary = new WorkspaceBoundary(workspaceRoot);
            Path start = directory(boundary, optionalString(invocation, "path", MAX_PATH_LENGTH));
            ScanBudget budget = new ScanBudget(token, context.deadline(), maxEntries + 1);
            if (!allowNext(budget)) return successful(budget, 0, true, "");
            List<Path> entries = new ArrayList<>();
            boolean truncated = false;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(start)) {
                for (Path child : stream) {
                    if (!allowNext(budget) || !budget.reserveEntry()) {
                        truncated = true;
                        break;
                    }
                    Path admitted;
                    try {
                        admitted = boundary.revalidateExisting(child);
                    } catch (IOException | SecurityException skippedEntry) {
                        budget.markSkipped();
                        continue;
                    }
                    if (Files.isRegularFile(admitted, LinkOption.NOFOLLOW_LINKS)) budget.accountMetadataFile();
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

    /** ls 的单层扫描统计真实可知；find/grep 另用 nativeSuccess，避免伪造全树计数。 */
    private static AgentTool.ToolResult successful(ScanBudget budget, int resultCount, boolean truncated,
                                                   String content) {
        boolean partial = truncated || budget.skippedEntries() > 0;
        JsonObjectBuilder metadata = JsonObjects.builder()
                .putNumber("resultCount", resultCount)
                .putNumber("scannedEntries", budget.scannedEntries())
                .putNumber("skippedEntries", budget.skippedEntries())
                .putNumber("scannedFiles", budget.scannedFiles())
                .putNumber("scannedBytes", 0)
                .putBoolean("truncated", partial);
        if (budget.termination() != null) metadata.putText("termination", budget.termination());
        String summary = "[workspace discovery] results=" + resultCount
                + ", scannedEntries=" + budget.scannedEntries()
                + ", skippedEntries=" + budget.skippedEntries()
                + ", scannedFiles=" + budget.scannedFiles()
                + ", scannedBytes=0"
                + ", truncated=" + partial
                + (budget.termination() == null ? "" : ", termination=" + budget.termination())
                + (partial ? ". Results are partial; some entries were skipped or a limit was reached."
                : resultCount == 0 ? ". No matching entries were found." : ". Results are complete.");
        String visibleContent = content.isBlank() ? summary : content + "\n\n" + summary;
        return new AgentTool.ToolResult(ToolOutcome.SUCCEEDED, visibleContent,
                Optional.of(metadata.build()), null);
    }

    /** 单层 ls 的有界统计；find/grep 不复用它，因此不会重新引入全树递归。 */
    private static final class ScanBudget {
        private final CancellationToken token;
        private final Instant deadline;
        private final int maximumEntries;
        private int scannedEntries;
        private int scannedFiles;
        private int skippedEntries;
        private String termination;

        /** 冻结一次 ls 调用的目录项上限，防止目录枚举阶段动态扩大预算。 */
        private ScanBudget(CancellationToken token, Instant deadline, int maximumEntries) {
            this.token = Objects.requireNonNull(token, "token");
            this.deadline = Objects.requireNonNull(deadline, "deadline");
            this.maximumEntries = maximumEntries;
        }

        /** 返回取消令牌供每个目录项复用。 */
        private CancellationToken token() {
            return token;
        }

        /** 返回冻结 Deadline，避免目录枚举过程中延长当前操作。 */
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

        /** 记录单层普通文件发现；ls 不读取正文，因此字节统计固定为零。 */
        private void accountMetadataFile() {
            scannedFiles++;
        }

        /** 记录被安全跳过的链接或竞态项，帮助模型区分空目录和部分发现。 */
        private void markSkipped() {
            skippedEntries++;
        }

        /** 保存第一个有界终止原因，避免后续条件覆盖最先触发的资源边界。 */
        private void mark(String reason) {
            if (termination == null) termination = reason;
        }

        /** 返回已枚举目录项数，仅用于 ls 的真实单层统计。 */
        private int scannedEntries() {
            return scannedEntries;
        }

        /** 返回已识别为普通文件的单层条目数。 */
        private int scannedFiles() {
            return scannedFiles;
        }

        /** 返回单层遍历中被跳过的链接或竞态项数量。 */
        private int skippedEntries() {
            return skippedEntries;
        }

        /** 返回首个有界终止原因；null 表示扫描自然完成。 */
        private String termination() {
            return termination;
        }
    }

    /** 返回不受本地路径分隔符影响的排序键，确保 ls 结果稳定。 */
    private static String stablePath(Path path) {
        return path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    }
}
