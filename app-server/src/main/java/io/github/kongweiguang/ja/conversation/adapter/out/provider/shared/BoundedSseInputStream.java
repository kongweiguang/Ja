// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * 在 Provider SSE 分帧和 JSON 解析前执行透明的原始字节边界保护。
 */
final class BoundedSseInputStream extends FilterInputStream {
    private final long maxEventBytes;
    private final long maxResponseBytes;
    private final long maxEventCount;

    private long responseBytes;
    private long eventBytes;
    private long eventCount;
    private int lineBytes;
    private boolean frameHasContent;
    private boolean previousWasCarriageReturn;
    private boolean eofProcessed;
    private boolean closed;

    /**
     * 创建仅向前读取的保护流，并分别限制单帧、整段响应和帧数量。
     */
    BoundedSseInputStream(InputStream delegate, long maxEventBytes,
                          long maxResponseBytes, long maxEventCount) {
        super(Objects.requireNonNull(delegate, "delegate"));
        if (maxEventBytes <= 0 || maxResponseBytes <= 0 || maxEventCount <= 0) {
            throw new IllegalArgumentException("SSE limits must be positive");
        }
        if (maxEventBytes > maxResponseBytes) {
            throw new IllegalArgumentException("event limit must not exceed response limit");
        }
        this.maxEventBytes = maxEventBytes;
        this.maxResponseBytes = maxResponseBytes;
        this.maxEventCount = maxEventCount;
    }

    /**
     * 串行读取一个原始字节并只结算一次 EOF 帧；同步边界保证意外的并发读取无法破坏累计状态。
     */
    @Override
    public synchronized int read() throws IOException {
        requireOpen();
        if (responseBytes == maxResponseBytes) {
            int value = in.read();
            if (value < 0) {
                finishEof();
                return -1;
            }
            throw responseLimit();
        }
        int value = in.read();
        if (value < 0) {
            finishEof();
            return -1;
        }
        accept(value);
        return value;
    }

    /**
     * 在向解析器暴露批量字节前，以同一把锁完成响应、事件和行状态的原子更新。
     */
    @Override
    public synchronized int read(byte[] bytes, int offset, int length) throws IOException {
        requireOpen();
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return 0;
        long remaining = maxResponseBytes - responseBytes;
        if (remaining == 0) {
            int value = in.read();
            if (value < 0) {
                finishEof();
                return -1;
            }
            throw responseLimit();
        }
        int allowed = (int) Math.min((long) length, remaining);
        int read = in.read(bytes, offset, allowed);
        if (read < 0) {
            finishEof();
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
     * 结算一个原始字节，并识别 LF、CRLF 与 CR 三种行边界。
     */
    private void accept(int value) {
        responseBytes++;
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
        frameHasContent = true;
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
     * 计入一个非空原始帧，并在下一帧前重置单帧容量状态。
     */
    private void finishFrame() {
        if (frameHasContent && ++eventCount > maxEventCount) throw responseLimit();
        eventBytes = 0;
        frameHasContent = false;
        lineBytes = 0;
    }

    /**
     * 结算未终止的最后一行或帧，避免解析器静默丢弃容量事实。
     */
    private void finishEof() {
        if (eofProcessed) return;
        eofProcessed = true;
        if (previousWasCarriageReturn) {
            previousWasCarriageReturn = false;
            endLine();
        }
        if (frameHasContent && ++eventCount > maxEventCount) throw responseLimit();
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

    /**
     * 创建不包含响应内容的整段流容量异常。
     */
    private static ProviderProtocolException responseLimit() {
        return new ProviderProtocolException(
                "RESPONSE_LIMIT", "provider response exceeds the size or event-count limit", false);
    }
}
