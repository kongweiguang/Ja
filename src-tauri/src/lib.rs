// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

/// 把适用于所有 Tauri runtime 的命令集中在一个事实来源，避免生产 handler 与
/// MockRuntime 注册测试发生漂移。仅桌面命令由调用方补充，因为具体的 Wry `AppHandle`
/// 参数无法为 MockRuntime 编译。
macro_rules! ja_command_handler {
    ([$($extra:path),* $(,)?]) => {
        tauri::generate_handler![
        crate::app_runtime::ja_runtime_start,
        crate::app_runtime::ja_runtime_stop,
        crate::app_runtime::ja_runtime_state,
        crate::app_runtime::ja_runtime_storage_info,
        crate::app_runtime::ja_runtime_general_workspace,
        crate::app_runtime::ja_runtime_recovery_state,
        crate::app_runtime::ja_runtime_acknowledge_recovery,
        crate::app_runtime::ja_runtime_workspace_open,
        crate::app_runtime::ja_runtime_query,
        crate::app_runtime::ja_approval_respond,
        crate::app_runtime::ja_turn_start,
        crate::app_runtime::ja_turn_cancel,
        crate::app_runtime::ja_turn_steer,
        crate::app_runtime::ja_turn_follow_up,
        crate::app_runtime::ja_tool_artifact_read,
        crate::app_runtime::ja_configuration_read,
        crate::app_runtime::ja_configuration_patch,
        crate::app_runtime::ja_configuration_replace,
        crate::app_runtime::ja_configuration_reset,
        crate::app_runtime::ja_credential_set,
        crate::app_runtime::ja_credential_delete,
        crate::app_runtime::ja_workspace_list,
        crate::app_runtime::ja_thread_create,
        crate::app_runtime::ja_thread_list,
        crate::app_runtime::ja_thread_search,
        crate::app_runtime::ja_thread_read,
        crate::app_runtime::ja_thread_rename,
        crate::app_runtime::ja_thread_preferences_update,
        crate::app_runtime::ja_thread_compact,
        crate::app_runtime::ja_thread_archive,
        crate::app_runtime::ja_thread_delete,
        crate::attachments::interface::commands::ja_attachment_import,
        crate::attachments::interface::commands::ja_attachment_discard,
        crate::review::interface::commands::ja_review_catalog,
        crate::review::interface::commands::ja_review_snapshot,
        crate::review::interface::commands::ja_review_file_diff,
        crate::review::interface::commands::ja_review_apply,
        crate::review::interface::commands::ja_review_cancel,
        crate::review::interface::commands::ja_turn_change_set_read,
        crate::workspace::interface::query::ja_workspace_tree,
        crate::workspace::interface::query::ja_workspace_read_file,
        crate::workspace::interface::query::ja_workspace_search,
        crate::workspace::interface::mutation::ja_workspace_create_entry,
        crate::workspace::interface::mutation::ja_workspace_save_file,
        crate::workspace::interface::mutation::ja_workspace_move_entry,
        crate::workspace::interface::mutation::ja_workspace_trash_prepare,
        crate::workspace::interface::mutation::ja_workspace_trash_commit,
        crate::workspace::interface::mutation::ja_workspace_import_drop,
        crate::workspace::interface::open_with::ja_workspace_open_targets,
        crate::workspace::interface::open_with::ja_workspace_open,
        crate::terminal::commands::ja_terminal_profiles,
        crate::terminal::commands::ja_terminal_open,
        crate::terminal::commands::ja_terminal_close_all,
        crate::terminal::commands::ja_terminal_drop,
        crate::terminal::commands::ja_terminal_input,
        crate::terminal::commands::ja_terminal_resize,
        crate::terminal::commands::ja_terminal_poll,
        crate::terminal::commands::ja_terminal_scrollback,
        crate::terminal::commands::ja_terminal_close,
        $($extra),*
        ]
    };
}

pub mod app_runtime;
pub(crate) mod attachments;
pub(crate) mod diagnostics;
pub(crate) mod native_shortcuts;
pub mod preview;
pub mod review;
pub mod terminal;
pub mod workspace;

