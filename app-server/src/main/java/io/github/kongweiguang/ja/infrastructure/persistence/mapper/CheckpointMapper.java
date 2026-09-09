// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;


/**
 * Append-only checkpoint 与 Thread source revision Mapper。
 */
@Mapper
public interface CheckpointMapper {
    /**
     * 读取 Thread 当前 revision，作为 checkpoint CAS 的权威前置条件。
     */
    Long selectThreadRevision(@Param("threadId") String threadId);

    /**
     * 读取 Thread 最新 checkpoint，恢复路径不拼接历史中间版本。
     */
    PersistenceRecords.CheckpointRow selectCheckpoint(@Param("threadId") String threadId);

    /** 按 Thread 与不可变 checkpoint ID 精确读取 Provider settlement 引用的摘要。 */
    PersistenceRecords.CheckpointRow selectCheckpointIdentity(
            @Param("threadId") String threadId, @Param("checkpointId") String checkpointId);

    /**
     * 精确读取 source 行，避免幂等重试把更新的 checkpoint 误认成自己的胜者。
     */
    PersistenceRecords.CheckpointRow selectCheckpointSource(PersistenceRecords.CheckpointKey values);

    /**
     * 统计同一 source revision 的 checkpoint，用于验证幂等唯一性。
     */
    int countCheckpointSource(PersistenceRecords.CheckpointKey values);

    /**
     * 统计 Thread checkpoint 总数，仅供事务测试与存储诊断。
     */
    int countCheckpoints(@Param("threadId") String threadId);

    /**
     * 追加不可变 checkpoint，唯一约束承担并发写入裁决。
     */
    int insertCheckpoint(PersistenceRecords.CheckpointInsert values);
}
