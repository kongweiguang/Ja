// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.model.ResourceReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Core 只持久化不透明资源身份，不解释或归一宿主资源位置。 */
final class ResourceReferencePersistenceTest {
    private final PersistenceCodec codec = new PersistenceCodec(new ObjectMapper());

    /** 保留宿主给出的资源标识原样，使路径策略留在真正拥有资源授权的接入方。 */
    @Test
    void roundTripsOpaqueResourceIdentityWithoutNormalizingIt() {
        ResourceReferenceContent reference = new ResourceReferenceContent(
                "host.catalog", "../outside/file.txt", "host-specific");
        UserContent original = new UserContent(List.of(reference, new TextContent("Please inspect this resource.")));

        String persisted = codec.writeUserContent(original);
        UserContent restored = codec.readUserContent(persisted);

        assertEquals(original, restored);
        assertEquals(reference, restored.resourceReferences().getFirst());
        assertTrue(persisted.contains("\"kind\":\"resource_reference\""));
    }

    /** 旧 workspace discriminator 必须由应用迁移先改写，Core 不留旧 wire alias。 */
    @Test
    void rejectsLegacyWorkspaceBlockInsteadOfApplyingCompatibilityRules() {
        String legacy = """
            [{"kind":"workspace_reference","workspaceId":"ws_old",\
            "relativePath":"file.txt","entryKind":"file"}]\
            """;

        assertThrows(StorageException.class, () -> codec.readUserContent(legacy));
    }
}
