// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 只用于 schema 验证，不承担应用 CRUD。
 */
@Mapper
public interface SchemaMapper {
    /**
     * 读取 SQLite 用户表集合，用于确认新 V1 基线完整落地。
     */
    List<String> selectUserTables();

    /**
     * 执行 TRUNCATE WAL checkpoint，关闭前必须据返回状态判断是否可释放 lease。
     */
    PersistenceRecords.WalCheckpointRow checkpointWal();
}
