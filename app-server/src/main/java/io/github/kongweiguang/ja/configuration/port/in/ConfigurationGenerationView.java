// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.port.in;

import io.github.kongweiguang.ja.configuration.domain.ConfigurationGenerationSnapshot;

/**
 * catalog 与 conversation 可读取的配置代际入站视图。
 *
 * <p>具体投影模型由配置领域持有；该类型只标记已经通过应用层发布的读取能力。</p>
 */
public interface ConfigurationGenerationView extends ConfigurationGenerationSnapshot {
}
