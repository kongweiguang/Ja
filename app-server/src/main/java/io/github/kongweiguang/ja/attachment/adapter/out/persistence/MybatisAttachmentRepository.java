// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.attachment.adapter.out.persistence;

import io.github.kongweiguang.ja.attachment.domain.AttachmentMetadata;
import io.github.kongweiguang.ja.attachment.port.out.AttachmentRepository;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.AttachmentRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import org.apache.ibatis.session.SqlSessionFactory;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 受管附件的 MyBatis repository；所有状态门与 blob 去重事实通过 SQLite 事务收口。
 */
public final class MybatisAttachmentRepository implements AttachmentRepository {
    private final MybatisUnitOfWork transactions;

    /** 生产构造只接收 Solon 已发布的具名 session factory。 */
    public MybatisAttachmentRepository(SqlSessionFactory sessions) {
        transactions = new MybatisUnitOfWork(sessions);
    }

    /** 聚焦测试可注入真实 SQLite transaction owner，不在生产源码建立测试分支。 */
    public MybatisAttachmentRepository(SqlSessionFactory sessions, MybatisUnitOfWork.SessionOwner owner) {
        transactions = new MybatisUnitOfWork(sessions, owner);
    }

    /** blob 冲突必须核对完整元数据，草稿插入与 blob 行共享一个事务。 */
    @Override
    public AttachmentMetadata createDraft(Draft draft) {
        Objects.requireNonNull(draft, "draft");
        return transactions.required(mapper -> {
            mapper.attachments().insertBlob(new AttachmentRecords.BlobInsert(
                    draft.sha256(), draft.sizeBytes(), draft.mediaKind().name(), draft.mediaType(),
                    draft.createdAt().toString()));
            AttachmentRecords.BlobRow blob = mapper.attachments().selectBlob(draft.sha256());
            if (blob == null || blob.sizeBytes() != draft.sizeBytes()
                || !draft.mediaKind().name().equals(blob.mediaKind())
                || !draft.mediaType().equals(blob.mediaType())) {
                throw invalidState("attachment blob metadata conflicts");
            }
            if (mapper.history().selectWorkspace(draft.workspaceId()) == null) {
                throw new StorageException(StorageException.Code.NOT_FOUND, "attachment workspace is unavailable");
            }
            int changed = mapper.attachments().insertAttachment(new AttachmentRecords.AttachmentInsert(
                    draft.attachmentId(), draft.workspaceId(), draft.sha256(), draft.displayName(),
                    draft.sizeBytes(), draft.mediaKind().name(), draft.mediaType(),
                    draft.createdAt().toString(), draft.expiresAt().toString()));
            if (changed != 1) throw conflict("attachment identity already exists");
            return metadata(mapper.attachments().selectAttachment(draft.attachmentId()));
        });
    }

    /** 丢弃只允许精确改变一个 DRAFT；重复或已绑定状态不会伪装成成功。 */
    @Override
    public AttachmentMetadata discardDraft(String attachmentId, Instant discardedAt) {
        Objects.requireNonNull(discardedAt, "discardedAt");
        return transactions.required(mapper -> {
            if (mapper.attachments().discardDraft(new AttachmentRecords.AttachmentDiscard(
                    attachmentId, discardedAt.toString())) != 1) {
                throw conflict("attachment is not a discardable draft");
            }
            return metadata(mapper.attachments().selectAttachment(attachmentId));
        });
    }

    /** Composer 草稿必须未被队列预留；Workspace 与预留关系在同一 SQL 快照内鉴权。 */
    @Override
    public Optional<AttachmentMetadata> findDraft(String attachmentId, String workspaceId) {
        Objects.requireNonNull(attachmentId, "attachmentId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        return transactions.required(mapper -> Optional.ofNullable(
                mapper.attachments().selectUnreservedDraft(attachmentId, workspaceId)).map(
                MybatisAttachmentRepository::metadata));
    }

    /** Thread 可见性由 join 权威验证，调用方不能仅凭 attachmentId 读取内容。 */
    @Override
    public Optional<AttachmentMetadata> findThread(String attachmentId, String threadId) {
        return transactions.required(mapper -> Optional.ofNullable(
                mapper.attachments().selectThreadAttachment(attachmentId, threadId)).map(
                MybatisAttachmentRepository::metadata));
    }

    /** 到期状态变化在单次 UPDATE 中完成，blob FK 同时置空使后续回收可见。 */
    @Override
    public int expireDrafts(Instant now) {
        Objects.requireNonNull(now, "now");
        return transactions.required(mapper -> mapper.attachments().expireDrafts(now.toString()));
    }

    /** 限制单轮 GC 数量，避免长事务阻塞 Turn admission。 */
    @Override
    public List<String> findUnreferencedBlobs(int limit) {
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("invalid GC limit");
        return transactions.required(mapper -> List.copyOf(mapper.attachments().selectUnreferencedBlobs(limit)));
    }

    /** 使用集合去重后发布数据库 blob identity，文件层不会看到 Mapper 或 session。 */
    @Override
    public Set<String> findAllBlobs() {
        return transactions.required(mapper -> Set.copyOf(new LinkedHashSet<>(
                mapper.attachments().selectAllBlobs())));
    }

    /** 删除行仍附加无引用谓词，文件删除与 DB 删除之间的新引用无法穿透。 */
    @Override
    public boolean deleteUnreferencedBlob(String sha256) {
        return transactions.required(mapper -> mapper.attachments().deleteUnreferencedBlob(sha256) == 1);
    }

    /** SQL 行转换为脱敏领域状态；缺失 blobSha 的终态仍使用永久 contentSha。 */
    private static AttachmentMetadata metadata(AttachmentRecords.AttachmentRow row) {
        if (row == null) throw invalidState("attachment row is unavailable");
        return new AttachmentMetadata(row.attachmentId(), row.workspaceId(), row.displayName(),
                row.sizeBytes(), row.contentSha256(), AttachmentMetadata.MediaKind.valueOf(row.mediaKind()),
                row.mediaType(), AttachmentMetadata.Status.valueOf(row.status()), Instant.parse(row.createdAt()),
                Instant.parse(row.expiresAt()), row.boundMessageId());
    }

    /** CAS 冲突不携带 attachmentId 或 SQL。 */
    private static StorageException conflict(String message) {
        return new StorageException(StorageException.Code.CAS_CONFLICT, message);
    }

    /** 损坏持久事实统一阻断读取。 */
    private static StorageException invalidState(String message) {
        return new StorageException(StorageException.Code.INVALID_STATE, message);
    }
}
