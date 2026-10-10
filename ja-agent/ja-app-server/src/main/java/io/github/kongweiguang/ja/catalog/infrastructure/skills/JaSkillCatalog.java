// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.infrastructure.skills;

import io.github.kongweiguang.ja.agent.application.skill.SkillCatalog;
import io.github.kongweiguang.ja.agent.domain.skill.SkillDescriptor;
import io.github.kongweiguang.ja.agent.domain.skill.SkillDocument;
import io.github.kongweiguang.ja.agent.domain.skill.SkillReadRequest;
import io.github.kongweiguang.ja.agent.domain.skill.SkillRequestContext;
import io.github.kongweiguang.ja.agent.application.gateway.skill.SkillSource;
import io.github.kongweiguang.ja.agent.skills.local.infrastructure.LocalSkillSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 将 Ja 的本地 Skill 来源与工作区信任规则绑定到与供应商无关的 catalog。
 */
public final class JaSkillCatalog {
    private static final String TENANT_ID = "ja";
    private static final String USER_ROOT = "skill-root-user";
    private static final String JA_ROOT = "skill-root-ja";
    private static final String WORKSPACE_ROOT = "workspace-directory";
    private static final String WORKSPACE_TRUSTED = "workspace-trusted";

    private final SkillCatalog catalog;
    private final Path agentsSkillRoot;
    private final Path jaSkillRoot;

    /**
     * 固定 Ja 的来源优先级；工作区身份与信任仍按请求传入，避免目录扫描跨 Thread 串用。
     */
    public JaSkillCatalog(Path agentsSkillRoot, Path jaSkillRoot) {
        this.agentsSkillRoot = normalizeRoot(agentsSkillRoot, "agentsSkillRoot");
        this.jaSkillRoot = normalizeRoot(jaSkillRoot, "jaSkillRoot");
        List<SkillSource> sources = List.of(
                LocalSkillSource.bundled(),
                LocalSkillSource.directory("user", 1, USER_ROOT),
                LocalSkillSource.directory("ja", 2, JA_ROOT),
                LocalSkillSource.workspace("project", 3, WORKSPACE_ROOT, WORKSPACE_TRUSTED));
        this.catalog = new SkillCatalog(sources);
    }

    /**
     * 仅规范化配置路径，不在启动期解析链接或强制要求目录已存在。
     */
    private static Path normalizeRoot(Path root, String name) {
        return Objects.requireNonNull(root, name).toAbsolutePath().normalize();
    }

    /**
     * 返回引擎级 Skill catalog，使 Prompt 和 Tool 共用同一来源校验与读取边界。
     */
    public SkillCatalog applicationCatalog() {
        return catalog;
    }

    /**
     * 先按来源限定的停用标识过滤，再发现本次请求最终可见的同名胜出项。
     */
    public SkillCatalog.Catalog discover(Path workspaceDirectory, boolean workspaceTrusted,
                                         Set<String> disabledReferences) {
        return catalog.discover(context(workspaceDirectory, workspaceTrusted), disabledReferences);
    }

    /**
     * 为设置页列出各来源副本；未信任工作区的内容不会进入来源查询。
     */
    public List<SkillDescriptor> discoverAll(Path workspaceDirectory, boolean workspaceTrusted) {
        return catalog.discoverAll(context(workspaceDirectory, workspaceTrusted));
    }

    /**
     * 从已发现目录读取租户绑定的资源，不向调用方暴露本地路径定位信息。
     */
    public SkillDocument read(SkillCatalog.Catalog discovered, SkillReadRequest request) {
        return catalog.read(discovered, request);
    }

    /**
     * 从既有快照收窄继承任务可见的 Skill，避免重扫后同名来源发生变化。
     */
    public SkillCatalog.Catalog select(SkillCatalog.Catalog discovered, List<String> allowedNames) {
        return catalog.select(discovered, allowedNames);
    }

    /**
     * 为单个请求生成不可变来源属性；本地 adapter 在发现前重新检查项目目录信任。
     */
    private SkillRequestContext context(Path workspaceDirectory, boolean workspaceTrusted) {
        Path workspace = normalizeRoot(workspaceDirectory, "workspaceDirectory");
        return new SkillRequestContext(TENANT_ID, Map.of(
                USER_ROOT, agentsSkillRoot.toString(),
                JA_ROOT, jaSkillRoot.toString(),
                WORKSPACE_ROOT, workspace.toString(),
                WORKSPACE_TRUSTED, Boolean.toString(workspaceTrusted)));
    }
}
