// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.model.TurnEvent;
import io.github.kongweiguang.ja.conversation.domain.model.TurnSummary;
import io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.transport.rpc.RpcServicesFactory;
import io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings;
import io.github.kongweiguang.ja.transport.rpc.support.TestConfigurationPorts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 冷连接仅观察权威未决审批；分页、身份 fence 与旧 Bridge replay 都必须形成闭环。 */
class RpcColdApprovalObservationTest {
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private static final String READY_TOKEN = "0123456789abcdef0123456789abcdef";

    /** ready 后完整分页并观察 MAIN/过期 SUBAGENT，同时跳过旁聊、终态和非审批暂停。 */
    @Test
    void observesOnlyCurrentApprovalWaitsAfterReadyAcrossDiscoveryPages() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Map<String, Fixture> fixtures = new HashMap<>();
        fixtures.put("thr_main", fixture("thr_main", "ws_main", "turn_main",
                TurnState.WAITING_APPROVAL, 8, 3, NOW.plusSeconds(60)));
        fixtures.put("thr_sub", fixture("thr_sub", "ws_sub", "turn_sub",
                TurnState.SUSPENDED, 13, 5, NOW.minusSeconds(1)));
        fixtures.put("thr_plain_suspended", fixture("thr_plain_suspended", "ws_plain", "turn_plain",
                TurnState.SUSPENDED, 4, 2, null));
        fixtures.put("thr_future_suspended", fixture("thr_future_suspended", "ws_future", "turn_future",
                TurnState.SUSPENDED, 9, 4, NOW.plusSeconds(60)));

