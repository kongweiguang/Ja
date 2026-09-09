// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.policy;

import io.github.kongweiguang.ja.conversation.port.out.ToolPolicy;
import io.github.kongweiguang.ja.foundation.validation.ContractChecks;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 把固定 Tool 策略注册表编译为同步责任链，失败时只允许收紧准入。 */
public final class ToolPolicyChain {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolPolicyChain.class);
    private static final ToolPolicy.Decision POLICY_FAILURE =
            ToolPolicy.Decision.deny("TOOL_FAILED", "Tool policy rejected the call");
    private final List<Registration> policies;

    /** 复制、排序并校验组合根的固定列表，运行中不允许注册、删除或重排。 */
    public ToolPolicyChain(List<? extends ToolPolicy> policies) {
        Objects.requireNonNull(policies, "policies");
        List<Registration> ordered = new ArrayList<>(policies.size());
        Set<String> identities = new HashSet<>();
        for (ToolPolicy policy : policies) {
            ToolPolicy required = Objects.requireNonNull(policy, "policy");
            String id = ContractChecks.identifier(required.id(), "Tool policy id");
            if (!identities.add(id)) throw new IllegalArgumentException("duplicate Tool policy id: " + id);
            ordered.add(new Registration(id, required.order(), required));
        }
        ordered.sort(Comparator.comparingInt(Registration::order).thenComparing(Registration::id));
        this.policies = List.copyOf(ordered);
    }

    /** 正序判断并在首次拒绝或策略故障时短路，禁止失败后继续逼近 Tool 副作用。 */
    public ToolPolicy.Decision evaluate(ToolPolicy.Context context) {
        Objects.requireNonNull(context, "context");
        for (Registration registration : policies) {
            try {
                ToolPolicy.Decision decision = Objects.requireNonNull(
                        registration.policy().evaluate(context), "Tool policy decision");
                if (!decision.proceed()) return decision;
            } catch (java.util.concurrent.CancellationException cancellation) {
                throw cancellation;
            } catch (RuntimeException failure) {
                LOGGER.warn("Tool policy failed id={} cause={}", registration.id(),
                        failure.getClass().getSimpleName());
                return POLICY_FAILURE;
            }
        }
        return ToolPolicy.Decision.allow();
    }

    /** 冻结身份和顺序，防止有状态实现让排序、去重与失败日志观察到不同注册值。 */
    private record Registration(String id, int order, ToolPolicy policy) {
    }
}
