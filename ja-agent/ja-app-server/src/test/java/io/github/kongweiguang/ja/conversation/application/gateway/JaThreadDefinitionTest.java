// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 Ja 首次创建 Thread 时必须冻结完整应用偏好。 */
final class JaThreadDefinitionTest {
    /** 持久层不得在创建时补造缺失偏好，否则恢复请求身份会与用户选择不一致。 */
    @Test
    void rejectsThreadWithoutPreferences() {
        assertThrows(NullPointerException.class, () -> new JaThreadDefinition(
                "thr_test", "ws_test", "test", null, Instant.parse("2026-09-07T00:00:00Z")));
    }
}
