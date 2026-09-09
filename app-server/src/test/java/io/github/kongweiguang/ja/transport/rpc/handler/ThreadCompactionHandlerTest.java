// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.catalog.port.in.CatalogUseCase;
import io.github.kongweiguang.ja.conversation.port.in.ApprovalUseCase;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionUseCase;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;
import io.github.kongweiguang.ja.transport.rpc.runtime.StdioWriter;
import io.github.kongweiguang.ja.transport.rpc.support.TestConfigurationPorts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.kongweiguang.ja.transport.rpc.runtime.RpcRuntimeTestAccess.markReady;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 锁定 thread/compact 的严格 DTO、nullable 结果和稳定错误映射。 */
final class ThreadCompactionHandlerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-29T00:00:00Z"), ZoneOffset.UTC);

    /** changed 结果必须保留 CAS revision、官方 Token 和两个真实身份。 */
    @Test
    void mapsCompactedResult() {
        AtomicReference<ContextCompactionUseCase.Command> observed = new AtomicReference<>();
        ContextCompactionUseCase useCase = (command, events, cancellation) -> {
            observed.set(command);
            return new ContextCompactionUseCase.Result(ContextCompactionUseCase.Outcome.COMPACTED,
                    "cmp_test", "checkpoint_test", 8, 1_000, 400);
        };
        try (Harness harness = new Harness(useCase)) {
            ObjectNode result = harness.handler.handle(command(7)).toCompletableFuture().join();
            assertEquals("thr_test", observed.get().threadId());
            assertEquals(7, observed.get().expectedThreadRevision());
            assertEquals("compacted", result.path("outcome").textValue());
            assertEquals("cmp_test", result.path("compactionId").textValue());
            assertEquals("checkpoint_test", result.path("checkpointId").textValue());
            assertEquals(8, result.path("threadRevision").longValue());
            assertEquals(1_000, result.path("inputTokensBefore").longValue());
            assertEquals(400, result.path("inputTokensAfter").longValue());
        }
    }

    /** unchanged 必须显式返回 JSON null，不能把条件字段变成 optional。 */
    @Test
    void mapsUnchangedWithExplicitNullIdentities() {
        ContextCompactionUseCase useCase = (command, events, cancellation) ->
                new ContextCompactionUseCase.Result(ContextCompactionUseCase.Outcome.UNCHANGED,
                        null, null, 7, 400, 400);
        try (Harness harness = new Harness(useCase)) {
            ObjectNode result = harness.handler.handle(command(7)).toCompletableFuture().join();
            assertNull(result.get("compactionId").textValue());
            assertNull(result.get("checkpointId").textValue());
        }
    }

    /** 应用失败闭集必须逐项映射为同名 JA-RPC errorCode。 */
    @Test
    void mapsStableFailureCatalog() {
        for (ContextCompactionUseCase.Code code : ContextCompactionUseCase.Code.values()) {
            ContextCompactionUseCase useCase = (command, events, cancellation) -> { throw new ContextCompactionUseCase.Failure(code); };
            try (Harness harness = new Harness(useCase)) {
                JaRpcException failure = assertThrows(JaRpcException.class,
                        () -> harness.handler.handle(command(7)));
                assertEquals(code.name(), failure.errorCode());
            }
        }
    }

    /** 未知字段必须在调用应用用例前被 strict params 拒绝。 */
    @Test
    void rejectsUnknownParams() {
        ContextCompactionUseCase useCase = (command, events, cancellation) -> { throw new AssertionError("must not execute"); };
        try (Harness harness = new Harness(useCase)) {
            ObjectNode params = command(7).params().put("legacy", true);
            JaRpcException failure = assertThrows(JaRpcException.class,
                    () -> harness.handler.handle(new RpcCommand(RpcMethod.THREAD_COMPACT, params)));
            assertEquals("INVALID_PARAMS", failure.errorCode());
        }
    }

    /** 构造精确 thread/compact 命令，避免每个断言重复 DTO 拼装。 */
    private static RpcCommand command(long revision) {
        return new RpcCommand(RpcMethod.THREAD_COMPACT, MAPPER.createObjectNode()
                .put("threadId", "thr_test").put("expectedThreadRevision", revision));
    }

    /** 使用真实 RpcSession ready 状态机，只把非目标端口替换为显式拒绝代理。 */
    private static final class Harness implements AutoCloseable {
        private final StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), MAPPER, 4 * 1024 * 1024);
        private final RpcSession session;
        private final ThreadCompactionHandler handler;

        /** 注入唯一允许执行的压缩端口，测试不创建存储、Provider 或配置文件。 */
        private Harness(ContextCompactionUseCase compactions) {
            RpcServiceBindings bindings = new RpcServiceBindings(unsupported(io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase.class),
                    unsupported(io.github.kongweiguang.ja.workspace.port.in.WorkspacePathSearchUseCase.class),
                    unsupported(ThreadUseCase.class), unsupported(TurnUseCase.class), compactions,
                    unsupported(ApprovalUseCase.class), unsupported(CatalogUseCase.class),
                    unsupported(io.github.kongweiguang.ja.attachment.port.in.AttachmentUseCase.class),
                    unsupported(io.github.kongweiguang.ja.attachment.port.in.AttachmentPreviewUseCase.class),
                    io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.passiveTasks(),
                    io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.passiveGoals(),
                    new DeadlineCloseable() {
                        /** 测试组合没有外部资源，deadline close 保持无副作用。 */
                        @Override public void closeAt(long shutdownDeadlineNanos) { }
                        /** 测试组合没有外部资源，普通 close 保持无副作用。 */
                        @Override public void close() { }
                    });
            Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-thread-compaction-handler-test").toAbsolutePath();
            SidecarConfiguration sidecar = new SidecarConfiguration(root.resolve("home"), root.resolve("data"),
                    root.resolve("run"), root.resolve("logs"));
            session = new RpcSession(sidecar, MAPPER, CLOCK, writer, ignored -> bindings,
                    TestConfigurationPorts.unavailable());
            session.initialize();
            markReady(session, "0123456789abcdef0123456789abcdef");
            handler = new ThreadCompactionHandler(session);
        }

        /** 按生产顺序关闭 session 与 writer，避免 stdout owner 线程泄漏。 */
        @Override
        public void close() {
            try { session.close(); } finally { writer.close(); }
        }
    }

    /** 非目标端口一旦被调用就失败，防止 Handler 越过压缩用例边界。 */
    @SuppressWarnings("unchecked")
    private static <T> T unsupported(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
    }
}
