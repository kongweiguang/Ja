// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.review.application.api;

import java.util.Objects;

/**
 * Review 用例可观察的稳定失败合同；传输适配器按分类映射，不解析存储或 Provider 诊断。
 */
public final class ReviewFailure extends RuntimeException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final Failure failure;

    /**
     * 只携带稳定失败分类，避免业务错误意外暴露实现细节。
     *
     * @param failure 固定的 Review 失败类别
     */
    public ReviewFailure(Failure failure) {
        this(failure, null);
    }

    /**
     * 保留进程内原因供诊断，调用方仍只能读取稳定枚举。
     *
     * @param failure 固定的 Review 失败类别
     * @param cause 仅供进程内定位的原始原因
     */
    public ReviewFailure(Failure failure, Throwable cause) {
        super("review operation failed", cause, false, false);
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    /**
     * 返回闭集业务错误类别，使边缘映射无需观察异常消息。
     */
    public Failure failure() {
        return failure;
    }

    /**
     * Review 接口稳定暴露的失败分类。
     */
    public enum Failure {
        /** 目标 Thread 不存在。 */
        THREAD_NOT_FOUND,
        /** 目标 Turn 不存在或不属于指定 Thread。 */
        TURN_NOT_FOUND,
        /** 目标 Turn 尚无可公开的有效完成结果。 */
        TURN_UNAVAILABLE,
        /** 当前工作区或其准入状态不可用。 */
        WORKSPACE_UNAVAILABLE,
        /** Thread revision、幂等身份或执行状态发生冲突。 */
        CONFLICT,
        /** 冻结快照或报告 artifact 无法验证或读取。 */
        REPORT_UNAVAILABLE
    }
}
