// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.runtime.McpRuntime;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.runtime.McpToolCatalog;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpServerDefinition;
import io.github.kongweiguang.ja.catalog.port.out.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationGenerationSnapshot;
import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.conversation.port.out.AgentCapability;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.McpGateway;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.conversation.port.out.SkillCatalog;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.task.adapter.in.tools.TaskAgentToolGateway;
import io.github.kongweiguang.ja.conversation.port.out.TaskCapabilityCeilingPort;
import io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeRequest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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

/** 验证冻结配置身份与 Runtime 恢复指纹在适配层保持各自的稳定语义。 */
final class ConfigurationTurnRuntimeResolverTest {
    /**
     * clarification 准入必须由 Turn 来源和只读规划策略共同决定；Thread 的 PLAN 偏好不能让 Goal
     * 续跑误入规划，也不能让 Plan-owned 执行在 DEFAULT 模式下丢失必需的用户决策入口。
     */
    @Test
    void clarificationAvailabilityUsesTurnOriginAndPlanningPolicy() {
        assertTrue(clarification(TurnOrigin.PLAN_EXECUTION, CollaborationMode.DEFAULT, false));
        assertTrue(clarification(TurnOrigin.USER, CollaborationMode.PLAN, false));
        assertTrue(clarification(TurnOrigin.CHILD_TASK, CollaborationMode.PLAN, false));
        assertFalse(clarification(TurnOrigin.USER, CollaborationMode.DEFAULT, false));
        assertFalse(clarification(TurnOrigin.GOAL_CONTINUATION, CollaborationMode.PLAN, false));
        assertTrue(clarification(TurnOrigin.GOAL_CONTINUATION, CollaborationMode.DEFAULT, true));
    }

