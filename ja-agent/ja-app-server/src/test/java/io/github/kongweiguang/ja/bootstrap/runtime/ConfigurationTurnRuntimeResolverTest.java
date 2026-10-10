// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.catalog.infrastructure.mcp.runtime.McpRuntime;
import io.github.kongweiguang.ja.catalog.infrastructure.mcp.runtime.McpToolCatalog;
import io.github.kongweiguang.ja.catalog.infrastructure.mcp.support.McpServerDefinition;
import io.github.kongweiguang.ja.catalog.application.gateway.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationGenerationSnapshot;
import io.github.kongweiguang.ja.configuration.domain.model.SkillReference;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.domain.turn.TurnLimits;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.conversation.application.gateway.JaToolCatalogIdentity;
import io.github.kongweiguang.ja.catalog.application.gateway.McpGateway;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.skill.SkillCatalog;
import io.github.kongweiguang.ja.agent.domain.skill.SkillDescriptor;
import io.github.kongweiguang.ja.agent.domain.skill.SkillPage;
import io.github.kongweiguang.ja.agent.domain.skill.SkillRequestContext;
import io.github.kongweiguang.ja.agent.application.gateway.skill.SkillSource;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.collaboration.interfaces.tools.TaskAgentToolGateway;
import io.github.kongweiguang.ja.conversation.application.admission.JaAdmissionParticipants;
import io.github.kongweiguang.ja.conversation.application.gateway.TaskCapabilityCeilingPort;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeRequest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 验证冻结配置身份与 Runtime 恢复指纹在适配层保持各自的稳定语义。 */
final class ConfigurationTurnRuntimeResolverTest {
    /** 主异常和两次关闭失败都必须保留；前一个 owner 失败不能阻止释放配置租约。 */
    @Test
    void cleanupPreservesPreparationFailureAndClosesEveryOwner() {
        RuntimeException preparation = new IllegalStateException("preparation failed");
        RuntimeException catalogClose = new IllegalStateException("catalog close failed");
        RuntimeException leaseClose = new IllegalStateException("lease close failed");
        List<String> closed = new ArrayList<>();

        assertDoesNotThrow(() -> ConfigurationTurnRuntimeResolver.closeRuntimeInputs(preparation,
            () -> {
                closed.add("catalog");
                throw catalogClose;
            }, () -> {
                closed.add("lease");
                throw leaseClose;
            }));

        assertEquals(List.of("catalog", "lease"), closed);
        assertEquals(List.of(catalogClose, leaseClose), List.of(preparation.getSuppressed()));
    }

    /** 正常关闭必须重抛首个失败；同一失败重放不会自抑制，也不会遗留后续资源。 */
    @Test
    void cleanupReplaysOneFailureWithoutSkippingRemainingOwners() {
        RuntimeException expected = new IllegalStateException("shared close failure");
        List<String> closed = new ArrayList<>();

        RuntimeException observed = assertThrows(RuntimeException.class,
            () -> ConfigurationTurnRuntimeResolver.closeRuntimeInputs(null,
                () -> {
                    closed.add("catalog");
                    throw expected;
                }, () -> {
                    closed.add("lease");
                    throw expected;
                }, () -> closed.add("session")));

        assertSame(expected, observed);
        assertEquals(List.of("catalog", "lease", "session"), closed);
        assertEquals(0, expected.getSuppressed().length);
        assertDoesNotThrow(() -> ConfigurationTurnRuntimeResolver.closeRuntimeInputs(expected,
            () -> { throw expected; }));
        assertEquals(0, expected.getSuppressed().length);
    }

    /**
     * clarification 准入由真实 Plan 执行来源和只读规划策略决定；普通 Thread 的 PLAN 偏好
     * 不得扩大其它来源的权限，Plan 执行则不能因默认模式丢失必要的用户决策入口。
     */
    @Test
    void clarificationAvailabilityUsesTurnOriginAndPlanningPolicy() {
        assertTrue(clarification(TurnOrigin.PLAN_EXECUTION, CollaborationMode.DEFAULT, false));
        assertTrue(clarification(TurnOrigin.USER, CollaborationMode.PLAN, false));
        assertTrue(clarification(TurnOrigin.CHILD_TASK, CollaborationMode.PLAN, false));
        assertFalse(clarification(TurnOrigin.USER, CollaborationMode.DEFAULT, false));
        assertFalse(clarification(TurnOrigin.CHILD_TASK, CollaborationMode.DEFAULT, false));
        assertTrue(clarification(TurnOrigin.USER_CONTINUATION, CollaborationMode.DEFAULT, true));
    }

    /** 恢复旧准入请求时，MCP 与 task 工具共享从本次解析时刻重开的 deadline。 */
    @Test
    void reResolveUsesFreshDeadlineForMcpAndTaskTools() {
        Instant requestedAt = Instant.parse("2026-09-10T12:00:00Z");
        Instant resolvedAt = Instant.parse("2026-09-30T12:00:00Z");
        Duration requestWindow = Duration.ofMinutes(2);
        TurnRuntimeRequest request = new TurnRuntimeRequest(
                "thr_deadline", "turn_deadline", Path.of(".").toAbsolutePath(), "ws_deadline",
                "provider_deadline", "model_deadline", null, AccessMode.FULL_ACCESS,
                CollaborationMode.DEFAULT, TurnOrigin.USER, requestedAt);
        TurnLimits limits = new TurnLimits(128_000, 8_192, requestWindow);

        Instant deadline = ConfigurationTurnRuntimeResolver.requestDeadline(
                limits, Clock.fixed(resolvedAt, ZoneOffset.UTC));

        assertEquals(resolvedAt.plus(requestWindow), deadline);
        assertTrue(deadline.isAfter(resolvedAt));
        assertFalse(request.requestedAt().plus(requestWindow).isAfter(resolvedAt));
        assertTrue(deadline.isAfter(request.requestedAt().plus(requestWindow)));
    }

