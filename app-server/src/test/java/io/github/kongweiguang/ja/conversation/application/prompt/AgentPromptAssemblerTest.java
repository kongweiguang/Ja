// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.prompt;

import io.github.kongweiguang.ja.conversation.port.out.ContextTransform;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 极简 Ja Persona、动态 System 唯一性与 catalog 整条预算的纯行为测试。 */
final class AgentPromptAssemblerTest {
    /** 固定精简核心的字节指纹，避免把整段正文复制进测试并形成第二份维护来源。 */
    @Test
    void freezesMinimalPersonaGolden() {
        assertFalse(AgentPromptAssembler.SYSTEM_PROMPT.endsWith("\n"));
        assertEquals("d414622963e6af2c0152ee575dc103f785092ec5bbf5775ff3b11b0b5cf0ae9f",
                sha256(AgentPromptAssembler.SYSTEM_PROMPT));
    }

    /** 每个非空章节只在动态 System 渲染一次，并保持确定性装配顺序。 */
    @Test
    void rendersDynamicSystemSectionsOnceInFixedOrder() {
        io.github.kongweiguang.ja.conversation.domain.prompt.AgentPromptSnapshot snapshot =
                AgentPromptAssembler.assemble(new AgentPromptAssembler.Material(
                100_000, "env", "rules", List.of(new AgentPromptAssembler.SkillEntry("review", "Review code")),
                "summary", List.of(new AgentPromptAssembler.ActiveSkill("review", "body")),
                new ContextTransform.DerivedContext(List.of(
                        new ContextTransform.SystemFragment("review-context", "derived body"))),
                List.of("diagnostic"), "trusted=true\ninstructions=agents-1"));

        assertTrue(snapshot.systemPrompt().startsWith(AgentPromptAssembler.SYSTEM_PROMPT));
        assertEquals(1, occurrences(snapshot.systemPrompt(), "<environment>"));
        assertEquals(1, occurrences(snapshot.systemPrompt(), "<instructions>"));
        assertEquals(1, occurrences(snapshot.systemPrompt(), "<skills>"));
        assertEquals(1, occurrences(snapshot.systemPrompt(), "<summary>"));
        assertEquals(1, occurrences(snapshot.systemPrompt(), "<active-skills>"));
        assertEquals(1, occurrences(snapshot.systemPrompt(), "<derived-context>"));
        assertEquals(1, occurrences(snapshot.systemPrompt(), "For a named or matching Skill"));
        assertTrue(snapshot.systemPrompt().contains("skill://<name>/SKILL.md"));
        assertTrue(snapshot.systemPrompt().indexOf("<environment>")
                   < snapshot.systemPrompt().indexOf("<instructions>"));
        assertTrue(snapshot.systemPrompt().indexOf("<instructions>")
                   < snapshot.systemPrompt().indexOf("<skills>"));
        assertTrue(snapshot.systemPrompt().indexOf("<skills>")
                   < snapshot.systemPrompt().indexOf("<summary>"));
        assertTrue(snapshot.systemPrompt().indexOf("<summary>")
                   < snapshot.systemPrompt().indexOf("<active-skills>"));
        assertTrue(snapshot.systemPrompt().indexOf("<active-skills>")
                   < snapshot.systemPrompt().indexOf("<derived-context>"));
        assertTrue(snapshot.systemPrompt().indexOf("<derived-context>")
                   < snapshot.systemPrompt().indexOf("<diagnostics>"));
        assertTrue(snapshot.systemPrompt().contains("summary"));
        assertTrue(snapshot.systemPrompt().contains("body"));
    }

    /** 空目录不注入 Skill 协议或空标签，使未启用扩展的 Turn 保持最小上下文。 */
    @Test
    void omitsSkillProtocolForEmptyCatalog() {
        io.github.kongweiguang.ja.conversation.domain.prompt.AgentPromptSnapshot snapshot =
                AgentPromptAssembler.assemble(new AgentPromptAssembler.Material(
                        100_000, "env", "rules", List.of(), "", List.of(),
                        ContextTransform.DerivedContext.empty(), List.of(), "catalog-empty"));

        assertFalse(snapshot.systemPrompt().contains("<skills>"));
        assertFalse(snapshot.systemPrompt().contains("skill://<name>/SKILL.md"));
    }

