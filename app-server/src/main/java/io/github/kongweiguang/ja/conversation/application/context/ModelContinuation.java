// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context;

/**
 * 保存 Provider 不透明续传状态；本地压缩后必须丢弃，禁止跨摘要边界复用。
 */
public record ModelContinuation(String protocol, String opaqueState) {
    /**
     * 限制协议名和不透明状态大小，避免未受信 Provider 数据无限进入内存与存储。
     */
    public ModelContinuation {
        if (protocol == null || protocol.isBlank() || protocol.length() > 128
            || opaqueState == null || opaqueState.isBlank() || opaqueState.length() > 4_000_000
            || protocol.indexOf('\0') >= 0 || opaqueState.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid model continuation");
        }
    }
}
