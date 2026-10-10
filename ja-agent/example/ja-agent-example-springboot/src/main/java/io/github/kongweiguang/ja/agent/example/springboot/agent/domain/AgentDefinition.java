// @author kongweiguang
package io.github.kongweiguang.ja.agent.example.springboot.agent.domain;

/**
 * 宿主定义的完整 Persona；提示词只在服务端运行时使用。
 *
 * @param id 持久化目录中的稳定角色标识
 * @param name 供用户选择角色时查看的名称
 * @param systemPrompt 仅在服务端组装模型请求时使用的系统提示词
 */
public record AgentDefinition(String id,
                              String name,
                              String systemPrompt) {
    /** 启动时校验数据库快照，避免损坏定义进入模型请求。 */
    public AgentDefinition {
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,63}") || name == null || name.isBlank()
                || name.length() > 80 || systemPrompt == null || systemPrompt.isBlank()
                || systemPrompt.length() > 32_768) {
            throw new IllegalArgumentException("invalid agent definition");
        }
    }
}
