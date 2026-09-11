// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.skills;

import io.github.kongweiguang.ja.conversation.port.out.SkillCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Pi 式 Skill 渐进披露边界，测试夹具不得引入第三方 Agent 类型。 */
final class JaSkillSourcesTest {
    @TempDir
    Path temporary;

    /** 发现只固定元数据和覆盖结果；同一 Catalog 后续 read 必须看到实时 SKILL.md 正文。 */
    @Test
    void keepsMetadataStableWhileReadingCurrentSkillDocument() throws Exception {
        Path user = Files.createDirectories(temporary.resolve("user"));
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        writeSkill(user, "coding", "User coding.", "user body", null, null);
        Path workspaceSkill = writeSkill(workspace.resolve(".agents/skills"), "coding",
                "Workspace coding.", "first workspace body", null, null);
        writeSkill(user, "review", "Review changes.", "review body", null, null);

        JaSkillSources catalog = new JaSkillSources();
        SkillCatalog.Catalog discovered = catalog.discover(request(workspace, user));

        assertEquals(List.of("coding", "review"), discovered.skills().stream()
                .map(SkillCatalog.SkillDescriptor::name).toList());
        assertEquals(SkillCatalog.Source.WORKSPACE, descriptor(discovered, "coding").source());
        assertEquals("Workspace coding.", descriptor(discovered, "coding").description());
        assertEquals("first workspace body",
                read(catalog, discovered, "coding", "SKILL.md", 100).content());
        assertFalse(discovered.skills().toString().contains("workspace body"));

        writeDocument(workspaceSkill, "coding", "Changed metadata.", "second workspace body");

        assertEquals("Workspace coding.", descriptor(discovered, "coding").description());
        assertEquals("second workspace body",
                read(catalog, discovered, "coding", "SKILL.md", 100).content());
    }

    /** 辅助资源不在发现或首次读取时冻结，修改与新增文件都应在下一次 read 生效。 */
    @Test
    void readsAuxiliaryResourcesLiveFromOriginalLocator() throws Exception {
        Path user = Files.createDirectories(temporary.resolve("user"));
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path skill = writeSkill(user, "review", "Review.", "body", "notes/checklist.txt", "first");
        JaSkillSources catalog = new JaSkillSources();
        SkillCatalog.Catalog discovered = catalog.discover(request(workspace, user));

        assertEquals("first", read(catalog, discovered, "review", "notes/checklist.txt", 100).content());
        Files.writeString(skill.resolve("notes/checklist.txt"), "second", StandardCharsets.UTF_8);
        Files.writeString(skill.resolve("notes/new.txt"), "created later", StandardCharsets.UTF_8);

        assertEquals("second", read(catalog, discovered, "review", "notes/checklist.txt", 100).content());
        assertEquals("created later", read(catalog, discovered, "review", "notes/new.txt", 100).content());
    }

    /** 发现不得递归扫描完整包；超大辅助文件只在它实际被 read 时触发大小拒绝。 */
    @Test
    void discoveryDoesNotMaterializeOversizedAuxiliaryFiles() throws Exception {
        Path user = Files.createDirectories(temporary.resolve("user"));
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path skill = writeSkill(user, "large-assets", "Large assets.", "small body", null, null);
        Files.writeString(skill.resolve("huge.txt"),
                "x".repeat(JaSkillSources.MAX_FILE_BYTES + 1), StandardCharsets.UTF_8);

        JaSkillSources catalog = new JaSkillSources();
        SkillCatalog.Catalog discovered = catalog.discover(request(workspace, user));

        assertEquals(List.of("large-assets"), discovered.skills().stream()
                .map(SkillCatalog.SkillDescriptor::name).toList());
        assertEquals("small body", read(catalog, discovered, "large-assets", "SKILL.md", 100).content());
        assertThrows(UncheckedIOException.class,
                () -> read(catalog, discovered, "large-assets", "huge.txt", 100));
    }

