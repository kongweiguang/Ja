// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 冻结进程级 Shell 探测结果；Shell 是可选 Tool，缺失时不能阻断配置、历史或 RPC 启动。
 */
public record ShellCapability(ShellProfile.OperatingSystem os, String pathStyle,
                              Optional<ShellProfile> profile) {

    /** 保证可用 Profile 与平台事实一致，避免提示环境和实际执行器分叉。 */
    public ShellCapability {
        os = Objects.requireNonNull(os, "os");
        pathStyle = Objects.requireNonNull(pathStyle, "pathStyle");
        profile = Objects.requireNonNull(profile, "profile");
        if (profile.isPresent()) {
            ShellProfile value = profile.orElseThrow();
            if (value.os() != os || !value.pathStyle().equals(pathStyle)) {
                throw new IllegalArgumentException("shell capability profile does not match platform");
            }
        }
    }

    /** 在生产启动时只探测一次，并把任何不可用结果收敛为显式空能力。 */
    public static ShellCapability detectAndPreflight() {
        return detectAndPreflight(System.getProperty("os.name", ""), System.getenv(),
                ShellProfile::preflight);
    }

    /** 构造已验证能力，供组合边界和不启动真实进程的测试显式注入。 */
    public static ShellCapability available(ShellProfile profile) {
        ShellProfile value = Objects.requireNonNull(profile, "profile");
        return new ShellCapability(value.os(), value.pathStyle(), Optional.of(value));
    }

    /** 构造缺失能力，模型环境会明确禁止 Shell 调用而不是伪装注册失败 Tool。 */
    public static ShellCapability unavailable(ShellProfile.OperatingSystem os, String pathStyle) {
        return new ShellCapability(os, pathStyle, Optional.empty());
    }

    /**
     * 注入完整环境与预检 seam，使回退顺序可在任意测试平台验证，且测试不修改全局环境或启动 Shell。
     */
    static ShellCapability detectAndPreflight(String osName, Map<String, String> environment,
                                               Predicate<ShellProfile> preflight) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(preflight, "preflight");
        String normalized = Objects.requireNonNullElse(osName, "").toLowerCase(Locale.ROOT);
        ShellProfile.OperatingSystem os;
        String pathStyle;
        List<ShellProfile> candidates;
        if (normalized.contains("win")) {
            os = ShellProfile.OperatingSystem.WINDOWS;
            pathStyle = "windows";
            Map<String, String> processEnvironment = ShellProcessEnvironment.capture(os, environment);
            candidates = windowsCandidates(environment, processEnvironment);
        } else if (normalized.contains("mac") || normalized.contains("darwin")) {
            os = ShellProfile.OperatingSystem.MACOS;
            pathStyle = "posix";
            Map<String, String> processEnvironment = ShellProcessEnvironment.capture(os, environment);
            candidates = List.of(new ShellProfile(os, ShellProfile.Dialect.ZSH,
                    Path.of(File.separator, "bin", "zsh"), List.of("-lc"), pathStyle, processEnvironment));
        } else {
            os = ShellProfile.OperatingSystem.LINUX;
            pathStyle = "posix";
            Map<String, String> processEnvironment = ShellProcessEnvironment.capture(os, environment);
            candidates = List.of(new ShellProfile(os, ShellProfile.Dialect.BASH,
                    Path.of(File.separator, "bin", "bash"), List.of("-lc"), pathStyle, processEnvironment));
        }
        for (ShellProfile candidate : candidates) {
            try {
                if (preflight.test(candidate)) {
                    return available(candidate);
                }
            } catch (RuntimeException ignored) {
                // Shell 是可选能力；单个候选的路径或进程异常只淘汰该候选。
            }
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
        }
        return unavailable(os, pathStyle);
    }

    /** PowerShell 7 优先；只有所有 pwsh 候选失败后才尝试 Windows PowerShell 5.1。 */
    private static List<ShellProfile> windowsCandidates(Map<String, String> environment,
                                                         Map<String, String> processEnvironment) {
        List<ShellProfile> candidates = new ArrayList<>();
        String path = environmentValue(environment, "PATH");
        if (path != null) {
            for (String directory : path.split(Pattern.quote(File.pathSeparator))) {
                String normalized = stripOptionalQuotes(directory.trim());
                if (normalized.isEmpty()) continue;
                try {
                    candidates.add(new ShellProfile(ShellProfile.OperatingSystem.WINDOWS,
                            ShellProfile.Dialect.POWERSHELL, Path.of(normalized, "pwsh.exe"),
                            List.of("-NoLogo", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                                    "-Command"), "windows", processEnvironment));
                } catch (InvalidPathException ignored) {
                    // 畸形 PATH 项不能阻断后续候选或 App Server 启动。
                }
            }
        }
        String systemRoot = environmentValue(environment, "SystemRoot");
        if (systemRoot != null && !systemRoot.isBlank()) {
            try {
                candidates.add(new ShellProfile(ShellProfile.OperatingSystem.WINDOWS,
                        ShellProfile.Dialect.WINDOWS_POWERSHELL,
                        Path.of(stripOptionalQuotes(systemRoot.trim()), "System32", "WindowsPowerShell", "v1.0",
                                "powershell.exe"),
                        List.of("-NoLogo", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                                "-Command"), "windows", processEnvironment));
            } catch (InvalidPathException ignored) {
                // 缺失系统回退仍只是 Shell 能力缺失，不扩大为服务启动失败。
            }
        }
        return List.copyOf(candidates);
    }

    /** 仅剥离包围整个 PATH 项的一对引号，保留路径内部的合法空格。 */
    private static String stripOptionalQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** Windows 环境名大小写不敏感；测试 Map 与 System.getenv() 都走相同的查找语义。 */
    private static String environmentValue(Map<String, String> environment, String name) {
        return environment.entrySet().stream()
                .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * 始终提供真实平台与 cwd；Shell 缺失时明确标记 unavailable，禁止提示词暗示不存在的 Tool。
     */
    public String executionEnvironment(Path cwd) {
        Path normalizedCwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
        return profile.map(value -> value.executionEnvironment(normalizedCwd)).orElseGet(() -> String.join("\n",
                "<execution_environment>",
                "os: " + os.wireName(),
                "shell: unavailable",
                "cwd: " + normalizedCwd,
                "path_style: " + pathStyle,
                "</execution_environment>",
                "No shell tool is available for this process; do not issue shell tool calls."));
    }
}
