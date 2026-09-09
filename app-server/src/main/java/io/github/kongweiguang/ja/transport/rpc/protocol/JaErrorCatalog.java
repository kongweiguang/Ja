// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 在编译期镜像冻结的 JA-RPC v1 错误目录，保证数值码、分类与重试语义唯一。
 */
public enum JaErrorCatalog {
    /**
     * JSONL 信封结构、编码或字段角色不符合 JA-RPC v1。
     */
    INVALID_FRAME(-32001, ErrorCategory.PROTOCOL, false),
    /**
     * 单帧字节数超过协商的硬上限。
     */
    FRAME_TOO_LARGE(-32002, ErrorCategory.CAPACITY, false),
    /**
     * 客户端请求的协议版本不在冻结支持范围内。
     */
    PROTOCOL_VERSION_UNSUPPORTED(-32003, ErrorCategory.PROTOCOL, false),
    /**
     * 当前连接尚未完成 runtime/initialize 握手。
     */
    NOT_INITIALIZED(-32004, ErrorCategory.CONFLICT, false),
    /**
     * 当前连接已经初始化，禁止再次协商运行代际。
     */
    ALREADY_INITIALIZED(-32005, ErrorCategory.CONFLICT, false),
    /**
     * 请求方法不属于当前方向允许的冻结方法集合。
     */
    METHOD_NOT_FOUND(-32006, ErrorCategory.NOT_FOUND, false),
    /**
     * 请求参数未通过严格字段、类型或取值校验。
     */
    INVALID_PARAMS(-32007, ErrorCategory.VALIDATION, false),
    /**
     * 进程级有界队列或关联容量已耗尽，调用方可稍后重试。
     */
    QUEUE_FULL(-32008, ErrorCategory.CAPACITY, true),
    /**
     * 单个 Thread 的排队 Turn 已达到上限，调用方可稍后重试。
     */
    THREAD_QUEUE_FULL(-32009, ErrorCategory.CAPACITY, true),
    /**
     * 配置内容违反严格 Schema 或业务约束。
     */
    CONFIG_INVALID(-32010, ErrorCategory.VALIDATION, false),
    /**
     * 配置 CAS 版本冲突，调用方应重新读取后重试。
     */
    CONFIG_CONFLICT(-32011, ErrorCategory.CONFLICT, true),
    /**
     * 持久化设施暂时不可用，调用方可在恢复后重试。
     */
    STORAGE_UNAVAILABLE(-32012, ErrorCategory.UNAVAILABLE, true),
    /**
     * 配置文档已损坏，不能按普通参数错误处理。
     */
    CONFIG_CORRUPTED(-32013, ErrorCategory.VALIDATION, false),
    /**
     * 请求在完成前超过其 Deadline，调用方可按契约重新发起。
     */
    REQUEST_DEADLINE_EXCEEDED(-32014, ErrorCategory.TIMEOUT, true),
    /**
     * 所选 Provider 缺少必需 Credential。
     */
    CREDENTIAL_MISSING(-32015, ErrorCategory.NOT_FOUND, false),
    /**
     * 运行时检测到必须先完成显式恢复的状态。
     */
    RECOVERY_REQUIRED(-32016, ErrorCategory.CONFLICT, false),
    /**
     * 请求引用的 Provider 或其下属 Model 不存在。
     */
    PROVIDER_OR_MODEL_NOT_FOUND(-32017, ErrorCategory.NOT_FOUND, false),
    /**
     * 工作区尚未获得执行该操作所需的信任级别。
     */
    WORKSPACE_TRUST_REQUIRED(-32018, ErrorCategory.PERMISSION, false),
    /**
     * 存储事实与期望 revision 或唯一性约束冲突。
     */
    STORAGE_CONFLICT(-32019, ErrorCategory.CONFLICT, false),
    /**
     * 运行时正在关闭，调用方可连接新代际后重试。
     */
    SHUTTING_DOWN(-32020, ErrorCategory.UNAVAILABLE, true),
    /**
     * 数据目录已被其他运行实例独占。
     */
    DATA_DIR_IN_USE(-32021, ErrorCategory.CONFLICT, false),
    /**
     * 当前存储或契约 Schema 与冻结基线不一致。
     */
    SCHEMA_MISMATCH(-32024, ErrorCategory.CONFLICT, false),
    /**
     * 请求引用的 Workspace 不存在或不可见。
     */
    WORKSPACE_NOT_FOUND(-32025, ErrorCategory.NOT_FOUND, false),
    /**
     * Workspace 路径违反物理目录约束。
     */
    WORKSPACE_CONFINEMENT(-32026, ErrorCategory.PERMISSION, false),
    /**
     * 可重试的通用 CAS 或资源状态冲突。
     */
    CONFLICT(-32028, ErrorCategory.CONFLICT, true),
    /**
     * 请求引用的 Thread 不存在。
     */
    THREAD_NOT_FOUND(-32029, ErrorCategory.NOT_FOUND, false),
    /**
     * Thread 仍有非终态 Turn，手动压缩必须等待其进入空闲态。
     */
    THREAD_BUSY(-32030, ErrorCategory.CONFLICT, true),
    /**
     * 请求引用的 Turn 不存在。
     */
    TURN_NOT_FOUND(-32032, ErrorCategory.NOT_FOUND, false),
    /**
     * Turn 不处于可恢复的挂起状态，或其持久执行游标已经终结。
     */
    TURN_NOT_RESUMABLE(-32065, ErrorCategory.CONFLICT, false),
    /**
     * 同一 Thread 存在更早的非终态 Turn，必须先按 admission 顺序处理。
     */
    TURN_RESUME_ORDER_CONFLICT(-32066, ErrorCategory.CONFLICT, true),
    /** 单 Turn 的回复中输入队列达到数量或 UTF-8 字节上限。 */
    TURN_INPUT_QUEUE_FULL(-32068, ErrorCategory.CAPACITY, true),
    /** 请求引用的待处理输入不存在、已消费或已删除。 */
    QUEUED_INPUT_NOT_FOUND(-32069, ErrorCategory.NOT_FOUND, false),
    /** Workspace 文件或目录引用在准入或消费时不再满足权威路径约束。 */
    WORKSPACE_REFERENCE_INVALID(-32070, ErrorCategory.VALIDATION, false),
    /** Skill 身份有效，但实时读取 SKILL.md 暂时失败。 */
    SKILL_LOAD_FAILED(-32071, ErrorCategory.UNAVAILABLE, true),
    /** 单条结构化消息或权威队列累计内容超过固定字节预算。 */
    CONTENT_TOO_LARGE(-32072, ErrorCategory.CAPACITY, false),
    /**
     * 当前领域状态不允许执行请求动作。
     */
    INVALID_STATE(-32034, ErrorCategory.CONFLICT, false),
    /**
     * 请求对应的操作已取消。
     */
    CANCELLED(-32035, ErrorCategory.CANCELLED, false),
    /**
     * Token、上下文、时间或工具预算已耗尽。
     */
    BUDGET_EXCEEDED(-32036, ErrorCategory.CAPACITY, false),
    /**
     * 请求引用的 Approval 不存在或不属于指定 Turn。
     */
    APPROVAL_NOT_FOUND(-32040, ErrorCategory.NOT_FOUND, false),
    /**
     * Approval 已超过允许响应的过期时间。
     */
    APPROVAL_EXPIRED(-32041, ErrorCategory.TIMEOUT, false),
    /**
     * Approval 已解决或已有响应者取得处理权。
     */
    APPROVAL_ALREADY_RESOLVED(-32042, ErrorCategory.CONFLICT, false),
    /**
     * 权限策略或用户审批拒绝执行 Tool。
     */
    TOOL_DENIED(-32043, ErrorCategory.PERMISSION, false),
    /**
     * Tool 已执行并返回确定性失败。
     */
    TOOL_FAILED(-32044, ErrorCategory.VALIDATION, false),
    /**
     * Tool 中断后无法确定外部副作用是否发生。
     */
    TOOL_OUTCOME_UNKNOWN(-32045, ErrorCategory.INTERNAL, false),
    /**
     * 外部进程超过允许执行时间。
     */
    PROCESS_TIMEOUT(-32046, ErrorCategory.TIMEOUT, false),
    /**
     * 外部进程输出超过有界采集上限。
     */
    PROCESS_OUTPUT_LIMIT(-32047, ErrorCategory.CAPACITY, false),
    /**
     * 摘要生成、修复与确定性降级均无法产生可提交的生产摘要。
     */
    SUMMARY_FAILURE(-32049, ErrorCategory.UNAVAILABLE, true),
    /**
     * Provider 响应违反模型协议或流式状态约束。
     */
    MODEL_PROTOCOL_ERROR(-32050, ErrorCategory.PROTOCOL, false),
    /**
     * 模型上下文超过 Provider 支持的限制。
     */
    CONTEXT_LIMIT(-32051, ErrorCategory.CAPACITY, false),
    /**
     * 所选模型不支持请求能力。
     */
    MODEL_UNSUPPORTED(-32052, ErrorCategory.VALIDATION, false),
    /**
     * 模型或 Provider 暂时不可用，调用方可稍后重试。
     */
    MODEL_UNAVAILABLE(-32053, ErrorCategory.UNAVAILABLE, true),
    /**
     * Skill 定义或内容不符合加载约束。
     */
    SKILL_INVALID(-32054, ErrorCategory.VALIDATION, false),
    /**
     * Skill 来源暂时不可访问，调用方可稍后重试。
     */
    SKILL_UNAVAILABLE(-32055, ErrorCategory.UNAVAILABLE, true),
    /**
     * MCP Server 或客户端不支持请求能力。
     */
    MCP_UNSUPPORTED(-32056, ErrorCategory.VALIDATION, false),
    /**
     * MCP Server 暂时不可用，调用方可稍后重试。
     */
    MCP_SERVER_UNAVAILABLE(-32057, ErrorCategory.UNAVAILABLE, true),
    /**
     * 请求引用的 MCP Tool 不存在。
     */
    MCP_TOOL_NOT_FOUND(-32059, ErrorCategory.NOT_FOUND, false),
    /**
     * MCP Tool 已执行并返回确定性失败。
     */
    MCP_TOOL_FAILED(-32060, ErrorCategory.VALIDATION, false),
    /** 请求引用的受管附件不存在或不属于当前可见范围。 */
    ATTACHMENT_NOT_FOUND(-32061, ErrorCategory.NOT_FOUND, false),
    /** 单文件、单轮数量或总字节数超过附件产品上限。 */
    ATTACHMENT_LIMIT_EXCEEDED(-32062, ErrorCategory.CAPACITY, false),
    /** 附件已绑定、已终结或重复 identity 与现有事实冲突。 */
    ATTACHMENT_CONFLICT(-32063, ErrorCategory.CONFLICT, false),
    /** staging 或受管内容暂时不可用、已变化或未通过完整性复核。 */
    ATTACHMENT_UNAVAILABLE(-32064, ErrorCategory.UNAVAILABLE, true),
    /** 请求引用的 Child Thread 不存在于当前任务树。 */
    TASK_NOT_FOUND(-32073, ErrorCategory.NOT_FOUND, false),
    /** 父子关系、任务类型或生命周期组合不合法。 */
    TASK_RELATION_INVALID(-32074, ErrorCategory.VALIDATION, false),
    /** 创建时声明的父 Thread revision 已过期。 */
    TASK_CONTEXT_REVISION_CONFLICT(-32075, ErrorCategory.CONFLICT, true),
    /** 调用方不在同一根任务树或试图扩大冻结权限上限。 */
    TASK_PERMISSION_DENIED(-32076, ErrorCategory.PERMISSION, false),
    /** 新 Child Thread 将超过最多四层的任务深度。 */
    TASK_DEPTH_LIMIT(-32077, ErrorCategory.CAPACITY, false),
    /** 当前根任务已经达到最多六十四个后代。 */
    TASK_TREE_LIMIT(-32078, ErrorCategory.CAPACITY, false),
    /** 目标 Mailbox 已达到持久条目或内容预算。 */
    TASK_MAILBOX_FULL(-32079, ErrorCategory.CAPACITY, true),
    /**
     * 服务端发生已脱敏的非预期内部错误。
     */
    INTERNAL_ERROR(-32080, ErrorCategory.INTERNAL, false),
    /**
     * Ja App Server sidecar 意外退出或失去响应。
     */
    SIDECAR_CRASHED(-32081, ErrorCategory.UNAVAILABLE, false),
    /**
     * 运行时未能在有界时间内完成关闭。
     */
    SHUTDOWN_TIMEOUT(-32082, ErrorCategory.TIMEOUT, false),
    /** 普通 Thread 删除遇到 Child Thread 时必须改用显式整树删除。 */
    TASK_TREE_DELETE_REQUIRED(-32083, ErrorCategory.CONFLICT, false),
    /** observe/seen 使用了不存在、过期或不属于当前连接的观察句柄。 */
    TASK_OBSERVATION_INVALID(-32084, ErrorCategory.NOT_FOUND, false),
    /** Workspace 写租约未能在 Turn 的冻结期限内取得。 */
    WORKSPACE_WRITE_LEASE_TIMEOUT(-32085, ErrorCategory.TIMEOUT, true),
    /** 请求引用的 Goal 不存在或不属于当前可见任务边界。 */
    GOAL_NOT_FOUND(-32086, ErrorCategory.NOT_FOUND, false),
    /** mutation 使用了过期 Goal revision，调用方必须重读权威投影。 */
    GOAL_REVISION_CONFLICT(-32087, ErrorCategory.CONFLICT, true),
    /** 当前 Goal 状态不允许所请求的生命周期转换。 */
    GOAL_INVALID_STATE(-32088, ErrorCategory.CONFLICT, false),
    /** 结构化 Plan、DAG 或验收定义违反冻结约束。 */
    PLAN_INVALID(-32089, ErrorCategory.VALIDATION, false),
    /** 批准引用的 Plan revision 或 canonical hash 已失效。 */
    PLAN_APPROVAL_STALE(-32090, ErrorCategory.CONFLICT, false),
    /** 当前 revision 缺少完成必要验收所需的可信证据。 */
    GOAL_EVIDENCE_INCOMPLETE(-32091, ErrorCategory.CONFLICT, false),
    /** 未知外部副作用或进程代际变化要求用户显式恢复。 */
    GOAL_RECOVERY_REQUIRED(-32092, ErrorCategory.CONFLICT, false),
    /** 输入请求已过期，旧回复不能推动 Goal。 */
    GOAL_INPUT_EXPIRED(-32093, ErrorCategory.TIMEOUT, false);

