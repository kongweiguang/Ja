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
            case READ -> readInput(invocation.arguments(), paths);
            case EDIT -> mutationInput("edit", invocation.arguments(), paths, "oldText", "newText");
            case WRITE -> mutationInput("write", invocation.arguments(), paths, "content");
            case MCP -> boundedJson(invocation.arguments(), root, knownSecrets);
        };
        return new ToolPresentation(kind, title(kind, invocation.toolName()), ToolPresentation.Status.PENDING,
                input, null, paths, command, ".", null, null, null, null, false, null);
    }

    /**
     * 只替换公开生命周期状态并复用已经完成脱敏的字段，审批切换不能重新接触原始 Tool 参数。
     */
    public static ToolPresentation withStatus(ToolPresentation value, ToolPresentation.Status status) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(status, "status");
        return new ToolPresentation(value.kind(), value.title(), status, value.inputPreview(), value.outputPreview(),
                value.relativePaths(), value.command(), value.relativeCwd(), value.stdout(), value.stderr(),
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
        ToolPresentation value = new ToolPresentation(base.kind(), base.title(), status(result.outcome()),
                base.inputPreview(), preview, base.relativePaths(), base.command(), base.relativeCwd(), stdout, stderr,
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

    /** 内置 Tool 使用专用展示，扩展能力统一标识为 MCP。 */
    private static ToolPresentation.Kind kind(String name) {
        return switch (name) {
            case "read", "read_attachment" -> ToolPresentation.Kind.READ;
            case "edit" -> ToolPresentation.Kind.EDIT;
            case "write" -> ToolPresentation.Kind.WRITE;
            case "shell" -> ToolPresentation.Kind.SHELL;
            default -> ToolPresentation.Kind.MCP;
        };
    }

    /** 标题保持简短且未知 Tool 只显示其经过校验的名称。 */
    private static String title(ToolPresentation.Kind kind, String name) {
        return switch (kind) {
            case READ -> "读取";
            case EDIT -> "编辑";
            case WRITE -> "写入";
            case SHELL -> "执行命令";
            case MCP -> name;
        };
    }

    /** 读取参数只展示相对路径与行范围。 */
    private static String readInput(JsonObject arguments, List<String> paths) {
        String path = paths.isEmpty() ? "[resource]" : paths.getFirst();
        Long offset = number(arguments, "offset");
        Long limit = number(arguments, "limit");
        if (offset == null && limit == null) return path;
        return path + " · " + (offset == null ? 1 : offset) + ":" + (limit == null ? 2_000 : limit);
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
