// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.conversation.UserMessage;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.application.gateway.store.TransactionResource;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 固定 Ja 只投影单条通用 Core USER 准入事实，队列正文仍交由独立队列参与者结算。 */
final class JaAdmissionFactsCodecTest {
    private static final Instant ACCEPTED_AT = Instant.parse("2026-09-26T00:00:00Z");
    private final ExecutionRecordCodec records = new StorePayloadCodec(new ObjectMapper());

    /** 成功准入从唯一 Core 记录恢复消息身份与正文，确保 Ja 投影读取同一个事实源。 */
    @Test
    void decodesTheSingleCoreUserMessage() throws SQLException {
        UserContent content = new UserContent(List.of(new TextContent("请继续处理")));
        AgentStore.RecordWrite user = records.encodeUser("record_user", "item_user", content, ACCEPTED_AT);

        assertEquals(new UserMessage("item_user", content),
                JaAdmissionFactsCodec.decodeUserMessage(records, transaction(List.of(user))));
    }

    /** 初始 USER 与待消费队列正文可同事务准入，Task brief 仍只能来自普通 USER 事实。 */
    @Test
    void ignoresQueuedInputWhenReadingTheInitialMessage() throws SQLException {
        UserContent initial = new UserContent(List.of(new TextContent("初始任务")));
        UserContent queuedContent = new UserContent(List.of(new TextContent("来自另一会话的消息")));
        AgentStore.RecordWrite queued = records.encodeQueuedUser("record_queued", "item_queued", queuedContent,
                ACCEPTED_AT);
        AgentStore.RecordWrite user = records.encodeUser("record_user", "item_user", initial, ACCEPTED_AT);

        assertEquals(new UserMessage("item_user", initial),
                JaAdmissionFactsCodec.decodeUserMessage(records, transaction(List.of(queued, user))));
    }

    /** 重复初始消息不能被“取第一条”掩盖，否则 Task brief 与执行正文可能分叉。 */
    @Test
    void rejectsDuplicateCoreUserMessages() {
        UserContent content = new UserContent(List.of(new TextContent("请继续处理")));
        AgentStore.RecordWrite first = records.encodeUser("record_user_1", "item_user_1", content, ACCEPTED_AT);
        AgentStore.RecordWrite second = records.encodeUser("record_user_2", "item_user_2", content, ACCEPTED_AT);

        assertThrows(SQLException.class,
                () -> JaAdmissionFactsCodec.decodeUserMessage(records, transaction(List.of(first, second))));
    }

    /** 无 USER 事实的空准入不允许 Ja 生成空正文或从临时请求对象补写内容。 */
    @Test
    void rejectsAdmissionWithoutUserMessage() {
        assertThrows(SQLException.class,
                () -> JaAdmissionFactsCodec.decodeUserMessage(records, transaction(List.of())));
    }

    /** 构造仅含 Store 已分配身份与事实列表的准入上下文，避免测试依赖数据库实现。 */
    private static AgentStoreTransactionContext transaction(List<AgentStore.RecordWrite> writes) {
        Connection connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> null);
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(
                new AgentStore.ConversationKey("ja.local", "thr_child", "user_local"), "turn_child");
        return new AgentStoreTransactionContext(TransactionResource.of(connection), execution, 0, 0, -1, 1, 1,
                0, 0, AgentStore.TransactionKind.ADMISSION, "RUNNING", 1, JsonObject.empty(), writes, List.of(),
                Optional.empty(), null, null, ACCEPTED_AT);
    }
}

