// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 私有算法单元测试；与生产实现分文件，避免测试 seam 扩大公共 API。

use super::{CLIENT_METHODS, EVENT_METHODS, catalog_entry};

/// 已读边界是 App Server 持久化方法，必须出现在握手与 decoder 共用的唯一闭集中。
#[test]
fn thread_seen_is_a_canonical_client_method() {
    assert!(CLIENT_METHODS.contains(&"thread/seen"));
    assert_eq!(
        CLIENT_METHODS
            .iter()
            .filter(|method| **method == "thread/seen")
            .count(),
        1
    );
}

/// Native 生命周期关闭必须只在冻结握手/decoder 闭集中出现一次，防止增加平行的 close alias。
#[test]
fn native_client_close_is_a_canonical_method() {
    assert_eq!(
        CLIENT_METHODS
            .iter()
            .filter(|method| **method == "runtime/client/close")
            .count(),
        1
    );
}

/// 隐藏续接与基于来源的重新询问属于不同的固定方法，必须在握手能力和解码器准入表中各出现一次。
#[test]
fn recovery_admission_methods_are_canonical() {
    for method in ["turn/continue", "turn/reask"] {
        assert_eq!(
            CLIENT_METHODS
                .iter()
                .filter(|candidate| **candidate == method)
                .count(),
            1,
            "recovery method must appear exactly once: {method}"
        );
    }
}

/// Thread 的 MCP 状态查询是解码器和握手闭包中的固定方法。
#[test]
fn thread_mcp_status_methods_are_canonical() {
    let method = "thread/mcp/read";
    assert_eq!(
        CLIENT_METHODS
            .iter()
            .filter(|candidate| **candidate == method)
            .count(),
        1,
        "thread MCP method must appear exactly once: {method}"
    );
}

/// Workspace read/update and metadata invalidation stay in the same closed protocol catalog used by handshake and decoder.
#[test]
fn workspace_metadata_methods_are_canonical() {
    for method in ["workspace/read", "workspace/update"] {
        assert_eq!(
            CLIENT_METHODS
                .iter()
                .filter(|candidate| **candidate == method)
                .count(),
            1,
            "workspace method must appear exactly once: {method}"
        );
    }
    assert_eq!(
        EVENT_METHODS
            .iter()
            .filter(|candidate| **candidate == "workspace/metadata-changed")
            .count(),
        1
    );
    assert_eq!(
        catalog_entry(-32_100, "WORKSPACE_BUSY", "conflict", true),
        Some(("WORKSPACE_BUSY", "workspace has active operations"))
    );
}

/// 锁定已删除错误和旧 Host CANCELLED 数值码，防止目录重新引入兼容别名。
#[test]
fn retired_errors_are_not_catalogued() {
    for (code, error_code, category) in [
        (-32_037, "CHANGE_CONFLICT", "conflict"),
        (-32_038, "CHANGE_NOT_FOUND", "not_found"),
        (-32_039, "CHANGE_CONTENT_UNAVAILABLE", "unavailable"),
        (-32_048, "TOKEN_COUNT_UNAVAILABLE", "unavailable"),
        (-32_106, "CANCELLED", "cancelled"),
    ] {
        assert!(catalog_entry(code, error_code, category, false).is_none());
    }
}

/// 锁定上下文压缩新增错误的完整 tuple，避免 decoder 与 Java catalog 独立漂移。
#[test]
fn context_compaction_errors_are_catalogued() {
    assert_eq!(
        catalog_entry(-32_030, "THREAD_BUSY", "conflict", true),
        Some(("THREAD_BUSY", "thread busy"))
    );
    assert_eq!(
        catalog_entry(-32_049, "SUMMARY_FAILURE", "unavailable", true),
        Some(("SUMMARY_FAILURE", "summary failure"))
    );
}