    /** 文件来源按高优先级到低优先级稳定展示，空内置来源不补入默认 Skill。 */
    @Test
    void resolvesFilesystemSourcesByPriorityThenName() throws Exception {
        Path agents = Files.createDirectories(temporary.resolve("agents-skills"));
        Path ja = Files.createDirectories(temporary.resolve("ja-skills"));
        Path workspace = Files.createDirectories(temporary.resolve("project"));
        Files.createDirectory(workspace.resolve(".git"));
        writeSkill(agents, "shared", "Agents shared.", "agents", null, null);
        writeSkill(agents, "user-only", "Agents only.", "agents only", null, null);
        writeSkill(ja, "shared", "Ja shared.", "ja", null, null);
        writeSkill(ja, "ja-only", "Ja only.", "ja only", null, null);
        writeSkill(workspace.resolve(".agents/skills"), "workspace-only",
                "Workspace only.", "workspace", null, null);

        JaSkillSources catalog = new JaSkillSources();
        SkillCatalog.Catalog discovered = catalog.discover(request(workspace, agents, ja, true));

        assertEquals(List.of("workspace-only", "ja-only", "shared", "user-only"),
                discovered.skills().stream().map(SkillCatalog.SkillDescriptor::name).toList());
        assertEquals(SkillCatalog.Source.JA_USER, descriptor(discovered, "shared").source());
        assertEquals("ja", read(catalog, discovered, "shared", "SKILL.md", 100).content());
    }

    /** 工作区信任为 false 时完全跳过项目来源，损坏项目包也不能影响用户 Skill。 */
    @Test
    void skipsWorkspaceSourcesWhenWorkspaceIsUntrusted() throws Exception {
        Path agents = temporary.resolve("absent-agents");
        Path ja = Files.createDirectories(temporary.resolve("trusted-ja"));
        Path workspace = Files.createDirectories(temporary.resolve("untrusted-project"));
        Files.createDirectory(workspace.resolve(".git"));
        writeSkill(ja, "safe", "Safe user skill.", "user", null, null);
        Path invalid = Files.createDirectories(workspace.resolve(".agents/skills/safe"));
        Files.writeString(invalid.resolve("SKILL.md"), "not frontmatter", StandardCharsets.UTF_8);

        JaSkillSources catalog = new JaSkillSources();
        SkillCatalog.Catalog discovered = catalog.discover(request(workspace, agents, ja, false));

        assertEquals(SkillCatalog.Source.JA_USER, descriptor(discovered, "safe").source());
        assertEquals("user", read(catalog, discovered, "safe", "SKILL.md", 100).content());
    }

    /** 项目 Skill 从 Git 根到 cwd 逐层整包覆盖，而不是只读取仓库根目录。 */
    @Test
    void resolvesWorkspaceSkillsFromGitRootToCurrentDirectory() throws Exception {
        Path agents = temporary.resolve("absent-agents");
        Path ja = temporary.resolve("absent-ja");
        Path gitRoot = Files.createDirectories(temporary.resolve("nested-project"));
        Files.createDirectory(gitRoot.resolve(".git"));
        Path child = Files.createDirectories(gitRoot.resolve("module/deep"));
        writeSkill(gitRoot.resolve(".agents/skills"), "scoped", "Root scope.", "root", null, null);
        writeSkill(gitRoot.resolve("module/.agents/skills"), "scoped",
                "Module scope.", "module", null, null);
        writeSkill(child.resolve(".agents/skills"), "scoped", "Deep scope.", "deep", null, null);

        JaSkillSources catalog = new JaSkillSources();
        SkillCatalog.Catalog discovered = catalog.discover(request(child, agents, ja, true));

        assertEquals(SkillCatalog.Source.WORKSPACE, descriptor(discovered, "scoped").source());
        assertEquals("deep", read(catalog, discovered, "scoped", "SKILL.md", 100).content());
    }

    /** select 按名称缩小 locator 集，且同值伪造 Catalog 不能获得原目录的读取权限。 */
    @Test
    void selectsByNameAndRejectsForgedCatalogs() throws Exception {
        Path user = Files.createDirectories(temporary.resolve("user"));
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        writeSkill(user, "coding", "Coding.", "coding body", null, null);
        Path review = writeSkill(user, "review", "Review.", "first review", null, null);
        JaSkillSources catalog = new JaSkillSources();
        SkillCatalog.Catalog complete = catalog.discover(request(workspace, user));

        SkillCatalog.Catalog filtered = catalog.select(complete, List.of("review"));
        assertEquals(List.of("review"), filtered.skills().stream()
                .map(SkillCatalog.SkillDescriptor::name).toList());
        assertThrows(IllegalArgumentException.class,
                () -> read(catalog, filtered, "coding", "SKILL.md", 100));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.select(complete, List.of("missing")));

