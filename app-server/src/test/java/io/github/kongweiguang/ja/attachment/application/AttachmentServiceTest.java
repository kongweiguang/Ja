// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.attachment.application;

import io.github.kongweiguang.ja.attachment.domain.AttachmentFailure;
import io.github.kongweiguang.ja.attachment.domain.AttachmentMetadata;
import io.github.kongweiguang.ja.attachment.port.in.AttachmentUseCase;
import io.github.kongweiguang.ja.attachment.port.out.AttachmentBlobStore;
import io.github.kongweiguang.ja.attachment.port.out.AttachmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证附件应用服务的生命周期、授权与有界 codec，不把文件系统或 SQL 细节带入测试。 */
final class AttachmentServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");
    private final List<AttachmentService> services = new ArrayList<>();

    /** 每个测试都关闭自有 Scheduler，避免守护线程隐藏生命周期泄漏。 */
    @AfterEach
    void closeServices() {
        services.forEach(AttachmentService::close);
    }

    /** 导入使用 Java 权威时钟生成 24 小时草稿，丢弃只能发生在绑定之前。 */
    @Test
    void importsAndDiscardsDraftWithoutExposingStorageIdentity() {
        InMemoryAttachmentRepository repository = new InMemoryAttachmentRepository();
        InMemoryBlobStore blobs = new InMemoryBlobStore("hello".getBytes(StandardCharsets.UTF_8),
                AttachmentMetadata.MediaKind.TEXT, "text/plain");
        AttachmentService service = service(repository, blobs);

        AttachmentMetadata imported = service.importDraft(importRequest("notes.txt", blobs));
        AttachmentMetadata discarded = service.discard(imported.attachmentId(), NOW.plusSeconds(1));

        assertEquals(AttachmentMetadata.Status.DRAFT, imported.status());
        assertEquals(NOW.plusSeconds(86_400), imported.expiresAt());
        assertEquals(AttachmentMetadata.Status.DISCARDED, discarded.status());
        AttachmentFailure conflict = assertThrows(AttachmentFailure.class,
                () -> service.discard(imported.attachmentId(), NOW.plusSeconds(2)));
        assertEquals(AttachmentFailure.Code.CONFLICT, conflict.code());
    }

    /** 24 小时边界到达时草稿先过期，再仅删除不再被活动附件引用的 blob。 */
    @Test
    void expiresDraftsAndCollectsUnreferencedBlobs() {
        InMemoryAttachmentRepository repository = new InMemoryAttachmentRepository();
        InMemoryBlobStore blobs = new InMemoryBlobStore(new byte[] {1, 2, 3},
                AttachmentMetadata.MediaKind.BINARY, "application/octet-stream");
        AttachmentService service = service(repository, blobs);
        AttachmentMetadata imported = service.importDraft(importRequest("payload.bin", blobs));

        repository.gcNow = imported.expiresAt();
        service.collectGarbage();

        assertEquals(AttachmentMetadata.Status.EXPIRED,
                repository.attachments.get(imported.attachmentId()).status());
        assertTrue(blobs.deleted.contains(blobs.sha256));
        assertTrue(blobs.orphanSweepCalled);
    }

    /** 文本 range 必须在 UTF-8 字符边界回退，nextOffset 使用真实消费字节而非字符数。 */
    @Test
    void readsTextOnUtf8Boundaries() {
        byte[] content = "A😀B".getBytes(StandardCharsets.UTF_8);
        InMemoryAttachmentRepository repository = new InMemoryAttachmentRepository();
        InMemoryBlobStore blobs = new InMemoryBlobStore(content,
                AttachmentMetadata.MediaKind.TEXT, "text/plain");
        AttachmentService service = service(repository, blobs);
        AttachmentMetadata attachment = service.importDraft(importRequest("unicode.txt", blobs));
        repository.bind(attachment.attachmentId(), "thr_visible", "turn_visible");

        AttachmentUseCase.ReadResult first = service.read(
                new AttachmentUseCase.ReadRequest(attachment.attachmentId(), "thr_visible", 0, 4));
        AttachmentUseCase.ReadResult second = service.read(
                new AttachmentUseCase.ReadRequest(attachment.attachmentId(), "thr_visible", 1, 4));

        assertEquals("A", first.content());
        assertEquals(1, first.nextOffsetBytes());
        assertEquals("😀", second.content());
        assertEquals(5, second.nextOffsetBytes());
        assertFalse(second.endOfFile());
    }

    /** Thread identity 来自执行上下文；猜中 attachmentId 也不能跨 Thread 读取。 */
    @Test
    void rejectsCrossThreadReads() {
        InMemoryAttachmentRepository repository = new InMemoryAttachmentRepository();
        InMemoryBlobStore blobs = new InMemoryBlobStore("secret".getBytes(StandardCharsets.UTF_8),
                AttachmentMetadata.MediaKind.TEXT, "text/plain");
        AttachmentService service = service(repository, blobs);
        AttachmentMetadata attachment = service.importDraft(importRequest("secret.txt", blobs));
        repository.bind(attachment.attachmentId(), "thr_owner", "turn_owner");

        AttachmentFailure failure = assertThrows(AttachmentFailure.class, () -> service.read(
                new AttachmentUseCase.ReadRequest(attachment.attachmentId(), "thr_other", 0, 64)));

        assertEquals(AttachmentFailure.Code.NOT_FOUND, failure.code());
        assertEquals(0, blobs.readCount);
    }

    /** 非文本媒体只返回有界 Base64，不借 MIME 或扩展名暗示模型原生理解。 */
    @Test
    void readsBinaryAsBoundedBase64() {
        byte[] content = new byte[] {0, 1, 2, 3, 4, 5};
        InMemoryAttachmentRepository repository = new InMemoryAttachmentRepository();
        InMemoryBlobStore blobs = new InMemoryBlobStore(content,
                AttachmentMetadata.MediaKind.IMAGE, "image/png");
        AttachmentService service = service(repository, blobs);
        AttachmentMetadata attachment = service.importDraft(importRequest("image.png", blobs));
        repository.bind(attachment.attachmentId(), "thr_image", "turn_image");

        AttachmentUseCase.ReadResult result = service.read(
                new AttachmentUseCase.ReadRequest(attachment.attachmentId(), "thr_image", 2, 4));

        assertEquals("base64", result.encoding());
        assertEquals(Base64.getEncoder().encodeToString(new byte[] {2, 3, 4, 5}), result.content());
        assertTrue(result.endOfFile());
    }

    /** close 先封闭新准入，不能让调用方在 Scheduler 终止后继续访问持久化或文件。 */
    @Test
    void rejectsAdmissionAfterClose() {
        InMemoryAttachmentRepository repository = new InMemoryAttachmentRepository();
        InMemoryBlobStore blobs = new InMemoryBlobStore(new byte[] {1},
                AttachmentMetadata.MediaKind.BINARY, "application/octet-stream");
        AttachmentService service = service(repository, blobs);

        service.close();
        AttachmentFailure failure = assertThrows(AttachmentFailure.class,
                () -> service.importDraft(importRequest("closed.bin", blobs)));

        assertEquals(AttachmentFailure.Code.IO, failure.code());
    }

    /** 测试禁用定时启动，只保留与生产一致的显式服务生命周期。 */
    private AttachmentService service(InMemoryAttachmentRepository repository, InMemoryBlobStore blobs) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AttachmentService service = new AttachmentService(repository, blobs,
                Clock.fixed(NOW, ZoneOffset.UTC), scheduler, false);
        services.add(service);
        return service;
    }

    /** Wire 不提供时间；该 fixture 直接表达 Handler 使用 Java Clock 后形成的入站请求。 */
    private static AttachmentUseCase.ImportRequest importRequest(String name, InMemoryBlobStore blobs) {
        return new AttachmentUseCase.ImportRequest("0123456789abcdef0123456789abcdef",
                "ws_attachment", name, blobs.content.length, blobs.sha256, NOW);
    }

    /** 仅保存服务可观察的附件状态与绑定关系，模拟 SQLite 原子状态门。 */
    private static final class InMemoryAttachmentRepository implements AttachmentRepository {
        private final Map<String, AttachmentMetadata> attachments = new LinkedHashMap<>();
        private final Map<String, String> boundThreads = new LinkedHashMap<>();
        private Instant gcNow;

        /** 创建 DRAFT 并保留内容寻址事实。 */
        @Override
        public AttachmentMetadata createDraft(Draft draft) {
            AttachmentMetadata metadata = new AttachmentMetadata(draft.attachmentId(), draft.workspaceId(),
                    draft.displayName(), draft.sizeBytes(), draft.sha256(), draft.mediaKind(), draft.mediaType(),
                    AttachmentMetadata.Status.DRAFT, draft.createdAt(), draft.expiresAt(), null);
            attachments.put(metadata.attachmentId(), metadata);
            return metadata;
        }

        /** 只允许 DRAFT 单向丢弃，模拟数据库 CAS 冲突。 */
        @Override
        public AttachmentMetadata discardDraft(String attachmentId, Instant discardedAt) {
            AttachmentMetadata current = attachments.get(attachmentId);
            if (current == null || current.status() != AttachmentMetadata.Status.DRAFT) {
                throw new io.github.kongweiguang.ja.foundation.error.StorageException(
                        io.github.kongweiguang.ja.foundation.error.StorageException.Code.CAS_CONFLICT,
                        "attachment state changed");
            }
            AttachmentMetadata discarded = metadata(current, AttachmentMetadata.Status.DISCARDED, null);
            attachments.put(attachmentId, discarded);
            return discarded;
        }

        /** Thread join 是读取授权的唯一来源。 */
        @Override
        public Optional<AttachmentMetadata> findBound(String attachmentId, String threadId) {
            AttachmentMetadata metadata = attachments.get(attachmentId);
            return metadata != null && metadata.status() == AttachmentMetadata.Status.BOUND
                    && threadId.equals(boundThreads.get(attachmentId)) ? Optional.of(metadata) : Optional.empty();
        }

        /** 到期边界包含 expiresAt，防止恰好 24 小时的草稿多存活一个周期。 */
        @Override
        public int expireDrafts(Instant now) {
            Instant effectiveNow = gcNow == null ? now : gcNow;
            int changed = 0;
            for (Map.Entry<String, AttachmentMetadata> entry : attachments.entrySet()) {
                AttachmentMetadata current = entry.getValue();
                if (current.status() == AttachmentMetadata.Status.DRAFT
                    && !current.expiresAt().isAfter(effectiveNow)) {
                    entry.setValue(metadata(current, AttachmentMetadata.Status.EXPIRED, null));
                    changed++;
                }
            }
            return changed;
        }

        /** 终态附件不再持有 blob；测试按摘要去重返回候选。 */
        @Override
        public List<String> findUnreferencedBlobs(int limit) {
            return attachments.values().stream()
                    .filter(value -> value.status() != AttachmentMetadata.Status.DRAFT
                            && value.status() != AttachmentMetadata.Status.BOUND)
                    .map(AttachmentMetadata::sha256).distinct().limit(limit).toList();
        }

        /** 活动附件摘要构成文件 orphan 扫描的权威集合。 */
        @Override
        public Set<String> findAllBlobs() {
            return attachments.values().stream()
                    .filter(value -> value.status() == AttachmentMetadata.Status.DRAFT
                            || value.status() == AttachmentMetadata.Status.BOUND)
                    .map(AttachmentMetadata::sha256).collect(java.util.stream.Collectors.toSet());
        }

        /** 假仓储让应用服务验证删除调用顺序，不模拟外键细节。 */
        @Override
        public boolean deleteUnreferencedBlob(String sha256) {
            return attachments.values().stream().noneMatch(value -> value.sha256().equals(sha256)
                    && (value.status() == AttachmentMetadata.Status.DRAFT
                    || value.status() == AttachmentMetadata.Status.BOUND));
        }

        /** 测试绑定同时记录 Thread identity 和领域状态。 */
        private void bind(String attachmentId, String threadId, String turnId) {
            AttachmentMetadata current = attachments.get(attachmentId);
            attachments.put(attachmentId, metadata(current, AttachmentMetadata.Status.BOUND, turnId));
            boundThreads.put(attachmentId, threadId);
        }

        /** 复制不可变 metadata 时只改变生命周期字段，保持导入事实不变。 */
        private static AttachmentMetadata metadata(AttachmentMetadata source, AttachmentMetadata.Status status,
                                                   String boundTurnId) {
            return new AttachmentMetadata(source.attachmentId(), source.workspaceId(), source.displayName(),
                    source.sizeBytes(), source.sha256(), source.mediaKind(), source.mediaType(), status,
                    source.createdAt(), source.expiresAt(), boundTurnId);
        }
    }

    /** 内存 blob 只实现有界 range，并记录授权发生前是否被误读。 */
    private static final class InMemoryBlobStore implements AttachmentBlobStore {
        private final byte[] content;
        private final AttachmentMetadata.MediaKind mediaKind;
        private final String mediaType;
        private final String sha256;
        private final List<String> deleted = new ArrayList<>();
        private int readCount;
        private boolean orphanSweepCalled;

        /** 构造固定内容身份，测试不依赖文件系统时序。 */
        private InMemoryBlobStore(byte[] content, AttachmentMetadata.MediaKind mediaKind, String mediaType) {
            this.content = Arrays.copyOf(content, content.length);
            this.mediaKind = mediaKind;
            this.mediaType = mediaType;
            this.sha256 = sha256(content);
        }

        /** 返回已复核内容事实，预期值由 ImportRequest 构造保证一致。 */
        @Override
        public ImportedBlob importStaged(String ingressToken, long expectedSize, String expectedSha256,
                                         String displayName) {
            return new ImportedBlob(content.length, sha256, mediaKind, mediaType);
        }

        /** range 末端按真实内容截断，模拟生产 BlobStore 的 EOF 语义。 */
        @Override
        public byte[] readRange(String requestedSha256, long offsetBytes, int maxBytes) {
            readCount++;
            int start = Math.toIntExact(offsetBytes);
            int end = Math.min(content.length, start + maxBytes);
            return Arrays.copyOfRange(content, start, end);
        }

        /** 记录应用服务确认无引用后的删除请求。 */
        @Override
        public void deleteBlob(String requestedSha256) {
            deleted.add(requestedSha256);
        }

        /** 记录 orphan 扫描已使用数据库权威摘要集合。 */
        @Override
        public void deleteOrphans(Set<String> referencedSha256) {
            orphanSweepCalled = true;
        }

        /** 测试摘要使用与生产一致的 SHA-256，避免伪造不合法 identity。 */
        private static String sha256(byte[] value) {
            try {
                return java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(value));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }
}
