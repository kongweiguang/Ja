// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.observation;

import io.github.kongweiguang.ja.conversation.port.out.ExecutionObserver;
import io.github.kongweiguang.ja.foundation.validation.ContractChecks;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 将固定观察器注册表按事件类型预路由，并逐个隔离非关键诊断故障。 */
public final class ExecutionObservers {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionObservers.class);
    private final Map<ExecutionObserver.EventKind, List<Registration>> subscribers;

    /** 复制、排序和去重注册表，运行中不允许动态订阅或改变通知顺序。 */
    public ExecutionObservers(List<? extends ExecutionObserver> observers) {
        Objects.requireNonNull(observers, "observers");
        List<Registration> ordered = new ArrayList<>(observers.size());
        Set<String> identities = new HashSet<>();
        for (ExecutionObserver observer : observers) {
            ExecutionObserver required = Objects.requireNonNull(observer, "observer");
            String id = ContractChecks.identifier(required.id(), "execution observer id");
            if (!identities.add(id)) throw new IllegalArgumentException("duplicate execution observer id: " + id);
            Set<ExecutionObserver.EventKind> subscriptions = Set.copyOf(
                    Objects.requireNonNull(required.subscriptions(), "observer subscriptions"));
            if (subscriptions.isEmpty()) {
                throw new IllegalArgumentException("execution observer must subscribe to at least one event");
            }
            ordered.add(new Registration(id, required.order(), subscriptions, required));
        }
        ordered.sort(Comparator.comparingInt(Registration::order).thenComparing(Registration::id));
        EnumMap<ExecutionObserver.EventKind, List<Registration>> routes =
                new EnumMap<>(ExecutionObserver.EventKind.class);
        for (ExecutionObserver.EventKind kind : ExecutionObserver.EventKind.values()) {
            routes.put(kind, new ArrayList<>());
        }
        for (Registration registration : ordered) {
            for (ExecutionObserver.EventKind kind : registration.subscriptions()) {
                routes.get(Objects.requireNonNull(kind, "subscription kind")).add(registration);
            }
        }
        routes.replaceAll((kind, route) -> List.copyOf(route));
        this.subscribers = Map.copyOf(routes);
    }

    /** 只通知当前类型的订阅者；异常仅记录安全身份和类型，不能覆盖已确定的内核结果。 */
    public void observe(ExecutionObserver.Event event) {
        Objects.requireNonNull(event, "event");
        for (Registration registration : subscribers.get(event.kind())) {
            try {
                registration.observer().observe(event);
            } catch (RuntimeException failure) {
                LOGGER.warn("Execution observer failed id={} event={} cause={}",
                        registration.id(), event.kind(), failure.getClass().getSimpleName());
            }
        }
    }

    /** 冻结注册元数据，避免有状态实现让路由与故障日志观察到不同身份或订阅集合。 */
    private record Registration(
            String id,
            int order,
            Set<ExecutionObserver.EventKind> subscriptions,
            ExecutionObserver observer) {
    }
}
