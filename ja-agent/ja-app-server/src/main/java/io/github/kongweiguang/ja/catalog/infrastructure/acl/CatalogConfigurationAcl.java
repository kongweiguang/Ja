// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.infrastructure.acl;

import io.github.kongweiguang.ja.catalog.application.gateway.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.catalog.application.gateway.CatalogConfigurationPort;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationError;
import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationGenerationSnapshot;

import java.time.Duration;
import java.util.Objects;

/**
 * Catalog 读取 configuration 的 ACL；把配置领域的 Provider/Model 投影转换为 Catalog 所需的模型请求。
 *
 * <p>配置代际和凭据租约仍由调用方持有，本类只负责跨上下文转换，避免 Catalog application service
 * 直接依赖 configuration domain 的内部投影。</p>
 */
public final class CatalogConfigurationAcl implements CatalogConfigurationPort {
    /** 配置转换是无状态的，禁止建立会缓存 Secret 或 Provider 投影的实例。 */
    public CatalogConfigurationAcl() {
    }

    /**
     * 在同一配置租约内构造模型探测请求，并保留展示名称供 Catalog 用例返回。
     *
     * @param providerId 配置中的 Provider 标识
     * @param modelId 配置中的模型标识
     * @param lease 已冻结且仍由调用方负责关闭的配置租约
     * @return 模型请求及其稳定展示名
     */
    @Override
    public ModelProbe modelProbe(String providerId, String modelId,
                                 ConfigurationGenerationPort.Lease lease) {
        ConfigurationGenerationPort.Lease value = Objects.requireNonNull(lease, "lease");
        ConfigurationGenerationSnapshot.Provider provider = value.snapshot().requireProvider(providerId);
        ConfigurationGenerationSnapshot.Model model = value.snapshot().requireModel(providerId, modelId);
        return new ModelProbe(modelConfiguration(provider, model, value), model.model());
    }

    /**
     * 从已保存 Provider 配置构造目录发现请求，禁止调用方传入草稿 URL 或凭据。
     *
     * @param providerId Provider 标识
     * @param lease 已冻结配置租约
     * @return Provider 发现请求
     */
    @Override
    public ModelPort.ModelDiscoveryRequest modelDiscoveryRequest(
            String providerId, ConfigurationGenerationPort.Lease lease) {
        ConfigurationGenerationPort.Lease value = Objects.requireNonNull(lease, "lease");
        ConfigurationGenerationSnapshot.Provider provider = value.snapshot().requireProvider(providerId);
        ProviderConnection connection = providerConnection(provider, value);
        return new ModelPort.ModelDiscoveryRequest(provider.providerId(), value.generationId(), connection.api(),
                provider.baseUrl(), connection.secret(), provider.networkTimeouts().connectTimeout(),
                connection.requestTimeout());
    }

    /**
     * 从冻结 Provider/Model 投影生成统一的模型请求配置，30 秒上限避免目录操作长期占用租约。
     *
     * @param provider Provider 配置
     * @param model 模型配置
     * @param lease 配置租约
     * @return 模型请求配置
     */
    private static ModelPort.ModelConfiguration modelConfiguration(
            ConfigurationGenerationSnapshot.Provider provider,
            ConfigurationGenerationSnapshot.Model model,
            ConfigurationGenerationPort.Lease lease) {
        ProviderConnection connection = providerConnection(provider, lease);
        return new ModelPort.ModelConfiguration(provider.providerId(), model.modelId(), lease.generationId(),
                connection.api(), model.model(), provider.baseUrl(), connection.secret(),
                provider.networkTimeouts().connectTimeout(), connection.requestTimeout(),
                java.util.Set.of(ModelPort.InputModality.TEXT),
                model.capabilities().supportsOutputSchema(),
                new ModelPort.GenerationOptions(null, null, 16, null));
    }

    /**
     * 解析配置中的凭据与协议，保持 API 映射和请求期限的单一边界。
     *
     * @param provider Provider 配置
     * @param lease 配置租约
     * @return 模型协议、凭据和有界请求期限
     */
    private static ProviderConnection providerConnection(
            ConfigurationGenerationSnapshot.Provider provider,
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
        return new ProviderConnection(api, secret, requestTimeout);
    }

    /**
     * ACL 的内部模型探测投影；不把 configuration Provider/Model 领域类型泄漏到 Catalog application。
     *
     * @param configuration 模型调用配置
     * @param modelDisplayName 返回给设置页的模型展示名
     */
    /**
     * Provider 凭据与协议的短生命周期投影，禁止存入共享状态。
     *
     * @param api Provider 协议
     * @param secret 本次租约内使用的凭据
     * @param requestTimeout 请求超时上限
     */
    private record ProviderConnection(ModelPort.Api api,
                                      String secret,
                                      Duration requestTimeout) {
        /** 保证 ACL 内部转换结果完整，调用方只能在当前租约内使用。 */
        private ProviderConnection {
            Objects.requireNonNull(api, "api");
            Objects.requireNonNull(secret, "secret");
            Objects.requireNonNull(requestTimeout, "requestTimeout");
        }
    }
}


