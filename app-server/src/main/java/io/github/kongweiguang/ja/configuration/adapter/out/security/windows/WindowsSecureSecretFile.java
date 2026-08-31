// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.security.windows;

import io.github.kongweiguang.ja.configuration.adapter.out.document.ConfigurationStore;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * WindowsSecureSecretFile 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
 */
public final class WindowsSecureSecretFile {
    private static final ConfigurationStore.WindowsWriteProbe NO_WINDOWS_WRITE_PROBE =
            new ConfigurationStore.WindowsWriteProbe() {
            };

    /** Windows Secret 文件边界只暴露原子静态操作，避免实例对象暗示可复用本地句柄。 */
    private WindowsSecureSecretFile() {
    }

    /**
     * readSecret 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    public static byte[] readSecret(Path path) throws IOException {
        return readWindowsSecret(path);
    }

    /**
     * writeAtomic 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    public static void writeAtomic(Path target, byte[] bytes) throws IOException {
        writeWindowsSecretAtomic(target, bytes, NO_WINDOWS_WRITE_PROBE);
    }

    /**
     * 返回 Win32 适配器的实时资源计数，供失败注入测试检查句柄和描述符泄漏。
     */
    public static ConfigurationStore.NativeResourceSnapshot nativeResourcesForTests() {
        return WindowsWin32Native.resources();
    }

    /**
     * writeWindowsSecretAtomicForTests 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    public static void writeWindowsSecretAtomicForTests(Path target, byte[] bytes,
                                                 ConfigurationStore.WindowsWriteProbe probe)
            throws IOException {
        writeWindowsSecretAtomic(target, bytes, Objects.requireNonNull(probe, "probe"));
    }

    /**
     * verifyWindowsSecretForTests 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    public static void verifyWindowsSecretForTests(Path path) throws IOException {
        verify(path);
    }

    /**
     * 使用短命 Win32 owner 从句柄读取文件标识，方法返回前关闭所有原生资源。
     */
    public static ConfigurationStore.FileIdentity identity(Path path) throws IOException {
        try (WindowsWin32Native nativeAcl = new WindowsWin32Native()) {
            return nativeAcl.identity(path);
        }
    }

    /**
     * 通过单一 ACL 边界收紧凭据文件权限，避免写入流程复制安全描述符逻辑。
     */
    public static void protect(Path path) throws IOException {
        WindowsAclSecurityDescriptor.protect(path);
    }

    /**
     * 回读并验证凭据文件仍只授权当前 SID，不在校验时自动修复异常 ACL。
     */
    public static void verify(Path path) throws IOException {
        WindowsAclSecurityDescriptor.verify(path);
    }

    /**
     * 固定父目录链和叶子句柄后再读取，并在失败时清零缓冲区、聚合资源关闭异常。
     */
    @SuppressWarnings({"PMD.CloseResource", "PMD.ReturnEmptyCollectionRatherThanNull"})
    private static byte[] readWindowsSecret(Path path) throws IOException {
        Path absolute = ConfigurationStore.absolute(path);
        byte[] bytes = null;
        WindowsPinnedDirectoryChain directories = null;
        WindowsSecretHandle handle = null;
        WindowsWin32Native nativeAcl = new WindowsWin32Native();
        try {
            directories = nativeAcl.pinDirectoryChain(ConfigurationStore.requireParent(absolute), false);
            if (directories == null) return null;
            handle = nativeAcl.openExisting(absolute, false);
            if (handle == null) return null;
            bytes = nativeAcl.readVerified(handle);
            handle.close();
            handle = null;
            directories.close();
            return bytes;
        } catch (IOException failure) {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
            IOException closeFailure = closeWindowsHandleForFailure(handle);
            if (closeFailure != null) failure.addSuppressed(closeFailure);
            closeFailure = closeWindowsDirectoriesForFailure(directories);
            if (closeFailure != null) failure.addSuppressed(closeFailure);
            throw failure;
        } finally {
            nativeAcl.close();
        }
    }

