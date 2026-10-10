// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.infrastructure.acl;

import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.catalog.application.gateway.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.catalog.application.gateway.ThreadMcpCatalogPort;
import io.github.kongweiguang.ja.catalog.application.gateway.ThreadMcpPolicyPort;
import io.github.kongweiguang.ja.catalog.application.gateway.ThreadMcpThreadPort;
import io.github.kongweiguang.ja.catalog.application.gateway.ThreadMcpWorkspacePort;
import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationGenerationSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Catalog 读取 Thread、Workspace 与 configuration 的 ACL；不依赖 Catalog 入站 API，
 * 只输出 application.gateway 中定义的策略 DTO。
 */
public final class CatalogThreadAcl implements ThreadMcpPolicyPort {
    private static final int THREAD_SNAPSHOT_LIMIT = 1;
    private final ThreadMcpThreadPort threads;
    private final ThreadMcpWorkspacePort workspaces;

    /** ACL 持有两个 owner 的窄读取端口；不缓存实体，每次读取重新校验身份和路径。 */
    public CatalogThreadAcl(ThreadMcpThreadPort threads, ThreadMcpWorkspacePort workspaces) {
        this.threads = Objects.requireNonNull(threads, "threads");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    }

    /** 读取 Thread 所属工作区根目录，配置代际租约必须以该根目录建立。 */
    @Override
    public Path workspaceRoot(String threadId) {
        ThreadSnapshot snapshot = threads.readThread(threadId, null, THREAD_SNAPSHOT_LIMIT)
                .orElseThrow(() -> new IllegalStateException("thread is unavailable"));
        return workspaces.requireOpenWorkspace(snapshot.thread().workspaceId()).root();
    }

    /** 在同一配置租约内压缩会话、工作区和配置身份，避免应用层引用 provider 领域快照。 */
    @Override
    public Context context(String threadId, ConfigurationGenerationPort.Lease lease) {
        ConfigurationGenerationSnapshot generation = Objects.requireNonNull(lease, "lease").snapshot();
        ThreadSnapshot snapshot = threads.readThread(threadId, null, THREAD_SNAPSHOT_LIMIT)
                .orElseThrow(() -> new IllegalStateException("thread is unavailable"));
        ThreadSummary thread = Objects.requireNonNull(snapshot.thread(), "thread");
        Workspace workspace = workspaces.requireOpenWorkspace(thread.workspaceId());
        String latestTurnId = snapshot.turns().isEmpty() ? null : snapshot.turns().getLast().turnId();
        return new Context(thread.workspaceId(), workspace.root(), workspace.kind() == Workspace.Kind.PROJECT,
                workspace.trust() == Workspace.Trust.TRUSTED, generation.generationId(), generation.trusted(),
                generation.projectMcpIssue(), thread.preferences().collaborationMode().name(),
                ThreadMcpIdentity.preferenceFingerprint(thread.preferences()),
                thread.latestTurnStatus(), latestTurnId);
    }

    /** 旧观测只有在工作区、代际、偏好和能力上限完全一致时才可复用。 */
    @Override
    public boolean matches(ThreadMcpCatalogPort.Observation observation, Context context,
                           String ceilingFingerprint) {
        return observation.workspaceId().equals(context.workspaceId())
                && observation.workspaceRoot().equals(context.root())
                && observation.generationId().equals(context.generationId())
                && observation.collaborationMode().name().equals(context.collaborationMode())
                && observation.preferenceFingerprint().equals(context.preferenceFingerprint())
                && Objects.equals(observation.ceilingFingerprint(), ceilingFingerprint);
    }

    /** 只有最近运行或等待审批的 Turn 可以继续使用活动 MCP 观测。 */
    @Override
    public boolean active(Context context, ThreadMcpCatalogPort.Observation observation) {
        return Objects.equals(context.latestTurnId(), observation.turnId())
                && (context.latestTurnStatus() == TurnState.RUNNING
                || context.latestTurnStatus() == TurnState.WAITING_APPROVAL);
    }

    /** 将 provider 观测映射为不含凭据和 endpoint 的 Catalog 服务视图。 */
    @Override
    public List<ServerView> publicServers(ThreadMcpCatalogPort.Observation observation) {
        return observation.servers().stream()
                .map(server -> new ServerView(server.serverId(), server.name(),
                        publicScope(observation.scopes().get(server.serverId())), server.state(),
                        server.toolCount(), server.reasonCode()))
                .toList();
    }

    /** 目录配置未加载时也必须返回确定服务状态，不猜测可用性。 */
    @Override
    public List<ServerView> configuredServers(ConfigurationGenerationPort.Lease lease,
                                              String collaborationMode, State fallback) {
        ConfigurationGenerationSnapshot generation = Objects.requireNonNull(lease, "lease").snapshot();
        return generation.mcpDefinitions().stream()
                .sorted(Comparator.comparing(ConfigurationGenerationSnapshot.McpServer::mcpId))
                .map(server -> new ServerView(server.mcpId(), server.name(), publicScope(server.scope()),
                        !server.enabled() ? "disabled"
                                : "PLAN".equals(collaborationMode) ? "not_exposed" : "not_discovered",
                        null, null))
                .toList();
    }

    /** 项目工作区未信任时保留提示，禁止把全局服务误报为项目服务。 */
    @Override
    public boolean projectUntrusted(Context context) {
        return context.project() && !context.workspaceTrusted();
    }

    /** 来源只允许 provider 的全局/项目闭集，未知值立即失败。 */
    private static String publicScope(ConfigurationGenerationSnapshot.Scope scope) {
        return switch (Objects.requireNonNull(scope, "scope")) {
            case GLOBAL -> "global";
            case PROJECT -> "project";
        };
    }
}
