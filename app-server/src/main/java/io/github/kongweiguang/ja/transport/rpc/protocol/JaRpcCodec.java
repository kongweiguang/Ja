// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 为刻意收窄的 {@code ja-rpc/v1} 信封集合提供严格 UTF-8 JSONL 编解码。
 */
public final class JaRpcCodec {
    public static final int DEFAULT_MAX_FRAME_BYTES = 4 * 1024 * 1024;
    private static final Set<String> REQUEST_FIELDS = Set.of("jsonrpc", "id", "method", "params");
    private static final Set<String> NOTIFICATION_FIELDS = Set.of("jsonrpc", "method", "params");
    private static final Set<String> RESPONSE_FIELDS = Set.of("jsonrpc", "id", "result", "error");
    private static final Set<String> CLIENT_METHODS = RpcMethod.wireNames();
    private static final Set<String> SERVER_NOTIFICATIONS = Set.of(
            "runtime/initialized", "runtime/status-changed", "turn/state-changed", "assistant/model-step-committed",
            "assistant/text-delta", "assistant/reasoning-summary-delta", "tool/started", "tool/batch-committed",
            "approval/requested", "approval/resolved", "context/compaction-started", "context/compacted",
            "context/compaction-failed",
            "workspace/dirty", "turn/input-queue-changed", "turn/input-consumed", "turn/messages_received", "turn/terminal",
            "thread/metadata-changed", "configuration/changed",
            "task/activity", "task/progress", "task/mailbox-changed",
            "goal/changed", "goal/activity", "interaction/changed", "plan/changed");

    private final ObjectMapper mapper;
    private final int maxFrameBytes;

    /**
     * 启用递归重复键检测，因为存在歧义的 JSON 无法安全授权。
     */
    public JaRpcCodec(int maxFrameBytes) {
        if (maxFrameBytes < 1_024 || maxFrameBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("maxFrameBytes is outside the supported bound");
        }
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.mapper = new ObjectMapper(factory);
        this.maxFrameBytes = maxFrameBytes;
    }

    /**
     * 提供 JVM 与 Native Image 入口共享的默认协商帧上限。
     */
    public JaRpcCodec() {
        this(DEFAULT_MAX_FRAME_BYTES);
    }

