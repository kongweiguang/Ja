// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.task.port.out;

import java.util.Objects;

/** Task 持久化边界的稳定失败闭集；RPC 层不需要解析 SQLite 或异常文本。 */
public final class TaskRepositoryException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** 错误分类供应用层稳定映射 JA-RPC；异常文本只承担安全诊断，不参与业务分支。 */
    public enum Code {
        /** 目标 Task、Thread 或持久化事实不存在或已不可见。 */
        NOT_FOUND,
        /** Workspace、父子血缘或根任务关系不满足同域约束。 */
        RELATION_INVALID,
        /** 创建上下文使用的父 Thread revision 已过期，调用方必须刷新后重试。 */
        CONTEXT_REVISION_CONFLICT,
        /** 当前调用身份或冻结权限上限不允许所请求操作。 */
        PERMISSION_DENIED,
        /** Subagent 深度超过产品固定上限，不能继续扩展任务树。 */
        DEPTH_LIMIT,
        /** 根任务的后代总量超过固定预算，防止无界扇出。 */
        TREE_LIMIT,
        /** 目标 Mailbox 的条数或 UTF-8 字节预算已耗尽。 */
        MAILBOX_FULL,
        /** revision、幂等键或单行状态门发生并发竞争，调用方必须重读事实。 */
        CAS_CONFLICT,
        /** 删除存在后代的 Task 必须显式选择整棵树，避免隐式级联。 */
        TREE_DELETE_REQUIRED,
        /** 已读边界或观察序号不属于当前投影快照。 */
        OBSERVATION_INVALID,
        /** 工作区写租约在 Deadline 前未能取得，禁止绕过 FIFO 直接写入。 */
        WRITE_LEASE_TIMEOUT,
        /** 数据库状态、JSON 或恢复事实违反不可变约束，必须失败关闭。 */
        INVALID_STATE
    }

    private final Code code;

    /** 只保存安全错误摘要，底层 SQL 和文件路径不得跨出 adapter。 */
    public TaskRepositoryException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    /** 返回供应用层映射 JA-RPC 稳定错误码的分类。 */
    public Code code() {
        return code;
    }
}
