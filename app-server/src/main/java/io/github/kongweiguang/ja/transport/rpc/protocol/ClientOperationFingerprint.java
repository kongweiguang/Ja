// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** 同一逻辑提交的公共参数摘要；ledger 只持有哈希，不保存用户正文或审批理由。 */
public final class ClientOperationFingerprint {
    /** 工具类不允许实例化，避免把摘要算法选择变成连接级可变状态。 */
    private ClientOperationFingerprint() { }

    /**
     * 排除客户端操作 ID 后按对象键递归排序；客户端 JSON 键顺序不会导致同一请求
     * 被误判为冲突，数组顺序与结构化内容语义仍精确保留。
     */
    public static String sha256(ObjectMapper mapper, ObjectNode params) {
        ObjectNode content = params.deepCopy();
        content.remove("clientOperationId");
        try {
            byte[] canonical = mapper.writeValueAsBytes(canonicalize(mapper, content));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("client operation fingerprint is unavailable", failure);
        }
    }

    /** 只排序 JSON 对象，保留数组顺序与原始标量类型，避免改变实际命令含义。 */
    private static JsonNode canonicalize(ObjectMapper mapper, JsonNode input) {
        if (input instanceof ObjectNode object) {
            ObjectNode result = mapper.createObjectNode();
            Map<String, JsonNode> fields = new TreeMap<>();
            object.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, value) -> result.set(key, canonicalize(mapper, value)));
            return result;
        }
        if (input instanceof ArrayNode array) {
            ArrayNode result = mapper.createArrayNode();
            array.forEach(value -> result.add(canonicalize(mapper, value)));
            return result;
        }
        return input.deepCopy();
    }
}
