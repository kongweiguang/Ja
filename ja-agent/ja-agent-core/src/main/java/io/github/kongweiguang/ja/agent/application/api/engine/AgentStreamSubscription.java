// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.api.engine;

import io.github.kongweiguang.ja.agent.application.api.TurnEvent;
import io.github.kongweiguang.ja.agent.application.api.TurnEventSink;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 单消费者临时流订阅；慢消费者失去连续性后读取持久历史并重新订阅，不拖住业务执行。
 */
public final class AgentStreamSubscription implements TurnEventSink, AutoCloseable {
    private static final int MAX_COALESCED_CHARS = 8_192;

    private final AgentIdentity identity;
    private final int capacity;
    private final ArrayDeque<QueuedFrame> queue;
    private volatile State state = State.OPEN;
    private long lastSequence;

    /**
     * 容量和单事件大小共同限制内存；订阅可以先于执行准入建立，避免漏掉首片草稿。
     */
    public AgentStreamSubscription(AgentIdentity identity, int capacity) {
        this.identity = Objects.requireNonNull(identity, "identity");
        if (capacity < 1 || capacity > 256) {
            throw new IllegalArgumentException("stream capacity must be in [1,256]");
        }
        this.capacity = capacity;
        queue = new ArrayDeque<>(capacity);
    }

    /**
     * 只做有界内存处理；相邻小增量合并以节省队列槽位，真正的容量溢出仍要求持久历史重同步。
     */
    @Override
    public synchronized void publish(TurnEvent event) {
        Objects.requireNonNull(event, "event");
        if (!identity.equals(event.context().identity())) {
            throw new IllegalArgumentException("stream identity mismatch");
        }
        if (state != State.OPEN) {
            return;
        }
        if (lastSequence > 0 && event.context().sequence() <= lastSequence) {
            requireResynchronizationLocked();
            return;
        }
        lastSequence = event.context().sequence();

        if (event instanceof TurnEvent.TextDelta text) {
            acceptDeltaLocked(DeltaKind.TEXT, text.context(), text.text());
        } else if (event instanceof TurnEvent.ReasoningSummaryDelta reasoning) {
            acceptDeltaLocked(DeltaKind.REASONING, reasoning.context(), reasoning.text());
        } else {
            enqueueLocked(QueuedFrame.event(event));
        }
    }

