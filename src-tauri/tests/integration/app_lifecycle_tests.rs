// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::app_runtime::{EventEmitError, EventSink, LaunchConfig, RuntimeHost};
use super::attachments::AttachmentIngress;
use super::native_shortcuts::NativeShortcutHost;
use super::preview::PreviewCommandHost;
use super::terminal::TerminalCommandHost;
use crate::runtime_test_support::RuntimeHostHarness;
#[cfg(not(debug_assertions))]
use std::ffi::OsString;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::AtomicUsize;
use std::sync::{Arc, mpsc};
use std::thread;
use std::time::Duration;
use tauri::Manager;

/// 创建完整退出测试独占的临时根，避免修改进程环境或接触真实 app data。
fn test_root(label: &str) -> PathBuf {
    std::env::temp_dir().join(format!(
        "ja-app-lifecycle-{}-{}-{}",
        std::process::id(),
        label,
        uuid::Uuid::new_v4()
    ))
}

/// 只清理本测试创建的唯一临时根；失败留给操作系统临时目录回收，不扩大删除范围。
fn cleanup(path: &PathBuf) {
    let _ = fs::remove_dir_all(path);
    let _ = fs::remove_file(path);
}

/// 在不改变生产启动路径的前提下解析 JDK 25 测试可执行文件，保证 MockRuntime 生命周期测试确定性。
fn full_exit_java() -> PathBuf {
    let java = std::env::var_os("JA_TEST_JAVA")
        .map(PathBuf::from)
        .or_else(|| {
            std::env::var_os("JAVA_HOME").map(|home| {
                PathBuf::from(home).join("bin").join(if cfg!(windows) {
                    "java.exe"
                } else {
                    "java"
                })
            })
        })
        .unwrap_or_else(|| PathBuf::from(if cfg!(windows) { "java.exe" } else { "java" }));
    assert!(java.is_file(), "JA_TEST_JAVA must point to JDK25 java");
    let version = std::process::Command::new(&java)
        .arg("-version")
        .output()
        .expect("inspect JDK25 version");
    let banner = format!(
        "{}{}",
        String::from_utf8_lossy(&version.stdout),
        String::from_utf8_lossy(&version.stderr)
    );
    assert!(
        banner.lines().any(|line| {
            line.split(|character: char| !character.is_ascii_digit())
                .find(|part| !part.is_empty())
                == Some("25")
        }),
        "full-exit test requires JDK25, got {banner:?}"
    );
    java
}

/// 解析 Maven 已构建的唯一 Ja App Server sidecar，避免完整组合根测试维护第二套协议夹具。
/// 路径必须包含当前单模块交付目录 `app-server`，并保留环境变量供 CI 注入外部产物。
fn full_exit_jar() -> PathBuf {
    let jar = std::env::var_os("JA_TEST_JAR")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            PathBuf::from(env!("CARGO_MANIFEST_DIR"))
                .join("..")
                .join("app-server")
                .join("target")
                .join("ja-app-server.jar")
        });
    assert!(
        jar.is_file(),
        "build app-server/target/ja-app-server.jar first"
    );
    jar
}

/// 为 MockRuntime 测试创建唯一的真实 Java sidecar 启动快照，生产环境仍只使用 bundle 资源。
/// 四类目录显式来自同一夹具布局，使测试不会依赖 debug 构造器猜测目录关系。
fn full_exit_fixture_config(run_dir: PathBuf) -> LaunchConfig {
    let java = full_exit_java();
    let jar = full_exit_jar();
    let home_dir = run_dir.join("home");
    let data_dir = run_dir.join("data");
    let sidecar_run_dir = run_dir.join("runtime");
    let java_logs_dir = run_dir.join("logs").join("java");
    fs::create_dir_all(&java_logs_dir).expect("debug Java log directory");
    #[cfg(debug_assertions)]
    {
        LaunchConfig::debug_java(
            java,
            jar,
            home_dir,
            data_dir,
            sidecar_run_dir,
            java_logs_dir,
        )
        .expect("debug Java25 config")
    }
    #[cfg(not(debug_assertions))]
    {
        RuntimeHostHarness::launch_config(
            java,
            vec![OsString::from("-jar"), jar.into_os_string()],
            sidecar_run_dir,
        )
    }
}

/// 创建隔离 run 目录且不接触真实 app-data 路径，使清理失败可检查又不会引入跨测试状态。
fn full_exit_run_dir(label: &str) -> PathBuf {
    let path = test_root(label);
    fs::create_dir_all(&path).expect("full-exit run directory");
    path
}

