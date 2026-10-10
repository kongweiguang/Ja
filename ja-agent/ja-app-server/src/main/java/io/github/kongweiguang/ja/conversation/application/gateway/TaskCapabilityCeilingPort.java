// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.conversation.domain.model.SubagentPolicy;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;

import java.util.Optional;

/**
 * 在 Task 领域与运行时目录之间传递版本化工具上限；核心运行时不解释 Task seed。
 */
public interface TaskCapabilityCeilingPort {
    /**
     * 按完整执行身份一次读取策略、权限上限与 Child identity，准入中的 Child 由精确匹配的快照覆盖。
     */
    RuntimeSnapshot read(AgentIdentity identity);

    /**
     * 使用最终请求目录身份创建后代 ceiling，调用方不得自行拼接 Task JSON。
     */
    JsonObject create(ThreadPreferences preferences, String configGeneration,
                      JaToolCatalogIdentity catalogIdentity);

    /**
     * 由 Task 持久 lineage 决定的运行时身份类型；Resolver 不需要认识 TaskModels。
     */
    enum Kind {
        /**
         * 独立侧边任务由用户直接管理。
         */
        SIDE_TASK,
        /**
         * Subagent 受父任务生命周期约束。
         */
        SUBAGENT
    }

    /**
     * Task 适配器向 conversation Runtime 暴露的最小身份闭集，避免端口反向依赖 Task 领域模型。
     *
     * @param taskThreadId 任务对话标识
     * @param parentThreadId 父对话标识
     * @param rootThreadId 任务树根对话标识
     * @param taskName 任务名称
     * @param parentTaskName 父任务名称
     * @param rootTaskName 根任务名称
     * @param kind 类型分类
     */
    record RuntimeIdentity(String taskThreadId,
                           String parentThreadId,
                           String rootThreadId,
                           String taskName,
                           String parentTaskName,
                           String rootTaskName,
                           Kind kind) {
        /**
         * 在边界处固定身份格式与标题长度，防止 Resolver 接收未校验的持久投影。
         */
        public RuntimeIdentity {
            taskThreadId = prefixedIdentifier(taskThreadId, "thr_", "taskThreadId");
            parentThreadId = prefixedIdentifier(parentThreadId, "thr_", "parentThreadId");
            rootThreadId = prefixedIdentifier(rootThreadId, "thr_", "rootThreadId");
            taskName = title(taskName, "taskName");
            parentTaskName = title(parentTaskName, "parentTaskName");
            rootTaskName = title(rootTaskName, "rootTaskName");
            java.util.Objects.requireNonNull(kind, "kind");
        }

        /**
         * 端口身份只接受与既有 Thread 标识一致的前缀，避免跨域 DTO 变成任意字符串容器。
         */
        private static String prefixedIdentifier(String value, String prefix, String name) {
            String identifier = ContractChecks.identifier(value, name);
            if (!identifier.startsWith(prefix)) {
                throw new IllegalArgumentException(name + " has invalid prefix");
            }
            return identifier;
        }

        /**
         * 标题允许自然语言，但不能携带控制边界或无界内容进入 System prompt。
         */
        private static String title(String value, String name) {
            String title = ContractChecks.text(value, name, 512, false);
            if (title.isBlank()) {
                throw new IllegalArgumentException("invalid " + name);
            }
            return title;
        }
    }

    /**
     * 把同一准入代际中的策略、权限上限与 lineage 一起冻结，避免首次 Child Turn 部分读到提交前状态。
     *
     * @param requestIdentity 完整租户、Thread、Turn、用户身份
     * @param capabilityCeiling Child 创建时的权限上限；Root 不具有 Task ceiling
     * @param taskIdentity 持久或准入中的 Child lineage；Root 为空
     * @param policy 当前 Thread 冻结的委派策略
     */
    record RuntimeSnapshot(AgentIdentity requestIdentity,
                           Optional<JsonObject> capabilityCeiling,
                           Optional<RuntimeIdentity> taskIdentity,
                           SubagentPolicy policy) {
        /**
         * Child identity 与 ceiling 必须同时存在，拒绝拼接不同来源的部分运行时事实。
         */
        public RuntimeSnapshot {
            java.util.Objects.requireNonNull(requestIdentity, "requestIdentity");
            capabilityCeiling = java.util.Objects.requireNonNull(capabilityCeiling, "capabilityCeiling");
            taskIdentity = java.util.Objects.requireNonNull(taskIdentity, "taskIdentity");
            java.util.Objects.requireNonNull(policy, "policy");
            if (capabilityCeiling.isPresent() != taskIdentity.isPresent()
                || taskIdentity.isPresent()
                   && !requestIdentity.threadId().equals(taskIdentity.orElseThrow().taskThreadId())) {
                throw new IllegalArgumentException("task runtime snapshot is inconsistent");
            }
        }
    }
}
