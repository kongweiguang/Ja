// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.collaboration.application.service.NativeHostProcessFixture;
import io.github.kongweiguang.ja.transport.rpc.handler.HandshakeHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 独立进程才能发现框架提前退出；真实 JA-RPC 和 SQLite 不用工厂 Mock 替代。 */
final class RecoverableRuntimeBootstrapProcessTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String READY_TOKEN = "0123456789abcdef0123456789abcdef";
    private static final Pattern FAILURE_TYPE = Pattern.compile("\\b([A-Za-z_$][A-Za-z0-9_$]*(?:Exception|Error))\\b");

    @TempDir
    Path root;

    /** 从空临时数据目录启动真实 sidecar，验证生产 bootstrap 发布服务并由同一生命周期正常关闭。 */
    @Test
    void successfulAssemblyPublishesServicesAndStopsCleanly() throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Path data = Files.createDirectories(root.resolve("data"));
        Path run = Files.createDirectories(root.resolve("run"));
        Path logs = Files.createDirectories(root.resolve("logs"));
        try (Child child = Child.start(home, data, run, logs, root.resolve("stderr.log"))) {
            ObjectNode initialize = MAPPER.createObjectNode().put("protocolMajor", 2).put("protocolMinor", 0)
                    .put("clientVersion", "bootstrap-ready-process-test");
            initialize.set("capabilities", HandshakeHandler.capabilities(MAPPER));
            initialize.set("limits", HandshakeHandler.limits(MAPPER));
            result(child.request("c:ready_init", "runtime/initialize", initialize));
            child.notify("runtime/initialized", MAPPER.createObjectNode().put("readyToken", READY_TOKEN));

            ObjectNode health = result(child.request("c:ready_health", "runtime/health", MAPPER.createObjectNode()));
            assertEquals("ready", health.path("status").asText(), child::diagnostics);
            result(child.request("c:ready_workspace_list", "workspace/list",
                    MAPPER.createObjectNode().put("limit", 1).putNull("cursor")));
            assertTrue(Files.isRegularFile(data.resolve("ja.db")));

            ObjectNode shutdown = result(child.request("c:ready_shutdown", "runtime/shutdown",
                    MAPPER.createObjectNode().put("force", true)));
            assertTrue(shutdown.path("accepted").booleanValue());
            child.requests.close();
            assertTrue(child.process.waitFor(15, TimeUnit.SECONDS), child::diagnostics);
            assertEquals(0, child.process.exitValue(), child::diagnostics);
        }
    }

    /** 故障期间健康与传输仍可用；只有移除临时障碍并显式重试后才开放真实业务服务。 */
    @Test
    void failedAssemblyKeepsHealthRpcAliveAndExplicitRetryRestoresServices() throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Path data = Files.createDirectories(root.resolve("data"));
        Path run = Files.createDirectories(root.resolve("run"));
        Path logs = Files.createDirectories(root.resolve("logs"));
        Path obstruction = Files.createDirectory(data.resolve("ja.db"));
        try (Child child = Child.start(home, data, run, logs, root.resolve("stderr.log"))) {
            ObjectNode initialize = MAPPER.createObjectNode().put("protocolMajor", 2).put("protocolMinor", 0)
                    .put("clientVersion", "bootstrap-process-test");
            initialize.set("capabilities", HandshakeHandler.capabilities(MAPPER));
            initialize.set("limits", HandshakeHandler.limits(MAPPER));
            ObjectNode initialized = result(child.request("c:bootstrap_init", "runtime/initialize", initialize));
            String instance = initialized.path("serverInstanceId").asText();
            assertFalse(instance.isBlank());
            child.notify("runtime/initialized", MAPPER.createObjectNode().put("readyToken", READY_TOKEN));

            ObjectNode blocked = result(child.request("c:bootstrap_health", "runtime/health", MAPPER.createObjectNode()));
            assertEquals("blocked", blocked.path("status").asText());
            assertEquals("blocked", blocked.path("storageRecovery").path("phase").asText());
            String recoveryId = blocked.path("storageRecovery").path("id").asText();
            assertFalse(recoveryId.isBlank());
            ObjectNode second = result(child.request("c:bootstrap_health2", "runtime/health", MAPPER.createObjectNode()));
            assertEquals(recoveryId, second.path("storageRecovery").path("id").asText());
            assertTrue(child.process.isAlive());
            ObjectNode denied = child.request("c:bootstrap_list_denied", "workspace/list",
                    MAPPER.createObjectNode().put("limit", 1).putNull("cursor"));
            assertEquals("STORAGE_UNAVAILABLE", denied.path("error").path("data").path("errorCode").asText());

            Files.delete(obstruction);
            ObjectNode recovered = result(child.request("c:bootstrap_retry", "runtime/storage/retry", MAPPER.createObjectNode()));
            assertEquals("ready", recovered.path("status").asText());
            result(child.request("c:bootstrap_list", "workspace/list",
                    MAPPER.createObjectNode().put("limit", 1).putNull("cursor")));
            assertTrue(Files.isRegularFile(data.resolve("ja.db")));
            assertTrue(child.process.isAlive());
            ObjectNode shutdown = result(child.request("c:bootstrap_shutdown", "runtime/shutdown",
                    MAPPER.createObjectNode().put("force", true)));
            assertTrue(shutdown.path("accepted").booleanValue());
            child.requests.close();
            assertTrue(child.process.waitFor(15, TimeUnit.SECONDS), child::diagnostics);
            assertEquals(0, child.process.exitValue(), child::diagnostics);
        }
    }

    /**
     * 存储故障和显式重试不能撤销宿主连接；独立 Host PID 让 owner 注册/关闭沿真实 ProcessHandle 闭环。
     * @author kongweiguang
     */
    @Test
    void failedAssemblyKeepsWebSocketAliveAcrossExplicitRetry() throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Path data = Files.createDirectories(root.resolve("data"));
        Path run = Files.createDirectories(root.resolve("run"));
        Path logs = Files.createDirectories(root.resolve("logs"));
        Path obstruction = Files.createDirectory(data.resolve("ja.db"));
        try (NativeHostProcessFixture.Host nativeHost = NativeHostProcessFixture.start();
             Child child = Child.start(home, data, run, logs, root.resolve("stderr.log"), true)) {
            Path endpointPath = run.resolve("app-server.endpoint.json");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (Files.notExists(endpointPath) && child.process.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(Files.isRegularFile(endpointPath), child::diagnostics);
            ObjectNode endpoint = (ObjectNode) MAPPER.readTree(Files.readString(endpointPath));
            try (SocketClient client = new SocketClient(endpoint)) {
                ObjectNode initialize = MAPPER.createObjectNode().put("protocolMajor", 2).put("protocolMinor", 0)
                        .put("clientVersion", "bootstrap-websocket-test");
                initialize.set("capabilities", HandshakeHandler.capabilities(MAPPER));
                initialize.set("limits", HandshakeHandler.limits(MAPPER));
                String instance = result(client.request("c:socket_init", "runtime/initialize", initialize))
                        .path("serverInstanceId").asText();
                assertFalse(instance.isBlank());
                client.notify("runtime/initialized", MAPPER.createObjectNode().put("readyToken", READY_TOKEN));
                ObjectNode nativeContext = MAPPER.createObjectNode();
                ObjectNode environment = nativeContext.putObject("environment");
                System.getenv().forEach(environment::put);
                // 保留完整宿主快照，仅追加确定性测试数据以触发 JDK 16 KiB 分片。
                String paddingKey = "JA_TEST_WEBSOCKET_FRAGMENTATION_PADDING";
                while (environment.has(paddingKey)) {
                    paddingKey += "_";
                }
                environment.put(paddingKey, "x".repeat(16 * 1024));
                nativeContext.putNull("shell");
                nativeContext.put("clientProcessId", nativeHost.processId());
                nativeContext.put("clientLifetimeId", nativeHost.lifetimeId());
                ObjectNode registered;
                try {
                    registered = client.request("c:socket_context", "runtime/context/register", nativeContext);
                } catch (AssertionError failure) {
                    throw new AssertionError("isolated WebSocket child: " + child.diagnostics(), failure);
                }
                assertTrue(result(registered).path("contextId").asText().startsWith("ctx_"));
                assertTrue(client.lastRequestBytes > 16 * 1024,
                        "the complete host snapshot plus test padding must exercise JDK WebSocket fragmentation");
                assertTrue(client.environmentEntries > 0,
                        "the request must retain the complete host environment");
                ObjectNode blocked = result(client.request("c:socket_health", "runtime/health", MAPPER.createObjectNode()));
                assertEquals("blocked", blocked.path("status").asText());
                String recoveryId = blocked.path("storageRecovery").path("id").asText();
                assertFalse(recoveryId.isBlank());
                ObjectNode second = result(client.request("c:socket_health2", "runtime/health", MAPPER.createObjectNode()));
                assertEquals(recoveryId, second.path("storageRecovery").path("id").asText());

                Files.delete(obstruction);
                ObjectNode recovered = result(client.request("c:socket_retry", "runtime/storage/retry", MAPPER.createObjectNode()));
                assertEquals("ready", recovered.path("status").asText(), child::diagnostics);
                assertEquals(endpoint, MAPPER.readTree(Files.readString(endpointPath)),
                        "storage retry must not replace the transport endpoint");
                ObjectNode health = result(client.request("c:socket_recovered_health", "runtime/health",
                        MAPPER.createObjectNode()));
                assertEquals("ready", health.path("status").asText(),
                        "the original WebSocket must remain attached to the recovered runtime");
                result(client.request("c:socket_list", "workspace/list",
                        MAPPER.createObjectNode().put("limit", 1).putNull("cursor")));
                assertTrue(Files.isRegularFile(data.resolve("ja.db")));
                ObjectNode shutdown = result(client.request("c:socket_shutdown", "runtime/shutdown",
                        MAPPER.createObjectNode().put("force", true)));
                assertTrue(shutdown.path("accepted").booleanValue());
            } catch (Exception failure) {
                throw new IllegalStateException("isolated WebSocket request failed: " + child.diagnostics(), failure);
            }
            assertTrue(child.process.waitFor(15, TimeUnit.SECONDS), child::diagnostics);
            assertEquals(0, child.process.exitValue(), child::diagnostics);
        }
    }

    /** JDK 原生客户端负责 WebSocket 协议；夹具只交接有界完整 JSON 帧，不模拟服务端恢复。 */
    private static final class SocketClient implements AutoCloseable {
        private final HttpClient http = HttpClient.newHttpClient();
        private final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>(64);
        private final WebSocket socket;
        private volatile int receivedFrames;
        private volatile int closeCode = -1;
        private volatile int closeReasonCharacters;
        private volatile String listenerFailureType = "none";
        private volatile String lastMethod = "none";
        private volatile int lastRequestBytes = -1;
        private volatile int environmentEntries = -1;
        private volatile long environmentCharacters = -1;

        /** 只使用本次子进程签发的本地端点与 token，不接入用户后台或记录凭据。 */
        private SocketClient(ObjectNode endpoint) throws Exception {
            WebSocket openedSocket = null;
            try {
                openedSocket = http.newWebSocketBuilder().buildAsync(
                        URI.create("ws://127.0.0.1:" + endpoint.path("port").intValue() + "/ja-rpc"),
                        new WebSocket.Listener() {
                            private final StringBuilder partial = new StringBuilder();

                            /** 帧片段按顺序组装；只记帧数，避免诊断保存可能含敏感数据的响应正文。 */
                            @Override
                            public CompletionStage<?> onText(WebSocket opened, CharSequence data, boolean last) {
                                partial.append(data);
                                if (last) {
                                    receivedFrames++;
                                    if (!frames.offer(partial.toString())) {
                                        opened.abort();
                                    }
                                    partial.setLength(0);
                                }
                                opened.request(1);
                                return null;
                            }

                            /** Close reason 由对端控制，记录长度和状态码即可诊断关闭边界。 */
                            @Override
                            public CompletionStage<?> onClose(WebSocket opened, int statusCode, String reason) {
                                closeCode = statusCode;
                                closeReasonCharacters = reason == null ? 0 : reason.length();
                                return null;
                            }

                            /** 异常消息可能带路径或请求内容，只保留稳定异常类型。 */
                            @Override
                            public void onError(WebSocket opened, Throwable failure) {
                                listenerFailureType = failure.getClass().getSimpleName();
                            }
                        }).get(5, TimeUnit.SECONDS);
                socket = openedSocket;
                socket.sendText(MAPPER.writeValueAsString(MAPPER.createObjectNode()
                        .put("token", endpoint.path("token").asText())), true).get(5, TimeUnit.SECONDS);
            } catch (Exception failure) {
                if (openedSocket != null) {
                    openedSocket.abort();
                }
                http.close();
                throw failure;
            }
        }

        /** 只等待当前请求身份；失败报告帧尺寸和连接状态，不复制环境正文或异常消息。 */
        private ObjectNode request(String id, String method, ObjectNode params) throws Exception {
            ObjectNode request = MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", id)
                    .put("method", method).set("params", params);
            lastMethod = method;
            lastRequestBytes = MAPPER.writeValueAsBytes(request).length;
            if ("runtime/context/register".equals(method) && params.path("environment").isObject()) {
                JsonNode environment = params.path("environment");
                environmentEntries = environment.size();
                long characters = 0;
                for (Iterator<java.util.Map.Entry<String, JsonNode>> entries = environment.fields(); entries.hasNext();) {
                    java.util.Map.Entry<String, JsonNode> entry = entries.next();
                    characters += entry.getKey().length();
                    if (entry.getValue().isTextual()) {
                        characters += entry.getValue().textValue().length();
                    }
                }
                environmentCharacters = characters;
            }
            send(request);
            for (int index = 0; index < 64; index++) {
                String frame = frames.poll(10, TimeUnit.SECONDS);
                assertNotNull(frame, () -> "WebSocket request did not receive a response; " + transportDiagnostics());
                ObjectNode value = (ObjectNode) MAPPER.readTree(frame);
                if (id.equals(value.path("id").asText())) {
                    return value;
                }
            }
            throw new AssertionError("WebSocket response identity was not observed");
        }

        /** 只公开无正文的尺寸与事件元数据，避免断线诊断泄露宿主环境或 close reason。 */
        private String transportDiagnostics() {
            return "method=" + lastMethod + ", requestBytes=" + lastRequestBytes
                    + ", environmentEntries=" + environmentEntries
                    + ", environmentCharacters=" + environmentCharacters
                    + ", receivedFrames=" + receivedFrames + ", closeCode=" + closeCode
                    + ", closeReasonCharacters=" + closeReasonCharacters
                    + ", listenerFailureType=" + listenerFailureType;
        }

        /** ready 挑战无请求身份；后续健康调用验证服务端已经处理该通知。 */
        private void notify(String method, ObjectNode params) throws Exception {
            send(MAPPER.createObjectNode().put("jsonrpc", "2.0").put("method", method).set("params", params));
        }

        /** JDK 负责完整 WebSocket 文本写入，等待写入失败而不是继续发送下一条命令。 */
        private void send(ObjectNode frame) throws Exception {
            socket.sendText(MAPPER.writeValueAsString(frame), true).get(5, TimeUnit.SECONDS);
        }

        /** 测试只关闭自己的网络连接；业务进程仍须用 runtime/shutdown 独立退出。 */
        @Override
        public void close() {
            socket.abort();
            http.close();
        }
    }

    /** 失败回执必须保留在断言中，不能把空结果或 HTTP/RPC 受理误判为成功。 */
    private static ObjectNode result(ObjectNode frame) {
        assertFalse(frame.has("error"), frame::toString);
        assertTrue(frame.path("result").isObject(), frame::toString);
        return (ObjectNode) frame.path("result");
    }

    /** 测试唯一持有子进程和管道，超时先结束进程再关闭读取线程，避免验收自己留下阻塞资源。 */
    private static final class Child implements AutoCloseable {
        private final Process process;
        private final Path errorOutput;
        private final BufferedWriter requests;
        private final BufferedReader frames;
        private final ExecutorService reads = Executors.newVirtualThreadPerTaskExecutor();

        /** 不投影或裁剪宿主环境；只有此子进程的临时日志位置与 JVM classpath 被明确指定。 */
        private Child(Process process, Path errorOutput) {
            this.process = process;
            this.errorOutput = errorOutput;
            requests = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            frames = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        }

        /** 使用正在执行 Maven 的 JDK 25 和真实应用入口，不借用安装产物或同 JVM 的框架状态。 */
        private static Child start(Path home, Path data, Path run, Path logs, Path errorOutput) throws Exception {
            return start(home, data, run, logs, errorOutput, false);
        }

        /** 两种传输共用隔离入口；可指定本次打包快照复验 JarLauncher，默认验证当前编译类。 */
        private static Child start(Path home, Path data, Path run, Path logs, Path errorOutput,
                                   boolean webSocketMode) throws Exception {
            assertEquals("25", System.getProperty("java.specification.version"));
            List<String> arguments = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                    "--enable-native-access=ALL-UNNAMED", "-Dja.log-dir=" + logs));
            String packagedJar = System.getProperty("ja.test.applicationJar");
            if (packagedJar == null) {
                arguments.addAll(List.of("-cp",
                        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                        App.class.getName()));
            } else {
                Path artifact = Path.of(packagedJar).toAbsolutePath().normalize();
                assertTrue(Files.isRegularFile(artifact), "the requested application JAR must exist");
                arguments.addAll(List.of("-jar", artifact.toString()));
            }
            arguments.add(directoryArgument("home", home));
            arguments.add(directoryArgument("data", data));
            arguments.add(directoryArgument("run", run));
            arguments.add(directoryArgument("log", logs));
            arguments.add("--ja-runtime-generation=1");
            if (webSocketMode) {
                arguments.add("--ja-transport=websocket");
            }
            Process process = new ProcessBuilder(arguments).redirectError(errorOutput.toFile()).start();
            return new Child(process, errorOutput);
        }

        /** 使用 Host 的唯一目录协议，避免测试建立另一套启动参数或相对路径回退。 */
        private static String directoryArgument(String name, Path directory) {
            return "--" + name + "-dir-base64=" + Base64.getEncoder()
                    .encodeToString(directory.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
        }

        /** 对无 id 通知只投递；ready 的实际完成仍由随后健康请求证明。 */
        private void notify(String method, ObjectNode params) throws Exception {
            write(MAPPER.createObjectNode().put("jsonrpc", "2.0").put("method", method).set("params", params));
        }

        /** 顺序发送并等待本请求身份，期间合法状态通知不能冒充业务回执。 */
        private ObjectNode request(String id, String method, ObjectNode params) throws Exception {
            write(MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method).set("params", params));
            for (int index = 0; index < 32; index++) {
                ObjectNode frame = read();
                if (id.equals(frame.path("id").asText())) {
                    return frame;
                }
                assertTrue(frame.has("method"), frame::toString);
            }
            throw new AssertionError("request did not receive a correlated response");
        }

        /** stdout 仅发送真实协议帧，关闭与日志沿独立管道管理。 */
        private void write(ObjectNode frame) throws Exception {
            requests.write(MAPPER.writeValueAsString(frame));
            requests.newLine();
            requests.flush();
        }

        /** 单次读取限时，若框架提前退出则立刻报告 EOF 与 stderr，不等待整个测试超时。 */
        private ObjectNode read() throws Exception {
            Future<String> line = reads.submit(frames::readLine);
            String wire = line.get(25, TimeUnit.SECONDS);
            assertNotNull(wire, this::diagnostics);
            JsonNode frame = MAPPER.readTree(wire);
            assertTrue(frame instanceof ObjectNode, () -> "non-protocol stdout: " + wire);
            return (ObjectNode) frame;
        }

        /** 失败时只保留隔离子进程的流尺寸、生命周期和异常类型，不回显日志正文。 */
        private String diagnostics() {
            try {
                long stderrBytes = Files.size(errorOutput);
                long stderrLines = 0;
                Set<String> failureTypes = new TreeSet<>();
                Set<String> transportFailureCategories = new TreeSet<>();
                try (BufferedReader lines = Files.newBufferedReader(errorOutput, StandardCharsets.UTF_8)) {
                    for (String line; (line = lines.readLine()) != null;) {
                        stderrLines++;
                        Matcher matcher = FAILURE_TYPE.matcher(line);
                        while (matcher.find() && failureTypes.size() < 8) {
                            failureTypes.add(matcher.group(1));
                        }
                        if (line.contains("Ja WebSocket rejected category=invalid_frame ")) {
                            transportFailureCategories.add("invalid_frame");
                        }
                        if (line.contains("Ja WebSocket rejected category=invalid_length ")) {
                            transportFailureCategories.add("invalid_length");
                        }
                        if (line.contains("Ja WebSocket rejected category=invalid_fragment_order ")) {
                            transportFailureCategories.add("invalid_fragment_order");
                        }
                        if (line.contains("Ja WebSocket rejected category=invalid_utf8 ")) {
                            transportFailureCategories.add("invalid_utf8");
                        }
                        if (line.contains("Ja WebSocket rejected category=message_too_big ")) {
                            transportFailureCategories.add("message_too_big");
                        }
                        if (line.contains("Ja WebSocket failure category=native_failure ")) {
                            transportFailureCategories.add("native_failure");
                        }
                    }
                }
                int stdoutBytes = process.getInputStream().available();
                String state = process.isAlive() ? "alive" : "exit-" + process.exitValue();
                return "state=" + state + ", stdoutAvailableBytes=" + stdoutBytes
                        + ", stderrBytes=" + stderrBytes + ", stderrLines=" + stderrLines
                        + ", stderrFailureTypes=" + failureTypes
                        + ", transportFailureCategories=" + transportFailureCategories;
            } catch (java.io.IOException failure) {
                return "diagnosticsUnavailable=" + failure.getClass().getSimpleName();
            }
        }

        /** 任何断言失败都回收本次创建的进程；未退出的进程先强制结束以解除管道读取。 */
        @Override
        public void close() throws Exception {
            try {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(5, TimeUnit.SECONDS), "isolated child did not terminate");
                }
            } finally {
                requests.close();
                frames.close();
                reads.shutdownNow();
                assertTrue(reads.awaitTermination(5, TimeUnit.SECONDS), "isolated reader did not terminate");
            }
        }
    }
}
