// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.task.SideChatPurger;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 SQLite 验证临时侧聊清理的 Goal/Plan 依赖闭包、外发 Mailbox 保留与事务回滚。 */
final class SideChatPurgeGoalPlanTest extends PersistenceTestSupport {
    private static final String AT = "2026-09-10T00:00:00Z";
    private static final String HASH = "b".repeat(64);
    private static final String SIDE_ATTACHMENT_SHA = "f".repeat(64);
    private static final String MAIN_ATTACHMENT_SHA = "6".repeat(64);
    private static final String SHARED_ATTACHMENT_SHA = "7".repeat(64);

    /** 物理 purge 必须删除 Goal/Plan 全图，同时保留发送方已删除后的外发 Mailbox。 */
    @Test
    void purgesGoalPlanGraphAndPreservesOutboundMailbox() throws Exception {
        try (TestDatabase database = database("side-chat-purge-goal-plan")) {
            seedGraph(database.sessions());
            runPurge(database.sessions(), false);

            assertEquals(0, count(database.sessions(), "threads", "thread_id='thr_side'"));
            assertEquals(0, count(database.sessions(), "temporary_side_chats", "thread_id='thr_side'"));
            assertEquals(0, count(database.sessions(), "goals", "goal_id='goal_side'"));
            assertEquals(0, count(database.sessions(), "plans", "plan_id='plan_side'"));
            assertEquals(0, count(database.sessions(), "plan_revisions", "plan_revision_id='pr_side'"));
            assertEquals(0, count(database.sessions(), "execution_runs", "run_id IN ('run_goal','run_plan')"));
            assertEquals(0, count(database.sessions(), "interaction_requests", "request_id='interaction_side'"));
            assertEquals(0, count(database.sessions(), "messages", "thread_id='thr_side'"));
            assertEquals(0, count(database.sessions(), "messages", "thread_id='thr_agent'"));
            assertEquals(0, count(database.sessions(), "context_checkpoints", "thread_id='thr_side'"));
            assertEquals(0, count(database.sessions(), "usage", "thread_id='thr_side'"));
            assertEquals(0, count(database.sessions(), "tool_bindings", "turn_id='turn_side'"));
            assertEquals(0, count(database.sessions(), "pending_inputs", "thread_id='thr_side'"));
            assertEquals(0, count(database.sessions(), "message_attachments", "message_id='msg_side'"));
            assertEquals(0, count(database.sessions(), "pending_input_attachments", "input_id='input_side'"));
            assertEquals("DISCARDED", rawAttachment(database, "att_side").status());
            assertNull(rawAttachment(database, "att_side").blobSha256());
            String discardedAt = discardedAt(database, "att_side");
            assertDoesNotThrow(() -> Instant.parse(discardedAt));
            assertEquals("BOUND", rawAttachment(database, "att_main").status());
            assertEquals(MAIN_ATTACHMENT_SHA, rawAttachment(database, "att_main").blobSha256());
            assertEquals("BOUND", rawAttachment(database, "att_shared").status());
            assertEquals(SHARED_ATTACHMENT_SHA, rawAttachment(database, "att_shared").blobSha256());
            assertEquals(1, count(database.sessions(), "task_context_seeds", "context_seed_id='seed_shared'"));
            assertEquals(0, count(database.sessions(), "turns", "thread_id IN ('thr_side','thr_agent')"));
            assertEquals(0, count(database.sessions(), "threads", "thread_id='thr_agent'"));
            assertEquals(0, count(database.sessions(), "task_mailbox", "target_thread_id='thr_side'"));
            assertEquals(1, count(database.sessions(), "task_mailbox", "message_id='msg_outbound'"));

            var attachmentRepository = database.attachments();
            var gcCandidates = attachmentRepository.findUnreferencedBlobs(16);
            assertTrue(gcCandidates.contains(SIDE_ATTACHMENT_SHA));
            assertFalse(gcCandidates.contains(MAIN_ATTACHMENT_SHA));
            assertFalse(gcCandidates.contains(SHARED_ATTACHMENT_SHA));
            assertTrue(attachmentRepository.deleteUnreferencedBlob(SIDE_ATTACHMENT_SHA));
            assertEquals(0, count(database.sessions(), "attachment_blobs", "sha256='" + SIDE_ATTACHMENT_SHA + "'"));
            assertEquals(1, count(database.sessions(), "attachment_blobs", "sha256='" + MAIN_ATTACHMENT_SHA + "'"));
            assertEquals(1, count(database.sessions(), "attachment_blobs", "sha256='" + SHARED_ATTACHMENT_SHA + "'"));
        }
    }

