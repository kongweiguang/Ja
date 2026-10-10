// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.application.service;

import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.catalog.domain.model.McpServerDescriptor;
import io.github.kongweiguang.ja.catalog.domain.model.McpToolDescriptor;
import io.github.kongweiguang.ja.catalog.domain.model.SkillDescriptor;
import io.github.kongweiguang.ja.catalog.application.api.CatalogUseCase;
import io.github.kongweiguang.ja.catalog.application.gateway.CatalogQueryPort;
import io.github.kongweiguang.ja.catalog.application.gateway.CatalogWorkspacePort;
import io.github.kongweiguang.ja.catalog.application.gateway.CatalogConfigurationPort;
import io.github.kongweiguang.ja.catalog.application.gateway.ConfigurationGenerationPort;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * catalog 入站用例实现，只负责编排并把外部 IO 委派给出站端口。
 */
public final class CatalogService implements CatalogUseCase {
    private final CatalogQueryPort queryPort;
    private final ConfigurationGenerationPort generations;
    private final ModelPort models;
    private final CatalogWorkspacePort workspaces;
    private final CatalogConfigurationPort configurations;

    /**
     * 注入唯一查询端口，避免 application 感知 MCP SDK、Jackson 或文件系统实现。
     */
    public CatalogService(CatalogQueryPort queryPort, ConfigurationGenerationPort generations,
                          ModelPort models, CatalogWorkspacePort workspaces,
                          CatalogConfigurationPort configurations) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.generations = Objects.requireNonNull(generations, "generations");
        this.models = Objects.requireNonNull(models, "models");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
    }


    /**
     * 只用 hi 探测基本对话；仅记录是否收到非空白正文，避免空流误报成功或保留回答内容。
     * 配置租约覆盖完整异步 IO，思考摘要和用量事件不能替代对话回复。
     */
    @Override
    @SuppressWarnings("PMD.CloseResource")
    public CompletionStage<ModelTestResult> testModel(
            String providerId, String modelId, CancellationToken cancellationToken) {
        ConfigurationGenerationPort.Lease lease = generations.acquire(null);
        try {
            CatalogConfigurationPort.ModelProbe probe = configurations.modelProbe(providerId, modelId, lease);
            ModelPort.ModelConfiguration configuration = probe.configuration();
            ModelPort.ModelRequest request = new ModelPort.ModelRequest(
                    configuration,
                    new ModelPort.PromptPayload("", "prompt_model_test_v2"),
                    List.of(new ModelMessage(ModelRole.USER, List.of(new TextContent("hi")))),
                    List.of(), null, 1);
            long started = System.nanoTime();
            AtomicBoolean receivedText = new AtomicBoolean();
            return models.start(request, event -> {
                        if (event instanceof ModelPort.TextDelta(String text) && !text.isBlank()) {
                            receivedText.set(true);
                        }
                        return CompletableFuture.completedFuture(null);
                    }, cancellationToken)
                    .thenApply(ignored -> {
                        if (!receivedText.get()) {
                            throw new IllegalStateException("model returned no reply text");
                        }
                        return new ModelTestResult(probe.modelDisplayName(),
                                Duration.ofNanos(System.nanoTime() - started).toMillis());
                    })
                    .whenComplete((ignored, failure) -> lease.close());
        } catch (RuntimeException | Error failure) {
            lease.close();
            throw failure;
        }
    }

    /**
     * 目录读取只解析已保存 Provider 的当前代际与 Secret，并把异步租约持有到 HTTP 完成；不能接受
     * WebView 传入的 Base URL、协议或 API Key，因此草稿中的未保存连接不会被意外使用。
     */
    @Override
    @SuppressWarnings("PMD.CloseResource") // Lease 在异步 stage 终态关闭；try-with-resources 会提前释放配置快照。
    public CompletionStage<ModelDiscoveryResult> discoverModels(
            String providerId, CancellationToken cancellationToken) {
        ConfigurationGenerationPort.Lease lease = generations.acquire(null);
        try {
            ModelPort.ModelDiscoveryRequest request = configurations.modelDiscoveryRequest(providerId, lease);
            return models.discoverModels(request, cancellationToken)
                    .thenApply(result -> new ModelDiscoveryResult(result.items(), result.truncated()))
                    .whenComplete((ignored, failure) -> lease.close());
        } catch (RuntimeException | Error failure) {
            lease.close();
            throw failure;
        }
    }

    /**
     * 先把不透明 workspaceId 解析为本进程已验证的路径能力，再在同一配置代际内合并发现结果。
     */
    @Override
    public CursorPage<SkillDescriptor> listSkills(String workspaceId, String cursor, int limit) {
        CatalogWorkspacePort.ProjectBinding workspace = skillWorkspace(workspaceId);
        Path workspaceRoot = workspace == null ? null : workspace.root();
        boolean workspaceTrusted = workspace != null && workspace.trusted();
        try (ConfigurationGenerationPort.Lease generation = generations.acquire(workspaceRoot)) {
            return queryPort.listSkills(generation, workspaceRoot, workspaceTrusted, cursor, limit);
        }
    }

    /**
     * 未指定 Workspace 时直接读取全局目录；显式身份才向 Workspace owner 验证 PROJECT，
     * 避免把合法的首启全局请求误当作缺失身份。
     */
    private CatalogWorkspacePort.ProjectBinding skillWorkspace(String workspaceId) {
        if (workspaceId == null) {
            return null;
        }
        return workspaces.project(workspaceId).orElse(null);
    }

    /**
     * 在同一短租约内完成 MCP 状态投影，避免脱敏查询延长 Secret 生命周期。
     */
    @Override
    public CursorPage<McpServerDescriptor> listMcp(String workspaceId, String cursor, int limit) {
        Path workspaceRoot = mcpWorkspaceRoot(workspaceId);
        try (ConfigurationGenerationPort.Lease generation = generations.acquire(workspaceRoot)) {
            return queryPort.listMcp(generation, cursor, limit);
        }
    }

    /**
     * MCP 管理只接受已打开的项目身份，路径仍由 Java Workspace Owner 解析。
     */
    private Path mcpWorkspaceRoot(String workspaceId) {
        CatalogWorkspacePort.ProjectBinding workspace = skillWorkspace(workspaceId);
        return workspace == null ? null : workspace.root();
    }

    /**
     * 让异步探测独占租约直到完成，并在同步抛错与异步完成两条路径都精确释放。
     */
    @Override
    public CompletionStage<McpServerDescriptor> testMcp(String workspaceId, String mcpId) {
        Path workspaceRoot = mcpWorkspaceRoot(workspaceId);
        ConfigurationGenerationPort.Lease generation = generations.acquire(workspaceRoot);
        try {
            generation.snapshot().requireMcp(mcpId);
            return queryPort.testMcp(generation, workspaceRoot, mcpId)
                    .whenComplete((ignored, failure) -> generation.close());
        } catch (RuntimeException | Error failure) {
            generation.close();
            throw failure;
        }
    }

    /**
     * 在同一短租约内校验 MCP 身份并读取 Tool Schema，拒绝 TOCTOU 代际漂移。
     */
    @Override
    public CursorPage<McpToolDescriptor> readMcpTools(
            String workspaceId, String mcpId, String cursor, int limit) {
        Path workspaceRoot = mcpWorkspaceRoot(workspaceId);
        try (ConfigurationGenerationPort.Lease generation = generations.acquire(workspaceRoot)) {
            generation.snapshot().requireMcp(mcpId);
            return queryPort.readMcpTools(generation, workspaceRoot, mcpId, cursor, limit);
        }
    }

    /**
     * 两类 Provider 探测共享的已解析连接字段，不承载模型选择或可见响应数据。
     *
     * @param api 模型 API 协议
     * @param secret 密钥
     * @param requestTimeout 单次请求的超时时间
     */
    private record ProviderConnection(ModelPort.Api api,
                                      String secret,
                                      Duration requestTimeout) {
    }
}


