// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.util.Objects;

/** Turn admission 事务冻结的非敏感运行选择；历史恢复不依赖当前配置文档。 */
public record TurnRuntimeSnapshot(String providerId, String modelId, String provider, String api,
                                  String upstreamModel, String reasoningLevel, AccessMode accessMode,
                                  String configGeneration) {
    /** 快照刻意排除 base URL 与 secret；仅保留解释既有 Turn 所需的路由和能力事实。 */
    public TurnRuntimeSnapshot {
        providerId = identifier(providerId, "provider_", "providerId");
        modelId = identifier(modelId, "model_", "modelId");
        provider = closed(provider, "provider", "openai", "anthropic");
        api = closed(api, "api", "openai_responses", "anthropic_messages");
        upstreamModel = text(upstreamModel, "upstreamModel", 512);
        if (reasoningLevel != null && !reasoningLevel.matches("off|minimal|low|medium|high|xhigh|max")) {
            throw new IllegalArgumentException("invalid reasoningLevel");
        }
        Objects.requireNonNull(accessMode, "accessMode");
        configGeneration = ContractChecks.configurationGeneration(configGeneration);
    }

    /** 配置身份使用当前稳定前缀，不接受已退役的 Profile selector。 */
    private static String identifier(String value, String prefix, String field) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /** Provider/API 是历史解释所需闭集，未知值必须在存储边界失败而非透传。 */
    private static String closed(String value, String field, String... allowed) {
        for (String candidate : allowed) if (candidate.equals(value)) return value;
        throw new IllegalArgumentException("invalid " + field);
    }

    /** 上游模型名允许厂商字符，但禁止控制字符和无界内容。 */
    private static String text(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
            || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }
}
