// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.application.concurrent.ShutdownDeadline;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
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
    static final long MAX_CONTROL_BYTES = 32L * 1024 * 1024;
    static final long MAX_DATA_BYTES = 32L * 1024 * 1024;
    private static final long OWNER_CLOSE_GRACE_MILLIS = 2_000;
    private final OutputStream output;
    private final ObjectMapper mapper;
    private final int maxFrameBytes;
    private final long maxControlBytes;
    private final long maxDataBytes;
    private final boolean closeOutputOnOwnerExit;
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
    private long controlBytesInFlight;
    private long dataBytesInFlight;
    private long notificationSequence;

    /**
     * 启动唯一平台 Writer 所有者，禁止请求或 Turn 工作线程直接写 stdout。
     */
    public StdioWriter(OutputStream output, ObjectMapper mapper, int maxFrameBytes) {
        this(output, mapper, maxFrameBytes, MAX_CONTROL_BYTES, MAX_DATA_BYTES, false);
    }

    /**
     * 以较小 lane 预算确定性验证背压；生产入口固定使用双方 32 MiB 的默认上限。
     */
    StdioWriter(OutputStream output, ObjectMapper mapper, int maxFrameBytes,
                long maxControlBytes, long maxDataBytes) {
        this(output, mapper, maxFrameBytes, maxControlBytes, maxDataBytes, false);
    }

    /**
     * 为由 RPC Server 独占创建的内部管道选择输出所有权；标准输入输出仍由宿主拥有，避免关闭 System.out。
     */
    StdioWriter(OutputStream output, ObjectMapper mapper, int maxFrameBytes,
                long maxControlBytes, long maxDataBytes, boolean closeOutputOnOwnerExit) {
        this.output = Objects.requireNonNull(output, "output");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        if (maxFrameBytes < 1_024 || maxControlBytes < 1 || maxDataBytes < 1) {
            throw new IllegalArgumentException("invalid writer capacity");
        }
        this.maxFrameBytes = maxFrameBytes;
        this.maxControlBytes = maxControlBytes;
        this.maxDataBytes = maxDataBytes;
        this.closeOutputOnOwnerExit = closeOutputOnOwnerExit;
        this.completionExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_COMPLETION_CALLBACKS),
                Thread.ofPlatform().daemon().name("ja-rpc-completion-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.owner = Thread.ofPlatform().daemon().name("ja-rpc-writer").start(this::run);
    }

    /**
     * 通知必须携带对象型 params；只允许 Writer owner 在写出前补充物理顺序。
     */
    private static ObjectNode notificationParams(ObjectNode envelope) {
        if (!(envelope.get("params") instanceof ObjectNode params)) {
            throw new IllegalStateException("notification params are unavailable");
        }
        return params;
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
     * 保留小写领域/动作边界，同时接受合同中 messages_received 的下划线；
     * 方法闭集由协议目录约束，Writer 不能用更窄语法拒绝已协商的持久消息事件。
     */
    private static String requireMethod(String value) {
        if (value == null || value.length() > 128
            || !value.matches("[a-z][a-z0-9]*(?:[-_][a-z0-9]+)*(?:/[a-z][a-z0-9]*(?:[-_][a-z0-9]+)*)*")) {
            throw new IllegalArgumentException("invalid method name");
        }
        return value;
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
     * 在准入前完成序列化并预留回调容量；序列化失败保留编码器 cause 供本地诊断。
     */
    private CompletableFuture<Void> enqueue(ObjectNode envelope, Lane lane, boolean dispatchCompletion) {
        synchronized (lifecycleLock) {
            if (!accepting.get() || failed.get()) {
                throw new IllegalStateException("stdio writer is unavailable");
            }
            ObjectNode sequencedEnvelope = dispatchCompletion ? envelope.deepCopy() : null;
            byte[] bytes;
            try {
                if (sequencedEnvelope != null) {
                    ObjectNode params = notificationParams(sequencedEnvelope);
                    // 最大正 long 预留最坏序列化宽度，保证 Writer 写入真实 sequence 后仍在帧预算内。
                    params.put("sequence", Long.MAX_VALUE);
                    bytes = mapper.writeValueAsBytes(sequencedEnvelope);
                } else {
                    bytes = mapper.writeValueAsBytes(envelope);
                }
            } catch (IOException failure) {
                throw new IllegalStateException("stdio serialization failed", failure);
            }
            if (bytes.length > maxFrameBytes) {
                throw JaRpcException.of(JaErrorCatalog.FRAME_TOO_LARGE, "outbound frame is too large");
            }
            if (dispatchCompletion && !completionPermits.tryAcquire()) {
                failed.set(true);
                accepting.set(false);
                throw JaRpcException.of(JaErrorCatalog.QUEUE_FULL, "completion capacity is exhausted");
            }
            if (!reserveBytes(lane, bytes.length)) {
                if (dispatchCompletion) {
                    completionPermits.release();
                }
                failed.set(true);
                accepting.set(false);
                throw JaRpcException.of(JaErrorCatalog.QUEUE_FULL, "outbound byte budget is exhausted");
            }
            Frame frame = new Frame(bytes, sequencedEnvelope, new CompletableFuture<>(), lane,
                    new AtomicBoolean());
            boolean accepted = (lane == Lane.CONTROL ? control : data).offer(frame);
            if (!accepted) {
                releaseFrameBytesLocked(frame);
                if (dispatchCompletion) {
                    completionPermits.release();
                }
                failed.set(true);
                accepting.set(false);
                throw JaRpcException.of(JaErrorCatalog.QUEUE_FULL, "outbound queue is full");
            }
            if (!dispatchCompletion) {
                return frame.flushed();
            }
            return deferCompletion(frame);
        }
    }

    /**
     * 在 lifecycleLock 下按实际编码长度预留 lane 容量，覆盖队列帧与正在写出的帧。
     */
    private boolean reserveBytes(Lane lane, int bytes) {
        long current = lane == Lane.CONTROL ? controlBytesInFlight : dataBytesInFlight;
        long maximum = lane == Lane.CONTROL ? maxControlBytes : maxDataBytes;
        if (bytes > maximum - current) {
            return false;
        }
        if (lane == Lane.CONTROL) {
            controlBytesInFlight += bytes;
        }
        else {
            dataBytesInFlight += bytes;
        }
        return true;
    }

    /**
     * 释放单帧字节预留；标记在同一生命周期锁下保证失败竞态只扣减一次。
     */
    private void releaseFrameBytes(Frame frame) {
        synchronized (lifecycleLock) {
            releaseFrameBytesLocked(frame);
        }
    }

    /**
     * 仅在 lifecycleLock 下释放帧容量，供入队失败、写完和统一排空路径复用。
     */
    private void releaseFrameBytesLocked(Frame frame) {
        if (!frame.byteReservationReleased().compareAndSet(false, true)) {
            return;
        }
        if (frame.lane() == Lane.CONTROL) {
            controlBytesInFlight -= frame.bytes().length;
        }
        else {
            dataBytesInFlight -= frame.bytes().length;
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
                    if (failure == null) {
                        result.complete(null);
                    }
                    else {
                        result.completeExceptionally(failure);
                    }
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
     * 严格优先写控制帧，同时继续排空有界草稿流量；独占输出在本线程退场前关闭，避免读端误判死写者。
     */
    private void run() {
        try {
            while (accepting.get() || !control.isEmpty() || !data.isEmpty()) {
                Frame frame = control.poll();
                if (frame == null) {
                    frame = data.poll(50, TimeUnit.MILLISECONDS);
                }
                if (frame != null) {
                    activeFrame.set(frame);
                    try {
                        write(frame);
                    } finally {
                        activeFrame.compareAndSet(frame, null);
                        releaseFrameBytes(frame);
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failQueued(new IllegalStateException("stdio writer interrupted"));
        } catch (RuntimeException failure) {
            if (accepting.get()) {
                ownerFailure.compareAndSet(null, failure);
            }
            failQueued(failure);
        } finally {
            closeOwnedOutput();
        }
    }

    /**
     * 由唯一 Writer 线程发布内部管道 EOF，消除 Writer 先退出而读端观察到 Write end dead 的窗口。
     * 关闭失败保留为 owner failure，仍由 RpcServer 的关闭结果传播，不把真实 I/O 错误伪装成正常 EOF。
     */
    private void closeOwnedOutput() {
        if (!closeOutputOnOwnerExit) {
            return;
        }
        try {
            output.close();
        } catch (IOException | RuntimeException failure) {
            RuntimeException closeFailure = failure instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException("stdio owned output close failed", failure);
            RuntimeException prior = ownerFailure.get();
            if (prior == null) {
                ownerFailure.compareAndSet(null, closeFailure);
            } else if (prior != closeFailure) {
                prior.addSuppressed(closeFailure);
            }
            failed.set(true);
            accepting.set(false);
            failQueued(closeFailure);
        }
    }

    /**
     * 完整写入一个 UTF-8 JSON 帧并在 flush 后确认；checked 错误带 cause 包装，unchecked 错误原样传递。
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    private void write(Frame frame) {
        try {
            byte[] bytes = frame.bytes();
            if (frame.sequencedEnvelope() != null) {
                long sequence = ++notificationSequence;
                if (sequence < 1) {
                    throw new IllegalStateException("notification sequence overflow");
                }
                notificationParams(frame.sequencedEnvelope()).put("sequence", sequence);
                bytes = mapper.writeValueAsBytes(frame.sequencedEnvelope());
                if (bytes.length > maxFrameBytes) {
                    throw JaRpcException.of(JaErrorCatalog.FRAME_TOO_LARGE,
                            "outbound frame is too large");
                }
            }
            output.write(bytes);
            output.write('\n');
            output.flush();
            releaseFrameBytes(frame);
            frame.flushed().complete(null);
        } catch (IOException | RuntimeException | Error failure) {
            boolean unexpected = accepting.get();
            failed.set(true);
            accepting.set(false);
            RuntimeException projectionFailure = failure instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException("stdio publish failed", failure);
            if (unexpected || !(failure instanceof IOException)) {
                ownerFailure.compareAndSet(null, projectionFailure);
            }
            releaseFrameBytes(frame);
            frame.flushed().completeExceptionally(projectionFailure);
            failQueued(projectionFailure);
            if (failure instanceof Error error) {
                throw error;
            }
            throw projectionFailure;
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
        List<Frame> failedFrames = new ArrayList<>();
        synchronized (lifecycleLock) {
            failed.set(true);
            accepting.set(false);
            Frame active = activeFrame.get();
            if (active != null) {
                releaseFrameBytesLocked(active);
                failedFrames.add(active);
            }
            Frame frame;
            while ((frame = control.poll()) != null) {
                releaseFrameBytesLocked(frame);
                failedFrames.add(frame);
            }
            while ((frame = data.poll()) != null) {
                releaseFrameBytesLocked(frame);
                failedFrames.add(frame);
            }
        }
        for (Frame frame : failedFrames) {
            frame.flushed().completeExceptionally(failure);
        }
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
            if (failure == null) {
                completion.complete(null);
            }
            else {
                completion.completeExceptionally(failure);
            }
        }
        deadline.await(completion, "stdio writer");
    }

    /**
     * 仅使用共享关闭预算的剩余时间等待平台 Writer 结束。
     */
    private void joinOwner(ShutdownDeadline deadline) {
        try {
            long remaining = deadline.remainingMillis();
            if (remaining > 0) {
                owner.join(Math.min(remaining, OWNER_CLOSE_GRACE_MILLIS));
            }
            if (owner.isAlive()) {
                failQueued(new IllegalStateException("stdio writer close deadline exceeded"));
                owner.interrupt();
                remaining = deadline.remainingMillis();
                if (remaining > 0) {
                    owner.join(remaining);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw ShutdownDeadline.forced("stdio writer close interrupted", interrupted);
        }
        if (owner.isAlive()) {
            throw ShutdownDeadline.forced("stdio writer close timed out", null);
        }
    }

    /**
     * 封闭队列类别，使语义控制事实与可丢弃草稿流量保持隔离。
     */
    private enum Lane {
        /**
         * 承载响应、错误及不可丢弃通知。
         */
        CONTROL,

        /**
         * 仅承载允许丢弃的流式草稿 delta。
         */
        DATA
    }

    /**
     * 保存不可变预检字节与 Writer 私有通知副本；sequence 只在物理写出点分配。
     *
     * @param bytes 二进制数据
     * @param sequencedEnvelope 有序协议封装
     * @param flushed 协议帧输出完成的信号
     * @param lane 持有本帧编码字节配额的输出通道
     * @param byteReservationReleased 保证成功、失败与排空路径仅释放一次配额
     */
    private record Frame(byte[] bytes,
                         ObjectNode sequencedEnvelope,
                         CompletableFuture<Void> flushed,
                         Lane lane,
                         AtomicBoolean byteReservationReleased) {
        /**
         * 绑定唯一所有的字节数组与私有 flush 确认，拒绝部分帧状态。
         */
        private Frame {
            Objects.requireNonNull(bytes, "bytes");
            Objects.requireNonNull(flushed, "flushed");
            Objects.requireNonNull(lane, "lane");
            Objects.requireNonNull(byteReservationReleased, "byteReservationReleased");
        }
    }
}
