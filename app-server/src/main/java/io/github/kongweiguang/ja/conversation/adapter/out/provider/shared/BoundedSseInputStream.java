// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * 在 Provider SSE 分帧和 JSON 解析前限制单帧资源；完整响应按流消费，不设置累计停机阈值。
 */
final class BoundedSseInputStream extends FilterInputStream {
    private final long maxEventBytes;

    private long eventBytes;
    private int lineBytes;
    private boolean previousWasCarriageReturn;
    private boolean closed;

    /**
     * 只限制当前帧占用；响应总长度与分片数量都不能成为长任务的隐式输出预算。
     */
    BoundedSseInputStream(InputStream delegate, long maxEventBytes) {
        super(Objects.requireNonNull(delegate, "delegate"));
        if (maxEventBytes <= 0) throw new IllegalArgumentException("SSE event limit must be positive");
        this.maxEventBytes = maxEventBytes;
    }

    /**
     * 单字节读取仍在交给解析器前计入当前帧，EOF 不制造额外语义事件。
     */
    @Override
    public synchronized int read() throws IOException {
        requireOpen();
        int value = in.read();
        if (value < 0) {
            return -1;
        }
        accept(value);
        return value;
    }

    /**
     * 批量读取与单字节读取共享当前帧状态，不让 HTTP 分包方式影响容量判断。
     */
    @Override
    public synchronized int read(byte[] bytes, int offset, int length) throws IOException {
        requireOpen();
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return 0;
        int read = in.read(bytes, offset, length);
        if (read < 0) {
            return -1;
        }
        for (int index = offset; index < offset + read; index++) {
            accept(Byte.toUnsignedInt(bytes[index]));
        }
        return read;
    }

    /**
     * 通过受限读取实现 skip，使并发调用和跳读都无法绕过容量计数。
     */
    @Override
    public synchronized long skip(long amount) throws IOException {
        requireOpen();
        if (amount <= 0) return 0;
        byte[] discard = new byte[(int) Math.min(8_192L, amount)];
        long skipped = 0;
        while (skipped < amount) {
            int read = read(discard, 0, (int) Math.min(discard.length, amount - skipped));
            if (read < 0) break;
            skipped += read;
        }
        return skipped;
    }

    /**
     * 禁止解析器回放已经计数的 Provider 字节。
     */
    @Override
    public boolean markSupported() {
        return false;
    }

    /**
     * 一次性流不支持 reset，因此 mark 不保留任何回放位置。
     */
    @Override
    public synchronized void mark(int readLimit) {
        // 一次性解析不得保留或回放 Provider 字节。
    }

    /**
     * 拒绝回放，保证响应和事件计数只能单调增加。
     */
    @Override
    public synchronized void reset() throws IOException {
        requireOpen();
        throw new IOException("mark/reset is not supported for bounded SSE streams");
    }

    /**
     * 与读取共享同一同步边界并幂等关闭底层响应体，保证取消线程关闭后读取线程立即可见。
     */
    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        super.close();
    }

    /**
     * 在任何委托读取前检查关闭状态，避免部分 InputStream 在 close 后继续返回缓存字节。
     */
    private void requireOpen() throws IOException {
        if (closed) throw new IOException("bounded SSE stream is closed");
    }

    /**
     * 逐字节核算帧容量并识别 LF、CRLF 与 CR，避免不同换行方式绕过单帧保护。
     */
    private void accept(int value) {
        if (previousWasCarriageReturn) {
            previousWasCarriageReturn = false;
            if (value == '\n') {
                countEventByte();
                endLine();
                return;
            }
            endLine();
        }
        countEventByte();
        if (value == '\r') {
            previousWasCarriageReturn = true;
            return;
        }
        if (value == '\n') {
            endLine();
            return;
        }
        lineBytes++;
    }

    /**
     * 空行结束非空帧，同时保留注释帧的容量计数。
     */
    private void endLine() {
        if (lineBytes == 0) {
            finishFrame();
        } else {
            lineBytes = 0;
        }
    }

    /**
     * 帧结束释放单帧计数；已处理的心跳和正文不会积累在输入流内。
     */
    private void finishFrame() {
        eventBytes = 0;
        lineBytes = 0;
    }

    /**
     * 字节到达解析器前先计入当前原始帧。
     */
    private void countEventByte() {
        eventBytes++;
        if (eventBytes > maxEventBytes) throw eventLimit();
    }

    /**
     * 创建不包含响应内容的单帧容量异常。
     */
    private static ProviderProtocolException eventLimit() {
        return new ProviderProtocolException(
                "EVENT_LIMIT", "provider event exceeds the size limit", false);
    }

}
