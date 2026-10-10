// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.prompt;

import io.github.kongweiguang.ja.agent.application.skill.SkillCatalog;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.domain.skill.SkillDescriptor;
import io.github.kongweiguang.ja.agent.domain.skill.SkillPage;
import io.github.kongweiguang.ja.agent.domain.skill.SkillReadRequest;
import io.github.kongweiguang.ja.agent.domain.skill.SkillRequestContext;
import io.github.kongweiguang.ja.agent.domain.skill.SkillResourceContent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.application.gateway.skill.SkillSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.kongweiguang.ja.agent.domain.prompt.AgentPromptSnapshot;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession.PreparedPrompt;

/** 验证 Prompt Skill 恢复的幂等、历史指纹保留与整组替换事务边界。 */
final class DefaultAgentPromptSessionTest {
    /** 宿主完整 Persona 替换默认规则时仍须保留 Skill 装配，并让正文差异进入 Prompt revision。 */
    @Test
    void hostPersonaReplacesDefaultAndKeepsSkillRevision() {
        MutableSkillSource source = new MutableSkillSource();
        SkillCatalog catalog = new SkillCatalog(List.of(source));
        SkillRequestContext context = new SkillRequestContext("tenant-test");
        SkillCatalog.Catalog discovered = catalog.discover(context, Set.of());
        try (DefaultAgentPromptSession general = new DefaultAgentPromptSession(catalog, discovered, context,
                    ContextBudget.capabilities(8_192, 512, true), "a".repeat(64), List.of(), "你是通用助手。");
             DefaultAgentPromptSession analyst = new DefaultAgentPromptSession(catalog, discovered, context,
                    ContextBudget.capabilities(8_192, 512, true), "a".repeat(64), List.of(), "你是分析助手。")) {
            general.prepareSkillReplacement(List.of("business.docs:guide")).commit();
            analyst.prepareSkillReplacement(List.of("business.docs:guide")).commit();
            AgentPromptSnapshot first = general.prepare("", List.of()).snapshot();
            AgentPromptSnapshot second = analyst.prepare("", List.of()).snapshot();
            assertTrue(first.systemPrompt().contains("你是通用助手。"));
            assertTrue(second.systemPrompt().contains("你是分析助手。"));
            assertTrue(second.systemPrompt().contains("guide body"));
            assertFalse(second.systemPrompt().contains("You are a general-purpose agent"));
            assertNotEquals(first.revision(), second.revision());
        }
    }

    /** 恢复时较新的摘要不能为旧模型批次重新授权；只有新模型请求采用的新 revision 才可继续副作用。 */
    @Test
    void changedRecoveredSummaryCannotAuthorizeAnOlderToolIntent() {
        try (DefaultAgentPromptSession original = session(new MutableSkillSource());
             DefaultAgentPromptSession recovered = session(new MutableSkillSource())) {
            original.prepare("已发送请求的摘要", List.of());
            String batchRevision = original.currentRevision();
            AgentTool.Invocation call = new AgentTool.Invocation("call_1", "update_order", JsonObject.empty(), 0);
            recovered.restoreActiveSkills("已发送请求的摘要", List.of());
            assertTrue(recovered.beforeTool(call, ToolSideEffect.EXTERNAL, batchRevision).proceed());

            recovered.restoreActiveSkills("更新后的摘要", List.of());
            AgentPromptSession.ToolGuard guard = recovered.beforeTool(call, ToolSideEffect.EXTERNAL, batchRevision);
            assertFalse(guard.proceed());
            assertEquals("PROMPT_REVISION_CHANGED", guard.code());
            assertTrue(recovered.beforeTool(call, ToolSideEffect.READ_ONLY, batchRevision).proceed());

            recovered.prepare("更新后的摘要", List.of());
            assertTrue(recovered.beforeTool(call, ToolSideEffect.EXTERNAL, recovered.currentRevision()).proceed());
        }
    }

    /** 缺失或未知的模型材料身份不能被最新摘要替代；旧 checkpoint ID 也不作为当前 revision 接受。 */
    @Test
    void missingOrUnknownToolGuardRevisionFailsClosed() {
        try (DefaultAgentPromptSession recovered = session(new MutableSkillSource())) {
            recovered.restoreActiveSkills("最新摘要", List.of());
            AgentTool.Invocation call = new AgentTool.Invocation("call_1", "update_order", JsonObject.empty(), 0);
            assertThrows(IllegalArgumentException.class,
                    () -> recovered.beforeTool(call, ToolSideEffect.EXTERNAL, null));
            assertThrows(IllegalArgumentException.class,
                    () -> recovered.beforeTool(call, ToolSideEffect.EXTERNAL, " "));
            AgentPromptSession.ToolGuard guard = recovered.beforeTool(call, ToolSideEffect.EXTERNAL, "cp_missing");
            assertFalse(guard.proceed());
            assertEquals("PROMPT_REVISION_CHANGED", guard.code());
        }
    }

