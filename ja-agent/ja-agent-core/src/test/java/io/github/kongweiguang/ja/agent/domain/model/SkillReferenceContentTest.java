// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 可复用 Core 的 Skill 选择不能依赖 Ja 本地来源枚举，也不能接受歧义引用。 */
final class SkillReferenceContentTest {
    /** PostgreSQL 或业务自定义来源与本地来源使用相同引用契约。 */
    @Test
    void acceptsRegisteredSourceNamesWithoutJaDirectoryAssumptions() {
        for (String reference : List.of("pg:guide", "business.docs:guide-v2", "project:guide")) {
            assertEquals(reference, new SkillReferenceContent(reference).skillId());
        }
    }

    /** 引用的来源和包名各占一个确定段，不允许路径或多余分隔符改变解析目标。 */
    @Test
    void rejectsAmbiguousOrPathLikeReferences() {
        for (String reference : List.of("guide", ":guide", "pg:", "pg:guide:extra", "pg:../guide", "pg:bad name")) {
            assertThrows(IllegalArgumentException.class, () -> new SkillReferenceContent(reference));
        }
    }
}
