// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

#[path = "../../src/controller/model_settings_recovery.rs"]
mod recovery;

use ja_cli::ui::{ModelSettingsCredential, ModelSettingsOutcome, ModelSettingsRequest};
use recovery::{ModelSettingsSession, SettingsRequestError};
use serde_json::{Value, json};

/// 用脱敏的真实读取形状验证两个 CAS，测试不接触用户目录或外部 Provider。
fn configuration(configured: bool) -> Value {
    let document = json!({"providers":[{
        "provider_id":"provider_a", "credential_id":"cred_a", "name":"测试供应商",
        "models":[{"model_id":"model_a","model":"upstream-a"}]
    }],"default_provider_id":"provider_a","default_model_id":"model_a","default_reasoning_level":null});
    json!({"user":{"document":document},"effective":document,
        "cas":{"userVersion":"user-2","credentialVersion":"auth-1"},
        "credentials":{"cred_a":{"configured":configured}}})
}

/// 每个提交持有读取时版本与同一稳定 ID，部分失败不能通过新增实体规避 CAS。
fn save(patch: Value, secret: Option<&str>) -> ModelSettingsRequest {
    ModelSettingsRequest::Save {
        operation_id: "save-1".into(),
        expected_version: "user-1".into(),
        expected_credential_version: "auth-1".into(),
        patch,
        credential: secret.map(|secret| ModelSettingsCredential {
            provider_id: "provider_a".into(),
            credential_id: "cred_a".into(),
            secret: secret.into(),
        }),
    }
}

/// RPC 明确失败与传输未知保持不同分类，避免把断线当作写入未发生。
fn error(code: Option<i64>) -> SettingsRequestError {
    SettingsRequestError {
        rpc_code: code,
        message: "fixture failure".into(),
    }
}

/// 配置未通过版本检查时，密钥调用必须为零；回读仅用于让用户审阅新基线。
#[test]
fn configuration_conflict_does_not_write_credentials() {
    let mut session = ModelSettingsSession::default();
    let mut calls = Vec::new();
    let result = session.execute(
        save(json!({"default_model_id":"model_a"}), Some("fixture-key")),
        |method, _| {
            calls.push(method.to_owned());
            match method {
                "configuration/patch" => Err(error(Some(-32011))),
                "configuration/read" => Ok(configuration(false)),
                _ => panic!("unexpected {method}"),
            }
        },
    );
    assert!(matches!(
        result.outcome,
        ModelSettingsOutcome::Conflict { .. }
    ));
    assert_eq!(calls, ["configuration/patch", "configuration/read"]);
}

/// 密钥明确失败保留已保存配置，用户下一次仅补密钥时不会再提交配置或换 ID。
#[test]
fn partial_success_retries_only_missing_credential() {
    let mut session = ModelSettingsSession::default();
    let result = session.execute(
        save(json!({"default_model_id":"model_a"}), Some("fixture-key")),
        |method, _| match method {
            "configuration/patch" => Ok(json!({"accepted":true,"scope":"user","version":"user-2"})),
            "configuration/read" => Ok(configuration(false)),
            "credential/set" => Err(error(Some(-32011))),
            _ => panic!("unexpected {method}"),
        },
    );
    assert!(matches!(
        result.outcome,
        ModelSettingsOutcome::NeedsCredential { .. }
    ));
    let mut calls = Vec::new();
    let mut credential_written = false;
    let result = session.execute(save(json!({}),Some("fixture-key")),|method,params| {
        calls.push(method.to_owned());
        match method {
            "configuration/read"=>{let mut current=configuration(credential_written);if credential_written {current["cas"]["credentialVersion"]="auth-2".into();} Ok(current)},
            "credential/set"=>{assert_eq!(params["credentialId"],"cred_a");assert_eq!(params["expectedVersion"],"auth-1");credential_written=true;Ok(json!({"accepted":true,"credentialId":"cred_a","configured":true,"version":"auth-2"}))},
            _=>panic!("unexpected {method}")
        }
    });
    assert!(matches!(result.outcome, ModelSettingsOutcome::Saved { .. }));
    assert_eq!(
        calls,
        ["configuration/read", "credential/set", "configuration/read"]
    );
}

