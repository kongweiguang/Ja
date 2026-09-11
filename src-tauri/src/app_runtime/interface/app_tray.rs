// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! Ja 系统托盘与显式退出握手；所有 Tauri 类型只停留在桌面接口边界。

use std::sync::atomic::{AtomicBool, Ordering};
#[cfg(desktop)]
use tauri::{
    App, AppHandle, Emitter, Manager, Runtime, WindowEvent,
    menu::{Menu, MenuItem, PredefinedMenuItem},
    tray::{MouseButton, TrayIconBuilder, TrayIconEvent},
};

#[cfg(desktop)]
use super::desktop_preferences::{CloseBehavior, DesktopPreferences};
#[cfg(desktop)]
use crate::MAIN_WINDOW_LABEL;

pub(crate) const APP_EXIT_REQUESTED_EVENT: &str = "ja://app-exit-requested";
const TRAY_ID: &str = "ja:tray";
const TRAY_MENU_ID_PREFIX: &str = "ja:tray:";

/// 托盘请求的三种封闭动作，菜单 id 不接受任意 renderer 输入。
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum TrayMenuAction {
    Show,
    Hide,
    Quit,
}

impl TrayMenuAction {
    /// 固定菜单 id，避免菜单显示文案变化破坏动作路由。
    const fn menu_id(self) -> &'static str {
        match self {
            Self::Show => "ja:tray:show",
            Self::Hide => "ja:tray:hide",
            Self::Quit => "ja:tray:quit",
        }
    }

    /// 只解析 Ja 自己的封闭菜单集合，忽略其它应用菜单事件。
    pub(crate) fn from_menu_id(menu_id: &str) -> Option<Self> {
        match menu_id.strip_prefix(TRAY_MENU_ID_PREFIX)? {
            "show" => Some(Self::Show),
            "hide" => Some(Self::Hide),
            "quit" => Some(Self::Quit),
            _ => None,
        }
    }
}

/// 托盘退出分发策略；renderer 未完成监听握手时直接走原生清理，避免启动期无法退出。
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum ExitDispatch {
    Renderer,
    Native,
    AlreadyPending,
}

/// 原生进程拥有退出 single-flight；renderer 只在托盘明确发起后才能提交或取消。
#[derive(Default)]
pub(crate) struct AppExitCoordinator {
    renderer_ready: AtomicBool,
    pending: AtomicBool,
}

impl AppExitCoordinator {
    /// page load 与 listener ACK 共同维护可投递事实，不能用窗口可见性推断 renderer 可用。
    pub(crate) fn set_renderer_ready(&self, ready: bool) {
        self.renderer_ready.store(ready, Ordering::Release);
    }

    /// 原子取得唯一退出请求，并在 renderer 尚未 ACK 时选择不会悬挂的原生路径。
    pub(crate) fn begin_exit(&self) -> ExitDispatch {
        if self
            .pending
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .is_err()
        {
            return ExitDispatch::AlreadyPending;
        }
        if self.renderer_ready.load(Ordering::Acquire) {
            ExitDispatch::Renderer
        } else {
            ExitDispatch::Native
        }
    }

    /// 仅消费真实的托盘退出请求，阻止 renderer 任意调用 commit 关闭应用。
    pub(crate) fn take_pending(&self) -> bool {
        self.pending.swap(false, Ordering::AcqRel)
    }

    /// renderer 清理失败时重新开放托盘退出，使用户处理冲突后可以重试。
    pub(crate) fn cancel(&self) {
        self.pending.store(false, Ordering::Release);
    }
}

/// 安装主窗口关闭语义；后台偏好隐藏窗口，退出偏好进入统一完整清理握手。
#[cfg(desktop)]
pub(crate) fn setup_close_to_tray<R: Runtime>(app: &App<R>) -> tauri::Result<()> {
    if let Some(window) = app.get_webview_window(MAIN_WINDOW_LABEL) {
        let window_to_hide = window.clone();
        let app_handle = app.handle().clone();
        window.on_window_event(move |event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                match app_handle.state::<DesktopPreferences>().read() {
                    CloseBehavior::Background => {
                        if window_to_hide.hide().is_err() {
                            tracing::error!("main window could not hide after close request");
                        }
                    }
                    CloseBehavior::Exit => request_exit(&app_handle, false),
                }
            }
        });
    }
    Ok(())
}

