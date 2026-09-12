// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.runtime;

import io.github.kongweiguang.ja.catalog.adapter.out.mcp.generation.GenerationCatalog;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.generation.GenerationTurnMcpSessionFactory;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.session.TurnMcpSessionFactory;
import io.github.kongweiguang.ja.catalog.adapter.out.skills.JaSkillSources;
import io.github.kongweiguang.ja.catalog.port.out.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationGenerationSnapshot;
import io.github.kongweiguang.ja.conversation.adapter.out.tools.BuiltInTools;
import io.github.kongweiguang.ja.conversation.adapter.out.tools.PlanReadOnlyToolCatalog;
import io.github.kongweiguang.ja.conversation.adapter.out.tools.ShellCapability;
import io.github.kongweiguang.ja.conversation.application.capability.AgentCapabilityCatalog;
import io.github.kongweiguang.ja.conversation.application.policy.PlanToolPolicy;
import io.github.kongweiguang.ja.conversation.application.loop.McpAgentTool;
import io.github.kongweiguang.ja.conversation.domain.ContextBudget;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnLimits;
import io.github.kongweiguang.ja.conversation.port.out.AgentCapability;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSession;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSessionFactory;
import io.github.kongweiguang.ja.conversation.port.out.ManagedAttachmentReader;
import io.github.kongweiguang.ja.conversation.port.out.McpGateway;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.conversation.port.out.RuntimeLease;
import io.github.kongweiguang.ja.conversation.port.out.SkillCatalog;
import io.github.kongweiguang.ja.conversation.port.out.TaskCapabilityCeilingPort;
import io.github.kongweiguang.ja.conversation.port.out.TaskCapabilityCeilingPort.Kind;
import io.github.kongweiguang.ja.conversation.port.out.TaskCapabilityCeilingPort.RuntimeIdentity;
import io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeRequest;
import io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeResolver;
import io.github.kongweiguang.ja.conversation.port.out.TurnToolSessionFactory;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonNull;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.foundation.json.JsonValue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.Comparator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 在每次 Provider 请求安全点解析 Provider、MCP、Skills 与预算，并交接短生命周运行时。
 */
public final class ConfigurationTurnRuntimeResolver implements TurnRuntimeResolver {
    private final ConfigurationGenerationPort configurations;
    private final JaSkillSources skills;
    private final Path agentsSkillRoot;
    private final Path jaSkillRoot;
    private final Path jaHome;
    private final ShellCapability shellCapability;
    private final GenerationCatalog generationCatalog;
    private final GenerationTurnMcpSessionFactory mcpSessions;
    private final AgentPromptSessionFactory promptSessions;
    private final ManagedAttachmentReader attachments;
    private final AgentCapabilityCatalog capabilities;
    private final TaskCapabilityCeilingPort taskCeilings;

    /**
     * 注入窄端口和无状态适配器，Resolver 本身不读取配置文件或持有 secret。
     */
    public ConfigurationTurnRuntimeResolver(
            ConfigurationGenerationPort configurations,
            JaSkillSources skills,
            Path agentsSkillRoot,
            Path jaSkillRoot,
            ShellCapability shellCapability,
            GenerationCatalog generationCatalog,
            GenerationTurnMcpSessionFactory mcpSessions,
            AgentPromptSessionFactory promptSessions,
            ManagedAttachmentReader attachments,
            AgentCapabilityCatalog capabilities,
            TaskCapabilityCeilingPort taskCeilings) {
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.skills = Objects.requireNonNull(skills, "skills");
        this.agentsSkillRoot = Objects.requireNonNull(agentsSkillRoot, "agentsSkillRoot")
                .toAbsolutePath().normalize();
        this.jaSkillRoot = Objects.requireNonNull(jaSkillRoot, "jaSkillRoot")
                .toAbsolutePath().normalize();
        this.jaHome = Objects.requireNonNull(this.jaSkillRoot.getParent(), "jaHome");
        this.shellCapability = Objects.requireNonNull(shellCapability, "shellCapability");
        this.generationCatalog = Objects.requireNonNull(generationCatalog, "generationCatalog");
        this.mcpSessions = Objects.requireNonNull(mcpSessions, "mcpSessions");
        this.promptSessions = Objects.requireNonNull(promptSessions, "promptSessions");
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.taskCeilings = Objects.requireNonNull(taskCeilings, "taskCeilings");
    }

