// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.conversation.domain.model.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 SQLite 验证全局 Thread discovery 的分页、过滤、关闭子树和旧导航隔离。 */
final class ThreadDiscoveryPersistenceTest extends PersistenceTestSupport {
    private static final long BASE_AT = Instant.parse("2026-09-03T00:00:00Z").toEpochMilli();

    /** 同一全局排序游标跨页只读取 discovery 最小投影，且 Workspace/title 过滤仍在 SQL 内完成。 */
    @Test
    void paginatesGlobalDiscoveryWithFilters() throws Exception {
        try (TestDatabase database = database("thread-discovery-page")) {
            seedFixture(database);
            MybatisHistoryService history = database.history();

            CursorPage<io.github.kongweiguang.ja.conversation.domain.model.ThreadDiscovery> first =
                    history.discoverThreads(new ThreadDiscovery.Query("all", null, null, 2));
            CursorPage<ThreadDiscovery> second = history.discoverThreads(
                    new ThreadDiscovery.Query("all", null, first.nextCursor(), 2));
            CursorPage<ThreadDiscovery> third = history.discoverThreads(
                    new ThreadDiscovery.Query("all", null, second.nextCursor(), 2));

            assertEquals(List.of("thr_main_a", "thr_main_b"), ids(first));
            assertEquals(List.of("thr_side_a", "thr_agent_a"), ids(second));
            assertEquals(List.of("thr_side_b"), ids(third));
            assertNotNull(first.nextCursor());
            assertNotNull(second.nextCursor());
            assertNull(third.nextCursor());
            assertEquals(List.of("subagent", "idle"), List.of(
                    second.items().get(1).kind().name().toLowerCase(java.util.Locale.ROOT),
                    second.items().get(1).status().name().toLowerCase(java.util.Locale.ROOT)));

            assertEquals(List.of("thr_main_b", "thr_side_b"), ids(history.discoverThreads(
                    new ThreadDiscovery.Query("all", null, null, 20, "ws_b"))));
            assertEquals(List.of("thr_main_a", "thr_main_b"), ids(history.discoverThreads(
                    new ThreadDiscovery.Query("all", "MAIN", null, 20))));
        }
    }

    /** CLOSING marker 从 side chat 根递归传播到 SUBAGENT 后代，未关闭 Workspace 的条目保持可发现。 */
    @Test
    void hidesClosingSideChatAndItsEntireLineageSubtree() throws Exception {
        try (TestDatabase database = database("thread-discovery-closing")) {
            seedFixture(database);
            markSideChatClosing(database);
            MybatisHistoryService history = database.history();

            CursorPage<ThreadDiscovery> page = history.discoverThreads(
                    new ThreadDiscovery.Query("all", null, null, 20));

            assertEquals(List.of("thr_main_a", "thr_main_b", "thr_side_b"), ids(page));
            assertTrue(page.items().stream().noneMatch(item -> item.threadId().equals("thr_side_a")
                    || item.threadId().equals("thr_agent_a")));
        }
    }

    /** 无 scope 的历史入口仍按单 Workspace 返回主 Thread metadata；导航 SQL 会排除所有 lineage child。 */
    @Test
    void preservesWorkspaceNavigationSemantics() throws Exception {
        try (TestDatabase database = database("thread-discovery-navigation")) {
            seedFixture(database);
            MybatisHistoryService history = database.history();

            CursorPage<ThreadSummary> page = history.listThreads("ws_a", null, 20);

            assertEquals(List.of("thr_main_a"),
                    page.items().stream().map(ThreadSummary::threadId).toList());
            assertTrue(page.items().stream().allMatch(thread -> thread.status() == ThreadSummary.Status.ACTIVE));
            assertEquals("provider_fixture", page.items().getFirst().preferences().providerId());
        }
    }

