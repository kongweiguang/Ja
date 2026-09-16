// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.presentation;

import io.github.kongweiguang.ja.conversation.domain.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonBoolean;
import io.github.kongweiguang.ja.foundation.json.JsonNumber;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.foundation.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定 Tool 原始事实进入 JA-RPC 和 SQLite 前的安全投影边界。
 */
final class ToolPresentationProjectorTest {
    private static final Path WORKSPACE = Path.of("C:\\workspace\\ja");
    private static final String KNOWN_SECRET = "known-provider-secret-123456";

    /**
     * Shell 命令必须同时清理已知凭据、常见凭据格式、控制字符和绝对路径，避免 UI 展示成为旁路。
     */
    @Test
    void sanitizesShellCommandBeforePresentation() {
        String command = "echo " + KNOWN_SECRET
                + " && echo api_key=secondary-secret"
                + " && echo Bearer " + "abcdefghijklmnop"
                + " && type C:\\workspace\\ja\\README.md\n"
                + "type C:\\Windows\\win.ini\u001B[31m\u0000\n"
                + "type C:/Users/private/secret.txt\n"
                + "type \\\\server\\share\\secret.txt\n"
                + "cat /home/private/secret.txt";
        AgentTool.Invocation invocation = invocation("shell", Map.of("command", new JsonText(command)));

        ToolPresentation presentation = ToolPresentationProjector.prepared(
                invocation, WORKSPACE, List.of(KNOWN_SECRET));

        assertEquals(ToolPresentation.Kind.SHELL, presentation.kind());
        assertFalse(presentation.command().contains(KNOWN_SECRET));
        assertFalse(presentation.command().contains("secondary-secret"));
        assertFalse(presentation.command().contains("abcdefghijklmnop"));
        assertFalse(presentation.command().contains("\u001B"));
        assertFalse(presentation.command().contains("\u0000"));
        assertFalse(presentation.command().contains("C:\\workspace\\ja"));
        assertFalse(presentation.command().contains("C:\\Windows"));
        assertFalse(presentation.command().contains("C:/Users"));
        assertFalse(presentation.command().contains("server\\share"));
        assertFalse(presentation.command().contains("/home/private"));
        assertTrue(presentation.command().contains(".\\README.md"));
        assertTrue(presentation.command().contains("[external-path]"));
    }

    /**
     * 结果预览和分页 artifact 必须共享同一份脱敏正文，禁止全文读取重新暴露已知 Secret。
     */
    @Test
    void storesOnlySanitizedArtifactAndLimitsPreviewToTenLines() {
        String stdout = IntStream.rangeClosed(1, 12)
                .mapToObj(line -> "line-" + line + (line == 2 ? " " + KNOWN_SECRET : ""))
                .reduce((left, right) -> left + "\n" + right).orElseThrow();
        String content = "[stdout]\n" + stdout + "\n[stderr]\nerror " + KNOWN_SECRET;
        JsonObject metadata = new JsonObject(Map.of(
                "exit_code", new JsonNumber(BigDecimal.valueOf(7)),
                "truncated", new JsonBoolean(false)));
        AgentTool.ToolResult result = new AgentTool.ToolResult(
                ToolOutcome.FAILED, content, Optional.<JsonValue>of(metadata), "TOOL_FAILED");

        ToolPresentationProjector.Completed completed = ToolPresentationProjector.completed(
                invocation("shell", Map.of("command", new JsonText("run " + KNOWN_SECRET))),
                result, WORKSPACE, List.of(KNOWN_SECRET), 125L);

        ToolPresentation presentation = completed.presentation();
        assertEquals(ToolPresentation.Status.ERROR, presentation.status());
        assertEquals(7, presentation.exitCode());
        assertEquals(125L, presentation.durationMs());
        assertTrue(presentation.truncated());
        assertNotNull(presentation.artifactId());
        assertEquals(10, presentation.outputPreview().lines().count());
        assertFalse(presentation.outputPreview().contains(KNOWN_SECRET));
        assertFalse(presentation.stdout().contains(KNOWN_SECRET));
        assertFalse(presentation.stderr().contains(KNOWN_SECRET));
        assertFalse(completed.artifactContent().contains(KNOWN_SECRET));
        assertTrue(completed.artifactContent().contains("line-12"));
        assertTrue(completed.artifactContent().contains("[REDACTED]"));
    }

