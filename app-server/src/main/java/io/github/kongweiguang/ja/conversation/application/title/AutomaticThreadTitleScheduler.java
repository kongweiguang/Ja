// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.title;

import io.github.kongweiguang.ja.conversation.domain.TurnRuntimeSnapshot;
import io.github.kongweiguang.ja.conversation.port.in.ThreadMetadataEventSink;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseable;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * 在 Turn 终态发布后异步生成首次会话标题，并拥有其后台任务的关闭边界。
 */
@FunctionalInterface
public interface AutomaticThreadTitleScheduler extends DeadlineCloseable {
    /**
     * 非阻塞接纳标题任务；返回阶段只描述后台工作，不参与已完成 Turn 的主结果。
     */
    CompletionStage<Void> schedule(Request request, ThreadMetadataEventSink sink);

    /** 无后台资源的实现无需在普通关闭时执行动作。 */
    @Override
    default void close() {
    }

    /** 无后台资源的实现无需消费进程关闭预算。 */
    @Override
    default void closeAt(long shutdownDeadlineNanos) {
    }

    /**
     * 绑定首次成功回复、终态 revision 与仍有效的冻结配置；完整内容只停留在进程内。
     */
    record Request(String threadId, String turnId, long terminalThreadRevision,
                   String firstUserRequest, String assistantReply,
                   TurnRuntimeSnapshot runtime, ModelPort.ModelConfiguration configuration) {
        /** 请求只能引用同一个冻结 Provider/Model/配置代际，禁止后台读取当前 Thread 偏好。 */
        public Request {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(turnId, "turnId");
            if (terminalThreadRevision < 0) throw new IllegalArgumentException("invalid terminal revision");
            Objects.requireNonNull(firstUserRequest, "firstUserRequest");
            Objects.requireNonNull(assistantReply, "assistantReply");
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(configuration, "configuration");
            if (!runtime.providerId().equals(configuration.providerId())
                || !runtime.modelId().equals(configuration.modelId())
                || !runtime.configGeneration().equals(configuration.configGeneration())
                || !runtime.upstreamModel().equals(configuration.model())) {
                throw new IllegalArgumentException("title request does not match frozen Turn runtime");
            }
        }
    }

    /** 测试和 AOT 占位不创建线程，生产组合根必须显式注入真实实现。 */
    static AutomaticThreadTitleScheduler disabled() {
        return (request, sink) -> {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(sink, "sink");
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        };
    }
}
