// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.experience.application.gateway;

import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;

import java.util.List;

/**
 * 对外展示实际注册工具的只读契约，接口层不依赖 Core 扩展实现。
 */
public interface ExperienceToolCatalog {
    /** 返回与 Engine 注册完全一致的不可变工具声明。 */
    List<ToolSpec> definitions();
}
