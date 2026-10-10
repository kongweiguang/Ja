// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.model.shared;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.infrastructure.model.ProviderProtocolException;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 冻结一次 Provider 请求的内存 JSON、协议感知预算和请求指纹。
 */
public final class ProviderRequestEnvelope implements AutoCloseable {
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final Object lifecycleLock = new Object();
    private final long bodyBytes;
    private final String fingerprint;
    private final long inputTokenEstimate;
    private final long displayInputTokenEstimate;
    private ObjectNode body;
    private volatile boolean closed;

    /**
     * 保存隔离树和同一次 JSON 序列化的摘要，拒绝估算正文与实际发送正文发生漂移。
     */
    private ProviderRequestEnvelope(ObjectNode body, long bodyBytes, String fingerprint,
                                    long displayInputTokenEstimate, long inputTokenEstimate) {
        this.body = Objects.requireNonNull(body, "body");
        if (bodyBytes < 0) {
            throw new IllegalArgumentException("request body length must not be negative");
        }
        this.bodyBytes = bodyBytes;
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        if (displayInputTokenEstimate < 0 || inputTokenEstimate < displayInputTokenEstimate) {
            throw new IllegalArgumentException("request input estimates are invalid");
        }
        this.displayInputTokenEstimate = displayInputTokenEstimate;
        this.inputTokenEstimate = inputTokenEstimate;
    }

