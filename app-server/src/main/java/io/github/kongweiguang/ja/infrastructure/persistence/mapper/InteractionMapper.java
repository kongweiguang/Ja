// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Interaction 请求、草稿和事件的事务 Mapper；SQL 由主控在下一迁移中装配。 */
@Mapper
public interface InteractionMapper {
    /** 按 Thread/request 身份读取交互行，所有更新前先由应用层完成资源归属校验。 */
    PersistenceRecords.InteractionRow selectInteraction(PersistenceRecords.InteractionKey key);
    /** 读取 Thread 唯一活动请求，避免多批问题同时抢占用户注意力。 */
    PersistenceRecords.InteractionRow selectActiveInteraction(@Param("threadId") String threadId);
    /** 与 Interaction 查询同事务读取关联 Turn 状态，避免 Handler 通过分页快照猜测恢复资格。 */
    /** 查询关联 Turn 的权威状态，供恢复快照在同一事务内计算 resumeState。 */
    String selectTurnState(@Param("turnId") String turnId);
    /** 原子插入待回答问题，唯一 requestId 防止重复创建。 */
    int insertInteraction(PersistenceRecords.InteractionInsert values);
    /** 以 request revision CAS 写入完整答案。 */
    int answerInteraction(PersistenceRecords.InteractionAnswerCas values);
    /** 以 request revision CAS 关闭取消或替代状态。 */
    int closeInteraction(PersistenceRecords.InteractionCloseCas values);
    /** 追加已提交事件，序列由数据库分配。 */
    int insertEvent(PersistenceRecords.InteractionEventInsert values);
    /** 读取可恢复草稿，草稿与请求生命周期分离保存。 */
    PersistenceRecords.InteractionDraftRow selectDraft(PersistenceRecords.InteractionKey key);
    /** 以草稿 revision CAS 保存编辑内容。 */
    int saveDraft(PersistenceRecords.InteractionDraftCas values);
    /** 读取 Thread 当前事件序列，供观察注册后的对账使用。 */
    Long selectCurrentEventSequence(@Param("threadId") String threadId);
    /** 读取指定序列之后的事件增量。 */
    List<PersistenceRecords.InteractionEventRow> selectEvents(@Param("threadId") String threadId,
                                                              @Param("afterSequence") long afterSequence);
}
