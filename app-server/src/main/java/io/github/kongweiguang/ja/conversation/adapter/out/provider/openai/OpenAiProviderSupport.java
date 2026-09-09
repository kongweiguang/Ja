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
import java.util.function.Function;

/** 收敛 OpenAI 两种原生 API 真正相同的 HTTP 与请求尾部语义。 */
final class OpenAiProviderSupport {
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final String CONTEXT_LENGTH_EXCEEDED = "context_length_exceeded";

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
     * 映射 OpenAI 共同的强类型溢出、重试和脱敏 HTTP 错误，不读取自由文本正文。
     */
    static RuntimeException serviceFailure(int status, Headers headers, JsonNode error) {
        if (status == 400 && error != null
            && CONTEXT_LENGTH_EXCEEDED.equals(error.path("error").path("code").textValue())) {
            return new ModelPort.ContextOverflowException(null);
        }
        boolean retryable = status == 429 || status >= 500 && status <= 599;
        return new ProviderProtocolException(
                "HTTP_STATUS", AbstractStreamingModelAdapter.serviceFailureDetail(status, error), retryable,
                RetryAfter.parse(headers.get("Retry-After")));
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
