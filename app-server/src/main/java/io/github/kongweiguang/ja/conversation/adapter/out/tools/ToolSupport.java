// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonNumber;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.foundation.json.JsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 为受控的内置 Tool 集统一参数防御、取消传播和稳定失败映射。
 */
abstract class ToolSupport implements AgentTool {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolSupport.class);
    private final ToolSpec spec;

    /**
     * 在构造时冻结模型可见契约，避免单次调用篡改调度或权限元数据。
     */
    ToolSupport(ToolSpec spec) {
        this.spec = Objects.requireNonNull(spec, "spec");
    }

    /**
     * 返回构造时冻结的定义，不向调用方暴露可变副本。
     */
    @Override
    public final ToolSpec spec() {
        return spec;
    }

    /**
     * 在调度器选定的虚拟线程中同步执行，并把所有失败归一为稳定 Tool 结果。
     */
    @Override
    public final CompletionStage<ToolResult> execute(
            Invocation invocation, ExecutionContext context, CancellationToken cancellationToken) {
        try {
            cancellationToken.throwIfCancellationRequested();
            return CompletableFuture.completedFuture(executeChecked(invocation, context, cancellationToken));
        } catch (MutationObservationException failure) {
            return CompletableFuture.completedFuture(failedWithDiagnostic(
                    failure.failure(), failure, Optional.of(failure.observation())));
        } catch (ToolFailure failure) {
            return CompletableFuture.completedFuture(failed(ToolOutcome.FAILED, failure.failure()));
        } catch (ArgumentFailure failure) {
            return CompletableFuture.completedFuture(failedArgument(failure));
        } catch (java.util.concurrent.CancellationException failure) {
            return CompletableFuture.completedFuture(failed(ToolOutcome.CANCELLED, Failure.TOOL_CANCELLED));
        } catch (AccessDeniedException | SecurityException failure) {
            return CompletableFuture.completedFuture(failedWithDiagnostic(Failure.TOOL_ACCESS_DENIED, failure));
        } catch (NoSuchFileException | NotDirectoryException failure) {
            return CompletableFuture.completedFuture(failed(ToolOutcome.FAILED, Failure.PATH_NOT_FOUND));
        } catch (IllegalArgumentException failure) {
            return CompletableFuture.completedFuture(failed(ToolOutcome.FAILED, Failure.TOOL_ARGUMENTS_INVALID));
        } catch (IOException failure) {
            return CompletableFuture.completedFuture(failedWithDiagnostic(Failure.TOOL_IO_FAILED, failure));
        } catch (Exception failure) {
            return CompletableFuture.completedFuture(failedWithDiagnostic(Failure.TOOL_EXECUTION_FAILED, failure));
        }
    }

    /**
     * Tool 失败必须向下一模型轮次和工作过程提供非空、脱敏的稳定原因；空结果既无法诊断，
     * 也可能被上游协议拒绝为无效 Tool continuation。
     */
    private static ToolResult failed(ToolOutcome outcome, Failure failure) {
        return new ToolResult(outcome, failure.safeContent(), Optional.empty(), failure.code());
    }

    /** 将已审计的字段和约束写入错误正文，使模型能修正参数而不会看到参数值或物理路径。 */
    private static ToolResult failedArgument(ArgumentFailure failure) {
        return new ToolResult(ToolOutcome.FAILED,
                Failure.TOOL_ARGUMENTS_INVALID.safeContent() + " Argument '" + failure.field()
                        + "' " + failure.constraint() + ".",
                Optional.empty(), Failure.TOOL_ARGUMENTS_INVALID.code());
    }

    /**
     * 为需要运维介入的故障生成关联 ID；日志只保留类型链，原始 message、参数、路径和堆栈不落盘。
     */
    private ToolResult failedWithDiagnostic(Failure failure, Exception exception) {
        return failedWithDiagnostic(failure, exception, Optional.empty());
    }

    /**
     * 写后观察失败沿用脱敏诊断正文，但只把闭集原因交给 Java tracker，不向模型或日志暴露路径和正文。
     */
    private ToolResult failedWithDiagnostic(Failure failure, Exception exception,
                                            Optional<AgentTool.MutationObservationFailure> observation) {
        String diagnosticId = "diag_" + UUID.randomUUID().toString().replace("-", "");
        Throwable cause = exception.getCause();
        LOGGER.warn("Built-in Tool failure diagnosticId={} tool={} code={} type={} causeType={} origin={}",
                diagnosticId, spec.name(), failure.code(), exception.getClass().getName(),
                cause == null ? "none" : cause.getClass().getName(), safeOrigin(exception));
        return new ToolResult(ToolOutcome.FAILED,
                failure.safeContent() + " Diagnostic ID: " + diagnosticId + ".",
                Optional.empty(), failure.code(), Optional.empty(), observation);
    }

    /**
     * 只记录代码位置而不记录 StackTraceElement 文件路径或异常 message，在可定位与本机数据最小化间取平衡。
     */
    private static String safeOrigin(Exception exception) {
        StackTraceElement[] trace = exception.getStackTrace();
        StackTraceElement origin = java.util.Arrays.stream(trace)
                .filter(frame -> frame.getClassName().startsWith("io.github.kongweiguang.ja."))
                .findFirst().orElse(trace.length == 0 ? null : trace[0]);
        return origin == null ? "unknown"
                : origin.getClassName() + "#" + origin.getMethodName() + ":" + origin.getLineNumber();
    }

    /**
     * 允许具体 Tool 抛出已审计的闭集失败，而不是把文件路径、账户名或平台异常正文带入模型上下文。
     */
    static ToolFailure failure(Failure failure) {
        return new ToolFailure(failure);
    }

    /** 创建只携带固定字段名和约束的参数异常，避免底层异常 message 穿过 Tool 边界。 */
    static ArgumentFailure argument(String field, String constraint) {
        return new ArgumentFailure(field, constraint);
    }

    /**
     * 写入开始后的 SecurityException 与 IOException 分别归为 outside_workspace 和 capture_failed；
     * 原始异常只保留为进程内 cause，公共边界不会复制其 message。
     */
    static MutationObservationException mutationObservation(
            AgentTool.MutationObservationFailure observation, Exception cause) {
        Failure failure = observation == AgentTool.MutationObservationFailure.OUTSIDE_WORKSPACE
                ? Failure.TOOL_ACCESS_DENIED : Failure.TOOL_IO_FAILED;
        return new MutationObservationException(observation, failure, cause);
    }

    /**
     * 每个 Tool 只实现自身有界副作用，异常脱敏与取消分类由公共 adapter 统一承担。
     */
    abstract ToolResult executeChecked(
            Invocation invocation, ExecutionContext context, CancellationToken cancellationToken) throws Exception;

    /**
     * Schema 校验后再次要求非空有界字符串，防止校验器被绕过时扩大输入面。
     */
    static String string(Invocation invocation, String name, int maxLength) {
        JsonValue value = invocation.arguments().members().get(name);
        if (!(value instanceof JsonText jsonText)
            || jsonText.value().isBlank()
            || jsonText.value().codePointCount(0, jsonText.value().length()) > maxLength) {
            throw argument(name, "must be a non-empty string within the published length limit");
        }
        return jsonText.value();
    }

    /** 读取允许空白字符但不允许空文本的字段，和 JSON Schema minLength=1 保持同一语义。 */
    static String requiredString(Invocation invocation, String name, int maxLength, String constraint) {
        JsonValue value = invocation.arguments().members().get(name);
        if (!(value instanceof JsonText jsonText)
                || jsonText.value().isEmpty()
                || jsonText.value().codePointCount(0, jsonText.value().length()) > maxLength) {
            throw argument(name, constraint);
        }
        return jsonText.value();
    }

    /**
     * 读取可选字符串，避免把缺失的 CAS 哈希转换为字面量 null。
     */
    static String optionalString(Invocation invocation, String name, int maxLength) {
        JsonValue value = invocation.arguments().members().get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof JsonText text)
                || text.value().codePointCount(0, text.value().length()) > maxLength) {
            throw argument(name, "must be omitted or be a string within the published length limit");
        }
        return text.value();
    }

    /**
     * 即使上游 Schema 校验意外被绕过，也只接受精确整数并执行硬范围限制。
     */
    static int integer(Invocation invocation, String name, int defaultValue, int min, int max) {
        JsonValue value = invocation.arguments().members().get(name);
        int result = value == null ? defaultValue : exactInteger(value, name);
        if (result < min || result > max) {
            throw argument(name, "must be an integer between " + min + " and " + max);
        }
        return result;
    }

    /**
     * 将 Number 通过十进制文本精确收窄，拒绝小数、溢出、NaN 和 Infinity。
     */
    private static int exactInteger(JsonValue value, String name) {
        if (!(value instanceof JsonNumber number)) {
            throw argument(name, "must be an integer");
        }
        try {
            return number.value().intValueExact();
        } catch (ArithmeticException failure) {
            throw argument(name, "must be an integer");
        }
    }

    /**
     * 构造 Provider adapter 与聚焦测试共享的最小 JSON Schema 对象结构。
     */
    static JsonObject objectSchema(Map<String, JsonObject> properties, List<String> required) {
        List<JsonValue> requiredValues = required.stream().map(JsonText::new).map(JsonValue.class::cast).toList();
        return JsonObjects.builder()
                .putText("type", "object")
                .put("properties", new JsonObject(properties))
                .put("required", new JsonArray(requiredValues))
                .putBoolean("additionalProperties", false)
                .build();
    }

    /**
     * 复用基础属性 Schema 的构造，同时保持返回 Map 不可变。
     */
    static JsonObject property(String type, String description) {
        return JsonObjects.builder().putText("type", type).putText("description", description).build();
    }

    /** 构造 Schema 与 requiredString 共用的非空有界字符串属性。 */
    static JsonObject requiredStringProperty(String description, int maxLength) {
        return JsonObjects.builder().putText("type", "string").putText("description", description)
                .putNumber("minLength", 1).putNumber("maxLength", maxLength).build();
    }

    /** 构造 Schema 与 optionalString 共用的可空文本属性；字段是否必需由对象 required 决定。 */
    static JsonObject optionalStringProperty(String description, int maxLength) {
        return JsonObjects.builder().putText("type", "string").putText("description", description)
                .putNumber("maxLength", maxLength).build();
    }

    /** 构造 Schema 与 integer 共用的精确整数属性，避免声明边界和运行时范围漂移。 */
    static JsonObject integerProperty(String description, int min, int max) {
        return JsonObjects.builder().putText("type", "integer").putText("description", description)
                .putNumber("minimum", min).putNumber("maximum", max).build();
    }

    /** 读取有界普通文本并在每个 chunk 检查取消与 Deadline；NOFOLLOW 防止重解析点绕过准入。 */
    static String readUtf8File(Path path, CancellationToken token, Instant deadline,
                               long maxBytes, int maxCharacters) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(deadline, "deadline");
        if (maxBytes < 1 || maxBytes > Integer.MAX_VALUE || maxCharacters < 1) {
            throw new IllegalArgumentException("bounded reader limits are invalid");
        }
        token.throwIfCancellationRequested();
        throwIfDeadlineExceeded(deadline);
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (attributes.isDirectory()) throw failure(Failure.PATH_IS_DIRECTORY);
        if (!attributes.isRegularFile() || Files.isSymbolicLink(path)) {
            throw failure(Failure.PATH_NOT_REGULAR_FILE);
        }
        if (attributes.size() > maxBytes) throw failure(Failure.FILE_TOO_LARGE);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                (int) Math.min(attributes.size(), 8_192L));
        byte[] buffer = new byte[8_192];
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            long remainingBytes = maxBytes;
            while (remainingBytes > 0) {
                token.throwIfCancellationRequested();
                throwIfDeadlineExceeded(deadline);
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remainingBytes));
                if (read < 0) break;
                if (read == 0) continue;
                bytes.write(buffer, 0, read);
                remainingBytes -= read;
            }
        }
        BasicFileAttributes finalAttributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (finalAttributes.size() > maxBytes) throw failure(Failure.FILE_TOO_LARGE);
        return decodeUtf8(bytes.toByteArray(), maxCharacters);
    }

    /** 将超时转换成文件工具可识别的局部截断信号，而不是泄露底层时间实现。 */
    private static void throwIfDeadlineExceeded(Instant deadline) throws DeadlineExceededException {
        if (!Instant.now().isBefore(deadline)) throw new DeadlineExceededException();
    }

    /** 使用 REPORT 解码并拒绝 NUL，避免二进制载荷伪装成搜索文本。 */
    private static String decodeUtf8(byte[] bytes, int maxCharacters) throws IOException {
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalidEncoding) {
            throw failure(Failure.FILE_NOT_UTF8);
        }
        if (content.codePointCount(0, content.length()) > maxCharacters) {
            throw failure(Failure.FILE_TOO_LARGE);
        }
        if (content.indexOf('\0') >= 0) throw failure(Failure.FILE_NOT_TEXT);
        return content;
    }

    /**
     * 冻结模型可操作的错误码与安全说明；说明包含下一步但不包含原始异常或物理路径。
     */
    enum Failure {
        /** 用户或上层生命周期已取消操作，重试必须由新的用户意图触发。 */
        TOOL_CANCELLED("tool_cancelled",
                "Tool cancelled: tool_cancelled. The operation stopped before completion; retry only if needed."),
        /** 当前身份或沙箱边界拒绝访问，禁止通过重复调用绕过权限。 */
        TOOL_ACCESS_DENIED("tool_access_denied",
                "Tool failed: tool_access_denied. Access was denied; choose an accessible path or request permission."),
        /** Tool 参数不满足已发布 schema 或边界约束，调用方必须先修正参数。 */
        TOOL_ARGUMENTS_INVALID("tool_arguments_invalid",
                "Tool failed: tool_arguments_invalid. Correct the arguments before retrying."),
        /** 请求路径不存在或其父级不是目录，避免继续猜测同一输入。 */
        PATH_NOT_FOUND("path_not_found",
                "Tool failed: path_not_found. The requested path does not exist; verify the path before retrying."),
        /** Read 收到目录而非文件，要求模型改用文件路径或目录专用能力。 */
        PATH_IS_DIRECTORY("path_is_directory",
                "Tool failed: path_is_directory. The read tool accepts a file, not a directory; choose a file."),
        /** 目录发现 Tool 收到普通文件，要求模型改用文件路径或省略 path。 */
        PATH_NOT_DIRECTORY("path_not_directory",
                "Tool failed: path_not_directory. The discovery tool accepts a directory; choose a directory."),
        /** Read 收到设备、管道等非普通文件，拒绝不可控或阻塞式读取。 */
        PATH_NOT_REGULAR_FILE("path_not_regular_file",
                "Tool failed: path_not_regular_file. The read tool accepts regular files only; choose a regular file."),
        /** 文件超过单次读取预算，防止内存与 Prompt 体积失控。 */
        FILE_TOO_LARGE("file_too_large",
                "Tool failed: file_too_large. The file exceeds the bounded read limit; use a smaller input."),
        /** 文件无法按严格 UTF-8 解码，禁止用替换字符伪造有效文本。 */
        FILE_NOT_UTF8("file_not_utf8",
                "Tool failed: file_not_utf8. The file is not valid UTF-8; use a binary-aware tool or convert it."),
        /** 文件包含 NUL 等二进制特征，避免把二进制载荷注入模型上下文。 */
        FILE_NOT_TEXT("file_not_text",
                "Tool failed: file_not_text. The file contains unsupported binary content; use a binary-aware tool."),
        /** 操作系统 IO 在已知参数外失败，需要诊断 ID 关联本地日志。 */
        TOOL_IO_FAILED("tool_io_failed",
                "Tool failed: tool_io_failed. The operating system could not complete the operation; verify the path and retry only after the cause changes."),
        /** 未分类执行故障的安全兜底，明确禁止无变化参数的盲目重试。 */
        TOOL_EXECUTION_FAILED("tool_execution_failed",
                "Tool failed: tool_execution_failed. The tool could not complete safely; do not repeat unchanged arguments.");

        private final String code;
        private final String safeContent;

        /** 保持错误码与安全正文一一绑定，避免调用点临时拼接泄露异常信息。 */
        Failure(String code, String safeContent) {
            this.code = code;
            this.safeContent = safeContent;
        }

        /** 返回供持久化、UI 与模型判断使用的稳定机器码。 */
        String code() {
            return code;
        }

        /** 返回不依赖平台异常文本的非空操作指引。 */
        String safeContent() {
            return safeContent;
        }
    }

    /**
     * 只承载经过审计的失败类型，禁用原始 cause/message 防止敏感文件系统事实跨越 Tool 端口。
     */
    static final class ToolFailure extends IOException {
        private final Failure failure;

        /** 仅保存闭集枚举，异常对象本身不会进入 ToolResult。 */
        private ToolFailure(Failure failure) {
            super(failure.code());
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        /** 返回对应失败枚举，公共执行边界负责生成最终安全结果。 */
        Failure failure() {
            return failure;
        }
    }

    /** 只保存已审计的字段和约束，供公共边界生成可行动但不回显输入的错误正文。 */
    static final class ArgumentFailure extends IllegalArgumentException {
        private final String field;
        private final String constraint;

        /** 校验诊断自身的字符集和长度，避免未来调用点把用户输入拼入公开错误。 */
        private ArgumentFailure(String field, String constraint) {
            super("tool_arguments_invalid");
            if (field == null || !field.matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                    || constraint == null || constraint.isBlank()
                    || constraint.codePointCount(0, constraint.length()) > 256
                    || !constraint.matches("[A-Za-z0-9 .,;:/_\\-]+")) {
                throw new IllegalArgumentException("argument diagnostic is not safe");
            }
            this.field = field;
            this.constraint = constraint;
        }

        /** 返回 Schema 中的稳定字段名，不包含参数值。 */
        String field() {
            return field;
        }

        /** 返回固定约束说明，不包含底层异常文本。 */
        String constraint() {
            return constraint;
        }
    }

    /** 有界文件读取在 Deadline 到达时中止当前候选，不把超时伪装成完整扫描。 */
    static final class DeadlineExceededException extends IOException {
        /** 使用固定 message，诊断只由调用方映射为截断元数据。 */
        private DeadlineExceededException() {
            super("tool_deadline_exceeded");
        }
    }

    /** 写后无法生成可信 receipt 的专用异常只承载闭集原因和脱敏 Tool 失败类型。 */
    static final class MutationObservationException extends IOException {
        private final AgentTool.MutationObservationFailure observation;
        private final Failure failure;

        /** cause 只供类型诊断，异常 message 固定为机器码，避免本机路径穿过端口。 */
        private MutationObservationException(AgentTool.MutationObservationFailure observation,
                                             Failure failure, Exception cause) {
            super(failure.code(), cause);
            this.observation = Objects.requireNonNull(observation, "observation");
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        /** 返回 tracker 可消费的闭集观察结果。 */
        AgentTool.MutationObservationFailure observation() {
            return observation;
        }

        /** 返回模型可见的稳定失败类型，内容仍由公共边界生成。 */
        Failure failure() {
            return failure;
        }
    }
}
