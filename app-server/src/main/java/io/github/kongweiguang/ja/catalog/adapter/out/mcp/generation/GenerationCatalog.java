// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.runtime.McpRuntime;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.runtime.McpToolCatalog;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.session.McpSessionFactory;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.session.SdkMcpSessionFactory;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpLimits;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpServerDefinition;
import io.github.kongweiguang.ja.catalog.adapter.out.skills.JaSkillSources;
import io.github.kongweiguang.ja.catalog.domain.McpServerDescriptor;
import io.github.kongweiguang.ja.catalog.domain.McpToolDescriptor;
import io.github.kongweiguang.ja.catalog.domain.SkillDescriptor;
import io.github.kongweiguang.ja.catalog.port.out.CatalogQueryPort;
import io.github.kongweiguang.ja.catalog.port.out.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.catalog.port.out.ThreadMcpCatalogPort;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationGenerationSnapshot;
import io.github.kongweiguang.ja.conversation.domain.NativeExecutionSnapshot;
import io.github.kongweiguang.ja.conversation.port.out.McpGateway;
import io.github.kongweiguang.ja.conversation.port.out.SkillCatalog;
import io.github.kongweiguang.ja.foundation.json.JacksonJsonValues;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.workspace.adapter.out.filesystem.WorkspaceBoundary;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 不持有可变 active Skill 正文的配置代际作用域 Skill/MCP 目录。
 *
 * <p>每次 Settings 调用都从传入的不可变代际重建 Descriptor 投影。
 * 工作区发现只按代际标识与规范 cwd 缓存非 Secret MCP Schema；启动定义在捕获时从活动 Turn 租约重建。</p>
 */
public final class GenerationCatalog implements CatalogQueryPort, ThreadMcpCatalogPort, AutoCloseable {
    private static final int MAXIMUM_PAGE = 200;
    private static final int MAXIMUM_WORKSPACE_CATALOGS = 32;
    private static final int MAXIMUM_THREAD_MCP_OBSERVATIONS = 512;