        List<ThreadDiscovery> firstPage = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            firstPage.add(new ThreadDiscovery("thr_skip_" + index, "completed", ThreadDiscovery.Kind.MAIN,
                    "ws_skip", ThreadDiscovery.Status.COMPLETED, null));
        }
        List<ThreadDiscovery> secondPage = List.of(
                discovery("thr_main", "ws_main", "turn_main", ThreadDiscovery.Kind.MAIN,
                        ThreadDiscovery.Status.WAITING_APPROVAL),
                discovery("thr_sub", "ws_sub", "turn_sub", ThreadDiscovery.Kind.SUBAGENT,
                        ThreadDiscovery.Status.SUSPENDED),
                discovery("thr_side", "ws_side", "turn_side", ThreadDiscovery.Kind.SIDE_CHAT,
                        ThreadDiscovery.Status.WAITING_APPROVAL),
                discovery("thr_plain_suspended", "ws_plain", "turn_plain", ThreadDiscovery.Kind.MAIN,
                        ThreadDiscovery.Status.SUSPENDED),
                discovery("thr_future_suspended", "ws_future", "turn_future", ThreadDiscovery.Kind.SUBAGENT,
                        ThreadDiscovery.Status.SUSPENDED),
                new ThreadDiscovery("thr_terminal", "terminal", ThreadDiscovery.Kind.SUBAGENT,
                        "ws_terminal", ThreadDiscovery.Status.CANCELLED, "turn_terminal"));
        AtomicInteger discoveryCalls = new AtomicInteger();
        List<String> historyReads = new ArrayList<>();
        List<String> requestedCursors = new ArrayList<>();
        ThreadUseCase threads = threadPort(fixtures, query -> {
            requestedCursors.add(query.cursor());
            assertEquals("all", query.scope());
            assertEquals(200, query.limit());
            return discoveryCalls.getAndIncrement() == 0
                    ? new CursorPage<>(firstPage, "cursor_page_2")
                    : new CursorPage<>(secondPage, null);
        }, historyReads);
        Map<String, ConversationTurnEventSink> observed = new HashMap<>();
        TurnUseCase turns = turnPort(observed);
        RpcServiceBindings bindings = RpcTestBindings.create(null, threads, turns, null, null, null);
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-cold-approval-observation").toAbsolutePath();
        try (StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024);
             RpcSession session = new RpcSession(new SidecarConfiguration(root.resolve("home"), root.resolve("data"),
                     root.resolve("run"), root.resolve("logs")), mapper, Clock.fixed(NOW, ZoneOffset.UTC), writer,
                     ignored -> bindings, TestConfigurationPorts.unavailable())) {
            session.initialize();
            assertEquals(0, discoveryCalls.get(), "history scanning must wait until the ready frame is flushed");
            output.reset();
            RpcRuntimeTestAccess.markReady(session, READY_TOKEN);

            assertEquals(Arrays.asList(null, "cursor_page_2"), requestedCursors);
            assertEquals(List.of("thr_main", "thr_sub", "thr_plain_suspended", "thr_future_suspended"), historyReads);
            assertEquals(List.of("turn_main", "turn_sub"), observed.keySet().stream().sorted().toList());

            ConversationTurnEventSink mainSink = observed.get("turn_main");
            mainSink.publish(approvalRequested("thr_main", "turn_main", 7, "appr_old"))
                    .toCompletableFuture().join();
            mainSink.publish(new TurnEvent.StateChanged(context("thr_main", "turn_main", 7, "evt_old_state"),
                    TurnState.RUNNING, TurnState.WAITING_APPROVAL)).toCompletableFuture().join();
            mainSink.publish(new TurnEvent.TextDelta("turn_main", 1, "uncommitted draft", "request_cold"))
                    .toCompletableFuture().join();
            mainSink.publish(new TurnEvent.ApprovalClosed(new TurnEvent.Context("evt_close", "thr_main",
                    "turn_main", 8, 4, NOW), "appr_current", InteractionStatus.EXPIRED))
                    .toCompletableFuture().join();

            List<String> methods = output.toString(StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank())
                    .map(line -> read(mapper, line).path("method").asText()).toList();
            assertTrue(methods.contains("runtime/status-changed"));
            assertTrue(methods.contains("approval/closed"));
            assertFalse(methods.contains("approval/requested"));
            assertFalse(methods.contains("turn/state-changed"));
            assertFalse(methods.contains("assistant/text-delta"));
            assertTrue(methods.indexOf("runtime/status-changed") < methods.indexOf("approval/closed"));
        }
    }

    /** 到期可能夹在 Thread 快照与全局 Turn 查询之间；一次重读应观察到新的精确 SUSPENDED 状态。 */
    @Test
    void rereadsOnceWhenApprovalExpiresBetweenSnapshotAndTurnLookup() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Fixture beforeExpiry = fixture("thr_race", "ws_race", "turn_race",
                TurnState.WAITING_APPROVAL, 7, 1, NOW.plusSeconds(60));
        Fixture afterExpiry = fixture("thr_race", "ws_race", "turn_race",
                TurnState.SUSPENDED, 8, 2, NOW.minusSeconds(1));
        AtomicInteger historyReads = new AtomicInteger();
        ThreadUseCase threads = (ThreadUseCase) Proxy.newProxyInstance(ThreadUseCase.class.getClassLoader(),
                new Class<?>[]{ThreadUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "discoverThreads" -> new CursorPage<>(List.of(discovery("thr_race", "ws_race",
                            "turn_race", ThreadDiscovery.Kind.SUBAGENT,
                            ThreadDiscovery.Status.WAITING_APPROVAL)), null);
                    case "readThreadLatest" -> {
                        historyReads.incrementAndGet();
                        yield Optional.of(historyReads.get() == 1 ? beforeExpiry.snapshot() : afterExpiry.snapshot());
                    }
                    // 在第一次快照之后到期；后续读取必须以变化后的全局 Turn 为准。
                    case "findTurn" -> Optional.of(afterExpiry.turn());
                    default -> throw new AssertionError("unexpected ThreadUseCase call: " + method.getName());
                });
        Map<String, ConversationTurnEventSink> observed = new HashMap<>();
        RpcServiceBindings bindings = RpcTestBindings.create(null, threads, turnPort(observed), null, null, null);
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-cold-approval-expiry-race-test").toAbsolutePath();
        try (StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024);
             RpcSession session = new RpcSession(new SidecarConfiguration(root.resolve("home"), root.resolve("data"),
                     root.resolve("run"), root.resolve("logs")), mapper, Clock.fixed(NOW, ZoneOffset.UTC), writer,
                     ignored -> bindings, TestConfigurationPorts.unavailable())) {
            session.initialize();
            RpcRuntimeTestAccess.markReady(session, READY_TOKEN);

            assertEquals(2, historyReads.get());
            assertEquals(List.of("turn_race"), List.copyOf(observed.keySet()));
        }
    }

    /** 重复的 opaque cursor 必须使 startup observation 失败，而不能永久扫描或报告 ready 成功。 */
    @Test
    void rejectsRepeatedDiscoveryCursor() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicInteger calls = new AtomicInteger();
        ThreadUseCase threads = threadPort(Map.of(), query -> {
            calls.incrementAndGet();
            return new CursorPage<>(List.of(), "same_cursor");
        }, new ArrayList<>());
        RpcServiceBindings bindings = RpcTestBindings.create(null, threads, turnPort(new HashMap<>()), null, null, null);
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-cold-approval-cursor-test").toAbsolutePath();
        try (StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024);
             RpcSession session = new RpcSession(new SidecarConfiguration(root.resolve("home"), root.resolve("data"),
                     root.resolve("run"), root.resolve("logs")), mapper, Clock.fixed(NOW, ZoneOffset.UTC), writer,
                     ignored -> bindings, TestConfigurationPorts.unavailable())) {
            session.initialize();
            assertThrows(IllegalStateException.class,
                    () -> RpcRuntimeTestAccess.markReady(session, READY_TOKEN));
            assertEquals(2, calls.get());
        }
    }

    /** 存储阻塞时 ready 只发布状态；显式重试成功后复用同一代际的一次性扫描。 */
    @Test
    void scansAfterStorageRetryWhenReadyWasPreviouslyBlocked() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicInteger discoveryCalls = new AtomicInteger();
        ThreadUseCase threads = threadPort(Map.of(), query -> {
            discoveryCalls.incrementAndGet();
            return new CursorPage<>(List.of(), null);
        }, new ArrayList<>());
        RpcServiceBindings bindings = RpcTestBindings.create(null, threads, turnPort(new HashMap<>()), null, null, null);
        java.util.concurrent.atomic.AtomicBoolean recovered = new java.util.concurrent.atomic.AtomicBoolean();
        RpcServicesFactory factory = new RpcServicesFactory() {
            /** 存储检查受阻时不发布部分服务图，重试后返回同一测试服务图。 */
            @Override
            public RpcServiceBindings open(ObjectMapper ignored) {
                if (!recovered.get()) {
                    throw new StorageException(StorageException.Code.IO, "opaque");
                }
                return bindings;
            }

            /** 恢复摘要区分阻塞初始 ready 与已显式解除阻塞的重试。 */
            @Override
            public RecoveryStatus storageRecovery() {
                return new RecoveryStatus("11111111-1111-1111-1111-111111111111",
                        recovered.get() ? "ready" : "blocked", "DISK_FULL", true, false, false);
            }

            /** 只改变夹具恢复门，生产扫描仍必须由 RpcSession 的正常重试路径触发。 */
            @Override
            public void retryStorage() {
                recovered.set(true);
            }
        };
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-cold-approval-retry-test").toAbsolutePath();
        try (StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024);
             RpcSession session = new RpcSession(new SidecarConfiguration(root.resolve("home"), root.resolve("data"),
                     root.resolve("run"), root.resolve("logs")), mapper, Clock.fixed(NOW, ZoneOffset.UTC), writer,
                     factory, TestConfigurationPorts.unavailable())) {
            session.initialize();
            output.reset();
            RpcRuntimeTestAccess.markReady(session, READY_TOKEN);
            assertFalse(session.servicesAvailable());
            assertEquals(0, discoveryCalls.get());

            session.retryStorage();

            assertTrue(session.servicesAvailable());
            assertEquals(1, discoveryCalls.get());
        }
    }

    /** 动态 Thread 端口只装配 discovery、必要历史页和精确全局 Turn 查询。 */
    private static ThreadUseCase threadPort(Map<String, Fixture> fixtures,
                                            java.util.function.Function<ThreadDiscovery.Query,
                                                    CursorPage<ThreadDiscovery>> discovery,
                                            List<String> historyReads) {
        return (ThreadUseCase) Proxy.newProxyInstance(ThreadUseCase.class.getClassLoader(),
                new Class<?>[]{ThreadUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "discoverThreads" -> discovery.apply((ThreadDiscovery.Query) arguments[0]);
                    case "readThreadLatest" -> {
                        String threadId = (String) arguments[0];
                        historyReads.add(threadId);
                        Fixture fixture = fixtures.get(threadId);
                        if (fixture == null) {
                            throw new AssertionError("unexpected cold history read: " + threadId);
                        }
                        yield Optional.of(fixture.snapshot());
                    }
                    case "findTurn" -> {
                        String turnId = (String) arguments[0];
                        yield fixtures.values().stream().map(Fixture::turn)
                                .filter(turn -> turn.turnId().equals(turnId)).findFirst();
                    }
                    default -> throw new AssertionError("unexpected ThreadUseCase call: " + method.getName());
                });
    }

    /** 冷扫描只应调用无副作用观察入口；fixture 保留每个精确 Turn 的实际 Wire sink。 */
    private static TurnUseCase turnPort(Map<String, ConversationTurnEventSink> observed) {
        return (TurnUseCase) Proxy.newProxyInstance(TurnUseCase.class.getClassLoader(),
                new Class<?>[]{TurnUseCase.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("observeApprovalWait")) {
                        observed.put((String) arguments[0], (ConversationTurnEventSink) arguments[1]);
                        return true;
                    }
                    throw new AssertionError("unexpected TurnUseCase call: " + method.getName());
                });
    }

    /** 用当前状态摘要构造最小发现项，避免 fixture 状态本身预先决定观察结果。 */
    private static ThreadDiscovery discovery(String threadId, String workspaceId, String turnId,
                                             ThreadDiscovery.Kind kind, ThreadDiscovery.Status status) {
        return new ThreadDiscovery(threadId, threadId, kind, workspaceId, status, turnId);
    }

    /** 快照与全局 Turn 共用同一事务身份，并可显式构造未决审批、已过期等待或任意 SUSPENDED。 */
    private static Fixture fixture(String threadId, String workspaceId, String turnId, TurnState state,
                                   long revision, long mutationVersion, Instant approvalExpiry) {
        ThreadSummary thread = new ThreadSummary(threadId, workspaceId, threadId, "project",
                ConversationTestFixtures.preferences(), ThreadSummary.Status.ACTIVE, false, state, false,
                revision, NOW.minusSeconds(60), NOW, turnId);
        ThreadSnapshot.Turn snapshotTurn = new ThreadSnapshot.Turn(turnId,
                state.name().toLowerCase(java.util.Locale.ROOT), NOW.minusSeconds(60), NOW, null, null,
                null, mutationVersion, 2, null);
        List<ThreadSnapshot.Item> items = approvalExpiry == null ? List.of()
                : List.of(new ThreadSnapshot.ApprovalItem("item_" + turnId, NOW.minusSeconds(10),
                        "appr_" + threadId.substring(4), turnId, "call_test", "shell", "run command",
                        null, approvalExpiry));
        ThreadSnapshot snapshot = new ThreadSnapshot(thread, List.of(snapshotTurn), items, null, null, null);
        TurnSummary turn = new TurnSummary(threadId, turnId,
                state.name().toLowerCase(java.util.Locale.ROOT), revision, mutationVersion, false);
        return new Fixture(snapshot, turn);
    }

    /** 构造可由实际 RPC 出站路径映射的 durable ApprovalRequested，用于证明旧 replay 被 fence 截断。 */
    private static TurnEvent.ApprovalRequested approvalRequested(String threadId, String turnId,
                                                                 long revision, String approvalId) {
        ToolPresentation presentation = new ToolPresentation(ToolPresentation.Kind.SHELL, "Run command",
                ToolPresentation.Status.PENDING, null, null, null, List.of(), List.of(), "pnpm test", "workspace",
                null, null, null, null, false, null, null, List.of());
        return new TurnEvent.ApprovalRequested(context(threadId, turnId, revision, "evt_" + approvalId),
                approvalId, "call_test", "shell", presentation, "run command", NOW.plusSeconds(60));
    }

    /** 生成固定时刻和独立事件 ID，使 wire 断言只受 identity/revision fence 影响。 */
    private static TurnEvent.Context context(String threadId, String turnId, long revision, String eventId) {
        return new TurnEvent.Context(eventId, threadId, turnId, revision, 2, NOW);
    }

    /** 将已 flush 的单行协议帧转成 JSON，只检查公开通知 method，不依赖字段顺序。 */
    private static JsonNode read(ObjectMapper mapper, String line) {
        try {
            return mapper.readTree(line);
        } catch (java.io.IOException failure) {
            throw new AssertionError("RPC writer emitted invalid JSON", failure);
        }
    }

    private record Fixture(ThreadSnapshot snapshot, TurnSummary turn) {
    }
}