    /**
     * Read/Edit/Write 只允许工作区相对路径；越界路径使用固定标识，不能把宿主目录带入历史记录。
     */
    @Test
    void confinesDisplayedPathsToWorkspace() {
        ToolPresentation internal = ToolPresentationProjector.prepared(
                invocation("read", Map.of("path", new JsonText("C:\\workspace\\ja\\src\\App.tsx"))),
                WORKSPACE, List.of());
        ToolPresentation external = ToolPresentationProjector.prepared(
                invocation("read", Map.of("path", new JsonText("C:\\Users\\private\\secret.txt"))),
                WORKSPACE, List.of());

        assertEquals(List.of("src/App.tsx"), internal.relativePaths());
        assertEquals("src/App.tsx", internal.inputPreview());
        assertEquals(List.of("[external-path]"), external.relativePaths());
        assertEquals("[external-path]", external.inputPreview());
    }

    /** 内容搜索、文件查找和目录列表共用 READ wire kind，但保留精确动作与首个目标。 */
    @Test
    void projectsDiscoveryToolsAsReadWithDistinctTargets() {
        ToolPresentation grep = ToolPresentationProjector.prepared(
                invocation("grep", Map.of(
                        "pattern", new JsonText("needle"),
                        "path", new JsonText("src"))),
                WORKSPACE, List.of());
        ToolPresentation whitespaceGrep = ToolPresentationProjector.prepared(
                invocation("grep", Map.of("pattern", new JsonText("   "))), WORKSPACE, List.of());
        ToolPresentation find = ToolPresentationProjector.prepared(
                invocation("find", Map.of(
                        "pattern", new JsonText("*.tsx"),
                        "path", new JsonText("src"))),
                WORKSPACE, List.of());
        ToolPresentation ls = ToolPresentationProjector.prepared(
                invocation("ls", Map.of("path", new JsonText("src"))), WORKSPACE, List.of());

        assertEquals(ToolPresentation.Kind.READ, grep.kind());
        assertEquals("搜索内容", grep.title());
        assertEquals("pattern=\"needle\" · src", grep.inputPreview());
        assertEquals("pattern=\"   \" · .", whitespaceGrep.inputPreview());
        assertEquals(ToolPresentation.Kind.READ, find.kind());
        assertEquals("查找文件", find.title());
        assertEquals("pattern=\"*.tsx\" · src", find.inputPreview());
        assertEquals(ToolPresentation.Kind.READ, ls.kind());
        assertEquals("列出目录", ls.title());
        assertEquals("src", ls.inputPreview());
    }

    /** 附件读取使用独立 opaque 身份，恢复或失败时不能退化为无法定位的 resource 占位。 */
    @Test
    void preservesAttachmentIdentityAndByteRangeInReadPresentation() {
        ToolPresentation attachment = ToolPresentationProjector.prepared(
                invocation("read_attachment", Map.of(
                        "attachmentId", new JsonText("att_fixture"),
                        "offsetBytes", new JsonNumber(BigDecimal.valueOf(8)),
                        "maxBytes", new JsonNumber(BigDecimal.valueOf(64)))),
                WORKSPACE, List.of());

        assertEquals(ToolPresentation.Kind.READ, attachment.kind());
        assertEquals("读取附件", attachment.title());
        assertEquals("attachmentId=\"att_fixture\" · bytes 8:64", attachment.inputPreview());
        assertTrue(attachment.relativePaths().isEmpty());
    }