    /**
     * writeWindowsSecretAtomic 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    @SuppressWarnings("PMD.CloseResource")
    private static void writeWindowsSecretAtomic(Path target, byte[] bytes, ConfigurationStore.WindowsWriteProbe probe)
            throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        Path absolute = ConfigurationStore.absolute(target);
        Path parent = ConfigurationStore.requireParent(absolute);
        ConfigurationStore.validateExistingPath(parent, true);
        Files.createDirectories(parent);
        ConfigurationStore.validateExistingPath(parent, true);

        Path rollback = null;
        Path temporary = null;
        ConfigurationStore.FileIdentity rollbackExpected = null;
        WindowsPinnedDirectoryChain directories = null;
        WindowsSecretHandle rollbackHandle = null;
        WindowsSecretHandle temporaryHandle = null;
        boolean published = false;
        boolean preserveRollbackArtifact = false;
        WindowsWin32Native nativeAcl = new WindowsWin32Native();
        try {
            directories = nativeAcl.pinDirectoryChain(parent, true);
            probe.afterDirectoriesPinned(parent);
            temporaryHandle = nativeAcl.createTemporary(parent);
            temporary = temporaryHandle.path;
            probe.afterTemporaryCreated(temporary);
            nativeAcl.protectWriteAndVerify(temporaryHandle, bytes);
            ConfigurationStore.FileIdentity expected = temporaryHandle.identity();

            try (WindowsSecretHandle current = nativeAcl.openExisting(absolute, false)) {
                if (current != null) {
                    nativeAcl.verify(current, 1);
                    rollback = createRollbackPath(parent);
                    Files.createLink(rollback, absolute);
                    nativeAcl.requireIdentity(current, current.identity().withLinks(2));
                    rollbackExpected = current.identity();
                }
            }
            if (rollback != null) {
                rollbackHandle = nativeAcl.openRollback(rollback);
                nativeAcl.verify(rollbackHandle, 2);
                if (!rollbackExpected.sameObjectAndSize(rollbackHandle.identity())) {
                    throw new IOException("secret_rollback_identity_changed");
                }
            }

            if (rollbackHandle != null) nativeAcl.verify(rollbackHandle, 2);
            nativeAcl.renameAtomic(temporaryHandle, absolute, true);
            published = true;
            nativeAcl.verify(temporaryHandle, 1);
            try (WindowsSecretHandle installed = nativeAcl.openPublished(absolute)) {
                if (installed == null) throw new IOException("secret_target_missing_after_move");
                nativeAcl.verify(installed, 1);
                if (!expected.sameObjectAndSize(installed.identity())) {
                    throw new IOException("secret_target_identity_changed");
                }
            }
            ConfigurationStore.deleteChecked(rollback);
            rollback = null;
            closeWindowsHandle(rollbackHandle);
            rollbackHandle = null;
            temporaryHandle.close();
            temporaryHandle = null;
            directories.close();
            directories = null;
        } catch (IOException failure) {
            IOException cleanupFailure = closeWindowsHandleForFailure(temporaryHandle);
            temporaryHandle = null;
            if (cleanupFailure != null) failure.addSuppressed(cleanupFailure);
            if (published) {
                try {
                    rollbackPublishedWindowsSecret(absolute, rollbackHandle, nativeAcl);
                    rollback = null;
                } catch (IOException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                    preserveRollbackArtifact = true;
                }
            }
            cleanupFailure = closeWindowsHandleForFailure(rollbackHandle);
            if (cleanupFailure != null) failure.addSuppressed(cleanupFailure);
            rollbackHandle = null;
            cleanupFailure = closeWindowsDirectoriesForFailure(directories);
            if (cleanupFailure != null) failure.addSuppressed(cleanupFailure);
            directories = null;
            throw failure;
        } finally {
            IOException closeFailure = closeWindowsHandleForFailure(temporaryHandle);
            IOException secondCloseFailure = closeWindowsHandleForFailure(rollbackHandle);
            if (closeFailure == null) closeFailure = secondCloseFailure;
            else if (secondCloseFailure != null) closeFailure.addSuppressed(secondCloseFailure);
            IOException directoryCloseFailure = closeWindowsDirectoriesForFailure(directories);
            if (closeFailure == null) closeFailure = directoryCloseFailure;
            else if (directoryCloseFailure != null) closeFailure.addSuppressed(directoryCloseFailure);
            try {
                nativeAcl.close();
                if (!preserveRollbackArtifact) ConfigurationStore.deleteChecked(rollback);
                if (!published) ConfigurationStore.deleteChecked(temporary);
            } finally {
                if (closeFailure != null) throw closeFailure;
            }
        }
    }

    /**
     * 为旧凭据句柄选择未存在的同目录回滚名，有界重试避免在竞态下无限循环。
     */
    private static Path createRollbackPath(Path parent) throws IOException {
        for (int attempt = 0; attempt < 32; attempt++) {
            Path candidate = parent.resolve(".ja-auth-rollback-" + UUID.randomUUID() + ".tmp");
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return candidate;
        }
        throw new IOException("secret_rollback_name_exhausted");
    }

