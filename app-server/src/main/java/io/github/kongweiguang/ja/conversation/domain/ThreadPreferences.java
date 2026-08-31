// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;

import java.util.Objects;

/** Thread 下一轮使用的模型与权限偏好；每个 Turn 接纳时会复制为独立运行快照。 */
public record ThreadPreferences(String providerId, String modelId, String reasoningLevel,
                                AccessMode accessMode, TitleSource titleSource) {
    /** 偏好只保存稳定选择器与公开能力，不保存端点、凭据或 Provider 私有参数。 */
    public ThreadPreferences {
        providerId = identifier(providerId, "provider_", "providerId");
        modelId = identifier(modelId, "model_", "modelId");
        if (reasoningLevel != null && !reasoningLevel.matches("off|minimal|low|medium|high|xhigh|max")) {
            throw new IllegalArgumentException("invalid reasoningLevel");
        }
        Objects.requireNonNull(accessMode, "accessMode");
        Objects.requireNonNull(titleSource, "titleSource");
    }

    /** 标题归属变化不应重写模型选择，因此只替换来源枚举并保留其它偏好。 */
    public ThreadPreferences withTitleSource(TitleSource source) {
        return new ThreadPreferences(providerId, modelId, reasoningLevel, accessMode,
                Objects.requireNonNull(source, "source"));
    }

    /** 标题来源决定并发写入优先级：系统自动结果只能替换 placeholder，人工标题永久获胜。 */
    public enum TitleSource {
        /** 尚未得到可用标题，允许首次自动结果竞争写入。 */
        PLACEHOLDER,
        /** 首次成功 Turn 的自动标题已经提交。 */
        AUTO,
        /** 用户显式命名，任何迟到自动结果都不得覆盖。 */
        MANUAL
    }

    /** Thread 偏好使用当前稳定身份，不接受已退役的 Profile 前缀或路径字符。 */
    private static String identifier(String value, String prefix, String field) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }
}
