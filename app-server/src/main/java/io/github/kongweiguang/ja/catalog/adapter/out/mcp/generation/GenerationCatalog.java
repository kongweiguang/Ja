// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.runtime.McpRuntime;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.session.McpSessionFactory;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.session.SdkMcpSessionFactory;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpLimits;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpServerDefinition;
import io.github.kongweiguang.ja.catalog.domain.McpServerDescriptor;
import io.github.kongweiguang.ja.catalog.domain.McpToolDescriptor;
import io.github.kongweiguang.ja.catalog.domain.SkillDescriptor;
import io.github.kongweiguang.ja.catalog.port.out.CatalogQueryPort;
import io.github.kongweiguang.ja.catalog.port.out.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationGenerationSnapshot;
import io.github.kongweiguang.ja.conversation.port.out.McpGateway;
import io.github.kongweiguang.ja.foundation.json.JacksonJsonValues;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.workspace.adapter.out.filesystem.WorkspaceBoundary;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 不持有可变 active 快照的配置代际作用域 Skill/MCP 目录。
 *
 * <p>每次 Settings 调用都从传入的不可变代际重建 Descriptor 投影。
 * 工作区发现只按代际标识与规范 cwd 缓存非 Secret MCP Schema；启动定义在捕获时从活动 Turn 租约重建。</p>
 */
public final class GenerationCatalog implements CatalogQueryPort, AutoCloseable {
    private static final int MAXIMUM_PAGE = 200;
    private static final int MAXIMUM_WORKSPACE_CATALOGS = 32;

