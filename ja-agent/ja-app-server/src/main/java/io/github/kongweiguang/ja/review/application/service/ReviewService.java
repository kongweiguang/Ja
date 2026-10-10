// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.review.application.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase;
import io.github.kongweiguang.ja.attachment.domain.model.AttachmentMetadata;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.api.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.conversation.domain.model.ClientOperationReceipt;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnSummary;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.model.ReviewContext;
import io.github.kongweiguang.ja.review.application.api.ReviewFailure;
import io.github.kongweiguang.ja.review.application.api.ReviewFailure.Failure;
import io.github.kongweiguang.ja.review.application.api.ReviewUseCase;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Review 的应用服务；只创建受限普通 Turn，并从其持久历史恢复快照绑定的报告。
 */
public final class ReviewService implements ReviewUseCase {
    private static final String REPORT_TOOL_NAME = "review_report_submit";
    private static final int HISTORY_PAGE_SIZE = 500;
    private static final int ARTIFACT_PAGE_CHARACTERS = 65_536;
    private static final int MAX_REPORT_UTF8_BYTES = 3 * 1024 * 1024;
    /**
     * 请求保持面向用户且简短；快照只读、位置证据与覆盖完整性由受限工具及中间件强制校验。
     */
    private static final String REVIEW_PROMPT = """
            请审查本次冻结的代码改动，指出可修复的问题，并为每条问题提供准确位置和证据。只读取该快照，提交报告并说明实际覆盖范围；未完整审查的内容须标记为不完整。
            """;

    private final ThreadUseCase threads;
    private final WorkspaceUseCase workspaces;
    private final TurnUseCase turns;
    private final AttachmentUseCase attachments;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** 应用服务只接收业务端口，报告 artifact 的持久边界由 mapper 统一解释。 */
    public ReviewService(ThreadUseCase threads, WorkspaceUseCase workspaces, TurnUseCase turns,
                         AttachmentUseCase attachments, ObjectMapper mapper, Clock clock) {
        this.threads = Objects.requireNonNull(threads, "threads");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 将快照绑定到普通 USER Turn；只读审查覆盖本次权限及协作模式，须明确登记覆盖而不改 Thread 偏好。
     * 目录仍由可信 ReviewContext 收窄，不能借用源会话的完全访问或 Plan 工具权限。
     */
    @Override
    public Accepted start(StartRequest request, ConversationTurnEventSink sink, String operationKey,
                          String requestFingerprint) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(sink, "sink");
        if (operationKey == null || !operationKey.matches("op_[0-9a-f]{32}")
            || requestFingerprint == null || !requestFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid review operation identity");
        }
        ThreadSnapshot snapshot = threadSnapshot(request.threadId());
        ThreadSummary thread = snapshot.thread();
        Optional<ClientOperationReceipt> receipt = turns.readClientOperation(operationKey);
        if (receipt.isPresent()) {
            ClientOperationReceipt committed = receipt.orElseThrow();
            if (!committed.matches("turn/start", requestFingerprint)
                || !committed.threadId().equals(request.threadId())
                || !committed.turnId().equals(request.turnId())) {
                throw new ReviewFailure(Failure.CONFLICT);
            }
        } else if (thread.revision() != request.expectedThreadRevision()) {
            throw new ReviewFailure(Failure.CONFLICT);
        } else if (thread.status() != ThreadSummary.Status.ACTIVE) {
            throw new ReviewFailure(Failure.CONFLICT);
        }
        Workspace workspace;
        try {
            workspace = workspaces.requireOpenWorkspace(thread.workspaceId());
        } catch (RuntimeException unavailable) {
            throw new ReviewFailure(Failure.WORKSPACE_UNAVAILABLE, unavailable);
        }
        if (!workspace.workspaceId().equals(thread.workspaceId())) {
            throw new ReviewFailure(Failure.WORKSPACE_UNAVAILABLE);
        }
        UserContent content = new UserContent(List.of(
                new AttachmentContent(request.snapshotAttachmentId()), new TextContent(REVIEW_PROMPT)));
        TurnStartRequest start = new TurnStartRequest(thread.threadId(), request.turnId(), workspace.workspaceId(),
                workspace.root(), content, thread.preferences().providerId(), thread.preferences().modelId(),
                thread.preferences().reasoningLevel(), AccessMode.APPROVAL_REQUIRED,
                io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode.DEFAULT,
                request.expectedThreadRevision(), 0, clock.instant(), Optional.empty(), Optional.empty(),
                Optional.of(new ReviewContext(request.snapshotAttachmentId())), true);
        TurnUseCase.Accepted accepted = turns.start(start, sink, operationKey, requestFingerprint);
        return new Accepted(accepted.threadId(), accepted.turnId(), accepted.threadRevision(), accepted.queued(),
                accepted.completion());
    }

