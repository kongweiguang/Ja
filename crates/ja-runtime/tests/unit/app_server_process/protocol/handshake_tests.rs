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
            "assistant/model-step-committed",
            "assistant/text-delta",
            "assistant/reasoning-summary-delta",
            "tool/batch-committed",
            "approval/requested",
            "approval/resolved",
            "context/compaction-started",
            "context/compacted",
            "context/compaction-failed",
            "workspace/dirty",
            "turn/terminal",
            "thread/metadata-changed",
            "configuration/changed"
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
    assert!(capabilities.get("hostTools").is_none());
    assert_eq!(
        capabilities.get("accessModes"),
        Some(&serde_json::json!(["approval_required", "full_access"]))
    );
}

/// Session 的全局硬上限只为五分钟手动压缩留出响应余量；普通调用仍传入各自短 deadline。
#[test]
fn default_session_limit_can_cover_manual_compaction() {
    assert_eq!(Limits::default().request_deadline_ms, 305_000);
}

/// initialize 刻意不包含业务配置；首次启动 home 为空时 v2 握手仍合法，缺少 profile
/// 或 credential 只在 Java admission Turn 时报告。
#[test]
fn initialize_has_no_business_configuration() {
    let params = default_initialize_params(&Limits::default());
    assert!(params.get("profiles").is_none());
    validate_initialize_params(&params, &Limits::default()).expect("v2 initialize");
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
    for malformed in [host_tools, workspace, read_only] {
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