    private final ObjectMapper objectMapper;
    private final McpLimits limits;
    private final Path stdioWorkingDirectory;
    private final McpSessionFactory sessionFactory;
    private final ConcurrentHashMap<WorkspaceKey, WorkspaceCatalog> workspaces = new ConcurrentHashMap<>();
    private final ExecutorService probes = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("ja-generation-mcp-", 0).factory());
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 创建代际目录但不读取配置或打开 MCP 传输，避免构造器产生外部副作用。
     */
    public GenerationCatalog(ObjectMapper objectMapper, McpLimits limits) {
        this(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(), objectMapper, limits);
    }

    /**
     * 绑定 Java 所有的 home/general 工作区，供仅 Settings 使用的 MCP 探测。
     */
    public GenerationCatalog(Path stdioWorkingDirectory, ObjectMapper objectMapper, McpLimits limits) {
        this(stdioWorkingDirectory, objectMapper, limits, new SdkMcpSessionFactory(objectMapper, limits));
    }

    /**
     * 测试接缝只替换 MCP 传输创建，配置代际所有权仍保持生产结构。
     */
    GenerationCatalog(Path stdioWorkingDirectory, ObjectMapper objectMapper, McpLimits limits,
                      McpSessionFactory sessionFactory) {
        this.stdioWorkingDirectory = Objects.requireNonNull(stdioWorkingDirectory, "stdioWorkingDirectory")
                .toAbsolutePath().normalize();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.limits = Objects.requireNonNull(limits, "limits");
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    }

    /**
     * 发现一个工作区目录，并且只缓存此代际的非 Secret Tool Schema。
     */
    public void prepareWorkspace(Path workspaceRoot, ConfigurationGenerationPort.Lease lease) {
        Objects.requireNonNull(lease, "lease");
        ConfigurationGenerationSnapshot generation = lease.snapshot();
        Path workspace = new WorkspaceBoundary(workspaceRoot).root();
        List<McpServerDefinition> definitions = definitions(generation, lease, workspace);
        McpGateway.McpSnapshot snapshot;
        try (McpRuntime runtime = new McpRuntime(definitions, limits, objectMapper, sessionFactory)) {
            snapshot = runtime.snapshot();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("mcp_workspace_refresh_failed");
        }
        WorkspaceKey key = new WorkspaceKey(generation.generationId(), workspace);
        workspaces.put(key, new WorkspaceCatalog(snapshot, snapshot.createdAt()));
        if (workspaces.size() > MAXIMUM_WORKSPACE_CATALOGS) {
            workspaces.keySet().stream().sorted(Comparator.comparing(WorkspaceKey::generationId))
                    .limit(workspaces.size() - MAXIMUM_WORKSPACE_CATALOGS)
                    .forEach(workspaces::remove);
        }
    }

    /**
     * 从活动租约与缓存 Schema 捕获选中定义，不进行全局安装。
     */
    TurnCatalog capture(ConfigurationGenerationPort.Lease lease,
                         ConfigurationGenerationSnapshot.AgentDefaults defaults, Path workspaceRoot) {
        ConfigurationGenerationSnapshot generation = lease.snapshot();
        List<ConfigurationGenerationSnapshot.McpServer> enabled = generation.mcpDefinitions().stream()
                .filter(ConfigurationGenerationSnapshot.McpServer::enabled).toList();
        if (enabled.isEmpty()) {
            return new TurnCatalog(List.of(), McpRuntime.catalogSnapshot(List.of(), objectMapper, Instant.EPOCH));
        }
        Path workspace = new WorkspaceBoundary(workspaceRoot).root();
        WorkspaceCatalog catalog = workspaces.get(new WorkspaceKey(generation.generationId(), workspace));
        if (catalog == null) throw new IllegalStateException("mcp_workspace_catalog_missing");
        Set<String> selectedIds = enabled.stream().map(ConfigurationGenerationSnapshot.McpServer::mcpId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<McpServerDefinition> selected = new ArrayList<>();
        for (ConfigurationGenerationSnapshot.McpServer server : enabled) {
            selected.add(GenerationMcpDefinitionFactory.create(server, workspace, lease));
        }
        List<McpGateway.McpTool> tools = catalog.snapshot().tools().stream()
                .filter(tool -> selectedIds.contains(tool.serverId())).toList();
        return new TurnCatalog(selected, McpRuntime.catalogSnapshot(tools, objectMapper, catalog.refreshedAt()));
    }

    /**
     * 从调用方精确代际列出 Skill Descriptor，禁止读取可变目录字段。
     */
    @Override
    public CursorPage<SkillDescriptor> listSkills(
            ConfigurationGenerationPort.Lease lease, String cursor, int limit) {
        List<SkillDescriptor> values = lease.snapshot().skillDefinitions().stream()
                .map(skill -> new SkillDescriptor(skill.skillId(), skill.name(), skill.scope(),
                        skill.enabled(), skill.enabled() ? "available" : "disabled", skill.description()))
                .sorted(Comparator.comparing(SkillDescriptor::skillId)).toList();
        return page(values, cursor, limit, SkillDescriptor::skillId);
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
            ConfigurationGenerationPort.Lease lease, String mcpId) {
        ConfigurationGenerationSnapshot.McpServer server = lease.snapshot().requireMcp(mcpId);
        if (!server.enabled()) {
            return CompletableFuture.completedFuture(new McpServerDescriptor(server.mcpId(), server.name(),
                    transport(server), "disabled", 0));
        }
        return CompletableFuture.supplyAsync(() -> {
            List<McpServerDefinition> definitions = List.of(GenerationMcpDefinitionFactory.create(
                    server, stdioWorkingDirectory, lease));
            try (McpRuntime runtime = new McpRuntime(definitions, limits, objectMapper, sessionFactory)) {
                int tools = (int) runtime.snapshot().tools().stream()
                        .filter(tool -> tool.serverId().equals(mcpId)).count();
                return new McpServerDescriptor(server.mcpId(), server.name(), transport(server),
                        "available", tools);
            } catch (RuntimeException failure) {
                return new McpServerDescriptor(server.mcpId(), server.name(), transport(server),
                        "unavailable", 0);
            }
        }, probes);
    }

    /**
     * 读取一个代际所有的有界 Tool Schema 页面，不缓存携带 Secret 的定义。
     */
    @Override
    public CursorPage<McpToolDescriptor> readMcpTools(
            ConfigurationGenerationPort.Lease lease, String mcpId, String cursor, int limit) {
        ConfigurationGenerationSnapshot.McpServer server = lease.snapshot().requireMcp(mcpId);
        if (!server.enabled()) return new CursorPage<>(List.of(), null);
        List<McpServerDefinition> definitions = List.of(GenerationMcpDefinitionFactory.create(server,
                stdioWorkingDirectory, lease));
        try (McpRuntime runtime = new McpRuntime(definitions, limits, objectMapper, sessionFactory)) {
            List<McpToolDescriptor> values = runtime.snapshot().tools().stream()
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
        probes.shutdownNow();
        try {
            probes.awaitTermination(limits.closeTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 从一个代际及其租约构建定义，不保留已解析 Secret。
     */
    private static List<McpServerDefinition> definitions(ConfigurationGenerationSnapshot generation,
                                                         ConfigurationGenerationPort.Lease lease,
                                                         Path workspace) {
        return generation.mcpDefinitions().stream()
                .filter(ConfigurationGenerationSnapshot.McpServer::enabled)
                .map(server -> GenerationMcpDefinitionFactory.create(server, workspace, lease)).toList();
    }

    /**
     * 返回脱敏 Settings 投影使用的封闭传输词汇。
     */
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
    private record WorkspaceKey(String generationId, Path workspaceRoot) {
    }

    /**
     * 仅含 Schema 的工作区缓存有意排除启动定义和凭据值。
     */
    private record WorkspaceCatalog(McpGateway.McpSnapshot snapshot, Instant refreshedAt) {
    }

    /**
     * 仅供租约绑定 MCP Session Factory 消费的包内交接值。
     */
    record TurnCatalog(List<McpServerDefinition> definitions, McpGateway.McpSnapshot snapshot) {
        /**
         * 为一个 Turn 冻结选中路由与 Schema 投影。
         */
        TurnCatalog {
            definitions = List.copyOf(definitions);
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }
}
