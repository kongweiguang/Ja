// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.goal.port.in.GoalEvent;

import java.util.Objects;

/** Goal 事件只投影聚合状态与活动，结构化问答由独立 Interaction 事件承接。 */
public final class GoalEventWireMapper {
    private final GoalWireMapper wire;

    /** 事件与查询必须共享同一个领域到 wire 映射规则。 */
    public GoalEventWireMapper(ObjectMapper mapper) {
        this.wire = new GoalWireMapper(Objects.requireNonNull(mapper, "mapper"));
    }

    /** 步骤与验收发布活动，问答等待只改变 Goal 阶段而不复制问题载荷。 */
    public WireEvent map(GoalEvent event) {
        Objects.requireNonNull(event, "event");
        String kind = event.activity().kind();
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
