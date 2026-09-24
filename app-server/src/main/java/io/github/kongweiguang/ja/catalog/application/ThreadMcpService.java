// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.application;

import io.github.kongweiguang.ja.catalog.port.in.ThreadMcpUseCase;
import io.github.kongweiguang.ja.catalog.port.out.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.catalog.port.out.ThreadMcpCatalogPort;
import io.github.kongweiguang.ja.conversation.port.out.TaskCapabilityCeilingPort;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationGenerationSnapshot;
import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.port.out.McpGateway;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 先确认会话身份和工作区权限，再读取共享 MCP 目录。 */
public final class ThreadMcpService implements ThreadMcpUseCase {
    private static final int THREAD_SNAPSHOT_LIMIT = 1;

    private final ThreadUseCase threads;
    private final WorkspaceUseCase workspaces;
    private final ConfigurationGenerationPort configurations;
    private final ThreadMcpCatalogPort catalogs;
    private final TaskCapabilityCeilingPort taskCeilings;

    /** 分别使用会话、工作区、配置和 MCP 的既有应用边界，避免复制所有权。 */
    public ThreadMcpService(ThreadUseCase threads, WorkspaceUseCase workspaces,
                            ConfigurationGenerationPort configurations, ThreadMcpCatalogPort catalogs,
                            TaskCapabilityCeilingPort taskCeilings) {
        this.threads = Objects.requireNonNull(threads, "threads");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.taskCeilings = Objects.requireNonNull(taskCeilings, "taskCeilings");
    }

    /** 只读取内存观测和当前配置元数据，不执行 MCP 传输 IO。 */
    @Override
    public ReadResult read(String threadId) {
        ThreadSnapshot snapshot = requireThreadSnapshot(threadId);
        ThreadSummary thread = snapshot.thread();
        Workspace workspace = requireWorkspace(thread.workspaceId());
        Path root = workspace.root();
        try (ConfigurationGenerationPort.Lease lease = configurations.acquire(root)) {
            ConfigurationGenerationSnapshot generation = lease.snapshot();
            CollaborationMode mode = thread.preferences().collaborationMode();
            Optional<ThreadMcpCatalogPort.Observation> previous = catalogs.observation(threadId);
            String currentCeiling = taskCeilings.read(threadId)
                    .map(ThreadMcpIdentity::ceilingFingerprint).orElse(null);
            List<Notice> notices = new java.util.ArrayList<>();
            if (workspace.kind() == Workspace.Kind.PROJECT && !generation.trusted()) {
                notices.add(Notice.PROJECT_UNTRUSTED);
            }
            if (generation.projectMcpIssue()) notices.add(Notice.PROJECT_CONFIG_ERROR);
            if (previous.isPresent() && active(thread, snapshot, previous.orElseThrow())
                    && previous.orElseThrow().workspaceId().equals(thread.workspaceId())
                    && previous.orElseThrow().workspaceRoot().equals(root)) {
                ThreadMcpCatalogPort.Observation observation = previous.orElseThrow();
                if (!matches(observation, thread, workspace, generation, mode, currentCeiling)
                        || !catalogs.current(observation)) {
                    notices.add(Notice.CONFIGURATION_CHANGED);
                }
                return new ReadResult(threadId, Source.ACTIVE, observation.catalogRevision(),
                        observation.observedAt(), publicServers(observation), notices);
            }
            if (previous.isPresent() && matches(previous.orElseThrow(), thread, workspace, generation,
                    mode, currentCeiling) && catalogs.current(previous.orElseThrow())) {
                ThreadMcpCatalogPort.Observation observation = previous.orElseThrow();
                return new ReadResult(threadId, Source.LAST_OBSERVED,
                        observation.catalogRevision(), observation.observedAt(), publicServers(observation), notices);
            }
            if (previous.isPresent()) {
                notices.add(Notice.CONFIGURATION_CHANGED);
                return new ReadResult(threadId, Source.STALE, null, null,
                        configuredServers(generation, mode, State.NOT_DISCOVERED), notices);
            }
            return new ReadResult(threadId, Source.UNCHECKED, null, null,
                    configuredServers(generation, mode, State.NOT_DISCOVERED), notices);
        } catch (ThreadMcpUseCase.Failure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new Failure("MCP_CONFIGURATION_UNAVAILABLE");
        }
    }

