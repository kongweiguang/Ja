// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.security.windows;

import io.github.kongweiguang.ja.configuration.adapter.out.document.ConfigurationStore;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 Java 25 FFM 的 Win32 文件身份与 ACL 边界；restricted 调用只封装在此类内。
 */
@SuppressWarnings("restricted")
final class WindowsWin32Native implements AutoCloseable {
    private static final int MAX_READ_BYTES = 16 * 1024 * 1024;
    private static final int SE_FILE_OBJECT = 1;
    private static final int OWNER_SECURITY_INFORMATION = 0x0000_0001;
    private static final int DACL_SECURITY_INFORMATION = 0x0000_0004;
    private static final int PROTECTED_DACL_SECURITY_INFORMATION = 0x8000_0000;
    private static final int SE_DACL_PROTECTED = 0x1000;
    private static final int TOKEN_QUERY = 0x0008;
    private static final int TOKEN_USER = 1;
    private static final int ERROR_INSUFFICIENT_BUFFER = 122;
    private static final int ACL_SIZE_INFORMATION = 2;
    private static final int ACCESS_ALLOWED_ACE_TYPE = 0;
    private static final int FILE_ALL_ACCESS = 0x001F01FF;
    private static final int GENERIC_READ = 0x8000_0000;
    private static final int GENERIC_WRITE = 0x4000_0000;
    private static final int DELETE = 0x0001_0000;
    private static final int READ_CONTROL = 0x0002_0000;
    private static final int WRITE_DAC = 0x0004_0000;
    private static final int WRITE_OWNER = 0x0008_0000;
    private static final int FILE_READ_ATTRIBUTES = 0x00000080;
    private static final int FILE_SHARE_READ = 0x00000001;
    private static final int FILE_SHARE_WRITE = 0x00000002;
    private static final int FILE_SHARE_ALL = 0x00000007;
    private static final int CREATE_NEW = 1;
    private static final int OPEN_EXISTING = 3;
    private static final int ERROR_FILE_NOT_FOUND = 2;
    private static final int ERROR_PATH_NOT_FOUND = 3;
    private static final int ERROR_FILE_EXISTS = 80;
    private static final int ERROR_ALREADY_EXISTS = 183;
    private static final int FILE_ATTRIBUTE_DIRECTORY = 0x00000010;
    private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x00000400;
    private static final int FILE_ATTRIBUTE_NORMAL = 0x00000080;
    private static final int FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000;
    private static final int FILE_FLAG_BACKUP_SEMANTICS = 0x02000000;
    private static final int ACL_REVISION = 2;
    private static final int SECURITY_DESCRIPTOR_REVISION = 1;
    private static final int FILE_RENAME_INFO = 3;
    private static final int BY_HANDLE_FILE_INFORMATION_BYTES = 52;
    private static final AtomicInteger LIVE_HANDLES = new AtomicInteger();
    private static final AtomicInteger LIVE_DESCRIPTORS = new AtomicInteger();

