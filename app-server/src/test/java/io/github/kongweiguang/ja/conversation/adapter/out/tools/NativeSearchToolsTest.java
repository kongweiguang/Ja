// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.foundation.search.NativeSearchProcess;
import io.github.kongweiguang.ja.foundation.search.NativeSearchToolResolver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 fd/rg 的真实 ignore、Glob、输出预算和物理链接边界。 */
class NativeSearchToolsTest {
    @TempDir Path temporary;

    /** fd 与 rg 都沿用仓库及嵌套 .gitignore，同时显式包含 hidden 项和目录 Glob 结果。 */
    @Test
    void honorsNestedGitIgnoreHiddenAndSubpathGlob() throws Exception {
        Path repository = Files.createDirectory(temporary.resolve("native-search-repository"));
        initializeGit(repository);
        Files.writeString(repository.resolve(".gitignore"), "ignored-root/\n");
        Path nested = Files.createDirectories(repository.resolve("src/nested"));
        Files.writeString(nested.resolve(".gitignore"), "ignored/\n");
        Files.createDirectories(repository.resolve("ignored-root"));
        Files.createDirectories(nested.resolve("ignored"));
        Files.createDirectories(nested.resolve("visible"));
        Files.writeString(repository.resolve("ignored-root/README.md"), "needle ignored root");
        Files.writeString(nested.resolve("ignored/README.md"), "needle nested ignored");
        Files.writeString(nested.resolve("visible/README.md"), "needle visible");
        Files.writeString(repository.resolve(".hidden.md"), "needle hidden");

        NativeSearchToolResolver resolver = nativeResolver();
        AgentTool.ToolResult find = execute(WorkspaceFileTools.find(repository, resolver), "find",
                JsonObjects.builder().putText("pattern", "*.md").build(), repository);
        assertEquals(ToolOutcome.SUCCEEDED, find.outcome());
        assertTrue(find.content().contains("src/nested/visible/README.md"), find.content());
        assertFalse(find.content().contains("ignored-root/README.md"), find.content());
        assertFalse(find.content().contains("src/nested/ignored/README.md"), find.content());

        AgentTool.ToolResult hidden = execute(WorkspaceFileTools.find(repository, resolver), "find",
                JsonObjects.builder().putText("pattern", ".hidden.md").build(), repository);
        assertTrue(hidden.content().contains(".hidden.md"), hidden.content());

        AgentTool.ToolResult directory = execute(WorkspaceFileTools.find(repository, resolver), "find",
                JsonObjects.builder().putText("pattern", "src/nested/visible").build(), repository);
        assertTrue(directory.content().contains("src/nested/visible/"), directory.content());

        AgentTool.ToolResult grep = execute(WorkspaceFileTools.grep(repository, resolver), "grep",
                JsonObjects.builder().putText("pattern", "needle").putText("path", "src/nested")
                        .putText("glob", "*.md").build(), repository);
        assertEquals(ToolOutcome.SUCCEEDED, grep.outcome());
        assertTrue(grep.content().contains("src/nested/visible/README.md"), grep.content());
        assertFalse(grep.content().contains("src/nested/ignored/README.md"), grep.content());
    }

    /** Pi 风格正则、忽略大小写和上下文只扩展 rg 参数，不得绕过 ignore、边界或结果预算。 */
    @Test
    void supportsRegexCaseInsensitiveMatchingAndContext() throws Exception {
        Path repository = Files.createDirectory(temporary.resolve("native-regex-repository"));
        initializeGit(repository);
        Files.writeString(repository.resolve("Notes.java"), "before\nTODO: fix\nafter\n");

        AgentTool.ToolResult match = execute(WorkspaceFileTools.grep(repository, nativeResolver()), "grep",
                JsonObjects.builder().putText("pattern", "^todo:\\s+fix$").putBoolean("ignoreCase", true)
                        .putNumber("context", 1).build(), repository);
        AgentTool.ToolResult invalid = execute(WorkspaceFileTools.grep(repository, nativeResolver()), "grep",
                JsonObjects.builder().putText("pattern", "[").build(), repository);

        assertEquals(ToolOutcome.SUCCEEDED, match.outcome(), match::content);
        assertTrue(match.content().contains("Notes.java-1- before"), match.content());
        assertTrue(match.content().contains("Notes.java:2: TODO: fix"), match.content());
        assertTrue(match.content().contains("Notes.java-3- after"), match.content());
        assertEquals("tool_arguments_invalid", invalid.errorCode());
        assertTrue(invalid.content().contains("pattern"), invalid.content());
    }

