// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.skill;

import java.util.List;
import java.util.Objects;

/**
 * 一页不可变的 Skill 元数据及来源专属续页游标；内核只检测游标是否前进，不解释其存储含义。
 *
 * @param skills Skill 列表
 * @param nextCursor 读取下一页时使用的游标
 */
public record SkillPage(List<SkillDescriptor> skills,
                        String nextCursor) {
    /**
     * 复制来源返回的列表并约束终页游标为空，防止排序期间外部修改条目或用空字符串表达模糊的结束状态。
     */
    public SkillPage {
        skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
        if (skills.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("skill page contains null descriptor");
        }
        if (nextCursor != null && nextCursor.isBlank()) {
            throw new IllegalArgumentException("skill page cursor must be null or non-blank");
        }
    }
}
