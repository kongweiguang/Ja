// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.document;

import io.github.kongweiguang.ja.configuration.adapter.out.security.windows.WindowsSecureSecretFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * ConfigurationStore 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
 */
public final class ConfigurationStore {
    /**
     * 表示权威文件尚未创建的稳定 CAS 版本，第一次写入也必须显式匹配。
     */
    public static final String MISSING_VERSION = "cfg_missing";
    /**
     * 表示权威文件存在但当前无法安全读取；该值只用于脱敏读取，任何写入 CAS 都必须与重新读取的
     * 真实内容版本比较，因此不会把 IO 失败误当成首次创建。
     */
    public static final String UNAVAILABLE_VERSION = "cfg_unavailable";
    private static final int MAX_READ_BYTES = 16 * 1024 * 1024;
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final SecretFileOperations SECRET_FILES = new PlatformSecretFileOperations();


    /**
     * 存储边界只提供原子静态操作，禁止实例化后误认为存在独立缓存或事务状态。
     */
    private ConfigurationStore() {
    }

    /**
     * 在有界大小内读取普通配置文件，读取前后均校验同一文件身份。
     */
    static byte[] read(Path path) throws IOException {
        return readBounded(path, false, SECRET_FILES);
    }

    /**
     * readSecret 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    public static byte[] readSecret(Path path) throws IOException {
        if (isWindows()) return WindowsSecureSecretFile.readSecret(path);
        return readBounded(path, true, SECRET_FILES);
    }

    /**
     * readSecret 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    static byte[] readSecret(Path path, SecretFileOperations operations) throws IOException {
        return readBounded(path, true, Objects.requireNonNull(operations, "operations"));
    }

    /**
     * 从当前权威内容计算 CAS 版本；读取失败返回不可用哨兵，不与合法缺失状态混淆，也不把 null
     * 扩散到配置端口的必填版本字段。
     */
    public static String version(Path path) {
        try {
            byte[] bytes = read(path);
            return bytes == null ? MISSING_VERSION : versionOf(bytes);
        } catch (IOException | SecurityException | UnsupportedOperationException failure) {
            return UNAVAILABLE_VERSION;
        }
    }

