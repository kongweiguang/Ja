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

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
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
            || jsonText.value().isBlank() || jsonText.value().length() > maxLength) {
            throw new IllegalArgumentException("invalid argument: " + name);
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
        if (!(value instanceof JsonText text) || text.value().length() > maxLength) {
            throw new IllegalArgumentException("invalid argument: " + name);
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
            throw new IllegalArgumentException("invalid argument: " + name);
        }
        return result;
    }

    /**
     * 将 Number 通过十进制文本精确收窄，拒绝小数、溢出、NaN 和 Infinity。
     */
    private static int exactInteger(JsonValue value, String name) {
        if (!(value instanceof JsonNumber number)) {
            throw new IllegalArgumentException("invalid argument: " + name);
        }
        try {
            return number.value().intValueExact();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("invalid argument: " + name);
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
