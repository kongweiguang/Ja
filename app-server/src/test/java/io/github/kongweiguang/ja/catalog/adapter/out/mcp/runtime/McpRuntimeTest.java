// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.runtime;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.session.McpSession;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.session.McpSessionFactory;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpDeadline;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpLimits;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpServerDefinition;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.conversation.port.out.McpGateway;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.foundation.json.JsonValue;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 覆盖独立于 Provider 的资源边界与生命周期，避免测试依赖 SDK 内部实现。 */
final class McpRuntimeTest {
    /** 验证手动遍历 Cursor 会保留全部页面并生成稳定、确定的版本。 */
    @Test
    void snapshotUsesCursorPagesAndStableNamespace() {
        FakeSession session = new FakeSession(Map.of(
                "<first>", new McpSession.ToolPage(List.of(tool("read-file")), "page-2"),
                "page-2", new McpSession.ToolPage(List.of(tool("write file")), null)));
        try (McpRuntime runtime = runtime(
                List.of(definition("local")), McpLimits.DEFAULT, (ignored, ignoredDeadline) -> session)) {
            McpGateway.McpSnapshot first = runtime.snapshot();
            McpGateway.McpSnapshot second = runtime.snapshot();
            assertEquals(first.revision(), second.revision());
            assertEquals(List.of("<first>", "page-2"), session.cursors);
            assertEquals(2, first.tools().size());
            assertEquals("mcp:local:read-file", first.tools().getFirst().spec().name());
            assertTrue(first.tools().get(1).spec().name().startsWith("mcp:local:write_file-"));
        }
    }

    /** 验证 Runtime 启用规范 Map 排序后，请求级 workspace 版本仍然有效。 */
    @Test
    void requestSnapshotRevisionIgnoresMapperMapOrderingConfiguration() {
        ObjectMapper discoveryMapper = new ObjectMapper();
        McpGateway.McpTool tool = new McpGateway.McpTool(
                "local",
                McpToolCatalog.encodeRemoteName("echo"),
                new ToolSpec(
                        "mcp:local:echo",
                        "echo",
                        JsonObjects.builder()
                                .put("required", new JsonArray(List.of(new JsonText("text"))))
                                .putText("type", "object")
                                .put("properties", JsonObjects.builder()
                                        .put("text", JsonObjects.builder().putNumber("minLength", 1)
                                                .putText("type", "string").build())
                                        .build())
                                .build()));
        McpGateway.McpSnapshot snapshot = McpToolCatalog.snapshot(
                List.of(tool), Map.of("local", definition("local")), discoveryMapper, Instant.EPOCH);
        ObjectMapper turnMapper = discoveryMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

        assertEquals(snapshot, McpToolCatalog.validateSnapshot(
                snapshot, turnMapper, Map.of("local", definition("local"))));
    }

    /** 缺失服务定义时不能伪造 revision，否则无法证明缓存 Tool 的真实传输与认证身份。 */
    @Test
    void snapshotRejectsMissingServerDefinition() {
        McpGateway.McpTool tool = new McpGateway.McpTool(
                "missing",
                McpToolCatalog.encodeRemoteName("echo"),
                new ToolSpec("mcp:missing:echo", "echo",
                        JsonObjects.builder().putText("type", "object").build()));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> McpToolCatalog.snapshot(
                        List.of(tool), Map.of(), new ObjectMapper(), Instant.EPOCH));

