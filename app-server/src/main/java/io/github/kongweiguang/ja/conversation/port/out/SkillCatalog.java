// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.nio.file.Path;
import java.util.List;

/**
 * 渐进发现 Skill 元数据，并在实际激活后实时读取资源的出站 SPI。
 */
public interface SkillCatalog {
    /**
     * 按内置、用户、工作区优先级发现名称和描述；正文保持在原始来源中，直到 read 才读取。
     */
    Catalog discover(DiscoveryRequest request);

    /**
     * 创建不扫描任何来源的空目录，供禁用 Skill 的 Provider 保持最小启动面。
     */
    Catalog emptyCatalog();

    /**
     * 从同一个发现结果按名称派生可见目录，禁止筛选阶段重新扫描来源或扩大可见集合。
     */
    Catalog select(Catalog catalog, List<String> allowedNames);

    /**
     * 从目录绑定的原始 locator 实时读取有界资源，不缓存正文或内容 revision。
     */
    SkillDocument read(Catalog catalog, SkillReadRequest request);

    /**
     * 发现 Skill 元数据所需的 cwd、两类用户来源与工作区信任事实。
     */
    record DiscoveryRequest(
            Path workspaceDirectory,
            Path agentsSkillRoot,
            Path jaSkillRoot,
            boolean workspaceTrusted) {
        /**
         * 路径必须由 App Server 权威 home/workspace 配置显式派生；Adapter 只能依据信任值关闭项目来源。
         */
        public DiscoveryRequest {
            workspaceDirectory = ContractChecks.absolutePath(workspaceDirectory, "workspaceDirectory");
            agentsSkillRoot = ContractChecks.absolutePath(agentsSkillRoot, "agentsSkillRoot");
            jaSkillRoot = ContractChecks.absolutePath(jaSkillRoot, "jaSkillRoot");
        }
    }

    /**
     * 单次发现得到的稳定元数据目录；正文仍由 read 从原始来源实时取得。
     */
    record Catalog(List<SkillDescriptor> skills) {
        /**
         * 复制条目并固定展示顺序，避免调用方修改列表而越过 select 边界。
         */
        public Catalog {
            skills = ContractChecks.immutableList(skills, "skills");
        }
    }

    /**
     * 目录中公开的 Skill 身份、说明与最终解析来源，不暴露内容版本或物理路径。
     */
    record SkillDescriptor(String name, String description, Source source) {
        /**
         * 元数据只承担模型发现与覆盖解释，不能携带 Tool 授权或正文缓存。
         */
        public SkillDescriptor {
            name = ContractChecks.identifier(name, "name");
            description = ContractChecks.text(description, "description", 16_384, false);
            if (source == null) {
                throw new IllegalArgumentException("source is required");
            }
        }
    }

    /**
     * Skill 来源按声明顺序形成从低到高的覆盖优先级。
     */
    enum Source {
        /** 应用随包发布的内置 Skill。 */
        BUNDLED(0),
        /** Agent Skills 通用用户目录中的个人 Skill。 */
        AGENTS_USER(1),
        /** Ja home 中的个人 Skill。 */
        JA_USER(2),
        /** 当前工作区声明且受信任边界约束的项目 Skill。 */
        WORKSPACE(3);

        private final int priority;

        /**
         * 显式固定优先级，避免调整枚举声明位置时静默改变覆盖语义。
         */
        Source(int priority) {
            this.priority = priority;
        }

        /**
         * 数值只表达覆盖与展示顺序，不作为授权；越具体的来源优先级越高。
         */
        public int priority() {
            return priority;
        }
    }

    /**
     * 在已发现 Skill 内读取单个相对资源的有界请求。
     */
    record SkillReadRequest(String skillName, String resourcePath, int maxCharacters) {
        /**
         * 先规范化分隔符并拒绝显然逃逸；物理 containment 与重解析点仍由 Adapter 在每次 read 复核。
         */
        public SkillReadRequest {
            skillName = ContractChecks.identifier(skillName, "skillName");
            resourcePath = ContractChecks.text(resourcePath, "resourcePath", 1_024, false)
                    .replace('\\', '/');
            if (resourcePath.startsWith("/") || resourcePath.contains("../") || resourcePath.equals("..")
                || maxCharacters < 1 || maxCharacters > 4_000_000) {
                throw new IllegalArgumentException("invalid Skill resource request");
            }
        }
    }

    /**
     * 返回给 Agent Loop 的实时有界 Skill 文档及截断事实。
     */
    record SkillDocument(String skillName, String resourcePath, String content, boolean truncated) {
        /**
         * 文档不携带 revision，避免调用方把一次读取误建模为不可变包代际。
         */
        public SkillDocument {
            skillName = ContractChecks.identifier(skillName, "skillName");
            resourcePath = ContractChecks.text(resourcePath, "resourcePath", 1_024, false);
            content = ContractChecks.text(content, "content", 4_000_000, true);
        }
    }
}
