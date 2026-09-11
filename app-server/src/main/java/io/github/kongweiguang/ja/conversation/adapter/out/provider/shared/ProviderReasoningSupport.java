// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import io.github.kongweiguang.ja.conversation.domain.model.ReasoningContent;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;

import java.util.Locale;
import java.util.Objects;

/**
 * 为各 Provider 适配器统一生成 reasoning opaque 内容的身份信封。
 *
 * <p>Provider 的原生字段名称可以不同，但历史匹配必须使用同一组 canonical 身份；集中在这里
 * 可避免某个适配器使用枚举大写名而上下文恢复使用配置文档的小写名，造成 reasoning 被静默丢弃。</p>
 */
public final class ProviderReasoningSupport {
    /** 静态身份工厂不允许被注册为运行时服务。 */
    private ProviderReasoningSupport() {
    }

    /**
     * 将已完整关闭的原生 JSON 封装为同身份可回传的历史块；该方法不复制或记录 API key。
     */
    public static ReasoningContent capture(ModelPort.ModelConfiguration configuration,
                                           String wireField, String nativeJson) {
        Objects.requireNonNull(configuration, "configuration");
        return new ReasoningContent(
                configuration.providerId(),
                configuration.modelId(),
                configuration.api().name().toLowerCase(Locale.ROOT),
                configuration.model(),
                ReasoningContent.endpointFingerprint(configuration.baseUri()),
                wireField,
                nativeJson);
    }

    /**
     * 返回与持久化 ProviderRequestProfile 相同的小写 API 名，供 Adapter 判断历史块能否回传。
     */
    public static String canonicalApi(ModelPort.ModelConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        return configuration.api().name().toLowerCase(Locale.ROOT);
    }
}
