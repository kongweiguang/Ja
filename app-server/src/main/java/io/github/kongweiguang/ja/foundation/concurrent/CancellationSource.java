// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.concurrent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 为连接级和请求级资源提供单调取消源；只向下游暴露 {@link CancellationToken} 读取能力。
 */
public final class CancellationSource implements CancellationToken {
    private final Map<Long, Runnable> callbacks = new LinkedHashMap<>();
    private long nextRegistration;
    private boolean cancelled;
    private String reason;

    /**
     * 原子发布一次已脱敏取消原因，并在锁外执行回调，避免 Provider 关闭反向阻塞注册表。
     */
    public CancelResult cancel(String cancellationReason) {
        List<Runnable> pending;
        synchronized (this) {
            if (cancelled) return new CancelResult(false, Optional.empty());
            reason = boundedReason(cancellationReason);
            cancelled = true;
            pending = new ArrayList<>(callbacks.values());
            callbacks.clear();
        }
        RuntimeException failure = null;
        for (Runnable callback : pending) {
            try {
                callback.run();
            } catch (RuntimeException callbackFailure) {
                if (failure == null) failure = callbackFailure;
                else failure.addSuppressed(callbackFailure);
            }
        }
        return new CancelResult(true, Optional.ofNullable(failure));
    }

    /** 跨线程读取单调状态；同步边界保证原因与取消位不会发生撕裂。 */
    @Override
    public synchronized boolean isCancellationRequested() {
        return cancelled;
    }

    /** 只返回有界内部原因，不保存调用方正文或底层异常。 */
    @Override
    public synchronized Optional<String> reason() {
        return Optional.ofNullable(reason);
    }

    /**
     * 在同一锁内完成检查与注册；取消已经发生时立即回调，避免丢失关闭竞态。
     */
    @Override
    public Registration onCancellation(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        long registrationId;
        synchronized (this) {
            if (!cancelled) {
                registrationId = ++nextRegistration;
                callbacks.put(registrationId, callback);
                AtomicBoolean closed = new AtomicBoolean();
                return () -> {
                    if (!closed.compareAndSet(false, true)) return;
                    synchronized (CancellationSource.this) {
                        callbacks.remove(registrationId);
                    }
                };
            }
        }
        callback.run();
        return Registration.noop();
    }

    /** 限制原因长度和字符集合，避免把外部输入带入日志或公开错误。 */
    private static String boundedReason(String value) {
        if (value == null || value.isBlank()) return "cancelled";
        String normalized = value.replaceAll("[^A-Za-z0-9_-]", "_");
        return normalized.substring(0, Math.min(64, normalized.length()));
    }

    /**
     * 将状态变更与回调清理故障同时返回；调用方必须继续自己的关闭序列，再把故障并入统一结果。
     */
    public record CancelResult(boolean changed, Optional<RuntimeException> callbackFailure) {
        /** 防止取消边界用 null 表示缺失故障，保持关闭编排只有一种空值语义。 */
        public CancelResult {
            Objects.requireNonNull(callbackFailure, "callbackFailure");
        }
    }
}
