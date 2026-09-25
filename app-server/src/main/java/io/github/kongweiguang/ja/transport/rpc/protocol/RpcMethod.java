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
    /** 为认证连接登记仅在 Java 内存保存的完整原生执行环境。 */
    RUNTIME_CONTEXT_REGISTER("runtime/context/register"),
    /** 查询有副作用请求是否已有持久提交回执，断线后不能盲目重发。 */
    OPERATION_READ("operation/read"),
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
    /** 按已提交消息身份分页读取公开正文，单帧只交付一个 Unicode 字符片段。 */
    THREAD_MESSAGE_CONTENT_READ("thread/message-content/read"),
    /** 分页搜索主会话已提交的公开用户输入，供终端 Ctrl+R 恢复。 */
    HISTORY_INPUT_SEARCH("history/input/search"),
    /** 显式声明当前连接正在观察 Thread；事件基线仍由独立 read 获取。 */
    THREAD_OBSERVE("thread/observe"),
    /** 释放连接的观察意图，不影响后台 Turn 生命周期。 */
    THREAD_UNOBSERVE("thread/unobserve"),
    /** 读取一个 Thread 的累计 Token 计量，不物化历史页。 */
    THREAD_USAGE_READ("thread/usage/read"),
    /** 读取当前 Thread 最近一次观测到的 MCP 目录，只返回脱敏状态。 */
    THREAD_MCP_READ("thread/mcp/read"),
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
    /** 停止当前连接正在运行的手动压缩，不影响其它会话和自动压缩。 */
    THREAD_COMPACT_CANCEL("thread/compact/cancel"),
    /** 读取当前 Thread 的待回答请求或指定历史问答。 */
    INTERACTION_READ("interaction/read"),
    /** 先建立连接过滤再对账快照，避免提问创建与 UI 订阅竞态丢失。 */
    INTERACTION_OBSERVE("interaction/observe"),
    /** 收起或切换会话只释放观察，不取消权威问题。 */
    INTERACTION_UNOBSERVE("interaction/unobserve"),
    /** 保存未提交答案以支持窗口刷新与重启恢复。 */
    INTERACTION_DRAFT_SAVE("interaction/draft/save"),
    /** 原子结算答案及原 Tool 调用，不能以普通用户消息冒充回答。 */
    INTERACTION_RESPOND("interaction/respond"),
    /** 显式取消问答，关闭卡片不走此入口。 */
    INTERACTION_CANCEL("interaction/cancel"),
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
    /** 当前 Thread 首次显示时恢复最近计划，不依赖 Goal identity。 */
    PLAN_CURRENT_READ("plan/current/read"),
    /** 独立 Plan 事件不能借用 Goal 水位。 */
    PLAN_EVENTS_READ("plan/events/read"),
    /** 订阅当前连接可见的独立 Plan。 */
    PLAN_OBSERVE("plan/observe"),
    /** 关闭详情不停止 Plan 执行。 */
    PLAN_UNOBSERVE("plan/unobserve"),
    /** 证据精确绑定 Plan revision 与 Run。 */
    PLAN_EVIDENCE_LIST("plan/evidence/list"),
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
    /** 创建由 Thread 所有、与 Goal 正交的 Plan。 */
    PLAN_CREATE("plan/create"),
    /** 保存结构化 Plan draft，不把 Markdown 当权威数据。 */
    PLAN_DRAFT_SAVE("plan/draft/save"),
    /** 丢弃 draft，并显式保留旧批准版本的恢复语义。 */
    PLAN_DRAFT_DISCARD("plan/draft/discard"),
    /** 将 draft 冻结为不可变、可批准的 Plan revision。 */
    PLAN_PROPOSE("plan/propose"),
    /** 从已批准的精确版本显式启动 standalone Plan run。 */
    PLAN_EXECUTE("plan/execute"),
    /** 停止领取新 Tool 并在安全结算后保留原 Run。 */
    PLAN_PAUSE("plan/pause"),
    /** 恢复原 Run 剩余工作，不重置预算或重复执行成功步骤。 */
    PLAN_RESUME("plan/resume"),
    /** 保留结果与审计历史，不隐式回滚工作区。 */
    PLAN_STOP("plan/stop"),
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
    /** 向任意当前实例中的 Thread Mailbox 入队，不唤醒目标或打断其当前 Turn。 */
    THREAD_MESSAGE_SEND("thread/message/send"),
    /** 持久化跟进并启动或排队新的 Child Turn。 */
    TASK_FOLLOWUP("task/followup"),
    /** 取消目标任务，ATTACHED 子树按生命周期递归传播。 */
    TASK_CANCEL("task/cancel"),
    /** 经过显式确认后原子删除一棵完整任务树。 */
    TASK_TREE_DELETE("task/tree/delete"),
    /** 关闭临时独立侧聊并释放其运行资源，不删除已投递到其它 Thread 的消息。 */
    TASK_CLOSE("task/close"),
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
    /** 在同一问题下创建隐藏 continuation Turn，不发送额外 USER message。 */
    TURN_CONTINUE("turn/continue"),
    /** 编辑当前路径最后一个未答问题，并原子切换至新 USER Turn。 */
    TURN_REASK("turn/reask"),
    /**
     * 显式恢复一次已挂起 Turn，不在进程启动时自动继续外部副作用。
     */
    TURN_RESUME("turn/resume"),
    /**
     * 仅提交当前未知 Tool 的显式重试或跳过裁决；最后一项的自动续跑仍复用 turn/resume。
     */
    TURN_RECOVERY_RESPOND("turn/recovery/respond"),
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
     * 从最近完整用户快照恢复配置，并保留当前原始文件备份。
     */
    CONFIGURATION_RESTORE("configuration/restore"),
    /**
     * 设置一个凭据。
     */
    CREDENTIAL_SET("credential/set"),
    /**
     * 删除一个凭据。
     */
    CREDENTIAL_DELETE("credential/delete"),
    /** 用户编辑 Provider 时短时回显其绑定 API Key。 */
    CREDENTIAL_REVEAL_PROVIDER("credential/reveal-provider"),
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
    /** 从已保存 Provider 的上游目录读取模型标识，不修改配置。 */
    MODEL_DISCOVER("model/discover"),
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
