// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

/**
 * 附件与自动标题用量的 MyBatis 行和参数，只描述 SQL 边界，不承载领域行为。
 */
public final class AttachmentRecords {
    /**
     * 类型容器不允许实例化，避免 Solon 误识别为组件。
     */
    private AttachmentRecords() {
    }

    /**
     * 内容寻址 blob 的不可变数据库事实。
     *
     * @param sha256 内容的 SHA-256 摘要
     * @param sizeBytes 内容大小，单位为字节
     * @param mediaKind 媒体分类
     * @param mediaType 内容的 MIME 类型
     * @param createdAt 创建时间
     */
    public record BlobRow(String sha256,
                          long sizeBytes,
                          String mediaKind,
                          String mediaType,
                          String createdAt) {
    }

    /**
     * 附件生命周期投影；消息 owner 与独立 Thread owner 分别填充，不能互相伪造。
     *
     * @param attachmentId 附件标识
     * @param workspaceId 工作区稳定标识
     * @param blobSha256 附件二进制内容的 SHA-256 摘要
     * @param contentSha256 内容的 SHA-256 摘要
     * @param displayName 面向用户的显示名称
     * @param sizeBytes 内容大小，单位为字节
     * @param mediaKind 媒体分类
     * @param mediaType 内容的 MIME 类型
     * @param status 当前状态分类
     * @param createdAt 创建时间
     * @param expiresAt 过期时间
     * @param boundMessageId 绑定消息标识
     * @param boundThreadId fork 或 ToolResult owner 的 Thread 标识
     */
    public record AttachmentRow(String attachmentId,
                                String workspaceId,
                                String blobSha256,
                                String contentSha256,
                                String displayName,
                                long sizeBytes,
                                String mediaKind,
                                String mediaType,
                                String status,
                                String createdAt,
                                String expiresAt,
                                String boundMessageId,
                                String boundThreadId) {
    }

    /**
     * 导入 blob 的幂等插入参数。
     *
     * @param sha256 内容的 SHA-256 摘要
     * @param sizeBytes 内容大小，单位为字节
     * @param mediaKind 媒体分类
     * @param mediaType 内容的 MIME 类型
     * @param createdAt 创建时间
     */
    public record BlobInsert(String sha256,
                             long sizeBytes,
                             String mediaKind,
                             String mediaType,
                             String createdAt) {
    }

    /**
     * 草稿附件插入参数。
     *
     * @param attachmentId 附件标识
     * @param workspaceId 工作区稳定标识
     * @param sha256 内容的 SHA-256 摘要
     * @param displayName 面向用户的显示名称
     * @param sizeBytes 内容大小，单位为字节
     * @param mediaKind 媒体分类
     * @param mediaType 内容的 MIME 类型
     * @param createdAt 创建时间
     * @param expiresAt 过期时间
     */
    public record AttachmentInsert(String attachmentId,
                                   String workspaceId,
                                   String sha256,
                                   String displayName,
                                   long sizeBytes,
                                   String mediaKind,
                                   String mediaType,
                                   String createdAt,
                                   String expiresAt) {
    }

    /**
     * Child Thread 的新附件身份复用已授权 BOUND 源附件的不可变 blob 元数据。
     *
     * @param attachmentId child 可见的新附件标识
     * @param workspaceId 所属工作区稳定标识
     * @param blobSha256 内容寻址 blob 摘要
     * @param contentSha256 内容摘要
     * @param displayName 显示名称
     * @param sizeBytes 内容大小
     * @param mediaKind 媒体分类
     * @param mediaType 媒体类型
     * @param createdAt 创建时间
     * @param expiresAt 继承的草稿过期时间
     * @param boundAt child 绑定时间
     */
    public record ForkAttachmentInsert(String attachmentId,
                                       String workspaceId,
                                       String blobSha256,
                                       String contentSha256,
                                       String displayName,
                                       long sizeBytes,
                                       String mediaKind,
                                       String mediaType,
                                       String createdAt,
                                       String expiresAt,
                                       String boundAt) {
    }

    /**
     * DRAFT→DISCARDED 状态门。
     *
     * @param attachmentId 附件标识
     * @param discardedAt 丢弃时间
     */
    public record AttachmentDiscard(String attachmentId,
                                    String discardedAt) {
    }

    /**
     * USER Message 创建事务内的 DRAFT→BOUND 状态门。
     *
     * @param attachmentId 附件标识
     * @param workspaceId 工作区稳定标识
     * @param messageId 消息标识
     * @param ordinal 当前项的顺序位置
     * @param boundAt 绑定时间
     */
    public record AttachmentBind(String attachmentId,
                                 String workspaceId,
                                 String messageId,
                                 int ordinal,
                                 String boundAt) {
    }

    /**
     * ToolResult 成功回执内的 DRAFT→BOUND CAS 与独立 owner 关系参数。
     *
     * @param attachmentId 图片附件标识
     * @param threadId 执行 Thread
     * @param turnId 当前 Turn
     * @param callId 成功工具调用
     * @param ordinal 结果中的媒体顺序
     * @param boundAt 同一回执事务时间
     */
    public record ToolResultAttachmentBind(String attachmentId,
                                           String threadId,
                                           String turnId,
                                           String callId,
                                           int ordinal,
                                           String boundAt) {
    }

    /**
     * 队列写事务内的 DRAFT 独占预留关系。
     *
     * @param inputId 输入标识
     * @param attachmentId 附件标识
     * @param ordinal 当前项的顺序位置
     * @param createdAt 创建时间
     */
    public record AttachmentReservation(String inputId,
                                        String attachmentId,
                                        int ordinal,
                                        String createdAt) {
    }

    /**
     * 自动标题 claim 的完整 durable identity。
     *
     * @param generationId 代际标识
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param providerId 模型供应商标识
     * @param modelId 模型标识
     * @param configGeneration 冻结的配置代际标识
     * @param claimedAt 领取时间
     */
    public record TitleGenerationInsert(String generationId,
                                        String threadId,
                                        String turnId,
                                        String providerId,
                                        String modelId,
                                        String configGeneration,
                                        String claimedAt) {
    }

    /**
     * 自动标题终态行，供幂等与矛盾结果判断。
     *
     * @param generationId 代际标识
     * @param threadId 所属对话的稳定标识
     * @param result 处理结果
     * @param inputTokens 输入 Token 数
     * @param outputTokens 输出 Token 数
     * @param totalTokens 输入与输出的 Token 总数
     * @param failureCode 失败代码
     * @param completedAt 完成时间
     */
    public record TitleGenerationRow(String generationId,
                                     String threadId,
                                     String result,
                                     Long inputTokens,
                                     Long outputTokens,
                                     Long totalTokens,
                                     String failureCode,
                                     String completedAt) {
    }

    /**
     * 自动标题 outcome CAS 参数。
     *
     * @param generationId 代际标识
     * @param result 处理结果
     * @param inputTokens 输入 Token 数
     * @param outputTokens 输出 Token 数
     * @param totalTokens 输入与输出的 Token 总数
     * @param failureCode 失败代码
     * @param completedAt 完成时间
     */
    public record TitleGenerationOutcome(String generationId,
                                         String result,
                                         Long inputTokens,
                                         Long outputTokens,
                                         Long totalTokens,
                                         String failureCode,
                                         String completedAt) {
    }
}