/// 安装复用应用图标的原生托盘；左键恢复窗口，菜单提供显示、隐藏和唯一退出入口。
#[cfg(desktop)]
pub(crate) fn setup_app_tray<R: Runtime>(app: &App<R>) -> tauri::Result<()> {
    let show = MenuItem::with_id(
        app,
        TrayMenuAction::Show.menu_id(),
        "显示 Ja",
        true,
        None::<&str>,
    )?;
    let hide = MenuItem::with_id(
        app,
        TrayMenuAction::Hide.menu_id(),
        "隐藏 Ja",
        true,
        None::<&str>,
    )?;
    let quit = MenuItem::with_id(
        app,
        TrayMenuAction::Quit.menu_id(),
        "退出 Ja",
        true,
        None::<&str>,
    )?;
    let separator = PredefinedMenuItem::separator(app)?;
    let menu = Menu::with_items(app, &[&show, &hide, &separator, &quit])?;
    let mut builder = TrayIconBuilder::with_id(TRAY_ID)
        .tooltip("Ja")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_tray_icon_event(|tray, event| {
            if should_show_window(&event) {
                show_main_window(tray.app_handle());
            }
        })
        .on_menu_event(|app, event| {
            if let Some(action) = TrayMenuAction::from_menu_id(event.id().as_ref()) {
                handle_menu_action(app, action);
            }
        });
    if let Some(icon) = app.default_window_icon().cloned() {
        builder = builder.icon(icon);
    }
    builder.build(app)?;
    Ok(())
}

/// 恢复并聚焦主窗口；unminimize 先于 focus，覆盖最小化后又被隐藏的平台状态。
#[cfg(desktop)]
pub(crate) fn show_main_window<R: Runtime>(app: &AppHandle<R>) {
    let Some(window) = app.get_webview_window(MAIN_WINDOW_LABEL) else {
        tracing::error!("tray could not find the main window");
        return;
    };
    if window.show().is_err() || window.unminimize().is_err() || window.set_focus().is_err() {
        tracing::error!("tray could not restore the main window");
    }
}

/// 处理托盘动作；退出先恢复窗口以展示冲突反馈，再由 renderer 或原生路径完成有界清理。
#[cfg(desktop)]
fn handle_menu_action<R: Runtime>(app: &AppHandle<R>, action: TrayMenuAction) {
    match action {
        TrayMenuAction::Show => show_main_window(app),
        TrayMenuAction::Hide => {
            if app
                .get_webview_window(MAIN_WINDOW_LABEL)
                .is_some_and(|window| window.hide().is_err())
            {
                tracing::error!("tray could not hide the main window");
            }
        }
        TrayMenuAction::Quit => request_exit_from_tray(app),
    }
}

/// 只把首次左键或双击视为恢复意图；右键继续交给平台原生菜单。
#[cfg(desktop)]
fn should_show_window(event: &TrayIconEvent) -> bool {
    matches!(
        event,
        TrayIconEvent::Click {
            button: MouseButton::Left,
            ..
        } | TrayIconEvent::DoubleClick {
            button: MouseButton::Left,
            ..
        }
    )
}

/// 托盘退出优先发给已 ACK 的 renderer；投递失败或未就绪时仍执行同一原生完整清理。
#[cfg(desktop)]
fn request_exit_from_tray<R: Runtime>(app: &AppHandle<R>) {
    request_exit(app, true);
}

/// 将系统关闭和托盘退出统一送入现有 renderer 清理握手，避免任一入口绕过 owner 清理。
#[cfg(desktop)]
fn request_exit<R: Runtime>(app: &AppHandle<R>, restore_window: bool) {
    let coordinator = app.state::<AppExitCoordinator>();
    match coordinator.begin_exit() {
        ExitDispatch::AlreadyPending => show_main_window(app),
        ExitDispatch::Native => crate::request_full_exit(app),
        ExitDispatch::Renderer => {
            if restore_window {
                show_main_window(app);
            }
            if app
                .emit_to(MAIN_WINDOW_LABEL, APP_EXIT_REQUESTED_EVENT, ())
                .is_err()
            {
                coordinator.cancel();
                crate::request_full_exit(app);
            }
        }
    }
}

/// renderer 在 listener 注册完成后 ACK，消除托盘点击与前端启动之间的投递竞态。
#[tauri::command]
pub(crate) fn ja_app_exit_listener_ready(state: tauri::State<'_, AppExitCoordinator>) {
    state.set_renderer_ready(true);
}

/// renderer 卸载时撤销 ACK；新的托盘退出不得投递给已经失效的 listener。
#[tauri::command]
pub(crate) fn ja_app_exit_listener_unready(state: tauri::State<'_, AppExitCoordinator>) {
    state.set_renderer_ready(false);
}

/// renderer 完成 Files/Preview 清理后提交唯一 pending 请求，并调度完整原生退出。
#[tauri::command]
pub(crate) fn ja_app_exit_commit(
    app: tauri::AppHandle,
    state: tauri::State<'_, AppExitCoordinator>,
) -> Result<(), &'static str> {
    if !state.take_pending() {
        return Err("APP_EXIT_NOT_REQUESTED");
    }
    let exit_app = app.clone();
    app.run_on_main_thread(move || crate::request_full_exit(&exit_app))
        .map_err(|_| "APP_EXIT_SCHEDULE_FAILED")
}

/// renderer 清理失败时取消 pending 请求，应用保持运行并允许用户修复后重试。
#[tauri::command]
pub(crate) fn ja_app_exit_cancel(state: tauri::State<'_, AppExitCoordinator>) {
    state.cancel();
}
