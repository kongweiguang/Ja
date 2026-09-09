// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** 请求级 Provider 等价键回归集，防止 continuation 在任一环境事实漂移后继续复用。 */
final class ProviderRequestProfileTest {

    /** 完全相同的请求事实必须保持值相等，作为 continuation 可复用的充分条件。 */
    @Test
    void identicalProfilesAreEquivalent() {
        ProviderRequestProfile expected = profile();

        ProviderRequestProfile actual = new ProviderRequestProfile(
                expected.providerId(), expected.modelId(), expected.api(), expected.upstreamModel(),
                expected.requestedReasoning(), expected.effectiveReasoning(), expected.accessMode(),
                expected.collaborationMode(), expected.configGeneration(), expected.promptRevision(),
                expected.toolCatalogRevision(), expected.contextWindowTokens(), expected.maxOutputTokens());

        assertEquals(expected, actual);
        assertEquals(expected.hashCode(), actual.hashCode());
    }

    /** 每个请求等价键都独立参与比较，任何单字段变化都必须使 continuation 失效。 */
    @Test
    void everyRequestEquivalenceKeyInvalidatesReuseWhenChanged() {
        ProviderRequestProfile baseline = profile();
        List<Change> changes = List.of(
                new Change(Key.PROVIDER_ID, "provider_other"),
                new Change(Key.MODEL_ID, "model_other"),
                new Change(Key.API, "anthropic_messages"),
                new Change(Key.UPSTREAM_MODEL, "claude-other"),
                new Change(Key.REQUESTED_REASONING, "high"),
                new Change(Key.EFFECTIVE_REASONING, "high"),
                new Change(Key.ACCESS_MODE, AccessMode.FULL_ACCESS),
                new Change(Key.COLLABORATION_MODE, CollaborationMode.PLAN),
                new Change(Key.CONFIG_GENERATION, "cfg_other"),
                new Change(Key.PROMPT_REVISION, "prompt_other"),
                new Change(Key.TOOL_CATALOG_REVISION, "b".repeat(64)),
                new Change(Key.CONTEXT_WINDOW_TOKENS, 200_001),
                new Change(Key.MAX_OUTPUT_TOKENS, 8_193));

        for (Change change : changes) {
            assertNotEquals(baseline, changed(baseline, change), change.key().name());
        }
    }

    /** 构造全部字段有效且彼此可独立变化的基线 Profile。 */
    private static ProviderRequestProfile profile() {
        return new ProviderRequestProfile(
                "provider_test", "model_test", "openai_responses", "gpt-test",
                "medium", "medium", AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT,
                "cfg_test", "prompt_test", "a".repeat(64), 200_000, 8_192);
    }

    /** 只替换一个命名字段，避免测试构造器重复掩盖遗漏的等价键。 */
    private static ProviderRequestProfile changed(ProviderRequestProfile source, Change change) {
        return new ProviderRequestProfile(
                change.key() == Key.PROVIDER_ID ? (String) change.value() : source.providerId(),
                change.key() == Key.MODEL_ID ? (String) change.value() : source.modelId(),
                change.key() == Key.API ? (String) change.value() : source.api(),
                change.key() == Key.UPSTREAM_MODEL ? (String) change.value() : source.upstreamModel(),
                change.key() == Key.REQUESTED_REASONING ? (String) change.value() : source.requestedReasoning(),
                change.key() == Key.EFFECTIVE_REASONING ? (String) change.value() : source.effectiveReasoning(),
                change.key() == Key.ACCESS_MODE ? (AccessMode) change.value() : source.accessMode(),
                change.key() == Key.COLLABORATION_MODE
                        ? (CollaborationMode) change.value() : source.collaborationMode(),
                change.key() == Key.CONFIG_GENERATION ? (String) change.value() : source.configGeneration(),
                change.key() == Key.PROMPT_REVISION ? (String) change.value() : source.promptRevision(),
                change.key() == Key.TOOL_CATALOG_REVISION
                        ? (String) change.value() : source.toolCatalogRevision(),
                change.key() == Key.CONTEXT_WINDOW_TOKENS
                        ? (Integer) change.value() : source.contextWindowTokens(),
                change.key() == Key.MAX_OUTPUT_TOKENS ? (Integer) change.value() : source.maxOutputTokens());
    }

    /** 将待变字段与合法替代值绑定，失败消息可直接指出遗漏的等价键。 */
    private record Change(Key key, Object value) {
        /** 测试数据必须完整，避免 null 被误当成某个字段的合法差异。 */
        private Change {
            java.util.Objects.requireNonNull(key, "key");
            java.util.Objects.requireNonNull(value, "value");
        }
    }

    /** 与生产 Profile canonical components 一一对应，新增字段时编译外还需显式补测试项。 */
    private enum Key {
        /** Provider 配置身份。 */
        PROVIDER_ID,
        /** Ja 模型选择身份。 */
        MODEL_ID,
        /** Provider API 协议。 */
        API,
        /** 实际发送的上游模型。 */
        UPSTREAM_MODEL,
        /** 用户请求的推理等级。 */
        REQUESTED_REASONING,
        /** Provider 实际采用的推理等级。 */
        EFFECTIVE_REASONING,
        /** Tool 访问模式。 */
        ACCESS_MODE,
        /** Agent 协作模式。 */
        COLLABORATION_MODE,
        /** 配置代际。 */
        CONFIG_GENERATION,
        /** 实际 System Prompt 修订。 */
        PROMPT_REVISION,
        /** 模型可见 Tool 目录修订。 */
        TOOL_CATALOG_REVISION,
        /** 模型上下文窗口。 */
        CONTEXT_WINDOW_TOKENS,
        /** 请求最大输出预算。 */
        MAX_OUTPUT_TOKENS
    }
}
