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

/** 将 JVM 与 Native Image 共用的唯一初始化 migration 固定为 V1。 */
final class JaFlywayResourcesTest {
    /** 新库只执行不可变 V1；历史布局不能通过额外 migration 被静默升级。 */
    @Test
    void returnsOnlyTheFixedSqlMigrations() {
        JaFlywayResources provider = JaFlywayResources.provider();

        Collection<LoadableResource> resources = provider.getResources("V", new String[]{".sql"});
        assertEquals(1, resources.size());
        assertEquals(java.util.List.of("V1__kernel.sql"),
                resources.stream().map(LoadableResource::getFilename).toList());
        assertEquals(1, provider.getResources("V", new String[]{"sql"}).size());
        assertTrue(provider.getResources("db/migration", new String[]{".sql"}).isEmpty());
    }

    /** 只解析 V1 的 classpath、relative 与 Windows scanner 名称，拒绝历史升级脚本。 */
    @Test
    void resolvesOnlyTheKnownMigrationNames() {
        JaFlywayResources provider = JaFlywayResources.provider();

        assertNotNull(provider.getResource("V1__kernel.sql"));
        assertNotNull(provider.getResource("classpath:db/migration/V1__kernel.sql"));
        assertNotNull(provider.getResource("C:\\db\\migration\\V1__kernel.sql"));
        assertNull(provider.getResource("classpath:db/migration/V2__thread_subagent_policies.sql"));
        assertNull(provider.getResource("classpath:db/migration/V18__ordered_assistant_public_text.sql"));
        assertNull(provider.getResource("V2__other.sql"));
    }
}
