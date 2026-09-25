// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;
use crate::app_runtime::{EventSink, LaunchConfig, WorkspaceOpenInput};
use crate::runtime_test_support::RuntimeHostHarness;
use ja_runtime::app_server_process::SidecarConfig;
use std::ffi::OsString;
use std::path::PathBuf;
use std::sync::Arc;

/// 使用生产 `LaunchConfig` 构造受控配置，避免 Workspace 单测依赖 feature-gated Harness，
/// 同时仍保持进程不会在构造阶段启动的生命周期不变量。
fn launch_config(executable: PathBuf, args: Vec<OsString>, run_dir: PathBuf) -> LaunchConfig {
    let mut sidecar = SidecarConfig::with_directories(
        executable,
        run_dir.clone(),
        run_dir.clone(),
        run_dir.clone(),
        run_dir,
    );
    sidecar.args = args;
    LaunchConfig::from_sidecar(sidecar)
}

/// 验证公开 Workspace id 规则与内部 UUID 保持隔离，防止协议身份被原生句柄替代。
#[test]
fn workspace_id_validation_is_protocol_scoped() {
    assert!(validate_workspace_id("ws_project"));
    assert!(!validate_workspace_id("project"));
    assert!(!validate_workspace_id("ws_"));
    assert!(!validate_workspace_id("ws_project:internal"));
}

/// 验证控制字符会在路径服务执行文件 IO 前被拒绝，同时 traversal 仍交给权威 containment 处理。
#[test]
fn relative_path_validation_rejects_controls_only_at_wire_edge() {
    assert!(validate_relative_path("src/main.rs"));
    assert!(validate_relative_path("../escape"));
    assert!(!validate_relative_path("src/\u{0000}main.rs"));
}

/// 将人工恢复失败锁定为唯一且不含路径的线协议 code，调用方不能把磁盘所有权不确定
/// 误判为普通可重试 IO。
#[test]
fn recovery_required_has_stable_redacted_projection() {
    let error = WorkspaceCommandError::from_workspace(WorkspaceError::RecoveryRequired);
    let value = serde_json::to_value(error).expect("serialize recovery error");
    assert_eq!(
        value,
        serde_json::json!({ "code": "WORKSPACE_RECOVERY_REQUIRED" })
    );
    assert_eq!(
        error.to_string(),
        "workspace recovery is required before continuing"
    );
    assert!(!error.to_string().contains(['/', '\\', ':']));
}

/// 卷回收策略被禁用时必须返回可操作且不可重试的独立错误，并且不得泄露卷标或 Registry key。
#[test]
fn recycle_unavailable_has_stable_redacted_projection() {
    let error = WorkspaceCommandError::from_workspace(WorkspaceError::RecycleUnavailable);
    let value = serde_json::to_value(error).expect("serialize recycle error");
    assert_eq!(value, serde_json::json!({ "code": "RECYCLE_UNAVAILABLE" }));
    assert_eq!(
        error.to_string(),
        "system recycle bin is unavailable for this volume"
    );
    assert!(!error.to_string().contains(['/', '\\', ':']));
}

/// 将 IPC 投影固定为 camelCase，同时保留 Rust 服务模型的 snake_case 字段，
/// 防止 interface 命名反向污染内部类型。
#[test]
fn tree_projection_is_camel_case_and_path_free() {
    let file_hash = "a".repeat(64);
    let directory_hash = "b".repeat(64);
    let revision = FileRevision::try_new(EntryKind::File, 3, Some(7), Some(file_hash.clone()))
        .expect("valid file revision");
    let page = project_tree(TreePage {
        entries: vec![TreeEntry {
            name: "main.rs".to_owned(),
            relative_path: "src/main.rs".to_owned(),
            metadata: FileMetadata {
                kind: EntryKind::File,
                size: 3,
                modified_unix_millis: Some(7),
                revision,
            },
            can_expand: false,
        }],
        directory_revision: FileRevision::try_new(
            EntryKind::Directory,
            0,
            Some(7),
            Some(directory_hash.clone()),
        )
        .expect("valid directory revision"),
        next_cursor: Some("1".to_owned()),
        snapshot_token: "token".to_owned(),
        total_entries: 1,
        depth: 1,
    });
    let value = serde_json::to_value(page).expect("serialize tree DTO");
    assert_eq!(value["nextCursor"], "1");
    assert_eq!(value["entries"][0]["relativePath"], "src/main.rs");
    assert!(value["entries"][0].get("relative_path").is_none());
    assert_eq!(
        value["entries"][0]["metadata"]["revision"]["sha256"],
        file_hash
    );
    assert_eq!(value["directoryRevision"]["kind"], "directory");
    assert_eq!(value["directoryRevision"]["sha256"], directory_hash);
}

