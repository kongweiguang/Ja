// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap.runtime;

import io.github.kongweiguang.ja.agent.application.skill.SkillCatalog;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonNull;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.skill.SkillDescriptor;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.domain.turn.TurnLimits;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader;
import io.github.kongweiguang.ja.conversation.application.gateway.ManagedToolMediaImporter;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.ToolArgumentValidator;
import io.github.kongweiguang.ja.catalog.infrastructure.mcp.generation.GenerationCatalog;
import io.github.kongweiguang.ja.catalog.infrastructure.mcp.generation.GenerationTurnMcpSessionFactory;
import io.github.kongweiguang.ja.catalog.infrastructure.mcp.session.TurnMcpSessionFactory;
import io.github.kongweiguang.ja.catalog.infrastructure.mcp.tools.McpAgentTool;
import io.github.kongweiguang.ja.catalog.infrastructure.skills.JaSkillCatalog;
import io.github.kongweiguang.ja.catalog.infrastructure.acl.ThreadMcpIdentity;
import io.github.kongweiguang.ja.catalog.application.gateway.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.catalog.application.gateway.McpGateway;
import io.github.kongweiguang.ja.catalog.application.gateway.ThreadMcpCatalogPort;
import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationGenerationSnapshot;
import io.github.kongweiguang.ja.configuration.domain.model.SkillReference;
import io.github.kongweiguang.ja.conversation.infrastructure.tools.BuiltInTools;
import io.github.kongweiguang.ja.conversation.infrastructure.tools.NetworkntToolArgumentValidation;
import io.github.kongweiguang.ja.conversation.infrastructure.tools.PlanReadOnlyToolCatalog;
import io.github.kongweiguang.ja.conversation.infrastructure.tools.ShellCapability;
import io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase;
import io.github.kongweiguang.ja.conversation.infrastructure.tools.ProcessToolFactory;
import io.github.kongweiguang.ja.review.interfaces.tools.ReviewReportToolFactory;
import io.github.kongweiguang.ja.conversation.application.interaction.InteractionToolFactory;
import io.github.kongweiguang.ja.conversation.application.policy.PlanToolPolicy;
import io.github.kongweiguang.ja.conversation.application.prompt.CollaborationModePrompt;
import io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.application.gateway.AgentPromptSessionFactory;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.conversation.application.gateway.JaToolCatalogIdentity;
import io.github.kongweiguang.ja.conversation.application.gateway.JaToolRequestContext;
import io.github.kongweiguang.ja.conversation.application.gateway.RuntimeLease;
import io.github.kongweiguang.ja.conversation.application.gateway.TaskCapabilityCeilingPort;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeRequest;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeResolver;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnToolSessionFactory;
import io.github.kongweiguang.ja.conversation.application.gateway.TaskCapabilityCeilingPort.Kind;
import io.github.kongweiguang.ja.conversation.application.gateway.TaskCapabilityCeilingPort.RuntimeIdentity;
import io.github.kongweiguang.ja.conversation.application.gateway.TaskCapabilityCeilingPort.RuntimeSnapshot;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceBoundaryFactory;
import io.github.kongweiguang.ja.plan.interfaces.tools.PlanToolFactory;
import io.github.kongweiguang.ja.collaboration.interfaces.tools.TaskAgentToolGateway;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 在每次 Provider 请求安全点解析 Provider、MCP、Skills 与预算，并交接短生命周运行时。
 */
public final class ConfigurationTurnRuntimeResolver implements TurnRuntimeResolver {
    private final ConfigurationGenerationPort configurations;
    private final JaSkillCatalog skills;
    private final Path jaHome;
    private final WorkspaceBoundaryFactory workspaceBoundaries;
    private final ShellCapability shellCapability;
    private final Map<NativeExecutionSnapshot, ShellCapability> clientShells =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final GenerationCatalog generationCatalog;
    private final GenerationTurnMcpSessionFactory mcpSessions;
    private final JsonValueCodec argumentsCodec;
    private final ToolArgumentValidator toolArgumentValidator;
    private final AgentPromptSessionFactory promptSessions;
    private final ManagedAttachmentReader attachments;
    private final ManagedToolMediaImporter mediaImporter;
    private final PlanToolFactory planTools;
    private final TaskAgentToolGateway taskTools;
    private final InteractionToolFactory interactionTools;
    private final TaskCapabilityCeilingPort taskCeilings;
    private final ProcessSessionUseCase processes;

    /**
     * 注入窄端口和无状态适配器，Resolver 本身不读取配置文件或持有 secret。
     */
    public ConfigurationTurnRuntimeResolver(
            ConfigurationGenerationPort configurations,
            JaSkillCatalog skills,
            Path jaHome,
            WorkspaceBoundaryFactory workspaceBoundaries,
            ShellCapability shellCapability,
            GenerationCatalog generationCatalog,
            GenerationTurnMcpSessionFactory mcpSessions,
            JsonValueCodec argumentsCodec,
            AgentPromptSessionFactory promptSessions,
            ManagedAttachmentReader attachments,
            ManagedToolMediaImporter mediaImporter,
            PlanToolFactory planTools,
            TaskAgentToolGateway taskTools,
            InteractionToolFactory interactionTools,
            TaskCapabilityCeilingPort taskCeilings,
            ProcessSessionUseCase processes) {
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.skills = Objects.requireNonNull(skills, "skills");
        this.jaHome = Objects.requireNonNull(jaHome, "jaHome").toAbsolutePath().normalize();
        this.workspaceBoundaries = Objects.requireNonNull(workspaceBoundaries, "workspaceBoundaries");
        this.shellCapability = Objects.requireNonNull(shellCapability, "shellCapability");
        this.generationCatalog = Objects.requireNonNull(generationCatalog, "generationCatalog");
        this.mcpSessions = Objects.requireNonNull(mcpSessions, "mcpSessions");
        this.argumentsCodec = Objects.requireNonNull(argumentsCodec, "argumentsCodec");
        this.toolArgumentValidator = new NetworkntToolArgumentValidation(this.argumentsCodec);
        this.promptSessions = Objects.requireNonNull(promptSessions, "promptSessions");
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.mediaImporter = Objects.requireNonNull(mediaImporter, "mediaImporter");
        this.planTools = Objects.requireNonNull(planTools, "planTools");
        this.processes = Objects.requireNonNull(processes, "processes");
        this.taskTools = Objects.requireNonNull(taskTools, "taskTools");
        this.interactionTools = Objects.requireNonNull(interactionTools, "interactionTools");
        this.taskCeilings = Objects.requireNonNull(taskCeilings, "taskCeilings");
    }