    /**
     * 先取得唯一配置租约，再完成全部派生；任一校验或适配失败都会在返回前释放 secret，
     * 只有完整 RuntimeLease 才把释放权转移给 TurnService。权限来自已持久化 Thread 的显式偏好，
     * default_access_mode 只用于创建时默认值；子任务能力上限仍由独立 inherited ceiling 校验。
     */
    @Override
    @SuppressWarnings("PMD.CloseResource")
    public RuntimeLease resolve(TurnRuntimeRequest request) {
        Objects.requireNonNull(request, "request");
        Optional<JsonObject> inheritedCeiling = taskCeilings.read(request.threadId());
        Optional<RuntimeIdentity> taskIdentity = taskCeilings.readIdentity(request.threadId());
        ConfigurationGenerationPort.Lease lease = configurations.acquire(request.workspaceRoot());
        boolean transferred = false;
        try {
            ConfigurationGenerationSnapshot.Provider provider =
                    lease.snapshot().requireProvider(request.providerId());
            ConfigurationGenerationSnapshot.Model selectedModel =
                    lease.snapshot().requireModel(request.providerId(), request.modelId());
            validateReasoning(request.reasoningLevel(), selectedModel);
            TurnLimits limits = limits(request, provider, selectedModel);
            ModelPort.ModelConfiguration model = model(
                    provider, selectedModel, request.reasoningLevel(), lease, limits.maxOutputTokens());
            SkillResolution availableSkills = skillCatalog(request.workspaceRoot(), lease);
            SkillResolution skillResolution = restrictSkills(availableSkills, inheritedCeiling);
            ContextBudget contextBudget = contextBudget(
                    selectedModel.capabilities(), provider.agentDefaults().context());
            AccessMode resolvedAccessMode = request.accessMode();
            ThreadPreferences requestPreferences = new ThreadPreferences(provider.providerId(), selectedModel.modelId(),
                    request.reasoningLevel(), resolvedAccessMode, request.collaborationMode(),
                    ThreadPreferences.TitleSource.MANUAL);
            Instant requestDeadline = request.requestedAt().plus(limits.wallTimeout());
            AgentCapabilityCatalog.PreparedCapabilities preparedCapabilities = capabilities.prepare(
                    new AgentCapability.Request(request.threadId(), request.turnId(), request.workspaceRoot(),
                            request.workspaceId(), requestPreferences, lease.generationId(),
                            isClarificationEnabled(request, lease.snapshot().clarificationEnabled()), requestDeadline,
                            request.origin(), taskIdentity.map(RuntimeIdentity::kind)));
            AgentPromptSession promptSession = promptSessions.open(new AgentPromptSessionFactory.SessionRequest(
                    request.threadId(), request.workspaceRoot(), jaHome, lease.snapshot().trusted(),
                    promptEnvironment(request, preparedCapabilities, taskIdentity), contextBudget, skills,
                    skillResolution.catalog(), skillResolution.skillNamesById()));
            List<AgentTool> builtInTools = BuiltInTools.create(
                    request.workspaceRoot(), skills, skillResolution.catalog(), shellCapability, promptSession,
                    attachments).snapshot();
            boolean planning = PlanToolPolicy.isReadOnlyPlanning(request.origin(), request.collaborationMode());
            if (planning) builtInTools = PlanReadOnlyToolCatalog.filter(builtInTools);
            TurnMcpSessionFactory.Context toolContext = new TurnMcpSessionFactory.Context(
                    provider.providerId(), selectedModel.modelId(), request.workspaceRoot(),
                    requestDeadline);
            GenerationTurnMcpSessionFactory.CatalogSnapshot mcpCatalog = planning
                    ? GenerationTurnMcpSessionFactory.CatalogSnapshot.planningEmpty()
                    : mcpSessions.catalog(toolContext, lease);
            List<AgentCapability.ToolContribution> catalogCapabilities = planning
                    ? preparedCapabilities.toolContributions().stream()
                        .filter(contribution -> contribution.planAccess() != AgentTool.PlanAccess.DISALLOWED
                                || (contribution.sideEffect() == ToolSideEffect.READ_ONLY
                                && contribution.workspaceMutationMode() == AgentTool.WorkspaceMutationMode.NONE))
                        .toList()
                    : preparedCapabilities.toolContributions();
            String catalogDigest = toolCatalogDigest(builtInTools, catalogCapabilities,
                    mcpCatalog.snapshot(), mcpCatalog.routeIdentities());
            validateInheritedCeiling(inheritedCeiling, requestPreferences, catalogDigest,
                    mcpCatalog.snapshot().revision(), taskIdentity.map(RuntimeIdentity::kind).orElse(null));
            AgentCapability.CatalogIdentity catalogIdentity = new AgentCapability.CatalogIdentity(
                    catalogDigest, mcpCatalog.snapshot().revision(), skillResolution.skillNamesById().keySet());
            AgentCapability.Binding capabilityBinding = preparedCapabilities.bind(catalogIdentity);
            List<AgentTool> capabilityTools = planning
                    ? PlanReadOnlyToolCatalog.filter(capabilityBinding.tools()) : capabilityBinding.tools();
            List<AgentTool> requestTools = new ArrayList<>(builtInTools.size() + capabilityTools.size());
            requestTools.addAll(builtInTools);
            requestTools.addAll(capabilityTools);
            TurnToolSessionFactory toolSessions = toolSessions(mcpCatalog, planning);
            ToolProjectionLimits outputLimits = new ToolProjectionLimits(20_000, 20_000);
            RuntimeLease runtimeLease = new RuntimeLease(lease.generationId(), model,
                    resolvedAccessMode, request.collaborationMode(), limits,
                    List.copyOf(requestTools), toolSessions, outputLimits, promptSession, attachments,
                    presentationSecrets(lease.snapshot(), lease, model.apiKey()),
                    catalogDigest, promptSession.currentRevision(),
                    request.reasoningLevel(), lease);
            transferred = true;
            return runtimeLease;
        } finally {
            if (!transferred) {
                lease.close();
            }
        }
    }

