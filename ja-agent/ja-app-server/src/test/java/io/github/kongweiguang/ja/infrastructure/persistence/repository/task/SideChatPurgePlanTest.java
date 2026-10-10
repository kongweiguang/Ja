// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.AssistantMessage;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SideChatPurgeMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TaskRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisAttachmentRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 SQLite 验证临时侧聊 Plan 依赖闭包、外发 Mailbox 保留与事务回滚。 */
final class SideChatPurgePlanTest extends PersistenceTestSupport {
    private static final long AT = Instant.parse("2026-09-10T00:00:00Z").toEpochMilli();
    private static final String HASH = "b".repeat(64);
    private static final String SIDE_ATTACHMENT_SHA = "f".repeat(64);
    private static final String MAIN_ATTACHMENT_SHA = "6".repeat(64);
    private static final String SHARED_ATTACHMENT_SHA = "7".repeat(64);

    /** 物理 purge 必须删除 Plan 全图，同时保留发送方已删除后的外发 Mailbox。 */
    @Test
    void purgesPlanGraphAndPreservesOutboundMailbox() throws Exception {
        try (TestDatabase database = database("side-chat-purge-plan")) {
            seedGraph(database);
            runPurge(database.sessions(), false);

            assertEquals(1, count(database.sessions(), "ja_task_side_chat_close_receipt",
                    "root_thread_id='thr_side' AND owner_root_thread_id='thr_root'"));
            assertEquals(2, count(database.sessions(), "ja_task_side_chat_close_member",
                    "root_thread_id='thr_side'"));
            assertEquals(0, count(database.sessions(), "ja_conversation_thread", "thread_id='thr_side'"));
            assertEquals(0, count(database.sessions(), "ja_conversation_thread",
                    "thread_id='thr_side' AND temporary_lifecycle_state IS NOT NULL"));
            assertEquals(0, count(database.sessions(), "ja_plan", "plan_id='plan_side'"));
            assertEquals(0, count(database.sessions(), "ja_plan_revision", "plan_revision_id='pr_side'"));
            assertEquals(0, count(database.sessions(), "ja_plan_execution", "run_id='run_plan'"));
            assertEquals(0, count(database.sessions(), "ja_interaction_request", "interaction_id='interaction_side'"));
            assertEquals(0, count(database.sessions(), "ja_conversation_message", "thread_id='thr_side'"));
            assertEquals(0, count(database.sessions(), "ja_conversation_message", "thread_id='thr_agent'"));
            assertEquals(0, count(database.sessions(), "ja_context_checkpoint", "thread_id='thr_side'"));
            assertEquals(0, count(database.sessions(), "ja_turn_usage", "thread_id='thr_side'"));
            assertEquals(0, count(database.sessions(), "ja_tool_binding", "turn_id='turn_side'"));
            assertEquals(0, count(database.sessions(), "ja_turn_input", "thread_id='thr_side'"));
            assertEquals(0, count(database.sessions(), "ja_message_attachment", "message_id='msg_side'"));
            assertEquals(0, count(database.sessions(), "ja_turn_input_attachment", "input_id='input_side'"));
            assertEquals("DISCARDED", rawAttachment(database, "att_side").status());
            assertNull(rawAttachment(database, "att_side").blobSha256());
            String discardedAt = discardedAt(database, "att_side");
            assertTrue(Long.parseLong(discardedAt) >= AT);
            assertEquals("BOUND", rawAttachment(database, "att_main").status());
            assertEquals(MAIN_ATTACHMENT_SHA, rawAttachment(database, "att_main").blobSha256());
            assertEquals("BOUND", rawAttachment(database, "att_shared").status());
            assertEquals(SHARED_ATTACHMENT_SHA, rawAttachment(database, "att_shared").blobSha256());
            assertEquals(1, count(database.sessions(), "ja_task_context_seed", "context_seed_id='seed_shared'"));
            assertEquals(0, count(database.sessions(), "ja_conversation_turn", "thread_id IN ('thr_side','thr_agent')"));
            assertEquals(0, count(database.sessions(), "ja_conversation_thread", "thread_id='thr_agent'"));
            assertEquals(0, count(database.sessions(), "ja_task_mailbox_message", "to_thread_id='thr_side'"));
            assertEquals(1, count(database.sessions(), "ja_task_mailbox_message", "message_id='msg_outbound'"));

            MybatisAttachmentRepository attachmentRepository = database.attachments();
            List<String> gcCandidates = attachmentRepository.findUnreferencedBlobs(16);
            assertTrue(gcCandidates.contains(SIDE_ATTACHMENT_SHA));
            assertFalse(gcCandidates.contains(MAIN_ATTACHMENT_SHA));
            assertFalse(gcCandidates.contains(SHARED_ATTACHMENT_SHA));
            assertTrue(attachmentRepository.deleteUnreferencedBlob(SIDE_ATTACHMENT_SHA));
            assertEquals(0, count(database.sessions(), "ja_attachment_blob", "sha256='" + SIDE_ATTACHMENT_SHA + "'"));
            assertEquals(1, count(database.sessions(), "ja_attachment_blob", "sha256='" + MAIN_ATTACHMENT_SHA + "'"));
            assertEquals(1, count(database.sessions(), "ja_attachment_blob", "sha256='" + SHARED_ATTACHMENT_SHA + "'"));
        }
    }

