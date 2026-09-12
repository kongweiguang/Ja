// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 冻结一次 Provider 请求的发送正文、本地输入 Token 估算和请求指纹。
 */
public final class ProviderRequestEnvelope {
    private final byte[] sendBody;
    private final String fingerprint;
    private final long inputTokenEstimate;

    /**
     * 从唯一请求节点派生发送正文与协议感知的预算证据，估算阶段不得调用任何 Provider 计量接口。
     */
    public static ProviderRequestEnvelope freeze(ObjectNode request, ModelPort.Api api) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(api, "api");
        byte[] sendBody = AbstractStreamingModelAdapter.serializeRequest(request.deepCopy());
        long inputTokenEstimate = ProviderInputTokenEstimator.estimate(request.deepCopy(), api);
        return new ProviderRequestEnvelope(sendBody, sha256(sendBody), inputTokenEstimate);
    }

    /**
     * 防御性复制正文，使 OkHttp RequestBody 和计量缓存不能观察到调用方后续修改。
     */
    private ProviderRequestEnvelope(byte[] sendBody, String fingerprint, long inputTokenEstimate) {
        this.sendBody = Arrays.copyOf(Objects.requireNonNull(sendBody, "sendBody"), sendBody.length);
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        if (inputTokenEstimate < 0) throw new IllegalArgumentException("inputTokenEstimate must not be negative");
        this.inputTokenEstimate = inputTokenEstimate;
    }

    /** 返回发送端使用的冻结正文副本，防止传输层持有可变数组。 */
    public byte[] sendBody() {
        return Arrays.copyOf(sendBody, sendBody.length);
    }

    /** 返回由完整发送正文派生的稳定 SHA-256 指纹。 */
    public String fingerprint() {
        return fingerprint;
    }

    /**
     * 返回协议感知的本地输入 Token 保守估计。
     *
     * <p>文本采用冻结 JSON 的 UTF-8 感知近似，协议原生图片则按公开的尺寸计量规则估算，
     * 避免把 Base64 传输膨胀误算为文本 Token。兼容端点可能采用不同 tokenizer，因此该值只用于
     * 请求前本地准入；响应后的 Provider usage 与真实 {@code CONTEXT_LIMIT} 仍是权威事实。</p>
     */
    public long inputTokenEstimate() {
        return inputTokenEstimate;
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
