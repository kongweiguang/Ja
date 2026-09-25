// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.in;

import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** 公开与内部 Turn 请求共享的入站合同校验，不向 conversation 端口外暴露额外 API。 */
final class TurnStartRequestValidation {
    /** 工具类只承载同包构造边界，禁止实例化后形成可替换策略。 */
    private TurnStartRequestValidation() { }

    /**
     * 只接受 JA-RPC v1 的稳定标识词汇，内部续跑也不能绕过公开请求的无别名约束。
     */
    static String identifier(String value, String prefix, String field) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
                || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /** 在请求边界固定绝对规范路径，避免公开与内部 Turn 对同一 Workspace 产生不同身份。 */
    static Path workspaceRoot(Path value) {
        return Objects.requireNonNull(value, "workspaceRoot").toAbsolutePath().normalize();
    }

    /**
     * 集中验证运行时选择和 CAS 游标，避免内部续跑重新引入整轮期限。
     */
    static void validateRuntime(String reasoningLevel, AccessMode accessMode,
                                CollaborationMode collaborationMode,
                                long expectedThreadRevision, long initialTurnMutationVersion,
                                Instant requestedAt) {
        if (reasoningLevel != null && !reasoningLevel.matches("off|minimal|low|medium|high|xhigh|max")) {
            throw new IllegalArgumentException("invalid reasoningLevel");
        }
        Objects.requireNonNull(accessMode, "accessMode");
        Objects.requireNonNull(collaborationMode, "collaborationMode");
        if (expectedThreadRevision < 0 || initialTurnMutationVersion < 0) {
            throw new IllegalArgumentException("turn revisions must be non-negative");
        }
        Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
