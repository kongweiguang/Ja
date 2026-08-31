// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.runtime;

import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.foundation.concurrent.ShutdownDeadline;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 以单一所有者串行写入有界 JSONL，并隔离控制事实与可丢弃草稿数据。
 */
public final class StdioWriter implements AutoCloseable {
    static final int MAX_CONTROL_FRAMES = 64;
    static final int MAX_DATA_FRAMES = 1_024;
    static final int MAX_COMPLETION_CALLBACKS = MAX_CONTROL_FRAMES + MAX_DATA_FRAMES;
    private static final long OWNER_CLOSE_GRACE_MILLIS = 2_000;
    private final OutputStream output;
    private final ObjectMapper mapper;
    private final int maxFrameBytes;
    private final ArrayBlockingQueue<Frame> control = new ArrayBlockingQueue<>(MAX_CONTROL_FRAMES);
    private final ArrayBlockingQueue<Frame> data = new ArrayBlockingQueue<>(MAX_DATA_FRAMES);
    private final Semaphore completionPermits = new Semaphore(MAX_COMPLETION_CALLBACKS);
    private final ThreadPoolExecutor completionExecutor;
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean failed = new AtomicBoolean();
    private final AtomicReference<Frame> activeFrame = new AtomicReference<>();
    private final AtomicReference<RuntimeException> ownerFailure = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Void>> closeCompletion = new AtomicReference<>();
    private final Thread owner;

    /**
     * 启动唯一平台 Writer 所有者，禁止请求或 Turn 工作线程直接写 stdout。
     */
    public StdioWriter(OutputStream output, ObjectMapper mapper, int maxFrameBytes) {
        this.output = Objects.requireNonNull(output, "output");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        if (maxFrameBytes < 1_024) throw new IllegalArgumentException("maxFrameBytes is too small");
        this.maxFrameBytes = maxFrameBytes;
        this.completionExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_COMPLETION_CALLBACKS),
                Thread.ofPlatform().daemon().name("ja-rpc-completion-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.owner = Thread.ofPlatform().daemon().name("ja-rpc-writer").start(this::run);
    }

    /**
     * 将响应加入控制通道并等待 flush 确认，避免关闭流程提前释放 stdout。
     */
    public CompletableFuture<Void> response(String id, ObjectNode result) {
        rejectOwnerReentry();
        ObjectNode envelope = envelope();
        envelope.put("id", requireId(id));
        envelope.set("result", Objects.requireNonNull(result, "result").deepCopy());
        return enqueue(envelope, Lane.CONTROL, false);
    }

    /**
     * 将稳定脱敏错误加入控制通道，禁止异常细节或调用方文本进入 Wire。
     */
    public CompletableFuture<Void> error(String id, JaRpcException failure) {
        rejectOwnerReentry();
        Objects.requireNonNull(failure, "failure");
        ObjectNode dataNode = mapper.createObjectNode();
        dataNode.put("errorCode", failure.errorCode());
        dataNode.put("category", failure.category());
        dataNode.put("retryable", failure.retryable());
        dataNode.put("errorId", failure.errorId());
        failure.retryAfterMs().ifPresent(value -> dataNode.put("retryAfterMs", value));
        ObjectNode error = mapper.createObjectNode();
        error.put("code", failure.code());
        error.put("message", failure.getMessage());
        error.set("data", dataNode);
        ObjectNode envelope = envelope();
        envelope.put("id", requireId(id));
        envelope.set("error", error);
        return enqueue(envelope, Lane.CONTROL, false);
    }

    /**
     * 通过专用 Writer 发布语义通知，并仅在 flush 后确认完成。
     */
    public CompletableFuture<Void> notification(String method, ObjectNode params) {
        ObjectNode envelope = envelope();
        envelope.put("method", requireMethod(method));
        envelope.set("params", Objects.requireNonNull(params, "params").deepCopy());
        return enqueue(envelope, Lane.CONTROL, true);
    }

    /**
     * 通过较大的数据通道发布可丢弃草稿 delta，避免阻塞 Agent Loop。
     */
    public CompletableFuture<Void> delta(String method, ObjectNode params) {
        if (!("assistant/text-delta".equals(method)
              || "assistant/reasoning-summary-delta".equals(method))) {
            throw new IllegalArgumentException("data lane accepts draft deltas only");
        }
        ObjectNode envelope = envelope();
        envelope.put("method", method);
        envelope.set("params", Objects.requireNonNull(params, "params").deepCopy());
        return enqueue(envelope, Lane.DATA, true);
    }

