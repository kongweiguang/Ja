// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

/** 本条用户消息显式选择的已启用 Skill 身份。 */
public record SkillReferenceContent(String skillId) implements UserContentBlock {
    /** Skill ID 只作不透明配置身份；名称和 SKILL.md 必须在实际消息边界由冻结代际解析。 */
    public SkillReferenceContent {
        skillId = ContractChecks.identifier(skillId, "skillId");
        if (!skillId.startsWith("skill_")) throw new IllegalArgumentException("invalid skillId");
    }
}
