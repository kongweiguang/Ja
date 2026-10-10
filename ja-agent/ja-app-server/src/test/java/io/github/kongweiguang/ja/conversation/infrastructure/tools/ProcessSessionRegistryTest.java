// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.tools;

import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase.Identity;
import io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase.Launch;
import io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase.Page;
import io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase.Snapshot;
import io.github.kongweiguang.ja.conversation.application.gateway.ProcessSessionLedger;
import io.github.kongweiguang.ja.conversation.application.gateway.ProcessSessionLedger.Entry;
import io.github.kongweiguang.ja.conversation.application.gateway.ProcessSessionLedger.WriteClaim;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.ProcessSessionScope;
import io.github.kongweiguang.ja.conversation.domain.model.ProcessSessionFailure;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.infrastructure.tools.process.ProcessSessionRegistry;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实宿主 Shell 覆盖持续进程会话的输入、隔离、恢复和 ConPTY 边界。 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class ProcessSessionRegistryTest {
    private static final String WORKSPACE_ID = "ws_process_test";
    private static final String THREAD_ID = "thr_process_test";

    @TempDir Path temporary;
    private InMemoryLedger ledger;
    private ProcessSessionRegistry registry;
    private ShellProfile profile;

    /** 使用宿主已配置 Shell 与完整环境，保持执行语义和用户终端一致。 */
    @BeforeEach
    void startRegistry() throws Exception {
        Map<String, String> environment = System.getenv();
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            environment = isolatedWindowsEnvironment(writeIsolatedProfiles(temporary.resolve("shell-home")));
        }
        profile = ShellCapability.detectAndPreflight(System.getProperty("os.name"), environment,
                ShellProfile::preflight).profile().orElse(null);
        Assumptions.assumeTrue(profile != null, "a supported host Shell is required for process tests");
        ledger = new InMemoryLedger();
        registry = new ProcessSessionRegistry(1, ledger, Clock.systemUTC());
    }

    /** 终结每个测试创建的原生进程树，避免失败断言留下后台宿主进程。 */
    @AfterEach
    void closeRegistry() {
        if (registry != null) {
            registry.close();
        }
    }

    /** 同一 Thread 的下一 Turn 可以写入先前启动的进程，重试同一调用不会重复投递字节。 */
    @Test
    void keepsSessionAcrossTurnsAndAppliesStdinOnce() {
        String command = interactiveCommand();
        Snapshot started = start("turn_first", "call_start", command, false, 500);
        assertEquals("running", started.state());

        String line = profile.os() == ShellProfile.OperatingSystem.WINDOWS ? "hello\r\n" : "hello\n";
        Identity nextTurn = identity("turn_second");
        Snapshot written = registry.writeStdin(nextTurn, "call_input", started.sessionId(), line,
                false, 0, 1_000, CancellationToken.none());
        assertTrue(written.output().contains("received:hello"), written.output());

        Snapshot retried = registry.writeStdin(nextTurn, "call_input", started.sessionId(), line,
                false, 0, 100, CancellationToken.none());
        assertEquals(1, occurrences(retried.output(), "received:hello"));

        Snapshot completed = registry.writeStdin(nextTurn, "call_eof", started.sessionId(), "",
                true, written.nextSequence(), 2_000, CancellationToken.none());
        Snapshot fullOutput = registry.read(scope(), started.sessionId(), 0, 1_024);
        assertEquals("exited", completed.state());
        assertEquals(1, occurrences(fullOutput.output(), "received:hello"));
    }

    /** 生产启动链必须加载 ShellProfile 指向的用户脚本；测试 Profile 被隔离在 TempDir。 */
    @Test
    void loadsIsolatedWindowsPowerShellProfile() {
        Assumptions.assumeTrue(profile.os() == ShellProfile.OperatingSystem.WINDOWS,
                "Windows PowerShell Profile loading is a Windows-only contract");
        Snapshot result = start("turn_profile", "call_profile", "$env:JA_TEST_PROFILE_MARKER", false, 2_000);

        assertEquals("exited", result.state());
        assertTrue(result.output().contains("isolated-profile-loaded"), result.output());
    }

    /** 列举能力只读且可用于 Plan；启动、输入和停止仍处于宿主审批与 Plan 禁用边界内。 */
    @Test
    void exposesOnlyApprovedHostProcessToolsOutsidePlan() {
        List<JaTool> tools = ProcessToolFactory.create(registry, profile);

        assertEquals(List.of("exec_command", "write_stdin", "list_processes", "stop_process"),
                tools.stream().map(tool -> tool.spec().name()).toList());
        for (JaTool tool : tools.stream().filter(candidate -> !"list_processes".equals(candidate.spec().name()))
                .toList()) {
            assertEquals(ToolSideEffect.EXTERNAL, tool.sideEffect());
            assertEquals(JaTool.WorkspaceMutationMode.UNOBSERVABLE, tool.workspaceMutationMode());
            assertEquals(JaTool.PlanAccess.DISALLOWED, tool.planAccess());
            assertEquals(JaTool.ApprovalRequirement.USER_REQUIRED, tool.approvalRequirement());
        }
        JaTool listing = tool(tools, "list_processes");
        assertEquals(ToolSideEffect.READ_ONLY, listing.sideEffect());
        assertEquals(JaTool.WorkspaceMutationMode.NONE, listing.workspaceMutationMode());
        assertEquals(JaTool.PlanAccess.READ_ONLY, listing.planAccess());
        assertEquals(JaTool.ApprovalRequirement.TRUSTED_INTERNAL, listing.approvalRequirement());
        assertTrue(PlanReadOnlyToolCatalog.admitted(listing));
    }

    /** 模型只提交 opaque session_id；当前 Thread 的 stop 工具必须结束其唯一登记进程。 */
    @Test
    void stopsCurrentThreadSessionByOpaqueId() {
        Snapshot started = start("turn_tool_stop", "call_tool_stop", longCommand(), false, 0);
        List<JaTool> tools = ProcessToolFactory.create(registry, profile);

        JaTool.ToolResult stopped = executeTool(tools, "stop_process", JsonObjects.builder()
                .putText("session_id", started.sessionId()).build());

        assertEquals(ToolOutcome.SUCCEEDED, stopped.outcome(), stopped.content());
        JsonObject structured = (JsonObject) stopped.structuredContent().orElseThrow();
        assertEquals(started.sessionId(), ((JsonText) structured.get("session_id")).value());
        assertTrue(Set.of("stopping", "exited").contains(((JsonText) structured.get("state")).value()));
    }

    /** 取消当前 Turn 只结束该 Turn 的进程；Thread 关闭再收口遗留会话并阻止新启动。 */
    @Test
    void cancellationIsTurnScopedAndThreadCloseFencesNewLaunches() {
        Snapshot earlier = start("turn_earlier", "call_earlier", longCommand(), false, 0);
        Snapshot current = start("turn_current", "call_current", longCommand(), false, 0);

        registry.cancelTurn(identity("turn_current"));

        assertEquals("exited", registry.read(scope(), current.sessionId(), 0, 1_024).state());
        assertEquals("running", registry.read(scope(), earlier.sessionId(), 0, 1_024).state());
        registry.closeThread(scope());
        assertEquals("exited", registry.read(scope(), earlier.sessionId(), 0, 1_024).state());
        assertThrows(IllegalStateException.class,
                () -> start("turn_after_close", "call_after_close", longCommand(), false, 0));

        registry.reopenThread(scope());
        Snapshot reopened = start("turn_reopened", "call_reopened", longCommand(), false, 0);
        assertTrue(registry.stop(scope(), reopened.sessionId()).state().matches("stopping|exited"));
    }

    /** 真实 Windows Job Object 在 Turn 取消时结束根 Shell 及其另行启动的宿主子进程。 */
    @Test
    void cancellationTerminatesWindowsDescendantProcessTree() {
        Assumptions.assumeTrue(profile.os() == ShellProfile.OperatingSystem.WINDOWS,
                "Windows process-tree ownership is a Windows-only contract");
        Snapshot unrelated = start("turn_unrelated", "call_unrelated", longCommand(), false, 0);
        String command = windowsChildCommand();
        Snapshot started = start("turn_child", "call_child", command, false, 0);
        String output = awaitOutput(identity("turn_child"), started, "child_pid=");
        long rootPid = reportedPid(output, "root_pid");
        long childPid = childPid(output);

        registry.cancelTurn(identity("turn_child"));

        assertProcessExited(rootPid);
        assertProcessExited(childPid);
        assertEquals("exited", registry.read(scope(), started.sessionId(), 0, 1_024).state());
        assertEquals("running", registry.read(scope(), unrelated.sessionId(), 0, 1_024).state());
    }

    /** 重启后列表与纯等待保留旧输出供核查；真实输入和停止仍被当前代际拒绝。 */
    @Test
    void exposesInterruptedHistoryForReadOnlyRecoveryAndRejectsOldGenerationControl() {
        Instant createdAt = Instant.now();
        Entry historical = new Entry("proc_" + "a".repeat(32), WORKSPACE_ID, THREAD_ID, "turn_old",
                "call_old", 1, "powershell -NoProfile", "running", "retained output", 15,
                null, createdAt, null);
        ledger.createStarting(historical);
        ledger.update(historical);

        registry.close();
        registry = new ProcessSessionRegistry(2, ledger, Clock.systemUTC());
        Snapshot recovered = registry.read(new ProcessSessionScope(WORKSPACE_ID, THREAD_ID, 2), historical.sessionId(), 0, 1_024);

        assertEquals("interrupted", recovered.state());
        assertEquals("retained output", recovered.output());
        Page page = registry.list(WORKSPACE_ID, THREAD_ID, 10, null, null);
        assertEquals(1, page.sessions().size());
        assertEquals(historical.sessionId(), page.sessions().getFirst().sessionId());
        assertEquals(1, page.sessions().getFirst().generation());
        assertEquals("interrupted", page.sessions().getFirst().state());

        List<JaTool> tools = ProcessToolFactory.create(registry, profile);
        JaTool.ToolResult listed = executeTool(tools, "list_processes", JsonObjects.builder().build());
        assertEquals(ToolOutcome.SUCCEEDED, listed.outcome(), listed.content());
        assertTrue(listed.content().contains(historical.sessionId()));
        assertTrue(listed.content().contains("interrupted"));
        assertTrue(listed.content().contains("current runtime generation: 2"));
        assertFalse(listed.content().contains("powershell -NoProfile"));
        assertFalse(listed.content().contains("retained output"));
        JsonObject structured = (JsonObject) listed.structuredContent().orElseThrow();
        assertEquals(2L, ((JsonNumber) structured.get("current_generation")).value().longValueExact());
        JsonArray summaries = (JsonArray) structured.get("sessions");
        JsonObject summary = (JsonObject) summaries.values().getFirst();
        assertEquals(historical.sessionId(), ((JsonText) summary.get("session_id")).value());
        assertEquals(1L, ((JsonNumber) summary.get("generation")).value().longValueExact());

        JaTool.ToolResult waited = executeTool(tools, "write_stdin", JsonObjects.builder()
                .putText("session_id", historical.sessionId()).putNumber("after_sequence", 0)
                .putNumber("yield_time_ms", 0).build());
        assertEquals(ToolOutcome.SUCCEEDED, waited.outcome(), waited.content());
        assertTrue(waited.content().contains("retained output"));
        assertTrue(waited.content().contains("process was not restarted"));
        assertTrue(ledger.writes.isEmpty(), "a read-only wait must not claim a stdin write receipt");

        JaTool.ToolResult inputRejected = executeTool(tools, "write_stdin", JsonObjects.builder()
                .putText("session_id", historical.sessionId()).putText("input", "do not send").build());
        assertEquals(ToolOutcome.FAILED, inputRejected.outcome());
        assertTrue(inputRejected.content().contains("is not writable in this runtime"));
        assertTrue(inputRejected.content().contains("never restarted automatically"));
        assertTrue(ledger.writes.isEmpty(), "rejected old-generation input must not create a write receipt");

        JaTool.ToolResult stopRejected = executeTool(tools, "stop_process", JsonObjects.builder()
                .putText("session_id", historical.sessionId()).build());
        assertEquals(ToolOutcome.FAILED, stopRejected.outcome());
        assertTrue(stopRejected.content().contains("is not stoppable in this runtime"));
        assertTrue(stopRejected.content().contains("never restarted automatically"));

        assertEquals(ProcessSessionFailure.Code.SESSION_UNAVAILABLE, assertThrows(ProcessSessionFailure.class,
                () -> registry.write(new ProcessSessionScope(WORKSPACE_ID, THREAD_ID, 2), historical.sessionId(),
                        "op_" + "b".repeat(32), "input", false, 0, 1_024)).code());
        assertEquals(ProcessSessionFailure.Code.SESSION_UNAVAILABLE, assertThrows(ProcessSessionFailure.class,
                () -> registry.stop(new ProcessSessionScope(WORKSPACE_ID, THREAD_ID, 2), historical.sessionId())).code());
        assertEquals(ProcessSessionFailure.Code.SESSION_UNAVAILABLE, assertThrows(ProcessSessionFailure.class,
                () -> registry.read(new ProcessSessionScope("ws_other", THREAD_ID, 2), historical.sessionId(), 0, 1_024))
                .code());
        assertEquals(ProcessSessionFailure.Code.SESSION_UNAVAILABLE, assertThrows(ProcessSessionFailure.class,
                () -> registry.read(new ProcessSessionScope(WORKSPACE_ID, "thr_other", 2), historical.sessionId(), 0, 1_024))
                .code());
    }

    /** Windows tty 输出必须进入可续读的 ConPTY；同时验证终端句柄，防止命令正文泄漏到父 RPC 管道。 */
    @Test
    void launchesWindowsPseudoConsoleWhenRequested() {
        Assumptions.assumeTrue(profile.os() == ShellProfile.OperatingSystem.WINDOWS,
                "ConPTY is a Windows-only process contract");
        Snapshot started = start("turn_tty", "call_tty", outputCommand("conpty-ready")
                + "; [Console]::Out.WriteLine('stdout-redirected:' + [Console]::IsOutputRedirected)", true, 2_000);

        assertEquals("exited", started.state());
        assertTrue(started.output().contains("conpty-ready"), started.output());
        assertTrue(started.output().contains("stdout-redirected:False"), started.output());
    }

    /** 大输出只返回请求上限内的字节，但绝对序号仍允许后续从精确位置继续读取。 */
    @Test
    void boundsLargeOutputAndPreservesAbsoluteCursor() {
        Assumptions.assumeTrue(profile.os() == ShellProfile.OperatingSystem.WINDOWS,
                "the bounded-output process contract is exercised with the Windows host Shell");
        Snapshot first = start("turn_large_output", "call_large_output", largeOutputCommand(), false, 2_000);

        assertEquals("exited", first.state());
        assertEquals(90_000, first.outputSequence());
        assertTrue(first.output().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 65_536);
        Snapshot next = registry.read(scope(), first.sessionId(), first.nextSequence(), 1_024);
        assertEquals(first.nextSequence() + next.output().length(), next.nextSequence());
        assertTrue(next.output().length() <= 1_024);
    }

    /** 以声明的宿主方言生成等待两次输入的程序，避免测试假设命令行引号规则。 */
    private String interactiveCommand() {
        return profile.os() == ShellProfile.OperatingSystem.WINDOWS
                ? "[Console]::Out.WriteLine('ready'); $line = [Console]::In.ReadLine(); "
                    + "[Console]::Out.WriteLine('received:' + $line); [Console]::In.ReadLine() | Out-Null"
                : "printf 'ready\\n'; IFS= read -r line; printf 'received:%s\\n' \"$line\"; IFS= read -r line";
    }

    /** 产生不依赖平台外部命令的长进程，以直接验证注册表的取消和停止语义。 */
    private String longCommand() {
        return profile.os() == ShellProfile.OperatingSystem.WINDOWS
                ? "Start-Sleep -Seconds 30" : "sleep 30";
    }

    /** 生成平台原生命令的单次输出脚本，供普通管道与 ConPTY 共用。 */
    private String outputCommand(String value) {
        return profile.os() == ShellProfile.OperatingSystem.WINDOWS
                ? "[Console]::Out.WriteLine('" + value + "')"
                : "printf '%s\\n' '" + value + "'";
    }

    /** 生成固定体量正文时把乘法留在子 Shell，避免工具参数本身触及命令长度上限。 */
    private String largeOutputCommand() {
        return "[Console]::Out.Write('x' * 90000)";
    }

    /** 输出根与子 PID 后再保持两者运行，使用配置中的 PowerShell executable 避免依赖 PATH 别名。 */
    private String windowsChildCommand() {
        String executable = "'" + profile.executable().toString().replace("'", "''") + "'";
        return "$child = Start-Process -FilePath " + executable
                + " -ArgumentList @('-NoLogo','-NonInteractive','-ExecutionPolicy','Bypass',"
                + "'-Command','Start-Sleep -Seconds 30') -PassThru; "
                + "[Console]::Out.WriteLine('root_pid=' + $PID); "
                + "[Console]::Out.WriteLine('child_pid=' + $child.Id); Start-Sleep -Seconds 30";
    }

    /** 从根 Shell 的标准输出提取子进程 ID，失败时让测试直接说明缺少进程树证据。 */
    private static long childPid(String output) {
        return reportedPid(output, "child_pid");
    }

    /** 按明确输出标记提取进程 ID，便于分别证明根进程与其后代均被 Job 终止。 */
    private static long reportedPid(String output, String marker) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(marker) + "=(\\d+)").matcher(output);
        if (!matcher.find()) {
            throw new AssertionError("PowerShell did not report " + marker + ": " + output);
        }
        return Long.parseLong(matcher.group(1));
    }

    /** 通过纯观察 API 按输出游标等待真实 ready 标记，避免把有限 exec yield 当成启动完成信号。 */
    private String awaitOutput(Identity owner, Snapshot started, String marker) {
        StringBuilder output = new StringBuilder(started.output());
        long afterSequence = started.nextSequence();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Snapshot latest = started;
        while (output.indexOf(marker) < 0 && Set.of("starting", "running").contains(latest.state())) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            int yieldMillis = (int) Math.max(1L, Math.min(500L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            latest = registry.observe(owner, started.sessionId(), afterSequence, 1_024, yieldMillis,
                    CancellationToken.none());
            output.append(latest.output());
            afterSequence = latest.nextSequence();
        }
        String finalState = latest.state();
        assertTrue(output.indexOf(marker) >= 0,
                () -> "Timed out waiting for PowerShell output marker " + marker
                        + " state=" + finalState + " output=" + output);
        return output.toString();
    }

    /** 在有界观察窗口内等待系统确认子进程已退出，不以根 Shell 退出替代进程树验证。 */
    private static void assertProcessExited(long processId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        ProcessHandle child = ProcessHandle.of(processId).orElse(null);
        while (child != null && child.isAlive() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(child == null || !child.isAlive(), "descendant process remained alive after Turn cancellation");
    }

    /** 将真实 PowerShell Profile 重定向到 TempDir，避免测试读取开发者脚本并保留完整其余环境。 */
    private static Path writeIsolatedProfiles(Path profileRoot) throws Exception {
        Path home = Files.createDirectories(profileRoot);
        Path documents = Files.createDirectories(home.resolve("Documents"));
        String profile = "$env:JA_TEST_PROFILE_MARKER = 'isolated-profile-loaded'\n";
        Path powerShell = Files.createDirectories(documents.resolve("PowerShell"));
        Path windowsPowerShell = Files.createDirectories(documents.resolve("WindowsPowerShell"));
        Files.writeString(powerShell.resolve("Profile.ps1"), profile, StandardCharsets.UTF_8);
        Files.writeString(powerShell.resolve("Microsoft.PowerShell_profile.ps1"), profile,
                StandardCharsets.UTF_8);
        Files.writeString(windowsPowerShell.resolve("Profile.ps1"), profile, StandardCharsets.UTF_8);
        Files.writeString(windowsPowerShell.resolve("Microsoft.PowerShell_profile.ps1"), profile,
                StandardCharsets.UTF_8);
        return home;
    }

    /** 只替换用户 Profile 根目录相关变量，Shell 仍继承 PATH、代理与其它宿主环境输入。 */
    private static Map<String, String> isolatedWindowsEnvironment(Path profileRoot) {
        Map<String, String> values = new HashMap<>(System.getenv());
        String root = profileRoot.toAbsolutePath().toString();
        putWindowsEnvironmentValue(values, "USERPROFILE", root);
        putWindowsEnvironmentValue(values, "HOME", root);
        putWindowsEnvironmentValue(values, "APPDATA", profileRoot.resolve("AppData/Roaming").toString());
        putWindowsEnvironmentValue(values, "LOCALAPPDATA", profileRoot.resolve("AppData/Local").toString());
        return Map.copyOf(values);
    }

    /** Windows 环境变量按大小写不敏感匹配，先移除旧键避免 Profile 定位出现歧义。 */
    private static void putWindowsEnvironmentValue(Map<String, String> environment, String name, String value) {
        environment.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
        environment.put(name, value);
    }

    /** 按固定 Workspace、Thread 和运行代际启动并返回真实进程会话。 */
    private Snapshot start(String turnId, String callId, String command, boolean tty, int yieldMillis) {
        Identity owner = identity(turnId);
        Launch launch = new Launch(owner, callId, command, profile.commandLine(command), temporary,
                profile.environment(), tty, 0);
        return registry.exec(launch, yieldMillis, CancellationToken.none());
    }

    /** Tool 入口与会话 scope 共用同一 Workspace/Thread 身份，避免回归绕过授权校验。 */
    private JaTool.ToolResult executeTool(List<JaTool> tools, String name, JsonObject arguments) {
        JaTool selected = tool(tools, name);
        String callId = "call_" + name;
        AgentIdentity agent = new AgentIdentity("tenant_process_test", THREAD_ID, "turn_process_tools",
                "user_process_test");
        JaTool.ExecutionContext context = new JaTool.ExecutionContext(agent, callId,
                agent.toolCallKey(callId), THREAD_ID, "turn_process_tools", temporary.toAbsolutePath(),
                AccessMode.FULL_ACCESS, "cfg_process_test", Instant.now().plusSeconds(30), WORKSPACE_ID,
                null, null, TurnOrigin.USER);
        AgentTool.Invocation invocation = new AgentTool.Invocation(callId, name, arguments, 0);
        return selected.execute(invocation, context, CancellationToken.none()).toCompletableFuture().join();
    }

    /** 按公开模型名选择冻结工具，确保测试通过生产 Tool 入口验证结果合同。 */
    private static JaTool tool(List<JaTool> tools, String name) {
        return tools.stream().filter(candidate -> name.equals(candidate.spec().name())).findFirst().orElseThrow();
    }

    /** 建立当前注册表 generation 的 Thread 级授权 scope。 */
    private ProcessSessionScope scope() {
        return new ProcessSessionScope(WORKSPACE_ID, THREAD_ID, registry.generation());
    }

    /** 为会话生命周期测试创建指定 Turn 的完整身份。 */
    private Identity identity(String turnId) {
        return new Identity(WORKSPACE_ID, THREAD_ID, turnId, registry.generation());
    }

    /** 统计可见终答片段出现次数以证明 stdin 幂等不重复执行。 */
    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int offset = 0; (offset = value.indexOf(needle, offset)) >= 0; offset += needle.length()) {
            count++;
        }
        return count;
    }

    /** 提供仅供该测试使用的持久化替身，完整执行 registry 幂等和重启状态转换。 */
    private static final class InMemoryLedger implements ProcessSessionLedger {
        private final Map<String, Entry> entries = new HashMap<>();
        private final Map<String, Receipt> writes = new HashMap<>();

        /** 返回已有工具调用事实，或原子插入新 starting 事实。 */
        @Override
        public synchronized Entry createStarting(Entry entry) {
            return findByCall(entry.threadId(), entry.startedTurnId(), entry.startedCallId())
                    .orElseGet(() -> {
                        entries.put(entry.sessionId(), entry);
                        return entry;
                    });
        }

        /** 按 Thread、Turn 和模型调用标识实现 launch 幂等查找。 */
        @Override
        public synchronized Optional<Entry> findByCall(String threadId, String turnId, String callId) {
            return entries.values().stream().filter(value -> value.threadId().equals(threadId)
                    && value.startedTurnId().equals(turnId) && value.startedCallId().equals(callId)).findFirst();
        }

        /** 只在请求提供的 Workspace 与 Thread 均匹配时回读完整事实。 */
        @Override
        public synchronized Optional<Entry> find(String sessionId, String workspaceId, String threadId) {
            Entry entry = entries.get(sessionId);
            return entry != null && entry.workspaceId().equals(workspaceId) && entry.threadId().equals(threadId)
                    ? Optional.of(entry) : Optional.empty();
        }

        /** 测试账本自己解释不透明游标，使注册表不依赖某个持久化适配器的格式。 */
        @Override
        public synchronized ProcessSessionLedger.Page list(
                ProcessSessionScope scope, int limit, String cursor, String startedCallId) {
            String[] decoded = cursor == null ? null : new String(
                    Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\n", -1);
            Instant cursorCreatedAt = decoded == null ? null : Instant.parse(decoded[0]);
            String cursorSessionId = decoded == null ? null : decoded[1];
            List<Entry> matched = entries.values().stream()
                    .filter(value -> value.workspaceId().equals(scope.workspaceId())
                            && value.threadId().equals(scope.threadId())
                            && (startedCallId == null || value.startedCallId().equals(startedCallId)))
                    .sorted(Comparator.comparing(Entry::createdAt).thenComparing(Entry::sessionId).reversed())
                    .filter(value -> cursorCreatedAt == null || value.createdAt().isBefore(cursorCreatedAt)
                            || (value.createdAt().equals(cursorCreatedAt)
                            && value.sessionId().compareTo(cursorSessionId) < 0))
                    .limit(limit + 1L).toList();
            boolean more = matched.size() > limit;
            List<Entry> page = more ? matched.subList(0, limit) : matched;
            String cursorValue = more && !page.isEmpty()
                    ? page.getLast().createdAt() + "\n" + page.getLast().sessionId() : null;
            String nextCursor = cursorValue == null ? null : Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(cursorValue.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new ProcessSessionLedger.Page(page.stream().map(Entry::summary).toList(), nextCursor);
        }

        /** 列出唯一 Workspace/Thread 下的活动行以适配生命周期清理。 */
        @Override
        public synchronized List<Entry> active(String workspaceId, String threadId) {
            return entries.values().stream().filter(value -> value.workspaceId().equals(workspaceId)
                    && value.threadId().equals(threadId)
                    && Set.of("starting", "running", "stopping").contains(value.state())).toList();
        }

        /** 替换同一持久身份对应的可变运行事实。 */
        @Override
        public synchronized void update(Entry entry) {
            entries.put(entry.sessionId(), entry);
        }

        /** 在输入副作用前登记幂等键，并区分已应用、冲突与未知结果。 */
        @Override
        public synchronized WriteClaim claimWrite(String sessionId, String operationKey, String requestFingerprint,
                                                  Instant createdAt) {
            String key = sessionId + ":" + operationKey;
            Receipt prior = writes.get(key);
            if (prior == null) {
                writes.put(key, new Receipt(requestFingerprint, WriteClaim.PENDING));
                return WriteClaim.CLAIMED;
            }
            if (!prior.fingerprint().equals(requestFingerprint)) {
                return WriteClaim.UNKNOWN;
            }
            return prior.state();
        }

        /** stdin 结果已确定后记录回执，模拟账本幂等而不重复交付输入。 */
        @Override
        public synchronized void resolveWrite(String sessionId, String operationKey, WriteClaim state,
                                              Instant completedAt) {
            String key = sessionId + ":" + operationKey;
            Receipt receipt = writes.get(key);
            if (receipt == null) {
                throw new IllegalStateException("missing process write receipt");
            }
            writes.put(key, new Receipt(receipt.fingerprint(), state));
        }

        /** 旧代际仅恢复中断事实，测试账本不会复活已经失去原生所有权的进程。 */
        @Override
        public synchronized void interruptOtherGenerations(long generation, Instant endedAt) {
            for (Entry entry : List.copyOf(entries.values())) {
                if (entry.generation() < generation
                        && Set.of("starting", "running", "stopping").contains(entry.state())) {
                    entries.put(entry.sessionId(), entry.withRuntime("interrupted", entry.output(),
                            entry.outputSequence(), entry.exitCode(), endedAt));
                }
            }
        }

        /**
         * 用不可变回执模拟输入副作用的持久围栏。
         *
         * @param fingerprint 冻结输入与 EOF 的摘要
         * @param state 已接纳或已完成的输入结果
         */
        private record Receipt(String fingerprint,
                               WriteClaim state) {
        }
    }
}
