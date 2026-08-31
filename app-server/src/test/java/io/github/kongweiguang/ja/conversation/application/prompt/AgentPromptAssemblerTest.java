// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.prompt;

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
    /** Persona 作为动态 System 的稳定短前缀，不再承担完整 System 的字节上限。 */
    @Test
    void freezesMinimalPersonaGolden() {
        assertFalse(AgentPromptAssembler.SYSTEM_PROMPT.endsWith("\n"));
        assertEquals("""
                You are Ja, a coding agent.

                Work in the user's workspace with the available tools.
                Be concise, follow applicable workspace guidance, verify material changes, and report results truthfully.""",
                AgentPromptAssembler.SYSTEM_PROMPT);
        assertEquals("5bb571e96c17dc99b470b41483147600c5e93df3d7d69a05b9c3de42df2ab87b",
                sha256(AgentPromptAssembler.SYSTEM_PROMPT));
    }

    /** 每个非空章节只在动态 System 渲染一次，并保持确定性装配顺序。 */
    @Test
    void rendersDynamicSystemSectionsOnceInFixedOrder() {
        io.github.kongweiguang.ja.conversation.domain.prompt.AgentPromptSnapshot snapshot =
                AgentPromptAssembler.assemble(new AgentPromptAssembler.Material(
                100_000, "env", "rules", List.of(new AgentPromptAssembler.SkillEntry("review", "Review code")),
                "summary", List.of(new AgentPromptAssembler.ActiveSkill("review", "skill_1", "body")),
                List.of("diagnostic"), "trusted=true\ninstructions=agents-1\nskills=catalog-1"));

        assertTrue(snapshot.systemPrompt().startsWith(AgentPromptAssembler.SYSTEM_PROMPT));
        assertEquals(1, occurrences(snapshot.systemPrompt(), "<environment>"));
        assertEquals(1, occurrences(snapshot.systemPrompt(), "<instructions>"));
        assertEquals(1, occurrences(snapshot.systemPrompt(), "<skills>"));
        assertEquals(1, occurrences(snapshot.systemPrompt(), "<summary>"));
        assertEquals(1, occurrences(snapshot.systemPrompt(), "<active-skills>"));
        assertTrue(snapshot.systemPrompt().indexOf("<instructions>")
                   < snapshot.systemPrompt().indexOf("<skills>"));
        assertTrue(snapshot.systemPrompt().contains("summary"));
        assertTrue(snapshot.systemPrompt().contains("body"));
    }

    /** revision 只在模型可见内容变化时变化，相同材料重复组装必须完全稳定。 */
    @Test
    void hashesCanonicalVisiblePrompt() {
        AgentPromptAssembler.Material material = new AgentPromptAssembler.Material(
                100_000, "env\r\nline", "rules", List.of(),
                "", List.of(), List.of(), "catalog-1");
        io.github.kongweiguang.ja.conversation.domain.prompt.AgentPromptSnapshot first =
                AgentPromptAssembler.assemble(material);
        io.github.kongweiguang.ja.conversation.domain.prompt.AgentPromptSnapshot second =
                AgentPromptAssembler.assemble(new AgentPromptAssembler.Material(
                100_000, "env\nline", "rules", List.of(), "", List.of(), List.of(), "catalog-1"));
        assertEquals(first.revision(), second.revision());
        assertTrue(first.revision().matches("prompt_[0-9a-f]{64}"));
    }

    /** 模型可见文本相同时，底层 AGENTS/Skill 代际变化也必须清除 Provider continuation。 */
    @Test
    void hashesNonVisibleInstructionAndCatalogRevisions() {
        io.github.kongweiguang.ja.conversation.domain.prompt.AgentPromptSnapshot first =
                AgentPromptAssembler.assemble(new AgentPromptAssembler.Material(
                100_000, "env", "rules", List.of(), "", List.of(), List.of(), "catalog-1"));
        io.github.kongweiguang.ja.conversation.domain.prompt.AgentPromptSnapshot second =
                AgentPromptAssembler.assemble(new AgentPromptAssembler.Material(
                100_000, "env", "rules", List.of(), "", List.of(), List.of(), "catalog-2"));
        assertEquals(first.systemPrompt(), second.systemPrompt());
        assertNotEquals(first.revision(), second.revision());
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