/// 创建生命周期测试使用的同一固定轨迹目标；release 构建改用零大小的禁用边界。
fn full_exit_trace(run_dir: &Path) -> (PathBuf, super::DebugExitTrace) {
    let path = run_dir.join("exit-trace.log");
    #[cfg(debug_assertions)]
    {
        let runtime_root = run_dir.to_path_buf();
        let trace = super::debug_exit_trace_from_lookup(|name| match name {
            super::EXIT_TRACE_PATH_ENV => Some(path.clone().into_os_string()),
            super::E2E_RUNTIME_ROOT_ENV => Some(runtime_root.clone().into_os_string()),
            _ => None,
        });
        (path, trace)
    }
    #[cfg(not(debug_assertions))]
    {
        (path, super::DebugExitTrace::disabled())
    }
}

#[cfg(debug_assertions)]
/// 等待 MockRuntime join 后检查完整轨迹，使断言观察到与 E2E harness 相同的持久化文件。
fn assert_full_exit_trace(path: &Path) {
    let content = fs::read_to_string(path).expect("full-exit trace");
    assert_eq!(
        content,
        "stage=exit_requested_enter\nstage=exit_requested_return\nstage=exit_enter\nstage=exit_return\n"
    );
}

/// 使用真实 full-exit handler 运行原生 MockRuntime 事件循环，并复现生产在清理故障后的
/// forced-exit recovery 记账；Tauri 在首次无 code 请求后发出的 programmatic exit 不会重复清理。
fn run_full_exit_mock(
    host: RuntimeHost,
    attachment_ingress: Arc<AttachmentIngress>,
    trace: super::DebugExitTrace,
) -> (
    tauri::AppHandle<tauri::test::MockRuntime>,
    tauri::WebviewWindow<tauri::test::MockRuntime>,
    mpsc::Receiver<&'static str>,
    thread::JoinHandle<()>,
) {
    use tauri::RunEvent;
    use tauri::test::{mock_builder, mock_context, noop_assets};

    // MockRuntime 与生产退出处理器必须托管同一附件预览 owner；空 host 只验证 cleanup/readiness，
    // Runtime adapter 绑定同一 RuntimeHost，避免测试用第二套远端 session 生命周期。
    let attachment_preview_host = Arc::new(
        super::attachment_preview::AttachmentPreviewHost::new()
            .expect("mock attachment preview host"),
    );
    let attachment_preview_runtime =
        super::attachment_preview::AttachmentPreviewRuntimeState::new(Arc::new(host.clone()));
    let app = mock_builder()
        .manage(attachment_preview_runtime)
        .manage(attachment_preview_host)
        .manage(host)
        // MockRuntime 必须托管与生产 composition root 相同的退出 owner；否则生产退出
        // handler 读取 AttachmentIngress 时会 panic，并把真正的生命周期断言掩盖为断连。
        .manage(attachment_ingress)
        .manage(TerminalCommandHost::new())
        .manage(NativeShortcutHost::default())
        .manage(PreviewCommandHost::new().expect("产品默认 Preview 配置必须有效"))
        .build(mock_context(noop_assets()))
        .expect("mock full-exit app");
    let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
        .build()
        .expect("mock full-exit window");
    let app_handle = app.handle().clone();
    let (sender, receiver) = mpsc::sync_channel(8);
    #[cfg(debug_assertions)]
    let callback_trace = trace.clone();
    #[cfg(not(debug_assertions))]
    let callback_trace = trace;
    let runner = thread::spawn(move || {
        app.run(move |app_handle, event| match event {
            RunEvent::Ready => {
                let _ = sender.send("ready");
            }
            RunEvent::ExitRequested { code, .. } => {
                let initial =
                    super::handle_full_exit_request_event(app_handle, code, &callback_trace);
                if initial {
                    let _ = sender.send("exit-requested");
                    // Tauri MockRuntime 未实现 request_exit；把携带 code 的后续请求送入同一生产
                    // handler，避免调用不支持的 runtime API 或引入另一套生命周期门禁。
                    let _ =
                        super::handle_full_exit_request_event(app_handle, Some(0), &callback_trace);
                    let _ = sender.send("exit-programmatic-requested");
                }
            }
            RunEvent::Exit => {
                callback_trace.record(super::DebugExitTraceEvent::ExitEntered);
                let host = app_handle.state::<RuntimeHost>();
                let terminal = app_handle.state::<TerminalCommandHost>();
                let native_shortcuts = app_handle.state::<NativeShortcutHost>();
                let preview = app_handle.state::<PreviewCommandHost>();
                let attachments = app_handle.state::<Arc<AttachmentIngress>>();
                let preview_empty = preview.manager().active_count().unwrap_or(usize::MAX) == 0;
                let workspace_watchers_empty = crate::workspace::is_shutdown_complete();
                let exit_safe = host.exit_ready()
                    && terminal.is_empty()
                    && native_shortcuts.is_revoked_for_controller_close()
                    && preview_empty
                    && workspace_watchers_empty
                    && attachments.is_shutdown_complete();
                if !exit_safe {
                    host.record_forced_exit();
                }
                let _ = sender.send(if exit_safe {
                    "exit-ready"
                } else {
                    "exit-unsafe"
                });
                callback_trace.record(super::DebugExitTraceEvent::ExitReturned);
            }
            _ => {}
        });
    });
    (app_handle, window, receiver, runner)
}