    /**
     * Plan-owned 执行和只读规划都必须能补齐影响结果的决策；前者不能因 Thread 保留的默认模式或
     * 用户关闭普通模式反问而失去提问能力，后者也不能只依赖 collaborationMode 的表面值放行。
     */
    static boolean isClarificationEnabled(TurnRuntimeRequest request, boolean configured) {
        Objects.requireNonNull(request, "request");
        return request.origin() == io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin.PLAN_EXECUTION
               || PlanToolPolicy.isReadOnlyPlanning(request.origin(), request.collaborationMode())
               || configured;
    }

    /**
     * 规划模式隐藏已启用服务，但不连接服务即可保留名称和停用状态。
     */
    private static List<McpGateway.McpServerStatus> notExposedStatuses(
            ConfigurationGenerationSnapshot generation) {
        return generation.mcpDefinitions().stream()
                .sorted(Comparator.comparing(ConfigurationGenerationSnapshot.McpServer::mcpId))
                .map(server -> new McpGateway.McpServerStatus(server.mcpId(), server.name(),
                        server.enabled() ? "not_exposed" : "disabled", null, null))
                .toList();
    }

    /**
     * Plan 目录只摘要和物化显式只读或受信内部工具，避免把未授权写工具交给请求扩展注册。
     */
    private static boolean safeForPlanning(JaTool.Definition definition) {
        return definition.planAccess() != JaTool.PlanAccess.DISALLOWED
               || (definition.sideEffect() == ToolSideEffect.READ_ONLY
                   && definition.workspaceMutationMode() == JaTool.WorkspaceMutationMode.NONE);
    }

    /**
     * 侧聊只继承背景而不继承委派义务；每次请求重申临时身份和纯投递边界，压缩不能把它变成主任务。
     * 名称按数据转义，来源关系不能被模型误解成自动汇报或执行来源任务的授权。
     * 文本块固定 LF 与段落边界，续行只改善源码阅读，不给实际提示词引入额外换行。
     */
    static String sideTaskIdentityPrompt(RuntimeIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        if (identity.kind() != Kind.SIDE_TASK) {
            throw new IllegalArgumentException("not a Side Task identity");
        }
        return """
            <side-task-identity>
            role: SIDE_TASK
            taskThreadId: %s
            parentThreadId: %s
            rootThreadId: %s
            taskName: %s
            parentTaskName: %s
            mainTaskName: %s
            This is an independent, temporary side chat. Closing it ends this conversation and its work; \
            it is not restored after application restart. Inherited history is background context only. \
            Follow the user's instructions in this side chat; do not continue the source task merely \
            because it appears in inherited history. Do not send periodic progress or automatic results \
            to the source conversation.
            Use list_threads to discover other conversations when communication is needed. \
            The real send_message Tool takes targetThreadId and message and only queues that text. \
            It does not wake, interrupt, or request a reply from the recipient. A running recipient \
            receives it before its next model request; an idle recipient waits for its next normal run. \
            Replies, when useful, are separate explicit send_message calls.
            </side-task-identity>\
            """.formatted(identity.taskThreadId(), identity.parentThreadId(), identity.rootThreadId(),
                quoted(identity.taskName()), quoted(identity.parentTaskName()), quoted(identity.rootTaskName()));
    }

    /**
     * Thread 标题是数据而非 system 标记；只转义结构化边界字符，不改写标题语义。
     */
    private static String quoted(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n")
                .replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;") + '"';
    }

    /**
     * Child 只能看见 seed 允许的 Skill ID；通过原 catalog 选择保留已发现来源绑定，不能伪造目录条目。
     */
    static SkillResolution restrictSkills(SkillCatalog skillCatalog, SkillResolution current,
                                          Optional<JsonObject> ceiling) {
        Objects.requireNonNull(skillCatalog, "skillCatalog");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(ceiling, "ceiling");
        if (ceiling.isEmpty()) {
            return current;
        }
        JsonObject inherited = ceiling.orElseThrow();
        if ("task_access_v1".equals(ceilingVersion(inherited))) {
            accessCeiling(inherited);
            return current;
        }
        Set<String> allowed = ceilingSkillIds(inherited);
        Map<String, String> names = current.skillNamesById().entrySet().stream()
                .filter(entry -> allowed.contains(entry.getKey()))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        SkillCatalog.Catalog selected = skillCatalog.select(current.catalog(), List.copyOf(names.values()));
        return new SkillResolution(selected, names);
    }

