// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.bootstrap;

import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据库快照使用不可变值，凭据只在配置层与模型适配器之间传递。
 *
 * @param model 模型
 * @param context 当前操作的上下文
 * @param mcpServers MCP 服务端列表
 */
public record AgentProperties(Model model,
                              Context context,
                              List<McpServer> mcpServers) {
    /**
     * 服务列表直接对应数据库字段，体验开关由 Spring 控制，不在快照中重复保存。
     */
    public AgentProperties {
        mcpServers = List.copyOf(mcpServers);
    }

    /**
     * MCP 统一使用 Streamable HTTP，配置不包含进程命令。
     *
     * @param id 当前实体的稳定标识
     * @param url 服务地址
     * @param headers HTTP 请求头
     */
    public record McpServer(String id,
                            String url,
                            Map<String, String> headers) {
        /**
         * 服务名稳定并冻结请求头；无 scheme 的相对 URI 也要按无效地址报告，不能泄漏集合查询的 NPE。
         */
        public McpServer {
            if (id == null || !id.matches("[a-z][a-z0-9-]{0,63}")) {
                throw new IllegalArgumentException("invalid MCP server id");
            }
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("MCP URL required");
            }
            URI uri = java.net.URI.create(url);
            if (uri.getScheme() == null || !java.util.Set.of("http", "https").contains(uri.getScheme())
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getFragment() != null)
                {
                    throw new IllegalArgumentException("invalid MCP URL");
                }
        }
    }

    /**
     * 超时约束单次网络请求，不构成整个 Agent 任务的预算。
     *
     * @param providerId 模型供应商标识
     * @param modelId 模型标识
     * @param configGeneration 冻结的配置代际标识
     * @param api 模型 API 协议
     * @param name 名称
     * @param baseUri 上游服务的基础地址
     * @param apiKey 模型 API 凭据，仅用于授权请求
     * @param connectTimeout 建立连接的超时时间
     * @param requestTimeout 单次请求的超时时间
     * @param reasoningLevel 模型推理等级
     * @param inputModalities 模型配置显式声明的输入模态
     */
    public record Model(String providerId,
                        String modelId,
                        String configGeneration,
                        ModelPort.Api api,
                        String name,
                        String baseUri,
                        String apiKey,
                        Duration connectTimeout,
                        Duration requestTimeout,
                        String reasoningLevel,
                        Set<ModelPort.InputModality> inputModalities) {
        /**
         * 冻结模型模态声明并要求文本输入始终可用；文件格式仍由所选 Codec 再次收窄。
         */
        public Model {
            inputModalities = Set.copyOf(inputModalities);
            if (inputModalities.isEmpty() || !inputModalities.contains(ModelPort.InputModality.TEXT)) {
                throw new IllegalArgumentException("text input modality is required");
            }
        }
    }

    /**
     * 窗口能力由接入方声明，切换模型时应同步实际能力。
     *
     * @param windowTokens 当前上下文窗口占用的 Token 数
     * @param maxOutputTokens 单次模型输出的 Token 上限
     * @param autoCompact 是否启用自动上下文压缩
     */
    public record Context(int windowTokens,
                          int maxOutputTokens,
                          boolean autoCompact) {
    }
}
