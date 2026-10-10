// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.skill;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.util.regex.Pattern;

/**
 * 来源发现时返回的稳定 Skill 元数据；不包含物理路径、访问授权或正文缓存，版本用于将后续读取绑定到发现结果。
 *
 * @param name 名称
 * @param description 说明
 * @param sourceId 来源标识
 * @param version 版本
 */
public record SkillDescriptor(String name,
                              String description,
                              String sourceId,
                              long version) {
    private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    /**
     * 在发现边界校验可移植名称、描述、来源标识和非负版本，避免适配器把不稳定路径或无效身份交给内核。
     */
    public SkillDescriptor {
        name = ContractChecks.identifier(name, "name");
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("skill_name_invalid");
        }
        description = ContractChecks.text(description, "description", 16_384, false);
        sourceId = ContractChecks.skillSourceIdentifier(sourceId);
        if (version < 0) {
            throw new IllegalArgumentException("skill version must be non-negative");
        }
    }

    /**
     * 返回来源内稳定且与租户无关的引用；引用只用于目录匹配，存储查询仍必须单独按租户隔离。
     */
    public String reference() {
        return sourceId + ":" + name;
    }
}