    /** resolver 缺少 fd/rg 时返回明确安装错误，不退回耗时且语义不同的 Java 递归实现。 */
    @Test
    void reportsMissingNativeDependencyWithoutFallback() throws Exception {
        Path repository = Files.createDirectory(temporary.resolve("missing-native-repository"));
        NativeSearchToolResolver missing = new NativeSearchToolResolver(
                repository.resolve("missing-fd.exe"), repository.resolve("missing-rg.exe"));

        AgentTool.ToolResult find = execute(WorkspaceFileTools.find(repository, missing), "find",
                JsonObjects.builder().putText("pattern", "*.txt").build(), repository);
        AgentTool.ToolResult grep = execute(WorkspaceFileTools.grep(repository, missing), "grep",
                JsonObjects.builder().putText("pattern", "needle").build(), repository);

        assertEquals(ToolOutcome.FAILED, find.outcome());
        assertEquals("search_tool_unavailable", find.errorCode());
        assertEquals(ToolOutcome.FAILED, grep.outcome());
        assertEquals("search_tool_unavailable", grep.errorCode());
    }

    /** stderr 超过系统管道容量时仍被持续排空，native 进程可以正常退出且诊断保留量有界。 */
    @Test
    void drainsSaturatedStderrWithoutBlocking() throws Exception {
        NativeSearchProcess.Result result = NativeSearchProcess.run(shell(),
                shellArguments(stderrCommand()), temporary, CancellationToken.none(),
                Instant.now().plusSeconds(10), 4_096, 1_024, line -> true);

        byte[] retained = result.stderr().getBytes(StandardCharsets.UTF_8);
        assertEquals(0, result.exitCode());
        assertTrue(retained.length > 0, "stderr fixture must produce diagnostic bytes");
        assertTrue(retained.length <= 1_024, "stderr retention must remain bounded");
    }

    /** fd 的结果上限由 native 进程提前停止，返回一条结果和 partial 原因而不是静默丢失。 */
    @Test
    void stopsNativeProcessAtResultLimit() throws Exception {
        Path repository = Files.createDirectory(temporary.resolve("native-result-limit-repository"));
        for (int index = 0; index < 8; index++) {
            Files.writeString(repository.resolve("entry-" + index + ".txt"), "entry");
        }

        AgentTool.ToolResult result = execute(WorkspaceFileTools.find(repository, nativeResolver()), "find",
                JsonObjects.builder().putText("pattern", "*.txt").putNumber("limit", 1).build(),
                repository);

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertEquals(1, result.content().lines().filter(line -> line.endsWith(".txt")).count());
        assertTrue(result.content().contains("termination=result_limit"), result.content());
    }

