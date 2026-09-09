// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * JA-RPC v1 客户端请求的封闭方法词汇；Wire 名称只在 transport 边界解析一次。
 */
public enum RpcMethod {
    /**
     * 初始化运行时并协商固定能力。
     */
    RUNTIME_INITIALIZE("runtime/initialize"),
    /**
     * 读取脱敏运行时健康状态。
     */
    RUNTIME_HEALTH("runtime/health"),
    /**
     * 停止接收请求并启动有界关闭。
     */
    RUNTIME_SHUTDOWN("runtime/shutdown"),
    /**
     * 打开一个物理工作区。
     */
    WORKSPACE_OPEN("workspace/open"),
    /**
     * 打开 Java 所有的通用工作区。
     */
    WORKSPACE_OPEN_GENERAL("workspace/open-general"),
    /**
     * 分页列出工作区。
     */
    WORKSPACE_LIST("workspace/list"),
    /**
     * 在当前 Thread 的 Workspace 中执行有界路径搜索。
     */
    WORKSPACE_PATH_SEARCH("workspace/path/search"),
    /**
     * 修改工作区信任状态。
     */
    WORKSPACE_SET_TRUST("workspace/set-trust"),
    /**
     * 注销工作区元数据。
     */
    WORKSPACE_UNREGISTER("workspace/unregister"),
    /**
     * 创建对话线程。
     */
    THREAD_CREATE("thread/create"),
    /**
     * 分页列出对话线程。
     */
    THREAD_LIST("thread/list"),
    /** 在一个 Workspace 内搜索 Thread 标题。 */
    THREAD_SEARCH("thread/search"),
    /**
     * 读取对话线程快照。
     */
    THREAD_READ("thread/read"),
    /** 通过 CAS 设置人工标题。 */
    THREAD_RENAME("thread/rename"),
    /** 通过 revision CAS 更新 Thread 的置顶事实。 */
    THREAD_PIN("thread/pin"),
    /** 通过 revision CAS 确认当前最新成功或失败 Turn 已被用户看到。 */
    THREAD_SEEN("thread/seen"),
    /** 通过 CAS 更新下一轮模型与权限偏好。 */
    THREAD_PREFERENCES_UPDATE("thread/preferences/update"),
    /**
     * 归档对话线程。
     */
    THREAD_ARCHIVE("thread/archive"),
    /** 通过 revision CAS 恢复已归档 Thread。 */
    THREAD_RESTORE("thread/restore"),
    /**
     * 删除对话线程。
     */
    THREAD_DELETE("thread/delete"),
    /**
     * 在空闲 Thread 上显式压缩上下文。
     */
    THREAD_COMPACT("thread/compact"),
    /** 读取一个 Goal 的完整权威投影。 */
    GOAL_READ("goal/read"),
    /** 分页读取 Goal 的持久事件流。 */
    GOAL_EVENTS_READ("goal/events/read"),
    /** 为当前连接建立 Goal 高频观察。 */
    GOAL_OBSERVE("goal/observe"),
    /** 释放当前连接持有的 Goal 观察句柄。 */
    GOAL_UNOBSERVE("goal/unobserve"),
    /** 读取 Thread 所有的独立 Plan 投影。 */
    PLAN_READ("plan/read"),
    /** 分页读取独立 Plan 的不可变 revision 历史。 */
    PLAN_REVISIONS_LIST("plan/revisions/list"),
    /** 分页读取当前 Plan revision 的可信验收证据。 */
    GOAL_EVIDENCE_LIST("goal/evidence/list"),
    /** 创建与 Plan 正交、可直接工作的 Goal。 */
    GOAL_CREATE("goal/create"),
    /** 将已批准的精确 Plan revision 关联到 Goal。 */
    GOAL_PLAN_ATTACH("goal/plan/attach"),
    /** 解除 Goal 当前 Plan 关联但保留双方历史。 */
    GOAL_PLAN_DETACH("goal/plan/detach"),
    /** 在先持久化状态后暂停 Goal 的自动续跑。 */
    GOAL_PAUSE("goal/pause"),
    /** 通过 revision CAS 恢复满足准入条件的 Goal。 */
    GOAL_RESUME("goal/resume"),
    /** 把 Goal 终结为 stopped，且不把错误误报为失败终态。 */
    GOAL_STOP("goal/stop"),
    /** 回答一个持久化且尚未过期的 Goal 输入请求。 */
    GOAL_INPUT_RESPOND("goal/input/respond"),
    /** 创建由 Thread 所有、与 Goal 正交的 Plan。 */
    PLAN_CREATE("plan/create"),
    /** 保存结构化 Plan draft，不把 Markdown 当权威数据。 */
    PLAN_DRAFT_SAVE("plan/draft/save"),
    /** 丢弃 draft，并显式保留旧批准版本的恢复语义。 */
    PLAN_DRAFT_DISCARD("plan/draft/discard"),
    /** 将 draft 冻结为不可变、可批准的 Plan revision。 */
    PLAN_PROPOSE("plan/propose"),
    /** 以 revision ID 和 canonical hash 批准一个精确 Plan 版本。 */
    PLAN_APPROVE("plan/approve"),
    /** 从已批准的精确版本显式启动 standalone Plan run。 */
    PLAN_EXECUTE("plan/execute"),
    /** 拒绝当前待批准版本并返回可编辑规划状态。 */
    PLAN_REJECT("plan/reject"),
    /** 用户首次发送时原子创建独立侧边任务及首个 Turn。 */
    TASK_CREATE("task/create"),
    /** 读取当前根任务下的侧边任务和完整 Subagent 树摘要。 */
    TASK_LIST("task/list"),
    /** 读取一个 Child Thread 的 lineage、seed 摘要、活动与 Mailbox。 */
    TASK_READ("task/read"),
    /** 为当前可见详情建立有界高频观察。 */
    TASK_OBSERVE("task/observe"),
    /** 释放连接私有的任务观察句柄。 */
    TASK_UNOBSERVE("task/unobserve"),
    /** 以服务端 activity sequence CAS 推进已读边界。 */
    TASK_SEEN("task/seen"),
    /** 只向目标 Mailbox 入队，不唤醒空闲任务。 */
    TASK_MESSAGE_SEND("task/message/send"),
    /** 持久化跟进并启动或排队新的 Child Turn。 */
    TASK_FOLLOWUP("task/followup"),
    /** 取消目标任务，ATTACHED 子树按生命周期递归传播。 */
    TASK_CANCEL("task/cancel"),
    /** 经过显式确认后原子删除一棵完整任务树。 */
    TASK_TREE_DELETE("task/tree/delete"),
    /** 一次读取冻结 change-set 的完整单文件 Diff。 */
    TURN_CHANGE_SET_READ("turn/change-set/read"),
    /** 分页读取已脱敏 Tool 输出。 */
    TOOL_ARTIFACT_READ("tool/artifact/read"),
    /** 从 Rust 私有 staging 导入一份受管草稿附件。 */
    ATTACHMENT_IMPORT("attachment/import"),
    /** 丢弃尚未绑定 Turn 的草稿附件。 */
    ATTACHMENT_DISCARD("attachment/discard"),
    /** 为图片或 UTF-8 文本建立短期受控预览 session。 */
    ATTACHMENT_PREVIEW_OPEN("attachment/preview/open"),
    /** 从预览 session 分段读取最多 64 KiB。 */
    ATTACHMENT_PREVIEW_READ("attachment/preview/read"),
    /** 幂等关闭预览 session。 */
    ATTACHMENT_PREVIEW_CLOSE("attachment/preview/close"),
    /**
     * 启动一次 Turn。
     */
    TURN_START("turn/start"),
    /**
     * 显式恢复一次已挂起 Turn，不在进程启动时自动继续外部副作用。
     */
    TURN_RESUME("turn/resume"),
    /**
     * 取消一次 Turn。
     */
    TURN_CANCEL("turn/cancel"),
    /** 为活动 Turn 追加一条普通后续输入。 */
    TURN_INPUT_ENQUEUE("turn/input/enqueue"),
    /** 把指定排队输入提升为下一个安全点 Steering。 */
    TURN_INPUT_PRIORITIZE("turn/input/prioritize"),
    /** 编辑尚未消费的排队输入。 */
    TURN_INPUT_UPDATE("turn/input/update"),
    /** 删除尚未消费的排队输入。 */
    TURN_INPUT_DELETE("turn/input/delete"),
    /**
     * 提交审批决定。
     */
    APPROVAL_RESPOND("approval/respond"),
    /**
     * 读取脱敏配置投影。
     */
    CONFIGURATION_READ("configuration/read"),
    /**
     * 按 RFC 7396 合并配置。
     */
    CONFIGURATION_PATCH("configuration/patch"),
    /**
     * 用完整文档替换配置。
     */
    CONFIGURATION_REPLACE("configuration/replace"),
    /**
     * 重置配置层。
     */
    CONFIGURATION_RESET("configuration/reset"),
    /**
     * 设置一个凭据。
     */
    CREDENTIAL_SET("credential/set"),
    /**
     * 删除一个凭据。
     */
    CREDENTIAL_DELETE("credential/delete"),
    /**
     * 分页列出 Skill。
     */
    SKILL_LIST("skill/list"),
    /**
     * 分页列出 MCP Server。
     */
    MCP_LIST("mcp/list"),
    /**
     * 测试 MCP Server 可用性。
     */
    MCP_TEST("mcp/test"),
    /** 对已保存 Provider/Model 执行一次严格限额真实验证。 */
    MODEL_TEST("model/test"),
    /**
     * 列出 MCP Server 暴露的 Tool。
     */
    MCP_LIST_TOOLS("mcp/list-tools");

    private static final Map<String, RpcMethod> BY_WIRE_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(RpcMethod::wireName, Function.identity()));
    private final String wireName;

    /**
     * 固定枚举与 Wire 名称的一对一关系，构造后不允许动态扩展。
     */
    RpcMethod(String wireName) {
        this.wireName = wireName;
    }

    /**
     * 返回 JA-RPC v1 合同中的精确方法名。
     */
    String wireName() {
        return wireName;
    }

    /** Codec 复用枚举的封闭词汇，避免另一个可漂移的客户端方法白名单。 */
    static Set<String> wireNames() {
        return BY_WIRE_NAME.keySet();
    }

    /**
     * 把已通过 frame 校验的 Wire 名称解析为封闭命令类型。
     */
    public static RpcMethod fromWireName(String wireName) {
        RpcMethod method = BY_WIRE_NAME.get(wireName);
        if (method == null) throw JaRpcException.methodNotFound();
        return method;
    }
}
