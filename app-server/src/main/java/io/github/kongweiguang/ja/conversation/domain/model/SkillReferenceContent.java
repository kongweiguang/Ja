// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/** 本条用户消息显式选择的已启用 Skill 身份。 */
public record SkillReferenceContent(String skillId) implements UserContentBlock {
    private static final Pattern SKILL_REFERENCE = Pattern.compile(
            "(?:user|ja|project):[^:\\u0000-\\u001F]{1,512}");

    /**
     * Skill 引用携带来源以防同名项目包继承全局授权；这里不解析或读取文件，正文仍在
     * 当前配置代际的激活路径中读取。
     */
    public SkillReferenceContent {
        skillId = Objects.requireNonNull(skillId, "skillId");
        if (!SKILL_REFERENCE.matcher(skillId).matches()) {
            throw new IllegalArgumentException("invalid skillId");
        }
    }
}
