// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.session.McpSession;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.session.McpSessionFactory;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.session.SdkMcpSessionFactory;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpCloseResult;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpDeadline;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpLimits;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpServerDefinition;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.out.McpGateway;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 生产 MCP Gateway，负责有界发现、Turn 快照、串行调用与取消。
 */
public final class McpRuntime implements McpGateway {
    private final List<McpServerDefinition> definitions;
    private final Map<String, McpServerDefinition> definitionsById;
    private final Map<String, McpServerState> servers;
    private final McpLimits limits;
    private final ObjectMapper objectMapper;
    private final McpSnapshot catalogSnapshot;
    private final McpDeadline deadline;
    private final McpRuntimeLifecycle lifecycle;

    /**
     * 从已解析私有配置创建 Native SDK Adapter，不读取活动配置。
     */
    public McpRuntime(
            List<McpServerDefinition> definitions,
            McpLimits limits,
            ObjectMapper objectMapper) {
        this(definitions, limits, objectMapper, new SdkMcpSessionFactory(objectMapper, limits), null,
                McpDeadline.forOperation(limits, System::nanoTime));
    }

    /**
     * 让协议和失败测试独立于网络时序及 SDK 内部实现。
     */
    public McpRuntime(
            List<McpServerDefinition> definitions,
            McpLimits limits,
            ObjectMapper objectMapper,
            McpSessionFactory sessionFactory) {
        this(definitions, limits, objectMapper, sessionFactory, null,
                McpDeadline.forOperation(limits, System::nanoTime));
    }

    /**
     * 针对工作区刷新时发现的目录启动全新传输代际。
     */
    public McpRuntime(
            List<McpServerDefinition> definitions,
            McpLimits limits,
            ObjectMapper objectMapper,
            McpSnapshot catalogSnapshot) {
        this(definitions, limits, objectMapper, new SdkMcpSessionFactory(objectMapper, limits), catalogSnapshot,
                McpDeadline.forOperation(limits, System::nanoTime));
    }

    /**
     * 将唯一准入的绝对 Deadline 传递给每个 SDK 与传输所有者。
     */
    public McpRuntime(
            List<McpServerDefinition> definitions,
            McpLimits limits,
            ObjectMapper objectMapper,
            McpSnapshot catalogSnapshot,
            McpDeadline deadline) {
        this(definitions, limits, objectMapper, new SdkMcpSessionFactory(objectMapper, limits),
                catalogSnapshot, deadline);
    }

    /**
     * 为聚焦测试提供接缝，用于证明缓存目录不会再次调用 tools/list。
     */
    McpRuntime(
            List<McpServerDefinition> definitions,
            McpLimits limits,
            ObjectMapper objectMapper,
            McpSessionFactory sessionFactory,
            McpSnapshot catalogSnapshot) {
        this(definitions, limits, objectMapper, sessionFactory, catalogSnapshot,
                McpDeadline.forOperation(limits, System::nanoTime));
    }

    /**
     * 提供单调时钟测试接缝，生产逻辑不依赖墙钟变化。
     */
    McpRuntime(
            List<McpServerDefinition> definitions,
            McpLimits limits,
            ObjectMapper objectMapper,
            McpSessionFactory sessionFactory,
            McpSnapshot catalogSnapshot,
            LongSupplier nanoTime) {
        this(definitions, limits, objectMapper, sessionFactory, catalogSnapshot,
                McpDeadline.forOperation(limits, nanoTime));
    }

