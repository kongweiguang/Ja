// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.skill;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

/**
 * 单次请求实际读取到的 Skill 文本及其来源版本和内容指纹；持久化此值可以说明执行使用的确切规则，而非后续新版本。
 *
 * @param skillName Skill 名称
 * @param resourcePath 资源路径
 * @param sourceId 来源标识
 * @param sourceVersion 来源版本
 * @param content 内容
 * @param truncated 结果是否被截断
 * @param contentFingerprint 内容指纹
 */
public record SkillDocument(String skillName,
                            String resourcePath,
                            String sourceId,
                            long sourceVersion,
                            String content,
                            boolean truncated,
                            String contentFingerprint) {
    /**
     * 校验正文及指纹边界，确保运行事实只记录已返回文本的摘要且使用发现时的来源版本。
     */
    public SkillDocument {
        skillName = ContractChecks.identifier(skillName, "skillName");
        resourcePath = ContractChecks.text(resourcePath, "resourcePath", 1_024, false);
        sourceId = ContractChecks.identifier(sourceId, "sourceId");
        if (sourceVersion < 0) {
            throw new IllegalArgumentException("skill version must be non-negative");
        }
        content = ContractChecks.text(content, "content", 4_000_000, true);
        if (contentFingerprint == null || !contentFingerprint.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid Skill content fingerprint");
        }
    }
}
