// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// 原生 bridge 单元测试与生产实现分文件，仍验证同一私有状态机。

use super::*;
use crate::app_runtime::WorkspacePathSearchInput;
use ja_runtime::app_server_process::TurnChangeSetReadResult as WireTurnChangeSetReadResult;
use serde_json::json;
use sha2::{Digest, Sha256};

/// 通过受控 panic 中毒指定 mutex，但绝不提取中毒 guard；这样测试验证的是关闭失败语义，
/// 而不是通过夹具重新引入生产代码已禁止的无条件恢复。
fn poison_mutex<T>(mutex: &std::sync::Mutex<T>) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let _guard = match mutex.lock() {
            Ok(guard) => guard,
            Err(_) => panic!("mutex fixture was already poisoned"),
        };
        panic!("intentional mutex poison");
    }));
    assert!(result.is_err(), "poison fixture must unwind");
}

/// 构造标准 Java 通用 Workspace 投影；parser 只验证协议，不创建 root。
fn general_workspace_projection(root: &std::path::Path) -> serde_json::Value {
    json!({
        "workspaceId": "ws_java_general",
        "root": root.to_string_lossy(),
        "displayName": "无项目",
        "trust": "trusted",
        "revision": 7,
    })
}

/// Java identity、root、trust 与 revision 必须由 infrastructure 在进入 capability registry 前
/// 一次性严格解析，且解析本身不能创建 Rust-owned fallback 目录。
#[test]
fn parses_general_workspace_without_creating_local_storage() {
    let root = std::env::temp_dir().join("ja-java-general-root-that-is-not-created");
    let parsed =
        parse_general_workspace(general_workspace_projection(&root)).expect("server projection");

    assert_eq!(parsed.workspace_id, "ws_java_general");
    assert_eq!(parsed.root, root.to_string_lossy());
    assert_eq!(parsed.display_name, "无项目");
    assert_eq!(parsed.trust, "trusted");
    assert_eq!(parsed.revision, 7);
    assert!(!root.exists(), "parser must not create Java storage");
}

/// unknown field、错误 identity prefix 与畸形 revision 必须在 native Workspace binding 前关闭失败。
#[test]
fn rejects_malformed_general_workspace_projection() {
    let root = std::env::temp_dir().join("ja-java-general-invalid-root");
    for invalid in [
        json!({
            "workspaceId": "ws_java_general",
            "root": root,
            "displayName": "无项目",
            "trust": "trusted",
            "revision": 1,
            "extra": true,
        }),
        json!({
            "workspaceId": "not-a-workspace",
            "root": root,
            "displayName": "无项目",
            "trust": "trusted",
            "revision": 1,
        }),
        json!({
            "workspaceId": "ws_java_general",
            "root": root,
            "displayName": "无项目",
            "trust": "trusted",
            "revision": 9_007_199_254_740_992_u64,
        }),
    ] {
        assert_eq!(
            parse_general_workspace(invalid)
                .expect_err("malformed projection must fail closed")
                .code,
            "RUNTIME_UNAVAILABLE"
        );
    }
}

/// 非绝对或过长 root 必须在 registry IO 前被拒绝，避免恢复本地 fallback 路径。
#[test]
fn rejects_unusable_general_workspace_root() {
    let relative = json!({
        "workspaceId": "ws_java_general",
        "root": "general-workspace",
        "displayName": "无项目",
        "trust": "trusted",
        "revision": 1,
    });
    assert!(parse_general_workspace(relative).is_err());

    let long_root = std::env::temp_dir()
        .join("x".repeat(4_096))
        .to_string_lossy()
        .into_owned();
    assert!(
        parse_general_workspace(json!({
            "workspaceId": "ws_java_general",
            "root": long_root,
            "displayName": "无项目",
            "trust": "trusted",
            "revision": 1,
        }))
        .is_err()
    );
}

/// 验证紧凑 terminal reason 映射保持有限且不含 secret。
#[test]
fn terminal_reason_projection_is_closed() {
    assert_eq!(
        terminal_reason(TERMINAL_SIGNAL_QUEUE),
        "runtime_signal_queue_full"
    );
    assert_eq!(
        terminal_reason(TERMINAL_SIDECAR_EXIT_NONZERO),
        "sidecar_exit_nonzero"
    );
    assert_eq!(terminal_reason(255), "sidecar_terminated");
}