    /**
     * Plan-owned 执行和只读规划都必须能补齐影响结果的决策；前者不能因 Thread 保留的默认模式或
     * 用户关闭普通模式反问而失去提问能力，后者也不能只依赖 collaborationMode 的表面值放行。
     */
    static boolean isClarificationEnabled(TurnRuntimeRequest request, boolean configured) {
        Objects.requireNonNull(request, "request");
        return request.origin() == io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin.PLAN_EXECUTION
                || PlanToolPolicy.isReadOnlyPlanning(request.origin(), request.collaborationMode())
                || configured;
    }

    /**
     * 能力说明与 Side Task 身份在同一次 resolve 中进入 System environment；身份不进入 UserContent、历史
     * 或 summary，且每个请求都重新读取持久投影，保证后续 continuation 不依赖首轮缓存。
     */
    private String promptEnvironment(
            TurnRuntimeRequest request, AgentCapabilityCatalog.PreparedCapabilities preparedCapabilities,
            Optional<RuntimeIdentity> taskIdentity) {
        String environment = shellCapability.executionEnvironment(request.workspaceRoot());
        String fragment = preparedCapabilities.promptFragment();
        String taskFragment = taskIdentity.filter(identity -> identity.kind() == Kind.SIDE_TASK)
                .map(ConfigurationTurnRuntimeResolver::sideTaskIdentityPrompt).orElse("");
        StringBuilder result = new StringBuilder(environment);
        if (!fragment.isBlank()) result.append("\n\n").append(fragment);
        if (!taskFragment.isBlank()) result.append("\n\n").append(taskFragment);
        return result.toString();
    }

    /**
     * 侧聊只继承背景而不继承委派义务；每次请求重申临时身份和纯投递边界，压缩不能把它变成主任务。
     * 名称按数据转义，来源关系不能被模型误解成自动汇报或执行来源任务的授权。
     */
    static String sideTaskIdentityPrompt(RuntimeIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        if (identity.kind() != Kind.SIDE_TASK) {
            throw new IllegalArgumentException("not a Side Task identity");
        }
        return "<side-task-identity>\n"
                + "role: SIDE_TASK\n"
                + "taskThreadId: " + identity.taskThreadId() + "\n"
                + "parentThreadId: " + identity.parentThreadId() + "\n"
                + "rootThreadId: " + identity.rootThreadId() + "\n"
                + "taskName: " + quoted(identity.taskName()) + "\n"
                + "parentTaskName: " + quoted(identity.parentTaskName()) + "\n"
                + "mainTaskName: " + quoted(identity.rootTaskName()) + "\n"
                + "This is an independent, temporary side chat. Closing it ends this conversation and its work;"
                + " it is not restored after application restart. Inherited history is background context only."
                + " Follow the user's instructions in this side chat; do not continue the source task merely"
                + " because it appears in inherited history. Do not send periodic progress or automatic results"
                + " to the source conversation.\n"
                + "Use list_threads to discover other conversations when communication is needed."
                + " The real send_message Tool takes targetThreadId and message and only queues that text."
                + " It does not wake, interrupt, or request a reply from the recipient. A running recipient"
                + " receives it before its next model request; an idle recipient waits for its next normal run."
                + " Replies, when useful, are separate explicit send_message calls.\n"
                + "</side-task-identity>";
    }

    /** Thread 标题是数据而非 system 标记；只转义结构化边界字符，不改写标题语义。 */
    private static String quoted(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n")
                .replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;") + '"';
    }

    /** Child 只能看见 seed 允许的 Skill ID；配置新增 Skill 不会扩大已创建任务的能力。 */
    static SkillResolution restrictSkills(SkillResolution current, Optional<JsonObject> ceiling) {
        if (ceiling.isEmpty()) return current;
        JsonObject inherited = ceiling.orElseThrow();
        if ("task_access_v1".equals(ceilingVersion(inherited))) {
            accessCeiling(inherited);
            return current;
        }
        Set<String> allowed = ceilingSkillIds(inherited);
        Map<String, String> names = current.skillNamesById().entrySet().stream()
                .filter(entry -> allowed.contains(entry.getKey()))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        List<SkillCatalog.SkillDescriptor> descriptors = current.catalog().skills().stream()
                .filter(item -> names.containsValue(item.name())).toList();
        return new SkillResolution(new SkillCatalog.Catalog(descriptors), names);
    }

    /** Skill 允许集合是严格 v1 ceiling 的必填字段，缺失、重复或类型错误均拒绝恢复。 */
    private static Set<String> ceilingSkillIds(JsonObject ceiling) {
        if (!(ceiling.get("version") instanceof JsonText version)
                || !"task_capability_v1".equals(version.value())
                || !(ceiling.get("skillIds") instanceof JsonArray values)) {
            throw new TurnRuntimeResolver.RuntimeMismatchException("Task capability ceiling is invalid");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonValue value : values.values()) {
            if (!(value instanceof JsonText text) || text.value().isBlank() || !ids.add(text.value())) {
                throw new TurnRuntimeResolver.RuntimeMismatchException("Task capability ceiling is invalid");
            }
        }
        return Set.copyOf(ids);
    }

