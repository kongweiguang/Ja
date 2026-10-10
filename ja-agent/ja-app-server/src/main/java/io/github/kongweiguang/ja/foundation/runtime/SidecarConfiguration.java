// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.runtime;

import java.net.Inet4Address;
import java.net.InetAddress;
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
 *
 * @param homeDirectory 用户主目录
 * @param dataDirectory 数据目录
 * @param runDirectory 运行目录
 * @param logDirectory 日志目录
 * @param runtimeGeneration 运行时代际标识
 * @param listenHost FeatHTTP WebSocket 监听的 IPv4 地址
 * @param listenPort FeatHTTP WebSocket 监听端口，0 表示自动选取
 */
public record SidecarConfiguration(Path homeDirectory,
                                   Path dataDirectory,
                                   Path runDirectory,
                                   Path logDirectory,
                                   long runtimeGeneration,
                                   String listenHost,
                                   int listenPort) {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final String DEFAULT_LISTEN_HOST = "127.0.0.1";

    /** 保留已有 Java 装配入口的本机默认值，只有显式启动参数改变监听地址。 */
    public SidecarConfiguration(Path homeDirectory, Path dataDirectory, Path runDirectory,
                                Path logDirectory, long runtimeGeneration) {
        this(homeDirectory, dataDirectory, runDirectory, logDirectory, runtimeGeneration,
                DEFAULT_LISTEN_HOST, 0);
    }

    /**
     * 为未接入 host generation 的纯 Java 测试适配器提供明确的第一代默认值；生产 sidecar
     * 必须通过 {@link #fromArgs(String[])} 接收 Rust Host 注入的真实代际。
     */
    public SidecarConfiguration(Path homeDirectory, Path dataDirectory, Path runDirectory,
                                Path logDirectory) {
        this(homeDirectory, dataDirectory, runDirectory, logDirectory, 1L);
    }

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
        if (runtimeGeneration < 1 || runtimeGeneration > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("runtime generation is invalid");
        }
        listenHost = parseListenHost(listenHost);
        if (listenPort < 0 || listenPort > 65_535) {
            throw new IllegalArgumentException("server port is invalid");
        }
    }

    /**
     * 只接受 Host 明确提供的绝对目录，避免相对路径绑定到偶然的进程 cwd。
     */
    private static Path normalizeDirectory(Path directory, String label) {
        if (directory == null) {
            return null;
        }
        if (!directory.isAbsolute()) {
            throw new IllegalArgumentException(label + " must be absolute");
        }
        return directory.toAbsolutePath().normalize();
    }

    /**
     * 解析封闭的启动参数面；FeatHTTP 原生 host/port 参数仅在 WebSocket 模式有意义。
     */
    public static SidecarConfiguration fromArgs(String[] args) {
        if (args == null) {
            throw new IllegalArgumentException("sidecar arguments are required");
        }
        Path homeDirectory = null;
        Path dataDirectory = null;
        Path runDirectory = null;
        Path logDirectory = null;
        long runtimeGeneration = 0L;
        String listenHost = DEFAULT_LISTEN_HOST;
        int listenPort = 0;
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
            } else if (arg.startsWith("--ja-runtime-generation=")) {
                rejectDuplicate(seen, "runtime-generation");
                runtimeGeneration = parseRuntimeGeneration(
                        arg.substring("--ja-runtime-generation=".length()));
            } else if ("--ja-transport=websocket".equals(arg)) {
                // Host 与 stdio 入口共同负责传输生命周期；Java 参数只校验当前唯一生产模式。
                rejectDuplicate(seen, "transport");
            } else if (arg.startsWith("--server.host=")) {
                rejectDuplicate(seen, "server.host");
                listenHost = parseListenHost(arg.substring("--server.host=".length()));
            } else if (arg.startsWith("--server.port=")) {
                rejectDuplicate(seen, "server.port");
                listenPort = parseListenPort(arg.substring("--server.port=".length()));
            } else {
                throw new IllegalArgumentException("unsupported sidecar argument");
            }
        }
        if ((seen.contains("server.host") || seen.contains("server.port"))
            && !seen.contains("transport")) {
            throw new IllegalArgumentException("server host and port require websocket transport");
        }
        if (runtimeGeneration == 0L) {
            throw new IllegalArgumentException("runtime generation is required");
        }
        return new SidecarConfiguration(homeDirectory, dataDirectory, runDirectory, logDirectory,
                runtimeGeneration, listenHost, listenPort);
    }

    /** 只接受不触发 DNS 的 IPv4 字面量；localhost 是本机入口的唯一名称别名。 */
    private static String parseListenHost(String value) {
        if ("localhost".equalsIgnoreCase(value)) {
            return DEFAULT_LISTEN_HOST;
        }
        try {
            InetAddress address = InetAddress.ofLiteral(Objects.requireNonNull(value, "listenHost"));
            if (address instanceof Inet4Address && !address.isMulticastAddress()) {
                return address.getHostAddress();
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("server host must be an IPv4 address", exception);
        }
        throw new IllegalArgumentException("server host must be an IPv4 address");
    }

    /** 0 保留现有自动端口策略；其余值必须是明确的 TCP 端口，不能由 Solon 静默改写。 */
    private static int parseListenPort(String value) {
        if (value == null || value.isEmpty()
            || !value.chars().allMatch(character -> character >= '0' && character <= '9')) {
            throw new IllegalArgumentException("server port is invalid");
        }
        try {
            int port = Integer.parseInt(value);
            if (port >= 0 && port <= 65_535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
            // 超出 int 范围与超出 TCP 端口范围同属无效启动参数。
        }
        throw new IllegalArgumentException("server port is invalid");
    }

    /**
     * 严格解析 Rust Host 注入的安全整数，拒绝空值、符号变体和超过 JavaScript 精度边界的代际。
     */
    private static long parseRuntimeGeneration(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("runtime generation is required");
        }
        if (value.chars().anyMatch(character -> character < '0' || character > '9')) {
            throw new IllegalArgumentException("runtime generation is invalid");
        }
        final long generation;
        try {
            generation = Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("runtime generation is invalid", exception);
        }
        if (generation < 1 || generation > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("runtime generation is invalid");
        }
        return generation;
    }

    /**
     * 拒绝重复参数，防止后出现的值静默改写已校验 owner。
     */
    private static void rejectDuplicate(Set<String> seen, String name) {
        if (!seen.add(name)) {
            throw new IllegalArgumentException("duplicate sidecar argument");
        }
    }

    /**
     * 将严格解码后的文本转换为绝对路径；解析异常含宿主路径，拒绝时不把其正文带入诊断链。
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
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
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("data directory must be absolute");
        }
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
        if (path.isBlank()) {
            throw new IllegalArgumentException(label + " directory is required");
        }
        return parseDataDirectory(path);
    }
}
