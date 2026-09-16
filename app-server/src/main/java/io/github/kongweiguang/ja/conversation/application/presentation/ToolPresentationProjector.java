// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.presentation;

import io.github.kongweiguang.ja.conversation.domain.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonBoolean;
import io.github.kongweiguang.ja.foundation.json.JsonNull;
import io.github.kongweiguang.ja.foundation.json.JsonNumber;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.foundation.json.JsonValue;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 在 Java 权威边界把 Tool 原始事实投影为脱敏、相对路径化且有界的客户端展示数据。
 */
public final class ToolPresentationProjector {
    private static final int PREVIEW_LINES = 10;
    private static final int PREVIEW_CHARACTERS = 16_384;
    private static final int MCP_PREVIEW_ITEMS = 24;
    private static final int MCP_PREVIEW_DEPTH = 4;
    private static final int MCP_VALUE_CHARACTERS = 1_024;
    private static final Pattern ANSI = Pattern.compile("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|password|passwd|secret|cookie)"
                    + "(\\s*[:=]\\s*)([^\\s,;]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/-]{8,}={0,2}");
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])[A-Z]:[\\\\/][^\\r\\n\\t\"']+");
    private static final Pattern UNC_ABSOLUTE = Pattern.compile(
            "(?<![A-Za-z0-9_])\\\\\\\\[^\\r\\n\\t\"']+");
    private static final Pattern POSIX_ABSOLUTE = Pattern.compile(
            "(?<![A-Za-z0-9_:/])/(?!/)[^\\r\\n\\t\"' ]+");
    private static final Pattern SKILL_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    /** 禁止实例化纯投影器，避免意外持有原始 Tool 数据。 */
    private ToolPresentationProjector() {
    }

    /**
     * Tool 准备阶段只展示允许的参数摘要；write/edit 正文永远只显示长度，不能进入 UI 或 Tool 表。
     */
    public static ToolPresentation prepared(AgentTool.Invocation invocation, Path workspaceRoot,
                                            List<String> knownSecrets) {
        Objects.requireNonNull(invocation, "invocation");
        knownSecrets = List.copyOf(Objects.requireNonNull(knownSecrets, "knownSecrets"));
        Path root = normalizedRoot(workspaceRoot);
        ToolPresentation.Kind kind = kind(invocation.toolName());
        List<String> paths = relativePaths(invocation.arguments(), root);
        String command = kind == ToolPresentation.Kind.SHELL
                ? sanitize(text(invocation.arguments(), "command"), root, knownSecrets) : null;
        String input = switch (kind) {
            case SHELL -> command;
            case READ -> readInput(invocation.toolName(), invocation.arguments(), paths, root, knownSecrets);
            case EDIT -> editInput(invocation.arguments(), paths);
            case WRITE -> mutationInput("write", invocation.arguments(), paths, "content");
            case MCP -> boundedJson(invocation.arguments(), root, knownSecrets);
        };
        String actionTitle = title(kind, invocation.toolName());
        if ("request_user_input".equals(invocation.toolName())) {
            actionTitle = "询问偏好";
            JsonValue questions = invocation.arguments().get("questions");
            input = questions instanceof JsonArray array && !array.values().isEmpty()
                    && array.values().getFirst() instanceof JsonObject question
                    ? sanitize(text(question, "prompt"), root, knownSecrets) : "等待用户回答";
        }
        return new ToolPresentation(kind, actionTitle, ToolPresentation.Status.PENDING,
                input, null, null, paths, command, ".", null, null, null, null, false, null);
    }

    /**
     * 只替换公开生命周期状态并复用已经完成脱敏的字段，审批切换不能重新接触原始 Tool 参数。
     */
    public static ToolPresentation withStatus(ToolPresentation value, ToolPresentation.Status status) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(status, "status");
        return new ToolPresentation(value.kind(), value.title(), status, value.inputPreview(), value.outputPreview(),
                value.summary(), value.relativePaths(), value.command(), value.relativeCwd(), value.stdout(), value.stderr(),
                value.exitCode(), value.durationMs(), value.truncated(), value.artifactId());
    }

    /**
     * Tool 终态复用准备阶段字段并只接纳已脱敏结果；完整正文使用 opaque artifactId 关联分页读取。
     */
    public static Completed completed(AgentTool.Invocation invocation, AgentTool.ToolResult result,
                                      Path workspaceRoot, List<String> knownSecrets, long durationMs) {
        ToolPresentation base = prepared(invocation, workspaceRoot, knownSecrets);
        Path root = normalizedRoot(workspaceRoot);
        String sanitized = sanitize(result.content(), root, knownSecrets);
        String artifactId = sanitized.isEmpty() ? null : "artifact_" + UUID.randomUUID().toString().replace("-", "");
        boolean sourceTruncated = booleanMetadata(result, "truncated");
        boolean previewTruncated = sanitized.length() > PREVIEW_CHARACTERS || sanitized.lines().count() > PREVIEW_LINES;
        String preview = preview(sanitized);
        String stdout = null;
        String stderr = null;
        if (base.kind() == ToolPresentation.Kind.SHELL) {
            Streams streams = streams(sanitized);
            stdout = preview(streams.stdout());
            stderr = preview(streams.stderr());
        }
        Integer exitCode = integerMetadata(result, "exit_code");
        String summary = completedSummary(invocation, result);
        ToolPresentation value = new ToolPresentation(base.kind(), base.title(), status(result.outcome()),
                base.inputPreview(), preview, summary, base.relativePaths(), base.command(), base.relativeCwd(), stdout, stderr,
                exitCode, durationMs, sourceTruncated || previewTruncated, artifactId);
        return new Completed(value, sanitized);
    }

    /** 完成投影携带已经脱敏的 artifact 正文；正文只允许进入受约束 artifact 表。 */
    public record Completed(ToolPresentation presentation, String artifactContent) {
        /** 确保 artifact identity 与正文存在性严格配对。 */
        public Completed {
            Objects.requireNonNull(presentation, "presentation");
            artifactContent = Objects.requireNonNull(artifactContent, "artifactContent");
            if ((presentation.artifactId() == null) != artifactContent.isEmpty()) {
                throw new IllegalArgumentException("artifact identity and content must be paired");
            }
        }
    }

    /** 把 Tool outcome 映射到客户端固定状态，不泄漏内部 ToolState。 */
    private static ToolPresentation.Status status(ToolOutcome outcome) {
        return switch (outcome) {
            case SUCCEEDED -> ToolPresentation.Status.SUCCESS;
            case FAILED -> ToolPresentation.Status.ERROR;
            case CANCELLED -> ToolPresentation.Status.CANCELLED;
        };
    }

    /**
     * 只从内置 Tool 已声明的数字 metadata 生成一句结果摘要，既让展开视图能快速扫描，也不把正文、路径
     * 或自由格式 metadata 带进持久化协议；失败仍以原有安全诊断为唯一事实来源。
     */
    private static String completedSummary(AgentTool.Invocation invocation, AgentTool.ToolResult result) {
        if (result.outcome() != ToolOutcome.SUCCEEDED) return null;
        return switch (invocation.toolName()) {
            case "read" -> readSummary(result);
            case "grep" -> discoverySummary(result, "个匹配项");
            case "find" -> discoverySummary(result, "个文件或目录");
            case "ls" -> discoverySummary(result, "个条目");
            case "edit" -> editSummary(invocation.arguments());
            case "write" -> "文件已写入";
            default -> null;
        };
    }

    /**
     * read 的续读位置仅使用执行端精确计算的行号；缺失任一关键数字时宁可不显示摘要，也不让 UI 猜测
     * 文件是否读取完毕。
     */
    private static String readSummary(AgentTool.ToolResult result) {
        Long lines = nonNegativeLongMetadata(result, "lines");
        Long totalLines = nonNegativeLongMetadata(result, "totalLines");
        if (lines == null || totalLines == null) return null;
        Long nextOffset = nonNegativeLongMetadata(result, "nextOffset");
        String summary = "已读取 " + lines + " 行，共 " + totalLines + " 行";
        if (nextOffset != null && nextOffset > 0) return summary + "；可从第 " + nextOffset + " 行继续";
        return booleanMetadata(result, "truncated") ? summary + "；结果受上限限制" : summary + "；已到末尾";
    }

    /**
     * 搜索与目录 Tool 的结果数和 partial 状态来自原生执行 metadata；预览自身的十行截断不等同于搜索不完整，
     * 因此不会误导用户或模型认为需要重复搜索。
     */
    private static String discoverySummary(AgentTool.ToolResult result, String noun) {
        Long count = nonNegativeLongMetadata(result, "resultCount");
        if (count == null) return null;
        String summary = count == 0 ? "未找到结果" : "找到 " + count + " " + noun;
        return booleanMetadata(result, "truncated") ? summary + "；部分结果未显示" : summary;
    }

    /**
     * edit 的替换数量从受 Schema 约束的调用参数读取，成功文案不再依赖英文执行输出，避免展示层解析正文。
     */
    private static String editSummary(JsonObject arguments) {
        int count = editBlockCount(arguments);
        return count == 0 ? "编辑完成" : "已完成 " + count + " 处替换";
    }

    /** 内置只读 Tool 共用 READ wire kind，具体动作由真实 toolName 和安全标题区分；未知扩展才归 MCP。 */
    private static ToolPresentation.Kind kind(String name) {
        return switch (name) {
            case "read", "read_attachment", "grep", "find", "ls" -> ToolPresentation.Kind.READ;
            case "edit" -> ToolPresentation.Kind.EDIT;
            case "write" -> ToolPresentation.Kind.WRITE;
            case "shell" -> ToolPresentation.Kind.SHELL;
            default -> ToolPresentation.Kind.MCP;
        };
    }

    /** 只读内置 Tool 使用精确动作标题，未知 Tool 只显示其经过校验的名称。 */
    private static String title(ToolPresentation.Kind kind, String name) {
        return switch (kind) {
            case READ -> switch (name) {
                case "grep" -> "搜索内容";
                case "find" -> "查找文件";
                case "ls" -> "列出目录";
                case "read_attachment" -> "读取附件";
                default -> "读取";
            };
            case EDIT -> "编辑";
            case WRITE -> "写入";
            case SHELL -> "执行命令";
            case MCP -> name;
        };
    }

    /** 按真实只读 Tool 保留最能说明动作的首个目标，并把敏感值限制在脱敏摘要内。 */
    private static String readInput(String toolName, JsonObject arguments, List<String> paths,
                                    Path root, List<String> knownSecrets) {
        return switch (toolName) {
            case "grep" -> searchInput("pattern", arguments, paths, root, knownSecrets);
            case "find" -> searchInput("pattern", arguments, paths, root, knownSecrets);
            case "ls" -> paths.isEmpty() ? "." : paths.getFirst();
            case "read_attachment" -> attachmentInput(arguments, root, knownSecrets);
            default -> fileReadInput(arguments, paths);
        };
    }

    /** 普通 read 只展示相对路径与一基行范围，保持既有文件定位语义。 */
    private static String fileReadInput(JsonObject arguments, List<String> paths) {
        String path = paths.isEmpty() ? "[resource]" : paths.getFirst();
        Long offset = number(arguments, "offset");
        Long limit = number(arguments, "limit");
        if (offset == null && limit == null) return path;
        return path + " · " + (offset == null ? 1 : offset) + ":" + (limit == null ? 2_000 : limit);
    }

    /** grep/find 的首个目标是 query/pattern，路径仅作为搜索范围附在摘要后。 */
    private static String searchInput(String field, JsonObject arguments, List<String> paths,
                                      Path root, List<String> knownSecrets) {
        String rawTarget = text(arguments, field);
        String target = rawTarget == null || rawTarget.isEmpty()
                ? "[missing-" + field + "]"
                : escapePreview(boundedScalar(sanitize(rawTarget, root, knownSecrets)));
        String scope = paths.isEmpty() ? "." : paths.getFirst();
        return field + "=\"" + target + "\" · " + scope;
    }

    /** read_attachment 保留受管 opaque ID 和字节窗口，避免退化成无法诊断的 resource 占位。 */
    private static String attachmentInput(JsonObject arguments, Path root, List<String> knownSecrets) {
        String rawAttachmentId = text(arguments, "attachmentId");
        String attachmentId = rawAttachmentId == null || rawAttachmentId.isBlank()
                ? "[missing-attachmentId]"
                : escapePreview(boundedScalar(sanitize(rawAttachmentId, root, knownSecrets)));
        Long offset = number(arguments, "offsetBytes");
        Long maxBytes = number(arguments, "maxBytes");
        return "attachmentId=\"" + attachmentId + "\" · bytes "
                + (offset == null ? 0 : offset) + ":" + (maxBytes == null ? 64 * 1024 : maxBytes);
    }

    /** 写操作只发布字符计数，避免源代码、密钥或完整补丁复制进展示存储。 */
    private static String mutationInput(String operation, JsonObject arguments, List<String> paths, String... keys) {
        StringBuilder result = new StringBuilder(paths.isEmpty() ? "[external-path]" : paths.getFirst());
        for (String key : keys) {
            String value = text(arguments, key);
            if (value != null) result.append(" · ").append(key).append('=').append(value.length()).append(" chars");
        }
        return operation + " " + result;
    }

    /**
     * 批量 edit 仅显示目标文件和替换块数，既让用户判断操作规模，也不将任一原文或替换内容写入历史展示。
     */
    private static String editInput(JsonObject arguments, List<String> paths) {
        int count = editBlockCount(arguments);
        String path = paths.isEmpty() ? "[external-path]" : paths.getFirst();
        return "edit " + path + " · " + (count == 0 ? "[missing-edits]" : count + " block(s)");
    }

    /**
     * 编辑摘要与输入摘要共用同一数组计数规则，避免两处对缺失或畸形 edits 得出不同操作规模。
     */
    private static int editBlockCount(JsonObject arguments) {
        JsonValue edits = arguments.get("edits");
        return edits instanceof JsonArray array ? array.values().size() : 0;
    }

    /**
     * 未知 MCP 参数按整棵 JSON 树递归脱敏，并同时限制深度、节点数与单值长度；只处理第一层会让
     * `headers.authorization` 等常见嵌套凭据绕过安全投影。
     */
    private static String boundedJson(JsonObject arguments, Path root, List<String> knownSecrets) {
        StringBuilder result = new StringBuilder();
        appendJsonPreview(result, arguments, root, knownSecrets, 0, new int[]{MCP_PREVIEW_ITEMS});
        return preview(result.toString());
    }

    /**
     * 穷举 sealed JsonValue，敏感键在访问其值之前即替换；共享剩余节点预算防止宽树通过每层重置上限。
     */
    private static void appendJsonPreview(StringBuilder target, JsonValue value, Path root,
                                          List<String> knownSecrets, int depth, int[] remaining) {
        if (depth >= MCP_PREVIEW_DEPTH) {
            target.append('…');
            return;
        }
        switch (value) {
            case JsonObject object -> {
                target.append('{');
                int written = 0;
                for (Map.Entry<String, JsonValue> entry : object.members().entrySet()) {
                    if (remaining[0]-- <= 0) {
                        if (written > 0) target.append(", ");
                        target.append('…');
                        break;
                    }
                    if (written++ > 0) target.append(", ");
                    String key = boundedScalar(sanitize(entry.getKey(), root, knownSecrets));
                    target.append(escapePreview(key)).append(": ");
                    if (secretKey(entry.getKey())) target.append("[REDACTED]");
                    else appendJsonPreview(target, entry.getValue(), root, knownSecrets, depth + 1, remaining);
                }
                target.append('}');
            }
            case JsonArray array -> {
                target.append('[');
                int written = 0;
                for (JsonValue member : array.values()) {
                    if (remaining[0]-- <= 0) {
                        if (written > 0) target.append(", ");
                        target.append('…');
                        break;
                    }
                    if (written++ > 0) target.append(", ");
                    appendJsonPreview(target, member, root, knownSecrets, depth + 1, remaining);
                }
                target.append(']');
            }
            case JsonText text -> target.append('"').append(escapePreview(
                    boundedScalar(sanitize(text.value(), root, knownSecrets)))).append('"');
            case JsonNumber number -> target.append(number.value().toPlainString());
            case JsonBoolean bool -> target.append(bool.value());
            case JsonNull ignored -> target.append("null");
        }
    }

    /** 单个 MCP 标量只保留固定 UTF-16 上限，并避免在代理项中间截断。 */
    private static String boundedScalar(String value) {
        if (value.length() <= MCP_VALUE_CHARACTERS) return value;
        int end = MCP_VALUE_CHARACTERS;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + '…';
    }

    /** 转义会破坏 JSON-like 预览结构的字符，控制字符已在 sanitize 中统一清理。 */
    private static String escapePreview(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }

    /**
     * 只从 path 字段提取工作区相对路径；Skill 仅公开不含物理位置的逻辑名称，
     * 其它工作区外路径和资源 URI 继续使用安全占位。
     */
    private static List<String> relativePaths(JsonObject arguments, Path root) {
        String raw = text(arguments, "path");
        if (raw == null) return List.of();
        if (raw.startsWith("skill://")) return List.of(skillPreview(raw));
        if (raw.startsWith("ja-artifact://")) return List.of("[resource]");
        try {
            Path candidate = Path.of(raw);
            Path absolute = (candidate.isAbsolute() ? candidate : root.resolve(candidate)).toAbsolutePath().normalize();
            if (!absolute.startsWith(root)) return List.of("[external-path]");
            String relative = root.relativize(absolute).toString().replace('\\', '/');
            return List.of(relative.isEmpty() ? "." : relative);
        } catch (InvalidPathException invalid) {
            return List.of("[invalid-path]");
        }
    }

    /**
     * Skill 预览只保留经过标识符校验的名称；资源子路径和畸形输入都不进入持久化展示。
     */
    private static String skillPreview(String raw) {
        String address = raw.substring("skill://".length());
        int separator = address.indexOf('/');
        String name = separator < 0 ? address : address.substring(0, separator);
        return SKILL_NAME.matcher(name).matches() ? "skill://" + name : "[resource]";
    }

    /** 统一清理 ANSI、危险控制字符、常见凭据和本地绝对路径。 */
    public static String sanitize(String value, Path workspaceRoot, List<String> knownSecrets) {
        if (value == null || value.isEmpty()) return "";
        knownSecrets = List.copyOf(Objects.requireNonNull(knownSecrets, "knownSecrets"));
        String cleaned = ANSI.matcher(value).replaceAll("");
        StringBuilder controls = new StringBuilder(cleaned.length());
        cleaned.codePoints().forEach(character -> {
            if (!Character.isISOControl(character) || character == '\n' || character == '\r' || character == '\t') {
                controls.appendCodePoint(character);
            }
        });
        cleaned = SECRET_ASSIGNMENT.matcher(controls).replaceAll("$1$2[REDACTED]");
        cleaned = BEARER.matcher(cleaned).replaceAll("Bearer [REDACTED]");
        for (String secret : knownSecrets) {
            if (secret == null || secret.isEmpty()) continue;
            cleaned = cleaned.replace(secret, "[REDACTED]");
        }
        Path root = normalizedRoot(workspaceRoot);
        cleaned = replaceIgnoreCase(cleaned, root.toString(), ".");
        String alternateRoot = root.toString().replace('\\', '/');
        cleaned = replaceIgnoreCase(cleaned, alternateRoot, ".");
        cleaned = UNC_ABSOLUTE.matcher(cleaned).replaceAll("[external-path]");
        cleaned = WINDOWS_ABSOLUTE.matcher(cleaned).replaceAll("[external-path]");
        return POSIX_ABSOLUTE.matcher(cleaned).replaceAll("[external-path]");
    }

    /** Windows 路径比较不区分大小写，普通 replace 不能安全覆盖这一点。 */
    private static String replaceIgnoreCase(String value, String target, String replacement) {
        return Pattern.compile(Pattern.quote(target), Pattern.CASE_INSENSITIVE).matcher(value)
                .replaceAll(java.util.regex.Matcher.quoteReplacement(replacement));
    }

    /** 结果预览固定保留前十行及字符上限，完整脱敏正文交给 artifact 分页。 */
    private static String preview(String value) {
        if (value == null || value.isEmpty()) return value;
        List<String> lines = value.lines().limit(PREVIEW_LINES).toList();
        String retained = String.join("\n", lines);
        if (retained.length() <= PREVIEW_CHARACTERS) return retained;
        int end = PREVIEW_CHARACTERS;
        if (Character.isHighSurrogate(retained.charAt(end - 1))) end--;
        return retained.substring(0, end);
    }

    /** Shell 兼容既有分区格式，并在无标签时把内容视为 stdout。 */
    private static Streams streams(String value) {
        if (value.startsWith("[stderr]\n")) return new Streams("", value.substring(9));
        if (!value.startsWith("[stdout]\n")) return new Streams(value, "");
        int separator = value.indexOf("\n[stderr]\n", 9);
        if (separator < 0) return new Streams(value.substring(9), "");
        return new Streams(value.substring(9, separator), value.substring(separator + 10));
    }

    /** 从强类型 metadata 读取布尔值，缺失时保持 false 而不伪造 Tool 提供的事实。 */
    private static boolean booleanMetadata(AgentTool.ToolResult result, String key) {
        if (result.structuredContent().orElse(null) instanceof JsonObject object
            && object.members().get(key) instanceof JsonBoolean value) return value.value();
        return false;
    }

    /** 从强类型 metadata 读取精确 int；缺失或溢出时不对外提供字段。 */
    private static Integer integerMetadata(AgentTool.ToolResult result, String key) {
        if (result.structuredContent().orElse(null) instanceof JsonObject object
            && object.members().get(key) instanceof JsonNumber value) {
            try {
                return value.value().intValueExact();
            } catch (ArithmeticException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 结果摘要只接受精确、非负的长整型 metadata；执行端缺失、越界或负值都保持缺失而不是展示猜测值。
     */
    private static Long nonNegativeLongMetadata(AgentTool.ToolResult result, String key) {
        if (result.structuredContent().orElse(null) instanceof JsonObject object
            && object.members().get(key) instanceof JsonNumber value) {
            try {
                long number = value.value().longValueExact();
                return number >= 0 ? number : null;
            } catch (ArithmeticException ignored) {
                return null;
            }
        }
        return null;
    }

    /** 读取 JSON 文本成员，缺失或非文本保持缺失。 */
    private static String text(JsonObject object, String key) {
        return object.members().get(key) instanceof JsonText value ? value.value() : null;
    }

    /** 读取 JSON 整数成员，非精确长整数保持缺失。 */
    private static Long number(JsonObject object, String key) {
        if (!(object.members().get(key) instanceof JsonNumber value)) return null;
        try {
            return value.value().longValueExact();
        } catch (ArithmeticException ignored) {
            return null;
        }
    }

    /** 敏感键使用大小写不敏感、去分隔符比较，覆盖常见 Provider 与 HTTP 命名。 */
    private static boolean secretKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return normalized.contains("token") || normalized.contains("password")
               || normalized.contains("secret") || normalized.contains("apikey")
               || normalized.equals("authorization") || normalized.contains("cookie");
    }

    /** 所有路径投影使用绝对规范根，拒绝依赖进程 cwd。 */
    private static Path normalizedRoot(Path value) {
        return Objects.requireNonNull(value, "workspaceRoot").toAbsolutePath().normalize();
    }

    /** Shell 两个流保持独立，避免客户端再解析普通输出。 */
    private record Streams(String stdout, String stderr) {
    }
}