        writeDocument(review, "review", "Review.", "second review");
        assertEquals("second review", read(catalog, filtered, "review", "SKILL.md", 100).content());

        SkillCatalog.Catalog forged = new SkillCatalog.Catalog(complete.skills());
        assertThrows(IllegalArgumentException.class,
                () -> read(catalog, forged, "review", "SKILL.md", 100));
    }

    /** 空目录与空选择都不扫描来源，并保持读取边界关闭。 */
    @Test
    void createsEmptyCatalogWithoutSourceIo() throws Exception {
        Path invalidSource = Files.writeString(temporary.resolve("not-directory"), "ignored",
                StandardCharsets.UTF_8);
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        JaSkillSources catalog = new JaSkillSources();

        SkillCatalog.Catalog empty = catalog.emptyCatalog();

        assertTrue(empty.skills().isEmpty());
        assertTrue(catalog.select(empty, List.of()).skills().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> read(catalog, empty, "coding", "SKILL.md", 100));
        assertTrue(Files.isRegularFile(invalidSource));
    }

    /** 未知、重复、空 block、缺失必填字段、BOM 与非法 UTF-8 frontmatter 都不能进入目录。 */
    @Test
    void rejectsUnsafeOrInvalidFrontmatterDuringDiscovery() throws Exception {
        List<String> invalid = List.of(
                "---\nname: bad\ndescription: Bad.\nunknown: 1\n---\nbody",
                "---\nname: bad\nname: bad\ndescription: Bad.\n---\nbody",
                "---\nname: bad\ndescription: |\n---\nbody",
                "---\nname: bad\n---\nbody");
        for (int index = 0; index < invalid.size(); index++) {
            Path root = Files.createDirectories(temporary.resolve("invalid-" + index));
            Path skill = Files.createDirectories(root.resolve("bad"));
            Files.writeString(skill.resolve("SKILL.md"), invalid.get(index), StandardCharsets.UTF_8);
            assertDiscoveryRejected(root);
        }

        Path bomRoot = Files.createDirectories(temporary.resolve("bom-user"));
        Path bom = Files.createDirectories(bomRoot.resolve("bad")).resolve("SKILL.md");
        byte[] valid = "---\nname: bad\ndescription: Bad.\n---\nbody".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[valid.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(valid, 0, withBom, 3, valid.length);
        Files.write(bom, withBom);
        assertDiscoveryRejected(bomRoot);

        Path malformedRoot = Files.createDirectories(temporary.resolve("malformed-user"));
        Path malformed = Files.createDirectories(malformedRoot.resolve("bad")).resolve("SKILL.md");
        Files.write(malformed, new byte[] {(byte) 0xC3, (byte) 0x28});
        assertDiscoveryRejected(malformedRoot);
    }

    /** 标准可选 frontmatter 可发现，但 allowed-tools 不进入公开 descriptor 或授权边界。 */
    @Test
    void acceptsStandardOptionalFrontmatterWithoutGrantingTools() throws Exception {
        Path ja = Files.createDirectories(temporary.resolve("optional-ja"));
        Path skill = Files.createDirectories(ja.resolve("standard"));
        String document = """
                ---
                name: standard
                description: >
                  Standard skill metadata.
                version: 1.2.3
                license: Apache-2.0
                compatibility: Requires Git.
                metadata:
                  author: example
                  version: "1"
                allowed-tools: Bash(git:*) Read
                ---
                body
                """;
        Files.writeString(skill.resolve("SKILL.md"), document, StandardCharsets.UTF_8);

        JaSkillSources catalog = new JaSkillSources();
        SkillCatalog.Catalog discovered = catalog.discover(request(
                Files.createDirectories(temporary.resolve("optional-workspace")), ja));
        SkillCatalog.SkillDescriptor descriptor = descriptor(discovered, "standard");

        assertEquals("Standard skill metadata.", descriptor.description());
        assertFalse(descriptor.toString().contains("allowed-tools"));
        assertEquals("body\n", read(catalog, discovered, "standard", "SKILL.md", 100).content());
    }

    /** Windows checkout 的 CRLF 在 UTF-8 规范化前也必须关闭 frontmatter，避免 JVM 与 Native
     * 只因资源复制保留平台换行而产生不同的 Skill 目录。 */
    @Test
    void discoversCrLfSkillDocumentsBeforeLineEndingNormalization() throws Exception {
        Path user = Files.createDirectories(temporary.resolve("crlf-user"));
        Path workspace = Files.createDirectories(temporary.resolve("crlf-workspace"));
        Path skill = Files.createDirectories(user.resolve("windows-lines"));
        Files.writeString(skill.resolve("SKILL.md"),
                "---\r\nname: windows-lines\r\ndescription: Windows lines.\r\n---\r\nbody\r\n",
                StandardCharsets.UTF_8);

        JaSkillSources catalog = new JaSkillSources();
        SkillCatalog.Catalog discovered = catalog.discover(request(workspace, user));

        assertEquals("Windows lines.", descriptor(discovered, "windows-lines").description());
        assertEquals("body\n", read(catalog, discovered, "windows-lines", "SKILL.md", 100).content());
    }

    /** 读取拒绝父级、别名、绝对路径与过深路径，并在 Unicode 安全边界截断。 */
    @Test
    void rejectsEscapingOrAmbiguousResourcePaths() throws Exception {
        Path user = Files.createDirectories(temporary.resolve("user"));
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        writeSkill(user, "safe", "Safe skill.", "body", "notes/a.txt", "A😀B");
        JaSkillSources catalog = new JaSkillSources();
        SkillCatalog.Catalog discovered = catalog.discover(request(workspace, user));

        assertThrows(IllegalArgumentException.class,
                () -> new SkillCatalog.SkillReadRequest("safe", "../outside", 100));
        assertThrows(IllegalArgumentException.class,
                () -> read(catalog, discovered, "safe", "notes/./a.txt", 100));
        assertThrows(IllegalArgumentException.class,
                () -> read(catalog, discovered, "safe", "notes//a.txt", 100));
        assertThrows(IllegalArgumentException.class,
                () -> read(catalog, discovered, "safe", "C:/outside.txt", 100));
        assertThrows(IllegalArgumentException.class,
                () -> read(catalog, discovered, "safe", "a/b/c/d/e/f/g/h/i.txt", 100));
        SkillCatalog.SkillDocument truncated = read(catalog, discovered, "safe", "notes/a.txt", 2);
        assertEquals("A", truncated.content());
        assertTrue(truncated.truncated());
    }

    /** 实时读取继续失败关闭超大文件和非法 UTF-8，不因发现成功而信任后续替换内容。 */
    @Test
    void failsClosedOnLiveSizeAndUtf8Violations() throws Exception {
        Path user = Files.createDirectories(temporary.resolve("user"));
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path skill = writeSkill(user, "mutable", "Mutable.", "body", "notes/value.txt", "valid");
        JaSkillSources catalog = new JaSkillSources();
        SkillCatalog.Catalog discovered = catalog.discover(request(workspace, user));

        Files.writeString(skill.resolve("notes/value.txt"),
                "x".repeat(JaSkillSources.MAX_FILE_BYTES + 1), StandardCharsets.UTF_8);
        assertThrows(UncheckedIOException.class,
                () -> read(catalog, discovered, "mutable", "notes/value.txt", 100));

        Files.write(skill.resolve("notes/value.txt"), new byte[] {(byte) 0xC3, (byte) 0x28});
        assertThrows(UncheckedIOException.class,
                () -> read(catalog, discovered, "mutable", "notes/value.txt", 100));
    }

    /** 发现后新增的符号链接资源仍在 read 时拒绝，不能借实时读取逃逸来源根。 */
    @Test
    void rejectsSymbolicLinksAtReadTimeWhenPlatformPermitsCreation() throws Exception {
        Path user = Files.createDirectories(temporary.resolve("user"));
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path skill = writeSkill(user, "linked", "Linked skill.", "body", null, null);
        Path outside = Files.writeString(temporary.resolve("outside.txt"), "outside", StandardCharsets.UTF_8);
        JaSkillSources catalog = new JaSkillSources();
        SkillCatalog.Catalog discovered = catalog.discover(request(workspace, user));
        try {
            Files.createSymbolicLink(skill.resolve("alias.txt"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            return;
        }

        assertThrows(UncheckedIOException.class,
                () -> read(catalog, discovered, "linked", "alias.txt", 100));
    }

    /** Windows junction 属于重解析点，即使在发现后创建且目标可读也必须在 read 时拒绝。 */
    @Test
    void rejectsWindowsJunctionsAtReadTimeWhenPlatformPermitsCreation() throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            return;
        }
        Path user = Files.createDirectories(temporary.resolve("user"));
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path skill = writeSkill(user, "junction", "Junction skill.", "body", null, null);
        Path outside = Files.createDirectories(temporary.resolve("junction-target"));
        Files.writeString(outside.resolve("outside.txt"), "outside", StandardCharsets.UTF_8);
        JaSkillSources catalog = new JaSkillSources();
        SkillCatalog.Catalog discovered = catalog.discover(request(workspace, user));
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                skill.resolve("alias").toString(), outside.toString()).redirectErrorStream(true).start();
        if (process.waitFor() != 0) {
            return;
        }

        assertThrows(UncheckedIOException.class,
                () -> read(catalog, discovered, "junction", "alias/outside.txt", 100));
    }

    /** 没有用户或项目 Skill 时目录必须为空，显式读取也不能恢复已移除的默认 coding。 */
    @Test
    void discoversNoDefaultSkillsWithoutFilesystemPackages() throws Exception {
        Path user = temporary.resolve("absent-user");
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        JaSkillSources catalog = new JaSkillSources();
        SkillCatalog.Catalog discovered = catalog.discover(request(workspace, user));

        assertTrue(discovered.skills().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> read(catalog, discovered, "coding", "SKILL.md", 2_000));
    }

    /** 创建公开 Kernel API 所要求的绝对发现请求，避免测试夹具隐含路径语义。 */
    private static SkillCatalog.DiscoveryRequest request(Path workspace, Path user) {
        return new SkillCatalog.DiscoveryRequest(
                workspace.toAbsolutePath().normalize(),
                user.resolveSibling(user.getFileName() + "-agents-absent").toAbsolutePath().normalize(),
                user.toAbsolutePath().normalize(), true);
    }

    /** 构造显式四来源请求，测试不会依赖进程 user.home 或真实 Ja home。 */
    private static SkillCatalog.DiscoveryRequest request(
            Path workspace, Path agents, Path ja, boolean trusted) {
        return new SkillCatalog.DiscoveryRequest(
                workspace.toAbsolutePath().normalize(), agents.toAbsolutePath().normalize(),
                ja.toAbsolutePath().normalize(), trusted);
    }

    /** 按身份定位描述对象，避免断言依赖排序后的列表下标。 */
    private static SkillCatalog.SkillDescriptor descriptor(SkillCatalog.Catalog catalog, String name) {
        return catalog.skills().stream().filter(skill -> name.equals(skill.name())).findFirst().orElseThrow();
    }

    /** 通过紧凑夹具调用公开实时读取端口，保持测试贴近真实边界。 */
    private static SkillCatalog.SkillDocument read(JaSkillSources catalog, SkillCatalog.Catalog discovered,
            String skill, String path, int limit) {
        return catalog.read(discovered, new SkillCatalog.SkillReadRequest(skill, path, limit));
    }

    /** 仅使用当前字段写入合法包及可选嵌套资源，避免夹具携带兼容数据。 */
    private static Path writeSkill(Path root, String name, String description, String body,
            String resourcePath, String resource) throws Exception {
        Path skill = Files.createDirectories(root.resolve(name));
        writeDocument(skill, name, description, body);
        if (resourcePath != null) {
            Path target = skill.resolve(resourcePath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, resource, StandardCharsets.UTF_8);
        }
        return skill;
    }

    /** 只替换主文档，以隔离并验证发现元数据与实时正文的不同生命周期。 */
    private static void writeDocument(Path skill, String name, String description, String body) throws Exception {
        Files.writeString(skill.resolve("SKILL.md"), "---\nname: " + name + "\ndescription: "
                + description + "\n---\n" + body, StandardCharsets.UTF_8);
    }

    /** 断言非法 frontmatter 不进入可用目录，同时保留同源其它合法条目。 */
    private void assertDiscoveryRejected(Path userRoot) throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace-" + userRoot.getFileName()));
        JaSkillSources catalog = new JaSkillSources();
        SkillCatalog.Catalog discovered = catalog.discover(request(workspace, userRoot));
        assertTrue(discovered.skills().stream()
                .noneMatch(item -> item.source() == SkillCatalog.Source.JA_USER));
    }
}
