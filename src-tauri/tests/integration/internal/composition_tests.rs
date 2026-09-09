// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use std::cell::Cell;
use std::fs;
use std::path::PathBuf;

/// 验证四态合同仅在 Drop 携带 token，且 DPI 投影与序列化都不泄漏 issuer 捕获的私有路径。
#[test]
fn native_drop_notification_redacts_paths_and_normalizes_dpi() {
    let private_path = PathBuf::from(r"C:\private\secret.txt");
    let router = super::NativeDropRouter::default();
    let enter = tauri::DragDropEvent::Enter {
        paths: vec![private_path.clone()],
        position: tauri::PhysicalPosition::new(120.0, 60.0),
    };
    let enter_payload = router
        .project("main", &enter, 1.5, || {
            panic!("enter must not issue a token")
        })
        .expect("native enter projection")
        .expect("main window enter notification");
    assert_eq!(
        serde_json::to_value(enter_payload).expect("serialize native enter payload"),
        serde_json::json!({ "phase": "enter", "x": 80.0, "y": 40.0, "count": 1 })
    );
    let drop = tauri::DragDropEvent::Drop {
        paths: vec![private_path.clone()],
        position: tauri::PhysicalPosition::new(150.0, 75.0),
    };
    let payload = router
        .project("main", &drop, 1.5, move || {
            let _native_only = private_path;
            Ok("drop-token".to_owned())
        })
        .expect("native drop projection")
        .expect("main window notification");
    let value = serde_json::to_value(payload).expect("serialize native drop payload");
    assert_eq!(
        value,
        serde_json::json!({
            "phase": "drop", "x": 100.0, "y": 50.0, "count": 1,
            "dropToken": "drop-token"
        })
    );
    assert!(!value.to_string().contains("private"));
    assert!(!value.to_string().contains("secret"));
}

/// 验证辅助窗口既不能消费一次性 Native Drop token，也不会收到 renderer 通知。
#[test]
fn native_drop_notification_ignores_non_main_windows_before_issuance() {
    let issued = Cell::new(false);
    let router = super::NativeDropRouter::default();
    let drop = tauri::DragDropEvent::Drop {
        paths: vec![PathBuf::from("private.txt")],
        position: tauri::PhysicalPosition::new(12.0, 24.0),
    };
    let result = router
        .project("preview_1", &drop, 1.0, || {
            issued.set(true);
            Ok("must-not-be-issued".to_owned())
        })
        .expect("non-main projection");
    assert!(result.is_none());
    assert!(!issued.get());
}

/// 验证 Over 沿用 Enter 数量，Leave 使用最后逻辑坐标并清空状态，取消后不会再投影陈旧拖放。
#[test]
fn native_drop_notification_tracks_over_and_clears_on_leave() {
    let router = super::NativeDropRouter::default();
    let enter = tauri::DragDropEvent::Enter {
        paths: vec![PathBuf::from("one"), PathBuf::from("two")],
        position: tauri::PhysicalPosition::new(20.0, 40.0),
    };
    router
        .project("main", &enter, 2.0, || {
            panic!("enter must not issue a token")
        })
        .expect("enter projection");
    let over = tauri::DragDropEvent::Over {
        position: tauri::PhysicalPosition::new(60.0, 80.0),
    };
    let over = router
        .project("main", &over, 2.0, || panic!("over must not issue a token"))
        .expect("over projection")
        .expect("over notification");
    assert_eq!(over.phase, super::NativeDropPhase::Over);
    assert_eq!((over.x, over.y, over.count), (30.0, 40.0, 2));
    let leave = router
        .project("main", &tauri::DragDropEvent::Leave, 2.0, || {
            panic!("leave must not issue a token")
        })
        .expect("leave projection")
        .expect("leave notification");
    assert_eq!(leave.phase, super::NativeDropPhase::Leave);
    assert_eq!((leave.x, leave.y, leave.count), (30.0, 40.0, 2));
    assert!(
        router
            .project("main", &tauri::DragDropEvent::Leave, 2.0, || {
                panic!("stale leave must not issue a token")
            })
            .expect("stale leave projection")
            .is_none()
    );
}

