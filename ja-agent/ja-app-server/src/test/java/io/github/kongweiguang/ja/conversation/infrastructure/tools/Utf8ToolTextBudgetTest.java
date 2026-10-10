// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证模型可见工具正文的 UTF-8 字节预算和整行边界。 */
class Utf8ToolTextBudgetTest {
    /** 多字节码点与分隔符按实际 UTF-8 占用计数，拒绝追加后不得留下半行或改写已接纳正文。 */
    @Test
    void appendsCompleteUnicodeLinesWithinExactByteBudget() {
        Utf8ToolTextBudget budget = new Utf8ToolTextBudget(8);

        assertTrue(budget.appendLine("界"));
        assertTrue(budget.appendLine("😀"));
        assertFalse(budget.appendLine("x"));

        assertEquals("界\n😀", budget.text());
        assertEquals(8, budget.byteCount());
        assertEquals(2, budget.lineCount());
    }

    /** 空行也占据行序号，以分隔符和完整空行保持文件内容投影的行边界。 */
    @Test
    void preservesEmptyLineSeparators() {
        Utf8ToolTextBudget budget = new Utf8ToolTextBudget(2);

        assertTrue(budget.appendLine(""));
        assertTrue(budget.appendLine("a"));

        assertEquals("\na", budget.text());
        assertEquals(2, budget.byteCount());
        assertEquals(2, budget.lineCount());
    }
}
