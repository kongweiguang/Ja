// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.util.Optional;

/**
 * 在 Task 领域与运行时目录之间传递版本化能力上限；通用能力 Catalog 不解释 Task seed。
 */
public interface TaskCapabilityCeilingPort {
    /** Root 返回空，Child 返回创建时持久化的不可变 ceiling。 */
    Optional<JsonObject> read(String threadId);

    /** 读取 Child 的持久身份；Root 返回空，resolver 依据 Kind 区分独立 Side Task 与 Subagent 上限。 */
    default Optional<RuntimeIdentity> readIdentity(String threadId) {
        return Optional.empty();
    }

    /** Task 适配器向 conversation Runtime 暴露的最小身份闭集，避免端口反向依赖 Task 领域模型。 */
    record RuntimeIdentity(String taskThreadId, String parentThreadId, String rootThreadId,
                           String taskName, String parentTaskName, String rootTaskName, Kind kind) {
        /** 在边界处固定身份格式与标题长度，防止 Resolver 接收未校验的持久投影。 */
        public RuntimeIdentity {
            taskThreadId = prefixedIdentifier(taskThreadId, "thr_", "taskThreadId");
            parentThreadId = prefixedIdentifier(parentThreadId, "thr_", "parentThreadId");
            rootThreadId = prefixedIdentifier(rootThreadId, "thr_", "rootThreadId");
            taskName = title(taskName, "taskName");
            parentTaskName = title(parentTaskName, "parentTaskName");
            rootTaskName = title(rootTaskName, "rootTaskName");
            java.util.Objects.requireNonNull(kind, "kind");
        }

        /** 端口身份只接受与既有 Thread 标识一致的前缀，避免跨域 DTO 变成任意字符串容器。 */
        private static String prefixedIdentifier(String value, String prefix, String name) {
            String identifier = ContractChecks.identifier(value, name);
            if (!identifier.startsWith(prefix)) throw new IllegalArgumentException(name + " has invalid prefix");
            return identifier;
        }

        /** 标题允许自然语言，但不能携带控制边界或无界内容进入 System prompt。 */
        private static String title(String value, String name) {
            String title = ContractChecks.text(value, name, 512, false);
            if (title.isBlank()) throw new IllegalArgumentException("invalid " + name);
            return title;
        }
    }

    /** 由 Task 持久 lineage 决定的运行时身份类型；Resolver 不需要认识 TaskModels。 */
    enum Kind {
        /** 独立侧边任务由用户直接管理。 */
        SIDE_TASK,
        /** Subagent 受父任务生命周期约束。 */
        SUBAGENT
    }

    /** 使用最终请求目录身份创建后代 ceiling，调用方不得自行拼接 Task JSON。 */
    JsonObject create(ThreadPreferences preferences, String configGeneration,
                      AgentCapability.CatalogIdentity catalogIdentity);
}
