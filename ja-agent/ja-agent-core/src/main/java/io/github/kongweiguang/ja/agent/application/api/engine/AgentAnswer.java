// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.api.engine;

import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 对已持久等待的类型化回答，绑定明确等待身份和可重试操作键。
 *
 * @param interactionId 交互标识
 * @param operationKey 业务操作标识，重试时复用以识别同一操作
 * @param expectedInteractionRevision 预期交互修订版本
 * @param answers 答案集合
 * @param hostMetadata 宿主元数据
 */
public record AgentAnswer(String interactionId,
                          String operationKey,
                          long expectedInteractionRevision,
                          List<InteractionAnswer> answers,
                          Map<String, String> hostMetadata) {
    /**
     * 在交给执行服务前冻结完整回答集合及对应等待身份。
     */
    public AgentAnswer {
        interactionId = ContractChecks.identifier(interactionId, "interactionId");
        operationKey = ContractChecks.identifier(operationKey, "operationKey");
        if (expectedInteractionRevision < 0) {
            throw new IllegalArgumentException("invalid expected wait revision");
        }
        answers = List.copyOf(Objects.requireNonNull(answers, "answers"));
        if (answers.isEmpty() || answers.size() > 3) {
            throw new IllegalArgumentException("invalid answers");
        }
        Objects.requireNonNull(hostMetadata, "hostMetadata");
        if (hostMetadata.size() > 32) {
            throw new IllegalArgumentException("too many answer metadata entries");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        hostMetadata.forEach((key, value) -> copy.put(ContractChecks.identifier(key, "metadata key"),
                ContractChecks.text(value, "metadata value", 4096, true)));
        if (copy.entrySet().stream().mapToInt(entry -> entry.getKey().length() + entry.getValue().length()).sum() > 16384) {
            throw new IllegalArgumentException("answer metadata exceeds the size bound");
        }
        hostMetadata = Map.copyOf(copy);
    }
}
