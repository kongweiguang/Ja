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
    private final ModelAttemptReplay replay;
    private final boolean replaying;

    /**
     * 将 sink、重试门禁、取消 Controller 和 Tool Catalog 固定到单次请求。
     */
    public StreamContext(ModelEventSink sink, AtomicBoolean semanticAccepted,
                  RequestController controller, ModelPort.ModelRequest request) {
        this(sink, semanticAccepted, controller, request, new ModelAttemptReplay(), false);
    }

    /**
     * 绑定共享回放账本；只有重试尝试启用前缀过滤，首次尝试仍保持原有实时流式语义。
     */
    StreamContext(ModelEventSink sink, AtomicBoolean semanticAccepted,
                  RequestController controller, ModelPort.ModelRequest request,
                  ModelAttemptReplay replay, boolean replaying) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.semanticAccepted = Objects.requireNonNull(semanticAccepted, "semanticAccepted");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.request = Objects.requireNonNull(request, "request");
        this.replay = Objects.requireNonNull(replay, "replay");
        this.replaying = replaying;
        replay.beginAttempt(replaying);
    }

    /**
     * 读取更多 Provider 字节或重试前，等待 sink 持久接受事件。
     */
    public void emit(ModelPort.ModelEvent event) {
        ModelPort.ModelEvent delivered;
        try {
            delivered = replay.prepare(event, replaying);
        } catch (ModelAttemptReplay.ReplayMismatchException mismatch) {
            throw new ProviderProtocolException(
                    "STREAM_REPLAY_MISMATCH", "provider retry response changed its accepted prefix", false,
                    mismatch);
        }
        if (delivered == null) {
            semanticAccepted.set(true);
            controller.throwIfStopped();
            return;
        }
        CompletableFuture<Void> accepted = controller.beginSink(sink, delivered);
        try {
            while (true) {
                try {
                    accepted.get(25, java.util.concurrent.TimeUnit.MILLISECONDS);
                    replay.accepted(delivered, replaying);
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
     * 在 Provider 宣布正常完成前收口重试回放；只把账本校验失败映射为稳定协议错误，首次尝试不增加门禁。
     */
    void verifyReplayComplete() {
        try {
            replay.verifyComplete(replaying);
        } catch (ModelAttemptReplay.ReplayMismatchException mismatch) {
            throw new ProviderProtocolException(
                    "STREAM_REPLAY_MISMATCH", "provider retry response did not complete its accepted prefix", false,
                    mismatch);
        }
    }

    /**
     * 只为已知 Tool 恢复 OpenAI strict 参数；目录外名称返回空值并由 Runner 回传失败结果。
     */
    public io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec tool(String name) {
        return request.tools().stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElse(null);
    }
}
