// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.goal.port.in.GoalEvent;

import java.util.Objects;

/** 将已提交 GoalEvent 映射到冻结的三事件闭集，公共连接元数据由 RpcSession 统一添加。 */
public final class GoalEventWireMapper {
    private final GoalWireMapper wire;

    /** 事件与查询必须共享同一个领域到 wire 映射规则。 */
    public GoalEventWireMapper(ObjectMapper mapper) {
        this.wire = new GoalWireMapper(Objects.requireNonNull(mapper, "mapper"));
    }

    /** input 使用专用提示事件，步骤/evaluator/recovery 使用活动事件，其余发布完整状态。 */
    public WireEvent map(GoalEvent event) {
        Objects.requireNonNull(event, "event");
        String kind = event.activity().kind();
        if ("input_requested".equals(kind) && event.snapshot().pendingInput() != null) {
            return new WireEvent("goal/input-requested", wire.input(event.snapshot()));
        }
        if ("step_changed".equals(kind) || kind.startsWith("evaluation_")
                || "recovery_required".equals(kind)) {
            return new WireEvent("goal/activity", wire.activity(event.snapshot(), event.activity()));
        }
        return new WireEvent("goal/changed", wire.changed(event.snapshot()));
    }

    /** 保持 method 与 params 原子返回，调用方不能错配事件类型。 */
    public record WireEvent(String method, ObjectNode params) {
        /** 事件参数在构造时复制，避免映射完成后被原始节点引用修改。 */
        public WireEvent {
            params = Objects.requireNonNull(params, "params").deepCopy();
        }

        /** 每次读取返回独立节点，RpcSession 的元数据附加不能污染缓存事件。 */
        @Override public ObjectNode params() { return params.deepCopy(); }
    }
}
