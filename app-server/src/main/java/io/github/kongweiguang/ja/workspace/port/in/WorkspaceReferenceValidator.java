// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.port.in;

import io.github.kongweiguang.ja.workspace.domain.WorkspaceEntryKind;

import java.util.Objects;

/**
 * Conversation 对 Workspace 引用的唯一窄准入端口，不向消费者暴露绝对路径或 NIO 对象。
 */
@FunctionalInterface
public interface WorkspaceReferenceValidator {
    /** 按 Thread 权威 Workspace、物理 containment 与目标类型重新验证引用。 */
    ValidatedReference validate(ValidationRequest request);

    /** 调用方必须传入从 Thread 权威快照读取的 Workspace，而不是客户端配置快照。 */
    record ValidationRequest(String threadWorkspaceId, String workspaceId,
                             String relativePath, WorkspaceEntryKind kind) {
        /** DTO 只做缺失与有界校验，路径解析严格留在 Workspace owner 内。 */
        public ValidationRequest {
            WorkspaceReferenceValues.requireWorkspaceId(threadWorkspaceId, "threadWorkspaceId");
            WorkspaceReferenceValues.requireWorkspaceId(workspaceId, "workspaceId");
            WorkspaceReferenceValues.requireRelativePath(relativePath);
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** 验证结果只返回规范相对路径，使模型投影无法取得宿主绝对路径。 */
    record ValidatedReference(String workspaceId, String relativePath, WorkspaceEntryKind kind) {
        /** 冻结已经准入的值，防止消费者重新拼接或丢失类型。 */
        public ValidatedReference {
            WorkspaceReferenceValues.requireWorkspaceId(workspaceId, "workspaceId");
            WorkspaceReferenceValues.requireRelativePath(relativePath);
            Objects.requireNonNull(kind, "kind");
        }
    }
}

/** Wire DTO 的共享值规则保持 package-private，避免为静态分析扩大 Workspace 公共端口。 */
final class WorkspaceReferenceValues {
    /** 共享值规则只服务同包 wire DTO，禁止实例化以保持端口表面最小。 */
    private WorkspaceReferenceValues() {
    }

    /** Workspace identity 在请求与验证结果中使用同一有界规则，字段名仅用于稳定诊断。 */
    static void requireWorkspaceId(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > 100 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid " + field);
        }
    }

    /** 相对路径这里只限制 wire 预算与 NUL；containment 和规范化仍由 Workspace owner 决定。 */
    static void requireRelativePath(String value) {
        Objects.requireNonNull(value, "relativePath");
        if (value.isBlank() || value.length() > 4_096 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid relativePath");
        }
    }
}
