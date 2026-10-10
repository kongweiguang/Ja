// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.attachment.application.gateway;

import io.github.kongweiguang.ja.attachment.domain.model.AttachmentMetadata;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 附件关系数据端口；文件系统 blob 的创建与删除由独立存储端口所有。
 */
public interface AttachmentRepository {
    /**
     * 在一个事务中登记去重 blob 与草稿附件。
     */
    AttachmentMetadata createDraft(Draft draft);

    /**
     * 只允许 DRAFT 单向进入 DISCARDED。
     */
    AttachmentMetadata discardDraft(String attachmentId, Instant discardedAt);

    /**
     * 按 attachmentId 与 Workspace 读取仍有效的草稿，防止跨 Workspace 预览。
     */
    Optional<AttachmentMetadata> findDraft(String attachmentId, String workspaceId);

    /**
     * 按当前 Thread 读取排队预留或消息已绑定附件，拒绝跨 Thread identity。
     */
    Optional<AttachmentMetadata> findThread(String attachmentId, String threadId);

    /**
     * ToolResult 回执投影内原子绑定图片草稿；调用必须加入当前 AgentStore 事务。
     */
    AttachmentMetadata bindToolResultAttachment(ToolResultAttachmentBind values);

    /**
     * 把到期草稿批量转为 EXPIRED，并返回本轮变化数量。
     */
    int expireDrafts(Instant now);

    /**
     * 返回没有 DRAFT/BOUND 引用的 blob identity，调用方先 CAS 删除数据库行，再清理文件。
     */
    List<String> findUnreferencedBlobs(int limit);

    /**
     * 返回数据库认识的全部 blob identity，供文件层识别 DB 失败留下的 orphan。
     */
    Set<String> findAllBlobs();

    /**
     * 仅在仍无活动引用时删除 blob 行。
     */
    boolean deleteUnreferencedBlob(String sha256);

    /**
     * 导入数据库事务所需的不可变 blob 与草稿事实。
     *
     * @param attachmentId 附件标识
     * @param workspaceId 工作区稳定标识
     * @param displayName 面向用户的显示名称
     * @param sizeBytes 内容大小，单位为字节
     * @param sha256 内容的 SHA-256 摘要
     * @param mediaKind 媒体分类
     * @param mediaType 内容的 MIME 类型
     * @param createdAt 创建时间
     * @param expiresAt 过期时间
     */
    record Draft(String attachmentId,
                 String workspaceId,
                 String displayName,
                 long sizeBytes,
                 String sha256,
                 AttachmentMetadata.MediaKind mediaKind,
                 String mediaType,
                 Instant createdAt,
                 Instant expiresAt) {
    }

    /**
     * 同一 ToolResult 成功回执中的附件 owner 与显示顺序。
     *
     * @param attachmentId 已导入的图片附件标识
     * @param threadId ToolResult 的 Thread owner
     * @param turnId ToolResult 所属 Turn
     * @param callId 成功 ToolResult 的调用标识
     * @param ordinal 结果中的媒体顺序
     * @param boundAt 与 ToolResult 回执相同的事务时间
     */
    record ToolResultAttachmentBind(String attachmentId,
                                    String threadId,
                                    String turnId,
                                    String callId,
                                    int ordinal,
                                    Instant boundAt) {
    }
}