    /** 未携带持久 Kind 时按保守完整 ceiling 校验，避免未知 Child 身份借 Side Task 规则放宽权限。 */
    static void validateInheritedCeiling(Optional<JsonObject> inherited,
                                         ThreadPreferences current, String toolDigest, String mcpRevision) {
        validateInheritedCeiling(inherited, current, toolDigest, mcpRevision, null);
    }

    /**
     * Side Task 的 access ceiling 只校验 seed 格式，不限制用户后续明确选择的 access；Subagent 则必须
     * 保持完整 task_capability_v1 上限。缺少持久 Kind 时沿用严格旧路径，绝不把未知身份当独立任务放行。
     */
    static void validateInheritedCeiling(Optional<JsonObject> inherited,
                                         ThreadPreferences current, String toolDigest, String mcpRevision,
                                         Kind kind) {
        if (inherited.isEmpty()) {
            if (kind != null) {
                throw new TurnRuntimeResolver.RuntimeMismatchException("Task capability ceiling is unavailable");
            }
            return;
        }
        JsonObject ceiling = inherited.orElseThrow();
        if (kind == Kind.SIDE_TASK) {
            if (!"task_access_v1".equals(ceilingVersion(ceiling))) {
                throw new TurnRuntimeResolver.RuntimeMismatchException("Side Task access ceiling is invalid");
            }
            accessCeiling(ceiling);
            return;
        }
        if (kind == Kind.SUBAGENT && "task_access_v1".equals(ceilingVersion(ceiling))) {
            throw new TurnRuntimeResolver.RuntimeMismatchException("Subagent capability ceiling is invalid");
        }
        if ("task_access_v1".equals(ceilingVersion(ceiling))) {
            AccessMode allowed = accessCeiling(ceiling);
            if (allowed == AccessMode.APPROVAL_REQUIRED
                    && current.accessMode() != AccessMode.APPROVAL_REQUIRED) {
                throw new TurnRuntimeResolver.RuntimeMismatchException("Task access exceeds its parent ceiling");
            }
            return;
        }
        Set<String> fields = Set.of("version", "providerId", "modelId", "reasoningLevel", "accessMode",
                "collaborationMode", "configGeneration", "toolCatalogDigest", "mcpCatalogRevision", "skillIds");
        if (!ceiling.members().keySet().equals(fields)) {
            throw new TurnRuntimeResolver.RuntimeMismatchException("Task capability ceiling is invalid");
        }
        requireCeilingText(ceiling, "version", "task_capability_v1");
        JsonValue access = ceiling.get("accessMode");
        if (!(access instanceof JsonText text)
                || !("approval_required".equals(text.value()) || "full_access".equals(text.value()))) {
            throw new TurnRuntimeResolver.RuntimeMismatchException("Task capability ceiling is invalid");
        }
        if ("approval_required".equals(text.value()) && current.accessMode() != AccessMode.APPROVAL_REQUIRED) {
            throw new TurnRuntimeResolver.RuntimeMismatchException("Task access exceeds its parent ceiling");
        }
        requireCeilingText(ceiling, "collaborationMode",
                current.collaborationMode().name().toLowerCase(java.util.Locale.ROOT));
        requireCeilingText(ceiling, "providerId", current.providerId());
        requireCeilingText(ceiling, "modelId", current.modelId());
        if (!(ceiling.get("configGeneration") instanceof JsonText generation)
                || generation.value().isBlank()) {
            throw new TurnRuntimeResolver.RuntimeMismatchException("Task capability ceiling is invalid");
        }
        JsonValue reasoning = ceiling.get("reasoningLevel");
        if (!(reasoning instanceof JsonText) && !(reasoning instanceof JsonNull)) {
            throw new TurnRuntimeResolver.RuntimeMismatchException("Task capability ceiling is invalid");
        }
        String expectedReasoning = reasoning instanceof JsonText value ? value.value() : null;
        if (!Objects.equals(expectedReasoning, current.reasoningLevel())) {
            throw new TurnRuntimeResolver.RuntimeMismatchException("Task reasoning changed from its parent ceiling");
        }
        requireCeilingText(ceiling, "toolCatalogDigest", toolDigest);
        requireCeilingText(ceiling, "mcpCatalogRevision", mcpRevision);
        ceilingSkillIds(ceiling);
    }

    /** Ceiling 版本必须显式属于当前闭集，未知版本不会降级为 access-only 或忽略约束。 */
    private static String ceilingVersion(JsonObject ceiling) {
        if (!(ceiling.get("version") instanceof JsonText version)
                || !("task_access_v1".equals(version.value())
                || "task_capability_v1".equals(version.value()))) {
            throw new TurnRuntimeResolver.RuntimeMismatchException("Task capability ceiling is invalid");
        }
        return version.value();
    }

