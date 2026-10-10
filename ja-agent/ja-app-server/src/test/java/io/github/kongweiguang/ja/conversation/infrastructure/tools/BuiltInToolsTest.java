// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.tools;

import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceBoundaryFactory;
import io.github.kongweiguang.ja.conversation.application.change.TurnChangeTracker;
import io.github.kongweiguang.ja.workspace.infrastructure.filesystem.WorkspaceBoundaryFactoryAdapter;
import io.github.kongweiguang.ja.conversation.application.change.WorkspaceMutationFactCodec;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader;
import io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference;
import io.github.kongweiguang.ja.conversation.application.gateway.ManagedToolMediaImporter;
import io.github.kongweiguang.ja.agent.application.skill.SkillCatalog;
import io.github.kongweiguang.ja.agent.application.concurrent.CancellationSource;
import io.github.kongweiguang.ja.agent.domain.skill.SkillDescriptor;
import io.github.kongweiguang.ja.agent.domain.skill.SkillPage;
import io.github.kongweiguang.ja.agent.domain.skill.SkillReadRequest;
import io.github.kongweiguang.ja.agent.domain.skill.SkillRequestContext;
import io.github.kongweiguang.ja.agent.domain.skill.SkillResourceContent;
import io.github.kongweiguang.ja.agent.application.gateway.skill.SkillSource;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonBoolean;
import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.support.FixedAgentPromptSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.RandomAccessFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证文件与图片 Tool 集、Workspace 路径语义和有界发现行为。 */
class BuiltInToolsTest {
    @TempDir Path temporary;
    private static final SkillRequestContext SKILL_CONTEXT = new SkillRequestContext("ja");
    private static final SkillCatalog EMPTY_SKILLS = new SkillCatalog(List.of());
    private static final SkillCatalog.Catalog EMPTY_CATALOG = EMPTY_SKILLS.emptyCatalog(SKILL_CONTEXT);

