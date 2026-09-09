// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.port.out;

/** Goal 仓储稳定失败闭集，RPC adapter 可一对一映射公开错误码。 */
public final class GoalRepositoryException extends RuntimeException {
    /** RPC 可稳定映射的 Goal 错误闭集。 */
    public enum Code {
        /** Goal 或 owner 不存在。 */
        GOAL_NOT_FOUND,
        /** expectedGoalRevision 已过期。 */ GOAL_REVISION_CONFLICT,
        /** 当前状态不允许请求动作。 */ GOAL_INVALID_STATE,
        /** 结构化计划或 DAG 非法。 */ PLAN_INVALID,
        /** 批准的 revision/hash 不是当前精确版本。 */ PLAN_APPROVAL_STALE,
        /** 必要步骤、证据或 evaluator 未完成。 */ GOAL_EVIDENCE_INCOMPLETE,
        /** 未知副作用或 workspace identity 需要人工恢复。 */ GOAL_RECOVERY_REQUIRED,
        /** 输入请求不存在、已响应或已过期。 */ GOAL_INPUT_EXPIRED
    }

    private final Code code;

    /** 消息不得包含 plan JSON、证据正文或工作区路径。 */
    public GoalRepositoryException(Code code, String message) {
        super(message);
        this.code = java.util.Objects.requireNonNull(code, "code");
    }

    /** 返回稳定错误分类，调用方禁止按 message 分支。 */
    public Code code() { return code; }
}
