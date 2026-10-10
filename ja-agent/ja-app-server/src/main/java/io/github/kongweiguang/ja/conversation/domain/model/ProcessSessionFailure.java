// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import java.util.Objects;

/**
 * 进程会话可被调用方识别的失败语义；异常原因细节仍留在服务端，不进入协议消息。
 */
public final class ProcessSessionFailure extends RuntimeException {
    private final Code code;

    /**
     * 记录稳定失败类别，避免 Transport 或工具适配器依赖具体注册表异常类型。
     *
     * @param code 对调用边界有意义的失败类别
     */
    public ProcessSessionFailure(Code code) {
        this(code, null);
    }

    /**
     * 保留内部原因用于诊断，但不把其消息暴露给工具或 RPC 调用方。
     *
     * @param code 对调用边界有意义的失败类别
     * @param cause 内部故障原因，可为空
     */
    public ProcessSessionFailure(Code code, Throwable cause) {
        super(message(Objects.requireNonNull(code, "code")), cause);
        this.code = code;
    }

    /**
     * 返回调用方映射错误动作所需的稳定类别。
     *
     * @return 失败类别
     */
    public Code code() {
        return code;
    }

    /**
     * 使用固定消息，不让底层进程、文件系统或数据库错误内容越过应用边界。
     *
     * @param code 失败类别
     * @return 脱敏诊断消息
     */
    private static String message(Code code) {
        return switch (code) {
            case SESSION_UNAVAILABLE -> "process session is unavailable";
            case LAUNCH_FAILED -> "process launch failed";
            case WRITE_OUTCOME_UNKNOWN -> "process input outcome is unknown";
        };
    }

    /** 进程操作在不同调用路径上的可恢复与副作用状态。 */
    public enum Code {
        /** 会话不存在、属于其他身份或已不允许控制。 */
        SESSION_UNAVAILABLE,
        /** 进程无法启动，且没有向调用方承诺会话已建立。 */
        LAUNCH_FAILED,
        /** 输入可能已交付，必须禁止自动重试以免重复副作用。 */
        WRITE_OUTCOME_UNKNOWN
    }
}