    /** 以来源对应的完整持久 identity 构造请求，隔离 clarification 策略与外部配置副作用。 */
    private static boolean clarification(TurnOrigin origin, CollaborationMode mode, boolean configured) {
        String planRevisionId = origin == TurnOrigin.PLAN_EXECUTION ? "planrev_clarification" : null;
        String runId = origin == TurnOrigin.PLAN_EXECUTION ? "run_clarification" : null;
        return ConfigurationTurnRuntimeResolver.isClarificationEnabled(new TurnRuntimeRequest(
                "thr_clarification", "turn_clarification", Path.of(".").toAbsolutePath(), "ws_clarification",
                "provider_clarification", "model_clarification", "medium", AccessMode.FULL_ACCESS, mode,
                origin, planRevisionId, runId, Instant.parse("2026-09-10T12:00:00Z"), null, java.util.Optional.empty()), configured);
    }

    /** Side Task 身份必须留在 system environment，且真实通信 Tool 的目标参数不能被自然语言改名。 */
    @Test
    void rendersSideTaskIdentityAsStructuredSystemContext() {
        TaskCapabilityCeilingPort.RuntimeIdentity identity = new TaskCapabilityCeilingPort.RuntimeIdentity(
                "thr_side", "thr_parent", "thr_root", "Review", "Parent", "Main",
                TaskCapabilityCeilingPort.Kind.SIDE_TASK);

        String prompt = ConfigurationTurnRuntimeResolver.sideTaskIdentityPrompt(identity);

        assertEquals(true, prompt.contains("role: SIDE_TASK"));
        assertEquals(true, prompt.contains("taskThreadId: thr_side"));
        assertEquals(true, prompt.contains("parentThreadId: thr_parent"));
        assertEquals(true, prompt.contains("rootThreadId: thr_root"));
        assertEquals(true, prompt.contains("parentTaskName: \"Parent\""));
        assertEquals(true, prompt.contains("mainTaskName: \"Main\""));
        assertEquals(true, prompt.contains("send_message Tool"));
        assertEquals(true, prompt.contains("targetThreadId and message"));
        assertEquals(true, prompt.contains("temporary side chat"));
        assertEquals(true, prompt.contains("Inherited history is background context only"));
        assertEquals(true, prompt.contains("Do not send periodic progress or automatic results"));
        assertEquals(true, prompt.contains("before its next model request"));
        assertEquals(true, prompt.contains("does not wake, interrupt, or request a reply"));
        assertEquals(false, prompt.contains("UserContent"));
    }

    /**
     * 消息引用必须保留来源限定授权，并只发布与胜出发现来源一致的交集；
     * 发现目录里的同名元数据不能反向生成跨来源许可。
     */
    @Test
    void skillIdentityMapUsesFrozenIdsForEnabledDiscoveredSkillsOnly() {
        List<ConfigurationGenerationSnapshot.Skill> configured = List.of(
                new ConfigurationGenerationSnapshot.Skill(SkillReference.parse("ja:review")),
                new ConfigurationGenerationSnapshot.Skill(SkillReference.parse("ja:disabled")),
                new ConfigurationGenerationSnapshot.Skill(SkillReference.parse("project:missing")));
        List<SkillDescriptor> discovered = List.of(
                new SkillDescriptor("review", "Review changes", "ja", 1),
                new SkillDescriptor("disabled", "Disabled skill", "ja", 1),
                new SkillDescriptor("unconfigured", "Unconfigured skill", "project", 1));

        assertEquals(Map.of("project:unconfigured", "unconfigured"),
                ConfigurationTurnRuntimeResolver.skillNamesById(configured, discovered));
    }

    /**
     * 文件删除和同名覆盖都只收窄当前能力；缺失记录留给设置页移除，不阻断其它 Turn。
     */
    @Test
    void missingOrShadowedSkillDoesNotBlockTurn() {
        List<ConfigurationGenerationSnapshot.Skill> projectOnly = List.of(
                new ConfigurationGenerationSnapshot.Skill(SkillReference.parse("project:project-only")));
        assertEquals(List.of(), ConfigurationTurnRuntimeResolver.availableSkillNames(
                projectOnly, List.of()));

        List<ConfigurationGenerationSnapshot.Skill> mixed = List.of(
                new ConfigurationGenerationSnapshot.Skill(SkillReference.parse("project:project-only")));
        List<SkillDescriptor> discovered = List.of(
                new SkillDescriptor("review", "Review changes", "ja", 1));
        assertEquals(List.of("review"), ConfigurationTurnRuntimeResolver.availableSkillNames(mixed, discovered));

        List<ConfigurationGenerationSnapshot.Skill> missingUser = List.of(
                new ConfigurationGenerationSnapshot.Skill(SkillReference.parse("user:missing")));
        assertEquals(List.of(), ConfigurationTurnRuntimeResolver.availableSkillNames(
                missingUser, List.of()));

        List<ConfigurationGenerationSnapshot.Skill> duplicateName = List.of(
                new ConfigurationGenerationSnapshot.Skill(SkillReference.parse("user:same-name")));
        assertEquals(List.of("same-name"), ConfigurationTurnRuntimeResolver.availableSkillNames(
                duplicateName, List.of(new SkillDescriptor("same-name", "Project skill", "project", 1))));
    }

    /** 构造真实身份绑定目录，验证子任务筛选使用发现时的来源定位能力。 */
    private static SkillFixture skills(String... names) {
        List<SkillDescriptor> descriptors = java.util.Arrays.stream(names)
                .map(name -> new SkillDescriptor(name, name + " skill", "ja", 1)).toList();
        SkillCatalog service = new SkillCatalog(descriptors.isEmpty()
                ? List.of() : List.of(new FixtureSkillSource(descriptors)));
        SkillRequestContext context = new SkillRequestContext("ja");
        return new SkillFixture(service, service.discover(context, Set.of()));
    }

    /**
     * 将 core 的不透明目录与发现它的同一服务绑定，防止测试越过来源身份边界。
     *
     * @param service 服务
     * @param catalog 目录
     */
    private record SkillFixture(SkillCatalog service,
                                SkillCatalog.Catalog catalog) {
    }

    /** 仅提供测试元数据；能力筛选不应读取 Skill 正文或依赖其文件定位信息。 */
    private static final class FixtureSkillSource implements SkillSource {
        private final List<SkillDescriptor> descriptors;

