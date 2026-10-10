// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 不含命令正文与输出的进程会话索引项，供有界列表及 RPC 投影共用。
 *
 * @param sessionId 进程会话的稳定标识
 * @param workspaceId 进程所属工作区的稳定标识
 * @param threadId 进程所属线程的稳定标识
 * @param startedTurnId 启动进程的 Turn 标识
 * @param startedCallId 启动进程的工具调用标识
 * @param generation 进程所属服务代际
 * @param state 持久化的进程生命周期状态
 * @param outputSequence 已产生输出的绝对字符序号
 * @param outputTruncated 是否已有输出超出保留后缀
 * @param exitCode 进程退出码，活动进程为空
 * @param createdAt 会话开始时间
 */
public record ProcessSessionSummary(String sessionId,
                                    String workspaceId,
                                    String threadId,
                                    String startedTurnId,
                                    String startedCallId,
                                    long generation,
                                    String state,
                                    long outputSequence,
                                    boolean outputTruncated,
                                    Integer exitCode,
                                    Instant createdAt) {
    /**
     * 统一验证从账本投影出的身份和状态，使不完整历史不能伪装成可控制会话。
     */
    public ProcessSessionSummary {
        new ProcessSessionScope(workspaceId, threadId, generation);
        requireIdentifier(sessionId, "sessionId");
        requireIdentifier(startedTurnId, "startedTurnId");
        requireIdentifier(startedCallId, "startedCallId");
        requireState(state);
        if (outputSequence < 0) {
            throw new IllegalArgumentException("invalid output sequence");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * 拒绝不可稳定回读的身份值，避免控制字符或任意长数据跨越应用边界。
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

    /**
     * 将持久化状态限制在会话生命周期闭集内，避免读回时静默改写未知值。
     *
     * @param value 待验证的生命周期状态
     */
    private static void requireState(String value) {
        if (!Objects.equals(value, "starting") && !Objects.equals(value, "running")
                && !Objects.equals(value, "stopping") && !Objects.equals(value, "exited")
                && !Objects.equals(value, "failed") && !Objects.equals(value, "interrupted")) {
            throw new IllegalArgumentException("invalid process state");
        }
    }
}
