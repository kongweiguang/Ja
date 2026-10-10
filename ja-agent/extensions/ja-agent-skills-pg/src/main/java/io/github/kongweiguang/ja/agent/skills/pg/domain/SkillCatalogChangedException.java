// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.skills.pg.domain;

/** 表示租户在分页期间修改了 Skill 目录；调用方必须从第一页重新发现，不能拼接不同版本。 */
public final class SkillCatalogChangedException extends RuntimeException {
    /**
     * 将目录代际变化作为明确冲突报告，避免调用方把前后两次查询的条目混成一个快照。
     */
    public SkillCatalogChangedException() {
        super("skill_catalog_changed_during_pagination");
    }
}
