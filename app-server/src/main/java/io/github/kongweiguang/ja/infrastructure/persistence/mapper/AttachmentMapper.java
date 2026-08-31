// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 受管附件与独立标题用量 Mapper；跨表事务由调用方的同一 PersistenceMappers owner 控制。
 */
@Mapper
public interface AttachmentMapper {
    /** 幂等登记内容寻址 blob。 */
    int insertBlob(AttachmentRecords.BlobInsert values);
    /** 按摘要读取既有 blob，导入端必须核对 size/media。 */
    AttachmentRecords.BlobRow selectBlob(@Param("sha256") String sha256);
    /** 插入一个 DRAFT 附件。 */
    int insertAttachment(AttachmentRecords.AttachmentInsert values);
    /** 按 opaque identity 读取附件。 */
    AttachmentRecords.AttachmentRow selectAttachment(@Param("attachmentId") String attachmentId);
    /** 只从指定 Thread 读取已绑定附件。 */
    AttachmentRecords.AttachmentRow selectBoundAttachment(@Param("attachmentId") String attachmentId,
                                                           @Param("threadId") String threadId);
    /** DRAFT 单向丢弃。 */
    int discardDraft(AttachmentRecords.AttachmentDiscard values);
    /** Turn admission 内把同 Workspace 未过期草稿转为 BOUND。 */
    int bindDraft(AttachmentRecords.AttachmentBind values);
    /** 绑定状态成功后插入稳定 ordinal 关系。 */
    int insertTurnAttachment(AttachmentRecords.AttachmentBind values);
    /** 批量过期 24 小时草稿。 */
    int expireDrafts(@Param("expiredAt") String expiredAt);
    /** 有界读取不再被 DRAFT/BOUND 引用的 blob。 */
    List<String> selectUnreferencedBlobs(@Param("limit") int limit);
    /** 返回全部数据库 blob identity，供文件 orphan 扫描。 */
    List<String> selectAllBlobs();
    /** 无活动引用时删除 blob row，外键把终态附件的 blobSha 置空。 */
    int deleteUnreferencedBlob(@Param("sha256") String sha256);

    /** durable at-most-once claim；thread UNIQUE 是最终竞争门。 */
    int insertTitleGeneration(AttachmentRecords.TitleGenerationInsert values);
    /** 按 generation identity 读取 outcome 供幂等比较。 */
    AttachmentRecords.TitleGenerationRow selectTitleGeneration(@Param("generationId") String generationId);
    /** outcome 只允许从 CLAIMED(null) 原子进入一次终态。 */
    int completeTitleGeneration(AttachmentRecords.TitleGenerationOutcome values);
}
