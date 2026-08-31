// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.domain;

import java.util.Objects;

/**
 * 在 workspace 边界内传播脱敏失败，避免 RPC 看到路径、文件系统或持久化实现细节。
 */
public final class WorkspaceFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Code code;

    /**
     * 保存可稳定映射到 RPC errorCode 的失败分类。
     */
    public WorkspaceFailure(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    /**
     * 仅为本地诊断保留根因，RPC 映射不得序列化根因文本或堆栈。
     */
    public WorkspaceFailure(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    /**
     * 返回稳定分类，调用方不需要识别底层异常类型。
     */
    public Code code() {
        return code;
    }

    /**
     * 工作区应用与文件适配器允许对外暴露的失败闭集。
     */
    public enum Code {
        /**
         * 请求路径不存在、不是目录或当前不可访问。
         */
        DIRECTORY_UNAVAILABLE,
        /**
         * 符号链接、junction、reparse point 或物理路径偏移违反目录约束。
         */
        DIRECTORY_CONFINEMENT,
        /**
         * Java 数据目录无法安全创建通用工作区。
         */
        GENERAL_WORKSPACE_UNAVAILABLE,
        /**
         * 当前进程已达到可绑定目录数量上限。
         */
        CAPACITY_EXHAUSTED,
        /**
         * workspaceId 没有对应的进程内物理目录能力。
         */
        WORKSPACE_NOT_OPEN,
        /**
         * 持久化身份、规范根目录或派生身份互相冲突。
         */
        IDENTITY_CONFLICT,
        /**
         * 通用工作区收到了不允许的信任状态。
         */
        TRUST_CONFLICT
    }
}