    /** purge 后事务即使在提交前失败，也必须恢复 marker、immutable facts 和全部 owner 图。 */
    @Test
    void rollsBackGoalPlanPurgeAsOneTransaction() throws Exception {
        try (TestDatabase database = database("side-chat-purge-goal-plan-rollback")) {
            seedGraph(database.sessions());
            runPurge(database.sessions(), true);

            assertEquals(1, count(database.sessions(), "threads", "thread_id='thr_side'"));
            assertEquals(1, count(database.sessions(), "threads", "thread_id='thr_agent'"));
            assertEquals(1, count(database.sessions(), "temporary_side_chats", "thread_id='thr_side' AND state='CLOSING'"));
            assertEquals(2, count(database.sessions(), "thread_lineage", "child_thread_id IN ('thr_side','thr_agent')"));
            assertEquals(2, count(database.sessions(), "task_context_seeds", "context_seed_id IN ('seed_side','seed_agent')"));
            assertEquals(1, count(database.sessions(), "goals", "goal_id='goal_side'"));
            assertEquals(1, count(database.sessions(), "plans", "plan_id='plan_side'"));
            assertEquals(1, count(database.sessions(), "messages", "thread_id='thr_side'"));
            assertEquals(1, count(database.sessions(), "messages", "thread_id='thr_agent'"));
            assertEquals(1, count(database.sessions(), "turns", "turn_id='turn_side'"));
            assertEquals(1, count(database.sessions(), "turns", "turn_id='turn_agent'"));
            assertEquals("BOUND", rawAttachment(database, "att_side").status());
            assertEquals(SIDE_ATTACHMENT_SHA, rawAttachment(database, "att_side").blobSha256());
            assertEquals(1, count(database.sessions(), "message_attachments", "attachment_id='att_side'"));
            assertEquals(1, count(database.sessions(), "message_attachments", "attachment_id='att_main'"));
            assertEquals(1, count(database.sessions(), "message_attachments", "attachment_id='att_shared'"));
            assertEquals(1, count(database.sessions(), "context_checkpoints", "thread_id='thr_side'"));
            assertEquals(1, count(database.sessions(), "usage", "thread_id='thr_side'"));
            assertEquals(1, count(database.sessions(), "tool_bindings", "turn_id='turn_side'"));
            assertEquals(1, count(database.sessions(), "pending_inputs", "thread_id='thr_side'"));
            assertEquals(1, count(database.sessions(), "task_mailbox", "message_id='msg_inbound'"));
            assertEquals(1, count(database.sessions(), "task_mailbox", "message_id='msg_outbound'"));
        }
    }

