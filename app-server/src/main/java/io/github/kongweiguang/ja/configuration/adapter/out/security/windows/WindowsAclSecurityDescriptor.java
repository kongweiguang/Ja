// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.security.windows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

/**
 * WindowsAclSecurityDescriptor 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
 */
final class WindowsAclSecurityDescriptor {
    /**
     * ACL 策略不保存句柄或可变状态，禁止实例化以保持唯一的 Win32 校验路径。
     */
    private WindowsAclSecurityDescriptor() {
    }

    /**
     * 将目标 ACL 收紧为当前 SID 的 FullControl 并禁止继承，写入后立即回读验证。
     */
    static void protect(Path path) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(
                path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) throw new IOException("secret_acl_view_unavailable");
        UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        AclEntry entry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .setFlags(EnumSet.noneOf(AclEntryFlag.class))
                .build();
        view.setAcl(List.of(entry));
        try (WindowsWin32Native nativeAcl = new WindowsWin32Native()) {
            nativeAcl.protect(path);
        }
        verify(path);
    }

    /**
     * verify 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    static void verify(Path path) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(
                path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) throw new IOException("secret_acl_view_unavailable");
        List<AclEntry> entries = view.getAcl();
        if (entries.size() != 1) throw new IOException("secret_acl_entry_count_invalid");
        AclEntry entry = entries.getFirst();
        if (entry.type() != AclEntryType.ALLOW
            || !entry.permissions().equals(EnumSet.allOf(AclEntryPermission.class))
            || !entry.flags().isEmpty()) {
            throw new IOException("secret_acl_entry_invalid");
        }
        try (WindowsWin32Native nativeAcl = new WindowsWin32Native()) {
            nativeAcl.verify(path);
        }
    }
}
