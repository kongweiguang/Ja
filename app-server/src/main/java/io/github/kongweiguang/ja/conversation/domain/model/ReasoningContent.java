// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Provider 原生 reasoning 内容块。
 *
 * <p>该块只用于 Java 权威历史和匹配的 Provider 请求；opaque JSON 不得进入普通展示、日志或
 * 摘要文本。身份字段刻意包含端点指纹，避免同一 provider 配置更换网关后误回传签名材料。</p>
 */
public record ReasoningContent(
        String providerId,
        String modelId,
        String api,
        String upstreamModel,
        String endpointFingerprint,
        String wireField,
        String nativeJson) implements ModelContent {
    private static final int MAX_NATIVE_JSON = 4_000_000;

    /**
     * 冻结 Provider 身份与原生 JSON，并以统一上限阻止 opaque 状态绕过上下文容量边界。
     */
    public ReasoningContent {
        providerId = ContractChecks.identifier(providerId, "providerId");
        modelId = ContractChecks.identifier(modelId, "modelId");
        api = ContractChecks.text(api, "api", 64, false);
        /* 上游模型沿用 ModelConfiguration.model 的文本域；OpenRouter 等真实标识允许 slash。 */
        upstreamModel = ContractChecks.text(upstreamModel, "upstreamModel", 512, false);
        if (endpointFingerprint == null || !endpointFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("endpointFingerprint must be a SHA-256 hex value");
        }
        wireField = ContractChecks.text(wireField, "wireField", 128, false);
        if (!wireField.matches("[A-Za-z][A-Za-z0-9_.:-]*")) {
            throw new IllegalArgumentException("wireField must be a safe JSON field name");
        }
        nativeJson = ContractChecks.text(nativeJson, "nativeJson", MAX_NATIVE_JSON, false);
    }

    /**
     * 使用稳定 URI 规范生成非敏感端点指纹；凭据和 query/fragment 永不进入历史。
     */
    public static String endpointFingerprint(URI baseUri) {
        if (baseUri == null || baseUri.getScheme() == null || baseUri.getHost() == null) {
            throw new IllegalArgumentException("baseUri must contain a scheme and host");
        }
        String scheme = baseUri.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host = baseUri.getHost().toLowerCase(java.util.Locale.ROOT);
        int port = baseUri.getPort();
        String path = baseUri.getPath() == null ? "" : baseUri.getPath();
        while (path.endsWith("/") && !path.isEmpty()) path = path.substring(0, path.length() - 1);
        String canonical = scheme + "://" + host + (port < 0 ? "" : ":" + port) + path;
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 仅在 Provider、模型、API、上游模型和端点均相同的请求中允许原生块回传。
     */
    public boolean matches(String requestedProviderId, String requestedModelId, String requestedApi,
                           String requestedUpstreamModel, String requestedEndpointFingerprint) {
        return providerId.equals(requestedProviderId)
                && modelId.equals(requestedModelId)
                && api.equals(requestedApi)
                && upstreamModel.equals(requestedUpstreamModel)
                && endpointFingerprint.equals(requestedEndpointFingerprint);
    }

    /**
     * 为摘要恢复指纹提供 opaque 内容的不可逆区分，避免不同原生块被 redacted toString 合并。
     */
    public String nativeJsonFingerprint() {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(nativeJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 隐藏 reasoning 原文、签名和 encrypted_content，避免异常或调试日志跨越 opaque 边界。
     */
    @Override
    public String toString() {
        return "ReasoningContent[providerId=" + providerId
                + ", modelId=" + modelId
                + ", api=" + api
                + ", upstreamModel=" + upstreamModel
                + ", endpointFingerprint=" + endpointFingerprint
                + ", wireField=" + wireField
                + ", nativeJson=<redacted>]";
    }
}
