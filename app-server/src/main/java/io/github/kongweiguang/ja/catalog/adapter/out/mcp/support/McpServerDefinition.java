// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.support;

import java.net.URI;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 仅从已校验 ConfigGeneration Lease 创建的内存 MCP 服务配置。
 */
public record McpServerDefinition(
        String id,
        Transport transport,
        List<String> command,
        Path workingDirectory,
        Map<String, String> environment,
        URI endpoint,
        Map<String, String> headers,
        Set<String> bearerHeaders,
        List<String> protocolVersions) {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,95}");
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}");
    private static final Pattern SENSITIVE_NAME = Pattern.compile(
            "(?i).*(api.?key|secret|token|password|passwd|authorization|cookie|credential).*");
    private static final Pattern SENSITIVE_ARGUMENT = Pattern.compile(
            "(?i)^--?(api.?key|secret|token|password|passwd|authorization|credential)(=|$).*");

    /**
     * 在凭据解析后校验 stdio 与 HTTP 互斥结构，禁止混合传输字段。
     */
    public McpServerDefinition {
        if (id == null || !IDENTIFIER.matcher(id).matches()) {
            throw new IllegalArgumentException("mcp_server_id_invalid");
        }
        Objects.requireNonNull(transport, "transport");
        command = copyCommand(command);
        environment = copyValues(environment, true);
        headers = copyValues(headers, false);
        bearerHeaders = copyBearerHeaders(bearerHeaders, headers);
        protocolVersions = copyProtocols(protocolVersions);
        if (transport == Transport.STDIO) {
            if (command.isEmpty() || endpoint != null || !headers.isEmpty() || !bearerHeaders.isEmpty()) {
                throw new IllegalArgumentException("mcp_stdio_shape_invalid");
            }
            workingDirectory = requireAbsoluteDirectory(workingDirectory);
        } else {
            if (!command.isEmpty() || workingDirectory != null || !environment.isEmpty()) {
                throw new IllegalArgumentException("mcp_http_shape_invalid");
            }
            endpoint = requireHttpEndpoint(endpoint);
        }
    }

    /**
     * 创建 stdio 定义，子进程只接收此处显式列出的环境变量。
     */
    public static McpServerDefinition stdio(
            String id,
            List<String> command,
            Path workingDirectory,
            Map<String, String> environment,
            List<String> protocolVersions) {
        return new McpServerDefinition(
                id, Transport.STDIO, command, workingDirectory, environment, null, Map.of(), Set.of(),
                protocolVersions);
    }

    /**
     * 创建 Streamable HTTP 定义，URI 本身不得携带 user-info 凭据。
     */
    public static McpServerDefinition streamableHttp(
            String id,
            URI endpoint,
            Map<String, String> headers,
            List<String> protocolVersions) {
        return new McpServerDefinition(
                id, Transport.STREAMABLE_HTTP, List.of(), null, Map.of(), endpoint, headers, Set.of(),
                protocolVersions);
    }

    /**
     * 创建要求标准 Bearer 语义的 HTTP 定义，避免认证 Header 被当作普通值处理。
     */
    public static McpServerDefinition streamableHttp(
            String id,
            URI endpoint,
            Map<String, String> headers,
            Set<String> bearerHeaders,
            List<String> protocolVersions) {
        return new McpServerDefinition(
                id, Transport.STREAMABLE_HTTP, List.of(), null, Map.of(), endpoint, headers, bearerHeaders,
                protocolVersions);
    }

    /**
     * 仅输出环境变量名与 Header 名，避免诊断意外泄露已解析认证材料。
     */
    @Override
    public String toString() {
        return "McpServerDefinition[id=" + id
               + ", transport=" + transport
               + ", commandCount=" + command.size()
               + ", environmentNames=" + environment.keySet()
               + ", endpointPresent=" + (endpoint != null)
               + ", headerNames=" + headers.keySet()
               + ", bearerHeaders=" + bearerHeaders
               + ", protocolVersions=" + protocolVersions + "]";
    }

    /**
     * 仅暴露 Kernel 明确支持且已施加资源边界的两种 MCP 传输。
     */
    public enum Transport {
        /**
         * 通过受控子进程的标准输入输出传输 JSON-RPC。
         */
        STDIO,

        /**
         * 通过受控 OkHttp 客户端使用 Streamable HTTP 传输 JSON-RPC。
         */
        STREAMABLE_HTTP
    }

    /**
     * 对完整启动语义生成不可逆定义修订；Secret 值只参与 SHA-256，绝不进入日志、目录或持久绑定。
     * Map 键和值分别排序并带长度编码，避免拼接歧义和 JVM 遍历顺序造成伪变更。
     */
    public String definitionRevision() {
        StringBuilder canonical = new StringBuilder();
        append(canonical, "transport", transport.name());
        command.forEach(value -> append(canonical, "command", value));
        append(canonical, "cwd", workingDirectory == null ? "" : workingDirectory.toString());
        environment.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> appendSecret(canonical, "env:" + entry.getKey(), entry.getValue()));
        append(canonical, "endpoint", endpoint == null ? "" : endpoint.toASCIIString());
        headers.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> appendSecret(canonical,
                        "header:" + entry.getKey().toLowerCase(Locale.ROOT), entry.getValue()));
        bearerHeaders.stream().sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(value -> append(canonical, "bearer", value.toLowerCase(Locale.ROOT)));
        protocolVersions.forEach(value -> append(canonical, "protocol", value));
        return "mcp-def-" + digest(canonical.toString());
    }

    /**
     * 以长度前缀编码公开配置事实，避免换行、分隔符或空值制造相同修订。
     */
    private static void append(StringBuilder target, String name, String value) {
        target.append(name.length()).append(':').append(name)
                .append(value.length()).append(':').append(value).append(';');
    }

    /**
     * 私密配置只把不可逆摘要纳入定义身份，防止 revision 调试输出反推出凭据正文。
     */
    private static void appendSecret(StringBuilder target, String name, String value) {
        append(target, name, digest(value));
    }

    /**
     * 使用 JDK 内置 SHA-256 保持 Native Image 与 JVM 一致，不增加易漂移的散列依赖。
     */
    private static String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("sha256_unavailable", impossible);
        }
    }

    /**
     * 有界复制进程参数，并拒绝在专用值 Map 之外携带 Secret 标记。
     */
    private static List<String> copyCommand(List<String> values) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > 128) {
            throw new IllegalArgumentException("mcp_command_limit");
        }
        List<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null || value.isBlank() || value.length() > 8192 || value.indexOf('\0') >= 0
                || isUnresolvedReference(value) || SENSITIVE_ARGUMENT.matcher(value).matches()) {
                throw new IllegalArgumentException("mcp_command_invalid");
            }
            copy.add(value);
        }
        return List.copyOf(copy);
    }

    /**
     * 接受有界内存值，同时拒绝大小写规范化后发生歧义的键。
     */
    private static Map<String, String> copyValues(Map<String, String> values, boolean environment) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        if (values.size() > 128) {
            throw new IllegalArgumentException("mcp_config_limit");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        Set<String> normalizedNames = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        values.forEach((name, value) -> {
            Pattern grammar = environment ? ENVIRONMENT_NAME : HEADER_NAME;
            if (name == null || !grammar.matcher(name).matches() || value == null || value.length() > 8192
                || value.indexOf('\0') >= 0 || isUnresolvedReference(value)
                || !normalizedNames.add(name)) {
                throw new IllegalArgumentException("mcp_config_value_invalid");
            }
            copy.put(name, value);
        });
        return Map.copyOf(copy);
    }

    /**
     * 只允许类型化认证为显式存在的 Header 绑定 Bearer 语义。
     */
    private static Set<String> copyBearerHeaders(Set<String> names, Map<String, String> headers) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        if (names.size() > headers.size()) {
            throw new IllegalArgumentException("mcp_bearer_header_invalid");
        }
        for (String name : names) {
            if (name == null || !headers.containsKey(name) || headers.get(name).isEmpty()) {
                throw new IllegalArgumentException("mcp_bearer_header_invalid");
            }
        }
        return Set.copyOf(names);
    }

    /**
     * 协商只允许显式协议版本，防止 SDK 默认值静默扩大支持范围。
     */
    private static List<String> copyProtocols(List<String> versions) {
        if (versions == null || versions.isEmpty() || versions.size() > 8) {
            throw new IllegalArgumentException("mcp_protocol_versions_invalid");
        }
        for (String version : versions) {
            if (version == null || !version.matches("20[0-9]{2}-[0-9]{2}-[0-9]{2}")) {
                throw new IllegalArgumentException("mcp_protocol_version_invalid");
            }
        }
        return List.copyOf(versions);
    }

    /**
     * 一次性规范化子进程 cwd，目录存在性仍由启动边界负责。
     */
    private static Path requireAbsoluteDirectory(Path path) {
        if (path == null || !path.isAbsolute()) {
            throw new IllegalArgumentException("mcp_working_directory_invalid");
        }
        return path.normalize();
    }

    /**
     * 构建请求前拒绝非 TLS 远端地址和 URI 内嵌凭据；loopback HTTP 是唯一例外。
     */
    private static URI requireHttpEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        boolean loopbackHttp = "http".equalsIgnoreCase(endpoint.getScheme())
                               && ("127.0.0.1".equals(endpoint.getHost()) || "localhost".equalsIgnoreCase(endpoint.getHost()));
        if (!("https".equalsIgnoreCase(endpoint.getScheme()) || loopbackHttp)
            || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("mcp_http_endpoint_invalid");
        }
        String query = endpoint.getRawQuery();
        if (query != null && java.util.Arrays.stream(query.split("&"))
                .map(parameter -> java.net.URLDecoder.decode(parameter, java.nio.charset.StandardCharsets.UTF_8))
                .anyMatch(McpServerDefinition::isUnresolvedReference)) {
            throw new IllegalArgumentException("mcp_http_endpoint_secret_invalid");
        }
        if (query != null) {
            for (String parameter : query.split("&")) {
                String name = parameter.split("=", 2)[0];
                if (SENSITIVE_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("mcp_http_endpoint_secret_invalid");
                }
            }
        }
        return endpoint.normalize();
    }

    /**
     * 拒绝未解析的引用 URI scheme，确保传输只接收已解析值。
     */
    private static boolean isUnresolvedReference(String value) {
        if (value == null) {
            return false;
        }
        int separator = value.indexOf("://");
        if (separator <= 0) {
            return false;
        }
        String scheme = value.substring(0, separator);
        return scheme.endsWith("-ref") || scheme.endsWith("_ref");
    }
}