    private static final Map<Integer, JaErrorCatalog> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(JaErrorCatalog::code, Function.identity()));
    private final int code;
    private final ErrorCategory category;
    private final boolean retryable;

    /**
     * 保存冻结的数值码、分类与重试策略；枚举名本身就是 Wire errorCode。
     */
    JaErrorCatalog(int code, ErrorCategory category, boolean retryable) {
        this.code = code;
        this.category = category;
        this.retryable = retryable;
    }

    /**
     * 返回目录中唯一的 JSON-RPC 应用错误码。
     */
    public int code() {
        return code;
    }

    /**
     * 返回由目录穷举决定的稳定错误分类，禁止从 message 推断。
     */
    public ErrorCategory category() {
        return category;
    }

    /**
     * 返回冻结的调用方重试策略。
     */
    public boolean retryable() {
        return retryable;
    }

    /**
     * 仅当数值码属于首发错误目录时解析，拒绝未登记扩展。
     */
    public static JaErrorCatalog fromCode(int code) {
        JaErrorCatalog value = BY_CODE.get(code);
        if (value == null) throw new IllegalArgumentException("unknown JA-RPC error code");
        return value;
    }

    /**
     * 校验完整错误元组，使陈旧调用点在本地失败而非发出冲突契约。
     */
    public static void requireTuple(int code, String errorCode, String category, boolean retryable) {
        JaErrorCatalog value = fromCode(code);
        if (!value.name().equals(errorCode) || !value.category.wireName().equals(category)
            || value.retryable != retryable) {
            throw new IllegalArgumentException("JA-RPC error tuple conflicts with catalog");
        }
    }

    /**
     * 冻结跨语言共享的通用错误分类，具体机器错误仍由 errorCode 精确表达。
     */
    public enum ErrorCategory {
        /**
         * 协议帧、版本或上下游消息违反协议。
         */
        PROTOCOL("protocol"),
        /**
         * 输入、配置或能力不符合明确约束。
         */
        VALIDATION("validation"),
        /**
         * 当前状态、版本或资源事实与请求冲突。
         */
        CONFLICT("conflict"),
        /**
         * 请求引用的稳定身份不存在。
         */
        NOT_FOUND("not_found"),
        /**
         * 信任或权限策略拒绝操作。
         */
        PERMISSION("permission"),
        /**
         * 有界容量、上下文或输出预算已耗尽。
         */
        CAPACITY("capacity"),
        /**
         * 依赖、进程或服务暂时不可用。
         */
        UNAVAILABLE("unavailable"),
        /**
         * 请求或外部操作超过显式期限。
         */
        TIMEOUT("timeout"),
        /**
         * 请求已由调用方或生命周期取消。
         */
        CANCELLED("cancelled"),
        /**
         * 服务端无法公开更多细节的内部失败。
         */
        INTERNAL("internal");

        private final String wireName;

        /**
         * 固定 Wire 拼写，避免枚举重命名意外改变公共契约。
         */
        ErrorCategory(String wireName) {
            this.wireName = wireName;
        }

        /**
         * 返回 JSON error.data.category 使用的小写稳定值。
         */
        public String wireName() {
            return wireName;
        }
    }
}
