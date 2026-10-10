// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.infrastructure.mcp.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.catalog.infrastructure.mcp.session.TurnMcpSessionFactory;
import io.github.kongweiguang.ja.catalog.infrastructure.mcp.support.McpLimits;
import io.github.kongweiguang.ja.catalog.application.gateway.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.catalog.application.gateway.McpGateway;
import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationGenerationSnapshot;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 从 Turn 准入的精确配置代际租约打开一个 MCP Runtime。
 */
public final class GenerationTurnMcpSessionFactory implements TurnMcpSessionFactory {
    private final GenerationCatalog catalog;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final ConfigurationGenerationPort configurations;

    /**
     * 绑定代际目录与传输上限，但不保留可变快照。
     */
    public GenerationTurnMcpSessionFactory(com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                           McpLimits limits, GenerationCatalog catalog) {
        this(objectMapper, limits, catalog, null);
    }

    /**
     * 生产路径额外绑定当前信任事实；旧租约只负责目录冻结，不授权撤信后的新调用。
     */
    public GenerationTurnMcpSessionFactory(com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                           McpLimits limits, GenerationCatalog catalog,
                                           ConfigurationGenerationPort configurations) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        Objects.requireNonNull(limits, "limits");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.configurations = configurations;
    }

    /**
     * 多个私有 MCP 同时存在时逐个退休，单一传输清理故障不得留下其它客户端进程。
     */
    @SuppressWarnings("PMD.CloseResource") // retire(true) 是该目录的完整关闭边界，必须逐项执行。
    private static void retirePrivateServices(java.util.List<McpServiceDirectory> directories) {
        RuntimeException first = null;
        for (McpServiceDirectory directory : directories) {
            try {
                directory.retire(true);
            } catch (RuntimeException failure) {
                if (first == null) {
                    first = new IllegalStateException("mcp_private_cleanup_failed");
                }
                first.addSuppressed(failure);
            }
        }
        if (first != null) {
            throw first;
        }
    }

    /**
     * 仅在 AgentLoop 已拥有取消边界后捕获选中路由；不会全量 initialize，实际服务首次调用才建连。
     */
    public TurnMcpSessionFactory.Session open(TurnMcpSessionFactory.Context context,
                                              ConfigurationGenerationPort.Lease lease,
                                              CancellationToken cancellation) {
        return open(catalog(context, lease), cancellation);
    }

    /**
     * Gateway 先取得执行期 pin，再释放 capture reservation；配置退休不能破坏已捕获或正在执行的批次。
     * 失败 cause 只包含该目录与 Runtime 提供的本地稳定码，外层消息仍保持脱敏并保留内部诊断链。
     */
    public TurnMcpSessionFactory.Session open(
            CatalogSnapshot catalogSnapshot, CancellationToken cancellation) {
        Objects.requireNonNull(catalogSnapshot, "catalogSnapshot");
        Objects.requireNonNull(cancellation, "cancellation");
        SharedMcpGateway gateway = catalogSnapshot.openGateway(
                objectMapper, () -> projectTrusted(catalogSnapshot.workspaceRoot));
        CancellationToken.Registration registration = null;
        try {
            registration = cancellation.onCancellation(gateway::close);
            cancellation.throwIfCancellationRequested();
            return new OwnedSession(
                    gateway, catalogSnapshot.snapshot(), catalogSnapshot.routeIdentities(), registration);
        } catch (RuntimeException failure) {
            if (registration != null) {
                registration.close();
            }
            try {
                gateway.close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            if (cancellation.isCancellationRequested())
                {
                    java.util.concurrent.CancellationException cancelled =
                            new java.util.concurrent.CancellationException("turn_mcp_cancelled");
                    cancelled.initCause(failure);
                    throw cancelled;
                }
            throw new IllegalStateException("turn_mcp_open_failed", failure);
        }
    }

    /**
     * 在 Provider 请求安全点获取最新 Tool schema 与路由证明；真正执行仍由 open
     * pin 住同一目录，保证已生成 batch 不被并发刷新改写。
     */
    public CatalogSnapshot catalog(TurnMcpSessionFactory.Context context,
                                   ConfigurationGenerationPort.Lease lease) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(lease, "lease");
        ConfigurationGenerationSnapshot.Provider provider =
                lease.snapshot().requireProvider(context.providerId());
        lease.snapshot().requireModel(context.providerId(), context.modelId());
        GenerationCatalog.TurnCatalog captured = catalog.capture(
                lease, provider.agentDefaults(), context.workspaceRoot(), context.executionContext());
        try {
            return new CatalogSnapshot(captured.snapshot(), captured.routeIdentities(), captured.services(),
                    captured.serverStatuses(), context.workspaceRoot(),
                    lease.snapshot().mcpDefinitions().stream()
                            .filter(server -> server.scope() == ConfigurationGenerationSnapshot.Scope.PROJECT)
                            .map(ConfigurationGenerationSnapshot.McpServer::mcpId)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    captured.privateServices(), captured.reservation());
        } catch (RuntimeException failure) {
            try {
                captured.reservation().close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            try {
                retirePrivateServices(captured.privateServices());
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * 每次项目远端调用前读取最新代际；信任读取失败时拒绝调用，避免旧 Turn pin 绕过撤信。
     */
    private boolean projectTrusted(java.nio.file.Path workspaceRoot) {
        if (configurations == null || workspaceRoot == null) {
            return false;
        }
        try (ConfigurationGenerationPort.Lease current = configurations.acquire(workspaceRoot)) {
            return current.snapshot().trusted();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Provider 安全点返回目录与同源路由证明，禁止调用方重新散列或从名称猜测 definitionRevision。
     */
    public static final class CatalogSnapshot implements AutoCloseable {
        private final McpGateway.McpSnapshot snapshot;
        private final Map<String, McpGateway.RouteIdentity> routeIdentities;
        private final Map<String, McpServiceDirectory> services;
        private final java.util.List<McpGateway.McpServerStatus> serverStatuses;
        private final java.nio.file.Path workspaceRoot;
        private final Set<String> projectServerIds;
        private final java.util.List<McpServiceDirectory> privateServices;
        private final GenerationCatalog.CatalogReservation reservation;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Object lifecycleLock = new Object();

        /**
         * 防御性复制同源投影与不对外暴露的目录 owner，避免请求组装后被并发刷新替换。
         */
        private CatalogSnapshot(
                McpGateway.McpSnapshot snapshot,
                Map<String, McpGateway.RouteIdentity> routeIdentities,
                Map<String, McpServiceDirectory> services,
                java.util.List<McpGateway.McpServerStatus> serverStatuses,
                java.nio.file.Path workspaceRoot, Set<String> projectServerIds,
                java.util.List<McpServiceDirectory> privateServices,
                GenerationCatalog.CatalogReservation reservation) {
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
            this.routeIdentities = Map.copyOf(routeIdentities);
            this.services = Map.copyOf(services);
            this.serverStatuses = java.util.List.copyOf(serverStatuses);
            this.workspaceRoot = workspaceRoot;
            this.projectServerIds = Set.copyOf(projectServerIds);
            this.privateServices = java.util.List.copyOf(privateServices);
            this.reservation = Objects.requireNonNull(reservation, "reservation");
        }

        /**
         * 规划阶段使用的空目录证明；它不触发 MCP 配置解析、进程启动或远端发现，执行阶段仍须
         * 通过 {@link GenerationTurnMcpSessionFactory#catalog} 获取真实目录。
         */
        public static CatalogSnapshot planningEmpty() {
            return new CatalogSnapshot(
                    new McpGateway.McpSnapshot("mcp_plan_disabled", java.util.List.of(),
                            java.time.Instant.EPOCH), Map.of(), Map.of(), java.util.List.of(), null, Set.of(),
                    java.util.List.of(), new GenerationCatalog.CatalogReservation(java.util.List.of()));
        }

        /**
         * 返回当前 Provider 请求看到的不可变 Tool 目录。
         */
        public McpGateway.McpSnapshot snapshot() {
            return snapshot;
        }

        /**
         * 返回与目录同源的精确路由证明，供 batch 持久化。
         */
        public Map<String, McpGateway.RouteIdentity> routeIdentities() {
            return routeIdentities;
        }

        /**
         * 返回与冻结 Tool snapshot 同源的脱敏服务状态，含健康零工具服务。
         */
        public java.util.List<McpGateway.McpServerStatus> serverStatuses() {
            return serverStatuses;
        }

        /**
         * 私有锁串行化 Gateway pin 转交和捕获引用释放，避免公开快照对象的 monitor 卡住资源生命周期。
         */
        private SharedMcpGateway openGateway(
                ObjectMapper objectMapper, java.util.function.BooleanSupplier projectTrusted) {
            synchronized (lifecycleLock) {
                requireOpen();
                SharedMcpGateway gateway = new SharedMcpGateway(snapshot, services, serverStatuses,
                        objectMapper, projectServerIds, projectTrusted);
                try {
                    reservation.close();
                } catch (RuntimeException failure) {
                    try {
                        gateway.close();
                    } catch (RuntimeException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                    throw failure;
                }
                return gateway;
            }
        }

        /**
         * 以私有锁与 Gateway 转交互斥，保证调用方持有公开快照 monitor 时仍能释放捕获和私有目录。
         */
        @Override
        public void close() {
            synchronized (lifecycleLock) {
                if (closed.compareAndSet(false, true)) {
                    RuntimeException failure = null;
                    try {
                        reservation.close();
                    } catch (RuntimeException cleanupFailure) {
                        failure = cleanupFailure;
                    }
                    try {
                        retirePrivateServices(privateServices);
                    } catch (RuntimeException cleanupFailure) {
                        if (failure == null) {
                            failure = cleanupFailure;
                        } else {
                            failure.addSuppressed(cleanupFailure);
                        }
                    }
                    if (failure != null) {
                        throw failure;
                    }
                }
            }
        }

        /**
         * 租约关闭后不得重新打开已退休的原生客户端 stdio。
         */
        private void requireOpen() {
            if (closed.get()) {
                throw new IllegalStateException("mcp_catalog_snapshot_closed");
            }
        }
    }

    /**
     * 独占一个 Runtime/Session 组合，并在 AgentLoop 终态边界恰好关闭一次。
     */
    private static final class OwnedSession implements TurnMcpSessionFactory.Session {
        private final McpGateway gateway;
        private final McpGateway.McpSnapshot snapshot;
        private final Map<String, McpGateway.RouteIdentity> routeIdentities;
        private final CancellationToken.Registration registration;
        private final AtomicBoolean closed = new AtomicBoolean();

        /**
         * 只保留 Turn 独占 Gateway 与不可变 Schema 快照。
         */
        private OwnedSession(McpGateway gateway, McpGateway.McpSnapshot snapshot,
                             Map<String, McpGateway.RouteIdentity> routeIdentities,
                             CancellationToken.Registration registration) {
            this.gateway = gateway;
            this.snapshot = snapshot;
            this.routeIdentities = Map.copyOf(routeIdentities);
            this.registration = registration;
        }

        /**
         * 仅在 Session 未关闭时返回 Turn 独占 Gateway，防止终态后的模型回调复用传输。
         */
        @Override
        public McpGateway gateway() {
            requireOpen();
            return gateway;
        }

        /**
         * 返回本次 Provider 请求安全点的 Tool Schema 快照，避免已生成 batch 被重新路由。
         */
        @Override
        public McpGateway.McpSnapshot snapshot() {
            requireOpen();
            return snapshot;
        }

        /**
         * 返回与 snapshot 同一安全点生成的路由证明，供 Tool batch 原子持久化。
         */
        @Override
        public Map<String, McpGateway.RouteIdentity> routeIdentities() {
            requireOpen();
            return routeIdentities;
        }

        /**
         * 先释放取消注册再关闭传输，防止迟到回调重复触发清理。
         */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                registration.close();
                gateway.close();
            }
        }

        /**
         * 阻止迟到的模型回调复用已过期 MCP Session。
         */
        private void requireOpen() {
            if (closed.get()) {
                throw new IllegalStateException("turn_mcp_session_closed");
            }
        }
    }
}