    /** Side Task 的 v1 上限严格只允许版本与 AccessMode，当前 Turn 只能保持或收窄。 */
    private static AccessMode accessCeiling(JsonObject ceiling) {
        if (!ceiling.members().keySet().equals(Set.of("version", "accessMode"))) {
            throw new TurnRuntimeResolver.RuntimeMismatchException("Task access ceiling is invalid");
        }
        requireCeilingText(ceiling, "version", "task_access_v1");
        JsonValue access = ceiling.get("accessMode");
        if (!(access instanceof JsonText text)
                || !("approval_required".equals(text.value()) || "full_access".equals(text.value()))) {
            throw new TurnRuntimeResolver.RuntimeMismatchException("Task access ceiling is invalid");
        }
        return "approval_required".equals(text.value())
                ? AccessMode.APPROVAL_REQUIRED : AccessMode.FULL_ACCESS;
    }

    /** 不透明身份按精确值比较；同名 Tool 或 MCP 在 route 变化后也不能静默复用。 */
    private static void requireCeilingText(JsonObject ceiling, String field, String actual) {
        if (!(ceiling.get(field) instanceof JsonText expected) || !expected.value().equals(actual)) {
            throw new TurnRuntimeResolver.RuntimeMismatchException("Task capability ceiling changed: " + field);
        }
    }

    /**
     * 在物化能力 Tool 前汇总模型可见的完整安全目录并拒绝跨来源重名；目录摘要直接覆盖每个 Tool 的
     * Schema、副作用、工作区可观察性、审批要求与固定路由，不借能力 catalog hash 间接代表这些事实。
     */
    static String toolCatalogDigest(
            List<AgentTool> builtInTools,
            List<AgentCapability.ToolContribution> capabilityTools,
            McpGateway.McpSnapshot mcpSnapshot,
            Map<String, McpGateway.RouteIdentity> mcpRoutes) {
        Objects.requireNonNull(builtInTools, "builtInTools");
        Objects.requireNonNull(capabilityTools, "capabilityTools");
        Objects.requireNonNull(mcpSnapshot, "mcpSnapshot");
        Map<String, McpGateway.RouteIdentity> routes = Map.copyOf(
                Objects.requireNonNull(mcpRoutes, "mcpRoutes"));
        if (routes.size() != mcpSnapshot.tools().size()) {
            throw new IllegalArgumentException("MCP route identities do not match catalog tools");
        }
        List<ToolCatalogEntry> entries = new ArrayList<>(
                builtInTools.size() + capabilityTools.size() + mcpSnapshot.tools().size());
        builtInTools.forEach(tool -> entries.add(new ToolCatalogEntry(
                tool.spec(), tool.sideEffect(), tool.workspaceMutationMode(), tool.approvalRequirement(),
                tool.bindingDescriptor())));
        // request_user_input 是随角色显隐的用户交互入口，Subagent 必须向委派方询问而不直接弹卡。
        // 它不授予工作区/外部能力，不能因子任务正常隐藏该入口而使继承的执行能力指纹失配。
        capabilityTools.stream().filter(tool -> !"request_user_input".equals(tool.spec().name()))
                .forEach(tool -> entries.add(new ToolCatalogEntry(
                tool.spec(), tool.sideEffect(), tool.workspaceMutationMode(), tool.approvalRequirement(),
                tool.bindingDescriptor())));
        for (McpGateway.McpTool tool : mcpSnapshot.tools()) {
            McpGateway.RouteIdentity route = Objects.requireNonNull(
                    routes.get(tool.spec().name()), "MCP route identity");
            if (!tool.spec().name().equals(route.localName())
                    || !tool.serverId().equals(route.serverId())
                    || !mcpSnapshot.revision().equals(route.catalogRevision())) {
                throw new IllegalArgumentException("MCP route identity does not match catalog snapshot");
            }
            AgentTool.ToolBindingDescriptor descriptor = new AgentTool.ToolBindingDescriptor(
                    AgentTool.RouteKind.MCP, route.localName(), route.serverId(), route.remoteName(),
                    route.schemaHash(), route.routeHash());
            entries.add(new ToolCatalogEntry(tool.spec(),
                    ToolSideEffect.EXTERNAL,
                    AgentTool.WorkspaceMutationMode.UNOBSERVABLE,
                    AgentTool.ApprovalRequirement.USER_REQUIRED, descriptor));
        }
        Set<String> names = new HashSet<>();
        for (ToolCatalogEntry entry : entries) {
            if (!names.add(entry.spec().name())) {
                throw new IllegalArgumentException("duplicate_tool_name: " + entry.spec().name());
            }
        }
        StringBuilder canonical = new StringBuilder();
        appendToken(canonical, 'v', "tool_catalog_v3");
        entries.stream().sorted(Comparator.comparing(entry -> entry.spec().name())).forEach(entry -> {
            ToolSpec spec = entry.spec();
            AgentTool.ToolBindingDescriptor descriptor = entry.bindingDescriptor();
            appendToken(canonical, 'n', spec.name());
            appendToken(canonical, 'd', spec.description());
            appendToken(canonical, 's', AgentTool.canonicalSchema(spec.inputSchema()));
            appendToken(canonical, 'e', entry.sideEffect().name());
            appendToken(canonical, 'w', entry.workspaceMutationMode().name());
            appendToken(canonical, 'a', entry.approvalRequirement().name());
            appendToken(canonical, 'k', descriptor.routeKind().name());
            appendToken(canonical, 'l', descriptor.localName());
            appendToken(canonical, 'i', descriptor.serverId());
            appendToken(canonical, 'r', descriptor.remoteName());
            appendToken(canonical, 'h', descriptor.schemaHash());
            appendToken(canonical, 'b', descriptor.routeHash());
        });
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** 统一三类 Tool 的目录投影，摘要与重名检查不需要提前创建可执行适配器。 */
    private record ToolCatalogEntry(
            ToolSpec spec,
            ToolSideEffect sideEffect,
            AgentTool.WorkspaceMutationMode workspaceMutationMode,
            AgentTool.ApprovalRequirement approvalRequirement,
            AgentTool.ToolBindingDescriptor bindingDescriptor) {
        /** 目录项拒绝空安全字段，避免摘要阶段把不完整声明降级为默认值。 */
        private ToolCatalogEntry {
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(sideEffect, "sideEffect");
            Objects.requireNonNull(workspaceMutationMode, "workspaceMutationMode");
            Objects.requireNonNull(approvalRequirement, "approvalRequirement");
            Objects.requireNonNull(bindingDescriptor, "bindingDescriptor");
            if (!spec.name().equals(bindingDescriptor.localName())) {
                throw new IllegalArgumentException("Tool binding name does not match catalog entry");
            }
        }
    }

    /** 长度前缀让相邻字段不可产生拼接歧义，内容无需依赖某个 JSON 库的转义或 Map 配置。 */
    private static void appendToken(StringBuilder target, char type, String value) {
        target.append(type).append(value.length()).append(':').append(value);
    }

    /**
     * 捕获当前请求真正可触达的 Provider、MCP 配置与进程环境敏感值；排序后先替换长值，
     * 防止一个短 Secret 提前破坏另一个长 Secret 的完整匹配。
     */
    private static List<String> presentationSecrets(
            ConfigurationGenerationSnapshot snapshot,
            ConfigurationGenerationPort.Lease lease,
            String providerSecret) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        addSecret(values, providerSecret);
        for (ConfigurationGenerationSnapshot.McpServer server : snapshot.mcpDefinitions()) {
            if (!server.enabled()) continue;
            server.env().forEach((key, value) -> addSensitiveSetting(values, key, value));
            server.headers().forEach((key, value) -> addSensitiveSetting(values, key, value));
            if (server.auth().credentialId() != null) {
                addSecret(values, lease.secretFor(server.auth().credentialId()));
            }
        }
        System.getenv().forEach((key, value) -> addSensitiveSetting(values, key, value));
        return values.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    /** 只把敏感名称对应的配置或环境值加入集合，普通变量不会导致大范围误脱敏。 */
    private static void addSensitiveSetting(Set<String> values, String key, String value) {
        String normalized = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT)
                .replace("_", "").replace("-", "").replace(".", "");
        if (normalized.contains("token") || normalized.contains("password")
                || normalized.contains("passwd") || normalized.contains("apikey")
                || normalized.contains("authorization") || normalized.contains("cookie")
                || normalized.contains("secret")) {
            addSecret(values, value);
        }
    }

