// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.prompt;

import io.github.kongweiguang.ja.conversation.domain.prompt.AgentPromptSnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** 把简洁 Persona、环境、工作区指导和渐进式 Skill 组装为完整动态 System。 */
public final class AgentPromptAssembler {
    public static final String SYSTEM_PROMPT = """
            You are Ja, a coding agent.

            Work in the user's workspace with the available tools.
            Be concise, follow applicable workspace guidance, verify material changes, and report results truthfully.""";
    private static final int MAX_CATALOG_CHARACTERS = 8_000;
    private static final long MAX_CATALOG_TOKENS = 2_000;

    /** 禁止实例化纯组装器，所有输入必须通过不可变 Material 显式提供。 */
    private AgentPromptAssembler() {
    }

    /**
     * 先在完整条目边界应用 Skill catalog 预算，再按固定章节顺序追加到 Persona；最终 revision
     * 直接散列完整 System，保证任何模型可见变化都会使 continuation 失效。
     */
    public static AgentPromptSnapshot assemble(Material material) {
        Objects.requireNonNull(material, "material");
        List<String> diagnostics = new ArrayList<>(material.diagnostics());
        String catalog = renderCatalog(material.contextWindowTokens(), material.skills(), diagnostics);
        StringBuilder system = new StringBuilder(SYSTEM_PROMPT);
        appendSection(system, "environment", material.environment());
        appendSection(system, "instructions", material.instructions());
        appendSection(system, "skills", catalog);
        appendSection(system, "summary", material.summary());
        appendActiveSkills(system, material.activeSkills());
        appendSection(system, "diagnostics", String.join("\n", diagnostics));
        String rendered = normalize(system.toString());
        String canonicalRevision = normalize(material.revisionMaterial()).trim();
        return new AgentPromptSnapshot(rendered,
                "prompt_" + sha256(rendered + "\n\u0000\n" + canonicalRevision),
                estimateTokens(rendered));
    }

    /**
     * 按调用方给出的最终 precedence 顺序加入完整条目；超出字符或窗口比例时停止，
     * 不把半条 description 暴露成可用 Skill。
     */
    private static String renderCatalog(long contextWindowTokens, List<SkillEntry> skills,
                                        List<String> diagnostics) {
        long tokenLimit = Math.min(MAX_CATALOG_TOKENS, Math.max(0L, contextWindowTokens / 50L));
        StringBuilder result = new StringBuilder();
        int included = 0;
        for (SkillEntry skill : skills) {
            String entry = skill.name() + ": " + normalize(skill.description()).trim();
            String candidate = result.isEmpty() ? entry : result + "\n" + entry;
            if (candidate.length() > MAX_CATALOG_CHARACTERS || estimateTokens(candidate) > tokenLimit) {
                break;
            }
            if (!result.isEmpty()) result.append('\n');
            result.append(entry);
            included++;
        }
        if (included < skills.size()) {
            diagnostics.add("Skill catalog omitted " + (skills.size() - included) + " entries due to budget.");
        }
        return result.toString();
    }

    /** 逐个标记已激活 Skill 的冻结 revision，使正文重附仍保持来源可审计。 */
    private static void appendActiveSkills(StringBuilder target, List<ActiveSkill> skills) {
        if (skills.isEmpty()) return;
        target.append("\n\n<active-skills>\n");
        for (ActiveSkill skill : skills) {
            target.append("[skill ").append(skill.name()).append('@').append(skill.revision()).append("]\n")
                    .append(normalize(skill.content()).trim()).append('\n');
        }
        target.append("</active-skills>");
    }

    /** 空章节完全省略，避免为未启用能力支付固定上下文开销。 */
    private static void appendSection(StringBuilder target, String name, String content) {
        String normalized = normalize(content).trim();
        if (normalized.isEmpty()) return;
        target.append("\n\n<").append(name).append(">\n")
                .append(normalized).append('\n')
                .append("</").append(name).append('>');
    }

    /** 使用统一 LF 形成跨 Provider、跨 Windows/Unix 相同的 Prompt revision。 */
    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 复用 Kernel 当前的保守字符估算，Provider 实测 Usage 仍由 ContextBudget 后续覆盖。 */
    public static long estimateTokens(String value) {
        return Math.max(1L, (Objects.requireNonNullElse(value, "").length() + 3L) / 4L);
    }

    /** SHA-256 不依赖 Provider 私有 cache key，并以完整十六进制避免截断碰撞。 */
    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** 一次组装所需的最终材料；来源发现、持久化和权限判断都在组装器之外完成。 */
    public record Material(long contextWindowTokens, String environment, String instructions,
                           List<SkillEntry> skills, String summary, List<ActiveSkill> activeSkills,
                           List<String> diagnostics, String revisionMaterial) {
        /** 防御性复制全部集合，使模型调用期间不能被发现器或 Tool 修改。 */
        public Material {
            if (contextWindowTokens < 1) throw new IllegalArgumentException("contextWindowTokens must be positive");
            environment = Objects.requireNonNullElse(environment, "");
            instructions = Objects.requireNonNullElse(instructions, "");
            skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
            summary = Objects.requireNonNullElse(summary, "");
            activeSkills = List.copyOf(Objects.requireNonNull(activeSkills, "activeSkills"));
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
            revisionMaterial = Objects.requireNonNullElse(revisionMaterial, "");
        }
    }

    /** Catalog 只携带模型默认可见的标准 name 与 description。 */
    public record SkillEntry(String name, String description) {
        /** 拒绝空条目，避免预算耗在模型无法调用的匿名 Skill 上。 */
        public SkillEntry {
            name = Objects.requireNonNull(name, "name");
            description = Objects.requireNonNull(description, "description");
            if (name.isBlank() || description.isBlank()) throw new IllegalArgumentException("invalid Skill entry");
        }
    }

    /** 当前 Turn 已激活 Skill 的冻结正文，顺序保持首次激活顺序。 */
    public record ActiveSkill(String name, String revision, String content) {
        /** Skill snapshot 必须提供稳定身份和非空正文，避免重附漂移或伪激活。 */
        public ActiveSkill {
            name = Objects.requireNonNull(name, "name");
            revision = Objects.requireNonNull(revision, "revision");
            content = Objects.requireNonNull(content, "content");
            if (name.isBlank() || revision.isBlank() || content.isBlank()) {
                throw new IllegalArgumentException("invalid active Skill");
            }
        }
    }
}
