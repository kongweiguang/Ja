// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

/**
 * 附件与自动标题用量的 MyBatis 行和参数，只描述 SQL 边界，不承载领域行为。
 */
public final class AttachmentRecords {
    /** 内容寻址 blob 的不可变数据库事实。 */
    public record BlobRow(String sha256, long sizeBytes, String mediaKind, String mediaType,
                          String createdAt) { }

    /** 附件生命周期投影；boundMessageId 仅在查询消息绑定时填充。 */
    public record AttachmentRow(String attachmentId, String workspaceId, String blobSha256,
                                String contentSha256, String displayName, long sizeBytes,
                                String mediaKind, String mediaType, String status,
                                String createdAt, String expiresAt, String boundMessageId) { }

    /** 导入 blob 的幂等插入参数。 */
    public record BlobInsert(String sha256, long sizeBytes, String mediaKind, String mediaType,
                             String createdAt) { }

    /** 草稿附件插入参数。 */
    public record AttachmentInsert(String attachmentId, String workspaceId, String sha256,
                                   String displayName, long sizeBytes, String mediaKind,
                                   String mediaType, String createdAt, String expiresAt) { }

    /** DRAFT→DISCARDED 状态门。 */
    public record AttachmentDiscard(String attachmentId, String discardedAt) { }

    /** USER Message 创建事务内的 DRAFT→BOUND 状态门。 */
    public record AttachmentBind(String attachmentId, String workspaceId, String messageId,
                                 int ordinal, String boundAt) { }

    /** 队列写事务内的 DRAFT 独占预留关系。 */
    public record AttachmentReservation(String inputId, String attachmentId, int ordinal,
                                        String createdAt) { }

    /** 自动标题 claim 的完整 durable identity。 */
    public record TitleGenerationInsert(String generationId, String threadId, String turnId,
                                        String providerId, String modelId, String configGeneration,
                                        String claimedAt) { }

    /** 自动标题终态行，供幂等与矛盾结果判断。 */
    public record TitleGenerationRow(String generationId, String threadId, String result,
                                     Long inputTokens, Long outputTokens, Long totalTokens,
                                     String failureCode, String completedAt) { }

    /** 自动标题 outcome CAS 参数。 */
    public record TitleGenerationOutcome(String generationId, String result, Long inputTokens,
                                         Long outputTokens, Long totalTokens, String failureCode,
                                         String completedAt) { }

    /** 类型容器不允许实例化，避免 Solon 误识别为组件。 */
    private AttachmentRecords() { }
}