    /** purge 后事务即使在提交前失败，也必须恢复 marker、immutable facts 和 Plan owner 图。 */
    @Test
    void rollsBackPlanPurgeAsOneTransaction() throws Exception {
        try (TestDatabase database = database("side-chat-purge-plan-rollback")) {
            seedGraph(database);
            insertCloseReceipt(database.sessions(), "thr_nested_closed", "thr_root", "ws_root", List.of("thr_nested_closed"));
            runPurge(database.sessions(), true);

            assertEquals(0, count(database.sessions(), "ja_task_side_chat_close_receipt",
                    "root_thread_id='thr_side'"));
            assertEquals(0, count(database.sessions(), "ja_task_side_chat_close_member",
                    "root_thread_id='thr_side'"));
            assertEquals(1, count(database.sessions(), "ja_task_side_chat_close_receipt",
                    "root_thread_id='thr_nested_closed' AND owner_root_thread_id='thr_root'"));
            assertEquals(1, count(database.sessions(), "ja_task_side_chat_close_member",
                    "root_thread_id='thr_nested_closed'"));
            assertEquals(1, count(database.sessions(), "ja_conversation_thread", "thread_id='thr_side'"));
            assertEquals(1, count(database.sessions(), "ja_conversation_thread", "thread_id='thr_agent'"));
            assertEquals(1, count(database.sessions(), "ja_conversation_thread",
                    "thread_id='thr_side' AND temporary_lifecycle_state='CLOSING'"));
            assertEquals(2, count(database.sessions(), "ja_conversation_lineage", "child_thread_id IN ('thr_side','thr_agent')"));
            assertEquals(2, count(database.sessions(), "ja_task_context_seed", "context_seed_id IN ('seed_side','seed_agent')"));
            assertEquals(1, count(database.sessions(), "ja_plan", "plan_id='plan_side'"));
            assertEquals(1, count(database.sessions(), "ja_plan_execution", "run_id='run_plan'"));
            assertEquals(1, count(database.sessions(), "ja_conversation_message", "thread_id='thr_side'"));
            assertEquals(1, count(database.sessions(), "ja_conversation_message", "thread_id='thr_agent'"));
            assertEquals(1, count(database.sessions(), "ja_conversation_turn", "turn_id='turn_side'"));
            assertEquals(1, count(database.sessions(), "ja_conversation_turn", "turn_id='turn_agent'"));
            assertEquals("BOUND", rawAttachment(database, "att_side").status());
            assertEquals(SIDE_ATTACHMENT_SHA, rawAttachment(database, "att_side").blobSha256());
            assertEquals(1, count(database.sessions(), "ja_message_attachment", "attachment_id='att_side'"));
            assertEquals(1, count(database.sessions(), "ja_message_attachment", "attachment_id='att_main'"));
            assertEquals(1, count(database.sessions(), "ja_message_attachment", "attachment_id='att_shared'"));
            assertEquals(1, count(database.sessions(), "ja_context_checkpoint", "thread_id='thr_side'"));
            assertEquals(1, count(database.sessions(), "ja_turn_usage", "thread_id='thr_side'"));
            assertEquals(1, count(database.sessions(), "ja_tool_binding", "turn_id='turn_side'"));
            assertEquals(1, count(database.sessions(), "ja_turn_input", "thread_id='thr_side'"));
            assertEquals(1, count(database.sessions(), "ja_task_mailbox_message", "message_id='msg_inbound'"));
            assertEquals(1, count(database.sessions(), "ja_task_mailbox_message", "message_id='msg_outbound'"));
        }
    }

