// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Thread 嵌套指令 scope 的最窄 Mapper，不承担 AGENTS 文件读取。 */
@Mapper
public interface InstructionScopeMapper {
    /** 按深度和路径读取全部 scope，保证重启后装配顺序稳定。 */
    List<String> selectScopes(@Param("threadId") String threadId);

    /** 查询精确 scope，幂等路径必须先于容量判断。 */
    int countScope(PersistenceRecords.ScopeKey values);

    /** 查询 Thread 当前 scope 数量，供容量拒绝诊断与测试回读。 */
    int countScopes(@Param("threadId") String threadId);

    /** 单条 INSERT SELECT 原子执行 256 上限和幂等冲突门，避免并发先查后写越界。 */
    int insertScopeWithinLimit(PersistenceRecords.ScopeInsert values);
}
