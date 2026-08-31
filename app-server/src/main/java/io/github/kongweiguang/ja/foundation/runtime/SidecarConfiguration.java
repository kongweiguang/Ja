// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.runtime;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 经过严格校验的 sidecar 进程启动参数，不携带任何业务状态或配置快照。
 */
public record SidecarConfiguration(Path homeDirectory,
                                   Path dataDirectory,
                                   Path runDirectory,
                                   Path logDirectory) {
    /**
     * 固定全部绝对目录，禁止 worker 根据 cwd、环境变量或测试模式重新解释启动边界。
     */
    public SidecarConfiguration {
        homeDirectory = normalizeDirectory(homeDirectory, "home directory");
        dataDirectory = normalizeDirectory(dataDirectory, "data directory");
        runDirectory = normalizeDirectory(runDirectory, "run directory");
        logDirectory = normalizeDirectory(logDirectory, "log directory");
        if (homeDirectory == null || dataDirectory == null || runDirectory == null || logDirectory == null) {
            throw new IllegalArgumentException("sidecar directories are required");
        }
    }

    /**
     * 只接受 Host 明确提供的绝对目录，避免相对路径绑定到偶然的进程 cwd。
     */
    private static Path normalizeDirectory(Path directory, String label) {
        if (directory == null) return null;
        if (!directory.isAbsolute()) throw new IllegalArgumentException(label + " must be absolute");
        return directory.toAbsolutePath().normalize();
    }

    /**
     * 解析封闭的首次发布参数面，不接受别名、旧路径参数或配置快照。
     */
    public static SidecarConfiguration fromArgs(String[] args) {
        if (args == null) throw new IllegalArgumentException("sidecar arguments are required");
        Path homeDirectory = null;
        Path dataDirectory = null;
        Path runDirectory = null;
        Path logDirectory = null;
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < args.length; index++) {
            String arg = Objects.requireNonNull(args[index], "args[" + index + "]");
            if (arg.startsWith("--home-dir-base64=")) {
                rejectDuplicate(seen, "home");
                homeDirectory = parseBase64Directory(arg.substring("--home-dir-base64=".length()), "home");
            } else if (arg.startsWith("--data-dir-base64=")) {
                rejectDuplicate(seen, "data");
                dataDirectory = parseBase64Directory(arg.substring("--data-dir-base64=".length()), "data");
            } else if (arg.startsWith("--run-dir-base64=")) {
                rejectDuplicate(seen, "run");
                runDirectory = parseBase64Directory(arg.substring("--run-dir-base64=".length()), "run");
            } else if (arg.startsWith("--log-dir-base64=")) {
                rejectDuplicate(seen, "log");
                logDirectory = parseBase64Directory(arg.substring("--log-dir-base64=".length()), "log");
            } else {
                throw new IllegalArgumentException("unsupported sidecar argument");
            }
        }
        return new SidecarConfiguration(homeDirectory, dataDirectory, runDirectory, logDirectory);
    }

    /**
     * 拒绝重复参数，防止后出现的值静默改写已校验 owner。
     */
    private static void rejectDuplicate(Set<String> seen, String name) {
        if (!seen.add(name)) throw new IllegalArgumentException("duplicate sidecar argument");
    }

    /**
     * 将严格解码后的文本转换为绝对路径，不允许空值回退到仓库或临时目录。
     */
    private static Path parseDataDirectory(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("data directory is required");
        }
        final Path path;
        try {
            path = Path.of(value);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("directory path is invalid");
        }
        if (!path.isAbsolute()) throw new IllegalArgumentException("data directory must be absolute");
        return path;
    }

    /**
     * 通过 Base64URL 和严格 UTF-8 解码 Host 路径，禁止替换字符把启动目录静默改向。
     */
    private static Path parseBase64Directory(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("encoded " + label + " directory is required");
        }
        byte[] pathBytes;
        try {
            pathBytes = Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid encoded " + label + " directory", exception);
        }
        if (pathBytes.length == 0) {
            throw new IllegalArgumentException("encoded " + label + " directory is required");
        }
        String path;
        try {
            path = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(pathBytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("encoded " + label + " directory must be valid UTF-8", exception);
        }
        if (path.isBlank()) throw new IllegalArgumentException(label + " directory is required");
        return parseDataDirectory(path);
    }
}
