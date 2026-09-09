// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.transport.rpc.runtime;

import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证唯一 stdout 写入所有者同时强制帧大小与控制队列上限。 */
final class StdioWriterTest {
    /** 错误 Wire 必须包含完整 typed data、每实例唯一 ID，并仅在显式退避时输出正整数。 */
    @Test
    void writesStrictTypedErrorData() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        JaRpcException first = JaRpcException.of(JaErrorCatalog.INVALID_PARAMS, "invalid params");
        JaRpcException second = JaRpcException.withRetryAfter(JaErrorCatalog.QUEUE_FULL, "queue full", 250);
        try (StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024)) {
            writer.error("c:first", first).join();
            writer.error("c:second", second).join();
        }
        List<String> frames = output.toString(java.nio.charset.StandardCharsets.UTF_8).lines().toList();
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

    /** 以可中断方式阻塞首次写入，使测试能够确定性填满队列而不依赖时序碰运气。 */
    private static final class BlockingOutput extends OutputStream {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private boolean blocked;

        /** 仅阻塞首字节，后续字节直接丢弃，避免夹具自身存储影响队列边界。 */
        @Override
        public synchronized void write(int value) throws IOException {
            if (blocked) return;
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
