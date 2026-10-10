// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.domain.model;

import java.util.Objects;

/**
 * 表示不含 Skill 正文和来源路径的公开目录项。
 *
 * @param skillId Skill 标识
 * @param name 名称
 * @param scope 适用范围
 * @param enabled 是否启用
 * @param status 当前状态分类
 * @param description 说明
 */
public record SkillDescriptor(String skillId,
                              String name,
                              String scope,
                              boolean enabled,
                              String status,
                              String description) {
    /**
     * 保证目录投影完整，避免 Wire 层用空字符串掩盖配置损坏。
     */
    public SkillDescriptor {
        Objects.requireNonNull(skillId, "skillId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(description, "description");
    }
}
