// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.application;

import io.github.kongweiguang.ja.catalog.domain.McpServerDescriptor;
import io.github.kongweiguang.ja.catalog.domain.McpToolDescriptor;
import io.github.kongweiguang.ja.catalog.domain.SkillDescriptor;
import io.github.kongweiguang.ja.catalog.port.in.CatalogUseCase;
import io.github.kongweiguang.ja.catalog.port.out.CatalogQueryPort;
import io.github.kongweiguang.ja.catalog.port.out.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationGenerationSnapshot;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationError;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * catalog 入站用例实现，只负责编排并把外部 IO 委派给出站端口。
 */
public final class CatalogService implements CatalogUseCase {
    private final CatalogQueryPort queryPort;
    private final ConfigurationGenerationPort generations;
    private final ModelPort models;
    private final WorkspaceUseCase workspaces;

    /**
     * 注入唯一查询端口，避免 application 感知 MCP SDK、Jackson 或文件系统实现。
     */
    public CatalogService(CatalogQueryPort queryPort, ConfigurationGenerationPort generations,
                          ModelPort models, WorkspaceUseCase workspaces) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.generations = Objects.requireNonNull(generations, "generations");
        this.models = Objects.requireNonNull(models, "models");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    }

    /**
     * 模型验证只冻结指定保存身份并发送固定短请求；Sink 丢弃所有流式正文，租约覆盖完整异步 IO。
     */
    @Override
    @SuppressWarnings("PMD.CloseResource")
    public CompletionStage<ModelTestResult> testModel(
            String providerId, String modelId, CancellationToken cancellationToken) {
        ConfigurationGenerationPort.Lease lease = generations.acquire(null);
        try {
            ConfigurationGenerationSnapshot.Provider provider = lease.snapshot().requireProvider(providerId);
            ConfigurationGenerationSnapshot.Model model = lease.snapshot().requireModel(providerId, modelId);
            ModelPort.ModelConfiguration configuration = modelConfiguration(provider, model, lease);
            ModelPort.ModelRequest request = new ModelPort.ModelRequest(
                    configuration,
                    new ModelPort.PromptPayload("Return exactly OK.", "prompt_model_test_v1"),
                    List.of(new ModelMessage(ModelRole.USER, List.of(new TextContent("Reply with OK.")))),
                    List.of(), null, 1);
            long started = System.nanoTime();
            return models.start(request, ignored -> CompletableFuture.completedFuture(null), cancellationToken)
                    .thenApply(ignored -> new ModelTestResult(model.model(),
                            Duration.ofNanos(System.nanoTime() - started).toMillis()))
                    .whenComplete((ignored, failure) -> lease.close());
        } catch (RuntimeException | Error failure) {
            lease.close();
            throw failure;
        }
    }

    /** 验证请求强制 text-only、16 token 和较短网络期限，不继承 Agent 默认生成预算。 */
    private static ModelPort.ModelConfiguration modelConfiguration(
            ConfigurationGenerationSnapshot.Provider provider,
            ConfigurationGenerationSnapshot.Model model,
            ConfigurationGenerationPort.Lease lease) {
        String secret = lease.secretFor(provider.credentialId());
        if (secret == null || secret.isBlank()) {
            throw new ConfigurationError(ConfigurationError.Code.MISSING_CREDENTIAL,
                    "provider credential is unavailable");
        }
        ModelPort.Api api = switch (provider.api()) {
            case OPENAI_RESPONSES -> ModelPort.Api.OPENAI_RESPONSES;
            case ANTHROPIC_MESSAGES -> ModelPort.Api.ANTHROPIC_MESSAGES;
            case OPENAI_CHAT_COMPLETIONS -> ModelPort.Api.OPENAI_CHAT_COMPLETIONS;
        };
        Duration requestTimeout = provider.networkTimeouts().requestTimeout().compareTo(Duration.ofSeconds(30)) > 0
                ? Duration.ofSeconds(30) : provider.networkTimeouts().requestTimeout();
        return new ModelPort.ModelConfiguration(provider.providerId(), model.modelId(), lease.generationId(),
                api, model.model(), provider.baseUrl(), secret,
                provider.networkTimeouts().connectTimeout(), requestTimeout,
                Set.of(ModelPort.InputModality.TEXT),
                new ModelPort.GenerationOptions(null, null, 16, null));
    }

    /**
     * 先把不透明 workspaceId 解析为本进程已验证的路径能力，再在同一配置代际内合并发现结果。
     */
    @Override
    public CursorPage<SkillDescriptor> listSkills(String workspaceId, String cursor, int limit) {
        Workspace workspace = skillWorkspace(workspaceId);
        Path workspaceRoot = workspace == null ? null : workspace.root();
        boolean workspaceTrusted = workspace != null && workspace.trust() == Workspace.Trust.TRUSTED;
        try (ConfigurationGenerationPort.Lease generation = generations.acquire(workspaceRoot)) {
            return queryPort.listSkills(generation, workspaceRoot, workspaceTrusted, cursor, limit);
        }
    }

    /**
     * 通用工作区与省略身份都关闭项目来源；项目路径只能来自已打开工作区，客户端不能注入本地路径。
     */
    private Workspace skillWorkspace(String workspaceId) {
        if (workspaceId == null) return null;
        Workspace workspace = workspaces.requireOpenWorkspace(workspaceId);
        return workspaces.isGeneralWorkspace(workspace.root()) ? null : workspace;
    }

    /**
     * 在同一短租约内完成 MCP 状态投影，避免脱敏查询延长 Secret 生命周期。
     */
    @Override
    public CursorPage<McpServerDescriptor> listMcp(String cursor, int limit) {
        try (ConfigurationGenerationPort.Lease generation = generations.acquire(null)) {
            return queryPort.listMcp(generation, cursor, limit);
        }
    }

    /**
     * 让异步探测独占租约直到完成，并在同步抛错与异步完成两条路径都精确释放。
     */
    @Override
    public CompletionStage<McpServerDescriptor> testMcp(String mcpId) {
        ConfigurationGenerationPort.Lease generation = generations.acquire(null);
        try {
            generation.snapshot().requireMcp(mcpId);
            return queryPort.testMcp(generation, mcpId)
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
    public CursorPage<McpToolDescriptor> readMcpTools(String mcpId, String cursor, int limit) {
        try (ConfigurationGenerationPort.Lease generation = generations.acquire(null)) {
            generation.snapshot().requireMcp(mcpId);
            return queryPort.readMcpTools(generation, mcpId, cursor, limit);
        }
    }
}
