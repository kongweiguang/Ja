// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.service;

import io.github.kongweiguang.ja.plan.application.api.PlanEvent;
import io.github.kongweiguang.ja.plan.application.api.PlanEventSink;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Plan 事件订阅按连接隔离；单个连接关闭或失败仅移除自身，不影响已提交状态与其他连接。
 */
public final class PlanEventRegistry {
    private final CopyOnWriteArrayList<PlanEventSink> subscribers = new CopyOnWriteArrayList<>();

    /**
     * 每个连接句柄只取消自身订阅，不改变 SQLite Plan 状态或其它窗口。
     */
    public AutoCloseable subscribe(PlanEventSink sink) {
        Objects.requireNonNull(sink, "sink");
        if (!subscribers.addIfAbsent(sink)) {
            throw new IllegalStateException("Plan event sink already subscribed");
        }
        return () -> subscribers.remove(sink);
    }

    /**
     * 提交后的 Plan 事件按订阅顺序发送，连接背压不能静默吞掉。
     */
    public void publish(PlanEvent event) {
        Objects.requireNonNull(event, "event");
        for (PlanEventSink sink : subscribers) {
            try {
                sink.publish(event).whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        subscribers.remove(sink);
                    }
                });
            } catch (RuntimeException failure) {
                // mutation 已提交，观察者失败只能隔离该连接，不能让 RPC 重试产生第二次副作用。
                subscribers.remove(sink);
            }
        }
    }

    /**
     * 没有订阅者时调用方可跳过快照与 event 额外读取。
     */
    public boolean isEmpty() {
        return subscribers.isEmpty();
    }
}