        assertEquals("mcp_route_definition_missing", failure.getMessage());
    }

    /** 相同 server/tool/schema 切换私密启动语义时，definition、route 与 catalog revision 都必须变化。 */
    @Test
    void routeIdentityIncludesHashedServerDefinition() {
        Path cwd = Path.of(".").toAbsolutePath().normalize();
        McpServerDefinition first = McpServerDefinition.stdio(
                "identity", List.of("fixture"), cwd, Map.of("TOKEN", "first-secret"),
                List.of("2025-06-18"));
        McpServerDefinition second = McpServerDefinition.stdio(
                "identity", List.of("fixture"), cwd, Map.of("TOKEN", "second-secret"),
                List.of("2025-06-18"));
        FakeSession firstSession = oneToolSession("echo");
        FakeSession secondSession = oneToolSession("echo");
        try (McpRuntime firstRuntime = runtime(List.of(first), McpLimits.DEFAULT,
                (ignored, ignoredDeadline) -> firstSession);
             McpRuntime secondRuntime = runtime(List.of(second), McpLimits.DEFAULT,
                     (ignored, ignoredDeadline) -> secondSession)) {
            McpGateway.McpSnapshot firstSnapshot = firstRuntime.snapshot();
            McpGateway.McpSnapshot secondSnapshot = secondRuntime.snapshot();
            var firstRoute = firstRuntime.routeIdentities(firstSnapshot).values().iterator().next();
            var secondRoute = secondRuntime.routeIdentities(secondSnapshot).values().iterator().next();

            assertFalse(first.definitionRevision().equals(second.definitionRevision()));
            assertFalse(firstRoute.routeHash().equals(secondRoute.routeHash()));
            assertFalse(firstSnapshot.revision().equals(secondSnapshot.revision()));
            assertFalse((firstRoute + " " + secondRoute).contains("secret"));
        }
    }

    /** 验证重复 Cursor 会隔离对应服务并立即关闭其 Session。 */
    @Test
    void paginationLoopClosesServer() {
        FakeSession session = new FakeSession(Map.of(
                "<first>", new McpSession.ToolPage(List.of(tool("one")), "again"),
                "again", new McpSession.ToolPage(List.of(tool("two")), "again")));
        try (McpRuntime runtime = runtime(
                List.of(definition("loop")), McpLimits.DEFAULT, (ignored, ignoredDeadline) -> session)) {
            assertTrue(runtime.snapshot().tools().isEmpty());
            assertEquals(1, session.closeCount.get());
        }
    }

    /** 验证 Tool 或 Schema 洪泛只隔离失败服务，不暴露部分目录。 */
    @Test
    void toolFloodIsRejectedAtomically() {
        McpLimits limits = limits(2, 1024 * 1024, Duration.ofSeconds(2));
        FakeSession session = new FakeSession(Map.of("<first>", new McpSession.ToolPage(
                List.of(tool("one"), tool("two"), tool("three")), null)));
        try (McpRuntime runtime = runtime(
                List.of(definition("flood")), limits, (ignored, ignoredDeadline) -> session)) {
            assertTrue(runtime.snapshot().tools().isEmpty());
            assertEquals(1, session.closeCount.get());
        }
    }

    /** 验证跨页原始名称重复属于冲突，不允许以最后写入获胜造成目录漂移。 */
    @Test
    void duplicateRemoteNameIsRejected() {
        FakeSession session = new FakeSession(Map.of("<first>", new McpSession.ToolPage(
                List.of(tool("same"), tool("same")), null)));
        try (McpRuntime runtime = runtime(
                List.of(definition("duplicate")), McpLimits.DEFAULT, (ignored, ignoredDeadline) -> session)) {
            assertTrue(runtime.snapshot().tools().isEmpty());
        }
    }

    /** 一个 MCP 目录失败时仍返回其它健康服务，避免拖垮内建 Tool 与完整请求。 */
    @Test
    void discoveryFailureIsIsolatedPerServer() {
        FakeSession healthy = oneToolSession("healthy");
        FakeSession broken = new FakeSession(Map.of());
        McpSessionFactory factory = (definition, ignoredDeadline) ->
                definition.id().equals("healthy") ? healthy : broken;
        try (McpRuntime runtime = runtime(
                List.of(definition("healthy"), definition("broken")), McpLimits.DEFAULT, factory)) {
            McpGateway.McpSnapshot snapshot = runtime.snapshot();
            assertEquals(1, snapshot.tools().size());
            assertEquals("mcp:healthy:healthy", snapshot.tools().getFirst().spec().name());
            assertEquals(0, healthy.closeCount.get());
            assertEquals(1, broken.closeCount.get());
        }
    }

    /** 原始 list_changed 只标脏；旧 batch 在调用前重拉并因 schema 变化稳定失败，绝不执行同名 Tool。 */
    @Test
    void dirtyDirectoryRejectsChangedRouteBeforeInvocation() throws Exception {
        FakeSession session = oneToolSession("echo");
        AtomicReference<Runnable> notifier = new AtomicReference<>();
        McpSessionFactory factory = new McpSessionFactory() {
            /** 兼容测试工厂的基础入口。 */
            @Override
            public McpSession open(McpServerDefinition definition, McpDeadline deadline) {
                return session;
            }

            /** 捕获生产目录的无阻塞通知入口。 */
            @Override
            public McpSession open(
                    McpServerDefinition definition, McpDeadline deadline, Runnable toolsChanged) {
                notifier.set(toolsChanged);
                return session;
            }
        };
        try (McpRuntime runtime = runtime(List.of(definition("dynamic")), McpLimits.DEFAULT, factory)) {
            McpGateway.McpSnapshot original = runtime.snapshot();
            session.pages = Map.of("<first>", new McpSession.ToolPage(
                    List.of(tool("echo", "changed")), null));
            notifier.get().run();

            McpGateway.McpResult result = runtime.invoke(
                    original,
                    invocation("stale-call", original.tools().getFirst().spec().name(), 0),
                    CancellationToken.none()).toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(new JsonText("tool_binding_unavailable"), structuredMember(result, "category"));
            assertEquals(0, session.callCount.get());
            assertEquals(List.of("<first>", "<first>"), session.cursors);
        }
    }

    /** 验证不同模型调用并发到达时，同一服务上的调用仍不得重叠。 */
    @Test
    void oneServerHasOneActiveCall() throws Exception {
        FakeSession session = new FakeSession(Map.of(
                "<first>", new McpSession.ToolPage(List.of(tool("echo")), null)));
        session.blockCalls = true;
        try (McpRuntime runtime = runtime(
                List.of(definition("serial")), McpLimits.DEFAULT, (ignored, ignoredDeadline) -> session)) {
            McpGateway.McpSnapshot snapshot = runtime.snapshot();
            String name = snapshot.tools().getFirst().spec().name();
            CompletableFuture<McpGateway.McpResult> first = runtime.invoke(
                    snapshot, invocation("call-1", name, 0), CancellationToken.none()).toCompletableFuture();
            assertTrue(session.callEntered.await(1, TimeUnit.SECONDS));
            CompletableFuture<McpGateway.McpResult> second = runtime.invoke(
                    snapshot, invocation("call-2", name, 1), CancellationToken.none()).toCompletableFuture();
            assertFalse(second.isDone());
            session.releaseCalls();
            CompletableFuture.allOf(first, second).get(3, TimeUnit.SECONDS);
            assertEquals(1, session.maximumActive.get());
            assertTrue(session.callVirtual.get());
            assertEquals(ToolOutcome.SUCCEEDED, first.get().outcome());
        }
    }

    /** 验证取消会关闭整个 Session，并在清理赢得竞争后报告 CANCELLED。 */
    @Test
    void cancellationClosesServerSession() throws Exception {
        FakeSession session = new FakeSession(Map.of(
                "<first>", new McpSession.ToolPage(List.of(tool("slow")), null)));
        session.blockCalls = true;
        TestToken token = new TestToken();
        try (McpRuntime runtime = runtime(
                List.of(definition("cancel")), McpLimits.DEFAULT, (ignored, ignoredDeadline) -> session)) {
            McpGateway.McpSnapshot snapshot = runtime.snapshot();
            CompletableFuture<McpGateway.McpResult> result = runtime.invoke(
                    snapshot,
                    invocation("cancel-call", snapshot.tools().getFirst().spec().name(), 0),
                    token).toCompletableFuture();
            assertTrue(session.callEntered.await(1, TimeUnit.SECONDS));
            token.cancel();
            assertEquals(ToolOutcome.CANCELLED, result.get(2, TimeUnit.SECONDS).outcome());
            assertTrue(session.closeCount.get() >= 1);
            assertTrue(session.closeVirtual.get());
        }
    }

    /** 验证超大结果映射为稳定失败；不确定副作用不再扩展公共 outcome 闭集。 */
    @Test
    void resultLimitClosesSessionAndIsUnknown() throws Exception {
        FakeSession session = new FakeSession(Map.of(
                "<first>", new McpSession.ToolPage(List.of(tool("large")), null)));
        session.result = new McpSession.RemoteResult(false, "x".repeat(4096), Optional.of(JsonObject.empty()));
        McpLimits limits = limits(10, 1024, Duration.ofSeconds(2));
        try (McpRuntime runtime = runtime(
                List.of(definition("large")), limits, (ignored, ignoredDeadline) -> session)) {
            McpGateway.McpSnapshot snapshot = runtime.snapshot();
            McpGateway.McpResult result = runtime.invoke(
                    snapshot,
                    invocation("large-call", snapshot.tools().getFirst().spec().name(), 0),
                    CancellationToken.none()).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(ToolOutcome.FAILED, result.outcome());
            assertEquals(new JsonText("result_limit"), structuredMember(result, "category"));
            assertTrue(session.closeCount.get() >= 1);
        }
    }

    /** 验证请求 Deadline 会关闭 Session，而不是依赖 SDK 超时取消传输 IO。 */
    @Test
    void timeoutClosesWholeSession() throws Exception {
        FakeSession session = new FakeSession(Map.of(
                "<first>", new McpSession.ToolPage(List.of(tool("slow")), null)));
        session.blockCalls = true;
        McpLimits limits = limits(10, 1024 * 1024, Duration.ofMillis(50));
        try (McpRuntime runtime = runtime(
                List.of(definition("timeout")), limits, (ignored, ignoredDeadline) -> session)) {
            McpGateway.McpSnapshot snapshot = runtime.snapshot();
            McpGateway.McpResult result = runtime.invoke(
                    snapshot,
                    invocation("timeout-call", snapshot.tools().getFirst().spec().name(), 0),
                    CancellationToken.none()).toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(ToolOutcome.FAILED, result.outcome());
            assertEquals(new JsonText("timeout"), structuredMember(result, "category"));
            assertTrue(session.closeCount.get() >= 1);
        }
    }

    /** 验证服务崩溃映射为稳定失败，详细不确定性只保留在错误类别。 */
    @Test
    void serverCrashIsUnknownAndClosesSession() throws Exception {
        FakeSession session = new FakeSession(Map.of(
                "<first>", new McpSession.ToolPage(List.of(tool("crash")), null)));
        session.callFailure = new IllegalStateException("fixture_crash");
        try (McpRuntime runtime = runtime(
                List.of(definition("crash")), McpLimits.DEFAULT, (ignored, ignoredDeadline) -> session)) {
            McpGateway.McpSnapshot snapshot = runtime.snapshot();
            McpGateway.McpResult result = runtime.invoke(
                    snapshot,
                    invocation("crash-call", snapshot.tools().getFirst().spec().name(), 0),
                    CancellationToken.none()).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(ToolOutcome.FAILED, result.outcome());
            assertEquals(new JsonText("transport_failure"), structuredMember(result, "category"));
            assertTrue(session.closeCount.get() >= 1);
        }
    }

    /** 验证发现过程跨服务共享一个 Deadline，重试只能使用新打开的 Session。 */
    @Test
    void snapshotSharesAbsoluteDeadlineAndDoesNotReuseTimedOutSessions() {
        FakeTicker ticker = new FakeTicker();
        FakeSession alphaFirst = oneToolSession("alpha");
        FakeSession betaFirst = oneToolSession("beta");
        FakeSession alphaSecond = oneToolSession("alpha");
        FakeSession betaSecond = oneToolSession("beta");
        alphaFirst.listAction = () -> ticker.advance(Duration.ofMillis(600));
        betaFirst.listAction = () -> ticker.advance(Duration.ofMillis(600));
        AtomicInteger alphaOpens = new AtomicInteger();
        AtomicInteger betaOpens = new AtomicInteger();
        McpSessionFactory factory = (definition, ignoredDeadline) -> {
            if (definition.id().equals("alpha")) {
                return alphaOpens.getAndIncrement() == 0 ? alphaFirst : alphaSecond;
            }
            return betaOpens.getAndIncrement() == 0 ? betaFirst : betaSecond;
        };
        McpLimits limits = lifecycleLimits(
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));
        try (McpRuntime runtime = runtime(
                List.of(definition("alpha"), definition("beta")), limits, factory, ticker)) {
            McpGateway.McpSnapshot partial = runtime.snapshot();
            assertEquals(1, partial.tools().size());

            McpGateway.McpSnapshot recovered = runtime.snapshot();
            assertEquals(2, recovered.tools().size());
            assertEquals(1, alphaOpens.get());
            assertEquals(2, betaOpens.get());
        }
    }

    /** 验证启动初始化的超时不会随配置服务数量倍增。 */
    @Test
    void initializeSessionsSharesAbsoluteDeadline() {
        FakeTicker ticker = new FakeTicker();
        FakeSession firstInitial = oneToolSession("first");
        FakeSession secondInitial = oneToolSession("second");
        FakeSession firstRecovery = oneToolSession("first");
        FakeSession secondRecovery = oneToolSession("second");
        firstInitial.initializeAction = () -> ticker.advance(Duration.ofMillis(600));
        secondInitial.initializeAction = () -> ticker.advance(Duration.ofMillis(600));
        AtomicInteger firstOpens = new AtomicInteger();
        AtomicInteger secondOpens = new AtomicInteger();
        McpSessionFactory factory = (definition, ignoredDeadline) -> {
            if (definition.id().equals("first")) {
                return firstOpens.getAndIncrement() == 0 ? firstInitial : firstRecovery;
            }
            return secondOpens.getAndIncrement() == 0 ? secondInitial : secondRecovery;
        };
        McpLimits limits = lifecycleLimits(
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));
        try (McpRuntime runtime = runtime(
                List.of(definition("first"), definition("second")), limits, factory, ticker)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, runtime::initializeSessions);
            assertEquals("mcp_initialize_timeout", failure.getMessage());
            runtime.initializeSessions();
            assertEquals(2, firstOpens.get());
            assertEquals(2, secondOpens.get());
        }
    }

    /** 验证关闭过程聚合脱敏失败，并向后续调用方重放相同结果。 */
    @Test
    void closeAggregatesSanitizedFailuresAndIsIdempotent() {
        FakeSession first = oneToolSession("first");
        FakeSession second = oneToolSession("second");
        first.closeFailure = new IllegalStateException("first-secret https://private.example/body");
        second.closeFailure = new IllegalStateException("second-secret Authorization: bearer");
        McpSessionFactory factory = (definition, ignoredDeadline) ->
                definition.id().equals("first") ? first : second;
        McpRuntime runtime = runtime(
                List.of(definition("first"), definition("second")), McpLimits.DEFAULT, factory);
        runtime.initializeSessions();

        IllegalStateException failure = assertThrows(IllegalStateException.class, runtime::close);
        assertEquals("mcp_runtime_close_failed", failure.getMessage());
        assertEquals(2, failure.getSuppressed().length);
        for (Throwable suppressed : failure.getSuppressed()) {
            assertEquals("mcp_session_close_failed", suppressed.getMessage());
        }
        String rendered = failure + List.of(failure.getSuppressed()).toString();
        assertFalse(rendered.contains("secret"));
        assertFalse(rendered.contains("private.example"));
        assertFalse(rendered.contains("Authorization"));
        IllegalStateException repeated = assertThrows(IllegalStateException.class, runtime::close);
        assertEquals(suppressedMessages(failure), suppressedMessages(repeated));
        assertEquals(1, first.closeCount.get());
        assertEquals(1, second.closeCount.get());
    }

    /** 验证阻塞式传输关闭不会占用 CompletableFuture 的全局超时线程。 */
    @Test
    void blockingCloseDoesNotBlockGlobalTimeoutThread() throws Exception {
        FakeSession session = oneToolSession("blocking-close");
        session.blockCalls = true;
        session.blockClose = true;
        McpLimits limits = lifecycleLimits(
                Duration.ofSeconds(1), Duration.ofMillis(25), Duration.ofSeconds(1));
        McpRuntime runtime = runtime(
                List.of(definition("blocking-close")), limits, (ignored, ignoredDeadline) -> session);
        McpGateway.McpSnapshot snapshot = runtime.snapshot();
        CompletableFuture<McpGateway.McpResult> invocation = runtime.invoke(
                snapshot,
                invocation("blocking-close", snapshot.tools().getFirst().spec().name(), 0),
                CancellationToken.none()).toCompletableFuture();
        assertTrue(session.closeEntered.await(1, TimeUnit.SECONDS));

        CompletableFuture<Boolean> sentinel = new CompletableFuture<Boolean>()
                .orTimeout(25, TimeUnit.MILLISECONDS)
                .handle((ignored, failure) -> failure != null);
        assertTrue(sentinel.get(1, TimeUnit.SECONDS));
        session.releaseClose();
        McpGateway.McpResult result = invocation.get(1, TimeUnit.SECONDS);
        assertEquals(new JsonText("timeout"), structuredMember(result, "category"));
        runtime.close();
    }

    /** 验证并发关闭调用共享同一屏障，所属传输关闭前任何调用方都不能返回。 */
    @Test
    void concurrentCloseCallersShareOneCompletion() throws Exception {
        FakeSession session = oneToolSession("concurrent-close");
        session.blockClose = true;
        McpRuntime runtime = runtime(
                List.of(definition("concurrent-close")), McpLimits.DEFAULT,
                (ignored, ignoredDeadline) -> session);
        runtime.initializeSessions();
        CompletableFuture<IllegalStateException> first = CompletableFuture.supplyAsync(() -> captureClose(runtime));
        assertTrue(session.closeEntered.await(1, TimeUnit.SECONDS));
        CountDownLatch secondStarted = new CountDownLatch(1);
        CompletableFuture<IllegalStateException> second = CompletableFuture.supplyAsync(() -> {
            secondStarted.countDown();
            return captureClose(runtime);
        });
        assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
        assertFalse(second.isDone());

        session.releaseClose();
        assertEquals(null, first.get(1, TimeUnit.SECONDS));
        assertEquals(null, second.get(1, TimeUnit.SECONDS));
        assertEquals(1, session.closeCount.get());
    }

    /** 验证忽略中断的关闭会触达类型化 Deadline，且所有调用方看到相同失败证据。 */
    @Test
    void interruptIgnoringCloseTimesOutWithSharedFailure() throws Exception {
        FakeSession session = oneToolSession("ignores-interrupt");
        session.blockClose = true;
        session.ignoreCloseInterrupt = true;
        McpLimits limits = lifecycleLimits(
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(50));
        McpRuntime runtime = runtime(
                List.of(definition("ignores-interrupt")), limits, (ignored, ignoredDeadline) -> session);
        runtime.initializeSessions();
        CompletableFuture<IllegalStateException> closing = CompletableFuture.supplyAsync(() -> captureClose(runtime));
        assertTrue(session.closeEntered.await(1, TimeUnit.SECONDS));

        IllegalStateException first = closing.get(1, TimeUnit.SECONDS);
        assertTrue(suppressedMessages(first).contains("mcp_session_close_timeout"));
        assertTrue(suppressedMessages(first).contains("mcp_cleanup_executor_close_timeout"));
        IllegalStateException second = captureClose(runtime);
        assertEquals(suppressedMessages(first), suppressedMessages(second));
        session.releaseClose();
        assertEquals(1, session.closeCount.get());
    }

    /** 验证后续 Tool 轮次沿用首次调用的剩余预算，不会获得新的完整超时。 */
    @Test
    void secondInvocationCannotRefreshTurnDeadline() throws Exception {
        FakeTicker ticker = new FakeTicker();
        FakeSession session = oneToolSession("turn-deadline");
        AtomicInteger calls = new AtomicInteger();
        session.callAction = () -> ticker.advance(
                calls.getAndIncrement() == 0 ? Duration.ofMillis(600) : Duration.ofMillis(500));
        McpLimits limits = lifecycleLimits(
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));
        McpDeadline deadline = McpDeadline.forTurn(
                Instant.EPOCH.plusSeconds(1), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), ticker::read);
        McpRuntime runtime = new McpRuntime(
                List.of(definition("turn-deadline")), limits, new ObjectMapper(),
                (ignored, ignoredDeadline) -> session, null, deadline);
        McpGateway.McpSnapshot snapshot = runtime.snapshot();
        String toolName = snapshot.tools().getFirst().spec().name();

        McpGateway.McpResult first = runtime.invoke(
                snapshot, invocation("round-1", toolName, 0), CancellationToken.none())
                .toCompletableFuture().get(1, TimeUnit.SECONDS);
        McpGateway.McpResult second = runtime.invoke(
                snapshot, invocation("round-2", toolName, 1), CancellationToken.none())
                .toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(ToolOutcome.SUCCEEDED, first.outcome());
        assertEquals(ToolOutcome.FAILED, second.outcome());
        assertEquals(new JsonText("cleanup_failure"), structuredMember(second, "category"));
        assertEquals(2, calls.get());
        assertTrue(suppressedMessages(captureClose(runtime)).contains("mcp_session_close_timeout"));
    }

    /** 验证初始化、请求级目录访问、调用和清理共同消耗原始绝对 Deadline。 */
    @Test
    void initializeAndInvokeShareAbsoluteTurnDeadline() throws Exception {
        FakeTicker ticker = new FakeTicker();
        FakeSession session = oneToolSession("turn-wide");
        session.initializeAction = () -> ticker.advance(Duration.ofMillis(600));
        McpLimits limits = lifecycleLimits(
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));
        McpDeadline deadline = McpDeadline.forTurn(
                Instant.EPOCH.plusSeconds(1), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), ticker::read);
        ObjectMapper mapper = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        String localName = "mcp:turn-wide:remote";
        McpGateway.McpSnapshot requestSnapshot = McpRuntime.catalogSnapshot(
                List.of(new McpGateway.McpTool(
                        "turn-wide",
                        McpToolCatalog.encodeRemoteName("remote"),
                        new ToolSpec(
                                localName,
                                "turn-wide fixture",
                                JsonObjects.builder().putText("type", "object")
                                        .putBoolean("additionalProperties", false).build()))),
                List.of(definition("turn-wide")), mapper,
                Instant.EPOCH);
        McpSessionFactory factory = (ignored, admittedDeadline) -> {
            session.bindDeadline(admittedDeadline);
            return session;
        };
        McpRuntime runtime = new McpRuntime(
                List.of(definition("turn-wide")), limits, mapper, factory, requestSnapshot, deadline);

        runtime.initializeSessions();
        assertEquals(requestSnapshot, runtime.snapshot());
        ticker.advance(Duration.ofMillis(500));
        McpGateway.McpResult result = runtime.invoke(
                        requestSnapshot, invocation("cross-phase", localName, 0), CancellationToken.none())
                .toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(ToolOutcome.FAILED, result.outcome());
        assertEquals(new JsonText("cleanup_failure"), structuredMember(result, "category"));
        assertEquals(Duration.ofMillis(1_100).toNanos(), ticker.read());
        assertEquals(1L, session.callEntered.getCount());
        assertTrue(session.cursors.isEmpty());
        assertTrue(session.closeEntered.await(1, TimeUnit.SECONDS));
        assertEquals(1, session.closeCount.get());
        assertEquals(List.of(deadline.absoluteNanos(), deadline.absoluteNanos()),
                session.lifecycleDeadlines);
        assertTrue(suppressedMessages(captureClose(runtime)).contains("mcp_session_close_timeout"));
    }

    /** 验证已解析的私有帧 Header 可用，但命令行 Secret 参数仍被禁止。 */
    @Test
    void resolvedConfigurationAcceptsHeaderSecret() {
        Path cwd = Path.of(".").toAbsolutePath().normalize();
        McpServerDefinition resolved = McpServerDefinition.streamableHttp(
                "resolved-header",
                java.net.URI.create("https://example.test/mcp"),
                Map.of("Authorization", "resolved-private-value"),
                List.of("2025-06-18"));
        assertEquals("resolved-private-value", resolved.headers().get("Authorization"));
        assertTrue(resolved.toString().contains("Authorization"));
        assertFalse(resolved.toString().contains("resolved-private-value"));
        assertEquals(
                "mcp_config_value_invalid",
                assertThrows(IllegalArgumentException.class, () -> McpServerDefinition.streamableHttp(
                        "unresolved-header",
                        java.net.URI.create("https://example.test/mcp"),
                        Map.of("Authorization", "vault_ref://fixture"),
                        List.of("2025-06-18"))).getMessage());
        assertEquals(
                "mcp_command_invalid",
                assertThrows(IllegalArgumentException.class, () -> McpServerDefinition.stdio(
                        "raw-argument",
                        List.of("server", "--token=raw-secret"),
                        cwd,
                        Map.of(),
                        List.of("2025-06-18"))).getMessage());
    }

    /** 创建不含 Secret 的 stdio 定义；伪 Session 不会启动其中的夹具命令。 */
    private static McpServerDefinition definition(String id) {
        return McpServerDefinition.stdio(
                id, List.of("fixture"), Path.of(".").toAbsolutePath().normalize(), Map.of(), List.of("2025-06-18"));
    }

    /** 使用请求级本地名称与有序模型序号构造一次调用。 */
    private static McpGateway.McpInvocation invocation(String callId, String name, int ordinal) {
        return new McpGateway.McpInvocation(
                callId, name, JsonObjects.builder().putText("value", "ok").build(), ordinal);
    }

    /** 创建小型合法远端 Tool，用于隔离目录资源边界测试。 */
    private static McpSession.RemoteTool tool(String name) {
        return new McpSession.RemoteTool(name, "fixture tool", JsonObjects.builder()
                .putText("type", "object").putBoolean("additionalProperties", false).build());
    }

    /** 创建带显式 schema 标记的 Tool，用于证明同名路由变化也会失败关闭。 */
    private static McpSession.RemoteTool tool(String name, String marker) {
        return new McpSession.RemoteTool(name, "fixture tool", JsonObjects.builder()
                .putText("type", "object").putText("title", marker)
                .putBoolean("additionalProperties", false).build());
    }

    /** 捕获稳定的 Runtime 关闭结果，以便确定性断言并发调用方。 */
    private static IllegalStateException captureClose(McpRuntime runtime) {
        try {
            runtime.close();
            return null;
        } catch (IllegalStateException failure) {
            return failure;
        }
    }

    /** 从可选任意 JSON 结果中读取对象成员，失败时直接暴露结果形状回归。 */
    private static JsonValue structuredMember(McpGateway.McpResult result, String name) {
        return ((JsonObject) result.structuredContent().orElseThrow()).get(name);
    }

    /** 只投影稳定本地代码，使两个独立重建的关闭异常可精确比较。 */
    private static List<String> suppressedMessages(IllegalStateException failure) {
        return java.util.Arrays.stream(failure.getSuppressed()).map(Throwable::getMessage).toList();
    }

    /** 创建确定性的单页 Session，用于多服务生命周期测试。 */
    private static FakeSession oneToolSession(String name) {
        return new FakeSession(Map.of("<first>", new McpSession.ToolPage(List.of(tool(name)), null)));
    }

    /** 调整单个目标边界时保持其余预算为生产默认值，避免测试变量相互干扰。 */
    private static McpLimits limits(int maxTools, int maxResultBytes, Duration requestTimeout) {
        McpLimits defaults = McpLimits.DEFAULT;
        return new McpLimits(
                defaults.maxPages(),
                maxTools,
                defaults.maxCursorBytes(),
                defaults.maxSchemaBytes(),
                maxResultBytes,
                defaults.maxMessageBytes(),
                defaults.maxStderrBytes(),
                defaults.outboundQueueCapacity(),
                defaults.startupTimeout(),
                requestTimeout,
                defaults.closeTimeout());
    }

    /** 调整聚合生命周期预算时保留全部生产资源上限。 */
    private static McpLimits lifecycleLimits(
            Duration startupTimeout, Duration requestTimeout, Duration closeTimeout) {
        McpLimits defaults = McpLimits.DEFAULT;
        return new McpLimits(
                defaults.maxPages(),
                defaults.maxTools(),
                defaults.maxCursorBytes(),
                defaults.maxSchemaBytes(),
                defaults.maxResultBytes(),
                defaults.maxMessageBytes(),
                defaults.maxStderrBytes(),
                defaults.outboundQueueCapacity(),
                startupTimeout,
                requestTimeout,
                closeTimeout);
    }

    /** 通过包内 Session 测试接缝构造 Gateway，避免扩大生产可见性。 */
    private static McpRuntime runtime(
            List<McpServerDefinition> definitions, McpLimits limits, McpSessionFactory factory) {
        return new McpRuntime(definitions, limits, new ObjectMapper(), factory);
    }

    /** 注入确定性单调 Ticker，同时不改变生产构造器的所有权。 */
    private static McpRuntime runtime(
            List<McpServerDefinition> definitions,
            McpLimits limits,
            McpSessionFactory factory,
            FakeTicker ticker) {
        return new McpRuntime(definitions, limits, new ObjectMapper(), factory, null, ticker::read);
    }

    /** 确定性伪实现记录 Cursor、并发、关闭、延迟及结果行为。 */
    private static final class FakeSession implements McpSession {
        private volatile Map<String, ToolPage> pages;
        private final List<String> cursors = new CopyOnWriteArrayList<>();
        private final AtomicInteger closeCount = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maximumActive = new AtomicInteger();
        private final AtomicInteger callCount = new AtomicInteger();
        private final AtomicBoolean callVirtual = new AtomicBoolean();
        private final AtomicBoolean closeVirtual = new AtomicBoolean();
        private final CountDownLatch callEntered = new CountDownLatch(1);
        private final CountDownLatch callRelease = new CountDownLatch(1);
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch closeRelease = new CountDownLatch(1);
        private volatile Thread activeThread;
        private volatile boolean blockCalls;
        private volatile boolean blockClose;
        private volatile boolean ignoreCloseInterrupt;
        private volatile RemoteResult result = new RemoteResult(
                false, "ok", Optional.of(JsonObjects.builder().putBoolean("ok", true).build()));
        private volatile RuntimeException callFailure;
        private volatile RuntimeException closeFailure;
        private volatile Runnable initializeAction = () -> { };
        private volatile Runnable listAction = () -> { };
        private volatile Runnable callAction = () -> { };
        private volatile McpDeadline deadline;
        private final List<Long> lifecycleDeadlines = new CopyOnWriteArrayList<>();

        /** 以首个空 Cursor 的哨兵值为键存储不可变页面夹具。 */
        private FakeSession(Map<String, ToolPage> pages) {
            this.pages = Map.copyOf(pages);
        }

        /** 伪初始化刻意保持无副作用，避免生命周期测试引入外部状态。 */
        @Override
        public void initialize() {
            recordDeadline();
            initializeAction.run();
        }

        /** 返回请求页面前记录精确的 Cursor 重载调用。 */
        @Override
        public ToolPage listTools(String cursor) {
            listAction.run();
            cursors.add(cursor == null ? "<first>" : cursor);
            ToolPage page = pages.get(cursor == null ? "<first>" : cursor);
            if (page == null) {
                throw new IllegalStateException("fixture_cursor_missing");
            }
            return page;
        }

        /** 模拟阻塞 SDK 调用，同时测量重叠并响应关闭中断。 */
        @Override
        public RemoteResult call(String toolName, JsonObject arguments) {
            recordDeadline();
            callCount.incrementAndGet();
            callVirtual.set(Thread.currentThread().isVirtual());
            int current = active.incrementAndGet();
            activeThread = Thread.currentThread();
            maximumActive.accumulateAndGet(current, Math::max);
            callEntered.countDown();
            try {
                if (blockCalls) {
                    callRelease.await();
                }
                callAction.run();
                if (callFailure != null) {
                    throw callFailure;
                }
                return result;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("fixture_interrupted", interrupted);
            } finally {
                activeThread = null;
                active.decrementAndGet();
            }
        }

        /** 记录生命周期清理，不引入传输特有行为。 */
        @Override
        public void close() {
            recordDeadline();
            closeVirtual.set(Thread.currentThread().isVirtual());
            closeCount.incrementAndGet();
            callRelease.countDown();
            closeEntered.countDown();
            Thread thread = activeThread;
            if (thread != null) {
                thread.interrupt();
            }
            while (blockClose && closeRelease.getCount() > 0) {
                try {
                    closeRelease.await();
                } catch (InterruptedException interrupted) {
                    if (!ignoreCloseInterrupt) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        /** 释放屏障控制的调用，避免依赖调度器休眠。 */
        private void releaseCalls() {
            callRelease.countDown();
        }

        /** 在 Runtime 超时行为可观测后释放屏障控制的关闭 IO。 */
        private void releaseClose() {
            closeRelease.countDown();
        }

        /** 绑定 Runtime 所有的 Session Factory 提供的精确 Deadline 对象。 */
        private void bindDeadline(McpDeadline admittedDeadline) {
            deadline = admittedDeadline;
        }

        /** 只记录稳定单调边界，不记录依赖调度器的耗时。 */
        private void recordDeadline() {
            McpDeadline current = deadline;
            if (current != null) lifecycleDeadlines.add(current.absoluteNanos());
        }
    }

    /** 原子伪单调时钟让操作精确消耗预算，无需依赖墙钟休眠。 */
    private static final class FakeTicker {
        private final AtomicLong nanos = new AtomicLong();

        /** 按显式时长推进，以确定性模拟已完成的远端工作。 */
        private void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }

        /** 返回当前单调时刻的纳秒值，保持 Deadline 测试与墙钟隔离。 */
        private long read() {
            return nanos.get();
        }
    }

    /** 最小单次令牌用于验证回调注册与注销竞争。 */
    private static final class TestToken implements CancellationToken {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();

        /** 返回单调变化的测试取消标记。 */
        @Override
        public boolean isCancellationRequested() {
            return cancelled.get();
        }

        /** 使用不含 Provider 或 Secret 数据的稳定本地原因。 */
        @Override
        public Optional<String> reason() {
            return cancelled.get() ? Optional.of("test_cancel") : Optional.empty();
        }

        /** 注册清理回调；若取消已获胜则立即执行。 */
        @Override
        public Registration onCancellation(Runnable callback) {
            callbacks.add(callback);
            if (cancelled.get()) {
                callback.run();
            }
            return () -> callbacks.remove(callback);
        }

        /** 只允许取消成功一次，并执行当前全部清理回调。 */
        private void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                callbacks.forEach(Runnable::run);
            }
        }
    }
}

