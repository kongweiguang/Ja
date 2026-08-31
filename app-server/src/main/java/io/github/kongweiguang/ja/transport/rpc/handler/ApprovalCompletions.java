// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 在单个连接内有界关联普通审批响应与已提交的审批结果。
 */
public final class ApprovalCompletions implements AutoCloseable {
    private final Clock clock;
    private final int maximumPending;
    private final int maximumTombstones;
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Map<String, String> tombstones = new LinkedHashMap<>();
    private boolean closed;

    /**
     * 固定待处理项和墓碑上限，避免 UI 遗弃审批后当前运行代际无限增长。
     */
    public ApprovalCompletions(Clock clock, int maximumPending, int maximumTombstones) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maximumPending < 1 || maximumTombstones < 1) throw new IllegalArgumentException("invalid bounds");
        this.maximumPending = maximumPending;
        this.maximumTombstones = maximumTombstones;
    }

    /**
     * 在 stdout 向客户端发布事件前登记持久化审批请求，确保响应始终可以关联。
     */
    public synchronized void requested(String approvalId, String threadId, String turnId, Instant expiresAt) {
        if (closed) throw JaRpcException.of(JaErrorCatalog.SHUTTING_DOWN, "runtime is shutting down");
        if (pending.size() >= maximumPending) {
            throw JaRpcException.of(JaErrorCatalog.QUEUE_FULL, "approval capacity is exhausted");
        }
        Pending value = new Pending(approvalId, threadId, turnId, expiresAt, new CompletableFuture<>());
        if (pending.putIfAbsent(approvalId, value) != null || tombstones.containsKey(approvalId)) {
            throw JaRpcException.of(JaErrorCatalog.INVALID_STATE, "approval identity is duplicated");
        }
    }

    /**
     * 只允许一个响应者取得处理权，并返回仅由已提交解决事件完成的 Future。
     */
    Pending begin(String approvalId, String turnId) {
        CompletableFuture<Resolution> expiredCompletion = null;
        synchronized (this) {
            String tombstone = tombstones.get(approvalId);
            if (tombstone != null) {
                throw JaRpcException.of(JaErrorCatalog.APPROVAL_ALREADY_RESOLVED,
                        "approval is already resolved");
            }
            Pending value = pending.get(approvalId);
            if (value == null || !value.turnId().equals(turnId)) {
                throw JaRpcException.of(JaErrorCatalog.APPROVAL_NOT_FOUND, "approval is unavailable");
            }
            if (!clock.instant().isBefore(value.expiresAt())) {
                pending.remove(approvalId);
                tombstone(approvalId, "expired");
                /*
                 * 持有此监视器时不能完成调用方 Future。过期响应可能已经挂接刷新 RPC 错误或
                 * 重试其他审批的后续动作；若在关联锁内触发该动作，会重新形成
                 * committed()/terminal() 明确规避的锁循环。
                 */
                expiredCompletion = value.completion();
            } else {
                if (value.responding()) {
                    throw JaRpcException.of(JaErrorCatalog.APPROVAL_ALREADY_RESOLVED,
                            "approval response is already in progress");
                }
                value.responding(true);
                return value;
            }
        }
        expiredCompletion.completeExceptionally(
                JaRpcException.of(JaErrorCatalog.APPROVAL_EXPIRED, "approval has expired"));
        throw JaRpcException.of(JaErrorCatalog.APPROVAL_EXPIRED, "approval has expired");
    }

    /**
     * Broker 在持久化解决前拒绝关联时释放响应门闩，使同一审批可以安全重试。
     */
    synchronized void rejected(Pending value) {
        Pending current = pending.get(value.approvalId());
        if (current == value) current.responding(false);
    }

    /**
     * 仅在持久化解决事件成功发布后完成普通请求，保持响应与事件提交顺序一致。
     */
    public void committed(String approvalId, long threadRevision, String decision) {
        CompletableFuture<Resolution> completion;
        synchronized (this) {
            Pending value = pending.remove(approvalId);
            if (value == null) return;
            tombstone(approvalId, decision);
            completion = value.completion();
        }
        /*
         * 必须在关联监视器外完成 Future：它可能同步执行 RPC 响应后续动作，而该动作允许
         * 阻塞等待独立的 stdout writer。若跨越回调持有监视器，无关审批会被外部刷新串行化，
         * 有界队列也可能演变成锁循环。
         */
        completion.complete(new Resolution(threadRevision, decision));
    }

    /**
     * 使终态 Turn 持有的全部审批失败，禁止迟到响应重新恢复工作。
     */
    public void terminal(String turnId) {
        java.util.List<CompletableFuture<Resolution>> completions;
        synchronized (this) {
            /* 先收集身份再删除；终态事件遍历 stream 时修改 LinkedHashMap 会触发
             * ConcurrentModificationException。 */
            java.util.List<String> ids = pending.values().stream()
                    .filter(value -> value.turnId().equals(turnId))
                    .map(Pending::approvalId)
                    .toList();
            completions = ids.stream().map(pending::remove).filter(Objects::nonNull)
                    .map(value -> {
                        tombstone(value.approvalId(), "terminal");
                        return value.completion();
                    }).toList();
        }
        /* 终态回调属于外部后续动作，持有内部状态锁时不得调用。 */
        JaRpcException failure = JaRpcException.of(JaErrorCatalog.APPROVAL_ALREADY_RESOLVED,
                "approval turn is terminal");
        completions.forEach(completion -> completion.completeExceptionally(failure));
    }

    /**
     * 有界保留重复或迟到响应标记，但不保存请求文本与 Secret。
     */
    private void tombstone(String approvalId, String status) {
        tombstones.put(approvalId, status);
        while (tombstones.size() > maximumTombstones) {
            tombstones.remove(tombstones.keySet().iterator().next());
        }
    }

    /**
     * 关闭期间使待处理普通响应失败并清空关联状态，避免跨运行代际泄漏。
     */
    @Override
    public void close() {
        java.util.List<CompletableFuture<Resolution>> completions;
        JaRpcException failure = JaRpcException.of(JaErrorCatalog.SHUTTING_DOWN,
                "runtime is shutting down");
        synchronized (this) {
            if (closed) return;
            closed = true;
            completions = pending.values().stream()
                    .map(Pending::completion)
                    .toList();
            pending.clear();
            tombstones.clear();
        }
        /* 关闭可能释放 RPC 等待者，必须先在锁内清除所有权，再触发外部后续动作。 */
        completions.forEach(completion -> completion.completeExceptionally(failure));
    }

    /**
     * 可变 responding 标记由外层所有者监视器保护，其余关联事实保持不可变。
     */
    static final class Pending {
        private final String approvalId;
        private final String threadId;
        private final String turnId;
        private final Instant expiresAt;
        private final CompletableFuture<Resolution> completion;
        private boolean responding;

        /**
         * 在请求事件发布时冻结持久化审批身份与过期时间，避免后续关联漂移。
         */
        private Pending(String approvalId, String threadId, String turnId, Instant expiresAt,
                        CompletableFuture<Resolution> completion) {
            this.approvalId = Objects.requireNonNull(approvalId, "approvalId");
            this.threadId = Objects.requireNonNull(threadId, "threadId");
            this.turnId = Objects.requireNonNull(turnId, "turnId");
            this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
            this.completion = Objects.requireNonNull(completion, "completion");
        }

        /**
         * 返回审批身份，但不暴露请求原因或 Tool 参数。
         */
        String approvalId() {
            return approvalId;
        }

        /**
         * 返回所属 Thread，供 revision CAS 关联使用。
         */
        String threadId() {
            return threadId;
        }

        /**
         * 返回所属 Turn，确保响应使用显式关联身份。
         */
        String turnId() {
            return turnId;
        }

        /**
         * 返回 Broker 解决前使用的不可变过期时间。
         */
        Instant expiresAt() {
            return expiresAt;
        }

        /**
         * 返回由 Agent 事件接收端完成的提交 Future。
         */
        CompletableFuture<Resolution> completion() {
            return completion;
        }

        /**
         * 指示是否已有调用方取得唯一响应处理权。
         */
        boolean responding() {
            return responding;
        }

        /**
         * 在所有者监视器保护下标记唯一 Broker 解决者。
         */
        void responding(boolean value) {
            responding = value;
        }
    }

    /**
     * 构造响应 revision 所需的持久化审批结果。
     */
    record Resolution(long threadRevision, String decision) {
        /**
         * 释放客户端请求前校验已提交投影，禁止无效 revision 或空决策外泄。
         */
        Resolution {
            if (threadRevision < 0) throw new IllegalArgumentException("invalid thread revision");
            Objects.requireNonNull(decision, "decision");
        }
    }
}
