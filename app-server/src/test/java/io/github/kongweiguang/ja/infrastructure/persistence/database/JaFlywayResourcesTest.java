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

/** 将 Native Image 的 Flyway 资源闭集锁定为当前 V1/V2/V3 顺序。 */
final class JaFlywayResourcesTest {
    /** Flyway 的两种 suffix 拼写都只能发现当前 V1/V2/V3，历史版本不能进入运行时资源闭集。 */
    @Test
    void returnsOnlyTheFixedSqlMigrations() {
        JaFlywayResources provider = JaFlywayResources.provider();

        Collection<LoadableResource> resources = provider.getResources("V", new String[]{".sql"});
        assertEquals(3, resources.size());
        assertEquals(java.util.List.of("V1__kernel.sql", "V2__thread_subagent_policies.sql",
                "V3__subagent_reasoning.sql"),
                resources.stream().map(LoadableResource::getFilename).toList());
        assertEquals(3, provider.getResources("V", new String[]{"sql"}).size());
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
        assertNotNull(provider.getResource("C:\\db\\migration\\V1__kernel.sql"));
        assertNull(provider.getResource("classpath:db/migration/V2__thread_runtime_preferences.sql"));
        assertNull(provider.getResource("classpath:db/migration/V7__turn_operations.sql.conf"));
        assertNull(provider.getResource("classpath:db/migration/V21__canonical_thread_preferences.sql"));
        assertNull(provider.getResource("V2__other.sql"));
    }
}
