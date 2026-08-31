// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.prompt;

import io.github.kongweiguang.ja.conversation.domain.ContextBudget;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.instruction.AgentInstructionCatalog;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSession;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSessionFactory;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.InstructionScopeRepository;
import io.github.kongweiguang.ja.conversation.port.out.SkillCatalog;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Prompt Session 的 Turn 隔离、渐进发现、Skill 激活与写前 revision 门禁。 */
final class DefaultAgentPromptSessionFactoryTest {
    @TempDir Path temporary;

    /** read 可完成新 scope 发现，但同 batch 后续任何外部副作用必须先让模型刷新上下文。 */
    @Test
    void allowsDiscoveryReadAndRejectsLaterExternalToolInSameBatch() throws Exception {
        Fixture fixture = fixture(100_000);
        Path nested = Files.createDirectories(fixture.workspace().resolve("nested"));
        Files.writeString(nested.resolve("AGENTS.md"), "nested rule");
        AgentPromptSession session = fixture.open("thread_read");
        String batchRevision = session.prepare("", List.of()).snapshot().revision();

        AgentPromptSession.ToolGuard read = session.beforeTool(
                invocation("read", "nested/source.txt"), ToolSideEffect.READ_ONLY, batchRevision);

        assertTrue(read.proceed());
        assertNotEquals(batchRevision, session.currentRevision());
        AgentPromptSession.ToolGuard shell = session.beforeTool(
                invocation("shell", null), ToolSideEffect.EXTERNAL, batchRevision);
        assertFalse(shell.proceed());
        assertEquals("INSTRUCTION_CONTEXT_REFRESH_REQUIRED", shell.code());
        assertTrue(session.prepare("", List.of()).snapshot().systemPrompt().contains("nested rule"));
    }

    /** edit/write 首次触达新规则时在文件副作用与 Approval 之前直接返回稳定刷新错误。 */
    @Test
    void rejectsWriteThatDiscoversNestedGuidance() throws Exception {
        Fixture fixture = fixture(100_000);
        Path nested = Files.createDirectories(fixture.workspace().resolve("write-target"));
        Files.writeString(nested.resolve("AGENTS.override.md"), "write guard");
        AgentPromptSession session = fixture.open("thread_write");
        String batchRevision = session.prepare("", List.of()).snapshot().revision();

        AgentPromptSession.ToolGuard guard = session.beforeTool(
                invocation("write", "write-target/new.txt"), ToolSideEffect.EXTERNAL, batchRevision);

        assertFalse(guard.proceed());
        assertEquals("INSTRUCTION_CONTEXT_REFRESH_REQUIRED", guard.code());
        assertFalse(Files.exists(nested.resolve("new.txt")));
    }

    /** Skill 正文只在激活后的动态 System 出现，重复激活幂等，新的 Turn 不继承激活集合。 */
    @Test
    void activatesSkillOnceAndResetsAtNextTurn() throws Exception {
        Fixture fixture = fixture(100_000);
        AgentPromptSession first = fixture.open("thread_skill_one");
        SkillCatalog.SkillDocument document =
                new SkillCatalog.SkillDocument("review", "SKILL.md", "skill_revision", "skill body", false);

        AgentPromptSession.SkillActivation activated = first.activateSkill(document);
        AgentPromptSession.SkillActivation repeated = first.activateSkill(document);
        String system = first.prepare("checkpoint summary", List.of()).snapshot().systemPrompt();

        assertTrue(activated.activated());
        assertEquals(activated.receipt(), repeated.receipt());
        assertEquals(1, occurrences(system, "skill body"));
        assertTrue(system.contains("checkpoint summary"));
        AgentPromptSession second = fixture.open("thread_skill_two");
        assertFalse(second.prepare("", List.of()).snapshot().systemPrompt().contains("skill body"));
    }

    /** Skill 激活不使用字符估算冒充 Provider Token；最终准入由冻结 envelope 的精确计量负责。 */
    @Test
    void defersSkillWindowAdmissionToProviderTokenCounting() throws Exception {
        Fixture fixture = fixture(180);
        AgentPromptSession session = fixture.open("thread_budget");
        String before = session.currentRevision();
        String body = "x".repeat(2_000);

        AgentPromptSession.SkillActivation result = session.activateSkill(
                new SkillCatalog.SkillDocument("large", "SKILL.md", "skill_large", body, false));

        assertTrue(result.activated());
        assertNotEquals(before, session.currentRevision());
    }