    /** 忽略空 Secret，避免空串替换在投影文本的每个字符边界产生无意义标记。 */
    private static void addSecret(Set<String> values, String value) {
        if (value != null && !value.isEmpty()) values.add(value);
    }

    /** 预算预检只租用配置代际，不解析尚未准入的 Plan Turn 身份或生成可执行工具。 */
    @Override
    public TurnLimits resolveLimits(TurnRuntimeRequest request) {
        Objects.requireNonNull(request, "request");
        try (ConfigurationGenerationPort.Lease lease = configurations.acquire(request.workspaceRoot())) {
            var provider = lease.snapshot().requireProvider(request.providerId());
            var selectedModel = lease.snapshot().requireModel(request.providerId(), request.modelId());
            validateReasoning(request.reasoningLevel(), selectedModel);
            return limits(request, provider, selectedModel);
        }
    }

    /** Workspace 生命周期只登记配置与定义 revision；短租约结束前不得启动 MCP 或读取 Tool schema。 */
    @Override
    public void prepareWorkspace(Path workspaceRoot) {
        Path root = Objects.requireNonNull(workspaceRoot, "workspaceRoot")
                .toAbsolutePath().normalize();
        try (ConfigurationGenerationPort.Lease lease = configurations.acquire(root)) {
            generationCatalog.prepareWorkspace(root, lease);
        }
    }

    /** 短时取得配置租约并返回 Owner 已成对验证的默认模型选择，不借出 snapshot 或 Secret。 */
    @Override
    public Optional<DefaultModelSelection> defaultModelSelection(Path workspaceRoot) {
        Path root = Objects.requireNonNull(workspaceRoot, "workspaceRoot")
                .toAbsolutePath().normalize();
        try (ConfigurationGenerationPort.Lease lease = configurations.acquire(root)) {
            ConfigurationGenerationSnapshot snapshot = lease.snapshot();
            if (snapshot.defaultProviderId().isEmpty() || snapshot.defaultModelId().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new DefaultModelSelection(snapshot.defaultProviderId().get(),
                    snapshot.defaultModelId().get(), snapshot.defaultReasoningLevel()
                            .map(value -> value.name().toLowerCase(java.util.Locale.ROOT)).orElse(null)));
        }
    }

