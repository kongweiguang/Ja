// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Thread 子智能体策略的独立持久化边界，避免污染公开 Thread 投影。 */
@Mapper
public interface SubagentPolicyMapper {
    /** 读取创建时冻结的策略；不存在只允许由迁移前旧库一次性回填解决。 */
    PersistenceRecords.SubagentPolicyRow select(@Param("threadId") String threadId);

    /** 普通或 Child Thread 创建事务内写入不可变策略。 */
    int insert(PersistenceRecords.SubagentPolicyInsert values);
}
