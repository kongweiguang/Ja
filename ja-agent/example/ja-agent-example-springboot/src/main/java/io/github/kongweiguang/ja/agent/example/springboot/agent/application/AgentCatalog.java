// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.agent.application;

import io.github.kongweiguang.ja.agent.example.springboot.agent.domain.AgentDefinition;
import io.github.kongweiguang.ja.agent.example.springboot.agent.port.out.AgentDefinitionRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.function.Function;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 启动时冻结 Persona，创建 Thread 与后续 Turn 使用相同角色身份。 */
@Service
public final class AgentCatalog {
    public static final String DEFAULT_AGENT_ID = "general";
    private final Map<String, AgentDefinition> definitions;

    /** 拒绝重复或缺失默认角色，避免重启后静默改变旧 Thread 的 Persona。 */
    public AgentCatalog(AgentDefinitionRepository repository) {
        definitions = repository.load().stream().collect(Collectors.toUnmodifiableMap(AgentDefinition::id, Function.identity()));
        if (!definitions.containsKey(DEFAULT_AGENT_ID)) {
            throw new IllegalStateException("default agent is missing");
        }
    }

    /** 浏览器只得到公开选择信息，不下发完整 System Prompt。 */
    public List<AgentView> list() {
        return definitions.values().stream().map(agent -> new AgentView(agent.id(), agent.name()))
                .sorted(Comparator.comparing(AgentView::id)).toList();
    }

    /** 模型每轮按 Thread 绑定查找冻结的正文，未知角色不能回退到默认值。 */
    public String prompt(String agentId) {
        AgentDefinition definition = definitions.get(agentId);
        if (definition == null) {
            throw new IllegalArgumentException("unknown agent id");
        }
        return definition.systemPrompt();
    }

    /** 创建时选择默认角色并立即验证，避免无效身份写入目录。 */
    public String requireAgentId(String agentId) {
        String selected = agentId == null ? DEFAULT_AGENT_ID : agentId;
        prompt(selected);
        return selected;
    }

    /**
     * 页面选择需要的最小视图。
     *
     * @param id 可用于创建 Thread 的稳定角色标识
     * @param name 供用户识别角色的显示名称
     */
    public record AgentView(String id,
                            String name) { }
}
