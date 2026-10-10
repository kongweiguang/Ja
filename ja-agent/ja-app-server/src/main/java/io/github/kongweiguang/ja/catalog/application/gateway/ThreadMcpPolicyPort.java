// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.application.gateway;

import io.github.kongweiguang.ja.agent.domain.turn.TurnState;

import java.nio.file.Path;
import java.util.List;

/**
 * Catalog 消费方定义的 Thread MCP 策略端口；跨上下文实体与配置投影只在 ACL 内解析。
 */
public interface ThreadMcpPolicyPort {
    /** 读取租约前需要的工作区根目录。 */
    Path workspaceRoot(String threadId);

    /** 在指定配置租约内读取冻结的会话上下文。 */
    Context context(String threadId, ConfigurationGenerationPort.Lease lease);

    /** 判断旧观测是否仍与当前会话身份一致。 */
    boolean matches(ThreadMcpCatalogPort.Observation observation, Context context, String ceilingFingerprint);

    /** 判断观测是否属于当前仍运行或等待审批的 Turn。 */
    boolean active(Context context, ThreadMcpCatalogPort.Observation observation);

    /** 把脱敏 MCP 观测转换为 Catalog 自有服务视图。 */
    List<ServerView> publicServers(ThreadMcpCatalogPort.Observation observation);

    /** 把配置代际转换为尚未发现的服务视图。 */
    List<ServerView> configuredServers(ConfigurationGenerationPort.Lease lease,
                                       String collaborationMode, State fallback);

    /** 当前项目是否因信任边界而不可读取。 */
    boolean projectUntrusted(Context context);

    /**
     * Catalog 自有的冻结会话上下文。
     * @param workspaceId Workspace 工作区标识
     * @param root 工作区根目录
     * @param project 是否绑定项目
     * @param workspaceTrusted Workspace 是否受信
     * @param generationId 配置代际
     * @param generationTrusted 配置代际是否受信
     * @param projectMcpIssue 项目 MCP 配置问题
     * @param collaborationMode 协作模式
     * @param preferenceFingerprint 偏好指纹
     * @param latestTurnStatus 最近 Turn 状态
     * @param latestTurnId 最近 Turn 执行标识
     */
    record Context(String workspaceId,
                   Path root,
                   boolean project,
                   boolean workspaceTrusted,
                   String generationId,
                   boolean generationTrusted,
                   boolean projectMcpIssue,
                   String collaborationMode,
                   String preferenceFingerprint,
                   TurnState latestTurnStatus,
                   String latestTurnId) {
    }

    /**
     * Catalog 自有的脱敏 MCP 服务视图。
     * @param serverId 服务 identity
     * @param name 服务名称
     * @param scope 服务作用域
     * @param state 服务状态
     * @param toolCount 工具数量
     * @param reasonCode 不可用原因码
     */
    record ServerView(String serverId,
                      String name,
                      String scope,
                      String state,
                      Integer toolCount,
                      String reasonCode) {
    }

    /** 配置尚未发现时的状态闭集。 */
    enum State {
        /** 尚未完成 MCP 服务发现。 */
        NOT_DISCOVERED
    }
}

