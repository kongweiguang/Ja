// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.model;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

/**
 * 本条用户消息显式选择的已启用 Skill 身份。
 *
 * @param skillId Skill 标识
 */
public record SkillReferenceContent(String skillId) implements UserContentBlock {
    /**
     * 来源由宿主注册决定，不限定 Ja 的目录类型；统一引用语法只校验身份，不执行读取或授予权限。
     */
    public SkillReferenceContent {
        skillId = ContractChecks.runtimeSkillIdentifier(skillId);
    }
}
