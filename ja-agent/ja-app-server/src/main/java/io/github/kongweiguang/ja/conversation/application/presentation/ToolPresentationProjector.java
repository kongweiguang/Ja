// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.presentation;

import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestion;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonBoolean;
import io.github.kongweiguang.ja.agent.domain.json.JsonNull;
import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.conversation.application.interaction.InteractionToolFactory;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;

import java.math.BigDecimal;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
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
    private static final Pattern SAFE_ARGUMENT_DIAGNOSTIC = Pattern.compile(
            "Tool arguments violate '(?<keyword>[A-Za-z][A-Za-z0-9_-]{0,63})' at "
            + "(?<location>[A-Za-z0-9_.$/\\[\\]~\\-]{1,256})"
            + "(?: for field '[A-Za-z0-9_.\\-]{1,128}')?: "
            + "(?<detail>a required field is missing|the JSON type is incorrect|an undeclared field is present|"
            + "the declared value constraint is not satisfied|the declared schema constraint is not satisfied)"
            + "\\.\\. Correct the arguments and retry this Tool\\.");
    private static final Pattern SKILL_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    /**
     * 禁止实例化纯投影器，避免意外持有原始 Tool 数据。
     */
    private ToolPresentationProjector() {
    }

    /**
     * Tool 准备阶段只展示允许的参数摘要；Plan 折叠目标从已脱敏的业务字段生成，原有有界参数仍供展开核对。
     * 命令沿用当前 cmd 契约，stdin 与 write/edit 正文不进入展示存储。
     */
    public static ToolPresentation prepared(AgentTool.Invocation invocation, Path workspaceRoot,
                                            List<String> knownSecrets) {
        Objects.requireNonNull(invocation, "invocation");
        knownSecrets = List.copyOf(Objects.requireNonNull(knownSecrets, "knownSecrets"));
        Path root = normalizedRoot(workspaceRoot);
        ToolPresentation.Kind kind = kind(invocation.toolName());
        List<String> paths = relativePaths(invocation.arguments(), root);
        String command = "exec_command".equals(invocation.toolName())
                ? sanitize(text(invocation.arguments(), "cmd"), root, knownSecrets) : null;
        String input = switch (kind) {
            case SHELL -> command == null ? processInput(invocation.arguments()) : command;
            case READ -> readInput(invocation.toolName(), invocation.arguments(), paths, root, knownSecrets);
            case EDIT -> editInput(invocation.arguments(), paths);
            case WRITE -> mutationInput("write", invocation.arguments(), paths, "content");
            case MCP -> boundedJson(invocation.arguments(), root, knownSecrets);
        };
        String actionTitle = title(kind, invocation.toolName());
        if ("request_user_input".equals(invocation.toolName())) {
            actionTitle = "询问偏好";
            JsonValue questions = invocation.arguments().get("questions");
            input = questions instanceof JsonArray(List<JsonValue> values) && !values.isEmpty()
                    && values.getFirst() instanceof JsonObject question
                    ? sanitize(text(question, "prompt"), root, knownSecrets) : "等待用户回答";
        }
        String summary = planSummary(invocation.toolName(), invocation.arguments(), root, knownSecrets);
        String cwd = "exec_command".equals(invocation.toolName())
                ? processCwd(invocation.arguments(), root) : ".";
        return new ToolPresentation(kind, actionTitle, ToolPresentation.Status.PENDING,
                input, null, summary, paths, command, cwd, null, null, null, null, false, null);
    }

    /**
     * 模型调用刚提交时只给出不含参数的动作提议；完整摘要必须等最终参数校验和绑定完成后再生成。
     */
    public static ToolPresentation modelCallPreview(AgentTool.Invocation invocation) {
        AgentTool.Invocation call = Objects.requireNonNull(invocation, "invocation");
        ToolPresentation.Kind kind = kind(call.toolName());
        return new ToolPresentation(kind, title(kind, call.toolName()), ToolPresentation.Status.PENDING,
                "等待参数校验", null, null, List.of(), null, ".", null, null, null, null, false, null);
    }

    /**
     * 只替换公开生命周期状态并复用已经完成脱敏的字段，审批切换不能重新接触原始 Tool 参数。
     */
    public static ToolPresentation withStatus(ToolPresentation value, ToolPresentation.Status status) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(status, "status");
        return new ToolPresentation(value.kind(), value.title(), status, value.inputPreview(), value.outputPreview(),
                value.summary(), value.interactionAnswers(), value.relativePaths(), value.command(), value.relativeCwd(),
                value.stdout(), value.stderr(), value.exitCode(), value.durationMs(), value.truncated(), value.artifactId(),
                value.recovery(), value.media());
    }

    /**
     * Tool 终态只接纳已脱敏结果；进程产物与模型恢复提示分流，防止内部会话句柄成为用户输出。
     * 成功 Plan Tool 保留请求意图摘要，失败仍只以真实安全诊断为事实；受管图片正文须由 Thread 授权读取。
     */
    public static Completed completed(AgentTool.Invocation invocation, AgentTool.ToolResult result,
                                      Path workspaceRoot, List<String> knownSecrets, Long durationMs) {
        if ("request_user_input".equals(invocation.toolName())
            && result.outcome() == ToolOutcome.SUCCEEDED) {
            return completedClarification(invocation, result, workspaceRoot, knownSecrets, durationMs);
        }
        ToolPresentation base = prepared(invocation, workspaceRoot, knownSecrets);
        Path root = normalizedRoot(workspaceRoot);
        boolean processOutput = result.outcome() == ToolOutcome.SUCCEEDED
                && List.of("exec_command", "write_stdin", "stop_process").contains(invocation.toolName())
                && result.artifactContent().isPresent();
        String sanitized = processOutput
                ? sanitize(result.artifactContent().orElseThrow(), root, knownSecrets)
                : sanitizeResultContent(result, root, knownSecrets);
        String artifact = result.artifactContent().map(value -> sanitize(value, root, knownSecrets)).orElse(sanitized);
        String artifactId = artifact.isEmpty() ? null : "artifact_" + UUID.randomUUID().toString().replace("-", "");
        boolean sourceTruncated = booleanMetadata(result, "truncated")
                || processOutput && booleanMetadata(result, "output_truncated");
        boolean previewTruncated = sanitized.length() > PREVIEW_CHARACTERS || sanitized.lines().count() > PREVIEW_LINES
                || artifact.length() > PREVIEW_CHARACTERS || artifact.lines().count() > PREVIEW_LINES;
        String preview = preview(sanitized);
        String stdout = null;
        String stderr = null;
        if (base.kind() == ToolPresentation.Kind.SHELL) {
            if (processOutput) {
                stdout = preview(sanitized);
            } else {
                Streams streams = streams(sanitized);
                stdout = preview(streams.stdout());
                stderr = preview(streams.stderr());
            }
        }
        Integer exitCode = integerMetadata(result, "exit_code");
        String summary = completedSummary(invocation, result);
        if (summary == null && result.outcome() == ToolOutcome.SUCCEEDED) {
            summary = base.summary();
        }
        ToolPresentation value = new ToolPresentation(base.kind(), base.title(), status(result.outcome()),
                base.inputPreview(), preview, summary, List.of(), base.relativePaths(), base.command(),
                base.relativeCwd(), stdout, stderr,
                exitCode, durationMs, sourceTruncated || previewTruncated, artifactId, null,
                result.outcome() == ToolOutcome.SUCCEEDED ? result.media() : List.of());
        return new Completed(value, artifact);
    }

    /**
     * 仅把 Core 生成的安全 JSON Pointer 映射为字段路径，再让完整诊断沿用统一脱敏规则。
     */
    private static String sanitizeResultContent(AgentTool.ToolResult result, Path root, List<String> knownSecrets) {
        String content = result.content();
        if (!"TOOL_ARGUMENTS_INVALID".equals(result.errorCode())) {
            return sanitize(content, root, knownSecrets);
        }
        Matcher diagnostic = SAFE_ARGUMENT_DIAGNOSTIC.matcher(content);
        if (!diagnostic.matches() || !isSafeJsonPointer(diagnostic.group("location"))
                || !matchesArgumentDetail(diagnostic.group("keyword"), diagnostic.group("detail"))) {
            return sanitize(content, root, knownSecrets);
        }

        String location = diagnostic.group("location");
        String fieldPath = location.equals("$") ? "$" : "$" + location.replace('/', '.');
        String normalized = content.substring(0, diagnostic.start("location")) + fieldPath
                + content.substring(diagnostic.end("location"));
        return sanitize(normalized, root, knownSecrets);
    }

    /**
     * 只接纳 Schema 校验器的根位置或以斜杠开头的 JSON Pointer，避免把相似自由文本解释成字段路径。
     */
    private static boolean isSafeJsonPointer(String location) {
        return location.equals("$") || location.startsWith("/");
    }

    /**
     * 约束关键字与详情文案必须沿用 Schema 校验器的固定映射，避免把伪造文本当成可保留诊断。
     */
    private static boolean matchesArgumentDetail(String keyword, String detail) {
        String expected = switch (keyword) {
            case "required" -> "a required field is missing";
            case "type" -> "the JSON type is incorrect";
            case "additionalProperties" -> "an undeclared field is present";
            case "minimum", "exclusiveMinimum", "maximum", "exclusiveMaximum", "multipleOf",
                 "minLength", "maxLength", "pattern", "format", "enum", "const", "uniqueItems",
                 "minItems", "maxItems", "minProperties", "maxProperties" ->
                    "the declared value constraint is not satisfied";
            default -> "the declared schema constraint is not satisfied";
        };
        return expected.equals(detail);
    }

    /**
     * 澄清答案投影保留可读问题/答案条目，但不把自由答案复制进通用输出预览或 artifact。
     */
    private static Completed completedClarification(AgentTool.Invocation invocation, AgentTool.ToolResult result,
                                                    Path workspaceRoot, List<String> knownSecrets,
                                                    Long durationMs) {
        List<InteractionQuestion> questions = InteractionToolFactory.parseQuestions(invocation.arguments());
        List<InteractionAnswer> answers = clarificationAnswers(result, questions);
        Path root = normalizedRoot(workspaceRoot);
        List<ToolPresentation.InteractionAnswerView> views = new java.util.ArrayList<>(questions.size());
        for (InteractionQuestion question : questions) {
            InteractionAnswer answer = answers.stream()
                    .filter(value -> value.questionId().equals(question.questionId())).findFirst().orElseThrow();
            List<String> labels = answer.skipped() ? List.of() : answer.optionIds().stream()
                    .map(optionId -> question.options().stream()
                            .filter(option -> option.optionId().equals(optionId)).findFirst().orElseThrow().label())
                    .map(value -> sanitize(value, root, knownSecrets)).collect(java.util.stream.Collectors.toCollection(
                            java.util.ArrayList::new));
            if (!answer.skipped() && answer.freeText() != null) {
                labels.add(sanitize(answer.freeText(), root, knownSecrets));
            }
            views.add(new ToolPresentation.InteractionAnswerView(
                    sanitize(question.prompt(), root, knownSecrets), labels, answer.skipped()));
        }
        ToolPresentation base = prepared(invocation, workspaceRoot, knownSecrets);
        ToolPresentation presentation = new ToolPresentation(base.kind(), base.title(), ToolPresentation.Status.SUCCESS,
                base.inputPreview(), null, "已回答 " + answers.size() + " 个澄清问题", views,
                base.relativePaths(), base.command(), base.relativeCwd(), null, null, null, durationMs, false, null);
        return new Completed(presentation, "");
    }

    /**
     * 结构化答案必须与冻结问题一一对应，未知 ID、额外字段和不完整答案都阻断错误展示。
     */
    private static List<InteractionAnswer> clarificationAnswers(AgentTool.ToolResult result,
                                                                List<InteractionQuestion> questions) {
        JsonObject payload = result.structuredContent().filter(JsonObject.class::isInstance)
                .map(JsonObject.class::cast)
                .orElseThrow(() -> new IllegalArgumentException("clarification result is missing structured answers"));
        if (!payload.members().keySet().equals(Set.of("answers"))
            || !(payload.get("answers") instanceof JsonArray(List<JsonValue> values1))
            || values1.size() != questions.size()) {
            throw new IllegalArgumentException("clarification result does not match its question batch");
        }
        List<InteractionAnswer> answers = values1.stream().map(value -> {
            if (!(value instanceof JsonObject item)
                || !item.members().keySet().equals(Set.of("questionId", "optionIds", "freeText", "skipped"))
                || !(item.get("questionId") instanceof JsonText(String value3))
                || !(item.get("optionIds") instanceof JsonArray(List<JsonValue> values))
                || !(item.get("skipped") instanceof JsonBoolean(boolean value2))
                || !(item.get("freeText") instanceof JsonText || item.get("freeText") == JsonNull.INSTANCE)) {
                throw new IllegalArgumentException("clarification answer is invalid");
            }
            List<String> optionIds = values.stream().map(option -> {
                if (!(option instanceof JsonText(String value1))) {
                    throw new IllegalArgumentException("clarification option identity is invalid");
                }
                return value1;
            }).toList();
            String freeText = item.get("freeText") instanceof JsonText(String value1) ? value1 : null;
            return new InteractionAnswer(value3, optionIds, freeText, value2);
        }).toList();
        InteractionQuestion.validateCompleteAnswers(questions, answers);
        return answers;
    }

    /**
     * 把 Tool outcome 映射到客户端固定状态，不泄漏内部 ToolState。
     */
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
        if (result.outcome() != ToolOutcome.SUCCEEDED) {
            return null;
        }
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
        if (lines == null || totalLines == null) {
            return null;
        }
        Long nextOffset = nonNegativeLongMetadata(result, "nextOffset");
        String summary = "已读取 " + lines + " 行，共 " + totalLines + " 行";
        if (nextOffset != null && nextOffset > 0) {
            return summary + "；可从第 " + nextOffset + " 行继续";
        }
        return booleanMetadata(result, "truncated") ? summary + "；结果受上限限制" : summary + "；已到末尾";
    }

    /**
     * 搜索与目录 Tool 的结果数和 partial 状态来自原生执行 metadata；预览自身的十行截断不等同于搜索不完整，
     * 因此不会误导用户或模型认为需要重复搜索。
     */
    private static String discoverySummary(AgentTool.ToolResult result, String noun) {
        Long count = nonNegativeLongMetadata(result, "resultCount");
        if (count == null) {
            return null;
        }
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

    /**
     * 只为三个内置 Plan 工具生成折叠业务摘要；opaque 身份留在既有脱敏参数详情，不能冒充用户目标。
     */
    private static String planSummary(String toolName, JsonObject arguments, Path root,
                                      List<String> knownSecrets) {
        return switch (toolName) {
            case "plan_propose", "plan_draft_update" -> planObjectiveSummary(arguments, root, knownSecrets);
            case "plan_step_update" -> planStepStatusSummary(arguments);
            default -> null;
        };
    }

    /**
     * 目标只取定义中的首个非空行，并复用路径、凭据清理和字符上限，避免完整草稿进入摘要。
     */
    private static String planObjectiveSummary(JsonObject arguments, Path root, List<String> knownSecrets) {
        if (!(arguments.get("definition") instanceof JsonObject definition)) {
            return null;
        }
        String objective = text(definition, "objective");
        if (objective == null || objective.isBlank()) {
            return null;
        }
        String firstLine = sanitize(objective, root, knownSecrets).lines()
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .findFirst()
                .orElse("");
        return firstLine.isEmpty() ? null : boundedScalar("目标：" + firstLine);
    }

    /**
     * 步骤摘要描述请求的状态迁移而非 Tool 是否执行成功；未知枚举不回显，完整安全参数仍可展开查看。
     */
    private static String planStepStatusSummary(JsonObject arguments) {
        String expected = planStepStatusLabel(text(arguments, "expectedStatus"));
        String requested = planStepStatusLabel(text(arguments, "status"));
        return expected == null || requested == null
                ? null : "请求步骤状态：" + expected + " → " + requested;
    }

    /**
     * 仅把 Plan 契约接受的步骤状态映射为中文，不让未识别参数变成可信的用户展示。
     */
    private static String planStepStatusLabel(String status) {
        if (status == null) {
            return null;
        }
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "PENDING" -> "待处理";
            case "READY" -> "待执行";
            case "RUNNING" -> "执行中";
            case "BLOCKED" -> "受阻";
            case "SUCCEEDED" -> "已完成";
            case "FAILED" -> "失败";
            case "SKIPPED" -> "已跳过";
            default -> null;
        };
    }

    /**
     * 当前进程工具共用 SHELL 展示以接入会话详情；只读工具共用 READ，未知扩展才使用通用 MCP 展示。
     */
    private static ToolPresentation.Kind kind(String name) {
        return switch (name) {
            case "read", "read_attachment", "view_image", "grep", "find", "ls", "list_processes" ->
                    ToolPresentation.Kind.READ;
            case "edit" -> ToolPresentation.Kind.EDIT;
            case "write" -> ToolPresentation.Kind.WRITE;
            case "exec_command", "write_stdin", "stop_process" -> ToolPresentation.Kind.SHELL;
            default -> ToolPresentation.Kind.MCP;
        };
    }

    /**
     * 内置动作使用中文标题；仅未知 MCP 保留原名，已知 Plan Tool 不把内部接口标识暴露成操作文案。
     */
    private static String title(ToolPresentation.Kind kind, String name) {
        return switch (kind) {
            case READ -> switch (name) {
                case "grep" -> "搜索内容";
                case "find" -> "查找文件";
                case "ls" -> "列出目录";
                case "read_attachment" -> "读取附件";
                case "view_image" -> "查看图片";
                case "list_processes" -> "查看命令会话";
                default -> "读取";
            };
            case EDIT -> "编辑";
            case WRITE -> "写入";
            case SHELL -> switch (name) {
                case "write_stdin" -> "继续命令";
                case "stop_process" -> "停止命令";
                default -> "执行命令";
            };
            case MCP -> switch (name) {
                case "plan_propose" -> "提交方案修订";
                case "plan_draft_update" -> "更新方案草稿";
                case "plan_step_update" -> "更新执行步骤";
                default -> name;
            };
        };
    }

    /**
     * 按真实只读 Tool 保留最能说明动作的首个目标，并把敏感值限制在脱敏摘要内。
     */
    private static String readInput(String toolName, JsonObject arguments, List<String> paths,
                                    Path root, List<String> knownSecrets) {
        return switch (toolName) {
            case "grep", "find" -> searchInput("pattern", arguments, paths, root, knownSecrets);
            case "ls" -> paths.isEmpty() ? "." : paths.getFirst();
            case "read_attachment" -> attachmentInput(arguments, root, knownSecrets);
            case "list_processes" -> "当前会话";
            default -> fileReadInput(arguments, paths);
        };
    }

    /** 会话身份只用于内部关联，进程控制摘要不复制恢复句柄或 stdin 正文到用户界面。 */
    private static String processInput(JsonObject arguments) {
        String sessionId = text(arguments, "session_id");
        return sessionId == null ? "未指定命令会话" : "后台命令";
    }

    /** 非根工作目录复用文件路径投影，避免命令详情暴露宿主绝对路径或错误地标为 Workspace 根。 */
    private static String processCwd(JsonObject arguments, Path root) {
        String workdir = text(arguments, "workdir");
        if (workdir == null) {
            return ".";
        }
        List<String> paths = relativePaths(new JsonObject(Map.of("path", new JsonText(workdir))), root);
        return paths.isEmpty() ? "." : paths.getFirst();
    }

    /**
     * 普通 read 只展示相对路径与一基行范围，保持既有文件定位语义。
     */
    private static String fileReadInput(JsonObject arguments, List<String> paths) {
        String path = paths.isEmpty() ? "[resource]" : paths.getFirst();
        Long offset = number(arguments, "offset");
        Long limit = number(arguments, "limit");
        if (offset == null && limit == null) {
            return path;
        }
        return path + " · " + (offset == null ? 1 : offset) + ":" + (limit == null ? 2_000 : limit);
    }

    /**
     * grep/find 的首个目标是 query/pattern，路径仅作为搜索范围附在摘要后。
     */
    private static String searchInput(String field, JsonObject arguments, List<String> paths,
                                      Path root, List<String> knownSecrets) {
        String rawTarget = text(arguments, field);
        String target = rawTarget == null || rawTarget.isEmpty()
                ? "[missing-" + field + "]"
                : escapePreview(boundedScalar(sanitize(rawTarget, root, knownSecrets)));
        String scope = paths.isEmpty() ? "." : paths.getFirst();
        return field + "=\"" + target + "\" · " + scope;
    }

    /**
     * read_attachment 保留受管 opaque ID 和字节窗口，避免退化成无法诊断的 resource 占位。
     */
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

    /**
     * 写操作只发布字符计数，避免源代码、密钥或完整补丁复制进展示存储。
     */
    private static String mutationInput(String operation, JsonObject arguments, List<String> paths, String... keys) {
        StringBuilder result = new StringBuilder(paths.isEmpty() ? "[external-path]" : paths.getFirst());
        for (String key : keys) {
            String value = text(arguments, key);
            if (value != null) {
                result.append(" · ").append(key).append('=').append(value.length()).append(" chars");
            }
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
        return edits instanceof JsonArray(List<JsonValue> values) ? values.size() : 0;
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
     * 穷举 sealed JsonValue，敏感键在访问其值之前即替换；共享剩余节点预算防止宽树通过每层重置上限，
     * 数字在格式化前受长度约束，避免极端 BigDecimal scale 绕过最终预览上限造成瞬时扩展。
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
                        if (written > 0) {
                            target.append(", ");
                        }
                        target.append('…');
                        break;
                    }
                    if (written++ > 0) {
                        target.append(", ");
                    }
                    String key = boundedScalar(sanitize(entry.getKey(), root, knownSecrets));
                    target.append(escapePreview(key)).append(": ");
                    if (secretKey(entry.getKey())) {
                        target.append("[REDACTED]");
                    }
                    else {
                        appendJsonPreview(target, entry.getValue(), root, knownSecrets, depth + 1, remaining);
                    }
                }
                target.append('}');
            }
            case JsonArray array -> {
                target.append('[');
                int written = 0;
                for (JsonValue member : array.values()) {
                    if (remaining[0]-- <= 0) {
                        if (written > 0) {
                            target.append(", ");
                        }
                        target.append('…');
                        break;
                    }
                    if (written++ > 0) {
                        target.append(", ");
                    }
                    appendJsonPreview(target, member, root, knownSecrets, depth + 1, remaining);
                }
                target.append(']');
            }
            case JsonText text -> target.append('"').append(escapePreview(
                    boundedScalar(sanitize(text.value(), root, knownSecrets)))).append('"');
            case JsonNumber number -> appendNumberPreview(target, number.value());
            case JsonBoolean bool -> target.append(bool.value());
            case JsonNull ignored -> target.append("null");
        }
    }

    /**
     * 普通数值保持精确 plain 展示；超长 scale 改用紧凑指数，超长精度只显示位数，避免分配展开正文。
     */
    private static void appendNumberPreview(StringBuilder target, BigDecimal value) {
        long plainCharacters;
        int precision = value.precision();
        int scale = value.scale();
        if (scale <= 0) {
            plainCharacters = (long) precision - scale;
        } else if (scale >= precision) {
            plainCharacters = (long) scale + 2;
        } else {
            plainCharacters = (long) precision + 1;
        }
        if (value.signum() < 0) {
            plainCharacters++;
        }
        if (plainCharacters <= MCP_VALUE_CHARACTERS) {
            target.append(value.toPlainString());
        } else if (precision <= MCP_VALUE_CHARACTERS - 16) {
            target.append(value.toString());
        } else {
            target.append("[number omitted: ").append(precision).append(" digits]");
        }
    }

    /**
     * 单个 MCP 标量只保留固定 UTF-16 上限，并避免在代理项中间截断。
     */
    private static String boundedScalar(String value) {
        if (value.length() <= MCP_VALUE_CHARACTERS) {
            return value;
        }
        int end = MCP_VALUE_CHARACTERS;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end) + '…';
    }

    /**
     * 转义会破坏 JSON-like 预览结构的字符，控制字符已在 sanitize 中统一清理。
     */
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
        if (raw == null) {
            return List.of();
        }
        if (raw.startsWith("skill://")) {
            return List.of(skillPreview(raw));
        }
        if (raw.startsWith("ja-artifact://")) {
            return List.of("[resource]");
        }
        try {
            Path candidate = Path.of(raw);
            Path absolute = (candidate.isAbsolute() ? candidate : root.resolve(candidate)).toAbsolutePath().normalize();
            if (!absolute.startsWith(root)) {
                return List.of("[external-path]");
            }
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

    /**
     * 统一清理 ANSI、危险控制字符、常见凭据和本地绝对路径。
     */
    public static String sanitize(String value, Path workspaceRoot, List<String> knownSecrets) {
        if (value == null || value.isEmpty()) {
            return "";
        }
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
            if (secret == null || secret.isEmpty()) {
                continue;
            }
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

    /**
     * Windows 路径比较不区分大小写，普通 replace 不能安全覆盖这一点。
     */
    private static String replaceIgnoreCase(String value, String target, String replacement) {
        return Pattern.compile(Pattern.quote(target), Pattern.CASE_INSENSITIVE).matcher(value)
                .replaceAll(java.util.regex.Matcher.quoteReplacement(replacement));
    }

    /**
     * 结果预览固定保留前十行及字符上限，完整脱敏正文交给 artifact 分页。
     */
    private static String preview(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        List<String> lines = value.lines().limit(PREVIEW_LINES).toList();
        String retained = String.join("\n", lines);
        if (retained.length() <= PREVIEW_CHARACTERS) {
            return retained;
        }
        int end = PREVIEW_CHARACTERS;
        if (Character.isHighSurrogate(retained.charAt(end - 1))) {
            end--;
        }
        return retained.substring(0, end);
    }

    /**
     * Shell 兼容既有分区格式，并在无标签时把内容视为 stdout。
     */
    private static Streams streams(String value) {
        if (value.startsWith("[stderr]\n")) {
            return new Streams("", value.substring(9));
        }
        if (!value.startsWith("[stdout]\n")) {
            return new Streams(value, "");
        }
        int separator = value.indexOf("\n[stderr]\n", 9);
        if (separator < 0) {
            return new Streams(value.substring(9), "");
        }
        return new Streams(value.substring(9, separator), value.substring(separator + 10));
    }

    /**
     * 从强类型 metadata 读取布尔值，缺失时保持 false 而不伪造 Tool 提供的事实。
     */
    private static boolean booleanMetadata(AgentTool.ToolResult result, String key) {
        if (result.structuredContent().orElse(null) instanceof JsonObject object
            && object.members().get(key) instanceof JsonBoolean value) {
                return value.value();
            }
        return false;
    }

    /**
     * 从强类型 metadata 读取精确 int；缺失或溢出时不对外提供字段。
     */
    private static Integer integerMetadata(AgentTool.ToolResult result, String key) {
        if (result.structuredContent().orElse(null) instanceof JsonObject object
            && object.members().get(key) instanceof JsonNumber(java.math.BigDecimal value1)) {
            try {
                return value1.intValueExact();
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
            && object.members().get(key) instanceof JsonNumber(java.math.BigDecimal value1)) {
            try {
                long number = value1.longValueExact();
                return number >= 0 ? number : null;
            } catch (ArithmeticException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 读取 JSON 文本成员，缺失或非文本保持缺失。
     */
    private static String text(JsonObject object, String key) {
        return object.members().get(key) instanceof JsonText(String value1) ? value1 : null;
    }

    /**
     * 读取 JSON 整数成员，非精确长整数保持缺失。
     */
    private static Long number(JsonObject object, String key) {
        if (!(object.members().get(key) instanceof JsonNumber(java.math.BigDecimal value1))) {
            return null;
        }
        try {
            return value1.longValueExact();
        } catch (ArithmeticException ignored) {
            return null;
        }
    }

    /**
     * 敏感键使用大小写不敏感、去分隔符比较，覆盖常见 Provider 与 HTTP 命名。
     */
    private static boolean secretKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return normalized.contains("token") || normalized.contains("password")
               || normalized.contains("secret") || normalized.contains("apikey")
               || normalized.equals("authorization") || normalized.contains("cookie");
    }

    /**
     * 所有路径投影使用绝对规范根，拒绝依赖进程 cwd。
     */
    private static Path normalizedRoot(Path value) {
        return Objects.requireNonNull(value, "workspaceRoot").toAbsolutePath().normalize();
    }

    /**
     * 完成投影携带已经脱敏的 artifact 正文；正文只允许进入受约束 artifact 表。
     *
     * @param presentation 展示信息
     * @param artifactContent 产物内容
     */
    public record Completed(ToolPresentation presentation,
                            String artifactContent) {
        /**
         * 确保 artifact identity 与正文存在性严格配对。
         */
        public Completed {
            Objects.requireNonNull(presentation, "presentation");
            artifactContent = Objects.requireNonNull(artifactContent, "artifactContent");
            if ((presentation.artifactId() == null) != artifactContent.isEmpty()) {
                throw new IllegalArgumentException("artifact identity and content must be paired");
            }
        }
    }

    /**
     * Shell 两个流保持独立，避免客户端再解析普通输出。
     *
     * @param stdout 进程标准输出
     * @param stderr 进程标准错误输出
     */
    private record Streams(String stdout,
                           String stderr) {
    }
}
