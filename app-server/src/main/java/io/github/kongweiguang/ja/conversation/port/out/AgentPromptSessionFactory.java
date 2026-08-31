// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.ContextBudget;
import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.nio.file.Path;
import java.util.Objects;

/** 从冻结配置、Skill snapshot 与 Workspace trust 创建逐 Turn Prompt Session。 */
public interface AgentPromptSessionFactory {
    /** Session 在 Turn 接纳前创建但只在模型执行阶段读取规则和持久 scope。 */
    AgentPromptSession open(SessionRequest request);

    /** Resolver 向 Prompt 边界传递的最小冻结输入，不暴露配置 Adapter 或 Secret。 */
    record SessionRequest(String threadId, Path workspaceRoot, Path jaHome, boolean trusted,
                          String environment, ContextBudget baseBudget,
                          SkillCatalog skillCatalog, SkillCatalog.SkillSnapshot skills) {
        /** 防御性规范路径并冻结全部依赖，避免 Session 自行重读配置文件。 */
        public SessionRequest {
            threadId = ContractChecks.identifier(threadId, "threadId");
            workspaceRoot = ContractChecks.absolutePath(workspaceRoot, "workspaceRoot");
            jaHome = ContractChecks.absolutePath(jaHome, "jaHome");
            environment = Objects.requireNonNull(environment, "environment");
            baseBudget = Objects.requireNonNull(baseBudget, "baseBudget");
            skillCatalog = Objects.requireNonNull(skillCatalog, "skillCatalog");
            skills = Objects.requireNonNull(skills, "skills");
        }
    }
}