use app_runtime::interface::app_tray;
use app_runtime::{
    EventEmitError, EventSink, HomeLayout, RPC_FRAME_EVENT, RuntimeHost, cleanup_on_exit,
    cleanup_on_exit_until, prepare_run_dir,
};
use std::ffi::OsString;
use std::fs;
#[cfg(debug_assertions)]
use std::io::Write;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tauri::{Emitter, Manager, RunEvent, webview::PageLoadEvent};

const MAIN_WINDOW_LABEL: &str = "main";
const WORKSPACE_NATIVE_DROP_EVENT: &str = "ja://workspace-native-drop";

/// 只携带不透明、短生命周期的 Native Drop capability 与逻辑坐标；
/// 源文件绝对路径始终留在 Rust 的一次性 plan 内。
#[derive(Clone, Debug, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct WorkspaceNativeDropEventDto {
    pub(crate) drop_token: String,
    pub(crate) x: f64,
    pub(crate) y: f64,
}

#[cfg(desktop)]
use tauri_plugin_window_state::StateFlags as WindowStateFlags;

#[cfg(desktop)]
/// 只为用户可见的唯一 Workbench 持久化原生窗口状态，辅助 Preview 窗口不得继承或覆盖主布局。
pub(crate) fn persist_main_window_state(label: &str) -> bool {
    label == "main"
}

#[cfg(desktop)]
/// 显式限定原生偏好边界，不继承插件的全 flags 默认值；后者会把可见性、装饰或全屏变化
/// 持久化到 Xcode 风格布局契约之外。
pub(crate) const MAIN_WINDOW_STATE_FLAGS: WindowStateFlags = WindowStateFlags::POSITION
    .union(WindowStateFlags::SIZE)
    .union(WindowStateFlags::MAXIMIZED);

#[cfg(debug_assertions)]
pub(crate) const EXIT_TRACE_PATH_ENV: &str = "JA_E2E_EXIT_TRACE_PATH";
#[cfg(debug_assertions)]
pub(crate) const E2E_RUNTIME_ROOT_ENV: &str = "JA_E2E_RUNTIME_ROOT";

/// 固定且仅用于 debug 的生命周期词汇；封闭取值可以阻止路径、ID、错误或 secret 进入 E2E 轨迹文件。
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum DebugExitTraceEvent {
    RequestEntered,
    RequestReturned,
    ExitEntered,
    ExitReturned,
}

impl DebugExitTraceEvent {
    /// 把事件映射为稳定的单行表示，避免诊断消费者依赖 Debug 格式。
    #[cfg(debug_assertions)]
    const fn line(self) -> &'static str {
        match self {
            Self::RequestEntered => "stage=exit_requested_enter",
            Self::RequestReturned => "stage=exit_requested_return",
            Self::ExitEntered => "stage=exit_enter",
            Self::ExitReturned => "stage=exit_return",
        }
    }
}

/// 持有在 `app.run` 前一次性捕获的可选 debug 轨迹目标。
/// Release 构建保留相同零状态形状，但不读取环境或执行文件 IO，确保诊断不影响产品行为。
#[cfg(debug_assertions)]
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub(crate) struct DebugExitTrace {
    path: Option<PathBuf>,
}

#[cfg(not(debug_assertions))]
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub(crate) struct DebugExitTrace;

impl DebugExitTrace {
    /// 当 debug E2E 契约未完整满足时构造禁用状态，绝不隐式猜测 fallback 路径。
    #[cfg(debug_assertions)]
    const fn disabled() -> Self {
        Self { path: None }
    }

    /// 让 release 轨迹状态保持零大小并永久禁用，生产二进制不存在环境变量控制的目标。
    #[cfg(not(debug_assertions))]
    const fn disabled() -> Self {
        Self
    }

