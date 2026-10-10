// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.shared;

import java.util.Objects;

/**
 * 生成带类型前缀的稳定身份键，避免不同生命周期把同一裸摘要误认为同一资源。
 */
public final class StableId {
    /**
     * 禁止实例化稳定标识工具类，避免把一次性身份计算误用为带状态的对象。
     */
    private StableId() {
    }

    /**
     * 将 owner 与业务键按显式 NUL 分隔后摘要，确保前缀只表达键的用途。
     *
     * @param prefix 类型前缀
     * @param owner 作用域身份
     * @param key 业务键
     * @return 形如 {@code prefix_<sha256>} 的稳定键
     */
    public static String prefixed(String prefix, String owner, String key) {
        String type = TextBoundary.identifier(prefix, "prefix");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(key, "key");
        return type + "_" + Hashing.sha256Hex(owner + "\0" + key);
    }
}