    /**
     * 仅队列为空时有界等待新帧；已有内容立即交付，发布者不等待消费者，状态切换唤醒等待者。
     */
    public PollResult poll(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("stream poll timeout must be in [0,1m]");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (this) {
            while (state == State.OPEN) {
                if (!queue.isEmpty()) {
                    return new PollResult(Optional.of(queue.removeFirst().materialize()), State.OPEN);
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            return new PollResult(Optional.empty(), state);
        }
    }

    /**
     * 返回完整租户和调用者身份，Registry 不得只按 turnId 路由。
     */
    public AgentIdentity identity() {
        return identity;
    }

    /**
     * 独立读取订阅状态，避免把消费者断开误当成任务取消或终态。
     */
    public State state() {
        return state;
    }

    /**
     * 仅释放当前订阅缓存；不会触碰 Engine、Provider、工具或持久状态。
     */
    @Override
    public synchronized void close() {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;
        queue.clear();
        notifyAll();
    }

    /**
     * 只合并仍留在队列尾部的同类、同请求小片；已交付帧不会被回写，避免限流式延迟。
     */
    private void acceptDeltaLocked(DeltaKind kind, TurnEvent.Context context, String text) {
        if (text.length() > MAX_COALESCED_CHARS) {
            enqueueLocked(QueuedFrame.event(kind.event(context, text)));
            return;
        }

        QueuedFrame tail = queue.peekLast();
        if (tail != null && tail.canAppend(kind, context, text.length())) {
            tail.append(context, text);
            notifyAll();
            return;
        }
        enqueueLocked(QueuedFrame.delta(kind, context, text));
    }

    /**
     * 按实际队列帧限制容量，超载即丢弃该订阅的非连续缓存并唤醒所有等待者。
     */
    private void enqueueLocked(QueuedFrame frame) {
        if (queue.size() >= capacity) {
            requireResynchronizationLocked();
            return;
        }
        queue.addLast(frame);
        notifyAll();
    }

    /**
     * 不连续数据不能继续伪装为完整草稿；清空局部缓存并要求调用方从持久状态恢复。
     */
    private void requireResynchronizationLocked() {
        state = State.RESYNC_REQUIRED;
        queue.clear();
        notifyAll();
    }

    /**
     * 订阅状态与执行状态相互独立，重同步必须重新建立订阅。
     */
    public enum State {
        /**
         * 正常接收尚未提交的草稿。
         */
        OPEN,
        /**
         * 缓存溢出或序号错误，必须读取持久状态后重建。
         */
        RESYNC_REQUIRED,
        /**
         * 调用方或 Engine 已释放本订阅。
         */
        CLOSED
    }

    /**
     * 将数据与订阅状态原子返回，避免调用方把唤醒信号当成正文。
     *
     * @param event 事件（可选）
     * @param state 当前状态
     */
    public record PollResult(Optional<TurnEvent> event,
                             State state) {
        /**
         * 只有正常订阅才可携带草稿，关闭或重同步不会泄漏缓存里的旧片段。
         */
        public PollResult {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(state, "state");
            if (state != State.OPEN && event.isPresent()) {
                throw new IllegalArgumentException("inactive stream carries event");
            }
        }
    }

    /**
     * 事件类别是合并键的一部分，避免正文与思考摘要跨类别串接。
     */
    private enum DeltaKind {
        /** 助手正文在合并后仍以正文事件交付。 */
        TEXT {
            /** 正文始终恢复为原事件类别，不将合并实现暴露为新的公开事件。 */
            @Override
            TurnEvent event(TurnEvent.Context context, String text) {
                return new TurnEvent.TextDelta(context, text);
            }
        },
        /** 供应商公开摘要保留独立事件类别。 */
        REASONING {
            /** 摘要与正文保持独立类型，避免消费端把公开摘要写入回复正文。 */
            @Override
            TurnEvent event(TurnEvent.Context context, String text) {
                return new TurnEvent.ReasoningSummaryDelta(context, text);
            }
        };

        /**
         * 仅在已经验证上限的完整字符串上创建公开事件，不截断或重新切分 Unicode 字符。
         */
        abstract TurnEvent event(TurnEvent.Context context, String text);
    }

    /**
     * 已排队增量帧按需物化，队列尾在仍未被消费者取走时可用 StringBuilder 原地合并。
     */
    private static final class QueuedFrame {
        private final TurnEvent event;
        private final DeltaKind kind;
        private final StringBuilder text;
        private TurnEvent.Context context;

        /**
         * 普通事件和超长单片原样排队，避免大帧被分割或与相邻片段拼接。
         */
        private QueuedFrame(TurnEvent event) {
            this.event = Objects.requireNonNull(event, "event");
            kind = null;
            text = null;
            context = event.context();
        }

        /**
         * 小增量保留一个有界可变缓冲，避免每次发布都复制既有字符串形成 O(n²) 成本。
         */
        private QueuedFrame(DeltaKind kind, TurnEvent.Context context, String initialText) {
            event = null;
            this.kind = Objects.requireNonNull(kind, "kind");
            this.context = Objects.requireNonNull(context, "context");
            text = new StringBuilder(Math.min(MAX_COALESCED_CHARS, initialText.length() * 2));
            text.append(initialText);
        }

        /**
         * 普通帧工厂保留原事件实例，尤其不拆分现有单片上限内的大增量。
         */
        private static QueuedFrame event(TurnEvent event) {
            return new QueuedFrame(event);
        }

        /**
         * 合并帧工厂只接收已通过大小边界校验的小片。
         */
        private static QueuedFrame delta(DeltaKind kind, TurnEvent.Context context, String text) {
            return new QueuedFrame(kind, context, text);
        }

        /**
         * 只允许同类别、同 model request 且合并后不超过 8192 个 UTF-16 code unit 的尾帧合并。
         */
        private boolean canAppend(DeltaKind candidateKind, TurnEvent.Context candidateContext, int length) {
            return text != null
                && kind == candidateKind
                && context.modelRequestId().equals(candidateContext.modelRequestId())
                && text.length() <= MAX_COALESCED_CHARS - length;
        }

        /**
         * 追加时沿用最新源事件上下文，使发出的合并帧序号代表其覆盖区间末尾。
         */
        private void append(TurnEvent.Context latestContext, String fragment) {
            text.append(fragment);
            context = latestContext;
        }

        /**
         * poll 时才复制一次 builder 并生成不可变 API 事件，发布路径不做反复字符串复制。
         */
        private TurnEvent materialize() {
            return event != null ? event : kind.event(context, text.toString());
        }
    }
}
