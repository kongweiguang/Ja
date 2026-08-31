// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.testsupport;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 供 generation 与 transport 测试共享的最小 stdio MCP 子进程夹具。
 */
public final class McpStdioFixture {
    /**
     * 测试夹具没有可变实例状态，禁止构造以免误用为进程内服务。
     */
    private McpStdioFixture() {
    }

    /**
     * 只发布非 Secret 观测值并处理固定 MCP 方法，使跨包测试共享同一协议基线。
     */
    public static void main(String[] args) throws Exception {
        Path report = Path.of(args[0]);
        Files.write(
                report,
                List.of(
                        Long.toString(ProcessHandle.current().pid()),
                        "cwd=" + Path.of("").toAbsolutePath().normalize(),
                        "parent=" + (System.getenv("USERPROFILE") != null),
                        "allowed=" + "yes".equals(System.getenv("JA_ALLOWED")),
                        "secret=" + (System.getenv("JA_SECRET") != null)),
                StandardCharsets.UTF_8);
        if (args.length > 1 && "stderr".equals(args[1])) {
            System.err.print("x".repeat(4096));
            System.err.flush();
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.contains("\"id\"")) {
                    continue;
                }
                String id = line.replaceFirst("(?s).*\"id\"\\s*:\\s*(\"[^\"]+\"|[0-9]+).*", "$1");
                String method = line.replaceFirst("(?s).*\"method\"\\s*:\\s*\"([^\"]+)\".*", "$1");
                Files.writeString(report, "method=" + method + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.APPEND);
                String result = fixtureResultFor(method);
                System.out.println("{\"jsonrpc\":\"2.0\",\"id\":"
                        + id + ",\"result\":" + result + "}");
                System.out.flush();
            }
        }
    }

    /**
     * 保持子 JVM 独立于外层测试依赖及类初始化，只返回固定协议结果。
     */
    private static String fixtureResultFor(String method) {
        return switch (method) {
            case "initialize" -> "{\"protocolVersion\":\"2025-06-18\","
                    + "\"capabilities\":{\"tools\":{}},"
                    + "\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}";
            case "tools/list" -> "{\"tools\":[{\"name\":\"echo\",\"description\":\"echo\","
                    + "\"inputSchema\":{\"type\":\"object\"}}]}";
            case "tools/call" -> "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                    + "\"isError\":false}";
            default -> "{}";
        };
    }
}
