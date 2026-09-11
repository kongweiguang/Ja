// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 私有算法单元测试；与生产实现分文件，避免测试 seam 扩大公共 API。

use super::*;

/// 锁定桌面宿主真实使用的完整能力面，避免默认握手回退为空数组却仍在带外消费事件。
#[test]
fn default_initialize_advertises_the_consumed_runtime_surface() {
    let params = default_initialize_params(&Limits::default());
    let capabilities = params
        .get("capabilities")
        .expect("default initialize capabilities");
    assert_eq!(
        capabilities.get("events"),
        Some(&serde_json::json!([
            "runtime/status-changed",
            "turn/state-changed",
            "turn/input-queue-changed",
            "turn/input-consumed",
            "turn/messages_received",
            "assistant/model-step-committed",
            "assistant/text-delta",
            "assistant/reasoning-summary-delta",
            "tool/started",
            "tool/batch-committed",
            "approval/requested",
            "approval/resolved",
            "context/compaction-started",
            "context/compacted",
            "context/compaction-failed",
            "workspace/dirty",
            "turn/terminal",
            "thread/metadata-changed",
            "configuration/changed",
            "task/activity",
            "task/progress",
            "task/mailbox-changed",
            "goal/changed",
            "goal/activity",
            "interaction/changed",
            "plan/changed"
        ]))
    );
    assert!(capabilities.get("itemKinds").is_none());
    assert!(capabilities.get("mcp").is_none());
    assert!(
        capabilities
            .get("methods")
            .and_then(Value::as_array)
            .is_some_and(|methods| methods.iter().any(|method| method == "turn/start"))
    );
    assert!(
        capabilities
            .get("methods")
            .and_then(Value::as_array)
            .is_some_and(|methods| methods.iter().any(|method| method == "turn/resume"))
    );
    assert!(
        capabilities
            .get("methods")
            .and_then(Value::as_array)
            .is_some_and(|methods| {
                methods
                    .iter()
                    .any(|method| method == "workspace/open-general")
            })
    );
    assert!(
        capabilities
            .get("methods")
            .and_then(Value::as_array)
            .is_some_and(|methods| methods.iter().any(|method| method == "thread/compact"))
    );
    assert!(
        capabilities
            .get("methods")
            .and_then(Value::as_array)
            .is_some_and(|methods| {
                methods
                    .iter()
                    .filter(|method| *method == "workspace/path/search")
                    .count()
                    == 1
            })
    );
    assert!(capabilities.get("hostTools").is_none());
    assert_eq!(
        capabilities.get("accessModes"),
        Some(&serde_json::json!(["approval_required", "full_access"]))
    );
    assert_eq!(
        capabilities.get("features"),
        Some(&serde_json::json!(["task_threads_v1", "plan_goal_v1", "interaction_v1"]))
    );
    assert_eq!(
        capabilities.get("collaborationModes"),
        Some(&serde_json::json!(["default", "plan"]))
    );
    let methods = capabilities["methods"].as_array().expect("method catalog");
    assert_eq!(params["protocolMajor"], 1);
    assert_eq!(params["protocolMinor"], 0);
    for method in [
        "task/create",
        "task/list",
        "task/read",
        "task/observe",
        "task/unobserve",
        "task/seen",
        "thread/message/send",
        "task/followup",
        "task/cancel",
        "task/tree/delete",
        "task/close",
    ] {
        assert_eq!(
            methods
                .iter()
                .filter(|candidate| *candidate == method)
                .count(),
            1
        );
    }
}

/// Session 的全局硬上限只为五分钟手动压缩留出响应余量；普通调用仍传入各自短 deadline。
#[test]
fn default_session_limit_can_cover_manual_compaction() {
    assert_eq!(Limits::default().request_deadline_ms, 305_000);
}

/// 初始化必须双向锁定每 Turn 队列条目与 UTF-8 总字节预算，防止一端误以为队列无界。
#[test]
fn default_initialize_advertises_turn_input_queue_limits() {
    let params = default_initialize_params(&Limits::default());
    assert_eq!(params["limits"]["maxTurnQueuedInputs"], 8);
    assert_eq!(params["limits"]["maxTurnQueuedInputBytes"], 524_288);
}

/// initialize 刻意不包含业务配置；首次启动 home 为空时 v1 握手仍合法，缺少 profile
/// 或 credential 只在 Java admission Turn 时报告。
#[test]
fn initialize_has_no_business_configuration() {
    let params = default_initialize_params(&Limits::default());
    assert!(params.get("profiles").is_none());
    validate_initialize_params(&params, &Limits::default()).expect("v1 initialize");

    // 首版没有升级窗口，不能将旧 2.1 offer 当作可协商的高版本。
    let mut retired = params;
    retired["protocolMajor"] = serde_json::json!(2);
    retired["protocolMinor"] = serde_json::json!(1);
    assert!(validate_initialize_params(&retired, &Limits::default()).is_err());
}

/// 锁定极简 capability 闭集；任何旧 Host Tool 或旧权限模式都必须在握手前失败。
#[test]
fn removed_host_tools_and_access_modes_fail_closed() {
    let valid = default_initialize_params(&Limits::default())["capabilities"].clone();
    validate_capabilities(Some(&valid)).expect("minimal capability closure");

    let mut host_tools = valid.clone();
    host_tools["hostTools"] = serde_json::json!({
        "version": "v1", "methods": ["write_file", "apply_patch"]
    });
    let mut workspace = valid.clone();
    workspace["accessModes"] = serde_json::json!(["workspace"]);
    let mut read_only = valid.clone();
    read_only["accessModes"] = serde_json::json!(["read_only"]);
    let mut missing_feature = valid.clone();
    missing_feature["features"] = serde_json::json!([]);
    let mut unknown_feature = valid.clone();
    unknown_feature["features"] = serde_json::json!(["task_threads_v2"]);
    for malformed in [
        host_tools,
        workspace,
        read_only,
        missing_feature,
        unknown_feature,
    ] {
        assert!(validate_capabilities(Some(&malformed)).is_err());
    }
}

/// 锁定 RFC3339 的结构/日期边界，防止 ready 校验退化为 starts_with 检查。
#[test]
fn timestamp_validation_is_strict_and_bounded() {
    assert!(valid_timestamp("2026-02-28T23:59:59Z"));
    assert!(valid_timestamp("2024-02-29T00:00:00.123456789+08:00"));
    assert!(valid_timestamp("2026-08-16T12:00:00-00:00"));
    assert!(!valid_timestamp("2026-02-29T23:59:59Z"));
    assert!(!valid_timestamp("2026-13-01T00:00:00Z"));
    assert!(!valid_timestamp("2026-01-01T24:00:00Z"));
    assert!(!valid_timestamp("2026-01-01T00:00:00"));
    assert!(!valid_timestamp("2026-01-01T00:00:00+8:00"));
}

/// 证明每个生成 challenge 都是固定长度的小写十六进制且不会按序号复用。
#[test]
fn ready_token_generation_is_csprng_shaped_and_unique() {
    let first = generate_ready_token().expect("platform CSPRNG available");
    let second = generate_ready_token().expect("platform CSPRNG available");
    assert_eq!(first.len(), 32);
    assert!(
        first
            .bytes()
            .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
    );
    assert_ne!(first, second);
}
