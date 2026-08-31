// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.json;

/**
 * 提供 JSON 对象构造入口，保持可变 Builder 与不可变领域值的生命周期边界清晰。
 */
public final class JsonObjects {
    /**
     * 返回一个新的有序 Builder，调用方完成构造后必须通过 build 冻结。
     */
    public static JsonObjectBuilder builder() {
        return new JsonObjectBuilder();
    }

    /**
     * 纯静态工厂禁止实例化，避免被依赖注入容器误注册。
     */
    private JsonObjects() {
    }
}
