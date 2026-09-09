// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.transport.rpc.runtime;

import io.github.kongweiguang.ja.transport.rpc.handler.HandshakeContractTestAccess;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings;
import io.github.kongweiguang.ja.transport.rpc.support.TestConfigurationPorts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationScope;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.foundation.concurrent.ShutdownDeadline;
import io.github.kongweiguang.ja.foundation.concurrent.BoundedVirtualExecutor;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.port.in.GoalEvent;
import io.github.kongweiguang.ja.goal.port.in.GoalEventSink;
import io.github.kongweiguang.ja.goal.port.in.GoalUseCase;
import io.github.kongweiguang.ja.task.port.in.TaskUseCase;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通过真实且有界的 RpcServer 传输验证严格握手、配置脱敏与关闭边界。 */
final class RpcServerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-25T12:00:00Z"), ZoneOffset.UTC);

    /** 提供 JA-RPC v1 Sidecar 边界要求的四个明确根目录，避免测试依赖用户目录。 */
    private static SidecarConfiguration testConfiguration() {
        java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "ja-rpc-server-test")
                .toAbsolutePath().normalize();
        return new SidecarConfiguration(root.resolve("home"), root.resolve("data"),
                root.resolve("run"), root.resolve("logs"));
    }

    /** 验证 initialize、ready 与 EOF 关闭只写出公开帧，任何凭据均不会回显。 */
    @Test
    void handshakePublishesIdentityAndRedactsConfig() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode initialize = initialize(mapper);
        String input = mapper.writeValueAsString(initialize) + "\n"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"runtime/initialized\",\"params\":"
                + "{\"readyToken\":\"0123456789abcdef0123456789abcdef\"}}\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicBoolean closed = new AtomicBoolean();
        RpcServer server = new RpcServer(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                output, testConfiguration(),
                CLOCK, ignored -> new EmptyServices(closed).bindings(),
                TestConfigurationPorts.unavailable());
        java.lang.reflect.Field requests = RpcServer.class.getDeclaredField("requests");
        requests.setAccessible(true);
        BoundedVirtualExecutor executor = (BoundedVirtualExecutor) requests.get(server);
        assertTrue(executor.submit(() -> Thread.currentThread().isVirtual()).get(1, TimeUnit.SECONDS));
        assertEquals(0, server.run());
        String wire = output.toString(StandardCharsets.UTF_8);
        assertTrue(wire.contains("\"engine\":\"ja-kernel\""));
        assertTrue(wire.contains("\"engineVersion\":\"0.1.0\""));
        assertTrue(wire.contains("\"status\":\"ready\""));
        assertTrue(wire.contains("\"status\":\"stopped\""));
        assertTrue(wire.contains("\"features\":[\"task_threads_v1\",\"plan_goal_v1\"]"));
        assertFalse(wire.contains("provider-secret"));
        assertTrue(closed.get());
    }

    /**
     * Goal 创建发生前 UI 不可能持有 observation；created 必须作为唯一低频发现事件送达，
     * 后续未观察变更仍保持静默，避免关闭 Workbench 后产生无界状态流。
     */
    @Test
    void publishesGoalCreationBeforeObservationAndFiltersLaterChanges() {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<GoalEventSink> subscribed = new AtomicReference<>();
        GoalModels.GoalSnapshot snapshot = goalDiscoverySnapshot();
        GoalUseCase goalOwner = (GoalUseCase) Proxy.newProxyInstance(
                RpcServerTest.class.getClassLoader(), new Class<?>[]{GoalUseCase.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "subscribe" -> {
                        subscribed.set((GoalEventSink) arguments[0]);
                        yield (AutoCloseable) () -> subscribed.set(null);
                    }
                    case "read" -> snapshot;
                    default -> throw new UnsupportedOperationException("unexpected Goal call: " + method.getName());
                });
        RpcServiceBindings base = RpcTestBindings.create(null, null, null, null, null, () -> { });
        RpcServiceBindings bindings = new RpcServiceBindings(base.workspaces(), base.workspacePathSearch(),
                base.threads(), base.turns(), base.compactions(), base.approvals(), base.catalog(),
                base.attachments(), base.attachmentPreviews(), base.tasks(), goalOwner, base.lifecycle());

        try (StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024);
             RpcSession session = new RpcSession(testConfiguration(), mapper, CLOCK, writer,
                     ignored -> bindings, TestConfigurationPorts.unavailable(), 73)) {
            session.initialize();
            session.ready("0123456789abcdef0123456789abcdef");
            GoalEventSink sink = subscribed.get();
            sink.publish(new GoalEvent(snapshot, new GoalModels.PublicEvent(
                    1, 0, "created", "Goal 已创建", CLOCK.instant()))).toCompletableFuture().join();
            String afterCreation = output.toString(StandardCharsets.UTF_8);
            assertTrue(afterCreation.contains("\"method\":\"goal/changed\""));
            assertTrue(afterCreation.contains("\"goalId\":\"goal_discovery\""));

            sink.publish(new GoalEvent(snapshot, new GoalModels.PublicEvent(
                    2, 0, "plan_draft_saved", "计划草稿已保存", CLOCK.instant())))
                    .toCompletableFuture().join();
            assertEquals(afterCreation, output.toString(StandardCharsets.UTF_8));

            RpcSession.GoalObservation observation = session.observeGoal("goal_discovery");
            assertTrue(sink.registerTurn("goal_discovery", "turn_goal", "ws_goal", "thr_discovery", 0));
            sink.publishTurn("goal_discovery", new TurnEvent.TextDelta("turn_goal", 1, "等待批准"))
                    .toCompletableFuture().join();
            sink.publishTurn("goal_discovery", new TurnEvent.ApprovalRequested(
                    new TurnEvent.Context("evt_goal_approval", "thr_discovery", "turn_goal", 1,
                            CLOCK.instant()), "appr_goal", "call_goal", "shell", "需要批准",
                    CLOCK.instant().plusSeconds(60))).toCompletableFuture().join();
            String whileObserved = output.toString(StandardCharsets.UTF_8);
            assertTrue(whileObserved.contains("\"method\":\"assistant/text-delta\""));
            assertTrue(whileObserved.contains("\"method\":\"approval/requested\""));
            assertTrue(whileObserved.contains("\"turnId\":\"turn_goal\""));

            session.unobserveGoal(observation.observationId());
            sink.publishTurn("goal_discovery", new TurnEvent.TextDelta("turn_goal", 2, "不得投影"))
                    .toCompletableFuture().join();
            assertEquals(whileObserved, output.toString(StandardCharsets.UTF_8));
        }
    }

    /**
     * 连接关闭必须保留最早的取消失败，并按实际清理顺序挂载 observation、订阅和生命周期失败；
     * 后续资源仍全部尝试关闭，避免一个诊断异常截断真实资源回收。
     */
    @Test
    void closePreservesFirstFailureAndSuppressesLaterCleanupFailures() {
        ObjectMapper mapper = new ObjectMapper();
        List<String> order = new java.util.ArrayList<>();
        IllegalStateException cancellationFailure = new IllegalStateException("cancellation failed");
        IllegalStateException observationFailure = new IllegalStateException("observation failed");
        IllegalStateException subscriptionFailure = new IllegalStateException("subscription failed");
        IllegalStateException lifecycleFailure = new IllegalStateException("lifecycle failed");
        TaskUseCase taskOwner = (TaskUseCase) Proxy.newProxyInstance(RpcServerTest.class.getClassLoader(),
                new Class<?>[]{TaskUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "subscribe" -> (AutoCloseable) () -> {
                        order.add("subscription");
                        throw subscriptionFailure;
                    };
                    case "observe" -> new TaskUseCase.Observation("observe_close", "thr_task_close", 1);
                    case "unobserve" -> {
                        order.add("unobserve");
                        throw observationFailure;
                    }
                    default -> throw new AssertionError("unexpected TaskUseCase call: " + method.getName());
                });
        DeadlineCloseable lifecycle = new DeadlineCloseable() {
            /** 关闭组合根时记录顺序并制造最终失败。 */
            @Override public void closeAt(long shutdownDeadlineNanos) {
                order.add("lifecycle");
                throw lifecycleFailure;
            }

            /** 测试只允许生产路径传递绝对 Deadline。 */
            @Override public void close() {
                throw new AssertionError("deadline-aware close is required");
            }
        };
        RpcServiceBindings base = RpcTestBindings.create(null, null, null, null, null, () -> { });
        RpcServiceBindings bindings = new RpcServiceBindings(base.workspaces(), base.workspacePathSearch(),
                base.threads(), base.turns(), base.compactions(), base.approvals(), base.catalog(),
                base.attachments(), base.attachmentPreviews(), taskOwner, base.goals(), lifecycle);
        try (StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), mapper, 4 * 1024 * 1024)) {
            RpcSession session = new RpcSession(testConfiguration(), mapper, CLOCK, writer,
                    ignored -> bindings, TestConfigurationPorts.unavailable(), 71);
            session.initialize();
            session.ready("0123456789abcdef0123456789abcdef");
            session.observeTask("thr_task_close", 1);
            session.cancellationToken().onCancellation(() -> {
                order.add("cancellation");
                throw cancellationFailure;
            });

            RuntimeException actual = assertThrows(RuntimeException.class,
                    () -> session.close(ShutdownDeadline.start(Duration.ofSeconds(2))));

            assertSame(cancellationFailure, actual);
            assertEquals(List.of("cancellation", "unobserve", "subscription", "lifecycle"), order);
            assertEquals(3, actual.getSuppressed().length);
            assertSame(observationFailure, actual.getSuppressed()[0]);
            assertSame(subscriptionFailure, actual.getSuppressed()[1].getCause());
            assertSame(lifecycleFailure, actual.getSuppressed()[2]);
        }
    }

    /** 验证任意入站响应信封都会在进入应用状态前被拒绝。 */
    @Test
    void rejectsInboundResponseEnvelopes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String id : List.of("c:client-response", "x:foreign-response")) {
            String frame = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", id)
                    .set("result", mapper.createObjectNode().put("accepted", true))
                    .toString();
            AtomicBoolean opened = new AtomicBoolean();
            RpcServer server = new RpcServer(new ByteArrayInputStream(
                    (frame + "\n").getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream(),
                    testConfiguration(), CLOCK, ignored -> {
                        opened.set(true);
                        return new EmptyServices(new AtomicBoolean()).bindings();
                    }, TestConfigurationPorts.unavailable());
            assertEquals(1, server.run(), "response must fail closed: " + id);
            assertFalse(opened.get(), "response must not open application services: " + id);
        }
    }

    /**
     * 验证忽略中断的 Handler 会先触发进程边界强制终止，而不会继续关闭 Session 或 Runtime。
     * 输入闸门保持读取线程存活，使测试覆盖生产中的同一关闭所有者，避免 EOF 触发第二次关闭造成竞态。
     */
    @Test
    void unquiescedHandlerForcesTerminationWithoutRuntimeCloseOrStopped() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode initialize = initialize(mapper);
        String input = mapper.writeValueAsString(initialize) + "\n"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"runtime/initialized\",\"params\":"
                + "{\"readyToken\":\"0123456789abcdef0123456789abcdef\"}}\n";
        ObjectNode list = mapper.createObjectNode().put("jsonrpc", "2.0")
                .put("id", "c:blocking-list").put("method", "workspace/list");
        list.set("params", mapper.createObjectNode());
        BlockingInputStream inputStream = new BlockingInputStream(
                (input + mapper.writeValueAsString(list) + "\n").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BlockingServices services = new BlockingServices();
        RpcServer server = new RpcServer(inputStream, output,
                testConfiguration(), CLOCK,
                ignored -> services.bindings(),
                TestConfigurationPorts.unavailable());
        Thread reader = Thread.ofVirtual().start(server::run);
        try {
            assertTrue(services.handlerEntered.await(2, TimeUnit.SECONDS), "handler did not start");
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> server.close(ShutdownDeadline.start(Duration.ofMillis(150))));
            assertTrue(failure instanceof ShutdownDeadline.ForcedTerminationException);
            assertEquals(0, services.closeCount.get(), "forced ingress must retain runtime ownership");
            assertFalse(output.toString(StandardCharsets.UTF_8).contains("\"status\":\"stopped\""));
        } finally {
            services.release();
            inputStream.release();
            reader.join(2_000);
        }
        assertFalse(reader.isAlive(), "reader must return after forced handoff");
    }

    /** 验证重复关闭会复用首次强制终止结果，而不会重新启动一段关闭预算。 */
    @Test
    void repeatedCloseReplaysForcedResultWithinOneCompletion() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode initialize = initialize(mapper);
        ObjectNode list = mapper.createObjectNode().put("jsonrpc", "2.0")
                .put("id", "c:repeat-close").put("method", "workspace/list");
        list.set("params", mapper.createObjectNode());
        BlockingInputStream inputStream = new BlockingInputStream(
                (mapper.writeValueAsString(initialize) + "\n"
                        + "{\"jsonrpc\":\"2.0\",\"method\":\"runtime/initialized\",\"params\":"
                        + "{\"readyToken\":\"0123456789abcdef0123456789abcdef\"}}\n"
                        + mapper.writeValueAsString(list) + "\n").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BlockingServices services = new BlockingServices();
        RpcServer server = new RpcServer(inputStream, output,
                testConfiguration(), CLOCK,
                ignored -> services.bindings(),
                TestConfigurationPorts.unavailable());
        Thread reader = Thread.ofVirtual().start(server::run);
        try {
            assertTrue(services.handlerEntered.await(2, TimeUnit.SECONDS), "handler did not start");
            RuntimeException first = assertThrows(RuntimeException.class,
                    () -> server.close(ShutdownDeadline.start(Duration.ofMillis(150))));
            long started = System.nanoTime();
            RuntimeException repeated = assertThrows(RuntimeException.class,
                    () -> server.close(ShutdownDeadline.start(Duration.ofSeconds(2))));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(first instanceof ShutdownDeadline.ForcedTerminationException);
            assertSame(first, repeated);
            assertTrue(elapsedMillis < 500, "repeated close waited for a new shutdown budget");
            assertEquals(0, services.closeCount.get(), "forced close must retain runtime ownership");
        } finally {
            services.release();
            inputStream.release();
            reader.join(2_000);
        }
        assertFalse(reader.isAlive(), "reader must return after forced handoff");
    }

    /**
     * 验证关闭请求必须等待 Codec 已返回的帧越过分发闸门后才能停止执行器，
     * 防止该帧在正常关闭完成后继续访问已经释放的 Runtime 对象图。
     */
    @Test
    void readDispatchGatePreventsLateHandlerAndResourceUse() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode initialize = initialize(mapper);
        String input = mapper.writeValueAsString(initialize) + "\n"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"runtime/initialized\",\"params\":"
                + "{\"readyToken\":\"0123456789abcdef0123456789abcdef\"}}\n";
        ObjectNode list = mapper.createObjectNode().put("jsonrpc", "2.0")
                .put("id", "c:gate-list").put("method", "workspace/list");
        list.set("params", mapper.createObjectNode());
        ByteArrayInputStream inputStream = new ByteArrayInputStream(
                (input + mapper.writeValueAsString(list) + "\n").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BlockingServices services = new BlockingServices(false);
        CountDownLatch frameRead = new CountDownLatch(1);
        CountDownLatch allowDispatch = new CountDownLatch(1);
        AtomicInteger frameCount = new AtomicInteger();
        RpcServer server = new RpcServer(inputStream, output,
                testConfiguration(), CLOCK,
                ignored -> services.bindings(),
                TestConfigurationPorts.unavailable(), () -> {
                    if (frameCount.incrementAndGet() == 3) {
                        frameRead.countDown();
                        awaitLatch(allowDispatch);
                    }
                });
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        CountDownLatch closeCall = new CountDownLatch(1);
        CountDownLatch closeDone = new CountDownLatch(1);
        Thread reader = Thread.ofVirtual().start(server::run);
        Thread closer = null;
        try {
            assertTrue(frameRead.await(1, TimeUnit.SECONDS),
                    () -> "frame did not reach read boundary count=" + frameCount.get()
                            + " output=" + output.toString(StandardCharsets.UTF_8));
            closer = Thread.ofVirtual().start(() -> {
                closeCall.countDown();
                try {
                    server.close(ShutdownDeadline.start(Duration.ofSeconds(2)));
                } catch (Throwable failure) {
                    closeFailure.set(failure);
                } finally {
                    closeDone.countDown();
                }
            });
            assertTrue(closeCall.await(1, TimeUnit.SECONDS));
            assertFalse(services.handlerEntered.await(100, TimeUnit.MILLISECONDS));
            assertEquals(0, services.closeCount.get());
            allowDispatch.countDown();
            assertTrue(services.handlerEntered.await(1, TimeUnit.SECONDS),
                    "frame was not dispatched after the gate opened");
            assertTrue(closeDone.await(2, TimeUnit.SECONDS));
            assertTrue(closeFailure.get() == null, () -> "unexpected close failure: " + closeFailure.get());
            assertEquals(1, services.closeCount.get());
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"status\":\"stopped\""));
        } finally {
            allowDispatch.countDown();
            reader.join(2_000);
            if (closer != null) closer.join(2_000);
        }
        assertFalse(reader.isAlive(), "reader must finish after the gated dispatch");
        if (closer != null) assertFalse(closer.isAlive(), "close owner must finish after the gated dispatch");
    }

    /** 验证限制参数不匹配时会在持有敏感信息的应用工厂打开前失败。 */
    @Test
    void rejectsLimitMismatchBeforeFactoryOpen() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode initialize = initialize(mapper);
        ((ObjectNode) ((ObjectNode) initialize.get("params")).get("limits"))
                .put("maxFrameBytes", 4 * 1024 * 1024 + 1);
        AtomicInteger opened = new AtomicInteger();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RpcServer server = new RpcServer(new ByteArrayInputStream(
                (mapper.writeValueAsString(initialize) + "\n").getBytes(StandardCharsets.UTF_8)), output,
                testConfiguration(), CLOCK,
                ignored -> {
                    opened.incrementAndGet();
                    return new EmptyServices(new AtomicBoolean()).bindings();
                }, TestConfigurationPorts.unavailable());
        assertEquals(0, server.run());
        assertEquals(0, opened.get());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"errorCode\":\"INVALID_PARAMS\""));
    }

    /** 旧协议不得触发应用对象图创建，确保首版进程不会隐式承担历史升级责任。 */
    @Test
    void rejectsLegacyProtocolBeforeFactoryOpen() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode initialize = initialize(mapper);
        ObjectNode params = (ObjectNode) initialize.path("params");
        params.put("protocolMajor", 2).put("protocolMinor", 1);
        AtomicInteger opened = new AtomicInteger();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RpcServer server = new RpcServer(new ByteArrayInputStream(
                (mapper.writeValueAsString(initialize) + "\n").getBytes(StandardCharsets.UTF_8)), output,
                testConfiguration(), CLOCK,
                ignored -> {
                    opened.incrementAndGet();
                    return new EmptyServices(new AtomicBoolean()).bindings();
                }, TestConfigurationPorts.unavailable());

        assertEquals(0, server.run());
        assertEquals(0, opened.get());
        assertTrue(output.toString(StandardCharsets.UTF_8)
                .contains("\"errorCode\":\"PROTOCOL_VERSION_UNSUPPORTED\""));
    }

    /** 验证配置损坏只会降低健康状态，同时保留 initialize 与 Settings 边界的可用性。 */
    @Test
    void exposesRedactedConfigurationDegradedHealth() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode initialize = initialize(mapper);
        ObjectNode health = mapper.createObjectNode().put("jsonrpc", "2.0")
                .put("id", "c:health").put("method", "runtime/health");
        health.set("params", mapper.createObjectNode());
        String input = mapper.writeValueAsString(initialize) + "\n"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"runtime/initialized\",\"params\":{\"readyToken\":\"0123456789abcdef0123456789abcdef\"}}\n"
                + mapper.writeValueAsString(health) + "\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RpcServer server = new RpcServer(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output,
                testConfiguration(), CLOCK,
                ignored ->
                        new EmptyServices(new AtomicBoolean()).configurationBindings(),
                new DegradedConfiguration());
        assertEquals(0, server.run());
        String wire = output.toString(StandardCharsets.UTF_8);
        assertTrue(wire.contains("\"name\":\"configuration\",\"status\":\"degraded\""));
        assertTrue(wire.contains("CORRUPT_CONFIG"));
        assertFalse(wire.contains("C:/private/secret.toml"));
    }

    /** 验证真实 stdio 帧保持冻结的配置和凭据结果结构，并对 Secret 完成脱敏。 */
    @Test
    void servesStrictConfigurationAndCredentialFrames() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode initialize = initialize(mapper);
        String ready = "{\"jsonrpc\":\"2.0\",\"method\":\"runtime/initialized\",\"params\":{\"readyToken\":\"0123456789abcdef0123456789abcdef\"}}";
        ObjectNode read = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", "c:config-read")
                .put("method", "configuration/read");
        read.set("params", mapper.createObjectNode());
        ObjectNode write = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", "c:config-write")
                .put("method", "configuration/patch");
        write.set("params", mapper.createObjectNode().put("scope", "user")
                .put("expectedVersion", "cfg_missing")
                .set("patch", mapper.createObjectNode().put("unexpected", true)));
        ObjectNode credential = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", "c:credential")
                .put("method", "credential/set");
        credential.set("params", mapper.createObjectNode().put("credentialId", "cred_test")
                .put("secret", "wire-secret-must-not-echo").put("expectedVersion", "cfg_missing"));
        String input = mapper.writeValueAsString(initialize) + "\n" + ready + "\n"
                + mapper.writeValueAsString(read) + "\n" + mapper.writeValueAsString(write) + "\n"
                + mapper.writeValueAsString(credential) + "\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RpcServer server = new RpcServer(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output,
                testConfiguration(), CLOCK,
                ignored ->
                        new EmptyServices(new AtomicBoolean()).configurationBindings(),
                new WireConfiguration());
        assertEquals(0, server.run());
        String wire = output.toString(StandardCharsets.UTF_8);
        assertTrue(wire.contains("\"credentialId\":\"cred_test\""));
        assertTrue(wire.contains("\"method\":\"configuration/changed\""));
        assertFalse(wire.contains("wire-secret-must-not-echo"));

        List<ObjectNode> frames = wire.lines().map(line -> {
            try {
                return (ObjectNode) mapper.readTree(line);
            } catch (Exception failure) {
                throw new AssertionError("runtime emitted invalid JSON", failure);
            }
        }).toList();
        ObjectNode configResult = responseResult(frames, "c:config-read");
        assertExactFields(configResult, "workspaceId", "effective", "user", "project", "credentials",
                "cas", "diagnostics", "trusted");
        assertTrue(configResult.get("workspaceId").isNull());
        ObjectNode cas = (ObjectNode) configResult.path("cas");
        assertExactFields(cas, "userVersion", "projectVersion", "credentialVersion");
        assertEquals("cfg_missing", cas.path("userVersion").textValue());
        assertEquals("cfg_missing", cas.path("projectVersion").textValue());
        assertEquals("cfg_missing", cas.path("credentialVersion").textValue());
        ObjectNode credentialResult = responseResult(frames, "c:credential");
        assertExactFields(credentialResult, "accepted", "credentialId", "configured", "version");
        assertFalse(credentialResult.has("secret"));
        frames.stream().filter(frame -> "configuration/changed".equals(frame.path("method").textValue()))
                .forEach(frame -> assertFalse(frame.path("params").has("path")));
    }

    /** 验证存储 CAS 与 I/O 失败映射到稳定错误目录，且不泄露持久化实现细节。 */
    @Test
    void mapsPersistenceFailuresToStableCatalog() {
        assertEquals("CONFLICT", RpcServer.mapPersistenceFailure(StorageException.Code.CAS_CONFLICT).errorCode());
        assertEquals("STORAGE_UNAVAILABLE",
                RpcServer.mapPersistenceFailure(StorageException.Code.IO).errorCode());
    }

    /** 使用生产能力和限制构造唯一首版 initialize 请求，避免测试复制其余合同常量。 */
    private static ObjectNode initialize(ObjectMapper mapper) {
        ObjectNode params = mapper.createObjectNode().put("protocolMajor", 1).put("protocolMinor", 0)
                .put("clientVersion", "0.1.0");
        params.set("capabilities", HandshakeContractTestAccess.capabilities(mapper));
        params.set("limits", HandshakeContractTestAccess.limits(mapper));
        ObjectNode request = mapper.createObjectNode().put("jsonrpc", "2.0")
                .put("id", "c:init").put("method", "runtime/initialize");
        request.set("params", params);
        return request;
    }

    /** 创建最小可执行 Goal 快照，确保 transport 测试不依赖 Plan 聚合或持久层 fixture。 */
    private static GoalModels.GoalSnapshot goalDiscoverySnapshot() {
        GoalModels.Goal goal = new GoalModels.Goal("goal_discovery", "thr_discovery",
                GoalModels.OwnerKind.ROOT_THREAD, "验证 Goal 发现事件", 1, GoalModels.GoalStatus.ACTIVE,
                GoalModels.GoalPhase.WORKING, 0, null, 0, 0, null, false,
                CLOCK.instant(), CLOCK.instant());
        GoalModels.GoalDefinition definition = new GoalModels.GoalDefinition(
                goal.goalId(), 1, goal.objective(), List.of(), CLOCK.instant());
        return new GoalModels.GoalSnapshot(goal, definition, null, null, 0, 0,
                null, null, null, null, null, 1);
    }

    /** 按 ID 读取唯一响应结果，避免断言把输入请求误认为标准输出帧。 */
    private static ObjectNode responseResult(List<ObjectNode> frames, String id) {
        ObjectNode response = frames.stream()
                .filter(frame -> id.equals(frame.path("id").textValue()))
                .findFirst().orElseThrow(() -> new AssertionError("response is missing: " + id));
        assertTrue(response.has("result"));
        return (ObjectNode) response.get("result");
    }

    /** 校验封闭的结果字段集合，同时允许 JSON 对象字段顺序变化。 */
    private static void assertExactFields(ObjectNode value, String... fields) {
        Set<String> expected = Set.of(fields);
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        assertEquals(expected, actual);
    }

    /** 保持测试专用分发屏障确定，并阻止中断绕过该同步点。 */
    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test dispatch barrier interrupted", interrupted);
        }
    }

    /**
     * 阻塞一个真实 RPC Handler，仅暴露证明强制入站分支不会进入 Session 或 Runtime 清理
     * 所需的关闭计数器，避免测试夹具扩散无关能力。
     */
    private static final class BlockingServices {
        private final CountDownLatch handlerEntered = new CountDownLatch(1);
        private final CountDownLatch releaseHandler = new CountDownLatch(1);
        private final AtomicInteger closeCount = new AtomicInteger();
        private final boolean holdHandler;
        private final WorkspaceUseCase history = new WorkspaceUseCase() {
            /** 阻塞夹具不开放工作区注册能力。 */
            @Override
            public Workspace openWorkspace(OpenWorkspace request) {
                throw new UnsupportedOperationException();
            }

            /** 阻塞夹具不创建通用工作区。 */
            @Override
            public Workspace openGeneralWorkspace() {
                throw new UnsupportedOperationException();
            }

            /** 列表调用作为唯一阻塞入口，用于观测 Handler 已经进入执行阶段。 */
            @Override
            public CursorPage<Workspace> listWorkspaces(String cursor, int limit) {
                handlerEntered.countDown();
                if (holdHandler) awaitReleaseIgnoringInterrupt();
                return new CursorPage<>(List.of(), null);
            }

            /** 阻塞夹具不提供可读取的工作区投影。 */
            @Override
            public Optional<Workspace> readWorkspace(String workspaceId) {
                throw new UnsupportedOperationException();
            }

            /** 阻塞夹具不提供已经打开的目录能力。 */
            @Override
            public Workspace requireOpenWorkspace(String workspaceId) {
                throw new UnsupportedOperationException();
            }

            /** 阻塞夹具不允许修改工作区信任状态。 */
            @Override
            public Workspace setWorkspaceTrust(String workspaceId, Workspace.Trust trust) {
                throw new UnsupportedOperationException();
            }

            /** 阻塞夹具不允许注销工作区，防止测试产生持久化副作用。 */
            @Override
            public void unregisterWorkspace(String workspaceId, long expectedRevision) {
                throw new UnsupportedOperationException();
            }

            /** 阻塞夹具不执行配置预热。 */
            @Override
            public void refreshPreparedWorkspaces() {
                throw new UnsupportedOperationException();
            }

            /** 阻塞夹具不声明通用目录。 */
            @Override
            public boolean isGeneralWorkspace(Path root) {
                return false;
            }

        };

        /** 创建默认忽略中断的阻塞夹具，以覆盖强制关闭分支。 */
        private BlockingServices() {
            this(true);
        }

        /** 控制 Workspace Handler 在准入后是否保持在途，使正常关闭与强制关闭可分别验证。 */
        private BlockingServices(boolean holdHandler) {
            this.holdHandler = holdHandler;
        }

        /** 在 shutdownNow 后仍保持测试 Handler 在途，确保生产屏障接受真实并发压力。 */
        private void awaitReleaseIgnoringInterrupt() {
            boolean released = false;
            while (!released) {
                try {
                    released = releaseHandler.await(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // 该夹具刻意模拟无法遵守中断协议的资源所有者。
                }
            }
        }

        /** 仅在强制关闭断言完成后释放阻塞 Handler，避免测试提前解除关键竞态。 */
        private void release() {
            releaseHandler.countDown();
        }

        /** 组合阻塞 Workspace 端口与关闭计数器，其他能力保持显式拒绝。 */
        private RpcServiceBindings bindings() {
            return RpcTestBindings.create(history, null, null, null, null,
                    closeCount::incrementAndGet);
        }
    }

    /**
     * 在完整请求后保持读取线程，使测试能在阻塞 Handler 仍处于准入状态时调用同一关闭所有者；
     * 该输入流只服务测试同步，不改变生产 stdin 唤醒实现。
     */
    private static final class BlockingInputStream extends InputStream {
        private final byte[] bytes;
        private final CountDownLatch release = new CountDownLatch(1);
        private int offset;

        /** 仅保留私有且不可变的请求前缀，不额外缓存第二条协议通道。 */
        private BlockingInputStream(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        /** 请求字节耗尽后等待显式释放，以稳定复现关闭与读取线程并发。 */
        @Override
        public int read() throws IOException {
            if (offset < bytes.length) return bytes[offset++] & 0xff;
            try {
                release.await();
                return -1;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("test input interrupted", interrupted);
            }
        }

        /** 在强制关闭分支返回后解除读取线程阻塞。 */
        private void release() {
            release.countDown();
        }
    }

    /** 用最小应用对象图验证传输生命周期，不伪造任何业务响应。 */
    private static final class EmptyServices {
        private final AtomicBoolean closed;

        /** 仅保留 EOF 生命周期验收所需的关闭探针。 */
        private EmptyServices(AtomicBoolean closed) { this.closed = closed; }

        /** 只组合关闭探针，所有业务端口保持显式拒绝。 */
        private RpcServiceBindings bindings() {
            return RpcTestBindings.create(null, null, null, null, null,
                    () -> closed.set(true));
        }

        /**
         * 配置写入测试只开放提交后的工作区预热能力；其它工作区操作仍显式失败，避免空夹具
         * 掩盖 Handler 的意外跨域调用。
         */
        private RpcServiceBindings configurationBindings() {
            WorkspaceUseCase workspaces = (WorkspaceUseCase) Proxy.newProxyInstance(
                    RpcServerTest.class.getClassLoader(), new Class<?>[]{WorkspaceUseCase.class},
                    (proxy, method, arguments) -> {
                        if ("refreshPreparedWorkspaces".equals(method.getName())) return null;
                        throw new AssertionError("unexpected workspace call: " + method.getName());
                    });
            return RpcTestBindings.create(workspaces, null, null, null, null,
                    () -> closed.set(true));
        }

    }

    /** 以脱敏健康状态模拟配置文件损坏，同时保持 initialize 可用。 */
    private static final class DegradedConfiguration extends TestConfigurationPorts {
        /** 返回固定的脱敏降级诊断，验证健康帧不会泄露配置路径。 */
        @Override public ConfigurationUseCase.HealthResult health() {
            return new ConfigurationUseCase.HealthResult(
                    ConfigurationUseCase.HealthStatus.DEGRADED, List.of("CORRUPT_CONFIG"));
        }
    }

    /** 最小配置用例夹具只返回纯 JDK 结果，由 Handler 负责构造冻结 Wire。 */
    private static final class WireConfiguration extends TestConfigurationPorts {
        /** 返回固定补丁回执，使 Wire 断言不依赖文件系统写入。 */
        @Override public ConfigurationUseCase.MutationResult patch(
                ConfigurationScope scope, Path root, ConfigurationUseCase.Document patch, String version) {
            return new ConfigurationUseCase.MutationResult(scope, "cfg_user_2");
        }
        /** 配置替换复用补丁回执，确保测试聚焦公共 Wire 结构。 */
        @Override public ConfigurationUseCase.MutationResult replace(
                ConfigurationScope scope, Path root, ConfigurationUseCase.Document document, String version) {
            return patch(scope, root, document, version);
        }
        /** 配置重置复用补丁回执，避免维护另一套测试响应形状。 */
        @Override public ConfigurationUseCase.MutationResult reset(
                ConfigurationScope scope, Path root, String version) {
            return new ConfigurationUseCase.MutationResult(scope, "cfg_user_2");
        }
        /** 返回不含 Secret 的固定凭据设置回执，用于验证响应脱敏。 */
        @Override public ConfigurationUseCase.CredentialResult setCredential(
                String credentialId, String secret, String version) {
            return new ConfigurationUseCase.CredentialResult(credentialId, true, "auth_2");
        }
        /** 返回固定凭据删除回执，验证 configured 状态和版本字段。 */
        @Override public ConfigurationUseCase.CredentialResult deleteCredential(
                String credentialId, String version) {
            return new ConfigurationUseCase.CredentialResult(credentialId, false, "auth_3");
        }
        /** 返回稳定健康状态，避免 Wire 合同测试被无关诊断干扰。 */
        @Override public ConfigurationUseCase.HealthResult health() {
            return new ConfigurationUseCase.HealthResult(
                    ConfigurationUseCase.HealthStatus.HEALTHY, List.of());
        }
    }
}
