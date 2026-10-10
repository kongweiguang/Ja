// @author kongweiguang
package io.github.kongweiguang.ja.agent.example.springboot.conversation.port.out;

import io.github.kongweiguang.ja.agent.example.springboot.conversation.domain.ThreadMetadata;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActor;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.application.gateway.store.ConversationTransactionContext;

import java.util.List;

/** 会话目录端口；Core 事务上下文保证目录和执行事实原子提交。 */
public interface ThreadDirectory {
    /** 缺失由应用服务解释为公开的 Thread 不存在。 */
    ThreadMetadata find(CurrentActor actor, String threadId);

    /** 创建键用于网络结果未知时回读原会话。 */
    ThreadMetadata findByCreateKey(CurrentActor actor, String operationKey);

    /** 列表只查询元数据，不物化 Core 历史记录；search 由应用服务按 Locale.ROOT 规范化。 */
    List<ThreadMetadata> list(CurrentActor actor, boolean archived, String search, int limit, int offset);

    /** 与 Core 会话锚点使用同一事务连接；无 checked 回调要求适配器将 SQL 故障包装并保留 cause。 */
    void insert(ConversationTransactionContext context, CurrentActor actor, String threadId, String operationKey,
                String agentId);

    /** CAS 同时保护目录版本；运行时失败是 Store 回滚信号，SQL cause 由适配器保留。 */
    void update(ConversationTransactionContext context, CurrentActor actor, String threadId, long expectedVersion,
                ThreadMetadata.Change change);

    /** Engine 回调只允许运行时失败，以便 Store 原子回滚事实和目录；适配器保留 SQL cause。 */
    void onExecutionCommit(AgentStoreTransactionContext context, String title);
}
