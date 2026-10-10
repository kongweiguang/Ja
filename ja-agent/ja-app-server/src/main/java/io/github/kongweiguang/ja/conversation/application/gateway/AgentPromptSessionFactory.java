// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.agent.application.skill.SkillCatalog;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * 从当前配置、Skill 元数据目录与 Workspace trust 创建请求级 Prompt Session。
 */
public interface AgentPromptSessionFactory {
    /**
     * Session 在 Turn 接纳前创建但只在模型执行阶段读取规则和持久 scope。
     */
    AgentPromptSession open(SessionRequest request);

    /**
     * Resolver 向 Prompt 边界传递的最小冻结输入，不暴露配置 Adapter 或 Secret。
     *
     * @param threadId 所属对话的稳定标识
     * @param workspaceId 所属工作区稳定标识
     * @param workspaceRoot 工作区根目录
     * @param jaHome Ja 用户目录
     * @param trusted 工作区是否已获信任
     * @param environment 环境
     * @param baseBudget 基础预算
     * @param skillCatalog Skill 目录
     * @param skills Skill 列表
     * @param skillNamesById 按 Skill 标识索引的名称
     */
    record SessionRequest(String threadId,
                          String workspaceId,
                          Path workspaceRoot,
                          Path jaHome,
                          boolean trusted,
                          String environment,
                          ContextBudget baseBudget,
                          SkillCatalog skillCatalog,
                          SkillCatalog.Catalog skills,
                          Map<String, String> skillNamesById) {
        /**
         * 防御性规范路径并冻结配置与发现目录；Skill 正文仅在 read 激活时访问文件系统。
         */
        public SessionRequest {
            threadId = ContractChecks.identifier(threadId, "threadId");
            workspaceId = ContractChecks.identifier(workspaceId, "workspaceId");
            workspaceRoot = ContractChecks.absolutePath(workspaceRoot, "workspaceRoot");
            jaHome = ContractChecks.absolutePath(jaHome, "jaHome");
            environment = Objects.requireNonNull(environment, "environment");
            baseBudget = Objects.requireNonNull(baseBudget, "baseBudget");
            skillCatalog = Objects.requireNonNull(skillCatalog, "skillCatalog");
            skills = Objects.requireNonNull(skills, "skills");
            skillNamesById = Map.copyOf(Objects.requireNonNull(skillNamesById, "skillNamesById"));
            skillNamesById.forEach((skillId, name) -> {
                ContractChecks.runtimeSkillIdentifier(skillId);
                if (name.isBlank() || !skillId.endsWith(":" + name)) {
                    throw new IllegalArgumentException("invalid Skill identity map");
                }
            });
        }
    }
}
