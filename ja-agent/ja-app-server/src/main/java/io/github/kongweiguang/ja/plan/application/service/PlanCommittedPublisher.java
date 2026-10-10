// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.service;

import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepository;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 只从已提交聚合事件投影 UI 通知，并用各自持久序号去重。
 */
public final class PlanCommittedPublisher {
    private final PlanRepository plans;
    private final PlanEventRegistry planEvents;
    private final ConcurrentMap<String, Long> planSequences = new ConcurrentHashMap<>();

    /**
     * 通知只依赖只读 Repository 查询与注册表，不持有生命周期或业务写入服务。
     */
    public PlanCommittedPublisher(PlanRepository plans, PlanEventRegistry planEvents) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.planEvents = Objects.requireNonNull(planEvents, "planEvents");
    }

    /**
     * 聚合与最新事件从同一快照读取，并按持久事件序号去重，避免混用通知与业务水位。
     */
    public synchronized void publishPlanCommitted(String planId) {
        String id = PlanModels.requireId(planId, "planId");
        if (planEvents.isEmpty()) {
            return;
        }
        PlanRepository.PlanObservation observation = plans.readPlanObservation(id);
        PlanRepository.PlanEvent stored = observation.latestEvent();
        if (stored == null || stored.sequence() <= planSequences.getOrDefault(id, 0L)) {
            return;
        }
        PlanModels.PublicEvent event = PlanService.projectPlanPublicEvent(stored);
        planEvents.publish(new io.github.kongweiguang.ja.plan.application.api.PlanEvent(
                observation.plan(), stored.sequence(), event, observation.progress()));
        planSequences.put(id, stored.sequence());
    }
}

