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
                + " && echo Bearer abcdefghijklmnop"
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
