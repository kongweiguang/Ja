// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 冻结父进程的完整 Shell 环境；命令行工具的登录、代理和用户配置必须与 Ja 启动环境一致。
 */
final class ShellProcessEnvironment {

    /** 纯策略类型不持有环境快照，实际冻结值归 ShellProfile。 */
    private ShellProcessEnvironment() {
    }

    /**
     * 复制启动 Ja 的父进程环境，不按变量名筛选、重写 PATH 或猜测哪些变量属于敏感信息。
     * 环境的唯一修改边界留给后续平台启动器，以便所有 Shell 方言看到同一份输入。
     */
    static Map<String, String> capture(ShellProfile.OperatingSystem os,
                                       Map<String, String> environment) {
        return validate(os, environment);
    }

    /**
     * 只校验进程协议不能接受的 NUL 和环境名格式，并保留其它变量、控制字符与原始值。
     * Windows 先按原生大小写不敏感语义合并重复键，避免用户环境在 Java Map 中被错误拒绝。
     */
    static Map<String, String> validate(ShellProfile.OperatingSystem os,
                                        Map<String, String> environment) {
        Objects.requireNonNull(os, "os");
        Objects.requireNonNull(environment, "environment");
        Map<String, String> copy = os == ShellProfile.OperatingSystem.WINDOWS
                ? new TreeMap<>(String.CASE_INSENSITIVE_ORDER)
                : new LinkedHashMap<>(environment.size());
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (!isEnvironmentName(os, name) || value == null || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("shell environment value is invalid");
            }
            copy.put(name, value);
        }
        return Map.copyOf(copy);
    }

    /**
     * 接受 Windows 普通变量名以及 CreateProcess 使用的 `=EXITCODE`/按盘符变量，拒绝 NUL 和额外等号。
     */
    private static boolean isEnvironmentName(ShellProfile.OperatingSystem os, String name) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0) {
            return false;
        }
        int equals = name.indexOf('=');
        return equals < 0 || (os == ShellProfile.OperatingSystem.WINDOWS && equals == 0
                && name.length() > 1 && name.indexOf('=', 1) < 0);
    }
}
