// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.catalog.infrastructure.mcp.generation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.workspace.infrastructure.filesystem.WorkspaceBoundaryFactoryAdapter;
import io.github.kongweiguang.ja.catalog.infrastructure.mcp.support.McpLimits;
import io.github.kongweiguang.ja.catalog.domain.model.SkillDescriptor;
import io.github.kongweiguang.ja.catalog.application.gateway.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.catalog.infrastructure.skills.JaSkillCatalog;
import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationGenerationSnapshot;
import io.github.kongweiguang.ja.configuration.domain.model.SkillReference;
import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
/** 验证 Settings 目录复用 Skill 元数据发现，并把发现与持久停用保持为两个独立事实。 */
final class GenerationCatalogSkillDiscoveryTest {
    @TempDir
    private Path temporary;
    /**
     * 可管理来源才映射到 Settings；未登记项默认开启，停用必须同时匹配来源和名称。
     */
    @Test
    void mapsAvailableDiscoveryScopesAndMergesConfiguredAuthorizationByReference() throws Exception {
        Path home = temporary.resolve("ja-home").toAbsolutePath().normalize();
        Path agents = temporary.resolve("agents-skills").toAbsolutePath().normalize();
        Path ja = temporary.resolve("ja-skills").toAbsolutePath().normalize();
        Path project = temporary.resolve("project").toAbsolutePath().normalize();
        writeSkill(agents, "agents-tool", "User Skill");
        writeSkill(ja, "ja-tool", "Ja Skill");
        writeSkill(project.resolve(".agents").resolve("skills"), "project-tool", "Project Skill");
        JaSkillCatalog skills = new JaSkillCatalog(agents, ja);
        ConfigurationGenerationSnapshot snapshot = snapshot(true, List.of(
                configured("ja:ja-tool"), configured("user:missing-tool")));
        try (GenerationCatalog catalog = new GenerationCatalog(home, new ObjectMapper(), McpLimits.DEFAULT, skills, new WorkspaceBoundaryFactoryAdapter())) {
            CursorPage<SkillDescriptor> page = catalog.listSkills(lease(snapshot), project, true, null, 200);
            Map<String, SkillDescriptor> byName = page.items().stream()
                    .collect(Collectors.toMap(SkillDescriptor::name, Function.identity()));
            assertEquals(Map.of("agents-tool", "user", "ja-tool", "ja", "project-tool", "project"),
                    byName.entrySet().stream().collect(Collectors.toMap(
                            Map.Entry::getKey, entry -> entry.getValue().scope())));
            assertFalse(byName.containsKey("builtin-tool"));
            assertEquals("ja:ja-tool", byName.get("ja-tool").skillId());
            assertFalse(byName.get("ja-tool").enabled());
            assertEquals("user:agents-tool", byName.get("agents-tool").skillId());
            assertTrue(byName.get("agents-tool").enabled());
            assertEquals("project:project-tool", byName.get("project-tool").skillId());
            assertTrue(byName.get("project-tool").enabled());
            assertFalse(byName.containsKey("missing-tool"));
            assertTrue(page.items().stream().allMatch(item -> item.status().equals("healthy")));
        }
    }
    /** 省略 workspace 时仍发现通用来源，但明确不向 Skill adapter 开放 project 来源。 */
    @Test
    void omitsProjectSourceForGeneralSettingsCatalog() throws Exception {
        Path home = temporary.resolve("general-home").toAbsolutePath().normalize();
        Path agents = temporary.resolve("agents");
        Path ja = temporary.resolve("ja");
        writeSkill(agents, "agents-tool", "User Skill");
        writeSkill(ja, "ja-tool", "Ja Skill");
        JaSkillCatalog skills = new JaSkillCatalog(agents, ja);
        ConfigurationGenerationSnapshot snapshot = snapshot(true, List.of());
        try (GenerationCatalog catalog = new GenerationCatalog(home, new ObjectMapper(), McpLimits.DEFAULT, skills, new WorkspaceBoundaryFactoryAdapter())) {
            CursorPage<SkillDescriptor> page = catalog.listSkills(lease(snapshot), null, false, null, 200);
            assertEquals(List.of("agents-tool", "ja-tool"),
                    page.items().stream().map(SkillDescriptor::name).sorted().toList());
        }
    }
    /** Workspace owner 未授权时即使配置代际仍为 trusted，也必须 fail closed 排除 project 来源。 */
    @Test
    void omitsProjectSourceWhenWorkspaceOwnerIsUntrusted() throws Exception {
        Path home = temporary.resolve("untrusted-home").toAbsolutePath().normalize();
        Path project = temporary.resolve("untrusted-project").toAbsolutePath().normalize();
        Path agents = temporary.resolve("agents-untrusted");
        Path ja = temporary.resolve("ja-untrusted");
        writeSkill(project.resolve(".agents").resolve("skills"), "project-tool", "Project Skill");
        JaSkillCatalog skills = new JaSkillCatalog(agents, ja);
        try (GenerationCatalog catalog = new GenerationCatalog(home, new ObjectMapper(), McpLimits.DEFAULT, skills, new WorkspaceBoundaryFactoryAdapter())) {
            CursorPage<SkillDescriptor> page = catalog.listSkills(
                    lease(snapshot(true, List.of())), project, false, null, 200);
            assertFalse(page.items().stream().anyMatch(item -> item.scope().equals("project")));
        }
    }
    /** 构造来源限定授权，测试不会再保存描述或布尔开关副本。 */
    private static ConfigurationGenerationSnapshot.Skill configured(String reference) {
        return new ConfigurationGenerationSnapshot.Skill(SkillReference.parse(reference));
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
    /** 生成最小 frontmatter 包，保证目录测试走生产 local source 的真实发现路径。 */
    private static void writeSkill(Path root, String name, String description) throws Exception {
        Path skillDirectory = Files.createDirectories(root.resolve(name));
        Files.writeString(skillDirectory.resolve("SKILL.md"), "---\nname: " + name
                + "\ndescription: " + description + "\n---\n# " + name + "\n");
    }
}
