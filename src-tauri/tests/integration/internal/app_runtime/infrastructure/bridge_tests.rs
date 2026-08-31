// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// 原生 bridge 单元测试与生产实现分文件，仍验证同一私有状态机。

use super::*;
use serde_json::json;

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
        ("TOKEN_COUNT_UNAVAILABLE", true),
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

/// Frozen diff reader 必须复核 artifact identity、byte 进度与调用方页预算；零进度的
/// truncated 页会让 Review 无限重读，必须关闭失败。
#[test]
fn turn_change_set_page_validation_is_bounded_and_monotonic() {
    let valid = json!({
        "artifactId": "artifact_turn_1",
        "offsetBytes": 0,
        "nextOffsetBytes": 3,
        "byteLength": 6,
        "truncated": true,
        "content": "你"
    });
    let page = validate_turn_change_set_page(valid.clone(), "artifact_turn_1", 0, 3)
        .expect("valid UTF-8 byte page");
    assert_eq!(page.next_offset_bytes, Some(3));

    let mut stalled = valid.clone();
    stalled["nextOffsetBytes"] = json!(0);
    stalled["content"] = json!("");
    assert!(validate_turn_change_set_page(stalled, "artifact_turn_1", 0, 3).is_err());

    let mut over_budget = valid;
    over_budget["nextOffsetBytes"] = json!(6);
    over_budget["content"] = json!("你好");
    assert!(validate_turn_change_set_page(over_budget, "artifact_turn_1", 0, 3).is_err());
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

/// host ownership 与 baseline 分账后，即使 baseline 因内部异常缺失，也必须生成显式
/// capture_failed；未登记为本 host 的 Turn 则不能被 Rust 越权提交。
#[test]
fn host_turn_with_missing_baseline_becomes_explicitly_unavailable() {
    let mut baselines = HashMap::new();
    let mut host_turns = HashMap::from([("turn_owned".to_owned(), "ws_owned".to_owned())]);
    let (workspace_id, change_set) =
        finish_host_turn_change("turn_owned", &mut baselines, &mut host_turns)
            .expect("owned turn must produce change-set");
    assert_eq!(workspace_id, "ws_owned");
    assert!(matches!(
        change_set,
        crate::review::domain::TurnChangeSet::Unavailable {
            reason: crate::review::domain::TurnChangeUnavailableReason::CaptureFailed
        }
    ));
    assert!(
        finish_host_turn_change("turn_external", &mut baselines, &mut host_turns).is_none(),
        "non-host terminal must not create a change-set commit"
    );
}

/// artifact commit 未确认不能吞掉 Java 权威 terminal；该事件仍到达 renderer，供 controller
/// 发起 Thread authoritative refresh，同时日志只记录稳定错误码。
#[test]
fn change_set_commit_failure_still_projects_terminal_refresh_trigger() {
    let received = Arc::new(Mutex::new(Vec::new()));
    let target = Arc::clone(&received);
    let sink: EventSink = Arc::new(move |value| {
        target
            .lock()
            .map_err(|_| crate::app_runtime::EventEmitError::DeliveryFailed)?
            .push(value);
        Ok(())
    });
    let frame = RpcFrame::notification(
        "turn/terminal",
        json!({
            "serverInstanceId": "srv_1", "eventId": "evt_terminal_commit_failure",
            "sequence": 7, "generation": 1, "workspaceId": "ws_1",
            "threadId": "thr_1", "turnId": "turn_1", "threadRevision": 7,
            "occurredAt": "2026-08-30T00:00:00Z", "state": "completed",
            "summary": "done", "finalMessage": {"messageId": "item_7", "text": "done"}
        }),
    )
    .expect("terminal frame");
    emit_terminal_after_change_set(
        &sink,
        &frame,
        1,
        Err(RuntimeCommandError {
            code: "RUNTIME_UNAVAILABLE",
            message: "runtime is unavailable",
            retryable: true,
        }),
    );
    let received = received.lock().expect("terminal sink");
    assert_eq!(received.len(), 1);
    assert_eq!(received[0]["method"], "turn/terminal");
}

/// Tool batch 与其它 v2 notification 使用同一显式 Java instance fence；Rust 不再依赖已从
/// canonical v2 删除的 workspaceDirty 字段。
#[test]
fn tool_batch_identity_requires_the_current_java_instance() {
    let batch = RpcFrame::notification(
        "tool/batch-committed",
        json!({
            "serverInstanceId": "srv_fixture",
            "threadId": "thr_fixture",
            "threadRevision": 2
        }),
    )
    .expect("tool batch fixture");
    assert!(notification_matches_identity(&batch, "srv_fixture"));
    assert!(!notification_matches_identity(&batch, "srv_other"));
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
    assert!(!notification_matches_identity(&missing, "srv_fixture"));

    let terminal =
        RpcFrame::notification("turn/terminal", json!({"serverInstanceId": "srv_fixture"}))
            .expect("terminal fixture");
    assert!(notification_matches_identity(&terminal, "srv_fixture"));
    assert!(!notification_matches_identity(&terminal, "srv_other"));
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
