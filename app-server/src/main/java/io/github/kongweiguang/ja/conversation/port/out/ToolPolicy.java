// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.foundation.validation.ContractChecks;
import java.util.Objects;

/**
 * Tool 执行前的同步限制策略；策略只能拒绝已解析调用，不能改写参数、执行 Tool 或放宽内核权限。
 */
public interface ToolPolicy {
    /** 返回进程内稳定身份，供确定性排序、重复检测与安全诊断使用。 */
    String id();

    /** 返回显式优先级；同优先级按稳定身份排序，避免依赖容器注入顺序。 */
    default int order() {
        return 0;
    }

    /** 对不可变调用上下文返回继续或结构化拒绝；实现必须保持同步且不得产生外部副作用。 */
    Decision evaluate(Context context);

    /** 策略判断所需的调用、Turn 权限环境与 Tool 静态副作用声明。 */
    record Context(
            AgentTool.Invocation invocation,
            AgentTool.ExecutionContext execution,
            ToolSideEffect sideEffect) {
        /** 固化判断输入，避免策略从全局运行时反向取得漂移配置。 */
        public Context {
            Objects.requireNonNull(invocation, "invocation");
            Objects.requireNonNull(execution, "execution");
            Objects.requireNonNull(sideEffect, "sideEffect");
        }
    }

    /** 策略唯一控制结果；拒绝只形成普通 Tool Result，不改变审批与执行器所有权。 */
    record Decision(boolean proceed, String code, String message) {
        /** 拒绝无意义字段组合，确保 Runner 不必猜测策略是否真的拒绝。 */
        public Decision {
            if (proceed) {
                if (code != null || message != null) {
                    throw new IllegalArgumentException("allowed Tool decision cannot contain rejection details");
                }
            } else {
                code = ContractChecks.identifier(code, "code");
                message = ContractChecks.text(message, "message", 65_536, false);
            }
        }

        /** 创建无附加信息的继续决定。 */
        public static Decision allow() {
            return new Decision(true, null, null);
        }

        /** 创建可作为安全 Tool 结果持久化的拒绝决定。 */
        public static Decision deny(String code, String message) {
            return new Decision(false, code, message);
        }
    }
}
