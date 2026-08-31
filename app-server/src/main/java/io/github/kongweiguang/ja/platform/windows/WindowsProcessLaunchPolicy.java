// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.platform.windows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 在创建本地资源前校验并冻结不可信启动描述。
 */
final class WindowsProcessLaunchPolicy {
    /**
     * 单次启动允许的参数数量上限。
     */
    static final int MAX_ARGUMENTS = 128;
    /**
     * 单次启动全部参数的字符总量上限。
     */
    static final int MAX_ARGUMENT_CHARACTERS = 128_000;
    /**
     * 单个参数的字符上限。
     */
    private static final int MAX_ARGUMENT_LENGTH = 8_192;
    /**
     * 环境变量条目数量上限。
     */
    private static final int MAX_ENVIRONMENT_ENTRIES = 128;
    /**
     * 单个环境变量值的字符上限。
     */
    private static final int MAX_ENVIRONMENT_VALUE_LENGTH = 8_192;
    /**
     * 允许跨越进程边界的环境变量名称语法。
     */
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    /**
     * 纯策略类型不允许实例化，避免产生无状态对象。
     */
    private WindowsProcessLaunchPolicy() {
    }

    /**
     * 在分配 Kernel32 句柄前复制并校验全部调用方字段，使路径、argv、环境策略不依赖 FFM 生命周期。
     */
    static LaunchSpec validate(
            List<String> command,
            Path workingDirectory,
            Map<String, String> environment) throws IOException {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(environment, "environment");
        return new LaunchSpec(
                validatedCommand(command),
                canonicalDirectory(workingDirectory),
                validatedEnvironment(environment));
    }

    /**
     * 在硬数量和大小上限内复制 argv，同时保留调用方有意传入的空参数。
     */
    private static List<String> validatedCommand(List<String> command) throws IOException {
        if (command.isEmpty() || command.size() > MAX_ARGUMENTS) {
            throw new IOException("windows_process_command_invalid");
        }
        int characters = 0;
        List<String> copy = new ArrayList<>(command.size());
        for (int index = 0; index < command.size(); index++) {
            String value = command.get(index);
            if (value == null || value.indexOf('\0') >= 0 || value.length() > MAX_ARGUMENT_LENGTH
                || (index == 0 && value.isBlank())) {
                throw new IOException("windows_process_command_invalid");
            }
            try {
                characters = Math.addExact(characters, value.length());
            } catch (ArithmeticException overflow) {
                throw new IOException("windows_process_command_invalid");
            }
            if (characters > MAX_ARGUMENT_CHARACTERS) {
                throw new IOException("windows_process_command_invalid");
            }
            copy.add(value);
        }
        return List.copyOf(copy);
    }

