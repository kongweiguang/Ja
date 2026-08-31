// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 为 Shell 子进程冻结最小非敏感环境；配置、凭据和任意宿主变量不能穿过该白名单。
 */
final class ShellProcessEnvironment {
    private static final int MAX_PATH_ENTRIES = 128;
    private static final int MAX_VALUE_CHARACTERS = 32_767;
    private static final Pattern WINDOWS_EXTENSION = Pattern.compile("\\.[A-Za-z0-9]{1,12}");
    private static final List<String> REQUIRED_WINDOWS_EXTENSIONS = List.of(".COM", ".EXE", ".BAT", ".CMD");

    /** 纯策略类型不持有环境快照，实际冻结值归 ShellProfile。 */
    private ShellProcessEnvironment() {
    }

    /**
     * 仅从显式白名单构造平台环境；Windows 缺少 PATHEXT 时补标准可执行扩展，修复
     * `env_clear` sidecar 中 PowerShell 无法解析 extensionless `.exe/.cmd` 的问题。
     */
    static Map<String, String> capture(ShellProfile.OperatingSystem os,
                                       Function<String, String> lookup) {
        Objects.requireNonNull(os, "os");
        Objects.requireNonNull(lookup, "lookup");
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (os == ShellProfile.OperatingSystem.WINDOWS) {
            String systemRoot = safePathValue(lookup.apply("SystemRoot"));
            put(result, "SystemRoot", systemRoot);
            put(result, "ComSpec", commandProcessor(lookup.apply("ComSpec"), systemRoot));
            put(result, "TEMP", safePathValue(lookup.apply("TEMP")));
            put(result, "TMP", safePathValue(lookup.apply("TMP")));
            put(result, "PATH", windowsPath(lookup.apply("PATH"), systemRoot));
            result.put("PATHEXT", windowsPathExtensions(lookup.apply("PATHEXT")));
        } else {
            put(result, "HOME", safePathValue(lookup.apply("HOME")));
            put(result, "TMPDIR", safePathValue(lookup.apply("TMPDIR")));
            put(result, "PATH", posixPath(lookup.apply("PATH")));
            put(result, "LANG", safeScalar(lookup.apply("LANG")));
            put(result, "LC_ALL", safeScalar(lookup.apply("LC_ALL")));
        }
        return Map.copyOf(result);
    }

    /**
     * Profile 构造时再次验证调用方注入，避免测试 seam 或未来 composition 绕过白名单与大小上限。
     */
    static Map<String, String> validate(ShellProfile.OperatingSystem os, Map<String, String> environment) {
        Objects.requireNonNull(os, "os");
        environment = Objects.requireNonNull(environment, "environment");
        Set<String> allowed = os == ShellProfile.OperatingSystem.WINDOWS
                ? Set.of("SystemRoot", "ComSpec", "TEMP", "TMP", "PATH", "PATHEXT")
                : Set.of("HOME", "TMPDIR", "PATH", "LANG", "LC_ALL");
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String canonical = allowed.stream().filter(key -> key.equalsIgnoreCase(entry.getKey()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("shell environment key is not allowed"));
            String value = entry.getValue();
            if (value == null || value.length() > MAX_VALUE_CHARACTERS || value.indexOf('\0') >= 0
                || value.chars().anyMatch(character -> Character.isISOControl(character))) {
                throw new IllegalArgumentException("shell environment value is invalid");
            }
            if (copy.putIfAbsent(canonical, value) != null) {
                throw new IllegalArgumentException("shell environment key is duplicated");
            }
        }
        if (os == ShellProfile.OperatingSystem.WINDOWS && !copy.containsKey("PATHEXT")) {
            throw new IllegalArgumentException("Windows shell environment requires PATHEXT");
        }
        return Map.copyOf(copy);
    }