    /**
     * Skill 允许集合是严格 v1 ceiling 的必填字段，缺失、重复或类型错误均拒绝恢复。
     */
    private static Set<String> ceilingSkillIds(JsonObject ceiling) {
        if (!(ceiling.get("version") instanceof JsonText(String value2))
            || !"task_capability_v1".equals(value2)
            || !(ceiling.get("skillIds") instanceof JsonArray(List<JsonValue> values1))) {
            throw new TurnRuntimeResolver.RuntimeMismatchException("Task capability ceiling is invalid");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonValue value : values1) {
            if (!(value instanceof JsonText(String value1)) || value1.isBlank() || !ids.add(value1)) {
                throw new TurnRuntimeResolver.RuntimeMismatchException("Task capability ceiling is invalid");
            }
        }
        return Set.copyOf(ids);
    }

    /**
     * 未携带持久 Kind 时按保守完整 ceiling 校验，避免未知 Child 身份借 Side Task 规则放宽权限。
     */
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
        if (!(access instanceof JsonText(String value2))
            || !("approval_required".equals(value2) || "full_access".equals(value2))) {
            throw new TurnRuntimeResolver.RuntimeMismatchException("Task capability ceiling is invalid");
        }
        if ("approval_required".equals(value2) && current.accessMode() != AccessMode.APPROVAL_REQUIRED) {
            throw new TurnRuntimeResolver.RuntimeMismatchException("Task access exceeds its parent ceiling");
        }
        requireCeilingText(ceiling, "collaborationMode",
                current.collaborationMode().name().toLowerCase(java.util.Locale.ROOT));
        requireCeilingText(ceiling, "providerId", current.providerId());
        requireCeilingText(ceiling, "modelId", current.modelId());
        if (!(ceiling.get("configGeneration") instanceof JsonText(String value))
            || value.isBlank()) {
            throw new TurnRuntimeResolver.RuntimeMismatchException("Task capability ceiling is invalid");
        }
        JsonValue reasoning = ceiling.get("reasoningLevel");
        if (!(reasoning instanceof JsonText) && !(reasoning instanceof JsonNull)) {
            throw new TurnRuntimeResolver.RuntimeMismatchException("Task capability ceiling is invalid");
        }
        String expectedReasoning = reasoning instanceof JsonText(String value1) ? value1 : null;
        if (!Objects.equals(expectedReasoning, current.reasoningLevel())) {
            throw new TurnRuntimeResolver.RuntimeMismatchException("Task reasoning changed from its parent ceiling");
        }
        requireCeilingText(ceiling, "toolCatalogDigest", toolDigest);
        requireCeilingText(ceiling, "mcpCatalogRevision", mcpRevision);
        ceilingSkillIds(ceiling);
    }

    /**
     * Ceiling 版本必须显式属于当前闭集，未知版本不会降级为 access-only 或忽略约束。
     */
    private static String ceilingVersion(JsonObject ceiling) {
        if (!(ceiling.get("version") instanceof JsonText(String value))
            || !("task_access_v1".equals(value)
                 || "task_capability_v1".equals(value))) {
            throw new TurnRuntimeResolver.RuntimeMismatchException("Task capability ceiling is invalid");
        }
        return value;
    }

    /**
     * Side Task 的 v1 上限严格只允许版本与 AccessMode，当前 Turn 只能保持或收窄。
     */
    private static AccessMode accessCeiling(JsonObject ceiling) {
        if (!ceiling.members().keySet().equals(Set.of("version", "accessMode"))) {
            throw new TurnRuntimeResolver.RuntimeMismatchException("Task access ceiling is invalid");
        }
        requireCeilingText(ceiling, "version", "task_access_v1");
        JsonValue access = ceiling.get("accessMode");
        if (!(access instanceof JsonText(String value))
            || !("approval_required".equals(value) || "full_access".equals(value))) {
            throw new TurnRuntimeResolver.RuntimeMismatchException("Task access ceiling is invalid");
        }
        return "approval_required".equals(value)
                ? AccessMode.APPROVAL_REQUIRED : AccessMode.FULL_ACCESS;
    }

    /**
     * 不透明身份按精确值比较；同名 Tool 或 MCP 在 route 变化后也不能静默复用。
     */
    private static void requireCeilingText(JsonObject ceiling, String field, String actual) {
        if (!(ceiling.get(field) instanceof JsonText(String value)) || !value.equals(actual)) {
            throw new TurnRuntimeResolver.RuntimeMismatchException("Task capability ceiling changed: " + field);
        }
    }

    /**
     * 在物化业务 Tool 前汇总完整安全定义目录并拒绝跨来源重名；目录摘要直接覆盖每个 Tool 的
     * Schema、副作用、工作区可观察性、Plan 准入、审批要求与固定路由。
     */
    static String toolCatalogDigest(
            List<JaTool> builtInTools,
            List<JaTool.Definition> jaToolDefinitions,
            McpGateway.McpSnapshot mcpSnapshot,
            Map<String, McpGateway.RouteIdentity> mcpRoutes) {
        Objects.requireNonNull(builtInTools, "builtInTools");
        Objects.requireNonNull(jaToolDefinitions, "jaToolDefinitions");
        Objects.requireNonNull(mcpSnapshot, "mcpSnapshot");
        Map<String, McpGateway.RouteIdentity> routes = Map.copyOf(
                Objects.requireNonNull(mcpRoutes, "mcpRoutes"));
        if (routes.size() != mcpSnapshot.tools().size()) {
            throw new IllegalArgumentException("MCP route identities do not match catalog tools");
        }
        List<ToolCatalogEntry> entries = new ArrayList<>(
                builtInTools.size() + jaToolDefinitions.size() + mcpSnapshot.tools().size());
        builtInTools.forEach(tool -> entries.add(new ToolCatalogEntry(
                tool.spec(), tool.sideEffect(), tool.workspaceMutationMode(), tool.planAccess(),
                tool.approvalRequirement(),
                tool.bindingDescriptor())));
        jaToolDefinitions.forEach(tool -> entries.add(new ToolCatalogEntry(
                tool.spec(), tool.sideEffect(), tool.workspaceMutationMode(), tool.planAccess(),
                tool.approvalRequirement(),
                tool.bindingDescriptor())));
        for (McpGateway.McpTool tool : mcpSnapshot.tools()) {
            McpGateway.RouteIdentity route = Objects.requireNonNull(
                    routes.get(tool.spec().name()), "MCP route identity");
            if (!tool.spec().name().equals(route.localName())
                || !tool.serverId().equals(route.serverId())
                || !mcpSnapshot.revision().equals(route.catalogRevision())) {
                throw new IllegalArgumentException("MCP route identity does not match catalog snapshot");
            }
            JaTool.ToolBindingDescriptor descriptor = new JaTool.ToolBindingDescriptor(
                    JaTool.RouteKind.MCP, route.localName(), route.serverId(), route.remoteName(),
                    route.schemaHash(), route.routeHash());
            entries.add(new ToolCatalogEntry(tool.spec(),
                    ToolSideEffect.EXTERNAL,
                    JaTool.WorkspaceMutationMode.UNOBSERVABLE,
                    JaTool.PlanAccess.DISALLOWED,
                    JaTool.ApprovalRequirement.USER_REQUIRED, descriptor));
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
            JaTool.ToolBindingDescriptor descriptor = entry.bindingDescriptor();
            appendToken(canonical, 'n', spec.name());
            appendToken(canonical, 'd', spec.description());
            appendToken(canonical, 's', AgentTool.canonicalSchema(spec.inputSchema()));
            appendToken(canonical, 'e', entry.sideEffect().name());
            appendToken(canonical, 'w', entry.workspaceMutationMode().name());
            appendToken(canonical, 'p', entry.planAccess().name());
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

    /**
     * 长度前缀让相邻字段不可产生拼接歧义，内容无需依赖某个 JSON 库的转义或 Map 配置。
     */
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
            String providerSecret, Map<String, String> hostEnvironment) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        addSecret(values, providerSecret);
        for (ConfigurationGenerationSnapshot.McpServer server : snapshot.mcpDefinitions()) {
            if (!server.enabled()) {
                continue;
            }
            server.env().forEach((key, value) -> addSensitiveSetting(values, key, value));
            server.headers().forEach((key, value) -> addSensitiveSetting(values, key, value));
            if (server.auth().credentialId() != null) {
                addSecret(values, lease.secretFor(server.auth().credentialId()));
            }
        }
        hostEnvironment.forEach((key, value) -> addSensitiveSetting(values, key, value));
        return values.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    /**
     * 只把敏感名称对应的配置或环境值加入集合，普通变量不会导致大范围误脱敏。
     */
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

    /**
     * 忽略空 Secret，避免空串替换在投影文本的每个字符边界产生无意义标记。
     */
    private static void addSecret(Set<String> values, String value) {
        if (value != null && !value.isEmpty()) {
            values.add(value);
        }
    }

    /**
     * 按调用方指定的依赖逆序释放本次请求资源；装配失败始终保留为主异常，关闭失败只补充诊断。
     * 动态 owner 列表必须逐项尝试并聚合异常，try-with-resources 无法表达这组清理语义。
     */
    @SuppressWarnings("PMD.CloseResource") // 统一清理边界需继续关闭剩余 owner 并合并失败。
    static void closeRuntimeInputs(Throwable preparationFailure, AutoCloseable... resources) {
        Throwable failure = preparationFailure;
        for (AutoCloseable resource : resources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Throwable closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else if (failure != closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (preparationFailure != null || failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("request runtime resources failed to close", failure);
    }

    /**
     * 把用户单次输出上限、精确已知模型上限和模型上下文窗口收敛为本次请求预算；未知模型沿用用户值。
     */
    static TurnLimits limits(ConfigurationGenerationSnapshot.Provider provider,
                             ConfigurationGenerationSnapshot.Model model) {
        ConfigurationGenerationSnapshot.Capabilities capabilities = model.capabilities();
        int userMaxOutputTokens = boundedTokenCount(capabilities.maxOutputTokens(), 1_000_000);
        int knownMaxOutputTokens = ProviderOutputCapabilityCatalog.maxOutputTokens(
                provider.baseUrl(), provider.api(), model.model()).orElse(userMaxOutputTokens);
        int maxOutputTokens = Math.min(userMaxOutputTokens, knownMaxOutputTokens);
        int maxInputTokens = boundedTokenCount(
                capabilities.contextWindowTokens() - maxOutputTokens, 4_000_000);
        return new TurnLimits(maxInputTokens, maxOutputTokens,
                provider.networkTimeouts().requestTimeout());
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
                selectedModel.capabilities().supportsOutputSchema(),
                new ModelPort.GenerationOptions(null, null, maxOutputTokens,
                        upstreamReasoning(reasoningLevel, selectedModel)));
    }

    /**
     * 显式映射配置 API，运行时严格执行选定协议且绝不自动 fallback。
     */
    private static ModelPort.Api modelApi(ConfigurationGenerationSnapshot.Api api) {
        return switch (api) {
            case OPENAI_RESPONSES -> ModelPort.Api.OPENAI_RESPONSES;
            case ANTHROPIC_MESSAGES -> ModelPort.Api.ANTHROPIC_MESSAGES;
            case OPENAI_CHAT_COMPLETIONS -> ModelPort.Api.OPENAI_CHAT_COMPLETIONS;
        };
    }

    /**
     * 计算当前 Turn 可用的 Skill 名称，只有胜出发现来源与显式授权引用一致时才可用。
     * 缺失记录是设置页可移除的历史事实，不能阻断未依赖它的 Turn。
     */
    static List<String> availableSkillNames(
            List<ConfigurationGenerationSnapshot.Skill> disabled,
            List<SkillDescriptor> discovered) {
        Objects.requireNonNull(disabled, "disabled");
        Objects.requireNonNull(discovered, "discovered");
        return discovered.stream()
                .filter(discoveredSkill -> disabled.stream()
                        .noneMatch(configured -> matches(configured.reference(), discoveredSkill)))
                .map(SkillDescriptor::name)
                .toList();
    }

    /**
     * 稳定 ID 只能来自本次解析的配置代际，发现目录只证明对应名称本代际可读；两者求交后再发布，
     * 避免用展示名称反推配置身份，也不会把未启用或未发现条目暴露给消息引用。
     */
    static Map<String, String> skillNamesById(
            List<ConfigurationGenerationSnapshot.Skill> disabled,
            List<SkillDescriptor> discovered) {
        Map<String, String> identities = new java.util.LinkedHashMap<>();
        discovered.stream()
                .filter(skill -> disabled.stream().noneMatch(item -> matches(item.reference(), skill)))
                .forEach(skill -> identities.put(skill.reference(), skill.name()));
        return java.util.Collections.unmodifiableMap(identities);
    }

    /**
     * 将配置来源映射回发现来源；名称相同不足以授权，防止项目包覆盖后借用用户级许可。
     */
    private static boolean matches(
            SkillReference configured, SkillDescriptor discovered) {
        return configured.identifier().equals(discovered.reference());
    }

    /**
     * 用模型窗口与已收敛的单次输出预算生成首次窗口，动态计量由 Turn 状态机后续覆盖。
     */
    static ContextBudget contextBudget(
            ConfigurationGenerationSnapshot.Capabilities capabilities,
            ConfigurationGenerationSnapshot.Context context,
            int effectiveMaxOutputTokens) {
        return ContextBudget.capabilities(capabilities.contextWindowTokens(),
                effectiveMaxOutputTokens, context.autoCompact());
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
     * reasoning 必须属于所选模型显式能力集合；null 继续使用模型默认。枚举解析异常含原始档位，
     * RuntimeMismatchException 只允许向 conversation 暴露稳定脱敏消息。
     */
    @SuppressWarnings("PMD.PreserveStackTrace") // 原始档位可能来自持久输入，不能沿异常链越过端口边界。
    private static void validateReasoning(
            String requested, ConfigurationGenerationSnapshot.Model model) {
        if (requested == null) {
            return;
        }
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

    /**
     * 将本次请求的逻辑档位解析为模型声明的上游值；null 跟随模型默认，模型未声明默认才省略字段。
     */
    private static String upstreamReasoning(
            String requested, ConfigurationGenerationSnapshot.Model model) {
        if (requested == null) {
            ConfigurationGenerationSnapshot.ReasoningLevel defaultLevel = model.defaultReasoningLevel();
            return defaultLevel == null ? null : model.reasoningLevelMap().get(defaultLevel);
        }
        ConfigurationGenerationSnapshot.ReasoningLevel level =
                ConfigurationGenerationSnapshot.ReasoningLevel.valueOf(
                        requested.toUpperCase(java.util.Locale.ROOT));
        String upstream = model.reasoningLevelMap().get(level);
        if (upstream == null) {
            throw new IllegalArgumentException("reasoning level is unsupported");
        }
        return upstream;
    }

    /**
     * 先取得唯一配置租约，再完成全部派生；失败按依赖逆序释放并保留装配原因，
     * 只有完整 RuntimeLease 才把释放权转移给 TurnService。每次恢复解析都从当前时刻重开请求窗口，
     * 权限来自已持久化 Thread 的显式偏好；子任务能力上限仍由独立 inherited ceiling 校验。
     */
    @Override
    @SuppressWarnings("PMD.CloseResource")
    public RuntimeLease resolve(TurnRuntimeRequest request) {
        Objects.requireNonNull(request, "request");
        ShellCapability requestShell = shellFor(request.executionContext());
        Map<String, String> hostEnvironment = request.executionContext() == null ? System.getenv()
                : request.executionContext().environment();
        Optional<RuntimeSnapshot> taskRuntimeSnapshot = request.turnId() == null ? Optional.empty()
                : Optional.of(taskCeilings.read(new AgentIdentity("ja.local", request.threadId(),
                        request.turnId(), "user_local")));
        Optional<JsonObject> inheritedCeiling = taskRuntimeSnapshot.flatMap(RuntimeSnapshot::capabilityCeiling);
        Optional<RuntimeIdentity> taskIdentity = taskRuntimeSnapshot.flatMap(RuntimeSnapshot::taskIdentity);
        ConfigurationGenerationPort.Lease lease = configurations.acquire(request.workspaceRoot());
        GenerationTurnMcpSessionFactory.CatalogSnapshot ownedMcpCatalog = null;
        try {
            ConfigurationGenerationSnapshot.Provider provider =
                    lease.snapshot().requireProvider(request.providerId());
            ConfigurationGenerationSnapshot.Model selectedModel =
                    lease.snapshot().requireModel(request.providerId(), request.modelId());
            validateReasoning(request.reasoningLevel(), selectedModel);
            TurnLimits limits = limits(provider, selectedModel);
            ModelPort.ModelConfiguration model = model(
                    provider, selectedModel, request.reasoningLevel(), lease, limits.maxOutputTokens());
            SkillResolution availableSkills = skillCatalog(request.workspaceRoot(), lease);
            SkillResolution skillResolution = restrictSkills(
                    skills.applicationCatalog(), availableSkills, inheritedCeiling);
            ContextBudget contextBudget = contextBudget(
                    selectedModel.capabilities(), provider.agentDefaults().context(), limits.maxOutputTokens());
            AccessMode resolvedAccessMode = request.accessMode();
            ThreadPreferences requestPreferences = new ThreadPreferences(provider.providerId(), selectedModel.modelId(),
                    request.reasoningLevel(), resolvedAccessMode, request.collaborationMode(),
                    ThreadPreferences.TitleSource.MANUAL);
            Instant requestDeadline = requestDeadline(limits, Clock.systemUTC());
            boolean clarificationEnabled = isClarificationEnabled(
                    request, lease.snapshot().clarificationEnabled());
            JaToolRequestContext toolRequest = new JaToolRequestContext(request, requestPreferences,
                    lease.generationId(), clarificationEnabled, requestDeadline,
                    taskIdentity.map(RuntimeIdentity::kind));
            JaTool.Selection interactionRequestTools = interactionTools.toolsFor(toolRequest);
            JaTool.Selection planRequestTools = planTools.toolsFor(toolRequest);
            TaskAgentToolGateway.Tools taskRequestTools = taskTools.toolsFor(toolRequest, taskRuntimeSnapshot);
            List<JaTool.Definition> jaToolDefinitions = new ArrayList<>(
                    interactionRequestTools.definitions().size() + planRequestTools.definitions().size()
                    + taskRequestTools.definitions().size());
            jaToolDefinitions.addAll(interactionRequestTools.definitions());
            jaToolDefinitions.addAll(planRequestTools.definitions());
            jaToolDefinitions.addAll(taskRequestTools.definitions());
            Set<String> visibleJaToolNames = new LinkedHashSet<>();
            visibleJaToolNames.addAll(interactionRequestTools.visibleNames());
            visibleJaToolNames.addAll(planRequestTools.visibleNames());
            visibleJaToolNames.addAll(taskRequestTools.visibleNames());
            List<String> requestPrompts = List.of(
                    CollaborationModePrompt.forRequest(request.origin(), request.collaborationMode(),
                            clarificationEnabled),
                    interactionRequestTools.promptFragment(), planRequestTools.promptFragment());
            AgentPromptSession promptSession = promptSessions.open(new AgentPromptSessionFactory.SessionRequest(
                    request.threadId(), request.workspaceId(), request.workspaceRoot(), jaHome, lease.snapshot().trusted(),
                    promptEnvironment(request, requestShell, requestPrompts, taskIdentity), contextBudget,
                    skills.applicationCatalog(), skillResolution.catalog(), skillResolution.skillNamesById()));
            List<JaTool> builtInTools = new ArrayList<>(BuiltInTools.create(
                    request.workspaceRoot(), workspaceBoundaries, skills.applicationCatalog(), skillResolution.catalog(), promptSession,
                    attachments, mediaImporter, hostEnvironment).snapshot());
            if (requestShell.profile().isPresent()) {
                builtInTools.addAll(ProcessToolFactory.create(processes, requestShell.profile().orElseThrow()));
            }
            boolean planning = PlanToolPolicy.isReadOnlyPlanning(request.origin(), request.collaborationMode());
            boolean reviewing = request.reviewContext().isPresent();
            if (reviewing) {
                builtInTools = new ArrayList<>(ReviewReportToolFactory.createTools(
                        request.reviewContext().orElseThrow(), attachments, argumentsCodec));
            } else if (planning) {
                builtInTools = PlanReadOnlyToolCatalog.filter(builtInTools);
            }
            TurnMcpSessionFactory.Context toolContext = new TurnMcpSessionFactory.Context(
                    provider.providerId(), selectedModel.modelId(), request.workspaceRoot(),
                    requestDeadline, request.executionContext());
            GenerationTurnMcpSessionFactory.CatalogSnapshot mcpCatalog = planning || reviewing
                    ? GenerationTurnMcpSessionFactory.CatalogSnapshot.planningEmpty()
                    : mcpSessions.catalog(toolContext, lease);
            ownedMcpCatalog = mcpCatalog;
            List<JaTool.Definition> catalogDefinitions = reviewing ? List.of()
                    : planning
                        ? jaToolDefinitions.stream().filter(ConfigurationTurnRuntimeResolver::safeForPlanning).toList()
                        : List.copyOf(jaToolDefinitions);
            String catalogDigest = toolCatalogDigest(builtInTools, catalogDefinitions,
                    mcpCatalog.snapshot(), mcpCatalog.routeIdentities());
            validateInheritedCeiling(inheritedCeiling, requestPreferences, catalogDigest,
                    mcpCatalog.snapshot().revision(), taskIdentity.map(RuntimeIdentity::kind).orElse(null));
            JaToolCatalogIdentity catalogIdentity = new JaToolCatalogIdentity(
                    catalogDigest, mcpCatalog.snapshot().revision(), skillResolution.skillNamesById().keySet());
            List<JaTool> requestJaTools = catalogDefinitions.stream()
                    .map(definition -> definition.materialize(catalogIdentity)).toList();
            Set<String> registeredJaToolNames = requestJaTools.stream()
                    .map(tool -> tool.spec().name()).collect(java.util.stream.Collectors.toUnmodifiableSet());
            visibleJaToolNames.retainAll(registeredJaToolNames);
            if (planning) {
                Set<String> planAllowedNames = PlanReadOnlyToolCatalog.filter(requestJaTools).stream()
                        .map(tool -> tool.spec().name()).collect(java.util.stream.Collectors.toUnmodifiableSet());
                visibleJaToolNames.retainAll(planAllowedNames);
            }
            List<JaTool> requestTools = new ArrayList<>(builtInTools.size() + requestJaTools.size());
            requestTools.addAll(builtInTools);
            requestTools.addAll(requestJaTools);
            Set<String> visibleToolNames = new LinkedHashSet<>(builtInTools.stream()
                    .map(tool -> tool.spec().name()).toList());
            visibleToolNames.addAll(visibleJaToolNames);
            TurnToolSessionFactory toolSessions = toolSessions(mcpCatalog, planning || reviewing);
            ToolProjectionLimits outputLimits = reviewing
                    ? new ToolProjectionLimits(65_536, 65_536) : new ToolProjectionLimits(20_000, 20_000);
            List<McpGateway.McpServerStatus> dispatchStatuses = planning || reviewing
                    ? notExposedStatuses(lease.snapshot()) : mcpCatalog.serverStatuses();
            String dispatchCatalogRevision = mcpCatalog.snapshot().revision();
            String dispatchGenerationId = lease.generationId();
            String preferenceFingerprint = ThreadMcpIdentity.preferenceFingerprint(requestPreferences);
            String ceilingFingerprint = ThreadMcpIdentity.ceilingFingerprint(inheritedCeiling.orElse(null));
            Map<String, ConfigurationGenerationSnapshot.Scope> dispatchScopes = lease.snapshot().mcpDefinitions().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            ConfigurationGenerationSnapshot.McpServer::mcpId,
                            ConfigurationGenerationSnapshot.McpServer::scope));
            java.util.function.Consumer<Boolean> observeDispatch = gatewayExposed -> {
                if (request.turnId() == null) {
                    return;
                }
                List<McpGateway.McpServerStatus> observedStatuses = gatewayExposed ? dispatchStatuses
                        : dispatchStatuses.stream().map(server -> "disabled".equals(server.state())
                                                                  ? server : new McpGateway.McpServerStatus(server.serverId(), server.name(),
                        "not_exposed", null, null)).toList();
                generationCatalog.observe(new ThreadMcpCatalogPort.Observation(
                        request.threadId(), request.workspaceId(), request.workspaceRoot(),
                        dispatchGenerationId, preferenceFingerprint, ceilingFingerprint,
                        request.collaborationMode(), request.turnId(),
                        dispatchCatalogRevision, Instant.now(), observedStatuses, dispatchScopes));
            };
            GenerationTurnMcpSessionFactory.CatalogSnapshot releasedCatalog = mcpCatalog;
            AutoCloseable release = () -> closeRuntimeInputs(null, releasedCatalog, lease);
            RuntimeLease runtimeLease = new RuntimeLease(lease.generationId(), model,
                    resolvedAccessMode, request.collaborationMode(), limits,
                    List.copyOf(requestTools), Set.copyOf(visibleToolNames), toolSessions, outputLimits, promptSession, attachments,
                    presentationSecrets(lease.snapshot(), lease, model.apiKey(), hostEnvironment),
                    catalogDigest, promptSession.currentRevision(),
                    request.reasoningLevel(), release, observeDispatch);
            return runtimeLease;
        } catch (RuntimeException | Error failure) {
            closeRuntimeInputs(failure, ownedMcpCatalog, lease);
            throw failure;
        }
    }

    /**
     * 请求时间窗约束本次 Provider/Tool 安全点，不是持久 Turn 的总期限；恢复时必须从当前解析时刻重新开始。
     */
    static Instant requestDeadline(TurnLimits limits, Clock clock) {
        return Objects.requireNonNull(clock, "clock").instant()
                .plus(Objects.requireNonNull(limits, "limits").requestWindow());
    }

    /**
     * 同一客户端快照可被一个 Turn 的多个 Provider 安全点复用；弱键缓存只避免重复
     * 启动 Shell 预检进程，不延长已经结束的 Turn 或断开客户端的环境生命周期。
     */
    private ShellCapability shellFor(NativeExecutionSnapshot context) {
        if (context == null) {
            return shellCapability;
        }
        return clientShells.computeIfAbsent(context, ShellCapability::forClient);
    }

    /**
     * 业务工具说明与 Side Task 身份在同一次 resolve 中进入 System environment；身份不进入 UserContent、历史
     * 或 summary，且每个请求都重新读取持久投影，保证后续 continuation 不依赖首轮缓存。
     */
    private String promptEnvironment(
            TurnRuntimeRequest request, ShellCapability requestShell,
            List<String> requestPrompts,
            Optional<RuntimeIdentity> taskIdentity) {
        String environment = requestShell.executionEnvironment(request.workspaceRoot());
        String fragment = requestPrompts.stream().filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
        String taskFragment = taskIdentity.filter(identity -> identity.kind() == Kind.SIDE_TASK)
                .map(ConfigurationTurnRuntimeResolver::sideTaskIdentityPrompt).orElse("");
        StringBuilder result = new StringBuilder(environment);
        if (!fragment.isBlank()) {
            result.append("\n\n").append(fragment);
        }
        if (!taskFragment.isBlank()) {
            result.append("\n\n").append(taskFragment);
        }
        return result.toString();
    }

    /**
     * 预算预检只租用配置代际，不解析尚未准入的 Plan Turn 身份或生成可执行工具。
     */
    @Override
    public TurnLimits resolveLimits(TurnRuntimeRequest request) {
        Objects.requireNonNull(request, "request");
        try (ConfigurationGenerationPort.Lease lease = configurations.acquire(request.workspaceRoot())) {
            ConfigurationGenerationSnapshot.Provider provider =
                    lease.snapshot().requireProvider(request.providerId());
            ConfigurationGenerationSnapshot.Model selectedModel =
                    lease.snapshot().requireModel(request.providerId(), request.modelId());
            validateReasoning(request.reasoningLevel(), selectedModel);
            return limits(provider, selectedModel);
        }
    }

    /**
     * Workspace 生命周期只登记配置与定义 revision；短租约结束前不得启动 MCP 或读取 Tool schema。
     */
    @Override
    public void prepareWorkspace(Path workspaceRoot) {
        Path root = Objects.requireNonNull(workspaceRoot, "workspaceRoot")
                .toAbsolutePath().normalize();
        try (ConfigurationGenerationPort.Lease lease = configurations.acquire(root)) {
            generationCatalog.prepareWorkspace(root, lease);
        }
    }

    /**
     * 短时取得配置租约并返回 Owner 已成对验证的默认模型选择，不借出 snapshot 或 Secret。
     */
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
     * 依据配置中的停用引用选择本 Turn 可发现目录；正文直到 read 激活时才访问对应资源。
     *
     * <p>项目 Skill 的配置身份来自全局文档，但资源身份属于当前工作区；工作区切换后，
     * 之前启用的项目 Skill 可能自然消失，必须把它当作能力收窄而不是阻断所有模型请求。
     * 用户、Ja 和内置 Skill 则仍要求安装目录完整，避免真正的运行时损坏被静默隐藏。</p>
     */
    private SkillResolution skillCatalog(
            Path workspaceRoot, ConfigurationGenerationPort.Lease lease) {
        List<ConfigurationGenerationSnapshot.Skill> disabled = lease.snapshot().skillDefinitions();
        Set<String> disabledReferences = disabled.stream()
                .map(skill -> skill.reference().identifier()).collect(java.util.stream.Collectors.toSet());
        SkillCatalog.Catalog discovered = skills.discover(
                workspaceRoot, lease.snapshot().trusted(), disabledReferences);
        return new SkillResolution(discovered, skillNamesById(disabled, discovered.skills()));
    }

    /**
     * 延迟到 Provider 安全点 pin 同一个目录句柄；适配失败也释放新会话并保留主异常。
     */
    private TurnToolSessionFactory toolSessions(
            GenerationTurnMcpSessionFactory.CatalogSnapshot catalogSnapshot, boolean disabled) {
        Objects.requireNonNull(catalogSnapshot, "catalogSnapshot");
        if (disabled) {
            return cancellation -> new TurnToolSessionFactory.Session() {
                /** 规划阶段显式返回空 MCP 集合，保证远端工具不会进入模型目录。 */
                @Override
                public List<JaTool> tools() {
                    return List.of();
                }

                /** 空会话没有外部资源，关闭保持幂等以统一生命周期协议。 */
                @Override
                public void close() {
                }
            };
        }
        return cancellation -> {
            TurnMcpSessionFactory.Session opened = mcpSessions.open(catalogSnapshot, cancellation);
            try {
                List<JaTool> adapted = McpAgentTool.adapt(
                        opened.gateway(), opened.snapshot(), catalogSnapshot.routeIdentities(),
                        argumentsCodec, toolArgumentValidator, mediaImporter);
                return new TurnToolSessionFactory.Session() {
                    private boolean closed;

                    /** 返回本次 Provider 请求绑定的不可变 MCP Tool 目录；会话关闭后拒绝复用失效连接。 */
                    @Override
                    public List<JaTool> tools() {
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
            } catch (RuntimeException | Error failure) {
                closeRuntimeInputs(failure, opened);
                throw failure;
            }
        };
    }

    /**
     * 统一三类 Tool 的目录投影，摘要与重名检查不需要提前创建可执行适配器。
     *
     * @param spec 配置规格
     * @param sideEffect 工具副作用分类
     * @param workspaceMutationMode 工作区状态变更模式
     * @param planAccess Plan 访问约束
     * @param approvalRequirement 审批要求
     * @param bindingDescriptor 绑定描述信息
     */
    private record ToolCatalogEntry(ToolSpec spec,
                                    ToolSideEffect sideEffect,
                                    JaTool.WorkspaceMutationMode workspaceMutationMode,
                                    JaTool.PlanAccess planAccess,
                                    JaTool.ApprovalRequirement approvalRequirement,
                                    JaTool.ToolBindingDescriptor bindingDescriptor) {
        /**
         * 目录项拒绝空安全字段，避免摘要阶段把不完整声明降级为默认值。
         */
        private ToolCatalogEntry {
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(sideEffect, "sideEffect");
            Objects.requireNonNull(workspaceMutationMode, "workspaceMutationMode");
            Objects.requireNonNull(planAccess, "planAccess");
            Objects.requireNonNull(approvalRequirement, "approvalRequirement");
            Objects.requireNonNull(bindingDescriptor, "bindingDescriptor");
            if (!spec.name().equals(bindingDescriptor.localName())) {
                throw new IllegalArgumentException("Tool binding name does not match catalog entry");
            }
        }
    }

    /**
     * 同步携带筛选后的目录与配置身份表，防止两个 Prompt 输入来自不同发现结果。
     *
     * @param catalog 目录
     * @param skillNamesById 按 Skill 标识索引的名称
     */
    record SkillResolution(SkillCatalog.Catalog catalog,
                           Map<String, String> skillNamesById) {
        /**
         * 防御性复制两项同源结果，Resolver 后续组装不能替换其中任一集合。
         */
        SkillResolution {
            catalog = Objects.requireNonNull(catalog, "catalog");
            skillNamesById = Map.copyOf(Objects.requireNonNull(skillNamesById, "skillNamesById"));
        }
    }
}

