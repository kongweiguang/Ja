// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

/**
 * Composer 协作语义的稳定闭集；它与工具访问策略正交，不能被解释为权限等级。
 */
public enum CollaborationMode {
    /** 直接执行用户请求。 */
    DEFAULT,
    /** 先形成结构化计划，批准后再进入 Goal 执行。 */
    PLAN
}