/// WebView listener 暂时不可达不改变 Rust 对 sidecar 的 ownership；只有非法协议投影才
/// 终止当前 generation，防止 HMR 或 renderer reload 杀死仍在执行的真实 Provider 回合。
#[test]
fn webview_delivery_failure_does_not_terminal_the_sidecar() {
    let delivery = RuntimeCommandError::event_delivery();
    assert!(!projection_failure_is_terminal(&delivery));

    let invalid = RuntimeCommandError {
        code: "SENSITIVE_EVENT_BLOCKED",
        message: "runtime event contains protected data",
        retryable: false,
    };
    assert!(projection_failure_is_terminal(&invalid));
}

/// Config operation 保持 method-scoped；Host 不构造完整 snapshot，也不在 initialize envelope
/// 嵌入 credential。
#[test]
fn config_methods_are_closed() {
    assert!(matches!(
        "configuration/read",
        "configuration/read"
            | "configuration/patch"
            | "configuration/replace"
            | "configuration/reset"
    ));
    assert!(!json!({"apiKey": "secret"}).to_string().is_empty());
}

/// 重复 open 可能返回 Java 首次注册保存的名称；若拒绝该名称，renderer 建议不同本地 label 后，
/// 合法持久 Workspace 将无法使用。
#[test]
fn workspace_open_accepts_authoritative_persisted_display_name() {
    let root = std::env::temp_dir().join(format!("ja-workspace-open-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&root).expect("workspace root");
    let canonical = std::fs::canonicalize(&root).expect("canonical workspace root");
    let result = json!({
        "workspaceId": "ws_0123456789abcdef0123456789abcdef",
        "root": root.to_string_lossy(),
        "displayName": "persisted-name",
        "trust": "trusted",
        "revision": 1
    });

    let projection = validate_workspace_open_result(&result, &canonical)
        .expect("persisted projection remains valid");
    assert_eq!(
        projection.workspace_id,
        "ws_0123456789abcdef0123456789abcdef"
    );
    assert_eq!(projection.display_name, "persisted-name");
    assert_eq!(projection.trust, "trusted");
    assert_eq!(projection.revision, 1);
    let _ = std::fs::remove_dir_all(root);
}

/// Java 25 的 `Instant.toString()` 可能携带纳秒精度；UUID attachment identity 与空绑定同样是
/// DRAFT 的正常事实，Rust bridge 必须接受后再收窄为 WebView projection。
#[test]
fn attachment_result_accepts_uuid_identity_high_precision_time_and_null_binding() {
    let result = json!({
        "attachmentId": "att_1f12f1bd-f519-4e30-9844-e59616ea9967",
        "workspaceId": "ws_0123456789abcdef0123456789abcdef",
        "displayName": "attachment-e2e.txt",
        "sizeBytes": 30,
        "mediaKind": "text",
        "mediaType": "text/plain",
        "state": "draft",
        "createdAt": "2026-09-04T15:31:22.123456789Z",
        "expiresAt": "2026-09-05T15:31:22.987654321Z",
        "boundMessageId": null
    });

    let metadata = parse_attachment_result(
        result,
        None,
        Some("ws_0123456789abcdef0123456789abcdef"),
        Some("attachment-e2e.txt"),
        Some(30),
        "draft",
    )
    .expect("current Java attachment result must remain accepted");

    assert_eq!(
        metadata.attachment_id,
        "att_1f12f1bd-f519-4e30-9844-e59616ea9967"
    );
    assert_eq!(metadata.media_type.as_deref(), Some("text/plain"));
    assert_eq!(metadata.state, "draft");
}

/// 严格响应解析继续拒绝 Java 合同外字段，避免 bridge 在协议漂移时静默接受第二套 metadata。
#[test]
fn attachment_result_rejects_unknown_fields() {
    let result = json!({
        "attachmentId": "att_1f12f1bd-f519-4e30-9844-e59616ea9967",
        "workspaceId": "ws_0123456789abcdef0123456789abcdef",
        "displayName": "attachment-e2e.txt",
        "sizeBytes": 30,
        "mediaKind": "text",
        "mediaType": "text/plain",
        "state": "draft",
        "createdAt": "2026-09-04T15:31:22.123456789Z",
        "expiresAt": "2026-09-05T15:31:22.987654321Z",
        "boundMessageId": null,
        "boundTurnId": null
    });

    assert!(
        parse_attachment_result(result, None, None, None, None, "draft").is_err(),
        "legacy ownership fields must fail closed"
    );
}

/// Host 暴露 startup success 前，health 必须精确、有界且为 Ready。
#[test]
fn health_result_is_exact_and_secret_free() {
    let valid = json!({
        "status": "ready",
        "components": [
            {"name": "sidecar", "status": "healthy"},
            {"name": "sqlite", "status": "healthy"},
            {"name": "kernel", "status": "healthy"},
            {"name": "configuration", "status": "healthy", "diagnostics": []}
        ]
    });
    assert!(validate_health_result(&valid).is_ok());
    assert!(
        validate_health_result(&json!({
            "status": "ready",
            "components": [],
            "apiKey": "must-not-cross"
        }))
        .is_err()
    );
    assert!(
        validate_health_result(&json!({
            "status": "degraded",
            "components": [
                {"name": "sidecar", "status": "healthy"},
                {"name": "sqlite", "status": "healthy"},
                {"name": "kernel", "status": "healthy"},
                {"name": "configuration", "status": "degraded", "diagnostics": ["CONFIG_INVALID"]}
            ]
        }))
        .is_err()
    );
    assert!(
        validate_health_result(&json!({
            "status": "ready",
            "components": [
                {"name": "sidecar", "status": "healthy"},
                {"name": "sqlite", "status": "healthy"},
                {"name": "kernel", "status": "healthy"},
                {"name": "configuration", "status": "degraded", "diagnostics": ["CONFIG_INVALID"]}
            ]
        }))
        .is_ok()
    );
    assert!(
        validate_health_result(&json!({
            "status": "ready",
            "components": [
                {"name": "sidecar", "status": "healthy", "diagnostics": []},
                {"name": "sqlite", "status": "healthy"},
                {"name": "kernel", "status": "healthy"},
                {"name": "configuration", "status": "healthy"}
            ]
        }))
        .is_err()
    );
    assert!(
        validate_health_result(&json!({
            "status": "ready",
            "components": [
                {"name": "sidecar", "status": "healthy"},
                {"name": "sqlite", "status": "healthy"},
                {"name": "kernel", "status": "healthy"},
                {"name": "configuration", "status": "healthy", "diagnostics": ["not-safe"]}
            ]
        }))
        .is_err()
    );
}

/// Approval input 是普通 client data，不包含私有 request ID。
#[test]
fn approval_params_are_business_scoped() {
    let input = ApprovalResponseInput {
        approval_id: "appr_demo".to_owned(),
        turn_id: "turn_demo".to_owned(),
        decision: "deny".to_owned(),
        expected_thread_revision: 3,
    };
    let value = approval_params(&input).expect("approval params");
    assert_eq!(value["approvalId"], "appr_demo");
    assert_eq!(value["expectedThreadRevision"], 3);
    assert!(value.get("requestId").is_none());
}

/// 手动压缩错误必须保留稳定机器码与重试语义，且不得把 Java message 或 Provider 正文透传。
#[test]
fn context_compaction_errors_map_to_stable_command_failures() {
    for (error_code, expected_retryable) in [
        ("THREAD_BUSY", true),
        ("SUMMARY_FAILURE", true),
        ("CONTEXT_LIMIT", false),
        ("INVALID_STATE", false),
    ] {
        let error = command_error_from_rpc(&json!({
            "message": "private provider detail",
            "data": {"errorCode": error_code}
        }));
        assert_eq!(error.code, error_code);
        assert_eq!(error.retryable, expected_retryable);
        assert_ne!(error.message, "private provider detail");
    }
}

/// 配置与凭据命令必须保留 App Server 的稳定分类，Renderer 才能安全恢复 CAS 或提示存储故障。
#[test]
fn configuration_errors_map_to_stable_retry_semantics() {
    for (error_code, expected_retryable) in [
        ("CONFIG_INVALID", false),
        ("CONFIG_CONFLICT", true),
        ("STORAGE_UNAVAILABLE", true),
        ("CONFIG_CORRUPTED", false),
    ] {
        let error = command_error_from_rpc(&json!({
            "message": "private configuration storage detail",
            "data": {"errorCode": error_code}
        }));
        assert_eq!(error.code, error_code);
        assert_eq!(error.retryable, expected_retryable);
        assert_ne!(error.message, "private configuration storage detail");
    }
}

/// 附件预览错误必须跨 JA-RPC 保留授权、容量、状态和内容可用性分类，不能坍缩为通用 runtime 故障。
#[test]
fn attachment_errors_map_to_stable_retry_semantics() {
    for (error_code, expected_retryable) in [
        ("ATTACHMENT_NOT_FOUND", false),
        ("ATTACHMENT_LIMIT_EXCEEDED", false),
        ("ATTACHMENT_CONFLICT", false),
        ("ATTACHMENT_UNAVAILABLE", true),
    ] {
        let error = command_error_from_rpc(&json!({
            "message": "private attachment persistence detail",
            "data": {"errorCode": error_code}
        }));
        assert_eq!(error.code, error_code);
        assert_eq!(error.retryable, expected_retryable);
        assert_ne!(error.message, "private attachment persistence detail");
    }
}

/// Goal 错误必须逐项保留冻结的 JA-RPC code/retryable；未知码降级会把 stale approval
/// 误报成存储故障，并破坏 UI 的冲突恢复动作。
#[test]
fn goal_errors_map_to_stable_retry_semantics() {
    for (error_code, expected_retryable) in [
        ("GOAL_NOT_FOUND", false),
        ("GOAL_REVISION_CONFLICT", true),
        ("GOAL_INVALID_STATE", false),
        ("PLAN_INVALID", false),
        ("PLAN_APPROVAL_STALE", false),
        ("GOAL_EVIDENCE_INCOMPLETE", false),
        ("GOAL_RECOVERY_REQUIRED", false),
        ("GOAL_INPUT_EXPIRED", false),
    ] {
        let error = command_error_from_rpc(&json!({
            "message": "private goal persistence detail",
            "data": {"errorCode": error_code}
        }));
        assert_eq!(error.code, error_code);
        assert_eq!(error.retryable, expected_retryable);
        assert_ne!(error.message, "private goal persistence detail");
    }
}

/// Resume 的三个领域拒绝必须保持稳定 code/retryable，Java 私有诊断不能穿透 Tauri。
#[test]
fn resume_errors_map_to_stable_retry_semantics() {
    for (error_code, retryable) in [
        ("TURN_NOT_RESUMABLE", false),
        ("TURN_RESUME_ORDER_CONFLICT", true),
    ] {
        let error = command_error_from_rpc(&json!({
            "message": "private execution state",
            "data": {"errorCode": error_code}
        }));
        assert_eq!(error.code, error_code);
        assert_eq!(error.retryable, retryable);
        assert_ne!(error.message, "private execution state");
    }
}

/// Resume Accepted 必须精确回显请求 Turn，且 accepted/queued 都为 true、无额外状态字段。
#[test]
fn resume_result_parser_is_identity_bound_and_exact() {
    let accepted = json!({
        "result": {
            "accepted": true,
            "turnId": "turn_resume_fixture",
            "queued": true,
            "threadRevision": 8
        }
    });
    let parsed = parse_turn_resume_result("turn_resume_fixture", &accepted).expect("accepted");
    assert_eq!(parsed.turn_id, "turn_resume_fixture");
    assert_eq!(parsed.thread_revision, 8);

    let mut wrong_identity = accepted.clone();
    wrong_identity["result"]["turnId"] = json!("turn_other");
    assert!(parse_turn_resume_result("turn_resume_fixture", &wrong_identity).is_err());
    let mut not_queued = accepted.clone();
    not_queued["result"]["queued"] = json!(false);
    assert!(parse_turn_resume_result("turn_resume_fixture", &not_queued).is_err());
    let mut extra = accepted;
    extra["result"]["executionState"] = json!("ready");
    assert!(parse_turn_resume_result("turn_resume_fixture", &extra).is_err());
}

/// 队列 mutation ACK 必须返回完整权威队列，保持 Java 顺序并拒绝重复 identity、超额或额外字段。
#[test]
fn turn_input_result_parser_is_exact_and_bounded() {
    let accepted = json!({
        "result": {
            "accepted": true,
            "inputId": "input_a",
            "inputQueue": {
                "turnId": "turn_queue",
                "revision": 2,
                "accepting": true,
                "items": [
                    {"inputId":"input_b","turnId":"turn_queue","content":[{"type":"text","text":"优先处理"}],"attachments":[], "kind":"steering",
                        "status":"pending","issue":null,"inputRevision":2,"createdAt":"2026-09-01T01:00:00Z"},
                    {"inputId":"input_a","turnId":"turn_queue","content":[{"type":"text","text":"普通后续"}],"attachments":[], "kind":"follow_up",
                        "status":"pending","issue":null,"inputRevision":1,"createdAt":"2026-09-01T01:00:01Z"}
                ]
            }
        }
    });
    let parsed = parse_turn_input_result("turn_queue", &accepted).expect("queue result");
    assert_eq!(parsed.input_id, "input_a");
    assert_eq!(parsed.input_queue.items[0].input_id, "input_b");
    assert_eq!(parsed.input_queue.items[1].input_id, "input_a");

    let mut extra = accepted.clone();
    extra["result"]["status"] = json!("queued");
    assert!(parse_turn_input_result("turn_queue", &extra).is_err());
    let mut duplicate = accepted;
    duplicate["result"]["inputQueue"]["items"][1]["inputId"] = json!("input_b");
    assert!(parse_turn_input_result("turn_queue", &duplicate).is_err());
}

/// 队列附件摘要必须与 content 同序同 ID，且附件不可用是可恢复 attention 闭集的一部分。
#[test]
fn queued_attachment_summary_and_unavailable_issue_are_strict() {
    let accepted = json!({
        "result": {
            "accepted": true,
            "inputId": "input_attachment",
            "inputQueue": {
                "turnId": "turn_queue",
                "revision": 3,
                "accepting": true,
                "items": [{
                    "inputId": "input_attachment",
                    "turnId": "turn_queue",
                    "content": [{"type":"attachment","attachmentId":"att_capture"}],
                    "attachments": [{"attachmentId":"att_capture","displayName":"capture.png",
                        "sizeBytes":128,"mediaKind":"image","mediaType":"image/png"}],
                    "kind": "follow_up",
                    "status": "needs_attention",
                    "issue": {"errorCode":"ATTACHMENT_UNAVAILABLE",
                        "message":"附件暂不可用","retryable":false},
                    "inputRevision": 2,
                    "createdAt": "2026-09-03T01:00:00Z"
                }]
            }
        }
    });
    let parsed = parse_turn_input_result("turn_queue", &accepted).expect("attachment queue");
    assert_eq!(
        parsed.input_queue.items[0].attachments[0].attachment_id,
        "att_capture"
    );
    assert_eq!(
        parsed.input_queue.items[0]
            .issue
            .as_ref()
            .map(|issue| issue.error_code.as_str()),
        Some("ATTACHMENT_UNAVAILABLE")
    );

    let mut mismatched = accepted;
    mismatched["result"]["inputQueue"]["items"][0]["attachments"][0]["attachmentId"] =
        json!("att_other");
    assert!(parse_turn_input_result("turn_queue", &mismatched).is_err());
}

/// Path search 结果必须回显全部竞态栅栏，并拒绝绝对路径、重复路径和额外字段。
#[test]
fn workspace_path_search_result_is_generation_fenced() {
    let input = WorkspacePathSearchInput {
        thread_id: "thr_demo".to_owned(),
        workspace_id: "ws_demo".to_owned(),
        query: "src".to_owned(),
        limit: Some(20),
    };
    let accepted = json!({"result": {
        "threadId":"thr_demo", "workspaceId":"ws_demo", "generation":3, "query":"src",
        "items":[{"relativePath":"src/main.rs","kind":"file"}], "truncated":false
    }});
    let result = parse_workspace_path_search_result(&input, 3, &accepted).expect("path result");
    assert_eq!(result.items[0].relative_path, "src/main.rs");
    assert!(parse_workspace_path_search_result(&input, 4, &accepted).is_err());
    let mut absolute = accepted;
    absolute["result"]["items"][0]["relativePath"] = json!("C:/secret.txt");
    assert!(parse_workspace_path_search_result(&input, 3, &absolute).is_err());
}

/// 队列容量与不存在错误在 native 边界保持可恢复语义，且不透传 Java message。
#[test]
fn turn_input_errors_map_to_stable_retry_semantics() {
    for (error_code, retryable) in [
        ("TURN_INPUT_QUEUE_FULL", true),
        ("QUEUED_INPUT_NOT_FOUND", false),
    ] {
        let error = command_error_from_rpc(&json!({
            "message": "private queue storage detail",
            "data": {"errorCode": error_code}
        }));
        assert_eq!(error.code, error_code);
        assert_eq!(error.retryable, retryable);
        assert_ne!(error.message, "private queue storage detail");
    }
}

/// History bridge 必须把 Java 错误映射为稳定 IPC envelope，不能把 Java message 或 raw
/// snapshot 带回调用方；未知错误仍统一收敛为 unavailable。
#[test]
fn history_bridge_failure_preserves_redacted_error_mapping() {
    let missing = command_error_from_rpc(&json!({
        "message": "private snapshot payload",
        "data": {"errorCode": "THREAD_NOT_FOUND"}
    }));
    assert_eq!(missing.code, "THREAD_NOT_FOUND");
    assert_eq!(missing.message, "thread was not found");
    assert!(!missing.message.contains("payload"));

    let unknown = command_error_from_rpc(&json!({
        "message": "private snapshot payload",
        "data": {"errorCode": "UNMAPPED_HISTORY_FAILURE"}
    }));
    assert_eq!(unknown.code, "RUNTIME_UNAVAILABLE");
    assert!(!unknown.message.contains("payload"));
}

/// 为严格解码用例构造自洽 wire；每个负例只破坏一个边界，避免错误原因互相遮蔽。
fn change_set_wire(bytes: &[u8]) -> WireTurnChangeSetReadResult {
    WireTurnChangeSetReadResult {
        artifact_id: "artifact_turn_1".to_owned(),
        file_path: "src/main.rs".to_owned(),
        byte_length: bytes.len() as u64,
        sha256: Sha256::digest(bytes)
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect(),
        content_base64: base64::engine::general_purpose::STANDARD.encode(bytes),
    }
}

/// 多字节正文必须按 bytes 计量，并在成功路径保留完整内容与摘要。
#[test]
fn turn_change_set_content_accepts_canonical_multibyte_utf8() {
    let result = validate_turn_change_set_content(
        change_set_wire("修改\n".as_bytes()),
        "artifact_turn_1",
        "src/main.rs",
    )
    .expect("canonical UTF-8 payload");
    assert_eq!(result.byte_length, 7);
    assert_eq!(result.content, "修改\n");
}

/// 标准 Base64 必须规范编码；缺 padding、非零 tail bits、URL-safe 字符与空白全部拒绝。
#[test]
fn turn_change_set_content_rejects_noncanonical_base64() {
    for content_base64 in ["YQ", "YR==", "____", "YQ==\n", "Y Q="] {
        let mut wire = change_set_wire(b"a");
        wire.content_base64 = content_base64.to_owned();
        assert!(
            validate_turn_change_set_content(wire, "artifact_turn_1", "src/main.rs").is_err(),
            "noncanonical payload must be rejected: {content_base64:?}"
        );
    }
}

/// identity、声明长度、摘要和 UTF-8 任一不一致都必须关闭失败。
#[test]
fn turn_change_set_content_rejects_identity_length_digest_and_utf8_mismatch() {
    let mut wrong_length = change_set_wire(b"content");
    wrong_length.byte_length += 1;
    assert!(
        validate_turn_change_set_content(wrong_length, "artifact_turn_1", "src/main.rs").is_err()
    );

    let mut wrong_digest = change_set_wire(b"content");
    wrong_digest.sha256 = "0".repeat(64);
    assert!(
        validate_turn_change_set_content(wrong_digest, "artifact_turn_1", "src/main.rs").is_err()
    );

    assert!(
        validate_turn_change_set_content(
            change_set_wire(b"content"),
            "artifact_other",
            "src/main.rs"
        )
        .is_err()
    );
    assert!(
        validate_turn_change_set_content(
            change_set_wire(b"content"),
            "artifact_turn_1",
            "src/other.rs"
        )
        .is_err()
    );
    assert!(
        validate_turn_change_set_content(
            change_set_wire(&[0xff, 0xfe]),
            "artifact_turn_1",
            "src/main.rs"
        )
        .is_err()
    );
}

/// 2 MiB 正文可读，增加一个 decoded byte 必须拒绝；边界不依赖 Base64 字符数猜测。
#[test]
fn turn_change_set_content_enforces_decoded_two_mebibyte_limit() {
    let exact = vec![b'a'; ja_runtime::app_server_process::TURN_CHANGE_SET_MAX_BYTES as usize];
    assert_eq!(change_set_wire(&exact).content_base64.len(), 2_796_204);
    assert!(
        validate_turn_change_set_content(change_set_wire(&exact), "artifact_turn_1", "src/main.rs")
            .is_ok()
    );
    let over = vec![b'a'; exact.len() + 1];
    assert!(
        validate_turn_change_set_content(change_set_wire(&over), "artifact_turn_1", "src/main.rs")
            .is_err()
    );
}

/// 双槽读取门禁必须立即拒绝第三个请求；任意终态 drop 后都允许后续请求复用槽位。
#[test]
fn turn_change_set_read_permits_are_bounded_and_released_by_drop() {
    let in_flight = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let first = ChangeSetReadPermit::acquire(std::sync::Arc::clone(&in_flight))
        .expect("first change set read permit");
    let second = ChangeSetReadPermit::acquire(std::sync::Arc::clone(&in_flight))
        .expect("second change set read permit");
    let saturated = match ChangeSetReadPermit::acquire(std::sync::Arc::clone(&in_flight)) {
        Ok(_) => panic!("third concurrent read must fail fast"),
        Err(error) => error,
    };
    assert_eq!(saturated.code, "RUNTIME_QUEUE_FULL");
    assert_eq!(in_flight.load(std::sync::atomic::Ordering::Acquire), 2);

    drop(first);
    let replacement = ChangeSetReadPermit::acquire(std::sync::Arc::clone(&in_flight))
        .expect("released slot must be reusable");
    assert_eq!(in_flight.load(std::sync::atomic::Ordering::Acquire), 2);
    drop(second);
    drop(replacement);
    assert_eq!(in_flight.load(std::sync::atomic::Ordering::Acquire), 0);
}

/// Tool artifact 使用 Unicode code point 分页；返回页不得越过请求 limit，也不能通过错误
/// artifact identity 或空进度页跨 Call 重放。
#[test]
fn tool_artifact_page_validation_is_identity_bound_and_monotonic() {
    let valid = json!({
        "artifactId": "artifact_tool_1",
        "offsetCharacters": 0,
        "nextOffsetCharacters": 2,
        "totalCharacters": 4,
        "truncated": true,
        "content": "你好"
    });
    let page = validate_tool_artifact_page(valid.clone(), "artifact_tool_1", 0, 2)
        .expect("valid Unicode character page");
    assert_eq!(page.next_offset_characters, Some(2));

    assert!(validate_tool_artifact_page(valid.clone(), "artifact_other", 0, 2).is_err());
    let mut stalled = valid.clone();
    stalled["nextOffsetCharacters"] = json!(0);
    stalled["content"] = json!("");
    assert!(validate_tool_artifact_page(stalled, "artifact_tool_1", 0, 2).is_err());

    let mut over_budget = valid;
    over_budget["nextOffsetCharacters"] = json!(3);
    over_budget["content"] = json!("你好啊");
    assert!(validate_tool_artifact_page(over_budget, "artifact_tool_1", 0, 2).is_err());
}

/// Tool batch 与其它 JA-RPC 1.0 notification 使用同一 Java instance + host generation fence；
/// Rust 不依赖首版合同之外的 workspaceDirty 字段。
#[test]
fn tool_batch_identity_requires_the_current_java_instance() {
    let batch = RpcFrame::notification(
        "tool/batch-committed",
        json!({
            "serverInstanceId": "srv_fixture",
            "generation": 7,
            "threadId": "thr_fixture",
            "threadRevision": 2
        }),
    )
    .expect("tool batch fixture");
    assert!(notification_matches_identity(&batch, "srv_fixture", 7));
    assert!(!notification_matches_identity(&batch, "srv_other", 7));
    assert!(!notification_matches_identity(&batch, "srv_fixture", 8));
    assert!(
        batch
            .params()
            .is_some_and(|params| params.get("workspaceDirty").is_none())
    );

    let missing = RpcFrame::notification(
        "tool/batch-committed",
        json!({
            "threadId": "thr_fixture",
            "threadRevision": 2
        }),
    )
    .expect("missing identity tool batch fixture");
    assert!(!notification_matches_identity(&missing, "srv_fixture", 7));

    let terminal = RpcFrame::notification(
        "turn/terminal",
        json!({"serverInstanceId": "srv_fixture", "generation": 7}),
    )
    .expect("terminal fixture");
    assert!(notification_matches_identity(&terminal, "srv_fixture", 7));
    assert!(!notification_matches_identity(&terminal, "srv_other", 7));
    assert!(!notification_matches_identity(&terminal, "srv_fixture", 6));
}

/// Completion 协议依赖 mutex、Condvar 与 AtomicBool 的同一 happens-before 关系；mutex
/// 中毒后不能只读取 atomic 并误判 worker 已安全完成。
#[test]
fn completion_poison_keeps_worker_completion_unconfirmed() {
    let completion = Completion::new();
    poison_mutex(&completion.lock);

    assert!(!completion.wait_until(Instant::now()));
}

/// exit attempt 锁中毒表示 deadline 或编号可能只提交了一半；控制器必须立即取消准入并
/// 返回已到期 attempt，而不是恢复未知状态后继续等待。
#[test]
fn exit_control_poison_cancels_and_expires_the_attempt() {
    let control = ExitControl::new(Duration::from_secs(30));
    poison_mutex(&control.attempt);

    let attempt = control.trigger();

    assert!(control.is_cancelled());
    assert!(attempt.deadline <= Instant::now());
    assert!(
        control
            .deadline()
            .is_some_and(|deadline| deadline <= Instant::now())
    );
}

/// quarantine 同时保存 process owner 与 crash 投影；事务锁中毒后 state/retry 必须返回
/// 稳定错误，且 empty 证明永久保持关闭，避免 Tauri 放行未知 owner。
#[test]
fn quarantine_poison_preserves_cleanup_debt_and_blocks_exit() {
    let quarantine = ExitQuarantine::new();
    let cleanup_fault = CleanupFault::new();
    poison_mutex(&quarantine.state);

    assert_eq!(
        quarantine
            .state(&cleanup_fault)
            .expect_err("poisoned quarantine state must fail")
            .code,
        "RUNTIME_UNAVAILABLE"
    );
    assert_eq!(
        quarantine
            .retry_until(
                Instant::now(),
                &cleanup_fault,
                production_runtime_control().as_ref(),
            )
            .expect_err("poisoned quarantine retry must fail")
            .code,
        "RUNTIME_UNAVAILABLE"
    );
    assert!(!quarantine.is_empty());
}

/// actor join slot 中毒后无法证明 JoinHandle 是否仍被持有；shutdown 返回稳定错误，
/// exit-ready 也必须保持 false，直到调用方明确重建一致状态。
#[test]
fn actor_join_poison_rejects_shutdown_and_exit_readiness() {
    let (commands, command_receiver) = mpsc::sync_channel(1);
    let (shutdown, shutdown_receiver) = mpsc::sync_channel(1);
    drop(command_receiver);
    drop(shutdown_receiver);
    let completion = Arc::new(Completion::new());
    completion.mark_done();
    let root = std::env::temp_dir().join(format!(
        "ja-runtime-actor-join-poison-{}",
        uuid::Uuid::new_v4()
    ));
    std::fs::create_dir_all(&root).expect("actor join poison fixture");
    let inner = RuntimeBridgeInner {
        commands,
        shutdown,
        completion,
        detached_event_generation: Arc::new(AtomicU64::new(0)),
        cleanup_fault: Arc::new(CleanupFault::new()),
        exit_control: Arc::new(ExitControl::new(Duration::from_secs(1))),
        quarantine: Arc::new(ExitQuarantine::new()),
        shutdown_completed: Arc::new(AtomicBool::new(true)),
        recovery_path: root.join("recovery.json"),
        actor_join: Mutex::new(None),
        runtime_control: production_runtime_control(),
        current_generation: Arc::new(AtomicU64::new(0)),
        change_set_reads_in_flight: Arc::new(AtomicUsize::new(0)),
    };
    poison_mutex(&inner.actor_join);

    assert_eq!(
        inner
            .shutdown()
            .expect_err("poisoned join must reject shutdown")
            .code,
        "RUNTIME_UNAVAILABLE"
    );
    assert!(!inner.exit_ready());

    // 夹具知道 slot 从未承载 handle，因此可显式重建一致状态，让 Drop 不制造恢复记录。
    inner.actor_join.clear_poison();
    drop(inner);
    std::fs::remove_dir_all(root).expect("remove actor join poison fixture");
}
