// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Goal 内部事实统一使用 UTF-8 SHA-256，避免不同恢复边界产生不兼容摘要。 */
final class GoalDigest {
    /** 返回小写十六进制摘要；JDK 缺少强制算法属于不可恢复的运行时损坏。 */
    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** 工具类不进入依赖注入容器，也不持有进程状态。 */
    private GoalDigest() { }
}
