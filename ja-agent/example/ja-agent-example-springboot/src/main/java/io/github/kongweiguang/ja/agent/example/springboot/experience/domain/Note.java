// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.experience.domain;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 已提交的笔记回执；完整 owner、Turn 和资料摘要用于隔离读取及恢复核验。
 *
 * @param callKey 稳定工具调用身份
 * @param tenantId 所属租户
 * @param userId 所属用户
 * @param threadId 所属会话
 * @param turnId 产生笔记的执行轮次
 * @param title 笔记标题
 * @param body 笔记正文
 * @param sourceAttachmentIds 资料来源附件标识
 * @param argumentFingerprint 规范化输入指纹
 * @param createdAt 数据库提交时间
 */
public record Note(String callKey,
                   String tenantId,
                   String userId,
                   String threadId,
                   String turnId,
                   String title,
                   String body,
                   List<String> sourceAttachmentIds,
                   String argumentFingerprint,
                   Instant createdAt) {
    /**
     * 只允许带有持久 owner、来源和数据库时间的已保存回执离开 repository。
     */
    public Note {
        callKey = ContractChecks.identifier(callKey, "callKey");
        tenantId = ContractChecks.identifier(tenantId, "tenantId");
        userId = ContractChecks.identifier(userId, "userId");
        threadId = ContractChecks.identifier(threadId, "threadId");
        turnId = ContractChecks.identifier(turnId, "turnId");
        title = ContractChecks.text(title, "title", 200, false);
        body = ContractChecks.text(body, "body", 16_000, false);
        sourceAttachmentIds = List.copyOf(Objects.requireNonNull(sourceAttachmentIds, "sourceAttachmentIds"));
        if (sourceAttachmentIds.stream().distinct().count() != sourceAttachmentIds.size()) {
            throw new IllegalArgumentException("invalid note sources");
        }
        argumentFingerprint = Objects.requireNonNull(argumentFingerprint, "argumentFingerprint");
        if (!argumentFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid note argument fingerprint");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * 恢复只复用完全相同的已提交副作用，不能把 callKey 冲突当作成功回执。
     */
    public boolean matches(String expectedTenantId, String expectedUserId, String expectedThreadId,
                           String expectedTurnId, String expectedTitle, String expectedBody,
                           List<String> expectedSources, String expectedFingerprint) {
        return tenantId.equals(expectedTenantId) && userId.equals(expectedUserId)
                && threadId.equals(expectedThreadId) && turnId.equals(expectedTurnId)
                && title.equals(expectedTitle) && body.equals(expectedBody)
                && sourceAttachmentIds.equals(expectedSources)
                && argumentFingerprint.equals(expectedFingerprint);
    }
}
