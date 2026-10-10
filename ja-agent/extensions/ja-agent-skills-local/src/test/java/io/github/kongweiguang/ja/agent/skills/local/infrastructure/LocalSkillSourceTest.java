// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.skills.local.infrastructure;

import io.github.kongweiguang.ja.agent.application.skill.SkillCatalog;
import io.github.kongweiguang.ja.agent.domain.skill.SkillDescriptor;
import io.github.kongweiguang.ja.agent.domain.skill.SkillDocument;
import io.github.kongweiguang.ja.agent.domain.skill.SkillReadRequest;
import io.github.kongweiguang.ja.agent.domain.skill.SkillRequestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Pi 式 Skill 渐进披露边界，测试夹具不得引入第三方 Agent 类型。 */
final class LocalSkillSourceTest {
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

        TestSkillCatalog catalog = new TestSkillCatalog();
        SkillCatalog.Catalog discovered = catalog.discover(request(workspace, user));

        assertEquals(List.of("coding", "review", "ja-docs"), discovered.skills().stream()
                .map(SkillDescriptor::name).toList());
        assertEquals("project", descriptor(discovered, "coding").sourceId());
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
        TestSkillCatalog catalog = new TestSkillCatalog();
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
                "x".repeat(LocalSkillSource.MAX_FILE_BYTES + 1), StandardCharsets.UTF_8);

        TestSkillCatalog catalog = new TestSkillCatalog();
        SkillCatalog.Catalog discovered = catalog.discover(request(workspace, user));

        assertEquals(List.of("large-assets", "ja-docs"), discovered.skills().stream()
                .map(SkillDescriptor::name).toList());
        assertEquals("small body", read(catalog, discovered, "large-assets", "SKILL.md", 100).content());
        assertThrows(UncheckedIOException.class,
                () -> read(catalog, discovered, "large-assets", "huge.txt", 100));
    }

    /** 文件来源按高优先级到低优先级稳定展示，内置说明排在其后。 */
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

        TestSkillCatalog catalog = new TestSkillCatalog();
        SkillCatalog.Catalog discovered = catalog.discover(request(workspace, agents, ja, true));

        assertEquals(List.of("workspace-only", "ja-only", "shared", "user-only", "ja-docs"),
                discovered.skills().stream().map(SkillDescriptor::name).toList());
        assertEquals("ja", descriptor(discovered, "shared").sourceId());
        assertEquals("ja", read(catalog, discovered, "shared", "SKILL.md", 100).content());
    }

    /** 停用项目同名包后用户包接管，管理目录仍同时显示两个来源。 */
    @Test
    void disabledProjectSourceFallsBackToEnabledUserSource() throws Exception {
        Path agents = Files.createDirectories(temporary.resolve("fallback-user"));
        Path workspace = Files.createDirectories(temporary.resolve("fallback-project"));
        writeSkill(agents, "review", "User review.", "user body", null, null);
        writeSkill(workspace.resolve(".agents/skills"), "review", "Project review.", "project body", null, null);
        TestSkillCatalog sources = new TestSkillCatalog();
        DiscoveryRequest request = request(workspace, agents,
                temporary.resolve("fallback-ja-absent"), true);

        assertEquals(2, sources.discoverAll(request).stream()
                .filter(skill -> skill.name().equals("review")).count());
        SkillCatalog.Catalog effective = sources.discover(request, Set.of("project:review"));
        assertEquals("user", descriptor(effective, "review").sourceId());
        assertEquals("user body", read(sources, effective, "review", "SKILL.md", 100).content());
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

        TestSkillCatalog catalog = new TestSkillCatalog();
        SkillCatalog.Catalog discovered = catalog.discover(request(workspace, agents, ja, false));

        assertEquals("ja", descriptor(discovered, "safe").sourceId());
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

        TestSkillCatalog catalog = new TestSkillCatalog();
        SkillCatalog.Catalog discovered = catalog.discover(request(child, agents, ja, true));

        assertEquals("project", descriptor(discovered, "scoped").sourceId());
        assertEquals("deep", read(catalog, discovered, "scoped", "SKILL.md", 100).content());
    }

    /** 校验共享来源对象不会让另一请求上下文复用前一请求发现的文件定位。 */
    @Test
    void rejectsDescriptorsFromDifferentRequestContexts() throws Exception {
        Path user = Files.createDirectories(temporary.resolve("context-user"));
        writeSkill(user, "review", "Review.", "review body", null, null);
        LocalSkillSource source = LocalSkillSource.directory("user", 1, "skill-root");
        SkillRequestContext firstContext = new SkillRequestContext("test-tenant",
                java.util.Map.of("skill-root", user.toString()));
        SkillRequestContext secondContext = new SkillRequestContext("test-tenant",
                java.util.Map.of("skill-root", user.toString()));

        SkillDescriptor descriptor = source.discover(firstContext, null, 10).skills().getFirst();

        assertEquals("review body", source.read(firstContext, descriptor,
                new SkillReadRequest("review", "SKILL.md", 100)).content());
        assertThrows(IllegalArgumentException.class, () -> source.read(secondContext, descriptor,
                new SkillReadRequest("review", "SKILL.md", 100)));
    }

    /** select 按名称缩小 locator 集，且同值伪造 Catalog 不能获得原目录的读取权限。 */
    @Test
    void selectsByNameAndRejectsForgedCatalogs() throws Exception {
        Path user = Files.createDirectories(temporary.resolve("user"));
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        writeSkill(user, "coding", "Coding.", "coding body", null, null);
        Path review = writeSkill(user, "review", "Review.", "first review", null, null);
        TestSkillCatalog catalog = new TestSkillCatalog();
        SkillCatalog.Catalog complete = catalog.discover(request(workspace, user));

        SkillCatalog.Catalog filtered = catalog.select(complete, List.of("review"));
        assertEquals(List.of("review"), filtered.skills().stream()
                .map(SkillDescriptor::name).toList());
        assertThrows(IllegalArgumentException.class,
                () -> read(catalog, filtered, "coding", "SKILL.md", 100));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.select(complete, List.of("missing")));

        writeDocument(review, "review", "Review.", "second review");
        assertEquals("second review", read(catalog, filtered, "review", "SKILL.md", 100).content());

        SkillCatalog.Catalog forged = new TestSkillCatalog().discover(request(workspace, user));
        assertThrows(IllegalArgumentException.class,
                () -> read(catalog, forged, "review", "SKILL.md", 100));
    }

    /** 空目录与空选择都不扫描来源，并保持读取边界关闭。 */
    @Test
    void createsEmptyCatalogWithoutSourceIo() throws Exception {
        Path invalidSource = Files.writeString(temporary.resolve("not-directory"), "ignored",
                StandardCharsets.UTF_8);
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        TestSkillCatalog catalog = new TestSkillCatalog();

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

        TestSkillCatalog catalog = new TestSkillCatalog();
        SkillCatalog.Catalog discovered = catalog.discover(request(
                Files.createDirectories(temporary.resolve("optional-workspace")), ja));
        SkillDescriptor descriptor = descriptor(discovered, "standard");

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

        TestSkillCatalog catalog = new TestSkillCatalog();
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
        TestSkillCatalog catalog = new TestSkillCatalog();
        SkillCatalog.Catalog discovered = catalog.discover(request(workspace, user));

        assertThrows(IllegalArgumentException.class,
                () -> new SkillReadRequest("safe", "../outside", 100));
        assertThrows(IllegalArgumentException.class,
                () -> read(catalog, discovered, "safe", "notes/./a.txt", 100));
        assertThrows(IllegalArgumentException.class,
                () -> read(catalog, discovered, "safe", "notes//a.txt", 100));
        assertThrows(IllegalArgumentException.class,
                () -> read(catalog, discovered, "safe", "C:/outside.txt", 100));
        assertThrows(IllegalArgumentException.class,
                () -> read(catalog, discovered, "safe", "a/b/c/d/e/f/g/h/i.txt", 100));
        SkillDocument truncated = read(catalog, discovered, "safe", "notes/a.txt", 2);
        assertEquals("A", truncated.content());
        assertTrue(truncated.truncated());
    }

    /** 实时读取继续失败关闭超大文件和非法 UTF-8，不因发现成功而信任后续替换内容。 */
    @Test
    void failsClosedOnLiveSizeAndUtf8Violations() throws Exception {
        Path user = Files.createDirectories(temporary.resolve("user"));
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path skill = writeSkill(user, "mutable", "Mutable.", "body", "notes/value.txt", "valid");
        TestSkillCatalog catalog = new TestSkillCatalog();
        SkillCatalog.Catalog discovered = catalog.discover(request(workspace, user));

        Files.writeString(skill.resolve("notes/value.txt"),
                "x".repeat(LocalSkillSource.MAX_FILE_BYTES + 1), StandardCharsets.UTF_8);
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
        TestSkillCatalog catalog = new TestSkillCatalog();
        SkillCatalog.Catalog discovered = catalog.discover(request(workspace, user));
        try {
            Files.createSymbolicLink(skill.resolve("alias.txt"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links are unavailable in this environment");
            return;
        }

        assertThrows(UncheckedIOException.class,
                () -> read(catalog, discovered, "linked", "alias.txt", 100));
    }

    /** Windows junction 属于重解析点，即使在发现后创建且目标可读也必须在 read 时拒绝。 */
    @Test
    void rejectsWindowsJunctionsAtReadTimeWhenPlatformPermitsCreation() throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Windows junction validation requires Windows");
            return;
        }
        Path user = Files.createDirectories(temporary.resolve("user"));
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path skill = writeSkill(user, "junction", "Junction skill.", "body", null, null);
        Path outside = Files.createDirectories(temporary.resolve("junction-target"));
        Files.writeString(outside.resolve("outside.txt"), "outside", StandardCharsets.UTF_8);
        TestSkillCatalog catalog = new TestSkillCatalog();
        SkillCatalog.Catalog discovered = catalog.discover(request(workspace, user));
        int exitCode = createJunction(skill.resolve("alias"), outside);
        if (exitCode != 0) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "junction creation is unavailable in this environment");
            return;
        }

        assertThrows(UncheckedIOException.class,
                () -> read(catalog, discovered, "junction", "alias/outside.txt", 100));
    }

    /** 对本地 mklink 命令设置硬等待边界并在异常退出时强制收尾，避免测试遗留子进程。 */
    private static int createJunction(Path link, Path target) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                link.toString(), target.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                throw new IOException("junction_creation_timed_out");
            }
            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    throw new IOException("junction_creation_process_did_not_exit");
                }
            }
        }
    }

    /** 空用户目录仍可发现完整的内置文档，且资源读取限于显式登记的专题。 */
    @Test
    void discoversBundledJaDocsWithoutFilesystemPackages() throws Exception {
        Path user = temporary.resolve("absent-user");
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        TestSkillCatalog catalog = new TestSkillCatalog();
        SkillCatalog.Catalog discovered = catalog.discover(request(workspace, user));

        assertEquals(List.of("ja-docs"), discovered.skills().stream().map(SkillDescriptor::name).toList());
        assertEquals("bundled", descriptor(discovered, "ja-docs").sourceId());
        assertTrue(read(catalog, discovered, "ja-docs", "SKILL.md", 8_000)
                .content().contains("Ja 用户文档"));
        for (String reference : List.of("getting-started.md", "configuration.md",
                "extensions.md", "cli.md", "troubleshooting.md")) {
            assertFalse(read(catalog, discovered, "ja-docs", "references/" + reference, 8_000)
                    .content().isBlank(), reference);
        }
        assertThrows(IllegalArgumentException.class,
                () -> read(catalog, discovered, "ja-docs", "references/unknown.md", 2_000));
    }

    /** 同名用户包应整包覆盖内置说明，避免把用户修改与随版本分发的资源混读。 */
    @Test
    void userSkillCanReplaceBundledJaDocs() throws Exception {
        Path user = Files.createDirectories(temporary.resolve("user-skills"));
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        writeSkill(user, "ja-docs", "Personal Ja docs.", "personal body", null, null);
        TestSkillCatalog catalog = new TestSkillCatalog();
        SkillCatalog.Catalog discovered = catalog.discover(request(
                workspace, user, temporary.resolve("absent-ja-skills"), true));

        assertEquals("user", descriptor(discovered, "ja-docs").sourceId());
        assertEquals("personal body", read(catalog, discovered, "ja-docs", "SKILL.md", 2_000).content());
        assertThrows(IllegalArgumentException.class,
                () -> read(catalog, discovered, "ja-docs", "references/configuration.md", 2_000));
    }

    /** 创建公开 Kernel API 所要求的绝对发现请求，避免测试夹具隐含路径语义。 */
    private static DiscoveryRequest request(Path workspace, Path user) {
        return new DiscoveryRequest(
                workspace.toAbsolutePath().normalize(),
                user.resolveSibling(user.getFileName() + "-agents-absent").toAbsolutePath().normalize(),
                user.toAbsolutePath().normalize(), true);
    }

    /** 构造显式四来源请求，测试不会依赖进程 user.home 或真实 Ja home。 */
    private static DiscoveryRequest request(
            Path workspace, Path agents, Path ja, boolean trusted) {
        return new DiscoveryRequest(
                workspace.toAbsolutePath().normalize(), agents.toAbsolutePath().normalize(),
                ja.toAbsolutePath().normalize(), trusted);
    }

    /** 按身份定位描述对象，避免断言依赖排序后的列表下标。 */
    private static SkillDescriptor descriptor(SkillCatalog.Catalog catalog, String name) {
        return catalog.skills().stream().filter(skill -> name.equals(skill.name())).findFirst().orElseThrow();
    }

    /** 通过紧凑夹具调用公开实时读取端口，保持测试贴近真实边界。 */
    private static SkillDocument read(TestSkillCatalog catalog, SkillCatalog.Catalog discovered,
            String skill, String path, int limit) {
        return catalog.read(discovered, new SkillReadRequest(skill, path, limit));
    }

    /**
     * 复现宿主显式注册四类来源的组合方式，并将所有根目录限制在测试临时目录内。
     */
    private static final class TestSkillCatalog {
        private final SkillCatalog catalog = new SkillCatalog(List.of(
                LocalSkillSource.bundled(),
                LocalSkillSource.directory("user", 1, "skill-root-user"),
                LocalSkillSource.directory("ja", 2, "skill-root-ja"),
                LocalSkillSource.workspace("project", 3, "workspace-directory", "workspace-trusted")));

        /** 先移除禁用引用再执行来源覆盖，验证较低优先级的同名项可以重新显露。 */
        private SkillCatalog.Catalog discover(DiscoveryRequest request) {
            return discover(request, Set.of());
        }

        /** 将禁用清单传给 Core Catalog，确保过滤发生在同名覆盖选择之前。 */
        private SkillCatalog.Catalog discover(DiscoveryRequest request, Set<String> disabledReferences) {
            return catalog.discover(request.context(), disabledReferences);
        }

        /** 返回设置页所需的全部来源副本，不把运行时胜出的覆盖项误当成唯一本体。 */
        private List<SkillDescriptor> discoverAll(DiscoveryRequest request) {
            return catalog.discoverAll(request.context());
        }

        /** 只允许从同一请求发现结果中挑选，并保留路径读取所需的原始上下文绑定。 */
        private SkillCatalog.Catalog select(SkillCatalog.Catalog discovered, List<String> names) {
            return catalog.select(discovered, names);
        }

        /** 构造显式空目录而不触发任何目录解析，验证无 Skill 请求不访问宿主文件系统。 */
        private SkillCatalog.Catalog emptyCatalog() {
            return catalog.emptyCatalog(new SkillRequestContext("test-tenant"));
        }

        /** 通过 Core Catalog 读取资源，覆盖生产路径使用的描述符绑定与内容指纹逻辑。 */
        private SkillDocument read(SkillCatalog.Catalog discovered, SkillReadRequest request) {
            return catalog.read(discovered, request);
        }
    }

    /**
     * 收集一次测试发现需要的宿主路径和信任状态，避免来源实现依赖进程全局目录。
     *
     * @param workspace 工作区
     * @param agents Agent 列表
     * @param ja Ja 专用上下文
     * @param trusted 工作区是否已获信任
     */
    private record DiscoveryRequest(Path workspace,
                                    Path agents,
                                    Path ja,
                                    boolean trusted) {
        /** 将夹具路径映射为来源属性；Core 合约只识别键值，不认识 Ja 专属路径名称。 */
        private SkillRequestContext context() {
            return new SkillRequestContext("test-tenant", java.util.Map.of(
                    "workspace-directory", workspace.toString(),
                    "workspace-trusted", Boolean.toString(trusted),
                    "skill-root-user", agents.toString(),
                    "skill-root-ja", ja.toString()));
        }
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
        TestSkillCatalog catalog = new TestSkillCatalog();
        SkillCatalog.Catalog discovered = catalog.discover(request(workspace, userRoot));
        assertTrue(discovered.skills().stream()
                .noneMatch(item -> item.sourceId().equals("ja")));
    }
}
