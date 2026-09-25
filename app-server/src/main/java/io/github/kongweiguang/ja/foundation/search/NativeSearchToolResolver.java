// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.search;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 解析 native 搜索可执行文件，但不把当前工作目录误当成资源目录。
 *
 * <p>打包后的 Native App Server 将 {@code fd} 和 {@code rg} 放在自身可执行文件旁的
 * {@code tools/} 中；JVM 开发和测试则使用继承的 PATH。Java 启动器目录不是应用资源目录，
 * 因此这两条路径必须明确区分。</p>
 */
public final class NativeSearchToolResolver {
    private static final List<String> FD_NAMES = List.of("fd", "fdfind");
    private static final String RG_NAME = "rg";
    private final Map<String, Path> fixedExecutables;
    private final Map<String, String> environment;

    /** 延迟解析生产搜索工具，使缺失工具只在实际调用时报告，不影响其它内置能力注册。 */
    public static NativeSearchToolResolver system() {
        return system(System.getenv());
    }

    /** 绑定本 Turn 的完整客户端环境，搜索可执行文件解析与真正启动保持一致。 */
    public static NativeSearchToolResolver system(Map<String, String> environment) {
        return new NativeSearchToolResolver(Map.of(), environment);
    }

    /** 注入明确的 fd/rg 路径；调用方可用空值表示该工具交由系统解析。 */
    public NativeSearchToolResolver(Path fd, Path rg) {
        this(fixedExecutables(fd, rg), System.getenv());
    }

    /** 把可选注入路径收敛为不可变表，避免解析过程观察到调用方后续修改。 */
    private static Map<String, Path> fixedExecutables(Path fd, Path rg) {
        Map<String, Path> executables = new LinkedHashMap<>();
        if (fd != null) executables.put("fd", fd);
        if (rg != null) executables.put(RG_NAME, rg);
        return executables;
    }

    /** 按注入路径、Native 旁路资源、宿主 PATH 的优先级解析工具；缺失时不回退 Java 递归扫描。 */
    public Path resolve(String toolName) throws IOException {
        Objects.requireNonNull(toolName, "toolName");
        Path fixed = fixedExecutables.get(toolName);
        if (fixed != null) return validate(toolName, fixed);

        Optional<Path> packaged = packaged(toolName);
        if (packaged.isPresent()) return validate(toolName, packaged.orElseThrow());

        List<String> names = "fd".equals(toolName) ? FD_NAMES : List.of(RG_NAME);
        for (String name : names) {
            Optional<Path> path = fromPath(name);
            if (path.isPresent()) return validate(toolName, path.orElseThrow());
        }
        throw new SearchToolUnavailableException(toolName);
    }

    /** 只保存归一化的绝对测试路径，生产解析仍独立于该注入表。 */
    private NativeSearchToolResolver(Map<String, Path> fixedExecutables, Map<String, String> environment) {
        Map<String, Path> normalized = new LinkedHashMap<>();
        fixedExecutables.forEach((name, path) -> normalized.put(name,
                Objects.requireNonNull(path, name).toAbsolutePath().normalize()));
        this.fixedExecutables = Map.copyOf(normalized);
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    }

    /** 搜索进程取得冻结副本，不能读取当前长驻后台的启动环境。 */
    public Map<String, String> environment() {
        return environment;
    }

    /**
     * 仅当当前进程命令是 Native App Server 可执行文件时查找旁路工具；明确排除 Java 启动器，
     * 防止把 JDK 目录误当应用目录。故意不检查当前工作目录。
     */
    private static Optional<Path> packaged(String toolName) {
        Optional<String> command = ProcessHandle.current().info().command();
        if (command.isEmpty()) return Optional.empty();
        Path executable;
        try {
            executable = Path.of(command.orElseThrow()).toAbsolutePath().normalize();
        } catch (InvalidPathException invalidPath) {
            return Optional.empty();
        }
        if (isJavaLauncher(executable)) return Optional.empty();
        Path parent = executable.getParent();
        if (parent == null) return Optional.empty();
        Path executableFileName = executable.getFileName();
        if (executableFileName == null) return Optional.empty();
        String executableName = executableFileName.toString().toLowerCase(Locale.ROOT);
        if (!executableName.endsWith(".exe") && executableName.contains("java")) {
            return Optional.empty();
        }
        String fileName = windows() ? toolName + ".exe" : toolName;
        Path candidate = parent.resolve("tools").resolve(fileName);
        return existingRegular(candidate) ? Optional.of(candidate) : Optional.empty();
    }

    /** 查找继承环境中的 PATH，保留宿主 CLI 配置，不拼接 shell 命令，也不改写环境。 */
    private Optional<Path> fromPath(String name) {
        String rawPath = environment.entrySet().stream()
                .filter(entry -> "PATH".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
        if (rawPath == null || rawPath.isBlank()) return Optional.empty();
        String[] entries = rawPath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator));
        for (String entry : entries) {
            if (entry.isBlank()) continue;
            Path directory;
            try {
                directory = Path.of(entry);
            } catch (InvalidPathException invalidPath) {
                continue;
            }
            for (String candidateName : executableNames(name)) {
                Path candidate = directory.resolve(candidateName);
                if (existingRegular(candidate)) return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** 补充 Windows .exe 和可移植名称，同时保留 fd/fdfind 的优先级。 */
    private static List<String> executableNames(String name) {
        if (!windows()) return List.of(name);
        return name.endsWith(".exe") ? List.of(name) : List.of(name + ".exe", name);
    }

    /** 启动 native 进程前拒绝目录和悬空链接。 */
    private static Path validate(String toolName, Path path) throws IOException {
        if (!existingRegular(path)) throw new SearchToolUnavailableException(toolName);
        return path.toAbsolutePath().normalize();
    }

    /**
     * 检查工具是否为普通文件。WinGet、Homebrew 等 PATH 管理器会提供可执行 shim，
     * 所以工具安装链接允许解析；搜索数据路径仍必须单独经过 WorkspaceBoundary 复核。
     */
    private static boolean existingRegular(Path path) {
        return Files.isRegularFile(path);
    }

    /** 在检查旁路工具前识别 Java 及同类启动器。 */
    private static boolean isJavaLauncher(Path executable) {
        Path fileName = executable.getFileName();
        if (fileName == null) return false;
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        return name.equals("java") || name.equals("java.exe")
                || name.equals("javaw") || name.equals("javaw.exe")
                || name.equals("javac") || name.equals("javac.exe");
    }

    /** 集中处理平台分支，使测试复用打包 Native App Server 的命名而不引入 shell 抽象。 */
    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** 由文件 Tool 使用的闭集查找错误，明确说明 fd/rg 安装缺失。 */
    public static final class SearchToolUnavailableException extends IOException {
        private final String toolName;

        /** 将工具名限制在可信小集合内，渲染诊断时不会泄露路径。 */
        private SearchToolUnavailableException(String toolName) {
            super("search_tool_unavailable");
            if (!toolName.equals("fd") && !toolName.equals("rg")) {
                throw new IllegalArgumentException("unsupported search tool");
            }
            this.toolName = toolName;
        }

        /** 返回固定工具名，供模型可见的安全诊断使用。 */
        public String toolName() {
            return toolName;
        }
    }
}
