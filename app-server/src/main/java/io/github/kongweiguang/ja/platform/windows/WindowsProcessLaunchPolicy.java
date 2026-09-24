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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

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
     * 纯策略类型不允许实例化，避免产生无状态对象。
     */
    private WindowsProcessLaunchPolicy() {
    }

    /**
     * 在分配 Kernel32 句柄前复制并校验全部调用方字段；仅将标准 npx.cmd 映射为同目录 Node/npm CLI，
     * 使 PATH/PATHEXT 预检查与实际启动共享完全相同的字面 argv 和环境快照。
     */
    static LaunchSpec validate(
            List<String> command,
            Path workingDirectory,
            Map<String, String> environment) throws IOException {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(environment, "environment");
        List<String> safeCommand = validatedCommand(command);
        Path safeWorkingDirectory = canonicalDirectory(workingDirectory);
        Map<String, String> validated = validatedEnvironment(environment);
        return new LaunchSpec(
                validatedCommand(normalizeNpxCommand(safeCommand, validated)), safeWorkingDirectory, validated);
    }

    /**
     * 把唯一允许的 npm npx.cmd shim 展开为同目录 node.exe 与 npm CLI 文件；绝不把任意批处理交给命令解释器。
     */
    private static List<String> normalizeNpxCommand(List<String> command, Map<String, String> environment)
            throws IOException {
        String executable = command.getFirst();
        if (executable == null) throw new IOException("windows_process_executable_invalid");
        if (!isNpxCommand(executable)) {
            return command;
        }
        Path candidate = findExecutableCandidate(executable, environment);
        if (candidate == null) {
            return command;
        }
        Path candidateName = candidate.getFileName();
        if (candidateName == null) throw new IOException("windows_process_npx_layout_invalid");
        if (!candidateName.toString().toLowerCase(Locale.ROOT).endsWith(".cmd")) return command;
        Path shim = checkedRegularFile(candidate, "windows_process_npx_layout_invalid");
        String shimText = Files.readString(shim, java.nio.charset.StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT).replace('/', '\\');
        if (!shimText.contains("%~dp0\\node.exe")
            || !shimText.contains("%~dp0\\node_modules\\npm\\bin\\npx-cli.js")) {
            throw new IOException("windows_process_npx_layout_invalid");
        }
        Path directory = shim.getParent();
        if (directory == null) {
            throw new IOException("windows_process_npx_layout_invalid");
        }
        Path node = checkedRegularFile(directory.resolve("node.exe"), "windows_process_npx_layout_invalid");
        Path cli = checkedRegularFile(
                directory.resolve("node_modules").resolve("npm").resolve("bin").resolve("npx-cli.js"),
                "windows_process_npx_layout_invalid");
        List<String> mapped = new ArrayList<>(command.size() + 1);
        mapped.add(node.toString());
        mapped.add(cli.toString());
        mapped.addAll(command.subList(1, command.size()));
        return List.copyOf(mapped);
    }

    /**
     * 按 Windows PATH 与 PATHEXT 顺序查找 exe；仅为 npx 额外返回 `.cmd`，供调用方验证标准 shim 后映射。
     */
    private static Path findExecutableCandidate(String executable, Map<String, String> environment)
            throws IOException {
        Path requested = Path.of(executable);
        Path name = requested.getFileName();
        if (name == null) {
            return null;
        }
        String fileName = name.toString();
        String extension = extension(fileName);
        List<String> extensions = pathExtensions(environment);
        boolean explicitPath = requested.isAbsolute();
        if (!explicitPath && (requested.getNameCount() != 1 || executable.indexOf(':') >= 0
                || executable.contains("/") || executable.contains("\\"))) {
            return null;
        }
        if (explicitPath) {
            Path parent = requested.toAbsolutePath().normalize().getParent();
            if (parent == null) {
                return null;
            }
            if (!extension.isEmpty()) {
                return allowedCandidate(parent.resolve(fileName), extension, fileName, extensions, true, false);
            }
            for (String suffix : extensions) {
                Path candidate = allowedCandidate(
                        parent.resolve(fileName + suffix), suffix, fileName, extensions, false, false);
                if (candidate != null) {
                    return candidate;
                }
            }
            return null;
        }
        String path = environment.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("PATH"))
                .map(Map.Entry::getValue)
                .findFirst().orElse("");
        if (!extension.isEmpty()) {
            for (String directory : path.split(";", -1)) {
                if (directory.isBlank()) continue;
                Path candidate = allowedCandidate(Path.of(directory).resolve(fileName), extension,
                        fileName, extensions, true, true);
                if (candidate != null) return candidate;
            }
            return null;
        }
        for (String directory : path.split(";", -1)) {
            if (directory.isBlank()) continue;
            for (String suffix : extensions) {
                Path candidate = allowedCandidate(Path.of(directory).resolve(fileName + suffix), suffix,
                        fileName, extensions, false, true);
                if (candidate != null) return candidate;
            }
        }
        return null;
    }

    /**
     * PATH 查询按输入顺序解析 `.exe` 并规范化父目录 junction；仅允许标准 npx `.cmd` 作为待映射候选。
     * 显式路径仍拒绝链接别名，其它脚本格式保持不可执行。
     */
    private static Path allowedCandidate(Path candidate, String extension, String fileName,
                                         List<String> pathExtensions, boolean exactExtension, boolean pathSearch)
            throws IOException {
        String normalizedExtension = extension.toLowerCase(Locale.ROOT);
        if (!exactExtension && !pathExtensions.contains(normalizedExtension)) {
            return null;
        }
        if (normalizedExtension.equals(".exe")) {
            try {
                Path executable = pathSearch
                        ? regularPhysicalFileCandidate(candidate, "windows_process_executable_invalid")
                        : checkedRegularFile(candidate, "windows_process_executable_invalid");
                return isWindowsExecutable(executable) ? executable : null;
            } catch (IOException ignored) {
                return null;
            }
        }
        if (pathSearch && normalizedExtension.equals(".cmd") && isNpxCommand(fileName)) {
            if (!pathExtensions.contains(normalizedExtension)) return null;
            try {
                return regularPhysicalFileCandidate(candidate, "windows_process_npx_layout_invalid");
            } catch (IOException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * PATH 搜索可跨父目录 junction 解析到物理文件，但拒绝候选文件自身的链接或重解析点。
     */
    private static Path regularPhysicalFileCandidate(Path candidate, String errorCode) throws IOException {
        Path absolute = candidate.toAbsolutePath().normalize();
        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(absolute)) {
            throw new IOException(errorCode);
        }
        BasicFileAttributes attributes = Files.readAttributes(
                absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isOther()) {
            throw new IOException(errorCode);
        }
        Path physical = absolute.toRealPath();
        rejectLinkOrReparse(physical, errorCode);
        return physical;
    }

    /**
     * 校验 PATHEXT 中可见的扩展名顺序；缺失时采用 Windows 通用命令扩展名集合。
     */
    private static List<String> pathExtensions(Map<String, String> environment) {
        String value = environment.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("PATHEXT"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(".COM;.EXE;.BAT;.CMD");
        return java.util.Arrays.stream(value.split(";", -1))
                .map(String::trim)
                .filter(extension -> extension.matches("(?i)\\.[A-Z0-9]{1,16}"))
                .map(extension -> extension.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    /**
     * 按文件名判断是否为唯一允许的 npx shim，防止把任意 `.cmd` 扩大为可执行入口。
     */
    private static boolean isNpxCommand(String executable) {
        Path fileName = Path.of(executable).getFileName();
        if (fileName == null) return false;
        String value = fileName.toString();
        return value.equalsIgnoreCase("npx") || value.equalsIgnoreCase("npx.cmd");
    }

    /**
     * 在使用 npx shim 引用的 Node/npm 文件前拒绝链接别名与非普通文件，避免 shim 映射绕过路径边界。
     */
    private static Path checkedRegularFile(Path path, String errorCode) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        rejectLinkOrReparse(absolute, errorCode);
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(errorCode);
        }
        return absolute.toRealPath();
    }

    /**
     * 读取可执行名称的最后扩展名；点号开头的命令名仍视为无扩展名。
     */
    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
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
     * 构造本地环境块前按 Windows 大小写不敏感语义校验环境变量；不增加条目、值或总块的
     * 人工配额，只保留 CreateProcess 无法表示的 NUL、等号和整数溢出检查。
     */
    private static Map<String, String> validatedEnvironment(Map<String, String> environment)
            throws IOException {
        Map<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (!isEnvironmentName(name) || value == null || value.indexOf('\0') >= 0) {
                throw new IOException("windows_process_environment_invalid");
            }
            if (copy.containsKey(name)) {
                throw new IOException("windows_process_environment_invalid");
            }
            copy.put(name, value);
        }
        int blockCharacters = 1;
        for (Map.Entry<String, String> entry : copy.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            try {
                blockCharacters = Math.addExact(blockCharacters,
                        Math.addExact(name.length(), Math.addExact(value.length(), 2)));
            } catch (ArithmeticException overflow) {
                throw new IOException("windows_process_environment_invalid");
            }
        }
        return Map.copyOf(copy);
    }

    /**
     * 接受普通 Windows 环境名和 Windows 的 `=EXITCODE`/按盘符变量，拒绝 NUL 及额外等号。
     */
    private static boolean isEnvironmentName(String name) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0) {
            return false;
        }
        int equals = name.indexOf('=');
        return equals < 0 || (equals == 0 && name.length() > 1 && name.indexOf('=', 1) < 0);
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
     * 构造按 Windows 键语义排序且以单 NUL 结尾的 Unicode 环境块；UTF-16 编码器再追加最终 NUL。
     */
    static String environmentBlock(Map<String, String> values) {
        List<Map.Entry<String, String>> entries = values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder())))
                .toList();
        StringBuilder block = new StringBuilder();
        for (Map.Entry<String, String> entry : entries) {
            block.append(entry.getKey()).append('=').append(entry.getValue()).append('\0');
        }
        if (block.isEmpty()) {
            block.append('\0');
        }
        return block.toString();
    }

    /**
     * 只解析显式 `.exe` 或调用方环境中的 PATH/PATHEXT `.exe`，不回退系统搜索。
     */
    static String resolveExecutable(String executable, Map<String, String> environment)
            throws WindowsProcessNativeApi.WindowsFailure {
        Path candidate;
        try {
            candidate = findExecutableCandidate(executable, environment);
        } catch (IOException invalid) {
            throw new WindowsProcessNativeApi.WindowsFailure("executable_not_found", 2);
        }
        if (candidate == null) {
            throw new WindowsProcessNativeApi.WindowsFailure("executable_not_found", 2);
        }
        Path candidateName = candidate.getFileName();
        if (candidateName == null || !candidateName.toString().toLowerCase(Locale.ROOT).endsWith(".exe")) {
            throw new WindowsProcessNativeApi.WindowsFailure("executable_not_found", 2);
        }
        try {
            rejectLinkOrReparse(candidate, "windows_process_executable_invalid");
            if (isWindowsExecutable(candidate)) return candidate.toRealPath().toString();
        } catch (IOException ignored) {
            // 仅在显式 PATH/PATHEXT 中继续查找，不启用任何备用解析路径。
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

        /** 诊断不暴露 argv、cwd 或环境，避免宿主路径与 MCP 凭据进入普通日志。 */
        @Override
        public String toString() {
            return "LaunchSpec[argumentCount=" + command.size()
                    + ", environmentVariableCount=" + environment.size() + "]";
        }
    }
}
