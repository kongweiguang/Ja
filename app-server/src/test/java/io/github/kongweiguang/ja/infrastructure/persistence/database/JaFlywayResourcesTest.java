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

/** 将有限 Native migration contract 锁定到 Flyway filename-prefix API。 */
final class JaFlywayResourcesTest {
    /** 接受 Flyway migration prefix 和正式 core 使用的两种 suffix 拼写。 */
    @Test
    void returnsOnlyTheFixedSqlMigrations() {
        JaFlywayResources provider = JaFlywayResources.provider();

        Collection<LoadableResource> resources = provider.getResources("V", new String[]{".sql"});
        assertEquals(6, resources.size());
        assertEquals(java.util.List.of("V1__kernel.sql", "V2__thread_runtime_preferences.sql",
                        "V3__managed_attachments_and_title_usage.sql", "V4__safe_agent_timeline.sql",
                        "V5__close_agent_timeline_states.sql", "V6__reasoning_levels.sql"),
                resources.stream().map(LoadableResource::getFilename).toList());
        assertEquals(6, provider.getResources("V", new String[]{"sql"}).size());
        assertTrue(provider.getResources("db/migration", new String[]{".sql"}).isEmpty());
    }

    /** 规范化 classpath、relative 和 Windows scanner 名称，不读取任意路径。 */
    @Test
    void resolvesOnlyTheKnownMigrationNames() {
        JaFlywayResources provider = JaFlywayResources.provider();

        assertNotNull(provider.getResource("V1__kernel.sql"));
        assertNotNull(provider.getResource("classpath:db/migration/V1__kernel.sql"));
        assertNotNull(provider.getResource("C:\\db\\migration\\V1__kernel.sql"));
        assertNotNull(provider.getResource("classpath:db/migration/V2__thread_runtime_preferences.sql"));
        assertNotNull(provider.getResource("classpath:db/migration/V3__managed_attachments_and_title_usage.sql"));
        assertNotNull(provider.getResource("classpath:db/migration/V4__safe_agent_timeline.sql"));
        assertNotNull(provider.getResource("classpath:db/migration/V5__close_agent_timeline_states.sql"));
        assertNotNull(provider.getResource("classpath:db/migration/V6__reasoning_levels.sql"));
        assertNull(provider.getResource("V2__thread_instruction_scopes.sql"));
        assertNull(provider.getResource("V2__config_generation_selectors.sql"));
        assertNull(provider.getResource("V2__other.sql"));
    }
}
