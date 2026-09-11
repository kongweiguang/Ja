// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.interaction;

import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.conversation.port.out.AgentCapability;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.port.out.InteractionRepository;
import io.github.kongweiguang.ja.conversation.port.out.TaskCapabilityCeilingPort;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证公共提问能力以 Turn 来源为硬边界，不把用户普通模式偏好误用于 Plan-owned 执行。 */
final class InteractionCapabilityTest {
    /** Plan-owned 执行即使注入策略错误返回 false，也必须保留 request_user_input。 */
    @Test
    void planExecutionOverridesDisabledPolicy() {
        InteractionCapability capability = new InteractionCapability(service(), ignored -> false);

        assertEquals(1, capability.prepare(request(CollaborationMode.DEFAULT, TurnOrigin.PLAN_EXECUTION,
                false, Optional.empty())).tools().size());
    }

    /** 普通模式仍服从关闭设置，防止 Plan 特例扩大到所有来源。 */
    @Test
    void ordinaryTurnRespectsDisabledPolicy() {
        InteractionCapability capability = new InteractionCapability(service(), ignored -> false);

        assertEquals(0, capability.prepare(request(CollaborationMode.DEFAULT, TurnOrigin.USER,
                false, Optional.empty())).tools().size());
    }

    /** 只读规划与子代理边界分别验证：规划可提问，子代理不能直接抢占用户卡片。 */
    @Test
    void planningAllowsQuestionsButSubagentDoesNot() {
        InteractionCapability capability = new InteractionCapability(service(), ignored -> false);

        assertEquals(1, capability.prepare(request(CollaborationMode.PLAN, TurnOrigin.USER,
                false, Optional.empty())).tools().size());
        assertEquals(0, capability.prepare(request(CollaborationMode.PLAN, TurnOrigin.CHILD_TASK,
                false, Optional.of(TaskCapabilityCeilingPort.Kind.SUBAGENT))).tools().size());
    }

    /** 构造真实服务对象但不提供可执行的外部端口，prepare 阶段不得触发任何 Repository 调用。 */
    private static InteractionService service() {
        return new InteractionService(unused(InteractionRepository.class), unused(ConversationRepository.class),
                Clock.systemUTC());
    }

    /** 用 fail-fast 动态代理隔离目录准入测试与 SQLite 及 Conversation 事务。 */
    @SuppressWarnings("unchecked")
    private static <T> T unused(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
                (proxy, method, arguments) -> {
                    throw new AssertionError("unexpected repository call: " + method.getName());
                });
    }

    /** 创建最小冻结请求，测试只改变来源、协作模式、设置和子代理身份。 */
    private static AgentCapability.Request request(CollaborationMode mode, TurnOrigin origin,
                                                    boolean clarification,
                                                    Optional<TaskCapabilityCeilingPort.Kind> taskKind) {
        return new AgentCapability.Request("thr_interaction", "turn_interaction", Path.of(".").toAbsolutePath(),
                "ws_interaction", new ThreadPreferences("provider_interaction", "model_interaction", "medium",
                AccessMode.FULL_ACCESS, mode, ThreadPreferences.TitleSource.MANUAL), "cfg_" + "a".repeat(64),
                clarification, Instant.parse("2026-09-10T12:00:00Z"), origin, taskKind);
    }
}
