// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.conversation.ContextInstruction;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 单次执行的不可变应用请求，不携带 Workspace 或 Ja 聚合身份。
 *
 * @param identity 包含租户、对话、执行与用户的完整身份
 * @param kind 类型分类
 * @param input 输入内容（可选）
 * @param inputMessageId 输入消息标识
 * @param instruction 指令（可选）
 * @param operation 操作
 * @param contextAttributes 上下文属性集合
 * @param history 历史消息
 * @param acceptedAt 受理时间
 * @param initialQueuedInputs 初始排队输入
 * @param outputSchema 结构化终答 Schema（可选）
 * @param initialContextSeed 创建侧边任务时冻结的初始上下文（可选）
 */
public record AgentExecutionRequest(AgentIdentity identity,
                                    AdmissionKind kind,
                                    Optional<UserContent> input,
                                    String inputMessageId,
                                    Optional<ContextInstruction> instruction,
                                    AgentStore.OperationIdentity operation,
                                    Map<String, String> contextAttributes,
                                    HistorySelection history,
                                    Instant acceptedAt,
                                    List<AgentInputCommand.Enqueue> initialQueuedInputs,
                                    Optional<JsonObject> outputSchema,
                                    Optional<InitialContextSeed> initialContextSeed) {
    /**
     * 冻结会改变终答语义的 Schema；密钥仍留在短期运行时租约中。
     */
    public AgentExecutionRequest {
        Objects.requireNonNull(identity, "identity");
        kind = Objects.requireNonNull(kind, "kind");
        input = Objects.requireNonNull(input, "input");
        instruction = Objects.requireNonNull(instruction, "instruction");
        initialQueuedInputs = AgentInputCommand.freezeInitialQueuedInputs(initialQueuedInputs);
        outputSchema = Objects.requireNonNull(outputSchema, "outputSchema");
        initialContextSeed = Objects.requireNonNull(initialContextSeed, "initialContextSeed");
        if (kind == AdmissionKind.INTERNAL && initialQueuedInputs.stream()
                .anyMatch(queued -> queued.kind() != AgentInputCommand.Kind.MESSAGE)) {
            throw new IllegalArgumentException("internal admission only accepts MESSAGE inputs");
        }
        if (kind == AdmissionKind.COMPACTION && !initialQueuedInputs.isEmpty()) {
            throw new IllegalArgumentException("compaction admission cannot carry initial queued inputs");
        }
        switch (kind) {
            case USER_INPUT -> {
                if (input.isEmpty()) {
                    throw new IllegalArgumentException("user admission requires input");
                }
                inputMessageId = ContractChecks.identifier(inputMessageId, "inputMessageId");
                if (instruction.isPresent()) {
                    throw new IllegalArgumentException("user input cannot include a context instruction");
                }
            }
            case INTERNAL -> {
                if (input.isPresent() || inputMessageId != null || instruction.isEmpty()) {
                    throw new IllegalArgumentException("internal admission requires only a context instruction");
                }
            }
            case COMPACTION -> {
                if (input.isPresent() || inputMessageId != null || instruction.isPresent()) {
                    throw new IllegalArgumentException("compaction admission cannot create prompt messages");
                }
            }
        }
        Objects.requireNonNull(operation, "operation");
        contextAttributes = freezeAttributes(contextAttributes);
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        if (initialContextSeed.isPresent()) {
            InitialContextSeed seed = initialContextSeed.orElseThrow();
            if (kind == AdmissionKind.COMPACTION) {
                throw new IllegalArgumentException("compaction admission cannot carry an initial context seed");
            }
            if (!identity.tenantId().equals(seed.sourceIdentity().tenantId())) {
                throw new IllegalArgumentException("initial context seed belongs to another tenant");
            }
        }
        if (outputSchema.isPresent() && kind != AdmissionKind.USER_INPUT) {
            throw new IllegalArgumentException("output schema is only valid for user input admission");
        }
    }

    /**
     * 限制调用方上下文为有界数据值，业务服务应通过工具注入而不进入恢复状态。
     */
    private static Map<String, String> freezeAttributes(Map<String, String> source) {
        Objects.requireNonNull(source, "contextAttributes");
        if (source.size() > 256) {
            throw new IllegalArgumentException("too many context attributes");
        }
        Map<String, String> values = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String name = ContractChecks.identifier(key, "context attribute key");
            String text = Objects.requireNonNull(value, "context attribute value");
            if (text.length() > 16_384 || text.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("invalid context attribute value");
            }
            if (values.putIfAbsent(name, text) != null)
                {
                    throw new IllegalArgumentException("duplicate context attribute");
                }
        });
        return Map.copyOf(values);
    }

    /**
     * 区分用户输入与宿主驱动的内部续跑，避免用 null 猜测请求来源。
     */
    public enum AdmissionKind {
        /**
         * 用户提供正文并创建一条可见 USER 消息。
         */
        USER_INPUT,
        /**
         * 宿主提供内部上下文指令，不伪造用户输入或 UI 消息。
         */
        INTERNAL,
        /**
         * 复用同一执行与摘要状态机压缩上下文，不创建提示消息。
         */
        COMPACTION
    }

    /**
     * 持久化历史可见性选择；Ja 来源路径由同事务宿主参与者校验。
     *
     * @param mode 模式
     * @param visibleTurnIds 可见执行轮次标识集合
     * @param expectedThreadRevision 预期对话修订版本
     */
    public record HistorySelection(Mode mode,
                                   List<String> visibleTurnIds,
                                   Long expectedThreadRevision) {
        /**
         * 保留显式历史顺序，并把宿主校验的续问绑定到准入前的会话版本。
         */
        public HistorySelection {
            mode = Objects.requireNonNull(mode, "mode");
            visibleTurnIds = List.copyOf(Objects.requireNonNull(visibleTurnIds, "visibleTurnIds"));
            if (visibleTurnIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(visibleTurnIds).size() != visibleTurnIds.size()) {
                throw new IllegalArgumentException("invalid history selection");
            }
            visibleTurnIds = visibleTurnIds.stream()
                    .map(value -> ContractChecks.identifier(value, "visible execution id")).toList();
            if (mode == Mode.EXPLICIT && (expectedThreadRevision == null
                                          || expectedThreadRevision < 0)) {
                throw new IllegalArgumentException("explicit history selection requires a conversation revision");
            }
            if (mode != Mode.EXPLICIT && (!visibleTurnIds.isEmpty() || expectedThreadRevision != null)) {
                throw new IllegalArgumentException("non-explicit history selection cannot carry a partial path");
            }
        }

        /**
         * 使用会话当前的通用可见执行集合。
         */
        public static HistorySelection currentConversation() {
            return new HistorySelection(Mode.CURRENT_CONVERSATION, List.of(), null);
        }

        /**
         * 从空历史开始独立执行。
         */
        public static HistorySelection empty() {
            return new HistorySelection(Mode.EMPTY, List.of(), null);
        }

        /**
         * 保留宿主选择的确切历史路径，并以来源会话版本实施并发保护；空路径表示从零历史开始。
         */
        public static HistorySelection explicit(List<String> turnIds, long expectedThreadRevision) {
            return new HistorySelection(Mode.EXPLICIT, turnIds, expectedThreadRevision);
        }

        /**
         * 历史模式只改变模型可见上下文，持久执行记录始终追加保存。
         */
        public enum Mode {
            /**
             * 使用当前 conversation 中全部可见执行历史。
             */
            CURRENT_CONVERSATION,
            /**
             * 使用调用方冻结的执行 ID 顺序，并以 conversation revision 做并发校验。
             */
            EXPLICIT,
            /**
             * 明确从空上下文开始，不读取已有 conversation 消息。
             */
            EMPTY
        }
    }
}