/// Resume 拒绝使用独立稳定 tuple；顺序冲突可在前序 Turn 解决后重试，其余拒绝不可重试。
#[test]
fn turn_resume_errors_are_catalogued() {
    assert_eq!(
        catalog_entry(-32_065, "TURN_NOT_RESUMABLE", "conflict", false),
        Some(("TURN_NOT_RESUMABLE", "turn is not resumable"))
    );
    assert_eq!(
        catalog_entry(-32_066, "TURN_RESUME_ORDER_CONFLICT", "conflict", true),
        Some((
            "TURN_RESUME_ORDER_CONFLICT",
            "an earlier turn must be resolved first"
        ))
    );
    assert_eq!(
        catalog_entry(-32_067, "UNASSIGNED", "conflict", false),
        None
    );
}

/// Reask 资格失败是确定性业务拒绝，不允许客户端把它当作可自动重试的网络或 CAS 错误。
#[test]
fn turn_reask_eligibility_error_is_catalogued() {
    assert_eq!(
        catalog_entry(-32_067, "TURN_NOT_REASKABLE", "validation", false),
        Some(("TURN_NOT_REASKABLE", "该问题当前不可重新回答。"))
    );
}

/// 队列容量可在消费后重试，已消费/不存在条目则要求调用方权威重读而非盲目重试。
#[test]
fn turn_input_queue_errors_are_catalogued() {
    assert_eq!(
        catalog_entry(-32_068, "TURN_INPUT_QUEUE_FULL", "capacity", true),
        Some(("TURN_INPUT_QUEUE_FULL", "turn input queue is full"))
    );
    assert_eq!(
        catalog_entry(-32_069, "QUEUED_INPUT_NOT_FOUND", "not_found", false),
        Some(("QUEUED_INPUT_NOT_FOUND", "queued input was not found"))
    );
}

/// Task Threads 的生命周期方法保持固定，普通消息使用独立 Thread 方法，防止 QueueOnly
/// message 被误路由为会启动 Turn 的 follow-up。
#[test]
fn task_thread_methods_are_canonical() {
    let expected = [
        "task/create",
        "task/list",
        "task/read",
        "task/observe",
        "task/unobserve",
        "task/seen",
        "task/followup",
        "task/cancel",
        "task/tree/delete",
        "task/close",
    ];
    for method in expected {
        assert_eq!(
            CLIENT_METHODS
                .iter()
                .filter(|candidate| **candidate == method)
                .count(),
            1,
            "task method must appear exactly once: {method}"
        );
    }
    assert_eq!(
        CLIENT_METHODS
            .iter()
            .filter(|candidate| **candidate == "thread/message/send")
            .count(),
        1
    );
}

/// Task 与写租约错误逐项锁定 category/retryable，确保桌面端只对安全的 CAS、容量和超时重试。
#[test]
fn task_thread_errors_are_catalogued() {
    for (code, error_code, category, retryable, message) in [
        (
            -32_073,
            "TASK_NOT_FOUND",
            "not_found",
            false,
            "task not found",
        ),
        (
            -32_074,
            "TASK_RELATION_INVALID",
            "validation",
            false,
            "task relation is invalid",
        ),
        (
            -32_075,
            "TASK_CONTEXT_REVISION_CONFLICT",
            "conflict",
            true,
            "task context revision conflicts",
        ),
        (
            -32_076,
            "TASK_PERMISSION_DENIED",
            "permission",
            false,
            "task permission denied",
        ),
        (
            -32_077,
            "TASK_DEPTH_LIMIT",
            "capacity",
            false,
            "task depth limit reached",
        ),
        (
            -32_078,
            "TASK_TREE_LIMIT",
            "capacity",
            false,
            "task tree limit reached",
        ),
        (
            -32_079,
            "TASK_MAILBOX_FULL",
            "capacity",
            true,
            "task mailbox is full",
        ),
        (
            -32_083,
            "TASK_TREE_DELETE_REQUIRED",
            "conflict",
            false,
            "task tree deletion is required",
        ),
        (
            -32_084,
            "TASK_OBSERVATION_INVALID",
            "not_found",
            false,
            "task observation is invalid",
        ),
        (
            -32_085,
            "WORKSPACE_WRITE_LEASE_TIMEOUT",
            "timeout",
            true,
            "workspace write lease timed out",
        ),
    ] {
        assert_eq!(
            catalog_entry(code, error_code, category, retryable),
            Some((error_code, message))
        );
    }
}
