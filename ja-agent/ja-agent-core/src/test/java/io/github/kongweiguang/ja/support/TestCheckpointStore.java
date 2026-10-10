// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.support;
import io.github.kongweiguang.ja.agent.domain.context.ToolProjection;

import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 纯策略测试显式选择的内存夹具；生产 CheckpointStore 不提供隐式内存默认实现。 */
public abstract class TestCheckpointStore implements CheckpointStore {
    private final Map<String, ProjectionSnapshot> projections = new HashMap<>();

    /** 测试内保留已冻结阶段，使重复请求不能重新选择同一工具结果。 */
    @Override public ProjectionSnapshot prepareProjection(ProjectionRequest request) {
        ProjectionSnapshot current = projections.get(request.threadId());
        return current != null && current.binding().equals(request.binding()) ? current
                : advanceProjection(request, current == null ? ProjectionReason.INITIAL : ProjectionReason.MODEL_BINDING);
    }

    /** 仅测试夹具使用随机阶段身份，生产实现必须从持久身份确定性派生。 */
    @Override public ProjectionSnapshot advanceProjection(ProjectionRequest request, ProjectionReason reason) {
        ProjectionSnapshot previous = projections.get(request.threadId());
        ProjectionSnapshot next = new ProjectionSnapshot("projection_" + UUID.randomUUID(), request.threadId(),
                previous == null ? 1 : previous.stageNumber() + 1, request.sourceRevision(), reason, request.binding(), Map.of());
        projections.put(request.threadId(), next);
        return next;
    }

    /** 复制测试选择，避免测试调用方修改 Map 造成与真实持久实现不同的行为。 */
    @Override public ProjectionSnapshot persistProjection(ProjectionSnapshot snapshot,
            Map<String, ToolProjection> selections) {
        ProjectionSnapshot frozen = new ProjectionSnapshot(snapshot.stageId(), snapshot.threadId(), snapshot.stageNumber(),
                snapshot.sourceRevision(), snapshot.reason(), snapshot.binding(), selections);
        projections.put(snapshot.threadId(), frozen);
        return frozen;
    }
}
