// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import io.github.kongweiguang.ja.conversation.port.out.ModelEventSink;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import okhttp3.Call;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一持有单次请求的取消、Deadline、sink、响应流和 OkHttp Call 竞态边界。
 */
public final class RequestController {
    private final CancellationToken token;
    private final AtomicReference<Thread> thread = new AtomicReference<>();
    private final AtomicReference<Call> call = new AtomicReference<>();
    private final AtomicReference<AutoCloseable> stream = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Void>> sinkStage = new AtomicReference<>();
    private final AtomicBoolean timedOut = new AtomicBoolean();
    private final AtomicBoolean shutDown = new AtomicBoolean();
    private final AtomicBoolean done = new AtomicBoolean();
    private final Object operationGate = new Object();
    private final Object stopBarrier = new Object();
    private boolean acceptingStops = true;
    private int stopActions;

    /**
     * 只保留只读取消 Token，传输句柄继续保持私有。
     */
    public RequestController(CancellationToken token) {
        this.token = Objects.requireNonNull(token, "token");
    }

    /**
     * 在 Provider 接纳前绑定请求工作线程，使取消能够中断初始化。
     */
    public void bindThread(Thread value) {
        synchronized (operationGate) {
            thread.set(Objects.requireNonNull(value, "value"));
            throwIfStopped();
        }
    }

    /**
     * 将一个活动 OkHttp Call 绑定到工作线程共用的取消门禁。
     */
    void bindCall(Call value) {
        synchronized (operationGate) {
            try {
                throwIfStopped();
                call.set(Objects.requireNonNull(value, "value"));
            } catch (RuntimeException failure) {
                value.cancel();
                throw failure;
            }
        }
    }

    /**
     * 只清除与已完成 Provider 尝试关联的 Call，避免影响后续重试。
     */
    void clearCall(Call value) {
        call.compareAndSet(value, null);
    }

    /**
     * 绑定单个响应，使取消能够同时关闭解析器和正文。
     */
    void bindStream(AutoCloseable value) {
        synchronized (operationGate) {
            try {
                throwIfStopped();
                stream.set(Objects.requireNonNull(value, "value"));
            } catch (RuntimeException failure) {
                closeQuietly(value);
                throw failure;
            }
        }
    }

    /**
     * 只清除已经完成的流，不影响后续重试建立的新流。
     */
    void clearStream(AutoCloseable value) {
        stream.compareAndSet(value, null);
    }

    /**
     * 调用 sink 并跟踪其完成 Future，以便传播取消。
     */
    CompletableFuture<Void> beginSink(ModelEventSink sink, ModelPort.ModelEvent event) {
        synchronized (operationGate) {
            throwIfStopped();
            CompletableFuture<Void> accepted;
            try {
                accepted = Objects.requireNonNull(sink.onEvent(event), "event sink stage")
                        .toCompletableFuture();
            } catch (RuntimeException failure) {
                throwIfStopped();
                throw new ProviderProtocolException(
                        "EVENT_SINK", "model event sink rejected an event", false, failure);
            }
            sinkStage.set(accepted);
            try {
                throwIfStopped();
            } catch (RuntimeException failure) {
                accepted.cancel(true);
                sinkStage.compareAndSet(accepted, null);
                throw failure;
            }
            return accepted;
        }
    }

    /**
     * 在解除关联前结束已观察到的 sink，避免已发布的取消越过负责把取消传播到阻塞 sink 的回调。
     */
    void clearSink(CompletableFuture<Void> accepted) {
        accepted.cancel(true);
        sinkStage.compareAndSet(accepted, null);
    }

    /**
     * 将用户取消传播到响应、Call、sink 和工作线程。
     */
    public void cancel() {
        if (!beginStop()) return;
        try {
            stopTransport();
        } finally {
            endStop();
        }
    }

    /**
     * 将超时标记为区别于用户取消的结果，同时复用传输清理路径。
     */
    public void timeout() {
        if (done.get()) return;
        timedOut.set(true);
        cancel();
    }

    /**
     * 标记 Runtime 关闭，并阻止回调重新打开 Provider 交换。
     */
    public void shutdown() {
        if (done.get()) return;
        shutDown.set(true);
        cancel();
    }

    /**
     * 在每个重试、解码和 sink 边界抛出权威停止类别。
     */
    public void throwIfStopped() {
        if (token.isCancellationRequested()) {
            throw new CancellationException(token.reason().orElse("turn cancelled"));
        }
        if (timedOut.get()) {
            throw new ProviderProtocolException(
                    "REQUEST_TIMEOUT", "provider request exceeded its deadline", true);
        }
        if (shutDown.get()) throw new CancellationException("model adapter closed");
    }

    /**
     * 原子解除活动句柄，再通过 closeQuietly 关闭响应流，避免持锁执行外部清理。
     */
    @SuppressWarnings("PMD.CloseResource")
    private void stopTransport() {
        Call activeCall;
        AutoCloseable activeStream;
        CompletableFuture<Void> activeSink;
        Thread activeThread;
        synchronized (operationGate) {
            if (done.get()) return;
            activeCall = call.getAndSet(null);
            activeStream = stream.getAndSet(null);
            activeSink = sinkStage.getAndSet(null);
            activeThread = thread.get();
        }
        if (activeSink != null) activeSink.cancel(true);
        if (activeCall != null) activeCall.cancel();
        closeQuietly(activeStream);
        if (activeThread != null) activeThread.interrupt();
    }

    /**
     * 关闭 Provider 响应，但不替换已经确定的取消或超时类别。
     */
    private static void closeQuietly(AutoCloseable value) {
        if (value == null) return;
        try {
            value.close();
        } catch (Exception ignored) {
            // Controller 的停止类别在该边界保持权威，关闭异常不得覆盖它。
        }
    }

    /**
     * 仅当完成流程尚未关闭回调接纳门禁时登记一次停止回调。
     */
    private boolean beginStop() {
        synchronized (stopBarrier) {
            if (!acceptingStops) return false;
            stopActions++;
            return true;
        }
    }

    /**
     * 在取消动作发出后释放完成屏障。
     */
    private void endStop() {
        synchronized (stopBarrier) {
            stopActions--;
            stopBarrier.notifyAll();
        }
    }

    /**
     * 清理池化工作线程引用前等待进行中的停止回调完成。
     */
    public void complete() {
        boolean interrupted = false;
        Thread boundThread;
        synchronized (stopBarrier) {
            acceptingStops = false;
            while (stopActions > 0) {
                try {
                    stopBarrier.wait();
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
        }
        synchronized (operationGate) {
            done.set(true);
            call.set(null);
            stream.set(null);
            sinkStage.set(null);
            boundThread = thread.getAndSet(null);
        }
        if (Thread.currentThread() == boundThread) Thread.interrupted();
        if (interrupted) Thread.currentThread().interrupt();
    }
}
