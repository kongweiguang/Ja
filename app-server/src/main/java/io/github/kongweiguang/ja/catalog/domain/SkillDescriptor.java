// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.domain;

import java.util.Objects;

/**
 * 表示不含 Skill 正文和来源路径的公开目录项。
 */
public record SkillDescriptor(String skillId, String name, String scope, boolean enabled,
                              String status, String description) {
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