    /**
     * 读取 Secret 版本；失败返回不可用哨兵，使修复界面仍能获得稳定脱敏快照，而写入继续由真实
     * 文件版本的 CAS 校验失败关闭。
     */
    public static String secretVersion(Path path) {
        byte[] bytes = null;
        try {
            bytes = readSecret(path);
            return bytes == null ? MISSING_VERSION : versionOf(bytes);
        } catch (IOException | SecurityException | UnsupportedOperationException failure) {
            return UNAVAILABLE_VERSION;
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * 以 SHA-256 和无填充 Base64URL 生成不透明内容版本，不在版本中携带路径或正文。
     */
    public static String versionOf(byte[] bytes) {
        try {
            return "cfg_" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /**
     * 向 Windows 测试暴露当前未释放句柄与安全描述符计数，不携带原生指针。
     */
    static NativeResourceSnapshot nativeResourcesForTests() {
        return WindowsSecureSecretFile.nativeResourcesForTests();
    }

    /**
     * writeWindowsSecretAtomicForTests 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    static void writeWindowsSecretAtomicForTests(Path target, byte[] bytes, WindowsWriteProbe probe)
            throws IOException {
        if (!isWindows()) throw new IOException("windows_secret_store_unavailable");
        WindowsSecureSecretFile.writeWindowsSecretAtomicForTests(target, bytes,
                Objects.requireNonNull(probe, "probe"));
    }

    /**
     * verifyWindowsSecretForTests 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    static void verifyWindowsSecretForTests(Path path) throws IOException {
        WindowsSecureSecretFile.verifyWindowsSecretForTests(path);
    }

    /**
     * 仅供测试比较的原生资源计数快照，不代表资源所有权。
     */
    public record NativeResourceSnapshot(int handles, int descriptors) {
    }

    /**
     * writeAtomic 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    public static void writeAtomic(Path target, byte[] bytes, boolean secret) throws IOException {
        if (secret) {
            if (isWindows()) WindowsSecureSecretFile.writeAtomic(target, bytes);
            else writeSecretAtomic(target, bytes, SECRET_FILES);
        } else {
            writeOrdinaryAtomic(target, bytes);
        }
    }

    /**
     * Windows 原子写入的故障注入端口，生产默认无操作且不参与资源所有权。
     */

    public interface WindowsWriteProbe {
        /**
         * 为 Windows 竞态测试暴露目录句柄已固定的时点，生产实现默认无副作用。
         */
        default void afterDirectoriesPinned(Path parent) throws IOException {
        }

        /**
         * 为 Windows 故障测试暴露临时文件已创建但尚未发布的时点。
         */
        default void afterTemporaryCreated(Path temporary) throws IOException {
        }
    }

    /**
     * writeSecretAtomic 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    static void writeSecretAtomic(Path target, byte[] bytes, SecretFileOperations operations)
            throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(operations, "operations");
        Path absolute = absolute(target);
        Path parent = requireParent(absolute);
        validateExistingPath(parent, true);
        Files.createDirectories(parent);
        validateExistingPath(parent, true);
        validateLeafIfPresent(absolute, false);

        Path temporary = operations.createTemporary(parent);
        Path rollback = null;
        boolean published = false;
        try {
            validateLeafIfPresent(temporary, false);
            operations.protectAndVerify(temporary);
            FileIdentity temporaryIdentity = identity(temporary, false);
            writeAndForce(temporary, bytes);
            requireIdentity(temporary, temporaryIdentity.withSize(bytes.length), false);
            operations.verify(temporary);

            if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
                operations.verify(absolute);
                FileIdentity targetIdentity = identity(absolute, false);
                rollback = operations.createRollbackLink(parent, absolute);
                requireIdentity(absolute, targetIdentity, false);
            }

            operations.moveAtomic(temporary, absolute);
            published = true;
            operations.verify(absolute);
            validateLeafIfPresent(absolute, false);
            if (rollback != null) {
                Files.delete(rollback);
                rollback = null;
            }
        } catch (IOException failure) {
            if (published) {
                rollbackPublishedSecret(absolute, rollback, operations, failure);
            }
            throw failure;
        } finally {
            deleteChecked(temporary);
            deleteChecked(rollback);
        }
    }

    /**
     * readBounded 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    private static byte[] readBounded(Path path, boolean secret, SecretFileOperations operations)
            throws IOException {
        Path absolute = absolute(path);
        validateExistingPath(requireParent(absolute), true);
        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) return null;
        validateLeafIfPresent(absolute, false);
        FileIdentity before = identity(absolute, false);
        if (before.size() > MAX_READ_BYTES) throw new IOException("configuration_file_too_large");
        if (secret) operations.verify(absolute);

        byte[] bytes = new byte[Math.toIntExact(before.size())];
        boolean success = false;
        try (FileChannel channel = FileChannel.open(absolute, readOptions())) {
            requireIdentity(absolute, before, false);
            if (secret) operations.verify(absolute);
            ByteBuffer destination = ByteBuffer.wrap(bytes);
            while (destination.hasRemaining()) {
                int count = channel.read(destination);
                if (count < 0) throw new IOException("configuration_file_changed_during_read");
            }
            ByteBuffer overflow = ByteBuffer.allocate(1);
            if (channel.read(overflow) >= 0) throw new IOException("configuration_file_changed_during_read");
            requireIdentity(absolute, before, false);
            if (secret) operations.verify(absolute);
            success = true;
            return bytes;
        } finally {
            if (!success) Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * 完整写入已固定的临时文件并 force 到存储，发布步骤只能在持久化成功后进行。
     */
    private static void writeAndForce(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, writeOptions())) {
            ByteBuffer source = ByteBuffer.wrap(bytes);
            while (source.hasRemaining()) channel.write(source);
            channel.force(true);
        }
    }

    /**
     * writeOrdinaryAtomic 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    private static void writeOrdinaryAtomic(Path target, byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        Path absolute = absolute(target);
        Path parent = requireParent(absolute);
        validateExistingPath(parent, true);
        Files.createDirectories(parent);
        validateExistingPath(parent, true);
        validateLeafIfPresent(absolute, false);
        Path temporary = Files.createTempFile(parent, ".ja-config-", ".tmp");
        try {
            writeAndForce(temporary, bytes);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * rollbackPublishedSecret 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    private static void rollbackPublishedSecret(Path target, Path rollback,
                                                SecretFileOperations operations,
                                                IOException original) {
        try {
            if (rollback == null) {
                Files.deleteIfExists(target);
            } else {
                operations.moveAtomic(rollback, target);
                operations.verify(target);
            }
        } catch (IOException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    /**
     * 沿绝对路径检查所有已存在组件，拒绝符号链接、reparse point 和错误的叶子类型。
     */
    public static void validateExistingPath(Path path, boolean directoryRequired) throws IOException {
        Path absolute = absolute(path);
        Path root = absolute.getRoot();
        if (root == null) throw new IOException("configuration_path_invalid");
        Path cursor = root;
        for (Path component : root.relativize(absolute)) {
            cursor = cursor.resolve(component);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) continue;
            rejectLinkOrReparse(cursor);
        }
        if (directoryRequired && Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)
            && !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("configuration_parent_invalid");
        }
    }

