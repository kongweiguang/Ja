// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.acl;

import io.github.kongweiguang.ja.conversation.application.gateway.ConversationWorkspaceReferencePort;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceReferenceValidator;
import io.github.kongweiguang.ja.workspace.domain.model.WorkspaceEntryKind;
import io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure;
import io.github.kongweiguang.ja.workspace.domain.model.WorkspacePathFailure;

import java.util.Objects;

/**
 * 将 Workspace owner 的路径校验与失败分类收敛为 Conversation 的窄 ACL，避免跨域异常泄漏。
 */
public final class ConversationWorkspaceReferenceAcl implements ConversationWorkspaceReferencePort {
    private final WorkspaceReferenceValidator validator;

    /**
     * 组合根注入 Workspace 唯一校验端口，ACL 只负责类型和异常转换。
     */
    public ConversationWorkspaceReferenceAcl(WorkspaceReferenceValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    /**
     * 先解析公开 kind，再调用 Workspace owner；失败保留原始 cause 供本地诊断但不暴露其类型。
     */
    @Override
    public ValidatedReference validate(String threadWorkspaceId, String workspaceId,
                                      String relativePath, String kind) {
        WorkspaceEntryKind entryKind = switch (kind) {
            case "file" -> WorkspaceEntryKind.FILE;
            case "directory" -> WorkspaceEntryKind.DIRECTORY;
            default -> throw new IllegalArgumentException("unsupported Ja resource kind");
        };
        try {
            WorkspaceReferenceValidator.ValidatedReference checked = validator.validate(
                    new WorkspaceReferenceValidator.ValidationRequest(
                            threadWorkspaceId, workspaceId, relativePath, entryKind));
            return new ValidatedReference(checked.workspaceId(), checked.relativePath(),
                    checked.kind() == WorkspaceEntryKind.FILE ? "file" : "directory");
        } catch (WorkspacePathFailure | WorkspaceFailure failure) {
            Failure mapped = new Failure("workspace-reference-invalid");
            mapped.initCause(failure);
            throw mapped;
        }
    }

    /**
     * Conversation 只需要知道引用无效；Workspace 细分码和绝对路径留在 owner 诊断中。
     */
    public static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /**
         * 固定 ACL 失败文本，避免下游依赖 Workspace message 形成隐式协议。
         */
        public Failure(String message) {
            super(message);
        }
    }
}
