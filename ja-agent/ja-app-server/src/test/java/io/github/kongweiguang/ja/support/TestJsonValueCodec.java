// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.support;

import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.infrastructure.json.JsonValueCodecFactory;

/**
 * 跨层测试复用生产 JSON 边界，避免夹具与线上严格解析规则漂移。
 */
public final class TestJsonValueCodec implements JsonValueCodec {
    private final JsonValueCodec delegate = JsonValueCodecFactory.standard();

    /**
     * 委托生产编码器，保持 sealed 值映射与十进制精度策略一致。
     */
    @Override
    public String encode(JsonValue value) {
        return delegate.encode(value);
    }

    /**
     * 委托生产解码器，保持重复键、尾随内容和缺失根节点的失败语义一致。
     */
    @Override
    public JsonValue decode(String encodedValue) {
        return delegate.decode(encodedValue);
    }
}
