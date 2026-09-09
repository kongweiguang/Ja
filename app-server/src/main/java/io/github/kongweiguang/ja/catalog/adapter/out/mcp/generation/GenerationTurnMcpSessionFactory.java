// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.generation;

import io.github.kongweiguang.ja.catalog.adapter.out.mcp.session.TurnMcpSessionFactory;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpLimits;
import io.github.kongweiguang.ja.catalog.port.out.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationGenerationSnapshot;
import io.github.kongweiguang.ja.conversation.port.out.McpGateway;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 从 Turn 准入的精确配置代际租约打开一个 MCP Runtime。
 */
public final class GenerationTurnMcpSessionFactory implements TurnMcpSessionFactory {
    private final GenerationCatalog catalog;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * 绑定代际目录与传输上限，但不保留可变快照。
     */
    public GenerationTurnMcpSessionFactory(com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                           McpLimits limits, GenerationCatalog catalog) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        Objects.requireNonNull(limits, "limits");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
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
     * pin 住 Resolver 已在本次 Provider 安全点捕获的同一目录，list_changed 只能影响下一请求。
     */
    public TurnMcpSessionFactory.Session open(
            CatalogSnapshot catalogSnapshot, CancellationToken cancellation) {
        Objects.requireNonNull(catalogSnapshot, "catalogSnapshot");
        Objects.requireNonNull(cancellation, "cancellation");
        SharedMcpGateway gateway = new SharedMcpGateway(
                catalogSnapshot.snapshot(), catalogSnapshot.services(), objectMapper);
        CancellationToken.Registration registration = cancellation.onCancellation(gateway::close);
        try {
            cancellation.throwIfCancellationRequested();
            return new OwnedSession(
                    gateway, catalogSnapshot.snapshot(), catalogSnapshot.routeIdentities(), registration);
        } catch (RuntimeException failure) {
            registration.close();
            gateway.close();
            if (cancellation.isCancellationRequested())
                throw new java.util.concurrent.CancellationException("turn_mcp_cancelled");
            throw new IllegalStateException("turn_mcp_open_failed");
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
                lease, provider.agentDefaults(), context.workspaceRoot());
        return new CatalogSnapshot(captured.snapshot(), captured.routeIdentities(), captured.services());
    }

    /**
     * Provider 安全点返回目录与同源路由证明，禁止调用方重新散列或从名称猜测 definitionRevision。
     */
    public static final class CatalogSnapshot {
        private final McpGateway.McpSnapshot snapshot;
        private final Map<String, McpGateway.RouteIdentity> routeIdentities;
        private final Map<String, McpServiceDirectory> services;

        /**
         * 防御性复制同源投影与不对外暴露的目录 owner，避免请求组装后被并发刷新替换。
         */
        private CatalogSnapshot(
                McpGateway.McpSnapshot snapshot,
                Map<String, McpGateway.RouteIdentity> routeIdentities,
                Map<String, McpServiceDirectory> services) {
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
            this.routeIdentities = Map.copyOf(routeIdentities);
            this.services = Map.copyOf(services);
        }

        /** 返回当前 Provider 请求看到的不可变 Tool 目录。 */
        public McpGateway.McpSnapshot snapshot() {
            return snapshot;
        }

        /** 返回与目录同源的精确路由证明，供 batch 持久化。 */
        public Map<String, McpGateway.RouteIdentity> routeIdentities() {
            return routeIdentities;
        }

        /** 仅允许本 Factory pin 包内 owner，防止上层绕过 Gateway 生命周期。 */
        private Map<String, McpServiceDirectory> services() {
            return services;
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
            if (closed.get()) throw new IllegalStateException("turn_mcp_session_closed");
        }
    }
}
