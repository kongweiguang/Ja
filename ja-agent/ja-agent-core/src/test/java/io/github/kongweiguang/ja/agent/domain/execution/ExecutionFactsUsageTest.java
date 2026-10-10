// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.execution;

import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证通用模型用量事实保留请求身份，并区分本地估算与供应商结算。 */
final class ExecutionFactsUsageTest {
    /** UNKNOWN 助手请求可携带上下文估算，但完整请求 Profile 必须随事实冻结。 */
    @Test
    void acceptsUnknownAssistantEstimateWithCompleteRequestIdentity() {
        ExecutionFacts.ModelRequestIdentity profile = profile();

        ExecutionFacts.Usage usage = new ExecutionFacts.Usage("modelreq_test", null, 1, 1,
                ExecutionFacts.UsagePurpose.ASSISTANT, ExecutionFacts.UsageCertainty.UNKNOWN, profile, 123L);

        assertEquals(profile, usage.profile());
        assertEquals(123L, usage.estimatedContextTokens());
    }

    /** 估算不能伪装摘要 Provider 用量或已经结算的真实 token 计数。 */
    @Test
    void rejectsEstimatesOutsideUnknownAssistantRequests() {
        ExecutionFacts.ModelRequestIdentity profile = profile();

        assertThrows(IllegalArgumentException.class, () -> new ExecutionFacts.Usage("modelreq_summary", null, 1, 1,
                ExecutionFacts.UsagePurpose.SUMMARY, ExecutionFacts.UsageCertainty.UNKNOWN, profile, 123L));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionFacts.Usage("modelreq_known",
                new ModelUsage(1, 0, 1), 1, 1, ExecutionFacts.UsagePurpose.ASSISTANT,
                ExecutionFacts.UsageCertainty.KNOWN, profile, 123L));
    }

    /** 即使实际用量尚未知，缺少请求身份也不能进入可恢复的执行事实。 */
    @Test
    void rejectsUsageWithoutRequestIdentity() {
        assertThrows(NullPointerException.class, () -> new ExecutionFacts.Usage("modelreq_test", null, 1, 1,
                ExecutionFacts.UsagePurpose.ASSISTANT, ExecutionFacts.UsageCertainty.UNKNOWN, null, null));
    }

    /** 用无端点、无凭据的稳定 Profile 验证事实结构，避免测试依赖业务配置对象。 */
    private static ExecutionFacts.ModelRequestIdentity profile() {
        return new ExecutionFacts.ModelRequestIdentity("provider_test", "model_test", "responses", "upstream-model",
                null, null, 100_000, 8_192, JsonObject.empty());
    }
}
