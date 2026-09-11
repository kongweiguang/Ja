// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.conversation.domain.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 SQLite 验证全局 Thread discovery 的分页、过滤、关闭子树和旧导航隔离。 */
final class ThreadDiscoveryPersistenceTest extends PersistenceTestSupport {
    /** 同一全局排序游标跨页只读取 discovery 最小投影，且 Workspace/title 过滤仍在 SQL 内完成。 */
    @Test
    void paginatesGlobalDiscoveryWithFilters() throws Exception {
        try (TestDatabase database = database("thread-discovery-page")) {
            seedFixture(database);
            MybatisConversationRepository store = database.agentStore();
            MybatisHistoryService history = database.history(store);

            CursorPage<io.github.kongweiguang.ja.conversation.domain.ThreadDiscovery> first =
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
            store.close();
        }
    }

    /** CLOSING marker 从 side chat 根递归传播到 SUBAGENT 后代，未关闭 Workspace 的条目保持可发现。 */
    @Test
    void hidesClosingSideChatAndItsEntireLineageSubtree() throws Exception {
        try (TestDatabase database = database("thread-discovery-closing")) {
            seedFixture(database);
            markSideChatClosing(database);
            MybatisConversationRepository store = database.agentStore();
            MybatisHistoryService history = database.history(store);

            CursorPage<ThreadDiscovery> page = history.discoverThreads(
                    new ThreadDiscovery.Query("all", null, null, 20));

            assertEquals(List.of("thr_main_a", "thr_main_b", "thr_side_b"), ids(page));
            assertTrue(page.items().stream().noneMatch(item -> item.threadId().equals("thr_side_a")
                    || item.threadId().equals("thr_agent_a")));
            store.close();
        }
    }

    /** 无 scope 的历史入口仍按单 Workspace 返回主 Thread metadata；导航 SQL 会排除所有 lineage child。 */
    @Test
    void preservesWorkspaceNavigationSemantics() throws Exception {
        try (TestDatabase database = database("thread-discovery-navigation")) {
            seedFixture(database);
            MybatisConversationRepository store = database.agentStore();
            MybatisHistoryService history = database.history(store);

            CursorPage<ThreadSummary> page = history.listThreads("ws_a", null, 20);

            assertEquals(List.of("thr_main_a"),
                    page.items().stream().map(ThreadSummary::threadId).toList());
            assertTrue(page.items().stream().allMatch(thread -> thread.status() == ThreadSummary.Status.ACTIVE));
            assertEquals("provider_fixture", page.items().getFirst().preferences().providerId());
            store.close();
        }
    }

