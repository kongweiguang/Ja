// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.error;

import java.util.Objects;

/**
 * 跨存储端口传播的稳定失败类型，不暴露 JDBC、SQL、路径或持久化实现细节。
 */
public final class StorageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * 供应用和入站适配器决策的粗粒度存储失败分类。
     */
    public enum Code {
        /**
         * 存储启动参数不满足新基线约束。
         */
        INVALID_CONFIGURATION,
        /**
         * 数据目录已被另一个进程实例占用。
         */
        INSTANCE_LOCKED,
        /**
         * 存储 owner 已进入关闭状态。
         */
        CLOSED,
        /**
         * 写入 owner 未能在截止时间内确认关闭。
         */
        WRITER_CLOSE_UNCONFIRMED,
        /**
         * 有界写队列已满。
         */
        QUEUE_FULL,
        /**
         * 写事务未能在有界等待内开始。
         */
        QUEUE_TIMEOUT,
        /**
         * 发生已脱敏的底层 I/O 失败。
         */
        IO,
        /**
         * 事务未能原子提交。
         */
        TRANSACTION,
        /**
         * 目录或数据库不符合全新代际标记。
         */
        FRESH_SCHEMA_REQUIRED,
        /**
         * 存储代际或实例身份发生冲突。
         */
        STORAGE_CONFLICT,
        /**
         * CAS 版本已经过期。
         */
        CAS_CONFLICT,
        /**
         * 请求的领域实体不存在。
         */
        NOT_FOUND,
        /**
         * 持久化事实违反领域状态约束。
         */
        INVALID_STATE
    }

    private final Code code;

    /**
     * 保存稳定错误码与脱敏消息，调用者只能据此决定重试或失败。
     */
    public StorageException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    /**
     * 额外保留仅供本地日志使用的根因，但根因不得跨 RPC 边界。
     */
    public StorageException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    /**
     * 返回稳定的粗粒度分类，不提供底层异常文本。
     */
    public Code code() {
        return code;
    }
}
