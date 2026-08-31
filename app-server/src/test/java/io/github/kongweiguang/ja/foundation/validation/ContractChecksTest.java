// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.validation;

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
}
