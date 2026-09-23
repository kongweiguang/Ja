// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * 复用生产端的 {@code thread/read} 活动流跨字段约束；JSON Schema 只能描述单字段形态，不能
 * 证明 Turn 归属、公开序号连续或恢复文本仍在同一预算内。
 */
public final class ThreadReadContract {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final Set<String> ACTIVE_TURN_STATUSES = Set.of(
            "queued", "running", "waiting_approval", "suspended");
    private static final Set<String> STREAM_FIELDS = Set.of("turnId", "streamSeq", "segments");
    private static final Set<String> SEGMENT_FIELDS = Set.of(
            "kind", "segmentStartSeq", "streamSeq", "text", "occurredAt");

    /** 仅提供无状态合同函数，禁止实例化后误存放运行会话。 */
    private ThreadReadContract() {
    }

    /**
     * 校验生产要发出的完整 thread/read 结果；失败只返回布尔值，让调用方决定抛出内部错误还是拒绝语料。
     */
    public static boolean isValidLiveStream(JsonNode result) {
        if (result == null || !result.isObject() || !result.has("liveStream")) return false;
        JsonNode stream = result.get("liveStream");
        if (stream == null || stream.isNull()) return true;
        if (!stream.isObject() || !exactFields(stream, STREAM_FIELDS)) return false;
        String turnId = text(stream, "turnId");
        long baseline = boundedLong(stream.get("streamSeq"), 0);
        JsonNode turns = result.get("turns");
        if (turnId == null || !validIdentifier(turnId, "turn_") || baseline < 0
                || !turnsOwnerActive(turns, turnId)) return false;
        JsonNode segments = stream.get("segments");
        if (segments == null || !segments.isArray() || segments.size() > 256) return false;
        long previousEnd = -1;
        long totalBytes = 0;
        for (JsonNode segment : segments) {
            if (!segment.isObject() || !exactFields(segment, SEGMENT_FIELDS)) return false;
            String kind = text(segment, "kind");
            String text = text(segment, "text");
            long start = boundedLong(segment.get("segmentStartSeq"), 1);
            long end = boundedLong(segment.get("streamSeq"), 1);
            if (!("assistant".equals(kind) || "reasoningSummary".equals(kind))
                    || start < 1 || end < start || end > baseline
                    || (previousEnd >= 0 && start != previousEnd + 1)
                    || text == null || text.isEmpty() || text.indexOf('\0') >= 0
                    || utf8Bytes(text) > ActiveStreamLimit.MAX_SEGMENT_BYTES
                    || !validTimestamp(text(segment, "occurredAt"))) return false;
            previousEnd = end;
            totalBytes = Math.addExact(totalBytes, utf8Bytes(text));
            if (totalBytes > ActiveStreamLimit.MAX_PUBLIC_BYTES) return false;
        }
        return segments.isEmpty() || previousEnd == baseline;
    }

    /**
     * 生产 Handler 在发送前使用同一约束失败关闭，防止仅由 Golden 测试额外校验而把残缺基线发给客户端。
     */
    public static void requireValidLiveStream(JsonNode result) {
        if (!isValidLiveStream(result)) {
            throw new IllegalStateException("thread/read live stream violates recovery contract");
        }
    }

    /** 只允许精确字段集合，避免未知字段绕过三端对同一恢复状态的解释。 */
    private static boolean exactFields(JsonNode object, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        Iterator<String> fields = object.fieldNames();
        while (fields.hasNext()) actual.add(fields.next());
        return actual.equals(expected);
    }

    /** 只认可拥有当前活动公开流的 queued/running 等非终态 Turn。 */
    private static boolean turnsOwnerActive(JsonNode turns, String turnId) {
        if (turns == null || !turns.isArray()) return false;
        for (JsonNode turn : turns) {
            if (turn.isObject() && turnId.equals(text(turn, "turnId"))
                    && ACTIVE_TURN_STATUSES.contains(text(turn, "status"))) return true;
        }
        return false;
    }

    /** 解析安全整数而不接受小数、字符串或超过 JavaScript 精确整数范围的值。 */
    private static long boundedLong(JsonNode value, long minimum) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) return -1;
        long number = value.longValue();
        return number >= minimum && number <= MAX_SAFE_INTEGER ? number : -1;
    }

    /** 生产 Wire 字段只能是文本；不做隐式转换，避免跨端出现不同的恢复正文。 */
    private static String text(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    /** 复用 JA-RPC 的 opaque ID 前缀边界，避免活动流跨 Thread 串线。 */
    private static boolean validIdentifier(String value, String prefix) {
        /* turnId 的 v1 schema 上限是 101（前缀加 96 个字符），不能在生产校验器里另造 128 上限。 */
        return value.length() > prefix.length() && value.length() <= 101 && value.startsWith(prefix)
                && value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}");
    }

    /** Instant 是生产映射使用的唯一时间格式；解析失败的语料不能进入恢复层。 */
    private static boolean validTimestamp(String value) {
        if (value == null || value.length() > 64) return false;
        try {
            Instant.parse(value);
            return true;
        } catch (DateTimeParseException invalid) {
            return false;
        }
    }

    /** 以最终 Wire 的 UTF-8 字节数计算预算，与 ActiveStreamRegistry 的内存界限保持一致。 */
    private static int utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 通过本地常量别名集中引用流预算，避免协议类直接依赖应用层实现；数值必须与 registry 同步。
     */
    private static final class ActiveStreamLimit {
        private static final int MAX_SEGMENT_BYTES = 64 * 1024;
        private static final int MAX_PUBLIC_BYTES = 1_048_576;

        /** 常量命名空间不创建实例，避免误把预算当作可变配置。 */
        private ActiveStreamLimit() {
        }
    }
}
