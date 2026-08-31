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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 为受控的内置 Tool 集统一参数防御、取消传播和稳定失败映射。
 */
abstract class ToolSupport implements AgentTool {
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
        } catch (java.util.concurrent.CancellationException failure) {
            return CompletableFuture.completedFuture(new ToolResult(
                    ToolOutcome.CANCELLED, "", Optional.empty(), "tool_cancelled"));
        } catch (SecurityException failure) {
            return CompletableFuture.completedFuture(new ToolResult(
                    ToolOutcome.FAILED, "", Optional.empty(), "tool_access_denied"));
        } catch (IllegalArgumentException failure) {
            return CompletableFuture.completedFuture(new ToolResult(
                    ToolOutcome.FAILED, "", Optional.empty(), "tool_arguments_invalid"));
        } catch (Exception failure) {
            return CompletableFuture.completedFuture(new ToolResult(
                    ToolOutcome.FAILED, "", Optional.empty(), "tool_execution_failed"));
        }
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
}
