// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 对请求级派生 System 片段执行同步纯变换；权威 AGENTS、Skills、消息、身份和权限不进入该端口。
 */
public interface ContextTransform {
    /** 返回进程级稳定身份，用于确定排序与启动时冲突检查。 */
    String id();

    /** 返回显式顺序；同序由稳定身份打破平局，避免依赖注入容器的枚举顺序。 */
    default int order() {
        return 0;
    }

    /**
     * 基于前序派生片段返回新的不可变视图；实现应保持短时、确定且无 IO，异常会阻止请求发送。
     */
    DerivedContext transform(DerivedContext context);

    /** 仅承载扩展产生的 System 片段，使变换器无法重写 Prompt 的权威部分。 */
    record DerivedContext(List<SystemFragment> systemFragments) {
        /** 防御性复制并拒绝重名片段，保证后序变换可按稳定身份精确裁剪。 */
        public DerivedContext {
            systemFragments = List.copyOf(Objects.requireNonNull(systemFragments, "systemFragments"));
            HashSet<String> identities = new HashSet<>();
            for (SystemFragment fragment : systemFragments) {
                if (fragment == null || !identities.add(fragment.id())) {
                    throw new IllegalArgumentException("derived context contains invalid fragment identities");
                }
            }
        }

        /** 从零派生片段启动每次请求，避免把上一轮变换结果误当作权威会话状态。 */
        public static DerivedContext empty() {
            return new DerivedContext(List.of());
        }
    }

    /** 带稳定身份的模型可见派生片段；身份与正文共同进入最终 Prompt revision。 */
    record SystemFragment(String id, String content) {
        /** 拒绝匿名、空白或 NUL 正文，避免产生不可定位或 Provider 不可表示的片段。 */
        public SystemFragment {
            id = ContractChecks.identifier(id, "context fragment id");
            content = Objects.requireNonNull(content, "content");
            if (content.isBlank() || content.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("context fragment content must be non-blank text without NUL");
            }
        }
    }
}
