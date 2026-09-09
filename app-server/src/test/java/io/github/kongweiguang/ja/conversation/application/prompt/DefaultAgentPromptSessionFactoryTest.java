// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.prompt;

import io.github.kongweiguang.ja.conversation.domain.ContextBudget;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.instruction.AgentInstructionCatalog;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSession;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSessionFactory;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.ContextTransform;
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
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        SkillCatalog.SkillDocument document =
                new SkillCatalog.SkillDocument("review", "SKILL.md", "skill body", false);
        Fixture fixture = fixture(100_000, new LiveSkills(document));
        AgentPromptSession first = fixture.open("thread_skill_one");

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
        String body = "x".repeat(2_000);
        SkillCatalog.SkillDocument document =
                new SkillCatalog.SkillDocument("large", "SKILL.md", body, false);
        Fixture fixture = fixture(180, new LiveSkills(document));
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
        SkillCatalog.SkillDocument document = new SkillCatalog.SkillDocument(
                "review", "SKILL.md", "original skill body", false);
        LiveSkills skills = new LiveSkills(document);
        Fixture fixture = fixture(100_001, skills);
        AgentPromptSession original = fixture.open("thread_resume_original");
        assertTrue(original.activateSkill(document).activated());
        AgentPromptSession.PreparedPrompt prepared = original.prepare("checkpoint summary", List.of());

        skills.replaceBody("updated skill body");
        AgentPromptSession resumed = fixture.open("thread_resume_restored");
        resumed.restoreActiveSkills("checkpoint summary", original.activeSkillReferences());

        assertNotEquals(prepared.snapshot().revision(), resumed.currentRevision());
        String restoredSystem = resumed.prepare("checkpoint summary", List.of()).snapshot().systemPrompt();
        assertTrue(restoredSystem.contains("checkpoint summary"));
        assertEquals(1, occurrences(restoredSystem, "updated skill body"));
        assertFalse(restoredSystem.contains("original skill body"));
    }

    /** 变换器按 order/id 确定执行，后序只能裁剪派生片段，planning 与 dispatch 复用的 prepare 均稳定。 */
    @Test
    void ordersAndTrimsDerivedContextOnEveryPrepare() throws Exception {
        ContextTransform add = transform("a-add", 10, context -> new ContextTransform.DerivedContext(List.of(
                new ContextTransform.SystemFragment("keep", "kept context"),
                new ContextTransform.SystemFragment("drop", "removed context"))));
        ContextTransform trim = transform("z-trim", 10, context -> new ContextTransform.DerivedContext(
                context.systemFragments().stream().filter(fragment -> !"drop".equals(fragment.id())).toList()));
        Fixture fixture = fixture(100_002, new EmptySkills(), List.of(trim, add));
        AgentPromptSession baseline = fixture.openWithoutTransforms("thread_transform");
        AgentPromptSession session = fixture.open("thread_transform");

        AgentPromptSession.PreparedPrompt untransformed = baseline.prepare("", List.of());
        AgentPromptSession.PreparedPrompt planning = session.prepare("", List.of());
        AgentPromptSession.PreparedPrompt dispatch = session.prepare("", List.of());

        assertTrue(planning.snapshot().systemPrompt().contains("kept context"));
        assertFalse(planning.snapshot().systemPrompt().contains("removed context"));
        assertNotEquals(untransformed.snapshot().revision(), planning.snapshot().revision());
        assertTrue(planning.snapshot().systemTokens() > untransformed.snapshot().systemTokens());
        assertEquals(planning.snapshot(), dispatch.snapshot());
    }

    /** 空或重复身份在工厂或派生值创建时失败，避免到首个 Provider 请求才暴露不确定上下文。 */
    @Test
    void rejectsInvalidOrDuplicateContextIdentity() throws Exception {
        AgentInstructionCatalog catalog = new AgentInstructionCatalog(new InMemoryScopes(),
                Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC));
        ContextTransform first = transform("duplicate", 1, UnaryOperator.identity());
        ContextTransform second = transform("duplicate", 2, UnaryOperator.identity());

        assertThrows(IllegalArgumentException.class,
                () -> new DefaultAgentPromptSessionFactory(catalog,
                        List.of(transform(" ", 0, UnaryOperator.identity()))));
        assertThrows(IllegalArgumentException.class,
                () -> new DefaultAgentPromptSessionFactory(catalog, List.of(first, second)));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextTransform.SystemFragment(" ", "body"));
        ContextTransform.SystemFragment fragment = new ContextTransform.SystemFragment("same", "body");
        assertThrows(IllegalArgumentException.class,
                () -> new ContextTransform.DerivedContext(List.of(fragment, fragment)));
    }

    /** 变换器抛错或返回空结果时在 Session 打开阶段失败关闭，不能形成可发送的半变换 Prompt。 */
    @Test
    void failsClosedWhenContextTransformThrowsOrReturnsNull() throws Exception {
        Fixture nullResult = fixture(100_003, new EmptySkills(), List.of(
                transform("broken", 0, ignored -> null)));
        Fixture failure = fixture(100_004, new EmptySkills(), List.of(
                transform("failed", 0, ignored -> {
                    throw new IllegalStateException("transform failed");
                })));

        assertThrows(NullPointerException.class, () -> nullResult.open("thread_transform_null"));
        assertThrows(IllegalStateException.class, () -> failure.open("thread_transform_failure"));
    }

    /** 构造真实 NIO catalog 与内存 scope 仓储，避免测试绕过生产发现和 revision 语义。 */
    private Fixture fixture(long contextWindow) throws Exception {
        return fixture(contextWindow, new EmptySkills());
    }

    /** 允许恢复用例注入实时 Skill reader，同时复用真实 Prompt Session 组装链路。 */
    private Fixture fixture(long contextWindow, SkillCatalog skills) throws Exception {
        return fixture(contextWindow, skills, List.of());
    }

    /** 显式注入冻结变换器列表，验证测试与生产使用完全相同的 Prompt Session 构造路径。 */
    private Fixture fixture(long contextWindow, SkillCatalog skills,
                            List<ContextTransform> contextTransforms) throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace-" + contextWindow));
        Files.createDirectory(workspace.resolve(".git"));
        Path jaHome = Files.createDirectory(temporary.resolve("home-" + contextWindow));
        InMemoryScopes scopes = new InMemoryScopes();
        AgentInstructionCatalog catalog = new AgentInstructionCatalog(scopes,
                Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC));
        return new Fixture(workspace, jaHome, contextWindow, catalog,
                new DefaultAgentPromptSessionFactory(catalog, contextTransforms), skills);
    }

    /** 为聚焦测试绑定显式身份与顺序，同时把行为保持为普通同步 Java 变换。 */
    private static ContextTransform transform(
            String id, int order, UnaryOperator<ContextTransform.DerivedContext> operation) {
        return new ContextTransform() {
            /** 测试身份模拟组合根注册时的稳定键。 */
            @Override public String id() { return id; }
            /** 测试顺序用于证明输入列表顺序不会改变执行结果。 */
            @Override public int order() { return order; }
            /** 直接委托纯变换，测试不引入线程或 IO。 */
            @Override public DerivedContext transform(DerivedContext context) {
                return operation.apply(context);
            }
        };
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
                           AgentInstructionCatalog instructions,
                           DefaultAgentPromptSessionFactory factory, SkillCatalog skills) {
        /** 每个 Session 重新发现元数据；正文是否变化由注入 reader 的 read 时刻决定。 */
        private AgentPromptSession open(String threadId) {
            return open(threadId, factory);
        }

        /** 用同一请求事实打开无变换基线，隔离派生片段对 revision 与计量的唯一影响。 */
        private AgentPromptSession openWithoutTransforms(String threadId) {
            return open(threadId, new DefaultAgentPromptSessionFactory(instructions, List.of()));
        }

        /** 复用完全相同的环境和 Skill 目录创建 Session，避免测试基线引入路径或配置差异。 */
        private AgentPromptSession open(String threadId, AgentPromptSessionFactory sessionFactory) {
            SkillCatalog.Catalog catalog = skills.discover(new SkillCatalog.DiscoveryRequest(
                    workspace, jaHome.resolve("agents"), jaHome.resolve("ja"), true));
            Map<String, String> skillNamesById = catalog.skills().stream().collect(
                    java.util.stream.Collectors.toUnmodifiableMap(
                            descriptor -> "skill_" + descriptor.name(), SkillCatalog.SkillDescriptor::name));
            return sessionFactory.open(new AgentPromptSessionFactory.SessionRequest(
                     threadId, workspace, jaHome, true, "Windows 11; pwsh; cwd=" + workspace,
                     ContextBudget.capabilities(contextWindow, 0, true), skills, catalog, skillNamesById));
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

    /** 空 Skill adapter 保证激活正文来自显式文档，而不是测试主机目录。 */
    private static final class EmptySkills implements SkillCatalog {
        /** 本用例禁止扫描来源。 */
        @Override public Catalog discover(DiscoveryRequest request) { return emptyCatalog(); }
        /** 空目录不携带正文或内容 revision。 */
        @Override public Catalog emptyCatalog() {
            return new Catalog(List.of());
        }
        /** 空目录过滤保持空，不引入兼容读取路径。 */
        @Override public Catalog select(Catalog catalog, List<String> names) { return catalog; }
        /** 本用例不通过 adapter 读取 Skill 资源。 */
        @Override public SkillDocument read(Catalog catalog, SkillReadRequest request) {
            throw new AssertionError("unexpected Skill read");
        }
    }

    /** 提供稳定元数据与可变正文，专门验证 Resume 在 read 时取得当前内容。 */
    private static final class LiveSkills implements SkillCatalog {
        private SkillDocument document;
        private final Catalog catalog;

        /** 元数据发现与正文存储分离，模拟 Pi 式渐进披露而不依赖测试磁盘。 */
        private LiveSkills(SkillDocument document) {
            this.document = document;
            this.catalog = new Catalog(List.of(new SkillDescriptor(
                    document.skillName(), "review skill", Source.BUNDLED)));
        }

        /** 模拟外部编辑，只替换下一次 read 返回的正文。 */
        private void replaceBody(String body) {
            document = new SkillDocument(document.skillName(), document.resourcePath(), body, false);
        }
        /** 发现只返回 name/description/source。 */
        @Override public Catalog discover(DiscoveryRequest request) { return catalog; }
        /** 禁用 Skill 时返回真正空目录。 */
        @Override public Catalog emptyCatalog() { return new Catalog(List.of()); }
        /** 唯一目录无需二次选择，但仍校验稳定名称。 */
        @Override public Catalog select(Catalog source, List<String> names) {
            if (!source.equals(catalog) || !names.equals(List.of(document.skillName()))) {
                throw new IllegalArgumentException("unexpected live Skill selection");
            }
            return catalog;
        }
        /** 每次返回当前字段，模拟生产 adapter 在调用时重新读取资源。 */
        @Override public SkillDocument read(Catalog source, SkillReadRequest request) {
            if (!source.equals(catalog) || !request.skillName().equals(document.skillName())
                || !request.resourcePath().equals(document.resourcePath())) {
                throw new IllegalArgumentException("unexpected live Skill read");
            }
            return document;
        }
    }
}
