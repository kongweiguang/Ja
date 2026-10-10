// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.api.engine;

import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 回答是可重试事务命令，入队后调用方不能改变内容或扩大宿主元数据。 */
class AgentAnswerTest {
    /** 防御性复制覆盖集合与返回视图，避免异步结算读取到不同于指纹的答案。 */
    @Test void freezesAnswersAndHostMetadata() {
        ArrayList<InteractionAnswer> answers = new ArrayList<>(List.of(answer()));
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>(Map.of("ja.operation", "first"));
        AgentAnswer command = new AgentAnswer("interaction_one", "answer_one", 0, answers, metadata);
        answers.clear();
        metadata.put("ja.operation", "second");
        assertEquals(List.of(answer()), command.answers());
        assertEquals("first", command.hostMetadata().get("ja.operation"));
        assertThrows(UnsupportedOperationException.class, () -> command.answers().clear());
        assertThrows(UnsupportedOperationException.class, () -> command.hostMetadata().clear());
    }

    /** 负版本、空回答与超限载荷不能进入执行调度后才发现无效。 */
    @Test void rejectsInvalidTransactionEnvelope() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentAnswer("interaction_one", "answer_one", -1, List.of(answer()), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentAnswer("interaction_one", "answer_one", 0, List.of(), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentAnswer("interaction_one", "answer_one", 0, List.of(answer()), Map.of("key", "x".repeat(4097))));
        LinkedHashMap<String, String> metadata = new LinkedHashMap<String, String>();
        for (int index = 0; index < 5; index++) {
            metadata.put("key" + index, "x".repeat(4096));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new AgentAnswer("interaction_one", "answer_one", 0, List.of(answer()), metadata));
    }

    /** 测试使用真实结构化选项，避免自由文本规则掩盖信封边界。 */
    private static InteractionAnswer answer() {
        return new InteractionAnswer("question_approval", List.of("option_approve"), null, false);
    }
}
