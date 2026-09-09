// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.apache.ibatis.session.SqlSession;

/**
 * 一个 transaction-scoped Mapper 集合；任何 Mapper 都不得逃逸 SqlSession callback。
 */
public record PersistenceMappers(HistoryMapper history, AgentMapper agent, CheckpointMapper checkpoint,
                                 RecoveryMapper recovery, SchemaMapper schema,
                                 InstructionScopeMapper instructionScopes, AttachmentMapper attachments,
                                 TaskMapper tasks) {
    /**
     * 从同一 SqlSession 创建所有职责 Mapper，保证跨表事实仍共享一个事务。
     */
    public static PersistenceMappers open(SqlSession session) {
        return new PersistenceMappers(session.getMapper(HistoryMapper.class),
                session.getMapper(AgentMapper.class), session.getMapper(CheckpointMapper.class),
                session.getMapper(RecoveryMapper.class),
                session.getMapper(SchemaMapper.class),
                session.getMapper(InstructionScopeMapper.class),
                session.getMapper(AttachmentMapper.class),
                session.getMapper(TaskMapper.class));
    }
}