    /**
     * 相同恢复快照不重读来源；正文变化只在下一模型准备点生效，历史恢复阶段保持原指纹。
     */
    @Test
    void repeatedRestorePreservesHistoricalFingerprintUntilNextPromptPreparation() {
        MutableSkillSource source = new MutableSkillSource();
        DefaultAgentPromptSession session = session(source);
        try (session) {
            session.prepareSkillReplacement(List.of("business.docs:guide")).commit();
            List<TurnExecutionState.ActiveSkill> historical = session.activeSkillReferences();
            int readsAfterSelection = source.reads;

            session.restoreActiveSkills("checkpoint summary", historical);
            String summaryRevision = session.currentRevision();
            session.restoreActiveSkills("checkpoint summary", historical);
            assertEquals(readsAfterSelection, source.reads);
            assertEquals(historical, session.activeSkillReferences());

            source.guideBody = "current guide body";
            session.restoreActiveSkills("checkpoint summary", historical);
            assertEquals(readsAfterSelection, source.reads);
            assertEquals(historical, session.activeSkillReferences());
            assertEquals(summaryRevision, session.currentRevision());

            PreparedPrompt nextRequest = session.prepare("next request summary", List.of());
            assertEquals(readsAfterSelection + 1, source.reads);
            assertTrue(nextRequest.snapshot().systemPrompt().contains("current guide body"));
            assertNotEquals(historical, session.activeSkillReferences());
        }
    }

    /**
     * 变化的权威列表在全部 Skill 正文成功读取后整体发布，读取失败时旧列表与 Prompt 均不改变。
     */
    @Test
    void changedRestoreReplacesTheWholeSetOnlyAfterEverySkillLoads() {
        MutableSkillSource source = new MutableSkillSource();
        DefaultAgentPromptSession session = session(source);
        try (session) {
            session.prepareSkillReplacement(List.of("business.docs:guide")).commit();
            List<TurnExecutionState.ActiveSkill> previous = session.activeSkillReferences();
            String previousRevision = session.currentRevision();
            List<TurnExecutionState.ActiveSkill> replacement = List.of(
                    new TurnExecutionState.ActiveSkill("business.docs:reference", Optional.empty()));
            source.failReferenceRead = true;

            assertThrows(IllegalStateException.class,
                    () -> session.restoreActiveSkills("new summary", replacement));
            assertEquals(previous, session.activeSkillReferences());
            assertEquals(previousRevision, session.currentRevision());

            source.failReferenceRead = false;
            session.restoreActiveSkills("new summary", replacement);
            assertEquals(replacement, session.activeSkillReferences());
            PreparedPrompt prepared = session.prepare("new summary", List.of());
            assertTrue(prepared.snapshot().systemPrompt().contains("reference body"));
            assertFalse(prepared.snapshot().systemPrompt().contains("guide body"));
        }
    }

    /** 单项超出模型目录预算时仍检查后续条目，保证可容纳元数据不被目录顺序遮蔽。 */
    @Test
    void oversizedMetadataDoesNotHideLaterEntryThatFits() {
        MutableSkillSource source = new MutableSkillSource();
        source.guideDescription = "x".repeat(120);
        SkillCatalog catalog = new SkillCatalog(List.of(source));
        SkillRequestContext context = new SkillRequestContext("tenant-test");
        SkillCatalog.Catalog discovered = catalog.discover(context, Set.of());
        try (DefaultAgentPromptSession session = new DefaultAgentPromptSession(catalog, discovered, context,
                ContextBudget.capabilities(2_000, 512, true), "a".repeat(64), List.of())) {
            String prompt = session.prepare("", List.of()).snapshot().systemPrompt();
            assertTrue(prompt.contains("\"name\":\"reference\""));
            assertTrue(prompt.contains("\"description\":\"Reference policy\""));
            assertFalse(prompt.contains(source.guideDescription));
            assertTrue(prompt.contains("Some Skill metadata is omitted by the configured context window."));
        }
    }

    /** 为测试构造绑定租户和显式单来源的请求级 Prompt Session。 */
    private static DefaultAgentPromptSession session(MutableSkillSource source) {
        SkillCatalog catalog = new SkillCatalog(List.of(source));
        SkillRequestContext context = new SkillRequestContext("tenant-test");
        SkillCatalog.Catalog discovered = catalog.discover(context, Set.of());
        return new DefaultAgentPromptSession(catalog, discovered, context,
                ContextBudget.capabilities(8_192, 512, true), "a".repeat(64), List.of());
    }

    /** 提供可变但计数可观测的来源，以区分恢复重读与下一模型请求的实时刷新。 */
    private static final class MutableSkillSource implements SkillSource {
        private String guideBody = "guide body";
        private String guideDescription = "Guide policy";
        private boolean failReferenceRead;
        private int reads;

        /** 返回测试内唯一的稳定 Skill 来源身份。 */
        @Override
        public String id() {
            return "business.docs";
        }

        /** 返回不覆盖其他来源的显式优先级。 */
        @Override
        public int priority() {
            return 1;
        }

        /** 按稳定名称顺序返回描述，使首项长度可控以验证目录预算的跳过边界。 */
        @Override
        public SkillPage discover(SkillRequestContext context, String cursor, int pageSize) {
            List<SkillDescriptor> descriptors = List.of(
                    new SkillDescriptor("guide", guideDescription, id(), 1),
                    new SkillDescriptor("reference", "Reference policy", id(), 1));
            return cursor == null ? new SkillPage(descriptors, null) : new SkillPage(List.of(), null);
        }

        /** 返回带读取计数的正文；允许单个资源失败以检查恢复替换的原子性。 */
        @Override
        public SkillResourceContent read(SkillRequestContext context, SkillDescriptor descriptor,
                                         SkillReadRequest request) {
            reads++;
            if (failReferenceRead && descriptor.name().equals("reference")) {
                throw new IllegalStateException("reference unavailable");
            }
            return new SkillResourceContent(descriptor.name().equals("guide") ? guideBody : "reference body", false);
        }
    }
}
