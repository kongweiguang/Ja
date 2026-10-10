// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway.store;

import io.github.kongweiguang.ja.agent.domain.json.JsonObject;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Store 已校验核心事实后的同步事务视图；资源句柄借用期限止于当前回调。
 *
 * @param resources 当前事务的基础设施资源句柄
 * @param execution 执行身份
 * @param fencingToken 执行租约令牌
 * @param expectedThreadRevision 期望会话版本
 * @param expectedMutationVersion 期望变更版本
 * @param committedConversationRevision 提交后的会话版本
 * @param committedMutationVersion 提交后的变更版本
 * @param firstEventSequence 首个事件序号
 * @param lastEventSequence 最后事件序号
 * @param kind 事务类型
 * @param state 执行状态
 * @param cursorSchemaVersion 游标格式版本
 * @param cursor 游标对象
 * @param records 提交的事实记录
 * @param events 提交的领域事件
 * @param operation 可选操作身份
 * @param interactionId 交互身份
 * @param answerId 回答身份
 * @param occurredAt 事实发生时间
 */
public record AgentStoreTransactionContext(TransactionResource resources,
                                           AgentStore.ExecutionKey execution,
                                           long fencingToken,
                                           long expectedThreadRevision,
                                           long expectedMutationVersion,
                                           long committedConversationRevision,
                                           long committedMutationVersion,
                                           long firstEventSequence,
                                           long lastEventSequence,
                                           AgentStore.TransactionKind kind,
                                           String state,
                                           int cursorSchemaVersion,
                                           JsonObject cursor,
                                           List<AgentStore.RecordWrite> records,
                                           List<AgentStore.EventWrite> events,
                                           Optional<AgentStore.OperationIdentity> operation,
                                           String interactionId,
                                           String answerId,
                                           Instant occurredAt) {
    /**
     * 限定同步借用期间和 fencing 身份，防止宿主回调把写入挂到另一执行或版本。
     */
    public AgentStoreTransactionContext {
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(execution, "execution");
        if (fencingToken < 0 || expectedThreadRevision < 0 || expectedMutationVersion < -1
                || committedConversationRevision < 1 || committedMutationVersion < 1
                || firstEventSequence < 0 || lastEventSequence < firstEventSequence
                || cursorSchemaVersion < 1) {
            throw new IllegalArgumentException("invalid transaction context");
        }
        Objects.requireNonNull(kind, "kind");
        if (state == null || state.isBlank() || state.length() > 256 || state.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid state");
        }
        Objects.requireNonNull(cursor, "cursor");
        records = List.copyOf(Objects.requireNonNull(records, "records"));
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        operation = Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (kind == AgentStore.TransactionKind.ADMISSION && (fencingToken != 0 || expectedMutationVersion != -1)) {
            throw new IllegalArgumentException("admission cannot carry an execution lease");
        }
        if (kind == AgentStore.TransactionKind.CONTROL && (fencingToken != 0 || expectedMutationVersion < 0)) {
            throw new IllegalArgumentException("control transaction cannot carry an execution lease");
        }
        if (kind != AgentStore.TransactionKind.ADMISSION && kind != AgentStore.TransactionKind.CONTROL
                && fencingToken < 1) {
            throw new IllegalArgumentException("execution commit requires a fencing token");
        }
    }
}
