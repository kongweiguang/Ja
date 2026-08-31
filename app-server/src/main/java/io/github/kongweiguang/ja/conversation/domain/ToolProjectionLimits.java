// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

/**
 * 定义普通请求与唯一一次溢出恢复共用的 Tool 输出投影边界。
 */
public record ToolProjectionLimits(int headCharacters, int tailCharacters, boolean artifactOnly) {
    /** 普通调用方只选择头尾边界；artifact-only 只能由溢出恢复显式创建。 */
    public ToolProjectionLimits(int headCharacters, int tailCharacters) {
        this(headCharacters, tailCharacters, false);
    }

    /**
     * 在进入应用编排前拒绝空投影和无界投影，避免不同调用链采用不同内存上限。
     */
    public ToolProjectionLimits {
        if (headCharacters < 0 || tailCharacters < 0
            || headCharacters + (long) tailCharacters < 1
            || headCharacters > 1_000_000 || tailCharacters > 1_000_000) {
            throw new IllegalArgumentException("invalid Tool output projection limits");
        }
    }

    /**
     * 仅收紧一次两侧边界，并确保极小配置仍保留至少一个字符。
     */
    public ToolProjectionLimits shrinkOnce() {
        int head = headCharacters / 2;
        int tail = tailCharacters / 2;
        if (head + tail == 0) {
            if (headCharacters > 0) {
                head = 1;
            } else {
                tail = 1;
            }
        }
        return new ToolProjectionLimits(head, tail);
    }

    /** 构造溢出恢复唯一允许的 artifact-only 阶段，仍保留合法占位边界。 */
    public static ToolProjectionLimits artifactOnlyProjection() {
        return new ToolProjectionLimits(1, 0, true);
    }
}
