// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;
import io.github.kongweiguang.ja.task.port.in.TaskUseCase;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.port.in.GoalUseCase;
import io.github.kongweiguang.ja.goal.port.in.GoalEvent;
import io.github.kongweiguang.ja.transport.rpc.handler.HandshakeHandler;
import io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings;
import io.github.kongweiguang.ja.transport.rpc.support.TestConfigurationPorts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedOutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用隔离目录和真实 TCP socket 验证一个业务 owner 对多个前端连接的寿命边界。 */
final class TcpDaemonTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 错误认证不进入 JA-RPC；A 断开后 B 保持可用，C 可重连同一实例，端点权限
     * 始终只允许当前 owner，且关闭后不残留可误连的端点。
     */
    @Test
    void authenticatedClientsAreIndependentAndEndpointIsPrivate() throws Exception {
        SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"),
                root.resolve("data"), root.resolve("run"), root.resolve("logs"), 41L);
        TcpDaemon daemon = new TcpDaemon(configuration,
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
            try (Socket rejected = new Socket("127.0.0.1", port)) {
                rejected.setSoTimeout(2_000);
                rejected.getOutputStream().write("{\"token\":\"wrong\"}\n".getBytes(StandardCharsets.UTF_8));
                assertEquals(-1, rejected.getInputStream().read());
            }
            try (Client first = connect(endpoint, "one"); Client second = connect(endpoint, "two")) {
                health(first, "one");
                health(second, "two");
                first.close();
                health(second, "two-after-first-exit");
                ObjectNode context = mapper.createObjectNode();
                context.putObject("environment").put("JA_TCP_TEST", "isolated");
                context.putNull("shell");
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
     * Thread 权威基线，unobserve 仅停止当前连接的后续投影。
     */
    @Test
    void threadObservationFiltersEventsAndReadRemainsAvailableAfterReconnect() throws Exception {
        SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"),
                root.resolve("data"), root.resolve("run"), root.resolve("logs"), 42L);
        ThreadSnapshot snapshot = emptyThreadSnapshot();
        ThreadUseCase threads = (ThreadUseCase) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ThreadUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "readThread", "readThreadLatest" -> Optional.of(snapshot);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        TaskUseCase tasks = (TaskUseCase) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{TaskUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "subscribe" -> (AutoCloseable) () -> { };
                    case "listRootActivities" -> List.of();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        GoalModels.GoalSnapshot goal = goalSnapshot();
        GoalUseCase goals = (GoalUseCase) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{GoalUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "subscribe", "subscribePlan" -> (AutoCloseable) () -> { };
                    case "read" -> goal;
                    case "listTerminalActivities" -> List.of();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AtomicReference<RpcSession> owner = new AtomicReference<>();
        TcpDaemon daemon = new TcpDaemon(configuration, ignored -> {
            RpcServiceBindings base = RpcTestBindings.create(null, threads, null, null, null, () -> { });
            return new RpcServiceBindings(base.workspaces(), base.workspacePathSearch(), base.threads(),
                    base.threadMcp(), base.turns(), base.compactions(), base.approvals(), base.catalog(),
                    base.attachments(), base.attachmentPreviews(), tasks, goals,
                    base.interactions(), base.lifecycle());
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
                    send(observed, "c:observe-goal", "goal/observe",
                            mapper.createObjectNode().put("goalId", "goal_test"));
                    ObjectNode goalObservation = response(observed, "c:observe-goal").withObject("result");
                    owner.get().publish(new GoalEvent(goal, new GoalModels.PublicEvent(1, 1,
                            "step_changed", "step", Instant.parse("2026-09-24T00:00:00Z")))).join();
                    ObjectNode activity = (ObjectNode) mapper.readTree(observed.reader().readLine());
                    assertEquals("goal/activity", activity.path("method").asText());
                    peer.socket.setSoTimeout(200);
                    org.junit.jupiter.api.Assertions.assertThrows(SocketTimeoutException.class,
                            () -> peer.reader().readLine());
                    peer.socket.setSoTimeout(2_000);
                    send(peer, "c:observe-peer-goal", "goal/observe",
                            mapper.createObjectNode().put("goalId", "goal_test"));
                    response(peer, "c:observe-peer-goal");
                    peer.close();
                    owner.get().publish(new GoalEvent(goal, new GoalModels.PublicEvent(2, 1,
                            "step_changed", "step", Instant.parse("2026-09-24T00:00:01Z")))).join();
                    ObjectNode afterPeerExit = (ObjectNode) mapper.readTree(observed.reader().readLine());
                    assertEquals("goal/activity", afterPeerExit.path("method").asText());
                    send(observed, "c:unobserve-goal", "goal/unobserve", mapper.createObjectNode()
                            .put("observationId", goalObservation.path("observationId").asText()));
                    response(observed, "c:unobserve-goal");
                    owner.get().publish(new GoalEvent(goal, new GoalModels.PublicEvent(3, 1,
                            "step_changed", "step", Instant.parse("2026-09-24T00:00:02Z")))).join();
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
                    Thread.ofVirtual().name("ja-fast-observer").start(() -> {
                        try {
                            int deltas = 0;
                            while (true) {
                                String line = observed.reader().readLine();
                                if (line == null) throw new IOException("fast observer disconnected");
                                if (line.contains("\"method\":\"assistant/text-delta\"")) deltas++;
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
                    for (int sequence = 1; sequence <= 600; sequence++) {
                        owner.get().publish(new TurnEvent.TextDelta("turn_flood", sequence, payload)).join();
                    }
                    send(observed, "c:health-after-flood", "runtime/health", mapper.createObjectNode());
                    assertTrue(fastHealth.get(10, TimeUnit.SECONDS) > 0,
                            "fast observer must still receive data while a peer is stalled");
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
            try (Client reconnected = connect(endpoint, "reconnected")) {
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
     * 普通 stop 在仍有准入 Turn 时返回稳定冲突并保持可连接；显式 force 才能
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
                    case "stopAccepting" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ThreadUseCase threads = (ThreadUseCase) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ThreadUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "discoverThreads" -> new CursorPage<ThreadDiscovery>(List.of(), null);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        TcpDaemon daemon = new TcpDaemon(configuration,
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
     * 无客户端的定时退出先经过业务保活检查；活跃 Turn 被拒绝后必须重新计时，
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
                    case "stopAccepting" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ThreadUseCase threads = (ThreadUseCase) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ThreadUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "discoverThreads" -> new CursorPage<ThreadDiscovery>(List.of(), null);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        TcpDaemon daemon = new TcpDaemon(configuration,
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
        TcpDaemon daemon = new TcpDaemon(configuration,
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
        TcpDaemon daemon = new TcpDaemon(configuration,
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
            org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                    () -> {
                        try (Socket ignored = new Socket("127.0.0.1", descriptor.path("port").intValue())) {
                            // 故障后的端点必须已不可连接，try-with-resources 只覆盖意外成功路径。
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
    private static void writeMalformedInnerFrame(TcpDaemon daemon) throws Exception {
        var field = TcpDaemon.class.getDeclaredField("runtimeIngress");
        field.setAccessible(true);
        PipedOutputStream ingress = (PipedOutputStream) field.get(daemon);
        ingress.write("{invalid}\n".getBytes(StandardCharsets.UTF_8));
        ingress.flush();
    }

    /** 输出损坏使真实 Codec 失败；不依赖 JDK Pipe.close 对阻塞 read 的唤醒行为。 */
    private static void writeMalformedOutputFrame(TcpDaemon daemon) throws Exception {
        var field = TcpDaemon.class.getDeclaredField("runtimeEgress");
        field.setAccessible(true);
        PipedOutputStream egress = (PipedOutputStream) field.get(daemon);
        egress.write("{invalid}\n".getBytes(StandardCharsets.UTF_8));
        egress.flush();
    }

    /** 构造只有公开元数据的权威页面，使观察测试不依赖数据库或付费 Provider。 */
    private static ThreadSnapshot emptyThreadSnapshot() {
        ThreadPreferences preferences = new ThreadPreferences("provider_test", "model_test", null,
                AccessMode.FULL_ACCESS, CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.MANUAL);
        ThreadSummary thread = new ThreadSummary("thr_test", "ws_test", "test", "project", null,
                preferences, ThreadSummary.Status.ACTIVE, false, null, true, null, 1,
                Instant.parse("2026-09-24T00:00:00Z"), Instant.parse("2026-09-24T00:00:00Z"));
        return new ThreadSnapshot(thread, List.of(), List.of(), null, null, null);
    }

    /** 高频 Goal 测试只需一份可读的安全聚合摘要，执行仍由测试直接发布内存事件。 */
    private static GoalModels.GoalSnapshot goalSnapshot() {
        Instant now = Instant.parse("2026-09-24T00:00:00Z");
        GoalModels.Goal goal = new GoalModels.Goal("goal_test", "thr_test",
                GoalModels.OwnerKind.ROOT_THREAD, "test goal", 1, GoalModels.GoalStatus.ACTIVE,
                GoalModels.GoalPhase.WORKING, 0, null, 0, 0, null, false, now, now);
        GoalModels.GoalDefinition definition = new GoalModels.GoalDefinition(
                goal.goalId(), 1, goal.objective(), List.of(), now);
        return new GoalModels.GoalSnapshot(goal, definition, null, null, 0, 0,
                null, null, null, null, 1);
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
            if (Files.exists(endpoint)) return (ObjectNode) mapper.readTree(Files.readAllBytes(endpoint));
            Thread.sleep(10);
        }
        throw new AssertionError("isolated TCP endpoint was not published");
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

    /** 客户端严格走 token 前帧、initialize 和 ready 挑战，以覆盖真实接入顺序。 */
    private Client connect(ObjectNode endpoint, String label) throws Exception {
        Socket socket = new Socket("127.0.0.1", endpoint.path("port").intValue());
        socket.setSoTimeout(2_000);
        Client client = new Client(socket);
        client.writer().write(mapper.writeValueAsString(mapper.createObjectNode()
                .put("token", endpoint.path("token").asText())) + "\n");
        client.writer().flush();
        ObjectNode params = mapper.createObjectNode().put("protocolMajor", 1).put("protocolMinor", 0)
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
        send(client, "c:context_" + label, "runtime/context/register", nativeContext);
        assertTrue(response(client, "c:context_" + label).path("result").path("contextId")
                .asText().matches("ctx_[0-9a-f]{32}"));
        return client;
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
            if (id.equals(frame.path("id").asText())) return frame;
        }
        throw new AssertionError("response was not delivered to its client");
    }

    /** Socket、reader 与 writer 作为单一测试连接关闭，服务端不得据此关闭共享 owner。 */
    private static final class Client implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader reader;
        private final BufferedWriter writer;
        private String instanceId;

        /** 测试只建立有超时的本地 socket，异常时由 AutoCloseable 回收。 */
        private Client(Socket socket) throws IOException {
            this.socket = socket;
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        /** 暴露纯测试的关联结果，不触及内部 daemon 字段。 */
        private String instanceId() { return instanceId; }
        /** reader 独占入站 socket，避免两个等待者互相消费响应。 */
        private BufferedReader reader() { return reader; }
        /** writer 独占出站 socket，测试不会发送半帧。 */
        private BufferedWriter writer() { return writer; }
        /** 故意只关闭 socket，验证服务端把断线视为连接生命周期事件。 */
        @Override public void close() throws IOException { socket.close(); }
    }
}