    /**
     * 在准入前完成序列化并预留回调容量，使完成分发始终有界。
     */
    private CompletableFuture<Void> enqueue(ObjectNode envelope, Lane lane, boolean dispatchCompletion) {
        synchronized (lifecycleLock) {
            if (!accepting.get() || failed.get()) {
                throw new IllegalStateException("stdio writer is unavailable");
            }
            byte[] bytes;
            try {
                bytes = mapper.writeValueAsBytes(envelope);
            } catch (IOException failure) {
                throw new IllegalStateException("stdio serialization failed");
            }
            if (bytes.length > maxFrameBytes) {
                throw JaRpcException.of(JaErrorCatalog.FRAME_TOO_LARGE, "outbound frame is too large");
            }
            if (dispatchCompletion && !completionPermits.tryAcquire()) {
                failed.set(true);
                accepting.set(false);
                throw JaRpcException.of(JaErrorCatalog.QUEUE_FULL, "completion capacity is exhausted");
            }
            Frame frame = new Frame(bytes, new CompletableFuture<>());
            boolean accepted = (lane == Lane.CONTROL ? control : data).offer(frame);
            if (!accepted) {
                if (dispatchCompletion) completionPermits.release();
                failed.set(true);
                accepting.set(false);
                throw JaRpcException.of(JaErrorCatalog.QUEUE_FULL, "outbound queue is full");
            }
            if (!dispatchCompletion) return frame.flushed();
            return deferCompletion(frame);
        }
    }

