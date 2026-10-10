// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.skill;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

/**
 * 对已发现 Skill 的有界相对资源读取请求；它限制路径别名和字符数，但不能替代来源适配器的物理边界检查。
 *
 * @param skillName Skill 名称
 * @param resourcePath 资源路径
 * @param maxCharacters 文本字符数上限
 */
public record SkillReadRequest(String skillName,
                               String resourcePath,
                               int maxCharacters) {
    /**
     * 在跨扩展边界前统一分隔符并拒绝绝对路径、空段与点段；物理文件系统链接仍由具体来源验证最终落点。
     */
    public SkillReadRequest {
        skillName = ContractChecks.identifier(skillName, "skillName");
        resourcePath = ContractChecks.text(resourcePath, "resourcePath", 1_024, false).replace('\\', '/');
        if (resourcePath.startsWith("/") || resourcePath.endsWith("/") || resourcePath.contains("//")
            || resourcePath.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("skill_resource_path_invalid");
        }
        for (String segment : resourcePath.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("skill_resource_path_invalid");
            }
        }
        if (maxCharacters < 1 || maxCharacters > 4_000_000) {
            throw new IllegalArgumentException("invalid Skill read character limit");
        }
    }
}
