// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.presentation;

import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonBoolean;
import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定 Tool 原始事实进入 JA-RPC 和 SQLite 前的安全投影边界。
 */
final class ToolPresentationProjectorTest {
    private static final Path WORKSPACE = Path.of("C:\\workspace\\ja");
    private static final String KNOWN_SECRET = "known-provider-secret-123456";

    /**
     * 当前 exec_command 的 cmd 必须清理凭据、控制字符和绝对路径，避免新工具落回通用 MCP 参数预览。
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
        AgentTool.Invocation invocation = invocation("exec_command", Map.of("cmd", new JsonText(command)));

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

    /** 模型刚提交调用时只展示静态动作提议，避免未经校验的参数进入事件或数据库展示字段。 */
    @Test
    void previewsModelCallWithoutExposingUnvalidatedArguments() {
        AgentTool.Invocation invocation = invocation("exec_command", Map.of(
                "cmd", new JsonText("deploy --token provider-secret-123")));

        ToolPresentation preview = ToolPresentationProjector.modelCallPreview(invocation);

        assertEquals(ToolPresentation.Kind.SHELL, preview.kind());
        assertEquals("执行命令", preview.title());
        assertEquals(ToolPresentation.Status.PENDING, preview.status());
        assertEquals("等待参数校验", preview.inputPreview());
        assertNull(preview.command());
        assertFalse(preview.inputPreview().contains("provider-secret-123"));
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
                invocation("exec_command", Map.of("cmd", new JsonText("run " + KNOWN_SECRET))),
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

    /** 持续进程在准备、完成与状态恢复时必须保持同一命令身份，否则客户端会丢失会话详情入口。 */
    @Test
    void preservesProcessPresentationAcrossLifecycle() {
        AgentTool.Invocation invocation = invocation("exec_command", Map.of(
                "cmd", new JsonText("echo ready"), "workdir", new JsonText("scripts")));
        ToolPresentation prepared = ToolPresentationProjector.prepared(invocation, WORKSPACE, List.of());
        AgentTool.ToolResult result = new AgentTool.ToolResult(ToolOutcome.SUCCEEDED, "ready",
                Optional.of(new JsonObject(Map.of("session_id", new JsonText("proc_fixture")))), null);
        ToolPresentation completed = ToolPresentationProjector.completed(
                invocation, result, WORKSPACE, List.of(), 10L).presentation();
        ToolPresentation restored = ToolPresentationProjector.withStatus(completed, ToolPresentation.Status.RUNNING);

        assertEquals(ToolPresentation.Kind.SHELL, prepared.kind());
        assertEquals("执行命令", prepared.title());
        assertEquals("echo ready", prepared.command());
        assertEquals("echo ready", prepared.inputPreview());
        assertEquals("scripts", prepared.relativeCwd());
        assertEquals(prepared.kind(), completed.kind());
        assertEquals(prepared.command(), completed.command());
        assertEquals(prepared.relativeCwd(), completed.relativeCwd());
        assertEquals("ready", completed.stdout());
        assertEquals(completed.kind(), restored.kind());
        assertEquals(completed.command(), restored.command());
    }

    /** 模型续读提示不能污染进程展示或历史产物，真实输出仍须脱敏并保留截断事实。 */
    @Test
    void separatesProcessOutputFromModelRecoveryInstructions() {
        String modelContent = "ready " + KNOWN_SECRET
                + "\n\n[process session: running, session_id=proc_fixture, generation=2, next_sequence=26]";
        AgentTool.ToolResult result = new AgentTool.ToolResult(ToolOutcome.SUCCEEDED, modelContent,
                Optional.of(new JsonObject(Map.of("output_truncated", new JsonBoolean(true)))), null,
                Optional.of("ready " + KNOWN_SECRET));

        ToolPresentationProjector.Completed completed = ToolPresentationProjector.completed(
                invocation("exec_command", Map.of("cmd", new JsonText("run"))),
                result, WORKSPACE, List.of(KNOWN_SECRET), 10L);

        assertEquals("ready [REDACTED]", completed.presentation().stdout());
        assertEquals(completed.presentation().stdout(), completed.presentation().outputPreview());
        assertEquals(completed.presentation().stdout(), completed.artifactContent());
        assertTrue(completed.presentation().truncated());
        assertTrue(result.content().contains("session_id=proc_fixture"));
        assertFalse(completed.artifactContent().contains("generation"));
    }

    /** 空进程输出保留真实空值，不能把模型所需的 no new output 提示伪造成命令输出。 */
    @Test
    void preservesEmptyProcessOutputWithoutRecoveryMarker() {
        AgentTool.ToolResult result = new AgentTool.ToolResult(ToolOutcome.SUCCEEDED,
                "(no new output)\n\n[process session: running, session_id=proc_fixture]",
                Optional.empty(), null, Optional.of(""));

        ToolPresentationProjector.Completed completed = ToolPresentationProjector.completed(
                invocation("write_stdin", Map.of("session_id", new JsonText("proc_fixture"))),
                result, WORKSPACE, List.of(), 10L);

        assertEquals("", completed.presentation().stdout());
        assertEquals("", completed.presentation().outputPreview());
        assertEquals("", completed.artifactContent());
        assertNull(completed.presentation().artifactId());
    }

    /** 进程注册表已合并输出，正文中的标签是用户内容，不能再解释成旧流分隔符。 */
    @Test
    void preservesLiteralStreamMarkersInProcessOutput() {
        String output = "log [stdout]\nnormal\n[stderr]\nexample";
        AgentTool.ToolResult result = new AgentTool.ToolResult(ToolOutcome.SUCCEEDED,
                output + "\n[process session: exited]", Optional.empty(), null, Optional.of(output));

        ToolPresentationProjector.Completed completed = ToolPresentationProjector.completed(
                invocation("exec_command", Map.of("cmd", new JsonText("print-example"))),
                result, WORKSPACE, List.of(), 10L);

        assertEquals(output, completed.presentation().stdout());
        assertNull(completed.presentation().stderr());
    }

    /** 续写与停止只公开命令会话目标，不能将 stdin 正文当作新的命令或 MCP 参数复制到界面。 */
    @Test
    void presentsProcessControlWithoutExposingInput() {
        Map<String, JsonValue> arguments = Map.of("session_id", new JsonText("proc_fixture"),
                "input", new JsonText("private-stdin-content"));
        ToolPresentation continued = ToolPresentationProjector.prepared(
                invocation("write_stdin", arguments), WORKSPACE, List.of());
        ToolPresentation stopped = ToolPresentationProjector.prepared(
                invocation("stop_process", Map.of("session_id", new JsonText("proc_fixture"))),
                WORKSPACE, List.of());
        ToolPresentation listed = ToolPresentationProjector.prepared(
                invocation("list_processes", Map.of()), WORKSPACE, List.of());

        assertEquals(ToolPresentation.Kind.SHELL, continued.kind());
        assertEquals("继续命令", continued.title());
        assertEquals("后台命令", continued.inputPreview());
        assertNull(continued.command());
        assertFalse(continued.inputPreview().contains("private-stdin-content"));
        assertEquals(ToolPresentation.Kind.SHELL, stopped.kind());
        assertEquals("停止命令", stopped.title());
        assertEquals("后台命令", stopped.inputPreview());
        assertEquals(ToolPresentation.Kind.READ, listed.kind());
        assertEquals("查看命令会话", listed.title());
        assertEquals("当前会话", listed.inputPreview());
    }

    /** 工作区图片属于可信读取动作，路径继续走受控相对投影，不展示为外部 MCP 调用。 */
    @Test
    void presentsWorkspaceImageAsRead() {
        ToolPresentation presentation = ToolPresentationProjector.prepared(
                invocation("view_image", Map.of("path", new JsonText("screenshots/error.png"))),
                WORKSPACE, List.of());

        assertEquals(ToolPresentation.Kind.READ, presentation.kind());
        assertEquals("查看图片", presentation.title());
        assertEquals("screenshots/error.png", presentation.inputPreview());
        assertEquals(List.of("screenshots/error.png"), presentation.relativePaths());
    }

    /** Schema 参数诊断把安全 JSON Pointer 展示成普通字段路径，避免和宿主绝对路径规则冲突。 */
    @Test
    void preservesValidatedJsonPointerInArgumentFailure() {
        AgentTool.ToolResult result = new AgentTool.ToolResult(ToolOutcome.FAILED,
                "Tool arguments violate 'pattern' at /pattern: the declared value constraint is not satisfied.. "
                        + "Correct the arguments and retry this Tool.",
                Optional.empty(), "TOOL_ARGUMENTS_INVALID");

        ToolPresentationProjector.Completed completed = ToolPresentationProjector.completed(
                invocation("grep", Map.of("pattern", new JsonText("needle"))),
                result, WORKSPACE, List.of(), 3L);

        assertTrue(completed.presentation().outputPreview().contains("at $.pattern:"));
        assertTrue(completed.artifactContent().contains("at $.pattern:"));
        assertFalse(completed.artifactContent().contains("[external-path]"));
    }

    /** 校验器以 `$` 表示根实例位置，投影时不能将其重复转换为 `$$`。 */
    @Test
    void preservesRootLocationInArgumentFailure() {
        AgentTool.ToolResult result = new AgentTool.ToolResult(ToolOutcome.FAILED,
                "Tool arguments violate 'type' at $: the JSON type is incorrect.. "
                        + "Correct the arguments and retry this Tool.",
                Optional.empty(), "TOOL_ARGUMENTS_INVALID");

        ToolPresentationProjector.Completed completed = ToolPresentationProjector.completed(
                invocation("grep", Map.of("pattern", new JsonText("needle"))),
                result, WORKSPACE, List.of(), 3L);

        assertTrue(completed.artifactContent().contains("at $: the JSON type is incorrect"));
        assertFalse(completed.artifactContent().contains("at $$:"));
    }

    /** 嵌套字段路径与其内的已知密钥继续通过统一脱敏器处理，不匹配诊断仍执行路径清理。 */
    @Test
    void redactsSecretsAndRejectsUnverifiedArgumentDiagnostics() {
        AgentTool.ToolResult secretPointer = new AgentTool.ToolResult(ToolOutcome.FAILED,
                "Tool arguments violate 'pattern' at /providerToken: the declared value constraint is not satisfied.. "
                        + "Correct the arguments and retry this Tool.",
                Optional.empty(), "TOOL_ARGUMENTS_INVALID");
        AgentTool.ToolResult nestedPointer = new AgentTool.ToolResult(ToolOutcome.FAILED,
                "Tool arguments violate 'pattern' at /edits/0/oldText: the declared value constraint is not satisfied.. "
                        + "Correct the arguments and retry this Tool.",
                Optional.empty(), "TOOL_ARGUMENTS_INVALID");
        AgentTool.ToolResult malformed = new AgentTool.ToolResult(ToolOutcome.FAILED,
                "Tool arguments violate 'type' at C:\\Users\\private\\secret.txt: the JSON type is incorrect.. "
                        + "Correct the arguments and retry this Tool.",
                Optional.empty(), "TOOL_ARGUMENTS_INVALID");
        AgentTool.ToolResult wrongCode = new AgentTool.ToolResult(ToolOutcome.FAILED,
                "Tool arguments violate 'type' at /pattern: the JSON type is incorrect.. "
                        + "Correct the arguments and retry this Tool.",
                Optional.empty(), "TOOL_FAILED");

        ToolPresentationProjector.Completed redacted = ToolPresentationProjector.completed(
                invocation("grep", Map.of("pattern", new JsonText("needle"))),
                secretPointer, WORKSPACE, List.of("providerToken"), 3L);
        ToolPresentationProjector.Completed sanitizedMalformed = ToolPresentationProjector.completed(
                invocation("grep", Map.of("pattern", new JsonText("needle"))),
                malformed, WORKSPACE, List.of(), 3L);
        ToolPresentationProjector.Completed normalizedNested = ToolPresentationProjector.completed(
                invocation("grep", Map.of("pattern", new JsonText("needle"))),
                nestedPointer, WORKSPACE, List.of(), 3L);
        ToolPresentationProjector.Completed sanitizedWrongCode = ToolPresentationProjector.completed(
                invocation("grep", Map.of("pattern", new JsonText("needle"))),
                wrongCode, WORKSPACE, List.of(), 3L);

        assertTrue(redacted.artifactContent().contains("at $.[REDACTED]:"));
        assertFalse(redacted.artifactContent().contains("providerToken"));
        assertTrue(normalizedNested.artifactContent().contains("at $.edits.0.oldText:"));
        assertTrue(sanitizedMalformed.artifactContent().contains("[external-path]"));
        assertFalse(sanitizedMalformed.artifactContent().contains("C:\\Users\\private"));
        assertTrue(sanitizedWrongCode.artifactContent().contains("at [external-path]"));
        assertFalse(sanitizedWrongCode.artifactContent().contains("$.pattern"));
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

    /** 澄清结果用已脱敏的问题/答案条目展示，答案正文不能落入通用预览或 artifact。 */
    @Test
    void projectsClarificationAnswersWithoutCreatingArtifact() {
        JsonObject question = new JsonObject(Map.of(
                "questionId", new JsonText("question_scope"),
                "prompt", new JsonText("Which scope?"),
                "type", new JsonText("text"),
                "options", new JsonArray(List.of()),
                "required", new JsonBoolean(true),
                "allowFreeText", new JsonBoolean(true)));
        AgentTool.Invocation invocation = invocation("request_user_input", Map.of(
                "questions", new JsonArray(List.of(question))));
        JsonObject answer = new JsonObject(Map.of(
                "questionId", new JsonText("question_scope"),
                "optionIds", new JsonArray(List.of()),
                "freeText", new JsonText(KNOWN_SECRET + "\nteam"),
                "skipped", new JsonBoolean(false)));
        AgentTool.ToolResult result = new AgentTool.ToolResult(ToolOutcome.SUCCEEDED,
                "用户回答内容", Optional.of(new JsonObject(Map.of(
                "answers", new JsonArray(List.of(answer))))), null);

        ToolPresentationProjector.Completed completed = ToolPresentationProjector.completed(
                invocation, result, WORKSPACE, List.of(KNOWN_SECRET), 8L);

        assertEquals(ToolPresentation.Status.SUCCESS, completed.presentation().status());
        assertEquals("已回答 1 个澄清问题", completed.presentation().summary());
        assertEquals("Which scope?", completed.presentation().interactionAnswers().getFirst().question());
        assertEquals(List.of("[REDACTED]\nteam"),
                completed.presentation().interactionAnswers().getFirst().answers());
        assertNull(completed.presentation().outputPreview());
        assertNull(completed.presentation().artifactId());
        assertEquals("", completed.artifactContent());
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

    /**
     * 内置 Plan Tool 的折叠展示只给出脱敏业务意图，完整受限参数仍留在可展开预览中供核对。
     */
    @Test
    void presentsPlanToolsWithLocalizedIntentAndSafeSummary() {
        JsonObject definition = new JsonObject(Map.of(
                "objective", new JsonText("检查发布门禁 " + KNOWN_SECRET)));
        ToolPresentation proposal = ToolPresentationProjector.prepared(invocation("plan_propose", Map.of(
                "planId", new JsonText("plan_private"),
                "expectedPlanRevision", new JsonNumber(BigDecimal.valueOf(3)),
                "operationKey", new JsonText("op_private"),
                "definition", definition)), WORKSPACE, List.of(KNOWN_SECRET));
        ToolPresentation draft = ToolPresentationProjector.prepared(invocation("plan_draft_update", Map.of(
                "planId", new JsonText("plan_private"),
                "expectedPlanRevision", new JsonNumber(BigDecimal.valueOf(3)),
                "expectedDraftRevision", new JsonNumber(BigDecimal.valueOf(2)),
                "operationKey", new JsonText("op_private"),
                "definition", definition)), WORKSPACE, List.of(KNOWN_SECRET));
        ToolPresentation step = ToolPresentationProjector.prepared(invocation("plan_step_update", Map.of(
                "planId", new JsonText("plan_private"),
                "expectedPlanRevision", new JsonNumber(BigDecimal.valueOf(3)),
                "runId", new JsonText("run_private"),
                "stepId", new JsonText("step_private"),
                "expectedStatus", new JsonText("READY"),
                "status", new JsonText("RUNNING"),
                "operationKey", new JsonText("op_private"))), WORKSPACE, List.of());
        ToolPresentation unknownStepStatus = ToolPresentationProjector.prepared(invocation("plan_step_update", Map.of(
                "expectedStatus", new JsonText("READY"),
                "status", new JsonText("untrusted-internal-status"))), WORKSPACE, List.of());

        assertEquals("提交方案修订", proposal.title());
        assertEquals("目标：检查发布门禁 [REDACTED]", proposal.summary());
        assertEquals("更新方案草稿", draft.title());
        assertEquals(proposal.summary(), draft.summary());
        assertEquals("更新执行步骤", step.title());
        assertEquals("请求步骤状态：待执行 → 执行中", step.summary());
        assertTrue(proposal.inputPreview().contains("planId"));
        assertFalse(proposal.summary().contains("plan_private"));
        assertFalse(proposal.summary().contains("op_private"));
        assertFalse(step.summary().contains("run_private"));
        assertFalse(step.summary().contains("step_private"));
        assertFalse(step.summary().contains("op_private"));
        assertNull(unknownStepStatus.summary());
    }

    /**
     * 外部 JSON 可用短指数携带极端 scale；先约束 plain 长度，避免展示预览在整体截断前耗尽内存。
     */
    @Test
    void boundsExtremeMcpNumbersBeforeFormatting() {
        JsonObject arguments = new JsonObject(Map.of(
                "amount", new JsonNumber(new BigDecimal("1.2300")),
                "positive", new JsonNumber(new BigDecimal("1E+100000")),
                "negative", new JsonNumber(new BigDecimal("1E-100000")),
                "zeroPositive", new JsonNumber(new BigDecimal("0E+100000")),
                "zeroNegative", new JsonNumber(new BigDecimal("0E-100000")),
                "wide", new JsonNumber(new BigDecimal("9".repeat(1_025)))));

        ToolPresentation presentation = ToolPresentationProjector.prepared(
                invocation("custom_mcp", arguments.members()), WORKSPACE, List.of());

        assertTrue(presentation.inputPreview().contains("amount: 1.2300"));
        assertTrue(presentation.inputPreview().contains("positive: 1E+100000"));
        assertTrue(presentation.inputPreview().contains("negative: 1E-100000"));
        assertTrue(presentation.inputPreview().contains("wide: [number omitted: 1025 digits]"));
        assertTrue(presentation.inputPreview().length() < 1_024);
    }

    /** 生成最小合法调用，测试只改变与投影行为相关的 Tool 名和参数。 */
    private static AgentTool.Invocation invocation(String toolName, Map<String, JsonValue> arguments) {
        return new AgentTool.Invocation("call_projection", toolName, new JsonObject(arguments), 0);
    }
}