    /**
     * 用隔离 JSON 树冻结发送内容；序列化只流过计数与摘要流，避免把完整 HTTP 正文写到本地或另建无界字节副本。
     */
    @SuppressWarnings("PMD.PreserveStackTrace") // 已分类的 ProviderProtocolException 原样重抛，保留固定协议错误。
    public static ProviderRequestEnvelope freeze(ObjectNode request, ModelPort.Api api) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(api, "api");
        try {
            ObjectNode frozenBody = request.deepCopy();
            ProviderInputTokenEstimator.Estimate estimate =
                    ProviderInputTokenEstimator.estimate(frozenBody.deepCopy(), api);
            MessageDigest digest = sha256();
            try (CountingDigestOutputStream output = new CountingDigestOutputStream(
                    OutputStream.nullOutputStream(), digest, null)) {
                AbstractStreamingModelAdapter.JSON.writeValue(output, frozenBody);
                return new ProviderRequestEnvelope(frozenBody, output.byteCount(), output.fingerprint(),
                        estimate.estimatedTokens(), estimate.conservativeUpperBound());
            }
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof ProviderProtocolException providerFailure) {
                throw providerFailure;
            }
            throw new ProviderProtocolException(
                    "REQUEST_ENCODING", "provider request could not be frozen", false, failure);
        }
    }

    /**
     * 创建独立 SHA-256 状态，算法缺失属于本地配置故障而不是可重试网络错误。
     */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new ProviderProtocolException("REQUEST_ENCODING", "SHA-256 is unavailable", false, unavailable);
        }
    }

    /**
     * 创建一次性 JSON RequestBody；写入结束即清空冻结树，不让 OkHttp 隐式重放正文。
     */
    public RequestBody requestBody() {
        ensureOpen();
        return new RequestBody() {
            /** 三类 Provider JSON 协议统一使用 UTF-8 JSON media type。 */
            @Override
            public MediaType contentType() {
                return JSON_MEDIA_TYPE;
            }

            /** 冻结阶段计得的精确长度，避免兼容端点退回未知长度分块传输。 */
            @Override
            public long contentLength() {
                return bodyBytes;
            }

            /** 每次重试都必须重新走 Agent 请求身份与冻结流程。 */
            @Override
            public boolean isOneShot() {
                return true;
            }

            /** 流式序列化隔离树并复核字节数与指纹，异常或取消后也立即释放树。 */
            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                Objects.requireNonNull(sink, "sink");
                try (OutputStream nonClosingSink = new FilterOutputStream(sink.outputStream()) {
                    /** 序列化器关闭流时只刷新 Okio sink，HTTP 交换仍拥有它的生命周期。 */
                    @Override
                    public void close() throws IOException {
                        flush();
                    }
                }) {
                    writeFrozenBody(nonClosingSink);
                } finally {
                    ProviderRequestEnvelope.this.close();
                }
            }
        };
    }

    /**
     * 供协议测试读取正文；生产发送走流式 RequestBody，不构造完整 byte[]。
     */
    public byte[] sendBody() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            writeFrozenBody(output);
            return output.toByteArray();
        } catch (IOException failure) {
            throw new ProviderProtocolException("REQUEST_ENCODING", "provider request body is unavailable", false,
                    failure);
        } finally {
            close();
        }
    }

    /**
     * 返回与冻结发送正文对应的 SHA-256 指纹。
     */
    public String fingerprint() {
        return fingerprint;
    }

    /**
     * 返回协议感知的输入 Token 保守估计，不读取发送正文副本。
     */
    public long inputTokenEstimate() {
        return inputTokenEstimate;
    }

    /**
     * 返回未加准入余量的基础估算，供界面准确标为本地估算。
     */
    public long displayInputTokenEstimate() {
        return displayInputTokenEstimate;
    }

    /**
     * 序列化唯一冻结树并校验发送字节；临时摘要流只跟踪实际写出的 HTTP 内容，不保存正文。
     */
    private void writeFrozenBody(OutputStream target) throws IOException {
        ObjectNode snapshot = frozenBody();
        try (CountingDigestOutputStream output = new CountingDigestOutputStream(target, sha256(), this)) {
            AbstractStreamingModelAdapter.JSON.writeValue(output, snapshot);
            if (output.byteCount() != bodyBytes || !fingerprint.equals(output.fingerprint())) {
                throw new ProviderProtocolException(
                        "REQUEST_ENCODING", "provider request changed after it was frozen", false);
            }
        }
    }

    /**
     * 取得仅供内部序列化使用的隔离树，关闭后立即拒绝新的正文读取。
     */
    private ObjectNode frozenBody() {
        synchronized (lifecycleLock) {
            ensureOpen();
            return body;
        }
    }

    /**
     * 用私有生命周期锁清空 Prompt 树，避免公开实例锁被调用方争用或形成外部锁顺序。
     */
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            body = null;
        }
    }

    /**
     * 检查流式发送尚未被取消或关闭打断。
     */
    private void ensureWritable() throws IOException {
        if (closed) {
            throw new IOException("provider request envelope is closed");
        }
    }

    /**
     * 在私有锁保护下拒绝读取已经释放的 Provider 请求快照。
     */
    private void ensureOpen() {
        synchronized (lifecycleLock) {
            if (closed || body == null) {
                throw new IllegalStateException("provider request envelope is closed");
            }
        }
    }

    /**
     * 对冻结与实际发送使用同一计数/摘要链；目标关闭由 RequestBody 的非关闭包装器控制。
     */
    private static final class CountingDigestOutputStream extends DigestOutputStream {
        private final OutputStream target;
        private final ProviderRequestEnvelope owner;
        private long byteCount;

        /**
         * 把 Okio、测试缓冲区或空输出流接入有界状态，不创建中间正文数组。
         */
        private CountingDigestOutputStream(OutputStream target, MessageDigest digest,
                                           ProviderRequestEnvelope owner) {
            super(Objects.requireNonNull(target, "target"), Objects.requireNonNull(digest, "digest"));
            this.target = target;
            this.owner = owner;
        }

        /**
         * 只在目标成功接收单字节后计数，避免失败写入伪造冻结长度。
         */
        @Override
        public void write(int value) throws IOException {
            ensureOwnerOpen();
            target.write(value);
            getMessageDigest().update((byte) value);
            addByteCount(1);
        }

        /**
         * 直接分段写入目标并同步更新摘要，不把 body 聚合到内存数组。
         */
        @Override
        public void write(byte[] value, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, value.length);
            ensureOwnerOpen();
            target.write(value, offset, length);
            getMessageDigest().update(value, offset, length);
            addByteCount(length);
        }

        /**
         * 序列化完成时刷新目标但不改变已计数内容。
         */
        @Override
        public void flush() throws IOException {
            target.flush();
        }

        /**
         * 关闭本层流以刷新目标，真实网络 sink 是否关闭由调用方显式决定。
         */
        @Override
        public void close() throws IOException {
            target.close();
        }

        /**
         * 返回精确 UTF-8 JSON 字节数，供 Content-Length 和实际发送复核共同使用。
         */
        private long byteCount() {
            return byteCount;
        }

        /**
         * 消费摘要并返回小写十六进制指纹；同一序列化只能读取一次。
         */
        private String fingerprint() {
            return HexFormat.of().formatHex(getMessageDigest().digest());
        }

        /**
         * 关闭竞态必须中止后续序列化，避免已取消请求继续消耗编码 CPU。
         */
        private void ensureOwnerOpen() throws IOException {
            if (owner != null) {
                owner.ensureWritable();
            }
        }

        /**
         * 防止极端长度溢出后生成错误 Content-Length。
         */
        private void addByteCount(long amount) throws IOException {
            try {
                byteCount = Math.addExact(byteCount, amount);
            } catch (ArithmeticException overflow) {
                throw new IOException("provider request length overflow", overflow);
            }
        }
    }
}
