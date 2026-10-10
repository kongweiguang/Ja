// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase;
import io.github.kongweiguang.ja.conversation.domain.model.ProcessSessionSummary;
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
import java.util.concurrent.atomic.AtomicReference;

import static io.github.kongweiguang.ja.transport.rpc.runtime.RpcRuntimeTestAccess.markReady;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 仅通过进程 RPC Handler 校验请求边界与投影，不启动宿主进程以避免测试依赖原生生命周期。
 */
final class ProcessHandlerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC);
    private static final String SESSION_ID = "proc_0123456789abcdef0123456789abcdef";

    /** 校验旧代际仍可读其历史输出，但活动状态必须投影为 interrupted，不能误示为可控制进程。 */
    @Test
    void projectsListReadWriteAndStopWithinTheWireContract() {
        RecordingProcesses processes = new RecordingProcesses();
        try (Harness harness = new Harness(processes.owner)) {
            processes.page.set(new ProcessSessionUseCase.Page(List.of(
                    summary("proc_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 7, "starting", null),
                    summary("proc_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 7, "failed", null),
                    summary(SESSION_ID, 6, "interrupted", null)), "Y3Vyc29y"));
            ObjectNode listed = invoke(harness.handler, RpcMethod.PROCESS_LIST,
                    MAPPER.createObjectNode().put("workspaceId", "ws_demo").put("threadId", "thr_demo")
                            .put("limit", 50).putNull("cursor"));
            assertEquals(Set.of("workspaceId", "threadId", "generation", "sessions", "nextCursor"),
                    fields(listed));
            assertEquals(7, listed.path("generation").longValue());
            assertEquals(2, listed.path("sessions").size());
            assertEquals("running", listed.path("sessions").get(0).path("state").textValue());
            assertEquals("interrupted", listed.path("sessions").get(1).path("state").textValue());
            assertEquals("Y3Vyc29y", listed.path("nextCursor").textValue());

            processes.snapshot.set(snapshot(6, "running", "ok", 4, 4, null));
            ObjectNode read = invoke(harness.handler, RpcMethod.PROCESS_READ, readParams());
            assertEquals(Set.of("workspaceId", "threadId", "generation", "sessionId", "startedTurnId",
                    "startedCallId", "createdAt", "state", "outputSequence", "outputTruncated",
                    "output", "nextSequence", "exitCode"), fields(read));
            assertEquals("ok", read.path("output").textValue());
            assertEquals("interrupted", read.path("state").textValue());

            processes.snapshot.set(snapshot(7, "running", "done", 8, 8, null));
            ObjectNode write = invoke(harness.handler, RpcMethod.PROCESS_WRITE,
                    MAPPER.createObjectNode().put("workspaceId", "ws_demo").put("threadId", "thr_demo")
                            .put("generation", 7).put("sessionId", SESSION_ID).put("afterSequence", 4)
                            .put("input", "\u0003").put("closeInput", false)
                            .put("operationKey", "op_0123456789abcdef0123456789abcdef"));
            assertEquals("done", write.path("output").textValue());
            assertEquals(1, processes.writeCalls.get());

            processes.snapshot.set(snapshot(7, "exited", "", 8, 8, 0));
            ObjectNode stopped = invoke(harness.handler, RpcMethod.PROCESS_STOP,
                    MAPPER.createObjectNode().put("workspaceId", "ws_demo").put("threadId", "thr_demo")
                            .put("generation", 7).put("sessionId", SESSION_ID));
            assertEquals("exited", stopped.path("state").textValue());
            assertEquals("", stopped.path("output").textValue());
        }
    }

    /** 非法身份和额外字段必须在触达 Owner 前拒绝，避免 RPC 宽容解析绕过会话权限边界。 */
    @Test
    void rejectsMalformedIdentityAndUnknownFieldsBeforeAccess() {
        RecordingProcesses processes = new RecordingProcesses();
        try (Harness harness = new Harness(processes.owner)) {
            ObjectNode invalid = readParams().put("sessionId", "../secret");
            assertThrows(JaRpcException.class,
                    () -> invoke(harness.handler, RpcMethod.PROCESS_READ, invalid));
            ObjectNode extra = MAPPER.createObjectNode().put("workspaceId", "ws_demo")
                    .put("threadId", "thr_demo").put("limit", 50).putNull("cursor")
                    .put("command", "whoami");
            assertThrows(JaRpcException.class,
                    () -> invoke(harness.handler, RpcMethod.PROCESS_LIST, extra));
            assertEquals(0, processes.readCalls.get());
            assertEquals(0, processes.listCalls.get());
        }
    }

    /** 固定 Handler 的方法闭集，避免路由变化意外扩大该 Owner 可处理的 RPC 范围。 */
    @Test
    void ownsOnlyProcessMethods() {
        RecordingProcesses processes = new RecordingProcesses();
        try (Harness harness = new Harness(processes.owner)) {
            assertEquals(Set.of(RpcMethod.PROCESS_LIST, RpcMethod.PROCESS_READ,
                    RpcMethod.PROCESS_WRITE, RpcMethod.PROCESS_STOP), harness.handler.methods());
        }
    }

    /** 构造有界读取请求并固定服务代际，令游标断言只覆盖 RPC 参数解析边界。 */
    private static ObjectNode readParams() {
        return MAPPER.createObjectNode().put("workspaceId", "ws_demo").put("threadId", "thr_demo")
                .put("generation", 7).put("sessionId", SESSION_ID).put("afterSequence", 2).put("maxBytes", 4);
    }

    /** 复用领域校验构造合法摘要，仅改变生命周期字段以覆盖 RPC 状态投影。 */
    private static ProcessSessionSummary summary(String sessionId, long generation, String state,
                                                 Integer exitCode) {
        return new ProcessSessionSummary(sessionId, "ws_demo", "thr_demo", "turn_demo", "call_demo",
                generation, state, 0, false, exitCode, Instant.parse("2026-10-06T00:00:00Z"));
    }

    /** 创建身份和输出游标闭合的快照，避免夹具本身越过会话领域约束。 */
    private static ProcessSessionUseCase.Snapshot snapshot(long generation, String state, String output,
                                                            long outputSequence, long nextSequence,
                                                            Integer exitCode) {
        return new ProcessSessionUseCase.Snapshot(SESSION_ID, "ws_demo", "thr_demo", "turn_demo", "call_demo",
                generation, state, Instant.parse("2026-10-06T00:00:00Z"), output, outputSequence, nextSequence,
                false, exitCode);
    }

    /** 经 RpcCommand 进入 Handler 公共入口，使参数校验和就绪态门禁一并受测。 */
    private static ObjectNode invoke(ProcessHandler handler, RpcMethod method, ObjectNode params) {
        return handler.handle(new RpcCommand(method, params)).toCompletableFuture().join();
    }

    /** 提取 JSON 对象的精确字段闭集，防止测试漏掉意外暴露或遗漏的可空字段。 */
    private static Set<String> fields(ObjectNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    /** 记录 Owner 调用并返回不可变夹具，使边界测试无需创建宿主进程。 */
    private static final class RecordingProcesses {
        private final AtomicReference<ProcessSessionUseCase.Page> page = new AtomicReference<>(
                new ProcessSessionUseCase.Page(List.of(), null));
        private final AtomicReference<ProcessSessionUseCase.Snapshot> snapshot = new AtomicReference<>(
                ProcessHandlerTest.snapshot(7, "running", "", 0, 0, null));
        private final AtomicInteger listCalls = new AtomicInteger();
        private final AtomicInteger readCalls = new AtomicInteger();
        private final AtomicInteger writeCalls = new AtomicInteger();
        private final ProcessSessionUseCase owner = (ProcessSessionUseCase) java.lang.reflect.Proxy.newProxyInstance(
                ProcessHandlerTest.class.getClassLoader(), new Class<?>[]{ProcessSessionUseCase.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "generation" -> 7L;
                    case "list" -> { listCalls.incrementAndGet(); yield page.get(); }
                    case "read" -> { readCalls.incrementAndGet(); yield snapshot.get(); }
                    case "write" -> { writeCalls.incrementAndGet(); yield snapshot.get(); }
                    case "stop" -> snapshot.get();
                    case "close", "closeAt" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** 仅替换进程 Owner，其余 Session 初始化与就绪校验仍走生产组合路径。 */
    private static final class Harness implements AutoCloseable {
        private final StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), MAPPER, 1024 * 1024);
        private final RpcSession session;
        private final ProcessHandler handler;

        /** 通过测试绑定替换进程会话 Owner，同时初始化真实 RPC 会话就绪边界。 */
        private Harness(ProcessSessionUseCase processes) {
            RpcServiceBindings bindings = RpcTestBindings.createWithProcesses(null, null, null, null, null,
                    null, processes, () -> { });
            Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-process-handler-test").toAbsolutePath();
            SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"), root.resolve("data"),
                    root.resolve("run"), root.resolve("logs"));
            session = new RpcSession(configuration, MAPPER, CLOCK, writer, ignored -> bindings,
                    TestConfigurationPorts.unavailable());
            session.initialize();
            markReady(session, "0123456789abcdef0123456789abcdef");
            handler = new ProcessHandler(session);
        }

        /** 按资源依赖顺序关闭 Session，再关闭 JSONL Writer，避免收尾事件失去输出通道。 */
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
