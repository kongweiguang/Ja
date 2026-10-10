// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.api;

import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadUsageSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnSummary;
import io.github.kongweiguang.ja.conversation.domain.model.UserInputHistory;

import java.util.Optional;
import java.util.Objects;

/**
 * 定义 Thread 生命周期与权威历史读取用例。
 */
public interface ThreadUseCase {
    /**
     * 在明确工作区下创建 Thread。
     */
    ThreadSummary createThread(ThreadSummary.Creation request);

    /**
     * 以来源已结算回复的精确历史前缀创建普通独立 Thread，不继承来源执行状态。
     */
    default ThreadForkResult forkThread(ThreadForkRequest request) {
        throw new UnsupportedOperationException("thread fork is unavailable");
    }

    /**
     * 在来源 Thread 所有权范围内读取持久分叉回执，以便不确定响应后确认已提交结果。
     *
     * @author kongweiguang
     */
    default Optional<ThreadForkResult> readThreadForkOperation(String sourceThreadId, String operationKey) {
        throw new UnsupportedOperationException("thread fork receipt lookup is unavailable");
    }

    /**
     * Thread fork 的不可变来源身份；回执始终指向实际冻结的版本，不能随来源后续修改漂移。
     *
     * @param threadId 冻结来源 Thread 标识
     * @param itemId 冻结来源已结算答复条目标识
     * @param threadRevision 创建分叉时核验的来源 revision
     */
    record ThreadForkSource(String threadId,
                            String itemId,
                            long threadRevision) {
        /** 身份前缀及 revision 与 JA-RPC 契约保持一致；item 身份不得退化为 Turn 截断位置。 */
        public ThreadForkSource {
            threadId = forkIdentifier(threadId, "thr_", "sourceThreadId");
            itemId = forkIdentifier(itemId, "item_", "sourceItemId");
            if (threadRevision < 0) {
                throw new IllegalArgumentException("invalid source thread revision");
            }
        }
    }

    /**
     * Thread fork 创建的普通 Thread 与其冻结来源回执；执行、审批和 Plan 状态不属于该结果。
     *
     * @param thread 新建 Thread 的初始投影
     * @param source 本次操作实际使用的冻结来源身份
     */
    record ThreadForkResult(ThreadSummary thread,
                            ThreadForkSource source) {
        /** 结果必须指向不同 Thread，不能把来源快照伪装成新身份。 */
        public ThreadForkResult {
            Objects.requireNonNull(thread, "thread");
            Objects.requireNonNull(source, "source");
            if (thread.threadId().equals(source.threadId())) {
                throw new IllegalArgumentException("fork thread must be independent");
            }
        }
    }

    /**
     * Thread fork 请求绑定实际答复、来源版本和跨重试稳定的操作身份，防止重试时隐式切换上下文。
     *
     * @param sourceThreadId 来源 Thread 标识
     * @param sourceItemId 来源已结算答复条目标识
     * @param expectedThreadRevision 调用方读取来源时的 revision
     * @param operationKey 同一创建意图重试时复用的操作键
     */
    record ThreadForkRequest(String sourceThreadId,
                             String sourceItemId,
                             long expectedThreadRevision,
                             String operationKey) {
        /** operation key 使用持久回执表的固定格式，revision 不允许隐式刷新。 */
        public ThreadForkRequest {
            sourceThreadId = forkIdentifier(sourceThreadId, "thr_", "sourceThreadId");
            sourceItemId = forkIdentifier(sourceItemId, "item_", "sourceItemId");
            if (expectedThreadRevision < 0 || operationKey == null
                || !operationKey.matches("op_[0-9a-f]{32}")) {
                throw new IllegalArgumentException("invalid thread fork request");
            }
        }
    }