    /** 仅当指定会话中的 Turn 已完成且报告 artifact 与原快照身份均通过回读校验时才公开报告。 */
    @Override
    public ReadResult read(ReadRequest request) {
        Objects.requireNonNull(request, "request");
        TurnSummary turn = threads.findTurn(request.turnId()).orElseThrow(() -> new ReviewFailure(Failure.TURN_NOT_FOUND));
        if (!turn.threadId().equals(request.threadId())) {
            throw new ReviewFailure(Failure.TURN_NOT_FOUND);
        }
        String status = turn.status().toLowerCase(Locale.ROOT);
        if (!Set.of("queued", "running", "waiting_approval", "waiting_input", "suspended",
                "completed", "failed", "cancelled").contains(status)) {
            throw new ReviewFailure(Failure.TURN_UNAVAILABLE);
        }
        if (!"completed".equals(status)) {
            return new ReadResult(request.threadId(), request.turnId(), status, null);
        }
        ReportArtifact artifact = findReportArtifact(request.threadId(), request.turnId());
        SnapshotIdentity snapshot = snapshotIdentity(request.threadId(), artifact.snapshotAttachmentId());
        Report report = readReport(request.threadId(), request.turnId(), artifact, snapshot);
        return new ReadResult(request.threadId(), request.turnId(), status, report);
    }

    /** 只读取单个权威 Thread 页，以确认当前归属并冻结本次准入使用的偏好。 */
    private ThreadSnapshot threadSnapshot(String threadId) {
        return threads.readThread(threadId, null, 1).orElseThrow(() -> new ReviewFailure(Failure.THREAD_NOT_FOUND));
    }

    /** 分页查找本 Turn 的成功报告 Tool 与唯一输入附件，避免从其他 Turn 借用 artifact。 */
    private ReportArtifact findReportArtifact(String threadId, String turnId) {
        String cursor = null;
        String artifactId = null;
        String callId = null;
        String attachmentId = null;
        do {
            ThreadSnapshot page = threads.readThreadLatest(threadId, cursor, HISTORY_PAGE_SIZE)
                    .orElseThrow(() -> new ReviewFailure(Failure.THREAD_NOT_FOUND));
            for (ThreadSnapshot.Item item : page.items()) {
                if (!turnId.equals(item.turnId())) {
                    continue;
                }
                if (item instanceof ThreadSnapshot.ToolItem tool && REPORT_TOOL_NAME.equals(tool.toolName())
                    && tool.presentation().status() == io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation.Status.SUCCESS) {
                    if (tool.presentation().artifactId() == null) {
                        throw new ReviewFailure(Failure.REPORT_UNAVAILABLE);
                    }
                    artifactId = tool.presentation().artifactId();
                    callId = tool.callId();
                }
                if (item instanceof ThreadSnapshot.UserInputItem userInput) {
                    if (userInput.attachments().size() != 1) {
                        throw new ReviewFailure(Failure.REPORT_UNAVAILABLE);
                    }
                    attachmentId = userInput.attachments().getFirst().attachmentId();
                }
            }
            if (artifactId != null && callId != null && attachmentId != null) {
                return new ReportArtifact(callId, artifactId, attachmentId);
            }
            cursor = page.nextCursor();
        } while (cursor != null);
        throw new ReviewFailure(Failure.REPORT_UNAVAILABLE);
    }

    /** 重新确认报告来源附件仍绑定当前 Thread 和 Workspace，防止历史身份被错误拼接。 */
    private SnapshotIdentity snapshotIdentity(String threadId, String attachmentId) {
        List<AttachmentMetadata> rows;
        try {
            rows = attachments.readAuthorizedMetadata(threadId, List.of(attachmentId));
        } catch (RuntimeException unavailable) {
            throw new ReviewFailure(Failure.REPORT_UNAVAILABLE, unavailable);
        }
        if (rows.size() != 1) {
            throw new ReviewFailure(Failure.REPORT_UNAVAILABLE);
        }
        AttachmentMetadata metadata = rows.getFirst();
        ThreadSnapshot thread = threadSnapshot(threadId);
        if (!metadata.attachmentId().equals(attachmentId)
            || !metadata.workspaceId().equals(thread.thread().workspaceId())
            || metadata.status() != AttachmentMetadata.Status.BOUND
            || metadata.mediaKind() != AttachmentMetadata.MediaKind.TEXT) {
            throw new ReviewFailure(Failure.REPORT_UNAVAILABLE);
        }
        return readSnapshotIdentity(threadId, attachmentId);
    }

