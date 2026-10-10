// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.interaction;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次等待回答的幂等提交信封，承载宿主回执所需元数据而不解释其命名空间。
 *
 * @param answerId 答案标识
 * @param interactionId 交互标识
 * @param expectedInteractionRevision 预期交互修订版本
 * @param operationKey 业务操作标识，重试时复用以识别同一操作
 * @param answers 答案集合
 * @param hostMetadata 宿主元数据
 * @param occurredAt 事件发生时间
 */
public record InteractionAnswerSubmission(String answerId,
                                          String interactionId,
                                          long expectedInteractionRevision,
                                          String operationKey,
                                          List<InteractionAnswer> answers,
                                          Map<String, String> hostMetadata,
                                          Instant occurredAt) {
    /**
     * 冻结完整答案和 CAS 版本，避免部分回答被作为可恢复提交持久化。
     */
    public InteractionAnswerSubmission {
        answerId = ContractChecks.identifier(answerId, "answerId");
        interactionId = ContractChecks.identifier(interactionId, "interactionId");
        if (expectedInteractionRevision < 0) {
            throw new IllegalArgumentException("negative expected wait revision");
        }
        operationKey = ContractChecks.identifier(operationKey, "operationKey");
        answers = List.copyOf(Objects.requireNonNull(answers, "answers"));
        if (answers.isEmpty() || answers.stream().anyMatch(Objects::isNull)
            || answers.stream().map(InteractionAnswer::questionId).distinct().count() != answers.size()) {
            throw new IllegalArgumentException("invalid answer submission");
        }
        hostMetadata = ContractChecks.hostMetadata(hostMetadata, 32, 16_384);
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

}
