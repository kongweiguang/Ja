// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;
import io.github.kongweiguang.ja.transport.rpc.runtime.StdioWriter;
import io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings;
import io.github.kongweiguang.ja.transport.rpc.support.TestConfigurationPorts;

import io.github.kongweiguang.ja.conversation.domain.approval.ApprovalDecision;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.TurnSummary;
import io.github.kongweiguang.ja.conversation.port.in.ApprovalUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import io.github.kongweiguang.ja.conversation.port.in.TurnEventSink;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.kongweiguang.ja.transport.rpc.runtime.RpcRuntimeTestAccess.markReady;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.preferences;

/** 通过真实 RPC 会话与 Writer 验证 Turn 启动边界和审批通知写出顺序。 */
final class RpcApprovalTransportTest {
    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** 提供 JA-RPC v2 Sidecar 边界要求的四个明确根目录。 */
    private static SidecarConfiguration testConfiguration() {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-rpc-approval-test")
                .toAbsolutePath().normalize();
        return new SidecarConfiguration(root.resolve("home"), root.resolve("data"),
                root.resolve("run"), root.resolve("logs"));
    }

    /** 验证审批响应释放在途 ID 前，resolved 通知已经完成 flush。 */
    @Test
    void approvalResolvedFlushesBeforeResponseWithoutWriterDeadlock() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ApprovalServices services = new ApprovalServices();
        StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024);
        RpcSession session = null;
        try {
            RpcSession current = new RpcSession(
                    testConfiguration(),
                    mapper, CLOCK, writer, ignored -> services.bindings(),
                    TestConfigurationPorts.unavailable());
            session = current;
            services.session.set(current);
            current.initialize();
            markReady(current, "0123456789abcdef0123456789abcdef");
            current.registerTurnNotificationContext("turn_test", "ws_test", "thr_test", 0);
            output.reset();

            current.publish(new TurnEvent.ApprovalRequested(
                    context("evt_requested", 3), "appr_test", "call_test", "shell",
                    "run tests", NOW.plusSeconds(60))).get(2, TimeUnit.SECONDS);

            ObjectNode params = mapper.createObjectNode().put("approvalId", "appr_test")
                    .put("turnId", "turn_test").put("decision", "approve")
                    .put("expectedThreadRevision", 3);
            params.put("decision", "invalid_decision");
            assertThrows(RuntimeException.class,
                    () -> new TurnApprovalHandler(current).handle(
                            new RpcCommand(RpcMethod.APPROVAL_RESPOND, params)));
            params.put("decision", "approve");
            CompletionStage<ObjectNode> result = new TurnApprovalHandler(current)
                    .handle(new RpcCommand(RpcMethod.APPROVAL_RESPOND, params));
            /*
             * 组合 Writer 确认而不是停在队列准入；断言随后立即检查字节流，
             * 若只等待响应回调，最终帧仍可能与检查竞争，即使 resolved 通知已完成 flush。
             */
            CompletionStage<Void> response = result.thenCompose(value ->
                    writer.response("c:approval", value));
            response.toCompletableFuture().get(2, TimeUnit.SECONDS);

            List<String> frames = output.toString(java.nio.charset.StandardCharsets.UTF_8).lines().toList();
            assertEquals(3, frames.size());
            assertTrue(frames.get(0).contains("approval/requested"));
            assertTrue(frames.get(1).contains("approval/resolved"));
            assertTrue(frames.get(2).contains("\"id\":\"c:approval\""));
            ObjectNode requested = (ObjectNode) mapper.readTree(frames.get(0)).path("params");
            assertEquals("ws_test", requested.path("workspaceId").textValue());
            assertEquals("thr_test", requested.path("threadId").textValue());
            assertEquals("turn_test", requested.path("turnId").textValue());
            assertEquals(3, requested.path("threadRevision").longValue());
            assertTrue(requested.path("sequence").longValue() > 0);
            assertTrue(requested.path("generation").longValue() > 0);
        } finally {
            if (session != null) session.close();
            writer.close();
        }
    }

    /** 验证终态清理多个审批时不会在 Stream 遍历期间修改 Map。 */
    @Test
    void terminalCleanupCompletesAllApprovalsWithoutConcurrentModification() throws Exception {
        ApprovalCompletions completions = new ApprovalCompletions(CLOCK, 8, 8);
        completions.requested("appr_one", "thr_test", "turn_test", NOW.plusSeconds(60));
        completions.requested("appr_two", "thr_test", "turn_test", NOW.plusSeconds(60));
        ApprovalCompletions.Pending one = completions.begin("appr_one", "turn_test");
        ApprovalCompletions.Pending two = completions.begin("appr_two", "turn_test");

        completions.terminal("turn_test");

        assertTrue(one.completion().isCompletedExceptionally());
        assertTrue(two.completion().isCompletedExceptionally());
        assertThrows(RuntimeException.class, () -> completions.begin("appr_one", "turn_test"));
        completions.close();
    }

    /**
     * 验证 transport 只把严格 Wire 参数映射为 TurnStartRequest；配置、Tool 与 RuntimeLease
     * 均不在 RPC 组合图中解析，从而由 TurnService 独占运行时冻结和释放职责。
     */
    @Test
    void turnStartDelegatesTransportFreeRequestToTurnUseCase() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StartServices services = new StartServices();
        StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024);
        RpcSession session = null;
        try {
            RpcSession current = new RpcSession(
                    testConfiguration(), mapper, CLOCK, writer,
                    ignored -> services.bindings(),
                    TestConfigurationPorts.unavailable());
            session = current;
            current.initialize();
            markReady(current, "0123456789abcdef0123456789abcdef");

            ObjectNode params = mapper.createObjectNode()
                    .put("threadId", "thr_start")
                    .put("deadlineMs", 2_500);
            params.putArray("content").addObject().put("type", "text").put("text", "第一段");
            params.withArray("content").addObject().put("type", "text").put("text", "第二段");

            ObjectNode response = new TurnApprovalHandler(current)
                    .handle(new RpcCommand(RpcMethod.TURN_START, params))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            TurnStartRequest request = ((CapturingTurns) services.turns).request.get();
            assertEquals("thr_start", request.threadId());
            assertTrue(request.turnId().startsWith("turn_"));
            assertEquals("ws_start", request.workspaceId());
            assertEquals(services.workspaceRoot, request.workspaceRoot());
            assertEquals("第一段\n第二段", request.input());
            assertEquals(java.util.List.of(), request.attachmentIds());
            assertEquals("provider_start", request.providerId());
            assertEquals("model_start", request.modelId());
            assertEquals("medium", request.reasoningLevel());
            assertEquals(io.github.kongweiguang.ja.conversation.domain.permission.AccessMode.APPROVAL_REQUIRED,
                    request.accessMode());
            assertEquals(Duration.ofMillis(2_500), request.deadline());
            assertEquals(7, request.expectedThreadRevision());
            assertEquals(0, request.initialTurnMutationVersion());
            assertEquals(NOW, request.requestedAt());
            assertEquals(request.turnId(), response.path("turnId").textValue());
            assertEquals(8, response.path("threadRevision").longValue());
            assertTrue(response.path("accepted").booleanValue());
            assertTrue(response.path("queued").booleanValue());
        } finally {
            if (session != null) session.close();
            writer.close();
        }
    }

    /**
     * 自动标题可在 transport 读取 Thread 后推进 revision；turn/start 没有客户端 CAS 参数，
     * 因而必须在首次无副作用 admission 冲突后重读最新偏好，而不是把内部竞态暴露为随机失败。
     */
    @Test
    void turnStartRetriesOneAdmissionConflictAgainstFreshThreadRevision() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ThreadSummary first = new ThreadSummary(
                "thr_start", "ws_start", "占位标题", preferences("provider_start", "model_start"),
                ThreadSummary.Status.ACTIVE, 7, NOW.minusSeconds(60), NOW);
        ThreadSummary refreshed = new ThreadSummary(
                "thr_start", "ws_start", "自动标题", preferences("provider_latest", "model_latest"),
                ThreadSummary.Status.ACTIVE, 8, NOW.minusSeconds(60), NOW);
        RetryingTurns turns = new RetryingTurns();
        StartServices services = new StartServices(new SequencedStartThreads(first, refreshed), turns);
        StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024);
        RpcSession session = null;
        try {
            RpcSession current = new RpcSession(
                    testConfiguration(), mapper, CLOCK, writer,
                    ignored -> services.bindings(), TestConfigurationPorts.unavailable());
            session = current;
            current.initialize();
            markReady(current, "0123456789abcdef0123456789abcdef");
            ObjectNode params = mapper.createObjectNode().put("threadId", "thr_start");
            params.putArray("content").addObject().put("type", "text").put("text", "继续下一轮");

            ObjectNode response = new TurnApprovalHandler(current)
                    .handle(new RpcCommand(RpcMethod.TURN_START, params))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(2, turns.attempts.get());
            assertEquals(8, turns.request.get().expectedThreadRevision());
            assertEquals("provider_latest", turns.request.get().providerId());
            assertEquals("model_latest", turns.request.get().modelId());
            assertEquals(9, response.path("threadRevision").longValue());
        } finally {
            if (session != null) session.close();
            writer.close();
        }
    }

    /** 创建由审批请求与解决事实共享的不可变 revision 上下文。 */
    private static TurnEvent.Context context(String eventId, long revision) {
        return new TurnEvent.Context(eventId, "thr_test", "turn_test", revision, NOW);
    }

    /** 仅提供 approval/respond 使用的应用投影，其余端口均以显式失败关闭。 */
    private static final class ApprovalServices {
        private final AtomicReference<RpcSession> session = new AtomicReference<>();
        private final ThreadUseCase history = new ApprovalHistory();
        private final ApprovalUseCase approvals = new ApprovalUseCase() {
            /** 先发布 resolved 事实再返回成功，用于验证 Writer flush 与响应顺序。 */
            @Override
            public boolean resolve(String approvalId, ApprovalDecision response, Instant resolvedAt) {
                session.get().publish(new TurnEvent.ApprovalResolved(
                        context("evt_resolved", 4), approvalId, response));
                return true;
            }

        };

        /** 只组合审批与 Turn 查找端口，其余能力由测试拒绝实现保护。 */
        private RpcServiceBindings bindings() {
            return RpcTestBindings.create(null, history, null, approvals, null, null);
        }

        /** 最小权威查询为 respond 提供准确的 Thread revision CAS 投影。 */
        private static final class ApprovalHistory implements ThreadUseCase {
            /** 审批响应夹具不允许创建 Thread。 */
            @Override public ThreadSummary createThread(ThreadSummary.Creation request) { throw new UnsupportedOperationException(); }
            /** 审批响应夹具不允许列出 Thread。 */
            @Override public CursorPage<ThreadSummary> listThreads(String workspaceId, String cursor, int limit) { throw new UnsupportedOperationException(); }
            /** 审批响应夹具不允许搜索 Thread。 */
            @Override public CursorPage<ThreadSummary> searchThreads(String workspaceId, String query, String cursor, int limit) { throw new UnsupportedOperationException(); }
            /** 审批响应夹具不提供完整 Thread 内容读取。 */
            @Override public Optional<ThreadSnapshot> readThread(String threadId, String cursor, int limit) { throw new UnsupportedOperationException(); }
            /** 审批响应夹具不允许重命名 Thread。 */
            @Override public ThreadSummary renameThread(String threadId, String title, long revision) { throw new UnsupportedOperationException(); }
            /** 审批响应夹具不允许改写下一轮偏好。 */
            @Override public ThreadSummary updatePreferences(String threadId, io.github.kongweiguang.ja.conversation.domain.ThreadPreferences value, long revision) { throw new UnsupportedOperationException(); }
            /** 审批响应夹具不运行自动标题竞争。 */
            @Override public boolean writeAutomaticTitle(String threadId, String title, long revision) { throw new UnsupportedOperationException(); }
            /** 审批响应夹具不允许归档 Thread。 */
            @Override public void archiveThread(String threadId, long expectedThreadRevision) { throw new UnsupportedOperationException(); }
            /** 审批响应夹具不允许删除 Thread。 */
            @Override public void deleteThread(String threadId, long expectedThreadRevision) { throw new UnsupportedOperationException(); }

            /** 返回响应关联与 CAS 使用的唯一 waiting_approval 快照。 */
            @Override
            public Optional<TurnSummary> findTurn(String turnId) {
                return Optional.of(new TurnSummary("thr_test", turnId, "waiting_approval", 3));
            }
        }
    }

    /** 组合 turn/start 唯一允许触达的工作区、Thread 与 Turn 入站端口。 */
    private static final class StartServices {
        private final Path workspaceRoot = Path.of(System.getProperty("java.io.tmpdir"), "ja-rpc-start-workspace")
                .toAbsolutePath().normalize();
        private final Workspace workspace = new Workspace(
                "ws_start", workspaceRoot, "启动测试", Workspace.Trust.TRUSTED, 4);
        private final ThreadSummary thread = new ThreadSummary(
                "thr_start", "ws_start", "启动测试",
                preferences("provider_start", "model_start"),
                ThreadSummary.Status.ACTIVE, 7, NOW.minusSeconds(60), NOW);
        private final ThreadUseCase threads;
        private final TurnUseCase turns;

        /** 默认夹具保持单次固定快照和捕获型 Turn 端口。 */
        private StartServices() {
            this.threads = new StartThreads(thread);
            this.turns = new CapturingTurns();
        }

        /** 允许竞态回归注入会推进 revision 的 Thread 投影与一次冲突的 Turn 端口。 */
        private StartServices(ThreadUseCase threads, TurnUseCase turns) {
            this.threads = threads;
            this.turns = turns;
        }

        /** 通过拒绝型测试工厂组合三个明确端口，不引入 Runtime Resolver 测试替身。 */
        private RpcServiceBindings bindings() {
            return RpcTestBindings.create(new StartWorkspaces(workspace), threads,
                    turns, null, null, null);
        }
    }

    /** 仅允许按已打开身份取得固定工作区，任何其他工作区操作均失败。 */
    private static final class StartWorkspaces implements WorkspaceUseCase {
        private final Workspace workspace;

        /** 固定预先打开的工作区，使测试不会产生文件系统副作用。 */
        private StartWorkspaces(Workspace workspace) {
            this.workspace = workspace;
        }

        /** 未声明工作区打开能力。 */
        @Override public Workspace openWorkspace(OpenWorkspace command) { throw unsupported(); }
        /** 未声明通用工作区打开能力。 */
        @Override public Workspace openGeneralWorkspace() { throw unsupported(); }
        /** 未声明工作区列表能力。 */
        @Override public CursorPage<Workspace> listWorkspaces(String cursor, int limit) { throw unsupported(); }
        /** 未声明可选工作区读取能力。 */
        @Override public Optional<Workspace> readWorkspace(String workspaceId) { throw unsupported(); }
        /** 仅返回与 Thread 关联且已经打开的工作区。 */
        @Override public Workspace requireOpenWorkspace(String workspaceId) {
            if (!workspace.workspaceId().equals(workspaceId)) throw unsupported();
            return workspace;
        }
        /** 未声明信任状态修改能力。 */
        @Override public Workspace setWorkspaceTrust(String workspaceId, Workspace.Trust trust) { throw unsupported(); }
        /** 未声明工作区注销能力。 */
        @Override public void unregisterWorkspace(String workspaceId, long expectedRevision) { throw unsupported(); }
        /** 未声明预热刷新能力。 */
        @Override public void refreshPreparedWorkspaces() { throw unsupported(); }
        /** 未声明通用工作区判定能力。 */
        @Override public boolean isGeneralWorkspace(Path root) { throw unsupported(); }
    }

    /** 仅发布 turn/start 所需的固定 Thread 快照。 */
    private static final class StartThreads implements ThreadUseCase {
        private final ThreadSummary thread;

        /** 固定 Thread 权威投影，避免测试从 Wire 参数推导 Profile 或工作区。 */
        private StartThreads(ThreadSummary thread) {
            this.thread = thread;
        }

        /** 未声明 Thread 创建能力。 */
        @Override public ThreadSummary createThread(ThreadSummary.Creation request) { throw unsupported(); }
        /** 未声明 Thread 列表能力。 */
        @Override public CursorPage<ThreadSummary> listThreads(String workspaceId, String cursor, int limit) { throw unsupported(); }
        /** 未声明 Thread 搜索能力。 */
        @Override public CursorPage<ThreadSummary> searchThreads(String workspaceId, String query, String cursor, int limit) { throw unsupported(); }
        /** 返回启动请求引用的唯一 Thread 快照。 */
        @Override public Optional<ThreadSnapshot> readThread(String threadId, String cursor, int limit) {
            return thread.threadId().equals(threadId)
                    ? Optional.of(new ThreadSnapshot(thread, List.of(), List.of(), null, null)) : Optional.empty();
        }
        /** 未声明 Thread 重命名能力。 */
        @Override public ThreadSummary renameThread(String threadId, String title, long revision) { throw unsupported(); }
        /** 未声明下一轮偏好修改能力。 */
        @Override public ThreadSummary updatePreferences(String threadId, io.github.kongweiguang.ja.conversation.domain.ThreadPreferences value, long revision) { throw unsupported(); }
        /** 未声明自动标题写入能力。 */
        @Override public boolean writeAutomaticTitle(String threadId, String title, long revision) { throw unsupported(); }
        /** 未声明 Thread 归档能力。 */
        @Override public void archiveThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 未声明 Thread 删除能力。 */
        @Override public void deleteThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 未声明 Turn 查找能力。 */
        @Override public Optional<TurnSummary> findTurn(String turnId) { throw unsupported(); }
    }

    /** 捕获 transport 交付的纯启动意图，并返回不触发异步终态的接纳回执。 */
    private static final class CapturingTurns implements TurnUseCase {
        private final AtomicReference<TurnStartRequest> request = new AtomicReference<>();

        /** 保存唯一启动请求；未完成 Future 使测试专注准入边界而非执行生命周期。 */
        @Override
        public Accepted start(TurnStartRequest request, TurnEventSink sink) {
            if (!this.request.compareAndSet(null, request)) throw unsupported();
            return new Accepted(request.threadId(), request.turnId(),
                    request.expectedThreadRevision() + 1, true, new CompletableFuture<>());
        }

        /** 未声明取消能力。 */
        @Override public CancelResult cancel(String turnId, long expectedThreadRevision) { throw unsupported(); }
        /** 关闭测试会话时停止准入，无需改变已捕获请求。 */
        @Override public void stopAccepting() { }
        /** 测试没有执行中 Turn，因此立即满足静默等待。 */
        @Override public boolean awaitQuiescence(Duration timeout) { return true; }
        /** 测试端口不持有资源，绝对期限关闭为空操作。 */
        @Override public void closeAt(long shutdownDeadlineNanos) { }
        /** 测试端口不持有资源，普通关闭为空操作。 */
        @Override public void close() { }
    }

    /** 每次读取依次返回旧、新 Thread，使 admission 竞态无需 sleep 即可确定复现。 */
    private static final class SequencedStartThreads implements ThreadUseCase {
        private final ThreadSummary first;
        private final ThreadSummary refreshed;
        private final AtomicInteger reads = new AtomicInteger();

        /** 两个快照仅允许 revision 和下一轮偏好随权威提交推进。 */
        private SequencedStartThreads(ThreadSummary first, ThreadSummary refreshed) {
            this.first = first;
            this.refreshed = refreshed;
        }

        /** 首次返回读后即过期的投影，重试返回自动标题提交后的最新投影。 */
        @Override public Optional<ThreadSnapshot> readThread(String threadId, String cursor, int limit) {
            ThreadSummary selected = reads.getAndIncrement() == 0 ? first : refreshed;
            return Optional.of(new ThreadSnapshot(selected, List.of(), List.of(), null, null));
        }
        /** 竞态夹具不创建 Thread。 */
        @Override public ThreadSummary createThread(ThreadSummary.Creation request) { throw unsupported(); }
        /** 竞态夹具不列出 Thread。 */
        @Override public CursorPage<ThreadSummary> listThreads(String workspaceId, String cursor, int limit) { throw unsupported(); }
        /** 竞态夹具不搜索 Thread。 */
        @Override public CursorPage<ThreadSummary> searchThreads(String workspaceId, String query, String cursor, int limit) { throw unsupported(); }
        /** 竞态夹具不重命名 Thread。 */
        @Override public ThreadSummary renameThread(String threadId, String title, long revision) { throw unsupported(); }
        /** 竞态夹具不更新偏好。 */
        @Override public ThreadSummary updatePreferences(String threadId, io.github.kongweiguang.ja.conversation.domain.ThreadPreferences value, long revision) { throw unsupported(); }
        /** 竞态夹具不写自动标题。 */
        @Override public boolean writeAutomaticTitle(String threadId, String title, long revision) { throw unsupported(); }
        /** 竞态夹具不归档。 */
        @Override public void archiveThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 竞态夹具不删除。 */
        @Override public void deleteThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 竞态夹具不读取 Turn。 */
        @Override public Optional<TurnSummary> findTurn(String turnId) { throw unsupported(); }
    }

    /** 第一次 admission 以存储 CAS 冲突失败，第二次捕获重读后的完整请求。 */
    private static final class RetryingTurns implements TurnUseCase {
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicReference<TurnStartRequest> request = new AtomicReference<>();

        /** 仅第一次在持久副作用前失败，确保 handler 的有界重试不会重放已接纳 Turn。 */
        @Override public Accepted start(TurnStartRequest candidate, TurnEventSink sink) {
            if (attempts.incrementAndGet() == 1) {
                throw new StorageException(StorageException.Code.CAS_CONFLICT, "fixture admission conflict");
            }
            request.set(candidate);
            return new Accepted(candidate.threadId(), candidate.turnId(),
                    candidate.expectedThreadRevision() + 1, true, new CompletableFuture<>());
        }
        /** 竞态夹具不取消 Turn。 */
        @Override public CancelResult cancel(String turnId, long expectedThreadRevision) { throw unsupported(); }
        /** 关闭测试会话时不再接纳。 */
        @Override public void stopAccepting() { }
        /** 没有执行中 Turn，立即静默。 */
        @Override public boolean awaitQuiescence(Duration timeout) { return true; }
        /** 测试端口无资源。 */
        @Override public void closeAt(long shutdownDeadlineNanos) { }
        /** 测试端口无资源。 */
        @Override public void close() { }
    }

    /** 为测试中所有越界端口调用生成统一失败，避免误把空操作当作行为覆盖。 */
    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("test capability is not configured");
    }
}

