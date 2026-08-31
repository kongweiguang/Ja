// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.generation;

import io.github.kongweiguang.ja.catalog.adapter.out.mcp.runtime.McpRuntime;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.session.TurnMcpSessionFactory;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpDeadline;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpLimits;
import io.github.kongweiguang.ja.catalog.port.out.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationGenerationSnapshot;
import io.github.kongweiguang.ja.conversation.port.out.McpGateway;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * 从 Turn 准入的精确配置代际租约打开一个 MCP Runtime。
 */
public final class GenerationTurnMcpSessionFactory implements TurnMcpSessionFactory {
    private final GenerationCatalog catalog;
    private final McpLimits limits;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final Clock clock;
    private final LongSupplier nanoTime;

    /**
     * 绑定代际目录与传输上限，但不保留可变快照。
     */
    public GenerationTurnMcpSessionFactory(com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                           McpLimits limits, GenerationCatalog catalog) {
        this(objectMapper, limits, catalog, Clock.systemUTC(), System::nanoTime);
    }

    /**
     * 测试接缝同时绑定墙钟与单调时钟，使单个 Turn Deadline 可独立复现。
     */
    GenerationTurnMcpSessionFactory(com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                    McpLimits limits, GenerationCatalog catalog,
                                    Clock clock, LongSupplier nanoTime) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.limits = Objects.requireNonNull(limits, "limits");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * 仅在 AgentLoop 已拥有取消边界后捕获选中路由并启动 Session。
     */
    public TurnMcpSessionFactory.Session open(TurnMcpSessionFactory.Context context,
                                               ConfigurationGenerationPort.Lease lease,
                                               CancellationToken cancellation) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(cancellation, "cancellation");
        ConfigurationGenerationSnapshot.Provider provider =
                lease.snapshot().requireProvider(context.providerId());
        lease.snapshot().requireModel(context.providerId(), context.modelId());
        GenerationCatalog.TurnCatalog turnCatalog = catalog.capture(
                lease, provider.agentDefaults(), context.workspaceRoot());
        McpDeadline deadline = McpDeadline.forTurn(context.deadlineAt(), clock, nanoTime);
        McpRuntime runtime = new McpRuntime(
                turnCatalog.definitions(), limits, objectMapper, turnCatalog.snapshot(), deadline);
        CancellationToken.Registration registration = cancellation.onCancellation(runtime::close);
        try {
            cancellation.throwIfCancellationRequested();
            runtime.initializeSessions();
            McpGateway.McpSnapshot snapshot = runtime.snapshot();
            cancellation.throwIfCancellationRequested();
            return new OwnedSession(runtime, snapshot, registration);
        } catch (RuntimeException failure) {
            registration.close();
            runtime.close();
            if (cancellation.isCancellationRequested())
                throw new java.util.concurrent.CancellationException("turn_mcp_cancelled");
            throw new IllegalStateException("turn_mcp_open_failed");
        }
    }

    /**
     * 独占一个 Runtime/Session 组合，并在 AgentLoop 终态边界恰好关闭一次。
     */
    private static final class OwnedSession implements TurnMcpSessionFactory.Session {
        private final McpRuntime runtime;
        private final McpGateway.McpSnapshot snapshot;
        private final CancellationToken.Registration registration;
        private final AtomicBoolean closed = new AtomicBoolean();

        /**
         * 只保留 Turn 独占 Gateway 与不可变 Schema 快照。
         */
        private OwnedSession(McpRuntime runtime, McpGateway.McpSnapshot snapshot,
                             CancellationToken.Registration registration) {
            this.runtime = runtime;
            this.snapshot = snapshot;
            this.registration = registration;
        }

        /**
         * 仅在 Session 未关闭时返回 Turn 独占 Gateway，防止终态后的模型回调复用传输。
         */
        @Override
        public McpGateway gateway() {
            requireOpen();
            return runtime;
        }

        /**
         * 返回打开阶段冻结的 Tool Schema 快照，避免运行中重新查询 MCP 服务。
         */
        @Override
        public McpGateway.McpSnapshot snapshot() {
            requireOpen();
            return snapshot;
        }

        /**
         * 先释放取消注册再关闭传输，防止迟到回调重复触发清理。
         */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                registration.close();
                runtime.close();
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
