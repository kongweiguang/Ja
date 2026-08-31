// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.testsupport;

import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.TurnRuntimeSnapshot;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;

/** 复用当前 v3 Thread/Turn 身份语义，避免测试夹具重新引入已删除的 Profile 概念。 */
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
                ThreadPreferences.TitleSource.PLACEHOLDER);
    }

    /** 返回默认既有 Turn 运行事实，历史断言不得从当前 Thread preferences 反推。 */
    public static TurnRuntimeSnapshot runtime() {
        return runtime("provider_test", "model_test", "cfg_test");
    }

    /** 返回指定稳定选择与配置代际的既有 Turn 运行事实，不保存 endpoint 或 credential。 */
    public static TurnRuntimeSnapshot runtime(String providerId, String modelId, String generation) {
        return new TurnRuntimeSnapshot(providerId, modelId, "openai", "openai_responses",
                "test-model", "medium", AccessMode.APPROVAL_REQUIRED, generation);
    }
}
