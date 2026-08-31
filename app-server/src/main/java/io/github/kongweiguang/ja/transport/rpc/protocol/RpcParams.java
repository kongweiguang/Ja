// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * 为小型领域 Handler 提供共享的严格参数解析原语。
 */
public final class RpcParams {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    /**
     * 禁止实例化纯参数策略类型，确保所有校验入口保持无状态。
     */
    private RpcParams() {
    }

    /**
     * 在 Handler 解析资源身份或产生副作用前拒绝未知字段。
     */
    public static void requireOnly(ObjectNode node, String... allowed) {
        Set<String> fields = Set.of(allowed);
        if (!node.properties().stream().allMatch(entry -> fields.contains(entry.getKey()))) {
            throw JaRpcException.invalidParams();
        }
    }

    /**
     * 在拒绝未知字段的同时要求预期字段完整存在。
     */
    public static void requireExact(ObjectNode node, String... expected) {
        requireOnly(node, expected);
        if (node.size() != expected.length) throw JaRpcException.invalidParams();
    }

    /**
     * 读取必需的有界文本，不接受标量强制转换或 NUL。
     */
    public static String text(ObjectNode node, String field, int maximum, boolean allowEmpty) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().length() > maximum
            || (!allowEmpty && value.textValue().isBlank()) || value.textValue().indexOf('\0') >= 0) {
            throw JaRpcException.invalidParams();
        }
        return value.textValue();
    }

    /**
     * 读取可选文本，并为键集游标保留“缺省即 null”的语义。
     */
    public static String optionalText(ObjectNode node, String field, int maximum) {
        if (!node.has(field) || node.get(field).isNull()) return null;
        return text(node, field, maximum, false);
    }

    /**
     * 要求字段为 ObjectNode，防止嵌套配置通过 Jackson 强制转换进入。
     */
    public static ObjectNode object(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (!(value instanceof ObjectNode object)) throw JaRpcException.invalidParams();
        return object;
    }

    /**
     * 读取有界正分页大小，并执行首发协议固定的 200 上限。
     */
    public static int pageLimit(ObjectNode node) {
        if (!node.has("limit")) return 200;
        JsonNode value = node.get("limit");
        if (!value.isIntegralNumber() || !value.canConvertToInt()
            || value.intValue() < 1 || value.intValue() > 200) {
            throw JaRpcException.invalidParams();
        }
        return value.intValue();
    }

    /**
     * 读取所有传输 CAS 操作共用的非负 JavaScript 安全 revision。
     */
    public static long revision(ObjectNode node, String field) {
        return wholeNumber(node, field);
    }

    /**
     * 读取 Provider/Model 时长与 Token 上限使用的非负 JavaScript 安全整数。
     */
    public static long wholeNumber(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
            || value.longValue() < 0 || value.longValue() > MAX_SAFE_INTEGER) {
            throw JaRpcException.invalidParams();
        }
        return value.longValue();
    }

    /**
     * 仅在共享安全整数校验通过后读取有界 Java int。
     */
    public static int integer(ObjectNode node, String field) {
        long value = wholeNumber(node, field);
        if (value > Integer.MAX_VALUE) throw JaRpcException.invalidParams();
        return (int) value;
    }

    /**
     * 读取并校验必需的 opaque 身份，不接受绝对路径或别名。
     */
    public static String identifier(ObjectNode node, String field, String prefix, int maximum) {
        return identifier(text(node, field, maximum, false), prefix, maximum);
    }

    /**
     * 在已提取 opaque 身份用作 Map 或存储键前再次校验。
     */
    public static String identifier(String value, String prefix, int maximum) {
        if (value == null || !value.startsWith(prefix) || value.length() > maximum
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw JaRpcException.invalidParams();
        }
        return value;
    }
}