    /** 先由 AgentStore 建立 Core 消息事实，再在单个 Ja writer transaction 中构造 Plan 依赖图。 */
    private static void seedGraph(TestDatabase database) throws Exception {
        Instant occurredAt = Instant.ofEpochMilli(AT);
        StorePayloadCodec codec = new StorePayloadCodec(database.mapper());
        try (AgentStore store = database.agentStore()) {
            seedCoreMessage(store, "thr_side", "turn_side",
                    codec.encodeUser("record_msg_side", "msg_side",
                            new UserContent(List.of(new TextContent("side"))), occurredAt));
            seedCoreMessage(store, "thr_agent", "turn_agent",
                    codec.encode("record_msg_agent", new AssistantMessage("msg_agent",
                            new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent("agent"))),
                            "agent", null, 1), occurredAt));
            seedCoreMessage(store, "thr_root", "turn_root",
                    codec.encodeUser("record_msg_root", "msg_root",
                            new UserContent(List.of(new TextContent("root"))), occurredAt));
        }
        try (SqlSession session = database.sessions().openSession(); Statement sql = session.getConnection().createStatement()) {
            sql.executeUpdate("""
                INSERT INTO ja_workspace(workspace_id,root_path,kind,display_name,trust,created_at_ms,updated_at_ms) VALUES \
                ('ws_root','C:/tmp/ja-root','PROJECT','Root','TRUSTED','\
                """ + AT + "','" + AT + "'),"
                    + "('ws_external','C:/tmp/ja-external','PROJECT','External','TRUSTED','" + AT + "','" + AT + "')");
            sql.executeUpdate("""
                INSERT INTO ja_conversation_thread(thread_id,owner_user_id,workspace_id,title,created_at_ms,updated_at_ms,provider_id,model_id,\
                access_mode,title_source,collaboration_mode) VALUES \
                ('thr_root','user_local','ws_root','Root','\
                """ + AT + "','" + AT + "','provider','model','APPROVAL_REQUIRED','MANUAL','DEFAULT'),"
                    + "('thr_side','user_local','ws_root','Side','" + AT + "','" + AT + "','provider','model','APPROVAL_REQUIRED','MANUAL','PLAN'),"
                    + "('thr_external','user_local','ws_external','External','" + AT + "','" + AT + "','provider','model','APPROVAL_REQUIRED','MANUAL','DEFAULT')");
            sql.executeUpdate("""
                INSERT INTO ja_task_context_seed(context_seed_id,parent_thread_id,parent_revision,inheritance_mode,\
                task_brief_json,effective_context_json,references_json,permission_ceiling_json,fingerprint,\
                created_at_ms) VALUES \
                ('seed_side','thr_root',0,'EFFECTIVE_CONTEXT',NULL,'{}','[]','{}','\
                """ + "a".repeat(64) + "','" + AT + "')");
            sql.executeUpdate("""
                INSERT INTO ja_task_context_seed(context_seed_id,parent_thread_id,parent_revision,inheritance_mode,\
                task_brief_json,effective_context_json,references_json,permission_ceiling_json,fingerprint,\
                created_at_ms) VALUES \
                ('seed_shared','thr_root',0,'EFFECTIVE_CONTEXT',NULL,'{}',\
                '[{"kind":"attachment","attachmentId":"att_shared"}]','{}','\
                """
                    + "5".repeat(64) + "','" + AT + "')");
            sql.executeUpdate("""
                INSERT INTO ja_conversation_lineage(child_thread_id,parent_thread_id,root_thread_id,task_name,depth,\
                task_kind,lifecycle,context_seed_id,created_at_ms) VALUES \
                ('thr_side','thr_root','thr_root','side','1','SIDE_TASK','INDEPENDENT','seed_side','\
                """ + AT + "')");
            sql.executeUpdate("""
                INSERT INTO ja_conversation_thread(thread_id,owner_user_id,workspace_id,title,created_at_ms,updated_at_ms,provider_id,model_id,\
                access_mode,title_source,collaboration_mode) VALUES \
                ('thr_agent','user_local','ws_root','Agent','\
                """ + AT + "','" + AT + "','provider','model','APPROVAL_REQUIRED','MANUAL','DEFAULT')");
            sql.executeUpdate("""
                INSERT INTO ja_conversation_turn(turn_id,thread_id,state,requested_at_ms,updated_at_ms,completed_at_ms,terminal_summary) \
                VALUES \
                ('turn_side','thr_side','COMPLETED','\
                """ + AT + "','" + AT + "','" + AT + "','done')");
            sql.executeUpdate("""
                INSERT INTO ja_conversation_turn(turn_id,thread_id,state,requested_at_ms,updated_at_ms,completed_at_ms,terminal_summary) \
                VALUES \
                ('turn_root','thr_root','COMPLETED','\
                """ + AT + "','" + AT + "','" + AT + "','done')");
            sql.executeUpdate("""
                INSERT INTO ja_conversation_turn(turn_id,thread_id,state,requested_at_ms,updated_at_ms,completed_at_ms,terminal_summary,\
                parent_turn_id,root_turn_id) VALUES \
                ('turn_agent','thr_agent','COMPLETED','\
                """ + AT + "','" + AT + "','" + AT + "','done','turn_side','turn_side')");
            sql.executeUpdate("""
                INSERT INTO ja_task_context_seed(context_seed_id,parent_thread_id,parent_turn_id,parent_revision,\
                inheritance_mode,\
                task_brief_json,effective_context_json,references_json,permission_ceiling_json,fingerprint,\
                created_at_ms) VALUES \
                ('seed_agent','thr_side','turn_side',0,'BRIEF_ONLY','[]',NULL,'[]','{}','\
                """ + "e".repeat(64) + "','" + AT + "')");
            sql.executeUpdate("""
                INSERT INTO ja_conversation_lineage(child_thread_id,parent_thread_id,root_thread_id,origin_turn_id,task_name,\
                depth,\
                task_kind,lifecycle,context_seed_id,created_at_ms) VALUES \
                ('thr_agent','thr_side','thr_root','turn_side','agent','2','SUBAGENT','ATTACHED','seed_agent','\
                """ + AT + "')");
            assertEquals(1, sql.executeUpdate("UPDATE ja_conversation_thread SET temporary_lifecycle_state='CLOSING' "
                    + "WHERE thread_id='thr_side'"));
            insertMessageProjection(session.getConnection(), "msg_side", "thr_side", "turn_side", "USER",
                    "USER_INPUT", "side", "record_msg_side");
            insertMessageProjection(session.getConnection(), "msg_agent", "thr_agent", "turn_agent", "ASSISTANT",
                    "ASSISTANT", "agent", "record_msg_agent");
            insertMessageProjection(session.getConnection(), "msg_root", "thr_root", "turn_root", "USER",
                    "USER_INPUT", "root", "record_msg_root");
            sql.executeUpdate("""
                INSERT INTO ja_attachment_blob(sha256,size_bytes,media_kind,media_type,created_at_ms) VALUES \
                ('\
                """ + SIDE_ATTACHMENT_SHA + "',3,'TEXT','text/plain','" + AT + "'),"
                    + "('" + MAIN_ATTACHMENT_SHA + "',4,'TEXT','text/plain','" + AT + "'),"
                    + "('" + SHARED_ATTACHMENT_SHA + "',5,'TEXT','text/plain','" + AT + "')");
            sql.executeUpdate("""
                INSERT INTO ja_attachment(attachment_id,workspace_id,blob_sha256,content_sha256,display_name,size_bytes,\
                media_kind,media_type,status,created_at_ms,expires_at_ms,bound_at_ms) VALUES \
                ('att_side','ws_root','\
                """ + SIDE_ATTACHMENT_SHA + "','" + SIDE_ATTACHMENT_SHA + "','side.txt',3,'TEXT','text/plain','BOUND','" + AT + "','" + AT + "','" + AT + "'),"
                    + "('att_main','ws_root','" + MAIN_ATTACHMENT_SHA + "','" + MAIN_ATTACHMENT_SHA + "','main.txt',4,'TEXT','text/plain','BOUND','" + AT + "','" + AT + "','" + AT + "'),"
                    + "('att_shared','ws_root','" + SHARED_ATTACHMENT_SHA + "','" + SHARED_ATTACHMENT_SHA + "','shared.txt',5,'TEXT','text/plain','BOUND','" + AT + "','" + AT + "','" + AT + "')");
            sql.executeUpdate("""
                INSERT INTO ja_message_attachment(message_id,attachment_id,ordinal,created_at_ms) VALUES \
                ('msg_side','att_side',0,'\
                """ + AT + "')");
            sql.executeUpdate("""
                INSERT INTO ja_message_attachment(message_id,attachment_id,ordinal,created_at_ms) VALUES \
                ('msg_side','att_shared',1,'\
                """ + AT + "')");
            sql.executeUpdate("""
                INSERT INTO ja_message_attachment(message_id,attachment_id,ordinal,created_at_ms) VALUES \
                ('msg_root','att_main',0,'\
                """ + AT + "')");
            sql.executeUpdate("""
                INSERT INTO ja_turn_input(input_id,thread_id,turn_id,kind,content_json,state,priority_sequence,\
                created_at_ms,updated_at_ms) VALUES \
                ('input_side','thr_side','turn_side','STEERING','[{"type":"text","text":"pending"}]','PENDING',1,'\
                """ + AT + "','" + AT + "')");
            sql.executeUpdate("""
                INSERT INTO ja_turn_input_attachment(input_id,attachment_id,ordinal,created_at_ms) VALUES \
                ('input_side','att_side',0,'\
                """ + AT + "')");
            sql.executeUpdate("""
                INSERT INTO ja_context_checkpoint(checkpoint_id,thread_id,source_revision,through_ordinal,\
                retained_from_ordinal,\
                summary_json,input_tokens,envelope_fingerprint,strategy_version,usage_json,created_at_ms) VALUES \
                ('checkpoint_side','thr_side',0,0,0,'{}',0,'\
                """ + "1".repeat(64) + "','ja-context-v1','{}','" + AT + "')");
            sql.executeUpdate("""
                INSERT INTO ja_turn_usage(usage_id,model_request_id,thread_id,turn_id,model_round,model_request_ordinal,\
                purpose,certainty,\
                profile_json,created_at_ms) VALUES \
                ('usage_side','ja_turn_usage-request-side','thr_side','turn_side',1,1,'ASSISTANT','UNKNOWN','{}','\
                """ + AT + "')");
            sql.executeUpdate("""
                INSERT INTO ja_tool_call(call_id,thread_id,turn_id,ordinal,tool_name,side_effect,presentation_json,state,\
                revision,created_at_ms,updated_at_ms) VALUES \
                ('call_side','thr_side','turn_side',0,'shell','READ_ONLY','{}','PREPARED',0,'\
                """ + AT + "','" + AT + "')");
            sql.executeUpdate("""
                INSERT INTO ja_tool_binding(turn_id,batch_id,call_id,route_kind,local_name,server_id,remote_name,\
                schema_hash,route_hash,catalog_revision,access_mode,created_at_ms) VALUES \
                ('turn_side','batch_side','call_side','BUILTIN','shell','builtin','shell','\
                """ + "2".repeat(64) + "','" + "3".repeat(64) + "','" + "4".repeat(64) + "','APPROVAL_REQUIRED','" + AT + "')");
            sql.executeUpdate("""
                INSERT INTO ja_task_activity(activity_id,root_thread_id,task_thread_id,actor_thread_id,kind,\
                summary_json,created_at_ms) VALUES \
                ('activity_side','thr_root','thr_side','thr_side','CREATED','{}','\
                """ + AT + "')");
            sql.executeUpdate("""
                INSERT INTO ja_task_projection(task_thread_id,root_thread_id,state,latest_activity_sequence,\
                unread_count,descendant_count,running_descendant_count,needs_attention_count,latest_safe_summary,\
                completed_at_ms,updated_at_ms) VALUES \
                ('thr_side','thr_root','COMPLETED',(SELECT activity_sequence FROM ja_task_activity WHERE \
                activity_id='activity_side'),\
                0,0,0,0,'done','\
                """ + AT + "','" + AT + "')");
            sql.executeUpdate("""
                INSERT INTO ja_task_activity(activity_id,root_thread_id,task_thread_id,actor_thread_id,causal_turn_id,\
                kind,summary_json,created_at_ms) VALUES \
                ('activity_agent','thr_root','thr_agent','thr_agent','turn_side','CREATED','{}','\
                """ + AT + "')");
            sql.executeUpdate("""
                INSERT INTO ja_task_projection(task_thread_id,root_thread_id,state,latest_activity_sequence,\
                unread_count,descendant_count,running_descendant_count,needs_attention_count,latest_safe_summary,\
                completed_at_ms,updated_at_ms) VALUES \
                ('thr_agent','thr_root','COMPLETED',(SELECT activity_sequence FROM ja_task_activity WHERE \
                activity_id='activity_agent'),\
                0,0,0,0,'done','\
                """ + AT + "','" + AT + "')");
            insertMailbox(sql, "msg_inbound", "thr_external", "External", "thr_side", "inbound");
            insertMailbox(sql, "msg_outbound", "thr_side", "Side", "thr_external", "outbound");

            insertPlanGraph(sql);
            insertInteractionGraph(sql);
            session.commit();
        }
    }

    /** 构造 Plan、revision、Run、approval、evaluation 与 step facts。 */
    private static void insertPlanGraph(Statement sql) throws Exception {
        sql.executeUpdate("""
            INSERT INTO ja_plan(plan_id,owner_thread_id,objective,create_operation_key,status,revision,created_at_ms,\
            updated_at_ms) VALUES \
            ('plan_side','thr_side','Plan','plan-create-key','DRAFT',0,'\
            """ + AT + "','" + AT + "')");
        sql.executeUpdate("""
            INSERT INTO ja_plan_draft(plan_draft_id,plan_id,draft_revision,definition_json,updated_at_ms) VALUES \
            ('draft_side','plan_side',0,'{}','\
            """ + AT + "')");
        sql.executeUpdate("""
            INSERT INTO ja_plan_revision(plan_revision_id,plan_id,revision_number,definition_json,plan_hash,created_by,\
            created_at_ms) VALUES \
            ('pr_side','plan_side',1,'{}','\
            """ + HASH + "','AGENT','" + AT + "')");
        sql.executeUpdate("""
            INSERT INTO ja_plan_step(plan_revision_id,step_id,ordinal,title,description,is_required,dependency_ids_json) \
            VALUES \
            ('pr_side','step_side',0,'Step','Do it',1,'[]')\
            """);
        sql.executeUpdate("""
            INSERT INTO ja_plan_acceptance_criterion(plan_revision_id,criterion_id,ordinal,description,is_required) VALUES \
            ('pr_side','criterion_plan',0,'Plan criterion',1)\
            """);
        sql.executeUpdate("""
            INSERT INTO ja_plan_approval(approval_id,plan_id,plan_revision_id,plan_hash,actor,decision,created_at_ms) \
            VALUES \
            ('approval_side','plan_side','pr_side','\
            """ + HASH + "','USER_UI','APPROVED','" + AT + "')");
        sql.executeUpdate("""
            INSERT INTO ja_plan_execution(run_id,plan_id,plan_revision_id,plan_hash,status,process_generation,started_at_ms,\
            created_at_ms,updated_at_ms) VALUES \
            ('run_plan','plan_side','pr_side','\
            """ + HASH + "','RUNNING',1,'" + AT + "','" + AT + "','" + AT + "')");
        sql.executeUpdate("UPDATE ja_plan SET status='EXECUTING',revision=1,active_plan_revision_id='pr_side',active_run_id='run_plan',updated_at_ms='" + AT + "' WHERE plan_id='plan_side'");
        sql.executeUpdate("""
            INSERT INTO ja_plan_event(event_id,plan_id,plan_revision,activity,operation_key,created_at_ms) VALUES \
            ('plan-event-side','plan_side',1,'created','plan-event-key','\
            """ + AT + "')");
        sql.executeUpdate("""
            INSERT INTO ja_plan_step_execution(run_id,plan_revision_id,step_id,status,updated_at_ms) VALUES \
            ('run_plan','pr_side','step_side','PENDING','\
            """ + AT + "')");
        sql.executeUpdate("""
            INSERT INTO ja_plan_acceptance_evidence(evidence_id,plan_id,run_id,plan_revision_id,criterion_id,step_id,\
            source_type,source_id,summary,digest,observed_at_ms,created_at_ms) VALUES \
            ('evidence_side','plan_side','run_plan','pr_side','criterion_plan','step_side','TEST_REPORT','test-side',\
            'ok','\
            """ + "c".repeat(64) + "','" + AT + "','" + AT + "')");
        sql.executeUpdate("""
            INSERT INTO ja_plan_evaluation_attempt(evaluation_id,model_request_id,plan_id,plan_revision_id,run_id,\
            owner_thread_id,input_digest,attempt_ordinal,profile_json,outcome,certainty,started_at_ms) VALUES \
            ('evaluation_plan_side','modelreq_plan_side','plan_side','pr_side','run_plan','thr_side','\
            """ + "d".repeat(64) + "',1,'{}','RUNNING','UNKNOWN','" + AT + "')");
    }

    /** 通过 Store 公共事务 API 建立 Core 消息事实，Ja 投影的复合外键随后引用该不可变身份。 */
    private static void seedCoreMessage(AgentStore store, String threadId, String turnId,
                                        AgentStore.RecordWrite record) {
        Instant occurredAt = record.occurredAt();
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey("ja.local", threadId, "user_local");
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(conversation, turnId);
        store.initializeConversation(new AgentStore.ConversationInitialization(conversation, occurredAt));
        store.admit(new AgentStore.Admission(execution, 0, 1, "RUNNING", JsonObject.empty(), List.of(record),
                List.of(new AgentStore.EventWrite("event_" + record.recordId(), "test.message.seed", 1,
                        JsonObject.empty(), occurredAt)),
                new AgentStore.OperationIdentity("operation_" + record.recordId(), "test.message.seed",
                        HASH, JsonObject.empty()), occurredAt));
        Instant leaseNow = Instant.now();
        AgentStore.Lease lease = store.claimLease(new AgentStore.LeaseRequest(conversation,
                "owner_" + record.recordId(), leaseNow, leaseNow.plusSeconds(300)));
        store.commit(new AgentStore.ExecutionCommit(execution, 1, "RUNNING", 1, lease, 1, "COMPLETED",
                JsonObject.empty(), List.of(), List.of(), new AgentStore.CommitIdentity(execution,
                "commit_" + record.recordId(), HASH), Optional.empty(), leaseNow));
    }

    /** 写入只含 Core 身份和有界展示字段的 V1 消息投影，正文仍只存在于 Core record。 */
    private static void insertMessageProjection(Connection connection, String messageId, String threadId,
                                               String turnId, String role, String kind, String preview,
                                               String recordId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ja_conversation_message(message_id,thread_id,turn_id,ordinal,role,kind,total_characters,
                preview_text,attachments_json,context_references_json,created_at_ms,core_tenant_id,core_thread_id,
                core_turn_id,core_record_id) VALUES(?,?,?,1,?,?,?,?, '[]','[]',?,'ja.local',?,?,?)
                """)) {
            statement.setString(1, messageId);
            statement.setString(2, threadId);
            statement.setString(3, turnId);
            statement.setString(4, role);
            statement.setString(5, kind);
            statement.setInt(6, preview.codePointCount(0, preview.length()));
            statement.setString(7, preview);
            statement.setLong(8, AT);
            statement.setString(9, threadId);
            statement.setString(10, turnId);
            statement.setString(11, recordId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    /** 单个 Thread 的历史 Turn 数可以无界增长，清理须按 SQLite 安全批次完整删除而不截断范围。 */
    @Test
    void purgesMoreThanOneSqlBatchOfTurns() throws Exception {
        try (TestDatabase database = database("side-chat-purge-large-turn-set")) {
            seedGraph(database);
            insertTerminalTurns(database.sessions(), "thr_side", 1_001);

            runPurge(database.sessions(), false);

            assertEquals(0, count(database.sessions(), "ja_conversation_turn",
                    "thread_id IN ('thr_side','thr_agent')"));
            assertEquals(1, count(database.sessions(), "ja_task_side_chat_close_receipt",
                    "root_thread_id='thr_side' AND closed_thread_count=2"));
        }
    }

    /** 第二批真实 SQL 失败时，第一批已删除的叶子及之前清理的业务图必须一起恢复，随后可原样重试。 */
    @Test
    void rollsBackEarlierTurnBatchWhenSecondBatchSqlFails() throws Exception {
        try (TestDatabase database = database("side-chat-purge-second-batch-rollback");
             MybatisTaskRepository tasks = database.taskRepository()) {
            seedGraph(database);
            insertTerminalTurns(database.sessions(), "thr_side", 1_001);
            long secondBatchSequence = installSecondTurnBatchFailure(database.sessions());
            String firstBatchLeaves = "thread_id='thr_side' AND turn_id LIKE 'turn_batch_%' "
                    + "AND turn_sequence<" + secondBatchSequence;
            assertEquals(298, count(database.sessions(), "ja_conversation_turn", firstBatchLeaves));

            StorageException failure = assertThrows(StorageException.class, () -> tasks.deleteClosedSideChat(
                    "thr_side", "thr_root", List.of("thr_agent", "thr_side")));
            assertEquals(StorageException.Code.TRANSACTION, failure.code());
            assertTrue(causedBy(failure, "test.second_turn_batch_after_first_batch"),
                    "故障必须来自第一批叶子已删除后的第二批 SQLite SQL，而非提前失败");

            assertEquals(298, count(database.sessions(), "ja_conversation_turn", firstBatchLeaves));
            assertEquals(1_003, count(database.sessions(), "ja_conversation_turn",
                    "thread_id IN ('thr_side','thr_agent')"));
            assertEquals(0, count(database.sessions(), "ja_task_side_chat_close_receipt",
                    "root_thread_id='thr_side'"));
            assertEquals(0, count(database.sessions(), "ja_task_side_chat_close_member",
                    "root_thread_id='thr_side'"));
            assertEquals(1, count(database.sessions(), "ja_conversation_thread",
                    "thread_id='thr_side' AND temporary_lifecycle_state='CLOSING'"));
            assertEquals(1, count(database.sessions(), "ja_conversation_thread",
                    "thread_id='thr_agent' AND temporary_lifecycle_state IS NULL"));
            assertEquals(1, count(database.sessions(), "ja_plan",
                    "plan_id='plan_side' AND status='EXECUTING' "
                            + "AND active_run_id='run_plan' AND active_plan_revision_id='pr_side'"));
            assertEquals(1, count(database.sessions(), "ja_plan_execution", "run_id='run_plan'"));
            assertEquals(1, count(database.sessions(), "ja_interaction_request",
                    "interaction_id='interaction_side'"));
            assertEquals(2, count(database.sessions(), "ja_conversation_message",
                    "thread_id IN ('thr_side','thr_agent')"));
            assertEquals(2, count(database.sessions(), "agent_record",
                    "tenant_id='ja.local' AND user_id='user_local' AND thread_id IN ('thr_side','thr_agent')"));
            assertEquals("BOUND", rawAttachment(database, "att_side").status());
            assertEquals(SIDE_ATTACHMENT_SHA, rawAttachment(database, "att_side").blobSha256());
            assertEquals(1, count(database.sessions(), "ja_message_attachment", "attachment_id='att_side'"));

            removeSecondTurnBatchFailure(database.sessions());
            TaskModels.SideChatCloseReceipt receipt = tasks.deleteClosedSideChat(
                    "thr_side", "thr_root", List.of("thr_agent", "thr_side"));
            assertEquals(List.of("thr_agent", "thr_side"), receipt.closedThreadIds());
            assertEquals("thr_root", receipt.ownerRootThreadId());
            assertEquals(0, count(database.sessions(), "ja_conversation_turn",
                    "thread_id IN ('thr_side','thr_agent')"));
            assertEquals(1, count(database.sessions(), "ja_task_side_chat_close_receipt",
                    "root_thread_id='thr_side' AND closed_thread_count=2"));
            assertEquals(2, count(database.sessions(), "ja_task_side_chat_close_member",
                    "root_thread_id='thr_side'"));
            assertEquals(0, count(database.sessions(), "ja_plan", "plan_id='plan_side'"));
            assertEquals("DISCARDED", rawAttachment(database, "att_side").status());
        }
    }

    /** 显式按 Mapper 的 turn_sequence 顺序选择第二批首行；触发器只在第一批无父引用叶子已消失时给出成功故障标记。 */
    private static long installSecondTurnBatchFailure(SqlSessionFactory sessions) throws Exception {
        try (SqlSession session = sessions.openSession(); Statement sql = session.getConnection().createStatement()) {
            long secondBatchSequence;
            try (ResultSet rows = sql.executeQuery("""
                    SELECT turn_sequence FROM ja_conversation_turn
                    WHERE thread_id IN ('thr_side','thr_agent')
                    ORDER BY turn_sequence LIMIT 1 OFFSET 300
                    """)) {
                assertTrue(rows.next());
                secondBatchSequence = rows.getLong(1);
                assertFalse(rows.next());
            }
            sql.execute("""
                    CREATE TRIGGER test_reject_second_turn_batch BEFORE DELETE ON ja_conversation_turn
                    WHEN OLD.turn_sequence=
                    """ + secondBatchSequence + """
                     BEGIN
                    SELECT CASE WHEN NOT EXISTS (
                        SELECT 1 FROM ja_conversation_turn
                        WHERE thread_id='thr_side' AND turn_id LIKE 'turn_batch_%' AND turn_sequence<
                    """ + secondBatchSequence + """
                    ) THEN RAISE(ABORT,'test.second_turn_batch_after_first_batch')
                      ELSE RAISE(ABORT,'test.first_turn_batch_not_deleted') END;
                    END
                    """);
            session.commit();
            return secondBatchSequence;
        }
    }

    /** 故障只属于隔离测试库；移除后复用同一关闭身份检验回滚没有残留成功回执。 */
    private static void removeSecondTurnBatchFailure(SqlSessionFactory sessions) throws Exception {
        try (SqlSession session = sessions.openSession(); Statement sql = session.getConnection().createStatement()) {
            sql.execute("DROP TRIGGER test_reject_second_turn_batch");
            session.commit();
        }
    }

    /** 仅查找测试触发器的固定分类，避免把任意事务异常误当作第二批失败证据。 */
    private static boolean causedBy(Throwable failure, String diagnostic) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(diagnostic)) {
                return true;
            }
        }
        return false;
    }

    /** 构造引用 Plan Run 的 Interaction request/draft/event，覆盖完整 interaction owner 子查询。 */
    private static void insertInteractionGraph(Statement sql) throws Exception {
        sql.executeUpdate("""
            INSERT INTO ja_interaction_request(interaction_id,thread_id,turn_id,tool_call_id,plan_revision_id,run_id,\
            operation_key,questions_json,answers_json,status,revision,created_at_ms,updated_at_ms) VALUES \
            ('interaction_side','thr_side','turn_side','call_side','pr_side','run_plan','interaction-key',\
            '[{"questionId":"q"}]','[]','PENDING',0,'\
            """ + AT + "','" + AT + "')");
        sql.executeUpdate("""
            INSERT INTO ja_interaction_draft(interaction_id,thread_id,answers_json,page,is_collapsed,operation_key,\
            revision,updated_at_ms) VALUES \
            ('interaction_side','thr_side','[]',0,0,'draft-key',0,'\
            """ + AT + "')");
        sql.executeUpdate("""
            INSERT INTO ja_interaction_event(thread_id,interaction_id,interaction_revision,kind,occurred_at_ms) VALUES \
            ('thr_side','interaction_side',0,'CREATED','\
            """ + AT + "')");
    }

    /** 插入一条无 sender 外键的 mailbox，分别验证入站删除和外发快照保留。 */
    private static void insertMailbox(Statement sql, String messageId, String senderId, String senderTitle,
                                      String targetId, String text) throws Exception {
        sql.executeUpdate("""
            INSERT INTO ja_task_mailbox_message(message_id,root_thread_id,from_thread_id,sender_title,to_thread_id,\
            kind,content_json,operation_key,state,created_at_ms,updated_at_ms) VALUES \
            ('\
            """ + messageId + "','thr_root','" + senderId + "','" + senderTitle + "','" + targetId + "',"
                + "'MESSAGE','[{\"type\":\"text\",\"text\":\"" + text + "\"}]','key-" + messageId + "','PENDING','" + AT + "','" + AT + "')");
    }

    /** 固定 SQL 形状批量造终态 Turn，单次 JDBC 命令不随记录数增加绑定参数。 */
    private static void insertTerminalTurns(SqlSessionFactory sessions, String threadId, int count)
            throws Exception {
        try (SqlSession session = sessions.openSession();
             PreparedStatement insert = session.getConnection().prepareStatement("""
                     INSERT INTO ja_conversation_turn(
                         turn_id,thread_id,state,requested_at_ms,updated_at_ms,completed_at_ms,terminal_summary
                     ) VALUES (?,?,'COMPLETED',?,?,?,'completed')
                     """)) {
            for (int index = 0; index < count; index++) {
                insert.setString(1, "turn_batch_" + index);
                insert.setString(2, threadId);
                insert.setLong(3, AT);
                insert.setLong(4, AT);
                insert.setLong(5, AT);
                insert.addBatch();
                if ((index + 1) % 200 == 0) {
                    insert.executeBatch();
                }
            }
            insert.executeBatch();
            session.commit();
        }
    }

    /** 执行 purge；rollbackProbe 用于证明所有 SQL 仍处于调用方单一事务中。 */
    private static void runPurge(SqlSessionFactory sessions, boolean rollbackProbe) throws Exception {
        try (SqlSession session = sessions.openSession()) {
            try {
                TaskModels.SideChatCloseReceipt receipt = SideChatPurger.purge(PersistenceMappers.open(session),
                        "thr_side", "thr_root", List.of("thr_agent", "thr_side"), true);
                assertEquals(List.of("thr_agent", "thr_side"), receipt.closedThreadIds());
                assertEquals("thr_root", receipt.ownerRootThreadId());
                if (rollbackProbe) {
                    throw new RollbackProbe();
                }
                session.commit();
            } catch (RollbackProbe expected) {
                session.rollback();
            } catch (Throwable failure) {
                session.rollback();
                throw failure;
            }
        }
    }

    /** 预置此前已清理的嵌套侧聊回执，验证父树 purge 不会按 Workspace 扩大删除范围。 */
    private static void insertCloseReceipt(SqlSessionFactory sessions, String rootThreadId,
                                           String ownerRootThreadId, String workspaceId,
                                           List<String> threadIds) {
        try (SqlSession session = sessions.openSession()) {
            SideChatPurgeMapper mapper = PersistenceMappers.open(session).sideChatPurges();
            assertEquals(1, mapper.insertCloseReceipt(new TaskRecords.SideChatCloseReceiptInsert(
                    rootThreadId, workspaceId, ownerRootThreadId, threadIds.size(), AT)));
            List<TaskRecords.SideChatCloseMemberInsert> members = new ArrayList<>();
            for (int ordinal = 0; ordinal < threadIds.size(); ordinal++) {
                members.add(new TaskRecords.SideChatCloseMemberInsert(rootThreadId, ordinal,
                        threadIds.get(ordinal)));
            }
            assertEquals(threadIds.size(), mapper.insertCloseReceiptMembers(members));
            session.commit();
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
             ResultSet rows = sql.executeQuery("SELECT discarded_at_ms FROM ja_attachment WHERE attachment_id='"
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
