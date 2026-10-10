// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.application;

import io.github.kongweiguang.ja.agent.example.springboot.agent.application.AgentCatalog;
import io.github.kongweiguang.ja.agent.example.springboot.agent.domain.AgentDefinition;
import io.github.kongweiguang.ja.agent.example.springboot.agent.port.out.AgentDefinitionRepository;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.port.out.ThreadDirectory;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.InputContent;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActor;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 覆盖应用边界的别名隔离、Locale 规范化和不确定创建回读失败。 */
final class ThreadServiceBoundaryTest {
    private static final CurrentActor ACTOR = new CurrentActor("tenant_test", "user_test");

    /** 历史响应应脱离组装用列表，避免内部复用时改写已经返回的页面。 */
    @Test
    void historyPageCopiesItsItems() {
        ThreadService.HistoryItem item = new ThreadService.HistoryItem(1, "record", "turn", "user", "text", null, null, null, null);
        List<ThreadService.HistoryItem> source = new ArrayList<>(List.of(item));
        ThreadService.HistoryPage page = new ThreadService.HistoryPage(source, 1, 1, false, null);

        source.clear();

        assertEquals(List.of(item), page.items());
        assertThrows(UnsupportedOperationException.class, page.items()::clear);
    }

    /** 默认标题上限按 Unicode 码点计算，避免截断 emoji 的 UTF-16 代理对。 */
    @Test
    void truncatesInitialTitleAtCodePointBoundary() {
        String expected = "a".repeat(39) + "🙂";

        String actual = io.github.kongweiguang.ja.agent.example.springboot.conversation.domain.ThreadMetadata
                .initialTitle(expected + "later text");

        assertEquals(expected, actual);
        assertEquals(40, actual.codePointCount(0, actual.length()));
    }

    /** 搜索词在应用边界使用 Locale.ROOT，避免宿主 Turkish Locale 改变持久化查询语义。 */
    @Test
    void normalizesSearchWithRootLocale() {
        AtomicReference<String> receivedSearch = new AtomicReference<>();
        ThreadDirectory directory = directory((method, arguments) -> {
            if (method.equals("list")) {
                receivedSearch.set((String) arguments[2]);
                return List.of();
            }
            throw new AssertionError("unexpected ThreadDirectory method: " + method);
        });
        ThreadService service = new ThreadService(directory, null, null, codec(), null);
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            service.list(ACTOR, false, " I ", 10, 0);
        } finally {
            Locale.setDefault(previous);
        }

