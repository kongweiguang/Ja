// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.runtime.McpRuntime;
import io.github.kongweiguang.ja.conversation.port.out.McpGateway;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tool batch 对持久服务目录的轻量 pin；关闭只释放 pin，不关闭健康的可通知 Session。
 */
final class SharedMcpGateway implements McpGateway {
    private final McpSnapshot snapshot;
    private final Map<String, McpServiceDirectory> services;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 构造时 pin 当前目录中的全部服务，使配置退休不能破坏已生成 batch 的精确路由。
     */
    SharedMcpGateway(
            McpSnapshot snapshot,
            Map<String, McpServiceDirectory> services,
            ObjectMapper objectMapper) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.services = Map.copyOf(services);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        pinServices(this.services);
    }

    /**
     * 将多服务 pin 视为一次准入；中途失败立即逆序释放，避免部分 batch 占用泄漏。
     */
    @SuppressWarnings("PMD.CloseResource")
    private static void pinServices(Map<String, McpServiceDirectory> services) {
        try (PinAdmission admission = new PinAdmission()) {
            for (McpServiceDirectory service : services.values()) {
                service.pin();
                admission.rollback = new PinRollback(service, admission.rollback);
            }
            admission.committed = true;
        }
    }

    /**
     * 将准入提交标记放进资源边界，使 pin 异常保持主异常，回滚异常由 JVM 自动记录为 suppressed。
     */
    private static final class PinAdmission implements AutoCloseable {
        private PinRollback rollback;
        private boolean committed;

        /** 未提交时执行完整逆序回滚；提交后 pins 的生命周期转交给 gateway.close。 */
        @Override
        public void close() {
            if (!committed && rollback != null) rollback.close();
        }
    }

    /**
     * 每层 try-with-resources 都保证更早的 pin 继续释放，并把多次 release 失败按发生顺序串入异常链。
     */
    private record PinRollback(McpServiceDirectory service, PinRollback previous) implements AutoCloseable {
        /** 当前层先释放较新的 pin，再由资源关闭语义释放更早的 pin。 */
        @Override
        public void close() {
            if (previous == null) {
                service.release();
                return;
            }
            try (previous) {
                service.release();
            }
        }
    }

    /**
     * 返回 Provider 生成本批 Tool call 时看到的不可变聚合目录。
     */
    @Override
    public McpSnapshot snapshot() {
        requireOpen();
        return snapshot;
    }

    /**
     * 仅按快照中精确 serverId 路由到已 pin owner，再由 Runtime 执行 dirty 重拉与 hash 校验。
     */
    @Override
    @SuppressWarnings("PMD.CloseResource")
    public CompletionStage<McpResult> invoke(
            McpSnapshot ignored,
            McpInvocation invocation,
            CancellationToken cancellationToken) {
        requireOpen();
        McpTool expected = snapshot.tools().stream()
                .filter(tool -> tool.spec().name().equals(invocation.localToolName()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("mcp_snapshot_tool_missing"));
        McpServiceDirectory service = services.get(expected.serverId());
        if (service == null) {
            throw new IllegalArgumentException("mcp_snapshot_server_missing");
        }
        McpSnapshot serviceSnapshot = McpRuntime.catalogSnapshot(
                List.of(expected), List.of(service.definition()), objectMapper, Instant.now());
        return service.runtime().invoke(serviceSnapshot, invocation, cancellationToken);
    }

    /**
     * 释放本批全部 pin；服务保持连接以接收 list_changed，直到退休或 catalog 关闭。
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            services.values().forEach(McpServiceDirectory::release);
        }
    }

    /**
     * batch 释放后禁止迟到回调复用路由 owner。
     */
    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("turn_mcp_session_closed");
        }
    }
}
