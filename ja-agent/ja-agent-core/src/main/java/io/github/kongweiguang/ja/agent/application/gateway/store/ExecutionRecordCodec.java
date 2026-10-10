// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.gateway.store;



import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.conversation.ContextInstruction;
import io.github.kongweiguang.ja.agent.domain.conversation.UserMessage;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFact;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 隔离持久 JSON 与执行语义，应用用例不依赖具体 JSON 库或基础设施类。
 */
public interface ExecutionRecordCodec {
    /**
     * 唯一会话级冻结上下文事实的类型，应用记录与持久编解码共用该常量。
     */
    String INITIAL_CONTEXT_SEED_RECORD_TYPE = "agent.context.seed";
    /**
     * Core 自有上下文记录类型只由各自 decoder 接受，Ja 投影不另建字段版本表。
     */
    String CONTEXT_INSTRUCTION_RECORD_TYPE = "agent.context.instruction";
    String CONTEXT_CHECKPOINT_RECORD_TYPE = "agent.context.checkpoint";
    String CONTEXT_PROJECTION_RECORD_TYPE = "agent.context.projection";
    String CONTEXT_COMPACTION_RECORD_TYPE = "agent.context.compaction";
    /**
     * 模型能力快照必须由同一 codec 负责版本、记录身份和字段闭集。
     */
    String MODEL_CAPABILITIES_RECORD_TYPE = "agent.model.capabilities";
    int MODEL_CAPABILITIES_SCHEMA_VERSION = 3;

    /**
     * 提交身份由事务所有者决定，编码器不能为重试重新分配事实键。
     */
    AgentStore.RecordWrite encode(String recordId, ExecutionFact fact, Instant occurredAt);

    /**
     * 只接受已知版本与严格字段，损坏记录不得被解释成默认成功结果。
     */
    ExecutionFact decode(AgentStore.StoredRecord record);

    /**
     * 将滚动摘要保存为游标中的强类型 JSON 文本，应用编排不依赖具体序列化框架。
     */
    String encodeSummaryDocument(SummaryDocument summary);

    /**
     * 严格恢复游标中的摘要文档，损坏进度不得回退为默认空摘要。
     */
    SummaryDocument decodeSummaryDocument(String encoded);

    /**
     * 准入保留用户消息原文与身份，不把内部续跑指令伪造成用户消息。
     */
    AgentStore.RecordWrite encodeUser(String recordId, String messageId, UserContent content, Instant occurredAt);

    /**
     * 恢复时严格要求用户角色，防止把工具结果或模型调用作为用户输入重放。
     */
    UserMessage decodeUser(AgentStore.StoredRecord record);

    /**
     * 队列输入尚未消费时独立保存，不能提前进入模型历史。
     */
    AgentStore.RecordWrite encodeQueuedUser(String recordId, String messageId, UserContent content, Instant occurredAt);

    /**
     * 只恢复待消费输入，禁止将普通历史消息误认为可再次投递的队列项。
     */
    UserMessage decodeQueuedUser(AgentStore.StoredRecord record);

    /**
     * 内部续跑文本独立编码，不向 UI 或调用方冒充用户原始输入。
     */
    AgentStore.RecordWrite encodeInstruction(String recordId, ContextInstruction instruction, Instant occurredAt);

    /**
     * 恢复内部指令时保留原身份和正文，禁止从当前应用配置重新生成历史。
     */
    ContextInstruction decodeInstruction(AgentStore.StoredRecord record);

    /**
     * 编码会话级压缩检查点；执行归属仍由外层事务记录，不从消息时间推断。
     */
    AgentStore.RecordWrite encodeCheckpoint(String recordId, CheckpointStore.ContextCheckpoint checkpoint,
                                            Instant occurredAt);

    /**
     * 严格恢复检查点摘要、保留边界和用量，不因缺少字段重建默认摘要。
     */
    CheckpointStore.ContextCheckpoint decodeCheckpoint(AgentStore.StoredRecord record);

    /**
     * 编码首次请求前冻结的上下文工具投影选择，防止重启后按变化的策略重新挑选。
     */
    AgentStore.RecordWrite encodeProjection(String recordId, CheckpointStore.ProjectionSnapshot projection,
                                            Instant occurredAt);

    /**
     * 严格恢复投影阶段及已持久化选择，未知来源版本不能退回内存默认值。
     */
    CheckpointStore.ProjectionSnapshot decodeProjection(AgentStore.StoredRecord record);

    /**
     * 将父上下文作为首次准入背景事实保存，不伪造当前执行的历史消息或 Checkpoint。
     */
    AgentStore.RecordWrite encodeInitialContextSeed(String recordId, InitialContextSeed seed, Instant occurredAt);

