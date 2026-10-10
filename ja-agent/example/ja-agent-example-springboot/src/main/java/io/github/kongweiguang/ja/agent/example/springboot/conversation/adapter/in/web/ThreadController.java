// @author kongweiguang
package io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web;

import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.ThreadService;
import io.github.kongweiguang.ja.agent.example.springboot.agent.application.AgentCatalog;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActorProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话管理保留 POST JSON 契约，身份固定由示例宿主确定。
 */
@RestController
@RequestMapping("/api/chat/threads")
public final class ThreadController {
    private final ThreadService threads;
    private final AgentCatalog prompts;
    private final CurrentActorProvider actors;

    /** Controller 只映射公开请求并传递服务端 actor；事务与状态约束由服务层集中处理。 */
    public ThreadController(ThreadService threads, AgentCatalog prompts, CurrentActorProvider actors) {
        this.threads = threads;
        this.prompts = prompts;
        this.actors = actors;
    }

    /** 返回启动时冻结的角色目录，页面只需名称和 ID。 */
    @PostMapping("/agents")
    public List<AgentCatalog.AgentView> agents() {
        return prompts.list();
    }

    /**
     * 创建操作使用稳定键，浏览器超时后可以原样重试。
     */
    @PostMapping("/create")
    public ThreadService.ThreadView create(@RequestBody Create command) {
        return threads.create(actors.currentActor(), command.operationKey(), command.agentId());
    }

    /**
     * 有界列表在浏览器搜索时只读元数据，不扫描全部执行记录。
     */
    @PostMapping("/list")
    public List<ThreadService.ThreadView> list(@RequestBody ListQuery query) {
        return threads.list(actors.currentActor(), query.archived(), query.search(), query.limit(), query.offset());
    }

    /**
     * 客户端传观察版本，冲突必须回读后再次决定修改。
     */
    @PostMapping("/update")
    public ThreadService.ThreadView update(@RequestBody Update command) {
        return threads.update(actors.currentActor(), command.threadId(), command.expectedVersion(), command.title(), command.archived());
    }

    /**
     * 首次返回固定水位；后续页带回原水位以避免并发提交混入历史页。
     */
    @PostMapping("/history")
    public ThreadService.HistoryPage history(@RequestBody HistoryQuery query) {
        return threads.history(actors.currentActor(), query.threadId(), query.afterSequence(), query.throughSequence(), query.limit());
    }

    /**
     * 创建命令只接受调用方稳定操作键，避免把 Thread 身份生成权交给浏览器。
     *
     * @param operationKey 业务操作标识，重试时复用以识别同一操作
     * @param agentId 选中的角色，省略时使用通用助手
     */
    public record Create(String operationKey,
                         String agentId) { }

    /**
     * 列表查询显式携带筛选与分页边界，避免无界加载全部 Thread。
     *
     * @param archived 是否已归档
     * @param search 搜索
     * @param limit 返回条目数上限
     * @param offset 读取起点的偏移
     */
    public record ListQuery(boolean archived,
                            String search,
                            int limit,
                            int offset) { }

    /**
     * 更新命令使用 Thread 版本 CAS，结果未知时调用方必须先回读。
     *
     * @param threadId 所属对话的稳定标识
     * @param expectedVersion 预期版本
     * @param title 显示标题
     * @param archived 是否已归档
     */
    public record Update(String threadId,
                         long expectedVersion,
                         String title,
                         Boolean archived) { }

    /**
     * 历史查询固定序列水位，保证翻页期间不会混入新提交。
     *
     * @param threadId 所属对话的稳定标识
     * @param afterSequence 从该序号之后开始读取
     * @param throughSequence 当前快照覆盖到的顺序号
     * @param limit 返回条目数上限
     */
    public record HistoryQuery(String threadId,
                               long afterSequence,
                               Long throughSequence,
                               int limit) { }
}
