// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.interaction;

import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionRequest;

import java.util.Objects;

/** request_user_input 已把请求落库后的控制流信号；不能被普通 Tool 失败处理吞掉。 */
public final class InteractionSuspendedException extends RuntimeException {
    private final InteractionRequest request;

    /** 携带完整但尚未持久化的请求，让 Loop 能把 Interaction 与 SUSPENDED 放进同一事务。 */
    public InteractionSuspendedException(InteractionRequest request) {
        super("agent turn is waiting for user input", null, false, false);
        this.request = Objects.requireNonNull(request, "request");
    }

    /** 返回稳定请求身份，供日志和恢复调度关联而不暴露题面。 */
    public String requestId() {
        return request.requestId();
    }

    /** 返回待持久化请求，供 Loop 与 Turn 状态在同一事务中提交。 */
    public InteractionRequest request() {
        return request;
    }
}
