// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import java.util.Optional;

/**
 * 为普通用户准入解析可选的宿主业务事务参与者，不让 Conversation 依赖 Task 等业务模型。
 */
@FunctionalInterface
public interface AdmissionParticipantResolver {
    /**
     * 按冻结的 Ja 准入事实选择 staged 参与者；返回空表示本次 Thread 没有该业务投影。
     */
    Optional<JaAdmissionTransactionParticipant> resolve(AdmissionFacts facts);
}