        /** 冻结测试元数据，避免一次解析断言期间来源身份或排序发生变化。 */
        FixtureSkillSource(List<SkillDescriptor> descriptors) {
            this.descriptors = List.copyOf(descriptors);
        }

        /** 使用 Ja 的稳定来源身份，使测试描述符匹配已保存的 Skill 引用。 */
        @Override public String id() { return "ja"; }

        /** 沿用 Ja 来源优先级，让同名能力筛选结果保持确定。 */
        @Override public int priority() { return 2; }

        /** 测试目录至多两个 Skill，因此返回单页并拒绝意外的续页请求。 */
        @Override public SkillPage discover(SkillRequestContext context, String cursor, int pageSize) {
            if (cursor != null) {
                throw new IllegalArgumentException("fixture has only one page");
            }
            if (!"ja".equals(context.tenantId())) {
                throw new IllegalArgumentException("unexpected fixture tenant");
            }
            return new SkillPage(descriptors, null);
        }

        /** 若解析器读取正文则立即失败，因为此用例只验证身份筛选，不涉及 Skill 激活。 */
        @Override public io.github.kongweiguang.ja.agent.domain.skill.SkillResourceContent read(
                SkillRequestContext context, SkillDescriptor descriptor,
                io.github.kongweiguang.ja.agent.domain.skill.SkillReadRequest request) {
            throw new AssertionError("resolver fixture must not read Skill bodies");
        }
    }

    /** 对象键的注册顺序不得进入恢复指纹，但真实 Schema 内容变化必须改变摘要。 */
    @Test
    void toolCatalogDigestCanonicalizesNestedObjectOrder() {
        JsonObject first = object("type", new JsonText("object"), "description", new JsonText("fixture"));
        JsonObject reversed = object(
                "description", new JsonText("fixture"), "type", new JsonText("object"));
        JsonObject changed = object("type", new JsonText("object"), "description", new JsonText("changed"));
        McpGateway.McpSnapshot mcp = new McpGateway.McpSnapshot(
                "mcp-empty", List.of(), Instant.parse("2026-09-01T00:00:00Z"));

        String baseline = ConfigurationTurnRuntimeResolver.toolCatalogDigest(
                List.of(tool(first)), List.of(), mcp, Map.of());
        assertEquals(baseline, ConfigurationTurnRuntimeResolver.toolCatalogDigest(
                List.of(tool(reversed)), List.of(), mcp, Map.of()));
        assertNotEquals(baseline, ConfigurationTurnRuntimeResolver.toolCatalogDigest(
                List.of(tool(changed)), List.of(), mcp, Map.of()));
    }

    /** 隐藏用户问答入口仍属于完整安全目录；角色显隐只能改变暴露集合，不能把定义从 digest 删除。 */
    @Test
    void inheritedExecutionDigestIncludesHiddenUserQuestionDefinition() {
        JsonObject schema = object("type", new JsonText("object"), "description", new JsonText("fixture"));
        ToolSpec question = new ToolSpec("request_user_input", "Ask the user", schema);
        JaTool questionTool = tool("request_user_input", schema, ToolSideEffect.EXTERNAL,
                JaTool.WorkspaceMutationMode.NONE, JaTool.ApprovalRequirement.USER_REQUIRED);
        JaTool.Definition contribution = new JaTool.Definition(question,
                ToolSideEffect.EXTERNAL, JaTool.WorkspaceMutationMode.NONE,
                questionTool.bindingDescriptor(), JaTool.PlanAccess.DISALLOWED,
                JaTool.ApprovalRequirement.USER_REQUIRED,
                ignored -> { throw new AssertionError("digest must not materialize tools"); });
        McpGateway.McpSnapshot mcp = new McpGateway.McpSnapshot("mcp-empty", List.of(), Instant.EPOCH);
        String withoutQuestion = ConfigurationTurnRuntimeResolver.toolCatalogDigest(
                List.of(), List.of(), mcp, Map.of());
        String withHiddenQuestion = ConfigurationTurnRuntimeResolver.toolCatalogDigest(
                List.of(), List.of(contribution), mcp, Map.of());
        assertNotEquals(withoutQuestion, withHiddenQuestion);
    }

    /** 目录摘要必须直接覆盖安全声明，不能让相同 Schema 的只读与外部副作用 Tool 共享身份。 */
    @Test
    void toolCatalogDigestIncludesSideEffectAndWorkspaceMutationMode() {
        JsonObject schema = object("type", new JsonText("object"), "description", new JsonText("fixture"));
        McpGateway.McpSnapshot mcp = new McpGateway.McpSnapshot(
                "mcp-empty", List.of(), Instant.parse("2026-09-01T00:00:00Z"));

        String readOnly = ConfigurationTurnRuntimeResolver.toolCatalogDigest(
                List.of(tool(schema, ToolSideEffect.READ_ONLY, JaTool.WorkspaceMutationMode.NONE)),
                List.of(), mcp, Map.of());
        String external = ConfigurationTurnRuntimeResolver.toolCatalogDigest(
                List.of(tool(schema, ToolSideEffect.EXTERNAL, JaTool.WorkspaceMutationMode.NONE)),
                List.of(), mcp, Map.of());
        String unobservable = ConfigurationTurnRuntimeResolver.toolCatalogDigest(
                List.of(tool(schema, ToolSideEffect.EXTERNAL, JaTool.WorkspaceMutationMode.UNOBSERVABLE)),
                List.of(), mcp, Map.of());

        assertNotEquals(readOnly, external);
        assertNotEquals(external, unobservable);
    }

    /** 可信内核审批标记改变时必须生成新的目录身份，避免恢复复用错误权限语义。 */
    @Test
    void toolCatalogDigestIncludesApprovalRequirement() {
        JsonObject schema = object("type", new JsonText("object"), "description", new JsonText("fixture"));
        McpGateway.McpSnapshot mcp = new McpGateway.McpSnapshot(
                "mcp-empty", List.of(), Instant.parse("2026-09-01T00:00:00Z"));

        String required = ConfigurationTurnRuntimeResolver.toolCatalogDigest(
                List.of(tool(schema)), List.of(), mcp, Map.of());
        String trusted = ConfigurationTurnRuntimeResolver.toolCatalogDigest(
                List.of(tool(schema, JaTool.ApprovalRequirement.TRUSTED_INTERNAL)),
                List.of(), mcp, Map.of());

        assertNotEquals(required, trusted);
    }