    /**
     * 把 Provider 默认上限映射为领域预算，并以入站 Deadline 继续收紧。
     */
    private static TurnLimits limits(TurnRuntimeRequest request,
                                     ConfigurationGenerationSnapshot.Provider provider,
                                     ConfigurationGenerationSnapshot.Model model) {
        ConfigurationGenerationSnapshot.Capabilities capabilities = model.capabilities();
        int maxInputTokens = boundedTokenCount(
                capabilities.contextWindowTokens() - capabilities.maxOutputTokens(), 4_000_000);
        int maxOutputTokens = boundedTokenCount(capabilities.maxOutputTokens(), 1_000_000);
        ConfigurationGenerationSnapshot.TurnLimits configured = provider.agentDefaults().turnLimits();
        Duration wallTimeout = minimum(request.deadline(), configured.wallTimeout());
        return new TurnLimits(
                configured.maxModelRounds(), configured.maxToolCalls(),
                maxInputTokens, maxOutputTokens, wallTimeout);
    }

    /**
     * 显式穷举配置枚举并解析本次模型输入模态，同时短时借用凭据；禁止通过名称或旧 Provider 别名回退。
     */
    static ModelPort.ModelConfiguration model(
            ConfigurationGenerationSnapshot.Provider provider,
            ConfigurationGenerationSnapshot.Model selectedModel,
            String reasoningLevel,
            ConfigurationGenerationPort.Lease lease,
            int maxOutputTokens) {
        ModelPort.Api api = modelApi(provider.api());
        String apiKey = lease.secretFor(provider.credentialId());
        if (apiKey == null || apiKey.isEmpty()) {
            throw new TurnRuntimeResolver.RuntimeMismatchException(
                    "Provider credential is unavailable");
        }
        return new ModelPort.ModelConfiguration(provider.providerId(), selectedModel.modelId(),
                lease.generationId(), api, selectedModel.model(), provider.baseUrl(), apiKey,
                provider.networkTimeouts().connectTimeout(), provider.networkTimeouts().requestTimeout(),
                selectedModel.capabilities().inputModalities().stream()
                        .map(modality -> ModelPort.InputModality.valueOf(modality.name()))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                new ModelPort.GenerationOptions(null, null, maxOutputTokens,
                        upstreamReasoning(reasoningLevel, selectedModel)));
    }

    /** 显式映射配置 API，运行时严格执行选定协议且绝不自动 fallback。 */
    private static ModelPort.Api modelApi(ConfigurationGenerationSnapshot.Api api) {
        return switch (api) {
            case OPENAI_RESPONSES -> ModelPort.Api.OPENAI_RESPONSES;
            case ANTHROPIC_MESSAGES -> ModelPort.Api.ANTHROPIC_MESSAGES;
            case OPENAI_CHAT_COMPLETIONS -> ModelPort.Api.OPENAI_CHAT_COMPLETIONS;
        };
    }

    /**
     * 依据配置中的稳定名称选择本 Turn 可发现目录；正文直到 read 激活时才访问对应资源。
     */
    private SkillResolution skillCatalog(
            Path workspaceRoot, ConfigurationGenerationPort.Lease lease) {
        SkillCatalog.DiscoveryRequest request = new SkillCatalog.DiscoveryRequest(
                workspaceRoot, agentsSkillRoot, jaSkillRoot, lease.snapshot().trusted());
        List<ConfigurationGenerationSnapshot.Skill> enabled = lease.snapshot().skillDefinitions().stream()
                .filter(ConfigurationGenerationSnapshot.Skill::enabled).toList();
        if (enabled.isEmpty()) {
            // 未授权任何 Skill 时不扫描无关目录；严格格式错误只能阻断真正选择了 Skill 的 Turn。
            return new SkillResolution(skills.emptyCatalog(), Map.of());
        }
        Set<String> allowedNames = new HashSet<>();
        for (ConfigurationGenerationSnapshot.Skill skill : enabled) {
            allowedNames.add(skill.name());
        }
        SkillCatalog.Catalog discovered = skills.discover(request);
        List<String> availableNames = discovered.skills().stream()
                .filter(descriptor -> allowedNames.contains(descriptor.name()))
                .map(SkillCatalog.SkillDescriptor::name)
                .toList();
        if (availableNames.size() != allowedNames.size()) {
            throw new TurnRuntimeResolver.RuntimeMismatchException(
                    "configured Turn Skill is unavailable");
        }
        return new SkillResolution(skills.select(discovered, availableNames),
                skillNamesById(enabled, discovered));
    }

