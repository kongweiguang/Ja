// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.experience.adapter.out.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestion;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestionType;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionWait;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ControlPayloadCodec;
import io.github.kongweiguang.ja.agent.example.springboot.experience.infrastructure.json.JacksonExperienceJsonCodec;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证等待回读按持久游标遍历全部页面，避免旧分页上限丢失已回答的等待。 */
final class ExperienceExtensionTest {
    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");
    private static final String CALL_ID = "call_target";

    /**
     * 确保澄清答案在第十七页仍可恢复，历史变长不能把已持久答案变成工具失败。
     */
    @Test
    void readsAnsweredWaitBeyondFormerPageLimit() {
        AgentStore.StoredRecord waitRecord = answeredWaitRecord(17);
        AtomicInteger pageCount = new AtomicInteger();
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(
                new AgentStore.ConversationKey("tenant_test", "thread_test", "user_test"), "turn_test");
        AgentStore store = (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[] {AgentStore.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("loadExecution")) {
                        return Optional.of(new AgentStore.ExecutionSnapshot(execution, 1, "RUNNING", 1,
                                1, JsonObjects.builder().build(), 17, NOW, NOW));
                    }
                    if (!method.getName().equals("readRecords")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    AgentStore.RecordQuery query = (AgentStore.RecordQuery) arguments[0];
                    int pageIndex = pageCount.getAndIncrement();
                    assertEquals(execution, query.execution());
                    assertEquals(pageIndex, query.afterSequence());
                    assertEquals(17, query.throughSequence());
                    assertEquals(256, query.limit());
                    long sequence = query.afterSequence() + 1;
                    List<AgentStore.StoredRecord> records = pageIndex == 16
                            ? List.of(waitRecord)
                            : List.of(new AgentStore.StoredRecord("record_page_" + sequence, "agent.message", 1,
                                    sequence, JsonObjects.builder().build(), NOW));
                    return new AgentStore.RecordPage(records, sequence, pageIndex < 16);
                });
        ControlPayloadCodec codec = new ControlPayloadCodec(new ObjectMapper());
        String answer = ExperienceExtension.readAnswer(store, codec,
                new JacksonExperienceJsonCodec(new ObjectMapper()), CALL_ID,
                new AgentIdentity("tenant_test", "thread_test", "turn_test", "user_test"),
                CancellationToken.none());

        assertEquals(17, pageCount.get());
        assertTrue(answer.contains("persisted answer"), answer);
    }

    /**
     * 用真实等待 codec 构造记录，避免测试替代持久化负载的字段约束。
     */
    private static AgentStore.StoredRecord answeredWaitRecord(long sequence) {
        InteractionQuestion question = new InteractionQuestion("question_test", "What did you choose?",
                InteractionQuestionType.TEXT, List.of(), true, true);
        InteractionWait wait = new InteractionWait("interaction_test", CALL_ID, "operation_test",
                JsonObjects.builder().putText("kind", "input").build(), List.of(question),
                List.of(new InteractionAnswer("question_test", List.of(), "persisted answer", false)),
                InteractionStatus.ANSWERED, 1, NOW, NOW);
        AgentStore.RecordWrite write = new ControlPayloadCodec(new ObjectMapper())
                .encodeWait("record_wait_test", wait, NOW);
        return new AgentStore.StoredRecord(write.recordId(), write.recordType(), write.payloadSchemaVersion(),
                sequence, write.payload(), write.occurredAt());
    }
}