/// 验证完整组合根共享同一绝对 deadline；Java、terminal、preview 与 workspace watcher
/// 清理后，只经历一次清理和一次 programmatic exit 请求即可允许原生退出。
#[test]
fn full_exit_request_allows_clean_runtime_cleanup() {
    let run_dir = full_exit_run_dir("full-exit-success");
    let (_trace_path, trace) = full_exit_trace(&run_dir);
    let sink: EventSink = Arc::new(|_| Ok::<(), EventEmitError>(()));
    let host = RuntimeHost::new(full_exit_fixture_config(run_dir.clone()), sink);
    let attachment_ingress = Arc::new(AttachmentIngress::new(&run_dir).expect("test ingress"));
    host.start().expect("test sidecar ready");
    let (app_handle, window, receiver, runner) =
        run_full_exit_mock(host, attachment_ingress, trace);
    assert_eq!(receiver.recv_timeout(Duration::from_secs(5)), Ok("ready"));
    window.close().expect("full-exit close request");
    assert_eq!(
        receiver.recv_timeout(Duration::from_secs(5)),
        Ok("exit-requested")
    );
    assert_eq!(
        receiver.recv_timeout(Duration::from_secs(5)),
        Ok("exit-programmatic-requested")
    );
    assert_eq!(
        receiver.recv_timeout(Duration::from_secs(5)),
        Ok("exit-ready")
    );
    runner.join().expect("mock full-exit runner");
    assert!(app_handle.state::<RuntimeHost>().exit_ready());
    assert!(
        app_handle
            .state::<Arc<AttachmentIngress>>()
            .is_shutdown_complete()
    );
    #[cfg(debug_assertions)]
    assert_full_exit_trace(&_trace_path);
    cleanup(&run_dir);
}

/// 验证 full-exit 清理失败仍能到达原生 Exit 事件、记录 recovery marker，
/// 并在同一单次清理/programmatic-exit 序列后把保留 owner 报告为不安全。
#[test]
fn full_exit_request_allows_forced_exit_after_cleanup_fault() {
    let run_dir = full_exit_run_dir("full-exit-forced");
    let (_trace_path, trace) = full_exit_trace(&run_dir);
    let sink: EventSink = Arc::new(|_| Ok::<(), EventEmitError>(()));
    let failures = Arc::new(AtomicUsize::new(usize::MAX));
    let harness = RuntimeHostHarness::with_exit_control(
        full_exit_fixture_config(run_dir.clone()),
        sink,
        Duration::from_millis(500),
        Arc::clone(&failures),
    );
    let host = harness.host();
    let attachment_ingress = Arc::new(AttachmentIngress::new(&run_dir).expect("test ingress"));
    host.start().expect("test sidecar ready");
    let (app_handle, first_window, receiver, runner) =
        run_full_exit_mock(host, attachment_ingress, trace);
    assert_eq!(receiver.recv_timeout(Duration::from_secs(5)), Ok("ready"));
    first_window.close().expect("first close request");
    assert_eq!(
        receiver.recv_timeout(Duration::from_secs(5)),
        Ok("exit-requested")
    );
    assert_eq!(
        receiver.recv_timeout(Duration::from_secs(5)),
        Ok("exit-programmatic-requested")
    );
    assert_eq!(
        receiver.recv_timeout(Duration::from_secs(5)),
        Ok("exit-unsafe")
    );
    runner.join().expect("mock forced-exit runner");
    let host = app_handle.state::<RuntimeHost>();
    assert!(!host.exit_ready());
    let recovery = host.recovery_state();
    assert!(recovery.required);
    assert!(recovery.recovery_id.is_some());
    assert!(
        app_handle
            .state::<Arc<AttachmentIngress>>()
            .is_shutdown_complete()
    );
    #[cfg(debug_assertions)]
    assert_full_exit_trace(&_trace_path);
    cleanup(&run_dir);
}
