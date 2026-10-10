// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.domain.model;

/**
 * Core admission 完成后返回的版本事实；Task 不从 expected 值推算任何投影版本。
 *
 * @param threadId            已提交的 Thread 标识
 * @param turnId              已接纳的 Turn 标识
 * @param threadRevision      提交后 Thread 投影版本
 * @param turnMutationVersion 提交后 Turn 变更版本
 * @param provisionalTitle    Core 授予的临时标题，可为空
 */
public record AdmissionReceipt(String threadId,
                               String turnId,
                               long threadRevision,
                               long turnMutationVersion,
                               String provisionalTitle) {
    /**
     * 回执必须来自同一次提交，所有版本均为非负且身份带类型前缀。
     */
    public AdmissionReceipt {
        threadId = identifier(threadId, "thr_", "threadId");
        turnId = identifier(turnId, "turn_", "turnId");
        if (threadRevision < 0 || turnMutationVersion < 0) {
            throw new IllegalArgumentException("invalid admission receipt versions");
        }
        if (provisionalTitle != null && (provisionalTitle.isBlank() || provisionalTitle.length() > 4096
                || provisionalTitle.indexOf('\0') >= 0)) {
            throw new IllegalArgumentException("invalid provisionalTitle");
        }
    }

    /**
     * 标题为空代表 Core 没有授予自动标题写入权，调用方据此跳过二次投影。
     */
    public boolean createdProvisionalTitle() {
        return provisionalTitle != null;
    }

    /**
     * 关联键只能使用 Collaboration 当前的持久前缀。
     */
    private static String identifier(String value, String prefix, String field) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
                || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }
}
