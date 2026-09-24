// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.platform.windows;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** 仅在 Windows 执行的 Job Object 所有权与单调清理契约测试。 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
final class WindowsJobObjectTest {
    /** 文件和进程状态轮询间隔，在测试速度与系统调用压力之间取平衡。 */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);

    /** 显式限定本地测试平台，避免其它平台静默跳过后被误报为通过。 */
    @BeforeEach
    void requireWindows() {
        assertTrue(WindowsJobObject.isSupported(), "Windows Job Object tests require Windows 11");
    }

    /** 关闭已配置 Job 必须终止只在分配后才创建的延迟子进程。 */
    @Test
    void closeKillsRootAndDescendantAndReleasesHandle(@TempDir Path temp) throws Exception {
        try (ProcessFixture fixture = ProcessFixture.start(temp)) {
            awaitFile(fixture.ready(), Duration.ofSeconds(5));
            long handlesBefore = HandleCounter.current();
            WindowsJobObject job = WindowsJobObject.create();
            try {
                job.assign(fixture.root());
                Files.writeString(fixture.release(), "go", StandardCharsets.UTF_8);
                long childPid = awaitPid(fixture.childPid(), Duration.ofSeconds(5));
                job.close();
                assertTrue(job.isClosed());
                assertTrue(fixture.root().waitFor(5, TimeUnit.SECONDS), "root process must exit");
                assertProcessGone(childPid, Duration.ofSeconds(5));
                awaitHandleCountAtMost(handlesBefore + 1, Duration.ofSeconds(3));
            } finally {
                assertDoesNotThrow(job::close);
            }
        }
    }

    /** 显式终止必须到达同一关闭边界，且不得留下存活后代。 */
    @Test
    void explicitTerminateKillsRootAndDescendant(@TempDir Path temp) throws Exception {
        try (ProcessFixture fixture = ProcessFixture.start(temp)) {
            awaitFile(fixture.ready(), Duration.ofSeconds(5));
            try (WindowsJobObject job = WindowsJobObject.create()) {
                job.assign(fixture.root());
                Files.writeString(fixture.release(), "go", StandardCharsets.UTF_8);
                long childPid = awaitPid(fixture.childPid(), Duration.ofSeconds(5));
                job.terminate();
                assertTrue(job.isClosed());
                assertTrue(fixture.root().waitFor(5, TimeUnit.SECONDS), "root process must exit");
                assertProcessGone(childPid, Duration.ofSeconds(5));
            }
        }
    }

    /** 重复关闭必须无害，关闭后再分配则返回稳定脱敏错误码。 */
    @Test
    void closeIsIdempotentAndRejectsAssignAfterClosed(@TempDir Path temp) throws Exception {
        try (ProcessFixture fixture = ProcessFixture.start(temp)) {
            awaitFile(fixture.ready(), Duration.ofSeconds(5));
            WindowsJobObject job = WindowsJobObject.create();
            job.close();
            assertDoesNotThrow(job::close);
            WindowsJobObject.WindowsJobObjectException failure = assertThrows(
                    WindowsJobObject.WindowsJobObjectException.class, () -> job.assign(fixture.root()));
            assertEquals("windows_job_assign_6", failure.code());
        }
    }

    /** 死进程必须在 OpenProcess 前被拒绝，证明适配器不会保留无效句柄。 */
    @Test
    void rejectsDeadProcessWithoutLeakingJob(@TempDir Path temp) throws Exception {
        Process dead = new ProcessBuilder("cmd.exe", "/d", "/c", "exit", "0")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        assertTrue(dead.waitFor(5, TimeUnit.SECONDS));
        try (WindowsJobObject job = WindowsJobObject.create()) {
            WindowsJobObject.WindowsJobObjectException failure = assertThrows(
                    WindowsJobObject.WindowsJobObjectException.class, () -> job.assign(dead));
            assertEquals("windows_job_assign_process_not_alive", failure.code());
            assertTrue(job.isClosed(), "rejected admission must close the unbound Job Object");
        }
    }

    /** CreateProcess 前拒绝环境块注入，同时调用方持有的 Job 仍可正常关闭。 */
    @Test
    void suspendedLauncherRejectsInvalidEnvironmentName(@TempDir Path temp) throws Exception {
        try (WindowsJobObject job = WindowsJobObject.create()) {
            IOException failure = assertThrows(IOException.class, () -> WindowsProcessLauncher.launch(
                    List.of("pwsh.exe", "-NoProfile", "-Command", "exit 0"), temp,
                    Map.of("SAFE=INJECTED", "value"), job));

            assertEquals("windows_process_environment_invalid", failure.getMessage());
        }
    }

    /** 验证 Job 接纳后的功能退出；冷启动 runner 的系统 PowerShell 可超过 5 秒，保留 20 秒硬上限。 */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void suspendedLauncherRunsSystemPowerShell(@TempDir Path temp) throws Exception {
        Map<String, String> environment = minimalWindowsEnvironment();
        try (WindowsJobObject job = WindowsJobObject.create()) {
            Process process = WindowsProcessLauncher.launch(
                    List.of("powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Milliseconds 100"),
                    temp, environment, job);
            try {
                process.getOutputStream().close();
                assertTrue(process.waitFor(20, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue());
            } finally {
                WindowsProcessLauncher.close(process);
            }
        }
    }

    /**
     * 复现桌面 Host Job -> App Server JVM -> Shell Job 的两级托管链；普通单 JVM 用例只验证
     * 最内层 Job，无法发现父 Job 策略导致的 CreateProcess/AssignProcessToJobObject 差异。
     * 外层预算包含 JVM 与系统 PowerShell 两次冷启动，不把宿主负载当作 Job 语义失败。
     */
    @Test
    @Timeout(value = 40, unit = TimeUnit.SECONDS)
    void nestedHostJobAllowsAppServerToLaunchOwnedPowerShell(@TempDir Path temp) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        List<String> command = List.of(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                NestedShellProbe.class.getName(),
                temp.toAbsolutePath().toString());
        Map<String, String> environment = minimalWindowsEnvironment();

        try (WindowsJobObject hostJob = WindowsJobObject.create()) {
            Process appServer = WindowsProcessLauncher.launch(command, temp, environment, hostJob);
            try {
                appServer.getOutputStream().close();
                assertTrue(appServer.waitFor(30, TimeUnit.SECONDS), "nested App Server probe must settle");
                String stdout = new String(appServer.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String stderr = new String(appServer.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

                assertEquals(0, appServer.exitValue(), "nested probe stderr=" + stderr);
                assertTrue(stdout.contains("nested-shell-ok"), "nested probe stdout=" + stdout);
                hostJob.closeAfterRootExit();
            } finally {
                if (appServer.isAlive()) {
                    hostJob.terminate();
                }
                WindowsProcessLauncher.close(appServer);
            }
        }
    }

    /**
     * 证明关闭精确进程 owner 会取消另一 platform thread 上的同步匿名管道读取；先观察有界超时，
     * 再要求 close 后得到 EOF，避免测试只覆盖尚未进入 ReadFile 的快速路径。
     */
    @Test
    @SuppressWarnings("PMD.CloseResource")
    void nativeProcessCloseCancelsPendingPipeRead(@TempDir Path temp) throws Exception {
        Map<String, String> environment = Map.of(
                "SystemRoot", System.getenv("SystemRoot"),
                "PATH", System.getenv("PATH"));
        ExecutorService reader = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("ja-pipe-cancel-test").factory());
        long processId = -1L;
        try (WindowsJobObject job = WindowsJobObject.create()) {
            Process process = WindowsProcessLauncher.launch(
                    List.of("cmd.exe", "/d", "/q", "/c",
                            "ping.exe -n 31 127.0.0.1 > nul"),
                    temp, environment, job);
            processId = process.pid();
            CountDownLatch entered = new CountDownLatch(1);
            Future<Integer> pendingRead = reader.submit(() -> {
                entered.countDown();
                return process.getInputStream().read();
            });
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS), "pipe reader did not start");
                assertThrows(TimeoutException.class,
                        () -> pendingRead.get(100, TimeUnit.MILLISECONDS));

                WindowsProcessLauncher.close(process);

                assertEquals(-1, pendingRead.get(2, TimeUnit.SECONDS));
            } finally {
                WindowsProcessLauncher.close(process);
            }
        } finally {
            reader.shutdownNow();
            assertTrue(reader.awaitTermination(2, TimeUnit.SECONDS),
                    "pipe reader executor did not terminate");
            if (processId > 0) {
                assertProcessGone(processId, Duration.ofSeconds(3));
            }
        }
    }

    /** PATH 中的普通数据文件不能因存在就取得执行资格，扩展名边界由策略先行收紧。 */
    @Test
    void executableResolutionRejectsNonExecutableExtension(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("payload.txt"), "not executable", StandardCharsets.UTF_8);
        WindowsProcessNativeApi.WindowsFailure failure = assertThrows(
                WindowsProcessNativeApi.WindowsFailure.class,
                () -> WindowsProcessLaunchPolicy.resolveExecutable(
                        "payload.txt", Map.of("PATH", temp.toString())));
        assertEquals(2, failure.error());
    }

    /** PATH 解析支持 Windows 的 PATHEXT 顺序，并将标准 npx shim 映射为不经 shell 的 Node argv。 */
    @Test
    @Timeout(value = 12, unit = TimeUnit.SECONDS)
    void resolvesNodeAndStandardNpxFromHostPath(@TempDir Path temp) throws Exception {
        Map<String, String> environment = System.getenv();
        String node = WindowsProcessLaunchPolicy.resolveExecutable("node", environment);
        assertTrue(node.toLowerCase(java.util.Locale.ROOT).endsWith("node.exe"));
        Path firstNodeOnPath = firstPathRegularFile("node.exe", environment);
        assertTrue(firstNodeOnPath.toString().equalsIgnoreCase(node),
                "the first PATH Node must win after resolving its parent junction");

        WindowsProcessLaunchPolicy.LaunchSpec npxSpec = WindowsProcessLaunchPolicy.validate(
                List.of("npx", "--version"), temp, environment);
        assertTrue(Path.of(npxSpec.command().getFirst()).toString().equalsIgnoreCase(firstNodeOnPath.toString()),
                "standard npx shim should map to the PATH-selected sibling Node; argv=" + npxSpec.command());
        assertTrue(npxSpec.command().get(1).toLowerCase(java.util.Locale.ROOT).endsWith("npx-cli.js"));
        assertFalse(npxSpec.toString().contains("--version"), "launch diagnostics must not echo argv");
        WindowsProcessLauncher.verifyExecutableAvailable(List.of("node", "--version"), temp, environment);
        WindowsProcessLauncher.verifyExecutableAvailable(List.of("npx", "--version"), temp, environment);

        String diagnostics = WindowsProcessLaunchPolicy.validate(
                List.of("node"), temp, Map.of("MCP_TOKEN", "credential-value-fixture")).toString();
        assertFalse(diagnostics.contains("credential-value-fixture"), "launch diagnostics must not expose Secrets");
        assertFalse(diagnostics.contains("MCP_TOKEN"), "launch diagnostics must not expose environment names");

        String nodeVersion = runVersion(List.of("node", "--version"), temp, environment);
        assertTrue(nodeVersion.matches("v[0-9]+\\.[0-9]+\\.[0-9]+.*"),
                "node should report a version from the inherited PATH");
        String npxVersion = runVersion(List.of("npx", "--version"), temp, environment);
        assertTrue(npxVersion.matches("[0-9]+\\.[0-9]+\\.[0-9]+.*"),
                "standard npx CLI should run from the inherited PATH");
    }

    /** NVM active junction 只作为 PATH 目录别名解析，运行时继续使用物理目录中的首个 Node exe。 */
    @Test
    void resolvesNodeThroughNvmParentJunction(@TempDir Path temp) throws Exception {
        Map<String, String> environment = System.getenv();
        String nvmLinkValue = environment.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("NVM_SYMLINK"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        assumeTrue(nvmLinkValue != null && !nvmLinkValue.isBlank());
        Path nvmLink = Path.of(nvmLinkValue);
        Path logicalNode = nvmLink.resolve("node.exe");
        assumeTrue(Files.isRegularFile(logicalNode));
        Path physicalDirectory = nvmLink.toRealPath();
        assumeTrue(!nvmLink.toAbsolutePath().normalize().toString().equalsIgnoreCase(physicalDirectory.toString()));
        Path physicalNode = physicalDirectory.resolve("node.exe").toRealPath();
        assumeTrue(firstPathRegularFile("node.exe", environment).toString().equalsIgnoreCase(physicalNode.toString()));

        String resolved = WindowsProcessLaunchPolicy.resolveExecutable("node", environment);

        assertTrue(physicalNode.toString().equalsIgnoreCase(resolved),
                "PATH resolution should return the NVM junction target, not a later Node installation");
    }

    /** PATHEXT 限制扩展名搜索；其它批处理脚本不能绕过只执行 exe 的边界。 */
    @Test
    void pathExtAndNonstandardCommandShimsFailClosed(@TempDir Path temp) throws Exception {
        Path bin = temp.resolve("bin");
        Files.createDirectories(bin);
        Files.writeString(bin.resolve("node.exe"), "fixture", StandardCharsets.UTF_8);
        Files.writeString(bin.resolve("npx.cmd"), "@echo off\r\necho not standard\r\n", StandardCharsets.UTF_8);
        Files.writeString(bin.resolve("other.cmd"), "@echo off\r\n", StandardCharsets.UTF_8);
        Map<String, String> environment = Map.of("Path", bin.toString(), "PATHEXT", ".CMD;.EXE");

        IOException npxFailure = assertThrows(IOException.class,
                () -> WindowsProcessLaunchPolicy.validate(List.of("npx", "--version"), temp, environment));
        assertEquals("windows_process_npx_layout_invalid", npxFailure.getMessage());
        WindowsProcessNativeApi.WindowsFailure commandFileFailure = assertThrows(
                WindowsProcessNativeApi.WindowsFailure.class,
                () -> WindowsProcessLaunchPolicy.resolveExecutable("other", environment));
        assertEquals(2, commandFileFailure.error());

        WindowsProcessNativeApi.WindowsFailure pathextFailure = assertThrows(
                WindowsProcessNativeApi.WindowsFailure.class,
                () -> WindowsProcessLaunchPolicy.resolveExecutable(
                        "node", Map.of("PATH", bin.toString(), "PATHEXT", ".CMD")));
        assertEquals(2, pathextFailure.error());
    }

    /** 标准 npx shim 仅经大小写不敏感 PATH/PATHEXT 精确发现，并映射为同目录 Node/npm CLI 字面 argv。 */
    @Test
    void mapsStandardNpxShimUsingCaseInsensitivePathAndPathExt(@TempDir Path temp) throws Exception {
        Path bin = temp.resolve("bin");
        Path node = bin.resolve("node.exe");
        Path cli = bin.resolve("node_modules").resolve("npm").resolve("bin").resolve("npx-cli.js");
        Files.createDirectories(cli.getParent());
        Files.writeString(node, "fixture-node", StandardCharsets.UTF_8);
        Files.writeString(cli, "fixture-cli", StandardCharsets.UTF_8);
        Files.writeString(bin.resolve("npx.cmd"),
                "@echo off\r\n\"%~dp0\\node.exe\" \"%~dp0\\node_modules\\npm\\bin\\npx-cli.js\" %*\r\n",
                StandardCharsets.UTF_8);
        Map<String, String> environment = Map.of("pAtH", bin.toString(), "pAtHeXt", ".CMD;.EXE");

        WindowsProcessLaunchPolicy.LaunchSpec spec = WindowsProcessLaunchPolicy.validate(
                List.of("npx", "--version"), temp, environment);

        assertEquals(node.toRealPath().toString(), spec.command().get(0));
        assertEquals(cli.toRealPath().toString(), spec.command().get(1));
        assertEquals("--version", spec.command().get(2));
    }

    /** 环境变量大小写冲突不依赖 Map 遍历次序选择值，避免 PATH 被重复定义时预检查与启动漂移。 */
    @Test
    void launchPolicyRejectsCaseInsensitiveEnvironmentConflicts(@TempDir Path temp) {
        Map<String, String> environment = Map.of("PATH", "C:\\first", "Path", "C:\\second");

        IOException failure = assertThrows(IOException.class,
                () -> WindowsProcessLaunchPolicy.validate(List.of("node"), temp, environment));

        assertEquals("windows_process_environment_invalid", failure.getMessage());
    }

    /** 使用正式受控启动与 Job 清理验证命令预检查之后的实际 argv 执行结果。 */
    @SuppressWarnings("PMD.CloseResource")
    private static String runVersion(List<String> command, Path temp, Map<String, String> environment)
            throws Exception {
        try (WindowsJobObject job = WindowsJobObject.create()) {
            Process process = WindowsProcessLauncher.launch(command, temp, environment, job);
            try {
                process.getOutputStream().close();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "version command must settle");
                String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, process.exitValue(), "version command failed; stderrEmpty=" + stderr.isEmpty());
                return stdout;
            } finally {
                WindowsProcessLauncher.close(process);
            }
        }
    }

    /** 按 PATH 顺序解析第一个普通文件的物理路径，固定父目录 junction 不改变命令优先级。 */
    private static Path firstPathRegularFile(String fileName, Map<String, String> environment) throws IOException {
        String path = environment.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("PATH"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
        for (String directory : path.split(";", -1)) {
            if (directory.isBlank()) continue;
            Path candidate = Path.of(directory).resolve(fileName);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(candidate)) continue;
            BasicFileAttributes attributes = Files.readAttributes(
                    candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isRegularFile() && !attributes.isOther()) return candidate.toRealPath();
        }
        throw new IOException("test_path_executable_unavailable");
    }

    /** 挂起创建前拒绝 junction 工作目录，确保物理路径固定不可绕过。 */
    @Test
    void suspendedLauncherRejectsJunctionWorkingDirectory(
            @TempDir Path temp, @TempDir Path outside) throws Exception {
        Path junction = temp.resolve("cwd-junction");
        Process helper = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                junction.toString(), outside.toString()).redirectErrorStream(true).start();
        helper.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        assertEquals(0, helper.waitFor());
        try (WindowsJobObject job = WindowsJobObject.create()) {
            IOException failure = assertThrows(IOException.class, () -> WindowsProcessLauncher.launch(
                    List.of("pwsh.exe", "-NoProfile", "-Command", "exit 0"), junction, Map.of(), job));

            assertEquals("windows_process_cwd_invalid", failure.getMessage());
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    /** 以硬截止时间等待标记，防止失败 shell 挂死测试线程。 */
    private static void awaitFile(Path path, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (Files.isRegularFile(path)) {
                return;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        throw new AssertionError("timed out waiting for Windows process marker");
    }

    /**
     * 仅在 Windows 共享冲突时重试，直到写入方关闭句柄；进程与 Job 断言保持不变，其它文件系统
     * 失败仍立即抛出。
     */
    private static long awaitPid(Path path, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        java.nio.file.FileSystemException sharingFailure = null;
        while (Instant.now().isBefore(deadline)) {
            if (Files.isRegularFile(path)) {
                try {
                    return Long.parseLong(Files.readString(path, StandardCharsets.UTF_8).trim());
                } catch (java.nio.file.FileSystemException failure) {
                    sharingFailure = failure;
                }
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        AssertionError timeoutFailure = new AssertionError("timed out waiting for readable child PID");
        if (sharingFailure != null) timeoutFailure.initCause(sharingFailure);
        throw timeoutFailure;
    }

    /** 按单个 PID 轮询 tasklist，使测试无需 Java 进程枚举即可证明后代已清理。 */
    private static void assertProcessGone(long pid, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!processExists(pid)) {
                return;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        throw new AssertionError("descendant process remained alive: " + pid);
    }

    /** 通过 Windows 诊断命令查询精确 PID，且不修改进程状态。 */
    private static boolean processExists(long pid) throws Exception {
        Process probe = new ProcessBuilder("tasklist", "/fi", "PID eq " + pid, "/fo", "csv", "/nh")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            byte[] bytes;
            try (java.io.InputStream output = probe.getInputStream()) {
                bytes = output.readAllBytes();
            }
            if (!probe.waitFor(2, TimeUnit.SECONDS)) {
                probe.destroyForcibly();
                return true;
            }
            return probe.exitValue() == 0
                    && new String(bytes, StandardCharsets.UTF_8).contains("\"" + pid + "\"");
        } finally {
            probe.getErrorStream().close();
            probe.getOutputStream().close();
        }
    }

    /**
     * 只向嵌套 JVM 传递启动 Java/PowerShell 所需的非敏感变量，保持与 Rust Host env_clear
     * 边界一致，避免开发终端的完整环境掩盖托管差异。
     */
    private static Map<String, String> minimalWindowsEnvironment() {
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        for (String name : List.of("SystemRoot", "ComSpec", "PATH", "PATHEXT", "TEMP", "TMP", "PSModuleAnalysisCachePath")) {
            String value = System.getenv(name);
            if (value != null) {
                values.put(name, value);
            }
        }
        return Map.copyOf(values);
    }

    /** 为关闭后的句柄释放预留短暂收敛窗口。 */
    private static void awaitHandleCountAtMost(long maximum, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (HandleCounter.current() <= maximum) {
                return;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        long observed = HandleCounter.current();
        assertTrue(observed <= maximum,
                "Job handle remained owned after close: observed=" + observed + ", maximum=" + maximum);
    }

    /** 使用显式 ready、release、child-PID 文件协调的有界 PowerShell 根进程夹具。 */
    private record ProcessFixture(Process root, Path ready, Path release, Path childPid) implements AutoCloseable {
        /**
         * 创建延迟后代夹具，确保 Java 侧分配根进程前子进程不会出现。测试失败时夹具只销毁根进程；
         * release 标记写入后的全部路径均由 Job 持有后代。
         */
        private static ProcessFixture start(Path temp) throws IOException {
            Path ready = temp.resolve("ready.marker");
            Path release = temp.resolve("release.marker");
            Path childPid = temp.resolve("child.pid");
            String script = "$ErrorActionPreference='Stop'; "
                    + "Set-Content -LiteralPath " + quote(ready) + " -Value ready; "
                    + "while (-not (Test-Path -LiteralPath " + quote(release)
                    + ")) { Start-Sleep -Milliseconds 20 }; "
                    + "$child=Start-Process -FilePath $env:ComSpec -ArgumentList @('/d','/c','ping.exe',"
                    + "'127.0.0.1','-n','120') -PassThru; "
                    + "Set-Content -LiteralPath " + quote(childPid) + " -Value ([string]$child.Id); "
                    + "Start-Sleep -Seconds 120";
            Process root = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command", script)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return new ProcessFixture(root, ready, release, childPid);
        }

        /** 将临时路径转义为 PowerShell 单引号字面量。 */
        private static String quote(Path path) {
            return "'" + path.toAbsolutePath().toString().replace("'", "''") + "'";
        }

        /** 适配器关闭 Job 后，仅以根进程为边界执行夹具兜底清理。 */
        @Override
        public void close() throws Exception {
            if (root.isAlive()) {
                root.destroyForcibly();
                root.waitFor(3, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * 通过 Kernel32 读取当前进程句柄数，使测试能独立于 Java 生命周期标志发现泄漏的 Job 句柄。
     */
    private static final class HandleCounter {
        /** 使用受限 FFM Arena 查询 GetProcessHandleCount，不保留持久本地状态。 */
        private static long current() {
            try (Arena libraryArena = Arena.ofConfined(); Arena callArena = Arena.ofConfined()) {
                Linker linker = Linker.nativeLinker();
                SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", libraryArena);
                MethodHandle currentProcess = linker.downcallHandle(kernel32.findOrThrow("GetCurrentProcess"),
                        FunctionDescriptor.of(ValueLayout.ADDRESS));
                MethodHandle handleCount = linker.downcallHandle(kernel32.findOrThrow("GetProcessHandleCount"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS));
                MemorySegment process = (MemorySegment) currentProcess.invokeWithArguments();
                MemorySegment count = callArena.allocate(ValueLayout.JAVA_INT);
                int result = (int) handleCount.invokeWithArguments(process, count);
                assertEquals(1, result, "GetProcessHandleCount failed");
                return Integer.toUnsignedLong(count.get(ValueLayout.JAVA_INT, 0));
            } catch (Throwable failure) {
                throw new AssertionError("cannot query Windows handle count", failure);
            }
        }
    }

    /**
     * 独立 JVM 入口模拟由 Rust Job 托管的 App Server；它再创建 Shell Job 并执行真实
     * PowerShell，使测试包含生产中缺失于普通 ShellToolTest 的父 Job 层级。
     */
    public static final class NestedShellProbe {
        /** 工具入口由父测试以绝对 cwd 启动，任何异常都转成短脱敏 stderr 与非零退出码。 */
        public static void main(String[] arguments) {
            try {
                Path workingDirectory = Path.of(arguments[0]).toAbsolutePath().normalize();
                String systemRoot = System.getenv("SystemRoot");
                Path powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0",
                        "powershell.exe");
                try (WindowsJobObject shellJob = WindowsJobObject.create()) {
                    Process shell = WindowsProcessLauncher.launch(
                            List.of(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                                    "-Command", "Write-Output 'nested-shell-ok'"),
                            workingDirectory,
                            minimalWindowsEnvironment(),
                            shellJob);
                    try {
                        shell.getOutputStream().close();
                        if (!shell.waitFor(20, TimeUnit.SECONDS)) {
                            throw new IOException("nested_shell_timeout");
                        }
                        String stdout = new String(shell.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                        String stderr = new String(shell.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                        if (shell.exitValue() != 0 || !stdout.contains("nested-shell-ok")) {
                            throw new IOException("nested_shell_failed exit=" + shell.exitValue()
                                    + " stderrEmpty=" + stderr.isEmpty());
                        }
                        shellJob.closeAfterRootExit();
                        System.out.print(stdout);
                    } finally {
                        if (shell.isAlive()) {
                            shellJob.terminate();
                        }
                        WindowsProcessLauncher.close(shell);
                    }
                }
            } catch (Exception failure) {
                System.err.println(failure.getClass().getSimpleName() + ":" + failure.getMessage());
                Throwable cause = failure.getCause();
                if (cause != null) {
                    System.err.println(cause.getClass().getSimpleName() + ":" + cause.getMessage());
                }
                System.exit(90);
            }
        }

        /** 该类型只作为独立 JVM 入口，不允许实例化。 */
        private NestedShellProbe() {
        }
    }
}