/// 验证首版 Workspace open 会原子替换待确认的原生绑定；Java 返回身份前，
/// 协议 Workspace id 仍不得取得文件能力，避免 Rust 提前复制业务事实。
#[test]
fn host_workspace_open_switch_replaces_pending_binding() {
    let base = std::env::temp_dir().join(format!("ja-command-binding-{}", uuid::Uuid::new_v4()));
    let first = base.join("first");
    let second = base.join("second");
    std::fs::create_dir_all(&first).expect("first workspace");
    std::fs::create_dir_all(&second).expect("second workspace");
    let sink: EventSink = Arc::new(|_| Ok(()));
    let host = RuntimeHost::new(
        launch_config(PathBuf::from("java"), Vec::new(), base.join("run")),
        sink,
    );
    assert!(!RuntimeHostHarness::workspace_binding_present(&host).expect("binding probe"));
    assert_eq!(
        host.with_configured_workspace("ws_first", |_| ())
            .expect_err("runtime is not ready"),
        WorkspaceLookup::Unknown
    );
    let first_open = host
        .open_workspace(WorkspaceOpenInput {
            cwd: first.to_string_lossy().into_owned(),
            display_name: None,
            trust: "trusted".to_owned(),
        })
        .expect("first binding");
    assert!(first_open.accepted);
    assert!(first_open.workspace_id.is_none());
    assert_eq!(
        host.with_configured_workspace("ws_first", |_| ())
            .expect_err("workspace open is not Ready yet"),
        WorkspaceLookup::Unknown
    );
    let second_open = host
        .open_workspace(WorkspaceOpenInput {
            cwd: second.to_string_lossy().into_owned(),
            display_name: None,
            trust: "trusted".to_owned(),
        })
        .expect("second binding");
    assert!(second_open.accepted);
    assert!(second_open.workspace_id.is_none());
    assert_ne!(first_open.cwd, second_open.cwd);
    assert_eq!(
        host.with_configured_workspace("ws_first", |_| ())
            .expect_err("old binding must be gone"),
        WorkspaceLookup::Unknown
    );
    assert_eq!(
        host.with_configured_workspace("ws_second", |_| ())
            .expect_err("workspace open is not Ready yet"),
        WorkspaceLookup::Unknown
    );
    let _ = std::fs::remove_dir_all(base);
}

/// 验证重新配置失败、stop 与 shutdown 都不会让旧文件句柄继续可读；
/// 测试通过统一 Harness 构造配置，不把测试构造器重新暴露到生产类型。
#[test]
fn host_workspace_binding_clears_on_failure_stop_and_shutdown() {
    let base = std::env::temp_dir().join(format!(
        "ja-command-binding-lifecycle-{}",
        uuid::Uuid::new_v4()
    ));
    let root = base.join("root");
    std::fs::create_dir_all(&root).expect("workspace root");
    std::fs::create_dir_all(base.join("run")).expect("runtime run directory");
    let sink: EventSink = Arc::new(|_| Ok(()));
    let host = RuntimeHost::new(
        launch_config(PathBuf::from("java"), Vec::new(), base.join("run")),
        sink,
    );
    let open_workspace = |host: &RuntimeHost| {
        host.open_workspace(WorkspaceOpenInput {
            cwd: root.to_string_lossy().into_owned(),
            display_name: None,
            trust: "trusted".to_owned(),
        })
    };

    open_workspace(&host).expect("initial binding");
    assert_eq!(
        host.with_configured_workspace("ws_lifecycle", |_| ())
            .expect_err("workspace open is not Ready yet"),
        WorkspaceLookup::Unknown
    );
    let failed = host.open_workspace(WorkspaceOpenInput {
        cwd: base.join("missing").to_string_lossy().into_owned(),
        display_name: None,
        trust: "trusted".to_owned(),
    });
    assert!(failed.is_err());
    assert_eq!(
        host.with_configured_workspace("ws_lifecycle", |_| ())
            .expect_err("failed configure preserves the prior atomic binding"),
        WorkspaceLookup::Unknown
    );

    open_workspace(&host).expect("binding after failed workspace open");
    host.stop().expect("stop boundary");
    assert!(!RuntimeHostHarness::workspace_binding_present(&host).expect("stop binding probe"));
    assert_eq!(
        host.with_configured_workspace("ws_lifecycle", |_| ())
            .expect_err("stopped runtime fails closed"),
        WorkspaceLookup::Unknown
    );

    open_workspace(&host).expect("binding before shutdown");
    host.shutdown().expect("shutdown boundary");
    assert!(!RuntimeHostHarness::workspace_binding_present(&host).expect("shutdown binding probe"));
    assert_eq!(
        host.with_configured_workspace("ws_lifecycle", |_| ())
            .expect_err("shutdown runtime fails closed"),
        WorkspaceLookup::Unknown
    );

    // 启动失败也必须撤销 sidecar Ready 前已接纳的绑定；故意使用不存在的
    // executable，使该恢复断言不依赖本机是否安装或打包 Java。
    let failed_start_base = base.join("failed-start");
    let failed_start_root = failed_start_base.join("root");
    std::fs::create_dir_all(&failed_start_root).expect("failed-start root");
    let failed_start_host = RuntimeHost::new(
        launch_config(
            failed_start_base.join("missing-sidecar.exe"),
            Vec::new(),
            failed_start_base.join("run"),
        ),
        Arc::new(|_| Ok(())),
    );
    failed_start_host
        .open_workspace(WorkspaceOpenInput {
            cwd: failed_start_root.to_string_lossy().into_owned(),
            display_name: None,
            trust: "trusted".to_owned(),
        })
        .expect("binding before failed start");
    assert!(
        failed_start_host.start().is_err(),
        "missing sidecar must fail"
    );
    assert!(
        !RuntimeHostHarness::workspace_binding_present(&failed_start_host)
            .expect("failed-start binding probe")
    );
    assert_eq!(
        failed_start_host
            .with_configured_workspace("ws_failed_start", |_| ())
            .expect_err("failed runtime fails closed"),
        WorkspaceLookup::Unknown
    );
    let _ = failed_start_host.shutdown();
    let _ = std::fs::remove_dir_all(base);
}
