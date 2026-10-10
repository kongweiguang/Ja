// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.json;

import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;

/**
 * 提供不带宿主配置读取的严格 JSON 编解码器，供纯 Java Engine 组合根直接装配。
 */
public final class JsonValueCodecFactory {
    /**
     * 静态工厂无实例状态，避免把 JSON 策略藏在可变的服务定位器中。
     */
    private JsonValueCodecFactory() {
    }

    /**
     * 创建采用重复键与尾随内容拒绝规则的标准值 codec；调用方可自行保留返回实例。
     */
    public static JsonValueCodec standard() {
        return new JacksonJsonValueCodec();
    }
}