    /**
     * 创建 Runtime 所有的 Executor 前绑定显式生命周期 Deadline。
     */
    public McpRuntime(
            List<McpServerDefinition> definitions,
            McpLimits limits,
            ObjectMapper objectMapper,
            McpSessionFactory sessionFactory,
            McpSnapshot catalogSnapshot,
            McpDeadline deadline) {
        Objects.requireNonNull(definitions, "definitions");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper")
                .copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.definitions = definitions.stream()
                .sorted(java.util.Comparator.comparing(McpServerDefinition::id))
                .toList();
        this.definitionsById = this.definitions.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                McpServerDefinition::id, java.util.function.Function.identity()));
        this.lifecycle = new McpRuntimeLifecycle(this.definitions, this.limits,
                Objects.requireNonNull(sessionFactory, "sessionFactory"), this.deadline);
        this.servers = lifecycle.servers();
        this.catalogSnapshot = McpToolCatalog.validateSnapshot(
                catalogSnapshot, this.objectMapper, this.definitionsById);
        if (this.catalogSnapshot != null) {
            for (McpServerDefinition definition : this.definitions) {
                List<McpTool> serverTools = this.catalogSnapshot.tools().stream()
                        .filter(tool -> definition.id().equals(tool.serverId()))
                        .toList();
                this.servers.get(definition.id()).publishInitialDirectory(serverTools);
            }
        }
    }

    /**
     * 使用显式 Cursor 页面发现所有服务，并生成确定性聚合版本。
     */
    @Override
    public McpSnapshot snapshot() {
        requireOpen();
        if (catalogSnapshot != null) {
            return catalogSnapshot;
        }
        long deadlineNanos = deadline.phaseDeadline(limits.requestTimeout());
        List<McpTool> tools = new ArrayList<>();
        Set<String> localNames = new HashSet<>();
        for (McpServerDefinition definition : definitions) {
            McpServerState holder = servers.get(definition.id());
            try {
                if (holder.directoryDirty()) {
                    long observedRevision = holder.discoveryRevision();
                    List<McpTool> discovered = lifecycle.executeServerWithin(
                            holder,
                            deadlineNanos,
                            "mcp_discovery_timeout",
                            () -> discover(holder, new HashSet<>()));
                    holder.publishDirectory(discovered, observedRevision);
                }
                if (holder.directoryDirty()) {
                    continue;
                }
                for (McpTool tool : holder.directoryTools()) {
                    if (!localNames.add(tool.spec().name())) {
                        throw new IllegalStateException("mcp_local_tool_conflict");
                    }
                    tools.add(tool);
                }
            } catch (RuntimeException isolatedFailure) {
                // 单个远端目录失败不得关闭健康服务；失败服务保持 dirty，下一安全点可独立重试。
                holder.markDiscoveryFailed();
                holder.markDirectoryDirty();
                tools.removeIf(tool -> tool.serverId().equals(definition.id()));
            }
        }
        return McpToolCatalog.snapshot(tools, definitionsById, objectMapper, Instant.now());
    }

    /**
     * 初始化选中传输，但对已提供的请求级目录不重复调用 tools/list。
     */
    public void initializeSessions() {
        requireOpen();
        long deadlineNanos = deadline.phaseDeadline(limits.startupTimeout());
        try {
            for (McpServerDefinition definition : definitions) {
                McpServerState holder = servers.get(definition.id());
                lifecycle.executeServerWithin(
                        holder,
                        deadlineNanos,
                        "mcp_initialize_timeout",
                        () -> {
                            holder.session();
                            return null;
                        });
            }
        } catch (RuntimeException failure) {
            lifecycle.closeAllSessionsAfterFailure(failure, deadlineNanos);
            throw failure;
        }
    }

    /**
     * 在 Turn 级 Deadline 内执行；超时回调只分离资源并调度清理。
     */
    @Override
    public CompletionStage<McpResult> invoke(
            McpSnapshot snapshot,
            McpInvocation invocation,
            CancellationToken cancellationToken) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        requireOpen();
        if (!McpToolCatalog.hasValidRevision(snapshot, objectMapper, definitionsById)) {
            throw new IllegalArgumentException("mcp_snapshot_revision_invalid");
        }
        McpTool tool = snapshot.tools().stream()
                .filter(candidate -> candidate.spec().name().equals(invocation.localToolName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("mcp_snapshot_tool_missing"));
        McpServerState holder = servers.get(tool.serverId());
        if (holder == null) {
            throw new IllegalArgumentException("mcp_snapshot_server_missing");
        }
        if (!refreshAndValidateBinding(holder, tool)) {
            return CompletableFuture.completedFuture(bindingUnavailableResult());
        }
        long deadlineNanos = deadline.phaseDeadline(limits.requestTimeout());
        long remaining = remainingNanos(deadlineNanos);
        if (remaining <= 0) {
            return finishFailedInvocation(
                    holder, null, new TimeoutException("mcp_turn_deadline_exceeded"), cancellationToken);
        }
        CompletableFuture<McpResult> operation = CompletableFuture.supplyAsync(
                () -> invokeSerialized(holder, tool, invocation, cancellationToken), lifecycle.executor());
        CompletableFuture<McpResult> bounded = operation.copy()
                .orTimeout(remaining, TimeUnit.NANOSECONDS);
        return bounded.<CompletionStage<McpResult>>handle((result, failure) -> {
            if (failure == null && remainingNanos(deadlineNanos) > 0) {
                return CompletableFuture.completedFuture(result);
            }
            Throwable cause = failure == null
                    ? new TimeoutException("mcp_turn_deadline_exceeded")
                    : unwrap(failure);
            return finishFailedInvocation(holder, operation, cause, cancellationToken);
        }).thenCompose(stage -> stage);
    }

    /**
     * 将关闭准入与结果重放委派给 Runtime 生命周期协调器。
     */
    @Override
    public void close() {
        lifecycle.close();
    }

    /**
     * 返回当前快照的精确路由证明，供请求级 Tool batch 在执行前持久化。
     */
    public Map<String, McpGateway.RouteIdentity>
    routeIdentities(McpSnapshot snapshot) {
        if (!McpToolCatalog.hasValidRevision(snapshot, objectMapper, definitionsById)) {
            throw new IllegalArgumentException("mcp_snapshot_revision_invalid");
        }
        return McpToolCatalog.routeIdentities(snapshot, definitionsById, objectMapper);
    }

    /**
     * Settings 探测读取逐服务脱敏失败集合；Provider 只使用 snapshot 中的健康 Tool。
     */
    public Set<String> unavailableServerIds() {
        return servers.entrySet().stream().filter(entry -> entry.getValue().discoveryFailed())
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * dirty 服务在调用前先走同一套有界分页，并与原 batch 的 schema/route hash 精确比较。
     */
    private boolean refreshAndValidateBinding(McpServerState holder, McpTool expected) {
        if (!holder.directoryDirty()) {
            return true;
        }
        long deadlineNanos = deadline.phaseDeadline(limits.requestTimeout());
        try {
            long observedRevision = holder.discoveryRevision();
            List<McpTool> refreshed = lifecycle.executeServerWithin(
                    holder, deadlineNanos, "mcp_discovery_timeout",
                    () -> discover(holder, new HashSet<>()));
            holder.publishDirectory(refreshed, observedRevision);
            if (holder.directoryDirty()) {
                return false;
            }
            McpTool current = refreshed.stream()
                    .filter(tool -> tool.spec().name().equals(expected.spec().name()))
                    .findFirst().orElse(null);
            if (current == null) {
                return false;
            }
            String expectedSchema = McpToolCatalog.schemaHash(expected, objectMapper);
            String currentSchema = McpToolCatalog.schemaHash(current, objectMapper);
            return expectedSchema.equals(currentSchema)
                    && McpToolCatalog.routeHash(holder.definition(), expected, expectedSchema)
                    .equals(McpToolCatalog.routeHash(holder.definition(), current, currentSchema));
        } catch (RuntimeException failure) {
            holder.markDiscoveryFailed();
            holder.markDirectoryDirty();
            return false;
        }
    }

    /**
     * 旧路由不可证明时返回稳定结果而不尝试同名 fallback，交由模型在下一请求基于最新目录继续。
     */
    private static McpResult bindingUnavailableResult() {
        return new McpResult(true, "", java.util.Optional.of(JsonObjects.builder()
                .putText("category", "tool_binding_unavailable").build()), ToolOutcome.FAILED);
    }

    /**
     * 拉取页面直到服务结束分页，并拒绝 Cursor 循环或聚合洪泛。
     */
    @SuppressWarnings("PMD.CloseResource")
    private List<McpTool> discover(McpServerState holder, Set<String> aggregateNames) {
        McpSession session = holder.session();
        List<McpTool> discovered = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        Set<String> remoteNames = new HashSet<>();
        String cursor = null;
        long schemaBytes = 0;
        for (int pageIndex = 0; pageIndex < limits.maxPages(); pageIndex++) {
            McpSession.ToolPage page;
            try {
                page = session.listTools(cursor);
            } catch (RuntimeException failure) {
                throw new IllegalStateException("mcp_list_tools_failed", failure);
            }
            for (McpSession.RemoteTool remote : page.tools()) {
                if (remote.name() == null || remote.name().isBlank() || remote.name().length() > 256
                    || !remoteNames.add(remote.name())) {
                    throw new IllegalStateException("mcp_remote_tool_conflict");
                }
                schemaBytes += McpToolCatalog.encodedSize(objectMapper, remote.inputSchema());
                if (schemaBytes > limits.maxSchemaBytes()) {
                    throw new IllegalStateException("mcp_schema_limit");
                }
                McpToolCatalog.requireToolSchema(objectMapper, remote.inputSchema());
                if (discovered.size() >= limits.maxTools()) {
                    throw new IllegalStateException("mcp_tool_limit");
                }
                String localName = McpToolCatalog.namespaced(holder.definition().id(), remote.name());
                if (!aggregateNames.add(localName)) {
                    throw new IllegalStateException("mcp_local_tool_conflict");
                }
                String description = remote.description();
                if (description == null || description.isBlank() || description.length() > 32_768) {
                    throw new IllegalStateException("mcp_tool_description_invalid");
                }
                ToolSpec spec = new ToolSpec(
                        localName,
                        description,
                        remote.inputSchema());
                discovered.add(new McpTool(
                        holder.definition().id(), McpToolCatalog.encodeRemoteName(remote.name()), spec));
            }
            String next = page.nextCursor();
            if (next == null || next.isBlank()) {
                return discovered;
            }
            if (next.getBytes(StandardCharsets.UTF_8).length > limits.maxCursorBytes() || !cursors.add(next)) {
                throw new IllegalStateException("mcp_cursor_loop_or_limit");
            }
            cursor = next;
        }
        throw new IllegalStateException("mcp_page_limit");
    }

    /**
     * 串行化单个服务调用，并在等待锁之前注册取消回调。
     */
    @SuppressWarnings({"try", "PMD.UnusedLocalVariable"})
    private McpResult invokeSerialized(
            McpServerState holder,
            McpTool tool,
            McpInvocation invocation,
            CancellationToken cancellationToken) {
        try (CancellationToken.Registration registration = cancellationToken.onCancellation(
                () -> holder.beginCloseSession(deadline.phaseDeadline(limits.closeTimeout())))) {
            cancellationToken.throwIfCancellationRequested();
            McpSession.RemoteResult result = null;
            RuntimeException operationFailure = null;
            boolean resultLimit = false;
            CompletableFuture<McpCloseResult> cleanup = null;
            long cleanupDeadlineNanos = Long.MIN_VALUE;
            holder.lockInterruptibly();
            try {
                cancellationToken.throwIfCancellationRequested();
                result = holder.session().call(
                        McpToolCatalog.decodeRemoteName(tool.remoteName()), invocation.arguments());
                cancellationToken.throwIfCancellationRequested();
                resultLimit = McpToolCatalog.encodedSize(objectMapper, result.content())
                              + result.structuredContent()
                                      .map(value -> McpToolCatalog.encodedSize(objectMapper, value))
                                      .orElse(0)
                              > limits.maxResultBytes();
                if (resultLimit) {
                    cleanupDeadlineNanos = deadline.phaseDeadline(limits.closeTimeout());
                    cleanup = holder.beginCloseSession(cleanupDeadlineNanos);
                }
            } catch (RuntimeException failure) {
                cleanupDeadlineNanos = deadline.phaseDeadline(limits.closeTimeout());
                cleanup = holder.beginCloseSession(cleanupDeadlineNanos);
                operationFailure = failure;
            } finally {
                holder.unlock();
            }
            if (cleanup != null) {
                McpCloseResult cleanupResult = lifecycle.awaitSessionClose(cleanup, cleanupDeadlineNanos);
                if (cleanupResult.failed()) {
                    if (operationFailure == null) {
                        return cleanupFailureResult();
                    }
                    operationFailure.addSuppressed(new IllegalStateException(cleanupResult.failures().getFirst()));
                }
            }
            if (operationFailure != null) {
                throw operationFailure;
            }
            if (resultLimit) {
                return new McpResult(
                        true, "", java.util.Optional.of(JsonObjects.builder()
                                .putText("category", "result_limit").build()), ToolOutcome.FAILED);
            }
            return new McpResult(
                    result.error(),
                    result.content(),
                    result.structuredContent(),
                    result.error() ? ToolOutcome.FAILED : ToolOutcome.SUCCEEDED);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("mcp_call_interrupted");
        }
    }

    /**
     * 仅在分离传输清理成功或到达 Deadline 后映射失败调用。
     */
    private CompletionStage<McpResult> finishFailedInvocation(
            McpServerState holder,
            CompletableFuture<McpResult> operation,
            Throwable cause,
            CancellationToken cancellationToken) {
        if (operation != null) {
            operation.cancel(true);
        }
        long cleanupDeadlineNanos = deadline.phaseDeadline(limits.closeTimeout());
        return holder.beginCloseSession(cleanupDeadlineNanos).thenApply(cleanup -> {
            if (cleanup.failed()) {
                return cleanupFailureResult();
            }
            if (cause instanceof CancellationException || cancellationToken.isCancellationRequested()) {
                return new McpResult(false, "", java.util.Optional.empty(), ToolOutcome.CANCELLED);
            }
            return new McpResult(
                    true,
                    "",
                    java.util.Optional.of(JsonObjects.builder().putText("category",
                            cause instanceof TimeoutException ? "timeout" : "transport_failure").build()),
                    ToolOutcome.FAILED);
        });
    }

    /**
     * 清理无法在 Deadline 前证明传输已释放时返回稳定结果。
     */
    private static McpResult cleanupFailureResult() {
        return new McpResult(true, "", java.util.Optional.of(JsonObjects.builder()
                .putText("category", "cleanup_failure").build()), ToolOutcome.FAILED);
    }

    /**
     * 使用创建 Deadline 的同一单调时钟计算剩余聚合预算。
     */
    private long remainingNanos(long deadlineNanos) {
        return deadline.remainingNanos(deadlineNanos);
    }

    /**
     * 生产目录构建显式携带服务定义，使 catalog revision 覆盖传输与认证语义。
     */
    public static McpSnapshot catalogSnapshot(
            List<McpTool> tools,
            List<McpServerDefinition> definitions,
            ObjectMapper objectMapper,
            Instant createdAt) {
        Map<String, McpServerDefinition> byId = definitions.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        McpServerDefinition::id, java.util.function.Function.identity()));
        return McpToolCatalog.snapshot(tools, byId, objectMapper, createdAt);
    }

    /**
     * Runtime 开始关闭后禁止打开新 Session。
     */
    private void requireOpen() {
        lifecycle.requireOpen();
    }

    /**
     * 解包异步异常，同时保留稳定的本地分类边界。
     */
    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
               && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
