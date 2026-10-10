// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.SideChatPurgeMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证临时会话清理遵守真实 Store 外键、租户与调用方事务，而非只删除展示表。 */
final class SideChatStorePurgeTest extends PersistenceTestSupport {
    private static final List<String> TABLES = List.of("agent_conversation", "agent_execution", "agent_record",
            "agent_event", "agent_operation_receipt", "agent_commit_receipt", "agent_lease");

    /** 空集合不产生删除；显式按外键叶到根清理后，其他租户和未选中的 Ja 会话仍须保留。 */
    @Test
    void purgesAgentStoreFactsWithinExactJaConversation() throws Exception {
        try (TestDatabase db = database("sidechat-store-cascade")) {
            seed(db.agentStore(), "ja.local", "thr_temporary");
            seed(db.agentStore(), "other.tenant", "thr_temporary");
            seed(db.agentStore(), "ja.local", "thr_main");
            try (SqlSession session = db.sessions().openSession()) {
                SideChatPurgeMapper purge = session.getMapper(SideChatPurgeMapper.class);
                assertEquals(0, purge.deleteAgentConversations(List.of()));
                assertEquals(1, deleteAgentStoreConversation(purge, List.of("thr_temporary")));
                session.commit();
            }
            for (String table : TABLES) {
                assertEquals(0, count(db, table, "ja.local", "thr_temporary"), table);
                assertTrue(count(db, table, "other.tenant", "thr_temporary") > 0, table);
                assertTrue(count(db, table, "ja.local", "thr_main") > 0, table);
            }
        }
    }

    /** 后续业务图清理失败时，调用方回滚必须同时恢复执行、记录、事件、回执和租约。 */
    @Test
    void rollbackRestoresAllAgentStoreFacts() throws Exception {
        try (TestDatabase db = database("sidechat-store-rollback")) {
            seed(db.agentStore(), "ja.local", "thr_temporary");
            try (SqlSession session = db.sessions().openSession()) {
                assertEquals(1, deleteAgentStoreConversation(session.getMapper(SideChatPurgeMapper.class),
                        List.of("thr_temporary")));
                session.rollback();
            }
            for (String table : TABLES) {
                assertTrue(count(db, table, "ja.local", "thr_temporary") > 0, table);
            }
        }
    }

    /** 按生产 purge 的外键叶到根顺序执行，避免测试依赖 Store schema 的级联删除策略。 */
    private static int deleteAgentStoreConversation(SideChatPurgeMapper purge, List<String> threadIds) {
        assertTrue(purge.deleteAgentEvents(threadIds) > 0);
        assertTrue(purge.deleteAgentOperationReceipts(threadIds) > 0);
        assertTrue(purge.deleteAgentCommitReceipts(threadIds) > 0);
        assertTrue(purge.deleteAgentRecords(threadIds) > 0);
        assertTrue(purge.deleteAgentLeases(threadIds) > 0);
        assertTrue(purge.deleteAgentExecutions(threadIds) > 0);
        return purge.deleteAgentConversations(threadIds);
    }

    /** 通过 Store 公共事务接口生成完整事实图，不手插执行行或绕过其外键与 fencing 规则。 */
    private static void seed(AgentStore store, String tenant, String threadId) {
        Instant now = Instant.now();
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(tenant, threadId, "user_local");
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(conversation, "turn_purge");
        store.initializeConversation(new AgentStore.ConversationInitialization(conversation, now));
        store.admit(new AgentStore.Admission(execution, 0, 1, "RUNNING", new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}"), List.of(new AgentStore.RecordWrite("record_initial", "test.fact", 1, new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}"), now)), List.of(new AgentStore.EventWrite("event_initial", "test.event", 1, new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}"), now)), new AgentStore.OperationIdentity("operation_" + threadId, "test.start", "a".repeat(64), new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}")), now));
        AgentStore.Lease lease = store.claimLease(new AgentStore.LeaseRequest(conversation, "owner_purge",
                now, now.plusSeconds(300)));
        store.commit(new AgentStore.ExecutionCommit(execution, 1, "RUNNING", 1, lease, 1, "COMPLETED",
                JsonObject.empty(),
                List.of(), List.of(), new AgentStore.CommitIdentity(execution, "commit_terminal", "b".repeat(64)),
                Optional.empty(), now));
    }

    /** 表名来自固定测试闭集，身份始终绑定参数；逐表检查物理清理是否完整且隔离。 */
    private static long count(TestDatabase db, String suffix, String tenant, String threadId) throws Exception {
        if (!TABLES.contains(suffix)) {
            throw new IllegalArgumentException("unknown Store table");
        }
        try (SqlSession session = db.sessions().openSession();
             PreparedStatement query = session.getConnection().prepareStatement("SELECT COUNT(*) FROM "
                     + suffix + " WHERE tenant_id=? AND thread_id=?")) {
            query.setString(1, tenant);
            query.setString(2, threadId);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }
}
