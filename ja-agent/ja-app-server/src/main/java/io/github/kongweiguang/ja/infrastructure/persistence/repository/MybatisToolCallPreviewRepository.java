// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;
import io.github.kongweiguang.ja.conversation.application.gateway.ToolCallPreviewReadPort;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.ToolCallPreviewMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.store.ToolPresentationCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.List;
import java.util.Objects;

/**
 * 按事件来源读取 Ja 工具提案，不从最终 Tool 行反推模型刚返回时的调用批次。
 */
public final class MybatisToolCallPreviewRepository implements ToolCallPreviewReadPort {
    private static final String TENANT_ID = "ja.local";

    private final MybatisUnitOfWork transactions;
    private final ToolPresentationCodec presentations;

    /**
     * 复用唯一 MyBatis transaction owner，并用展示 codec 验证持久 JSON 后再交给事件桥。
     */
    public MybatisToolCallPreviewRepository(SqlSessionFactory sessions,
                                            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        transactions = new MybatisUnitOfWork(Objects.requireNonNull(sessions, "sessions"));
        presentations = new ToolPresentationCodec(Objects.requireNonNull(mapper, "mapper"));
    }

    /**
     * 测试显式注入 SQLite owner；生产写入仍由 AgentStore participant 同事务执行。
     */
    public MybatisToolCallPreviewRepository(SqlSessionFactory sessions,
                                            com.fasterxml.jackson.databind.ObjectMapper mapper,
                                            MybatisUnitOfWork.SessionOwner owner) {
        transactions = new MybatisUnitOfWork(Objects.requireNonNull(sessions, "sessions"),
                Objects.requireNonNull(owner, "owner"));
        presentations = new ToolPresentationCodec(Objects.requireNonNull(mapper, "mapper"));
    }

    /**
     * MyBatis 行映射器已按字段构造值对象；此处再验证 presentation JSON 不含非法生命周期。
     */
    private static ToolCallPreview toPreview(ToolCallPreviewMapper.ToolCallPreviewRow row) {
        try {
            return new ToolCallPreview(row.callId(), row.ordinal(), row.toolName(), row.presentationJson(),
                    row.sourceEventId(), row.sourceEventSequence());
        } catch (IllegalArgumentException invalid) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "tool call preview row is invalid", invalid);
        }
    }

    /**
     * 读取键使用 Core 允许的完整标识，不要求 Provider 原生 callId 带 Ja 前缀。
     */
    private static void requireIdentifier(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("invalid " + name);
        }
    }

    /**
     * 用完整执行键和事件 watermark 读取原子提案批次，拒绝跨租户或不匹配的来源记录。
     */
    @Override
    public List<ToolCallPreview> read(AgentStore.ExecutionKey execution, String assistantMessageRecordId,
                                      String eventId, long eventSequence) {
        Objects.requireNonNull(execution, "execution");
        if (!TENANT_ID.equals(execution.conversation().tenantId()) || eventSequence < 1) {
            throw new IllegalArgumentException("invalid tool preview source");
        }
        requireIdentifier(assistantMessageRecordId, "assistantMessageRecordId");
        requireIdentifier(eventId, "eventId");
        return transactions.required(mappers -> {
            List<ToolCallPreview> values = mappers.toolCallPreviews().selectBatch(
                            new ToolCallPreviewMapper.ToolCallPreviewKey(
                                    execution.conversation().threadId(), execution.turnId(),
                                    assistantMessageRecordId, eventId, eventSequence))
                    .stream().map(MybatisToolCallPreviewRepository::toPreview).toList();
            List<ToolCallPreview> complete = ToolCallPreviewReadPort.requireCompleteBatch(values);
            for (ToolCallPreview preview : complete) {
                if (!eventId.equals(preview.sourceEventId()) || preview.sourceEventSequence() > eventSequence) {
                    throw new StorageException(StorageException.Code.INVALID_STATE,
                            "tool call preview source exceeds requested event watermark");
                }
                ToolPresentation decoded = presentations.read(preview.presentationJson());
                if (decoded.status() != ToolPresentation.Status.PENDING) {
                    throw new StorageException(StorageException.Code.INVALID_STATE,
                            "tool call preview is not a pending proposal");
                }
            }
            return complete;
        });
    }
}