    /**
     * 本地进程创建前把 cwd 固定到一个存在且非重解析的物理目录。
     */
    private static Path canonicalDirectory(Path workingDirectory) throws IOException {
        Path absolute = workingDirectory.toAbsolutePath().normalize();
        rejectLinkOrReparse(absolute, "windows_process_cwd_invalid");
        Path physical = absolute.toRealPath();
        if (!Files.isDirectory(physical, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("windows_process_cwd_invalid");
        }
        return physical;
    }

    /**
     * 构造本地环境块前按 Windows 大小写不敏感语义校验环境变量。
     */
    private static Map<String, String> validatedEnvironment(Map<String, String> environment)
            throws IOException {
        if (environment.size() > MAX_ENVIRONMENT_ENTRIES) {
            throw new IOException("windows_process_environment_invalid");
        }
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, String> copy = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || !ENVIRONMENT_NAME.matcher(name).matches() || !names.add(name)
                || value == null || value.length() > MAX_ENVIRONMENT_VALUE_LENGTH
                || value.indexOf('\0') >= 0) {
                throw new IOException("windows_process_environment_invalid");
            }
            copy.put(name, value);
        }
        return Map.copyOf(copy);
    }

    /**
     * 在信任路径身份前拒绝符号链接、junction 和其它重解析别名。
     */
    private static void rejectLinkOrReparse(Path path, String errorCode) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException(errorCode);
        }
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isOther() || !path.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(path.toRealPath())) {
            throw new IOException(errorCode);
        }
    }

    /**
     * 按 Windows 引号和尾部反斜线规则构造命令行。
     */
    static String commandLine(List<String> command) {
        return command.stream()
                .map(WindowsProcessLaunchPolicy::quoteArgument)
                .reduce((left, right) -> left + " " + right)
                .orElseThrow();
    }

    /**
     * 引用单个参数，禁止参数值提前终止命令行语法。
     */
    private static String quoteArgument(String value) {
        if (!value.isEmpty() && value.chars().noneMatch(
                character -> character == ' ' || character == '\t' || character == '"')) {
            return value;
        }
        StringBuilder result = new StringBuilder("\"");
        int slashes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\') {
                slashes++;
            } else if (character == '"') {
                result.append("\\".repeat(slashes * 2 + 1)).append('"');
                slashes = 0;
            } else {
                result.append("\\".repeat(slashes)).append(character);
                slashes = 0;
            }
        }
        result.append("\\".repeat(slashes * 2)).append('"');
        return result.toString();
    }

    /**
     * 构造按 Windows 键语义排序且以双 NUL 结尾的 Unicode 环境块。
     */
    static String environmentBlock(Map<String, String> values) {
        return values.entrySet().stream()
                       .sorted(Map.Entry.comparingByKey(
                               String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder())))
                       .map(entry -> entry.getKey() + "=" + entry.getValue())
                       .reduce((left, right) -> left + "\0" + right)
                       .orElse("") + "\0";
    }

    /**
     * 只解析显式可执行文件或调用方提供的 PATH 白名单，不回退系统搜索。
     */
    static String resolveExecutable(String executable, Map<String, String> environment)
            throws WindowsProcessNativeApi.WindowsFailure {
        Path requested = Path.of(executable);
        if (requested.isAbsolute()) {
            try {
                Path absolute = requested.toAbsolutePath().normalize();
                rejectLinkOrReparse(absolute, "windows_process_executable_invalid");
                if (isWindowsExecutable(absolute)) {
                    return absolute.toRealPath().toString();
                }
            } catch (IOException ignored) {
                throw new WindowsProcessNativeApi.WindowsFailure("executable_not_found", 2);
            }
            throw new WindowsProcessNativeApi.WindowsFailure("executable_not_found", 2);
        }
        if (requested.getNameCount() != 1 || executable.indexOf(':') >= 0
            || executable.contains("/") || executable.contains("\\")) {
            throw new WindowsProcessNativeApi.WindowsFailure("executable_not_found", 2);
        }
        String pathValue = environment.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("PATH"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
        for (String directory : pathValue.split(";", -1)) {
            if (directory.isBlank()) {
                continue;
            }
            Path candidate = Path.of(directory).resolve(requested).toAbsolutePath().normalize();
            try {
                rejectLinkOrReparse(candidate, "windows_process_executable_invalid");
                if (isWindowsExecutable(candidate)) {
                    return candidate.toRealPath().toString();
                }
            } catch (IOException ignored) {
                // 仅在显式 PATH 白名单中继续查找，不启用任何备用解析路径。
            }
        }
        throw new WindowsProcessNativeApi.WindowsFailure("executable_not_found", 2);
    }

    /**
     * Windows 没有 POSIX execute 位；只接受真实常规 `.exe`，最终格式仍由 CreateProcessW 校验。
     */
    private static boolean isWindowsExecutable(Path candidate) {
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Path fileName = candidate.getFileName();
        return fileName != null && fileName.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".exe");
    }

    /**
     * 从策略校验传入本地接纳边界的不可变启动输入。
     */
    record LaunchSpec(List<String> command, Path workingDirectory, Map<String, String> environment) {
        /**
         * 防止调用方后续修改已授权的命令、目录或环境边界。
         */
        LaunchSpec {
            command = List.copyOf(command);
            workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
            environment = Map.copyOf(environment);
        }
    }
}
