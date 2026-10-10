// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.transport.rpc.runtime;

import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证唯一 stdout 写入所有者同时强制帧大小与控制队列上限。 */
final class StdioWriterTest {
    /** 已协商的来源消息通知必须能真正写出 stdout，不能只通过 mapper/schema 的静态校验。 */
    @Test
    void flushesNegotiatedThreadMessagesNotification() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024)) {
            writer.notification("turn/messages_received", mapper.createObjectNode()
                    .put("threadId", "thr_target").put("turnId", "turn_target")).join();
        }
        assertEquals("turn/messages_received", mapper.readTree(output.toByteArray()).path("method").asText());
    }

    /** 错误 Wire 只投影 typed data 与唯一 ID；cause 和 suppressed 清理诊断不外泄，退避时间仅显式输出。 */
    @Test
    void writesStrictTypedErrorData() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        IllegalStateException internalCause = new IllegalStateException("private storage diagnostic");
        IllegalStateException cleanupFailure = new IllegalStateException("private cleanup diagnostic");
        JaRpcException first = JaRpcException.of(
                JaErrorCatalog.INVALID_PARAMS, "invalid params", internalCause);
        first.addSuppressed(cleanupFailure);
        JaRpcException second = JaRpcException.withRetryAfter(JaErrorCatalog.QUEUE_FULL, "queue full", 250);
        assertSame(internalCause, first.getCause());
        assertSame(cleanupFailure, first.getSuppressed()[0]);
        try (StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024)) {
            writer.error("c:first", first).join();
            writer.error("c:second", second).join();
        }
        List<String> frames = output.toString(java.nio.charset.StandardCharsets.UTF_8).lines().toList();
        assertFalse(frames.get(0).contains(internalCause.getMessage()));
        assertFalse(frames.get(0).contains(cleanupFailure.getMessage()));
        ObjectNode firstData = (ObjectNode) mapper.readTree(frames.get(0)).path("error").path("data");
        ObjectNode secondData = (ObjectNode) mapper.readTree(frames.get(1)).path("error").path("data");
        assertEquals("INVALID_PARAMS", firstData.path("errorCode").textValue());
        assertEquals("validation", firstData.path("category").textValue());
        assertTrue(firstData.path("errorId").textValue().matches("err_[0-9a-f]{32}"));
        assertFalse(firstData.has("retryAfterMs"));
        assertEquals(250, secondData.path("retryAfterMs").longValue());
        assertTrue(secondData.path("retryable").booleanValue());
        assertFalse(firstData.path("errorId").textValue().equals(secondData.path("errorId").textValue()));
    }

    /** 序列化失败发生在队列准入前，异常仍须保留 Jackson 的原始 cause 供本地诊断。 */
    @Test
    void serializationFailureRetainsCauseBeforeAdmission() throws Exception {
        IOException encodingFailure = new IOException("synthetic encoder failure");
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(ObjectNode.class, new JsonSerializer<ObjectNode>() {
            /** 在队列准入前注入编码故障，以验证原始诊断和资源配额均保留正确边界。 */
            @Override
            public void serialize(ObjectNode value, JsonGenerator generator, SerializerProvider serializers)
                    throws IOException {
                throw encodingFailure;
            }
        });
        mapper.registerModule(module);
        try (StdioWriter writer = new StdioWriter(OutputStream.nullOutputStream(), mapper, 4 * 1024 * 1024)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> writer.response("c:serialization", mapper.createObjectNode()));
            assertTrue(failure.getCause() instanceof IOException);
        }
    }

    /** 固定超大序列化帧必须在占用控制队列容量前被拒绝，避免放大内存压力。 */
    @Test
    void rejectsOversizedOutboundFrame() {
        ObjectMapper mapper = new ObjectMapper();
        try (StdioWriter writer = new StdioWriter(OutputStream.nullOutputStream(), mapper, 1_024)) {
            ObjectNode params = mapper.createObjectNode().put("text", "x".repeat(2_000));
            JaRpcException failure = assertThrows(JaRpcException.class,
                    () -> writer.notification("runtime/status-changed", params));
            assertEquals("FRAME_TOO_LARGE", failure.errorCode());
        }
    }

    /** 固定控制队列达到精确上限时失败关闭，禁止通过无限等待帧转移背压。 */
    @Test
    void controlQueueOverflowClosesGeneration() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        BlockingOutput output = new BlockingOutput();
        StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024);
        List<java.util.concurrent.CompletableFuture<Void>> admitted = new ArrayList<>();
        admitted.add(writer.notification("runtime/status-changed", mapper.createObjectNode().put("n", 0)));
        assertTrue(output.entered.await(1, TimeUnit.SECONDS));
        for (int index = 1; index <= StdioWriter.MAX_CONTROL_FRAMES; index++) {
            admitted.add(writer.notification("runtime/status-changed",
                    mapper.createObjectNode().put("n", index)));
        }
        JaRpcException failure = assertThrows(JaRpcException.class,
                () -> writer.notification("runtime/status-changed", mapper.createObjectNode().put("n", 66)));
        assertEquals("QUEUE_FULL", failure.errorCode());
        output.release.countDown();
        writer.close();
    }

    /** 控制帧字节预算包含已阻塞的活动帧，超限沿用当前 generation 失败关闭策略。 */
    @Test
    void controlQueueByteBudgetIncludesBlockedActiveFrame() throws Exception {
        assertLaneByteBudgetOverflow(true);
    }

    /** 可丢弃数据帧仍受累计字节上限约束，避免小帧数上限之外的内存放大。 */
    @Test
    void dataQueueByteBudgetIncludesBlockedActiveFrame() throws Exception {
        assertLaneByteBudgetOverflow(false);
    }

    /** 控制与数据帧成功刷写后均归还真实编码字节预算，下一帧无需等关闭代际。 */
    @Test
    void reusesLaneByteBudgetAfterFlush() throws Exception {
        assertLaneByteBudgetReusable(true);
        assertLaneByteBudgetReusable(false);
    }

    /** 证明已刷新的审批事件可同步触发响应，同时禁止写线程等待自身形成死锁。 */
    @Test
    void flushedNotificationDependentCanSynchronouslyAwaitResponseFlush() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CountDownLatch responseDone = new CountDownLatch(1);
        AtomicBoolean callbackRanOnWriter = new AtomicBoolean();
        try (StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024)) {
            CompletableFuture<Void> published = writer.notification("approval/resolved",
                    mapper.createObjectNode().put("approvalId", "appr_test"));
            CompletableFuture<Void> response = published.thenRun(() -> {
                callbackRanOnWriter.set(Thread.currentThread().getName().equals("ja-rpc-writer"));
                writer.response("c:approval", mapper.createObjectNode().put("accepted", true));
                responseDone.countDown();
            });
            assertTrue(responseDone.await(2, TimeUnit.SECONDS));
            response.join();
            assertFalse(callbackRanOnWriter.get());
        }
        List<String> frames = output.toString(java.nio.charset.StandardCharsets.UTF_8).lines().toList();
        assertEquals(2, frames.size());
        assertTrue(frames.get(0).contains("approval/resolved"));
        assertTrue(frames.get(1).contains("\"id\":\"c:approval\""));
    }

    /** 双队列允许控制事实越过草稿，但 Wire sequence 必须严格跟随最终 stdout 物理顺序。 */
    @Test
    void assignsNotificationSequenceAtPhysicalWriteBoundary() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        BlockingCaptureOutput output = new BlockingCaptureOutput();
        try (StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024)) {
            CompletableFuture<Void> first = writer.notification("task/activity",
                    mapper.createObjectNode().put("marker", "first").put("sequence", 99));
            assertTrue(output.entered.await(1, TimeUnit.SECONDS));
            CompletableFuture<Void> draft = writer.delta("assistant/text-delta",
                    mapper.createObjectNode().put("marker", "draft").put("sequence", 7));
            CompletableFuture<Void> control = writer.notification("turn/terminal",
                    mapper.createObjectNode().put("marker", "control").put("sequence", 3));
            output.release.countDown();
            CompletableFuture.allOf(first, draft, control).join();
        }
        List<ObjectNode> frames = output.content().lines().map(line -> {
            try {
                return (ObjectNode) mapper.readTree(line);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        }).toList();
        assertEquals(List.of(1L, 2L, 3L), frames.stream()
                .map(frame -> frame.path("params").path("sequence").longValue()).toList());
        assertEquals(List.of("first", "control", "draft"), frames.stream()
                .map(frame -> frame.path("params").path("marker").textValue()).toList());
    }

    /** 确保被中断的刷写仍终结异步阶段，且迟到回调不能复活已关闭 Writer。 */
    @Test
    void closeCompletesLateCallbackWithoutRevivingWriter() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        BlockingOutput output = new BlockingOutput();
        StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024);
        CompletableFuture<Void> published = writer.notification("approval/resolved",
                mapper.createObjectNode().put("approvalId", "appr_close"));
        assertTrue(output.entered.await(1, TimeUnit.SECONDS));
        writer.close();
        assertTrue(published.isDone());
        assertTrue(published.isCompletedExceptionally());
        AtomicBoolean lateCallback = new AtomicBoolean();
        published.whenComplete((ignored, failure) -> lateCallback.set(true));
        assertTrue(lateCallback.get());
    }

    /** 未声明的运行时写失败必须结束活动帧和两条队列，并保留原始异常供 close 诊断。 */
    @Test
    void runtimeWriteFailureCompletesActiveAndQueuedFramesWithPrimaryCause() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RuntimeException primary = new IllegalStateException("synthetic unchecked output failure");
        OutputStream output = new OutputStream() {
            /** 阻塞首字节以确定性建立活动帧和排队帧，再抛出同一个可识别主因。 */
            @Override
            public void write(int value) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test output interrupted", interrupted);
                }
                throw primary;
            }
        };
        StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024);
        CompletableFuture<Void> active = writer.notification("approval/resolved",
                mapper.createObjectNode().put("approvalId", "appr_active"));
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            CompletableFuture<Void> queuedControl = writer.notification("turn/terminal",
                    mapper.createObjectNode().put("turnId", "turn_queued"));
            CompletableFuture<Void> queuedData = writer.delta("assistant/text-delta",
                    mapper.createObjectNode().put("turnId", "turn_draft").put("text", "draft"));
            release.countDown();

            assertSame(primary, assertThrows(ExecutionException.class,
                    () -> active.get(2, TimeUnit.SECONDS)).getCause());
            assertSame(primary, assertThrows(ExecutionException.class,
                    () -> queuedControl.get(2, TimeUnit.SECONDS)).getCause());
            assertSame(primary, assertThrows(ExecutionException.class,
                    () -> queuedData.get(2, TimeUnit.SECONDS)).getCause());
            RuntimeException closeFailure = assertThrows(RuntimeException.class, writer::close);
            assertSame(primary, closeFailure.getCause());
            assertTrue(closeFailure.getMessage().contains("stdio writer failed before close"));
        } finally {
            release.countDown();
            try {
                writer.close();
            } catch (RuntimeException ignored) {
                // 预期的投影失败已在上方断言；此 finally 仅用于避免 owner 泄漏。
            }
        }
    }

    /** 用多字节文本验证阻塞活动帧的 UTF-8 计费，防止字符数低估预算，保留 queue-full 错误映射。 */
    private static void assertLaneByteBudgetOverflow(boolean controlLane) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String text = "中".repeat(256);
        String method = controlLane ? "runtime/status-changed" : "assistant/text-delta";
        ObjectNode params = mapper.createObjectNode().put("text", text);
        int frameBytes = encodedNotificationBytes(mapper, method, params);
        long laneBudget = 2L * frameBytes - 1;
        BlockingOutput output = new BlockingOutput();
        StdioWriter writer = new StdioWriter(output, mapper, 4_096,
                controlLane ? laneBudget : StdioWriter.MAX_CONTROL_BYTES,
                controlLane ? StdioWriter.MAX_DATA_BYTES : laneBudget);
        try {
            publish(writer, mapper, controlLane, text);
            assertTrue(output.entered.await(1, TimeUnit.SECONDS));

            JaRpcException failure = assertThrows(JaRpcException.class,
                    () -> publish(writer, mapper, controlLane, text));
            assertEquals("QUEUE_FULL", failure.errorCode());
        } finally {
            output.release.countDown();
            writer.close();
        }
    }

    /** 使用实际序列化长度设置恰好容纳一帧的预算，证明 flush 后可重新准入。 */
    private static void assertLaneByteBudgetReusable(boolean controlLane) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String text = "x".repeat(256);
        String method = controlLane ? "runtime/status-changed" : "assistant/text-delta";
        int frameBytes = encodedNotificationBytes(mapper, method,
                mapper.createObjectNode().put("text", text));
        BlockingOutput output = new BlockingOutput();
        StdioWriter writer = new StdioWriter(output, mapper, 4_096, frameBytes, frameBytes);
        try {
            CompletableFuture<Void> first = publish(writer, mapper, controlLane, text);
            assertTrue(output.entered.await(1, TimeUnit.SECONDS));
            output.release.countDown();
            first.get(2, TimeUnit.SECONDS);

            publish(writer, mapper, controlLane, text).get(2, TimeUnit.SECONDS);
        } finally {
            output.release.countDown();
            writer.close();
        }
    }

    /** 复用生产信封字段与最大序号占位计算同一准入字节数，测试无需分配大帧。 */
    private static int encodedNotificationBytes(ObjectMapper mapper, String method, ObjectNode params)
            throws IOException {
        ObjectNode sequencedParams = params.deepCopy().put("sequence", Long.MAX_VALUE);
        ObjectNode envelope = mapper.createObjectNode().put("jsonrpc", "2.0").put("method", method);
        envelope.set("params", sequencedParams);
        return mapper.writeValueAsBytes(envelope).length;
    }

    /** 以完全相同的 payload 驱动指定 lane，保持预算断言针对编码大小而非调用差异。 */
    private static CompletableFuture<Void> publish(StdioWriter writer, ObjectMapper mapper,
                                                   boolean controlLane, String text) {
        ObjectNode params = mapper.createObjectNode().put("text", text);
        return controlLane ? writer.notification("runtime/status-changed", params)
                : writer.delta("assistant/text-delta", params);
    }

    /** 以可中断方式阻塞首次写入，使测试能够确定性填满队列而不依赖时序碰运气。 */
    private static final class BlockingOutput extends OutputStream {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private boolean blocked;

        /** 仅阻塞首字节，后续字节直接丢弃，避免夹具自身存储影响队列边界。 */
        @Override
        public synchronized void write(int value) throws IOException {
            if (blocked) {
                return;
            }
            blocked = true;
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        }
    }

    /** 首帧阻塞后继续捕获全部字节，用于确定性制造 control/data 选择竞争。 */
    private static final class BlockingCaptureOutput extends OutputStream {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private boolean blocked;

        /** 只在首次写入前阻塞，释放后保留完整 JSONL 供协议顺序断言。 */
        @Override
        public synchronized void write(int value) throws IOException {
            if (!blocked) {
                blocked = true;
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
            }
            delegate.write(value);
        }

        /** 返回 Writer 已关闭后的 UTF-8 内容，避免测试读取仍在变化的缓冲区。 */
        private synchronized String content() {
            return delegate.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
