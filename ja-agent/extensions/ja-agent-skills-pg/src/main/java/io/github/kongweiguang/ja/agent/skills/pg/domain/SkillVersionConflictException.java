// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.skills.pg.domain;

/** 表示 Skill 包的乐观版本已变化；错误不区分其他租户是否存在同名数据。 */
public final class SkillVersionConflictException extends RuntimeException {
    /**
     * 将过期写入与数据库故障区分，调用方可重新读取最新版本后再决定如何处理冲突。
     */
    public SkillVersionConflictException() {
        super("skill_version_conflict");
    }

    /** 保留数据库唯一约束的底层原因，同时继续对调用方暴露稳定的冲突标识。 */
    public SkillVersionConflictException(Throwable cause) {
        super("skill_version_conflict", cause);
    }
}