    /** PATH 只保留绝对目录、去重并追加 Windows 核心目录，避免 cwd 相对项劫持命令解析。 */
    private static String windowsPath(String raw, String systemRoot) {
        List<String> entries = absolutePathEntries(raw, File.pathSeparator);
        if (systemRoot != null) {
            addAbsolute(entries, Path.of(systemRoot, "System32").toString());
            addAbsolute(entries, systemRoot);
            addAbsolute(entries, Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0").toString());
        }
        return boundedJoin(entries, File.pathSeparator);
    }

    /** POSIX 同样排除相对与空 PATH 项，防止工作区文件获得隐式 executable 优先级。 */
    private static String posixPath(String raw) {
        return boundedJoin(absolutePathEntries(raw, File.pathSeparator), File.pathSeparator);
    }

    /** PATHEXT 使用闭集语法并始终包含 Windows 原生与脚本启动所需的四个标准扩展。 */
    private static String windowsPathExtensions(String raw) {
        LinkedHashSet<String> extensions = new LinkedHashSet<>();
        if (raw != null && raw.length() <= MAX_VALUE_CHARACTERS) {
            for (String value : raw.split(";", -1)) {
                String normalized = value.strip().toUpperCase(Locale.ROOT);
                if (WINDOWS_EXTENSION.matcher(normalized).matches()) extensions.add(normalized);
                if (extensions.size() >= 16) break;
            }
        }
        REQUIRED_WINDOWS_EXTENSIONS.forEach(extensions::add);
        return String.join(";", extensions);
    }

    /** 只解析有限数量的绝对路径，畸形或超长项被丢弃而不扩大整个服务启动失败。 */
    private static List<String> absolutePathEntries(String raw, String separator) {
        List<String> entries = new ArrayList<>();
        if (raw == null || raw.length() > MAX_VALUE_CHARACTERS) return entries;
        for (String value : raw.split(Pattern.quote(separator), -1)) {
            if (entries.size() >= MAX_PATH_ENTRIES) break;
            addAbsolute(entries, stripOptionalQuotes(value.strip()));
        }
        return entries;
    }

    /** 路径按平台语义规范化并大小写不敏感去重 Windows 输入。 */
    private static void addAbsolute(List<String> entries, String value) {
        if (value == null || value.isBlank() || value.length() > 4_096 || value.indexOf('\0') >= 0) return;
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute()) return;
            String normalized = path.normalize().toString();
            if (entries.stream().noneMatch(existing -> existing.equalsIgnoreCase(normalized))) {
                entries.add(normalized);
            }
        } catch (InvalidPathException ignored) {
            // 单个畸形 PATH 项不应让 Shell 能力整体消失。
        }
    }

    /** 保证合并后的 PATH 不超过 Windows 环境块单值上限。 */
    private static String boundedJoin(List<String> entries, String separator) {
        StringBuilder result = new StringBuilder();
        for (String entry : entries) {
            int required = entry.length() + (result.isEmpty() ? 0 : separator.length());
            if (result.length() + required > MAX_VALUE_CHARACTERS) break;
            if (!result.isEmpty()) result.append(separator);
            result.append(entry);
        }
        return result.toString();
    }

    /** ComSpec 缺失时只从已验证 SystemRoot 构造标准路径，不从 PATH 搜索脚本别名。 */
    private static String commandProcessor(String raw, String systemRoot) {
        String explicit = safePathValue(raw);
        if (explicit != null) return explicit;
        return systemRoot == null ? null : Path.of(systemRoot, "System32", "cmd.exe").toString();
    }

    /** 环境路径值必须是绝对路径，防止 cwd 改变其语义。 */
    private static String safePathValue(String raw) {
        if (raw == null || raw.length() > MAX_VALUE_CHARACTERS) return null;
        String stripped = stripOptionalQuotes(raw.strip());
        try {
            Path value = Path.of(stripped);
            return value.isAbsolute() ? value.normalize().toString() : null;
        } catch (InvalidPathException invalid) {
            return null;
        }
    }

    /** Locale 只接受不含控制字符的短标量，不允许借普通变量携带大段配置。 */
    private static String safeScalar(String raw) {
        if (raw == null || raw.length() > 128 || raw.chars().anyMatch(Character::isISOControl)) return null;
        return raw;
    }

    /** 只剥离包围完整值的一对引号，不改变内部合法空格。 */
    private static String stripOptionalQuotes(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    /** 缺失值不进入环境块，空 PATH 则显式保留以阻止 Java 回退继承。 */
    private static void put(Map<String, String> values, String key, String value) {
        if (value != null) values.put(key, value);
    }
}
