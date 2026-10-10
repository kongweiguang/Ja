// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.context;

import java.util.List;
import java.util.Objects;

/**
 * 描述摘要压缩时同一轮被拆成已淘汰前缀和保留后缀的中立证据。
 *
 * @param turnId 本次可恢复执行的稳定标识
 * @param prefix 前缀
 * @param suffix 后缀
 */
public record SummaryTurnSplit(String turnId,
                               List<SummaryContextMessage> prefix,
                               List<SummaryContextMessage> suffix) {
    /**
     * 冻结两侧消息顺序并拒绝无法解释的空切分。
     */
    public SummaryTurnSplit {
        if (turnId == null || turnId.isBlank() || turnId.length() > 256 || turnId.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid split turn");
        }
        prefix = List.copyOf(Objects.requireNonNull(prefix, "prefix"));
        suffix = List.copyOf(Objects.requireNonNull(suffix, "suffix"));
        if (prefix.isEmpty() || suffix.isEmpty()) {
            throw new IllegalArgumentException("split turn requires both sides");
        }
    }

}
