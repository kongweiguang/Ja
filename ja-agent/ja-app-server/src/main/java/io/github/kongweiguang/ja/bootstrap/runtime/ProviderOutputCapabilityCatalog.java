// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap.runtime;

import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationGenerationSnapshot;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * 版本随 Ja 分发的已知输出能力目录；未知端点和别名不会借名称前缀套用厂商额度。
 */
final class ProviderOutputCapabilityCatalog {
    private static final int DEEPSEEK_V4_MAX_OUTPUT_TOKENS = 384_000;
    private static final List<Entry> ENTRIES = List.of(
            new Entry("api.deepseek.com", ConfigurationGenerationSnapshot.Api.OPENAI_CHAT_COMPLETIONS,
                    "deepseek-flash", DEEPSEEK_V4_MAX_OUTPUT_TOKENS),
            new Entry("api.deepseek.com", ConfigurationGenerationSnapshot.Api.OPENAI_CHAT_COMPLETIONS,
                    "deepseek-v4-pro", DEEPSEEK_V4_MAX_OUTPUT_TOKENS));

    /**
     * 仅在 HTTPS 端点、API 与上游模型标识全部精确匹配时返回已核验的单次输出上限。
     */
    static OptionalInt maxOutputTokens(URI baseUrl, ConfigurationGenerationSnapshot.Api api, String model) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(model, "model");
        if (!"https".equalsIgnoreCase(baseUrl.getScheme())
            || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null || baseUrl.getFragment() != null
            || !"api.deepseek.com".equalsIgnoreCase(baseUrl.getHost())
            || baseUrl.getPort() != -1 && baseUrl.getPort() != 443
            || !("".equals(baseUrl.getPath()) || "/".equals(baseUrl.getPath())
                 || "/v1".equals(baseUrl.getPath()) || "/v1/".equals(baseUrl.getPath()))) {
            return OptionalInt.empty();
        }
        return ENTRIES.stream()
                .filter(entry -> entry.host().equalsIgnoreCase(baseUrl.getHost())
                                 && entry.api() == api && entry.model().equals(model))
                .mapToInt(Entry::maxOutputTokens)
                .findFirst();
    }

    /**
     * 每条目录记录绑定完整连接身份，不以模型名称片段推测能力。
     *
     * @param host 宿主
     * @param api 模型 API 协议
     * @param model 模型
     * @param maxOutputTokens 单次模型输出的 Token 上限
     */
    private record Entry(String host,
                         ConfigurationGenerationSnapshot.Api api,
                         String model,
                         int maxOutputTokens) {
        /**
         * 目录初始化时校验内置记录，避免随版本分发无效额度。
         */
        private Entry {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(api, "api");
            Objects.requireNonNull(model, "model");
            if (maxOutputTokens < 1) {
                throw new IllegalArgumentException("invalid output capability");
            }
        }
    }
}