    /// 以 best-effort 方式追加一条固定记录；原生事件循环退出时，诊断不得改变清理结果或触发 panic。
    #[cfg(debug_assertions)]
    pub(crate) fn record(&self, event: DebugExitTraceEvent) {
        let Some(path) = self.path.as_ref() else {
            return;
        };
        let Ok(mut file) = fs::OpenOptions::new().create(true).append(true).open(path) else {
            return;
        };
        let _ = writeln!(file, "{}", event.line());
    }

    /// 把 release 轨迹调用编译为空操作，生产环境不读取 E2E 环境变量，也不打开诊断文件。
    #[cfg(not(debug_assertions))]
    fn record(&self, _event: DebugExitTraceEvent) {}
}

/// 每次 app run 只读取并验证一次冻结的 debug 轨迹配置。
/// 目标必须是绝对路径、父目录必须已存在且 canonical，并以 canonical 的
/// `JA_E2E_RUNTIME_ROOT` 本身作为直接父目录。
#[cfg(debug_assertions)]
fn debug_exit_trace_from_environment() -> DebugExitTrace {
    debug_exit_trace_from_lookup(|name| std::env::var_os(name))
}

/// Release 二进制刻意不提供环境变量控制的轨迹路径，避免生产行为被测试配置改变。
#[cfg(not(debug_assertions))]
fn debug_exit_trace_from_environment() -> DebugExitTrace {
    DebugExitTrace::disabled()
}

/// 通过注入 lookup 保证 debug 环境测试确定性，避免与其他测试并发修改进程级环境状态。
#[cfg(debug_assertions)]
pub(crate) fn debug_exit_trace_from_lookup<F>(lookup: F) -> DebugExitTrace
where
    F: Fn(&str) -> Option<OsString>,
{
    let Some(raw_path) = lookup(EXIT_TRACE_PATH_ENV) else {
        return DebugExitTrace::disabled();
    };
    let Some(raw_runtime_root) = lookup(E2E_RUNTIME_ROOT_ENV) else {
        return DebugExitTrace::disabled();
    };
    let path = PathBuf::from(raw_path);
    let runtime_root = PathBuf::from(raw_runtime_root);
    if !path.is_absolute() || !runtime_root.is_absolute() || path.file_name().is_none() {
        return DebugExitTrace::disabled();
    }
    let Ok(runtime_root) = fs::canonicalize(runtime_root) else {
        return DebugExitTrace::disabled();
    };
    let Ok(runtime_metadata) = fs::metadata(&runtime_root) else {
        return DebugExitTrace::disabled();
    };
    if !runtime_metadata.is_dir() {
        return DebugExitTrace::disabled();
    }
    let Some(parent) = path.parent() else {
        return DebugExitTrace::disabled();
    };
    let Ok(parent) = fs::canonicalize(parent) else {
        return DebugExitTrace::disabled();
    };
    if parent != runtime_root {
        return DebugExitTrace::disabled();
    }
    if let Ok(metadata) = fs::symlink_metadata(&path)
        && (metadata.file_type().is_symlink() || !metadata.is_file())
    {
        return DebugExitTrace::disabled();
    }
    DebugExitTrace { path: Some(path) }
}

