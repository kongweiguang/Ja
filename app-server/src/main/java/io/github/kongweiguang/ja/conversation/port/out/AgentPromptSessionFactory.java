// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.ContextBudget;
import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Map;

/** 从当前配置、Skill 元数据目录与 Workspace trust 创建请求级 Prompt Session。 */
public interface AgentPromptSessionFactory {
    /** Session 在 Turn 接纳前创建但只在模型执行阶段读取规则和持久 scope。 */
    AgentPromptSession open(SessionRequest request);

    /** Resolver 向 Prompt 边界传递的最小冻结输入，不暴露配置 Adapter 或 Secret。 */
    record SessionRequest(String threadId, Path workspaceRoot, Path jaHome, boolean trusted,
                          String environment, ContextBudget baseBudget,
                          SkillCatalog skillCatalog, SkillCatalog.Catalog skills,
                          Map<String, String> skillNamesById) {
        /** 防御性规范路径并冻结配置与发现目录；Skill 正文仅在 read 激活时访问文件系统。 */
        public SessionRequest {
            threadId = ContractChecks.identifier(threadId, "threadId");
            workspaceRoot = ContractChecks.absolutePath(workspaceRoot, "workspaceRoot");
            jaHome = ContractChecks.absolutePath(jaHome, "jaHome");
            environment = Objects.requireNonNull(environment, "environment");
            baseBudget = Objects.requireNonNull(baseBudget, "baseBudget");
            skillCatalog = Objects.requireNonNull(skillCatalog, "skillCatalog");
            skills = Objects.requireNonNull(skills, "skills");
            skillNamesById = Map.copyOf(Objects.requireNonNull(skillNamesById, "skillNamesById"));
            if (skillNamesById.entrySet().stream().anyMatch(entry -> !entry.getKey().startsWith("skill_")
                    || entry.getKey().length() > 128 || entry.getValue().isBlank())) {
                throw new IllegalArgumentException("invalid Skill identity map");
            }
        }
    }
}