    /** 文件与图片 Tool 名称固定；宿主进程 Tool 由独立注册表装配。 */
    @Test
    void exposesWorkspaceToolsAndAllowsPathsOutsideWorkspace() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments());
        assertEquals(List.of("edit", "find", "grep", "ls", "read", "read_attachment", "view_image", "write"),
                registry.snapshot().stream().map(tool -> tool.spec().name()).toList());

        execute(registry, "write", JsonObjects.builder()
                .putText("path", outside.resolve("note.txt").toString()).putText("content", "one").build());
        assertEquals("one", execute(registry, "read",
                JsonObjects.builder().putText("path", "../outside/note.txt").build()).content());
        execute(registry, "edit", JsonObjects.builder()
                .putText("path", "../outside/note.txt").put("edits", edits(replacement("one", "two"))).build());
        assertEquals("two", Files.readString(outside.resolve("note.txt")));
    }

    /**
     * 路径安全依赖归一化后的 WorkspaceBoundary，而不是原始路径拼写长度；edit 批量语义也不应有任意条数上限。
     */
    @Test
    void acceptsLongNormalizedPathsAndEditBatchesBeyondFormerCaps() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("long-tool-input-workspace"));
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS,
                catalog(), promptSession(), unusedAttachments());
        String longPath = "unused/../".repeat(900) + "sample.txt";
        assertTrue(longPath.length() > 8_192);

        for (String toolName : List.of("read", "edit", "write")) {
            JaTool tool = registry.snapshot().stream().filter(candidate -> toolName.equals(candidate.spec().name()))
                    .findFirst().orElseThrow();
            JsonObject properties = (JsonObject) tool.spec().inputSchema().get("properties");
            assertFalse(((JsonObject) properties.get("path")).containsKey("maxLength"), toolName);
        }
        JaTool editTool = registry.snapshot().stream().filter(candidate -> "edit".equals(candidate.spec().name()))
                .findFirst().orElseThrow();
        JsonObject editProperties = (JsonObject) editTool.spec().inputSchema().get("properties");
        assertFalse(((JsonObject) editProperties.get("edits")).containsKey("maxItems"));

        String before = java.util.stream.IntStream.range(0, 129)
                .mapToObj(index -> "old-" + (1_000 + index))
                .collect(java.util.stream.Collectors.joining("\n"));
        String after = java.util.stream.IntStream.range(0, 129)
                .mapToObj(index -> "new-" + (2_000 + index))
                .collect(java.util.stream.Collectors.joining("\n"));
        JaTool.ToolResult written = execute(registry, "write", JsonObjects.builder()
                .putText("path", longPath).putText("content", before).build());
        JaTool.ToolResult read = execute(registry, "read", JsonObjects.builder().putText("path", longPath).build());
        JsonObject[] replacements = java.util.stream.IntStream.range(0, 129)
                .mapToObj(index -> replacement("old-" + (1_000 + index), "new-" + (2_000 + index)))
                .toArray(JsonObject[]::new);
        JaTool.ToolResult edited = execute(registry, "edit", JsonObjects.builder().putText("path", longPath)
                .put("edits", edits(replacements[0], java.util.Arrays.copyOfRange(replacements, 1, replacements.length)))
                .build());

        assertEquals(ToolOutcome.SUCCEEDED, written.outcome(), written::toString);
        assertEquals(ToolOutcome.SUCCEEDED, read.outcome(), read::toString);
        assertEquals(before, read.content());
        assertEquals(ToolOutcome.SUCCEEDED, edited.outcome(), edited::toString);
        assertEquals(after, Files.readString(workspace.resolve("sample.txt")));
        assertReceipt(edited, workspace.resolve("sample.txt"), true, before, after);
    }

    /** DEL/C1 在 Windows 可命名文件，但无法进入 ChangeSet，write/edit 必须在副作用之前拒绝。 */
    @Test
    void rejectsControlCharactersBeforeWritingOrEditingFiles() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("control-path-workspace"));
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments());
        for (int control : List.of(0x7F, 0x85, 0x9F)) {
            String relative = "file" + (char) control + ".txt";
            Path target = workspace.resolve(relative);
            JaTool.ToolResult written = execute(registry, "write", JsonObjects.builder()
                    .putText("path", relative).putText("content", "created").build());
            assertEquals(ToolOutcome.FAILED, written.outcome());
            assertFalse(Files.exists(target));
            Files.writeString(target, "original");
            JaTool.ToolResult edited = execute(registry, "edit", JsonObjects.builder()
                    .putText("path", relative).put("edits", edits(replacement("original", "changed"))).build());
            assertEquals(ToolOutcome.FAILED, edited.outcome());
            assertEquals("original", Files.readString(target));
        }
    }

    /** write 参数超过旧 4M 字符门槛后仍通过原子临时文件完整落盘。 */
    @Test
    void writesGeneratedContentBeyondFormerToolArgumentCeiling() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("large-write-workspace"));
        Path output = workspace.resolve("generated.txt");
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments());
        String content = "x".repeat(4_200_000);

        JaTool.ToolResult result = execute(registry, "write", JsonObjects.builder()
                .putText("path", "generated.txt").putText("content", content).build());

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertEquals(content.length(), Files.size(output));
        assertEquals(content, Files.readString(output));

        Path largeExisting = workspace.resolve("large-existing.txt");
        Files.writeString(largeExisting, "p".repeat(4_200_000), StandardCharsets.UTF_8);
        JaTool.ToolResult replaced = execute(registry, "write", JsonObjects.builder()
                .putText("path", "large-existing.txt").putText("content", "replacement").build());
        assertEquals(ToolOutcome.SUCCEEDED, replaced.outcome());
        assertEquals("replacement", Files.readString(largeExisting));
        assertTrue(replaced.mutationReceipt().isEmpty());
        assertEquals(JaTool.MutationObservationFailure.CAPTURE_FAILED,
                replaced.mutationObservationFailure().orElseThrow());
    }

    /** 取消发生在第二个写入块前时不得发布半文件，且同目录暂存文件由 atomicWrite 自己清理。 */
    @Test
    void atomicWriteStopsBetweenChunksAndRemovesTemporaryFile() throws Exception {
        Path target = temporary.resolve("cancelled.txt");

        assertThrows(java.util.concurrent.CancellationException.class,
                () -> BuiltInTools.atomicWrite(target, "x".repeat(64 * 1024 + 1), null,
                        cancelledAfterChecks(3), Instant.now().plusSeconds(30)));

        assertFalse(Files.exists(target));
        try (Stream<Path> children = Files.list(temporary)) {
            assertEquals(0L, children.count());
        }
    }

    /** 过期操作在创建暂存文件前失败，保留目标状态且不留下可误认作提交的临时文件。 */
    @Test
    void atomicWriteRejectsExpiredDeadlineBeforeStaging() throws Exception {
        Path target = temporary.resolve("expired.txt");

        assertThrows(ToolSupport.DeadlineExceededException.class,
                () -> BuiltInTools.atomicWrite(target, "content", null,
                        CancellationToken.none(), Instant.EPOCH));

        assertFalse(Files.exists(target));
        try (Stream<Path> children = Files.list(temporary)) {
            assertEquals(0L, children.count());
        }
    }

    /** 原子替换已完成后到达的取消不能把已提交文件误报为失败或丢失收据。 */
    @Test
    void committedWriteRemainsSuccessfulWhenCancellationArrivesBeforeReceipt() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("post-commit-cancel-workspace"));
        Path target = workspace.resolve("committed.txt");
        CancellationSource cancellation = new CancellationSource();
        BuiltInTools.MutationWriter writer = (path, content, boundary, token, deadline) -> {
            BuiltInTools.atomicWrite(path, content, boundary, token, deadline);
            cancellation.cancel("after_commit");
        };
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments(), writer);

        JaTool.ToolResult result = execute(registry, "write", JsonObjects.builder()
                .putText("path", "committed.txt").putText("content", "committed").build(),
                cancellation, context());

        assertTrue(cancellation.isCancellationRequested());
        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertEquals("committed", Files.readString(target));
        assertTrue(result.mutationReceipt().isPresent());
    }

    /** grep 默认正则，literal=true 可让常见标点按精确文本匹配；省略 glob 时搜索所有文件。 */
    @Test
    void grepsWorkspaceFilesWithLiteralPatternAndDefaultGlob() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("search-workspace"));
        Path nested = Files.createDirectory(workspace.resolve("nested"));
        Files.writeString(workspace.resolve("Plan.java"), "class Plan {\n  // a+b marker\n}\n", StandardCharsets.UTF_8);
        Files.writeString(nested.resolve("notes.md"), "a+b marker in markdown\n", StandardCharsets.UTF_8);
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments());

        JaTool.ToolResult result = execute(registry, "grep", JsonObjects.builder()
                .putText("pattern", "a+b").putBoolean("literal", true).build());

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertTrue(result.content().contains("Plan.java:2"), result.content());
        assertTrue(result.content().contains("searchTool=rg"), result.content());
        assertTrue(result.content().contains("nested/notes.md:1"), result.content());

        JaTool.ToolResult javaOnly = execute(registry, "grep", JsonObjects.builder()
                .putText("pattern", "a+b").putBoolean("literal", true).putText("glob", "*.java").build());
        assertTrue(javaOnly.content().contains("Plan.java:2"));
        assertFalse(javaOnly.content().contains("notes.md:1"));
        assertEquals(JaTool.WorkspaceMutationMode.NONE,
                registry.snapshot().stream().filter(tool -> "grep".equals(tool.spec().name()))
                        .findFirst().orElseThrow().workspaceMutationMode());
    }

    /** find 只返回相对文件名，不读取正文，因此 malformed UTF-8 和大文件仍可被定位。 */
    @Test
    void findsFilesByGlobWithoutReadingContents() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("find-workspace"));
        Path nested = Files.createDirectory(workspace.resolve("src"));
        Files.write(nested.resolve("Main.java"), new byte[]{(byte) 0xc3, 0x28});
        Path large = workspace.resolve("large.jar");
        try (RandomAccessFile file = new RandomAccessFile(large.toFile(), "rw")) {
            file.setLength(32L * 1024 * 1024);
        }
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments());

        JaTool.ToolResult result = execute(registry, "find", JsonObjects.builder()
                .putText("pattern", "*.java").build());

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertTrue(result.content().contains("src/Main.java"), result.content());
        assertFalse(result.content().contains("c3"));
        assertTrue(result.content().contains("searchTool=fd"), result.content());
    }

    /** ls 只列目标目录一层，并保留目录类型；子目录正文/后代不会被物化。 */
    @Test
    void listsOnlyOneDirectoryLevel() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("ls-workspace"));
        Path child = Files.createDirectory(workspace.resolve("child"));
        Files.writeString(child.resolve("nested.txt"), "nested");
        Files.writeString(workspace.resolve("root.txt"), "root");
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments());

        JaTool.ToolResult result = execute(registry, "ls", JsonObjects.builder().build());

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertTrue(result.content().contains("child\tdirectory"));
        assertTrue(result.content().contains("root.txt\tfile"));
        assertFalse(result.content().contains("nested.txt"));
        assertTrue(result.content().contains("scannedBytes=0"));
    }

    /** 搜索查询没有任意 Unicode code point 长度门槛，长度仍由协议载荷和 rg 进程边界控制。 */
    @Test
    void acceptsSearchPatternBeyondFormerUnicodeCodePointLimit() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("unicode-argument-workspace"));
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments());

        JaTool.ToolResult result = execute(registry, "grep", JsonObjects.builder()
                .putText("pattern", "😀".repeat(4_097)).putBoolean("literal", true).build());

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome(), result::content);
    }

    /** rg 命中含非法 UTF-8 的候选时必须显式报告 partial，不能把二进制输出伪装成空文本。 */
    @Test
    void reportsSkippedUnreadableFilesAsPartial() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("grep-unreadable-workspace"));
        Files.write(workspace.resolve("malformed.txt"), new byte[]{(byte) 0xc3, 0x28,
                ' ', 'n', 'o', 't', '-', 'p', 'r', 'e', 's', 'e', 'n', 't'});
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments());

        JaTool.ToolResult result = execute(registry, "grep", JsonObjects.builder()
                .putText("pattern", "not-present").build());

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertTrue(result.content().contains("skippedCandidates=1"), result.content());
        assertTrue(result.content().contains("truncated=true"));
        assertTrue(result.content().contains("termination=non_text_output"), result.content());
        assertTrue(result.content().contains("Results are partial"));
    }

    /** grep 的结果上限必须返回部分事实和明确原因，而不是静默丢弃后续命中。 */
    @Test
    void grepHonorsResultBudgetAndReportsTruncation() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("grep-budget-workspace"));
        Files.writeString(workspace.resolve("markers.txt"), "marker\nmarker\nmarker\n");
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments());

        JaTool.ToolResult result = execute(registry, "grep", JsonObjects.builder()
                .putText("pattern", "marker").putNumber("limit", 1).build());

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertEquals(1, result.content().lines().filter(line -> line.contains("markers.txt:")).count());
        assertTrue(result.content().contains("truncated=true"));
        assertTrue(result.content().contains("termination=result_limit"));
    }

    /** 空 pattern、非法 Glob 和普通文件 path 都在执行边界返回字段化、可纠正错误。 */
    @Test
    void rejectsInvalidDiscoveryArgumentsWithSpecificConstraints() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("discovery-arguments-workspace"));
        Files.writeString(workspace.resolve("one.txt"), "one");
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments());

        JaTool.ToolResult emptyPattern = execute(registry, "grep", JsonObjects.builder()
                .putText("pattern", "").build());
        JaTool.ToolResult badGlob = execute(registry, "find", JsonObjects.builder()
                .putText("pattern", "[").build());
        JaTool.ToolResult filePath = execute(registry, "ls", JsonObjects.builder()
                .putText("path", "one.txt").build());

        assertEquals("tool_arguments_invalid", emptyPattern.errorCode());
        assertTrue(emptyPattern.content().contains("pattern"));
        assertTrue(emptyPattern.content().contains("find"));
        assertEquals("tool_arguments_invalid", badGlob.errorCode());
        assertTrue(badGlob.content().contains("pattern"));
        assertTrue(badGlob.content().contains("glob"));
        assertEquals("path_not_directory", filePath.errorCode());
    }

    /** 已取消调用必须在 WorkspaceBoundary 和文件 IO 前结束，不产生任何结果正文。 */
    @Test
    void discoveryHonorsCancellationBeforeFilesystemAccess() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("discovery-cancel-workspace"));
        Files.writeString(workspace.resolve("one.txt"), "one");
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments());

        JaTool.ToolResult result = execute(registry, "find", JsonObjects.builder()
                .putText("pattern", "*.txt").build(), cancelled(), context());

        assertEquals(ToolOutcome.CANCELLED, result.outcome());
        assertEquals("tool_cancelled", result.errorCode());
    }

    /** 过期 Deadline 返回 bounded 截断摘要，模型不会把未执行扫描误判成空目录。 */
    @Test
    void discoveryHonorsDeadlineWithExplicitTruncation() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("discovery-deadline-workspace"));
        Files.writeString(workspace.resolve("one.txt"), "one");
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments());
        JaTool.ExecutionContext expired = context(Instant.now().minusMillis(1));

        JaTool.ToolResult result = execute(registry, "find", JsonObjects.builder()
                .putText("pattern", "*.txt").build(), CancellationToken.none(), expired);

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertTrue(result.content().contains("truncated=true"));
        assertTrue(result.content().contains("termination=deadline"));
        assertFalse(result.content().contains("one.txt\n"));
    }

    /** fd 默认不跟随 Workspace 内的 symlink，搜索结果不得暴露其外部目标内容。 */
    @Test
    void discoverySkipsUnrelatedSymbolicLinks() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("discovery-link-workspace"));
        Path outside = Files.createDirectory(temporary.resolve("discovery-link-outside"));
        Files.writeString(workspace.resolve("README.md"), "readme");
        Files.writeString(outside.resolve("README.md"), "outside");
        Path link = workspace.resolve("node_modules-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException unavailable) {
            Assumptions.assumeTrue(false, "当前文件系统不能创建 symlink: "
                    + unavailable.getClass().getSimpleName());
        }
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments());

        JaTool.ToolResult result = execute(registry, "find", JsonObjects.builder()
                .putText("pattern", "README.md").build());

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertTrue(result.content().contains("README.md"));
        assertTrue(result.content().contains("searchTool=fd"));
        assertFalse(result.content().contains("node_modules-link"), result.content());
    }

    /**
     * 精确写工具只在内存结果字段携带收据，模型正文、structuredContent 与默认字符串都不得泄漏路径、
     * preimage、postimage 或 hash；只读与不可观察工具的静态分类必须保持闭集。
     */
    @Test
    void returnsNonLeakingMutationReceiptsWithExplicitModes() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("receipt-workspace"));
        Path workspaceInput = windowsShortPath(workspace);
        Path target = workspace.resolve("private-receipt.txt");
        ToolRegistry registry = createBuiltIns(workspaceInput, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments());

        JaTool.ToolResult written = execute(registry, "write", JsonObjects.builder()
                .putText("path", target.toString()).putText("content", "secret-before").build());
        JaTool.ToolResult edited = execute(registry, "edit", JsonObjects.builder()
                .putText("path", target.toString()).put("edits", edits(replacement("secret-before", "secret-after")))
                .build());

        assertEquals(JaTool.WorkspaceMutationMode.EXACT_TEXT,
                registry.snapshot().stream().filter(tool -> "write".equals(tool.spec().name())).findFirst()
                        .orElseThrow().workspaceMutationMode());
        assertEquals(JaTool.WorkspaceMutationMode.EXACT_TEXT,
                registry.snapshot().stream().filter(tool -> "edit".equals(tool.spec().name())).findFirst()
                        .orElseThrow().workspaceMutationMode());
        assertEquals(JaTool.WorkspaceMutationMode.NONE,
                registry.snapshot().stream().filter(tool -> "read".equals(tool.spec().name())).findFirst()
                        .orElseThrow().workspaceMutationMode());
        assertEquals(JaTool.WorkspaceMutationMode.NONE,
                registry.snapshot().stream().filter(tool -> "read_attachment".equals(tool.spec().name())).findFirst()
                        .orElseThrow().workspaceMutationMode());
        assertEquals(ToolSideEffect.READ_ONLY,
                registry.snapshot().stream().filter(tool -> "read".equals(tool.spec().name())).findFirst()
                        .orElseThrow().sideEffect());
        assertEquals(ToolSideEffect.READ_ONLY,
                registry.snapshot().stream().filter(tool -> "read_attachment".equals(tool.spec().name())).findFirst()
                        .orElseThrow().sideEffect());
        for (String toolName : List.of("edit", "write")) {
            assertEquals(ToolSideEffect.EXTERNAL,
                    registry.snapshot().stream().filter(tool -> toolName.equals(tool.spec().name())).findFirst()
                            .orElseThrow().sideEffect());
        }
        assertTrue(written.mutationReceipt().isPresent(), written::toString);
        assertReceipt(written, target, false, "", "secret-before");
        assertReceipt(edited, target, true, "secret-before", "secret-after");
        assertEquals(workspace.toRealPath(), written.mutationReceipt().orElseThrow().confinedWorkspaceRoot());
        assertEquals("private-receipt.txt",
                written.mutationReceipt().orElseThrow().confinedRelativePath());
    }

    /**
     * Windows Native sidecar 使用 namespaced Workspace root，而 Provider Tool 参数仍是普通盘符绝对路径；
     * 两种表示必须生成同一 Turn tracker 可接受的 receipt，不能在首个 continuation 前抛出参数异常。
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void appliesReceiptAcrossNamespacedWorkspaceAndOrdinaryAbsoluteToolPath() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("namespaced-workspace"));
        Path namespacedWorkspace = Path.of("\\\\?\\" + windowsShortPath(workspace).toAbsolutePath());
        Path target = workspace.resolve(".ja-fixture").resolve("turn-change-review.txt");
        ToolRegistry registry = createBuiltIns(namespacedWorkspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(), promptSession(), unusedAttachments());

        JaTool.ToolResult result = execute(registry, "write", JsonObjects.builder()
                .putText("path", target.toString()).putText("content", "JA_TURN_CHANGE_REVISION_000").build());
        TurnChangeTracker tracker = TurnChangeTracker.fresh(namespacedWorkspace, "ws_fixture");

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome(), result::toString);
        tracker.applyCommittedWorkspaceFact(WorkspaceMutationFactCodec.NAMESPACE,
                WorkspaceMutationFactCodec.RECEIPT_TYPE, WorkspaceMutationFactCodec.SCHEMA_VERSION,
                persistedReceipt(result.mutationReceipt().orElseThrow(), "ws_fixture"));
        TurnChangeTracker.Frozen frozen = tracker.freeze();
        assertEquals(".ja-fixture/turn-change-review.txt", frozen.changeSet().files().getFirst().path());
    }

    /** 工作区目录 symlink 不得把 write/edit 的 preimage、postimage 或副作用重定向到外部。 */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsWorkspaceDirectorySymlinkBeforeMutation() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("symlink-workspace"));
        Path outside = Files.createDirectory(temporary.resolve("symlink-outside"));
        Path link = workspace.resolve("linked");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (java.io.IOException | UnsupportedOperationException failure) {
            Assumptions.assumeTrue(false,
                    "当前 Windows 账户不能创建目录 symlink: " + failure.getClass().getSimpleName());
        }
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments());

        JaTool.ToolResult result = execute(registry, "write", JsonObjects.builder()
                .putText("path", "linked/secret.txt").putText("content", "blocked").build());

        assertEquals("tool_access_denied", result.errorCode());
        assertFalse(Files.exists(outside.resolve("secret.txt")));
        assertTrue(result.mutationReceipt().isEmpty());
    }

    /** Windows junction 与 symlink 使用不同内核机制，junction 也必须在首次文件 IO 前失败关闭。 */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsWorkspaceJunctionBeforeMutation() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("junction-workspace"));
        Path outside = Files.createDirectory(temporary.resolve("junction-outside"));
        Path junction = workspace.resolve("junction");
        int exit = createJunction(junction, outside);
        Assumptions.assumeTrue(exit == 0, "当前 Windows 环境不能创建 junction");
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments());

        JaTool.ToolResult result = execute(registry, "write", JsonObjects.builder()
                .putText("path", "junction/secret.txt").putText("content", "blocked").build());

        assertEquals("tool_access_denied", result.errorCode());
        assertFalse(Files.exists(outside.resolve("secret.txt")));
        assertTrue(result.mutationReceipt().isEmpty());
    }

    /**
     * 写入后 parent 被竞争替换为 junction 时，不得把失败伪装为“无修改”；外部文件、Workspace
     * pre/postimage 和物理路径都不能进入模型可见结果，Runner 只消费闭集 outside observer。
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void reportsOutsideObservationWhenParentBecomesJunctionAfterWrite() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("race-workspace"));
        Path parent = Files.createDirectory(workspace.resolve("parent"));
        Path displaced = workspace.resolve("parent-original");
        Files.writeString(parent.resolve("note.txt"), "workspace-secret-before");
        Path outside = Files.createDirectory(temporary.resolve("race-outside"));
        Path outsideTarget = Files.writeString(outside.resolve("note.txt"), "external-secret");
        BuiltInTools.MutationWriter racingWriter = (path, content, ignoredBoundary,
                                                    ignoredToken, ignoredDeadline) -> {
            Files.writeString(path, content);
            Files.move(parent, displaced);
            try {
                if (createJunction(parent, outside) != 0) {
                    throw new java.io.IOException("junction_failed");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("junction_interrupted", failure);
            }
        };
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments(), racingWriter);

        JaTool.ToolResult result = execute(registry, "write", JsonObjects.builder()
                .putText("path", "parent/note.txt").putText("content", "workspace-secret-after").build());

        assertEquals(ToolOutcome.FAILED, result.outcome());
        assertEquals("tool_access_denied", result.errorCode());
        assertEquals(JaTool.MutationObservationFailure.OUTSIDE_WORKSPACE,
                result.mutationObservationFailure().orElseThrow());
        assertTrue(result.mutationReceipt().isEmpty());
        assertEquals("external-secret", Files.readString(outsideTarget));
        assertEquals("workspace-secret-after", Files.readString(displaced.resolve("note.txt")));
        String visible = result.content() + result.structuredContent() + result;
        assertFalse(visible.contains(workspace.toString()));
        assertFalse(visible.contains("workspace-secret-before"));
        assertFalse(visible.contains("workspace-secret-after"));
        assertFalse(visible.contains("external-secret"));
    }

    /** 重复 oldText 是可纠正的编辑输入错误，不应伪装成文件系统故障。 */
    @Test
    void editRequiresUniqueOldText() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("unique-workspace"));
        Files.writeString(workspace.resolve("duplicate.txt"), "same same");
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments());
        JaTool.ToolResult result = execute(registry, "edit",
                JsonObjects.builder().putText("path", "duplicate.txt")
                        .put("edits", edits(replacement("same", "next"))).build());
        assertEquals("tool_arguments_invalid", result.errorCode());
        assertTrue(result.content().contains("Argument 'edits' oldText must occur exactly once in the current file."));
        assertFalse(result.content().contains("Diagnostic ID:"));
        assertEquals("same same", Files.readString(workspace.resolve("duplicate.txt")));
        assertThrows(IllegalArgumentException.class,
                () -> registry.require(invocation("read_file", JsonObject.empty())));
    }

    /** oldText 不存在时保留原文件，并提示模型重新读取当前内容后再生成匹配文本。 */
    @Test
    void editReportsMissingOldTextAsCorrectableArgumentFailure() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("missing-edit-workspace"));
        Path source = Files.writeString(workspace.resolve("source.txt"), "current content");
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS,
                catalog(), promptSession(), unusedAttachments());

        JaTool.ToolResult result = execute(registry, "edit", JsonObjects.builder().putText("path", "source.txt")
                .put("edits", edits(replacement("outdated content", "replacement"))).build());

        assertEquals(ToolOutcome.FAILED, result.outcome());
        assertEquals("tool_arguments_invalid", result.errorCode());
        assertTrue(result.content().contains("oldText must occur exactly once in the current file."));
        assertFalse(result.content().contains("Diagnostic ID:"));
        assertEquals("current content", Files.readString(source));
    }

    /** 多个 edit 必须对同一 preimage 定位、拒绝重叠，并以一份精确收据原子提交。 */
    @Test
    void editsMultipleNonOverlappingBlocksAtomically() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("batch-edit-workspace"));
        Path source = workspace.resolve("source.txt");
        Files.writeString(source, "alpha\nunchanged\nomega\n");
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments());

        JaTool.ToolResult success = execute(registry, "edit", JsonObjects.builder().putText("path", "source.txt")
                .put("edits", edits(replacement("alpha", "ALPHA"), replacement("omega", "OMEGA"))).build());
        JaTool.ToolResult overlap = execute(registry, "edit", JsonObjects.builder().putText("path", "source.txt")
                .put("edits", edits(replacement("ALPHA\nunchanged", "first"),
                        replacement("ALPHA\nunchanged\nOMEGA", "second"))).build());

        assertEquals(ToolOutcome.SUCCEEDED, success.outcome(), success::toString);
        assertEquals("Successfully replaced 2 block(s) in the file.", success.content());
        assertReceipt(success, source, true, "alpha\nunchanged\nomega\n", "ALPHA\nunchanged\nOMEGA\n");
        assertEquals("tool_arguments_invalid", overlap.errorCode());
        assertTrue(overlap.content().contains("oldText matches must not overlap."));
        assertFalse(overlap.content().contains("Diagnostic ID:"));
        assertEquals("ALPHA\nunchanged\nOMEGA\n", Files.readString(source));
    }

    /** read 的行请求再大也必须保持 Pi 风格的字节/行双预算，并向模型给出可用的后续 offset。 */
    @Test
    void readCapsVisibleBytesAndReturnsContinuationMetadata() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("read-budget-workspace"));
        Path source = workspace.resolve("long-lines.txt");
        StringBuilder content = new StringBuilder();
        for (int index = 0; index < 200; index++) {
            content.append("界".repeat(1_023)).append("😀").append('\n');
        }
        Files.writeString(source, content);
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments());

        JaTool.ToolResult result = execute(registry, "read", JsonObjects.builder()
                .putText("path", "long-lines.txt").putNumber("limit", 100_000).build());
        JsonObject metadata = (JsonObject) result.structuredContent().orElseThrow();

        assertTrue(result.content().getBytes(StandardCharsets.UTF_8).length <= 64 * 1024,
                () -> Integer.toString(result.content().length()));
        assertEquals(new JsonText("byte_limit"), metadata.get("termination"));
        assertTrue(metadata.get("nextOffset") instanceof JsonNumber, metadata::toString);
        assertTrue(((JsonBoolean) metadata.get("truncated")).value(), metadata::toString);
        assertTrue(result.content().contains("Use offset="), result::content);
    }

    /** 首行超过 read 正文预算时行 offset 无法前进，必须终止并明确提示改用有界 Shell 范围查询。 */
    @Test
    void readTerminatesWhenFirstLineExceedsVisibleBudget() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("read-long-line-workspace"));
        Path source = workspace.resolve("single-long-line.txt");
        Files.writeString(source, "界".repeat(21_800));
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments());

        JaTool.ToolResult result = execute(registry, "read", JsonObjects.builder()
                .putText("path", "single-long-line.txt").build());
        JsonObject metadata = (JsonObject) result.structuredContent().orElseThrow();

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome(), result::content);
        assertTrue(result.content().getBytes(StandardCharsets.UTF_8).length <= 65_536, result::content);
        assertEquals(new JsonText("line_too_long"), metadata.get("termination"));
        assertEquals(new JsonBoolean(true), metadata.get("truncated"));
        assertFalse(metadata.containsKey("nextOffset"), metadata::toString);
        assertTrue(result.content().contains("Use shell to inspect a bounded range."), result::content);
        assertFalse(result.content().contains("Use offset="), result::content);
    }

    /** read 只返回内容与范围元数据，文件字节和修改时间都不能形成任何修改事实。 */
    @Test
    void readLeavesWorkspaceFileUnchanged() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("read-only-workspace"));
        Path source = workspace.resolve("source.txt");
        Files.writeString(source, "unchanged");
        java.nio.file.attribute.FileTime modifiedAt = Files.getLastModifiedTime(source);
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments());

        JaTool.ToolResult result = execute(registry, "read",
                JsonObjects.builder().putText("path", "source.txt").build());

        assertEquals("unchanged", result.content());
        assertEquals("unchanged", Files.readString(source));
        assertEquals(modifiedAt, Files.getLastModifiedTime(source));
    }

    /** read 对缺失路径和目录返回不同稳定码，且安全正文不回显模型提交的敏感路径片段。 */
    @Test
    void readDistinguishesMissingPathFromDirectory() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("read-path-errors"));
        Path directory = Files.createDirectory(workspace.resolve("private-directory-name"));
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments());

        JaTool.ToolResult missing = execute(registry, "read",
                JsonObjects.builder().putText("path", "private-missing-name.txt").build());
        JaTool.ToolResult directoryResult = execute(registry, "read",
                JsonObjects.builder().putText("path", directory.toString()).build());

        assertFailure(missing, "path_not_found", "private-missing-name.txt");
        assertFailure(directoryResult, "path_is_directory", "private-directory-name");
    }

    /** read 同时限制原始字节和解码后字符，并把非法 UTF-8 与容量超限区分为可操作失败。 */
    @Test
    void readRejectsOversizedAndMalformedUtf8Files() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("read-content-errors"));
        Path oversized = workspace.resolve("oversized-secret.txt");
        try (RandomAccessFile file = new RandomAccessFile(oversized.toFile(), "rw")) {
            file.setLength(16_000_001L);
        }
        Path tooManyCharacters = workspace.resolve("too-many-characters-secret.txt");
        Files.writeString(tooManyCharacters, "a".repeat(4_000_001));
        Path malformed = workspace.resolve("malformed-secret.txt");
        Files.write(malformed, new byte[]{(byte) 0xC3, 0x28});
        Path binary = workspace.resolve("binary-secret.txt");
        Files.write(binary, new byte[]{'o', 'k', 0});
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), unusedAttachments());

        JaTool.ToolResult tooLarge = execute(registry, "read",
                JsonObjects.builder().putText("path", oversized.toString()).build());
        JaTool.ToolResult tooLong = execute(registry, "read",
                JsonObjects.builder().putText("path", tooManyCharacters.toString()).build());
        JaTool.ToolResult invalidUtf8 = execute(registry, "read",
                JsonObjects.builder().putText("path", malformed.toString()).build());
        JaTool.ToolResult notText = execute(registry, "read",
                JsonObjects.builder().putText("path", binary.toString()).build());

        assertFailure(tooLarge, "file_too_large", "oversized-secret.txt");
        assertFailure(tooLong, "file_too_large", "too-many-characters-secret.txt");
        assertFailure(invalidUtf8, "file_not_utf8", "malformed-secret.txt");
        assertFailure(notText, "file_not_text", "binary-secret.txt");
    }

    /** Skill URI 必须经冻结目录实时读取并激活，避免把模型可见的逻辑地址误当成本地文件路径。 */
    @Test
    void readsSkillUriThroughCatalogAndActivatesPromptSession() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("skill-workspace"));
        SkillCatalog skills = new SkillCatalog(List.of(new SingleSkill()));
        SkillCatalog.Catalog catalog = skills.discover(SKILL_CONTEXT, Set.of());
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), skills, catalog,
                promptSession(), unusedAttachments());

        JaTool.ToolResult result = execute(registry, "read",
                JsonObjects.builder().putText("path", "skill://updeng-workflow").build());

        assertEquals("activated fixture skill", result.content());
    }

    /** Workspace Tool 集不受宿主 Shell 能力状态影响。 */
    @Test
    void exposesOnlyWorkspaceTools() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("no-shell-workspace"));
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(), promptSession(),
                unusedAttachments());
        assertEquals(List.of("edit", "find", "grep", "ls", "read", "read_attachment", "view_image", "write"),
                registry.snapshot().stream().map(tool -> tool.spec().name()).toList());
    }

    /** read_attachment 只能把模型参数与冻结 Thread 身份组合，且图片仍通过 Base64 Tool 路由。 */
    @Test
    void readsAttachmentThroughBoundedToolWithContextThreadIdentity() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("attachment-workspace"));
        AtomicReference<ManagedAttachmentReader.ReadRequest> observed = new AtomicReference<>();
        ToolRegistry registry = createBuiltIns(workspace, new WorkspaceBoundaryFactoryAdapter(), EMPTY_SKILLS, catalog(),
                promptSession(), visibleAttachment(observed));

        JaTool.ToolResult result = execute(registry, "read_attachment", JsonObjects.builder()
                .putText("attachmentId", "att_fixture")
                .putNumber("offsetBytes", 8).putNumber("maxBytes", 64).build());

        assertEquals("thr_fixture", observed.get().threadId());
        assertEquals(8, observed.get().offsetBytes());
        assertEquals(64, observed.get().maxBytes());
        assertEquals("AAEC", result.content());
    }

    /** 通过真实 JaTool 端口执行，避免测试私有实现细节。 */
    private JaTool.ToolResult execute(ToolRegistry registry, String name, JsonObject arguments) {
        return execute(registry, name, arguments, CancellationToken.none(), context());
    }

    /** 为取消与 Deadline 回归注入调用边界，仍通过真实 JaTool 公共入口执行。 */
    private JaTool.ToolResult execute(ToolRegistry registry, String name, JsonObject arguments,
                                         CancellationToken token, JaTool.ExecutionContext executionContext) {
        AgentTool.Invocation invocation = invocation(name, arguments);
        return registry.require(invocation).execute(invocation, executionContext, token)
                .toCompletableFuture().join();
    }

    /** 统一验证失败同时携带稳定机器码、非空操作指引，并拒绝泄露输入中的敏感路径。 */
    private static void assertFailure(JaTool.ToolResult result, String code, String sensitivePath) {
        assertEquals(code, result.errorCode());
        assertFalse(result.content().isBlank());
        assertFalse(result.content().contains(sensitivePath));
    }

    /** 收据只通过专用字段读取；路径比较物理身份，不能把 Windows 短路径的拼写当成泄漏或越界。 */
    private static void assertReceipt(JaTool.ToolResult result, Path path, boolean beforeExists,
                                      String before, String after) throws IOException {
        JaTool.MutationReceipt receipt = result.mutationReceipt().orElseThrow();
        assertTrue(Files.isSameFile(path, receipt.path()));
        assertEquals(beforeExists, receipt.beforeExists());
        assertEquals(before, receipt.beforeText());
        assertEquals(after, receipt.afterText());
        assertTrue(result.structuredContent().isEmpty());
        String publicProjection = result.content() + result;
        assertFalse(publicProjection.contains(path.toString()));
        if (!before.isEmpty()) {
            assertFalse(publicProjection.contains(before));
        }
        assertFalse(publicProjection.contains(after));
        assertFalse(publicProjection.contains(receipt.afterSha256()));
    }

    /** 构造严格 JSON 数组，使所有批量 edit 测试走 Provider 实际提交的嵌套对象形状。 */
    private static JsonArray edits(JsonObject first, JsonObject... remaining) {
        List<JsonValue> values = new java.util.ArrayList<>();
        values.add(first);
        for (JsonObject replacement : remaining) {
            values.add(replacement);
        }
        return new JsonArray(values);
    }

    /** 保持 oldText/newText 成对出现，避免测试夹具无意绕开 edit 的封闭对象约束。 */
    private static JsonObject replacement(String oldText, String newText) {
        return JsonObjects.builder().putText("oldText", oldText).putText("newText", newText).build();
    }

    /** 每次调用使用合法稳定身份，测试只变化 Tool 名和参数。 */
    private static AgentTool.Invocation invocation(String name, JsonObject arguments) {
        return new AgentTool.Invocation("call_fixture", name, arguments, 0);
    }

    /** Workspace 仅作为相对路径基准；权限值不改变文件 Tool 路径解析。 */
    private JaTool.ExecutionContext context() {
        return context(Instant.now().plusSeconds(30));
    }

    /** Deadline 可变但 Core 与 Ja 身份同源，避免文件工具回归使用错配的会话字段。 */
    private JaTool.ExecutionContext context(Instant deadline) {
        AgentIdentity identity = new AgentIdentity("tenant_fixture", "thr_fixture", "turn_fixture", "user_fixture");
        String callId = "call_fixture";
        return new JaTool.ExecutionContext(identity, callId, identity.toolCallKey(callId),
                "thr_fixture", "turn_fixture", temporary.toAbsolutePath(), AccessMode.FULL_ACCESS,
                "cfg_fixture", deadline, "ws_fixture", null, null, TurnOrigin.USER);
    }

    /** 持久化收据只保留相对路径和版本化文件证据，不在 tracker 测试中传递 Path DTO。 */
    private static JsonObject persistedReceipt(JaTool.MutationReceipt receipt, String workspaceId) {
        return JsonObjects.builder().putText("workspaceId", workspaceId)
                .putText("relativePath", receipt.confinedRelativePath())
                .putBoolean("beforeExists", receipt.beforeExists()).putBoolean("afterExists", receipt.afterExists())
                .putText("beforeText", receipt.beforeText()).putText("afterText", receipt.afterText())
                .putNumber("beforeBytes", receipt.beforeBytes()).putNumber("afterBytes", receipt.afterBytes())
                .putText("beforeSha256", receipt.beforeSha256()).putText("afterSha256", receipt.afterSha256())
                .putBoolean("confined", receipt.confinedWorkspaceRoot() != null).build();
    }

    /** 提供在首次检查即取消的令牌，验证发现 Tool 不会先构造或遍历文件快照。 */
    private static CancellationToken cancelled() {
        return new CancellationToken() {
            /** 令牌始终报告已取消，覆盖 ToolSupport 的首个取消边界。 */
            @Override
            public boolean isCancellationRequested() {
                return true;
            }

            /** 返回固定测试原因，避免把测试路径送入 Tool 结果。 */
            @Override
            public java.util.Optional<String> reason() {
                return java.util.Optional.of("test");
            }

            /** 测试令牌不保留回调，避免临时目录测试结束后残留引用。 */
            @Override
            public Registration onCancellation(Runnable callback) {
                return Registration.noop();
            }
        };
    }

    /** 在指定数量的边界检查后单调切换为取消，用于稳定命中分块写入的中途检查。 */
    private static CancellationToken cancelledAfterChecks(int allowedChecks) {
        if (allowedChecks < 0) {
            throw new IllegalArgumentException("allowed cancellation checks must be non-negative");
        }
        AtomicInteger checks = new AtomicInteger();
        return new CancellationToken() {
            /** 超过允许的检查次数后保持取消状态，保证测试确定性地中断第二个写块。 */
            @Override
            public boolean isCancellationRequested() {
                return checks.incrementAndGet() > allowedChecks;
            }

            /** 固定测试原因不携带本地文件信息。 */
            @Override
            public java.util.Optional<String> reason() {
                return checks.get() > allowedChecks ? java.util.Optional.of("test") : java.util.Optional.empty();
            }

            /** 该测试令牌不注册回调，避免临时文件用例保留额外生命周期。 */
            @Override
            public Registration onCancellation(Runnable callback) {
                return Registration.noop();
            }
        };
    }

    /** Shell 本测试不执行，仅需冻结一份合法 Profile 供注册表创建。 */
    private static ShellCapability shellCapability() {
        return ShellCapability.available(new ShellProfile(ShellProfile.OperatingSystem.WINDOWS,
                ShellProfile.Dialect.POWERSHELL,
                Path.of(System.getProperty("java.home"), "bin", "java.exe"), List.of(), "windows",
                Map.of("PATHEXT", ".EXE;.CMD")));
    }

    /** 通过显式 fake 满足图片 Tool 的受管导入依赖，普通文件用例不会调用该能力。 */
    private static final ManagedToolMediaImporter TEST_MEDIA_IMPORTER = (workspaceId, displayName, mediaType,
                                                                         content) -> new ToolMediaReference(
            "att_test", mediaType, displayName, content.length);

    /** 所有普通文件测试均显式绑定 fake media importer，避免无导入端口的生产降级重载。 */
    private static ToolRegistry createBuiltIns(Path workspaceRoot,
                                               WorkspaceBoundaryFactory boundaries,
                                               SkillCatalog skills, SkillCatalog.Catalog catalog,
                                               AgentPromptSession promptSession,
                                               ManagedAttachmentReader attachments) {
        return BuiltInTools.create(workspaceRoot, boundaries, skills, catalog, promptSession,
                attachments, TEST_MEDIA_IMPORTER);
    }

    /** 写路径竞态测试继续替换唯一 mutation IO，同时明确提供不访问真实附件存储的 media fake。 */
    private static ToolRegistry createBuiltIns(Path workspaceRoot,
                                               WorkspaceBoundaryFactory boundaries,
                                               SkillCatalog skills, SkillCatalog.Catalog catalog,
                                               AgentPromptSession promptSession,
                                               ManagedAttachmentReader attachments,
                                               BuiltInTools.MutationWriter mutationWriter) {
        return BuiltInTools.create(workspaceRoot, boundaries, skills, catalog, promptSession,
                attachments, TEST_MEDIA_IMPORTER, mutationWriter);
    }

    /** 空目录证明普通文件读取不会反向发现 Skill。 */
    private static SkillCatalog.Catalog catalog() {
        return EMPTY_CATALOG;
    }

    /** 文件 Tool 测试不覆盖 Prompt 刷新，固定 Session 只满足当前生产边界。 */
    private static FixedAgentPromptSession promptSession() {
        return new FixedAgentPromptSession(ContextBudget.capabilities(1_000_000, 8_192, true));
    }

    /** 普通文件用例不读取附件，严格代理确保 Tool 被意外调用时立即失败。 */
    private static ManagedAttachmentReader unusedAttachments() {
        return (ManagedAttachmentReader) java.lang.reflect.Proxy.newProxyInstance(
                BuiltInToolsTest.class.getClassLoader(), new Class<?>[]{ManagedAttachmentReader.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("unexpected attachment access: " + method.getName());
                });
    }

    /** 返回消费者自有的窄读取端口，测试不再反向依赖附件切片的入站用例。 */
    private static ManagedAttachmentReader visibleAttachment(
            AtomicReference<ManagedAttachmentReader.ReadRequest> observed) {
        return request -> {
            observed.set(request);
            return new ManagedAttachmentReader.ReadResult(
                    "att_fixture", "image.png", 11, "image", "image/png",
                    request.offsetBytes(), 11, true, "base64", "AAEC");
        };
    }

    /**
     * Windows 回归必须使用操作系统实际返回的 8.3 alias，避免用字符串拼接伪造路径表示；
     * 未启用 8.3 命名时跳过该专属夹具，普通路径测试仍覆盖其它平台的行为。
     */
    private static Path windowsShortPath(Path path) throws IOException, InterruptedException {
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            return path;
        }
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c",
                "for %I in (\"" + path.toAbsolutePath() + "\") do @echo %~sI")
                .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS), "short path probe timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            assertEquals(0, process.exitValue());
            String shortPath = output.lines().findFirst().orElse("").trim();
            assertFalse(shortPath.isBlank());
            return Path.of(shortPath);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** 使用 Windows 原生 junction 机制，避免 symlink 开发者权限影响 reparse point 回归覆盖。 */
    private static int createJunction(Path junction, Path target) throws java.io.IOException, InterruptedException {
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                junction.toString(), target.toString()).redirectErrorStream(true).start();
        return process.waitFor();
    }

    /** 只暴露逻辑 Skill URI 的来源，确保 Tool 通过 core 的 catalog-bound read 路径取正文。 */
    private static final class SingleSkill implements SkillSource {
        /** 固定 source ID，令请求身份与 `skill://updeng-workflow` 的展示名相互独立。 */
        @Override public String id() { return "bundled"; }
        /** 内置来源优先级最低，fixture 不引入额外覆盖分支。 */
        @Override public int priority() { return 0; }

        /** 元数据阶段只公开一条声明，正文仍留待 read Tool 实际调用。 */
        @Override
        public SkillPage discover(SkillRequestContext context, String cursor, int pageSize) {
            assertEquals(null, cursor);
            return new SkillPage(List.of(new SkillDescriptor(
                    "updeng-workflow", "Workflow fixture", id(), 1)), null);
        }

        /** 返回固定资源并校验 Skill URI 没有被改写成普通文件路径。 */
        @Override
        public SkillResourceContent read(SkillRequestContext context, SkillDescriptor descriptor,
                                         SkillReadRequest request) {
            assertEquals("updeng-workflow", request.skillName());
            assertEquals("SKILL.md", request.resourcePath());
            return new SkillResourceContent("workflow body", false);
        }
    }
}
