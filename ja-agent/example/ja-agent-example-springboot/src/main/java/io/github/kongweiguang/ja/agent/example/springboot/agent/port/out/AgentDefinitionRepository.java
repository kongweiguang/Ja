// @author kongweiguang
package io.github.kongweiguang.ja.agent.example.springboot.agent.port.out;

import io.github.kongweiguang.ja.agent.example.springboot.agent.domain.AgentDefinition;
import java.util.List;

/** Agent 定义读取端口；实现可在启动时播种并读取数据库。 */
public interface AgentDefinitionRepository {
    /** 返回本进程使用的完整定义快照，应用服务负责冻结。 */
    List<AgentDefinition> load();
}
