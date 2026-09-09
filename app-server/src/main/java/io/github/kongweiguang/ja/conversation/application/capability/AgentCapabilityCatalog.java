// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.capability;

import io.github.kongweiguang.ja.conversation.port.out.AgentCapability;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 冻结编译期能力顺序，并把请求级 prepare 结果合并为一个可验证的不可变目录。
 */
public final class AgentCapabilityCatalog {
    private final List<Registration> capabilities;

    /** 启动时只固定注册身份；动态业务状态仍在每次请求 prepare 的安全点读取一次。 */
    public AgentCapabilityCatalog(List<AgentCapability> capabilities) {
        List<AgentCapability> provided = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        Set<String> identities = new HashSet<>();
        List<Registration> registrations = new ArrayList<>(provided.size());
        for (AgentCapability capability : provided) {
            Objects.requireNonNull(capability, "capability");
            String id = capability.id();
            if (id == null || !id.matches("[a-z][a-z0-9_.-]{0,127}") || !identities.add(id)) {
                throw new IllegalArgumentException("invalid or duplicate Agent capability id");
            }
            registrations.add(new Registration(id, capability.order(), capability));
        }
        this.capabilities = registrations.stream()
                .sorted(Comparator.comparingInt(Registration::order).thenComparing(Registration::id))
                .toList();
    }

    /** 按冻结顺序 prepare 每个能力，并在模型看见目录前拒绝请求级 Tool 重名。 */
    public PreparedCapabilities prepare(AgentCapability.Request request) {
        Objects.requireNonNull(request, "request");
        List<AgentCapability.Prepared> entries = new ArrayList<>(capabilities.size());
        Set<String> toolNames = new HashSet<>();
        for (Registration registration : capabilities) {
            AgentCapability.Prepared prepared = Objects.requireNonNull(
                    registration.capability().prepare(request), "prepared capability");
            for (AgentCapability.ToolContribution tool : prepared.tools()) {
                if (!toolNames.add(tool.spec().name())) {
                    throw new IllegalArgumentException("duplicate capability Tool name");
                }
            }
            entries.add(prepared);
        }
        return new PreparedCapabilities(entries);
    }

    /**
     * 请求级预备目录把一次领域读取与最终 catalog 身份绑定分开，专门消除 Task ceiling 的摘要自引用。
     */
    public static final class PreparedCapabilities {
        private final List<AgentCapability.ToolContribution> tools;
        private final String promptFragment;

        /** 合并只使用已经冻结的 prepare 值，不执行 Tool 工厂或再次访问领域 owner。 */
        private PreparedCapabilities(List<AgentCapability.Prepared> entries) {
            List<AgentCapability.Prepared> frozen = List.copyOf(entries);
            this.tools = frozen.stream().flatMap(entry -> entry.tools().stream()).toList();
            this.promptFragment = frozen.stream().map(AgentCapability.Prepared::promptFragment)
                    .filter(value -> !value.isBlank()).reduce((left, right) -> left + "\n" + right).orElse("");
        }

        /** 返回模型可见的确定顺序说明；动态身份已经在同一次 prepare 内冻结。 */
        public String promptFragment() {
            return promptFragment;
        }

        /** 返回最终 Tool 摘要计算所需的冻结安全描述，不触发实际 Tool 物化。 */
        public List<AgentCapability.ToolContribution> toolContributions() {
            return List.copyOf(tools);
        }

        /**
         * 注入最终目录身份并物化 Tool；逐项核验防止 binder 替换 Schema、权限元数据或执行路由。
         */
        public AgentCapability.Binding bind(AgentCapability.CatalogIdentity identity) {
            Objects.requireNonNull(identity, "identity");
            List<AgentTool> bound = new ArrayList<>(tools.size());
            for (AgentCapability.ToolContribution contribution : tools) {
                AgentTool tool = Objects.requireNonNull(contribution.binder().apply(identity), "bound Tool");
                if (!contribution.spec().equals(tool.spec())
                        || contribution.sideEffect() != tool.sideEffect()
                        || contribution.workspaceMutationMode() != tool.workspaceMutationMode()
                        || !contribution.bindingDescriptor().equals(tool.bindingDescriptor())) {
                    throw new IllegalStateException("Agent capability Tool changed after prepare");
                }
                bound.add(tool);
            }
            return new AgentCapability.Binding(promptFragment, bound);
        }
    }

    /** 注册身份在构造期读取一次，恶意或有状态实现不能在请求间改变排序与摘要身份。 */
    private record Registration(String id, int order, AgentCapability capability) {
    }
}
