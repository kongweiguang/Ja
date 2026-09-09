// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import org.junit.jupiter.api.Test;

import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.profile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Provider 请求用量首版不变量测试。 */
final class ProviderRequestUsageTest {

    /** UNKNOWN 仍是可靠事实，但必须保留 dispatch 时冻结的完整请求画像。 */
    @Test
    void acceptsUnknownUsageWithCompleteProfile() {
        ProviderRequestProfile requestProfile = profile("provider_test", "model_test", "cfg_test");

        ProviderRequestUsage usage = new ProviderRequestUsage(
                "request_test", 1, 1, ProviderRequestUsage.Purpose.ASSISTANT,
                ProviderRequestUsage.Certainty.UNKNOWN, requestProfile, null);

        assertEquals(requestProfile, usage.profile());
        assertNull(usage.usage());
    }

    /** 缺失 Profile 代表请求事实损坏，不能用 UNKNOWN 或虚构默认值绕过。 */
    @Test
    void rejectsMissingProfile() {
        assertThrows(NullPointerException.class, () -> new ProviderRequestUsage(
                "request_test", 1, 1, ProviderRequestUsage.Purpose.ASSISTANT,
                ProviderRequestUsage.Certainty.UNKNOWN, null, null));
    }

    /** KNOWN/UNKNOWN 与 token 三元组必须严格一致，防止未知计量被解释为零。 */
    @Test
    void rejectsCertaintyThatDoesNotMatchTokenFacts() {
        ProviderRequestProfile requestProfile = profile("provider_test", "model_test", "cfg_test");
        ModelUsage tokens = new ModelUsage(10, 2, 12);

        assertThrows(IllegalArgumentException.class, () -> new ProviderRequestUsage(
                "request_test", 1, 1, ProviderRequestUsage.Purpose.ASSISTANT,
                ProviderRequestUsage.Certainty.UNKNOWN, requestProfile, tokens));
        assertThrows(IllegalArgumentException.class, () -> new ProviderRequestUsage(
                "request_test", 1, 1, ProviderRequestUsage.Purpose.ASSISTANT,
                ProviderRequestUsage.Certainty.KNOWN, requestProfile, null));
    }
}
