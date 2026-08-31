// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.attachment.adapter.out.storage;

import io.github.kongweiguang.ja.attachment.domain.AttachmentMetadata;
import io.github.kongweiguang.ja.attachment.port.out.AttachmentBlobStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 从 Rust-owned staging 导入 Java-owned 内容寻址存储，并在两个边界都执行独立完整性校验。
 */
public final class ManagedAttachmentStore implements AttachmentBlobStore {
    private static final long MAX_FILE_BYTES = 100L * 1024 * 1024;
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final Duration ORPHAN_GRACE = Duration.ofHours(1);
    private final Path ingressRoot;
    private final Path blobRoot;
    private final Object mutationMonitor = new Object();

    /**
     * 两个根都必须来自 Host 发布的绝对目录；构造时建立固定子目录并拒绝 link/reparse 替代。
     */
    public ManagedAttachmentStore(Path runDirectory, Path dataDirectory) {
        Path run = requirePlainDirectory(Objects.requireNonNull(runDirectory, "runDirectory"));
        Path data = requirePlainDirectory(Objects.requireNonNull(dataDirectory, "dataDirectory"));
        ingressRoot = preparePlainDirectory(run.resolve("attachment-ingress"));
        blobRoot = preparePlainDirectory(data.resolve("attachments").resolve("blobs"));
    }

    /**
     * 只由 token 派生 staging，复制期间同时计算摘要；预期 size/hash 任一不匹配都不发布 blob。
     */
    @Override
    public ImportedBlob importStaged(String ingressToken, long expectedSize, String expectedSha256,
                                     String displayName) {
        if (ingressToken == null || !ingressToken.matches("[0-9a-f]{32}")
            || expectedSize < 0 || expectedSize > MAX_FILE_BYTES
            || expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")
            || displayName == null || displayName.isBlank() || displayName.length() > 512) {
            throw failure(AttachmentBlobStore.Code.INVALID_REQUEST);
        }
        synchronized (mutationMonitor) {
            return importLocked(ingressToken, expectedSize, expectedSha256);
        }
    }

    /**
     * 单进程 mutation 锁让原子 no-replace 发布、去重校验与 GC 互斥，防止数据库删除 blob
     * 时并发导入复用一个即将消失的内容文件。
     */
    private ImportedBlob importLocked(String ingressToken, long expectedSize, String expectedSha256) {
        requirePlainDirectory(ingressRoot);
        requirePlainDirectory(blobRoot);
        Path source = ingressRoot.resolve(ingressToken);
        Path temporary = blobRoot.resolve("." + expectedSha256 + "." + UUID.randomUUID() + ".part");
        BasicFileAttributes before = attributes(source, AttachmentBlobStore.Code.SOURCE_UNAVAILABLE);
        if (!before.isRegularFile() || before.isSymbolicLink() || before.size() != expectedSize) {
            throw failure(AttachmentBlobStore.Code.SOURCE_UNAVAILABLE);
        }
        MessageDigest digest = sha256();
        byte[] sample = new byte[Math.toIntExact(Math.min(expectedSize, 8_192))];
        int sampled = 0;
        long copied = 0;
        boolean published = false;
        try (FileChannel input = FileChannel.open(source, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_BYTES);
            while (input.read(buffer) >= 0) {
                if (buffer.position() == 0) break;
                buffer.flip();
                int count = buffer.remaining();
                copied = Math.addExact(copied, count);
                if (copied > expectedSize || copied > MAX_FILE_BYTES) {
                    throw failure(AttachmentBlobStore.Code.TOO_LARGE);
                }
                ByteBuffer hashView = buffer.asReadOnlyBuffer();
                digest.update(hashView);
                if (sampled < sample.length) {
                    int take = Math.min(sample.length - sampled, buffer.remaining());
                    ByteBuffer sampleView = buffer.asReadOnlyBuffer();
                    sampleView.get(sample, sampled, take);
                    sampled += take;
                }
                while (buffer.hasRemaining()) output.write(buffer);
                buffer.clear();
            }
            output.force(true);
        } catch (AttachmentBlobStore.Failure failure) {
            deleteQuietly(temporary);
            throw failure;
        } catch (IOException | ArithmeticException failure) {
            deleteQuietly(temporary);
            throw failure(AttachmentBlobStore.Code.IO, failure);
        }
        BasicFileAttributes after = attributes(source, AttachmentBlobStore.Code.SOURCE_CHANGED);
        String actualSha256 = HexFormat.of().formatHex(digest.digest());
        if (copied != expectedSize || !expectedSha256.equals(actualSha256)
            || !sameFile(before, after)) {
            deleteQuietly(temporary);
            throw failure(AttachmentBlobStore.Code.SOURCE_CHANGED);
        }
        Media media = classify(temporary, sample, sampled);
        Path blob = blobRoot.resolve(actualSha256);
        try {
            if (Files.exists(blob, LinkOption.NOFOLLOW_LINKS)) {
                verifyExistingBlob(blob, expectedSize, expectedSha256);
                deleteQuietly(temporary);
            } else {
                moveNoReplace(temporary, blob);
                published = true;
            }
        } catch (IOException failure) {
            deleteQuietly(temporary);
            throw failure(AttachmentBlobStore.Code.IO, failure);
        }
        if (!published) verifyExistingBlob(blob, expectedSize, expectedSha256);
        return new ImportedBlob(expectedSize, expectedSha256, media.kind(), media.type());
    }

