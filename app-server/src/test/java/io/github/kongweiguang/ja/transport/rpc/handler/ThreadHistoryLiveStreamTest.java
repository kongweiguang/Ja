// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionUseCase;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.catalog.port.in.CatalogUseCase;
import io.github.kongweiguang.ja.attachment.port.in.AttachmentPreviewUseCase;
import io.github.kongweiguang.ja.attachment.port.in.AttachmentUseCase;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationUseCase;
import io.github.kongweiguang.ja.conversation.port.in.ApprovalUseCase;
import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.goal.port.in.GoalUseCase;
import io.github.kongweiguang.ja.task.port.in.TaskUseCase;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;
import io.github.kongweiguang.ja.transport.rpc.runtime.StdioWriter;
import io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings;
import io.github.kongweiguang.ja.transport.rpc.support.TestConfigurationPorts;
import io.github.kongweiguang.ja.workspace.port.in.WorkspacePathSearchUseCase;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.preferences;
import static io.github.kongweiguang.ja.transport.rpc.runtime.RpcRuntimeTestAccess.markReady;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通过真实 RpcSession/ThreadHistoryHandler 验证 thread/read 的活动流基线投影。 */
final class ThreadHistoryLiveStreamTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** 新活动尚无 delta 时仍返回 seq=0 空基线，避免客户端把运行中的 Turn 当作无活动。 */
    @Test
    void readsEmptyLiveStreamBeforeFirstDelta() {
        try (Harness harness = new Harness(List.of(turn("turn_empty", "queued")))) {
            harness.session.registerTurnNotificationContext("turn_empty", "ws_live", "thr_live", 4);

            ObjectNode result = harness.read();

            assertEquals(4, result.path("revision").intValue());
            assertEquals("turn_empty", result.path("liveStream").path("turnId").textValue());
            assertEquals(0, result.path("liveStream").path("streamSeq").intValue());
            assertTrue(result.path("liveStream").path("segments").isEmpty());
        }
    }

    /**
     * Provider dispatch 的内部提交没有公开事件时，真实 RpcSession sink 仍须先建立 modelRound fence；
     * 首段 delta 到达后 thread/read 不得把健康活动流误判为断流。
     */
    @Test
    void readsFirstDeltaAfterInternalDispatchBoundary() {
        try (Harness harness = new Harness(List.of(turn("turn_dispatch", "running", 3, 0)))) {
            harness.session.registerTurnNotificationContext("turn_dispatch", "ws_live", "thr_live", 4, 1);
            harness.session.eventSink().observeCommittedTurn("thr_live", "turn_dispatch", 4,
                    3, 0, null, NOW);
            harness.session.publish(new TurnEvent.TextDelta("turn_dispatch", 1, "首段输出")).join();

            ObjectNode result = harness.read();

            assertEquals("turn_dispatch", result.path("liveStream").path("turnId").textValue());
            assertEquals(1, result.path("liveStream").path("streamSeq").intValue());
            assertEquals("首段输出", result.path("liveStream").path("segments").get(0)
                    .path("text").textValue());
        }
    }

    /** 新 RPC 代际恢复已有模型轮次时，首个公开 delta 不得因默认零轮次而被误判为未清稿。 */
    @Test
    void restoresCompletedModelRoundForNewGeneration() {
        try (Harness harness = new Harness(List.of(turn("turn_resume", "running", 3, 2)))) {
            harness.session.registerTurnNotificationContext("turn_resume", "ws_live", "thr_live", 4,
                    3, 2);
            harness.session.publish(new TurnEvent.TextDelta("turn_resume", 1, "恢复后的首段")).join();

            ObjectNode result = harness.read();

            assertEquals("turn_resume", result.path("liveStream").path("turnId").textValue());
            assertEquals("恢复后的首段", result.path("liveStream").path("segments").get(0)
                    .path("text").textValue());
        }
    }

    /** queued 与 running 共存时，真实出站 delta 绑定的 running Turn 必须成为 read 基线。 */
    @Test
    void readsRunningLiveStreamThroughRpcAfterQueuedTurn() {
        try (Harness harness = new Harness(List.of(turn("turn_queued", "queued"),
                turn("turn_running", "running")))) {
            harness.session.registerTurnNotificationContext("turn_queued", "ws_live", "thr_live", 4, 0);
            harness.session.registerTurnNotificationContext("turn_running", "ws_live", "thr_live", 4, 0);
            harness.session.publish(new TurnEvent.StateChanged(new TurnEvent.Context(
                    "evt_running", "thr_live", "turn_running", 4, 0, NOW),
                    TurnState.QUEUED, TurnState.RUNNING)).join();
            harness.session.publish(new TurnEvent.TextDelta("turn_running", 1, "正在生成")).join();

            ObjectNode result = harness.read();

            assertEquals("turn_running", result.path("liveStream").path("turnId").textValue());
            assertEquals(1, result.path("liveStream").path("streamSeq").intValue());
            assertEquals("assistant", result.path("liveStream").path("segments").get(0)
                    .path("kind").textValue());
            assertEquals("正在生成", result.path("liveStream").path("segments").get(0)
                    .path("text").textValue());
            assertEquals(NOW, Instant.parse(result.path("liveStream").path("segments").get(0)
                    .path("occurredAt").textValue()));
        }
    }

    /** JSON 控制字符会把 1MiB 公开正文扩成超过 4MiB frame；只丢弃完整 liveStream，不截断片段。 */
    @Test
    void dropsLiveStreamWhenEscapedFrameWouldExceedBudget() {
        try (Harness harness = new Harness(List.of(turn("turn_budget", "running")))) {
            harness.session.registerTurnNotificationContext("turn_budget", "ws_live", "thr_live", 4);
            String escaped = String.valueOf((char) 1).repeat(60 * 1024);
            for (int sequence = 1; sequence <= 16; sequence++) {
                harness.session.publish(new TurnEvent.TextDelta("turn_budget", sequence, escaped)).join();
            }

            ObjectNode result = harness.read();

            assertTrue(result.has("liveStream"));
            assertTrue(result.get("liveStream").isNull());
            assertTrue(result.has("items"));
        }
    }

    /** 删除 Thread 必须调用按 Thread 清理入口，不能留下同 Thread 的 queued 或 running 草稿。 */
    @Test
    void deletesAllLiveStreamsForThread() {
        try (Harness harness = new Harness(List.of(turn("turn_delete_queued", "queued"),
                turn("turn_delete_running", "running")))) {
            harness.session.registerTurnNotificationContext("turn_delete_queued", "ws_live", "thr_live", 4);
            harness.session.registerTurnNotificationContext("turn_delete_running", "ws_live", "thr_live", 4);
            harness.session.publish(new TurnEvent.TextDelta("turn_delete_running", 1, "draft")).join();

            ObjectNode result = harness.delete();

            assertTrue(result.path("accepted").booleanValue());
            assertTrue(harness.threads.deleted.get());
            assertTrue(!harness.session.activeStreams().hasActive("thr_live"));
        }
    }

    /** 持久快照先进入终态时，即使终态通知迟到，thread/read 也必须回收孤儿临时流。 */
    @Test
    void clearsOrphanedStreamAfterTerminalSnapshot() {
        try (Harness harness = new Harness(List.of(turn("turn_terminal_snapshot", "completed")))) {
            harness.session.registerTurnNotificationContext(
                    "turn_terminal_snapshot", "ws_live", "thr_live", 4);
            harness.session.publish(new TurnEvent.TextDelta("turn_terminal_snapshot", 1, "draft")).join();

            ObjectNode result = harness.read();

            assertTrue(result.get("liveStream").isNull());
            assertFalse(harness.session.activeStreams().hasActive("thr_live"));
        }
    }

    /** 构造真实连接级 Handler；除历史读取和 Task/Goal 空列表外的能力均明确拒绝。 */
    private static final class Harness implements AutoCloseable {
        private final RecordingThreads threads;
        private final StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), MAPPER, 4 * 1024 * 1024);
        private final RpcSession session;
        private final ThreadHistoryHandler handler;

        /** 将 ThreadUseCase 读取和真实 Event Sink 接入同一 RpcSession 生命周期。 */
        private Harness(List<ThreadSnapshot.Turn> turns) {
            threads = new RecordingThreads(turns);
            RpcServiceBindings bindings = new RpcServiceBindings(
                    unsupported(WorkspaceUseCase.class), unsupported(WorkspacePathSearchUseCase.class), threads.proxy,
                    unsupported(TurnUseCase.class), unsupported(ContextCompactionUseCase.class),
                    unsupported(ApprovalUseCase.class), unsupported(CatalogUseCase.class),
                    unsupported(AttachmentUseCase.class), unsupported(AttachmentPreviewUseCase.class),
                    passiveTasks(), passiveGoals(), RpcTestBindings.passiveInteractions(), new NoopLifecycle());
            Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-thread-live-stream-test")
                    .toAbsolutePath();
            SidecarConfiguration sidecar = new SidecarConfiguration(root.resolve("home"), root.resolve("data"),
                    root.resolve("run"), root.resolve("logs"));
            ConfigurationUseCase configuration = TestConfigurationPorts.unavailable();
            session = new RpcSession(sidecar, MAPPER, CLOCK, writer, ignored -> bindings, configuration);
            session.initialize();
            markReady(session, "0123456789abcdef0123456789abcdef");
            handler = new ThreadHistoryHandler(session);
        }

        /** 直接驱动 ThreadHistoryHandler，仍经过真实 RpcSession active stream owner。 */
        private ObjectNode read() {
            return handler.handle(new RpcCommand(RpcMethod.THREAD_READ,
                    MAPPER.createObjectNode().put("threadId", "thr_live").put("limit", 20)))
                    .toCompletableFuture().join();
        }

        /** 通过真实 Handler 走 Thread 删除分支，验证按 Thread 清理而非仅删一个 Turn。 */
        private ObjectNode delete() {
            return handler.handle(new RpcCommand(RpcMethod.THREAD_DELETE,
                    MAPPER.createObjectNode().put("threadId", "thr_live")
                            .put("expectedThreadRevision", 4)))
                    .toCompletableFuture().join();
        }

        /** 关闭真实 Handler、Session 与 Writer，避免线程池或出站 executor 泄漏到其它测试。 */
        @Override
        public void close() {
            try {
                handler.close();
            } finally {
                try {
                    session.close();
                } finally {
                    writer.close();
                }
            }
        }
    }

    /** 返回固定 revision 的历史快照；运行事件仍通过 RpcSession registry 进入 read。 */
    private static final class RecordingThreads implements ThreadUseCase {
        private final List<ThreadSnapshot.Turn> turns;
        private final AtomicLong revision = new AtomicLong(4);
        private final AtomicBoolean deleted = new AtomicBoolean();
        private final ThreadUseCase proxy;

        /** 只冻结历史 turns，避免测试 proxy 从内存 registry 反推页面状态。 */
        private RecordingThreads(List<ThreadSnapshot.Turn> turns) {
            this.turns = List.copyOf(turns);
            this.proxy = (ThreadUseCase) Proxy.newProxyInstance(
                    ThreadUseCase.class.getClassLoader(), new Class<?>[]{ThreadUseCase.class},
                    (ignored, method, args) -> {
                        if ("readThread".equals(method.getName())) {
                            return Optional.of(snapshot(revision.get(), this.turns));
                        }
                        if ("deleteThread".equals(method.getName())) {
                            deleted.set(true);
                            return null;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }

        /** 以同一 Thread revision 返回真实 reader 会看到的 metadata 与 turns。 */
        private static ThreadSnapshot snapshot(long revision, List<ThreadSnapshot.Turn> turns) {
            TurnState latest = turns.stream().anyMatch(turn -> "running".equals(turn.status()))
                    ? TurnState.RUNNING : TurnState.QUEUED;
            ThreadSummary thread = new ThreadSummary("thr_live", "ws_live", "Live stream", preferences(),
                    ThreadSummary.Status.ACTIVE, false, latest, false, null, revision, NOW, NOW);
            return new ThreadSnapshot(thread, turns, List.of(), null, null, null);
        }

        /** 该回归只读取固定快照，不允许测试意外创建 Thread。 */
        @Override public ThreadSummary createThread(ThreadSummary.Creation request) { throw unsupported(); }
        /** 该回归不覆盖列表分页，未声明能力必须显式失败。 */
        @Override public CursorPage<ThreadSummary> listThreads(String workspaceId, String cursor, int limit) { throw unsupported(); }
        /** 该回归不覆盖搜索分页，避免用搜索结果伪造历史快照。 */
        @Override public CursorPage<ThreadSummary> searchThreads(String workspaceId, String query, String cursor, int limit) { throw unsupported(); }
        /** 代理只响应内部 readThread 分支，直接调用端口表示测试接线错误。 */
        @Override public Optional<ThreadSnapshot> readThread(String threadId, String cursor, int limit) { throw unsupported(); }
        /** 该回归不修改标题，保持 Thread revision 固定以专测活动流。 */
        @Override public ThreadSummary renameThread(String threadId, String title, long revision) { throw unsupported(); }
        /** 该回归不修改偏好，避免 metadata 变化掩盖活动流 fence。 */
        @Override public ThreadSummary updatePreferences(String threadId, io.github.kongweiguang.ja.conversation.domain.ThreadPreferences value, long revision) { throw unsupported(); }
        /** 该回归不生成自动标题，固定目录事实以隔离 liveStream 读取。 */
        @Override public boolean writeAutomaticTitle(String threadId, String title, long revision) { throw unsupported(); }
        /** 该回归不归档 Thread，归档副作用应由真实仓储测试覆盖。 */
        @Override public ThreadSummary archiveThread(String threadId, long revision) { throw unsupported(); }
        /** 删除只通过代理识别并记录，不让未声明调用改变快照。 */
        @Override public void deleteThread(String threadId, long revision) { throw unsupported(); }
        /** 该回归不按 Turn 查询，防止单 Turn 查询替代 thread/read 合同。 */
        @Override public Optional<io.github.kongweiguang.ja.conversation.domain.TurnSummary> findTurn(String turnId) { throw unsupported(); }
    }

    /** 构造历史 Turn 元数据，状态与 registry 生命周期选择逻辑保持同一 wire 小写值。 */
    private static ThreadSnapshot.Turn turn(String turnId, String status) {
        return turn(turnId, status, 0, 0);
    }

    /** 允许恢复回归固定持久 Turn mutation 与已完成模型轮次，避免从旧 usage 推断 fence。 */
    private static ThreadSnapshot.Turn turn(String turnId, String status,
                                             long mutationVersion, int modelRound) {
        return new ThreadSnapshot.Turn(turnId, status, NOW, NOW, null, null, null,
                mutationVersion, modelRound);
    }

    /** Task/Goal 只允许连接订阅与 thread/read 所需的空活动列表。 */
    private static TaskUseCase passiveTasks() {
        return (TaskUseCase) Proxy.newProxyInstance(TaskUseCase.class.getClassLoader(),
                new Class<?>[]{TaskUseCase.class}, (ignored, method, args) -> {
                    if ("subscribe".equals(method.getName())) return (AutoCloseable) () -> { };
                    if ("closeTemporarySideChats".equals(method.getName())) return null;
                    if ("listRootActivities".equals(method.getName())) return List.of();
                    throw unsupported();
                });
    }

    /** Goal 只允许连接订阅和 thread/read 所需的空终态活动列表。 */
    private static GoalUseCase passiveGoals() {
        return (GoalUseCase) Proxy.newProxyInstance(GoalUseCase.class.getClassLoader(),
                new Class<?>[]{GoalUseCase.class}, (ignored, method, args) -> {
                    if ("subscribe".equals(method.getName()) || "subscribePlan".equals(method.getName())) {
                        return (AutoCloseable) () -> { };
                    }
                    if ("listTerminalActivities".equals(method.getName())) return List.of();
                    throw unsupported();
                });
    }

    /** 未声明的边界能力显式失败，避免 test harness 吞掉真实接线缺口。 */
    private static <T> T unsupported(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, args) -> { throw unsupported(); });
    }

    /** 统一未声明能力失败类型。 */
    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("not used by live stream test");
    }

    /** 不产生外部资源的最小 lifecycle owner。 */
    private static final class NoopLifecycle implements DeadlineCloseable {
        /** 测试无需关闭外部资源。 */
        @Override public void closeAt(long shutdownDeadlineNanos) { }
        /** 测试无无需关闭外部资源。 */
        @Override public void close() { }
    }
}
