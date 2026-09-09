// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.domain;

import java.util.Objects;

/**
 * 路径搜索和引用准入的脱敏失败闭集，禁止把绝对路径或底层异常文本带到 Wire。
 */
public final class WorkspacePathFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Code code;

    /** 保存可由调用边界稳定映射的失败分类。 */
    public WorkspacePathFailure(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    /** 根因只供本地诊断，调用方不得序列化其路径或平台细节。 */
    public WorkspacePathFailure(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    /** 返回稳定错误类别，避免消费者解析异常 message。 */
    public Code code() {
        return code;
    }

    /** 路径能力允许跨模块识别的最小失败集合。 */
    public enum Code {
        /** Thread 的权威 Workspace 与引用声明不一致。 */
        WORKSPACE_MISMATCH,
        /** 相对路径包含绝对注入、遍历、NUL 或非法语法。 */
        INVALID_PATH,
        /** 引用目标已经删除或当前不可访问。 */
        PATH_UNAVAILABLE,
        /** 目标不是声明的 file/directory 类型。 */
        TYPE_MISMATCH,
        /** 路径包含链接、junction、reparse point 或物理逃逸。 */
        CONFINEMENT,
        /** 文件系统暂时无法完成有界枚举或 metadata 检查。 */
        IO_UNAVAILABLE
    }
}
