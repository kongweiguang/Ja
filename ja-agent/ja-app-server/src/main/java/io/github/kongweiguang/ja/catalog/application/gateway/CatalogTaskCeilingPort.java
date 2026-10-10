// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.application.gateway;

import java.util.Optional;

/**
 * Catalog 查询消费方定义的能力上限读取合同；隐藏 Conversation/Collaboration 的完整能力端口。
 */
@FunctionalInterface
public interface CatalogTaskCeilingPort {
    /**
     * 读取已冻结的能力上限；根会话或尚未创建子任务时返回空。
     *
     * @param threadId 会话标识
     * @return 不可变能力上限
     */
    Optional<String> readFingerprint(String threadId);
}