    /** 插入跨 Workspace、主 Thread、side chat、SUBAGENT 和 archived 行，显式父根关系避免 discovery 把 child 当主 Thread。 */
    private void seedFixture(TestDatabase database) throws Exception {
        try (var session = database.sessions().openSession()) {
            try (PreparedStatement workspace = session.getConnection().prepareStatement(
                    "INSERT INTO workspaces(workspace_id,root_path,display_name,trust,created_at,updated_at)"
                            + " VALUES(?,?,?,?,?,?)")) {
                insertWorkspace(workspace, "ws_a", "C:/fixture/a", "Workspace A");
                insertWorkspace(workspace, "ws_b", "C:/fixture/b", "Workspace B");
            }
            try (PreparedStatement thread = session.getConnection().prepareStatement(
                    "INSERT INTO threads(thread_id,workspace_id,title,revision,created_at,updated_at,archived_at,"
                            + "deleted_at,provider_id,model_id,access_mode,reasoning_level,pinned_at,"
                            + "last_seen_turn_sequence,collaboration_mode,title_source) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                insertThread(thread, "thr_main_a", "ws_a", "Main A", "2026-09-03T00:00:05Z", null);
                insertThread(thread, "thr_main_b", "ws_b", "Main B", "2026-09-03T00:00:04Z", null);
                insertThread(thread, "thr_side_a", "ws_a", "Side A", "2026-09-03T00:00:03Z", null);
                insertThread(thread, "thr_agent_a", "ws_a", "Agent A", "2026-09-03T00:00:02Z", null);
                insertThread(thread, "thr_side_b", "ws_b", "Side B", "2026-09-03T00:00:01Z", null);
                insertThread(thread, "thr_archived_a", "ws_a", "Archived A", "2026-09-02T00:00:00Z",
                        "2026-09-03T00:00:06Z");
            }
            try (PreparedStatement seed = session.getConnection().prepareStatement(
                    "INSERT INTO task_context_seeds(context_seed_id,parent_thread_id,parent_turn_id,parent_revision,"
                            + "inheritance_mode,task_brief_json,effective_context_json,references_json,"
                            + "permission_ceiling_json,fingerprint,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                insertSeed(seed, "seed_side_a", "thr_main_a", "2026-09-03T00:00:03Z");
                insertSeed(seed, "seed_agent_a", "thr_side_a", "2026-09-03T00:00:02Z");
                insertSeed(seed, "seed_side_b", "thr_main_b", "2026-09-03T00:00:01Z");
            }
            try (PreparedStatement lineage = session.getConnection().prepareStatement(
                    "INSERT INTO thread_lineage(child_thread_id,parent_thread_id,root_thread_id,origin_turn_id,"
                            + "task_name,depth,task_kind,lifecycle,context_seed_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
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

    /** 关闭只登记真实 side chat marker，测试不通过 TaskCoordinator 猜测或删除 Thread。 */
    private static void markSideChatClosing(TestDatabase database) throws Exception {
        try (var session = database.sessions().openSession();
             PreparedStatement marker = session.getConnection().prepareStatement(
                     "INSERT INTO temporary_side_chats(thread_id,state) VALUES(?,?)")) {
            marker.setString(1, "thr_side_a");
            marker.setString(2, "CLOSING");
            marker.executeUpdate();
            session.commit();
        }
    }

    /** 绑定 Workspace 基础列，时间统一使用 ISO-8601 文本以匹配 SQLite keyset 顺序。 */
    private static void insertWorkspace(PreparedStatement statement, String id, String root,
                                        String displayName) throws Exception {
        statement.setString(1, id);
        statement.setString(2, root);
        statement.setString(3, displayName);
        statement.setString(4, "TRUSTED");
        statement.setString(5, "2026-09-03T00:00:00Z");
        statement.setString(6, "2026-09-03T00:00:00Z");
        statement.executeUpdate();
    }

    /** 用同一参数形状插入 active/archived Thread，避免 fixture 偶然依赖默认列值。 */
    private static void insertThread(PreparedStatement statement, String id, String workspaceId, String title,
                                     String updatedAt, String archivedAt) throws Exception {
        statement.setString(1, id);
        statement.setString(2, workspaceId);
        statement.setString(3, title);
        statement.setLong(4, 0);
        statement.setString(5, "2026-09-03T00:00:00Z");
        statement.setString(6, updatedAt);
        if (archivedAt == null) statement.setNull(7, java.sql.Types.VARCHAR);
        else statement.setString(7, archivedAt);
        statement.setNull(8, java.sql.Types.VARCHAR);
        statement.setString(9, "provider_fixture");
        statement.setString(10, "model_fixture");
        statement.setString(11, "APPROVAL_REQUIRED");
        statement.setString(12, "medium");
        statement.setNull(13, java.sql.Types.VARCHAR);
        statement.setNull(14, java.sql.Types.INTEGER);
        statement.setString(15, "DEFAULT");
        statement.setString(16, "MANUAL");
        statement.executeUpdate();
    }

    /** 使用 BRIEF_ONLY 的最小 seed 满足 lineage 外键与领域闭集，并显式绑定对应 parent，不创建无关 Turn/正文。 */
    private static void insertSeed(PreparedStatement statement, String id, String parentThreadId, String createdAt)
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
        statement.setString(11, createdAt);
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
        statement.setString(10, "2026-09-03T00:00:00Z");
        statement.executeUpdate();
    }

    /** 只投影 Thread identity，避免测试对 discovery 内部排序行或实现类型产生耦合。 */
    private static List<String> ids(CursorPage<ThreadDiscovery> page) {
        return page.items().stream().map(ThreadDiscovery::threadId).toList();
    }
}
