// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import java.util.Objects;
import java.util.Set;

/**
 * Plan/Task 工具用最终安全目录摘要与依赖版本固定本次请求绑定。
 *
 * @param toolCatalogDigest 工具目录摘要
 * @param mcpCatalogRevision MCP 目录修订版本
 * @param skillIds Skill 标识集合
 */
public record JaToolCatalogIdentity(String toolCatalogDigest,
                                    String mcpCatalogRevision,
                                    Set<String> skillIds) {
    /**
     * 冻结摘要与 Skill 身份，避免绑定阶段观察后续变化的可变集合。
     */
    public JaToolCatalogIdentity {
        if (toolCatalogDigest == null || !toolCatalogDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid toolCatalogDigest");
        }
        if (mcpCatalogRevision == null || mcpCatalogRevision.isBlank()
            || mcpCatalogRevision.length() > 256
            || mcpCatalogRevision.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid mcpCatalogRevision");
        }
        skillIds = Set.copyOf(Objects.requireNonNull(skillIds, "skillIds"));
        if (skillIds.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("invalid skillIds");
        }
    }
}