/// 构建并运行唯一 Tauri 组合根，使托管资源、命令、路径脱敏的 Native Drop 投影和
/// 最终清理/强制恢复边界都只注册一次。核心命令清单由 `ja_command_handler!` 统一维护，
/// MockRuntime 回归测试与生产入口共享同一事实来源；preview 命令因其具体
/// Wry `AppHandle` 参数而保留在这个桌面组合根中。
/// 桌面 `App::run` 委托给不返回的原生事件循环，因此四阶段轨迹止于 `ExitReturned`，
/// 不伪造无法到达的 `run` 返回后标记。
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let exit_trace = debug_exit_trace_from_environment();
    #[cfg(debug_assertions)]
    let callback_trace = exit_trace.clone();
    #[cfg(not(debug_assertions))]
    let callback_trace = exit_trace;
    let builder = tauri::Builder::default();
    // Tauri 要求 single-instance 最先注册，使竞争失败的进程在其他插件或 sidecar 初始化前退出。
    #[cfg(desktop)]
    let builder = builder.plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
        app_tray::show_main_window(app);
    }));
    // single-instance 之后的插件顺序对应用户可见的信任路径：目录选择、安全外链、
    // 主动授权的通知、显式剪贴板写入、固定形状诊断，最后才恢复窗口。
    let builder = builder.plugin(tauri_plugin_dialog::init());
    #[cfg(desktop)]
    let builder = builder.plugin(tauri_plugin_opener::init());
    #[cfg(desktop)]
    let builder = builder.plugin(tauri_plugin_notification::init());
    #[cfg(desktop)]
    let builder = builder.plugin(tauri_plugin_clipboard_manager::init());
    #[cfg(desktop)]
    let builder = builder.plugin(tauri_plugin_log::Builder::new().skip_logger().build());
    #[cfg(desktop)]
    let builder = builder.plugin(
        tauri_plugin_window_state::Builder::default()
            .with_state_flags(MAIN_WINDOW_STATE_FLAGS)
            .with_filter(persist_main_window_state)
            .build(),
    );
    // 在任何 page-load callback 前托管快捷键 host，使首次加载和每次 hard reload
    // 都能以 fail-closed 方式轮换 renderer epoch。
    let native_shortcuts = native_shortcuts::NativeShortcutHost::default();
    let page_load_shortcuts = native_shortcuts.clone();
    #[cfg(windows)]
    let setup_shortcuts = native_shortcuts.clone();
    let builder = builder
        .manage(native_shortcuts)
        // 同一 managed trace 覆盖窗口销毁与托盘显式退出；release 状态为零大小且永久禁用。
        .manage(exit_trace)
        .manage(app_tray::AppExitCoordinator::default())
        .on_page_load(move |webview, payload| {
            if webview.label() == MAIN_WINDOW_LABEL && payload.event() == PageLoadEvent::Started {
                webview
                    .state::<app_tray::AppExitCoordinator>()
                    .set_renderer_ready(false);
                if let Err(error) = page_load_shortcuts.rotate_main_renderer_lease() {
                    tracing::debug!(
                        code = ?error.code,
                        "native shortcut renderer lease could not rotate"
                    );
                }
            }
        });
    let builder = builder.setup(move |app| {
        let home = HomeLayout::new().map_err(|error| {
            tauri::Error::Setup((Box::new(error) as Box<dyn std::error::Error>).into())
        })?;
        // 原生日志与 runtime 数据共用已验证的 Ja home，同时让隔离 Windows profile
        // 不依赖 shell Known Folder 注册状态。
        let tracing_guard = diagnostics::initialize_native_tracing(home.paths().logs_dir())
            .map_err(|error| {
                tauri::Error::Setup((Box::new(error) as Box<dyn std::error::Error>).into())
            })?;
        app.manage(tracing_guard);
        let run_dir = prepare_run_dir(home.paths().run_dir())?;
        let attachment_ingress = Arc::new(attachments::AttachmentIngress::new(&run_dir).map_err(
            |error| tauri::Error::Setup((Box::new(error) as Box<dyn std::error::Error>).into()),
        )?);
        app.manage(attachment_ingress);
        let java_logs_dir = home.paths().java_logs_dir().to_path_buf();
        let home_dir = home.paths().root().to_path_buf();
        let data_dir = home.paths().data_dir().to_path_buf();
        // debug Java/JAR 启动不会消费 bundled resource；跳过无用的 Known Folder 查询，
        // 可以让真实 WebView2 smoke 在私有 profile 下保持确定性。
        #[cfg(debug_assertions)]
        let resource_dir = if std::env::var_os("JA_DEBUG_JAVA").is_some()
            && std::env::var_os("JA_DEBUG_JAR").is_some()
        {
            std::path::PathBuf::new()
        } else {
            app.path().resource_dir()?
        };
        #[cfg(not(debug_assertions))]
        let resource_dir = app.path().resource_dir()?;
        let config = debug_or_bundled_launch_config(
            resource_dir,
            home_dir,
            data_dir,
            run_dir,
            java_logs_dir,
        )?;
        let app_handle = app.handle().clone();
        let sink: EventSink = Arc::new(move |payload| {
            app_handle
                .emit(RPC_FRAME_EVENT, payload)
                .map_err(|_| EventEmitError::DeliveryFailed)
        });
        // 即使需要 recovery 也继续托管 host，使窗口能渲染类型化恢复界面，
        // 而不是在 Tauri 创建 UI surface 前直接让 setup 失败。
        app.manage(RuntimeHost::new(config, sink));
        // 这些 managed host 是 PTY 与 Preview model 的唯一 owner；
        // configuration/auth 仍由 Ja App Server 持有。
        app.manage(terminal::TerminalCommandHost::new());
        let preview_host = preview::PreviewCommandHost::new().map_err(|error| {
            tauri::Error::Setup((Box::new(error) as Box<dyn std::error::Error>).into())
        })?;
        app.manage(preview_host);
        #[cfg(desktop)]
        {
            app_tray::setup_close_to_tray(app)?;
            app_tray::setup_app_tray(app)?;
        }
        #[cfg(windows)]
        {
            let main_webview = app.get_webview(MAIN_WINDOW_LABEL).ok_or_else(|| {
                tauri::Error::Setup(
                    (Box::new(native_shortcuts::NativeShortcutError::new(
                        native_shortcuts::NativeShortcutErrorCode::RegistrationFailed,
                    )) as Box<dyn std::error::Error>)
                        .into(),
                )
            })?;
            native_shortcuts::schedule_main_installation(
                main_webview,
                app.handle().clone(),
                setup_shortcuts,
            )
            .map_err(|error| {
                tauri::Error::Setup((Box::new(error) as Box<dyn std::error::Error>).into())
            })?;
        }
        Ok(())
    });
    let app = builder
        .invoke_handler(ja_command_handler!([
            crate::preview::commands::ja_preview_open,
            crate::preview::commands::ja_preview_navigate,
            crate::preview::commands::ja_preview_layout,
            crate::preview::commands::ja_preview_close,
            crate::preview::commands::ja_preview_events,
            crate::preview::commands::ja_preview_state,
            crate::preview::commands::ja_preview_recover_pending,
            crate::native_shortcuts::ja_native_shortcut_lease_query,
            crate::native_shortcuts::ja_native_shortcut_context_update,
            crate::native_shortcuts::ja_native_shortcut_context_activate,
            crate::app_runtime::interface::app_tray::ja_app_exit_listener_ready,
            crate::app_runtime::interface::app_tray::ja_app_exit_listener_unready,
            crate::app_runtime::interface::app_tray::ja_app_exit_commit,
            crate::app_runtime::interface::app_tray::ja_app_exit_cancel,
            crate::workspace::interface::watch::ja_workspace_watch_start,
            crate::workspace::interface::watch::ja_workspace_watch_rescan,
            crate::workspace::interface::watch::ja_workspace_watch_stop,
        ]))
        .build(tauri::generate_context!())
        .expect("error while building tauri application");
    app.run(move |app_handle, event| match event {
        RunEvent::WindowEvent { label, event, .. } => {
            handle_native_window_event(app_handle, &label, &event);
        }
        RunEvent::ExitRequested { code, .. }
            if handle_full_exit_request_event(app_handle, code, &callback_trace) =>
        {
            // 这是桌面产品中唯一由应用拥有的 programmatic exit 路径。Tauri 会再次发出
            // 携带 `Some(0)` 的 `ExitRequested`，后续交还 Tauri，避免清理和轨迹循环或重复。
            app_handle.exit(0);
        }
        RunEvent::Exit => {
            callback_trace.record(DebugExitTraceEvent::ExitEntered);
            let host = app_handle.state::<RuntimeHost>();
            let terminal = app_handle.state::<terminal::TerminalCommandHost>();
            let native_shortcuts = app_handle.state::<native_shortcuts::NativeShortcutHost>();
            let preview = app_handle.state::<preview::PreviewCommandHost>();
            let attachments = app_handle.state::<Arc<attachments::AttachmentIngress>>();
            let preview_empty = preview.manager().active_count().unwrap_or(usize::MAX) == 0;
            let workspace_watchers_empty = workspace::is_shutdown_complete();
            if !host.exit_ready()
                || !terminal.is_empty()
                // Registry 条目可以真实地延迟到 controller destroy；退出门禁只要求 callback 已撤销。
                || !native_shortcuts.is_revoked_for_controller_close()
                || !preview_empty
                || !workspace_watchers_empty
                || !attachments.is_shutdown_complete()
            {
                host.record_forced_exit();
                tracing::error!("runtime Exit reached before cleanup confirmation");
            }
            callback_trace.record(DebugExitTraceEvent::ExitReturned);
        }
        _ => {}
    });
}