    /** 相同本地名称与 Schema 若绑定到不同 MCP 路由，也必须形成不同的恢复目录身份。 */
    @Test
    void toolCatalogDigestIncludesMcpRouteIdentity() {
        ToolSpec spec = tool(object("type", new JsonText("object"),
                "description", new JsonText("fixture"))).spec();
        McpFixture first = mcpFixture(spec, "server_first", "remote fixture");
        McpFixture second = mcpFixture(spec, "server_second", "remote fixture");

        assertNotEquals(first.snapshot().tools().getFirst().remoteName(),
                first.routes().get(spec.name()).remoteName());
        assertEquals("remote fixture", first.routes().get(spec.name()).remoteName());
        String firstDigest = ConfigurationTurnRuntimeResolver.toolCatalogDigest(
                List.of(), List.of(), first.snapshot(), first.routes());
        String secondDigest = ConfigurationTurnRuntimeResolver.toolCatalogDigest(
                List.of(), List.of(), second.snapshot(), second.routes());

        assertNotEquals(firstDigest, secondDigest);
    }

    /** 内置、能力与 MCP 共用一个模型命名空间，任意两类冲突都必须在 Provider 请求前失败。 */
    @Test
    void toolCatalogDigestRejectsDuplicateNamesAcrossSources() {
        JsonObject schema = object("type", new JsonText("object"), "description", new JsonText("fixture"));
        JaTool builtin = tool(schema);
        JaTool.Definition capability = contribution(tool(schema));
        McpGateway.McpSnapshot emptyMcp = new McpGateway.McpSnapshot(
                "mcp-empty", List.of(), Instant.parse("2026-09-01T00:00:00Z"));
        McpFixture duplicateMcp = mcpFixture(builtin.spec(), "server_fixture", "remote fixture");

        assertThrows(IllegalArgumentException.class, () -> ConfigurationTurnRuntimeResolver.toolCatalogDigest(
                List.of(builtin), List.of(capability), emptyMcp, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> ConfigurationTurnRuntimeResolver.toolCatalogDigest(
                List.of(builtin), List.of(), duplicateMcp.snapshot(), duplicateMcp.routes()));
        assertThrows(IllegalArgumentException.class, () -> ConfigurationTurnRuntimeResolver.toolCatalogDigest(
                List.of(), List.of(capability), duplicateMcp.snapshot(), duplicateMcp.routes()));
    }

    /** Child 请求必须拒绝目录或 MCP 身份漂移，不能因 Tool 同名就越过父请求冻结上限。 */
    @Test
    void childCapabilityCeilingRejectsCatalogDrift() {
        ThreadPreferences preferences = new ThreadPreferences("provider_test", "model_test", "medium",
                AccessMode.APPROVAL_REQUIRED,
                io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode.DEFAULT,
                ThreadPreferences.TitleSource.MANUAL);
        JsonObject ceiling = capabilityCeiling(preferences, "cfg_parent",
                "a".repeat(64), "mcp_parent", java.util.Set.of("skill_review"));

        assertThrows(io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeResolver.RuntimeMismatchException.class,
                () -> ConfigurationTurnRuntimeResolver.validateInheritedCeiling(
                        Optional.of(ceiling), preferences, "b".repeat(64), "mcp_parent"));
        assertThrows(io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeResolver.RuntimeMismatchException.class,
                () -> ConfigurationTurnRuntimeResolver.validateInheritedCeiling(
                        Optional.of(ceiling), preferences, "a".repeat(64), "mcp_changed"));
    }

    /** 父 Turn 创建后新增的 Skill 必须从 Child 目录移除，而父允许的稳定 ID 与描述继续保留。 */
    @Test
    void childCapabilityCeilingFiltersSkillsAddedLater() {
        ThreadPreferences preferences = preferences(AccessMode.FULL_ACCESS);
        JsonObject ceiling = capabilityCeiling(preferences, "cfg_parent",
                "a".repeat(64), "mcp_parent", Set.of("ja:review"));
        SkillFixture fixture = skills("review", "deploy");
        ConfigurationTurnRuntimeResolver.SkillResolution current = new ConfigurationTurnRuntimeResolver.SkillResolution(
                fixture.catalog(), Map.of("ja:review", "review", "ja:deploy", "deploy"));

        ConfigurationTurnRuntimeResolver.SkillResolution restricted =
                ConfigurationTurnRuntimeResolver.restrictSkills(fixture.service(), current, Optional.of(ceiling));

        assertEquals(Map.of("ja:review", "review"), restricted.skillNamesById());
        assertEquals(List.of("review"), restricted.catalog().skills().stream()
                .map(SkillDescriptor::name).toList());
        assertDoesNotThrow(() -> ConfigurationTurnRuntimeResolver.validateInheritedCeiling(
                Optional.of(ceiling), preferences, "a".repeat(64), "mcp_parent"));
    }

    /** 父允许的 Skill 若在当前代际消失属于合法收窄，Child 以剩余交集继续运行。 */
    @Test
    void childCapabilityCeilingAllowsMissingInheritedSkill() {
        ThreadPreferences preferences = preferences(AccessMode.FULL_ACCESS);
        JsonObject ceiling = capabilityCeiling(preferences, "cfg_parent",
                "a".repeat(64), "mcp_parent", Set.of("ja:review"));
        SkillFixture fixture = skills();
        ConfigurationTurnRuntimeResolver.SkillResolution current = new ConfigurationTurnRuntimeResolver.SkillResolution(
                fixture.catalog(), Map.of());

        ConfigurationTurnRuntimeResolver.SkillResolution restricted =
                ConfigurationTurnRuntimeResolver.restrictSkills(fixture.service(), current, Optional.of(ceiling));

        assertEquals(Map.of(), restricted.skillNamesById());
        assertEquals(List.of(), restricted.catalog().skills());
        assertDoesNotThrow(() -> ConfigurationTurnRuntimeResolver.validateInheritedCeiling(
                Optional.of(ceiling), preferences, "a".repeat(64), "mcp_parent"));
    }

    /** AccessMode 是能力上限：approval_required 禁止提权，full_access 允许 Child 主动收窄。 */
    @Test
    void childCapabilityCeilingAllowsOnlyAccessNarrowing() {
        ThreadPreferences full = preferences(AccessMode.FULL_ACCESS);
        ThreadPreferences approval = preferences(AccessMode.APPROVAL_REQUIRED);
        JsonObject approvalCeiling = capabilityCeiling(approval, "cfg_parent",
                "a".repeat(64), "mcp_parent", Set.of());
        JsonObject fullCeiling = capabilityCeiling(full, "cfg_parent",
                "a".repeat(64), "mcp_parent", Set.of());

        assertThrows(io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeResolver.RuntimeMismatchException.class,
                () -> ConfigurationTurnRuntimeResolver.validateInheritedCeiling(
                        Optional.of(approvalCeiling), full, "a".repeat(64), "mcp_parent"));
        assertDoesNotThrow(() -> ConfigurationTurnRuntimeResolver.validateInheritedCeiling(
                Optional.of(fullCeiling), approval, "a".repeat(64), "mcp_parent"));
    }

    /** Side Task 使用独立 versioned access ceiling；它不冻结目录，但仍拒绝提权与额外字段。 */
    @Test
    void sideTaskAccessCeilingIsStrictAndDoesNotFilterCatalogs() {
        JsonObject ceiling = JsonObjects.builder().putText("version", "task_access_v1")
                .putText("accessMode", "approval_required").build();
        SkillFixture fixture = skills("review");
        ConfigurationTurnRuntimeResolver.SkillResolution current = new ConfigurationTurnRuntimeResolver.SkillResolution(
                fixture.catalog(), Map.of("ja:review", "review"));

        assertEquals(current, ConfigurationTurnRuntimeResolver.restrictSkills(
                fixture.service(), current, Optional.of(ceiling)));
        assertDoesNotThrow(() -> ConfigurationTurnRuntimeResolver.validateInheritedCeiling(
                Optional.of(ceiling), preferences(AccessMode.APPROVAL_REQUIRED),
                "a".repeat(64), "mcp_current"));
        assertThrows(io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeResolver.RuntimeMismatchException.class,
                () -> ConfigurationTurnRuntimeResolver.validateInheritedCeiling(
                        Optional.of(ceiling), preferences(AccessMode.FULL_ACCESS),
                        "a".repeat(64), "mcp_current"));

        JsonObject extraField = JsonObjects.builder().putText("version", "task_access_v1")
                .putText("accessMode", "approval_required").putText("skillIds", "not-allowed").build();
        assertThrows(io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeResolver.RuntimeMismatchException.class,
                () -> ConfigurationTurnRuntimeResolver.restrictSkills(
                        fixture.service(), current, Optional.of(extraField)));
    }

    /** 未知 ceiling 版本不能回退到 access-only 或完整能力模型。 */
    @Test
    void rejectsUnknownTaskCeilingVersion() {
        JsonObject unknown = JsonObjects.builder().putText("version", "task_future_v2")
                .putText("accessMode", "approval_required").build();

        assertThrows(io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeResolver.RuntimeMismatchException.class,
                () -> ConfigurationTurnRuntimeResolver.validateInheritedCeiling(
                        Optional.of(unknown), preferences(AccessMode.APPROVAL_REQUIRED),
                        "a".repeat(64), "mcp_current"));
    }

    /** Side Task 的独立用户权限只验证 seed 结构；approval/full 两个方向都由当前请求偏好决定。 */
    @Test
    void sideTaskAccessPreferenceIsNotRestrictedByHistoricalCeiling() {
        JsonObject approvalSeed = JsonObjects.builder().putText("version", "task_access_v1")
                .putText("accessMode", "approval_required").build();
        JsonObject fullSeed = JsonObjects.builder().putText("version", "task_access_v1")
                .putText("accessMode", "full_access").build();

        assertDoesNotThrow(() -> ConfigurationTurnRuntimeResolver.validateInheritedCeiling(
                Optional.of(approvalSeed), preferences(AccessMode.FULL_ACCESS),
                "a".repeat(64), "mcp_current", TaskCapabilityCeilingPort.Kind.SIDE_TASK));
        assertDoesNotThrow(() -> ConfigurationTurnRuntimeResolver.validateInheritedCeiling(
                Optional.of(fullSeed), preferences(AccessMode.APPROVAL_REQUIRED),
                "a".repeat(64), "mcp_current", TaskCapabilityCeilingPort.Kind.SIDE_TASK));
        assertThrows(io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeResolver.RuntimeMismatchException.class,
                () -> ConfigurationTurnRuntimeResolver.validateInheritedCeiling(
                        Optional.empty(), preferences(AccessMode.FULL_ACCESS),
                        "a".repeat(64), "mcp_current", TaskCapabilityCeilingPort.Kind.SIDE_TASK));
    }

    /** Subagent 的完整能力 ceiling 仍禁止 approval 到 full 的提权，且不能借 access-only seed 绕过模型边界。 */
    @Test
    void subagentCapabilityCeilingRemainsStrict() {
        ThreadPreferences baseline = preferences(AccessMode.FULL_ACCESS);
        JsonObject fullCeiling = capabilityCeiling(baseline, "cfg_parent",
                "a".repeat(64), "mcp_parent", Set.of());
        assertDoesNotThrow(() -> ConfigurationTurnRuntimeResolver.validateInheritedCeiling(
                Optional.of(fullCeiling), preferences(AccessMode.APPROVAL_REQUIRED),
                "a".repeat(64), "mcp_parent", TaskCapabilityCeilingPort.Kind.SUBAGENT));

        JsonObject approvalCeiling = capabilityCeiling(preferences(AccessMode.APPROVAL_REQUIRED), "cfg_parent",
                "a".repeat(64), "mcp_parent", Set.of());
        assertThrows(io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeResolver.RuntimeMismatchException.class,
                () -> ConfigurationTurnRuntimeResolver.validateInheritedCeiling(
                        Optional.of(approvalCeiling), baseline, "a".repeat(64), "mcp_parent",
                        TaskCapabilityCeilingPort.Kind.SUBAGENT));

        JsonObject accessOnly = JsonObjects.builder().putText("version", "task_access_v1")
                .putText("accessMode", "full_access").build();
        assertThrows(io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeResolver.RuntimeMismatchException.class,
                () -> ConfigurationTurnRuntimeResolver.validateInheritedCeiling(
                        Optional.of(accessOnly), baseline, "a".repeat(64), "mcp_parent",
                        TaskCapabilityCeilingPort.Kind.SUBAGENT));
    }

    /** Provider、Model 与 reasoning 无自然偏序，任一漂移都必须拒绝。 */
    @Test
    void childCapabilityCeilingRejectsRuntimeIdentityDrift() {
        ThreadPreferences baseline = preferences(AccessMode.FULL_ACCESS);
        JsonObject ceiling = capabilityCeiling(baseline, "cfg_parent",
                "a".repeat(64), "mcp_parent", Set.of());
        List<ThreadPreferences> changed = List.of(
                new ThreadPreferences("provider_other", "model_test", "medium", AccessMode.FULL_ACCESS,
                        io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode.DEFAULT,
                        ThreadPreferences.TitleSource.MANUAL),
                new ThreadPreferences("provider_test", "model_other", "medium", AccessMode.FULL_ACCESS,
                        io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode.DEFAULT,
                        ThreadPreferences.TitleSource.MANUAL),
                new ThreadPreferences("provider_test", "model_test", "high", AccessMode.FULL_ACCESS,
                        io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode.DEFAULT,
                        ThreadPreferences.TitleSource.MANUAL));

        for (ThreadPreferences preferences : changed) {
            assertThrows(io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeResolver.RuntimeMismatchException.class,
                    () -> ConfigurationTurnRuntimeResolver.validateInheritedCeiling(
                            Optional.of(ceiling), preferences, "a".repeat(64), "mcp_parent",
                            TaskCapabilityCeilingPort.Kind.SUBAGENT));
        }
        JsonObject nextGeneration = capabilityCeiling(baseline, "cfg_next",
                "a".repeat(64), "mcp_parent", Set.of());
        assertDoesNotThrow(() -> ConfigurationTurnRuntimeResolver.validateInheritedCeiling(
                Optional.of(nextGeneration), baseline, "a".repeat(64), "mcp_parent",
                TaskCapabilityCeilingPort.Kind.SUBAGENT));
    }

    /** 构造固定身份偏好，让 ceiling 测试只改变被声明的能力维度。 */
    private static ThreadPreferences preferences(AccessMode accessMode) {
        return new ThreadPreferences("provider_test", "model_test", "medium", accessMode,
                io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode.DEFAULT,
                ThreadPreferences.TitleSource.MANUAL);
    }

    /**
     * 自定义供应商可分别选择三种 API，且每条配置只读取自己的 credentialId；
     * 该测试禁止通过供应商名称猜测协议或复用其它供应商的 API key。
     */
    @Test
    void deepSeekProtocolsUseTheirOwnCredentialWithoutFallback() {
        Map<String, String> secrets = Map.of(
                "cred_deepseek_anthropic", "secret-anthropic",
                "cred_deepseek_responses", "secret-responses",
                "cred_deepseek_chat", "secret-chat");
        List<ConfigurationGenerationSnapshot.Api> configuredApis = List.of(
                ConfigurationGenerationSnapshot.Api.ANTHROPIC_MESSAGES,
                ConfigurationGenerationSnapshot.Api.OPENAI_RESPONSES,
                ConfigurationGenerationSnapshot.Api.OPENAI_CHAT_COMPLETIONS);
        List<ModelPort.Api> expectedApis = List.of(
                ModelPort.Api.ANTHROPIC_MESSAGES,
                ModelPort.Api.OPENAI_RESPONSES,
                ModelPort.Api.OPENAI_CHAT_COMPLETIONS);
        List<String> credentialIds = List.of(
                "cred_deepseek_anthropic", "cred_deepseek_responses", "cred_deepseek_chat");
        ConfigurationGenerationSnapshot.Model model = model();
        ConfigurationGenerationPort.Lease lease = lease(secrets);

        for (int ordinal = 0; ordinal < configuredApis.size(); ordinal++) {
            String credentialId = credentialIds.get(ordinal);
            ConfigurationGenerationSnapshot.Provider provider = provider(
                    configuredApis.get(ordinal), credentialId, model);
            ModelPort.ModelConfiguration resolved = ConfigurationTurnRuntimeResolver.model(
                    provider, model, null, lease, 4_096);

            assertEquals(expectedApis.get(ordinal), resolved.api());
            assertEquals(secrets.get(credentialId), resolved.apiKey());
        }
    }

    /** 精确匹配的上游能力收紧单次输出，同时把未使用的窗口空间留给输入。 */
    @Test
    void appliesExactKnownOutputCapabilityAndReservesRemainingContext() {
        ConfigurationGenerationSnapshot.Model known = model("deepseek-flash", 1_000_000, 900_000);
        ConfigurationGenerationSnapshot.Provider official = provider(
                ConfigurationGenerationSnapshot.Api.OPENAI_CHAT_COMPLETIONS, "cred_deepseek_chat", known);
        ConfigurationGenerationSnapshot.Model unknown = model("deepseek-flash-custom", 1_000_000, 900_000);
        ConfigurationGenerationSnapshot.Provider compatibleGateway = new ConfigurationGenerationSnapshot.Provider(
                "provider_gateway", "Gateway", ConfigurationGenerationSnapshot.Api.OPENAI_CHAT_COMPLETIONS,
                URI.create("https://gateway.example/v1"), "cred_deepseek_chat",
                official.networkTimeouts(), official.agentDefaults(), List.of(unknown));
        ConfigurationGenerationSnapshot.Provider otherApi = new ConfigurationGenerationSnapshot.Provider(
                "provider_deepseek_responses", "DeepSeek Responses",
                ConfigurationGenerationSnapshot.Api.OPENAI_RESPONSES, official.baseUrl(), "cred_deepseek_chat",
                official.networkTimeouts(), official.agentDefaults(), List.of(known));

        io.github.kongweiguang.ja.agent.domain.turn.TurnLimits knownLimits =
                ConfigurationTurnRuntimeResolver.limits(official, known);
        io.github.kongweiguang.ja.agent.domain.turn.TurnLimits unknownLimits =
                ConfigurationTurnRuntimeResolver.limits(compatibleGateway, unknown);
        io.github.kongweiguang.ja.agent.domain.turn.TurnLimits otherApiLimits =
                ConfigurationTurnRuntimeResolver.limits(otherApi, known);
        io.github.kongweiguang.ja.agent.domain.ContextBudget knownContextBudget =
                ConfigurationTurnRuntimeResolver.contextBudget(
                known.capabilities(), official.agentDefaults().context(), knownLimits.maxOutputTokens());

        assertEquals(384_000, knownLimits.maxOutputTokens());
        assertEquals(616_000, knownLimits.maxInputTokens());
        assertEquals(900_000, unknownLimits.maxOutputTokens());
        assertEquals(100_000, unknownLimits.maxInputTokens());
        assertEquals(900_000, otherApiLimits.maxOutputTokens());
        assertEquals(616_000, knownContextBudget.sendCeilingTokens());
    }

    /** Thread 偏好选择“跟随模型默认”时，Provider generation 仍必须冻结模型声明的上游档位。 */
    @Test
    void followsModelDefaultReasoningWhenThreadPreferenceIsNull() {
        ConfigurationGenerationSnapshot.Model model = new ConfigurationGenerationSnapshot.Model(
                "model_reasoning", "Reasoning", "gpt-reasoning",
                new ConfigurationGenerationSnapshot.Capabilities(
                        128_000, 8_192, List.of(ConfigurationGenerationSnapshot.InputModality.TEXT), false),
                Map.of(ConfigurationGenerationSnapshot.ReasoningLevel.MEDIUM, "medium"),
                ConfigurationGenerationSnapshot.ReasoningLevel.MEDIUM);
        ConfigurationGenerationSnapshot.Provider provider = provider(
                ConfigurationGenerationSnapshot.Api.OPENAI_RESPONSES, "cred_reasoning", model);

        ModelPort.ModelConfiguration resolved = ConfigurationTurnRuntimeResolver.model(
                provider, model, null, lease(Map.of("cred_reasoning", "secret-reasoning")), 4_096);

        assertEquals("medium", resolved.generation().reasoningLevel());
    }

    /** 显式选择 max 必须覆盖模型默认 medium，确保对话框当前档位进入真实 Provider 请求。 */
    @Test
    void explicitReasoningOverridesModelDefault() {
        ConfigurationGenerationSnapshot.Model model = new ConfigurationGenerationSnapshot.Model(
                "model_reasoning", "Reasoning", "gpt-reasoning",
                new ConfigurationGenerationSnapshot.Capabilities(
                        128_000, 8_192, List.of(ConfigurationGenerationSnapshot.InputModality.TEXT), false),
                Map.of(ConfigurationGenerationSnapshot.ReasoningLevel.MEDIUM, "medium",
                        ConfigurationGenerationSnapshot.ReasoningLevel.MAX, "max"),
                ConfigurationGenerationSnapshot.ReasoningLevel.MEDIUM);
        ConfigurationGenerationSnapshot.Provider provider = provider(
                ConfigurationGenerationSnapshot.Api.OPENAI_RESPONSES, "cred_reasoning", model);

        ModelPort.ModelConfiguration resolved = ConfigurationTurnRuntimeResolver.model(
                provider, model, "max", lease(Map.of("cred_reasoning", "secret-reasoning")), 4_096);

        assertEquals("max", resolved.generation().reasoningLevel());
    }

    /** 构造 text-only 模型，隔离 Provider/API/凭据路由行为。 */
    private static ConfigurationGenerationSnapshot.Model model() {
        return model("deepseek-chat", 128_000, 8_192);
    }

    /** 构造上游标识可变的能力样例，确保额度不依赖展示名称。 */
    private static ConfigurationGenerationSnapshot.Model model(String upstreamModel, int context, int output) {
        return new ConfigurationGenerationSnapshot.Model(
                "model_deepseek", "DeepSeek", upstreamModel,
                new ConfigurationGenerationSnapshot.Capabilities(
                        context, output, List.of(ConfigurationGenerationSnapshot.InputModality.TEXT), false),
                Map.of(), null);
    }

    /** 为单个协议构造独立凭据引用，不共享 Provider 连接身份。 */
    private static ConfigurationGenerationSnapshot.Provider provider(
            ConfigurationGenerationSnapshot.Api api, String credentialId,
            ConfigurationGenerationSnapshot.Model model) {
        return new ConfigurationGenerationSnapshot.Provider(
                "provider_deepseek_" + api.name().toLowerCase(java.util.Locale.ROOT), "DeepSeek",
                api,
                URI.create("https://api.deepseek.com"), credentialId,
                new ConfigurationGenerationSnapshot.NetworkTimeouts(
                        Duration.ofSeconds(5), Duration.ofMinutes(2)),
                new ConfigurationGenerationSnapshot.AgentDefaults(
                        new ConfigurationGenerationSnapshot.Context(true)),
                List.of(model));
    }

    /** 只按传入 credentialId 返回对应 Secret，未知引用立即失败以捕获 key 混用。 */
    private static ConfigurationGenerationPort.Lease lease(Map<String, String> secrets) {
        return new ConfigurationGenerationPort.Lease() {
            /** 模型配置只读取代际标识，不取得完整配置快照。 */
            @Override public String generationId() { return "cfg_fixture"; }

            /** 本聚焦测试不允许模型映射回读配置目录。 */
            @Override public ConfigurationGenerationSnapshot snapshot() {
                throw new AssertionError("unexpected snapshot lookup");
            }

            /** 凭据查找必须使用当前 Provider 保存的精确引用。 */
            @Override public String secretFor(String credentialId) {
                String secret = secrets.get(credentialId);
                if (secret == null) {
                    throw new AssertionError("unexpected credential lookup: " + credentialId);
                }
                return secret;
            }

            /** fake 不持有真实 Secret 资源。 */
            @Override public void close() { }
        };
    }

    /** LinkedHashMap 显式构造相反顺序，避免测试自身依赖 Map.of 的进程随机遍历。 */
    private static JsonObject object(String firstKey, JsonText firstValue,
                                     String secondKey, JsonText secondValue) {
        LinkedHashMap<String, io.github.kongweiguang.ja.agent.domain.json.JsonValue> members =
                new LinkedHashMap<>();
        members.put(firstKey, firstValue);
        members.put(secondKey, secondValue);
        return new JsonObject(members);
    }

    /** 只为摘要测试提供不可执行 Tool；执行端若被调用即说明测试越过了目录边界。 */
    private static JaTool tool(JsonObject schema) {
        return tool(schema, ToolSideEffect.EXTERNAL, JaTool.WorkspaceMutationMode.UNOBSERVABLE);
    }

    /** 允许摘要测试分别改变两个正交安全维度，执行实现保持不可达。 */
    private static JaTool tool(JsonObject schema, ToolSideEffect sideEffect,
                               JaTool.WorkspaceMutationMode workspaceMutationMode) {
        return tool(schema, sideEffect, workspaceMutationMode, JaTool.ApprovalRequirement.USER_REQUIRED);
    }

    /** 摘要测试可单独切换审批要求，避免把其它 Tool 安全字段误作为变量。 */
    private static JaTool tool(JsonObject schema, JaTool.ApprovalRequirement approvalRequirement) {
        return tool(schema, ToolSideEffect.READ_ONLY, JaTool.WorkspaceMutationMode.NONE, approvalRequirement);
    }

    /** 允许摘要测试分别改变副作用、工作区可观察性和审批要求三个正交安全维度。 */
    private static JaTool tool(JsonObject schema, ToolSideEffect sideEffect,
                               JaTool.WorkspaceMutationMode workspaceMutationMode,
                               JaTool.ApprovalRequirement approvalRequirement) {
        return tool("fixture_tool", schema, sideEffect, workspaceMutationMode, approvalRequirement);
    }

    /** 生成可替换名称的测试工具，供用户问答隐藏目录身份独立验证。 */
    private static JaTool tool(String name, JsonObject schema, ToolSideEffect sideEffect,
                               JaTool.WorkspaceMutationMode workspaceMutationMode,
                               JaTool.ApprovalRequirement approvalRequirement) {
        return new JaTool() {
            /** 返回当前测试冻结的 Schema，名称和描述保持不变以隔离对象键顺序变量。 */
            @Override
            public ToolSpec spec() {
                return new ToolSpec(name, "fixture description", schema);
            }

            /** 测试显式声明副作用，确保摘要不再依赖历史名称白名单。 */
            @Override
            public ToolSideEffect sideEffect() {
                return sideEffect;
            }

            /** 测试显式声明工作区可观察性，验证其与副作用分类分别进入目录身份。 */
            @Override
            public WorkspaceMutationMode workspaceMutationMode() {
                return workspaceMutationMode;
            }

            /** 测试显式声明审批边界，验证其进入恢复目录身份。 */
            @Override
            public ApprovalRequirement approvalRequirement() {
                return approvalRequirement;
            }

            /** 摘要计算不得触发 Tool 执行；若越界则立即让回归失败。 */
            @Override
            public java.util.concurrent.CompletionStage<JaTool.ToolResult> execute(
                    AgentTool.Invocation invocation,
                    JaTool.ExecutionContext context,
                    io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken cancellationToken) {
                throw new AssertionError("digest test must not execute tools");
            }
        };
    }

    /** 将可执行测试 Tool 投影为静态定义，目录摘要不调用绑定函数。 */
    private static JaTool.Definition contribution(JaTool tool) {
        AgentTool.Invocation metadata = new AgentTool.Invocation("call_" + "0".repeat(64),
                tool.spec().name(), JsonObjects.builder().build(), 0);
        return new JaTool.Definition(tool.spec(), tool.sideEffect(metadata),
                tool.workspaceMutationMode(metadata), tool.bindingDescriptor(metadata),
                tool.planAccess(metadata), tool.approvalRequirement(metadata), ignored -> tool);
    }

    /**
     * 通过生产 MCP 目录 owner 同时生成 catalog revision、Jackson schema hash 与规范路由，
     * Resolver 测试只负责消费这些不透明身份，不复制其散列算法。
     */
    private static McpFixture mcpFixture(ToolSpec spec, String serverId, String remoteName) {
        ObjectMapper mapper = new ObjectMapper();
        McpServerDefinition definition = McpServerDefinition.stdio(serverId, List.of("fixture"),
                Path.of(".").toAbsolutePath().normalize(), Map.of(), List.of("2025-06-18"));
        String encodedRemote = "r-" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(remoteName.getBytes(StandardCharsets.UTF_8));
        McpGateway.McpSnapshot snapshot = McpRuntime.catalogSnapshot(
                List.of(new McpGateway.McpTool(serverId, encodedRemote, spec)),
                List.of(definition), mapper, Instant.parse("2026-09-01T00:00:00Z"));
        return new McpFixture(snapshot,
                McpToolCatalog.routeIdentities(snapshot, Map.of(serverId, definition), mapper));
    }

    /**
     * 同源快照与路由始终成对传给 Resolver，避免测试构造现实中不存在的混合代际。
     *
     * @param snapshot 快照
     * @param routes 路由列表
     */
    private record McpFixture(McpGateway.McpSnapshot snapshot,
                              Map<String, McpGateway.RouteIdentity> routes) {
    }

    /** 测试通过正式 Task ceiling 端口创建 seed，不再调用已删除的静态领域捷径。 */
    private static JsonObject capabilityCeiling(ThreadPreferences preferences, String configGeneration,
                                                String toolDigest, String mcpRevision, Set<String> skillIds) {
        return new TaskAgentToolGateway(threadId -> java.util.Optional.of(
                io.github.kongweiguang.ja.conversation.domain.model.SubagentPolicy.defaultPolicy()),
                new JaAdmissionParticipants()).create(
                preferences, configGeneration,
                new JaToolCatalogIdentity(toolDigest, mcpRevision, skillIds));
    }
}