    /** 只解析冻结附件的有限前缀，避免读取报告时重新物化整份快照正文。 */
    private SnapshotIdentity readSnapshotIdentity(String threadId, String attachmentId) {
        AttachmentUseCase.ReadResult first;
        try {
            first = attachments.read(new AttachmentUseCase.ReadRequest(attachmentId, threadId, 0, 64 * 1024));
        } catch (RuntimeException unavailable) {
            throw new ReviewFailure(Failure.REPORT_UNAVAILABLE, unavailable);
        }
        if (!"utf-8".equals(first.encoding()) || first.offsetBytes() != 0
            || first.nextOffsetBytes() <= 0 || first.nextOffsetBytes() > 64 * 1024) {
            throw new ReviewFailure(Failure.REPORT_UNAVAILABLE);
        }
        String prefix = first.content();
        String snapshotId = null;
        String workspaceId = null;
        String revision = null;
        String snapshotHash = null;
        try (JsonParser parser = mapper.getFactory().createParser(prefix)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new ReviewFailure(Failure.REPORT_UNAVAILABLE);
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("snapshotId".equals(field) && value == JsonToken.VALUE_STRING) {
                    snapshotId = parser.getValueAsString();
                } else if ("workspaceId".equals(field) && value == JsonToken.VALUE_STRING) {
                    workspaceId = parser.getValueAsString();
                } else if ("revision".equals(field) && value == JsonToken.VALUE_STRING) {
                    revision = parser.getValueAsString();
                } else if ("snapshotHash".equals(field) && value == JsonToken.VALUE_STRING) {
                    snapshotHash = parser.getValueAsString();
                    break;
                } else {
                    parser.skipChildren();
                }
            }
        } catch (IOException failure) {
            throw new ReviewFailure(Failure.REPORT_UNAVAILABLE, failure);
        }
        if (snapshotId == null || workspaceId == null || revision == null || snapshotHash == null
            || !snapshotId.matches("rev_[0-9a-f]{24}") || !workspaceId.matches("ws_[A-Za-z0-9_-]{1,97}")
            || !revision.matches("[0-9a-f]{64}") || !snapshotHash.matches("[0-9a-f]{64}")) {
            throw new ReviewFailure(Failure.REPORT_UNAVAILABLE);
        }
        ThreadSnapshot thread = threadSnapshot(threadId);
        if (!workspaceId.equals(thread.thread().workspaceId())) {
            throw new ReviewFailure(Failure.REPORT_UNAVAILABLE);
        }
        return new SnapshotIdentity(snapshotId, snapshotHash, revision);
    }

    /** 按持久 code point 游标分页读取报告，并在拼接前执行共享 UTF-8 字节预算。 */
    private Report readReport(String threadId, String turnId, ReportArtifact artifact, SnapshotIdentity snapshot) {
        StringBuilder content = new StringBuilder();
        int offset = 0;
        int byteLength = 0;
        while (true) {
            ThreadUseCase.TextArtifactPage page = threads.readToolArtifact(threadId, turnId, artifact.callId(),
                    artifact.artifactId(), offset, ARTIFACT_PAGE_CHARACTERS)
                    .orElseThrow(() -> new ReviewFailure(Failure.REPORT_UNAVAILABLE));
            int pageCharacters = page.content().codePointCount(0, page.content().length());
            byteLength = Math.addExact(byteLength, page.content().getBytes(StandardCharsets.UTF_8).length);
            if (!artifact.artifactId().equals(page.artifactId()) || page.offsetCharacters() != offset
                || byteLength > MAX_REPORT_UTF8_BYTES) {
                throw new ReviewFailure(Failure.REPORT_UNAVAILABLE);
            }
            content.append(page.content());
            if (!page.truncated()) {
                if (offset + pageCharacters != page.totalCharacters()) {
                    throw new ReviewFailure(Failure.REPORT_UNAVAILABLE);
                }
                break;
            }
            if (page.nextOffsetCharacters() == null || page.nextOffsetCharacters() != offset + pageCharacters
                || page.nextOffsetCharacters() >= page.totalCharacters()) {
                throw new ReviewFailure(Failure.REPORT_UNAVAILABLE);
            }
            offset = page.nextOffsetCharacters();
        }
        try {
            JsonNode root = mapper.readTree(content.toString());
            requireFields(root, Set.of("reportId", "snapshotId", "snapshotHash", "revision", "summary",
                    "coverage", "findings"));
            Report report = mapper.treeToValue(root, Report.class);
            if (!snapshot.snapshotId().equals(report.snapshotId())
                || !snapshot.snapshotHash().equals(report.snapshotHash())
                || !snapshot.revision().equals(report.revision())
                || !report.coverage().reviewComplete() && report.coverage().complete()
                    && report.coverage().filesReviewed().size() == report.coverage().files()) {
                throw new ReviewFailure(Failure.REPORT_UNAVAILABLE);
            }
            return report;
        } catch (IOException | IllegalArgumentException failure) {
            throw new ReviewFailure(Failure.REPORT_UNAVAILABLE, failure);
        }
    }

    /** 要求报告根字段完全匹配当前 schema，避免 Jackson 默认值接纳旧版或越界 artifact。 */
    private static void requireFields(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()) {
            throw new IllegalArgumentException("review report fields are invalid");
        }
        java.util.Set<String> actual = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(fields)) {
            throw new IllegalArgumentException("review report fields are invalid");
        }
    }

    /**
     * @param callId 生成报告的 Tool 调用身份
     * @param artifactId 持久化报告正文身份
     * @param snapshotAttachmentId 本次审查绑定的快照附件身份
     */
    private record ReportArtifact(String callId,
                                  String artifactId,
                                  String snapshotAttachmentId) {
    }

    /**
     * @param snapshotId 冻结快照身份
     * @param snapshotHash 冻结快照内容摘要
     * @param revision 快照来源修订号
     */
    private record SnapshotIdentity(String snapshotId,
                                    String snapshotHash,
                                    String revision) {
    }

}
