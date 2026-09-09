// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.foundation.concurrent.BoundedVirtualExecutor;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;
import io.github.kongweiguang.ja.transport.rpc.runtime.StdioWriter;
import io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings;
import io.github.kongweiguang.ja.transport.rpc.support.TestConfigurationPorts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.kongweiguang.ja.transport.rpc.runtime.RpcRuntimeTestAccess.markReady;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定冻结 ChangeSet 的四字段完整文件、后台容量和五秒期限语义。 */
final class ThreadChangeSetReadHandlerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC);

    /** Handler 必须传递 filePath，并原样投影完整 Base64、长度和文件级摘要。 */
    @Test
    void requiresAndProjectsCompleteSelectedFile() {
        try (Harness harness = new Harness(Duration.ofSeconds(5), successfulReader())) {
            ObjectNode response = harness.handler.handle(command(request())).toCompletableFuture().join();
            assertEquals("src/末尾😀.txt", harness.selectedPath.get());
            assertEquals("src/末尾😀.txt", response.path("filePath").textValue());
            assertEquals(4, response.path("byteLength").intValue());
            assertEquals("digest", response.path("sha256").textValue());
            assertEquals("diff", new String(Base64.getDecoder().decode(
                    response.path("contentBase64").textValue()), StandardCharsets.UTF_8));
            assertEquals(5, response.size());
        }
    }

    /** 旧分页字段与缺失 filePath 都必须在调用端口前失败，不保留兼容 shape。 */
    @Test
    void rejectsLegacyAndMissingFields() {
        try (Harness harness = new Harness(Duration.ofSeconds(5), successfulReader())) {
            ObjectNode paged = request().put("offsetBytes", 0).put("limitBytes", 64);
            assertThrows(JaRpcException.class, () -> harness.handler.handle(command(paged)));
            ObjectNode missing = request();
            missing.remove("filePath");
            assertThrows(JaRpcException.class, () -> harness.handler.handle(command(missing)));
            assertNull(harness.selectedPath.get());
        }
    }

    /** 三个已接纳读取占满 2 active+1 pending 后立即拒绝第四个，容量不会无界增长。 */
    @Test
    void boundsTwoActiveReadsAndOnePendingSelection() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ThreadUseCase reader = reader((arguments) -> {
            calls.incrementAndGet();
            entered.countDown();
            release.await();
            return artifact((String) arguments[3]);
        });
        try (Harness harness = new Harness(Duration.ofSeconds(5), reader)) {
            var first = harness.handler.handle(command(request()));
            var second = harness.handler.handle(command(request()));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var pending = harness.handler.handle(command(request()));
            CompletionException rejected = assertThrows(CompletionException.class,
                    () -> harness.handler.handle(command(request())).toCompletableFuture().join());
            JaRpcException rpc = assertInstanceOf(JaRpcException.class, rejected.getCause());
            assertEquals(JaErrorCatalog.QUEUE_FULL.name(), rpc.errorCode());
            release.countDown();
            first.toCompletableFuture().join();
            second.toCompletableFuture().join();
            pending.toCompletableFuture().join();
            assertEquals(3, calls.get());
        }
    }

    /** 期限覆盖等待与读取，超时只结束当前响应且通过中断释放后台任务。 */
    @Test
    void timesOutAndInterruptsOnlyCurrentRead() {
        CountDownLatch interrupted = new CountDownLatch(1);
        ThreadUseCase reader = reader((arguments) -> {
            try {
                Thread.sleep(Duration.ofSeconds(10));
            } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return artifact((String) arguments[3]);
        });
        try (Harness harness = new Harness(Duration.ofMillis(50), reader)) {
            CompletionException timedOut = assertThrows(CompletionException.class,
                    () -> harness.handler.handle(command(request())).toCompletableFuture().join());
            JaRpcException rpc = assertInstanceOf(JaRpcException.class, timedOut.getCause());
            assertEquals(JaErrorCatalog.REQUEST_DEADLINE_EXCEEDED.name(), rpc.errorCode());
            assertTrue(await(interrupted));
        }
    }

    /** 构造当前唯一合法的按文件请求，不携带分页游标。 */
    private static ObjectNode request() {
        return MAPPER.createObjectNode().put("threadId", "thr_review").put("turnId", "turn_review")
                .put("artifactId", "artifact_review").put("filePath", "src/末尾😀.txt");
    }

    /** 将参数包装成目标 JA-RPC 命令，避免各测试复制方法身份。 */
    private static RpcCommand command(ObjectNode params) {
        return new RpcCommand(RpcMethod.TURN_CHANGE_SET_READ, params);
    }

    /** 默认成功端口返回固定正文，Harness 记录第四个 filePath 参数。 */
    private static ThreadUseCase successfulReader() {
        return reader(arguments -> artifact((String) arguments[3]));
    }

    /** 构造严格完整单文件响应，测试重点是 transport 而非持久 parser。 */
    private static Optional<ThreadUseCase.ChangeSetArtifactFile> artifact(String path) {
        return Optional.of(new ThreadUseCase.ChangeSetArtifactFile(
                "artifact_review", path, 4, "digest",
                Base64.getEncoder().encodeToString("diff".getBytes(StandardCharsets.UTF_8))));
    }

    /** 动态代理只开放目标端口，阻止测试夹具无意依赖 ThreadUseCase 其它默认方法。 */
    private static ThreadUseCase reader(ReaderOperation operation) {
        return (ThreadUseCase) Proxy.newProxyInstance(ThreadUseCase.class.getClassLoader(),
                new Class<?>[]{ThreadUseCase.class}, (proxy, method, arguments) -> {
                    if (!"readChangeSetArtifact".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    return operation.read(arguments);
                });
    }

    /** 有界等待中断证据，失败时不把测试挂住。 */
    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 允许容量与超时测试在 fake 端口内阻塞，而不引入生产测试分支。 */
    @FunctionalInterface
    private interface ReaderOperation {
        /** 接收反射端口参数并返回可选单文件。 */
        Optional<ThreadUseCase.ChangeSetArtifactFile> read(Object[] arguments) throws Exception;
    }

    /** 真实 ready RpcSession 只开放目标 Thread 端口，读取执行器由 Handler 独立持有。 */
    private static final class Harness implements AutoCloseable {
        private final AtomicReference<String> selectedPath = new AtomicReference<>();
        private final StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), MAPPER, 1024 * 1024);
        private final RpcSession session;
        private final ThreadHistoryHandler handler;

        /** 注入窄期限与 fake 端口，同时保留生产相同的 2 active、3 admitted。 */
        private Harness(Duration timeout, ThreadUseCase source) {
            ThreadUseCase recording = reader(arguments -> {
                selectedPath.set((String) arguments[3]);
                return source.readChangeSetArtifact((String) arguments[0], (String) arguments[1],
                        (String) arguments[2], (String) arguments[3]);
            });
            Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-change-set-handler-test")
                    .toAbsolutePath();
            SidecarConfiguration configuration = new SidecarConfiguration(root.resolve("home"),
                    root.resolve("data"), root.resolve("run"), root.resolve("logs"));
            session = new RpcSession(configuration, MAPPER, CLOCK, writer,
                    ignored -> RpcTestBindings.create(null, recording, null, null, null, () -> { }),
                    TestConfigurationPorts.unavailable());
            session.initialize();
            markReady(session, "0123456789abcdef0123456789abcdef");
            handler = new ThreadHistoryHandler(session, timeout,
                    new BoundedVirtualExecutor("change-set-handler-test-", 2, 3),
                    Executors.newSingleThreadScheduledExecutor(
                            Thread.ofPlatform().daemon().name("change-set-deadline-test-", 0).factory()));
        }

        /** 先排空 Handler 自有读取，再释放可能关闭 SQLite 的 Session。 */
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
}
