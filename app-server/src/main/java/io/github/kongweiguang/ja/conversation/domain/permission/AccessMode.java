// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.permission;

/**
 * 用户为 Turn 选择的唯一审批行为；文件与命令访问范围始终等同桌面进程账户。
 */
public enum AccessMode {
    /**
     * 仅允许无副作用的读取能力。
     */
    APPROVAL_REQUIRED,
    /**
     * 所有 Tool 直接执行，不产生审批等待。
     */
    FULL_ACCESS
}
