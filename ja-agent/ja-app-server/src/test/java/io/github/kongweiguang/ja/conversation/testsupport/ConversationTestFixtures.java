// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.testsupport;

import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ProviderRequestProfile;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;

import java.util.List;

/** 复用 Ja 偏好与请求身份夹具，避免测试反复构造不完整的领域值。 */
public final class ConversationTestFixtures {
    /** 纯静态测试夹具不允许实例化，防止测试误以为它持有可变运行状态。 */
    private ConversationTestFixtures() {
    }

    /** 返回默认下一轮偏好；调用方需要竞争标题时再显式构造不同 title source。 */
    public static ThreadPreferences preferences() {
        return preferences("provider_test", "model_test");
    }

    /** 返回指定稳定 Provider/Model 的下一轮偏好，权限固定为需要审批。 */
    public static ThreadPreferences preferences(String providerId, String modelId) {
        return new ThreadPreferences(providerId, modelId, "medium", AccessMode.APPROVAL_REQUIRED,
                io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode.DEFAULT,
                ThreadPreferences.TitleSource.PLACEHOLDER);
    }

    /** 为持久化测试冻结完整 READY 游标；请求级 runtime 不再写入 Operation Common。 */
    public static TurnExecutionState.Ready execution() {
        TurnExecutionState.Common common = new TurnExecutionState.Common(0, 0, 1,
                null, List.of());
        return new TurnExecutionState.Ready(common, TurnExecutionState.Next.ASSISTANT, null);
    }

    /** 构造当前请求的完整 Profile；测试中的修订值固定，只有显式传入的选择参与差异断言。 */
    public static ProviderRequestProfile profile(String providerId, String modelId, String generation) {
        return new ProviderRequestProfile(providerId, modelId, "openai_responses", "test-model",
                "medium", "medium", AccessMode.APPROVAL_REQUIRED,
                io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode.DEFAULT,
                generation, "prompt_test",
                "a".repeat(64), 100_000, 8_192);
    }

}
