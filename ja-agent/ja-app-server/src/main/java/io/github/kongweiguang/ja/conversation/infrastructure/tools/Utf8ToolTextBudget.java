// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.tools;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 为模型可见工具正文按 UTF-8 字节数追加完整行，拒绝半行以保持路径和结果计数可验证。
 */
final class Utf8ToolTextBudget {
    static final int MAX_MODEL_VISIBLE_TEXT_BYTES = 65_536;

    private final int maxBytes;
    private final StringBuilder text = new StringBuilder();
    private int byteCount;
    private int lineCount;

    /**
     * 冻结单次结果的字节上限，允许零预算用于边界测试但拒绝负预算。
     */
    Utf8ToolTextBudget(int maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must not be negative");
        }
        this.maxBytes = maxBytes;
    }

    /**
     * 仅当分隔符和整行都能放入预算时追加；失败不改变缓冲区，调用方可稳定报告截断位置。
     */
    boolean appendLine(String line) {
        Objects.requireNonNull(line, "line");
        int separatorBytes = lineCount == 0 ? 0 : 1;
        int lineBytes = utf8ByteLength(line);
        if ((long) byteCount + separatorBytes + lineBytes > maxBytes) {
            return false;
        }
        if (separatorBytes != 0) {
            text.append('\n');
        }
        text.append(line);
        byteCount += separatorBytes + lineBytes;
        lineCount++;
        return true;
    }

    /**
     * 返回 UTF-8 编码后的文本字节数，使调用方不依赖 UTF-16 char 长度估算预算。
     */
    static int utf8ByteLength(String value) {
        return Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 返回已追加行数；空行也计数，以免分页丢失行分隔语义。
     */
    int lineCount() {
        return lineCount;
    }

    /**
     * 返回正文实际消耗的 UTF-8 字节数，不含未追加的下一行。
     */
    int byteCount() {
        return byteCount;
    }

    /**
     * 返回预算内的完整行文本副本，避免调用方修改内部累加器。
     */
    String text() {
        return text.toString();
    }
}
