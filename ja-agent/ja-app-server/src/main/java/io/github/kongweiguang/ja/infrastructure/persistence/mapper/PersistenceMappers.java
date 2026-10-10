// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.apache.ibatis.session.SqlSession;

/**
 * 一个 transaction-scoped Mapper 集合；任何 Mapper 都不得逃逸 SqlSession callback。
 *
 * @param history 对话历史的数据库映射器
 * @param agent Agent 执行数据的数据库映射器
 * @param schema 数据库结构的数据库映射器
 * @param instructionScopes 指令作用域的数据库映射器
 * @param attachments 附件的数据库映射器
 * @param tasks 任务的数据库映射器
 * @param subagentPolicies 子任务策略的数据库映射器
 * @param interactions 交互的数据库映射器
 * @param planEvaluations Plan 验收评估的数据库映射器
 * @param threadDiscoveries 对话发现的数据库映射器
 * @param sideChats 临时侧聊的数据库映射器
 * @param sideChatPurges 临时侧聊清理的数据库映射器
 * @param threadForks 持久会话分叉的数据库映射器
 * @param toolCallPreviews 工具调用预览的数据库映射器
 */
public record PersistenceMappers(HistoryMapper history,
                                 AgentMapper agent,
                                 SchemaMapper schema,
                                 InstructionScopeMapper instructionScopes,
                                 AttachmentMapper attachments,
                                 TaskMapper tasks,
                                 SubagentPolicyMapper subagentPolicies,
                                 InteractionMapper interactions,
                                 PlanEvaluationMapper planEvaluations,
                                 ThreadDiscoveryMapper threadDiscoveries,
                                 SideChatMapper sideChats,
                                 SideChatPurgeMapper sideChatPurges,
                                 ThreadForkMapper threadForks,
                                 ToolCallPreviewMapper toolCallPreviews) {
    /**
     * 从同一 SqlSession 创建所有职责 Mapper，保证跨表事实仍共享一个事务。
     */
    public static PersistenceMappers open(SqlSession session) {
        return new PersistenceMappers(session.getMapper(HistoryMapper.class),
                session.getMapper(AgentMapper.class), session.getMapper(SchemaMapper.class),
                session.getMapper(InstructionScopeMapper.class),
                session.getMapper(AttachmentMapper.class),
                session.getMapper(TaskMapper.class),
                session.getMapper(SubagentPolicyMapper.class),
                session.getMapper(InteractionMapper.class),
                session.getMapper(PlanEvaluationMapper.class),
                session.getMapper(ThreadDiscoveryMapper.class), session.getMapper(SideChatMapper.class),
                session.getMapper(SideChatPurgeMapper.class), session.getMapper(ThreadForkMapper.class),
                session.getMapper(ToolCallPreviewMapper.class));
    }
}