    /** 完成与状态恢复只替换生命周期字段，搜索动作和首个目标必须继续来自同一安全投影。 */
    @Test
    void keepsDiscoveryPresentationIdentityAcrossCompletionAndResume() {
        AgentTool.ToolResult result = new AgentTool.ToolResult(
                ToolOutcome.SUCCEEDED, "src/App.tsx:1: needle", Optional.empty(), null);
        ToolPresentation prepared = ToolPresentationProjector.prepared(
                invocation("grep", Map.of(
                        "pattern", new JsonText("needle"),
                        "path", new JsonText("src"))),
                WORKSPACE, List.of());
        ToolPresentationProjector.Completed completed = ToolPresentationProjector.completed(
                invocation("grep", Map.of(
                        "pattern", new JsonText("needle"),
                        "path", new JsonText("src"))),
                result, WORKSPACE, List.of(), 12L);
        ToolPresentation resumed = ToolPresentationProjector.withStatus(
                completed.presentation(), ToolPresentation.Status.RUNNING);

        assertEquals(prepared.kind(), completed.presentation().kind());
        assertEquals(prepared.title(), completed.presentation().title());
        assertEquals(prepared.inputPreview(), completed.presentation().inputPreview());
        assertEquals(completed.presentation().inputPreview(), resumed.inputPreview());
        assertEquals(completed.presentation().relativePaths(), resumed.relativePaths());
    }

    /**
     * 文件工具摘要只能来自固定 metadata 或受 Schema 约束的 edit 数量；英文结果正文、路径和任意自由字段
     * 都不能成为摘要来源，以便前端能安全地把它作为紧凑结果说明。
     */
    @Test
    void projectsSafeCompactSummariesForFileTools() {
        AgentTool.ToolResult readResult = new AgentTool.ToolResult(ToolOutcome.SUCCEEDED, "line", Optional.of(
                new JsonObject(Map.of(
                        "lines", new JsonNumber(BigDecimal.valueOf(20)),
                        "totalLines", new JsonNumber(BigDecimal.valueOf(80)),
                        "nextOffset", new JsonNumber(BigDecimal.valueOf(21)),
                        "truncated", new JsonBoolean(true)))), null);
        ToolPresentationProjector.Completed read = ToolPresentationProjector.completed(
                invocation("read", Map.of("path", new JsonText("README.md"))),
                readResult, WORKSPACE, List.of(), 1L);
        AgentTool.ToolResult discoveryResult = new AgentTool.ToolResult(ToolOutcome.SUCCEEDED, "src/a.ts:1: needle",
                Optional.of(new JsonObject(Map.of(
                        "resultCount", new JsonNumber(BigDecimal.valueOf(12)),
                        "truncated", new JsonBoolean(true)))), null);
        ToolPresentationProjector.Completed grep = ToolPresentationProjector.completed(
                invocation("grep", Map.of("pattern", new JsonText("needle"))),
                discoveryResult, WORKSPACE, List.of(), 1L);
        JsonObject replacement = new JsonObject(Map.of(
                "oldText", new JsonText("before"), "newText", new JsonText("after")));
        ToolPresentationProjector.Completed edit = ToolPresentationProjector.completed(
                invocation("edit", Map.of("path", new JsonText("src/a.ts"),
                        "edits", new JsonArray(List.of(replacement, replacement)))),
                new AgentTool.ToolResult(ToolOutcome.SUCCEEDED, "Successfully replaced 2 block(s) in the file.",
                        Optional.empty(), null), WORKSPACE, List.of(), 1L);

        assertEquals("已读取 20 行，共 80 行；可从第 21 行继续", read.presentation().summary());
        assertEquals("找到 12 个匹配项；部分结果未显示", grep.presentation().summary());
        assertEquals("已完成 2 处替换", edit.presentation().summary());
        assertFalse(edit.presentation().summary().contains("Successfully"));
    }