    /** 领域身份校验与 RpcParams 解耦，避免持久 API 依赖 transport。 */
    private static String forkIdentifier(String value, String prefix, String field) {
        if (value == null || value.length() > 128
            || !value.matches(java.util.regex.Pattern.quote(prefix) + "[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 在一个 Java 权威 Workspace 内使用稳定键集游标列出 Thread，避免跨项目分页污染。
     */
    CursorPage<ThreadSummary> listThreads(String workspaceId, String cursor, int limit);

    /**
     * 无项目主会话跨多个 SESSION workspace 聚合读取，仍返回完整可导航摘要。
     */
    CursorPage<ThreadSummary> listSessionThreads(String cursor, int limit);

    /**
     * 读取全局 Thread 发现投影；默认失败保持窄测试夹具显式声明未提供该能力。
     */
    CursorPage<ThreadDiscovery> discoverThreads(ThreadDiscovery.Query query);

    /**
     * 在用户主会话中按最近优先搜索已提交输入，不从终端草稿建立第二份历史。
     */
    CursorPage<UserInputHistory> searchUserInputs(String query, String cursor, int limit);

    /**
     * 在一个 Workspace 内按归一化标题包含关系搜索，并保持最近更新时间 keyset 顺序。
     */
    CursorPage<ThreadSummary> searchThreads(String workspaceId, String query, String cursor, int limit);

    /**
     * 在所有 SESSION workspace 内搜索主会话标题，不把 PROJECT 会话混入结果。
     */
    CursorPage<ThreadSummary> searchSessionThreads(String query, String cursor, int limit);

    /**
     * 读取一个事务一致的 Thread 历史页面。
     */
    Optional<ThreadSnapshot> readThread(String threadId, String cursor, int limit);

    /**
     * 从权威历史末端反向分页，页面仍按旧到新发布；nextCursor 是继续读取更旧条目的边界。
     * 最新页和普通向前分页独立，避免长会话打开时先物化全部历史。
     */
    Optional<ThreadSnapshot> readThreadLatest(String threadId, String beforeCursor, int limit);

    /**
     * 读取 Thread 的全量用量汇总而不物化历史页面；默认关闭，避免旧测试实现把缺失的
     * Provider 计量伪装成空汇总。
     */
    Optional<ThreadUsageSummary> readThreadUsageSummary(String threadId);

    /**
     * 用户显式标题通过 revision CAS 写入，并永久取得 manual 所有权。
     */
    ThreadSummary renameThread(String threadId, String title, long expectedThreadRevision);

    /**
     * 下一轮偏好通过 revision CAS 更新；已经接纳的 Turn 快照不会随之改变。
     */
    ThreadSummary updatePreferences(String threadId, ThreadPreferences preferences,
                                    long expectedThreadRevision);

    /**
     * 系统标题只允许从 placeholder 状态赢一次；expectedThreadRevision 是首次成功 Turn 的
     * revision 下界，后续 Turn 推进 revision 不取消标题资格，人工标题仍令迟到结果安静退出。
     */
    boolean writeAutomaticTitle(String threadId, String title, long expectedThreadRevision);

    /**
     * 通过 revision CAS 更新置顶事实并返回服务端重排所需的完整投影。
     */
    ThreadSummary pinThread(String threadId, boolean pinned, long expectedThreadRevision);

    /**
     * 以 revision CAS 确认当前最新成功或失败 Turn 已被看到；无可确认结果时幂等返回。
     */
    ThreadSummary markThreadSeen(String threadId, long expectedThreadRevision);

    /**
     * 通过 revision CAS 归档空闲 Thread，并返回可供撤销保存的权威投影。
     */
    ThreadSummary archiveThread(String threadId, long expectedThreadRevision);

    /**
     * 通过 revision CAS 恢复已归档 Thread；恢复后始终保持未置顶。
     */
    ThreadSummary restoreThread(String threadId, long expectedThreadRevision);

    /**
     * 通过 revision CAS 删除空闲 Thread。
     */
    void deleteThread(String threadId, long expectedThreadRevision);

    /**
     * 按全局 Turn 身份读取取消与审批关联所需的最小投影。
     */
    Optional<TurnSummary> findTurn(String turnId);

    /**
     * 通过严格四元身份按 Unicode code point 分页读取已脱敏 Tool 输出。
     */
    Optional<TextArtifactPage> readToolArtifact(String threadId, String turnId, String callId,
                                                String artifactId, int offsetCharacters,
                                                int limitCharacters);

    /**
     * 通过 Thread snapshot item 身份读取完整公开正文；实现负责 owner、item kind 与 current path 校验。
     */
    Optional<ItemContentPage> readItemContent(String threadId, String itemId,
                                              int offsetCharacters, int limitCharacters);

    /**
     * 通过严格三元身份与 artifact 内文件键一次读取完整冻结 Diff，不回退到当前工作区。
     */
    Optional<ChangeSetArtifactFile> readChangeSetArtifact(String threadId, String turnId, String artifactId,
                                                          String filePath);

    /**
     * Tool artifact 使用 code point 游标，避免切断 UTF-16 surrogate pair。
     *
     * @param artifactId 产物标识
     * @param offsetCharacters 读取起点的字符偏移
     * @param nextOffsetCharacters 下次读取的字符偏移
     * @param totalCharacters 文本总字符数
     * @param truncated 结果是否被截断
     * @param content 内容
     */
    record TextArtifactPage(String artifactId,
                            int offsetCharacters,
                            Integer nextOffsetCharacters,
                            int totalCharacters,
                            boolean truncated,
                            String content) {
    }

    /**
     * 正文页始终回显精确 item 身份；游标按 Unicode code point 单调推进。
     *
     * @param itemId 历史条目标识
     * @param offsetCharacters 读取起点的字符偏移
     * @param nextOffsetCharacters 下次读取的字符偏移
     * @param totalCharacters 文本总字符数
     * @param truncated 结果是否仍有后续内容
     * @param content 内容
     */
    record ItemContentPage(String itemId,
                           int offsetCharacters,
                           Integer nextOffsetCharacters,
                           int totalCharacters,
                           boolean truncated,
                           String content) {
        /**
         * 保证分页回执描述唯一连续的 Unicode code point 区间，避免游标与正文长度互相矛盾。
         */
        public ItemContentPage {
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(content, "content");
            int pageCharacters = content.codePointCount(0, content.length());
            int expectedNext = Math.addExact(offsetCharacters, pageCharacters);
            if (offsetCharacters < 0 || totalCharacters < expectedNext
                || truncated != (nextOffsetCharacters != null)
                || nextOffsetCharacters != null && (nextOffsetCharacters != expectedNext
                    || nextOffsetCharacters >= totalCharacters)
                || nextOffsetCharacters == null && expectedNext != totalCharacters) {
                throw new IllegalArgumentException("invalid item content page");
            }
        }
    }

    /**
     * 冻结单文件以标准 Base64 携带原始 UTF-8 bytes，摘要只覆盖该文件 Diff。
     *
     * @param artifactId 产物标识
     * @param filePath 文件路径
     * @param byteLength 数据长度，单位为字节
     * @param sha256 内容的 SHA-256 摘要
     * @param contentBase64 Base64 编码的内容
     */
    record ChangeSetArtifactFile(String artifactId,
                                 String filePath,
                                 int byteLength,
                                 String sha256,
                                 String contentBase64) {
    }
}