    /** 在同一 writer transaction 中构造完整临时侧聊事实图，并区分独有、主线程与继承附件。 */
    private static void seedGraph(SqlSessionFactory sessions) throws Exception {
        try (SqlSession session = sessions.openSession(); Statement sql = session.getConnection().createStatement()) {
            sql.executeUpdate("INSERT INTO workspaces(workspace_id,root_path,display_name,trust,created_at,updated_at) VALUES "
                    + "('ws_root','C:/tmp/ja-root','Root','TRUSTED','" + AT + "','" + AT + "'),"
                    + "('ws_external','C:/tmp/ja-external','External','TRUSTED','" + AT + "','" + AT + "')");
            sql.executeUpdate("INSERT INTO threads(thread_id,workspace_id,title,created_at,updated_at,provider_id,model_id,"
                    + "access_mode,title_source,collaboration_mode) VALUES "
                    + "('thr_root','ws_root','Root','" + AT + "','" + AT + "','provider','model','APPROVAL_REQUIRED','MANUAL','DEFAULT'),"
                    + "('thr_side','ws_root','Side','" + AT + "','" + AT + "','provider','model','APPROVAL_REQUIRED','MANUAL','PLAN'),"
                    + "('thr_external','ws_external','External','" + AT + "','" + AT + "','provider','model','APPROVAL_REQUIRED','MANUAL','DEFAULT')");
            sql.executeUpdate("INSERT INTO task_context_seeds(context_seed_id,parent_thread_id,parent_revision,inheritance_mode,"
                    + "task_brief_json,effective_context_json,references_json,permission_ceiling_json,fingerprint,created_at) VALUES "
                    + "('seed_side','thr_root',0,'EFFECTIVE_CONTEXT',NULL,'{}','[]','{}','" + "a".repeat(64) + "','" + AT + "')");
            sql.executeUpdate("INSERT INTO task_context_seeds(context_seed_id,parent_thread_id,parent_revision,inheritance_mode,"
                    + "task_brief_json,effective_context_json,references_json,permission_ceiling_json,fingerprint,created_at) VALUES "
                    + "('seed_shared','thr_root',0,'EFFECTIVE_CONTEXT',NULL,'{}',"
                    + "'[{\"kind\":\"attachment\",\"attachmentId\":\"att_shared\"}]','{}','"
                    + "5".repeat(64) + "','" + AT + "')");
            sql.executeUpdate("INSERT INTO thread_lineage(child_thread_id,parent_thread_id,root_thread_id,task_name,depth,"
                    + "task_kind,lifecycle,context_seed_id,created_at) VALUES "
                    + "('thr_side','thr_root','thr_root','side','1','SIDE_TASK','INDEPENDENT','seed_side','" + AT + "')");
            sql.executeUpdate("INSERT INTO threads(thread_id,workspace_id,title,created_at,updated_at,provider_id,model_id,"
                    + "access_mode,title_source,collaboration_mode) VALUES "
                    + "('thr_agent','ws_root','Agent','" + AT + "','" + AT + "','provider','model','APPROVAL_REQUIRED','MANUAL','DEFAULT')");
            sql.executeUpdate("INSERT INTO turns(turn_id,thread_id,state,requested_at,updated_at,completed_at,terminal_summary) VALUES "
                    + "('turn_side','thr_side','COMPLETED','" + AT + "','" + AT + "','" + AT + "','done')");
            sql.executeUpdate("INSERT INTO turns(turn_id,thread_id,state,requested_at,updated_at,completed_at,terminal_summary) VALUES "
                    + "('turn_root','thr_root','COMPLETED','" + AT + "','" + AT + "','" + AT + "','done')");
            sql.executeUpdate("INSERT INTO turns(turn_id,thread_id,state,requested_at,updated_at,completed_at,terminal_summary,parent_turn_id,root_turn_id) VALUES "
                    + "('turn_agent','thr_agent','COMPLETED','" + AT + "','" + AT + "','" + AT + "','done','turn_side','turn_side')");
            sql.executeUpdate("INSERT INTO task_context_seeds(context_seed_id,parent_thread_id,parent_turn_id,parent_revision,inheritance_mode,"
                    + "task_brief_json,effective_context_json,references_json,permission_ceiling_json,fingerprint,created_at) VALUES "
                    + "('seed_agent','thr_side','turn_side',0,'BRIEF_ONLY','[]',NULL,'[]','{}','" + "e".repeat(64) + "','" + AT + "')");
            sql.executeUpdate("INSERT INTO thread_lineage(child_thread_id,parent_thread_id,root_thread_id,origin_turn_id,task_name,depth,"
                    + "task_kind,lifecycle,context_seed_id,created_at) VALUES "
                    + "('thr_agent','thr_side','thr_root','turn_side','agent','2','SUBAGENT','ATTACHED','seed_agent','" + AT + "')");
            sql.executeUpdate("INSERT INTO temporary_side_chats(thread_id,state) VALUES('thr_side','CLOSING')");
            sql.executeUpdate("INSERT INTO messages(message_id,thread_id,turn_id,ordinal,role,blocks_json,created_at) VALUES "
                    + "('msg_side','thr_side','turn_side',1,'USER','[{\"type\":\"text\",\"text\":\"side\"}]','" + AT + "')");
            sql.executeUpdate("INSERT INTO messages(message_id,thread_id,turn_id,ordinal,role,blocks_json,created_at) VALUES "
                    + "('msg_agent','thr_agent','turn_agent',1,'ASSISTANT','[{\"type\":\"text\",\"text\":\"agent\"}]','" + AT + "')");
            sql.executeUpdate("INSERT INTO messages(message_id,thread_id,turn_id,ordinal,role,blocks_json,created_at) VALUES "
                    + "('msg_root','thr_root','turn_root',1,'USER','[{\"type\":\"text\",\"text\":\"root\"}]','" + AT + "')");
            sql.executeUpdate("INSERT INTO attachment_blobs(sha256,size_bytes,media_kind,media_type,created_at) VALUES "
                    + "('" + SIDE_ATTACHMENT_SHA + "',3,'TEXT','text/plain','" + AT + "'),"
                    + "('" + MAIN_ATTACHMENT_SHA + "',4,'TEXT','text/plain','" + AT + "'),"
                    + "('" + SHARED_ATTACHMENT_SHA + "',5,'TEXT','text/plain','" + AT + "')");
            sql.executeUpdate("INSERT INTO attachments(attachment_id,workspace_id,blob_sha256,content_sha256,display_name,size_bytes,"
                    + "media_kind,media_type,status,created_at,expires_at,bound_at) VALUES "
                    + "('att_side','ws_root','" + SIDE_ATTACHMENT_SHA + "','" + SIDE_ATTACHMENT_SHA + "','side.txt',3,'TEXT','text/plain','BOUND','" + AT + "','" + AT + "','" + AT + "'),"
                    + "('att_main','ws_root','" + MAIN_ATTACHMENT_SHA + "','" + MAIN_ATTACHMENT_SHA + "','main.txt',4,'TEXT','text/plain','BOUND','" + AT + "','" + AT + "','" + AT + "'),"
                    + "('att_shared','ws_root','" + SHARED_ATTACHMENT_SHA + "','" + SHARED_ATTACHMENT_SHA + "','shared.txt',5,'TEXT','text/plain','BOUND','" + AT + "','" + AT + "','" + AT + "')");
            sql.executeUpdate("INSERT INTO message_attachments(message_id,attachment_id,ordinal,created_at) VALUES "
                    + "('msg_side','att_side',0,'" + AT + "')");
            sql.executeUpdate("INSERT INTO message_attachments(message_id,attachment_id,ordinal,created_at) VALUES "
                    + "('msg_side','att_shared',1,'" + AT + "')");
            sql.executeUpdate("INSERT INTO message_attachments(message_id,attachment_id,ordinal,created_at) VALUES "
                    + "('msg_root','att_main',0,'" + AT + "')");
            sql.executeUpdate("INSERT INTO pending_inputs(input_id,thread_id,turn_id,kind,content_json,state,priority_sequence,created_at,updated_at) VALUES "
                    + "('input_side','thr_side','turn_side','STEERING','[{\"type\":\"text\",\"text\":\"pending\"}]','PENDING',1,'" + AT + "','" + AT + "')");
            sql.executeUpdate("INSERT INTO pending_input_attachments(input_id,attachment_id,ordinal,created_at) VALUES "
                    + "('input_side','att_side',0,'" + AT + "')");
            sql.executeUpdate("INSERT INTO context_checkpoints(checkpoint_id,thread_id,source_revision,through_ordinal,retained_from_ordinal,"
                    + "summary_json,input_tokens,envelope_fingerprint,strategy_version,usage_json,created_at) VALUES "
                    + "('checkpoint_side','thr_side',0,0,0,'{}',0,'" + "1".repeat(64) + "','ja-context-v1','{}','" + AT + "')");
            sql.executeUpdate("INSERT INTO usage(usage_id,request_id,thread_id,turn_id,model_round,request_ordinal,purpose,certainty,"
                    + "profile_json,created_at) VALUES "
                    + "('usage_side','usage-request-side','thr_side','turn_side',1,1,'ASSISTANT','UNKNOWN','{}','" + AT + "')");
            sql.executeUpdate("INSERT INTO tools(call_id,thread_id,turn_id,ordinal,tool_name,side_effect,presentation_json,state,revision,created_at,updated_at) VALUES "
                    + "('call_side','thr_side','turn_side',0,'shell','READ_ONLY','{}','PREPARED',0,'" + AT + "','" + AT + "')");
            sql.executeUpdate("INSERT INTO tool_bindings(turn_id,batch_id,call_id,route_kind,local_name,server_id,remote_name,"
                    + "schema_hash,route_hash,catalog_revision,access_mode,created_at) VALUES "
                    + "('turn_side','batch_side','call_side','BUILTIN','shell','builtin','shell','" + "2".repeat(64) + "','" + "3".repeat(64) + "','" + "4".repeat(64) + "','APPROVAL_REQUIRED','" + AT + "')");
            sql.executeUpdate("INSERT INTO task_activities(activity_id,root_thread_id,task_thread_id,actor_thread_id,kind,summary_json,created_at) VALUES "
                    + "('activity_side','thr_root','thr_side','thr_side','CREATED','{}','" + AT + "')");
            sql.executeUpdate("INSERT INTO task_projections(task_thread_id,root_thread_id,state,latest_activity_sequence,"
                    + "unread_count,descendant_count,running_descendant_count,needs_attention_count,latest_safe_summary,completed_at,updated_at) VALUES "
                    + "('thr_side','thr_root','COMPLETED',(SELECT activity_sequence FROM task_activities WHERE activity_id='activity_side'),"
                    + "0,0,0,0,'done','" + AT + "','" + AT + "')");
            sql.executeUpdate("INSERT INTO task_activities(activity_id,root_thread_id,task_thread_id,actor_thread_id,causal_turn_id,kind,summary_json,created_at) VALUES "
                    + "('activity_agent','thr_root','thr_agent','thr_agent','turn_side','CREATED','{}','" + AT + "')");
            sql.executeUpdate("INSERT INTO task_projections(task_thread_id,root_thread_id,state,latest_activity_sequence,"
                    + "unread_count,descendant_count,running_descendant_count,needs_attention_count,latest_safe_summary,completed_at,updated_at) VALUES "
                    + "('thr_agent','thr_root','COMPLETED',(SELECT activity_sequence FROM task_activities WHERE activity_id='activity_agent'),"
                    + "0,0,0,0,'done','" + AT + "','" + AT + "')");
            insertMailbox(sql, "msg_inbound", "thr_external", "External", "thr_side", "inbound");
            insertMailbox(sql, "msg_outbound", "thr_side", "Side", "thr_external", "outbound");

            insertGoalPlanGraph(sql);
            insertInteractionGraph(sql);
            session.commit();
        }
    }

