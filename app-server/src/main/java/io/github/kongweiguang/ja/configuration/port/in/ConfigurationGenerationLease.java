// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.port.in;

/**
 * 一次 Turn 对不可变配置代际的所有权。
 *
 * <p>端口只表达生命周期；具体配置投影由领域消费者自己的出站适配器读取，避免 catalog
 * 或 conversation 反向依赖 configuration 的文件实现。</p>
 */
public interface ConfigurationGenerationLease extends AutoCloseable {
    /**
     * 返回不透明代际标识，用于关联事件而不泄露配置内容或本地路径。
     */
    String generationId();

    /**
     * 返回当前租约固定的脱敏 catalog 投影，关闭后必须拒绝读取。
     */
    ConfigurationGenerationView view();

    /**
     * 为当前连接短时借用一个 secret。
     *
     * <p>调用方不得缓存或记录返回值，并必须在 Provider/MCP 请求结束后及时释放租约。</p>
     */
    String secretFor(String credentialId);

    /**
     * 释放当前 Turn 的唯一持有权；实现必须幂等并在最后一个租约结束时清除 secret。
     */
    @Override
    void close();
}
