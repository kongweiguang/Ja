// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.api.engine;

import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 有幂等身份的输入队列变更闭集；每个变更都绑定当前输入项版本。
 */
public sealed interface AgentInputCommand permits AgentInputCommand.Enqueue, AgentInputCommand.Update,
        AgentInputCommand.Delete, AgentInputCommand.Prioritize {
    /**
     * 统一冻结准入时的初始输入批次；身份重复或超过队列容量时整批拒绝，避免 caller 分别校验后产生部分准入。
     */
    static List<Enqueue> freezeInitialQueuedInputs(List<Enqueue> source) {
        List<Enqueue> values = List.copyOf(Objects.requireNonNull(source, "initialQueuedInputs"));
        if (values.size() > AgentInputQueue.MAX_ENTRIES) {
            throw new IllegalArgumentException("too many initial queued inputs");
        }
        if (values.stream().map(Enqueue::inputId).distinct().count() != values.size()
            || values.stream().map(Enqueue::operationKey).distinct().count() != values.size()) {
            throw new IllegalArgumentException("duplicate initial queued input identity");
        }
        return values;
    }

    /**
     * 限制所有命令共用的条目版本契约，队列 revision 由存储事务原子分配。
     */
    private static void requireEntryRevision(long revision) {
        if (revision < 1) {
            throw new IllegalArgumentException("expectedInputRevision must be positive");
        }
    }

    /**
     * 冻结宿主幂等回执所需的数据字段，业务方可在同一 Store 事务中消费该快照。
     */
    private static Map<String, String> freezeMetadata(Map<String, String> source) {
        Objects.requireNonNull(source, "hostMetadata");
        if (source.size() > 32) {
            throw new IllegalArgumentException("too many host metadata entries");
        }
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String name = ContractChecks.identifier(key, "host metadata key");
            String text = ContractChecks.text(value, "host metadata value", 16_384, true);
            if (copy.putIfAbsent(name, text) != null) {
                throw new IllegalArgumentException("duplicate host metadata key");
            }
        });
        if (copy.entrySet().stream().mapToInt(entry -> entry.getKey().length() + entry.getValue().length()).sum()
            > 65_536) {
            throw new IllegalArgumentException("host metadata exceeds the request bound");
        }
        return Map.copyOf(copy);
    }

    /**
     * 返回调用方为超时重试固定的操作键。
     */
    String operationKey();

    /**
     * 保留用户指导、后续输入与宿主消息各自的消费语义。
     */
    enum Kind {
        /**
         * 请求进入下一可用安全点，供用户即时纠正或追加指导。
         */
        STEERING,
        /**
         * 请求等当前回复正常完成后再消费，保留用户明确的后续顺序。
         */
        FOLLOW_UP,
        /**
         * 宿主投递的对话消息进入模型 USER 历史，但沿用当前技能选择。
         */
        MESSAGE
    }

    /**
     * 将新输入加入队列尾部；MESSAGE 进入 USER 历史但不改变活动技能。
     *
     * @param operationKey 业务操作标识，重试时复用以识别同一操作
     * @param inputId 输入标识
     * @param kind 类型分类
     * @param content 内容
     * @param hostMetadata 宿主元数据
     */
    record Enqueue(String operationKey,
                   String inputId,
                   Kind kind,
                   UserContent content,
                   Map<String, String> hostMetadata) implements AgentInputCommand {
        /**
         * 冻结新正文并要求显式选择其安全点语义；MESSAGE 不接受技能选择块。
         */
        public Enqueue {
            operationKey = ContractChecks.identifier(operationKey, "operationKey");
            inputId = ContractChecks.identifier(inputId, "inputId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(content, "content");
            if (kind == Kind.MESSAGE && !content.skillIds().isEmpty()) {
                throw new IllegalArgumentException("message input cannot change selected skills");
            }
            hostMetadata = freezeMetadata(hostMetadata);
        }
    }

    /**
     * 编辑一项尚未消费的输入，并以该项 revision 拒绝陈旧修改。
     *
     * @param operationKey 业务操作标识，重试时复用以识别同一操作
     * @param inputId 输入标识
     * @param expectedInputRevision 预期输入修订版本
     * @param content 内容
     * @param hostMetadata 宿主元数据
     */
    record Update(String operationKey,
                  String inputId,
                  long expectedInputRevision,
                  UserContent content,
                  Map<String, String> hostMetadata) implements AgentInputCommand {
        /**
         * CAS 校验针对队列条目而非整个队列，以允许无冲突的并行 UI 编辑。
         */
        public Update {
            operationKey = ContractChecks.identifier(operationKey, "operationKey");
            inputId = ContractChecks.identifier(inputId, "inputId");
            requireEntryRevision(expectedInputRevision);
            Objects.requireNonNull(content, "content");
            hostMetadata = freezeMetadata(hostMetadata);
        }
    }

    /**
     * 删除指定版本的待处理输入，不影响已在同事务消费的消息。
     *
     * @param operationKey 业务操作标识，重试时复用以识别同一操作
     * @param inputId 输入标识
     * @param expectedInputRevision 预期输入修订版本
     * @param hostMetadata 宿主元数据
     */
    record Delete(String operationKey,
                  String inputId,
                  long expectedInputRevision,
                  Map<String, String> hostMetadata) implements AgentInputCommand {
        /**
         * 拒绝未观察当前条目版本的删除请求。
         */
        public Delete {
            operationKey = ContractChecks.identifier(operationKey, "operationKey");
            inputId = ContractChecks.identifier(inputId, "inputId");
            requireEntryRevision(expectedInputRevision);
            hostMetadata = freezeMetadata(hostMetadata);
        }
    }

    /**
     * 将一项提升到当前输入安全点的队首，并保留可核验的条目版本。
     *
     * @param operationKey 业务操作标识，重试时复用以识别同一操作
     * @param inputId 输入标识
     * @param expectedInputRevision 预期输入修订版本
     * @param hostMetadata 宿主元数据
     */
    record Prioritize(String operationKey,
                      String inputId,
                      long expectedInputRevision,
                      Map<String, String> hostMetadata) implements AgentInputCommand {
        /**
         * 优先级变更与内容编辑使用同一 entry revision，避免覆盖后续用户操作。
         */
        public Prioritize {
            operationKey = ContractChecks.identifier(operationKey, "operationKey");
            inputId = ContractChecks.identifier(inputId, "inputId");
            requireEntryRevision(expectedInputRevision);
            hostMetadata = freezeMetadata(hostMetadata);
        }
    }
}