#[cfg(desktop)]
/// 固定原生布局契约，防止意外恢复或保存临时的可见性、装饰和全屏状态。
#[test]
fn main_window_state_policy_is_explicit() {
    use super::{MAIN_WINDOW_STATE_FLAGS, WindowStateFlags, persist_main_window_state};

    assert!(persist_main_window_state("main"));
    assert!(!persist_main_window_state("preview"));
    assert!(MAIN_WINDOW_STATE_FLAGS.contains(WindowStateFlags::POSITION));
    assert!(MAIN_WINDOW_STATE_FLAGS.contains(WindowStateFlags::SIZE));
    assert!(MAIN_WINDOW_STATE_FLAGS.contains(WindowStateFlags::MAXIMIZED));
    assert!(!MAIN_WINDOW_STATE_FLAGS.contains(WindowStateFlags::VISIBLE));
    assert!(!MAIN_WINDOW_STATE_FLAGS.contains(WindowStateFlags::DECORATIONS));
    assert!(!MAIN_WINDOW_STATE_FLAGS.contains(WindowStateFlags::FULLSCREEN));
}

use super::app_runtime::{EventEmitError, EventSink, RuntimeHost};
use crate::runtime_test_support::RuntimeHostHarness;
use std::sync::Arc;

/// 创建隔离路径，使测试无需修改进程级环境变量或接触真实 app data 即可验证目录创建。
fn test_root(label: &str) -> PathBuf {
    std::env::temp_dir().join(format!(
        "ja-settings-root-{}-{}-{}",
        std::process::id(),
        label,
        uuid::Uuid::new_v4()
    ))
}

/// 通过生产 handler 的共享命令清单实际发起 IPC，确保 `ja_runtime_query`
/// 到达 Rust 参数校验，而不是只验证某个辅助常量仍包含命令名。
#[test]
fn production_command_handler_registers_runtime_query() {
    use tauri::ipc::{CallbackFn, InvokeBody};
    use tauri::test::{INVOKE_KEY, get_ipc_response, mock_builder, mock_context, noop_assets};
    use tauri::webview::InvokeRequest;

    let run_dir = test_root("query-handler");
    fs::create_dir_all(&run_dir).expect("query handler run directory");
    let sink: EventSink = Arc::new(|_| Ok::<(), EventEmitError>(()));
    let host = RuntimeHost::new(
        RuntimeHostHarness::launch_config(
            run_dir.join("missing-sidecar"),
            Vec::new(),
            run_dir.clone(),
        ),
        sink,
    );
    let app = mock_builder()
        .invoke_handler(ja_command_handler!([]))
        .manage(host)
        .build(mock_context(noop_assets()))
        .expect("mock Tauri app");
    let webview = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
        .build()
        .expect("mock webview");
    let request = InvokeRequest {
        cmd: "ja_runtime_query".to_owned(),
        callback: CallbackFn(0),
        error: CallbackFn(1),
        url: if cfg!(any(windows, target_os = "android")) {
            "http://tauri.localhost"
        } else {
            "tauri://localhost"
        }
        .parse()
        .expect("mock invoke URL"),
        body: InvokeBody::Json(serde_json::json!({
            "input": {"method": "turn/start", "params": {}}
        })),
        headers: Default::default(),
        invoke_key: INVOKE_KEY.to_owned(),
    };
    let response = get_ipc_response(&webview, request).expect_err("handler must reject input");
    assert!(
        response.to_string().contains("INVALID_PARAMS"),
        "registered handler must return its validation error, got {response}"
    );
    cleanup(&run_dir);
}

/// 通过真实 MockRuntime IPC 调用异步 `ja_thread_compact`，证明 command 已注册且在任何
/// sidecar IO 前执行严格 Thread identity 校验。
#[test]
fn production_command_handler_registers_thread_compact() {
    use tauri::ipc::{CallbackFn, InvokeBody};
    use tauri::test::{INVOKE_KEY, get_ipc_response, mock_builder, mock_context, noop_assets};
    use tauri::webview::InvokeRequest;

    let run_dir = test_root("compact-handler");
    fs::create_dir_all(&run_dir).expect("compact handler run directory");
    let sink: EventSink = Arc::new(|_| Ok::<(), EventEmitError>(()));
    let host = RuntimeHost::new(
        RuntimeHostHarness::launch_config(
            run_dir.join("missing-sidecar"),
            Vec::new(),
            run_dir.clone(),
        ),
        sink,
    );
    let app = mock_builder()
        .invoke_handler(ja_command_handler!([]))
        .manage(host)
        .build(mock_context(noop_assets()))
        .expect("mock Tauri app");
    let webview = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
        .build()
        .expect("mock webview");
    let request = InvokeRequest {
        cmd: "ja_thread_compact".to_owned(),
        callback: CallbackFn(0),
        error: CallbackFn(1),
        url: if cfg!(any(windows, target_os = "android")) {
            "http://tauri.localhost"
        } else {
            "tauri://localhost"
        }
        .parse()
        .expect("mock invoke URL"),
        body: InvokeBody::Json(serde_json::json!({
            "input": {"threadId": "invalid", "expectedThreadRevision": 0}
        })),
        headers: Default::default(),
        invoke_key: INVOKE_KEY.to_owned(),
    };
    let response = get_ipc_response(&webview, request).expect_err("handler must reject input");
    assert!(
        response.to_string().contains("INVALID_PARAMS"),
        "registered compact handler must return validation error, got {response}"
    );
    cleanup(&run_dir);
}