/// 在签发不透明 token 前把原生物理坐标换算为 renderer CSS 坐标。
/// issuer closure 只对受信任主窗口和有效有限坐标求值，防止辅助窗口或畸形事件消费 Drop capability。
pub(crate) fn build_native_drop_notification<F>(
    label: &str,
    physical_x: f64,
    physical_y: f64,
    scale_factor: f64,
    issue_token: F,
) -> Result<Option<WorkspaceNativeDropEventDto>, workspace::WorkspaceError>
where
    F: FnOnce() -> Result<String, workspace::WorkspaceError>,
{
    if label != MAIN_WINDOW_LABEL
        || !physical_x.is_finite()
        || !physical_y.is_finite()
        || !scale_factor.is_finite()
        || scale_factor <= 0.0
    {
        return Ok(None);
    }
    let drop_token = issue_token()?;
    Ok(Some(WorkspaceNativeDropEventDto {
        drop_token,
        x: physical_x / scale_factor,
        y: physical_y / scale_factor,
    }))
}

/// 接纳 OS Drop 时不向 WebView 转发源路径；接纳或投递失败只记录固定分类，
/// 确保文件路径与不透明 token 都不会经诊断泄漏。
fn handle_native_window_event<R: tauri::Runtime>(
    app_handle: &tauri::AppHandle<R>,
    label: &str,
    event: &tauri::WindowEvent,
) {
    let tauri::WindowEvent::DragDrop(tauri::DragDropEvent::Drop { paths, position }) = event else {
        return;
    };
    if label != MAIN_WINDOW_LABEL {
        return;
    }
    let Some(window) = app_handle.get_webview_window(MAIN_WINDOW_LABEL) else {
        tracing::warn!("native workspace drop ignored because the main window is unavailable");
        return;
    };
    let Ok(scale_factor) = window.scale_factor() else {
        tracing::warn!("native workspace drop ignored because window scale is unavailable");
        return;
    };
    match build_native_drop_notification(label, position.x, position.y, scale_factor, || {
        workspace::issue_native_drop(paths.iter().cloned())
    }) {
        Ok(Some(payload)) => {
            if app_handle
                .emit_to(MAIN_WINDOW_LABEL, WORKSPACE_NATIVE_DROP_EVENT, payload)
                .is_err()
            {
                tracing::warn!("native workspace drop notification could not be delivered");
            }
        }
        Ok(None) => {}
        Err(_) => tracing::warn!("native workspace drop was rejected"),
    }
}

