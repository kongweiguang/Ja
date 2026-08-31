// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.domain.TurnRuntimeSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ThreadTitlePolicy;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.AttachmentRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.ToolPresentationCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import org.apache.ibatis.session.SqlSessionFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 MyBatis 的 conversation Repository；所有写操作经同一个 Unit of Work 完成。
 */
public final class MybatisConversationRepository implements ConversationRepository {
    private static final long MAX_TURN_ATTACHMENT_BYTES = 250L * 1024 * 1024;
    private final MybatisUnitOfWork transactions;
    private final PersistenceCodec codec;
    private final ToolPresentationCodec presentations;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 生产 factory 必须由 MyBatis-Solon plugin 注入，禁止本类创建 datasource 或 session factory。
     */
    public MybatisConversationRepository(SqlSessionFactory sessions, ObjectMapper objectMapper) {
        transactions = new MybatisUnitOfWork(sessions);
        codec = new PersistenceCodec(objectMapper);
        presentations = new ToolPresentationCodec(objectMapper);
    }

    /**
     * package seam 仅允许真实 SQLite 测试注入 test-source transaction owner。
     */
    public MybatisConversationRepository(SqlSessionFactory sessions, ObjectMapper objectMapper,
                                         MybatisUnitOfWork.SessionOwner owner) {
        transactions = new MybatisUnitOfWork(sessions, owner);
        codec = new PersistenceCodec(objectMapper);
        presentations = new ToolPresentationCodec(objectMapper);
    }

    /**
     * Thread 只能指向已存在 workspace，初始 revision=0 由 schema 固定。
     */
    @Override
    public ThreadSnapshot createThread(ThreadDefinition thread) {
        ensureOpen();
        Objects.requireNonNull(thread, "thread");
        return transactions.required(mapper -> {
            if (mapper.history().selectWorkspace(thread.workspaceId()) == null) notFound("workspace");
            requireChanged(mapper.history().insertThread(new PersistenceRecords.ThreadInsert(
                            thread.threadId(), thread.workspaceId(), thread.title(),
                            thread.preferences().providerId(), thread.preferences().modelId(),
                            thread.preferences().reasoningLevel(), thread.preferences().accessMode().name(),
                            thread.preferences().titleSource().name(),
                            instant(thread.createdAt()))),
                    "thread insert lost");
            return new ThreadSnapshot(thread.threadId(), thread.workspaceId(), thread.title(),
                    thread.preferences(), 0, List.of(), List.of(), thread.createdAt(), thread.createdAt());
        });
    }

    /**
     * Turn、用户 blocks、可选首次标题和唯一 revision CAS 共用事务，队列收到 receipt 后才可执行。
     */
    @Override
    public AdmissionReceipt admit(TurnAdmission admission) {
        ensureOpen();
        Objects.requireNonNull(admission, "admission");
        return transactions.required(mapper -> {
            PersistenceRecords.ThreadRow thread = requireThread(mapper, admission.threadId());
            requireRevision(thread, admission.expectedThreadRevision());
            TurnRuntimeSnapshot runtime = admission.runtime();
            requireChanged(mapper.agent().insertTurn(new PersistenceRecords.TurnInsert(
                    admission.turnId(), admission.threadId(), runtime.providerId(), runtime.modelId(),
                    runtime.provider(), runtime.api(), runtime.upstreamModel(), runtime.reasoningLevel(),
                    runtime.accessMode().name(), runtime.configGeneration(), instant(admission.requestedAt()))),
                    "turn insert lost");
            List<String> attachmentNames = bindAttachments(mapper, thread.workspaceId(), admission);
            long ordinal = mapper.agent().selectNextMessageOrdinal(admission.threadId());
            requireChanged(mapper.agent().insertMessage(new PersistenceRecords.MessageInsert(
                    admission.messageId(), admission.threadId(), admission.turnId(), ordinal,
                    admission.userMessage().role().name(), codec.writeMessage(admission.userMessage()),
                    instant(admission.requestedAt()))), "user message insert lost");
            String visibleUserInput = visibleText(admission.userMessage());
            if (!visibleUserInput.isBlank()) {
                insertTimelineMessage(mapper, admission.messageId(), admission.threadId(), admission.turnId(),
                        "USER_INPUT", visibleUserInput, null, admission.requestedAt());
            }
            String provisionalTitle = null;
            if (ordinal == 1 && "PLACEHOLDER".equals(thread.titleSource())) {
                String candidate = ThreadTitlePolicy.provisionalTitle(visibleUserInput, attachmentNames);
                if (!candidate.isBlank()) provisionalTitle = candidate;
            }
            requireChanged(mapper.history().compareAndSetThreadAdmission(
                    new PersistenceRecords.ThreadAdmissionCas(admission.threadId(), runtime.providerId(),
                            runtime.modelId(), runtime.reasoningLevel(), runtime.accessMode().name(), provisionalTitle,
                            admission.expectedThreadRevision(), instant(admission.requestedAt()))),
                    "thread admission revision lost");
            return new AdmissionReceipt(admission.threadId(), admission.turnId(),
                    admission.expectedThreadRevision() + 1, 0, provisionalTitle);
        });
    }

