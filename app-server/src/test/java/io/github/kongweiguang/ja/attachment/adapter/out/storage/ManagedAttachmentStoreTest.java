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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    /**
     * Windows 的 8.3 路径是同一普通目录的另一种词法拼写，不能被误判为 reparse alias。
     * 该用例固定目录真实性判断应比较 NOFOLLOW 与跟随后的物理身份，而不是比较字符串拼写。
     */
    @Test
    void acceptsWindowsShortPathSpellingForPlainDirectories() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"),
                "8.3 path spelling is Windows-specific");
        Path run = Files.createDirectories(temp.resolve("short-path-run"));
        Path data = Files.createDirectories(temp.resolve("short-path-data"));
        Path ingress = Files.createDirectories(run.resolve("attachment-ingress"));
        byte[] content = "short-path-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String token = "0123456789abcdef0123456789abcdef";
        Files.write(ingress.resolve(token), content);
        Path shortRun = shortPath(run);
        Path shortData = shortPath(data);
        assumeTrue(!shortRun.toString().equalsIgnoreCase(run.toString())
                        || !shortData.toString().equalsIgnoreCase(data.toString()),
                "the filesystem did not expose an alternate 8.3 spelling");

        ManagedAttachmentStore store = new ManagedAttachmentStore(shortRun, shortData);

        AttachmentBlobStore.ImportedBlob imported = store.importStaged(
                token, content.length, sha256(content), "short-path.txt");
        assertArrayEquals(content, Files.readAllBytes(
                data.resolve("attachments/blobs").resolve(imported.sha256())));
    }

    /**
     * Junction 位于根本身或其祖先时都必须被拒绝；不能因最终目录是普通目录而绕过物理边界。
     */
    @Test
    void rejectsJunctionRootsAndAncestors() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"),
                "junctions are Windows-specific");
        Path target = Files.createDirectories(temp.resolve("junction-target"));
        Path data = Files.createDirectories(temp.resolve("junction-data"));
        Path rootJunction = temp.resolve("junction-root");
        assumeTrue(createJunction(rootJunction, target) == 0, "cannot create junction fixture");

        assertThrows(AttachmentBlobStore.Failure.class,
                () -> new ManagedAttachmentStore(rootJunction, data));

        Files.createDirectories(target.resolve("nested"));
        Path ancestorJunction = temp.resolve("junction-ancestor");
        assumeTrue(createJunction(ancestorJunction, target) == 0, "cannot create ancestor junction fixture");
        assertThrows(AttachmentBlobStore.Failure.class,
                () -> new ManagedAttachmentStore(ancestorJunction.resolve("nested"), data));
    }

    /** 符号链接根必须与 junction 使用相同的稳定失败分类，避免平台差异形成绕过。 */
    @Test
    void rejectsSymbolicLinkRoots() throws Exception {
        Path target = Files.createDirectories(temp.resolve("symbolic-target"));
        Path link = temp.resolve("symbolic-link");
        try {
            Files.createSymbolicLink(link, target);
        } catch (Exception unavailable) {
            assumeTrue(false, "当前文件系统不允许创建 symlink");
            return;
        }

        Path data = Files.createDirectories(temp.resolve("symbolic-data"));
        assertThrows(AttachmentBlobStore.Failure.class,
                () -> new ManagedAttachmentStore(link, data));
    }

    /** 通过 Windows 原生命令取得短路径；不提供替代拼写时由测试假设跳过。 */
    private static Path shortPath(Path path) throws Exception {
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c",
                "for %I in (\"" + path + "\") do @echo %~sI")
                .redirectErrorStream(true).start();
        assumeTrue(process.waitFor(5, TimeUnit.SECONDS), "8.3 path probe timed out");
        assumeTrue(process.exitValue() == 0, "8.3 path probe failed");
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .trim();
        assumeTrue(!output.isBlank(), "8.3 path probe returned no path");
        return Path.of(output);
    }

    /** 用 Windows 原生 junction 构造 reparse ancestor，避免依赖开发者 symlink 权限。 */
    private static int createJunction(Path junction, Path target) throws Exception {
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                junction.toString(), target.toString()).redirectErrorStream(true).start();
        assumeTrue(process.waitFor(5, TimeUnit.SECONDS), "junction fixture timed out");
        return process.exitValue();
    }

    /** 测试摘要与生产 JCA 算法一致，避免 fixture 自带预期摘要漂移。 */
    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
