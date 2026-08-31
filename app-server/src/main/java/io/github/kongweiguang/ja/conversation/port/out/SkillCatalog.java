// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * 从受信任来源冻结 Skill 目录并按修订读取资源的出站 SPI。
 */
public interface SkillCatalog {
    /**
     * 按内置、用户、工作区优先级冻结目录，保证一个 Turn 内不会随文件变化漂移。
     */
    SkillSnapshot snapshot(SnapshotRequest request);

    /**
     * 创建不扫描任何来源的空能力快照，供未启用 Skill 的 Provider 保持最小启动面。
     */
    SkillSnapshot emptySnapshot();

    /**
     * 从同一个完整快照按 revision 派生 Turn 视图，禁止在过滤期间重新读取或切换目录代际。
     */
    SkillSnapshot select(SkillSnapshot snapshot, List<String> allowedRevisions);

    /**
     * 只从给定快照修订读取有界资源，禁止绕过快照读取最新文件。
     */
    SkillDocument read(SkillSnapshot snapshot, SkillReadRequest request);

    /**
     * 创建 Skill 快照所需的 cwd、两类用户来源与冻结信任事实。
     */
    record SnapshotRequest(
            Path workspaceDirectory,
            Path agentsSkillRoot,
            Path jaSkillRoot,
            boolean workspaceTrusted) {
        /**
         * 路径必须由 App Server 的权威 home/workspace 配置显式派生；信任值由同一配置代际冻结，
         * Adapter 只能据此关闭项目来源，不能自行推测或扩大信任。
         */
        public SnapshotRequest {
            workspaceDirectory = ContractChecks.absolutePath(workspaceDirectory, "workspaceDirectory");
            agentsSkillRoot = ContractChecks.absolutePath(agentsSkillRoot, "agentsSkillRoot");
            jaSkillRoot = ContractChecks.absolutePath(jaSkillRoot, "jaSkillRoot");
        }
    }

    /**
     * 一个 Turn 可重复读取的不可变 Skill 目录与生成时刻。
     */
    record SkillSnapshot(String revision, List<SkillDescriptor> skills, Instant createdAt) {
        /**
         * 固化目录修订和条目顺序，使恢复与重试得到相同 Skill 解析结果。
         */
        public SkillSnapshot {
            revision = ContractChecks.identifier(revision, "revision");
            skills = ContractChecks.immutableList(skills, "skills");
            if (createdAt == null) {
                throw new IllegalArgumentException("createdAt is required");
            }
        }
    }

    /**
     * 目录中公开的 Skill 身份、说明、来源与内容修订。
     */
    record SkillDescriptor(String name, String description, Source source, String revision) {
        /**
         * 校验公开描述并保留来源，供覆盖冲突和审计解释使用。
         */
        public SkillDescriptor {
            name = ContractChecks.identifier(name, "name");
            description = ContractChecks.text(description, "description", 16_384, false);
            if (source == null) {
                throw new IllegalArgumentException("source is required");
            }
            revision = ContractChecks.identifier(revision, "revision");
        }
    }

    /**
     * Skill 来源按声明顺序形成从低到高的覆盖优先级。
     */
    enum Source {
        /**
         * 应用随包发布的内置 Skill。
         */
        BUNDLED(0),
        /**
         * Agent Skills 通用用户目录中的个人 Skill。
         */
        AGENTS_USER(1),
        /**
         * Ja home 中的个人 Skill。
         */
        JA_USER(2),
        /**
         * 当前工作区声明且受信任边界约束的项目 Skill。
         */
        WORKSPACE(3);

        private final int priority;

        /**
         * 显式固定优先级，避免未来仅调整枚举声明位置就静默改变覆盖语义。
         */
        Source(int priority) {
            this.priority = priority;
        }

        /**
         * 数值只表达覆盖与目录展示顺序，不作为授权；越具体、越靠后的来源优先级越高。
         */
        public int priority() {
            return priority;
        }
    }

    /**
     * 在已冻结 Skill 内读取单个相对资源的有界请求。
     */
    record SkillReadRequest(String skillName, String resourcePath, int maxCharacters) {
        /**
         * 规范化分隔符并拒绝绝对路径或父级逃逸，文件系统复核仍由 Adapter 执行。
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
     * 返回给 Agent Loop 的有界 Skill 文档及截断事实。
     */
    record SkillDocument(String skillName, String resourcePath, String revision, String content, boolean truncated) {
        /**
         * 固化读取修订与截断后的正文，避免调用方误把部分内容当作最新完整文件。
         */
        public SkillDocument {
            skillName = ContractChecks.identifier(skillName, "skillName");
            resourcePath = ContractChecks.text(resourcePath, "resourcePath", 1_024, false);
            revision = ContractChecks.identifier(revision, "revision");
            content = ContractChecks.text(content, "content", 4_000_000, true);
        }
    }
}