/// 通过真实 MockRuntime IPC 调用 `ja_thread_seen`，证明命令进入生产注册表且在 sidecar IO
/// 前拒绝非规范 Thread identity；已读不能只停留在 renderer wrapper。
#[test]
fn production_command_handler_registers_thread_seen() {
    use tauri::ipc::{CallbackFn, InvokeBody};
    use tauri::test::{INVOKE_KEY, get_ipc_response, mock_builder, mock_context, noop_assets};
    use tauri::webview::InvokeRequest;

    let run_dir = test_root("seen-handler");
    fs::create_dir_all(&run_dir).expect("seen handler run directory");
    let sink: EventSink = Arc::new(|_| Ok::<(), EventEmitError>(()));
    let host = RuntimeHost::new(
        RuntimeHostHarness::launch_config(
            run_dir.join("missing-sidecar"),
            Vec::new(),
            run_dir.clone(),
        ),
        sink,
    );
    let app = mock_builder()
        .invoke_handler(ja_command_handler!([]))
        .manage(host)
        .build(mock_context(noop_assets()))
        .expect("mock Tauri app");
    let webview = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
        .build()
        .expect("mock webview");
    let request = InvokeRequest {
        cmd: "ja_thread_seen".to_owned(),
        callback: CallbackFn(0),
        error: CallbackFn(1),
        url: if cfg!(any(windows, target_os = "android")) {
            "http://tauri.localhost"
        } else {
            "tauri://localhost"
        }
        .parse()
        .expect("mock invoke URL"),
        body: InvokeBody::Json(serde_json::json!({
            "input": {"threadId": "invalid", "expectedThreadRevision": 0}
        })),
        headers: Default::default(),
        invoke_key: INVOKE_KEY.to_owned(),
    };
    let response = get_ipc_response(&webview, request).expect_err("handler must reject input");
    assert!(
        response.to_string().contains("INVALID_PARAMS"),
        "registered seen handler must return its validation error, got {response}"
    );
    cleanup(&run_dir);
}

/// 通过真实 MockRuntime IPC 调用 Task create，证明新增 command 进入生产注册表，且在
/// sidecar IO 前执行 Child Thread 输入校验。
#[test]
fn production_command_handler_registers_task_create() {
    use tauri::ipc::{CallbackFn, InvokeBody};
    use tauri::test::{INVOKE_KEY, get_ipc_response, mock_builder, mock_context, noop_assets};
    use tauri::webview::InvokeRequest;

    let run_dir = test_root("task-create-handler");
    fs::create_dir_all(&run_dir).expect("task create handler run directory");
    let sink: EventSink = Arc::new(|_| Ok::<(), EventEmitError>(()));
    let host = RuntimeHost::new(
        RuntimeHostHarness::launch_config(
            run_dir.join("missing-sidecar"),
            Vec::new(),
            run_dir.clone(),
        ),
        sink,
    );
    let app = mock_builder()
        .invoke_handler(ja_command_handler!([]))
        .manage(host)
        .build(mock_context(noop_assets()))
        .expect("mock Tauri app");
    let webview = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
        .build()
        .expect("mock webview");
    let request = InvokeRequest {
        cmd: "ja_runtime_task_create".to_owned(),
        callback: CallbackFn(0),
        error: CallbackFn(1),
        url: if cfg!(any(windows, target_os = "android")) {
            "http://tauri.localhost"
        } else {
            "tauri://localhost"
        }
        .parse()
        .expect("mock invoke URL"),
        body: InvokeBody::Json(serde_json::json!({
            "input": {
                "parentThreadId": "invalid",
                "parentTurnId": null,
                "expectedParentRevision": 0,
                "taskName": "检查边界",
                "content": [{"type": "text", "text": "检查"}]
            }
        })),
        headers: Default::default(),
        invoke_key: INVOKE_KEY.to_owned(),
    };
    let response = get_ipc_response(&webview, request).expect_err("handler must reject input");
    assert!(
        response.to_string().contains("INVALID_PARAMS"),
        "registered task handler must return its validation error, got {response}"
    );
    cleanup(&run_dir);
}

