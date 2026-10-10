// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.review.interfaces.tools;

import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeMiddlewareFactory;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeRequest;

import java.util.List;
import java.util.Objects;

/**
 * 将可信 Turn 上下文适配为 Review 专用完成校验；Review 实现通过 Conversation 端口接入通用 Resolver。
 */
public final class ReviewTurnRuntimeMiddlewareFactory implements TurnRuntimeMiddlewareFactory {
    private final JsonValueCodec codec;

    /**
     * 复用组合根持有的 JSON 边界，不在运行时解析器内直接创建 Review 或 Jackson 实现。
     */
    public ReviewTurnRuntimeMiddlewareFactory(JsonValueCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /**
     * 每次解析都从持久准入恢复 Review 身份，普通 Turn 返回空列表且不创建额外状态。
     */
    @Override
    public List<AgentMiddleware> create(TurnRuntimeRequest request) {
        Objects.requireNonNull(request, "request");
        return request.reviewContext().<List<AgentMiddleware>>map(context ->
                List.of(new ReviewCompletionMiddleware(context, codec))).orElseGet(List::of);
    }
}