    /**
     * resources 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    static ConfigurationStore.NativeResourceSnapshot resources() {
        return new ConfigurationStore.NativeResourceSnapshot(LIVE_HANDLES.get(), LIVE_DESCRIPTORS.get());
    }

    private final Arena libraryArena = Arena.ofShared();
    private final Linker linker;
    private final Linker.Option captureLastError;
    private final MethodHandle getNamedSecurityInfo;
    private final MethodHandle setNamedSecurityInfo;
    private final MethodHandle getSecurityInfo;
    private final MethodHandle setSecurityInfo;
    private final MethodHandle getSecurityDescriptorControl;
    private final MethodHandle getAclInformation;
    private final MethodHandle getAce;
    private final MethodHandle initializeAcl;
    private final MethodHandle addAccessAllowedAceEx;
    private final MethodHandle initializeSecurityDescriptor;
    private final MethodHandle setSecurityDescriptorOwner;
    private final MethodHandle setSecurityDescriptorDacl;
    private final MethodHandle setSecurityDescriptorControl;
    private final MethodHandle getLengthSid;
    private final MethodHandle equalSid;
    private final MethodHandle openProcessToken;
    private final MethodHandle getTokenInformation;
    private final MethodHandle getCurrentProcess;
    private final MethodHandle closeHandle;
    private final MethodHandle localFree;
    private final MethodHandle createFile;
    private final MethodHandle getFileInformationByHandle;
    private final MethodHandle readFile;
    private final MethodHandle writeFile;
    private final MethodHandle flushFileBuffers;
    private final MethodHandle setFileInformationByHandle;

    /**
     * 在独立 shared Arena 中加载 advapi32/kernel32 符号并固定签名，任一符号缺失都关闭 Arena。
     */
    WindowsWin32Native() throws IOException {
        try {
            linker = Linker.nativeLinker();
            captureLastError = Linker.Option.captureCallState("GetLastError");
            SymbolLookup advapi32 = SymbolLookup.libraryLookup("advapi32", libraryArena);
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", libraryArena);
            getNamedSecurityInfo = downcall(advapi32, "GetNamedSecurityInfoW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));
            setNamedSecurityInfo = downcall(advapi32, "SetNamedSecurityInfoW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            getSecurityInfo = downcall(advapi32, "GetSecurityInfo",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));
            setSecurityInfo = downcall(advapi32, "SetSecurityInfo",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            getSecurityDescriptorControl = downcall(advapi32, "GetSecurityDescriptorControl",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            getAclInformation = downcall(advapi32, "GetAclInformation",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            getAce = downcall(advapi32, "GetAce",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            initializeAcl = downcall(advapi32, "InitializeAcl",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            addAccessAllowedAceEx = downcall(advapi32, "AddAccessAllowedAceEx",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS));
            initializeSecurityDescriptor = downcall(advapi32, "InitializeSecurityDescriptor",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT));
            setSecurityDescriptorOwner = downcall(advapi32, "SetSecurityDescriptorOwner",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            setSecurityDescriptorDacl = downcall(advapi32, "SetSecurityDescriptorDacl",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            setSecurityDescriptorControl = downcall(advapi32, "SetSecurityDescriptorControl",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_SHORT, ValueLayout.JAVA_SHORT));
            getLengthSid = downcall(advapi32, "GetLengthSid",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            equalSid = downcall(advapi32, "EqualSid",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));
            openProcessToken = downcall(advapi32, "OpenProcessToken",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            getTokenInformation = downcall(advapi32, "GetTokenInformation",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS));
            getCurrentProcess = downcall(kernel32, "GetCurrentProcess",
                    FunctionDescriptor.of(ValueLayout.ADDRESS));
            closeHandle = downcall(kernel32, "CloseHandle",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            localFree = downcall(kernel32, "LocalFree",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            createFile = downcall(kernel32, "CreateFileW",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            getFileInformationByHandle = downcall(kernel32, "GetFileInformationByHandle",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));
            readFile = downcall(kernel32, "ReadFile",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));
            writeFile = downcall(kernel32, "WriteFile",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));
            flushFileBuffers = downcall(kernel32, "FlushFileBuffers",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            setFileInformationByHandle = downcall(kernel32, "SetFileInformationByHandle",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        } catch (Throwable failure) {
            libraryArena.close();
            throw new IOException("secret_acl_native_symbols_unavailable", failure);
        }
    }

    /**
     * openExisting 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    WindowsSecureSecretFile.WindowsSecretHandle openExisting(Path path, boolean required) throws IOException {
        WindowsSecureSecretFile.WindowsSecretHandle opened = open(path, GENERIC_READ | READ_CONTROL,
                FILE_SHARE_READ, OPEN_EXISTING);
        if (opened == null && required) throw new IOException("secret_file_missing");
        return opened;
    }

    /**
     * openPublished 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    WindowsSecureSecretFile.WindowsSecretHandle openPublished(Path path) throws IOException {
        return open(path, GENERIC_READ | READ_CONTROL, FILE_SHARE_ALL, OPEN_EXISTING);
    }

    /**
     * openRollback 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    WindowsSecureSecretFile.WindowsSecretHandle openRollback(Path path) throws IOException {
        WindowsSecureSecretFile.WindowsSecretHandle opened = open(path, GENERIC_READ | DELETE | READ_CONTROL,
                FILE_SHARE_ALL, OPEN_EXISTING);
        if (opened == null) throw new IOException("secret_rollback_open_failed");
        return opened;
    }

    /**
     * createTemporary 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    WindowsSecureSecretFile.WindowsSecretHandle createTemporary(Path parent) throws IOException {
        for (int attempt = 0; attempt < 32; attempt++) {
            Path path = parent.resolve(".ja-auth-" + UUID.randomUUID() + ".tmp");
            WindowsSecureSecretFile.WindowsSecretHandle opened = createProtected(path);
            if (opened != null) return opened;
        }
        throw new IOException("secret_temporary_name_exhausted");
    }

    /**
     * createProtected 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    private WindowsSecureSecretFile.WindowsSecretHandle createProtected(Path path) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            TokenIdentity identity = currentToken(arena);
            try {
                MemorySegment acl = currentUserAcl(identity.sid(), arena);
                MemorySegment descriptor = arena.allocate(64, 8);
                if ((int) invoke(initializeSecurityDescriptor, descriptor,
                        SECURITY_DESCRIPTOR_REVISION).value() == 0
                    || (int) invoke(setSecurityDescriptorOwner, descriptor,
                        identity.sid(), 0).value() == 0
                    || (int) invoke(setSecurityDescriptorDacl, descriptor,
                        1, acl, 0).value() == 0
                    || (int) invoke(setSecurityDescriptorControl, descriptor,
                        (short) SE_DACL_PROTECTED, (short) SE_DACL_PROTECTED).value() == 0) {
                    throw new IOException("secret_initial_descriptor_failed");
                }
                MemorySegment attributes = arena.allocate(24, 8);
                attributes.set(ValueLayout.JAVA_INT, 0, 24);
                attributes.set(ValueLayout.ADDRESS, 8, descriptor);
                attributes.set(ValueLayout.JAVA_INT, 16, 0);
                return open(path, GENERIC_READ | GENERIC_WRITE | DELETE | READ_CONTROL
                                  | WRITE_DAC | WRITE_OWNER,
                        FILE_SHARE_READ, CREATE_NEW, attributes);
            } finally {
                closeNativeHandle(identity.token(), "secret_token_close_failed");
            }
        }
    }

    /**
     * open 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    private WindowsSecureSecretFile.WindowsSecretHandle open(Path path, int access, int share, int disposition)
            throws IOException {
        return open(path, access, share, disposition, MemorySegment.NULL);
    }

    /**
     * open 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    private WindowsSecureSecretFile.WindowsSecretHandle open(Path path, int access, int share, int disposition,
                                                             MemorySegment securityAttributes) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            NativeCall opened = invoke(createFile, wide(path, arena), access, share,
                    securityAttributes, disposition,
                    FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OPEN_REPARSE_POINT, MemorySegment.NULL);
            MemorySegment handle = (MemorySegment) opened.value();
            if (isNull(handle) || handle.address() == -1L) {
                if ((disposition == OPEN_EXISTING
                     && (opened.error() == ERROR_FILE_NOT_FOUND
                         || opened.error() == ERROR_PATH_NOT_FOUND))
                    || (disposition == CREATE_NEW
                        && (opened.error() == ERROR_FILE_EXISTS
                            || opened.error() == ERROR_ALREADY_EXISTS))) {
                    return null;
                }
                throw nativeFailure("secret_file_open_failed", opened.error());
            }
            LIVE_HANDLES.incrementAndGet();
            try {
                return new WindowsSecureSecretFile.WindowsSecretHandle(this, path, handle, fileIdentity(handle));
            } catch (IOException failure) {
                closeFileHandle(handle);
                throw failure;
            }
        }
    }

    /**
     * 从卷根到目标依次打开 NOFOLLOW 目录句柄，任一中间失败按逆序释放已固定链。
     */
    WindowsPinnedDirectoryChain pinDirectoryChain(Path directory, boolean createMissing)
            throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        Path root = absolute.getRoot();
        if (root == null) throw new IOException("configuration_parent_invalid");
        List<MemorySegment> handles = new ArrayList<>();
        try {
            Path cursor = root;
            MemorySegment rootHandle = openDirectory(cursor, true);
            handles.add(rootHandle);
            for (Path component : root.relativize(absolute)) {
                cursor = cursor.resolve(component);
                MemorySegment handle = openDirectory(cursor, false);
                if (handle == null && createMissing) {
                    try {
                        Files.createDirectory(cursor);
                    } catch (FileAlreadyExistsException ignored) {
                        // 并发创建同一目录是允许的；随后必须通过 NOFOLLOW 句柄重新固定真实对象。
                    }
                    handle = openDirectory(cursor, true);
                }
                if (handle == null) {
                    closeDirectoryHandles(handles);
                    return null;
                }
                handles.add(handle);
            }
            return new WindowsPinnedDirectoryChain(this, handles);
        } catch (IOException failure) {
            try {
                closeDirectoryHandles(handles);
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /**
     * 以 FILE_FLAG_OPEN_REPARSE_POINT 打开目录句柄，可选模式仅容忍路径缺失，其他 Win32 错误均失败关闭。
     */
    private MemorySegment openDirectory(Path path, boolean required) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            NativeCall opened = invoke(createFile, wide(path, arena), FILE_READ_ATTRIBUTES,
                    FILE_SHARE_READ | FILE_SHARE_WRITE, MemorySegment.NULL, OPEN_EXISTING,
                    FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS,
                    MemorySegment.NULL);
            MemorySegment handle = (MemorySegment) opened.value();
            if (isNull(handle) || handle.address() == -1L) {
                if (!required && (opened.error() == ERROR_FILE_NOT_FOUND
                                  || opened.error() == ERROR_PATH_NOT_FOUND)) return null;
                throw nativeFailure("configuration_directory_open_failed", opened.error());
            }
            LIVE_HANDLES.incrementAndGet();
            try {
                requireDirectory(handle);
                return handle;
            } catch (IOException failure) {
                closeFileHandle(handle);
                throw failure;
            }
        }
    }

    /**
     * 按与打开相反的顺序关闭目录句柄，并将多个 CloseHandle 失败合并为 suppressed 异常。
     */
    private void closeDirectoryHandles(List<MemorySegment> handles) throws IOException {
        IOException failure = null;
        for (int index = handles.size() - 1; index >= 0; index--) {
            try {
                closeFileHandle(handles.get(index));
            } catch (IOException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        handles.clear();
        if (failure != null) throw failure;
    }

    /**
     * 通过句柄属性确认目标是真实目录且不是 reparse point，不信任路径预检结果。
     */
    private void requireDirectory(MemorySegment handle) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment information = arena.allocate(BY_HANDLE_FILE_INFORMATION_BYTES, 4);
            NativeCall loaded = invoke(getFileInformationByHandle, handle, information);
            if ((int) loaded.value() == 0) {
                throw nativeFailure("configuration_directory_identity_failed", loaded.error());
            }
            int attributes = information.get(ValueLayout.JAVA_INT, 0);
            if ((attributes & FILE_ATTRIBUTE_DIRECTORY) == 0
                || (attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
                throw new IOException("configuration_reparse_forbidden");
            }
        }
    }

    /**
     * readVerified 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    byte[] readVerified(WindowsSecureSecretFile.WindowsSecretHandle file) throws IOException {
        verify(file, 1);
        long size = file.identity().size();
        if (size > MAX_READ_BYTES) throw new IOException("configuration_file_too_large");
        byte[] bytes = new byte[Math.toIntExact(size)];
        boolean success = false;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nativeBytes = arena.allocate(Math.max(1L, size), 1);
            MemorySegment overflow = arena.allocate(1, 1);
            try {
                int offset = 0;
                while (offset < bytes.length) {
                    int count = transfer(readFile, file.handle, nativeBytes.asSlice(offset),
                            bytes.length - offset, "secret_file_read_failed");
                    if (count == 0) throw new IOException("configuration_file_changed_during_read");
                    offset += count;
                }
                if (transfer(readFile, file.handle, overflow, 1, "secret_file_read_failed") != 0) {
                    throw new IOException("configuration_file_changed_during_read");
                }
                MemorySegment.copy(nativeBytes, ValueLayout.JAVA_BYTE, 0,
                        bytes, 0, bytes.length);
                requireIdentity(file, file.identity());
                verifySecurity(file.handle);
                success = true;
                return bytes;
            } finally {
                nativeBytes.fill((byte) 0);
                overflow.fill((byte) 0);
            }
        } finally {
            if (!success) Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * protectWriteAndVerify 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    void protectWriteAndVerify(WindowsSecureSecretFile.WindowsSecretHandle file, byte[] bytes) throws IOException {
        if (bytes.length > MAX_READ_BYTES) throw new IOException("configuration_file_too_large");
        requireIdentity(file, file.identity().withSize(0).withLinks(1));
        protectHandle(file.handle);
        verifySecurity(file.handle);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nativeBytes = arena.allocate(Math.max(1, bytes.length), 1);
            try {
                MemorySegment.copy(bytes, 0, nativeBytes, ValueLayout.JAVA_BYTE, 0, bytes.length);
                int offset = 0;
                while (offset < bytes.length) {
                    int count = transfer(writeFile, file.handle, nativeBytes.asSlice(offset),
                            bytes.length - offset, "secret_file_write_failed");
                    if (count == 0) throw new IOException("secret_file_write_stalled");
                    offset += count;
                }
            } finally {
                nativeBytes.fill((byte) 0);
            }
        }
        if ((int) invoke(flushFileBuffers, file.handle).value() == 0) {
            throw new IOException("secret_file_flush_failed");
        }
        ConfigurationStore.FileIdentity written = fileIdentity(file.handle);
        file.identity(written);
        requireIdentity(file, written.withSize(bytes.length).withLinks(1));
        verifySecurity(file.handle);
    }

    /**
     * renameAtomic 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    void renameAtomic(WindowsSecureSecretFile.WindowsSecretHandle file, Path target, boolean replace) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = wide(extendedPath(target), arena);
            int nameBytes = Math.toIntExact((name.byteSize() / Character.BYTES - 1)
                                            * Character.BYTES);
            int structureBytes = 20 + nameBytes + Character.BYTES;
            MemorySegment rename = arena.allocate(structureBytes, 8);
            rename.set(ValueLayout.JAVA_BYTE, 0, (byte) (replace ? 1 : 0));
            rename.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL);
            rename.set(ValueLayout.JAVA_INT, 16, nameBytes);
            MemorySegment.copy(name, 0, rename, 20, nameBytes + Character.BYTES);
            NativeCall moved = invoke(setFileInformationByHandle, file.handle,
                    FILE_RENAME_INFO, rename, structureBytes);
            if ((int) moved.value() == 0) {
                throw nativeFailure("secret_atomic_move_failed", moved.error());
            }
        }
    }

    /**
     * requireIdentity 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    void requireIdentity(WindowsSecureSecretFile.WindowsSecretHandle file, ConfigurationStore.FileIdentity expected) throws IOException {
        ConfigurationStore.FileIdentity actual = fileIdentity(file.handle);
        file.identity(actual);
        if (!expected.sameObject(actual)) throw new IOException("configuration_identity_changed");
        if (expected.size() != actual.size()) throw new IOException("configuration_size_changed");
        if (expected.links() != actual.links()) throw new IOException("secret_link_count_invalid");
    }

    /**
     * verify 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    void verify(WindowsSecureSecretFile.WindowsSecretHandle file, int expectedLinks) throws IOException {
        ConfigurationStore.FileIdentity expected = file.identity().withLinks(expectedLinks);
        requireIdentity(file, expected);
        verifySecurity(file.handle);
    }

    /**
     * 统一 ReadFile/WriteFile 的传输计数检查，拒绝 Win32 返回超出申请缓冲区的异常长度。
     */
    private int transfer(MethodHandle function, MemorySegment handle, MemorySegment buffer,
                         int bytes, String code) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment transferred = arena.allocate(ValueLayout.JAVA_INT);
            NativeCall call = invoke(function, handle, buffer, bytes, transferred,
                    MemorySegment.NULL);
            if ((int) call.value() == 0) throw nativeFailure(code, call.error());
            int count = transferred.get(ValueLayout.JAVA_INT, 0);
            if (count < 0 || count > bytes) throw new IOException(code + "_count_invalid");
            return count;
        }
    }

    /**
     * 直接对已固定句柄设置当前 SID 的受保护 DACL，token 在成功与失败路径都关闭。
     */
    private void protectHandle(MemorySegment handle) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            TokenIdentity identity = currentToken(arena);
            try {
                MemorySegment acl = currentUserAcl(identity.sid(), arena);
                int status = (int) invoke(setSecurityInfo, handle, SE_FILE_OBJECT,
                        OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION
                        | PROTECTED_DACL_SECURITY_INFORMATION,
                        identity.sid(), MemorySegment.NULL, acl, MemorySegment.NULL).value();
                if (status != 0) throw nativeFailure("secret_acl_protect_failed", status);
            } finally {
                closeNativeHandle(identity.token(), "secret_token_close_failed");
            }
        }
    }

    /**
     * 构造仅含当前 SID FullControl 的精确 ACL，创建与显式保护操作共用该规则。
     */
    private MemorySegment currentUserAcl(MemorySegment sid, Arena arena) throws IOException {
        int sidBytes = (int) invoke(getLengthSid, sid).value();
        if (sidBytes <= 0 || sidBytes > 1024) throw new IOException("secret_token_sid_invalid");
        int aclBytes = 16 + sidBytes;
        MemorySegment acl = arena.allocate(aclBytes, 4);
        if ((int) invoke(initializeAcl, acl, aclBytes, ACL_REVISION).value() == 0) {
            throw new IOException("secret_acl_initialize_failed");
        }
        if ((int) invoke(addAccessAllowedAceEx, acl, ACL_REVISION, 0,
                FILE_ALL_ACCESS, sid).value() == 0) {
            throw new IOException("secret_acl_add_ace_failed");
        }
        return acl;
    }

    /**
     * 按路径读取安全描述符后应用受保护 DACL，LocalAlloc 描述符始终由本方法释放。
     */
    void protect(Path path) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            SecurityDescriptor descriptor = securityDescriptor(path, arena);
            try {
                int status = (int) invoke(setNamedSecurityInfo, wide(path, arena), SE_FILE_OBJECT,
                        DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
                        MemorySegment.NULL, MemorySegment.NULL, descriptor.dacl(), MemorySegment.NULL).value();
                if (status != 0) throw nativeFailure("secret_acl_protect_failed", status);
            } finally {
                freeDescriptor(descriptor.allocation());
            }
        }
    }

    /**
     * 按路径回读 ACL 并验证精确 owner/DACL/ACE，验证结束前释放 LocalAlloc 描述符。
     */
    void verify(Path path) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            SecurityDescriptor descriptor = securityDescriptor(path, arena);
            try {
                verifyDescriptor(descriptor, arena);
            } finally {
                freeDescriptor(descriptor.allocation());
            }
        }
    }

    /**
     * 从已固定文件句柄回读 ACL，避免路径替换窗口并在验证后释放描述符。
     */
    private void verifySecurity(MemorySegment handle) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            SecurityDescriptor descriptor = securityDescriptor(handle, arena);
            try {
                verifyDescriptor(descriptor, arena);
            } finally {
                freeDescriptor(descriptor.allocation());
            }
        }
    }

    /**
     * 要求 owner 为当前 SID、DACL 禁止继承且只含一条无 flags 的 FullControl ACE。
     */
    private void verifyDescriptor(SecurityDescriptor descriptor, Arena arena) throws IOException {
        TokenIdentity identity = currentToken(arena);
        try {
            requireEqualSid(descriptor.ownerSid(), identity.sid(), "secret_owner_not_current_user");
            MemorySegment control = arena.allocate(ValueLayout.JAVA_SHORT);
            MemorySegment revision = arena.allocate(ValueLayout.JAVA_INT);
            if ((int) invoke(getSecurityDescriptorControl, descriptor.allocation(),
                    control, revision).value() == 0
                || (Short.toUnsignedInt(control.get(ValueLayout.JAVA_SHORT, 0))
                    & SE_DACL_PROTECTED) == 0) {
                throw new IOException("secret_dacl_not_protected");
            }
            MemorySegment aclInfo = arena.allocate(12, 4);
            if ((int) invoke(getAclInformation, descriptor.dacl(), aclInfo, 12,
                    ACL_SIZE_INFORMATION).value() == 0
                || aclInfo.get(ValueLayout.JAVA_INT, 0) != 1) {
                throw new IOException("secret_acl_native_count_invalid");
            }
            MemorySegment aceOut = arena.allocate(ValueLayout.ADDRESS);
            if ((int) invoke(getAce, descriptor.dacl(), 0, aceOut).value() == 0) {
                throw new IOException("secret_acl_native_ace_unavailable");
            }
            MemorySegment ace = aceOut.get(ValueLayout.ADDRESS, 0).reinterpret(64);
            int type = Byte.toUnsignedInt(ace.get(ValueLayout.JAVA_BYTE, 0));
            int flags = Byte.toUnsignedInt(ace.get(ValueLayout.JAVA_BYTE, 1));
            int mask = ace.get(ValueLayout.JAVA_INT, 4);
            if (type != ACCESS_ALLOWED_ACE_TYPE || flags != 0 || mask != FILE_ALL_ACCESS) {
                throw new IOException("secret_acl_native_ace_invalid");
            }
            requireEqualSid(ace.asSlice(8), identity.sid(),
                    "secret_acl_principal_not_current_user");
        } finally {
            closeNativeHandle(identity.token(), "secret_token_close_failed");
        }
    }

    /**
     * 以 OPEN_REPARSE_POINT 打开短命句柄读取文件身份，无论读取成功与否都关闭句柄。
     */
    ConfigurationStore.FileIdentity identity(Path path) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            NativeCall opened = invoke(createFile, wide(path, arena), FILE_READ_ATTRIBUTES,
                    FILE_SHARE_ALL, MemorySegment.NULL, OPEN_EXISTING,
                    FILE_FLAG_OPEN_REPARSE_POINT, MemorySegment.NULL);
            MemorySegment handle = (MemorySegment) opened.value();
            if (isNull(handle) || handle.address() == -1L) {
                throw nativeFailure("configuration_identity_open_failed", opened.error());
            }
            LIVE_HANDLES.incrementAndGet();
            try {
                return fileIdentity(handle);
            } finally {
                closeNativeHandle(handle, "configuration_identity_close_failed");
            }
        }
    }

    /**
     * 从 BY_HANDLE_FILE_INFORMATION 提取卷序列、文件索引、大小和链接数，同时拒绝目录与 reparse point。
     */
    private ConfigurationStore.FileIdentity fileIdentity(MemorySegment handle) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment information = arena.allocate(BY_HANDLE_FILE_INFORMATION_BYTES, 4);
            NativeCall loaded = invoke(getFileInformationByHandle, handle, information);
            if ((int) loaded.value() == 0) {
                throw nativeFailure("configuration_identity_read_failed", loaded.error());
            }
            int attributes = information.get(ValueLayout.JAVA_INT, 0);
            if ((attributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT)) != 0) {
                throw new IOException("configuration_reparse_forbidden");
            }
            long volume = Integer.toUnsignedLong(information.get(ValueLayout.JAVA_INT, 28));
            long size = (Integer.toUnsignedLong(information.get(ValueLayout.JAVA_INT, 32)) << 32)
                        | Integer.toUnsignedLong(information.get(ValueLayout.JAVA_INT, 36));
            int links = information.get(ValueLayout.JAVA_INT, 40);
            long fileIndex = (Integer.toUnsignedLong(information.get(ValueLayout.JAVA_INT, 44)) << 32)
                             | Integer.toUnsignedLong(information.get(ValueLayout.JAVA_INT, 48));
            if (links <= 0) throw new IOException("secret_link_count_invalid");
            return new ConfigurationStore.FileIdentity(new WindowsFileKey(volume, fileIndex), size, links);
        }
    }

    /**
     * 按路径读取安全描述符；路径编码和 Win32 查询保留在本入口，返回指针的所有权统一交给接管方法。
     */
    private SecurityDescriptor securityDescriptor(Path path, Arena arena) throws IOException {
        SecurityDescriptorPointers pointers = allocateSecurityDescriptorPointers(arena);
        int status = (int) invoke(getNamedSecurityInfo, wide(path, arena), SE_FILE_OBJECT,
                OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION, pointers.owner(),
                MemorySegment.NULL, pointers.dacl(), MemorySegment.NULL, pointers.allocation()).value();
        if (status != 0) throw nativeFailure("secret_acl_read_failed", status);
        return takeSecurityDescriptor(pointers);
    }

    /**
     * 按已固定句柄读取安全描述符；句柄身份语义不变，返回指针的所有权统一交给接管方法。
     */
    private SecurityDescriptor securityDescriptor(MemorySegment handle, Arena arena) throws IOException {
        SecurityDescriptorPointers pointers = allocateSecurityDescriptorPointers(arena);
        int status = (int) invoke(getSecurityInfo, handle, SE_FILE_OBJECT,
                OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION, pointers.owner(),
                MemorySegment.NULL, pointers.dacl(), MemorySegment.NULL, pointers.allocation()).value();
        if (status != 0) throw nativeFailure("secret_acl_read_failed", status);
        return takeSecurityDescriptor(pointers);
    }

    /**
     * 为 Win32 安全描述符查询分配三个输出槽；槽位只在调用 Arena 内有效，不代表 LocalAlloc 所有权。
     */
    private static SecurityDescriptorPointers allocateSecurityDescriptorPointers(Arena arena) {
        return new SecurityDescriptorPointers(arena.allocate(ValueLayout.ADDRESS),
                arena.allocate(ValueLayout.ADDRESS), arena.allocate(ValueLayout.ADDRESS));
    }

    /**
     * 接管 Win32 返回的 LocalAlloc 描述符，并在附属指针无效时立即释放，避免计数与真实所有权分离。
     */
    private SecurityDescriptor takeSecurityDescriptor(SecurityDescriptorPointers pointers) throws IOException {
        MemorySegment allocated = pointers.allocation().get(ValueLayout.ADDRESS, 0);
        MemorySegment ownerSid = pointers.owner().get(ValueLayout.ADDRESS, 0);
        MemorySegment acl = pointers.dacl().get(ValueLayout.ADDRESS, 0);
        if (!isNull(allocated)) LIVE_DESCRIPTORS.incrementAndGet();
        if (isNull(allocated) || isNull(ownerSid) || isNull(acl)) {
            if (!isNull(allocated)) freeDescriptor(allocated);
            throw new IOException("secret_acl_descriptor_invalid");
        }
        return new SecurityDescriptor(allocated, ownerSid, acl);
    }

    /**
     * 借用当前进程 token 读取 SID，token 句柄在方法内关闭，SID 内存仅在调用方 Arena 生命周期内有效。
     */
    private TokenIdentity currentToken(Arena arena) throws IOException {
        MemorySegment process = (MemorySegment) invoke(getCurrentProcess).value();
        MemorySegment tokenOut = arena.allocate(ValueLayout.ADDRESS);
        NativeCall opened = invoke(openProcessToken, process, TOKEN_QUERY, tokenOut);
        if ((int) opened.value() == 0) {
            throw nativeFailure("secret_token_open_failed", opened.error());
        }
        MemorySegment token = tokenOut.get(ValueLayout.ADDRESS, 0);
        LIVE_HANDLES.incrementAndGet();
        try {
            MemorySegment needed = arena.allocate(ValueLayout.JAVA_INT);
            NativeCall sized = invoke(getTokenInformation, token, TOKEN_USER, MemorySegment.NULL,
                    0, needed);
            int bytes = needed.get(ValueLayout.JAVA_INT, 0);
            if ((int) sized.value() != 0 || sized.error() != ERROR_INSUFFICIENT_BUFFER || bytes < 16) {
                throw nativeFailure("secret_token_size_failed", sized.error());
            }
            MemorySegment buffer = arena.allocate(bytes, 8);
            NativeCall loaded = invoke(getTokenInformation, token, TOKEN_USER, buffer, bytes, needed);
            if ((int) loaded.value() == 0) {
                throw nativeFailure("secret_token_read_failed", loaded.error());
            }
            MemorySegment sid = buffer.get(ValueLayout.ADDRESS, 0);
            if (isNull(sid)) throw new IOException("secret_token_sid_invalid");
            return new TokenIdentity(token, sid);
        } catch (IOException failure) {
            closeNativeHandle(token, "secret_token_close_failed");
            throw failure;
        }
    }

    /**
     * 通过 EqualSid 比较 SID 二进制语义，不依赖本地化账户名或字符串形式。
     */
    private void requireEqualSid(MemorySegment left, MemorySegment right, String code)
            throws IOException {
        if ((int) invoke(equalSid, left, right).value() == 0) throw new IOException(code);
    }

    /**
     * 调用 CloseHandle 并仅在成功后递减实时计数，使泄漏测试与真实所有权一致。
     */
    private void closeNativeHandle(MemorySegment handle, String code) throws IOException {
        NativeCall closed = invoke(closeHandle, handle);
        if ((int) closed.value() == 0) {
            throw nativeFailure(code, closed.error());
        }
        LIVE_HANDLES.decrementAndGet();
    }

    /**
     * 以稳定的文件句柄错误码转交通用关闭逻辑，供句柄 owner 调用。
     */
    void closeFileHandle(MemorySegment handle) throws IOException {
        closeNativeHandle(handle, "secret_file_close_failed");
    }

    /**
     * freeDescriptor 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    private void freeDescriptor(MemorySegment descriptor) throws IOException {
        MemorySegment result = (MemorySegment) invoke(localFree, descriptor).value();
        if (!isNull(result)) throw new IOException("secret_acl_descriptor_free_failed");
        LIVE_DESCRIPTORS.decrementAndGet();
    }

    /**
     * 将 Path 编码为当前 confined Arena 所有的 UTF-16LE 终止字符串，不返回跨 Arena 指针。
     */
    private static MemorySegment wide(Path path, Arena arena) {
        return wide(path.toString(), arena);
    }

    /**
     * 在调用方 Arena 中编码零终止 UTF-16 字符串，内存随 Arena 关闭而失效。
     */
    private static MemorySegment wide(String value, Arena arena) {
        MemorySegment wide = arena.allocate((long) (value.length() + 1) * Character.BYTES, 2);
        for (int index = 0; index < value.length(); index++) {
            wide.setAtIndex(ValueLayout.JAVA_CHAR, index, value.charAt(index));
        }
        wide.setAtIndex(ValueLayout.JAVA_CHAR, value.length(), '\0');
        return wide;
    }

    /**
     * 将规范绝对路径转换为 Win32 NT 扩展路径，仅接受盘符和 UNC 两类明确根。
     */
    private static String extendedPath(Path path) throws IOException {
        String value = path.toAbsolutePath().normalize().toString();
        if (value.startsWith("\\\\")) {
            return "\\??\\UNC\\" + value.substring(2);
        }
        if (value.length() < 3 || value.charAt(1) != ':') {
            throw new IOException("configuration_path_invalid");
        }
        return "\\??\\" + value;
    }

    /**
     * 为每次 FFM 调用分配独立 call-state，同步捕获 GetLastError 并将 Throwable 收敛为 IO 错误。
     */
    private NativeCall invoke(MethodHandle function, Object... arguments) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(Linker.Option.captureStateLayout());
            Object[] actual = new Object[arguments.length + 1];
            actual[0] = state;
            System.arraycopy(arguments, 0, actual, 1, arguments.length);
            Object value = function.invokeWithArguments(actual);
            long offset = Linker.Option.captureStateLayout().byteOffset(
                    java.lang.foreign.MemoryLayout.PathElement.groupElement("GetLastError"));
            return new NativeCall(value, state.get(ValueLayout.JAVA_INT, offset));
        } catch (Throwable failure) {
            throw new IOException("secret_acl_native_call_failed", failure);
        }
    }

    /**
     * 按精确 FunctionDescriptor 绑定 Win32 符号，并为所有调用开启 GetLastError 捕获。
     */
    private MethodHandle downcall(SymbolLookup lookup, String symbol, FunctionDescriptor descriptor) {
        return linker.downcallHandle(lookup.findOrThrow(symbol), descriptor, captureLastError);
    }

    /**
     * 将 Win32 无符号错误码附加到稳定内部 code，不包含路径、SID 或凭据内容。
     */
    private static IOException nativeFailure(String code, int error) {
        return new IOException(code + "_" + Integer.toUnsignedString(error));
    }

    /**
     * 同时识别 Java null 和地址为零的 FFM NULL，避免解引用无效原生指针。
     */
    private static boolean isNull(MemorySegment value) {
        return value == null || value.address() == 0;
    }

    /**
     * 关闭持有 DLL 符号生命周期的 shared Arena；调用方必须先释放由本实例创建的句柄。
     */
    @Override
    public void close() {
        libraryArena.close();
    }

    /**
     * 关联 LocalAlloc 描述符及其内部 owner SID/DACL 指针，只有 allocation 可单独释放。
     */
    private record SecurityDescriptor(MemorySegment allocation, MemorySegment ownerSid,
                                      MemorySegment dacl) {
    }

    /**
     * SecurityDescriptorPointers 仅保存 FFM 输出槽，不拥有槽内 LocalAlloc 描述符。
     */
    private record SecurityDescriptorPointers(MemorySegment owner, MemorySegment dacl,
                                              MemorySegment allocation) {
    }

    /**
     * 绑定必须 CloseHandle 的 token 与仅在当前 Arena 有效的 SID 指针。
     */
    private record TokenIdentity(MemorySegment token, MemorySegment sid) {
    }

    /**
     * 同时保留 FFM 返回值和该次调用捕获的 GetLastError，避免后续调用覆盖。
     */
    private record NativeCall(Object value, int error) {
    }

    /**
     * 以卷序列和文件索引构成 Windows 稳定对象键，不使用可替换的路径作为身份。
     */
    private record WindowsFileKey(long volumeSerial, long fileIndex) {
    }
}
