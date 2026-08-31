// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.AbstractStreamingModelAdapter;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;

import java.util.List;

/**
 * 在 Agent Loop 的不透明槽位中封装 Anthropic 私有 thinking 块，避免其进入公开消息模型。
 */
final class AnthropicMessagesContinuation {
    static final String PROTOCOL = "anthropic_messages";
    private static final int MAX_STATE_CHARACTERS = 4_000_000;

    /** 禁止创建无状态续传 Codec。 */
    private AnthropicMessagesContinuation() {
    }

    /**
     * 仅序列化 Provider 要求原样回传的私有块；Tool 调用仍由公开结构化消息承载，避免双份事实源。
     */
    static ModelPort.Continuation encode(List<ObjectNode> blocks) {
        if (blocks.isEmpty()) return null;
        ObjectNode root = AbstractStreamingModelAdapter.JSON.createObjectNode();
        root.put("version", 1);
        ArrayNode encoded = root.putArray("blocks");
        blocks.forEach(block -> encoded.add(block.deepCopy()));
        try {
            String state = AbstractStreamingModelAdapter.JSON.writeValueAsString(root);
            if (state.length() > MAX_STATE_CHARACTERS) throw invalid();
            return new ModelPort.Continuation(PROTOCOL, state);
        } catch (ProviderProtocolException failure) {
            throw failure;
        } catch (JsonProcessingException failure) {
            throw invalid();
        }
    }

    /**
     * 严格解码 Ja 自己生成的不透明状态，只允许当前 Messages API 的 thinking 原生块通过。
     */
    static List<ObjectNode> decode(ModelPort.Continuation continuation) {
        if (continuation == null || !PROTOCOL.equals(continuation.protocol())
            || continuation.opaqueState().length() > MAX_STATE_CHARACTERS) {
            throw invalid();
        }
        try {
            JsonNode root = AbstractStreamingModelAdapter.JSON.readTree(continuation.opaqueState());
            if (root == null || !root.isObject() || root.size() != 2
                || root.path("version").asInt(-1) != 1 || !root.path("blocks").isArray()
                || root.path("blocks").isEmpty()) {
                throw invalid();
            }
            java.util.ArrayList<ObjectNode> blocks = new java.util.ArrayList<>();
            for (JsonNode block : root.path("blocks")) {
                if (!(block instanceof ObjectNode object)) throw invalid();
                String type = object.path("type").textValue();
                if ("thinking".equals(type)) {
                    if (object.size() != 3 || !object.path("thinking").isTextual()
                        || !object.path("signature").isTextual()
                        || object.path("signature").textValue().isEmpty()) {
                        throw invalid();
                    }
                } else if ("redacted_thinking".equals(type)) {
                    if (object.size() != 2 || !object.path("data").isTextual()
                        || object.path("data").textValue().isEmpty()) {
                        throw invalid();
                    }
                } else {
                    throw invalid();
                }
                blocks.add(object.deepCopy());
            }
            return List.copyOf(blocks);
        } catch (ProviderProtocolException failure) {
            throw failure;
        } catch (JsonProcessingException failure) {
            throw invalid();
        }
    }

    /**
     * 用稳定且脱敏的错误拒绝协议错配或畸形状态，绝不把私有 thinking 写入诊断。
     */
    private static ProviderProtocolException invalid() {
        return new ProviderProtocolException(
                "ANTHROPIC_CONTINUATION", "Anthropic continuation state is invalid", false);
    }
}
