// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model.permission;

/**
 * Ja 对会话工具访问方式的选择；它属于宿主授权语义，不由通用 Agent Core 解释。
 */
public enum AccessMode {
    /**
     * 需要批准后才能执行有副作用的工具。
     */
    APPROVAL_REQUIRED,
    /**
     * 允许本会话直接执行已登记的工具。
     */
    FULL_ACCESS
}