    /**
     * 在 Turn admission 事务内先校验全部配额和 Workspace，再执行 DRAFT→BOUND 与关系插入；
     * 任一附件失败会连同 Turn、消息和偏好 CAS 一起回滚。
     */
    private static List<String> bindAttachments(PersistenceMappers mapper, String workspaceId,
                                                TurnAdmission admission) {
        long totalBytes = 0;
        List<String> displayNames = new java.util.ArrayList<>(admission.attachmentIds().size());
        for (String attachmentId : admission.attachmentIds()) {
            AttachmentRecords.AttachmentRow row = mapper.attachments().selectAttachment(attachmentId);
            if (row == null) notFound("attachment");
            if (!workspaceId.equals(row.workspaceId()) || !"DRAFT".equals(row.status())
                || row.blobSha256() == null || !Instant.parse(row.expiresAt()).isAfter(admission.requestedAt())) {
                throw conflict("attachment is not an available draft for this workspace");
            }
            try {
                totalBytes = Math.addExact(totalBytes, row.sizeBytes());
            } catch (ArithmeticException overflow) {
                throw conflict("turn attachment quota exceeded");
            }
            if (totalBytes > MAX_TURN_ATTACHMENT_BYTES) {
                throw conflict("turn attachment quota exceeded");
            }
            displayNames.add(row.displayName());
        }
        for (int ordinal = 0; ordinal < admission.attachmentIds().size(); ordinal++) {
            String attachmentId = admission.attachmentIds().get(ordinal);
            AttachmentRecords.AttachmentBind binding = new AttachmentRecords.AttachmentBind(
                    attachmentId, workspaceId, admission.turnId(), ordinal,
                    instant(admission.requestedAt()));
            requireChanged(mapper.attachments().bindDraft(binding),
                    "attachment changed during turn admission");
            requireChanged(mapper.attachments().insertTurnAttachment(binding),
                    "turn attachment relation insert lost");
        }
        return List.copyOf(displayNames);
    }

    /**
     * 状态边和相关事实先全部提交，调用方随后才可发布语义通知。
     */
    @Override
    public CommitReceipt commit(CommitRequest request) {
        ensureOpen();
        Objects.requireNonNull(request, "request");
        return transactions.required(mapper -> {
            PersistenceRecords.TurnRow turn = checkedTurn(mapper, request.threadId(), request.turnId(),
                    request.expectedTurnMutationVersion());
            TurnState current = TurnState.valueOf(requiredText(turn.state(), "state"));
            requireCancellationNotClaimed(turn);
            requireTransition(current, request.state(), false, !request.facts().isEmpty());
            long threadRevision = allocateThreadRevision(mapper, request.threadId(), request.occurredAt());
            applyFacts(mapper, request.threadId(), request.turnId(), request.facts(),
                    request.occurredAt());
            requireChanged(mapper.agent().compareAndSetTurn(new PersistenceRecords.TurnCas(
                            request.threadId(), request.turnId(), request.state().name(),
                            request.expectedTurnMutationVersion(), instant(request.occurredAt()),
                            null, null, null, null)),
                    "turn state changed concurrently");
            return new CommitReceipt(threadRevision, request.expectedTurnMutationVersion() + 1);
        });
    }

