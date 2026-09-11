// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use super::*;
use serde::de::DeserializeOwned;

/// 原生代理必须保留关闭时的模型及思考档位，也不能把显式 null 跟随语义变成缺失字段。
#[test]
fn subagent_policy_survives_native_configuration_serialization() {
    for policy in [
        json!({"enabled": false, "provider_id": "provider_child", "model_id": "model_child", "reasoning_level": "high"}),
        json!({"enabled": true, "provider_id": null, "model_id": null, "reasoning_level": null}),
    ] {
        let input: ConfigReplaceInput = serde_json::from_value(json!({
            "scope": "user",
            "document": {"subagents": policy},
            "expectedVersion": "cfg_subagents"
        }))
        .expect("typed replace input");
        let wire = serde_json::to_value(input).expect("native wire");
        assert_eq!(wire["document"]["subagents"], policy);
        assert_eq!(wire["expectedVersion"], "cfg_subagents");
        assert!(wire.get("workspaceId").is_none());
    }
}

/// 统一验证所有 CAS DTO 的版本字段都不能缺失或为 null，避免凭据写入被序列化成无版本请求。
fn assert_expected_version_is_required<T>(mut input: Value)
where
    T: DeserializeOwned,
{
    input
        .as_object_mut()
        .expect("test input must be an object")
        .remove("expectedVersion");
    assert!(serde_json::from_value::<T>(input.clone()).is_err());

    input["expectedVersion"] = Value::Null;
    assert!(serde_json::from_value::<T>(input).is_err());
}

/// 在 Tauri 边界锁定 JA-RPC v1 五种写 DTO 的必填语义，避免把约束推迟到 Java 运行期才发现。
#[test]
fn cas_write_dtos_reject_missing_and_null_expected_version() {
    assert_expected_version_is_required::<ConfigPatchInput>(json!({
        "scope": "user",
        "patch": {"model": "gpt-test"},
        "expectedVersion": "cfg_missing"
    }));
    assert_expected_version_is_required::<ConfigReplaceInput>(json!({
        "scope": "user",
        "document": {},
        "expectedVersion": "cfg_missing"
    }));
    assert_expected_version_is_required::<ConfigResetInput>(json!({
        "scope": "user",
        "expectedVersion": "cfg_missing"
    }));
    assert_expected_version_is_required::<CredentialSetInput>(json!({
        "credentialId": "cred_demo",
        "secret": "fixture-secret",
        "expectedVersion": "cfg_missing"
    }));
    assert_expected_version_is_required::<CredentialDeleteInput>(json!({
        "credentialId": "cred_demo",
        "expectedVersion": "cfg_missing"
    }));
}

/// 用户级写入必须省略 workspaceId 而不是发送 null，因为严格 Java v1 handler 会把字段存在视为项目写入声明。
#[test]
fn user_scope_write_dtos_omit_workspace_identity() {
    let patch: ConfigPatchInput = serde_json::from_value(json!({
        "scope": "user", "patch": {}, "expectedVersion": "cfg_missing"
    }))
    .expect("patch input");
    let replace: ConfigReplaceInput = serde_json::from_value(json!({
        "scope": "user", "document": {}, "expectedVersion": "cfg_missing"
    }))
    .expect("replace input");
    let reset: ConfigResetInput = serde_json::from_value(json!({
        "scope": "user", "expectedVersion": "cfg_missing"
    }))
    .expect("reset input");

    for value in [
        serde_json::to_value(patch).expect("patch wire"),
        serde_json::to_value(replace).expect("replace wire"),
        serde_json::to_value(reset).expect("reset wire"),
    ] {
        assert!(
            !value
                .as_object()
                .expect("wire object")
                .contains_key("workspaceId")
        );
    }
}

/// 固定服务端签发的版本词汇，同时保持 Base64URL token 不透明，Rust 不附加服务商特定的摘要长度约束。
#[test]
fn config_version_requires_cfg_prefix_and_bounded_suffix() {
    for version in ["cfg_missing", "cfg_1", "cfg_Az09_-token"] {
        assert!(validate_version(version).is_ok(), "{version}");
    }
    for version in [
        "",
        "v1",
        "cfg_",
        "cfg_sha256:abc",
        "cfg_bad\nvalue",
        "cfg_bad=",
    ] {
        assert!(validate_version(version).is_err(), "{version:?}");
    }
    let oversized = format!("cfg_{}", "x".repeat(MAX_VERSION_BYTES));
    assert!(validate_version(&oversized).is_err());
}