    private final ObjectMapper objectMapper;
    private final McpLimits limits;
    private final Path stdioWorkingDirectory;
    private final McpSessionFactory sessionFactory;
    private final SkillCatalog skillSources;
    private final Path agentsSkillRoot;
    private final Path jaSkillRoot;
    private final ConcurrentHashMap<Path, WorkspaceRegistration> workspaces = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ServiceKey, McpServiceDirectory> serviceDirectories =
            new ConcurrentHashMap<>();
    private final Map<String, StoredObservation> threadObservations =
            new LinkedHashMap<>(16, 0.75f, true);
    private final ExecutorService probes = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("ja-generation-mcp-", 0).factory());
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 创建代际目录但不读取配置或打开 MCP 传输，避免构造器产生外部副作用。
     */
    public GenerationCatalog(ObjectMapper objectMapper, McpLimits limits) {
        this(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(), objectMapper, limits,
                new JaSkillSources(), Path.of(System.getProperty("user.home"), ".agents", "skills"),
                Path.of(System.getProperty("user.home"), ".ja", "skills"));
    }

    /**
     * 绑定 Java 所有的 home/general 工作区，供仅 Settings 使用的 MCP 探测。
     */
    public GenerationCatalog(Path stdioWorkingDirectory, ObjectMapper objectMapper, McpLimits limits) {
        this(stdioWorkingDirectory, objectMapper, limits, new JaSkillSources(),
                Path.of(System.getProperty("user.home"), ".agents", "skills"),
                Path.of(System.getProperty("user.home"), ".ja", "skills"));
    }

    /**
     * 生产组合显式复用 Turn 的 Skill 发现/实时读取器和两类默认用户根，避免 Settings 形成第二套规则。
     */
    public GenerationCatalog(Path stdioWorkingDirectory, ObjectMapper objectMapper, McpLimits limits,
                             SkillCatalog skillSources, Path agentsSkillRoot, Path jaSkillRoot) {
        this(stdioWorkingDirectory, objectMapper, limits, new SdkMcpSessionFactory(objectMapper, limits),
                skillSources, agentsSkillRoot, jaSkillRoot);
    }

    /**
     * 测试接缝只替换 MCP 传输创建，配置代际所有权仍保持生产结构。
     */
    GenerationCatalog(Path stdioWorkingDirectory, ObjectMapper objectMapper, McpLimits limits,
                      McpSessionFactory sessionFactory) {
        this(stdioWorkingDirectory, objectMapper, limits, sessionFactory, new JaSkillSources(),
                Path.of(System.getProperty("user.home"), ".agents", "skills"),
                Path.of(System.getProperty("user.home"), ".ja", "skills"));
    }

    /**
     * 完整测试接缝允许隔离 MCP 传输和 Skill 来源，但仍固定所有规范绝对根目录。
     */
    GenerationCatalog(Path stdioWorkingDirectory, ObjectMapper objectMapper, McpLimits limits,
                      McpSessionFactory sessionFactory, SkillCatalog skillSources,
                      Path agentsSkillRoot, Path jaSkillRoot) {
        this.stdioWorkingDirectory = Objects.requireNonNull(stdioWorkingDirectory, "stdioWorkingDirectory")
                .toAbsolutePath().normalize();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.limits = Objects.requireNonNull(limits, "limits");
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.skillSources = Objects.requireNonNull(skillSources, "skillSources");
        this.agentsSkillRoot = Objects.requireNonNull(agentsSkillRoot, "agentsSkillRoot")
                .toAbsolutePath().normalize();
        this.jaSkillRoot = Objects.requireNonNull(jaSkillRoot, "jaSkillRoot")
                .toAbsolutePath().normalize();
    }

    /** Workspace prepare 只登记非 Secret 配置代际，实际定义与凭据延迟到 Provider 安全点解析。 */
    public void prepareWorkspace(Path workspaceRoot, ConfigurationGenerationPort.Lease lease) {
        Objects.requireNonNull(lease, "lease");
        ConfigurationGenerationSnapshot generation = lease.snapshot();
        Path workspace = new WorkspaceBoundary(workspaceRoot).root();
        String generationId = generation.generationId();
        workspaces.compute(workspace, (ignored, previous) -> {
            if (previous != null && previous.generationId().equals(generationId)) return previous;
            serviceDirectories.forEach((key, directory) -> {
                if (key.workspaceRoot().equals(workspace)) directory.retire(false);
            });
            return new WorkspaceRegistration(generationId, Map.of());
        });
        if (workspaces.size() > MAXIMUM_WORKSPACE_CATALOGS) {
            workspaces.keySet().stream().sorted(Comparator.comparing(Path::toString))
                    .limit(workspaces.size() - MAXIMUM_WORKSPACE_CATALOGS)
                    .forEach(workspaces::remove);
        }
    }

    /**
     * Provider 安全点逐服务懒发现并聚合健康目录；客户端冻结环境使用请求私有服务，
     * 只有原生 stdio 合同的后台环境沿用代际缓存，防止相同配置跨客户端复用进程。
     */
    @SuppressWarnings("PMD.CloseResource")
    TurnCatalog capture(ConfigurationGenerationPort.Lease lease,
                         ConfigurationGenerationSnapshot.AgentDefaults defaults, Path workspaceRoot,
                         NativeExecutionSnapshot executionContext) {
        ConfigurationGenerationSnapshot generation = lease.snapshot();
        Path workspace = new WorkspaceBoundary(workspaceRoot).root();
        List<ConfigurationGenerationSnapshot.McpServer> enabled = generation.mcpDefinitions().stream()
                .filter(ConfigurationGenerationSnapshot.McpServer::enabled).toList();
        if (enabled.isEmpty()) {
            retireUnselected(workspace, Map.of());
            workspaces.put(workspace, new WorkspaceRegistration(generation.generationId(), Map.of()));
            McpGateway.McpSnapshot empty = McpRuntime.catalogSnapshot(List.of(), List.of(), objectMapper, Instant.EPOCH);
            List<McpGateway.McpServerStatus> disabled = generation.mcpDefinitions().stream()
                    .sorted(Comparator.comparing(ConfigurationGenerationSnapshot.McpServer::mcpId))
                    .map(server -> new McpGateway.McpServerStatus(
                            server.mcpId(), server.name(), "disabled", null, null)).toList();
            return new TurnCatalog(empty, Map.of(), Map.of(), disabled, List.of());
        }
        Map<String, McpGateway.McpServerStatus> serverStatuses = new LinkedHashMap<>();
        generation.mcpDefinitions().stream().filter(server -> !server.enabled())
                .sorted(Comparator.comparing(ConfigurationGenerationSnapshot.McpServer::mcpId))
                .forEach(server -> serverStatuses.put(server.mcpId(), new McpGateway.McpServerStatus(
                        server.mcpId(), server.name(), "disabled", null, null)));
        List<McpServerDefinition> selected = new ArrayList<>();
        for (ConfigurationGenerationSnapshot.McpServer server : enabled) {
            try {
                selected.add(GenerationMcpDefinitionFactory.create(server, workspace, lease));
            } catch (RuntimeException invalidDefinition) {
                /* 一份服务定义或凭据损坏只隔离该服务，其他 MCP 服务仍进入同一 Provider 目录。 */
                serverStatuses.put(server.mcpId(), new McpGateway.McpServerStatus(
                        server.mcpId(), server.name(), "unavailable", null, "CONFIGURATION_INVALID"));
            }
        }
        List<McpServiceDirectory> privateServices = new ArrayList<>();
        try {
        Map<String, String> selectedRevisions = selected.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        McpServerDefinition::id, McpServerDefinition::definitionRevision));
        retireUnselected(workspace, selectedRevisions);
        Map<String, McpServiceDirectory> capturedServices = new LinkedHashMap<>();
        List<McpGateway.McpTool> tools = new ArrayList<>();
        for (McpServerDefinition definition : selected) {
            ConfigurationGenerationSnapshot.McpServer configured = enabled.stream()
                    .filter(server -> server.mcpId().equals(definition.id())).findFirst().orElseThrow();
            McpServiceDirectory privateDirectory = null;
            try {
                retireSuperseded(workspace, definition, true);
                McpServiceDirectory directory;
                if (executionContext == null) {
                    ServiceKey key = new ServiceKey(workspace, definition.id(), definition.definitionRevision());
                    directory = serviceDirectories.computeIfAbsent(key,
                            ignored -> new McpServiceDirectory(
                                    definition, limits, objectMapper, sessionFactory, System.getenv()));
                } else {
                    // 原生客户端目录不进入跨 Turn 全局缓存，断线中的 Turn 仍持有本次环境，
                    // 终态由 RuntimeLease 关闭；另一客户端即使使用相同配置也会建独立 stdio。
                    privateDirectory = new McpServiceDirectory(
                            definition, limits, objectMapper, sessionFactory,
                            executionContext.environment());
                    privateServices.add(privateDirectory);
                    directory = privateDirectory;
                }
                capturedServices.put(definition.id(), directory);
                List<McpGateway.McpTool> serverTools = directory.snapshot().tools();
                boolean failed = directory.runtime().unavailableServerIds().contains(definition.id());
                serverStatuses.put(definition.id(), new McpGateway.McpServerStatus(
                        definition.id(), configured.name(), failed ? "unavailable" : "available",
                        failed ? null : serverTools.size(), failed ? "DISCOVERY_FAILED" : null));
                tools.addAll(serverTools);
            } catch (RuntimeException isolatedFailure) {
                if (privateDirectory != null) {
                    privateServices.remove(privateDirectory);
                    try {
                        privateDirectory.close();
                    } catch (RuntimeException cleanupFailure) {
                        isolatedFailure.addSuppressed(cleanupFailure);
                        throw new IllegalStateException("mcp_private_cleanup_failed", isolatedFailure);
                    }
                }
                /* Session owner 或并发退休故障与远端发现同样只让本服务不可用，不吞掉其他目录。 */
                tools.removeIf(tool -> tool.serverId().equals(definition.id()));
                serverStatuses.put(definition.id(), new McpGateway.McpServerStatus(
                        definition.id(), configured.name(), "unavailable", null, "DISCOVERY_FAILED"));
            }
        }
        workspaces.put(workspace, new WorkspaceRegistration(generation.generationId(), selectedRevisions));
        McpGateway.McpSnapshot snapshot = McpRuntime.catalogSnapshot(tools, selected, objectMapper, Instant.now());
        Map<String, McpGateway.RouteIdentity> identities =
                McpToolCatalog.routeIdentities(snapshot, selected.stream().collect(
                        java.util.stream.Collectors.toUnmodifiableMap(
                                McpServerDefinition::id, java.util.function.Function.identity())), objectMapper);
        List<McpGateway.McpServerStatus> orderedStatuses = generation.mcpDefinitions().stream()
                .sorted(Comparator.comparing(ConfigurationGenerationSnapshot.McpServer::mcpId))
                .map(server -> serverStatuses.get(server.mcpId())).toList();
        return new TurnCatalog(snapshot, identities, capturedServices, orderedStatuses,
                List.copyOf(privateServices));
        } catch (RuntimeException failure) {
            try {
                closePrivateServices(privateServices);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** 捕获失败时逐个收口已启动的客户端私有进程，单个 close 故障不能跳过其它服务。 */
    @SuppressWarnings("PMD.CloseResource") // 每项都显式 close；try-with-resources 会在首个失败后漏掉后续项。
    private static void closePrivateServices(List<McpServiceDirectory> directories) {
        RuntimeException first = null;
        for (McpServiceDirectory directory : directories) {
            try {
                directory.close();
            } catch (RuntimeException failure) {
                if (first == null) first = new IllegalStateException("mcp_private_cleanup_failed");
                first.addSuppressed(failure);
            }
        }
        if (first != null) throw first;
    }

    /** 只读取最近发布的内存会话目录，不初始化或刷新服务。 */
    @Override
    public Optional<ThreadMcpCatalogPort.Observation> observation(String threadId) {
        Objects.requireNonNull(threadId, "threadId");
        synchronized (threadObservations) {
            return Optional.ofNullable(threadObservations.get(threadId)).map(StoredObservation::observation);
        }
    }

    /** 每个会话只保留最近一次有界且脱敏的派发快照，避免观测缓存无限增长。 */
    @Override
    public void observe(ThreadMcpCatalogPort.Observation observation) {
        Objects.requireNonNull(observation, "observation");
        if (closed.get()) return;
        Map<String, McpServiceDirectory.DirectoryVersion> versions = captureServiceVersions(observation);
        synchronized (threadObservations) {
            threadObservations.put(observation.threadId(), new StoredObservation(observation, versions));
            while (threadObservations.size() > MAXIMUM_THREAD_MCP_OBSERVATIONS) {
                String eldest = threadObservations.keySet().iterator().next();
                threadObservations.remove(eldest);
            }
        }
    }

    /** 同代际服务收到目录变更或更新的探测结果后，使旧观测失效。 */
    @Override
    public boolean current(ThreadMcpCatalogPort.Observation observation) {
        Objects.requireNonNull(observation, "observation");
        StoredObservation stored;
        synchronized (threadObservations) {
            stored = threadObservations.get(observation.threadId());
        }
        if (stored == null || !stored.observation().equals(observation)) return false;
        WorkspaceRegistration registration = workspaces.get(observation.workspaceRoot());
        if (registration == null) return stored.serviceVersions().isEmpty();
        if (!registration.generationId().equals(observation.generationId())) return false;
        for (Map.Entry<String, McpServiceDirectory.DirectoryVersion> entry
                : stored.serviceVersions().entrySet()) {
            String definitionRevision = registration.definitionRevisions().get(entry.getKey());
            if (definitionRevision == null) return false;
            ServiceKey key = new ServiceKey(observation.workspaceRoot(), entry.getKey(), definitionRevision);
            McpServiceDirectory.DirectoryVersion current = Optional.ofNullable(serviceDirectories.get(key))
                    .map(McpServiceDirectory::version)
                    .orElse(null);
            if (current == null) return false;
            if ((current.dirty() && !current.lastDiscoveryFailed())
                    || current.lastDiscoveryFailed() != entry.getValue().lastDiscoveryFailed()
                    || !Objects.equals(current.revision(), entry.getValue().revision())) return false;
        }
        return true;
    }

    /** 仅捕获缓存中的服务修订，让读取操作无需连接 MCP 也能识别变化。 */
    private Map<String, McpServiceDirectory.DirectoryVersion> captureServiceVersions(
            ThreadMcpCatalogPort.Observation observation) {
        WorkspaceRegistration registration = workspaces.get(observation.workspaceRoot());
        if (registration == null || !registration.generationId().equals(observation.generationId())) {
            return Map.of();
        }
        Set<String> observedServices = observation.servers().stream()
                .filter(server -> !Set.of("disabled", "not_exposed").contains(server.state()))
                .map(McpGateway.McpServerStatus::serverId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, McpServiceDirectory.DirectoryVersion> versions = new LinkedHashMap<>();
        registration.definitionRevisions().forEach((serverId, revision) -> {
            if (!observedServices.contains(serverId)) return;
            ServiceKey key = new ServiceKey(observation.workspaceRoot(), serverId, revision);
            Optional.ofNullable(serviceDirectories.get(key))
                    .map(McpServiceDirectory::version)
                    .ifPresent(version -> versions.put(serverId, version));
        });
        return Map.copyOf(versions);
    }

    /**
     * 同一工作区和 serverId 只保留当前 definitionRevision；旧 batch pin 会把实际关闭延迟到结算后。
     */
    private void retireSuperseded(
            Path workspace, McpServerDefinition current, boolean closeWhenIdle) {
        serviceDirectories.forEach((key, directory) -> {
            if (key.workspaceRoot().equals(workspace)
                && key.serverId().equals(current.id())
                && !key.definitionRevision().equals(current.definitionRevision())) {
                if (serviceDirectories.remove(key, directory)) {
                    directory.retire(closeWhenIdle);
                }
            }
        });
    }

    /**
     * Provider 安全点回收已禁用或定义已改变的空闲服务；被旧 batch pin 的 owner 延迟到 release。
     */
    private void retireUnselected(Path workspace, Map<String, String> selectedRevisions) {
        serviceDirectories.forEach((key, directory) -> {
            if (key.workspaceRoot().equals(workspace)
                && !Objects.equals(selectedRevisions.get(key.serverId()), key.definitionRevision())
                && serviceDirectories.remove(key, directory)) {
                directory.retire(true);
            }
        });
    }

    /**
     * 发现四类默认来源的元数据后按名称合并当前配置；未登记项不会被自动授权或读取正文。
     */
    @Override
    public CursorPage<SkillDescriptor> listSkills(
            ConfigurationGenerationPort.Lease lease, Path workspaceRoot, boolean workspaceTrusted,
            String cursor, int limit) {
        ConfigurationGenerationSnapshot generation = lease.snapshot();
        Path discoveryRoot = workspaceRoot == null ? stdioWorkingDirectory : workspaceRoot;
        List<SkillCatalog.SkillDescriptor> discovered = skillSources.discoverAll(new SkillCatalog.DiscoveryRequest(
                discoveryRoot, agentsSkillRoot, jaSkillRoot,
                workspaceRoot != null && workspaceTrusted && generation.trusted()));
        Set<String> disabled = configuredReferences(generation);
        List<SkillDescriptor> values = discovered.stream()
                .filter(skill -> skill.source() != SkillCatalog.Source.BUNDLED)
                .map(skill -> skillDescriptor(skill, disabled))
                .sorted(Comparator.comparing(SkillDescriptor::skillId)).toList();
        return page(values, cursor, limit, SkillDescriptor::skillId);
    }

    /**
     * 停用事实必须与发现项的来源和名称同时匹配，不能因同名覆盖扩散到其它来源。
     */
    private static Set<String> configuredReferences(
            ConfigurationGenerationSnapshot generation) {
        return generation.skillDefinitions().stream()
                .map(skill -> skill.reference().identifier())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Settings 的稳定身份就是来源限定引用；未登记发现项默认开启，不复制磁盘正文。
     */
    private static SkillDescriptor skillDescriptor(
            SkillCatalog.SkillDescriptor discovered, Set<String> disabled) {
        String scope = scope(discovered.source());
        String skillId = scope + ":" + discovered.name();
        return new SkillDescriptor(skillId, discovered.name(), scope, !disabled.contains(skillId),
                "healthy", discovered.description());
    }

    /**
     * 将可授权来源映射为 Settings 身份前缀；内置来源没有 v2 配置身份，已在调用方过滤。
     */
    private static String scope(SkillCatalog.Source source) {
        return switch (source) {
            case BUNDLED -> throw new IllegalArgumentException("bundled skills are not configurable");
            case AGENTS_USER -> "user";
            case JA_USER -> "ja";
            case WORKSPACE -> "project";
        };
    }

    /**
     * 直接从代际列出脱敏 MCP 状态，不保留 endpoint 或认证信息。
     */
    @Override
    public CursorPage<McpServerDescriptor> listMcp(
            ConfigurationGenerationPort.Lease lease, String cursor, int limit) {
        ConfigurationGenerationSnapshot generation = lease.snapshot();
        List<McpServerDescriptor> values = generation.mcpDefinitions().stream()
                .sorted(Comparator.comparing(ConfigurationGenerationSnapshot.McpServer::mcpId))
                .map(server -> new McpServerDescriptor(server.mcpId(), server.name(),
                        scope(server),
                        server.transport() == ConfigurationGenerationSnapshot.Transport.STDIO
                                ? "stdio" : "streamable_http",
                        server.enabled() ? "configured" : "disabled", 0)).toList();
        return page(values, cursor, limit, McpServerDescriptor::mcpId);
    }

    /**
     * 执行代际所有的有界 initialize/tools 探测，并在完成后丢弃含 Secret 的定义。
     */
    @Override
    public CompletionStage<McpServerDescriptor> testMcp(
            ConfigurationGenerationPort.Lease lease, Path workspaceRoot, String mcpId) {
        ConfigurationGenerationSnapshot.McpServer server = lease.snapshot().requireMcp(mcpId);
        if (!server.enabled()) {
            return CompletableFuture.completedFuture(new McpServerDescriptor(server.mcpId(), server.name(),
                    scope(server), transport(server), "disabled", 0));
        }
        return CompletableFuture.supplyAsync(() -> {
            List<McpServerDefinition> definitions = List.of(GenerationMcpDefinitionFactory.create(
                    server, workspaceRoot == null ? stdioWorkingDirectory : workspaceRoot, lease));
            try (McpRuntime runtime = new McpRuntime(definitions, limits, objectMapper, sessionFactory)) {
                McpGateway.McpSnapshot snapshot = runtime.snapshot();
                if (runtime.unavailableServerIds().contains(mcpId)) {
                    return new McpServerDescriptor(server.mcpId(), server.name(), scope(server), transport(server),
                            "unavailable", 0);
                }
                int tools = (int) snapshot.tools().stream()
                        .filter(tool -> tool.serverId().equals(mcpId)).count();
                return new McpServerDescriptor(server.mcpId(), server.name(), scope(server), transport(server),
                        "available", tools);
            } catch (RuntimeException failure) {
                return new McpServerDescriptor(server.mcpId(), server.name(), scope(server), transport(server),
                        "unavailable", 0);
            }
        }, probes);
    }

    /**
     * 读取一个代际所有的有界 Tool Schema 页面，不缓存携带 Secret 的定义。
     */
    @Override
    public CursorPage<McpToolDescriptor> readMcpTools(
            ConfigurationGenerationPort.Lease lease, Path workspaceRoot, String mcpId, String cursor, int limit) {
        ConfigurationGenerationSnapshot.McpServer server = lease.snapshot().requireMcp(mcpId);
        if (!server.enabled()) return new CursorPage<>(List.of(), null);
        List<McpServerDefinition> definitions = List.of(GenerationMcpDefinitionFactory.create(server,
                workspaceRoot == null ? stdioWorkingDirectory : workspaceRoot, lease));
        try (McpRuntime runtime = new McpRuntime(definitions, limits, objectMapper, sessionFactory)) {
            McpGateway.McpSnapshot snapshot = runtime.snapshot();
            if (runtime.unavailableServerIds().contains(mcpId)) {
                throw new IllegalStateException("mcp_tools_unavailable");
            }
            List<McpToolDescriptor> values = snapshot.tools().stream()
                    .map(tool -> new McpToolDescriptor(tool.spec().name(), tool.spec().description(),
                            JacksonJsonValues.toNode(objectMapper, tool.spec().inputSchema()).toString()))
                    .toList();
            return page(values, cursor, limit, McpToolDescriptor::name);
        }
    }

    /**
     * 停止探测准入并只释放 Schema 缓存；活动 Turn Runtime 仍自行负责关闭。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        workspaces.clear();
        synchronized (threadObservations) {
            threadObservations.clear();
        }
        List<RuntimeException> failures = new ArrayList<>();
        serviceDirectories.values().forEach(directory -> {
            try {
                directory.close();
            } catch (RuntimeException failure) {
                failures.add(failure);
            }
        });
        serviceDirectories.clear();
        probes.shutdownNow();
        try {
            probes.awaitTermination(limits.closeTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!failures.isEmpty()) {
            IllegalStateException failure = new IllegalStateException("mcp_catalog_close_failed");
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
    }

    /** 设置页来源和会话来源复用同一冻结配置事实。 */
    private static String scope(ConfigurationGenerationSnapshot.McpServer server) {
        return server.scope() == ConfigurationGenerationSnapshot.Scope.PROJECT ? "project" : "global";
    }

    /** 返回脱敏 Settings 投影使用的封闭传输词汇。 */
    private static String transport(ConfigurationGenerationSnapshot.McpServer server) {
        return server.transport() == ConfigurationGenerationSnapshot.Transport.STDIO
                ? "stdio" : "streamable_http";
    }

    /**
     * 对所有代际所有的 Descriptor 投影应用有界键集分页。
     */
    private static <T> CursorPage<T> page(List<T> values, String cursor, int limit,
                                          java.util.function.Function<T, String> identity) {
        if (limit < 1 || limit > MAXIMUM_PAGE) throw new IllegalArgumentException("catalog_page_limit_invalid");
        String after = decodeCursor(cursor);
        int start = 0;
        if (after != null) {
            while (start < values.size() && identity.apply(values.get(start)).compareTo(after) <= 0) start++;
        }
        int end = Math.min(values.size(), start + limit);
        String next = end < values.size() ? encodeCursor(identity.apply(values.get(end - 1))) : null;
        return new CursorPage<>(values.subList(start, end), next);
    }

    /**
     * 只把上一页最后一个 Descriptor 身份编码为不透明 Cursor。
     */
    private static String encodeCursor(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 拒绝格式错误或超长 Cursor，且错误信息不得回显调用方输入。
     */
    private static String decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        if (cursor.length() > 256) throw new IllegalArgumentException("catalog_cursor_invalid");
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("catalog_cursor_invalid");
        }
    }

    /**
     * 以代际标识与规范 cwd 隔离配置或信任更新前后的 Schema 缓存。
     */
    private record WorkspaceRegistration(String generationId, Map<String, String> definitionRevisions) {
        /**
         * 只保存脱敏修订，不保留定义或 Secret，保证工作区登记仍是轻量状态操作。
         */
        private WorkspaceRegistration {
            Objects.requireNonNull(generationId, "generationId");
            definitionRevisions = Map.copyOf(definitionRevisions);
        }
    }

    /**
     * 服务 owner 的最小稳定键覆盖工作区、服务身份和完整定义修订。
     */
    private record ServiceKey(Path workspaceRoot, String serverId, String definitionRevision) {
    }

    /** 观测仅保留不透明身份和不含密钥的服务修订，限制缓存内容。 */
    private record StoredObservation(ThreadMcpCatalogPort.Observation observation,
                                     Map<String, McpServiceDirectory.DirectoryVersion> serviceVersions) {
        /** 防御性复制使共享服务目录推进时不会改变已保存的观测。 */
        private StoredObservation {
            Objects.requireNonNull(observation, "observation");
            serviceVersions = Map.copyOf(serviceVersions);
        }
    }

    /**
     * 仅供租约绑定 MCP Session Factory 消费的包内交接值；私有服务的终态关闭权
     * 由返回的 RuntimeLease 在终态退休，绝不进入全局代际缓存。
     */
    record TurnCatalog(
            McpGateway.McpSnapshot snapshot,
            Map<String, McpGateway.RouteIdentity> routeIdentities,
            Map<String, McpServiceDirectory> services,
            List<McpGateway.McpServerStatus> serverStatuses,
            List<McpServiceDirectory> privateServices) {
        /**
         * 防御性复制单次 Provider 请求安全点选中的路由与 Schema 投影。
         */
        TurnCatalog {
            Objects.requireNonNull(snapshot, "snapshot");
            routeIdentities = Map.copyOf(routeIdentities);
            services = Map.copyOf(services);
            serverStatuses = List.copyOf(serverStatuses);
            privateServices = List.copyOf(privateServices);
        }
    }
}
