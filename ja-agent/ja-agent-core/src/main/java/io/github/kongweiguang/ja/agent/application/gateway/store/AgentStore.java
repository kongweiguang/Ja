// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway.store;

import io.github.kongweiguang.ja.agent.domain.json.JsonObject;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 统一 Agent loop 与存储适配器的持久化事务边界；租户归属、游标、事实、事件和回执必须一起提交，恢复时才能读到完整状态。
 */
public interface AgentStore extends AutoCloseable {
    /**
     * Store pages are soft byte budgets; an oversized first row may be returned alone, up to the payload bound.
     */
    long DEFAULT_PAGE_MAX_BYTES = 4L * 1024 * 1024;

    /**
     * Maximum UTF-8 size of a codec-serialized JSON object accepted by a Store implementation.
     */
    long MAX_JSON_PAYLOAD_UTF8_BYTES = 16L * 1024 * 1024;

    /**
     * 校验长度、空值和控制字符，但不强制 Ja 私有前缀，保持 core 可被其他 Java 项目复用。
     */
    private static String identifier(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 256 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 幂等指纹必须是规范的小写 SHA-256，避免同一请求因格式差异形成不同键值。
     */
    private static String sha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 首轮执行前先建立会话锚点，并把宿主元数据放进同一事务，避免 Ja 线程已创建而通用会话缺失。
     */
    ConversationSnapshot initializeConversation(ConversationInitialization request,
                                                ConversationTransactionParticipant participant);

    /**
     * 没有宿主元数据参与者时也能建立会话，避免调用方为无业务行的场景伪造事务回调。
     */
    default ConversationSnapshot initializeConversation(ConversationInitialization request) {
        return initializeConversation(request, ConversationTransactionParticipant.NONE);
    }

    /**
     * Ja 可在不伪造 Agent Turn 的情况下更新会话元数据；revision CAS 会拒绝覆盖并发修改。
     */
    ConversationSnapshot reviseConversation(ConversationRevision request,
                                            ConversationTransactionParticipant participant);

    /**
     * 没有宿主侧数据时仍可单独推进会话 revision，避免空回调进入事务。
     */
    default ConversationSnapshot reviseConversation(ConversationRevision request) {
        return reviseConversation(request, ConversationTransactionParticipant.NONE);
    }

    /**
     * 准入时将执行、初始用户事实、事件和幂等回执一次写入，避免请求已受理却无法查回初始状态。
     */
    default AdmissionReceipt admit(Admission request) {
        return admit(request, AgentStoreTransactionParticipant.NONE);
    }

    /**
     * 宿主扩展行与核心准入共用存储事务，防止 Ja 关联记录和 Agent 执行一边成功、一边失败。
     */
    AdmissionReceipt admit(Admission request, AgentStoreTransactionParticipant participant);

    /**
     * 一次 CAS 转换连同产出的持久事实共用同一提交，避免游标已前进而消息、用量或事件丢失。
     */
    default CommitReceipt commit(ExecutionCommit request) {
        return commit(request, AgentStoreTransactionParticipant.NONE);
    }

    /**
     * 只有会话版本、执行版本和 fencing token 全部匹配时才让核心及宿主行共同提交，阻止旧执行者落库。
     */
    CommitReceipt commit(ExecutionCommit request, AgentStoreTransactionParticipant participant);

    /**
     * 回答等待请求时同时提交回答、工具事实与新游标，避免恢复后重复处理已结算的等待。
     */
    default CommitReceipt settleWait(WaitSettlement request) {
        return settleWait(request, AgentStoreTransactionParticipant.NONE);
    }

    /**
     * 宿主答案行与等待结算共享一次事务，避免业务侧认为已回答而 loop 仍停在等待态。
     */
    CommitReceipt settleWait(WaitSettlement request, AgentStoreTransactionParticipant participant);

    /**
     * 提交不驱动执行游标的控制事实，避免 answer/input 为了写库抢占当前 Runner 租约。
     */
    default CommitReceipt controlCommit(ControlCommit request) {
        return controlCommit(request, AgentStoreTransactionParticipant.NONE);
    }

    /**
     * 在 Store 事务内以会话、执行状态和 mutation 三重 CAS 保存控制事实及宿主投影。
     */
    CommitReceipt controlCommit(ControlCommit request, AgentStoreTransactionParticipant participant);

    /**
     * 只读取恢复需要的版本和事件水位，历史由调用方按固定水位分页，避免一次装载全部内容。
     */
    Optional<ConversationSnapshot> loadConversation(ConversationKey key);

    /**
     * 仅返回执行状态和恢复游标，事实流单独分页，避免大型对话耗尽内存。
     */
    Optional<ExecutionSnapshot> loadExecution(ExecutionKey key);

    /**
     * 按不可变准入序号读取执行，并固定会话快照水位，避免并发新执行混入恢复页。
     */
    ExecutionPage readExecutions(ConversationKey key, long afterSequence, long throughSequence, int limit);

    /**
     * 实时查询可使用最新水位；恢复调用必须传入快照水位，确保多页结果来自同一逻辑时点。
     */
    default ExecutionPage readExecutions(ConversationKey key, long afterSequence, int limit) {
        return readExecutions(key, afterSequence, Long.MAX_VALUE, limit);
    }

    /**
     * 按 execution 过滤并依会话全局序号读取事实和消息；过滤后的序号允许有间隙，查询从 afterSequence 之后读取到
     * throughSequence（含）为止。非空页的 nextSequence 是最后一条记录序号，空页保持输入游标。
     */
    RecordPage readRecords(RecordQuery query);

    /**
     * 事件需要物化记录正文时按执行身份和记录 ID 精确读取，缺失值保持空结果而不从别处推断。
     */
    Optional<StoredRecord> readRecord(ExecutionKey execution, String recordId);

    /**
     * 按 owner 会话和固定 record 水位读取记录；每条记录都携带明确 execution 身份。
     */
    ConversationRecordPage readConversationRecords(ConversationRecordQuery query);

    /**
     * 按固定事件水位重放持久事件，断线重连不会把后续提交混入同一恢复区间。
     */
    EventPage readEvents(EventQuery query);

    /**
     * 按权威用户 owner 及业务域内操作键读取回执；Store 不把身份或正文派生进查询键，因而重用同一键时可由指纹报告冲突。
     */
    Optional<OperationReceipt> readReceipt(OperationKey key);

    /**
     * 会话执行权只能由一个租约持有者取得；冲突时明确返回当前占用状态，不并行推进同一会话。
     */
    Lease claimLease(LeaseRequest request);

    /**
     * 仅当前 owner 和 fencing token 可续租，过期后被接管的旧执行者不能复活旧代际。
     */
    Lease renewLease(Lease lease, Instant expiresAt);

    /**
     * 释放时校验 owner 与 fencing token，并保留代际记录，保证下次取得的 token 单调递增。
     */
    void releaseLease(Lease lease);

    /**
     * 用稳定提交键找回原回执而不重放记录或外部副作用，处理提交成功但响应丢失的情况。
     */
    Optional<CommitReceipt> reconcile(CommitIdentity identity);

    /**
     * 关闭仅释放存储自身资源；注入的 DataSource 和连接池仍由业务方管理，防止共享资源被误关。
     */
    @Override
    void close();

    /**
     * 标明回调参与的是准入、执行提交还是等待结算，宿主据此选择对应业务行映射。
     */
    enum TransactionKind {
        /**
         * 首次提交执行、初始用户事实及幂等回执的同一准入事务。
         */
        ADMISSION,
        /**
         * 推进已运行执行并同步保存产出事实的普通提交事务。
         */
        COMMIT,
        /**
         * 原子结算挂起请求、回答和相关工具结果的等待事务。
         */
        WAIT_SETTLEMENT,
        /**
         * 不携带执行租约的控制事务；仅追加输入/取消/裁决，或关闭等待并将执行挂起。
         */
        CONTROL
    }

    /**
     * 控制事实分类决定 Store 可允许的 state/cursor 变化边界。
     */
    enum ControlKind {
        /**
         * 追加或调整待消费输入，不直接改变当前游标。
         */
        INPUT,
        /**
         * 记录取消意图，业务副作用是否已发生仍由当前 Runner 核验。
         */
        CANCEL_REQUEST,
        /**
         * 记录暂停意图，由现有执行者在下一个安全点结束当前派发。
         */
        SUSPEND_REQUEST,
        /**
         * 显式恢复时消费已观察到的旧暂停意图，新意图仍由安全点处理。
         */
        SUSPEND_CONSUME,
        /**
         * 原子结算一个 Wait 并让显式恢复获得资格。
         */
        WAIT_SETTLEMENT,
        /**
         * 保存宿主显式授权的工具结果恢复裁决，不自动重放副作用。
         */
        TOOL_RECOVERY
    }

    /**
     * 会话键固定租户、Thread 与权威用户 owner，阻止相同 Thread 被另一用户重新占有。
     *
     * @param tenantId 租户标识，用于隔离不同租户的数据与执行
     * @param threadId 所属对话的稳定标识
     * @param userId 权威用户身份，创建后不可更换
     */
    record ConversationKey(String tenantId,
                           String threadId,
                           String userId) {
        /**
         * 在生成 SQL 前拒绝空白或歧义标识，避免不同数据库对无效键作出不同解释。
         */
        public ConversationKey {
            tenantId = identifier(tenantId, "tenantId");
            threadId = identifier(threadId, "threadId");
            userId = identifier(userId, "userId");
        }
    }

    /**
     * 用于首轮前创建线程身份，使重命名或偏好更新也受会话 CAS 保护。
     *
     * @param conversation 对话
     * @param occurredAt 事件发生时间
     */
    record ConversationInitialization(ConversationKey conversation,
                                      Instant occurredAt) {
        /**
         * 初始化请求将核心会话锚点和宿主创建行放在同一事务，防止留下半初始化线程。
         */
        public ConversationInitialization {
            Objects.requireNonNull(conversation, "conversation");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /**
     * 会话级修改独立于 Agent Turn，但必须携带预期 revision，避免业务元数据静默覆盖。
     *
     * @param conversation 对话
     * @param expectedRevision 提交时预期的修订版本
     * @param occurredAt 事件发生时间
     */
    record ConversationRevision(ConversationKey conversation,
                                long expectedRevision,
                                Instant occurredAt) {
        /**
         * revision 过期时拒绝写入，防止宿主状态在核心会话版本保护之外变更。
         */
        public ConversationRevision {
            Objects.requireNonNull(conversation, "conversation");
            if (expectedRevision < 0) {
                throw new IllegalArgumentException("invalid conversation revision");
            }
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /**
     * 执行键由租户、会话和执行组成，防止相同执行 ID 跨会话串读。
     *
     * @param conversation 对话
     * @param turnId 本次可恢复执行的稳定标识
     */
    record ExecutionKey(ConversationKey conversation,
                        String turnId) {
        /**
         * 强制执行与会话共享租户身份，使数据库复合外键和 API 校验保持一致。
         */
        public ExecutionKey {
            Objects.requireNonNull(conversation, "conversation");
            turnId = identifier(turnId, "turnId");
        }
    }

    /**
     * 可选幂等键必须绑定固定请求指纹，阻止同一键代表不同输入。
     *
     * @param tenantId 租户标识，用于隔离不同租户的数据与执行
     * @param userId 权威用户身份，用于隔离同租户中的不同调用者
     * @param operationKey 业务操作标识，重试时复用以识别同一操作
     */
    record OperationKey(String tenantId,
                        String userId,
                        String operationKey) {
        /**
         * 回执查找按租户和权威用户限定；业务操作键仍在其 operationType 域内稳定去重。
         */
        public OperationKey {
            tenantId = identifier(tenantId, "tenantId");
            userId = identifier(userId, "userId");
            operationKey = identifier(operationKey, "operationKey");
        }
    }

    /**
     * 可选幂等键必须绑定固定请求指纹，阻止同一键代表不同输入。
     *
     * @param operationKey 业务操作标识，重试时复用以识别同一操作
     * @param operationType 操作类型
     * @param fingerprint 当前内容或配置的校验指纹
     * @param receipt 回执对象
     */
    record OperationIdentity(String operationKey,
                             String operationType,
                             String fingerprint,
                             JsonObject receipt) {
        /**
         * 稳定指纹和有限长度回执支持超时后读回结果，同时避免任意响应膨胀持久存储。
         */
        public OperationIdentity {
            operationKey = identifier(operationKey, "operationKey");
            operationType = identifier(operationType, "operationType");
            fingerprint = sha256(fingerprint, "fingerprint");
            Objects.requireNonNull(receipt, "receipt");
        }
    }

    /**
     * 提交身份在调用重试间保持不变，以便区分新提交和响应丢失后的核对。
     *
     * @param execution 执行
     * @param commitKey 提交键
     * @param fingerprint 当前内容或配置的校验指纹
     */
    record CommitIdentity(ExecutionKey execution,
                          String commitKey,
                          String fingerprint) {
        /**
         * 同一提交键不能绑定另一份转换内容，否则无法安全判断是否已执行。
         */
        public CommitIdentity {
            Objects.requireNonNull(execution, "execution");
            commitKey = identifier(commitKey, "commitKey");
            fingerprint = sha256(fingerprint, "fingerprint");
        }
    }

    /**
     * 准入时保存可恢复的初始状态，避免先创建执行、后补游标造成空壳任务。
     *
     * @param execution 执行
     * @param expectedThreadRevision 预期对话修订版本
     * @param cursorSchemaVersion 游标数据结构版本
     * @param state 当前状态
     * @param cursor 游标 JSON 对象
     * @param records 记录列表
     * @param events 事件列表
     * @param operation 操作
     * @param occurredAt 事件发生时间
     */
    record Admission(ExecutionKey execution,
                     long expectedThreadRevision,
                     int cursorSchemaVersion,
                     String state,
                     JsonObject cursor,
                     List<RecordWrite> records,
                     List<EventWrite> events,
                     OperationIdentity operation,
                     Instant occurredAt) {
        /**
         * 复制可变集合并区分新建 revision=0 与既有会话 CAS，避免调用方事后改写事务内容。
         */
        public Admission {
            Objects.requireNonNull(execution, "execution");
            if (expectedThreadRevision < 0 || cursorSchemaVersion < 1) {
                throw new IllegalArgumentException("invalid admission revision or cursor schema");
            }
            state = identifier(state, "state");
            Objects.requireNonNull(cursor, "cursor");
            records = List.copyOf(Objects.requireNonNull(records, "records"));
            events = List.copyOf(Objects.requireNonNull(events, "events"));
            Objects.requireNonNull(operation, "operation");
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /**
     * 持久记录保存 core 编解码器产生的有类型 JSON 和版本号，恢复时按明确类型解码而非反射猜测。
     *
     * @param recordId 记录标识
     * @param recordType 记录类型
     * @param payloadSchemaVersion 负载数据结构版本
     * @param payload 严格 JSON 对象
     * @param occurredAt 事件发生时间
     */
    record RecordWrite(String recordId,
                       String recordType,
                       int payloadSchemaVersion,
                       JsonObject payload,
                       Instant occurredAt) {
        /**
         * 记录类型可扩展，但标识、版本和 JSON 仍受大小及格式约束，避免任意对象序列化污染存储契约。
         */
        public RecordWrite {
            recordId = identifier(recordId, "recordId");
            recordType = identifier(recordType, "recordType");
            if (payloadSchemaVersion < 1) {
                throw new IllegalArgumentException("invalid payload schema version");
            }
            Objects.requireNonNull(payload, "payload");
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /**
     * 事件序号由存储在会话事务内分配，保证并发提交下顺序唯一且连续。
     *
     * @param eventId 事件标识
     * @param eventType 事件类型
     * @param payloadSchemaVersion 负载数据结构版本
     * @param payload 严格 JSON 对象
     * @param occurredAt 事件发生时间
     */
    record EventWrite(String eventId,
                      String eventType,
                      int payloadSchemaVersion,
                      JsonObject payload,
                      Instant occurredAt) {
        /**
         * 不可变事件封套用于断线重放；事件与执行事实同事务提交，游标不会指向未落库事件。
         */
        public EventWrite {
            eventId = identifier(eventId, "eventId");
            eventType = identifier(eventType, "eventType");
            if (payloadSchemaVersion < 1) {
                throw new IllegalArgumentException("invalid payload schema version");
            }
            Objects.requireNonNull(payload, "payload");
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /**
     * 一次转换必须同时描述目标状态及产出的事实，避免调用方拆成多次裸 save。
     *
     * @param execution 执行
     * @param expectedThreadRevision 预期对话修订版本
     * @param expectedState 预期状态
     * @param expectedMutationVersion 预期状态变更版本
     * @param lease 租约
     * @param cursorSchemaVersion 游标数据结构版本
     * @param state 当前状态
     * @param cursor 游标 JSON 对象
     * @param records 记录列表
     * @param events 事件列表
     * @param identity 存储事务提交的稳定身份
     * @param operation 操作（可选）
     * @param occurredAt 事件发生时间
     */
    record ExecutionCommit(ExecutionKey execution,
                           long expectedThreadRevision,
                           String expectedState,
                           long expectedMutationVersion,
                           Lease lease,
                           int cursorSchemaVersion,
                           String state,
                           JsonObject cursor,
                           List<RecordWrite> records,
                           List<EventWrite> events,
                           CommitIdentity identity,
                           Optional<OperationIdentity> operation,
                           Instant occurredAt) {
        /**
         * 请求固定携带会话版本、执行版本和 fencing token，使重试只能重放完全相同的提交。
         */
        public ExecutionCommit {
            Objects.requireNonNull(execution, "execution");
            if (expectedThreadRevision < 0 || expectedMutationVersion < 0 || cursorSchemaVersion < 1) {
                throw new IllegalArgumentException("invalid execution commit fence");
            }
            expectedState = identifier(expectedState, "expectedState");
            Objects.requireNonNull(lease, "lease");
            if (!lease.conversation().equals(execution.conversation())) {
                throw new IllegalArgumentException("lease belongs to another conversation");
            }
            state = identifier(state, "state");
            Objects.requireNonNull(cursor, "cursor");
            records = List.copyOf(Objects.requireNonNull(records, "records"));
            events = List.copyOf(Objects.requireNonNull(events, "events"));
            Objects.requireNonNull(identity, "identity");
            if (!identity.execution().equals(execution)) {
                throw new IllegalArgumentException("commit identity belongs to another execution");
            }
            operation = Objects.requireNonNull(operation, "operation");
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /**
     * 澄清或审批等待可能没有工具结果；若有结果则必须与答案、事件和游标原子结算。
     *
     * @param transition 状态迁移
     * @param interactionId 交互标识
     * @param answerId 答案标识
     * @param toolResultRecordIds 工具结果记录标识集合
     */
    record WaitSettlement(ExecutionCommit transition,
                          String interactionId,
                          String answerId,
                          List<String> toolResultRecordIds) {
        /**
         * 答案绑定具体挂起操作，重复或过期回答不能推进后续执行。
         */
        public WaitSettlement {
            Objects.requireNonNull(transition, "transition");
            interactionId = identifier(interactionId, "interactionId");
            answerId = identifier(answerId, "answerId");
            boolean hasAnswer = false;
            for (RecordWrite record : transition.records()) {
                if (record.recordId().equals(answerId)) {
                    hasAnswer = true;
                    break;
                }
            }
            if (!hasAnswer) {
                throw new IllegalArgumentException("wait settlement must persist its answer record");
            }
            toolResultRecordIds = List.copyOf(Objects.requireNonNull(toolResultRecordIds, "toolResultRecordIds"));
            java.util.Set<String> persistedRecordIds = new java.util.HashSet<>();
            for (RecordWrite record : transition.records()) {
                persistedRecordIds.add(record.recordId());
            }
            java.util.Set<String> uniqueToolResultIds = new java.util.HashSet<>();
            for (String recordId : toolResultRecordIds) {
                if (!persistedRecordIds.contains(recordId) || !uniqueToolResultIds.add(recordId)) {
                    throw new IllegalArgumentException("tool result record ids must be unique and persisted in the transition");
                }
            }
        }
    }

    /**
     * 可在执行租约被另一请求持有时提交的控制 CAS；它不接受 cursor 内容，因而不能推进或覆盖 Loop。
     * 普通控制只增加事实与 mutation 水位；等待结算把状态置为 SUSPENDED 并原样保留游标。
     *
     * @param execution 执行
     * @param expectedThreadRevision 预期对话修订版本
     * @param expectedState 预期状态
     * @param expectedMutationVersion 预期状态变更版本
     * @param kind 类型分类
     * @param records 记录列表
     * @param events 事件列表
     * @param operation 操作
     * @param waitControl 等待控制（可选）
     * @param occurredAt 事件发生时间
     */
    record ControlCommit(ExecutionKey execution,
                         long expectedThreadRevision,
                         String expectedState,
                         long expectedMutationVersion,
                         ControlKind kind,
                         List<RecordWrite> records,
                         List<EventWrite> events,
                         OperationIdentity operation,
                         Optional<WaitControl> waitControl,
                         Instant occurredAt) {
        /**
         * 校验控制类别、CAS 水位与等待参数的闭集对应关系，避免任意无租约状态写入。
         */
        public ControlCommit {
            Objects.requireNonNull(execution, "execution");
            if (expectedThreadRevision < 0 || expectedMutationVersion < 0) {
                throw new IllegalArgumentException("invalid control commit fence");
            }
            expectedState = identifier(expectedState, "expectedState");
            Objects.requireNonNull(kind, "kind");
            records = List.copyOf(Objects.requireNonNull(records, "records"));
            events = List.copyOf(Objects.requireNonNull(events, "events"));
            if (records.isEmpty() || events.isEmpty()) {
                throw new IllegalArgumentException("control commit requires durable records and events");
            }
            Objects.requireNonNull(operation, "operation");
            waitControl = Objects.requireNonNull(waitControl, "waitControl");
            if ((kind == ControlKind.WAIT_SETTLEMENT) != waitControl.isPresent()) {
                throw new IllegalArgumentException("wait metadata must match control kind");
            }
            if (kind == ControlKind.WAIT_SETTLEMENT
                && !(expectedState.equals("WAITING_INPUT") || expectedState.equals("WAITING_APPROVAL")
                     || expectedState.equals("SUSPENDED"))) {
                throw new IllegalArgumentException("wait settlement requires a waiting or suspended execution");
            }
            if (kind != ControlKind.WAIT_SETTLEMENT
                && Set.of("COMPLETED", "FAILED", "CANCELLED").contains(expectedState)) {
                throw new IllegalArgumentException("control request cannot target a terminal execution");
            }
            if (kind == ControlKind.SUSPEND_CONSUME && !expectedState.equals("SUSPENDED")) {
                throw new IllegalArgumentException("suspend consumption requires a suspended execution");
            }
            String requiredRecordType = switch (kind) {
                case CANCEL_REQUEST -> "agent.execution.cancel-request";
                case SUSPEND_REQUEST -> "agent.execution.suspend-request";
                case SUSPEND_CONSUME -> "agent.execution.suspend-consumed";
                case TOOL_RECOVERY -> "agent.tool.recovery.command";
                case INPUT -> null;
                case WAIT_SETTLEMENT -> null;
            };
            if (requiredRecordType != null && records.stream().noneMatch(record ->
                    record.recordType().equals(requiredRecordType))) {
                throw new IllegalArgumentException("control record does not match transaction kind");
            }
            String answerRecordId = waitControl.flatMap(WaitControl::answerId).orElse(null);
            if (answerRecordId != null) {
                boolean answerIncluded = false;
                for (RecordWrite record : records) {
                    if (record.recordId().equals(answerRecordId)) {
                        answerIncluded = true;
                        break;
                    }
                }
                if (!answerIncluded) {
                    throw new IllegalArgumentException("answer record must be part of control transaction");
                }
            }
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /**
     * Wait 的比较版本和结算回执身份；answerId 为空表示关闭问题而非伪造答案。
     *
     * @param interactionId 交互标识
     * @param expectedInteractionRevision 预期交互修订版本
     * @param answerId 答案标识（可选）
     */
    record WaitControl(String interactionId,
                       long expectedInteractionRevision,
                       Optional<String> answerId) {
        /**
         * 关闭与回答共享相同 CAS 键，但明确区分是否存在真实回答记录。
         */
        public WaitControl {
            interactionId = identifier(interactionId, "interactionId");
            if (expectedInteractionRevision < 0) {
                throw new IllegalArgumentException("invalid wait revision");
            }
            answerId = Objects.requireNonNull(answerId, "answerId");
            answerId.ifPresent(value -> identifier(value, "answerId"));
        }
    }

    /**
     * 首次准入和相同幂等重试都返回同一回执，使调用端超时后可安全重试。
     *
     * @param threadRevision 对话修订版本
     * @param mutationVersion 状态变更版本，用于并发提交校验
     * @param firstEventSequence 当前区间的首个事件序号
     * @param lastEventSequence 当前区间的最后事件序号
     * @param receipt 回执 JSON 对象
     * @param newlyAdmitted 是否为本次新准入的执行
     */
    record AdmissionReceipt(long threadRevision,
                            long mutationVersion,
                            long firstEventSequence,
                            long lastEventSequence,
                            JsonObject receipt,
                            boolean newlyAdmitted) {
        /**
         * 回执验证版本和事件范围完整后才返回，避免向上层暴露不可能的恢复游标。
         */
        public AdmissionReceipt {
            if (threadRevision < 1 || mutationVersion < 1 || firstEventSequence < 0
                || lastEventSequence < firstEventSequence) {
                throw new IllegalArgumentException("invalid admission receipt");
            }
            Objects.requireNonNull(receipt, "receipt");
        }
    }

    /**
     * 提交回执保留执行版本和事件区间，支持响应丢失后的精确协调。
     *
     * @param threadRevision 对话修订版本
     * @param mutationVersion 状态变更版本，用于并发提交校验
     * @param firstEventSequence 当前区间的首个事件序号
     * @param lastEventSequence 当前区间的最后事件序号
     * @param receipt 回执 JSON 对象
     * @param newlyCommitted 是否为本次新提交的结果
     */
    record CommitReceipt(long threadRevision,
                         long mutationVersion,
                         long firstEventSequence,
                         long lastEventSequence,
                         JsonObject receipt,
                         boolean newlyCommitted) {
        /**
         * 回执中的版本和事件水位必须相互匹配，调用方不得自行推算已提交位置。
         */
        public CommitReceipt {
            if (threadRevision < 1 || mutationVersion < 1 || firstEventSequence < 0
                || lastEventSequence < firstEventSequence) {
                throw new IllegalArgumentException("invalid commit receipt");
            }
            Objects.requireNonNull(receipt, "receipt");
        }
    }

    /**
     * 会话快照提供恢复所需水位而不装载所有历史，正文按需分页。
     *
     * @param key 键
     * @param revision 当前修订版本
     * @param eventSequence 事件序号
     * @param latestExecutionSequence 最近执行序号
     * @param createdAt 创建时间
     * @param updatedAt 最后更新时间
     * @param recordSequence 记录序号
     */
    record ConversationSnapshot(ConversationKey key,
                                long revision,
                                long eventSequence,
                                long latestExecutionSequence,
                                Instant createdAt,
                                Instant updatedAt,
                                long recordSequence) {
        /**
         * 拒绝负数或倒退的水位，避免从损坏元数据伪造恢复位置。
         */
        public ConversationSnapshot {
            Objects.requireNonNull(key, "key");
            if (revision < 0 || eventSequence < 0 || latestExecutionSequence < 0 || recordSequence < 0) {
                throw new IllegalArgumentException("invalid conversation snapshot");
            }
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }

    }

    /**
     * 执行快照保存状态和带版本游标，core 可显式解码恢复而不依赖存储实现类型。
     *
     * @param key 键
     * @param admissionSequence 准入序号
     * @param state 当前状态
     * @param mutationVersion 状态变更版本，用于并发提交校验
     * @param cursorSchemaVersion 游标数据结构版本
     * @param cursor 游标 JSON 对象
     * @param lastRecordSequence 最近记录的顺序号
     * @param createdAt 创建时间
     * @param updatedAt 最后更新时间
     */
    record ExecutionSnapshot(ExecutionKey key,
                             long admissionSequence,
                             String state,
                             long mutationVersion,
                             int cursorSchemaVersion,
                             JsonObject cursor,
                             long lastRecordSequence,
                             Instant createdAt,
                             Instant updatedAt) {
        /**
         * 读取前校验游标格式与版本，损坏状态会明确失败而不会盲目重放工具。
         */
        public ExecutionSnapshot {
            Objects.requireNonNull(key, "key");
            if (admissionSequence < 1 || mutationVersion < 0 || cursorSchemaVersion < 1
                || lastRecordSequence < 0) {
                throw new IllegalArgumentException("invalid execution snapshot");
            }
            state = identifier(state, "state");
            Objects.requireNonNull(cursor, "cursor");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    /**
     * 执行页按会话准入序号排序并设置上限，避免恢复时无界读取。
     *
     * @param executions 执行列表
     * @param nextSequence 下一序号
     * @param hasMore 是否还有后续分页数据
     */
    record ExecutionPage(List<ExecutionSnapshot> executions,
                         long nextSequence,
                         boolean hasMore) {
        /**
         * 返回值复制为不可变数据，防止 JDBC ResultSet 在连接关闭后泄漏到调用方。
         */
        public ExecutionPage {
            executions = List.copyOf(Objects.requireNonNull(executions, "executions"));
            if (nextSequence < 0) {
                throw new IllegalArgumentException("invalid execution page cursor");
            }
        }
    }

    /**
     * 固定会话全局序号窗口并按 execution 过滤；同时限制条数和序列化后 UTF-8 字节预算。
     *
     * @param execution 执行
     * @param afterSequence 从该序号之后开始读取
     * @param throughSequence 当前快照覆盖到的顺序号
     * @param limit 返回条目数上限
     * @param maxBytes JSON 负载序列化后的 UTF-8 字节预算
     */
    record RecordQuery(ExecutionKey execution,
                       long afterSequence,
                       long throughSequence,
                       int limit,
                       long maxBytes) {
        /**
         * 恢复分页必须固定上界和有限页长，后续并发提交不会污染本次读取。
         */
        public RecordQuery {
            Objects.requireNonNull(execution, "execution");
            if (afterSequence < 0 || throughSequence < afterSequence || limit < 1 || limit > 10_000
                || maxBytes < 1) {
                throw new IllegalArgumentException("invalid record page query");
            }
        }

        /**
         * 使用固定恢复水位和默认 4 MiB 软字节预算；首条记录可单独超过预算但不能超过单项上限。
         */
        public static RecordQuery withDefaultMaxBytes(ExecutionKey execution, long afterSequence,
                                                       long throughSequence, int limit) {
            return new RecordQuery(execution, afterSequence, throughSequence, limit, DEFAULT_PAGE_MAX_BYTES);
        }
    }

    /**
     * 会话记录查询使用全局序号窗口，要求每条记录已绑定执行身份。
     *
     * @param conversation 对话
     * @param afterSequence 从该序号之后开始读取
     * @param throughSequence 当前快照覆盖到的顺序号
     * @param limit 返回条目数上限
     * @param maxBytes JSON 负载序列化后的 UTF-8 字节预算
     */
    record ConversationRecordQuery(ConversationKey conversation,
                                   long afterSequence,
                                   long throughSequence,
                                   int limit,
                                   long maxBytes) {
        /**
         * 查询范围必须来自快照记录水位，禁止用时间猜测迁移记录归属。
         */
        public ConversationRecordQuery {
            Objects.requireNonNull(conversation, "conversation");
            if (afterSequence < 0 || throughSequence < afterSequence || limit < 1 || limit > 10_000
                || maxBytes < 1) {
                throw new IllegalArgumentException("invalid conversation record page query");
            }
        }

        /**
         * 绑定固定记录水位并使用默认页预算，避免分页期间并发记录混入恢复结果。
         */
        public static ConversationRecordQuery withDefaultMaxBytes(ConversationKey conversation,
                                                                   long afterSequence,
                                                                   long throughSequence,
                                                                   int limit) {
            return new ConversationRecordQuery(conversation, afterSequence, throughSequence, limit,
                    DEFAULT_PAGE_MAX_BYTES);
        }
    }

    /**
     * 每条事实都必须绑定所属执行，禁止调用方按时间或记录类型推断 Turn。
     *
     * @param conversation 对话
     * @param execution 执行
     * @param recordId 记录标识
     * @param recordType 记录类型
     * @param payloadSchemaVersion 负载数据结构版本
     * @param sequence 当前记录的顺序号
     * @param payload 严格 JSON 对象
     * @param occurredAt 事件发生时间
     */
    record ConversationRecord(ConversationKey conversation,
                              ExecutionKey execution,
                              String recordId,
                              String recordType,
                              int payloadSchemaVersion,
                              long sequence,
                              JsonObject payload,
                              Instant occurredAt) {
        /**
         * 校验执行归属，避免会话列表把事实投影到错误的 Turn。
         */
        public ConversationRecord {
            Objects.requireNonNull(conversation, "conversation");
            Objects.requireNonNull(execution, "execution");
            if (!execution.conversation().equals(conversation)) {
                throw new IllegalArgumentException("record execution belongs to another conversation");
            }
            recordId = identifier(recordId, "recordId");
            recordType = identifier(recordType, "recordType");
            if (payloadSchemaVersion < 1 || sequence < 1) {
                throw new IllegalArgumentException("invalid conversation record");
            }
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /**
     * 会话记录页从全局序号继续，条目有界且顺序稳定。
     *
     * @param records 记录列表
     * @param nextSequence 下一序号
     * @param hasMore 是否还有后续分页数据
     */
    record ConversationRecordPage(List<ConversationRecord> records,
                                  long nextSequence,
                                  boolean hasMore) {
        /**
         * 分页结果脱离 JDBC 连接仍可安全使用，调用方可以重复读取固定水位。
         */
        public ConversationRecordPage {
            records = List.copyOf(Objects.requireNonNull(records, "records"));
            if (nextSequence < 0) {
                throw new IllegalArgumentException("invalid conversation record page cursor");
            }
        }
    }

    /**
     * 每条持久记录携带存储分配的会话级序号；按 execution 过滤时，记录间不保证序号连续。
     *
     * @param recordId 记录标识
     * @param recordType 记录类型
     * @param payloadSchemaVersion 负载数据结构版本
     * @param sequence 当前记录的顺序号
     * @param payload 严格 JSON 对象
     * @param occurredAt 事件发生时间
     */
    record StoredRecord(String recordId,
                        String recordType,
                        int payloadSchemaVersion,
                        long sequence,
                        JsonObject payload,
                        Instant occurredAt) {
        /**
         * 明确记录序号和负载版本，core 恢复不必推测格式或顺序。
         */
        public StoredRecord {
            recordId = identifier(recordId, "recordId");
            recordType = identifier(recordType, "recordType");
            if (payloadSchemaVersion < 1 || sequence < 1) {
                throw new IllegalArgumentException("invalid stored record");
            }
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /**
     * 记录页只暴露不可变值，避免调用者修改已提交事实。
     *
     * @param records 记录列表
     * @param nextSequence 下一序号
     * @param hasMore 是否还有后续分页数据
     */
    record RecordPage(List<StoredRecord> records,
                      long nextSequence,
                      boolean hasMore) {
        /**
         * 页面只把最后已交付序号作为续读游标，不依赖行数或时间戳续页。
         */
        public RecordPage {
            records = List.copyOf(Objects.requireNonNull(records, "records"));
            if (nextSequence < 0) {
                throw new IllegalArgumentException("invalid record page cursor");
            }
        }
    }

    /**
     * 事件查询使用排他起点、固定包含水位和 UTF-8 字节预算，控制断线恢复内存占用。
     *
     * @param conversation 对话
     * @param afterSequence 从该序号之后开始读取
     * @param throughSequence 当前快照覆盖到的顺序号
     * @param limit 返回条目数上限
     * @param maxBytes JSON 负载序列化后的 UTF-8 字节预算
     */
    record EventQuery(ConversationKey conversation,
                      long afterSequence,
                      long throughSequence,
                      int limit,
                      long maxBytes) {
        /**
         * 事件读取同时限定租户、水位和页大小，避免越权或无界重放。
         */
        public EventQuery {
            Objects.requireNonNull(conversation, "conversation");
            if (afterSequence < 0 || throughSequence < afterSequence || limit < 1 || limit > 10_000
                || maxBytes < 1) {
                throw new IllegalArgumentException("invalid event page query");
            }
        }

        /**
         * 为固定事件水位创建默认 4 MiB 页；首条事件可单独超过软预算但仍受单项上限约束。
         */
        public static EventQuery withDefaultMaxBytes(ConversationKey conversation, long afterSequence,
                                                      long throughSequence, int limit) {
            return new EventQuery(conversation, afterSequence, throughSequence, limit, DEFAULT_PAGE_MAX_BYTES);
        }

        /**
         * 为实时尾读使用最新水位和默认字节预算；需要跨页恢复时应改用固定水位工厂。
         */
        public static EventQuery latestWithDefaultMaxBytes(ConversationKey conversation, long afterSequence,
                                                            int limit) {
            return withDefaultMaxBytes(conversation, afterSequence, Long.MAX_VALUE, limit);
        }
    }

    /**
     * 事件保留产生它的会话版本、执行版本和记录水位，回放不会被后续提交的最新快照改写。
     *
     * @param conversation 对话
     * @param execution 执行
     * @param eventId 事件标识
     * @param eventType 事件类型
     * @param payloadSchemaVersion 负载数据结构版本
     * @param sequence 当前记录的顺序号
     * @param threadRevision 对话修订版本
     * @param mutationVersion 状态变更版本，用于并发提交校验
     * @param lastRecordSequence 最近记录的顺序号
     * @param payload 严格 JSON 对象
     * @param occurredAt 事件发生时间
     */
    record PersistedEvent(ConversationKey conversation,
                          ExecutionKey execution,
                          String eventId,
                          String eventType,
                          int payloadSchemaVersion,
                          long sequence,
                          long threadRevision,
                          long mutationVersion,
                          long lastRecordSequence,
                          JsonObject payload,
                          Instant occurredAt) {
        /**
         * 校验会话归属及各版本水位，避免跨租户事件或负数进度进入持久重放流。
         */
        public PersistedEvent {
            Objects.requireNonNull(conversation, "conversation");
            Objects.requireNonNull(execution, "execution");
            if (!execution.conversation().equals(conversation)) {
                throw new IllegalArgumentException("event execution belongs to another conversation");
            }
            eventId = identifier(eventId, "eventId");
            eventType = identifier(eventType, "eventType");
            if (payloadSchemaVersion < 1 || sequence < 1 || threadRevision < 1
                || mutationVersion < 1 || lastRecordSequence < 0) {
                throw new IllegalArgumentException("invalid stored event");
            }
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /**
     * 事件页只返回已提交内容且有界，慢消费者可从水位重新同步。
     *
     * @param events 事件列表
     * @param nextSequence 下一序号
     * @param hasMore 是否还有后续分页数据
     */
    record EventPage(List<PersistedEvent> events,
                     long nextSequence,
                     boolean hasMore) {
        /**
         * 事件值脱离数据库连接后仍可读取，并保留最后提交序号供精确续读。
         */
        public EventPage {
            events = List.copyOf(Objects.requireNonNull(events, "events"));
            if (nextSequence < 0) {
                throw new IllegalArgumentException("invalid event page cursor");
            }
        }
    }

    /**
     * 重复请求或超时重试时返回原始操作回执，确保响应丢失不会让副作用重做。
     *
     * @param conversation 对话
     * @param turnId 本次可恢复执行的稳定标识
     * @param operationKey 业务操作标识，重试时复用以识别同一操作
     * @param fingerprint 当前内容或配置的校验指纹
     * @param operationType 操作类型
     * @param threadRevision 对话修订版本
     * @param mutationVersion 状态变更版本，用于并发提交校验
     * @param firstEventSequence 当前区间的首个事件序号
     * @param lastEventSequence 当前区间的最后事件序号
     * @param receipt 回执 JSON 对象
     * @param occurredAt 事件发生时间
     */
    record OperationReceipt(ConversationKey conversation,
                            String turnId,
                            String operationKey,
                            String fingerprint,
                            String operationType,
                            long threadRevision,
                            long mutationVersion,
                            long firstEventSequence,
                            long lastEventSequence,
                            JsonObject receipt,
                            Instant occurredAt) {
        /**
         * 保存的响应同时绑定租户与规范化请求指纹，避免回执被误用于另一请求。
         */
        public OperationReceipt {
            Objects.requireNonNull(conversation, "conversation");
            turnId = identifier(turnId, "turnId");
            operationKey = identifier(operationKey, "operationKey");
            fingerprint = sha256(fingerprint, "fingerprint");
            operationType = identifier(operationType, "operationType");
            if (threadRevision < 1 || mutationVersion < 1 || firstEventSequence < 0
                || lastEventSequence < firstEventSequence) {
                throw new IllegalArgumentException("invalid operation receipt versions");
            }
            Objects.requireNonNull(receipt, "receipt");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /**
     * 租约请求声明持有者和正向时长；执行时限由业务策略控制，不与租约混为一谈。
     *
     * @param conversation 对话
     * @param ownerId 所有者标识
     * @param now 当前时间
     * @param expiresAt 过期时间
     */
    record LeaseRequest(ConversationKey conversation,
                        String ownerId,
                        Instant now,
                        Instant expiresAt) {
        /**
         * 拒绝零或负租期；租约到期只允许接管，不代表 loop 达到运行预算。
         */
        public LeaseRequest {
            Objects.requireNonNull(conversation, "conversation");
            ownerId = identifier(ownerId, "ownerId");
            Objects.requireNonNull(now, "now");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (!expiresAt.isAfter(now)) {
                throw new IllegalArgumentException("lease expiry must be in the future");
            }
        }
    }

    /**
     * 每次执行提交都携带持有证明，以便存储端核对当前代际。
     *
     * @param conversation 对话
     * @param ownerId 所有者标识
     * @param fencingToken 执行租约的隔离令牌，用于拒绝过期执行者
     * @param expiresAt 过期时间
     */
    record Lease(ConversationKey conversation,
                 String ownerId,
                 long fencingToken,
                 Instant expiresAt) {
        /**
         * fencing token 持久单调递增，释放后重获也换代，旧执行者因此无法通过 ABA 校验。
         */
        public Lease {
            Objects.requireNonNull(conversation, "conversation");
            ownerId = identifier(ownerId, "ownerId");
            if (fencingToken < 1) {
                throw new IllegalArgumentException("invalid fencing token");
            }
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /**
     * 不确定提交的协调结果保留原提交身份，使调用方能确认同一次转换。
     *
     * @param identity 存储事务提交的稳定身份
     * @param receipt 回执
     */
    record ReconciledCommit(CommitIdentity identity,
                            CommitReceipt receipt) {
        /**
         * 回执身份必须与查询键一致，避免把别的提交成功误认成当前操作。
         */
        public ReconciledCommit {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(receipt, "receipt");
        }
    }

    /**
     * 并发版本过期、标识重用或指纹不匹配统一报告冲突，让调用方修正后重试。
     */
    final class StoreConflictException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        /**
         * 公开冲突不泄漏 SQL 细节，数据库诊断留在 cause 中供运维排查。
         */
        public StoreConflictException(String message) {
            super(message);
        }
    }

    /**
     * 损坏游标、记录或持久化约束被明确归为完整性错误，不能静默修补。
     */
    final class StoreIntegrityException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        /**
         * 保留底层原因供诊断，但不把数据库信息混入模型可见内容。
         */
        public StoreIntegrityException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 存储不可用时明确失败，不自动切换数据库、内存或本地文件模式。
     */
    final class StoreUnavailableException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        /**
         * 底层 SQL 原因可供排障，业务侧只依赖稳定的存储不可用类别。
         */
        public StoreUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 宿主事务参与者失败时保留独立异常类别，使调用方知道整笔事务未完成。
     */
    final class StoreParticipantException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        /**
         * 保留宿主回调异常原因，确保整笔提交可以被准确诊断或安全重试。
         */
        public StoreParticipantException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
