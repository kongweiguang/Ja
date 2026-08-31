// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.attachment.domain;

import java.util.Objects;

/**
 * 附件入站边界的稳定失败；RPC 只映射 code，cause、路径和底层存储文本不得离开进程。
 */
public final class AttachmentFailure extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;
    private final Code code;

    /** 创建不携带底层实现消息的失败。 */
    public AttachmentFailure(Code code) {
        this(code, null);
    }

    /** cause 只用于本地诊断链，公开消息保持稳定且不包含用户内容。 */
    public AttachmentFailure(Code code, Throwable cause) {
        super("attachment operation failed", cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    /** 返回传输层可穷举映射的稳定分类。 */
    public Code code() {
        return code;
    }

    /** 错误分类按用户可恢复动作划分，不泄露文件系统或 SQLite 细节。 */
    public enum Code {
        /** token、identity、时间或 range 不满足当前协议。 */
        INVALID_REQUEST,
        /** 单文件超过 100 MiB 产品上限。 */
        TOO_LARGE,
        /** Workspace 或 Thread 可见附件不存在。 */
        NOT_FOUND,
        /** 草稿已绑定、已终结或 identity 与现有事实冲突。 */
        CONFLICT,
        /** Rust staging 已消失或被非普通节点替换。 */
        CONTENT_UNAVAILABLE,
        /** staging 在跨边界复核窗口内发生变化。 */
        CONTENT_CHANGED,
        /** 内容寻址 blob 或声明为文本的内容已损坏。 */
        CONTENT_CORRUPT,
        /** 导入、读取或回收发生可重试的脱敏 I/O 失败。 */
        IO
    }
}
