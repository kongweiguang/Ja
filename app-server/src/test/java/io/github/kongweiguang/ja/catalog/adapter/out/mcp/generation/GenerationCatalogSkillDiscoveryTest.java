// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpLimits;
import io.github.kongweiguang.ja.catalog.domain.SkillDescriptor;
import io.github.kongweiguang.ja.catalog.port.out.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationGenerationSnapshot;
import io.github.kongweiguang.ja.conversation.port.out.SkillCatalog;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 Settings 目录复用 Skill 元数据发现，并把发现与持久授权保持为两个独立事实。 */
final class GenerationCatalogSkillDiscoveryTest {
    @TempDir
    private Path temporary;

    /**
     * 四来源只映射有效发现项；配置按名称沿用身份与授权，未登记项稳定展示但默认禁用。
     */
    @Test
    void mapsFourDiscoveryScopesAndMergesConfiguredAuthorizationByName() {
        Path home = temporary.resolve("ja-home").toAbsolutePath().normalize();
        Path agents = temporary.resolve("agents-skills").toAbsolutePath().normalize();
        Path ja = temporary.resolve("ja-skills").toAbsolutePath().normalize();
        Path project = temporary.resolve("project").toAbsolutePath().normalize();
        RecordingSkillCatalog skills = new RecordingSkillCatalog();
        ConfigurationGenerationSnapshot snapshot = snapshot(true, List.of(
                configured("skill_saved-builtin", "builtin-tool", true, "配置描述不应覆盖发现"),
                configured("skill_saved-ja", "ja-tool", false, "配置描述不应覆盖发现"),
                configured("skill_missing", "missing-tool", true, "磁盘已缺失")));

        try (GenerationCatalog catalog = new GenerationCatalog(
                home, new ObjectMapper(), McpLimits.DEFAULT, skills, agents, ja)) {
            CursorPage<SkillDescriptor> page = catalog.listSkills(lease(snapshot), project, true, null, 200);
            Map<String, SkillDescriptor> byName = page.items().stream()
                    .collect(Collectors.toMap(SkillDescriptor::name, Function.identity()));

            assertEquals(Map.of("builtin-tool", "builtin", "agents-tool", "user",
                    "ja-tool", "ja", "project-tool", "project"),
                    byName.entrySet().stream().collect(Collectors.toMap(
                            Map.Entry::getKey, entry -> entry.getValue().scope())));
            assertEquals("skill_saved-builtin", byName.get("builtin-tool").skillId());
            assertTrue(byName.get("builtin-tool").enabled());
            assertEquals("来自 bundled 的描述", byName.get("builtin-tool").description());
            assertEquals("skill_saved-ja", byName.get("ja-tool").skillId());
            assertFalse(byName.get("ja-tool").enabled());
            assertEquals("skill_agents-tool", byName.get("agents-tool").skillId());
            assertFalse(byName.get("agents-tool").enabled());
            assertEquals("skill_project-tool", byName.get("project-tool").skillId());
            assertFalse(byName.containsKey("missing-tool"));
            assertTrue(page.items().stream().allMatch(item -> item.status().equals("healthy")));
            assertEquals(project, skills.lastRequest.get().workspaceDirectory());
            assertTrue(skills.lastRequest.get().workspaceTrusted());
        }
    }

    /** 省略 workspace 时仍发现通用来源，但明确不向 Skill adapter 开放 project 来源。 */
    @Test
    void omitsProjectSourceForGeneralSettingsCatalog() {
        Path home = temporary.resolve("general-home").toAbsolutePath().normalize();
        RecordingSkillCatalog skills = new RecordingSkillCatalog();
        ConfigurationGenerationSnapshot snapshot = snapshot(true, List.of());

        try (GenerationCatalog catalog = new GenerationCatalog(home, new ObjectMapper(), McpLimits.DEFAULT,
                skills, temporary.resolve("agents"), temporary.resolve("ja"))) {
            CursorPage<SkillDescriptor> page = catalog.listSkills(lease(snapshot), null, false, null, 200);

            assertEquals(List.of("agents-tool", "builtin-tool", "ja-tool"),
                    page.items().stream().map(SkillDescriptor::name).sorted().toList());
            assertEquals(home, skills.lastRequest.get().workspaceDirectory());
            assertFalse(skills.lastRequest.get().workspaceTrusted());
        }
    }