    /** 构造独立 Goal、Plan、revision、Run、link、approval、evaluation 与 step facts。 */
    private static void insertGoalPlanGraph(Statement sql) throws Exception {
        sql.executeUpdate("INSERT INTO goals(goal_id,owner_thread_id,owner_kind,objective,goal_definition_revision,"
                + "create_idempotency_key,status,phase,revision,active_run_id,created_at,updated_at) VALUES "
                + "('goal_side','thr_side','INDEPENDENT_TASK','Goal','1','goal-create-key','ACTIVE','WORKING','0','run_goal','" + AT + "','" + AT + "')");
        sql.executeUpdate("INSERT INTO goal_definition_revisions(goal_id,revision_number,objective,created_at) VALUES "
                + "('goal_side',1,'Goal','" + AT + "')");
        sql.executeUpdate("INSERT INTO goal_acceptance_criteria(goal_id,goal_definition_revision,criterion_id,ordinal,description,required) VALUES "
                + "('goal_side',1,'criterion_goal',0,'Goal criterion',1)");
        sql.executeUpdate("INSERT INTO execution_runs(run_id,goal_id,goal_definition_revision,status,process_generation,started_at,created_at,updated_at) VALUES "
                + "('run_goal','goal_side',1,'RUNNING',1,'" + AT + "','" + AT + "','" + AT + "')");

        sql.executeUpdate("INSERT INTO plans(plan_id,owner_thread_id,objective,create_idempotency_key,status,revision,created_at,updated_at) VALUES "
                + "('plan_side','thr_side','Plan','plan-create-key','DRAFT',0,'" + AT + "','" + AT + "')");
        sql.executeUpdate("INSERT INTO plan_drafts(plan_draft_id,plan_id,draft_revision,definition_json,updated_at) VALUES "
                + "('draft_side','plan_side',0,'{}','" + AT + "')");
        sql.executeUpdate("INSERT INTO plan_revisions(plan_revision_id,plan_id,revision_number,definition_json,plan_hash,created_by,created_at) VALUES "
                + "('pr_side','plan_side',1,'{}','" + HASH + "','AGENT','" + AT + "')");
        sql.executeUpdate("INSERT INTO plan_steps(plan_revision_id,step_id,ordinal,title,description,required,dependency_ids_json) VALUES "
                + "('pr_side','step_side',0,'Step','Do it',1,'[]')");
        sql.executeUpdate("INSERT INTO acceptance_criteria(plan_revision_id,criterion_id,ordinal,description,required) VALUES "
                + "('pr_side','criterion_plan',0,'Plan criterion',1)");
        sql.executeUpdate("INSERT INTO plan_approvals(approval_id,plan_id,plan_revision_id,plan_hash,actor,decision,created_at) VALUES "
                + "('approval_side','plan_side','pr_side','" + HASH + "','USER_UI','APPROVED','" + AT + "')");
        sql.executeUpdate("INSERT INTO execution_runs(run_id,plan_id,plan_revision_id,plan_hash,status,process_generation,started_at,created_at,updated_at) VALUES "
                + "('run_plan','plan_side','pr_side','" + HASH + "','RUNNING',1,'" + AT + "','" + AT + "','" + AT + "')");
        sql.executeUpdate("UPDATE plans SET status='EXECUTING',revision=1,active_plan_revision_id='pr_side',active_run_id='run_plan',updated_at='" + AT + "' WHERE plan_id='plan_side'");
        sql.executeUpdate("INSERT INTO goal_plan_links(goal_id,link_revision,plan_id,plan_revision_id,plan_hash,attached_at) VALUES "
                + "('goal_side',1,'plan_side','pr_side','" + HASH + "','" + AT + "')");
        sql.executeUpdate("INSERT INTO goal_events(event_id,goal_id,goal_revision,kind,payload_json,idempotency_key,created_at) VALUES "
                + "('goal-event-side','goal_side',0,'CHANGED','{}','goal-event-key','" + AT + "')");
        sql.executeUpdate("INSERT INTO plan_events(event_id,plan_id,plan_revision,activity,idempotency_key,created_at) VALUES "
                + "('plan-event-side','plan_side',1,'created','plan-event-key','" + AT + "')");
        sql.executeUpdate("INSERT INTO plan_step_executions(run_id,plan_revision_id,step_id,status,updated_at) VALUES "
                + "('run_plan','pr_side','step_side','PENDING','" + AT + "')");
        sql.executeUpdate("INSERT INTO acceptance_evidence(evidence_id,plan_id,run_id,plan_revision_id,criterion_id,step_id,source_type,source_id,summary,digest,observed_at,created_at) VALUES "
                + "('evidence_side','plan_side','run_plan','pr_side','criterion_plan','step_side','TEST_REPORT','test-side','ok','" + "c".repeat(64) + "','" + AT + "','" + AT + "')");
        sql.executeUpdate("INSERT INTO goal_evaluations(evaluation_id,goal_id,goal_definition_revision,run_id,status,process_generation,model_id,provider_id,requested_at) VALUES "
                + "('evaluation_side','goal_side',1,'run_goal','REQUESTED',1,'model','provider','" + AT + "')");
        sql.executeUpdate("INSERT INTO plan_evaluation_requests(request_id,plan_id,plan_revision_id,run_id,owner_thread_id,input_digest,profile_json,outcome,certainty,started_at) VALUES "
                + "('plan-eval-side','plan_side','pr_side','run_plan','thr_side','" + "d".repeat(64) + "','{}','RUNNING','UNKNOWN','" + AT + "')");
    }

