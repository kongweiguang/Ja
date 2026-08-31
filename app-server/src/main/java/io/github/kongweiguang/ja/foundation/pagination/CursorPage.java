// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.pagination;

import java.util.List;
import java.util.Objects;

/**
 * 使用不透明游标承载稳定键集分页结果，避免领域端口依赖具体传输协议。
 */
public record CursorPage<T>(List<T> items, String nextCursor) {
    /**
     * 复制结果集合，防止数据库会话关闭后调用方继续观察可变投影。
     */
    public CursorPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}
