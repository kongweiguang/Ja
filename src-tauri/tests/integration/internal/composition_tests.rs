// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use std::cell::Cell;
use std::fs;
use std::path::PathBuf;

/// 验证 renderer 载荷仅包含 token 与逻辑坐标，即使原生 issuer closure 捕获了私有路径也不会泄漏。
#[test]
fn native_drop_notification_redacts_paths_and_normalizes_dpi() {
    let private_path = PathBuf::from(r"C:\private\secret.txt");
    let payload = super::build_native_drop_notification("main", 150.0, 75.0, 1.5, move || {
        let _native_only = private_path;
        Ok("drop-token".to_owned())
    })
    .expect("native drop projection")
    .expect("main window notification");
    let value = serde_json::to_value(payload).expect("serialize native drop payload");
    assert_eq!(
        value,
        serde_json::json!({ "dropToken": "drop-token", "x": 100.0, "y": 50.0 })
    );
    assert!(!value.to_string().contains("private"));
    assert!(!value.to_string().contains("secret"));
}

/// 验证辅助窗口既不能消费一次性 Native Drop token，也不会收到 renderer 通知。
#[test]
fn native_drop_notification_ignores_non_main_windows_before_issuance() {
    let issued = Cell::new(false);
    let result = super::build_native_drop_notification("preview_1", 12.0, 24.0, 1.0, || {
        issued.set(true);
        Ok("must-not-be-issued".to_owned())
    })
    .expect("non-main projection");
    assert!(result.is_none());
    assert!(!issued.get());
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
