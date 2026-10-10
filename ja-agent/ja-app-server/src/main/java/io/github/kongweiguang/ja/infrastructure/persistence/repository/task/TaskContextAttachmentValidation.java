// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.AttachmentRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepositoryException;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 只验证 Core Seed 对父附件的冻结授权；不复制消息、checkpoint 或附件归属。
 */
public final class TaskContextAttachmentValidation {
    /**
     * 此验证不持有可变状态，不能实例化为后台服务。
     */
    private TaskContextAttachmentValidation() {
    }

    /**
     * Child Seed 的附件必须同时在冻结白名单、父 Thread 绑定和目标 Workspace 内。
     */
    public static void validate(PersistenceMappers mapper, TaskModels.ContextSeed seed, String targetThreadId) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(seed, "seed");
        Objects.requireNonNull(targetThreadId, "targetThreadId");
        if (seed.inheritanceMode() != TaskModels.InheritanceMode.EFFECTIVE_CONTEXT
            || seed.initialContextSeed() == null) {
                return;
            }
        PersistenceRecords.ThreadRow parent = mapper.history().selectThread(seed.parentThreadId());
        PersistenceRecords.ThreadRow target = mapper.history().selectThread(targetThreadId);
        if (parent == null || target == null || !parent.workspaceId().equals(target.workspaceId())) {
            throw invalid("effective context workspace mismatch");
        }
        Set<String> allowed = attachmentReferences(seed.references());
        Set<String> inherited = new HashSet<>();
        for (ContextMessage message : seed.initialContextSeed().messages()) {
            for (ContextMessage.Block block : message.blocks()) {
                if (block instanceof ContextMessage.AttachmentBlock(String attachmentId)) {
                    inherited.add(attachmentId);
                }
            }
        }
        if (!allowed.equals(inherited)) {
            throw invalid("effective context attachment references do not match its seed");
        }
        for (String id : inherited) {
            AttachmentRecords.AttachmentRow row = mapper.attachments().selectThreadAttachment(id, seed.parentThreadId());
            if (row == null || !"BOUND".equals(row.status()) || !target.workspaceId().equals(row.workspaceId())) {
                throw invalid("inherited attachment is outside the frozen parent scope");
            }
        }
    }

    /**
     * 白名单解析只接受精确 attachment 闭集，不从普通文本或不明引用类型取得权限。
     */
    private static Set<String> attachmentReferences(JsonArray references) {
        Set<String> result = new HashSet<>();
        for (JsonValue value : references.values()) {
            if (!(value instanceof JsonObject object)
                || !(object.get("kind") instanceof JsonText(String value2))
                || !"attachment".equals(value2)) {
                    continue;
                }
            if (!(object.get("attachmentId") instanceof JsonText(String value1))
                || !result.add(value1)) {
                throw invalid("effective context contains an invalid attachment reference");
            }
        }
        return Set.copyOf(result);
    }

    /**
     * 拒绝失败闭合，不能把损坏快照降级成可继续访问的无附件上下文。
     */
    private static TaskRepositoryException invalid(String message) {
        return new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE, message);
    }
}
