// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 冻结一次 Provider 请求的发送正文、精确计量正文和 Token 相关指纹。
 *
 * <p>计量接口通常不接受 {@code stream} 等传输控制字段，因此两个 HTTP 正文不要求字节完全
 * 相同；它们必须由同一个冻结节点派生，且只允许删除不影响输入 Token 的传输字段。</p>
 */
public final class ProviderRequestEnvelope {
    private final byte[] sendBody;
    private final byte[] countBody;
    private final String fingerprint;

    /**
     * 从唯一冻结请求节点派生两个正文，避免 count 与 send 分别执行可能漂移的业务映射。
     */
    public static ProviderRequestEnvelope freeze(ObjectNode request) {
        Objects.requireNonNull(request, "request");
        ObjectNode send = request.deepCopy();
        ObjectNode count = request.deepCopy();
        count.remove("stream");
        byte[] sendBody = AbstractStreamingModelAdapter.serializeRequest(send);
        byte[] countBody = AbstractStreamingModelAdapter.serializeRequest(count);
        return new ProviderRequestEnvelope(sendBody, countBody, sha256(countBody));
    }

    /**
     * 防御性复制正文，使 OkHttp RequestBody 和计量缓存不能观察到调用方后续修改。
     */
    private ProviderRequestEnvelope(byte[] sendBody, byte[] countBody, String fingerprint) {
        this.sendBody = Arrays.copyOf(Objects.requireNonNull(sendBody, "sendBody"), sendBody.length);
        this.countBody = Arrays.copyOf(Objects.requireNonNull(countBody, "countBody"), countBody.length);
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
    }

    /** 返回发送端使用的冻结正文副本，防止传输层持有可变数组。 */
    public byte[] sendBody() {
        return Arrays.copyOf(sendBody, sendBody.length);
    }

    /** 返回计量端使用的冻结正文副本，防止重试改变指纹对应内容。 */
    public byte[] countBody() {
        return Arrays.copyOf(countBody, countBody.length);
    }

    /** 返回仅由 Token 相关计量正文派生的稳定 SHA-256 指纹。 */
    public String fingerprint() {
        return fingerprint;
    }

    /**
     * 返回冻结计量正文的 UTF-8 字节数作为 Token 严格上界。
     *
     * <p>该值只供明确缺少官方计量端点的 OpenAI-compatible 服务使用。完整历史 JSON 中每个
     * tokenizer token 至少消费一个底层字节，因此字节数可能显著高估，但不会低估窗口占用。</p>
     */
    public long conservativeInputTokenUpperBound() {
        return countBody.length;
    }

    /**
     * 使用进程必备的 SHA-256 生成缓存键；缺少算法属于不可恢复的运行时损坏。
     */
    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new ProviderProtocolException(
                    "REQUEST_ENCODING", "SHA-256 is unavailable for provider request fingerprinting", false,
                    new IllegalStateException("SHA-256 is unavailable", impossible));
        }
    }
}
