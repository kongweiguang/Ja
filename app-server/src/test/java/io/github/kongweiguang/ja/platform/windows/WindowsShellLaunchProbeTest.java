// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.platform.windows;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** 在同一 runner 中对照环境与启动机制，只执行固定回显且并发排空受限输出。 */
@EnabledOnOs(OS.WINDOWS)
final class WindowsShellLaunchProbeTest {
    /** 每个候选均有硬时限；一轮输出全部差异，不以增加等待预算掩盖真实阻塞。 */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void comparePipeLaunchEnvironments(@TempDir Path temp) throws Exception {
        List<String> runtime = List.of("SystemRoot", "PATH", "ComSpec", "PATHEXT", "TEMP", "TMP");
        Map<String, String> base = new LinkedHashMap<>();
        for (String name : runtime) {
            String value = System.getenv(name);
            if (value != null) base.put(name, value);
        }
        Map<String, String> inherited = new LinkedHashMap<>();
        System.getenv().forEach((name, value) -> {
            if (name.matches("[A-Za-z_][A-Za-z0-9_]*")) inherited.put(name, value);
        });
        // 仅作为固定脚本的诊断对照，生产 Shell 白名单不采用这份环境，也不记录其值。
        org.junit.jupiter.api.Assertions.assertTrue(probe(temp, inherited, false));
        List<String> candidates = inherited.keySet().stream()
                .filter(name -> runtime.stream().noneMatch(name::equalsIgnoreCase)).sorted().toList();
        while (candidates.size() > 1) {
            int middle = candidates.size() / 2;
            List<String> left = candidates.subList(0, middle);
            List<String> right = candidates.subList(middle, candidates.size());
            if (probe(temp, selected(base, inherited, left), false)) candidates = left;
            else if (probe(temp, selected(base, inherited, right), false)) candidates = right;
            else {
                System.out.println("JA_ENV_INTERACTION_KEYS=" + candidates);
                return;
            }
        }
        System.out.println("JA_REQUIRED_ENV_KEY=" + candidates);
        org.junit.jupiter.api.Assertions.assertTrue(probe(temp, selected(base, inherited, candidates), true));
    }

    /** 只组合本轮对照指定的变量，大小写别名不重复加入环境块。 */
    private static Map<String, String> selected(Map<String, String> base, Map<String, String> source,
                                                List<String> names) {
        Map<String, String> result = new LinkedHashMap<>(base);
        for (String name : names) result.put(name, source.get(name));
        return result;
    }

    /** 两种启动器共用相同参数、环境、cwd 与排空策略，输出不包含环境值或用户文件。 */
    private static boolean probe(Path temp, Map<String, String> environment, boolean nativeLaunch) throws Exception {
        List<String> command = List.of(Path.of(System.getenv("SystemRoot"), "System32",
                "WindowsPowerShell", "v1.0", "powershell.exe").toString(),
                "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
                "[Console]::WriteLine('JA_BOOT'); Write-Output 'JA_CMDLET'; exit 0");
        try (WindowsJobObject job = WindowsJobObject.create()) {
            Process process;
            if (nativeLaunch) {
                process = WindowsProcessLauncher.launch(command, temp, environment, job);
            } else {
                ProcessBuilder builder = new ProcessBuilder(command).directory(temp.toFile());
                builder.environment().clear();
                builder.environment().putAll(environment);
                process = builder.start();
            }
            try {
                process.getOutputStream().close();
                CompletableFuture<String> stdout = capture(process.getInputStream());
                CompletableFuture<String> stderr = capture(process.getErrorStream());
                boolean exited = process.waitFor(6, TimeUnit.SECONDS);
                if (!exited) process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
                System.out.println("JA_SHELL_PROBE native=" + nativeLaunch + " keyCount=" + environment.size()
                        + " exited=" + exited + " stdout=" + stdout.get(3, TimeUnit.SECONDS)
                        + " stderr=" + stderr.get(3, TimeUnit.SECONDS));
                return exited && stdout.get(3, TimeUnit.SECONDS).contains("JA_CMDLET");
            } finally {
                if (nativeLaunch) WindowsProcessLauncher.close(process);
                else if (process.isAlive()) process.destroyForcibly();
            }
        }
    }

    /** 捕获固定脚本的少量输出后继续排空，避免管道背压成为对照实验中的第三个变量。 */
    private static CompletableFuture<String> capture(InputStream input) {
        CompletableFuture<String> result = new CompletableFuture<>();
        Thread.ofPlatform().daemon().start(() -> {
            try (input) {
                byte[] preview = input.readNBytes(1024);
                input.transferTo(java.io.OutputStream.nullOutputStream());
                result.complete(new String(preview, StandardCharsets.UTF_8).replace('\n', ' ').replace('\r', ' '));
            } catch (Exception failure) {
                result.complete("io:" + failure.getClass().getSimpleName());
            }
        });
        return result;
    }
}
