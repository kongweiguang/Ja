// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.attachment.adapter.out.storage;

import io.github.kongweiguang.ja.attachment.domain.AttachmentMetadata;
import io.github.kongweiguang.ja.attachment.port.out.AttachmentBlobStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 真实临时文件验证 staging 复核、内容寻址、媒体识别和 orphan 回收。 */
final class ManagedAttachmentStoreTest {
    @TempDir Path temp;

    /** 超过采样窗口的文本必须逐字节完整发布，不能因采样推进 ByteBuffer 丢失前缀。 */
    @Test
    void importsWholeContentAndReadsBoundedRanges() throws Exception {
        Path run = Files.createDirectories(temp.resolve("run"));
        Path data = Files.createDirectories(temp.resolve("data"));
        Path ingress = Files.createDirectories(run.resolve("attachment-ingress"));
        byte[] content = new byte[9_321];
        java.util.Arrays.fill(content, (byte) 'a');
        String token = "0123456789abcdef0123456789abcdef";
        Files.write(ingress.resolve(token), content);
        String sha256 = sha256(content);
        ManagedAttachmentStore store = new ManagedAttachmentStore(run, data);

        AttachmentBlobStore.ImportedBlob imported = store.importStaged(
                token, content.length, sha256, "notes.pdf");

        assertEquals(AttachmentMetadata.MediaKind.TEXT, imported.mediaKind());
        assertArrayEquals(content, Files.readAllBytes(data.resolve("attachments/blobs").resolve(sha256)));
        assertArrayEquals(java.util.Arrays.copyOfRange(content, 8_000, 8_128),
                store.readRange(sha256, 8_000, 128));
    }

    /** PDF/图片只由真实 magic bytes 识别，扩展名不能把任意二进制伪装成模型已理解媒体。 */
    @Test
    void classifiesByContentSignatureInsteadOfExtension() throws Exception {
        Path run = Files.createDirectories(temp.resolve("signature-run"));
        Path data = Files.createDirectories(temp.resolve("signature-data"));
        Path ingress = Files.createDirectories(run.resolve("attachment-ingress"));
        byte[] content = new byte[] {(byte) 0xff, 0x00, 0x10, 0x20};
        String token = "abcdef0123456789abcdef0123456789";
        Files.write(ingress.resolve(token), content);
        ManagedAttachmentStore store = new ManagedAttachmentStore(run, data);

        AttachmentBlobStore.ImportedBlob imported = store.importStaged(
                token, content.length, sha256(content), "renamed.png");

        assertEquals(AttachmentMetadata.MediaKind.BINARY, imported.mediaKind());
        assertEquals("application/octet-stream", imported.mediaType());
    }

    /** 严格 token 拒绝路径注入，过保护期且数据库未知的 hash 文件可由 GC 幂等清理。 */
    @Test
    void rejectsPathInjectionAndDeletesOldOrphans() throws Exception {
        Path run = Files.createDirectories(temp.resolve("gc-run"));
        Path data = Files.createDirectories(temp.resolve("gc-data"));
        ManagedAttachmentStore store = new ManagedAttachmentStore(run, data);
        AttachmentBlobStore.Failure invalid = assertThrows(AttachmentBlobStore.Failure.class,
                () -> store.importStaged("../outside", 0, "0".repeat(64), "outside"));
        assertEquals(AttachmentBlobStore.Code.INVALID_REQUEST, invalid.code());
        Path orphan = data.resolve("attachments/blobs").resolve("1".repeat(64));
        Files.write(orphan, new byte[] {1});
        Files.setLastModifiedTime(orphan, FileTime.from(Instant.now().minusSeconds(7_200)));

        store.deleteOrphans(Set.of());

        assertFalse(Files.exists(orphan));
    }

    /** 测试摘要与生产 JCA 算法一致，避免 fixture 自带预期摘要漂移。 */
    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