    /** Windows junction 只作为 reparse point 被 fd 看到，不得跟随到 Workspace 外部目录。 */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void doesNotFollowWindowsJunction() throws Exception {
        Path repository = Files.createDirectory(temporary.resolve("junction-repository"));
        Path outside = Files.createDirectory(temporary.resolve("junction-outside"));
        Files.writeString(repository.resolve("inside.md"), "inside");
        Files.writeString(outside.resolve("inside.md"), "outside");
        Path junction = repository.resolve("linked");
        Assumptions.assumeTrue(createJunction(junction, outside), "当前账户不能创建 Windows junction");
        try {
            NativeSearchToolResolver resolver = nativeResolver();
            AgentTool.ToolResult find = execute(WorkspaceFileTools.find(repository, resolver), "find",
                    JsonObjects.builder().putText("pattern", "inside.md").build(), repository);
            assertEquals(ToolOutcome.SUCCEEDED, find.outcome());
            assertTrue(find.content().contains("inside.md"), find.content());
            assertFalse(find.content().contains("linked"), find.content());

            AgentTool.ToolResult outsideGrep = execute(WorkspaceFileTools.grep(repository, resolver), "grep",
                    JsonObjects.builder().putText("pattern", "outside").build(), repository);
            assertEquals(ToolOutcome.SUCCEEDED, outsideGrep.outcome());
            assertFalse(outsideGrep.content().contains("outside"), outsideGrep.content());
            assertFalse(outsideGrep.content().contains("linked"), outsideGrep.content());

            AgentTool.ToolResult insideGrep = execute(WorkspaceFileTools.grep(repository, resolver), "grep",
                    JsonObjects.builder().putText("pattern", "inside").build(), repository);
            assertTrue(insideGrep.content().contains("inside.md"), insideGrep.content());

            StringBuilder rawOutput = new StringBuilder();
            NativeSearchProcess.Result raw = NativeSearchProcess.run(resolver.resolve("rg"), List.of(
                            "--no-config", "--json", "--line-number", "--color=never", "--hidden",
                            "--no-follow", "--fixed-strings", "--", "outside", repository.toString()),
                    repository, CancellationToken.none(), Instant.now().plusSeconds(15), 64 * 1024, 4 * 1024,
                    line -> {
                        rawOutput.append(line).append('\n');
                        return true;
                    });
            assertEquals(1, raw.exitCode(), "rg exit 1 means no match");
            assertFalse(rawOutput.toString().contains("outside"), rawOutput.toString());
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    /** 真实行为测试必须运行打包或 PATH 中的 fd/rg，缺失依赖应直接使门禁失败。 */
    private static NativeSearchToolResolver nativeResolver() throws IOException {
        NativeSearchToolResolver resolver = NativeSearchToolResolver.system();
        try {
            resolver.resolve("fd");
            resolver.resolve("rg");
        } catch (NativeSearchToolResolver.SearchToolUnavailableException missing) {
            throw new AssertionError("fd/rg native tools are unavailable", missing);
        }
        return resolver;
    }

    /** 使用 git init 让 fd/rg 按真实仓库规则加载根和嵌套 ignore 文件。 */
    private static void initializeGit(Path repository) throws Exception {
        Process process;
        try {
            process = new ProcessBuilder("git", "init", "--quiet", repository.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException unavailable) {
            Assumptions.assumeTrue(false, "git is unavailable for ignore fixture");
            return;
        }
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                Assumptions.assumeTrue(false, "git init timed out");
            }
            Assumptions.assumeTrue(process.exitValue() == 0, "git init failed");
        } finally {
            process.destroyForcibly();
        }
    }

    /** 构造普通 Tool 调用上下文，测试只观察 native 搜索结果和稳定错误字段。 */
    private static AgentTool.ToolResult execute(AgentTool tool, String name, JsonObject arguments, Path repository) {
        AgentTool.Invocation invocation = new AgentTool.Invocation("native_search_call", name, arguments, 0);
        AgentTool.ExecutionContext context = new AgentTool.ExecutionContext("thr_native", "turn_native",
                repository.toAbsolutePath(), AccessMode.FULL_ACCESS, "cfg_native", Instant.now().plusSeconds(15),
                "ws_native");
        return tool.execute(invocation, context, CancellationToken.none()).toCompletableFuture().join();
    }

    /** 直接选择 Windows cmd 或 Unix sh，测试进程边界而不是生产搜索参数解析。 */
    private static Path shell() {
        if (isWindows()) {
            String commandShell = System.getenv("ComSpec");
            return Path.of(commandShell == null || commandShell.isBlank() ? "cmd.exe" : commandShell);
        }
        return Path.of("/bin/sh");
    }

    /** 为两类命令解释器生成统一的参数列表，不经 shell 字符串拼接进入生产代码。 */
    private static List<String> shellArguments(String command) {
        return isWindows() ? List.of("/d", "/c", command) : List.of("-c", command);
    }

    /** 生成大于典型管道容量的 stderr，验证 reader 会持续排空而非等待读满。 */
    private static String stderrCommand() {
        return isWindows()
                ? "for /L %i in (1,1,20000) do @echo stderr-line-0123456789 1>&2"
                : "i=0; while [ $i -lt 20000 ]; do echo stderr-line-0123456789 >&2; i=$((i+1)); done";
    }

    /** 使用 Windows 原生 mklink 创建 junction；输出丢弃，避免测试辅助命令自身堵塞。 */
    private static boolean createJunction(Path junction, Path target) throws IOException, InterruptedException {
        Process process;
        try {
            process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                    junction.toString(), target.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException unavailable) {
            return false;
        }
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } finally {
            process.destroyForcibly();
        }
    }

    /** 根据 JVM 宿主切换测试命令参数；native resolver 不依赖该测试分支。 */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
