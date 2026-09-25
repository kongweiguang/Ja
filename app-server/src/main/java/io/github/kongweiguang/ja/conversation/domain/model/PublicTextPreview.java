// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import java.util.Objects;

/** JA-RPC 终态与 Timeline 共用短预览；完整正文留在持久事实并由身份分页读取。 */
public final class PublicTextPreview {
    private static final int MAX_CHARACTERS = 65_536;
    private static final int WIRE_CHARACTERS = 4_096;

    /** 仅提供纯函数投影，禁止实例持有完整正文或会话状态。 */
    private PublicTextPreview() { }

    /** 从有序模型块组装安全前缀，避免先复制完整正文或把 Tool/opaque 内容投影到界面。 */
    public static String from(ModelMessage message) {
        Objects.requireNonNull(message, "message");
        StringBuilder result = new StringBuilder();
        for (ModelContent block : message.content()) {
            if (!(block instanceof TextContent text)) continue;
            int remaining = MAX_CHARACTERS - result.length();
            if (remaining <= 0) break;
            int end = Math.min(remaining, text.text().length());
            if (end < text.text().length() && end > 0
                    && Character.isHighSurrogate(text.text().charAt(end - 1))
                    && Character.isLowSurrogate(text.text().charAt(end))) end--;
            if (end > 0) result.append(text.text(), 0, end);
        }
        return result.toString();
    }

    /** 已公开摘要只缩短事件投影；原始字符串仍完整提交，代理对绝不在预览边界拆开。 */
    public static String of(String value) {
        if (value == null || value.length() <= MAX_CHARACTERS) return value;
        int end = MAX_CHARACTERS;
        if (Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) end--;
        return value.substring(0, end);
    }

    /** 实时事件只携带一个小前缀，防止大终态事件拖住本地传输；全文走持久消息页。 */
    public static String wireFrom(ModelMessage message) {
        return wire(from(message));
    }

    /** 单条终态、模型步与排队消费事件沿用同一短预览；历史预览仍可略长以便阅读。 */
    public static String wire(String value) {
        if (value == null || value.length() <= WIRE_CHARACTERS) return value;
        int end = WIRE_CHARACTERS;
        if (Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) end--;
        return value.substring(0, end);
    }
}