    /** 等待澄清是持久态，重启后的发现与 Workspace 列表必须返回同一状态。 */
    @Test
    void discoversPersistedWaitingInputTurn() throws Exception {
        try (TestDatabase database = database("thread-discovery-waiting-input")) {
            seedFixture(database);
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession();
                 PreparedStatement turn = session.getConnection().prepareStatement(
                         "INSERT INTO ja_conversation_turn(turn_id,thread_id,state,requested_at_ms,updated_at_ms) VALUES(?,?,?,?,?)")) {
                turn.setString(1, "turn_waiting_input");
                turn.setString(2, "thr_main_a");
                turn.setString(3, "WAITING_INPUT");
                turn.setLong(4, BASE_AT + 6_000);
                turn.setLong(5, BASE_AT + 6_000);
                turn.executeUpdate();
                session.commit();
            }

            MybatisHistoryService history = database.history();
            ThreadDiscovery discovered = history.discoverThreads(
                    new ThreadDiscovery.Query("all", null, null, 20, "ws_a")).items().stream()
                    .filter(item -> item.threadId().equals("thr_main_a"))
                    .findFirst().orElseThrow();
            assertEquals(ThreadDiscovery.Status.WAITING_INPUT, discovered.status());
            assertEquals("turn_waiting_input", discovered.latestTurnId());
            assertEquals(TurnState.WAITING_INPUT,
                    history.listThreads("ws_a", null, 20).items().getFirst().latestTurnStatus());
        }
    }

    /** 插入跨 Workspace、主 Thread、side chat、SUBAGENT 和 archived 行，显式父根关系避免 discovery 把 child 当主 Thread。 */
    private void seedFixture(TestDatabase database) throws Exception {
        try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
            try (PreparedStatement workspace = session.getConnection().prepareStatement(
                    """
                        INSERT INTO ja_workspace(workspace_id,root_path,kind,display_name,trust,created_at_ms,updated_at_ms)\
                         VALUES(?,?,?,?,?,?,?)\
                        """)) {
                insertWorkspace(workspace, "ws_a", "C:/fixture/a", "Workspace A");
                insertWorkspace(workspace, "ws_b", "C:/fixture/b", "Workspace B");
            }
            try (PreparedStatement thread = session.getConnection().prepareStatement(
                    """
                        INSERT INTO ja_conversation_thread(thread_id,owner_user_id,workspace_id,title,revision,created_at_ms,updated_at_ms,archived_at_ms,\
                        deleted_at_ms,provider_id,model_id,access_mode,reasoning_level,pinned_at_ms,\
                        last_seen_turn_sequence,collaboration_mode,title_source) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,\
                        ?)\
                        """)) {
                insertThread(thread, "thr_main_a", "ws_a", "Main A", BASE_AT + 5_000, null);
                insertThread(thread, "thr_main_b", "ws_b", "Main B", BASE_AT + 4_000, null);
                insertThread(thread, "thr_side_a", "ws_a", "Side A", BASE_AT + 3_000, null);
                insertThread(thread, "thr_agent_a", "ws_a", "Agent A", BASE_AT + 2_000, null);
                insertThread(thread, "thr_side_b", "ws_b", "Side B", BASE_AT + 1_000, null);
                insertThread(thread, "thr_archived_a", "ws_a", "Archived A", BASE_AT - 86_400_000,
                        BASE_AT + 6_000);
            }
            try (PreparedStatement seed = session.getConnection().prepareStatement(
                    """
                        INSERT INTO ja_task_context_seed(context_seed_id,parent_thread_id,parent_turn_id,parent_revision,\
                        inheritance_mode,task_brief_json,effective_context_json,references_json,\
                        permission_ceiling_json,fingerprint,created_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?)\
                        """)) {
                insertSeed(seed, "seed_side_a", "thr_main_a", BASE_AT + 3_000);
                insertSeed(seed, "seed_agent_a", "thr_side_a", BASE_AT + 2_000);
                insertSeed(seed, "seed_side_b", "thr_main_b", BASE_AT + 1_000);
            }
            try (PreparedStatement lineage = session.getConnection().prepareStatement(
                    """
                        INSERT INTO ja_conversation_lineage(child_thread_id,parent_thread_id,root_thread_id,origin_turn_id,\
                        task_name,depth,task_kind,lifecycle,context_seed_id,created_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?)\
                        """)) {
                insertLineage(lineage, "thr_side_a", "thr_main_a", "thr_main_a", 1, "SIDE_TASK", "INDEPENDENT",
                        "seed_side_a", "side");
                insertLineage(lineage, "thr_agent_a", "thr_side_a", "thr_main_a", 2, "SUBAGENT", "ATTACHED",
                        "seed_agent_a", "agent");
                insertLineage(lineage, "thr_side_b", "thr_main_b", "thr_main_b", 1, "SIDE_TASK", "INDEPENDENT",
                        "seed_side_b", "side-b");
            }
            session.commit();
        }
    }

    /** 关闭只更新真实 Thread marker，测试不通过 TaskCoordinator 猜测或删除 Thread。 */
    private static void markSideChatClosing(TestDatabase database) throws Exception {
        try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession();
             PreparedStatement marker = session.getConnection().prepareStatement(
                     "UPDATE ja_conversation_thread SET temporary_lifecycle_state=? WHERE thread_id=?")) {
            marker.setString(1, "CLOSING");
            marker.setString(2, "thr_side_a");
            assertEquals(1, marker.executeUpdate());
            session.commit();
        }
    }

    /** 写入显式 Workspace 类型和 UTC epoch 毫秒，保持 fixture 与持久化 schema 一致。 */
    private static void insertWorkspace(PreparedStatement statement, String id, String root,
                                        String displayName) throws Exception {
        statement.setString(1, id);
        statement.setString(2, root);
        statement.setString(3, "PROJECT");
        statement.setString(4, displayName);
        statement.setString(5, "TRUSTED");
        statement.setLong(6, BASE_AT);
        statement.setLong(7, BASE_AT);
        statement.executeUpdate();
    }

    /** 用同一参数形状插入归属明确的 active/archived Thread，避免 fixture 偶然依赖默认列值。 */
    private static void insertThread(PreparedStatement statement, String id, String workspaceId, String title,
                                     long updatedAt, Long archivedAt) throws Exception {
        statement.setString(1, id);
        statement.setString(2, "user_local");
        statement.setString(3, workspaceId);
        statement.setString(4, title);
        statement.setLong(5, 0);
        statement.setLong(6, BASE_AT);
        statement.setLong(7, updatedAt);
        if (archivedAt == null) {
            statement.setNull(8, java.sql.Types.BIGINT);
        }
        else {
            statement.setLong(8, archivedAt);
        }
        statement.setNull(9, java.sql.Types.BIGINT);
        statement.setString(10, "provider_fixture");
        statement.setString(11, "model_fixture");
        statement.setString(12, "APPROVAL_REQUIRED");
        statement.setString(13, "medium");
        statement.setNull(14, java.sql.Types.BIGINT);
        statement.setNull(15, java.sql.Types.INTEGER);
        statement.setString(16, "DEFAULT");
        statement.setString(17, "MANUAL");
        statement.executeUpdate();
    }

    /** 使用 BRIEF_ONLY 的最小 seed 满足 lineage 外键与领域闭集，并显式绑定对应 parent，不创建无关 Turn/正文。 */
    private static void insertSeed(PreparedStatement statement, String id, String parentThreadId, long createdAt)
            throws Exception {
        statement.setString(1, id);
        statement.setString(2, parentThreadId);
        statement.setNull(3, java.sql.Types.VARCHAR);
        statement.setLong(4, 0);
        statement.setString(5, "BRIEF_ONLY");
        statement.setString(6, "[]");
        statement.setNull(7, java.sql.Types.VARCHAR);
        statement.setString(8, "[]");
        statement.setString(9, "{\"version\":\"task_access_v1\"}");
        statement.setString(10, "a".repeat(64));
        statement.setLong(11, createdAt);
        statement.executeUpdate();
    }

    /** 建立 side chat 根与其 SUBAGENT 后代，显式传入 root 防止跨 Workspace fixture 被错误归并。 */
    private static void insertLineage(PreparedStatement statement, String childId, String parentId, String rootId,
                                      int depth,
                                      String kind, String lifecycle, String seedId, String taskName)
            throws Exception {
        statement.setString(1, childId);
        statement.setString(2, parentId);
        statement.setString(3, rootId);
        statement.setNull(4, java.sql.Types.VARCHAR);
        statement.setString(5, taskName);
        statement.setInt(6, depth);
        statement.setString(7, kind);
        statement.setString(8, lifecycle);
        statement.setString(9, seedId);
        statement.setLong(10, BASE_AT);
        statement.executeUpdate();
    }

    /** 只投影 Thread identity，避免测试对 discovery 内部排序行或实现类型产生耦合。 */
    private static List<String> ids(CursorPage<ThreadDiscovery> page) {
        return page.items().stream().map(ThreadDiscovery::threadId).toList();
    }
}
