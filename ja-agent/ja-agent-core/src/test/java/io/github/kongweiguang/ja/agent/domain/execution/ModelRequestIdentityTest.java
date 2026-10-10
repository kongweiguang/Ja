// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.domain.execution;

import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 请求审计必须保留真实预算，不能为满足存储构造器而伪造输出 Token 预留。 */
final class ModelRequestIdentityTest {
    /** ContextBudget 允许不预留输出，审计 DTO 应接受相同零值而不是把请求阻断。 */
    @Test
    void preservesZeroOutputReservation() {
        assertEquals(0, identity(0).maxOutputTokens());
    }

    /** 允许零预留不等于允许无效窗口，负数和超过上下文的输出上限仍须拒绝。 */
    @Test
    void rejectsImpossibleOutputReservation() {
        assertThrows(IllegalArgumentException.class, () -> identity(-1));
        assertThrows(IllegalArgumentException.class, () -> identity(4097));
    }

    /** 使用中立供应商字段，只改变本测试关注的输出预留边界。 */
    private static ExecutionFacts.ModelRequestIdentity identity(int output) {
        return new ExecutionFacts.ModelRequestIdentity("provider", "model", "custom-api", "model",
                null, null, 4096, output, JsonObject.empty());
    }
}
