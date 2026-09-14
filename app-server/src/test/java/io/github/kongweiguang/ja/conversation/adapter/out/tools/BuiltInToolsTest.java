// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import io.github.kongweiguang.ja.conversation.application.change.TurnChangeTracker;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.ManagedAttachmentReader;
import io.github.kongweiguang.ja.conversation.port.out.SkillCatalog;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.conversation.domain.ContextBudget;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证基础文件 Tool 集、Workspace 外路径语义和有界发现行为。 */
class BuiltInToolsTest {
    @TempDir Path temporary;

    /** Shell 可用时内置名称固定；write/read/edit 可通过绝对路径和 .. 访问隔离外部目录。 */
    @Test
    void exposesFourToolsAndAllowsPathsOutsideWorkspace() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), catalog(), shellCapability(),
                promptSession(), unusedAttachments());
        assertEquals(List.of("edit", "find", "grep", "ls", "read", "read_attachment", "shell", "write"),
                registry.snapshot().stream().map(tool -> tool.spec().name()).toList());

        execute(registry, "write", JsonObjects.builder()
                .putText("path", outside.resolve("note.txt").toString()).putText("content", "one").build());
        assertEquals("one", execute(registry, "read",
                JsonObjects.builder().putText("path", "../outside/note.txt").build()).content());
        execute(registry, "edit", JsonObjects.builder()
                .putText("path", "../outside/note.txt").putText("oldText", "one").putText("newText", "two")
                .build());
        assertEquals("two", Files.readString(outside.resolve("note.txt")));
    }

    /** grep 只做字面量内容匹配；省略 filePattern 时默认搜索所有文件。 */
    @Test
    void grepsWorkspaceFilesWithLiteralQueryAndDefaultPattern() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("search-workspace"));
        Path nested = Files.createDirectory(workspace.resolve("nested"));
        Files.writeString(workspace.resolve("Plan.java"), "class Plan {\n  // a+b marker\n}\n", StandardCharsets.UTF_8);
        Files.writeString(nested.resolve("notes.md"), "a+b marker in markdown\n", StandardCharsets.UTF_8);
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), catalog(), shellCapability(),
                promptSession(), unusedAttachments());

        AgentTool.ToolResult result = execute(registry, "grep", JsonObjects.builder()
                .putText("query", "a+b").build());

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertTrue(result.content().contains("Plan.java:2"));
        assertTrue(result.content().contains("scannedFiles=2"));
        assertTrue(result.content().contains("nested/notes.md:1"));

        AgentTool.ToolResult javaOnly = execute(registry, "grep", JsonObjects.builder()
                .putText("query", "a+b").putText("filePattern", "*.java").build());
        assertTrue(javaOnly.content().contains("Plan.java:2"));
        assertFalse(javaOnly.content().contains("notes.md:1"));
        assertEquals(AgentTool.WorkspaceMutationMode.NONE,
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
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), catalog(), shellCapability(),
                promptSession(), unusedAttachments());

        AgentTool.ToolResult result = execute(registry, "find", JsonObjects.builder()
                .putText("pattern", "*.java").build());

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertTrue(result.content().contains("src/Main.java"));
        assertFalse(result.content().contains("c3"));
        assertTrue(result.content().contains("scannedBytes=0"));
    }

    /** ls 只列目标目录一层，并保留目录类型；子目录正文/后代不会被物化。 */
    @Test
    void listsOnlyOneDirectoryLevel() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("ls-workspace"));
        Path child = Files.createDirectory(workspace.resolve("child"));
        Files.writeString(child.resolve("nested.txt"), "nested");
        Files.writeString(workspace.resolve("root.txt"), "root");
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), catalog(), shellCapability(),
                promptSession(), unusedAttachments());

        AgentTool.ToolResult result = execute(registry, "ls", JsonObjects.builder().build());

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertTrue(result.content().contains("child\tdirectory"));
        assertTrue(result.content().contains("root.txt\tfile"));
        assertFalse(result.content().contains("nested.txt"));
        assertTrue(result.content().contains("scannedBytes=0"));
    }

    /** JSON Schema maxLength 按 Unicode code point 计数，代理项不应把合法 emoji 双重计数。 */
    @Test
    void validatesStringLimitsByUnicodeCodePoint() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("unicode-argument-workspace"));
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), catalog(), shellCapability(),
                promptSession(), unusedAttachments());

        String maximumQuery = "😀".repeat(512);
        AgentTool.ToolResult accepted = execute(registry, "grep", JsonObjects.builder()
                .putText("query", maximumQuery).build());
        AgentTool.ToolResult rejected = execute(registry, "grep", JsonObjects.builder()
                .putText("query", maximumQuery + "😀").build());

        assertEquals(ToolOutcome.SUCCEEDED, accepted.outcome());
        assertEquals("tool_arguments_invalid", rejected.errorCode());
        assertTrue(rejected.content().contains("query"));
    }

    /** grep 跳过非法 UTF-8 候选时必须显式报告 partial，不能把无匹配误报为完整扫描。 */
    @Test
    void reportsSkippedUnreadableFilesAsPartial() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("grep-unreadable-workspace"));
        Files.write(workspace.resolve("malformed.txt"), new byte[]{(byte) 0xc3, 0x28});
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), catalog(), shellCapability(),
                promptSession(), unusedAttachments());

        AgentTool.ToolResult result = execute(registry, "grep", JsonObjects.builder()
                .putText("query", "not-present").build());

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertTrue(result.content().contains("skippedEntries=1"));
        assertTrue(result.content().contains("truncated=true"));
        assertTrue(result.content().contains("termination=unreadable_file"));
        assertTrue(result.content().contains("Results are partial"));
    }

    /** grep 的结果上限必须返回部分事实和明确原因，而不是静默丢弃后续命中。 */
    @Test
    void grepHonorsResultBudgetAndReportsTruncation() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("grep-budget-workspace"));
        Files.writeString(workspace.resolve("markers.txt"), "marker\nmarker\nmarker\n");
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), catalog(), shellCapability(),
                promptSession(), unusedAttachments());

        AgentTool.ToolResult result = execute(registry, "grep", JsonObjects.builder()
                .putText("query", "marker").putNumber("maxResults", 1).build());

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertEquals(1, result.content().lines().filter(line -> line.contains("markers.txt:")).count());
        assertTrue(result.content().contains("truncated=true"));
        assertTrue(result.content().contains("termination=result_limit"));
    }

    /** 空 query、非法 Glob 和普通文件 path 都在执行边界返回字段化、可纠正错误。 */
    @Test
    void rejectsInvalidDiscoveryArgumentsWithSpecificConstraints() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("discovery-arguments-workspace"));
        Files.writeString(workspace.resolve("one.txt"), "one");
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), catalog(), shellCapability(),
                promptSession(), unusedAttachments());

        AgentTool.ToolResult emptyQuery = execute(registry, "grep", JsonObjects.builder()
                .putText("query", "").build());
        AgentTool.ToolResult badGlob = execute(registry, "find", JsonObjects.builder()
                .putText("pattern", "[").build());
        AgentTool.ToolResult filePath = execute(registry, "ls", JsonObjects.builder()
                .putText("path", "one.txt").build());

        assertEquals("tool_arguments_invalid", emptyQuery.errorCode());
        assertTrue(emptyQuery.content().contains("query"));
        assertTrue(emptyQuery.content().contains("find"));
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
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), catalog(), shellCapability(),
                promptSession(), unusedAttachments());

        AgentTool.ToolResult result = execute(registry, "find", JsonObjects.builder()
                .putText("pattern", "*.txt").build(), cancelled(), context());

        assertEquals(ToolOutcome.CANCELLED, result.outcome());
        assertEquals("tool_cancelled", result.errorCode());
    }

    /** 遍历中的取消必须在 collect 阶段生效，而不是等到首个结果投影后才发现。 */
    @Test
    void discoveryHonorsCancellationDuringDirectoryWalk() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("discovery-walk-cancel-workspace"));
        for (int index = 0; index < 16; index++) {
            Path directory = Files.createDirectory(workspace.resolve("dir-" + index));
            Files.writeString(directory.resolve("file.txt"), "marker-" + index);
        }
        CountingCancellationToken token = cancelAfterChecks(8);
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), catalog(), shellCapability(),
                promptSession(), unusedAttachments());

        AgentTool.ToolResult result = execute(registry, "find", JsonObjects.builder()
                .putText("pattern", "*.txt").putNumber("maxResults", 1).build(), token, context());

        assertEquals(ToolOutcome.CANCELLED, result.outcome());
        assertEquals("tool_cancelled", result.errorCode());
        assertTrue(token.checks() > 8, "cancellation must be observed after entering directory traversal");
    }

    /** 过期 Deadline 返回 bounded 截断摘要，模型不会把未执行扫描误判成空目录。 */
    @Test
    void discoveryHonorsDeadlineWithExplicitTruncation() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("discovery-deadline-workspace"));
        Files.writeString(workspace.resolve("one.txt"), "one");
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), catalog(), shellCapability(),
                promptSession(), unusedAttachments());
        AgentTool.ExecutionContext expired = context(Instant.now().minusMillis(1));

        AgentTool.ToolResult result = execute(registry, "find", JsonObjects.builder()
                .putText("pattern", "*.txt").build(), CancellationToken.none(), expired);

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertTrue(result.content().contains("truncated=true"));
        assertTrue(result.content().contains("termination=deadline"));
        assertFalse(result.content().contains("one.txt\n"));
    }

    /** Workspace 内的无关 symlink 被跳过并记录，显式 symlink path 仍由 Boundary 拒绝。 */
    @Test
    void discoverySkipsUnrelatedSymbolicLinks() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("discovery-link-workspace"));
        Path outside = Files.createDirectory(temporary.resolve("discovery-link-outside"));
        Files.writeString(workspace.resolve("README.md"), "readme");
        Path link = workspace.resolve("node_modules-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException unavailable) {
            Assumptions.assumeTrue(false, "当前文件系统不能创建 symlink: "
                    + unavailable.getClass().getSimpleName());
        }
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), catalog(), shellCapability(),
                promptSession(), unusedAttachments());

        AgentTool.ToolResult result = execute(registry, "find", JsonObjects.builder()
                .putText("pattern", "README.md").build());

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertTrue(result.content().contains("README.md"));
        assertTrue(result.content().contains("skippedEntries=1"));
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
        ToolRegistry registry = BuiltInTools.create(workspaceInput, new EmptySkills(), catalog(), shellCapability(),
                promptSession(), unusedAttachments());

        AgentTool.ToolResult written = execute(registry, "write", JsonObjects.builder()
                .putText("path", target.toString()).putText("content", "secret-before").build());
        AgentTool.ToolResult edited = execute(registry, "edit", JsonObjects.builder()
                .putText("path", target.toString()).putText("oldText", "secret-before")
                .putText("newText", "secret-after").build());

        assertEquals(AgentTool.WorkspaceMutationMode.EXACT_TEXT,
                registry.snapshot().stream().filter(tool -> "write".equals(tool.spec().name())).findFirst()
                        .orElseThrow().workspaceMutationMode());
        assertEquals(AgentTool.WorkspaceMutationMode.EXACT_TEXT,
                registry.snapshot().stream().filter(tool -> "edit".equals(tool.spec().name())).findFirst()
                        .orElseThrow().workspaceMutationMode());
        assertEquals(AgentTool.WorkspaceMutationMode.NONE,
                registry.snapshot().stream().filter(tool -> "read".equals(tool.spec().name())).findFirst()
                        .orElseThrow().workspaceMutationMode());
        assertEquals(AgentTool.WorkspaceMutationMode.NONE,
                registry.snapshot().stream().filter(tool -> "read_attachment".equals(tool.spec().name())).findFirst()
                        .orElseThrow().workspaceMutationMode());
        assertEquals(AgentTool.WorkspaceMutationMode.UNOBSERVABLE,
                registry.snapshot().stream().filter(tool -> "shell".equals(tool.spec().name())).findFirst()
                        .orElseThrow().workspaceMutationMode());
        assertEquals(ToolSideEffect.READ_ONLY,
                registry.snapshot().stream().filter(tool -> "read".equals(tool.spec().name())).findFirst()
                        .orElseThrow().sideEffect());
        assertEquals(ToolSideEffect.READ_ONLY,
                registry.snapshot().stream().filter(tool -> "read_attachment".equals(tool.spec().name())).findFirst()
                        .orElseThrow().sideEffect());
        for (String toolName : List.of("edit", "shell", "write")) {
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
        ToolRegistry registry = BuiltInTools.create(namespacedWorkspace, new EmptySkills(), catalog(),
                shellCapability(), promptSession(), unusedAttachments());

        AgentTool.ToolResult result = execute(registry, "write", JsonObjects.builder()
                .putText("path", target.toString()).putText("content", "JA_TURN_CHANGE_REVISION_000").build());
        TurnChangeTracker tracker = TurnChangeTracker.fresh(namespacedWorkspace);

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome(), result::toString);
        tracker.apply(result.mutationReceipt().orElseThrow());
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
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), catalog(), shellCapability(),
                promptSession(), unusedAttachments());

        AgentTool.ToolResult result = execute(registry, "write", JsonObjects.builder()
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
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), catalog(), shellCapability(),
                promptSession(), unusedAttachments());

        AgentTool.ToolResult result = execute(registry, "write", JsonObjects.builder()
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
        BuiltInTools.MutationWriter racingWriter = (path, content, ignoredBoundary) -> {
            Files.writeString(path, content);
            Files.move(parent, displaced);
            try {
                if (createJunction(parent, outside) != 0) throw new java.io.IOException("junction_failed");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("junction_interrupted", failure);
            }
        };
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), catalog(), shellCapability(),
                promptSession(), unusedAttachments(), racingWriter);

        AgentTool.ToolResult result = execute(registry, "write", JsonObjects.builder()
                .putText("path", "parent/note.txt").putText("content", "workspace-secret-after").build());

        assertEquals(ToolOutcome.FAILED, result.outcome());
        assertEquals("tool_access_denied", result.errorCode());
        assertEquals(AgentTool.MutationObservationFailure.OUTSIDE_WORKSPACE,
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

    /** edit 对多重匹配返回失败结果，不能静默修改任意一个位置。 */
    @Test
    void editRequiresUniqueOldText() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("unique-workspace"));
        Files.writeString(workspace.resolve("duplicate.txt"), "same same");
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), catalog(), shellCapability(),
                promptSession(), unusedAttachments());
        AgentTool.ToolResult result = execute(registry, "edit",
                JsonObjects.builder().putText("path", "duplicate.txt").putText("oldText", "same")
                        .putText("newText", "next").build());
        assertEquals("tool_io_failed", result.errorCode());
        assertTrue(result.content().startsWith(
                "Tool failed: tool_io_failed. The operating system could not complete the operation; "));
        assertTrue(result.content().matches("(?s).*Diagnostic ID: diag_[0-9a-f]{32}\\.$"));
        assertEquals("same same", Files.readString(workspace.resolve("duplicate.txt")));
        assertThrows(IllegalArgumentException.class,
                () -> registry.require(invocation("read_file", JsonObject.empty())));
    }

    /** read 只返回内容与范围元数据，文件字节和修改时间都不能形成任何修改事实。 */
    @Test
    void readLeavesWorkspaceFileUnchanged() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("read-only-workspace"));
        Path source = workspace.resolve("source.txt");
        Files.writeString(source, "unchanged");
        java.nio.file.attribute.FileTime modifiedAt = Files.getLastModifiedTime(source);
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), catalog(), shellCapability(),
                promptSession(), unusedAttachments());

        AgentTool.ToolResult result = execute(registry, "read",
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
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), catalog(), shellCapability(),
                promptSession(), unusedAttachments());

        AgentTool.ToolResult missing = execute(registry, "read",
                JsonObjects.builder().putText("path", "private-missing-name.txt").build());
        AgentTool.ToolResult directoryResult = execute(registry, "read",
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
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), catalog(), shellCapability(),
                promptSession(), unusedAttachments());

        AgentTool.ToolResult tooLarge = execute(registry, "read",
                JsonObjects.builder().putText("path", oversized.toString()).build());
        AgentTool.ToolResult tooLong = execute(registry, "read",
                JsonObjects.builder().putText("path", tooManyCharacters.toString()).build());
        AgentTool.ToolResult invalidUtf8 = execute(registry, "read",
                JsonObjects.builder().putText("path", malformed.toString()).build());
        AgentTool.ToolResult notText = execute(registry, "read",
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
        SkillCatalog skills = new SingleSkill();
        ToolRegistry registry = BuiltInTools.create(workspace, skills, catalog(), shellCapability(),
                promptSession(), unusedAttachments());

        AgentTool.ToolResult result = execute(registry, "read",
                JsonObjects.builder().putText("path", "skill://updeng-workflow").build());

        assertEquals("activated fixture skill", result.content());
    }

    /** Shell 缺失时只移除该 Tool，文件和 Skill 读取能力仍保持可执行。 */
    @Test
    void omitsShellWhenCapabilityIsUnavailable() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("no-shell-workspace"));
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), catalog(),
                ShellCapability.unavailable(ShellProfile.OperatingSystem.WINDOWS, "windows"), promptSession(),
                unusedAttachments());
        assertEquals(List.of("edit", "find", "grep", "ls", "read", "read_attachment", "write"),
                registry.snapshot().stream().map(tool -> tool.spec().name()).toList());
    }

    /** read_attachment 只能把模型参数与冻结 Thread 身份组合，且图片仍通过 Base64 Tool 路由。 */
    @Test
    void readsAttachmentThroughBoundedToolWithContextThreadIdentity() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("attachment-workspace"));
        AtomicReference<ManagedAttachmentReader.ReadRequest> observed = new AtomicReference<>();
        ToolRegistry registry = BuiltInTools.create(workspace, new EmptySkills(), catalog(), shellCapability(),
                promptSession(), visibleAttachment(observed));

        AgentTool.ToolResult result = execute(registry, "read_attachment", JsonObjects.builder()
                .putText("attachmentId", "att_fixture")
                .putNumber("offsetBytes", 8).putNumber("maxBytes", 64).build());

        assertEquals("thr_fixture", observed.get().threadId());
        assertEquals(8, observed.get().offsetBytes());
        assertEquals(64, observed.get().maxBytes());
        assertEquals("AAEC", result.content());
    }

    /** 通过真实 AgentTool 端口执行，避免测试私有实现细节。 */
    private AgentTool.ToolResult execute(ToolRegistry registry, String name, JsonObject arguments) {
        return execute(registry, name, arguments, CancellationToken.none(), context());
    }

    /** 为取消与 Deadline 回归注入调用边界，仍通过真实 AgentTool 公共入口执行。 */
    private AgentTool.ToolResult execute(ToolRegistry registry, String name, JsonObject arguments,
                                         CancellationToken token, AgentTool.ExecutionContext executionContext) {
        AgentTool.Invocation invocation = invocation(name, arguments);
        return registry.require(invocation).execute(invocation, executionContext, token)
                .toCompletableFuture().join();
    }

    /** 统一验证失败同时携带稳定机器码、非空操作指引，并拒绝泄露输入中的敏感路径。 */
    private static void assertFailure(AgentTool.ToolResult result, String code, String sensitivePath) {
        assertEquals(code, result.errorCode());
        assertFalse(result.content().isBlank());
        assertFalse(result.content().contains(sensitivePath));
    }

    /** 收据只通过专用字段读取；路径比较物理身份，不能把 Windows 短路径的拼写当成泄漏或越界。 */
    private static void assertReceipt(AgentTool.ToolResult result, Path path, boolean beforeExists,
                                      String before, String after) throws IOException {
        AgentTool.MutationReceipt receipt = result.mutationReceipt().orElseThrow();
        assertTrue(Files.isSameFile(path, receipt.path()));
        assertEquals(beforeExists, receipt.beforeExists());
        assertEquals(before, receipt.beforeText());
        assertEquals(after, receipt.afterText());
        assertTrue(result.structuredContent().isEmpty());
        String publicProjection = result.content() + result;
        assertFalse(publicProjection.contains(path.toString()));
        if (!before.isEmpty()) assertFalse(publicProjection.contains(before));
        assertFalse(publicProjection.contains(after));
        assertFalse(publicProjection.contains(receipt.afterSha256()));
    }

    /** 每次调用使用合法稳定身份，测试只变化 Tool 名和参数。 */
    private static AgentTool.Invocation invocation(String name, JsonObject arguments) {
        return new AgentTool.Invocation("call_fixture", name, arguments, 0);
    }

    /** Workspace 仅作为相对路径基准；权限值不改变文件 Tool 路径解析。 */
    private AgentTool.ExecutionContext context() {
        return context(Instant.now().plusSeconds(30));
    }

    /** 构造带指定 Deadline 的合法执行上下文，测试只改变时间边界而不改变身份。 */
    private AgentTool.ExecutionContext context(Instant deadline) {
        return new AgentTool.ExecutionContext("thr_fixture", "turn_fixture", temporary.toAbsolutePath(),
                AccessMode.FULL_ACCESS, "cfg_fixture", deadline, "ws_fixture");
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

    /** 返回在固定检查次数后取消的令牌，避免遍历取消测试依赖 sleep 或文件系统竞态。 */
    private static CountingCancellationToken cancelAfterChecks(int checksBeforeCancellation) {
        return new CountingCancellationToken(checksBeforeCancellation);
    }

    /** 计数令牌让回归测试确定性覆盖 collect 的逐项取消检查。 */
    private static final class CountingCancellationToken implements CancellationToken {
        private final int checksBeforeCancellation;
        private final AtomicInteger checks = new AtomicInteger();

        /** 冻结取消阈值，避免测试执行中改变取消时序。 */
        private CountingCancellationToken(int checksBeforeCancellation) {
            this.checksBeforeCancellation = checksBeforeCancellation;
        }

        /** 在第 N+1 次查询时发布取消，使前置检查可以完成并进入真实遍历。 */
        @Override
        public boolean isCancellationRequested() {
            return checks.incrementAndGet() > checksBeforeCancellation;
        }

        /** 返回固定原因，不把测试夹具路径带入 Tool 结果。 */
        @Override
        public java.util.Optional<String> reason() {
            return Optional.of("test_walk_cancelled");
        }

        /** 本测试不注册长期回调，避免把临时令牌生命周期延伸到用例结束之后。 */
        @Override
        public Registration onCancellation(Runnable callback) {
            return Registration.noop();
        }

        /** 暴露检查总数，验证取消发生在进入遍历之后。 */
        private int checks() {
            return checks.get();
        }
    }

    /** Shell 本测试不执行，仅需冻结一份合法 Profile 供注册表创建。 */
    private static ShellCapability shellCapability() {
        return ShellCapability.available(new ShellProfile(ShellProfile.OperatingSystem.WINDOWS,
                ShellProfile.Dialect.POWERSHELL,
                Path.of(System.getProperty("java.home"), "bin", "java.exe"), List.of(), "windows",
                Map.of("PATHEXT", ".EXE;.CMD")));
    }

    /** 空目录证明普通文件读取不会反向发现 Skill。 */
    private static SkillCatalog.Catalog catalog() {
        return new SkillCatalog.Catalog(List.of());
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
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) return path;
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
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    /** 使用 Windows 原生 junction 机制，避免 symlink 开发者权限影响 reparse point 回归覆盖。 */
    private static int createJunction(Path junction, Path target) throws java.io.IOException, InterruptedException {
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                junction.toString(), target.toString()).redirectErrorStream(true).start();
        return process.waitFor();
    }

    /** 不参与本用例的 Skill 端口保持严格失败，防止意外读取被忽略。 */
    private static final class EmptySkills implements SkillCatalog {
        /** 返回同一空目录，避免测试引入文件发现行为。 */
        @Override public Catalog discover(DiscoveryRequest request) { return BuiltInToolsTest.catalog(); }
        /** 未启用 Skill 时显式返回空目录，不触发任何扫描。 */
        @Override public Catalog emptyCatalog() { return BuiltInToolsTest.catalog(); }
        /** 当前用例没有可选条目，过滤只保持同一目录。 */
        @Override public Catalog select(Catalog catalog, List<String> allowedNames) {
            return catalog;
        }
        /** 普通文件用例若触发 Skill 读取即说明路由发生回归。 */
        @Override public SkillDocument read(Catalog catalog, SkillReadRequest request) {
            throw new AssertionError("unexpected skill read");
        }
    }

    /** 只暴露截图中的逻辑 Skill 地址，确保测试覆盖 Catalog read 而非文件系统旁路。 */
    private static final class SingleSkill implements SkillCatalog {
        /** 本夹具不执行发现，目录由 Turn runtime 预先冻结。 */
        @Override public Catalog discover(DiscoveryRequest request) { return BuiltInToolsTest.catalog(); }
        /** 未启用时仍返回空目录，避免测试夹具隐式扩大可见集合。 */
        @Override public Catalog emptyCatalog() { return BuiltInToolsTest.catalog(); }
        /** 单一 Skill 没有额外筛选分支。 */
        @Override public Catalog select(Catalog catalog, List<String> allowedNames) { return catalog; }
        /** 返回实时 SKILL.md 正文，并校验调用方没有把逻辑地址改写成其它资源。 */
        @Override public SkillDocument read(Catalog catalog, SkillReadRequest request) {
            assertEquals("updeng-workflow", request.skillName());
            assertEquals("SKILL.md", request.resourcePath());
            return new SkillDocument(request.skillName(), request.resourcePath(), "workflow body", false);
        }
    }
}
