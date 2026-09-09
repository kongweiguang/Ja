// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.util.Objects;

/**
 * 一次真实 Provider 请求采用的完整非敏感运行事实；相等性就是 continuation 的唯一复用条件。
 */
public record ProviderRequestProfile(
        String providerId,
        String modelId,
        String api,
        String upstreamModel,
        String requestedReasoning,
        String effectiveReasoning,
        AccessMode accessMode,
        CollaborationMode collaborationMode,
        String configGeneration,
        String promptRevision,
        String toolCatalogRevision,
        int contextWindowTokens,
        int maxOutputTokens) {

    /**
     * Profile 只保存审计与等价判断所需事实，拒绝端点、凭据和协议别名进入恢复状态。
     */
    public ProviderRequestProfile {
        providerId = identifier(providerId, "provider_", "providerId");
        modelId = identifier(modelId, "model_", "modelId");
        api = closed(api, "openai_responses", "anthropic_messages", "openai_chat_completions");
        upstreamModel = boundedText(upstreamModel, "upstreamModel", 512);
        requestedReasoning = reasoning(requestedReasoning, "requestedReasoning");
        effectiveReasoning = reasoning(effectiveReasoning, "effectiveReasoning");
        Objects.requireNonNull(accessMode, "accessMode");
        Objects.requireNonNull(collaborationMode, "collaborationMode");
        configGeneration = ContractChecks.configurationGeneration(configGeneration);
        promptRevision = boundedText(promptRevision, "promptRevision", 256);
        toolCatalogRevision = sha256(toolCatalogRevision, "toolCatalogRevision");
        if (contextWindowTokens < 1 || contextWindowTokens > 4_000_000
                || maxOutputTokens < 1 || maxOutputTokens > 1_000_000
                || maxOutputTokens > contextWindowTokens) {
            throw new IllegalArgumentException("invalid Provider token limits");
        }
    }

    /** Summary 或收口会改变实际 system prompt，调用方必须以最终 revision 生成新的不可变 Profile。 */
    public ProviderRequestProfile withPromptRevision(String revision) {
        return new ProviderRequestProfile(providerId, modelId, api, upstreamModel, requestedReasoning,
                effectiveReasoning, accessMode, collaborationMode, configGeneration, revision, toolCatalogRevision,
                contextWindowTokens, maxOutputTokens);
    }

    /** Provider/Model 使用配置合同的稳定前缀，避免展示名称成为路由身份。 */
    private static String identifier(String value, String prefix, String field) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
                || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /** 请求协议是当前三种原生 API 的严格闭集，不接受配置层之外的别名。 */
    private static String closed(String value, String... allowed) {
        for (String candidate : allowed) if (candidate.equals(value)) return value;
        throw new IllegalArgumentException("invalid api");
    }

    /** reasoning 允许由模型默认，但显式值必须落在产品公开等级内。 */
    private static String reasoning(String value, String field) {
        if (value != null && !value.matches("off|minimal|low|medium|high|xhigh|max")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /** 修订与上游模型允许厂商字符，但禁止空白、控制字符和无界正文。 */
    private static String boundedText(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /** Tool catalog 使用内容寻址修订，防止松散字符串相等掩盖路由漂移。 */
    private static String sha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }
}
