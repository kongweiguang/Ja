// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.testsupport;

import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.ProviderRequestProfile;
import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;

import java.time.Instant;
import java.util.List;

/** 复用首版 Thread/Turn 身份语义，避免测试夹具生成不完整的请求事实。 */
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
                io.github.kongweiguang.ja.conversation.domain.CollaborationMode.DEFAULT,
                ThreadPreferences.TitleSource.PLACEHOLDER);
    }

    /** 为持久化测试冻结完整 READY 游标；请求级 runtime 不再写入 Operation Common。 */
    public static TurnExecutionState.Ready execution(String generation) {
        TurnExecutionState.Common common = new TurnExecutionState.Common(0, 0, 1,
                null, List.of(), Instant.parse("2099-01-01T00:00:00Z"),
                io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin.USER);
        return new TurnExecutionState.Ready(common, TurnExecutionState.Next.ASSISTANT, null);
    }

    /** 构造当前请求的完整 Profile；测试中的修订值固定，只有显式传入的选择参与差异断言。 */
    public static ProviderRequestProfile profile(String providerId, String modelId, String generation) {
        return new ProviderRequestProfile(providerId, modelId, "openai_responses", "test-model",
                "medium", "medium", AccessMode.APPROVAL_REQUIRED,
                io.github.kongweiguang.ja.conversation.domain.CollaborationMode.DEFAULT,
                generation, "prompt_test",
                "a".repeat(64), 100_000, 8_192);
    }

    /** 将请求级 Usage 转为持久事实；首版请求必须始终携带完整 Profile。 */
    public static ConversationRepository.UsageFact usageFact(
            int requestOrdinal, int modelRound, ModelUsage tokens) {
        ProviderRequestProfile requestProfile = profile("provider_test", "model_test", "cfg_test");
        return new ConversationRepository.UsageFact("request_" + requestOrdinal, tokens,
                modelRound, requestOrdinal, ConversationRepository.UsagePurpose.ASSISTANT,
                tokens == null ? ConversationRepository.UsageCertainty.UNKNOWN
                        : ConversationRepository.UsageCertainty.KNOWN,
                requestProfile);
    }

    /** 构造内建 Tool 的精确不可变绑定；三个摘要刻意不同以发现 Mapper 字段错位。 */
    public static ConversationRepository.ToolBinding binding(String batchId, String callId, String localName) {
        return new ConversationRepository.ToolBinding(batchId, callId, AgentTool.RouteKind.BUILTIN,
                localName, "builtin", localName, "a".repeat(64), "b".repeat(64),
                "c".repeat(64), AccessMode.APPROVAL_REQUIRED);
    }

}
