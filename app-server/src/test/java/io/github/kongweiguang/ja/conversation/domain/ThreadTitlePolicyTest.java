// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 覆盖会话即时标题与模型标题共享的 Unicode、附件和包装清理边界。 */
final class ThreadTitlePolicyTest {
    /** 中英文、换行和连续空白必须稳定折叠为单行导航标题。 */
    @Test
    void provisionalTextNormalizesWhitespace() {
        assertEquals("实现 Ja 会话 title", ThreadTitlePolicy.provisionalTitle(
                "  实现\nJa\t会话  title  ", List.of("ignored.txt")));
    }

    /** 控制、双向格式和私用字符不得进入数据库或 UI。 */
    @Test
    void removesUnsafeInvisibleCharacters() {
        String title = ThreadTitlePolicy.provisionalTitle("修复\u0000标\u202E题\uE000", List.of());
        assertEquals("修复标题", title);
    }

    /** 超长内容按 code point 截断并保留完整 Emoji。 */
    @Test
    void truncatesWithoutSplittingEmoji() {
        String title = ThreadTitlePolicy.provisionalTitle("😀".repeat(60), List.of());
        assertEquals(48, title.codePointCount(0, title.length()));
        assertTrue(title.endsWith("…"));
        assertFalse(Character.isHighSurrogate(title.charAt(title.length() - 2)));
    }

    /** 纯单附件对话直接表达分析对象。 */
    @Test
    void createsSingleAttachmentTitle() {
        assertEquals("分析 需求.pdf", ThreadTitlePolicy.provisionalTitle("", List.of("需求.pdf")));
    }

    /** 多附件只展示首个名称和真实总数，避免侧栏塞入文件清单。 */
    @Test
    void createsMultipleAttachmentTitle() {
        assertEquals("分析 first.png 等 3 个文件", ThreadTitlePolicy.provisionalTitle(
                "", List.of("first.png", "second.png", "third.png")));
    }

    /** 模型 Markdown、引号和标题前缀被视为包装而不是标题正文。 */
    @Test
    void cleansModelWrappers() {
        assertEquals("生产级标题", ThreadTitlePolicy.modelTitle("# “标题：生产级标题”"));
        assertEquals("Agent workflow", ThreadTitlePolicy.modelTitle("Title: Agent workflow"));
    }

    /** 模型结果必须在代码侧落实 24 个汉字预算，不能把约束只交给提示词。 */
    @Test
    void limitsModelTitleByHanCharacterWidth() {
        String title = ThreadTitlePolicy.modelTitle("标题：" + "界".repeat(30));
        assertEquals(24, title.codePointCount(0, title.length()));
        assertTrue(title.endsWith("…"));
    }
}