/// Capability 必须覆盖生产 handler 的完整命令集合；单独收紧一个 command 时若遗漏其余命令，
/// Tauri 会把它们统一隐藏成 `Command not found`，导致应用在 sidecar spawn 前失效。
#[test]
fn desktop_capability_covers_every_registered_command() {
    use std::collections::BTreeSet;

    let handler_source = include_str!("../../../src/lib.rs");
    let registered = handler_source
        .lines()
        .filter_map(|line| {
            let marker = line.find("::ja_")? + 2;
            let name = line[marker..].trim_end_matches(',').trim();
            name.starts_with("ja_").then(|| name.to_owned())
        })
        .collect::<BTreeSet<_>>();
    let permission_source = format!(
        "{}\n{}",
        include_str!("../../../permissions/desktop_commands.toml"),
        include_str!("../../../permissions/thread_seen.toml")
    );
    let allowed = permission_source
        .split('"')
        .filter(|value| value.starts_with("ja_"))
        .map(str::to_owned)
        .collect::<BTreeSet<_>>();

    assert!(
        !registered.is_empty(),
        "production handler command catalog is empty"
    );
    assert_eq!(
        allowed, registered,
        "desktop ACL drifted from ja_command_handler"
    );
    assert!(
        include_str!("../../../capabilities/default.json")
            .contains("\"allow-ja-desktop-commands\"")
    );
}

/// 每次 root 选择断言后只删除测试拥有的临时树，不接触真实 settings 目录。
fn cleanup(path: &PathBuf) {
    let _ = fs::remove_dir_all(path);
    let _ = fs::remove_file(path);
}

#[cfg(debug_assertions)]
/// 验证未设置 debug 轨迹环境时不会猜测或接触文件，使诊断保持显式启用且位于普通 app-data 路径之外。
#[test]
fn debug_exit_trace_without_environment_writes_nothing() {
    let root = test_root("exit-trace-unset");
    let path = root.join("events.log");
    let trace = super::debug_exit_trace_from_lookup(|_| None);
    trace.record(super::DebugExitTraceEvent::RequestEntered);
    assert!(!path.exists());
    cleanup(&root);
}

#[cfg(debug_assertions)]
/// 验证可接受的轨迹路径直属 canonical debug runtime 目录，且只输出冻结的生命周期词汇与顺序。
#[test]
fn debug_exit_trace_writes_fixed_event_sequence() {
    let root = test_root("exit-trace-valid");
    fs::create_dir_all(&root).expect("trace runtime root");
    let path = root.join("events.log");
    let trace = super::debug_exit_trace_from_lookup(|name| match name {
        super::EXIT_TRACE_PATH_ENV => Some(path.clone().into_os_string()),
        super::E2E_RUNTIME_ROOT_ENV => Some(root.clone().into_os_string()),
        _ => None,
    });
    // 测试在自身文件维护有限序列，避免生产状态机暴露仅供断言使用的枚举集合。
    for event in [
        super::DebugExitTraceEvent::RequestEntered,
        super::DebugExitTraceEvent::RequestReturned,
        super::DebugExitTraceEvent::ExitEntered,
        super::DebugExitTraceEvent::ExitReturned,
    ] {
        trace.record(event);
    }
    let content = fs::read_to_string(&path).expect("trace file");
    assert_eq!(
        content,
        "stage=exit_requested_enter\nstage=exit_requested_return\nstage=exit_enter\nstage=exit_return\n"
    );
    cleanup(&root);
}

