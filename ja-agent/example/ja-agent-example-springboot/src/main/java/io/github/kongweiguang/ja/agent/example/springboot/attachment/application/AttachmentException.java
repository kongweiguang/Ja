// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.attachment.application;

/**
 * 附件边界错误携带固定 HTTP 分类，不暴露 SQL、文件路径或上传正文。
 */
public final class AttachmentException extends RuntimeException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final Reason reason;

    /**
     * 保存稳定错误分类，公开消息只供 HTTP handler 投影，不拼接不可信输入。
     */
    public AttachmentException(Reason reason) {
        super(reason.publicMessage());
        this.reason = reason;
    }

    /** 保留内部故障 cause 供诊断，同时只使用稳定业务文案作为异常消息。 */
    public AttachmentException(Reason reason, Throwable cause) {
        super(reason.publicMessage(), cause);
        this.reason = reason;
    }

    /**
     * 返回客户端可见的固定错误分类。
     */
    public Reason reason() {
        return reason;
    }

    /**
     * 将附件业务边界映射为可修正的参数、媒体、大小、归属或状态结果。
     */
    public enum Reason {
        /** 请求字段、标识或文件元数据无效。 */
        INVALID(400, "Invalid attachment request."),
        /** 媒体类型未同时获模型声明和 Provider Codec 支持。 */
        UNSUPPORTED(415, "This file format is not supported by the selected model."),
        /** 单个附件超过模型可编码的字节上限。 */
        TOO_LARGE(413, "The file exceeds the selected model's attachment limit."),
        /** 当前上下文与新增附件合计超过单次模型请求上限。 */
        REQUEST_TOO_LARGE(413, "The current conversation attachments exceed the selected model's request limit. "
                + "Compress or remove some files, then retry."),
        /** 附件或其 Thread 归属不存在。 */
        NOT_FOUND(404, "Attachment resource not found."),
        /** 同一操作与已保存附件状态冲突。 */
        CONFLICT(409, "The attachment operation conflicts with stored state."),
        /** 附件草稿已丢弃或过期，必须重新上传。 */
        GONE(410, "This attachment draft was discarded or expired. Upload it again with a new operation key.");

        private final int status;
        private final String publicMessage;

        /**
         * 固定状态与文案避免将持久内容或内部数据库原因回显给浏览器。
         */
        Reason(int status, String publicMessage) {
            this.status = status;
            this.publicMessage = publicMessage;
        }

        /**
         * 返回稳定的 HTTP 状态码。
         */
        public int status() {
            return status;
        }

        /**
         * 返回不包含请求参数的固定说明。
         */
        public String publicMessage() {
            return publicMessage;
        }
    }
}