    /** 构造引用 Plan Run 的 Interaction request/draft/event，覆盖完整 request owner 子查询。 */
    private static void insertInteractionGraph(Statement sql) throws Exception {
        sql.executeUpdate("INSERT INTO interaction_requests(request_id,thread_id,turn_id,tool_call_id,plan_revision_id,run_id,"
                + "idempotency_key,questions_json,answers_json,status,revision,created_at,updated_at) VALUES "
                + "('interaction_side','thr_side','turn_side','call_side','pr_side','run_plan','interaction-key',"
                + "'[{\"questionId\":\"q\"}]','[]','PENDING',0,'" + AT + "','" + AT + "')");
        sql.executeUpdate("INSERT INTO interaction_drafts(request_id,thread_id,answers_json,page,collapsed,idempotency_key,revision,updated_at) VALUES "
                + "('interaction_side','thr_side','[]',0,0,'draft-key',0,'" + AT + "')");
        sql.executeUpdate("INSERT INTO interaction_events(thread_id,request_id,request_revision,kind,occurred_at) VALUES "
                + "('thr_side','interaction_side',0,'CREATED','" + AT + "')");
    }

    /** 插入一条无 sender 外键的 mailbox，分别验证入站删除和外发快照保留。 */
    private static void insertMailbox(Statement sql, String messageId, String senderId, String senderTitle,
                                      String targetId, String text) throws Exception {
        sql.executeUpdate("INSERT INTO task_mailbox(message_id,root_thread_id,sender_thread_id,sender_title,target_thread_id,"
                + "kind,content_json,idempotency_key,state,created_at,updated_at) VALUES "
                + "('" + messageId + "','thr_root','" + senderId + "','" + senderTitle + "','" + targetId + "',"
                + "'MESSAGE','[{\"type\":\"text\",\"text\":\"" + text + "\"}]','key-" + messageId + "','PENDING','" + AT + "','" + AT + "')");
    }

