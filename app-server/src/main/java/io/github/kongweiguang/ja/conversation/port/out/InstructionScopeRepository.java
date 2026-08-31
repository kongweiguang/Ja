// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import java.time.Instant;
import java.util.List;

/**
 * 持久化 Thread 已发现的嵌套 AGENTS 目录；正文始终由文件系统实时读取，避免数据库成为第二事实源。
 */
public interface InstructionScopeRepository {
    /**
     * 按目录深度和路径稳定排序返回已发现 scope，使重启后的发现顺序可复现。
     */
    List<String> list(String threadId);

    /**
     * 幂等登记一个 workspace 相对目录；256 上限必须在同一数据库事务中判定，避免并发越界。
     */
    Registration register(String threadId, String relativeDirectory, Instant discoveredAt);

    /** scope 登记结果显式区分幂等命中与容量拒绝，调用方不得猜测受影响行数。 */
    enum Registration {
        /** 首次持久化该目录。 */
        REGISTERED,

        /** 目录此前已经发现。 */
        ALREADY_PRESENT,

        /** Thread 已达到 256 个嵌套 scope 的硬上限。 */
        LIMIT_REACHED
    }
}
