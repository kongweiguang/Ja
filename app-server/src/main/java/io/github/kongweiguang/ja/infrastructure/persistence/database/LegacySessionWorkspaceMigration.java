// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import io.github.kongweiguang.ja.workspace.domain.WorkspacePolicy;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 将 V1-V6 的旧共享会话全树迁到 SESSION 根；数据库归属一次事务提交，目录只预建为空目录。
 */
final class LegacySessionWorkspaceMigration {
    private static final String SESSION_DISPLAY_NAME = "无项目对话";
    private static final String LEGACY_SHARED_DISPLAY_NAME = "无项目";

    /** 启动升级没有外部可替换状态，不允许构造多个迁移控制器。 */
    private LegacySessionWorkspaceMigration() { }

    /**
     * V7 schema 可能已提交而数据迁移失败；每次启动都检查旧固定根并安全重试，成功后不再匹配会话行。
     */
    static void migrate(SQLiteDataSource source, DatabaseConfig config) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(config, "config");
        try (Connection read = source.getConnection()) {
            Path databaseParent = config.databasePath().toAbsolutePath().normalize().getParent();
            if (databaseParent == null) throw new IOException("database path has no parent");
            Path legacyRoot = databaseParent.resolve("general-workspace").toAbsolutePath().normalize();
            List<LegacyRoot> roots = legacyRoots(read, legacyRoot);
            if (roots.isEmpty()) return;
            Map<String, SessionTarget> targets = prepareTargets(config.homeDirectory(), roots);
            migrateTransaction(source, roots, targets);
        } catch (SQLException | IOException failure) {
            throw new IllegalStateException("legacy session workspace migration failed", failure);
        }
    }

    /**
     * 仅固定旧根可迁移；数据库搬迁后对可疑旧 General 身份失败关闭，避免把残留会话当项目放行。
     */
    private static List<LegacyRoot> legacyRoots(Connection connection, Path legacyRoot) throws SQLException {
        List<LegacyRoot> candidates = new ArrayList<>();
        try (PreparedStatement workspaceQuery = connection.prepareStatement(
                "SELECT workspace_id,root_path,kind,display_name FROM workspaces "
                        + "WHERE kind IN ('PROJECT','LEGACY_SHARED')")) {
            try (ResultSet rows = workspaceQuery.executeQuery()) {
                while (rows.next()) {
                    String workspaceId = rows.getString(1);
                    String rootPath = rows.getString(2);
                    String kind = rows.getString(3);
                    String displayName = rows.getString(4);
                    Path persistedRoot = Path.of(rootPath);
                    boolean legacyKind = "LEGACY_SHARED".equals(kind);
                    boolean fixedPathIdentity = persistedRoot.toAbsolutePath().normalize().toString()
                            .equals(legacyRoot.toAbsolutePath().normalize().toString());
                    boolean relocatedIdentity = hasRelocatedLegacyIdentity(persistedRoot, displayName);
                    if (legacyKind || fixedPathIdentity || relocatedIdentity) {
                        if (isLegacyRoot(persistedRoot, legacyRoot)) {
                            candidates.add(new LegacyRoot(workspaceId, rootPath, kind, List.of()));
                        } else if (legacyKind) {
                            throw new SQLException("legacy shared workspace root cannot be verified");
                        } else if (relocatedIdentity) {
                            throw new SQLException("legacy shared workspace is outside the configured data directory");
                        }
                    }
                }
            }
        }
        if (candidates.size() > 1) throw new SQLException("multiple legacy shared workspace identities found");
        List<LegacyRoot> result = new ArrayList<>();
        for (LegacyRoot candidate : candidates) {
            List<LegacyThread> mainThreads = new ArrayList<>();
            try (PreparedStatement threadQuery = connection.prepareStatement(
                    "SELECT t.thread_id,t.title,t.created_at,t.updated_at FROM threads t "
                            + "WHERE t.workspace_id=? AND NOT EXISTS "
                            + "(SELECT 1 FROM thread_lineage l WHERE l.child_thread_id=t.thread_id) "
                            + "ORDER BY t.thread_id")) {
                threadQuery.setString(1, candidate.workspaceId());
                try (ResultSet rows = threadQuery.executeQuery()) {
                    while (rows.next()) mainThreads.add(new LegacyThread(
                            rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4)));
                }
            }
            if (!mainThreads.isEmpty() || !"LEGACY_SHARED".equals(candidate.kind())) {
                result.add(new LegacyRoot(candidate.workspaceId(), candidate.rootPath(), candidate.kind(), mainThreads));
            }
        }
        return List.copyOf(result);
    }

    /**
     * 真实路径可用时由文件系统 identity 对照，避免 Windows Path.equals 忽略大小写；缺失时只接受
     * 双方均缺失且大小写敏感的严格词法相等旧路径。
     * 单边存在或路径含链接都不足以证明目录身份，必须阻止启动而不是降级为共享/项目目录。
     */
    private static boolean isLegacyRoot(Path persistedRoot, Path expectedRoot) throws SQLException {
        Path persisted = persistedRoot.toAbsolutePath().normalize();
        Path expected = expectedRoot.toAbsolutePath().normalize();
        boolean persistedExists = Files.exists(persisted, LinkOption.NOFOLLOW_LINKS);
        boolean expectedExists = Files.exists(expected, LinkOption.NOFOLLOW_LINKS);
        if (persistedExists && expectedExists) {
            try {
                Path canonicalPersisted = canonicalDirectory(persisted);
                Path canonicalExpected = canonicalDirectory(expected);
                return Files.isSameFile(canonicalPersisted, canonicalExpected);
            } catch (IOException unsafePath) {
                throw new SQLException("legacy workspace path cannot be verified", unsafePath);
            }
        }
        if (!persistedExists && !expectedExists) return persisted.toString().equals(expected.toString());
        throw new SQLException("legacy workspace root availability differs from configured root");
    }

    /**
     * 旧库异址复制时固定 data 根可能改变；这个精确旧目录名与旧无项目展示身份组合只触发失败关闭，
     * 不自动迁移用户项目，也不让历史会话静默变成 PROJECT。
     */
    private static boolean hasRelocatedLegacyIdentity(Path persistedRoot, String displayName) {
        Path leaf = persistedRoot.getFileName();
        return leaf != null && "general-workspace".equals(leaf.toString())
                && LEGACY_SHARED_DISPLAY_NAME.equals(displayName);
    }

    /**
     * 每个旧主会话只准备固定布局下的空目录；预建残留可重试，但任意已有内容都使升级失败关闭。
     */
    private static Map<String, SessionTarget> prepareTargets(Path configuredHome, List<LegacyRoot> roots)
            throws IOException {
        Files.createDirectories(configuredHome);
        Path home = canonicalDirectory(configuredHome);
        Path sessionBase = home.resolve("workspaces");
        if (Files.exists(sessionBase, LinkOption.NOFOLLOW_LINKS)) {
            sessionBase = canonicalDirectory(sessionBase);
        } else {
            Files.createDirectory(sessionBase);
            sessionBase = canonicalDirectory(sessionBase);
        }
        requireDirectChild(home, sessionBase);
        WorkspacePolicy policy = new WorkspacePolicy();
        Map<String, SessionTarget> targets = new LinkedHashMap<>();
        for (LegacyRoot legacy : roots) {
            for (LegacyThread thread : legacy.mainThreads()) {
                Path target = sessionBase.resolve(requireThreadId(thread.threadId()));
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    target = canonicalDirectory(target);
                } else {
                    Files.createDirectory(target);
                    target = canonicalDirectory(target);
                }
                requireDirectChild(sessionBase, target);
                requireEmpty(target);
                targets.put(thread.threadId(), new SessionTarget(
                        policy.workspaceId(target), target, thread));
            }
        }
        return Map.copyOf(targets);
    }

    /**
     * 新 workspace 先登记，随后按 lineage root 重属各表；唯一提交边界避免 Thread 与附件落在不同 owner。
     */
    private static void migrateTransaction(SQLiteDataSource source, List<LegacyRoot> roots,
                                           Map<String, SessionTarget> targets) throws SQLException {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                for (LegacyRoot legacy : roots) {
                    markLegacyShared(connection, legacy.workspaceId());
                    for (LegacyThread thread : legacy.mainThreads()) {
                        SessionTarget target = targets.get(thread.threadId());
                        insertSessionWorkspace(connection, legacy.workspaceId(), target);
                        reassignThreads(connection, legacy.workspaceId(), thread.threadId(), target.workspaceId());
                    }
                    requireNoLegacyThreads(connection, legacy.workspaceId());
                    requireAttachmentOwnersAgree(connection, legacy.workspaceId());
                    reassignAttachments(connection, legacy.workspaceId());
                    reassignTurnChangeSets(connection, legacy.workspaceId());
                    reassignWorkspaceWriteClaims(connection, legacy.workspaceId());
                }
                verifyDatabase(connection);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    /** 固定旧根保留且只改 kind；它继续承载无法从历史记录判定 owner 的 draft 附件。 */
    private static void markLegacyShared(Connection connection, String workspaceId) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE workspaces SET kind='LEGACY_SHARED',display_name=?,revision=revision+1,updated_at=? "
                        + "WHERE workspace_id=? AND kind IN ('PROJECT','LEGACY_SHARED')")) {
            update.setString(1, "无项目");
            update.setString(2, Instant.now().toString());
            update.setString(3, workspaceId);
            if (update.executeUpdate() != 1) throw new SQLException("legacy workspace changed during migration");
        }
    }

    /**
     * 把新会话根和保留的旧共享根关联，使界面能显式提供旧文件找回入口。
     * The old directory remains in place; this reference does not move or copy its contents.
     */
    private static void insertSessionWorkspace(Connection connection, String legacyWorkspaceId,
                                               SessionTarget target) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO workspaces(workspace_id,root_path,display_name,trust,revision,created_at,updated_at,kind,"
                        + "legacy_shared_workspace_id) VALUES(?,?,?,'TRUSTED',0,?,?, 'SESSION',?)")) {
            insert.setString(1, target.workspaceId());
            insert.setString(2, target.root().toString());
            insert.setString(3, SESSION_DISPLAY_NAME);
            insert.setString(4, target.thread().createdAt());
            insert.setString(5, target.thread().updatedAt());
            insert.setString(6, legacyWorkspaceId);
            insert.executeUpdate();
        }
    }

    /** 侧边任务和子任务通过 root_thread_id 映射，不依赖深度或生命周期过滤。 */
    private static void reassignThreads(Connection connection, String legacyWorkspaceId,
                                        String rootThreadId, String sessionWorkspaceId) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE threads SET workspace_id=? WHERE workspace_id=? AND "
                        + "(thread_id=? OR thread_id IN "
                        + "(SELECT child_thread_id FROM thread_lineage WHERE root_thread_id=?))")) {
            update.setString(1, sessionWorkspaceId);
            update.setString(2, legacyWorkspaceId);
            update.setString(3, rootThreadId);
            update.setString(4, rootThreadId);
            if (update.executeUpdate() < 1) throw new SQLException("legacy main thread disappeared");
        }
    }

    /** 未归属 lineage root 的记录表示数据库不满足当前迁移模型，必须保留备份并阻止启动。 */
    private static void requireNoLegacyThreads(Connection connection, String legacyWorkspaceId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM threads WHERE workspace_id=?")) {
            query.setString(1, legacyWorkspaceId);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next() || row.getLong(1) != 0) throw new SQLException("legacy thread has no main owner");
            }
        }
    }

    /** 单个 attachment 若消息和 pending input 指向不同会话，不能猜选一个 owner。 */
    private static void requireAttachmentOwnersAgree(Connection connection, String legacyWorkspaceId)
            throws SQLException {
        String sql = "WITH owners AS (" +
                "SELECT a.attachment_id, " +
                "CASE WHEN EXISTS(SELECT 1 FROM thread_lineage l WHERE l.child_thread_id=mthread.thread_id) " +
                "THEN (SELECT root_thread_id FROM thread_lineage l WHERE l.child_thread_id=mthread.thread_id) " +
                "ELSE mthread.thread_id END AS message_root, " +
                "CASE WHEN EXISTS(SELECT 1 FROM thread_lineage l WHERE l.child_thread_id=pthread.thread_id) " +
                "THEN (SELECT root_thread_id FROM thread_lineage l WHERE l.child_thread_id=pthread.thread_id) " +
                "ELSE pthread.thread_id END AS pending_root " +
                "FROM attachments a " +
                "LEFT JOIN message_attachments ma ON ma.attachment_id=a.attachment_id " +
                "LEFT JOIN messages m ON m.message_id=ma.message_id " +
                "LEFT JOIN threads mthread ON mthread.thread_id=m.thread_id " +
                "LEFT JOIN pending_input_attachments pia ON pia.attachment_id=a.attachment_id " +
                "LEFT JOIN pending_inputs pi ON pi.input_id=pia.input_id " +
                "LEFT JOIN threads pthread ON pthread.thread_id=pi.thread_id " +
                "WHERE a.workspace_id=? ) SELECT COUNT(*) FROM owners " +
                "WHERE message_root IS NOT NULL AND pending_root IS NOT NULL " +
                "AND message_root<>pending_root";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, legacyWorkspaceId);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next() || row.getLong(1) != 0) {
                    throw new SQLException("attachment has conflicting thread owners");
                }
            }
        }
    }

    /** 有消息或待处理输入归属的附件跟随 Thread；无证据的草稿仍留在 LEGACY_SHARED。 */
    private static void reassignAttachments(Connection connection, String legacyWorkspaceId) throws SQLException {
        String sql = "UPDATE attachments SET workspace_id=COALESCE(" +
                "(SELECT t.workspace_id FROM message_attachments ma JOIN messages m ON m.message_id=ma.message_id " +
                "JOIN threads t ON t.thread_id=m.thread_id WHERE ma.attachment_id=attachments.attachment_id)," +
                "(SELECT t.workspace_id FROM pending_input_attachments pia " +
                "JOIN pending_inputs pi ON pi.input_id=pia.input_id JOIN threads t ON t.thread_id=pi.thread_id " +
                "WHERE pia.attachment_id=attachments.attachment_id)) " +
                "WHERE workspace_id=? AND (EXISTS(SELECT 1 FROM message_attachments ma " +
                "WHERE ma.attachment_id=attachments.attachment_id) OR EXISTS(" +
                "SELECT 1 FROM pending_input_attachments pia WHERE pia.attachment_id=attachments.attachment_id))";
        try (PreparedStatement update = connection.prepareStatement(sql)) {
            update.setString(1, legacyWorkspaceId);
            update.executeUpdate();
        }
    }

    /** 历史 change sets 仅按固定 SQL 标识符重属，Thread 外键目标仍以事务内 threads 为准。 */
    private static void reassignTurnChangeSets(Connection connection, String legacyWorkspaceId)
            throws SQLException {
        String sql = "UPDATE turn_change_sets SET workspace_id=(SELECT t.workspace_id FROM threads t " +
                "WHERE t.thread_id=turn_change_sets.thread_id) WHERE workspace_id=? AND EXISTS " +
                "(SELECT 1 FROM threads t WHERE t.thread_id=turn_change_sets.thread_id)";
        try (PreparedStatement update = connection.prepareStatement(sql)) {
            update.setString(1, legacyWorkspaceId);
            update.executeUpdate();
        }
    }

    /** write claims 的 fence 序号、队列和终态按 thread_id 迁移，同时保持 SQL 表名编译期固定。 */
    private static void reassignWorkspaceWriteClaims(Connection connection, String legacyWorkspaceId)
            throws SQLException {
        String sql = "UPDATE workspace_write_claims SET workspace_id=(SELECT t.workspace_id FROM threads t " +
                "WHERE t.thread_id=workspace_write_claims.thread_id) WHERE workspace_id=? AND EXISTS " +
                "(SELECT 1 FROM threads t WHERE t.thread_id=workspace_write_claims.thread_id)";
        try (PreparedStatement update = connection.prepareStatement(sql)) {
            update.setString(1, legacyWorkspaceId);
            update.executeUpdate();
        }
    }

    /** 完整性和所有权回读在 commit 前执行，任何不一致都回滚整个归属事务。 */
    private static void verifyDatabase(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "PRAGMA foreign_key_check")) {
            if (rows.next()) throw new SQLException("legacy migration violates a foreign key");
        }
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT COUNT(*) FROM turn_change_sets c JOIN threads t ON t.thread_id=c.thread_id "
                        + "WHERE c.workspace_id<>t.workspace_id")) {
            if (!rows.next() || rows.getLong(1) != 0) throw new SQLException("change set workspace ownership diverged");
        }
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT COUNT(*) FROM workspace_write_claims c JOIN threads t ON t.thread_id=c.thread_id "
                        + "WHERE c.workspace_id<>t.workspace_id")) {
            if (!rows.next() || rows.getLong(1) != 0) throw new SQLException("write claim workspace ownership diverged");
        }
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT COUNT(*) FROM attachments a WHERE "
                        + "EXISTS(SELECT 1 FROM message_attachments ma JOIN messages m ON m.message_id=ma.message_id "
                        + "JOIN threads t ON t.thread_id=m.thread_id WHERE ma.attachment_id=a.attachment_id "
                        + "AND t.workspace_id<>a.workspace_id) OR "
                        + "EXISTS(SELECT 1 FROM pending_input_attachments pia "
                        + "JOIN pending_inputs pi ON pi.input_id=pia.input_id JOIN threads t ON t.thread_id=pi.thread_id "
                        + "WHERE pia.attachment_id=a.attachment_id AND t.workspace_id<>a.workspace_id)")) {
            if (!rows.next() || rows.getLong(1) != 0) throw new SQLException("attachment workspace ownership diverged");
        }
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT COUNT(*) FROM thread_lineage l JOIN threads root ON root.thread_id=l.root_thread_id "
                        + "JOIN threads child ON child.thread_id=l.child_thread_id "
                        + "JOIN workspaces w ON w.workspace_id=root.workspace_id "
                        + "WHERE w.kind='SESSION' AND w.legacy_shared_workspace_id IS NOT NULL "
                        + "AND child.workspace_id<>root.workspace_id")) {
            if (!rows.next() || rows.getLong(1) != 0) throw new SQLException("session thread tree was split");
        }
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "PRAGMA integrity_check")) {
            if (!rows.next() || !"ok".equalsIgnoreCase(rows.getString(1)) || rows.next()) {
                throw new SQLException("legacy migration failed integrity check");
            }
        }
    }

    /** 检查实际目录身份并拒绝链接/reparse point，不能把路径文本当作隔离证明。 */
    private static Path canonicalDirectory(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("workspace root is not a physical directory");
        }
        Path noFollow = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path followed = path.toRealPath();
        if (!noFollow.equals(followed)) throw new IOException("workspace root is a reparse point");
        return followed;
    }

    /** 防止目录被重定向到 home 外的共享位置。 */
    private static void requireDirectChild(Path parent, Path child) throws IOException {
        if (!parent.equals(child.getParent())) throw new IOException("workspace root escaped its parent");
    }

    /** 迁移只能复用上次失败留下的空目录，绝不触碰已有文件。 */
    private static void requireEmpty(Path directory) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            if (entries.iterator().hasNext()) throw new IOException("session workspace is not empty");
        }
    }

    /** SQL 来源必须是数据库内真实根会话 ID，防止历史损坏路径穿越。 */
    private static String requireThreadId(String threadId) throws IOException {
        if (threadId == null || !threadId.matches("thr_[A-Za-z0-9_-]{1,96}")) {
            throw new IOException("invalid legacy thread identity");
        }
        return threadId;
    }

    /** 保存待迁移旧共享根及其所有独立主会话。 */
    private record LegacyRoot(String workspaceId, String rootPath, String kind, List<LegacyThread> mainThreads) { }

    /** 保存数据库中可证明的主会话标识与原始时间，供新 workspace 稳定登记。 */
    private record LegacyThread(String threadId, String title, String createdAt, String updatedAt) { }

    /** 将主会话、workspace identity 和规范空目录绑定为一个迁移目标。 */
    private record SessionTarget(String workspaceId, Path root, LegacyThread thread) { }
}
