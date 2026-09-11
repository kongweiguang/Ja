// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 主 Thread、side chat 与 subagent 的统一只读发现 SQL 边界。 */
@Mapper
public interface ThreadDiscoveryMapper {
    /**
     * 在数据库内合并三类 Thread，并以更新时间与身份执行有界 keyset 分页。
     */
    List<PersistenceRecords.ThreadDiscoveryRow> selectPage(PersistenceRecords.ThreadDiscoveryPage values);
}
