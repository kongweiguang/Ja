// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.foundation.lifecycle.NativeClientOwner;
import io.github.kongweiguang.ja.conversation.application.api.NativeExecutionContext;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import io.github.kongweiguang.ja.conversation.domain.model.TurnEvent;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;
import io.github.kongweiguang.ja.collaboration.application.api.TaskUseCase;
import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.application.api.PlanEvent;
import io.github.kongweiguang.ja.plan.application.api.PlanEventSink;
import io.github.kongweiguang.ja.plan.application.api.PlanUseCase;
import io.github.kongweiguang.ja.transport.rpc.handler.HandshakeHandler;
import io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings;
import io.github.kongweiguang.ja.transport.rpc.support.TestConfigurationPorts;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.PipedOutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用隔离目录和真实 FeatHTTP WebSocket 验证业务 owner 与连接的寿命边界。 */
final class WebSocketDaemonTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Process> nativeHostFixtures = new ArrayList<>();

    /**
     * WebSocket 断连不结束宿主生命周期；每项测试结束时回收自行启动的隔离宿主进程。
     * @author kongweiguang
     */
    @AfterEach
    void stopNativeHostFixtures() throws InterruptedException {
        for (Process process : nativeHostFixtures) {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(2, TimeUnit.SECONDS), "Native host fixture failed to exit");
            }
        }
        nativeHostFixtures.clear();
    }

    /** 注册或拒绝连接的关闭异常不能逃出回调；同一异常实例仍须归还全部连接额度。 */
    @Test
    void failedConnectionRegistrationClosesSocketWithoutConsumingCapacity() throws Exception {
        SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"),
                root.resolve("data"), root.resolve("run"), root.resolve("logs"), 53L);
        WebSocketDaemon daemon = new WebSocketDaemon(configuration,
                ignored -> RpcTestBindings.create(null, null, null, null, null, () -> { }),
                TestConfigurationPorts.unavailable(), ignored -> () -> { });
        AtomicInteger failedCloses = new AtomicInteger();
        IllegalStateException registrationFailure = new IllegalStateException("injected registration failure");
        org.noear.solon.net.websocket.WebSocket failed = (org.noear.solon.net.websocket.WebSocket)
                Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{org.noear.solon.net.websocket.WebSocket.class},
                        (proxy, method, arguments) -> switch (method.getName()) {
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            case "setIdleTimeout" -> throw registrationFailure;
                            case "close" -> {
                                failedCloses.incrementAndGet();
                                throw registrationFailure;
                            }
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
        try {
            daemon.listener().onOpen(failed);
            assertEquals(1, failedCloses.get());

            List<AtomicInteger> closes = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                AtomicInteger count = new AtomicInteger();
                closes.add(count);
                org.noear.solon.net.websocket.WebSocket socket = (org.noear.solon.net.websocket.WebSocket)
                        Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[]{org.noear.solon.net.websocket.WebSocket.class},
                                (proxy, method, arguments) -> switch (method.getName()) {
                                    case "hashCode" -> System.identityHashCode(proxy);
                                    case "equals" -> proxy == arguments[0];
                                    case "setIdleTimeout" -> null;
                                    case "close" -> {
                                        count.incrementAndGet();
                                        yield null;
                                    }
                                    default -> throw new UnsupportedOperationException(method.getName());
                                });
                daemon.listener().onOpen(socket);
            }
            assertTrue(closes.stream().allMatch(count -> count.get() == 0));
            AtomicInteger rejectedCloses = new AtomicInteger();
            org.noear.solon.net.websocket.WebSocket rejected = (org.noear.solon.net.websocket.WebSocket)
                    Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{org.noear.solon.net.websocket.WebSocket.class},
                            (proxy, method, arguments) -> switch (method.getName()) {
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == arguments[0];
                                case "close" -> {
                                    rejectedCloses.incrementAndGet();
                                    throw new IllegalStateException("injected rejection close failure");
                                }
                                default -> throw new UnsupportedOperationException(method.getName());
                            });
            daemon.listener().onOpen(rejected);
            assertEquals(1, rejectedCloses.get());
            daemon.close();
            assertTrue(closes.stream().allMatch(count -> count.get() == 1));
        } finally {
            daemon.close();
        }
    }

    /** 关闭必须等正在注册的连接入集，再一次性撤销，避免留下认证计时器和连接额度。 */
    @Test
    void closingDuringConnectionRegistrationClosesAcceptedSocket() throws Exception {
        SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"),
                root.resolve("data"), root.resolve("run"), root.resolve("logs"), 52L);
        WebSocketDaemon daemon = new WebSocketDaemon(configuration,
                ignored -> RpcTestBindings.create(null, null, null, null, null, () -> { }),
                TestConfigurationPorts.unavailable(), ignored -> () -> { });
        CountDownLatch settingIdleTimeout = new CountDownLatch(1);
        CountDownLatch finishRegistration = new CountDownLatch(1);
        AtomicInteger closedSockets = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        org.noear.solon.net.websocket.WebSocket socket = (org.noear.solon.net.websocket.WebSocket)
                Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{org.noear.solon.net.websocket.WebSocket.class},
                        (proxy, method, arguments) -> switch (method.getName()) {
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            case "setIdleTimeout" -> {
                                settingIdleTimeout.countDown();
                                if (!finishRegistration.await(5, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("registration was not released");
                                }
                                yield null;
                            }
                            case "close" -> {
                                closedSockets.incrementAndGet();
                                yield null;
                            }
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
        Thread registration = Thread.ofPlatform().start(() -> {
            try {
                daemon.listener().onOpen(socket);
            } catch (Throwable unexpected) {
                failure.compareAndSet(null, unexpected);
            }
        });
        assertTrue(settingIdleTimeout.await(5, TimeUnit.SECONDS));
        Thread closing = Thread.ofPlatform().start(() -> {
            try {
                daemon.close();
            } catch (Throwable unexpected) {
                failure.compareAndSet(null, unexpected);
            }
        });
        try {
            Instant deadline = Instant.now().plusSeconds(5);
            while (closing.getState() != Thread.State.BLOCKED && Instant.now().isBefore(deadline)) {
                Thread.sleep(1);
            }
            assertEquals(Thread.State.BLOCKED, closing.getState());
        } finally {
            finishRegistration.countDown();
        }
        registration.join(5_000);
        closing.join(5_000);
        assertFalse(registration.isAlive());
        assertFalse(closing.isAlive());
        assertNull(failure.get());
        assertEquals(1, closedSockets.get());
    }

    /** 首个候选端口已被占用时，FeatHTTP 重绑下一候选并只发布实际成功的监听端口。 */
    @Test
    void occupiedPortIsRetriedByFeatHttpBind() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (ServerSocket occupied = new ServerSocket(0, 1, loopback)) {
            int available;
            try (ServerSocket reservation = new ServerSocket(0, 1, loopback)) {
                available = reservation.getLocalPort();
            }
            AtomicInteger candidates = new AtomicInteger();
            SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"),
                root.resolve("data"), root.resolve("run"), root.resolve("logs"), 50L);
            WebSocketDaemon daemon = new WebSocketDaemon(configuration,
                ignored -> RpcTestBindings.create(null, null, null, null, null, () -> { }),
                TestConfigurationPorts.unavailable(), ignored -> () -> { },
                Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofMinutes(5),
                () -> candidates.getAndIncrement() == 0 ? occupied.getLocalPort() : available);
            Thread.ofVirtual().name("ja-port-conflict-test-daemon").start(() -> {
                try { daemon.run(); } catch (IOException ignored) { /* test closes daemon */ }
            });
            try {
                ObjectNode endpoint = waitForEndpoint(configuration.runDirectory().resolve("app-server.endpoint.json"));
                assertEquals(available, endpoint.path("port").intValue());
                assertEquals(2, candidates.get());
                try (Client client = connect(endpoint, "after-bind-conflict")) {
                    health(client, "after-bind-conflict");
                }
            } finally {
                daemon.close();
            }
        }
    }

    /** 显式 host/port 必须由真实 FeatHTTP 绑定，并在端点文件中发布同一可连接地址。 */
    @Test
    void explicitListenAddressIsPublishedAndConnectable() throws Exception {
        int requestedPort;
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            requestedPort = reservation.getLocalPort();
        }
        SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"),
                root.resolve("data"), root.resolve("run"), root.resolve("logs"), 51L,
                "127.0.0.1", requestedPort);
        WebSocketDaemon daemon = new WebSocketDaemon(configuration,
                ignored -> RpcTestBindings.create(null, null, null, null, null, () -> { }),
                TestConfigurationPorts.unavailable(), ignored -> () -> { });
        Thread.ofVirtual().name("ja-explicit-port-test-daemon").start(() -> {
            try { daemon.run(); } catch (IOException ignored) { /* test closes daemon */ }
        });
        try {
            ObjectNode endpoint = waitForEndpoint(configuration.runDirectory().resolve("app-server.endpoint.json"));
            assertEquals("127.0.0.1", endpoint.path("host").asText());
            assertEquals(requestedPort, endpoint.path("port").intValue());
            try (Client client = connect(endpoint, "explicit-address")) {
                health(client, "explicit-address");
            }
        } finally {
            daemon.close();
        }
    }

    /** 通配地址只用于 FeatHTTP 监听，客户端发现文件必须发布可连接的环回地址。 */
    @Test
    void wildcardListenAddressPublishesLoopbackEndpoint() throws Exception {
        SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"),
                root.resolve("data"), root.resolve("run"), root.resolve("logs"), 53L,
                "0.0.0.0", 0);
        WebSocketDaemon daemon = new WebSocketDaemon(configuration,
                ignored -> RpcTestBindings.create(null, null, null, null, null, () -> { }),
                TestConfigurationPorts.unavailable(), ignored -> () -> { });
        Thread.ofVirtual().name("ja-wildcard-test-daemon").start(() -> {
            try { daemon.run(); } catch (IOException ignored) { /* test closes daemon */ }
        });
        try {
            ObjectNode endpoint = waitForEndpoint(configuration.runDirectory().resolve("app-server.endpoint.json"));
            assertEquals("127.0.0.1", endpoint.path("host").asText());
            try (Client client = connect(endpoint, "wildcard-address")) {
                health(client, "wildcard-address");
            }
        } finally {
            daemon.close();
        }
    }

    /** 显式端口冲突不能悄悄重试或发布另一个端口，错误须说明失败的绑定目标。 */
    @Test
    void explicitOccupiedPortFailsWithoutFallback() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"),
                    root.resolve("data"), root.resolve("run"), root.resolve("logs"), 54L,
                    "127.0.0.1", occupied.getLocalPort());
            WebSocketDaemon daemon = new WebSocketDaemon(configuration,
                    ignored -> RpcTestBindings.create(null, null, null, null, null, () -> { }),
                    TestConfigurationPorts.unavailable(), ignored -> () -> { });
            try {
                IOException failure = assertThrows(IOException.class, daemon::run);
                assertTrue(failure.getMessage().contains("127.0.0.1:" + occupied.getLocalPort()));
                assertFalse(Files.exists(configuration.runDirectory().resolve("app-server.endpoint.json")));
            } finally {
                daemon.close();
            }
        }
    }

    /** 超限或未完成的帧只给帧头、不给载荷，必须在 FeatHTTP 解码前断开连接。 */
    @Test
    void invalidFrameIsRejectedBeforePayloadArrives() throws Exception {
        SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"),
                root.resolve("data"), root.resolve("run"), root.resolve("logs"), 49L);
        WebSocketDaemon daemon = new WebSocketDaemon(configuration,
                ignored -> RpcTestBindings.create(null, null, null, null, null, () -> { }),
                TestConfigurationPorts.unavailable(), ignored -> () -> { });
        Thread.ofVirtual().name("ja-oversized-frame-test-daemon").start(() -> {
            try { daemon.run(); } catch (IOException ignored) { /* test closes daemon */ }
        });
        try {
            ObjectNode endpoint = waitForEndpoint(configuration.runDirectory().resolve("app-server.endpoint.json"));
            int port = endpoint.path("port").intValue();
            byte[] header = oversizedMaskedFrameHeader();
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(2_000);
                ByteArrayOutputStream packet = new ByteArrayOutputStream();
                packet.write(webSocketHandshake().getBytes(StandardCharsets.US_ASCII));
                packet.write(header);
                socket.getOutputStream().write(packet.toByteArray());
                assertSocketClosesWithoutPayload(socket.getInputStream());
            }
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(5_000);
                socket.getOutputStream().write(webSocketHandshake().getBytes(StandardCharsets.US_ASCII));
                assertTrue(readHttpHeader(socket.getInputStream()).startsWith("HTTP/1.1 101"));
                socket.getOutputStream().write(maskedAuthFrame(endpoint.path("token").asText()));
                socket.getOutputStream().write(header, 0, 2);
                socket.getOutputStream().write(header, 2, 3);
                socket.getOutputStream().write(header, 5, header.length - 5);
                assertSocketClosesWithoutPayload(socket.getInputStream());
            }
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(2_000);
                socket.getOutputStream().write(oversizedWebSocketHandshake().getBytes(StandardCharsets.US_ASCII));
                assertSocketClosesWithoutPayload(socket.getInputStream());
            }
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(5_000);
                socket.getOutputStream().write(webSocketHandshake().getBytes(StandardCharsets.US_ASCII));
                assertTrue(readHttpHeader(socket.getInputStream()).startsWith("HTTP/1.1 101"));
                socket.getOutputStream().write(maskedAuthFrame(endpoint.path("token").asText()));
                socket.getOutputStream().write(new byte[]{(byte) 0x80, (byte) 0x81, 0, 0, 0, 0});
                assertSocketClosesWithoutPayload(socket.getInputStream());
            }
            try (Client healthy = connect(endpoint, "after-oversized-frame")) {
                health(healthy, "after-oversized-frame");
            }
        } finally {
            daemon.close();
        }
    }

    /** 保留标准 Upgrade 请求，以便同包测试只改变 WS 帧头。 */
    private static String webSocketHandshake() {
        return "GET /ja-rpc HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\n"
            + "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
            + "Sec-WebSocket-Version: 13\r\n\r\n";
    }

    /** 多个短 header 累计越过握手预算，不依赖单字段超过读缓冲的独立拒绝路径。 */
    private static String oversizedWebSocketHandshake() {
        StringBuilder request = new StringBuilder("GET /ja-rpc HTTP/1.1\r\nHost: 127.0.0.1\r\n");
        for (int i = 0; i < 80; i++) {
            request.append("X-Extra-").append(i).append(": ").append("x".repeat(240)).append("\r\n");
        }
        request.append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
            .append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
            .append("Sec-WebSocket-Version: 13\r\n\r\n");
        return request.toString();
    }

    /** 使用 64 位长度编码，声明 4 MiB 加一字节且不构造实际载荷。 */
    private static byte[] oversizedMaskedFrameHeader() {
        return new byte[]{(byte) 0x81, (byte) 0xff, 0, 0, 0, 0, 0, 0x40, 0, 1, 0, 0, 0, 0};
    }

    /** 先完成真实连接认证，后续非法帧测试不会被两秒认证期限伪装为守卫通过。 */
    private static byte[] maskedAuthFrame(String token) {
        byte[] payload = ("{\"token\":\"" + token + "\"}").getBytes(StandardCharsets.UTF_8);
        byte[] mask = {1, 2, 3, 4};
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x81);
        frame.write(0x80 | payload.length);
        frame.writeBytes(mask);
        for (int index = 0; index < payload.length; index++) {
            frame.write(payload[index] ^ mask[index % mask.length]);
        }
        return frame.toByteArray();
    }

    /** 最多读取握手上限内的响应头，避免测试本身无界等待或分配。 */
    private static String readHttpHeader(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (bytes.size() < 16 * 1024) {
            int value = input.read();
            if (value < 0) {
                break;
            }
            bytes.write(value);
            byte[] current = bytes.toByteArray();
            int size = current.length;
            if (size >= 4 && current[size - 4] == '\r' && current[size - 3] == '\n'
                && current[size - 2] == '\r' && current[size - 1] == '\n') {
                break;
            }
        }
        return bytes.toString(StandardCharsets.US_ASCII);
    }

    /** 不发送声明的载荷；EOF 或框架的立即 RST 都表示非法连接已关闭。 */
    private static void assertSocketClosesWithoutPayload(InputStream input) throws IOException {
        try {
            for (int i = 0; i < 16 * 1024; i++) {
                if (input.read() < 0) {
                    return;
                }
            }
        } catch (SocketException closed) {
            return;
        }
        throw new AssertionError("invalid WebSocket frame remained open");
    }

    /**
     * FeatHTTP 空闲超时短于测试等候时，控制 Ping 应保持没有事件的已认证连接。
     */
    @Test
    void pingKeepsQuietConnectionAlive() throws Exception {
        SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"),
                root.resolve("data"), root.resolve("run"), root.resolve("logs"), 47L);
        WebSocketDaemon daemon = new WebSocketDaemon(configuration,
                ignored -> RpcTestBindings.create(null, null, null, null, null, () -> { }),
                TestConfigurationPorts.unavailable(), ignored -> () -> { },
                Duration.ofMinutes(5), Duration.ofMillis(100), Duration.ofMillis(500));
        Thread.ofVirtual().name("ja-ping-test-daemon").start(() -> {
            try { daemon.run(); } catch (IOException ignored) { /* test closes daemon */ }
        });
        try {
            ObjectNode endpoint = waitForEndpoint(configuration.runDirectory().resolve("app-server.endpoint.json"));
            try (Client client = connect(endpoint, "quiet")) {
                Thread.sleep(1_500);
                health(client, "after-quiet-idle");
            }
        } finally {
            daemon.close();
        }
    }

    /**
     * 拒绝文本帧只记录固定 phase 与安全异常摘要，正文哨兵和异常消息不得泄漏。
     *
     * @author kongweiguang
     */
    @Test
    void rejectedTextFrameLogsSafeCloseSourceOnce() throws Exception {
        SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"),
                root.resolve("data"), root.resolve("run"), root.resolve("logs"), 41L);
        WebSocketDaemon daemon = new WebSocketDaemon(configuration,
                ignored -> RpcTestBindings.create(null, null, null, null, null, () -> { }),
                TestConfigurationPorts.unavailable(), ignored -> () -> { });
        Thread.ofVirtual().name("ja-ws-safe-close-frame-test-daemon").start(() -> {
            try { daemon.run(); } catch (IOException ignored) { /* test closes daemon */ }
        });
        try {
            ObjectNode endpoint = waitForEndpoint(configuration.runDirectory().resolve("app-server.endpoint.json"));
            try (Client client = connect(endpoint, "safe-close-frame")) {
                ListAppender<ILoggingEvent> appender = captureClientCloseLogs();
                try {
                    client.writer().write("{\"jsonrpc\":\"2.0\"}\nSYNTHETIC_WS_FRAME_SECRET");
                    client.writer().flush();
                    client.awaitClose();

                    List<ILoggingEvent> closeEvents = clientCloseEvents(appender, 5);
                    assertEquals(1, closeEvents.size(), "the first close cause must be logged once");
                    ILoggingEvent event = closeEvents.getFirst();
                    String message = event.getFormattedMessage();
                    assertEquals(Level.WARN, event.getLevel());
                    assertTrue(message.contains("phase=INBOUND_TEXT_REJECTED"));
                    assertTrue(message.contains("textMessageSequence=5"));
                    assertTrue(message.contains("failureType=java.io.IOException"));
                    assertTrue(message.contains("WebSocketDaemon$Client#onText"));
                    assertFalse(message.contains("SYNTHETIC_WS_FRAME_SECRET"));
                    assertNull(event.getThrowableProxy(), "the raw throwable must not be attached to the log event");
                } finally {
                    detachClientCloseLogs(appender);
                }
            }
        } finally {
            daemon.close();
        }
    }

    /**
     * Close callback 仅证明 Java 观察到 transport 关闭，不据此推断底层发起方。
     *
     * @author kongweiguang
     */
    @Test
    void transportCloseCallbackLogsObservedPhaseWithoutInitiatorClaim() throws Exception {
        SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"),
                root.resolve("data"), root.resolve("run"), root.resolve("logs"), 40L);
        WebSocketDaemon daemon = new WebSocketDaemon(configuration,
                ignored -> RpcTestBindings.create(null, null, null, null, null, () -> { }),
                TestConfigurationPorts.unavailable(), ignored -> () -> { });
        Thread.ofVirtual().name("ja-ws-transport-close-test-daemon").start(() -> {
            try { daemon.run(); } catch (IOException ignored) { /* test closes daemon */ }
        });
        try {
            ObjectNode endpoint = waitForEndpoint(configuration.runDirectory().resolve("app-server.endpoint.json"));
            try (Client client = connect(endpoint, "transport-close")) {
                ListAppender<ILoggingEvent> appender = captureClientCloseLogs();
                try {
                    client.sendClose();
                    assertEquals(WebSocket.NORMAL_CLOSURE, client.awaitClose());

                    List<ILoggingEvent> closeEvents = clientCloseEvents(appender, 4);
                    assertEquals(1, closeEvents.size(),
                            "transport callback and client cleanup must share one close record");
                    ILoggingEvent event = closeEvents.getFirst();
                    String message = event.getFormattedMessage();
                    assertEquals(Level.INFO, event.getLevel());
                    assertTrue(message.contains("phase=TRANSPORT_CLOSE_CALLBACK"));
                    assertTrue(message.contains("textMessageSequence=4"));
                    assertTrue(message.contains("failureType=none"));
                    assertTrue(message.contains("origin=none"));
                    assertFalse(message.contains("peer"), "the callback cannot establish who initiated closure");
                } finally {
                    detachClientCloseLogs(appender);
                }
            }
        } finally {
            daemon.close();
        }
    }

    /** 多个文本分片间允许 Ping/Pong；JA-RPC 只在最终 CONTINUATION 后投递一次。 */
    @Test
    void fragmentedRpcSurvivesInterleavedPingAndDispatchesOnce() throws Exception {
        SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"),
                root.resolve("data"), root.resolve("run"), root.resolve("logs"), 46L);
        WebSocketDaemon daemon = new WebSocketDaemon(configuration,
                ignored -> RpcTestBindings.create(null, null, null, null, null, () -> { }),
                TestConfigurationPorts.unavailable(), ignored -> () -> { });
        Thread.ofVirtual().name("ja-fragmented-frame-test-daemon").start(() -> {
            try { daemon.run(); } catch (IOException ignored) { /* test closes daemon */ }
        });
        try {
            ObjectNode endpoint = waitForEndpoint(configuration.runDirectory().resolve("app-server.endpoint.json"));
            try (Client client = connect(endpoint, "fragmented")) {
                String id = "c:fragmented_context";
                ObjectNode context = mapper.createObjectNode();
                context.putObject("environment").put("JA_FRAGMENT_TEST", "重庆恢复");
                context.putNull("shell");
                setNativeClientIdentity(context, client);
                ObjectNode request = mapper.createObjectNode().put("jsonrpc", "2.0")
                        .put("id", id).put("method", "runtime/context/register");
                request.set("params", context);
                String wire = mapper.writeValueAsString(request);
                int chinese = wire.indexOf("重庆");
                assertTrue(chinese >= 0);
                byte[] ping = "fragment-ping".getBytes(StandardCharsets.UTF_8);

                client.sendFragment(wire.substring(0, chinese + 1), false);
                client.sendPing(ping);
                client.sendFragment(wire.substring(chinese + 1, chinese + 2), false);
                client.sendFragment(wire.substring(chinese + 2), true);

                assertArrayEquals(ping, client.awaitPong());
                ObjectNode rpcResponse = response(client, id);
                assertTrue(rpcResponse.path("result").path("contextId").asText()
                        .matches("ctx_[0-9a-f]{32}"));
                client.socket.setSoTimeout(200);
                assertThrows(SocketTimeoutException.class, () -> client.reader().readLine(),
                        "one logical message must produce exactly one RPC response");
                client.socket.setSoTimeout(2_000);
            }
        } finally {
            daemon.close();
        }
    }

    /** Close 与 TCP 断开都释放未完成消息；后续连接仍可用同一 daemon 完成 RPC。 */
    @Test
    void closeAndDisconnectReleaseFragmentAssembly() throws Exception {
        SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"),
                root.resolve("data"), root.resolve("run"), root.resolve("logs"), 45L);
        WebSocketDaemon daemon = new WebSocketDaemon(configuration,
                ignored -> RpcTestBindings.create(null, null, null, null, null, () -> { }),
                TestConfigurationPorts.unavailable(), ignored -> () -> { });
        Thread.ofVirtual().name("ja-fragment-close-test-daemon").start(() -> {
            try { daemon.run(); } catch (IOException ignored) { /* test closes daemon */ }
        });
        try {
            ObjectNode endpoint = waitForEndpoint(configuration.runDirectory().resolve("app-server.endpoint.json"));
            try (Client closing = connect(endpoint, "fragment-close")) {
                closing.sendFragment("{\"jsonrpc\":\"2.0\",\"id\":", false);
                closing.sendClose();
                assertEquals(WebSocket.NORMAL_CLOSURE, closing.awaitClose());
            }
            try (Client disconnected = connect(endpoint, "fragment-disconnect")) {
                disconnected.sendFragment("{\"jsonrpc\":\"2.0\",\"id\":", false);
                disconnected.close();
            }
            try (Client recovered = connect(endpoint, "after-fragment-close")) {
                health(recovered, "after-fragment-close");
            }
        } finally {
            daemon.close();
        }
    }

    /** 未收到对端活动时仍须触发框架空闲关闭，不能通过关闭计时器让保活回归虚假通过。 */
    @Test
    void idleConnectionStillClosesBeforeNextPing() throws Exception {
        SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"),
                root.resolve("data"), root.resolve("run"), root.resolve("logs"), 48L);
        WebSocketDaemon daemon = new WebSocketDaemon(configuration,
                ignored -> RpcTestBindings.create(null, null, null, null, null, () -> { }),
                TestConfigurationPorts.unavailable(), ignored -> () -> { },
                Duration.ofMinutes(5), Duration.ofSeconds(3), Duration.ofMillis(500));
        Thread.ofVirtual().name("ja-no-ping-test-daemon").start(() -> {
            try { daemon.run(); } catch (IOException ignored) { /* test closes daemon */ }
        });
        try {
            ObjectNode endpoint = waitForEndpoint(configuration.runDirectory().resolve("app-server.endpoint.json"));
            try (Client client = connect(endpoint, "idle")) {
                assertNull(client.reader().readLine(), "the idle connection must close before the delayed ping");
            }
        } finally {
            daemon.close();
        }
    }

    /**
     * 错误认证不进入 JA-RPC；A 断开后 B 保持可用，C 可重连同一实例，端点权限
     * 始终只允许当前 owner，且关闭后不残留可误连的端点。
     */
    @Test
    void authenticatedClientsAreIndependentAndEndpointIsPrivate() throws Exception {
        SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"),
                root.resolve("data"), root.resolve("run"), root.resolve("logs"), 41L);
        WebSocketDaemon daemon = new WebSocketDaemon(configuration,
                ignored -> RpcTestBindings.create(null, null, null, null, null, () -> { }),
                TestConfigurationPorts.unavailable(), ignored -> () -> { });
        CompletableFuture<Integer> stopped = new CompletableFuture<>();
        Thread.ofVirtual().name("ja-test-daemon").start(() -> {
            try { stopped.complete(daemon.run()); }
            catch (IOException failure) { stopped.completeExceptionally(failure); }
        });
        try {
            Path endpointPath = configuration.runDirectory().resolve("app-server.endpoint.json");
            ObjectNode endpoint = waitForEndpoint(endpointPath);
            assertEquals(41L, endpoint.path("runtimeGeneration").longValue());
            assertEquals(64, endpoint.path("token").asText().length());
            assertPrivateEndpoint(endpointPath);
            int port = endpoint.path("port").intValue();
            try (Client rejected = open(port)) {
                rejected.writer().write("{\"token\":\"wrong\"}\n");
                rejected.writer().flush();
                assertEquals(null, rejected.reader().readLine());
            }
            try (Client first = connect(endpoint, "one"); Client second = connect(endpoint, "two")) {
                health(first, "one");
                health(second, "two");
                first.close();
                health(second, "two-after-first-exit");
                ObjectNode context = mapper.createObjectNode();
                context.putObject("environment").put("JA_TCP_TEST", "isolated");
                context.putNull("shell");
                setNativeClientIdentity(context, second);
                send(second, "c:context_two", "runtime/context/register", context);
                assertTrue(response(second, "c:context_two").path("result").path("contextId")
                        .asText().matches("ctx_[0-9a-f]{32}"));
                assertFalse(Files.readString(endpointPath).contains("JA_TCP_TEST"));
                try (Client third = connect(endpoint, "three")) {
                    health(third, "three");
                    assertEquals(endpoint.path("serverInstanceId").asText(),
                            third.instanceId());
                }
            }
            assertFalse(stopped.isDone(), "disconnected clients must not close the Java owner");
            assertTrue(Files.exists(endpointPath));
        } finally {
            daemon.close();
        }
        assertFalse(Files.exists(configuration.runDirectory().resolve("app-server.endpoint.json")));
    }

    /**
     * 显式订阅先于 ACK 建立；未订阅连接没有 Turn 事件，断线重连后仍可读取
     * Thread 权威基线与 Plan 观察按各自身份过滤，unobserve 只停止当前连接的后续 Plan 投影。
     * 快读者按小批确认消费，使此用例测量慢连接隔离而不是测试线程调度速度。
     * @author kongweiguang
     */
    @Test
    void threadObservationFiltersEventsAndReadRemainsAvailableAfterReconnect() throws Exception {
        SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"),
                root.resolve("data"), root.resolve("run"), root.resolve("logs"), 42L);
        ThreadSnapshot snapshot = emptyThreadSnapshot();
        ThreadUseCase threads = (ThreadUseCase) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ThreadUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "readThread", "readThreadLatest" -> Optional.of(snapshot);
                    case "discoverThreads" -> new CursorPage<ThreadDiscovery>(List.of(), null);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        TaskUseCase tasks = (TaskUseCase) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{TaskUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "subscribe" -> (AutoCloseable) () -> { };
                    case "listRootActivities" -> List.of();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        PlanModels.PlanSnapshot plan = planSnapshot();
        AtomicReference<PlanEventSink> planEvents = new AtomicReference<>();
        PlanUseCase plans = (PlanUseCase) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PlanUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "subscribePlan" -> {
                        planEvents.set((PlanEventSink) arguments[0]);
                        yield (AutoCloseable) () -> planEvents.set(null);
                    }
                    case "readPlan" -> plan;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AtomicReference<RpcSession> owner = new AtomicReference<>();
        WebSocketDaemon daemon = new WebSocketDaemon(configuration, ignored -> {
            RpcServiceBindings base = RpcTestBindings.create(null, threads, null, null, null, () -> { });
            return new RpcServiceBindings(base.workspaces(), base.workspacePathSearch(), base.threads(),
                    base.threadMcp(), base.turns(), base.compactions(), base.approvals(), base.catalog(),
                    base.attachments(), base.attachmentPreviews(), tasks, plans,
                    base.interactions(),
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedProcesses(),
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedReviews(), base.lifecycle());
        }, TestConfigurationPorts.unavailable(), session -> {
            owner.set(session);
            return () -> { };
        });
        Thread.ofVirtual().name("ja-observe-test-daemon").start(() -> {
            try { daemon.run(); } catch (IOException ignored) { /* test closes listener */ }
        });
        try {
            ObjectNode endpoint = waitForEndpoint(configuration.runDirectory().resolve("app-server.endpoint.json"));
            try (Client unobserved = connect(endpoint, "unobserved");
                 Client observed = connect(endpoint, "observed")) {
                ObjectNode thread = mapper.createObjectNode().put("threadId", "thr_test");
                send(observed, "c:observe", "thread/observe", thread);
                assertEquals(true, response(observed, "c:observe").path("result").path("accepted").booleanValue());
                send(observed, "c:baseline", "thread/read", thread);
                assertEquals(1, response(observed, "c:baseline").path("result").path("revision").intValue());
                publishState(owner.get(), "turn_first", "evt_first");
                ObjectNode event = (ObjectNode) mapper.readTree(observed.reader().readLine());
                assertEquals("turn/state-changed", event.path("method").asText());
                assertEquals("thr_test", event.path("params").path("threadId").asText());
                try (Client peer = connect(endpoint, "peer")) {
                    send(peer, "c:observe-peer", "thread/observe", thread);
                    response(peer, "c:observe-peer");
                    ObjectNode planRequest = mapper.createObjectNode()
                            .put("threadId", "thr_test").put("planId", "plan_test");
                    send(observed, "c:observe-plan", "plan/observe", planRequest);
                    ObjectNode planObservation = response(observed, "c:observe-plan").withObject("result");
                    planEvents.get().publish(planEvent(plan.plan(), 1)).toCompletableFuture().join();
                    ObjectNode activity = (ObjectNode) mapper.readTree(observed.reader().readLine());
                    assertEquals("plan/changed", activity.path("method").asText());
                    assertEquals("plan_test", activity.path("params").path("planId").asText());
                    peer.socket.setSoTimeout(200);
                    org.junit.jupiter.api.Assertions.assertThrows(SocketTimeoutException.class,
                            () -> peer.reader().readLine());
                    peer.socket.setSoTimeout(2_000);
                    send(peer, "c:observe-peer-plan", "plan/observe", planRequest);
                    response(peer, "c:observe-peer-plan");
                    peer.close();
                    planEvents.get().publish(planEvent(plan.plan(), 2)).toCompletableFuture().join();
                    ObjectNode afterPeerExit = (ObjectNode) mapper.readTree(observed.reader().readLine());
                    assertEquals("plan/changed", afterPeerExit.path("method").asText());
                    send(observed, "c:unobserve-plan", "plan/unobserve", mapper.createObjectNode()
                            .put("observationId", planObservation.path("observationId").asText()));
                    response(observed, "c:unobserve-plan");
                    planEvents.get().publish(planEvent(plan.plan(), 3)).toCompletableFuture().join();
                    observed.socket.setSoTimeout(200);
                    org.junit.jupiter.api.Assertions.assertThrows(SocketTimeoutException.class,
                            () -> observed.reader().readLine());
                    observed.socket.setSoTimeout(2_000);
                }
                try (Client slow = connect(endpoint, "slow")) {
                    send(slow, "c:observe-slow", "thread/observe", thread);
                    response(slow, "c:observe-slow");
                    slow.socket.setReceiveBufferSize(1_024);
                    observed.socket.setSoTimeout(10_000);
                    owner.get().registerTurnNotificationContext("turn_flood", "ws_test", "thr_test", 1);
                    CompletableFuture<Integer> fastHealth = new CompletableFuture<>();
                    Semaphore fastDeltas = new Semaphore(0);
                    Thread.ofVirtual().name("ja-fast-observer").start(() -> {
                        try {
                            int deltas = 0;
                            while (true) {
                                String line = observed.reader().readLine();
                                if (line == null) {
                                    throw new IOException("fast observer disconnected");
                                }
                                if (line.contains("\"method\":\"assistant/text-delta\"")) {
                                    deltas++;
                                    fastDeltas.release();
                                }
                                if (line.contains("\"id\":\"c:health-after-flood\"")) {
                                    ObjectNode health = (ObjectNode) mapper.readTree(line);
                                    if (!"ready".equals(health.path("result").path("status").asText())) {
                                        throw new IOException("fast observer health failed");
                                    }
                                    fastHealth.complete(deltas);
                                    return;
                                }
                            }
                        } catch (IOException failure) {
                            fastHealth.completeExceptionally(failure);
                        }
                    });
                    String payload = "x".repeat(48_000);
                    long fastDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    int acknowledged = 0;
                    for (int sequence = 1; sequence <= 600; sequence++) {
                        owner.get().publish(new TurnEvent.TextDelta(
                                "turn_flood", sequence, payload, "mreq_flood")).join();
                        if (sequence % 16 == 0 || sequence == 600) {
                            int batchSize = sequence - acknowledged;
                            long remaining = fastDeadline - System.nanoTime();
                            assertTrue(remaining > 0 && fastDeltas.tryAcquire(batchSize, remaining,
                                            TimeUnit.NANOSECONDS),
                                    "fast observer must drain each bounded event batch");
                            acknowledged = sequence;
                        }
                    }
                    send(observed, "c:health-after-flood", "runtime/health", mapper.createObjectNode());
                    long remaining = fastDeadline - System.nanoTime();
                    assertTrue(remaining > 0, "fast observer must drain the flood within the existing budget");
                    assertEquals(600, fastHealth.get(remaining, TimeUnit.NANOSECONDS),
                            "fast observer must receive every event while a peer is stalled");
                    int drained = 0;
                    boolean disconnected = false;
                    slow.socket.setSoTimeout(2_000);
                    while (drained++ < 600) {
                        String line = slow.reader().readLine();
                        if (line == null) { disconnected = true; break; }
                    }
                    assertTrue(disconnected, "slow observer must disconnect after bounded queue overflow");
                    observed.socket.setSoTimeout(2_000);
                }
                unobserved.socket.setSoTimeout(200);
                org.junit.jupiter.api.Assertions.assertThrows(SocketTimeoutException.class,
                        () -> unobserved.reader().readLine());
                send(observed, "c:unobserve", "thread/unobserve", thread);
                assertEquals(true, response(observed, "c:unobserve").path("result").path("accepted").booleanValue());
                publishState(owner.get(), "turn_second", "evt_second");
                observed.socket.setSoTimeout(200);
                org.junit.jupiter.api.Assertions.assertThrows(SocketTimeoutException.class,
                        () -> observed.reader().readLine());
            }
            try (Client reconnected = connect(endpoint, "observed")) {
                send(reconnected, "c:reobserve", "thread/observe",
                        mapper.createObjectNode().put("threadId", "thr_test"));
                response(reconnected, "c:reobserve");
                send(reconnected, "c:read-again", "thread/read",
                        mapper.createObjectNode().put("threadId", "thr_test").put("tail", true));
                ObjectNode result = response(reconnected, "c:read-again").withObject("result");
                assertEquals("thr_test", result.path("threadId").asText());
                assertTrue(result.path("liveStream").isNull());
            }
        } finally {
            daemon.close();
        }
    }

    /**
     * close 已落到 owner 但响应在断线时丢失，重连沿用相同 token 并安全重放关闭回执。
     * @author kongweiguang
     */
    @Test
    void lostNativeClientCloseAckCanRetryAfterReconnect() throws Exception {
        SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"),
                root.resolve("data"), root.resolve("run"), root.resolve("logs"), 46L);
        AtomicInteger closeAttempts = new AtomicInteger();
        AtomicReference<NativeClientOwner> firstOwner = new AtomicReference<>();
        CountDownLatch firstCloseCommitted = new CountDownLatch(1);
        TaskUseCase tasks = (TaskUseCase) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{TaskUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "closeNativeClient" -> {
                        NativeClientOwner owner = (NativeClientOwner) arguments[0];
                        if (closeAttempts.incrementAndGet() == 1) {
                            firstOwner.set(owner);
                            NativeExecutionContext.shared().beginNativeClientClose(owner);
                            NativeExecutionContext.shared().completeNativeClientClose(owner);
                            firstCloseCommitted.countDown();
                        } else {
                            assertTrue(firstOwner.get() == owner,
                                    "same live Native token must resolve to its existing closed owner fence");
                        }
                        yield null;
                    }
                    case "hasLiveNativeClientWork" -> false;
                    case "closeTemporarySideChats" -> null;
                    case "subscribe" -> (AutoCloseable) () -> { };
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        WebSocketDaemon daemon = new WebSocketDaemon(configuration, ignored -> {
            RpcServiceBindings base = RpcTestBindings.create(null, null, null, null, null, () -> { });
            return new RpcServiceBindings(base.workspaces(), base.workspacePathSearch(), base.threads(),
                    base.threadMcp(), base.turns(), base.compactions(), base.approvals(), base.catalog(),
                    base.attachments(), base.attachmentPreviews(), tasks, base.plans(), base.interactions(),
                    base.processes(), base.reviews(), base.lifecycle());
        }, TestConfigurationPorts.unavailable(), ignored -> () -> { });
        CompletableFuture<Integer> stopped = new CompletableFuture<>();
        Thread.ofVirtual().name("ja-client-close-ack-loss-test").start(() -> {
            try { stopped.complete(daemon.run()); }
            catch (IOException failure) { stopped.completeExceptionally(failure); }
        });
        try {
            ObjectNode endpoint = waitForEndpoint(configuration.runDirectory().resolve("app-server.endpoint.json"));
            try (Client first = connect(endpoint, "close-ack-loss")) {
                send(first, "c:close-lost", "runtime/client/close", mapper.createObjectNode().put("timeoutMs", 2_000));
                assertTrue(firstCloseCommitted.await(5, TimeUnit.SECONDS), "server did not commit the first close");
            }
            try (Client reconnected = connect(endpoint, "close-ack-loss")) {
                send(reconnected, "c:close-retry", "runtime/client/close",
                        mapper.createObjectNode().put("timeoutMs", 2_000));
                assertTrue(response(reconnected, "c:close-retry").path("result").path("accepted").booleanValue());
                assertEquals(2, closeAttempts.get());
                assertTrue(NativeExecutionContext.shared().nativeClientClosed(firstOwner.get()));
            }
            daemon.close();
            assertEquals(0, stopped.get(5, TimeUnit.SECONDS));
        } finally {
            daemon.close();
        }
    }

    /**
     * 普通 stop 在仍有准入 Turn 时通过原子准入屏障返回稳定冲突并保持可连接；显式 force 才能
     * 进入既有有界清理路径，测试不构造真实 Tool 副作用。
     */
    @Test
    void shutdownRejectsActiveWorkAndThenStopsWhenQuiescent() throws Exception {
        SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"),
                root.resolve("data"), root.resolve("run"), root.resolve("logs"), 43L);
        AtomicBoolean busy = new AtomicBoolean(true);
        TurnUseCase turns = (TurnUseCase) Proxy.newProxyInstance(getClass().getClassLoader(),
                 new Class<?>[]{TurnUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                     case "awaitQuiescence" -> !busy.get();
                     case "stopAcceptingIfQuiescent" -> !busy.get();
                     case "stopAccepting" -> null;
                     default -> throw new UnsupportedOperationException(method.getName());
                 });
        ThreadUseCase threads = (ThreadUseCase) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ThreadUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "discoverThreads" -> new CursorPage<ThreadDiscovery>(List.of(), null);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        WebSocketDaemon daemon = new WebSocketDaemon(configuration,
                ignored -> RpcTestBindings.create(null, threads, turns, null, null, () -> { }),
                TestConfigurationPorts.unavailable(), ignored -> () -> { });
        CompletableFuture<Integer> stopped = new CompletableFuture<>();
        Thread.ofVirtual().name("ja-shutdown-test-daemon").start(() -> {
            try { stopped.complete(daemon.run()); }
            catch (IOException failure) { stopped.completeExceptionally(failure); }
        });
        try {
            Path endpointPath = configuration.runDirectory().resolve("app-server.endpoint.json");
            ObjectNode endpoint = waitForEndpoint(endpointPath);
            try (Client client = connect(endpoint, "shutdown")) {
                send(client, "c:stop-busy", "runtime/shutdown", mapper.createObjectNode());
                assertEquals("INVALID_STATE", response(client, "c:stop-busy")
                        .path("error").path("data").path("errorCode").asText());
                health(client, "after-rejected-stop");
                assertFalse(stopped.isDone());
            send(client, "c:stop-forced", "runtime/shutdown", mapper.createObjectNode().put("force", true));
            ObjectNode result = response(client, "c:stop-forced").withObject("result");
                assertTrue(result.path("accepted").booleanValue());
                assertEquals("shutting_down", result.path("status").asText());
            }
            assertEquals(0, stopped.get(5, TimeUnit.SECONDS));
            assertFalse(Files.exists(endpointPath));
        } finally {
            daemon.close();
        }
    }

    /**
     * 物理管道 EOF 不能抢先清空客户端尾帧；真实客户端须先得到 shutdown ACK 和 stopped，再观察后台退出。
     */
    @Test
    void orderlyOutputEofDeliversShutdownAckAndFinalStatus() throws Exception {
        SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home-tail"),
                root.resolve("data-tail"), root.resolve("run-tail"), root.resolve("logs-tail"), 44L);
        TurnUseCase turns = (TurnUseCase) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{TurnUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "awaitQuiescence", "stopAcceptingIfQuiescent" -> true;
                    case "stopAccepting" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        WebSocketDaemon daemon = new WebSocketDaemon(configuration,
                ignored -> RpcTestBindings.create(null, null, turns, null, null, () -> { }),
                TestConfigurationPorts.unavailable(), ignored -> () -> { });
        CompletableFuture<Integer> stopped = new CompletableFuture<>();
        Thread.ofVirtual().name("ja-shutdown-tail-test").start(() -> {
            try {
                stopped.complete(daemon.run());
            } catch (IOException failure) {
                stopped.completeExceptionally(failure);
            }
        });
        try {
            ObjectNode endpoint = waitForEndpoint(configuration.runDirectory().resolve("app-server.endpoint.json"));
            try (Client client = connect(endpoint, "shutdown-tail")) {
                send(client, "c:stop-tail", "runtime/shutdown", mapper.createObjectNode().put("force", true));
                assertTrue(response(client, "c:stop-tail").path("result").path("accepted").booleanValue());
                ObjectNode finalStatus = (ObjectNode) mapper.readTree(client.reader().readLine());
                assertNotNull(finalStatus, "final status was dropped before transport close");
                assertEquals("runtime/status-changed", finalStatus.path("method").asText());
                assertEquals("stopped", finalStatus.path("params").path("status").asText());
            }
            assertEquals(0, stopped.get(5, TimeUnit.SECONDS));
        } finally {
            daemon.close();
        }
    }

    /**
     * 无客户端的定时退出先经过业务保活检查；活跃 Turn 让原子准入屏障拒绝关闭并重新计时，
     * 结算后才移除端点和释放唯一 owner。
     */
    @Test
    void idleExitWaitsForPendingWork() throws Exception {
        SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"),
                root.resolve("data"), root.resolve("run"), root.resolve("logs"), 44L);
        AtomicBoolean busy = new AtomicBoolean(true);
        TurnUseCase turns = (TurnUseCase) Proxy.newProxyInstance(getClass().getClassLoader(),
                 new Class<?>[]{TurnUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                     case "awaitQuiescence" -> !busy.get();
                     case "stopAcceptingIfQuiescent" -> !busy.get();
                     case "stopAccepting" -> null;
                     default -> throw new UnsupportedOperationException(method.getName());
                 });
        ThreadUseCase threads = (ThreadUseCase) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ThreadUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "discoverThreads" -> new CursorPage<ThreadDiscovery>(List.of(), null);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        WebSocketDaemon daemon = new WebSocketDaemon(configuration,
                ignored -> RpcTestBindings.create(null, threads, turns, null, null, () -> { }),
                TestConfigurationPorts.unavailable(), ignored -> () -> { }, Duration.ofMillis(100));
        CompletableFuture<Integer> stopped = new CompletableFuture<>();
        Thread.ofVirtual().name("ja-idle-test-daemon").start(() -> {
            try { stopped.complete(daemon.run()); }
            catch (IOException failure) { stopped.completeExceptionally(failure); }
        });
        try {
            Path endpoint = configuration.runDirectory().resolve("app-server.endpoint.json");
            waitForEndpoint(endpoint);
            Thread.sleep(1_300);
            assertFalse(stopped.isDone(), "pending work must survive the idle timeout");
            assertTrue(Files.exists(endpoint));
            busy.set(false);
            assertEquals(0, stopped.get(4, TimeUnit.SECONDS));
            assertFalse(Files.exists(endpoint));
        } finally {
            daemon.close();
        }
    }

    /** 内部 RPC 输入故障不能被 TCP 外壳改写为正常退出或留下可误连的端点。 */
    @Test
    void innerRpcFailurePropagatesNonzeroExitAndRemovesEndpoint() throws Exception {
        SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"),
                root.resolve("data"), root.resolve("run"), root.resolve("logs"), 45L);
        WebSocketDaemon daemon = new WebSocketDaemon(configuration,
                ignored -> RpcTestBindings.create(null, null, null, null, null, () -> { }),
                TestConfigurationPorts.unavailable(), ignored -> () -> { });
        CompletableFuture<Integer> stopped = new CompletableFuture<>();
        Thread.ofVirtual().name("ja-inner-failure-test").start(() -> {
            try { stopped.complete(daemon.run()); }
            catch (IOException failure) { stopped.completeExceptionally(failure); }
        });
        try {
            Path endpoint = configuration.runDirectory().resolve("app-server.endpoint.json");
            waitForEndpoint(endpoint);
            writeMalformedInnerFrame(daemon);
            assertEquals(1, stopped.get(5, TimeUnit.SECONDS));
            assertFalse(Files.exists(endpoint));
        } finally {
            try { daemon.close(); }
            catch (RuntimeException expectedAfterInjectedFailure) {
                assertEquals(1, stopped.getNow(0));
            }
        }
    }

    /** 输出泵丢失后应立即撤销发现面并唤醒 accept，不能继续给新连接空等握手。 */
    @Test
    void outputPumpFailureFailsClosedBeforeAnotherClientCanAttach() throws Exception {
        SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"),
                root.resolve("data"), root.resolve("run"), root.resolve("logs"), 46L);
        WebSocketDaemon daemon = new WebSocketDaemon(configuration,
                ignored -> RpcTestBindings.create(null, null, null, null, null, () -> { }),
                TestConfigurationPorts.unavailable(), ignored -> () -> { });
        CompletableFuture<Integer> stopped = new CompletableFuture<>();
        Thread.ofVirtual().name("ja-output-failure-test").start(() -> {
            try { stopped.complete(daemon.run()); }
            catch (IOException failure) { stopped.completeExceptionally(failure); }
        });
        try {
            Path endpoint = configuration.runDirectory().resolve("app-server.endpoint.json");
            ObjectNode descriptor = waitForEndpoint(endpoint);
            writeMalformedOutputFrame(daemon);
            assertEquals(1, stopped.get(5, TimeUnit.SECONDS));
            assertFalse(Files.exists(endpoint));
            org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                    () -> {
                        try (Client ignored = open(descriptor.path("port").intValue())) {
                            ignored.writer().write("{\"token\":\"wrong\"}\n");
                            ignored.writer().flush();
                            if (ignored.reader().readLine() == null) {
                                throw new IOException("transport rejected after failure");
                            }
                        }
                    });
        } finally {
            try { daemon.close(); }
            catch (RuntimeException expectedAfterInjectedFailure) {
                assertEquals(1, stopped.getNow(0));
            }
        }
    }

    /** 故意写一条非法内部帧，让真实 RpcServer.run 报非零而不依赖 Pipe 关闭唤醒语义。 */
    private static void writeMalformedInnerFrame(WebSocketDaemon daemon) throws Exception {
        java.lang.reflect.Field field = WebSocketDaemon.class.getDeclaredField("runtimeIngress");
        field.setAccessible(true);
        PipedOutputStream ingress = (PipedOutputStream) field.get(daemon);
        ingress.write("{invalid}\n".getBytes(StandardCharsets.UTF_8));
        ingress.flush();
    }

    /** 输出损坏使真实 Codec 失败；不依赖 JDK Pipe.close 对阻塞 read 的唤醒行为。 */
    private static void writeMalformedOutputFrame(WebSocketDaemon daemon) throws Exception {
        java.lang.reflect.Field field = WebSocketDaemon.class.getDeclaredField("runtimeEgress");
        field.setAccessible(true);
        PipedOutputStream egress = (PipedOutputStream) field.get(daemon);
        egress.write("{invalid}\n".getBytes(StandardCharsets.UTF_8));
        egress.flush();
    }

    /** 构造只有公开元数据的权威页面，使观察测试不依赖数据库或付费 Provider。 */
    private static ThreadSnapshot emptyThreadSnapshot() {
        ThreadPreferences preferences = new ThreadPreferences("provider_test", "model_test", null,
                AccessMode.FULL_ACCESS, CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.MANUAL);
        ThreadSummary thread = new ThreadSummary("thr_test", "ws_test", "test", "project", preferences, ThreadSummary.Status.ACTIVE, false, null, true, 1, Instant.parse("2026-09-24T00:00:00Z"), Instant.parse("2026-09-24T00:00:00Z"), null);
        return new ThreadSnapshot(thread, List.of(), List.of(), null, null, null);
    }

    /** Plan observation 只需要可读的独立快照，执行事件通过受连接过滤的订阅口发布。 */
    private static PlanModels.PlanSnapshot planSnapshot() {
        Instant now = Instant.parse("2026-09-24T00:00:00Z");
        PlanModels.Plan plan = new PlanModels.Plan("plan_test", "thr_test", "test plan",
                PlanModels.PlanStatus.DRAFT, 1, null, null, now, now);
        return new PlanModels.PlanSnapshot(plan, null, null, null, List.of(), 1);
    }

    /** 每个事件保留 Plan revision，并只携带有界进度而不构造执行正文。 */
    private static PlanEvent planEvent(PlanModels.Plan plan, long sequence) {
        PlanModels.PublicEvent activity = new PlanModels.PublicEvent(
                sequence, plan.revision(), "step_changed", "step", Instant.parse("2026-09-24T00:00:00Z"));
        return new PlanEvent(plan, sequence, activity, new PlanModels.PlanProgress(null, null, 0, 0));
    }

    /** 模拟一次已提交 Turn 通知，检验 TCP 层过滤而不启动 Agent/Provider。 */
    private static void publishState(RpcSession owner, String turnId, String eventId) {
        owner.registerTurnNotificationContext(turnId, "ws_test", "thr_test", 1);
        owner.publish(new TurnEvent.StateChanged(new TurnEvent.Context(eventId, "thr_test", turnId,
                1, 0, Instant.parse("2026-09-24T00:00:00Z")), TurnState.QUEUED, TurnState.RUNNING)).join();
    }

    /** 等待的是新测试 owner 的原子端点，不探测用户当前 Ja 数据目录。 */
    private ObjectNode waitForEndpoint(Path endpoint) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(endpoint)) {
                return (ObjectNode) mapper.readTree(Files.readAllBytes(endpoint));
            }
            Thread.sleep(10);
        }
        throw new AssertionError("isolated WebSocket endpoint was not published");
    }

    /** Windows DACL 与 POSIX 0600 均以真实文件属性回读，不从设置成功推断权限生效。 */
    private static void assertPrivateEndpoint(Path endpoint) throws IOException {
        AclFileAttributeView acl = Files.getFileAttributeView(endpoint, AclFileAttributeView.class);
        if (acl != null) {
            assertEquals(1, acl.getAcl().size());
            assertEquals(acl.getOwner(), acl.getAcl().getFirst().principal());
            return;
        }
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(endpoint));
    }

    /**
     * 捕获目标 daemon 的应用日志，断言诊断边界而不依赖全局日志文件。
     *
     * @author kongweiguang
     */
    private static ListAppender<ILoggingEvent> captureClientCloseLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(WebSocketDaemon.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /**
     * 仅返回目标连接的本地消息序号，保留该连接任何第二种 close phase 以验证首因只记录一次。
     *
     * @author kongweiguang
     */
    private static List<ILoggingEvent> clientCloseEvents(ListAppender<ILoggingEvent> appender,
                                                         long expectedSequence) {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("category=websocket_client_close"))
                .filter(event -> event.getFormattedMessage().contains("textMessageSequence=" + expectedSequence))
                .toList();
    }

    /**
     * 每项测试独立拆除 appender，避免全局 logger 持有测试对象或相互污染。
     *
     * @author kongweiguang
     */
    private static void detachClientCloseLogs(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(WebSocketDaemon.class)).detachAppender(appender);
        appender.stop();
    }

    /**
     * 客户端严格走 token 前帧、initialize 和 ready 挑战，并维持断连重连期间不变的 Native 生命周期。
     * @author kongweiguang
     */
    private Client connect(ObjectNode endpoint, String label) throws Exception {
        Client client = open(endpoint.path("port").intValue());
        attachNativeClientIdentity(client, label);
        client.writer().write(mapper.writeValueAsString(mapper.createObjectNode()
                .put("token", endpoint.path("token").asText())) + "\n");
        client.writer().flush();
        ObjectNode params = mapper.createObjectNode().put("protocolMajor", 2).put("protocolMinor", 0)
                .put("clientVersion", "test");
        params.set("capabilities", HandshakeHandler.capabilities(mapper));
        params.set("limits", HandshakeHandler.limits(mapper));
        send(client, "c:init_" + label, "runtime/initialize", params);
        ObjectNode result = response(client, "c:init_" + label).withObject("result");
        assertEquals(endpoint.path("serverInstanceId").asText(), result.path("serverInstanceId").asText());
        client.instanceId = result.path("serverInstanceId").asText();
        ObjectNode ready = mapper.createObjectNode().put("jsonrpc", "2.0")
                .put("method", "runtime/initialized");
        ready.set("params", mapper.createObjectNode().put("readyToken", "0123456789abcdef0123456789abcdef"));
        client.writer().write(mapper.writeValueAsString(ready) + "\n");
        client.writer().flush();
        ObjectNode notification = (ObjectNode) mapper.readTree(client.reader().readLine());
        assertEquals("runtime/status-changed", notification.path("method").asText());
        assertEquals("ready", notification.path("params").path("status").asText());
        ObjectNode nativeContext = mapper.createObjectNode();
        nativeContext.putObject("environment");
        nativeContext.putNull("shell");
        setNativeClientIdentity(nativeContext, client);
        send(client, "c:context_" + label, "runtime/context/register", nativeContext);
        assertTrue(response(client, "c:context_" + label).path("result").path("contextId")
                .asText().matches("ctx_[0-9a-f]{32}"));
        return client;
    }

    /**
     * 为 RPC 测试创建真实可观察的宿主进程，避免当前测试 JVM 的 ProcessHandle 不支持 onExit。
     * @author kongweiguang
     */
    private void attachNativeClientIdentity(Client client, String label) throws IOException {
        Process process;
        if (nativeHostFixtures.isEmpty()) {
            String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
            process = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"),
                    io.github.kongweiguang.ja.collaboration.application.service.NativeHostProcessFixture.class.getName())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            nativeHostFixtures.add(process);
        } else {
            process = nativeHostFixtures.getFirst();
            if (!process.isAlive()) {
                throw new IOException("Native host fixture exited before the client reconnected");
            }
        }
        client.nativeClientProcessId = process.pid();
        client.nativeClientLifetimeId = "native_" + java.util.UUID.nameUUIDFromBytes(
                label.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
    }

    /**
     * 重新登记 context 时沿用该连接的精确 Host identity，不把同一逻辑 Host 拆成新 epoch。
     * @author kongweiguang
     */
    private void setNativeClientIdentity(ObjectNode params, Client client) {
        params.put("clientProcessId", client.nativeClientProcessId);
        params.put("clientLifetimeId", client.nativeClientLifetimeId);
    }

    /** 健康读取证明请求 ID 回送给所属 socket，未串到另一个客户端。 */
    private void health(Client client, String suffix) throws Exception {
        String id = "c:health_" + suffix;
        send(client, id, "runtime/health", mapper.createObjectNode());
        assertEquals("ready", response(client, id).path("result").path("status").asText());
    }

    /** 只读写一条完整 JSONL 请求，避免测试夹具伪造内部代理状态。 */
    private void send(Client client, String id, String method, ObjectNode params) throws IOException {
        ObjectNode frame = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", id)
                .put("method", method);
        frame.set("params", params);
        client.writer().write(mapper.writeValueAsString(frame) + "\n");
        client.writer().flush();
    }

    /** 跳过无关通知并读取精确关联响应，防止测试依赖通知调度时机。 */
    private ObjectNode response(Client client, String id) throws Exception {
        for (int index = 0; index < 16; index++) {
            String line = client.reader().readLine();
            assertNotNull(line, "client disconnected before response");
            ObjectNode frame = (ObjectNode) mapper.readTree(line);
            if (id.equals(frame.path("id").asText())) {
                return frame;
            }
        }
        throw new AssertionError("response was not delivered to its client");
    }

    /** 真实 JDK WebSocket 客户端保留与原测试一致的顺序读取接口。 */
    private static Client open(int port) throws Exception {
        Client client = new Client();
        client.websocket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ja-rpc"), client)
                .get(2, TimeUnit.SECONDS);
        return client;
    }

    /** 队列模拟单连接顺序消费；WebSocket 的消息边界已由协议栈负责。 */
    private static final class Client implements AutoCloseable, WebSocket.Listener {
        private static final String CLOSED = "\u0000";
        private final LinkedBlockingQueue<String> incoming = new LinkedBlockingQueue<>();
        private final LinkedBlockingQueue<byte[]> pongs = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder fragments = new StringBuilder();
        private final TestReader reader = new TestReader();
        private final TestWriter writer = new TestWriter();
        private final SocketSettings socket = new SocketSettings();
        private volatile WebSocket websocket;
        private String instanceId;
        private long nativeClientProcessId;
        private String nativeClientLifetimeId;

        /** 显式请求下一条消息，避免 JDK 客户端背压影响正常用例。 */
        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }

        /** 一次完整文本才交给测试读取，分片不会被误认成多条 JA-RPC 帧。 */
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            synchronized (fragments) {
                fragments.append(data);
                if (last) {
                    incoming.offer(fragments.toString());
                    fragments.setLength(0);
                }
            }
            if (!socket.paused) { webSocket.request(1); }
            return null;
        }

        /** Pong payload 与本轮 Ping 精确关联，验证控制帧穿过分片消息但不进入 JA-RPC。 */
        @Override public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            byte[] payload = new byte[message.remaining()];
            message.get(payload);
            pongs.offer(payload);
            if (!socket.paused) { webSocket.request(1); }
            return null;
        }

        /** 关闭标记唤醒等待读者，保留与旧 EOF 断言相同语义。 */
        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            incoming.offer(CLOSED);
            closed.complete(statusCode);
            return null;
        }

        /** 测试错误也作为连接终结交给读取端。 */
        @Override public void onError(WebSocket webSocket, Throwable error) {
            incoming.offer(CLOSED);
            closed.completeExceptionally(error);
        }

        /** 关联结果只来自握手响应，不触及 daemon 内部状态。 */
        private String instanceId() { return instanceId; }
        /** 顺序读取入站 WebSocket 文本。 */
        private TestReader reader() { return reader; }
        /** 每次 flush 提交恰好一个 WebSocket 文本消息。 */
        private TestWriter writer() { return writer; }
        /** 显式指定 JDK WebSocket 的 last 标志，构造真实 TEXT/CONTINUATION 序列。 */
        private void sendFragment(String text, boolean last) {
            websocket.sendText(text, last).join();
        }
        /** 控制 Ping 在文本分片队列中按调用顺序发送。 */
        private void sendPing(byte[] payload) {
            websocket.sendPing(ByteBuffer.wrap(payload)).join();
        }
        /** 正常 Close 可插入尚未完成的数据消息，并等待远端完成关闭回执。 */
        private void sendClose() {
            websocket.sendClose(WebSocket.NORMAL_CLOSURE, "test").join();
        }
        /** 有界等待关闭回调，确认服务端完成当前连接的资源释放。 */
        private int awaitClose() throws Exception { return closed.get(5, TimeUnit.SECONDS); }
        /** 有界等待与当前控制帧匹配的 Pong，不读或打印任何业务正文。 */
        private byte[] awaitPong() throws Exception {
            byte[] payload = pongs.poll(5, TimeUnit.SECONDS);
            assertNotNull(payload, "peer did not return the Ping payload");
            return payload;
        }
        /** 关闭客户端会话不得关闭共享业务 owner。 */
        @Override public void close() { websocket.abort(); }

        /** 保留按行读取的测试接口，底层只消费完整 WebSocket 文本帧。 */
        private final class TestReader {
            /** 允许测试设置超时，阻塞读取不占用服务端线程。 */
            private String readLine() throws IOException {
                try {
                    String value = incoming.poll(socket.timeoutMillis, TimeUnit.MILLISECONDS);
                    if (value == null) { throw new SocketTimeoutException("WebSocket receive timed out"); }
                    return CLOSED.equals(value) ? null : value;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("WebSocket receive interrupted", interrupted);
                }
            }
        }

        /** 保留测试调用点的写入顺序，每次 flush 对应一个 WebSocket 消息。 */
        private final class TestWriter {
            private String value;
            /** 测试用原有调用点传入一帧 JSONL，发送时去掉传输外层换行。 */
            private void write(String text) { value = text.stripTrailing(); }
            /** WebSocket 文本消息是独立帧，不包含 JSONL 分隔字节。 */
            private void flush() { websocket.sendText(value, true).join(); }
        }

        /** 将旧测试的接收控制映射为 JDK WebSocket demand，不修改服务端配置。 */
        private final class SocketSettings {
            private int timeoutMillis = 2_000;
            private volatile boolean paused;
            /** 接收超时只约束测试等待读取，不改变服务端会话。 */
            private void setSoTimeout(int timeout) {
                timeoutMillis = timeout;
                if (paused) {
                    paused = false;
                    websocket.request(1);
                }
            }
            /** 暂停 JDK 消息 demand，使慢读者不再持续排空服务器输出。 */
            private void setReceiveBufferSize(int ignored) { paused = true; }
        }
    }
}
