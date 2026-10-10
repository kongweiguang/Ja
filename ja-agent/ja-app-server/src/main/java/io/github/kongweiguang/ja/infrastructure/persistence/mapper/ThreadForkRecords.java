// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

/**
 * Thread fork 的冻结快照、操作回执和授权关系 SQL 值。
 */
public final class ThreadForkRecords {
    /**
     * 值类型容器不持有状态。
     */
    private ThreadForkRecords() {
    }

    /**
     * 首轮可消费的来源快照；来源 Thread 删除后，目标仍只依赖该冻结副本。
     *
     * @param threadId 持有冻结 seed 的目标 Thread 标识
     * @param sourceThreadId seed 来源 Thread 标识
     * @param sourceTurnId seed 来源真实 Turn 标识
     * @param sourceItemId seed 来源可见回复条目标识
     * @param sourceThreadRevision 冻结时核验的来源 revision
     * @param seedJson 带来源身份的规范 seed 编码
     * @param createdAt seed 持久化时间
     */
    public record SeedRow(String threadId,
                          String sourceThreadId,
                          String sourceTurnId,
                          String sourceItemId,
                          long sourceThreadRevision,
                          String seedJson,
                          String createdAt) {
    }

    /**
     * 重复请求使用的不可变原始响应；保存创建时投影以避免回执随 Thread 后续状态漂移。
     *
     * @param operationKey 创建操作的幂等键
     * @param requestFingerprint 来源与 revision 的摘要
     * @param resultJson 创建时的完整响应编码
     * @param createdAt 回执持久化时间
     */
    public record OperationRow(String operationKey,
                               String requestFingerprint,
                               String resultJson,
                               String createdAt) {
    }

    /**
     * fork 建立事务内保存操作回执，和目标 Thread、seed、附件授权同事务提交。
     *
     * @param operationKey 创建操作的幂等键
     * @param requestFingerprint 来源与 revision 的摘要
     * @param resultJson 创建时的完整响应编码
     * @param createdAt 回执持久化时间
     */
    public record OperationInsert(String operationKey,
                                  String requestFingerprint,
                                  String resultJson,
                                  String createdAt) {
    }

    /**
     * 复制后由目标 Thread 独立读取的附件授权关系；来源身份仅作审计，不作为读取前提。
     *
     * @param threadId 获得独立读取权限的目标 Thread
     * @param attachmentId 目标侧新附件身份
     * @param sourceThreadId 冻结来源 Thread
     * @param sourceAttachmentId 被复用内容元数据的来源附件身份
     * @param ordinal seed 中附件引用的稳定顺序
     * @param createdAt 授权关系创建时间
     */
    public record AttachmentInsert(String threadId,
                                   String attachmentId,
                                   String sourceThreadId,
                                   String sourceAttachmentId,
                                   int ordinal,
                                   String createdAt) {
    }

    /**
     * 创建 Thread 时写入的冻结来源上下文，保证首轮初始化不再依赖来源存活。
     *
     * @param threadId 持有冻结 seed 的目标 Thread 标识
     * @param sourceThreadId seed 来源 Thread 标识
     * @param sourceTurnId seed 来源 Turn 标识
     * @param sourceItemId 冻结上下文所对应的来源答复条目标识
     * @param sourceThreadRevision 冻结时核验的来源 revision
     * @param seedJson 带来源身份的规范 seed 编码
     * @param createdAt seed 持久化时间
     * @author kongweiguang
     */
    public record SeedInsert(String threadId,
                             String sourceThreadId,
                             String sourceTurnId,
                             String sourceItemId,
                             long sourceThreadRevision,
                             String seedJson,
                             String createdAt) {
    }

    /**
     * 目标 Thread 自有的公开历史副本；展示身份、来源身份、长正文和上下文切点不依赖来源表存续。
     *
     * @param threadId 接收冻结历史的目标 Thread 标识
     * @param ordinal 目标 Thread 内连续递增的历史顺序
     * @param itemId 目标 Thread 命名空间中的公开条目标识
     * @param sourceThreadId 该历史行直接复制自的来源 Thread
     * @param sourceItemId 来源 Thread 中对应的公开条目标识
     * @param sourceTurnId 来源事实所属的 Turn 标识
     * @param turnId 目标 Thread 命名空间中的 Turn 标识
     * @param contextThroughOrdinal 对应答复所冻结的中性上下文末尾顺序号
     * @param itemJson 已重映射为目标身份的公开快照项
     * @param fullContent 分页读取所需的完整公开正文
     * @param turnJson 在分叉边界冻结并重映射后的 Turn 投影
     * @param createdAt 该副本写入目标存储的时间
     * @author kongweiguang
     */
    public record HistoryItemInsert(String threadId,
                                    long ordinal,
                                    String itemId,
                                    String sourceThreadId,
                                    String sourceItemId,
                                    String sourceTurnId,
                                    String turnId,
                                    Long contextThroughOrdinal,
                                    String itemJson,
                                    String fullContent,
                                    String turnJson,
                                    String createdAt) {
    }

    /**
     * 以 child-local keyset 读取的冻结公开历史项。
     *
     * @param ordinal 目标 Thread 内连续递增的历史顺序
     * @param itemId 目标 Thread 命名空间中的公开条目标识
     * @param sourceThreadId 该历史行直接复制自的来源 Thread
     * @param sourceItemId 来源 Thread 中对应的公开条目标识
     * @param sourceTurnId 来源事实所属的 Turn 标识
     * @param turnId 目标 Thread 命名空间中的 Turn 标识
     * @param contextThroughOrdinal 对应答复所冻结的中性上下文末尾顺序号
     * @param itemJson 目标身份下的公开快照项
     * @param fullContent 分页读取所需的完整公开正文
     * @param turnJson 在分叉边界冻结并重映射后的 Turn 投影
     * @param createdAt 该副本写入目标存储的时间
     * @author kongweiguang
     */
    public record HistoryItemRow(long ordinal,
                                 String itemId,
                                 String sourceThreadId,
                                 String sourceItemId,
                                 String sourceTurnId,
                                 String turnId,
                                 Long contextThroughOrdinal,
                                 String itemJson,
                                 String fullContent,
                                 String turnJson,
                                 String createdAt) {
    }

    /**
     * 将 provider-neutral ContextMessage 原样冻结在目标 owner 下，供嵌套 fork 精确截断。
     *
     * @param threadId 持有此上下文消息副本的目标 Thread
     * @param ordinal 消息在冻结模型前缀中的顺序号
     * @param sourceThreadId 冻结前缀对应的来源 Thread
     * @param sourceItemId 该前缀所截止的来源答复条目标识
     * @param sourceMessageId 来源消息身份，用于追溯冻结边界
     * @param messageJson 重映射附件身份后的规范消息编码
     * @author kongweiguang
     */
    public record ContextMessageInsert(String threadId,
                                       long ordinal,
                                       String sourceThreadId,
                                       String sourceItemId,
                                       String sourceMessageId,
                                       String messageJson) {
    }

    /**
     * child-owned raw history row；source identity 用于审计，不作为读取依赖。
     *
     * @param ordinal 消息在目标模型前缀中的顺序号
     * @param sourceThreadId 冻结时的来源 Thread 身份
     * @param sourceItemId 冻结时的来源答复条目标识
     * @param sourceMessageId 冻结时的来源消息身份
     * @param messageJson 目标 owner 下冻结的规范消息编码
     * @author kongweiguang
     */
    public record ContextMessageRow(long ordinal,
                                    String sourceThreadId,
                                    String sourceItemId,
                                    String sourceMessageId,
                                    String messageJson) {
    }
}