    /**
     * 稳定 ID 只能来自本次解析的配置代际，发现目录只证明对应名称本代际可读；两者求交后再发布，
     * 避免用展示名称反推配置身份，也不会把未启用或未发现条目暴露给消息引用。
     */
    static Map<String, String> skillNamesById(
            List<ConfigurationGenerationSnapshot.Skill> definitions,
            SkillCatalog.Catalog discovered) {
        Set<String> discoveredNames = discovered.skills().stream()
                .map(SkillCatalog.SkillDescriptor::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, String> identities = new java.util.LinkedHashMap<>();
        definitions.stream()
                .filter(ConfigurationGenerationSnapshot.Skill::enabled)
                .filter(skill -> discoveredNames.contains(skill.name()))
                .forEach(skill -> identities.put(skill.skillId(), skill.name()));
        return java.util.Collections.unmodifiableMap(identities);
    }

    /** 同步携带筛选后的目录与配置身份表，防止两个 Prompt 输入来自不同发现结果。 */
    record SkillResolution(
            SkillCatalog.Catalog catalog,
            Map<String, String> skillNamesById) {
        /** 防御性复制两项同源结果，Resolver 后续组装不能替换其中任一集合。 */
        SkillResolution {
            catalog = Objects.requireNonNull(catalog, "catalog");
            skillNamesById = Map.copyOf(Objects.requireNonNull(skillNamesById, "skillNamesById"));
        }
    }

    /**
     * 延迟到 AgentLoop 准备 Provider 请求时 pin 同一个不透明目录句柄；通知刷新只影响下一请求。
     */
    private TurnToolSessionFactory toolSessions(
            GenerationTurnMcpSessionFactory.CatalogSnapshot catalogSnapshot, boolean disabled) {
        Objects.requireNonNull(catalogSnapshot, "catalogSnapshot");
        if (disabled) {
            return cancellation -> new TurnToolSessionFactory.Session() {
                /** 规划阶段显式返回空 MCP 集合，保证远端工具不会进入模型目录。 */
                @Override public List<AgentTool> tools() { return List.of(); }
                /** 空会话没有外部资源，关闭保持幂等以统一生命周期协议。 */
                @Override public void close() { }
            };
        }
        return cancellation -> {
            TurnMcpSessionFactory.Session opened = mcpSessions.open(catalogSnapshot, cancellation);
            try {
                List<AgentTool> adapted = McpAgentTool.adapt(
                        opened.gateway(), opened.snapshot(), catalogSnapshot.routeIdentities());
                return new TurnToolSessionFactory.Session() {
                    private boolean closed;

                    /** 返回本次 Provider 请求绑定的不可变 MCP Tool 目录；会话关闭后拒绝复用失效连接。 */
                    @Override
                    public List<AgentTool> tools() {
                        if (closed) {
                            throw new IllegalStateException("Turn MCP session is closed");
                        }
                        return List.copyOf(adapted);
                    }

                    /** 幂等关闭底层 MCP 会话，使正常、取消和异常路径可以竞争释放。 */
                    @Override
                    public void close() {
                        if (!closed) {
                            closed = true;
                            opened.close();
                        }
                    }
                };
            } catch (RuntimeException failure) {
                opened.close();
                throw failure;
            }
        };
    }

    /**
     * 用配置窗口生成无 Provider 实测值的首次预算，动态计量由 Turn 状态机后续覆盖。
     */
    private static ContextBudget contextBudget(
            ConfigurationGenerationSnapshot.Capabilities capabilities,
            ConfigurationGenerationSnapshot.Context context) {
        return ContextBudget.capabilities(capabilities.contextWindowTokens(),
                capabilities.maxOutputTokens(), context.autoCompact());
    }

    /**
     * 将 long 配置收敛到领域允许的正整数上限，拒绝固定预算已经耗尽的配置。
     */
    private static int boundedTokenCount(long value, int maximum) {
        if (value < 1) {
            throw new IllegalArgumentException("configured token budget is exhausted");
        }
        return (int) Math.min(value, maximum);
    }

    /**
     * 选择两个正 Deadline 中更严格者，Resolver 永远不能扩大客户端意图。
     */
    private static Duration minimum(Duration requested, Duration configured) {
        return requested.compareTo(configured) <= 0 ? requested : configured;
    }

    /** reasoning 必须属于所选模型显式能力集合；null 继续使用模型默认。 */
    private static void validateReasoning(
            String requested, ConfigurationGenerationSnapshot.Model model) {
        if (requested == null) return;
        final ConfigurationGenerationSnapshot.ReasoningLevel effort;
        try {
            effort = ConfigurationGenerationSnapshot.ReasoningLevel.valueOf(
                    requested.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new TurnRuntimeResolver.RuntimeMismatchException(
                    "reasoning level is unavailable");
        }
        if (!model.reasoningLevelMap().containsKey(effort)) {
            throw new TurnRuntimeResolver.RuntimeMismatchException(
                    "reasoning level is unsupported");
        }
    }

    /** 将本次请求的逻辑档位解析为模型声明的上游值；null 保持 Provider 默认。 */
    private static String upstreamReasoning(
            String requested, ConfigurationGenerationSnapshot.Model model) {
        if (requested == null) return null;
        ConfigurationGenerationSnapshot.ReasoningLevel level =
                ConfigurationGenerationSnapshot.ReasoningLevel.valueOf(
                        requested.toUpperCase(java.util.Locale.ROOT));
        String upstream = model.reasoningLevelMap().get(level);
        if (upstream == null) throw new IllegalArgumentException("reasoning level is unsupported");
        return upstream;
    }
}
