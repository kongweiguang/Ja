// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.skills;

import io.github.kongweiguang.ja.conversation.port.out.SkillCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证原生 Skill 目录边界，测试夹具不得引入第三方 Agent 类型。 */
final class JaSkillSourcesTest {
    @TempDir
    Path temporary;

    /** 验证 builtin、user、workspace 的覆盖优先级，以及只暴露元数据的投影边界。 */
    @Test
    void resolvesPrecedenceAndLoadsDocumentsLazily() throws Exception {
        Path user = Files.createDirectories(temporary.resolve("user"));
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        writeSkill(user, "coding", "User coding.", "user body", "notes/user.txt", "user resource");
        writeSkill(workspace.resolve(".agents/skills"), "coding", "Workspace coding.",
                "workspace body", "notes/workspace.txt", "workspace resource");
        writeSkill(user, "review", "Review changes.", "review body", null, null);

        JaSkillSources catalog = new JaSkillSources();
        SkillCatalog.SkillSnapshot snapshot = catalog.snapshot(request(workspace, user));

        assertEquals(List.of("coding", "review"), snapshot.skills().stream()
                .map(SkillCatalog.SkillDescriptor::name).toList());
        SkillCatalog.SkillDescriptor coding = descriptor(snapshot, "coding");
        assertEquals(SkillCatalog.Source.WORKSPACE, coding.source());
        assertEquals("Workspace coding.", coding.description());
        assertTrue(coding.revision().matches("skill_[0-9a-f]{64}"));
        assertTrue(snapshot.revision().matches("skills_[0-9a-f]{64}"));
        assertFalse(snapshot.revision().startsWith("skill_"));
        assertEquals("workspace body", read(catalog, snapshot, "coding", "SKILL.md", 100).content());
        assertEquals("workspace resource",
                read(catalog, snapshot, "coding", "notes/workspace.txt", 100).content());
        assertThrows(IllegalArgumentException.class,
                () -> read(catalog, snapshot, "coding", "notes/user.txt", 100));
        assertFalse(snapshot.skills().toString().contains("workspace body"));

        Path userOnlyWorkspace = Files.createDirectories(temporary.resolve("user-only-workspace"));
        SkillCatalog.SkillSnapshot userWins = catalog.snapshot(request(userOnlyWorkspace, user));
        assertEquals(SkillCatalog.Source.JA_USER, descriptor(userWins, "coding").source());
        assertEquals("user body", read(catalog, userWins, "coding", "SKILL.md", 100).content());
    }

    /** 验证四级来源按高优先级到低优先级稳定展示，并让 Ja user 覆盖通用 user 包。 */
    @Test
    void resolvesFourSourcesAndOrdersDescriptorsByPriorityThenName() throws Exception {
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
        SkillCatalog.SkillSnapshot snapshot = catalog.snapshot(request(workspace, agents, ja, true));

        assertEquals(List.of("workspace-only", "ja-only", "shared", "user-only", "coding"),
                snapshot.skills().stream().map(SkillCatalog.SkillDescriptor::name).toList());
        assertEquals(SkillCatalog.Source.JA_USER, descriptor(snapshot, "shared").source());
        assertEquals("ja", read(catalog, snapshot, "shared", "SKILL.md", 100).content());
    }

    /** 验证项目 Skill 从 Git 根到 cwd 逐层整包覆盖，而不是只读取仓库根目录。 */
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
        SkillCatalog.SkillSnapshot snapshot = catalog.snapshot(request(child, agents, ja, true));