    /** 执行 purge；rollbackProbe 用于证明所有 SQL 仍处于调用方单一事务中。 */
    private static void runPurge(SqlSessionFactory sessions, boolean rollbackProbe) throws Exception {
        try (SqlSession session = sessions.openSession()) {
            try {
                int deleted = SideChatPurger.purge(PersistenceMappers.open(session), "thr_side", true);
                assertEquals(2, deleted);
                if (rollbackProbe) throw new RollbackProbe();
                session.commit();
            } catch (RollbackProbe expected) {
                session.rollback();
            } catch (Throwable failure) {
                session.rollback();
                throw failure;
            }
        }
    }

    /** 通过 JDBC 回读精确事实数量，避免把仓储 projection 当作 purge 成功证据。 */
    private static int count(SqlSessionFactory sessions, String table, String predicate) throws Exception {
        try (SqlSession session = sessions.openSession(); Statement sql = session.getConnection().createStatement();
             ResultSet rows = sql.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + predicate)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    /** 绕过 Thread 可见性过滤读取附件状态，验证 purge 只释放本次冻结关系图。 */
    private static io.github.kongweiguang.ja.infrastructure.persistence.mapper.AttachmentRecords.AttachmentRow
            rawAttachment(TestDatabase database, String attachmentId) {
        try (SqlSession session = database.sessions().openSession()) {
            return PersistenceMappers.open(session).attachments().selectAttachment(attachmentId);
        }
    }

    /** 直接回读生命周期审计列，确保 purge 不写入 SQLite 方言的非 ISO 时间文本。 */
    private static String discardedAt(TestDatabase database, String attachmentId) throws Exception {
        try (SqlSession session = database.sessions().openSession();
             Statement sql = session.getConnection().createStatement();
             ResultSet rows = sql.executeQuery("SELECT discarded_at FROM attachments WHERE attachment_id='"
                     + attachmentId + "'")) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    /** 事务测试专用异常，不携带业务语义，确保 rollback 由调用方显式触发。 */
    private static final class RollbackProbe extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
