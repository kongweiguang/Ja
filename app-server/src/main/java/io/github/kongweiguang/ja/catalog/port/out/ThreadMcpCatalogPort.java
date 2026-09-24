// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.port.out;

import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.port.out.McpGateway;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationGenerationSnapshot;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 通过同一个工作区 MCP 目录 owner 保存有界派发观测。 */
public interface ThreadMcpCatalogPort {
    /** 返回一份脱敏内存观测，不打开或刷新 MCP 传输。 */
    Optional<Observation> observation(String threadId);

    /** 请求运行时构造真实目录后才发布派发快照。 */
    void observe(Observation observation);

    /** 确认共享目录在观测发布后未收到通知或更新发现。 */
    boolean current(Observation observation);

    /** 内部观测键不含地址、Header、环境或凭据。 */
    record Observation(String threadId, String workspaceId, Path workspaceRoot, String generationId,
                       String preferenceFingerprint, String ceilingFingerprint,
                       CollaborationMode collaborationMode, String turnId, String catalogRevision,
                       Instant observedAt, List<McpGateway.McpServerStatus> servers,
                       Map<String, ConfigurationGenerationSnapshot.Scope> scopes) {
        /** 冻结身份、代际与脱敏服务投影，供有界会话缓存持有。 */
        public Observation {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(workspaceId, "workspaceId");
            workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot").toAbsolutePath().normalize();
            Objects.requireNonNull(generationId, "generationId");
            Objects.requireNonNull(preferenceFingerprint, "preferenceFingerprint");
            if (!preferenceFingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid preference fingerprint");
            }
            if (ceilingFingerprint != null && !ceilingFingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid capability ceiling fingerprint");
            }
            Objects.requireNonNull(collaborationMode, "collaborationMode");
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(catalogRevision, "catalogRevision");
            Objects.requireNonNull(observedAt, "observedAt");
            servers = List.copyOf(Objects.requireNonNull(servers, "servers"));
            scopes = Map.copyOf(Objects.requireNonNull(scopes, "scopes"));
        }
    }

}