/// 只处理首次无 code 的退出请求，并报告是否应向 Tauri 发出唯一一次显式 `exit(0)`。
/// 携带 code 的请求已由 Tauri 拥有（包括 restart/非零语义），因此这里不重复清理或记录轨迹。
pub(crate) fn handle_full_exit_request_event<R: tauri::Runtime>(
    app_handle: &tauri::AppHandle<R>,
    code: Option<i32>,
    trace: &DebugExitTrace,
) -> bool {
    if code.is_some() {
        return false;
    }
    trace.record(DebugExitTraceEvent::RequestEntered);
    handle_full_exit_requested(app_handle);
    trace.record(DebugExitTraceEvent::RequestReturned);
    true
}

/// 在同一个绝对 deadline 内关闭全部 Rust owner，包括 child Preview WebView 和原生 workspace watcher。
/// Preview 自行执行绑定身份的 close ACK，因为应用级 WebView 枚举无法可靠发现 child WebView。
/// callback 执行时最终窗口已进入销毁流程，清理失败不能阻止退出并遗留无头进程；随后由
/// `RunEvent::Exit` 记录 recovery，既有进程树与 Drop 清理保留为最后安全边界。
fn handle_full_exit_requested<R: tauri::Runtime>(app_handle: &tauri::AppHandle<R>) {
    let deadline = Instant::now()
        .checked_add(Duration::from_secs(20))
        .unwrap_or_else(|| Instant::now() + Duration::from_secs(20));
    let runtime = app_handle.state::<RuntimeHost>();
    let attachments = app_handle.state::<Arc<attachments::AttachmentIngress>>();
    let terminal = app_handle.state::<terminal::TerminalCommandHost>();
    let native_shortcuts = app_handle.state::<native_shortcuts::NativeShortcutHost>();
    let preview = app_handle.state::<preview::PreviewCommandHost>();
    let native_shortcut_result = native_shortcuts.shutdown_until(deadline);
    let terminal_result = terminal.shutdown_until(deadline);
    let preview_result = preview.shutdown_until(app_handle, deadline);
    let workspace_watcher_result = workspace::shutdown_all_until(deadline);
    let attachment_result = attachments.shutdown();
    let runtime_result = cleanup_on_exit_until(&runtime, deadline);
    if !native_shortcut_result
        .as_ref()
        .is_ok_and(|report| report.admission_closed)
        || !native_shortcuts.is_revoked_for_controller_close()
        || terminal_result.is_err()
        || !preview_result.as_ref().is_ok_and(|report| report.complete)
        || workspace_watcher_result.is_err()
        || attachment_result.is_err()
        || runtime_result.is_err()
    {
        tracing::error!("application cleanup did not complete before exit deadline");
    }
}