        assertEquals(SkillCatalog.Source.WORKSPACE, descriptor(snapshot, "scoped").source());
        assertEquals("deep", read(catalog, snapshot, "scoped", "SKILL.md", 100).content());
    }

    /** 验证调用方冻结为不受信任时完全跳过项目来源，损坏项目包也不能被解析。 */
    @Test
    void skipsWorkspaceSourcesWhenFrozenTrustIsFalse() throws Exception {
        Path agents = temporary.resolve("absent-agents");
        Path ja = Files.createDirectories(temporary.resolve("trusted-ja"));
        Path workspace = Files.createDirectories(temporary.resolve("untrusted-project"));
        Files.createDirectory(workspace.resolve(".git"));
        writeSkill(ja, "safe", "Safe user skill.", "user", null, null);
        Path invalid = Files.createDirectories(workspace.resolve(".agents/skills/safe"));
        Files.writeString(invalid.resolve("SKILL.md"), "not frontmatter", StandardCharsets.UTF_8);

        JaSkillSources catalog = new JaSkillSources();
        SkillCatalog.SkillSnapshot snapshot = catalog.snapshot(request(workspace, agents, ja, false));

        assertEquals(SkillCatalog.Source.JA_USER, descriptor(snapshot, "safe").source());
        assertEquals("user", read(catalog, snapshot, "safe", "SKILL.md", 100).content());
    }

    /** 验证换行符和 Unicode 组合形式不会产生平台特有的版本号。 */
    @Test
    void revisionUsesNormalizedReproducibleContent() throws Exception {
        Path user = Files.createDirectories(temporary.resolve("user"));
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path skill = writeSkill(user, "cafe", "Caf\u00e9 review.", "line one\nline two", null, null);
        JaSkillSources catalog = new JaSkillSources();
        SkillCatalog.SkillSnapshot first = catalog.snapshot(request(workspace, user));

        Files.writeString(skill.resolve("SKILL.md"),
                "---\r\nname: cafe\r\ndescription: Cafe\u0301 review.\r\n---\r\nline one\r\nline two",
                StandardCharsets.UTF_8);
        SkillCatalog.SkillSnapshot second = catalog.snapshot(request(workspace, user));

        assertEquals(first.revision(), second.revision());
        assertEquals(descriptor(first, "cafe").revision(), descriptor(second, "cafe").revision());
    }

    /** 验证替换后既有快照字节保持冻结，而后续 Turn 能读取新版本。 */
    @Test
    void freezesRevisionAndDocumentsForTurn() throws Exception {
        Path user = Files.createDirectories(temporary.resolve("user"));
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path skill = writeSkill(user, "stable", "Stable skill.", "first", null, null);
        JaSkillSources catalog = new JaSkillSources();
        SkillCatalog.SkillSnapshot first = catalog.snapshot(request(workspace, user));

        writeDocument(skill, "stable", "Stable skill.", "second");
        SkillCatalog.SkillSnapshot second = catalog.snapshot(request(workspace, user));

        assertEquals("first", read(catalog, first, "stable", "SKILL.md", 100).content());
        assertEquals("second", read(catalog, second, "stable", "SKILL.md", 100).content());
        assertNotEquals(first.revision(), second.revision());
        SkillCatalog.SkillSnapshot forged = new SkillCatalog.SkillSnapshot(
                first.revision(), first.skills(), Instant.EPOCH);
        assertThrows(IllegalArgumentException.class,
                () -> read(catalog, forged, "stable", "SKILL.md", 100));
    }

    /** 验证选择结果只暴露精确解析版本，读取注册表不能越过筛选边界。 */
    @Test
    void filtersByResolvedRevisionAndFreezesSelectedDocuments() throws Exception {
        Path user = Files.createDirectories(temporary.resolve("user"));
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        writeSkill(user, "coding", "User coding.", "user body", null, null);
        Path selectedSkill = writeSkill(user, "review", "Review.", "first review", null, null);
        writeSkill(workspace.resolve(".agents/skills"), "coding", "Workspace coding.",
                "workspace body", null, null);
        JaSkillSources catalog = new JaSkillSources();
        SkillCatalog.SkillSnapshot complete = catalog.snapshot(request(workspace, user));
        String reviewRevision = descriptor(complete, "review").revision();

        SkillCatalog.SkillSnapshot filtered = catalog.select(complete, List.of(reviewRevision));
        assertEquals(List.of("review"), filtered.skills().stream()
                .map(SkillCatalog.SkillDescriptor::name).toList());
        assertEquals("first review", read(catalog, filtered, "review", "SKILL.md", 100).content());
        assertThrows(IllegalArgumentException.class,
                () -> read(catalog, filtered, "coding", "SKILL.md", 100));

        writeDocument(selectedSkill, "review", "Review.", "second review");
        assertEquals("first review", read(catalog, filtered, "review", "SKILL.md", 100).content());
    }

    /** 验证 workspace 解析覆盖后，低优先级来源中的重复版本不可再被选择。 */
    @Test
    void rejectsRevisionHiddenByPrecedence() throws Exception {
        Path user = Files.createDirectories(temporary.resolve("user"));
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        writeSkill(user, "coding", "User coding.", "user body", null, null);
        JaSkillSources catalog = new JaSkillSources();
        Path userOnlyWorkspace = Files.createDirectories(temporary.resolve("user-only-workspace"));
        String hiddenRevision = descriptor(catalog.snapshot(request(userOnlyWorkspace, user)), "coding").revision();
        writeSkill(workspace.resolve(".agents/skills"), "coding", "Workspace coding.",
                "workspace body", null, null);

        SkillCatalog.SkillSnapshot workspaceComplete = catalog.snapshot(request(workspace, user));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.select(workspaceComplete, List.of(hiddenRevision)));
    }

    /** 验证空选择不访问任何 Skill 来源，未授权的损坏目录不能阻断 Turn。 */
    @Test
    void emptySelectionProducesReadableButEmptySnapshotBoundary() throws Exception {
        Path user = Files.writeString(temporary.resolve("user-not-directory"), "ignored",
                StandardCharsets.UTF_8);
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path invalid = Files.createDirectories(workspace.resolve(".agents/skills/invalid"));
        Files.writeString(invalid.resolve("SKILL.md"),
                "---\nname: invalid\ndescription: |\n---\nignored", StandardCharsets.UTF_8);
        JaSkillSources catalog = new JaSkillSources();

        SkillCatalog.SkillSnapshot empty = catalog.emptySnapshot();

        assertTrue(empty.skills().isEmpty());
        assertTrue(empty.revision().matches("skills_[0-9a-f]{64}"));
        assertThrows(IllegalArgumentException.class,
                () -> read(catalog, empty, "coding", "SKILL.md", 100));
    }

    /** 验证未知、重复、空 block 及缺失必填字段不会进入可用快照。 */
    @Test
    void rejectsInvalidOrUnknownFrontmatterFields() throws Exception {
        List<String> invalid = List.of(
                "---\nname: bad\ndescription: Bad.\nunknown: 1\n---\nbody",
                "---\nname: bad\nname: bad\ndescription: Bad.\n---\nbody",
                "---\nname: bad\ndescription: |\n---\nbody",
                "---\nname: bad\n---\nbody");
        for (int index = 0; index < invalid.size(); index++) {
            Path root = Files.createDirectories(temporary.resolve("user-" + index));
            Path skill = Files.createDirectories(root.resolve("bad"));
            Files.writeString(skill.resolve("SKILL.md"), invalid.get(index), StandardCharsets.UTF_8);
            assertSnapshotRejected(root);
        }
    }

    /** 验证 Agent Skills 标准可选字段可被读取，但 allowed-tools 不进入公开 descriptor。 */
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
        SkillCatalog.SkillSnapshot snapshot = catalog.snapshot(request(
                Files.createDirectories(temporary.resolve("optional-workspace")), ja));
        SkillCatalog.SkillDescriptor descriptor = descriptor(snapshot, "standard");

        assertEquals("Standard skill metadata.", descriptor.description());
        assertFalse(descriptor.toString().contains("allowed-tools"));
        assertEquals("body\n", read(catalog, snapshot, "standard", "SKILL.md", 100).content());

        Files.writeString(skill.resolve("SKILL.md"),
                document.replace("Bash(git:*) Read", "Bash(git:*) Read Write"), StandardCharsets.UTF_8);
        SkillCatalog.SkillSnapshot refreshed = catalog.snapshot(request(
                temporary.resolve("optional-workspace"), ja));
        assertNotEquals(descriptor.revision(), descriptor(refreshed, "standard").revision());
    }

    /** 验证带 BOM 或非法 UTF-8 的包被排除，但不阻断其它已解析 Skill。 */
    @Test
    void rejectsBomAndMalformedUtf8() throws Exception {
        Path bomRoot = Files.createDirectories(temporary.resolve("bom-user"));
        Path bom = Files.createDirectories(bomRoot.resolve("bad")).resolve("SKILL.md");
        byte[] valid = "---\nname: bad\ndescription: Bad.\n---\nbody".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[valid.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(valid, 0, withBom, 3, valid.length);
        Files.write(bom, withBom);
        assertSnapshotRejected(bomRoot);

        Path malformedRoot = Files.createDirectories(temporary.resolve("malformed-user"));
        Path malformed = Files.createDirectories(malformedRoot.resolve("bad")).resolve("SKILL.md");
        Files.write(malformed, new byte[] {(byte) 0xC3, (byte) 0x28});
        assertSnapshotRejected(malformedRoot);
    }

    /** 验证逻辑读取不能逃逸、别名映射或绕过已冻结的精确资源键。 */
    @Test
    void rejectsTraversalAndAmbiguousResourcePaths() throws Exception {
        Path user = Files.createDirectories(temporary.resolve("user"));
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        writeSkill(user, "safe", "Safe skill.", "body", "notes/a.txt", "ab");
        JaSkillSources catalog = new JaSkillSources();
        SkillCatalog.SkillSnapshot snapshot = catalog.snapshot(request(workspace, user));

        assertThrows(IllegalArgumentException.class,
                () -> new SkillCatalog.SkillReadRequest("safe", "../outside", 100));
        assertThrows(IllegalArgumentException.class,
                () -> read(catalog, snapshot, "safe", "notes/./a.txt", 100));
        assertThrows(IllegalArgumentException.class,
                () -> read(catalog, snapshot, "safe", "notes//a.txt", 100));
        assertTrue(read(catalog, snapshot, "safe", "notes/a.txt", 1).truncated());
    }

    /** 验证文件系统链接即使当前解析到来源目录内部也必须拒绝。 */
    @Test
    void rejectsSymbolicLinksWhenPlatformPermitsCreation() throws Exception {
        Path user = Files.createDirectories(temporary.resolve("user"));
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path skill = writeSkill(user, "linked", "Linked skill.", "body", null, null);
        Path target = Files.writeString(skill.resolve("target.txt"), "target", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(skill.resolve("alias.txt"), target.getFileName());
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            return;
        }
        JaSkillSources catalog = new JaSkillSources();
        assertTrue(catalog.snapshot(request(workspace, user)).skills().stream()
                .noneMatch(item -> "linked".equals(item.name())));
    }

    /** 验证 Windows junction 属于重解析点，即使目标可读也必须拒绝。 */
    @Test
    void rejectsWindowsJunctionsWhenPlatformPermitsCreation() throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            return;
        }
        Path user = Files.createDirectories(temporary.resolve("user"));
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path skill = writeSkill(user, "junction", "Junction skill.", "body", null, null);
        Path target = Files.createDirectories(temporary.resolve("junction-target"));
        Files.writeString(target.resolve("outside.txt"), "outside", StandardCharsets.UTF_8);
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                skill.resolve("alias").toString(), target.toString()).redirectErrorStream(true).start();
        if (process.waitFor() != 0) {
            return;
        }
        JaSkillSources catalog = new JaSkillSources();
        assertTrue(catalog.snapshot(request(workspace, user)).skills().stream()
                .noneMatch(item -> "junction".equals(item.name())));
    }

    /** 验证文件数、嵌套深度与保守 token 预算超限包被整体排除，不执行截断。 */
    @Test
    void enforcesCountDepthAndTextBudgets() throws Exception {
        Path countRoot = Files.createDirectories(temporary.resolve("count-user"));
        Path counted = writeSkill(countRoot, "counted", "Counted skill.", "body", null, null);
        for (int index = 0; index < JaSkillSources.MAX_FILES_PER_SKILL; index++) {
            Files.writeString(counted.resolve("r" + index + ".txt"), "x", StandardCharsets.UTF_8);
        }
        assertSnapshotRejected(countRoot);

        Path depthRoot = Files.createDirectories(temporary.resolve("depth-user"));
        Path deep = writeSkill(depthRoot, "deep", "Deep skill.", "body", null, null);
        Path nested = deep;
        for (int index = 0; index < JaSkillSources.MAX_DIRECTORY_DEPTH + 1; index++) {
            nested = Files.createDirectories(nested.resolve("d" + index));
        }
        Files.writeString(nested.resolve("too-deep.txt"), "x", StandardCharsets.UTF_8);
        assertSnapshotRejected(depthRoot);

        Path bytesRoot = Files.createDirectories(temporary.resolve("bytes-user"));
        Path bytes = writeSkill(bytesRoot, "bytes", "Byte-limited skill.", "body", null, null);
        Files.writeString(bytes.resolve("oversize.txt"),
                "x".repeat(JaSkillSources.MAX_FILE_BYTES + 1), StandardCharsets.UTF_8);
        assertSnapshotRejected(bytesRoot);

        Path documentRoot = Files.createDirectories(temporary.resolve("document-user"));
        Path document = Files.createDirectories(documentRoot.resolve("document"));
        Files.writeString(document.resolve("SKILL.md"),
                "---\nname: document\ndescription: Document size.\n---\n"
                        + "x".repeat(JaSkillSources.MAX_SKILL_DOCUMENT_BYTES),
                StandardCharsets.UTF_8);
        assertSnapshotRejected(documentRoot);

        Path tokenRoot = Files.createDirectories(temporary.resolve("token-user"));
        writeSkill(tokenRoot, "large", "Large skill.",
                "x".repeat(JaSkillSources.MAX_SKILL_TOKENS + 1), null, null);
        assertSnapshotRejected(tokenRoot);
    }

    /** 验证显式注册可加载 classpath builtin 资源，且无需枚举目录。 */
    @Test
    void loadsBuiltinFromExactClasspathFixture() throws Exception {
        Path user = temporary.resolve("absent-user");
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        JaSkillSources catalog = new JaSkillSources();
        SkillCatalog.SkillSnapshot snapshot = catalog.snapshot(request(workspace, user));

        SkillCatalog.SkillDescriptor coding = descriptor(snapshot, "coding");
        assertEquals(SkillCatalog.Source.BUNDLED, coding.source());
        assertTrue(coding.revision().matches("skill_[0-9a-f]{64}"));
        assertTrue(snapshot.revision().matches("skills_[0-9a-f]{64}"));
        assertTrue(read(catalog, snapshot, "coding", "SKILL.md", 10_000).content()
                .contains("# Ja coding skill"));
    }

    /** 创建冻结 Kernel API 所要求的绝对请求结构，避免测试夹具隐含路径语义。 */
    private static SkillCatalog.SnapshotRequest request(Path workspace, Path user) {
        return new SkillCatalog.SnapshotRequest(
                workspace.toAbsolutePath().normalize(),
                user.resolveSibling(user.getFileName() + "-agents-absent").toAbsolutePath().normalize(),
                user.toAbsolutePath().normalize(), true);
    }

    /** 构造显式四来源请求，测试不会依赖进程 user.home 或真实 Ja home。 */
    private static SkillCatalog.SnapshotRequest request(
            Path workspace, Path agents, Path ja, boolean trusted) {
        return new SkillCatalog.SnapshotRequest(
                workspace.toAbsolutePath().normalize(), agents.toAbsolutePath().normalize(),
                ja.toAbsolutePath().normalize(), trusted);
    }

    /** 按身份定位描述对象，避免断言依赖排序后的列表下标。 */
    private static SkillCatalog.SkillDescriptor descriptor(SkillCatalog.SkillSnapshot snapshot, String name) {
        return snapshot.skills().stream().filter(skill -> name.equals(skill.name())).findFirst().orElseThrow();
    }

    /** 通过紧凑夹具调用公开延迟读取端口，保持测试贴近真实边界。 */
    private static SkillCatalog.SkillDocument read(JaSkillSources catalog, SkillCatalog.SkillSnapshot snapshot,
            String skill, String path, int limit) {
        return catalog.read(snapshot, new SkillCatalog.SkillReadRequest(skill, path, limit));
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

    /** 只替换主文档，以隔离并验证快照版本行为。 */
    private static void writeDocument(Path skill, String name, String description, String body) throws Exception {
        Files.writeString(skill.resolve("SKILL.md"), "---\nname: " + name + "\ndescription: "
                + description + "\n---\n" + body, StandardCharsets.UTF_8);
    }

    /** 断言非法包不进入可用快照，同时保留同一来源中的其它合法条目。 */
    private void assertSnapshotRejected(Path userRoot) throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace-" + userRoot.getFileName()));
        JaSkillSources catalog = new JaSkillSources();
        SkillCatalog.SkillSnapshot snapshot = catalog.snapshot(request(workspace, userRoot));
        assertTrue(snapshot.skills().stream().noneMatch(item -> item.source() == SkillCatalog.Source.JA_USER));
    }
}
