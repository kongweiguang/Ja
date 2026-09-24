// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import io.github.kongweiguang.ja.workspace.domain.WorkspacePolicy;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;

/** 仅在随机 Temp Ja home 写入最小 V6 旧共享会话，供 WebView2 检查迁移后的菜单。 */
public final class LegacyWorkspaceMenuFixture {
    private static final String TIME = "2026-09-23T00:00:00Z";

    /** 无状态 fixture 不允许被生产组合根实例化。 */
    private LegacyWorkspaceMenuFixture() { }

    /** 用生产 Flyway 资源创建 V6 schema；旧共享文件只在隔离目录中写入一次。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("expected isolated Ja home");
        Path home = Path.of(args[0]).toAbsolutePath().normalize();
        Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        if (!home.startsWith(temporary) || home.equals(temporary)) {
            throw new IllegalArgumentException("fixture Ja home must be under system Temp");
        }
        Path relative = temporary.relativize(home);
        if (relative.getNameCount() < 3
                || !relative.getName(0).toString().startsWith("ja-review-redesign-")) {
            throw new IllegalArgumentException("fixture Ja home must belong to an isolated WebView run");
        }
        Path data = Files.createDirectories(home.resolve("data"));
        Path legacy = Files.createDirectories(data.resolve("general-workspace"));
        Files.writeString(legacy.resolve("old-file.txt"), "legacy file stays in the shared folder\r\n");
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + data.resolve("ja.db"));
        Flyway.configure().dataSource(source).locations(new String[0])
                .resourceProvider(JaFlywayResources.provider()).baselineOnMigrate(false)
                .target(MigrationVersion.fromVersion("6")).load().migrate();
        String workspaceId = new WorkspacePolicy().workspaceId(legacy.toRealPath());
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement workspace = connection.prepareStatement(
                    "INSERT INTO workspaces(workspace_id,root_path,display_name,trust,created_at,updated_at) "
                            + "VALUES(?,?,?,'TRUSTED',?,?)");
                 PreparedStatement thread = connection.prepareStatement(
                         "INSERT INTO threads(thread_id,workspace_id,title,created_at,updated_at,provider_id,model_id,"
                                 + "access_mode,collaboration_mode,title_source) "
                                 + "VALUES('thr_legacy_menu',?,'Legacy Menu Thread',?,?,'provider_e2e',"
                                 + "'model_e2e','FULL_ACCESS','DEFAULT','MANUAL')")) {
                workspace.setString(1, workspaceId);
                workspace.setString(2, legacy.toRealPath().toString());
                workspace.setString(3, "无项目");
                workspace.setString(4, TIME);
                workspace.setString(5, TIME);
                workspace.executeUpdate();
                thread.setString(1, workspaceId);
                thread.setString(2, TIME);
                thread.setString(3, TIME);
                thread.executeUpdate();
            }
            connection.commit();
        }
    }
}
