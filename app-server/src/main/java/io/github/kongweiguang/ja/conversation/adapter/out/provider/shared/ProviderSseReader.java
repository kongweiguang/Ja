// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Set;

/**
 * 两种 Provider Adapter 共享的严格、仅向前 SSE 帧 Reader。
 */
public final class ProviderSseReader {
    private final StrictSseLineReader lines;
    private final Set<String> allowedEvents;
    private final String providerCode;

    /**
     * 固定协议白名单，原始字节上限仍由外层 Stream 持有。
     */
    ProviderSseReader(InputStream input, Set<String> allowedEvents, String providerCode) {
        Objects.requireNonNull(input, "input");
        this.allowedEvents = Set.copyOf(Objects.requireNonNull(allowedEvents, "allowedEvents"));
        this.providerCode = Objects.requireNonNull(providerCode, "providerCode");
        lines = new StrictSseLineReader(input, providerCode, "provider SSE is not valid UTF-8");
        if (this.allowedEvents.isEmpty()) {
            throw new IllegalArgumentException("Provider SSE event allowlist must not be empty");
        }
    }

    /**
     * 读取一个完整命名 JSON 事件，同时接受注释和三种 SSE 行结尾。EOF 可终止最后一帧，
     * 但不能补造缺失的事件名或 data 字段。
     */
    Event next() throws IOException {
        while (true) {
            Event event = readFrame();
            if (event == null || allowedEvents.contains(event.name())) return event;
        }
    }

    /**
     * 未知 SSE 字段与事件只消耗单帧字节预算，不改变语义状态；已知 event/data 仍保持唯一性校验。
     */
    private Event readFrame() throws IOException {
        String eventName = null;
        StringBuilder data = new StringBuilder();
        boolean frameHasField = false;
        boolean dataSeen = false;
        while (true) {
            String line = lines.nextLine();
            if (line == null) {
                if (!frameHasField) return null;
                if (eventName == null && !dataSeen) return null;
                return finish(eventName, data, dataSeen, true);
            }
            if (line.isEmpty()) {
                if (!frameHasField) continue;
                if (eventName == null && !dataSeen) {
                    frameHasField = false;
                    continue;
                }
                return finish(eventName, data, dataSeen, false);
            }
            if (line.charAt(0) == ':') continue;
            frameHasField = true;
            int separator = line.indexOf(':');
            if (separator == 0) throw protocol("provider SSE field is invalid");
            String field = separator < 0 ? line : line.substring(0, separator);
            String value = separator < 0 ? "" : line.substring(separator + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            if ("event".equals(field)) {
                if (eventName != null || value.isEmpty()) {
                    throw protocol("provider SSE event field is invalid");
                }
                eventName = value;
            } else if ("data".equals(field)) {
                if (dataSeen) data.append('\n');
                data.append(value);
                dataSeen = true;
            } else {
                // SSE 扩展字段不参与模型语义；仍由外层字节流限制单帧资源。
            }
        }
    }

    /**
     * 仅在精确事件名和根 type 一致后解析帧。
     */
    private Event finish(String eventName, StringBuilder data, boolean dataSeen, boolean eofTerminated) {
        if (eventName == null || !dataSeen) {
            if (eofTerminated) throw truncated();
            throw protocol("provider SSE event is unsupported");
        }
        if (!allowedEvents.contains(eventName)) {
            return new Event(eventName, AbstractStreamingModelAdapter.JSON.createObjectNode());
        }
        try (JsonParser parser = AbstractStreamingModelAdapter.JSON.createParser(data.toString())) {
            JsonNode root = AbstractStreamingModelAdapter.JSON.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null
                || !eventName.equals(root.path("type").textValue())) {
                throw protocol("provider SSE event payload is invalid");
            }
            return new Event(eventName, root);
        } catch (ProviderProtocolException failure) {
            throw failure;
        } catch (com.fasterxml.jackson.core.io.JsonEOFException failure) {
            if (eofTerminated) throw truncated();
            throw protocol("provider SSE event payload is invalid");
        } catch (IOException | RuntimeException failure) {
            throw protocol("provider SSE event payload is invalid");
        }
    }

    /** 仅把传输 EOF 截断的半帧归入可恢复故障；显式结束的畸形帧仍保持确定性协议错误。 */
    private static ProviderProtocolException truncated() {
        return new ProviderProtocolException("STREAM_TRUNCATED",
                "provider SSE event ended before completion", true, "MODEL_STREAM_INVALID");
    }

    /**
     * 创建 Provider 专属脱敏异常，不保留字段或 payload 文本。
     */
    private ProviderProtocolException protocol(String message) {
        return new ProviderProtocolException(providerCode, message, false);
    }

    /**
     * 不可变解析事件；原始帧和 data 字符串不会逃逸出模型包。
     */
    public record Event(String name, JsonNode data) {
        /**
         * 要求 Reader 只发布完整的命名对象事件。
         */
        public Event {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(data, "data");
        }
    }
}