    /**
     * 取消声明只为最后一个已执行 Tool batch 开窄门；状态保持不变，事实闭集由端口类型预先约束。
     */
    @Override
    public CommitReceipt commitCancellationToolBatch(CancellationToolBatchCommit cancellationCommit) {
        ensureOpen();
        Objects.requireNonNull(cancellationCommit, "cancellationCommit");
        CommitRequest request = cancellationCommit.request();
        return transactions.required(mapper -> {
            PersistenceRecords.TurnRow turn = checkedTurn(mapper, request.threadId(), request.turnId(),
                    request.expectedTurnMutationVersion());
            TurnState current = TurnState.valueOf(requiredText(turn.state(), "state"));
            requireCancellationClaimed(turn);
            if (current != request.state()) {
                throw conflict("cancellation Tool batch cannot change turn state");
            }
            requireTransition(current, request.state(), false, true);
            long threadRevision = allocateThreadRevision(mapper, request.threadId(), request.occurredAt());
            applyFacts(mapper, request.threadId(), request.turnId(), request.facts(), request.occurredAt());
            requireChanged(mapper.agent().advanceCancellationToolBatch(new PersistenceRecords.TurnAdvance(
                            request.threadId(), request.turnId(), request.expectedTurnMutationVersion(),
                            current.name(), instant(request.occurredAt()))),
                    "turn state changed concurrently");
            return new CommitReceipt(threadRevision, request.expectedTurnMutationVersion() + 1);
        });
    }

    /**
     * 终态门、最终消息和事实同事务，任何 constraint/CAS 失败都会整体回滚。
     */
    @Override
    public CommitReceipt commitTerminal(TerminalCommit request) {
        ensureOpen();
        Objects.requireNonNull(request, "request");
        return transactions.required(mapper -> {
            PersistenceRecords.TurnRow turn = checkedTurn(mapper, request.threadId(), request.turnId(),
                    request.expectedTurnMutationVersion());
            long expectedMutationVersion = request.expectedTurnMutationVersion();
            TurnState current = TurnState.valueOf(requiredText(turn.state(), "state"));
            requireTransition(current, request.state(), true, !request.facts().isEmpty());
            requireTerminalAfterCancellation(turn, request.state());
            long threadRevision = allocateThreadRevision(mapper, request.threadId(), request.occurredAt());
            applyFacts(mapper, request.threadId(), request.turnId(), request.facts(),
                    request.occurredAt());
            settleTerminalTools(mapper, request.turnId(), request.state(), request.occurredAt());
            if (request.finalMessage() != null) {
                insertMessage(mapper, request.threadId(), request.turnId(), request.finalMessageId(),
                        request.finalMessage(), request.occurredAt());
                insertTimelineMessage(mapper, request.finalMessageId(), request.threadId(), request.turnId(),
                        "FINAL_ANSWER", visibleText(request.finalMessage()), null, request.occurredAt());
            }
            requireChanged(mapper.agent().compareAndSetTurn(new PersistenceRecords.TurnCas(
                            request.threadId(), request.turnId(), request.state().name(), expectedMutationVersion,
                            instant(request.occurredAt()), instant(request.occurredAt()), request.summary(),
                            request.errorCode(), request.errorMessage())),
                    "terminal gate already committed");
            return new CommitReceipt(threadRevision, expectedMutationVersion + 1);
        });
    }

