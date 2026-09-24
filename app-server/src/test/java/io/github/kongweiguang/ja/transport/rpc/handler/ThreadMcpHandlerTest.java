// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.catalog.port.in.ThreadMcpUseCase;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;
import io.github.kongweiguang.ja.transport.rpc.runtime.StdioWriter;
import io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings;
import io.github.kongweiguang.ja.transport.rpc.support.TestConfigurationPorts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.kongweiguang.ja.transport.rpc.runtime.RpcRuntimeTestAccess.markReady;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 固定只读的会话 MCP 状态形状，测试不打开传输。 */
final class ThreadMcpHandlerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"), ZoneOffset.UTC);

    /** 原生边界只公开来源、状态和简短提示，防止泄漏连接字段。 */
    @Test
    void readsSanitizedScopedStatus() {
        RecordingUseCase useCase = new RecordingUseCase(result("thr_current"));
        try (Harness harness = new Harness(useCase)) {
            ObjectNode result = invoke(harness.handler, MAPPER.createObjectNode().put("threadId", "thr_current"));
            assertEquals(Set.of("threadId", "source", "notices", "servers"), fields(result));
            assertEquals("unchecked", result.path("source").textValue());
            assertEquals("project", result.path("servers").get(0).path("scope").textValue());
            assertEquals("not_discovered", result.path("servers").get(0).path("state").textValue());
            assertEquals("project_untrusted", result.path("notices").get(0).textValue());
            assertEquals(1, useCase.readCalls.get());
        }
    }

    /** 读取命令拒绝夹带服务 ID 或非法会话身份。 */
    @Test
    void rejectsMalformedInputAndWrongResultIdentity() {
        RecordingUseCase useCase = new RecordingUseCase(result("thr_other"));
        try (Harness harness = new Harness(useCase)) {
            assertThrows(JaRpcException.class, () -> harness.handler.handle(new RpcCommand(
                    RpcMethod.THREAD_MCP_READ, MAPPER.createObjectNode().put("threadId", "thr_current")
                            .put("serverId", "mcp_demo"))));
            assertThrows(JaRpcException.class, () -> harness.handler.handle(new RpcCommand(
                    RpcMethod.THREAD_MCP_READ, MAPPER.createObjectNode().put("threadId", "../secret"))));
            assertEquals(0, useCase.readCalls.get());
            assertThrows(IllegalStateException.class, () -> invoke(harness.handler,
                    MAPPER.createObjectNode().put("threadId", "thr_current")));
        }
    }

    /** Handler 只拥有无副作用的状态读取入口。 */
    @Test
    void ownsOnlyRead() {
        try (Harness harness = new Harness(new RecordingUseCase(result("thr_current")))) {
            assertEquals(Set.of(RpcMethod.THREAD_MCP_READ), harness.handler.methods());
        }
    }

    /** 构造脱敏项目服务行，确保 Wire 不遗漏来源标签。 */
    private static ThreadMcpUseCase.ReadResult result(String threadId) {
        return new ThreadMcpUseCase.ReadResult(threadId, ThreadMcpUseCase.Source.UNCHECKED,
                null, null, List.of(new ThreadMcpUseCase.Server("mcp_demo", "Demo",
                        ThreadMcpUseCase.Scope.PROJECT, ThreadMcpUseCase.State.NOT_DISCOVERED,
                        null, null)), List.of(ThreadMcpUseCase.Notice.PROJECT_UNTRUSTED));
    }

    /** 使用与路由器相同的命令形状调用唯一 RPC 方法。 */
    private static ObjectNode invoke(ThreadMcpHandler handler, ObjectNode params) {
        return handler.handle(new RpcCommand(RpcMethod.THREAD_MCP_READ, params)).toCompletableFuture().join();
    }

    /** 精确比较字段名，阻止地址或凭据被意外投影。 */
    private static Set<String> fields(ObjectNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    /** 记录读取次数，验证非法请求在应用边界前被拒绝。 */
    private static final class RecordingUseCase implements ThreadMcpUseCase {
        private final ReadResult result;
        private final AtomicInteger readCalls = new AtomicInteger();

        /** 固定外部应用 owner 的结果，避免测试中隐式刷新。 */
        private RecordingUseCase(ReadResult result) { this.result = result; }

        /** 不连接传输层，直接返回预设投影。 */
        @Override public ReadResult read(String threadId) {
            readCalls.incrementAndGet();
            return result;
        }
    }
    /** 构造已就绪会话，无关端口均采用拒绝式测试默认值。 */
    private static final class Harness implements AutoCloseable {
        private final StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), MAPPER, 1024 * 1024);
        private final RpcSession session;
        private final ThreadMcpHandler handler;

        /** 注入记录型 MCP 端口，同时保留生产会话的就绪校验。 */
        private Harness(ThreadMcpUseCase threadMcp) {
            RpcServiceBindings base = RpcTestBindings.create(null, null, null, null, null, () -> { });
            RpcServiceBindings bindings = new RpcServiceBindings(base.workspaces(), base.workspacePathSearch(),
                    base.threads(), threadMcp, base.turns(), base.compactions(), base.approvals(), base.catalog(),
                    base.attachments(), base.attachmentPreviews(), base.tasks(), base.goals(), base.interactions(),
                    base.lifecycle());
            Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-thread-mcp-handler-test")
                    .toAbsolutePath();
            SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"), root.resolve("data"),
                    root.resolve("run"), root.resolve("logs"));
            session = new RpcSession(configuration, MAPPER, CLOCK, writer, ignored -> bindings,
                    TestConfigurationPorts.unavailable());
            session.initialize();
            markReady(session, "0123456789abcdef0123456789abcdef");
            handler = new ThreadMcpHandler(session);
        }

        /** 先关闭会话再关闭 JSONL writer，保证资源所有权顺序稳定。 */
        @Override
        public void close() {
            try {
                session.close();
            } finally {
                writer.close();
            }
        }
    }
}
