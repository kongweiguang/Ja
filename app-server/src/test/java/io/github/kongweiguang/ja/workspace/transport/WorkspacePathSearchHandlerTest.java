// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.TurnSummary;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.workspace.port.in.WorkspacePathSearchUseCase;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 验证路径搜索 Handler 的可选分页、Thread 身份与跨 Workspace 失败关闭。 */
final class WorkspacePathSearchHandlerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 省略 limit 使用 50 上限，并由服务端回传可信 runtime generation。 */
    @Test
    void omitsLimitWithBoundedDefault() {
        RecordingThreads threads = new RecordingThreads(snapshot("ws_test"));
        AtomicReference<WorkspacePathSearchUseCase.SearchRequest> observed = new AtomicReference<>();
        WorkspacePathSearchHandler handler = handler(threads, request -> {
            observed.set(request);
            return new WorkspacePathSearchUseCase.SearchResult(
                    request.threadId(), request.workspaceId(), request.runtimeGeneration(),
                    request.query(), List.of(), false);
        });
        ObjectNode params = MAPPER.createObjectNode()
                .put("threadId", "thr_test")
                .put("workspaceId", "ws_test")
                .put("query", "main");

        ObjectNode result = handler.handle(new RpcCommand(
                RpcMethod.WORKSPACE_PATH_SEARCH, params)).toCompletableFuture().join();

        assertEquals(50, observed.get().limit());
        assertEquals(41, observed.get().runtimeGeneration());
        assertEquals("thr_test", result.path("threadId").textValue());
        assertEquals(41, result.path("generation").longValue());
    }

    /** RpcParams 的通用 200 上限仍由 Handler 收窄到产品固定的 50 项。 */
    @Test
    void clampsSharedPageLimitToPathMaximum() {
        RecordingThreads threads = new RecordingThreads(snapshot("ws_test"));
        AtomicReference<WorkspacePathSearchUseCase.SearchRequest> observed = new AtomicReference<>();
        WorkspacePathSearchHandler handler = handler(threads, request -> {
            observed.set(request);
            return new WorkspacePathSearchUseCase.SearchResult(
                    request.threadId(), request.workspaceId(), request.runtimeGeneration(),
                    request.query(), List.of(), false);
        });
        ObjectNode params = MAPPER.createObjectNode()
                .put("threadId", "thr_test")
                .put("workspaceId", "ws_test")
                .put("query", "")
                .put("limit", 200);

        handler.handle(new RpcCommand(RpcMethod.WORKSPACE_PATH_SEARCH, params));

        assertEquals(50, observed.get().limit());
    }

    /** 错误 Thread 前缀在读取 Thread 前即作为 INVALID_PARAMS 拒绝。 */
    @Test
    void rejectsLegacyThreadPrefixBeforeLookup() {
        RecordingThreads threads = new RecordingThreads(snapshot("ws_test"));
        WorkspacePathSearchHandler handler = handler(threads, request -> {
            throw new AssertionError("search must not run");
        });
        ObjectNode params = MAPPER.createObjectNode()
                .put("threadId", "thread_test")
                .put("workspaceId", "ws_test")
                .put("query", "");

        JaRpcException failure = assertThrows(JaRpcException.class,
                () -> handler.handle(new RpcCommand(RpcMethod.WORKSPACE_PATH_SEARCH, params)));

        assertEquals("INVALID_PARAMS", failure.errorCode());
        assertEquals(0, threads.readCalls.get());
    }

    /** Thread 的权威 Workspace 不一致时不得调用路径服务或泄露另一 Workspace 是否存在。 */
    @Test
    void rejectsCrossWorkspaceSearchBeforeFilesystemAccess() {
        RecordingThreads threads = new RecordingThreads(snapshot("ws_authoritative"));
        AtomicInteger searchCalls = new AtomicInteger();
        WorkspacePathSearchHandler handler = handler(threads, request -> {
            searchCalls.incrementAndGet();
            throw new AssertionError("search must not run");
        });
        ObjectNode params = MAPPER.createObjectNode()
                .put("threadId", "thr_test")
                .put("workspaceId", "ws_other")
                .put("query", "");

        JaRpcException failure = assertThrows(JaRpcException.class,
                () -> handler.handle(new RpcCommand(RpcMethod.WORKSPACE_PATH_SEARCH, params)));

        assertEquals("WORKSPACE_CONFINEMENT", failure.errorCode());
        assertEquals(0, searchCalls.get());
    }

    /** 测试构造通过窄 seam 注入权威 Thread、路径用例和固定服务端代际。 */
    private static WorkspacePathSearchHandler handler(
            ThreadUseCase threads, WorkspacePathSearchUseCase searches) {
        return new WorkspacePathSearchHandler(
                () -> { }, MAPPER, () -> threads, () -> searches, () -> 41);
    }

    /** 构造最小事务一致 Thread 快照，避免 Handler 测试依赖 SQLite。 */
    private static ThreadSnapshot snapshot(String workspaceId) {
        Instant now = Instant.parse("2026-09-03T00:00:00Z");
        ThreadPreferences preferences = new ThreadPreferences(
                "provider_test", "model_test", "medium", AccessMode.APPROVAL_REQUIRED,
                CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.PLACEHOLDER);
        ThreadSummary thread = new ThreadSummary(
                "thr_test", workspaceId, "Test", preferences, ThreadSummary.Status.ACTIVE,
                false, null, true, null, 0, now, now);
        return new ThreadSnapshot(thread, List.of(), List.of(), null, null, null);
    }

    /** Thread fake 只允许读取单个权威快照，并记录参数失败是否提前发生。 */
    private static final class RecordingThreads implements ThreadUseCase {
        private final ThreadSnapshot snapshot;
        private final AtomicInteger readCalls = new AtomicInteger();

        /** 冻结当前测试的唯一 Thread。 */
        private RecordingThreads(ThreadSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        /** 本测试不创建 Thread。 */
        @Override public ThreadSummary createThread(ThreadSummary.Creation request) { throw unsupported(); }
        /** 本测试不列出 Thread。 */
        @Override public CursorPage<ThreadSummary> listThreads(
                String workspaceId, String cursor, int limit) { throw unsupported(); }
        /** 本测试不搜索标题。 */
        @Override public CursorPage<ThreadSummary> searchThreads(
                String workspaceId, String query, String cursor, int limit) { throw unsupported(); }
        /** 返回唯一权威快照并记录实际查找次数。 */
        @Override public Optional<ThreadSnapshot> readThread(String threadId, String cursor, int limit) {
            readCalls.incrementAndGet();
            return snapshot.thread().threadId().equals(threadId) ? Optional.of(snapshot) : Optional.empty();
        }
        /** 本测试不重命名 Thread。 */
        @Override public ThreadSummary renameThread(
                String threadId, String title, long revision) { throw unsupported(); }
        /** 本测试不更新偏好。 */
        @Override public ThreadSummary updatePreferences(String threadId,
                io.github.kongweiguang.ja.conversation.domain.ThreadPreferences preferences,
                long revision) { throw unsupported(); }
        /** 本测试不写入自动标题。 */
        @Override public boolean writeAutomaticTitle(
                String threadId, String title, long revision) { throw unsupported(); }
        /** 本测试不归档 Thread。 */
        @Override public ThreadSummary archiveThread(
                String threadId, long revision) { throw unsupported(); }
        /** 本测试不删除 Thread。 */
        @Override public void deleteThread(String threadId, long revision) { throw unsupported(); }
        /** 本测试不查找 Turn。 */
        @Override public Optional<TurnSummary> findTurn(String turnId) { throw unsupported(); }
    }

    /** 未进入本测试职责的方法必须显式失败，避免空 fake 掩盖调用漂移。 */
    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("test operation is unsupported");
    }
}
