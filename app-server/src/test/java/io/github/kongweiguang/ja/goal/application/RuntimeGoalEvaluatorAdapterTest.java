// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeResolver;
import io.github.kongweiguang.ja.goal.port.out.GoalEvaluatorPort;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证独立 evaluator 输入不依赖 Native Image 中的 record 反射序列化。 */
final class RuntimeGoalEvaluatorAdapterTest {
    /** 显式字段映射同时覆盖 Goal-only、criteria 与证据，避免 Native metadata 漂移。 */
    @Test
    void encodesEvaluatorInputWithoutReflectiveRecordSerialization() throws Exception {
        ObjectMapper json = new ObjectMapper();
        RuntimeGoalEvaluatorAdapter adapter = new RuntimeGoalEvaluatorAdapter(proxy(ModelPort.class),
                proxy(TurnRuntimeResolver.class), proxy(ConversationRepository.class),
                proxy(WorkspaceUseCase.class), json, Clock.systemUTC());
        GoalEvaluatorPort.Request request = new GoalEvaluatorPort.Request("goal_test", "thr_test", 3,
                null, "run_test", "provider_test", "model_test", "持续完成验收", null,
                List.of(new GoalEvaluatorPort.Criterion("criterion_main", "测试通过", true)),
                List.of(new GoalEvaluatorPort.EvidenceDigest("criterion_main", "tool_result", "call_test",
                        "测试成功", "a".repeat(64))));

        var root = json.readTree(adapter.encodeInput(request));

        assertEquals("goal_test", root.path("goalId").textValue());
        assertEquals(3, root.path("goalDefinitionRevision").longValue());
        assertEquals("criterion_main", root.path("criteria").get(0).path("criterionId").textValue());
        assertTrue(root.path("criteria").get(0).path("required").booleanValue());
        assertEquals("call_test", root.path("evidence").get(0).path("sourceId").textValue());
        assertFalse(root.has("plan"));
    }

    /** 构造期未使用的依赖以严格接口代理占位，任何意外调用都会暴露测试边界扩大。 */
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, arguments) -> {
                    throw new AssertionError("unexpected dependency call: " + method.getName());
                });
    }
}