    /**
     * 恢复会话级唯一 Seed；记录类型或 schema 不匹配时必须拒绝而不能按空背景继续。
     */
    InitialContextSeed decodeInitialContextSeed(AgentStore.StoredRecord record);

    /**
     * 压缩阶段是可恢复 Core context 事实，不能由 Session 与取消结算分别维护 JSON schema。
     */
    AgentStore.RecordWrite encodeContextCompaction(String recordId, ContextCompaction value, Instant occurredAt);

    /**
     * 严格恢复压缩阶段标记，错误类型、版本和字段必须失败。
     */
    ContextCompaction decodeContextCompaction(AgentStore.StoredRecord record);

    /**
     * 为 Ja 事务提供 Core context 事实的严格闭集验证，不暴露可被投影层改写的数据。
     */
    void validateContextRecord(AgentStore.StoredRecord record);

    /**
     * 在 Provider 派发前保存本轮冻结能力，并使用执行身份派生 recordId。
     */
    AgentStore.RecordWrite encodeModelCapabilities(AgentStore.ExecutionKey execution,
                                                   ModelCapabilities value, Instant occurredAt);

    /**
     * 按执行身份和冻结 messageId 验证模型能力快照，供恢复和事务参与者共用。
     */
    ModelCapabilities decodeModelCapabilities(AgentStore.StoredRecord record,
                                              AgentStore.ExecutionKey execution);

    /**
     * 压缩标记只包含重启恢复所需的稳定身份、边界、计量和活动状态。
     *
     * @param id 当前实体的稳定标识
     * @param sourceRevision 来源修订版本
     * @param inputTokensBefore 处理前的输入 Token 数
     * @param active 是否处于活动状态
     */
    record ContextCompaction(String id,
                             long sourceRevision,
                             long inputTokensBefore,
                             boolean active) {
        /**
         * 负水位或缺少身份无法表示已开始的有效压缩阶段。
         */
        public ContextCompaction {
            if (id == null || id.isBlank() || sourceRevision < 0 || inputTokensBefore < 0) {
                throw new IllegalArgumentException("invalid context compaction marker");
            }
        }
    }

    /**
     * 模型请求携带的工具可见集合与摘要来源在派发前冻结。
     *
     * @param messageId 消息标识
     * @param visibleTools 可见工具列表
     * @param checkpointId 检查点标识（可选）
     * @param seedSummaryRef 初始上下文摘要引用（可选）
     */
    record ModelCapabilities(String messageId,
                             List<String> visibleTools,
                             Optional<String> checkpointId,
                             Optional<SeedSummaryReference> seedSummaryRef) {
        /**
         * 保留工具顺序，并禁止重复工具名或相互冲突的摘要来源。
         */
        public ModelCapabilities {
            if (messageId == null || messageId.isBlank())
                {
                    throw new IllegalArgumentException("invalid model message id");
                }
            visibleTools = List.copyOf(Objects.requireNonNull(visibleTools, "visibleTools"));
            checkpointId = Objects.requireNonNull(checkpointId, "checkpointId");
            seedSummaryRef = Objects.requireNonNull(seedSummaryRef, "seedSummaryRef");
            if (checkpointId.isPresent() && seedSummaryRef.isPresent()) {
                throw new IllegalArgumentException("model capability summary sources conflict");
            }
            checkpointId.ifPresent(value -> {
                if (value.isBlank() || value.length() > 256 || value.indexOf('\0') >= 0
                    || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                    throw new IllegalArgumentException("invalid checkpoint reference");
                }
            });
            if (visibleTools.stream().anyMatch(name -> name == null || name.isBlank())
                || visibleTools.stream().distinct().count() != visibleTools.size()) {
                throw new IllegalArgumentException("model capability tools must be unique names");
            }
        }
    }

    /**
     * 父 Seed 摘要引用只核对来源与 ordinal，不作为 child checkpoint 边界。
     *
     * @param sourceIdentity 来源身份
     * @param sourceOrdinal 来源消息的顺序位置
     */
    record SeedSummaryReference(InitialContextSeed.SourceIdentity sourceIdentity,
                                long sourceOrdinal) {
        /**
         * 来源身份必须完整，ordinal 不能使用负值伪造跨会话历史。
         */
        public SeedSummaryReference {
            Objects.requireNonNull(sourceIdentity, "sourceIdentity");
            if (sourceOrdinal < 0) {
                throw new IllegalArgumentException("invalid seed source ordinal");
            }
        }
    }
}