    /**
     * 对已存在叶子执行 NOFOLLOW 类型检查，缺失叶子保留给受控创建流程。
     */
    static void validateLeafIfPresent(Path path, boolean directory) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        rejectLinkOrReparse(path);
        boolean valid = directory
                ? Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        if (!valid) throw new IOException("configuration_leaf_invalid");
    }

    /**
     * 所有平台拒绝符号链接和特殊文件；Windows 另比较 NOFOLLOW 路径识别 reparse point。
     * POSIX 的回滚硬链接仍是同一 inode，macOS real path 可返回另一个链接名，不能因名称
     * 不同拒绝安全原子替换；祖先 symlink 由 validateExistingPath 逐分量拒绝。
     */
    private static void rejectLinkOrReparse(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("configuration_reparse_forbidden");
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isOther()
            || isWindows() && !path.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(path.toRealPath())) {
            throw new IOException("configuration_reparse_forbidden");
        }
    }

    /**
     * 捕获文件对象、大小和链接数快照，Windows 使用句柄身份而非路径字符串。
     */
    private static FileIdentity identity(Path path, boolean directory) throws IOException {
        validateLeafIfPresent(path, directory);
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (isWindows()) return WindowsSecureSecretFile.identity(path);
        if (attributes.fileKey() == null) throw new IOException("configuration_identity_unavailable");
        return new FileIdentity(attributes.fileKey(), attributes.size(), 1);
    }

    /**
     * 重读身份并同时比对对象与大小，检测校验和 IO 之间的替换竞态。
     */
    private static void requireIdentity(Path path, FileIdentity expected, boolean directory)
            throws IOException {
        FileIdentity actual = identity(path, directory);
        if (!expected.sameObjectAndSize(actual)) throw new IOException("configuration_identity_changed");
    }

    /**
     * 仅清理已显式持有的临时或回滚路径，null 表示该资源从未创建。
     */
    public static void deleteChecked(Path path) throws IOException {
        if (path != null) Files.deleteIfExists(path);
    }

    /**
     * 把存储目标收敛为语法规范的绝对路径，后续还必须执行物理身份校验。
     */
    public static Path absolute(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    /**
     * 要求文件目标具有可验证的父目录，拒绝直接把根路径作为文件目标。
     */
    public static Path requireParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent == null) throw new IOException("configuration_parent_invalid");
        return parent;
    }

    /**
     * 以 NOFOLLOW_LINKS 打开只读通道，防止打开阶段跟随后置替换的链接。
     */
    private static Set<OpenOption> readOptions() {
        return Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * 只允许截断并写入已创建的临时文件，不在通道打开时创建或跟随链接。
     */
    private static Set<OpenOption> writeOptions() {
        return Set.of(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * SecretFileOperations 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    interface SecretFileOperations {
        /**
         * createTemporary 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
         */
        Path createTemporary(Path parent) throws IOException;

        /**
         * 为新凭据文件应用平台最小权限，并立即回读确认保护已生效。
         */
        void protectAndVerify(Path path) throws IOException;

        /**
         * 验证已存在凭据文件仍符合平台最小权限，不尝试自动放宽 ACL。
         */
        void verify(Path path) throws IOException;

        /**
         * createRollbackLink 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
         */
        Path createRollbackLink(Path parent, Path target) throws IOException;

        /**
         * 以平台原子替换发布已完整持久化的临时文件，不提供非原子降级。
         */
        void moveAtomic(Path source, Path target) throws IOException;
    }

    /**
     * PlatformSecretFileOperations 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    private static final class PlatformSecretFileOperations implements SecretFileOperations {
        /**
         * 在已验证的目标父目录内创建唯一临时文件，使后续替换不跨文件系统。
         */
        @Override
        public Path createTemporary(Path parent) throws IOException {
            return Files.createTempFile(parent, ".ja-auth-", ".tmp");
        }

        /**
         * Windows 应用当前用户 ACL，POSIX 应用 0600；不支持精确权限时拒绝保存 secret。
         */
        @Override
        public void protectAndVerify(Path path) throws IOException {
            if (isWindows()) {
                WindowsSecureSecretFile.protect(path);
            } else {
                try {
                    Files.setPosixFilePermissions(path, OWNER_ONLY);
                } catch (UnsupportedOperationException failure) {
                    throw new IOException("secret_posix_permissions_unavailable", failure);
                }
                verifyPosix(path);
            }
        }

        /**
         * 按当前平台验证凭据文件的 ACL 或 POSIX 权限仍然精确。
         */
        @Override
        public void verify(Path path) throws IOException {
            if (isWindows()) WindowsSecureSecretFile.verify(path);
            else verifyPosix(path);
        }

        /**
         * 为旧凭据创建同目录硬链接，使原子替换后仍能恢复同一文件对象和权限。
         */
        @Override
        public Path createRollbackLink(Path parent, Path target) throws IOException {
            Path rollback = Files.createTempFile(parent, ".ja-auth-rollback-", ".tmp");
            Files.delete(rollback);
            return Files.createLink(rollback, target);
        }

        /**
         * moveAtomic 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
         */
        @Override
        public void moveAtomic(Path source, Path target) throws IOException {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 要求 POSIX 凭据文件权限精确为 owner read/write，文件系统不支持权限时失败关闭。
     */
    private static void verifyPosix(Path path) throws IOException {
        final Set<PosixFilePermission> actual;
        try {
            actual = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException failure) {
            throw new IOException("secret_posix_permissions_unavailable", failure);
        }
        if (!actual.equals(OWNER_ONLY)) throw new IOException("secret_posix_permissions_invalid");
    }

    /**
     * 仅用于选择 Windows ACL/FFM 与 POSIX 权限实现，不用作任何业务分支。
     */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 跨平台文件快照，用对象键、大小和链接数检测受保护 IO 期间的替换。
     */
    public record FileIdentity(Object fileKey, long size, int links) {
        /**
         * 在预期写入后保留同一文件键与链接数，仅更新应有大小。
         */
        public FileIdentity withSize(long nextSize) {
            return new FileIdentity(fileKey, nextSize, links);
        }

        /**
         * 在创建回滚硬链接后保留文件键与大小，仅更新应有链接数。
         */
        public FileIdentity withLinks(int nextLinks) {
            return new FileIdentity(fileKey, size, nextLinks);
        }

        /**
         * 只比较底层文件对象，用于大小或链接数预期会变化的中间阶段。
         */
        public boolean sameObject(FileIdentity other) {
            return fileKey.equals(other.fileKey);
        }

        /**
         * 同时比较文件对象和大小，用于检测读写期间的替换或长度变化。
         */
        public boolean sameObjectAndSize(FileIdentity other) {
            return fileKey.equals(other.fileKey) && size == other.size;
        }

        /**
         * 比较完整身份快照，适用于不允许对象、内容长度或硬链接变化的阶段。
         */
        public boolean sameObjectSizeAndLinks(FileIdentity other) {
            return sameObjectAndSize(other) && links == other.links;
        }
    }

}