/// 写入回执丢失后，普通刷新及重复保存都不得自动重放，只有主动核实能解除锁。
#[test]
fn lost_configuration_ack_is_verified_without_replay() {
    let mut session = ModelSettingsSession::default();
    let result = session.execute(
        save(json!({"default_model_id":"model_a"}), None),
        |method, _| {
            assert_eq!(method, "configuration/patch");
            Err(error(None))
        },
    );
    assert!(matches!(
        result.outcome,
        ModelSettingsOutcome::Uncertain { .. }
    ));
    for request in [
        ModelSettingsRequest::Read {
            operation_id: "refresh".into(),
        },
        save(json!({"default_model_id":"model_a"}), None),
    ] {
        assert!(matches!(
            session
                .execute(request, |_, _| panic!("must not replay"))
                .outcome,
            ModelSettingsOutcome::Uncertain { .. }
        ));
    }
    let result = session.execute(
        ModelSettingsRequest::Verify {
            operation_id: "verify".into(),
        },
        |method, _| {
            assert_eq!(method, "configuration/read");
            Ok(configuration(false))
        },
    );
    assert!(matches!(result.outcome, ModelSettingsOutcome::Saved { .. }));
}

/// configured=true 只表示存在密钥，不能证明未知写入使用了本次输入；比较在内存中完成。
#[test]
fn credential_ack_loss_requires_secret_content_verification() {
    for matches in [false, true] {
        let mut session = ModelSettingsSession::default();
        let result = session.execute(
            save(json!({}), Some("fixture-key")),
            |method, _| match method {
                "configuration/read" => Ok(configuration(true)),
                "credential/set" => Err(error(None)),
                _ => panic!("unexpected {method}"),
            },
        );
        assert!(matches!(
            result.outcome,
            ModelSettingsOutcome::Uncertain { .. }
        ));
        let mut calls = Vec::new();
        let result = session.execute(
            ModelSettingsRequest::Verify {
                operation_id: "verify".into(),
            },
            |method, params| {
                calls.push(method.to_owned());
                match method {
                    "configuration/read" => Ok(configuration(true)),
                    "credential/reveal-provider" => {
                        assert_eq!(params, json!({"providerId":"provider_a"}));
                        Ok(json!({"secret":if matches {"fixture-key"} else {"other-key"}}))
                    }
                    _ => panic!("unexpected {method}"),
                }
            },
        );
        if matches {
            assert!(matches!(result.outcome, ModelSettingsOutcome::Saved { .. }));
        } else {
            assert!(matches!(
                result.outcome,
                ModelSettingsOutcome::NeedsCredential { .. }
            ));
        }
        assert_eq!(
            calls,
            [
                "configuration/read",
                "credential/reveal-provider",
                "configuration/read"
            ]
        );
    }
}

/// 换绑引用后禁止 reveal 旧意图的密钥，避免用户主动核实误读另一个 Provider 凭据。
#[test]
fn changed_credential_reference_prevents_reveal() {
    let mut session = ModelSettingsSession::default();
    session.execute(
        save(json!({}), Some("fixture-key")),
        |method, _| match method {
            "configuration/read" => Ok(configuration(true)),
            "credential/set" => Err(error(None)),
            _ => panic!("unexpected"),
        },
    );
    let mut current = configuration(true);
    current["user"]["document"]["providers"][0]["credential_id"] = "cred_other".into();
    let result = session.execute(
        ModelSettingsRequest::Verify {
            operation_id: "verify".into(),
        },
        |method, _| {
            assert_eq!(method, "configuration/read");
            Ok(current.clone())
        },
    );
    assert!(matches!(
        result.outcome,
        ModelSettingsOutcome::Conflict { .. }
    ));
}

