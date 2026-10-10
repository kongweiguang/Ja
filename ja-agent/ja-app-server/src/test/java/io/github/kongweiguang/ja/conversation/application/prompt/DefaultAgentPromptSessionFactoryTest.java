// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.prompt;

import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.application.service.AgentInstructionCatalog;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;
import io.github.kongweiguang.ja.conversation.application.gateway.AgentPromptSessionFactory;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.conversation.application.gateway.InstructionScopeRepository;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceRootChangePort;
import io.github.kongweiguang.ja.agent.application.skill.SkillCatalog;
import io.github.kongweiguang.ja.agent.domain.skill.SkillDescriptor;
import io.github.kongweiguang.ja.agent.domain.skill.SkillDocument;
import io.github.kongweiguang.ja.agent.domain.skill.SkillPage;
import io.github.kongweiguang.ja.agent.domain.skill.SkillReadRequest;
import io.github.kongweiguang.ja.agent.domain.skill.SkillRequestContext;
import io.github.kongweiguang.ja.agent.domain.skill.SkillResourceContent;
import io.github.kongweiguang.ja.agent.application.gateway.skill.SkillSource;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

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

    /** 工作目录规则每次都以当前根为基线，旧根通知只在请求意图提交后由该 Thread 消费。 */
    @Test
    void includesCurrentDirectoryBaselineAndConsumesPendingRootChangeOnRequestCommit() throws Exception {
        Path oldRoot = temporary.resolve("old-project").toAbsolutePath().normalize();
        Path newRoot = temporary.resolve("workspace-root-change").toAbsolutePath().normalize();
        Files.createDirectories(newRoot.resolve(".git"));
        WorkspaceRootChangePort.Change change = new WorkspaceRootChangePort.Change(3, oldRoot, newRoot);
        AtomicLong consumedRevision = new AtomicLong();
        WorkspaceRootChangePort changes = new WorkspaceRootChangePort() {
            @Override
            public Optional<Change> pending(String workspaceId, String threadId) {
                assertEquals("ws_prompt_change", workspaceId);
                assertEquals("thread_root_change", threadId);
                return consumedRevision.get() >= change.revision() ? Optional.empty() : Optional.of(change);
            }

            @Override
            public void consume(String workspaceId, String threadId, long revision) {
                assertEquals("ws_prompt_change", workspaceId);
                assertEquals("thread_root_change", threadId);
                consumedRevision.set(revision);
            }
        };
        Fixture fixture = fixture(100_000, new SkillCatalog(List.of()), changes, "ws_prompt_change", newRoot);
        AgentPromptSession session = fixture.open("thread_root_change");

        String firstPrompt = session.prepare("", List.of()).snapshot().systemPrompt();
        String uncommittedPrompt = session.prepare("", List.of()).snapshot().systemPrompt();
        assertTrue(firstPrompt.contains("当前项目工作目录为 `" + newRoot + "`"));
        assertTrue(firstPrompt.contains("历史绝对路径保持原目标，相对路径以当前目录为准"));
        assertTrue(firstPrompt.contains("`" + oldRoot + "` 重绑到 `" + newRoot + "`"));
        assertTrue(uncommittedPrompt.contains("重绑到 `" + newRoot + "`"));

        session.requestCommitted();
        String nextPrompt = session.prepare("", List.of()).snapshot().systemPrompt();

        assertEquals(change.revision(), consumedRevision.get());
        assertTrue(nextPrompt.contains("当前项目工作目录为 `" + newRoot + "`"));
        assertFalse(nextPrompt.contains("重绑到 `" + newRoot + "`"));
    }

    /** Skill 正文只在激活后的动态 System 出现，重复激活幂等，新的 Turn 不继承激活集合。 */
    @Test
    void activatesSkillOnceAndResetsAtNextTurn() throws Exception {
        LiveSkills source = new LiveSkills("review", "skill body");
        Fixture fixture = fixture(100_000, new SkillCatalog(List.of(source)));
        SkillDocument document = fixture.readSkill("review");
        AgentPromptSession first = fixture.open("thread_skill_one");

        AgentPromptSession.SkillActivation activated = first.activateSkill(document);
        AgentPromptSession.SkillActivation repeated = first.activateSkill(document);
        String system = first.prepare("checkpoint summary", List.of()).snapshot().systemPrompt();

        assertTrue(activated.activated());
        assertEquals(activated.receipt(), repeated.receipt());
        assertEquals(document.contentFingerprint(), first.activeSkillReferences().getFirst().contentFingerprint()
                .orElseThrow());
        assertEquals(1, occurrences(system, "skill body"));
        assertTrue(system.contains("checkpoint summary"));
        AgentPromptSession second = fixture.open("thread_skill_two");
        assertFalse(second.prepare("", List.of()).snapshot().systemPrompt().contains("skill body"));
    }

    /** Skill 激活不使用字符估算冒充 Provider Token；最终准入由冻结 envelope 的精确计量负责。 */
    @Test
    void defersSkillWindowAdmissionToProviderTokenCounting() throws Exception {
        String body = "x".repeat(2_000);
        Fixture fixture = fixture(180, new SkillCatalog(List.of(new LiveSkills("large", body))));
        SkillDocument document = fixture.readSkill("large");
        AgentPromptSession session = fixture.open("thread_budget");
        String before = session.currentRevision();

        AgentPromptSession.SkillActivation result = session.activateSkill(document);

        assertTrue(result.activated());
        assertNotEquals(before, session.currentRevision());
    }

    /**
     * 强杀恢复按持久名称重新读取当前 Skill 正文；正文更新立即生效，不要求复原历史内容 revision。
     */
    @Test
    void restoresActiveSkillFromCurrentDocumentWithoutRevisionDriftFailure() throws Exception {
        LiveSkills source = new LiveSkills("review", "original skill body");
        Fixture fixture = fixture(100_001, new SkillCatalog(List.of(source)));
        SkillDocument document = fixture.readSkill("review");
        AgentPromptSession original = fixture.open("thread_resume_original");
        assertTrue(original.activateSkill(document).activated());
        AgentPromptSession.PreparedPrompt prepared = original.prepare("checkpoint summary", List.of());
        String originalFingerprint = original.activeSkillReferences().getFirst().contentFingerprint().orElseThrow();

        source.replaceBody("updated skill body");
        AgentPromptSession resumed = fixture.open("thread_resume_restored");
        resumed.restoreActiveSkills("checkpoint summary", original.activeSkillReferences());

        String restoredFingerprint = resumed.activeSkillReferences().getFirst().contentFingerprint().orElseThrow();
        assertNotEquals(originalFingerprint, restoredFingerprint);
        assertNotEquals(prepared.snapshot().revision(), resumed.currentRevision());
        String restoredSystem = resumed.prepare("checkpoint summary", List.of()).snapshot().systemPrompt();
        assertTrue(restoredSystem.contains("checkpoint summary"));
        assertEquals(1, occurrences(restoredSystem, "updated skill body"));
        assertFalse(restoredSystem.contains("original skill body"));
    }

    /** 构造真实 NIO catalog 与内存 scope 仓储，避免测试绕过生产发现和 revision 语义。 */
    private Fixture fixture(long contextWindow) throws Exception {
        return fixture(contextWindow, new SkillCatalog(List.of()), WorkspaceRootChangePort.empty(),
                "ws_test_prompt");
    }

    /** 允许恢复用例注入实时 Skill reader，同时复用真实 Prompt Session 组装链路。 */
    private Fixture fixture(long contextWindow, SkillCatalog skills) throws Exception {
        return fixture(contextWindow, skills, WorkspaceRootChangePort.empty(), "ws_test_prompt");
    }

    /** 将真实 Workspace 根变更账本接入 Prompt fixture，仍复用正式组装器和指令目录。 */
    private Fixture fixture(long contextWindow, SkillCatalog skills,
                            WorkspaceRootChangePort rootChanges, String workspaceId) throws Exception {
        return fixture(contextWindow, skills, rootChanges, workspaceId, null);
    }

    /** 显式提供当前 Workspace 根，使重绑提示测试与运行时冻结 cwd 使用同一权威路径。 */
    private Fixture fixture(long contextWindow, SkillCatalog skills,
                            WorkspaceRootChangePort rootChanges, String workspaceId,
                            Path workspaceRoot) throws Exception {
        Path workspace = workspaceRoot == null
                ? Files.createDirectory(temporary.resolve("workspace-" + contextWindow))
                : workspaceRoot.toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        Files.createDirectories(workspace.resolve(".git"));
        Path jaHome = Files.createDirectory(temporary.resolve("home-" + contextWindow));
        InMemoryScopes scopes = new InMemoryScopes();
        AgentInstructionCatalog catalog = new AgentInstructionCatalog(scopes,
                Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC));
        return new Fixture(workspace, jaHome, contextWindow,
                new DefaultAgentPromptSessionFactory(catalog, rootChanges), skills, workspaceId);
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

    /**
     * 聚焦测试的会话依赖集合；每次 open 都创建新的生产 Prompt Session。
     *
     * @param workspace 工作区
     * @param jaHome Ja 用户目录
     * @param contextWindow 模型上下文窗口容量
     * @param factory 工厂
     * @param skills Skill 列表
     */
    private record Fixture(Path workspace,
                           Path jaHome,
                           long contextWindow,
                           DefaultAgentPromptSessionFactory factory,
                           SkillCatalog skills,
                           String workspaceId) {
        /** 每次打开都创建独立 Prompt 状态；发现元数据与正文读取仍走生产 Skill 接口。 */
        private AgentPromptSession open(String threadId) {
            SkillRequestContext context = new SkillRequestContext("ja");
            SkillCatalog.Catalog catalog = skills.discover(context, Set.of());
            Map<String, String> skillNamesById = catalog.skills().stream().collect(
                    java.util.stream.Collectors.toUnmodifiableMap(
                            SkillDescriptor::reference, SkillDescriptor::name));
            return factory.open(new AgentPromptSessionFactory.SessionRequest(
                     threadId, workspaceId, workspace, jaHome, true, "Windows 11; pwsh; cwd=" + workspace,
                     ContextBudget.capabilities(contextWindow, 0, true), skills, catalog, skillNamesById));
        }

        /** 沿用生产激活的请求上下文 catalog 路径读取夹具，避免测试绕开来源绑定。 */
        private SkillDocument readSkill(String name) {
            SkillRequestContext context = new SkillRequestContext("ja");
            SkillCatalog.Catalog catalog = skills.discover(context, Set.of());
            return skills.read(catalog, new SkillReadRequest(name, "SKILL.md", 4_000_000));
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
            if (scopes.contains(relativeDirectory)) {
                return Registration.ALREADY_PRESENT;
            }
            scopes.add(relativeDirectory);
            return Registration.REGISTERED;
        }
    }

    /** 提供固定元数据与可变正文，专门验证恢复时按请求实时读取且内容指纹随正文变化。 */
    private static final class LiveSkills implements SkillSource {
        private final String name;
        private String body;

        /** 元数据发现与正文存储分离，模拟按需来源且不依赖测试主机目录。 */
        private LiveSkills(String name, String body) {
            this.name = name;
            this.body = body;
        }

        /** 模拟外部编辑，只替换下一次有界资源读取返回的正文。 */
        private void replaceBody(String body) {
            this.body = body;
        }

        /** 固定来源身份，使不同 Turn 的 read 都经过相同显式注册。 */
        @Override public String id() { return "bundled"; }
        /** 内置来源优先级最低，避免 fixture 引入不相关的覆盖行为。 */
        @Override public int priority() { return 0; }

        /** 每次发现只发布元数据；SKILL.md 正文始终延迟到请求的 read。 */
        @Override
        public SkillPage discover(SkillRequestContext context, String cursor, int pageSize) {
            if (cursor != null) {
                throw new IllegalArgumentException("unexpected Skill page cursor");
            }
            return new SkillPage(List.of(new SkillDescriptor(name, "review skill", id(), 1)), null);
        }

        /** 每次 read 取当前正文，核心目录据此生成可审计 SHA-256 指纹。 */
        @Override
        public SkillResourceContent read(SkillRequestContext context, SkillDescriptor descriptor,
                                         SkillReadRequest request) {
            if (!descriptor.name().equals(name) || !request.skillName().equals(name)
                    || !"SKILL.md".equals(request.resourcePath())) {
                throw new IllegalArgumentException("unexpected live Skill read");
            }
            return new SkillResourceContent(body, false);
        }
    }
}
