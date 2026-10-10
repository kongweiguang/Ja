// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;

import java.util.Objects;

/**
 * Ja 对 Provider 请求计量的业务投影；UNKNOWN 明确表示未确认的计费事实。
 *
 * @param modelRequestId 一次真实模型请求的标识
 * @param modelRequestOrdinal 模型请求顺序位置
 * @param modelRound 模型调用轮次
 * @param purpose 用途
 * @param certainty 确定性
 * @param profile 配置档案
 * @param usage 用量
 */
public record ProviderRequestUsage(String modelRequestId,
                                   int modelRequestOrdinal,
                                   int modelRound,
                                   Purpose purpose,
                                   Certainty certainty,
                                   ProviderRequestProfile profile,
                                   ModelUsage usage) {

    /**
     * 计量投影拒绝身份或精度不一致，避免把未知费用展示成零。
     */
    public ProviderRequestUsage {
        if (modelRequestId == null || !modelRequestId.startsWith("modelreq_") || modelRequestId.length() > 103
            || !modelRequestId.substring("modelreq_".length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid modelRequestId");
        }
        if (modelRequestOrdinal < 1 || modelRound < 1) {
            throw new IllegalArgumentException("invalid Provider request ordinal");
        }
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(certainty, "certainty");
        Objects.requireNonNull(profile, "profile");
        if ((certainty == Certainty.KNOWN) == (usage == null)) {
            throw new IllegalArgumentException("usage certainty does not match token facts");
        }
    }

    /**
     * 模型请求用途区分普通回复与摘要计量。
     */
    public enum Purpose {
        /**
         * 普通 Assistant 或 Tool continuation 请求。
         */
        ASSISTANT,
        /**
         * Turn 内自动或 overflow Summary 请求。
         */
        SUMMARY
    }

    /**
     * 用量确定性区分真实计量与请求结果未知。
     */
    public enum Certainty {
        /**
         * Provider 返回完整且可校验的 Token 计量。
         */
        KNOWN,
        /**
         * 请求已登记，但没有可靠 Token 计量。
         */
        UNKNOWN
    }
}