    /** 构造最小运行请求，隔离 clarification 准入判断与配置读取、Tool 目录和文件系统副作用。 */
    private static boolean clarification(TurnOrigin origin, CollaborationMode mode, boolean configured) {
        return ConfigurationTurnRuntimeResolver.isClarificationEnabled(new TurnRuntimeRequest(
                "thr_clarification", "turn_clarification", Path.of(".").toAbsolutePath(), "ws_clarification",
                "provider_clarification", "model_clarification", "medium", AccessMode.FULL_ACCESS, mode,
                origin, Duration.ofMinutes(1), Instant.parse("2026-09-10T12:00:00Z")), configured);
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
     * 消息引用必须保留配置中的稳定 ID，并只发布已启用且实际发现的交集；
     * 发现目录里的同名元数据不能反向生成 ID，禁用项也不能进入本 Turn。
     */
    @Test
    void skillIdentityMapUsesFrozenIdsForEnabledDiscoveredSkillsOnly() {
        List<ConfigurationGenerationSnapshot.Skill> configured = List.of(
                new ConfigurationGenerationSnapshot.Skill(
                        "skill_review", "review", "user", true, "Review changes"),
                new ConfigurationGenerationSnapshot.Skill(
                        "skill_disabled", "disabled", "user", false, "Disabled skill"),
                new ConfigurationGenerationSnapshot.Skill(
                        "skill_missing", "missing", "workspace", true, "Missing skill"));
        SkillCatalog.Catalog discovered = new SkillCatalog.Catalog(List.of(
                new SkillCatalog.SkillDescriptor(
                        "review", "Review changes", SkillCatalog.Source.JA_USER),
                new SkillCatalog.SkillDescriptor(
                        "disabled", "Disabled skill", SkillCatalog.Source.JA_USER),
                new SkillCatalog.SkillDescriptor(
                        "unconfigured", "Unconfigured skill", SkillCatalog.Source.WORKSPACE)));

        assertEquals(Map.of("skill_review", "review"),
                ConfigurationTurnRuntimeResolver.skillNamesById(configured, discovered));
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

    /** 子任务隐藏用户问答入口属于角色约束，不应被误判为外部执行能力发生漂移。 */
    @Test
    void inheritedExecutionDigestDoesNotDependOnUserQuestionVisibility() {
        JsonObject schema = object("type", new JsonText("object"), "description", new JsonText("fixture"));
        ToolSpec question = new ToolSpec("request_user_input", "Ask the user", schema);
        AgentCapability.ToolContribution contribution = new AgentCapability.ToolContribution(question,
                ToolSideEffect.EXTERNAL, AgentTool.WorkspaceMutationMode.NONE,
                AgentTool.builtinBindingDescriptor(question, ToolSideEffect.EXTERNAL, AgentTool.WorkspaceMutationMode.NONE),
                ignored -> { throw new AssertionError("digest must not bind tools"); });
        McpGateway.McpSnapshot mcp = new McpGateway.McpSnapshot("mcp-empty", List.of(), Instant.EPOCH);
        assertEquals(ConfigurationTurnRuntimeResolver.toolCatalogDigest(List.of(tool(schema)), List.of(), mcp, Map.of()),
                ConfigurationTurnRuntimeResolver.toolCatalogDigest(List.of(tool(schema)), List.of(contribution), mcp, Map.of()));
    }

    /** 目录摘要必须直接覆盖安全声明，不能让相同 Schema 的只读与外部副作用 Tool 共享身份。 */
    @Test
    void toolCatalogDigestIncludesSideEffectAndWorkspaceMutationMode() {
        JsonObject schema = object("type", new JsonText("object"), "description", new JsonText("fixture"));
        McpGateway.McpSnapshot mcp = new McpGateway.McpSnapshot(
                "mcp-empty", List.of(), Instant.parse("2026-09-01T00:00:00Z"));

        String readOnly = ConfigurationTurnRuntimeResolver.toolCatalogDigest(
                List.of(tool(schema, ToolSideEffect.READ_ONLY, AgentTool.WorkspaceMutationMode.NONE)),
                List.of(), mcp, Map.of());
        String external = ConfigurationTurnRuntimeResolver.toolCatalogDigest(
                List.of(tool(schema, ToolSideEffect.EXTERNAL, AgentTool.WorkspaceMutationMode.NONE)),
                List.of(), mcp, Map.of());
        String unobservable = ConfigurationTurnRuntimeResolver.toolCatalogDigest(
                List.of(tool(schema, ToolSideEffect.EXTERNAL, AgentTool.WorkspaceMutationMode.UNOBSERVABLE)),
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
                List.of(tool(schema, AgentTool.ApprovalRequirement.TRUSTED_INTERNAL)),
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
        AgentTool builtin = tool(schema);
        AgentCapability.ToolContribution capability = contribution(tool(schema));
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
                io.github.kongweiguang.ja.conversation.domain.CollaborationMode.DEFAULT,
                ThreadPreferences.TitleSource.MANUAL);
        JsonObject ceiling = capabilityCeiling(preferences, "cfg_parent",
                "a".repeat(64), "mcp_parent", java.util.Set.of("skill_review"));

        assertThrows(io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeResolver.RuntimeMismatchException.class,
                () -> ConfigurationTurnRuntimeResolver.validateInheritedCeiling(
                        Optional.of(ceiling), preferences, "b".repeat(64), "mcp_parent"));
        assertThrows(io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeResolver.RuntimeMismatchException.class,
                () -> ConfigurationTurnRuntimeResolver.validateInheritedCeiling(
                        Optional.of(ceiling), preferences, "a".repeat(64), "mcp_changed"));
    }

    /** 父 Turn 创建后新增的 Skill 必须从 Child 目录移除，而父允许的稳定 ID 与描述继续保留。 */
    @Test
    void childCapabilityCeilingFiltersSkillsAddedLater() {
        ThreadPreferences preferences = preferences(AccessMode.FULL_ACCESS);
        JsonObject ceiling = capabilityCeiling(preferences, "cfg_parent",
                "a".repeat(64), "mcp_parent", Set.of("skill_review"));
        ConfigurationTurnRuntimeResolver.SkillResolution current = new ConfigurationTurnRuntimeResolver.SkillResolution(
                new SkillCatalog.Catalog(List.of(
                        new SkillCatalog.SkillDescriptor("review", "Review changes", SkillCatalog.Source.JA_USER),
                        new SkillCatalog.SkillDescriptor("deploy", "Deploy changes", SkillCatalog.Source.JA_USER))),
                Map.of("skill_review", "review", "skill_deploy", "deploy"));

        ConfigurationTurnRuntimeResolver.SkillResolution restricted =
                ConfigurationTurnRuntimeResolver.restrictSkills(current, Optional.of(ceiling));

        assertEquals(Map.of("skill_review", "review"), restricted.skillNamesById());
        assertEquals(List.of("review"), restricted.catalog().skills().stream()
                .map(SkillCatalog.SkillDescriptor::name).toList());
        assertDoesNotThrow(() -> ConfigurationTurnRuntimeResolver.validateInheritedCeiling(
                Optional.of(ceiling), preferences, "a".repeat(64), "mcp_parent"));
    }

    /** 父允许的 Skill 若在当前代际消失属于合法收窄，Child 以剩余交集继续运行。 */
    @Test
    void childCapabilityCeilingAllowsMissingInheritedSkill() {
        ThreadPreferences preferences = preferences(AccessMode.FULL_ACCESS);
        JsonObject ceiling = capabilityCeiling(preferences, "cfg_parent",
                "a".repeat(64), "mcp_parent", Set.of("skill_review"));
        ConfigurationTurnRuntimeResolver.SkillResolution current = new ConfigurationTurnRuntimeResolver.SkillResolution(
                new SkillCatalog.Catalog(List.of()), Map.of());

        ConfigurationTurnRuntimeResolver.SkillResolution restricted =
                ConfigurationTurnRuntimeResolver.restrictSkills(current, Optional.of(ceiling));

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

        assertThrows(io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeResolver.RuntimeMismatchException.class,
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
        ConfigurationTurnRuntimeResolver.SkillResolution current = new ConfigurationTurnRuntimeResolver.SkillResolution(
                new SkillCatalog.Catalog(List.of(
                        new SkillCatalog.SkillDescriptor("review", "Review changes", SkillCatalog.Source.JA_USER))),
                Map.of("skill_review", "review"));

        assertEquals(current, ConfigurationTurnRuntimeResolver.restrictSkills(current, Optional.of(ceiling)));
        assertDoesNotThrow(() -> ConfigurationTurnRuntimeResolver.validateInheritedCeiling(
                Optional.of(ceiling), preferences(AccessMode.APPROVAL_REQUIRED),
                "a".repeat(64), "mcp_current"));
        assertThrows(io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeResolver.RuntimeMismatchException.class,
                () -> ConfigurationTurnRuntimeResolver.validateInheritedCeiling(
                        Optional.of(ceiling), preferences(AccessMode.FULL_ACCESS),
                        "a".repeat(64), "mcp_current"));

        JsonObject extraField = JsonObjects.builder().putText("version", "task_access_v1")
                .putText("accessMode", "approval_required").putText("skillIds", "not-allowed").build();
        assertThrows(io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeResolver.RuntimeMismatchException.class,
                () -> ConfigurationTurnRuntimeResolver.restrictSkills(current, Optional.of(extraField)));
    }

    /** 未知 ceiling 版本不能回退到 access-only 或完整能力模型。 */
    @Test
    void rejectsUnknownTaskCeilingVersion() {
        JsonObject unknown = JsonObjects.builder().putText("version", "task_future_v2")
                .putText("accessMode", "approval_required").build();

        assertThrows(io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeResolver.RuntimeMismatchException.class,
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
        assertThrows(io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeResolver.RuntimeMismatchException.class,
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
        assertThrows(io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeResolver.RuntimeMismatchException.class,
                () -> ConfigurationTurnRuntimeResolver.validateInheritedCeiling(
                        Optional.of(approvalCeiling), baseline, "a".repeat(64), "mcp_parent",
                        TaskCapabilityCeilingPort.Kind.SUBAGENT));

        JsonObject accessOnly = JsonObjects.builder().putText("version", "task_access_v1")
                .putText("accessMode", "full_access").build();
        assertThrows(io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeResolver.RuntimeMismatchException.class,
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
                        io.github.kongweiguang.ja.conversation.domain.CollaborationMode.DEFAULT,
                        ThreadPreferences.TitleSource.MANUAL),
                new ThreadPreferences("provider_test", "model_other", "medium", AccessMode.FULL_ACCESS,
                        io.github.kongweiguang.ja.conversation.domain.CollaborationMode.DEFAULT,
                        ThreadPreferences.TitleSource.MANUAL),
                new ThreadPreferences("provider_test", "model_test", "high", AccessMode.FULL_ACCESS,
                        io.github.kongweiguang.ja.conversation.domain.CollaborationMode.DEFAULT,
                        ThreadPreferences.TitleSource.MANUAL));

        for (ThreadPreferences preferences : changed) {
            assertThrows(io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeResolver.RuntimeMismatchException.class,
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
                io.github.kongweiguang.ja.conversation.domain.CollaborationMode.DEFAULT,
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

    /** 构造 text-only 模型，隔离 Provider/API/凭据路由行为。 */
    private static ConfigurationGenerationSnapshot.Model model() {
        return new ConfigurationGenerationSnapshot.Model(
                "model_deepseek", "DeepSeek", "deepseek-chat",
                new ConfigurationGenerationSnapshot.Capabilities(
                        128_000, 8_192, List.of(ConfigurationGenerationSnapshot.InputModality.TEXT)),
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
                        new ConfigurationGenerationSnapshot.Context(true),
                        new ConfigurationGenerationSnapshot.TurnLimits(8, 32, Duration.ofMinutes(5))),
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
                if (secret == null) throw new AssertionError("unexpected credential lookup: " + credentialId);
                return secret;
            }

            /** fake 不持有真实 Secret 资源。 */
            @Override public void close() { }
        };
    }

    /** LinkedHashMap 显式构造相反顺序，避免测试自身依赖 Map.of 的进程随机遍历。 */
    private static JsonObject object(String firstKey, JsonText firstValue,
                                     String secondKey, JsonText secondValue) {
        LinkedHashMap<String, io.github.kongweiguang.ja.foundation.json.JsonValue> members =
                new LinkedHashMap<>();
        members.put(firstKey, firstValue);
        members.put(secondKey, secondValue);
        return new JsonObject(members);
    }

    /** 只为摘要测试提供不可执行 Tool；执行端若被调用即说明测试越过了目录边界。 */
    private static AgentTool tool(JsonObject schema) {
        return tool(schema, ToolSideEffect.EXTERNAL, AgentTool.WorkspaceMutationMode.UNOBSERVABLE);
    }

    /** 允许摘要测试分别改变两个正交安全维度，执行实现保持不可达。 */
    private static AgentTool tool(JsonObject schema, ToolSideEffect sideEffect,
                                  AgentTool.WorkspaceMutationMode workspaceMutationMode) {
        return tool(schema, sideEffect, workspaceMutationMode, AgentTool.ApprovalRequirement.USER_REQUIRED);
    }

    /** 摘要测试可单独切换审批要求，避免把其它 Tool 安全字段误作为变量。 */
    private static AgentTool tool(JsonObject schema, AgentTool.ApprovalRequirement approvalRequirement) {
        return tool(schema, ToolSideEffect.READ_ONLY, AgentTool.WorkspaceMutationMode.NONE, approvalRequirement);
    }

    /** 允许摘要测试分别改变副作用、工作区可观察性和审批要求三个正交安全维度。 */
    private static AgentTool tool(JsonObject schema, ToolSideEffect sideEffect,
                                  AgentTool.WorkspaceMutationMode workspaceMutationMode,
                                  AgentTool.ApprovalRequirement approvalRequirement) {
        return new AgentTool() {
            /** 返回当前测试冻结的 Schema，名称和描述保持不变以隔离对象键顺序变量。 */
            @Override
            public ToolSpec spec() {
                return new ToolSpec("fixture_tool", "fixture description", schema);
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
            public java.util.concurrent.CompletionStage<ToolResult> execute(
                    Invocation invocation,
                    ExecutionContext context,
                    io.github.kongweiguang.ja.foundation.concurrent.CancellationToken cancellationToken) {
                throw new AssertionError("digest test must not execute tools");
            }
        };
    }

    /** 将可执行测试 Tool 投影为 prepare 阶段贡献，binder 不参与目录摘要。 */
    private static AgentCapability.ToolContribution contribution(AgentTool tool) {
        return new AgentCapability.ToolContribution(tool.spec(), tool.sideEffect(), tool.workspaceMutationMode(),
                tool.bindingDescriptor(), ignored -> tool);
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

    /** 同源快照与路由始终成对传给 Resolver，避免测试构造现实中不存在的混合代际。 */
    private record McpFixture(McpGateway.McpSnapshot snapshot,
                              Map<String, McpGateway.RouteIdentity> routes) {
    }

    /** 测试通过正式 Task ceiling 端口创建 seed，不再调用已删除的静态领域捷径。 */
    private static JsonObject capabilityCeiling(ThreadPreferences preferences, String configGeneration,
                                                String toolDigest, String mcpRevision, Set<String> skillIds) {
        return new TaskAgentToolGateway(threadId -> java.util.Optional.of(
                io.github.kongweiguang.ja.conversation.domain.SubagentPolicy.defaultPolicy())).create(
                preferences, configGeneration,
                new AgentCapability.CatalogIdentity(toolDigest, mcpRevision, skillIds));
    }
}