    /**
     * 将所有外部依赖回调移出 Writer 线程，并在关闭竞态后仍释放对应许可。
     */
    private CompletableFuture<Void> deferCompletion(Frame frame) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        frame.flushed().whenComplete((ignored, failure) -> {
            Runnable callback = () -> {
                try {
                    if (failure == null) result.complete(null);
                    else result.completeExceptionally(failure);
                } finally {
                    completionPermits.release();
                }
            };
            try {
                completionExecutor.execute(callback);
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                completionPermits.release();
                poison(rejected);
                result.completeExceptionally(failure == null ? rejected : failure);
            }
        });
        return result;
    }

    /**
     * 返回供可能继续入队帧的协议依赖使用的有界执行器。
     *
     * <p>Writer 所有者在独占 stdout 副作用期间完成帧 Future；RPC 回调必须显式切换到此执行器，
     * 否则 Future 依赖可能在 Writer 内联执行，并同步等待自己刚入队的响应，形成自锁。</p>
     */
    Executor completionExecutor() {
        return completionExecutor;
    }

    /**
     * 投影边界失去安全性时标记 Writer 中毒，并使所有帧等待者失败。
     */
    void poison(Throwable failure) {
        RuntimeException redacted = failure instanceof RuntimeException runtime
                ? runtime : new IllegalStateException("stdio writer failed", failure);
        synchronized (lifecycleLock) {
            failed.set(true);
            accepting.set(false);
        }
        failQueued(redacted);
    }

    /**
     * 严格优先写控制帧，同时继续排空有界草稿流量。
     */
    private void run() {
        try {
            while (accepting.get() || !control.isEmpty() || !data.isEmpty()) {
                Frame frame = control.poll();
                if (frame == null) frame = data.poll(50, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    activeFrame.set(frame);
                    try {
                        write(frame);
                    } finally {
                        activeFrame.compareAndSet(frame, null);
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failQueued(new IllegalStateException("stdio writer interrupted"));
        } catch (RuntimeException failure) {
            if (accepting.get()) ownerFailure.compareAndSet(null, failure);
            failQueued(failure);
        }
    }

    /**
     * 完整写入一个 UTF-8 JSON 帧，并仅在 flush 成功后确认。
     */
    private void write(Frame frame) {
        try {
            output.write(frame.bytes());
            output.write('\n');
            output.flush();
            frame.flushed().complete(null);
        } catch (IOException failure) {
            boolean unexpected = accepting.get();
            failed.set(true);
            accepting.set(false);
            IllegalStateException redacted = new IllegalStateException("stdio publish failed");
            if (unexpected) ownerFailure.compareAndSet(null, redacted);
            frame.flushed().completeExceptionally(redacted);
            throw redacted;
        }
    }

    /**
     * 拒绝可能让 Writer 所有者等待自身 flush 的重入调用形态。
     */
    private void rejectOwnerReentry() {
        if (Thread.currentThread() == owner) {
            throw new IllegalStateException("stdio writer owner cannot synchronously enqueue a response");
        }
    }

    /**
     * stdout 无法继续保证顺序时，使所有已准入等待者以异常完成。
     */
    private void failQueued(RuntimeException failure) {
        failed.set(true);
        accepting.set(false);
        Frame active = activeFrame.get();
        if (active != null) active.flushed().completeExceptionally(failure);
        Frame frame;
        while ((frame = control.poll()) != null) frame.flushed().completeExceptionally(failure);
        while ((frame = data.poll()) != null) frame.flushed().completeExceptionally(failure);
    }

    /**
     * 仅构造协议判别字段，强制调用方明确选择唯一信封角色。
     */
    private ObjectNode envelope() {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        return envelope;
    }

    /**
     * 将出站响应关联限制在 Rust 客户端命名空间。
     */
    private static String requireId(String value) {
        if (value == null || !value.matches("c:[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) {
            throw new IllegalArgumentException("invalid request identity");
        }
        return value;
    }

    /**
     * 只接受小写 kebab-case 的领域/动作名称；该规则与 v2 合同一致，并拒绝 camelCase、
     * 空分段和未命名空间化的非法通知。
     */
    private static String requireMethod(String value) {
        if (value == null || value.length() > 128
            || !value.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*(?:/[a-z][a-z0-9]*(?:-[a-z0-9]+)*)*")) {
            throw new IllegalArgumentException("invalid method name");
        }
        return value;
    }

    /**
     * 启动唯一 Writer 关闭流程，并让重复或并发调用者共享完成结果。
     */
    @Override
    public void close() {
        close(ShutdownDeadline.start());
    }

    /**
     * 在调用方提供的绝对期限内关闭 Writer 所有者与回调执行器。
     */
    void close(ShutdownDeadline deadline) {
        Objects.requireNonNull(deadline, "deadline");
        CompletableFuture<Void> completion = closeCompletion.get();
        boolean ownerClose = false;
        if (completion == null) {
            CompletableFuture<Void> candidate = new CompletableFuture<>();
            if (closeCompletion.compareAndSet(null, candidate)) {
                completion = candidate;
                ownerClose = true;
            } else {
                completion = closeCompletion.get();
            }
        }
        if (ownerClose) {
            RuntimeException failure = null;
            synchronized (lifecycleLock) {
                accepting.set(false);
            }
            try {
                joinOwner(deadline);
            } catch (RuntimeException closeFailure) {
                failure = closeFailure;
                owner.interrupt();
            }
            if (failure == null && ownerFailure.get() != null) {
                failure = ShutdownDeadline.forced("stdio writer failed before close", ownerFailure.get());
            }
            failQueued(failure == null ? new IllegalStateException("stdio writer is closed") : failure);
            completionExecutor.shutdown();
            try {
                long remaining = deadline.remainingMillis();
                if (!completionExecutor.awaitTermination(remaining, TimeUnit.MILLISECONDS)) {
                    completionExecutor.shutdownNow();
                    if (failure == null) {
                        failure = ShutdownDeadline.forced("stdio completion executor timed out", null);
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                completionExecutor.shutdownNow();
                if (failure == null) {
                    failure = ShutdownDeadline.forced("stdio completion executor interrupted", interrupted);
                }
            }
            if (failure == null && owner.isAlive()) {
                failure = ShutdownDeadline.forced("stdio writer owner did not stop", null);
            }
            if (failure == null) completion.complete(null);
            else completion.completeExceptionally(failure);
        }
        deadline.await(completion, "stdio writer");
    }

    /**
     * 仅使用共享关闭预算的剩余时间等待平台 Writer 结束。
     */
    private void joinOwner(ShutdownDeadline deadline) {
        try {
            long remaining = deadline.remainingMillis();
            if (remaining > 0) owner.join(Math.min(remaining, OWNER_CLOSE_GRACE_MILLIS));
            if (owner.isAlive()) {
                failQueued(new IllegalStateException("stdio writer close deadline exceeded"));
                owner.interrupt();
                remaining = deadline.remainingMillis();
                if (remaining > 0) owner.join(remaining);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw ShutdownDeadline.forced("stdio writer close interrupted", interrupted);
        }
        if (owner.isAlive()) throw ShutdownDeadline.forced("stdio writer close timed out", null);
    }

    /**
     * 保存不可变预序列化帧，防止入队后继续受 Jackson 节点变更影响。
     */
    private record Frame(byte[] bytes, CompletableFuture<Void> flushed) {
        /**
         * 绑定唯一所有的字节数组与私有 flush 确认，拒绝部分帧状态。
         */
        private Frame {
            Objects.requireNonNull(bytes, "bytes");
            Objects.requireNonNull(flushed, "flushed");
        }
    }

    /**
     * 封闭队列类别，使语义控制事实与可丢弃草稿流量保持隔离。
     */
    private enum Lane {
        /**
         * 承载响应、错误、反向请求及不可丢弃通知。
         */
        CONTROL,

        /**
         * 仅承载允许丢弃的流式草稿 delta。
         */
        DATA
    }
}
