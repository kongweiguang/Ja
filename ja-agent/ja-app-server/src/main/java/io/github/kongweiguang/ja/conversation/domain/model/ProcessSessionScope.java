// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

/**
 * 绑定进程会话的工作区、线程与运行时代际，防止身份的一部分被调用方省略。
 *
 * @param workspaceId 进程所属工作区的稳定标识
 * @param threadId 进程所属线程的稳定标识
 * @param generation 创建或当前控制该进程会话的服务代际
 */
public record ProcessSessionScope(String workspaceId,
                                  String threadId,
                                  long generation) {
    /**
     * 在作用域进入会话服务前检查身份完整性与代际范围，避免后续端口依赖调用顺序保护。
     */
    public ProcessSessionScope {
        requireIdentifier(workspaceId, "workspaceId");
        requireIdentifier(threadId, "threadId");
        if (generation < 1 || generation > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException("invalid process generation");
        }
    }

    /**
     * 保留有限且可打印的稳定标识，但不把具体前缀规则重复写入业务作用域。
     *
     * @param value 待验证的身份值
     * @param field 错误诊断使用的字段名
     */
    private static void requireIdentifier(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
    }
}
