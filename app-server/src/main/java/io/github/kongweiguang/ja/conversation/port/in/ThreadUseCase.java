// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.in;

import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.TurnSummary;
import io.github.kongweiguang.ja.conversation.domain.TurnChangeSet;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;

import java.util.Optional;

/**
 * 定义 Thread 生命周期与权威历史读取用例。
 */
public interface ThreadUseCase {
    /**
     * 在明确工作区下创建 Thread。
     */
    ThreadSummary createThread(ThreadSummary.Creation request);

    /**
     * 在一个 Java 权威 Workspace 内使用稳定键集游标列出 Thread，避免跨项目分页污染。
     */
    CursorPage<ThreadSummary> listThreads(String workspaceId, String cursor, int limit);

    /** 在一个 Workspace 内按归一化标题包含关系搜索，并保持最近更新时间 keyset 顺序。 */
    CursorPage<ThreadSummary> searchThreads(String workspaceId, String query, String cursor, int limit);

    /**
     * 读取一个事务一致的 Thread 历史页面。
     */
    Optional<ThreadSnapshot> readThread(String threadId, String cursor, int limit);

    /** 用户显式标题通过 revision CAS 写入，并永久取得 manual 所有权。 */
    ThreadSummary renameThread(String threadId, String title, long expectedThreadRevision);

    /** 下一轮偏好通过 revision CAS 更新；已经接纳的 Turn 快照不会随之改变。 */
    ThreadSummary updatePreferences(String threadId, ThreadPreferences preferences,
                                    long expectedThreadRevision);

    /**
     * 系统标题只允许从 placeholder 状态赢一次；expectedThreadRevision 是首次成功 Turn 的
     * revision 下界，后续 Turn 推进 revision 不取消标题资格，人工标题仍令迟到结果安静退出。
     */
    boolean writeAutomaticTitle(String threadId, String title, long expectedThreadRevision);

    /**
     * 通过 revision CAS 归档空闲 Thread。
     */
    void archiveThread(String threadId, long expectedThreadRevision);

    /**
     * 通过 revision CAS 删除空闲 Thread。
     */
    void deleteThread(String threadId, long expectedThreadRevision);

    /**
     * 按全局 Turn 身份读取取消与审批关联所需的最小投影。
     */
    Optional<TurnSummary> findTurn(String turnId);

    /** 由 Rust 前向提交一次 Turn 文件差异，Java 负责身份、hash 与唯一性门。 */
    default TurnChangeSet commitChangeSet(ChangeSetCommit request) {
        throw new UnsupportedOperationException("change set persistence is unavailable");
    }

    /** 通过严格四元身份按 Unicode code point 分页读取已脱敏 Tool 输出。 */
    default Optional<TextArtifactPage> readToolArtifact(String threadId, String turnId, String callId,
                                                        String artifactId, int offsetCharacters,
                                                        int limitCharacters) {
        throw new UnsupportedOperationException("tool artifact persistence is unavailable");
    }

    /** 通过严格三元身份按 UTF-8 byte 分页读取冻结 diff。 */
    default Optional<BinaryTextArtifactPage> readChangeSetArtifact(String threadId, String turnId, String artifactId,
                                                                   int offsetBytes, int limitBytes) {
        throw new UnsupportedOperationException("change set artifact persistence is unavailable");
    }

    /** Rust 捕获事实与可选 diff；artifactId 只能由 Java 在提交成功后分配。 */
    record ChangeSetCommit(String threadId, String turnId, String workspaceId, TurnChangeSet changeSet,
                           String sha256, Long byteLength, String unifiedDiff) { }

    /** Tool artifact 使用 code point 游标，避免切断 UTF-16 surrogate pair。 */
    record TextArtifactPage(String artifactId, int offsetCharacters, Integer nextOffsetCharacters,
                            int totalCharacters, boolean truncated, String content) { }

    /** Diff artifact 使用 UTF-8 byte 游标，offset 必须落在字符边界。 */
    record BinaryTextArtifactPage(String artifactId, int offsetBytes, Integer nextOffsetBytes,
                                  int byteLength, boolean truncated, String content) { }
}
