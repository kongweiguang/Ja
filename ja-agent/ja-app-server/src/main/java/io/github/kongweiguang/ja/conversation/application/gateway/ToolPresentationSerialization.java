// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;

/**
 * 业务侧只依赖安全展示序列化能力，避免执行适配器反向依赖持久化实现包。
 */
@FunctionalInterface
public interface ToolPresentationSerialization {
    /**
     * 只编码已脱敏的展示 DTO，使调用方无法借此持久化原始工具参数或结果。
     */
    String write(ToolPresentation presentation);
}
