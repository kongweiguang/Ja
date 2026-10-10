// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 保存 Ja 展示与运行审计所需的非敏感模型请求事实，不进入可复用 Core。
 *
 * @param providerId 模型供应商标识
 * @param modelId 模型标识
 * @param api 模型 API 协议
 * @param upstreamModel 上游模型
 * @param requestedReasoning 调用方请求的推理等级
 * @param effectiveReasoning 实际生效的推理等级
 * @param accessMode 访问模式
 * @param collaborationMode 协作模式
 * @param configGeneration 冻结的配置代际标识
 * @param promptRevision 提示词修订版本
 * @param toolCatalogRevision 工具目录修订版本
 * @param contextWindowTokens 模型上下文窗口的 Token 容量
 * @param maxOutputTokens 单次模型输出的 Token 上限
 */
public record ProviderRequestProfile(String providerId,
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
     * 拒绝密钥、端点、协议别名和无效预算进入持久请求档案。
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
        configGeneration = configurationGeneration(configGeneration);
        promptRevision = boundedText(promptRevision, "promptRevision", 256);
        toolCatalogRevision = sha256(toolCatalogRevision, "toolCatalogRevision");
        if (contextWindowTokens < 1 || contextWindowTokens > 4_000_000
            || maxOutputTokens < 1 || maxOutputTokens > 1_000_000
            || maxOutputTokens > contextWindowTokens) {
            throw new IllegalArgumentException("invalid Provider token limits");
        }
    }

    /**
     * 仅允许稳定配置身份，避免展示名称成为 Provider 路由键。
     */
    private static String identifier(String value, String prefix, String field) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 协议字段采用支持的原生 API 闭集，不接受配置别名。
     */
    private static String closed(String value, String... allowed) {
        for (String candidate : allowed) {
            if (candidate.equals(value)) {
            return value;
        }
        }
        throw new IllegalArgumentException("invalid api");
    }

    /**
     * reasoning 可省略表示模型默认；显式值必须是 Ja 支持的公开档位。
     */
    private static String reasoning(String value, String field) {
        if (value != null && !value.matches("off|minimal|low|medium|high|xhigh|max")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 只保存有界非空审计文本，防止 prompt 或其他无界内容混入档案。
     */
    private static String boundedText(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
            || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 工具目录版本是内容寻址值，确保相等性代表相同目录事实。
     */
    private static String sha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * Provider 档案只接受 Ja 配置代际身份，避免依赖可复用 Core 的内部验证包。
     */
    private static String configurationGeneration(String value) {
        if (value == null || value.length() > 128 || !value.matches("cfg_[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("invalid configGeneration");
        }
        return value;
    }

    /**
     * 摘要改变最终 system prompt 时生成新档案，避免复用旧请求的上下文指纹。
     */
    public ProviderRequestProfile withPromptRevision(String revision) {
        return new ProviderRequestProfile(providerId, modelId, api, upstreamModel, requestedReasoning,
                effectiveReasoning, accessMode, collaborationMode, configGeneration, revision, toolCatalogRevision,
                contextWindowTokens, maxOutputTokens);
    }

    /**
     * 仅对模型路由与配置代际求指纹，prompt 和工具目录变化不重置模型阶段。
     */
    public String modelBindingFingerprint() {
        String value = String.join("\n", providerId, modelId, api, upstreamModel,
                String.valueOf(requestedReasoning), String.valueOf(effectiveReasoning),
                accessMode.name(), collaborationMode.name(), configGeneration,
                Integer.toString(contextWindowTokens), Integer.toString(maxOutputTokens));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}

