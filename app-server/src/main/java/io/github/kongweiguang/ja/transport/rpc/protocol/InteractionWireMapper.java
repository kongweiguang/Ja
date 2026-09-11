// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionOption;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionQuestion;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionRequest;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionDraft;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionEvent;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionSnapshot;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 问答只显式投影用户可见事实，内部创建幂等键与恢复游标不得被反射带入 WebView。 */
public final class InteractionWireMapper {
    private final ObjectMapper mapper;

    /** 连接与其它 JA-RPC Mapper 共用严格 JSON 配置，避免公共字段编码漂移。 */
    public InteractionWireMapper(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** 空请求仍保留 Thread 事件水位，防止已回答后的对账回退到创建事件。 */
    public ObjectNode snapshot(InteractionSnapshot snapshot) {
        ObjectNode result = mapper.createObjectNode().put("threadId", snapshot.threadId())
                .put("eventSequence", snapshot.eventSequence());
        result.set("request", snapshot.request().map(this::request).orElse(null));
        result.set("draft", snapshot.draft().map(this::draft).orElse(null));
        result.put("resumeState", snapshot.resumeState().name().toLowerCase(Locale.ROOT));
        return result;
    }

    /** 事件只发送对账所需身份，答案正文不随高频草稿事件广播。 */
    public ObjectNode event(InteractionEvent event) {
        return mapper.createObjectNode().put("threadId", event.threadId())
                .put("requestId", event.requestId()).put("requestRevision", event.requestRevision())
                .put("eventSequence", event.eventSequence())
                .put("kind", event.kind().name().toLowerCase(Locale.ROOT))
                .put("occurredAt", event.occurredAt().toString());
    }

    /** 草稿与已确认答案分开投影，保存成功不会被 UI 当作已提交。 */
    public ObjectNode draft(InteractionDraft draft) {
        ObjectNode result = mapper.createObjectNode().put("threadId", draft.threadId())
                .put("requestId", draft.requestId()).put("page", draft.page())
                .put("collapsed", draft.collapsed()).put("revision", draft.revision())
                .put("updatedAt", draft.updatedAt().toString());
        result.set("answers", answers(draft.answers()));
        return result;
    }

    /** 请求以稳定身份与 revision 绑定问答摘要，显示文案不参与答案关联。 */
    public ObjectNode request(InteractionRequest request) {
        ObjectNode result = mapper.createObjectNode()
                .put("requestId", request.requestId())
                .put("threadId", request.threadId())
                .put("turnId", request.turnId())
                .put("toolCallId", request.toolCallId())
                .put("status", request.status().name().toLowerCase(Locale.ROOT))
                .put("revision", request.revision())
                .put("createdAt", request.createdAt().toString())
                .put("updatedAt", request.updatedAt().toString());
        ArrayNode questions = result.putArray("questions");
        request.questions().forEach(question -> questions.add(question(question)));
        result.set("answers", answers(request.answers()));
        result.put("planRevisionId", request.planRevisionId());
        result.put("runId", request.runId());
        result.put("goalId", request.goalId());
        return result;
    }

    /** 明确保留自填 null 与 skipped，客户端不必由空文本猜测用户意图。 */
    public ArrayNode answers(List<InteractionAnswer> answers) {
        ArrayNode result = mapper.createArrayNode();
        for (InteractionAnswer answer : answers) {
            ObjectNode item = result.addObject().put("questionId", answer.questionId())
                    .put("skipped", answer.skipped());
            ArrayNode optionIds = item.putArray("optionIds");
            answer.optionIds().forEach(optionIds::add);
            if (answer.freeText() == null) item.putNull("freeText");
            else item.put("freeText", answer.freeText());
        }
        return result;
    }

    /** 题型决定控件语义，客户端无需检查选项数量来猜单选或多选。 */
    private ObjectNode question(InteractionQuestion question) {
        ObjectNode result = mapper.createObjectNode().put("questionId", question.questionId())
                .put("prompt", question.prompt())
                .put("type", question.type().name().toLowerCase(Locale.ROOT))
                .put("required", question.required()).put("allowFreeText", question.allowFreeText());
        ArrayNode options = result.putArray("options");
        question.options().forEach(option -> options.add(option(option)));
        return result;
    }

    /** 推荐标记独立于选中状态，映射器不会替用户生成默认答案。 */
    private ObjectNode option(InteractionOption option) {
        return mapper.createObjectNode().put("optionId", option.optionId()).put("label", option.label())
                .put("description", option.description()).put("recommended", option.recommended());
    }
}
