// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.gateway;

/**
 * Plan 仓储稳定失败闭集，RPC adapter 可一对一映射公开错误码。
 */
public final class PlanRepositoryException extends RuntimeException {
    private final Code code;

    /**
     * 稳定消息不得包含 Plan JSON、证据正文或工作区路径。
     */
    public PlanRepositoryException(Code code, String message) {
        this(code, message, null);
    }

    /**
     * 保留进程内持久化或解析 cause；调用方仍必须按稳定 code 映射公开错误。
     */
    public PlanRepositoryException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = java.util.Objects.requireNonNull(code, "code");
    }

    /**
     * 返回稳定错误分类，调用方禁止按 message 分支。
     */
    public Code code() {
        return code;
    }

    /**
     * RPC 可稳定映射的 Plan 错误闭集。
     */
    public enum Code {
        /**
         * Plan 或 owner 不存在。
         */
        PLAN_NOT_FOUND,
        /**
         * expectedPlanRevision 已过期。
         */
        PLAN_REVISION_CONFLICT,
        /**
         * 当前状态不允许请求动作。
         */
        PLAN_INVALID_STATE,
        /**
         * 结构化计划或 DAG 非法。
         */
        PLAN_INVALID,
        /**
         * 批准的 revision/hash 不是当前精确版本。
         */
        PLAN_APPROVAL_STALE,
        /**
         * 必要步骤、证据或 evaluator 未完成。
         */
        PLAN_EVIDENCE_INCOMPLETE,
        /**
         * 未知副作用或 workspace identity 需要人工恢复。
         */
        PLAN_RECOVERY_REQUIRED
    }
}

