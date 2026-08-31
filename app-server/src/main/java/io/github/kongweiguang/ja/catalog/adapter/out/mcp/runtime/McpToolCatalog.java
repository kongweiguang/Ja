// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpJsonValues;
import io.github.kongweiguang.ja.conversation.adapter.out.tools.NetworkntToolArgumentValidator;
import io.github.kongweiguang.ja.conversation.adapter.out.tools.ToolSchemaException;
import io.github.kongweiguang.ja.conversation.port.out.McpGateway;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonValue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 负责确定性 MCP Tool 命名、路由编码、Schema 上限与冻结版本校验。
 */
final class McpToolCatalog {
    /**
     * 禁止实例化无状态包内目录职责，避免产生第二个所有者。
     */
    private McpToolCatalog() {
    }

    /**
     * 排序并散列不可变 Tool 投影，生成 Turn 目录版本。
     */
    static McpGateway.McpSnapshot snapshot(
            List<McpGateway.McpTool> tools, ObjectMapper objectMapper, Instant createdAt) {
        List<McpGateway.McpTool> ordered = tools.stream()
                .sorted(Comparator.comparing(tool -> tool.spec().name())).toList();
        return new McpGateway.McpSnapshot(revision(ordered, objectMapper), ordered, createdAt);
    }

    /**
     * 启动选中传输前拒绝过期或跨服务缓存路由。
     */
    static McpGateway.McpSnapshot validateSnapshot(
            McpGateway.McpSnapshot snapshot, ObjectMapper objectMapper, Set<String> serverIds) {
        if (snapshot == null) {
            return null;
        }
        if (!hasValidRevision(snapshot, objectMapper)) {
            throw new IllegalArgumentException("mcp_cached_snapshot_revision_invalid");
        }
        Set<String> names = new HashSet<>();
        for (McpGateway.McpTool tool : snapshot.tools()) {
            if (!serverIds.contains(tool.serverId()) || !names.add(tool.spec().name())) {
                throw new IllegalArgumentException("mcp_cached_snapshot_route_invalid");
            }
        }
        return snapshot;
    }

    /**
     * 校验调用方精确 Tool 顺序；不可信快照重排必须失败关闭。
     */
    static boolean hasValidRevision(McpGateway.McpSnapshot snapshot, ObjectMapper objectMapper) {
        return revision(snapshot.tools(), objectMapper).equals(snapshot.revision());
    }

    /**
     * 生成 Provider 安全的确定性本地名称，并散列有歧义的规范化结果。
     */
    static String namespaced(String serverId, String remoteName) {
        String server = sanitize(serverId);
        String remote = sanitize(remoteName);
        String base = "mcp:" + server + ":" + remote;
        if (base.length() <= 220 && remote.equals(remoteName)) {
            return base;
        }
        String digest = digest(remoteName).substring(0, 12);
        int maximumRemote = Math.max(1, 220 - "mcp:::-".length() - server.length() - digest.length());
        if (remote.length() > maximumRemote) {
            remote = remote.substring(0, maximumRemote);
        }
        return "mcp:" + server + ":" + remote + "-" + digest;
    }

    /**
     * 将任意 MCP 名称编码为 Kernel 标识语法，同时保留路由身份。
     */
    static String encodeRemoteName(String remoteName) {
        byte[] bytes = remoteName.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 180) {
            throw new IllegalStateException("mcp_remote_tool_name_limit");
        }
        return "r-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 只反解冻结快照中由 Gateway 所有的路由编码。
     */
    static String decodeRemoteName(String encoded) {
        if (!encoded.startsWith("r-")) {
            throw new IllegalArgumentException("mcp_remote_route_invalid");
        }
        try {
            return new String(Base64.getUrlDecoder().decode(encoded.substring(2)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("mcp_remote_route_invalid", failure);
        }
    }

    /**
     * 测量真实 UTF-8 JSON 大小，避免 Java 字符数低估非 ASCII 载荷。
     */
    static int encodedSize(ObjectMapper objectMapper, String value) {
        try {
            return objectMapper.writeValueAsBytes(value).length;
        } catch (Exception failure) {
            throw new IllegalStateException("mcp_json_encoding_failed", failure);
        }
    }

    /**
     * 对强类型 JSON 值先映射为 Wire 树再计量，避免 Jackson 序列化 record 组件包装层。
     */
    static int encodedSize(ObjectMapper objectMapper, JsonValue value) {
        try {
            return objectMapper.writeValueAsBytes(McpJsonValues.toNode(objectMapper, value)).length;
        } catch (Exception failure) {
            throw new IllegalStateException("mcp_json_encoding_failed", failure);
        }
    }

    /**
     * 复用唯一 Tool 边界 Networknt Adapter，并将远端 Schema 失败映射为目录状态。
     */
    static void requireToolSchema(ObjectMapper objectMapper, JsonObject schema) {
        try {
            new NetworkntToolArgumentValidator(
                    objectMapper.writeValueAsString(McpJsonValues.toNode(objectMapper, schema)));
        } catch (ToolSchemaException | com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("mcp_tool_schema_invalid", failure);
        }
    }

    /**
     * 散列规范 JSON，避免发现阶段与 Turn Runtime 因 Mapper 的 Map 排序配置不同而产生分歧。
     * Tool 顺序保持调用方语义；计算版本前会排序所有对象键，包括嵌套远端 Schema。
     */
    private static String revision(List<McpGateway.McpTool> tools, ObjectMapper objectMapper) {
        List<Map<String, Object>> material = tools.stream()
                .map(tool -> Map.<String, Object>of(
                        "server", tool.serverId(),
                        "remote", tool.remoteName(),
                        "local", tool.spec().name(),
                        "description", tool.spec().description(),
                        "schema", McpJsonValues.toSdkArguments(objectMapper, tool.spec().inputSchema())))
                .toList();
        try {
            return "mcp-" + digest(objectMapper.writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(material));
        } catch (Exception failure) {
            throw new IllegalStateException("mcp_catalog_revision_failed", failure);
        }
    }

    /**
     * 将远端标点映射为稳定下划线，同时拒绝空标识。
     */
    private static String sanitize(String value) {
        StringBuilder safe = new StringBuilder();
        boolean underscore = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isLetterOrDigit(character) || character == '.' || character == ':' || character == '-') {
                safe.append(character);
                underscore = false;
            } else if (!underscore) {
                safe.append('_');
                underscore = true;
            }
        }
        if (safe.isEmpty() || !Character.isLetterOrDigit(safe.charAt(0))) {
            safe.insert(0, 't');
        }
        return safe.toString();
    }

    /**
     * 返回稳定小写 SHA-256 摘要，不引入额外散列依赖。
     */
    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("sha256_unavailable", impossible);
        }
    }
}
