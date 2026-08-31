// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import io.github.kongweiguang.ja.conversation.port.out.ModelEventSink;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provider 事件状态机共享的请求作用域语义边界。
 */
public final class StreamContext {
    private final ModelEventSink sink;
    private final AtomicBoolean semanticAccepted;
    private final RequestController controller;
    private final ModelPort.ModelRequest request;

    /**
     * 将 sink、重试门禁、取消 Controller 和 Tool Catalog 固定到单次请求。
     */
    public StreamContext(ModelEventSink sink, AtomicBoolean semanticAccepted,
                  RequestController controller, ModelPort.ModelRequest request) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.semanticAccepted = Objects.requireNonNull(semanticAccepted, "semanticAccepted");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.request = Objects.requireNonNull(request, "request");
    }

    /**
     * 读取更多 Provider 字节或重试前，等待 sink 持久接受事件。
     */
    public void emit(ModelPort.ModelEvent event) {
        CompletableFuture<Void> accepted = controller.beginSink(sink, event);
        try {
            while (true) {
                try {
                    accepted.get(25, java.util.concurrent.TimeUnit.MILLISECONDS);
                    semanticAccepted.set(true);
                    break;
                } catch (TimeoutException ignored) {
                    controller.throwIfStopped();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            controller.throwIfStopped();
            throw new ProviderProtocolException(
                    "EVENT_SINK", "model event sink wait was interrupted", false, interrupted);
        } catch (ExecutionException | CancellationException failure) {
            controller.throwIfStopped();
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            throw new ProviderProtocolException(
                    "EVENT_SINK", "model event sink rejected an event", false, cause);
        } finally {
            controller.clearSink(accepted);
        }
        controller.throwIfStopped();
    }

    /**
     * 按冻结名称查找 Tool，并拒绝接纳目录外的调用。
     */
    public io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec tool(String name) {
        return request.tools().stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new ProviderProtocolException(
                        "UNKNOWN_TOOL", "provider requested an unknown tool", false));
    }
}