    /** 通过单项历史投影读取权威会话头，避免另造身份来源。 */
    private ThreadSnapshot requireThreadSnapshot(String threadId) {
        try {
            return threads.readThread(threadId, null, THREAD_SNAPSHOT_LIMIT)
                    .orElseThrow(() -> new Failure("THREAD_NOT_FOUND"));
        } catch (ThreadMcpUseCase.Failure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new Failure("THREAD_NOT_FOUND");
        }
    }

    /** 只使用宿主已绑定的工作区身份，RPC 输入不提供文件系统路径。 */
    private Workspace requireWorkspace(String workspaceId) {
        try {
            return workspaces.requireOpenWorkspace(workspaceId);
        } catch (RuntimeException failure) {
            throw new Failure("WORKSPACE_UNAVAILABLE");
        }
    }

    /** 会话、工作区、配置代际和暴露模式全部一致时观测才算当前状态。 */
    private static boolean matches(ThreadMcpCatalogPort.Observation observation, ThreadSummary thread,
                                   Workspace workspace, ConfigurationGenerationSnapshot generation,
                                   CollaborationMode mode, String ceilingFingerprint) {
        return observation.workspaceId().equals(thread.workspaceId())
                && observation.workspaceRoot().equals(workspace.root())
                && observation.generationId().equals(generation.generationId())
                && observation.collaborationMode() == mode
                && observation.preferenceFingerprint().equals(
                        ThreadMcpIdentity.preferenceFingerprint(thread.preferences()))
                && Objects.equals(observation.ceilingFingerprint(), ceilingFingerprint);
    }

    /** 仅最近运行中的 Turn 拥有此观测；较新的排队 Turn 不能借用它。 */
    private static boolean active(ThreadSummary thread, ThreadSnapshot snapshot,
                                  ThreadMcpCatalogPort.Observation observation) {
        String latestTurnId = snapshot.turns().isEmpty() ? null : snapshot.turns().getLast().turnId();
        return isActiveObservation(thread.latestTurnStatus(), latestTurnId, observation.turnId());
    }

    /** 排队或更新的 Turn 不得把旧 Provider 派发标为本轮 MCP 状态。 */
    static boolean isActiveObservation(TurnState latestStatus, String latestTurnId, String observedTurnId) {
        return Objects.equals(latestTurnId, observedTurnId)
                && (latestStatus == TurnState.RUNNING || latestStatus == TurnState.WAITING_APPROVAL);
    }

    /** 当前目录的状态只来自会话配置；未知健康度不能推断为已连接或零工具。 */
    private List<Server> configuredServers(ConfigurationGenerationSnapshot generation, CollaborationMode mode,
                                           State fallback) {
        return generation.mcpDefinitions().stream()
                .sorted(Comparator.comparing(ConfigurationGenerationSnapshot.McpServer::mcpId))
                .map(server -> {
                    State state = !server.enabled() ? State.DISABLED
                            : mode == CollaborationMode.PLAN ? State.NOT_EXPOSED : fallback;
                    return new Server(server.mcpId(), server.name(), publicScope(server.scope()),
                            state, null, null);
                }).toList();
    }

    /** 将共享网关的脱敏记录投影为稳定的会话 MCP DTO。 */
    private static List<Server> publicServers(ThreadMcpCatalogPort.Observation observation) {
        return observation.servers().stream()
                .map(server -> publicServer(server, observation.scopes().get(server.serverId()))).toList();
    }

    /** 观测结果必须使用当时冻结的来源，配置变化不能把旧项目服务误标为全局。 */
    private static Server publicServer(McpGateway.McpServerStatus server,
                                       ConfigurationGenerationSnapshot.Scope scope) {
        State state = switch (server.state()) {
            case "available" -> State.AVAILABLE;
            case "unavailable" -> State.UNAVAILABLE;
            case "disabled" -> State.DISABLED;
            case "not_discovered" -> State.NOT_DISCOVERED;
            case "not_exposed" -> State.NOT_EXPOSED;
            default -> State.STALE;
        };
        return new Server(server.serverId(), server.name(), publicScope(scope),
                state, server.toolCount(), server.reasonCode());
    }

    /** 在应用边界穷举来源，禁止缺失身份默认为全局。 */
    private static Scope publicScope(ConfigurationGenerationSnapshot.Scope scope) {
        return switch (Objects.requireNonNull(scope, "scope")) {
            case GLOBAL -> Scope.GLOBAL;
            case PROJECT -> Scope.PROJECT;
        };
    }
}
