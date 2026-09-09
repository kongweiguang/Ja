// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;

import java.util.Objects;

/**
 * 一次真实 Provider 请求的计量与完整非敏感 Profile；UNKNOWN 也是一等持久事实。
 */
public record ProviderRequestUsage(
        String requestId,
        int requestOrdinal,
        int modelRound,
        Purpose purpose,
        Certainty certainty,
        ProviderRequestProfile profile,
        ModelUsage usage) {

    /** 每条请求都保存完整 Profile；UNKNOWN 只表达 token 尚不可知，不能弱化请求身份。 */
    public ProviderRequestUsage {
        if (requestId == null || !requestId.startsWith("request_") || requestId.length() > 103
                || !requestId.substring("request_".length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid requestId");
        }
        if (requestOrdinal < 1 || requestOrdinal > 1_024 || modelRound < 1 || modelRound > 128) {
            throw new IllegalArgumentException("invalid Provider request ordinal");
        }
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(certainty, "certainty");
        Objects.requireNonNull(profile, "profile");
        if ((certainty == Certainty.KNOWN) != (usage != null)) {
            throw new IllegalArgumentException("usage certainty does not match token facts");
        }
    }

    /** Provider 请求用途参与恢复解释与跨端去重。 */
    public enum Purpose {
        /** 普通 Assistant 或 Tool continuation 请求。 */
        ASSISTANT,
        /** Turn 内自动或 overflow Summary 请求。 */
        SUMMARY
    }

    /** UNKNOWN 表示请求可能已计费但 Token 不可知，不能被解释成零。 */
    public enum Certainty {
        /** Provider 返回了完整可校验的 Token 计量。 */
        KNOWN,
        /** 请求已登记，但尚无可靠 Token 计量。 */
        UNKNOWN
    }
}
