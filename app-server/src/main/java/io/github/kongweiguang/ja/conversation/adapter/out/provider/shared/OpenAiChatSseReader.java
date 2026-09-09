// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** 严格读取 OpenAI Chat Completions 的 data-only SSE 与可选的唯一 [DONE] 终止帧。 */
public final class OpenAiChatSseReader {
    private final StrictSseLineReader lines;
    private boolean done;

    /** 将字节、事件数和响应总量限制留给外层 BoundedSseInputStream。 */
    public OpenAiChatSseReader(InputStream input) {
        lines = new StrictSseLineReader(Objects.requireNonNull(input, "input"),
                "OPENAI_CHAT_EVENT", "OpenAI Chat SSE is not valid UTF-8");
    }

    /** 读取一个 data 帧；拒绝命名 event/id/retry，避免放宽其它 Provider 的严格 SSE 契约。 */
    public Event next() throws IOException {
        StringBuilder data = new StringBuilder();
        boolean frameHasField = false;
        boolean dataSeen = false;
        while (true) {
            String line = lines.nextLine();
            if (line == null) {
                if (!frameHasField) return null;
                return finish(data, dataSeen);
            }
            if (line.isEmpty()) {
                if (!frameHasField) continue;
                return finish(data, dataSeen);
            }
            if (line.charAt(0) == ':') continue;
            if (done) throw protocol("OpenAI Chat emitted data after [DONE]");
            frameHasField = true;
            int separator = line.indexOf(':');
            if (separator <= 0 || !"data".equals(line.substring(0, separator))) {
                throw protocol("OpenAI Chat SSE field is unsupported");
            }
            String value = line.substring(separator + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            if (dataSeen) data.append('\n');
            data.append(value);
            dataSeen = true;
        }
    }

    /** 将 [DONE] 与 JSON chunk 分型，clean EOF 仍由状态机依据 finish_reason 判定。 */
    private Event finish(StringBuilder data, boolean dataSeen) {
        if (!dataSeen) throw protocol("OpenAI Chat SSE data is missing");
        if ("[DONE]".contentEquals(data)) {
            if (done) throw protocol("OpenAI Chat repeated [DONE]");
            done = true;
            return Event.doneEvent();
        }
        if (done) throw protocol("OpenAI Chat emitted data after [DONE]");
        try (JsonParser parser = AbstractStreamingModelAdapter.JSON.createParser(data.toString())) {
            JsonNode root = AbstractStreamingModelAdapter.JSON.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw protocol("OpenAI Chat chunk is invalid");
            }
            return Event.chunk(root);
        } catch (ProviderProtocolException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw protocol("OpenAI Chat chunk is invalid");
        }
    }

    /** 创建不携带 Provider 原始 payload 的稳定 Chat 协议错误。 */
    private static ProviderProtocolException protocol(String message) {
        return new ProviderProtocolException("OPENAI_CHAT_EVENT", message, false);
    }

    /** 明确区分 JSON chunk 与终止标记，状态机不依赖 null 猜测事件种类。 */
    public record Event(JsonNode data, boolean done) {
        /** 构造普通 JSON chunk。 */
        public static Event chunk(JsonNode data) {
            return new Event(Objects.requireNonNull(data, "data"), false);
        }

        /** 构造唯一终止事件。 */
        public static Event doneEvent() {
            return new Event(null, true);
        }
    }
}
