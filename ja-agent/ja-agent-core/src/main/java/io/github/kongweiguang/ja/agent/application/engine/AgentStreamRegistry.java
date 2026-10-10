// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.engine;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.TurnEvent;
import io.github.kongweiguang.ja.agent.application.api.TurnEventSink;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentStreamSubscription;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 管理仅存于进程内的流式订阅；持久事件仍由 Store 水位补读，流订阅关闭不影响执行。
 */
public final class AgentStreamRegistry implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(AgentStreamRegistry.class.getName());
    private final int maximumSubscriptions;
    private final Map<AgentIdentity, List<AgentStreamSubscription>> subscriptions = new HashMap<>();
    private boolean closed;

    /**
     * 以当前最大并行执行数限制预订资源，避免未提交执行的空订阅无界占用内存。
     */
    public AgentStreamRegistry(int maximumSubscriptions) {
        if (maximumSubscriptions < 1) {
            throw new IllegalArgumentException("maximumSubscriptions must be positive");
        }
        this.maximumSubscriptions = maximumSubscriptions;
    }

    /**
     * 在调度前登记订阅；Engine 注册表与调用方共同持有它并在各自生命周期边界关闭。
     */
    @SuppressWarnings("PMD.CloseResource")
    public synchronized AgentStreamSubscription subscribe(AgentIdentity identity, int capacity) {
        Objects.requireNonNull(identity, "identity");
        ensureOpen();
        discardClosedSubscriptions();
        if (capacity < 1 || capacity > 256) {
            throw new IllegalArgumentException("stream capacity must be in [1,256]");
        }
        if (activeSubscriptionCount() >= maximumSubscriptions) {
            throw new IllegalStateException("transient stream subscription capacity is exhausted");
        }
        AgentStreamSubscription subscription = new AgentStreamSubscription(identity, capacity);
        subscriptions.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(subscription);
        return subscription;
    }

    /**
     * 获取与单次 Loop 执行身份绑定的非阻塞 sink；无订阅时临时增量按设计直接丢弃。
     */
    public TurnEventSink sink(AgentIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return event -> publish(identity, event);
    }

    /**
     * 把增量路由到有界订阅；正常订阅仍由注册表与调用方共同持有。
     */
    @SuppressWarnings("PMD.CloseResource")
    public synchronized void publish(AgentIdentity identity, TurnEvent event) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(event, "event");
        if (closed) {
            return;
        }
        List<AgentStreamSubscription> selected = subscriptions.get(identity);
        if (selected == null) {
            return;
        }
        for (AgentStreamSubscription subscription : List.copyOf(selected)) {
            if (subscription.state() == AgentStreamSubscription.State.CLOSED) {
                selected.remove(subscription);
                continue;
            }
            try {
                subscription.publish(event);
            } catch (RuntimeException failure) {
                subscription.close();
                selected.remove(subscription);
                LOGGER.log(System.Logger.Level.WARNING, "Agent transient stream detached after invalid event: {0}",
                        failure.toString());
            }
        }
        if (selected.isEmpty()) {
            subscriptions.remove(identity, selected);
        }
    }

    /**
     * 关闭 Engine 时唤醒所有等待者，但不触及任何 Loop 的取消令牌。
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        subscriptions.values().forEach(values -> values.forEach(AgentStreamSubscription::close));
        subscriptions.clear();
    }

    /**
     * 仅回收用户已关闭的订阅，RESYNC_REQUIRED 必须由调用方显式关闭后才能重新订阅。
     */
    private void discardClosedSubscriptions() {
        subscriptions.values().forEach(values -> values.removeIf(
                subscription -> subscription.state() == AgentStreamSubscription.State.CLOSED));
        subscriptions.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    /**
     * 汇总所有执行身份下的活动订阅数，用构造时上限保护 Registry 内存。
     */
    private int activeSubscriptionCount() {
        return subscriptions.values().stream().mapToInt(List::size).sum();
    }

    /**
     * 在关闭后拒绝新建订阅，避免返回一个永远不能收到事件的句柄。
     */
    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("agent stream registry is closed");
        }
    }
}
