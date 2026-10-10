// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 仅承载 admission 同事务需要的 Ja 身份与显式关联，不复制 Core 执行游标或消息历史。
 *
 * @param identity 包含租户、对话、执行与用户的完整身份
 * @param workspaceId 工作区稳定标识
 * @param origin 来源分类
 * @param method 方法
 * @param acceptedAt 受理时间
 * @param sourceMessageId 来源消息标识
 * @param parentTurnId 父执行标识
 * @param rootTurnId 根任务执行标识
 * @param planRevisionId Plan 修订版本标识
 * @param runId 运行标识
 * @param operationKey 客户端操作标识
 * @param requestFingerprint 请求指纹
 * @param expectedThreadRevision 预期对话修订版本
 * @param newThread 新增对话（可选）
 */
public record AdmissionFacts(AgentIdentity identity,
                             String workspaceId,
                             TurnOrigin origin,
                             String method,
                             Instant acceptedAt,
                             String sourceMessageId,
                             String parentTurnId,
                             String rootTurnId,
                             String planRevisionId,
                             String runId,
                             String operationKey,
                             String requestFingerprint,
                             long expectedThreadRevision,
                             Optional<ThreadInitialization> newThread) {
    /**
     * 校验租户、来源身份和关联闭集，避免存储参与者根据缺失字段推导业务关系。
     */
    public AdmissionFacts {
        Objects.requireNonNull(identity, "identity");
        if (!"ja.local".equals(identity.tenantId()) || !"user_local".equals(identity.userId())
            || !identity.threadId().startsWith("thr_")
            || !identity.turnId().startsWith("turn_")) {
            throw new IllegalArgumentException("Ja admission identity is invalid");
        }
        workspaceId = prefixedIdentifier(workspaceId, "ws_", "workspaceId");
        Objects.requireNonNull(origin, "origin");
        method = ContractChecks.text(method, "method", 96, false);
        validateMethodOrigin(method, origin);
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        sourceMessageId = optionalIdentifier(sourceMessageId, "sourceMessageId");
        parentTurnId = optionalPrefixedIdentifier(parentTurnId, "turn_", "parentTurnId");
        rootTurnId = optionalPrefixedIdentifier(rootTurnId, "turn_", "rootTurnId");
        planRevisionId = optionalIdentifier(planRevisionId, "planRevisionId");
        runId = optionalIdentifier(runId, "runId");
        operationKey = optionalIdentifier(operationKey, "operationKey");
        if ((operationKey == null) != (requestFingerprint == null)
            || (requestFingerprint != null && !requestFingerprint.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("client operation identity is incomplete");
        }
        if (expectedThreadRevision < 0) {
            throw new IllegalArgumentException("invalid expectedThreadRevision");
        }
        newThread = Objects.requireNonNull(newThread, "newThread");
        if (newThread.isPresent()) {
            ThreadInitialization value = newThread.orElseThrow();
            if (origin != TurnOrigin.CHILD_TASK || !value.workspaceId().equals(workspaceId)) {
                throw new IllegalArgumentException("new Ja Thread metadata does not match the admission");
            }
        }
        validateOriginIdentity(method, origin, parentTurnId, rootTurnId, planRevisionId, runId);
    }

    /**
     * 只接受当前 RPC/Coordinator 的稳定方法名与来源搭配，拒绝把来源伪装成另一类执行。
     */
    private static void validateMethodOrigin(String method, TurnOrigin origin) {
        boolean valid = switch (origin) {
            case USER -> method.equals("turn/start") || method.equals("turn/reask");
            case USER_CONTINUATION -> method.equals("turn/continue");
            case CHILD_TASK -> method.equals("task/create") || method.equals("task/followup");
            case PLAN_EXECUTION -> method.equals("plan/execute");
        };
        if (!valid) {
            throw new IllegalArgumentException("Ja admission method and origin do not match");
        }
    }

    /**
     * Plan 与 Child 的父执行身份按领域来源成组校验，不从路径或会话类型推导。
     */
    private static void validateOriginIdentity(String method, TurnOrigin origin,
                                               String parentTurnId, String rootTurnId,
                                               String planRevisionId, String runId) {
        switch (origin) {
            case PLAN_EXECUTION -> {
                if (planRevisionId == null || runId == null
                    || parentTurnId != null || rootTurnId != null) {
                    throw new IllegalArgumentException("Plan admission requires only its Plan Run identity");
                }
            }
            case CHILD_TASK -> {
                boolean childCreation = "task/create".equals(method);
                if ((childCreation && (parentTurnId == null || rootTurnId == null))
                    || ((!childCreation) && (parentTurnId == null) != (rootTurnId == null))
                    || planRevisionId != null || runId != null) {
                    throw new IllegalArgumentException("Child admission has invalid causal Turn identities");
                }
            }
            case USER, USER_CONTINUATION -> {
                if (parentTurnId != null || rootTurnId != null || planRevisionId != null
                    || runId != null) {
                    throw new IllegalArgumentException("ordinary admission cannot carry internal execution identities");
                }
            }
        }
    }

    /**
     * 可选来源身份仍按同一稳定标识语法校验，缺失保持 null 而不写入文本占位。
     */
    private static String optionalIdentifier(String value, String field) {
        return value == null ? null : ContractChecks.identifier(value, field);
    }

    /**
     * 关系键必须是明确的 Turn ID，避免将 Workspace、Thread 或任意文本写入父子列。
     */
    private static String optionalPrefixedIdentifier(String value, String prefix, String field) {
        return value == null ? null : prefixedIdentifier(value, prefix, field);
    }

    /**
     * Workspace 和 Turn 身份沿用 Ja 公开前缀及稳定字符集，不对持久键做静默修复。
     */
    private static String prefixedIdentifier(String value, String prefix, String field) {
        String checked = ContractChecks.identifier(value, field);
        if (!checked.startsWith(prefix)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return checked;
    }

    /**
     * 新建 Thread 的宿主元数据仅在首次 Child admission 时交给同事务参与者。
     *
     * @param workspaceId 工作区稳定标识
     * @param title 显示标题
     * @param preferences 偏好配置
     * @param createdAt 创建时间
     */
    public record ThreadInitialization(String workspaceId,
                                       String title,
                                       ThreadPreferences preferences,
                                       Instant createdAt) {
        /**
         * 持久化标题和偏好必须明确提供，不能由 store 或运行时配置猜默认值。
         */
        public ThreadInitialization {
            workspaceId = prefixedIdentifier(workspaceId, "ws_", "workspaceId");
            title = ContractChecks.text(title, "title", 512, false);
            Objects.requireNonNull(preferences, "preferences");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }
}
