// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.application.service;

import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.catalog.application.api.ThreadMcpUseCase;
import io.github.kongweiguang.ja.catalog.application.gateway.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.catalog.application.gateway.CatalogTaskCeilingPort;
import io.github.kongweiguang.ja.catalog.application.gateway.ThreadMcpCatalogPort;
import io.github.kongweiguang.ja.catalog.application.gateway.ThreadMcpThreadPort;
import io.github.kongweiguang.ja.catalog.application.gateway.ThreadMcpWorkspacePort;
import io.github.kongweiguang.ja.catalog.application.gateway.ThreadMcpPolicyPort;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.nio.file.Path;

/**
 * 先确认会话身份和工作区权限，再读取共享 MCP 目录。
 */
public final class ThreadMcpService implements ThreadMcpUseCase {
    private final ConfigurationGenerationPort configurations;
    private final ThreadMcpCatalogPort catalogs;
    private final CatalogTaskCeilingPort taskCeilings;
    private final ThreadMcpPolicyPort policy;

    /**
     * 分别使用会话、工作区、配置和 MCP 的既有应用边界，避免复制所有权。
     */
    public ThreadMcpService(ThreadMcpThreadPort threads, ThreadMcpWorkspacePort workspaces,
                            ConfigurationGenerationPort configurations, ThreadMcpCatalogPort catalogs,
                            CatalogTaskCeilingPort taskCeilings, ThreadMcpPolicyPort policy) {
        Objects.requireNonNull(threads, "threads");
        Objects.requireNonNull(workspaces, "workspaces");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.taskCeilings = Objects.requireNonNull(taskCeilings, "taskCeilings");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** 排队或更新的 Turn 不得把旧 Provider 派发标为本轮 MCP 状态。 */
    static boolean isActiveObservation(TurnState latestStatus, String latestTurnId, String observedTurnId) {
        return Objects.equals(latestTurnId, observedTurnId)
               && (latestStatus == TurnState.RUNNING || latestStatus == TurnState.WAITING_APPROVAL);
    }

    /**
     * 只读取内存观测和当前配置元数据，不执行 MCP 传输 IO；异常继续映射为稳定领域码，并把内部 cause 留给诊断。
     */
    @Override
    public ReadResult read(String threadId) {
        Objects.requireNonNull(threadId, "threadId");
        try {
            // 先取得租约再读取 owner 快照，确保 Catalog 比较和目录读取使用同一配置代际。
            Path root = policy.workspaceRoot(threadId);
            ThreadMcpPolicyPort.Context context;
            try (ConfigurationGenerationPort.Lease lease = configurations.acquire(root)) {
            context = policy.context(threadId, lease);
            root = context.root();
            String mode = context.collaborationMode();
            Optional<ThreadMcpCatalogPort.Observation> previous = catalogs.observation(threadId);
            String currentCeiling = taskCeilings.readFingerprint(threadId).orElse(null);
            List<Notice> notices = new java.util.ArrayList<>();
            if (policy.projectUntrusted(context)) {
                notices.add(Notice.PROJECT_UNTRUSTED);
            }
            if (context.projectMcpIssue()) {
                notices.add(Notice.PROJECT_CONFIG_ERROR);
            }
            if (previous.isPresent() && policy.active(context, previous.orElseThrow())
                && previous.orElseThrow().workspaceId().equals(context.workspaceId())
                && previous.orElseThrow().workspaceRoot().equals(root)) {
                ThreadMcpCatalogPort.Observation observation = previous.orElseThrow();
                if (!policy.matches(observation, context, currentCeiling)
                    || !catalogs.current(observation)) {
                    notices.add(Notice.CONFIGURATION_CHANGED);
                }
                return new ReadResult(threadId, Source.ACTIVE, observation.catalogRevision(),
                        observation.observedAt(), toPublicServers(policy.publicServers(observation)), notices);
            }
            if (previous.isPresent() && policy.matches(previous.orElseThrow(), context, currentCeiling)
                    && catalogs.current(previous.orElseThrow())) {
                ThreadMcpCatalogPort.Observation observation = previous.orElseThrow();
                return new ReadResult(threadId, Source.LAST_OBSERVED,
                        observation.catalogRevision(), observation.observedAt(),
                        toPublicServers(policy.publicServers(observation)), notices);
            }
            if (previous.isPresent()) {
                notices.add(Notice.CONFIGURATION_CHANGED);
                return new ReadResult(threadId, Source.STALE, null, null,
                        toPublicServers(policy.configuredServers(lease, mode,
                                ThreadMcpPolicyPort.State.NOT_DISCOVERED)), notices);
            }
                return new ReadResult(threadId, Source.UNCHECKED, null, null,
                        toPublicServers(policy.configuredServers(lease, mode,
                                ThreadMcpPolicyPort.State.NOT_DISCOVERED)), notices);
            }
        } catch (ThreadMcpUseCase.Failure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            Failure mapped = new Failure("MCP_CONFIGURATION_UNAVAILABLE");
            mapped.initCause(failure);
            throw mapped;
        }
    }

    /** 将 application.gateway 的稳定服务视图映射为 Catalog 入站 API，避免 ACL 依赖入站契约。 */
    private static List<Server> toPublicServers(List<ThreadMcpPolicyPort.ServerView> values) {
        return values.stream().map(value -> new Server(value.serverId(), value.name(),
                Scope.valueOf(value.scope().toUpperCase(java.util.Locale.ROOT)),
                state(value.state()), value.toolCount(), value.reasonCode())).toList();
    }

    /** Provider 状态只允许映射到 ThreadMcpUseCase 的稳定闭集。 */
    private static State state(String value) {
        return switch (value) {
            case "available" -> State.AVAILABLE;
            case "unavailable" -> State.UNAVAILABLE;
            case "disabled" -> State.DISABLED;
            case "not_discovered" -> State.NOT_DISCOVERED;
            case "not_exposed" -> State.NOT_EXPOSED;
            default -> State.STALE;
        };
    }

}