    /**
     * 读取一个完整行帧，不允许替换式解码或无界缓冲。
     */
    public Optional<Frame> read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxFrameBytes, 16 * 1024));
        while (true) {
            int value = input.read();
            if (value < 0) {
                if (bytes.size() == 0) {
                    return Optional.empty();
                }
                break;
            }
            if (value == '\n') {
                break;
            }
            if (bytes.size() == maxFrameBytes) {
                throw JaRpcException.of(JaErrorCatalog.FRAME_TOO_LARGE, "protocol frame is too large");
            }
            bytes.write(value);
        }
        byte[] frame = bytes.toByteArray();
        if (frame.length > 0 && frame[frame.length - 1] == '\r') {
            frame = java.util.Arrays.copyOf(frame, frame.length - 1);
        }
        if (frame.length == 0) {
            throw JaRpcException.invalidFrame();
        }
        return Optional.of(decode(frame));
    }

    /**
     * 只解码互斥的请求、通知或响应角色，并拒绝信封扩展字段。
     */
    public Frame decode(byte[] bytes) {
        try {
            String json = strictUtf8(bytes);
            JsonNode parsed = mapper.readTree(json);
            if (!(parsed instanceof ObjectNode object) || !"2.0".equals(text(object, "jsonrpc"))) {
                throw JaRpcException.invalidFrame();
            }
            boolean hasId = object.has("id");
            boolean hasMethod = object.has("method");
            boolean hasResult = object.has("result");
            boolean hasError = object.has("error");
            if (hasMethod && hasId && !hasResult && !hasError) {
                requireExactFields(object, REQUEST_FIELDS);
                String requestMethod = method(object);
                if (!CLIENT_METHODS.contains(requestMethod)) throw JaRpcException.methodNotFound();
                return new Request(identifier(object, "id"), requestMethod, object(object, "params"));
            }
            if (hasMethod && !hasId && !hasResult && !hasError) {
                requireExactFields(object, NOTIFICATION_FIELDS);
                String notificationMethod = method(object);
                if (!SERVER_NOTIFICATIONS.contains(notificationMethod)) throw JaRpcException.methodNotFound();
                return new Notification(notificationMethod, object(object, "params"));
            }
            if (!hasMethod && hasId && (hasResult ^ hasError)) {
                requireSubsetFields(object, RESPONSE_FIELDS);
                String id = responseIdentifier(object);
                return hasResult
                        ? new Response(id, object(object, "result"), null)
                        : new Response(id, null, object(object, "error"));
            }
            throw JaRpcException.invalidFrame();
        } catch (JaRpcException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw JaRpcException.invalidFrame();
        }
    }

    /**
     * 返回严格配置的 Mapper 副本，避免调用方注册模块或特性时改变协议解码器。
     */
    public ObjectMapper mapper() {
        return mapper.copy();
    }

    /**
     * 执行严格 UTF-8 解码，防止畸形字节静默改变已授权值。
     */
    private static String strictUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    /**
     * 仅接受有界 Rust 客户端身份，拒绝服务端方向的请求 ID。
     */
    private static String identifier(ObjectNode object, String field) {
        String value = text(object, field);
        if (value == null || value.length() > 98 || !value.matches("c:[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) {
            throw JaRpcException.invalidFrame();
        }
        return value;
    }

    /**
     * 将入站调用限制在首发冻结方法词汇表内，不接受兼容别名。
     */
    private static String method(ObjectNode object) {
        String value = text(object, "method");
        if (value == null || value.length() > 128
            || !CLIENT_METHODS.contains(value)
               && !SERVER_NOTIFICATIONS.contains(value)) {
            throw JaRpcException.invalidFrame();
        }
        return value;
    }

    /**
     * 要求字段为 ObjectNode，因为每个首版 v1 方法都拥有具名且经 Schema 校验的参数或结果结构。
     */
    private static ObjectNode object(ObjectNode envelope, String field) {
        JsonNode value = envelope.get(field);
        if (!(value instanceof ObjectNode object)) {
            throw JaRpcException.invalidFrame();
        }
        return object;
    }

    /**
     * 读取文本字段，不强制转换数值、布尔值或 null。
     */
    private static String text(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    /**
     * 对直接构造的帧执行与字节解码相同的客户端关联规则。
     */
    private static void requireClientId(String value) {
        if (value == null || !value.matches("c:[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) {
            throw JaRpcException.invalidFrame();
        }
    }

    /**
     * 在独立命名空间中接受 c:* 客户端响应与 h:* 反向 Host 响应。
     */
    private static String responseIdentifier(ObjectNode object) {
        return responseIdentifier(text(object, "id"));
    }

    /**
     * 对直接构造的帧与已解码 JSON 统一执行响应命名空间规则。
     */
    private static String responseIdentifier(String value) {
        if (value == null || value.length() > 98
            || !value.matches("c:[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) {
            throw JaRpcException.invalidFrame();
        }
        return value;
    }

    /**
     * 拒绝未知请求字段，因为信封扩展会模糊请求与响应角色。
     */
    private static void requireExactFields(ObjectNode object, Set<String> expected) {
        if (object.size() != expected.size()) {
            throw JaRpcException.invalidFrame();
        }
        requireSubsetFields(object, expected);
    }

    /**
     * 检查每个已出现字段，同时允许响应在 result 与 error 之间二选一。
     */
    private static void requireSubsetFields(ObjectNode object, Set<String> allowed) {
        Iterator<Map.Entry<String, JsonNode>> fields = object.properties().iterator();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next().getKey())) {
                throw JaRpcException.invalidFrame();
            }
        }
    }

    /**
     * 封闭入站帧集合，使运行时分派保持穷尽且不依赖 Jackson 节点类型判断。
     */
    public sealed interface Frame permits Request, Notification, Response {
    }

    /**
     * 客户端到服务端的调用，必须携带一个对象参数。
     */
    public record Request(String id, String method, ObjectNode params) implements Frame {
        /**
         * 复制调用方持有的 JSON，避免异步分派观察到后续修改。
         */
        public Request {
            requireClientId(id);
            if (method == null || !CLIENT_METHODS.contains(method)) throw JaRpcException.methodNotFound();
            params = Objects.requireNonNull(params, "params").deepCopy();
        }

        /**
         * 每次读取返回节点副本，防止 Handler 修改已冻结的入站信封。
         */
        @Override
        public ObjectNode params() {
            return params.deepCopy();
        }
    }

    /**
     * 客户端或服务端通知信封，运行时分派器负责应用方向语义。
     */
    public record Notification(String method, ObjectNode params) implements Frame {
        /**
         * 复制参数，使握手状态持有不可变 challenge 值。
         */
        public Notification {
            if (method == null || !SERVER_NOTIFICATIONS.contains(method)) throw JaRpcException.methodNotFound();
            params = Objects.requireNonNull(params, "params").deepCopy();
        }

        /**
         * 每次读取返回节点副本，使通知关联字段在异步分派期间保持冻结。
         */
        @Override
        public ObjectNode params() {
            return params.deepCopy();
        }
    }

    /**
     * 双向共用的响应信封；Host 响应到达 HostToolProxy 前，RpcServer 会按方向校验
     * 对应的 pending registry。
     */
    public record Response(String id, ObjectNode result, ObjectNode error) implements Frame {
        /**
         * 在 pending 关联消费响应前强制只存在一个载荷。
         */
        public Response {
            responseIdentifier(id);
            if ((result == null) == (error == null)) {
                throw new IllegalArgumentException("response requires exactly one payload");
            }
            result = result == null ? null : result.deepCopy();
            error = error == null ? null : error.deepCopy();
        }

        /**
         * 返回结果副本，调用方消费响应时不得改变 pending registry 持有的载荷。
         */
        @Override
        public ObjectNode result() {
            return result == null ? null : result.deepCopy();
        }

        /**
         * 返回错误副本，防止映射错误时污染后续诊断或重试判断。
         */
        @Override
        public ObjectNode error() {
            return error == null ? null : error.deepCopy();
        }
    }
}
