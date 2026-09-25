// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.in;

import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.ThreadUsageSummary;
import io.github.kongweiguang.ja.conversation.domain.UserInputHistory;
import io.github.kongweiguang.ja.conversation.domain.TurnSummary;
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

    /** 无项目主会话跨多个 SESSION workspace 聚合读取，仍返回完整可导航摘要。 */
    default CursorPage<ThreadSummary> listSessionThreads(String cursor, int limit) {
        throw new UnsupportedOperationException("session thread listing is unavailable");
    }

    /**
     * 读取全局 Thread 发现投影；默认失败保持窄测试夹具显式声明未提供该能力。
     */
    default CursorPage<ThreadDiscovery> discoverThreads(ThreadDiscovery.Query query) {
        throw new UnsupportedOperationException("thread discovery is unavailable");
    }

    /** 在用户主会话中按最近优先搜索已提交输入，不从终端草稿建立第二份历史。 */
    default CursorPage<UserInputHistory> searchUserInputs(String query, String cursor, int limit) {
        throw new UnsupportedOperationException("input history search is unavailable");
    }

    /** 在一个 Workspace 内按归一化标题包含关系搜索，并保持最近更新时间 keyset 顺序。 */
    CursorPage<ThreadSummary> searchThreads(String workspaceId, String query, String cursor, int limit);

    /** 在所有 SESSION workspace 内搜索主会话标题，不把 project 或 legacy recovery root 混入结果。 */
    default CursorPage<ThreadSummary> searchSessionThreads(String query, String cursor, int limit) {
        throw new UnsupportedOperationException("session thread search is unavailable");
    }

    /**
     * 读取一个事务一致的 Thread 历史页面。
     */
    Optional<ThreadSnapshot> readThread(String threadId, String cursor, int limit);

    /**
     * 从权威历史末端反向分页，页面仍按旧到新发布；nextCursor 是继续读取更旧条目的边界。
     * 最新页和普通向前分页独立，避免长会话打开时先物化全部历史。
     */
    default Optional<ThreadSnapshot> readThreadLatest(String threadId, String beforeCursor, int limit) {
        throw new UnsupportedOperationException("latest thread history is unavailable");
    }

    /**
     * 读取 Thread 的全量用量汇总而不物化历史页面；默认关闭，避免旧测试实现把缺失的
     * Provider 计量伪装成空汇总。
     */
    default Optional<ThreadUsageSummary> readThreadUsageSummary(String threadId) {
        throw new UnsupportedOperationException("thread usage summary is unavailable");
    }

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

    /** 通过 revision CAS 更新置顶事实并返回服务端重排所需的完整投影。 */
    default ThreadSummary pinThread(String threadId, boolean pinned, long expectedThreadRevision) {
        throw new UnsupportedOperationException("thread pinning is unavailable");
    }

    /**
     * 以 revision CAS 确认当前最新成功或失败 Turn 已被看到；无可确认结果时幂等返回。
     */
    default ThreadSummary markThreadSeen(String threadId, long expectedThreadRevision) {
        throw new UnsupportedOperationException("thread seen boundary is unavailable");
    }

    /**
     * 通过 revision CAS 归档空闲 Thread，并返回可供撤销保存的权威投影。
     */
    ThreadSummary archiveThread(String threadId, long expectedThreadRevision);

    /** 通过 revision CAS 恢复已归档 Thread；恢复后始终保持未置顶。 */
    default ThreadSummary restoreThread(String threadId, long expectedThreadRevision) {
        throw new UnsupportedOperationException("thread restore is unavailable");
    }

    /**
     * 通过 revision CAS 删除空闲 Thread。
     */
    void deleteThread(String threadId, long expectedThreadRevision);

    /**
     * 按全局 Turn 身份读取取消与审批关联所需的最小投影。
     */
    Optional<TurnSummary> findTurn(String turnId);

    /** 通过严格四元身份按 Unicode code point 分页读取已脱敏 Tool 输出。 */
    default Optional<TextArtifactPage> readToolArtifact(String threadId, String turnId, String callId,
                                                        String artifactId, int offsetCharacters,
                                                        int limitCharacters) {
        throw new UnsupportedOperationException("tool artifact persistence is unavailable");
    }

    /** 只读取当前路径上的 Assistant 正文或公开摘要；Provider 私有 reasoning 永不进入公开页。 */
    default Optional<MessageContentPage> readMessageContent(String threadId, String messageId,
                                                             int offsetCharacters, int limitCharacters) {
        throw new UnsupportedOperationException("message content persistence is unavailable");
    }

    /** 通过严格三元身份与 artifact 内文件键一次读取完整冻结 Diff，不回退到当前工作区。 */
    default Optional<ChangeSetArtifactFile> readChangeSetArtifact(String threadId, String turnId, String artifactId,
                                                                  String filePath) {
        throw new UnsupportedOperationException("change set artifact persistence is unavailable");
    }

    /** Tool artifact 使用 code point 游标，避免切断 UTF-16 surrogate pair。 */
    record TextArtifactPage(String artifactId, int offsetCharacters, Integer nextOffsetCharacters,
                            int totalCharacters, boolean truncated, String content) { }

    /** 最终答复和续写段共用消息身份；字符游标按 Unicode code point 单调推进。 */
    record MessageContentPage(String messageId, int offsetCharacters, Integer nextOffsetCharacters,
                              int totalCharacters, boolean truncated, String content) { }

    /** 冻结单文件以标准 Base64 携带原始 UTF-8 bytes，摘要只覆盖该文件 Diff。 */
    record ChangeSetArtifactFile(String artifactId, String filePath, int byteLength, String sha256,
                                 String contentBase64) { }
}
