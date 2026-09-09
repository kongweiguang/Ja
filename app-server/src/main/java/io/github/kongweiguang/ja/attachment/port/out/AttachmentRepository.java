// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.attachment.port.out;

import io.github.kongweiguang.ja.attachment.domain.AttachmentMetadata;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 附件关系数据端口；文件系统 blob 的创建与删除由独立存储端口所有。
 */
public interface AttachmentRepository {
    /** 在一个事务中登记去重 blob 与草稿附件。 */
    AttachmentMetadata createDraft(Draft draft);

    /** 只允许 DRAFT 单向进入 DISCARDED。 */
    AttachmentMetadata discardDraft(String attachmentId, Instant discardedAt);

    /** 按 attachmentId 与 Workspace 读取仍有效的草稿，防止跨 Workspace 预览。 */
    Optional<AttachmentMetadata> findDraft(String attachmentId, String workspaceId);

    /** 按当前 Thread 读取排队预留或消息已绑定附件，拒绝跨 Thread identity。 */
    Optional<AttachmentMetadata> findThread(String attachmentId, String threadId);

    /** 把到期草稿批量转为 EXPIRED，并返回本轮变化数量。 */
    int expireDrafts(Instant now);

    /** 返回没有 DRAFT/BOUND 引用的 blob identity，调用方删除文件后再删行。 */
    List<String> findUnreferencedBlobs(int limit);

    /** 返回数据库认识的全部 blob identity，供文件层识别 DB 失败留下的 orphan。 */
    Set<String> findAllBlobs();

    /** 仅在仍无活动引用时删除 blob 行。 */
    boolean deleteUnreferencedBlob(String sha256);

    /** 导入数据库事务所需的不可变 blob 与草稿事实。 */
    record Draft(String attachmentId, String workspaceId, String displayName, long sizeBytes,
                 String sha256, AttachmentMetadata.MediaKind mediaKind, String mediaType,
                 Instant createdAt, Instant expiresAt) { }
}