    /** Workspace owner 未授权时即使配置代际仍为 trusted，也必须 fail closed 排除 project 来源。 */
    @Test
    void omitsProjectSourceWhenWorkspaceOwnerIsUntrusted() {
        Path home = temporary.resolve("untrusted-home").toAbsolutePath().normalize();
        Path project = temporary.resolve("untrusted-project").toAbsolutePath().normalize();
        RecordingSkillCatalog skills = new RecordingSkillCatalog();

        try (GenerationCatalog catalog = new GenerationCatalog(home, new ObjectMapper(), McpLimits.DEFAULT,
                skills, temporary.resolve("agents-untrusted"), temporary.resolve("ja-untrusted"))) {
            CursorPage<SkillDescriptor> page = catalog.listSkills(
                    lease(snapshot(true, List.of())), project, false, null, 200);

            assertFalse(page.items().stream().anyMatch(item -> item.scope().equals("project")));
            assertFalse(skills.lastRequest.get().workspaceTrusted());
        }
    }

    /** 构造当前配置中的持久 Skill 条目，测试只关心名称授权合并。 */
    private static ConfigurationGenerationSnapshot.Skill configured(
            String skillId, String name, boolean enabled, String description) {
        return new ConfigurationGenerationSnapshot.Skill(skillId, name, "user", enabled, description);
    }

    /** 用最窄动态投影冻结 Skill 定义与信任，任何额外配置读取都视为越界。 */
    private static ConfigurationGenerationSnapshot snapshot(
            boolean trusted, List<ConfigurationGenerationSnapshot.Skill> configured) {
        return (ConfigurationGenerationSnapshot) Proxy.newProxyInstance(
                GenerationCatalogSkillDiscoveryTest.class.getClassLoader(),
                new Class<?>[]{ConfigurationGenerationSnapshot.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "skillDefinitions" -> configured;
                    case "trusted" -> trusted;
                    default -> throw new AssertionError("unexpected generation call: " + method.getName());
                });
    }

    /** 配置租约只发布冻结投影；本目录查询禁止触达 Secret。 */
    private static ConfigurationGenerationPort.Lease lease(ConfigurationGenerationSnapshot snapshot) {
        return new ConfigurationGenerationPort.Lease() {
            /** 本测试不依赖代际身份。 */
            @Override public String generationId() { return "generation_fixture"; }
            /** 返回同一冻结配置投影。 */
            @Override public ConfigurationGenerationSnapshot snapshot() { return snapshot; }
            /** Skill 列表绝不能解析 Secret。 */
            @Override public String secretFor(String credentialId) {
                throw new AssertionError("unexpected secret lookup");
            }
            /** 调用方拥有租约；本测试无需分配外部资源。 */
            @Override public void close() { }
        };
    }

    /** 返回四来源元数据夹具，并按请求信任开关模拟 JaSkillSources 的 project 关闭语义。 */
    private static final class RecordingSkillCatalog implements SkillCatalog {
        private final AtomicReference<DiscoveryRequest> lastRequest = new AtomicReference<>();

        /** 记录真实请求根并只在可信项目上下文中加入 WORKSPACE 项。 */
        @Override
        public Catalog discover(DiscoveryRequest request) {
            lastRequest.set(request);
            List<SkillCatalog.SkillDescriptor> values = List.of(
                    discovered("builtin-tool", Source.BUNDLED),
                    discovered("agents-tool", Source.AGENTS_USER),
                    discovered("ja-tool", Source.JA_USER),
                    discovered("project-tool", Source.WORKSPACE)).stream()
                    .filter(skill -> request.workspaceTrusted() || skill.source() != Source.WORKSPACE)
                    .toList();
            return new Catalog(values);
        }

        /** 未登记授权的列表仍必须扫描通用来源，因此本测试拒绝 empty 快捷路径。 */
        @Override public Catalog emptyCatalog() { throw unsupported(); }
        /** Settings 不选择 Turn Skill。 */
        @Override public Catalog select(Catalog catalog, List<String> allowedNames) {
            throw unsupported();
        }
        /** Settings 不读取 Skill 正文。 */
        @Override public SkillDocument read(Catalog catalog, SkillReadRequest request) {
            throw unsupported();
        }

        /** 创建只含公开元数据的发现项，未登记 skillId 仍由稳定名称派生。 */
        private static SkillCatalog.SkillDescriptor discovered(String name, Source source) {
            return new SkillCatalog.SkillDescriptor(
                    name, "来自 " + source.name().toLowerCase() + " 的描述", source);
        }

        /** 所有非列表能力都必须保持不可达。 */
        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("unexpected Skill catalog operation");
        }
    }
}
