// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.attachment.adapter.in.web;

import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 上传与附件错误只暴露稳定业务分类，不把文件名、正文、SQL 或宿主路径写入 HTTP 响应。
 */
@RestControllerAdvice
public final class AttachmentExceptionHandler {
    /** 附件业务状态码保留可恢复语义，例如 410 表示应使用新 operation key 上传。 */
    @ExceptionHandler(AttachmentException.class)
    public ProblemDetail attachmentFailure(AttachmentException failure) {
        AttachmentException.Reason reason = failure.reason();
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(reason.status()), reason.publicMessage());
        detail.setProperty("code", reason.name());
        return detail;
    }

    /** Servlet multipart 总量门拒绝发生在 Controller 前，仍向调用方提供统一 413 分类。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail uploadTooLarge() {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,
                AttachmentException.Reason.TOO_LARGE.publicMessage());
        detail.setProperty("code", AttachmentException.Reason.TOO_LARGE.name());
        return detail;
    }
}
