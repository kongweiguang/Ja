// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.attachment.application;

import io.github.kongweiguang.ja.attachment.domain.AttachmentFailure;
import io.github.kongweiguang.ja.attachment.domain.AttachmentMetadata;
import io.github.kongweiguang.ja.attachment.port.in.AttachmentPreviewUseCase;
import io.github.kongweiguang.ja.attachment.port.in.AttachmentUseCase;
import io.github.kongweiguang.ja.attachment.port.out.AttachmentBlobStore;
import io.github.kongweiguang.ja.attachment.port.out.AttachmentRepository;
import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 受管附件的唯一应用服务；导入、生命周期、Thread 可见读取和 24 小时回收共享同一状态边界。
 */
public final class AttachmentService implements AttachmentUseCase, AttachmentPreviewUseCase, DeadlineCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(AttachmentService.class);
    private static final Duration DRAFT_TTL = Duration.ofHours(24);
    private static final Duration IMPORT_MAX_AGE = Duration.ofHours(1);
    private static final Duration IMPORT_CLOCK_SKEW = Duration.ofMinutes(5);
    private static final int GC_BATCH_SIZE = 256;
    private static final int GC_MAX_BATCHES = 4;
    private static final Duration PREVIEW_IDLE_TTL = Duration.ofMinutes(5);
    private static final int PREVIEW_SESSION_LIMIT = 32;
    private static final long TEXT_PREVIEW_LIMIT = 1024L * 1024;
    private final AttachmentRepository repository;
    private final AttachmentBlobStore blobs;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final Object mutationMonitor = new Object();
    private final Object previewMonitor = new Object();
    private final Map<String, PreviewSession> previewSessions = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 生产服务在发布前完成一次恢复性 GC，随后以单守护线程按小时回收；启动恢复失败必须阻止半可用入口。
     */
    public AttachmentService(AttachmentRepository repository, AttachmentBlobStore blobs, Clock clock) {
        this(repository, blobs, clock, Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("ja-attachment-gc", 0).factory()), true);
    }

    /**
     * 可注入 Scheduler 的构造用于嵌入式 owner 复用生命周期；`startSchedule=false` 只允许 AOT
     * 元数据装配，不改变真实运行时默认行为。
     */
    public AttachmentService(AttachmentRepository repository, AttachmentBlobStore blobs, Clock clock,
                             ScheduledExecutorService scheduler, boolean startSchedule) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (startSchedule) {
            boolean started = false;
            try {
                collectGarbage();
                scheduler.scheduleWithFixedDelay(this::scheduledCollection,
                        1, 1, TimeUnit.HOURS);
                started = true;
            } finally {
                if (!started) scheduler.shutdownNow();
            }
        }
    }

    /**
     * Java 重新读取并校验 Rust staging；数据库失败允许 blob 作为有保护期 orphan 留给 GC，
     * 不能在未知事务结果后猜测删除共享内容。
     */
    @Override
    public AttachmentMetadata importDraft(ImportRequest request) {
        ensureOpen();
        Objects.requireNonNull(request, "request");
        Instant now = clock.instant();
        if (request.importedAt().isBefore(now.minus(IMPORT_MAX_AGE))
            || request.importedAt().isAfter(now.plus(IMPORT_CLOCK_SKEW))) {
            throw new AttachmentFailure(AttachmentFailure.Code.INVALID_REQUEST);
        }
        synchronized (mutationMonitor) {
            try {
                AttachmentBlobStore.ImportedBlob imported = blobs.importStaged(
                        request.ingressToken(), request.sizeBytes(), request.sha256(), request.displayName());
                String attachmentId = "att_" + UUID.randomUUID();
                AttachmentRepository.Draft draft = new AttachmentRepository.Draft(
                        attachmentId, request.workspaceId(), request.displayName(), imported.sizeBytes(),
                        imported.sha256(), imported.mediaKind(), imported.mediaType(),
                        request.importedAt(), request.importedAt().plus(DRAFT_TTL));
                return repository.createDraft(draft);
            } catch (AttachmentBlobStore.Failure storageFailure) {
                throw map(storageFailure);
            } catch (StorageException persistenceFailure) {
                throw map(persistenceFailure);
            }
        }
    }

    /** DRAFT 状态门由 SQLite 原子执行；成功后 blob 只由后续 GC 在无活动引用时删除。 */
    @Override
    public AttachmentMetadata discard(String attachmentId, Instant discardedAt) {
        ensureOpen();
        Objects.requireNonNull(discardedAt, "discardedAt");
        synchronized (mutationMonitor) {
            try {
                AttachmentMetadata discarded = repository.discardDraft(attachmentId, discardedAt);
                closePreviewsForAttachment(attachmentId);
                return discarded;
            } catch (StorageException persistenceFailure) {
                throw map(persistenceFailure);
            }
        }
    }

    /**
     * 先用 Thread join 完成授权，再读取内容寻址 blob；文本保持 UTF-8 字符边界，其它格式只返回有界 Base64。
     */
    @Override
    public ReadResult read(ReadRequest request) {
        ensureOpen();
        Objects.requireNonNull(request, "request");
        try {
            AttachmentMetadata metadata = repository.findThread(request.attachmentId(), request.threadId())
                    .orElseThrow(() -> new AttachmentFailure(AttachmentFailure.Code.NOT_FOUND));
            byte[] bytes = blobs.readRange(metadata.sha256(), request.offsetBytes(), request.maxBytes());
            boolean physicalEnd = request.offsetBytes() + bytes.length >= metadata.sizeBytes();
            if (metadata.mediaKind() == AttachmentMetadata.MediaKind.TEXT) {
                TextSegment segment = decodeText(bytes, physicalEnd);
                long next = request.offsetBytes() + segment.consumedBytes();
                return new ReadResult(metadata, request.offsetBytes(), next,
                        next >= metadata.sizeBytes(), "utf-8", segment.text());
            }
            long next = request.offsetBytes() + bytes.length;
            return new ReadResult(metadata, request.offsetBytes(), next,
                    next >= metadata.sizeBytes(), "base64", Base64.getEncoder().encodeToString(bytes));
        } catch (AttachmentBlobStore.Failure storageFailure) {
            throw map(storageFailure);
        } catch (StorageException persistenceFailure) {
            throw map(persistenceFailure);
        }
    }

    /**
     * 打开前先清理空闲 session，再从 DRAFT Workspace 或 BOUND Thread 权威关系授权；不支持媒体拒绝创建假预览。
     */
    @Override
    public PreviewDescriptor openPreview(PreviewOpenRequest request) {
        ensureOpen();
        Objects.requireNonNull(request, "request");
        synchronized (previewMonitor) {
            Instant now = clock.instant();
            purgeExpiredPreviews(now);
            AttachmentMetadata metadata = authorizePreview(
                    request.attachmentId(), request.authorization(), now);
            if (previewSessions.size() >= PREVIEW_SESSION_LIMIT) {
                throw new AttachmentFailure(AttachmentFailure.Code.CONFLICT);
            }
            PreviewKind previewKind = switch (metadata.mediaKind()) {
                case IMAGE -> PreviewKind.IMAGE;
                case TEXT -> PreviewKind.TEXT;
                case PDF, BINARY -> throw new AttachmentFailure(AttachmentFailure.Code.INVALID_REQUEST);
            };
            String previewSessionId = "apv_" + UUID.randomUUID().toString().replace("-", "");
            previewSessions.put(previewSessionId, new PreviewSession(previewSessionId,
                    request.attachmentId(), request.authorization(), now));
            return new PreviewDescriptor(previewSessionId, metadata.attachmentId(), metadata.displayName(),
                    metadata.sizeBytes(), metadata.mediaKind(), metadata.mediaType(), previewKind);
        }
    }

    /**
     * 每个分段都重新验证原始授权，使丢弃、过期或 Thread 关系变化立即失败关闭；内容只以 Base64 有界返回给 Rust。
     */
    @Override
    public PreviewReadResult readPreview(PreviewReadRequest request) {
        ensureOpen();
        Objects.requireNonNull(request, "request");
        synchronized (previewMonitor) {
            Instant now = clock.instant();
            PreviewSession session = requirePreviewSession(request.previewSessionId(), now);
            try {
                AttachmentMetadata metadata = authorizePreview(
                        session.attachmentId(), session.authorization(), now);
                PreviewReadResult result = readPreviewSegment(request, metadata);
                previewSessions.put(session.previewSessionId(), session.accessedAt(now));
                return result;
            } catch (AttachmentFailure failure) {
                previewSessions.remove(request.previewSessionId());
                throw failure;
            } catch (AttachmentBlobStore.Failure storageFailure) {
                previewSessions.remove(request.previewSessionId());
                throw map(storageFailure);
            }
        }
    }

    /** 有效格式的未知或已关闭 session 与首次关闭返回相同结果，避免暴露 session 历史。 */
    @Override
    public void closePreview(String previewSessionId) {
        ensureOpen();
        if (previewSessionId == null || !previewSessionId.matches("apv_[0-9a-f]{32}")) {
            throw new IllegalArgumentException("invalid preview session identity");
        }
        synchronized (previewMonitor) {
            previewSessions.remove(previewSessionId);
        }
    }

    /** 根据媒体类型应用各自读取上限；文本截断点也按 UTF-8 边界回退，不能输出半个码点。 */
    private PreviewReadResult readPreviewSegment(PreviewReadRequest request, AttachmentMetadata metadata) {
        long previewEnd = metadata.mediaKind() == AttachmentMetadata.MediaKind.TEXT
                ? Math.min(metadata.sizeBytes(), TEXT_PREVIEW_LIMIT) : metadata.sizeBytes();
        if (request.offsetBytes() > previewEnd) {
            throw new AttachmentFailure(AttachmentFailure.Code.INVALID_REQUEST);
        }
        boolean truncated = metadata.mediaKind() == AttachmentMetadata.MediaKind.TEXT
                && metadata.sizeBytes() > TEXT_PREVIEW_LIMIT;
        if (request.offsetBytes() == previewEnd) {
            return new PreviewReadResult(request.previewSessionId(), request.offsetBytes(),
                    request.offsetBytes(), "", true, truncated);
        }
        int readSize = Math.toIntExact(Math.min(request.limitBytes(), previewEnd - request.offsetBytes()));
        byte[] bytes = blobs.readRange(metadata.sha256(), request.offsetBytes(), readSize);
        if (bytes.length == 0 || bytes.length > readSize
            || request.offsetBytes() + bytes.length < Math.min(previewEnd, request.offsetBytes() + readSize)) {
            throw new AttachmentFailure(AttachmentFailure.Code.CONTENT_CORRUPT);
        }
        int consumed = bytes.length;
        if (metadata.mediaKind() == AttachmentMetadata.MediaKind.TEXT) {
            boolean physicalEnd = request.offsetBytes() + bytes.length >= metadata.sizeBytes();
            consumed = decodeText(bytes, physicalEnd).consumedBytes();
        }
        long next = request.offsetBytes() + consumed;
        boolean eof = request.offsetBytes() + bytes.length >= previewEnd;
        return new PreviewReadResult(request.previewSessionId(), request.offsetBytes(), next,
                Base64.getEncoder().encodeToString(Arrays.copyOf(bytes, consumed)), eof, truncated);
    }

    /** DRAFT 只允许未预留 Composer 草稿；Thread 分支每次从队列或消息关系重新授权。 */
    private AttachmentMetadata authorizePreview(String attachmentId, Authorization authorization, Instant now) {
        try {
            if (authorization instanceof DraftAuthorization draft) {
                AttachmentMetadata metadata = repository.findDraft(attachmentId, draft.workspaceId())
                        .orElseThrow(() -> new AttachmentFailure(AttachmentFailure.Code.NOT_FOUND));
                if (!metadata.expiresAt().isAfter(now)) {
                    throw new AttachmentFailure(AttachmentFailure.Code.NOT_FOUND);
                }
                return metadata;
            }
            if (authorization instanceof ThreadAuthorization thread) {
                return repository.findThread(attachmentId, thread.threadId())
                        .orElseThrow(() -> new AttachmentFailure(AttachmentFailure.Code.NOT_FOUND));
            }
            throw new AttachmentFailure(AttachmentFailure.Code.INVALID_REQUEST);
        } catch (StorageException persistenceFailure) {
            throw map(persistenceFailure);
        }
    }

    /** 空闲满五分钟即删除；边界包含恰好 TTL，避免 session 多存活一个请求周期。 */
    private void purgeExpiredPreviews(Instant now) {
        previewSessions.values().removeIf(session ->
                !session.lastAccessAt().plus(PREVIEW_IDLE_TTL).isAfter(now));
    }

    /** 读取不存在或已空闲过期的 session 都返回相同 NOT_FOUND，并在返回前完成删除。 */
    private PreviewSession requirePreviewSession(String previewSessionId, Instant now) {
        PreviewSession session = previewSessions.get(previewSessionId);
        if (session == null || !session.lastAccessAt().plus(PREVIEW_IDLE_TTL).isAfter(now)) {
            previewSessions.remove(previewSessionId);
            throw new AttachmentFailure(AttachmentFailure.Code.NOT_FOUND);
        }
        return session;
    }

    /** 草稿成功丢弃后同步撤销同附件所有预览，不等待下一次 read 才释放 session 配额。 */
    private void closePreviewsForAttachment(String attachmentId) {
        synchronized (previewMonitor) {
            previewSessions.values().removeIf(session -> session.attachmentId().equals(attachmentId));
        }
    }

    /**
     * 在服务 mutation 锁内先删除无引用数据库行、再删文件；所有导入使用同一锁，因此不会复用即将删除的 blob。
     */
    @Override
    public void collectGarbage() {
        ensureOpen();
        synchronized (mutationMonitor) {
            try {
                Instant now = clock.instant();
                repository.expireDrafts(now);
                for (int batch = 0; batch < GC_MAX_BATCHES; batch++) {
                    List<String> candidates = repository.findUnreferencedBlobs(GC_BATCH_SIZE);
                    for (String sha256 : candidates) {
                        if (repository.deleteUnreferencedBlob(sha256)) blobs.deleteBlob(sha256);
                    }
                    if (candidates.size() < GC_BATCH_SIZE) break;
                }
                blobs.deleteOrphans(repository.findAllBlobs());
            } catch (AttachmentBlobStore.Failure storageFailure) {
                throw map(storageFailure);
            } catch (StorageException persistenceFailure) {
                throw map(persistenceFailure);
            }
        }
    }

    /** 定时失败记录稳定分类并保留下一小时重试，绝不输出路径、文件名或用户内容。 */
    private void scheduledCollection() {
        if (closed.get()) return;
        try {
            collectGarbage();
        } catch (AttachmentFailure failure) {
            LOGGER.warn("Managed attachment GC failed ({})", failure.code());
        }
    }

    /**
     * 非 EOF 分段最多回退三个尾字节以保持 UTF-8 边界；中间畸形或调用方从续字节开始都视为内容损坏。
     */
    private static TextSegment decodeText(byte[] bytes, boolean endOfFile) {
        int minimum = endOfFile ? bytes.length : Math.max(0, bytes.length - 3);
        for (int length = bytes.length; length >= minimum; length--) {
            try {
                String text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, 0, length)).toString();
                if (length == 0 && bytes.length > 0) break;
                return new TextSegment(length, text);
            } catch (CharacterCodingException malformed) {
                if (endOfFile) break;
            }
        }
        throw new AttachmentFailure(AttachmentFailure.Code.CONTENT_CORRUPT);
    }

    /** Blob 端口分类映射为用户可恢复的入站错误，cause 只保留在本地链。 */
    private static AttachmentFailure map(AttachmentBlobStore.Failure failure) {
        AttachmentFailure.Code code = switch (failure.code()) {
            case INVALID_REQUEST -> AttachmentFailure.Code.INVALID_REQUEST;
            case SOURCE_UNAVAILABLE -> AttachmentFailure.Code.CONTENT_UNAVAILABLE;
            case SOURCE_CHANGED -> AttachmentFailure.Code.CONTENT_CHANGED;
            case TOO_LARGE -> AttachmentFailure.Code.TOO_LARGE;
            case BLOB_CORRUPT -> AttachmentFailure.Code.CONTENT_CORRUPT;
            case IO -> AttachmentFailure.Code.IO;
        };
        return new AttachmentFailure(code, failure);
    }

    /** SQLite 只发布足以选择恢复动作的粗粒度分类，不把 SQL 文本带入附件错误。 */
    private static AttachmentFailure map(StorageException failure) {
        AttachmentFailure.Code code = switch (failure.code()) {
            case NOT_FOUND -> AttachmentFailure.Code.NOT_FOUND;
            case CAS_CONFLICT, STORAGE_CONFLICT -> AttachmentFailure.Code.CONFLICT;
            case INVALID_STATE -> AttachmentFailure.Code.CONTENT_CORRUPT;
            default -> AttachmentFailure.Code.IO;
        };
        return new AttachmentFailure(code, failure);
    }

    /** 关闭后的任何新准入均失败，避免 Scheduler 与进程退出后继续访问数据库。 */
    private void ensureOpen() {
        if (closed.get()) throw new AttachmentFailure(AttachmentFailure.Code.IO);
    }

    /** 普通关闭使用两秒本地预算；组合根会优先调用带绝对 Deadline 的版本。 */
    @Override
    public void close() {
        long now = System.nanoTime();
        closeAt(now + Duration.ofSeconds(2).toNanos());
    }

    /** 先阻止新任务并中断 Scheduler，只消费组合根提供的剩余关闭预算。 */
    @Override
    public void closeAt(long shutdownDeadlineNanos) {
        if (closed.compareAndSet(false, true)) {
            scheduler.shutdownNow();
            synchronized (previewMonitor) {
                previewSessions.clear();
            }
        }
        long remaining = shutdownDeadlineNanos - System.nanoTime();
        if (remaining <= 0 && !scheduler.isTerminated()) {
            throw new IllegalStateException("attachment scheduler close deadline expired");
        }
        try {
            if (remaining > 0 && !scheduler.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException("attachment scheduler did not terminate");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("attachment scheduler close interrupted", interrupted);
        }
    }

    /** UTF-8 分段保存真实消费字节数，下一页 offset 不能按 Java 字符数推导。 */
    private record TextSegment(int consumedBytes, String text) { }

    /** session 只保存重新授权所需 identity 与最近访问时间，不缓存 blob identity 或内容。 */
    private record PreviewSession(String previewSessionId, String attachmentId,
                                  Authorization authorization, Instant lastAccessAt) {
        /** 成功读取后用不可变替换更新时间，失败不会延长 session 生命周期。 */
        private PreviewSession accessedAt(Instant now) {
            return new PreviewSession(previewSessionId, attachmentId, authorization, now);
        }
    }
}
