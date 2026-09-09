// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.ProviderRequestProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.profile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 持久化端口事实进入事务前的首版不变量测试。 */
final class ConversationRepositoryFactsTest {

    /** UNKNOWN Usage 必须携带 dispatch 时的完整 Profile，不能重建或省略请求身份。 */
    @Test
    void acceptsUnknownUsageWithCompleteProfile() {
        ProviderRequestProfile requestProfile = profile("provider_test", "model_test", "cfg_test");

        ConversationRepository.UsageFact usage = new ConversationRepository.UsageFact(
                "request_test", null, 1, 1, ConversationRepository.UsagePurpose.ASSISTANT,
                ConversationRepository.UsageCertainty.UNKNOWN, requestProfile);

        assertEquals(requestProfile, usage.profile());
    }

    /** 缺少 Profile 的请求事实不得进入 SQLite，即使 token 计量也处于 UNKNOWN。 */
    @Test
    void rejectsUsageWithoutProfile() {
        assertThrows(NullPointerException.class, () -> new ConversationRepository.UsageFact(
                "request_test", null, 1, 1, ConversationRepository.UsagePurpose.ASSISTANT,
                ConversationRepository.UsageCertainty.UNKNOWN, null));
    }

    /** 首版 Thread 创建始终冻结完整偏好，禁止依赖历史默认值补写。 */
    @Test
    void rejectsThreadWithoutPreferences() {
        assertThrows(NullPointerException.class, () -> new ConversationRepository.ThreadDefinition(
                "thr_test", "ws_test", "test", null, Instant.parse("2026-09-07T00:00:00Z")));
    }
}