    /**
     * 数据库先完成 Thread 可见性授权，本层仍复核 hash 命名和普通文件，再执行有界 positional read。
     */
    @Override
    public byte[] readRange(String sha256, long offsetBytes, int maxBytes) {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}") || offsetBytes < 0
            || maxBytes < 1 || maxBytes > 64 * 1024) {
            throw failure(AttachmentBlobStore.Code.INVALID_REQUEST);
        }
        Path blob = blobRoot.resolve(sha256);
        BasicFileAttributes metadata = attributes(blob, AttachmentBlobStore.Code.BLOB_CORRUPT);
        if (!metadata.isRegularFile() || metadata.isSymbolicLink()) {
            throw failure(AttachmentBlobStore.Code.BLOB_CORRUPT);
        }
        int length = Math.toIntExact(Math.min((long) maxBytes,
                Math.max(0L, metadata.size() - Math.min(offsetBytes, metadata.size()))));
        ByteBuffer output = ByteBuffer.allocate(length);
        try (FileChannel input = FileChannel.open(blob, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            long position = offsetBytes;
            while (output.hasRemaining()) {
                int count = input.read(output, position);
                if (count < 0) break;
                position += count;
            }
        } catch (IOException failure) {
            throw failure(AttachmentBlobStore.Code.IO, failure);
        }
        return java.util.Arrays.copyOf(output.array(), output.position());
    }

    /** 数据库二次确认无活动引用后，文件删除可幂等重试。 */
    @Override
    public void deleteBlob(String sha256) {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw failure(AttachmentBlobStore.Code.INVALID_REQUEST);
        }
        synchronized (mutationMonitor) {
            deleteBlobLocked(sha256);
        }
    }

    /** mutation monitor 已由调用方持有；此处只执行可幂等删除和稳定错误映射。 */
    private void deleteBlobLocked(String sha256) {
        try {
            Files.deleteIfExists(blobRoot.resolve(sha256));
        } catch (IOException failure) {
            throw failure(AttachmentBlobStore.Code.IO, failure);
        }
    }

    /**
     * 仅处理名称为完整 SHA-256 且超过保护期的普通文件，未知节点和新文件保持不动供后续诊断。
     */
    @Override
    public void deleteOrphans(Set<String> referencedSha256) {
        Objects.requireNonNull(referencedSha256, "referencedSha256");
        synchronized (mutationMonitor) {
            Instant cutoff = Instant.now().minus(ORPHAN_GRACE);
            try (var entries = Files.list(blobRoot)) {
                entries.filter(path -> fileName(path).matches("[0-9a-f]{64}"))
                        .filter(path -> !referencedSha256.contains(fileName(path)))
                        .filter(path -> {
                            BasicFileAttributes attributes = attributes(path, AttachmentBlobStore.Code.IO);
                            return attributes.isRegularFile() && !attributes.isSymbolicLink()
                                   && attributes.lastModifiedTime().toInstant().isBefore(cutoff);
                        }).forEach(ManagedAttachmentStore::deleteQuietly);
            } catch (IOException failure) {
                throw failure(AttachmentBlobStore.Code.IO, failure);
            }
        }
    }

    /** Files.list 的直接子项必须有文件名；显式校验能把异常节点收敛为稳定存储错误。 */
    private static String fileName(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) throw failure(AttachmentBlobStore.Code.IO);
        return fileName.toString();
    }

    /** 既有 blob 不能只凭名称信任；完整重算保证损坏不会被去重路径放大。 */
    private static void verifyExistingBlob(Path blob, long expectedSize, String expectedSha256) {
        BasicFileAttributes metadata = attributes(blob, AttachmentBlobStore.Code.BLOB_CORRUPT);
        if (!metadata.isRegularFile() || metadata.isSymbolicLink() || metadata.size() != expectedSize) {
            throw failure(AttachmentBlobStore.Code.BLOB_CORRUPT);
        }
        MessageDigest digest = sha256();
        try (var input = Files.newInputStream(blob, StandardOpenOption.READ)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
        } catch (IOException failure) {
            throw failure(AttachmentBlobStore.Code.BLOB_CORRUPT, failure);
        }
        if (!expectedSha256.equals(HexFormat.of().formatHex(digest.digest()))) {
            throw failure(AttachmentBlobStore.Code.BLOB_CORRUPT);
        }
    }

    /** magic bytes 优先，文本必须通过全文件严格 UTF-8 校验；扩展名不参与能力判断。 */
    private static Media classify(Path content, byte[] sample, int sampleLength) {
        Media signature = mediaSignature(sample, sampleLength);
        if (signature != null) return signature;
        if (isUtf8Text(content)) return new Media(AttachmentMetadata.MediaKind.TEXT, "text/plain");
        return new Media(AttachmentMetadata.MediaKind.BINARY, "application/octet-stream");
    }

    /** 仅识别实现明确支持的文件签名，防止重命名二进制被伪装为图片或 PDF。 */
    private static Media mediaSignature(byte[] sample, int length) {
        if (startsWith(sample, length, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            return new Media(AttachmentMetadata.MediaKind.PDF, "application/pdf");
        }
        if (startsWith(sample, length,
                new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) {
            return new Media(AttachmentMetadata.MediaKind.IMAGE, "image/png");
        }
        if (length >= 3 && sample[0] == (byte) 0xff
            && sample[1] == (byte) 0xd8 && sample[2] == (byte) 0xff) {
            return new Media(AttachmentMetadata.MediaKind.IMAGE, "image/jpeg");
        }
        if (startsWith(sample, length, "GIF87a".getBytes(StandardCharsets.US_ASCII))
            || startsWith(sample, length, "GIF89a".getBytes(StandardCharsets.US_ASCII))) {
            return new Media(AttachmentMetadata.MediaKind.IMAGE, "image/gif");
        }
        if (length >= 12 && startsWith(sample, length, "RIFF".getBytes(StandardCharsets.US_ASCII))
            && matchesAt(sample, 8, "WEBP".getBytes(StandardCharsets.US_ASCII))) {
            return new Media(AttachmentMetadata.MediaKind.IMAGE, "image/webp");
        }
        if (startsWith(sample, length, "BM".getBytes(StandardCharsets.US_ASCII))) {
            return new Media(AttachmentMetadata.MediaKind.IMAGE, "image/bmp");
        }
        return null;
    }

    /** decoder 跨块保留未完成序列，任何 NUL、畸形或 EOF 半字符都降级为 binary。 */
    private static boolean isUtf8Text(Path content) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer input = ByteBuffer.allocate(BUFFER_BYTES + 4);
        CharBuffer output = CharBuffer.allocate(BUFFER_BYTES);
        try (FileChannel channel = FileChannel.open(content, StandardOpenOption.READ)) {
            boolean endOfInput = false;
            while (!endOfInput) {
                int read = channel.read(input);
                endOfInput = read < 0;
                input.flip();
                if (containsNul(input.asReadOnlyBuffer())) return false;
                CoderResult result;
                do {
                    result = decoder.decode(input, output, endOfInput);
                    if (result.isError()) return false;
                    output.clear();
                } while (result.isOverflow());
                input.compact();
            }
            CoderResult flush = decoder.flush(output);
            return !flush.isError() && input.position() == 0;
        } catch (IOException failure) {
            throw failure(AttachmentBlobStore.Code.IO, failure);
        }
    }

    /** 检查当前 decoder 输入窗口中的 NUL，不移动原始 position。 */
    private static boolean containsNul(ByteBuffer bytes) {
        while (bytes.hasRemaining()) if (bytes.get() == 0) return true;
        return false;
    }

    /** 固定前缀比较避免把任意附件字节先解释为字符串。 */
    private static boolean startsWith(byte[] bytes, int length, byte[] expected) {
        return length >= expected.length && matchesAt(bytes, 0, expected);
    }

    /** 只在小型 magic 窗口内执行显式边界比较。 */
    private static boolean matchesAt(byte[] bytes, int offset, byte[] expected) {
        if (offset < 0 || offset + expected.length > bytes.length) return false;
        for (int index = 0; index < expected.length; index++) {
            if (bytes[offset + index] != expected[index]) return false;
        }
        return true;
    }

    /** 发布只允许同卷原子 no-replace；竞争者已发布时由调用方重新验证既有 blob。 */
    private static void moveNoReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("atomic move is unavailable", unsupported);
        }
    }

    /**
     * size 与 mtime 发现改写；平台发布 fileKey 时再验证 identity。Windows NIO 可能返回 null，
     * 此时已打开的 NOFOLLOW handle、完整摘要和 Rust 预期摘要共同证明实际导入内容。
     */
    private static boolean sameFile(BasicFileAttributes left, BasicFileAttributes right) {
        return left.size() == right.size() && left.lastModifiedTime().equals(right.lastModifiedTime())
               && sameFileKey(left.fileKey(), right.fileKey())
               && right.isRegularFile() && !right.isSymbolicLink();
    }

    /** fileKey 缺失必须在两次读取中一致；仅一侧缺失仍按 identity 变化失败。 */
    private static boolean sameFileKey(Object left, Object right) {
        if (left == null || right == null) return left == right;
        return left.toString().equals(right.toString());
    }

    /** 读取 NOFOLLOW 元数据并统一脱敏错误。 */
    private static BasicFileAttributes attributes(Path path, AttachmentBlobStore.Code code) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException failure) {
            throw failure(code, failure);
        }
    }

    /** 创建并 canonicalize owner 目录，拒绝任何符号链接或目录替换。 */
    private static Path preparePlainDirectory(Path path) {
        try {
            Files.createDirectories(path);
            return requirePlainDirectory(path);
        } catch (IOException failure) {
            throw failure(AttachmentBlobStore.Code.SOURCE_UNAVAILABLE, failure);
        }
    }

    /** 已存在目录必须是绝对、非链接且 real path 与 normalized spelling 相同。 */
    private static Path requirePlainDirectory(Path path) {
        if (!path.isAbsolute()) throw failure(AttachmentBlobStore.Code.SOURCE_UNAVAILABLE);
        BasicFileAttributes metadata = attributes(path, AttachmentBlobStore.Code.SOURCE_UNAVAILABLE);
        if (!metadata.isDirectory() || metadata.isSymbolicLink()) {
            throw failure(AttachmentBlobStore.Code.SOURCE_UNAVAILABLE);
        }
        try {
            Path normalized = path.toAbsolutePath().normalize();
            Path real = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!normalized.equals(real)) throw failure(AttachmentBlobStore.Code.SOURCE_UNAVAILABLE);
            return real;
        } catch (IOException failure) {
            throw failure(AttachmentBlobStore.Code.SOURCE_UNAVAILABLE, failure);
        }
    }

    /** SHA-256 是运行时必备 JCA 算法，缺失代表进程配置损坏。 */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** 仅清理本 owner 创建或严格 hash 命名的文件，失败由下一轮 GC 重试。 */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort cleanup; authoritative lifecycle remains in SQLite.
        }
    }

    /** 媒体分类内部值不进入 wire codec capability 声明。 */
    private record Media(AttachmentMetadata.MediaKind kind, String type) { }

    /** 统一创建端口失败，防止具体 NIO 异常逃出 Adapter。 */
    private static AttachmentBlobStore.Failure failure(AttachmentBlobStore.Code code) {
        return new AttachmentBlobStore.Failure(code);
    }

    /** 保留本地 cause，同时让公开错误只依赖稳定端口分类。 */
    private static AttachmentBlobStore.Failure failure(AttachmentBlobStore.Code code, Throwable cause) {
        return new AttachmentBlobStore.Failure(code, cause);
    }

}