    /**
     * 成功终态拒绝任何悬空 Tool；失败和取消则在同一事务中把它们收敛为可恢复终态，
     * 从而让实时事件、历史快照与 Turn 状态不再互相矛盾。
     */
    private static void settleTerminalTools(PersistenceMappers mapper, String turnId,
                                            TurnState state, Instant occurredAt) {
        int unfinished = mapper.agent().countUnfinishedTools(turnId);
        if (unfinished == 0) return;
        if (state == TurnState.COMPLETED) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "completed turn contains unfinished tools");
        }
        boolean cancelled = state == TurnState.CANCELLED;
        int settled = mapper.agent().settleUnfinishedTools(new PersistenceRecords.ToolSettlement(
                turnId, cancelled ? "CANCELLED" : "FAILED", cancelled ? "cancelled" : "error",
                cancelled ? "Turn was cancelled before this Tool completed."
                          : "Turn failed before this Tool completed.", instant(occurredAt)));
        if (settled != unfinished) {
            throw new StorageException(StorageException.Code.TRANSACTION,
                    "unfinished tool settlement lost rows");
        }
    }

    /**
     * 在唯一持久化事务中先验证 Thread CAS，再登记 Turn 取消事实；SQLite 写锁保证两次更新
     * 不会被终态提交插入。已登记请求只回读原有版本，避免重试制造第二个 callback 资格。
     */
    @Override
    public CancellationClaim claimCancellation(String threadId, String turnId,
                                               long expectedThreadRevision, String reason,
                                               Instant occurredAt) {
        ensureOpen();
        if (expectedThreadRevision < 0) throw new IllegalArgumentException("invalid thread revision");
        String boundedReason = cancellationReason(reason);
        Objects.requireNonNull(occurredAt, "occurredAt");
        return transactions.required(mapper -> {
            PersistenceRecords.ThreadRow thread = mapper.history().selectThread(threadId);
            if (thread == null) notFound("thread");
            PersistenceRecords.TurnRow turn = mapper.agent().selectTurn(new PersistenceRecords.TurnKey(threadId, turnId));
            if (turn == null) notFound("turn");
            TurnState state = TurnState.valueOf(requiredText(turn.state(), "state"));
            Long claimedExpectedRevision = turn.cancelExpectedThreadRevision();
            if (claimedExpectedRevision != null) {
                if (expectedThreadRevision != claimedExpectedRevision) {
                    throw conflict("thread revision is stale");
                }
                Long claimedThreadRevision = turn.cancelThreadRevision();
                Long claimedTurnMutationVersion = turn.cancelTurnMutationVersion();
                if (claimedThreadRevision == null || claimedTurnMutationVersion == null) {
                    throw new StorageException(StorageException.Code.INVALID_STATE,
                            "cancel intent receipt is incomplete");
                }
                return new CancellationClaim(true, state, claimedThreadRevision,
                        claimedTurnMutationVersion);
            }
            if (state.terminal()) throw conflict("turn is already terminal");
            requireRevision(thread, expectedThreadRevision);
            long expectedMutationVersion = turn.mutationVersion();
            long claimedThreadRevision = expectedThreadRevision + 1;
            long claimedTurnMutationVersion = expectedMutationVersion + 1;
            requireChanged(mapper.history().compareAndSetThread(new PersistenceRecords.ThreadRevisionCas(
                    threadId, expectedThreadRevision, instant(occurredAt))), "thread revision is stale");
            requireChanged(mapper.agent().claimCancellation(new PersistenceRecords.CancellationClaim(
                            threadId, turnId, expectedMutationVersion, instant(occurredAt), boundedReason,
                            expectedThreadRevision, claimedThreadRevision, claimedTurnMutationVersion)),
                    "turn changed concurrently");
            return new CancellationClaim(true, state, claimedThreadRevision,
                    claimedTurnMutationVersion);
        });
    }

    /** 排队仅验证 Turn 仍活动并插入一行，不推进执行 CAS，避免 UI 排队打断当前 Tool。 */
    @Override
    public void enqueueInput(PendingInput input) {
        ensureOpen();
        Objects.requireNonNull(input, "input");
        transactions.required(mapper -> {
            PersistenceRecords.TurnRow turn = mapper.agent().selectTurn(
                    new PersistenceRecords.TurnKey(input.threadId(), input.turnId()));
            if (turn == null || TurnState.valueOf(requiredText(turn.state(), "state")).terminal()
                || turn.cancelRequestedAt() != null) {
                throw conflict("turn cannot accept queued input");
            }
            requireChanged(mapper.agent().insertPendingInput(new PersistenceRecords.PendingInputInsert(
                    input.inputId(), input.threadId(), input.turnId(), input.kind().name(), input.text(),
                    instant(input.createdAt()))), "input identity must be unique");
            return null;
        });
    }

    /** 标记消费和 USER Message 插入共享一个事务，并以 Turn mutation version 拒绝终态竞态。 */
    @Override
    public Optional<InputConsumption> consumeInput(String threadId, String turnId, InputKind kind,
                                                   long expectedTurnMutationVersion, Instant occurredAt) {
        ensureOpen();
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(occurredAt, "occurredAt");
        return transactions.required(mapper -> {
            checkedTurn(mapper, threadId, turnId, expectedTurnMutationVersion);
            PersistenceRecords.PendingInputRow row = mapper.agent().selectPendingInput(
                    new PersistenceRecords.PendingInputQuery(turnId, kind.name()));
            if (row == null) return Optional.empty();
            String inputId = requiredText(row.inputId(), "input_id");
            requireChanged(mapper.agent().consumePendingInput(
                    new PersistenceRecords.PendingInputConsume(inputId, instant(occurredAt))),
                    "input was consumed concurrently");
            ModelMessage message = new ModelMessage(ModelRole.USER,
                    List.of(new TextContent(requiredValue(row.text(), "text"))));
            insertMessage(mapper, threadId, turnId, "item_" + UUID.randomUUID(), message, occurredAt);
            long revision = allocateThreadRevision(mapper, threadId, occurredAt);
            requireChanged(mapper.agent().advanceInputConsumption(new PersistenceRecords.InputAdvance(
                    threadId, turnId, expectedTurnMutationVersion, instant(occurredAt))),
                    "turn changed during input consumption");
            return Optional.of(new InputConsumption(inputId, message, revision,
                    expectedTurnMutationVersion + 1));
        });
    }

    /** 取消剩余队列不推进 revision；Turn 的取消声明已经提供唯一可观察状态变化。 */
    @Override
    public void cancelInputs(String turnId, Instant occurredAt) {
        ensureOpen();
        Objects.requireNonNull(occurredAt, "occurredAt");
        transactions.required(mapper -> {
            mapper.agent().cancelPendingInputs(new PersistenceRecords.PendingInputCancel(
                    turnId, instant(occurredAt)));
            return null;
        });
    }

    /**
     * 单次查询同时读取 Thread revision，避免状态和 CAS token 来自不同快照。
     */
    @Override
    public Optional<TurnSnapshot> findTurn(String threadId, String turnId) {
        ensureOpen();
        return transactions.required(mapper -> {
            PersistenceRecords.ThreadRow thread = mapper.history().selectThread(threadId);
            if (thread == null) return Optional.empty();
            PersistenceRecords.TurnRow turn = mapper.agent().selectTurn(new PersistenceRecords.TurnKey(threadId, turnId));
            return turn == null ? Optional.empty() : Optional.of(turnSnapshot(turn, thread.revision()));
        });
    }

    /**
     * 消息 blocks 在事务内完成解码，损坏任意一条都会拒绝发布部分 snapshot。
     */
    @Override
    public Optional<ThreadSnapshot> readThread(String threadId) {
        ensureOpen();
        return transactions.required(mapper -> {
            PersistenceRecords.ThreadRow row = mapper.history().selectThread(threadId);
            if (row == null) return Optional.empty();
            long revision = row.revision();
            List<TurnSnapshot> turns = mapper.agent().selectTurns(threadId).stream()
                    .map(turn -> turnSnapshot(turn, revision)).toList();
            List<StoredMessage> messages = mapper.agent().selectMessages(threadId).stream()
                    .map(message -> new StoredMessage(requiredText(message.messageId(), "message_id"),
                            requiredText(message.turnId(), "turn_id"), message.ordinal(),
                            codec.readMessage(requiredText(message.role(), "role"),
                                    requiredText(message.blocksJson(), "blocks_json")),
                            Instant.parse(requiredText(message.createdAt(), "created_at")))).toList();
            return Optional.of(new ThreadSnapshot(threadId, requiredText(row.workspaceId(), "workspace_id"),
                    requiredText(row.title(), "title"), PersistenceRowProjections.threadPreferences(row),
                    revision, turns, messages,
                    Instant.parse(requiredText(row.createdAt(), "created_at")),
                    Instant.parse(requiredText(row.updatedAt(), "updated_at"))));
        });
    }

    /**
     * store 不持有数据库资源；close 只阻止 composition shutdown 后的新调用。
     */
    @Override
    public void close() {
        closed.set(true);
    }

    /**
     * 将 sealed facts 映射到内聚表，禁止用事件 journal 充当业务事实。
     */
    private void applyFacts(PersistenceMappers mapper, String threadId, String turnId,
                            List<Fact> facts, Instant occurredAt) {
        for (Fact fact : facts) {
            switch (fact) {
                case AssistantFact assistant -> {
                    insertMessage(mapper, threadId, turnId,
                            assistant.messageId(), assistant.message(), occurredAt);
                    insertTimelineMessage(mapper, assistant.messageId(), threadId, turnId,
                            "ASSISTANT_PROGRESS", assistant.publicText(), assistant.modelRound(), occurredAt);
                    if (assistant.reasoningSummary() != null) {
                        insertTimelineMessage(mapper, assistant.messageId() + "_reasoning", threadId, turnId,
                                "REASONING_SUMMARY", assistant.reasoningSummary(), assistant.modelRound(), occurredAt);
                    }
                }
                case ToolResultMessageFact resultMessage -> insertMessage(mapper, threadId, turnId,
                        resultMessage.messageId(), resultMessage.message(), occurredAt);
                case ToolPreparedFact prepared -> requireChanged(mapper.agent().insertTool(
                        new PersistenceRecords.ToolInsert(prepared.callId(), threadId, turnId, prepared.ordinal(),
                                prepared.toolName(), prepared.sideEffect().name(),
                                presentations.write(prepared.presentation()), instant(occurredAt))),
                        "tool call must be unique by callId and ordinal");
                case ToolStartedFact started -> requireChanged(mapper.agent().startTool(
                                new PersistenceRecords.ToolStart(turnId, started.callId(), instant(occurredAt))),
                        "tool start requires PREPARED call");
                case ToolResultFact result -> {
                    if (result.presentation().artifactId() != null) {
                        requireChanged(mapper.agent().insertToolArtifact(new PersistenceRecords.ToolArtifactInsert(
                                        result.presentation().artifactId(), threadId, turnId, result.callId(),
                                        result.artifactContent(), result.artifactContent().codePointCount(
                                                0, result.artifactContent().length()), instant(occurredAt))),
                                "tool artifact identity already exists");
                    }
                    requireChanged(mapper.agent().finishTool(
                                    new PersistenceRecords.ToolFinish(turnId, result.callId(), result.state().name(),
                                            presentations.write(result.presentation()),
                                            result.presentation().artifactId(), instant(occurredAt))),
                            "tool result requires exactly one unfinished call");
                }
                case ApprovalFact approval -> persistApproval(mapper, threadId, turnId, approval, occurredAt);
                case UsageFact usage -> requireChanged(mapper.agent().insertUsage(
                        new PersistenceRecords.UsageInsert("usage_" + turnId + "_" + usage.modelRound(),
                                threadId, turnId, usage.modelRound(), usage.usage().inputTokens(),
                                usage.usage().outputTokens(), usage.usage().totalTokens(), instant(occurredAt))),
                        "usage round must be unique");
            }
        }
    }

    /**
     * approval request/response 共享一行，响应不能凭空创建或越过 expiry。
     */
    private void persistApproval(PersistenceMappers mapper, String threadId, String turnId,
                                 ApprovalFact approval, Instant occurredAt) {
        int changed = approval.decision() == null
                ? mapper.agent().insertApproval(new PersistenceRecords.ApprovalInsert(
                        approval.approvalId(), threadId, turnId, approval.callId(),
                        instant(occurredAt), instant(approval.expiresAt())))
                : mapper.agent().resolveApproval(new PersistenceRecords.ApprovalResolve(
                        turnId, approval.approvalId(), approval.callId(), approval.decision().name(),
                        instant(occurredAt)));
        requireChanged(changed, "approval transition is stale or expired");
        requireChanged(mapper.agent().updateToolPresentation(new PersistenceRecords.ToolPresentationUpdate(
                        turnId, approval.callId(), presentations.write(approval.presentation()),
                        instant(occurredAt))),
                "approval presentation requires an unfinished Tool call");
    }

    /**
     * 每个 assistant fact 分配唯一递增 ordinal；不再存在 event/sequence 双游标。
     */
    private void insertMessage(PersistenceMappers mapper, String threadId, String turnId, String messageId,
                               ModelMessage message, Instant occurredAt) {
        long ordinal = mapper.agent().selectNextMessageOrdinal(threadId);
        requireChanged(mapper.agent().insertMessage(new PersistenceRecords.MessageInsert(
                        messageId, threadId, turnId, ordinal, message.role().name(), codec.writeMessage(message),
                        instant(occurredAt))),
                "message identity or ordinal already exists");
    }

    /** UI 时间线文本写入独立表，确保历史读取不再解析 Provider 上下文 blocks。 */
    private static void insertTimelineMessage(PersistenceMappers mapper, String itemId, String threadId,
                                              String turnId, String kind, String text, Integer modelRound,
                                              Instant occurredAt) {
        requireChanged(mapper.agent().insertTimelineMessage(new PersistenceRecords.TimelineMessageInsert(
                        itemId, threadId, turnId, kind, text, modelRound, instant(occurredAt))),
                "timeline message identity already exists");
    }

    /** 只拼接公开文本块；Tool arguments/result blocks 不得复制进 timeline_messages。 */
    private static String visibleText(ModelMessage message) {
        return message.content().stream().filter(TextContent.class::isInstance)
                .map(TextContent.class::cast).map(TextContent::text).reduce("", String::concat);
    }

    /** 四元身份读取已脱敏 Tool artifact，供独立分页用例使用。 */
    public Optional<PersistenceRecords.ToolArtifactRow> readToolArtifact(
            String threadId, String turnId, String callId, String artifactId) {
        ensureOpen();
        return transactions.required(mapper -> Optional.ofNullable(mapper.agent().selectToolArtifact(
                new PersistenceRecords.ToolArtifactKey(threadId, turnId, callId, artifactId))));
    }

    /**
     * 内部提交只读取并校验所属 Turn mutation version，不受同 Thread 其它 Turn 推进影响。
     */
    private static PersistenceRecords.TurnRow checkedTurn(
            PersistenceMappers mapper, String threadId, String turnId, long expectedMutationVersion) {
        PersistenceRecords.TurnRow turn = mapper.agent().selectTurn(new PersistenceRecords.TurnKey(threadId, turnId));
        if (turn == null) notFound("turn");
        if (turn.mutationVersion() != expectedMutationVersion) {
            throw conflict("turn mutation version is stale");
        }
        return turn;
    }

    /**
     * 每个成功内部提交原子分配一个全局可观察 revision，但不把它当作 Turn 写入门。
     */
    private static long allocateThreadRevision(PersistenceMappers mapper, String threadId, Instant occurredAt) {
        Long revision = mapper.history().allocateThreadRevision(
                new PersistenceRecords.ThreadRevision(threadId, instant(occurredAt)));
        if (revision == null) notFound("thread");
        return revision;
    }

    /**
     * 六态转换显式 fail closed，不允许从终态产生第二终态。
     */
    private static void requireTransition(TurnState current, TurnState target,
                                          boolean terminalCommit, boolean carriesFacts) {
        if (current.terminal()) throw conflict("turn is already terminal");
        boolean legal = current == target ? carriesFacts : current.canTransitionTo(target);
        if (!legal || terminalCommit != target.terminal()) throw conflict("illegal turn state transition");
    }

    /**
     * 已登记的取消请求在收敛前独占非终态 mutation gate。
     */
    private static void requireCancellationNotClaimed(PersistenceRecords.TurnRow turn) {
        if (cancellationClaimed(turn)) {
            throw conflict("turn cancellation is already claimed");
        }
    }

    /**
     * 专用 Tool 收敛事务必须建立在已持久化取消声明之上，禁止被普通执行路径误用。
     */
    private static void requireCancellationClaimed(PersistenceRecords.TurnRow turn) {
        if (!cancellationClaimed(turn)) {
            throw conflict("turn cancellation is not claimed");
        }
    }

    /**
     * 已持久化的取消请求只允许进入 CANCELLED，不能被后续失败路径改写为 FAILED。
     */
    private static void requireTerminalAfterCancellation(PersistenceRecords.TurnRow turn, TurnState target) {
        if (cancellationClaimed(turn) && target != TurnState.CANCELLED) {
            throw conflict("turn cancellation already claimed");
        }
    }

    /**
     * 读取独立取消意图，不复用终态错误字段表达中间状态。
     */
    private static boolean cancellationClaimed(PersistenceRecords.TurnRow turn) {
        return turn.cancelRequestedAt() != null;
    }

    /**
     * 将 SQL 行转换为公开六态快照，不保留 Mapper map。
     */
    private static TurnSnapshot turnSnapshot(PersistenceRecords.TurnRow row, long threadRevision) {
        return new TurnSnapshot(requiredText(row.threadId(), "thread_id"),
                requiredText(row.turnId(), "turn_id"), TurnState.valueOf(requiredText(row.state(), "state")),
                PersistenceRowProjections.turnRuntime(row),
                Instant.parse(requiredText(row.requestedAt(), "requested_at")),
                Instant.parse(requiredText(row.updatedAt(), "updated_at")),
                row.completedAt() == null ? null : Instant.parse(row.completedAt()), threadRevision,
                row.mutationVersion());
    }

    /**
     * 读取必须存在的 Thread 行，把缺失统一映射为稳定存储错误。
     */
    private static PersistenceRecords.ThreadRow requireThread(PersistenceMappers mapper, String threadId) {
        PersistenceRecords.ThreadRow row = mapper.history().selectThread(threadId);
        if (row == null) notFound("thread");
        return row;
    }

    /**
     * 比较 Thread revision，避免过期外部请求穿透 admission CAS 门。
     */
    private static void requireRevision(PersistenceRecords.ThreadRow thread, long expected) {
        if (thread.revision() != expected) throw conflict("thread revision is stale");
    }

    /**
     * 所有单行状态迁移都必须精确影响一行，否则按并发冲突处理。
     */
    private static void requireChanged(int count, String message) {
        if (count != 1) throw conflict(message);
    }

    /**
     * 构造不携带 SQL 或路径的稳定未找到错误。
     */
    private static void notFound(String subject) {
        throw new StorageException(StorageException.Code.NOT_FOUND, subject + " was not found");
    }

    /**
     * 构造不携带底层 SQL 的 CAS 冲突错误。
     */
    private static StorageException conflict(String message) {
        return new StorageException(StorageException.Code.CAS_CONFLICT, message);
    }

    /**
     * 将领域时间编码为数据库统一的 UTC 字符串。
     */
    private static String instant(Instant value) {
        return Objects.requireNonNull(value, "value").toString();
    }

    /**
     * 必需文本列使用统一损坏行校验。
     */
    private static String requiredText(String value, String column) {
        if (value == null || value.isBlank()) throw corrupted(column);
        return value;
    }

    /** 必需但允许空字符串的文本列只拒绝 SQL NULL，保留 Tool 结果等合法空正文。 */
    private static String requiredValue(String value, String column) {
        if (value == null) throw corrupted(column);
        return value;
    }

    /** 将缺失必需列统一归类为损坏数据，避免下游用默认值掩盖存储漂移。 */
    private static StorageException corrupted(String column) {
        return new StorageException(StorageException.Code.INVALID_STATE,
                "persistence row is missing required column " + column);
    }

    /**
     * 用户来源的取消文本进入 SQLite 前必须限制长度和 NUL 字符。
     */
    private static String cancellationReason(String value) {
        if (value == null || value.isBlank() || value.length() > 4_096 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid cancellation reason");
        }
        return value;
    }

    /**
     * composition shutdown 后所有调用 fail fast，避免已释放 datasource 上出现迟到事务。
     */
    private void ensureOpen() {
        if (closed.get()) throw new StorageException(StorageException.Code.CLOSED, "agent store is closed");
    }
}