#[cfg(not(debug_assertions))]
/// 验证 release 构建使用编译期禁用的轨迹边界，无法从进程环境取得目标路径。
#[test]
fn release_exit_trace_hook_is_compile_time_disabled() {
    let trace = super::debug_exit_trace_from_environment();
    assert_eq!(trace, super::DebugExitTrace);
}
#[cfg(windows)]
/// 晚创建窗口筛选必须只接纳真实渲染类、保持首次枚举顺序并去重。
#[test]
fn native_drop_selects_only_unique_webview_render_targets() {
    let targets = super::native_drop::interface::select_render_target_ids([
        (10, "WRY_WEBVIEW".to_owned()),
        (20, "Chrome_RenderWidgetHostHWND".to_owned()),
        (20, "Chrome_RenderWidgetHostHWND".to_owned()),
        (30, "Chrome_WidgetWin_1".to_owned()),
        (40, "Chrome_RenderWidgetHostHWND".to_owned()),
    ]);

    assert_eq!(targets, vec![20, 40]);
}

#[cfg(windows)]
/// 恶意或异常窗口树不能让 COM 注册数量无界增长，筛选仍保留最先出现的八个命中面。
#[test]
fn native_drop_render_target_selection_is_bounded() {
    let targets = super::native_drop::interface::select_render_target_ids(
        (1..=12).map(|id| (id, "Chrome_RenderWidgetHostHWND".to_owned())),
    );

    assert_eq!(targets, (1..=8).collect::<Vec<_>>());
}

#[cfg(windows)]
/// HWND DPI 必须按 96 基准投影，并在 Win32 查询失败的零值上关闭事件，不能错投 Composer。
#[test]
fn native_drop_scale_factor_uses_windows_dpi_contract() {
    assert_eq!(
        super::native_drop::interface::scale_factor_from_dpi(96),
        Some(1.0)
    );
    assert_eq!(
        super::native_drop::interface::scale_factor_from_dpi(144),
        Some(1.5)
    );
    assert_eq!(
        super::native_drop::interface::scale_factor_from_dpi(0),
        None
    );
}

#[cfg(windows)]
/// Explorer 的等价 verbatim spelling 必须在 Shell 边界收敛，设备 namespace 仍失败关闭。
#[test]
fn native_drop_normalizes_only_shell_file_namespaces() {
    use std::path::{Path, PathBuf};

    assert_eq!(
        super::native_drop::interface::normalize_shell_drop_path(Path::new(
            r"\\?\C:\workspace\file.txt"
        )),
        Some(PathBuf::from(r"C:\workspace\file.txt"))
    );
    assert_eq!(
        super::native_drop::interface::normalize_shell_drop_path(Path::new(
            r"\\?\UNC\server\share\file.txt"
        )),
        Some(PathBuf::from(r"\\server\share\file.txt"))
    );
    assert_eq!(
        super::native_drop::interface::normalize_shell_drop_path(Path::new(r"\\.\PIPE\ja")),
        None
    );
}

#[cfg(windows)]
/// reload 的差量计划必须保留稳定 HWND，仅撤销消失目标并安装新目标。
#[test]
fn native_drop_refresh_plan_does_not_accumulate_reload_targets() {
    let plan = super::native_drop::interface::plan_target_refresh(&[20, 40], &[40, 60, 60]);

    assert_eq!(plan.revoke, vec![20]);
    assert_eq!(plan.install, vec![60]);
}

#[cfg(windows)]
/// 退出清理必须幂等且永久关闭 admission，避免迟到 page-load refresh 留下 COM target。
#[test]
fn native_drop_shutdown_is_idempotent_and_complete_without_targets() {
    let host = super::native_drop::NativeDropTargetHost::default();

    assert!(!host.is_shutdown_complete());
    host.shutdown().expect("first native drop shutdown");
    host.shutdown().expect("idempotent native drop shutdown");
    assert!(host.is_shutdown_complete());
}

#[cfg(windows)]
/// COM host 一旦绑定 WebView UI apartment，其他线程不得伪装成可撤销目标的 owner。
#[test]
fn native_drop_rejects_cross_apartment_lifecycle_access() {
    let host = super::native_drop::NativeDropTargetHost::default();
    host.claim_ui_thread().expect("claim native drop UI thread");
    let foreign_host = host.clone();

    let error = std::thread::spawn(move || foreign_host.require_claimed_ui_thread())
        .join()
        .expect("foreign native drop thread")
        .expect_err("foreign apartment must be rejected");
    assert_eq!(
        error.code,
        super::native_drop::interface::NativeDropTargetErrorCode::StateUnavailable
    );
    host.shutdown().expect("owner apartment shutdown");
    assert!(host.is_shutdown_complete());
}