    /** 预算无法容纳任一完整条目时，协议也必须省略，避免模型误判存在可激活 Skill。 */
    @Test
    void omitsSkillProtocolWhenEveryCatalogEntryExceedsBudget() {
        io.github.kongweiguang.ja.conversation.domain.prompt.AgentPromptSnapshot snapshot =
                AgentPromptAssembler.assemble(new AgentPromptAssembler.Material(
                        50, "", "", List.of(new AgentPromptAssembler.SkillEntry("review", "Review code")),
                        "", List.of(), ContextTransform.DerivedContext.empty(), List.of(),
                        "catalog-over-budget"));

        assertFalse(snapshot.systemPrompt().contains("<skills>"));
        assertFalse(snapshot.systemPrompt().contains("skill://<name>/SKILL.md"));
        assertTrue(snapshot.systemPrompt().contains("Skill catalog omitted 1 entries due to budget."));
    }

    /** revision 只在模型可见内容变化时变化，相同材料重复组装必须完全稳定。 */
    @Test
    void hashesCanonicalVisiblePrompt() {
        AgentPromptAssembler.Material material = new AgentPromptAssembler.Material(
                100_000, "env\r\nline", "rules", List.of(),
                "", List.of(), ContextTransform.DerivedContext.empty(), List.of(), "catalog-1");
        io.github.kongweiguang.ja.conversation.domain.prompt.AgentPromptSnapshot first =
                AgentPromptAssembler.assemble(material);
        io.github.kongweiguang.ja.conversation.domain.prompt.AgentPromptSnapshot second =
                AgentPromptAssembler.assemble(new AgentPromptAssembler.Material(
                100_000, "env\nline", "rules", List.of(), "", List.of(),
                ContextTransform.DerivedContext.empty(), List.of(), "catalog-1"));
        assertEquals(first.revision(), second.revision());
        assertTrue(first.revision().matches("prompt_[0-9a-f]{64}"));
    }

    /** 模型可见文本相同时，底层 AGENTS 安全事实变化仍必须清除 Provider continuation。 */
    @Test
    void hashesNonVisibleInstructionRevision() {
        io.github.kongweiguang.ja.conversation.domain.prompt.AgentPromptSnapshot first =
                AgentPromptAssembler.assemble(new AgentPromptAssembler.Material(
                100_000, "env", "rules", List.of(), "", List.of(),
                ContextTransform.DerivedContext.empty(), List.of(), "instructions-1"));
        io.github.kongweiguang.ja.conversation.domain.prompt.AgentPromptSnapshot second =
                AgentPromptAssembler.assemble(new AgentPromptAssembler.Material(
                100_000, "env", "rules", List.of(), "", List.of(),
                ContextTransform.DerivedContext.empty(), List.of(), "instructions-2"));
        assertEquals(first.systemPrompt(), second.systemPrompt());
        assertNotEquals(first.revision(), second.revision());
    }

    /** 派生片段正文与身份都属于冻结 Prompt，任一变化必须同步改变 revision 和估算。 */
    @Test
    void hashesAndMeasuresDerivedContextBeforeProviderPreparation() {
        AgentPromptAssembler.Material base = new AgentPromptAssembler.Material(
                100_000, "env", "rules", List.of(), "", List.of(),
                ContextTransform.DerivedContext.empty(), List.of(), "instructions-1");
        AgentPromptAssembler.Material derived = new AgentPromptAssembler.Material(
                100_000, "env", "rules", List.of(), "", List.of(),
                new ContextTransform.DerivedContext(List.of(
                        new ContextTransform.SystemFragment("memory", "derived context body"))),
                List.of(), "instructions-1");

        var baseSnapshot = AgentPromptAssembler.assemble(base);
        var derivedSnapshot = AgentPromptAssembler.assemble(derived);

        assertNotEquals(baseSnapshot.revision(), derivedSnapshot.revision());
        assertTrue(derivedSnapshot.systemTokens() > baseSnapshot.systemTokens());
        assertTrue(derivedSnapshot.systemPrompt().contains("[context memory]\nderived context body"));
    }

    /** 统计固定文本出现次数，避免 contains 无法发现意外重复注入。 */
    private static int occurrences(String value, String needle) {
        int result = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            result++;
            offset += needle.length();
        }
        return result;
    }

    /** 直接散列 UTF-8 字节，防止 Text Block 缩进或关闭分隔符意外改变稳定 Persona。 */
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
