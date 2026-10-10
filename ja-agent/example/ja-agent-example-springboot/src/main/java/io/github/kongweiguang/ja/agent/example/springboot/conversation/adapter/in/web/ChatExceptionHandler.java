// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web;

import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.ChatNotFoundException;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 只投影稳定错误分类，原异常由宿主诊断，不向 HTTP 调用方泄露 SQL 或模型凭据。
 */
@RestControllerAdvice
public final class ChatExceptionHandler {
    /**
     * 不存在由服务层表达，HTTP 层统一映射而不回显内部资源值。
     */
    @ExceptionHandler(ChatNotFoundException.class)
    public ProblemDetail notFound() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Conversation resource not found.");
    }

    /**
     * 参数错误可以修正后重试，不直接回显包含用户正文的内部异常消息。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalidRequest() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid execution identity or command.");
    }

    /**
     * 执行权或状态冲突需先查询持久事实；不能自动换身份重新提交。
     */
    @ExceptionHandler({IllegalStateException.class, AgentStore.StoreConflictException.class,
            io.github.kongweiguang.ja.agent.skills.pg.domain.SkillVersionConflictException.class,
            io.github.kongweiguang.ja.agent.skills.pg.domain.SkillCatalogChangedException.class})
    public ProblemDetail stateConflict() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Read execution state before retrying the command.");
    }

    /**
     * 数据库暂不可用时保留原调用身份，由调用方回读事实后决定是否重试。
     */
    @ExceptionHandler(AgentStore.StoreUnavailableException.class)
    public ProblemDetail storageUnavailable() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Execution storage is unavailable.");
    }

    /**
     * 完整性错误需要宿主诊断，不能通过 HTTP 重试自动修补持久事实。
     */
    @ExceptionHandler(AgentStore.StoreIntegrityException.class)
    public ProblemDetail storageIntegrity() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Execution storage integrity check failed.");
    }
}
