// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import org.flywaydb.core.api.resource.LoadableResource;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 将 Native Image 的 Flyway 资源闭集锁定为当前固定版本顺序。 */
final class JaFlywayResourcesTest {
    /** Flyway 的两种 suffix 拼写都只能发现当前固定迁移，旧脚本不能进入运行时资源闭集。 */
    @Test
    void returnsOnlyTheFixedSqlMigrations() {
        JaFlywayResources provider = JaFlywayResources.provider();

        Collection<LoadableResource> resources = provider.getResources("V", new String[]{".sql"});
        assertEquals(14, resources.size());
        assertEquals(java.util.List.of("V1__kernel.sql", "V2__thread_subagent_policies.sql",
                "V3__subagent_reasoning.sql", "V4__conversation_recovery_usage_projection.sql",
                "V6__conversation_current_path_reask.sql", "V7__session_workspace_identity.sql",
                "V8__client_operation_receipts.sql", "V9__goal_progress_counter.sql",
                "V10__unbounded_round_counters.sql", "V11__execution_cursor_without_budget.sql",
                "V12__drop_execution_budgets.sql", "V13__plan_evaluation_attempts.sql",
                "V14__assistant_public_text_pages.sql", "V15__input_operation_receipts.sql"),
                resources.stream().map(LoadableResource::getFilename).toList());
        assertEquals(14, provider.getResources("V", new String[]{"sql"}).size());
        assertTrue(provider.getResources("db/migration", new String[]{".sql"}).isEmpty());
    }

    /** 规范化 classpath、relative 和 Windows scanner 名称，但拒绝任意路径与历史脚本。 */
    @Test
    void resolvesOnlyTheKnownMigrationNames() {
        JaFlywayResources provider = JaFlywayResources.provider();

        assertNotNull(provider.getResource("V1__kernel.sql"));
        assertNotNull(provider.getResource("classpath:db/migration/V1__kernel.sql"));
        assertNotNull(provider.getResource("classpath:db/migration/V2__thread_subagent_policies.sql"));
        assertNotNull(provider.getResource("classpath:db/migration/V3__subagent_reasoning.sql"));
        assertNotNull(provider.getResource("classpath:db/migration/V4__conversation_recovery_usage_projection.sql"));
        assertNotNull(provider.getResource("classpath:db/migration/V6__conversation_current_path_reask.sql"));
        assertNotNull(provider.getResource("classpath:db/migration/V7__session_workspace_identity.sql"));
        assertNotNull(provider.getResource("classpath:db/migration/V8__client_operation_receipts.sql"));
        assertNotNull(provider.getResource("classpath:db/migration/V9__goal_progress_counter.sql"));
        assertNotNull(provider.getResource("classpath:db/migration/V10__unbounded_round_counters.sql"));
        assertNotNull(provider.getResource("classpath:db/migration/V11__execution_cursor_without_budget.sql"));
        assertNotNull(provider.getResource("classpath:db/migration/V12__drop_execution_budgets.sql"));
        assertNotNull(provider.getResource("classpath:db/migration/V13__plan_evaluation_attempts.sql"));
        assertNotNull(provider.getResource("classpath:db/migration/V14__assistant_public_text_pages.sql"));
        assertNotNull(provider.getResource("classpath:db/migration/V15__input_operation_receipts.sql"));
        assertNotNull(provider.getResource("C:\\db\\migration\\V1__kernel.sql"));
        assertNull(provider.getResource("classpath:db/migration/V2__thread_runtime_preferences.sql"));
        assertNull(provider.getResource("classpath:db/migration/V7__turn_operations.sql.conf"));
        assertNull(provider.getResource("classpath:db/migration/V21__canonical_thread_preferences.sql"));
        assertNull(provider.getResource("V2__other.sql"));
    }
}
