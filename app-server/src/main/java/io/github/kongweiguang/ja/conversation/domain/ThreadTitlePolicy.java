// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

import java.text.Normalizer;
import java.util.List;
import java.util.Objects;

/** 会话标题的唯一纯规则入口；即时标题与模型标题共享字符安全边界。 */
public final class ThreadTitlePolicy {
    private static final int TITLE_CODE_POINT_LIMIT = 48;

    /** 纯策略不持有状态，禁止实例化以维持所有调用方使用同一静态规则。 */
    private ThreadTitlePolicy() {
    }

    /** 文本优先，只有文本清理后为空才使用附件名，避免附件掩盖用户意图。 */
    public static String provisionalTitle(String userText, List<String> attachmentNames) {
        String text = clean(userText);
        if (!text.isBlank()) return truncate(text, TITLE_CODE_POINT_LIMIT);
        List<String> names = Objects.requireNonNullElse(attachmentNames, List.<String>of()).stream()
                .map(ThreadTitlePolicy::clean)
                .filter(value -> !value.isBlank())
                .toList();
        if (names.isEmpty()) return "";
        String source = names.size() == 1
                ? "分析 " + names.getFirst()
                : "分析 " + names.getFirst() + " 等 " + names.size() + " 个文件";
        return truncate(source, TITLE_CODE_POINT_LIMIT);
    }

    /** 模型输出先剥离说明性包装；空结果表示禁止提交 AUTO。 */
    public static String modelTitle(String source) {
        String cleaned = clean(source);
        cleaned = cleaned.replaceFirst("^[#*`]+\\s*", "").trim();
        if (cleaned.length() >= 2
            && matchingWrapper(cleaned.charAt(0), cleaned.charAt(cleaned.length() - 1))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        cleaned = cleaned.replaceFirst("(?i)^(?:title|标题)\\s*[:：-]\\s*", "");
        return cleaned.isBlank() ? "" : truncateModelTitle(cleaned);
    }

    /** NFKC 后压缩空白并删除不可见类别，使数据库与 UI 得到稳定文本。 */
    public static String clean(String source) {
        String normalized = Normalizer.normalize(Objects.requireNonNullElse(source, ""), Normalizer.Form.NFKC);
        StringBuilder cleaned = new StringBuilder(Math.min(normalized.length(), 512));
        boolean pendingSpace = false;
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                pendingSpace = !cleaned.isEmpty();
                continue;
            }
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint) || type == Character.FORMAT
                || type == Character.PRIVATE_USE || type == Character.SURROGATE) continue;
            if (pendingSpace) cleaned.append(' ');
            cleaned.appendCodePoint(codePoint);
            pendingSpace = false;
        }
        return cleaned.toString().trim();
    }

    /** 只识别自然语言标题的成对包装，避免误删真实字符。 */
    private static boolean matchingWrapper(char first, char last) {
        return first == '"' && last == '"' || first == '\'' && last == '\''
               || first == '“' && last == '”' || first == '‘' && last == '’';
    }

    /** 按 code point 截断并预留省略号，不切断 Emoji 代理对。 */
    private static String truncate(String value, int maximumCodePoints) {
        int count = value.codePointCount(0, value.length());
        if (count <= maximumCodePoints) return value;
        int end = value.offsetByCodePoints(0, maximumCodePoints - 1);
        return value.substring(0, end).stripTrailing() + "…";
    }

    /**
     * 模型标题使用 48 个窄字符预算，汉字按两个单位计算，落实“24 个汉字或 48 个字符”而不只依赖提示词。
     */
    private static String truncateModelTitle(String value) {
        int total = value.codePoints().map(ThreadTitlePolicy::modelCharacterUnits).sum();
        if (total <= TITLE_CODE_POINT_LIMIT) return value;
        int used = 0;
        int end = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int units = modelCharacterUnits(codePoint);
            if (used + units + 1 > TITLE_CODE_POINT_LIMIT) break;
            used += units;
            end += Character.charCount(codePoint);
        }
        return value.substring(0, end).stripTrailing() + "…";
    }

    /** 汉字使用双单位，其它 Unicode code point 使用单单位，规则保持简单且可预测。 */
    private static int modelCharacterUnits(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN ? 2 : 1;
    }
}
