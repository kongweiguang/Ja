// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.skill;

import io.github.kongweiguang.ja.agent.domain.skill.SkillDescriptor;
import io.github.kongweiguang.ja.agent.domain.skill.SkillPage;
import io.github.kongweiguang.ja.agent.domain.skill.SkillReadRequest;
import io.github.kongweiguang.ja.agent.domain.skill.SkillRequestContext;
import io.github.kongweiguang.ja.agent.domain.skill.SkillResourceContent;
import io.github.kongweiguang.ja.agent.application.gateway.skill.SkillSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固定 Skill 目录的覆盖优先级、请求级读取能力、租户隔离、分页进展和正文指纹契约。
 */
final class SkillCatalogTest {
    /**
     * 先移除被禁用的高优先级副本，再允许低优先级来源接管同名 Skill，避免禁用设置意外隐藏全部版本。
     */
    @Test
    void disabledWinnerAllowsLowerPrioritySourceToTakeOver() {
        SkillCatalog catalog = new SkillCatalog(List.of(
                source("user", 1, "review", "user body"),
                source("project", 3, "review", "project body")));
        SkillRequestContext context = new SkillRequestContext("tenant-a");

        SkillCatalog.Catalog effective = catalog.discover(context, Set.of("project:review"));

        assertEquals("user", effective.skills().getFirst().sourceId());
        assertEquals("user body", catalog.read(effective, new SkillReadRequest("review", "SKILL.md", 100))
                .content());
    }

    /**
     * 重复来源 ID 会让禁用引用和优先级顺序失去唯一含义，因此必须在目录创建时直接拒绝。
     */
    @Test
    void rejectsDuplicateSourceIds() {
        assertThrows(IllegalArgumentException.class, () -> new SkillCatalog(List.of(
                source("user", 1, "first", "one"), source("user", 2, "second", "two"))));
    }

    /**
     * 仅在优先级相同时使用显式注册顺序，并固定后注册来源胜出，保证配置相同就得到相同目录。
     */
    @Test
    void usesRegistrationOrderAsStablePriorityTieBreak() {
        SkillCatalog catalog = new SkillCatalog(List.of(
                source("first", 1, "policy", "first"), source("second", 1, "policy", "second")));

        SkillCatalog.Catalog discovered = catalog.discover(new SkillRequestContext("tenant-a"), Set.of());

        assertEquals("second", discovered.skills().getFirst().sourceId());
    }

    /** 目录注册、描述符和运行时引用共同接受通用带点扩展来源身份。 */
    @Test
    void acceptsDottedExtensionSourceIdentity() {
        SkillCatalog catalog = new SkillCatalog(List.of(source("business.docs", 1, "guide", "rules")));

        SkillCatalog.Catalog discovered = catalog.discover(new SkillRequestContext("tenant-a"), Set.of());

        assertEquals("business.docs", discovered.skills().getFirst().sourceId());
        assertEquals("business.docs:guide", discovered.skills().getFirst().reference());
    }

    /** 来源身份不能包含引用分隔符或路径字符，否则恢复时无法唯一拆解引用。 */
    @Test
    void rejectsAmbiguousSkillSourceIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> new SkillCatalog(List.of(source("business:docs", 1, "guide", "rules"))));
        assertThrows(IllegalArgumentException.class,
                () -> new SkillDescriptor("guide", "Policy.", "business/docs", 1));
    }

    /**
     * 读取绑定到发现时租户和目录对象身份；另一目录实例不能凭相同元数据复用原来源的读取能力。
     */
    @Test
    void bindsReadsToTenantAndCatalogIdentity() {
        AtomicReference<String> readTenant = new AtomicReference<>();
        SkillSource source = source("pg", 1, "policy", "tenant rules", readTenant);
        SkillCatalog first = new SkillCatalog(List.of(source));
        SkillCatalog second = new SkillCatalog(List.of(source));
        SkillRequestContext context = new SkillRequestContext("tenant-a");
        SkillCatalog.Catalog catalog = first.discover(context, Set.of());

        assertEquals("tenant rules", first.read(catalog, new SkillReadRequest("policy", "SKILL.md", 100))
                .content());
        assertEquals("tenant-a", readTenant.get());
        assertThrows(IllegalArgumentException.class,
                () -> second.read(catalog, new SkillReadRequest("policy", "SKILL.md", 100)));
    }

    /**
     * 对实际返回而非来源宣称的正文计算指纹，使持久执行事实能核对模型当时看到的精确文本。
     */
    @Test
    void fingerprintsExactReturnedText() throws Exception {
        String content = "policy 😀";
        SkillCatalog catalog = new SkillCatalog(List.of(source("pg", 1, "policy", content)));
        SkillCatalog.Catalog discovered = catalog.discover(new SkillRequestContext("tenant-a"), Set.of());
        String expected = "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));

        assertEquals(expected, catalog.read(discovered, new SkillReadRequest("policy", "SKILL.md", 100))
                .contentFingerprint());
    }

    /**
     * 发现来源重复返回续页游标时必须失败，避免错误适配器令同步目录发现无限占用执行线程。
     */
    @Test
    void rejectsSourceCursorLoops() {
        SkillSource source = new SkillSource() {
            /** 返回固定来源标识，便于断言错误定位而不依赖匿名类名称。 */
            @Override
            public String id() {
                return "paged";
            }

            /** 固定测试来源的优先级，使唯一测试变量是分页游标是否前进。 */
            @Override
            public int priority() {
                return 1;
            }

            /** 重复返回相同续页以验证 Core 拒绝无进展来源，而不是持续请求页面。 */
            @Override
            public SkillPage discover(SkillRequestContext context, String cursor, int pageSize) {
                return new SkillPage(List.of(), "same");
            }

            /** 提供有界测试正文；游标错误应在到达该读取方法前被发现。 */
            @Override
            public SkillResourceContent read(SkillRequestContext context, SkillDescriptor descriptor,
                                             SkillReadRequest request) {
                return new SkillResourceContent("body", false);
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new SkillCatalog(List.of(source)).discover(new SkillRequestContext("tenant-a"), Set.of()));
        assertTrue(error.getMessage().startsWith("skill_source_cursor_loop:"));
    }

    /**
     * 创建单 Skill 来源并记录读取租户，供来源绑定与发现快照测试复用。
     */
    private static SkillSource source(String id, int priority, String name, String body) {
        return source(id, priority, name, body, new AtomicReference<>());
    }

    /**
     * 创建返回单个描述符的来源夹具；读取时保留并观察目录传入的租户上下文。
     */
    private static SkillSource source(String id, int priority, String name, String body,
                                      AtomicReference<String> observedTenant) {
        return new SkillSource() {
            /** 返回夹具捕获的稳定来源身份。 */
            @Override
            public String id() {
                return id;
            }

            /** 返回夹具配置的固定优先级。 */
            @Override
            public int priority() {
                return priority;
            }

            /** 首页返回唯一描述符，任何后续页均为空终页，避免夹具引入额外分页行为。 */
            @Override
            public SkillPage discover(SkillRequestContext context, String cursor, int pageSize) {
                return cursor == null
                        ? new SkillPage(List.of(new SkillDescriptor(name, "Policy.", id, 1)), null)
                        : new SkillPage(List.of(), null);
            }

            /** 先记录绑定租户，再返回该来源的测试正文。 */
            @Override
            public SkillResourceContent read(SkillRequestContext context, SkillDescriptor descriptor,
                                             SkillReadRequest request) {
                observedTenant.set(context.tenantId());
                return new SkillResourceContent(body, false);
            }
        };
    }
}
