// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpJsonValues;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpServerDefinition;
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
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * 负责确定性 MCP Tool 命名、路由编码、Schema 上限与请求级版本校验。
 */
public final class McpToolCatalog {
    /**
     * 禁止实例化无状态包内目录职责，避免产生第二个所有者。
     */
    private McpToolCatalog() {
    }

    /**
     * 把服务定义修订纳入目录版本，确保 endpoint、command、环境或认证变化即使 Tool schema 未变也会失效。
     */
    static McpGateway.McpSnapshot snapshot(
            List<McpGateway.McpTool> tools,
            Map<String, McpServerDefinition> definitions,
            ObjectMapper objectMapper,
            Instant createdAt) {
        List<McpGateway.McpTool> ordered = tools.stream()
                .sorted(Comparator.comparing(tool -> tool.spec().name())).toList();
        return new McpGateway.McpSnapshot(revision(ordered, definitions, objectMapper), ordered, createdAt);
    }

    /**
     * 生产缓存校验同时绑定完整服务定义身份，配置变化后旧快照不得跨 revision 复用。
     */
    static McpGateway.McpSnapshot validateSnapshot(
            McpGateway.McpSnapshot snapshot,
            ObjectMapper objectMapper,
            Map<String, McpServerDefinition> definitions) {
        if (snapshot == null) {
            return null;
        }
        if (!hasValidRevision(snapshot, objectMapper, definitions)) {
            throw new IllegalArgumentException("mcp_cached_snapshot_revision_invalid");
        }
        validateRoutes(snapshot, definitions.keySet());
        return snapshot;
    }

    /**
     * 缓存路由只能引用当前服务且本地名称唯一，避免畸形快照绕过 hash 校验。
     */
    private static void validateRoutes(McpGateway.McpSnapshot snapshot, Set<String> serverIds) {
        Set<String> names = new HashSet<>();
        for (McpGateway.McpTool tool : snapshot.tools()) {
            if (!serverIds.contains(tool.serverId()) || !names.add(tool.spec().name())) {
                throw new IllegalArgumentException("mcp_cached_snapshot_route_invalid");
            }
        }
    }

    /**
     * 生产校验同时纳入服务定义修订，防止只校验可见 Schema 而漏掉传输或认证重路由。
     */
    static boolean hasValidRevision(
            McpGateway.McpSnapshot snapshot,
            ObjectMapper objectMapper,
            Map<String, McpServerDefinition> definitions) {
        return revision(snapshot.tools(), definitions, objectMapper).equals(snapshot.revision());
    }

    /**
     * 为请求级持久化投影生成每个 Tool 的精确身份；key 与模型实际看到的 localName 完全一致。
     */
    public static Map<String, McpGateway.RouteIdentity> routeIdentities(
            McpGateway.McpSnapshot snapshot,
            Map<String, McpServerDefinition> definitions,
            ObjectMapper objectMapper) {
        LinkedHashMap<String, McpGateway.RouteIdentity> result = new LinkedHashMap<>();
        for (McpGateway.McpTool tool : snapshot.tools()) {
            McpServerDefinition definition = definitions.get(tool.serverId());
            if (definition == null) {
                throw new IllegalArgumentException("mcp_route_definition_missing");
            }
            String schemaHash = schemaHash(tool, objectMapper);
            String routeHash = routeHash(definition, tool, schemaHash);
            String localName = tool.spec().name();
            result.put(localName, new McpGateway.RouteIdentity(
                    localName,
                    tool.serverId(),
                    decodeRemoteName(tool.remoteName()),
                    definition.definitionRevision(),
                    schemaHash,
                    routeHash,
                    snapshot.revision()));
        }
        return Map.copyOf(result);
    }

    /**
     * Schema 摘要只基于递归规范 JSON，避免字段顺序差异制造无意义的路由失配。
     */
    static String schemaHash(McpGateway.McpTool tool, ObjectMapper objectMapper) {
        try {
            return digest(objectMapper.writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(McpJsonValues.toSdkArguments(objectMapper, tool.spec().inputSchema())));
        } catch (Exception failure) {
            throw new IllegalStateException("mcp_schema_hash_failed", failure);
        }
    }

    /**
     * 路由摘要覆盖服务完整定义、双端名称和 Schema，禁止配置变化后按同名 Tool 回退。
     */
    static String routeHash(
            McpServerDefinition definition, McpGateway.McpTool tool, String schemaHash) {
        String material = "mcp\0" + definition.definitionRevision() + '\0'
                + tool.serverId() + '\0' + decodeRemoteName(tool.remoteName()) + '\0'
                + tool.spec().name() + '\0' + tool.spec().description() + '\0' + schemaHash;
        return digest(material);
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
     * 只反解 Gateway 快照中由目录 owner 生成的路由编码。
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
     * 目录修订聚合模型可见描述、Schema 与不可见路由身份，任一变化都使 continuation 失效。
     */
    private static String revision(
            List<McpGateway.McpTool> tools,
            Map<String, McpServerDefinition> definitions,
            ObjectMapper objectMapper) {
        List<Map<String, Object>> material = tools.stream()
                .map(tool -> Map.<String, Object>of(
                        "server", tool.serverId(),
                        "remote", tool.remoteName(),
                        "local", tool.spec().name(),
                        "description", tool.spec().description(),
                        "definition", requireDefinition(tool, definitions).definitionRevision(),
                        "schemaHash", schemaHash(tool, objectMapper),
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
     * 每个 Tool 必须绑定同一安全点的真实服务定义；缺失定义无法证明路由身份，禁止伪造 revision。
     */
    private static McpServerDefinition requireDefinition(
            McpGateway.McpTool tool, Map<String, McpServerDefinition> definitions) {
        McpServerDefinition definition = definitions.get(tool.serverId());
        if (definition == null) {
            throw new IllegalArgumentException("mcp_route_definition_missing");
        }
        return definition;
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
