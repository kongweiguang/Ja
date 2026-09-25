// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.AbstractStreamingModelAdapter;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.RetryAfter;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.Function;

/** 收敛 OpenAI 两种原生 API 真正相同的 HTTP 与请求尾部语义。 */
final class OpenAiProviderSupport {
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final String CONTEXT_LENGTH_EXCEEDED = "context_length_exceeded";
    private static final Set<String> SAFE_ERROR_CODES = Set.of(
            "invalid_value", "invalid_type", "missing_required_parameter", "unknown_parameter",
            "unsupported_parameter", "unsupported_value", "context_length_exceeded", "model_not_found",
            "rate_limit_exceeded", "server_error", "upstream_error", "insufficient_quota",
            "invalid_api_key", "authentication_error", "permission_denied", "request_too_large");
    private static final Set<String> SAFE_ERROR_TYPES = Set.of(
            "invalid_request_error", "authentication_error", "permission_error", "rate_limit_error",
            "api_error", "server_error");
    private static final Set<String> SAFE_ERROR_PARAMS = Set.of(
            "model", "instructions", "input", "input[]", "input[].type", "input[].role", "input[].status",
            "input[].call_id", "input[].name", "input[].arguments", "input[].output", "input[].content",
            "input[].content[]", "input[].content[].type", "input[].content[].text",
            "input[].content[].image_url", "input[].content[].detail", "input[].content[].filename",
            "input[].content[].file_data", "tools", "tools[]", "tools[].type", "tools[].name",
            "tools[].description", "tools[].parameters", "tools[].strict", "tool_choice",
            "parallel_tool_calls", "stream", "store", "include", "reasoning", "reasoning.effort",
            "reasoning.summary", "temperature", "top_p", "max_output_tokens");
    private static final Pattern ARRAY_INDEX = Pattern.compile("\\[(?:0|[1-9][0-9]{0,5})\\]");

    /** 禁止实例化无状态支持边界，Provider 专属 message/input 编码仍留在各自 Codec。 */
    private OpenAiProviderSupport() {
    }

    /**
     * 构造带 SSE 协商的受限 POST；仅托管 OpenAI 共同 Header 和凭据规则，不解释 endpoint 后缀。
     */
    static Request ssePost(ModelPort.ModelConfiguration configuration, byte[] body, String suffix) {
        Request.Builder builder = new Request.Builder()
                .url(AbstractStreamingModelAdapter.endpoint(configuration.baseUri(), suffix))
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body, JSON_MEDIA_TYPE));
        builder.header("Authorization", "Bearer " + configuration.apiKey());
        return builder.build();
    }

    /**
     * 映射 OpenAI 共同的强类型溢出、重试和脱敏 HTTP 错误；诊断只接受固定 code/type 与已知请求字段。
     */
    static RuntimeException serviceFailure(int status, Headers headers, JsonNode error) {
        if (status == 400 && error != null
            && CONTEXT_LENGTH_EXCEEDED.equals(error.path("error").path("code").textValue())) {
            return new ModelPort.ContextOverflowException(null);
        }
        boolean retryable = status == 408 || status == 409 || status == 429
                || status >= 500 && status <= 599;
        return new ProviderProtocolException(
                "HTTP_STATUS", serviceFailureDetail(status, error), retryable,
                RetryAfter.parse(headers.get("Retry-After")),
                retryable ? "MODEL_UNAVAILABLE" : "MODEL_UPSTREAM_REJECTED");
    }

    /**
     * HTTP 400 的 code/param 用于定位拒绝字段，但 Provider 自由文本、URL、请求正文与凭据不得进入诊断。
     */
    private static String serviceFailureDetail(int status, JsonNode root) {
        StringBuilder detail = new StringBuilder("provider returned HTTP status ").append(status);
        if (root == null) return detail.toString();
        JsonNode error = root.path("error");
        appendAllowlisted(detail, "code", error.path("code").textValue(), SAFE_ERROR_CODES);
        appendAllowlisted(detail, "type", error.path("type").textValue(), SAFE_ERROR_TYPES);
        if (status == 400) appendSafeParameter(detail, error.path("param").textValue());
        return detail.toString();
    }

    /** 缺失或非文本的上游字段按未知值忽略；只输出固定闭集内的分类，不回显自由文本。 */
    private static void appendAllowlisted(StringBuilder detail, String label, String value, Set<String> allowed) {
        if (value != null && allowed.contains(value)) detail.append(' ').append(label).append(' ').append(value);
    }

    /** 将数组索引折叠为通配符后再做闭集匹配，不把任意上游字符串拼进异常或日志。 */
    private static void appendSafeParameter(StringBuilder detail, String value) {
        if (value == null || value.length() > 128) return;
        String normalized = ARRAY_INDEX.matcher(value).replaceAll("[]");
        if (SAFE_ERROR_PARAMS.contains(normalized)) detail.append(" param ").append(normalized);
    }

    /**
     * 在各 Codec 完成专属 generation 与 message/input 后统一附加 Tool 和流控制字段；
     * Tool mapper 保留两种 OpenAI API 不同的 function 包装层。
     */
    static void applyToolsAndStreaming(ObjectNode root, List<ToolSpec> tools,
                                       Function<ToolSpec, ObjectNode> toolMapper) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(toolMapper, "toolMapper");
        if (!tools.isEmpty()) {
            ArrayNode encodedTools = root.putArray("tools");
            tools.forEach(tool -> encodedTools.add(toolMapper.apply(tool)));
            root.put("tool_choice", "auto");
            root.put("parallel_tool_calls", true);
        }
        root.put("stream", true);
    }
}