/// ACK 后的回读若发现别的凭据版本，不将 configured 当作当前密钥内容的保证。
#[test]
fn concurrent_credential_write_after_ack_requires_verification() {
    let mut session = ModelSettingsSession::default();
    let result = session.execute(save(json!({}), Some("fixture-key")), |method, _| match method {
        "configuration/read" => Ok(configuration(true)),
        "credential/set" => Ok(json!({"accepted":true,"credentialId":"cred_a","configured":true,"version":"auth-2"})),
        _ => panic!("unexpected {method}"),
    });
    assert!(matches!(
        result.outcome,
        ModelSettingsOutcome::Uncertain { .. }
    ));
}

/// 即使错误适配器意外包含本次输入，恢复结果也不能把密钥带回 renderer。
#[test]
fn credential_error_is_redacted_before_ui_delivery() {
    let mut session = ModelSettingsSession::default();
    let result = session.execute(
        save(json!({}), Some("fixture-key")),
        |method, _| match method {
            "configuration/read" => Ok(configuration(false)),
            "credential/set" => Err(SettingsRequestError {
                rpc_code: Some(-32013),
                message: "rejected fixture-key".into(),
            }),
            _ => panic!("unexpected {method}"),
        },
    );
    let ModelSettingsOutcome::NeedsCredential { message, .. } = result.outcome else {
        panic!("expected partial save");
    };
    assert!(!message.contains("fixture-key"));
    assert!(message.contains("[已隐藏]"));
}

/// 验证仅比较触及字段及列出的顺序，不把其它实体或未知字段当作覆盖意图。
#[test]
fn patch_readback_matches_identity_operations_and_nullable_fields() {
    let config = configuration(false);
    assert!(recovery::patch_matches(
        &config,
        &json!({
            "providers":{"provider_a":{"models":{"model_a":{"model":"upstream-a"},"model_deleted":null},"model_order":["model_a"]}},
            "provider_order":["provider_a"],"default_reasoning_level":{"__ja_null":true}
        })
    ));
    assert!(!recovery::patch_matches(
        &config,
        &json!({"providers":{"provider_a":null}})
    ));
    assert!(!recovery::patch_matches(
        &config,
        &json!({"providers":{"provider_a":{"models":{"model_a":{"model":"wrong"}}}}})
    ));
}

/// 修复回执未知时只读取候选文档，备份和 replace 都不能因 F5 被再次执行。
#[test]
fn repair_ack_loss_is_recovered_by_read_only() {
    let mut session = ModelSettingsSession::default();
    let config = configuration(false);
    let result = session.execute(
        ModelSettingsRequest::Repair {
            operation_id: "repair".into(),
            expected_version: "user-1".into(),
            document: config["user"]["document"].clone(),
        },
        |method, _| {
            assert_eq!(method, "configuration/replace");
            Err(error(None))
        },
    );
    assert!(matches!(
        result.outcome,
        ModelSettingsOutcome::Uncertain { .. }
    ));
    let result = session.execute(
        ModelSettingsRequest::Verify {
            operation_id: "verify".into(),
        },
        |method, _| {
            assert_eq!(method, "configuration/read");
            Ok(config.clone())
        },
    );
    assert!(matches!(result.outcome, ModelSettingsOutcome::Saved { .. }));
}

