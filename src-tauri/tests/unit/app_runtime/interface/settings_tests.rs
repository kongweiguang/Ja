// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;
use serde_json::json;

/// Native allowlist 必须拒绝不属于 Settings 投影的方法。
#[test]
fn rejects_arbitrary_method() {
    let input = SettingsQueryInput {
        method: "turn/start".to_owned(),
        params: json!({}),
    };
    assert!(input.validate().is_err());
}

/// MCP test 参数只接受 opaque revision identity，不接受路径或 command。
#[test]
fn validates_mcp_test_revisions() {
    let input = SettingsQueryInput {
        method: "mcp/test".to_owned(),
        params: json!({"mcpId": "mcp_docs"}),
    };
    assert!(input.validate().is_ok());
    let invalid = SettingsQueryInput {
        method: "mcp/test".to_owned(),
        params: json!({"mcpId": "C:\\workspace"}),
    };
    assert!(invalid.validate().is_err());
}

/// 模型验证参数必须同时携带保存后的 Provider/Model 身份，结果只允许脱敏模型名与耗时。
#[test]
fn validates_model_test_boundary() {
    let input = SettingsQueryInput {
        method: "model/test".to_owned(),
        params: json!({"providerId": "provider_openai", "modelId": "model_gpt"}),
    };
    assert!(input.validate().is_ok());
    let invalid = SettingsQueryInput {
        method: "model/test".to_owned(),
        params: json!({"providerId": "provider_openai", "modelId": "../secret"}),
    };
    assert!(invalid.validate().is_err());
    assert!(
        validate_result(
            SettingsQueryMethod::ModelTest,
            json!({"responseModel": "gpt-test", "latencyMs": 17})
        )
        .is_ok()
    );
    assert!(
        validate_result(
            SettingsQueryMethod::ModelTest,
            json!({"responseModel": "gpt-test", "latencyMs": 17, "answer": "OK"})
        )
        .is_err()
    );
}

/// 统一 items 数组在序列化前受限，旧列表键不能绕过大小边界。
#[test]
fn bounds_projection_rows() {
    let oversized = json!({"items": vec![json!({}); MAX_ROWS + 1], "nextCursor": null});
    assert!(validate_result(SettingsQueryMethod::SkillList, oversized).is_err());
    assert!(
        validate_result(
            SettingsQueryMethod::SkillList,
            json!({"skills": [], "nextCursor": null})
        )
        .is_err()
    );
}

/// 嵌套凭据字段必须在 WebView 序列化前被拒绝。
#[test]
fn rejects_private_result_fields_recursively() {
    let value = json!({"items": [], "nextCursor": null, "extension": {"apiKey": "must-not-cross"}});
    assert!(validate_result(SettingsQueryMethod::McpList, value).is_err());
}
