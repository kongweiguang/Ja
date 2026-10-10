// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 固定供应商原生 callId 的 Ja 跨端语法与不改写约束。 */
final class JaToolCallIdTest {
    /** 接受不同 Provider 的原生 ID 形状，并验证返回值与输入完全一致。 */
    @Test
    void acceptsProviderNativeIdsWithoutRewriting() {
        assertEquals("call_abc-123", JaToolCallId.require("call_abc-123"));
        assertEquals("toolu_01Jxyz:part.2", JaToolCallId.require("toolu_01Jxyz:part.2"));
        assertEquals("A", JaToolCallId.require("A"));
    }

    /** 拒绝空值、非 ASCII、分隔符首位错误及超过 JA-RPC 上限的 ID。 */
    @Test
    void rejectsIdsOutsideCanonicalContract() {
        assertThrows(NullPointerException.class, () -> JaToolCallId.require(null));
        assertThrows(IllegalArgumentException.class, () -> JaToolCallId.require(""));
        assertThrows(IllegalArgumentException.class, () -> JaToolCallId.require(":"));
        assertThrows(IllegalArgumentException.class, () -> JaToolCallId.require("调用_1"));
        assertThrows(IllegalArgumentException.class, () -> JaToolCallId.require("a".repeat(129)));
    }
}
