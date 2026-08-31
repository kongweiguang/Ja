// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.generation;

import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpServerDefinition;
import io.github.kongweiguang.ja.catalog.port.out.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationGenerationSnapshot;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 从不可变配置代际租约生成一个 MCP 启动定义。
 *
 * <p>只有此窄适配器可把不透明凭据引用转换为短生命周期进程环境变量或 Header。
 * 结果定义由 Turn MCP Runtime 独占，绝不放入共享配置目录。</p>
 */
final class GenerationMcpDefinitionFactory {
    private static final List<String> PROTOCOL_VERSIONS = List.of("2025-06-18");
    private static final Pattern SENSITIVE_NAME = Pattern.compile(
            "(?i).*(api.?key|secret|token|password|passwd|authorization|cookie|credential).*");

    /**
     * 该工厂只封装纯转换规则，禁止实例化以免持有或延长凭据租约。
     */
    private GenerationMcpDefinitionFactory() {
    }

    /**
     * 只解析选中服务的凭据，其余代际数据始终保持不可变。
     */
    static McpServerDefinition create(ConfigurationGenerationSnapshot.McpServer server,
                                      Path workingDirectory, ConfigurationGenerationPort.Lease lease) {
        ConfigurationGenerationSnapshot.Auth auth = server.auth();
        String secret = auth.kind() == ConfigurationGenerationSnapshot.AuthKind.NONE
                ? null : lease.secretFor(auth.credentialId());
        if (auth.kind() != ConfigurationGenerationSnapshot.AuthKind.NONE
                && (secret == null || secret.isEmpty())) {
            throw new IllegalStateException("mcp_credential_unavailable");
        }
        if (server.transport() == ConfigurationGenerationSnapshot.Transport.STDIO) {
            rejectSecretNames(server.env(), "mcp_environment_auth_invalid");
            List<String> command = new ArrayList<>();
            command.add(server.endpoint());
            command.addAll(server.args());
            Map<String, String> environment = new HashMap<>(server.env());
            if (auth.kind() == ConfigurationGenerationSnapshot.AuthKind.ENV) {
                putCaseInsensitive(environment, auth.name(), secret, "mcp_environment_auth_conflict");
            }
            return McpServerDefinition.stdio(server.mcpId(), command, workingDirectory, environment,
                    PROTOCOL_VERSIONS);
        }
        rejectSecretNames(server.headers(), "mcp_header_auth_invalid");
        Map<String, String> headers = new HashMap<>(server.headers());
        Set<String> bearerHeaders = Set.of();
        if (auth.kind() == ConfigurationGenerationSnapshot.AuthKind.BEARER) {
            putCaseInsensitive(headers, "Authorization", secret, "mcp_header_auth_conflict");
            bearerHeaders = Set.of("Authorization");
        } else if (auth.kind() == ConfigurationGenerationSnapshot.AuthKind.HEADER) {
            putCaseInsensitive(headers, auth.name(), secret, "mcp_header_auth_conflict");
        }
        return McpServerDefinition.streamableHttp(server.mcpId(), URI.create(server.endpoint()), headers,
                bearerHeaders, PROTOCOL_VERSIONS);
    }

    /**
     * 拒绝未类型化 Secret 值，确保所有凭据只通过代际租约进入。
     */
    private static void rejectSecretNames(Map<String, String> values, String errorCode) {
        if (values.keySet().stream().anyMatch(name -> SENSITIVE_NAME.matcher(name).matches())) {
            throw new IllegalArgumentException(errorCode);
        }
    }

    /**
     * 拒绝 Windows 环境变量与 HTTP Header 命名空间中的大小写不敏感认证冲突。
     */
    private static void putCaseInsensitive(Map<String, String> values, String name, String value,
                                           String errorCode) {
        if (values.keySet().stream().anyMatch(existing -> existing.equalsIgnoreCase(name))) {
            throw new IllegalArgumentException(errorCode);
        }
        values.put(name, value);
    }
}
