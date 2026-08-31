// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.document;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ConfigurationStoreWindowsTest 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。 */
final class ConfigurationStoreWindowsTest {
    @TempDir Path temporary;

    /** 验证首次写入和后续轮换都能读回新凭据，版本变化且不留临时文件。 */
    @Test
    void firstWriteAndRotationRemainCurrentUserOnly() throws Exception {
        requireWindows();
        Path auth = temporary.resolve("home/auth.json");
        byte[] first = "{\"cred\":\"first\"}".getBytes(StandardCharsets.UTF_8);
        byte[] second = "{\"cred\":\"second\"}".getBytes(StandardCharsets.UTF_8);

        ConfigurationStore.writeAtomic(auth, first, true);
        assertArrayEquals(first, ConfigurationStore.readSecret(auth));
        String firstVersion = ConfigurationStore.versionOf(first);
        ConfigurationStore.writeAtomic(auth, second, true);

        assertArrayEquals(second, ConfigurationStore.readSecret(auth));
        assertFalse(firstVersion.equals(ConfigurationStore.versionOf(second)));
        assertNoTemporaryFiles(auth.getParent());
    }

    /** broadExtraAceFailsClosedBeforeRead 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。 */
    @Test
    void broadExtraAceFailsClosedBeforeRead() throws Exception {
        requireWindows();
        Path auth = temporary.resolve("home/auth.json");
        ConfigurationStore.writeAtomic(auth, "secret".getBytes(StandardCharsets.UTF_8), true);
        AclFileAttributeView view = Files.getFileAttributeView(
                auth, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        List<AclEntry> widened = new ArrayList<>(view.getAcl());
        widened.add(AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(Files.getOwner(auth, LinkOption.NOFOLLOW_LINKS))
                .setPermissions(EnumSet.of(AclEntryPermission.READ_DATA))
                .build());
        view.setAcl(widened);

        assertThrows(IOException.class, () -> ConfigurationStore.readSecret(auth));
    }

    /** temporaryIsProtectedBeforeFirstSecretByte 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。 */
    @Test
    void temporaryIsProtectedBeforeFirstSecretByte() throws Exception {
        requireWindows();
        Path auth = temporary.resolve("home/auth.json");
        RecordingOperations operations = new RecordingOperations();

        ConfigurationStore.writeSecretAtomic(
                auth, "secret".getBytes(StandardCharsets.UTF_8), operations);

        assertEquals(0L, operations.sizeAtProtection);
        assertArrayEquals("secret".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(auth));
        assertNoTemporaryFiles(auth.getParent());
    }

    /** 遍历凭据轮换的所有故障注入点，验证旧正文、版本和 ACL 均保持且无临时残留。 */
    @Test
    void everyRotationFailurePreservesOldTarget() throws Exception {
        requireWindows();
        for (FailurePoint point : FailurePoint.values()) {
            Path home = Files.createDirectories(temporary.resolve(point.name()));
            Path auth = home.resolve("auth.json");
            byte[] old = "old-secret".getBytes(StandardCharsets.UTF_8);
            ConfigurationStore.writeAtomic(auth, old, true);
            String version = ConfigurationStore.versionOf(old);
            List<AclEntry> oldAcl = Files.getFileAttributeView(
                    auth, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).getAcl();
            RecordingOperations operations = new RecordingOperations();
            operations.failure = point;

            assertThrows(IOException.class, () -> ConfigurationStore.writeSecretAtomic(
                    auth, "new-secret".getBytes(StandardCharsets.UTF_8), operations), point.name());

            assertArrayEquals(old, Files.readAllBytes(auth), point.name());
            assertEquals(version, ConfigurationStore.versionOf(Files.readAllBytes(auth)), point.name());
            assertEquals(oldAcl, Files.getFileAttributeView(
                    auth, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).getAcl(), point.name());
            assertNoTemporaryFiles(home);
        }
    }

    /** 在 ACL 验证后原子替换凭据文件，验证读取流程以身份变更失败关闭。 */
    @Test
    void replacementRaceFailsClosed() throws Exception {
        requireWindows();
        Path home = Files.createDirectories(temporary.resolve("race"));
        Path auth = home.resolve("auth.json");
        Files.writeString(auth, "old-secret");
        RecordingOperations operations = new RecordingOperations();
        operations.replaceOnVerify = auth;

        IOException failure = assertThrows(IOException.class,
                () -> ConfigurationStore.readSecret(auth, operations));
        assertEquals("configuration_identity_changed", failure.getMessage());
        assertEquals(1, operations.verifyCalls);
    }

    /** hardLinkedSecretFailsClosed 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。 */
    @Test
    void hardLinkedSecretFailsClosed() throws Exception {
        requireWindows();
        Path home = Files.createDirectories(temporary.resolve("hard-link"));
        Path auth = home.resolve("auth.json");
        Path alias = home.resolve("alias.json");
        ConfigurationStore.writeAtomic(auth, "secret".getBytes(StandardCharsets.UTF_8), true);
        Files.createLink(alias, auth);

        IOException failure = assertThrows(IOException.class, () -> ConfigurationStore.readSecret(auth));

        assertEquals("secret_link_count_invalid", failure.getMessage());
        assertArrayEquals("secret".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(alias));
    }

    /** broadParentAclIsStrippedBeforeFirstSecretByte 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。 */
    @Test
    void broadParentAclIsStrippedBeforeFirstSecretByte() throws Exception {
        requireWindows();
        Path home = Files.createDirectories(temporary.resolve("broad-parent"));
        AclFileAttributeView parentView = Files.getFileAttributeView(
                home, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        List<AclEntry> original = parentView.getAcl();
        UserPrincipal everyone = home.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName("Everyone");
        List<AclEntry> broad = new ArrayList<>(original);
        broad.add(AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(everyone)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                .build());
        parentView.setAcl(broad);
        try {
            Path auth = home.resolve("auth.json");
            ConfigurationStore.writeAtomic(auth, "secret".getBytes(StandardCharsets.UTF_8), true);

            List<AclEntry> leaf = Files.getFileAttributeView(
                    auth, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).getAcl();
            assertEquals(1, leaf.size());
            assertTrue(leaf.getFirst().flags().isEmpty());
            assertFalse(leaf.getFirst().principal().equals(everyone));
            assertArrayEquals("secret".getBytes(StandardCharsets.UTF_8),
                    ConfigurationStore.readSecret(auth));
        } finally {
            parentView.setAcl(original);
        }
    }

    /** 验证 CREATE_NEW 在写入 secret 前已经安装最终 owner 与受保护 DACL。 */
    @Test
    void initialSecurityDescriptorIsAlreadyCurrentUserOnly() throws Exception {
        requireWindows();
        Path home = Files.createDirectories(temporary.resolve("initial-descriptor"));
        Path auth = home.resolve("auth.json");
        AclFileAttributeView parentView = Files.getFileAttributeView(
                home, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        List<AclEntry> original = parentView.getAcl();
        UserPrincipal everyone = home.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName("Everyone");
        List<AclEntry> broad = new ArrayList<>(original);
        broad.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(everyone)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT).build());
        parentView.setAcl(broad);
        try {
            java.util.concurrent.atomic.AtomicBoolean observed = new java.util.concurrent.atomic.AtomicBoolean();
            ConfigurationStore.writeWindowsSecretAtomicForTests(auth,
                    "secret".getBytes(StandardCharsets.UTF_8), new ConfigurationStore.WindowsWriteProbe() {
                        /** 在发布前回读临时文件 ACL，证明宽松父目录 ACL 没有被继承。 */
                        @Override
                        public void afterTemporaryCreated(Path temporaryFile) throws IOException {
                            ConfigurationStore.verifyWindowsSecretForTests(temporaryFile);
                            observed.set(true);
                        }
                    });
            assertTrue(observed.get());
            assertArrayEquals("secret".getBytes(StandardCharsets.UTF_8),
                    ConfigurationStore.readSecret(auth));
        } finally {
            parentView.setAcl(original);
        }
    }

    /** 在目录链句柄固定后尝试原子重命名，验证 Windows 拒绝竞态且凭据仍写入原目标。 */
    @Test
    void pinnedDirectoryChainRejectsConcurrentRename() throws Exception {
        requireWindows();
        Path home = Files.createDirectories(temporary.resolve("pinned-home/child"));
        Path auth = home.resolve("auth.json");
        Path raced = home.getParent().resolveSibling("pinned-home-raced");
        java.util.concurrent.atomic.AtomicReference<IOException> denied = new java.util.concurrent.atomic.AtomicReference<>();

        ConfigurationStore.writeWindowsSecretAtomicForTests(auth,
                "secret".getBytes(StandardCharsets.UTF_8), new ConfigurationStore.WindowsWriteProbe() {
                    /** 在精确的固定时点尝试移动父目录，避免测试时序依赖休眠。 */
                    @Override
                    public void afterDirectoriesPinned(Path ignored) throws IOException {
                        denied.set(assertThrows(IOException.class,
                                () -> Files.move(home.getParent(), raced, StandardCopyOption.ATOMIC_MOVE)));
                    }
                });

        assertTrue(denied.get() != null);
        assertTrue(Files.exists(home, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(raced, LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals("secret".getBytes(StandardCharsets.UTF_8),
                ConfigurationStore.readSecret(auth));
    }

    /** leafOwnerAndAceMatchCurrentProcessTokenSid 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。 */
    @Test
    void leafOwnerAndAceMatchCurrentProcessTokenSid() throws Exception {
        requireWindows();
        Path auth = temporary.resolve("token-sid/auth.json");
        ConfigurationStore.writeAtomic(auth, "secret".getBytes(StandardCharsets.UTF_8), true);

        AclFileAttributeView view = Files.getFileAttributeView(
                auth, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        assertEquals(Files.getOwner(auth, LinkOption.NOFOLLOW_LINKS),
                view.getAcl().getFirst().principal());
        assertArrayEquals("secret".getBytes(StandardCharsets.UTF_8),
                ConfigurationStore.readSecret(auth));
    }

    /** 比较成功读写及硬链接失败前后的原生计数，验证句柄和 LocalAlloc 描述符零泄漏。 */
    @Test
    void nativeHandlesAndSecurityDescriptorsAreAlwaysReleased() throws Exception {
        requireWindows();
        ConfigurationStore.NativeResourceSnapshot before = ConfigurationStore.nativeResourcesForTests();
        Path home = Files.createDirectories(temporary.resolve("resources"));
        Path auth = home.resolve("auth.json");
        ConfigurationStore.writeAtomic(auth, "secret".getBytes(StandardCharsets.UTF_8), true);
        byte[] loaded = ConfigurationStore.readSecret(auth);
        java.util.Arrays.fill(loaded, (byte) 0);
        Path alias = Files.createLink(home.resolve("alias.json"), auth);
        assertThrows(IOException.class, () -> ConfigurationStore.readSecret(auth));
        Files.delete(alias);

        assertEquals(before, ConfigurationStore.nativeResourcesForTests());
    }

    /** homeJunctionIsRejectedWithoutOutsideWrite 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。 */
    @Test
    void homeJunctionIsRejectedWithoutOutsideWrite() throws Exception {
        requireWindows();
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        Path junction = temporary.resolve("home-link");
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                junction.toString(), outside.toString()).redirectErrorStream(true).start();
        process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        assertEquals(0, process.waitFor(), "Windows junction fixture must be available");
        try {
            assertThrows(IOException.class, () -> ConfigurationStore.writeAtomic(
                    junction.resolve("auth.json"), "secret".getBytes(StandardCharsets.UTF_8), true));
            assertFalse(Files.exists(outside.resolve("auth.json"), LinkOption.NOFOLLOW_LINKS));
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    /** 明确要求 Windows 真实 ACL/FFM 环境，禁止在其他平台以假实现冒充验收。 */
    private static void requireWindows() {
        assertTrue(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"),
                "Windows ACL tests must run on Windows");
    }

    /** 验证成功和每个注入故障都会清除 secret 临时文件与回滚文件。 */
    private static void assertNoTemporaryFiles(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> {
                Path fileName = path.getFileName();
                return fileName != null && fileName.toString().startsWith(".ja-auth-");
            }));
        }
    }

    /** 枚举必须保留旧文件身份的每个故障注入边界。 */
    private enum FailurePoint {
        /** 在临时文件应用安全描述符时失败。 */
        PROTECT,
        /** 在写入后校验临时文件身份时失败。 */
        VERIFY_TEMP,
        /** 在原子替换最终文件时失败。 */
        MOVE,
        /** 在发布后校验最终文件身份时失败。 */
        VERIFY_TARGET
    }

    /** 该声明集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。 */
    private static final class RecordingOperations implements ConfigurationStore.SecretFileOperations {
        private FailurePoint failure;
        private long sizeAtProtection = -1;
        private int verifyCalls;
        private int moveCalls;
        private Path replaceOnVerify;

        /** 在目标父目录创建可观测的 fixture 临时文件，便于断言清理边界。 */
        @Override
        public Path createTemporary(Path parent) throws IOException {
            return Files.createTempFile(parent, ".ja-auth-test-", ".tmp");
        }

        /** 记录权限保护时的文件大小，并可在写入前精确注入保护失败。 */
        @Override
        public void protectAndVerify(Path path) throws IOException {
            sizeAtProtection = Files.size(path);
            if (failure == FailurePoint.PROTECT) throw new IOException("injected_protect_failure");
        }

        /** 按调用次序注入临时或发布后验证失败，也可在首次验证时模拟替换竞态。 */
        @Override
        public void verify(Path path) throws IOException {
            verifyCalls++;
            if (replaceOnVerify != null && verifyCalls == 1) {
                Path replacement = Files.writeString(path.resolveSibling("replacement.tmp"), "new-secret");
                Files.move(replacement, replaceOnVerify, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            if (failure == FailurePoint.VERIFY_TEMP && verifyCalls == 1) {
                throw new IOException("injected_temp_verify_failure");
            }
            if (failure == FailurePoint.VERIFY_TARGET && verifyCalls == 3) {
                throw new IOException("injected_target_verify_failure");
            }
        }

        /** 为旧凭据创建同目录硬链接，还原生产轮换在发布后的回滚所有权。 */
        @Override
        public Path createRollbackLink(Path parent, Path target) throws IOException {
            Path rollback = Files.createTempFile(parent, ".ja-auth-rollback-test-", ".tmp");
            Files.delete(rollback);
            return Files.createLink(rollback, target);
        }

        /** 记录并可在首次原子替换时注入失败，后续调用仍可用于验证回滚。 */
        @Override
        public void moveAtomic(Path source, Path target) throws IOException {
            moveCalls++;
            if (failure == FailurePoint.MOVE && moveCalls == 1) {
                throw new IOException("injected_move_failure");
            }
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