/// 损坏层的有效默认值不能证明替换已落盘；未知修复结果只能回读，不能重放 replace。
#[test]
fn repair_ack_loss_cannot_be_confirmed_by_effective_fallback() {
    for status in ["corrupt", "partial", "io_error", "missing"] {
        let config = configuration(false);
        let document = config["user"]["document"].clone();
        let mut unavailable = config.clone();
        unavailable["user"]["status"] = json!(status);
        unavailable["user"]["document"] = if status == "partial" {
            document.clone()
        } else {
            Value::Null
        };
        let mut session = ModelSettingsSession::default();
        let result = session.execute(
            ModelSettingsRequest::Repair {
                operation_id: "repair".into(),
                expected_version: "user-1".into(),
                document,
            },
            |method, _| {
                assert_eq!(method, "configuration/replace");
                Err(error(None))
            },
        );
        assert!(matches!(
            result.outcome,
            ModelSettingsOutcome::Uncertain { .. }
        ));
        let mut calls = Vec::new();
        let result = session.execute(
            ModelSettingsRequest::Verify {
                operation_id: "verify".into(),
            },
            |method, _| {
                calls.push(method.to_owned());
                Ok(unavailable.clone())
            },
        );
        assert!(
            matches!(result.outcome, ModelSettingsOutcome::Conflict { .. }),
            "{status}"
        );
        assert_eq!(calls, ["configuration/read"]);
    }
}

/// 显式可空值必须存在于用户文档；缺字段和有效层的空值都不能冒充本次保存。
#[test]
fn nullable_readback_requires_explicit_user_value() {
    let mut config = configuration(false);
    let patch = json!({"default_reasoning_level": {"__ja_null": true}});
    assert!(recovery::patch_matches(&config, &patch));
    config["user"]["document"]
        .as_object_mut()
        .unwrap()
        .remove("default_reasoning_level");
    assert!(!recovery::patch_matches(&config, &patch));
    config["user"]["document"] = Value::Null;
    assert!(!recovery::patch_matches(&config, &patch));
}

/// 写后回执与通知失败可返回 RPC 错误；三种写入均须保留意图，只读核实，不重放副作用。
#[test]
fn post_commit_rpc_errors_require_read_only_verification() {
    for rpc_code in [-32010, -32007, -32018, -32080, -32012] {
        for write_method in [
            "configuration/patch",
            "configuration/replace",
            "credential/set",
        ] {
            let mut committed = configuration(true);
            committed["user"]["document"]["providers"][0]["name"] = json!("已提交名称");
            committed["cas"]["credentialVersion"] = json!("auth-2");
            let request = match write_method {
                "configuration/patch" => save(
                    json!({"providers": {"provider_a": {"name": "已提交名称"}}}),
                    None,
                ),
                "configuration/replace" => ModelSettingsRequest::Repair {
                    operation_id: "repair".into(),
                    expected_version: "user-1".into(),
                    document: committed["user"]["document"].clone(),
                },
                _ => save(json!({}), Some("fixture-key")),
            };
            let mut session = ModelSettingsSession::default();
            let mut writes = 0;
            let result = session.execute(request, |method, _| {
                if method == "configuration/read" {
                    return Ok(configuration(false));
                }
                assert_eq!(method, write_method);
                writes += 1;
                Err(error(Some(rpc_code)))
            });
            assert!(
                matches!(result.outcome, ModelSettingsOutcome::Uncertain { .. }),
                "{write_method} code={rpc_code}"
            );
            assert_eq!(writes, 1);
            let result = session.execute(save(json!({}), None), |_, _| panic!("must not replay"));
            assert!(matches!(
                result.outcome,
                ModelSettingsOutcome::Uncertain { .. }
            ));

            let mut verification_calls = Vec::new();
            let result = session.execute(
                ModelSettingsRequest::Verify {
                    operation_id: "verify".into(),
                },
                |method, _| {
                    verification_calls.push(method.to_owned());
                    match method {
                        "configuration/read" => Ok(committed.clone()),
                        "credential/reveal-provider" => Ok(json!({"secret": "fixture-key"})),
                        _ => panic!("verification repeated a write: {method}"),
                    }
                },
            );
            assert!(
                matches!(result.outcome, ModelSettingsOutcome::Saved { .. }),
                "{write_method} code={rpc_code}"
            );
            assert_eq!(
                verification_calls,
                if write_method == "credential/set" {
                    vec![
                        "configuration/read",
                        "credential/reveal-provider",
                        "configuration/read",
                    ]
                } else {
                    vec!["configuration/read"]
                }
            );
        }
    }
}