        assertEquals("i", receivedSearch.get());
    }

    /** 幂等回读失败只作为附加诊断，调用方仍能识别原始写入不确定结果。 */
    @Test
    void preservesCreateFailureWhenIdempotencyLookupFails() {
        RuntimeException writeFailure = new IllegalStateException("write result unknown");
        RuntimeException lookupFailure = new IllegalStateException("lookup unavailable");
        AtomicInteger lookups = new AtomicInteger();
        ThreadDirectory directory = directory((method, arguments) -> {
            if (method.equals("findByCreateKey") && lookups.getAndIncrement() == 0) {
                return null;
            }
            if (method.equals("findByCreateKey")) {
                throw lookupFailure;
            }
            throw new AssertionError("unexpected ThreadDirectory method: " + method);
        });
        AgentStore store = (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(), new Class<?>[]{AgentStore.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("initializeConversation")) {
                        throw writeFailure;
                    }
                    throw new AssertionError("unexpected AgentStore method: " + method.getName());
                });
        AgentDefinitionRepository definitions = () -> List.of(new AgentDefinition("general", "General", "prompt"));
        ThreadService service = new ThreadService(directory, store, new AgentCatalog(definitions), codec(), null);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.create(ACTOR, "create-key", null));

        assertSame(writeFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(lookupFailure, thrown.getSuppressed()[0]);
    }

    /** 并发幂等键若已绑定其它 Agent，应返回状态冲突而不能伪称创建成功或泄漏唯一键异常。 */
    @Test
    void rejectsCreateRaceWhenIdempotencyKeyBelongsToAnotherAgent() {
        AtomicInteger lookups = new AtomicInteger();
        ThreadDirectory directory = directory((method, arguments) -> {
            if (method.equals("findByCreateKey") && lookups.getAndIncrement() == 0) {
                return null;
            }
            if (method.equals("findByCreateKey")) {
                return new io.github.kongweiguang.ja.agent.example.springboot.conversation.domain.ThreadMetadata(
                        "thr_existing", "analyst", "Existing", false, 0, Instant.EPOCH, Instant.EPOCH);
            }
            throw new AssertionError("unexpected ThreadDirectory method: " + method);
        });
        RuntimeException uniqueConflict = new IllegalStateException("create key already exists");
        AgentStore store = (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(), new Class<?>[]{AgentStore.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("initializeConversation")) {
                        throw uniqueConflict;
                    }
                    throw new AssertionError("unexpected AgentStore method: " + method.getName());
                });
        AgentDefinitionRepository definitions = () -> List.of(
                new AgentDefinition("general", "General", "general prompt"),
                new AgentDefinition("analyst", "Analyst", "analyst prompt"));
        ThreadService service = new ThreadService(directory, store, new AgentCatalog(definitions), codec(), null);

        IllegalStateException conflict = assertThrows(IllegalStateException.class,
                () -> service.create(ACTOR, "shared-key", "general"));

        assertEquals("create key belongs to another agent", conflict.getMessage());
        assertSame(uniqueConflict, conflict.getSuppressed()[0]);
    }

    /** owner actor 显式进入目录查询，跨用户 Thread 仍以 not-found 隐藏存在性。 */
    @Test
    void requiresThreadOwnershipAtApplicationBoundary() {
        AtomicReference<CurrentActor> receivedActor = new AtomicReference<>();
        ThreadDirectory directory = directory((method, arguments) -> {
            if (method.equals("find")) {
                receivedActor.set((CurrentActor) arguments[0]);
                return null;
            }
            throw new AssertionError("unexpected ThreadDirectory method: " + method);
        });
        ThreadService service = new ThreadService(directory, null, null, codec(), null);

        assertThrows(ChatNotFoundException.class, () -> service.requireOwned(ACTOR, "thr_missing"));

        assertEquals(ACTOR, receivedActor.get());
    }

    /** 输入 DTO 规范化为 Skill、附件、正文顺序，并允许仅携带附件的消息。 */
    @Test
    void convertsInputContentInCanonicalOrderAndAllowsAttachmentOnly() {
        UserContent content = new InputContent("Review this", List.of("demo-pg:brief-reply"), List.of("att_a"))
                .toUserContent();
        UserContent attachmentOnly = new InputContent("", List.of(), List.of("att_b")).toUserContent();

        assertEquals(List.of(SkillReferenceContent.class, AttachmentContent.class, TextContent.class),
                content.blocks().stream().map(Object::getClass).toList());
        assertEquals(List.of("att_b"), attachmentOnly.attachmentIds());
        assertThrows(IllegalArgumentException.class, () -> new InputContent("", List.of("skill_a"), List.of()));
    }

    /** 动态端口只实现测试场景读取路径，未命中的协作调用立即失败。 */
    private static ThreadDirectory directory(DirectoryCall call) {
        return (ThreadDirectory) Proxy.newProxyInstance(ThreadDirectory.class.getClassLoader(), new Class<?>[]{ThreadDirectory.class},
                (proxy, method, arguments) -> call.invoke(method.getName(), arguments));
    }

    /** 为应用端口测试提供真实 codec，确保测试不会绕过持久化事实解码约束。 */
    private static StorePayloadCodec codec() {
        return new StorePayloadCodec(new ObjectMapper());
    }

    /** 避免为只验证边界语义的用例实现无关的 JDBC 端口方法。 */
    @FunctionalInterface
    private interface DirectoryCall {
        /** 按测试场景读取动态代理收到的目录调用。 */
        Object invoke(String method, Object[] arguments);
    }
}
