// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

import java.util.Objects;

/**
 * Thread 创建时冻结的子智能体策略；独立于 Thread DTO，避免设置变更回写既有会话。
 */
public record SubagentPolicy(boolean enabled, String providerId, String modelId, String reasoningLevel) {
    /**
     * provider/model 必须成对出现；两者同时为空表示派发时跟随父 Turn 的冻结模型。
     */
    public SubagentPolicy {
        if ((providerId == null) != (modelId == null)) {
            throw new IllegalArgumentException("subagent provider and model must be paired");
        }
        if (providerId != null) {
            providerId = identifier(providerId, "provider_", "providerId");
            modelId = identifier(modelId, "model_", "modelId");
        } else if (reasoningLevel != null) {
            throw new IllegalArgumentException("follow-parent subagent policy cannot set reasoning level");
        }
        if (reasoningLevel != null && !reasoningLevel.matches("off|minimal|low|medium|high|xhigh|max")) {
            throw new IllegalArgumentException("invalid subagent reasoning level");
        }
    }

    /** 默认开启且跟随父任务，作为已有 Thread 的一次性迁移值。 */
    public static SubagentPolicy defaultPolicy() {
        return new SubagentPolicy(true, null, null, null);
    }

    /** 指定模型仍保留 enabled=false 时的选择，重新开启无需丢失用户选择。 */
    public boolean followsParent() {
        return providerId == null;
    }

    /** 路由身份严格限定为不透明键，禁止配置文本被解释为文件路径或脚本。 */
    private static String identifier(String value, String prefix, String field) {
        // 策略会进入 SQLite 并参与模型路由；这里拒绝路径/控制字符，避免把设置值变成隐式配置通道。
        Objects.requireNonNull(value, field);
        if (!value.startsWith(prefix) || value.length() > 128
                || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }
}
