// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定派发前估算与 Provider 结算的身份边界及可恢复故障分类。 */
final class AgentModelStepDurableUsageTest {
    /** 估算重试必须复用稳定 ID，同时不能与真实 Provider 用量共用记录。 */
    @Test
    void estimatedUsageHasStableIdentitySeparateFromProviderSettlement() {
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(
                new AgentStore.ConversationKey("tenant", "thread", "user"), "turn");

        String estimate = AgentExecutionDriver.estimatedUsageRecordId(execution, "model_request");

        assertEquals(estimate, AgentExecutionDriver.estimatedUsageRecordId(execution, "model_request"));
        assertNotEquals(AgentExecutionDriver.recordId(execution, "agent.usage", "model_request"), estimate);
    }

    /** Provider 不可用保持持续退避，确定性协议错误和上游拒绝不被重试。 */
    @Test
    void retriesOnlyRecoverableProviderFailures() {
        assertTrue(AgentModelStep.isRetryableFailure("MODEL_UNAVAILABLE"));
        assertTrue(AgentModelStep.isRetryableFailure("MODEL_STREAM_INVALID"));
        assertTrue(AgentModelStep.isRetryableFailure("MODEL_IDLE_TIMEOUT"));
        assertFalse(AgentModelStep.isRetryableFailure("MODEL_PROTOCOL_ERROR"));
        assertFalse(AgentModelStep.isRetryableFailure("MODEL_UPSTREAM_REJECTED"));
    }
}