/// 从托盘或其它显式应用级入口进入唯一完整退出路径；调用方不得直接 `exit(0)` 绕过 owner 清理。
pub(crate) fn request_full_exit<R: tauri::Runtime>(app_handle: &tauri::AppHandle<R>) {
    let trace = app_handle.state::<DebugExitTrace>();
    trace.record(DebugExitTraceEvent::RequestEntered);
    handle_full_exit_requested(app_handle);
    trace.record(DebugExitTraceEvent::RequestReturned);
    app_handle.exit(0);
}

/// 生产与 MockRuntime 测试共用同一清理/阻止策略；首次尝试失败时保留 managed owner 供重试。
pub fn handle_exit_requested(host: &RuntimeHost, api: &tauri::ExitRequestApi) {
    if let Err(error) = cleanup_on_exit(host) {
        tracing::error!(
            code = error.code,
            "runtime cleanup blocked application exit"
        );
        api.prevent_exit();
    }
}

/// 存在仅 host 可见的 Java 25 debug 变量时显式使用；release 构建只能解析 Tauri 受信任目录下的固定原生资源。
fn debug_or_bundled_launch_config(
    resource_dir: std::path::PathBuf,
    home_dir: std::path::PathBuf,
    data_dir: std::path::PathBuf,
    run_dir: std::path::PathBuf,
    java_logs_dir: std::path::PathBuf,
) -> Result<app_runtime::LaunchConfig, app_runtime::RuntimeCommandError> {
    #[cfg(debug_assertions)]
    if let (Some(java), Some(jar)) = (
        std::env::var_os("JA_DEBUG_JAVA"),
        std::env::var_os("JA_DEBUG_JAR"),
    ) {
        return app_runtime::LaunchConfig::debug_java(
            java.into(),
            jar.into(),
            run_dir,
            java_logs_dir,
        );
    }
    app_runtime::bundled_launch_config_with_dirs(
        resource_dir,
        home_dir,
        data_dir,
        run_dir,
        java_logs_dir,
    )
}