    /**
     * rollbackPublishedWindowsSecret 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    private static void rollbackPublishedWindowsSecret(Path target, WindowsSecretHandle rollback,
                                                       WindowsWin32Native nativeAcl) throws IOException {
        if (rollback == null) {
            Files.deleteIfExists(target);
        } else {
            nativeAcl.renameAtomic(rollback, target, true);
            nativeAcl.verify(rollback, 1);
            try (WindowsSecretHandle restored = nativeAcl.openPublished(target)) {
                if (restored == null) throw new IOException("secret_rollback_target_missing");
                nativeAcl.verify(restored, 1);
                if (!rollback.identity().sameObjectAndSize(restored.identity())) {
                    throw new IOException("secret_rollback_identity_changed");
                }
            }
        }
    }

    /**
     * closeWindowsHandle 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    private static void closeWindowsHandle(WindowsSecretHandle handle) throws IOException {
        if (handle != null) handle.close();
    }

    /**
     * closeWindowsHandleForFailure 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    private static IOException closeWindowsHandleForFailure(WindowsSecretHandle handle) {
        if (handle == null) return null;
        try {
            handle.close();
            return null;
        } catch (IOException failure) {
            return failure;
        }
    }

    /**
     * closeWindowsDirectoriesForFailure 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    private static IOException closeWindowsDirectoriesForFailure(WindowsPinnedDirectoryChain directories) {
        if (directories == null) return null;
        try {
            directories.close();
            return null;
        } catch (IOException failure) {
            return failure;
        }
    }

    /**
     * WindowsSecretHandle 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    static final class WindowsSecretHandle implements AutoCloseable {
        final WindowsWin32Native owner;
        final Path path;
        final MemorySegment handle;
        ConfigurationStore.FileIdentity identity;
        private boolean closed;

        /**
         * WindowsSecretHandle 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
         */
        WindowsSecretHandle(WindowsWin32Native owner, Path path, MemorySegment handle,
                            ConfigurationStore.FileIdentity identity) {
            this.owner = owner;
            this.path = path;
            this.handle = handle;
            this.identity = identity;
        }

        /**
         * 返回句柄当前绑定的文件身份快照，用于 IO 前后检测替换。
         */
        ConfigurationStore.FileIdentity identity() {
            return identity;
        }

        /**
         * 在已知大小或链接数变化后更新预期快照，底层句柄所有权保持不变。
         */
        void identity(ConfigurationStore.FileIdentity next) {
            identity = next;
        }

        /**
         * 幂等关闭所有的 Win32 文件句柄，仅在 CloseHandle 成功后标记已释放。
         */
        @Override
        public void close() throws IOException {
            if (!closed) {
                owner.closeFileHandle(handle);
                closed = true;
            }
        }
    }


}
