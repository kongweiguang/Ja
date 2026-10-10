// @author kongweiguang
package io.github.kongweiguang.ja.agent.example.springboot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.example.springboot.bootstrap.AgentProperties;
import io.github.kongweiguang.ja.agent.example.springboot.experience.adapter.out.mcp.McpService;
import io.github.kongweiguang.ja.agent.example.springboot.experience.application.gateway.McpDirectory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/** Java SDK 与 uv 管理的 FastMCP 实际通过 HTTP 通信，测试结束释放 Python 进程。 */
class FastMcpHttpTest {
    private static final String READY_PREFIX = "JA_DEMO_MCP_READY=";

    /** 服务绑定端口后通过握手回报地址，同时验证真实 MCP 调用与父子进程清理。 */
    @Test
    void discoversAndCallsFastMcpOverHttp() throws Exception {
        Path directory = Path.of(System.getProperty("user.dir"), "mcp");
        ProcessBuilder processBuilder = new ProcessBuilder("uv", "run", "--project", directory.toString(), directory.resolve("server.py").toString());
        processBuilder.environment().put("JA_DEMO_MCP_PORT", "0");
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        CompletableFuture<Integer> readyPort = new CompletableFuture<>();
        Thread outputReader = Thread.ofVirtual().start(() -> readReadyPort(process, readyPort));
        try {
            int port = awaitReadyPort(process, readyPort);
            try (McpService mcp = new McpService(List.of(new AgentProperties.McpServer(
                    "demo", "http://127.0.0.1:" + port + "/mcp", Map.of())))) {
            List<McpDirectory.ServerView> catalog = mcp.catalog();
                assertEquals("AVAILABLE", catalog.getFirst().status());
                ObjectMapper json = new ObjectMapper();
                JsonMapper httpJson = tools.jackson.databind.json.JsonMapper.builder().build();
                assertEquals("AVAILABLE", json.readTree(httpJson.writeValueAsString(catalog)).get(0).path("status").asText());
                McpService.ToolView tool = catalog.getFirst().tools().stream()
                        .filter(value -> value.name().equals("add")).findFirst().orElseThrow();
                JsonNode schema = json.readTree(httpJson.writeValueAsString(tool)).path("inputSchema");
                assertEquals("object", schema.path("type").asText());
                assertEquals("number", schema.path("properties").path("a").path("type").asText());
                assertEquals(List.of("a", "b"), json.readerForListOf(String.class).readValue(schema.path("required")));
                assertEquals("19.75", mcp.call("demo", "add", JsonObjects.builder()
                        .putNumber("a", new java.math.BigDecimal("12.5"))
                        .putNumber("b", new java.math.BigDecimal("7.25")).build(), CancellationToken.none()));
                assertNotNull(java.time.OffsetDateTime.parse(
                        mcp.call("demo", "now", JsonObjects.builder().build(), CancellationToken.none())));
            }
        } finally {
            stopProcessTree(process);
            outputReader.join(TimeUnit.SECONDS.toMillis(5));
            if (outputReader.isAlive()) {
                throw new IllegalStateException("FastMCP process output reader did not exit");
            }
        }
    }

    /** 持续排空子进程输出，避免启动日志填满管道并阻塞 Python 服务。 */
    private static void readReadyPort(Process process, CompletableFuture<Integer> readyPort) {
        try (BufferedReader reader = process.inputReader()) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.startsWith(READY_PREFIX)) {
                    readyPort.complete(Integer.parseInt(line.substring(READY_PREFIX.length())));
                }
            }
            readyPort.completeExceptionally(new IllegalStateException("FastMCP exited before reporting its port"));
        } catch (IOException | NumberFormatException failure) {
            readyPort.completeExceptionally(failure);
        }
    }

    /** 只在拿到服务端绑定后的端口时继续，启动超时和进程提前退出均给出确定失败。 */
    private static int awaitReadyPort(Process process, CompletableFuture<Integer> readyPort) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        while (true) {
            if (!process.isAlive()) {
                fail("FastMCP exited with " + process.exitValue());
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("FastMCP did not report a ready port within 30 seconds");
            }
            try {
                int port = readyPort.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)),
                        TimeUnit.NANOSECONDS);
                if (port < 1 || port > 65_535) {
                    throw new IllegalStateException("FastMCP reported an invalid port");
                }
                return port;
            } catch (TimeoutException waiting) {
                // 再次检查进程是否退出以及启动期限是否已到。
            }
        }
    }

    /** 先请求正常退出，再强制终止并等待父进程与遗留子进程归零。 */
    private static void stopProcessTree(Process process) throws Exception {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("FastMCP process did not exit after forced termination");
            }
        }
        for (ProcessHandle descendant : descendants) {
            if (!descendant.isAlive()) {
                continue;
            }
            descendant.destroyForcibly();
            try {
                descendant.onExit().get(10, TimeUnit.SECONDS);
            } catch (TimeoutException failure) {
                throw new IllegalStateException("FastMCP child process did not exit after forced termination", failure);
            }
        }
    }
}