    /** 批量 edit 仅显示块数，避免原文和替换内容进入持久化的工具活动摘要。 */
    @Test
    void projectsBatchEditAsAContentFreeOperationSummary() {
        JsonObject first = new JsonObject(Map.of(
                "oldText", new JsonText("before-secret"), "newText", new JsonText("after-secret")));
        JsonObject second = new JsonObject(Map.of(
                "oldText", new JsonText("before-two"), "newText", new JsonText("after-two")));
        ToolPresentation presentation = ToolPresentationProjector.prepared(
                invocation("edit", Map.of(
                        "path", new JsonText("src/App.tsx"),
                        "edits", new JsonArray(List.of(first, second)))), WORKSPACE, List.of());

        assertEquals(ToolPresentation.Kind.EDIT, presentation.kind());
        assertEquals("edit src/App.tsx · 2 block(s)", presentation.inputPreview());
        assertFalse(presentation.inputPreview().contains("before-secret"));
        assertFalse(presentation.inputPreview().contains("after-secret"));
    }

    /** Skill 只展示逻辑名称，既能诊断读取对象，也不会把资源子路径或物理 locator 写入历史。 */
    @Test
    void displaysOnlyValidatedSkillIdentity() {
        ToolPresentation skill = ToolPresentationProjector.prepared(
                invocation("read", Map.of("path", new JsonText("skill://updeng-workflow/references/private.md"))),
                WORKSPACE, List.of());
        ToolPresentation malformed = ToolPresentationProjector.prepared(
                invocation("read", Map.of("path", new JsonText("skill://bad name/secret.md"))),
                WORKSPACE, List.of());

        assertEquals(List.of("skill://updeng-workflow"), skill.relativePaths());
        assertEquals("skill://updeng-workflow", skill.inputPreview());
        assertEquals(List.of("[resource]"), malformed.relativePaths());
    }

    /**
     * 未知 MCP 参数仅保留有界 JSON 预览，并同时应用敏感键和已知值脱敏规则。
     */
    @Test
    void redactsUnknownMcpArguments() {
        JsonObject nested = new JsonObject(Map.of(
                "headers", new JsonObject(Map.of(
                        "authorization", new JsonText("nested-raw-header"),
                        "x-api-key", new JsonText("nested-api-key"))),
                "items", new JsonArray(List.of(
                        new JsonObject(Map.of("password", new JsonText("nested-password"))),
                        new JsonText("safe-value")))));
        ToolPresentation presentation = ToolPresentationProjector.prepared(
                invocation("custom_mcp", Map.of(
                        "authorization", new JsonText("raw-header"),
                        "query", new JsonText("find " + KNOWN_SECRET),
                        "nested", nested)),
                WORKSPACE, List.of(KNOWN_SECRET));

        assertEquals(ToolPresentation.Kind.MCP, presentation.kind());
        assertFalse(presentation.inputPreview().contains("raw-header"));
        assertFalse(presentation.inputPreview().contains("nested-api-key"));
        assertFalse(presentation.inputPreview().contains("nested-password"));
        assertFalse(presentation.inputPreview().contains(KNOWN_SECRET));
        assertTrue(presentation.inputPreview().contains("authorization: [REDACTED]"));
        assertTrue(presentation.inputPreview().contains("query: \"find [REDACTED]\""));
        assertTrue(presentation.inputPreview().contains("x-api-key: [REDACTED]"));
        assertTrue(presentation.inputPreview().contains("password: [REDACTED]"));
        assertTrue(presentation.inputPreview().contains("\"safe-value\""));
        assertTrue(presentation.inputPreview().length() <= 16_384);
    }

    /** 生成最小合法调用，测试只改变与投影行为相关的 Tool 名和参数。 */
    private static AgentTool.Invocation invocation(String toolName, Map<String, JsonValue> arguments) {
        return new AgentTool.Invocation("call_projection", toolName, new JsonObject(arguments), 0);
    }
}
