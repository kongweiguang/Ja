// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 配置代际身份的共享边界测试。 */
final class ContractChecksTest {
    /** Base64URL 代际允许连字符和下划线，避免拒绝真实 SHA-256 派生身份。 */
    @Test
    void configurationGenerationAcceptsOwnerFormat() {
        assertEquals("cfg_-Abc_123", ContractChecks.configurationGeneration("cfg_-Abc_123"));
    }

    /** 旧 Profile revision 即使是普通安全标识也不能重新进入配置代际调用链。 */
    @Test
    void configurationGenerationRejectsLegacyProfileIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> ContractChecks.configurationGeneration("profile_fixture"));
    }

    /** Skill 引用须兼容 Descriptor 可生成的最长名称，同时保留完整自定义来源和 bundled 来源。 */
    @Test
    void runtimeSkillIdentifierAcceptsDescriptorNameLimitAndLongSource() {
        String name = "a".repeat(256);
        String source = "s".repeat(256);
        String customReference = source + ":" + name;

        assertEquals(customReference, ContractChecks.runtimeSkillIdentifier(customReference));
        assertEquals("bundled:" + name, ContractChecks.runtimeSkillIdentifier("bundled:" + name));
    }

    /** 257 字符名称不能进入运行期引用，因为任何 SkillDescriptor 都无法产生该名称。 */
    @Test
    void runtimeSkillIdentifierRejectsNameBeyondDescriptorLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> ContractChecks.runtimeSkillIdentifier("bundled:" + "a".repeat(257)));
    }
}
