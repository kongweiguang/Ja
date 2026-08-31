// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * JA-RPC v2 客户端请求的封闭方法词汇；Wire 名称只在 transport 边界解析一次。
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
    /** 通过 CAS 更新下一轮模型与权限偏好。 */
    THREAD_PREFERENCES_UPDATE("thread/preferences/update"),
    /**
     * 归档对话线程。
     */
    THREAD_ARCHIVE("thread/archive"),
    /**
     * 删除对话线程。
     */
    THREAD_DELETE("thread/delete"),
    /**
     * 在空闲 Thread 上显式压缩上下文。
     */
    THREAD_COMPACT("thread/compact"),
    /** Rust 提交 Turn 基线到终态的冻结文件差异。 */
    TURN_CHANGE_SET_COMMIT("turn/change-set/commit"),
    /** 分页读取冻结 change-set diff。 */
    TURN_CHANGE_SET_READ("turn/change-set/read"),
    /** 分页读取已脱敏 Tool 输出。 */
    TOOL_ARTIFACT_READ("tool/artifact/read"),
    /** 从 Rust 私有 staging 导入一份受管草稿附件。 */
    ATTACHMENT_IMPORT("attachment/import"),
    /** 丢弃尚未绑定 Turn 的草稿附件。 */
    ATTACHMENT_DISCARD("attachment/discard"),
    /**
     * 启动一次 Turn。
     */
    TURN_START("turn/start"),
    /**
     * 取消一次 Turn。
     */
    TURN_CANCEL("turn/cancel"),
    /** 为活动 Turn 追加一条 Tool 边界 steering。 */
    TURN_STEER("turn/steer"),
    /** 为活动 Turn 追加一条结束前 follow-up。 */
    TURN_FOLLOW_UP("turn/follow-up"),
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
     * 返回 JA-RPC v2 合同中的精确方法名。
     */
    String wireName() {
        return wireName;
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
