// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.permission;

/** 审批审计保留的四类 Tool 动作，不参与权限矩阵或会话授权。 */
public enum PermissionAction {
    /**
     * 读取文件或无副作用状态。
     */
    READ,
    /**
     * 修改工作区文件或持久状态。
     */
    WRITE,
    /**
     * 启动 Shell 进程。
     */
    SHELL,
    /**
     * 调用 MCP Server 暴露的 Tool。
     */
    MCP
}
