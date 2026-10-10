// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.domain.model;

import java.util.Objects;

/**
 * Collaboration 上下文保存的最小执行偏好；不把 conversation 的 Thread 类型带入领域模型。
 *
 * @param providerId        提供方稳定标识
 * @param modelId           模型稳定标识
 * @param reasoningLevel    推理等级，可为空
 * @param accessMode        工具访问权限
 * @param collaborationMode 协作执行模式
 * @param titleSource       标题写入来源
 */
public record CollaborationPreferences(String providerId,
                                       String modelId,
                                       String reasoningLevel,
                                       AccessMode accessMode,
                                       CollaborationMode collaborationMode,
                                       TitleSource titleSource) {
    /**
     * 偏好值在边界冻结，避免后续 conversation 配置变化反向改变已提交 Task。
     */
    public CollaborationPreferences {
        providerId = identifier(providerId, "provider_", "providerId");
        modelId = identifier(modelId, "model_", "modelId");
        if (reasoningLevel != null && !reasoningLevel.matches("off|minimal|low|medium|high|xhigh|max")) {
            throw new IllegalArgumentException("invalid reasoningLevel");
        }
        Objects.requireNonNull(accessMode, "accessMode");
        Objects.requireNonNull(collaborationMode, "collaborationMode");
        Objects.requireNonNull(titleSource, "titleSource");
    }

    /**
     * 保留模型和权限选择，只替换标题所有权，避免命令层重新拼接偏好。
     */
    public CollaborationPreferences withTitleSource(TitleSource source) {
        return new CollaborationPreferences(providerId, modelId, reasoningLevel, accessMode,
                collaborationMode, Objects.requireNonNull(source, "source"));
    }

    /**
     * 只允许稳定的本地选择器，防止把 endpoint 或凭据片段写入 Task 事实。
     */
    private static String identifier(String value, String prefix, String field) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
                || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /** Task 自有权限闭集，ACL 负责转换为 conversation 的执行策略。 */
    public enum AccessMode {
        /** 需要用户审批副作用工具。 */
        APPROVAL_REQUIRED,
        /** 允许已登记工具直接执行。 */
        FULL_ACCESS
    }

    /** Task 自有协作闭集，避免领域模型依赖 conversation 偏好。 */
    public enum CollaborationMode {
        /** 直接执行请求。 */
        DEFAULT,
        /** 先执行批准后的 Plan。 */
        PLAN
    }

    /** 标题写入所有权，保持和现有持久投影相同的三态语义。 */
    public enum TitleSource {
        /** 允许自动标题竞争。 */
        PLACEHOLDER,
        /** 自动标题已提交。 */
        AUTO,
        /** 用户标题永久优先。 */
        MANUAL
    }
}