    /** 构造真实 NIO catalog 与内存 scope 仓储，避免测试绕过生产发现和 revision 语义。 */
    private Fixture fixture(long contextWindow) throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace-" + contextWindow));
        Files.createDirectory(workspace.resolve(".git"));
        Path jaHome = Files.createDirectory(temporary.resolve("home-" + contextWindow));
        InMemoryScopes scopes = new InMemoryScopes();
        AgentInstructionCatalog catalog = new AgentInstructionCatalog(scopes,
                Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC));
        return new Fixture(workspace, jaHome, contextWindow,
                new DefaultAgentPromptSessionFactory(catalog), new EmptySkills());
    }

    /** 生成结构化文件调用；Shell 没有 path，证明 V1 不解析命令字符串中的目录。 */
    private static AgentTool.Invocation invocation(String tool, String path) {
        JsonObject arguments = path == null
                ? JsonObjects.builder().putText("command", "echo ok").build()
                : JsonObjects.builder().putText("path", path).build();
        return new AgentTool.Invocation("call_" + tool, tool, arguments, 0);
    }

    /** 统计正文出现次数，避免 contains 掩盖动态 System 的重复注入。 */
    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int offset = 0; (offset = value.indexOf(needle, offset)) >= 0; offset += needle.length()) {
            count++;
        }
        return count;
    }

    /** 聚焦测试的会话依赖集合；每次 open 都创建新的生产 Prompt Session。 */
    private record Fixture(Path workspace, Path jaHome, long contextWindow,
                           DefaultAgentPromptSessionFactory factory, SkillCatalog skills) {
        /** 使用同一冻结 Skill snapshot，隔离本用例与磁盘 Skill 扫描。 */
        private AgentPromptSession open(String threadId) {
            SkillCatalog.SkillSnapshot snapshot = skills.emptySnapshot();
            return factory.open(new AgentPromptSessionFactory.SessionRequest(
                    threadId, workspace, jaHome, true, "Windows 11; pwsh; cwd=" + workspace,
                    ContextBudget.capabilities(contextWindow, 0, true), skills, snapshot));
        }
    }

    /** 仅保存规范相对 scope，模拟 SQLite 的 Thread 隔离与幂等登记。 */
    private static final class InMemoryScopes implements InstructionScopeRepository {
        private final Map<String, List<String>> values = new HashMap<>();

        /** 返回稳定副本，避免 catalog 观察测试集合的后续变化。 */
        @Override
        public List<String> list(String threadId) {
            return List.copyOf(values.getOrDefault(threadId, List.of()));
        }

        /** 第一次登记追加，重复目录返回幂等结果。 */
        @Override
        public Registration register(String threadId, String relativeDirectory, Instant discoveredAt) {
            List<String> scopes = values.computeIfAbsent(threadId, ignored -> new ArrayList<>());
            if (scopes.contains(relativeDirectory)) return Registration.ALREADY_PRESENT;
            scopes.add(relativeDirectory);
            return Registration.REGISTERED;
        }
    }

    /** 空 Skill adapter 保证激活正文来自显式冻结文档，而不是测试主机目录。 */
    private static final class EmptySkills implements SkillCatalog {
        /** 本用例禁止扫描来源。 */
        @Override public SkillSnapshot snapshot(SnapshotRequest request) { return emptySnapshot(); }
        /** 空快照具有稳定 revision，仍参与 Prompt revision。 */
        @Override public SkillSnapshot emptySnapshot() {
            return new SkillSnapshot("skills_empty", List.of(), Instant.EPOCH);
        }
        /** 空快照过滤保持空，不引入兼容读取路径。 */
        @Override public SkillSnapshot select(SkillSnapshot snapshot, List<String> revisions) { return snapshot; }
        /** 本用例不通过 adapter 读取 Skill 资源。 */
        @Override public SkillDocument read(SkillSnapshot snapshot, SkillReadRequest request) {
            throw new AssertionError("unexpected Skill read");
        }
    }
}
