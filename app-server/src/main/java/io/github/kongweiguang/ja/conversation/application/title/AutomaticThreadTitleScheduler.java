// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.title;

import io.github.kongweiguang.ja.conversation.port.in.ThreadMetadataEventSink;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseable;

import java.time.Duration;
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
     * 绑定首次成功回复、终态 revision 与发送前解析的请求配置；完整内容只停留在进程内。
     */
    record Request(String threadId, String turnId, long terminalThreadRevision,
                   String firstUserRequest, String assistantReply,
                   RequestRuntimeFactory runtimeFactory) {
        /** 调度边界只保留稳定事实和延迟 factory，不能提前解析或延长请求环境。 */
        public Request {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(turnId, "turnId");
            if (terminalThreadRevision < 0) throw new IllegalArgumentException("invalid terminal revision");
            Objects.requireNonNull(firstUserRequest, "firstUserRequest");
            Objects.requireNonNull(assistantReply, "assistantReply");
            Objects.requireNonNull(runtimeFactory, "runtimeFactory");
        }
    }

    /** 标题任务在真正调用模型前请求一次当前环境，完成后必须释放其底层配置租约。 */
    @FunctionalInterface
    interface RequestRuntimeFactory {
        /** timeout 是标题请求的独立上限，解析器不得沿用已结束 Turn 的剩余时长。 */
        RequestRuntime open(Duration timeout);
    }

    /** 只向标题服务暴露所需模型配置，同时保留底层租约的唯一释放责任。 */
    record RequestRuntime(ModelPort.ModelConfiguration configuration,
                          AutoCloseable release) implements AutoCloseable {
        /** 配置和释放动作必须成对，避免后台失败路径泄漏凭据代际。 */
        public RequestRuntime {
            Objects.requireNonNull(configuration, "configuration");
            Objects.requireNonNull(release, "release");
        }

        /** checked close 统一收敛，后台任务会记录稳定错误分类而不泄漏配置。 */
        @Override
        public void close() {
            try {
                release.close();
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("title request runtime release failed", failure);
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
