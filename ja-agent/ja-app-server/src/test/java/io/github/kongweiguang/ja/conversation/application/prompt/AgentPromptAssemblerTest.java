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
    /** 固定公共提示的字节指纹，防止普通任务完成约束被入口差异或意外改动静默移除。 */
    @Test
    void freezesMinimalPersonaGolden() {
        assertFalse(AgentPromptAssembler.SYSTEM_PROMPT.endsWith("\n"));
        assertEquals("cdaaec9fe2c6c7efb73d1a0fcbd14587e89e24e9c92a5385722f678e8e4f0802",
                sha256(AgentPromptAssembler.SYSTEM_PROMPT));
        assertTrue(AgentPromptAssembler.SYSTEM_PROMPT.contains(
                "Before finishing an execution request, compare the result with the user's actual request"));
    }

    /** 锁定补充指令续作、无虚构预算收口及实施到验证修正交付的闭环。 */
    @Test
    void keepsExecutionLoopExplicitAcrossFollowUps() {
        String prompt = AgentPromptAssembler.SYSTEM_PROMPT;

        assertTrue(prompt.contains("A progress question or added instruction does not by itself cancel unfinished work"));
        assertTrue(prompt.contains("incorporate it\nand continue unless the user redirects or stops."));
        assertTrue(prompt.contains("Never stop for a\nself-imposed turn, tool, retry, or elapsed-time budget."));
        assertTrue(prompt.contains("respect runtime limits, permissions, and cancellation."));
        assertTrue(prompt.contains("Only then\nreport what was completed and verified plus any unresolved blocker."));
        assertFalse(prompt.contains("within the available budget"));
    }

    /** 主聊和侧聊共用此 Persona；固定简短链接约束，避免各入口分叉或要求每次列出文件。 */
    @Test
    void guidesKnownFileLinksWithoutAddingRoutineInventories() {
        String prompt = AgentPromptAssembler.SYSTEM_PROMPT;

        assertTrue(prompt.contains("link each verified path in ordinary prose and never invent paths"));
        assertTrue(prompt.contains("Prefer workspace-relative links; include only verified line numbers"));
        assertTrue(prompt.contains("[src/file.ts:12](src/file.ts#L12) or #L12C3"));
        assertTrue(prompt.contains("For absolute paths, use valid file:// links"));
        assertTrue(prompt.contains("URL-encode spaces and non-ASCII characters in targets"));
        assertTrue(prompt.contains("do not add a file list just to show links"));
    }

    /** 每个非空章节只在动态 System 渲染一次，并保持确定性装配顺序。 */
    @Test
    void rendersDynamicSystemSectionsOnceInFixedOrder() {
        io.github.kongweiguang.ja.agent.domain.prompt.AgentPromptSnapshot snapshot =
                AgentPromptAssembler.assemble(new AgentPromptAssembler.Material(
                100_000, "env", "rules", List.of(new AgentPromptAssembler.SkillEntry("review", "Review code")),
                "summary", List.of(new AgentPromptAssembler.ActiveSkill("review", "body")),
                List.of("diagnostic"), "trusted=true\ninstructions=agents-1"));

        assertTrue(snapshot.systemPrompt().startsWith(AgentPromptAssembler.SYSTEM_PROMPT));
        assertEquals(1, occurrences(snapshot.systemPrompt(), "<environment>"));
        assertEquals(1, occurrences(snapshot.systemPrompt(), "<instructions>"));
        assertEquals(1, occurrences(snapshot.systemPrompt(), "<skills>"));
        assertEquals(1, occurrences(snapshot.systemPrompt(), "<summary>"));
        assertEquals(1, occurrences(snapshot.systemPrompt(), "<active-skills>"));
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
                   < snapshot.systemPrompt().indexOf("<diagnostics>"));
        assertTrue(snapshot.systemPrompt().contains("summary"));
        assertTrue(snapshot.systemPrompt().contains("body"));
    }

    /** 空目录不注入 Skill 协议或空标签，使未启用扩展的 Turn 保持最小上下文。 */
    @Test
    void omitsSkillProtocolForEmptyCatalog() {
        io.github.kongweiguang.ja.agent.domain.prompt.AgentPromptSnapshot snapshot =
                AgentPromptAssembler.assemble(new AgentPromptAssembler.Material(
                        100_000, "env", "rules", List.of(), "", List.of(),
                        List.of(), "catalog-empty"));

        assertFalse(snapshot.systemPrompt().contains("<skills>"));
        assertFalse(snapshot.systemPrompt().contains("skill://<name>/SKILL.md"));
    }

    /** 预算无法容纳任一完整条目时，协议也必须省略，避免模型误判存在可激活 Skill。 */
    @Test
    void omitsSkillProtocolWhenEveryCatalogEntryExceedsBudget() {
        io.github.kongweiguang.ja.agent.domain.prompt.AgentPromptSnapshot snapshot =
                AgentPromptAssembler.assemble(new AgentPromptAssembler.Material(
                        50, "", "", List.of(new AgentPromptAssembler.SkillEntry("review", "Review code")),
                        "", List.of(), List.of(), "catalog-over-budget"));

        assertFalse(snapshot.systemPrompt().contains("<skills>"));
        assertFalse(snapshot.systemPrompt().contains("skill://<name>/SKILL.md"));
        assertTrue(snapshot.systemPrompt().contains("Skill catalog omitted 1 entries due to budget."));
    }

    /** revision 只在模型可见内容变化时变化，相同材料重复组装必须完全稳定。 */
    @Test
    void hashesCanonicalVisiblePrompt() {
        AgentPromptAssembler.Material material = new AgentPromptAssembler.Material(
                100_000, "env\r\nline", "rules", List.of(), "", List.of(), List.of(), "catalog-1");
        io.github.kongweiguang.ja.agent.domain.prompt.AgentPromptSnapshot first =
                AgentPromptAssembler.assemble(material);
        io.github.kongweiguang.ja.agent.domain.prompt.AgentPromptSnapshot second =
                AgentPromptAssembler.assemble(new AgentPromptAssembler.Material(
                100_000, "env\nline", "rules", List.of(), "", List.of(), List.of(), "catalog-1"));
        assertEquals(first.revision(), second.revision());
        assertTrue(first.revision().matches("prompt_[0-9a-f]{64}"));
    }

    /** 模型可见文本相同时，底层 AGENTS 安全事实变化仍必须清除 Provider continuation。 */
    @Test
    void hashesNonVisibleInstructionRevision() {
        io.github.kongweiguang.ja.agent.domain.prompt.AgentPromptSnapshot first =
                AgentPromptAssembler.assemble(new AgentPromptAssembler.Material(
                100_000, "env", "rules", List.of(), "", List.of(),
                List.of(), "instructions-1"));
        io.github.kongweiguang.ja.agent.domain.prompt.AgentPromptSnapshot second =
                AgentPromptAssembler.assemble(new AgentPromptAssembler.Material(
                100_000, "env", "rules", List.of(), "", List.of(),
                List.of(), "instructions-2"));
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
